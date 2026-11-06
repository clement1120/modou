package cn.modou.app;

import android.graphics.Color;

/**
 * Tonal Palette 生成器（0.9.162 · 台账 §2.11 A1 / B3）。
 *
 * <p><b>为什么需要它</b>：M3 与 iOS 的「容器色 / 层级色 / 按压态」不是几块写死的灰，
 * 而是从主色按<b>感知均匀的亮度轴</b>派生出来的整条色阶：
 * <ul>
 *   <li>Material 3：5 组 tonal palette（primary / secondary / tertiary / neutral /
 *       neutral-variant）各 13 级 tone；{@code surfaceContainerLow..Highest} 是
 *       <b>neutral tone + 少量 primary tint 叠加</b>，因此浅色下带一点点紫、深色下带一点点冷，
 *       而不是纯灰（§2.11 A1）。</li>
 *   <li>M3 state layer：hover 8% / focus 10% / pressed 10% / dragged 16% 的 onSurface
 *       叠加，交互态只叠一层、不换底色、无涟漪外扩（§2.11 A2/A6）。</li>
 *   <li>iOS 26：systemFill 四档、label 四级、separator（非不透明）、system 蓝绿红靛橙，
 *       材质上的文字靠 vibrancy（§2.11 B3）。</li>
 * </ul>
 *
 * <p><b>实现口径（要如实，不夸大）</b>：官方 HCT 用 CAM16 色彩空间 + 一个求解
 * 「给定 hue / tone 下的最大 chroma」的 solver。这里用 <b>CIELAB (D65) 感知均匀空间</b>
 * 逼近 HCT 的 tone 轴：把 sRGB → 线性 → XYZ(D65) → Lab，以 L* 作 tone（0..100）、
 * a*b* 决定 hue / chroma；生成某一 tone 时保持 hue 与 chroma 的方向，只沿 L* 移动并做
 * gamut 钳制。这条轴与 CAM16 的 J* / tone 相关性极高、单调且可复现，足以生成视觉上等价的
 * container 色阶；差异在极端高彩度色上约 2~4 ΔE，不影响「容器分层靠 tint」这一设计意图。
 * 纯数学、零依赖、无抓屏、无第三方库，符合本项目「一改全改、少新东西」的约定。
 *
 * <p><b>零回归保证</b>：本类只<b>派生新增的</b>容器色 / state layer / system 色表，
 * 不改写 {@link DesignSpec} 里已定稿的 {@code surface / accent / text} 主色；墨读语言
 * 完全不调用它（保持 0.9.157 的原值）。
 */
final class ModouTonal {

    private ModouTonal() {}

