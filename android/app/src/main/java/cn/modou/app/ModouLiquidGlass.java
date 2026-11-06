package cn.modou.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 底栏「液态玻璃」材质（0.9.139）——照 ColorOS 17 图集底栏的凸透镜质感重做。
 *
 * <p><b>和 0.9.138 的最大区别</b>：那一版的 {@link ModouGlass.GlassMaterial} 是整块
 * 7% 白遮罩<b>平涂</b>，窗口背景又叠 3% 白，两层把系统实时模糊出来的背后画面整体压白，
 * 于是玻璃里什么都没有，看着仍是一块磨砂塑料片。真实玻璃的光学分布是反过来的：
 *
 * <ol>
 *   <li><b>中心几乎全透明</b>——背后的模糊画面（色块、轮廓）要透得出来；</li>
 *   <li><b>四边由外向内快速衰减的一圈白</b>——玻璃的厚度 / 凸透镜边缘；</li>
 *   <li><b>轮廓外两三道递减的白色羽化高光</b>——图2 那条明显的亮厚边，
 *       旧版只有 1px 内侧描边、还被 clipPath 裁掉外扩部分，根本做不出厚度；</li>
 *   <li><b>三团极低不透明度的彩色光晕</b>（粉 / 青 / 暖黄）——模拟内部色散，
 *       但 alpha 只有 8~12%，中心仍是透明的，不会盖住背后画面；</li>
 *   <li><b>顶边一条最亮的受光线 + 底边一条极淡暗边</b>——上下厚度差。</li>
 * </ol>
 *
 * <p>本类只画玻璃自己。背后画面的实时模糊依旧由独立窗口的
 * {@code Window.setBackgroundBlurRadius} 提供，不抓屏、不取色、不读窗口外像素。
 */
final class ModouLiquidGlass extends Drawable {

    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float radiusPx;
    private final float density;
    private boolean dark;

    /** 三段彩色光晕：粉 / 青 / 暖黄。alpha 极低，只做色散暗示，不是一块彩色底 */
    private static final int[] BLOB_LIGHT = {0x1AFF9EC4, 0x1478D7FF, 0x14FFD88A};
    private static final int[] BLOB_DARK = {0x20FF9EC4, 0x1878D7FF, 0x18FFD88A};

    ModouLiquidGlass(float radiusPx, boolean dark, float density) {
        this.radiusPx = radiusPx;
        this.dark = dark;
        this.density = density;
        fill.setStyle(Paint.Style.FILL);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeJoin(Paint.Join.ROUND);
    }

    void setDark(boolean d) {
        if (dark == d) return;
        dark = d;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        rect.set(b);
        // capsuleBlend=true：radiusPx 传的是「短边一半」的近似值（这里传一个很大的值），
        // ModouShape.rect 内部会 clamp 到短边一半并把端头混回正圆，得到标准胶囊。
        path.set(ModouShape.rect(rect, radiusPx, true));
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        float w = rect.width();
        float h = rect.height();

        // ① 轮廓外的羽化高光：三层由外到内递减，制造「厚玻璃」的亮边。
        //    在 clipPath 之前画，stroke 的一半落在轮廓外；外层那半会被 View 的
        //    clipToOutline 裁掉，留下的内侧一半仍约 4dp —— 比旧版 1px 内描边厚得多。
        float[] widths = {9f, 5.5f, 2.5f};
        int[] alphas = {0x10, 0x20, 0x52};
        for (int i = 0; i < widths.length; i++) {
            halo.setStrokeWidth(widths[i] * density);
            halo.setColor((alphas[i] << 24) | 0x00FFFFFF);
            canvas.drawPath(path, halo);
        }

        int save = canvas.save();
        canvas.clipPath(path);

        // ② 四边向内衰减的白：中心保持透明，背后的模糊画面才透得出来。
        float e = Math.min(h * 0.62f, 22f * density);
        int topA = dark ? 0x3CFFFFFF : 0x66FFFFFF;   // 顶边受光最亮
        int botA = dark ? 0x1EFFFFFF : 0x33FFFFFF;
        int sideA = dark ? 0x26FFFFFF : 0x48FFFFFF;
        drawTop(canvas, e, topA);
        drawBottom(canvas, e, botA);
        drawLeft(canvas, e * 1.5f, sideA);
        drawRight(canvas, e * 1.5f, sideA);

        // ③ 彩色光晕：三团大半径、极低 alpha 的径向渐变，模拟玻璃内部色散。
        int[] blobs = dark ? BLOB_DARK : BLOB_LIGHT;
        drawBlob(canvas, rect.left + w * 0.20f, rect.top + h * 0.78f, h * 1.15f, blobs[0]);
        drawBlob(canvas, rect.left + w * 0.52f, rect.top + h * 0.18f, h * 1.05f, blobs[1]);
        drawBlob(canvas, rect.left + w * 0.84f, rect.top + h * 0.72f, h * 1.20f, blobs[2]);

        // ④ 顶边受光亮线 + 底边极淡暗边：上下厚度差
        fill.setShader(null);
        fill.setColor(dark ? 0x30FFFFFF : 0x7AFFFFFF);
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + 1.2f * density, fill);
        fill.setColor(dark ? 0x18000000 : 0x0E000000);
        canvas.drawRect(rect.left, rect.bottom - 1.2f * density, rect.right, rect.bottom, fill);

        canvas.restoreToCount(save);
    }

    private void drawTop(Canvas c, float e, int color) {
        fill.setShader(new LinearGradient(0f, rect.top, 0f, rect.top + e,
                new int[]{color, 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(rect.left, rect.top, rect.right, rect.top + e, fill);
    }

    private void drawBottom(Canvas c, float e, int color) {
        fill.setShader(new LinearGradient(0f, rect.bottom, 0f, rect.bottom - e,
                new int[]{color, 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(rect.left, rect.bottom - e, rect.right, rect.bottom, fill);
    }

    private void drawLeft(Canvas c, float e, int color) {
        fill.setShader(new LinearGradient(rect.left, 0f, rect.left + e, 0f,
                new int[]{color, 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(rect.left, rect.top, rect.left + e, rect.bottom, fill);
    }

    private void drawRight(Canvas c, float e, int color) {
        fill.setShader(new LinearGradient(rect.right, 0f, rect.right - e, 0f,
                new int[]{color, 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(rect.right - e, rect.top, rect.right, rect.bottom, fill);
    }

    private void drawBlob(Canvas c, float cx, float cy, float r, int color) {
        if (r <= 0f) return;
        fill.setShader(new RadialGradient(cx, cy, r,
                new int[]{color, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(rect, fill);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        halo.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        halo.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
