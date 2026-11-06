package cn.modou.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * B5（0.9.163 · Stage 4）：可复用的 iOS 26 Large Title / M3 app bar 头部。
 *
 * <p><b>为什么要抽出来</b>：0.9.160 主题画廊里已验证过「大标题随滚动收起」的观感，
 * 但那一版的顶栏是 ScrollView 内容里的第一行 —— 滚动时顶栏会跟着一起滚出视口，
 * inline 标题与 hairline 刚淡入就消失了。本组件把顶栏改成<b>固定覆盖层</b>，
 * 大标题留在内容里，滚动联动才真正等价于系统 UINavigationBar 的 scroll edge。
 *
 * <p><b>一改全改</b>：视觉规格全部读 {@link DesignSpec.Gallery}（largeTitleScroll /
 * appBarTonalScroll / durIn）、{@link DesignSpec} 色板与 15 档字阶（A8）；
 * 动效走全局 {@code DesignSpec.interpolator()}。页面只传文案与内容体，不写任何
 * {@code if (lang == ...)}。墨读（两个滚动开关都 false）不进入本组件，零回归。
 *
 * <p><b>当前接入</b>：{@link SettingsPanel} 的独立 Activity 形态（view(false)）。
 * 0.9.164 起滚动联动已收口到 {@link ScrollChrome}，与两个画廊页共用一份。
 * 书架头部因 4 tab 共用 headBox + 各 tab 滚动容器各异 + 横屏三套布局，且要做
 * 真正的 iOS Large Title 需把大标题移进内容区（版式重构），留单独一轮接，
 * 不跨阶段糊改回归基准核心。
 */
final class LargeTitleHeader {

    private final Activity a;
    private final DesignSpec sp;
    private final DesignSpec.Gallery g;

    private final FrameLayout root;
    private final ScrollView sc;
    private final LinearLayout content;
    private final LinearLayout body;
    private final FrameLayout topBar;
    private final TextView eyebrowView, bigTitleView, subtitleView, inlineTitle;
    private final View chrome, tonal, hairline;

    static LargeTitleHeader create(Activity a, String eyebrow, String bigTitle,
                                   String subtitle, boolean showBack) {
        return new LargeTitleHeader(a, eyebrow, bigTitle, subtitle, showBack);
    }

    /** 调用方往这个容器里加页面内容（可反复 removeAllViews 重绘）。 */
    LinearLayout body() { return body; }

    View root() { return root; }

    private LargeTitleHeader(Activity a, String eyebrow, String bigTitle,
                             String subtitle, boolean showBack) {
        this.a = a;
        this.sp = ModouUi.of(a).spec;
        this.g = sp.gallery;
        ModouUi ui = ModouUi.of(a);
        int status = statusBarPx();

        root = new FrameLayout(a);
        root.setBackgroundColor(sp.bg);

        /* ---------- 内容层（可滚动） ---------- */
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = ModouUi.dp(a, sp.topInset);
        content.setPadding(pad, 0, pad, ModouUi.dp(a, 48));

        sc = new ScrollView(a);
        sc.setVerticalScrollBarEnabled(false);
        sc.setFillViewport(false);
        sc.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 让出固定顶栏的高度，内容才不会被压在下面
        View spacer = new View(a);
        content.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, status + ModouUi.dp(a, 56)));

        eyebrowView = ModouUi.tv(a, eyebrow, 12.5f, ModouUi.mix(sp.text, ui.sub, 0.40f));
        eyebrowView.setLetterSpacing(0.10f);
        eyebrowView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = ModouUi.dp(a, 8);
        content.addView(eyebrowView, elp);

        // A8 字阶：M3 32sp Medium / iOS 34sp Bold Large Title，字重与字距同源
        bigTitleView = ModouUi.pageTitle(a, bigTitle);
        content.addView(bigTitleView);

        subtitleView = ModouUi.tv(a, subtitle, 13f, ui.sub);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = ModouUi.dp(a, 6);
        slp.bottomMargin = ModouUi.dp(a, 22);
        content.addView(subtitleView, slp);

        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------- 固定顶栏（覆盖层，不随内容滚动） ---------- */
        topBar = new FrameLayout(a);
        topBar.setPadding(0, status, 0, 0);

        // 滚动后才浮出的「材质 / 底色」：初始完全透明，才是 iOS 大标题展开的样子
        chrome = new View(a);
        int base = sp.material == DesignSpec.Material.TONAL ? sp.surfaceContainer
                : sp.surface;
        chrome.setBackgroundColor(Color.argb(0xF2, Color.red(base),
                Color.green(base), Color.blue(base)));
        chrome.setAlpha(0f);
        topBar.addView(chrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // M3：滚动后叠一层 primary tint，模拟 app bar 的 tonal elevation
        tonal = new View(a);
        tonal.setBackgroundColor(sp.navSurfaceTint != 0 ? sp.navSurfaceTint
                : Color.argb(0x0D, Color.red(sp.accent), Color.green(sp.accent),
                Color.blue(sp.accent)));
        tonal.setAlpha(0f);
        topBar.addView(tonal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ModouUi.dp(a, 4), 0, ModouUi.dp(a, 4), 0);
        if (showBack) {
            bar.addView(ModouUi.backBtn(a, a::finish), new LinearLayout.LayoutParams(
                    ModouUi.dp(a, 40), ModouUi.dp(a, 40)));
        } else {
            bar.addView(new View(a), new LinearLayout.LayoutParams(
                    ModouUi.dp(a, 40), ModouUi.dp(a, 40)));
        }
        inlineTitle = ModouUi.tv(a, eyebrow, 17f, ui.text);
        inlineTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        inlineTitle.setGravity(Gravity.CENTER);
        inlineTitle.setAlpha(0f);
        bar.addView(inlineTitle, new LinearLayout.LayoutParams(
                0, ModouUi.dp(a, 48), 1f));
        // 右侧占位，保证 inline 标题真正居中（与左返回钮对称）
        bar.addView(new View(a), new LinearLayout.LayoutParams(
                ModouUi.dp(a, 40), ModouUi.dp(a, 40)));
        topBar.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(a, 48)));

        // iOS：滚到内容区后顶栏出现 0.5px hairline（scroll edge）
        hairline = new View(a);
        hairline.setBackgroundColor(sp.line);
        hairline.setAlpha(0f);
        topBar.addView(hairline, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ModouUi.dp(a, 0.5f)),
                Gravity.BOTTOM));

        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, status + ModouUi.dp(a, 48),
                Gravity.TOP));

        /* ---------- 滚动联动（0.9.164 收口到 ScrollChrome，三处一改全改） ---------- */
        ScrollChrome.of(a, g)
                .expand(eyebrowView, bigTitleView, subtitleView)
                .bigTitle(bigTitleView)
                .inlineTitle(inlineTitle)
                .hairline(hairline)
                .chrome(chrome)
                .tonal(tonal)
                .bar(topBar)
                .attach(sc);

        /* ---------- 入场 ---------- */
        content.setAlpha(0f);
        content.setTranslationY(ModouUi.dp(a, 18));
        content.animate().alpha(1f).translationY(0f)
                .setDuration(g.durIn)
                .setInterpolator(DesignSpec.interpolator(sp, true, 1.6f))
                .start();
    }

    private int statusBarPx() {
        int id = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? a.getResources().getDimensionPixelSize(id) : ModouUi.dp(a, 24);
    }
}
