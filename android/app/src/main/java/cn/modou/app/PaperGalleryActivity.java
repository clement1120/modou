package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 「阅读纸张」沉浸式选择页（0.9.161）。
 *
 * <p>用户要求把阅读纸张主题也做成参考图（开元阅读「主题外观」）那个样子：
 * 眉标 + 诗意大标题 + 副标题 + 「正在使用」大预览卡 + 缩略卡网格 + 底部胶囊确认条
 * + 极大留白。四档纸张（素月白 / 晨光米 / 远山青 / 夜间）排成 2×2，卡片里画的是
 * {@link PaperMiniPreview} —— 直接读 {@link NativeTheme} 的真实纸色与墨色，不是色块。
 *
 * <p>页面<b>自身</b>仍严格按当前设计语言呈现：视觉规格全部继承
 * {@link DesignSpec.Gallery}（0.9.160 三套已定稿、两个画廊页共用一份定义），
 * 只覆盖文案与网格；iOS Large Title 滚动收起、M3 顶栏 tonal elevation 同样接上。
 *
 * <p><b>去重</b>：深浅 / 跟随系统只留在「主题外观」画廊，本页大预览卡右侧三行改成
 * 只读的纸张属性（明暗 / 色温 / 适合），不再出现第二处切换逻辑。
 *
 * <p><b>确认式切换</b>：点卡片只改待选，按底部按钮才 {@link NativeTheme#save} 落盘；
 * 通过 {@link #EXTRA_PAPER} 把结果回传给阅读页，让它走原有的 {@code applyTheme} 完整刷新。
 */
public class PaperGalleryActivity extends Activity {

    static final String EXTRA_PAPER = "paper_gallery_paper";
    private static final String KEY_PENDING = "paper_gallery_pending";

    private ModouUi ui;
    private DesignSpec sp;
    private DesignSpec.PaperGallery g;
    private final String[] ids = DesignSpec.PAPER_IDS;
    private String current;
    private String pending;

    private final View[] cards = new View[4];
    private final ThemeGalleryActivity.Badge[] badges = new ThemeGalleryActivity.Badge[4];
    private final TextView[] cardNames = new TextView[4];
    private final ThemeGalleryActivity.Surface[] cardSurfaces =
            new ThemeGalleryActivity.Surface[4];
    private final TextView[] infoVals = new TextView[3];
    private View bigPreview;
    private TextView inUse, bigName, bigTagline, applyBtn;
    private Typeface serif;
    /* iOS Large Title 收起 / M3 顶栏 tonal elevation 的滚动联动 */
    private TextView eyebrowView, titleView, subtitleView, inlineTitle, brandView;
    private View topHairline, topTonal, topChrome;
    private FrameLayout topBarBox;

    /** 设置页 / 其它页面：普通打开，画廊自己落盘 */
    static void launch(Activity from) {
        ModouTransition.push(from, new Intent(from, PaperGalleryActivity.class));
    }

    /** 阅读页：带结果打开，让阅读页用原有 applyTheme 完整刷新正文与工具坞 */
    static void launchForResult(Activity from, int requestCode) {
        from.startActivityForResult(new Intent(from, PaperGalleryActivity.class), requestCode);
        ModouTransition.forward(from);
    }

    /** 统一返回转场：三套语言各自的 pop 动作（见 {@link ModouTransition}） */
    @Override
    public void finish() {
        super.finish();
        ModouTransition.back(this);
    }

    @Override
    protected void onCreate(Bundle b) {
        ModouUi.applyTheme(this);
        super.onCreate(b);
        // 0.9.184：同 ThemeGalleryActivity —— decor 默认会再让一次状态栏，
        // 与页面自己的 statusBarPx() 叠加成顶上那片大留白。显式 edge-to-edge，只让一次。
        getWindow().setDecorFitsSystemWindows(false);
        ui = ModouUi.of(this);
        sp = ui.spec;
        g = sp.paperGallery;
        current = NativeTheme.currentName(this);
        pending = current;
        if (b != null) {
            String s = b.getString(KEY_PENDING);
            if (valid(s)) pending = s;
        }
        setContentView(build());
        applySystemBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(KEY_PENDING, pending);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 深浅 / 语言在别处被改过：整页重建才换得掉写死在 View 上的颜色
        if (ModouUi.syncTheme(this)) return;
    }

    private boolean valid(String s) {
        for (String id : ids) if (id.equals(s)) return true;
        return false;
    }

    /* ==================== 页面骨架 ==================== */

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new ThemeGalleryActivity.Backdrop(
                g.backdropA, g.backdropB, g.glassCard));

        // 0.9.173：与「主题外观」同一套修法 —— 固定顶栏时 ScrollView 从条下方开始，
        // 内容上滑只在条的下缘被裁切，绝不会压到返回钮（0.9.164 的「整屏 + 62dp 占位」已废弃）。
        final boolean fixedBar = g.largeTitleScroll || g.appBarTonalScroll;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, fixedBar ? dp(2) : statusBarPx() + dp(4), pad, dp(120));

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.setFillViewport(false);
        sc.setClipToPadding(true);
        sc.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View bar = topBar();
        if (fixedBar) {
            topChrome.setAlpha(1f);
            if (topHairline != null) topHairline.setAlpha(g.largeTitleScroll ? 1f : 0f);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            blp.topMargin = statusBarPx();
            root.addView(bar, blp);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            slp.topMargin = statusBarPx() + dp(62);
            root.addView(sc, slp);
        } else {
            content.addView(bar);
            root.addView(sc, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        content.addView(eyebrow());
        content.addView(bigTitle());
        content.addView(subtitle());
        content.addView(inUseLabel());
        content.addView(bigCard());
        content.addView(ModouUi.groupTitle(this, g.allTitle));
        content.addView(hint(g.allHint, 11.5f, dp(0), dp(10)));
        content.addView(grid());
        content.addView(footer());

        // B5：滚动镀铬统一收口到 ScrollChrome（与独立设置页、主题画廊同一处）
        ScrollChrome.of(this, g)
                .expand(eyebrowView, titleView, subtitleView, brandView)
                .bigTitle(titleView)
                .inlineTitle(inlineTitle)
                .hairline(topHairline)
                .tonal(topTonal)
                .bar(topBarBox)
                .range(dp(g.galleryStyle == 1 ? 132 : 128))
                .attach(sc);

        root.addView(actionBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        refresh(false);
        // 同上：只做位移入场，不再用 alpha 0 —— 动画被取消时会留下一张空白页
        content.setAlpha(1f);
        content.setTranslationY(dp(14));
        content.animate().translationY(0f)
                .setDuration(g.durIn).setInterpolator(interp()).start();
        return root;
    }

    /* ---------------- 顶栏 ---------------- */

    private View topBar() {
        FrameLayout wrap = new FrameLayout(this);

        // 0.9.164：滚动后才浮出的顶栏材质底色（iOS 磨砂 / M3 surfaceContainer），
        // 作为最底层铺在内容行之下，固定覆盖层滚动时才不会透出下方内容。
        // 0.9.177：材质层必须是**条的固定高度**，不能用 MATCH_PARENT。
        // FrameLayout 在 WRAP_CONTENT 父里会把 MATCH_PARENT 子量成「可用高度」，
        // 于是这条本是 62dp 的顶栏底色其实有整屏高 —— 静止时 alpha=0 看不出来，
        // 一滚动（旧逻辑按进度把 alpha 拉到 1）整页就被它盖成一片白。这就是「上滑变白」。
        topChrome = new View(this);
        topChrome.setBackgroundColor(g.barColor);
        topChrome.setAlpha(0f);
        wrap.addView(topChrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        wrap.setMinimumHeight(dp(62));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, dp(16));

        View back = ModouUi.backBtn(this, this::finish);
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        FrameLayout mid = new FrameLayout(this);
        // 0.9.201：与主题页统一为「页面名」，不再在顶栏放品牌词「轻阅」。
        brandView = ModouUi.tv(this, "阅读纸张", 14f, ui.text);
        brandView.setTypeface(Typeface.DEFAULT_BOLD);
        brandView.setGravity(Gravity.CENTER_VERTICAL);
        brandView.setPadding(dp(10), 0, 0, 0);
        mid.addView(brandView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        inlineTitle = ModouUi.tv(this, g.eyebrow, 16f, ui.text);
        inlineTitle.setTypeface(Typeface.DEFAULT_BOLD);
        inlineTitle.setGravity(Gravity.CENTER);
        inlineTitle.setAlpha(0f);
        mid.addView(inlineTitle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        bar.addView(mid, new LinearLayout.LayoutParams(0, dp(40), 1f));

        View about = ModouUi.topBtn(this, ModouGlyph.PLUS, 40, v -> showAbout());
        bar.addView(about);
        wrap.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        topTonal = new View(this);
        topTonal.setBackgroundColor(sp.navSurfaceTint != 0 ? sp.navSurfaceTint
                : Color.argb(0x0D, Color.red(sp.accent), Color.green(sp.accent), Color.blue(sp.accent)));
        topTonal.setAlpha(0f);
        wrap.addView(topTonal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        topHairline = new View(this);
        topHairline.setBackgroundColor(sp.line);
        topHairline.setAlpha(0f);
        topHairline.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)), Gravity.BOTTOM));
        wrap.addView(topHairline);

        topBarBox = wrap;
        return wrap;
    }

    private TextView eyebrow() {
        TextView t = ModouUi.tv(this, g.eyebrow, 12.5f,
                ModouUi.mix(g.titleColor, ui.sub, 0.40f));
        t.setLetterSpacing(0.10f);
        t.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        eyebrowView = t;
        return t;
    }

    private TextView bigTitle() {
        TextView t = ModouUi.tv(this, g.title, g.titleSp, g.titleColor);
        if (g.titleSerif) {
            t.setTypeface(serif());
        } else if (g.titleWeight == 2) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else {
            t.setTypeface(Typeface.DEFAULT,
                    g.titleWeight == 1 ? Typeface.BOLD : Typeface.NORMAL);
        }
        t.setLetterSpacing(g.titleTracking);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(3), 1.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        titleView = t;
        return t;
    }

    private TextView subtitle() {
        TextView t = ModouUi.tv(this, g.subtitle, 13.5f, ui.sub);
        t.setLineSpacing(dp(3), 1.25f);
        t.setIncludeFontPadding(false);
        subtitleView = t;
        return t;
    }

    private TextView inUseLabel() {
        inUse = ModouUi.tv(this, g.inUsePrefix + g.paperNames[indexOf(current)], 12f, ui.sub);
        inUse.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(30);
        lp.bottomMargin = dp(10);
        inUse.setLayoutParams(lp);
        return inUse;
    }

    /* ---------------- 「正在使用」大预览卡 ---------------- */

    private View bigCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(12);
        card.setPadding(p, p, p, p);
        card.setBackground(new ThemeGalleryActivity.Surface(sp, g, dp(g.cardRadiusDp),
                g.cardColor, g.cardRim, dp(g.cardRimDp), 0, false, g.glassCard));

        // 左：当前 / 待选纸张的迷你阅读页（大尺寸）
        bigPreview = new View(this);
        bigPreview.setBackground(new PaperMiniPreview(pending));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(178), 0.44f);
        plp.rightMargin = dp(12);
        card.addView(bigPreview, plp);

        // 右：纸张名 + 三行只读属性（不再放深浅切换，避免与主题外观重复）
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.56f));

        bigName = ModouUi.tv(this, g.paperNames[indexOf(pending)], 15f, ui.text);
        bigName.setTypeface(Typeface.DEFAULT_BOLD);
        bigName.setIncludeFontPadding(false);
        right.addView(bigName);
        bigTagline = ModouUi.tv(this, g.paperHints[indexOf(pending)], 11f, ui.sub);
        bigTagline.setIncludeFontPadding(false);
        bigTagline.setPadding(0, dp(3), 0, dp(9));
        right.addView(bigTagline);

        for (int i = 0; i < 3; i++) right.addView(infoRow(i));
        card.addView(right);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);
        return card;
    }

    private View infoRow(int idx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackground(new ThemeGalleryActivity.Surface(sp, g, dp(g.cardRadiusDp * 0.55f),
                transparent(ui.card), 0, 0, 0, false, false));

        TextView label = ModouUi.tv(this, g.infoLabels[idx], 10.5f, ui.sub);
        label.setIncludeFontPadding(false);
        row.addView(label, new LinearLayout.LayoutParams(dp(34),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView val = ModouUi.tv(this, "", 11.5f, ui.text);
        val.setIncludeFontPadding(false);
        val.setSingleLine(true);
        row.addView(val, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        infoVals[idx] = val;

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (idx > 0) lp.topMargin = dp(3);
        row.setLayoutParams(lp);
        return row;
    }

    /* ---------------- 2×2 缩略卡网格 ---------------- */

    private View grid() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        int cols = Math.max(1, g.gridColumns);
        int rows = (ids.length + cols - 1) / cols;
        for (int r = 0; r < rows; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, dp(g.thumbHeightDp), 1f);
                if (c > 0) lp.leftMargin = dp(9);
                line.addView(idx < ids.length ? thumb(idx) : new View(this), lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) rlp.topMargin = dp(9);
            box.addView(line, rlp);
        }
        return box;
    }

    private View thumb(int idx) {
        boolean on = ids[idx].equals(pending);
        FrameLayout card = new FrameLayout(this);
        card.setClipChildren(false);
        card.setClickable(true);
        ThemeGalleryActivity.Surface surface = new ThemeGalleryActivity.Surface(sp, g,
                dp(g.thumbRadiusDp), g.cardColor, g.cardRim, dp(g.cardRimDp),
                dp(g.selectStrokeDp), on, g.glassCard);
        cardSurfaces[idx] = surface;
        card.setBackground(surface);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int p = dp(9);
        inner.setPadding(p, p, p, p);

        View mini = new View(this);
        mini.setBackground(new PaperMiniPreview(ids[idx]));
        inner.addView(mini, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(g.thumbHeightDp - 58)));

        TextView name = ModouUi.tv(this, g.paperNames[idx], 12.5f,
                on ? g.selectColor : ui.text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setIncludeFontPadding(false);
        name.setSingleLine(true);
        name.setPadding(0, dp(8), 0, 0);
        inner.addView(name);
        cardNames[idx] = name;

        TextView tag = ModouUi.tv(this, g.paperHints[idx], 9.5f, ui.sub);
        tag.setIncludeFontPadding(false);
        tag.setSingleLine(true);
        tag.setPadding(0, dp(2), 0, 0);
        inner.addView(tag);
        card.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ThemeGalleryActivity.Badge bd = new ThemeGalleryActivity.Badge(
                g.selectColor, 0xFFFFFFFF, on, dp(1.2f));
        badges[idx] = bd;
        View check = new View(this);
        check.setBackground(bd);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(18), dp(18), Gravity.TOP | Gravity.END);
        clp.rightMargin = dp(9);
        clp.topMargin = dp(9);
        card.addView(check, clp);

        final int i = idx;
        card.setOnClickListener(v -> select(i));
        cards[idx] = card;
        return card;
    }

    private void select(int idx) {
        if (ids[idx].equals(pending)) return;
        pending = ids[idx];
        refresh(true);
    }

    private void refresh(boolean animate) {
        int pi = indexOf(pending);
        for (int i = 0; i < ids.length; i++) {
            boolean on = ids[i].equals(pending);
            // 与主题画廊同一套防御：网格形式可能不建勾选圈（M3 用容器色表达选中）
            if (cardSurfaces[i] != null) cardSurfaces[i].setSelected(on);
            if (badges[i] != null) badges[i].setSelected(on);
            if (cardNames[i] != null) cardNames[i].setTextColor(on ? g.selectColor : ui.text);
            if (animate && cards[i] != null) {
                float s = on ? 1.025f : 1f;
                cards[i].animate().scaleX(s).scaleY(s)
                        .setDuration(g.spring ? g.durIn : 180)
                        .setInterpolator(interp()).start();
            }
        }
        bigPreview.setBackground(new PaperMiniPreview(pending));
        bigName.setText(g.paperNames[pi]);
        bigTagline.setText(g.paperHints[pi]);
        for (int i = 0; i < 3; i++) infoVals[i].setText(g.paperInfo[pi][i]);
        inUse.setText(pending.equals(current)
                ? g.inUsePrefix + g.paperNames[indexOf(current)]
                : "预览中 · " + g.paperNames[pi]);
        applyBtn.setAlpha(pending.equals(current) ? 0.62f : 1f);
    }

    /* ---------------- 页脚 ---------------- */

    private View footer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(34), dp(4), dp(8));
        TextView a = ModouUi.tv(this, g.footerA, 11.5f,
                ModouUi.mix(g.titleColor, ui.sub, 0.35f));
        a.setLetterSpacing(0.06f);
        a.setIncludeFontPadding(false);
        box.addView(a);
        TextView b = ModouUi.tv(this, g.footerB, 11.5f, ui.sub);
        b.setIncludeFontPadding(false);
        b.setPadding(0, dp(9), 0, 0);
        box.addView(b);
        return box;
    }

    private TextView hint(String s, float size, int top, int bottom) {
        TextView t = ModouUi.tv(this, s, size, ui.sub);
        t.setLineSpacing(dp(2), 1.2f);
        t.setIncludeFontPadding(false);
        t.setPadding(dp(8), top, dp(8), bottom);
        return t;
    }

    /* ---------------- 底部胶囊确认条 ---------------- */

    private View actionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(6);
        bar.setPadding(p, p, p, p);
        bar.setBackground(new ThemeGalleryActivity.Surface(sp, g, dp(999), g.barColor,
                g.barRim, dp(1), 0, false, g.barGlass));
        bar.setElevation(dp(g.glassCard ? 6 : 3));

        TextView cancel = ModouUi.tv(this, g.cancel, 13f, g.cancelText);
        cancel.setGravity(Gravity.CENTER);
        cancel.setIncludeFontPadding(false);
        cancel.setPadding(dp(12), dp(9), dp(12), dp(9));
        cancel.setOnClickListener(v -> finish());
        bar.addView(cancel, new LinearLayout.LayoutParams(0, dp(38), 1f));

        applyBtn = ModouUi.tv(this, g.apply, 13f, g.primaryBtnText);
        applyBtn.setGravity(Gravity.CENTER);
        applyBtn.setIncludeFontPadding(false);
        applyBtn.setTypeface(Typeface.DEFAULT_BOLD);
        applyBtn.setBackground(new ThemeGalleryActivity.Surface(sp, g, dp(g.btnRadiusDp),
                g.primaryBtnColor, 0, 0, 0, false, false));
        applyBtn.setElevation(dp(1));
        applyBtn.setOnClickListener(v -> apply());
        bar.addView(applyBtn, new LinearLayout.LayoutParams(0, dp(38), 1.65f));

        FrameLayout wrap = new FrameLayout(this);
        int side = dp(16), bottom = dp(16) + navBarPx();
        wrap.setPadding(side, 0, side, bottom);
        wrap.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private void apply() {
        if (!pending.equals(current)) {
            NativeTheme.save(this, pending);   // readerLightTheme + 全局深浅联动（唯一入口）
            Intent data = new Intent();
            data.putExtra(EXTRA_PAPER, pending);
            setResult(RESULT_OK, data);
            // 对齐 APPLIED 状态键：避免 saveAppTheme 触发的配置变更这一轮又把本页重建一次
            ModouUi.applyTheme(this);
        }
        finish();
    }

    /* ---------------- 关于（＋） ---------------- */

    private void showAbout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), dp(18));
        TextView t = ModouUi.tv(this, g.aboutBody, sp.bodySp, ui.sub);
        t.setLineSpacing(dp(5), 1.35f);
        box.addView(t);
        Dialog d = ModouUi.sheet(this, g.aboutTitle, box);
        d.show();
    }

    /* ==================== 系统栏与工具 ==================== */

    /** 状态栏 / 导航栏与页面底衬同色，系统栏图标按深浅反色（参考图的整页沉浸感） */
    private void applySystemBars() {
        try {
            // 0.9.190：与主题外观页一致 —— 系统栏透明，底衬铺到屏幕最顶
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(ui.dark ? (flags & ~light) : (flags | light));
        } catch (Throwable ignore) {
            /* 个别 ROM 不支持：不影响页面本身 */
        }
    }

    private int indexOf(String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return i;
        return 0;
    }

    private int dp(float v) {
        return ModouUi.dp(this, v);
    }

    private int statusBarPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navBarPx() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(8);
    }

    private static int transparent(int color) {
        return color & 0x00FFFFFF;
    }

    private Typeface serif() {
        if (serif == null) {
            try {
                serif = Typeface.createFromAsset(getAssets(), "fonts/ModouSerif-Bold.otf");
            } catch (Throwable e) {
                serif = Typeface.create(Typeface.SERIF, Typeface.BOLD);
            }
        }
        return serif;
    }

    private Interpolator interp() {
        // A3/B9 全局收口：与主题画廊共用 DesignSpec 里唯一一份曲线定义
        return DesignSpec.interpolator(g.spring, g.overshoot, g.overshoot > 0f, 1.6f);
    }
}
