package cn.modou.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地书的导入核心：读文件 → 解析 → 入库。
 *
 * v0.6.127 起改成**流式 + 串行**：
 *
 *  - 先把 SAF 的 Uri 复制成 cacheDir 里的临时文件，解析器按文件随机读，
 *    不再把整本书（解压后常常上百 MB）一次性摆进堆里。低端机上 40MB 的合集
 *    以前必 OOM（进程被杀 = 用户看到「点了没反应 / 闪回桌面」）。
 *  - 章节边解析边分批写库，写完即弃，内存峰值只剩当前一章。
 *  - 所有导入走同一条后台队列：连点两本不会再并发两份解析，内存不会翻倍。
 *  - 整段包在 catch Throwable 里：万一还是内存不足，给一句「这本书太大了」，
 *    而不是让进程死掉。
 *
 * id / bookUrl 仍是 `local://文件名#字节数`，与网页版同一条记录，不会导两次出现两本。
 */
final class LocalImport {

    /** 本地书的伪书源，与书架判定本地书用的 LOCAL_SOURCE_URL 一致 */
    static final String LOCAL = "local://";
    /** 单本上限：再大解析出来也装不进内存 */
    static final long MAX_BYTES = 60L * 1024 * 1024;

    static final class Res {
        boolean ok;
        /** 失败原因，或成功时的摘要（用于列表上的提示条） */
        String msg = "";
        String id = "";
        String title = "";
        int chapters;
        /** 真实读到的字节数：有些 provider 报的 size 与文件实际大小不一致 */
        long bytes;
        /** true = 覆盖了一本已存在的书；false = 新加的 */
        boolean updated;
    }

    /* ---------------- 对外：排队执行 ---------------- */

    interface Callback {
        /** 进度文案（主线程） */
        void onStage(String text);

        /** 结束（主线程）；回调后临时文件已清理 */
        void onDone(Res r);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 单本「多久没有进度」就判定卡死。
     *
     * 为什么必须有：SAF 的 InputStream 卡住时（云盘离线、文件被别的 App 独占）read()
     * 可以永远不返回。串行队列一旦被这样一个任务占住，后面排的书全部不动 ——
     * 用户看到的就是「导入到一半，剩下几本怎么点都没反应」，而且换一批再点还是排在同一条死队后面。
     */
    private static final long STALL_MS = 100_000L;

    /** 串行闸门：同一时间只解析一本（并发解析内存翻倍，正是以前「连点两本闪回桌面」的原因） */
    private static final Semaphore SLOT = new Semaphore(1, true);

    private static final AtomicReference<Task> CUR = new AtomicReference<>();

    private static final class Task {
        volatile long tick = System.currentTimeMillis();
        volatile boolean abandoned;
        final AtomicBoolean freed = new AtomicBoolean();
        Callback cb;
        String name = "";

        /** 有任何进展就刷新一下：看门狗据此区分「慢」和「死了」 */
        void touch() {
            tick = System.currentTimeMillis();
        }

        /** 放行队列（只放一次，看门狗超时后原线程回来再 free 不会多放一个） */
        void free() {
            if (freed.compareAndSet(false, true)) SLOT.release();
        }

        void postDone(Res r) {
            if (abandoned || cb == null) return;
            final Res rr = r;
            MAIN.post(() -> cb.onDone(rr));
        }
    }

    private static final ScheduledExecutorService WATCH =
            Executors.newSingleThreadScheduledExecutor((r) -> {
                Thread t = new Thread(r, "modou-import-watch");
                t.setDaemon(true);
                return t;
            });

    static {
        WATCH.scheduleWithFixedDelay(() -> {
            Task t = CUR.get();
            if (t == null || t.abandoned) return;
            if (System.currentTimeMillis() - t.tick <= STALL_MS) return;
            t.abandoned = true;
            CUR.compareAndSet(t, null);
            t.free();
            Res r = new Res();
            r.msg = "读取卡住了（文件可能被其他 App 占用，或在云盘上没下载完），先把它存到本机再试";
            final Res rr = r;
            MAIN.post(() -> {
                if (t.cb != null) t.cb.onDone(rr);
            });
        }, 5, 5, TimeUnit.SECONDS);
    }

