package cn.modou.app;

import android.content.Context;
import android.util.Log;

import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * LocalServer — 壳内本地 HTTP 服务（对应 Node dev server 的双重职责）：
 *   1. 静态服务 assets/web/（SPA）
 *   2. /proxy 代理转发（UA 注入、编码嗅探、UTF-8 JSON 包装）
 *
 * WebView 指向 http://127.0.0.1:PORT/ ，前端 http.js 的 location.origin 自动正确，
 * 引擎与 UI 代码零改动——与桌面开发时走的是同一条代码路径。
 */
public class LocalServer extends NanoHTTPD {
    private static final String TAG = "LocalServer";
    private final Context ctx;

    public LocalServer(Context ctx, int port) {
        super(port);
        this.ctx = ctx;
    }

    @Override
    protected Response serve(IHTTPSession session) {
        String uri = session.getUri();
        try {
            if ("/proxy".equals(uri)) {
                Map<String, String> p = session.getParms();
                String target = p.get("url");
                if (target == null || target.isEmpty()) {
                    return Response.newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "missing url");
                }
                String method = p.get("method");
                String headerJson = p.get("header");
                String body = p.get("body");
                String charset = p.get("charset");
                String json = ProxyHandler.handle(target, method, headerJson, body, charset);
                return Response.newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", json);
            }
            return serveAsset(uri);
        } catch (Exception e) {
            Log.e(TAG, "serve error: " + uri, e);
            return Response.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "server error");
        }
    }

    /** 静态资源：assets/web/ 下取文件；防目录穿越 */
    private Response serveAsset(String uri) {
        String path = uri;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        if (path.isEmpty() || "/".equals(path)) path = "/index.html";
        if (path.contains("..")) {
            return Response.newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "");
        }
        String assetPath = "web" + path;
        try {
            InputStream is = ctx.getAssets().open(assetPath);
            return Response.newChunkedResponse(Status.OK, mimeOf(path), is);
        } catch (IOException e) {
            return Response.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "not found");
        }
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
