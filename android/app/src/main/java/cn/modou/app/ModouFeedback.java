package cn.modou.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 应用内底部反馈：不依赖各 ROM 的 Toast 外观，也不会遮断当前操作。 */
final class ModouFeedback {
    private static final Object TAG = new Object();
    private ModouFeedback() {}

    static void show(Context context, String message) {
        show(context, message, false);
    }

    static void showCenter(Context context, String message) {
        show(context, message, true);
    }

    private static void show(Context context, String message, boolean centered) {
        if (message == null || message.trim().isEmpty()) return;
        Activity a = context instanceof Activity ? (Activity) context : ModouApp.resumed();
        if (a == null || a.isFinishing() || a.isDestroyed() || a.getWindow() == null) return;
        View decor = a.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) decor;
        View old = root.findViewWithTag(TAG);
        if (old != null) root.removeView(old);

        DesignSpec sp = DesignSpec.of(a);
        boolean m3 = sp.material == DesignSpec.Material.TONAL;
        boolean ios = sp.material == DesignSpec.Material.LIQUID;
        int fill = m3 ? (sp.dark ? 0xFFE6E0E9 : 0xFF322F35)
                : ios ? (sp.dark ? 0xF22C2C2E : 0xF2FFFFFF)
                : (sp.dark ? 0xF21C1C1E : 0xF7FFFFFF);
        int fg = m3 ? (sp.dark ? 0xFF322F35 : 0xFFF5EFF7) : sp.text;
        TextView t = ModouUi.tv(a, message, m3 ? 14f : 15f, fg);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(3);
        t.setLineSpacing(ModouUi.dp(a, 2), 1.15f);
        t.setPadding(ModouUi.dp(a, m3 ? 20 : 18), ModouUi.dp(a, 13),
                ModouUi.dp(a, m3 ? 20 : 18), ModouUi.dp(a, 13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(ModouUi.dp(a, m3 ? 12 : 22));
        if (!m3) bg.setStroke(Math.max(1, ModouUi.dp(a, 0.5f)), sp.glassRim);
        t.setBackground(bg);
        t.setElevation(ModouUi.dp(a, m3 ? 3 : 8));
        t.setTag(TAG);
        t.setClickable(false);
        t.setFocusable(false);
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                centered ? Gravity.CENTER : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.leftMargin = ModouUi.dp(a, 24);
        lp.rightMargin = ModouUi.dp(a, 24);
        if (!centered) {
            lp.bottomMargin = ModouUi.dp(a, a instanceof NativeReaderActivity ? 92 : 104);
        }
        t.setMaxWidth(Math.max(ModouUi.dp(a, 220),
                a.getResources().getDisplayMetrics().widthPixels - ModouUi.dp(a, 48)));
        root.addView(t, lp);
        t.setAlpha(0f);
        t.setTranslationY(ModouUi.dp(a, centered ? 6 : 12));
        long in = ModouMotion.duration(a, 180);
        if (in == 0) {
            t.setAlpha(1f);
            t.setTranslationY(0f);
        } else {
            t.animate().alpha(1f).translationY(0f).setDuration(in)
                    .setInterpolator(ModouMotion.standard(a)).start();
        }
        t.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
        t.postDelayed(() -> {
            if (t.getParent() != root) return;
            long out = ModouMotion.duration(a, 140);
            if (out == 0) {
                root.removeView(t);
            } else {
                t.animate().alpha(0f).translationY(ModouUi.dp(a, 6)).setDuration(out)
                        .setInterpolator(ModouMotion.exit(a))
                        .withEndAction(() -> root.removeView(t)).start();
            }
        }, Math.max(2200, Math.min(4200, message.length() * 115L)));
    }
}
