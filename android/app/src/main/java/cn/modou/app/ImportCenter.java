package cn.modou.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局导入中心：批量导入的后台队列 + 进度持久化（对齐 legado-with-MD3 的导入体验）。
 *
 * 为什么要有它：
 *   - 旧版进度只挂在 ImportActivity 的一个 TextView 上：切到书架/设置再回来，进度就没了
 *     （Activity 重建后旧 callback 还连着旧实例，新页面拿不到任何状态）；批量导几十本时
 *     用户只能干等，也不知道导到哪一本。
 *   - 这里把「导入状态」提升为 App 级单例，每步（节流后）落盘到设置表：
 *       同一进程内任何页面回来都能从 ImportCenter 读到实时进度；
 *       进程被杀后再进来，也能看到「上次导入中断 3/10」而不是一无所知。
 *
 * 执行模型（与 LocalImport 一致，刻意保留串行单槽）：
 *   - 低端机并发解析两本大书 = 以前「闪回桌面」的根因，Legado 的导入同样在后台串行跑；
 *   - 文件级状态记录每一本 pending / importing / ok / fail + 失败原因，
 *     书架横幅与导入页读同一份快照。
 */
final class ImportCenter {

    /** 单个文件的状态（导入页逐行展示） */
    static final class FileState {
        final String name;
        String status = "pending"; // pending / importing / ok / fail
        String msg = "";

        FileState(String n) {
            name = n;
        }
    }

    public interface Listener {
        /** 进度有任何变化（主线程回调），书架横幅 / 导入页据此刷新 */
        void onImportProgress();
    }

    private static final ImportCenter I = new ImportCenter();

    static ImportCenter get() {
        return I;
    }

    private static final String SET_KEY = "importCenter";

    private final Handler MAIN = new Handler(Looper.getMainLooper());
    private final List<FileState> files = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long startedAt = 0;
    private volatile long updatedAt = 0;
    private volatile int done = 0;
    private volatile int ok = 0;
    private volatile int fail = 0;
    private volatile String currentName = "";
    private volatile String stage = "";
    private volatile boolean interrupted = false;
    /** 上次落盘时间：say() 每章都会来一次，300ms 内不再重复写设置表 */
    private volatile long lastPersist = 0;

    private ImportCenter() {
    }

    void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    boolean isRunning() {
        return running.get();
    }

    /** 进程重启后第一次进页面：上次跑了一半的批次标记为「中断」 */
    void noteResume(Context c) {
        if (running.get()) return;
        if (interrupted) return;
        JSONObject saved = load(c);
        if (saved != null && saved.optBoolean("running", false) && saved.optInt("total", 0) > 0
                && saved.optInt("done", 0) < saved.optInt("total", 0)) {
            interrupted = true;
            currentName = saved.optString("current", "");
            stage = "上次导入中断：" + saved.optInt("done", 0) + "/" + saved.optInt("total", 0);
            updatedAt = System.currentTimeMillis();
            persist(c);
        }
    }

    /**
     * 开始一批导入（替换当前批次）。文件级回调由这里统一收：
     * LocalImport 串行执行，onDone 逐本推进计数。
     */
    synchronized void start(Context c, List<Uri> uris, List<String> names, List<Long> sizes) {
        if (running.get()) {
            ModouUi.toast(c, "正在导入，先等当前批次完成");
            return;
        }
        final Context app = c.getApplicationContext();
        files.clear();
        for (int i = 0; i < uris.size(); i++) {
            files.add(new FileState(names.size() > i ? names.get(i) : "文件 " + (i + 1)));
        }
        running.set(true);
        interrupted = false;
        done = 0;
        ok = 0;
        fail = 0;
        startedAt = System.currentTimeMillis();
        updatedAt = startedAt;
        currentName = files.isEmpty() ? "" : files.get(0).name;
        stage = "正在导入 " + files.size() + " 个文件…";
        persist(app);
        notifyUI();

        for (int i = 0; i < uris.size(); i++) {
            final FileState fs = files.get(i);
            LocalImport.enqueue(app, uris.get(i), fs.name,
                    sizes.size() > i ? sizes.get(i) : 0,
                    new LocalImport.Callback() {
                        @Override
                        public void onStage(String text) {
                            fs.status = "importing";
                            currentName = fs.name;
                            stage = text;
                            updatedAt = System.currentTimeMillis();
                            persistThrottled(app);
                            notifyUI();
                        }

                        @Override
                        public void onDone(LocalImport.Res r) {
                            fs.status = r.ok ? "ok" : "fail";
                            fs.msg = r.msg;
                            done++;
                            if (r.ok) ok++;
                            else fail++;
                            updatedAt = System.currentTimeMillis();
                            if (done >= files.size()) {
                                running.set(false);
                                stage = "导入完成：" + ok + " 本成功"
                                        + (fail > 0 ? "，" + fail + " 本失败" : "");
                            } else {
                                stage = "正在导入 " + (done + 1) + "/" + files.size() + " 个文件…";
                            }
                            persist(app);
                            notifyUI();
                        }
                    });
        }
    }

    /** 快照：导入页与书架横幅都只读这一份 */
    synchronized JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("running", running.get());
            o.put("interrupted", interrupted);
            o.put("total", files.size());
            o.put("done", done);
            o.put("ok", ok);
            o.put("fail", fail);
            o.put("current", currentName == null ? "" : currentName);
            o.put("stage", stage == null ? "" : stage);
            o.put("startedAt", startedAt);
            o.put("updatedAt", updatedAt);
            JSONArray arr = new JSONArray();
            for (FileState f : files) {
                JSONObject fo = new JSONObject();
                fo.put("name", f.name == null ? "" : f.name);
                fo.put("status", f.status);
                fo.put("msg", f.msg == null ? "" : f.msg);
                arr.put(fo);
            }
            o.put("files", arr);
        } catch (Exception ignore) {
            /* 快照失败不影响导入 */
        }
        return o;
    }

    private void notifyUI() {
        MAIN.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onImportProgress();
                } catch (Exception ignore) {
                    /* 单个监听器异常不影响其他 */
                }
            }
        });
    }

    private void persistThrottled(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastPersist < 300) return;
        lastPersist = now;
        persist(c);
    }

    private void persist(Context c) {
        try {
            ModouStore.get(c).putSettingStr(SET_KEY, snapshot().toString());
        } catch (Exception ignore) {
            /* 持久化失败不阻塞导入 */
        }
    }

    private JSONObject load(Context c) {
        try {
            String s = ModouStore.get(c).settingStr(SET_KEY, "");
            if (s == null || s.isEmpty()) return null;
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 供 ImportActivity 启动入口复用（书架上横幅点击） */
    static void open(Context c) {
        try {
            c.startActivity(new android.content.Intent(c, ImportActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            /* 打不开就算了 */
        }
    }
}
