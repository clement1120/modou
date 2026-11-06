package cn.modou.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;
import org.mozilla.universalchardet.UniversalDetector;

import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NetClient — 全局网络层（单例）。所有书源请求都从 `/proxy` 进来，最终汇到这里。
 *
 * <p>为什么不是「直接 new OkHttpClient」：本项目零第三方依赖、离线构建，
 * 而 Android 的 {@code HttpURLConnection} 底层实现（libcore）本来就是 OkHttp ——
 * 连接池、HTTP/2、keep-alive 都在系统里。真正拖慢搜索的不是"没用 OkHttp"，
 * 而是旧代码每个请求结束时 {@code disconnect()}，把刚建好的 TCP+TLS 直接掐了：
 * 134 个源 = 134 次三次握手 + 134 次 TLS 协商。这里改成复用连接池。
 *
 * <p>四件事：
 * <ol>
 *   <li>连接池保活：不再主动 disconnect，只关流；同一 host 复用连接；</li>
 *   <li>DNS 预热缓存：host 首次出现时后台解析一次，结果进系统 DNS 缓存，
 *       后续同域连接省掉解析 RTT（10 分钟内不重复解析）；</li>
 *   <li>统一请求头：UA / Referer / Accept / Accept-Language 一处注入，
 *       书源自己指定的一律优先，不覆盖；</li>
 *   <li>Cookie：按 host 保存，落 SharedPreferences，重启 App 仍然有效
 *       （大量书源是「先开首页拿 cookie 再搜索」的会话流程）。</li>
 * </ol>
 */
final class NetClient {

    private static final String TAG = "NetClient";

    /** 移动端 Chrome UA：不少站点按 UA 分流，桌面 UA 会拿到精简页/校验页 */
    static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private static final String SP_NAME = "modou_net";
    private static final String KEY_JAR = "cookie_jar";
    /** 最多记多少个域名：再多也没人登，反而把启动开销拖长 */
    private static final int MAX_HOSTS = 256;
    /** 单域名 cookie 串长度上限，防某站写几百条把存档撑爆 */
    private static final int MAX_COOKIE_LEN = 4096;

    private static volatile NetClient instance;

    static void init(Context c) {
        if (instance != null || c == null) return;
        synchronized (NetClient.class) {
            if (instance == null) instance = new NetClient(c.getApplicationContext());
        }
    }

    static NetClient get() {
        return instance;
    }

    /* ---------------- 状态 ---------------- */

    private final SharedPreferences sp;
    /** host → "k=v; k2=v2" */
    private final Map<String, String> jar = new ConcurrentHashMap<>();
    private final Map<String, Long> used = new ConcurrentHashMap<>();
    private final Map<String, Long> dnsWarm = new ConcurrentHashMap<>();

    private final Object saveLock = new Object();
    private boolean savePending = false;

    private NetClient(Context c) {
        sp = c.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        loadJar();
    }

    /* ---------------- 1. 统一请求头 ---------------- */

