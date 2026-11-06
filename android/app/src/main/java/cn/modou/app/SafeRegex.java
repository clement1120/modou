package cn.modou.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 正则执行的三道防线：**预编译缓存 → 灾难回溯预检 → 超时熔断**。
 *
 * <p>书源规则是用户从别处抄来的，一条写坏的正则就能让整章净化卡上十几秒：
 * {@code (a+)+b} 配一段 aaa...a 是经典的指数回溯，CPU 单核 100%、主线程不再响应。
 * 这里不去"优化"用户的正则，而是**不让它有机会跑满**：
 * <ol>
 *   <li>编译结果进 LRU（256 条）。同一条规则每渲染一次就 compile 一次，
 *       在长章里是白烧的 CPU；</li>
 *   <li>嵌套量词在编译前就被认出来，直接跳过并计数 —— 宁可少净化一条，
 *       不能让整本书打不开；</li>
 *   <li>后台线程执行时套 Future + 3s 硬超时（见 {@link #replaceAllBounded}）。</li>
 * </ol>
 *
 * <p>主线程不能 {@code future.get(3s)} —— 那是把"卡 10 秒"换成"卡 3 秒"，
 * 用户照样骂。主线程走 {@link #looksExplosive} + 调用方的时间预算（{@link #MAIN_BUDGET_MS}）。
 */
final class SafeRegex {

    /** 编译缓存条数：一本书常见规则二三十条，256 足够覆盖"最近几本书换着看" */
    private static final int CACHE_MAX = 256;
    /** 主线程整轮净化最多花这么久，超了本轮直接收手，下次渲染再来 */
    static final long MAIN_BUDGET_MS = 260;
    /** 后台线程单条正则的硬超时 */
    static final long BG_TIMEOUT_MS = 3000;

    private static final Map<String, Pattern> CACHE =
            new LinkedHashMap<String, Pattern>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> e) {
                    return size() > CACHE_MAX;
                }
            };

    private SafeRegex() {
    }

    /* ---------------- 1. 编译缓存 ---------------- */

    static Pattern compile(String pattern) throws PatternSyntaxException {
        if (pattern == null || pattern.isEmpty()) {
            throw new PatternSyntaxException("空正则", pattern == null ? "" : pattern, 0);
        }
        synchronized (CACHE) {
            Pattern p = CACHE.get(pattern);
            if (p != null) return p;
        }
        Pattern p = Pattern.compile(pattern); // 坏正则照旧抛，由调用方跳过这条规则
        synchronized (CACHE) {
            CACHE.put(pattern, p);
        }
        return p;
    }

    static Matcher matcher(String pattern, CharSequence input) throws PatternSyntaxException {
        return compile(pattern).matcher(input);
    }

    /* ---------------- 2. 灾难回溯预检 ---------------- */

    /**
     * 粗判"嵌套量词"：一个分组里已经有 + * {n,}，分组本身又被 + * {n,} 修饰。
     *
     * <p>典型：{@code (a+)+}、{@code (\d*)*}、{@code (.*)*$}、{@code ([a-z]+)*}。
     * 这类模式在匹配失败时会尝试所有切分方式，长度每加一个字符耗时翻倍。
     *
     * <p>不追求形式化判定（那要写一个正则分析器），只抓这一类——它们占线上
     * "正则卡死"的绝大多数。误判代价是这条规则本次不生效，比卡死整章轻得多。
     */
    static boolean looksExplosive(String pattern) {
        if (pattern == null || pattern.length() < 4) return false;
        // 跳过转义：\( \) 不是分组
        final int n = pattern.length();
        int depth = 0;
        boolean quantInGroup = false;  // 当前分组内是否出现过量词
        boolean[] inner = new boolean[32];
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++; // 跳过被转义的字符（\( \) \+ \* 都不算语法）
                continue;
            }
            if (c == '[') {
                // 字符类里的一切都是字面量，直到未转义的 ]
                i++;
                while (i < n) {
                    char d = pattern.charAt(i);
                    if (d == '\\') i += 2;
                    else if (d == ']') break;
                    else i++;
                }
                continue;
            }
            if (c == '(') {
                if (depth < inner.length - 1) depth++;
                inner[depth] = false;
            } else if (c == ')') {
                if (depth > 0) {
                    boolean hadQuant = inner[depth];
                    depth--;
                    // 分组闭合后紧跟量词 → 嵌套量词
                    if (hadQuant && i + 1 < n) {
                        char q = pattern.charAt(i + 1);
                        if (q == '+' || q == '*') return true;
                        if (q == '{' && i + 2 < n && Character.isDigit(pattern.charAt(i + 2))) return true;
                    }
                    if (hadQuant) inner[depth] = true; // 向上传递：((a+)*)+ 这种也拦
                }
            } else if (depth > 0) {
                if (c == '+' || c == '*') inner[depth] = true;
                else if (c == '{' && i + 1 < n && Character.isDigit(pattern.charAt(i + 1))) {
                    inner[depth] = true;
                }
            }
        }
        return false;
    }

    /* ---------------- 3. 超时熔断（后台线程） ---------------- */

    /**
     * 在 CPU 池上跑 replaceAll，最多等 {@link #BG_TIMEOUT_MS}。
     *
     * <p>超时后取消任务并抛 {@link TimeoutException}，由调用方决定"跳过这条规则"
     * 还是"整段放弃"。**注意**：{@code Future.cancel(true)} 只是发中断信号，
     * 纯 Java 正则不响应中断，线程仍会跑完 —— 所以真正的拦截靠预检，
     * 这里的作用是"别让调用方跟着一起等"。
     */
    static String replaceAllBounded(final String pattern, final String input,
                                    final String replacement)
            throws PatternSyntaxException, TimeoutException {
        if (looksExplosive(pattern)) {
            throw new PatternSyntaxException("规则疑似灾难性回溯", pattern, 0);
        }
        final Pattern p = compile(pattern);
        Future<String> f = TaskPools.cpu().submit(() ->
                p.matcher(input).replaceAll(replacement));
        try {
            return f.get(BG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw new TimeoutException("规则执行被中断");
        } catch (ExecutionException e) {
            f.cancel(true);
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new TimeoutException(String.valueOf(c == null ? e : c.getMessage()));
        } catch (TimeoutException e) {
            f.cancel(true);
            throw e;
        }
    }

    /** 清缓存（改规则、换书时不用调；只在内存吃紧时可调） */
    static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
