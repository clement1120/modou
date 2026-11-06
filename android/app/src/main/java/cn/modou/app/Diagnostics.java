package cn.modou.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 诊断日志：崩溃自动落盘 + 轻量事件流 + 一键导出。
 *
 * 为什么要这个东西：用户报「闪退了 / 白屏了」，如果没有日志，只能靠猜。
 * 这里只做三件事，刻意不引入第三方崩溃上报（项目零依赖）：
 *   1) 未捕获异常写成本地文件（保留最近 5 份），进程被杀也不丢；
 *   2) 记住最近若干条关键事件（启动、WebView 加载失败、JS 报错），
 *      崩溃时和前因一起写进报告 —— 只看堆栈往往不知道用户点了什么；
 *   3) 导出时拼成纯文本走系统分享，用户直接发微信，不申请任何权限。
 *
 * 注意：所有写盘都在调用线程同步完成（崩溃路径上没法用线程池，进程随时会死）。
 */
final class Diagnostics {

    private static final String TAG = "Diagnostics";
    private static final int MAX_EVENTS = 400;
    private static final int KEEP_CRASH = 5;

    private static final Deque<String> EVENTS = new ArrayDeque<>();
    private static Context app;
    private static volatile boolean installed;
    /** 最近一次崩溃的完整文本：导出时直接给，不必再读盘 */
    private static volatile String lastCrash = "";

    private Diagnostics() {
    }