    /**
     * 给一次请求补齐默认头。书源显式写了的一律不覆盖 ——
     * 有些源就是要桌面 UA、就是要空 Referer，强行改反而 403。
     */
    Map<String, String> headersFor(String url, Map<String, String> given) {
        Map<String, String> h = new LinkedHashMap<>();
        if (given != null) h.putAll(given);
        putIfAbsent(h, "User-Agent", DEFAULT_UA);
        putIfAbsent(h, "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,"
                        + "image/avif,image/webp,image/apng,*/*;q=0.8");
        putIfAbsent(h, "Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        // 明确只要 gzip/deflate：HttpURLConnection 不会自动解压，Java 也没有内置 brotli。
        // 不声明的话 Cloudflare 类站点可能直接回 br，正文全成乱码（书源"全失效"的隐蔽原因）。
        putIfAbsent(h, "Accept-Encoding", "gzip, deflate");
        putIfAbsent(h, "Upgrade-Insecure-Requests", "1");
        putIfAbsent(h, "Connection", "keep-alive");

        String host = hostOf(url);
        if (host != null) {
            // 防盗链：Referer 用同源首页，是绝大多数站点校验的最低要求
            putIfAbsent(h, "Referer", schemeOf(url) + "://" + host + "/");
            putIfAbsent(h, "Cookie", cookieFor(host));
        }
        return h;
    }

    /** 大小写不敏感地补一个头；值为 null/空 表示"没有就跳过" */
    private static void putIfAbsent(Map<String, String> h, String key, String value) {
        if (value == null || value.isEmpty()) return;
        for (String k : h.keySet()) {
            if (k != null && k.equalsIgnoreCase(key)) return;
        }
        h.put(key, value);
    }

    static String hostOf(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            java.net.URL u = new java.net.URL(url);
            String h = u.getHost();
            return h == null || h.isEmpty() ? null : h.toLowerCase(Locale.ROOT);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String schemeOf(String url) {
        return url != null && url.regionMatches(true, 0, "http://", 0, 7) ? "http" : "https";
    }

    /* ---------------- 2. DNS 预热 ---------------- */

    /**
     * 后台解析一次域名。不用自定义 DNS 实现（那会绕开 SNI/证书校验），
     * 而是把解析结果塞进系统 DNS 缓存 —— 首次连接本来就要解析，挪到后台等于白赚。
     */
    void warmDns(String host) {
        if (host == null || host.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long last = dnsWarm.get(host);
        if (last != null && now - last < 10 * 60 * 1000L) return;
        dnsWarm.put(host, now);
        TaskPools.io().execute(() -> {
            try {
                InetAddress.getAllByName(host);
            } catch (Throwable ignore) {
                /* 解析失败交给真正的请求去报错，这里不喧哗 */
            }
        });
    }

    /* ---------------- 3. Cookie（按域名 + 落盘） ---------------- */

    String cookieFor(String host) {
        if (host == null) return null;
        used.put(host, System.currentTimeMillis());
        String v = jar.get(host);
        return v == null || v.isEmpty() ? null : v;
    }

    /**
     * 合并响应里的 Set-Cookie。
     *
     * @param host 最终 URL 的 host（跟随重定向之后），不是请求发起时的 host ——
     *             否则跨域跳转拿到的会话 cookie 会挂到错误域名下。
     */
    void mergeSetCookies(String host, Map<String, List<String>> fields) {
        if (host == null || fields == null) return;
        List<String> scs = null;
        for (Map.Entry<String, List<String>> e : fields.entrySet()) {
            if (e.getKey() != null && "set-cookie".equalsIgnoreCase(e.getKey())) {
                scs = e.getValue();
                break;
            }
        }
        if (scs == null || scs.isEmpty()) return;

        Map<String, String> cur = new LinkedHashMap<>();
        String old = jar.get(host);
        if (old != null) {
            for (String kv : old.split(";\\s*")) {
                int i = kv.indexOf('=');
                if (i > 0) cur.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
            }
        }
        for (String sc : scs) {
            if (sc == null) continue;
            String first = sc.split(";")[0];
            int i = first.indexOf('=');
            if (i <= 0) continue;
            String k = first.substring(0, i).trim();
            String v = first.substring(i + 1).trim();
            String low = sc.toLowerCase(Locale.ROOT);
            boolean expired = v.isEmpty() || low.contains("max-age=0")
                    || low.contains("expires=thu, 01 jan 1970");
            if (expired) cur.remove(k);
            else cur.put(k, v);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cur.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
            if (sb.length() > MAX_COOKIE_LEN) break;
        }
        if (sb.length() == 0) jar.remove(host);
        else jar.put(host, sb.toString());
        used.put(host, System.currentTimeMillis());
        scheduleSave();
    }

    private void scheduleSave() {
        synchronized (saveLock) {
            if (savePending) return;
            savePending = true;
        }
        TaskPools.after(1200, () -> {
            synchronized (saveLock) {
                savePending = false;
            }
            saveNow();
        });
    }

    /** 立刻落盘（App 进后台时可以主动调一次） */
    void saveNow() {
        try {
            JSONObject o = new JSONObject();
            // 超过上限就按最近使用时间淘汰，避免存档无限长
            if (jar.size() > MAX_HOSTS) {
                List<String> hosts = new ArrayList<>(jar.keySet());
                hosts.sort((a, b) -> Long.compare(
                        used.getOrDefault(b, 0L), used.getOrDefault(a, 0L)));
                for (int i = MAX_HOSTS; i < hosts.size(); i++) jar.remove(hosts.get(i));
            }
            for (Map.Entry<String, String> e : jar.entrySet()) o.put(e.getKey(), e.getValue());
            sp.edit().putString(KEY_JAR, o.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "cookie save failed", t);
        }
    }

    private void loadJar() {
        try {
            String raw = sp.getString(KEY_JAR, "");
            if (raw == null || raw.isEmpty()) return;
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String h = it.next();
                String v = o.optString(h, "");
                if (!h.isEmpty() && !v.isEmpty()) {
                    jar.put(h, v);
                    used.put(h, 0L);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cookie load failed", t);
        }
    }

    /** 清空所有 cookie（设置页的"退出登录/清缓存"用） */
    void clearCookies() {
        jar.clear();
        used.clear();
        saveNow();
    }

    /* ---------------- 4. 编码探测 ---------------- */

    private static final Pattern CT_CHARSET = Pattern.compile(
            "charset=[\"']?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET = Pattern.compile(
            "(?:charset\\s*=\\s*[\"']?\\s*|encoding\\s*=\\s*[\"'])([\\w.:-]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 解码顺序（用户要求的三级 + 两道兜底）：
     * <pre>
     *   Content-Type → HTML meta / XML 声明 → BOM
     *   → 严格 UTF-8 校验 → 统计探测（chardet）→ 汉字符号比例打分 → utf-8
     * </pre>
     *
     * <p>严格 UTF-8 排在统计探测前：无 BOM 的 UTF-8 页面在 chardet 里常被判成
     * windows-1252，反而把好好的中文变成天书。
     */
    /** 按声明 charset 解码整页；非法/不支持的编码名返回 null。供 meta/CT 命中后的「解码有效性」校验 */
    private static String safeDecode(byte[] buf, String cs) {
        try {
            return new String(buf, java.nio.charset.Charset.forName(cs));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解码有效性：统计替换字符（U+FFFD）占比。声明类 charset 解码后若替换字符过多，
     * 说明声明不可信——典型就是「meta 标 UTF-8 实际 GBK」的老站，按 UTF-8 解整页天书。
     * 替换字符占比超 2% 视为不可信；极短页面允许至多 1 个，避免偶发误判。
     */
    private static boolean validChars(String s) {
        if (s == null || s.isEmpty()) return false;
        int repl = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') repl++;
        }
        return repl <= Math.max(1, s.length() * 2 / 100);
    }

    /** meta / Content-Type 命中声明后不直接采用，先验证解码有效性；无效则返回 null 继续下探 */
    private static String checkDeclared(byte[] buf, String raw) {
        String c = normalize(raw, true);
        if (c == null) return null;
        String decoded = safeDecode(buf, c);
        if (decoded == null) return null;
        return validChars(decoded) ? c : null;
    }

    static String sniffCharset(byte[] buf, String headerCt) {
        if (buf == null || buf.length == 0) return "utf-8";

        // 1. BOM：字节层面最可靠，直接定编码，无需猜（规约：BOM 优先于一切声明）
        if (buf.length >= 3 && (buf[0] & 0xFF) == 0xEF
                && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF) return "utf-8";
        if (buf.length >= 2 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFE) return "utf-16le";
        if (buf.length >= 2 && (buf[0] & 0xFF) == 0xFE && (buf[1] & 0xFF) == 0xFF) return "utf-16be";

        // 2. 文档头 4KB 里的声明（meta charset / xml encoding）：优先级高于 Content-Type。
        //    大量老站把 UTF-8 页面声明成 gbk（或反之），以作者写的 meta 为准才能解对，
        //    否则整页乱码 → bookList 命中 0 条 → 整源被误判「失效」。
        int headLen = Math.min(buf.length, 4096);
        String head = new String(buf, 0, headLen, StandardCharsets.ISO_8859_1);
        Matcher m2 = META_CHARSET.matcher(head);
        if (m2.find()) {
            // meta 声明必须过「解码有效性」校验才采用：否则继续下探。
            // 典型坑——meta 标 UTF-8 实际 GBK 的站，按 UTF-8 解整页乱码 → bookList 命中 0 条 → 整源被误判失效。
            String c = checkDeclared(buf, m2.group(1));
            if (c != null) return c;
        }

        // 3. 响应头 Content-Type 声明（比 meta 次一级：站点常写错）
        if (headerCt != null) {
            Matcher m = CT_CHARSET.matcher(headerCt);
            if (m.find()) {
                // 同 meta：声明必须过解码有效性校验，否则下探（CT 标 UTF-8 实际 GBK 同样常见）
                String c = checkDeclared(buf, m.group(1));
                if (c != null) return c;
            }
        }

        // 4. 整体是合法 UTF-8（且含多字节字符）→ 就是 UTF-8，不用再猜
        if (isStrictUtf8(buf)) return "utf-8";

        // 5. 字符统计探测
        String guess = chardet(buf);
        if (guess != null) return guess;

        // 6. 打分：UTF-8 与 GB18030 各解一遍，取"替换字符少 + 汉字多"的那个
        return scoreCharset(buf);
    }

    /** chardet 结果过白名单；不可信的（西欧单字节）返回 null 交给打分 */
    private static String chardet(byte[] buf) {
        try {
            int n = Math.min(buf.length, 128 * 1024);
            UniversalDetector d = new UniversalDetector(null);
            d.handleData(buf, 0, n);
            d.dataEnd();
            String enc = d.getDetectedCharset();
            if (enc == null || enc.isEmpty()) return null;
            return normalize(enc, false);
        } catch (Throwable t) {
            return null; // 探测只是兜底，任何异常都不该影响一次请求
        }
    }

    /**
     * @param trust false 时表示"来自统计探测"：ISO-8859-1 / windows-1252 / ASCII
     *              这类对中文页面几乎全是误判，直接放弃。
     */
    private static String normalize(String raw, boolean trust) {
        if (raw == null) return "utf-8";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("utf-8") || s.equals("utf8") || s.startsWith("unicode-1-1-utf-8")) {
            return "utf-8";
        }
        if (s.startsWith("gb18030")) return "gb18030";
        if (s.startsWith("gbk") || s.startsWith("gb2312") || s.equals("cp936")
                || s.equals("x-gbk") || s.startsWith("hz-gb")) return "gbk";
        if (s.startsWith("big5") || s.startsWith("big-5") || s.equals("cp950")) return "big5";
        if (s.startsWith("utf-16le")) return "utf-16le";
        if (s.startsWith("utf-16be")) return "utf-16be";
        if (s.startsWith("euc-jp")) return "euc-jp";
        if (s.startsWith("shift_jis") || s.startsWith("shift-jis") || s.equals("sjis")) return "shift_jis";
        if (s.startsWith("euc-kr")) return "euc-kr";
        if (s.equals("iso-8859-1") || s.equals("iso8859-1") || s.equals("windows-1252")
                || s.equals("ascii") || s.equals("us-ascii")) {
            return trust ? "iso-8859-1" : null;
        }
        // 其它：只有真被 Charset 支持才用，否则当没猜出来
        try {
            return Charset.isSupported(raw) ? raw : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * 整页是否严格合法 UTF-8。
     * 只取前 64KB 做严格校验（一个坏字节就否决整页会误伤），再用替换字符比例兜底：
     * GBK 中文在这段里几乎必然出现非法序列，而合法 UTF-8 几乎不会。
     */
    static boolean isStrictUtf8(byte[] buf) {
        if (buf == null || buf.length == 0) return true;
        if (buf.length < 8) return true; // 太短的片段没有统计意义，别乱否决
        int probe = Math.min(buf.length, 64 * 1024);
        boolean multiByte = false;
        for (int i = 0; i < probe; i++) {
            if ((buf[i] & 0x80) != 0) {
                multiByte = true;
                break;
            }
        }
        if (!multiByte) return true; // 纯 ASCII：两种编码解出来一样
        try {
            java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder();
            dec.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            dec.decode(java.nio.ByteBuffer.wrap(buf, 0, probe));
        } catch (Exception e) {
            return false;
        }
        String text = new String(buf, StandardCharsets.UTF_8);
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') bad++;
        }
        return bad * 200 < Math.max(1, text.length()); // 替换字符 < 0.5%
    }

    /** 最后一道：两种候选各解一遍，按"汉字比例 − 替换字符惩罚"打分 */
    private static String scoreCharset(byte[] buf) {
        int n = Math.min(buf.length, 128 * 1024);
        int utf = score(new String(buf, 0, n, StandardCharsets.UTF_8));
        int gb;
        try {
            gb = score(new String(buf, 0, n, Charset.forName("GB18030")));
        } catch (Throwable t) {
            gb = -1;
        }
        int big5;
        try {
            big5 = score(new String(buf, 0, n, Charset.forName("Big5")));
        } catch (Throwable t) {
            big5 = -1;
        }
        if (gb > utf && gb >= big5) return "gb18030";
        if (big5 > utf && big5 > gb) return "big5";
        return "utf-8";
    }

    private static int score(String s) {
        int han = 0, bad = 0, total = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') {
                bad++;
                total++;
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') continue;
            if (Character.isWhitespace(c)) continue;
            total++;
            if (c >= 0x4E00 && c <= 0x9FFF) han++;
            else if (c >= 0x3000 && c <= 0x303F) han++; // 中文标点也算"像中文"
        }
        if (total == 0) return 0;
        int pct = han * 100 / total;
        // 每 1% 替换字符扣 5 分：乱码页即使凑出一堆汉字也救不回来
        return pct - (bad * 100 / total) * 5;
    }
}
