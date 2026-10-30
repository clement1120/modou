package cn.modou.app;

/**
 * 原生阅读页配色：与网页版 RBG / 墨读配色一一对应（纸白 / 米黄 / 青石 / 夜间）。
 *
 * 只放阅读态需要的几个色，不做主题系统 —— 全局深浅仍由网页侧决定，
 * 打开原生页时把当前 theme 名传进来即可。
 *
 * card / acc 取自 web/styles.css 的 body[data-reader-theme=...] 变量：
 * 工具坞面板用 card、强调色（进度条/激活态）用 acc，保证原生与网页一眼同色。
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
                // --bg #17191D / --card #22252B / --text #C8C4B7 / --text2 #8F8B7E / --acc #B96B4E
                return new NativeTheme(0xFF17191D, 0xFFC9C4B8, 0xFF7A7568, 0x4DB96B4E,
                        0xFFB96B4E, 0xFF22252B, 0xFFB96B4E, true);
            case "cream":
                // --bg #EFE7D2 / --card #F7F1E1 / --text #3A3025 / --text2 #6E5F49 / --acc #A8472A
                return new NativeTheme(0xFFEFE7D2, 0xFF2B251B, 0xFF7A6F5C, 0x4DA8472A,
                        0xFFA8472A, 0xFFF7F1E1, 0xFFA8472A, false);
            case "gray":
                // --bg #E9EAE3 / --card #F3F4EE / --text #2E322B / --text2 #5C6157 / --acc #8C5A3B
                return new NativeTheme(0xFFE9EAE3, 0xFF2B2E29, 0xFF6E746B, 0x4D8C5A3B,
                        0xFF8C5A3B, 0xFFF3F4EE, 0xFF8C5A3B, false);
            case "paper":
            default:
                // --bg #F6F1E5 / --card #FCF8EE / --text #2B251B / --text2 #6F6655 / --acc #AE4A2C
                return new NativeTheme(0xFFF6F1E5, 0xFF2B251B, 0xFF7A6F5C, 0x4DAE4A2C,
                        0xFFAE4A2C, 0xFFFCF8EE, 0xFFAE4A2C, false);
        }
    }
}
