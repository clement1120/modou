package cn.modou.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

/**
 * 0.9.167 · 列表行 leading 图标工厂（Stage 4 第 5 部分）。
 *
 * <p>此前所有列表页共用一张「纯文字行」皮，三套语言只调字号 / 圆角 / 缩进。
 * 这一层把「行的构图」交给语言：
 * <ul>
 *   <li>iOS 26：29dp 彩色圆角方块 + 白色 SF 风格 glyph（设置页标志性构图）；</li>
 *   <li>M3 Expressive：40dp tonal 圆 + 单色 icon（不彩色，靠 surface 层级）；</li>
 *   <li>墨读：{@code listIconStyle == 0} 直接返回 null，工厂一行都不执行。</li>
 * </ul>
 *
 * <p>语义 → 矢量资源 / iOS system color 的映射全部收在这一处；页面只传一个 int key，
 * 不写 {@code if (lang == ...)}，也不碰颜色。新增一行图标 = 加一个 key + 一个 drawable，
 * 全 App 所有列表页自动获得。
 */
final class ListIcon {

    static final int APPEARANCE = 1;   // 主题外观：半明半暗圆
    static final int PAPER      = 2;   // 阅读纸张
    static final int CACHE      = 3;   // 章节缓存
    static final int FILTER     = 4;   // 按条件筛选 / 清理
    static final int STORAGE    = 5;   // 书库占用
    static final int EXPORT     = 6;   // 导出
    static final int IMPORT     = 7;   // 导入
    static final int DANGER     = 8;   // 危险操作（三角警告）
    static final int DIAG       = 9;   // 诊断日志
    static final int CLOCK      = 10;  // 阅读时间
    static final int INFO       = 11;  // 关于 / 说明
    static final int TRASH      = 12;  // 清空（垃圾桶）
    /* 0.9.172：导入页 / 本地书页用到的四枚（文件、文件夹、排序、刷新）。
       与上面 12 枚同一套 24dp / 1.8-1.9 线宽矢量，落在 iOS 色块或 M3 tonal 圆里同档。 */
    static final int FILE       = 13;  // 本地文件（.txt / .epub）
    static final int FOLDER     = 14;  // 书籍文件夹
    static final int SORT       = 15;  // 排序
    static final int REFRESH    = 16;  // 重新扫描

    private ListIcon() { }

    /** 返回可直接 addView 到横向行首的容器；墨读 / key=0 返回 null（调用方跳过）。 */
    static View create(Context c, ModouUi u, int key) {
        DesignSpec sp = u.spec;
        if (key == 0 || sp.listIconStyle == 0) return null;

        boolean ios = sp.listIconStyle == 1;
        int size = ModouUi.dp(c, sp.listIconSizeDp);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ModouUi.dp(c, sp.listIconCornerDp));
        // 0.9.204：iOS 六枚纯饱和系统色同屏出现互相打架，是设置页廉价感主因。
        // 保留分组列表的彩色方块形状，但混入 40% 白降饱和；M3 tonal 圆也再淡一档。
        int base = iosColor(key);
        bg.setColor(ios ? ModouUi.mix(base, 0xFFFFFFFF, 0.40f)
                : ModouUi.alpha(u.accent, 0x14));

        FrameLayout box = new FrameLayout(c);
        box.setBackground(bg);

        ImageView glyph = new ImageView(c);
        int rid = resId(key);
        if (rid != 0) glyph.setImageResource(rid);
        glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 0.9.204：底色变淡后白 glyph 会看不清，iOS 改同色系深色（仍是彩色方块 +
        // 白底分组列表的骨架），M3 维持 accent 单色落在淡 tonal 圆上。
        glyph.setColorFilter(ios ? ModouUi.mix(base, 0xFF000000, 0.28f) : u.accent);
        int gs = ModouUi.dp(c, sp.listIconGlyphDp);
        box.addView(glyph, new FrameLayout.LayoutParams(gs, gs, Gravity.CENTER));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = ModouUi.dp(c, sp.listIconGapDp);
        box.setLayoutParams(lp);
        return box;
    }

    /** iOS system colors：深浅模式都用同一套饱和色，和「设置」一致。 */
    private static int iosColor(int key) {
        switch (key) {
            case APPEARANCE: return 0xFF5856D6;  // indigo
            case PAPER:      return 0xFFFF9500;  // orange
            case CACHE:      return 0xFF30B0C7;  // teal
            case FILTER:     return 0xFF34C759;  // green
            case STORAGE:    return 0xFF007AFF;  // blue
            case EXPORT:     return 0xFF34C759;  // green
            case IMPORT:     return 0xFF007AFF;  // blue
            case DANGER:     return 0xFFFF3B30;  // red
            case DIAG:       return 0xFF8E8E93;  // gray
            case CLOCK:      return 0xFFFF9500;  // orange
            case INFO:       return 0xFF8E8E93;  // gray
            case TRASH:      return 0xFFFF9500;  // orange
            case FILE:       return 0xFF007AFF;  // blue（文件 = 系统蓝）
            case FOLDER:     return 0xFF007AFF;  // blue
            case SORT:       return 0xFF8E8E93;  // gray
            case REFRESH:    return 0xFF007AFF;  // blue
            default:         return 0xFF007AFF;
        }
    }

    private static int resId(int key) {
        switch (key) {
            case APPEARANCE: return R.drawable.ic_ls_appearance;
            case PAPER:      return R.drawable.ic_ls_paper;
            case CACHE:      return R.drawable.ic_ls_cache;
            case FILTER:     return R.drawable.ic_ls_filter;
            case STORAGE:    return R.drawable.ic_ls_storage;
            case EXPORT:     return R.drawable.ic_ls_export;
            case IMPORT:     return R.drawable.ic_ls_import;
            case DANGER:     return R.drawable.ic_ls_danger;
            case DIAG:       return R.drawable.ic_ls_diag;
            case CLOCK:      return R.drawable.ic_ls_clock;
            case INFO:       return R.drawable.ic_ls_info;
            case TRASH:      return R.drawable.ic_ls_trash;
            case FILE:       return R.drawable.ic_ls_file;
            case FOLDER:     return R.drawable.ic_ls_folder;
            case SORT:       return R.drawable.ic_ls_sort;
            case REFRESH:    return R.drawable.ic_ls_refresh;
            default:         return 0;
        }
    }
}
