package cn.modou.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.json.JSONArray;
import org.json.JSONObject;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 「导入本地书籍」页 —— 只做一件事：**选一个放书的文件夹**。
 *
 * v0.6.127：以前这页还带着「扫描本机书籍 / 重新扫描 / 全不选 / 导入 N 本」那一整套
 * 批量导入，选完文件夹之后这些东西全是用不上的旧流程残留（书已经按文件夹列在下一页了）。
 * 现在这里只剩：选文件夹（选完直接进文件夹页），以及一个兜底的文件选择入口
 * ——微信/QQ 存在 Android/data 里的书，系统的文件夹列表里根本看不到。
 *
 * 以后从书架点「导入」会直接进 {@link LocalBooksActivity}，只有还没选过文件夹时才落到这页。
 */
public class ImportActivity extends Activity {

    private static final int REQ_PICK = 4711;
    private static final long MAX_BYTES = 60L * 1024 * 1024;

    private ModouUi ui;
    private LinearLayout body;
    private TextView folderValue;
    private TextView progress;
    private LinearLayout importRows;
    private boolean busy;
    private final ImportCenter.Listener centerListener = this::onCenterProgress;

    static void launch(Activity from) {
        ModouTransition.push(from, new Intent(from, ImportActivity.class));
    }

    /** 统一返回转场：三套语言各自的 pop 动作（见 {@link ModouTransition}） */
    @Override
    public void finish() {
        super.finish();
        ModouTransition.back(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ModouUi.applyTheme(this);
        super.onCreate(savedInstanceState);
        // 选过文件夹就直接进那个文件夹的页面
        if (!getIntent().hasExtra("manual") && LocalBooksActivity.hasFolder(this)) {
            LocalBooksActivity.launch(this);
            finish();
            return;
        }
        ui = ModouUi.of(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(this, 14);
        int e = ModouUi.topInset(this);
        body.setPadding(e, p + statusBarPx(), e, p);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        paint();
        ModouUi.enter(body);
        // 全局导入进度：这个页面只是「看板」之一，书架横幅看同一份状态
        ImportCenter.get().addListener(centerListener);
        ImportCenter.get().noteResume(this);
        onCenterProgress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ImportCenter.get().removeListener(centerListener);
    }

    private int statusBarPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : ModouUi.dp(this, 24);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy && ModouUi.syncTheme(this)) return;
        if (folderValue != null) folderValue.setText(currentPlace());
        // 从其他页面回来：进度可能已经在后台走了好几本，直接读快照
        ImportCenter.get().noteResume(this);
        onCenterProgress();
    }

    /** 导入中心有任何变化（后台线程也在跑）都重绘看板 */
    private void onCenterProgress() {
        if (progress == null || importRows == null) return;
        try {
            JSONObject snap = ImportCenter.get().snapshot();
            renderSnapshot(snap);
        } catch (Exception ignore) {
            /* 渲染失败不影响导入 */
        }
    }

