package cn.modou.app;

import java.io.BufferedReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 TXT 解析：编码探测 + 智能分章（web/js/lib/txt.js 的原生移植）。
 *
 * 刻意不依赖 Android：这样能在 JVM 上直接跑，与 JS 版逐条对照。
 * 分章规则是从一堆真实 bug 里长出来的（尤其是 isTitleLine 的分隔符判定），
 * 移植时不能凭感觉重写，必须和 JS 版行为一致 —— 否则用户重新导入同一本书，
 * 目录会和之前对不上。
 */
public final class TxtBook {

    private TxtBook() {
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
        public List<Chapter> chapters = new ArrayList<>();
    }

    /* ---------------- 编码探测 ---------------- */

    /**
     * BOM → UTF-8 严格 → GBK 回退。
     * 中文网文常见 UTF-8 与 GBK 混存；无 BOM 的 GBK 文本几乎必然含非法 UTF-8 序列，
     * 所以「UTF-8 严格解码失败」是判断 GBK 的可靠信号。
     */
    public static String decodeText(byte[] buf) {
        if (buf == null || buf.length == 0) return "";
        if (buf.length >= 3 && (buf[0] & 0xFF) == 0xEF && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF) {
            return new String(buf, 3, buf.length - 3, utf8());
        }
        if (buf.length >= 2 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFE) {
            return new String(buf, 2, buf.length - 2, Charset.forName("UTF-16LE"));
        }
        if (buf.length >= 2 && (buf[0] & 0xFF) == 0xFE && (buf[1] & 0xFF) == 0xFF) {
            return new String(buf, 2, buf.length - 2, Charset.forName("UTF-16BE"));
        }
        String s = strictUtf8(buf);
        if (s != null && s.indexOf('\uFFFD') < 0) return s;
        try {
            return new String(buf, Charset.forName("GBK"));
        } catch (Exception e) {
            return s != null ? s : new String(buf, utf8());
        }
    }

    private static Charset utf8() {
        return Charset.forName("UTF-8");
    }

    /** 严格 UTF-8：遇到非法序列返回 null（而不是悄悄替换成 U+FFFD） */
    private static String strictUtf8(byte[] buf) {
        CharsetDecoder d = utf8().newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = d.decode(ByteBuffer.wrap(buf));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /* ---------------- 文件名猜书名作者 ---------------- */

    /** 《书名》（作者）/《书名》作者 试探 */
    public static String[] guessMetaFromName(String fileName) {
        String base = String.valueOf(fileName == null ? "" : fileName);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String name = base;
        String author = "";
        Matcher m = Pattern.compile("《([^》]{1,60})》").matcher(base);
        if (m.find()) {
            name = m.group(1).trim();
            String rest = base.replace(m.group(0), " ").trim();
            Matcher am = Pattern.compile("[（(]\\s*([一-龥A-Za-z0-9_· ]{1,20})\\s*[）)]").matcher(rest);
            if (am.find()) {
                author = am.group(1).trim();
            } else if (!rest.isEmpty() && rest.length() <= 12
                    && !rest.matches(".*[下载全集txtTXT完本精校版].*")) {
                author = rest.trim();
            }
        }
        return new String[]{name.isEmpty() ? base : name, author};
    }

    /* ---------------- 正文 → HTML ---------------- */

    /**
     * 纯文本 → 段落 HTML（与引擎正文一致，阅读页的 .content p 样式直接复用）。
     *
     * 逐行扫一遍、直接往同一个 StringBuilder 里塞转义后的字符：
     * 早先是 split("\n+") + 每行 new StringBuilder + trim，20MB 的书要造几十万个
     * 临时字符串和字符串数组，导入慢有一半是这类垃圾带来的 GC。
     */
    public static String textToHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        int n = text.length();
        StringBuilder sb = new StringBuilder(n + n / 8 + 16);
        int i = 0;
        while (i < n) {
            int e = text.indexOf('\n', i);
            if (e < 0) e = n;
            int a = i;
            int b = e;
            // 与 String.trim() 完全一致：只去掉 <= ' ' 的字符
            while (a < b && text.charAt(a) <= ' ') a++;
            while (b > a && text.charAt(b - 1) <= ' ') b--;
            if (b > a) {
                sb.append("<p>");
                appendEsc(sb, text, a, b);
                sb.append("</p>");
            }
            i = e + 1;
        }
        return sb.toString();
    }

