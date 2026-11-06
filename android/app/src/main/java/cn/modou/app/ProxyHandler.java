package cn.modou.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

/**
 * ProxyHandler — /proxy 转发逻辑，与 server/proxy-server.js 行为保持一致：
 * UA 注入 → header/body 透传 → 编码嗅探（响应头 → HTML meta → BOM → utf-8）→ UTF-8 JSON 包装。
 * 返回 JSON：{ok, status, finalUrl, contentType, charset, text}
 */
final class ProxyHandler {
    private static final String TAG = "ProxyHandler";

    /** 默认连接/读取超时（毫秒）：阅读链路（规约：阅读 连接 10s / 读取 15s）。包级可见，供 LocalServer 复用同一套兜底默认值 */
    static final int DEF_CONNECT = 10000;
    static final int DEF_READ = 15000;

    /** 与 NetClient 保持同一份 UA（老调用点仍引用这里的常量） */
    static final String DEFAULT_UA = NetClient.DEFAULT_UA;

    /* ---------------- TLS 校验档位 ---------------- */

    /** 严格校验证书链（默认，99% 的书源走这里） */
    private static final int TLS_STRICT = 0;
    /** 证书异常后已降级到 http 明文重试 */
    private static final int TLS_HTTP = 1;
    /** 证书异常后已忽略校验（仅本连接、用后即弃） */
    private static final int TLS_INSECURE = 2;

    /**
     * host → 上次成功的降级档位 [mode, 记录时间]。
     *
     * <p>证书坏了的站点，每次请求都要先赔一次失败的 TLS 握手（实测 ~4s）。一章正文
     * 加目录加详情就是十几秒纯等待，读一本书能拖到几分钟。记 10 分钟，期间直接按
     * 上次成功的档位发请求；站方续了证书，到期后自动回到严格校验。
     *
     * <p>为什么不是更久：降级的连接是不校验证书的，记忆期越长，「中间人正好在这段时间
     * 伪装成这个站」的窗口就越大。10 分钟够读完一章，也不给攻击者留下长期立足点。
     */
    private static final Map<String, long[]> TLS_MEMO = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TLS_MEMO_TTL = 10 * 60 * 1000L;



    private ProxyHandler() {
    }

    /**
     * 转发一个请求。
     *
     * <p>连接不在这里 disconnect —— 读完流就让连接回连接池。
     * 旧实现每个请求 finally disconnect()，等于每次搜索都重做一遍 TCP + TLS 握手；
     * 134 个源就是 134 次，这才是"搜书慢"里最大的一块，比解析慢得多。
     */
    static String handle(String target, String method, String headerJson, String body) {
        return handle(target, method, headerJson, body, null);
    }

    static String handle(String target, String method, String headerJson, String body, String forceCharset) {
        return handle(target, method, headerJson, body, forceCharset, false, 0, DEF_CONNECT, DEF_READ);
    }

    /** 带连接/读取超时的入口：由 LocalServer 从 /proxy?connect=&read= 解析后调用 */
    static String handle(String target, String method, String headerJson, String body,
                         String forceCharset, int connectMs, int readMs) {
        return handle(target, method, headerJson, body, forceCharset, false, 0, connectMs, readMs);
    }

    /**
     * 被反爬拦下来的第二次尝试。
     *
     * 403 / 429 / 503 是书源站最常见的三种「拦你」回复：第一次几乎是「无 Referer +
     * 默认 UA」，站点直接甩 403。Legado 在这一步会补 Referer 并换 UA 再试一次，
     * 我们原来是直接把 403 当「源失效」报给用户 —— 一整个源库在体检里就全红了。
     *
     * 这里只重试一次（retried 标记），且只在书源自己没写 Referer/UA 时才覆盖，
     * 不破坏书源的自定义配置。
     */
    private static String handle(String target, String method, String headerJson, String body,
                                 String forceCharset, boolean retried) {
        return handle(target, method, headerJson, body, forceCharset, retried, 0, DEF_CONNECT, DEF_READ);
    }

