package cn.modou.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Paras — 章节 HTML → 段落文本，对齐 web/js/ui/native-reader.js 的 parasFromHtml()。
 *
 * 存在的理由：原生阅读页要「直接读库」，拿到的是 chapters 表里存的 HTML 正文，
 * 而 NativePageView 要的是纯文本段落。以前这一步在 JS 里做（parasFromHtml），
 * 原生只能 RPC 回网页侧；现在原生自己解析，已缓存的章节不用再往返 JS。
 *
 * 与 JS 版保持一致的几处细节（不一致会导致同一章在两种渲染方式下分段不同、
 * 进度偏移对不上）：
 *  - 按块级边界切段（p / br / div / li / h1-6 / blockquote / tr）；
 *  - 全角空格 　、&nbsp; 当普通空格，连续空白折叠；
 *  - 空段丢弃；
 *  - 首段若与章名相同则删掉（原生顶部已单独显示章名，否则会重复一遍）；
 *  - 输出时每段加全角缩进「　　」，段间用 \n（与 parasToText 完全相同）。
 *
 * 图片：正则不匹配 img，stripTags 后图片段落变空被丢弃——
 * 与「原生阅读页暂不支持插图」的现状一致（网页版才显示插图）。
 */
public final class Paras {

    private Paras() {
    }

    /** 章节 HTML → NativePageView 可直接吃的正文文本 */
    public static String toText(String html, String chapterName) {
        List<String> paras = fromHtml(html, chapterName);
        StringBuilder sb = new StringBuilder();
        for (String s : paras) {
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("　　").append(s);
        }
        return sb.toString();
    }

    public static List<String> fromHtml(String html, String chapterName) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        // 块级边界（开标签/闭标签都算切点）
        String[] chunks = html.split("(?i)</?(p|br|div|li|h[1-6]|blockquote|tr)\\b[^>]*>");
        for (String c : chunks) {
            String s = decode(stripTags(c))
                    .replace('\u00a0', ' ')
                    .replace('\u3000', ' ')
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!s.isEmpty()) out.add(s);
        }
        String name = chapterName == null ? "" : chapterName.replaceAll("\\s+", "");
        if (!name.isEmpty() && !out.isEmpty()
                && out.get(0).replaceAll("\\s+", "").equals(name)) {
            out.remove(0);
        }
        return out;
    }

    private static String stripTags(String s) {
        return s.replaceAll("(?i)<[^>]+>", "");
    }

    /* ---------- 块序列（图文混排用） ---------- */

    /**
     * 一「块」正文：要么是文字段，要么是一张插图。
     *
     * 之所以要有它：EPUB 正文里插图是内联的 <img>，而纯文字排版（StaticLayout + String）
     * 根本没地方放图。阅读页改用块序列后，图片以 ImageSpan 嵌进同一个 StaticLayout，
     * 分页/绘制/选区三条链路都不用重写。
     *
     * 这里刻意不依赖 Android：Paras 可以在 JVM 上直接跑测试（沙箱里没有模拟器）。
     */
    public static final class Block {
        public static final int TEXT = 0;
        public static final int IMAGE = 1;

        public final int type;
        /** TEXT=文字内容；IMAGE=图片地址（可能是 data:URL 或 http 地址） */
        public final String value;

        private Block(int t, String v) {
            type = t;
            value = v;
        }

        public static Block text(String s) {
            return new Block(TEXT, s);
        }

        public static Block image(String url) {
            return new Block(IMAGE, url);
        }

        public boolean isImage() {
            return type == IMAGE;
        }
    }

    private static final java.util.regex.Pattern IMG =
            java.util.regex.Pattern.compile("<img\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern SRC = java.util.regex.Pattern.compile(
            "\\bsrc\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 把一章 HTML 解析成「文字段 + 插图」的块序列，保持原有顺序。
     *
     * 与 fromHtml 的差别只有一处：插图不再被丢掉，而是作为独立块留在序列里。
     * 章名去重、实体解码、空白规范化全部沿用 fromHtml 的规则，
     * 保证加了图之后纯文字部分的排版结果与之前逐字一致。
     */
    public static List<Block> blocks(String html, String chapterName) {
        List<Block> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        String[] chunks = html.split("(?i)</?(p|br|div|li|h[1-6]|blockquote|tr)\\b[^>]*>");
        for (String c : chunks) {
            if (c == null || c.isEmpty()) continue;
            java.util.regex.Matcher m = IMG.matcher(c);
            int pos = 0;
            while (m.find()) {
                addText(out, c.substring(pos, m.start()));
                String src = srcOf(m.group(0));
                if (src != null && !src.isEmpty()) out.add(Block.image(src));
                pos = m.end();
            }
            addText(out, c.substring(pos));
        }
        String name = chapterName == null ? "" : chapterName.replaceAll("\\s+", "");
        if (!name.isEmpty() && !out.isEmpty() && out.get(0).type == Block.TEXT
                && out.get(0).value.replaceAll("\\s+", "").equals(name)) {
            out.remove(0);
        }
        return out;
    }

    private static void addText(List<Block> out, String raw) {
        if (raw == null || raw.isEmpty()) return;
        String s = decode(stripTags(raw))
                .replace('\u00a0', ' ')
                .replace('\u3000', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (!s.isEmpty()) out.add(Block.text(s));
    }

    /** 取 <img> 的 src；没有 src 的（比如纯 CSS 装饰图）返回 null */
    private static String srcOf(String imgTag) {
        java.util.regex.Matcher m = SRC.matcher(imgTag);
        if (!m.find()) return null;
        String v = m.group(2) != null ? m.group(2)
                : m.group(3) != null ? m.group(3) : m.group(4);
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty() || v.startsWith("{")) return null; // 模板占位符不是真地址
        return decode(v);
    }

    /** 常见实体解码（够用即可：正文里出现频率最高的就是这几个） */
    private static String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                continue;
            }
            int end = s.indexOf(';', i + 1);
            if (end < 0 || end - i > 12) {
                sb.append(c);
                continue;
            }
            String ent = s.substring(i + 1, end);
            String rep = null;
            if (ent.equalsIgnoreCase("nbsp")) rep = " ";
            else if (ent.equalsIgnoreCase("amp")) rep = "&";
            else if (ent.equalsIgnoreCase("lt")) rep = "<";
            else if (ent.equalsIgnoreCase("gt")) rep = ">";
            else if (ent.equalsIgnoreCase("quot")) rep = "\"";
            else if (ent.equalsIgnoreCase("apos") || ent.equals("#39")) rep = "'";
            else {
                try {
                    int cp;
                    if (ent.length() > 1 && (ent.charAt(0) == 'x' || ent.charAt(0) == 'X')) {
                        cp = Integer.parseInt(ent.substring(1), 16);
                    } else if (ent.charAt(0) == '#') {
                        cp = Integer.parseInt(ent.substring(1), 10);
                    } else {
                        cp = -1;
                    }
                    if (cp >= 0) rep = new String(Character.toChars(cp));
                } catch (Exception e) {
                    rep = null; // 未知实体：原样保留
                }
            }
            if (rep == null) {
                sb.append(c);
            } else {
                sb.append(rep);
                i = end;
            }
        }
        return sb.toString();
    }
}
