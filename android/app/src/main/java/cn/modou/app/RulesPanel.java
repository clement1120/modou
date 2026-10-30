package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 正文净化 / 替换规则面板（可嵌入首页底部导航，也可由 RulesActivity 薄壳承载）。
 *
 * 规则存在 settings 的 replaceRules 键下（数组），与网页版同一份存档 ——
 * 在这里改完，网页阅读页下次取章同样生效，两边不会出现「这边净化了那边没有」。
 */
final class RulesPanel {

    private static final String KEY = "replaceRules";

    private final Activity host;
    private ModouUi ui;
    private LinearLayout body;
    private TextView status;
    private final List<Rules.Rule> rules = new ArrayList<>();

    RulesPanel(Activity host) {
        this.host = host;
    }

    /** @param embedded 嵌入首页时为 true：不留状态栏偏移、不画「‹ 返回」 */
    View view(boolean embedded) {
        ui = ModouUi.of(host);

        ScrollView sv = new ScrollView(host);
        sv.setVerticalScrollBarEnabled(false);
        sv.setBackgroundColor(ui.bg);
        body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(host, 14);
        body.setPadding(p, embedded ? ModouUi.dp(host, 6) : statusBarPad(), p, p);
        sv.addView(body);

        reload();
        return sv;
    }

    private int statusBarPad() {
        int id = host.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? host.getResources().getDimensionPixelSize(id) : ModouUi.dp(host, 24);
    }

