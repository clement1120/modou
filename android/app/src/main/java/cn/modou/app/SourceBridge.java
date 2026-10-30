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
}
