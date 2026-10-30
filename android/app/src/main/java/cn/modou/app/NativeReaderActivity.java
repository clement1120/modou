package cn.modou.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 原生阅读页（渐进式原生化）。
 *
 * 与网页版同一份数据、同一套排版参数（字号/行距/段距/页边距/字体/字重/主题/亮度），
 * 并把网页版底部工具坞的核心能力都搬了过来：目录、正文全文搜索、朗读（原生 TTS +
 * 定时停止）、书签、自动阅读、净化规则入口、排版面板、设置入口。
 *
 * 数据协议（全部走 JSON，避免一堆 extra 字段）：
 *   JS → 原生   payload：{bookId, bookTitle, index, total, titles[], title, paras[], offset,
 *                        fontSize, lineHeight, paraGap, margin, font, weight, theme, brightness}
 *   原生 → JS   window.__modouNativeChapter(index)  按需取相邻章
 *   原生 → JS   ModouBridge.nativeChapterData(json) 回传 {index, title, paras[], error}
 *   原生 → JS   window.__modouNativeProgress(index, offset)  保存进度
 *   原生 → JS   window.__modouNativeBack()                   退出，由前端决定是否重渲染
 */
public class NativeReaderActivity extends Activity implements NativePageView.Host {

    private static NativeReaderActivity sInstance;
    /** 与网页版通信的桥；由 MainActivity 在启动本页时注入 */
    private static ModouBridge sBridge;
    /**
     * 是否从原生书架/详情页进来。
     *
     * 这两种入口没有网页会话，网页侧的取章函数（__modouNativeChapter）拿到调用也
     * 只会直接返回 —— 走那条路就是白等一个超时。用它区分「有没有网页会话可借」。
     */
    private boolean fromNative;
    /** 书籍完整记录缓存：取章要用 sourceUrl 与 chapters[].url，读一次就够 */
    private JSONObject bookCache;
    /** 换章时要落到的章内位置（书签跳转）；用完即清，否则翻页都会跳回旧位置 */
    private int pendingJump = 0;

    private FrameLayout root;
    private NativePageView pageView;
    private LinearLayout selBar;
    private TextView loading;
    private TextView header;
    private TextView headerSub;
    /** 顶栏返回钮：换主题时要重新着色（图标是 tinted 出来的位图，不会自己跟色） */
    private ImageView headBack;
    /** 排版抽屉是否开着：切主题时要按新配色重建它（它自己是旧色画的） */
    private boolean typoShowing;
    /** 顶部沉浸栏容器（含状态栏高度），随工具坞一起显隐 */
    private LinearLayout head;
    private boolean headShowing = false; // 顶栏默认藏起来：第一次开书不自动弹菜单
    /** 亮度遮罩：盖在正文上、不拦截触摸，透明度 = (1-亮度)*0.9 */
    private View dim;

    /* ---------------- 底部工具坞（对齐网页版 .rd-dock） ---------------- */

    /** 工具坞整体容器（重力贴底，含圆角面板） */
    private LinearLayout dock;
    private LinearLayout dockPanel;
    private LinearLayout dockRow1;
    private LinearLayout dockRow2;
    /** 两行图标之间的分隔线：某一行被清空时它也要跟着藏起来 */
    private View dockDivider;
    /**
     * 图标排布（对齐网页版 settings.readerMenuLayout）：
     * 第一行 / 第二行的 key 顺序 + 被隐藏的 key。写在设置表里，与网页版互通。
     */
    private final java.util.List<String> menuR1 = new java.util.ArrayList<>();
    private final java.util.List<String> menuR2 = new java.util.ArrayList<>();
    private final java.util.List<String> menuHidden = new java.util.ArrayList<>();
    private LinearLayout dockAuto;
    private TextView dockAutoSpeed;
    private TextView dockMetaL;
    private TextView dockMetaR;
    private SeekBar dockSeek;
    /** 本章书签所在页码：叠在工具坞进度条上做刻度点（网页版 .bmk-tick） */
    private final java.util.List<Integer> dockTicks = new java.util.ArrayList<>();
    private boolean dockShowing = false;
    /** 「上一章 / 下一章」按钮：换章后不用重建整个工具坞，改它们的可用状态就行 */
    private View dockPrev;
    private View dockNext;
    /** 程序化 setProgress 时抑制 SeekBar 回调，避免"刷新进度又触发跳页"的死循环 */
    private boolean seekSuppress = false;
    /** 手指正按在进度滑杆上：这期间只刷文字，别回写滑杆位置 */
    private boolean seekTracking = false;
    /** 当前弹出的底部抽屉（更多 / 排版），null 表示没有 */
    private View overlay;

    private NativeTheme theme = NativeTheme.of("paper");

    private String bookId = "";
    private String bookTitle = "";
    private String[] titles = new String[0];
    private int index = 0;
    private int total = 0;
    private String chapterTitle = "";

    /** 当前章的块序列（同章内搜索跳转要复用，避免重新向书源要一次） */
    private List<Paras.Block> curBlocks;

    /** 章节正在向网页侧请求：期间禁止重复请求，翻页也先挡住 */
    private boolean loadingChapter = false;

    /**
     * 章节正文的后台加载器。
     *
     * 读库（一章几十 KB 的字符串）、应用净化规则、HTML → 块序列（切段、认图片、
     * 去正文里重复的标题行）这几步以前全在主线程：换一章就是几百毫秒的卡顿，
     * 图多的 EPUB 更明显。现在都放到这条后台线程，主线程只负责排版一次。
     */
    private static final ExecutorService io =
            Executors.newSingleThreadExecutor((r) -> {
                Thread t = new Thread(r, "modou-chapter");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    /**
     * 预取 / 目录解析用的另一条线程。
     *
     * 以前它们和「用户正在等的这一章」排在同一个单线程池里：预取一本几十万字的大书，
     * 用户点下一章就得等它先读完、净化完、切块完 —— 看起来就是「下一章死活加载不出来」。
     * 分开之后，用户点的这一章永远排在自己那条队列的第一位。
     */
    private static final ExecutorService bg =
            Executors.newSingleThreadExecutor((r) -> {
                Thread t = new Thread(r, "modou-bg");
                t.setPriority(Thread.NORM_PRIORITY - 2);
                return t;
            });

    /**
     * 最近解析好的几章（LRU，最多 4 章）。
     *
     * 预取下一章是这个缓存的主要来源：翻到下一章时直接命中内存，
     * 不用再读库 + 解析，翻章手感就接近「同一章内翻页」。
     */
    private final LinkedHashMap<Integer, List<Paras.Block>> blockCache =
            new LinkedHashMap<Integer, List<Paras.Block>>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, List<Paras.Block>> e) {
                    return size() > 4;
                }
            };

    /** 目录是否已在后台补齐：没有的话章名先空着，靠正文首行推断 */
    private volatile boolean tocLoaded = false;
    private long lastSaveAt = 0;
    private int lastSavedOffset = -1;
    /** 上次计入阅读时长的时间点：每次落进度时把「距上次的这段」累加进 readMs */
    private long lastTickAt = 0;
    /** 只在本次进程里提示一次：原生页没有可见的工具栏，得让人知道怎么退出 */

    /* ---------------- 排版状态（与网页版设置键一一对应） ---------------- */

    private int fontSize = 18;          // sp
    private float lineHeight = 1.9f;
    private float paraGap = 1.0f;
    private int marginPx = 24;
    /** 正文首行到页眉（或屏幕顶）的距离：固定值，排版面板不再提供调节 */
    private static final int TOP_GAP_DP = 18;
    /**
     * 正文末行到页脚（或屏幕底）的距离：固定值。取 24 是为了让底部朗读小横条
     * （贴底 12dp、高 40dp）压不到正文最后一行 —— 末行底距屏底 44dp，横条顶在 52dp。
     * 上一版是 30，加上页尾那条被当成正文的段距空行，视觉上底部空出一整行多。
     */
    private static final int BOT_GAP_DP = 24;
    /** MD3 emphasized（强调过渡）/ decelerate（进入）：与网页版 --flow 同一条曲线 */
    private static final android.view.animation.Interpolator MD3_EMPH =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final android.view.animation.Interpolator MD3_DECEL =
            new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private String font = "song";
    private int weightVal = 500;         // 300/400/500/600
    private String lightTheme = "paper"; // paper/cream/gray
    private boolean darkMode = false;
    /** 亮度遮罩浓度：排版面板不再提供调节，恒为 1.0（不叠遮罩） */
    private float brightness = 1.0f;
    private boolean keepAwake = true;

    /** 翻页方式：false 左右翻页 / true 上下滚动（网页版 readerPageMode） */
    private boolean scrollMode = false;
    /** 动效三档：0 关闭 / 1 简约（仅淡入）/ 2 MD3 标准（emphasized 上滑 + 微反馈） */
    private int animGrade = 2;
    /** 页眉：0 不显示 / 1 章节名 / 2 书名 / 3 书名+章节 */
    private int headMode = 1;
    /** 状态栏高度（px）：先取系统资源，拿到真实窗口 inset 后再修正 */
    private int sbPx = -1;
    /** 页脚位掩码：1 页码 / 2 时间 / 4 电量 / 8 全书进度 / 16 本章进度 / 32 剩余章数 */
    private int footMask = 3;
    private final Handler hfHandler = new Handler(Looper.getMainLooper());
    /** 拖动滑杆时的防抖重排句柄 */
    private final Handler styleHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingStyle;
    /** 页脚时间/电量的定时刷新（30s 一次，与网页版 startFootClock 一致） */
    private final Runnable hfTick = new Runnable() {
        @Override
        public void run() {
            if (footMask != 0 && pageView != null) {
                pageView.invalidate();
                readBattery();
            }
            hfHandler.postDelayed(this, 30000);
        }
    };

    /**
     * 换章请求的代次。
     *
     * 以前用一个 loadingChapter 布尔挡重复请求，结果一旦某次卡住（等锁/等书源），
     * 后面的每一次点击都被这一句 `if (loadingChapter) return;` 静默吃掉 ——
     * 用户看到的就是「下一章死活加载不出来，怎么点都没反应」。
     * 现在新请求直接顶掉旧请求（代次 +1），旧回调回来发现自己过期就丢掉。
     */
    private int chapterGen;

    private final Runnable loadTimeout = () -> {
        if (!loadingChapter) return;
        // 让这条请求的迟到回调作废：用户重试之后，旧结果不能再贴到屏幕上
        chapterGen++;
        loadingChapter = false;
        hideLoading();
        toast("章节加载超时，请再点一次");
    };


    /* ---------------- 自动阅读 ---------------- */

