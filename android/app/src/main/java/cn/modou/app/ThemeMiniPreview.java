package cn.modou.app;

import android.graphics.Canvas;
import android.graphics.Color;
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
 * 主题画廊里的「迷你界面」预览（0.9.160）。
 *
 * <p>参考图里每张主题卡都带一张 Aa 缩略，但三套<b>设计语言</b>的差异不在纸张配色，
 * 而在导航形态、卡片几何、按钮形状、色调层级与材质。所以这里不是画个色块，
 * 而是用 Canvas 按目标语言的真实规格画一个竖版手机界面：
 *
 * <ul>
 *   <li>墨读：淡渐变底衬 + 连续曲率玻璃卡 + 悬浮胶囊浮岛（离底、左右留边、选中靠浓淡放大）</li>
 *   <li>M3：纯色 surface + 普通圆弧 Filled card + 全圆 Filled button +
 *       贴底 Navigation Bar（64×32 primaryContainer active indicator + filled icon）</li>
 *   <li>iOS：groupedBackground + 白卡 Inset Grouped（左缩 hairline + disclosure ›）+
 *       蓝色胶囊按钮 + 贴底 Liquid Glass Tab Bar（顶缘凝光 + 0.5px hairline，选中仅 tint 变蓝）</li>
 * </ul>
 *
 * <p><b>预览与实机同源</b>：颜色 / 形状 / 导航参数全部读 {@code DesignSpec.of(lang, dark)}，
 * 不另抄一份数值。规格表一改，三张缩略图自动跟着改 —— 这是「一改全改」原则在预览上的延伸。
 * 尺寸完全按 bounds 比例计算，同一份 Drawable 既能当小卡（约 100×150dp），
 * 也能当「正在使用」大预览（约 140×190dp）。
 */
final class ThemeMiniPreview extends Drawable {

    private final DesignSpec sp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tmp = new Path();

    ThemeMiniPreview(DesignSpec.Lang lang, boolean dark) {
        this.sp = DesignSpec.of(lang, dark);
        bar.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        if (w < 8 || h < 8) return;

        c.save();
        c.translate(b.left, b.top);
        // 内容按外层卡片圆角裁掉，不溢出缩略卡
        float outer = Math.min(w, h) * 0.085f;
        RectF all = new RectF(0, 0, w, h);
        pathFor(all, outer);
        c.clipPath(tmp);

        backdrop(c, w, h);
        statusBar(c, w, h);
        topBar(c, w, h);
        content(c, w, h);
        nav(c, w, h);

        c.restore();
    }

    /* ---------------- 页面底衬 ---------------- */

    private void backdrop(Canvas c, float w, float h) {
        fill.setStyle(Paint.Style.FILL);
        if (sp.gradientBackdrop) {
            // 墨读：斜向淡渐变 + 一团柔光，卡片浮在光上才成立
            LinearGradient g = new LinearGradient(0, 0, w * 0.30f, h,
                    mix(sp.bg, sp.dark ? 0xFF223027 : 0xFFFFFFFF, 0.16f),
                    sp.bg, Shader.TileMode.CLAMP);
            fill.setShader(g);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            fill.setColor(sp.dark ? 0x10FFFFFF : 0x30FFFFFF);
            c.drawCircle(w * 0.86f, h * 0.18f, w * 0.40f, fill);
        } else {
            fill.setColor(sp.bg);
            c.drawRect(0, 0, w, h, fill);
            if (sp.material == DesignSpec.Material.TONAL) {
                // M3 的层级不是渐变，是 elevation 带来的 primary tint（预览里加重一点更明显）
                fill.setColor(alpha(sp.accent, 0x16));
                c.drawRect(0, 0, w, h * 0.40f, fill);
            }
        }
    }

    private void statusBar(Canvas c, float w, float h) {
        float y = h * 0.032f;
        bar.setColor(alpha(sp.text, 0x55));
        c.drawRoundRect(w * 0.07f, y, w * 0.16f, y + h * 0.011f, 2f, 2f, bar);
        float r = Math.max(1f, w * 0.007f);
        for (int i = 0; i < 3; i++) {
            c.drawCircle(w * (0.80f + i * 0.045f), y + h * 0.0055f, r, bar);
        }
    }

