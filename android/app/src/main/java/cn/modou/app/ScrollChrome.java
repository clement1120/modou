package cn.modou.app;

import android.content.Context;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * B5（0.9.164 · Stage 4 第 2 部分）：可复用的「滚动镀铬」——大标题收起 / 顶栏
 * scroll edge 的联动逻辑集中在这一处。
 *
 * <p><b>为什么要抽出来</b>：0.9.160 起，{@link LargeTitleHeader}、主题画廊、
 * 纸张画廊各写了一份几乎相同的 {@code setOnScrollChangeListener}：按
 * {@code scrollY / 96dp} 算进度 t，iOS 收起大标题 + 浮出 hairline / 底色，
 * M3 抬升 tonal elevation。三份重复既难维护，也让「顶栏随内容滚走」的缺陷
 * 只能一处一处修 —— 0.9.163 修好了独立设置页，画廊页仍留着旧写法。
 * 0.9.164 本轮把三处全部收口到本类，并把两个画廊页的顶栏改为固定覆盖层。
 *
 * <p><b>0.9.165 通用化（Stage 4 第 3 部分 · 书架）</b>：原 {@link #attach(ScrollView)}
 * 只吃得下 ScrollView，而书架三个子视图的滚动容器分别是 RecyclerView（书架分组网格）、
 * ListView（阅读历史）、GridView（收藏），外加空态 TextView 根本没有滚动容器。
 * 本轮把进度计算抽成 {@link #apply(int)}，新增 {@link #attach(View)} 按容器类型分发
 * ——页面只调一次 attach，不写任何 {@code if (tab == ...)}；三个画廊页 / 设置页的
 * {@link #attach(ScrollView)} 调用点一行未改，行为完全一致。
 *
 * <p><b>一改全改</b>：页面只负责把「展开态 / 收起态 / 材质层」视图登记进来，
 * 行程读 {@link DesignSpec.Gallery#scrollEdgeDp}、语言开关读
 * {@code largeTitleScroll} / {@code appBarTonalScroll}，页面不写任何
 * {@code if (lang == ...)}。画廊页的 Gallery 可以是 {@code sp.paperGallery}
 * （继承自 Gallery），字段同源。
 *
 * <p><b>墨读零回归</b>：两个开关都为 false 时 {@link #attach(View)}
 * 直接返回 —— 不注册监听、不动任何 alpha / translationY / elevation。
 */
final class ScrollChrome {

    private final Context c;
    private final DesignSpec.Gallery g;
    private final List<View> expand = new ArrayList<>();
    private final List<View> scrollWith = new ArrayList<>();
    private View bigTitle, inlineTitle, hairline, chrome, tonal, bar;
    /* 0.9.166 书架真·Large Title：内容区大标题块 + 收起态构图开关 */
    private View shelfTitleBlock;
    private boolean shelfFade, shelfInline, shelfHairline;
    /* 0.9.172：行程可覆盖。画廊页的大标题区高度（眉标 + 大标题 + 副标题）与
     * scrollEdgeDp 并不相等，固定用规格值会出现「标题还没淡完就被顶栏切掉」。 */
    private int rangePx;

    private ScrollChrome(Context c, DesignSpec.Gallery g) {
        this.c = c;
        this.g = g;
    }

    static ScrollChrome of(Context c, DesignSpec.Gallery g) {
        return new ScrollChrome(c, g);
    }

    /** 展开态视图：滚动时 alpha 1→0（眉标 / 大标题 / 副标题 / 品牌名）。 */
    ScrollChrome expand(View... vs) {
        if (vs != null) for (View v : vs) if (v != null) expand.add(v);
        return this;
    }

    /** 大标题：在 alpha 之外再多一点上移，做出被顶栏「吸走」的感觉。 */
    ScrollChrome bigTitle(View v) { bigTitle = v; return this; }

    /**
     * 大标题随内容 1:1 上移（0.9.165 书架）：大标题留在固定工具条里、不能真的
     * 随内容滚出视口，用 -min(sy, 行程) 的位移配合 alpha 淡出模拟「被内容顶走」。
     * 与 {@link #bigTitle(View)} 互斥（都写 translationY），书架只登记本项。
     */
    ScrollChrome scrollWith(View... vs) {
        if (vs != null) for (View v : vs) if (v != null) scrollWith.add(v);
        return this;
    }

    /** 收起后顶栏居中的 inline 标题：alpha 0→1。 */
    ScrollChrome inlineTitle(View v) { inlineTitle = v; return this; }

    /** iOS scroll edge：顶栏底部 0.5px 分隔线。 */
    ScrollChrome hairline(View v) { hairline = v; return this; }

    /** 顶栏材质底色：初始 alpha 0，滚动后浮出（iOS 磨砂 / M3 surfaceContainer）。 */
    ScrollChrome chrome(View v) { chrome = v; return this; }

    /** M3：滚动后叠加的 primary tint 层。 */
    ScrollChrome tonal(View v) { tonal = v; return this; }

    /** 顶栏容器：M3 滚动后抬升 elevation。 */
    ScrollChrome bar(View v) { bar = v; return this; }

    /**
     * 0.9.166 书架真·Large Title：登记内容区大标题块与收起态构图。
     *
     * <p>与三个画廊页 / 独立设置页的 {@code largeTitleScroll} 语义<b>解耦</b>：M3 的
     * 全局规格是 {@code largeTitleScroll=false}（已验收页面保持不动），但 M3 Expressive
     * 的 large top app bar 本来就应收起为 small app bar，故书架单独由本方法驱动。
     * 三个布尔全部来自 {@link DesignSpec.Gallery} 规格，页面不写语言判断。
     *
     * <p>大标题块随滚动 1:1 上移（UIKit 的 contentInset 模型），配合内容的顶部留白，
     * 内容会连续上移填满收起后的空间，不会出现「标题淡出留空洞」。
     */
    ScrollChrome shelfTitle(View block, boolean fade, boolean inline, boolean hairline) {
        shelfTitleBlock = block;
        shelfFade = fade;
        shelfInline = inline;
        shelfHairline = hairline;
        return this;
    }

    /** 覆盖收起行程（px）：画廊页按自己的标题区高度给，避免淡出与裁剪错位。 */
    ScrollChrome range(int px) {
        rangePx = px;
        return this;
    }

    /** 兼容 0.9.164 三处调用（独立设置页 + 两个画廊页），内部委托到统一实现。 */
    void attach(ScrollView sc) { attachView(sc); }

    /**
     * 通用入口：按滚动容器类型注册监听，统一把「已滚动的像素」喂给 {@link #apply(int)}。
     * 不认识的容器（例如空态 TextView / LinearLayout）直接忽略，不会抛异常。
     */
    void attach(View scroller) { attachView(scroller); }

    private void attachView(View scroller) {
        // 墨读：两个开关都 false，直接返回，连初始态都不碰（逐像素零回归）
        if (!g.largeTitleScroll && !g.appBarTonalScroll) return;
        // scroller 允许为 null（书架空态是纯 TextView，没有滚动容器）：
        // 不注册监听，但仍要把头部复位到展开态，避免切 tab 时残留收起态。
        if (scroller instanceof ScrollView) {
            ((ScrollView) scroller).setOnScrollChangeListener(
                    (View v, int sx, int sy, int osx, int osy) -> apply(sy));
        } else if (scroller instanceof androidx.recyclerview.widget.RecyclerView) {
            ((androidx.recyclerview.widget.RecyclerView) scroller).addOnScrollListener(
                    new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                        @Override public void onScrolled(
                                androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                            apply(rv.computeVerticalScrollOffset());
                        }
                    });
        } else if (scroller instanceof AbsListView) {
            ((AbsListView) scroller).setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                    apply(absListOffset(v));
                }
                @Override public void onScrollStateChanged(AbsListView v, int state) { }
            });
        }
        apply(0); // 新容器 / 空态的初始态：展开、无材质、无 hairline
    }

    /**
     * AbsListView（ListView / GridView）没有 public 的垂直滚动偏移，
     * {@code computeVerticalScrollOffset()} 是 protected 不可外部调用。
     * 用「首可见项所在行 × 行高 − 首 child 的 top」估算已滚动像素；GridView 按列数
     * 把 item 位置换算成行。近似值只用于把 scrollEdgeDp 归一化成 0→1 的进度，
     * 不参与布局与滚动位置恢复，误差不影响视觉。
     */
    private static int absListOffset(AbsListView v) {
        View c = v.getChildAt(0);
        if (c == null) return 0;
        int h = c.getHeight();
        if (h <= 0) return 0;
        int cols = 1;
        if (v instanceof android.widget.GridView) {
            int n = ((android.widget.GridView) v).getNumColumns();
            if (n > 1) cols = n;
        }
        int first = v.getFirstVisiblePosition();
        // 0.9.166：书架真·Large Title 给滚动容器加了顶部留白，第一项初始 top = paddingTop。
        // 三项推算（first=0 静止 / first=0 滚动 / first>=1）都会稳定偏 -paddingTop，
        // 补回来才是真实的已滚动像素，否则前留白那一段大标题不动、顶栏不浮出。
        return Math.max(0, (first / cols) * h - c.getTop() + v.getPaddingTop());
    }

    /** 滚动态复位（书架切 tab / paintPane 重建后调用）。 */
    void reset() { apply(0); }

    /** 切 tab 时同步 inline 标题文案（「书架 / 阅读历史 / 收藏」）。 */
    void setInlineTitle(CharSequence s) {
        if (inlineTitle instanceof TextView) ((TextView) inlineTitle).setText(s);
    }

    /** 唯一的进度驱动：syPx 为已滚动距离，t 由规格里的 scrollEdgeDp 归一化。 */
    private void apply(int syPx) {
        final int range = rangePx > 0 ? rangePx : Math.max(1, ModouUi.dp(c, g.scrollEdgeDp));
        float t = Math.min(1f, Math.max(0f, syPx / (float) range));
        if (g.largeTitleScroll) {
            for (View e : expand) e.setAlpha(1f - t);
            if (bigTitle != null) bigTitle.setTranslationY(-ModouUi.dp(c, 12) * t);
            for (View s : scrollWith) s.setTranslationY(-Math.min(syPx, range));
            if (inlineTitle != null) inlineTitle.setAlpha(t);
            if (hairline != null) hairline.setAlpha(t);
            if (chrome != null) chrome.setAlpha(t);
        }
        if (g.appBarTonalScroll) {
            if (tonal != null) tonal.setAlpha(t);
            if (chrome != null) chrome.setAlpha(t);
            if (bar != null) bar.setElevation(ModouUi.dp(c, 3) * t);
        }
        /* 0.9.166 书架真·Large Title：与语言无关的「内容区标题被推走」物理关系。
         * 只有登记过 shelfTitle 的页面执行；三个画廊页 / 独立设置页零回归。
         * iOS 的 largeTitleScroll 分支已经写过 inline / hairline 的 alpha，这里同值
         * 再写一次无害；M3 全局 largeTitleScroll=false，靠这里补上收起态构图。 */
        if (shelfTitleBlock != null) {
            shelfTitleBlock.setTranslationY(-Math.min(syPx, range));
            if (shelfFade) shelfTitleBlock.setAlpha(1f - t);
        }
        if (shelfInline && inlineTitle != null) inlineTitle.setAlpha(t);
        if (shelfHairline && hairline != null) hairline.setAlpha(t);
    }
}
