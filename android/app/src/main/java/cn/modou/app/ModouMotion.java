package cn.modou.app;

import android.content.Context;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** 三套界面共用的运动与触反馈契约；系统关闭动画时所有公共组件立即到位。 */
final class ModouMotion {
    private ModouMotion() {}

    static float scale(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable ignore) {
            return 1f;
        }
    }

    static long duration(Context c, int ms) {
        return Math.round(ms * scale(c));
    }

    static Interpolator standard(Context c) {
        DesignSpec sp = DesignSpec.of(c);
        if (sp.material == DesignSpec.Material.TONAL) {
            return new PathInterpolator(0.2f, 0f, 0f, 1f);
        }
        if (sp.material == DesignSpec.Material.LIQUID) {
            return new PathInterpolator(0.22f, 0.72f, 0.18f, 1f);
        }
        return new android.view.animation.DecelerateInterpolator(1.6f);
    }

    static Interpolator exit(Context c) {
        return new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    }

    /** 页面只进行短距离容器进入；不使用整页闪白式淡入。 */
    static void enter(View v) {
        if (v == null) return;
        v.animate().cancel();
        long d = duration(v.getContext(), DesignSpec.of(v.getContext()).durMed);
        v.setAlpha(1f);
        if (d <= 0) {
            v.setTranslationY(0f);
            return;
        }
        v.setTranslationY(ModouUi.dp(v.getContext(), 8));
        v.animate().translationY(0f).setDuration(d).setInterpolator(standard(v.getContext())).start();
    }

    /** 图标与按钮的物理按压；不改变布局尺寸，也不覆盖页面已有点击逻辑。 */
    static void press(View v) {
        press(v, 0f);
    }

    /**
     * 首屏内容错峰入场（0.9.183）：子块按顺序依次上浮淡入。
     *
     * <p>参考那种「一屏一屏浮上来」的精致感，靠的就是这个 —— 整页一次性出现会显得硬。
     * 只处理前 10 个子块（再多延迟会拖沓）；系统关闭动画时直接返回，一切保持可见。
     * M3 位移小（8dp）节奏快，iOS / 墨读 位移稍大（12dp），与各自的运动语言一致。
     */
    static void stagger(android.view.ViewGroup g) {
        if (g == null) return;
        Context c = g.getContext();
        DesignSpec sp = DesignSpec.of(c);
        long dur = duration(c, sp.durMed);
        if (dur <= 0) return;
        long step = duration(c, sp.material == DesignSpec.Material.TONAL ? 30 : 45);
        float rise = ModouUi.dp(c, sp.material == DesignSpec.Material.TONAL ? 8 : 12);
        int n = Math.min(g.getChildCount(), 10);
        for (int i = 0; i < n; i++) {
            View v = g.getChildAt(i);
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(rise);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * step)
                    .setDuration(dur)
                    .setInterpolator(standard(c))
                    .start();
        }
    }

    static void press(View v, float requestedScale) {
        if (v == null) return;
        final float pressed = requestedScale > 0f ? requestedScale
                : DesignSpec.of(v.getContext()).material == DesignSpec.Material.TONAL ? 0.96f : 0.97f;
        v.setOnTouchListener((view, event) -> {
            if (!view.isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    long d = duration(view.getContext(), 90);
                    if (d == 0) {
                        view.setScaleX(pressed);
                        view.setScaleY(pressed);
                    } else {
                        view.animate().scaleX(pressed).scaleY(pressed).setDuration(d)
                                .setInterpolator(standard(view.getContext())).start();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                case MotionEvent.ACTION_CANCEL:
                    long up = duration(view.getContext(), 150);
                    if (up == 0) {
                        view.setScaleX(1f);
                        view.setScaleY(1f);
                    } else {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(up)
                                .setInterpolator(standard(view.getContext())).start();
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    static void tick(View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }
}