    /** 排队导入一本。串行执行：同一时间只有一本在解析。 */
    static void enqueue(Context c, Uri uri, String name, long size, Callback cb) {
        final Context app = c.getApplicationContext();
        final Task task = new Task();
        task.cb = cb;
        task.name = name;
        Thread t = new Thread(() -> {
            boolean got = false;
            try {
                SLOT.acquire();
                got = true;
                if (task.abandoned) return;
                CUR.set(task);
                task.touch();
                Res r;
                try {
                    r = run(app, uri, name, size, (text) -> {
                        task.touch();
                        if (!task.abandoned && cb != null) MAIN.post(() -> cb.onStage(text));
                    });
                } catch (OutOfMemoryError oom) {
                    r = new Res();
                    r.msg = "内存不足：这本书太大，或含大量内嵌图片";
                } catch (Throwable x) {
                    r = new Res();
                    r.msg = "导入失败：" + brief(x);
                }
                task.postDone(r);
            } catch (InterruptedException ie) {
                Res r = new Res();
                r.msg = "已取消";
                task.postDone(r);
            } finally {
                CUR.compareAndSet(task, null);
                if (got) task.free();
            }
        }, "modou-import");
        // 导入是长任务：降一档优先级，别和滚动/翻页抢 CPU
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /** 进度回调（后台线程） */
    private interface Stage {
        void say(String text);
    }

    private LocalImport() {
    }

    /* ---------------- 导入主流程 ---------------- */

    private static Res run(Context c, Uri uri, String name, long size, Stage stage) {
        Res r = new Res();
        String ext = LocalScan.extOf(name);
        if (!"txt".equals(ext) && !"epub".equals(ext)) {
            r.msg = "仅支持 .txt / .epub";
            return r;
        }
        if (size > MAX_BYTES) {
            r.msg = "文件超过 60MB";
            return r;
        }

        File tmp = null;
        ModouStore store = ModouStore.get(c);
        String id = null;
        try {
            // 空间够不够，先算一笔：正文 HTML 大约是原文件的 1.5–2 倍，再留出写库与回滚余量。
            // 不预先拦的话，写到一半 SQLite 报 disk is full，用户只看到一句「导入失败」，
            // 还以为是书的问题 —— 实际是手机满了，删几本就好。
            if (size > 0) {
                long need = size * 3 + 40L * 1024 * 1024;
                long free = freeBytes(c);
                if (free > 0 && free < need) {
                    r.msg = "存储空间不足：还剩 " + mb(free) + "，导入这本约需 " + mb(need) + "，先删点别的再试";
                    return r;
                }
            }
            stage.say("正在读取《" + strip(name) + "》…");
            tmp = copyToCache(c, uri, ext, size, stage);
            long bytes = tmp.length();
            if (bytes <= 0) {
                r.msg = "文件为空";
                return r;
            }
            if (bytes > MAX_BYTES) {
                r.msg = "文件超过 60MB";
                return r;
            }

            String bookUrl = LOCAL + name + "#" + bytes;
            id = LOCAL + "||" + bookUrl;
            String existed = store.get("books", id);
            if (existed != null) {
                // 重新导入 = 覆盖：先清旧章节，免得新旧章数不一致留下孤儿正文
                store.delChaptersOf(id);
                store.del("books", id);
                BookFiles.clearImages(c, id); // 旧版本内联在正文里的图也一起换掉
            }

            Writer w = new Writer(c, store, id, name, stage);
            String title, author, intro, cover = "";
            int total;
            if ("txt".equals(ext)) {
                Charset cs = TxtBook.detectCharset(head(tmp, 1 << 16));
                try (Reader rd = new InputStreamReader(new FileInputStream(tmp), cs)) {
                    TxtBook.Parsed p = TxtBook.parseStream(rd, name, w);
                    title = p.title;
                    author = p.author;
                    intro = p.intro;
                }
            } else {
                // 插图落文件，章节只留 file:// 地址：内联 base64 会把一本图多的
                // 合集吹到上百 MB，导入慢得离谱，单章还会超过 CursorWindow 的 2MB 上限
                EpubBook.Parsed p = EpubBook.parse(tmp, w, BookFiles.imageSink(c, id));
                title = p.title;
                author = p.author;
                intro = p.intro;
                cover = p.cover;
            }
            w.flush();
            total = w.count();

            if (total == 0) {
                store.delChaptersOf(id);
                r.msg = "没解析到任何章节";
                return r;
            }
            // 目录有 N 章、库里只写进 M 章 = 写到一半失败（最常见是存储空间不够）。
            // 以前这种书照样报「已导入」，用户点进去就是一片「这一章没有正文」。
            int stored = w.written();
            if (stored < total) {
                store.delChaptersOf(id);
                BookFiles.clearImages(c, id);
                r.msg = "只写入了 " + stored + "/" + total + " 章：存储空间可能不够，删点东西再试";
                return r;
            }

            JSONArray chs = new JSONArray();
            List<String> names = w.names();
            for (int i = 0; i < names.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("name", names.get(i));
                o.put("url", LOCAL + "ch/" + i);
                chs.put(o);
            }
            JSONObject rec = new JSONObject();
            rec.put("id", id);
            rec.put("sourceUrl", LOCAL);
            rec.put("sourceName", "本地导入");
            rec.put("name", title == null || title.isEmpty() ? strip(name) : title);
            rec.put("author", author == null ? "" : author);
            rec.put("intro", intro == null ? "" : intro);
            rec.put("coverUrl", cover == null ? "" : cover);
            rec.put("kind", ext.toUpperCase(Locale.ROOT));
            rec.put("lastChapter", names.isEmpty() ? "" : names.get(names.size() - 1));
            rec.put("bookUrl", bookUrl);
            rec.put("group", "默认");
            rec.put("chapters", chs);
            long now = System.currentTimeMillis();
            long added = now;
            if (existed != null) {
                try {
                    added = new JSONObject(existed).optLong("addedAt", now);
                } catch (Exception ignore) {
                    added = now;
                }
            }
            rec.put("addedAt", added);
            rec.put("updatedAt", now);
            // 文件大小：原文件真实字节数（不再另存副本，原文件留在用户自己的文件夹里）
            rec.put("bytes", bytes);
            rec.put("localPath", BookFiles.placeOf(c));
            // 原文件地址（SAF 文档 Uri）：阅读页「编辑本章」保存后要写回这本书，
            // 没有它就只能改书库、够不到手机上的 .txt 原文件
            rec.put("fileUri", String.valueOf(uri));
            store.put("books", rec.toString());

            r.ok = true;
            r.id = id;
            r.bytes = bytes;
            r.title = rec.optString("name", strip(name));
            r.chapters = total;
            r.updated = existed != null;
            r.msg = (r.updated ? "已更新《" : "已导入《") + r.title + "》" + total + " 章";
        } catch (OutOfMemoryError oom) {
            cleanup(c, store, id);
            r.msg = "内存不足：这本书太大，或含大量内嵌图片";
        } catch (Exception e) {
            cleanup(c, store, id);
            String m = brief(e);
            String low = m.toLowerCase(Locale.ROOT);
            if (low.contains("disk is full") || low.contains("sqlite_full") || low.contains("no space")) {
                m = "存储空间不足，正文写不进去了";
            }
            r.msg = "导入失败：" + m;
        } finally {
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        return r;
    }

    private static void cleanup(Context c, ModouStore store, String id) {
        if (id == null) return;
        try {
            store.delChaptersOf(id);
            store.del("books", id);
            BookFiles.clearImages(c, id);
        } catch (Exception ignore) {
            /* 半截记录清不掉也不该让导入报错 */
        }
    }

    /* ---------------- 边解析边写库 ---------------- */

    /** 章节收集 + 分批入库：写完的正文立刻丢掉引用，峰值只有一批 */
    private static final class Writer implements TxtBook.Sink, EpubBook.Sink {
        // 1.2M→1.6M：每本少约 1/3 的写库往返（内容仍是流式、峰值不变）
        private static final int BATCH_CHARS = 1600000;

        private final ModouStore store;
        private final String id;
        private final String fileName;
        private final Stage stage;
        private final List<String> batchHtml = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private int cursor = 0;
        private int budget = 0;
        /** 真正写进库的章数：putChapters 事务回滚时它会偏小，导入后据此校验 */
        private int written = 0;
        private long cachedAt = System.currentTimeMillis();

        Writer(Context c, ModouStore s, String id, String fileName, Stage stage) {
            this.store = s;
            this.id = id;
            this.fileName = fileName;
            this.stage = stage;
        }

        @Override
        public void chapter(String name, String html) {
            add(name, html);
        }

        @Override
        public void chapter(int index, String name, String html) {
            add(name, html);
        }

        private void add(String name, String html) {
            if (html == null || html.isEmpty()) return;
            names.add(name == null || name.isEmpty() ? "(未命名章节)" : name);
            batchHtml.add(html);
            budget += html.length();
            if (budget >= BATCH_CHARS) flush();
        }

        void flush() {
            if (batchHtml.isEmpty()) return;
            written += store.putChapters(id, cursor, batchHtml, cachedAt);
            cursor += batchHtml.size();
            batchHtml.clear();
            budget = 0;
            stage.say("解析《" + strip(fileName) + "》… 已入库 " + cursor + " 章");
        }

        int count() {
            return names.size();
        }

        int written() {
            return written;
        }

        List<String> names() {
            return names;
        }
    }

    /* ---------------- 文件读取 ---------------- */

    /** 复制到 cacheDir：解析器可以反复随机读，且不受 SAF provider 的流式限制 */
    private static File copyToCache(Context c, Uri u, String ext, long sizeHint, Stage stage) throws Exception {
        File dir = new File(c.getCacheDir(), "import");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建临时目录");
        File f = new File(dir, "book-" + System.currentTimeMillis() + "." + ext);
        InputStream in = c.getContentResolver().openInputStream(u);
        if (in == null) throw new Exception("打不开文件（可能已被移动或删除）");
        try (InputStream src = in; FileOutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[131072];
            long total = 0;
            long mark = 0;
            int n;
            while ((n = src.read(b)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new Exception("文件超过 60MB");
                out.write(b, 0, n);
                // 每 16MB 报一次进度：读云盘上的大文件时，界面不能一直停在「正在读取…」
                if (total - mark >= 16L * 1024 * 1024) {
                    mark = total;
                    if (stage != null) {
                        stage.say("正在读取… " + mb(total) + (sizeHint > 0 ? " / " + mb(sizeHint) : ""));
                    }
                }
            }
            out.flush();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw e;
        }
        return f;
    }

    /** 可用空间（字节）；拿不到返回 -1，按「不拦」处理 */
    private static long freeBytes(Context c) {
        try {
            StatFs fs = new StatFs(c.getFilesDir().getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String mb(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1fGB", bytes / 1024.0 / 1024 / 1024);
        }
        return Math.max(1, bytes / 1024 / 1024) + "MB";
    }

    private static byte[] head(File f, int max) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[max];
            int n = 0;
            while (n < max) {
                int k = in.read(b, n, max - n);
                if (k <= 0) break;
                n += k;
            }
            if (n == max) return b;
            byte[] out = new byte[n];
            System.arraycopy(b, 0, out, 0, n);
            return out;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    static String strip(String name) {
        String n = String.valueOf(name);
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    /** 与 LocalScan.urlOf 同一格式：书架判定「是否导入过」用的就是这个 */
    static String urlOf(String name, long size) {
        return LOCAL + name + "#" + size;
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }
}
