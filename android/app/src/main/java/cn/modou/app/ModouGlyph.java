package cn.modou.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 细线条图标（Outline Icons）：加号 / 竖排三点 / 横排三点。
 *
 * <p>以前这些位置直接写字符 {@code "+"}、{@code "┇"}、{@code "⋮"}：
 * 字形由系统字体决定，笔画永远是「中文标点」级别的粗，且各机型粗细不一。
 * 自绘之后线宽固定 1.4dp、圆头，和 Chevron 是同一套语言。
 */
final class ModouGlyph extends Drawable {

    static final int PLUS = 0;
    static final int MORE_V = 1;
    static final int MORE_H = 2;
    static final int BOOKMARK = 3;
    static final int INFO = 4;
    static final int REFRESH = 5;
    static final int EDIT = 6;
    static final int GRID = 7;
    static final int ARROW_UP = 8;
    static final int CLOSE = 9;
    static final int BOOK = 10;
    static final int GEAR = 11;
    static final int SEARCH = 12;
    static final int FILTER = 13;
    static final int BACK = 14;
    static final int ADD = 15;
    static final int TRASH = 16;
    static final int SWITCH = 17;
    static final int CHECK = 18;
    static final int BLOCK = 19;
    static final int DRAG = 20;
    static final int UP_DOWN = 21;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind;
    private final float size;
    private final float stroke;

