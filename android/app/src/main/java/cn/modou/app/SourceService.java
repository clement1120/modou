package cn.modou.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SourceService — 书源服务的原生侧门面（单例）。
 *
 * 它背后是一个**没有界面的常驻 WebView**，加载 assets/web/source-service.html。
 * 书源规则本质是「抓 HTML + 执行 JS + CSS/JSON/XPath 取字段」，这套能力只有 WebView 有，
 * 用原生重写等于重做一个 JS 引擎，不划算。
 *
 * 所以这里做的是「把引擎关起来」而不是「把引擎搬走」：
 * 原生屏通过 run(action, payload, cb) 发请求，服务页算完回传一次结果。
 * 好处是原生搜书/详情/阅读都不必再经过 UI WebView，
 * 将来 UI WebView 整个退役，这一层完全不用动。
 *
 * 三个必须守住的约定：
 *  1. 每个请求必定回调一次（超时也算），否则调用方的等待 UI 会永远转圈；
 *  2. 所有 WebView 操作都在主线程（WebView 不是线程安全的）；
 *  3. 服务页未就绪时请求进队列，就绪后按序发出，不丢。
 */
public class SourceService {
    private static final String TAG = "SourceService";
    /** 服务页地址：必须与 UI 页同源，否则 /proxy 不可用、ES module 也会被 CORS 挡掉 */
    private static final String PAGE = "source-service.html";

    private static volatile SourceService instance;
    /** 本地服务端口：由 MainActivity 注入（服务页必须与 UI 页同源，才能共用 /proxy） */
    private int port = 39617;

    public static SourceService get() {
        return instance;
    }

    /** 在 Application/Activity 早期调用一次；重复调用无害 */
    public static synchronized void init(Context c, int port) {
        if (instance == null) {
            SourceService s = new SourceService(c.getApplicationContext());
            s.port = port;
            instance = s;
        }
    }

    /** 只有端口没变时的轻量取用（避免重复 init 覆盖已有实例） */
    public static synchronized SourceService getOrInit(Context c, int port) {
        if (instance == null) init(c, port);
        return instance;
    }

    /* ---------- 状态 ---------- */

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private boolean startRequested = false;
    private boolean ready = false;
    private int seqGen = 0;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final List<Job> waiting = new ArrayList<>();

    private SourceService(Context c) {
        this.ctx = c;
    }

    /** 外部拿到页面地址用（端口由 MainActivity 定的，这里只给相对名） */
    public static String pageName() {
        return PAGE;
    }

    /** 服务页是否已就绪（未就绪时调用方可以提示「书源服务启动中」） */
    public boolean isReady() {
        return ready;
    }

    /* ---------- 回调 ---------- */

    public interface Callback {
        void onDone(JSONObject result);

        /** 多书源搜索的进度；调用方不需要就不实现 */
        default void onProgress(int done, int total) {}
    }

    /* ---------- 调用 ---------- */

    /**
     * 发起一次书源动作。
     *
     * @param action  search / explore / info / toc / content
     * @param payload JSON 字符串（含 sources 或 source/book/chapter 等参数）
     * @param timeoutMs 超时；到点按失败回调一次，避免调用方永久等待
     */
    public void run(String action, String payload, long timeoutMs, Callback cb) {
        final Job job = new Job(action, payload == null ? "{}" : payload, timeoutMs, cb);
        main.post(() -> {
            if (ready) fire(job);
            else {
                synchronized (waiting) {
                    waiting.add(job);
                }
                ensureWebView();
            }
        });
    }

    public void run(String action, String payload, Callback cb) {
        run(action, payload, 45000, cb);
    }

    /* ---------- 面向业务的便捷动作 ---------- */
    /* 书源定义统一从 SQLite 的 sources 表取：原生屏不再需要进 WebView 问「有哪些书源」。 */

