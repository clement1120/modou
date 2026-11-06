package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 书源结构化编辑器（替代 SourcesPanel.editSource 的「5 字段 + 原始 JSON」）。
 *
 * 设计目标（对照 docs/书源页重设计研究.md 痛点②「只能手改 JSON」）：
 *  · 按 legado 真实规则字段分组（基本信息 / 搜索 / 发现 / 详情 / 目录 / 正文 / 高级），
 *    每个字段一个独立输入框，不再逼用户手敲一整坨 JSON。
 *  · 字段命名对齐 web/js/engine/source.js 的 SEARCH/INFO/TOC_FIELDS，并补上列表容器键
 *    （bookList / chapterList）—— 它们是取数入口，漏掉会让书源彻底抓不到内容。
 *  · 数据保真：打开时克隆整份源 JSON，保存时**只覆写编辑器涉及的字段**，其余字段
 *    （含各种 legado 可选字段、非文本的额外键）原样保留，绝不整体覆盖。
 *  · 折叠分组 + 底部「原始 JSON」逃生舱（默认收起）：给高级用户整体覆盖的出口。
 *
 * 复用：ModouUi（配色/输入框/开关/卡片/底部按钮）、DesignSpec（圆角/间距）。纯 Java 零依赖。
 */
final class SourceEditor {

    /** 搜索 / 发现 字段（与 source.js SEARCH_FIELDS 一致）；label 为中文语义 */
    private static final String[][] SEARCH_FIELDS = {
            {"书名", "name"}, {"作者", "author"}, {"分类", "kind"}, {"简介", "intro"},
            {"最新章节", "lastChapter"}, {"更新时间", "updateTime"}, {"书籍地址", "bookUrl"},
            {"封面", "coverUrl"}, {"字数", "wordCount"}
    };
    /** 详情字段（与 source.js INFO_FIELDS 一致） */
    private static final String[][] INFO_FIELDS = {
            {"书名", "name"}, {"作者", "author"}, {"简介", "intro"}, {"分类", "kind"},
            {"最新章节", "lastChapter"}, {"更新时间", "updateTime"}, {"封面", "coverUrl"},
            {"目录地址", "tocUrl"}, {"可重命名", "canReName"}
    };
    /** 目录字段（与 source.js TOC_FIELDS 一致） */
    private static final String[][] TOC_FIELDS = {
            {"章节名", "chapterName"}, {"章节地址", "chapterUrl"}, {"是否卷", "isVolume"},
            {"是否Vip", "isVip"}, {"是否付费", "isPay"}
    };

    private final Activity host;
    private JSONObject base;
    private List<Runnable> writers;
    private EditText rawJson;
    /** 打开时这条记录的主键。保存必须沿用它，否则改名/改地址会新增一条、旧的还留着 */
    private String editId = "";

    SourceEditor(Activity host) {
        this.host = host;
    }

