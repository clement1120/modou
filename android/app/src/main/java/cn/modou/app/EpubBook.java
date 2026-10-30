package cn.modou.app;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 本地 EPUB 解析（web/js/lib/epub.js 的原生移植）。
 *
 * 依赖只有 java.util.zip / java.util.regex：Android 上能用，JVM 上也能跑，
 * 因此可以和 JS 版做真实对照测试（沙箱里没有模拟器，这是唯一能证明它对的方式）。
 *
 * 两处不能随意"优化"的设计：
 *  - 正文插图在分段前先换成 token。stripTags 会把标签全清掉，图会跟着一起没；
 *    token 是唯一能穿过 stripTags 的东西，这也是 EPUB 插图之前显示不出来的根因。
 *  - 封面有五级兜底。EPUB2/3 写法差异极大，只认 properties="cover-image" 的话，
 *    大量 EPUB2 的书会没有封面。
 */
public final class EpubBook {

    private EpubBook() {
    }

    public static final class Chapter {
        public final String name;
        public final String html;

        Chapter(String n, String h) {
            name = n;
            html = h;
        }
    }

    public static final class Parsed {
        public String title = "";
        public String author = "";
        public String intro = "";
        public String cover = "";
        public List<Chapter> chapters = new ArrayList<>();
        /** 实际产出的章数：流式解析时 chapters 是空的（正文已直接写库） */
        public int count;
    }

    private static final int MAX_IMG_BYTES = 4 * 1024 * 1024; // 单图上限，超了就不内联
    private static final String IMG_TOKEN = "\u0001IMG\u0002";

    /* ---------------- 入口 ---------------- */

    /** 每产出一章回调一次（流式）；为 null 时全部攒在内存里 */
    public interface Sink {
        void chapter(int index, String name, String html);
    }

    /**
     * 插图落地：把一张图存成文件，返回能塞进 <img src> 的地址（file:// 或 http）。
     *
     * 不给这个实现时退回「内联 base64」。内联的代价很大：一本 1200 章、每章一张
     * 60KB 插图的合集，正文会膨胀到 100MB 左右（实测 97.9MB），导入就是把这 100MB
     * 一条条写进 SQLite —— 用户看到的就是「已导入 x 章」一行行爬、要等半天；
     * 而且单章超过 2MB 以后 CursorWindow 装不下，读回来直接失败。
     * 落到文件后，章节行里只剩几十 KB 的正文，导入快、库小、读取也不再撞上限。
     */
    public interface ImageSink {
        /**
         * @param key   zip 内条目路径（同一张图多章引用时只会调一次）
         * @param bytes 原始字节
         * @param mime  由扩展名推出来的 MIME
         * @return 可写进 src 的地址；返回空串表示放弃这张图
         */
        String save(String key, byte[] bytes, String mime);
    }

    /**
     * 从文件解析：**不把整本 zip 解压进内存**，每个条目用到时才读、读完即弃。
     *
     * 之前是 `unzip(byte[])` 一次解成 `Map<name, byte[]>`，一本 40MB 的合集
     * （正文 + 插图 + 字体解压后上百 MB）在低端机上直接 OOM，进程被杀 = 用户看到的
     * 「点了没反应 / 闪回桌面」。改成 ZipFile 随机读之后，峰值只剩当前这一章。
     */
    public static Parsed parse(File zipFile, Sink sink) {
        return parse(zipFile, sink, null);
    }

    /** 带插图落地的解析：图片写文件，章节 HTML 里只留地址 */
    public static Parsed parse(File zipFile, Sink sink, ImageSink images) {
        try (Zip z = Zip.open(zipFile)) {
            return parseZip(z, sink, images);
        } catch (IOException e) {
            throw new IllegalArgumentException("打不开 EPUB：" + e.getMessage());
        }
    }

