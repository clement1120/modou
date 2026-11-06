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

    /** 当前进程里活着的实例。后台任务（ShelfUpdateJob）没有 Activity，
     *  要靠它判断「服务是不是已经在跑」，否则会重复绑同一个端口直接失败。 */
    private static volatile LocalServer current;

    /** 保证本地服务在跑：应用在前台时是现成的，纯后台任务里则自己起一个 */
    static boolean ensureRunning(Context c) {
        if (c == null) return false;
        LocalServer s = current;
        if (s != null && s.isAlive()) return true;
        synchronized (LocalServer.class) {
            s = current;
            if (s != null && s.isAlive()) return true;
            try {
                s = new LocalServer(c.getApplicationContext(), MainActivity.PORT);
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                current = s;
                Log.i(TAG, "本地服务已由后台任务启动");
                return true;
            } catch (Throwable t) {
                // 端口被占（多半是前台实例在别的进程/上一轮还没释放）：不抛，让调用方决定
                Log.w(TAG, "ensureRunning failed", t);
                return false;
            }
        }
    }

    public LocalServer(Context ctx, int port) {
        // 只绑回环：NanoHTTPD(int) 会走通配地址（0.0.0.0），同一局域网任何设备
        // 都能访问 39617 端口——/proxy 无鉴权可被当 SSRF 打内网、也能读静态资源。
        // 这个服务只服务于本机 WebView，绑 127.0.0.1 即够，且彻底关掉外部入口。
        super("127.0.0.1", port);
        this.ctx = ctx;
        current = this; // 让后台任务知道「这个进程已经有服务了」
        // 网络层单例在这里就位：其后所有 /proxy 请求都共享它（连接池 / Cookie / DNS 缓存）
        NetClient.init(ctx);
    }

    @Override
    protected Response serve(IHTTPSession session) {
        String uri = session.getUri();
        try {
            // 压测用：不碰网络/资源/数据库，只证明「这个连接被收到了、并且能干净地回一句」。
            // 生产路径上没人调用它，留着也没有攻击面（只监听回环）。
            if ("/__ping".equals(uri)) {
                return Response.newFixedLengthResponse(Status.OK, "text/plain", "pong");
            }
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
                // 连接/读取超时分链路透传：体检链路（校验）连接 8s / 读取 12s，阅读链路 10s / 15s。
                // 由引擎层 service.js 按 isCheck 设好，经 http.js 落到这里。
                int cm = ProxyHandler.DEF_CONNECT, rm = ProxyHandler.DEF_READ;
                try { if (p.get("connect") != null) cm = Integer.parseInt(p.get("connect")); } catch (Exception ignore) {}
                try { if (p.get("read") != null) rm = Integer.parseInt(p.get("read")); } catch (Exception ignore) {}
                String json = ProxyHandler.handle(target, method, headerJson, body, charset, cm, rm);
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
            Response r = Response.newChunkedResponse(Status.OK, mimeOf(path), is);
            // 必须显式禁缓存：以前这里没有任何缓存头，WebView 会按启发式缓存 JS 模块，
            // 于是**覆盖安装后仍在跑上一版的 source.js** —— 用户装了新版、行为还是旧的，
            // 排查时完全看不出是缓存（0.9.117 真机复现：3Q 中文网仍显示 0.9.116 的旧文案）。
            // 这些文件都在 APK 里，读一次是本地 IO，禁缓存没有性能代价。
            r.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            r.addHeader("Pragma", "no-cache");
            r.addHeader("Expires", "0");
            return r;
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