    /** 在 Application/首个 Activity 的 onCreate 里调一次（重复调用无副作用）。 */
    static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        app = c.getApplicationContext();
        loadLastCrash();

        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                saveCrash(t, e);
            } catch (Throwable ignore) {
                // 崩溃处理本身再崩，就只能交给系统了
            }
            // 交给系统默认处理：用户仍能看到「应用已停止」，别做成静默消失
            if (prev != null) prev.uncaughtException(t, e);
        });
        event("进程启动 · v" + versionName(app));
    }

    /** 记一条带时间的事件（环形缓冲，只在内存里）。 */
    static void event(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + "  " + msg;
        synchronized (EVENTS) {
            EVENTS.addLast(line);
            while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        }
    }

    /** 调试期用：同时打 Logcat。 */
    static void log(String msg) {
        Log.i(TAG, msg);
        event(msg);
    }

    // ---------------------------------------------------------------- 崩溃落盘

    private static void saveCrash(Thread t, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("时间 ").append(now()).append('\n');
        sb.append(deviceLine()).append('\n');
        sb.append("崩溃线程 ").append(t == null ? "?" : t.getName()).append('\n');
        sb.append("---- 异常 ----\n");
        sb.append(Log.getStackTraceString(e)).append('\n');
        sb.append("---- 崩溃前记录 ----\n").append(eventsText());

        String text = sb.toString();
        lastCrash = text;
        File dir = dir(app);
        File f = new File(dir, "crash-" + stamp() + ".txt");
        writeFile(f, text);
        prune(dir);
        // 0.9.48：同时写一份到用户找得到的地方。以前只写内部私有目录，
        // App 一崩用户拿不到文件，「有日志」等于没有。
        copyVisible(f.getName(), text);
    }

    /** 被业务代码 try-catch 住的崩溃（如启动诊断锁）：同样落盘，便于导出 */
    static void capture(String where, Throwable e) {
        event(where + " 崩溃: " + e);
        saveCrash(Thread.currentThread(), e);
    }

    /**
     * 把崩溃文本复制到两处用户可达的位置：
     * 1. 应用外部专属目录 Android/data/<包>/files/diag/（任何版本都无权限要求）；
     * 2. Android 10+ 的公共 Download 目录（文件管理器直接可见，插电脑也在）。
     * 任一失败都静默：崩溃路径上不允许再炸。
     */
    private static void copyVisible(String name, String text) {
        try {
            File base = app.getExternalFilesDir(null);
            if (base != null) {
                File d = new File(base, "diag");
                if (!d.exists()) //noinspection ResultOfMethodCallIgnored
                    d.mkdirs();
                writeFile(new File(d, name), text);
            }
        } catch (Throwable ignore) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 29 && app.getContentResolver() != null) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        "modou-" + name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                android.net.Uri uri = app.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (java.io.OutputStream os =
                                 app.getContentResolver().openOutputStream(uri)) {
                        if (os != null) os.write(text.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** 上次进程死前留下的最后一份崩溃，读进内存供导出使用。 */
    private static void loadLastCrash() {
        try {
            File dir = dir(app);
            File[] fs = dir.listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".txt"));
            if (fs == null || fs.length == 0) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(fs[0]),
                            StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 24000) break; // 别把一份报告撑爆
            }
            r.close();
            lastCrash = sb.toString();
        } catch (Throwable ignore) {
        }
    }

    private static void prune(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".txt"));
        if (fs == null || fs.length <= KEEP_CRASH) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP_CRASH; i < fs.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            fs[i].delete();
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "diag");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    private static void writeFile(File f, String text) {
        try (FileOutputStream out = new FileOutputStream(f);
             OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            w.write(text);
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------- 报告

    /** 拼一份完整报告：环境 + 书库概况 + 最近崩溃 + 事件流。 */
    static String report(Context c) {
        Context ctx = c.getApplicationContext();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("==== 轻阅 诊断日志 ====\n");
        sb.append("导出时间 ").append(now()).append('\n');
        sb.append(deviceLine()).append('\n');
        sb.append("语言 ").append(Locale.getDefault()).append('\n');
        try {
            ModouUi u = ModouUi.of(ctx);
            sb.append("外观 ").append(u.dark ? "深色" : "浅色").append('\n');
        } catch (Throwable ignore) {
        }
        sb.append("书库 ").append(libraryStat(ctx)).append('\n');

        sb.append("\n---- 最近一次崩溃 ----\n");
        sb.append(lastCrash.isEmpty() ? "（没有崩溃记录）" : lastCrash.trim()).append('\n');

        sb.append("\n---- 运行记录（最近 ").append(EVENTS.size()).append(" 条）----\n");
        sb.append(eventsText());

        return sb.toString();
    }

    private static String libraryStat(Context c) {
        try {
            ModouStore st = ModouStore.get(c);
            String lite = st.booksLite();
            int n = 0;
            if (lite != null) {
                org.json.JSONArray arr = new org.json.JSONArray(lite);
                n = arr.length();
            }
            return n + " 本 · 已迁移=" + st.migrated();
        } catch (Throwable e) {
            return "读取失败: " + e.getClass().getSimpleName();
        }
    }

    private static String eventsText() {
        StringBuilder sb = new StringBuilder();
        synchronized (EVENTS) {
            if (EVENTS.isEmpty()) sb.append("（无）\n");
            for (String s : EVENTS) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    private static String deviceLine() {
        // app 是静态引用：进程被系统重建、或多进程下没走 install() 时会是 null，
        // 直接 app.getResources() 就是「Context.getResources() on a null object」那个崩溃
        Context a = app;
        if (a == null) {
            return "版本 ? · 包名 " + Build.MANUFACTURER + " " + Build.MODEL
                    + " · Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
        }
        String ver = "?", code = "";
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            ver = pi.versionName;
            code = " (" + pi.versionCode + ")";
        } catch (Throwable ignore) {
        }
        android.util.DisplayMetrics dm;
        try {
            dm = a.getResources().getDisplayMetrics();
        } catch (Throwable e) {
            dm = new android.util.DisplayMetrics();
        }
        return "版本 v" + ver + code
                + " · 包名 " + a.getPackageName() + '\n'
                + "设备 " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                + " · " + dm.widthPixels + "x" + dm.heightPixels + " @" + dm.density;
    }

    private static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "?";
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 导出：把报告写成文件（留存）+ 走系统分享（可直接发微信）。
     * 分享面板不可用时退回剪贴板，保证「总能拿到东西」。
     */
    static void export(Context c) {
        final Context ctx = c.getApplicationContext();
        // 兜底：万一 install() 没跑到（进程重启/多进程），这里补一次静态引用，
        // 后面所有 getResources()/getFilesDir() 才不会踩空指针
        if (app == null) app = ctx;
        // 报告里有书库查询，放后台拼：大书库时主线程查几千条会卡一下
        new Thread(() -> {
            String text;
            try {
                text = report(ctx);
            } catch (Throwable e) {
                text = "报告生成失败: " + e;
            }
            // 留一份在应用私有目录，方便以后 adb 或再次导出时对照；
            // 0.9.48 再留一份到 Download / Android/data，设置页打不开也能手工取
            writeFile(new File(dir(ctx), "last-report.txt"), text);
            copyVisible("last-report.txt", text);
            final String body = text;
            new android.os.Handler(Looper.getMainLooper()).post(() -> share(ctx, body));
        }, "diag-export").start();
    }

    private static void share(Context ctx, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "轻阅诊断日志");
        send.putExtra(Intent.EXTRA_TEXT, text);
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            Intent chooser = Intent.createChooser(send, "导出诊断日志");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
            event("导出诊断日志（分享）");
        } catch (Throwable e) {
            try {
                ClipboardManager cm = (ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("轻阅诊断日志", text));
                toastOnMain(ctx, "分享面板不可用，日志已复制到剪贴板");
                event("导出诊断日志（剪贴板）");
            } catch (Throwable e2) {
                toastOnMain(ctx, "导出失败：" + e2.getClass().getSimpleName());
            }
        }
    }

    private static void toastOnMain(Context c, String s) {
        try {
            new android.os.Handler(Looper.getMainLooper()).post(() -> ModouUi.toast(c, s));
        } catch (Throwable ignore) {
        }
    }
}