    private static void appendEsc(StringBuilder sb, String s, int from, int to) {
        for (int i = from; i < to; i++) {
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
    }

    /* ---------------- 分章 ---------------- */

    private static final String CN_NUM = "0-9零〇一二三四五六七八九十百千万两";
    /** 章号与标题之间的常见分隔符 */
    private static final String SEP = " \t\u3000、,，:：\\-—~－(（【\\[「";
    private static final String SPECIAL = "序章|序言|楔子|引子|终章|终曲|尾声|终言|番外篇?|特别篇|后记|跋";

    private static final Pattern P_NUM =
            Pattern.compile("^(第[" + CN_NUM + "]+[章节卷回部篇集])(.*)$");
    private static final Pattern P_SPECIAL =
            Pattern.compile("^(" + SPECIAL + ")(.*)$");

    /**
     * 判定一行是否为章节标题。
     *
     * 这里有一个已经踩过并且修好的坑，改动时务必保持：
     * **先判断有没有分隔符，再 trim**。
     * 若先 trim 再判断，分隔符会被吃掉，于是「第1章 少年不识愁滋味」这种
     * 正常的带标题章节会被判成正文 —— 整本书退化成按 5000 字硬切，
     * 目录彻底错乱（40 章的书被切成 22 段）。
     */
    public static boolean isTitleLine(String t) {
        if (t == null || t.length() > 42) return false;
        Matcher m = P_NUM.matcher(t);
        if (m.matches()) return restOk(m.group(2));
        m = P_SPECIAL.matcher(t);
        if (m.matches()) return restOk(m.group(2));
        return false;
    }

    private static boolean restOk(String raw) {
        String rest = String.valueOf(raw == null ? "" : raw).trim();
        if (rest.isEmpty()) return true;                     // 「第3章」单独成行
        if (Pattern.matches("^[" + SEP + "].*", String.valueOf(raw == null ? "" : raw))) {
            return rest.length() <= 40;                       // 有分隔符：标题可以长
        }
        return rest.length() <= 4;                            // 紧贴章号（「第3章完」）：只接受极短后缀
    }

    /**
     * 智能分章：返回 [{name, html}]。
     * - 标题行必须独立成行且短，避免把正文里的「第三章讲的是…」误判为标题
     * - 第一个标题前若有实质内容（>300 字）单独成「首章」
     * - 完全找不到标题：按 ~5000 字在段落边界兜底切分
     */
    public static List<Chapter> splitTxtChapters(String text) {
        List<Chapter> out = new ArrayList<>();
        String src = String.valueOf(text == null ? "" : text).replaceAll("\r\n?", "\n");
        String[] lines = src.split("\n", -1);
        List<int[]> marks = new ArrayList<>();  // {lineIndex}
        List<String> markNames = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (isTitleLine(t)) {
                marks.add(new int[]{i});
                markNames.add(t);
            }
        }

        if (!marks.isEmpty()) {
            StringBuilder pre = new StringBuilder();
            for (int i = 0; i < marks.get(0)[0]; i++) {
                if (i > 0) pre.append('\n');
                pre.append(lines[i]);
            }
            String preText = pre.toString();
            // 第一个标题前的实质内容：够长就单独成「首章」，否则并进第一章正文
            boolean preOwn = preText.replaceAll("\\s+", "").length() > 300;
            if (preOwn) addChapter(out, "首章", preText);
            String preAttach = preOwn ? null : preText.trim();

            for (int i = 0; i < marks.size(); i++) {
                int start = marks.get(i)[0] + 1;
                int end = (i + 1 < marks.size()) ? marks.get(i + 1)[0] : lines.length;
                StringBuilder body = new StringBuilder();
                for (int k = start; k < end; k++) {
                    if (k > start) body.append('\n');
                    body.append(lines[k]);
                }
                if (i == 0 && preAttach != null && !preAttach.isEmpty()) {
                    body.insert(0, preAttach + "\n");
                }
                // 边切边转 HTML：不再先把整本书的正文攒成一个中间列表，
                // 一本 20MB 的书省下几十 MB 常驻内存（GC 少了，导入自然快）
                addChapter(out, markNames.get(i), body.toString());
            }
        } else {
            // 无分章：按段落聚合到 ~5000 字
            StringBuilder cur = new StringBuilder();
            int curLen = 0;
            int idx = 1;
            for (String p : src.split("\n+")) {
                if (p.trim().isEmpty()) continue;
                if (cur.length() > 0) cur.append('\n');
                cur.append(p);
                curLen += p.length();
                if (curLen >= 5000) {
                    addChapter(out, "第 " + (idx++) + " 节", cur.toString());
                    cur.setLength(0);
                    curLen = 0;
                }
            }
            if (cur.length() > 0) addChapter(out, "第 " + (idx++) + " 节", cur.toString());
        }

        // 文件开头可能残留目录碎片：持续剔除「正文只有标题行」的开头章节
        while (out.size() > 1 && titleOnly(out.get(0).html)) out.remove(0);
        return out;
    }

