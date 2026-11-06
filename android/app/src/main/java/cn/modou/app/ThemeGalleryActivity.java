package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
 * 「主题外观」沉浸式选择页（0.9.160）。
 *
 * <p>仿参考图（开元阅读「主题外观」展示页）的<b>信息架构</b>：品牌顶栏 → 眉标 →
 * 诗意大标题 → 副标题 → 「正在使用」大预览卡（左侧界面缩略 + 右侧深浅三行）→
 * 「全部主题」三列缩略卡网格 → 底部胶囊确认条 → 页脚留白。
 *
 * <p>但页面<b>自身</b>严格按当前设计语言呈现（这是三主题大任务「方方面面」的硬要求，
 * 也是「最高还原度」验收口径）：墨读是淡绿宣纸渐变 + 打包宋体墨绿大标题 + 玻璃卡；
 * M3 Expressive 是纯色 surface + 32sp Medium 标题 + Filled card / 全圆 Filled button /
 * active indicator / expressive spring；iOS 26 是 groupedBackground + 34sp Bold Large Title +
 * Inset Grouped / hairline / 系统蓝胶囊 / 阻尼 spring。所有差异由 {@link DesignSpec.Gallery}
 * 定稿，本类只读字段，不写 {@code if (lang == ...)}。
 *
 * <p>三张缩略卡里画的是各语言<b>真实的迷你界面</b>（{@link ThemeMiniPreview}），
 * 不是色块；点选后大预览卡即时换成待选语言，按「使用选中的主题」才真正写入偏好。
 */
public class ThemeGalleryActivity extends Activity {

    private static final String KEY_PENDING = "gallery_pending";
    private static final String[] MODES = {"light", "dark", "auto"};

    private ModouUi ui;
    private DesignSpec sp;
    private DesignSpec.Gallery g;
    private final DesignSpec.Lang[] langs = DesignSpec.Lang.values();
    private DesignSpec.Lang current;
    private DesignSpec.Lang pending;

    private final View[] cards = new View[3];
    private final Badge[] badges = new Badge[3];
    private final TextView[] cardNames = new TextView[3];
    private final Surface[] cardSurfaces = new Surface[3];
    private View bigPreview;
    private TextView inUse, bigName, bigTagline;
    private LinearLayout modeBox;
    private TextView applyBtn;
    private Typeface serif;
    /* iOS Large Title 收起 / M3 顶栏 tonal elevation 的滚动联动 */
    private TextView eyebrowView, titleView, subtitleView, inlineTitle, brandView;
    private View topHairline, topTonal, topChrome;
    private FrameLayout topBarBox;

    static void launch(Activity from) {
        from.startActivity(new Intent(from, ThemeGalleryActivity.class));
    }

    @Override
    protected void onCreate(Bundle b) {
        ModouUi.applyTheme(this);
        super.onCreate(b);
        ui = ModouUi.of(this);
        sp = ui.spec;
        g = sp.gallery;
        current = DesignSpec.current(this);
        pending = current;
        if (b != null) pending = DesignSpec.parse(b.getString(KEY_PENDING));
        setContentView(build());
        applySystemBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(KEY_PENDING, pending.id);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 深浅在别处被改过 / 语言在别处被切过：整页重建才换得掉写死在 View 上的颜色
        if (ModouUi.syncTheme(this)) return;
    }

