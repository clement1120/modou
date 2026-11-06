package cn.modou.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 玻璃容器（0.9.138 重写）——只提供材质，不再抓屏。
 *
 * <p><b>历史</b>：0.9.137 及以前这里是「PixelCopy 抓一张背后画面 → 铺进 ImageView →
 * 挂 RenderEffect 做 GPU 高斯」。模糊本身没问题，但输入是一张静态位图：滚动时只能停稳再抓一次，
 * 于是用户看到的是死色块、周边取色、滑动中不刷新。0.9.138 起全部删除。
 *
 * <p><b>现在</b>：本类只负责把一层 {@link ModouGlass.GlassMaterial}（5~10% 遮罩 + 极细白色
 * 高光描边 + API 33 的 AGSL 折射）垫在宿主内容下面，并把整块按圆角裁好。
 * 真正的「背后内容实时模糊」由独立窗口上的 {@link ModouGlass#attach} 完成 —— 那是系统级能力，
 * 同窗口内的 View 拿不到，靠抓屏也模拟不出来。
 *
 * <p><b>因此调用方分两类</b>：
 * <ul>
 *   <li>挂在独立窗口里的（底部 Dock、底部面板 sheet）：外层窗口已挂系统模糊，这里只补材质；</li>
 *   <li>挂在 Activity 内容树里的覆盖层（级联菜单、右上角圆钮）：没有独立窗口，
 *       按设计只给材质，不给模糊（见 CascadingMenu 头部注释：0.9.52 起为规避
 *       ColorOS / Android 13+ 的 PopupWindow 崩溃，菜单刻意不放进浮动窗口）。</li>
 * </ul>
 *
 * <p>对外 API 与旧版完全一致，{@code sampleAbove / tint / blurDp} 三个旧参数只为兼容保留、
 * 已无含义：不再存在「抓上方一条」和「色调层」。
 */
final class ModouFrost {

    private ModouFrost() {
    }

    /** 兼容旧调用点的常量，已无实际作用 */
    static final float BLUR_DP = 26f;
    static final int TINT_NONE = 0x00FFFFFF;

    static FrostBox wrap(View content, float cornerDp) {
        return wrap(content, cornerDp, false, 0, 0f);
    }

    static FrostBox wrap(View content, float cornerDp, boolean sampleAbove) {
        return wrap(content, cornerDp, sampleAbove, 0, 0f);
    }

    static FrostBox wrap(View content, float cornerDp, boolean sampleAbove,
                         int tint, float blurDp) {
        Context c = content.getContext();
        ViewGroup.LayoutParams lp = content.getLayoutParams();

        // 0.9.145：内容**保留自己的宽高**，不再一律 MATCH_PARENT。
        // 旧写法把内容强制成 MATCH_PARENT，而 FrostBox 自己是 WRAP_CONTENT：FrameLayout 在
        // AT_MOST 下量 MATCH_PARENT 子 View 会直接吃满可用高度 —— 所有 sheet 面板都被撑成
        // 接近整屏（换源最明显）。这里只把宽对齐父容器，高仍由内容自己决定。
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                lp != null ? lp.width : ViewGroup.LayoutParams.MATCH_PARENT,
                lp != null ? lp.height : ViewGroup.LayoutParams.WRAP_CONTENT);
        content.setLayoutParams(clp);

        FrostBox box = new FrostBox(c, cornerDp);
        if (lp != null) box.setLayoutParams(lp);
        box.addView(box.material, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(content, new FrameLayout.LayoutParams(clp));
        return box;
    }

    /**
     * 玻璃容器：自己按圆角裁轮廓，内部「材质层 + 宿主内容」两层。
     *
     * <p>0.9.145：容器尺寸**只由内容决定**（见 {@link #onMeasure}）。旧版把材质层也当成
     * MATCH_PARENT 参与测量，在 WRAP_CONTENT 面板里会一路吃满可用高度 —— 0.9.138~0.9.144
     * 期间所有 sheet 都因此被撑成接近整屏（换源面板首当其冲）。
     */
    static final class FrostBox extends FrameLayout {

        final ModouGlass.GlassMaterial material;
        private final float cornerDp;

        FrostBox(Context c, float cornerDp) {
            super(c);
            this.cornerDp = cornerDp;
            setClipChildren(false);
            setClipToPadding(false);
            material = ModouGlass.material(c, cornerDp);
            // 整块按圆角裁，材质层的高光不会溢出到直角外
            setClipToOutline(true);
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    int w = v.getWidth(), h = v.getHeight();
                    if (w <= 0 || h <= 0) return;
                    float r = cornerDp >= 999f ? Math.min(w, h) * 0.5f
                            : ModouUi.dp(getContext(), cornerDp);
                    o.setRoundRect(0, 0, w, h, r);
                }
            });
        }

        /**
         * 尺寸只由「内容」决定，材质层不参与 —— 它是 MATCH_PARENT 的纯绘制层，
         * 一旦参与测量，在 WRAP_CONTENT 容器里会把整块撑到可用高度（面板变成整屏）。
         * 这里先按内容量出最终尺寸，再把材质层按该尺寸精确铺满。
         */
        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int cw = 0, ch = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child == material) continue;
                measureChildWithMargins(child, wSpec, 0, hSpec, 0);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
                cw = Math.max(cw, child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
                ch = Math.max(ch, child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
            }
            setMeasuredDimension(resolveSize(cw, wSpec), resolveSize(ch, hSpec));
            material.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
        }

        /**
         * 主题切换时刷新材质颜色。
         *
         * <p>旧版这里是「重抓一次背板」——滚动/切页/尺寸变化都要喊它，还有 90ms 去抖、
         * 停稳才抓、切页后补 5 发定时抓图。系统窗口模糊每帧自动更新，以上全部不再需要。
         */
        void refresh() {
            material.refreshTheme();
        }
    }
}
