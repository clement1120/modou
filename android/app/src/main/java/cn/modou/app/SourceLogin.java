package cn.modou.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 书源「是否需要登录」判定（0.9.157）。
 *
 * <p>批量导入的书源里，需要登录才能用的源对没有账号的用户等于废源。这里把三类信号
 * 收敛到<b>一个纯静态方法</b>，供 ⋮ 一键清理 / 筛选 / 导入提示共用 —— 判定口径只改
 * 这一处，全站同步（用户要求「一改全改、减少新东西」）。
 *
 * <p>级别：
 * <ul>
 *   <li>{@link #CERTAIN} 明确：书源声明了登录入口（loginUrl / loginUi / loginCheckJs），
 *       或名称 / 分组 / 注释里明说「需登录」；</li>
 *   <li>{@link #SUSPECT} 疑似：会员 / VIP / 鉴权字样，或体检撞到登录墙；</li>
 *   <li>{@link #NONE} 不需登录。特别注意「免登录 / 无需登录」这类<b>否定</b>标注，
 *       命中即判 NONE —— 这些恰恰是最好用的源，误删比漏筛严重得多。</li>
 * </ul>
 *
 * <p>刻意<b>不联网</b>：几百个源逐个实测要跑几分钟，且临时风控 / 限流会被误判成登录墙。
 * 静态字段 + 文字标注 + 已有的体检失败流水，足够在打开面板的瞬间给出结果。
 */
final class SourceLogin {

    static final int NONE = 0;
    static final int SUSPECT = 1;
    static final int CERTAIN = 2;

    /** 判定结果：级别 + 一条给人看的依据（结果页每行都显示，便于决定要不要删） */
    static final class Verdict {
        final int level;
        final String why;

        Verdict(int level, String why) {
            this.level = level;
            this.why = why;
        }
    }

    private static final Verdict NO = new Verdict(NONE, "");

    /** 结构化登录字段：Legado 书源声明登录入口的标准字段，导入时原样保留 */
    private static final String[] LOGIN_FIELDS = {"loginUrl", "loginUi", "loginCheckJs"};

    /** 否定标注：命中即判「不需要登录」，优先于任何肯定词（防误删） */
    private static final String[] NEGATIVE = {
            "免登录", "免登陆", "无需登录", "无需登陆", "不用登录", "不用登陆",
            "不需要登录", "不需要登陆", "不需登录", "不需登陆", "无登录", "不登录",
            "无需账号", "不用账号"
    };

    /** 强肯定标注：明说要登录 */
    private static final String[] STRONG = {
            "需登录", "需登陆", "需要登录", "需要登陆", "要登录", "要登陆",
            "请先登录", "请先登陆", "必须登录", "登录后", "登陆后",
            "登录可用", "登录才能", "登陆才能", "登录才可用"
    };

    /** 弱肯定标注：可能要登录，也可能只是会员内容，归疑似让用户自己判 */
    private static final String[] WEAK = {
            "登录", "登陆", "会员", "vip", "鉴权", "付费", "充值"
    };

    /**
     * 体检 / 健康度流水里代表「撞到登录墙」的文案。
     *
     * <p>只认<b>明确的登录页文案</b>。刻意不收「被站点拒绝（需登录/鉴权）」这类
     * 401/403 通用收敛文案（见 SourceHealth.KIND_REJECT 的来由）：一次限流或临时封禁
     * 就会命中，把好源摆上删除台面是历史上踩过的坑。
     */
    private static final String[] WALL = {
            "登录墙", "请登录", "请先登录", "登录后才能", "登录后可用", "登陆后"
    };

    private SourceLogin() {
    }

    /**
     * @param o           书源 JSON（原样存库，登录字段不会被导入丢掉）
     * @param checkReason 体检 / 健康度里该源最近的失败原文（可为 null / 空）
     */
    static Verdict check(JSONObject o, String checkReason) {
        if (o == null) return NO;

        // 1. 结构化字段最可靠，不受文字否定影响
        for (String k : LOGIN_FIELDS) {
            if (hasValue(o.opt(k))) {
                return new Verdict(CERTAIN, "声明了 " + k + "（书源自带登录入口）");
            }
        }

        String text = (o.optString("bookSourceName", "") + " "
                + o.optString("bookSourceGroup", "") + " "
                + o.optString("bookSourceComment", "") + " "
                + o.optString("bookSourceDesc", "")).toLowerCase();

        // 2. 否定标注优先：「免登录 / 无需登录」的源恰恰是最好用的，绝不能误删
        for (String w : NEGATIVE) {
            if (text.contains(w)) return NO;
        }

        for (String w : STRONG) {
            if (text.contains(w)) {
                return new Verdict(CERTAIN, "名称/注释标注「" + w + "」");
            }
        }

        List<String> why = new ArrayList<>();
        for (String w : WEAK) {
            if (text.contains(w)) {
                why.add("名称/注释含「" + w + "」");
                break;
            }
        }

        // 3. 体检 / 健康度流水：撞到明确的登录页
        if (checkReason != null && !checkReason.isEmpty()) {
            String low = checkReason.toLowerCase();
            for (String w : WALL) {
                if (low.contains(w.toLowerCase())) {
                    why.add("体检撞到「" + w + "」：" + clip(checkReason));
                    break;
                }
            }
        }

        if (why.isEmpty()) return NO;
        return new Verdict(SUSPECT, join(why));
    }

    /** 体检失败原文里是否含「明确登录页」文案（供 SourceHealth 一次性筛 url 用） */
    static boolean wallWord(String msg) {
        if (msg == null) return false;
        String low = msg.toLowerCase();
        for (String w : WALL) {
            if (low.contains(w.toLowerCase())) return true;
        }
        return false;
    }

    /** 字段「有值」：非空、非 "null"、非空数组/对象，兼容部分导出工具的占位写法 */
    private static boolean hasValue(Object v) {
        if (v == null) return false;
        if (v instanceof JSONArray) return ((JSONArray) v).length() > 0;
        if (v instanceof JSONObject) return ((JSONObject) v).length() > 0;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return false;
        return !"null".equalsIgnoreCase(s) && !"[]".equals(s) && !"{}".equals(s)
                && !"false".equalsIgnoreCase(s) && !"undefined".equalsIgnoreCase(s);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append("；");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String clip(String s) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }
}