    ModouGlyph(int kind, int color, float sizePx, float strokePx) {
        this.kind = kind;
        this.size = sizePx;
        this.stroke = Math.max(1f, strokePx);
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float cx = b.centerX();
        float cy = b.centerY();
        float half = size / 2f;

        if (kind == SEARCH) {
            // 放大镜：圆环 + 45° 短柄。环心略偏左上，柄落到右下 0.40 处，
            // 整体重心才落在字形正中；线宽与其余顶栏图标同一档（1.8dp），不粗不细。
            float r = size * 0.30f;
            float ox = cx - size * 0.055f;
            float oy = cy - size * 0.055f;
            c.drawCircle(ox, oy, r, paint);
            float sx = ox + r * 0.7071f;
            float sy = oy + r * 0.7071f;
            c.drawLine(sx, sy, cx + size * 0.40f, cy + size * 0.40f, paint);
            return;
        }

        if (kind == FILTER) {
            // 三条递减横线（左对齐）—— ColorOS 的极简筛选。
            // 原来的闭合漏斗在 19dp 上是一整块黑疙瘩：六段折线 + 尖角，
            // 线宽一上去就显笨重。三条圆头线只有三笔，密度和放大镜、三点完全同级。
            // 居中对齐（不是左对齐）：三条线的重心落在字形正中，
            // 两侧留白一样多，视觉上更像「漏斗收口」而不是「排序箭头」。
            float[] halfW = {size * 0.38f, size * 0.24f, size * 0.11f};
            float[] ys = {cy - size * 0.24f, cy, cy + size * 0.24f};
            for (int i = 0; i < 3; i++) {
                c.drawLine(cx - halfW[i], ys[i], cx + halfW[i], ys[i], paint);
            }
            return;
        }
        if (kind == BACK) {
            // < 箭头：一笔两段，端头圆角
            c.drawLine(cx + size * 0.16f, cy - size * 0.32f,
                    cx - size * 0.16f, cy, paint);
            c.drawLine(cx - size * 0.16f, cy,
                    cx + size * 0.16f, cy + size * 0.32f, paint);
            return;
        }
        if (kind == ADD) {
            // 略小的 +：圆形底里留出呼吸空间（PLUS 的 half 顶到边）
            float h = size * 0.40f;
            c.drawLine(cx - h, cy, cx + h, cy, paint);
            c.drawLine(cx, cy - h, cx, cy + h, paint);
            return;
        }

        if (kind == UP_DOWN) {
            // 上下双箭头（级联菜单父项）：两个叠放的小 chevron，中间留半格呼吸
            float g = size * 0.30f;   // chevron 半宽
            float gap = size * 0.10f; // 中缝
            c.drawLine(cx - g, cy - gap - size * 0.14f, cx, cy - gap - size * 0.30f, paint);
            c.drawLine(cx, cy - gap - size * 0.30f, cx + g, cy - gap - size * 0.14f, paint);
            c.drawLine(cx - g, cy + gap + size * 0.14f, cx, cy + gap + size * 0.30f, paint);
            c.drawLine(cx, cy + gap + size * 0.30f, cx + g, cy + gap + size * 0.14f, paint);
            return;
        }

        if (kind == PLUS) {
            // 两条等长线：比字符 + 细一档，也不会因为字体把基线抬高
            c.drawLine(cx - half, cy, cx + half, cy, paint);
            c.drawLine(cx, cy - half, cx, cy + half, paint);
            return;
        }

        // 以下都是「线性菜单图标」：和四点三点的 line 同一线宽、同一圆头，
        // 阅读页更多菜单、书源页工具条都从这一套里取，不再各画各的。
        if (kind == BOOKMARK) {
            // 旗形书签：上宽下尖，中间一个 V
            Path p = new Path();
            float w = size * 0.30f;
            p.moveTo(cx - w, cy - size * 0.42f);
            p.lineTo(cx + w, cy - size * 0.42f);
            p.lineTo(cx + w, cy + size * 0.40f);
            p.lineTo(cx, cy + size * 0.14f);
            p.lineTo(cx - w, cy + size * 0.40f);
            p.close();
            c.drawPath(p, paint);
            return;
        }
        if (kind == INFO) {
            c.drawCircle(cx, cy, size * 0.40f, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy - size * 0.16f, stroke * 0.78f, paint);
            paint.setStyle(Paint.Style.STROKE);
            c.drawLine(cx, cy - size * 0.02f, cx, cy + size * 0.22f, paint);
            return;
        }
        if (kind == REFRESH) {
            // 300° 圆弧 + 端点箭头：缺口留在右上，视觉上「转了一圈」。
            // 半径放大一档：小弧 + 粗线会糊成一团，大弧才和同排图标一个密度
            float r = size * 0.40f;
            c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -55, 280, false, paint);
            float ax = cx + r * (float) Math.cos(Math.toRadians(-55));
            float ay = cy + r * (float) Math.sin(Math.toRadians(-55));
            c.drawLine(ax, ay, ax + size * 0.13f, ay, paint);
            c.drawLine(ax, ay, ax, ay + size * 0.13f, paint);
            return;
        }
        if (kind == EDIT) {
            // 铅笔：笔身四边形 + 笔尖一小横
            Path p = new Path();
            p.moveTo(cx - size * 0.36f, cy + size * 0.36f);
            p.lineTo(cx - size * 0.28f, cy + size * 0.10f);
            p.lineTo(cx + size * 0.18f, cy - size * 0.38f);
            p.lineTo(cx + size * 0.38f, cy - size * 0.18f);
            p.lineTo(cx - size * 0.10f, cy + size * 0.28f);
            p.close();
            c.drawPath(p, paint);
            c.drawLine(cx - size * 0.36f, cy + size * 0.36f,
                    cx - size * 0.20f, cy + size * 0.36f, paint);
            return;
        }
        if (kind == GRID) {
            // 图标排布：四宫格（格子加大占面，避免在同等线宽下显得比其他图标细）
            float w = size * 0.33f, g = size * 0.05f, rr = size * 0.09f;
            c.drawRoundRect(new RectF(cx - g - w, cy - g - w, cx - g, cy - g),
                    rr, rr, paint);
            c.drawRoundRect(new RectF(cx + g, cy - g - w, cx + g + w, cy - g),
                    rr, rr, paint);
            c.drawRoundRect(new RectF(cx - g - w, cy + g, cx - g, cy + g + w),
                    rr, rr, paint);
            c.drawRoundRect(new RectF(cx + g, cy + g, cx + g + w, cy + g + w),
                    rr, rr, paint);
            return;
        }
        if (kind == ARROW_UP) {
            c.drawLine(cx, cy + size * 0.38f, cx, cy - size * 0.34f, paint);
            c.drawLine(cx, cy - size * 0.34f,
                    cx - size * 0.20f, cy - size * 0.14f, paint);
            c.drawLine(cx, cy - size * 0.34f,
                    cx + size * 0.20f, cy - size * 0.14f, paint);
            return;
        }
        if (kind == CLOSE) {
            c.drawLine(cx - size * 0.30f, cy - size * 0.30f,
                    cx + size * 0.30f, cy + size * 0.30f, paint);
            c.drawLine(cx + size * 0.30f, cy - size * 0.30f,
                    cx - size * 0.30f, cy + size * 0.30f, paint);
            return;
        }
        if (kind == BOOK) {
            // 书籍详情：圆角书 + 中缝
            float w = size * 0.32f, h = size * 0.42f;
            c.drawRoundRect(new RectF(cx - w, cy - h, cx + w, cy + h),
                    size * 0.10f, size * 0.10f, paint);
            c.drawLine(cx - w * 0.34f, cy - h, cx - w * 0.34f, cy + h, paint);
            return;
        }
        if (kind == GEAR) {
            // 设置：外圈 + 内圈 + 八根齿（比整块齿轮轮廓更适合 20dp 的小尺寸，
            // 齿太密会糊成一团）
            float gr = size * 0.28f;
            c.drawCircle(cx, cy, gr, paint);
            c.drawCircle(cx, cy, size * 0.13f, paint);
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45);
                float c1 = (float) Math.cos(a), s1 = (float) Math.sin(a);
                c.drawLine(cx + c1 * gr, cy + s1 * gr,
                        cx + c1 * size * 0.41f, cy + s1 * size * 0.41f, paint);
            }
            return;
        }

        if (kind == TRASH) {
            // 垃圾桶：桶盖横线 + 梯形桶身 + 两道内肋。桶身收窄一档，
            // 和同排图标的笔画密度拉平（宽梯形 + 粗线会显得整块发黑）
            c.drawLine(cx - size * 0.32f, cy - size * 0.30f,
                    cx + size * 0.32f, cy - size * 0.30f, paint);
            Path p = new Path();
            p.moveTo(cx - size * 0.24f, cy - size * 0.30f);
            p.lineTo(cx - size * 0.18f, cy + size * 0.38f);
            p.lineTo(cx + size * 0.18f, cy + size * 0.38f);
            p.lineTo(cx + size * 0.24f, cy - size * 0.30f);
            c.drawPath(p, paint);
            // 内肋缩短一档：长肋 + 梯形身会整块发黑，和同排细线图标不在一个重量级
            c.drawLine(cx - size * 0.07f, cy - size * 0.10f,
                    cx - size * 0.06f, cy + size * 0.14f, paint);
            c.drawLine(cx + size * 0.07f, cy - size * 0.10f,
                    cx + size * 0.06f, cy + size * 0.14f, paint);
            return;
        }
        if (kind == SWITCH) {
            // 开关：胶囊轮廓 + 实心旋钮。批量启用/禁用的入口图标
            c.drawRoundRect(new RectF(cx - size * 0.42f, cy - size * 0.26f,
                            cx + size * 0.42f, cy + size * 0.26f),
                    size * 0.26f, size * 0.26f, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx + size * 0.17f, cy, size * 0.14f, paint);
            paint.setStyle(Paint.Style.STROKE);
            return;
        }
        if (kind == BLOCK) {
            // 禁止符号：圆环 + 45° 斜杠（下方「启用/禁用」用它，一眼就是「关掉」）
            float r = size * 0.41f;
            c.drawCircle(cx, cy, r, paint);
            float d = r * 0.7071f;
            c.drawLine(cx - d, cy + d, cx + d, cy - d, paint);
            return;
        }
        if (kind == DRAG) {
            // 拖拽手柄：两列各三个点（长按它才拖，长按行仍是进多选）
            paint.setStyle(Paint.Style.FILL);
            float r = stroke * 0.66f;
            float gx = size * 0.16f, gy = size * 0.30f;
            for (int i = -1; i <= 1; i++) {
                c.drawCircle(cx - gx, cy + i * gy, r, paint);
                c.drawCircle(cx + gx, cy + i * gy, r, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            return;
        }
        if (kind == CHECK) {
            // 对勾：方框内的白勾（多选 checkbox 的选中态）
            Path p = new Path();
            p.moveTo(cx - size * 0.32f, cy + size * 0.01f);
            p.lineTo(cx - size * 0.09f, cy + size * 0.25f);
            p.lineTo(cx + size * 0.33f, cy - size * 0.25f);
            c.drawPath(p, paint);
            return;
        }

        // 三点：纯圆点，不用 stroke（stroke 会变成三个小圆环）。
        // 半径与间距按字形尺寸算，不再乘线宽 —— 否则书架 24dp 与书源 19dp
        // 的「⋮」会一个胖一个瘦，放在两页之间像两套图标。
        paint.setStyle(Paint.Style.FILL);
        float gap = size * 0.30f;    // 圆心间距：19dp 字形约 5.7dp
        float r = size * 0.115f;     // 半径：19dp 字形约 2.2dp，饱满但不糊成一条
        if (kind == MORE_V) {
            c.drawCircle(cx, cy - gap, r, paint);
            c.drawCircle(cx, cy, r, paint);
            c.drawCircle(cx, cy + gap, r, paint);
        } else {
            c.drawCircle(cx - gap, cy, r, paint);
            c.drawCircle(cx, cy, r, paint);
            c.drawCircle(cx + gap, cy, r, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
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
        return Math.round(size);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(size);
    }
}
