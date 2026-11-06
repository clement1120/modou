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

    /** 书签刻度（页码），由外部持有同一份 List，改完 invalidate 即可 */
    private List<Integer> ticks;

    private SeekBar.OnSeekBarChangeListener listener;

    public ModouPillSeekBar(Context c) {
        super(c);
        setFocusable(true);
        setClickable(true);
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

        // 轨道：两端全圆。左端会被白球盖住一半，看起来就是从球里延伸出来的一条胶囊
        p.setStyle(Paint.Style.FILL);
        p.setColor(trackBg);
        rf.set(left, cy - rh, right, cy + rh);
        c.drawRoundRect(rf, rh, rh, p);

        // 已读段：终点严格等于滑块中心 tx，圆角与轨道同为 rh；
        // 宽度不足一个高度时收成 宽/2，两端仍是半圆（否则会画成方块或椭圆）。
        float w = tx - left;
        if (w > 0.01f) {
            float rr = Math.min(rh, w / 2f);
            p.setColor(isEnabled() ? trackOn : (trackOn & 0x00FFFFFF) | 0x66000000);
            rf.set(left, cy - rh, tx, cy + rh);
            c.drawRoundRect(rf, rr, rr, p);

            // 极淡的内发光：只画在「球体以内、两端圆头以内」，不会在交界处露出直边
            float gx0 = left + rh * 2f;
            float gx1 = tx - rh * 2f;
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

        // 滑块：中心 = 进度终点。不透明球 + 一圈极淡白光，球边缘加 1dp 淡墨描边，
        // 压在纸色面板上才看得出轮廓（纯白球在全白面板上会糊掉）。
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(tx, cy, ball * 1.9f,
                new int[]{0x4DFFFFFF, 0x1FFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(tx, cy, ball * 1.9f, p);
        p.setShader(null);
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
}