    void open(JSONObject src) {
        ModouUi ui = ModouUi.of(host);
        base = src == null ? new JSONObject() : clone(src);
        editId = src == null ? "" : src.optString("id", "");
        writers = new ArrayList<>();

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 8), ModouUi.dp(host, 14), 0);

        // 基本信息
        box.addView(section(ui, "基本信息", true, c -> {
            if (src == null) {
                TextView tip = ModouUi.tv(host,
                        "新建书源：至少填「书源名称」和「书源地址」，再展开下面的搜索 / 目录 / 正文规则。"
                                + "规则写法与 Legado 一致。", 11, ui.sub);
                tip.setPadding(0, ModouUi.dp(host, 2), 0, ModouUi.dp(host, 4));
                c.addView(tip);
            }
            c.addView(topField(ui, "书源名称", base.optString("bookSourceName", ""), "bookSourceName"));
            c.addView(topField(ui, "书源地址（bookSourceUrl）", base.optString("bookSourceUrl", ""), "bookSourceUrl"));
            c.addView(topField(ui, "分组（bookSourceGroup）", base.optString("bookSourceGroup", ""), "bookSourceGroup"));
            c.addView(ModouUi.switchRow(host, "启用", base.optBoolean("enabled", true),
                    on -> put(base, "enabled", on)));
        }));

        // 搜索（容器 bookList + 9 字段）
        box.addView(ruleSection(ui, "搜索（ruleSearch）", true,
                "ruleSearch", "bookList", "列表容器（bookList）", SEARCH_FIELDS, false));
        // 发现（容器 bookList + 9 字段）
        box.addView(ruleSection(ui, "发现（ruleExplore）", false,
                "ruleExplore", "bookList", "列表容器（bookList）", SEARCH_FIELDS, false));
        // 详情（无容器，9 字段直接挂在对象上）
        box.addView(ruleSection(ui, "详情（ruleBookInfo）", false,
                "ruleBookInfo", null, null, INFO_FIELDS, false));
        // 目录（容器 chapterList + 5 字段）
        box.addView(ruleSection(ui, "目录（ruleToc）", false,
                "ruleToc", "chapterList", "目录容器（chapterList）", TOC_FIELDS, false));
        // 正文（ruleContent，content 多行）
        box.addView(ruleSection(ui, "正文（ruleContent）", false,
                "ruleContent", null, null, new String[][]{{"正文", "content"}}, true));
        // 高级（注释 + 原始 JSON 逃生舱）
        box.addView(section(ui, "高级", false, c -> {
            c.addView(topField(ui, "书源注释（bookSourceComment）", base.optString("bookSourceComment", ""), "bookSourceComment"));
            TextView hint = ModouUi.tv(host,
                    "原始 JSON（逃生舱）：留空 = 只保存上方改过的字段（推荐）。"
                            + "只有想整份替换时才把完整书源粘进来。", 11, ui.sub);
            hint.setPadding(0, ModouUi.dp(host, 8), 0, ModouUi.dp(host, 4));
            c.addView(hint);
            // 这里**必须**留空。以前默认填入 pretty(src)，保存时「非空就用原始 JSON 整体覆盖」，
            // 于是上面所有结构化改动全被那份没改过的原文吃掉 —— 用户看到的就是
            // 「点编辑、改字段、保存，什么都没变」。逃生舱默认关着，要用才打开。
            rawJson = ModouUi.input(host, "留空即只保存上方改动", "", 6);
            c.addView(rawJson);
        }));

        // 表单 sheet：content 限高可滚、按钮固定在底部。
        // 之前的 sheet + 按钮塞进内容里，几十个字段要一路滚到底才看得见「保存」。
        LinearLayout footer = new LinearLayout(host);
        footer.setOrientation(LinearLayout.VERTICAL);
        final Dialog d = ModouUi.sheetForm(host, src == null ? "新建书源" : "编辑书源", box, footer);
        footer.addView(ModouUi.sheetButtons(host, d, "取消", "保存", null, () -> save()));
    }

    /* ---------------- 分组容器（可折叠） ---------------- */

    private LinearLayout section(ModouUi ui, String title, boolean expanded, SectionFill fill) {
        LinearLayout card = ui.groupCard(host);
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ModouUi.dp(host, ui.spec.rowPadH), ModouUi.dp(host, 10),
                ModouUi.dp(host, ui.spec.rowPadH), ModouUi.dp(host, 10));
        TextView t = ModouUi.tv(host, title, 14.5f, ui.text);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chev = ModouUi.tv(host, expanded ? "▾" : "▸", 16, ui.sub);
        head.addView(chev);
        card.addView(head);

        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        card.addView(content);
        fill.fill(content);
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        head.setOnClickListener(v -> {
            boolean vis = content.getVisibility() == View.VISIBLE;
            content.setVisibility(vis ? View.GONE : View.VISIBLE);
            chev.setText(vis ? "▸" : "▾");
        });
        return card;
    }

    /* ---------------- 字段 ---------------- */

    /** 顶层字段（书源名称 / 地址 / 分组 / 注释等） */
    private LinearLayout topField(ModouUi ui, String label, String value, String key) {
        TextView lab = ModouUi.tv(host, label, 12.5f, ui.sub);
        lab.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 2));
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(lab);
        final String orig = value == null ? "" : value;
        EditText ed = ModouUi.input(host, label, orig, 1);
        col.addView(ed);
        writers.add(() -> {
            // 没改过的字段不写回：原值不是字符串（null / 对象）时，
            // 写一个 "" 进去等于把数据删了
            if (String.valueOf(ed.getText()).trim().equals(orig)) return;
            put(base, key, String.valueOf(ed.getText()).trim());
        });
        return col;
    }

    /** 规则对象分组：容器键 + 已知字段 + 额外未知键，全部逐字段输入 */
    private LinearLayout ruleSection(ModouUi ui, String title, boolean expanded,
                                     String ruleKey, String containerKey, String containerLabel,
                                     String[][] fields, boolean multiline) {
        return section(ui, title, expanded, c -> {
            if (containerKey != null) {
                c.addView(ruleInput(ui, base, ruleKey, containerKey, containerLabel, multiline));
            }
            for (String[] f : fields) {
                c.addView(ruleInput(ui, base, ruleKey, f[1], f[0] + "（" + f[1] + "）", multiline));
            }
            addExtraKeys(ui, c, base, ruleKey, fields, containerKey);
        });
    }

    /** 单个规则字段输入框（含写入器） */
    private LinearLayout ruleInput(ModouUi ui, JSONObject base, String ruleKey,
                                   String key, String label, boolean multiline) {
        TextView lab = ModouUi.tv(host, label, 12.5f, ui.sub);
        lab.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 2));
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(lab);
        JSONObject rule = base.optJSONObject(ruleKey);
        String val = rule == null ? "" : rule.optString(key, "");
        EditText ed = ModouUi.input(host, label, val, multiline ? 4 : 1);
        col.addView(ed);
        final String fk = key;
        final String orig = val;
        writers.add(() -> {
            // 值没变就不写。Legado 里少数规则字段是数组/对象（如 bookList 给了对象），
            // 它们 optString 出来是空串，一旦无脑写回就把结构压成了一个空字符串。
            if (String.valueOf(ed.getText()).trim().equals(orig)) return;
            JSONObject r = ensure(base, ruleKey);
            put(r, fk, String.valueOf(ed.getText()).trim());
        });
        return col;
    }

    /** 保留规则对象里的未知键：字符串值可编辑，非文本值只读展示（不丢数据也不破坏结构） */
    private void addExtraKeys(ModouUi ui, LinearLayout c, JSONObject base,
                              String ruleKey, String[][] fields, String containerKey) {
        JSONObject rule = base.optJSONObject(ruleKey);
        if (rule == null) return;
        Set<String> known = new HashSet<>();
        if (containerKey != null) known.add(containerKey);
        for (String[] f : fields) known.add(f[1]);
        Iterator<String> it = rule.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (known.contains(k)) continue;
            Object cur = rule.opt(k);
            if (!(cur instanceof String)) {
                TextView ro = ModouUi.tv(host, k + "：" + cur, 11.5f, ui.sub);
                ro.setPadding(0, ModouUi.dp(host, 4), 0, ModouUi.dp(host, 2));
                c.addView(ro);
                continue;
            }
            TextView lab = ModouUi.tv(host, k + "（额外）", 12.5f, ui.sub);
            lab.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 2));
            LinearLayout col = new LinearLayout(host);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(lab);
            EditText ed = ModouUi.input(host, k, (String) cur, 1);
            col.addView(ed);
            final String fk = k;
            writers.add(() -> {
                JSONObject r = ensure(base, ruleKey);
                put(r, fk, String.valueOf(ed.getText()).trim());
            });
            c.addView(col);
        }
    }

    /* ---------------- 保存 ---------------- */

    private void save() {
        // 1) 结构化字段已通过 writers 写入 base
        for (Runnable w : writers) w.run();
        // 2) 原始 JSON 逃生舱：填了就整体覆盖 base（以原始为准）
        JSONObject out = base;
        String raw = rawJson == null ? "" : String.valueOf(rawJson.getText()).trim();
        if (!raw.isEmpty()) {
            try {
                out = new JSONObject(raw);
            } catch (Exception e) {
                ModouUi.toast(host, "原始 JSON 解析失败：" + e.getMessage());
                return;
            }
        }
        String su = out.optString("bookSourceUrl", "").trim();
        if (su.isEmpty()) {
            ModouUi.toast(host, "书源地址不能为空");
            return;
        }
        // 主键沿用打开时那条记录的 id：导入时的规则是 url|name，改名会算出新 id，
        // upsert 变成「新增一条」，旧的那条还留在库里 —— 编辑完书源翻倍。
        // 只有新建（editId 为空）才现算一个。
        try {
            out.put("id", editId.isEmpty() ? su + "|" + out.optString("bookSourceName", "") : editId);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        try {
            ModouStore.get(host).put("sources", out.toString());
            // 规则变了，引擎侧的书源定义缓存必须立刻作废，否则马上点「校验」还是按旧规则跑
            SourceService svc = SourceService.get();
            if (svc != null) svc.invalidateDefs();
            ModouUi.toast(host, editId.isEmpty() ? "已新建，可在列表里「只校验这一个」试试"
                    : "已保存，可用「只校验这一个」验证");
            SourcesPanel.reloadActive();
        } catch (Exception e) {
            ModouUi.toast(host, "保存失败：" + e.getMessage());
        }
    }

    /* ---------------- 工具 ---------------- */

    private static JSONObject clone(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String pretty(JSONObject o) {
        try {
            return o.toString(2);
        } catch (Exception e) {
            return o.toString();
        }
    }

    private static JSONObject ensure(JSONObject base, String key) {
        JSONObject o = base.optJSONObject(key);
        if (o == null) {
            o = new JSONObject();
            try {
                base.put(key, o);
            } catch (Exception ignore) {
                /* 不可能 */
            }
        }
        return o;
    }

    private static void put(JSONObject o, String k, String v) {
        try {
            if (v == null || v.isEmpty()) o.remove(k);
            else o.put(k, v);
        } catch (Exception ignore) {
            /* 不可能 */
        }
    }

    private static void put(JSONObject o, String k, boolean v) {
        try {
            o.put(k, v);
        } catch (Exception ignore) {
            /* 不可能 */
        }
    }

    /** 折叠分组内容填充回调 */
    private interface SectionFill {
        void fill(LinearLayout c);
    }
}
