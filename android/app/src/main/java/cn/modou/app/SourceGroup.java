package cn.modou.app;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * 书源分组的显示层清洗。
 *
 * <p>导入的书源包里，bookSourceGroup 常常不是分组，而是导出批次或调试残留：
 * 「0905校验」「2023年校验」「test」「备份」「旧版」。这些字符串直接当分组标题，
 * 用户看到的就是一堆时间戳和代码变量名。
 *
 * <p>这里只在**显示**时清洗，不改库：源文件里的分组原样保留，编辑书源时仍能看到
 * 原始值（要改就去编辑器里改），避免「打开列表顺手把用户数据洗掉」。
 */
final class SourceGroup {

    /** 正常的「未分组」 */
    static final String NONE = "未分组";
    /** 分组字段是脏数据（批号 / 校验 / 测试 / 备份）：统一归到这一档 */
    static final String IMPORTED = "导入书源";

    /**
     * 调试 / 批次痕迹：命中即整段丢弃。
     * 用中文词而不是单纯数字，避免把「第1卷」「2023新书」这种正常分组误杀。
     *
     * 注意「校验」和「效验」是两种常见写法（导入包里各占一半），
     * 「验证」又是第三种 —— 三个都得列上，否则「2023.7.20效验」会原样漏出来。
     */
    private static final Pattern DIRTY = Pattern.compile(
            "校验|校驗|效验|效驗|验证|驗證|核验|核驗|检验|檢驗|检查|檢查|检测|檢測|测试|測試|"
                    + "test|debug|temp|tmp|备份|備份|bak|导出|匯出|批次|旧版|舊版",
            Pattern.CASE_INSENSITIVE);

    /** 纯编号 / 日期：0905、2023-09-05、2023年9月 —— 都不像人起的分组名 */
    private static final Pattern PURE_NUMBER = Pattern.compile(
            "^[0-9\\s._\\-/:年月日号]+$");

    /**
     * 日期开头的批次名：「2023.7.20效验」「2023-07-04 验证777书源」「2023年7月20日」。
     *
     * 日期本身不是分组语义，后面跟什么字都只是导出批次的尾巴。
     * 要求「4 位年 + 分隔符」，所以「2023新书」「2024全量」这种正常名字不会被误杀。
     */
    private static final Pattern DATE_BATCH = Pattern.compile(
            "^\\d{4}[.\\-_/年]\\s*\\d{1,2}([.\\-_/月]\\s*\\d{1,2}\\s*[日号]?)?.*$");

    private SourceGroup() {
    }

    /** 一条书源该归到哪一组（永远返回非空、干净的字符串） */
    static String of(JSONObject o) {
        String raw = o == null ? "" : o.optString("bookSourceGroup", "");
        return ofRaw(raw);
    }

    /** 原始分组字段 → 显示用分组名 */
    static String ofRaw(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return NONE;

        boolean sawDirty = false;
        // 多分组（"玄幻,都市"）只认第一段干净的名字，保持「一组一行」的列表结构
        for (String seg : s.split("[,，、|/]")) {
            String g = clean(seg);
            if (!g.isEmpty()) return g;
            if (!seg.trim().isEmpty()) sawDirty = true;
        }
        return sawDirty ? IMPORTED : NONE;
    }

    /** 单段清洗：脏的、纯编号的返回空串 */
    static String clean(String raw) {
        String g = raw == null ? "" : raw.trim();
        if (g.isEmpty()) return "";
        g = g.replaceAll("\\s+", " ");
        if (PURE_NUMBER.matcher(g).matches()) return "";
        if (DATE_BATCH.matcher(g).matches()) return "";
        if (DIRTY.matcher(g).find()) return "";
        return g;
    }
}