    /** 章名归一 + 剔除空章（目录区）后转 HTML 落进结果表 */
    private static void addChapter(List<Chapter> out, String name, String bodyText) {
        if (bodyText == null || tooShort(bodyText)) return;
        String nm = String.valueOf(name == null ? "" : name).replaceAll("\\s+", " ").trim();
        if (nm.isEmpty()) nm = "(未命名章节)";
        out.add(new Chapter(nm, textToHtml(bodyText)));
    }

    /** 去掉所有空白后不足 5 字：这一章只是目录/空行，丢掉（同旧逻辑，但不再复制一整段正文） */
    private static boolean tooShort(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > ' ' && ++c >= 5) return false;
        }
        return true;
    }

    private static boolean titleOnly(String html) {
        String plain = html.replaceAll("<[^>]+>", "");
        List<String> ls = new ArrayList<>();
        for (String l : plain.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) ls.add(t);
        }
        if (ls.size() > 2) return false;
        for (String l : ls) if (!isTitleLine(l)) return false;
        return !ls.isEmpty();
    }

    /* ---------------- 流式解析（大文件不上堆） ---------------- */

    /** 每产出一章就回调一次；为 null 时退化为整本解析 */
    public interface Sink {
        void chapter(String name, String html);
    }

    /**
     * 探测编码：只看开头一段字节。
     *
     * 截断点可能正好切在多字节字符中间，严格 UTF-8 会因此失败并误判成 GBK，
     * 所以末尾 1–3 字节逐个回退再试一次（中文书里判错编码就是满屏乱码）。
     */
    public static Charset detectCharset(byte[] head) {
        if (head == null || head.length == 0) return utf8();
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB
                && (head[2] & 0xFF) == 0xBF) return utf8();
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return Charset.forName("UTF-16LE");
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return Charset.forName("UTF-16BE");
        }
        for (int cut = 0; cut <= 3 && head.length - cut > 0; cut++) {
            byte[] part = cut == 0 ? head : java.util.Arrays.copyOf(head, head.length - cut);
            String s = strictUtf8(part);
            if (s != null && s.indexOf('\uFFFD') < 0) return utf8();
        }
        try {
            Charset gbk = Charset.forName("GBK");
            return gbk;
        } catch (Exception e) {
            return utf8();
        }
    }

    /**
     * 流式分章：逐行读、边切边吐，整本书不会进内存。
     *
     * 与 {@link #splitTxtChapters(String)} 的判定规则一致（同一套 isTitleLine / tooShort），
     * 唯一区别是「开头目录碎片」要延迟判定：前几章先压进队列，等确认不是纯标题章再吐。
     */
    public static Parsed parseStream(java.io.Reader reader, String fileName, Sink sink)
            throws java.io.IOException {
        String[] meta = guessMetaFromName(fileName);
        Parsed p = new Parsed();
        p.title = meta[0];
        p.author = meta[1];

        BufferedReader br = new BufferedReader(reader, 1 << 16);
        List<Chapter> pending = new ArrayList<>();   // 前几章先压着，判断目录碎片
        StringBuilder pre = new StringBuilder();     // 首个标题之前的内容
        StringBuilder body = new StringBuilder();
        String curName = null;
        String attachPre = null;
        int emitted = 0;
        int idx = 1;
        boolean seenTitle = false;

        String line;
        while ((line = br.readLine()) != null) {
            if (line.indexOf('\uFEFF') == 0) line = line.substring(1);   // BOM
            String t = line.trim();
            if (isTitleLine(t)) {
                seenTitle = true;
                if (curName == null) {
                    String preText = pre.toString();
                    boolean preOwn = preText.replaceAll("\\s+", "").length() > 300;
                    if (preOwn) emitted += addChapter(pending, "首章", preText, sink) ? 1 : 0;
                    else attachPre = preText.trim();
                    curName = t;
                    body = new StringBuilder();
                    if (attachPre != null && !attachPre.isEmpty()) body.append(attachPre).append('\n');
                } else {
                    emitted += addChapter(pending, curName, body.toString(), sink) ? 1 : 0;
                    curName = t;
                    body = new StringBuilder();
                }
            } else if (curName == null) {
                if (pre.length() > 0) pre.append('\n');
                pre.append(line);
            } else {
                if (body.length() > 0) body.append('\n');
                body.append(line);
            }
        }
        if (curName != null) {
            emitted += addChapter(pending, curName, body.toString(), sink) ? 1 : 0;
        } else if (!seenTitle && pre.length() > 0) {
            // 完全没有章节标题：按 ~5000 字切（与整本解析的兜底一致）
            for (String para : pre.toString().split("\n+")) {
                if (para.trim().isEmpty()) continue;
                if (body.length() > 0) body.append('\n');
                body.append(para);
                if (body.length() >= 5000) {
                    emitted += addChapter(pending, "第 " + (idx++) + " 节", body.toString(), sink) ? 1 : 0;
                    body.setLength(0);
                }
            }
            if (body.length() > 0) {
                emitted += addChapter(pending, "第 " + (idx++) + " 节", body.toString(), sink) ? 1 : 0;
            }
        }
        flush(pending, sink);
        if (emitted == 0) throw new IllegalArgumentException("未解析到任何内容");
        p.chapters = pending;
        if (!pending.isEmpty()) {
            String first = pending.get(0).html.replaceAll("<[^>]+>", "");
            p.intro = first.length() > 120 ? first.substring(0, 120) : first;
        }
        return p;
    }

    /**
     * 压队列：超过 3 章就吐最前面那章（开头最多 3 章用于判断目录碎片）。
     *
     * 返回值是「这一章有没有真的产生」——不能拿「有没有立刻吐出」当数，
     * 否则全书不足 4 章时（都还压在队列里）会被误判成「没解析到任何内容」。
     */
    private static boolean addChapter(List<Chapter> pending, String name, String bodyText, Sink sink) {
        if (bodyText == null || tooShort(bodyText)) return false;
        String nm = String.valueOf(name == null ? "" : name).replaceAll("\\s+", " ").trim();
        if (nm.isEmpty()) nm = "(未命名章节)";
        Chapter c = new Chapter(nm, textToHtml(bodyText));
        pending.add(c);
        if (pending.size() > 3) {
            Chapter out = pending.remove(0);
            if (sink != null) sink.chapter(out.name, out.html);
        }
        return true;
    }

    private static void flush(List<Chapter> pending, Sink sink) {
        while (pending.size() > 1 && titleOnly(pending.get(0).html)) pending.remove(0);
        if (sink != null) {
            for (Chapter c : pending) sink.chapter(c.name, c.html);
        }
    }

    /* ---------------- 入口 ---------------- */

    public static Parsed parse(byte[] buf, String fileName) {
        String text = decodeText(buf);
        String[] meta = guessMetaFromName(fileName);
        List<Chapter> chapters = splitTxtChapters(text);
        if (chapters.isEmpty()) throw new IllegalArgumentException("未解析到任何内容");
        Parsed p = new Parsed();
        p.title = meta[0];
        p.author = meta[1];
        p.intro = chapters.get(0).html.replaceAll("<[^>]+>", "");
        if (p.intro.length() > 120) p.intro = p.intro.substring(0, 120);
        p.chapters = chapters;
        return p;
    }
}
