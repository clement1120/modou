package cn.modou.app;

import android.webkit.JavascriptInterface;

/**
 * SourceBridge — 注入进「书源服务 WebView」的桥。
 *
 * 与 ModouBridge（注入 UI WebView）分开是有意的：两个 WebView 各管一件事，
 * 桥混在一起会让「UI 页面被回收」连带把书源服务也弄死。
 * 这里只做三件事：就绪通知、结果回传、进度/日志。不做任何业务。
 */
public class SourceBridge {
    /** 服务页加载完成、window.ModouSource 已就绪 */
    @JavascriptInterface
    public void onReady() {
        SourceService.get().onBridgeReady();
    }

    /**
     * 结果回传：每个请求**必定**回调一次（成功失败都回），
     * 调用方靠它摘掉超时定时器。
     */
    @JavascriptInterface
    public void onResult(String seq, String json) {
        SourceService.get().onResult(seq, json);
    }

    /** 搜索进度：done/total，用于原生显示「3/12」而不是干等 */
    @JavascriptInterface
    public void onProgress(String seq, int done, int total) {
        SourceService.get().onProgress(seq, done, total);
    }

    @JavascriptInterface
    public void onLog(String msg) {
        SourceService.get().onLog(msg);
    }

    /**
     * 用浏览器内核打开一个页面（异步，立刻返回，结果回调给 JS 里的 Promise）。
     *
     * <p>给「HTTP 客户端过不去」的站点用的：Cloudflare 的 Managed Challenge 要求执行 JS 才给通行证，
     * 而通行证绑 TLS 指纹、离开浏览器内核就作废（实测带同一份 cf_clearance + 同 UA 用 curl 仍 403）。
     * 所以只能在 WebView 里把页面渲染出来，再把渲染后的 HTML 交回给规则引擎。
     *
     * <p>异步是为了不阻塞书源 JS 引擎：同步等待十几秒会把整条引擎冻住，拖垮其它源的搜索。
     * 原生侧取完页面后通过 {@code SourceService.deliverWebFetch} 回调 JS。
     *
     * @param seq JS 侧用于配对 Promise 的序号
     */
    @JavascriptInterface
    public void webFetchAsync(String url, int timeoutMs, int seq) {
        try {
            SourceService s = SourceService.get();
            if (s == null) {
                WebFetch.fetchAsync(null, "", timeoutMs, seq); // 经 fetchAsync 统一回传 err
                return;
            }
            WebFetch.fetchAsync(s.appContext(), url, timeoutMs, seq);
        } catch (Throwable t) {
            WebFetch.deliver(seq, "{\"err\":\"浏览器内核抓取调度失败：" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}");
        }
    }

    /**
     * 把中文关键词编成 GBK 百分号串（`%C8%FD%CC%E5` 这种）。
     *
     * <p>为什么需要原生：老 ASP 站是 GB2312，搜索参数必须按 GBK 编码。
     * 浏览器只有 UTF-8 的 `encodeURIComponent`，把 UTF-8 字节发给 GB2312 站，
     * 站点按 GBK 解出来是一串乱码，永远搜不到 —— 而 Java 的 `URLEncoder` 原生支持 GBK。
     *
     * <p>同步返回（`@JavascriptInterface` 的 String 返回值在 WebView 里可以直接拿到），
     * 失败返回空串，JS 侧会退回 `encodeURIComponent`。
     */
    @JavascriptInterface
    public String gbk(String text) {
        try {
            String q = java.net.URLEncoder.encode(text == null ? "" : text, "GBK");
            return q.replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把「按 UTF-8 编出来的字节」再用 GBK 解一遍，得到乱码原样（「三体」→「涓変綋」）。
     *
     * <p>这是判据，不是用来显示的：GB2312/GBK 站点把 UTF-8 关键词解错后，页面上出现的
     * 就是这串东西。看到它等于拿到「关键词编码发错了」的铁证，比「页面里有没有原词」可靠：
     * 搜「我的」时页面导航里本来就有「我的书架」，旧判据会被这句骗过去，永远不重发。
     */
    @JavascriptInterface
    public String mojibake(String text) {
        try {
            byte[] b = (text == null ? "" : text).getBytes("UTF-8");
            return new String(b, "GBK");
        } catch (Exception e) {
            return "";
        }
    }
}
