package cn.modou.app;

import android.app.Activity;
import android.content.Intent;

/**
 * 三套设计语言各自的「页面转场」。
 *
 * <p>0.9.181 之前全工程一次 {@code overridePendingTransition} 都没有 —— 所有页面切换
 * 都吃系统默认的淡入淡出，于是 M3 / iOS / 墨读 只是换了颜色和图标，**进出动作完全一样**，
 * 看起来就像同一套 UI 改了个皮。台账 A3/B9 里「push/pop 转场」一直挂着「待 Stage 5」，
 * 而 Stage 5 只做了阅读页内部，这里补上。
 *
 * <p>三套语言的运动语言刻意做成三种：
 * <ul>
 *   <li><b>M3 Expressive</b>：shared axis Z —— 新页 92% 放大淡入、旧页淡出微胀，
 *       前后是「纵深」关系，不做左右滑动；</li>
 *   <li><b>iOS 27</b>：UINavigationController push —— 新页整幅从右推入，
 *       旧页左移 28% 并压暗（视差），返回整幅滑出；</li>
 *   <li><b>墨读</b>：纸页上浮 —— 极短纵向位移 + 渐显，不滑动、不缩放，
 *       和另外两套在「有没有方向性」这一层就分开。</li>
 * </ul>
 *
 * <p>系统关闭动画（开发者选项「动画缩放 0」）时一律走 {@code (0,0)}，不做半截动画。
 */
final class ModouTransition {
    private ModouTransition() {}

    /** 前进：启动一个页面，并按当前设计语言套转场 */
    static void push(Activity from, Intent intent) {
        if (from == null || intent == null) return;
        from.startActivity(intent);
        forward(from);
    }

    /** 只补转场，不启动页面（给已经自己 startActivity 的调用点用） */
    static void forward(Activity a) {
        if (a == null) return;
        int[] p = pair(a, true);
        try {
            a.overridePendingTransition(p[0], p[1]);
        } catch (Throwable ignore) {
            /* 页面已 finish / 系统不支持：忽略，退回默认 */
        }
    }

    /** 返回：必须在 {@code super.finish()} 之后调用 */
    static void back(Activity a) {
        if (a == null) return;
        int[] p = pair(a, false);
        try {
            a.overridePendingTransition(p[0], p[1]);
        } catch (Throwable ignore) {
            /* 同上 */
        }
    }

    /** 页面统一出口：{@code finish()} + 返回转场。子类 finish() 里调用即可。 */
    static void finish(Activity a) {
        if (a == null) return;
        a.finish();
        back(a);
    }

    private static int[] pair(Activity a, boolean forward) {
        // 系统动画被关掉时不做动画（否则会出现「滑一半停住」）
        if (ModouMotion.scale(a) <= 0f) return new int[]{0, 0};
        switch (DesignSpec.current(a)) {
            case MATERIAL:
                return forward
                        ? new int[]{R.anim.tr_m3_in, R.anim.tr_m3_out}
                        : new int[]{R.anim.tr_m3_back_in, R.anim.tr_m3_back_out};
            case MONOLITH:
                return forward
                        ? new int[]{R.anim.tr_ios_in, R.anim.tr_ios_out}
                        : new int[]{R.anim.tr_ios_back_in, R.anim.tr_ios_back_out};
            case MODOU:
            default:
                return forward
                        ? new int[]{R.anim.tr_ink_in, R.anim.tr_ink_out}
                        : new int[]{R.anim.tr_ink_back_in, R.anim.tr_ink_back_out};
        }
    }
}
