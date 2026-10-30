package cn.modou.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 用户自定义净化规则（web/js/ui/app.js 的 purifyContent / purifyTitle 移植）。
 *
 * 不依赖 Android，方便在 JVM 上跑对照测试。
 * 两处必须与网页版一致，否则同一条规则在两边净化结果不同：
 *  - 非正则模式用「按字面串 split + join」，不是 replace —— replace 会把
 *    replacement 里的 $1 当捕获组，用户写替换文本时踩过这个坑；
 *  - 坏规则（正则语法错误）静默跳过，不能让一条坏规则毁掉整章。
 */
public final class Rules {

    private Rules() {
    }

    public static final class Rule {
        public String id = "";
        public String pattern = "";
        public String replacement = "";
        public boolean isRegex = false;
        /** 'content' | 'title' */
        public String scope = "content";
        public boolean enabled = true;
    }

    /** 从 JSON 数组解析；脏数据（非对象、缺 pattern）直接丢弃 */
    public static List<Rule> parse(String json) {
        List<Rule> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Rule r = new Rule();
                r.id = o.optString("id", "");
                r.pattern = o.optString("pattern", "");
                r.replacement = o.optString("replacement", "");
                r.isRegex = o.optBoolean("isRegex", false);
                r.scope = "title".equals(o.optString("scope", "content")) ? "title" : "content";
                r.enabled = o.optBoolean("enabled", true);
                if (r.pattern.isEmpty()) continue;
                out.add(r);
            }
        } catch (Exception e) {
            /* 坏存档：当空规则处理 */
        }
        return out;
    }

    public static String applyContent(String html, List<Rule> rules) {
        return apply(html, rules, "content");
    }

    public static String applyTitle(String title, List<Rule> rules) {
        return apply(title, rules, "title");
    }

    private static String apply(String s, List<Rule> rules, String scope) {
        String out = String.valueOf(s == null ? "" : s);
        if (out.isEmpty() || rules == null || rules.isEmpty()) return out;
        for (Rule r : rules) {
            if (r == null || r.pattern.isEmpty() || !r.enabled) continue;
            if (!scope.equals(r.scope)) continue;
            try {
                if (r.isRegex) {
                    out = Pattern.compile(r.pattern).matcher(out)
                            .replaceAll(safeReplacement(r.replacement));
                } else {
                    // 字面替换：split+join，避免 replacement 里的 $ 被当成捕获组
                    StringBuilder sb = new StringBuilder();
                    String[] parts = out.split(Pattern.quote(r.pattern), -1);
                    for (int i = 0; i < parts.length; i++) {
                        if (i > 0) sb.append(r.replacement == null ? "" : r.replacement);
                        sb.append(parts[i]);
                    }
                    out = sb.toString();
                }
            } catch (PatternSyntaxException e) {
                /* 坏正则跳过：不能让一条写错的规则毁掉整章 */
            } catch (Exception e) {
                /* 其他异常同样跳过 */
            }
        }
        return out;
    }

    /**
     * replaceAll 的 replacement 里 $ 与 \ 是元字符。
     * 用户写的替换文本里的 "$1" 多半是想原样输出，这里转义掉。
     */
    private static String safeReplacement(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\\", "\\\\").replace("$", "\\$");
    }
}