    /* ---------------- 顶栏 ---------------- */

    private void topBar(Canvas c, float w, float h) {
        float cy = h * 0.115f;
        float s = w * 0.026f;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, w * 0.011f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(alpha(sp.text, 0x75));
        // 返回箭头（三套共有）
        c.drawLine(w * 0.10f, cy, w * 0.065f, cy - s, line);
        c.drawLine(w * 0.065f, cy - s, w * 0.065f, cy + s, line);

        if (sp.material == DesignSpec.Material.TONAL) {
            // M3：左对齐标题（紧贴返回钮右侧）+ 右上角实心头像圆，一眼区分于居中排版
            bar.setColor(alpha(sp.text, 0x8A));
            float tw = w * 0.30f, th = h * 0.016f;
            c.drawRoundRect(w * 0.155f, cy - th / 2f, w * 0.155f + tw, cy + th / 2f,
                    th / 2f, th / 2f, bar);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(sp.accent);
            c.drawCircle(w * 0.90f, cy, s * 1.15f, fill);
        } else if (sp.material == DesignSpec.Material.LIQUID) {
            // iOS：居中 Large Title（更宽更高，象征加粗大标题）+ 右上角空心搜索圆
            bar.setColor(alpha(sp.text, 0x8A));
            float tw = w * 0.34f, th = h * 0.022f;
            c.drawRoundRect((w - tw) / 2f, cy - th / 2f, (w + tw) / 2f, cy + th / 2f,
                    th / 2f, th / 2f, bar);
            line.setColor(alpha(sp.text, 0x75));
            c.drawCircle(w * 0.90f, cy, s * 1.1f, line);
        } else {
            // 墨读：居中标题 + 右上角 ＋
            bar.setColor(alpha(sp.text, 0x8A));
            float tw = w * 0.26f, th = h * 0.018f;
            c.drawRoundRect((w - tw) / 2f, cy - th / 2f, (w + tw) / 2f, cy + th / 2f,
                    th / 2f, th / 2f, bar);
            line.setColor(alpha(sp.text, 0x75));
            c.drawLine(w * 0.90f - s, cy, w * 0.90f + s, cy, line);
            c.drawLine(w * 0.90f, cy - s, w * 0.90f, cy + s, line);
        }
    }

    /* ---------------- 内容区 ---------------- */

