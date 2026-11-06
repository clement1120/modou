package cn.modou.app;

import android.content.Context;

/**
 * 原生阅读页配色（纸白 / 米黄 / 青石 / 夜间）。
 *
 * 只放阅读态需要的几个色，不做主题系统 —— 全局深浅由「外观」设置决定，
 * 打开原生页时把当前 theme 名传进来即可。
 *
 * 工具坞面板用 card、强调色（进度条/激活态）用 acc。
 *
 * <p>acc 现在四套主题统一是「柔和蓝」：浅色 #4E77A8、夜间 #7FA6D9，与全局强调色同一支。
 * 原来的朱砂/赭石（#AE4A2C 等）只负责纸色主题的暖感，一旦用在进度条和按钮上，
 * 就会和全 App 的蓝调脱节 —— 正文纸色保留暖调，控件一律蓝。
 */
final class NativeTheme {

    final int bg;
    final int text;
    final int sub;
    /** 选区底色：强调色的低透明度版本（番茄/微信一致的观感，跟随后阅读主题） */
    final int sel;
    /** 手柄（竖线 + 圆点）：就是强调色本身，与进度条/激活态同色 */
    final int handle;
    final int card;
    final int acc;
    final boolean dark;

    private NativeTheme(int bg, int text, int sub, int sel, int handle, int card, int acc, boolean dark) {
        this.bg = bg;
        this.text = text;
        this.sub = sub;
        this.sel = sel;
        this.handle = handle;
        this.card = card;
        this.acc = acc;
        this.dark = dark;
    }

    static NativeTheme of(String name) {
        String n = name == null ? "" : name;
        switch (n) {
            case "dark":
                // 夜间：近黑底 #121212 + 深灰卡 #1C1C1E，与 App 深色同一套灰阶（原来的
                // #17191D/#22252B 偏冷蓝，切到夜间整页发青）
                return new NativeTheme(0xFF121212, 0xFFE6E2D8, 0xFF8E8A7C, 0x4D7FA6D9,
                        0xFF7FA6D9, 0xFF1C1C1E, 0xFF7FA6D9, true);
            case "cream":
                // 晨光米：慵懒暖黄羊皮纸 #F7EDD9，正文暖褐 #4A3F35（其余色沿用原值）
                return new NativeTheme(0xFFF7EDD9, 0xFF4A3F35, 0xFF8A8378, 0x4D4E77A8,
                        0xFF4E77A8, 0xFFFBF8F1, 0xFF4E77A8, false);
            case "gray":
                // 远山青：淡青绿 #E3EBE4，正文墨绿灰 #2F3B32，与素月白/晨光米明显拉开色相
                return new NativeTheme(0xFFE3EBE4, 0xFF2F3B32, 0xFF6E7A70, 0x4D4E77A8,
                        0xFF4E77A8, 0xFFEFF4F0, 0xFF4E77A8, false);
            case "paper":
            default:
                // 素月白：冷调浅灰白 #F7F8FA（彻底去掉复古牛皮纸黄，与 ColorOS 的干净白底同源）
                // 正文深灰 #2C2C2C 而非纯黑：降低对比度，长看不刺眼。
                return new NativeTheme(0xFFF7F8FA, 0xFF2C2C2C, 0xFF74777C, 0x4D4E77A8,
                        0xFF4E77A8, 0xFFFFFFFF, 0xFF4E77A8, false);
        }
    }

    /** 四档纸张 id，顺序与 {@link DesignSpec#PAPER_IDS} 一致（画廊 / 迷你预览共用） */
    static final String[] IDS = {"paper", "cream", "gray", "dark"};

    /**
     * 当前生效的纸张 id：夜间由整 App 深浅决定；浅色读 {@code readerLightTheme}。
     *
     * <p>{@code readerLightTheme} 是纸张偏好的<b>唯一持久键</b>：{@link ReaderLaunch}
     * 从它构造阅读页 payload 的 {@code theme} 字段。排版面板的 segRow 与画廊页
     * 都只写这一个键，不存在「改了这边那边没换」。
     */
    static String currentName(Context c) {
        if (ModouUi.isDark(c)) return "dark";
        String s = "paper";
        try {
            s = ModouStore.get(c).settingStr("readerLightTheme", "paper");
        } catch (Throwable ignore) {
            /* 库暂不可用：退回默认纸 */
        }
        if (!"cream".equals(s) && !"gray".equals(s)) s = "paper";
        return s;
    }

    /**
     * 落盘一张纸（0.9.161）：写 {@code readerLightTheme} + 整 App 深浅联动。
     *
     * <p>语义与阅读页 {@code applyTheme} 完全一致（历史行为，零回归）：选夜间 →
     * 全局深色；选浅色纸 → 全局浅色，且夜间不覆盖上次的浅色纸。深浅 / 跟随系统
     * 归「主题外观」画廊管，这里只做联动，不另开一处设置。
     */
    static void save(Context c, String name) {
        boolean dark = "dark".equals(name);
        if (!dark) {
            String light = "paper";
            if ("cream".equals(name) || "gray".equals(name)) light = name;
            try {
                ModouStore.get(c).putSettingStr("readerLightTheme", light);
            } catch (Throwable ignore) {
                /* 库暂不可用：下次启动以旧值为准 */
            }
        }
        ModouUi.saveAppTheme(c, dark ? "dark" : "light");
    }
}