    /** 兼容入口：内存里已有字节时先落临时文件，再走流式解析（不在堆上多留一份解压结果） */
    public static Parsed parse(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) throw new IllegalArgumentException("空文件");
        File tmp = null;
        try {
            tmp = File.createTempFile("modou-epub", ".zip");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(zipBytes);
            }
            return parse(tmp, null);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取失败：" + e.getMessage());
        } finally {
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static Parsed parseZip(Zip z, Sink sink) {
        return parseZip(z, sink, null);
    }

    private static Parsed parseZip(Zip z, Sink sink, ImageSink images) {
        if (z.count() == 0) throw new IllegalArgumentException("不是有效的 EPUB（zip 解压失败）");

        final String opfPath = findOpfPath(z);
        if (opfPath.isEmpty()) throw new IllegalArgumentException("EPUB 缺少 OPF 清单");
        final String opf = text(z.read(opfPath));
        if (opf.isEmpty()) throw new IllegalArgumentException("OPF 读取失败");

        Parsed out = new Parsed();
        out.title = firstNonEmpty(tagText(opf, "dc:title"), tagText(opf, "title"), "未命名 EPUB");
        out.author = firstNonEmpty(tagText(opf, "dc:creator"), tagText(opf, "creator"), "");

        // manifest: id → {href, type, props}
        Map<String, Item> manifest = new LinkedHashMap<>();
        for (Map<String, String> a : tagAttrs(opf, "item")) {
            String id = a.get("id");
            String href = a.get("href");
            if (id == null || href == null) continue;
            manifest.put(id, new Item(href, lower(a.get("media-type")), str(a.get("properties"))));
        }
        // spine：阅读顺序（缺省用 manifest 顺序）
        List<String> idrefs = new ArrayList<>();
        if (!tagAttrs(opf, "spine").isEmpty()) {
            for (Map<String, String> a : tagAttrs(opf, "itemref")) {
                if (a.get("idref") != null) idrefs.add(a.get("idref"));
            }
        }
        if (idrefs.isEmpty()) idrefs.addAll(manifest.keySet());

        List<Item> order = new ArrayList<>();
        for (String id : idrefs) {
            Item it = manifest.get(id);
            if (it == null) continue;
            String h = it.href.toLowerCase();
            if (it.type.contains("xhtml") || it.type.contains("html")
                    || h.endsWith(".xhtml") || h.endsWith(".html")) {
                order.add(it);
            }
        }

        // 整本书共用一份图片→dataURL 缓存（同图多章引用时只编一次 base64）
        ImgCache imgUrls = new ImgCache();

        List<String[]> spineDocs = new ArrayList<>(); // {path, href}
        int idx = 0;
        int produced = 0;
        String introFrom = null;
        for (Item m : order) {
            idx++;
            if (m.props.toLowerCase().contains("nav")) continue; // 跳过导航页
            String path = resolvePath(opfPath, m.href);
            spineDocs.add(new String[]{path, m.href});
            byte[] raw = z.read(path);
            if (raw == null) raw = z.read(m.href);
            String xhtml = raw == null ? "" : utf8(raw);
            raw = null;   // 原文立刻可回收：后面还要按 token 重建 HTML
            if (xhtml.trim().isEmpty()) continue;
            Chapter ch = htmlToChapter(xhtml, "第 " + idx + " 章", z, path, imgUrls, images);
            xhtml = null;
            boolean emptyText = ch.html.replaceAll("<[^>]+>", "").trim().length() < 2;
            if (emptyText) {
                // 空页/插图页过滤：纯图页保留，但书首的纯图页按封面页跳过
                if (!ch.html.contains("class=\"img-p\"") || produced == 0) continue;
            }
            produced++;
            if (introFrom == null) introFrom = ch.html;
            if (sink != null) sink.chapter(produced - 1, ch.name, ch.html);
            else out.chapters.add(ch);
        }
        if (produced == 0) throw new IllegalArgumentException("EPUB 未解析到正文章节");
        out.count = produced;
        out.intro = cut(introFrom == null ? "" : introFrom.replaceAll("<[^>]+>", ""), 120);
        out.cover = findCover(opf, z, opfPath, manifest, spineDocs);
        return out;
    }

    /* ---------------- zip ---------------- */

    /**
     * 只读的 zip 视图：一次打开、按名取条目、取完即弃。
     *
     * 与旧实现的唯一差别是「按需解压」——旧版把每个条目都 inflate 到一张 Map 里，
     * 40MB 的书光这一步就要吃掉上百 MB 堆。这里只记住条目名（几 KB），
     * 正文/图片用到哪个才读哪个。
     */
    private static final class Zip implements Closeable {
        private final ZipFile zf;
        private final List<String> names = new ArrayList<>();
        /** 小写 basename → 真实条目名（zip 里的大小写常与 OPF href 不一致） */
        private final Map<String, String> byBase = new LinkedHashMap<>();
        private final Map<String, String> exactLower = new LinkedHashMap<>();

        private Zip(ZipFile f) {
            zf = f;
        }

        static Zip open(File f) throws IOException {
            ZipFile zf = new ZipFile(f);
            Zip z = new Zip(zf);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                z.names.add(n);
                String b = baseOf(n);
                if (!b.isEmpty() && !z.byBase.containsKey(b)) z.byBase.put(b, n);
                z.exactLower.put(n.toLowerCase(), n);
            }
            return z;
        }

        int count() {
            return names.size();
        }

        List<String> names() {
            return names;
        }

        /** 精确 → 归一化 → basename 兜底（与旧 lookup 语义一致） */
        byte[] read(String path) {
            if (path == null || path.isEmpty()) return null;
            byte[] v = readExact(path);
            if (v != null) return v;
            String p = normSegments(path);
            v = readExact(p);
            if (v != null) return v;
            String real = byBase.get(baseOf(p));
            return real == null ? null : readExact(real);
        }

        private byte[] readExact(String name) {
            if (name == null) return null;
            String real = exactLower.get(name.toLowerCase());
            if (real == null) return null;
            ZipEntry e = zf.getEntry(real);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                ByteArrayOutputStream o = new ByteArrayOutputStream(
                        e.getSize() > 0 && e.getSize() < (1 << 24) ? (int) e.getSize() : 8192);
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                return o.toByteArray();
            } catch (Exception ex) {
                return null;
            }
        }

        @Override
        public void close() {
            try {
                zf.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
    }

    private static String baseOf(String p) {
        if (p == null) return "";
        String s = p.toLowerCase();
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static String findOpfPath(Zip z) {
        String container = text(z.read("META-INF/container.xml"));
        List<Map<String, String>> rf = tagAttrs(container, "rootfile");
        if (!rf.isEmpty()) {
            String fp = rf.get(0).get("full-path");
            if (fp != null && !fp.isEmpty()) return fp;
        }
        for (String k : z.names()) {
            if (k.toLowerCase().endsWith(".opf")) return k;
        }
        return "";
    }

    /* ---------------- XML 辅助 ---------------- */

    private static String tagText(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + "(?:\\s[^>]*)?>([\\s\\S]*?)</" + tag + ">",
                Pattern.CASE_INSENSITIVE).matcher(xml);
        return m.find() ? xmlUnescape(m.group(1)).trim() : "";
    }

    private static List<Map<String, String>> tagAttrs(String xml, String tag) {
        List<Map<String, String>> list = new ArrayList<>();
        Matcher m = Pattern.compile("<" + tag + "(\\s[^>]*?)/?>",
                Pattern.CASE_INSENSITIVE).matcher(xml);
        Pattern ap = Pattern.compile("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
        while (m.find()) {
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher a = ap.matcher(m.group(1));
            while (a.find()) {
                String v = a.group(2) != null ? a.group(2) : a.group(3);
                attrs.put(a.group(1).toLowerCase(), xmlUnescape(v == null ? "" : v));
            }
            list.add(attrs);
        }
        return list;
    }

    private static String xmlUnescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(amp|lt|gt|quot|apos|nbsp|#x?[0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String r;
            if ("amp".equals(e)) r = "&";
            else if ("lt".equals(e)) r = "<";
            else if ("gt".equals(e)) r = ">";
            else if ("quot".equals(e)) r = "\"";
            else if ("apos".equals(e)) r = "'";
            else if ("nbsp".equals(e)) r = " ";
            else {
                try {
                    int cp = (e.charAt(1) == 'x' || e.charAt(1) == 'X')
                            ? Integer.parseInt(e.substring(2), 16)
                            : Integer.parseInt(e.substring(1), 10);
                    r = new String(Character.toChars(cp));
                } catch (Exception ex) {
                    r = m.group(0);
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String normSegments(String p) {
        List<String> out = new ArrayList<>();
        if (p == null) return "";
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    /** href 相对 OPF 所在目录拼接（'/x.jpg' 按包根解析，不再拼 OPF 目录） */
    private static String resolvePath(String opfPath, String href) {
        String h = pctDecode(String.valueOf(href == null ? "" : href).split("#")[0]).trim();
        if (h.isEmpty()) return "";
        if (h.startsWith("/")) return normSegments(h);
        String dir = opfPath.contains("/") ? opfPath.replaceAll("/[^/]*$", "") : "";
        return normSegments((dir.isEmpty() ? "" : dir + "/") + h);
    }

    /* ---------------- 正文 ---------------- */

    /** 只去空格/制表/换行的 trim：绝不碰 \u0001/\u0002（图片 token 边界） */
    private static String trimWs(String s) {
        if (s == null || s.isEmpty()) return "";
        int a = 0;
        int b = s.length();
        while (a < b && isWs(s.charAt(a))) a++;
        while (b > a && isWs(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }

    /** %xx 解码（与 JS 的 decodeURIComponent 对齐）：href 里路径含空格很常见 */
    private static String pctDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    o.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 3;
                    continue;
                } catch (Exception ignore) {
                    /* 不是合法转义就当普通字符 */
                }
            }
            byte[] enc = String.valueOf(c).getBytes(java.nio.charset.Charset.forName("UTF-8"));
            o.write(enc, 0, enc.length);
            i++;
        }
        return new String(o.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?i)<(script|style)[\\s\\S]*?</\\1>", " ")
                .replaceAll("<[^>]+>", "\n")
                .replace("&nbsp;", " ");
    }

    /**
     * 图片 → dataURL 的整书缓存。
     *
     * 同图多章引用（章节头图、分隔线）时不必重复编 base64；但缓存本身也是几十 MB 的
     * 字符串，所以设总预算，超了就不再往里放 —— 宁可多编一次，也不要把堆吃光。
     */
    private static final class ImgCache {
        static final long MAX_BYTES = 24L * 1024 * 1024;
        final Map<String, String> map = new LinkedHashMap<>();
        long bytes = 0;
    }

    private static final class ImgCtx {
        final Zip zip;
        final String docPath;
        final List<String> tags = new ArrayList<>();
        /** 同一张图常被多章引用：base64 只编一次，图多的书能省掉大半 CPU 与临时字符串 */
        final ImgCache urls;
        /** 非 null 时插图落文件（章节 HTML 里只留 file:// 地址） */
        final ImageSink images;

        ImgCtx(Zip z, String d, ImgCache u, ImageSink im) {
            zip = z;
            docPath = d;
            urls = u;
            images = im;
        }
    }

    /**
     * XHTML → {name, html}。
     *
     * 图片以 token 过不了 stripTags 的特性保留下来：先换成 token，分段后按 token 拆段回填，
     * 图片独立成段（.img-p）。少了这一步，插图会在 stripTags 里和标签一起被清掉。
     */
    private static Chapter htmlToChapter(String xhtml, String fallbackName,
                                         Zip zip, String docPath, ImgCache imgUrls, ImageSink images) {
        Matcher bodyM = Pattern.compile("<body[^>]*>([\\s\\S]*?)</body>",
                Pattern.CASE_INSENSITIVE).matcher(xhtml);
        String body = bodyM.find() ? bodyM.group(1) : xhtml;

        String name = "";
        // 注意：find() 只能调一次 —— 先判空再取会让第二次 find 去找下一个匹配，
        // 于是"有且只有一个 h1"的章反而取不到名字，全部退化成「第 N 章」
        Matcher hM = Pattern.compile("<h[1-6][^>]*>([\\s\\S]*?)</h[1-6]>",
                Pattern.CASE_INSENSITIVE).matcher(body);
        boolean gotH = hM.find();
        if (!gotH) {
            hM = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>",
                    Pattern.CASE_INSENSITIVE).matcher(xhtml);
            gotH = hM.find();
        }
        if (gotH) name = stripTags(hM.group(1)).replaceAll("\\s+", " ").trim();

        final ImgCtx ctx = new ImgCtx(zip, docPath, imgUrls, images);
        Matcher imgM = Pattern.compile("<(?:svg:)?(?:img|image)\\b[^>]*>",
                Pattern.CASE_INSENSITIVE).matcher(body);
        StringBuffer body2 = new StringBuffer();
        while (imgM.find()) {
            String src = resolveImg(ctx, imgM.group(0));
            if (src.isEmpty()) {
                imgM.appendReplacement(body2, " ");
            } else {
                ctx.tags.add("<img src=\"" + src + "\" referrerpolicy=\"no-referrer\" alt=\"\">");
                imgM.appendReplacement(body2,
                        Matcher.quoteReplacement(IMG_TOKEN + (ctx.tags.size() - 1) + IMG_TOKEN));
            }
        }
        imgM.appendTail(body2);

        String cleaned = body2.toString()
                .replaceAll("(?i)<(script|style)[\\s\\S]*?</\\1>", "")
                .replaceAll("(?i)<h[1-6][^>]*>[\\s\\S]*?</h[1-6]>", ""); // 标题已作章名
        List<String> paras = new ArrayList<>();
        for (String p : cleaned.split("(?i)(?:</p>|<br\\s*/?>|</div>|</li>)")) {
            // 不能用 String.trim()：Java 的 trim 把 \u0001/\u0002 也当空白删掉，
            // 而这两个字符正是图片 token 的边界符 —— 一删 token 就残缺，图就回填不进去
            // （JS 的 trim 不认这两个字符，所以网页版一直是好的）
            String s = trimWs(stripTags(p).replaceAll("[ \t]+", " "));
            if (s.isEmpty()) continue;
            if (Pattern.compile("^(mobipocket|calibre|copyright|www\\.)\\b",
                    Pattern.CASE_INSENSITIVE).matcher(s).find()) continue;
            paras.add(s);
        }

        StringBuilder html = new StringBuilder();
        Pattern tok = Pattern.compile(IMG_TOKEN + "(\\d+)" + IMG_TOKEN);
        for (String p : paras) {
            String[] parts = tok.split(p, -1);
            // split 丢掉了捕获组，需要重新扫一遍拿到图片下标
            Matcher tm = tok.matcher(p);
            int[] idxs = new int[16];
            int ni = 0;
            while (tm.find() && ni < idxs.length) idxs[ni++] = Integer.parseInt(tm.group(1));
            int k = 0;
            for (int i = 0; i < parts.length; i++) {
                if (i % 2 == 1) {
                    if (k < ni) {
                        int id = idxs[k];
                        if (id >= 0 && id < ctx.tags.size()) {
                            html.append("<p class=\"img-p\">").append(ctx.tags.get(id)).append("</p>");
                        }
                    }
                    k++;
                } else if (!parts[i].isEmpty()) {
                    html.append("<p>").append(escHtml(parts[i])).append("</p>");
                }
            }
        }
        String h = html.toString();
        return new Chapter(name.isEmpty() ? fallbackName : name, h);
    }

    /** <img>/<image> 标签 → dataURL；取不到字节就返回空（图被丢弃，不留裂图） */
    private static String resolveImg(ImgCtx ctx, String tag) {
        Matcher m = Pattern.compile("\\bsrc=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag);
        if (!m.find()) {
            m = Pattern.compile("\\b(?:xlink:)?href=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE).matcher(tag);
            if (!m.find()) return "";
        }
        String ref = m.group(1);
        String key = resolvePath(ctx.docPath, ref);
        String hit = ctx.urls.map.get(key);
        if (hit != null) return hit;
        byte[] bytes = ctx.zip.read(key);
        if (bytes == null) bytes = ctx.zip.read(ref);
        String url = "";
        if (ctx.images != null && bytes != null && bytes.length > 0 && bytes.length < 8 * 1024 * 1024) {
            try {
                url = ctx.images.save(key, bytes, mimeOf(ref));
            } catch (Throwable t) {
                url = ""; // 写文件失败就退回内联，图不能丢
            }
            if (url == null) url = "";
        }
        if (url.isEmpty()) url = toDataUrl(bytes, ref);
        if (!url.isEmpty() && ctx.urls.bytes + url.length() < ImgCache.MAX_BYTES) {
            ctx.urls.map.put(key, url);
            ctx.urls.bytes += url.length();
        }
        return url;
    }

    private static String toDataUrl(byte[] bytes, String path) {
        if (bytes == null || bytes.length == 0 || bytes.length >= 6 * 1024 * 1024) return "";
        if (bytes.length > MAX_IMG_BYTES) return "";
        return "data:" + mimeOf(path) + ";base64," + b64(bytes);
    }

    private static final Map<String, String> EXT_MIME = new LinkedHashMap<>();

    static {
        EXT_MIME.put("jpg", "image/jpeg");
        EXT_MIME.put("jpeg", "image/jpeg");
        EXT_MIME.put("jfif", "image/jpeg");
        EXT_MIME.put("png", "image/png");
        EXT_MIME.put("gif", "image/gif");
        EXT_MIME.put("webp", "image/webp");
        EXT_MIME.put("bmp", "image/bmp");
        EXT_MIME.put("svg", "image/svg+xml");
    }

    private static String mimeOf(String p) {
        String s = String.valueOf(p == null ? "" : p);
        int i = s.lastIndexOf('.');
        String ext = i >= 0 ? s.substring(i + 1).toLowerCase() : "";
        String v = EXT_MIME.get(ext);
        return v == null ? "image/jpeg" : v;
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static String b64(byte[] d) {
        StringBuilder sb = new StringBuilder(((d.length + 2) / 3) * 4);
        for (int i = 0; i < d.length; i += 3) {
            int rem = Math.min(3, d.length - i);
            int v = 0;
            for (int j = 0; j < 3; j++) {
                v <<= 8;
                if (j < rem) v |= (d[i + j] & 0xFF);
            }
            int chunks = rem + 1;
            for (int c = 0; c < 4; c++) {
                if (c < chunks) {
                    sb.append(B64[(v >> (18 - c * 6)) & 0x3F]);
                } else {
                    sb.append('=');
                }
            }
        }
        return sb.toString();
    }

    /* ---------------- 封面 ---------------- */

    private static final class Item {
        final String href;
        final String type;
        final String props;

        Item(String h, String t, String p) {
            href = h;
            type = t;
            props = p;
        }
    }

    /**
     * 封面多级兜底：
     * a. properties="cover-image"  b. <meta name="cover">  c. manifest 里 id/href 含 cover
     * d. 首个 spine 文档里的第一张图（封面页常只有图、被正文过滤掉）  e. zip 内文件名含 cover
     */
    private static String findCover(String opf, Zip z,
                                    String opfPath, Map<String, Item> manifest,
                                    List<String[]> spineDocs) {
        try {
            Item item = null;
            for (Item it : manifest.values()) {
                if (it.props.toLowerCase().contains("cover-image") && it.type.startsWith("image/")) {
                    item = it;
                    break;
                }
            }
            if (item == null) {
                Matcher mm = Pattern.compile(
                        "<meta[^>]*name=[\"']cover[\"'][^>]*content=[\"']([^\"']+)[\"']",
                        Pattern.CASE_INSENSITIVE).matcher(opf);
                if (!mm.find()) {
                    mm = Pattern.compile(
                            "<meta[^>]*content=[\"']([^\"']+)[\"'][^>]*name=[\"']cover[\"']",
                            Pattern.CASE_INSENSITIVE).matcher(opf);
                    mm.reset();
                }
                if (mm.find()) {
                    Item cand = manifest.get(mm.group(1));
                    if (cand != null && cand.type.startsWith("image/")) item = cand;
                }
            }
            if (item == null) {
                for (Item it : manifest.values()) {
                    if (it.type.startsWith("image/")
                            && Pattern.compile("cover", Pattern.CASE_INSENSITIVE)
                            .matcher(it.href + " " + it.props).find()) {
                        item = it;
                        break;
                    }
                }
            }
            if (item != null) {
                byte[] b = z.read(resolvePath(opfPath, item.href));
                if (b == null) b = z.read(item.href);
                String c = toDataUrl(b, item.href);
                if (!c.isEmpty()) return c;
            }
            // d：封面页正文常只有一张图（被空页过滤跳过），从 spine 文档里挖第一张图
            Pattern IMG_RE = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
            Pattern SVG_RE = Pattern.compile("<(?:svg:)?image[^>]+?(?:xlink:)?href=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE);
            for (String[] doc : spineDocs) {
                byte[] raw = z.read(doc[0]);
                if (raw == null) continue;
                String xhtml = utf8(raw);
                Matcher m = IMG_RE.matcher(xhtml);
                if (!m.find()) {
                    m = SVG_RE.matcher(xhtml);
                    m.reset();
                }
                if (!m.find()) continue;
                String c = toDataUrl(z.read(resolvePath(doc[0], m.group(1))), m.group(1));
                if (!c.isEmpty()) return c;
            }
            // e：扫 zip 内文件名含 cover 的图片
            for (String key : z.names()) {
                if (Pattern.compile("cover", Pattern.CASE_INSENSITIVE).matcher(key).find()
                        && Pattern.compile("\\.(jpe?g|png|gif|webp|bmp|svg)$",
                        Pattern.CASE_INSENSITIVE).matcher(key).find()) {
                    String c = toDataUrl(z.read(key), key);
                    if (!c.isEmpty()) return c;
                }
            }
        } catch (Exception e) {
            /* 封面提取失败不影响导入 */
        }
        return "";
    }

    /* ---------------- 小工具 ---------------- */

    private static String text(byte[] raw) {
        return raw == null ? "" : utf8(raw);
    }

    private static String utf8(byte[] b) {
        return new String(b, java.nio.charset.Charset.forName("UTF-8"));
    }

    private static String lower(String s) {
        return String.valueOf(s == null ? "" : s).toLowerCase();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonEmpty(String... ss) {
        for (String s : ss) if (s != null && !s.isEmpty()) return s;
        return "";
    }

    private static String cut(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }

    private static String escHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