    private void content(Canvas c, float w, float h) {
        float left = w * 0.085f, right = w * 0.915f;
        float cardR = w * 0.17f * (sp.cornerCard / 22f);

        // 主卡
        RectF card = new RectF(left, h * 0.195f, right, h * 0.505f);
        int cardColor = sp.material == DesignSpec.Material.TONAL
                ? sp.surfaceLow : (sp.material == DesignSpec.Material.GLASS
                ? alpha(sp.surface, sp.dark ? 0xB0 : 0xD8) : sp.surface);
        shape(c, card, cardR, cardColor);

        if (sp.material == DesignSpec.Material.GLASS) {
            // 墨读：边缘泛光 + 顶部凝光，无硬线
            outline(c, card, cardR, sp.glassRim, Math.max(1f, w * 0.006f));
            fill.setStyle(Paint.Style.FILL);
            LinearGradient sheen = new LinearGradient(0, card.top, 0, card.top + card.height() * 0.32f,
                    0x40FFFFFF, 0x00000000, Shader.TileMode.CLAMP);
            fill.setShader(sheen);
            c.drawRect(card.left, card.top, card.right, card.top + card.height() * 0.32f, fill);
            fill.setShader(null);
        } else if (sp.material == DesignSpec.Material.LIQUID) {
            // iOS：Inset Grouped 行间 hairline，左缩进跟文字对齐 + 行尾 disclosure
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1f, w * 0.004f));
            line.setColor(sp.line);
            float rowY = card.top + card.height() * 0.50f;
            c.drawLine(card.left + w * 0.075f, rowY, card.right - w * 0.04f, rowY, line);
            float dy = rowY - card.height() * 0.25f;
            c.drawLine(card.right - w * 0.075f, dy - w * 0.016f,
                    card.right - w * 0.055f, dy, line);
            c.drawLine(card.right - w * 0.055f, dy,
                    card.right - w * 0.075f, dy + w * 0.016f, line);
            // 多画一行 hairline，强化「分组列表」观感
            float rowY2 = card.top + card.height() * 0.76f;
            c.drawLine(card.left + w * 0.075f, rowY2, card.right - w * 0.04f, rowY2, line);
        } else {
            // M3：card 内叠一层极淡 primary tint，体现 surfaceContainerLow → Low 的 tonal 层级
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(alpha(sp.accent, 0x08));
            c.drawRect(card, fill);
        }

        // 卡内占位文本条（两行，长度递减）
        bar.setColor(alpha(sp.text, 0x55));
        c.drawRoundRect(card.left + w * 0.075f, card.top + card.height() * 0.22f,
                card.left + w * 0.52f, card.top + card.height() * 0.22f + h * 0.014f,
                h * 0.007f, h * 0.007f, bar);
        bar.setColor(alpha(sp.text, 0x24));
        c.drawRoundRect(card.left + w * 0.075f, card.top + card.height() * 0.62f,
                card.left + w * 0.40f, card.top + card.height() * 0.62f + h * 0.010f,
                h * 0.005f, h * 0.005f, bar);

        // 主按钮：墨读 8dp 小方钮；M3 / iOS 全圆胶囊
        RectF btn = new RectF(left, h * 0.555f, left + w * 0.42f, h * 0.625f);
        float btnR = sp.cornerButton >= 999 ? btn.height() / 2f : w * 0.045f;
        shape(c, btn, btnR, sp.accent);
        bar.setColor(sp.onAccent);
        c.drawRoundRect(btn.left + btn.width() * 0.22f, btn.centerY() - h * 0.006f,
                btn.right - btn.width() * 0.22f, btn.centerY() + h * 0.006f,
                h * 0.006f, h * 0.006f, bar);

        // 次卡 / 第二组：三套形状依旧不同，且 iOS 直接画成分组列表、与另两套的「卡片」拉开
        if (sp.material == DesignSpec.Material.LIQUID) {
            // iOS：Inset Grouped 列表 —— 不画填充卡，直接画 2 行 hairline 分组行
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1f, w * 0.004f));
            line.setColor(sp.line);
            float top2 = h * 0.665f, bot2 = h * 0.795f;
            float rh = (bot2 - top2) / 2f;
            for (int r = 0; r < 2; r++) {
                float ry = top2 + rh * r;
                // 行首小圆头像（SF 风格彩色圆）
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(alpha(sp.accent, 0xCC));
                c.drawCircle(left + w * 0.045f, ry + rh * 0.5f, w * 0.022f, fill);
                // 标题条
                bar.setColor(alpha(sp.text, 0x55));
                c.drawRoundRect(left + w * 0.105f, ry + rh * 0.34f,
                        left + w * 0.52f, ry + rh * 0.34f + h * 0.010f, h * 0.005f, h * 0.005f, bar);
                // 行尾 disclosure ›
                float dy = ry + rh * 0.5f;
                c.drawLine(right - w * 0.075f, dy - w * 0.013f, right - w * 0.057f, dy, line);
                c.drawLine(right - w * 0.057f, dy, right - w * 0.075f, dy + w * 0.013f, line);
                if (r == 0) c.drawLine(left + w * 0.105f, ry + rh, right - w * 0.04f, ry + rh, line);
            }
        } else {
            RectF card2 = new RectF(left, h * 0.665f, right, h * 0.795f);
            shape(c, card2, cardR * 0.85f, cardColor);
            if (sp.material == DesignSpec.Material.GLASS) {
                outline(c, card2, cardR * 0.85f, alpha(sp.glassRim, 0x66), Math.max(1f, w * 0.005f));
            }
            bar.setColor(alpha(sp.text, 0x30));
            c.drawRoundRect(card2.left + w * 0.075f, card2.top + card2.height() * 0.26f,
                    card2.left + w * 0.60f, card2.top + card2.height() * 0.26f + h * 0.010f,
                    h * 0.005f, h * 0.005f, bar);
            if (sp.material == DesignSpec.Material.TONAL) {
                // M3 再给一枚 tonal chip，明快、块面清晰
                RectF chip = new RectF(card2.right - w * 0.30f, card2.top + card2.height() * 0.60f,
                        card2.right - w * 0.06f, card2.bottom - card2.height() * 0.16f);
                shape(c, chip, chip.height() / 2f, sp.container);
            }
        }
    }

    /* ---------------- 底部导航（差异最大处） ---------------- */

    private void nav(Canvas c, float w, float h) {
        boolean floating = sp.navStyle == DesignSpec.NavStyle.FLOATING_ISLAND;
        boolean m3 = sp.navStyle == DesignSpec.NavStyle.M3_NAV_BAR;
        float top = floating ? h * 0.855f : (m3 ? h * 0.840f : h * 0.858f);
        float cy = (top + h) / 2f;

        if (floating) {
            // 墨读：离底、左右留边的全圆玻璃浮岛 + 顶部凝光
            RectF island = new RectF(w * 0.10f, top, w * 0.90f, h * 0.965f);
            // 先垫一层柔和投影，强调「悬浮」而非贴底（与 M3 / iOS 的贴底栏拉开差距）
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(alpha(0xFF000000, sp.dark ? 0x38 : 0x1C));
            pill(c, new RectF(island.left + w * 0.03f, island.top + h * 0.014f,
                    island.right - w * 0.03f, island.bottom + h * 0.014f),
                    alpha(0xFF000000, sp.dark ? 0x38 : 0x1C));
            pill(c, island, alpha(sp.glassBase, sp.dark ? 0xD0 : 0xE0));
            outline(c, island, island.height() / 2f,
                    alpha(sp.glassRim, 0x99), Math.max(1f, w * 0.006f));
            fill.setStyle(Paint.Style.FILL);
            LinearGradient sheen = new LinearGradient(0, island.top, 0, island.top + island.height() * 0.5f,
                    0x4DFFFFFF, 0x00000000, Shader.TileMode.CLAMP);
            fill.setShader(sheen);
            c.drawRect(island.left, island.top, island.right, island.top + island.height() * 0.5f, fill);
            fill.setShader(null);
        } else {
            // 贴底全宽栏：M3 实色 surfaceContainer（+ primary tint）；iOS 半透 Liquid Glass
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(sp.navBarColor);
            c.drawRect(0, top, w, h, fill);
            if (m3 && sp.navSurfaceTint != 0) {
                fill.setColor(sp.navSurfaceTint);
                c.drawRect(0, top, w, h, fill);
            }
            if (sp.navDividerColor != 0) {
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(Math.max(1f, w * 0.004f));
                line.setColor(sp.navDividerColor);
                c.drawLine(0, top, w, top, line);
            }
            if (sp.navGlassMaterial && sp.navGlassHighlight != 0) {
                // iOS 26 Tab Bar 顶缘镜面凝光
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(Math.max(1f, w * 0.009f));
                line.setColor(sp.navGlassHighlight);
                c.drawLine(0, top + w * 0.004f, w, top + w * 0.004f, line);
            }
        }

        // 四个导航项
        float size = w * (floating ? 0.070f : 0.078f);
        for (int i = 0; i < 4; i++) {
            float cx = w * (0.125f + i * 0.25f);
            boolean on = i == 0;
            int color;
            if (floating) {
                color = on ? sp.navOnColor : sp.navOffColor;
            } else if (m3) {
                color = on ? sp.navOnColor : sp.navOffColor;
            } else {
                color = on ? sp.accent : sp.navOffColor;   // iOS 选中仅 tint 变蓝
            }

            if (m3 && on) {
                // active indicator：64×32 的 primaryContainer 药丸，图标坐落其上
                RectF ind = new RectF(cx - w * 0.105f, cy - h * 0.032f,
                        cx + w * 0.105f, cy + h * 0.032f);
                pill(c, ind, sp.navIndicatorColor);
            }

            float glyphCy = cy - (floating ? 0f : h * 0.012f);
            drawGlyph(c, cx, glyphCy, on && floating ? size * 1.10f : size, i, color,
                    m3 && on);

            // 标签占位条
            if (!floating) {
                bar.setColor(alpha(color, 0x60));
                float lw = w * 0.075f;
                c.drawRoundRect(cx - lw / 2f, h * 0.955f, cx + lw / 2f,
                        h * 0.955f + h * 0.006f, 2f, 2f, bar);
            }
        }
    }

    /** 四种导航语义图标：书 / 源 / 规则 / 设置；M3 选中项为 filled，其余线性 */
    private void drawGlyph(Canvas c, float cx, float cy, float size, int kind,
                           int color, boolean filled) {
        float s = size / 2f;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, size * 0.11f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);

        switch (kind) {
            case 0: // 打开的书
                if (filled) {
                    c.drawRect(cx - s, cy - s * 0.72f, cx, cy + s * 0.72f, fill);
                    c.drawRect(cx, cy - s * 0.72f, cx + s, cy + s * 0.72f, fill);
                } else {
                    c.drawRect(cx - s, cy - s * 0.72f, cx, cy + s * 0.72f, line);
                    c.drawRect(cx, cy - s * 0.72f, cx + s, cy + s * 0.72f, line);
                }
                c.drawLine(cx, cy - s * 0.72f, cx, cy + s * 0.72f, line);
                break;
            case 1: // 书源：三枚圆点
                float[] xs = {cx - s * 0.62f, cx, cx + s * 0.62f};
                float[] ys = {cy - s * 0.32f, cy + s * 0.45f, cy - s * 0.32f};
                for (int i = 0; i < 3; i++) {
                    if (filled) c.drawCircle(xs[i], ys[i], s * 0.27f, fill);
                    else c.drawCircle(xs[i], ys[i], s * 0.27f, line);
                }
                break;
            case 2: // 规则：三条递减横线
                for (int i = 0; i < 3; i++) {
                    float hw = s * (0.85f - i * 0.22f);
                    float y = cy - s * 0.55f + i * s * 0.55f;
                    c.drawLine(cx - hw, y, cx + hw, y, line);
                }
                break;
            default: // 设置：齿轮简化为圆环 + 轴心
                if (filled) c.drawCircle(cx, cy, s * 0.82f, fill);
                else c.drawCircle(cx, cy, s * 0.82f, line);
                c.drawCircle(cx, cy, s * 0.25f, filled ? fill : line);
                break;
        }
    }

    /* ---------------- 绘制小工具 ---------------- */

    private void pathFor(RectF r, float radiusPx) {
        tmp.reset();
        if (sp.squircle) tmp.set(ModouShape.rect(r, radiusPx, false));
        else tmp.addRoundRect(r, radiusPx, radiusPx, Path.Direction.CW);
    }

    private void shape(Canvas c, RectF r, float radiusPx, int color) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        pathFor(r, radiusPx);
        c.drawPath(tmp, fill);
    }

    private void outline(Canvas c, RectF r, float radiusPx, int color, float widthPx) {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Math.max(1f, widthPx));
        line.setColor(color);
        pathFor(r, radiusPx);
        c.drawPath(tmp, line);
    }

    private void pill(Canvas c, RectF r, int color) {
        shape(c, r, r.height() / 2f, color);
    }

    private static int alpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static int mix(int a, int b, float t) {
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    @Override
    public void setAlpha(int a) {
        fill.setAlpha(a);
        line.setAlpha(a);
        bar.setAlpha(a);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
