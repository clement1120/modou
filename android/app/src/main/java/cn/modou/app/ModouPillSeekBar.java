package cn.modou.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * 药丸进度条：轨道、已读段、滑块三者在同一套坐标里算，杜绝「进度条末端与白球错位」。
 *
 * <p>为什么不用系统 {@link SeekBar}：SeekBar 的 progressDrawable 由 level 换算宽度，
 * 而 thumb 的中心是另一套公式（还要叠加 thumbOffset），两者在小比例时能差出 6dp ——
 * 红条的 4dp 圆头露在白球左边之外，看起来就像被人用刀切了一刀（「圆柱截面」）。
 *
 * <p>这里改成自绘：滑块中心的 x 就是进度段的终点 x，白球（半径 8dp）永远盖住红条
 * 末端（半径 4dp），交界处必然平滑；轨道与红条都用 rh = 高度/2 的全圆角，
 * 宽度不足一个高度时按 宽度/2 收圆角，两端始终是半圆而不是直角。
 */
public class ModouPillSeekBar extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    private int max = 100;
    private int progress = 0;
    private boolean tracking;

    /** 轨道高（dp）与白球半径（dp）：球必须明显大于轨道半高，才能盖住红条圆头 */
    private float trackH = 8f;
    private float ballR = 8f;

    private int trackBg = 0x1F000000;
    private int trackOn = 0xFFD96C47;
    private int glowColor = 0x40FFFFFF;
    private int tickRing = 0xFFD96C47;
    private int tickFill = 0xFFFFFFFF;
    private int ballFill = 0xFFFFFFFF;
    private int ballRing = 0x14000000;

    /* 0.9.173：三套设计语言（Stage 5）
       墨读 = 8dp 药丸轨 + 内发光 + 白球；iOS = 4dp 细轨 + 无发光圆点；
       M3 = 4dp 细轨 + 滑块两侧留缝 + 两端 2dp 停靠点 + 竖向药丸滑块。 */
    private int style;
    private boolean glow = true;
    private boolean stopDots;

    /** 书签刻度（页码），由外部持有同一份 List，改完 invalidate 即可 */
    private List<Integer> ticks;

    private SeekBar.OnSeekBarChangeListener listener;

    /** 无障碍：朗读用的标签（如「亮度」），以及键盘/无障碍一次调节的步长 */
    private String a11yLabel;
    private int keyStep = 1;

    public ModouPillSeekBar(Context c) {
        super(c);
        setFocusable(true);
        setClickable(true);
        // 规格一次读定：轨道高度 / 滑块半径 / 内发光 / 停靠点全由设计语言决定，
        // 调用点不再传 8 / 8 这种写死值。
        DesignSpec sp = DesignSpec.of(c);
        style = sp.rdSeekStyle;
        trackH = sp.rdSeekTrackDp;
        ballR = sp.rdSeekThumbDp;
        glow = sp.rdSeekGlow;
        stopDots = sp.rdSeekStopDots;
    }

    /* ---------------- 配置 ---------------- */

    public void setPalette(int trackBg, int trackOn, int glowColor,
                           int tickRing, int tickFill, int ballFill, int ballRing) {
        this.trackBg = trackBg;
        this.trackOn = trackOn;
        this.glowColor = glowColor;
        this.tickRing = tickRing;
        this.tickFill = tickFill;
        this.ballFill = ballFill;
        this.ballRing = ballRing;
        invalidate();
    }

    public void setTrackHeightDp(float h) {
        trackH = h;
        invalidate();
    }

    public void setBallRadiusDp(float r) {
        ballR = r;
        invalidate();
    }

    public void setTicks(List<Integer> t) {
        ticks = t;
        invalidate();
    }

    public void setOnSeekBarChangeListener(SeekBar.OnSeekBarChangeListener l) {
        listener = l;
    }

    public void setAccessibilityLabel(String s) {
        a11yLabel = s;
    }

    public void setKeyStep(int s) {
        keyStep = Math.max(1, s);
    }

    private String valueText() {
        String v = max == 100 ? (progress + "%") : (progress + " / " + max);
        return (a11yLabel != null && !a11yLabel.isEmpty()) ? (a11yLabel + " " + v) : v;
    }

    public int getMax() {
        return max;
    }

    public void setMax(int m) {
        max = Math.max(1, m);
        if (progress > max) progress = max;
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int v) {
        int n = Math.max(0, Math.min(max, v));
        if (n == progress) return;
        progress = n;
        invalidate();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
    }

    /* ---------------- 尺寸 ---------------- */

    private float px(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize(getSuggestedMinimumWidth(), wSpec);
        // 高度至少要装下白球和它的光晕，否则球会被上下裁掉
        int want = (int) Math.ceil(px(ballR * 2 + 10));
        int h = resolveSize(Math.max(want, getSuggestedMinimumHeight()), hSpec);
        setMeasuredDimension(w, h);
    }

    /** 轨道左端（也就是进度 0 时滑块中心） */
    private float x0() {
        return getPaddingLeft() + px(ballR);
    }

    /** 轨道右端（进度满时滑块中心） */
    private float x1() {
        return getWidth() - getPaddingRight() - px(ballR);
    }

    /* ---------------- 绘制 ---------------- */

    @Override
    protected void onDraw(Canvas c) {
        float left = x0();
        float right = x1();
        if (right <= left) return;

        float cy = getHeight() / 2f;
        float rh = px(trackH) / 2f;
        float ball = px(ballR);
        float ratio = max <= 0 ? 0f : Math.min(1f, (float) progress / max);
        float tx = left + (right - left) * ratio;

        // M3：轨道在滑块两侧各留 6dp 缝（Expressive slider 的「断轨」）
        float gap = style == 2 ? ball + px(6) : 0f;

        p.setStyle(Paint.Style.FILL);
        p.setColor(trackBg);
        if (style == 2) {
            if (tx - gap > left) {
                rf.set(left, cy - rh, tx - gap, cy + rh);
                c.drawRoundRect(rf, rh, rh, p);
            }
            if (tx + gap < right) {
                rf.set(tx + gap, cy - rh, right, cy + rh);
                c.drawRoundRect(rf, rh, rh, p);
            }
        } else {
            rf.set(left, cy - rh, right, cy + rh);
            c.drawRoundRect(rf, rh, rh, p);
        }

        // M3 停靠点：轨道两端各一枚 2dp 小圆，标记「最小 / 最大」
        if (stopDots) {
            p.setColor(tickRing);
            float d = px(2f);
            c.drawCircle(left, cy, d, p);
            c.drawCircle(right, cy, d, p);
        }

        // 已读段：终点严格等于滑块中心（M3 要再退一个 gap），圆角与轨道同为 rh
        float onEnd = style == 2 ? tx - gap : tx;
        float w = onEnd - left;
        if (w > 0.01f) {
            float rr = Math.min(rh, w / 2f);
            p.setColor(isEnabled() ? trackOn : (trackOn & 0x00FFFFFF) | 0x66000000);
            rf.set(left, cy - rh, onEnd, cy + rh);
            c.drawRoundRect(rf, rr, rr, p);

            // 内发光只有墨读有（它是「药丸」体积感的一部分）；iOS / M3 是平轨
            if (glow) {
                float gx0 = left + rh * 2f;
                float gx1 = onEnd - rh * 2f;
                if (gx1 > gx0 && isEnabled()) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeWidth(rh * 0.44f);
                    p.setColor(glowColor);
                    float gy = cy - rh * 0.48f;
                    c.drawLine(gx0, gy, gx1, gy, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setStrokeCap(Paint.Cap.BUTT);
                }
            }
        }

        // 书签刻度：画在轨道上，压在已读/未读两段都能看清
        if (ticks != null && !ticks.isEmpty()) {
            int total = Math.max(2, max + 1);
            float dot = px(2.4f), ring = px(1f);
            for (int i = 0; i < ticks.size(); i++) {
                float x = left + (right - left) * ((ticks.get(i) + 0.5f) / total);
                p.setColor(tickRing);
                c.drawCircle(x, cy, dot + ring, p);
                p.setColor(tickFill);
                c.drawCircle(x, cy, dot, p);
            }
        }

        p.setStyle(Paint.Style.FILL);
        if (style == 2) {
            // M3 Expressive 的滑块是一根竖向短棒（4×2R），不是球
            float hw = px(2f);
            float hh = ball;
            rf.set(tx - hw, cy - hh, tx + hw, cy + hh);
            p.setColor(ballFill);
            c.drawRoundRect(rf, hw, hw, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, px(0.8f)));
            p.setColor(ballRing);
            c.drawRoundRect(rf, hw, hw, p);
            p.setStyle(Paint.Style.FILL);
            return;
        }

        // 墨读：不透明球 + 一圈极淡白光；iOS：去掉白光，只留 1dp 描边（UISlider 的干净圆点）
        if (glow) {
            p.setShader(new RadialGradient(tx, cy, ball * 1.9f,
                    new int[]{0x4DFFFFFF, 0x1FFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(tx, cy, ball * 1.9f, p);
            p.setShader(null);
        }
        p.setColor(ballFill);
        c.drawCircle(tx, cy, ball - px(1f), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, px(1f)));
        p.setColor(ballRing);
        c.drawCircle(tx, cy, ball - px(1f), p);
        p.setStyle(Paint.Style.FILL);
    }

    /* ---------------- 触摸 ---------------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        float left = x0(), right = x1();
        if (right <= left) return false;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) listener.onStartTrackingTouch(null);
                dropTo(e.getX(), left, right);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracking) dropTo(e.getX(), left, right);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (tracking) {
                    tracking = false;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                    if (listener != null) listener.onStopTrackingTouch(null);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void dropTo(float x, float left, float right) {
        float ratio = (x - left) / (right - left);
        ratio = Math.max(0f, Math.min(1f, ratio));
        int n = Math.round(ratio * max);
        if (n == progress) return;
        progress = n;
        invalidate();
        if (listener != null) listener.onProgressChanged(null, n, true);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SeekBar.class.getName());
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, max, progress));
        String t = valueText();
        info.setText(t);
        info.setContentDescription(t);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        super.onPopulateAccessibilityEvent(event);
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SELECTED
                || type == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
            event.getText().add(valueText());
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
            if (args != null && args.containsKey(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                float val = args.getFloat(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                setProgress(Math.round(val));
                return true;
            }
            return false;
        }
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()) {
            setProgress(progress + keyStep);
            return true;
        }
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId()) {
            setProgress(progress - keyStep);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isEnabled()) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MINUS:
                setProgress(progress - keyStep);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_EQUALS:
                setProgress(progress + keyStep);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
