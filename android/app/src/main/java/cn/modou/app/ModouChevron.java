package cn.modou.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 极简向下 Chevron（折线箭头）。
 *
 * <p>以前胶囊里的箭头是文字 {@code "⌄"}：字形跟着字体走，笔画细到几乎看不见，基线还
 * 和中文对不齐，看起来像一个小污点。改为自绘：两段等长直线、圆头圆角，1.5dp 线宽，
 * 折角用 round join 磨圆，颜色由调用方传入（用 70% 黑而不是纯黑，和文字融在一起）。
 */
final class ModouChevron extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float w, h;

    ModouChevron(int color, float widthPx, float heightPx, float strokePx) {
        w = widthPx;
        h = heightPx;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, strokePx));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.centerX();
        float cy = b.centerY();
        // 折线略高于中心：视觉重心在笔画下半，放中心会显得往下坠
        float top = cy - h * 0.42f;
        float bottom = top + h;
        float left = cx - w / 2f;
        float right = cx + w / 2f;
        path.reset();
        path.moveTo(left, top);
        path.lineTo(cx, bottom);
        path.lineTo(right, top);
        c.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(w + paint.getStrokeWidth());
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(h + paint.getStrokeWidth());
    }
}
