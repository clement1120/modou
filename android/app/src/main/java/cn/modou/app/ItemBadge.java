package cn.modou.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 0.9.168 · 动态列表项的 leading 字母块（Stage 4 第 6 部分）。
 *
 * <p>{@link ListIcon} 管的是「固定功能行」——语义明确，12 枚固定矢量图标足够。
 * 书源名 / 规则名 / 搜索词这类行里是动态数据，硬塞一枚固定图标语义是错的：
 * 137 个书源会长得一模一样。这里改用数据驱动：取名称首字，颜色由名称 hash
 * 派生，语义来自数据本身。
 *
 * <ul>
 *   <li>iOS 26：32dp 彩色圆角方块 + 白色首字，颜色按 seed hash 落
 *       system color 调色板（通讯录 / 邮件的头像逻辑）；</li>
 *   <li>M3 Expressive：40dp tonal 圆 + accent 单色首字（M3 avatar 规范，
 *       不彩色，靠 surface 层级）；</li>
 *   <li>墨读：{@code itemBadgeStyle == 0} 返回 {@code null}，
 *       调用方一行都不加，逐像素沿用 0.9.167。</li>
 * </ul>
 *
 * <p>为 RecyclerView 复用行设计：{@link #create} 只在建 ViewHolder 时调一次，
 * {@link #bind} 在每次 onBindViewHolder 里无条件重设首字与颜色——漏设任何一处
 * 都会让滚出屏幕的行带着上一个书源的首字回来。
 */
final class ItemBadge {

    private ItemBadge() { }

    /** iOS system colors：深浅模式都用同一套饱和色，与 ListIcon.iosColor 同一调色板。 */
    private static final int[] IOS_COLORS = {
            0xFF007AFF,  // blue
            0xFF34C759,  // green
            0xFFFF9500,  // orange
            0xFFFF3B30,  // red
            0xFF5856D6,  // indigo
            0xFFAF52DE,  // purple
            0xFFFF2D55,  // pink
            0xFF30B0C7,  // teal
            0xFFFFCC00,  // yellow
            0xFF00C7BE,  // mint
    };

    /** create 出来的可复用部件，挂在容器 tag 上，bind 时直接取用不重建 drawable。 */
    private static final class Parts {
        GradientDrawable bg;
        TextView text;
        boolean ios;
        int accent;
    }

    /** 返回可直接 addView 到横向行首的容器；墨读返回 null（调用方跳过 addView）。 */
    static View create(Context c, ModouUi u) {
        DesignSpec sp = u.spec;
        if (sp.itemBadgeStyle == 0) return null;

        boolean ios = sp.itemBadgeStyle == 1;
        int size = ModouUi.dp(c, sp.itemBadgeSizeDp);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ModouUi.dp(c, sp.itemBadgeCornerDp));
        bg.setColor(ios ? IOS_COLORS[0] : ModouUi.alpha(u.accent, 0x1F));

        FrameLayout box = new FrameLayout(c);
        box.setBackground(bg);

        TextView text = new TextView(c);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(false);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp.itemBadgeTextSp);
        text.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        text.setTextColor(ios ? 0xFFFFFFFF : u.accent);
        box.addView(text, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = ModouUi.dp(c, sp.itemBadgeGapDp);
        box.setLayoutParams(lp);

        Parts p = new Parts();
        p.bg = bg; p.text = text; p.ios = ios; p.accent = u.accent;
        box.setTag(p);
        return box;
    }

    /**
     * 把一行数据刷进复用的字母块。seed 优先用稳定标识（书源 URL / 规则 id），
     * 保证同一个源滚回来颜色不变；name 只用来取首字。
     */
    static void bind(View box, String name, String seed) {
        if (box == null) return;
        Object t = box.getTag();
        if (!(t instanceof Parts)) return;
        Parts p = (Parts) t;

        p.text.setText(String.valueOf(initial(name, seed)));
        if (p.ios) {
            p.bg.setColor(IOS_COLORS[paletteIndex(seed, name)]);
        } else {
            // M3 恒定 tonal，不随数据变色；仍无条件重设，防复用残留
            p.bg.setColor(ModouUi.alpha(p.accent, 0x1F));
            p.text.setTextColor(p.accent);
        }
    }

    private static int paletteIndex(String seed, String name) {
        String s = (seed != null && !seed.isEmpty()) ? seed : (name == null ? "" : name);
        int h = s.hashCode();
        int i = h % IOS_COLORS.length;
        return i < 0 ? i + IOS_COLORS.length : i;
    }

    /** 首字：去协议与 www.、英文大写化、中文取首字；空值给一个中点占位。 */
    private static char initial(String name, String seed) {
        String s = name == null ? "" : name.trim();
        if (s.isEmpty()) s = seed == null ? "" : seed.trim();
        if (s.startsWith("http://")) s = s.substring(7);
        else if (s.startsWith("https://")) s = s.substring(8);
        if (s.startsWith("www.")) s = s.substring(4);
        if (s.isEmpty()) return '·';
        char c = s.charAt(0);
        if (c >= 'a' && c <= 'z') return (char) (c - 32);
        return c;
    }
}
