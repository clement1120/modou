package cn.modou.app;

/**
 * Material3 设计令牌（零依赖 Java 自绘版）。
 *
 * 墨读的界面是 Java 手绘的，没有 Compose / Material Components 可依赖，
 * 以前圆角、间距、图标尺寸都散落成 dp(c, 14) 这类魔法数字 —— 同一个
 * 「卡片圆角」在不同面板里写出 8 / 14 / 22 好几种值，改一处漏三处，
 * 也没法一眼看出「这两个圆角本该是同一档」。
 *
 * 这里按 M3 的刻度收成一组命名常量：
 * · 形状沿用 M3 shape scale 的分档命名（XS / S / M / L），取值对齐现有视觉，
 *   所以接入后画面零变化，但语义清楚了，以后改一处就能全局生效；
 * · 间距统一走 4dp 网格，不再出现 10 / 11 / 12 这种随手值；
 * · 图标固定 24dp（M3 标准），行内小图标 18dp。
 *
 * 配色刻意不放在这里：阅读态配色在 {@link NativeTheme}，全局主题仍由网页侧
 * 决定，两边共用同一套色值（见 NativeTheme 注释里的 styles.css 对应表）。
 * 若把色再抽一层，就会出现「原生一套、网页一套」，正是之前跨端漂移的根源。
 */
final class ModouTokens {

    private ModouTokens() {
    }

    /* ---------------- 形状（圆角，单位 dp） ---------------- */

    /** 极小圆角：拖拽把手、进度指示条 */
    static final int CORNER_XS = 2;
    /** 小圆角：按钮、chip、输入框等控件 */
    static final int CORNER_S = 8;
    /** 中圆角：卡片、分组容器 */
    static final int CORNER_M = 14;
    /** 大圆角：底部面板 / sheet */
    static final int CORNER_L = 22;

    /* ---------------- 间距（4dp 网格，单位 dp） ---------------- */

    static final int SPACE_1 = 4;
    static final int SPACE_2 = 8;
    static final int SPACE_3 = 12;
    static final int SPACE_4 = 16;

    /* ---------------- 行 / 面板内边距（单位 dp） ---------------- */

    /** 列表行左右内边距 */
    static final int ROW_PAD_H = 14;
    /** 列表行上下内边距 */
    static final int ROW_PAD_V = 11;
    /** 面板左右内边距 */
    static final int PANEL_PAD_H = 16;
    /** 面板顶部内边距 */
    static final int PANEL_PAD_T = 10;
    /** 面板底部内边距 */
    static final int PANEL_PAD_B = 12;

    /* ---------------- 图标（单位 dp） ---------------- */

    /** 标准图标尺寸（M3 规范） */
    static final int ICON = 24;
    /** 行内小图标 */
    static final int ICON_SM = 18;

    /* ---------------- 线条（单位 dp） ---------------- */

    /** 分隔线厚度 */
    static final int DIVIDER = 1;
}
