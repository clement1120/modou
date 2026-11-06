package cn.modou.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 页面底衬 —— ColorOS 17「流体」观感里那层<b>光</b>。
 *
 * <p>以前整屏就是一个纯色 {@code setBackgroundColor(ui.bg)}：颜色再准，画面也是平的，
 * 因为「平涂」不携带任何光照信息。真机的 ColorOS 界面之所以看起来是「有厚度的」，
 * 是因为底色本身由两件事叠出来：
 * <ol>
 *   <li>一条<b>斜向渐变</b>：不是均色，左上偏冷亮、右下沉一点，等于给出一个光源方向；</li>
 *   <li>几团<b>大半径彩色光斑</b>：青、紫、暖橙、青绿，中心落在屏幕外，只有边缘扫进来，
 *       于是屏幕上出现「这块偏蓝、那块偏紫」的缓慢色偏 —— 这才是最像「流体」的部分。</li>
 * </ol>
 *
 * <p>实现上刻意避开背板模糊（那要抓图 + 降采样，代价高且对 WebView 层不可靠）：
 * 这里只用一次 {@link LinearGradient} 加 N 个 {@link RadialGradient}，每帧 5 次
 * {@code drawRect/drawCircle}，几何在 {@link #onBoundsChange} 里一次算好并缓存，
 * 滚动时不重建 shader。这是纯 Java 自绘能做到、且代价可忽略的等效手法。
 *
 * <p>光斑位置用<b>相对比例</b>声明（0~1 的 cx/cy/r），因此任何屏幕尺寸、横竖屏
 * 都能得到同一构图，不会在小屏上挤成一块、在大屏上漏出空档。
 */
final class ModouBackdrop extends Drawable {

    /** 一团光：颜色、圆心（相对宽高的比例，可超出 0~1）、半径（相对宽度） */
    private static final class Glow {
        final int color;
        final float cx, cy, r;

        Glow(int color, float cx, float cy, float r) {
            this.color = color;
            this.cx = cx;
            this.cy = cy;
            this.r = r;
        }
    }

    /* 浅色：磨砂灰白底 + 极弱白光。
       刻意去掉青/紫/橙的彩色光斑 —— 真机上一屏大面积渐变会变成「一块蓝紫」，和
       白色卡片割裂；ColorOS 17 的设置/便签底子其实几乎是中性灰白，只有上沿略亮。 */
    /* 顶部那层「蓝白渐变」已经去掉：它和下面的灰底不是同一个色相，
       一屏看过去页面上半截发蓝、下半截发灰，纯净度全丢。现在整页是一个中性灰。 */
    private static final int[] LIGHT_BASE = {0xFFF2F3F6, 0xFFF2F3F6, 0xFFF1F2F5, 0xFFF1F2F5};
    private static final Glow[] LIGHT_GLOWS = {};

    /* 深色：纯黑到底，不加任何彩色光斑。
       ColorOS 17 的夜间模式是「真黑底 + 深灰卡片」，所有层次都由卡片给出；
       原来的蓝/紫/青三团光斑会把黑底抬成发蓝的灰，卡片就浮不起来了。 */
    private static final int[] DARK_BASE = {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000};
    private static final Glow[] DARK_GLOWS = {};

    private final Paint basePaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] baseColors;
    private final Glow[] glows;
    /** 与 glows 一一对应的 shader，随 bounds 重建 */
    private final Shader[] shaders;
    private final float[] cxs, cys, rs;

    private ModouBackdrop(int[] baseColors, Glow[] glows) {
        this.baseColors = baseColors;
        this.glows = glows;
        this.shaders = new Shader[glows.length];
        this.cxs = new float[glows.length];
        this.cys = new float[glows.length];
        this.rs = new float[glows.length];
        glowPaint.setStyle(Paint.Style.FILL);
    }

    static ModouBackdrop light() {
        return new ModouBackdrop(LIGHT_BASE, LIGHT_GLOWS);
    }

    static ModouBackdrop dark() {
        return new ModouBackdrop(DARK_BASE, DARK_GLOWS);
    }

    /** 按当前外观取对应的一套光 */
    static ModouBackdrop of(android.content.Context c) {
        return ModouUi.isDark(c) ? dark() : light();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        // 斜向渐变：左上冷亮 → 右下微暗，给出光源方向（角度固定，随尺寸拉伸即可）
        basePaint.setShader(new LinearGradient(
                b.left, b.top, b.left + w * 0.72f, b.bottom,
                baseColors, new float[]{0f, 0.38f, 0.72f, 1f},
                Shader.TileMode.CLAMP));

        for (int i = 0; i < glows.length; i++) {
            Glow g = glows[i];
            float cx = b.left + w * g.cx;
            float cy = b.top + h * g.cy;
            float r = Math.max(w, h) * g.r * 0.5f; // 半径按长边折算，横竖屏观感一致
            cxs[i] = cx;
            cys[i] = cy;
            rs[i] = r;
            int solid = g.color;
            int clear = g.color & 0x00FFFFFF;
            // 三段衰减：核心 → 40% 处仍有六成 → 边缘归零。两段会让边界看出「圈」。
            int mid = blendAlpha(solid, 0.42f);
            shaders[i] = new RadialGradient(cx, cy, r,
                    new int[]{solid, mid, clear},
                    new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
        }
    }

    private static int blendAlpha(int color, float k) {
        int a = (int) (((color >>> 24) & 0xFF) * k);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;

        if (basePaint.getShader() == null) onBoundsChange(b);
        canvas.drawRect(b, basePaint);

        for (int i = 0; i < shaders.length; i++) {
            if (shaders[i] == null) continue;
            glowPaint.setShader(shaders[i]);
            canvas.drawCircle(cxs[i], cys[i], rs[i], glowPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        basePaint.setAlpha(alpha);
        glowPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        basePaint.setColorFilter(cf);
        glowPaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        // 底色是不透明实色，光斑只是叠在它上面 —— 对系统如实报 OPAQUE，省一层混合
        return PixelFormat.OPAQUE;
    }
}