    /** 取全部书源定义（JSON 数组）；读失败返回空数组 */
    private org.json.JSONArray sourceDefs() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            String s = ModouStore.get(ctx).all("sources");
            if (s != null && !s.isEmpty()) arr = new org.json.JSONArray(s);
        } catch (Exception e) {
            Log.w(TAG, "load sources failed", e);
        }
        return arr;
    }

    /** 按 bookSourceUrl 找单个书源定义 */
    private org.json.JSONObject sourceDef(String url) {
        if (url == null || url.isEmpty()) return null;
        org.json.JSONArray arr = sourceDefs();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o != null && url.equals(o.optString("bookSourceUrl", ""))) return o;
        }
        return null;
    }

    /**
     * 搜索。
     *
     * @param picked 选中的书源 URL 集合；为空表示全部启用书源
     */
    public void search(String keyword, java.util.Set<String> picked, int page, Callback cb) {
        org.json.JSONArray use = new org.json.JSONArray();
        org.json.JSONArray arr = sourceDefs();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (o.optBoolean("enabled", true) == false) continue;
            String u = o.optString("bookSourceUrl", "");
            if (u.isEmpty()) continue;
            if (picked != null && !picked.isEmpty() && !picked.contains(u)) continue;
            if (!o.has("searchUrl") || o.optString("searchUrl", "").trim().isEmpty()) continue;
            use.put(o);
        }
        if (use.length() == 0) {
            safeFail(cb, picked != null && !picked.isEmpty()
                    ? "选中的书源里没有配置搜索规则的" : "没有书源配置了搜索规则");
            return;
        }
        org.json.JSONObject p = new org.json.JSONObject();
        try {
            p.put("sources", use);
            p.put("keyword", keyword);
            p.put("page", page > 0 ? page : 1);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        run("search", p.toString(), 60000, cb); // 多源并发，超时放宽
    }

    /** 发现（书架式推荐） */
    public void explore(java.util.Set<String> picked, int page, String exploreUrl, Callback cb) {
        org.json.JSONArray use = new org.json.JSONArray();
        org.json.JSONArray arr = sourceDefs();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (o.optBoolean("enabled", true) == false) continue;
            String u = o.optString("bookSourceUrl", "");
            if (u.isEmpty()) continue;
            if (picked != null && !picked.isEmpty() && !picked.contains(u)) continue;
            if (o.optString("exploreUrl", "").trim().isEmpty()) continue;
            use.put(o);
        }
        if (use.length() == 0) {
            safeFail(cb, "没有书源配置了发现规则");
            return;
        }
        org.json.JSONObject p = new org.json.JSONObject();
        try {
            p.put("sources", use);
            p.put("page", page > 0 ? page : 1);
            if (exploreUrl != null) p.put("exploreUrl", exploreUrl);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        run("explore", p.toString(), 60000, cb);
    }

    /** 书籍详情（刷新简介/最新章/目录地址） */
    public void info(org.json.JSONObject book, Callback cb) {
        act("info", book, null, 30000, cb);
    }

    /** 取目录 */
    public void toc(org.json.JSONObject book, Callback cb) {
        act("toc", book, null, 45000, cb);
    }

    /** 取正文 */
    public void content(org.json.JSONObject book, org.json.JSONObject chapter, long timeoutMs, Callback cb) {
        act("content", book, chapter, timeoutMs, cb);
    }

    public void content(org.json.JSONObject book, org.json.JSONObject chapter, Callback cb) {
        act("content", book, chapter, 30000, cb);
    }

    private void act(String action, org.json.JSONObject book, org.json.JSONObject chapter,
                     long timeoutMs, Callback cb) {
        if (book == null) {
            safeFail(cb, "缺少书籍信息");
            return;
        }
        org.json.JSONObject src = sourceDef(book.optString("sourceUrl", ""));
        if (src == null) {
            safeFail(cb, "找不到这本书的书源（可能已被删除）");
            return;
        }
        org.json.JSONObject p = new org.json.JSONObject();
        try {
            p.put("source", src);
            p.put("book", book);
            if (chapter != null) p.put("chapter", chapter);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        run(action, p.toString(), timeoutMs, cb);
    }

    /* ---------- WebView 生命周期 ---------- */

    private void ensureWebView() {
        if (startRequested) return;
        startRequested = true;
        try {
            web = new WebView(ctx);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true); // 少数书源的 @js 会用到 localStorage
            s.setMediaPlaybackRequiresUserGesture(false);
            web.addJavascriptInterface(new SourceBridge(), "ModouSourceNative");
            web.loadUrl("http://127.0.0.1:" + port + "/" + PAGE);
        } catch (Throwable t) {
            Log.e(TAG, "create webview failed", t);
            // 起不来就立刻失败所有等待中的请求：宁可报错，也不要让用户对着转圈干等
            failAll("书源服务启动失败：" + t.getMessage());
        }
    }

    /** 主动预热：预计马上要用时先调，省掉首次调用的页面加载等待 */
    public void warmUp() {
        main.post(this::ensureWebView);
    }

    /** 由 SourceBridge 回调：服务页已就绪 */
    void onBridgeReady() {
        main.post(() -> {
            ready = true;
            synchronized (waiting) {
                for (Job j : waiting) fire(j);
                waiting.clear();
            }
        });
    }

    void onResult(String seq, String json) {
        final Job j = jobs.remove(seq);
        if (j == null) return;
        main.removeCallbacks(j.timeout);
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            o = new JSONObject();
            try {
                o.put("ok", false);
                o.put("error", "结果解析失败");
            } catch (Exception ignore) {
                /* 不可能 */
            }
        }
        final JSONObject res = o;
        main.post(() -> {
            try {
                j.cb.onDone(res);
            } catch (Throwable t) {
                Log.e(TAG, "callback error", t);
            }
        });
    }

    void onProgress(String seq, int done, int total) {
        final Job j = jobs.get(seq);
        if (j == null) return;
        main.post(() -> {
            try {
                j.cb.onProgress(done, total);
            } catch (Throwable t) {
                Log.e(TAG, "progress callback error", t);
            }
        });
    }

    void onLog(String msg) {
        Log.d(TAG, "svc: " + msg);
    }

    /* ---------- 内部 ---------- */

    private void fire(Job j) {
        if (web == null) {
            safeFail(j.cb, "书源服务未启动");
            return;
        }
        final String seq = String.valueOf(++seqGen);
        jobs.put(seq, j);
        j.seq = seq;
        j.timeout = () -> {
            if (jobs.remove(seq) == null) return;
            safeFail(j.cb, "书源服务超时（" + (j.timeoutMs / 1000) + " 秒未返回）");
        };
        main.postDelayed(j.timeout, j.timeoutMs);
        try {
            web.evaluateJavascript("window.ModouSource.run('" + seq + "','" + j.action + "',"
                    + jsQuote(j.payload) + ")", null);
        } catch (Throwable t) {
            jobs.remove(seq);
            main.removeCallbacks(j.timeout);
            safeFail(j.cb, "调用书源服务失败：" + t.getMessage());
        }
    }

    private void failAll(String reason) {
        main.post(() -> {
            synchronized (waiting) {
                for (Job j : waiting) safeFail(j.cb, reason);
                waiting.clear();
            }
        });
    }

    private static void safeFail(Callback cb, String reason) {
        if (cb == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", reason);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        try {
            cb.onDone(o);
        } catch (Throwable t) {
            Log.e(TAG, "fail callback error", t);
        }
    }

    /** 释放：App 退出时调用，避免隐藏 WebView 泄漏 */
    public void shutdown() {
        main.post(() -> {
            ready = false;
            startRequested = false;
            jobs.clear();
            if (web != null) {
                try {
                    web.removeJavascriptInterface("ModouSourceNative");
                    web.destroy();
                } catch (Throwable t) {
                    Log.w(TAG, "destroy failed", t);
                }
                web = null;
            }
        });
    }

    /**
     * JS 字符串字面量转义。
     * U+2028 / U+2029 在 JS 里是行终止符（JSON 里却合法），不转义会直接语法错误 ——
     * 书名里带这类字符虽罕见，但一旦出现就是「整次搜索静默失败」。
     */
    private static String jsQuote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 16);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\u2028': b.append("\\u2028"); break;
                case '\u2029': b.append("\\u2029"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static final class Job {
        final String action;
        final String payload;
        final long timeoutMs;
        final Callback cb;
        String seq;
        Runnable timeout;

        Job(String a, String p, long t, Callback c) {
            action = a;
            payload = p;
            timeoutMs = t;
            cb = c;
        }
    }
}
