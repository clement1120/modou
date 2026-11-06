package cn.modou.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebFetch — 用浏览器内核取页面（给「HTTP 客户端过不去」的站点留的后门）。
 *
 * <p>解决的问题：Cloudflare 的 Managed Challenge（页面标题 `Just a moment...`）要求执行一段
 * JS 才能拿到通行证。HttpURLConnection 不执行 JS，只能拿到 403 挑战页，于是整条书源被判死。
 *
 * <p>为什么不能「过一次挑战，拿到 cf_clearance 再用 HTTP 请求」：
 * Cloudflare 把通行证和**客户端 TLS 指纹**绑在一起。实测用 Chromium 过挑战拿到 cf_clearance，
 * 再用 curl 带同一个 cookie、同一份 UA（连 Sec-Fetch-* 都补齐）请求，仍然 403
 * `Attention Required!`。也就是说通行证离开浏览器内核就作废 —— 必须让每个页面都在 WebView 里加载。
 *
 * <p>代价要如实说：比 HTTP 直连慢一个数量级（首次要等挑战跑完，约 5~20 秒；同一站点后续页面
 * 带着 cookie 直接 200，约 1~3 秒）。所以只有「HTTP 被挑战页挡下」时才走这里，正常站点一步都不碰。
 *
 * <p>线程约定：异步接口。`fetchAsync` 立刻返回，主线程取完页面后通过 {@code deliverer} 把 JSON
 * 回传给书源服务 WebView（再由其回调 JS 里的 Promise）。不再在 JS bridge 线程上阻塞等待 ——
 * 否则一个风控源要等十几秒，会冻住整条书源 JS 引擎、把其它源的搜索全拖超时。
 */
final class WebFetch {

    private static final String TAG = "WebFetch";
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** 常驻一个实例：同一站点的后续页面靠它带的 cookie 直接过，重新建等于每次重挑战 */
    private static WebView view;
    /** 上一次的目标地址与加载状态：上一次超时放弃后，页面往往还在跑挑战，不能重新 loadUrl 冲掉进度 */
    private static String lastUrl = "";
    private static boolean loading = false;

    /** 结果回传：交给书源服务 WebView 去 evaluateJavascript 通知 JS 侧 Promise */
    static Deliverer deliverer;
    interface Deliverer {
        void deliver(int seq, String json);
    }

    private WebFetch() {}

    /**
     * 抓取一个页面（异步）：立刻返回，结果通过 {@link Deliverer#deliver} 回传。
     * 不阻塞调用线程 —— 这是相对旧 `fetch`（synchronized + CountDownLatch.await 冻住 JS 线程）
     * 的关键改进。
     */
    static void fetchAsync(Context ctx, String url, int timeoutMs, int seq) {
        if (url == null || url.trim().isEmpty()) { deliver(seq, err("地址为空", "")); return; }
        if (ctx == null) { deliver(seq, err("没有上下文", "")); return; }
        final String target = url.trim();
        final int budget = timeoutMs > 1000 ? Math.min(timeoutMs, 45000) : 25000;
        final Pending p = new Pending(seq);
        main.post(() -> start(ctx.getApplicationContext(), target, budget, p));
    }

    /** 一次抓取的句柄：带 seq 与「是否已回传」标记，避免 poll 循环重复投递 */
    private static final class Pending {
        final int seq;
        boolean done;
        Pending(int seq) { this.seq = seq; }
    }

    static void deliver(int seq, String json) {
        if (deliverer != null) deliverer.deliver(seq, json == null ? "" : json);
    }

    /* ---------------- 主线程实现 ---------------- */

    private static void start(Context ctx, String target, int budget, Pending p) {
        final long t0 = System.currentTimeMillis();
        try {
            // WebView 正忙着别的地址：稍后重试（不阻塞调用线程，也不会用 loadUrl 冲掉别人的进度）
            if (loading && !target.equals(lastUrl)) {
                if (System.currentTimeMillis() - t0 < budget) {
                    main.postDelayed(() -> start(ctx, target, budget, p), 700);
                } else {
                    finish(p, err("浏览器内核正忙，未能在 " + (budget / 1000) + " 秒内接入", ""));
                }
                return;
            }
            final WebView w = ensure(ctx);
            final AtomicInteger status = new AtomicInteger(0);
            // 每次抓取换一个 client：上一次的 finish 回调不能影响这一次
            w.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView v, String u, android.graphics.Bitmap fav) {
                    loading = true;
                }

                @Override
                public void onReceivedHttpError(WebView v, WebResourceRequest req, WebResourceResponse res) {
                    if (req != null && req.isForMainFrame() && res != null) status.set(res.getStatusCode());
                }

                @Override
                public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError er) {
                    // 主框架错误不立刻失败：CF 挑战中途常有一次 abort，接着又会自己继续
                }

