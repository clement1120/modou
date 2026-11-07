package cn.modou.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 三套设计语言的规格总表（0.9.158）。
 *
 * <p><b>为什么有这个文件</b>：本 App 的界面是 Java 手绘的，配色原先直接写在
 * {@link ModouUi} 的构造里、形状写在一组静态常量里、弹窗位置写死在
 * {@code ModouUi.sheetImpl} 里。要支持「墨读 / Material 3 / iOS 26」三套设计语言，
 * 如果各页各写 {@code if (lang == ...) }，很快就会变成三套互不相干的代码，
 * 改一处漏三处 —— 正是用户明确反对的「打补丁」。
 *
 * <p><b>做法</b>：把「这套语言长什么样」全部收成一张表，组件工厂只读表、不判断语言。
 * 于是新增/调整一套语言只改本文件，全 App 一改全改；各 Activity 的调用点零改动。
 *
 * <p><b>正交维度</b>：设计语言（3 种）与深浅（light / dark / auto）是两件事，
 * 规格由 {@code (lang, dark)} 二元组决定，共 3 × 2 种取值。阅读页纸张配色另有
 * {@link NativeTheme}，不受本表影响（但阅读页控件要读本表）。
 *
 * <p>完整规范、阶段划分与进度见项目根目录的《三主题设计语言-进度台账.md》。
 * 本文件里的数值是**定稿**，调整前先改台账，别凭感觉微调。
 */
final class DesignSpec {

    /* ==================== 语言枚举 ==================== */

    enum Lang {
        MODOU("modou", "墨读", "流体玻璃 · 悬浮浮岛"),
        MATERIAL("material", "M3 Expressive", "色调分层 · 弹性形状"),
        MONOLITH("monolith", "极简·纪", "莫兰迪色 · 胶囊浮岛");

        final String id;
        final String label;
        final String tagline;

        Lang(String id, String label, String tagline) {
            this.id = id;
            this.label = label;
            this.tagline = tagline;
        }
    }

    /* ==================== 策略枚举 ==================== */

    /** 弹层锚点：悬浮底部卡片 / 屏幕居中 / 贴底全宽 / 贴底两侧内缩 */
    enum SheetAnchor { FLOAT_BOTTOM, CENTER, BOTTOM_FULL, BOTTOM_INSET }

    /** 弹层按钮排布：填充并排 / 右下文字按钮 / 分隔线整行堆叠 */
    enum ButtonStyle { FILLED_PAIR, TEXT_RIGHT, IOS_STACK }

    /**
     * 弹层语义（0.9.162 Stage 3）：同一个 {@code sheetImpl} 靠 anchor 只能决定「在哪、
     * 多宽、什么材质」，决定不了「内部怎么组织」。confirm / alert / 纯列表 / 表单各自
     * 在三套语言里有完全不同的内部构造（M3 Dialog 无把手、iOS Action Sheet 独立取消组、
     * iOS Page Sheet 露出上一页……），所以加一层语义，让窗口骨架保持一份、内容按语义分流。
     *
     * <p>所有现有 {@code sheet()/sheetForm()/sheetCompact()/sheetAnchored()} 调用点零改动，
     * 默认 {@link #PANEL}；只有 confirm/alert/sheetList 显式传自己的语义。
     */
    enum SheetSemantic { PANEL, FORM, LIST, CONFIRM, ALERT }

    /** A6 按钮族：Filled / Tonal / Outlined / Text 四档层级（M3 Expressive）。 */
    enum ButtonKind { FILLED, TONAL, OUTLINED, TEXT }

    /** 导航形态：悬浮胶囊 / 贴底导航栏 / 贴底标签栏 */
    enum NavStyle { FLOATING_ISLAND, M3_NAV_BAR, IOS_TAB_BAR }

    /** 菜单形态：锚定玻璃窗 / 不透明填充列表 / 弹出式菜单 */
    enum MenuStyle { GLASS_ANCHOR, FILLED_ANCHOR, LIQUID_POPOVER }

    /** 材质：玻璃 + 系统模糊 / 实色 + 色调层级 / 液态玻璃 */
    enum Material { GLASS, TONAL, LIQUID }

    /* ==================== A8：完整 type scale（0.9.163 · Stage 4） ==================== */

    /**
     * Material 3 官方 type scale 的 15 个角色（display / headline / title / body / label
     * 各 L/M/S）。三套语言都填同一张表：M3 用官方 Roboto 字阶与精确 letterSpacing，
     * iOS 用 SF Pro / Dynamic Type 对应档位，墨读沿用 0.9.162 既有字号。
     *
     * <p>组件工厂只按角色取「字号 / 字重 / 字距」，不再散落写死数值 —— 这是完整的
     * 排版系统，而不是「三套各换个字号」。{@link #applyTypeScale()} 会把表里对应档位
     * 派生到 {@code pageTitleSp / rowTitleSp / ...} 等既有字段，所有调用点零改动。
     */
    enum TypeRole {
        DISPLAY_L, DISPLAY_M, DISPLAY_S,
        HEADLINE_L, HEADLINE_M, HEADLINE_S,
        TITLE_L, TITLE_M, TITLE_S,
        BODY_L, BODY_M, BODY_S,
        LABEL_L, LABEL_M, LABEL_S
    }

    /**
     * 字阶表。weight 编码沿用本文件既有约定：0 = normal，1 = bold，2 = medium(500)，
     * 3 = semibold(600，纯 Java 下用 bold 近似)。tracking 单位是 em（TextView 的
     * {@code setLetterSpacing} 单位），不是 dp / px。
     */
    static final class TypeScale {
        private final float[] size, tracking;
        private final int[] weight;

        TypeScale(float[] size, int[] weight, float[] tracking) {
            if (size == null || weight == null || tracking == null
                    || size.length != 15 || weight.length != 15 || tracking.length != 15) {
                throw new IllegalArgumentException("type scale must define exactly 15 roles");
            }
            this.size = size;
            this.weight = weight;
            this.tracking = tracking;
        }

        float size(TypeRole r) { return size[r.ordinal()]; }
        int weight(TypeRole r) { return weight[r.ordinal()]; }
        float tracking(TypeRole r) { return tracking[r.ordinal()]; }
    }

    /* ==================== 持久化 ==================== */

    private static final String PREFS = "modou";
    private static final String PREF_LANG = "designLang";
    /** 0.9.184：强调色覆盖（0 = 跟随设计语言默认色） */
    private static final String PREF_ACCENT = "accentColor";
    /** 构造期读取的一次性覆盖值：{@link #of(Context, boolean)} 填，构造函数消费 */
    private static int sAccent;
    /** 网页版同步用的 settings 表键与 JS 钩子（Stage 6 接上） */
    static final String SETTING_KEY = "designLang";

    static Lang current(Context c) {
        if (c == null) return Lang.MODOU;
        try {
            return parse(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(PREF_LANG, Lang.MODOU.id));
        } catch (Throwable e) {
            return Lang.MODOU;
        }
    }

    static Lang parse(String id) {
        if (Lang.MATERIAL.id.equals(id)) return Lang.MATERIAL;
        if (Lang.MONOLITH.id.equals(id)) return Lang.MONOLITH;
        return Lang.MODOU;
    }

