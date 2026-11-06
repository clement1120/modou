package cn.modou.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 封面的斜掠高光（sheen）。
 *
 * <p>真机上 ColorOS 的封面不是「一张平贴的图」：它有一道从左上斜掠到右下的淡光，
 * 强度只有 8%~22%，但只要在，封面就「立」起来了 —— 因为它给这张图指定了一个光源。
 * 这不是装饰，是把「平涂 vs 有光」的差别搬到封面这一层。
 *
 * <p>实现用一个 122° 的 {@link LinearGradient}：起手 22% 白 → 20% 处衰减到 3%
 * → 中段完全透明（让封面本来的颜色透出来）→ 尾部 6% 白再收掉。两段式的
 * 「中间亮两头空」是刻意为之：单向渐变会让封面一半发白、一半死黑，像贴了张膜。
 *
 * <p>成本：一个 rect + 一个渐变 shader，几何在 {@link #onBoundsChange} 里算好，
 * 滚动时不重建，几十张封面同时在场也不掉帧。
 */
final class ModouSheen extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect rect = new Rect();
    private LinearGradient shader;

    /** 高光强度倍率：1 = 默认，深色主题下调低（浅色字+强光会发灰） */
    private float strength = 1f;

    ModouSheen() {
    }

    ModouSheen(float strength) {
        this.strength = strength;
    }

    @Override
    protected void onBoundsChange(Rect r) {
        rect.set(r);
        if (r.width() <= 0 || r.height() <= 0) return;
        // 斜掠方向：横向推进 0.85 倍宽度，纵向到底 —— 视觉上约 122°
        shader = new LinearGradient(r.left, r.top, r.left + r.width() * 0.85f, r.bottom,
                new int[]{
                        scale(0x44), // 27%：起手高光
                        scale(0x08), // 3%：迅速衰减
                        scale(0x00), // 中段留空，封面本色透出
                        scale(0x0E), // 6%：尾部再收一道
                        scale(0x00),
                },
                new float[]{0f, 0.20f, 0.38f, 0.80f, 0.94f},
                Shader.TileMode.CLAMP);
        paint.setShader(shader);
    }

    private int scale(int alpha) {
        int a = (int) (((alpha >>> 24) & 0xFF) * strength);
        return (a << 24) | 0x00FFFFFF;
    }

    @Override
    public void draw(Canvas canvas) {
        if (shader == null) return;
        canvas.drawRect(rect, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