                @Override
                public void onPageFinished(WebView v, String u) {
                    loading = false;
                    poll(v, u, t0, budget, status, p);
                }
            });
            // 上一次超时放弃时页面还在跑挑战：接着等它跑完，别重新 loadUrl 把进度冲掉
            // （重新加载 = 重新挑战，等于每次都卡在同一秒数上）。
            if (loading && target.equals(lastUrl) && w.getUrl() != null) {
                poll(w, w.getUrl(), t0, budget, status, p);
            } else {
                lastUrl = target;
                loading = true;
                w.loadUrl(target);
            }
            // 兜底：个别页面 onPageFinished 不触发（长连接、框架内跳转），定时轮询也不能断
            main.postDelayed(() -> poll(w, w.getUrl(), t0, budget, status, p), 1500);
            main.postDelayed(() -> poll(w, w.getUrl(), t0, budget, status, p), 4000);
        } catch (Throwable t) {
            finish(p, err("浏览器内核启动失败：" + t.getMessage(), ""));
        }
    }

    /** 挑战页特征：只要有这些就别急着取 HTML，取到的也是验证页 */
    private static boolean isChallenge(String title, String url) {
        if (title != null) {
            if (title.contains("Just a moment")) return true;
            if (title.contains("Attention Required")) return true;
            if (title.contains("Checking your browser")) return true;
            if (title.contains("请稍候")) return true;
        }
        return url != null && url.contains("__cf_chl");
    }

    private static void poll(WebView w, String url, long t0, int budget,
                             AtomicInteger status, Pending p) {
        if (p.done) return;
        String title = "";
        try {
            title = w.getTitle() == null ? "" : w.getTitle();
        } catch (Throwable ignored) {
            /* WebView 已销毁 */
        }
        if (!isChallenge(title, url) && !title.isEmpty()) {
            // 标题正常了不代表 DOM 已稳定（有些站先出标题再渲染列表），留一点时间
            main.postDelayed(() -> grab(w, url, status, t0, budget, p), 700);
            return;
        }
        long spent = System.currentTimeMillis() - t0;
        if (spent > budget) {
            finish(p, err("站点验证未通过（" + (spent / 1000) + " 秒后仍是验证页）", "title=" + title));
            return;
        }
        // 轮询间隔 700ms：挑战本身要跑 JS + 几次网络往返，太密没意义还抢主线程
        main.postDelayed(() -> poll(w, w.getUrl(), t0, budget, status, p), 700);
    }

    private static void grab(WebView w, String url, AtomicInteger status,
                             long t0, int budget, Pending p) {
        if (p.done) return;
        try {
            w.evaluateJavascript(
                    "(function(){try{return document.documentElement.outerHTML||''}catch(e){return ''}})()",
                    value -> {
                        if (p.done) return;
                        String html = unquote(value);
                        if (html.length() < 200 && System.currentTimeMillis() - t0 < budget) {
                            // 空壳页（有些站是 JS 全渲染，晚一点才有内容）；超预算就按现状返回
                            main.postDelayed(() -> grab(w, url, status, t0, budget, p), 900);
                            return;
                        }
                        int st = status.get() > 0 ? status.get() : 200;
                        // 挑战过程里主框架先回 403，过完挑战后同一个 URL 其实是 200：
                        // 把历史 403 带出去，下游会按「被拒绝」把整页丢掉，等于白过挑战。
                        // 只有「页面有实质内容 + 标题已不是验证页」才改写，真 403 不会被吞。
                        if ((st == 403 || st == 503) && html.length() > 3000
                                && !isChallenge(w.getTitle(), url)) {
                            st = 200;
                        }
                        String fu = url == null ? "" : url;
                        finish(p, ok(st, fu, html, System.currentTimeMillis() - t0));
                    });
        } catch (Throwable t) {
            finish(p, err("取页面内容失败：" + t.getMessage(), ""));
        }
    }

    private static WebView ensure(Context appCtx) {
        if (view != null) return view;
        WebView w = new WebView(appCtx);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 与 HTTP 客户端用同一份 UA：CF 会把 UA 也算进指纹，两边不一致等于每次都重新挑战
        s.setUserAgentString(NetClient.DEFAULT_UA);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(w, true);
        } catch (Throwable ignored) {
            /* 老 ROM 没有第三方 cookie 接口 */
        }
        view = w;
        return w;
    }

    /* ---------------- 小工具 ---------------- */

    private static void finish(Pending p, String json) {
        if (p.done) return;
        p.done = true;
        deliver(p.seq, json);
    }

    /** evaluateJavascript 回的是 JSON 字符串字面量（带引号与转义），要还原成原文 */
    private static String unquote(String v) {
        if (v == null || "null".equals(v)) return "";
        try {
            return new JSONArray("[" + v + "]").getString(0);
        } catch (Throwable t) {
            return v;
        }
    }

    /** 转义成 JS 单引号字符串字面量（包级可见：SourceService 回传 JS 时复用） */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    private static String ok(int status, String finalUrl, String html, long ms) {
        return "{\"status\":" + status
                + ",\"finalUrl\":\"" + esc(finalUrl) + "\""
                + ",\"html\":\"" + esc(html) + "\""
                + ",\"ms\":" + ms + "}";
    }

    private static String err(String msg, String detail) {
        return "{\"err\":\"" + esc(msg) + "\",\"detail\":\"" + esc(detail) + "\"}";
    }
}
