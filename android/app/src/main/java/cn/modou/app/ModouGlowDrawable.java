package cn.modou.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 菜单景深用的柔光晕：在圆角矩形轮廓外侧叠几遍递减透明度的描边，近似外发光。
 *
 * <p>不引入模糊库（RenderScript 已弃用、第三方库增加体积），用「同路径多遍描边」近似：
 * 越外圈描边越宽、越淡，叠出一圈柔和的 bloom。调用方把它垫在面板背后、尺寸略大于面板，
 * 露出四周光晕，让菜单像浮在正文之上（ColorOS 的「凝光」观感）。
 *
 * <p><b>为什么以前深色下像「一圈圈亮框」</b>：旧实现只有 6 遍描边、每遍步进 0.85×半径，
 * 且每遍都带 0x24 的实心 alpha —— 六条边彼此分离、亮度又都不低。浅色主题下白光照在
 * 米色纸面上几乎看不出来；深色主题下就是六个同心圆角矩形，一圈套一圈。
 * 现在改成 12 遍细步进 + 二次衰减，视觉上是连续的渐隐，任何底色都不会再看出"层"。
 */
final class ModouGlowDrawable extends Drawable {

    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;
    private final float radiusPx;
    /** 整体强度：1 = 浅色主题的白光晕；深色主题传 0.35 左右，只留一点"气" */
    private final float strength;

    ModouGlowDrawable(int color, float radiusPx) {
        this(color, radiusPx, 1f);
    }

    ModouGlowDrawable(int color, float radiusPx, float strength) {
        this.color = color;
        this.radiusPx = radiusPx;
        this.strength = Math.max(0f, Math.min(2f, strength));
        p.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        rect.set(b);
        path.set(ModouShape.rect(rect, radiusPx, true));
    }

    @Override
    public void draw(Canvas c) {
        if (rect.width() <= 0 || rect.height() <= 0 || strength <= 0f) return;
        // 由内到外 12 遍：步进只有 0.5×半径，相邻两遍的重叠区足以把边缘抹平，
        // 不会像 6 遍那样一条一条分得开。alpha 用二次衰减，越外越淡得干脆。
        final int layers = 12;
        final float base = 0x20 * strength;
        final float maxW = radiusPx * 6f;
        for (int i = 0; i < layers; i++) {
            float f = i / (float) (layers - 1);              // 0 = 贴边，1 = 最外
            p.setStrokeWidth(radiusPx * 0.5f + (maxW - radiusPx * 0.5f) * f);
            float k = 1f - f;
            p.setColor(withAlpha(color, (int) (base * k * k)));
            c.drawPath(path, p);
        }
    }

    private static int withAlpha(int color, int a) {
        a = Math.max(0, Math.min(255, a));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