    private void reload() {
        String raw = ModouStore.get(host).settingRaw(KEY);
        // settings 里存的是 JSON 值本身（数组会被存成 JSON 文本）
        rules.clear();
        rules.addAll(Rules.parse(raw));
        paint();
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Rules.Rule r : rules) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("pattern", r.pattern);
                o.put("replacement", r.replacement);
                o.put("isRegex", r.isRegex);
                o.put("scope", r.scope);
                o.put("enabled", r.enabled);
                a.put(o);
            }
        } catch (Exception ignore) {
            /* 不可能 */
        }
        ModouStore.get(host).putSetting(KEY, a.toString());
        paint();
    }

    private void paint() {
        body.removeAllViews();

        /* page-head：26sp 大标题 + ＋ 添加（嵌入时导航由底部 tag 承担） */
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        if (!embedded()) {
            TextView back = ModouUi.tv(host, "‹ 返回", 14, ui.accent);
            back.setPadding(0, ModouUi.dp(host, 4), ModouUi.dp(host, 10), ModouUi.dp(host, 8));
            back.setOnClickListener((v) -> host.finish());
            bar.addView(back);
        }
        TextView t = ModouUi.tv(host, "正文净化", 26, ui.text);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = ModouUi.tv(host, "＋ 添加", 14, ui.accent);
        add.setPadding(ModouUi.dp(host, 10), ModouUi.dp(host, 6), ModouUi.dp(host, 2), ModouUi.dp(host, 6));
        add.setOnClickListener((v) -> edit(-1, null));
        bar.addView(add);
        body.addView(bar);

        status = ModouUi.tv(host, "", 12, ui.sub);
        status.setPadding(ModouUi.dp(host, 2), ModouUi.dp(host, 6), ModouUi.dp(host, 2), ModouUi.dp(host, 8));
        status.setText(rules.isEmpty()
                ? "还没有规则。添加后可去掉正文里的广告、错字、固定前缀。"
                : "共 " + rules.size() + " 条；点条目修改，开关停用/启用。");
        body.addView(status);

        if (rules.isEmpty()) {
            LinearLayout card = ui.groupCard(host);
            card.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 12), ModouUi.dp(host, 14), ModouUi.dp(host, 12));
            card.addView(ModouUi.tv(host, "常用例子：\n· 把「www.xxx.com」替换成空\n· 正则 ^\\s+ 去掉行首空白", 12.5f, ui.sub));
            body.addView(card);
        } else {
            // 全部规则放一个组容器里：行紧贴 + 1px 分隔线（不再每行一张大卡）
            LinearLayout group = ui.groupCard(host);
            for (int i = 0; i < rules.size(); i++) {
                if (group.getChildCount() > 0) group.addView(ModouUi.groupDivider(host));
                group.addView(ruleRow(i));
            }
            body.addView(group);
        }

        body.addView(bottomActions());
    }

    private boolean embedded() {
        return host instanceof ShelfActivity;
    }

    private View ruleRow(final int i) {
        final Rules.Rule r = rules.get(i);
        LinearLayout top = new LinearLayout(host);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ModouUi.dp(host, 10), ModouUi.dp(host, 7), ModouUi.dp(host, 12), ModouUi.dp(host, 7));

        // 自绘胶囊开关：系统 CheckBox 在这张纸上是个 Material 蓝方块
        top.addView(ModouUi.switchRow(host, "", r.enabled, on -> {
            rules.get(i).enabled = on;
            save();
        }));

        LinearLayout mid = new LinearLayout(host);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pat = ModouUi.tv(host, (r.isRegex ? "[正则] " : "") + r.pattern, 14, ui.text);
        pat.setSingleLine(true);
        pat.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(pat);
        String rep = r.replacement.isEmpty() ? "（替换为空）" : "→ " + r.replacement;
        mid.addView(ModouUi.tv(host, rep + " · " + ("title".equals(r.scope) ? "仅标题" : "正文"), 11.5f, ui.sub));
        top.addView(mid);

        TextView del = ModouUi.tv(host, "删除", 12.5f, ui.danger);
        del.setPadding(ModouUi.dp(host, 9), ModouUi.dp(host, 4), 0, ModouUi.dp(host, 4));
        del.setOnClickListener((v) -> {
            rules.remove(i);
            save();
        });
        top.addView(del);

        top.setOnClickListener((v) -> edit(i, null));
        return top;
    }

    private View bottomActions() {
        LinearLayout wrap = new LinearLayout(host);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setPadding(0, ModouUi.dp(host, 12), 0, 0);

        TextView exp = button("导出 JSON", false);
        exp.setOnClickListener((v) -> {
            JSONArray a = new JSONArray();
            try {
                for (Rules.Rule r : rules) {
                    JSONObject o = new JSONObject();
                    o.put("pattern", r.pattern);
                    o.put("replacement", r.replacement);
                    o.put("isRegex", r.isRegex);
                    o.put("scope", r.scope);
                    o.put("enabled", r.enabled);
                    a.put(o);
                }
            } catch (Exception ignore) {
                /* 不可能 */
            }
            ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("墨读规则", a.toString()));
            ModouUi.toast(host, "已复制到剪贴板");
        });
        wrap.addView(exp, btnLp());

        TextView imp = button("导入 JSON", false);
        imp.setOnClickListener((v) -> showImport());
        wrap.addView(imp, btnLp());
        return wrap;
    }

    private void showImport() {
        final EditText ed = ModouUi.input(host, "粘贴规则 JSON 数组", "", 6);
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ed);
        final Dialog d = ModouUi.sheet(host, "导入规则", box);
        box.addView(ModouUi.sheetButtons(host, d, "取消", "导入并覆盖", null, () -> {
            List<Rules.Rule> rs = Rules.parse(String.valueOf(ed.getText()));
            if (rs.isEmpty()) {
                ModouUi.toast(host, "没解析到规则");
                return;
            }
            rules.clear();
            rules.addAll(rs);
            save();
            ModouUi.toast(host, "已导入 " + rs.size() + " 条");
        }));
    }

    void edit(final int idx, final String prefill) {
        final boolean isNew = idx < 0 || idx >= rules.size();
        final Rules.Rule r = isNew ? new Rules.Rule() : rules.get(idx);

        int p = ModouUi.dp(host, 14);
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, p);

        final EditText ePat = ModouUi.input(host, "要删掉或替换的文字",
                prefill != null ? prefill : r.pattern, 1);
        final EditText eRep = ModouUi.input(host, "留空 = 直接删除", r.replacement, 1);
        box.addView(labeled("查找内容", ePat));
        box.addView(labeled("替换为", eRep));

        final boolean[] isRegex = {r.isRegex};
        final boolean[] isTitle = {"title".equals(r.scope)};
        box.addView(ModouUi.switchRow(host, "按正则表达式匹配", r.isRegex, v -> isRegex[0] = v));
        box.addView(ModouUi.switchRow(host, "只作用于章节标题", isTitle[0], v -> isTitle[0] = v));

        if (isNew) {
            r.id = "r" + System.currentTimeMillis();
            r.enabled = true;
            rules.add(r);
        }

        final Dialog d = ModouUi.sheet(host,
                isNew ? "添加规则" : "修改规则", box);

        box.addView(ModouUi.sheetButtons(host, d, "取消", "保存",
                () -> { // 取消：新增的那条要撤掉，编辑中的不动
                    if (isNew && !rules.isEmpty()) rules.remove(rules.size() - 1);
                },
                () -> {
                    // 新增时先加进列表再改字段，保存失败也不会留下半条规则
                    int at = isNew ? rules.size() - 1 : idx;
                    Rules.Rule t = rules.get(at);
                    t.pattern = String.valueOf(ePat.getText());
                    t.replacement = String.valueOf(eRep.getText());
                    t.isRegex = isRegex[0];
                    t.scope = isTitle[0] ? "title" : "content";
                    if (t.pattern.trim().isEmpty()) {
                        if (isNew) rules.remove(at);
                        ModouUi.toast(host, "查找内容不能为空");
                        return;
                    }
                    save();
                }));

        if (prefill != null) {
            // 从选词进来的：光标直接给「替换为」，留空即删除，选完就能落规则
            android.view.Window win = d.getWindow();
            if (win != null) {
                win.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            eRep.post(eRep::requestFocus);
        }
    }

    private View labeled(String label, View v) {
        LinearLayout l = new LinearLayout(host);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, 0, 0, ModouUi.dp(host, 8));
        l.addView(ModouUi.tv(host, label, 12, ui.sub));
        l.addView(v);
        return l;
    }

    private TextView button(String text, boolean primary) {
        TextView t = ModouUi.tv(host, text, 14, primary ? 0xFFFFFFFF : ui.text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ModouUi.dp(host, 8), ModouUi.dp(host, 10), ModouUi.dp(host, 8), ModouUi.dp(host, 10));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(primary ? ui.accent : ui.card);
        g.setCornerRadius(ModouUi.dp(host, 8));
        t.setBackground(g);
        return t;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(ModouUi.dp(host, 3), 0, ModouUi.dp(host, 3), 0);
        return lp;
    }
}
