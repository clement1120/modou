package cn.modou.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * 书架（应用根页）。
 *
 * 直接查 books / progress / settings 表，渲染完全本地完成，不经 WebView。
 * 底部导航提供 书源 / 规则 / 设置 四个常驻入口。
 *
 * 排序与分组的语义曾经和 web/js/ui/shelf.js 对齐（该页已删，语义保留）：
 *   recent = 进度更新时间倒序（没读过的排最后，用添加时间兜底）
 *   added  = 添加时间倒序
 *   name   = 书名（中文按拼音，用 Collator 而不是 String.compareTo）
 */
public class ShelfActivity extends Activity {

    private static final String ALL = "全部";
    private static final String DEF_GROUP = "默认";

    static void launch(Activity from) {
        from.startActivity(new Intent(from, ShelfActivity.class));
    }

    /* 全局导入进度横幅：导入中心跑的时候，书架顶部一直能看到进度（切页回来不丢） */
    private View importBanner;
    private TextView importBannerText;
    private final android.os.Handler importTickH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable importTick = new Runnable() {
        @Override
        public void run() {
            refreshImportBanner();
            if (importBanner != null && importBanner.getVisibility() == View.VISIBLE) {
                importTickH.postDelayed(this, 700);
            }
        }
    };

    private ModouUi ui;
    private ModouStore store;

    private final List<JSONObject> books = new ArrayList<>();
    private final Map<String, JSONObject> prog = new HashMap<>();
    private final List<String> groups = new ArrayList<>();

    private String tab = "shelf"; // shelf | history | fav
    private String sort = "recent";
    private String group = ALL;
    private long monthMs = 0;

    /**
     * 上次整页重绘时的书架结构签名（tab|分组|排序 + 每本书的 id/封面/书名/来源/章节数/收藏）。
     * onResume 回书架时若签名没变，就**不重建整页**——否则 removeAllViews 会把已经显示出来的
     * 封面 ImageView 全部销毁重建，封面要么丢进异步再闪一下、要么被阅读页的大位图挤出
     * 内存缓存后要从磁盘重新解码。返回书架时书架内容本就没变，没必要重绘，原地保留即可。
     */
    private String lastSig;
    /** 书卡进度文字（「未读 N 章」等）按书 id 记下来，结构没变时只原地刷新它，不重建封面 */
    private final Map<String, TextView> cardProg = new HashMap<>();
    /** 书 id → 书对象，给原地刷新进度用 */
    private final Map<String, JSONObject> bookById = new HashMap<>();

    /**
     * 书架/历史/收藏的滚动位置。
     *
     * 书架是从阅读页返回时 onResume → reload → paintPane 整页重建的，
     * 重建出来的 ScrollView 位置是 0——用户看到的就是「翻到第 20 本，点开一本，
     * 返回后跳回最顶上」。所以在重建前把位置记下来，同一视图（tab/分组/排序都没变）
     * 重绘时再放回去；切分组、换排序则视为新列表，从头看。
     */
    private View lastScroller;
    private String lastScrollerKey = "";
    private int lastScrollY;
    private int lastFirstPos = -1;
    private int lastFirstTop;
    /**
     * 滚动锚点 = 「第一个可见的是哪本书」。
     * 按下标记位置在转屏后会失效（列数从 3 变 5，第 18 项已经不是同一本书），
     * 所以额外记书 id：重建后按 id 找回位置，再套用原来的顶部偏移。
     */
    private String lastAnchorId = "";
    private int lastAnchorTop;
    /** 转屏 recreate 后要走一次位置恢复（此时 lastScroller 还是空的） */
    private boolean pendingRestoreScroll = false;
    /** 筛选弹层是否处于「管理分组」模式（点 chip 即删除） */

    private LinearLayout root;
    private FrameLayout paneBox;
    /**
     * 内容区容器：pager 在这里，非浮岛语言（M3 / iOS）的贴底导航也挂在它的底部。
     * 浮岛语言（墨读）只用 pager，底栏另开独立窗口。
     */
    private FrameLayout stage;
    /** 0.9.183：M3 Expressive 专属的右下角 FAB（搜索）；iOS / 墨读 为 null。 */
    private View shelfFab;
    /**
     * 底栏独立窗口（0.9.138）：玻璃必须待在一个 translucent + floating 窗口里，
     * 系统在合成时才会把它下面的书架画面实时卷积。挂在 Activity 内容树里的 View
     * 无论怎么 setRenderEffect 都只能糊自己，采不到兄弟节点。
     * 0.9.159：只有 ui.spec.navFloating（墨读）才创建；M3 / iOS 走内容树内贴底 View。
     */
    private ModouDockWindow dockWindow;
    /** 底栏本体：浮岛语言是窗口 content，M3 / iOS 是挂在 stage 底部的贴底 View；
     *  书源多选态的让位动画统一作用在它身上 */
    private View navDockWrap;
    /** 多选态让位中：onResume 不能把这扇窗口重新 show 出来 */
    private boolean dockHiddenBySel;
    /** 胶囊尺寸与离底距离：分屏 / 转屏后重设窗口用 */
    private int dockWinW, dockWinH, dockWinBottom;
    private float dockWinCorner;
    /** 首页横滑容器：书架 / 书源 / 规则 / 设置 四页原地切换 */
    private HomePager pager;
    /** 书源页 / 设置页的实例：它们要开系统文件选择器，回调得转发回去 */
    private SourcesPanel sourcesPanel;
    private SettingsPanel settingsPanel;
    /** 头部容器：墨读/横屏是原来那一行 LinearLayout；M3/iOS 竖屏是带材质层的
     *  FrameLayout 覆盖层（0.9.165 · B5）。声明成 View 才能两种都装。 */
    private View headBox;
    /** 头部大标题：随「书架 / 阅读历史 / 收藏」切换文案；非墨读语言滚动时淡出 */
    private TextView headTitle;
    /** 非书架视图时出现的圆形返回钮（页签整行已去掉，靠它回退） */
    private ImageView headBack;
    /** B5（0.9.165）：书架固定工具条的滚动镀铬。墨读 / 横屏恒为 null，
     *  headBox 保持 0.9.164 原样，滚动容器一个监听都不注册，逐像素零回归。 */
    private ScrollChrome shelfChrome;
    /** 0.9.166 真·Large Title：内容区大标题块（不随 paintPane 重建）、副标题、
     *  滚动容器顶部留白、分组吸顶基准。墨读 / 横屏为 null / 0，完全不生效。 */
    private View shelfTitleBlock;
    private TextView shelfSub;
    private int shelfInsetPx;
    private int stickyTopPx;
    /**
     * 底栏配色（只作用于底部四个 tag，不动全局色板）：
     * 0.9.155 起四个 tag 恒用一套线性图标，选中不再「线→面」整枚替换（旧版切页像
     * 「哪盏灯亮了」，被判不够精致）。改为仅靠前景浓淡 + 图标轻微放大 + 文字 medium
     * 字重区分，且浓淡是同一墨色的深浅，不做彩色开关。
     *   navOff  未选中前景：浅色 65% 墨 / 夜间 50% 白
     *   navOn   选中前景：浅色近实黑 / 夜间纯白
     */
    private int navOff = 0xA614171C;
    private int navOn = 0xEE14171C;

    /** 底部 tag 的图标+文字，切页时刷新选中色 */
    private final List<LinearLayout> navItems = new ArrayList<>();
    private final List<ImageView> navIcons = new ArrayList<>();
    private final List<TextView> navLabels = new ArrayList<>();
    /** 0.9.159 三态导航：M3 的 active indicator 药丸、以及选中/未选中两套图标资源。
     *  墨读与 iOS 不用药丸（列表里存 null），仍只切 tint。 */
    private final List<View> navIndicators = new ArrayList<>();
    private final List<Integer> navIconLine = new ArrayList<>();
    private final List<Integer> navIconFill = new ArrayList<>();

    /** onCreate 也会走一次 onResume，首次不必重读（reload 已在 onCreate 末尾调过） */
    private boolean resumed;

    /** 本页是否被盖住了（阅读页/导入页在上面）：盖住期间一律不读库、不刷新 */
    private boolean paused;
    /** lite 回填线程是否在跑（避免重复起线程） */
    private boolean backfilling;
    /** 当前方向：横竖屏切换时布局几何全变，需要重建这一屏 */
    private int cfgOrientation = -1;

    /**
     * 0.9.48 诊断锁：onCreate 全程 try-catch —— 启动崩溃不再闪退，
     * 直接在屏幕上显示完整堆栈并自动复制到剪贴板，错误原文同时落盘到
     * 外部可见目录（下载/Download 与 Android/data）。
     * 之前崩溃日志只写内部私有目录，App 一崩用户就拿不到，排查全靠猜。
     */
    @Override
    protected void onCreate(Bundle b) {
        ModouUi.applyTheme(this); // 必须早于 super：窗口底色/系统栏图标在此定型
        super.onCreate(b);
        try {
            onCreateImpl(b);
        } catch (Throwable e) {
            showCrashScreen(e);
        }
    }