    /**
     * 主实现（tls 见 TLS_* 常量）。
     *
     * <p>为什么要给 TLS 留档位：书源站大量是个人站，Let's Encrypt 证书到期不续是常态
     * （笔趣阁 la 就是 2026-06-29 到期后一直没续）。浏览器会弹「继续前往」，用户能读；
     * 而 Java 的 TrustManager 是硬失败，表现为「HTTPS 握手失败（证书异常）」——
     * 用户看到的就是「这个源坏了」，其实站点内容完好、只是证书没人管。
     *
     * <p>降级顺序（都只在严格校验失败后触发，正常站点不受影响）：
     * <ol>
     *   <li>换 http 同地址重试：证书只管 https，明文往往还能通；且能顺着站点的 301
     *       跳到新域名，后续目录/正文就都正常了（比"信任坏证书"更干净）；</li>
     *   <li>http 也不通 → 忽略证书校验再试一次 https。这条连接用完即弃（Connection: close），
     *       不会被连接池复用到后续的严格请求上。</li>
     * </ol>
     * 无论走哪条，响应里都带 {@code ssl}/{@code sslNote}，调试台会明写「已降级」，
     * 不做静默处理 —— 用户有权知道自己正在读一个证书有问题的站。
     */
    private static String handle(String target, String method, String headerJson, String body,
                                 String forceCharset, boolean retried, int hops,
                                 int connectMs, int readMs) {
        return handle(target, method, headerJson, body, forceCharset, retried, hops,
                connectMs, readMs, TLS_STRICT);
    }