    /* ==================== 页面骨架 ==================== */

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Backdrop(g.backdropA, g.backdropB, g.glassCard));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, statusBarPx() + dp(4), pad, dp(120));

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.setFillViewport(false);
        sc.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 0.9.164（§2.11 B5 / A9）：iOS / M3 顶栏改成「固定覆盖层」，不再放在
        // content 第一行 —— 旧写法滚动时顶栏会一起滚出视口，inline 标题与
        // hairline 刚淡入就消失。墨读（两开关都 false）保留 content 第一行原结构，
        // 逐像素零回归。
        final boolean fixedBar = g.largeTitleScroll || g.appBarTonalScroll;
        View bar = topBar();
        if (fixedBar) {
            View spacer = new View(this);
            content.addView(spacer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            blp.topMargin = statusBarPx();
            root.addView(bar, blp);
        } else {
            content.addView(bar);
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

        // B5：滚动镀铬统一收口到 ScrollChrome（与独立设置页、纸张画廊同一处）
        ScrollChrome.of(this, g)
                .expand(eyebrowView, titleView, subtitleView, brandView)
                .bigTitle(titleView)
                .inlineTitle(inlineTitle)
                .hairline(topHairline)
                .chrome(topChrome)
                .tonal(topTonal)
                .bar(topBarBox)
                .attach(sc);

        root.addView(actionBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        refresh(false);
        content.setAlpha(0f);
        content.setTranslationY(dp(18));
        content.animate().alpha(1f).translationY(0f)
                .setDuration(g.durIn).setInterpolator(interp()).start();
        return root;
    }

    /* ---------------- 顶栏 ---------------- */

    private View topBar() {
        FrameLayout wrap = new FrameLayout(this);

        // 0.9.164：滚动后才浮出的顶栏材质底色（iOS 磨砂 / M3 surfaceContainer）。
        // 作为最底层铺在内容行之下，固定覆盖层滚动时才不会透出下方内容。
        topChrome = new View(this);
        topChrome.setBackgroundColor(g.barColor);
        topChrome.setAlpha(0f);
        wrap.addView(topChrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, dp(16));

        View back = ModouUi.backBtn(this, this::finish);
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // 中间：品牌名（Large Title 展开时）/ inline 标题（收起后）叠在同一位置
        FrameLayout mid = new FrameLayout(this);
        brandView = ModouUi.tv(this, "轻阅", 14f, ui.text);
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

        // 复用全局唯一的 40dp 细线圆钮：返回 / 加号同一套笔画与圆底，三语言各自出图
        View about = ModouUi.topBtn(this, ModouGlyph.PLUS, 40, v -> showAbout());
        bar.addView(about);
        wrap.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // M3：滚动后叠一层 primary tint，模拟 app bar 的 tonal elevation
        topTonal = new View(this);
        topTonal.setBackgroundColor(sp.navSurfaceTint != 0 ? sp.navSurfaceTint
                : Color.argb(0x0D, Color.red(sp.accent), Color.green(sp.accent), Color.blue(sp.accent)));
        topTonal.setAlpha(0f);
        wrap.addView(topTonal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // iOS：滚到内容区后顶栏出现 0.5px hairline（scroll edge）
        topHairline = new View(this);
        topHairline.setBackgroundColor(sp.line);
        topHairline.setAlpha(0f);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)), Gravity.BOTTOM);
        topHairline.setLayoutParams(hlp);
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
        inUse = ModouUi.tv(this, g.inUsePrefix + current.label, 12f, ui.sub);
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
        card.setBackground(new Surface(sp, g, dp(g.cardRadiusDp), g.cardColor,
                g.cardRim, dp(g.cardRimDp), 0, false, g.glassCard));

        // 左：当前/待选语言的迷你界面（大尺寸）
        bigPreview = new View(this);
        bigPreview.setBackground(new ThemeMiniPreview(pending, ui.dark));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(178), 0.44f);
        plp.rightMargin = dp(12);
        card.addView(bigPreview, plp);

        // 右：语言名 + 深浅三行
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.56f));

        bigName = ModouUi.tv(this, pending.label, 15f, ui.text);
        bigName.setTypeface(Typeface.DEFAULT_BOLD);
        bigName.setIncludeFontPadding(false);
        right.addView(bigName);
        bigTagline = ModouUi.tv(this, g.taglines[indexOf(pending)], 11f, ui.sub);
        bigTagline.setIncludeFontPadding(false);
        bigTagline.setPadding(0, dp(3), 0, dp(10));
        right.addView(bigTagline);

        modeBox = new LinearLayout(this);
        modeBox.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < MODES.length; i++) modeBox.addView(modeRow(i));
        right.addView(modeBox);
        card.addView(right);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);
        return card;
    }

    private View modeRow(int idx) {
        final boolean on = MODES[idx].equals(ModouUi.themeMode(this));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(7), dp(6), dp(7));
        row.setBackground(new Surface(sp, g, dp(g.cardRadiusDp * 0.62f),
                transparent(ui.card), 0, 0, 0, on, false));

        View icon = new View(this);
        icon.setBackground(new ModeGlyph(idx, on ? g.selectColor : ui.sub, dp(1.4f)));
        row.addView(icon, new LinearLayout.LayoutParams(dp(19), dp(19)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(9), 0, 0, 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        TextView n = ModouUi.tv(this, g.modeNames[idx], 12.5f, on ? g.selectColor : ui.text);
        n.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        n.setIncludeFontPadding(false);
        text.addView(n);
        TextView h = ModouUi.tv(this, g.modeHints[idx], 10f, ui.sub);
        h.setIncludeFontPadding(false);
        h.setPadding(0, dp(2), 0, 0);
        text.addView(h);
        row.addView(text, tlp);

        if (on) {
            View check = new View(this);
            Badge bd = new Badge(g.selectColor, 0xFFFFFFFF, true, dp(0.9f));
            check.setBackground(bd);
            row.addView(check, new LinearLayout.LayoutParams(dp(16), dp(16)));
        } else {
            TextView arrow = ModouUi.tv(this, "›", 17f, ui.sub);
            arrow.setIncludeFontPadding(false);
            row.addView(arrow);
        }

        row.setOnClickListener(v -> {
            if (MODES[idx].equals(ModouUi.themeMode(this))) return;
            // 0.9.161：设置页的深浅 segment 已移除，这里是唯一入口 —— 必须写全四处
            ModouUi.saveAppTheme(this, MODES[idx]);
            repaint();        // 原地重绘：避开 onUiModeChanged 再 post 一次 recreate 的竞态
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (idx > 0) lp.topMargin = dp(3);
        row.setLayoutParams(lp);
        return row;
    }

    /* ---------------- 三列缩略卡 ---------------- */

    private View grid() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (int i = 0; i < langs.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(190), 1f);
            if (i > 0) lp.leftMargin = dp(9);
            row.addView(thumb(i), lp);
        }
        return row;
    }

    private View thumb(int idx) {
        boolean on = langs[idx] == pending;
        FrameLayout card = new FrameLayout(this);
        card.setClipChildren(false);
        card.setClickable(true);
        Surface surface = new Surface(sp, g, dp(g.thumbRadiusDp), g.cardColor,
                g.cardRim, dp(g.cardRimDp), dp(g.selectStrokeDp), on, g.glassCard);
        cardSurfaces[idx] = surface;
        card.setBackground(surface);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int p = dp(9);
        inner.setPadding(p, p, p, p);

        // 迷你界面：该语言的真实导航 / 卡片 / 按钮 / 配色
        View mini = new View(this);
        mini.setBackground(new ThemeMiniPreview(langs[idx], ui.dark));
        inner.addView(mini, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(108)));

        TextView name = ModouUi.tv(this, langs[idx].label, 12.5f, on ? g.selectColor : ui.text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setIncludeFontPadding(false);
        name.setSingleLine(true);
        name.setPadding(0, dp(9), 0, 0);
        inner.addView(name);
        cardNames[idx] = name;

        TextView tag = ModouUi.tv(this, g.taglines[idx], 9.5f, ui.sub);
        tag.setIncludeFontPadding(false);
        tag.setLineSpacing(dp(2), 1.15f);
        tag.setMaxLines(2);
        tag.setPadding(0, dp(3), 0, 0);
        inner.addView(tag);
        card.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右上选中圈
        Badge bd = new Badge(g.selectColor, 0xFFFFFFFF, on, dp(1.2f));
        badges[idx] = bd;
        View check = new View(this);
        check.setBackground(bd);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END);
        clp.rightMargin = dp(9);
        clp.topMargin = dp(9);
        card.addView(check, clp);

        final int i = idx;
        card.setOnClickListener(v -> select(i));
        cards[idx] = card;
        return card;
    }

    private void select(int idx) {
        if (langs[idx] == pending) return;
        pending = langs[idx];
        refresh(true);
    }

    private void refresh(boolean animate) {
        for (int i = 0; i < langs.length; i++) {
            boolean on = langs[i] == pending;
            cardSurfaces[i].setSelected(on);
            badges[i].setSelected(on);
            cardNames[i].setTextColor(on ? g.selectColor : ui.text);
            if (animate) {
                float s = on ? 1.025f : 1f;
                cards[i].animate().scaleX(s).scaleY(s)
                        .setDuration(g.spring ? g.durIn : 180)
                        .setInterpolator(interp()).start();
            }
        }
        bigPreview.setBackground(new ThemeMiniPreview(pending, ui.dark));
        bigName.setText(pending.label);
        bigTagline.setText(g.taglines[indexOf(pending)]);
        inUse.setText(pending == current
                ? g.inUsePrefix + current.label
                : "预览中 · " + pending.label);
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
        bar.setBackground(new Surface(sp, g, dp(999), g.barColor,
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
        applyBtn.setBackground(new Surface(sp, g, dp(g.btnRadiusDp), g.primaryBtnColor,
                0, 0, 0, false, false));
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
        if (pending != current) {
            DesignSpec.save(this, pending);   // pref + settings 表 + 网页版钩子
            setResult(RESULT_OK);
        }
        finish();                            // 宿主 onResume 的 syncTheme 会整页重建
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

    /* ==================== 深浅切换与系统栏 ==================== */

    /**
     * 深浅切换后原地重绘，不走 {@code recreate()}。
     *
     * <p>{@code saveThemeMode} 会同步系统夜间模式，触发 Application 的
     * {@code onUiModeChanged} —— 它已经会 post 一次 recreate。这里若再手动
     * recreate 就会连着重建两次（0.9.156 修过同类竞态）。先调 {@code applyTheme}
     * 把 APPLIED 状态键对齐，配置变更那一轮的比对就会判定「已一致」而跳过，
     * 页面则由这里自己按新规格重画，pending 选择不丢。
     */
    private void repaint() {
        ModouUi.applyTheme(this);
        ui = ModouUi.of(this);
        sp = ui.spec;
        g = sp.gallery;
        current = DesignSpec.current(this);
        setContentView(build());
        applySystemBars();
    }

    /** 状态栏 / 导航栏与页面底衬同色，系统栏图标按深浅反色（参考图的整页沉浸感） */
    private void applySystemBars() {
        try {
            getWindow().setStatusBarColor(g.backdropA);
            getWindow().setNavigationBarColor(g.backdropB);
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(ui.dark ? (flags & ~light) : (flags | light));
        } catch (Throwable ignore) {
            /* 个别 ROM 不支持：不影响页面本身 */
        }
    }

    /* ==================== 小工具 ==================== */

    private int indexOf(DesignSpec.Lang l) {
        for (int i = 0; i < langs.length; i++) if (langs[i] == l) return i;
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
        // A3/B9 全局收口：三套语言的曲线只在 DesignSpec 定义一处，画廊页只读规格
        return DesignSpec.interpolator(g.spring, g.overshoot, g.overshoot > 0f, 1.6f);
    }

    /* ==================== 自绘 Drawable ====================
       0.9.161：以下 4 个 Drawable 是「主题外观」与「阅读纸张」两个画廊页共用的
       材质层，故为包内可见（PaperGalleryActivity 直接引用 ThemeGalleryActivity.Xxx）。
       定义只留这一份 —— 改曲率 / 凝光 / 选中态 / 图标时两个页面一起生效。 */

    /** 页面底衬：淡绿宣纸渐变 + 两团柔光（墨读）；M3 / iOS 是纯色 surface，无渐变 */
    static final class Backdrop extends Drawable {
        private final int a, b;
        private final boolean glow;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Backdrop(int a, int b, boolean glow) {
            this.a = a; this.b = b; this.glow = glow;
        }

        @Override
        public void draw(Canvas c) {
            Rect r = getBounds();
            float w = r.width(), h = r.height();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, w * 0.25f, h, a, b, Shader.TileMode.CLAMP));
            c.drawRect(r, p);
            if (glow) {
                p.setShader(new RadialGradient(w * 0.86f, h * 0.10f, w * 0.85f,
                        0x30FFFFFF, 0x00000000, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
                p.setShader(new RadialGradient(w * 0.10f, h * 0.86f, w * 0.75f,
                        0x1CFFFFFF, 0x00000000, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
            }
            p.setShader(null);
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /**
     * 通用圆角底板：按语言曲率（squircle / 普通圆弧）、可选顶部镜面凝光、
     * 常驻描边、选中态高亮与选中描边。卡片 / 深浅行 / 顶栏圆钮 / 底部操作条共用一套。
     */
    static final class Surface extends Drawable {
        private final DesignSpec sp;
        private final float radiusPx, rimPx, selectStrokePx;
        private final int baseColor, rimColor, selectColor;
        private final boolean glow;
        private boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Surface(DesignSpec sp, DesignSpec.Gallery g, float radiusPx, int baseColor,
                int rimColor, float rimPx, float selectStrokePx, boolean selected, boolean glow) {
            this.sp = sp;
            this.radiusPx = radiusPx;
            this.baseColor = baseColor;
            this.rimColor = rimColor;
            this.rimPx = rimPx;
            this.selectStrokePx = selectStrokePx;
            this.selectColor = g.selectColor;
            this.selected = selected;
            this.glow = glow;
        }

        void setSelected(boolean s) {
            if (selected == s) return;
            selected = s;
            invalidateSelf();
        }

        private void buildPath(RectF r) {
            path.reset();
            float half = Math.min(r.width(), r.height()) / 2f;
            if (radiusPx >= half) {
                // 全圆胶囊：用专用路径，避免超椭圆曲线在大半径下把端头画方
                if (sp.squircle) path.set(ModouShape.capsule(r));
                else path.addRoundRect(r, half, half, Path.Direction.CW);
            } else if (sp.squircle) {
                path.set(ModouShape.rect(r, radiusPx, false));
            } else {
                path.addRoundRect(r, radiusPx, radiusPx, Path.Direction.CW);
            }
        }

        @Override
        public void draw(Canvas c) {
            Rect rb = getBounds();
            RectF r = new RectF(rb);
            buildPath(r);

            p.setStyle(Paint.Style.FILL);
            p.setColor(baseColor);
            c.drawPath(path, p);

            if (glow) {
                // Liquid Glass 的顶部凝光：上半部一层由上至下衰减的白色高光
                c.save();
                c.clipPath(path);
                p.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.55f,
                        0x40FFFFFF, 0x00000000, Shader.TileMode.CLAMP));
                c.drawRect(r, p);
                p.setShader(null);
                c.restore();
            }

            if (selected) {
                // 选中高亮：M3 是 primaryContainer tonal 层，iOS 是系统蓝淡染，墨读是淡蓝玻璃
                p.setStyle(Paint.Style.FILL);
                p.setColor(selectFill());
                c.drawPath(path, p);
            }

            if (rimPx > 0 && rimColor != 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, rimPx));
                p.setColor(rimColor);
                c.drawPath(path, p);
            }
            if (selected && selectStrokePx > 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, selectStrokePx));
                p.setColor(selectColor);
                c.drawPath(path, p);
            }
        }

        private int selectFill() {
            // 由 DesignSpec.Gallery 定稿，但 Surface 是静态内部类；从 sp 直接取
            return sp.gallery.selectFill;
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 右上选中圈：未选中空心，选中实心 + 白勾 */
    static final class Badge extends Drawable {
        private final int color, checkColor;
        private final float stroke;
        private boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Badge(int color, int checkColor, boolean selected, float stroke) {
            this.color = color; this.checkColor = checkColor;
            this.selected = selected; this.stroke = Math.max(1f, stroke);
        }

        void setSelected(boolean s) {
            if (selected == s) return;
            selected = s;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.centerX(), cy = b.centerY(), r = Math.min(b.width(), b.height()) / 2f;
            if (selected) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(color);
                c.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, r * 0.20f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(checkColor);
                Path k = new Path();
                k.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                k.lineTo(cx - r * 0.08f, cy + r * 0.36f);
                k.lineTo(cx + r * 0.46f, cy - r * 0.34f);
                c.drawPath(k, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(stroke);
                p.setColor(Color.argb(120,
                        Color.red(color), Color.green(color), Color.blue(color)));
                c.drawCircle(cx, cy, r - stroke, p);
            }
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 深浅三行的线性小图标：太阳 / 月牙 / 自动（左半填充） */
    static final class ModeGlyph extends Drawable {
        private final int kind, color;
        private final float stroke;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ModeGlyph(int kind, int color, float stroke) {
            this.kind = kind; this.color = color; this.stroke = Math.max(1f, stroke);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.centerX(), cy = b.centerY();
            float s = Math.min(b.width(), b.height()) / 2f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(color);

            if (kind == 0) {
                c.drawCircle(cx, cy, s * 0.34f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float x1 = cx + (float) Math.cos(a) * s * 0.55f;
                    float y1 = cy + (float) Math.sin(a) * s * 0.55f;
                    float x2 = cx + (float) Math.cos(a) * s * 0.82f;
                    float y2 = cy + (float) Math.sin(a) * s * 0.82f;
                    c.drawLine(x1, y1, x2, y2, p);
                }
            } else if (kind == 1) {
                Path m = new Path();
                m.moveTo(cx + s * 0.52f, cy - s * 0.72f);
                m.cubicTo(cx - s * 0.18f, cy - s * 0.82f,
                        cx - s * 0.82f, cy - s * 0.06f, cx - s * 0.82f, cy + s * 0.16f);
                m.cubicTo(cx - s * 0.82f, cy + s * 0.74f,
                        cx - s * 0.10f, cy + s * 0.88f, cx + s * 0.52f, cy + s * 0.72f);
                m.cubicTo(cx + s * 0.02f, cy + s * 0.42f,
                        cx + s * 0.02f, cy - s * 0.42f, cx + s * 0.52f, cy - s * 0.72f);
                m.close();
                c.drawPath(m, p);
            } else {
                RectF r = new RectF(cx - s * 0.72f, cy - s * 0.72f, cx + s * 0.72f, cy + s * 0.72f);
                c.drawArc(r, 90, 180, false, p);
                c.drawArc(r, -90, 180, false, p);
                c.drawLine(cx, cy - s * 0.72f, cx, cy + s * 0.72f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawArc(r, 90, 180, true, p);
            }
        }

        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