    private boolean autoOn = false;
    private int autoInterval = 5000; // ms/页：中速
    /** 自动阅读顶部走秒条（网页版 .auto-tick）：满宽 → 收完就翻页 */
    private View autoBar;
    private long autoStartAt = 0;
    private final Handler autoHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoBarTick = new Runnable() {
        @Override
        public void run() {
            if (!autoOn || autoBar == null) return;
            float f = (SystemClock.uptimeMillis() - autoStartAt) / (float) Math.max(1, autoInterval);
            autoBar.setScaleX(Math.max(0f, 1f - Math.min(1f, f)));
            autoHandler.postDelayed(this, 40);
        }
    };
    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (!autoOn) return;
            int pages = pageView.pageCount();
            if (pages == 0) { // 章节还在加载：等一会再判断
                autoHandler.postDelayed(this, 600);
                return;
            }
            int p = pageView.page();
            if (p + 1 < pages) {
                pageView.gotoPage(p + 1);
                autoStartAt = SystemClock.uptimeMillis();
                autoHandler.postDelayed(this, autoInterval);
            } else if (index + 1 < NativeReaderActivity.this.total) {
                requestChapter(index + 1); // 跨章续读：加载完自动接着翻
                autoStartAt = SystemClock.uptimeMillis();
                autoHandler.postDelayed(this, 800);
            } else {
                stopAuto();
                refreshDockAuto();
                toast("自动阅读到结尾");
            }
        }
    };

    /* ---------------- 与 MainActivity 的接线 ---------------- */

    static void launch(Activity from, ModouBridge bridge, String payload) {
        sBridge = bridge;
        Intent i = new Intent(from, NativeReaderActivity.class);
        i.putExtra("payload", payload);
        from.startActivity(i);
    }

    /** 网页侧回传章节数据（由 ModouBridge 转调） */
    static void deliverChapter(String json) {
        NativeReaderActivity a = sInstance;
        if (a != null) a.runOnUiThread(() -> a.onChapterJson(json));
    }

    /** 是否正开着阅读页：书架据此把后台杂活（补封面、lite 回填）让开 */
    static boolean isOpen() {
        return sInstance != null;
    }

    /** MainActivity 销毁时清掉桥引用，避免静态持有已泄漏的 WebView */
    static void clearBridge() {
        sBridge = null;
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;

        JSONObject o = parse(getIntent().getStringExtra("payload"));

        fontSize = (int) o.optDouble("fontSize", 18);
        lineHeight = (float) o.optDouble("lineHeight", 1.9);
        paraGap = (float) o.optDouble("paraGap", 1.0);
        marginPx = o.optInt("margin", 16);
        font = o.optString("font", "song");
        weightVal = o.optInt("weight", 500);
        lightTheme = o.optString("theme", "paper");
        if (!"paper".equals(lightTheme) && !"cream".equals(lightTheme) && !"gray".equals(lightTheme)) {
            lightTheme = "paper";
        }
        // 亮度不再可调节：旧存档里可能存着 0.6 这类值，照读会让屏幕一直偏暗，故恒为 1.0
        brightness = 1.0f;
        // 翻页方式与页眉页脚：与网页版共用同一组设置键，网页里改过的设置原生要能接上
        scrollMode = "scroll".equals(ModouStore.get(this).settingStr("readerPageMode", "page"));
        try {
            animGrade = Integer.parseInt(ModouStore.get(this).settingStr("uiAnimGrade", "2"));
        } catch (Exception e) {
            animGrade = 2;
        }
        if (animGrade < 0 || animGrade > 2) animGrade = 2;
        readHeadFoot();
        // 全局深浅决定阅读页是深色还是浅色；阅读页只额外记「浅色用哪种纸色」
        darkMode = ModouUi.isDark(this);
        theme = NativeTheme.of(darkMode ? "dark" : lightTheme);

        // 开屏那一帧是 windowBackground：先染成阅读底色，避免夜间模式闪一下白
        getWindow().setBackgroundDrawable(new ColorDrawable(theme.bg));
        applyBars();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applySystemBarIcons();

        fromNative = o.optBoolean("fromNative", false);
        bookId = o.optString("bookId", "");
        bookTitle = o.optString("bookTitle", "");
        index = o.optInt("index", 0);
        total = o.optInt("total", 0);
        chapterTitle = o.optString("title", "");
        titles = strArray(o.optJSONArray("titles"));

        buildUi();
        applyBrightness(brightness);

        applyStyle();
        readBattery();
        syncHeadFoot();
        JSONArray paras0 = o.optJSONArray("paras");
        // 冷启动这条路径以前直接把文本丢给视图、连标题都不设置：既没有居中章标题，
        // 又因为没走 fixHeading 让「正文自带的标题行」漏在首屏（屏幕上两个一样的标题）。
        // 现在和换章路径用同一套矫正，再统一交给视图。
        String body0 = parasToText(paras0);
        if (!body0.trim().isEmpty()) {
            String[] f0 = fixTextHeading(purifyTitle(chapterTitle == null ? "" : chapterTitle.trim()), body0);
            chapterTitle = f0[0];
            pageView.setChapter(f0[1], f0[0], o.optInt("offset", 0));
        }
        lastTickAt = System.currentTimeMillis(); // 阅读时长从进页开始算
        updateHeader();

        // 入口只带了「哪本书、第几章」：正文与目录都在这条后台线程里取。
        // 首章没缓存过时先给一句「加载中」，而不是留一屏白纸。
        if (paras0 == null || paras0.length() == 0) {
            if (!bookId.isEmpty()) {
                loading.setText("正在打开…");
                loading.setVisibility(View.VISIBLE);
                requestChapter(index, o.optInt("offset", 0));
            }
        }
        // 目录与正文并行取（都在后台）：目录先到，首屏排版时章标题就已经有了，
        // 不会出现「先出一屏无标题正文、标题到了再整页跳一下」。这里只是一次
        // 单行读取 + json 解析，不占主线程，也不是首章卡顿的来源。
        loadTocAsync();

        // 冷启动不再弹「轻点屏幕中间…」的提示：第一屏就教人操作很吵，
        // 而且每次进书都会再来一次。
    }

    @Override
    protected void onResume() {
        super.onResume();
        sInstance = this;
        // 读者在前台：书架那边的整本 json 回填（lite 列）暂停，别抢数据库锁
        ModouStore.setQuiet(true);
        applySystemBarIcons();
        // 页脚的时间/电量：进页先取一次，之后每 30s 刷新（只在页脚开启时跑）
        readBattery();
        hfHandler.removeCallbacks(hfTick);
        if (footMask != 0) hfHandler.postDelayed(hfTick, 30000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退出阅读页（或被打断）：后台杂活可以继续了
        ModouStore.setQuiet(false);
        stopAuto();
        saveProgress(true);
        hfHandler.removeCallbacks(hfTick);
    }

    @Override
    protected void onDestroy() {
        if (sInstance == this) sInstance = null;
        stopAuto();
        cancelTtsTimer();
        hfHandler.removeCallbacks(hfTick);
        if (pendingStyle != null) styleHandler.removeCallbacks(pendingStyle);
        // 不释放的话朗读会在退出后继续响 —— TextToSpeech 是全局服务，不随 Activity 结束
        if (tts != null) {
            try {
                tts.release();
            } catch (Exception e) {
                /* 忽略 */
            }
            tts = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 抽屉 → 工具坞 → 选区，逐层退出，最后才离开阅读页
        if (overlay != null) {
            closeSheet();
            return;
        }
        if (dockShowing) {
            hideDock();
            return;
        }
        if (selBar != null && selBar.getVisibility() == View.VISIBLE) {
            pageView.clearSel();
            return;
        }
        stopAuto();
        saveProgress(true);
        if (sBridge != null) sBridge.evalJs("window.__modouNativeBack && window.__modouNativeBack()");
        finish();
    }

    /* ---------------- UI ---------------- */

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(theme.bg);

        pageView = new NativePageView(this);
        pageView.setHost(this);
        root.addView(pageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 自动阅读走秒条：贴状态栏下沿、3dp 高，从左往右收完就翻页
        autoBar = new View(this);
        GradientDrawable autoBg = new GradientDrawable();
        autoBg.setColor(theme.acc);
        autoBar.setBackground(autoBg);
        autoBar.setVisibility(View.GONE);
        autoBar.setPivotX(0f);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        alp.gravity = Gravity.TOP;
        alp.topMargin = statusBarHeight();
        root.addView(autoBar, alp);

        // 亮度遮罩：铺满、不拦截触摸，正文与菜单都盖在它之上
        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        dim.setClickable(false);
        dim.setFocusable(false);
        root.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部沉浸栏：返回圆钮 + 书名/章次两行（对齐网页版 .rd-head）
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(theme.bg);
        head.setElevation(dp(3));
        head.setPadding(dp(6), 0, dp(6), 0);

        ImageView back = new ImageView(this);
        headBack = back;
        back.setImageDrawable(tinted(R.drawable.ic_rd_back, theme.text));
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable backBg = new GradientDrawable();
        backBg.setShape(GradientDrawable.OVAL);
        backBg.setColor(Color.TRANSPARENT);
        back.setBackground(backBg);
        back.setOnClickListener(v -> onBackPressed());
        head.addView(back, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.CENTER_HORIZONTAL);
        header = new TextView(this);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        header.setTextColor(theme.text);
        header.setTypeface(Typeface.SERIF, Typeface.BOLD);
        header.setSingleLine(true);
        header.setGravity(Gravity.CENTER);
        header.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.addView(header);
        headerSub = new TextView(this);
        headerSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        headerSub.setTextColor(theme.sub);
        headerSub.setSingleLine(true);
        headerSub.setGravity(Gravity.CENTER);
        headerSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.addView(headerSub);
        head.addView(meta, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧等宽占位，让书名真正居中（网页版同款隐形按钮）
        View gap = new View(this);
        head.addView(gap, new LinearLayout.LayoutParams(dp(36), dp(36)));

        head.setOnClickListener(v -> showMenu());
        // 高度比「状态栏 + 46dp」少 6dp：正文顶（padT）就在 46dp 处，而中文字形的墨迹
        // 会比行框上沿再高出 2~4dp。顶栏底板正好压到 46dp 时，第一行的字头会被这条
        // 底板削平（菜单打开时尤其明显）；让出 6dp，墨迹落在顶栏与正文之间的留白里。
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46) + statusBarHeight() - dp(6));
        hp.topMargin = 0;
        head.setPadding(dp(6), statusBarHeight(), dp(6), 0);
        head.setVisibility(View.GONE); // 与 headShowing=false 一致：点正文中部才出现
        root.addView(head, hp);

        loading = new TextView(this);
        loading.setText("加载中…");
        loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        loading.setTextColor(theme.sub);
        loading.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(loading, lp);

        selBar = buildSelBar();
        selBar.setVisibility(View.GONE);
        root.addView(selBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        loadMenuCfg(); // 图标排布要先于工具坞构建：两行按用户顺序画
        buildDock();

        setContentView(root);
    }

    private LinearLayout buildSelBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));
        GradientDrawable g = new GradientDrawable();
        g.setColor(theme.dark ? 0xFF2A2C31 : 0xFFFCF8EE);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), theme.dark ? 0x33FFFFFF : 0x1A000000);
        bar.setBackground(g);
        bar.setElevation(dp(8));

        bar.addView(barBtn("复制", this::copySelection));
        bar.addView(barBtn("搜索", () -> {
            CharSequence t = pageView.selectionText();
            pageView.clearSel();
            showSearch(t == null ? "" : t.toString());
        }));
        bar.addView(barBtn("分享", this::shareSelection));
        // 「全选」= 整章（番茄/微信如此），不是当前这一页
        bar.addView(barBtn("全选", () -> pageView.selectAll()));
        bar.addView(barBtn("替换净化", this::purifySelection));
        bar.addView(barBtn("取消", () -> pageView.clearSel()));
        return bar;
    }

    private TextView barBtn(String label, Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(theme.text);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(11), dp(12), dp(11));
        tv.setOnClickListener(v -> action.run());
        return tv;
    }

    private void applySystemBarIcons() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (!theme.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    /** 状态栏/导航栏底色随主题走，避免浅色主题下露出黑边（全页面沉浸） */
    private void applyBars() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(theme.bg);
        // 窗口底色也跟着阅读主题走：系统栏与内容之间的缝隙露出来的就是它，
        // 不设就会露出 app_bg（比纸色白一点），看着像多了一条白边。
        getWindow().setBackgroundDrawable(new ColorDrawable(theme.bg));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(theme.bg);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 会给浅色系统栏自动叠一层对比遮罩；纸色底上就成了一条灰白带
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
    }

    /* ---------------- 排版应用 ---------------- */

    /** 把当前排版状态套到正文视图（任一项变化都走这里，避免参数散落不一致） */
    private void applyStyle() {
        float d = getResources().getDisplayMetrics().density;
        // 页眉页脚各占一条带，从正文可用高度里实扣。顶栏是浮层（点中间才出现），
        // 不再像之前那样常驻预留 54dp —— 那会让正文上方空掉一大块。
        // 只看开关不看文案：章节还没加载完时 headText() 是空的，若按空文案不预留，
        // 章名一到页眉就凭空压到正文第一行上。
        // 页眉带 28dp + 间隙 12dp：正文顶只比上一版下移 4dp（不多占白），
        // 但页眉字底到正文顶从 14dp 变成 22dp —— 视觉上就是「不再压着首行」。
        int headH = (!scrollMode && headMode != 0) ? Math.round(28 * d) : 0;
        int footH = (!scrollMode && footMask != 0) ? Math.round(20 * d) : 0;
        // 上下间距按固定值（排版面板不再提供「上边距 / 下边距」这两项调节）
        int topGap = Math.round(TOP_GAP_DP * d);
        int botGap = Math.round(BOT_GAP_DP * d);
        // 先静默改页眉页脚配置，再由 setStyle 的 pad 变化触发唯一一次重排
        pageView.setScrollMode(scrollMode);
        pageView.setHeadFoot(headMode, footMask);
        pageView.setFootBandH(footH);
        pageView.setStyle(theme, fontSize * d, lineHeight, font, weightVal, paraGap,
                Math.round(marginPx * d),
                statusBarHeight() + headH + topGap,
                Math.round(marginPx * d),
                footH + botGap);
    }

    /** 页眉文案（空则不占位） */
    private String headText() {
        String b = bookTitle == null ? "" : bookTitle.trim();
        String c = chapterTitle == null ? "" : chapterTitle.trim();
        switch (headMode) {
            case 1:
                return c;
            case 2:
                return b;
            case 3:
                return c.isEmpty() ? b : (b.isEmpty() ? c : b + " · " + c);
            default:
                return "";
        }
    }

    /** 把书名/章名/章序号喂给正文视图，页眉页脚与全书进度要用 */
    private void syncHeadFoot() {
        if (pageView == null) return;
        pageView.setHeadFootText(bookTitle, chapterTitle, index, total);
    }

    /** 电量（页脚可选显示）；取不到就留 -1，页脚自动省掉这一项 */
    private void readBattery() {
        if (pageView == null || (footMask & 4) == 0) return;
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            int pct = bm == null ? -1
                    : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            pageView.setBattery(pct);
        } catch (Exception e) {
            /* 旧系统没有该属性：不显示电量 */
        }
    }

    /** 读网页版的 readerHeadFoot 设置：{head:'chapter', foot:['page','time']} */
    private void readHeadFoot() {
        String raw = ModouStore.get(this).settingRaw("readerHeadFoot");
        if (raw == null || raw.isEmpty()) return; // 保持默认：章节名 + 页码/时间
        try {
            JSONObject o = new JSONObject(raw);
            String h = o.optString("head", "chapter");
            headMode = "none".equals(h) ? 0 : "book".equals(h) ? 2 : "both".equals(h) ? 3 : 1;
            JSONArray f = o.optJSONArray("foot");
            if (f == null) return;
            int m = 0;
            for (int i = 0; i < f.length(); i++) {
                String k = f.optString(i, "");
                if ("page".equals(k)) m |= 1;
                else if ("time".equals(k)) m |= 2;
                else if ("battery".equals(k)) m |= 4;
                else if ("bookPct".equals(k)) m |= 8;
                else if ("chapPct".equals(k)) m |= 16;
                else if ("left".equals(k)) m |= 32;
            }
            footMask = m;
        } catch (Exception e) {
            /* 脏数据：用默认配置 */
        }
    }

    /** 写回网页版同一个键，网页版再进阅读页时配置一致 */
    private void saveHeadFoot() {
        try {
            JSONObject o = new JSONObject();
            o.put("head", new String[]{"none", "chapter", "book", "both"}[headMode]);
            JSONArray f = new JSONArray();
            String[] keys = {"page", "time", "battery", "bookPct", "chapPct", "left"};
            for (int i = 0; i < keys.length; i++) {
                if ((footMask & (1 << i)) != 0) f.put(keys[i]);
            }
            o.put("foot", f);
            o.put("v", 2);
            ModouStore.get(this).putSetting("readerHeadFoot", o.toString());
        } catch (Exception e) {
            /* 忽略 */
        }
    }

    private void applyBrightness(float v) {
        brightness = (float) Math.min(1.0, Math.max(0.5, v));
        dim.setAlpha((float) Math.max(0, Math.min(0.9, (1 - brightness) * 0.9)));
    }

    /** 主题切换：更新配色 + 系统栏 + 沉浸条，并同步给全局与网页版 */
    private void applyTheme(String name) {
        boolean toDark = "dark".equals(name);
        darkMode = toDark;
        if (!toDark) lightTheme = name;
        theme = NativeTheme.of(toDark ? "dark" : lightTheme);
        applyBars();
        applySystemBarIcons();
        // 顶栏 / 加载提示 / 选区条这些「建好就不动」的视图不会自己跟着变色：
        // 只换正文的话，夜间模式下顶上仍留着一条纸色的栏（用户说的「切夜间顶上还白」）
        applyChromeColors();
        applyStyleNow();
        // 工具坞、朗读面板、底部小横条都是建的时候取色，一起重建
        refreshDockTheme();
        // 排版抽屉也是旧色画的，且主题按钮就在它里面：就地按新配色重建一次
        if (typoShowing) root.post(this::showTypoPanel);
        // 全局外观：选浅色纸 → 整 App 变浅色；选夜间 → 整 App 变深色（与网页版一致）
        String appMode = toDark ? "dark" : "light";
        ModouStore.get(this).putSettingStr("appTheme", appMode);
        ModouStore.get(this).putSettingStr("readerLightTheme", lightTheme);
        ModouUi.saveThemeMode(this, appMode);
        if (sBridge != null) {
            sBridge.evalJs("window.__modouSaveAppTheme && window.__modouSaveAppTheme('" + appMode + "')");
        }
    }

    /** 把当前主题色刷到「常驻不重建」的那几块界面元素上（顶栏、状态条、加载提示、选区条） */
    private void applyChromeColors() {
        if (root != null) root.setBackgroundColor(theme.bg);
        if (autoBar != null) {
            GradientDrawable ab = new GradientDrawable();
            ab.setColor(theme.acc);
            autoBar.setBackground(ab);
        }
        if (head != null) {
            head.setBackgroundColor(theme.bg);
            if (headBack != null) headBack.setImageDrawable(tinted(R.drawable.ic_rd_back, theme.text));
            if (header != null) header.setTextColor(theme.text);
            if (headerSub != null) headerSub.setTextColor(theme.sub);
        }
        if (loading != null) loading.setTextColor(theme.sub);
        if (selBar != null && root != null) {
            // 选区条是横排的一串 TextView，逐个改色不如按新主题重建一遍（顺便换圆角底和描边）
            int vis = selBar.getVisibility();
            root.removeView(selBar);
            selBar = buildSelBar();
            selBar.setVisibility(vis);
            root.addView(selBar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /* ---------------- 章节 ---------------- */

    /**
     * 取章：先查共享存储层（SQLite），命中就直接解析排版出屏，**不回网页侧**；
     * 未命中（在线书源的章还没抓过、库里没有缓存）才走 JS 的书源引擎去抓。
     *
     * 这样已缓存的章（本地导入的书、读过的章）翻页是纯本地 IO，没有 JS 往返；
     * 同时又不会丢掉在线书源的联网取章能力。
     */
    private void requestChapter(int idx) {
        requestChapter(idx, 0);
    }

    /** @param offset 章内字符偏移（书签跳转用）；换章时由 pendingJump 带进异步回调 */
    private void requestChapter(int idx, int offset) {
        if (idx < 0) return;
        // 章数可能是书架轻量记录带进来的旧值（chapterCount 落后于真实目录）。
        // 以前这里直接 return —— 点「下一章」什么都不发生、也不给提示，看着就像卡死。
        // 现在以库里的目录为准刷新一次再判，真到头了就说清楚。
        if (total > 0 && idx >= total) {
            int real = 0;
            try {
                real = ModouStore.get(this).chapterCount(bookId);
            } catch (Throwable ignore) {
                /* 读不到就按原值处理 */
            }
            if (real > total) {
                total = real;
                refreshDockNav();
            }
            if (total > 0 && idx >= total) {
                loadingChapter = false;
                root.removeCallbacks(loadTimeout);
                hideLoading();
                toast("已经是最后一章了");
                return;
            }
        }
        // 新请求顶掉旧请求：旧请求还在等锁/等网络时，用户的这几次点击不能白点
        final int gen = ++chapterGen;
        pendingJump = Math.max(0, offset);
        final int want = idx;

        // ① 内存命中（预取过 / 刚读过的相邻章）：直接排版，没有任何 IO
        List<Paras.Block> hit = cacheGet(want);
        if (hit != null) {
            loadingChapter = false;
            root.removeCallbacks(loadTimeout);
            hideLoading();
            applyChapter(want, purifyTitle(chapterNameOf(want)), hit, takeJump());
            return;
        }

        // ② 后台读库 + 净化 + 解析，主线程只等结果
        loadingChapter = true;
        loading.setText(want > index ? "加载下一章…" : "加载上一章…");
        loading.setVisibility(View.VISIBLE);
        // 读库这条路以前没有超时：一旦卡在等锁上，转圈就一直转，用户点多少次都
        // 被 loadingChapter 挡回去。现在所有路径共用同一个超时。
        root.removeCallbacks(loadTimeout);
        root.postDelayed(loadTimeout, 12000);
        io.execute(() -> {
            List<Paras.Block> bl = null;
            String name = "";
            try {
                name = purifyTitle(chapterNameOf(want));
                bl = readChapterBlocks(want, name);
            } catch (Throwable ignore) {
                /* 库里没有 / 解析失败：交给联网路径 */
            }
            final List<Paras.Block> got = bl;
            final String nm = name;
            runOnUiThread(() -> {
                if (gen != chapterGen) return; // 已被更晚的请求顶掉
                if (isFinishing() || isDestroyed()) return;
                loadingChapter = false;
                hideLoading();
                root.removeCallbacks(loadTimeout);
                if (got != null && !got.isEmpty()) {
                    applyChapter(want, nm, got, takeJump());
                    return;
                }
                // ③ 库里没有这一章：走书源服务（常驻无头 WebView），再退到网页桥
                requestViaSource(want, () -> {
                    if (gen != chapterGen) return;
                    // 本地导入的书没有书源，"取不到"就是文件里真的没有正文 ——
                    // 说清楚比让人盯着「加载中」转圈强
                    if (bookId != null && bookId.startsWith(LocalImport.LOCAL)) {
                        toast("这一章没有正文，可能导入时没解析到内容");
                        return;
                    }
                    // 网页桥只在「从网页版进阅读页」时有用：网页侧的取章函数要读它的
                    // 会话（native-reader.js 的 session）。从原生书架进来的没有那个会话，
                    // 发了也只是空等一个超时 —— 直接说结论，别让人对着转圈猜。
                    if (fromNative) {
                        toast("这一章取不到正文：这本书没有可用的书源规则");
                        return;
                    }
                    ModouBridge br = sBridge != null ? sBridge : MainActivity.ensureBridge();
                    if (br == null) {
                        toast("已断开与阅读页的连接，请返回重进");
                        return;
                    }
                    sBridge = br;
                    loadingChapter = true;
                    loading.setText(want > index ? "加载下一章…" : "加载上一章…");
                    loading.setVisibility(View.VISIBLE);
                    root.removeCallbacks(loadTimeout);
                    root.postDelayed(loadTimeout, 10000);
                    br.evalJs("window.__modouNativeChapter && window.__modouNativeChapter(" + want + ")");
                });
            });
        });
    }

    /* ---------------- 章节数据（全部在 io 线程做） ---------------- */

    private List<Paras.Block> cacheGet(int idx) {
        synchronized (blockCache) {
            return blockCache.get(idx);
        }
    }

    private void cachePut(int idx, List<Paras.Block> blocks) {
        synchronized (blockCache) {
            blockCache.put(idx, blocks);
        }
    }

    /**
     * 读一章并解析成块序列。命中缓存直接返回；库里没有返回 null。
     *
     * 只在 io 线程调用：净化规则用的是懒加载的 ruleCache（只读），
     * ModouStore 的读写本身是 synchronized 的。
     */
    private List<Paras.Block> readChapterBlocks(int idx, String name) {
        List<Paras.Block> hit = cacheGet(idx);
        if (hit != null) return hit;
        // HARD_READ_CHARS：单章正文一次最多搬这么多字。老版本导入的 EPUB 会把插图
        // base64 内联在正文里，一行几 MB 到几十 MB；整章读进堆再净化/切块，冷启动
        // 第一次进书就卡十几秒，然后被判 ANR 或 OOM 杀掉 —— 用户看到「转很久，
        // 闪一下回到书架」。这里加硬上限，宁可截断也不撑爆进程。
        String html = ModouStore.get(getApplicationContext())
                .chapterContentCapped(bookId, idx, HARD_READ_CHARS);
        if (html == null || html.trim().isEmpty()) return null;
        boolean heavy = false;
        if (html.length() > MAX_CHAPTER_CHARS) {
            html = stripBigDataUrls(html);
            heavy = true;
        }
        // 净化规则要在这里生效：网页版是在 purifyContent 之后才交给渲染的，
        // 原生若跳过这一步，用户在设置页配的替换规则在阅读页就等于没配。
        // 走块序列而不是纯文本：EPUB 正文里的插图因此才能显示出来。
        String body = purifyContent(html);
        if (body.length() > MAX_CHAPTER_CHARS) {
            body = body.substring(0, MAX_CHAPTER_CHARS);
            heavy = true;
        }
        List<Paras.Block> bl = Paras.blocks(body, name);
        if (bl.isEmpty()) return null;
        if (heavy) noteHeavyChapter();
        cachePut(idx, bl);
        return bl;
    }

    /** 单章正文的展示上限（约 90 万字，够读十几分钟） */
    private static final int MAX_CHAPTER_CHARS = 900000;
    /** 单章正文的读取硬上限：再大就只读前这么多，剩下的直接放弃 */
    private static final int HARD_READ_CHARS = 6000000;
    /** 只剥这么长的 data URL：短的小图标留着还能显示 */
    private static final int BIG_DATA_URL = 2048;
    private boolean heavyNotified;

    /** 剥掉正文里的超长内联图片（base64），只保留文字与短资源 */
    private static String stripBigDataUrls(String s) {
        StringBuilder out = new StringBuilder(Math.min(s.length(), 1 << 21));
        int i = 0;
        final int n = s.length();
        while (i < n) {
            int k = s.indexOf("data:image/", i);
            if (k < 0) {
                out.append(s, i, n);
                break;
            }
            int j = k + 11;
            while (j < n) {
                char ch = s.charAt(j);
                if (ch == '"' || ch == '\'' || ch == ')' || ch == ' ' || ch == '>') break;
                j++;
            }
            out.append(s, i, k);
            if (j - k < BIG_DATA_URL) out.append(s, k, j); // 小图留原样
            i = j;
        }
        return out.toString();
    }

    /** 大章提示每本书只说一次，别每翻一页弹一遍 */
    private void noteHeavyChapter() {
        if (heavyNotified) return;
        heavyNotified = true;
        toast("本章体积过大（内嵌插图多），已省略插图只显示文字");
    }

    /**
     * 预取下一章。
     *
     * 「加载下一章慢」有一半是「等它读完库、解析完才开始排版」。读书是线性的，
     * 下一章必然会用到，趁当前章还在看的时候把它准备好，翻章就只剩排版。
     * 只在后台做，失败无所谓（真翻过去时 requestChapter 还会再走一次）。
     */
    private void prefetchChapter(int idx) {
        if (idx < 0 || (total > 0 && idx >= total)) return;
        if (cacheGet(idx) != null) return;
        bg.execute(() -> {
            try {
                readChapterBlocks(idx, purifyTitle(chapterNameOf(idx)));
            } catch (Throwable ignore) {
                /* 预取失败不影响阅读 */
            }
        });
    }

    /**
     * 目录后台补齐：章名数组（几千项）也是从整本 json 里来的，
     * 放主线程解析会让「进书第一屏」白等两百毫秒。这里先出正文，目录稍后到。
     */
    /** 目录正在后台解析（防重复解析同一份 json） */
    private boolean tocLoading;

    private void loadTocAsync() {
        // tocLoading：进书时 onCreate 和首章 applyChapter 都会调到这里，
        // 没有这个闸门就会把同一份几百 KB 的目录 json 解析两遍
        if (bookId.isEmpty() || tocLoaded || tocLoading) return;
        tocLoading = true;
        bg.execute(() -> {
            String[] names = null;
            int n = 0;
            try {
                JSONObject b = bookObj();
                JSONArray chs = b == null ? null : b.optJSONArray("chapters");
                if (chs != null && chs.length() > 0) {
                    n = chs.length();
                    names = new String[n];
                    for (int i = 0; i < n; i++) {
                        JSONObject c = chs.optJSONObject(i);
                        names[i] = c == null ? "" : c.optString("name", "");
                    }
                }
            } catch (Throwable t) {
                names = null;
            }
            final String[] got = names;
            final int len = n;
            runOnUiThread(() -> {
                tocLoading = false;
                if (got == null || got.length == 0) return;
                if (isFinishing() || isDestroyed()) return;
                if (titles.length == 0) titles = got;
                if (total <= 0) total = len;
                tocLoaded = true;
                // 首章是「瘦入口」进来的：那时目录还没到，章标题是空的。
                // 目录一到就补上，否则整章首屏没有标题，得翻页才出现。
                if ((chapterTitle == null || chapterTitle.isEmpty()) && index >= 0) {
                    String nm = purifyTitle(chapterNameOf(index));
                    if (!nm.isEmpty()) {
                        chapterTitle = nm;
                        pageView.setTitle(nm);
                    }
                }
                updateHeader();
                refreshDockNav();
            });
        });
    }

    /**
     * 通过书源服务取未缓存的章节。
     *
     * @return true 表示已接管（结果异步回来）；false 表示这条路径用不了，调用方继续回退
     */
    /**
     * 通过书源服务取未缓存的章节。
     *
     * 关键：**bookObj() 必须在后台线程做**。整条书籍记录（含几千章目录、内联封面）
     * 动辄几百 KB～几 MB，在翻页/进书的主线程里 new JSONObject 就是一次几百毫秒的卡顿。
     * 以前这里直接在 UI 线程解析，本地书还要白解析一遍才因为「没有书源」退出，
     * 用户看到的就是「翻下一章卡一下」。
     *
     * @param fallback 这条路走不通时回到 UI 线程继续（通常是退回网页桥）
     */
    private void requestViaSource(int idx, Runnable fallback) {
        SourceService svc = SourceService.get();
        if (svc == null) {
            if (fallback != null) fallback.run();
            return;
        }
        io.execute(() -> {
            JSONObject book = null;
            JSONObject ch = null;
            try {
                book = bookObj();
                String su = book == null ? "" : book.optString("sourceUrl", "");
                // 本地导入的书没有书源，走这条路只会白等一个超时
                if (!su.isEmpty() && !"local://".equals(su)) {
                    JSONArray chs = book.optJSONArray("chapters");
                    JSONObject c = chs == null ? null : chs.optJSONObject(idx);
                    if (c != null && !c.optString("url", "").isEmpty()) ch = c;
                }
            } catch (Throwable ignore) {
                /* 记录坏了就当没有书源 */
            }
            final JSONObject fBook = book;
            final JSONObject fCh = ch;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fCh == null) {
                    if (fallback != null) fallback.run();
                    return;
                }
                startSourceFetch(svc, fBook, fCh, idx);
            });
        });
    }

    private void startSourceFetch(SourceService svc, JSONObject book, JSONObject ch, int idx) {
        final int gen = chapterGen;
        loadingChapter = true;
        loading.setText(idx > index ? "加载下一章…" : "加载上一章…");
        loading.setVisibility(View.VISIBLE);
        root.removeCallbacks(loadTimeout);
        // 32s 太长了：用户等 10 秒就会认定「卡死」并开始反复点。给 12 秒，
        // 到点就明说「取章超时」，再点一次即可（新请求会顶掉这一次）。
        root.postDelayed(loadTimeout, 12000);

        svc.content(book, ch, 12000, new SourceService.Callback() {
            @Override
            public void onDone(JSONObject res) {
                if (gen != chapterGen) return; // 已被更晚的请求顶掉
                if (res == null || !res.optBoolean("ok", false)) {
                    final String err = res == null ? "取章失败" : res.optString("error", "取章失败");
                    runOnUiThread(() -> {
                        if (gen != chapterGen) return;
                        loadingChapter = false;
                        root.removeCallbacks(loadTimeout);
                        hideLoading();
                        toast(err);
                    });
                    return;
                }
                final String raw = res.optString("content", "");
                // 净化规则 + HTML 切块 + 写缓存全部在后台：大章节在主线程做这几步就是一次卡顿
                io.execute(() -> {
                    String name = purifyTitle(chapterNameOf(idx));
                    List<Paras.Block> bl = null;
                    try {
                        String html = purifyContent(raw);
                        bl = Paras.blocks(html, name);
                        // 顺手缓存：下次翻回来是纯本地 IO，也帮网页版省一次网络
                        if (html != null && !html.trim().isEmpty()) {
                            ModouStore.get(getApplicationContext()).cacheChapter(bookId, idx, html);
                        }
                        if (bl != null && !bl.isEmpty()) cachePut(idx, bl);
                    } catch (Throwable ignore) {
                        /* 解析失败当没取到 */
                    }
                    final List<Paras.Block> fBl = bl;
                    final String fName = name;
                    runOnUiThread(() -> {
                        if (gen != chapterGen) return;
                        if (isFinishing() || isDestroyed()) return;
                        loadingChapter = false;
                        root.removeCallbacks(loadTimeout);
                        hideLoading();
                        if (fBl == null || fBl.isEmpty()) {
                            toast("本章没有正文");
                            return;
                        }
                        applyChapter(idx, fName, fBl, takeJump());
                    });
                });
            }
        });
    }

    /**
     * 书籍完整记录（含 sourceUrl / chapters[].url，取章要用）；懒读一次并缓存。
     *
     * synchronized：目录与正文都在后台线程取，两处同时进来不能各解析一份。
     */
    private synchronized JSONObject bookObj() {
        if (bookCache != null) return bookCache;
        if (bookId == null || bookId.isEmpty()) return null;
        try {
            String s = ModouStore.get(this).get("books", bookId);
            bookCache = s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            bookCache = null;
        }
        return bookCache;
    }

    /** 目录里这一章的名字（顶部要显示，也用于去掉正文里重复的那个章名） */
    private String chapterNameOf(int idx) {
        if (titles != null && idx >= 0 && idx < titles.length) {
            return titles[idx] == null ? "" : titles[idx];
        }
        return "";
    }

    /* ---------------- 朗读（原生 TTS） ---------------- */

    private TTSController tts;
    private final List<String> ttsQueue = new ArrayList<>();
    private int ttsAt = -1;
    private double ttsRate = 1.0;
    private LinearLayout ttsBar;
    private TextView ttsInfo;
    private TextView ttsRateChip;      // 语速胶囊：显示当前值，点开语速设置
    private TextView ttsTimerChip;     // 定时胶囊：显示当前值，点开定时设置（可自由输入）
    private TextView ttsToggleBtn;     // 暂停/播放按钮
    private boolean ttsPaused;
    private int ttsTimerMin = 0;
    private Runnable ttsStopTask;
    /** 当前页码（onPageChanged 记录）：读完一页自动翻下一页续读 */
    private int ttsPage = -1, ttsPageTotal = -1;
    /** 本章读完自动进下一章继续读：章渲染完成后消费这个标记 */
    private boolean ttsAutoNext;
    /** 引擎还在初始化（state=init）时点的听书：ready 回调后自动开读 */
    private boolean ttsPendingStart;
    /** 听书会话是否进行中（暂停也算进行中）——决定朗读面板/底部小横条的显隐 */
    private boolean ttsSession;
    /** 正在朗读的那一页（与当前显示页区分开）：翻走后小横条给「从本页听 / 返回」 */
    private int ttsReadingPage = -1;
    /** 底部居中小横条：工具坞收起时贴页面最底部显示（对齐番茄阅读） */
    private LinearLayout ttsPill;
    private TextView ttsPillToggle;    // 当前页正在朗读：暂停播放 / 继续播放
    private TextView ttsPillBack;      // 当前页不是朗读页：返回正在朗读的那一页（只放图标）
    private View ttsPillDivider;
    private TextView ttsPillFromHere;  // 当前页不是朗读页：从本页开始读

    /**
     * 开始朗读：从当前页开头读起，一句一句喂给引擎。
     *
     * 之所以自己排队列而不是把整章丢给 TextToSpeech：
     * 一是长文本在部分引擎上会被截断，二是拿不到"读到哪了"，
     * 没法做停止后续读，也没法在界面上报进度。
     */
    private void startTts() {
        if (tts == null) {
            tts = new TTSController(this,
                    id -> runOnUiThread(this::ttsNext),
                    st -> runOnUiThread(() -> ttsOnState(st)));
        }
        if (!tts.available()) {
            // 引擎绑定是异步的（几百毫秒）：init 中点听书不能直接判死，
            // 等 ready 回调后自动开读 —— 否则冷启动第一次点听书必报「不可用(init)」
            if (TTSController.ST_INIT.equals(tts.state())) {
                ttsPendingStart = true;
                toast("正在启动语音引擎…");
                return;
            }
            toast("朗读不可用（" + tts.state() + "）");
            return;
        }
        ttsPendingStart = false;
        // 上次调过的语速与定时就是这次的默认值（用户要求：调完下次默认这个时长）
        try {
            ttsRate = Double.parseDouble(ModouStore.get(this).settingStr("ttsRate", "1.0"));
        } catch (Exception ignore) {
            ttsRate = 1.0;
        }
        ttsRate = Math.max(0.5, Math.min(2.5, ttsRate));
        try {
            ttsTimerMin = Integer.parseInt(ModouStore.get(this).settingStr("ttsTimerMin", "0"));
        } catch (Exception ignore) {
            ttsTimerMin = 0;
        }
        if (ttsTimerMin < 0) ttsTimerMin = 0;
        ttsPaused = false;
        fillTtsQueueFromPage();
        if (ttsQueue.isEmpty()) {
            toast("这一页没有可朗读的内容");
            return;
        }
        ttsSession = true;
        buildTtsBar();
        buildTtsPill();
        ttsAt = -1;
        ttsNext();
        if (ttsTimerMin > 0) applyTtsTimer();
        // 放在 ttsNext() 之后：ttsAt 这时才落到第一句，ttsActive() 为真，按钮才会亮
        refreshDockTts();
    }

    /** 把当前页文本切成句队列（开始朗读与读完一页自动续读共用） */
    private void fillTtsQueueFromPage() {
        ttsQueue.clear();
        ttsReadingPage = pageView.page();
        String s = String.valueOf(pageView.currentText() == null ? "" : pageView.currentText())
                .replace('\uFFFC', ' ');
        int from = pageView.pageStartOffset();
        if (from > 0 && from < s.length()) s = s.substring(from);
        for (String part : s.split("\n+")) {
            for (String sent : splitSentences(part)) {
                if (!sent.trim().isEmpty()) ttsQueue.add(sent.trim());
            }
        }
    }

    /** 按标点切句；超长的再按逗号切（引擎对单次文本长度有限制） */
    private static List<String> splitSentences(String s) {
        List<String> out = new ArrayList<>();
        for (String seg : String.valueOf(s).split("(?<=[。！？；!?…])")) {
            if (seg.length() <= 120) {
                out.add(seg);
                continue;
            }
            StringBuilder cur = new StringBuilder();
            for (String piece : seg.split("(?<=[，,、])")) {
                if (cur.length() + piece.length() > 120 && cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(piece);
            }
            if (cur.length() > 0) out.add(cur.toString());
        }
        return out;
    }

    private void ttsNext() {
        if (ttsAt + 1 >= ttsQueue.size()) {
            // 读完一页自动翻下一页继续（网页版修好的行为，原生补齐）
            if (ttsPage >= 0 && ttsPageTotal > 0 && ttsPage + 1 < ttsPageTotal) {
                pageView.gotoPage(ttsPage + 1);
                fillTtsQueueFromPage();
                ttsAt = -1;
                ttsNext();
                return;
            }
            // 本章读完 → 自动进下一章继续（章渲染完成后由 applyChapter 消费 ttsAutoNext）
            if (total > 0 && index + 1 < total) {
                ttsAutoNext = true;
                if (ttsInfo != null) ttsInfo.setText("下一章…");
                requestChapter(index + 1);
                return;
            }
            stopTts();
            ttsAt = -1;
            toast("朗读结束");
            return;
        }
        ttsAt++;
        if (ttsInfo != null) ttsInfo.setText("朗读中 " + (ttsAt + 1) + "/" + ttsQueue.size());
        tts.speak(ttsQueue.get(ttsAt), ttsRate, ttsAt);
    }

    private void stopTts() {
        cancelTtsTimer();
        if (tts != null) tts.stop();
        ttsPaused = false;
        ttsSession = false;
        ttsAt = -1;
        ttsQueue.clear();
        refreshDockTts();
    }

    /**
     * 听书开/停后即时刷新工具坞：按钮高亮（听书图标会变成强调色）+ 面板/小横条显隐。
     * 之前只刷了覆盖层，工具坞那排图标要等下次唤起菜单才重画 ——
     * 于是「退出听书了，听书按钮还亮着」。
     */
    private void refreshDockTts() {
        if (dock != null) paintDockRows();
        syncTtsOverlay();
    }

    /**
     * 朗读面板（只在工具坞展开时出现，浮在工具坞上方并留出间距，不再和菜单叠在一起）。
     * 视觉与工具坞同一套：同样的卡片底色、22dp 圆角、1dp 细描边、两排之间的分隔线，
     * 上排「进度 + 语速 + 定时 + 退出」，下排「上一句 / 暂停 / 下一句」。
     */
    private void buildTtsBar() {
        if (ttsBar != null) {
            syncTtsChips();
            return;
        }
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(theme.card);
        pg.setCornerRadius(dp(22));
        pg.setStroke(dp(1), ModouUi.mix(theme.card, theme.text, 0.12f));
        bar.setBackground(pg);
        bar.setPadding(dp(8), dp(2), dp(8), dp(2));
        bar.setClickable(true);

        /* 上排：进度 + 语速 + 定时 + 退出 */
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), 0, dp(2), 0);
        ttsInfo = ModouUi.tv(this, "准备朗读…", 12.5f, theme.text);
        ttsInfo.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(ttsInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        int chipPadH = dp(10), chipPadV = dp(5);
        ttsRateChip = chipChip("", chipPadH, chipPadV);
        ttsRateChip.setOnClickListener((v) -> openTtsRateSheet());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(8);
        top.addView(ttsRateChip, rlp);

        ttsTimerChip = chipChip("", chipPadH, chipPadV);
        ttsTimerChip.setOnClickListener((v) -> openTtsTimerSheet());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(6);
        top.addView(ttsTimerChip, tlp);

        TextView exit = ModouUi.tv(this, "退出", 12.5f, theme.sub);
        exit.setGravity(Gravity.CENTER);
        exit.setPadding(dp(10), 0, dp(6), 0);
        exit.setOnClickListener((v) -> {
            stopTts();
            ttsAt = -1;
        });
        top.addView(exit);
        bar.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        // 两排之间的分隔线（与工具坞同一个色值）
        View divider = new View(this);
        divider.setBackgroundColor(theme.dark ? 0x14FFFFFF : 0x0F2B251B);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dlp.leftMargin = dp(6);
        dlp.rightMargin = dp(6);
        bar.addView(divider, dlp);

        /* 下排：上一句 / 暂停 / 下一句（定高、整组居中） */
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);

        int btnPadH = dp(16);
        TextView prev = glyphText("上一句", GlyphIcon.PREV, theme.text, 14);
        prev.setPadding(btnPadH, 0, btnPadH, 0);
        prev.setOnClickListener((v) -> {
            if (ttsQueue.isEmpty()) return;
            ttsPaused = false;
            syncTtsToggle();
            // ttsNext 内部先自增：回退两句再 next = 上一句；已在第一句则重读本句
            ttsAt = Math.max(-1, ttsAt - 2);
            tts.speak(ttsQueue.get(ttsAt + 1), ttsRate, ttsAt + 1);
            ttsAt++;
            syncTtsChips();
        });
        ctl.addView(prev);

        ttsToggleBtn = ModouUi.tv(this, "暂停", 15, theme.acc);
        ttsToggleBtn.setGravity(Gravity.CENTER);
        ttsToggleBtn.setPadding(dp(28), 0, dp(28), 0);
        ttsToggleBtn.setOnClickListener((v) -> ttsTogglePlay());
        ctl.addView(ttsToggleBtn);

        TextView next = glyphText("下一句", GlyphIcon.NEXT, theme.text, 14);
        next.setPadding(btnPadH, 0, btnPadH, 0);
        next.setOnClickListener((v) -> {
            if (ttsQueue.isEmpty()) return;
            ttsPaused = false;
            syncTtsToggle();
            ttsNext(); // 内部自增后读下一句；末句则照旧走翻页/翻章续读
        });
        ctl.addView(next);
        bar.addView(ctl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.setMargins(dp(10), 0, dp(10), dp(12));
        root.addView(bar, lp);
        ttsBar = bar;
        syncTtsChips();
    }

    /** 面板里的小胶囊：透明底 + 1dp 细描边 + 强调色文字（与工具坞的描边语言一致） */
    private TextView chipChip(String text, int padH, int padV) {
        TextView v = ModouUi.tv(this, text, 13, theme.acc);
        v.setGravity(Gravity.CENTER);
        v.setPadding(padH, padV, padH, padV);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(13));
        g.setStroke(dp(1), ModouUi.mix(theme.card, theme.text, 0.16f));
        v.setBackground(g);
        return v;
    }

    /** 图标 + 文字的按钮（图标自绘，不依赖 emoji 字形） */
    private TextView glyphText(String text, int kind, int color, float sp) {
        TextView v = ModouUi.tv(this, text, sp, color);
        v.setGravity(Gravity.CENTER);
        v.setCompoundDrawablePadding(dp(6));
        v.setCompoundDrawablesWithIntrinsicBounds(new GlyphIcon(kind, color, dp(14)), null, null, null);
        return v;
    }

    /**
     * 自绘小图标：暂停 / 播放 / 上一句 / 下一句 / 返回 / 耳机。
     * 用 emoji 字形在不同 ROM 上会变成彩色方块或直接缺字，自己画最稳。
     */
    private static class GlyphIcon extends android.graphics.drawable.Drawable {
        static final int PAUSE = 0, PLAY = 1, PREV = 2, NEXT = 3, BACK = 4, EARPHONE = 5;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind, size;

        GlyphIcon(int kind, int color, int sizePx) {
            this.kind = kind;
            this.size = sizePx;
            p.setColor(color);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            p.setStyle(Paint.Style.FILL);
            switch (kind) {
                case PAUSE: {
                    float bw = w * 0.19f, bh = h * 0.60f, gap = w * 0.16f, r = bw / 2f;
                    c.drawRoundRect(new RectF(cx - gap / 2 - bw, cy - bh / 2, cx - gap / 2, cy + bh / 2), r, r, p);
                    c.drawRoundRect(new RectF(cx + gap / 2, cy - bh / 2, cx + gap / 2 + bw, cy + bh / 2), r, r, p);
                    break;
                }
                case PLAY: {
                    android.graphics.Path t = new android.graphics.Path();
                    float tw = w * 0.52f, th = h * 0.58f;
                    t.moveTo(cx - tw * 0.40f, cy - th / 2);
                    t.lineTo(cx + tw * 0.60f, cy);
                    t.lineTo(cx - tw * 0.40f, cy + th / 2);
                    t.close();
                    c.drawPath(t, p);
                    break;
                }
                case PREV:
                case NEXT: {
                    boolean left = kind == PREV;
                    float bh = h * 0.58f, bw = w * 0.12f;
                    float barX = left ? cx - w * 0.40f : cx + w * 0.40f - bw;
                    c.drawRoundRect(new RectF(barX, cy - bh / 2, barX + bw, cy + bh / 2), bw / 2, bw / 2, p);
                    android.graphics.Path t = new android.graphics.Path();
                    float tip = left ? cx - w * 0.24f : cx + w * 0.24f;
                    float base = left ? cx + w * 0.36f : cx - w * 0.36f;
                    t.moveTo(base, cy - bh / 2);
                    t.lineTo(tip, cy);
                    t.lineTo(base, cy + bh / 2);
                    t.close();
                    c.drawPath(t, p);
                    break;
                }
                case BACK: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(2f, w * 0.11f));
                    p.setStrokeCap(Paint.Cap.ROUND);
                    RectF arc = new RectF(cx - w * 0.30f, cy - h * 0.30f, cx + w * 0.30f, cy + h * 0.30f);
                    c.drawArc(arc, 200, 250, false, p);
                    p.setStyle(Paint.Style.FILL);
                    android.graphics.Path t = new android.graphics.Path();
                    t.moveTo(cx - w * 0.44f, cy - h * 0.06f);
                    t.lineTo(cx - w * 0.06f, cy - h * 0.30f);
                    t.lineTo(cx - w * 0.06f, cy + h * 0.16f);
                    t.close();
                    c.drawPath(t, p);
                    break;
                }
                case EARPHONE: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(2f, w * 0.11f));
                    p.setStrokeCap(Paint.Cap.ROUND);
                    RectF arc = new RectF(cx - w * 0.34f, cy - h * 0.40f, cx + w * 0.34f, cy + h * 0.12f);
                    c.drawArc(arc, 180, 180, false, p);
                    c.drawLine(cx - w * 0.34f, cy - h * 0.14f, cx - w * 0.34f, cy + h * 0.10f, p);
                    c.drawLine(cx + w * 0.34f, cy - h * 0.14f, cx + w * 0.34f, cy + h * 0.10f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(new RectF(cx - w * 0.44f, cy + h * 0.02f,
                            cx - w * 0.24f, cy + h * 0.34f), w * 0.08f, w * 0.08f, p);
                    c.drawRoundRect(new RectF(cx + w * 0.24f, cy + h * 0.02f,
                            cx + w * 0.44f, cy + h * 0.34f), w * 0.08f, w * 0.08f, p);
                    break;
                }
            }
        }
    }

    /**
     * 底部居中小横条（照番茄阅读那种）：页面最底部、左右页脚之间，深灰半透明小胶囊。
     * 工具坞收起时出现；当前页就是朗读页时给「暂停播放 / 继续播放」，
     * 翻走之后变成「返回（图标）｜耳机 从本页听」。
     */
    private void buildTtsPill() {
        if (ttsPill != null) return;
        final int fg = 0xFFE9E9E9;

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xE62E2E2E);
        g.setCornerRadius(dp(20));
        pill.setBackground(g);

        ttsPillBack = ModouUi.tv(this, "", 13.5f, fg);
        ttsPillBack.setGravity(Gravity.CENTER);
        ttsPillBack.setMinWidth(dp(46));
        ttsPillBack.setPadding(dp(14), 0, dp(14), 0);
        ttsPillBack.setCompoundDrawablesWithIntrinsicBounds(
                new GlyphIcon(GlyphIcon.BACK, fg, dp(15)), null, null, null);
        ttsPillBack.setOnClickListener((v) -> {
            if (ttsReadingPage >= 0) pageView.gotoPage(ttsReadingPage);
            syncTtsPill();
        });
        pill.addView(ttsPillBack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        ttsPillDivider = new View(this);
        ttsPillDivider.setBackgroundColor(0x33FFFFFF);
        pill.addView(ttsPillDivider, new LinearLayout.LayoutParams(
                Math.max(1, dp(1)), dp(15)));

        ttsPillFromHere = ModouUi.tv(this, "从本页听", 13.5f, fg);
        ttsPillFromHere.setGravity(Gravity.CENTER);
        ttsPillFromHere.setPadding(dp(14), 0, dp(18), 0);
        ttsPillFromHere.setCompoundDrawablePadding(dp(7));
        ttsPillFromHere.setCompoundDrawablesWithIntrinsicBounds(
                new GlyphIcon(GlyphIcon.EARPHONE, fg, dp(15)), null, null, null);
        ttsPillFromHere.setOnClickListener((v) -> {
            if (tts == null) return;
            ttsPaused = false;
            fillTtsQueueFromPage(); // 顺带把「正在朗读的页」记成当前页
            ttsAt = -1;
            ttsNext();
            syncTtsChips();
            syncTtsPill();
        });
        pill.addView(ttsPillFromHere, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        ttsPillToggle = ModouUi.tv(this, "暂停播放", 13.5f, fg);
        ttsPillToggle.setGravity(Gravity.CENTER);
        ttsPillToggle.setPadding(dp(18), 0, dp(20), 0);
        ttsPillToggle.setCompoundDrawablePadding(dp(7));
        ttsPillToggle.setCompoundDrawablesWithIntrinsicBounds(
                new GlyphIcon(GlyphIcon.PAUSE, fg, dp(15)), null, null, null);
        ttsPillToggle.setOnClickListener((v) -> {
            ttsTogglePlay();
            syncTtsPill();
        });
        pill.addView(ttsPillToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        // 底距 12dp（高 40dp → 横条顶在 52dp）：正文末行止于 44dp，两者不打架
        lp.bottomMargin = dp(12);
        pill.setVisibility(View.GONE);
        root.addView(pill, lp);
        ttsPill = pill;
    }

    /** 暂停 / 继续：面板与小横条共用 */
    private void ttsTogglePlay() {
        if (ttsQueue.isEmpty() || tts == null || ttsAt < 0) return;
        if (ttsPaused) {
            ttsPaused = false;
            tts.speak(ttsQueue.get(ttsAt), ttsRate, ttsAt);
        } else {
            ttsPaused = true;
            tts.stop(); // 只停当前句：ttsAt 不动，恢复时重读这一句
        }
        syncTtsToggle();
    }

    /**
     * 朗读 UI 三态同步：面板（工具坞展开时，浮在工具坞上方）↔ 底部小横条（工具坞收起时）
     * ↔ 全隐藏（没在朗读）。主题切换重建工具坞后，面板/小横条也会跟着重建。
     */
    private void syncTtsOverlay() {
        if (ttsSession) {
            if (ttsBar == null) buildTtsBar();
            if (ttsPill == null) buildTtsPill();
        }
        boolean menu = dockShowing;
        if (ttsBar != null) ttsBar.setVisibility(ttsSession && menu ? View.VISIBLE : View.GONE);
        syncTtsPill();
        if (ttsBar != null && ttsSession && menu) {
            // 工具坞高度（自动阅读条显隐、两行图标）要等一次布局才拿得到
            ttsBar.post(this::syncTtsOverlayMargin);
        }
    }

    /** 面板抬到工具坞上方并留一段间距 */
    private void syncTtsOverlayMargin() {
        if (ttsBar == null) return;
        int want = ModouUi.dp(this, 12);
        if (dockShowing && dock != null && dock.getMeasuredHeight() > 0) {
            want = dock.getMeasuredHeight() + ModouUi.dp(this, 10);
        }
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) ttsBar.getLayoutParams();
        if (lp != null && lp.bottomMargin != want) {
            lp.bottomMargin = want;
            ttsBar.setLayoutParams(lp);
        }
    }

    /** 小横条内容：同页 → 暂停/继续；翻走了 → 返回 / 从本页听 */
    private void syncTtsPill() {
        if (ttsPill == null) return;
        if (!ttsSession || dockShowing) {
            ttsPill.setVisibility(View.GONE);
            return;
        }
        ttsPill.setVisibility(View.VISIBLE);
        boolean same = ttsReadingPage < 0 || ttsPage < 0 || ttsReadingPage == ttsPage;
        ttsPillToggle.setVisibility(same ? View.VISIBLE : View.GONE);
        ttsPillBack.setVisibility(same ? View.GONE : View.VISIBLE);
        ttsPillDivider.setVisibility(same ? View.GONE : View.VISIBLE);
        ttsPillFromHere.setVisibility(same ? View.GONE : View.VISIBLE);
        ttsPillToggle.setText(ttsPaused ? "▶ 继续播放" : "⏸ 暂停播放");
    }

    /** 语速/定时胶囊与暂停按钮的文字刷新 */
    private void syncTtsChips() {
        if (ttsRateChip != null) ttsRateChip.setText(String.format(java.util.Locale.ROOT, "%.1fx", ttsRate));
        if (ttsTimerChip != null) ttsTimerChip.setText(ttsTimerMin > 0 ? "定时 " + ttsTimerMin + " 分" : "定时 关");
        syncTtsToggle();
    }

    private void syncTtsToggle() {
        if (ttsToggleBtn != null) ttsToggleBtn.setText(ttsPaused ? "继续" : "暂停");
        if (ttsPillToggle != null) {
            ttsPillToggle.setText(ttsPaused ? "继续播放" : "暂停播放");
            ttsPillToggle.setCompoundDrawablesWithIntrinsicBounds(
                    new GlyphIcon(ttsPaused ? GlyphIcon.PLAY : GlyphIcon.PAUSE, 0xFFE9E9E9, dp(15)),
                    null, null, null);
        }
    }

    /** 语速设置：常用档位分段选择，选定即生效并落库 */
    private void openTtsRateSheet() {
        ModouUi u = ModouUi.of(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(this, 14);
        box.setPadding(p, ModouUi.dp(this, 8), p, 0);
        box.addView(ModouUi.tv(this, "点一句试听，下一句起按新语速读", 12, u.sub));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ModouUi.dp(this, 10), 0, 0);
        double[] rates = {0.8, 1.0, 1.2, 1.5, 2.0};
        for (double r : rates) {
            TextView b = ModouUi.tv(this, String.format(java.util.Locale.ROOT, "%.1fx", r), 13.5f,
                    Math.abs(ttsRate - r) < 0.05 ? 0xFFFFFFFF : u.text);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(Math.abs(ttsRate - r) < 0.05 ? u.accent : u.bg);
            g.setCornerRadius(ModouUi.dp(this, 8));
            b.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(ModouUi.dp(this, 3), 0, ModouUi.dp(this, 3), 0);
            b.setOnClickListener((v) -> {
                ttsRate = r;
                ModouStore.get(this).putSetting("ttsRate", String.valueOf(r));
                syncTtsChips();
                v.postDelayed(() -> closeCurrentSheet(), 120);
            });
            row.addView(b, lp);
        }
        box.addView(row);
        showTtsSheet("语速", box);
    }

    /**
     * 定时设置：拨齿轮横向滑动选 1~60 分钟、步进 1 分钟（用户要求：像拨齿轮一样左右拨），
     * 松手即生效并落库，下次打开听书默认沿用这个时长；「关」单独一个按钮。
     */
    private void openTtsTimerSheet() {
        ModouUi u = ModouUi.of(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(this, 14);
        box.setPadding(p, ModouUi.dp(this, 6), p, 0);

        /* 上排：关 + 当前/已选时长 */
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView off = ModouUi.tv(this, "关", 13.5f, ttsTimerMin == 0 ? 0xFFFFFFFF : u.text);
        off.setGravity(Gravity.CENTER);
        off.setPadding(ModouUi.dp(this, 18), ModouUi.dp(this, 8),
                ModouUi.dp(this, 18), ModouUi.dp(this, 8));
        android.graphics.drawable.GradientDrawable og = new android.graphics.drawable.GradientDrawable();
        og.setColor(ttsTimerMin == 0 ? u.accent : u.bg);
        og.setCornerRadius(ModouUi.dp(this, 8));
        off.setBackground(og);
        off.setOnClickListener((v) -> {
            setTtsTimer(0);
            v.postDelayed(() -> closeCurrentSheet(), 120);
        });
        row.addView(off);

        TextView cur = ModouUi.tv(this, "", 12.5f, u.sub);
        cur.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        cur.setText(ttsTimerMin > 0 ? "当前 " + ttsTimerMin + " 分钟" : "定时关闭");
        row.addView(cur, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);

        TtsTimerRuler ruler = new TtsTimerRuler(this);
        ruler.setValue(ttsTimerMin > 0 ? ttsTimerMin : 15);
        Runnable showSel = () -> cur.setText("已选 " + Math.round(ruler.getValue()) + " 分钟");
        ruler.onChange = showSel;
        ruler.onSnap = () -> setTtsTimer(Math.round(ruler.getValue()));
        box.addView(ruler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(this, 92)));

        TextView tip = ModouUi.tv(this, "左右滑动，1~60 分钟，每次 1 分钟", 12, u.sub);
        tip.setPadding(0, ModouUi.dp(this, 2), 0, ModouUi.dp(this, 6));
        box.addView(tip);
        showTtsSheet("定时停止", box);
    }

    /** 拨齿轮：横向标尺选分钟（1~60、步进 1），左右滑动，松手吸附并回调 */
    private class TtsTimerRuler extends View {
        private static final int MINV = 1, MAXV = 60;
        private float value = 15;
        private float downX, downValue;
        private final Paint pTick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pNum = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pInd = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int colText, colSub, colAccent;
        Runnable onChange, onSnap;

        TtsTimerRuler(Context c) {
            super(c);
            setClickable(true);
            ModouUi u = ModouUi.of(c);
            colText = u.text;
            colSub = u.sub;
            colAccent = u.accent;
            pTick.setStrokeWidth(ModouUi.dp(c, 1.6f));
            pNum.setColor(colSub);
            pNum.setTextAlign(Paint.Align.CENTER);
            pNum.setTextSize(ModouUi.dp(c, 12.5f));
            pCur.setColor(colAccent);
            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setTextSize(ModouUi.dp(c, 24));
            pCur.setFakeBoldText(true);
            pInd.setColor(colAccent);
            pInd.setStrokeWidth(ModouUi.dp(c, 2.5f));
            pInd.setStrokeCap(Paint.Cap.ROUND);
        }

        float getValue() {
            return value;
        }

        void setValue(int v) {
            value = Math.max(MINV, Math.min(MAXV, v));
        }

        @Override
        protected void onMeasure(int wm, int hm) {
            setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), wm),
                    View.MeasureSpec.getSize(hm));
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int cx = w / 2;
            int per = ModouUi.dp(getContext(), 24);
            int tickTop = ModouUi.dp(getContext(), 46);
            int topTextY = ModouUi.dp(getContext(), 27);

            /* 上头：当前拨到的分钟数（拖动中实时变） */
            String s = String.valueOf(Math.round(value));
            c.drawText(s, cx, topTextY, pCur);
            float half = pCur.measureText(s) / 2f;
            pNum.setColor(colSub);
            c.drawText("分钟", cx + half + ModouUi.dp(getContext(), 9), topTextY, pNum);

            /* 刻度：每分钟一格，5 的倍数加长并标数字 */
            float span = cx / (float) per + 1;
            int m0 = Math.max(MINV, (int) Math.ceil(value - span));
            int m1 = Math.min(MAXV, (int) Math.floor(value + span));
            pTick.setColor((colSub & 0x00FFFFFF) | 0x66000000);
            for (int m = m0; m <= m1; m++) {
                float x = cx + (m - value) * per;
                boolean major = m % 5 == 0;
                pTick.setColor(major ? colText : ((colSub & 0x00FFFFFF) | 0x66000000));
                float len = ModouUi.dp(getContext(), major ? 16 : 9);
                c.drawLine(x, tickTop, x, tickTop + len, pTick);
                if (major) {
                    pNum.setColor(colSub);
                    c.drawText(String.valueOf(m), x, tickTop + ModouUi.dp(getContext(), 30), pNum);
                }
            }

            /* 中心指针：固定不动，拨的是下面的刻度 */
            c.drawLine(cx, tickTop - ModouUi.dp(getContext(), 8),
                    cx, tickTop + ModouUi.dp(getContext(), 22), pInd);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int per = ModouUi.dp(getContext(), 24);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downValue = value;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    value = Math.max(MINV, Math.min(MAXV, downValue - (ev.getX() - downX) / per));
                    invalidate();
                    if (onChange != null) onChange.run();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float v = Math.max(MINV, Math.min(MAXV, Math.round(value)));
                    if (Math.abs(v - value) > 0.01f) {
                        value = v;
                        invalidate();
                    }
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (onSnap != null) onSnap.run();
                    return true;
                }
            }
            return super.onTouchEvent(ev);
        }
    }

    /** 设定定时并落库（0 = 关）。听书进行中即重新计时 */
    private void setTtsTimer(int min) {
        ttsTimerMin = min;
        ModouStore.get(this).putSetting("ttsTimerMin", String.valueOf(min));
        applyTtsTimer();
        syncTtsChips();
    }

    /** 按当前 ttsTimerMin 挂定时任务（重复调用先撤旧的，不会叠加） */
    private void applyTtsTimer() {
        cancelTtsTimer();
        if (ttsTimerMin <= 0) return;
        final int ms = ttsTimerMin * 60 * 1000;
        ttsStopTask = () -> {
            stopTts();
            ttsAt = -1;
            toast("朗读已到定时停止");
        };
        root.postDelayed(ttsStopTask, ms);
    }

    /** 听书设置面板的窗口引用（设置面板里选完即收） */
    private android.app.Dialog ttsSheet;

    private void showTtsSheet(String title, LinearLayout box) {
        ttsSheet = ModouUi.sheet(this, title, box);
    }

    private void closeCurrentSheet() {
        if (ttsSheet != null) {
            ttsSheet.dismiss();
            ttsSheet = null;
        }
    }

    private void ttsOnState(String st) {
        if (TTSController.ST_READY.equals(st) && ttsPendingStart) {
            ttsPendingStart = false;
            startTts();
            return;
        }
        if (ttsInfo == null) return;
        if (TTSController.ST_NO_ENGINE.equals(st)) ttsInfo.setText("没有可用的语音引擎");
        else if (TTSController.ST_NO_DATA.equals(st)) ttsInfo.setText("缺少中文语音数据");
    }

    private void cancelTtsTimer() {
        if (ttsStopTask != null) {
            root.removeCallbacks(ttsStopTask);
            ttsStopTask = null;
        }
    }

    /* ---------------- 净化规则 ---------------- */

    /** 规则懒读一次：整章应用几十条规则，每次翻页都重读 settings 没必要 */
    private List<Rules.Rule> ruleCache;

    private List<Rules.Rule> rules() {
        if (ruleCache == null) {
            ruleCache = Rules.parse(ModouStore.get(this).settingRaw("replaceRules"));
        }
        return ruleCache;
    }

    private String purifyContent(String html) {
        return html == null ? "" : Rules.applyContent(html, rules());
    }

    private String purifyTitle(String t) {
        return t == null ? "" : Rules.applyTitle(t, rules());
    }

    /** 取走待跳转位置并清零：不清理的话下一次翻页会莫名跳回书签处 */
    private int takeJump() {
        int v = pendingJump;
        pendingJump = 0;
        return v;
    }

    /**
     * 章已经落到屏幕上了：加载态与超时一起收掉。
     *
     * 以前只在「读库」「书源」两条路径里各自清一次，内存命中那条不清：
     * 上一次请求留下的 12 秒超时到点后会弹一句「章节加载超时」——
     * 明明正文已经在屏幕上了。集中到这里，任何路径出章都不会再误报。
     */
    private void clearLoadingState() {
        loadingChapter = false;
        root.removeCallbacks(loadTimeout);
        hideLoading();
    }

    /** 把章落到屏幕上（读库命中 / 书源服务 / JS 回传三条路径共用） */
    private void applyChapter(int idx, String title, String body, int offset) {
        clearLoadingState();
        index = idx;
        curBlocks = null;
        String[] fixed = fixTextHeading(purifyTitle(title == null ? "" : title.trim()),
                body == null ? "" : body);
        chapterTitle = fixed[0];
        // 标题与正文一起给：只排一次版（以前是两次，等于一章白排一遍）
        pageView.setChapter(fixed[1], fixed[0], offset);
        updateHeader();
        saveProgress(false);
        refreshDockNav();
        ttsContinueIfPending();
        // 目录等这一章真的排到屏幕上之后再补：整本 json（几千章）的解析
        // 不该压进「进书第一屏」这条路径
        pageView.post(this::loadTocAsync);
    }

    /** 听书跨章续读：新章渲染完成后接着读 */
    private void ttsContinueIfPending() {
        if (!ttsAutoNext) return;
        ttsAutoNext = false;
        pageView.post(this::startTts);
    }

    /**
     * 纯文字章的章名矫正（冷启动与换章共用，避免两处逻辑漂移）。
     * 返回 {章名, 去掉标题行后的正文}。
     */
    private static String[] fixTextHeading(String title, String body) {
        String t = title == null ? "" : title.trim();
        String b = body == null ? "" : body;
        String first = firstLineOf(b);
        int tk = titleLineIndexOfText(b, 5);
        if (tk >= 0) {
            String[] ls = b.split("\n", -1);
            String ti = ls[tk].trim();
            int from = tk;
            // 标题行前面那一小句「正文开头」（目录名从这儿来的）一并删掉
            for (int i = tk - 1; i >= 0; i--) {
                String v = ls[i].trim();
                if (v.isEmpty()) continue;
                if (normEq(v, t) || t.startsWith(v)) from = i;
                else break;
            }
            StringBuilder nb = new StringBuilder();
            for (int i = from + 1; i < ls.length; i++) {
                if (nb.length() > 0) nb.append('\n');
                nb.append(ls[i]);
            }
            return new String[]{ti, nb.toString()};
        }
        if (!first.isEmpty() && normEq(first, t)) {
            return new String[]{t, dropFirstLine(b)};
        }
        if (titleIsBodyText(b, t)) {
            // 目录把正文首段当成了章名：这个名字是错的，不能拿去当标题
            return new String[]{"", b};
        }
        return new String[]{t, b};
    }

    /** 图文混排版本：本地导入的 EPUB 与在线书源的插图都走这里 */
    private void applyChapter(int idx, String title, List<Paras.Block> blocks, int offset) {
        clearLoadingState();
        index = idx;
        Heading h = fixHeading(purifyTitle(title == null ? "" : title.trim()), blocks);
        curBlocks = h.blocks;
        chapterTitle = h.title;
        pageView.setBlocks(h.blocks, h.title, offset);
        updateHeader();
        saveProgress(false);
        refreshDockNav();
        ttsContinueIfPending();
        // 读书是线性的：这一章出来了，下一章大概率马上要用，趁现在后台备好
        prefetchChapter(idx + 1);
        // 同上：目录稍后补（首章出屏优先）
        pageView.post(this::loadTocAsync);
    }

    /** 矫正后的章标题 + 去掉重复标题行后的块序列 */
    private static final class Heading {
        String title;
        List<Paras.Block> blocks;
    }

    /**
     * 目录章名不可靠时的统一矫正。阅读页与正文搜索必须走同一个函数，
     * 否则搜索算出的字符偏移与屏幕上排出来的文本对不上，跳转会整体前移。
     */
    private static Heading fixHeading(String title, List<Paras.Block> blocks) {
        Heading h = new Heading();
        h.blocks = blocks;
        String t = title == null ? "" : title.trim();
        String first = firstTextOf(blocks);
        int k = firstTextIndex(blocks);
        // 正文前几段里找真正的标题行。EPUB 常见两种毛病：
        //   ① 目录/`<title>` 取到的是一小句正文（「叶凡虽然谈不上……但因为一些」）；
        //   ② 真正的章名「第三章 今昔」排在正文里，只是位置不在第一段。
        // 只认第一段会漏掉 ②，只比长度会留下 ① —— 所以这里扫前几段，找到就用它。
        int tk = titleLineIndex(blocks, 5);
        if (tk >= 0) {
            String ti = blocks.get(tk).value.trim();
            // 标题行前面若还压着一小句「正文开头」（正是目录名来源），一起删掉
            int from = tk;
            for (int i = tk - 1; i >= 0; i--) {
                Paras.Block b = blocks.get(i);
                if (b == null) continue;
                if (!b.isImage() && (normEq(b.value, t) || t.startsWith(b.value.trim()))) from = i;
                else break;
            }
            t = ti;
            h.blocks = new ArrayList<>(blocks.subList(from + 1, blocks.size()));
        } else if (k >= 0 && !first.isEmpty() && normEq(first, t)) {
            // 目录名就是正文第一段：留着当章名，把它从正文里去掉
            h.blocks = new ArrayList<>(blocks.subList(k + 1, blocks.size()));
        } else if (titleIsBodyText(blocksFlatText(blocks), t)) {
            // 目录把这个「章名」当成正文首段抓了下来 —— 它是正文，不是标题
            t = "";
        }
        h.title = t;
        return h;
    }

    /** 前 n 个文本块里第一个像标题的行（第X章 / 序章 / 番外…），找不到 -1 */
    private static int titleLineIndex(List<Paras.Block> bl, int n) {
        if (bl == null) return -1;
        int seen = 0;
        for (int i = 0; i < bl.size() && seen < n; i++) {
            Paras.Block b = bl.get(i);
            if (b == null || b.isImage() || b.value == null || b.value.trim().isEmpty()) continue;
            seen++;
            if (isTitleLine(b.value)) return i;
        }
        return -1;
    }

    /** 章名比较：忽略缩进与空白（正文里的标题行常带全角缩进） */
    private static boolean normEq(String a, String b) {
        return a != null && b != null
                && a.replace("\u3000", "").replace(" ", "").trim()
                        .equals(b.replace("\u3000", "").replace(" ", "").trim());
    }

    /* ---------------- 章标题矫正 ----------------
     * 目录不可靠：有的书源规则抓错，把「正文首段」当成了章名；也有的书正文里自己就带一行
     * 「第三章 今昔」。前者会让页眉显示一段正文，后者会让标题在屏幕上出现两次。
     * 这里统一按「正文实际长什么样」反推标题，两条路径（纯文字 / 图文）规则一致。
     */

    /** 正文首行是不是一个标题行：短、无句读、带「第X章 / 序章 / 番外」这类前缀 */
    private static boolean isTitleLine(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty() || t.length() > 30) return false;
        if (t.contains("。") || t.contains("，") || t.contains("！") || t.contains("？")
                || t.contains("“") || t.contains("”") || t.contains("…")) return false;
        return t.matches("^第[0-9零一二三四五六七八九十百千万]+[章节回卷篇].*")
                || t.matches("^(序章|序言|序|楔子|引子|尾声|后记|番外).*");
    }

    /** 这个「章名」其实是正文开头的一段（只比开头十几个字，且限定在正文前 300 字内） */
    private static boolean titleIsBodyText(String body, String title) {
        if (body == null || title == null) return false;
        String b = body.replace("\n", "").replace("\u3000", "").trim();
        String t = title.replace("\u3000", "").trim();
        if (t.length() < 6) return false;
        int n = Math.min(12, t.length());
        int at = b.indexOf(t.substring(0, n));
        return at >= 0 && at < 300;
    }

    /** 前 n 个非空行里第一个像标题的行号（原样下标），找不到 -1 */
    private static int titleLineIndexOfText(String body, int n) {
        if (body == null) return -1;
        String[] ls = body.split("\n", -1);
        int seen = 0;
        for (int i = 0; i < ls.length && seen < n; i++) {
            String v = ls[i].trim();
            if (v.isEmpty()) continue;
            seen++;
            if (isTitleLine(v)) return i;
        }
        return -1;
    }

    /** 跳过开头空行后的第一行有效内容（正文常以空行/缩进开头） */
    private static String firstLineOf(String body) {
        if (body == null) return "";
        String[] ls = body.split("\n", -1);
        for (String ln : ls) {
            String t = ln.trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    /** 删掉第一行有效内容，连同它前面那些空行 */
    private static String dropFirstLine(String body) {
        if (body == null) return "";
        String[] ls = body.split("\n", -1);
        int k = 0;
        while (k < ls.length && ls[k].trim().isEmpty()) k++;
        if (k >= ls.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = k + 1; i < ls.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(ls[i]);
        }
        return sb.toString();
    }

    /** 第一个非空文本块的序号（插图/空段不算），没有则 -1 */
    private static int firstTextIndex(List<Paras.Block> bl) {
        if (bl == null) return -1;
        for (int i = 0; i < bl.size(); i++) {
            Paras.Block b = bl.get(i);
            if (b != null && !b.isImage() && b.value != null && !b.value.trim().isEmpty()) return i;
        }
        return -1;
    }

    private static String firstTextOf(List<Paras.Block> bl) {
        int i = firstTextIndex(bl);
        return i < 0 ? "" : bl.get(i).value.trim();
    }

    /** 前若干段的纯文本（只用来判断章名是不是正文，不参与排版） */
    private static String blocksFlatText(List<Paras.Block> bl) {
        if (bl == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Paras.Block b : bl) {
            if (sb.length() > 400) break;
            if (b != null && !b.isImage() && b.value != null) sb.append(b.value);
        }
        return sb.toString();
    }

    private void onChapterJson(String json) {
        loadingChapter = false;
        root.removeCallbacks(loadTimeout);
        hideLoading();
        JSONObject o = parse(json);
        String err = o.optString("error", "");
        if (!err.isEmpty()) {
            ttsAutoNext = false;
            toast(err);
            return;
        }
        int idx = o.optInt("index", index);
        String title = o.optString("title", chapterTitle);
        String body = parasToText(o.optJSONArray("paras"));
        if (body.trim().isEmpty()) {
            ttsAutoNext = false;
            toast("本章没有正文");
            return;
        }
        applyChapter(idx, title, body, takeJump());
    }

    private void updateHeader() {
        String t = chapterTitle == null ? "" : chapterTitle.trim();
        if (t.isEmpty()) t = bookTitle;
        header.setText(bookTitle == null ? "" : bookTitle);
        headerSub.setText(t);
        // 页眉页脚要跟着换章：章名变了，页眉文案与「剩余章数」都要重算
        syncHeadFoot();
    }

    private void hideLoading() {
        loading.setVisibility(View.GONE);
    }

    /* ---------------- 进度 ---------------- */

    /**
     * 保存进度：翻页频繁，写入节流 1s；但位置没变就不写。
     *
     * 直接落共享存储层（SQLite），不再把持久化寄托在 JS 上——桥断了、网页侧被回收了，
     * 进度照样存得住（这是「阅读页直接读库」最实在的收益）。
     * 仍然通知网页侧一次：它要靠这个同步「最后读到哪一章」，退出时才知道要不要重渲染。
     */
    private void saveProgress(boolean force) {
        int off = pageView.pageStartOffset();
        long now = System.currentTimeMillis();
        if (!force && off == lastSavedOffset) return; // 位置没变：不写
        if (!force && now - lastSaveAt < 1000) return; // 连续翻页：节流 1s，退出时会强制写
        lastSavedOffset = off;
        lastSaveAt = now;
        long addMs = lastTickAt > 0 ? now - lastTickAt : 0;
        lastTickAt = now;
        ModouStore.get(this).putProgress(bookId, index, off, addMs);
        if (sBridge != null) {
            sBridge.evalJs("window.__modouNativeProgress && window.__modouNativeProgress(" + index + ","
                    + off + ")");
        }
    }

    /* ---------------- NativePageView.Host ---------------- */

    @Override
    public void onPageChanged(int page, int pageTotal) {
        ttsPage = page;
        ttsPageTotal = pageTotal;
        syncTtsPill(); // 翻页后可能就不再是「正在朗读的那一页」了
        // 工具坞上的「第几章 / 第几页」跟着走：翻页、拖滑杆、换章都要实时变，
        // 不能等下次唤起菜单才更新（用户点着滑杆看到文案不动会以为卡住了）
        updateDockProgress(false);
        saveProgress(false);
        String t = chapterTitle == null ? "" : chapterTitle.trim();
        if (t.isEmpty()) t = bookTitle;
        header.setText(bookTitle == null ? "" : bookTitle);
        headerSub.setText(t + "  ·  " + (page + 1) + "/" + Math.max(1, pageTotal));
    }

    @Override
    public void onNeedChapter(int delta) {
        if (delta < 0) {
            // 从本章首页往回翻：上一章要落在**末页**（跟网页版一致），
            // 而不是从上一章第一页读起 —— 传一个超出全文的偏移，分页时会钳到最后一页
            requestChapter(index - 1, Integer.MAX_VALUE);
            return;
        }
        requestChapter(index + delta);
    }

    @Override
    public void onSelRect(RectF r) {
        if (r == null) {
            selBar.setVisibility(View.GONE);
            return;
        }
        selBar.setVisibility(View.VISIBLE);
        selBar.post(() -> placeSelBar(r));
    }

    @Override
    public void onSelText(String text) {
        /* 工具条按钮不依赖文本内容；保留回调以便后续加「搜索」时直接用 */
    }

    @Override
    public void onSelectionClosed() {
        selBar.setVisibility(View.GONE);
    }

    @Override
    public void onTapCenter() {
        showMenu();
    }

    private void placeSelBar(RectF r) {
        // 工具条刚 addView 时可能还没走过布局：先自测一遍，别把位置算成 0 丢到左上角
        selBar.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = selBar.getMeasuredWidth();
        int bh = selBar.getMeasuredHeight();
        if (bw <= 0 || bh <= 0) return;
        int w = root.getWidth();
        int h = root.getHeight();
        float x = r.centerX() - bw / 2f;
        x = Math.max(dp(8), Math.min(x, w - bw - dp(8)));
        float y = r.top - bh - dp(10);
        if (y < statusBarHeight() + dp(4)) {
            y = Math.min(r.bottom + dp(10), h - bh - dp(8));
        }
        selBar.setX(x);
        selBar.setY(y);
    }

    /* ---------------- 菜单 / 目录 ---------------- */

    /**
     * 工具坞显隐（轻点正文中部 / 顶部标题）。
     * 网页版是常驻的底部卡片，这里沿用同一套视觉：圆角卡片 + 章节进度 + 两行图标按钮，
     * 而不是系统默认的 AlertDialog 列表。
     */
    private void showMenu() {
        if (pageView.hasSelection()) {
            pageView.clearSel();
            return;
        }
        if (dockShowing) hideDock();
        else showDock();
    }

    /* ---------------- 底部工具坞（对齐网页版 .rd-dock） ---------------- */

    /** 构建工具坞：进度（章节 + 页）+ 两行图标按钮，完全代码构建、跟随阅读主题配色 */
    private void buildDock() {
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setVisibility(View.GONE);

        dockPanel = new LinearLayout(this);
        dockPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(theme.card);
        pg.setCornerRadius(dp(22));
        // 网页版是 `0 8px 28px rgba(62,52,25,.18)` 的向下投影；Android 的 elevation 却是
        // 四周一圈模糊光圈，在纸色底上圆角处会脏成一圈灰边。这里改成「无阴影 + 1dp 细描边」，
        // 卡片与纸面靠一条极淡的轮廓分开，更接近网页版的观感。
        pg.setStroke(dp(1), ModouUi.mix(theme.card, theme.text, 0.12f));
        dockPanel.setBackground(pg);
        dockPanel.setPadding(dp(8), dp(6), dp(8), dp(6));
        dockPanel.setClickable(true); // 吸收触摸，避免穿透到正文翻页

        // 自动阅读状态条（运行时出现）
        dockAuto = buildDockAutoBar();
        dockAuto.setVisibility(View.GONE);
        dockPanel.addView(dockAuto);

        // 章节 / 页码
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(dp(6), dp(4), dp(6), dp(7));
        dockMetaL = new TextView(this);
        dockMetaL.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dockMetaL.setTextColor(theme.sub);
        dockMetaR = new TextView(this);
        dockMetaR.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dockMetaR.setTextColor(theme.sub);
        dockMetaR.setGravity(Gravity.END);
        meta.addView(dockMetaL, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        meta.addView(dockMetaR);
        dockPanel.addView(meta);

        // 进度条：视觉 4dp、触摸 26dp（与网页版一致）
        dockSeek = buildDockSeek();
        dockPanel.addView(dockSeek);

        // 第一行图标
        dockRow1 = new LinearLayout(this);
        dockRow1.setOrientation(LinearLayout.HORIZONTAL);
        dockPanel.addView(dockRow1);

        // 第二行上方分隔线（网页版 rgba(43,37,27,.06)）
        dockDivider = new View(this);
        dockDivider.setBackgroundColor(theme.dark ? 0x14FFFFFF : 0x0F2B251B);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        llp.topMargin = dp(2);
        dockPanel.addView(dockDivider, llp);

        dockRow2 = new LinearLayout(this);
        dockRow2.setOrientation(LinearLayout.HORIZONTAL);
        dockPanel.addView(dockRow2);

        dock.addView(dockPanel);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        // 内容区本身就排在导航栏之上（没有 LAYOUT_HIDE_NAVIGATION），这里不能再加一次导航栏高度，
        // 否则工具坞会被顶起大半屏高的一条空白，露出窗口底色看着像「白条」。
        // 10dp 那版在手势导航机上实测距屏幕底 26dp（导航栏 16dp + 10dp），像浮在半空；
        // 收到 2dp，与左右各 10dp 的边距凑成同一套呼吸感。
        lp.bottomMargin = dp(2);
        root.addView(dock, lp);

        paintDockRows();
    }

    /* ---------------- 图标排布（网页版 settings.readerMenuLayout） ---------------- */

    /** 图标注册表：key 固定，顺序由用户排布决定 */
    private static final String[] MENU_KEYS = {
            "toc", "bmk", "find", "auto", "tts", "purify",
            "prev", "next", "typo", "night", "more"};
    private static final String[] MENU_DEF_R1 = {"toc", "bmk", "find", "auto", "tts", "purify"};
    private static final String[] MENU_DEF_R2 = {"prev", "next", "typo", "night", "more"};

    private static String menuLabel(String key) {
        switch (key) {
            case "toc": return "目录";
            case "bmk": return "书签";
            case "find": return "搜索";
            case "auto": return "自动";
            case "tts": return "听书";
            case "purify": return "净化";
            case "prev": return "上一章";
            case "next": return "下一章";
            case "typo": return "排版";
            case "night": return "夜间";
            case "more": return "更多";
            default: return key;
        }
    }

    private static boolean menuKeyValid(String k) {
        for (String x : MENU_KEYS) if (x.equals(k)) return true;
        return false;
    }

    /** 读排布：坏 key / 重复 key 滤掉，新增功能补进第一行（与网页版 normMenuCfg 同语义） */
    private void loadMenuCfg() {
        menuR1.clear();
        menuR2.clear();
        menuHidden.clear();
        JSONObject o = null;
        try {
            String raw = ModouStore.get(this).settingRaw("readerMenuLayout");
            if (raw != null && !raw.isEmpty()) o = new JSONObject(raw);
        } catch (Exception e) {
            /* 脏数据：走默认 */
        }
        if (o != null) {
            fillKeys(menuR1, o.optJSONArray("r1"));
            fillKeys(menuR2, o.optJSONArray("r2"));
            fillKeys(menuHidden, o.optJSONArray("hidden"));
        }
        if (menuR1.isEmpty() && menuR2.isEmpty() && menuHidden.isEmpty()) {
            for (String k : MENU_DEF_R1) menuR1.add(k);
            for (String k : MENU_DEF_R2) menuR2.add(k);
        }
        normMenuCfg();
    }

    private static void fillKeys(java.util.List<String> out, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            String k = a.optString(i, "");
            if (menuKeyValid(k) && !out.contains(k)) out.add(k);
        }
    }

    private void normMenuCfg() {
        java.util.List<String> r1 = new java.util.ArrayList<>();
        java.util.List<String> r2 = new java.util.ArrayList<>();
        java.util.List<String> hid = new java.util.ArrayList<>();
        for (String k : menuR1) if (menuKeyValid(k) && !r1.contains(k) && !r2.contains(k)) r1.add(k);
        for (String k : menuR2) if (menuKeyValid(k) && !r1.contains(k) && !r2.contains(k)) r2.add(k);
        for (String k : menuHidden) if (menuKeyValid(k) && !r1.contains(k) && !r2.contains(k) && !hid.contains(k)) hid.add(k);
        for (String k : MENU_KEYS) if (!r1.contains(k) && !r2.contains(k) && !hid.contains(k)) r1.add(k);
        menuR1.clear();
        menuR1.addAll(r1);
        menuR2.clear();
        menuR2.addAll(r2);
        menuHidden.clear();
        menuHidden.addAll(hid);
    }

    private void saveMenuCfg() {
        try {
            JSONObject o = new JSONObject();
            o.put("r1", new JSONArray(menuR1));
            o.put("r2", new JSONArray(menuR2));
            o.put("hidden", new JSONArray(menuHidden));
            ModouStore.get(this).putSetting("readerMenuLayout", o.toString());
        } catch (Exception e) {
            /* 写失败不打断交互 */
        }
    }

    /** 按当前排布重建两行图标（隐藏项不画；整行空了连分隔线一起藏） */
    private void paintDockRows() {
        if (dockRow1 == null) return;
        dockRow1.removeAllViews();
        for (String k : menuR1) dockRow1.addView(dockButton(k));
        dockRow2.removeAllViews();
        for (String k : menuR2) dockRow2.addView(dockButton(k));
        dockRow1.setVisibility(menuR1.isEmpty() ? View.GONE : View.VISIBLE);
        dockRow2.setVisibility(menuR2.isEmpty() ? View.GONE : View.VISIBLE);
        if (dockDivider != null) {
            dockDivider.setVisibility(menuR1.isEmpty() || menuR2.isEmpty() ? View.GONE : View.VISIBLE);
        }
        // 注意：不能在这里回头调 refreshDockAuto()——它是「改状态 → 重画」的入口，
        // 而重画又会走到这里，两个方法会互相递归把栈打穿。状态的同步就地写清楚。
        if (dockAuto != null) dockAuto.setVisibility(autoOn ? View.VISIBLE : View.GONE);
        if (autoOn && dockAutoSpeed != null) dockAutoSpeed.setText((autoInterval / 1000) + "s");
    }

    /**
     * 换章后就地更新「上一章 / 下一章」的可用状态。
     *
     * 只改这两个按钮，不重建整行：重建会把正在被按住的那个视图换掉，
     * 连点时第二下容易落空；而且整行重画会让工具坞闪一下。
     */
    private void refreshDockNav() {
        if (dockPrev != null) {
            boolean off = index <= 0;
            dockPrev.setAlpha(off ? 0.3f : 1f);
            dockPrev.setOnClickListener(off ? null : v -> requestChapter(index - 1));
        }
        if (dockNext != null) {
            boolean off = total <= 0 || index >= total - 1;
            dockNext.setAlpha(off ? 0.3f : 1f);
            dockNext.setOnClickListener(off ? null : v -> requestChapter(index + 1));
        }
        // 换章了：章号、页数、书签刻度全要换。新章此刻还在排版（页数没算完），
        // 排到下一帧再刷一遍，才能拿到正确的总页数。
        if (dock != null) dock.post(() -> updateDockProgress(true));
    }

    /** 一个按钮：key → 图标 / 文案 / 当前状态 / 动作（顺序变了不用改这里） */
    private View dockButton(String key) {
        switch (key) {
            case "toc":
                return dockAct(R.drawable.ic_rd_toc, "目录", false, false, () -> {
                    hideDock();
                    showToc();
                });
            case "bmk":
                return dockAct(R.drawable.ic_rd_bmk, "书签", false, false, () -> {
                    // 与网页版一致：点一下就在当前位置加书签；书签列表在目录抽屉的「书签」页签里
                    hideDock();
                    addBookmark();
                });
            case "find":
                return dockAct(R.drawable.ic_rd_find, "搜索", false, false, () -> {
                    hideDock();
                    showSearch("");
                });
            case "auto":
                return dockAct(R.drawable.ic_rd_auto, "自动", autoOn, false, this::toggleAuto);
            case "tts":
                return dockAct(R.drawable.ic_rd_tts, "听书", ttsActive(), false, () -> {
                    hideDock();
                    startTts();
                });
            case "purify":
                return dockAct(R.drawable.ic_rd_purify, "净化", false, false, () -> {
                    hideDock();
                    RulesActivity.launch(this);
                });
            case "prev": {
                // 不收起工具坞：连读时经常要连点几下「下一章」，收一次就得重新唤起，
                // 而且再点开时手指还得重新找位置。状态由 refreshDockNav 就地更新。
                View v = dockAct(R.drawable.ic_rd_prev, "上一章", false, index <= 0,
                        () -> requestChapter(index - 1));
                dockPrev = v;
                return v;
            }
            case "next": {
                View v = dockAct(R.drawable.ic_rd_next, "下一章", false,
                        total <= 0 || index >= total - 1, () -> requestChapter(index + 1));
                dockNext = v;
                return v;
            }
            case "typo":
                return dockAa("排版", () -> {
                    hideDock();
                    showTypoPanel();
                });
            case "night":
                return dockAct(R.drawable.ic_rd_night, darkMode ? "日间" : "夜间", darkMode, false,
                        () -> applyTheme(darkMode ? lightTheme : "dark"));
            case "more":
            default:
                return dockAct(R.drawable.ic_rd_more, "更多", false, false, () -> {
                    hideDock();
                    openMoreSheet();
                });
        }
    }

    /**
     * 图标排布面板：第一行 / 第二行 / 已隐藏三块。
     * 每一项给「上移 / 下移 / 换行 / 隐藏」，改一次存一次、立刻重画工具坞。
     * 与网页版「更多 → 图标排布」完全对应。
     */
    private void showMenuLayout() {
        ScrollView sc = new ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.66f)));
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        sc.addView(box);
        fillMenuLayout(box);
        showSheet("图标排布", sc);
    }

    private void fillMenuLayout(LinearLayout box) {
        box.removeAllViews();
        box.addView(mlSection("第一行"));
        if (menuR1.isEmpty()) box.addView(mlBlank("（空）"));
        for (int i = 0; i < menuR1.size(); i++) box.addView(mlRow(menuR1.get(i), menuR1, i));
        box.addView(mlSection("第二行"));
        if (menuR2.isEmpty()) box.addView(mlBlank("（空）"));
        for (int i = 0; i < menuR2.size(); i++) box.addView(mlRow(menuR2.get(i), menuR2, i));
        box.addView(mlSection("已隐藏"));
        if (menuHidden.isEmpty()) box.addView(mlBlank("（无）"));
        for (String k : new java.util.ArrayList<>(menuHidden)) box.addView(mlHiddenRow(k));

        TextView reset = new TextView(this);
        reset.setText("恢复默认排布");
        reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        reset.setTextColor(theme.acc);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, dp(14), 0, dp(10));
        reset.setOnClickListener(v -> {
            menuR1.clear();
            for (String k : MENU_DEF_R1) menuR1.add(k);
            menuR2.clear();
            for (String k : MENU_DEF_R2) menuR2.add(k);
            menuHidden.clear();
            saveMenuCfg();
            paintDockRows();
            fillMenuLayout(box);
        });
        box.addView(reset);
    }

    /** 改完排布的统一收尾：存盘 → 重画工具坞 → 重画面板 */
    private void menuCfgChanged(LinearLayout box) {
        normMenuCfg();
        saveMenuCfg();
        paintDockRows();
        fillMenuLayout(box);
    }

    private TextView mlSection(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        v.setTextColor(ModouUi.alpha(theme.sub, 0xB0));
        v.setPadding(dp(4), dp(12), dp(4), dp(4));
        return v;
    }

    private TextView mlBlank(String t) {
        return ModouUi.tv(this, t, 12.5f, ModouUi.alpha(theme.sub, 0x99));
    }

    /** 「Aa」没有矢量图标，用文字画；其余用工具坞同一个图标，一眼认出挪的是哪一个 */
    private View mlIcon(String key, int color) {
        if ("typo".equals(key)) {
            TextView aa = new TextView(this);
            aa.setText("Aa");
            aa.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            aa.setTypeface(Typeface.SERIF, Typeface.BOLD);
            aa.setTextColor(color);
            aa.setGravity(Gravity.CENTER);
            aa.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
            return aa;
        }
        ImageView iv = new ImageView(this);
        int ic = menuIcon(key);
        if (ic != 0) iv.setImageDrawable(tinted(ic, color));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
        return iv;
    }

    private LinearLayout mlRow(String key, java.util.List<String> row, int i) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(4), dp(2), dp(4));

        r.addView(mlIcon(key, theme.sub));

        TextView name = ModouUi.tv(this, menuLabel(key), 14, theme.text);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(6);
        r.addView(name, nlp);

        r.addView(mlMini("↑", i > 0, () -> {
            java.util.Collections.swap(row, i, i - 1);
            menuCfgChanged((LinearLayout) r.getParent());
        }));
        r.addView(mlMini("↓", i < row.size() - 1, () -> {
            java.util.Collections.swap(row, i, i + 1);
            menuCfgChanged((LinearLayout) r.getParent());
        }));
        r.addView(mlMini("⇄", true, () -> {
            java.util.List<String> other = (row == menuR1) ? menuR2 : menuR1;
            row.remove(i);
            other.add(key);
            menuCfgChanged((LinearLayout) r.getParent());
        }));
        r.addView(mlMini("隐", true, () -> {
            row.remove(i);
            menuHidden.add(key);
            menuCfgChanged((LinearLayout) r.getParent());
        }));
        return r;
    }

    private LinearLayout mlHiddenRow(String key) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(4), dp(2), dp(4));
        r.addView(mlIcon(key, ModouUi.alpha(theme.sub, 0x88)));
        TextView name = ModouUi.tv(this, menuLabel(key), 14, ModouUi.alpha(theme.text, 0x99));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(6);
        r.addView(name, nlp);

        TextView show = new TextView(this);
        show.setText("显示");
        show.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        show.setTextColor(theme.acc);
        show.setGravity(Gravity.CENTER);
        show.setPadding(dp(10), dp(6), dp(10), dp(6));
        show.setBackground(ModouUi.round(ModouUi.alpha(theme.acc, 0x14), 9));
        show.setOnClickListener(v -> {
            menuHidden.remove(key);
            menuR1.add(key);
            menuCfgChanged((LinearLayout) r.getParent());
        });
        r.addView(show);
        return r;
    }

    /** 排布面板里的小圆钮：32dp 触摸区，禁用时降到 0.25 透明 */
    private TextView mlMini(String label, boolean enabled, Runnable r) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTextColor(theme.sub);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ModouUi.alpha(theme.sub, 0x12));
        bg.setCornerRadius(dp(9));
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.leftMargin = dp(6);
        v.setLayoutParams(lp);
        if (enabled) v.setOnClickListener(x -> r.run());
        else v.setAlpha(0.25f);
        return v;
    }

    private static int menuIcon(String key) {
        switch (key) {
            case "toc": return R.drawable.ic_rd_toc;
            case "bmk": return R.drawable.ic_rd_bmk;
            case "find": return R.drawable.ic_rd_find;
            case "auto": return R.drawable.ic_rd_auto;
            case "tts": return R.drawable.ic_rd_tts;
            case "purify": return R.drawable.ic_rd_purify;
            case "prev": return R.drawable.ic_rd_prev;
            case "next": return R.drawable.ic_rd_next;
            case "night": return R.drawable.ic_rd_night;
            case "more": return R.drawable.ic_rd_more;
            default: return 0;
        }
    }

    /**
     * 一个图标按钮：20dp 图标 + 10.5sp 文字，纵向居中。
     * on=true 用强调色 + 淡底（对应网页版 .rd-act.on）；disabled 时降到 0.3 透明。
     */
    private View dockAct(int iconRes, String label, boolean on, boolean disabled, Runnable r) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(6), dp(2), dp(6));
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView iv = new ImageView(this);
        int s = dp(20);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setImageDrawable(tinted(iconRes, on ? theme.acc : theme.sub));
        b.addView(iv);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setTextColor(on ? theme.acc : theme.sub);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);
        b.addView(t);

        if (on) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(ModouUi.alpha(theme.acc, 0x1A)); // 约 .10 的淡底
            bg.setCornerRadius(dp(14));
            b.setBackground(bg);
        }
        if (disabled) b.setAlpha(0.3f);
        else b.setOnClickListener(v -> r.run());
        return b;
    }

    /** 「Aa」排版按钮：用文字而不是图标，和网页版一致（20dp 盒子、衬线加粗） */
    private View dockAa(String label, Runnable r) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(6), dp(2), dp(6));
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView aa = new TextView(this);
        aa.setText("Aa");
        aa.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        aa.setTypeface(Typeface.SERIF, Typeface.BOLD);
        aa.setTextColor(theme.sub);
        aa.setGravity(Gravity.CENTER);
        aa.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        b.addView(aa);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setTextColor(theme.sub);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);
        b.addView(t);

        b.setOnClickListener(v -> r.run());
        return b;
    }

    /** 进度条：细轨 + 强调色填充 + 圆点；拖动即按页跳转 */
    private SeekBar buildDockSeek() {
        SeekBar sb = new SeekBar(this);
        sb.setMinimumHeight(dp(26));
        sb.setPadding(dp(7), 0, dp(7), 0);
        sb.setBackground(null);
        sb.setSplitTrack(false);
        // 用和排版滑杆同一套自绘：4dp 圆角轨道垂直居中，圆点 13dp 白底 + 2dp 强调色描边。
        // 之前用 LayerDrawable + setLayerHeight(4dp)，高度设了但没居中，轨道会贴在顶部。
        try {
            sb.setProgressDrawable(dockTrack());
            sb.setThumb(dockThumb());
            sb.setThumbOffset(0);
        } catch (Throwable ignored) {
            /* 旧 API 退回系统外观 */
        }
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || seekSuppress) return;
                pageView.gotoPage(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seekTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekTracking = false;
                updateDockProgress();
                saveProgress(true);
            }
        });
        return sb;
    }

    /**
     * 工具坞进度轨道：与排版滑杆同一条 4dp 细轨，另外把本章书签画成小刻度点。
     *
     * 刻度点直接画在轨道上（不是另起一条），所以读进度时一眼能看出「这一章还有几处
     * 记过的地方、分别在多后面」——对应网页版进度条上的 .bmk-tick。
     */
    private Drawable dockTrack() {
        final int trackBg = theme.dark ? 0x2BFFFFFF : 0x292B251B;
        final int trackOn = theme.acc;
        final int tickColor = theme.dark ? 0xFFF0EDE4 : 0xFFFFFFFF;
        final int tickRing = theme.acc;
        final float h = ModouUi.dp(this, 4);
        final float r = h / 2f;
        final float dot = ModouUi.dp(this, 2.4f);
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                float cy = b.centerY();
                p.setStyle(Paint.Style.FILL);
                p.setColor(trackBg);
                c.drawRoundRect(new RectF(b.left, cy - h / 2f, b.right, cy + h / 2f), r, r, p);
                float w = b.width() * (getLevel() / 10000f);
                if (w > 0.5f) {
                    p.setColor(trackOn);
                    c.drawRoundRect(new RectF(b.left, cy - h / 2f, b.left + w, cy + h / 2f), r, r, p);
                }
                // 书签刻度：白点 + 强调色细边，压在已读/未读两段上都看得见
                int total = Math.max(2, dockSeek != null ? dockSeek.getMax() + 1 : 2);
                for (int i = 0; i < dockTicks.size(); i++) {
                    int pg = dockTicks.get(i);
                    float x = b.left + b.width() * ((pg + 0.5f) / total);
                    p.setColor(tickRing);
                    c.drawCircle(x, cy, dot + ModouUi.dp(NativeReaderActivity.this, 1f), p);
                    p.setColor(tickColor);
                    c.drawCircle(x, cy, dot, p);
                }
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /** 工具坞进度圆点：白底 + 描边（网页版 .rd-knob，与排版滑杆的橙点刚好相反） */
    private Drawable dockThumb() {
        final float size = dp(13);
        final int fill = theme.dark ? 0xFFF0EDE4 : 0xFFFFFFFF;
        final int ring = theme.acc;
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c) {
                float cx = getBounds().centerX();
                float cy = getBounds().centerY();
                float rad = size / 2f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(fill);
                c.drawCircle(cx, cy, rad - dp(1), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(ring);
                c.drawCircle(cx, cy, rad - dp(1), p);
            }

            @Override
            public int getIntrinsicWidth() {
                return (int) size;
            }

            @Override
            public int getIntrinsicHeight() {
                return (int) size;
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    private Drawable tinted(int resId, int color) {
        Drawable d = getResources().getDrawable(resId, getTheme()).mutate();
        d.setTint(color);
        return d;
    }

    /** 听书是否正在进行（有排队且未读完） */
    private boolean ttsActive() {
        return ttsAt >= 0 && ttsAt < ttsQueue.size();
    }

    private void updateDockProgress() {
        updateDockProgress(true);
    }

    /**
     * 刷新工具坞的「第几章 / 第几页」与滑杆位置。
     *
     * withTicks=false 用于翻页途中：书签刻度要读库、解析 JSON，每翻一页都做一遍没必要，
     * 只在开面板、换章时重算。拖动滑杆时不去 setProgress，否则手指还按着就被改写，
     * 滑杆会跟手指打架。
     */
    private void updateDockProgress(boolean withTicks) {
        // buildDock 是先 new 出 dock、再逐个建子视图的：中途被 onPageChanged 撞上时
        // dock 已非空但滑杆还没建，所以三个都要判
        if (dock == null || dockSeek == null || dockMetaL == null || dockMetaR == null) return;
        int pages = Math.max(0, pageView.pageCount());
        int p = Math.max(0, pageView.page());
        int max = Math.max(1, pages - 1);
        if (!seekTracking && dockSeek.getMax() != max) {
            seekSuppress = true;
            dockSeek.setMax(max);
            seekSuppress = false;
        }
        if (!seekTracking) {
            int want = Math.min(p, max);
            if (dockSeek.getProgress() != want) {
                seekSuppress = true;
                dockSeek.setProgress(want);
                seekSuppress = false;
            }
            if (dockSeek.isEnabled() != (pages > 1)) dockSeek.setEnabled(pages > 1);
        }
        // 文本相同就别再 setText：滚动模式下 onPageChanged 来得很密，
        // 每帧都触发一次 measure/layout 会让滑动发涩
        String ml = "第 " + (index + 1) + " / " + Math.max(1, total) + " 章";
        if (!ml.contentEquals(dockMetaL.getText())) dockMetaL.setText(ml);
        String mr = pages > 1 ? "第 " + (p + 1) + " / " + pages + " 页"
                : (pages == 1 ? "第 1 页" : "—");
        if (!mr.contentEquals(dockMetaR.getText())) dockMetaR.setText(mr);
        if (withTicks) refreshDockTicks(pages);
        dockAuto.setVisibility(autoOn ? View.VISIBLE : View.GONE);
        if (autoOn && dockAutoSpeed != null) dockAutoSpeed.setText((autoInterval / 1000) + "s");
    }

    /** 读本章书签，换算成页码后给进度条画刻度点 */
    private void refreshDockTicks(int pages) {
        dockTicks.clear();
        if (pages <= 1 || bookId == null || bookId.isEmpty()) return;
        try {
            String raw = ModouStore.get(this).all("bookmarks");
            if (raw == null || raw.isEmpty()) return;
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (!bookId.equals(o.optString("bookId", ""))) continue;
                if (o.optInt("chapterIndex", -1) != index) continue;
                int pg = pageView.pageOfOffset(o.optInt("offset", 0));
                if (pg >= 0 && pg < pages && !dockTicks.contains(pg)) dockTicks.add(pg);
            }
            java.util.Collections.sort(dockTicks);
        } catch (Exception e) {
            /* 脏数据跳过 */
        }
    }

    private void showDock() {
        if (dock == null) return;
        dockShowing = true;
        updateDockProgress();
        paintDockRows();
        dock.setVisibility(View.VISIBLE);
        dock.setAlpha(0f);
        dock.setTranslationY(dp(40));
        dock.animate().alpha(1f).translationY(0f).setDuration(220).start();
        showHead(true);
        syncTtsOverlay(); // 朗读面板跟着工具坞出现（浮在它上方），小横条收起
    }

    private void hideDock() {
        if (dock == null || !dockShowing) return;
        dockShowing = false;
        syncTtsOverlay(); // 朗读面板收起，底部小横条顶上
        dock.animate().alpha(0f).translationY(dp(40)).setDuration(160)
                .withEndAction(() -> {
                    if (!dockShowing && dock != null) dock.setVisibility(View.GONE);
                }).start();
        showHead(false);
    }

    /** 顶部栏显隐：与工具坞同步（网页版 .chrome-hidden 会一起收起来） */
    private void showHead(boolean show) {
        if (head == null) return;
        headShowing = show;
        head.animate().cancel();
        if (show) {
            head.setVisibility(View.VISIBLE);
            head.animate().alpha(1f).translationY(0f).setDuration(200).start();
        } else {
            head.animate().alpha(0f).translationY(-(dp(46) + statusBarHeight()))
                    .setDuration(180)
                    .withEndAction(() -> {
                        if (!headShowing && head != null) head.setVisibility(View.GONE);
                    }).start();
        }
    }

    /** 夜间/纸色切换后重建工具坞：面板底色、进度条、按钮配色都要跟着换 */
    private void refreshDockTheme() {
        if (dock == null) return;
        boolean was = dockShowing;
        root.removeView(dock);
        dock = null;
        dockShowing = false;
        // 朗读面板/小横条也按旧主题画的（底色、文字色都变了）：一起拆掉，下次用到自动重建
        if (ttsBar != null) {
            root.removeView(ttsBar);
            ttsBar = null;
        }
        if (ttsPill != null) {
            root.removeView(ttsPill);
            ttsPill = null;
            ttsPillToggle = null;
            ttsPillBack = null;
            ttsPillFromHere = null;
            ttsPillDivider = null;
        }
        buildDock();
        syncTtsOverlay();
        if (was) showDock();
    }

    /** 自动阅读状态条：自动阅读 / 间隔 / 慢 / 快 / 停止（对齐网页版 .rd-auto） */
    private LinearLayout buildDockAutoBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(4), dp(6), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ModouUi.alpha(theme.text, 0x0D));
        bg.setCornerRadius(dp(999));
        row.setBackground(bg);

        TextView lab = new TextView(this);
        lab.setText("自动阅读");
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lab.setTextColor(theme.sub);
        row.addView(lab);

        dockAutoSpeed = new TextView(this);
        dockAutoSpeed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dockAutoSpeed.setTextColor(theme.text);
        dockAutoSpeed.setPadding(dp(6), 0, 0, 0);
        dockAutoSpeed.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(dockAutoSpeed);

        row.addView(dockMini("慢", () -> {
            autoInterval = Math.min(15000, autoInterval + 1000);
            if (dockAutoSpeed != null) dockAutoSpeed.setText((autoInterval / 1000) + "s");
            if (autoOn) {
                autoHandler.removeCallbacks(autoTick);
                autoHandler.postDelayed(autoTick, autoInterval);
            }
        }));
        row.addView(dockMini("快", () -> {
            autoInterval = Math.max(2000, autoInterval - 1000);
            if (dockAutoSpeed != null) dockAutoSpeed.setText((autoInterval / 1000) + "s");
            if (autoOn) {
                autoHandler.removeCallbacks(autoTick);
                autoHandler.postDelayed(autoTick, autoInterval);
            }
        }));
        row.addView(dockMiniStop("停止", () -> {
            stopAuto();
            updateDockProgress();
            paintDockRows();
        }));
        return row;
    }

    private TextView dockMini(String label, Runnable r) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setTextColor(theme.sub);
        t.setPadding(dp(11), dp(5), dp(11), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.dark ? 0x14FFFFFF : 0x0F2B251B);
        bg.setCornerRadius(dp(999));
        t.setBackground(bg);
        t.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView dockMiniStop(String label, Runnable r) {
        TextView t = dockMini(label, r);
        t.setTextColor(theme.acc);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ModouUi.alpha(theme.acc, 0x1A));
        bg.setCornerRadius(dp(999));
        t.setBackground(bg);
        return t;
    }

    /* ---------------- 底部抽屉（更多 / 排版） ---------------- */

    /** 通用底部抽屉：遮罩 + 圆角面板 + 标题行；点遮罩或 ✕ 关闭 */
    private void showSheet(String title, View content) {
        closeSheet();
        FrameLayout wrap = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> closeSheet());
        wrap.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(theme.card);
        g.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        panel.setBackground(g);
        panel.setPadding(dp(14), dp(8), dp(14), dp(14));
        panel.setClickable(true);

        View handle = new View(this);
        GradientDrawable hg = new GradientDrawable();
        hg.setColor(theme.dark ? 0x33FFFFFF : 0x1F2B251B);
        hg.setCornerRadius(dp(999));
        handle.setBackground(hg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(2);
        hlp.bottomMargin = dp(8);
        panel.addView(handle, hlp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), 0, 0, dp(6));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(theme.text);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = new TextView(this);
        x.setText("✕");
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        x.setTextColor(theme.sub);
        x.setPadding(dp(10), dp(2), dp(6), dp(2));
        x.setOnClickListener(v -> closeSheet());
        head.addView(x);
        panel.addView(head);

        panel.addView(content);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.BOTTOM;
        wrap.addView(panel, plp);

        root.addView(wrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = wrap;

        if (animGrade == 0) {
            // 关闭动效：直接到位，不做任何动画（低配设备 / 用户明确关闭）
            wrap.setAlpha(1f);
            panel.setTranslationY(0f);
        } else if (animGrade == 1) {
            // 简约：只淡入，不上滑
            wrap.setAlpha(0f);
            panel.setTranslationY(0f);
            wrap.animate().alpha(1f).setDuration(140).setInterpolator(MD3_DECEL).start();
        } else {
            // MD3 标准：遮罩 180ms decelerate + 抽屉 260ms emphasized
            panel.setElevation(dp(16));
            panel.setTranslationY(dp(60));
            wrap.setAlpha(0f);
            wrap.animate().alpha(1f).setDuration(180).setInterpolator(MD3_DECEL).start();
            panel.animate().translationY(0f).setDuration(260).setInterpolator(MD3_EMPH).start();
        }
    }

    private void closeSheet() {
        typoShowing = false;
        if (overlay == null) return;
        final View o = overlay;
        if (animGrade == 0) {
            overlay = null;
            root.removeView(o);
            return;
        }
        // 退出用 emphasized-accelerate（200ms）：先淡出再移除，避免生硬消失
        overlay = null;
        o.animate().alpha(0f).setDuration(animGrade == 2 ? 200 : 120)
                .setInterpolator(animGrade == 2 ? MD3_EMPH : MD3_DECEL)
                .withEndAction(() -> {
                    if (o.getParent() != null) root.removeView(o);
                }).start();
    }

    /** 更多：加入书架 / 书籍详情 / 重新加载本章 / 设置 / 回到本章开头 / 退出原生阅读 */
    private void openMoreSheet() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // 搜书点进来还没入书架的书（preview），第一个动作应该是「加入书架」
        JSONObject cur = bookObj();
        if (cur != null && cur.optBoolean("preview", false)) {
            list.addView(sheetItem("加入书架", () -> {
                closeSheet();
                addToShelf();
            }));
        }
        list.addView(sheetItem("书籍详情", () -> {
            closeSheet();
            BookActivity.launch(this, bookId);
        }));
        list.addView(sheetItem("书签", () -> {
            closeSheet();
            new TocSheet(1);
        }));
        list.addView(sheetItem("重新加载本章", () -> {
            closeSheet();
            ModouStore.get(this).del("chapters", ModouStore.chapterKey(bookId, index));
            requestChapter(index);
            toast("已重新加载本章");
        }));
        // 编辑本章：只对本地 TXT 开放（EPUB 是 HTML+插图，改纯文本会破坏结构）
        JSONObject kb = bookObj();
        if (kb != null && "TXT".equalsIgnoreCase(kb.optString("kind", ""))) {
            list.addView(sheetItem("编辑本章", () -> {
                closeSheet();
                editChapter();
            }));
        }
        list.addView(sheetItem("图标排布", () -> {
            closeSheet();
            showMenuLayout();
        }));
        list.addView(sheetItem("设置", () -> {
            closeSheet();
            SettingsActivity.launch(this);
        }));
        list.addView(sheetItem("回到本章开头", () -> {
            closeSheet();
            pageView.gotoPage(0);
        }));
        list.addView(sheetItem("退出阅读", () -> {
            closeSheet();
            onBackPressed();
        }));
        showSheet(bookTitle == null || bookTitle.isEmpty() ? "更多" : bookTitle, list);
    }

    /**
     * 试读转正式：去掉 preview 标记，书架列表就会显示它。
     * 与详情页「加入书架」同一套数据动作（改的是同一条 books 记录），
     * 区别只是这里不用离开阅读页 —— 读到一半才发现忘了加书架的情况更常见。
     */
    /**
     * 编辑本章（本地 TXT 书）：把章文本改写进书库缓存。
     *
     * 「对本地文件编辑」的边界要说明清楚：导入时正文已预写进书库（SQLite），
     * 阅读器读的也是书库——改书库即永久生效；手机上的 .txt 原文件不动
     * （导入时没有保留可回写的文件句柄，硬写有损坏原文件的风险）。
     */
    private void editChapter() {
        String html = ModouStore.get(this).chapterContent(bookId, index);
        String name = chapterNameOf(index);
        StringBuilder before = new StringBuilder();
        if (html != null && !html.trim().isEmpty()) {
            for (Paras.Block b : Paras.blocks(html, name)) {
                if (b.isImage()) continue;
                if (before.length() > 0) before.append('\n');
                before.append(b.value);
            }
        }
        if (before.length() == 0) {
            toast("本章没有可编辑的文本");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(this, 14);
        box.setPadding(p, ModouUi.dp(this, 8), p, 0);
        EditText ed = new EditText(this);
        ed.setText(before.toString());
        ed.setTextSize(14);
        ed.setTextColor(theme.text);
        ed.setBackgroundColor(0x00000000);
        ed.setGravity(Gravity.TOP);
        box.addView(ed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.42f)));
        TextView note = ModouUi.tv(this,
                "每行一段，保存后从本章开头重新显示。修改写进书库、阅读立即生效，手机上的原文件不会改动。",
                11.5f, theme.sub);
        note.setPadding(0, ModouUi.dp(this, 6), 0, 0);
        box.addView(note);
        LinearLayout editFooter = new LinearLayout(this);
        editFooter.setOrientation(LinearLayout.VERTICAL);
        final Dialog d = ModouUi.sheet(this, "编辑 · " + name, box, editFooter);
        editFooter.addView(ModouUi.sheetButtons(this, d, "取消", "保存", null, () -> {
            String t = String.valueOf(ed.getText());
            if (t.contentEquals(before)) {
                d.dismiss();
                return;
            }
            StringBuilder h = new StringBuilder();
            for (String line : t.split("\n", -1)) {
                String s = line.replace("\u3000", "").trim();
                if (s.isEmpty()) continue;
                if (h.length() > 0) h.append('\n');
                h.append("<p>").append(escapeHtml(s)).append("</p>");
            }
            if (h.length() == 0) {
                toast("内容为空，未保存");
                return;
            }
            ModouStore.get(this).cacheChapter(bookId, index, h.toString());
            d.dismiss();
            requestChapter(index, 0); // 分段变了，旧偏移不可靠，回本章开头
            toast("已保存，本章已按修改重新显示");
        }));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void addToShelf() {
        JSONObject b = bookObj();
        if (b == null) {
            toast("书籍记录不存在");
            return;
        }
        try {
            b.put("preview", false);
            b.put("addedAt", System.currentTimeMillis());
            b.put("updatedAt", System.currentTimeMillis());
            ModouStore.get(this).put("books", b.toString());
            toast("已加入书架");
        } catch (Exception e) {
            toast("加入书架失败：" + e.getMessage());
        }
    }

    private TextView sheetItem(String label, Runnable r) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(theme.text);
        t.setPadding(dp(6), dp(14), dp(6), dp(14));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    /* ---------------- 排版面板 ---------------- */

    /** 排版面板：字号 / 字重 / 行距 / 段距 / 页边距 / 常亮 / 主题 / 字体 / 翻页方式 / 页眉页脚 */
    private void showTypoPanel() {
        ScrollView sc = new ScrollView(this);
        // 面板高度上限：内容再长也不顶到屏幕外，超出部分可滚动
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.68f);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH));
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, ModouUi.dp(this, 2), 0, ModouUi.dp(this, 6));

        // 字号
        box.addView(sizeRow("字号", fontSize));
        box.addView(div());

        // 字重
        box.addView(segRow("字重", new String[]{"细", "常规", "中粗", "粗"},
                weightIndex(weightVal), i -> {
                    weightVal = new int[]{300, 400, 500, 600}[i];
                    applyStyleNow();
                }));
        box.addView(div());

        // 行距 1.4–2.6（滑杆内部做防抖重排，松手才落库）
        box.addView(seekRow("行距", 14, 26, 1, Math.round(lineHeight * 10),
                v -> (v / 10f) + " 倍", v -> lineHeight = v / 10f));
        box.addView(div());

        // 段距 0–2
        box.addView(seekRow("段距", 0, 20, 1, Math.round(paraGap * 10),
                v -> v == 0 ? "无" : (v / 10f) + " 倍", v -> paraGap = v / 10f));
        box.addView(div());

        // 页边距 12–44
        box.addView(seekRow("页边距", 8, 36, 2, marginPx,
                v -> v + " px", v -> marginPx = v));
        box.addView(div());

        // 屏幕常亮
        box.addView(toggleRow("屏幕常亮", keepAwake, on -> {
            keepAwake = on;
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }));
        box.addView(div());

        // 主题（四档）：segRow 回调给的是序号，转成主题名再套用
        box.addView(segRow("主题", new String[]{"纸白", "米黄", "青石", "夜间"},
                themeIndex(), i -> applyTheme(new String[]{"paper", "cream", "gray", "dark"}[i])));
        box.addView(div());

        // 字体
        box.addView(segRow("字体", new String[]{"宋体", "黑体", "楷体"},
                fontIndex(font), i -> {
                    font = new String[]{"song", "hei", "kai"}[i];
                    applyStyleNow();
                }));
        box.addView(div());

        // 翻页方式（切换会重排，按当前位置对接，不回到章首）
        box.addView(segRow("翻页方式", new String[]{"左右翻页", "上下滚动"},
                scrollMode ? 1 : 0, i -> {
                    scrollMode = i == 1;
                    applyStyleNow();
                }));
        box.addView(div());

        // 动效三档：0 关闭 / 1 简约 / 2 MD3 标准（与设置页同键，立即生效）
        box.addView(segRow("动效", new String[]{"关闭", "简约", "标准"},
                animGrade, i -> {
                    animGrade = i;
                    ModouStore.get(this).putSettingStr("uiAnimGrade", String.valueOf(i));
                    ModouUi.toast(this, i == 0 ? "动效已关闭" : i == 1 ? "动效：简约" : "动效：MD3 标准");
                }));
        box.addView(div());

        // 页眉（只对分页模式生效，与网页版一致）
        box.addView(segRow("页眉", new String[]{"不显示", "章节名", "书名", "书名+章"},
                headMode, i -> {
                    headMode = i;
                    saveHeadFoot();
                    applyStyleNow();
                }));
        box.addView(div());

        // 页脚（可多选，可同时开「页码 + 时间 + 电量」）
        box.addView(multiRow("页脚",
                new String[]{"页码", "时间", "电量", "全书进度", "本章进度", "剩余章数"},
                new int[]{1, 2, 4, 8, 16, 32}, footMask, m -> {
                    footMask = m;
                    saveHeadFoot();
                    readBattery();
                    applyStyleNow();
                    hfHandler.removeCallbacks(hfTick);
                    if (footMask != 0) hfHandler.postDelayed(hfTick, 30000);
                }));
        // 页脚六项可任意组合，但位置不是随机的：位置类固定靠左、状态类靠右，
        // 只选一个状态类时会自动挪到左边占位 —— 这条规则光看按钮猜不出来，写一行说明。
        TextView footNote = ModouUi.tv(this,
                "可多选。位置类（页码 / 剩余章数）靠左，状态类（时间 / 电量 / 进度）靠右；一行放不下会自动折行。",
                11.5f, theme.sub);
        footNote.setPadding(0, 0, 0, dp(6));
        box.addView(footNote);

        sc.addView(box);
        showSheet("排版", sc);
        typoShowing = true;
    }

    /** 多选 chip 行（页脚）：每行 3 个等分，点一下切一个位 */
    private LinearLayout multiRow(String title, String[] labels, int[] bits, int mask, Pick cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
        row.addView(ModouUi.tv(this, title, 13, theme.sub));
        final int[] st = {mask};
        LinearLayout line = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 3 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setPadding(0, i == 0 ? ModouUi.dp(this, 8) : ModouUi.dp(this, 6), 0, 0);
                row.addView(line);
            }
            final int bit = bits[i];
            boolean on = (st[0] & bit) != 0;
            TextView b = ModouUi.tv(this, labels[i], 13, on ? segOnText() : theme.text);
            b.setGravity(Gravity.CENTER);
            b.setPadding(ModouUi.dp(this, 4), ModouUi.dp(this, 9), ModouUi.dp(this, 4), ModouUi.dp(this, 9));
            b.setBackground(chipBgDrawable(on));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, ModouUi.dp(this, 6), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener((x) -> {
                st[0] ^= bit;
                boolean now = (st[0] & bit) != 0;
                b.setTextColor(now ? segOnText() : theme.text);
                b.setBackground(chipBgDrawable(now));
                if (cb != null) cb.on(st[0]);
            });
            line.addView(b);
        }
        return row;
    }

    private GradientDrawable chipBgDrawable(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? theme.acc : chipBg());
        g.setCornerRadius(ModouUi.dp(this, 11));
        return g;
    }

    /**
     * 字号行：标题 + 数值在上，下面两个等宽的「A− / A+」。
     * 之前把两个按钮塞进标题那一行的剩余宽度里，权重 1 会把方块拉成一条长条，
     * 高度又被内部 LayoutParams 顶成 36dp，看起来就是个歪的色块 —— 这里按网页版
     * .btn.stepper 的结构改成独占一行、等分宽度。
     */
    private View sizeRow(String title, int val) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ModouUi.tv(this, title, 14, theme.text));
        TextView v = ModouUi.tv(this, val + " px", 13, theme.acc);
        v.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
        head.addView(v);
        row.addView(head);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, ModouUi.dp(this, 10), 0, 0);
        TextView down = stepBtn("A−");
        TextView up = stepBtn("A+");
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ModouUi.dp(this, 40), 1f);
        l1.rightMargin = ModouUi.dp(this, 10);
        btns.addView(down, l1);
        btns.addView(up, new LinearLayout.LayoutParams(0, ModouUi.dp(this, 40), 1f));
        row.addView(btns);

        down.setOnClickListener((x) -> {
            fontSize = Math.max(14, fontSize - 1);
            v.setText(fontSize + " px");
            applyStyleNow();
        });
        up.setOnClickListener((x) -> {
            fontSize = Math.min(26, fontSize + 1);
            v.setText(fontSize + " px");
            applyStyleNow();
        });
        return row;
    }

    /** 「A− / A+」按钮：淡底 + 1px 描边 + 11dp 圆角，与网页版 .btn.stepper 一致 */
    private TextView stepBtn(String label) {
        TextView t = ModouUi.tv(this, label, 15, theme.text);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(chipBg());
        g.setCornerRadius(ModouUi.dp(this, 11));
        g.setStroke(ModouUi.dp(this, 1), theme.dark ? 0x1FFFFFFF : 0x142B251B);
        t.setBackground(g);
        return t;
    }

    /** 淡底 chip 色：浅色主题是淡墨，夜间是淡白 */
    private int chipBg() {
        return theme.dark ? 0x1FFFFFFF : 0x0F2B251B;
    }

    /** 分段控件行：标题 + 一排等宽按钮，选中态高亮 */
    private LinearLayout segRow(String title, String[] labels, int cur, Pick cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
        row.addView(ModouUi.tv(this, title, 13, theme.sub));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(0, ModouUi.dp(this, 8), 0, 0);
        int n = labels.length;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            TextView b = ModouUi.tv(this, labels[i], 14, i == cur ? segOnText() : theme.text);
            b.setGravity(Gravity.CENTER);
            b.setPadding(ModouUi.dp(this, 6), ModouUi.dp(this, 9), ModouUi.dp(this, 6), ModouUi.dp(this, 9));
            GradientDrawable g = new GradientDrawable();
            g.setColor(i == cur ? theme.acc : chipBg());
            g.setCornerRadius(ModouUi.dp(this, 11));
            b.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, ModouUi.dp(this, 6), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener((x) -> {
                if (cb != null) cb.on(idx);
                // 重画选中态
                for (int k = 0; k < seg.getChildCount(); k++) {
                    TextView c = (TextView) seg.getChildAt(k);
                    boolean on = k == idx;
                    c.setTextColor(on ? segOnText() : theme.text);
                    GradientDrawable gg = new GradientDrawable();
                    gg.setColor(on ? theme.acc : chipBg());
                    gg.setCornerRadius(ModouUi.dp(this, 11));
                    c.setBackground(gg);
                }
            });
            seg.addView(b);
        }
        row.addView(seg);
        return row;
    }

    /** 选中分段的前景色：强调色做底时取浅色文字 */
    private int segOnText() {
        return theme.dark ? 0xFF1B1D21 : 0xFFFFFFFF;
    }

    /**
     * 滑杆行：标题 + 实时数值 + 自绘滑杆。
     *
     * 不用系统 SeekBar 的默认外观（那是 Material 的粗轨道 + 大圆点，和网页版差得远），
     * 而是复刻网页版 input[type=range]：4dp 圆角细轨、18dp 强调色圆点、2dp 奶油白描边。
     * 进度基准也修了：以前 progress 直接等于数值，导致「页边距 12–44」的滑杆前 1/3
     * 全是死区（滑到最左也还是 12），现在按 (max-min) 映射。
     */
    private LinearLayout seekRow(String title, int min, int max, int step, int val,
                                 Fmt fmt, Pick cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ModouUi.tv(this, title, 14, theme.text));
        TextView v = ModouUi.tv(this, fmt.of(val), 13, theme.acc);
        v.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
        head.addView(v);
        row.addView(head);

        final int span = Math.max(1, max - min);
        SeekBar sb = new SeekBar(this);
        sb.setMax(span);
        sb.setProgress(Math.max(0, Math.min(span, val - min)));
        sb.setKeyProgressIncrement(Math.max(1, step));
        sb.setSplitTrack(false);
        sb.setPadding(ModouUi.dp(this, 9), 0, ModouUi.dp(this, 9), 0);
        sb.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(this, 30)));
        try {
            sb.setProgressDrawable(rangeTrack());
            sb.setThumb(rangeThumb());
            sb.setThumbOffset(0);
        } catch (Throwable ignored) {
            /* 旧 API 退回系统外观 */
        }
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int v2 = min + Math.round((float) progress / Math.max(1, step)) * Math.max(1, step);
                v2 = Math.max(min, Math.min(max, v2));
                v.setText(fmt.of(v2));
                if (fromUser && cb != null) {
                    cb.on(v2);
                    // 拖动中即时预览；样式应用做 90ms 防抖，避免每帧重排掉帧
                    applyStyleSoon();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                // 松手补一次，保证落库的是最终值而不是防抖窗口里的中间值
                int v2 = min + Math.round((float) bar.getProgress() / Math.max(1, step)) * Math.max(1, step);
                v2 = Math.max(min, Math.min(max, v2));
                if (cb != null) cb.on(v2);
                applyStyleNow();
            }
        });
        row.addView(sb);
        return row;
    }

    /** 4dp 圆角轨道：未滑过 = 淡墨（网页版 rgba(43,37,27,.16)），已滑过 = 强调色 */
    private Drawable rangeTrack() {
        final int trackBg = theme.dark ? 0x2BFFFFFF : 0x292B251B;
        final int trackOn = theme.acc;
        final float h = ModouUi.dp(this, 4);
        final float r = h / 2f;
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c) {
                Rect b = getBounds();
                float cy = b.centerY();
                p.setColor(trackBg);
                c.drawRoundRect(new RectF(b.left, cy - h / 2f, b.right, cy + h / 2f), r, r, p);
                float w = b.width() * (getLevel() / 10000f);
                if (w > 0.5f) {
                    p.setColor(trackOn);
                    c.drawRoundRect(new RectF(b.left, cy - h / 2f, b.left + w, cy + h / 2f), r, r, p);
                }
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /** 18dp 圆点：强调色实心 + 2dp 奶油白描边（夜间换深描边，否则白圈刺眼） */
    private Drawable rangeThumb() {
        final float size = ModouUi.dp(this, 18);
        final int fill = theme.acc;
        final int ring = theme.dark ? 0xFF1B1D21 : 0xFFFCF8EE;
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c) {
                float cx = getBounds().centerX();
                float cy = getBounds().centerY();
                float rad = size / 2f;
                p.setStyle(Paint.Style.FILL);
                p.setColor(ring);
                c.drawCircle(cx, cy, rad, p);
                p.setColor(fill);
                c.drawCircle(cx, cy, rad - ModouUi.dp(NativeReaderActivity.this, 2), p);
            }

            @Override
            public int getIntrinsicWidth() {
                return (int) size;
            }

            @Override
            public int getIntrinsicHeight() {
                return (int) size;
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter cf) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /** 拖动滑杆时的防抖重排：120ms 内的连续改动只应用最后一次 */
    private void applyStyleSoon() {
        if (pendingStyle != null) styleHandler.removeCallbacks(pendingStyle);
        pendingStyle = this::applyStyle;
        styleHandler.postDelayed(pendingStyle, 90);
    }

    private void applyStyleNow() {
        if (pendingStyle != null) {
            styleHandler.removeCallbacks(pendingStyle);
            pendingStyle = null;
        }
        applyStyle();
        syncSettings();
    }

    /** 把所有排版状态一次性落库：拖动中不写，松手/点击才调，避免每帧写 SQLite */
    private void syncSettings() {
        ModouStore s = ModouStore.get(this);
        s.putSetting("readerFontSize", String.valueOf(fontSize));
        s.putSetting("readerLineHeight", String.valueOf(lineHeight));
        s.putSetting("readerParaGap", String.valueOf(paraGap));
        s.putSetting("readerMargin", String.valueOf(marginPx));
        s.putSetting("readerFontWeight", String.valueOf(weightVal));
        s.putSettingStr("readerFontFamily", font);
        s.putSettingStr("readerPageMode", scrollMode ? "scroll" : "page");
    }

    private LinearLayout toggleRow(String title, boolean on, PickBool cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ModouUi.dp(this, 10), 0, ModouUi.dp(this, 10));
        row.addView(ModouUi.tv(this, title, 15, theme.text));
        TextView t = ModouUi.tv(this, on ? "开" : "关", 14, theme.acc);
        t.setGravity(Gravity.END);
        t.setPadding(ModouUi.dp(this, 10), 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(lp);
        row.addView(t);
        boolean[] st = {on};
        row.setOnClickListener((x) -> {
            boolean next = !st[0];
            st[0] = next;
            t.setText(next ? "开" : "关");
            if (cb != null) cb.on(next);
        });
        return row;
    }

    private View div() {
        View v = new View(this);
        v.setBackgroundColor(theme.dark ? 0x14FFFFFF : 0x0F2B251B);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private int weightIndex(int w) {
        int[] arr = {300, 400, 500, 600};
        for (int i = 0; i < arr.length; i++) if (arr[i] == w) return i;
        return 2;
    }

    private int fontIndex(String f) {
        if ("hei".equals(f)) return 1;
        if ("kai".equals(f)) return 2;
        return 0;
    }

    private int themeIndex() {
        if (darkMode) return 3;
        if ("cream".equals(lightTheme)) return 1;
        if ("gray".equals(lightTheme)) return 2;
        return 0;
    }

    /** 分段控件回调 */
    private interface Pick {
        void on(int i);
    }

    /** 带标签的分段（主题用）：按下即按序号 0..3 处理 */
    private interface Fmt {
        String of(int v);
    }

    private interface PickBool {
        void on(boolean on);
    }

    /* ---------------- 正文全文搜索 ---------------- */

    private void showSearch(String preset) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 输入行：圆角淡底输入框 + 强调色「搜索」胶囊
        LinearLayout inRow = new LinearLayout(this);
        inRow.setOrientation(LinearLayout.HORIZONTAL);
        inRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText input = new EditText(this);
        input.setHint("搜索全书正文");
        input.setHintTextColor(theme.sub);
        input.setTextColor(theme.text);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setText(preset == null ? "" : preset);
        input.setSingleLine(true);
        input.setBackground(chipDrawable(ModouUi.dp(this, 999)));
        input.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 9),
                ModouUi.dp(this, 14), ModouUi.dp(this, 9));
        inRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView searchBtn = new TextView(this);
        searchBtn.setText("搜索");
        searchBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        searchBtn.setTextColor(segOnText());
        searchBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.setPadding(ModouUi.dp(this, 16), ModouUi.dp(this, 9),
                ModouUi.dp(this, 16), ModouUi.dp(this, 9));
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(theme.acc);
        gb.setCornerRadius(ModouUi.dp(this, 999));
        searchBtn.setBackground(gb);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = ModouUi.dp(this, 8);
        inRow.addView(searchBtn, blp);
        box.addView(inRow);

        TextView status = ModouUi.tv(this, "输入关键词后点搜索", 12, theme.sub);
        status.setPadding(ModouUi.dp(this, 6), ModouUi.dp(this, 10), 0, ModouUi.dp(this, 4));
        box.addView(status);

        final List<SearchHit> hits = new ArrayList<>();
        ListView lv = sheetList(new String[]{" "}, null, -1);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.46f)));

        searchBtn.setOnClickListener((x) -> {
            String kw = input.getText().toString().trim();
            if (kw.isEmpty()) {
                status.setText("请输入关键词");
                return;
            }
            status.setText("搜索中…");
            runSearch(kw, hits, status, lv);
        });

        lv.setOnItemClickListener((a, v, pos, id) -> {
            if (pos < 0 || pos >= hits.size()) return;
            SearchHit h = hits.get(pos);
            closeSheet();
            if (h.index == index) {
                if (curBlocks != null) pageView.setBlocks(curBlocks, h.offset);
                else pageView.setChapter(String.valueOf(pageView.currentText()), h.offset);
                saveProgress(false);
            } else {
                requestChapter(h.index, h.offset);
            }
        });

        showSheet("搜索", box);
    }

    /** 圆角淡底：输入框、chip 共用 */
    private GradientDrawable chipDrawable(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(chipBg());
        g.setCornerRadius(radius);
        return g;
    }

    /** 扫描已缓存章节（含当前章）的命中；偏移落在与阅读页完全一致的带段距文本上 */
    private void runSearch(String kw, List<SearchHit> out, TextView status, ListView lv) {
        out.clear();
        final String lowerKw = kw.toLowerCase();
        new Thread(() -> {
            List<SearchHit> found = new ArrayList<>();
            float d = getResources().getDisplayMetrics().density;
            for (int ci = 0; ci < total; ci++) {
                String html = ModouStore.get(this).chapterContent(bookId, ci);
                if (html == null || html.trim().isEmpty()) continue;
                String name = purifyTitle(chapterNameOf(ci));
                List<Paras.Block> bl = Paras.blocks(purifyContent(html), name);
                if (bl.isEmpty()) continue;
                Heading h = fixHeading(name, bl);
                CharSequence text = NativePageView.gapped(null, h.blocks, paraGap, fontSize * d, h.title);
                String s = text.toString().toLowerCase();
                int from = 0;
                while (found.size() < 300) {
                    int p = s.indexOf(lowerKw, from);
                    if (p < 0) break;
                    int snipStart = Math.max(0, p - 12);
                    int snipEnd = Math.min(s.length(), p + kw.length() + 12);
                    found.add(new SearchHit(ci, p,
                            chapterNameOf(ci) + "：" + s.substring(snipStart, snipEnd).replace('\n', ' ')));
                    from = p + kw.length();
                }
                if (found.size() >= 300) break;
            }
            runOnUiThread(() -> {
                out.addAll(found);
                if (found.isEmpty()) {
                    status.setText("没有匹配（仅搜索已缓存的章节）");
                    lv.setAdapter(sheetAdapter(new String[]{"无结果"}, null, -1));
                    return;
                }
                status.setText("找到 " + found.size() + " 处");
                String[] labels = new String[found.size()];
                for (int i = 0; i < found.size(); i++) labels[i] = found.get(i).label;
                lv.setAdapter(sheetAdapter(labels, null, -1));
            });
        }).start();
    }

    private static final class SearchHit {
        final int index;
        final int offset;
        final String label;

        SearchHit(int index, int offset, String label) {
            this.index = index;
            this.offset = offset;
            this.label = label;
        }
    }

    /* ---------------- 自动阅读 ---------------- */

    private void toggleAuto() {
        if (autoOn) {
            stopAuto();
            toast("自动阅读·关");
        } else {
            startAuto();
        }
        refreshDockAuto();
    }

    /** 自动阅读开关后刷新工具坞：状态条显隐 + 按钮高亮 */
    private void refreshDockAuto() {
        if (dock == null) return;
        if (dockAuto != null) dockAuto.setVisibility(autoOn ? View.VISIBLE : View.GONE);
        if (autoOn && dockAutoSpeed != null) dockAutoSpeed.setText((autoInterval / 1000) + "s");
        paintDockRows();
        if (ttsBar != null && ttsBar.getVisibility() == View.VISIBLE) {
            ttsBar.post(this::syncTtsOverlayMargin); // 自动阅读条会改工具坞高度
        }
    }

    private void startAuto() {
        autoOn = true;
        autoHandler.removeCallbacks(autoTick);
        autoHandler.postDelayed(autoTick, autoInterval);
        if (autoBar != null) {
            autoStartAt = SystemClock.uptimeMillis();
            autoBar.setVisibility(View.VISIBLE);
            autoBar.setScaleX(1f);
            autoHandler.removeCallbacks(autoBarTick);
            autoHandler.post(autoBarTick);
        }
        toast("自动阅读·开（" + (autoInterval / 1000) + " 秒/页）");
    }

    private void stopAuto() {
        autoOn = false;
        autoHandler.removeCallbacks(autoTick);
        autoHandler.removeCallbacks(autoBarTick);
        if (autoBar != null) autoBar.setVisibility(View.GONE);
    }

    /* ---------------- 书签 ---------------- */

    /**
     * 加书签。
     *
     * 位置记两样：章节序号 + 章内字符偏移。只记章节的话，一章几千字，
     * 书签回来还得从头翻；只记偏移的话换章后偏移就失去意义。
     * id 带上两者，同一位置重复加不会攒出一串重复书签。
     */
    private void addBookmark() {
        if (bookId == null || bookId.isEmpty()) return;
        int off = pageView.pageStartOffset();
        String id = bookId + "||" + index + "||" + off;
        try {
            // 同一页再点一次：id 相同，直接提示，别让人以为又存了一个
            if (ModouStore.get(this).get("bookmarks", id) != null) {
                toast("这一页已经有书签了");
                return;
            }
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("bookId", bookId);
            o.put("bookName", bookTitle);
            o.put("chapterIndex", index);
            o.put("chapterName", chapterTitle);
            o.put("page", 0); // 页码是网页版的概念，原生不用；保留字段免得网页版读到 undefined
            o.put("offset", off);
            o.put("text", previewOf(off));
            o.put("createdAt", System.currentTimeMillis());
            ModouStore.get(this).put("bookmarks", o.toString());
            toast("已加书签");
        } catch (Exception e) {
            toast("书签保存失败");
        }
    }

    /** 书签位置往后取一小段正文当摘要（只取当前页首屏那点字） */
    private String previewOf(int off) {
        String s = pageView.selectionText();
        if (!s.isEmpty()) return s.length() > 30 ? s.substring(0, 30) : s;
        CharSequence t = pageView.currentText();
        if (t == null) return "";
        int end = Math.min(t.length(), off + 24);
        String sub = t.subSequence(Math.min(off, t.length()), end).toString()
                .replace('\n', ' ').replace('\uFFFC', ' ').trim();
        return sub;
    }

    /** 本书书签，按时间倒序（目录抽屉、书签列表共用） */
    private List<JSONObject> bookmarksOf() {
        List<JSONObject> all = new ArrayList<>();
        if (bookId == null || bookId.isEmpty()) return all;
        try {
            String s = ModouStore.get(this).all("bookmarks");
            if (s != null && !s.isEmpty()) {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && bookId.equals(o.optString("bookId", ""))) all.add(o);
                }
            }
        } catch (Exception e) {
            /* 空表/脏数据 */
        }
        all.sort((x, y) -> Long.compare(y.optLong("createdAt", 0), x.optLong("createdAt", 0)));
        return all;
    }

    /** 抽屉里的一行：主行 + 可选副行 + 跳转位置 */
    private static final class TocRow {
        String label;
        String sub;
        int chapter;
        int offset;
        boolean cur;   // 当前所在章
        String id;     // 书签行才有：删除时按 id 定位
    }

    /**
     * 目录抽屉：目录 / 书签双 tab + 关键字筛选（对齐网页版 reader-toc.js 的抽屉）。
     *
     * 原来只能翻目录，找书签要先「更多 → 书签列表」，找某一章要靠滚动几千行。
     * 现在两件事在同一个抽屉里，一个输入框就能筛。
     */
    private class TocSheet {
        private final List<JSONObject> bms;
        private final ListView lv;
        private final EditText filter;
        private final TextView[] tabTv = new TextView[2];
        private final View[] tabBar = new View[2];
        private final TextView empty;
        private final TextView clearBm;
        private final List<TocRow> rows = new ArrayList<>();
        private int mode = 0;   // 0 目录 / 1 书签

        TocSheet() {
            this(0);
        }

        TocSheet(int start) {
            bms = bookmarksOf();
            mode = start == 1 ? 1 : 0;
            LinearLayout box = new LinearLayout(NativeReaderActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);

            // ---- 双 tab：文字 + 2dp 指示条（书架页签同一套视觉） ----
            LinearLayout tabs = new LinearLayout(NativeReaderActivity.this);
            tabs.setOrientation(LinearLayout.HORIZONTAL);
            String[] names = {"目录 · " + titles.length, "书签 · " + bms.size()};
            for (int i = 0; i < 2; i++) {
                final int m = i;
                boolean on = i == mode;
                LinearLayout col = new LinearLayout(NativeReaderActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView t = ModouUi.tv(NativeReaderActivity.this, names[i], 14,
                        on ? theme.text : theme.sub);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, dp(7), 0, dp(6));
                if (on) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                col.addView(t, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                View b = new View(NativeReaderActivity.this);
                GradientDrawable bgBar = new GradientDrawable();
                bgBar.setColor(theme.acc);
                bgBar.setCornerRadius(dp(1));
                b.setBackground(bgBar);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(26), dp(2));
                blp.gravity = Gravity.CENTER_HORIZONTAL;
                col.addView(b, blp);
                b.setAlpha(on ? 1f : 0f);
                tabTv[i] = t;
                tabBar[i] = b;
                col.setOnClickListener(v -> select(m));
                tabs.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            box.addView(tabs);

            // ---- 筛选框 ----
            filter = new EditText(NativeReaderActivity.this);
            filter.setSingleLine(true);
            filter.setHint("筛选章节名 / 书签内容");
            filter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            filter.setTextColor(theme.text);
            filter.setHintTextColor(ModouUi.alpha(theme.sub, 0xB0));
            GradientDrawable fbg = new GradientDrawable();
            fbg.setColor(theme.bg);
            fbg.setCornerRadius(dp(10));
            filter.setBackground(fbg);
            filter.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.topMargin = dp(8);
            flp.bottomMargin = dp(6);
            box.addView(filter, flp);

            // ---- 列表 ----
            lv = new ListView(NativeReaderActivity.this);
            lv.setDivider(null);
            lv.setDividerHeight(0);
            lv.setVerticalScrollBarEnabled(false);
            lv.setCacheColorHint(Color.TRANSPARENT);
            lv.setAdapter(adapter());
            lv.setOnItemClickListener((p, v, pos, id) -> {
                TocRow r = rows.get(pos);
                closeSheet();
                if (mode == 0) {
                    if (r.chapter != index) requestChapter(r.chapter);
                } else {
                    if (r.chapter == index) {
                        if (curBlocks != null) pageView.setBlocks(curBlocks, r.offset);
                        else pageView.setChapter(String.valueOf(pageView.currentText()), r.offset);
                        saveProgress(false);
                    } else {
                        requestChapter(r.chapter, r.offset);
                    }
                }
            });
            int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.56f);
            box.addView(lv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h));

            empty = ModouUi.empty(NativeReaderActivity.this, "没有匹配的条目");
            empty.setVisibility(View.GONE);
            box.addView(empty);

            // ---- 书签 tab 的「清空」：原来在独立的书签列表里，现在跟着 tab 走 ----
            clearBm = ModouUi.tv(NativeReaderActivity.this, "清空本书书签", 13, theme.acc);
            clearBm.setGravity(Gravity.CENTER);
            clearBm.setPadding(0, dp(10), 0, dp(2));
            clearBm.setOnClickListener(v -> {
                for (JSONObject o : bms) {
                    ModouStore.get(NativeReaderActivity.this).del("bookmarks", o.optString("id", ""));
                }
                bms.clear();
                closeSheet();
                toast("已清空");
            });
            box.addView(clearBm);

            filter.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                    fill();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            fill();
            showSheet(bookTitle == null || bookTitle.isEmpty() ? "目录" : bookTitle, box);
        }

        private void select(int m) {
            if (mode == m) return;
            mode = m;
            for (int i = 0; i < 2; i++) {
                tabTv[i].setTextColor(i == m ? theme.text : theme.sub);
                tabTv[i].setTypeface(Typeface.DEFAULT, i == m ? Typeface.BOLD : Typeface.NORMAL);
                tabBar[i].setAlpha(i == m ? 1f : 0f);
            }
            filter.setText("");
            fill();
        }

        /** 按当前 tab + 关键字重算列表；目录 tab 自动滚到当前章 */
        private void fill() {
            String kw = filter.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            rows.clear();
            if (mode == 0) {
                for (int i = 0; i < titles.length; i++) {
                    String s = titles[i] == null ? "" : titles[i];
                    if (!kw.isEmpty() && !s.toLowerCase(java.util.Locale.ROOT).contains(kw)) continue;
                    TocRow r = new TocRow();
                    r.label = s;
                    r.chapter = i;
                    r.cur = i == index;
                    rows.add(r);
                }
            } else {
                for (JSONObject o : bms) {
                    String cn = o.optString("chapterName", "");
                    if (cn.isEmpty()) cn = "第 " + (o.optInt("chapterIndex", 0) + 1) + " 章";
                    String tx = o.optString("text", "").replace('\n', ' ').trim();
                    if (!kw.isEmpty()
                            && !(cn + " " + tx).toLowerCase(java.util.Locale.ROOT).contains(kw)) {
                        continue;
                    }
                    TocRow r = new TocRow();
                    r.label = cn;
                    r.sub = tx;
                    r.chapter = o.optInt("chapterIndex", 0);
                    r.offset = o.optInt("offset", 0);
                    r.id = o.optString("id", "");
                    r.cur = r.chapter == index;
                    rows.add(r);
                }
            }
            lv.setAdapter(adapter());
            empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            lv.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
            clearBm.setVisibility(mode == 1 && !bms.isEmpty() ? View.VISIBLE : View.GONE);
            if (mode == 0 && kw.isEmpty()) {
                int at = 0;
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).cur) {
                        at = i;
                        break;
                    }
                }
                lv.setSelection(Math.max(0, at - 3));
            }
        }

        private BaseAdapter adapter() {
            return new BaseAdapter() {
                @Override
                public int getCount() {
                    return rows.size();
                }

                @Override
                public Object getItem(int i) {
                    return rows.get(i);
                }

                @Override
                public long getItemId(int i) {
                    return i;
                }

                @Override
                public View getView(int i, View reuse, ViewGroup parent) {
                    TocRow r = rows.get(i);
                    LinearLayout row = new LinearLayout(NativeReaderActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(8), dp(11), dp(8), dp(11));
                    // 这里不能给根视图塞 LinearLayout.LayoutParams：ListView 只认
                    // AbsListView.LayoutParams，宽 0 + weight 1 在 ListView 里就是「宽 0」——
                    // 目录每一行都被压成 0 宽，列表看着就是一片空白（书签行同病）。
                    // 宽度交给 ListView 的默认参数（MATCH_PARENT），只有书签行包 wrapper 时才给 weight。
                    TextView a = ModouUi.tv(NativeReaderActivity.this, r.label, 14,
                            r.cur ? theme.acc : theme.text);
                    a.setSingleLine(true);
                    a.setEllipsize(TextUtils.TruncateAt.END);
                    if (r.cur) a.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    row.addView(a);
                    if (r.sub != null && !r.sub.isEmpty()) {
                        TextView b = ModouUi.tv(NativeReaderActivity.this, r.sub, 11.5f, theme.sub);
                        b.setSingleLine(true);
                        b.setEllipsize(TextUtils.TruncateAt.END);
                        b.setAlpha(0.8f);
                        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                        bp.topMargin = dp(3);
                        row.addView(b, bp);
                    }
                    if (mode != 1 || r.id == null || r.id.isEmpty()) return row;

                    // 书签行：右侧一个「×」，删一条不用再跑「清空本书」
                    LinearLayout wrap = new LinearLayout(NativeReaderActivity.this);
                    wrap.setOrientation(LinearLayout.HORIZONTAL);
                    wrap.setGravity(Gravity.CENTER_VERTICAL);
                    wrap.addView(row, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    TextView del = new TextView(NativeReaderActivity.this);
                    del.setText("×");
                    del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                    del.setTextColor(ModouUi.alpha(theme.sub, 0xB0));
                    del.setGravity(Gravity.CENTER);
                    del.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
                    del.setOnClickListener(v -> {
                        ModouStore.get(NativeReaderActivity.this).del("bookmarks", r.id);
                        bms.clear();
                        bms.addAll(bookmarksOf());
                        if (tabTv.length > 1) tabTv[1].setText("书签 · " + bms.size());
                        fill();
                        toast("已删除书签");
                    });
                    wrap.addView(del);
                    return wrap;
                }
            };
        }
    }

    private void showToc() {
        ensureTitles();
        if (titles.length == 0) {
            toast("目录为空");
            return;
        }
        new TocSheet();
    }

    /**
     * 兜底取目录。
     *
     * titles 平时由入口（网页版 payload / 书架）一并带进来，但只要有一条入口没带全，
     * 目录就直接是空的 —— 而 books 记录里就存着完整的 chapters[]，没必要让它空着。
     */
    private void ensureTitles() {
        if (titles.length > 0) return;
        JSONObject b = bookObj();
        JSONArray chs = b == null ? null : b.optJSONArray("chapters");
        if (chs == null || chs.length() == 0) return;
        String[] t = new String[chs.length()];
        for (int i = 0; i < chs.length(); i++) {
            JSONObject c = chs.optJSONObject(i);
            t[i] = c == null ? "" : c.optString("name", "");
        }
        titles = t;
        if (total <= 0) total = t.length;
    }

    /**
     * 抽屉里的列表：主行 + 可选副行，当前项用强调色加粗。
     * 不用 simple_list_item_1，避免系统深色文字在夜间主题下看不清。
     */
    private ListView sheetList(String[] items, String[] subs, int current) {
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setVerticalScrollBarEnabled(false);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setAdapter(sheetAdapter(items, subs, current));
        return lv;
    }

    /** 抽屉列表的适配器：主行 + 可选副行，当前项用强调色加粗 */
    private BaseAdapter sheetAdapter(String[] items, String[] subs, int current) {
        final String[] sub2 = subs;
        return new BaseAdapter() {
            @Override
            public int getCount() {
                return items.length;
            }

            @Override
            public Object getItem(int i) {
                return items[i];
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View reuse, ViewGroup parent) {
                LinearLayout row = new LinearLayout(NativeReaderActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(ModouUi.dp(NativeReaderActivity.this, 8),
                        ModouUi.dp(NativeReaderActivity.this, 11),
                        ModouUi.dp(NativeReaderActivity.this, 8),
                        ModouUi.dp(NativeReaderActivity.this, 11));
                boolean on = i == current;
                TextView t = new TextView(NativeReaderActivity.this);
                t.setText(items[i]);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                t.setSingleLine(sub2 == null);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setTextColor(on ? theme.acc : theme.text);
                t.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
                row.addView(t);
                if (sub2 != null && i < sub2.length && !sub2[i].isEmpty()) {
                    TextView s = new TextView(NativeReaderActivity.this);
                    s.setText(sub2[i]);
                    s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    s.setSingleLine(true);
                    s.setEllipsize(TextUtils.TruncateAt.END);
                    s.setTextColor(theme.sub);
                    s.setPadding(0, ModouUi.dp(NativeReaderActivity.this, 3), 0, 0);
                    row.addView(s);
                }
                return row;
            }
        };
    }

    /* ---------------- 选区动作 ---------------- */

    private void copySelection() {
        String txt = pageView.selectionText();
        if (txt.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("墨读", txt));
        pageView.clearSel();
        toast("已复制 " + txt.length() + " 字");
    }

    private void shareSelection() {
        String txt = pageView.selectionText();
        if (txt.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, txt);
        startActivity(Intent.createChooser(i, "分享"));
        pageView.clearSel();
    }

    /**
     * 「替换净化」：把选中的文字直接落成一条正文净化规则（替换留空 = 删除），
     * 与网页版选词工具条同一套语义 —— 看到广告词选一下就有，不用再去设置页重敲一遍。
     *
     * 选区可能跨段（中间夹着换行与段首缩进），那样落库的 pattern 在正文里永远匹配不到，
     * 所以只取第一段，剩下的交给编辑器里改。
     */
    private void purifySelection() {
        String raw = pageView.selectionText();
        pageView.clearSel();
        String pat = raw.replace('\u3000', ' ').trim();
        int nl = pat.indexOf('\n');
        if (nl > 0) pat = pat.substring(0, nl).trim();
        if (pat.isEmpty()) {
            toast("先选中要净化的文字");
            return;
        }
        RulesActivity.launchAdd(this, pat);
    }

    /* ---------------- 小工具 ---------------- */

    private static JSONObject parse(String json) {
        try {
            return new JSONObject(json == null || json.isEmpty() ? "{}" : json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String[] strArray(JSONArray a) {
        if (a == null) return new String[0];
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optString(i, "");
        return out;
    }

    /** 段落 → 单串：段首两个全角空格缩进，段间硬换行 */
    private static String parasToText(JSONArray paras) {
        if (paras == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paras.length(); i++) {
            String s = paras.optString(i, "");
            s = s.replace('\u00a0', ' ').trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("　　").append(s);
        }
        return sb.toString();
    }

    /**
     * 状态栏高度。
     *
     * 不能只读 status_bar_height 这个系统资源：刘海/挖孔机型上它是「状态栏本体」高度，
     * 不含挖孔，比真实可绘制区域顶端小一截 —— 页眉就会顶到状态栏里，看着像被切掉一截。
     * 优先用窗口 inset（含刘海），拿不到再退回资源值；首次取到更准的值会重排一次。
     */
    private int statusBarHeight() {
        if (sbPx > 0) return sbPx;
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        sbPx = id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
        return sbPx;
    }

    /** 窗口真实 inset 顶部（含挖孔）；尚未 attach 时返回 0 */
    private int realStatusBarHeight() {
        try {
            View decor = getWindow() == null ? null : getWindow().getDecorView();
            if (decor == null) return 0;
            android.view.WindowInsets wi = decor.getRootWindowInsets();
            if (wi == null) return 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return wi.getInsets(android.view.WindowInsets.Type.statusBars()).top;
            }
            return wi.getSystemWindowInsetTop();
        } catch (Throwable e) {
            return 0;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        int real = realStatusBarHeight();
        // 只往大里修：资源值偏小时页眉会被状态栏压住，偏大时只是多留一点白，不会出错
        if (real > statusBarHeight()) {
            sbPx = real;
            applyStyle();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
