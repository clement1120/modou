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
 * <p>0.9.198：整页只有<b>一张</b>预览图（{@link ThemeMiniPreview}）。下方三行文字
 * 选设计语言，点哪行上方那张图就切成哪个语言；按「使用所选主题」才真正写入偏好。
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
    private TextView applyBtn;
    private Typeface serif;
    /* iOS Large Title 收起 / M3 顶栏 tonal elevation 的滚动联动 */
    private TextView titleView, subtitleView, inlineTitle, brandView;
    private View topHairline, topTonal, topChrome;
    private FrameLayout topBarBox;

    static void launch(Activity from) {
        ModouTransition.push(from, new Intent(from, ThemeGalleryActivity.class));
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
        // 0.9.184：窗口本身是全屏 frame，decor 默认还会给一条状态栏 padding，
        // 而本页又按 statusBarPx() 让位一次 —— 顶上因此空出约 40dp 的「大片留白」。
        // 显式声明 edge-to-edge（与书架页一致），insets 由页面自己接管，只让一次。
        getWindow().setDecorFitsSystemWindows(false);
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

        // 0.9.164：iOS / M3 顶栏是「固定覆盖层」；墨读（两开关都 false）保留 content 第一行。
        final boolean fixedBar = g.largeTitleScroll || g.appBarTonalScroll;
        View bar = topBar();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        // 0.9.172 修复「上滑重叠」：固定顶栏时 ScrollView 从条下方开始（不再是整屏 +
        // 62dp 占位），内容上滑只在条的下缘被裁切，绝不会压到返回钮和标题上。
        // 0.9.181：底部动作条是 BOTTOM 覆盖层，内容留白必须高过它（宽动作条 ≈ 156dp），
        // 否则最后一张语言卡会被按钮条压住、只在缝隙里露出半行字。
        // 0.9.196：顶栏固定覆盖层下，内容第一行离条太近（仅 2dp）显得发紧；
        // 提到 16dp 让大标题与顶栏之间留出一口气。墨读（条内嵌内容）保持从状态栏下起笔。
        // 底部留白给通栏动作条（iOS/M3）留足空间，强调色六点才不会与「取消」挤在一起。
        content.setPadding(pad, fixedBar ? dp(16) : statusBarPx() + dp(14), pad,
                g.galleryWideAction ? dp(156) : dp(128));

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.setFillViewport(false);
        sc.setClipToPadding(true);
        sc.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (fixedBar) {
            // 顶栏常驻材质：M3 是 surfaceContainer 实色，iOS 是半透明底 + scroll edge 细线。
            // 两条语言都不用「滚动才浮出底色」，因为条下方已经不再有内容穿过去。
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

        // 0.9.194：顶栏品牌字已改为「主题外观」（与用户要求一致），内容区若再挂一枚
        // 同名的 eyebrow 眉标就会在左上角叠出两个「主题外观」。这里不再重复，内容区
        // 直接从大标题（g.title）起笔，避免与顶栏标题重复。
        // 0.9.196：用统一的纵向节奏把各区块隔开（大标题→副标题→预览卡→分组标题→
        // 网格→强调色），避免原来「上密中松下密」的忽大忽小；gap() 提供一致的留白。
        content.addView(bigTitle());
        content.addView(subtitle());
        content.addView(gap(12));
        content.addView(bigCard());   // 左长图 + 右三行设计语言（选择器已并入）
        content.addView(gap(14));
        content.addView(modeCard());
        content.addView(gap(14));
        content.addView(accentSection());
        // 0.9.191：去掉页脚那两行装饰文案 —— 它会从底部动作条上方露出来，
        // 而且这一页的文字本来就已经够多。
        ModouMotion.stagger(content);

        // B5：滚动镀铬统一收口（墨读两开关 false，本类内部直接返回，零回归）
        ScrollChrome chrome = ScrollChrome.of(this, g)
                .expand(titleView, subtitleView, brandView)
                .bigTitle(titleView)
                .inlineTitle(inlineTitle)
                .bar(topBarBox)
                // 行程取「标题区高度」的量级：淡出与裁切同步，标题不会在顶栏下露出半截
                .range(dp(g.galleryStyle == 1 ? 132 : 128));
        if (g.appBarTonalScroll) chrome.tonal(topTonal);
        if (g.largeTitleScroll) chrome.hairline(topHairline);
        chrome.attach(sc);

        root.addView(actionBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        refresh(false);
        // 0.9.176：入场不再从 alpha 0 起。alpha 动画一旦被取消（重建 / 页面切走 /
        // 系统省电模式把动画时长压成 0），内容会永久停在透明状态 —— 用户看到的就是
        // 「整页空白，只剩顶栏」。位移最多 14dp，同样有「浮上来」的手感，但不会失联。
        content.setAlpha(1f);
        content.setTranslationY(dp(14));
        content.animate().translationY(0f)
                .setDuration(g.durIn).setInterpolator(interp()).start();
        return root;
    }

    /**
     * 组标题：墨读用全局 groupTitle（17sp 衬线小节），iOS / M3 各有自己的小节标题 ——
     * iOS 是 13sp 大写感次级灰（grouped table 的 section header），M3 是 16sp Medium。
     */
    private View sectionHeader(String text) {
        if (g.galleryStyle == 0) return ModouUi.groupTitle(this, text);
        // 0.9.196：原来 section header 与卡内副标题几乎同字号同灰，分不出来。
        // iOS 提到 14sp Medium + 字距 + 主文字色（不再用弱灰），M3 提到 17sp Medium，
        // 配合 build() 里统一的 30dp 上间距，分组标题终于「浮」出来了。
        TextView t = ModouUi.tv(this, text, g.galleryStyle == 1 ? 14 : 17, ui.text);
        if (g.galleryStyle == 1) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setLetterSpacing(0.04f);
            t.setPadding(dp(16), dp(2), 0, dp(6));
        } else {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextColor(ui.text);
            t.setPadding(dp(4), dp(6), 0, dp(12));
        }
        return t;
    }

    /* ---------------- 顶栏 ---------------- */

    private View topBar() {
        FrameLayout wrap = new FrameLayout(this);

        // 0.9.164：滚动后才浮出的顶栏材质底色（iOS 磨砂 / M3 surfaceContainer）。
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

        // 中间：品牌名（Large Title 展开时）/ inline 标题（收起后）叠在同一位置
        // 0.9.196：用户明确要求顶栏固定显示「主题外观」四个字（不论当前是哪种设计语言，
        // 不再用各语言的 eyebrow 变体，避免 iOS/M3 下变成「外观」之类与页名不符的字）。
        FrameLayout mid = new FrameLayout(this);
        brandView = ModouUi.tv(this, "主题外观", 14f, ui.text);
        brandView.setTypeface(Typeface.DEFAULT_BOLD);
        brandView.setGravity(Gravity.CENTER_VERTICAL);
        brandView.setPadding(dp(10), 0, 0, 0);
        mid.addView(brandView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        // 收起态标题同样固定为「主题外观」，与展开态保持一致
        inlineTitle = ModouUi.tv(this, "主题外观", g.galleryStyle == 2 ? 22f : 17f, ui.text);
        if (g.galleryStyle == 2) {
            inlineTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else {
            inlineTitle.setTypeface(Typeface.DEFAULT_BOLD);
        }
        inlineTitle.setLetterSpacing(g.galleryStyle == 1 ? -0.01f : 0f);
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

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
        // 0.9.196：原 13.5sp / ui.sub 太弱，和 32sp+ 的标题落差过大、像注释不像副标。
        // 提到 15sp 并掺一点主文字色，让「标题—副标题」成为一组、而不是两级断层。
        TextView t = ModouUi.tv(this, g.subtitle, 15f,
                ModouUi.mix(ui.text, ui.sub, 0.32f));
        t.setLineSpacing(dp(4), 1.32f);
        t.setIncludeFontPadding(false);
        subtitleView = t;
        return t;
    }

    /** 竖向留白：统一区块之间的呼吸距离（0.9.196 引入，替换原来各处硬编码的杂距） */
    private View gap(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(dp)));
        return v;
    }


    /* ---------------- 主题预览 + 设计语言（左长图 / 右三行） ---------------- */

    private View bigCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        // 0.9.201：按「图做长一点，三种设计放旁边」重排 —— 左侧一张修长的手机预览
        // （126×238dp，接近真机比例），右侧三行文字选设计语言，点行即换左图。
        // 整页只有这一张图，图与选择器同处一卡，不用滚动就能对照。
        int p = dp(14);
        card.setPadding(p, p, p, p);
        card.setBackground(new Surface(sp, g, dp(g.cardRadiusDp), g.cardColor,
                g.cardRim, dp(g.cardRimDp), 0, false, g.glassCard));

        // 左：唯一一张预览图 + 一行极简 caption
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        bigPreview = new View(this);
        bigPreview.setBackground(new ThemeMiniPreview(pending, ui.dark));
        clipRounded(bigPreview, 12);
        left.addView(bigPreview, new LinearLayout.LayoutParams(dp(126), dp(224)));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                dp(126), ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.rightMargin = dp(14);
        card.addView(left, llp);

        // 右：三行文字（设计语言）
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        TextView head = ModouUi.tv(this, g.allTitle, 12.5f, ui.sub);
        head.setIncludeFontPadding(false);
        head.setLetterSpacing(0.02f);
        head.setPadding(0, 0, 0, dp(8));
        right.addView(head);
        right.addView(themeRows());
        card.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);
        return card;
    }

    /** 显示模式：横向三格分段控件，独立于预览图与语言列表。 */
    private View modeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        card.setPadding(p, dp(10), p, dp(10));
        card.setBackground(new Surface(sp, g, dp(g.cardRadiusDp), g.cardColor,
                g.cardRim, dp(g.cardRimDp), 0, false, g.glassCard));

        TextView head = ModouUi.tv(this, "显示模式", 12.5f, ui.sub);
        head.setIncludeFontPadding(false);
        head.setLetterSpacing(0.02f);
        head.setPadding(0, 0, 0, dp(10));
        card.addView(head);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < MODES.length; i++) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) slp.leftMargin = dp(6);
            seg.addView(modeSegment(i), slp);
        }
        card.addView(seg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        card.setLayoutParams(lp);
        return card;
    }

    /** 0.9.198：深浅模式由三行竖排改为横向三格分段控件，一屏内不再堆两遍图与文字。 */
    private View modeSegment(int idx) {
        final boolean on = MODES[idx].equals(ModouUi.themeMode(this));
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(6), dp(10), dp(6), dp(10));
        cell.setBackground(new Surface(sp, g, dp(g.cardRadiusDp * 0.62f),
                on ? g.selectFill : transparent(ui.card), 0, 0, 0, false, false));

        View icon = new View(this);
        icon.setBackground(new ModeGlyph(idx, on ? g.selectColor : ui.sub, dp(1.4f)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(21), dp(21));
        ilp.bottomMargin = dp(6);
        cell.addView(icon, ilp);

        TextView n = ModouUi.tv(this, g.modeNames[idx], 12.5f,
                on ? g.selectColor : ui.text);
        n.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        n.setIncludeFontPadding(false);
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        cell.addView(n);

        cell.setOnClickListener(v -> {
            if (MODES[idx].equals(ModouUi.themeMode(this))) return;
            // 0.9.161：设置页的深浅 segment 已移除，这里是唯一入口 —— 必须写全四处
            ModouUi.saveAppTheme(this, MODES[idx]);
            repaint();        // 原地重绘：避开 onUiModeChanged 再 post 一次 recreate 的竞态
        });
        return cell;
    }

    /* ---------------- 设计语言：三行文字（位于长图右侧） ---------------- */

    /**
     * 0.9.201：三行文字选择器作为大预览卡右侧的一列，不再单独成卡。
     * 选中淡染由 Surface.selected 驱动、圈色由 Badge.setColor 动态刷新，
     * 切换语言时旧行不会残留蓝底 / 蓝圈。圆角、描边、分隔线仍取自当前语言 token。
     */
    private View themeRows() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < langs.length; i++) {
            final int idx = i;
            boolean on = langs[i] == pending;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(66));
            row.setPadding(dp(10), dp(8), dp(8), dp(8));
            row.setClickable(true);
            float r = dp(g.cardRadiusDp * 0.55f);
            Surface surface = new Surface(sp, g, r,
                    transparent(ui.card), 0, 0, 0, on, false);
            cardSurfaces[i] = surface;
            row.setBackground(surface);
            row.setForeground(ModouUi.pressOverlay(this, g.cardRadiusDp * 0.55f));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = ModouUi.tv(this, langs[i].label, 15f,
                    on ? g.selectColor : ui.text);
            name.setIncludeFontPadding(false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (on) name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            cardNames[i] = name;
            TextView tag = ModouUi.tv(this, g.taglines[i], 11.5f, ui.sub);
            tag.setIncludeFontPadding(false);
            tag.setSingleLine(true);
            tag.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tag.setPadding(0, dp(3), 0, 0);
            col.addView(name);
            col.addView(tag);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Badge bd = new Badge(on ? g.selectColor : ui.sub, 0xFFFFFFFF, on, dp(1.2f));
            badges[i] = bd;
            View check = new View(this);
            check.setBackground(bd);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(20), dp(20));
            clp.leftMargin = dp(6);
            row.addView(check, clp);

            row.setOnClickListener(v -> select(idx));
            cards[i] = row;
            box.addView(row);

            if (i < langs.length - 1) {
                View line = new View(this);
                line.setBackgroundColor(sp.divider);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
                llp.leftMargin = dp(10);
                box.addView(line, llp);
            }
        }
        return box;
    }

    /** 给任意 View 加圆角裁剪（缩略图 / 子行高亮溢出） */
    private void clipRounded(View v, float rDp) {
        final float r = dp(rDp);
        v.setClipToOutline(true);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
    }

    private void select(int idx) {
        if (langs[idx] == pending) return;
        pending = langs[idx];
        refresh(true);
        morphSelection(idx);
    }

    /**
     * 0.9.176（A4 形状 morph）：选中卡的圆角从 base 涨到 base+4dp，其余回落。
     *
     * <p>墨读（galleryStyle=0）不做 —— 它的选中表达是描边 + 勾选圈，圆角一动就不是它了。
     * 动画只改 Drawable 半径并 invalidate，不触发布局，几千次也扛得住。
     */
    private void morphSelection(int selected) {
        if (g.galleryStyle == 0) return;
        float baseDp = g.thumbRadiusDp;
        float growDp = g.galleryStyle == 2 ? 4f : 2f;
        for (int i = 0; i < cardSurfaces.length; i++) {
            final Surface sf = cardSurfaces[i];
            if (sf == null) continue;
            float to = dp(i == selected ? baseDp + growDp : baseDp);
            android.animation.ValueAnimator a =
                    android.animation.ValueAnimator.ofFloat(sf.radiusPx(), to);
            a.setDuration(g.spring ? g.durIn : 180);
            a.setInterpolator(interp());
            a.addUpdateListener(x -> sf.setRadiusPx((float) x.getAnimatedValue()));
            a.start();
        }
    }

    private void refresh(boolean animate) {
        for (int i = 0; i < langs.length; i++) {
            boolean on = langs[i] == pending;
            // 0.9.176：M3 的选中态是「整卡换 tonal 容器色」，没有右上角勾选圈，
            // 所以 m3Thumb 不会创建 Badge。这里必须逐项判空 —— 否则切到 M3 后
            // 一进主题外观页就在 badges[i].setSelected 上 NPE（用户报的「点主题外观闪退」）。
            if (cardSurfaces[i] != null) cardSurfaces[i].setSelected(on);
            if (badges[i] != null) {
                badges[i].setColor(on ? g.selectColor : ui.sub);
                badges[i].setSelected(on);
            }
            if (cardNames[i] != null) cardNames[i].setTextColor(on ? g.selectColor : ui.text);
            if (animate && cards[i] != null) {
                float s = on ? 1.025f : 1f;
                cards[i].animate().scaleX(s).scaleY(s)
                        .setDuration(g.spring ? g.durIn : 180)
                        .setInterpolator(interp()).start();
            }
        }
        bigPreview.setBackground(new ThemeMiniPreview(pending, ui.dark));
        if (animate) {
            // 0.9.198：点文字行换图时给一个轻淡入，明确「图跟着这一行变了」
            bigPreview.setAlpha(0.45f);
            bigPreview.animate().alpha(1f).setDuration(g.spring ? g.durIn : 220)
                    .setInterpolator(interp()).start();
        }
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

    /* ---------------- 强调色（0.9.184） ---------------- */

    /** 6 枚通用强调色：在浅/深两套底上都站得住，且不改变各语言的材质与形状。 */
    private static final int[] ACCENTS = {
            0xFF3B6FE0, 0xFF6750A4, 0xFF0F9D8C,
            0xFF4E8F52, 0xFFC2681C, 0xFFC0453C
    };

    private View accentSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionHeader("强调色"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 0.9.196：必须让行填满父宽度，里面的 6 个 weight=1 槽位才会均匀铺开；
        // 否则行按 WRAP_CONTENT 只包住圆点，六点全堆在左半边。
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(0, dp(4), 0, 0);
        int cur = DesignSpec.currentAccent(this);
        // 未自定义时把「当前语言的默认强调色」标出来：取与它最接近的那一枚，
        // 而不是一个都不选（各语言默认色不一定是这 6 枚里的某一个）。
        int sel = cur != 0 ? cur : nearestAccent(ui.accent);
        for (int c : ACCENTS) row.addView(accentDot(c, sel));
        box.addView(row);

        if (cur != 0) {
            TextView reset = ModouUi.tv(this, "恢复默认强调色", 12.5f, ui.sub);
            reset.setIncludeFontPadding(false);
            reset.setPadding(0, dp(10), 0, 0);
            reset.setOnClickListener(v -> {
                DesignSpec.saveAccent(this, 0);
                recreate();
            });
            box.addView(reset);
        }
        return box;
    }

    /** 小卡上的短名：三列并排时 "M3 Expressive" / "iOS 27" 放不下，只留品牌前缀。
     *  完整名字在大预览卡里已经写了，这里不丢信息。 */
    private static String shortLabel(DesignSpec.Lang l) {
        String s = l.label;
        int i = s.indexOf(' ');
        return i > 0 ? s.substring(0, i) : s;
    }

    private static int nearestAccent(int c) {
        int best = ACCENTS[0];
        int bestD = Integer.MAX_VALUE;
        for (int a : ACCENTS) {
            int dr = ((a >> 16) & 0xFF) - ((c >> 16) & 0xFF);
            int dg = ((a >> 8) & 0xFF) - ((c >> 8) & 0xFF);
            int db = (a & 0xFF) - (c & 0xFF);
            int d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = a;
            }
        }
        return best;
    }

    /**
     * 单枚强调色：放进一个 weight=1 的等宽槽里居中，六枚因此均匀铺满整行
     * （0.9.196：原来左聚一堆、右半空荡）。0.9.205 起选中态是「次级文字色细环 +
     * 留白 + 缩小的实心点」，六枚槽位等高，不会因选中而跳动。
     */
    private View accentDot(int color, int cur) {
        FrameLayout slot = new FrameLayout(this);
        slot.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 0.9.205：0.9.204 把 3dp 黑圈换成 20% 淡墨描边，描边压在实心色点边缘
        // 上等于没有 —— 六个点看不出哪个被选中。改成「外环 + 一圈留白 + 缩小的
        // 实心点」：环用色板 ui.sub（中灰、深浅主题都可读），不是刺眼的纯黑框。
        boolean selected = color == cur;
        FrameLayout dotHost = new FrameLayout(this);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(dp(42), dp(42));
        hlp.gravity = Gravity.CENTER;
        dotHost.setLayoutParams(hlp);
        if (selected) {
            android.graphics.drawable.GradientDrawable ring =
                    new android.graphics.drawable.GradientDrawable();
            ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            ring.setColor(0x00000000);
            ring.setStroke(dp(2f), ui.sub);
            dotHost.setBackground(ring);
        }
        View dot = new View(this);
        int size = dp(selected ? 30 : 34);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(size, size);
        dlp.gravity = Gravity.CENTER;
        dot.setLayoutParams(dlp);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        dot.setBackground(gd);
        dot.setContentDescription(selected ? "强调色（已选中）" : "强调色");
        dotHost.addView(dot);
        slot.addView(dotHost);
        ModouMotion.press(dot, 0.9f);
        dot.setOnClickListener(v -> {
            DesignSpec.saveAccent(this, color);
            recreate();          // 立刻按新色重建整页，所见即所得
        });
        return slot;
    }

    /* ---------------- 底部胶囊确认条 ---------------- */

    private View actionBar() {
        if (g.galleryWideAction) return wideActionBar();
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
        // 0.9.198：顶部让出淡出带，渐变尽快转纯底色；上一版强调色圆点会从
        // 按钮下方透出来、和「取消」糊在一起，就是这段半透明区太长导致的。
        wrap.setPadding(side, dp(22), side, bottom);
        wrap.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /**
     * iOS / M3 的底部动作：一个通栏主按钮（不是墨读那种「取消 + 确认」双段胶囊）。
     * iOS = 50dp 系统蓝全圆、17sp Semibold；M3 = 56dp Filled、15sp Medium、下方
     * TextButton 取消。按住时叠一层按压缩放，松手回弹 —— 走规格里的 spring。
     */
    private View wideActionBar() {
        final boolean ios = g.galleryStyle == 1;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        applyBtn = ModouUi.tv(this, g.apply, ios ? 15 : 14, g.primaryBtnText);  // 0.9.204 收字号
        applyBtn.setGravity(Gravity.CENTER);
        applyBtn.setIncludeFontPadding(false);
        applyBtn.setTypeface(ios ? Typeface.DEFAULT_BOLD
                : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        applyBtn.setBackground(new Surface(sp, g, dp(999), g.primaryBtnColor, 0, 0, 0,
                false, false));
        applyBtn.setElevation(dp(0));   // 0.9.204：M3 的 2dp 投影让按钮像浮在页面上
        applyBtn.setOnClickListener(v -> apply());
        applyBtn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.975f).scaleY(0.975f)
                            .setDuration(120).setInterpolator(interp()).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(g.spring ? g.durIn : 160)
                            .setInterpolator(interp()).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));   // 0.9.204 统一收高
        col.addView(applyBtn, blp);

        TextView cancel = ModouUi.tv(this, g.cancel, ios ? 13 : 12.5f, g.cancelText);  // 0.9.204
        cancel.setGravity(Gravity.CENTER);
        cancel.setIncludeFontPadding(false);
        cancel.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        clp.topMargin = dp(2);   // 0.9.204：贴紧按钮，不再像孤立一行
        col.addView(cancel, clp);

        FrameLayout wrap = new FrameLayout(this);
        int side = dp(16), bottom = dp(10) + navBarPx();
        wrap.setPadding(side, 0, side, bottom);
        // 底部渐隐：动作条与滚动内容之间原本是透明的，卡片会从按钮下方的缝隙里透出来，
        // 像布局坏了。加一层「透明 → 页面底」的渐变把两者压成一块完整的底栏。
        android.graphics.drawable.GradientDrawable fade =
                new android.graphics.drawable.GradientDrawable();
        fade.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        fade.setColors(new int[]{sp.bg & 0x00FFFFFF, sp.bg, sp.bg, sp.bg});
        wrap.setBackground(fade);
        wrap.addView(col, new FrameLayout.LayoutParams(
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
            // 0.9.190：系统栏透明（不是填一个近似色）—— 页面是 edge-to-edge 的，
            // 底衬要一直铺到屏幕最顶，状态栏区域才是真沉浸。
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
        /** 0.9.176（A4）：半径可动 —— 选中时做一次 12→16dp 的形状 morph */
        private float radiusPx;
        private final float rimPx, selectStrokePx;
        private final int baseColor, rimColor, selectColor;
        private final boolean glow;
        private boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        /** 0.9.190：描边专用路径（向内缩半个线宽，见 draw()） */
        private final Path strokePath = new Path();

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

        float radiusPx() { return radiusPx; }

        void setRadiusPx(float r) { radiusPx = Math.max(0f, r); invalidateSelf(); }

        private void buildPath(RectF r) {
            buildInto(path, r, radiusPx);
        }

        /**
         * 把同一个形状画进指定 Path。
         *
         * <p>0.9.190：描边必须用「内缩半个线宽 + 圆角同比减小」的路径 —— 直接对着外框
         * 描边时，一半的线宽落在 View 边界之外被裁掉，看起来就是又细又方、像套了个矩形框。
         */
        private void buildInto(Path target, RectF r, float radius) {
            target.reset();
            float half = Math.min(r.width(), r.height()) / 2f;
            if (radius >= half) {
                // 全圆胶囊：用专用路径，避免超椭圆曲线在大半径下把端头画方
                if (sp.squircle) target.set(ModouShape.capsule(r));
                else target.addRoundRect(r, half, half, Path.Direction.CW);
            } else if (sp.squircle) {
                target.set(ModouShape.rect(r, Math.max(0f, radius), false));
            } else {
                target.addRoundRect(r, Math.max(0f, radius), Math.max(0f, radius),
                        Path.Direction.CW);
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
                float w = Math.max(1f, rimPx);
                RectF sr = new RectF(r.left + w / 2f, r.top + w / 2f,
                        r.right - w / 2f, r.bottom - w / 2f);
                buildInto(strokePath, sr, radiusPx - w / 2f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(w);
                p.setColor(rimColor);
                c.drawPath(strokePath, p);
            }
            if (selected && selectStrokePx > 0) {
                float w = Math.max(1f, selectStrokePx);
                RectF sr = new RectF(r.left + w / 2f, r.top + w / 2f,
                        r.right - w / 2f, r.bottom - w / 2f);
                buildInto(strokePath, sr, radiusPx - w / 2f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(w);
                p.setColor(selectColor);
                c.drawPath(strokePath, p);
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
        private int color;
        private final int checkColor;
        private final float stroke;
        private boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Badge(int color, int checkColor, boolean selected, float stroke) {
            this.color = color; this.checkColor = checkColor;
            this.selected = selected; this.stroke = Math.max(1f, stroke);
        }

        /** 0.9.200：选中/未选中的圈色也要跟着换，否则旧选中行会残留蓝圈。 */
        void setColor(int c) {
            if (color == c) return;
            color = c;
            invalidateSelf();
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
