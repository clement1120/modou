package cn.modou.app;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * 连续曲率圆角（squircle）几何 —— 对齐 coui17 / ColorOS 17 的真实曲线。
 *
 * <p>为什么不用 {@code GradientDrawable.setCornerRadius}：那是四分之一圆弧，直线与圆弧的
 * 接点处曲率从 0 突跳到 1/r，肉眼看到的是「直线走到头突然拐弯」；系统面板用的是 hwui 的
 * G2 有符号距离场，即 Lp 超椭圆 {@code |x|^n + |y|^n = R^n}（n = 2.5），曲率沿边连续变化，
 * 所以边角看着是「渐渐弯过去」的。
 *
 * <p>三次贝塞尔无法精确表示 Lp 球，coui17 的做法是让它在 45° 内切点上与目标曲线重合：
 * n = 2.5 要求 {@code h/T = 0.3123}（h 是控制点距角点的距离，T 是角块边长），
 * 即控制柄比 {@code 1 - 0.3123 = 0.6877}。本类沿用同一常量，所以两条曲线的轮廓一致。
 *
 * <p>另一个细节是胶囊：当半径逼近短边一半时，超椭圆端头会变成「胖椭圆」而不是正圆。
 * coui17 用 {@link #CIRCLE_BLEND_START}（π/4）做阈值，超过后把控制柄线性混回圆弧的
 * 0.5523，半径到满就完全是圆——胶囊按钮、进度条端头才不会畸形。
 *
 * <p>全部为 px 单位，调用方自行 dp→px。
 */
final class ModouShape {

    private ModouShape() {
    }

    /** 目标曲线为 n = 2.5 的 Lp 超椭圆：控制柄距角点 0.3123T，故柄比 0.6877。 */
    static final float SQUIRCLE_CONTROL = 0.6877f;

    /** 圆弧的四分之一圆贝塞尔常量（半径到满时混回它）。 */
    private static final float CIRCLE_CONTROL = 0.5522848f;

    /** 角块超过短边一半的 π/4 倍后开始向圆弧过渡。 */
    private static final float CIRCLE_BLEND_START = 0.7853982f;

    /**
     * 角块离「正圆」还有多远：0 = 纯超椭圆，1 = 正圆弧。
     *
     * @param tile    请求的角块边长（**未**按半短边裁剪，裁剪后无法判断是否已到胶囊半径）
     * @param halfMin 矩形短边的一半
     * @param blend   是否允许在胶囊半径处混回圆弧；底部面板这类大圆角传 false，
     *                让曲线一直保持超椭圆（与 coui17 的 28dp 弹层一致）
     */
    static float circleBlendWeight(float tile, float halfMin, boolean blend) {
        if (!blend) return 0f;
        float threshold = halfMin * CIRCLE_BLEND_START;
        float range = halfMin - threshold;
        if (range <= 0f) return 1f;
        float w = (tile - threshold) / range;
        return w < 0f ? 0f : (w > 1f ? 1f : w);
    }

    /**
     * 控制点距角点的距离：tile × (1 − 柄比)。
     * 柄比在 0.6877（超椭圆）与 0.5523（圆弧）之间按 weight 插值。
     */
    static float handle(float tile, float weight) {
        float ratio = SQUIRCLE_CONTROL + (CIRCLE_CONTROL - SQUIRCLE_CONTROL) * weight;
        return tile * (1f - ratio);
    }

    /** 四角同半径。 */
    static Path rect(RectF b, float radiusPx, boolean capsuleBlend) {
        return rect(b, radiusPx, radiusPx, radiusPx, radiusPx, capsuleBlend);
    }

    /**
     * 四角独立半径的 squircle 路径，坐标已含 bounds 偏移。
     *
     * @param tl 左上半径（px）；tr 右上；br 右下；bl 左下
     * @param capsuleBlend 半径接近短边一半时是否混回正圆（胶囊控件传 true）
     */
    static Path rect(RectF b, float tl, float tr, float br, float bl, boolean capsuleBlend) {
        Path path = new Path();
        float w = b.width();
        float h = b.height();
        if (w <= 0f || h <= 0f) return path;

        float halfMin = Math.min(w, h) / 2f;
        // 未裁剪值用来判断「是否已到胶囊半径」，裁剪值决定实际角块大小
        float reqTl = Math.max(0f, tl), reqTr = Math.max(0f, tr);
        float reqBr = Math.max(0f, br), reqBl = Math.max(0f, bl);
        float cTl = Math.min(reqTl, halfMin), cTr = Math.min(reqTr, halfMin);
        float cBr = Math.min(reqBr, halfMin), cBl = Math.min(reqBl, halfMin);

        if (cTl <= 0f && cTr <= 0f && cBr <= 0f && cBl <= 0f) {
            path.addRect(b.left, b.top, b.right, b.bottom, Path.Direction.CW);
            return path;
        }

        float hTl = handle(cTl, circleBlendWeight(reqTl, halfMin, capsuleBlend));
        float hTr = handle(cTr, circleBlendWeight(reqTr, halfMin, capsuleBlend));
        float hBr = handle(cBr, circleBlendWeight(reqBr, halfMin, capsuleBlend));
        float hBl = handle(cBl, circleBlendWeight(reqBl, halfMin, capsuleBlend));

        float l = b.left, t = b.top, r = b.right, bo = b.bottom;

        // 顺时针：上边 → 右上 → 右 → 右下 → 下 → 左下 → 左 → 左上
        path.moveTo(l + cTl, t);
        path.lineTo(r - cTr, t);
        path.cubicTo(r - hTr, t, r, t + hTr, r, t + cTr);

        path.lineTo(r, bo - cBr);
        path.cubicTo(r, bo - hBr, r - hBr, bo, r - cBr, bo);

        path.lineTo(l + cBl, bo);
        path.cubicTo(l + hBl, bo, l, bo - hBl, l, bo - cBl);

        path.lineTo(l, t + cTl);
        path.cubicTo(l, t + hTl, l + hTl, t, l + cTl, t);

        path.close();
        return path;
    }

    /** 只有上方两角是圆角（底部面板贴屏用），下方两角为直角。 */
    static Path rectTop(RectF b, float radiusPx, boolean capsuleBlend) {
        return rect(b, radiusPx, radiusPx, 0f, 0f, capsuleBlend);
    }

    /** 只圆下面两角：顶边要当接缝用，和上面那块面板拼成一体 */
    static Path rectBottom(RectF b, float radiusPx, boolean capsuleBlend) {
        return rect(b, 0f, 0f, radiusPx, radiusPx, capsuleBlend);
    }

    /** 胶囊：短边一半为半径，且强制混回正圆，端头才是半圆而不是胖椭圆。 */
    static Path capsule(RectF b) {
        float halfMin = Math.min(b.width(), b.height()) / 2f;
        return rect(b, halfMin, halfMin, halfMin, halfMin, true);
    }
}