    /**
     * 切换设计语言：三处一起写，少一处就会出现「这边换了那边没换」。
     * 与 {@code SettingsPanel.setTheme} 的深浅链路完全对称。
     */
    static void save(Context c, Lang lang) {
        Lang l = lang == null ? Lang.MODOU : lang;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_LANG, l.id).apply();
        try {
            ModouStore.get(c).putSettingStr(SETTING_KEY, l.id);
        } catch (Throwable ignore) {
            /* 数据库暂不可用：下次启动以 pref 为准 */
        }
        // 0.9.175：这里原来还有一句 `window.__modouSaveDesignLang(...)` 的 JS 钩子。
        // 网页版书架已退役（web/index.html 现在只是隐藏 WebView 的引擎宿主，没有 UI），
        // 钩子没有宿主、每次切换都在跑一个空 eval，故删除。settings 表那一份仍要写：
        // 迁移 / 诊断 / 将来的多端同步都读它。
    }

    /** 供 {@link ModouUi} 比对「建页时的语言/深浅」与「此刻」是否一致 */
    static String stateKey(Context c, boolean dark) {
        // 0.9.184：强调色也算进状态键 —— 换了颜色，其它页面在 onResume 会自动重建
        return current(c).id + ":" + (dark ? "d" : "l") + ":" + currentAccent(c);
    }

    /* ---------- 0.9.184：强调色 ---------- */

    /** 用户选的强调色；0 = 跟随设计语言的默认强调色 */
    static int currentAccent(Context c) {
        if (c == null) return 0;
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PREF_ACCENT, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 写入强调色并让所有页面在下次 onResume 重建（color = 0 表示恢复默认） */
    static void saveAccent(Context c, int color) {
        if (c == null) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(PREF_ACCENT, color).apply();
    }

    private static float luminance(int c) {
        return (0.2126f * ((c >> 16) & 0xFF) + 0.7152f * ((c >> 8) & 0xFF)
                + 0.0722f * (c & 0xFF)) / 255f;
    }

    /** 深色主题下把强调色调亮一点，否则压在深底上发闷 */
    private static int brighten(int c, float amt) {
        int a = (c >>> 24) & 0xFF, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r = (int) Math.min(255, r + (255 - r) * amt);
        g = (int) Math.min(255, g + (255 - g) * amt);
        b = (int) Math.min(255, b + (255 - b) * amt);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 只依赖 dark，不回调 {@code ModouUi.isDark}，避免构造期循环 */
    static DesignSpec of(Context c, boolean dark) {
        sAccent = currentAccent(c);
        return new DesignSpec(c == null ? Lang.MODOU : current(c), dark);
    }

    /**
     * 按「指定语言」取规格，不读当前偏好（0.9.160 主题画廊的缩略预览用）。
     * 预览卡要同时画出三套语言，且必须与真实 UI 同源：这里读到的色板 / 形状 /
     * 导航参数就是那个语言实际生效的值，规格一改预览自动跟着改。
     */
    static DesignSpec of(Lang lang, boolean dark) {
        return new DesignSpec(lang == null ? Lang.MODOU : lang, dark);
    }

    static DesignSpec of(Context c) {
        return of(c, ModouUi.isDark(c));
    }

    /* ==================== 规格字段 ==================== */

    final Lang lang;
    final boolean dark;
    /** 主题画廊页的专属规格（0.9.160），见下方 {@link Gallery} */
    final Gallery gallery;
    /** 阅读纸张画廊页规格（0.9.161）：视觉全部继承 gallery，只覆盖文案与网格 */
    final PaperGallery paperGallery;

    /* ---------- 色板 ---------- */
    int bg;              // 页面底
    int surface;         // 卡片 / 分组容器（ModouUi.card）
    int surfaceHigh;     // 弹层等高层容器
    int surfaceLow;      // 低层容器 / 槽底
    int text;            // 主文
    int sub;             // 次文
    int line;            // 分隔线
    int accent;          // 强调色
    int onAccent;        // 强调色之上的文字
    int danger;          // 危险操作
    int container;       // 强调色容器（选中态 / indicator）
    int onContainer;     // 容器之上的文字
    int divider;         // 行间分隔线（可与 line 不同）
    int ripple;          // 按压水波 / state layer
    int glassBase;       // 玻璃材质基色
    int glassVeil;       // 玻璃薄纱
    int glassRim;        // 边缘高光色
    int scrimColor;      // 遮罩色
    float scrim;         // 遮罩浓度
    boolean gradientBackdrop;   // 页面底衬是否走渐变/光斑

    /* ---------- 形状 ---------- */
    int cornerXs;        // 极小圆角（把手 / 进度条）
    int cornerS;         // 小圆角（控件）
    int cornerM;         // 中圆角（卡片）
    int cornerL;         // 大圆角（浮岛 / 通用弹层）
    int cornerSheet;     // 贴底/表单弹层圆角
    int cornerAlert;     // 居中弹层圆角
    int cornerCard;      // 卡片实际圆角
    int cornerButton;    // 按钮圆角（999 = 全圆胶囊）
    int cornerMenu;      // 菜单圆角
    boolean squircle;    // true = 连续曲率（ModouShape）；false = 普通圆弧
    boolean capsuleButton;
    float cardStrokeDp;  // 卡片描边（0 = 无）
    float cardElevationDp;

    /* ---------- 排版 ---------- */
    float pageTitleSp;
    float sheetTitleSp;
    float rowTitleSp;
    float rowHintSp;
    float groupTitleSp;
    float navLabelSp;
    float bodySp;
    boolean pageTitleSerif;
    boolean sheetTitleSerif;
    boolean sheetTitleCenter;
    float pageTitleTracking;
    /** 0 = normal，1 = bold，2 = medium */
    int pageTitleWeight;
    int sheetTitleWeight;

    /* ---------- A8：字阶派生字段（0.9.163） ----------
       上面这些「散字段」仍保留（调用点零改动），但全部由 {@link #applyTypeScale()}
       从 15 档 {@link TypeScale} 派生。新补的是先前完全没有的字重与字距维度：
       M3 bodyLarge 的 0.031em、labelMedium 的 0.042em、iOS Large Title 的 −0.01em
       这类官方精确值，只有落成系统字阶才谈得上「最高程度还原」。 */
    final TypeScale type;
    float rowTitleTracking, rowHintTracking, groupTitleTracking,
            sheetTitleTracking, buttonTracking;
    int rowTitleWeight, groupTitleWeight;

    /* ---------- 度量（dp / sp 由调用方换算） ---------- */
    int rowPadH;
    int rowPadV;
    int panelPadH;
    int panelPadT;
    int panelPadB;
    int topInset;
    int rowMinHeight;
    int groupGap;
    int dividerInset;
    int dividerDp;

    /* ---------- 0.9.167：列表行构图（三语言级差异，不再只是调参数） ----------
       此前几轮只改字号 / 圆角 / 缩进 / 行高，三套语言共用同一张「纯文字行」皮：
       左「标题 + 说明」、右一个值、组卡一张描边卡片。整页观感因此始终没变。
       这一组规格把「行的构图」本身交给语言：leading 图标容器、行尾 disclosure、
       分隔线起点 / 有无、组卡内缩与圆角节奏。页面只读字段，不写 if (lang == ...)。
       墨读全部取 0 / 原值，ModouUi 在这些值下完全走原路径（逐像素零回归）。 */
    int     listIconStyle;       // 0=无 / 1=iOS 彩色圆角方块 / 2=M3 tonal 圆
    int     listIconSizeDp;      // leading 容器边长
    int     listIconCornerDp;    // 容器圆角（iOS 圆角方 / M3 全圆）
    int     listIconGlyphDp;     // 容器内 glyph 尺寸
    int     listIconGapDp;       // 图标与文字间距
    boolean listChevron;         // 行尾是否画 disclosure ›
    float   listChevronSp;
    int     listGroupInsetDp;    // 组卡 / 组标题左右外边距（iOS inset grouped）
    int     listDividerStartDp;  // 分隔线左缩进（0 = 沿用 dividerInset）
    boolean listNoDivider;       // 完全不画组内分隔线（M3 靠 surface 分层）
    int     listCardCornerDp;    // 组卡圆角覆盖（0 = 沿用 cornerCard）
    int     listRowMinHDp;       // 带图标行最小高度（0 = 沿用 rowMinHeight）

    /* ---------- 0.9.168：动态列表项构图（书源名 / 规则名 / 搜索词） ----------
       list* 管的是「固定功能行」——语义明确，可以用 12 枚固定图标。书源 / 规则 /
       搜索结果这类行里是动态数据，硬塞一枚固定图标语义是错的，137 个书源会长得
       一模一样。这里改用「数据驱动的字母块」：取名称首字、颜色由名称 hash 派生，
       语义来自数据本身。iOS = system color 彩色圆角方块 + 白字（通讯录头像逻辑），
       M3 = tonal 圆 + accent 单色字（M3 avatar 规范），墨读全 0 → 无块、无间距、
       分隔线与圆角逐像素沿用 0.9.167。工厂见 ItemBadge，页面只读字段。 */
    int     itemBadgeStyle;      // 0=无 / 1=iOS 彩色圆角方块 / 2=M3 tonal 圆
    int     itemBadgeSizeDp;     // 字母块边长
    int     itemBadgeCornerDp;   // 字母块圆角（iOS 圆角方 / M3 全圆）
    float   itemBadgeTextSp;     // 首字字号
    int     itemBadgeGapDp;      // 字母块与文字间距
    int     itemCardGapDp;       // 独立项卡之间的纵向间距（M3 filled card；0=紧贴分组）
    int     itemCardCornerDp;    // 单项卡圆角（0 = 沿用 cornerCard）
    boolean itemCardNoStroke;    // M3 filled card 不描边（靠 surface 色分层）
    int     itemDividerStartDp;  // 分组内分隔线左缩进（从字母块之后起；0 = 沿用旧值）

    /* ---------- 0.9.169：搜索页构图 ----------
       此前搜索页三套共用同一个 glass(999) 玻璃搜索框 + 28sp 衬线老标题 + 裸图标
       历史区 + 写死的结果行背景，是三套主题里「换皮最纯」的一页。搜索框恰恰是
       三套设计语言各自都有强规范的组件：iOS 26 search field = 36dp / 10dp 圆角矩形 /
       tertiarySystemFill；M3 search bar = 56dp 全圆胶囊 / surfaceContainerHigh +
       elevation；墨读 = 48dp 玻璃胶囊。这组规格把「搜索页怎么构图」交给语言，
       页面只读字段、不写 if (lang == ...)。墨读全部取原值 / 0 → 逐像素零回归。 */
    int     searchBarStyle;          // 0=墨读玻璃胶囊 / 1=iOS search field / 2=M3 search bar
    int     searchBarHDp;            // 搜索框高度
    int     searchBarCornerDp;       // 搜索框圆角
    int     searchBarIconDp;         // leading 放大镜尺寸
    float   searchBarTextSp;         // 输入字号
    float   searchBarElevDp;         // M3 tonal elevation（dp）
    int     searchCoverCornerDp;     // 结果封面圆角（0 = 沿用旧 12）
    int     searchHitCardCornerDp;   // 结果行卡圆角（0 = 无卡背景，透明行 + 涟漪）
    boolean searchHitCardFilled;     // 结果行是否铺 filled card（M3）
    int     searchHitGapDp;          // 结果卡纵向间距（M3 filled card；0=紧贴）
    int     searchHitDividerStartDp; // 结果行分隔线左缩进（iOS 从封面之后起；0=不画线）
    float   searchTagCornerDp;       // 结果行标签圆角
    boolean searchTagStroke;         // 标签是否描边（M3 assist chip）

    /* ---------- 0.9.170：搜索框焦点态 ----------
       0.9.169 只做了「静态构图」，但搜索框是三套语言里焦点态差异最明显的组件：
       iOS 26 UISearchBar 未聚焦时「放大镜 + 占位符」整体居中、无 Cancel，聚焦后
       占位靠左、右侧滑入蓝色 Cancel（搜索框平滑让出宽度）；M3 search bar 聚焦时
       tonal elevation 从 3dp 升到 6dp、底色从 surfaceContainerHigh 升到 Highest；
       墨读没有焦点态概念，style=0 时 SearchActivity 连监听都不注册，逐像素零回归。
       页面只读这两个字段，不写 if (lang == ...)。 */
    int     searchFocusStyle;        // 0=无焦点态(墨读) / 1=iOS 居中提示+Cancel / 2=M3 tonal 提升
    float   searchFocusElevDp;       // M3 聚焦态 elevation（dp）；其余 0

    /* ---------- 0.9.171：书详情页构图 ----------
       书详情是三套主题里最后一块「完全没换」的高频页：此前 BookActivity 的
       ui.spec 使用数为 0 —— hero 封面 / 24sp 衬线书名 / 全圆胶囊标签 / 18dp
       独立动作块 / 卡内 hairline / 白色浮片 FAB 全是墨读写死值，三套只有颜色
       跟着变。这组规格把「书详情怎么构图」交给语言，页面只读字段、不写
       if (lang == ...)。墨读全部取原值 → 逐像素零回归。 */
    int     bookStyle;               // 0=墨读原样 / 1=iOS 26 / 2=M3 Expressive
    int     bookCoverWDp;            // 封面宽
    float   bookCoverCornerDp;       // 封面圆角
    float   bookCoverElevDp;         // 封面 elevation
    int     bookCoverPlace;          // 0=墨读写死米色 / 1=sysFill3 / 2=surfaceContainerHigh
    float   bookTitleSp;             // 书名字号
    int     bookTitleFace;           // 0=SERIF BOLD / 1=SANS BOLD / 2=SANS MEDIUM
    float   bookTagCornerDp;         // 标签圆角
    boolean bookTagStroke;           // 标签描边（M3 assist chip）
    int     bookTagFill;             // 0=alpha(text,0x0E) / 1=sysFill2 / 2=surfaceContainerHigh
    float   bookActionCornerDp;      // 动作块圆角
    int     bookActionStyle;         // 0=4 独立卡 / 1=iOS 连续分组卡 / 2=M3 tonal 块
    int     bookActionBg;            // 0=ui.card / 1=sysFill2 / 2=surfaceContainerHigh
    boolean bookCardHairline;        // 卡内是否画细线（M3 靠 tonal 分层，不画）
    int     bookFabStyle;            // 0=白色浮片描边 / 1=iOS 蓝填充 / 2=M3 tonal FAB
    float   bookTocBarCornerDp;      // 目录条圆角

    /* ---------- 0.9.172：导入页 / 本地书页构图 ----------
       这两页是 Stage 4 里最后两块「ui.spec 使用数为 0」的主区：导入页把「选文件夹」
       做成两张 16dp 墨读卡 + 两颗墨读按钮，本地书页是「一页胶囊按钮 + 一排独立小卡」。
       三套语言在这里的分歧不是配色，而是**信息组织方式**：
         · iOS 26  —— inset grouped 分组表格：白卡 + 44/46dp 行 + 行尾 › + 12sp 组脚注，
                      动作直接由「点这一行」承担，页面上没有按钮；
         · M3 Expressive —— 每项一张 filled card（surfaceContainerLow）+ 40dp Filled /
                      Outlined 按钮，卡内标题 titleMedium、说明 bodyMedium；
         · 墨读 —— 保持原来的两张圆角卡 + 填充主按钮 + 描边次按钮。
       页面只读字段，不写 if (lang == ...)；墨读全部取原值 → 逐像素零回归。 */
    int     ioStyle;                 // 两页共用：0=墨读 / 1=iOS 26 / 2=M3 Expressive
    /* 导入页 */
    float   ioTitleSp;               // 页面标题字号（26 衬线 / 34 大标题 / 22 顶栏）
    int     ioTitleWeight;           // 0 normal / 1 bold / 2 medium
    boolean ioTitleSerif;            // 标题是否衬线（只有墨读）
    float   ioIntroSp;               // 引导段字号（13 / 15 / 14）
    float   ioFootSp;                // 组脚注 / 说明字号（12 / 12 / 12）
    int     ioCardCornerDp;          // 卡 / 分组圆角（16 / 12 / 12）
    int     ioCardGapDp;             // 卡与卡之间（12 / 18 / 8）
    int     ioCardPadH;              // 卡内左右留白
    int     ioCardPadT;              // 卡内上留白
    int     ioCardPadB;              // 卡内下留白
    int     ioCardBg;                // 0=ui.card / 1=分组白卡（iOS）/ 2=surfaceContainerLow
    boolean ioRowChevron;            // 行尾 ›（iOS 点整行即动作）
    int     ioRowHDp;                // 行高（0 = 随内容）
    int     ioBtnHDp;                // 按钮高度（0 = 靠 padding 撑开）
    /* 本地书页 */
    int     localHeadHDp;            // 顶栏行高（46 / 44 / 64）
    float   localNavTitleSp;         // 顶栏标题字号（21 / 17 / 22）
    int     localNavTitleWeight;     // 0 normal / 1 bold / 2 medium
    boolean localLargeTitle;         // 正文区是否再放大标题（iOS Large Title）
    float   localLargeTitleSp;       // 大标题字号（34）
    int     localHeadAction;         // 0=玻璃文字胶囊 / 1=iOS 蓝文字 / 2=M3 图标钮
    float   localSubSp;              // 副标题（文件夹 · N 本）字号
    int     localRowStyle;           // 0=独立卡 / 1=iOS 连续分组 / 2=M3 filled 卡
    int     localRowCornerDp;        // 行 / 组圆角
    int     localRowGapDp;           // 行间距（0 = 连续分组，靠分隔线）
    int     localRowPadH;            // 行左右留白
    int     localRowPadV;            // 行上下留白
    int     localRowMinHDp;          // 行最小高度（0 = 不限制）
    float   localNameSp;             // 文件名字号（15 / 17 / 16）
    int     localNameWeight;         // 0 normal / 1 bold / 2 medium
    float   localTagSp;              // 「已导入」标记字号（12.5 / 12 / 11）
    int     localIconStyle;          // 0=无 / 1=iOS 文件色块 / 2=M3 tonal 圆
    int     localIconDp;             // 图标容器边长（29 / 40）
    int     localIconCornerDp;       // 容器圆角（7 / 999）
    int     localGlyphDp;            // 容器内图标尺寸（18 / 22）
    int     localTagStyle;           // 0=纯文字 / 1=iOS 胶囊 / 2=M3 chip
    int     localAddStyle;           // 0=描边圆＋ / 1=iOS 蓝＋（无圈）/ 2=tonal 填充圆
    int     localEmptyStyle;         // 0=虚线圆＋ / 1=iOS 符号+文字钮 / 2=M3 插画+按钮
    int     localSelBarStyle;        // 0=卡片 / 1=iOS 工具条 / 2=M3 底栏
    int     localDividerStartDp;     // 连续分组分隔线左缩进（0 = 不画）

    /* ---------- 0.9.173：chip / 小按钮收口（A6 补充） ----------
       工具栏上的「已激活条件」「清正文 / 清目录」这类小控件此前是三处各写一套：
       999 胶囊 + 6% 墨色、12.5sp、padding 14/6 —— 三套语言共用同一颗。
       这里把「小控件」也交给语言：iOS 是 systemFill 胶囊 + 12sp；M3 是 8dp 描边
       chip + 11sp（选中换成 secondaryContainer tonal）；墨读保持原值。 */
    int     chipStyle;          // 0=墨读 / 1=iOS / 2=M3
    float   chipCornerDp;       // 999 / 999 / 8
    float   chipLabelSp;        // 12.5 / 12 / 11
    float   chipPadHDp;         // 水平内边距
    float   chipPadVDp;         // 垂直内边距
    boolean chipStroke;         // 是否描边（M3 outlined chip）
    int     chipFill;           // 0=alpha(text,0x0F) / 1=sysFill2 / 2=透明
    int     chipActiveFill;     // 0=toneAccent / 1=alpha(sysBlue,0x1F) / 2=secondaryContainer

    /* ---------- 0.9.173：阅读页工具坞（Stage 5 第一刀） ----------
       阅读页的纸张配色与设计语言正交（§2.10），所以工具坞的颜色仍取纸张色板
       （theme.card / acc / sub），这里只规定**形状、材质厚度、字号、行内构图**：
       墨读 = 22dp 玻璃浮卡 + 10.5sp 文字；iOS = 20dp 液态玻璃工具条 + 10sp；
       M3 = 28dp 仅上两角 + tonal 实底 + 12sp Medium + 图标后 32×20 指示器药丸。 */
    int     rdDockStyle;        // 0=墨读 / 1=iOS / 2=M3
    float   rdDockCornerDp;     // 22 / 20 / 28
    boolean rdDockTopOnly;      // M3：只有上两角圆（贴底底栏）
    int     rdDockPadH;         // 卡片左右内边距
    int     rdDockPadV;         // 卡片上下内边距
    float   rdDockLabelSp;      // 图标下文字字号
    int     rdDockLabelWeight;  // 0 normal / 1 bold / 2 medium
    int     rdDockIconDp;       // 图标尺寸
    int     rdDockItemPadV;     // 单项上下内边距
    float   rdDockItemCornerDp; // 选中项底色圆角
    boolean rdDockIndicator;    // M3：选中项在图标后画 tonal 药丸
    float   rdDockMetaSp;       // 章节 / 页码字号

    /* ---------- 0.9.173：阅读页其余控件（滑杆 / TTS 浮条 / 目录抽屉） ---------- */
    int     rdSeekStyle;        // 0=墨读药丸 / 1=iOS 细轨圆点 / 2=M3 细轨 + 端点停靠点
    float   rdSeekTrackDp;      // 轨道高 8 / 4 / 4
    float   rdSeekThumbDp;      // 滑块半径 8 / 7.5 / 9
    boolean rdSeekGlow;         // 墨读的内发光；iOS / M3 无
    boolean rdSeekStopDots;     // M3：轨道两端 2dp 停靠点
    float   rdTtsTitleSp;       // 朗读浮条标题字号 13.5 / 13 / 14
    int     rdTtsTitleWeight;   // 字重 1 bold / 1 / 2 medium
    float   rdTtsLabelSp;       // 上一句 / 暂停 / 下一句 标签 11 / 11 / 12
    int     rdTtsPlayDp;        // 中央播放键 40 / 40 / 48
    boolean rdTtsPlayFill;      // 中央键是否铺 tonal 圆底（M3）
    float   rdTocTitleSp;       // 目录行标题 15 / 17 / 16
    float   rdTocSubSp;         // 目录行副标题 12 / 13 / 12
    int     rdTocPadV;          // 目录行上下留白 11 / 12 / 12
    int     rdTocDividerStartDp;// 目录分隔线左缩进（0 = 不画）
    boolean rdTocSelectedFill;  // M3：当前章节整行 tonal 底

    /* ---------- 弹层 ---------- */
    SheetAnchor sheetAnchor;     // 普通面板 / 表单
    SheetAnchor confirmAnchor;   // 确认与警示
    SheetAnchor listAnchor;      // 纯选择列表
    int sheetWidthDp;            // 悬浮/居中弹层固定宽
    int sheetMaxWidthDp;
    int sheetSideMarginDp;       // 两侧内缩（BOTTOM_INSET）
    float sheetLandRatio;        // 横屏宽度占比
    boolean sheetBlur;           // 是否挂系统跨窗口背景模糊
    boolean sheetHandle;         // 是否有拖动把手
    boolean sheetDraggable;      // 是否可下拉关闭
    boolean sheetTitleDivider;
    int sheetRadiusDp;
    ButtonStyle buttonStyle;
    Material material;

    /* ---------- Stage 3：语义弹层细节（0.9.162） ----------
       全部由 DesignSpec 集中定稿；sheetImpl 只读字段，不写 if(lang==...)。
       「窗口骨架」（gravity / setLayout / 模糊 attach / 拖动 target）不在这里，也不改。 */
    /** M3 Dialog：supporting text 字号、右下按钮区高度、
     *  tonal elevation（靠 primary tint 叠加，不是投影）。iOS alert 无把手、正文居中。 */
    float dialogSupportSp;
    int dialogBtnHeightDp;
    int dialogElevationDp;
    boolean dialogTitleCenter;
    /** iOS Action Sheet：独立「取消」组 + 分组圆角 + 57dp 行 + destructive 红 + 标题弱化 */
    boolean actionSheetSeparateCancel;
    int actionSheetRowDp;      // 57
    int actionSheetGroupRadiusDp;
    int actionSheetCancelGapDp;
    float actionSheetTitleSp;
    float actionSheetItemSp;
    /** iOS Page Sheet：顶部露出上一页（sticky 露出高度 + 圆角），配 grabber 下拉关闭 */
    boolean pageSheetPeek;
    int pageSheetPeekDp;
    /** B2 同心圆角：内部控件圆角 = 外容器圆角 − 内边距，形成等差数列 */
    boolean concentric;
    int concentricInsetDp;
    /** A6 按钮族：M3 labelLarge 字重、高度、最小触控、按压 state layer 档位 */
    int buttonHeightDp;
    float buttonLabelSp;
    int buttonWeight;          // 0 normal / 1 bold / 2 medium(近似)
    int buttonMinWidthDp;
    /** 弹层是否挂入场动画（墨读 false 零回归；M3/iOS true 走全局 spring） */
    boolean sheetEnterAnim;

    /* ---------- Stage 3：A1 / B3 派生色板（0.9.162） ----------
       M3 用 ModouTonal 从 accent 派生整套 tonal palette；iOS 补齐 system 色表。
       墨读不派生（保持 0.9.157 原值，零回归）。 */
    int[] tonalPrimary, tonalSecondary, tonalTertiary, tonalNeutral, tonalNeutralVariant;
    int surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
            surfaceContainerHigh, surfaceContainerHighest;
    int primaryContainer, onPrimaryContainer, secondaryContainer, tertiaryContainer;
    int outline, outlineVariant;
    int stateHover, stateFocus, statePressed, stateDragged;
    int sysFill1, sysFill2, sysFill3, sysFill4;      // iOS systemFill 四档
    int labelSecondary, labelTertiary, labelQuaternary;
    int separatorOpaque, separatorNonOpaque;
    int sysBlue, sysGreen, sysIndigo, sysOrange;     // iOS system colors
    int destructive;                                 // iOS destructive / M3 error

    /* ---------- 导航 ---------- */
    NavStyle navStyle;
    int navHeightDp;
    int navBottomGapDp;
    int navSideGapDp;
    int navCapWidthDp;
    int navIconDp;
    int navCornerDp;
    boolean navIndicator;
    boolean navDivider;
    boolean navBlur;
    boolean navFloating;
    int navFootDp;               // 列表底部要给导航留出的高度

    /* ---------- 菜单 ---------- */
    MenuStyle menuStyle;
    int menuCornerDp;
    int menuRowHeightDp;
    boolean menuBlur;

    /* ---------- 导航细化（Stage 2） ----------
       这些字段让 ShelfActivity 只描述「画一个什么形状的底栏」，不出现任何语言判断。
       墨读沿用它原来的浓淡 + 放大逻辑，取值全部对齐 0.9.157 现状。 */
    int navBarColor;          // 贴底栏底板：M3 实色 surfaceContainer / iOS 半透明毛玻璃
    int navOnColor;           // 选中前景
    int navOffColor;          // 未选中前景
    int navIndicatorColor;    // M3 active indicator 药丸（primaryContainer）
    int navIndicatorWDp;      // 药丸宽（M3 64，其余 0 = 不用）
    int navIndicatorHDp;      // 药丸高（M3 32，其余 0）
    int navDividerColor;      // iOS Tab Bar 顶部分隔线（0 = 不画）
    boolean navScaleSelected; // 墨读选中放大 8%；M3 / iOS 不放大
    int navBarElevationDp;    // M3 Navigation Bar 的 tonal elevation

    /* ---------- 导航材质 / 专属图标（Stage 2 补强 · 最高还原度） ----------
       用户要求「最高程度还原」：M3 的层级靠 tonal elevation（surface 上叠 primary tint），
       iOS 26 的 Tab Bar 是 Liquid Glass 材质（半透基色 + 顶部凝光 + 边缘泛光），
       且三套各自用符合本语言的图标（不是同一套线性图标换 tint）。 */
    boolean navGlassMaterial; // iOS：走 Liquid Glass 材质 Drawable，不是平涂半透明色
    int navGlassHighlight;    // iOS 顶部镜面高光色（0 = 不画）
    int navSurfaceTint;       // M3 tonal elevation：叠在 surface 上的 primary tint（0 = 不叠）
    int[] navIconLine;        // 4 个 tag 的未选中图标（按语言各一套）
    int[] navIconFill;        // 选中图标（M3 filled / iOS 同一枚 SF 符号 / 墨读线性）

    /* ---------- 菜单细化（Stage 2） ----------
       CascadingMenu 的卡片底色/遮罩/描边/圆角/行高全部按这些字段出图，
       原来散在类里的 20dp、0x17000000、1.2dp 硬编码一律收口。 */
    int menuSurface;          // 卡片底板
    int menuScrim;            // 子菜单展开时压在主卡上的退后遮罩
    float menuRimDp;          // 卡片描边（墨读 1.2 / M3 0 / iOS 1 镜面高光）
    int menuElevationDp;      // M3 菜单 elevation 3
    boolean menuPopoverArrow; // iOS popover 指向锚点的小箭头

    /* ---------- 菜单高还原（Stage 2 补充 · 用户「最高程度还原」要求） ----------
       组件只读这些字段出图，CascadingMenu 里不出现任何语言判断。墨读全部对齐
       0.9.158 之前写死的值（零回归）；M3 走 state layer + bodyLarge；iOS 走
       Liquid Glass 高光 + 指向锚点箭头 + 右置 SF 风格图标。 */
    float menuTitleSp;        // 行标题：墨读 14 / M3 16(bodyLarge) / iOS 17(body)
    float menuHintSp;         // 副标题 / 行右数值：11.5 / 14 / 13
    int menuItemIconDp;       // 行图标：0=不画 / M3 24(leading) / iOS 22(trailing)
    boolean menuIconLeading;  // true = M3 左置；false = iOS 右置
    int menuDividerInsetDp;   // 分隔线左缩进：16 / 0 / 16
    int menuStateLayer;       // 按压叠加色（M3 onSurface 10% / iOS 灰 / 墨读 ripple 不用）
    int menuHighlight;        // checked 行底色
    int menuArrowDp;          // popover 箭头高度：0 / 0 / 10
    int menuRimLight;         // iOS 镜面高光描边色（0 = 不画）
    int durMenuIn;            // 入场时长：墨读 150 / M3 250 / iOS 350
    int durMenuOut;           // 出场时长：墨读 130 / M3 200 / iOS 280
    int durMenuSub;           // 子菜单推入时长：墨读 190 / M3 300 / iOS 340
    int menuRowPadLDp;        // 行左内边距：墨读 16 / M3 12 / iOS 16
    int menuRowPadRDp;        // 行右内边距：墨读 14 / M3 12 / iOS 16
    int menuRowPadVDp;        // 行上下内边距：墨读 13 / M3 6 / iOS 5
    float menuRowRadiusDp;    // 行高亮圆角：墨读 14 / M3 8(全圆角块) / iOS 10
    int menuDividerColor;     // 分隔线：墨读 8% 墨 / M3 outline / iOS separator

    /* ---------- 玻璃材质兜底色（Stage 2 收口 ModouGlass 私有常量） ----------
       墨读取 0.9.152 定稿的原值；M3 / iOS 用各自的 surface，避免出现奶油白玻璃。 */
    int glassMaterialVeil;    // GlassMaterial 基底
    int glassSolidFallback;   // 系统跨窗口模糊被禁用时的实底兜底

    /* ---------- 动效 ---------- */
    int durShort;
    int durMed;
    int durLong;
    /** A3/B9 全局 spring：把菜单 / 画廊 / 弹层三处各写一份的插值器收口到一处。
     *  spring=true 走 PathInterpolator 阻尼曲线；overshoot>0 且允许时走 expressive 过冲。 */
    boolean spring;
    float overshoot;

    /* ==================== 主题画廊页规格（0.9.160） ==================== */

    /**
     * 「主题外观」沉浸式选择页的规格（仿参考图的仪式感骨架）。
     *
     * <p>三套语言共享同一套信息架构：眉标 / 诗意大标题 / 副标题 / 正在使用大预览卡 /
     * 深浅三行 / 全部主题三列缩略卡 / 底部胶囊确认条。但每一项视觉表达都由本规格定稿：
     * 墨读是淡绿宣纸渐变 + 宋体墨绿大标题 + 玻璃卡；M3 是纯色 surface + 32sp Medium
     * 标题 + Filled card / active indicator / Filled 胶囊按钮 + expressive spring；
     * iOS 26 是 groupedBackground + 34sp Bold Large Title + Inset Grouped + hairline +
     * 系统蓝填充胶囊 + 阻尼 spring。Activity 只读字段，不写 {@code if (lang == ...)}。
     *
     * <p>{@link #taglines} 按 {@link Lang#values()} 顺序（墨读 / M3 / iOS）。
     */
    static class Gallery {
        /* 文案 */
        String eyebrow, title, subtitle, inUsePrefix, allTitle, allHint;
        String cancel, apply, aboutTitle, aboutBody;
        String footerA, footerB;
        String[] modeNames, modeHints, taglines;
        /* 大标题 */
        float titleSp;
        boolean titleSerif;
        int titleColor;
        float titleTracking;
        int titleWeight;          // 0 = normal / 1 = bold / 2 = medium
        /* 页面底衬与卡片 */
        int backdropA, backdropB; // 相等即纯色
        int cardColor, cardRim;
        float cardRimDp;
        boolean glassCard;
        float cardRadiusDp, thumbRadiusDp;
        /* 选中态 */
        int selectColor, selectFill;
        float selectStrokeDp;
        boolean selectIndicator;  // M3：选中项背后画 active indicator 药丸
        /* 底部操作条与按钮 */
        int barColor, barRim;
        int primaryBtnColor, primaryBtnText, cancelText;
        float btnRadiusDp;
        boolean barGlass;
        /* 0.9.172：画廊页构图（此前三套只换配色/字体，信息架构一模一样） */
        int galleryStyle;         // 0=墨读 / 1=iOS 26 / 2=M3 Expressive
        int galleryGridStyle;     // 0=三列缩略卡 / 1=纵向分组列表（iOS）/ 2=两列 filled 卡（M3）
        boolean galleryWideAction; // 底部是否为一个通栏主按钮（iOS / M3），否则保持胶囊双段
        /* 动效 */
        boolean spring;
        int durIn;
        float overshoot;
        /* 滚动时的 app bar 行为（0.9.160 画廊页落地 §2.11 B5 / A9） */
        boolean largeTitleScroll;   // iOS：Large Title 收起为 inline 标题 + 顶栏 hairline
        boolean appBarTonalScroll;  // M3：顶栏滚动后出现 tonal elevation
        int scrollEdgeDp;           // 顶栏完成浮出所需的滚动距离（scroll edge，0.9.164）
        /* 书架页 B5（0.9.165）：headBox 是 4 页共用的固定工具条，与画廊页「大标题在
         * 内容区」不同，规格单独给开关，页面只读字段、不写 if (lang == ...)。 */
        boolean shelfChrome;        // 是否给书架固定工具条加 scroll edge 覆盖层
        boolean shelfChromeLand;    // 横屏（一行紧凑工具条）是否也加，false 则逐像素保持原样
        /* 书架页真·Large Title（0.9.166）：大标题移进内容区、随滚动被推走，与
         * LargeTitleHeader 同构。展开态高度、是否收起、收起态构图各语言不同，
         * 单独给规格；页面只读字段，不写 if (lang == ...)。 */
        int     shelfTitleBlockDp;   // 展开态大标题区高度（大标题 + 副标题 + 下方留白）
        boolean shelfBigTitleFade;   // 大标题是否随滚动收起（M3 Expressive large top app bar 也为 true）
        boolean shelfInlineCenter;   // 收起态 inline 标题居中（iOS）/ 靠左（M3 top app bar 规约）
        boolean shelfHairline;       // 收起态是否画顶栏 hairline（iOS scroll edge）
        /** 0.9.183：大标题下的「语言专属结构件」。
         *  0 = 无（墨读） / 1 = M3 Expressive 筛选 chips / 2 = iOS 大标题下搜索框。
         *  这是三套语言在书架首屏上唯一「只有自己有」的组件，不是换颜色。 */
        int     shelfHeaderStyle;
    }

    /**
     * 阅读纸张画廊页规格（0.9.161）。
     *
     * <p>与「主题外观」画廊共用同一套<b>视觉规格</b>（底衬 / 卡片曲率 / 选中态 /
     * 按钮 / 动效 / 滚动行为）—— 这是三套设计语言已定稿的部分，两个画廊页必须一致，
     * 改一处两边同步。这里只覆盖<b>文案、网格列数与纸张属性行</b>。
     *
     * <p>「深浅 / 跟随系统」按用户要求只留在主题外观画廊，本页右侧三行改成
     * <b>只读的纸张属性</b>（明暗 / 色温 / 适合），不再出现第二处切换逻辑。
     */
    static final class PaperGallery extends Gallery {
        String[] paperNames, paperHints;
        String paperAllTitle, paperAllHint, paperApply;
        String paperAboutTitle, paperAboutBody;
        String paperFooterA, paperFooterB;
        /** 大预览卡右侧三行：标签 + 每档纸张对应的三行说明 */
        String[] infoLabels;
        String[][] paperInfo;
        int gridColumns;
        float thumbHeightDp;
    }

    /* ==================== 构造：三套数值 ==================== */

    private DesignSpec(Lang lang, boolean dark) {
        this.lang = lang;
        this.dark = dark;
        switch (lang) {
            case MATERIAL:
                fillMaterial(dark);
                break;
            case MONOLITH:
                fillMonolith(dark);
                break;
            case MODOU:
            default:
                fillModou(dark);
                break;
        }
        // 0.9.184：用户选了强调色就在派生 tonal 之前覆盖 —— 这样 M3 的 tonal palette、
        // 导航选中色、系统蓝都会跟着新色走，而不是只改一处强调色。
        applyAccent(dark);
        gallery = new Gallery();        switch (lang) {
            case MATERIAL:
                fillGalleryMaterial(gallery, dark);
                break;
            case MONOLITH:
                fillGalleryMonolith(gallery, dark);
                break;
            case MODOU:
            default:
                fillGalleryModou(gallery, dark);
                break;
        }
        paperGallery = new PaperGallery();
        switch (lang) {
            case MATERIAL:
                fillPaperGalleryMaterial(paperGallery, dark);
                break;
            case MONOLITH:
                fillPaperGalleryMonolith(paperGallery, dark);
                break;
            case MODOU:
            default:
                fillPaperGalleryModou(paperGallery, dark);
                break;
        }
        // 0.9.184：两个画廊页自己的强调色（选中标记 / 主按钮 / 取消文字）也要跟着换，
        // 否则会出现「内容变青了、底下那颗应用主题按钮还是紫色」。
        if (sAccent != 0) {
            int base = accent;
            gallery.selectColor = base;
            gallery.selectFill = alpha(base, 0x1F);
            gallery.primaryBtnColor = base;
            gallery.primaryBtnText = onAccent;
            paperGallery.selectColor = base;
            paperGallery.selectFill = alpha(base, 0x1F);
            paperGallery.primaryBtnColor = base;
            paperGallery.primaryBtnText = onAccent;
        }
        // Stage 3：语义弹层 / 按钮族 / 同心圆角 / 全局 spring 三套一起定稿（集中对照）
        fillStage3();
        // A1/B3：三套基础色板定稿后再派生 tonal palette、state layer、system 色表
        deriveTokens();
        // iOS 的系统蓝在 deriveTokens 里被写回固定值；用户自定义过就跟着强调色走
        if (sAccent != 0 && lang == Lang.MONOLITH) sysBlue = accent;
        // A8：15 档官方字阶统一派生所有排版字段（墨读取值逐项对齐 0.9.162，零回归）
        type = createTypeScale(lang);
        applyTypeScale();
    }

    /**
     * 强调色覆盖（0.9.184）：只换「强调色族」这几个 token，材质 / 形状 / 排版保持各语言原样。
     *
     * <p>必须跑在 {@code deriveTokens()} 之前 —— M3 的 tonal palette 是从主色派生的，
     * 先换色再生派生，整页的色调分层才会跟着新强调色走，而不是只有一枚按钮变色。
     */
    private void applyAccent(boolean dark) {
        if (sAccent == 0) return;
        int base = dark ? brighten(sAccent, 0.30f) : sAccent;
        accent = base;
        onAccent = luminance(base) > 0.55f ? 0xFF14171C : 0xFFFFFFFF;
        container = alpha(base, 0x24);
        onContainer = base;
        // 导航选中药丸（0.9.209）：
        // - M3 active indicator 取 container 快照，这里同步；
        // - 极简（Monolith）药丸要「通透干净」：淡蓝 + 20% alpha，不再被 container 染成浑水；
        // - 墨读（navIndicatorColor==0）保留各自默认，不在此覆盖。
        if (lang == Lang.MATERIAL) {
            navIndicatorColor = container;
        } else if (lang == Lang.MONOLITH) {
            navIndicatorColor = alpha(base, 0x33); // 淡蓝 + 20% alpha，叠加底栏不发灰发脏
        }
        // 导航选中色与各自默认逻辑保持一致：M3 用 onContainer、iOS 用 accent
        if (lang == Lang.MATERIAL) navOnColor = onContainer;
        if (lang == Lang.MONOLITH) navOnColor = accent;
    }

    /**
     * Stage 3 新增字段的三套数值，集中在此而非散进三个 {@code fill*}，方便逐项对照 §2.5。
     * 墨读保持既有浮岛语义（无 Dialog / 无 Action Sheet 分流），零回归。
     */
    private void fillStage3() {
        // ---- 公共安全默认（墨读基线）----
        dialogSupportSp = 14f;
        dialogBtnHeightDp = 40;
        dialogElevationDp = 0;
        dialogTitleCenter = false;
        actionSheetSeparateCancel = false;
        actionSheetRowDp = 0;
        actionSheetGroupRadiusDp = 0;
        actionSheetCancelGapDp = 0;
        actionSheetTitleSp = 13f;
        actionSheetItemSp = 17f;
        pageSheetPeek = false;
        pageSheetPeekDp = 0;
        concentric = false;
        concentricInsetDp = 0;
        buttonHeightDp = 40;
        buttonLabelSp = 14f;
        buttonWeight = 1;
        buttonMinWidthDp = 64;
        sheetEnterAnim = false;   // 墨读零回归：不加入场动画
        spring = false;
        overshoot = 0f;

        switch (lang) {
            case MATERIAL: {
                // A7：M3 Dialog = 28dp / 24sp headlineSmall / 14sp bodyMedium supporting text /
                // 右下 TextButton / 可选居中 icon / tonal elevation 3（不是投影）。
                dialogSupportSp = 14f;
                dialogBtnHeightDp = 40;
                dialogElevationDp = 3;
                dialogTitleCenter = false;         // M3 Dialog 标题左对齐（有 icon 时居中）
                // M3 纯列表 = 贴底全宽 Modal Bottom Sheet，56dp 行，无独立取消组
                actionSheetSeparateCancel = false;
                actionSheetRowDp = 56;
                actionSheetGroupRadiusDp = 0;
                actionSheetItemSp = 16f;
                concentric = false;                // M3 用 shape scale，非同心递减
                // A6：全圆胶囊、labelLarge 500 近似 medium、40dp 高、64dp 最小宽、elevation 0
                buttonHeightDp = 48;
                buttonLabelSp = 14f;
                buttonWeight = 2;
                buttonMinWidthDp = 64;
                sheetEnterAnim = true;             // M3 Dialog fade+scale / Bottom Sheet 上滑 spring
                spring = true;
                overshoot = 1.06f;                 // expressive spring 轻微过冲
                break;
            }
            case MONOLITH: {
                // B7：iOS alert = 270dp 居中 / 14dp / 标题+正文居中 / 分隔线按钮 / 无把手
                dialogSupportSp = 13f;
                dialogBtnHeightDp = 44;
                dialogElevationDp = 0;
                dialogTitleCenter = true;
                // B7：iOS Action Sheet = 独立「取消」组 + 分组圆角 14dp + 57dp 行 + destructive
                actionSheetSeparateCancel = true;
                actionSheetRowDp = 57;
                actionSheetGroupRadiusDp = 16;
                actionSheetCancelGapDp = 8;
                actionSheetTitleSp = 13f;
                actionSheetItemSp = 20f;           // iOS Action Sheet 行文字 20sp
                // B7：iOS Page Sheet 顶部露出上一页 + grabber 下拉关闭
                pageSheetPeek = true;
                pageSheetPeekDp = 10;
                // B2：concentric 圆角（内圆角 = 外圆角 − 内边距）
                concentric = true;
                concentricInsetDp = 4;
                buttonHeightDp = 50;
                buttonLabelSp = 17f;
                buttonWeight = 0;                  // iOS 按钮 Regular
                buttonMinWidthDp = 0;
                sheetEnterAnim = true;             // iOS alert / Action Sheet / Page Sheet spring
                spring = true;
                overshoot = 0f;                    // iOS spring 带阻尼，不过冲
                break;
            }
            case MODOU:
            default:
                // 墨读：底部浮岛，标题 17sp 衬线，填充并排按钮，Decelerate，零改动
                break;
        }
    }

    /** 墨读：现有 ColorOS 17 流体玻璃，所有数值照搬 0.9.157，作为回归基准 */
    private void fillModou(boolean d) {
        // 色板
        bg = d ? 0xFF000000 : 0xFFF1F2F5;
        surface = d ? 0xFF1C1C1E : 0xECFFFFFF;
        surfaceHigh = surface;
        surfaceLow = d ? 0xFF161618 : 0xFFE9EBF0;
        text = d ? 0xFFF2F3F5 : 0xFF101A29;
        sub = d ? 0xFF9B9DA2 : 0xFF5E7089;
        line = d ? 0xFF2C2C2E : 0xFFE7E9ED;
        accent = d ? 0xFF7FA6D9 : 0xFF2F6BF0;
        onAccent = 0xFFFFFFFF;
        danger = d ? 0xFFE5544B : 0xFFD2453C;
        container = alpha(accent, 0x24);
        onContainer = accent;
        // 行间分隔线：墨读是 5% 墨色的极淡线，不是不透明的 line 色
        divider = alpha(text, 0x0D);
        ripple = alpha(text, 0x14);
        glassBase = d ? 0x8C0E0E10 : 0x82FFFFFF;
        glassVeil = d ? 0x0AFFFFFF : 0x1AFFFFFF;
        glassRim = 0x33FFFFFF;
        scrimColor = 0xFF000000;
        scrim = 0.28f;
        gradientBackdrop = true;

        // 形状：连续曲率、大圆角浮岛
        cornerXs = 2;
        cornerS = 8;
        cornerM = 22;
        cornerL = 32;
        cornerSheet = 32;
        cornerAlert = 32;
        cornerCard = 22;
        cornerButton = 8;
        cornerMenu = 20;
        squircle = true;
        capsuleButton = false;
        cardStrokeDp = 0f;
        cardElevationDp = 2f;

        // 排版
        pageTitleSp = 26f;
        sheetTitleSp = 17f;
        rowTitleSp = 14.5f;
        rowHintSp = 11.5f;
        groupTitleSp = 15f;
        navLabelSp = 11f;
        bodySp = 14f;
        pageTitleSerif = false;
        sheetTitleSerif = true;
        sheetTitleCenter = false;
        pageTitleTracking = -0.02f;
        pageTitleWeight = 1;
        sheetTitleWeight = 1;

        // 度量
        rowPadH = 14;
        rowPadV = 14;
        panelPadH = 18;
        panelPadT = 10;
        panelPadB = 12;
        topInset = 16;
        rowMinHeight = 0;
        groupGap = 22;
        dividerInset = 14;
        dividerDp = 1;

        // 列表行构图：墨读保持 0.9.166 的纯文字行，一个像素都不动
        listIconStyle = 0;
        listIconSizeDp = 0; listIconCornerDp = 0; listIconGlyphDp = 0; listIconGapDp = 0;
        listChevron = false; listChevronSp = 0f;
        listGroupInsetDp = 0; listDividerStartDp = 0; listNoDivider = false;
        listCardCornerDp = 0; listRowMinHDp = 0;

        // 动态项构图：墨读全 0 → ItemBadge.create 返回 null、不加间距、
        // 不改圆角 / 描边 / 分隔线，书源 / 规则行逐像素保持 0.9.167。
        itemBadgeStyle = 0;
        itemBadgeSizeDp = 0; itemBadgeCornerDp = 0; itemBadgeTextSp = 0f; itemBadgeGapDp = 0;
        itemCardGapDp = 0; itemCardCornerDp = 0; itemCardNoStroke = false;
        itemDividerStartDp = 0;

        // 搜索页构图：墨读逐像素保持 0.9.168 —— 48dp glass(999) 玻璃胶囊、
        // 28sp 衬线标题、裸 16dp 历史图标、透明结果行 + 涟漪、5dp 圆角标签。
        // style=0 时 SearchActivity 全部走原路径，一个像素都不动。
        searchBarStyle = 0;
        searchBarHDp = 48; searchBarCornerDp = 999; searchBarIconDp = 18;
        searchBarTextSp = 15f; searchBarElevDp = 0f;
        searchCoverCornerDp = 0; searchHitCardCornerDp = 0; searchHitCardFilled = false;
        searchHitGapDp = 0; searchHitDividerStartDp = 0;
        searchTagCornerDp = 5f; searchTagStroke = false;
        // 焦点态：墨读逐像素零回归——SearchActivity 不注册焦点监听、不建提示层/Cancel。
        searchFocusStyle = 0; searchFocusElevDp = 0f;
        // 书详情页：墨读逐像素保持 0.9.170 原值（96dp 封面 / 24sp 衬线 /
        // 全圆胶囊 / 18dp 独立动作块 / 卡内细线 / 白色浮片 FAB）。
        bookStyle = 0;
        bookCoverWDp = 96; bookCoverCornerDp = 14; bookCoverElevDp = 3f;
        bookCoverPlace = 0; bookTitleSp = 24f; bookTitleFace = 0;
        bookTagCornerDp = 999f; bookTagStroke = false; bookTagFill = 0;
        bookActionCornerDp = 18f; bookActionStyle = 0; bookActionBg = 0;
        bookCardHairline = true; bookFabStyle = 0; bookTocBarCornerDp = 10f;
        // 导入页 / 本地书页：墨读逐像素保持 0.9.171 —— 26sp 衬线标题、两张 16dp 圆角卡、
        // 填充 + 描边两颗按钮、46dp 顶栏 + 玻璃文字胶囊、独立小卡列表（12dp / 8dp 间距）、
        // 描边圆＋、虚线圆空态、底部卡片式多选条。style=0 时两页全部走原代码路径。
        ioStyle = 0;
        ioTitleSp = 26f; ioTitleWeight = 1; ioTitleSerif = true;
        ioIntroSp = 13f; ioFootSp = 12f;
        ioCardCornerDp = 16; ioCardGapDp = 12;
        ioCardPadH = 16; ioCardPadT = 14; ioCardPadB = 16;
        ioCardBg = 0; ioRowChevron = false; ioRowHDp = 0;
        ioBtnHDp = 0;
        localHeadHDp = 46; localNavTitleSp = 21f; localNavTitleWeight = 1;
        localLargeTitle = false; localLargeTitleSp = 21f; localHeadAction = 0; localSubSp = 12f;
        localRowStyle = 0; localRowCornerDp = 12; localRowGapDp = 8;
        localRowPadH = 10; localRowPadV = 10; localRowMinHDp = 0;
        localNameSp = 15f; localNameWeight = 0; localTagSp = 12.5f;
        localIconStyle = 0; localIconDp = 0; localIconCornerDp = 0; localGlyphDp = 0;
        localTagStyle = 0; localAddStyle = 0; localEmptyStyle = 0; localSelBarStyle = 0;
        localDividerStartDp = 0;
        // 小控件：墨读原值（999 胶囊 / 12.5sp / 12·6 内边距 / 不描边）
        chipStyle = 0; chipCornerDp = 999; chipLabelSp = 12.5f;
        chipPadHDp = 12; chipPadVDp = 6; chipStroke = false;
        chipFill = 0; chipActiveFill = 0;
        // 阅读页工具坞：墨读原值
        rdDockStyle = 0; rdDockCornerDp = 22; rdDockTopOnly = false;
        rdDockPadH = 8; rdDockPadV = 6; rdDockLabelSp = 10.5f; rdDockLabelWeight = 0;
        rdDockIconDp = 20; rdDockItemPadV = 6; rdDockItemCornerDp = 14;
        rdDockIndicator = false; rdDockMetaSp = 12f;
        // 阅读页其余控件：墨读原值
        rdSeekStyle = 0; rdSeekTrackDp = 8; rdSeekThumbDp = 8; rdSeekGlow = true; rdSeekStopDots = false;
        rdTtsTitleSp = 13.5f; rdTtsTitleWeight = 1; rdTtsLabelSp = 11; rdTtsPlayDp = 40; rdTtsPlayFill = false;
        rdTocTitleSp = 15; rdTocSubSp = 12; rdTocPadV = 11; rdTocDividerStartDp = 0; rdTocSelectedFill = false;

        // 弹层：底部居中的悬浮玻璃浮岛
        sheetAnchor = SheetAnchor.FLOAT_BOTTOM;
        confirmAnchor = SheetAnchor.FLOAT_BOTTOM;
        listAnchor = SheetAnchor.FLOAT_BOTTOM;
        sheetWidthDp = 360;
        sheetMaxWidthDp = 360;
        sheetSideMarginDp = 12;
        sheetLandRatio = 0.40f;
        sheetBlur = true;
        sheetHandle = true;
        sheetDraggable = true;
        sheetTitleDivider = false;
        sheetRadiusDp = 32;
        buttonStyle = ButtonStyle.FILLED_PAIR;
        material = Material.GLASS;

        // 导航：悬浮胶囊浮岛（独立窗口 + 系统模糊）
        navStyle = NavStyle.FLOATING_ISLAND;
        // 0.9.185：50 → 58。浮岛窗口高度按这个值给，50dp 时「图标 + 文字」几乎顶满，
        // 在新机型上文字会贴到玻璃下缘、看着像没被包进去。
        navHeightDp = 58;
        navBottomGapDp = 8;
        navSideGapDp = 28;
        navCapWidthDp = 420;
        navIconDp = 21;
        navCornerDp = 999;
        navIndicator = false;
        navDivider = false;
        navBlur = true;
        navFloating = true;
        navFootDp = 76;      // 6 + 50 + 8 + 12

        // 菜单：锚定玻璃浮动窗口
        menuStyle = MenuStyle.GLASS_ANCHOR;
        menuCornerDp = 20;
        // 0.9.159 对齐现状：墨读菜单行实测最小 52dp，规格表原写 44 会让行变矮（回归）。
        // 三语言分别 52 / 48 / 44，仍符合 §2.7「墨读沿用现有、M3 48、iOS 44」。
        menuRowHeightDp = 52;
        menuBlur = true;

        // 导航细化：浮岛语言不用贴底板/indicator，只有「选中浓淡 + 放大」这一套。
        navBarColor = glassBase;                 // 浮岛语言用不到，给个安全值
        // 原样搬 ShelfActivity 0.9.155 的两支：它们是带 alpha 的 ARGB，浓淡差就是选中态。
        navOnColor = d ? 0xFFFFFFFF : 0xEE14171C;
        navOffColor = d ? 0x80FFFFFF : 0xA614171C;
        navIndicatorColor = 0;
        navIndicatorWDp = 0;
        navIndicatorHDp = 0;
        navDividerColor = 0;
        navScaleSelected = true;
        navBarElevationDp = 0;
        // 浮岛走自己的 dockMaterial 液态玻璃；这套字段用不到，给安全值 + 原有线性图标
        navGlassMaterial = false;
        navGlassHighlight = 0;
        navSurfaceTint = 0;
        navIconLine = new int[]{R.drawable.ic_nav_shelf_line, R.drawable.ic_nav_source_line,
                R.drawable.ic_nav_rule_line, R.drawable.ic_nav_gear_line};
        navIconFill = navIconLine;   // 墨读恒线性，选中只换浓淡

        // 菜单细化：与 CascadingMenu 0.9.158 之前写死的值逐一对应。
        menuSurface = alpha(surface, 0x12);
        menuScrim = 0x17000000;
        menuRimDp = 1.2f;
        menuElevationDp = 0;
        menuPopoverArrow = false;
        // 高还原字段：逐项对齐 0.9.158 现状（零回归）。
        menuTitleSp = 14f;
        menuHintSp = 11.5f;
        menuItemIconDp = 0;                    // 墨读菜单不配图标
        menuIconLeading = true;
        menuDividerInsetDp = 16;
        menuStateLayer = 0;                    // 墨读用圆角限定 ripple，不用叠色
        menuHighlight = alpha(d ? 0xFFF2F3F5 : 0xFF101A29, 0x0D);
        menuArrowDp = 0;
        menuRimLight = 0;
        durMenuIn = 150;
        durMenuOut = 130;
        durMenuSub = 190;
        menuRowPadLDp = 16;
        menuRowPadRDp = 14;
        menuRowPadVDp = 13;
        menuRowRadiusDp = 14f;
        menuDividerColor = alpha(d ? 0xFFF2F3F5 : 0xFF101A29, 0x14);

        // 玻璃材质：0.9.152 定稿的奶油白 / 深灰，及其模糊禁用兜底。
        glassMaterialVeil = d ? 0xB0202024 : 0xA6F7F3EC;
        glassSolidFallback = d ? 0xD0202024 : 0xC8F7F3EC;

        durShort = 180;
        durMed = 160;
        durLong = 300;
    }

    /** Google Material 3 Expressive：色调分层、圆弧、全圆按钮、居中 Dialog、贴底 Navigation Bar */
    private void fillMaterial(boolean d) {
        bg = d ? 0xFF141218 : 0xFFFDF7FF;
        surface = d ? 0xFF211F26 : 0xFFF3EDF7;
        surfaceHigh = d ? 0xFF2B2930 : 0xFFECE6F0;
        surfaceLow = d ? 0xFF1D1B20 : 0xFFF7F2FA;
        text = d ? 0xFFE6E0E9 : 0xFF1D1B20;
        sub = d ? 0xFFCAC4D0 : 0xFF49454F;
        line = d ? 0xFF36343B : 0xFFE7E0EC;
        // 0.9.193：M3 默认强调色由紫改蓝（与 iOS / 墨读一致，全语言默认蓝色）
        accent = d ? 0xFF9DB2FF : 0xFF3B6FE0;
        onAccent = d ? 0xFF0A2A6B : 0xFFFFFFFF;
        danger = d ? 0xFFF2B8B5 : 0xFFB3261E;
        container = d ? 0xFF3C4C8A : 0xFFDDE4FF;
        onContainer = d ? 0xFFDCE2FF : 0xFF00246B;
        divider = line;
        ripple = alpha(text, 0x14);
        glassBase = surfaceHigh;
        glassVeil = 0x00000000;
        glassRim = 0x00000000;
        scrimColor = 0xFF000000;
        scrim = 0.32f;
        gradientBackdrop = false;

        // M3 shape scale：普通圆弧、卡片 12dp、弹层 28dp、按钮全圆
        cornerXs = 4;
        cornerS = 8;
        cornerM = 20;
        cornerL = 28;
        cornerSheet = 28;
        cornerAlert = 28;
        cornerCard = 20;
        cornerButton = 999;
        cornerMenu = 12;
        squircle = false;
        capsuleButton = true;
        cardStrokeDp = 0f;
        cardElevationDp = 1f;

        pageTitleSp = 32f;
        sheetTitleSp = 24f;
        rowTitleSp = 16f;
        rowHintSp = 14f;
        groupTitleSp = 14f;
        navLabelSp = 12f;
        bodySp = 16f;
        pageTitleSerif = false;
        sheetTitleSerif = false;
        sheetTitleCenter = false;
        pageTitleTracking = 0f;
        pageTitleWeight = 2;
        sheetTitleWeight = 0;

        rowPadH = 16;
        rowPadV = 14;
        panelPadH = 24;
        panelPadT = 16;
        panelPadB = 24;
        topInset = 16;
        rowMinHeight = 56;
        groupGap = 24;
        dividerInset = 16;
        dividerDp = 1;

        // 列表行构图（M3 Expressive）：40dp tonal 圆 + 单色 icon、64dp 行、
        // 不画组内分隔线（list item 靠 surface 层级区分，官方即如此）、组卡更圆、
        // 行尾不画 disclosure（M3 list 用 trailing supporting text / icon）。
        listIconStyle = 2;
        // 0.9.204：40dp 圆形图标 + 16dp gap 把行撑得又空又挤，收到 32/16/18/12。
        listIconSizeDp = 32; listIconCornerDp = 16; listIconGlyphDp = 18; listIconGapDp = 12;
        listChevron = false; listChevronSp = 0f;
        listGroupInsetDp = 0; listDividerStartDp = 0; listNoDivider = true;
        listCardCornerDp = 24; listRowMinHDp = 56;   // 0.9.204：配合单行说明收行高

        // 动态项构图（M3 Expressive）：40dp tonal 圆 + accent 单色首字，
        // 每个书源一张独立 filled card（8dp 间距、16dp 圆角、不描边），
        // 组内不画分隔线——M3 list item 靠 surface 层级区分，官方即如此。
        itemBadgeStyle = 2;
        itemBadgeSizeDp = 40; itemBadgeCornerDp = 20; itemBadgeTextSp = 17f; itemBadgeGapDp = 16;
        itemCardGapDp = 10; itemCardCornerDp = 20; itemCardNoStroke = true;
        itemDividerStartDp = 0;

        // 搜索页构图（M3 Expressive）：search bar = 56dp 全圆胶囊 + surfaceContainerHigh
        // + 3dp tonal elevation + 24dp leading icon + bodyLarge 16sp；大标题走 A8 32 Medium；
        // 结果行每张独立 filled card（16dp 圆角 / 8dp 间距 / 不描边）；标签 8dp assist chip 描边。
        // 官方 list item 不画分隔线，靠 surface 层级区分，故 dividerStart=0。
        searchBarStyle = 2;
        searchBarHDp = 56; searchBarCornerDp = 28; searchBarIconDp = 24;
        searchBarTextSp = 16f; searchBarElevDp = 3f;
        searchCoverCornerDp = 16; searchHitCardCornerDp = 20; searchHitCardFilled = true;
        searchHitGapDp = 8; searchHitDividerStartDp = 0;
        searchTagCornerDp = 8f; searchTagStroke = true;
        // 焦点态：M3 search bar 聚焦时 elevation 3dp→6dp、底色 High→Highest（tonal 升一级）。
        searchFocusStyle = 2; searchFocusElevDp = 6f;
        // 书详情页（M3 Expressive）：封面更大 + 16dp 圆角 + 6dp tonal elevation、
        // 书名 26sp Medium 无衬线、标签 8dp assist chip 描边、动作块 12dp tonal、
        // 卡片不画线靠 surface 分层、FAB 16dp 圆角 tonal 容器。
        bookStyle = 2;
        bookCoverWDp = 112; bookCoverCornerDp = 16; bookCoverElevDp = 6f;
        bookCoverPlace = 2; bookTitleSp = 26f; bookTitleFace = 2;
        bookTagCornerDp = 8f; bookTagStroke = true; bookTagFill = 2;
        bookActionCornerDp = 16f; bookActionStyle = 2; bookActionBg = 2;
        bookCardHairline = false; bookFabStyle = 2; bookTocBarCornerDp = 8f;
        // 导入页 / 本地书页（M3 Expressive）：顶栏 64dp（22sp Medium 标题 + 40dp 图标钮）、
        // 每项一张 surfaceContainerLow filled card（12dp / 8dp 间距）、卡内 title 16sp +
        // 40dp Filled / Outlined 按钮（20dp 全圆）、行首 40dp tonal 圆图标、文件行 64dp。
        ioStyle = 2;
        ioTitleSp = 22f; ioTitleWeight = 2; ioTitleSerif = false;
        ioIntroSp = 14f; ioFootSp = 12f;
        ioCardCornerDp = 20; ioCardGapDp = 10;
        ioCardPadH = 16; ioCardPadT = 16; ioCardPadB = 16;
        ioCardBg = 2; ioRowChevron = false; ioRowHDp = 0;
        ioBtnHDp = 40;
        localHeadHDp = 64; localNavTitleSp = 22f; localNavTitleWeight = 2;
        localLargeTitle = false; localLargeTitleSp = 22f; localHeadAction = 2; localSubSp = 12f;
        localRowStyle = 2; localRowCornerDp = 20; localRowGapDp = 10;
        localRowPadH = 16; localRowPadV = 14; localRowMinHDp = 64;
        localNameSp = 16f; localNameWeight = 2; localTagSp = 11f;
        localIconStyle = 2; localIconDp = 40; localIconCornerDp = 999; localGlyphDp = 22;
        localTagStyle = 2; localAddStyle = 2; localEmptyStyle = 2; localSelBarStyle = 2;
        localDividerStartDp = 0;

        // M3：8dp 描边 chip、11sp、9·5 内边距；选中用 secondaryContainer tonal
        chipStyle = 2; chipCornerDp = 8; chipLabelSp = 11f;
        chipPadHDp = 9; chipPadVDp = 5; chipStroke = true;
        chipFill = 2; chipActiveFill = 2;
        // 阅读页工具坞（M3）：28dp 仅上两角、tonal 实底、12sp Medium、图标后指示器
        rdDockStyle = 2; rdDockCornerDp = 28; rdDockTopOnly = true;
        rdDockPadH = 8; rdDockPadV = 8; rdDockLabelSp = 12f; rdDockLabelWeight = 2;
        rdDockIconDp = 24; rdDockItemPadV = 8; rdDockItemCornerDp = 999;
        rdDockIndicator = true; rdDockMetaSp = 12f;
        // 滑杆 / TTS / 目录（M3）：细轨 + 端点停靠点、48dp 播放键铺 tonal 底、当前章整行 tonal
        rdSeekStyle = 2; rdSeekTrackDp = 4; rdSeekThumbDp = 9; rdSeekGlow = false; rdSeekStopDots = true;
        rdTtsTitleSp = 14; rdTtsTitleWeight = 2; rdTtsLabelSp = 12; rdTtsPlayDp = 48; rdTtsPlayFill = true;
        rdTocTitleSp = 16; rdTocSubSp = 12; rdTocPadV = 12; rdTocDividerStartDp = 0; rdTocSelectedFill = true;

        // 表单/面板贴底全宽；确认走屏幕居中 Dialog；列表贴底全宽
        sheetAnchor = SheetAnchor.BOTTOM_FULL;
        confirmAnchor = SheetAnchor.CENTER;
        listAnchor = SheetAnchor.BOTTOM_FULL;
        sheetWidthDp = 320;
        sheetMaxWidthDp = 560;
        sheetSideMarginDp = 0;
        sheetLandRatio = 0.56f;
        sheetBlur = false;
        sheetHandle = true;
        sheetDraggable = true;       // Modal Bottom Sheet 可跟手下滑关闭
        sheetTitleDivider = false;
        sheetRadiusDp = 28;
        buttonStyle = ButtonStyle.TEXT_RIGHT;
        material = Material.TONAL;

        navStyle = NavStyle.M3_NAV_BAR;
        navHeightDp = 80;
        navBottomGapDp = 0;
        navSideGapDp = 0;
        navCapWidthDp = 0;
        navIconDp = 24;
        navCornerDp = 0;
        navIndicator = true;
        navDivider = false;
        navBlur = false;
        navFloating = false;
        navFootDp = 96;

        menuStyle = MenuStyle.FILLED_ANCHOR;
        menuCornerDp = 12;
        menuRowHeightDp = 52;
        menuBlur = false;

        // 导航细化：贴底 Navigation Bar = 实色 surfaceContainer + 64×32 primaryContainer
        // active indicator；选中用 onPrimaryContainer，未选中用 onSurfaceVariant；不放大。
        navBarColor = surface;
        navOnColor = onContainer;
        navOffColor = sub;
        navIndicatorColor = container;
        navIndicatorWDp = 64;
        navIndicatorHDp = 32;
        navDividerColor = 0;
        navScaleSelected = false;
        navBarElevationDp = 3;
        // M3 层级靠 tonal elevation：surface 上叠一层 5% primary tint（不是投影），
        // 与 M3 Navigation Bar 的 surfaceContainer 语义一致。
        navGlassMaterial = false;
        navGlassHighlight = 0;
        navSurfaceTint = alpha(accent, 0x0D);
        navIconLine = new int[]{R.drawable.ic_m3_shelf, R.drawable.ic_m3_source,
                R.drawable.ic_m3_rule, R.drawable.ic_m3_gear};
        navIconFill = new int[]{R.drawable.ic_m3_shelf_fill, R.drawable.ic_m3_source_fill,
                R.drawable.ic_m3_rule_fill, R.drawable.ic_m3_gear_fill};

        // 菜单细化：不透明填充列表，靠 elevation 分层，不靠描边 / 玻璃。
        menuSurface = surfaceHigh;
        menuScrim = 0x20000000;
        menuRimDp = 0f;
        menuElevationDp = 3;
        menuPopoverArrow = false;
        // 高还原：M3 menu = 8dp 圆角、elevation3、48dp 行、bodyLarge 16sp、
        // 24dp leading icon、state layer 按压（10% onSurface），expressive spring 带轻过冲。
        menuTitleSp = 16f;
        menuHintSp = 14f;
        menuItemIconDp = 24;
        menuIconLeading = true;
        menuDividerInsetDp = 20;              // 0.9.209：分割线左右缩进 20dp，与极简/Liquid 对齐
        menuStateLayer = alpha(text, 0x1A);   // 10% onSurface
        menuHighlight = alpha(container != 0 ? container : accent, 0x24);
        menuArrowDp = 0;
        menuRimLight = 0;
        durMenuIn = 250;
        durMenuOut = 200;
        durMenuSub = 300;
        menuRowPadLDp = 12;                  // M3 menu 12dp 水平内边距
        menuRowPadRDp = 12;
        menuRowPadVDp = 6;                   // 48dp 行高靠内容撑，上下收紧
        menuRowRadiusDp = 8f;                // M3 高亮块圆角跟卡片同档
        menuDividerColor = line;             // M3 divider = outlineVariant 档

        // M3 不挂系统模糊，这两支只是给万一走玻璃材质的窗口一个不刺眼的实底。
        glassMaterialVeil = surfaceHigh;
        glassSolidFallback = surfaceHigh;

        durShort = 200;
        durMed = 300;
        durLong = 400;
    }

    /** 极简·纪 (Monolith)：莫兰迪色、大圆角卡片、悬浮药丸底栏、纯线条图标 */
    private void fillMonolith(boolean d) {
        // 1. 色彩体系（基于开元阅读的灰蓝底与高亮卡片）
        bg = d ? 0xFF1A1C23 : 0xFFF7F8FA; // 深色为灰蓝黑，浅色为冷灰白
        surface = d ? 0xFF242731 : 0xFFFFFFFF; // 卡片色
        surfaceHigh = d ? 0xFF2E323E : 0xFFFFFFFF;
        surfaceLow = d ? 0xFF1E2029 : 0xFFF0F2F5;
        text = d ? 0xFFE2E4E9 : 0xFF1A1C23;
        sub = d ? 0xFF9AA0B0 : 0xFF7A8299;
        line = d ? 0xFF333744 : 0xFFE5E8EF;
        accent = d ? 0xFF8AA4F0 : 0xFF5C7CE0; // 柔和蓝
        onAccent = 0xFFFFFFFF;
        danger = d ? 0xFFFF5252 : 0xFFD32F2F;
        container = alpha(accent, 0x24);
        onContainer = accent;
        divider = alpha(text, 0x0D);
        ripple = alpha(text, 0x14);
        
        // 极简风拒绝毛玻璃，全部用实色
        glassBase = surfaceHigh;
        glassVeil = 0x00000000;
        glassRim = 0x00000000;
        scrimColor = 0xFF000000;
        scrim = 0.40f;
        gradientBackdrop = false;

        // 2. 形状：全部为大圆角、胶囊
        cornerXs = 4;
        cornerS = 12;
        cornerM = 24;      // 卡片大圆角
        cornerL = 28;
        cornerSheet = 28;
        cornerAlert = 24;
        cornerCard = 24;
        cornerButton = 999; // 全圆胶囊
        cornerMenu = 20;
        squircle = true;
        capsuleButton = true;
        cardStrokeDp = 0f;
        cardElevationDp = 0f; // 扁平化，不要阴影

        // 3. 排版（主字号会被下面的 applyTypeScale 覆盖）
        pageTitleSp = 28f; 
        sheetTitleSp = 20f;
        rowTitleSp = 16f;
        rowHintSp = 13f;
        groupTitleSp = 15f;
        navLabelSp = 11f;
        bodySp = 16f;
        pageTitleSerif = false;
        sheetTitleSerif = false;
        sheetTitleCenter = false;
        pageTitleTracking = -0.01f;
        pageTitleWeight = 1;
        sheetTitleWeight = 1;

        // 4. 度量与列表行（大间距，去线化）
        rowPadH = 20;
        rowPadV = 16;
        panelPadH = 20;
        panelPadT = 16;
        panelPadB = 24;
        topInset = 16;
        rowMinHeight = 56;
        groupGap = 24; 
        dividerInset = 20;
        dividerDp = 1;

        listIconStyle = 2; 
        listIconSizeDp = 32; listIconCornerDp = 16; listIconGlyphDp = 18; listIconGapDp = 16;
        listChevron = true; listChevronSp = 16f;
        listGroupInsetDp = 0; listDividerStartDp = 20; listNoDivider = false;
        listCardCornerDp = 24; listRowMinHDp = 56;

        // 5. 导航栏（悬浮胶囊 + 药丸底座，完全借鉴开元阅读）
        navStyle = NavStyle.FLOATING_ISLAND;
        navHeightDp = 64;
        navBottomGapDp = 16; // 底部留白
        navSideGapDp = 16;
        navCapWidthDp = 360;
        navIconDp = 22;
        navCornerDp = 32; // 胶囊圆角
        navIndicator = true; // 开启选中底部的药丸
        navDivider = false;
        navBlur = false; // 纯实色
        navFloating = true;
        navFootDp = 96;
        
        navBarColor = d ? 0xFF242731 : 0xFFFFFFFF;
        navOnColor = accent;
        navOffColor = sub;
        navIndicatorColor = alpha(accent, 0x1F); // 药丸底座颜色（淡蓝）
        navIndicatorWDp = 56; 
        navIndicatorHDp = 32;
        navDividerColor = 0;
        navScaleSelected = false;
        navBarElevationDp = 0;
        navGlassMaterial = false;
        navGlassHighlight = 0;
        navSurfaceTint = 0;
        navIconLine = new int[]{R.drawable.ic_nav_shelf_line, R.drawable.ic_nav_source_line,
                R.drawable.ic_nav_rule_line, R.drawable.ic_nav_gear_line};
        navIconFill = navIconLine;

        // 6. 菜单
        menuStyle = MenuStyle.FILLED_ANCHOR;
        menuCornerDp = 20;
        menuRowHeightDp = 52;
        menuBlur = false;
        menuSurface = surface;
        menuScrim = 0x40000000;
        menuRimDp = 0f;
        menuElevationDp = 0;
        menuPopoverArrow = false;
        menuTitleSp = 16f;
        menuHintSp = 13f;
        menuItemIconDp = 20;
        menuIconLeading = true;
        menuDividerInsetDp = 20;
        menuStateLayer = alpha(text, 0x0A);
        menuHighlight = alpha(accent, 0x15);
        menuArrowDp = 0;
        menuRimLight = 0;
        durMenuIn = 200;
        durMenuOut = 150;
        durMenuSub = 250;
        menuRowPadLDp = 20;
        menuRowPadRDp = 20;
        menuRowPadVDp = 14;
        menuRowRadiusDp = 16f;
        menuDividerColor = line;

        // 7. 弹层
        sheetAnchor = SheetAnchor.FLOAT_BOTTOM;
        confirmAnchor = SheetAnchor.CENTER;
        listAnchor = SheetAnchor.FLOAT_BOTTOM;
        sheetWidthDp = 320;
        sheetMaxWidthDp = 400;
        sheetSideMarginDp = 16;
        sheetLandRatio = 0.50f;
        sheetBlur = false;
        sheetHandle = true;
        sheetDraggable = true;
        sheetTitleDivider = false;
        sheetRadiusDp = 28;
        buttonStyle = ButtonStyle.FILLED_PAIR;
        material = Material.GLASS; 

        // 动效
        spring = true;
        overshoot = 0f;
        durShort = 200;
        durMed = 300;
        durLong = 400;
        
        // 覆盖阶段3字段
        dialogSupportSp = 14f;
        dialogBtnHeightDp = 48;
        dialogElevationDp = 0;
        dialogTitleCenter = false;
        actionSheetSeparateCancel = false;
        actionSheetRowDp = 56;
        actionSheetGroupRadiusDp = 24;
        actionSheetCancelGapDp = 0;
        actionSheetTitleSp = 14f;
        actionSheetItemSp = 16f;
        pageSheetPeek = false;
        concentric = false;
        buttonHeightDp = 48;
        buttonLabelSp = 16f;
        buttonWeight = 1;
        buttonMinWidthDp = 64;
        sheetEnterAnim = true;
    }

    /* ==================== 画廊页三套定稿数值 ==================== */

    private static void fillGalleryModou(Gallery g, boolean d) {
        g.galleryStyle = 0;
        g.galleryGridStyle = 0;
        g.galleryWideAction = false;
        g.eyebrow = "主题外观";
        g.title = "阅读，也有你的风格";
        g.subtitle = "从流体玻璃 · 到诗意山水";
        g.inUsePrefix = "正在使用 · ";
        g.allTitle = "全部主题";
        g.allHint = "点选卡片预览，确认后应用到整个软件";
        g.cancel = "取消";
        g.apply = "使用选中的主题";
        g.aboutTitle = "关于三套设计语言";
        g.aboutBody = ABOUT_BODY;
        g.footerA = "配色 · 界面材质 · 导航形态";
        g.footerB = "阅读，自有天地。";
        g.modeNames = new String[]{"浅色模式", "深色模式", "跟随系统"};
        g.modeHints = new String[]{"明亮通透", "沉静护眼", "随日出日落"};
        g.taglines = TAGLINES;
        // 参考图的灵魂：淡绿宣纸渐变 + 深墨绿宋体大标题 + 玻璃卡边缘泛光
        g.titleSp = 34f;
        g.titleSerif = true;
        g.titleColor = d ? 0xFFE3EFE4 : 0xFF1B3B33;
        g.titleTracking = 0.012f;
        g.titleWeight = 1;
        g.backdropA = d ? 0xFF0D1512 : 0xFFF4F8F1;
        g.backdropB = d ? 0xFF18261E : 0xFFDDEBDC;
        g.cardColor = d ? 0xB01C211D : 0xCCFFFFFF;
        g.cardRim = d ? 0x33FFFFFF : 0x8CFFFFFF;
        g.cardRimDp = 1f;
        g.glassCard = true;
        g.cardRadiusDp = 22f;
        g.thumbRadiusDp = 18f;
        g.selectColor = d ? 0xFF9FC3E8 : 0xFF2F6BF0;
        g.selectFill = d ? 0x243F6BF0 : 0x182F6BF0;
        g.selectStrokeDp = 1.6f;
        g.selectIndicator = false;
        // 0.9.181：底部动作条原为 80% 白，滚动卡片的文字会从玻璃里透出来形成「重影字」。
        // 提遮光度（保留 rim / 凝光，玻璃感不丢），文字不再和卡片标签叠在一起。
        g.barColor = d ? 0xE61A201C : 0xF2FFFFFF;
        g.barRim = d ? 0x26FFFFFF : 0x66FFFFFF;
        g.barGlass = true;
        g.primaryBtnColor = d ? 0xFF7FA6D9 : 0xFF2F6BF0;
        g.primaryBtnText = 0xFFFFFFFF;
        g.cancelText = d ? 0xFF9B9DA2 : 0xFF5E7089;
        g.btnRadiusDp = 999f;
        g.spring = false;
        g.durIn = 260;
        g.overshoot = 0f;
        g.largeTitleScroll = false;   // 墨读大标题是版式的一部分，不随滚动收起
        g.appBarTonalScroll = false;
        g.scrollEdgeDp = 96;          // 墨读不启用，仍填值避免字段为 0
        g.shelfChrome = false;        // 墨读书架工具条逐像素保持 0.9.164 原样
        g.shelfChromeLand = false;
        // 真·Large Title 只在 M3 / iOS 启用；墨读这四项填安全值，页面分支根本不读
        g.shelfTitleBlockDp = 0;
        g.shelfBigTitleFade = false;
        g.shelfInlineCenter = true;
        g.shelfHairline = false;
    }

    /** M3 Expressive：surface 纯色、Medium 大标题、Filled card、indicator、全圆 Filled button、spring */
    private static void fillGalleryMaterial(Gallery g, boolean d) {
        // M3：large top app bar + 两列 filled 卡网格 + 通栏 Filled 按钮
        g.galleryStyle = 2;
        g.galleryGridStyle = 2;
        g.galleryWideAction = true;
        g.eyebrow = "主题外观";
        g.title = "让界面，随你而变";
        g.subtitle = "形状、色彩与动效，都由你定义";
        g.inUsePrefix = "当前 · ";
        g.allTitle = "选择风格";
        g.allHint = "轻点卡片预览，确认后应用";
        g.cancel = "取消";
        g.apply = "应用主题";
        g.aboutTitle = "三套设计语言";
        g.aboutBody = ABOUT_BODY;
        g.footerA = "色彩系统 · 形状刻度 · 弹性动效";
        g.footerB = "Material 3 Expressive";
        g.modeNames = new String[]{"浅色", "深色", "跟随系统"};
        g.modeHints = new String[]{"明亮", "沉静", "自动"};
        g.taglines = TAGLINES;
        g.titleSp = 28f;                               // 0.9.204：M3 画廊大标题收小
        g.titleSerif = false;
        g.titleColor = d ? 0xFFE6E0E9 : 0xFF1D1B20;
        g.titleTracking = 0f;
        g.titleWeight = 2;
        // M3 规范：底衬是纯色 surface，没有渐变与光斑
        g.backdropA = g.backdropB = d ? 0xFF141218 : 0xFFFDF7FF;
        g.cardColor = d ? 0xFF1D1B20 : 0xFFF7F2FA;   // surfaceContainerLow
        g.cardRim = 0;
        g.cardRimDp = 0f;
        g.glassCard = false;
        g.cardRadiusDp = 24f;                          // Expressive large shape
        g.thumbRadiusDp = 20f;
        // 0.9.193：M3 默认强调色由紫改蓝（与全语言一致），画廊内按钮 / 选中态同步
        g.selectColor = d ? 0xFFDCE2FF : 0xFF00246B;
        g.selectFill = d ? 0xFF3C4C8A : 0xFFDDE4FF;
        g.selectStrokeDp = 0f;                        // 靠 indicator 药丸，不描边
        g.selectIndicator = true;
        g.barColor = d ? 0xFF211F26 : 0xFFF3EDF7;     // surfaceContainer
        g.barRim = 0;
        g.barGlass = false;
        g.primaryBtnColor = d ? 0xFF9DB2FF : 0xFF3B6FE0;
        g.primaryBtnText = d ? 0xFF0A2A6B : 0xFFFFFFFF;
        g.cancelText = d ? 0xFFCAC4D0 : 0xFF49454F;
        g.btnRadiusDp = 999f;                         // M3 Filled button 全圆
        g.spring = true;
        g.durIn = 300;
        g.overshoot = 1.06f;                          // expressive spring 轻微过冲
        g.largeTitleScroll = false;
        g.appBarTonalScroll = true;                   // M3 app bar：滚动后 tonal elevation 提升
        g.scrollEdgeDp = 96;                          // app bar tonal elevation 的行程
        g.shelfChrome = true;                         // 书架工具条滚动后抬 tonal elevation
        g.shelfChromeLand = false;                    // 横屏一行紧凑工具条，保持原样
        // M3 Expressive large top app bar：大标题 32sp Medium，滚动收起为 small app bar
        g.shelfTitleBlockDp = 88;
        g.shelfBigTitleFade = true;                   // 局部启用，不改全局 largeTitleScroll（三个已验收页面零回归）
        g.shelfInlineCenter = false;                  // M3 top app bar 标题靠左，不居中
        g.shelfHairline = false;                      // M3 用 tonal elevation 分层，不画 hairline
        // M3 的结构差异放在「右下角 Expressive FAB」（ShelfActivity.buildShelfFab），
        // 不占标题块高度；iOS 的差异是标题下搜索框，墨读两者都没有。
        g.shelfHeaderStyle = 0;
    }

    private static void fillGalleryMonolith(Gallery g, boolean d) {
        g.galleryStyle = 1;
        g.galleryGridStyle = 0;
        g.galleryWideAction = true;
        g.eyebrow = "外观";
        g.title = "极简风格";
        g.subtitle = "莫兰迪配色与纯粹的版式";
        g.inUsePrefix = "正在使用 · ";
        g.allTitle = "设计语言";
        g.allHint = "轻点以预览，确认后应用";
        g.cancel = "取消";
        g.apply = "使用所选主题";
        g.aboutTitle = "关于设计语言";
        g.aboutBody = ABOUT_BODY;
        g.footerA = "去边框 · 大留白 · 胶囊底座";
        g.footerB = "极简·纪";
        g.modeNames = new String[]{"浅色", "深色", "跟随系统"};
        g.modeHints = new String[]{"明亮", "沉静", "自动"};
        g.taglines = TAGLINES;
        g.titleSp = 28f;
        g.titleSerif = false;
        g.titleColor = d ? 0xFFFFFFFF : 0xFF000000;
        g.titleTracking = -0.01f;
        g.titleWeight = 1;
        g.backdropA = g.backdropB = d ? 0xFF1A1C23 : 0xFFF7F8FA;
        g.cardColor = d ? 0xFF242731 : 0xFFFFFFFF;
        g.cardRim = 0;
        g.cardRimDp = 0f;
        g.glassCard = false;
        g.cardRadiusDp = 24f;
        g.thumbRadiusDp = 20f;
        g.selectColor = d ? 0xFF8AA4F0 : 0xFF5C7CE0;
        g.selectFill = alpha(g.selectColor, 0x1F);
        g.selectStrokeDp = 0f;
        g.selectIndicator = false;
        g.barColor = d ? 0xFF242731 : 0xFFFFFFFF;
        g.barRim = 0;
        g.barGlass = false;
        g.primaryBtnColor = g.selectColor;
        g.primaryBtnText = 0xFFFFFFFF;
        g.cancelText = d ? 0xFF9AA0B0 : 0xFF7A8299;
        g.btnRadiusDp = 999f;
        g.spring = true;
        g.durIn = 300;
        g.overshoot = 0f;
        g.largeTitleScroll = false;
        g.appBarTonalScroll = false;
        g.shelfChrome = false;
        g.shelfChromeLand = false;
        g.shelfTitleBlockDp = 0;
        g.shelfBigTitleFade = false;
        g.shelfInlineCenter = true;
        g.shelfHairline = false;
        g.shelfHeaderStyle = 0;
    }

    private static final String[] TAGLINES = {
            "流体玻璃 · 悬浮浮岛", "色调分层 · 明快弹性", "莫兰迪色 · 胶囊浮岛"};
    private static final String ABOUT_BODY =
            "墨读以流体玻璃与悬浮浮岛营造通透景深；Material 3 用色调分层与弹性动效组织层级；"
                    + "极简·纪以莫兰迪色系、胶囊底栏与去边框设计将内容放在最前面。"
                    + "切换后配色、圆角、排版、弹窗位置与导航形态都会一起改变，"
                    + "阅读纸张配色不受影响。";

    /* ---------- 阅读纸张画廊（0.9.161）---------- */

    static final String[] PAPER_IDS = {"paper", "cream", "gray", "dark"};
    private static final String[] PAPER_NAMES = {"素月白", "晨光米", "远山青", "夜间"};

    /** 纸张 id → 展示名（设置页入口行用，避免各处再抄一份名字） */
    static String paperLabel(String id) {
        for (int i = 0; i < PAPER_IDS.length; i++) {
            if (PAPER_IDS[i].equals(id)) return PAPER_NAMES[i];
        }
        return PAPER_NAMES[0];
    }
    private static final String[] PAPER_HINTS = {"清冷干净", "温软羊皮", "山色沉静", "低光护眼"};

    private static final String PAPER_ABOUT =
            "素月白清冷、晨光米温软、远山青沉静、夜间低光护眼。选浅色纸会让整个软件回到浅色，"
                    + "选夜间会一同切到深色；纸张配色与三套设计语言互不影响，"
                    + "深浅与跟随系统请在「主题外观」里设置。";

    private static final String[] INFO_LABELS_MODOU = {"明暗", "色温", "适合"};
    private static final String[][] PAPER_INFO_MODOU = {
            {"冷调浅白", "中性", "日间 · 长久阅读"},
            {"暖调米黄", "偏暖", "午后 · 慢慢读"},
            {"淡青绿", "偏冷", "清晨 · 静读"},
            {"近黑底", "中性", "睡前 · 低光"},
    };
    private static final String[] INFO_LABELS_M3 = {"明度", "色温", "建议"};
    private static final String[][] PAPER_INFO_M3 = {
            {"高明度", "中性", "白天 / 强光"},
            {"中明度", "暖色", "室内 / 暖光"},
            {"中明度", "冷色", "白天 / 户外"},
            {"低明度", "中性", "夜间 / 弱光"},
    };
    private static final String[] INFO_LABELS_IOS = {"明暗", "色调", "场景"};
    private static final String[][] PAPER_INFO_IOS = {
            {"明亮", "中性", "日间"},
            {"柔和", "暖色", "午后"},
            {"柔和", "冷色", "清晨"},
            {"深色", "中性", "夜间"},
    };

    /** 墨读：沿用宣纸渐变 + 宋体墨绿大标题，文案偏诗意 */
    private static void fillPaperGalleryModou(PaperGallery g, boolean d) {
        fillGalleryModou(g, d);
        g.eyebrow = "阅读纸张";
        g.title = "纸上有光，字里有山";
        g.subtitle = "从素月白到夜色，四种纸，四种心绪";
        g.inUsePrefix = "正在使用 · ";
        g.allTitle = "我的纸张";
        g.allHint = "点选卡片预览，确认后铺满整页";
        g.cancel = "取消";
        g.apply = "使用这张纸";
        g.aboutTitle = "关于阅读纸张";
        g.aboutBody = PAPER_ABOUT;
        g.footerA = "纸张 · 墨色 · 留白";
        g.footerB = "读书，也读纸。";
        g.paperNames = PAPER_NAMES;
        g.paperHints = PAPER_HINTS;
        g.infoLabels = INFO_LABELS_MODOU;
        g.paperInfo = PAPER_INFO_MODOU;
        g.gridColumns = 2;
        g.thumbHeightDp = 158f;
    }

    /** M3：纯色 surface + Medium 标题 + Filled card，文案直白功能化 */
    private static void fillPaperGalleryMaterial(PaperGallery g, boolean d) {
        fillGalleryMaterial(g, d);
        g.eyebrow = "阅读纸张";
        g.title = "选一张纸，开始读";
        g.subtitle = "四种配色，随时切换";
        g.inUsePrefix = "当前 · ";
        g.allTitle = "选择纸张";
        g.allHint = "轻点卡片预览，确认后应用";
        g.cancel = "取消";
        g.apply = "应用纸张";
        g.aboutTitle = "阅读纸张";
        g.aboutBody = PAPER_ABOUT;
        g.footerA = "配色 · 对比度 · 阅读舒适度";
        g.footerB = "Material 3 Expressive";
        g.paperNames = PAPER_NAMES;
        g.paperHints = PAPER_HINTS;
        g.infoLabels = INFO_LABELS_M3;
        g.paperInfo = PAPER_INFO_M3;
        g.gridColumns = 2;
        g.thumbHeightDp = 158f;
    }

    private static void fillPaperGalleryMonolith(PaperGallery g, boolean d) {
        fillGalleryMonolith(g, d);
        g.eyebrow = "阅读纸张";
        g.title = "选择阅读背景";
        g.subtitle = "四种极简纯色纸张";
        g.inUsePrefix = "当前使用 · ";
        g.allTitle = "选择纸张";
        g.allHint = "轻点卡片预览，确认后应用";
        g.cancel = "取消";
        g.apply = "应用纸张";
        g.aboutTitle = "阅读纸张";
        g.aboutBody = PAPER_ABOUT;
        g.footerA = "配色 · 对比度 · 阅读舒适度";
        g.footerB = "极简·纪";
        g.paperNames = PAPER_NAMES;
        g.paperHints = PAPER_HINTS;
        g.infoLabels = INFO_LABELS_IOS;
        g.paperInfo = PAPER_INFO_IOS;
        g.gridColumns = 2;
        g.thumbHeightDp = 158f;
    }

    /* ==================== A8：三套字阶与派生（0.9.163 · 台账 A8） ==================== */

    /**
     * 三套语言的 15 档字阶。顺序严格按 {@link TypeRole} 枚举。
     *
     * <p><b>M3</b>：Material 3 官方 type scale（displayLarge 57 / headlineLarge 32 /
     * titleMedium 16-500 / bodyLarge 16-0.5px / labelLarge 14-500 / labelMedium 12-0.5px…），
     * tracking 由官方 px 值换算成 em（如 bodyLarge 0.5px ÷ 16sp = 0.031em）。
     *
     * <p><b>iOS 26</b>：SF Pro / Dynamic Type 对应档位（Large Title 34、Title1 28、
     * Title2 22、Title3 20、Headline 17、Body 17、Footnote 13、Caption1 12、Caption2 11），
     * 大字号带负字距，小字号正字距；按钮与正文同为 17sp Regular。
     *
     * <p><b>墨读</b>：逐项抄 0.9.162 已定稿数值（pageTitle 26 / rowTitle 14.5 /
     * groupTitle 15 / sheetTitle 17 / navLabel 11 / buttonLabel 14），保证零回归。
     * 表里 DISPLAY_* / TITLE_L 等未直接落到既有字段的档位，是留给后续页面（详情页大标题、
     * 空态标题等）按角色取值的，不是摆设。
     */
    private static TypeScale createTypeScale(Lang lang) {
        switch (lang) {
            case MATERIAL:
                return new TypeScale(
                        // 0.9.204：HEADLINE_L/M 32/28 → 28/26。原值在书架与设置页
                        // 压出「巨黑大标题」，与墨读 26sp 的克制差一档；字重/字距不动。
                        new float[]{57, 45, 36, 28, 26, 24, 22, 16, 14, 16, 14, 12, 14, 12, 11},
                        new int[]{0, 0, 0, 2, 0, 0, 0, 2, 2, 0, 0, 0, 2, 2, 2},
                        new float[]{-0.004f, 0, 0, 0, 0, 0, 0, 0.009f, 0.007f,
                                0.031f, 0.018f, 0.033f, 0.007f, 0.042f, 0.045f});
            case MONOLITH:
                return new TypeScale(
                    new float[]{34, 28, 22, 28, 24, 20, 22, 18, 15, 16, 14, 12, 15, 13, 11},
                    new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0},
                    new float[]{-0.01f, -0.01f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
                );

            case MODOU:
            default:
                return new TypeScale(
                        new float[]{26, 24, 20, 26, 22, 17, 17, 15, 15, 14.5f, 11.5f, 11, 14, 11, 10},
                        new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0},
                        new float[]{-0.02f, -0.02f, -0.01f, -0.02f, -0.01f, 0,
                                0, 0, 0, 0, 0, 0, 0, 0, 0});
        }
    }

    /** 角色 → 既有散字段。所有组件工厂继续读散字段，无需改动。 */
    private void applyTypeScale() {
        pageTitleSp = ts(TypeRole.HEADLINE_L);
        pageTitleWeight = tw(TypeRole.HEADLINE_L);
        pageTitleTracking = tt(TypeRole.HEADLINE_L);
        sheetTitleSp = ts(TypeRole.HEADLINE_S);
        sheetTitleWeight = tw(TypeRole.HEADLINE_S);
        sheetTitleTracking = tt(TypeRole.HEADLINE_S);
        rowTitleSp = ts(TypeRole.BODY_L);
        rowTitleWeight = tw(TypeRole.BODY_L);
        rowTitleTracking = tt(TypeRole.BODY_L);
        rowHintSp = ts(TypeRole.BODY_M);
        rowHintTracking = tt(TypeRole.BODY_M);
        groupTitleSp = ts(TypeRole.TITLE_S);
        groupTitleWeight = tw(TypeRole.TITLE_S);
        groupTitleTracking = tt(TypeRole.TITLE_S);
        navLabelSp = ts(TypeRole.LABEL_M);
        buttonLabelSp = ts(TypeRole.LABEL_L);
        buttonWeight = tw(TypeRole.LABEL_L);
        buttonTracking = tt(TypeRole.LABEL_L);
        // bodySp 不派生：墨读正文基准 14sp 与 rowTitle 14.5sp 语义不同，保持 0.9.162 原值。
    }

    /** 按角色取字号 / 字重 / 字距，供新组件直接查表。 */
    float ts(TypeRole r) { return type.size(r); }
    int tw(TypeRole r) { return type.weight(r); }
    float tt(TypeRole r) { return type.tracking(r); }

    /* ==================== 全局动效工厂（0.9.162 · 台账 A3/B9） ==================== */

    /**
     * 唯一一份「按语言分流的插值器」：菜单 {@code CascadingMenu.menuInterp}、两个画廊页
     * {@code interp()}、弹层入场/退场、按钮按压复位全部调用它，避免三处各写一份曲线。
     *
     * @param sp               当前语言规格
     * @param overshootAllowed 入场/展开才允许过冲；出场/收起传 false，避免关闭时「弹一下」
     * @param decel            非 spring 语言（墨读）用的 DecelerateInterpolator 系数
     */
    static android.view.animation.Interpolator interpolator(
            DesignSpec sp, boolean overshootAllowed, float decel) {
        if (sp == null || !sp.spring) {
            return new android.view.animation.DecelerateInterpolator(decel <= 0 ? 1.6f : decel);
        }
        if (overshootAllowed && sp.overshoot > 0f) {
            // M3 Expressive：轻微过冲的空间型 spring
            return new android.view.animation.PathInterpolator(0.34f, 1.22f, 0.62f, 1f);
        }
        // iOS 26 / Liquid Glass：带阻尼、不过冲的 spring
        return new android.view.animation.PathInterpolator(0.28f, 0.88f, 0.30f, 1f);
    }

    /** 便捷重载：给只有 {@code spring/overshoot} 两个字段的页面规格（Gallery）用。 */
    static android.view.animation.Interpolator interpolator(
            boolean spring, float overshoot, boolean overshootAllowed, float decel) {
        if (!spring) {
            return new android.view.animation.DecelerateInterpolator(decel <= 0 ? 1.6f : decel);
        }
        if (overshootAllowed && overshoot > 0f) {
            return new android.view.animation.PathInterpolator(0.34f, 1.22f, 0.62f, 1f);
        }
        return new android.view.animation.PathInterpolator(0.28f, 0.88f, 0.30f, 1f);
    }

    /**
     * A1 / B3 派生色板：三个 fill 方法跑完后由构造函数调用。
     * 只写「新增字段」，不改任何已定稿的 {@code surface/accent/text}，墨读零回归。
     */
    private void deriveTokens() {
        // --- 所有语言都要有一份安全默认，避免任何工厂读到 0 或 null ---
        tonalPrimary = tonalSecondary = tonalTertiary =
                tonalNeutral = tonalNeutralVariant = ModouTonal.ramp(accent);
        surfaceContainerLowest = surface;
        surfaceContainerLow = surfaceLow;
        surfaceContainer = surface;
        surfaceContainerHigh = surfaceHigh;
        surfaceContainerHighest = surfaceHigh;
        primaryContainer = container;
        onPrimaryContainer = onContainer;
        secondaryContainer = container;
        tertiaryContainer = container;
        outline = line;
        outlineVariant = divider;
        stateHover = ModouTonal.stateLayer(text, 0.08f);
        stateFocus = ModouTonal.stateLayer(text, 0.10f);
        statePressed = ModouTonal.stateLayer(text, 0.10f);
        stateDragged = ModouTonal.stateLayer(text, 0.16f);
        sysFill1 = sysFill2 = sysFill3 = sysFill4 = ripple;
        labelSecondary = sub;
        labelTertiary = sub;
        labelQuaternary = sub;
        separatorOpaque = line;
        separatorNonOpaque = divider;
        sysBlue = accent;
        sysGreen = accent;
        sysIndigo = accent;
        sysOrange = danger;
        destructive = danger;

        if (lang == Lang.MATERIAL) {
            // A1：5 组 tonal palette 从主色派生；surfaceContainer 系列 = neutral tone + primary tint
            ModouTonal.Palette p = ModouTonal.palette(accent, dark);
            tonalPrimary = p.primary;
            tonalSecondary = p.secondary;
            tonalTertiary = p.tertiary;
            tonalNeutral = p.neutral;
            tonalNeutralVariant = p.neutralVariant;
            int prim = p.p(dark ? 80 : 40);          // primary key color
            int onPrim = p.p(dark ? 20 : 100);
            // M3 标准 elevation → tone 映射：Lowest 100/95、Low 95/90、Base 90/85、
            // High 85/80、Highest 80/70（浅色）；深色反过来取低 tone。
            int tLowest = dark ? 10 : 100;
            int tLow = dark ? 12 : 95;
            int tBase = dark ? 14 : 90;
            int tHigh = dark ? 17 : 85;
            int tHighest = dark ? 22 : 80;
            surfaceContainerLowest = ModouTonal.tint(p.n(tLowest), prim, 0.02f);
            surfaceContainerLow = ModouTonal.tint(p.n(tLow), prim, 0.05f);
            surfaceContainer = ModouTonal.tint(p.n(tBase), prim, 0.06f);
            surfaceContainerHigh = ModouTonal.tint(p.n(tHigh), prim, 0.08f);
            surfaceContainerHighest = ModouTonal.tint(p.n(tHighest), prim, 0.11f);
            primaryContainer = p.p(dark ? 30 : 90);
            onPrimaryContainer = p.p(dark ? 90 : 10);
            secondaryContainer = p.s(dark ? 30 : 90);
            tertiaryContainer = p.t(dark ? 30 : 90);
            outline = p.nv(dark ? 60 : 50);
            outlineVariant = p.nv(dark ? 30 : 80);
            // A2：state layer 四档一律叠 onSurface（不是换底色）
            int onSurf = text;
            stateHover = ModouTonal.stateLayer(onSurf, 0.08f);
            stateFocus = ModouTonal.stateLayer(onSurf, 0.10f);
            statePressed = ModouTonal.stateLayer(onSurf, 0.10f);
            stateDragged = ModouTonal.stateLayer(onSurf, 0.16f);
            // 覆盖「已定稿」实色值？不。按钮族与弹层直接读下面这些派生值，
            // 而 surfaceHigh/surfaceLow 维持定稿，保证前几轮验收过的 M3 观感不回退。
            onPrimaryContainer = ModouTonal.tint(onPrim, prim, 0f);
            destructive = danger;
        } else if (lang == Lang.MONOLITH) {
            // B3：iOS 26 system colors + label 四级 + systemFill 四档 + separator（非不透明）
            sysBlue = dark ? 0xFF0A84FF : 0xFF007AFF;
            sysGreen = dark ? 0xFF30D158 : 0xFF34C759;
            sysIndigo = dark ? 0xFF5E5CE6 : 0xFF5856D6;
            sysOrange = dark ? 0xFFFF9F0A : 0xFFFF9500;
            destructive = danger;
            int base = dark ? 0xFFEBEBF5 : 0xFF3C3C43;
            sysFill1 = ModouTonal.withAlpha(base, dark ? 0x1A : 0x14); // systemFill 1st
            sysFill2 = ModouTonal.withAlpha(base, dark ? 0x2E : 0x1F);
            sysFill3 = ModouTonal.withAlpha(base, dark ? 0x47 : 0x33);
            sysFill4 = ModouTonal.withAlpha(base, dark ? 0x5C : 0x47);
            labelSecondary = dark ? 0x99EBEBF5 : 0x993C3C43;
            labelTertiary = dark ? 0x4DEBEBF5 : 0x4C3C3C43;
            labelQuaternary = dark ? 0x2EEBEBF5 : 0x2E3C3C43;
            separatorOpaque = dark ? 0xFF38383A : 0xFFC6C6C8;
            separatorNonOpaque = dark ? 0x99545458 : 0x4A3C3C43;
            stateHover = ModouTonal.stateLayer(text, 0.06f);
            stateFocus = ModouTonal.stateLayer(text, 0.08f);
            statePressed = sysFill2;   // iOS 行整体高亮走 systemFill
            stateDragged = sysFill3;
        }
    }

    /* ==================== 小工具 ==================== */

    private static int alpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** 三段切换用的展示文案 */
    static String[] langIds() {
        return new String[]{Lang.MODOU.id, Lang.MATERIAL.id, Lang.MONOLITH.id};
    }

    static String[] langLabels() {
        return new String[]{Lang.MODOU.label, Lang.MATERIAL.label, Lang.MONOLITH.label};
    }
}