    /** 被诊断锁兜住的启动崩溃：落盘 + 复制 + 屏显，App 保持打开状态 */
    private void showCrashScreen(Throwable e) {
        try {
            Diagnostics.capture("书架启动", e);
            String trace = android.util.Log.getStackTraceString(e);
            // 自动复制：用户切到微信直接粘贴就能发，不用找文件
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "modou-crash", trace));
            } catch (Throwable ignore) {
            }
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText("书架启动失败（已复制到剪贴板，可直接粘贴发送）\n\n"
                    + trace
                    + "\n\n错误原文已写入：\n· 手机 Download 目录 modou-crash-*.txt\n"
                    + "· Android/data/cn.modou.app.debug/files/diag/");
            tv.setTextIsSelectable(true);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextColor(0xFF222222);
            tv.setBackgroundColor(0xFFFFFFFF);
            int pad = ModouUi.dp(this, 16);
            tv.setPadding(pad, pad, pad, pad);
            sv.addView(tv, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setContentView(sv);
        } catch (Throwable ignore) {
            // 连错误页都画不出来，只能交回系统
            finish();
        }
    }

    /** 原 onCreate 主体（0.9.48 起被 onCreate 的诊断锁包住） */
    private void onCreateImpl(Bundle b) {
        ui = ModouUi.of(this);
        cfgOrientation = getResources().getConfiguration().orientation;
        // 0.9.159：导航选中/未选中前景改从设计语言规格读。
        // 墨读取值与 0.9.155 完全一致（零回归）；M3 用 onContainer / onSurfaceVariant、
        // iOS 用系统蓝 / 次级灰，都是各自语言的选中语义。
        navOff = ui.spec.navOffColor;
        navOn = ui.spec.navOnColor;
        store = ModouStore.get(this);
        // 进程被回收 / 多窗口尺寸变化时系统会重建 Activity：从 savedState 里把
        // 「停在哪个 tag、书架的哪个视图」捡回来，别一律打回书架首页
        if (b != null) {
            int pg = b.getInt("pagerPage", 0);
            if (pg > 0) pendingTag = pg;
            String tb = b.getString("shelfTab");
            if (tb != null) pendingTab = tb;
            String gr = b.getString("shelfGroup");
            if (gr != null) group = gr;
            String so = b.getString("shelfSort");
            if (so != null) sort = so;
            // 滚动位置：转屏后列数变了，只能靠锚点书 id 找回
            lastAnchorId = b.getString("shelfAnchor");
            if (lastAnchorId == null) lastAnchorId = "";
            lastAnchorTop = b.getInt("shelfAnchorTop", 0);
            lastFirstPos = b.getInt("shelfFirstPos", -1);
            lastFirstTop = b.getInt("shelfFirstTop", 0);
            lastScrollY = b.getInt("shelfScrollY", 0);
            pendingRestoreScroll = !lastAnchorId.isEmpty() || lastFirstPos > 0 || lastScrollY > 0;
        }
        // 旧版本导入时会另存一份原文件副本到应用私有目录（每本书占两份空间）。
        // 现在改成读用户自己的文件夹、不再复制，这里把历史遗留的副本目录清一次。
        BookFiles.clearOldCopies(this);
        // 旧版 IndexedDB / 导入临时文件 / 已删书的孤儿插图：启动后低优先级清一次。
        // VACUUM 容易长时间占写锁，放到设置页的显式「深度回收」。
        StorageJanitor.autoClean(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 底衬不再是单色：斜向渐变 + 四团冷光（ModouBackdrop）。整屏的「光」都在这一层，
        // 下面所有内层容器都必须留透明，否则会把光斑盖成一整块死色。
        root.setBackground(ModouUi.backdrop(this));
        // 左右不给 root 加 padding：底部导航要通栏；头部/页签自己补。
        // 上下自己补：窗口已铺满系统栏（见下面的 edge-to-edge），状态栏与手势条
        // 都归页面画 —— 这样底衬的渐变能一直铺到屏幕最底边。
        // 横屏也要给：窗口现在铺到状态栏底下，再按 0 算的话标题会被状态栏图标压住
        final int baseTopPad = statusBarPad();
        final int baseBotPad = navBarPad();
        root.setPadding(0, baseTopPad, 0, baseBotPad);
        setContentView(root);
        // 横屏时刘海屏的挖孔在左侧，系统默认把窗口整体右移 → 左边一条纯黑。
        // 允许窗口延伸到短边（挖孔所在边），再用 WindowInsets 的 safeInset 把内容让开，
        // 底衬就能一直铺到屏幕左边缘。
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams wlp = getWindow().getAttributes();
            // SHORT_EDGES 只让窗口延伸「短边」：竖屏时是左右，横屏时是上下。
            // 横屏后挖孔跑到左边（长边），SHORT_EDGES 就不让延伸了，系统在左侧
            // 拉出一条纯黑 letterbox —— 就是那条黑边。API 30 起用 ALWAYS，四边都铺满。
            wlp.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
                    ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wlp);
        }
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            // 0.9.203：改用真实系统栏 insets（含三键/手势导航栏），不再只读挖孔 +
            // 静态 navigation_bar_height；换算与 WebView 壳走同一个 helper。
            int[] si = ModouUi.systemInsetsPx(this, ins);
            int l = si[0], r = si[2];
            int t = Math.max(baseTopPad, si[1]);
            int btm = Math.max(baseBotPad, si[3]);
            if (v.getPaddingLeft() != l || v.getPaddingRight() != r
                    || v.getPaddingTop() != t || v.getPaddingBottom() != btm) {
                v.setPadding(l, t, r, btm);
            }
            return ins;
        });
        // 铺满系统栏（edge-to-edge）：状态栏和手势条那一截也由页面自己画。
        // 不这么做的话，导航条区域显示的是主题里的纯色 app_bg，而页面是渐变底衬，
        // 两者对不上 —— 横屏设置页底下那条深色带就是这么来的。
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        // 系统在导航栏上方默认会画一条分隔线（三键导航尤其明显），浮岛要干净地悬着
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            getWindow().setNavigationBarDividerColor(android.graphics.Color.TRANSPARENT);
        }
        // 关键：导航栏透明时系统会强制铺一层「对比度 scrim」保证图标可见，
        // 那条半透明灰带就是它，横屏下尤其明显（盖住浮岛下半截）。
        // 页面自己已经把底衬铺到屏幕最底，颜色对得上，不需要这层。
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }

        // 书架 pane：头部 + 内容区（书架 / 阅读历史 / 收藏 共用这一个头部），
        // 作为横滑容器的第 0 页。原来的页签整行已去掉：它占掉一条书区高度，
        // 而三个视图的入口收进头部「更多」、返回靠头部的圆形返回钮。
        buildHead();
        paneBox = new FrameLayout(this);
        // 原来靠 root 的左右 padding，root 通栏后由这里统一给内容页留边
        paneBox.setPadding(ModouUi.dp(this, 12), 0, ModouUi.dp(this, 12), 0);

        // 0.9.166：M3 / iOS 竖屏启用真·Large Title —— 滚动容器满屏（从 y=0 起），
        // 大标题块与固定顶栏都是覆盖层；内容靠顶部留白让开，滚动时连续上移填满
        // 收起后的空间。墨读 / 横屏保持 0.9.165「头部 + 内容」上下两行，逐像素零回归。
        final DesignSpec.Gallery sg = ui.spec.gallery;
        final boolean largeOn = sg.shelfChrome && (land() ? sg.shelfChromeLand : true);
        final View shelfPane;
        if (largeOn) {
            FrameLayout f = new FrameLayout(this);
            f.addView(paneBox, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // 大标题块在内容之上、顶栏之下；顶栏最后加，圆钮与材质盖最上层
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ModouUi.dp(this, sg.shelfTitleBlockDp), Gravity.TOP);
            tlp.topMargin = ModouUi.dp(this, 48);
            f.addView(shelfTitleBlock, tlp);
            f.addView(headBox);   // LayoutParams 由 buildHead() 设好
            shelfPane = f;
            shelfInsetPx = ModouUi.dp(this, 48) + ModouUi.dp(this, sg.shelfTitleBlockDp);
            // 吸顶头必须对齐到内容首行（让开 48dp 工具条 + 大标题块），否则会画在 y=48
            // 压在大「书架」标题上，造成「本」字叠到标题右上角的重影。
            stickyTopPx = shelfInsetPx;
        } else {
            LinearLayout s = new LinearLayout(this);
            s.setOrientation(LinearLayout.VERTICAL);
            s.addView(headBox);
            s.addView(paneBox, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            shelfPane = s;
            shelfInsetPx = 0;
            stickyTopPx = 0;
        }

        // 4 个功能页并排：点底部 tag 或左右滑动原地切换（不再跳 Activity——
        // 跳页后还要按返回才能回来，用户明确说这不合理）
        //
        // 后三页先放占位：它们的界面（书源列表 / 替换规则 / 设置）都要读库、建几十个
        // 控件，冷启动时一次性全建出来，等于把书架首帧往后推了三四百毫秒 ——
        // 用户看到的就是「点开 App 先空白一下」。真正滑到哪一页再造哪一页。
        pager = new HomePager(this);
        pager.addView(shelfPane);
        for (int i = 1; i < 4; i++) {
            View slot = HomePager.slot(this);
            slot.setBackgroundColor(android.graphics.Color.TRANSPARENT); // 留透明，露出底衬的光
            pager.addView(slot);
        }
        pager.setLazy(this::buildTagPage);
        pager.setOnPageChange(this::navRefresh);
        // 书源页多选态禁止横滑：右滑直接回书架会把勾选全丢掉
        pager.setSwipeGate(() -> sourcesPanel != null && sourcesPanel.inSelMode());
        // 内容区外面套一层 FrameLayout：底部浮岛 Dock 叠在内容**上面**。
        // 以前 Dock 是 pager 的兄弟（竖排 LinearLayout 的下一条），内容和 Dock 上下分居，
        // Dock 背后永远只有页面底衬 —— 再好的模糊也只能糊到一片纯色，看起来就是实心白瓷砖。
        // 叠上去之后，书封滚到底部时会从玻璃背后穿过。
        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 0.9.138：底栏不再挂在内容树里。它进了一个独立的 translucent + floating 窗口，
        // 系统每帧合成时把窗口 bounds 内的书架画面实时卷积成玻璃。内容树里只留 pager，
        // 封面从玻璃背后滚过时是真正的「下层画面在动」，不是一张抓来的旧图。
        // 等 decor attach 完再建窗口，否则部分 ROM 报 token null。
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View decor = getWindow().getDecorView();
        decor.post(this::attachDockWindow);

        // 导入横幅放最顶上：导入中心有活（导入中/上次中断）就显示，点击回到导入页
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(android.view.Gravity.CENTER_VERTICAL);
        banner.setPadding(ModouUi.dp(this, 12), ModouUi.dp(this, 8), ModouUi.dp(this, 12), ModouUi.dp(this, 8));
        android.graphics.drawable.Drawable bannerBg =
                ModouUi.squircle(this, ModouUi.alpha(ui.accent, 0x16), ui.spec.cornerCard);
        banner.setBackground(bannerBg);
        android.graphics.drawable.RippleDrawable bannerRip = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModouUi.alpha(ui.accent, 0x30)),
                null, bannerBg);
        banner.setBackground(bannerRip);
        importBannerText = ModouUi.tv(this, "", 13, ui.accent);
        banner.addView(importBannerText);
        banner.setOnClickListener((v) -> ImportCenter.open(ShelfActivity.this));
        importBanner = banner;
        importBanner.setVisibility(View.GONE);
        root.addView(importBanner, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 外部（设置面板等）要求直接落在某个 tag
        if (pendingTag > 0) {
            pager.setPage(pendingTag, false);
            navRefresh(pendingTag);
            pendingTag = 0;
        }
        // 重建前停在哪一页、书架的哪个视图：都还回去
        if (pendingTab != null) {
            tab = pendingTab;
            pendingTab = null;
            paintHeadTitle();
        }

        // 冷启动快画：先用上次的轻量快照把书画出来（数据库首次打开要做 WAL
        // 恢复/检查点，章节缓存大时要好几秒，期间书区空白很滞涩），
        // reload() 查完库再校准一次。快照只含书目元数据，无进度无章节。
        paintFromSnapshot();
        reload();
        backfillLite(); // 老库的 lite 列在后台慢慢补，补完自己再刷一次书架
    }

    /**
     * 把书架上的改动写回书籍记录。
     *
     * 书架内存里是轻量记录（没有 chapters 数组），直接 put 会把整份章节表抹掉，
     * 表现就是「改个分组 / 收藏一下，这本书的目录空了、章节数变 0」。
     * 所以先取库里的完整记录当底，再把这次改的字段盖上去。
     */
    private void saveBook(JSONObject lite) {
        String id = lite.optString("id", "");
        JSONObject full = lite;
        try {
            String s = store.get("books", id);
            if (s != null) {
                JSONObject old = new JSONObject(s);
                long oldT = old.optLong("updatedAt", 0);
                java.util.Iterator<String> it = lite.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    old.put(k, lite.opt(k));
                }
                // 别把「最近阅读」的时间倒拨：书架内存里可能是冷启动快照的旧值，
                // 写回去会让刚读过的书在排序里又退回原位（用户看到的就是「顺序变回去」）
                if (oldT > old.optLong("updatedAt", 0)) old.put("updatedAt", oldT);
                full = old;
            }
        } catch (Exception ignore) {
            /* 读不到完整记录就按轻量记录写（总比不保存强） */
        }
        store.put("books", full.toString());
    }

    /** 冷启动快画用的快照（书目元数据 + 分组 + 排序方式） */
    private void paintFromSnapshot() {
        try {
            SharedPreferences sp = getSharedPreferences("shelf_snap", MODE_PRIVATE);
            String s = sp.getString("v", null);
            if (s == null || s.isEmpty() || !books.isEmpty()) return;
            // 老版本把整份书籍记录（含章节表、内联封面）塞进了快照，三十多本能到十几 MB：
            // 在主线程读出来解析正好卡死在启动第一屏。这种快照直接丢掉，走数据库。
            if (s.length() > 200_000) {
                sp.edit().remove("v").apply();
                return;
            }
            JSONObject o = new JSONObject(s);
            JSONArray ba = o.optJSONArray("books");
            if (ba == null || ba.length() == 0) return;
            for (int i = 0; i < ba.length(); i++) {
                JSONObject b = ba.optJSONObject(i);
                if (b != null) books.add(b);
            }
            JSONArray ga = o.optJSONArray("groups");
            groups.add(ALL);
            if (ga != null) {
                for (int i = 0; i < ga.length(); i++) {
                    String g = ga.optString(i, "");
                    if (!g.isEmpty() && !groups.contains(g)) groups.add(g);
                }
            }
            sort = o.optString("sort", "recent");
            paintHeadTitle();
            paintPane();
        } catch (Exception ignore) {
            // 快照坏了就当没有，走正常加载
        }
    }

    /** reload 完成后落一份快照，供下次冷启动快画（只存元数据，体量几百 KB 内） */
    private void saveSnapshot() {
        try {
            JSONObject o = new JSONObject();
            JSONArray ba = new JSONArray();
            for (JSONObject b : books) ba.put(b);
            JSONArray ga = new JSONArray();
            for (String g : groups) if (!ALL.equals(g)) ga.put(g);
            o.put("books", ba);
            o.put("groups", ga);
            o.put("sort", sort);
            getSharedPreferences("shelf_snap", MODE_PRIVATE).edit().putString("v", o.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    /** launch 重载：打开首页并直接落到某个底部 tag（0 书架 / 1 书源 / 2 规则 / 3 设置） */
    private static int pendingTag = 0;

    static void launch(Activity from, int tag) {
        pendingTag = tag;
        from.startActivity(new Intent(from, ShelfActivity.class));
    }

    /** recreate 前记住当前 tag（如设置里切深浅色）：重建后回到原页而不是书架首页 */
    static void rememberPage(ShelfActivity a, int tag) {
        pendingTag = tag;
    }

    /**
     * recreate 前连「书架 / 阅读历史 / 收藏」一起记住。
     *
     * 只记 tag 的话，在历史页里改外观重建之后会被放回书架首页 —— 用户看到的就是
     * 「闪一下就回到书架首页」。
     */
    private static String pendingTab = null;

    /** 当前 tag 页（0 书架 / 1 书源 / 2 规则 / 3 设置） */
    int currentPage() {
        return pager == null ? 0 : pager.page();
    }

    /**
     * 按需建功能页（由 HomePager 在第一次滑到/点到时调用）。
     * 冷启动只建书架页，另外三页的读库与控件构建都推迟到真正需要的时候。
     */
    private View buildTagPage(int i) {
        switch (i) {
            case 1:
                sourcesPanel = new SourcesPanel(this);
                // 多选态 ↔ 底部全局导航互斥：批量操作栏升起时导航让位，退出再回归
                sourcesPanel.setOnSelModeChange(this::onSourcesSelMode);
                return sourcesPanel.view(true);
            case 2:
                return new RulesPanel(this).view(true);
            case 3:
                settingsPanel = new SettingsPanel(this);
                return settingsPanel.view(true);
            default:
                return new View(this);
        }
    }

    /**
     * 嵌入页（书源 / 设置）会开系统文件选择器，结果得转发给它们。
     *
     * 这两个面板嵌在书架的 HomePager 里，没有自己的 Activity，以前书架也没实现
     * 这个方法 —— 选完文件回来没人接，导入/导出备份都停在「点了没反应」。
     */
    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (sourcesPanel != null) sourcesPanel.onActivityResult(req, res, data);
        if (settingsPanel != null) settingsPanel.onActivityResult(req, res, data);
    }

    /** 嵌入页内部跳转：直接把横滑容器切到某个 tag（不再另开 Activity） */
    void showTag(int i) {
        if (pager != null && pager.page() != i) pager.setPage(i, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 外观可能在别处被改过（阅读页点夜间 / 设置里换外观 / 系统日落切深浅）。
        // 书架的颜色是建 View 时取好写死的，不重建就会「书架还是黑的、新弹的菜单却是浅的」。
        if (ModouUi.themeChanged(this)) {
            // 重建前记住停在哪一页，否则重建后被踹回书架首页（这个坑之前踩过一次）
            if (pager != null) pendingTag = pager.page();
            pendingTab = tab;
            ModouUi.syncTheme(this);
            return;
        }
        // 后台更新兜底：国产 ROM 常拦 JobScheduler，靠系统唤醒基本等不到。
        // 用户回到书架时顺手补一次（6 小时内不重复），完成后再重读一遍书架。
        ShelfUpdater.maybeCatchUp(this, this::reload);
        // 从导入页/详情页回来要重读一遍：新导的书、刚改的分组、阅读进度都只写在库里，
        // 不重读就会看到一本「刚才明明导入成功了却不在书架」的错觉
        paused = false;
        // 回到前台：只有墨读的独立窗口需要 show 回来（系统模糊每帧自己更新）。
        // M3 / iOS 的贴底 View 在内容树里，随 Activity 可见性自然显示，不必重建，
        // 也不该被 dockWindow == null 反复 post attachDockWindow。
        if (navDockWrap == null) {
            getWindow().getDecorView().post(this::attachDockWindow);
        } else if (!dockHiddenBySel && dockWindow != null) {
            dockWindow.show();
        }
        if (resumed) {
            reload();
            backfillLite();
        }
        resumed = true;
        refreshImportBanner();
        importTickH.postDelayed(importTick, 700);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 盖住本页就收手：阅读页正在取章节，书架不该再占着数据库锁和主线程
        paused = true;
        importTickH.removeCallbacks(importTick);
        // 独立窗口是挂在 Activity token 上的顶层窗口，不 hide 会浮在下一个 Activity 上面
        if (dockWindow != null) dockWindow.hide();
    }

    @Override
    protected void onDestroy() {
        if (dockWindow != null) dockWindow.dismiss();
        dockWindow = null;
        super.onDestroy();
    }

    /** 导入横幅：只读 ImportCenter 快照，导入中/上次中断才显示 */
    private void refreshImportBanner() {
        if (importBanner == null || importBannerText == null) return;
        try {
            JSONObject snap = ImportCenter.get().snapshot();
            boolean running = snap.optBoolean("running", false);
            boolean interrupted = snap.optBoolean("interrupted", false);
            int total = snap.optInt("total", 0);
            int done = snap.optInt("done", 0);
            String stage = snap.optString("stage", "");
            if (running && total > 0) {
                importBannerText.setText("正在导入 " + done + "/" + total + " · " + stage);
                importBanner.setVisibility(View.VISIBLE);
            } else if (interrupted && total > 0 && done < total) {
                importBannerText.setText("上次导入中断 " + done + "/" + total + " · 点此重新导入");
                importBanner.setVisibility(View.VISIBLE);
            } else {
                importBanner.setVisibility(View.GONE);
            }
        } catch (Exception ignore) {
            /* 横幅刷新失败不影响书架 */
        }
    }

    /**
     * 书架就是应用根页：返回应当回桌面。
     * 以前是 finish() 落到下面那层网页壳（它的书架已被删除，只会看到一张空页），
     * 所以改成 moveTaskToBack——任务栈不动，下次进来还是这个书架。
     * 停在其他功能 tag 时，返回先回到书架页。
     */
    @Override
    public void onBackPressed() {
        // 书源页多选态：返回（含手势返回）先退多选，不要一路退回书架
        if (sourcesPanel != null && sourcesPanel.inSelMode()) {
            sourcesPanel.exitSelection();
            return;
        }
        if (pager != null && pager.page() != 0) {
            pager.setPage(0, true);
            return;
        }
        // 阅读历史 / 收藏是从「更多」进的次级视图：返回先回书架（等同于头部返回圆钮）
        if (!"shelf".equals(tab)) {
            switchTab("shelf");
            return;
        }
        moveTaskToBack(true);
    }

    /**
     * 横竖屏切换：声明了 configChanges，系统不会重建 Activity。
     * 列数、封面高、Dock 宽度全是按屏幕宽度算的，不重建就会继续用竖屏那套几何
     * （横屏下就是「3 列被拉成砖块、底栏贯通全屏」）。这里直接重建这一屏。
     */
    @Override
    protected void onSaveInstanceState(android.os.Bundle out) {
        super.onSaveInstanceState(out);
        if (pager != null) out.putInt("pagerPage", pager.page());
        out.putString("shelfTab", tab);
        out.putString("shelfGroup", group);
        out.putString("shelfSort", sort);
        // 滚动位置一起带走：recreate 后按锚点书 id 找回，横竖屏来回切不跳回顶部
        captureScrollAnchor();
        out.putString("shelfAnchor", lastAnchorId);
        out.putInt("shelfAnchorTop", lastAnchorTop);
        out.putInt("shelfFirstPos", lastFirstPos);
        out.putInt("shelfFirstTop", lastFirstTop);
        out.putInt("shelfScrollY", lastScrollY);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (cfgOrientation != c.orientation) {
            cfgOrientation = c.orientation;
            // recreate 会重建整个 Activity：不先记下当前横滑页，重建后就回到第 0 页
            // （在设置/书源页一转屏就被踹回书架，就是这么来的）
            if (pager != null) pendingTag = pager.page();
            pendingTab = tab;
            recreate();
        }
    }

    /** 横屏：整页按紧凑布局排（头部压成一行、列数增加、底栏限宽） */
    private boolean land() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /* ---------------- 响应式：列数与封面尺寸 ---------------- */

    /** 内容可用宽度（扣掉书架左右各 12dp 的留白） */
    private int contentW() {
        return Math.max(ModouUi.dp(this, 320),
                getResources().getDisplayMetrics().widthPixels - ModouUi.dp(this, 24));
    }

    /** 每行列数：按「封面目标宽 104dp」算，竖屏 3 列，横屏/平板自动加列（上限 8） */
    private int gridCols() {
        int gap = ModouUi.dp(this, 14);
        int target = ModouUi.dp(this, 104);
        int cols = (contentW() + gap) / (target + gap);
        return Math.max(3, Math.min(cols, 8));
    }

    /** 封面高：严格 3:4，宽度由所在行等分决定，所以这里按同一套宽度公式反算 */
    private int coverHFor(int cols) {
        int gap = ModouUi.dp(this, 14);
        int w = (contentW() - gap * Math.max(0, cols - 1)) / Math.max(1, cols);
        return Math.round(w * 4f / 3f);
    }

    private int statusBarPad() {
        // 状态栏高度拿不准时给个保守值：宁可多一点，也别把标题顶到状态栏底下
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : ModouUi.dp(this, 24);
    }

    private int navBarPad() {
        // 0.9.203：优先读真实 insets（三键/手势切换会变），静态 dimen 仅作首帧兜底。
        android.view.WindowInsets ins = getWindow().getDecorView().getRootWindowInsets();
        if (ins != null) return ModouUi.systemInsetsPx(this, ins)[3];
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /**
     * 书源多选态 ↔ 底部全局导航互斥。
     *
     * <p>书源页的批量操作栏是面板内部的一条悬浮岛，全局导航也是悬浮岛 ——
     * 两个岛都钉在底部就必然叠字。让位规则：进入多选，全局导航向下滑出屏幕并
     * GONE（把底部安全空间整个让给批量操作栏）；退出多选再滑回来。
     */
    private void onSourcesSelMode(boolean sel) {
        if (navDockWrap == null) return;
        dockHiddenBySel = sel;
        navDockWrap.animate().cancel();
        // 没量过高度（还没布局）时按「胶囊 50 + 上下边距」估算，保证也能完全滑出
        float hide = navDockWrap.getHeight() > 0
                ? navDockWrap.getHeight() + ModouUi.dp(this, 8)
                : ModouUi.dp(this, 90);
        if (sel) {
            navDockWrap.animate().translationY(hide).alpha(0f).setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                    .withEndAction(() -> {
                        // 只把 content 设 GONE 是不够的：窗口 bounds 还在，那块背景模糊
                        // 会留在屏幕上变成一条空玻璃。必须整扇窗口 hide 掉。
                        if (sourcesPanel != null && sourcesPanel.inSelMode()
                                && dockWindow != null) {
                            dockWindow.hide();
                        }
                    })
                    .start();
        } else {
            if (dockWindow != null) dockWindow.show();
            navDockWrap.setVisibility(View.VISIBLE);
            navDockWrap.setTranslationY(hide);
            navDockWrap.setAlpha(0f);
            navDockWrap.animate().translationY(0f).alpha(1f).setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    /* ---------------- 底部导航 ---------------- */

    /**
     * 常驻功能入口：书架 / 书源 / 规则 / 设置。
     * 以前这些页只能从设置页一层层点进去，首页没有任何功能 tag —— 找个书源管理
     * 要三步。现在一级入口放底部，和网页版原来的侧栏/底栏同级。
     * 点 tag 原地切换（横滑容器），不跳 Activity；左右滑动同样可以翻页。
     */
    private View buildBottomNav() {
        DesignSpec s = ui.spec;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        navItems.clear();
        navIcons.clear();
        navLabels.clear();
        navIndicators.clear();
        navIconLine.clear();
        navIconFill.clear();
        // 0.9.159：三套各用符合本语言的图标（墨读线性几何 / M3 Material Symbols
        // outlined+filled / iOS SF Symbols），全部从 spec 数组读，页面不写语言分支。
        bar.addView(navItem("书架", s.navIconLine[0], s.navIconFill[0], 0));
        bar.addView(navItem("书源", s.navIconLine[1], s.navIconFill[1], 1));
        bar.addView(navItem("规则", s.navIconLine[2], s.navIconFill[2], 2));
        bar.addView(navItem("设置", s.navIconLine[3], s.navIconFill[3], 3));

        FrameLayout box = new FrameLayout(this);
        if (s.navFloating) {
            // ---- 墨读：悬浮胶囊浮岛，尺寸由独立窗口 bounds 决定（原路径，零回归）----
            bar.setPadding(ModouUi.dp(this, 6), 0, ModouUi.dp(this, 6), 0);
            int dockH = ModouUi.dp(this, s.navHeightDp);
            dockWinW = calcDockWidth();
            dockWinH = dockH;
            dockWinBottom = ModouUi.dp(this, s.navBottomGapDp) + navBarPad();
            dockWinCorner = dockH * 0.5f;
            bar.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View glass = ModouGlass.dockMaterial(this, 999);
            box.addView(glass, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            box.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            box.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // ---- M3 / iOS：内容树内贴底全宽，不建独立窗口、不挂系统模糊 ----
            if (s.navGlassMaterial) {
                // iOS 26 Tab Bar：Liquid Glass 材质（半透基色 + 顶部凝光 + 边缘泛光），
                // 不是平涂半透明色块。窗口内 View 拿不到兄弟节点做实时模糊（历史坑），
                // 故用材质层模拟 —— 与 §2.5 的 iOS 弹层策略一致。
                box.setBackground(new TabBarGlass(s.navBarColor, s.navGlassHighlight));
            } else {
                // M3 Navigation Bar：surface 实色 + 5% primary tonal tint（tonal elevation），
                // 层级不靠阴影投影，这是 M3 与 iOS / 墨读最本质的分层差异。
                android.graphics.drawable.GradientDrawable base =
                        new android.graphics.drawable.GradientDrawable();
                base.setColor(s.navBarColor);
                if (s.navSurfaceTint != 0) {
                    android.graphics.drawable.GradientDrawable tint =
                            new android.graphics.drawable.GradientDrawable();
                    tint.setColor(s.navSurfaceTint);
                    box.setBackground(new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{base, tint}));
                } else {
                    box.setBackground(base);
                }
            }
            if (s.navBarElevationDp > 0) {
                box.setElevation(ModouUi.dp(this, s.navBarElevationDp));
            }
            // iOS Tab Bar 顶部的 0.5px 分隔线（M3 靠 tonal elevation 分层，不画线）
            if (s.navDividerColor != 0) {
                View line = new View(this);
                line.setBackgroundColor(s.navDividerColor);
                box.addView(line, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(1, ModouUi.dp(this, 0.5f)), Gravity.TOP));
            }
            box.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            box.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ModouUi.dp(this, s.navHeightDp), Gravity.BOTTOM));
        }
        return box;
    }

    /**
     * iOS 26 Tab Bar 的 Liquid Glass 材质（0.9.159）。
     *
     * <p>贴底全宽矩形：半透明基色透出页面内容，顶部一道镜面凝光（受光边），
     * 顶缘一根极细高光，底部一层极淡内阴影把材质「压」在屏幕上。
     * 不抓屏、不挂系统模糊 —— 内容树内的 View 采不到兄弟节点，实时模糊只能由独立窗口
     * 完成（见 §6 历史坑），这里按 iOS 规范用材质层近似，零窗口骨架改动。
     */
    private static final class TabBarGlass extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fill =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint sheen =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint shade =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int baseColor, highlight;
        private android.graphics.LinearGradient sheenGrad, shadeGrad;
        private final android.graphics.RectF rect = new android.graphics.RectF();

        TabBarGlass(int baseColor, int highlight) {
            this.baseColor = baseColor;
            this.highlight = highlight;
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            rect.set(b);
            float h = rect.height();
            if (h <= 0) return;
            // 受光边：白色镜面凝光从上往下快速衰减（占高度 55%）
            sheenGrad = new android.graphics.LinearGradient(0, rect.top, 0, rect.top + h * 0.55f,
                    new int[]{highlight, 0x00000000}, new float[]{0f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            // 底部内阴影：只在下缘 30% 起一点点黑，材质才有厚度
            shadeGrad = new android.graphics.LinearGradient(0, rect.bottom - h * 0.30f, 0, rect.bottom,
                    new int[]{0x00000000, 0x14000000}, new float[]{0f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            invalidateSelf();
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            if (rect.width() <= 0 || rect.height() <= 0) return;
            fill.setStyle(android.graphics.Paint.Style.FILL);
            fill.setColor(baseColor);
            canvas.drawRect(rect, fill);
            // 0.9.181：内容树里的 View 拿不到实时模糊，单层 51% 基色会让封面/书名直接透过
            // 标签，看着像导航栏坏掉。把基色叠三遍（0.51 → 约 0.88）模拟 blur 的遮蔽力。
            canvas.drawRect(rect, fill);
            canvas.drawRect(rect, fill);
            if (sheenGrad != null) {
                sheen.setShader(sheenGrad);
                canvas.drawRect(rect, sheen);
            }
            if (shadeGrad != null) {
                shade.setShader(shadeGrad);
                canvas.drawRect(rect, shade);
            }
            // 顶缘 1px 高光：Liquid Glass 的「受光边」，比整片凝光更立得住边界
            if (highlight != 0) {
                fill.setColor(highlight);
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + 1.5f, fill);
            }
        }

        @Override
        public void setAlpha(int a) { fill.setAlpha(a); invalidateSelf(); }
        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            fill.setColorFilter(cf); invalidateSelf();
        }
        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 横屏 / 分屏 / 小窗：重新算胶囊宽度（0.9.139 收窄成浮岛，封顶 420dp） */
    private int calcDockWidth() {
        int sideGap = ModouUi.dp(this, 28);
        int cap = land()
                ? Math.min(ModouUi.dp(this, 420), getResources().getDisplayMetrics().widthPixels / 2)
                : ModouUi.dp(this, 420);
        int w = Math.min(getResources().getDisplayMetrics().widthPixels - sideGap * 2, cap);
        return Math.max(w, ModouUi.dp(this, 240));
    }

    /**
     * decor attach 之后建底栏。0.9.159 按设计语言分两条路：
     * 墨读走独立玻璃窗口（系统实时模糊）；M3 / iOS 直接把贴底全宽 View 挂进 stage，
     * 不创建 ModouDockWindow —— 这两种语言规范里就没有悬浮浮岛，也不该有系统模糊。
     */
    private void attachDockWindow() {
        if (navDockWrap != null || isFinishing() || isDestroyed() || paused) return;
        DesignSpec s = ui.spec;
        if (!s.navFloating) {
            navDockWrap = buildBottomNav();
            if (stage != null && navDockWrap.getParent() == null) {
                stage.addView(navDockWrap,
                        (FrameLayout.LayoutParams) navDockWrap.getLayoutParams());
            }
            // M3 Expressive：右下角 extended FAB（搜索）。iOS / 墨读 不加 —— 这是三套语言在
            // 书架首屏上「只有自己有」的结构件，不是换色。
            if (s.material == DesignSpec.Material.TONAL) buildShelfFab(s);
            return;   // 贴底 View 随内容树走生命周期，不需要 show/hide/dismiss
        }
        if (!ModouGlass.available(this)) return;
        navDockWrap = buildBottomNav();
        dockWindow = new ModouDockWindow(this);
        dockWindow.attach(navDockWrap, dockWinW, dockWinH, dockWinBottom, dockWinCorner);
        // 分屏/小窗宽度变化不会走 recreate：按 decor 实际宽度重设窗口 bounds，
        // 否则胶囊会停在旧宽度上（一边留一大截空白，或横向溢出）。
        getWindow().getDecorView().addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, orr, ob) -> {
                    if (dockWindow == null || (r - l) == (orr - ol)) return;
                    dockWinW = calcDockWidth();
                    dockWindow.update(dockWinW, dockWinH, dockWinBottom, dockWinCorner);
                });
    }

    /** 浮岛压在内容上，列表底部要留出它的高度，最后一排书才不会被盖住 */
    private int dockFoot() {
        // 四处共用 ModouUi.dockFootPx：书架、设置、书源、规则，底部留白必须是同一个数，
        // 否则滚到底时只有一个页面多出一条空白（就是「底部那块灰带」）。
        int f = ModouUi.dockFootPx(this);
        // M3 的搜索 FAB 浮在内容之上，底部再多留一个 FAB 的高度，
        // 滚到底时最后一排书名不会被 FAB 压住。
        if (ui.spec.material == DesignSpec.Material.TONAL) f += ModouUi.dp(this, 72);
        return f;
    }

    /**
     * 单个 tag（0.9.159 起：三态导航）。选中语义完全由 {@code ui.spec} 决定，页面不写语言判断：
     * <ul>
     *   <li>墨读 FLOATING_ISLAND：恒线性图标，前景浓淡 + 8% 放大 + medium 字重（与 0.9.155 逐像素一致）</li>
     *   <li>M3 M3_NAV_BAR：图标后垫一枚 64×32 active indicator 药丸，选中换 filled 图标</li>
     *   <li>iOS IOS_TAB_BAR：恒线性图标，仅前景 tint 随选中变系统蓝，无药丸、无放大</li>
     * </ul>
     * 按压水波仍是圆角限定的透明波纹，不留下常驻方块。四个 tag 四等分，点击切页。
     */
    private View navItem(String label, int lineRes, int fillRes, final int idx) {
        DesignSpec sp = ui.spec;
        boolean selected = idx == 0;
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);

        // 常态背景透明，仅保留圆角限定的按压水波，无常驻色块。
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(ModouUi.dp(this, 17));
        android.graphics.drawable.RippleDrawable rip = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModouUi.alpha(ui.text, 0x1E)),
                new android.graphics.drawable.ColorDrawable(0x00000000), mask);
        it.setBackground(ui.spec.material == DesignSpec.Material.GLASS ? rip
                : ModouUi.pressOverlay(this, 17));
        it.setContentDescription(label);
        it.setSelected(selected);

        ImageView iv = new ImageView(this);
        // 图标尺寸按语言：墨读 21 / M3 24 / iOS 25。首帧就按选中态上色，不等 navRefresh。
        int iconPx = ModouUi.dp(this, sp.navIconDp);
        // 只有 M3 会在「线性 ↔ 实心」之间换图；墨读 / iOS 恒用线性那套。
        int firstRes = (sp.navIndicator && selected) ? fillRes : lineRes;
        android.graphics.drawable.Drawable d = getResources()
                .getDrawable(firstRes, getTheme()).mutate();
        d.setTint(selected ? navOn : navOff);
        iv.setImageDrawable(d);
        if (sp.navScaleSelected && selected) { iv.setScaleX(1.08f); iv.setScaleY(1.08f); }

        View indicator = null;
        if (sp.navIndicator) {
            // M3：图标压在 active indicator 药丸之上 —— 用 FrameLayout 叠两层，
            // 药丸用 alpha 控制显隐，切页时能做 200ms 淡入淡出而不跳变。
            FrameLayout slot = new FrameLayout(this);
            indicator = new View(this);
            android.graphics.drawable.GradientDrawable ig =
                    new android.graphics.drawable.GradientDrawable();
            ig.setColor(sp.navIndicatorColor);
            ig.setCornerRadius(ModouUi.dp(this, sp.navIndicatorHDp * 0.5f));
            indicator.setBackground(ig);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                    ModouUi.dp(this, sp.navIndicatorWDp), ModouUi.dp(this, sp.navIndicatorHDp));
            ilp.gravity = Gravity.CENTER;
            slot.addView(indicator, ilp);
            indicator.setAlpha(selected ? 1f : 0f);
            FrameLayout.LayoutParams ivp = new FrameLayout.LayoutParams(iconPx, iconPx);
            ivp.gravity = Gravity.CENTER;
            slot.addView(iv, ivp);
            it.addView(slot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ModouUi.dp(this, sp.navIndicatorHDp + 8)));
        } else {
            // 墨读 / iOS：图标直接进竖列，保留墨读原来的上下边距（零回归）。
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconPx, iconPx);
            ip.topMargin = ModouUi.dp(this, 3);
            ip.bottomMargin = ModouUi.dp(this, 1);
            it.addView(iv, ip);
        }
        navIndicators.add(indicator);   // 墨读 / iOS 存 null，navRefresh 会跳过
        navIcons.add(iv);
        navIconLine.add(lineRes);
        navIconFill.add(fillRes);

        // 文字铺满整格 + 内容居中：不依赖 ROM 对 wrap_content 的 gravity 解释。
        // 字号按语言：墨读 11 / M3 12 / iOS 10。
        TextView t = ModouUi.tv(this, label, sp.navLabelSp, selected ? navOn : navOff);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        if (selected) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        it.addView(t);
        navLabels.add(t);
        navItems.add(it);

        it.setOnClickListener(v -> {
            if (pager.page() != idx) pager.setPage(idx, true);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        it.setLayoutParams(lp);
        return it;
    }

    /**
     * 切页回调（0.9.159 三态）。墨读路径与 0.9.155 完全一致（只改 tint + 8% 放大 +
     * medium 字重）；M3 额外切 filled/linear 图标并淡入淡出 indicator 药丸；iOS 只改 tint。
     */
    private void navRefresh(int idx) {
        DesignSpec sp = ui.spec;
        for (int i = 0; i < navIcons.size(); i++) {
            boolean on = i == idx;
            ImageView iv = navIcons.get(i);
            if (sp.navIndicator) {
                // M3：整枚换图（选中实心 / 未选中线性）+ 药丸淡入淡出
                int res = on ? navIconFill.get(i) : navIconLine.get(i);
                android.graphics.drawable.Drawable nd = getResources()
                        .getDrawable(res, getTheme()).mutate();
                nd.setTint(on ? navOn : navOff);
                iv.setImageDrawable(nd);
                View ind = i < navIndicators.size() ? navIndicators.get(i) : null;
                if (ind != null) {
                    ind.animate().cancel();
                    if (on && ModouMotion.scale(this) > 0f) ind.setScaleX(0.62f);
                    ind.animate().alpha(on ? 1f : 0f).scaleX(on ? 1f : 0.82f)
                            .setDuration(ModouMotion.duration(this, sp.durShort))
                            .setInterpolator(ModouMotion.standard(this)).start();
                    iv.animate().scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f)
                            .setDuration(ModouMotion.duration(this, sp.durShort)).start();
                }
            } else {
                // 墨读 / iOS：只改现有 drawable 的 tint，不换图形
                android.graphics.drawable.Drawable d = iv.getDrawable();
                if (d != null) {
                    d.mutate().setTint(on ? navOn : navOff);
                    d.invalidateSelf();
                }
            }
            // 选中放大 8%：只有墨读开（M3 / iOS 关）
            if (sp.navScaleSelected) {
                float sc = on ? 1.08f : 1f;
                iv.animate().scaleX(sc).scaleY(sc).setDuration(sp.durShort).start();
            }
            // 文字同步浓淡与字重
            navLabels.get(i).setTextColor(on ? navOn : navOff);
            navLabels.get(i).setTypeface(on
                    ? android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    : android.graphics.Typeface.DEFAULT);
            navItems.get(i).setSelected(on);
        }
        refreshShelfFab(idx);
    }

    /**
     * M3 FAB 只在「书架」页出现：切到书源 / 规则 / 设置时收掉。
     * iOS / 墨读 没有这个组件（shelfFab 为 null），直接跳过。
     */
    private void refreshShelfFab(int idx) {
        if (shelfFab == null) return;
        boolean show = idx == 0;
        if (show && shelfFab.getVisibility() != View.VISIBLE) {
            shelfFab.setVisibility(View.VISIBLE);
            shelfFab.setAlpha(0f);
            shelfFab.setScaleX(0.7f);
            shelfFab.setScaleY(0.7f);
            shelfFab.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(ModouMotion.duration(this, ui.spec.durMed))
                    .setInterpolator(ModouMotion.standard(this)).start();
        } else if (!show && shelfFab.getVisibility() == View.VISIBLE) {
            shelfFab.animate().cancel();
            shelfFab.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f)
                    .setDuration(ModouMotion.duration(this, ui.spec.durShort))
                    .setInterpolator(ModouMotion.exit(this))
                    .withEndAction(() -> {
                        if (shelfFab != null) shelfFab.setVisibility(View.GONE);
                    }).start();
        }
    }

    /* ---------------- 头部 ---------------- */

    private void buildHead() {
        final boolean land = land();
        final DesignSpec.Gallery g = ui.spec.gallery;
        // B5（0.9.165）：规格一处决策，页面不写 if (lang == ...)。
        // 墨读 shelfChrome=false；横屏 shelfChromeLand=false（一行紧凑工具条）；
        // 两者都直接返回原来那一行 LinearLayout，逐像素零回归。
        final boolean chromeOn = g.shelfChrome && (land ? g.shelfChromeLand : true);
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        // 横屏高度只有竖屏的一半，头部按「一行工具条」处理：不要上下留白，字也降一档
        // 左右用全站标准顶栏边距：右侧原来是 4dp，两颗圆钮几乎压在屏幕边上
        h.setPadding(ModouUi.topInset(this), land ? 0 : ModouUi.dp(this, 8),
                ModouUi.topInset(this), land ? 0 : ModouUi.dp(this, 2));

        headBack = ModouUi.backBtn(this, () -> switchTab("shelf"));
        headBack.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ModouUi.dp(this, 40), ModouUi.dp(this, 40));
        blp.rightMargin = ModouUi.dp(this, 6);
        h.addView(headBack, blp);

        if (!chromeOn) {
            // 墨读 / 横屏：大标题仍在工具行内，逐像素保持 0.9.165 原样
            headTitle = ModouUi.pageTitle(this, "书架");
            if (land) headTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f);
            h.addView(headTitle);
        }

        // 横屏不再把「排序 / 分组」胶囊并进标题行：这两项已整合进右上角 ⋮ 的「筛选与分组」，
        // 标题行只留搜索 / 更多两颗圆钮，最大化留给书封面的高度。

        View spacer = new View(this);
        h.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // 右上角两颗互不相连的圆形按钮：搜索 / 更多。
        // 之前是一只长条胶囊装着三枚图标，横在标题右边像一条白色担架；
        // ColorOS 的右上角是两颗各带圆底的独立圆钮。
        // 0.9.184：M3 的搜索入口是右下 FAB，顶栏再放一枚搜索钮就成了「一页两个搜索」。
        // M3 顶栏只留「⋯」；墨读 / iOS 没有 FAB，搜索钮保留。
        if (ui.spec.material != DesignSpec.Material.TONAL) {
            h.addView(roundBtn(ModouGlyph.SEARCH, land ? 34 : 40,
                    v -> SearchActivity.launch(this)));
        }
        h.addView(roundBtn(ModouGlyph.MORE_V, land ? 34 : 40, v -> openMore(v)),
                new LinearLayout.LayoutParams(ModouUi.dp(this, land ? 34 : 40),
                        ModouUi.dp(this, land ? 34 : 40)) {{
                    leftMargin = ModouUi.dp(ShelfActivity.this, 8);
                }});
        if (!chromeOn) {
            // 墨读 / 横屏：逐像素保持 0.9.165 的原一行工具条，一个监听都不注册
            h.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shelfChrome = null;
            shelfTitleBlock = null;
            headBox = h;
            return;
        }

        /* ---------- 0.9.166 真·Large Title：M3 / iOS 竖屏 ----------
         * 工具行固定 48dp 浮在满屏内容之上；大标题不再挤在工具行里，而是内容区
         * 顶部的独立块，随滚动 1:1 被推走（UIKit 的 contentInset 模型）。滚动容器
         * 用顶部留白让开，内容连续上移填满收起后的空间，不会出现「标题淡出留空洞」。 */
        final int barH = ModouUi.dp(this, 48);
        h.setPadding(ModouUi.topInset(this), 0, ModouUi.topInset(this), 0);
        h.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH));

        FrameLayout wrap = new FrameLayout(this);
        wrap.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH, Gravity.TOP));

        // 材质底：iOS 磨砂 / M3 surfaceContainer，颜色读规格（不写语言判断）
        View chrome = new View(this);
        chrome.setBackgroundColor(g.barColor);
        chrome.setAlpha(0f);
        wrap.addView(chrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // M3：滚动后叠一层 primary tint，模拟 app bar 的 tonal elevation
        View tonal = new View(this);
        tonal.setBackgroundColor(ui.spec.navSurfaceTint != 0 ? ui.spec.navSurfaceTint
                : ModouUi.alpha(ui.accent, 0x0D));
        tonal.setAlpha(0f);
        wrap.addView(tonal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        wrap.addView(h); // 工具行（圆钮）叠在最上层，点击逻辑一行未改

        // 收起态 inline 标题：iOS 居中 / M3 靠左（M3 top app bar 标题从不居中）。
        // WRAP_CONTENT 只包文字宽度，不盖住两侧圆钮的点击区。
        TextView inline = ModouUi.tv(this, "书架", 17f, ui.text);
        inline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        inline.setGravity(Gravity.CENTER_VERTICAL);
        inline.setAlpha(0f);
        FrameLayout.LayoutParams ilp = g.shelfInlineCenter
                ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, barH,
                        Gravity.CENTER)
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, barH,
                        Gravity.START | Gravity.CENTER_VERTICAL);
        if (!g.shelfInlineCenter) ilp.leftMargin = ModouUi.dp(this, 52); // 让开左返回钮
        wrap.addView(inline, ilp);

        // iOS scroll edge：顶栏底部 0.5px hairline（M3 由规格关闭驱动，alpha 恒 0）
        View hairline = new View(this);
        hairline.setBackgroundColor(ui.spec.line);
        hairline.setAlpha(0f);
        wrap.addView(hairline, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ModouUi.dp(this, 0.5f)),
                Gravity.BOTTOM));

        headBox = wrap;
        buildShelfTitleBlock(g);
        shelfChrome = ScrollChrome.of(this, g)
                .inlineTitle(inline)
                .hairline(hairline)
                .chrome(chrome)
                .tonal(tonal)
                .bar(wrap)
                .shelfTitle(shelfTitleBlock, g.shelfBigTitleFade, true, g.shelfHairline);
    }

    /**
     * 0.9.166：内容区大标题块，与 {@link LargeTitleHeader} 同构。作为 shelfPane 的
     * 覆盖层独立存在（不随 {@code paintPane()} 重建），高度由
     * {@link DesignSpec.Gallery#shelfTitleBlockDp} 给；字阶走 A8（M3 32sp Medium /
     * iOS 34sp Bold），副标题由 paintPane 按 tab 刷新。
     */
    private void buildShelfTitleBlock(DesignSpec.Gallery g) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(ModouUi.dp(this, 12), ModouUi.dp(this, 6),
                ModouUi.dp(this, 12), 0);
        block.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ModouUi.dp(this, g.shelfTitleBlockDp), Gravity.TOP));

        headTitle = ModouUi.pageTitle(this, "书架");
        headTitle.setIncludeFontPadding(false);
        block.addView(headTitle);

        shelfSub = ModouUi.tv(this, "", 13f, ui.sub);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = ModouUi.dp(this, 6);
        block.addView(shelfSub, slp);

        // iOS 26：大标题正下方挂全宽搜索框（UISearchBar 的 scroll-edge 形态）。
        // M3 用右下 FAB、墨读用右上圆钮 —— 三套语言各有各的搜索入口位置与形态。
        if (g.shelfHeaderStyle == 2) block.addView(buildShelfSearch());

        shelfTitleBlock = block;
    }

    /** iOS 大标题下的搜索框：systemFill 圆角矩形 + 放大镜 + 占位文案 */
    private View buildShelfSearch() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(ModouUi.dp(this, 12), 0, ModouUi.dp(this, 12), 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        // 0.9.204：sysFill3 的灰实心块太重，换更淡的 sysFill2、圆角 12→10。
        bg.setColor(ui.spec.sysFill2 != 0 ? ui.spec.sysFill2 : ModouUi.alpha(ui.text, 0x10));
        bg.setCornerRadius(ModouUi.dp(this, 10));
        pill.setBackground(bg);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new ModouGlyph(ModouGlyph.SEARCH, ui.sub,
                ModouUi.dp(this, 17), ModouUi.dp(this, 1.7f)));
        pill.addView(ic, new LinearLayout.LayoutParams(
                ModouUi.dp(this, 17), ModouUi.dp(this, 17)));
        TextView hint = ModouUi.tv(this, "搜索书名、作者", 13.5f, ui.sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ModouUi.dp(this, 8);
        pill.addView(hint, lp);
        pill.setContentDescription("搜索");
        pill.setOnClickListener(v -> SearchActivity.launch(this));
        ModouUi.expandTouch(pill, 48);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(this, 36));
        plp.topMargin = ModouUi.dp(this, 8);
        pill.setLayoutParams(plp);
        return pill;
    }

    /** M3 Expressive 右下角 extended FAB：主容器色 + 图标 + 文案，56dp 高 */
    private void buildShelfFab(DesignSpec s) {
        if (shelfFab != null || stage == null) return;
        LinearLayout fab = new LinearLayout(this);
        fab.setOrientation(LinearLayout.HORIZONTAL);
        fab.setGravity(Gravity.CENTER);
        // 0.9.204：从带「搜索」文字的 extended FAB 收成 56dp 标准圆 FAB。
        // 亮蓝实底面积减少约六成、投影 6→2dp，Expressive 的悬浮形状保留，
        // 但不再是一整块高饱和色压在封面右下角。primaryContainer 曾试过、
        // 实测对比度不够，故主色实底不动，只缩面积。
        fab.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(s.accent);
        bg.setCornerRadius(ModouUi.dp(this, 28));
        fab.setBackground(bg);
        fab.setElevation(ModouUi.dp(this, 2));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new ModouGlyph(ModouGlyph.SEARCH, s.onAccent,
                ModouUi.dp(this, 20), ModouUi.dp(this, 1.8f)));
        fab.addView(ic, new LinearLayout.LayoutParams(
                ModouUi.dp(this, 20), ModouUi.dp(this, 20)));
        fab.setContentDescription("搜索");
        fab.setOnClickListener(v -> SearchActivity.launch(this));
        ModouMotion.press(fab, 0.96f);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ModouUi.dp(this, 56), ModouUi.dp(this, 56),
                Gravity.BOTTOM | Gravity.END);
        flp.rightMargin = ModouUi.dp(this, 18);
        flp.bottomMargin = ModouUi.dp(this, s.navHeightDp + 18);
        stage.addView(fab, flp);
        shelfFab = fab;
        // 入场：60% 弹到 100%，Expressive 的蓄力感
        fab.setScaleX(0.6f);
        fab.setScaleY(0.6f);
        fab.setAlpha(0f);
        fab.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(ModouMotion.duration(this, 320))
                .setInterpolator(ModouMotion.standard(this)).start();
    }

    /**
     * 右上角独立圆钮：几何规格全部来自 {@link ModouUi#topBtn}（19dp 字形 / 1.8dp 线宽
     * / 40dp 正圆），这里只多套一层玻璃材质。
     *
     * <p>0.9.138：圆钮是 Activity 内容树里的常显覆盖层，没有独立窗口，拿不到系统
     * backdrop 模糊，因此只给材质（半透明基色 + 白色薄纱 + 高光描边），不再抓屏。
     * 书架页保留玻璃底（它下面是会滚动的封面墙，实白会像贴了张纸）；
     * 其余页面是纯白/深灰圆底，但图标资源、字号、线宽完全同源 —— 换页时不会再出现
     * 「这个页面三点更肥、那个页面更细」。
     */
    private View roundBtn(int glyph, int boxDp, View.OnClickListener c) {
        ImageView iv = ModouUi.topBtn(this, glyph, boxDp, c);
        return ui.spec.material == DesignSpec.Material.GLASS
                ? ModouFrost.wrap(iv, 999) : iv;
    }

    /** 圆形图标按钮：38dp 热区、21dp 图标、淡底反馈 */
    private View iconBtn(int resId, View.OnClickListener c) {
        ImageView iv = new ImageView(this);
        int s = ModouUi.dp(this, 38);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setPadding(ModouUi.dp(this, 9), ModouUi.dp(this, 9), ModouUi.dp(this, 9), ModouUi.dp(this, 9));
        android.graphics.drawable.Drawable d = getResources().getDrawable(resId, getTheme()).mutate();
        d.setTint(ui.sub);
        iv.setImageDrawable(d);
        // 有界 ripple：这三个按钮现在装在一只圆角胶囊里，无界水波会溢到胶囊外面
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(ModouUi.dp(this, 999));
        android.graphics.drawable.RippleDrawable rip = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModouUi.alpha(ui.text, 0x14)),
                null, mask);
        iv.setBackground(rip);
        iv.setOnClickListener(c);
        return iv;
    }

    private void openMore(View anchor) {
        // ColorOS 级联菜单：分组用分割线隔开，排序 / 分组是「父项」（右侧细箭头 + 当前值），
        // 点一下就在旁边展开二级卡片 —— 不再是一长条平铺 + 一个半屏弹窗。
        java.util.List<CascadingMenu.Item> items = new java.util.ArrayList<>();
        items.add(CascadingMenu.Item.leaf("阅读历史", () -> switchTab("history")));
        items.add(CascadingMenu.Item.leaf("收藏", () -> switchTab("fav")));
        items.add(CascadingMenu.Item.divider());

        // 排序方式（父项）：最近阅读 / 加入时间 / 书名 / 手动
        final String[] sortKeys = {"recent", "added", "name", "manual"};
        final String[] sortNames = {"最近阅读", "加入时间", "书名", "手动排序"};
        java.util.List<CascadingMenu.Item> sortItems = new java.util.ArrayList<>();
        for (int i = 0; i < sortKeys.length; i++) {
            final String k = sortKeys[i];
            sortItems.add(CascadingMenu.Item.check(sortNames[i], k.equals(sort), () -> {
                sort = k;
                // 两份落库：settings 表读不出来时书架顺序也不至于「变回去」
                store.putSettingStr("shelfSort", k);
                getSharedPreferences("shelf_prefs", MODE_PRIVATE).edit()
                        .putString("shelfSort", k).apply();
                paintPane();
            }));
        }
        items.add(CascadingMenu.Item.parent("排序方式", sortName(), sortItems));

        // 分组（父项）：全部 + 各分组，尾部接新建 / 管理
        java.util.List<CascadingMenu.Item> groupItems = new java.util.ArrayList<>();
        for (String g : groups) {
            groupItems.add(CascadingMenu.Item.check(g, g.equals(group), () -> {
                group = g;
                paintPane();
            }));
        }
        groupItems.add(CascadingMenu.Item.divider());
        groupItems.add(CascadingMenu.Item.leaf("新建分组…", this::newGroup));
        groupItems.add(CascadingMenu.Item.leaf("管理分组…", this::manageGroups));
        items.add(CascadingMenu.Item.parent("分组", groupName(), groupItems));

        items.add(CascadingMenu.Item.divider());
        items.add(CascadingMenu.Item.leaf("导入书籍", () -> ImportActivity.launch(this)));
        items.add(CascadingMenu.Item.leaf("设置", () -> SettingsActivity.launch(this)));
        CascadingMenu.show(this, anchor, items);
    }

    /** 父项副标题：当前排序方式 */
    private String sortName() {
        if ("added".equals(sort)) return "加入时间";
        if ("name".equals(sort)) return "书名";
        if ("manual".equals(sort)) return "手动排序";
        return "最近阅读";
    }

    /** 父项副标题：当前分组（「全部」说成「全部分组」，过长截断） */
    private String groupName() {
        if (ALL.equals(group)) return "全部分组";
        if (group.length() <= 6) return group;
        return group.substring(0, 5) + "…";
    }

    /** 切书架 / 阅读历史 / 收藏：头部文案与返回按钮跟着变 */
    private void switchTab(String t) {
        if (t.equals(tab)) {
            paintPane();
            return;
        }
        tab = t;
        paintHeadTitle();
        paintPane();
    }

    /** 头部标题：书架 →「书架」；历史 →「阅读历史」+ 返回；收藏 →「收藏」+ 返回 */
    private void paintHeadTitle() {
        if (headTitle == null) return;
        String t = "shelf".equals(tab) ? "书架" : "history".equals(tab) ? "阅读历史" : "收藏";
        headTitle.setText(t);
        if (headBack != null) headBack.setVisibility("shelf".equals(tab) ? View.GONE : View.VISIBLE);
        // B5：切 tab 时同步 inline 标题文案并复位滚动态（新 tab 从展开态开始）。
        // 墨读 shelfChrome 恒为 null，这一行完全不生效。
        if (shelfChrome != null) {
            shelfChrome.setInlineTitle(t);
            shelfChrome.reset();
        }
    }

    /* ---------------- 数据 ---------------- */

    private void reload() {
        // 书架不在前台（阅读页/导入页盖在上面）就不读库：以前这里照样每 600ms 跑一轮
        // 「补 lite + 读整张 books 表 + 重建整页书架」，读者那边每翻一章都在跟它抢
        // 同一把数据库锁、同一条主线程 —— 表现就是「下一章一直转圈，死活出不来」。
        if (paused || isFinishing() || isDestroyed()) return;
        new Thread(() -> {
            // 试读暂存（搜索结果点开但还没「加入书架」）不算书架成员：
            // 否则搜一下就往书架里塞一本书，书架很快变成搜索历史
            final List<JSONObject> bs = parseArr(store.booksLite(), true);
            // 清理僵尸占位：上次进程被杀时留下的「导入中」书，此刻并没有任务在跑
            // （LocalImport.CURRENT_ID 为空），它永远等不到解析完成，留在书架上就是一本点不开的空书。
            // 这种占位没有任何正文，直接删掉即可；正在导入的那本保留。
            for (int i = bs.size() - 1; i >= 0; i--) {
                JSONObject b = bs.get(i);
                if (!b.optBoolean("importing", false)) continue;
                String bid = b.optString("id", "");
                if (LocalImport.isImporting(bid)) continue;
                bs.remove(i);
                try {
                    store.delChaptersOf(bid);
                    store.del("books", bid);
                } catch (Exception ignore) {
                    /* 清不掉也无所谓：下次再清 */
                }
            }
            final Map<String, JSONObject> pm = new HashMap<>();
            for (JSONObject p : parseArr(store.all("progress"))) {
                String id = p.optString("bookId", "");
                if (!id.isEmpty()) pm.put(id, p);
            }
            // 排序偏好两个地方各留一份：库里的 settings 是权威，SharedPreferences 是兜底。
            // 只认库的话，settings 没写进去/被迁移漏掉时会悄悄退回默认值，
            // 用户看到的就是「明明选过，重启又变回去」。
            final String sInDb = store.settingStr("shelfSort", null);
            final String so = (sInDb == null || sInDb.isEmpty())
                    ? getSharedPreferences("shelf_prefs", MODE_PRIVATE)
                    .getString("shelfSort", "recent")
                    : sInDb;
            final List<String> gs = new ArrayList<>();
            gs.add(ALL);
            JSONArray arr = store.settingArr("shelfGroups");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "");
                    if (!s.isEmpty() && !gs.contains(s)) gs.add(s);
                }
            }
            if (!gs.contains(DEF_GROUP)) gs.add(DEF_GROUP);
            final long ms = monthMs();
            // 书架结构签名：tab|分组|排序 + 每本书的关键字段。下一帧 onResume 回来时若它没变，
            // 就跳过整页重建（见 runOnUiThread 里的判断），避免封面闪烁。
            final String newSig = shelfSig(bs, gs, so, group, tab, pm);
            runOnUiThread(() -> {
                // 「库是空的」有两种真相：真没书，或数据还在 WebView 的 IndexedDB 里
                // 没迁过来。后者必须说清楚，否则用户第一反应是「书丢了」。
                if (bs.isEmpty() && !store.migrated()) {
                    bookById.clear();
                    books.clear();
                    books.addAll(bs);
                    prog.clear();
                    prog.putAll(pm);
                    paintMigrating();
                    return;
                }
                // 结构没变（从阅读页返回、或从导入页返回、或单纯 onResume）且上一帧已经画过：
                // 不 removeAllViews 重建——已经在屏幕上的封面 ImageView 原样保留，绝不会闪。
                // 只在原地刷新进度文字（读了一章后「未读 N 章」要跟着变），封面一个字节都不动。
                if (newSig.equals(lastSig) && paneBox.getChildCount() > 0) {
                    prog.clear();
                    prog.putAll(pm);
                    refreshProgress();
                    scheduleCoverFill();
                    saveSnapshot();
                    return;
                }
                books.clear();
                books.addAll(bs);
                bookById.clear();
                for (JSONObject b : books) {
                    String id = b.optString("id", "");
                    if (!id.isEmpty()) bookById.put(id, b);
                }
                prog.clear();
                prog.putAll(pm);
                groups.clear();
                groups.addAll(gs);
                sort = so;
                monthMs = ms;
                if (!groups.contains(group)) group = ALL;
                paintHeadTitle();
                paintPane();
                scheduleCoverFill();
                saveSnapshot(); // 真数据到齐后落快照（空书架也落：清空书架后快照要跟着清）
            });
        }).start();
    }

    /**
     * lite 列回填（老库迁移）。
     *
     * 单独一条后台线程逐本补，补完才刷新一次书架 —— 以前是「每一轮 reload 都补一点、
     * 再整表读一遍」，书架几十本书时等于每 600ms 读几十 MB 的 json，读者还怎么看章节。
     * 现在：只补不读，补完（pending 归零）再读一次。
     */
    private void backfillLite() {
        if (backfilling || paused || isFinishing()) return;
        backfilling = true;
        new Thread(() -> {
            int left = 1;
            try {
                while (left > 0 && !paused && !ModouStore.isQuiet() && !isFinishing()) {
                    left = store.ensureBookLite(500);
                    if (left > 0) {
                        // 让出一点时间：别让这条线程把 CPU 和 I/O 全占住
                        try {
                            Thread.sleep(120);
                        } catch (InterruptedException ignore) {
                            break;
                        }
                    }
                }
            } catch (Throwable ignore) {
                /* 补不动就算了：书架用列拼装的老记录照样能显示 */
            }
            final boolean done = left <= 0;
            backfilling = false;
            if (done) runOnUiThread(() -> {
                if (!paused && !isFinishing()) reload();
            });
        }, "modou-lite").start();
    }

    /** 本进程内已经试过在线搜封面的书 id：搜不到的书不反复打扰书源 */
    private static final Set<String> coverTried = new HashSet<>();
    /** 补封面任务是否已经排进队列（每次重载都会走到这里，别排一串） */
    private boolean coverFillPosted;

    /**
     * 排队补封面：延后、且只在用户没在看书时做。
     *
     * 补封面要经书源引擎（一个无头 WebView）—— 首次创建 WebView 是主线程 1~3 秒的活。
     * 以前冷启动书架一画完就去搜封面，用户紧接着点开一本书，正好撞上这次初始化，
     * 表现就是「冷启动后第一本书究极慢」。看书优先，封面晚几秒补没人会注意。
     */
    private void scheduleCoverFill() {
        if (coverFillPosted) return;
        coverFillPosted = true;
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            coverFillPosted = false;
            if (isFinishing() || isDestroyed()) return;
            if (NativeReaderActivity.isOpen()) return;      // 正在看书：别抢主线程/CPU
            if (pager != null && pager.page() != 0) return;  // 不在书架页
            if (!"shelf".equals(tab)) return;
            autoFillCovers();
        }, 6000);
    }

    /**
     * 自动补封面：书架上没有 coverUrl 的书，按书名在线搜一次（单线程逐本来，静默）。
     * 搜到且图片可用的写回书籍记录并刷新书架；搜不到的记入 coverTried 不再重试，
     * 用户仍可在「编辑信息 → 在线搜封面」里手动挑。没有 coverUrl 的书由占位封面兜底
     * （书名全名排版的那张），所以这一步失败了也不影响观感。
     */
    private void autoFillCovers() {
        List<JSONObject> need = new ArrayList<>();
        for (JSONObject b : books) {
            String id = b.optString("id", "");
            if (id.isEmpty() || coverTried.contains(id)) continue;
            // 本地导入的书没有书源：按书名在线搜到的多半是另一本书的封面，
            // 而且三十多本本地书会变成三十多次网络搜索，App 一直忙在后台
            if (id.startsWith(LocalImport.LOCAL)) {
                coverTried.add(id);
                continue;
            }
            if (!b.optString("coverUrl", "").trim().isEmpty()) continue;
            coverTried.add(id);
            need.add(b);
            if (need.size() >= 8) break; // 一次最多补 8 本，别把书源和后台线程占满
        }
        if (need.isEmpty() || SourceService.get() == null) return;
        new Thread(() -> {
            SourceService svc = SourceService.get();
            int fixed = 0;
            for (JSONObject b : need) {
                if (svc == null) break;
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final String[] hit = {""};
                try {
                    svc.search(b.optString("name", ""), null, 1, res -> {
                        try {
                            JSONArray gs = res == null ? null : res.optJSONArray("groups");
                            if (gs != null) {
                                for (int i = 0; i < gs.length() && hit[0].isEmpty(); i++) {
                                    JSONObject grp = gs.optJSONObject(i);
                                    if (grp == null) continue;
                                    JSONArray hs = grp.optJSONArray("hits");
                                    if (hs == null) continue;
                                    for (int j = 0; j < hs.length(); j++) {
                                        JSONObject hh = hs.optJSONObject(j);
                                        JSONObject bk = hh == null ? null : hh.optJSONObject("book");
                                        String u = bk == null ? "" : bk.optString("coverUrl", "").trim();
                                        if (u.isEmpty()) continue;
                                        // 先验图：搜到的 URL 打不开就换下一个候选
                                        if (CoverLoader.fetch(u) != null) {
                                            hit[0] = u;
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignore) {
                            /* 脏数据：当没搜到 */
                        }
                        latch.countDown();
                    });
                    latch.await(12, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    break; // 服务或线程异常：这轮不再继续
                }
                if (!hit[0].isEmpty()) {
                    try {
                        b.put("coverUrl", hit[0]);
                        saveBook(b);
                        fixed++;
                    } catch (Exception ignore) {
                        /* 存失败就当没补上 */
                    }
                }
            }
            final int n = fixed;
            if (n > 0) runOnUiThread(() -> {
                ModouUi.toast(this, "已为 " + n + " 本书自动补封面");
                reload();
            });
        }, "modou-coverfill").start();
    }

    /** 本月阅读时长：settings.readStatsByMonth = { 'YYYY-MM': ms } */
    private long monthMs() {
        try {
            String raw = store.settingRaw("readStatsByMonth");
            if (raw == null) return 0;
            JSONObject o = new JSONObject(raw);
            Calendar c = Calendar.getInstance();
            String key = c.get(Calendar.YEAR) + "-"
                    + String.format(Locale.US, "%02d", c.get(Calendar.MONTH) + 1);
            return o.optLong(key, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<JSONObject> parseArr(String s) {
        return parseArr(s, false);
    }

    /** @param skipPreview 跳过 preview 标记的书（试读暂存，还没正式入书架） */
    private static List<JSONObject> parseArr(String s, boolean skipPreview) {
        List<JSONObject> out = new ArrayList<>();
        if (s == null) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (skipPreview && o.optBoolean("preview", false)) continue;
                out.add(o);
            }
        } catch (Exception e) {
            /* 空表/脏数据：当空处理 */
        }
        return out;
    }

    /* ---------------- 渲染 ---------------- */

    private void paintPane() {
        // 重建前先记下滚动位置（见字段注释），重建后同一视图再放回去
        captureScrollAnchor();
        final String key = paneKey();
        // 同 tab/分组/排序的重绘要复位置；转屏 recreate 后即使 key 比对不成也要求恢复一次
        final boolean keepScroll = pendingRestoreScroll
                || (lastScroller != null && key.equals(lastScrollerKey));
        pendingRestoreScroll = false;
        lastScroller = null;

        paneBox.removeAllViews();
        cardProg.clear(); // 旧书卡被销毁，进度文字引用随之作废，等 cardView 重建时再登记
        if ("history".equals(tab)) {
            paneBox.addView(buildHistory());
        } else if ("fav".equals(tab)) {
            List<JSONObject> favs = new ArrayList<>();
            for (JSONObject b : books) if (b.optBoolean("fav", false)) favs.add(b);
            paneBox.addView(buildGrid(favs, "还没有收藏"), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            paneBox.addView(buildShelfPane());
        }
        // 0.9.166：给滚动容器加顶部留白，内容从大标题块下方开始、滚动时连续上移
        // 进入留白区（三个容器都是 clipToPadding=false），从固定顶栏下穿过。
        if (shelfInsetPx > 0 && !applyShelfInset(paneBox)) {
            View first = paneBox.getChildAt(0);   // 空态：没有滚动容器，退化给根 View
            if (first != null) first.setPadding(first.getPaddingLeft(),
                    first.getPaddingTop() + shelfInsetPx,
                    first.getPaddingRight(), first.getPaddingBottom());
        }
        if (shelfSub != null) {
            shelfSub.setText("history".equals(tab)
                    ? "最近读过 " + prog.size() + " 本" : countText(filtered()));
        }
        if (keepScroll && lastScroller != null) restoreScroll();
        // B5（0.9.165）：把本次新建的滚动容器接给 ScrollChrome。三个 tab 的容器
        // 各不相同（书架 RecyclerView / 历史 ListView / 收藏 GridView / 空态无容器），
        // 用递归 findScroller 一处收口，不逐 tab 手写、不写语言判断；墨读 shelfChrome
        // 恒为 null，这一行完全不生效。attach 内部 apply(0) 同时完成切 tab 滚动态复位。
        if (shelfChrome != null) shelfChrome.attach(findScroller(paneBox));
        // 记下这次重绘出的书架结构：下次 onResume 回来若一致，就不必重建（防封面闪烁）
        lastSig = shelfSig(books, groups, sort, group, tab, prog);
    }

    /**
     * B5（0.9.165）：从内容树里找出当前 tab 的滚动容器。
     * 书架 → RecyclerView（分组网格），阅读历史 → ListView，收藏 → GridView，
     * 空态 → 纯 TextView / LinearLayout（返回 null，attach 只复位头部、不注册监听）。
     * AbsListView 与 RecyclerView 分开判，避免遗漏；只看直接可见的第一层滚动容器。
     */
    private static View findScroller(View v) {
        if (v instanceof androidx.recyclerview.widget.RecyclerView
                || v instanceof AbsListView || v instanceof ScrollView) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findScroller(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * 0.9.166：给当前 tab 的滚动容器加顶部留白。三个容器（RecyclerView / ListView /
     * GridView）都是 {@code clipToPadding=false}，内容能画进留白区；返回 false 表示
     * 本页是空态、没有滚动容器，由 paintPane 退化处理。
     */
    private boolean applyShelfInset(View v) {
        if (v instanceof androidx.recyclerview.widget.RecyclerView
                || v instanceof AbsListView || v instanceof ScrollView) {
            v.setPadding(v.getPaddingLeft(), shelfInsetPx,
                    v.getPaddingRight(), v.getPaddingBottom());
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (applyShelfInset(g.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /** 书架结构签名：tab|分组|排序 + 每本书的关键字段。任一变化都意味着要整页重绘 */
    private String shelfSig(List<JSONObject> bs, List<String> gs, String so, String grp, String tb,
                            Map<String, JSONObject> pm) {
        StringBuilder sb = new StringBuilder();
        sb.append(tb).append('|').append(grp).append('|').append(so).append('|');
        for (String g : gs) sb.append(g).append(',');
        sb.append('|');
        // 「最近阅读」的顺序取决于阅读时间：不把时间算进签名，读完一本回来签名没变，
        // 就会走「只刷新进度文字」的快路径 —— 进度变了、顺序却没动（用户看到的就是这个）。
        boolean byTime = "recent".equals(so);
        for (JSONObject b : bs) {
            sb.append(b.optString("id", "")).append(',').append(b.optString("coverUrl", "")).append(',')
                    .append(b.optString("name", "")).append(',').append(b.optString("sourceUrl", "")).append(',')
                    .append(b.optInt("chapterCount", 0)).append(',')
                    .append(b.optBoolean("fav", false));
            if (byTime) sb.append(',').append(sortKey(b, pm));
            sb.append(';');
        }
        return sb.toString();
    }

    /** 结构没变时只原地刷新每本书卡的进度文字，绝不重建封面 ImageView */
    private void refreshProgress() {
        for (Map.Entry<String, TextView> e : cardProg.entrySet()) {
            JSONObject b = bookById.get(e.getKey());
            TextView v = e.getValue();
            if (b != null && v != null) v.setText(progressText(b));
        }
    }

    /** 单本书卡的进度文案（书卡底部「导入中 / 未读过 / 读到第 N 章 / 未读 N 章 / 已读完」） */
    private String progressText(JSONObject b) {
        if (b.optBoolean("importing", false)) return "导入中…";
        JSONObject p = prog.get(b.optString("id", ""));
        int total = b.optInt("chapterCount", 0);
        if (p == null) return "未读过";
        if (total <= 0) return "读到第 " + (p.optInt("index", 0) + 1) + " 章";
        return p.optInt("index", 0) >= total - 1
                ? "已读完" : "未读 " + Math.max(0, total - p.optInt("index", 0) - 1) + " 章";
    }

    /** 当前列表的身份：tab / 分组 / 排序任一变化都算换了一个列表 */
    private String paneKey() {
        return tab + "|" + group + "|" + sort;
    }

    /**
     * 记下当前滚动位置。RV 同时记「下标 + 偏移」与「锚点书 id」：
     * 同列数重绘用下标（一步到位），转屏列数变了用 id（下标含义已变）。
     */
    private void captureScrollAnchor() {
        if (lastScroller instanceof AbsListView) {
            AbsListView lv = (AbsListView) lastScroller;
            lastFirstPos = lv.getFirstVisiblePosition();
            View first = lv.getChildCount() > 0 ? lv.getChildAt(0) : null;
            lastFirstTop = first == null ? 0 : first.getTop();
            lastAnchorId = "";
        } else if (lastScroller instanceof androidx.recyclerview.widget.RecyclerView) {
            androidx.recyclerview.widget.RecyclerView rv =
                    (androidx.recyclerview.widget.RecyclerView) lastScroller;
            androidx.recyclerview.widget.RecyclerView.LayoutManager lm = rv.getLayoutManager();
            if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                lastFirstPos = ((androidx.recyclerview.widget.LinearLayoutManager) lm)
                        .findFirstVisibleItemPosition();
                View first = rv.getChildCount() > 0 ? rv.getChildAt(0) : null;
                lastFirstTop = first == null ? 0 : first.getTop();
                lastAnchorId = anchorIdAt(rv, lastFirstPos);
                lastAnchorTop = lastFirstTop;
            }
        } else if (lastScroller != null) {
            lastScrollY = lastScroller.getScrollY();
        }
    }

    /** 某个位置对应的书 id（落在组标题上就取它后面那本，保证锚点永远是书而不是标题） */
    private String anchorIdAt(androidx.recyclerview.widget.RecyclerView rv, int pos) {
        androidx.recyclerview.widget.RecyclerView.Adapter<?> ad = rv.getAdapter();
        if (!(ad instanceof ShelfAdapter) || pos < 0) return "";
        List<ShelfRow> data = ((ShelfAdapter) ad).data;
        for (int i = pos; i < data.size(); i++) {
            ShelfRow r = data.get(i);
            if (r.kind == ShelfRow.BOOK && r.book != null) return r.book.optString("id", "");
        }
        return "";
    }

    /** 按书 id 反查行位置（转屏后列数变了，只能按书找回） */
    private int positionOfBook(androidx.recyclerview.widget.RecyclerView rv, String id) {
        androidx.recyclerview.widget.RecyclerView.Adapter<?> ad = rv.getAdapter();
        if (!(ad instanceof ShelfAdapter) || id == null || id.isEmpty()) return -1;
        List<ShelfRow> data = ((ShelfAdapter) ad).data;
        for (int i = 0; i < data.size(); i++) {
            ShelfRow r = data.get(i);
            if (r.kind == ShelfRow.BOOK && r.book != null
                    && id.equals(r.book.optString("id", ""))) return i;
        }
        return -1;
    }

    /** 把重建前记下的位置放回去（首帧布局没量完，补一次，避免被 clamp 到 0） */
    /** 把重建前记下的位置放回去（首帧布局没量完，补一次，避免被 clamp 到 0） */
    private void restoreScroll() {
        final View sc = lastScroller;
        if (sc instanceof AbsListView) {
            if (lastFirstPos < 0) return;
            final AbsListView lv = (AbsListView) sc;
            final int pos = lastFirstPos, top = lastFirstTop;
            lv.post(() -> lv.setSelectionFromTop(pos, top));
            return;
        }
        if (sc instanceof androidx.recyclerview.widget.RecyclerView) {
            final androidx.recyclerview.widget.RecyclerView rv =
                    (androidx.recyclerview.widget.RecyclerView) sc;
            final String anchor = lastAnchorId;
            rv.post(() -> {
                int target = lastFirstPos, off = lastFirstTop;
                if (!anchor.isEmpty()) {
                    int p = positionOfBook(rv, anchor);
                    if (p > 0) {
                        target = p;
                        off = lastAnchorTop;
                    }
                }
                if (target <= 0) return;
                // scrollToPositionWithOffset 在 LayoutManager 上：顶部对齐，而不是「滚到可见就行」
                androidx.recyclerview.widget.RecyclerView.LayoutManager lmm = rv.getLayoutManager();
                if (lmm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                    ((androidx.recyclerview.widget.LinearLayoutManager) lmm)
                            .scrollToPositionWithOffset(target, off);
                } else {
                    rv.scrollToPosition(target);
                }
                // 封面是异步解码的，首帧行高还没定：布局稳定后再校一次，否则停在半行处
                final int t2 = target, o2 = off;
                rv.postDelayed(() -> {
                    androidx.recyclerview.widget.RecyclerView.LayoutManager lm2 = rv.getLayoutManager();
                    if (lm2 instanceof androidx.recyclerview.widget.LinearLayoutManager) {
                        ((androidx.recyclerview.widget.LinearLayoutManager) lm2)
                                .scrollToPositionWithOffset(t2, o2);
                    }
                }, 90);
            });
            return;
        }
        if (lastScrollY <= 0) return;
        final int y = lastScrollY;
        sc.post(() -> {
            sc.scrollTo(0, y);
            // 书卡封面是异步解码的，第一次布局可能还没有最终高度 → 再补一次
            sc.postDelayed(() -> sc.scrollTo(0, y), 90);
        });
    }

    /** 数据还在 IndexedDB 里没迁进 SQLite：催一次迁移，并如实告诉用户在等什么 */
    private void paintMigrating() {
        // 迁移必须借 JS 引擎（老数据在 IndexedDB 里）：这时才按需把 WebView 建出来。
        // 平时的冷启动不建 —— 那是首次进书卡顿的主因。
        ModouBridge br = MainActivity.ensureBridge();
        if (br != null) br.evalJs("window.__modouMigrateNow && window.__modouMigrateNow()");

        paneBox.removeAllViews();
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setPadding(ModouUi.dp(this, 24), ModouUi.dp(this, 60), ModouUi.dp(this, 24), 0);

        TextView t = ModouUi.tv(this, "正在搬入书库", 16, ui.text);
        t.setGravity(Gravity.CENTER);
        p.addView(t);
        TextView s = ModouUi.tv(this,
                "首次使用时，历史数据要从旧存储搬到应用数据库（在后台进行）。"
                        + "稍等一会回到这里点「刷新」即可，不会影响任何一本书。", 13, ui.sub);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(ModouUi.dp(this, 3), 1.4f);
        s.setPadding(0, ModouUi.dp(this, 12), 0, ModouUi.dp(this, 16));
        p.addView(s);

        TextView retry = ModouUi.tv(this, "刷新", 15, 0xFFFFFFFF);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(ModouUi.dp(this, 28), ModouUi.dp(this, 10), ModouUi.dp(this, 28), ModouUi.dp(this, 10));
        retry.setBackground(ModouUi.squircle(this, ui.accent, 20));
        retry.setOnClickListener((x) -> reload());
        p.addView(retry);
        paneBox.addView(p);
    }

    private View buildShelfPane() {
        // 0.9.166：「共 X 本」统计行上移为内容区大标题块的副标题（shelfSub），
        // 这里只返回满屏网格，滚动容器才能从 y=0 起、靠顶部留白让开大标题。
        List<JSONObject> inGroup = filtered();
        return buildGrouped(inGroup, books.isEmpty()
                ? "书架是空的\n导入手机里的 TXT / EPUB，或联网搜书加入。"
                : "这个分组还是空的");
    }

    private String countText(List<JSONObject> list) {
        if ("fav".equals(tab)) return "收藏 " + list.size() + " 本";
        if (ALL.equals(group)) return "共 " + list.size() + " 本";
        return group + " · " + list.size() + " / " + books.size() + " 本";
    }

    /** 分组过滤 + 排序（语义对齐 web/js/ui/shelf.js） */
    private List<JSONObject> filtered() {
        List<JSONObject> list = new ArrayList<>();
        for (JSONObject b : books) {
            if (ALL.equals(group) || group.equals(b.optString("group", DEF_GROUP))) list.add(b);
        }
        if ("name".equals(sort)) {
            final Collator col = Collator.getInstance(Locale.CHINA);
            list.sort((x, y) -> col.compare(x.optString("name", ""), y.optString("name", "")));
        } else if ("added".equals(sort)) {
            list.sort((x, y) -> Long.compare(y.optLong("addedAt", 0), x.optLong("addedAt", 0)));
        } else if ("manual".equals(sort)) {
            // 手动排序：按 shelfOrder 里的 id 顺序；没登记过的书（新加的）按加入时间垫底
            Map<String, Integer> ix = new HashMap<>();
            List<String> ord = shelfOrder();
            for (int i = 0; i < ord.size(); i++) ix.put(ord.get(i), i);
            list.sort((x, y) -> {
                Integer a = ix.get(x.optString("id", ""));
                Integer b2 = ix.get(y.optString("id", ""));
                int av = a == null ? Integer.MAX_VALUE : a;
                int bv = b2 == null ? Integer.MAX_VALUE : b2;
                if (av != bv) return Integer.compare(av, bv);
                return Long.compare(y.optLong("addedAt", 0), x.optLong("addedAt", 0));
            });
        } else {
            // 读过的按进度更新时间倒序，都没读过就按添加时间倒序
            list.sort((x, y) -> {
                long a = sortKey(x);
                long b2 = sortKey(y);
                if (a != b2) return Long.compare(b2, a);
                return Long.compare(y.optLong("addedAt", 0), x.optLong("addedAt", 0));
            });
        }
        return list;
    }

    /**
     * 「最近阅读」用的时间戳。
     *
     * 三个来源取最大：①进度记录的 updatedAt（每次翻页/退出都会写）；
     * ②书籍记录自身的 updatedAt（详情页、加书架、网页版阅读都会写）；
     * ③都没有就退到加入时间。旧版本进度里没写 updatedAt，只认第①项的话
     * 已读过的书会全部落到「加入时间」，排出来和最近阅读完全无关。
     */
    private long sortKey(JSONObject b) {
        return sortKey(b, prog);
    }

    /** 同上，但用外部传入的进度表（reload 在后台线程算签名时，不能再读 UI 线程上的 prog） */
    private long sortKey(JSONObject b, Map<String, JSONObject> pm) {
        String id = b.optString("id", "");
        long t = 0;
        JSONObject p = pm.get(id);
        if (p != null) t = p.optLong("updatedAt", 0);
        long bt = b.optLong("updatedAt", 0);
        if (bt > t) t = bt;
        if (t > 0) return t;
        return b.optLong("addedAt", 0);
    }

    /* ---------------- 排序方式 ---------------- */

    private List<String> shelfOrder() {
        List<String> out = new ArrayList<>();
        JSONArray a = store.settingArr("shelfOrder");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    private void saveShelfOrder(List<String> ids) {
        JSONArray a = new JSONArray();
        for (String id : ids) a.put(id);
        store.putSetting("shelfOrder", a.toString());
    }

    /** 手动排序菜单（长按书卡）：置顶 / 上移 / 下移，写回 shelfOrder 后整页重排 */
    private void showManualSortMenu(JSONObject b) {
        final String id = b.optString("id", "");
        List<JSONObject> shown = filtered();
        int pos = -1;
        for (int i = 0; i < shown.size(); i++) {
            if (id.equals(shown.get(i).optString("id", ""))) {
                pos = i;
                break;
            }
        }
        if (pos < 0) return;
        final int p = pos;
        final int n = shown.size();
        List<String> acts = new ArrayList<>();
        if (p > 0) acts.add("置顶");
        if (p > 0) acts.add("上移一位");
        if (p < n - 1) acts.add("下移一位");
        acts.add("书籍详情");
        ModouUi.sheetList(this, b.optString("name", ""), acts.toArray(new String[0]), w -> {
            String act = acts.get(w);
            if (act.equals("书籍详情")) {
                openBook(b);
                return;
            }
            // 以当前显示顺序为基准调整，再整体落库 —— 其他排序来的新书自动带上序
            List<String> cur = new ArrayList<>();
            for (JSONObject o : shown) cur.add(o.optString("id", ""));
            cur.remove(id);
            switch (act) {
                case "置顶":
                    cur.add(0, id);
                    break;
                case "上移一位":
                    cur.add(Math.max(0, p - 1), id);
                    break;
                default:
                    cur.add(Math.min(cur.size(), p + 1), id);
                    break;
            }
            saveShelfOrder(cur);
            reload();
        });
    }

    private View buildHistory() {
        List<JSONObject> rows = new ArrayList<>();
        for (JSONObject b : books) if (prog.containsKey(b.optString("id", ""))) rows.add(b);
        rows.sort((x, y) -> Long.compare(sortKey(y), sortKey(x)));

        // 0.9.166：月统计横幅原先是列表外的固定一行，会让内容从「大标题 + 横幅」
        // 之后才开始，收起后顶部留空洞。改成列表第 0 项，随内容一起滚动。
        if (rows.isEmpty()) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(buildMonthStat());
            box.addView(ModouUi.empty(this, "还没有阅读记录"), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return box;   // 空态无滚动容器，由 applyShelfInset 的兜底加顶部留白
        }

        ListView lv = new ListView(this);
        lv.setBackgroundColor(Color.TRANSPARENT);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setVerticalScrollBarEnabled(false);
        lv.setClipToPadding(false);
        lv.setPadding(0, 0, 0, dockFoot());
        lv.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return rows.size() + 1;   // 第 0 项 = 月统计横幅
            }

            @Override
            public Object getItem(int i) {
                return i == 0 ? null : rows.get(i - 1);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                if (i == 0) {
                    if (cv != null && Boolean.TRUE.equals(cv.getTag())) return cv;
                    View banner = buildMonthStat();
                    banner.setTag(Boolean.TRUE);
                    return banner;
                }
                HistHolder h = (cv != null && cv.getTag() instanceof HistHolder)
                        ? (HistHolder) cv.getTag() : newHistRow();
                JSONObject b = rows.get(i - 1);
                JSONObject p = prog.get(b.optString("id", ""));
                CoverLoader.load(h.cover, b.optString("coverUrl", ""), b.optString("name", ""),
                        b.optString("author", ""), 0f);
                h.name.setText(b.optString("name", ""));
                int idx = p == null ? 0 : p.optInt("index", 0);
                h.sub.setText("读到第 " + (idx + 1) + " 章");
                h.time.setText(relTime(p == null ? 0 : p.optLong("updatedAt", 0)));
                return h.outer;
            }
        });
        // 历史列表与书卡同语义：单击直接续读，长按进详情；第 0 项是统计横幅，不响应
        lv.setOnItemClickListener((a, v, i, id) -> {
            if (i == 0) return;
            JSONObject b = rows.get(i - 1);
            JSONObject p = prog.get(b.optString("id", ""));
            ReaderLaunch.open(ShelfActivity.this, ModouBridge.shared(), b, p == null ? 0 : p.optInt("index", 0));
        });
        lv.setOnItemLongClickListener((a, v, i, id) -> {
            if (i == 0) return false;
            openBook(rows.get(i - 1));
            return true;
        });
        lastScroller = lv;
        lastScrollerKey = paneKey();
        return lv;
    }

    /** 本月阅读统计条：原先是书架顶部的一条横幅，占书架的书区，现在只出现在阅读历史页 */
    private View buildMonthStat() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackground(ModouUi.squircle(this, ui.card, 14));
        banner.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 9),
                ModouUi.dp(this, 12), ModouUi.dp(this, 9));

        TextView bico = ModouUi.tv(this, "时", 13, ui.accent);
        bico.setGravity(Gravity.CENTER);
        bico.setBackground(ModouUi.squircle(this, ModouUi.alpha(ui.accent, 0x22), 11));
        int isz = ModouUi.dp(this, 34);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
        ilp.rightMargin = ModouUi.dp(this, 10);
        banner.addView(bico, ilp);

        TextView btxt = ModouUi.tv(this, "本月阅读 " + fmtDur(monthMs), 13.5f, ui.text);
        btxt.setTypeface(btxt.getTypeface(), Typeface.BOLD);
        banner.addView(btxt);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = ModouUi.dp(this, 12);
        banner.setLayoutParams(blp);
        return banner;
    }

    /** 三列封面网格（书架 / 收藏共用） */
    private View buildGrid(List<JSONObject> list, String emptyText) {
        if (list.isEmpty()) return ModouUi.empty(this, emptyText);

        GridView gv = new GridView(this);
        final int cols = gridCols();
        gv.setNumColumns(cols);
        gv.setHorizontalSpacing(ModouUi.dp(this, 14));
        gv.setVerticalSpacing(ModouUi.dp(this, 26));
        gv.setBackgroundColor(Color.TRANSPARENT);
        gv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        // 浮岛是叠在内容上的：底部留出它的位置，最后一排书能滚到玻璃上方
        gv.setClipToPadding(false);
        gv.setPadding(0, 0, 0, dockFoot());

        final int coverH = coverHFor(cols);

        final List<JSONObject> data = new ArrayList<>(list);
        gv.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return data.size();
            }

            @Override
            public Object getItem(int i) {
                return data.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                // 复用：以前忽略 cv 整套 new，滚动时每张卡都要重新解码封面
                View v = (cv != null && cv.getTag() instanceof CardHolder) ? cv : newCard(coverH);
                bindCard(v, data.get(i));
                return v;
            }
        });
        gv.setOnItemClickListener((a, v, i, id) -> {
            JSONObject b = data.get(i);
            JSONObject p = prog.get(b.optString("id", ""));
            ReaderLaunch.open(ShelfActivity.this, ModouBridge.shared(), b, p == null ? 0 : p.optInt("index", 0));
        });
        gv.setOnItemLongClickListener((a, v, i, id) -> {
            if ("manual".equals(sort)) showManualSortMenu(data.get(i));
            else openBook(data.get(i));
            return true;
        });
        lastScroller = gv;
        lastScrollerKey = paneKey();
        return gv;
    }

    /** 书架行模型：分组标题 / 一本书（供 RecyclerView 复用 + DiffUtil 比较） */
    private static final class ShelfRow {
        static final int HEADER = 0, BOOK = 1;
        final int kind;
        final String group;
        final JSONObject book;
        final String label;      // 标题行文本（HEADER 才有）

        ShelfRow(int kind, String group, JSONObject book, String label) {
            this.kind = kind;
            this.group = group;
            this.book = book;
            this.label = label;
        }

        static ShelfRow header(String g, String label) { return new ShelfRow(HEADER, g, null, label); }

        static ShelfRow book(String g, JSONObject b) { return new ShelfRow(BOOK, g, b, null); }
    }

    /** 书架适配器：书卡用 newCard/bindCard 复用，头部用 sectionHeader */
    private final class ShelfAdapter
            extends androidx.recyclerview.widget.RecyclerView.Adapter
            <androidx.recyclerview.widget.RecyclerView.ViewHolder> {
        final List<ShelfRow> data = new ArrayList<>();
        int coverH;

        void submit(List<ShelfRow> next) {
            if (next == null) return;   // 兜底：任何调用链给空都不许炸适配器
            final List<ShelfRow> old = new ArrayList<>(data);
            androidx.recyclerview.widget.DiffUtil.DiffResult diff =
                    androidx.recyclerview.widget.DiffUtil.calculateDiff(
                            new androidx.recyclerview.widget.DiffUtil.Callback() {
                                @Override public int getOldListSize() { return old.size(); }
                                @Override public int getNewListSize() { return next.size(); }
                                @Override public boolean areItemsTheSame(int a, int b) {
                                    ShelfRow x = old.get(a), y = next.get(b);
                                    if (x.kind != y.kind) return false;
                                    if (x.kind == ShelfRow.HEADER) return eq(x.group, y.group);
                                    return eq(x.book.optString("id", ""), y.book.optString("id", ""));
                                }
                                @Override public boolean areContentsTheSame(int a, int b) {
                                    ShelfRow x = old.get(a), y = next.get(b);
                                    if (x.kind == ShelfRow.HEADER) return eq(x.label, y.label);
                                    return eq(x.book.optString("name", ""), y.book.optString("name", ""))
                                            && eq(progressText(x.book), progressText(y.book));
                                }
                            }, false);
            data.clear();
            data.addAll(next);
            diff.dispatchUpdatesTo(this);
        }

        @Override public int getItemViewType(int p) { return data.get(p).kind; }
        @Override public int getItemCount() { return data.size(); }

        @Override
        public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(
                ViewGroup parent, int type) {
            if (type == ShelfRow.HEADER) {
                return new androidx.recyclerview.widget.RecyclerView.ViewHolder(sectionHeader("")) {};
            }
            return new androidx.recyclerview.widget.RecyclerView.ViewHolder(newCard(coverH)) {};
        }

        @Override
        public void onBindViewHolder(
                androidx.recyclerview.widget.RecyclerView.ViewHolder h, int p) {
            ShelfRow r = data.get(p);
            if (r.kind == ShelfRow.HEADER) {
                ((TextView) h.itemView).setText(r.label);
            } else {
                bindCard(h.itemView, r.book);
            }
        }

        private boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    /**
     * 分组标题吸顶（替代 SectionScroll 的 pinned 头部）：当前组贴在顶部，
     * 下一组标题顶上来时把它一起推走。
     *
     * 两个细节决定了它是否「像原生」：
     * 1. 底色：吸顶头是压在封面上的，只画文字会透出封面 →
     *    先铺一层页面底色，下缘再叠 10dp 渐隐，与背后的毛玻璃底衬无缝过渡，不出现硬边；
     * 2. 顶走：偏移量每帧按「下一个标题的实际 top」重算，手一松就停在那里，
     *    是跟着手指的位移，不是补间动画，所以不会有「甩过头再弹回」的感觉。
     */
    private final class StickyGroupHeader
            extends androidx.recyclerview.widget.RecyclerView.ItemDecoration {
        private final TextView stub = sectionHeader("");
        private final List<ShelfRow> rowsRef;
        private final android.graphics.Paint bgPaint = new android.graphics.Paint();
        private final android.graphics.Paint fadePaint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private int hh = 0, lastW = 0, fade = 0;

        StickyGroupHeader(List<ShelfRow> rows) {
            rowsRef = rows;
            bgPaint.setColor(ui.bg);
            // 0.9.49 修复启动闪退（崩溃日志实锤的真凶）：stub 从不 attach 到任何
            // 父容器，LayoutParams 一直是 null；onDrawOver 里先手动 measure 再
            // setText，TextView.checkForRelayout() 读 mLayoutParams.width 直接 NPE，
            // 首帧必崩。给它配一组固定 LayoutParams 就不会再走空引用。
            // 0.9.44 引入依赖裁剪是另一处隐患（已另修），但不是这次的元凶。
            stub.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override
        public void onDrawOver(android.graphics.Canvas c,
                               androidx.recyclerview.widget.RecyclerView parent,
                               androidx.recyclerview.widget.RecyclerView.State state) {
            if (rowsRef.isEmpty() || parent.getChildCount() == 0) return;
            int w = parent.getWidth();
            // 转屏 / 分屏后宽度变了要重测，否则吸顶头还是按旧宽度画的
            if (hh == 0 || w != lastW) {
                lastW = w;
                stub.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                hh = stub.getMeasuredHeight();
                stub.layout(0, 0, w, hh);
                fade = ModouUi.dp(ShelfActivity.this, 10);
                fadePaint.setShader(new android.graphics.LinearGradient(
                        0, hh, 0, hh + fade, ui.bg, ModouUi.alpha(ui.bg, 0),
                        android.graphics.Shader.TileMode.CLAMP));
            }
            View first = parent.getChildAt(0);
            int firstPos = parent.getChildAdapterPosition(first);
            if (firstPos < 0 || firstPos >= rowsRef.size()) return;
            ShelfRow cur = rowsRef.get(firstPos);
            // 0.9.49：文本变了才 setText —— setText 会触发 checkForRelayout()，
            // 离屏 stub 每帧都重排既浪费也有踩空引用的风险
            String label;
            if (cur.kind == ShelfRow.HEADER && cur.label != null) {
                label = cur.label;
            } else if (cur.kind == ShelfRow.BOOK) {
                label = cur.group + "  ·  " + groupCount(cur.group) + " 本";
            } else {
                label = null;
            }
            if (label != null && !label.contentEquals(stub.getText())) {
                stub.setText(label);
            }
            int y = 0;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View nv = parent.getChildAt(i);
                int np = parent.getChildAdapterPosition(nv);
                if (np <= firstPos || np >= rowsRef.size()) continue;
                if (rowsRef.get(np).kind != ShelfRow.HEADER) continue;
                if (nv.getTop() < hh) y = Math.max(-hh, nv.getTop() - hh);
                break;
            }
            if (y <= -hh) return;   // 已被下一组完全顶出视野
            c.save();
            // 0.9.166：滚动容器改成满屏（真·Large Title），吸顶基准要下移到固定
            // 工具条之下，否则分组头会画在 y=0 被顶栏材质盖住。墨读/横屏为 0，原样。
            c.translate(0, stickyTopPx + y);
            c.drawRect(0, 0, w, hh, bgPaint);
            c.drawRect(0, hh, w, hh + fade, fadePaint);
            stub.draw(c);
            c.restore();
        }
    }

    private int groupCount(String g) {
        int n = 0;
        for (JSONObject b : books) if (g.equals(b.optString("group", DEF_GROUP))) n++;
        return n;
    }

    /**
     * 按分组渲染书架：0.9.44 从「SectionScroll + 手写行（每本书一张卡全部 new）」
     * 换成 RecyclerView + GridLayoutManager（标题整行跨列）+ DiffUtil 局部刷新，
     * 分组吸顶改用 ItemDecoration 绘制。
     */
    private View buildGrouped(List<JSONObject> inGroup, String emptyText) {
        if (inGroup.isEmpty()) return ModouUi.empty(this, emptyText);

        final int cols = gridCols();
        final List<ShelfRow> rowsData = new ArrayList<>();
        final List<String> grps = orderedGroups(inGroup);
        // 单分组时不再渲染列表分组头：大标题副标题已承载计数，避免「24本」出现两处。
        final boolean singleGroup = grps.size() <= 1;
        for (String g : grps) {
            List<JSONObject> sec = new ArrayList<>();
            for (JSONObject b : inGroup) {
                if (g.equals(b.optString("group", DEF_GROUP))) sec.add(b);
            }
            if (sec.isEmpty()) continue;
            if (!singleGroup) rowsData.add(ShelfRow.header(g, g + "  ·  " + sec.size() + " 本"));
            for (JSONObject b : sec) rowsData.add(ShelfRow.book(g, b));
        }

        androidx.recyclerview.widget.RecyclerView rv =
                new androidx.recyclerview.widget.RecyclerView(this);
        androidx.recyclerview.widget.GridLayoutManager lm =
                new androidx.recyclerview.widget.GridLayoutManager(this, cols);
        final int fCols = cols;
        lm.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int p) {
                return p < rowsData.size() && rowsData.get(p).kind == ShelfRow.HEADER ? fCols : 1;
            }
        });
        rv.setLayoutManager(lm);
        androidx.recyclerview.widget.DefaultItemAnimator anim =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        anim.setSupportsChangeAnimations(false);
        rv.setItemAnimator(anim);
        rv.setClipToPadding(false);
        rv.setPadding(0, 0, 0, dockFoot());
        rv.setBackgroundColor(Color.TRANSPARENT);
        final int gap = ModouUi.dp(this, 12);
        rv.addItemDecoration(new androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(android.graphics.Rect out, View v,
                                                 androidx.recyclerview.widget.RecyclerView parent,
                                                 androidx.recyclerview.widget.RecyclerView.State st) {
                int p = parent.getChildAdapterPosition(v);
                if (p < 0 || p >= rowsData.size()) return;
                if (rowsData.get(p).kind == ShelfRow.HEADER) {
                    out.bottom = ModouUi.dp(ShelfActivity.this, 8);
                    return;
                }
                int col = ((androidx.recyclerview.widget.GridLayoutManager.LayoutParams)
                        v.getLayoutParams()).getSpanIndex();
                out.left = col * gap / fCols;
                out.right = gap - (col + 1) * gap / fCols;
                out.bottom = ModouUi.dp(ShelfActivity.this, 22);
            }
        });
        ShelfAdapter ad = new ShelfAdapter();
        ad.coverH = coverHFor(cols);
        rv.setAdapter(ad);
        ad.submit(rowsData);
        // 单分组不挂吸顶头：列表里已无分组头行，且大标题副标题已显示计数，避免重复。
        if (!singleGroup) rv.addItemDecoration(new StickyGroupHeader(ad.data));

        lastScroller = rv;
        lastScrollerKey = paneKey();
        return rv;
    }

    /** 分组出现顺序：全部视图按 groups 列表排序，单个分组视图只那一组成立 */
    private List<String> orderedGroups(List<JSONObject> inGroup) {
        java.util.LinkedHashSet<String> present = new java.util.LinkedHashSet<>();
        for (JSONObject b : inGroup) present.add(b.optString("group", DEF_GROUP));
        List<String> ordered = new ArrayList<>();
        if (!ALL.equals(group)) {
            if (present.contains(group)) ordered.add(group);
        } else {
            for (String g : groups) {
                if (ALL.equals(g)) continue;
                if (present.contains(g)) ordered.add(g);
            }
        }
        for (String g : present) if (!ordered.contains(g)) ordered.add(g);
        return ordered;
    }

    /** 一行 cols 张书卡，不足时用空白占位保持对齐 */
    private View rowOfCards(List<JSONObject> cards, int coverH, int cols) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int gap = ModouUi.dp(this, 14);
        for (int i = 0; i < cols; i++) {
            View cell = i < cards.size() ? cardView(cards.get(i), coverH) : new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = gap;
            row.addView(cell, lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 24dp：书名两行 +「未读 N 章」之后，和下一排封面之间必须留出可见的空隙，
        // 12dp 时两者几乎贴在一起（横屏压缩后尤其明显，看着像书名压在封面上）
        rlp.bottomMargin = ModouUi.dp(this, 24);
        row.setLayoutParams(rlp);
        return row;
    }

    /** 分组标题：实心底（吸顶时盖住下方书卡）+ 强调色粗体 */
    private TextView sectionHeader(String text) {
        TextView h = ModouUi.tv(this, text, 13, ui.accent);
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        h.setPadding(ModouUi.dp(this, 4), ModouUi.dp(this, land() ? 2 : 10),
                ModouUi.dp(this, 4), ModouUi.dp(this, land() ? 2 : 10));
        h.setBackgroundColor(ui.bg);
        return h;
    }

    /**
     * 吸顶滚动容器：滚动时把当前分组标题显示到 pinned 头部。
     * 算法：找到「顶部已滚出视口」的最后一个分组标题，它就是吸顶的标题；
     * 若下一个标题即将顶到头部高度，则把 pinned 上移，做出被顶走的过渡。
     */
    private static final class SectionScroll extends ScrollView {
        final List<View> headers = new ArrayList<>();
        final List<String> headerText = new ArrayList<>();
        TextView pinned;

        SectionScroll(Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
        }

        @Override
        protected void onScrollChanged(int x, int y, int ox, int oy) {
            super.onScrollChanged(x, y, ox, oy);
            CoverLoader.markScroll();
            if (pinned == null) return;
            int sy = getScrollY();
            int active = -1;
            for (int i = 0; i < headers.size(); i++) {
                int top = headers.get(i).getTop() - sy;
                if (top <= 0) active = i;
                else break;
            }
            if (active < 0) {
                pinned.setVisibility(View.GONE);
                return;
            }
            pinned.setText(headerText.get(active));
            pinned.setVisibility(View.VISIBLE);
            int push = 0;
            if (active + 1 < headers.size()) {
                int nextTop = headers.get(active + 1).getTop() - sy;
                int ph = pinned.getHeight();
                if (nextTop < ph) push = nextTop - ph; // 负：被下一个标题顶上去
            }
            pinned.setTranslationY(push);
        }
    }

    /** 一张书卡：封面（圆角+阴影） + 角标 + 书名（两行） + 进度 + ⋯ */
    /** 书卡的可变部分：滚动复用时只换这些数据，视图骨架不动 */
    private static final class CardHolder {
        JSONObject book;
        ImageView cover;
        TextView badge;
        TextView name;
        TextView prog;
        /** 角标底层 drawable：复用时只 setColor，不重建 */
        android.graphics.drawable.GradientDrawable badgeBg;
    }

    /** 阅读历史行的 ViewHolder（原来 getView 整套 new，滚动时每行重建） */
    private static final class HistHolder {
        LinearLayout outer;
        ImageView cover;
        TextView name, sub, time;
    }

    private HistHolder newHistRow() {
        HistHolder h = new HistHolder();
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(0, ModouUi.dp(this, 4), 0, ModouUi.dp(this, 4));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ModouUi.squircle(this, ui.card, ui.spec.cornerCard));
        card.setElevation(ModouUi.dp(this, ui.spec.cardElevationDp));
        card.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 10),
                ModouUi.dp(this, 12), ModouUi.dp(this, 10));

        FrameLayout cf = new FrameLayout(this);
        cf.setBackground(ModouUi.squircle(this, 0xFFD8CFBB, 12));
        cf.setClipToOutline(true);
        h.cover = new ImageView(this);
        h.cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cf.addView(h.cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ModouUi.dp(this, 44), ModouUi.dp(this, 60));
        clp.rightMargin = ModouUi.dp(this, 12);
        card.addView(cf, clp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        h.name = ModouUi.tv(this, "", ui.spec.material == DesignSpec.Material.GLASS ? 14 : 16, ui.text);
        h.name.setTypeface(ui.spec.material == DesignSpec.Material.GLASS
                ? Typeface.SERIF : Typeface.create("sans-serif-medium", Typeface.NORMAL),
                ui.spec.material == DesignSpec.Material.GLASS ? Typeface.BOLD : Typeface.NORMAL);
        h.name.setSingleLine(true);
        h.name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(h.name);
        h.sub = ModouUi.tv(this, "", 11.5f, ui.sub);
        h.sub.setSingleLine(true);
        h.sub.setEllipsize(TextUtils.TruncateAt.END);
        h.sub.setPadding(0, ModouUi.dp(this, 4), 0, 0);
        info.addView(h.sub);
        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        h.time = ModouUi.tv(this, "", 11, ui.sub);
        h.time.setSingleLine(true);
        h.time.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
        card.addView(h.time);

        outer.addView(card);
        outer.setTag(h);
        h.outer = outer;
        return h;
    }

    /** 建卡 / 绑卡分开：以前每次 getView 都整套 new（含封面重新解码），滚动必掉帧 */
    private View cardView(JSONObject b, int coverH) {
        View v = newCard(coverH);
        bindCard(v, b);
        return v;
    }

    /** 书卡骨架：只建一次（点击一律从 holder 读当前书，复用后不会点错） */
    private View newCard(int coverH) {
        final CardHolder h = new CardHolder();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        // 点击语义与网页版一致：单击 = 直接进阅读（从进度处续读），长按 = 书籍详情。
        // 按压反馈用有界 ripple（圆角矩形 mask）：无界 ripple 会盖过整屏压在相邻封面上
        android.graphics.drawable.GradientDrawable cardMask = new android.graphics.drawable.GradientDrawable();
        cardMask.setColor(0xFFFFFFFF);
        cardMask.setCornerRadius(ModouUi.dp(this, 14));
        int ripColor = ModouUi.isDark(this) ? 0x1FFFFFFF : 0x14000000;
        l.setBackground(ui.spec.material == DesignSpec.Material.GLASS
                ? new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(ripColor), null, cardMask)
                : ModouUi.pressOverlay(this, ui.spec.cornerCard));
        final float cardR = ModouUi.dp(this, ui.spec.cornerCard);
        l.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cardR);
            }
        });
        l.setClipToOutline(true);
        l.setOnClickListener(v -> {
            JSONObject b = h.book;
            if (b == null) return;
            if (b.optBoolean("importing", false)) {
                ModouUi.toast(ShelfActivity.this, "《" + b.optString("name", "") + "》正在导入中…");
                return;
            }
            JSONObject p = prog.get(b.optString("id", ""));
            int ci = p == null ? 0 : p.optInt("index", 0);
            ReaderLaunch.open(ShelfActivity.this, ModouBridge.shared(), b, ci);
        });
        l.setLongClickable(true);
        l.setOnLongClickListener(v -> {
            JSONObject b = h.book;
            if (b == null) return false;
            if ("manual".equals(sort)) showManualSortMenu(b);
            else openBook(b);
            return true;
        });

        FrameLayout cover = new FrameLayout(this);
        cover.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, coverH));
        float coverRadius = ui.spec.material == DesignSpec.Material.TONAL ? 16f
                : ui.spec.material == DesignSpec.Material.LIQUID ? 12f : 14f;
        cover.setBackground(ModouUi.squircle(this, 0xFFD8CFBB, coverRadius,
                ModouUi.alpha(ui.text, 0x18), 1f));
        final float coverR = ModouUi.dp(this, coverRadius);
        cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), coverR);
            }
        });
        cover.setClipToOutline(true);
        cover.setElevation(ModouUi.dp(this,
                ui.spec.material == DesignSpec.Material.TONAL ? 4
                        : ui.spec.material == DesignSpec.Material.LIQUID ? 0 : 3));

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View sheen = new View(this);
        sheen.setBackground(new ModouSheen());
        sheen.setClickable(false);
        sheen.setFocusable(false);
        sheen.setVisibility(ui.spec.material == DesignSpec.Material.GLASS ? View.VISIBLE : View.GONE);
        cover.addView(sheen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 0.9.181：描边必须画在封面图「之上」——背景 drawable 的 stroke 会被全尺寸的
        // ImageView 整个盖住（之前那圈描边等于没生效），故单独盖一层透明环。
        View ring = new View(this);
        android.graphics.drawable.GradientDrawable rg =
                new android.graphics.drawable.GradientDrawable();
        rg.setColor(0x00000000);
        rg.setCornerRadius(ModouUi.dp(this, coverRadius));
        rg.setStroke(Math.max(1, ModouUi.dp(this, 1)), ModouUi.alpha(ui.text, 0x1C));
        ring.setBackground(rg);
        ring.setClickable(false);
        ring.setFocusable(false);
        cover.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 封面角标只回答「这本书从哪来」：极淡底 + 次级字色，不抢封面主视觉
        TextView badge = ModouUi.tv(this, "在线", 9f, ui.sub);
        badge.setPadding(ModouUi.dp(this, 5), ModouUi.dp(this, 2),
                ModouUi.dp(this, 5), ModouUi.dp(this, 2));
        // 角标背景骨架只建一次：bind 时仅改颜色，不再每行 new drawable
        h.badgeBg = new android.graphics.drawable.GradientDrawable();
        h.badgeBg.setCornerRadius(ModouUi.dp(this, 4));
        badge.setBackground(h.badgeBg);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.TOP | Gravity.END;
        bp.topMargin = ModouUi.dp(this, 6);
        cover.addView(badge, bp);
        l.addView(cover);

        TextView name = ModouUi.tv(this, "",
                ui.spec.material == DesignSpec.Material.TONAL ? 14f
                        : ui.spec.material == DesignSpec.Material.LIQUID ? 13f : 12.5f, ui.text);
        name.setTypeface(ui.spec.material == DesignSpec.Material.GLASS
                ? Typeface.SERIF : Typeface.create("sans-serif-medium", Typeface.NORMAL),
                ui.spec.material == DesignSpec.Material.GLASS ? Typeface.BOLD : Typeface.NORMAL);
        // 固定两行高度：一行书名的卡与两行的卡若各按内容撑高，下面进度行就一高一低
        name.setMinLines(2);
        name.setMaxLines(2);
        name.setLineSpacing(ModouUi.dp(this, 1), 1.32f);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, ModouUi.dp(this, 7), 0, 0);
        l.addView(name);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(0, ModouUi.dp(this, 3), 0, 0);
        boolean monoCard = ui.spec.lang == DesignSpec.Lang.MONOLITH;
        TextView pv = ModouUi.tv(this, "", monoCard ? 11f : 10.5f,
                monoCard ? (ui.spec.dark ? 0xFF9AA0B0 : 0xFF5A6070) : ui.sub);
        pv.setSingleLine(true);
        pv.setEllipsize(TextUtils.TruncateAt.END);
        foot.addView(pv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView more = new ImageView(this);
        more.setImageDrawable(new ModouGlyph(ModouGlyph.MORE_H, ui.sub,
                ModouUi.dp(this, 17), ModouUi.dp(this, 1.6f)));
        more.setScaleType(ImageView.ScaleType.CENTER);
        more.setContentDescription("书籍操作");
        int ms = ModouUi.dp(this, 30);
        more.setLayoutParams(new LinearLayout.LayoutParams(ms, ms));
        more.setBackground(ModouUi.pressOverlay(this, 17));
        more.setOnClickListener(x -> {
            if (h.book != null) cardMenu(h.book);
        });
        ModouUi.expandTouch(more, 48);
        ModouMotion.press(more);
        foot.addView(more);
        l.addView(foot);

        h.cover = iv;
        h.badge = badge;
        h.name = name;
        h.prog = pv;
        l.setTag(h);
        return l;
    }

    /** 把一本书的数据刷进复用的书卡 */
    private void bindCard(View v, JSONObject b) {
        Object tag = v == null ? null : v.getTag();
        if (!(tag instanceof CardHolder) || b == null) return;
        CardHolder h = (CardHolder) tag;
        h.book = b;
        CoverLoader.load(h.cover, b.optString("coverUrl", ""), b.optString("name", ""),
                        b.optString("author", ""), 0f);
        boolean local = "local://".equals(b.optString("sourceUrl", ""));
        h.badge.setText(local ? "本地" : "在线");
        // 骨架在 newCard 建好，这里只换底色/字色：滚动时零 drawable 分配
        if (h.badgeBg != null) h.badgeBg.setColor(ModouUi.alpha(ui.text, 0x0C));
        h.badge.setTextColor(local ? ui.sub : ui.accent);
        h.name.setText(b.optString("name", ""));
        h.prog.setText(progressText(b));
        String bid = b.optString("id", "");
        if (!bid.isEmpty()) cardProg.put(bid, h.prog); // 结构没变时靠这个原地刷新进度
    }

    private void openBook(JSONObject b) {
        BookActivity.launch(this, b.optString("id", ""));
    }

    /* ---------------- 菜单与筛选 ---------------- */

    private void cardMenu(JSONObject b) {
        String name = b.optString("name", "");
        boolean fav = b.optBoolean("fav", false);
        List<String> items = new ArrayList<>();
        items.add("继续阅读");
        items.add("书籍详情");
        items.add(fav ? "取消收藏" : "收藏");
        items.add("移到分组…");
        items.add("编辑信息");
        items.add("移出书架");
        ModouUi.sheetList(this, name, items.toArray(new String[0]), w -> {
            switch (w) {
                case 0: {
                    // 从书架进度处续读（没进度就从第一章）
                    JSONObject p = prog.get(b.optString("id", ""));
                    int ci = p == null ? 0 : p.optInt("index", 0);
                    ReaderLaunch.open(ShelfActivity.this, ModouBridge.shared(), b, ci);
                    break;
                }
                case 1:
                    openBook(b);
                    break;
                case 2:
                    setFav(b, !fav);
                    break;
                case 3:
                    moveGroup(b);
                    break;
                case 4:
                    editBookDialog(b);
                    break;
                default:
                    removeBook(b);
                    break;
            }
        });
    }

    /** 书架内直接编辑书名/作者/封面（与详情页同一套面板：自绘 sheet，深色下也是同一套色） */
    private void editBookDialog(JSONObject b) {
        ModouUi u = ModouUi.of(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 8), ModouUi.dp(this, 14), 0);

        final ImageView iv = new ImageView(this);
        int cw = ModouUi.dp(this, 84);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(cw, (int) (cw * 1.35f));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        ilp.bottomMargin = ModouUi.dp(this, 10);
        iv.setLayoutParams(ilp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        CoverLoader.load(iv, b.optString("coverUrl", ""), b.optString("name", ""),
                b.optString("author", ""), 0f);
        box.addView(iv);

        EditText name = ModouUi.input(this, "书名", b.optString("name", ""), 1);
        box.addView(name);
        EditText author = ModouUi.input(this, "作者", b.optString("author", ""), 1);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = ModouUi.dp(this, 8);
        box.addView(author, alp);
        EditText cover = ModouUi.input(this, "封面 URL", b.optString("coverUrl", ""), 1);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = ModouUi.dp(this, 8);
        box.addView(cover, clp);

        TextView search = ModouUi.tv(this, "在线搜封面", 14, u.accent);
        search.setPadding(0, ModouUi.dp(this, 10), 0, ModouUi.dp(this, 6));
        search.setOnClickListener((x) -> pickCover(b.optString("name", ""), search, (url) -> {
            cover.setText(url);
            CoverLoader.load(iv, url, b.optString("name", ""));
        }));
        box.addView(search);

        // 文件大小 / 本地保存位置：只读，长按可复制路径
        box.addView(BookFiles.infoCard(this, b));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog d = ModouUi.sheet(this, "编辑信息", box, footer);
        // 表单面板不允许「点外面就关」：编辑信息里要敲书名、作者、封面，
        // 手指落在面板边缘或被键盘顶起的空白区就等于把整张表单丢掉 ——
        // 表现就是「面板闪一下就没了，根本编辑不了」
        d.setCanceledOnTouchOutside(false);
        footer.addView(ModouUi.sheetButtons(this, d, "取消", "保存", null, () -> {
            try {
                b.put("name", name.getText().toString().trim());
                b.put("author", author.getText().toString().trim());
                b.put("coverUrl", cover.getText().toString().trim());
                saveBook(b);
                ModouUi.toast(this, "已保存");
                reload();
            } catch (Exception e) {
                ModouUi.toast(this, "保存失败");
            }
        }));
    }

    /** 封面候选网格：点选即采用 */
    private void showCoverCandidates(List<String> urls, CoverPick cb) {
        GridView gv = new GridView(this);
        gv.setNumColumns(3);
        gv.setHorizontalSpacing(ModouUi.dp(this, 14));
        gv.setVerticalSpacing(ModouUi.dp(this, 26));
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int cw = (screenW - ModouUi.dp(this, 48)) / 3;
        final int ch = (int) (cw * 1.35f);
        gv.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return urls.size();
            }

            @Override
            public Object getItem(int i) {
                return urls.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View cv, ViewGroup p) {
                ImageView im = new ImageView(ShelfActivity.this);
                im.setLayoutParams(new ViewGroup.LayoutParams(cw, ch));
                im.setScaleType(ImageView.ScaleType.CENTER_CROP);
                CoverLoader.load(im, urls.get(i), "");
                return im;
            }
        });
        // 系统 AlertDialog 的网格在深色下是白底，改走同一个自绘 sheet
        int rows = (urls.size() + 2) / 3;
        gv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rows * ch + Math.max(0, rows - 1) * ModouUi.dp(this, 8)));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(gv);
        final android.app.Dialog d = ModouUi.sheet(this, "选择封面", wrap);
        gv.setOnItemClickListener((a, v, i, id) -> {
            d.dismiss();
            if (cb != null) cb.on(urls.get(i));
        });
    }

    private void pickCover(String name, TextView btn, CoverPick cb) {
        BookActivity.searchCovers(this, name, () -> {
            if (btn != null) {
                btn.setText("搜索封面中…");
                btn.setEnabled(false);
            }
        }, urls -> {
            if (btn != null) {
                btn.setText("在线搜封面");
                btn.setEnabled(true);
            }
            if (urls.isEmpty()) {
                ModouUi.toast(ShelfActivity.this, "没搜到可用封面，可手动填封面 URL");
                return;
            }
            showCoverCandidates(urls, cb);
        });
    }

    private interface CoverPick {
        void on(String url);
    }

    private void setFav(JSONObject b, boolean fav) {
        try {
            b.put("fav", fav);
            saveBook(b);
            ModouUi.toast(this, fav ? "已收藏" : "已取消收藏");
            reload();
        } catch (Exception e) {
            ModouUi.toast(this, "操作失败");
        }
    }

    private void moveGroup(JSONObject b) {
        List<String> gs = new ArrayList<>(groups);
        gs.remove(ALL);
        ModouUi.sheetList(this, "移到分组", gs.toArray(new String[0]), w -> {
            try {
                b.put("group", gs.get(w));
                saveBook(b);
                ModouUi.toast(this, "已移到「" + gs.get(w) + "」");
                reload();
            } catch (Exception e) {
                ModouUi.toast(this, "操作失败");
            }
        });
    }

    /**
     * 移出书架：自绘确认面板 + 「同时删除本地文件」勾选（默认不勾）。
     *
     * 不勾 = 只从书架拿掉、缓存的正文留着（重新导入同一本书能直接接着读）；
     * 勾上 = 连缓存的正文一起删。要注意的实情：本地书的原文件在用户自己选的
     * 文件夹里，我们只读不复制，所以这里删的是**库内缓存**，不是那个原文件 ——
     * 提示文案照这个事实写，不糊弄。
     */
    private void removeBook(JSONObject b) {
        String name = b.optString("name", "");
        String id = b.optString("id", "");
        boolean local = id.startsWith("local://") || "local://".equals(b.optString("sourceUrl", ""));
        ModouUi u = ModouUi.of(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 4), ModouUi.dp(this, 14), 0);
        TextView msg = ModouUi.tv(this, "《" + name + "》将从书架移除，阅读进度一并清除。", 13.5f, u.sub);
        msg.setLineSpacing(ModouUi.dp(this, 3), 1.25f);
        box.addView(msg);

        /* 勾选行：22dp 方块 + 文案，点整行切换 */
        final boolean[] delFile = {false};
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ModouUi.dp(this, 16), 0, 0);
        TextView tick = ModouUi.tv(this, "", 12, 0xFFFFFFFF);
        tick.setGravity(Gravity.CENTER);
        int sz = ModouUi.dp(this, 22);
        row.addView(tick, new LinearLayout.LayoutParams(sz, sz));
        TextView label = ModouUi.tv(this, "同时删除本地文件", 14.5f, u.text);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = ModouUi.dp(this, 10);
        row.addView(label, llp);
        box.addView(row);

        TextView hint = ModouUi.tv(this, local
                        ? "本地书：勾选后会连手机里那个原文件一起删掉（不可恢复），并清掉缓存正文；不勾只移出书架，原文件保留。"
                        : "网络书：勾选后连已缓存的章节一起删掉，以后再打开要重新联网下载。",
                12, u.sub);
        hint.setLineSpacing(ModouUi.dp(this, 2), 1.25f);
        hint.setPadding(ModouUi.dp(this, 32), ModouUi.dp(this, 6), 0, 0);
        box.addView(hint);

        Runnable paintTick = () -> {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(ModouUi.dp(this, 6));
            if (delFile[0]) {
                g.setColor(u.accent);
                tick.setText("✓");
            } else {
                g.setColor(0x00000000);
                g.setStroke(Math.max(1, ModouUi.dp(this, 1.5f)), u.line);
                tick.setText("");
            }
            tick.setBackground(g);
        };
        paintTick.run();
        row.setOnClickListener((v) -> {
            delFile[0] = !delFile[0];
            paintTick.run();
        });

        final android.app.Dialog d = ModouUi.sheet(this, "移出书架", box);
        box.addView(ModouUi.sheetButtons(this, d, "取消", "移出", null, () -> {
            // 删记录之前先删原文件：库里那条记录里存着 fileUri，删了记录就再也找不到这个文件了
            String err = null;
            if (delFile[0] && local) {
                try {
                    // 书架上这条是 lite 记录（没有 fileUri），必须回库取整条记录再删
                    JSONObject full = b;
                    try {
                        String s = store.get("books", id);
                        if (s != null) full = new JSONObject(s);
                    } catch (Exception ignore) {
                        /* 取不到就按 lite 走，BookFiles 里还有媒体库反查兜底 */
                    }
                    err = BookFiles.deleteOriginal(this, full);
                } catch (Throwable e) {
                    err = "系统异常";
                    Diagnostics.event("书架删原文件失败: " + android.util.Log.getStackTraceString(e));
                }
            }
            store.del("books", id);
            store.del("progress", id);
            if (delFile[0]) {
                store.delChaptersOf(id);
                BookFiles.clearImages(ShelfActivity.this, id); // 正文清了，插图目录也别留着
            }
            if (delFile[0] && local && err != null) {
                // 失败必须让用户知道，否则以为文件已删
                ModouUi.toast(this, "已移出书架，但原文件没删掉：" + err);
            } else {
                ModouUi.toast(this, delFile[0]
                        ? (local ? "已移出书架，本地文件已删除" : "已移出书架，缓存正文已删除")
                        : "已移出书架");
            }
            reload();
        }));
    }

    /**
     * 老版本导入的本地书没有 fileUri：按「文件名 + 字节数」在媒体库里找回文档 Uri。
     * 现在统一由 {@link BookFiles#deleteOriginal(android.content.Context, JSONObject)} 处理，
     * 这里保留是为了不破坏别处的调用习惯。
     */
    private String resolveLocalUri(JSONObject b) {
        try {
            String bookUrl = b.optString("bookUrl", "");
            String file = bookUrl.startsWith(LocalImport.LOCAL) && bookUrl.contains("#")
                    ? bookUrl.substring(LocalImport.LOCAL.length(), bookUrl.lastIndexOf('#')) : "";
            long bytes = b.optLong("bytes", -1);
            if (file.isEmpty() || bytes <= 0) return "";
            for (LocalScan.Item it : LocalScan.scan(this)) {
                if (it != null && file.equals(it.name) && bytes == it.size && it.uri != null) {
                    return it.uri.toString();
                }
            }
        } catch (Throwable ignore) {
            /* 扫描失败就返回空串，交给上层提示 */
        }
        return "";
    }

    /** 筛选 chip：选中＝强调色实底白字，未选中＝淡底 */
    private TextView chip(String label, boolean on, Runnable click) {
        TextView t = ModouUi.tv(this, label, 13, on ? 0xFFFFFFFF : ui.text);
        t.setBackground(ModouUi.round(on ? ui.accent : ModouUi.alpha(ui.text, 0x12), 999));
        int h = ModouUi.dp(this, 7);
        t.setPadding(ModouUi.dp(this, 14), h, ModouUi.dp(this, 14), h);
        t.setOnClickListener((v) -> click.run());
        return t;
    }

    private void manageGroups() {
        List<String> gs = new ArrayList<>(groups);
        gs.remove(ALL);
        List<String> items = new ArrayList<>(gs);
        items.add("＋ 新建分组");
        ModouUi.sheetList(this, "分组管理", items.toArray(new String[0]), w -> {
            if (w < gs.size()) delGroup(gs.get(w));
            else newGroup();
        });
    }

    private void newGroup() {
        final EditText et = ModouUi.input(this, "分组名", "", 1);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(et);
        final android.app.Dialog d = ModouUi.sheet(this, "新建分组", box);
        box.addView(ModouUi.sheetButtons(this, d, "取消", "创建", null, () -> {
            String n = String.valueOf(et.getText()).trim();
            if (n.isEmpty()) return;
            List<String> cur = new ArrayList<>(groups);
            cur.remove(ALL);
            if (!cur.contains(n)) {
                cur.add(n);
                saveGroups(cur);
            }
        }));
        et.post(et::requestFocus);
    }

    private void delGroup(String g) {
        delGroup(g, null);
    }

    private void delGroup(String g, final Runnable after) {
        if (DEF_GROUP.equals(g)) {
            ModouUi.toast(this, "「默认」分组不能删除");
            return;
        }
        ModouUi.confirm(this, "删除分组", "删除「" + g + "」？组内书籍会回到「默认」。", () -> {
            List<String> cur = new ArrayList<>(groups);
            cur.remove(ALL);
            cur.remove(g);
            saveGroups(cur);
            // 组内的书回到默认，否则它们会从所有分组视图里消失
            for (JSONObject b : books) {
                if (g.equals(b.optString("group", DEF_GROUP))) {
                    try {
                        b.put("group", DEF_GROUP);
                        saveBook(b);
                    } catch (Exception e) {
                        /* 单本失败不中断 */
                    }
                }
            }
            if (g.equals(group)) group = ALL;
            reload();
            if (after != null) after.run();
        });
    }

    private void saveGroups(List<String> gs) {
        JSONArray a = new JSONArray();
        for (String s : gs) a.put(s);
        store.putSetting("shelfGroups", a.toString());
        ModouUi.toast(this, "已保存");
        reload();
    }

    /* ---------------- 工具 ---------------- */

    private static String fmtDur(long ms) {
        long s = ms / 1000;
        if (s < 60) return "不到 1 分钟";
        long m = s / 60;
        if (m < 60) return m + " 分钟";
        long hh = m / 60;
        long mm = m % 60;
        return mm != 0 ? hh + " 小时 " + mm + " 分钟" : hh + " 小时";
    }

    private static String relTime(long ts) {
        if (ts <= 0) return "";
        long d = System.currentTimeMillis() - ts;
        if (d < 60_000L) return "刚刚";
        if (d < 3_600_000L) return d / 60_000L + " 分钟前";
        if (d < 86_400_000L) return d / 3_600_000L + " 小时前";
        if (d < 172_800_000L) return "昨天";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd", Locale.CHINA);
        return f.format(new java.util.Date(ts));
    }
}