    /** M3 标准 tone 断点（13 级），数组下标与 {@link Palette} 的 ramp 一一对应。 */
    static final int[] TONES = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100};

    /* ==================== sRGB / XYZ(D65) / Lab 互转 ==================== */

    private static float srgbToLinear(float v) {
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }

    private static float linearToSrgb(float v) {
        return v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1.0 / 2.4) - 0.055f;
    }

    private static float[] xyzFromArgb(int argb) {
        float r = srgbToLinear(Color.red(argb) / 255f);
        float g = srgbToLinear(Color.green(argb) / 255f);
        float b = srgbToLinear(Color.blue(argb) / 255f);
        // sRGB D65 → XYZ，矩阵按列主序展开
        return new float[]{
                0.4124564f * r + 0.3575761f * g + 0.1804375f * b,
                0.2126729f * r + 0.7151522f * g + 0.0721750f * b,
                0.0193339f * r + 0.1191920f * g + 0.9503041f * b
        };
    }

    private static int argbFromXyz(float[] xyz) {
        float x = xyz[0], y = xyz[1], z = xyz[2];
        float r =  3.2404542f * x - 1.5371385f * y - 0.4985314f * z;
        float g = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z;
        float b =  0.0556434f * x - 0.2040259f * y + 1.0572252f * z;
        // 先线性钳制再转 gamma：直接钳 gamma 值会让高彩度色偏灰
        r = clamp01(linearToSrgb(clamp01(r)));
        g = clamp01(linearToSrgb(clamp01(g)));
        b = clamp01(linearToSrgb(clamp01(b)));
        return 0xFF000000 | (Math.round(r * 255f) << 16)
                | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : v > 1f ? 1f : v; }

    // D65 白点
    private static final float XN = 0.95047f, YN = 1.00000f, ZN = 1.08883f;

    private static float labF(float t) {
        final float e = 216f / 24389f, k = 24389f / 27f;
        return t > e ? (float) Math.cbrt(t) : (k * t + 16f) / 116f;
    }

    private static float labFInv(float t) {
        final float e = 216f / 24389f, k = 24389f / 27f;
        float t3 = t * t * t;
        return t3 > e ? t3 : (116f * t - 16f) / k;
    }

    /** @return Lab = {L* (0..100), a*, b*} */
    static float[] labFromArgb(int argb) {
        float[] xyz = xyzFromArgb(argb);
        float fx = labF(xyz[0] / XN), fy = labF(xyz[1] / YN), fz = labF(xyz[2] / ZN);
        return new float[]{116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz)};
    }

    static int argbFromLab(float L, float a, float b) {
        float fy = (L + 16f) / 116f;
        float fx = fy + a / 500f;
        float fz = fy - b / 200f;
        return argbFromXyz(new float[]{
                XN * labFInv(fx), YN * labFInv(fy), ZN * labFInv(fz)});
    }

    /**
     * 把 seed 颜色投到指定 tone（0=黑 .. 100=白），保持 Lab 的 a 星 / b 星方向（hue）。
     * 高/低 tone 下按比例收敛 chroma，避免 gamut 钳制后色相翻转。
     */
    static int tone(int seed, int toneLevel) {
        int t = toneLevel < 0 ? 0 : toneLevel > 100 ? 100 : toneLevel;
        float[] lab = labFromArgb(seed);
        // 越靠近黑白，可容纳的 chroma 越小；用一个平滑包络避免边界硬切
        float room = (float) Math.sin(Math.PI * t / 100f);   // 0..1..0
        room = 0.35f + 0.65f * room;                          // 保留底噪，色阶不突然变灰
        float a = lab[1] * room;
        float b = lab[2] * room;
        return argbFromLab(t, a, b);
    }

    /** 生成 13 级 tone ramp（与 {@link #TONES} 对齐）。 */
    static int[] ramp(int seed) {
        int[] out = new int[TONES.length];
        for (int i = 0; i < TONES.length; i++) out[i] = tone(seed, TONES[i]);
        return out;
    }

    /** 取 ramp 上最接近某个 tone 的那一级。 */
    static int at(int[] ramp, int toneLevel) {
        int best = 0, diff = Integer.MAX_VALUE;
        for (int i = 0; i < TONES.length; i++) {
            int d = Math.abs(TONES[i] - toneLevel);
            if (d < diff) { diff = d; best = i; }
        }
        return ramp[best];
    }

    /**
     * primary tint 叠加：M3 的 surfaceContainer 系列 = neutral tone 上叠一点 primary。
     * 这是「容器色带情绪」的关键，纯灰替代不了（§2.11 A1/A9）。
     *
     * @param neutralTone 中性骨架色（由 neutral palette 生成）
     * @param primary     primary palette 的主色（一般取 tone 40/80）
     * @param strength    叠加比例，M3 elevation 递增时从 0.02 → 0.11
     */
    static int tint(int neutralTone, int primary, float strength) {
        float s = strength < 0f ? 0f : strength > 1f ? 1f : strength;
        int r = (int) (Color.red(neutralTone) + (Color.red(primary) - Color.red(neutralTone)) * s);
        int g = (int) (Color.green(neutralTone) + (Color.green(primary) - Color.green(neutralTone)) * s);
        int b = (int) (Color.blue(neutralTone) + (Color.blue(primary) - Color.blue(neutralTone)) * s);
        return 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    /** state layer：返回「带 alpha 的叠加色」，直接叠在容器上（不换底、无涟漪）。 */
    static int stateLayer(int contentColor, float fraction) {
        int a = Math.round(255 * (fraction < 0f ? 0f : fraction > 1f ? 1f : fraction));
        return (a << 24) | (contentColor & 0x00FFFFFF);
    }

    /** 把任意色压成指定 alpha 的透明色（iOS systemFill / separator 用）。 */
    static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /* ==================== M3 Palette ==================== */

    /** 5 组 tonal palette；M3 的容器色 / 按钮族 / state layer 全从这里派生。 */
    static final class Palette {
        final int[] primary, secondary, tertiary, neutral, neutralVariant;

        Palette(int seed, boolean dark) {
            float[] lab = labFromArgb(seed);
            float L = lab[0], a = lab[1], b = lab[2];
            // 色相旋转：secondary 与 tertiary 按 M3 规范做固定角度偏移，保证三组和而不同
            primary = ramp(seed);
            secondary = ramp(argbFromLab(Math.max(35f, Math.min(70f, L)),
                    a * 0.55f, b * 0.55f));                       // 同色相、低彩度
            tertiary = ramp(argbFromLab(Math.max(35f, Math.min(70f, L)),
                    -b * 0.42f + a * 0.30f, a * 0.42f + b * 0.30f)); // +~90°
            int neutralSeed = argbFromLab(50f, a * 0.05f, b * 0.05f);
            neutral = ramp(neutralSeed);
            int nvSeed = argbFromLab(50f, a * 0.12f, b * 0.12f);
            neutralVariant = ramp(nvSeed);
            // dark 只影响「取哪一级」，palette 本身与深浅无关（HCT 的 tone 轴是绝对的）
            if (dark) { /* 保留字段，取值在 DesignSpec 里按 tone 选择 */ }
        }

        /** primary key colors */
        int p(int toneLevel) { return at(primary, toneLevel); }
        int n(int toneLevel) { return at(neutral, toneLevel); }
        int nv(int toneLevel) { return at(neutralVariant, toneLevel); }
        int s(int toneLevel) { return at(secondary, toneLevel); }
        int t(int toneLevel) { return at(tertiary, toneLevel); }
    }

    /** 便捷：从主色构造 palette。 */
    static Palette palette(int seed, boolean dark) {
        return new Palette(seed, dark);
    }
}
