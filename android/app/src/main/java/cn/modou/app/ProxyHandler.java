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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * ProxyHandler — /proxy 转发逻辑，与 server/proxy-server.js 行为保持一致：
 * UA 注入 → header/body 透传 → 编码嗅探（响应头 → HTML meta → BOM → utf-8）→ UTF-8 JSON 包装。
 * 返回 JSON：{ok, status, finalUrl, contentType, charset, text}
 */
final class ProxyHandler {
    private static final String TAG = "ProxyHandler";

    static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private ProxyHandler() {
    }

    /**
     * Cookie 罐（按 host）：书源普遍依赖"先开首页拿 cookie 再搜索"的会话流程，
     * 每次裸发会被 403 / 返回空壳页 —— 这是大批源"校验失效"但别的软件能用的常见原因。
     */
    private static final java.util.Map<String, String> COOKIE_JAR = new java.util.concurrent.ConcurrentHashMap<>();

    static String handle(String target, String method, String headerJson, String body) {
        return handle(target, method, headerJson, body, null);
    }

    static String handle(String target, String method, String headerJson, String body, String forceCharset) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(target);
            conn = (HttpURLConnection) url.openConnection();
            String m = method == null || method.isEmpty() ? "GET" : method.toUpperCase();
            conn.setRequestMethod(m);
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            Map<String, String> headers = parseHeader(headerJson);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            // UA：书源 header 未指定时注入默认移动端 UA
            if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
                conn.setRequestProperty("User-Agent", DEFAULT_UA);
            }
            // 明确只要 gzip/deflate：HttpURLConnection 不会自动解压，而且 Java 没有内置 brotli。
            // 不声明的话，Cloudflare 类站点可能直接回 br，正文全成乱码（书源"全失效"的隐蔽原因）。
            if (!headers.containsKey("Accept-Encoding") && !headers.containsKey("accept-encoding")) {
                conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
            }

            String host = url.getHost();
            String origin = url.getProtocol() + "://" + url.getAuthority() + "/";
            // 书源显式给了就以书源为准，否则补会话 cookie 与同域 Referer
            if (!headers.containsKey("Cookie") && !headers.containsKey("cookie")) {
                String ck = COOKIE_JAR.get(host);
                if (ck != null && !ck.isEmpty()) conn.setRequestProperty("Cookie", ck);
            }
            if (!headers.containsKey("Referer") && !headers.containsKey("referer")) {
                conn.setRequestProperty("Referer", origin);
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
            mergeSetCookies(host, conn.getHeaderFields());
            String ct = conn.getContentType();
            if (ct == null) ct = "";
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] buf = maybeDecompress(readAll(is), conn.getHeaderField("Content-Encoding"));
            // 书源规则指定的编码优先：GBK 老站常无 meta 声明，嗅探会退化成 utf-8 导致全页乱码
            String charset = forceCharset != null && !forceCharset.isEmpty() ? forceCharset.toLowerCase() : sniffCharset(buf, ct);
            String text = decodeBody(buf, charset);

            JSONObject j = new JSONObject();
            j.put("ok", status >= 200 && status < 300);
            j.put("status", status);
            j.put("finalUrl", conn.getURL().toString());
            j.put("contentType", ct);
            j.put("charset", charset);
            j.put("text", text);
            return j.toString();
        } catch (Exception e) {
            Log.w(TAG, "proxy fail: " + target, e);
            try {
                JSONObject j = new JSONObject();
                j.put("ok", false);
                j.put("error", String.valueOf(e.getMessage()));
                return j.toString();
            } catch (JSONException je) {
                return "{\"ok\":false,\"error\":\"unknown\"}";
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 合并响应里的 Set-Cookie 到罐里（同名覆盖，过期删除） */
    private static void mergeSetCookies(String host, java.util.Map<String, java.util.List<String>> fields) {
        if (fields == null || host == null) return;
        java.util.List<String> scs = null;
        for (java.util.Map.Entry<String, java.util.List<String>> e : fields.entrySet()) {
            if (e.getKey() != null && "set-cookie".equalsIgnoreCase(e.getKey())) {
                scs = e.getValue();
                break;
            }
        }
        if (scs == null || scs.isEmpty()) return;
        java.util.Map<String, String> cur = new java.util.LinkedHashMap<>();
        String old = COOKIE_JAR.get(host);
        if (old != null) {
            for (String kv : old.split(";\\s*")) {
                int i = kv.indexOf('=');
                if (i > 0) cur.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
            }
        }
        for (String sc : scs) {
            String first = sc.split(";")[0];
            int i = first.indexOf('=');
            if (i <= 0) continue;
            String k = first.substring(0, i).trim();
            String v = first.substring(i + 1).trim();
            boolean expired = v.isEmpty() || sc.toLowerCase().contains("expires=thu, 01 jan 1970");
            if (expired) cur.remove(k);
            else cur.put(k, v);
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : cur.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        if (sb.length() > 0) COOKIE_JAR.put(host, sb.toString());
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
    private static Map<String, String> parseHeader(String headerJson) {
        java.util.HashMap<String, String> out = new java.util.HashMap<>();
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

    /** 编码嗅探：响应头 → HTML meta → BOM → 默认 utf-8（与 dev server 相同顺序） */
    private static String sniffCharset(byte[] buf, String headerCt) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("charset=[\"']?([\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(headerCt == null ? "" : headerCt);
        if (m.find()) return m.group(1).toLowerCase();
        String head = new String(buf, 0, Math.min(buf.length, 4096), StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("charset=[\"']?([\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(head);
        if (m2.find()) return m2.group(1).toLowerCase();
        if (buf.length >= 3 && buf[0] == (byte) 0xEF && buf[1] == (byte) 0xBB && buf[2] == (byte) 0xBF) {
            return "utf-8";
        }
        return "utf-8";
    }

    /**
     * 判断字节流是否「像 UTF-8」。
     * 不用整页 fatal 判定（一个坏字节就会否决整页，反而按 GBK 解出天书）：
     *  - 取前 64KB 做严格校验（中文 GBK 在这段内几乎必然出现非法序列）
     *  - 再以整页解码结果里 U+FFFD 的比例兜底
     */
    private static boolean isStrictUtf8(byte[] buf) {
        if (buf == null || buf.length == 0) return true;
        int probeLen = Math.min(buf.length, 64 * 1024);
        try {
            java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder();
            dec.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            dec.decode(java.nio.ByteBuffer.wrap(buf, 0, probeLen));
        } catch (Exception e) {
            return false;
        }
        String text = new String(buf, StandardCharsets.UTF_8);
        int bad = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\uFFFD') bad++;
        return bad * 200 < Math.max(1, text.length()); // 替换字符 < 0.5%
    }

    private static String decodeBody(byte[] buf, String charset) {
        String declared = charset == null ? "utf-8" : charset.toLowerCase();
        try {
            // 站点常把 UTF-8 页面声明成 gbk（反之亦然），按声明解就是整页乱码 → 规则全命中 0 条。
            // GBK 中文几乎不可能整体是合法 UTF-8，故「合法 UTF-8 + 声明非 utf-8」一律按 UTF-8 解。
            if (!declared.startsWith("utf-8") && !declared.startsWith("utf8") && isStrictUtf8(buf)) {
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
