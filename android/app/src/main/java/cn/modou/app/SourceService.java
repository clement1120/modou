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
        // 规约自检（规约 5）：风控/JS错误类 reason 不得含 "HTTP 2" 子串，否则会被误判成规则失效误删。
        SourceHealth.verifyBlockedReasonContract();
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
    /** 连续多少个请求超时（中间成功一次就清零）：用来判定 JS 线程是不是已经死了 */
    private int timeoutsInRow = 0;
    /** 连续超到这个数就重建服务页 —— 正则跑飞/死循环时这是唯一能自愈的办法 */
    private static final int RECYCLE_AFTER = 3;

    /* ---------- 重建的三道闸 ---------- */
    /** 重建冷却：5 分钟内最多重建一次。断网时每个请求都会超时，
     *  没有这道闸就是「超时→重建→再超时→再重建」无限循环，比不重建更耗电 */
    private static final long RECYCLE_COOLDOWN_MS = 5 * 60 * 1000;
    private long lastRecycleAt = 0;

    /* ---------- 心跳 ---------- */
    /** 心跳间隔：空闲时才发，用来区分「JS 线程死了」和「只是这次网络慢」 */
    private static final long BEAT_MS = 2000;
    /** 闲置超过 1 分钟后改 10 秒一拍：用户在看本地书时，2 秒一次纯属白白戳 JS 引擎 */
    private static final long BEAT_MS_IDLE = 10000;
    private static final long BEAT_ACTIVE_WINDOW_MS = 60 * 1000;
    private long lastJobAt;
    /** 连续几拍没回判定死亡。4 秒，比单纯看网络超时（8 秒）准得多 */
    private static final int BEAT_MISS_LIMIT = 2;
    private boolean beatPending;
    private int beatMiss;

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
        // 没网就直接回失败：让调用方立刻看到「没网」，而不是转 30 秒圈再报超时。
        // 顺带把「断网时疯狂超时」整条链路掐掉 —— 超时是重建 WebView 的触发源。
        if (!online()) {
            // 回调统一回主线程，和正常结果（onResult）走同一条路，调用方不必判断线程
            main.post(() -> safeFail(cb, "当前没有网络，连上网络后再试"));
            return;
        }
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

    /** 书源定义缓存：体检 137 个源会调 137 次，每次都读全表 + 全量 JSON.parse 是 O(n²) */
    private org.json.JSONArray defsCache;
    private long defsAt;

    /**
     * 取全部书源定义（JSON 数组）；读失败返回空数组。
     *
     * <p>2 秒软缓存：体检期间一次解析供全部调用复用，导入/编辑书源后最多迟 2 秒生效 ——
     * 反过来（不缓存）每校验一个源就把整张表读一遍、再 parse 一遍，137 个源能卡出几秒。
     */
    private synchronized org.json.JSONArray sourceDefs() {
        long now = System.currentTimeMillis();
        if (defsCache != null && now - defsAt < 2000) return defsCache;
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            String s = ModouStore.get(ctx).all("sources");
            if (s != null && !s.isEmpty()) arr = new org.json.JSONArray(s);
        } catch (Exception e) {
            Log.w(TAG, "load sources failed", e);
        }
        defsCache = arr;
        defsAt = now;
        return arr;
    }

    /**
     * 让 2 秒软缓存立刻失效。
     *
     * <p>校验前必须调：用户「编辑书源 → 只校验这一个」时，编辑保存到点校验往往不到 2 秒，
     * 缓存里还是旧规则 —— 改了等于没改，校验结论自然还是旧的那一套。
     */
    public synchronized void invalidateDefs() {
        defsCache = null;
        defsAt = 0;
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
        search(keyword, picked, page, false, cb);
    }

    /**
     * @param check 体检 / 校验单个源时的搜索。引擎会给这类请求更长的单源超时
     *              （20s + 一次重试），而不是日常广撒网用的 8s 一刀切 ——
     *              慢站被 8s 判死，正是「Legado 里能用、体检里全红」的主因。
     */
    public void search(String keyword, java.util.Set<String> picked, int page, boolean check, Callback cb) {
        search(keyword, picked, page, check, false, cb);
    }

    /**
     * @param includeDisabled 体检 / 校验时传 true。
     *
     * <p>「启用 / 停用」的语义只是「搜书时要不要用它」，跟「这个源还能不能用」无关。
     * 以前这里无差别过滤停用源，于是校验停用源时数组为空，直接失败在
     * 「没有配置搜索规则」—— 用户看到的是「停用的源根本校验不了」。
     */
    public void search(String keyword, java.util.Set<String> picked, int page, boolean check,
                       boolean includeDisabled, Callback cb) {
        search(keyword, picked, page, check, includeDisabled, false, cb);
    }

    /**
     * @param waitRate 单源调试传 true：站点回「搜索间隔限制」时，等够它要求的时间再重发一次。
     *                 批量校验必须为 false —— 几十个源每个等十秒，总预算当场耗光。
     */
    public void search(String keyword, java.util.Set<String> picked, int page, boolean check,
                       boolean includeDisabled, boolean waitRate, Callback cb) {
        org.json.JSONArray use = new org.json.JSONArray();
        org.json.JSONArray arr = sourceDefs();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!includeDisabled && !o.optBoolean("enabled", true)) continue;
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
            if (check) p.put("check", true);
            if (waitRate) p.put("waitRate", true);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        // 校验：单源 20s × 重试 1 次 + 解析余量；日常多源搜索仍是 60s
        // 单源调试（waitRate）可能要为限流多等十来秒，再给宽一点。
        run("search", p.toString(), check ? (waitRate ? 120000 : 90000) : 60000, cb);
    }

    /**
     * 单源搜索。
     *
     * 给「搜到一个显示一个」的界面用：多源并发由调用方（SourceSearchPool）控制，
     * 每源回来就刷新一次列表，而不是等最慢的那个源才一次性出结果 —— 134 个源里
     * 只要有一个卡满超时，用户就要对着空白等半分钟，看起来完全像卡死。
     */
    public void searchOne(JSONObject source, String keyword, int page, Callback cb) {
        if (source == null) {
            safeFail(cb, "书源为空");
            return;
        }
        JSONObject p = new JSONObject();
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            a.put(source);
            p.put("sources", a);
            p.put("keyword", keyword);
            p.put("page", page > 0 ? page : 1);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        // 9s 对上「HTTP 直连」够用，但对「站点有人机验证」的源不够：
        // Cloudflare 的 Managed Challenge 要在 WebView 里跑十几秒 JS 才放行，9s 必然先超时，
        // 用户看到的就是「这个源永远搜不到」。放宽到 22s——这是**上限**不是固定等待，
        // 正常源照样 1~2 秒返回，只有卡住的源才会占满。
        run("search", p.toString(), 26000, cb); // 引擎内单源 8s 请求超时 + 可能的浏览器内核验证
    }

    /**
     * 探针词：拿这个源首页上真实存在的书名，供「这个源能不能搜」的校验使用。
     *
     * <p>校验最根本的误判是「拿什么词去问」：用「三体」问言情站，0 条不代表源坏。
     * 首页推荐位上的书名站内一定有，用它的书名去搜，搜不到才是源的问题。
     */
    public void probe(JSONObject source, Callback cb) {
        if (source == null) {
            safeFail(cb, "书源为空");
            return;
        }
        JSONObject p = new JSONObject();
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            a.put(source);
            p.put("sources", a);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        run("probe", p.toString(), 12000, cb);
    }

    /**
     * 搜索入口自动探测：传入一个书源，让引擎去首页扒搜索框、试常见路径，真发请求验证，
     * 回传可用的 searchUrl（含 GET/POST 选项串）。原生侧据此一键改写或批量修复源。
     */
    public void discoverSearch(JSONObject source, String keyword, Callback cb) {
        if (source == null) {
            safeFail(cb, "书源为空");
            return;
        }
        JSONObject p = new JSONObject();
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            a.put(source);
            p.put("sources", a);
            if (keyword != null && !keyword.isEmpty()) p.put("keyword", keyword);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        // 探测要发首页 + 若干候选请求，预算给足；单源场景，不怕慢
        run("discoverSearch", p.toString(), 45000, cb);
    }

    /** 发现（书架式推荐） */
    public void explore(java.util.Set<String> picked, int page, String exploreUrl, Callback cb) {
        explore(picked, page, exploreUrl, false, cb);
    }
    /** @param includeDisabled 同 search：校验停用源时要能看到它 */
    public void explore(java.util.Set<String> picked, int page, String exploreUrl,
                        boolean includeDisabled, Callback cb) {
        org.json.JSONArray use = new org.json.JSONArray();
        org.json.JSONArray arr = sourceDefs();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!includeDisabled && !o.optBoolean("enabled", true)) continue;
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
            // includeDisabled 只在校验路径为 true：顺带告诉超时判定「这是体检，别触发重建」
            if (includeDisabled) p.put("check", true);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        run("explore", p.toString(), 60000, cb);
    }

    /** 书籍详情（刷新简介/最新章/目录地址） */
    public void info(org.json.JSONObject book, Callback cb) {
        act("info", book, null, 30000, cb);
    }

    /** 指定超时的详情：体检深检要看总时长，不该让单步吃满 30 秒 */
    public void info(org.json.JSONObject book, long timeoutMs, Callback cb) {
        act("info", book, null, timeoutMs, cb, true);
    }

    /** 取目录 */
    public void toc(org.json.JSONObject book, Callback cb) {
        act("toc", book, null, 45000, cb);
    }

    /** 指定超时的目录请求：后台批量更新要按「每本 15 秒」走，45 秒会让几十本书排到天亮 */
    public void toc(org.json.JSONObject book, long timeoutMs, Callback cb) {
        act("toc", book, null, timeoutMs, cb, true);
    }

    /** 取正文 */
    public void content(org.json.JSONObject book, org.json.JSONObject chapter, long timeoutMs, Callback cb) {
        act("content", book, chapter, timeoutMs, cb, true);
    }

    public void content(org.json.JSONObject book, org.json.JSONObject chapter, Callback cb) {
        act("content", book, chapter, 30000, cb);
    }

    private void act(String action, org.json.JSONObject book, org.json.JSONObject chapter,
                     long timeoutMs, Callback cb) {
        act(action, book, chapter, timeoutMs, cb, false);
    }

    private void act(String action, org.json.JSONObject book, org.json.JSONObject chapter,
                     long timeoutMs, Callback cb, boolean check) {
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
            if (check) p.put("check", true);
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
            // CF 兜底（WebFetch）取完页面后，把结果回传给书源 JS 引擎里的 Promise。
            // 这样单个风控源不再阻塞整条 JS 引擎、拖垮其它源的搜索。
            WebFetch.deliverer = (seq, json) -> deliverWebFetch(seq, json);
            web.loadUrl("http://127.0.0.1:" + port + "/" + PAGE);
        } catch (Throwable t) {
            Log.e(TAG, "create webview failed", t);
            // 创建失败要松开闸门，否则这条服务页永远起不来（下一轮请求连重试的机会都没有）
            startRequested = false;
            // 起不来就立刻失败所有等待中的请求：宁可报错，也不要让用户对着转圈干等
            failAll("书源服务启动失败：" + t.getMessage());
        }
    }

    /** 给 WebFetch 用：WebView 只需要 application context，不必持有 Activity */
    Context appContext() {
        return ctx;
    }

    /** 主动预热：预计马上要用时先调，省掉首次调用的页面加载等待 */
    public void warmUp() {
        main.post(this::ensureWebView);
    }

    /** 由 SourceBridge 回调：服务页已就绪 */
    void onBridgeReady() {
        main.post(() -> {
            ready = true;
            beatMiss = 0;
            beatPending = false;
            synchronized (waiting) {
                for (Job j : waiting) fire(j);
                waiting.clear();
            }
            scheduleBeat();
        });
    }

    void onResult(String seq, String json) {
        final Job j = jobs.remove(seq);
        if (j == null) return;
        timeoutsInRow = 0; // 服务还活着
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

    /**
     * CF 兜底取页完成后，把结果回传给书源 JS 引擎里的 Promise。
     * 必须在主线程调（evaluateJavascript 只能主线程）。JSON 用 WebFetch.esc 转成 JS 字面量。
     */
    private void deliverWebFetch(int seq, String json) {
        final WebView w = web;
        if (w == null) return;
        final String js = "window.__modouWebFetchDone(" + seq + ",\"" + WebFetch.esc(json) + "\")";
        main.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    /* ---------- 心跳与网络 ---------- */

    /**
     * 心跳：空闲时每 2 秒问 JS 一句「还在吗」。
     *
     * <p>为什么需要它：连续超时只能说明「请求没回来」，原因可能是网络慢、也可能是 JS 线程
     * 被一条灾难性回溯的正则按死了。前者重建 WebView 纯属浪费，后者不重建就永远好不了。
     * 请求在途时 JS 本来就在忙，那时的沉默不算数；**只在没有在途请求时**发心跳，
     * 连续两拍都没回，才是真的死了。
     */
    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            if (web == null || !ready) {
                beatPending = false;
                beatMiss = 0;
                return; // 页面没就绪：不排下一拍，等 onBridgeReady 重新点火
            }
            if (!jobs.isEmpty()) {
                beatPending = false;
                beatMiss = 0;
            } else if (beatPending) {
                if (++beatMiss >= BEAT_MISS_LIMIT) {
                    beatMiss = 0;
                    beatPending = false;
                    recycle("心跳连续 " + BEAT_MISS_LIMIT + " 次无响应");
                }
            } else {
                beatPending = true;
                try {
                    // 回调在主线程、JS 执行完之后才来：JS 被按住就永远等不到
                    web.evaluateJavascript("1", v -> {
                        beatPending = false;
                        beatMiss = 0;
                    });
                } catch (Throwable t) {
                    beatPending = false;
                }
            }
            scheduleBeat();
        }
    };

    private void scheduleBeat() {
        main.removeCallbacks(beat);
        if (ready && web != null) {
            long idle = System.currentTimeMillis() - lastJobAt;
            main.postDelayed(beat, idle < BEAT_ACTIVE_WINDOW_MS ? BEAT_MS : BEAT_MS_IDLE);
        }
    }

    /** 当前是否有网（没有 ACCESS_NETWORK_STATE 时按「有网」处理，宁可多试一次也别误判） */
    private boolean online() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    /* ---------- 内部 ---------- */

    private void fire(Job j) {
        if (web == null) {
            safeFail(j.cb, "书源服务未启动");
            return;
        }
        final String seq = String.valueOf(++seqGen);
        lastJobAt = System.currentTimeMillis();
        jobs.put(seq, j);
        j.seq = seq;
        j.timeout = () -> {
            if (jobs.remove(seq) == null) return;
            // 体检超时不参与「重建」判定：5 路并发跑 137 个源，同时撞上三个慢站是常态，
            // 而那一刻 JS 明明活着。按老规则会重建 WebView，把在途的另外两个请求一起打死，
            // 下一批源全部报「服务超时」—— 这才是体检「一片红」最隐蔽的来源之一。
            final boolean isCheck = j.payload != null && j.payload.contains("\"check\":true");
            if (isCheck) {
                timeoutsInRow = 0;
            } else if (++timeoutsInRow >= RECYCLE_AFTER && jobs.isEmpty()) {
                recycle("连续 " + timeoutsInRow + " 个请求超时");
            }
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

    /**
     * 重建服务页。
     *
     * <p>WebView 的 JS 是单线程，一条灾难性回溯的正则会把整条 JS 线程按住，
     * 之后任何 evaluateJavascript 都不会有回调 —— 原生侧看起来就是「所有源都超时」。
     * 此时唯一有效的手段是销毁并重建这个 WebView：渲染线程整个换掉，卡死的脚本随之消失。
     * 代价是丢一次预热（约 200ms），比让用户重启 App 强。
     */
    private void recycle(String why) {
        long now = System.currentTimeMillis();
        if (now - lastRecycleAt < RECYCLE_COOLDOWN_MS) {
            // 冷却中：重复重建只会把内存和电一起烧掉，本轮认栽
            Log.w(TAG, "重建冷却中，跳过：" + why);
            timeoutsInRow = 0;
            return;
        }
        if (!online()) {
            // 断网时每个请求都超时，这不是 JS 的错；重建一百次也还是没网
            Log.w(TAG, "当前无网络，跳过重建：" + why);
            timeoutsInRow = 0;
            return;
        }
        lastRecycleAt = now;
        Log.w(TAG, "重建书源服务页：" + why);
        timeoutsInRow = 0;
        ready = false;
        startRequested = false;
        for (String k : new ArrayList<>(jobs.keySet())) {
            Job j = jobs.remove(k);
            if (j == null) continue;
            main.removeCallbacks(j.timeout);
            safeFail(j.cb, "书源服务已重启，本次请求作废");
        }
        if (web != null) {
            try {
                web.removeJavascriptInterface("ModouSourceNative");
                web.stopLoading();
                web.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "destroy for recycle failed", t);
            }
            web = null;
        }
        ensureWebView();
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