    private static String handle(String target, String method, String headerJson, String body,
                                 String forceCharset, boolean retried, int hops,
                                 int connectMs, int readMs, int tls) {
        HttpURLConnection conn = null;
        boolean broken = false;
        try {
            // GBK 老站：关键词在 URL 里是 UTF-8 的百分号转义，站点按自己的编码去解 →
            // 关键词变乱码 → 返回空列表 → 整源被判「失效」。这就是「搜书一本书都搜不到」
            // 里最隐蔽的一条。POST 的 body 下面另有处理，这里是 GET 的查询串。
            if (isGbkFamily(forceCharset)) target = reencodeUrlAsGbk(target);
            // 书源里拼出来的地址经常带中文（书名、作者直接接在路径上），
            // new URL() 遇到裸中文直接抛异常 → 详情/目录整条链路失败。补成 %xx。
            target = safeUrl(target);
            // 这个 host 上次就是靠降级才通的 → 别再赔一次握手失败（见 TLS_MEMO 注释）
            if (tls == TLS_STRICT) {
                int memo = memoMode(target);
                if (memo != TLS_STRICT) {
                    if (memo == TLS_HTTP) {
                        String p = toHttpUrl(target);
                        if (p != null) {
                            target = p;
                            tls = TLS_HTTP;
                        }
                    } else {
                        tls = TLS_INSECURE;
                    }
                }
            }
            URL url = new URL(target);
            conn = (HttpURLConnection) url.openConnection();
            String m = method == null || method.isEmpty() ? "GET" : method.toUpperCase();
            conn.setRequestMethod(m);
            // 自己跟跳转：HttpURLConnection 的自动跟随在 https→http 时会放弃，
            // 而且跨域跳转会把书源自定义的 header 丢掉（很多站的搜索结果页就是 302 过去的）。
            conn.setInstanceFollowRedirects(false);
            // 连接/读取超时按链路细分：体检链路（校验）连接 8s / 读取 12s，阅读链路 10s / 15s，
            // 由上层 /proxy?connect=&read= 透传；默认走阅读链路值。慢站被过长超时一刀切，
            // 正是「Legado 能用、体检里全红」的元凶之一。
            conn.setConnectTimeout(connectMs > 0 ? connectMs : DEF_CONNECT);
            conn.setReadTimeout(readMs > 0 ? readMs : DEF_READ);
            // 连接池保活：HttpURLConnection 默认就是 keep-alive，这里显式写清楚，
            // 避免以后有人看到"没设置"又去加 useCaches/Connection: close
            conn.setRequestProperty("Connection", "keep-alive");

            NetClient nc = NetClient.get();
            Map<String, String> headers = parseHeader(headerJson);
            if (nc != null) {
                // 统一头注入：UA / Referer / Accept / Cookie，一处补齐，书源自己写了的优先
                headers = nc.headersFor(target, headers);
                nc.warmDns(url.getHost()); // DNS 预热：把首次解析挪到后台
            } else {
                if (!contains(headers, "User-Agent")) headers.put("User-Agent", DEFAULT_UA);
                if (!contains(headers, "Accept-Encoding")) headers.put("Accept-Encoding", "gzip, deflate");
            }
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            // 忽略证书校验（仅 TLS_INSECURE）：SSLSocketFactory 必须在连接建立前挂上。
            // 用「我们自己的 factory 实例」还有个附带好处：HttpURLConnection 的连接池
            // 按 Address（含 socketFactory 引用）分桶，天然和严格连接分开，不会互相串。
            if (tls == TLS_INSECURE && conn instanceof HttpsURLConnection) {
                SSLSocketFactory f = trustAllFactory();
                HttpsURLConnection hc = (HttpsURLConnection) conn;
                if (f != null) hc.setSSLSocketFactory(f);
                hc.setHostnameVerifier((h, s) -> true);
                // 双保险：这条连接用完即弃，绝不被后续严格请求复用
                conn.setRequestProperty("Connection", "close");
            }

            if (!"GET".equals(m) && !"HEAD".equals(m) && body != null) {
                conn.setDoOutput(true);
                // 请求体编码跟随书源指定的 charset（GBK 站的搜索词必须按 GBK 编码，
                // 否则服务端收到乱码 → 返回空列表 → 整源判失效）
                Charset bodyCs = StandardCharsets.UTF_8;
                if (forceCharset != null && !forceCharset.isEmpty()) {
                    try {
                        bodyCs = Charset.forName(forceCharset);
                    } catch (Exception ignore) {
                        /* 未知编码回落 UTF-8 */
                    }
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(bodyCs));
                }
            }

            int status = conn.getResponseCode();
            if (status >= 300 && status < 400 && hops >= 5) {
                broken = true;
                return errJson("重定向次数过多（>5），站点可能有反爬跳转");
            }
            // 手动跟跳转：保留书源 header（301/302/303 转 GET，307/308 保持原方法）
            if (status >= 300 && status < 400 && hops < 5) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && !loc.trim().isEmpty()) {
                    String next;
                    try {
                        next = new URL(conn.getURL(), loc.trim()).toString();
                    } catch (Exception bad) {
                        next = loc.trim();
                    }
                    if (nc != null) {
                        try {
                            nc.mergeSetCookies(new URL(next).getHost(), conn.getHeaderFields());
                        } catch (Exception ignore) {
                            /* 合并失败不影响跳转 */
                        }
                    }
                    // 301/302/303 按浏览器与 OkHttp 的语义转成 GET：把 POST body 带着
                    // 重发到结果页，不少站会回 405 或直接吐一个空壳页。
                    // 只有 307/308 明确要求「保持原方法」。
                    boolean keep = status == 307 || status == 308;
                    String m2 = keep ? m : "GET";
                    String b2 = keep ? body : null;
                    broken = true; // 让 finally 释放这条连接
                    // 跨域跳转要同步改 Referer：52书库书源写在旧域名 po5.net，
                    // 301 到 www.52shuku.net 时把 po5.net 的 Referer 带过去，
                    // 对方 WAF 直接回 "Access Denied: Invalid Referer." 403。
                    return handle(next, m2, refererForHop(headerJson, target, next), b2,
                            forceCharset, retried, hops + 1, connectMs, readMs, tls);
                }
            }
            // 被拦 → 补 Referer + 换 UA 重试一次（见方法头的说明）
            if (!retried && (status == 403 || status == 429 || status == 503)) {
                String h2 = retryHeaders(target, headerJson);
                if (h2 != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignore) {
                        /* 释放失败无所谓，反正要重开 */
                    }
                    return handle(target, method, h2, body, forceCharset, true, hops, connectMs, readMs, tls);
                }
            }
            String finalHost = conn.getURL().getHost();
            if (nc != null) {
                // 归到"最终 URL"的域名下：跨域跳转拿到的会话 cookie 挂错域名，搜索会一直是空壳页
                nc.mergeSetCookies(finalHost, conn.getHeaderFields());
            }
            String ct = conn.getContentType();
            if (ct == null) ct = "";
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] buf = maybeDecompress(readAll(is), conn.getHeaderField("Content-Encoding"));
            // 书源规则指定的编码优先：GBK 老站常无 meta 声明，嗅探会退化成 utf-8 导致全页乱码
            String charset = forceCharset != null && !forceCharset.isEmpty()
                    ? forceCharset.toLowerCase(java.util.Locale.ROOT)
                    : NetClient.sniffCharset(buf, ct);
            String text = decodeBody(buf, charset);

            JSONObject j = new JSONObject();
            j.put("ok", status >= 200 && status < 300);
            j.put("status", status);
            j.put("finalUrl", conn.getURL().toString());
            j.put("contentType", ct);
            j.put("charset", charset);
            j.put("text", text);
            // 证书降级的痕迹必须带出去：引擎写进 trace，调试台原样展示。
            // 不做静默降级 —— 用户有权知道「这个站证书有问题，我正用降级方式在读」。
            if (tls == TLS_HTTP) {
                j.put("ssl", "http");
                j.put("sslNote", "站点 HTTPS 证书异常，已改用 http 访问");
            } else if (tls == TLS_INSECURE) {
                j.put("ssl", "insecure");
                String why = certNote(conn);
                j.put("sslNote", "已忽略证书校验（" + (why.isEmpty() ? "站点证书不受信任" : why) + "）");
            }
            // 透传少数响应头给引擎层：cf-mitigated 用于识别真挑战页；
            // server / cf-ray / content-type 只作调试台展示线索，不参与风控判定
            // （站点挂 Cloudflare 时这两个头每个响应都有，拿它判风控必然误伤）。
            JSONObject hdrs = new JSONObject();
            try {
                String cf = conn.getHeaderField("cf-ray");
                if (cf != null && !cf.isEmpty()) hdrs.put("cf-ray", cf);
                String srv = conn.getHeaderField("server");
                if (srv != null && !srv.isEmpty()) hdrs.put("server", srv);
                String ctp = conn.getHeaderField("content-type");
                if (ctp != null && !ctp.isEmpty()) hdrs.put("content-type", ctp);
                // cf-mitigated: challenge 才是「确实下发了挑战」的硬证据。
                // 注意 cf-ray / server: cloudflare 只是走了 CDN，正常页面全都带，
                // 绝不能单独据此判风控（0.9.82 就是这么把 52书库的正常搜索页判死的）。
                String cfm = conn.getHeaderField("cf-mitigated");
                if (cfm != null && !cfm.isEmpty()) hdrs.put("cf-mitigated", cfm);
            } catch (Exception ignore) { /* getHeaderField 极少数实现会抛 */ }
            j.put("headers", hdrs);
            return j.toString();
        } catch (java.net.SocketTimeoutException e) {
            broken = true;
            Log.w(TAG, "proxy timeout: " + target);
            // 超时是最常见的一类：必须和「403」「规则没命中」分开写，
            // 否则用户拿到的永远是一句「源失效」，无从判断该等还是该换源。
            return errJson("请求超时（站点无响应）");
        } catch (java.net.UnknownHostException e) {
            broken = true;
            return errJson("域名解析失败（可能已被墙或域名过期）: " + e.getMessage());
        } catch (javax.net.ssl.SSLException e) {
            broken = true;
            String why = sslReason(e);
            if (tls == TLS_STRICT) {
                // ① 先试 http 同地址：证书只管 https，明文往往还通。这条路比"信任坏证书"
                //    更干净 —— 站点普遍把 http 301 到新域名，后续目录/正文就彻底绕开坏证书了。
                String plain = toHttpUrl(target);
                if (plain != null) {
                    Log.w(TAG, "TLS 失败，降级 http 重试: " + target + "（" + why + "）");
                    rememberTls(target, TLS_HTTP);
                    return handle(plain, method, headerJson, body, forceCharset, retried,
                            hops, connectMs, readMs, TLS_HTTP);
                }
                // ② 转不成 http（已经是明文）→ 直接忽略证书再试一次
                Log.w(TAG, "TLS 失败，忽略证书重试: " + target + "（" + why + "）");
                rememberTls(target, TLS_INSECURE);
                return handle(target, method, headerJson, body, forceCharset, retried,
                        hops, connectMs, readMs, TLS_INSECURE);
            }
            if (tls == TLS_HTTP) {
                // 站点把 http 又 301 回了 https，而证书依旧坏 → 最后一条路：忽略校验
                Log.w(TAG, "http 仍不通，忽略证书重试: " + target + "（" + why + "）");
                rememberTls(target, TLS_INSECURE);
                return handle(target, method, headerJson, body, forceCharset, retried,
                        hops, connectMs, readMs, TLS_INSECURE);
            }
            // 忽略校验后仍握手失败 = 真的连不上（协议不支持 / 被中间设备重置）
            return errJson("HTTPS 握手失败（" + why + "），已尝试 http 与忽略证书均未成功: "
                    + e.getMessage());
        } catch (Exception e) {
            broken = true;
            Log.w(TAG, "proxy fail: " + target, e);
            return errJson(String.valueOf(e.getMessage()));
        } finally {
            // 正常路径不 disconnect（连接回池复用）；只有异常中断、连接还挂着时才主动释放
            if (conn != null && broken) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                    /* 释放失败也没别的办法 */
                }
            }
        }
    }

    /** 大小写不敏感的存在性判断（书源 header 里 user-agent / User-Agent 都有人写） */
    private static boolean contains(Map<String, String> h, String key) {
        for (String k : h.keySet()) {
            if (k != null && k.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static String errJson(String msg) {
        try {
            JSONObject j = new JSONObject();
            j.put("ok", false);
            j.put("error", msg == null || msg.isEmpty() ? "未知错误" : msg);
            return j.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }

    /* ---------------- 证书异常：分类 + 降级支撑 ---------------- */

    /**
     * 把 SSL 异常翻译成人话。
     *
     * <p>Android 统一抛「Chain validation failed」，用户看到只会一脸问号 ——
     * 到底是证书过期、域名不匹配，还是自签名？三者处理方式完全不同（等站方续证书 /
     * 换域名 / 只能忽略），必须分开说。
     */
    private static String sslReason(Throwable e) {
        Throwable t = e;
        for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
            String n = t.getClass().getSimpleName();
            String m = t.getMessage() == null ? ""
                    : t.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("CertExpired") || m.contains("expired") || m.contains("notafter")) {
                return "站点证书已过期";
            }
            if (n.contains("SSLPeerUnverified") || n.contains("HostnameVerifier")
                    || m.contains("hostname") || m.contains("no subject alternative")) {
                return "证书域名与实际访问域名不符";
            }
            if (m.contains("unable to find valid certification path") || m.contains("chain validation")
                    || n.contains("CertPathValidator") || m.contains("untrusted")) {
                return "证书链不完整或不受信任";
            }
        }
        return "证书校验失败";
    }

    /** https:// → http://；不是 https 返回 null（没得降级） */
    static String toHttpUrl(String url) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8)) return null;
        return "http://" + url.substring(8);
    }

    /** 异常链里有没有 TLS 握手/证书问题（封面这类独立下载也要按它决定是否降级） */
    static boolean isSslError(Throwable e) {
        for (int i = 0; e != null && i < 8; i++, e = e.getCause()) {
            if (e instanceof javax.net.ssl.SSLException) return true;
        }
        return false;
    }

    /** 查这个 host 上次是靠哪个档位通的（超时自动忘掉，让站方续证书后能回严格校验） */
    private static int memoMode(String url) {
        String h = NetClient.hostOf(url);
        if (h == null) return TLS_STRICT;
        long[] v = TLS_MEMO.get(h);
        if (v == null) return TLS_STRICT;
        if (System.currentTimeMillis() - v[1] > TLS_MEMO_TTL) {
            TLS_MEMO.remove(h);
            return TLS_STRICT;
        }
        return (int) v[0];
    }

    private static void rememberTls(String url, int mode) {
        String h = NetClient.hostOf(url);
        if (h != null) TLS_MEMO.put(h, new long[]{mode, System.currentTimeMillis()});
    }

    /** 忽略校验成功后，从服务端证书里读出到期日 —— 让用户知道是「站方没续」而不是自己的问题 */
    private static String certNote(HttpURLConnection conn) {
        try {
            if (!(conn instanceof HttpsURLConnection)) return "";
            java.security.cert.Certificate[] cs = ((HttpsURLConnection) conn).getServerCertificates();
            if (cs == null || cs.length == 0 || !(cs[0] instanceof X509Certificate)) return "";
            java.util.Date na = ((X509Certificate) cs[0]).getNotAfter();
            if (na == null) return "";
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
            if (na.getTime() < System.currentTimeMillis()) {
                return "站点证书已于 " + f.format(na) + " 过期";
            }
            return "站点证书不受信任（到期日 " + f.format(na) + "）";
        } catch (Throwable ignore) {
            return "";
        }
    }

    /** 全信任 factory：懒加载一次，之后复用（实例唯一即可与严格连接分池） */
    private static volatile SSLSocketFactory trustAll;

    private static SSLSocketFactory trustAllFactory() {
        SSLSocketFactory f = trustAll;
        if (f != null) return f;
        synchronized (ProxyHandler.class) {
            if (trustAll != null) return trustAll;
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new javax.net.ssl.TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        /* 不校验：调用方已明确降级 */
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        /* 同上 */
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, new java.security.SecureRandom());
                trustAll = ctx.getSocketFactory();
            } catch (Throwable t) {
                Log.w(TAG, "trustAll factory init failed", t);
            }
            return trustAll;
        }
    }

    /**
     * 把 URL 里未转义的中文 / 空格补成 %xx（已编码的 %xx 原样保留）。
     * 书源把书名、作者直接拼进路径时（/book/我的姐姐），new URL() 会直接抛
     * IllegalArgumentException —— 表现为「详情打不开」，但源本身是好的。
     */
    static String safeUrl(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' || (c < 0x7F && c != ' ')) {
                b.append(c);
                continue;
            }
            byte[] bs;
            try {
                bs = String.valueOf(c).getBytes("UTF-8");
            } catch (Exception e) {
                b.append(c);
                continue;
            }
            for (byte x : bs) {
                b.append('%').append(String.format(java.util.Locale.ROOT, "%02X", x & 0xFF));
            }
        }
        return b.toString();
    }

    /** 桌面 Chrome UA：被识别成手机浏览器时，部分站点会直接甩一个「请用电脑访问」的空壳页 */
    private static final String RETRY_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * 构造「重试用」的请求头 JSON：补同源 Referer、换成桌面 UA。
     * 书源自己写了这两个头就不动它（那是作者针对站点调过的），返回 null 表示不值得重试。
     *
     * 例外：Referer 与目标**跨域**时必须改写 —— 那种 Referer 多半是从别处（书源地址、
     * 上一跳）继承来的，站点校验不过就是 403，重试一次的成本远低于让用户以为源失效。
     */
    private static String retryHeaders(String target, String headerJson) {
        try {
            JSONObject h = headerJson == null || headerJson.trim().isEmpty()
                    ? new JSONObject() : new JSONObject(headerJson);
            URL u = new URL(target);
            boolean changed = false;
            String refKey = null;
            String refVal = null;
            java.util.Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("referer".equalsIgnoreCase(k)) {
                    refKey = k;
                    refVal = h.optString(k, "");
                    break;
                }
            }
            boolean crossHost = refVal != null && !u.getHost().equalsIgnoreCase(NetClient.hostOf(refVal));
            if (refVal == null || refVal.trim().isEmpty() || crossHost) {
                h.put(refKey != null ? refKey : "Referer", u.getProtocol() + "://" + u.getHost() + "/");
                changed = true;
            }
            if (!hasKeyIgnoreCase(h, "User-Agent")) {
                h.put("User-Agent", RETRY_UA);
                changed = true;
            }
            return changed ? h.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 跨域跳转时修正 Referer。
     *
     * 浏览器语义会把原页面的 Referer 带到跳转后的地址，但对端 WAF 常常要求 Referer 与目标同源，
     * 跨域 Referer 一律按盗链拒掉（52书库：po5.net → 52shuku.net，回 31 字节
     * "Access Denied: Invalid Referer."，状态 403，看起来和"被封"一模一样）。
     *
     * 只在两种情况下改写：Referer 缺失，或 Referer 的 host 恰好等于「上一跳 host」
     * （说明它是按同源自动带上的）。源里显式写死第三方 Referer（图床、API 白名单）时保持原样。
     */
    private static String refererForHop(String headerJson, String fromUrl, String toUrl) {
        try {
            String fromHost = NetClient.hostOf(fromUrl);
            String toHost = NetClient.hostOf(toUrl);
            if (toHost == null || toHost.equalsIgnoreCase(fromHost == null ? "" : fromHost)) return headerJson;

            JSONObject h = headerJson == null || headerJson.trim().isEmpty()
                    ? new JSONObject() : new JSONObject(headerJson);
            String refKey = null;
            String refVal = null;
            java.util.Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                if ("referer".equalsIgnoreCase(k)) {
                    refKey = k;
                    refVal = h.optString(k, "");
                    break;
                }
            }
            boolean inherited = refVal != null && fromHost != null && fromHost.equalsIgnoreCase(NetClient.hostOf(refVal));
            if (refVal != null && !refVal.trim().isEmpty() && !inherited) return headerJson;

            URL u = new URL(toUrl);
            String ref = u.getProtocol() + "://" + u.getHost() + "/";
            h.put(refKey != null ? refKey : "Referer", ref);
            return h.toString();
        } catch (Exception e) {
            return headerJson;
        }
    }

    private static boolean hasKeyIgnoreCase(JSONObject o, String key) {
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            if (key.equalsIgnoreCase(it.next())) return true;
        }
        return false;
    }

    /**
     * 解压响应体。大量书源站无视 Accept-Encoding 直接返回 gzip/br 字节，
     * 不解压就当文本解码 → 页面乱码 → bookList 命中 0 条（"书源全失效"的头号原因）。
     * 既看 Content-Encoding，也按压缩 magic 兜底（0x1F8B / 0x78）。
     */
    private static byte[] maybeDecompress(byte[] buf, String contentEncoding) {
        if (buf == null || buf.length < 2) return buf;
        String ce = contentEncoding == null ? "" : contentEncoding.toLowerCase();
        boolean gzip = ce.contains("gzip") || ((buf[0] & 0xFF) == 0x1F && (buf[1] & 0xFF) == 0x8B);
        boolean deflate = ce.contains("deflate") || (buf[0] == 0x78);
        try {
            if (gzip) {
                return readAll(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(buf)));
            }
            if (deflate) {
                return readAll(new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(buf)));
            }
        } catch (Exception e) {
            Log.w(TAG, "proxy decompress failed: " + ce, e);
        }
        return buf;
    }

    /** Legado 书源 header 存的是 JSON 字符串，解析为键值对 */
    /** GBK 系编码：这些老站会按自己的编码解释查询串里的百分号转义 */
    private static boolean isGbkFamily(String cs) {
        if (cs == null) return false;
        String s = cs.trim().toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("gbk") || s.startsWith("gb2312") || s.startsWith("gb18030")
                || s.startsWith("cp936") || s.startsWith("x-gbk");
    }

    /**
     * 把 URL 里按 UTF-8 转义的中文重编码成 GBK。
     *
     * 书源 searchUrl 里的 {{key}} 是 encodeURIComponent 出来的（UTF-8 百分号转义），
     * 而 GBK 老站按自己的编码去解 → 关键词变乱码 → 返回 0 条 → 整个源被判失效。
     * 纯 ASCII 的转义（%2F、%20）解码后仍是 ASCII，原样保留，不会破坏已有地址。
     */
    static String reencodeUrlAsGbk(String url) {
        if (url == null || url.isEmpty()) return url;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?:%[0-9A-Fa-f]{2})+").matcher(url);
        StringBuffer out = new StringBuffer(url.length() + 32);
        while (m.find()) {
            String hex = m.group();
            String rep = hex;
            byte[] raw = new byte[hex.length() / 3];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) Integer.parseInt(hex.substring(i * 3 + 1, i * 3 + 3), 16);
            }
            try {
                // 严格解码：原本就是 GBK 转义（非法 UTF-8）时保留原样，不能二次编码
                String decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw)).toString();
                boolean ascii = true;
                for (int i = 0; i < decoded.length(); i++) {
                    if (decoded.charAt(i) > 0x7F) { ascii = false; break; }
                }
                if (!ascii) rep = pctEncode(decoded.getBytes("GBK"));
            } catch (Exception ignore) {
                /* 非法 UTF-8：说明本来就是别的编码，保持原样 */
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String pctEncode(byte[] bs) {
        StringBuilder b = new StringBuilder(bs.length * 3);
        for (byte x : bs) {
            b.append('%');
            String h = Integer.toHexString(x & 0xFF).toUpperCase(java.util.Locale.ROOT);
            if (h.length() < 2) b.append('0');
            b.append(h);
        }
        return b.toString();
    }

    private static Map<String, String> parseHeader(String headerJson) {        java.util.HashMap<String, String> out = new java.util.HashMap<>();
        if (headerJson == null || headerJson.isEmpty()) return out;
        try {
            JSONObject o = new JSONObject(headerJson);
            JSONArray names = o.names();
            if (names == null) return out;
            for (int i = 0; i < names.length(); i++) {
                String k = names.getString(i);
                out.put(k, String.valueOf(o.get(k)));
            }
        } catch (JSONException e) {
            /* 坏 header 忽略 */
        }
        return out;
    }

    private static String decodeBody(byte[] buf, String charset) {
        String declared = charset == null ? "utf-8" : charset.toLowerCase();
        try {
            // 站点常把 UTF-8 页面声明成 gbk（反之亦然），按声明解就是整页乱码 → 规则全命中 0 条。
            // GBK 中文几乎不可能整体是合法 UTF-8，故「合法 UTF-8 + 声明非 utf-8」一律按 UTF-8 解。
            if (!declared.startsWith("utf-8") && !declared.startsWith("utf8")
                    && NetClient.isStrictUtf8(buf)) {
                return new String(buf, StandardCharsets.UTF_8);
            }
            return new String(buf, Charset.forName(declared));
        } catch (Exception e) {
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream is) throws java.io.IOException {
        if (is == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] tmp = new byte[16384];
        int n;
        while ((n = is.read(tmp)) != -1) bos.write(tmp, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
