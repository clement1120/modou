package cn.modou.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 玻璃材质 Drawable —— 对齐 coui17 {@code coui-blur/highlight} 的 BloomStroke 边缘泛光。
 *
 * <p>系统面板的「凝光」不是一个 1dp 灰描边，而是：<b>圆角矩形的 SDF + 半球法线 + 两盏方向光</b>。
 * 顶边法线朝上，被位于上方的主光（y=0.5, 强度 0.3）照亮；底边法线朝下，被位于下方的
 * 副光（y=1.0, 强度 0.16）补亮；左右两侧处在掠射角，几乎不反光。于是描边的亮度沿
 * 垂直方向呈「上亮 → 中间几乎消失 → 底部微亮」。
 *
 * <p>我们用一条垂直 {@link LinearGradient} 做描边，按 y 取色即可得到同样的分布：
 * 水平边取到的是恒定色（顶全亮 / 底全亮），垂直边自然从亮渐变到暗。不需要 shader。
 *
 * <p><b>0.9.138：删掉背板位图</b>。旧版这里能塞一张抓屏模糊后的 Bitmap
 * （{@code setBackdrop}），draw 时先铺位图再压实色。那是一张静态快照，滚动时永远是旧画面。
 * 现在「背后内容」由独立窗口的 {@link ModouGlass#attach} 实时模糊提供，本类只负责
 * 材质本身：半透明基色 → 极淡纯白薄纱 → 边缘泛光 → 入场流光。
 */
final class ModouGlassDrawable extends Drawable {

    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int baseColor;
    private float radiusPx;
    private boolean topOnly;
    /** 只圆下两角：给「上方还要接一块面板」的底栏用（顶边当接缝，必须是直的） */
    private boolean bottomOnly;
    private boolean capsuleBlend = true;

    /** 叠在基色上的一层纯白薄纱（提亮，去掉「灰蒙蒙」） */
    private int veil;
    private LinearGradient rim;
    private float rimWidthPx;
    /** 0.9.152 弹出层专属：宽柔边缘光影总宽（px）。>0 开启，<=0 关闭（退回原细线/无线） */
    private float edgeWidthPx = 0f;
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient glowShader;
    private LinearGradient shadeShader;
    /** 入场流光进度：0→1 由调用方动画驱动；>=0 时绘制一道斜向高光扫过玻璃，<0 关闭 */
    private float sheenProg = -1f;
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    ModouGlassDrawable(int baseColor, float radiusPx, boolean topOnly) {
        this.baseColor = baseColor;
        this.radiusPx = radiusPx;
        this.topOnly = topOnly;
        fill.setStyle(Paint.Style.FILL);
        rimPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStyle(Paint.Style.STROKE);
        shadePaint.setStyle(Paint.Style.STROKE);
        // 内部泛光：描边压在轮廓内侧，避免外扩 1px 显得发虚
        rimPaint.setStrokeWidth(Math.max(1f, radiusPx * 0.02f));
    }

    /** 设成胶囊（短边一半为半径、端头为正圆） */
    ModouGlassDrawable capsule() {
        capsuleBlend = true;
        return this;
    }

    /**
     * 顶边切成直角 / 恢复圆角。
     *
     * <p>听书面板压在底栏上时，底栏顶边必须是直的 —— 两个圆角对不上，接缝处就会露出
     * 两个月牙形的正文。收起听书后恢复四角圆，底栏还是那座浮岛。
     */
    ModouGlassDrawable joinTop(boolean join) {
        if (bottomOnly == join) return this;
        bottomOnly = join;
        if (rect.width() > 0) build();
        invalidateSelf();
        return this;
    }

    void setBaseColor(int c) {
        baseColor = c;
        // 基色决定「深玻璃/浅玻璃」，边光强度跟着走：换色后必须重算渐变
        if (rect.width() > 0) build();
        invalidateSelf();
    }

    /** 白色薄纱：基色上再压一层极低不透明度的纯白，玻璃才是「亮」的而不是「灰」的 */
    void setVeil(int c) {
        veil = c;
        invalidateSelf();
    }

    /** 入场流光：驱动高光扫过进度（0→1）；传 -1 关闭（恢复常态玻璃，无额外绘制） */
    void setSheen(float prog) {
        sheenProg = prog;
        invalidateSelf();
    }

    /**
     * 边缘泛光宽度（px）。coui17 的面板是 innerBlurRadius 3.5dp —— 它的「描边」其实
     * 是一圈向内衰减的光晕，所以这里用 2.5 倍作为可见描边宽度上限，再宽就糊了。
     */
    void setRimWidth(float px) {
        rimWidthPx = px;
        rimPaint.setStrokeWidth(Math.max(1f, px));
        if (rect.width() > 0) build();
        invalidateSelf();
    }

    /**
     * 0.9.152：弹出层玻璃开启「宽柔边缘泛光 + 极淡内阴影」。
     * 边界靠光影立起来，不再靠描边线（白细/白粗/暗线几版全试错）或提实白板（0.9.151 死板）。
     * 只给走 ModouFrost.wrap 的窗口材质层开；圆钮、TTS 条等小组件不调用，保持原样。
     */
    ModouGlassDrawable setEdgeLight(float widthPx) {
        edgeWidthPx = widthPx;
        if (rect.width() > 0) build();
        invalidateSelf();
        return this;
    }

    private void build() {
        path.reset();
        if (topOnly) {
            path.set(ModouShape.rectTop(rect, radiusPx, capsuleBlend));
        } else if (bottomOnly) {
            path.set(ModouShape.rectBottom(rect, radiusPx, capsuleBlend));
        } else {
            path.set(ModouShape.rect(rect, radiusPx, capsuleBlend));
        }
        buildRim();
        buildEdgeShaders();
    }

    /** 边缘光影的两条垂直渐变：光「上亮下收」，影「只压在下缘」。 */
    private void buildEdgeShaders() {
        if (edgeWidthPx <= 0f || rect.height() <= 0f) {
            glowShader = null;
            shadeShader = null;
            return;
        }
        glowShader = new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{0x80FFFFFF, 0x44FFFFFF, 0x1CFFFFFF, 0x0CFFFFFF},
                new float[]{0f, 0.18f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        shadeShader = new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{0x00000000, 0x00000000, 0x08000000, 0x14000000},
                new float[]{0f, 0.45f, 0.80f, 1f}, Shader.TileMode.CLAMP);
    }

    private void buildRim() {
        if (rect.height() <= 0) {
            rim = null;
            return;
        }
        final boolean darkGlass = luminance(baseColor) < 96;
        // 0.9.152：细线描边整体作废。几版实拍已把所有走法试错：
        //   白细线（0.9.147 前）白对白看不见 → 边界糊；
        //   白粗线（0.9.148）→ 生硬硬框；
        //   暗细线（0.9.150）→ 实拍出现黑线；
        //   提实白板（0.9.151）→ 死板发闷，边界照样糊。
        // 根因不是线的颜色/粗细，而是想在玻璃上靠「一根线」或「一块白底」撑边界。
        // 放大核对系统菜单后确认：它们是半透明磨砂玻璃，边界靠「宽柔白色边缘泛光 + 极淡投影」。
        // 故浅色玻璃这里不画细线；弹出层（开了 setEdgeLight）由 drawEdge() 画宽柔光影，
        // 窗口外投影由 Theme.Modou.Glass 的 windowElevation 承担。
        // 深色玻璃保留柔和白细线，仅用于**未开 edgeLight** 的小组件（深底上白边才可见）。
        if (!darkGlass) {
            rim = null;
            return;
        }
        int top = 0x30FFFFFF;      // 19%：受光边
        int upper = 0x1EFFFFFF;    // 12%
        int mid = 0x16FFFFFF;      // 9%
        int bottom = 0x16FFFFFF;   // 9%
        rim = new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{top, upper, mid, bottom},
                new float[]{0f, 0.16f, 0.55f, 1f},
                Shader.TileMode.CLAMP);
    }

    /** 基色的感知亮度（忽略 alpha）：只用来判断「这块玻璃是深是浅」 */
    private static int luminance(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        rect.set(b);
        build();
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.width() <= 0 || rect.height() <= 0) return;

        int save = canvas.save();
        canvas.clipPath(path);

        // 尊重传入的 alpha：以前这里强制提到 0xF2（95%），结果「半透明玻璃」被自己
        // 涂成实底，底下的背景一点也透不上来。可读性由调用方选基色时保证。
        fill.setColor(baseColor);
        canvas.drawRect(rect, fill);
        // 再叠一层纯白薄纱。只靠基色提亮会把整块玻璃推成「半实心白」，
        // 薄纱只抬亮度、几乎不挡背后的颜色与轮廓。
        if ((veil >>> 24) != 0) {
            fill.setColor(veil);
            canvas.drawRect(rect, fill);
        }

        // 入场流光：一道斜向高光随面板升起扫过（仅 entrance 动画期间由 setSheen 驱动，
        // 已裁剪在圆角内，不会溢出直角）。斜 16° 让光带更像「凝光」而非生硬横条。
        if (sheenProg >= 0f && rect.width() > 0f) {
            float w = rect.width();
            float h = rect.height();
            float bw = w * 0.62f;                       // 光带宽度（屏宽占比）
            float x = -bw + sheenProg * (w + 2f * bw);  // 从左侧外扫到右侧外
            canvas.save();
            canvas.rotate(-16f, rect.centerX(), rect.centerY());
            sheenPaint.setShader(new LinearGradient(x - bw / 2f, 0f, x + bw / 2f, 0f,
                    new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            sheenPaint.setAlpha(255);
            canvas.drawRect(x - bw / 2f, rect.top - h, x + bw / 2f, rect.bottom + h, sheenPaint);
            canvas.restore();
        }

        canvas.restoreToCount(save);

        if (edgeWidthPx > 0f) {
            // 0.9.152 弹出层：边界交给光影，不再画那根细线
            drawEdge(canvas);
        } else if (rim != null) {
            // 泛光必须裁在轮廓**内侧**：drawPath 的描边是以路径为中线向两侧各扩
            // strokeWidth/2，2.5dp 的边就会在面板外面多出一圈 1.25dp 的亮线。
            int rimSave = canvas.save();
            canvas.clipPath(path);
            rimPaint.setShader(rim);
            canvas.drawPath(path, rimPaint);
            canvas.restoreToCount(rimSave);
        }
    }

    /**
     * 边界 = 光 + 影，不是一根线。
     *
     * <p>clip 在圆角轮廓内侧后，STROKE 只有 strokeWidth/2 可见。画 4 层由宽到窄的白色
     * 内描边，层层叠加自然形成「越靠边越亮、向内约 3.5dp 衰减」的宽柔光晕；最亮处也只是
     * 几层低 alpha 的叠加（上缘峰值约 40% 白），不会变成 0.9.148 那种硬白线。
     *
     * <p>再在下缘补 2 层极淡暗内阴影（实际约 5% 黑、宽且向内衰减），把卡片从背景「托」
     * 起来，与窗口 elevation 的外投影一起给浮起感 —— 绝不是 0.9.150 那根 1px 黑线。
     */
    private void drawEdge(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(path);

        glowPaint.setShader(glowShader);
        for (int i = 0; i < 4; i++) {
            float w = edgeWidthPx * (1f - 0.70f * (i / 3f));   // 7dp → 2.1dp
            glowPaint.setStrokeWidth(Math.max(1f, w));
            glowPaint.setAlpha(56);                             // 22% × 渐变；4 层叠加
            canvas.drawPath(path, glowPaint);
        }

        shadePaint.setShader(shadeShader);
        for (int i = 0; i < 2; i++) {
            float w = edgeWidthPx * (0.62f - 0.22f * i);
            shadePaint.setStrokeWidth(Math.max(1f, w));
            shadePaint.setAlpha(90);                            // 35% × 渐变；下缘实际 ~5% 黑
            canvas.drawPath(path, shadePaint);
        }
        canvas.restoreToCount(save);
    }

    /**
     * 投影轮廓。不给轮廓时，{@code setElevation} 会按<b>外接矩形</b>投一圈方影 ——
     * 胶囊两端就会出现两道直边灰影，看着就是「底栏下面有条线」。给上真实轮廓后，
     * 阴影才跟着圆角走。
     */
    @Override
    public void getOutline(android.graphics.Outline outline) {
        if (rect.width() <= 0 || rect.height() <= 0) return;
        // 0.9.138：minSdk 31，Outline.setPath 恒可用；保留 try/catch 兜个别 ROM 拒绝非标路径。
        try {
            outline.setPath(path);
            return;
        } catch (Throwable ignore) {
            /* 个别 ROM 不接受非标准路径：走下面的圆角兜底 */
        }
        // 直角底（接缝用）在兜底路径上也不能给圆角轮廓：一圆回来，接缝就又裂开了
        if (topOnly || bottomOnly) {
            outline.setRect(new Rect((int) rect.left, (int) rect.top,
                    (int) Math.ceil(rect.right), (int) Math.ceil(rect.bottom)));
            return;
        }
        outline.setRoundRect(new Rect((int) rect.left, (int) rect.top,
                        (int) Math.ceil(rect.right), (int) Math.ceil(rect.bottom)),
                Math.min(radiusPx, Math.min(rect.width(), rect.height()) * 0.5f));
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        rimPaint.setAlpha(alpha);
        glowPaint.setAlpha(alpha);
        shadePaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        rimPaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return (baseColor >>> 24) == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }
}
