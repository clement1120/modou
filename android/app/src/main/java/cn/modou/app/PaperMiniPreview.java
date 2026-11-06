package cn.modou.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * 「阅读纸张」画廊的迷你阅读页预览（0.9.161）。
 *
 * <p>参考图里每张主题卡都带一张 {@code Aa} 缩略。本项目的四档差异是<b>真实纸色 +
 * 墨色</b>，所以这里直接读 {@link NativeTheme#of(String)} 出图：素月白冷白、
 * 晨光米暖黄、远山青淡青绿、夜间近黑，字色与卡片色同源 —— 纸色一改，预览同步，
 * 不会出现「卡片好看、进阅读页是另一套颜色」。
 *
 * <p>画的不是色块：顶部章名细线、{@code Aa} 字样、错落正文行、底部工具坞细条
 * 一起构成一页缩略书，尺寸按 bounds 自适应（大预览卡与 2×2 缩略卡共用同一份绘制）。
 */
final class PaperMiniPreview extends Drawable {

    private final NativeTheme t;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 正文行的长短比例：末行短，模仿真书段落收尾 */
    private static final float[] LINE_W = {0.86f, 0.94f, 0.79f, 0.91f, 0.62f, 0.88f};

    PaperMiniPreview(String name) {
        this.t = NativeTheme.of(name);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        if (w <= 0 || h <= 0) return;

        float pad = Math.min(w, h) * 0.085f;
        float r = Math.min(w, h) * 0.105f;
        RectF card = new RectF(b.left, b.top, b.right, b.bottom);

        // 1 纸张本体
        p.setStyle(Paint.Style.FILL);
        p.setColor(t.bg);
        c.drawRoundRect(card, r, r, p);

        c.save();
        c.clipPath(roundPath(card, r));

        // 2 纸面受光：顶部一层极淡的暖/冷高光，让四档纸的光感不同（夜间收敛为冷光）
        int glow = t.dark ? 0x143C5A8C
                : ("cream".equals(nameOf()) ? 0x22FFE9B0
                : "gray".equals(nameOf()) ? 0x1C9FD8B4 : 0x18FFFFFF);
        p.setShader(new LinearGradient(0, b.top, w * 0.35f, b.top + h * 0.72f,
                glow, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(card, p);
        p.setShader(null);

        // 3 顶部章名细线 + 右侧小圆点（模拟更多按钮）
        float x0 = b.left + pad, x1 = b.right - pad;
        float unit = h * 0.028f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(withAlpha(t.sub, 0x70));
        RectF title = new RectF(x0, b.top + pad * 0.92f,
                x0 + w * 0.36f, b.top + pad * 0.92f + unit);
        c.drawRoundRect(title, unit / 2, unit / 2, p);
        float dotR = unit * 0.62f;
        p.setColor(withAlpha(t.sub, 0x50));
        c.drawCircle(x1 - dotR, title.centerY(), dotR, p);

        // 4 Aa 字样：衬线 + 加粗，落在纸面上部三分之一
        float aaSize = Math.min(w * 0.34f, h * 0.205f);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(t.text);
        ink.setTypeface(Typeface.SERIF);
        ink.setFakeBoldText(true);
        ink.setTextSize(aaSize);
        Paint.FontMetrics fm = ink.getFontMetrics();
        float aaBottom = b.top + pad * 0.92f + unit + aaSize;
        c.drawText("Aa", x0, aaBottom - fm.descent, ink);
        ink.setFakeBoldText(false);

        // 5 正文行：长短错落，末行短；夜间用浅墨、浅色纸用深墨的低透明度
        float lineTop = aaBottom + aaSize * 0.30f;
        float lineH = Math.max(unit * 0.72f, (b.bottom - pad * 2.1f - lineTop) / (LINE_W.length + 1.8f));
        float thick = Math.max(1.4f, unit * 0.52f);
        p.setColor(withAlpha(t.text, t.dark ? 0x55 : 0x46));
        for (int i = 0; i < LINE_W.length; i++) {
            float y = lineTop + lineH * (i + 0.4f);
            if (y > b.bottom - pad * 2.0f) break;
            float rw = (x1 - x0) * LINE_W[i];
            c.drawRoundRect(new RectF(x0, y, x0 + rw, y + thick),
                    thick / 2f, thick / 2f, p);
        }

        // 6 底部工具坞细条 + 进度点：强调色低透明度，与真实工具坞同一支蓝
        float barY = b.bottom - pad * 1.15f;
        p.setColor(withAlpha(t.card, 0xFF));
        c.drawRoundRect(new RectF(x0, barY, x1, barY + unit * 1.1f),
                unit * 0.55f, unit * 0.55f, p);
        p.setColor(withAlpha(t.acc, 0xCC));
        c.drawRoundRect(new RectF(x0, barY, x0 + (x1 - x0) * 0.42f, barY + unit * 1.1f),
                unit * 0.55f, unit * 0.55f, p);
        c.restore();

        // 7 纸边：素月白在白色卡片上会糊掉，必须留一圈极淡轮廓
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, Math.min(w, h) * 0.007f));
        p.setColor(t.dark ? 0x26FFFFFF : 0x1F000000);
        c.drawRoundRect(card, r, r, p);
    }

    private String nameOf() {
        // NativeTheme 不回存名字，用配色反推（只为挑高光色，不参与主色）
        if (t.bg == 0xFFF7EDD9) return "cream";
        if (t.bg == 0xFFE3EBE4) return "gray";
        if (t.dark) return "dark";
        return "paper";
    }

    private static int withAlpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static android.graphics.Path roundPath(RectF r, float radius) {
        android.graphics.Path path = new android.graphics.Path();
        path.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
        return path;
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
        ink.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
        ink.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
