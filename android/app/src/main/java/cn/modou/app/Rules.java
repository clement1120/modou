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

    /* ---------------- 内置规则 ---------------- */

    /**
     * 内置规则：装好就有一条能用的净化清单，不用用户自己琢磨正则。
     *
     * 覆盖中文书源最常见的四类噪声：广告行（「本书由 xx 整理」）、站点网址、
     * 翻页提示（「本章未完，点击下一页」）、排版脏字符（零宽字符 / 全角缩进 / 省略号）。
     * id 统一 "b:" 前缀，面板据此把它们标成「内置」，只允许开关、不允许删改 ——
     * 删掉后下次也没法凭空补回来，不如把「别改」写清楚。
     */
    public static List<Rule> builtins() {
        List<Rule> out = new ArrayList<>();
        // 只清「正文里露出来的网址」，不能碰属性里的地址：
        // 规则作用在原始 HTML 上，一条无差别的 https?:// 会把 <img src="..."> 一起清掉，
        // 有插图的章节就整页空白。用「前面不是 = : " ' 或字母斜杠」把属性值排除在外。
        out.add(rule("b:url",
                "(?i)(?<![=:\"'\\w/])(?:https?://|www\\.)[a-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
                "", true, "content", true));
        out.add(rule("b:next",
                "(本章未完|本章完|未完待续)[，,]?\\s*(请)?(点击)?(下一页|下一章)[^<\\n]{0,12}",
                "", true, "content", true));
        out.add(rule("b:site",
                "手机(用户|端)?请(浏览|访问|用)[^<\\n]{0,30}(阅读|观看|体验)",
                "", true, "content", true));
        out.add(rule("b:domain",
                "(请记住|记住|收藏)(本站|本书|本页)?(域名|网址|地址)[：:]?[^<\\n]{0,40}",
                "", true, "content", true));
        out.add(rule("b:ad",
                "(本书|本文|本章|全文)由[^<\\n]{0,24}(整理|提供|发布|收集)",
                "", true, "content", true));
        // 全角空格要按字符拼，Java 源码里写 \u3000 会被编译器提前展开成真字符
        out.add(rule("b:indent", "(?m)(?:^|(?<=>))[ \\t" + '\u3000' + "]+", "",
                true, "content", true));
        out.add(rule("b:zw", "[" + '\u200B' + "-" + '\u200D' + '\uFEFF' + "]", "",
                true, "content", true));
        out.add(rule("b:dots", "\\.{3,}|\u3002{3,}", "……", true, "content", true));
        // 标题尾巴（「第 3 章 风起（顶点小说网）」）：站点写法差异太大，默认关，需要的人自己开
        out.add(rule("b:title-site",
                "[（(\\[【][^）)\\]】]{0,10}(网|阅读|书屋|文学|小说)[）)\\]】]$",
                "", true, "title", false));
        return out;
    }

    private static Rule rule(String id, String pattern, String replacement,
                             boolean isRegex, String scope, boolean enabled) {
        Rule r = new Rule();
        r.id = id;
        r.pattern = pattern;
        r.replacement = replacement;
        r.isRegex = isRegex;
        r.scope = scope;
        r.enabled = enabled;
        return r;
    }

    public static boolean isBuiltin(Rule r) {
        return r != null && r.id != null && r.id.startsWith("b:");
    }

    /** 内置规则的中文名：正则本身没法当标题看 */
    public static String builtinName(String id) {
        switch (id) {
            case "b:url": return "去掉网址链接";
            case "b:next": return "去掉「点击下一页」提示";
            case "b:site": return "去掉「手机用户请浏览」";
            case "b:domain": return "去掉「记住本站网址」";
            case "b:ad": return "去掉「本书由 xx 整理」";
            case "b:indent": return "去掉段首全角缩进";
            case "b:zw": return "去掉零宽隐藏字符";
            case "b:dots": return "省略号统一为「……」";
            case "b:title-site": return "去掉标题里的站名后缀";
            default: return "内置规则";
        }
    }
    public static String applyTitle(String title, List<Rule> rules) {
        return apply(title, rules, "title");
    }

    private static String apply(String s, List<Rule> rules, String scope) {
        String out = String.valueOf(s == null ? "" : s);
        if (out.isEmpty() || rules == null || rules.isEmpty()) return out;
        // 主线程预算：一轮净化最多花 260ms，超了剩下的规则本轮不跑（下次渲染再来）。
        // 净化是「锦上添花」——少去一条广告没人会注意，卡 3 秒所有人都能感觉到。
        // 不引 android.os.Looper：Rules 要能在纯 JVM 上跑对照测试。Android 主线程名恒为 "main"
        final boolean onMain = "main".equals(Thread.currentThread().getName());
        final long budget = onMain ? SafeRegex.MAIN_BUDGET_MS : Long.MAX_VALUE;
        final long t0 = System.nanoTime();
        for (Rule r : rules) {
            if (r == null || r.pattern.isEmpty() || !r.enabled) continue;
            if (!scope.equals(r.scope)) continue;
            if (budget != Long.MAX_VALUE
                    && (System.nanoTime() - t0) / 1_000_000L > budget) break;
            try {
                if (r.isRegex) {
                    // 嵌套量词直接跳过：这类正则一旦回溯就是指数级，拿主线程陪跑不值
                    if (SafeRegex.looksExplosive(r.pattern)) continue;
                    out = SafeRegex.matcher(r.pattern, out)
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