    /** 把中心快照画成：总进度行 + 每本书一行状态 */
    private void renderSnapshot(JSONObject snap) {
        boolean running = snap.optBoolean("running", false);
        boolean interrupted = snap.optBoolean("interrupted", false);
        int total = snap.optInt("total", 0);
        int done = snap.optInt("done", 0);
        int ok = snap.optInt("ok", 0);
        int fail = snap.optInt("fail", 0);
        String stage = snap.optString("stage", "");
        String current = snap.optString("current", "");

        progress.setText(TextUtils.isEmpty(stage) ? "" : stage);
        progress.setVisibility(TextUtils.isEmpty(progress.getText()) ? View.GONE : View.VISIBLE);

        importRows.removeAllViews();
        JSONArray arr = snap.optJSONArray("files");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject fo = arr.optJSONObject(i);
            if (fo == null) continue;
            String name = fo.optString("name", "文件 " + (i + 1));
            String status = fo.optString("status", "pending");
            String msg = fo.optString("msg", "");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, ModouUi.dp(this, ui.spec.ioStyle == 0 ? 4 : 6), 0,
                    ModouUi.dp(this, ui.spec.ioStyle == 0 ? 4 : 6));
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            String chip = "待导入";
            int chipColor = ui.sub;
            if ("importing".equals(status)) {
                chip = "导入中";
                chipColor = ui.accent;
            } else if ("ok".equals(status)) {
                chip = "完成";
                chipColor = ui.accent;
            } else if ("fail".equals(status)) {
                chip = "失败";
                chipColor = ui.danger;
            }
            DesignSpec ps = ui.spec;
            float chipSp = ps.ioStyle == 0 ? 11f : (ps.ioStyle == 1 ? 12f : 11f);
            TextView c = ModouUi.tv(this, chip, chipSp, chipColor);
            int chp = ModouUi.dp(this, 6);
            int cvp = ModouUi.dp(this, ps.ioStyle == 0 ? 2 : 3);
            c.setPadding(chp, cvp, chp, cvp);
            if (ps.ioStyle == 1) {
                // iOS：systemFill 胶囊 + 彩色字（badge 的写法，不用描边）
                c.setBackground(ModouUi.squircle(this, ModouUi.alpha(chipColor, 0x1F), 999));
            } else if (ps.ioStyle == 2) {
                // M3：8dp chip，靠描边而不是底色
                c.setBackground(ModouUi.squircle(this, 0x00000000, 8, chipColor, 0.8f));
                c.setTextColor(chipColor);
            } else {
                c.setBackground(ModouUi.squircle(this, ModouUi.alpha(chipColor, 0x22), 6));
            }
            row.addView(c);
            TextView n = ModouUi.tv(this, name + ("fail".equals(status) && !msg.isEmpty() ? "（" + msg + "）" : ""), 13, ui.text);
            n.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
            n.setMaxLines(2);
            row.addView(n);
            importRows.addView(row);
        }
        if (running && total > 0) {
            TextView bar = ModouUi.tv(this,
                    done + "/" + total + " · " + (interrupted ? "上次中断" : "正在导入") + "《" + current + "》",
                    13, ui.accent);
            bar.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 2));
            importRows.addView(bar, 0);
        } else if (done >= total && total > 0) {
            TextView bar = ModouUi.tv(this, "本批完成：" + ok + " 成功" + (fail > 0 ? "，" + fail + " 失败" : ""), 13, ui.accent);
            bar.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 2));
            importRows.addView(bar, 0);
        }
    }

    /* ---------------- 页面 ---------------- */

    private void paint() {
        body.removeAllViews();
        if (ui.spec.ioStyle != 0) {
            paintModern(ui.spec);
            return;
        }

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ModouUi.dp(this, 40), ModouUi.dp(this, 40));
        blp.bottomMargin = ModouUi.dp(this, 8);
        body.addView(ModouUi.backBtn(this, () -> finish()), blp);

        TextView title = ModouUi.tv(this, "导入本地书籍", 26, ui.text);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setPadding(0, 0, 0, ModouUi.dp(this, 10));
        body.addView(title);

        TextView intro = ModouUi.tv(this,
                "选一个专门放书的文件夹。以后从书架点「导入」就直接列出这个文件夹里的 .txt / .epub"
                        + "（单本不超过 60MB），点一下就进书架；导入过的会标出来，不会重复加。",
                13, ui.sub);
        intro.setLineSpacing(ModouUi.dp(this, 3), 1f);
        intro.setPadding(0, 0, 0, ModouUi.dp(this, 16));
        body.addView(intro);

        body.addView(buildFolderCardModou());
        body.addView(buildFileCardModou());

        progress = ModouUi.tv(this, "", 13, ui.sub);
        progress.setLineSpacing(ModouUi.dp(this, 3), 1f);
        progress.setPadding(ModouUi.dp(this, 2), ModouUi.dp(this, 14), 0, 0);
        progress.setVisibility(TextUtils.isEmpty(progress.getText()) ? View.GONE : View.VISIBLE);
        body.addView(progress);

        importRows = new LinearLayout(this);
        importRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(importRows);
    }

    private View buildFolderCardModou() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ModouUi.dp(this, 16), ModouUi.dp(this, 14),
                ModouUi.dp(this, 16), ModouUi.dp(this, 16));
        card.setBackground(ModouUi.round(ui.card, 16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = ModouUi.tv(this, "书籍文件夹", 15, ui.text);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(h, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        folderValue = ModouUi.tv(this, currentPlace(), 12.5f,
                LocalBooksActivity.hasFolder(this) ? ui.text : ui.sub);
        folderValue.setSingleLine(true);
        folderValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderValue.setGravity(Gravity.END);
        row.addView(folderValue, new LinearLayout.LayoutParams(
                ModouUi.dp(this, 150), ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row);

        TextView btn = ModouUi.tv(this, LocalBooksActivity.hasFolder(this) ? "重新选择文件夹" : "选择文件夹",
                15, 0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, ModouUi.dp(this, 13), 0, ModouUi.dp(this, 13));
        btn.setBackground(ModouUi.round(ui.accent, 12));
        btn.setOnClickListener((v) -> chooseFolder());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = ModouUi.dp(this, 12);
        card.addView(btn, blp);

        TextView tip = ModouUi.tv(this,
                "系统会记住这个授权，下次不用再选。原文件留在你的文件夹里，我们只读取、不复制，"
                        + "所以不会额外占空间。",
                12, ui.sub);
        tip.setLineSpacing(ModouUi.dp(this, 2), 1f);
        tip.setPadding(0, ModouUi.dp(this, 10), 0, 0);
        card.addView(tip);
        return card;
    }

    private View buildFileCardModou() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ModouUi.dp(this, 16), ModouUi.dp(this, 14),
                ModouUi.dp(this, 16), ModouUi.dp(this, 16));
        card.setBackground(ModouUi.round(ui.card, 16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = ModouUi.dp(this, 12);
        card.setLayoutParams(clp);

        TextView h = ModouUi.tv(this, "不在文件夹里？直接选文件", 15, ui.text);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(h);

        TextView tip = ModouUi.tv(this,
                "微信、QQ 下载的书常放在 Android/data 里，系统的文件夹列表看不到，"
                        + "这里可以一次挑一本或多本直接导入。",
                12, ui.sub);
        tip.setLineSpacing(ModouUi.dp(this, 2), 1f);
        tip.setPadding(0, ModouUi.dp(this, 6), 0, ModouUi.dp(this, 10));
        card.addView(tip);

        TextView btn = ModouUi.tv(this, "选择文件导入", 14, ui.accent);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, ModouUi.dp(this, 11), 0, ModouUi.dp(this, 11));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(ModouUi.dp(this, 12));
        g.setStroke(Math.max(1, ModouUi.dp(this, 1.2f)), ui.accent);
        btn.setBackground(g);
        btn.setOnClickListener((v) -> pickFiles());
        card.addView(btn);
        return card;
    }

    /* ================= 0.9.172 · iOS 26 / M3 构图（Stage 4 第 10 部分） =================

       墨读这张「两张 16dp 卡 + 两颗按钮」在这一页已经很好用，但它只属于墨读：
       iOS 26 的「设置」页是 inset grouped 分组表格（白卡 + 46dp 行 + 行尾 › + 12sp 脚注），
       动作直接由「点这一行」承担；M3 Expressive 是 filled card + 40dp Filled/Outlined 按钮。
       这里按 spec 出图，ioStyle=0 时整个方法都不会被调用，墨读逐像素不动。 */

    private void paintModern(DesignSpec sp) {
        final boolean ios = sp.ioStyle == 1;

        // —— 顶栏：iOS 44dp 居中 17sp 标题、M3 64dp TopAppBar 22sp Medium ——
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(ModouUi.dp(this, ios ? 44 : 64));

        if (ios) {
            top.addView(navSlot(64, true,
                    navGlyph(ModouGlyph.BACK, 44, 22f, 2.1f, sp.sysBlue, v -> finish())));
        } else {
            top.addView(navGlyph(ModouGlyph.BACK, 48, 24f, 2.0f, ui.text, v -> finish()));
        }

        TextView navTitle = ModouUi.tv(this, ios ? "导入" : "导入本地书籍",
                ios ? 17 : 22, ui.text);
        if (ios) {
            navTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            navTitle.setLetterSpacing(-0.01f);
            navTitle.setGravity(Gravity.CENTER);
        } else {
            navTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            navTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        }
        navTitle.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (!ios) nlp.leftMargin = ModouUi.dp(this, 4);
        top.addView(navTitle, nlp);
        if (ios) {
            // 右侧留等宽空槽：没有它，居中标题会被左侧返回钮挤偏
            top.addView(navSlot(64, false, new View(this)));
        }
        body.addView(top);

        // —— iOS Large Title ——
        if (ios) {
            TextView big = ModouUi.tv(this, "导入书籍", sp.ioTitleSp, ui.text);
            big.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            big.setLetterSpacing(-0.02f);
            big.setIncludeFontPadding(false);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
            blp.topMargin = ModouUi.dp(this, 2);
            blp.bottomMargin = ModouUi.dp(this, 8);
            body.addView(big, blp);
        }

        // —— 引导段 ——
        TextView intro = ModouUi.tv(this,
                "选一个专门放书的文件夹，之后这里会列出里面的 .txt / .epub"
                        + (ios ? "（单本不超过 60MB），点一下即入书架。" : "（单本不超过 60MB），点一下就进书架；导入过的会标出来，不会重复加。"),
                sp.ioIntroSp, ui.sub);
        intro.setLineSpacing(ModouUi.dp(this, 3), 1f);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.bottomMargin = ModouUi.dp(this, ios ? 18 : 14);
        body.addView(intro, ilp);

        if (ios) {
            // —— 组 1：书籍文件夹（整行可点）——
            body.addView(iosGroup(ModouUi.tv(this, "书籍文件夹", 17, ui.text),
                    folderValue = iosValue(currentPlace()),
                    v -> chooseFolder(),
                    "系统会记住这个授权，下次不用再选。原文件留在你的文件夹里，"
                            + "我们只读取、不复制，所以不会额外占空间。", true));

            // —— 组 2：不在文件夹里 ——
            body.addView(iosGroup(ModouUi.tv(this, "从文件导入", 17, ui.text),
                    iosValue("TXT / EPUB"),
                    v -> pickFiles(),
                    "微信、QQ 下载的书常放在 Android/data 里，系统的文件夹列表看不到，这里可以一次挑一本或多本。",
                    false));
        } else {
            body.addView(m3Card("书籍文件夹", currentPlace(), "选择文件夹",
                    DesignSpec.ButtonKind.FILLED, v -> chooseFolder(), true));
            body.addView(m3Card("不在文件夹里？", "微信、QQ 下载的书常放在 Android/data 里，"
                    + "系统的文件夹列表看不到，这里可以一次挑一本或多本直接导入。",
                    "选择文件导入", DesignSpec.ButtonKind.OUTLINED, v -> pickFiles(), false));
        }

        // —— 全局导入看板（与书架横幅同一份状态）——
        progress = ModouUi.tv(this, "", ios ? 13 : 14, ui.sub);
        progress.setLineSpacing(ModouUi.dp(this, 3), 1f);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = ModouUi.dp(this, 14);
        body.addView(progress, plp);

        importRows = new LinearLayout(this);
        importRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = ModouUi.dp(this, ios ? 6 : 8);
        body.addView(importRows, rlp);
    }

    /** 卡 / 分组底色：0 = ui.card、1 = iOS 分组白卡、2 = M3 surfaceContainerLow */
    private int ioCardColor(DesignSpec sp) {
        switch (sp.ioCardBg) {
            case 1:  return sp.surface;
            case 2:  return sp.surfaceContainerLow;
            default: return ui.card;
        }
    }

    /** iOS 行尾的值：右对齐、限宽、长路径中间省略（与系统「文件夹」行一致） */
    private TextView iosValue(String text) {
        TextView v = ModouUi.tv(this, text, 17, ui.sub);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        v.setMaxWidth(ModouUi.dp(this, 170));
        v.setGravity(Gravity.END);
        return v;
    }

    /**
     * iOS inset grouped 的一组：一张白卡（12dp）+ 一条 46dp 行 + 12sp 组脚注。
     * 行尾 › 是 iOS「点整行进下一页」的唯一提示，比按钮更省视觉噪音。
     */
    private View iosGroup(TextView title, TextView value, View.OnClickListener cb,
                          String footer, boolean first) {
        DesignSpec sp = ui.spec;
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = ModouUi.dp(this, first ? 0 : sp.ioCardGapDp);
        wrap.setLayoutParams(wlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ModouUi.dp(this, sp.ioRowHDp));
        row.setPadding(ModouUi.dp(this, sp.ioCardPadH), 0, ModouUi.dp(this, sp.ioCardPadH), 0);
        row.setBackground(ModouUi.squircle(this, ioCardColor(sp), sp.ioCardCornerDp));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        if (value != null) {
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
            vlp.leftMargin = ModouUi.dp(this, 10);
            row.addView(value, vlp);
        }
        if (sp.ioRowChevron) {
            TextView chev = ModouUi.tv(this, "›", 20, ui.sub);
            chev.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
            chev.setIncludeFontPadding(false);
            row.addView(chev);
        }

        // 常驻分组底色保留在 background，按压只作为 foreground 叠加，避免把白卡覆盖没。
        row.setForeground(ModouUi.pressOverlay(this, sp.ioCardCornerDp));
        row.setOnClickListener(cb);
        row.setClickable(true);
        wrap.addView(row);

        // 组脚注：12sp 次级色、16dp 左缩进，正是 iOS grouped table 的 footer 写法
        TextView foot = ModouUi.tv(this, footer, sp.ioFootSp, ui.sub);
        foot.setLineSpacing(ModouUi.dp(this, 2), 1f);
        foot.setPadding(ModouUi.dp(this, sp.ioCardPadH), ModouUi.dp(this, 7),
                ModouUi.dp(this, sp.ioCardPadH), ModouUi.dp(this, 4));
        wrap.addView(foot);
        return wrap;
    }

    /** M3 filled card：卡内标题 titleMedium / 说明 bodyMedium / 40dp 按钮（Filled 或 Outlined） */
    private View m3Card(String title, String bodyText, String btnLabel,
                        DesignSpec.ButtonKind kind, View.OnClickListener cb, boolean folder) {
        DesignSpec sp = ui.spec;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ModouUi.squircle(this, ioCardColor(sp), sp.ioCardCornerDp));
        card.setPadding(ModouUi.dp(this, sp.ioCardPadH), ModouUi.dp(this, sp.ioCardPadT),
                ModouUi.dp(this, sp.ioCardPadH), ModouUi.dp(this, sp.ioCardPadB));

        TextView t = ModouUi.tv(this, title, 16, ui.text);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        card.addView(t);

        TextView b = ModouUi.tv(this, bodyText, 14, ui.sub);
        b.setLineSpacing(ModouUi.dp(this, 3), 1f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = ModouUi.dp(this, 6);
        card.addView(b, blp);
        if (folder) {
            folderValue = b;
            b.setTextColor(hasFolderNow() ? ui.text : ui.sub);
        }

        TextView btn = ModouUi.button(this, btnLabel, kind, ModouUi.CAPSULE, cb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(this, sp.ioBtnHDp));
        lp.topMargin = ModouUi.dp(this, 14);
        card.addView(btn, lp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = ModouUi.dp(this, sp.ioCardGapDp);
        card.setLayoutParams(clp);
        return card;
    }

    private boolean hasFolderNow() {
        return LocalBooksActivity.hasFolder(this);
    }

    /** 顶栏细线图标钮（返回）：与本地书页同一套画法 */
    private ImageView navGlyph(int glyph, int boxDp, float glyphDp, float strokeDp,
                               int color, View.OnClickListener cb) {
        ImageView iv = new ImageView(this);
        int b = ModouUi.dp(this, boxDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(b, b));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageDrawable(new ModouGlyph(glyph, color,
                ModouUi.dp(this, glyphDp), ModouUi.dp(this, strokeDp)));
        android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(b / 2f);
        iv.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        ui.dark ? 0x26FFFFFF : 0x14000000), null, mask));
        iv.setOnClickListener(cb);
        iv.setClickable(true);
        iv.setFocusable(true);
        return iv;
    }

    private LinearLayout navSlot(int wDp, boolean start, View child) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity((start ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
        box.addView(child);
        box.setLayoutParams(new LinearLayout.LayoutParams(ModouUi.dp(this, wDp), -1));
        return box;
    }

    private String currentPlace() {
        String p = LocalBooksActivity.prefs(this)
                .getString(LocalBooksActivity.PREF_PLACE, "");
        return TextUtils.isEmpty(p) ? "未选择" : p;
    }

    /* ---------------- 选文件夹 / 选文件 ---------------- */

    private void chooseFolder() {
        if (!LocalBooksActivity.pickFolder(this)) {
            // 少数 ROM 没有目录选择器：退回「选文件」，不留死在死路上
            pickFiles();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == LocalBooksActivity.REQ_TREE) {
            if (LocalBooksActivity.handleResult(this, req, res, data)) {
                LocalBooksActivity.launch(this);
                finish();
            }
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        Uri one = data.getData();
        if (one != null) uris.add(one);
        android.content.ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        }
        if (uris.isEmpty()) return;
        // 长久写权限：编辑本章保存时要写回原文件；不拿的话下次进 App
        // 就只剩只读（openOutputStream 直接抛 SecurityException）
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignore) {
                /* 部分 ROM / provider 不给长期授权：本次能用就行 */
            }
        }
        importFiles(uris);
    }

    private void pickFiles() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"text/plain", "application/epub+zip"});
            // 写权限一并要：删原文件（deleteDocument）、编辑本章写回都靠它
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(i, "选择 TXT / EPUB"), REQ_PICK);
        } catch (Exception e) {
            say("打不开文件选择器：" + e.getMessage());
        }
    }

    /** 批量导入统一走 ImportCenter：进度持久化 + 文件级状态，切页回来不丢 */
    private void importFiles(List<Uri> uris) {
        if (ImportCenter.get().isRunning()) {
            ModouUi.toast(this, "正在导入，先等当前批次完成");
            return;
        }
        busy = true;
        List<String> names = new java.util.ArrayList<>();
        List<Long> sizes = new java.util.ArrayList<>();
        for (Uri u : uris) {
            names.add(nameOf(u));
            sizes.add(sizeOf(u));
        }
        ImportCenter.get().start(this, uris, names, sizes);
        onCenterProgress();
        busy = false;
        ModouUi.toast(this, "开始导入 " + uris.size() + " 个文件，进度已保存，切到其他页面也能看到");
    }

    private void say(String s) {
        if (progress == null) return;
        progress.setVisibility(View.VISIBLE);
        progress.setText(s);
    }

    private String nameOf(Uri u) {
        String n = "";
        try (android.database.Cursor c = getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignore) {
            /* 拿不到就用 Uri 最后一段 */
        }
        if (n == null) n = "";
        if (n.isEmpty()) {
            String s = String.valueOf(u.getLastPathSegment());
            int i = s.lastIndexOf('/');
            n = i >= 0 ? s.substring(i + 1) : s;
        }
        return n;
    }

    private long sizeOf(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignore) {
            /* 问不到就让 LocalImport 按实际读到的算 */
        }
        return 0;
    }
}
