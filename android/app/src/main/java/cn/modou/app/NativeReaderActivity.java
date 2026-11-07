package cn.modou.app;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
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
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
public class NativeReaderActivity extends Activity implements NativePageView.Host, SourceSwap.Host {

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
    /** 程序化跳转（搜索“上一处/下一处”、书签、目录）是否走静默切换：
     *  true 时不弹加载遮罩，旧页一直保留到新章在后台排版完成再无缝替换，绝无白屏/半成品帧 */
    private boolean silentJump;
    private TextView header;
    private TextView headerSub;
    /** 沉浸常驻页眉：菜单收起时仍留在顶部的一行小字（书名 · 章节），半透明灰 */
    private TextView miniHead;
    /** 顶栏返回钮：换主题时要重新着色（图标是 tinted 出来的位图，不会自己跟色） */
    private ImageView headBack;
    /** 排版抽屉是否开着：切主题时要按新配色重建它（它自己是旧色画的） */
    private boolean typoShowing;
    /** 顶部沉浸栏容器（含状态栏高度），随工具坞一起显隐 */
    private LinearLayout head;
    private boolean headShowing = false; // 顶栏默认藏起来：第一次开书不自动弹菜单

    /* ---------------- 底部工具坞（对齐网页版 .rd-dock） ---------------- */

    /** 工具坞整体容器（重力贴底，含圆角面板） */
    private LinearLayout dock;
    /**
     * 工具坞窗口内容容器（竖排）：上方放朗读浮条 ttsBar、下方放 dock。
     * 整块搬进独立窗口，由系统实时模糊它背后的阅读正文（与书架底栏同源做法）。
     */
    private LinearLayout dockHost;
    /** 工具坞独立窗口（复用书架 ModouDockWindow：NOT_TOUCH_MODAL | NOT_FOCUSABLE + 模糊） */
    private ModouDockWindow dockWin;
    private LinearLayout dockPanel;
    /** 工具坞的玻璃层：半透明基色 + 正文实时模糊背板（0.9.154 调薄成磨砂，模糊透上来） */
    private ModouGlassDrawable dockBg;
    private LinearLayout dockRow1;
    private LinearLayout dockRow2;
    /**
     * 朗读面板在屏上时工具坞让位：收起第二行图标（目录/排版/夜间/更多），
     * 工具坞整体矮一截，面板就能落得更低、和正文之间留出真正的空白，
     * 也不会像膏药一样贴在菜单顶边。
     */
    private boolean dockSqueezed;
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
    private ModouPillSeekBar dockSeek;
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
    /** 底部抽屉所在窗口：搬进独立窗口后由系统实时模糊背后的阅读正文 */
    private Dialog sheetDialog;
    /** 面板是否已经沉到屏幕外（避让状态），防止重复触发动画 */
    private boolean sheetDodging;

    private NativeTheme theme = NativeTheme.of("paper");
    /** 0.9.173：设计语言规格（阅读页只读它决定控件形状 / 材质厚度 / 字号，颜色仍走纸张色板） */
    private DesignSpec rd;

    private DesignSpec rd() {
        if (rd == null) rd = DesignSpec.of(this);
        return rd;
    }

    /** 极简·纪：本轮去控件化只在第三套生效，墨读 / M3 逐像素不变 */
    private boolean mono() {
        return rd().lang == DesignSpec.Lang.MONOLITH;
    }

    /** 极简·纪图标纯色：浅色纯黑、深色纯白，不掺纸张灰 */
    private int monoInk() {
        return theme.dark ? 0xFFFFFFFF : 0xFF000000;
    }

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
                    return size() > 16;
                }
            };

    /** 目录是否已在后台补齐：没有的话章名先空着，靠正文首行推断 */
    private volatile boolean tocLoaded = false;
    private long lastSaveAt = 0;
    private int lastSavedOffset = -1;
    /** 还没落库的章节内偏移；-1 表示「内存与库一致」 */
    private int pendingOffset = -1;
    /** 还没落库的阅读时长（毫秒），随 flush 一起累加进去 */
    private long pendingAddMs = 0;
    /** 上次真正写库时所在的页：用来判断「已经翻了 10 页」 */
    private int lastSavedPage = -1;
    private final android.os.Handler saveHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable saveDebounce = () -> flushProgress();
    /** 上次计入阅读时长的时间点：每次落进度时把「距上次的这段」累加进 readMs */
    private long lastTickAt = 0;
    /** 只在本次进程里提示一次：原生页没有可见的工具栏，得让人知道怎么退出 */

    /* ---------------- 排版状态（与网页版设置键一一对应） ---------------- */

    private int fontSize = 18;          // sp
    private float lineHeight = 2.0f;
    private float paraGap = 1.0f;
    private int marginPx = 24;
    /** 行尾标点悬挂（不压缩）：默认关（行尾句读照常压半字）。两端对齐始终开启，不再设开关 */
    private boolean justifyHang = false;
    /** 隐藏书内图片：默认关。开启后排版直接跳过插图块，纯文字不受影响 */
    private boolean hideImages;
    /**
     * 沉浸阅读时也显示页眉（默认开）：菜单收起后，正文顶部那条 11dp 的浅色小字
     * 会一直在，不用每次点屏幕才知道自己在哪一章。走的是正文自己的页眉带
     * （{@code NativePageView#setHeadFoot}），不额外占屏，也不会和浮层顶栏打架。
     */
    private boolean headImmersive = true;
    /** 上边距：正文顶到页眉带（或状态栏）的间隙，dp。排版面板可调 */
    private int topGapDp = TOP_GAP_DP;
    /** 下边距：正文末行到页脚带的间隙，dp */
    private int botGapDp = BOT_GAP_DP;
    /** 页眉页脚字号（sp）：与小字一起可调，带高会跟着长，绝不切字 */
    private float hfSizeSp = 11f;

    /**
     * 排版面板里每一根滑杆的「活引用」。
     *
     * 套用预设后不能重建面板来刷新滑杆：重建会在点击回调里拆掉整棵 View 树，
     * 之后的滑杆既不响应拖动、也不再写库（用户报的「预设之后滑杆失效」）。
     * 保留引用、直接 setProgress 才是无副作用的路子。
     */
    private static final class SeekBind {
        ModouPillSeekBar sb;
        TextView label;
        int min, max, step;
        Fmt fmt;
        java.util.function.IntSupplier cur;

        SeekBind(ModouPillSeekBar sb, TextView label, int min, int max, int step, Fmt fmt,
                 java.util.function.IntSupplier cur) {
            this.sb = sb;
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.fmt = fmt;
            this.cur = cur;
        }
    }

    private final java.util.List<SeekBind> seekBinds = new java.util.ArrayList<>();
    /**
     * 0.9.156：每个折叠区 content 当前那支展开/收起动画。用独立弱引用表而不是 View.setTag ——
     * tag 已被拖拽行（存 key）和列表行（存 ViewHolder）占用，共用会互相覆盖。
     */
    private final java.util.WeakHashMap<View, android.animation.ValueAnimator> foldAnims =
            new java.util.WeakHashMap<>();
    /** 字号行的数值文本：套用预设后要跟着改 */
    private TextView sizeLabel;
    /** 拖动滑杆时的数值浮窗：面板整块沉到屏外，行首那行数值跟着一起走了 */
    private LinearLayout seekHud;
    private TextView seekHudTitle;
    private TextView seekHudVal;
    private Runnable seekHudHide;
    /** 正文首行到页眉（或屏幕顶）的距离：固定值，排版面板不再提供调节 */
    /**
     * 正文顶端与页眉带的间隙。
     *
     * 14dp 在 18sp 时刚好，但字号越小、首行基线越靠上（首行 baseline = 顶边 − ascent，
     * ascent 随字号缩小），16sp 时字形顶端会顶进页眉带 —— 截图里「第一行字被上边压住」
     * 就是这个。留 20dp 让 16sp 也有余量。
     */
    private static final int TOP_GAP_DP = 20;
    /** 0.9.161：从「阅读纸张」画廊返回，结果带回选中的纸张 id */
    private static final int REQ_PAPER = 71;
    /**
     * 正文末行到页脚带的距离：固定值。
     *
     * 页脚带自己已经占 20dp，正文可用高度是「屏高 − 页脚带 − 这个间隙」。
     * 这一项原来是 24：叠上页脚带，末行下面一共空出 44dp，而一行正文才 32dp 左右，
     * 每页都能再塞一行却塞不下 —— 用户看到的就是「页脚上面空一大块」。
     * 收到 8dp 后（末行底到页脚字顶约 8dp）每页多出一行的机会，又不至于挤在一起。
     */
    private static final int BOT_GAP_DP = 8;
    /** MD3 emphasized（强调过渡）/ decelerate（进入）：与网页版 --flow 同一条曲线 */
    private static final android.view.animation.Interpolator MD3_EMPH =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final android.view.animation.Interpolator MD3_DECEL =
            new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** 标准档的弹簧阻尼感：先过冲再回弹（Overshoot） */
    private static final android.view.animation.Interpolator SPRING =
            new android.view.animation.OvershootInterpolator(1.15f);
    private String font = "song";
    private int weightVal = 500;         // 300/400/500/600
    private String lightTheme = "paper"; // paper/cream/gray
    private boolean darkMode = false;
    /**
     * 文字亮度/柔化系数：1 = 主题原色（夜间即纯白），0.5 = 半透（夜间白字 → 灰）。
     * 按日间/夜间分别记忆：active brightness 是「当前模式正在用的那份」，
     * 另存 brightnessLight / brightnessDark 两份，切模式互不影响、各自恢复。
     */
    private float brightness = 1.0f;       // 当前生效（日间或夜间之一）
    private float brightnessLight = 1.0f;  // 日间存档键 readerBrightnessLight
    private float brightnessDark = 1.0f;   // 夜间存档键 readerBrightnessDark
    private boolean keepAwake = true;

    /** 翻页方式：false 左右翻页 / true 上下滚动（网页版 readerPageMode） */
    private boolean scrollMode = false;
    /**
     * 点击区域方案：classic 左侧上一页 / left 左手（左=下一页）/ single 单手（左=呼出菜单）。
     * 用字符串存，将来加第四种不用改数据库。
     */
    private String tapMode = "classic";
    /** 屏幕最左/最右的安全区（dp）：防单手握持时掌根误触 */
    private static final int EDGE_GUARD_DP = 14;
    /** 动效三档：0 关闭 / 1 简约（仅淡入）/ 2 MD3 标准（弹簧上滑 + 微反馈） */
    private int animGrade = 2;
    /** 系统「移除动画」（无障碍）已开启：此时任何自绘动画都应瞬切，否则体验断层 */
    private boolean systemNoAnim;
    /** 监听系统「移除动画」开关，即时跟随（不重启 Activity） */
    private final android.database.ContentObserver animScaleObs =
            new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) { syncSystemAnim(); }
            };

    /** 系统 ANIMATOR_DURATION_SCALE 为 0（无障碍「移除动画」）时强制瞬切；不改动用户手动档位 */
    private int effectiveAnimGrade() {
        return systemNoAnim ? 0 : animGrade;
    }

    /** 读取系统动画时长缩放：0 = 用户要求移除所有动画。改了即时跟随（onResume 重读 + ContentObserver） */
    private void syncSystemAnim() {
        float s = 1f;
        try {
            s = android.provider.Settings.Global.getFloat(getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable t) {
            s = 1f; // 无权限 / 旧系统：当 1，保留用户档位
        }
        systemNoAnim = (s <= 0f);
    }
    /** 当前排版面板的 sheet 内层面板（带 ModouGlassDrawable 背景，供流光用） */
    private View sheetPanel;
    /** 当前排版面板的 sheet 整块磨砂外壳（FrostBox），入场/避让/重放动画作用在它身上 */
    private View sheetShell;
    /** 面板背后的柔光晕（景深用），随面板升起/下沉同步位移 */
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
        ModouTransition.push(from, i);
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

    /** 统一返回转场：三套语言各自的 pop 动作（见 {@link ModouTransition}） */
    @Override
    public void finish() {
        super.finish();
        ModouTransition.back(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        // 通知栏/锁屏/线控的回调：MediaSession 与通知按钮最终都落到这三件事上
        TtsService.listen(new TtsService.Listener() {
            @Override
            public void onTtsToggle() {
                runOnUiThread(NativeReaderActivity.this::ttsTogglePlay);
            }

            @Override
            public void onTtsPrev() {
                runOnUiThread(NativeReaderActivity.this::ttsPrevSentence);
            }

            @Override
            public void onTtsNext() {
                runOnUiThread(() -> {
                    if (ttsQueue.isEmpty() || tts == null) return;
                    ttsPaused = false;
                    syncTtsToggle();
                    ttsNext();
                });
            }
        });

        JSONObject o = parse(getIntent().getStringExtra("payload"));

        fontSize = (int) o.optDouble("fontSize", 18);
        lineHeight = (float) o.optDouble("lineHeight", 2.0);
        // 旧版默认是 1.9；没动过滑杆的用户读到的就是 1.9 —— 迁到新的 2.0（更透气），
        // 之后只要用户自己调过（写入别的值）就不再动它
        if (Math.abs(lineHeight - 1.9f) < 0.01f) lineHeight = 2.0f;
        paraGap = (float) o.optDouble("paraGap", 1.0);
        // 历史存档里可能存着 1.4 甚至更小的行距（早期滑杆下限更低），
        // 读到就夹到安全线，否则进书第一屏就是叠字
        lineHeight = Math.max(MIN_LINE_MUL, Math.min(2.8f, lineHeight));
        paraGap = Math.max(MIN_PARA_GAP, Math.min(2.4f, paraGap));
        marginPx = o.optInt("margin", 16);
        justifyHang = o.optBoolean("justifyHang", false);
        // 隐藏书内图片：与网页版共用同一个设置键，进阅读页直接读库，下次进仍然生效
        hideImages = "1".equals(ModouStore.get(this).settingStr("readerHideImages", "0"));
        // 沉浸页眉：默认开（"0" 才关），老用户没有这个键也按开处理
        headImmersive = !"0".equals(ModouStore.get(this).settingStr("readerHeadImmersive", "1"));
        // 上下边距与页眉页脚字号：老存档没有这三个键，用当前默认值
        ModouStore gs = ModouStore.get(this);
        try {
            topGapDp = Math.max(0, Math.min(48, Integer.parseInt(gs.settingStr("readerTopGap", String.valueOf(TOP_GAP_DP)))));
            botGapDp = Math.max(0, Math.min(48, Integer.parseInt(gs.settingStr("readerBotGap", String.valueOf(BOT_GAP_DP)))));
            hfSizeSp = Math.max(9f, Math.min(20f, Float.parseFloat(gs.settingStr("readerHfSize", "11"))));
        } catch (Exception ignore) {
            /* 脏值：保持默认 */
        }
        // 两端对齐始终开启（不再设开关），无需迁移旧存档的「关」状态。
        ModouStore ms = ModouStore.get(this);
        String layVer = ms.settingStr("readerLayoutVer", "");
        // v4：整体再松一档（紧凑 1.75 / 标准 1.85 / 宽松 2.05）。
        // 库里凡是低于 1.7 的行距都还停在旧观感上，一次性抬到 1.85，打开即生效，不必再点预设。
        if (!"4".equals(layVer) && !"5".equals(layVer)) {
            if (lineHeight < 1.7f) lineHeight = 1.85f;
        }
        // v5：老存档里停在 1.7 上下的行距（1.85 之前那批）读起来仍然「字贴字」，
        // 一次性抬到 1.9。只抬一次，之后用户怎么调都不再覆盖。
        if (!"5".equals(layVer)) {
            ms.putSettingStr("readerLayoutVer", "5");
            if (lineHeight < 1.75f) lineHeight = 1.9f;
        }
        font = o.optString("font", "song");
        weightVal = o.optInt("weight", 500);
        lightTheme = o.optString("theme", "paper");
        if (!"paper".equals(lightTheme) && !"cream".equals(lightTheme) && !"gray".equals(lightTheme)) {
            lightTheme = "paper";
        }
        // 亮度：按日间/夜间分别记忆。先读旧的全局键 readerBrightness 作迁移兜底——
        // 老用户之前只有一个全局值，升级后两个模式各持一份相同值（互不干扰、再各自微调）；
        // 新用户两键都缺，直接 1.0。
        float legacyB = 1.0f;
        try {
            legacyB = Float.parseFloat(gs.settingStr("readerBrightness", "1.0"));
        } catch (Exception ignore) {
            legacyB = 1.0f;
        }
        legacyB = Math.max(0.5f, Math.min(1.0f, legacyB));
        try {
            brightnessLight = Float.parseFloat(gs.settingStr("readerBrightnessLight", String.valueOf(legacyB)));
        } catch (Exception ignore) {
            brightnessLight = legacyB;
        }
        try {
            brightnessDark = Float.parseFloat(gs.settingStr("readerBrightnessDark", String.valueOf(legacyB)));
        } catch (Exception ignore) {
            brightnessDark = legacyB;
        }
        brightnessLight = Math.max(0.5f, Math.min(1.0f, brightnessLight));
        brightnessDark = Math.max(0.5f, Math.min(1.0f, brightnessDark));
        // 翻页方式与页眉页脚：与网页版共用同一组设置键，网页里改过的设置原生要能接上
        scrollMode = "scroll".equals(ModouStore.get(this).settingStr("readerPageMode", "page"));
        tapMode = ModouStore.get(this).settingStr("readerTapMode", "classic");
        // 屏幕常亮从排版面板搬到「更多」：它是阅读时的环境偏好，不是排版参数
        keepAwake = !"0".equals(ModouStore.get(this).settingStr("readerKeepAwake", "1"));
        if (!"left".equals(tapMode) && !"single".equals(tapMode)) tapMode = "classic";
        try {
            animGrade = Integer.parseInt(ModouStore.get(this).settingStr("uiAnimGrade", "2"));
        } catch (Exception e) {
            animGrade = 2;
        }
        if (animGrade < 0 || animGrade > 2) animGrade = 2;
        syncSystemAnim(); // 跟随系统「移除动画」：开则强制瞬切，关则保留用户档位
        readHeadFoot();
        // 全局深浅决定阅读页是深色还是浅色；阅读页只额外记「浅色用哪种纸色」
        darkMode = ModouUi.isDark(this);
        theme = NativeTheme.of(darkMode ? "dark" : lightTheme);
        // active 亮度取当前模式那一份（darkMode 此刻已确定）
        brightness = darkMode ? brightnessDark : brightnessLight;

        // 开屏那一帧是 windowBackground：先染成阅读底色，避免夜间模式闪一下白
        getWindow().setBackgroundDrawable(new ColorDrawable(theme.bg));
        applyBars();
        if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applySystemBarIcons();

        fromNative = o.optBoolean("fromNative", false);
        bookId = o.optString("bookId", "");
        if (pageView != null) pageView.setCacheBook(bookId);
        bookTitle = o.optString("bookTitle", "");
        index = o.optInt("index", 0);
        total = o.optInt("total", 0);
        chapterTitle = o.optString("title", "");
        titles = strArray(o.optJSONArray("titles"));

        buildUi();
        pageView.setBright(brightness);

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
        lastSavedPage = pageView == null ? -1 : pageView.page();
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
        syncSystemAnim();
        try {
            getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(
                            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE),
                    false, animScaleObs);
        } catch (Throwable t) { /* 无权限：退化为仅启动时读一次 */ }
        readBattery();
        hfHandler.removeCallbacks(hfTick);
        if (footMask != 0) hfHandler.postDelayed(hfTick, 30000);
        // 回到阅读页：若工具坞本应显示，把独立窗口重新挂出来（onPause 时收起了）。
        if (dockShowing) showDock();
    }

    /**
     * 从「阅读纸张」画廊返回（0.9.161）：画廊已把偏好落盘，这里走原有的
     * {@code applyTheme} 做完整刷新（正文重排、系统栏、工具坞、排版面板一起换色）。
     * 不在画廊里直接改阅读页 UI —— 阅读页是独立 Activity，且 7447 行的刷新链路
     * 本来就在 {@code applyTheme} 里，复用它比再写一份安全。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PAPER && resultCode == RESULT_OK && data != null) {
            String name = data.getStringExtra(PaperGalleryActivity.EXTRA_PAPER);
            if (name != null) applyTheme(name);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退出阅读页（或被打断）：后台杂活可以继续了
        try {
            getContentResolver().unregisterContentObserver(animScaleObs);
        } catch (Throwable ignore) { /* 没注册过也安全 */ }
        ModouStore.setQuiet(false);
        stopAuto();
        saveProgress(true);
        hfHandler.removeCallbacks(hfTick);
        // 离开阅读页（或被打断 / 跳去书籍详情·设置）：先把工具坞窗口收起来，
        // 否则这扇独立窗口会浮到下一个 Activity 上面（书架底栏同源处理）。
        if (dockWin != null) dockWin.hide();
    }

    @Override
    protected void onDestroy() {
        // 防抖队列里可能还压着一个没写的进度：销毁前清掉并补写
        saveHandler.removeCallbacks(saveDebounce);
        flushProgress();
        if (sInstance == this) sInstance = null;
        TtsService.listen(null);
        // 退出阅读页就把常驻通知收掉：不放声音还挂着一条「正在听书」比没有更怪
        TtsService.stop(this);
        stopAuto();
        cancelTtsTimer();
        hfHandler.removeCallbacks(hfTick);
        if (pendingStyle != null) styleHandler.removeCallbacks(pendingStyle);
        // 退出阅读页：工具坞独立窗口必须 dismiss，否则会浮到书架 / 下一个 Activity 之上
        if (dockWin != null) {
            dockWin.dismiss();
            dockWin = null;
        }
        // 0.9.153：面板窗口同样要 dismiss，否则会浮到书架 / 下一个 Activity 之上
        if (sheetDialog != null) {
            try { sheetDialog.dismiss(); } catch (Throwable ignore) { /* 已销毁 */ }
            sheetDialog = null;
        }
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

    /** 顶栏圆钮底：浅色 7% 墨、深色 10% 白，圆形按压水波（有界，不溢出成方块） */
    private Drawable circleBtnBg() {
        // 毛玻璃感：不打「墨色半透明」（那层压在米色纸面上会发土黄），
        // 改成半透白 + 一圈更亮的白描边，任何主题下都只是把底色提亮一点点。
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(theme.dark ? 0x16FFFFFF : 0x33FFFFFF);
        fill.setStroke(Math.max(1, dp(1)), theme.dark ? 0x12FFFFFF : 0x26FFFFFF);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        theme.dark ? 0x2EFFFFFF : 0x1A000000), fill, mask);
    }

    /** 左上返回：保存进度后直接离开阅读页（不走 onBackPressed 的分层返回） */
    private void quitReader() {
        try {
            stopAuto();
        } catch (Throwable ignore) {
            /* 自动阅读没开 */
        }
        try {
            saveProgress(true);
        } catch (Throwable ignore) {
            /* 位置写不进去也不能把用户困在阅读页 */
        }
        try {
            if (sBridge != null) {
                sBridge.evalJs("window.__modouNativeBack && window.__modouNativeBack()");
            }
        } catch (Throwable ignore) {
            /* 网页会话已经没了 */
        }
        finish();
    }

    /* ---------------- UI ---------------- */

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(theme.bg);

        pageView = new NativePageView(this);
        pageView.setCacheBook(bookId);   // 页表缓存按书隔离，切书再切回来才命得中
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

        // 顶部沉浸栏：返回圆钮 + 书名/章次两行（对齐网页版 .rd-head）
        head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // 与正文同一个 theme.bg，并且不留投影 —— 原来 3dp 的 elevation 会在栏下压出一条
        // 灰色硬边，上半屏看着像贴了一条「系统状态栏」，和纸面割裂。
        head.setBackgroundColor(theme.bg);
        head.setElevation(0f);
        // 左右安全边距：与正文左右边距对齐（正文默认 24dp），至少 16dp。
        // 原来写死 6dp，圆钮几乎贴着屏幕边，是全站最局促的一处。
        head.setPadding(headInset(), 0, headInset(), 0);

        // 左上：圆形细线 < 箭头。原来是 36dp 的裸图标，压在纸面上看不出边界；
        // 现在给一圈淡底，和右上角的圆钮、底部浮岛是同一套圆形容器语言。
        ImageView back = new ImageView(this);
        headBack = back;
        back.setScaleType(ImageView.ScaleType.CENTER);
        if (mono()) {
            // 极简·纪：无圆底，1.5dp 纯黑/纯白细线图标直接悬浮
            back.setImageDrawable(new ModouGlyph(ModouGlyph.BACK, monoInk(),
                    dp(20), ModouUi.dp(this, 1.5f)));
            back.setBackground(null);
        } else {
            back.setImageDrawable(new ModouGlyph(ModouGlyph.BACK, theme.text,
                    dp(18), ModouUi.dp(this, 1.8f)));
            back.setBackground(circleBtnBg());
        }
        // 直接退出阅读页。以前走 onBackPressed()，而工具坞此时是开着的，
        // 它只会「收起工具坞」并 return —— 点了像没反应。现在无论如何都回书架。
        back.setOnClickListener(v -> quitReader());
        // 40dp + OVAL 底：与详情页的返回/更多同一规格，是真正的正圆
        head.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

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

        // 右上：换源圆钮（与返回钮同款 40dp 正圆）。阅读页原地弹换源面板，不用跳书籍详情页
        ImageView swap = new ImageView(this);
        swap.setScaleType(ImageView.ScaleType.CENTER);
        swap.setImageDrawable(tinted(R.drawable.ic_book_swap, mono() ? monoInk() : theme.text));
        if (!mono()) swap.setBackground(circleBtnBg());
        swap.setOnClickListener(v -> SourceSwap.show(NativeReaderActivity.this));
        swap.setContentDescription("换源");
        head.addView(swap, new LinearLayout.LayoutParams(dp(40), dp(40)));

        head.setOnClickListener(v -> showMenu());
        // 高度比「状态栏 + 46dp」少 6dp：正文顶（padT）就在 46dp 处，而中文字形的墨迹
        // 会比行框上沿再高出 2~4dp。顶栏底板正好压到 46dp 时，第一行的字头会被这条
        // 底板削平（菜单打开时尤其明显）；让出 6dp，墨迹落在顶栏与正文之间的留白里。
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46) + statusBarHeight() - dp(6));
        hp.topMargin = 0;
        head.setPadding(headInset(), statusBarHeight(), headInset(), 0);
        head.setVisibility(View.GONE); // 与 headShowing=false 一致：点正文中部才出现
        root.addView(head, hp);

        /* 沉浸常驻页眉。
         *
         * 以前「页眉」是画在正文视图里的（NativePageView.drawHeadFoot），可它只在
         * headMode != 0 时才画，而 headMode 默认是「无」—— 开关拨到开、把菜单收起，
         * 顶部依旧干干净净，用户看到的就是「开关不生效」。
         * 现在改成 Activity 自己的一块浮层：只要开关开着、菜单收起，它就一直在，
         * 与 headMode 怎么设无关；颜色是正文色再压到 55% 透明，落在纸面上是淡灰，
         * 不抢标题的位置，也不会在夜里变成一道黑杠。 */
        miniHead = new TextView(this);
        miniHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        miniHead.setGravity(Gravity.CENTER);
        miniHead.setSingleLine(true);
        miniHead.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniHead.setIncludeFontPadding(false);
        miniHead.setPadding(dp(56), 0, dp(56), 0);
        miniHead.setTextColor(ModouUi.alpha(theme.text, 0x8C));
        miniHead.setOnClickListener(v -> showMenu());
        miniHead.setVisibility(View.GONE);
        FrameLayout.LayoutParams mhp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
        mhp.gravity = Gravity.TOP;
        mhp.topMargin = statusBarHeight();
        root.addView(miniHead, mhp);

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

        // 整条书籍记录（含几千章目录）第一次解析要几百毫秒：趁进阅读页的空档
        // 在后台预载，别等用户点「更多 / 编辑」时才在主线程上卡一下
        io.execute(this::bookObj);
    }

    private LinearLayout buildSelBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));
        GradientDrawable g = new GradientDrawable();
        g.setColor(theme.dark ? 0xFF2A2C31 : theme.card);
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

    /**
     * 点击区域 + 边缘安全区下发。
     *
     * <p>安全区只在**边缘那一条**吞事件：正文里正常点击一律照旧，
     * 单手握持时掌心压住屏幕边上不会再把书翻过去。
     */
    private void applyTapZones() {
        if (pageView == null) return;
        int l, r;
        if ("left".equals(tapMode)) {          // 左手：左边翻下一页，右边回上一页
            l = 1;
            r = 0;
        } else if ("single".equals(tapMode)) { // 单手：左边呼出菜单，右边下一页
            l = 2;
            r = 1;
        } else {                               // 经典
            l = 0;
            r = 1;
        }
        pageView.setTapZones(l, r);
        pageView.setEdgeGuardPx(dp(EDGE_GUARD_DP));
    }

    /** 把当前排版状态套到正文视图（任一项变化都走这里，避免参数散落不一致） */
    private void applyStyle() {
        float d = getResources().getDisplayMetrics().density;
        // 页眉页脚各占一条带，从正文可用高度里实扣。顶栏是浮层（点中间才出现），
        // 不再像之前那样常驻预留 54dp —— 那会让正文上方空掉一大块。
        // 只看开关不看文案：章节还没加载完时 headText() 是空的，若按空文案不预留，
        // 章名一到页眉就凭空压到正文第一行上。
        // 页眉带 28dp + 间隙 12dp：正文顶只比上一版下移 4dp（不多占白），
        // 但页眉字底到正文顶从 14dp 变成 22dp —— 视觉上就是「不再压着首行」。
        // 页眉带：开关开着（常驻小字）或用户本来就设了页眉，都要从正文里实扣 28dp。
        // 以前只看 effHeadMode()，而 headMode 默认「无」，扣不到高度 —— 页眉就算画出来
        // 也会压在正文第一行上，所以干脆一起没画。
        boolean headBand = !scrollMode && headMode != 0;
        // 页眉/页脚带高 = 字形高 + 极小的呼吸缝，不再用「max(28dp, 字号+12dp)」兜底。
        // 旧写法给了 28dp / 20dp 的下限：11sp 时实际字高只有约 13dp，多出来的十几 dp
        // 全成了正文与页眉页脚之间的死白 —— 下边距拖到 0 也贴不住页脚，这就是「幽灵留白」。
        // 现在：
        //   页眉带 = 字高 + 2dp(顶) + 4dp(字底到正文顶)
        //   页脚带 = 字高 + 4dp(字底到屏底)，正文底正好等于页脚字形的上边缘
        float hfPx = hfSizeSp * d;
        // 用真实字形高度（ascent→descent）而不是「字号 × 1.16」估算：
        // 不同字体的行框差 1~2dp，估算偏小就会让页脚字顶压住最后一行。
        Paint hfMeasure = new Paint(Paint.ANTI_ALIAS_FLAG);
        hfMeasure.setTextSize(hfPx);
        Paint.FontMetrics hfm = hfMeasure.getFontMetrics();
        int hfGlyph = Math.round(hfm.descent - hfm.ascent);
        int headH = headBand ? hfGlyph + dp(6) : 0;
        // 页脚只留 2dp：它没有任何「上方留缝」的必要 —— 下边距滑杆就是那条缝，
        // 用户在滑杆上要多少给多少，代码里不再私藏。
        int footH = (!scrollMode && footMask != 0) ? hfGlyph + dp(2) : 0;
        // 上/下边距由滑杆给出（0–48dp），正文可用高度随之变小，setStyle 的 pad 变化会触发重排
        int topGap = Math.round(topGapDp * d);
        int botGap = Math.round(botGapDp * d);
        // 先静默改页眉页脚配置，再由 setStyle 的 pad 变化触发唯一一次重排
        pageView.setScrollMode(scrollMode);
        applyTapZones();
        // 沉浸页眉由 miniHead 画：pageView 那条只在 headMode != 0 时画，两套同时开会叠字
        pageView.setHeadFoot(headImmersive ? 0 : headMode, footMask);
        pageView.setFootBandH(footH);
        pageView.setHeadBandH(headH);
        pageView.setHeadFootSize(hfPx);
        // 隐藏图片要在 setStyle 之前：它自己会按当前偏移重排一次，
        // 后面 setStyle 若样式没变就不会再排第二次
        pageView.setHideImages(hideImages);
        pageView.setStyle(theme, fontSize * d, lineHeight, font, weightVal, paraGap,
                Math.round(marginPx * d),
                statusBarHeight() + headH + topGap,
                Math.round(marginPx * d),
                footH + botGap, justifyHang);
        // 正文左右边距可能刚被滑杆改过：顶栏跟着走，圆钮左边缘始终与正文左边缘同一条竖线
        if (head != null) head.setPadding(headInset(), statusBarHeight(), headInset(), 0);
        // 开关拨动后要立刻体现：开 → 收起菜单时顶部那行小字在位；关 → 收回
        syncMiniHead();
    }

    /**
     * 实际生效的页眉模式。
     *
     * 以前这里在「沉浸页眉」开着时把 headMode=0（不显示）强行改成 1（章节名），
     * 于是用户明明选了「不显示」，菜单一收起顶上还是冒出章节名 —— 配置项写了不认。
     * 现在一律返回用户的选择，沉浸开关只决定「谁来画」（浮层小字 / 正文页眉带），
     * 不再篡改内容。
     */
    private int effHeadMode() {
        return headMode;
    }

    /**
     * 页眉文案（空则不占位）。
     *
     * 章名走 {@link #headChapterName()} 而不是裸的 chapterTitle：进书瞬间 title 还没到，
     * 裸字段是空串，浮层页眉会被判成「没内容」直接隐藏，之后再也没机会重新显示。
     * headChapterName() 有「章名 → 目录章名 → 第 N 章」三级兜底，永远给得出东西。
     */
    private String headText() {
        String b = bookTitle == null ? "" : bookTitle.trim();
        String c = headChapterName();
        switch (effHeadMode()) {
            case 1:
                return c;
            case 2:
                return b;
            case 3:
                return b.isEmpty() ? c : b + " · " + c;
            default:
                return "";
        }
    }

    /** 把书名/章名/章序号喂给正文视图，页眉页脚与全书进度要用 */
    private void syncHeadFoot() {
        if (pageView == null) return;
        pageView.setHeadFootText(bookTitle, headChapterName(), index, total);
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

    /** 主题切换：更新配色 + 系统栏 + 沉浸条，并同步给全局与网页版 */
    private void applyTheme(String name) {
        boolean toDark = "dark".equals(name);
        darkMode = toDark;
        if (!toDark) lightTheme = name;
        theme = NativeTheme.of(toDark ? "dark" : lightTheme);
        // 文字亮度按模式分别记忆：切到哪边就用哪边的值，不互相覆盖
        brightness = toDark ? brightnessDark : brightnessLight;
        pageView.setBright(brightness);
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
        // 0.9.161：纸张偏好 + 全局深浅联动收口到 NativeTheme.save（与纸张画廊同一入口）。
        // 原先这里手写 4 行（appTheme / readerLightTheme / saveThemeMode / JS 钩子），
        // 画廊页也要写一遍；抽成一处后「少写一项导致网页版不同步」不再可能发生。
        NativeTheme.save(this, name);
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
            if (headBack != null) {
                if (mono()) {
                    headBack.setImageDrawable(new ModouGlyph(ModouGlyph.BACK, monoInk(),
                            dp(20), ModouUi.dp(this, 1.5f)));
                    headBack.setBackground(null);
                } else {
                    headBack.setImageDrawable(new ModouGlyph(ModouGlyph.BACK, theme.text,
                            dp(17), ModouUi.dp(this, 1.7f)));
                    headBack.setBackground(circleBtnBg());
                }
            }
            if (header != null) header.setTextColor(theme.text);
            if (headerSub != null) headerSub.setTextColor(theme.sub);
        }
        if (miniHead != null) miniHead.setTextColor(ModouUi.alpha(theme.text, 0x8C));
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

    /** 程序化跳转（搜索“上一处/下一处”、书签、目录）：静默切换，不弹加载遮罩。
     *  旧页一直保留到新章在后台排版完成，再无缝替换，绝无白屏/半成品帧。 */
    private void requestChapter(int idx, int offset, boolean silent) {
        silentJump = silent;
        requestChapterCore(idx, offset);
    }

    /** @param offset 章内字符偏移（书签跳转用）；换章时由 pendingJump 带进异步回调 */
    private void requestChapter(int idx, int offset) {
        silentJump = false;
        requestChapterCore(idx, offset);
    }

    /** 取章核心逻辑（silentJump 已由上层设好） */
    private void requestChapterCore(int idx, int offset) {
        if (idx < 0) {
            // 第一章再往回翻：直接收掉跨章等待，否则页面会停在拖出去的位置等 6 秒
            if (pageView != null) pageView.cancelCrossChapter();
            return;
        }
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
                if (pageView != null) pageView.cancelCrossChapter();   // 拖着的那一页弹回原位
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
        if (!silentJump) {
            loading.setText(want > index ? "加载下一章…" : "加载上一章…");
            loading.setVisibility(View.VISIBLE);
        }
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
                // ③ 库里没有这一章：走书源服务（常驻无头 WebView），仍取不到就说明没有可用规则。
                // 网页版阅读页已退役，没有「网页桥」可取章，直接说结论，别让人对着转圈猜。
                requestViaSource(want, () -> {
                    if (gen != chapterGen) return;
                    // 本地导入的书没有书源，"取不到"就是文件里真的没有正文 ——
                    // 说清楚比让人盯着「加载中」转圈强
                    if (bookId != null && bookId.startsWith(LocalImport.LOCAL)) {
                        toast("这本书的正文还没恢复：到「本地书籍」页点「一键补齐」，正文会重新解析，阅读进度不变");
                        if (pageView != null) pageView.cancelCrossChapter();
                        return;
                    }
                    // 原生引擎吃不下这个站点的结构：别让人对着空白页反复点，
                    // 直接给一句能行动的建议（换源通常几秒就能解决）
                    toast("本书格式暂不支持，请尝试换源");
                    if (pageView != null) pageView.cancelCrossChapter();
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
        if (idx == index) return; // 当前章已在显示，排版缓存由 relayout 自己落，别重复
        final int fi = idx;
        bg.execute(() -> {
            try {
                List<Paras.Block> bl = readChapterBlocks(fi, purifyTitle(chapterNameOf(fi)));
                // 相邻章正文到内存后，顺手把它提前排版落进页表缓存：跨章翻页时 relayout
                // 直接命中，对面不再空白 / 延迟出现（precacheChapter 内部只作用于缓存，
                // 不影响当前章显示，且仅隐藏图片模式生效）
                if (bl != null && !bl.isEmpty() && pageView != null) {
                    pageView.precacheChapter(bl, chapterNameOf(fi));
                }
            } catch (Throwable ignore) {
                /* 预取失败不影响阅读 */
            }
        });
    }

    /**
     * 搜索完成后，把命中所在章节预取到内存（受 LRU 窗口约束）。
     * 这样「上一处/下一处」跨章跳时直接命中缓存：零读库、零加载遮罩、旧页保留到新章排完，
     * 丝滑无闪 —— 等价于 legado 的「预排版相邻/目标章节」。
     */
    private void prefetchSearchHits(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) return;
        ArrayList<Integer> seen = new ArrayList<>();
        for (SearchHit h : hits) {
            if (h == null) continue;
            int ci = h.index;
            if (ci < 0 || seen.contains(ci)) continue;
            seen.add(ci);
            if (seen.size() >= 16) break; // 与 blockCache 的 LRU 窗口对齐，别白读
            prefetchChapter(ci);
        }
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
        if (!silentJump) {
            loading.setText(idx > index ? "加载下一章…" : "加载上一章…");
            loading.setVisibility(View.VISIBLE);
        }
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
                    // 「能读正文」是最硬的可用性证据：不记这一笔，书源列表会一直显示
                    // 「近 7 天未成功过」，用户就会把还能用的源一键禁用掉
                    SourceHealth.fail(getApplicationContext(), book.optString("sourceUrl", ""),
                            SourceHealth.classifyText(err), err);
                    runOnUiThread(() -> {
                        if (gen != chapterGen) return;
                        loadingChapter = false;
                        root.removeCallbacks(loadTimeout);
                        hideLoading();
                        toast(err);
                    });
                    return;
                }
                // 抓到正文 = 这个源活着：补记一次成功（健康度以前只统计搜书，会误标成坏源）
                SourceHealth.ok(getApplicationContext(), book.optString("sourceUrl", ""));
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

    /* ---------------- 换源 Host 实现（阅读页原地弹换源面板） ---------------- */

    @Override public Activity getContext() { return this; }
    @Override public int textColor() { return theme.text; }
    @Override public int subColor() { return theme.sub; }
    @Override public JSONObject getBook() { return bookObj(); }
    @Override public String getBookId() { return bookId; }

    @Override
    public boolean isLocalBook() {
        JSONObject b = bookObj();
        String su = b == null ? "" : b.optString("sourceUrl", "");
        return su.isEmpty() || "local://".equals(su);
    }

    @Override
    public boolean ensureFullRecord() {
        return bookObj() != null;
    }

    @Override
    public void saveBook() {
        try {
            ModouStore.get(this).put("books", bookCache.toString());
        } catch (Exception ignore) {
            /* 写不进下次再写 */
        }
    }

    @Override
    public SourceSwap.OldPos readOldPosition() {
        SourceSwap.OldPos p = new SourceSwap.OldPos();
        p.idx = index;
        p.total = total;
        p.title = (titles != null && index >= 0 && index < titles.length) ? titles[index] : "";
        return p;
    }

    @Override
    public void afterSourceApplied(SourceSwap.OldPos old, JSONArray chapters) {
        // 换源后直接重建阅读页：复用全部初始化（读进度、定位、拉正文、重载目录），零新增风险
        int hit = 0;
        if (chapters != null && chapters.length() > 0) {
            hit = SourceSwap.matchByTitle(old.title, chapters);
            if (hit < 0) {
                hit = Math.max(0, Math.min(chapters.length() - 1,
                        Math.round((float) old.idx / Math.max(1, old.total) * chapters.length())));
            }
        }
        try {
            ModouStore.get(this).put("books", bookCache.toString());
        } catch (Exception ignore) {
            /* 写不进下次再写 */
        }
        try {
            ModouStore.get(this).putProgress(bookId, hit, 0, 0);
        } catch (Exception ignore) {
            /* 写不进就下次从旧位置开始 */
        }
        recreate();
    }

    @Override
    public void centerToast(String s) {
        ModouUi.centerToast(this, s);
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
    /** 听书面板的玻璃材质：与工具坞同一套（半透明基色 + 边缘泛光），不再有抓屏背板 */
    private ModouGlassDrawable ttsBarBg;
    private TextView ttsInfo;
    private TextView ttsRateChip;      // 语速胶囊：显示当前值，点开语速设置
    private TextView ttsTimerChip;     // 定时胶囊：显示当前值，点开定时设置（可自由输入）
    private TextView ttsToggleBtn;     // 暂停/播放按钮（圆形图标钮）
    private TextView ttsToggleLabel;   // 暂停/播放按钮下方的小字
    private boolean ttsPaused;
    private int ttsTimerMin = 0;
    /**
     * 「播完 N 章后停」：0 关，1 播完本章，3 播完 3 章。
     * 与分钟定时互斥 —— 两个都开就变成「先到先停」，用户预期说不清。
     */
    private int ttsStopChapters = 0;
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
        try {
            ttsStopChapters = Integer.parseInt(
                    ModouStore.get(this).settingStr("ttsStopChapters", "0"));
        } catch (Exception ignore) {
            ttsStopChapters = 0;
        }
        if (ttsStopChapters < 0) ttsStopChapters = 0;
        if (ttsStopChapters > 0) ttsTimerMin = 0; // 两种定时只留一种
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
        TtsService.start(this);
        askNotifPermission();
        syncTtsMedia();
        // startForegroundService 是异步的：这一刻服务可能还没 onCreate，
        // 兜一次 400ms 后再说，免得通知顶着「轻阅」这个默认标题
        root.postDelayed(this::syncTtsMedia, 400);
    }

    /**
     * Android 13 起通知要用户点头才显示。听书是「看不见界面但需要反馈」的场景，
     * 没这个权限通知卡片根本不出现，用户会以为后台控制没做。
     */
    private void askNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0x7A);
        } catch (Throwable ignore) {
            /* 拒绝就给不了通知，不影响朗读本身 */
        }
    }

    /** 把当前书/章/句进度推给通知栏与锁屏（服务没起就什么都不做） */
    private void syncTtsMedia() {
        if (!ttsSession) return;
        String chap = chapterTitle == null ? "" : chapterTitle;
        String sub = chap;
        if (ttsAt >= 0 && !ttsQueue.isEmpty()) {
            sub = sub + " · " + (ttsAt + 1) + "/" + ttsQueue.size();
        }
        TtsService.update(bookTitle == null ? "" : bookTitle, sub, !ttsPaused);
    }

    /** 上一句（面板按钮与通知共用）：回退两句再 next = 上一句；已在第一句则重读本句 */
    private void ttsPrevSentence() {
        if (tts == null || ttsQueue.isEmpty() || ttsAt < 0) return;
        ttsPaused = false;
        syncTtsToggle();
        ttsAt = Math.max(-1, ttsAt - 2);
        tts.speak(ttsQueue.get(ttsAt + 1), ttsRate, ttsAt + 1);
        ttsAt++;
        syncTtsChips();
        syncTtsMedia();
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
            // 「播完 N 章停」：先把本次额度扣掉，扣到 0 就不再往下读
            if (ttsStopChapters > 0) {
                ttsStopChapters--;
                if (ttsStopChapters == 0) {
                    stopTts();
                    ttsAt = -1;
                    toast("定时：本章已读完，停止朗读");
                    return;
                }
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
        syncTtsMedia();
    }

    private void stopTts() {
        cancelTtsTimer();
        ttsStopChapters = 0;
        TtsService.stop(this);
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
        // 与工具坞**同一块玻璃**：同样的基色透明度、同样的 22dp 圆角、同样的 1dp 泛光描边。
        // 以前这里是 99% 白的实心板，压在 70% 透明的工具坞上方，两块材质打架，
        // 看着像两张膏药贴在一起。
        // 只圆上面两角：底边要和底栏严丝合缝地拼在一起，底角一圆，接缝处就露出两个
        // 月牙形的正文 —— 那正是「漏出一条缝」的真正来源（不是 margin，是圆角缺口）
        ttsBarBg = new ModouGlassDrawable(
                ModouUi.alpha(theme.card, 0xF2), dp(22), true);
        ttsBarBg.setRimWidth(Math.max(1, dp(1)));
        // 0.9.138 材质补全：5% 纯白薄纱（深色模式换纯黑），玻璃才「亮」而不是死色块。
        ttsBarBg.setVeil(theme.dark ? 0x0D000000 : 0x0DFFFFFF);
        bar.setBackground(ttsBarBg);
        // 无投影：面板和底栏是一条接缝，4dp 的 elevation 会在缝上打一道灰边，
        // 看着就是「中间漏了一条」。层级靠添加顺序保证（面板后加，压在上面）
        bar.setElevation(0f);
        bar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                // 轮廓必须跟玻璃**同一个形状**：以前这里写死成四角圆角，
                // setClipToOutline 又把已经切成直角的底边裁回圆角 ——
                // 于是底栏顶上永远露着两个月牙形的正文，看着就是「有一条缝」
                if (ttsBarBg != null) ttsBarBg.getOutline(o);
            }
        });
        bar.setClipToOutline(true);
        bar.setClickable(true);
        // 统一网格：左 16dp 是所有内容共用的左边线（标题、胶囊、上一句），
        // 右 8dp 留给 X 的触摸区，视觉右边距由按钮自己撑到 16dp
        bar.setPadding(dp(16), dp(12), dp(8), dp(2));

        /* 第一行：进度 + 右上角关闭 */
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        DesignSpec tsp = rd();
        ttsInfo = ModouUi.tv(this, "准备朗读…", tsp.rdTtsTitleSp, theme.text);
        ttsInfo.setGravity(Gravity.CENTER_VERTICAL);
        if (tsp.rdTtsTitleWeight == 2) {
            ttsInfo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else {
            ttsInfo.setTypeface(ttsInfo.getTypeface(), Typeface.BOLD);
        }
        // 行高与按钮同高（44）：两者都是「满行高 + 内部居中」，
        // 文字基线和 X 的圆心必然在同一条水平线上
        top.addView(ttsInfo, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView exit = new TextView(this);
        // 44×44 触摸区 / 22dp 图标，整行垂直居中
        exit.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        exit.setGravity(Gravity.CENTER);
        exit.setIncludeFontPadding(false);
        // 只留纯图标：外面那圈半透明圆底在浅色面板上是块脏灰，去掉；
        // 按下时仍有一点点反馈，不至于「点了没感觉」
        android.graphics.drawable.GradientDrawable exMask = new GradientDrawable();
        exMask.setShape(GradientDrawable.OVAL);
        exMask.setColor(0xFFFFFFFF);
        exit.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x14000000), null, exMask));
        exit.setCompoundDrawablesWithIntrinsicBounds(null,
                new GlyphIcon(GlyphIcon.CLOSE, theme.sub, dp(22)), null, null);
        exit.setOnClickListener((v) -> {
            stopTts();
            ttsAt = -1;
        });
        top.addView(exit);
        bar.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        /* 第二行：语速 / 定时 两枚胶囊 */
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        ttsRateChip = chipChip("");
        ttsRateChip.setOnClickListener((v) -> openTtsRateSheet());
        chips.addView(ttsRateChip);
        ttsTimerChip = chipChip("");
        ttsTimerChip.setOnClickListener((v) -> openTtsTimerSheet());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(18);
        chips.addView(ttsTimerChip, tlp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(5);
        clp.bottomMargin = dp(3);
        // 左边缘必须压住 16dp 网格线：胶囊本身 paddingStart=0，这里也不能再加偏移
        clp.leftMargin = 0;
        bar.addView(chips, clp);

        View divider = new View(this);
        divider.setBackgroundColor(theme.dark ? 0x14FFFFFF : 0x0F000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        bar.addView(divider, dlp);

        /* 第三行：上一句 / 暂停 / 下一句 —— 三格等分、图标大、下方带字 */
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        ctl.setPadding(0, 0, 0, 0);

        LinearLayout prev = ttsCtrlCell("上一句", GlyphIcon.PREV, false, Gravity.LEFT,
                this::ttsPrevSentence);
        ctl.addView(prev, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout play = ttsCtrlCell("暂停", GlyphIcon.PAUSE, true, Gravity.CENTER, this::ttsTogglePlay);
        ctl.addView(play, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout next = ttsCtrlCell("下一句", GlyphIcon.NEXT, false, Gravity.RIGHT, () -> {
            if (ttsQueue.isEmpty()) return;
            ttsPaused = false;
            syncTtsToggle();
            ttsNext(); // 内部自增后读下一句；末句则照旧走翻页/翻章续读
        });
        ctl.addView(next, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(ctl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.setMargins(dp(10), 0, dp(10), dp(10));
        // 0.9.153：朗读浮条随工具坞一起搬进 dockHost（竖排容器顶部），与 dock 共享一条无缝接缝
        // （顶上两角圆、底边切方，joinTop）。dockHost 在独立窗口里，浮条背后也走系统模糊。
        if (dockHost != null) {
            dockHost.addView(bar, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            root.addView(bar, lp);
        }
        ttsBar = bar;
        if (dock != null) {
            dock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
                if (ttsBar != null && ttsBar.getVisibility() == View.VISIBLE) syncTtsOverlayMargin();
            });
        }
        syncTtsChips();
    }

    /**
     * 听书控制格：上面一枚图标，下面一行小字。
     *
     * 三个格子**都不铺底色**。上一句/下一句连按下反馈都只是极淡的圆；
     * 暂停是主按钮，靠「图标更大 + 强调色」表达，而不是靠一块灰饼。
     *
     * @param align 格内容对齐：左格 LEFT（让「上」字压上 16dp 的网格线）、
     *              中格 CENTER（暂停永远在正中）、右格 RIGHT（和内边距对齐）
     */
    private LinearLayout ttsCtrlCell(String label, int kind, boolean primary, int align,
                                     Runnable click) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(align);

        DesignSpec csp = rd();
        int size = primary ? dp(csp.rdTtsPlayDp) : dp(34);
        TextView ic = new TextView(this);
        ic.setGravity(Gravity.CENTER);
        ic.setIncludeFontPadding(false);
        ic.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        android.graphics.drawable.GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        // content 传 null：平时一点底色都不画，只有按下那一刻才浮一圈波纹
        ic.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        ModouUi.alpha(theme.text, primary ? 0x18 : 0x10)),
                null, mask));
        ic.setCompoundDrawablesWithIntrinsicBounds(null,
                new GlyphIcon(kind, primary ? theme.acc : theme.text,
                        dp(primary ? (csp.rdTtsPlayDp >= 48 ? 24 : 22) : 17)),
                null, null);
        if (primary && csp.rdTtsPlayFill) {
            // M3：中央播放键是一枚 tonal 圆底（primaryContainer 语汇的纸张色近似）
            GradientDrawable pg = new GradientDrawable();
            pg.setShape(GradientDrawable.OVAL);
            pg.setColor(ModouUi.alpha(theme.acc, 0x1F));
            ic.setBackground(pg);
        }

        TextView t = ModouUi.tv(this, label, csp.rdTtsLabelSp, primary ? theme.acc : theme.sub);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(2);
        col.addView(ic);
        col.addView(t, tlp);

        View.OnClickListener l = v -> click.run();
        ic.setOnClickListener(l);
        t.setOnClickListener(l);
        col.setOnClickListener(l);

        if (primary) {
            ttsToggleBtn = ic;
            ttsToggleLabel = t;
        }
        return col;
    }

    /** 面板里的胶囊：**什么都不画**，只剩下文字本身（描边也会被看成灰底圈，一并去掉） */
    private TextView chipChip(String text) {
        TextView v = ModouUi.tv(this, text, 14.5f, theme.acc);
        v.setGravity(Gravity.CENTER);
        // 左内边距必须是 0：胶囊的左边缘要压在 16dp 那条网格线上，
        // 和上面的「朗」、下面的「上」同一条竖线
        v.setPadding(0, dp(6), 0, dp(6));
        v.setBackground(null);
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
        static final int PAUSE = 0, PLAY = 1, PREV = 2, NEXT = 3, BACK = 4, EARPHONE = 5, CLOSE = 6;
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
                case CLOSE: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(Math.max(2f, w * 0.10f));
                    p.setStrokeCap(Paint.Cap.ROUND);
                    float d = w * 0.24f;
                    c.drawLine(cx - d, cy - d, cx + d, cy + d, p);
                    c.drawLine(cx + d, cy - d, cx - d, cy + d, p);
                    p.setStyle(Paint.Style.FILL);
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
        // 文字用主题正文色，图标用全局柔和蓝 —— 原来是深灰底 + 白色 emoji（在部分 ROM
        // 上被渲染成橙色的彩色字形），和整页配色打架
        final int fg = theme.dark ? 0xFFF2F3F5 : theme.text;
        final int ic = theme.acc;

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        // 半透明毛玻璃小胶囊：底是 60% 白（夜里 60% 深灰），透出下面的纸色和字影，
        // 不再是那块「死白」的实心板；高光描边 + 柔光投影维持浮起感
        GradientDrawable g = new GradientDrawable();
        g.setColor(theme.dark ? 0x99202224 : 0xA6FFFFFF);
        g.setCornerRadius(dp(22));
        // 0.9.201：浅色纸底下 20% 白描边几乎不可见，改 10% 墨，与全文件其余描边一致。
        g.setStroke(Math.max(1, dp(1)), theme.dark ? 0x33FFFFFF : 0x1A000000);
        pill.setBackground(g);
        pill.setElevation(dp(10));
        pill.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22));
            }
        });
        pill.setClipToOutline(true);
        pill.setPadding(dp(2), dp(2), dp(2), dp(2));

        ttsPillBack = ModouUi.tv(this, "", 13.5f, fg);
        ttsPillBack.setGravity(Gravity.CENTER);
        ttsPillBack.setMinWidth(dp(46));
        ttsPillBack.setPadding(dp(14), 0, dp(14), 0);
        ttsPillBack.setCompoundDrawablesWithIntrinsicBounds(
                new GlyphIcon(GlyphIcon.BACK, ic, dp(15)), null, null, null);
        ttsPillBack.setOnClickListener((v) -> {
            if (ttsReadingPage >= 0) pageView.gotoPage(ttsReadingPage);
            syncTtsPill();
        });
        pill.addView(ttsPillBack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        ttsPillDivider = new View(this);
        ttsPillDivider.setBackgroundColor(theme.dark ? 0x1FFFFFFF : 0x14181B20);
        pill.addView(ttsPillDivider, new LinearLayout.LayoutParams(
                Math.max(1, dp(1)), dp(14)));

        ttsPillFromHere = ModouUi.tv(this, "从本页听", 13.5f, fg);
        ttsPillFromHere.setGravity(Gravity.CENTER);
        ttsPillFromHere.setPadding(dp(14), 0, dp(18), 0);
        ttsPillFromHere.setCompoundDrawablePadding(dp(7));
        ttsPillFromHere.setCompoundDrawablesWithIntrinsicBounds(
                new GlyphIcon(GlyphIcon.EARPHONE, ic, dp(15)), null, null, null);
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

        ttsPillToggle = ModouUi.tv(this, "暂停播放", 13.5f, theme.acc);
        ttsPillToggle.setGravity(Gravity.CENTER);
        ttsPillToggle.setPadding(dp(16), 0, dp(18), 0);
        ttsPillToggle.setCompoundDrawablePadding(dp(7));
        ttsPillToggle.setCompoundDrawablesWithIntrinsicBounds(
                new GlyphIcon(GlyphIcon.PAUSE, ic, dp(15)), null, null, null);
        ttsPillToggle.setOnClickListener((v) -> {
            ttsTogglePlay();
            syncTtsPill();
        });
        pill.addView(ttsPillToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        // 底距 4dp（高 40dp → 横条顶在 44dp）。正文末行现在止于 28dp，
        // 这条只在朗读时出现，浮在页脚带上沿，不再为了它常驻让出一整行的高度。
        lp.bottomMargin = dp(4);
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
        syncTtsMedia();
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
        boolean joined = ttsSession && menu;
        if (ttsBar != null) ttsBar.setVisibility(joined ? View.VISIBLE : View.GONE);
        // 面板压上来时，底栏顶边切成直角 —— 两个圆角对不上就会漏出月牙形的正文
        if (dockBg != null) dockBg.joinTop(joined);
        // 面板要露出来 → 工具坞收起第二行图标让位（退出朗读自动还原）
        boolean squeeze = joined && ttsBar != null;
        if (squeeze != dockSqueezed) {
            dockSqueezed = squeeze;
            if (dockRow1 != null) paintDockRows();
            if (dock != null) dock.post(this::syncTtsOverlayMargin);
        }
        syncTtsPill();
        if (ttsBar != null && ttsSession && menu) {
            // 工具坞高度（自动阅读条显隐、两行图标）要等一次布局才拿得到
            ttsBar.post(this::syncTtsOverlayMargin);
        }
    }

    /**
     * 面板抬到工具坞上方并留一段间距。
     *
     * 以前用 dock.getMeasuredHeight() 估高度：工具坞里有自动阅读条、两行图标，
     * 高度会随状态变，估出来常常偏小，面板底边就正好压在「第几章 / 第几页」那行上。
     * 改成拿 dock 在 root 里的真实顶边算，布局怎么变都不会贴上去。
     */
    /**
     * 朗读浮条与工具坞现在同在 dockHost（独立窗口）里竖排：浮条在上、坞在下，接缝由
     * 圆角对缝（joinTop）保证，位置是自动堆叠的，不再需要手算 bottomMargin。
     * 这里只负责朗读浮条显隐后重算窗口大小（窗口 bounds 决定模糊区域）。
     */
    private void syncTtsOverlayMargin() {
        relayoutDockWindow();
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
        // 图标由自绘 GlyphIcon 提供，文本里不要再塞 ▶/⏸（那些是 emoji 码位，
        // 多数 ROM 会渲染成彩色方块或橙色字形，正是「橙图标」的来源）
        ttsPillToggle.setText(ttsPaused ? "继续播放" : "暂停播放");
        ttsPillToggle.setCompoundDrawablesWithIntrinsicBounds(
                new GlyphIcon(ttsPaused ? GlyphIcon.PLAY : GlyphIcon.PAUSE, theme.acc, dp(15)),
                null, null, null);
    }

    /** 语速/定时胶囊与暂停按钮的文字刷新 */
    private void syncTtsChips() {
        if (ttsRateChip != null) ttsRateChip.setText(String.format(java.util.Locale.ROOT, "%.1fx", ttsRate));
        if (ttsTimerChip != null) {
            ttsTimerChip.setText(ttsTimerMin > 0 ? "定时 " + ttsTimerMin + " 分"
                    : ttsStopChapters == 1 ? "定时 本章"
                    : ttsStopChapters > 1 ? "定时 " + ttsStopChapters + " 章"
                    : "定时 关");
        }
        syncTtsToggle();
    }

    private void syncTtsToggle() {
        if (ttsToggleBtn != null) {
            ttsToggleBtn.setCompoundDrawablesWithIntrinsicBounds(null,
                    new GlyphIcon(ttsPaused ? GlyphIcon.PLAY : GlyphIcon.PAUSE, theme.acc, dp(26)),
                    null, null);
        }
        if (ttsToggleLabel != null) ttsToggleLabel.setText(ttsPaused ? "继续" : "暂停");
        if (ttsPillToggle != null) {
            ttsPillToggle.setText(ttsPaused ? "继续播放" : "暂停播放");
            ttsPillToggle.setCompoundDrawablesWithIntrinsicBounds(
                    new GlyphIcon(ttsPaused ? GlyphIcon.PLAY : GlyphIcon.PAUSE, theme.acc, dp(15)),
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

        /* 最上排：睡前听书真正会用到的四档，一下点完，不用拨齿轮 */
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        String[] qs = {"15 分钟", "30 分钟", "播完本章", "播完 3 章"};
        boolean[] qon = {ttsTimerMin == 15, ttsTimerMin == 30,
                ttsStopChapters == 1, ttsStopChapters == 3};
        for (int i = 0; i < qs.length; i++) {
            final int k = i;
            boolean on = qon[i];
            TextView b = ModouUi.tv(this, qs[i], 12, on ? 0xFFFFFFFF : u.text);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(on ? u.accent : u.bg);
            g.setCornerRadius(ModouUi.dp(this, 8));
            b.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(ModouUi.dp(this, 3), 0, ModouUi.dp(this, 3), ModouUi.dp(this, 10));
            b.setLayoutParams(lp);
            b.setOnClickListener((v) -> {
                if (k == 0) {
                    ttsStopChapters = 0;
                    saveTtsStopChapters();
                    setTtsTimer(15);
                } else if (k == 1) {
                    ttsStopChapters = 0;
                    saveTtsStopChapters();
                    setTtsTimer(30);
                } else {
                    ttsStopChapters = k == 2 ? 1 : 3;
                    saveTtsStopChapters();
                    setTtsTimer(0);   // setTtsTimer(0) 只清分钟定时，不动章节计数
                    applyTtsTimer();
                }
                ModouUi.toast(this, k == 0 ? "15 分钟后停止"
                        : k == 1 ? "30 分钟后停止"
                        : k == 2 ? "本章读完就停" : "3 章读完就停");
                v.postDelayed(() -> closeCurrentSheet(), 120);
            });
            quick.addView(b);
        }
        box.addView(quick);

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
    private void saveTtsStopChapters() {
        ModouStore.get(this).putSetting("ttsStopChapters", String.valueOf(ttsStopChapters));
    }

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
        noteTypesetting();
        updateHeader();
        saveProgress(false);
        refreshDockNav();
        syncFindFor(idx);
        ttsContinueIfPending();
        prefetchChapter(idx - 1);
        prefetchChapter(idx + 1);
        // 目录等这一章真的排到屏幕上之后再补：整本 json（几千章）的解析
        // 不该压进「进书第一屏」这条路径
        pageView.post(this::loadTocAsync);
    }

    /**
     * 大章的后台排版要一两百毫秒，这期间页面还停在上一章：
     * 把「加载中」改成「排版中」留在屏幕上，比让画面莫名不动清楚。
     * 小章走同步排版，setChapter 返回时已经排完，这里什么都不做。
     */
    private void noteTypesetting() {
        if (!silentJump && pageView.isTypesetting()) {
            loading.setText("排版中…");
            loading.setVisibility(View.VISIBLE);
        }
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
        noteTypesetting();
        updateHeader();
        saveProgress(false);
        refreshDockNav();
        syncFindFor(idx);
        ttsContinueIfPending();
        // 读书是线性的：这一章出来了，下一章大概率马上要用，趁现在后台备好
        prefetchChapter(idx - 1);
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

    /**
     * 顶部小字：当前章名。
     *
     * 以前章名取不到时会退回书名，于是「择天记」下面又写一遍「择天记」。
     * 现在按「章名 → 目录里的章名 → 第 N 章」的顺序兜底，永不和书名重复。
     */
    private String headChapterName() {
        String t = chapterTitle == null ? "" : chapterTitle.trim();
        if (t.isEmpty() || t.equals(bookTitle)) {
            try {
                t = purifyTitle(chapterNameOf(index));
            } catch (Throwable e) {
                t = "";
            }
        }
        if (t == null) t = "";
        t = t.trim();
        if (t.isEmpty() || t.equals(bookTitle)) t = "第 " + Math.max(1, index + 1) + " 章";
        return t;
    }

    private void updateHeader() {
        header.setText(bookTitle == null ? "" : bookTitle);
        headerSub.setText(headChapterName());
        // 页眉页脚要跟着换章：章名变了，页眉文案与「剩余章数」都要重算
        syncHeadFoot();
        // 这里必须整段同步（文案 + 可见性）：只 setText 的话，进书那一刻文案还空、
        // 浮层被判成 GONE 之后就再也不会自己回来 —— 就是「页眉直接消失了」。
        syncMiniHead();
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
        if (pageView == null) return;
        int off = pageView.pageStartOffset();
        long now = System.currentTimeMillis();
        if (off != lastSavedOffset) {
            // 位置变了 → 先记在内存里，累计这一段停留时长
            if (lastTickAt > 0 && now > lastTickAt) pendingAddMs += now - lastTickAt;
            lastTickAt = now;
            pendingOffset = off;
        }
        if (force) {
            flushProgress();
            return;
        }
        if (pendingOffset < 0) return;                 // 位置没变：什么都不做
        // 每翻 10 页强制落库一次：防抖只解决「连续翻页时的写放大」，
        // 长时间阅读中途被杀进程时，最多也只丢 10 页的位置。
        if (lastSavedPage < 0 || Math.abs(pageView.page() - lastSavedPage) >= 10) {
            flushProgress();
            return;
        }
        // 其余情况只是重置 2s 计时器（防抖，不是节流）：手指停下来 2 秒才写一次
        saveHandler.removeCallbacks(saveDebounce);
        saveHandler.postDelayed(saveDebounce, 2000);
    }

    /** 真正落库。防抖到点、翻满 10 页、onPause/退出时都会走到这里 */
    private void flushProgress() {
        saveHandler.removeCallbacks(saveDebounce);
        if (pageView == null) return;
        if (pendingOffset < 0) return;
        int off = pendingOffset;
        pendingOffset = -1;
        long now = System.currentTimeMillis();
        if (lastTickAt > 0 && now > lastTickAt) pendingAddMs += now - lastTickAt;
        lastTickAt = now;
        long addMs = pendingAddMs;
        pendingAddMs = 0;
        lastSavedOffset = off;
        lastSavedPage = pageView.page();
        lastSaveAt = now;
        ModouStore.get(this).putProgress(bookId, index, off, addMs);
        if (sBridge != null) {
            sBridge.evalJs("window.__modouNativeProgress && window.__modouNativeProgress(" + index + ","
                    + off + ")");
        }
    }

    /* ---------------- NativePageView.Host ---------------- */

    @Override
    public void onPageChanged(int page, int pageTotal) {
        silentJump = false;
        ttsPage = page;
        ttsPageTotal = pageTotal;
        syncTtsPill(); // 翻页后可能就不再是「正在朗读的那一页」了
        // 工具坞上的「第几章 / 第几页」跟着走：翻页、拖滑杆、换章都要实时变，
        // 不能等下次唤起菜单才更新（用户点着滑杆看到文案不动会以为卡住了）
        updateDockProgress(false);
        saveProgress(false);
        String t = headChapterName();
        header.setText(bookTitle == null ? "" : bookTitle);
        headerSub.setText(t + "  ·  " + (page + 1) + "/" + Math.max(1, pageTotal));
        syncMiniHead();
        // 新页表就绪（大章是后台排完这一下才来）：这时才收起「排版中」
        if (!pageView.isTypesetting()) hideLoading();
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
        // 0.9.154：工具坞底色从 95% 实底调薄成「磨砂玻璃」（约 28%），背后的阅读正文实时模糊
        // 透上来，工具坞本身是磨砂玻璃而非死实卡 —— 之前 95% 实底把模糊盖死，看着就是一块白板，
        // 和背后的模糊成了两团东西。圆角 / 边缘泛光 / 顶边切方（joinTop，与朗读浮条无缝拼接）
        // 仍由 dockBg（ModouGlassDrawable）承担，复用同一套磨砂材质。
        // 0.9.173（Stage 5 第一刀）：形状 / 材质厚度 / 内边距按设计语言。
        // 颜色仍取纸张色板 —— 阅读纸张与设计语言正交（§2.10），把 accent 换成系统色
        // 会让「牛皮纸 + 紫色指示器」这种组合出现；控件只改结构，不改色相。
        DesignSpec rsp = rd();
        if (mono()) {
            // 极简·纪：不要整块圆角灰底，只垫一层由下往上的极弱纸色渐变遮住底部文字
            GradientDrawable veil = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{ModouUi.alpha(theme.bg, 0xE6),
                            ModouUi.alpha(theme.bg, 0x00)});
            dockPanel.setBackground(veil);
            dockPanel.setPadding(dp(16), dp(8), dp(16), dp(12));
            dockBg = null;
        } else {
            int baseAlpha = rsp.rdDockStyle == 2 ? 0xF2 : 0x47;
            dockBg = new ModouGlassDrawable(ModouUi.alpha(theme.card, baseAlpha),
                    ModouUi.dp(this, rsp.rdDockCornerDp), rsp.rdDockTopOnly);
            dockBg.setRimWidth(Math.max(1, dp(rsp.rdDockStyle == 2 ? 0 : 1)));
            dockBg.setVeil(rsp.rdDockStyle == 2 ? 0
                    : (theme.dark ? 0x0D000000 : 0x0DFFFFFF));
            dockPanel.setBackground(dockBg);
            dockPanel.setPadding(dp(rsp.rdDockPadH), dp(rsp.rdDockPadV),
                    dp(rsp.rdDockPadH), dp(rsp.rdDockPadV));
        }
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
        dockMetaL.setTextSize(TypedValue.COMPLEX_UNIT_SP, rsp.rdDockMetaSp);
        dockMetaL.setTextColor(theme.sub);
        dockMetaR = new TextView(this);
        dockMetaR.setTextSize(TypedValue.COMPLEX_UNIT_SP, rsp.rdDockMetaSp);
        dockMetaR.setTextColor(theme.sub);
        dockMetaR.setGravity(Gravity.END);
        meta.addView(dockMetaL, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        meta.addView(dockMetaR);
        dockPanel.addView(meta);

        // 进度条行：‹ 轨道 ›，三样在同一条横轴上。
        // 「上一章 / 下一章」原来占着第二行两个带文字的格子，既挤图标位置，
        // 又要下滑到最底才够得着；现在收紧成轨道两端的两个箭头，
        // 想「往回翻 / 往后翻」手不用离开拇指正上方这一条。
        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);

        if (!mono()) {
            dockPrev = seekNav(R.drawable.ic_rd_prev);
            seekRow.addView(dockPrev);
        }

        // 轨道视觉 4dp、触摸 30dp。上下各留 10dp 到相邻元素，
        // 免得手指想点箭头时先被进度条吃掉事件、页码乱跳。
        dockSeek = buildDockSeek();
        seekRow.addView(dockSeek, new LinearLayout.LayoutParams(
                0, dp(34), 1f));

        if (!mono()) {
            dockNext = seekNav(R.drawable.ic_rd_next);
            seekRow.addView(dockNext);
        }

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(10);
        dockPanel.addView(seekRow, slp);

        // 第一行图标
        dockRow1 = new LinearLayout(this);
        dockRow1.setOrientation(LinearLayout.HORIZONTAL);
        dockPanel.addView(dockRow1);

        // 第二行上方分隔线（网页版 rgba(43,37,27,.06)）
        dockDivider = new View(this);
        dockDivider.setBackgroundColor(theme.dark ? 0x14FFFFFF : 0x0F000000);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        llp.topMargin = dp(2);
        dockPanel.addView(dockDivider, llp);

        dockRow2 = new LinearLayout(this);
        dockRow2.setOrientation(LinearLayout.HORIZONTAL);
        dockPanel.addView(dockRow2);

        dock.addView(dockPanel);

        // 0.9.153：工具坞搬进独立窗口（与书架底栏同源）。dockHost 是竖排容器，上方留朗读浮条
        // ttsBar 的位置（buildTtsBar 里塞进来）、下方放 dock；窗口 bounds 精确等于这块卡片，
        // 系统每帧把背后的阅读正文实时卷积模糊，只糊卡片、不糊整页。
        // 旧版 dock 直接 addView 进阅读页同窗口，拿不到系统 backdrop 模糊（注释里也写过）。
        if (dockWin != null) {
            dockWin.dismiss();
            dockWin = null;
        }
        dockHost = new LinearLayout(this);
        dockHost.setOrientation(LinearLayout.VERTICAL);
        dockHost.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // dock 撑满 dockHost 宽、随内容定高；左右各 10dp 的留白交给「窗口离屏边 10dp」承担，
        // 这样窗口外（10dp 缝隙）的触摸用 NOT_TOUCH_MODAL 透传给正文，不会点死。
        dockHost.addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 窗口实例先建好，但不在 build 时 show：build 发生在开书初始化，工具坞此刻是隐藏的。
        // 真正 show + 挂模糊推迟到 showDock（用户首次点出菜单），那时 decor 早已 attach。
        dockWin = new ModouDockWindow(this);

        paintDockRows();
    }

    /* ---------------- 图标排布（网页版 settings.readerMenuLayout） ---------------- */

    /** 图标注册表：key 固定，顺序由用户排布决定（上一章/下一章已挪到进度条两端，不在此列） */
    private static final String[] MENU_KEYS = {
            "toc", "bmk", "find", "auto", "tts", "purify",
            "typo", "night", "more"};
    private static final String[] MENU_DEF_R1 = {"toc", "bmk", "find", "auto", "tts", "purify"};
    private static final String[] MENU_DEF_R2 = {"typo", "night", "more"};

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
        if (mono()) {
            // 极简·纪：九个图标全部补齐、单行等分，忽略用户隐藏项与两行排布
            dockRow1.removeAllViews();
            for (String k : MENU_KEYS) dockRow1.addView(dockButton(k));
            dockRow2.removeAllViews();
            dockRow2.setVisibility(View.GONE);
            if (dockDivider != null) dockDivider.setVisibility(View.GONE);
            dockRow1.setVisibility(View.VISIBLE);
            if (dockAuto != null) dockAuto.setVisibility(autoOn ? View.VISIBLE : View.GONE);
            if (autoOn && dockAutoSpeed != null) dockAutoSpeed.setText((autoInterval / 1000) + "s");
            return;
        }
        dockRow1.removeAllViews();
        for (String k : menuR1) dockRow1.addView(dockButton(k));
        dockRow2.removeAllViews();
        for (String k : menuR2) dockRow2.addView(dockButton(k));
        dockRow1.setVisibility(menuR1.isEmpty() ? View.GONE : View.VISIBLE);
        boolean row2 = !menuR2.isEmpty() && !dockSqueezed;
        dockRow2.setVisibility(row2 ? View.VISIBLE : View.GONE);
        if (dockDivider != null) {
            dockDivider.setVisibility(menuR1.isEmpty() || !row2 ? View.GONE : View.VISIBLE);
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

    /**
     * 进度条两端的换章箭头：只有图标，没有文字。
     * 44dp 触摸区里放 20dp 图标（上下各 12dp 留白），和上面图标行同一套尺寸；
     * 触控宽度比图标本身宽一倍，连点「下一章」时不至于点空。
     */
    private View seekNav(int iconRes) {
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        // 图标 28dp（44 − 8×2）：20dp 时和旁边那根 4dp 橙条比起来太小，
        // 视觉上像两个装饰点；放到 28dp 才够「一枚按钮」的分量，点击区仍是 44dp
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(tinted(iconRes, theme.sub));
        iv.setOnClickListener(v -> requestChapter(
                iconRes == R.drawable.ic_rd_prev ? index - 1 : index + 1));
        return iv;
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
                // 0.9.50：不再 hideDock —— 级联菜单直接浮在「更多」按钮上方，坞保持可见
                return dockAct(R.drawable.ic_rd_more, "更多", false, false,
                        this::openMoreSheet);
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
        mlRowViews.clear();
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
        // 长按拖拽：setTag 存 key，拖动时靠它找回在 list 里的位置
        r.setTag(key);
        mlRowViews.put(key, r);
        mlAttachDrag(r, key, row);
        return r;
    }

    /** 图标排布里每一行 key → 行视图：拖动时要拿同行的兄弟做「让位」 */
    private final java.util.HashMap<String, View> mlRowViews = new java.util.HashMap<>();
    /** 拖动中的目标下标（松手才真正落到 list） */
    private int mlDragTarget = -1;

    /**
     * 长按拖拽排序。
     *
     * 面板是 LinearLayout，不是 RecyclerView，所以自己处理：长按 320ms 进入拖拽，
     * 拖动时只改 translationY（不重建视图，重建会把手势目标一起扔掉），
     * 越过半行高度就给兄弟行让位，松手把 list 重排、存盘、重画。
     *
     * 手指按在行上直接 DOWN 返回 true —— 否则 ScrollView 会抢走后续 MOVE。
     * 代价是要自己把「没进入拖拽时的上下滑动」译成 scrollBy，见下面的 fallback。
     */
    private void mlAttachDrag(View r, String key, java.util.List<String> row) {
        final float[] downY = {0f};
        final boolean[] dragging = {false};
        final Runnable[] timer = {null};

        r.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY[0] = e.getRawY();
                    if (timer[0] != null) v.removeCallbacks(timer[0]);
                    timer[0] = () -> {
                        timer[0] = null;
                        dragging[0] = true;
                        mlDragTarget = row.indexOf(key);
                        ScrollView p = findScroll(v);
                        if (p != null) p.requestDisallowInterceptTouchEvent(true);
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(110).start();
                        v.setElevation(dp(12));
                        v.setBackground(ModouUi.round(ModouUi.alpha(theme.acc, 0x1F), 12));
                    };
                    v.postDelayed(timer[0], 320);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float y = e.getRawY();
                    if (!dragging[0]) {
                        // 还没到 320ms 就滑了：用户想滚面板，撤回长按，交给 ScrollView。
                        // 这里不自己 scrollBy —— 父容器本来就会在这时拦截，双份滚动会滑飞。
                        if (Math.abs(y - downY[0]) > dp(6)) {
                            if (timer[0] != null) {
                                v.removeCallbacks(timer[0]);
                                timer[0] = null;
                            }
                        }
                        return true;
                    }
                    mlDragMove(v, row, y - downY[0]);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (timer[0] != null) {
                        v.removeCallbacks(timer[0]);
                        timer[0] = null;
                    }
                    if (dragging[0]) {
                        dragging[0] = false;
                        mlDragEnd(v, row);
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    /** 拖动中：被拖行跟手，其余同行走位让开（一格的位移，160ms 缓动，不闪） */
    private void mlDragMove(View dragView, java.util.List<String> row, float dy) {
        int n = row.size();
        if (n <= 1) {
            dragView.setTranslationY(dy);
            return;
        }
        String key = (String) dragView.getTag();
        int from = row.indexOf(key);
        if (from < 0) return;
        int h = dragView.getHeight();
        if (h <= 0) h = dp(52);
        int to = Math.max(0, Math.min(n - 1, from + Math.round(dy / h)));
        mlDragTarget = to;
        for (int i = 0; i < n; i++) {
            View sib = mlRowViews.get(row.get(i));
            if (sib == null || sib == dragView) continue;
            float off = 0f;
            if (from < to && i > from && i <= to) off = -h;
            else if (from > to && i >= to && i < from) off = h;
            if (Math.abs(sib.getTranslationY() - off) > 0.5f) {
                sib.animate().translationY(off).setDuration(160).start();
            }
        }
        dragView.setTranslationY(dy);
    }

    /** 松手：吸附 → 改 list → 存盘 + 重画工具坞 → 重建面板 */
    private void mlDragEnd(View dragView, java.util.List<String> row) {
        String key = (String) dragView.getTag();
        int from = row.indexOf(key);
        int to = mlDragTarget;
        mlDragTarget = -1;
        dragView.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
        dragView.setElevation(0);
        dragView.setBackground(null);
        for (String k : row) {
            View sib = mlRowViews.get(k);
            if (sib != null && sib != dragView) {
                sib.animate().translationY(0f).setDuration(110).start();
            }
        }
        dragView.setTranslationY(0f);
        if (from < 0 || to < 0 || to == from) return;
        String k = row.remove(from);
        row.add(Math.min(to, row.size()), k);
        View parent = (View) dragView.getParent();
        if (parent instanceof LinearLayout) menuCfgChanged((LinearLayout) parent);
    }

    /** 从某个行视图往上找 ScrollView：拖动时要临时禁止它抢手势、未拖动时替它滚动 */
    private static ScrollView findScroll(View v) {
        android.view.ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p instanceof ScrollView) return (ScrollView) p;
            p = p.getParent();
        }
        return null;
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
        if (mono()) return dockIconOnly(iconRes, on, disabled, r);
        DesignSpec rsp = rd();
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(rsp.rdDockItemPadV), dp(2), dp(rsp.rdDockItemPadV));
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView iv = new ImageView(this);
        int s = dp(rsp.rdDockIconDp);
        iv.setImageDrawable(tinted(iconRes, on ? theme.acc : theme.sub));
        if (on && rsp.rdDockIndicator) {
            // M3 NavigationBar 的 active indicator：图标后面一枚 32×20 的 tonal 药丸，
            // 圆角 = 药丸高度的一半，颜色取纸张强调色的 1F 透明（不引入系统紫）。
            FrameLayout pill = new FrameLayout(this);
            int pw = dp(rsp.rdDockIconDp + 16);
            int phh = dp(rsp.rdDockIconDp + 8);
            GradientDrawable pg = new GradientDrawable();
            pg.setColor(ModouUi.alpha(theme.acc, 0x1F));
            pg.setCornerRadius(phh / 2f);
            pill.setBackground(pg);
            pill.addView(iv, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
            b.addView(pill, new LinearLayout.LayoutParams(pw, phh));
        } else {
            iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            b.addView(iv);
        }

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, rsp.rdDockLabelSp);
        if (rsp.rdDockLabelWeight == 2) {
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        }
        t.setTextColor(on ? theme.acc : theme.sub);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(4), 0, 0);
        b.addView(t);

        if (on && !rsp.rdDockIndicator) {
            // 约 .10 的淡底（墨读 14dp / iOS 10dp 圆角）
            b.setBackground(ModouUi.squircle(this, ModouUi.alpha(theme.acc, 0x1A),
                    rsp.rdDockItemCornerDp));
        }
        if (disabled) b.setAlpha(0.3f);
        else b.setOnClickListener(v -> {
            lastDockBtn = v;   // 级联菜单要锚定「刚被点的那个坞按钮」弹出
            r.run();
        });
        return b;
    }

    /** 最近一次点击的工具坞按钮（CascadingMenu 的弹出锚点） */
    private View lastDockBtn;

    /** 「Aa」排版按钮：用文字而不是图标，和网页版一致（20dp 盒子、衬线加粗） */
    private View dockAa(String label, Runnable r) {
        if (mono()) return dockAaIcon(r);
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

    /** 进度条：药丸轨道 + 强调色填充 + 白球；拖动即按页跳转 */
    private ModouPillSeekBar buildDockSeek() {
        ModouPillSeekBar sb = new ModouPillSeekBar(this);
        sb.setMinimumHeight(dp(34));
        sb.setPadding(dp(7), 0, dp(7), 0);
        // 轨道 8dp、球半径 8dp：球直径是轨道高的两倍，红条 4dp 的圆头必然被球整个盖住，
        // 交界处不会露出「圆柱截面」。进度段终点就取球心，两者始终同一点。
        // 轨道高 / 滑块半径由 ModouPillSeekBar 按设计语言自取（0.9.173）
        if (mono()) {
            // 极简·纪：2dp 细线、深灰底轨、极小白色圆点滑块
            sb.setTrackHeightDp(2f);
            sb.setBallRadiusDp(3f);
            sb.setPalette(0xFF3A4050, theme.acc, 0x00000000,
                    0x00000000, 0x00000000, 0xFFFFFFFF, 0x00000000);
        } else {
            sb.setPalette(
                    theme.dark ? 0x2BFFFFFF : 0x1F000000,
                    ModouUi.mix(theme.acc, 0xFFFFFFFF, 0.16f),
                    theme.dark ? 0x26FFFFFF : 0x40FFFFFF,
                    theme.acc,
                    theme.dark ? 0xFFF0EDE4 : 0xFFFFFFFF,
                    theme.dark ? 0xFFF7F5EF : 0xFFFFFFFF,
                    theme.dark ? 0x33FFFFFF : 0x14000000);
        }
        sb.setTicks(dockTicks);
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
        // 自绘滑杆读的是同一份刻度 List，改完要主动重绘一次
        if (withTicks && dockSeek != null) dockSeek.invalidate();
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
        if (dock == null || dockHost == null) return;
        dockShowing = true;
        updateDockProgress();
        paintDockRows();
        // 0.9.155：复位整个 dock 容器的显隐状态。hideDock 把 dock（父容器）的 alpha /
        // translationY 动画到 0 / 40dp，而旧版 showDock 只复位了子节点 dockPanel ——
        // dock 自身 alpha 一直停在 0，于是「第一次正常、第二次起只剩窗口模糊、按钮全没了」：
        // 窗口与系统模糊都在，内容 View 却是全透明的。父容器统一复位，子节点入场动画照旧。
        dock.animate().cancel();
        dock.setAlpha(1f);
        dock.setTranslationY(0f);
        dock.setVisibility(View.VISIBLE);
        // 先把窗口挂出来（模糊 + 卡片位置），再用滑杆入场动画把卡片推上来
        relayoutDockWindow();
        if (dockWin == null) dockWin = new ModouDockWindow(this);
        int wPx = Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(20));
        int hPx = measureDockHost();
        dockWin.attach(dockHost, wPx, hPx > 0 ? hPx : ViewGroup.LayoutParams.WRAP_CONTENT, dp(2), dp(22));
        // 快速「收起→再唤起」时，上一次入场动画可能还在跑：先取消再显式设初值，
        // 避免旧动画把 alpha / 位移覆盖回半途的值。
        dockPanel.animate().cancel();
        dockPanel.setAlpha(0f);
        dockPanel.setTranslationY(dp(40));
        dockPanel.animate().alpha(1f).translationY(0f).setDuration(220).start();
        showHead(true);
        syncTtsOverlay(); // 朗读面板跟着工具坞出现（浮在它上方），小横条收起
        // 搜索导航条也要让位给工具坞，否则会压在「上一章/下一章」上
        if (searchNav != null) searchNav.post(this::syncSearchNavMargin);
    }

    /** 量出 dockHost 当前高度（含/不含朗读浮条），供窗口按内容自适应 */
    private int measureDockHost() {
        if (dockHost == null) return 0;
        int wPx = Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(20));
        int screenH = getResources().getDisplayMetrics().heightPixels;
        dockHost.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST));
        return dockHost.getMeasuredHeight();
    }

    /** 朗读浮条显隐 / 工具坞尺寸变化时，重算窗口大小（窗口 bounds 决定模糊区域） */
    private void relayoutDockWindow() {
        if (dockHost == null || dockWin == null) return;
        int wPx = Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(20));
        int hPx = measureDockHost();
        if (hPx <= 0) return;
        dockWin.update(wPx, hPx, dp(2), dp(22));
    }

    private void hideDock() {
        if (dock == null || !dockShowing) return;
        dockShowing = false;
        syncTtsOverlay(); // 朗读面板收起，底部小横条顶上
        // 工具坞收起：导航条回到贴底位置
        syncSearchNavMargin();
        dock.animate().alpha(0f).translationY(dp(40)).setDuration(160)
                .withEndAction(() -> {
                    if (!dockShowing && dock != null) {
                        dock.setVisibility(View.GONE);
                        // 卡片滑出后把整扇窗口收起来：模糊与卡片一起消失，
                        // 不和下一个覆盖层/页面串台；下次 showDock 再 attach 重挂。
                        if (dockWin != null) dockWin.hide();
                    }
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
        syncMiniHead();
    }

    /** 沉浸页眉跟随菜单：菜单收起时出现，菜单展开时让位给顶栏（否则书名叠两遍） */
    private void syncMiniHead() {
        if (miniHead == null) return;
        String txt = headText();
        boolean on = headImmersive && !headShowing && !scrollMode && !txt.isEmpty();
        // 字号跟着「页眉字号」走；高度同步撑开，否则调大后字被 22dp 的框切掉一半
        miniHead.setTextSize(TypedValue.COMPLEX_UNIT_SP, hfSizeSp);
        if (miniHead.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) miniHead.getLayoutParams();
            // 与 applyStyle 的 headH 同一套算法：带高 = 字高 + 6dp，浮层只占「字高 + 2dp」，
            // 剩下 4dp 就是字底到正文第一行的缝。顶从状态栏下沿起算，不再额外 +3dp，
            // 否则浮层底会比 padT 还低，直接压到首行上。
            Paint p = new Paint();
            p.setTextSize(hfSizeSp * getResources().getDisplayMetrics().density);
            Paint.FontMetrics fm = p.getFontMetrics();
            int h = Math.round(fm.descent - fm.ascent) + dp(2);
            boolean changed = false;
            if (lp.height != h) {
                lp.height = h;
                changed = true;
            }
            if (lp.topMargin != statusBarHeight()) {
                lp.topMargin = statusBarHeight();
                changed = true;
            }
            if (changed) miniHead.setLayoutParams(lp);
        }
        miniHead.setText(txt);
        miniHead.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /**
     * 浮层页眉的文案。
     *
     * 这里原来写死成「书名 · 章节名」，完全不看 headMode —— 用户把页眉设成【章节名】，
     * 菜单一收起顶上照样是「我的时空旅舍 · 第3章 …」。现在与正文页眉共用 headText()，
     * 选中哪项就只出哪项：章节名 / 书名 / 书名 · 章节名。
     */
    private String miniHeadText() {
        return headText();
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
            ttsBarBg = null;
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
        bg.setColor(theme.dark ? 0x14FFFFFF : 0x0F000000);
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

    /** 阅读弹层适配层：公共结构负责三语言外观，本类继续负责动画、避让与实例状态。 */
    private void showSheet(String title, View content) {
        // 替换面板时立即撤掉旧窗口，避免两扇带 dim/模糊的窗口短暂重叠。
        dismissSheetImmediate();
        sheetTitle = title == null ? "" : title;
        final ModouUi.SheetHandle h = ModouUi.readerSheet(
                this, sheetTitle, content, theme, this::closeSheet);
        final Dialog d = h.dialog;
        final FrameLayout wrap = h.root;
        final View panelBox = h.shell;
        d.setOnDismissListener(di -> {
            // 旧面板迟到的 dismiss 不能误清已经打开的新面板。
            if (sheetDialog != di) return;
            sheetDialog = null;
            overlay = null;
            sheetPanel = null;
            sheetShell = null;
            typoShowing = false;
            sheetTitle = "";
            sheetDodging = false;
            hideSeekHud(true);
        });
        d.setOnKeyListener((di, keyCode, ev) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && ev.getAction() == android.view.KeyEvent.ACTION_UP) {
                closeSheet();
                return true;
            }
            return false;
        });
        sheetDialog = d;
        overlay = wrap;
        sheetPanel = h.panel;
        sheetShell = panelBox;
        sheetDodging = false;

        if (effectiveAnimGrade() == 0) {
            panelBox.setElevation(0f);
            wrap.setAlpha(1f);
            panelBox.setTranslationY(0f);
        } else if (effectiveAnimGrade() == 1) {
            panelBox.setElevation(0f);
            wrap.setAlpha(0f);
            panelBox.setTranslationY(0f);
            wrap.animate().alpha(1f).setDuration(ModouMotion.duration(this, 120))
                    .setInterpolator(MD3_DECEL).start();
            runSheetSheen();
        } else {
            panelBox.setElevation(dp(rd().material == DesignSpec.Material.TONAL ? 4 : 16));
            panelBox.setTranslationY(dp(rd().material == DesignSpec.Material.GLASS ? 60 : 36));
            wrap.setAlpha(0f);
            wrap.animate().alpha(1f).setDuration(ModouMotion.duration(this, 180))
                    .setInterpolator(MD3_DECEL).start();
            panelBox.animate().translationY(0f)
                    .setDuration(ModouMotion.duration(this, rd().durMed))
                    .setInterpolator(DesignSpec.interpolator(rd(), true, 1.6f)).start();
            runSheetSheen();
        }
    }

    /** 面板入场流光：一道斜向高光随升起扫过玻璃（仅 ModouGlassDrawable 背景，亮色主题） */
    private void runSheetSheen() {
        if (systemNoAnim || sheetPanel == null) return;
        Drawable b = sheetPanel.getBackground();
        if (!(b instanceof ModouGlassDrawable)) return; // 深色主题是实底 squircle，无流光
        final ModouGlassDrawable g = (ModouGlassDrawable) b;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(340);
        va.setInterpolator(MD3_DECEL);
        va.addUpdateListener(an -> g.setSheen((Float) an.getAnimatedValue()));
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { g.setSheen(-1f); }
        });
        va.start();
    }

    /** 动效切换后即时重放当前档位的进入动画，让「标准 / 简约 / 关闭」的区别立刻可见 */
    private void replaySheetEnter() {
        if (overlay == null || sheetShell == null) return;
        if (effectiveAnimGrade() == 0) {
            sheetShell.setElevation(0f);
            overlay.setAlpha(1f);
            sheetShell.setTranslationY(0f);
        } else if (effectiveAnimGrade() == 1) {
            sheetShell.setElevation(0f);
            overlay.setAlpha(0f);
            sheetShell.setTranslationY(0f);
            overlay.animate().alpha(1f).setDuration(120).setInterpolator(MD3_DECEL).start();
            runSheetSheen();
        } else {
            sheetShell.setElevation(dp(16));
            sheetShell.setTranslationY(dp(60));
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(180).setInterpolator(MD3_DECEL).start();
            sheetShell.animate().translationY(0f).setDuration(280).setInterpolator(SPRING).start();
            runSheetSheen();
        }
    }

    /**
     * 面板动态避让：拖动「下边距 / 页脚字号」时把整个面板沉到屏幕底之外，
     * 只留一条标题在屏幕上，下半屏（正文末行 + 页脚）彻底露出来；松手弹回。
     *
     * 之前是「面板淡到 55%」，可这两根滑杆的观察点在屏幕最底，半透明只是把
     * 要看的正文糊成一片 —— 遮着的还是遮着。下沉才是真的让开。
     */
    private void dodgeSheet(boolean away) {
        if (sheetShell == null || overlay == null) return;
        if (sheetDodging == away) return;
        sheetDodging = away;
        Window w = sheetDialog != null ? sheetDialog.getWindow() : null;
        float h = sheetShell.getHeight();
        if (h <= 0) h = dp(420);
        if (away) {
            // 让开正文末行 / 页脚，但**绝不能把窗口设为 INVISIBLE / dismiss**：滑杆正处在
            // ACTION_DOWN→MOVE 的触摸序列里，窗口一隐身后续 MOVE 就丢了，数值浮窗会卡住。
            // 窗口保持可见且 View 仍是 VISIBLE，只把面板平移出窗口 bounds 被裁剪掉，触摸序列不断。
            if (w != null) {
                w.setDimAmount(0f);            // 关暗化，正文不被压暗
                // 0.9.156：模糊的关/开必须走 ModouGlass 同一套判定。旧实现直接设 radius=0，
                // 但模糊被系统禁用时 windowBackdrop 被 applyBlur 提成了 ~78% 实底，那块实白
                // 仍会盖住页脚；恢复时又无条件设回 radius，禁用态下状态错乱。
                ModouGlass.suspendBlur(w);
            }
            sheetShell.setTranslationY(h + dp(28));    // 松手时从这里弹回
        } else {
            if (w != null) {
                w.setDimAmount(theme.dark ? 0.50f : 0.34f);
                ModouGlass.resumeBlur(w);       // 按系统模糊开关恢复 radius + 配套底色
            }
            sheetShell.animate().translationY(0f)
                    .setDuration(240).setInterpolator(SPRING).start();
        }
    }

    /**
     * 拖动滑杆时的数值浮窗。
     *
     * 按住滑杆，面板必须整块沉到屏幕外（否则上半屏正文被遮，行距变没变根本看不出来），
     * 可数值就写在那一行标题里，跟着一起沉走了 —— 手里拖着一根没有刻度的杆，
     * 只剩「好像宽了点」的模糊感受。这里在屏幕上浮一块胶囊，把当前值顶到眼前。
     *
     * 位置跟着手指走：压在按下的那个点上方 64dp。固定放屏幕底部等于白放 ——
     * 手就按在那里，自己盖着自己，浮窗再清楚也看不见。
     *
     * @param touchY 按下时的屏幕 Y（rawY，含状态栏），-1 表示拿不到，退回屏高 74%
     */
    private void showSeekHud(String title, String value, float touchY) {
        if (root == null) return;
        if (seekHud == null) {
            seekHud = new LinearLayout(this);
            seekHud.setOrientation(LinearLayout.HORIZONTAL);
            seekHud.setGravity(Gravity.CENTER_VERTICAL);
            seekHud.setPadding(dp(18), dp(9), dp(18), dp(9));
            seekHud.setElevation(dp(10));
            seekHud.setClickable(false);
            seekHud.setFocusable(false);
            seekHudTitle = ModouUi.tv(this, "", 12.5f, theme.sub);
            seekHudVal = ModouUi.tv(this, "", 20, theme.acc);
            seekHudVal.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = dp(10);
            seekHud.addView(seekHudTitle);
            seekHud.addView(seekHudVal, vlp);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            // 0.9.154：面板窗口只包住底部卡片，拖滑杆时整扇窗口隐身（dodgeSheet），
            // 数值浮窗挂回阅读页 root 就能浮在任意位置、不会被玻璃壳裁剪。
            root.addView(seekHud, lp);
        } else if (seekHud.getParent() == null) {
            root.addView(seekHud, (FrameLayout.LayoutParams) seekHud.getLayoutParams());
        }
        // 主题可能换过：每次按下重刷一遍色，省得夜间模式浮出一块白底
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(theme.dark ? 0xF21A1F26 : 0xF7FFFFFF);
        // 0.9.181：加一根发丝 rim —— 纯白药丸压在纸面上像贴纸，有边才有「浮起来」的实体感
        bg.setStroke(Math.max(1, ModouUi.dp(this, 0.5f)),
                theme.dark ? 0x2EFFFFFF : 0x14101826);
        seekHud.setBackground(bg);
        seekHudTitle.setTextColor(theme.sub);
        seekHudVal.setTextColor(theme.acc);
        seekHudTitle.setText(title);
        seekHudVal.setText(value);
        placeSeekHud(touchY);
        if (seekHudHide != null) {
            root.removeCallbacks(seekHudHide);
            seekHudHide = null;
        }
        seekHud.animate().cancel();
        seekHud.setAlpha(1f);
        seekHud.setVisibility(View.VISIBLE);
        seekHud.bringToFront();
    }

    /**
     * 把浮窗摆到手指上方 64dp（约一个拇指的直径）。
     *
     * 手指按住的那个点自己看不见 —— 浮窗要是压在手指底下，等于没做。
     * 上方放不下（滑杆本来就靠屏幕顶部）就翻到手指下方，绝不让它被手盖住。
     */
    private void placeSeekHud(float touchY) {
        if (seekHud == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) seekHud.getLayoutParams();
        if (lp == null) return;
        // 浮窗挂在阅读页 root 里，坐标原点就是 root（在状态栏之下），直接用它算。
        int hudH = seekHud.getHeight() > 0 ? seekHud.getHeight() : dp(46);
        int gap = dp(64);
        int top;
        if (touchY < 0) {
            top = Math.round(getResources().getDisplayMetrics().heightPixels * 0.74f) - hudH;
        } else {
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            int y = Math.round(touchY) - loc[1];       // 转成 root 内的坐标
            top = y - hudH - gap;
            if (top < dp(8)) top = y + dp(56);         // 上方没地方，落到手指下方
            int maxTop = root.getHeight() - hudH - dp(8);
            if (top > maxTop) top = Math.max(dp(8), maxTop);
        }
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = top;
        seekHud.setLayoutParams(lp);
    }

    /** 松手后留一小段让用户看清算到的值，再淡出；关面板时立刻抹掉 */
    private void hideSeekHud(boolean now) {
        if (seekHud == null || root == null) return;
        if (now) {
            if (seekHudHide != null) {
                root.removeCallbacks(seekHudHide);
                seekHudHide = null;
            }
            // 0.9.156：淡出动画可能正在跑，只 GONE 会把 alpha 停在半途。
            // 与 showDock 的复位同理，隐藏时把状态清干净。
            seekHud.animate().cancel();
            seekHud.setAlpha(1f);
            seekHud.setVisibility(View.GONE);
            return;
        }
        if (seekHudHide != null) return;
        seekHudHide = () -> {
            seekHudHide = null;
            if (seekHud == null) return;
            seekHud.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                if (seekHud != null) seekHud.setVisibility(View.GONE);
            }).start();
        };
        root.postDelayed(seekHudHide, 260);
    }

    /**
     * 立即关掉当前面板窗口（无退出动画），并清干净全部面板状态。
     * 用于「面板 A → 面板 B」的替换与排版面板重建：这类场景没有「告别」语义，
     * 若走 closeSheet() 的淡出动画，旧窗口在动画结束前仍 show 着，新窗口已叠上来，
     * 两扇 FLAG_DIM_BEHIND + 模糊窗口会短暂叠加，出现一次可见的变暗 / 重影。
     *
     * 先把 sheetDialog 置空再 dismiss：onDismissListener 的实例比对会直接 return，
     * 不与紧接着新建的面板抢状态；状态清理在这里一次做完。
     */
    private void dismissSheetImmediate() {
        final Dialog sd = sheetDialog;
        sheetDialog = null;
        overlay = null;
        sheetPanel = null;
        sheetShell = null;
        sheetDodging = false;
        typoShowing = false;
        sheetTitle = "";
        hideSeekHud(true);
        if (sd != null && sd.isShowing()) sd.dismiss();
    }

    private void closeSheet() {
        typoShowing = false;
        sheetTitle = "";
        sheetDodging = false;
        hideSeekHud(true);
        if (overlay == null) return;
        final View o = overlay;
        // 0.9.153：sheet 已经是独立 Dialog 窗口，退出动画作用在窗口内容上，结束后 dismiss
        // 窗口；用局部变量捕获「本对话框」，避免动画跑完前又开了新面板、字段已指向新窗口时
        // 误把新面板 dismiss 掉。
        final Dialog sd = sheetDialog;
        if (effectiveAnimGrade() == 0) {
            overlay = null;
            if (sd != null && sd.isShowing()) sd.dismiss();
            if (sheetDialog == sd) sheetDialog = null;
            return;
        }
        // 退出用 emphasized-accelerate（200ms）：先淡出再移除，避免生硬消失
        overlay = null;
        o.animate().alpha(0f).setDuration(effectiveAnimGrade() == 2 ? 200 : 120)
                .setInterpolator(effectiveAnimGrade() == 2 ? MD3_EMPH : MD3_DECEL)
                .withEndAction(() -> {
                    if (sd != null && sd.isShowing()) sd.dismiss();
                    if (sheetDialog == sd) sheetDialog = null;
                }).start();
    }

    /**
     * 更多（0.9.50：底部抽屉 → ColorOS 级联卡片）：
     * 浮在工具坞「更多」按钮上方，屏幕常亮是勾选态开关，其余是叶子动作。
     */
    private void openMoreSheet() {
        View anchor = lastDockBtn != null ? lastDockBtn : getWindow().getDecorView();
        java.util.List<CascadingMenu.Item> items = new java.util.ArrayList<>();
        // 屏幕常亮是「状态开关」：用勾选态表达，点一下切换（与旧抽屉同一语义）
        items.add(CascadingMenu.Item.check("屏幕常亮", keepAwake, () -> {
            keepAwake = !keepAwake;
            ModouStore.get(this).putSettingStr("readerKeepAwake", keepAwake ? "1" : "0");
            if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ModouUi.toast(this, keepAwake ? "已开启屏幕常亮" : "已关闭屏幕常亮");
        }));
        items.add(CascadingMenu.Item.divider());
        // 搜书点进来还没入书架的书（preview），第一个动作应该是「加入书架」
        JSONObject cur = bookObj();
        if (cur != null && cur.optBoolean("preview", false)) {
            items.add(CascadingMenu.Item.leaf("加入书架", this::addToShelf));
        }
        items.add(CascadingMenu.Item.leaf("书籍详情", () -> BookActivity.launch(this, bookId)));
        items.add(CascadingMenu.Item.leaf("书签", () -> new TocSheet(1)));
        items.add(CascadingMenu.Item.leaf("重新加载本章", () -> {
            ModouStore.get(this).del("chapters", ModouStore.chapterKey(bookId, index));
            requestChapter(index);
            toast("已重新加载本章");
        }));
        // 编辑本章：只对本地 TXT 开放（EPUB 是 HTML+插图，改纯文本会破坏结构）
        JSONObject kb = bookObj();
        if (kb != null && "TXT".equalsIgnoreCase(kb.optString("kind", ""))) {
            items.add(CascadingMenu.Item.leaf("编辑本章", this::editChapter));
        }
        items.add(CascadingMenu.Item.leaf("图标排布", this::showMenuLayout));
        items.add(CascadingMenu.Item.leaf("设置", () -> SettingsActivity.launch(this)));
        items.add(CascadingMenu.Item.divider());
        items.add(CascadingMenu.Item.leaf("退出阅读", this::onBackPressed));
        CascadingMenu.showAbove(this, anchor, items);
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
    /**
     * 编辑本章（本地 TXT 书）。
     * 取正文、切纯文本全部在后台线程做（一章几十 KB 时 Paras.blocks 也要几十毫秒，
     * 以前在打开弹窗的主线程上跑，图多的章节会卡一下），弹窗本身回主线程再建。
     */
    private void editChapter() {
        final String name = chapterNameOf(index);
        io.execute(() -> {
            String html = ModouStore.get(this).chapterContent(bookId, index);
            final StringBuilder before = new StringBuilder();
            if (html != null && !html.trim().isEmpty()) {
                for (Paras.Block b : Paras.blocks(html, name)) {
                    if (b.isImage()) continue;
                    if (before.length() > 0) before.append('\n');
                    before.append(b.value);
                }
            }
            if (before.length() == 0) {
                runOnUiThread(() -> toast("本章没有可编辑的文本"));
                return;
            }
            runOnUiThread(() -> showEditDialog(name, before.toString()));
        });
    }

    private void showEditDialog(String name, String before) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(this, 14);
        box.setPadding(p, ModouUi.dp(this, 8), p, 0);
        EditText ed = ModouUi.input(this, "章节正文", before, 6);
        ed.setTextColor(theme.text);
        ed.setHintTextColor(theme.sub);
        ed.setGravity(Gravity.TOP | Gravity.START);
        ed.setContentDescription("编辑章节正文");
        // 高度自适应：先按最小高度插入，量完文本后按实际行数撑开，上限取「弹层真正
        // 能给的空间」（与 ModouUi.sheetForm 的滚动区口径一致），超出就在框内滚动。
        // 不再写死「屏高的百分之几」：横屏机器屏高本来就矮，62% 只剩两百来 dp，
        // 再叠上说明和按钮，弹层直接被顶出屏幕。
        final int sh = getResources().getDisplayMetrics().heightPixels;
        final boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int scrollCap = Math.max(ModouUi.dp(this, 96),
                (int) (sh * (land ? 0.74f : 0.62f)) - ModouUi.dp(this, 76));
        final int edMin = Math.min(ModouUi.dp(this, 150), scrollCap);
        final int edMax = Math.max(edMin, scrollCap - ModouUi.dp(this, 52));
        final LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, edMin);
        box.addView(ed, elp);
        ed.post(() -> {
            android.text.Layout lay = ed.getLayout();
            if (lay == null) return;
            int contentH = lay.getHeight() + ed.getPaddingTop() + ed.getPaddingBottom()
                    + ModouUi.dp(NativeReaderActivity.this, 6);
            int h = Math.max(edMin, Math.min(contentH, edMax));
            if (h != elp.height) {
                elp.height = h;
                ed.setLayoutParams(elp);
            }
        });
        TextView note = ModouUi.tv(this,
                "每行一段，保存后从本章开头重新显示。修改写进书库（阅读立即生效），并尝试同步写回手机上的原 .txt 文件。",
                11.5f, theme.sub);
        note.setPadding(0, ModouUi.dp(this, 6), 0, 0);
        box.addView(note);
        LinearLayout editFooter = new LinearLayout(this);
        editFooter.setOrientation(LinearLayout.VERTICAL);
        final Dialog d = ModouUi.sheetForm(this, "编辑 · " + name, box, editFooter);
        editFooter.addView(ModouUi.sheetButtonsGuarded(this, d, "取消", "保存", null, () -> {
            String t = String.valueOf(ed.getText());
            if (t.contentEquals(before)) return true;
            StringBuilder h = new StringBuilder();
            for (String line : t.split("\n", -1)) {
                String s = line.replace("\u3000", "").trim();
                if (s.isEmpty()) continue;
                if (h.length() > 0) h.append('\n');
                h.append("<p>").append(escapeHtml(s)).append("</p>");
            }
            if (h.length() == 0) {
                ed.setError("章节内容不能为空");
                ed.requestFocus();
                return false;
            }
            try {
                ModouStore.get(this).cacheChapter(bookId, index, h.toString());
            } catch (Throwable e) {
                toast("保存失败：" + (e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage()));
                return false;
            }
            requestChapter(index, 0); // 分段变了，旧偏移不可靠，回本章开头
            // 写回原文件放后台：整本重建 + 网络/SAF 写可能慢，不挡阅读
            io.execute(() -> {
                String fb = writeBackToFile();
                runOnUiThread(() -> toast(fb));
            });
            return true;
        }));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 保存章节修改后写回原文件（仅本地 TXT）。
     *
     * 从书库逐章重建整本文本：章名独立成行（与 TxtBook 的分章规则同构，重新导入
     * 仍能分出同一套章节），章内正文 = Paras.blocks 的纯文本，按原编码写回 SAF 文档。
     * 找不到可写的原文件时只改书库、返回说明文字（EPUB 不参与，见 openMoreSheet 的守卫）。
     */
    private String writeBackToFile() {
        JSONObject b = bookObj();
        if (b == null) return "已保存（书库），原文件写回失败：书籍记录缺失";
        if (!"TXT".equalsIgnoreCase(b.optString("kind", ""))) return "已保存到书库";
        String fileUri = b.optString("fileUri", "");
        if (fileUri.isEmpty()) {
            fileUri = resolveFileUri(b);
            if (fileUri.isEmpty()) return "已保存到书库（找不到可写回的原文件，换「重新导入」覆盖更新）";
            try {
                b.put("fileUri", fileUri);
                ModouStore.get(this).put("books", b.toString());
                if (bookCache != null) bookCache = b;
            } catch (Exception ignore) {
                /* 写回地址记不上也不影响本次写回 */
            }
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < total; i++) {
            String nm = chapterNameOf(i);
            String html = ModouStore.get(this).chapterContent(bookId, i);
            if (text.length() > 0) text.append("\n\n");
            if (nm != null && !nm.trim().isEmpty()) {
                text.append(nm.trim()).append("\n").append("\n");
            }
            if (html != null && !html.isEmpty()) {
                List<Paras.Block> blocks = Paras.blocks(html, nm);
                for (Paras.Block bl : blocks) {
                    if (bl.isImage()) continue;
                    if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
                    text.append(bl.value);
                }
            }
        }
        if (text.length() == 0) return "已保存到书库（重建文本为空，未写回原文件）";
        try {
            Uri u = Uri.parse(fileUri);
            ContentResolver cr = getContentResolver();
            Charset cs = Charset.forName("UTF-8");
            try (InputStream in = cr.openInputStream(u)) {
                if (in != null) {
                    byte[] head = new byte[1 << 16];
                    int n = 0;
                    while (n < head.length) {
                        int r = in.read(head, n, head.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                    if (n > 0) cs = TxtBook.detectCharset(java.util.Arrays.copyOf(head, n));
                }
            }
            try (OutputStream os = cr.openOutputStream(u, "wt")) {
                if (os == null) return "已保存到书库（原文件不可写：没有写权限，可重新选文件夹导入）";
                os.write(text.toString().getBytes(cs));
            }
            return "已保存，并已写回手机上的原文件";
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
            return "已保存到书库（写回原文件失败：" + m + "）";
        }
    }

    /** 老版本导入的书没有 fileUri：按「文件名 + 字节数」在系统媒体库里找回对应文档 */
    private String resolveFileUri(JSONObject b) {
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
        } catch (Exception ignore) {
            /* 找不到就返回空串，调用方走书库-only 分支 */
        }
        return "";
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

    /**
     * 菜单行：左侧 20dp 线性图标 + 文字。
     *
     * <p>以前是一排纯文字（只有 label 的 TextView），一行行像设置项列表的最低配版本；
     * ColorOS 的菜单每项都带图标，眼睛先扫图标再读字，行高也更松。
     */
    private View sheetItem(int glyph, String label, Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(13), dp(2), dp(13));

        ImageView ic = new ImageView(this);
        ic.setScaleType(ImageView.ScaleType.CENTER);
        ic.setImageDrawable(new ModouGlyph(glyph,
                theme.dark ? 0xFFB9BBC0 : theme.sub, dp(20), ModouUi.dp(this, 1.4f)));
        row.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(theme.text);
        t.setPadding(dp(14), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> r.run());
        // 按压反馈：整行淡墨，不越界
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(12));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        theme.dark ? 0x1FFFFFFF : 0x14000000), null, mask));
        return row;
    }

    /** 菜单项 + 与上一项之间的极细分割线（左缩进对齐文字，不画满整宽） */
    private void addMenuItem(LinearLayout list, int glyph, String label, Runnable r) {
        if (list.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(theme.dark ? 0x14FFFFFF : 0x0F000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.leftMargin = dp(36);
            list.addView(line, lp);
        }
        list.addView(sheetItem(glyph, label, r));
    }

    /**
     * 菜单顶部的开关区块：整块浅底圆角，下面留 6dp 空隙，和点击列表分开。
     * 只给「屏幕常亮」这类当场生效、不必关菜单的项用。
     */
    private void addSwitchMenuItem(LinearLayout list, String label, boolean on, PickBool cb) {
        LinearLayout row = toggleRow(label, on, cb);
        row.setPadding(dp(14), dp(8), dp(12), dp(8));
        row.setBackground(ModouUi.squircle(this, chipBg(),
                rd().material == DesignSpec.Material.TONAL ? 20
                        : rd().material == DesignSpec.Material.LIQUID ? 14 : 16));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(6);
        list.addView(row, rlp);
    }

    /* ---------------- 排版面板 ---------------- */
    /* ---------------- 排版快捷预设 ---------------- */

    /**
     * 三档预设存在 settings 里（JSON），用户长按改过也不会因为升级丢失。
     *
     * 键名带 V2：0.9.5 重写了出厂参数，沿用旧键的话老用户读到的仍是被写进库里的
     * 上一版数值（行距 2.0 / 段距 1.0，套用后字挤成一团），改默认值等于白改。
     */
    private static final String PRESET_KEY = "readerPresetsV4";
    private static final String[] PRESET_NAME = {"紧凑", "标准", "宽松"};

    /**
     * 行距硬下限。
     *
     * CJK 字体自身的 ascent+descent 约 1.42em，行高低于 1.5em 时字形的上下边缘只剩
     * 零点几个像素 —— 看上去就是「行与行叠在一起」。1.45 那一版紧凑档正是踩在这条线上
     * （16sp × 1.45 与字形高只差 0.5px）。1.5 仍然偏挤：实测 1.5 倍时行间只剩
     * 0.08em 的白，长段阅读会糊成一片。抬到 1.7（留 0.28em，约 5sp），滑杆下限同步。
     */
    private static final float MIN_LINE_MUL = 1.7f;
    private static final float MIN_PARA_GAP = 0.4f;

    /**
     * 出厂三档：字号 / 行距 / 段距 / 页边距。
     *
     * 按主流阅读器的经验值取：正文行距 1.5–1.6 是「不挤也不散」的甜点，
     * 段距 0.6–0.8em 足以区分段落又不会把正文拉出断层；页边距保证每行 18–22 字。
     *  紧凑 16sp / 1.45 / 0.5 / 14 —— 一屏塞更多字，适合快读
     *  标准 18sp / 1.60 / 0.8 / 20 —— 默认
     *  宽松 21sp / 1.80 / 1.1 / 26 —— 大字 + 大留白，适合睡前长读
     */
    private static JSONObject presetDefaults() {
        JSONObject o = new JSONObject();
        try {
            o.put("p0", presetOf(16, 1.75f, 0.9f, 16));
            o.put("p1", presetOf(18, 1.85f, 1.0f, 20));
            o.put("p2", presetOf(21, 2.05f, 1.3f, 26));
        } catch (Exception ignore) {
            /* JSONObject.put 不会失败 */
        }
        return o;
    }

    private static JSONObject presetOf(int fs, float lh, float pg, int mg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("fontSize", fs);
        o.put("lineHeight", lh);
        o.put("paraGap", pg);
        o.put("margin", mg);
        return o;
    }

    private JSONObject presets() {
        try {
            String raw = ModouStore.get(this).settingStr(PRESET_KEY, "");
            if (raw != null && !raw.isEmpty()) {
                JSONObject o = new JSONObject(raw);
                JSONObject dft = presetDefaults();
                // 老版本只存了一两档时补齐，避免点「宽松」没反应
                for (int i = 0; i < 3; i++) {
                    if (!o.has("p" + i)) o.put("p" + i, dft.optJSONObject("p" + i));
                }
                return o;
            }
        } catch (Exception ignore) {
            /* 存档坏了就用出厂值 */
        }
        JSONObject d = presetDefaults();
        ModouStore.get(this).putSettingStr(PRESET_KEY, d.toString());
        return d;
    }

    /**
     * 三个并排胶囊：紧凑 / 标准 / 宽松。
     *
     * 不再写「18sp · 行距 2.0」这类参数说明 —— 用户点预设是为了「立刻看到正文变舒服」，
     * 不是为了读一串数字；参数就摆在下面的滑杆里，想知道细节直接看滑杆。
     * 长按仍然把当前参数存进这一档（保留能力，但不做任何提示占用版面）。
     */
    private View presetRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView cap = ModouUi.tv(this, "预设槽位", 13, theme.sub);
        cap.setPadding(0, ModouUi.dp(this, 6), 0, 0);
        wrap.addView(cap);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 4));
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView b = ModouUi.tv(this, PRESET_NAME[i], 13, theme.text);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, ModouUi.dp(this, 9), 0, ModouUi.dp(this, 9));
            b.setBackground(pillBg(false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, ModouUi.dp(this, 6), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> applyPreset(idx));
            b.setOnLongClickListener(v -> {
                savePreset(idx);
                return true;
            });
            row.addView(b);
        }
        wrap.addView(row);
        return wrap;
    }

    private void applyPreset(int idx) {
        JSONObject o = presets().optJSONObject("p" + idx);
        if (o == null) return;
        fontSize = Math.max(12, Math.min(40, o.optInt("fontSize", 18)));
        lineHeight = Math.max(MIN_LINE_MUL, Math.min(2.8f, (float) o.optDouble("lineHeight", 1.6)));
        paraGap = Math.max(MIN_PARA_GAP, Math.min(2.4f, (float) o.optDouble("paraGap", 0.8)));
        marginPx = Math.max(8, Math.min(44, o.optInt("margin", 20)));
        applyStyleNow();
        // 不重建面板：重建会在点击回调里把整棵 View 树拆掉，之后滑杆拖不动。
        // 直接把新值刷回三根滑杆和字号文本，控件与监听都还是原来那套。
        refreshSeeks();
        if (sizeLabel != null) sizeLabel.setText(fontSize + " px");
        ModouUi.toast(this, "已套用「" + PRESET_NAME[idx] + "」");
    }

    private void savePreset(int idx) {
        try {
            JSONObject ps = presets();
            ps.put("p" + idx, presetOf(fontSize, lineHeight, paraGap, marginPx));
            ModouStore.get(this).putSettingStr(PRESET_KEY, ps.toString());
            ModouUi.toast(this, "已把当前参数存为「" + PRESET_NAME[idx] + "」");
            refreshSeeks();
        } catch (Exception e) {
            ModouUi.toast(this, "保存失败：" + e.getMessage());
        }
    }

    /** 把当前排版参数刷回滑杆：预设套用后必须调，否则滑杆还停在旧位置且与正文不一致 */
    private void refreshSeeks() {
        for (SeekBind b : seekBinds) {
            int v = b.cur.getAsInt();
            v = Math.max(b.min, Math.min(b.max, v));
            int prog = Math.max(0, Math.min(b.sb.getMax(), v - b.min));
            if (b.sb.getProgress() != prog) b.sb.setProgress(prog);
            b.label.setText(b.fmt.of(v));
        }
    }

    /** 重建排版抽屉：预设文案和滑杆位置都要跟着新参数走，逐个 setProgress 容易漏 */
    private void refreshTypoPanel() {
        if (overlay == null && sheetDialog == null) return;
        // 0.9.156：与 showSheet 共用同一个「即时替换」入口，不再手写一遍 dismiss。
        dismissSheetImmediate();
        showTypoPanel();
    }

    /**
     * 排版面板：从宏观到微观。
     *
     * 顺序是硬约束（主题 → 预设 → 字号 → 字体 → 字重 → 行距 → 段距 → 页边距 →
     * 两个开关 → 页面与交互折叠区）。以前主题夹在中间、翻页方式/页眉页脚平铺，
     * 十几行同级控件谁都不比谁重要；现在高频的四项（字号/行距/段距/页边距）连成一段，
     * 低频的交互设置全部收进折叠区，默认收起。
     */
    /* ============ 极简·纪排版面板：纯 TextView 胶囊，零滑杆 / 零 Switch / 零粗线框 ============ */

    private static final int MONO_PANEL_BG = 0xFF242731;
    private static final int MONO_CHIP_ON = 0x1F5C7CE0;   // 极低透明度淡蓝实心块
    private static final int MONO_TEXT = 0xFFFFFFFF;
    private static final int MONO_SUB = 0xFF9AA0B0;

    private TextView monoLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(MONO_SUB);
        t.setPadding(0, ModouUi.dp(this, 12), 0, ModouUi.dp(this, 6));
        return t;
    }

    private View monoDivider() {
        View v = new View(this);
        v.setBackgroundColor(0x14FFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ModouUi.dp(this, 1))));
        return v;
    }

    /** 胶囊：选中 = 极低透明度淡蓝实心圆角块 + 白字；未选中 = 透明底 + 灰字。无描边。 */
    private TextView monoChip(String text, boolean on) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(on ? MONO_TEXT : MONO_SUB);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setPadding(ModouUi.dp(this, 8), ModouUi.dp(this, 9),
                ModouUi.dp(this, 8), ModouUi.dp(this, 9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? MONO_CHIP_ON : 0x00000000);
        g.setCornerRadius(ModouUi.dp(this, 999));
        t.setBackground(g);
        t.setMinimumHeight(ModouUi.dp(this, 36));
        t.setContentDescription(text + (on ? "，已选中" : "，未选中"));
        return t;
    }

    /** 单选行：点哪个哪个变淡蓝底，就地重绘，不重建面板。 */
    private LinearLayout monoChipRow(String title, String[] labels, int cur, Pick cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(monoLabel(title));
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        final TextView[] chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = monoChip(labels[i], i == cur);
            chips[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, ModouUi.dp(this, 6), 0);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> {
                for (int j = 0; j < chips.length; j++) {
                    ((GradientDrawable) chips[j].getBackground())
                            .setColor(j == idx ? MONO_CHIP_ON : 0x00000000);
                    chips[j].setTextColor(j == idx ? MONO_TEXT : MONO_SUB);
                    chips[j].setContentDescription(labels[j]
                            + (j == idx ? "，已选中" : "，未选中"));
                }
                if (cb != null) cb.on(idx);
            });
            line.addView(t);
        }
        row.addView(line);
        return row;
    }

    /** 数值档位行：标签是纯文字，values 是实际写回字段的值。 */
    private LinearLayout monoValueRow(String title, String[] labels, int[] values,
                                      int cur, Pick cb) {
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == cur) { sel = i; break; }
        }
        return monoChipRow(title, labels, sel, i -> { if (cb != null) cb.on(values[i]); });
    }

    /** [关] [开] 文字胶囊，替代药丸 Switch。 */
    private LinearLayout monoSwitchRow(String title, boolean on, PickBool cb) {
        return monoChipRow(title, new String[]{"关", "开"}, on ? 1 : 0,
                i -> { if (cb != null) cb.on(i == 1); });
    }

    /** 多选行：每项独立 on/off（页脚六项，两行三列）。 */
    private LinearLayout monoMultiRow(String title, String[] labels, int[] bits,
                                      int mask, java.util.function.IntConsumer cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(monoLabel(title));
        final int[] state = {mask};
        LinearLayout line = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 3 == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(line);
            }
            final int bit = bits[i];
            TextView t = monoChip(labels[i], (state[0] & bit) != 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, ModouUi.dp(this, 6), ModouUi.dp(this, 6));
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> {
                state[0] ^= bit;
                boolean on = (state[0] & bit) != 0;
                ((GradientDrawable) t.getBackground())
                        .setColor(on ? MONO_CHIP_ON : 0x00000000);
                t.setTextColor(on ? MONO_TEXT : MONO_SUB);
                if (cb != null) cb.accept(state[0]);
            });
            line.addView(t);
        }
        return row;
    }

    /**
     * 极简·纪专属排版面板：纯实色底 #242731，全部控件都是 TextView 套圆角背景，
     * 不创建 ModouPillSeekBar、不创建 Switch、不画蓝色粗线框。
     */
    private void showTypoPanelMono() {
        seekBinds.clear();       // 无滑杆：预设套用时的 refreshSeeks() 走空列表
        sizeLabel = null;
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.68f);
        final ScrollView sc = new ScrollView(this) {
            @Override protected void onMeasure(int wSpec, int hSpec) {
                super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        sc.setBackgroundColor(MONO_PANEL_BG);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(MONO_PANEL_BG);
        box.setPadding(ModouUi.dp(this, 18), ModouUi.dp(this, 8),
                ModouUi.dp(this, 18), ModouUi.dp(this, 24));

        TextView head = new TextView(this);
        head.setText("排版");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        head.setTextColor(MONO_TEXT);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setPadding(0, ModouUi.dp(this, 4), 0, ModouUi.dp(this, 10));
        box.addView(head);

        // 阅读纸张入口
        LinearLayout paper = new LinearLayout(this);
        paper.setOrientation(LinearLayout.HORIZONTAL);
        paper.setGravity(Gravity.CENTER_VERTICAL);
        paper.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
        TextView pt = new TextView(this);
        pt.setText("阅读纸张");
        pt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pt.setTextColor(MONO_SUB);
        paper.addView(pt, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pv = new TextView(this);
        pv.setText(DesignSpec.paperLabel(darkMode ? "dark" : lightTheme));
        pv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pv.setTextColor(MONO_TEXT);
        paper.addView(pv);
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        arrow.setTextColor(MONO_SUB);
        arrow.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
        paper.addView(arrow);
        paper.setOnClickListener(v -> PaperGalleryActivity.launchForResult(this, REQ_PAPER));
        box.addView(paper);
        box.addView(monoDivider());

        // 预设槽位
        box.addView(monoLabel("预设槽位"));
        LinearLayout preset = new LinearLayout(this);
        preset.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView b = monoChip(PRESET_NAME[i], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, ModouUi.dp(this, 6), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> applyPreset(idx));
            b.setOnLongClickListener(v -> { savePreset(idx); return true; });
            preset.addView(b);
        }
        box.addView(preset);
        box.addView(monoDivider());

        box.addView(monoValueRow("字号", new String[]{"14", "16", "18", "20", "22", "24"},
                new int[]{14, 16, 18, 20, 22, 24}, fontSize,
                v -> { fontSize = v; applyStyleNow(); }));
        box.addView(monoDivider());

        box.addView(monoChipRow("字体", new String[]{"宋体", "黑体", "楷体"},
                fontIndex(font), i -> {
                    font = new String[]{"song", "hei", "kai"}[i];
                    pageView.clearPageCache();
                    applyStyleNow();
                }));
        box.addView(monoDivider());

        box.addView(monoChipRow("字重", new String[]{"细", "常规", "中粗", "粗"},
                weightIndex(weightVal), i -> {
                    weightVal = new int[]{300, 400, 500, 600}[i];
                    applyStyleNow();
                }));
        box.addView(monoDivider());

        // 行距档位取现有合法区间（下限 MIN_LINE_MUL=1.7）：若按示例给 1.2/1.5，
        // applyStyle 会夹到 1.7，面板读数与正文不一致。
        box.addView(monoChipRow("行距", new String[]{"1.7", "1.9", "2.1", "2.4"},
                lineHeight < 1.8f ? 0 : lineHeight < 2.0f ? 1 : lineHeight < 2.3f ? 2 : 3,
                i -> {
                    lineHeight = new float[]{1.7f, 1.9f, 2.1f, 2.4f}[i];
                    applyStyleNow();
                }));
        box.addView(monoDivider());

        box.addView(monoChipRow("段距", new String[]{"无", "0.5", "1.0", "1.5"},
                paraGap < 0.25f ? 0 : paraGap < 0.75f ? 1 : paraGap < 1.25f ? 2 : 3,
                i -> {
                    paraGap = new float[]{0f, 0.5f, 1.0f, 1.5f}[i];
                    applyStyleNow();
                }));
        box.addView(monoDivider());

        box.addView(monoValueRow("左右页边距", new String[]{"8", "16", "24", "36"},
                new int[]{8, 16, 24, 36}, marginPx,
                v -> { marginPx = v; applyStyleNow(); }));
        box.addView(monoValueRow("上边距", new String[]{"0", "12", "24", "48"},
                new int[]{0, 12, 24, 48}, topGapDp,
                v -> { topGapDp = v; applyStyleNow(); }));
        box.addView(monoValueRow("下边距", new String[]{"0", "12", "24", "48"},
                new int[]{0, 12, 24, 48}, botGapDp,
                v -> { botGapDp = v; applyStyleNow(); }));
        box.addView(monoDivider());

        box.addView(monoChipRow("文字亮度", new String[]{"50%", "75%", "100%"},
                brightness < 0.625f ? 0 : brightness < 0.875f ? 1 : 2,
                i -> {
                    brightness = new float[]{0.5f, 0.75f, 1f}[i];
                    if (darkMode) brightnessDark = brightness; else brightnessLight = brightness;
                    pageView.setBright(brightness);
                    syncSettings();
                }));
        box.addView(monoValueRow("页眉/页脚字号", new String[]{"9", "12", "15", "20"},
                new int[]{9, 12, 15, 20}, Math.round(hfSizeSp),
                v -> { hfSizeSp = v; applyStyleNow(); }));
        box.addView(monoDivider());

        box.addView(monoSwitchRow("行尾标点不压缩", justifyHang, on -> {
            justifyHang = on;
            applyStyleNow();
        }));
        box.addView(monoSwitchRow("隐藏书内图片", hideImages, on -> {
            hideImages = on;
            ModouStore.get(this).putSettingStr("readerHideImages", on ? "1" : "0");
            applyStyleNow();
        }));
        box.addView(monoSwitchRow("沉浸时显示页眉", headImmersive, on -> {
            headImmersive = on;
            ModouStore.get(this).putSettingStr("readerHeadImmersive", on ? "1" : "0");
            applyStyleNow();
        }));
        box.addView(monoDivider());

        box.addView(monoChipRow("页眉", new String[]{"不显示", "章节名", "书名", "书名+章"},
                headMode, i -> {
                    headMode = i;
                    saveHeadFoot();
                    applyStyleNow();
                }));
        box.addView(monoMultiRow("页脚（可多选）",
                new String[]{"页码", "时间", "电量", "全书进度", "本章进度", "剩余章数"},
                new int[]{1, 2, 4, 8, 16, 32}, footMask, m -> {
                    footMask = m;
                    saveHeadFoot();
                    readBattery();
                    applyStyleNow();
                    hfHandler.removeCallbacks(hfTick);
                    if (footMask != 0) hfHandler.postDelayed(hfTick, 30000);
                }));
        box.addView(monoDivider());

        box.addView(monoChipRow("点击区域", new String[]{"经典", "左手", "单手"},
                "left".equals(tapMode) ? 1 : "single".equals(tapMode) ? 2 : 0,
                i -> {
                    tapMode = i == 1 ? "left" : i == 2 ? "single" : "classic";
                    ModouStore.get(this).putSettingStr("readerTapMode", tapMode);
                    applyTapZones();
                }));
        box.addView(monoChipRow("翻页方式", new String[]{"左右翻页", "上下滚动"},
                scrollMode ? 1 : 0, i -> {
                    scrollMode = i == 1;
                    applyStyleNow();
                }));
        box.addView(monoChipRow("动效", new String[]{"关闭", "简约", "标准"},
                animGrade, i -> {
                    animGrade = i;
                    ModouStore.get(this).putSettingStr("uiAnimGrade", String.valueOf(i));
                    replaySheetEnter();
                }));

        sc.addView(box);
        showSheet("", sc);
        typoShowing = true;
        // 外壳也压成纯实色：杜绝半透明玻璃把正文文字透上来叠在一起
        if (sheetShell != null) {
            GradientDrawable solid = new GradientDrawable();
            solid.setColor(MONO_PANEL_BG);
            solid.setCornerRadius(ModouUi.dp(this, 24));
            sheetShell.setBackground(solid);
            sheetShell.setElevation(0f);
        }
        if (sheetPanel != null) sheetPanel.setBackgroundColor(0x00000000);
    }

    /** 极简·纪工具坞按钮：只有图标，没有下方文字、没有药丸底、没有选中色块。 */
    private View dockIconOnly(int iconRes, boolean on, boolean disabled, Runnable r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.setPadding(0, dp(14), 0, dp(14));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(tinted(iconRes, on ? theme.acc : monoInk()));
        int s = dp(22);
        box.addView(iv, new LinearLayout.LayoutParams(s, s));
        if (disabled) box.setAlpha(0.3f);
        else box.setOnClickListener(v -> { lastDockBtn = v; r.run(); });
        return box;
    }

    /** 「Aa」是文字图标，同样去掉下方标签。 */
    private View dockAaIcon(Runnable r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.setPadding(0, dp(14), 0, dp(14));
        TextView aa = new TextView(this);
        aa.setText("Aa");
        aa.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        aa.setTypeface(Typeface.SERIF, Typeface.BOLD);
        aa.setTextColor(monoInk());
        aa.setGravity(Gravity.CENTER);
        box.addView(aa, new LinearLayout.LayoutParams(dp(22), dp(22)));
        box.setOnClickListener(v -> r.run());
        return box;
    }

    private void showTypoPanel() {
        if (mono()) { showTypoPanelMono(); return; }
        // 面板高度收到 58%：拖滑杆时要能看见上半屏正文（配合拖动时的半透明）
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.58f);
        seekBinds.clear();
        sizeLabel = null;
        // 高度改成「跟着内容走、最高 68%」：以前固定 maxH，收起折叠区时下面永远
        // 挂着一大块空白；现在内容少就矮，超过上限才出现滚动。
        final ScrollView sc = new ScrollView(this) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, ModouUi.dp(this, 2), 0, ModouUi.dp(this, 6));
        // 折叠区要按真实宽度量高：sheet 左右各 10dp、内部再 18dp 内边距
        final int panelW = getResources().getDisplayMetrics().widthPixels
                - ModouUi.dp(this, 10) * 2 - ModouUi.dp(this, 18) * 2;

        // 1 阅读纸张（0.9.161）：四档快捷 segment 换成画廊入口。
        // 用户要求纸张主题也做成参考图那种「大预览卡 + 缩略卡网格 + 确认条」；
        // 这里再留一枚 segment 就成了同一功能的第二处入口（与设置页深浅重复的同类问题），
        // 故只留画廊。dock 里的「夜间 / 日间」是阅读态快捷开关，语义不同，保留。
        box.addView(paperEntry());
        box.addView(div());

        // 2 预设槽位：三个滑杆一起调太麻烦，先给出「紧凑 / 标准 / 宽松」三套成品
        box.addView(presetRow());
        box.addView(div());

        // 2.5 文字亮度/柔化：把正文文字 Paint 的透明度按滑杆缩放（100% = 主题原色，
        // 50% = 半透 → 夜间白字直接变灰，不再刺眼）。只改「正文」颜色，背景与面板文字不动；
        // 与排版无关、不重排，故 live=false（拖动即时预览、不触发整章重排）。
        box.addView(seekRow("文字亮度", 50, 100, 1, Math.round(brightness * 100),
                v -> v + "%",
                () -> Math.round(brightness * 100),
                v -> {
                    // 必须先回写字段：滑杆初值、refreshSeeks()、落库全都读 brightness，
                    // 只喂给 pageView 的话面板一关一开就按旧值重建，看上去像「没保存」
                    brightness = v / 100f;
                    // 写进「当前模式」那一份，别覆盖另一模式的亮度
                    if (darkMode) brightnessDark = brightness; else brightnessLight = brightness;
                    pageView.setBright(brightness);
                    syncSettings();   // 与排版无关，最终值直接落库，不必走 applyStyleNow
                }, true, 0, false));
        box.addView(div());

        // 3 字号
        box.addView(sizeRow("字号", fontSize));
        box.addView(div());

        // 4 字体
        box.addView(segRow("字体", new String[]{"宋体", "黑体", "楷体"},
                fontIndex(font), i -> {
                    font = new String[]{"song", "hei", "kai"}[i];
                    // 先清页表缓存：字宽变了而字号/边距没变，最容易命中旧页表原样装回去
                    pageView.clearPageCache();
                    applyStyleNow();
                }));
        box.addView(div());

        // 5 字重
        box.addView(segRow("字重", new String[]{"细", "常规", "中粗", "粗"},
                weightIndex(weightVal), i -> {
                    weightVal = new int[]{300, 400, 500, 600}[i];
                    applyStyleNow();
                }));
        box.addView(div());

        // 6 行距 1.70–2.60：按 0.01 一档（90 档）。下限与 MIN_LINE_MUL 对齐，
        // 否则拖到最左显示 1.50 而正文按 1.70 排，读数就是假的。
        // 以前是 0.1 一档，整条轨道只有 11 个落点，拖十几 dp 才蹦一格，
        // 手感就是「调了一点没反应」；细分之后每 2~3dp 就动一档，滑过去是连续变宽。
        box.addView(seekRow("行距", 170, 260, 1, Math.max(170, Math.round(lineHeight * 100)),
                v -> String.format(java.util.Locale.US, "%.2f", v / 100f) + " 倍",
                () -> Math.round(lineHeight * 100), v -> lineHeight = v / 100f));
        box.addView(div());

        // 7 段距 0–2.0：0.05 一档（40 档）。段距只作用在段与段之间，
        // 一屏通常五六段，同样的倍数变化看起来比行距还弱，所以档位比行距粗一档。
        box.addView(seekRow("段距", 0, 200, 5, Math.round(paraGap * 20) * 5,
                v -> v == 0 ? "无" : String.format(java.util.Locale.US, "%.2f", v / 100f) + " 倍",
                () -> Math.round(paraGap * 20) * 5,
                v -> paraGap = v / 100f));
        box.addView(div());

        // 8 左右页边距 12–44
        box.addView(seekRow("左右页边距", 8, 36, 2, marginPx,
                v -> v + " px", () -> marginPx, v -> marginPx = v));
        box.addView(div());

        // 9 上边距 / 10 下边距：与行距段距同属「版心几何」，必须在主面板随手可调。
        // 下边距调到 0 时正文最后一行贴页脚上边缘（带高只留字高 + 4dp）。
        box.addView(seekRow("上边距", 0, 48, 2, topGapDp,
                v -> v + " dp", () -> topGapDp, v -> topGapDp = v, true, 1));
        box.addView(div());
        box.addView(seekRow("下边距", 0, 48, 2, botGapDp,
                v -> v + " dp", () -> botGapDp, v -> botGapDp = v, true, 2));
        box.addView(div());

        // 9 两端对齐始终开启（不再设开关）：用一行说明文字替代原来的开关
        {
            LinearLayout tip = new LinearLayout(this);
            tip.setOrientation(LinearLayout.HORIZONTAL);
            tip.setGravity(Gravity.CENTER_VERTICAL);
            tip.setPadding(0, ModouUi.dp(this, 11), 0, ModouUi.dp(this, 11));
            tip.addView(ModouUi.tv(this, "正文自动两端对齐（段落末行除外）", 13f, theme.sub));
            box.addView(tip);
        }
        // 9.1 行尾标点悬挂：关=行尾句读压半字（默认，右缘贴版心）；开=不压缩、标点按全宽落版心
        box.addView(toggleRow("行尾标点不压缩", justifyHang, on -> {
            justifyHang = on;
            applyStyleNow();
        }));
        box.addView(div());

        // 10 隐藏书内图片：EPUB 的章节插图、封面插图一律不排（整块跳过，不留空行）
        box.addView(toggleRow("隐藏书内图片", hideImages, on -> {
            hideImages = on;
            ModouStore.get(this).putSettingStr("readerHideImages", on ? "1" : "0");
            applyStyleNow();
        }));

        /* 11 页面与交互：页眉 / 页脚 / 点击区域 / 翻页方式 / 动效 —— 都是设一次就不动的项 */
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setPadding(0, 0, 0, ModouUi.dp(this, 2));

        // 偏好行会被底部 sheet 的 18dp 内边距夹住，这里不再额外左右缩进
        // 页眉/页脚字号：属于「字号」这一类，留在折叠区；调它时视线在屏幕最底，
        // 所以滑杆带 dodge=true —— 按住就面板下沉，露出整块下半屏。
        adv.addView(seekRow("页眉/页脚字号", 9, 20, 1, Math.round(hfSizeSp),
                v -> v + " sp", () -> Math.round(hfSizeSp), v -> hfSizeSp = v, true));
        adv.addView(div());

        adv.addView(segRow("页眉", new String[]{"不显示", "章节名", "书名", "书名+章"},
                headMode, i -> {
                    headMode = i;
                    saveHeadFoot();
                    applyStyleNow();
                }));
        adv.addView(div());

        // 沉浸页眉：菜单收起时顶部仍留一行章节名（小字、半透明，不占正文）
        adv.addView(toggleRow("沉浸时显示页眉", headImmersive, on -> {
            headImmersive = on;
            ModouStore.get(this).putSettingStr("readerHeadImmersive", on ? "1" : "0");
            applyStyleNow();
        }));
        adv.addView(div());

        // 页脚（可多选，可同时开「页码 + 时间 + 电量」）
        adv.addView(multiRow("页脚",
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
                11f, theme.sub);
        footNote.setLineSpacing(ModouUi.dp(this, 2), 1.0f);
        footNote.setPadding(0, ModouUi.dp(this, 4), 0, ModouUi.dp(this, 6));
        adv.addView(footNote);
        adv.addView(div());

        // 点击区域：左手用户在左边点一下变成「下一页」才不会一直往回翻
        adv.addView(segRow("点击区域", new String[]{"经典", "左手", "单手"},
                "left".equals(tapMode) ? 1 : "single".equals(tapMode) ? 2 : 0, i -> {
                    tapMode = i == 1 ? "left" : i == 2 ? "single" : "classic";
                    ModouStore.get(this).putSettingStr("readerTapMode", tapMode);
                    applyTapZones();
                    ModouUi.toast(this, i == 0 ? "左：上一页 · 右：下一页"
                            : i == 1 ? "左手：左翻下一页" : "单手：左侧呼出菜单");
                }));
        adv.addView(div());

        // 翻页方式（切换会重排，按当前位置对接，不回到章首）
        adv.addView(segRow("翻页方式", new String[]{"左右翻页", "上下滚动"},
                scrollMode ? 1 : 0, i -> {
                    scrollMode = i == 1;
                    applyStyleNow();
                }));
        adv.addView(div());

        // 动效三档：0 关闭 / 1 简约 / 2 MD3 标准（与设置页同键，立即生效）
        adv.addView(segRow("动效", new String[]{"关闭", "简约", "标准"},
                animGrade, i -> {
                    animGrade = i;
                    ModouStore.get(this).putSettingStr("uiAnimGrade", String.valueOf(i));
                    ModouUi.toast(this, i == 0 ? "动效已关闭" : i == 1 ? "动效：简约" : "动效：标准");
                    replaySheetEnter();   // 切换后立即重放当前档位的进入动画，区别立刻可见
                }));

        box.addView(foldSection("页面与交互", adv, panelW, sc));

        sc.addView(box);
        showSheet("排版", sc);
        typoShowing = true;
    }

    /**
     * 折叠区标题行 + 可动画的内容块，默认收起。
     *
     * 面板在固定高度的 ScrollView 里：直接改 Visibility 会让整块内容瞬间顶开，
     * 视觉上像跳帧。这里用高度动画（同时按比例淡入）把「展开」这一下铺开，
     * 收起则反向收回；动效设置为「关闭」时保持瞬切。
     */
    private View foldSection(String title, View content, int measureW, final ScrollView sc) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, ModouUi.dp(this, 13), 0, ModouUi.dp(this, 13));
        head.addView(ModouUi.tv(this, title, 14, theme.text),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final View arrow = new ChevronView(this, theme.sub);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ModouUi.dp(this, 18), ModouUi.dp(this, 18));
        alp.rightMargin = ModouUi.dp(this, 3);
        head.addView(arrow, alp);

        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        content.setVisibility(View.GONE);
        content.setAlpha(0f);
        wrap.addView(head);
        wrap.addView(content);

        final boolean[] open = {false};
        head.setOnClickListener(v -> {
            open[0] = !open[0];
            // 0.9.156：快速连点时上一支旋转动画未结束就又开一支，箭头方向会错乱。
            arrow.animate().cancel();
            arrow.animate().rotation(open[0] ? 180f : 0f).setDuration(160).start();
            // 展开完成后把面板滚到底：折叠区在面板最末，滚到底就等于「展开的内容全部进视野」。
            // 不滚的话内容虽然排出来了，但停在可视区下方，用户必须自己再划一下。
            expandAnim(content, open[0], measureW, open[0] ? () -> scrollFoldIntoView(sc, wrap) : null);
        });
        return wrap;
    }

    /**
     * 把刚展开的折叠区顶到可视区顶部。
     *
     * 必须 post 一帧：高度动画刚结束时子 View 的 measuredHeight 还是动画中间值，
     * 立刻算 maxScroll 会得到旧的（更小的）结果，滚不到位。
     * 目标位置再按 maxScroll 夹一次：折叠区在面板最末，下方没有那么多内容可滚，
     * 不夹的话 smoothScrollTo 会被 ScrollView 自行修正成「滚到底」，反而把标题顶出去。
     */
    private void scrollFoldIntoView(final ScrollView sc, final View anchor) {
        if (sc == null || anchor == null) return;
        sc.post(() -> {
            View child = sc.getChildCount() > 0 ? sc.getChildAt(0) : null;
            if (child == null) return;
            int max = Math.max(0, child.getHeight() - sc.getHeight());
            int y = Math.max(0, anchor.getTop() - ModouUi.dp(this, 2));
            sc.smoothScrollTo(0, Math.min(max, y));
        });
    }

    /**
     * 折叠展开/收起的动画：高度 0 ↔ 实测高度，结束后恢复 WRAP_CONTENT 免得裁掉后续内容。
     *
     * 0.9.156：修「快速连点折叠标题」的同类竞态 —— 与 0.9.155 工具坞 alpha 残留同源：
     * ① 旧动画从不取消，两支 ValueAnimator 同时写同一个 lp.height / alpha，高度抖动；
     * ② ValueAnimator.cancel() 仍会回调 onAnimationEnd，旧动画的终态（WRAP_CONTENT 或
     *    GONE）会在新动画跑到一半时覆盖上去，内容错乱/被裁；
     * ③ 起点硬写 0 / target，连点时从中间态瞬间跳回端点，看着像闪一下。
     * 用 View.tag 记住「本 View 当前那支动画」：开新的先摘 listener 再 cancel；终点从当前
     * 高度出发；onAnimationEnd 加实例守卫，迟到的旧回调不再写状态。
     */
    private void expandAnim(final View c, final boolean expand, final int measureW,
                            final Runnable after) {
        // 先取消本 View 上一支折叠动画：必须先摘 listener，否则 cancel() 触发的
        // onAnimationEnd 会把终态写到「即将开始的新动画」头上。
        android.animation.ValueAnimator prev = foldAnims.remove(c);
        if (prev != null) {
            prev.removeAllUpdateListeners();
            prev.removeAllListeners();
            prev.cancel();
        }

        c.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, measureW),
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int target = c.getMeasuredHeight();
        final LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) c.getLayoutParams();

        if (effectiveAnimGrade() == 0 || target <= 0) {
            lp.height = expand ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            c.setAlpha(expand ? 1f : 0f);
            c.setVisibility(expand ? View.VISIBLE : View.GONE);
            c.requestLayout();
            if (expand && after != null) c.post(after);
            return;
        }

        c.setVisibility(View.VISIBLE);
        // 从「当前高度」平滑到目标，而不是硬从 0 / target 起：连点时不会跳回端点。
        int from = lp.height >= 0 ? lp.height : (expand ? 0 : target);
        if (from > target) from = target;
        final android.animation.ValueAnimator va =
                android.animation.ValueAnimator.ofInt(from, expand ? target : 0);
        va.setDuration(200);
        va.setInterpolator(MD3_DECEL);
        va.addUpdateListener(a -> {
            int h = (Integer) a.getAnimatedValue();
            lp.height = h;
            c.requestLayout();
            float f = target <= 0 ? 1f : (float) h / target;
            c.setAlpha(expand ? Math.min(1f, f * 1.5f) : f);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (foldAnims.get(c) != va) return;   // 迟到的旧动画：不写状态
                foldAnims.remove(c);
                if (expand) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    c.setAlpha(1f);
                } else {
                    lp.height = 0;
                    c.setAlpha(0f);
                    c.setVisibility(View.GONE);
                }
                c.requestLayout();
                if (expand && after != null) c.post(after);
            }
        });
        foldAnims.put(c, va);
        va.start();
    }

    /**
     * 细线折线箭头（chevron）：两笔 1.6dp 圆头线段在底部交汇。
     *
     * 原来是「▼」字符 —— 实心三角是十年前的展开指示器，字号调小之后还带一堆内边距，
     * 跟这套细线图标完全不是一个语言。自绘两笔线条，粗细/端点都可控，
     * 旋转交给 View 的 rotationX/Y 之外的平面旋转动画（180° = 收起态朝下）。
     */
    private static final class ChevronView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChevronView(Context c, int color) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(ModouUi.dp(c, 1.6f));
            p.setColor(color);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f, cy = h / 2f;
            float half = w * 0.26f;
            float dy = h * 0.15f;
            cv.drawLine(cx - half, cy - dy, cx, cy + dy, p);
            cv.drawLine(cx, cy + dy, cx + half, cy - dy, p);
        }
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

    /** 选项胶囊：选中柔和蓝实底 + 白字，未选半透明浅灰无描边（描边会让它看着像输入框） */
    private GradientDrawable pillBg(boolean on) {
        DesignSpec sp = rd();
        GradientDrawable g = new GradientDrawable();
        if (sp.rdDockStyle == 0) {
            // 墨读：强调色实底 + 999 胶囊（原值）
            g.setColor(on ? theme.acc : chipBg());
            g.setCornerRadius(ModouUi.dp(this, 999));
            return g;
        }
        // iOS：选中 = 系统蓝 1F 淡染胶囊；M3：选中 = tonal 8dp 块，未选中完全透明
        g.setColor(on ? ModouUi.alpha(theme.acc, 0x1F)
                : (sp.rdDockStyle == 2 ? 0x00000000 : chipBg()));
        g.setCornerRadius(ModouUi.dp(this, sp.rdDockStyle == 2 ? 8 : 999));
        return g;
    }

    private GradientDrawable chipBgDrawable(boolean on) {
        return pillBg(on);
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
        sizeLabel = v;
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

    /** 「A− / A+」按钮：与选项同一种胶囊（999 圆角 + 半透明浅灰底） */
    private TextView stepBtn(String label) {
        TextView t = ModouUi.tv(this, label, 15, theme.text);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(chipBg());
        g.setCornerRadius(ModouUi.dp(this, 999));
        t.setBackground(g);
        return t;
    }

    /** 淡底 chip 色：浅色主题是淡墨，夜间是淡白 */
    private int chipBg() {
        return theme.dark ? 0x1FFFFFFF : 0x0F000000;
    }

    /** 分段控件行：公共三语言形状、状态层与选中语义，配色仍跟随阅读纸张。 */
    private LinearLayout segRow(String title, String[] labels, int cur, Pick cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
        row.addView(ModouUi.tv(this, title, 13, theme.sub));
        LinearLayout seg = ModouUi.readerSegment(this, labels, cur,
                idx -> { if (cb != null) cb.on(idx); }, theme);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ModouUi.dp(this, 8);
        row.addView(seg, lp);
        return row;
    }

    /** 选中分段的前景色：墨读强调色做底时取浅色文字；iOS / M3 用淡染底 + 强调色文字 */
    private int segOnText() {
        if (rd().rdDockStyle != 0) return theme.acc;
        return theme.dark ? 0xFF1B1D21 : 0xFFFFFFFF;
    }

    /** 未选中分段的前景色：0.9.173 起 iOS / M3 用次级灰（分段控件的常态） */
    private int segOffText() {
        return rd().rdDockStyle == 0 ? theme.text : theme.sub;
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
                                 Fmt fmt, java.util.function.IntSupplier cur, Pick cb) {
        // 所有滑杆默认「按住就让开」：行距、段距、边距看的都是整页重排，
        // 面板只要还留在屏内，上半屏就只剩几行、还被遮罩压暗 —— 那等于没预览。
        return seekRow(title, min, max, step, val, fmt, cur, cb, true, 0, true);
    }

    /**
     * @param dodge 按住时是否把整个面板沉到屏幕外：只有「下边距 / 页脚字号」这类
     *              调完要看屏幕最底部的滑杆才需要 —— 视线在底部时，把面板变半透明
     *              只是把底层正文糊成一片，等于没让开。
     */
    private LinearLayout seekRow(String title, int min, int max, int step, int val,
                                 Fmt fmt, java.util.function.IntSupplier cur, Pick cb,
                                 boolean dodge) {
        return seekRow(title, min, max, step, val, fmt, cur, cb, dodge, 0, true);
    }

    /**
     * @param guide 拖这根滑杆时画哪条版心边界线：0 不画，1 顶（上边距），2 底（下边距）。
     *              下边距改的是「本页还能塞几行」，行是整行跳的 —— 没有这条线，
     *              中间几十档看上去一个样。
     */
    private LinearLayout seekRow(String title, int min, int max, int step, int val,
                                 Fmt fmt, java.util.function.IntSupplier cur, Pick cb,
                                 boolean dodge, int guide) {
        return seekRow(title, min, max, step, val, fmt, cur, cb, dodge, guide, true);
    }

    /**
     * @param guide 拖这根滑杆时画哪条版心边界线：0 不画，1 顶（上边距），2 底（下边距）。
     *              下边距改的是「本页还能塞几行」，行是整行跳的 —— 没有这条线，
     *              中间几十档看上去一个样。
     */
    private LinearLayout seekRow(String title, int min, int max, int step, int val,
                                 Fmt fmt, java.util.function.IntSupplier cur, Pick cb,
                                 boolean dodge, int guide, boolean live) {
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
        // 0.9.175：排版面板的三根滑杆换成自绘三语言滑杆（与工具坞进度条同一个类）。
        // 系统 SeekBar 的轨道粗细 / thumb 只能靠 tint 调，做不出 M3 的断轨与停靠点；
        // 而且它的 progressDrawable 与 thumb 是两套坐标，小比例时会露出「圆柱截面」。
        final ModouPillSeekBar sb = new ModouPillSeekBar(this);
        sb.setMax(span);
        sb.setProgress(Math.max(0, Math.min(span, val - min)));
        sb.setPadding(ModouUi.dp(this, 11), 0, ModouUi.dp(this, 11), 0);
        sb.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(this, 36)));
        // 配色仍取纸张色板（与工具坞进度条完全同一组），只换形状由规格决定
        sb.setPalette(
                theme.dark ? 0x2BFFFFFF : 0x1F000000,
                theme.acc,
                theme.dark ? 0x26FFFFFF : 0x40FFFFFF,
                theme.acc,
                theme.dark ? 0xFFF0EDE4 : 0xFFFFFFFF,
                theme.dark ? 0xFFF7F5EF : 0xFFFFFFFF,
                theme.dark ? 0x33FFFFFF : 0x14000000);
        // 只记录手指落点，不消费事件（返回 false）—— SeekBar 自己还要拖动。
        // 浮窗要靠这个 Y 躲开手指：固定位置放哪都可能正好压在手下。
        final float[] touchY = {-1f};
        sb.setOnTouchListener((vv, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touchY[0] = e.getRawY();
            return false;
        });
        // 上一次的档位值：只有真的跨档才震，手指在同一档里抖动不该连震
        final int[] lastV = {val};
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int v2 = min + Math.round((float) progress / Math.max(1, step)) * Math.max(1, step);
                v2 = Math.max(min, Math.min(max, v2));
                v.setText(fmt.of(v2));
                // 浮窗跟着手指走：面板已经沉走了，这里是屏幕上唯一的读数
                if (seekHudVal != null && seekHud != null && seekHud.getVisibility() == View.VISIBLE) {
                    seekHudVal.setText(fmt.of(v2));
                }
                if (fromUser && cb != null) {
                    cb.on(v2);
                    // 每跨一档震一下：眼睛盯着正文，手上得有「咔」的确认，
                    // 否则拖到底也不知道自己动没动（尤其行距这种整页重排的滑杆）
                    if (v2 != lastV[0]) {
                        lastV[0] = v2;
                        sb.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    // 拖动中即时预览；样式应用做 60ms 节流，避免每帧重排掉帧。
                    // live=false 的滑杆（文字亮度）只改正文 Paint 颜色、不重排，跳过这里
                    if (live) applyStyleSoon();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // 按下就把面板整体沉下去，只留标题条；数值改由浮窗显示
                if (dodge) dodgeSheet(true);
                if (guide != 0) pageView.setGuide(guide);
                int v0 = min + Math.round((float) sb.getProgress() / Math.max(1, step)) * Math.max(1, step);
                v0 = Math.max(min, Math.min(max, v0));
                showSeekHud(title, fmt.of(v0), touchY[0]);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (dodge) dodgeSheet(false);
                if (guide != 0) pageView.setGuide(0);
                hideSeekHud(false);
                // 松手补一次，保证落库的是最终值而不是防抖窗口里的中间值
                int v2 = min + Math.round((float) sb.getProgress() / Math.max(1, step)) * Math.max(1, step);
                v2 = Math.max(min, Math.min(max, v2));
                if (cb != null) cb.on(v2);
                if (live) applyStyleNow();
            }
        });
        row.addView(sb);
        seekBinds.add(new SeekBind(sb, v, min, max, step, fmt, cur));
        return row;
    }

    /** 拖动滑杆时的防抖重排：120ms 内的连续改动只应用最后一次 */
    /**
     * 拖动中的重排节流（以前写成了「防抖」，是个死循环）：
     *
     * 旧实现每次进度变化都 `removeCallbacks + postDelayed(90)`。手指只要在动，
     * 相邻两次变化就短于 90ms，计时器永远被重置 —— 拖动全程一次都没重排，
     * 松手才 applyStyleNow，看上去就是「怎么拖都不变、一松手才跳」。
     *
     * 现在反过来：排队期间后续变化直接合并，到点必跑一次，跑完再接受下一次排队，
     * 即「最多 60ms 就刷新一帧」。松手再由 onStopTrackingTouch 落最终值。
     */
    private void applyStyleSoon() {
        if (pendingStyle != null) return;
        pendingStyle = () -> {
            pendingStyle = null;
            applyStyle();
        };
        styleHandler.postDelayed(pendingStyle, 60);
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
        s.putSetting("readerJustifyHang", justifyHang ? "1" : "0");
        s.putSetting("readerTopGap", String.valueOf(topGapDp));
        s.putSetting("readerBotGap", String.valueOf(botGapDp));
        s.putSetting("readerHfSize", String.valueOf(hfSizeSp));
        s.putSetting("readerBrightnessLight", String.valueOf(brightnessLight));
        s.putSetting("readerBrightnessDark", String.valueOf(brightnessDark));
        s.putSetting("readerBrightness", String.valueOf(brightness)); // 兼容旧键
    }

    /**
     * 开关行：右侧是自绘拨动开关，不再写「开 / 关」两个字。
     *
     * 文字开关的问题不只是丑：它是「当前值的标签」，点上去没有任何触感反馈，
     * 用户会怀疑到底点没点上。药丸轨道 + 滑块位移是唯一不需要读文字的提示。
     */
    private LinearLayout toggleRow(String title, boolean on, PickBool cb) {
        return ModouUi.readerSwitchRow(this, title, on,
                value -> { if (cb != null) cb.on(value); }, theme);
    }

    private View div() {
        View v = new View(this);
        v.setBackgroundColor(theme.dark ? 0x14FFFFFF : 0x0F000000);
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

    /** 排版面板里的「阅读纸张」入口行：当前纸张名 + ›，点开沉浸式纸张画廊 */
    private LinearLayout paperEntry() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ModouUi.dp(this, 10), 0, ModouUi.dp(this, 10));
        TextView title = ModouUi.tv(this, "阅读纸张", 13f, theme.sub);
        row.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = ModouUi.tv(this,
                DesignSpec.paperLabel(darkMode ? "dark" : lightTheme), 13.5f, theme.text);
        val.setTypeface(val.getTypeface(), Typeface.BOLD);
        val.setIncludeFontPadding(false);
        row.addView(val);
        TextView arrow = ModouUi.tv(this, "›", 18f, theme.sub);
        arrow.setIncludeFontPadding(false);
        arrow.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
        row.addView(arrow);
        row.setOnClickListener(v -> PaperGalleryActivity.launchForResult(this, REQ_PAPER));
        return row;
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

    private LinearLayout searchNav;          // 跳转后的浮动导航条
    private TextView searchNavCount;         // 导航条上的「n / N 关键词」
    private final List<SearchHit> lastHits = new ArrayList<>();  // 上次结果（供上一处/下一处）
    private int lastHitPos = -1;
    /** 当前抽屉的标题：导航条上的「搜索」要按它判断「已经开着就收起」 */
    private String sheetTitle = "";
    private String lastKw = "";

    private void showSearch(String preset) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // 输入行：圆角淡底输入框 + 强调色「搜索」胶囊
        LinearLayout inRow = new LinearLayout(this);
        inRow.setOrientation(LinearLayout.HORIZONTAL);
        inRow.setGravity(Gravity.CENTER_VERTICAL);

        EditText input = ModouUi.searchField(this, "搜索全书正文");
        input.setHintTextColor(theme.sub);
        input.setTextColor(theme.text);
        input.setText(preset == null ? "" : preset);
        input.setSingleLine(true);
        inRow.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView searchBtn = ModouUi.button(this, "搜索",
                DesignSpec.ButtonKind.FILLED, ModouUi.CAPSULE, null);
        searchBtn.setContentDescription("搜索全书正文");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        blp.leftMargin = ModouUi.dp(this, 8);
        inRow.addView(searchBtn, blp);
        box.addView(inRow);

        TextView status = ModouUi.tv(this, "输入关键词后点搜索", 12, theme.sub);
        status.setPadding(ModouUi.dp(this, 6), ModouUi.dp(this, 10), 0, ModouUi.dp(this, 4));
        box.addView(status);

        final List<SearchHit> hits = new ArrayList<>();
        // 从命中位置切回搜索列表：同一个关键词的上次结果原样摆回来（列表、选中位置都在），
        // 不重跑全文扫描 —— 用户只是回来换一处看，不是要重新搜。
        final boolean restored = preset != null && !lastHits.isEmpty()
                && preset.trim().equals(lastKw);
        if (restored) hits.addAll(lastHits);
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setVerticalScrollBarEnabled(false);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setAdapter(searchAdapter(hits, restored ? lastHitPos : -1, restored ? lastKw : preset));
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.46f)));
        if (restored) {
            status.setText("找到 " + hits.size() + " 处（上次的结果）");
            final int to = Math.max(0, lastHitPos);
            lv.post(() -> lv.setSelection(to)); // 回到刚才那一处，不用自己再往下翻
        }

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
            // 记住本次结果：跳转后仍能「上一处 / 下一处 / 回到搜索」
            lastHits.clear();
            lastHits.addAll(hits);
            lastKw = input.getText().toString().trim();
            closeSheet();
            gotoHit(pos);
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
                String orig = text.toString();
                String s = orig.toLowerCase();
                int from = 0;
                while (found.size() < 300) {
                    int p = s.indexOf(lowerKw, from);
                    if (p < 0) break;
                    int snipStart = Math.max(0, p - 12);
                    int snipEnd = Math.min(orig.length(), p + kw.length() + 12);
                    // 摘要取原文（不是小写副本）：英文关键词在列表里也得保持原样大小写
                    found.add(new SearchHit(ci, p, chapterNameOf(ci),
                            orig.substring(snipStart, snipEnd).replace('\n', ' ')));
                    from = p + kw.length();
                }
                if (found.size() >= 300) break;
            }
            runOnUiThread(() -> {
                out.addAll(found);
                prefetchSearchHits(found);
                if (found.isEmpty()) {
                    status.setText("没有匹配（仅搜索已缓存的章节）");
                    lv.setAdapter(searchAdapter(found, -1, kw));
                    return;
                }
                status.setText("找到 " + found.size() + " 处");
                lv.setAdapter(searchAdapter(found, -1, kw)); // 新搜一次，没有「当前选中」
            });
        }).start();
    }

    private static final class SearchHit {
        final int index;
        final int offset;
        final String chapter;   // 章节名（结果第一行）
        final String snippet;   // 内容摘要（结果第二行）

        SearchHit(int index, int offset, String chapter, String snippet) {
            this.index = index;
            this.offset = offset;
            this.chapter = chapter;
            this.snippet = snippet;
        }
    }

    /**
     * 搜索结果适配器：每处两行——第一行章节名（次要色），第二行内容摘要（正文色）。
     *
     * 摘要里的关键词描上与正文同一个底纹：列表里一眼能看出「命中的是哪两个字」，
     * 不用点进去再找。
     */
    private BaseAdapter searchAdapter(List<SearchHit> hits, int current, String kw) {
        return new BaseAdapter() {
            @Override public int getCount() { return hits.size(); }
            @Override public Object getItem(int i) { return hits.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View reuse, ViewGroup parent) {
                LinearLayout row = new LinearLayout(NativeReaderActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(10), dp(11), dp(10), dp(11));
                SearchHit h = hits.get(i);
                boolean cur = i == current;
                if (cur) {
                    // 当前这一处（跳转过去的那条）：淡赤陶底 + 章名赤陶色。
                    // 以前列表只显示「7 / 300」，面板一开满眼都是一样的灰行，根本认不出刚点的是哪条
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(ModouUi.mix(theme.card, theme.acc, 0.14f));
                    bg.setCornerRadius(dp(8));
                    row.setBackground(bg);
                }
                TextView c = ModouUi.tv(NativeReaderActivity.this, h.chapter, 12.5f,
                        cur ? theme.acc : theme.sub);
                c.setSingleLine(true);
                c.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(c);
                TextView s = ModouUi.tv(NativeReaderActivity.this, h.snippet, 14.5f, theme.text);
                if (kw != null && !kw.isEmpty()) s.setText(marked(h.snippet, kw));
                s.setSingleLine(true);
                s.setEllipsize(TextUtils.TruncateAt.END);
                s.setPadding(0, dp(3), 0, 0);
                row.addView(s);
                return row;
            }
        };
    }

    /** 摘要里的关键词描底纹（与正文搜索高亮同色），大小写不敏感、可多处 */
    private CharSequence marked(String text, String kw) {
        int m = kw.length();
        if (text == null || text.isEmpty() || m == 0) return text;
        String src = text;
        SpannableString ss = new SpannableString(src);
        int i = 0;
        boolean any = false;
        while (i + m <= src.length()) {
            if (!src.regionMatches(true, i, kw, 0, m)) { i++; continue; }
            ss.setSpan(new BackgroundColorSpan(theme.sel), i, i + m,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            any = true;
            i += m;
        }
        return any ? ss : text;
    }

    /* -------- 搜索导航条：跳转后仍能上一处 / 下一处 / 回搜索 -------- */

    /** 跳转到第 pos 处命中（循环），并保证导航条可见 */
    private void gotoHit(int pos) {
        if (lastHits.isEmpty()) return;
        int n = lastHits.size();
        lastHitPos = ((pos % n) + n) % n;
        SearchHit h = lastHits.get(lastHitPos);
        // 正文里每处命中都描底，当前这一处用选区色（否则跳过去只看得到「第 5/35 处」，
        // 满屏字里根本认不出命中的是哪几个）。跨章时偏移属于新章，先不指定当前处，
        // 等 applyChapter 把新正文排上屏再由 syncFindFor 认领
        pageView.setFind(lastKw, h.index == index ? h.offset : -1);
        if (h.index == index) {
            // 同章跳转：直接用已经排好的版心，按字符偏移算出页码翻过去。
            // 绝不调 setBlocks/setChapter（那会整章 relayout，就是闪烁的根因），
            // 只改视图位置 + 高亮，丝滑无重排。
            pageView.gotoPage(pageView.pageOfOffset(h.offset));
            saveProgress(false);
        } else {
            requestChapter(h.index, h.offset, true);
        }
        showSearchNav();
        updateSearchNav();
    }

    /**
     * 把高亮对齐到当前章节：跨章跳转时命中偏移属于新章，必须等新正文上屏再设。
     * 当前章不是命中所在章（用户手动翻走了）时只留「其余命中」的淡底纹，不指认当前处。
     */
    private void syncFindFor(int idx) {
        if (lastHits.isEmpty() || lastHitPos < 0 || lastHitPos >= lastHits.size()) {
            pageView.clearFind();
            return;
        }
        SearchHit h = lastHits.get(lastHitPos);
        pageView.setFind(lastKw, h.index == idx ? h.offset : -1);
    }

    private void showSearchNav() {
        if (searchNav == null) {
            searchNav = new LinearLayout(this);
            searchNav.setOrientation(LinearLayout.HORIZONTAL);
            searchNav.setGravity(Gravity.CENTER_VERTICAL);
            searchNav.setBackground(ModouUi.squircle(this, theme.card, 24,
                    ModouUi.mix(theme.card, theme.text, 0.16f), 1));
            searchNav.setElevation(dp(6));
            searchNav.setPadding(dp(8), dp(2), dp(8), dp(2));

            searchNav.addView(navIcon(R.drawable.ic_rd_prev, theme.text, "上一个搜索结果",
                    () -> gotoHit(lastHitPos - 1)));
            searchNavCount = ModouUi.tv(this, "", 13.5f, theme.text);
            searchNavCount.setGravity(Gravity.CENTER);
            searchNav.addView(searchNavCount, new LinearLayout.LayoutParams(0, dp(44), 1f));
            searchNav.addView(navText("搜索", theme.acc, this::toggleSearch));
            searchNav.addView(navIcon(R.drawable.ic_rd_next, theme.text, "下一个搜索结果",
                    () -> gotoHit(lastHitPos + 1)));
            searchNav.addView(navIcon(R.drawable.ic_rd_close, theme.sub, "关闭搜索导航",
                    this::hideSearchNav));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 贴在底部：这是拇指够得到的区域。工具坞弹出来时再抬到它上方（见 syncSearchNavMargin）
            lp.gravity = Gravity.BOTTOM;
            lp.setMargins(dp(10), 0, dp(10), dp(12));
            root.addView(searchNav, lp);
        }
        searchNav.setVisibility(View.VISIBLE);
        // 工具坞高度要等一次布局才拿得到，跟朗读面板同一套做法
        searchNav.post(this::syncSearchNavMargin);
    }

    /**
     * 导航条避开工具坞：贴近底部（拇指可及），工具坞展开时抬到它正上方。
     * 不重叠是硬要求 —— 压在「上一章/下一章」上会连点错。
     */
    private void syncSearchNavMargin() {
        if (searchNav == null) return;
        int want = dp(12);
        if (dockShowing && dock != null && dock.getMeasuredHeight() > 0) {
            want = dock.getMeasuredHeight() + dp(10);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) searchNav.getLayoutParams();
        if (lp != null && lp.bottomMargin != want) {
            lp.bottomMargin = want;
            searchNav.setLayoutParams(lp);
        }
    }

    /**
     * 导航条上的图标按钮：‹ › ✕ 三枚一律用 24 视口的矢量图 + 同一线宽。
     *
     * 之前用「‹」「›」「✕」三个字符画：箭头的字形只有字号的四成高、且坐在基线上偏低，
     * ✕ 却是满字高的居中字符 —— 两个放一起永远一大一小、一高一低。矢量图没有基线，
     * 20dp 盒子放进 44dp 触摸区正好光学居中，三枚尺寸完全一致。
     */
    private View navIcon(int res, int color, String description, Runnable r) {
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(tinted(res, color));
        iv.setContentDescription(description);
        iv.setTooltipText(description);
        iv.setFocusable(true);
        iv.setOnClickListener(v -> r.run());
        ModouMotion.press(iv);
        return iv;
    }

    private TextView navText(String label, int color, Runnable r) {
        TextView t = ModouUi.tv(this, label, 14, color);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    /**
     * 导航条上的「搜索」：开 / 收。
     *
     * 以前只会 showSearch：面板已经开着时再点等于又铺一层遮罩，看起来「点一次能出来，
     * 再点收不回去」。现在按当前抽屉标题判断，开着就关。
     */
    private void toggleSearch() {
        if (overlay != null && "搜索".equals(sheetTitle)) {
            closeSheet();
            return;
        }
        showSearch(lastKw);
    }

    private void updateSearchNav() {
        if (searchNavCount == null) return;
        String kw = lastKw.isEmpty() ? "" : "  " + lastKw;
        searchNavCount.setText((lastHitPos + 1) + " / " + lastHits.size() + kw);
    }

    private void hideSearchNav() {
        if (searchNav != null) searchNav.setVisibility(View.GONE);
        // 结束这次搜索：底纹收起（结果列表留着，再点「搜索」还能回到上次那一份）
        pageView.clearFind();
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
        boolean cached; // 正文已在本地库里（目录页打「已缓存」）
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
        private TocFastScroll tocScroll;
        private TextView tocBubble;
        /** 已缓存正文的章号（在线书才有意义；本地书整本都在库里，不标） */
        private java.util.HashSet<Integer> cachedIdx = new java.util.HashSet<>();
        /** 本地导入书 / 没有书号：整本都在库里，全部当作已缓存，不画云 */
        private boolean allCached;

        TocSheet() {
            this(0);
        }

        TocSheet(int start) {
            bms = bookmarksOf();
            mode = start == 1 ? 1 : 0;
            // 本地导入书的正文就是书本身，每章都「已缓存」，标出来全是噪音
            allCached = bookId == null || bookId.startsWith(LocalImport.LOCAL);
            if (!allCached) {
                cachedIdx = ModouStore.get(NativeReaderActivity.this).cachedChapterIndexes(bookId);
            }
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
                col.setMinimumHeight(dp(48));
                col.setFocusable(true);
                col.setSelected(on);
                col.setContentDescription(names[i] + (on ? "，已选中" : "，未选中"));
                col.setStateDescription(on ? "已选中" : "未选中");
                col.setOnClickListener(v -> select(m));
                tabs.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            box.addView(tabs);

            // ---- 回到当前章：几千章的目录手动划回去非常难受 ----
            // 一条细胶囊，同时显示进度百分比，点了取消筛选并滚回当前章。
            final TextView back2cur = ModouUi.tv(NativeReaderActivity.this,
                    "↩ 回到当前章 · 第 " + (index + 1) + " 章 · "
                            + Math.round((index + 1) * 100f / Math.max(1, titles.length)) + "%",
                    12.5f, theme.acc);
            back2cur.setGravity(Gravity.CENTER);
            back2cur.setPadding(dp(10), dp(6), dp(10), dp(6));
            GradientDrawable b2c = new GradientDrawable();
            b2c.setColor(ModouUi.alpha(theme.acc, 0x14));
            b2c.setCornerRadius(dp(999));
            back2cur.setBackground(b2c);
            LinearLayout.LayoutParams b2clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            b2clp.topMargin = dp(8);
            box.addView(back2cur, b2clp);

            // ---- 筛选框 ----
            filter = ModouUi.searchField(NativeReaderActivity.this,
                    "搜章名 / 输入章号直接跳，如 128");
            filter.setSingleLine(true);
            filter.setTextColor(theme.text);
            filter.setHintTextColor(ModouUi.alpha(theme.sub, 0xB0));
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
            lv.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(android.widget.AbsListView v, int state) {
                }

                @Override
                public void onScroll(android.widget.AbsListView v, int first, int visible, int total) {
                    if (tocScroll != null) tocScroll.invalidate();
                }
            });

            // 目录动辄几百上千章：右侧加一条自绘快速滚动条，按住上下拖能一下跳过几百章，
            // 拖动时列表中央浮出「第 N 章 · 章名」。不用 ListView 自带的 fastScroll ——
            // 那是系统灰疙瘩，和纸墨界面不是一套语言，也没法显示章节名。
            FrameLayout listWrap = new FrameLayout(NativeReaderActivity.this);
            listWrap.addView(lv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tocScroll = new TocFastScroll();
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    dp(26), ViewGroup.LayoutParams.MATCH_PARENT);
            slp.gravity = Gravity.END;
            listWrap.addView(tocScroll, slp);

            tocBubble = ModouUi.tv(NativeReaderActivity.this, "", 13, 0xFFFFFFFF);
            tocBubble.setSingleLine(true);
            tocBubble.setEllipsize(TextUtils.TruncateAt.END);
            tocBubble.setMaxWidth(dp(280));
            tocBubble.setGravity(Gravity.CENTER);
            tocBubble.setPadding(dp(16), dp(9), dp(16), dp(9));
            GradientDrawable bubbleBg = new GradientDrawable();
            bubbleBg.setColor(0xF0201D1A);
            bubbleBg.setCornerRadius(dp(12));
            tocBubble.setBackground(bubbleBg);
            tocBubble.setVisibility(View.GONE);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.CENTER;
            listWrap.addView(tocBubble, blp);

            box.addView(listWrap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h));
            // 监听挂在这里：filter/lv 都已初始化（挂太早编译器不让引用 final 字段）
            back2cur.setOnClickListener(v -> {
                filter.setText("");
                int at = 0;
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).cur) {
                        at = i;
                        break;
                    }
                }
                lv.setSelection(Math.max(0, at - 3));
                toast("已回到第 " + (index + 1) + " 章");
            });

            empty = ModouUi.empty(NativeReaderActivity.this, "没有匹配的条目");
            empty.setVisibility(View.GONE);
            box.addView(empty);

            // ---- 书签 tab 的「清空」：原来在独立的书签列表里，现在跟着 tab 走 ----
            clearBm = ModouUi.tv(NativeReaderActivity.this, "清空本书书签", 13, theme.acc);
            clearBm.setGravity(Gravity.CENTER);
            clearBm.setPadding(0, dp(10), 0, dp(2));
            clearBm.setMinimumHeight(dp(48));
            clearBm.setContentDescription("清空本书全部书签");
            clearBm.setOnClickListener(v -> {
                if (bms.isEmpty()) {
                    toast("本书还没有书签");
                    return;
                }
                ModouUi.confirm(NativeReaderActivity.this, "清空本书书签",
                        "将永久删除本书的 " + bms.size() + " 条书签，此操作无法撤销。", () -> {
                            for (JSONObject o : bms) {
                                ModouStore.get(NativeReaderActivity.this).del(
                                        "bookmarks", o.optString("id", ""));
                            }
                            bms.clear();
                            closeSheet();
                            toast("已清空本书书签");
                        });
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
                boolean selected = i == m;
                tabTv[i].setTextColor(selected ? theme.text : theme.sub);
                tabTv[i].setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
                tabBar[i].setAlpha(selected ? 1f : 0f);
                View tab = (View) tabTv[i].getParent();
                tab.setSelected(selected);
                tab.setContentDescription(tabTv[i].getText() + (selected ? "，已选中" : "，未选中"));
                tab.setStateDescription(selected ? "已选中" : "未选中");
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
                    if (!kw.isEmpty() && !matchChapterKw(kw, s, i)) continue;
                    TocRow r = new TocRow();
                    r.label = s;
                    r.chapter = i;
                    r.cur = i == index;
                    r.cached = allCached || cachedIdx.contains(i);
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
            if (tocScroll != null) tocScroll.invalidate();
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

        /** 章号或章名匹配：纯数字既当章号（128 → 第 128 章），也当标题里的数字 */
        private boolean matchChapterKw(String kw, String title, int i) {
            String low = title.toLowerCase(java.util.Locale.ROOT);
            if (low.contains(kw)) return true;
            if (!kw.matches("\\d{1,6}")) return false;
            int n;
            try {
                n = Integer.parseInt(kw);
            } catch (Exception e) {
                return false;
            }
            if (n == i + 1) return true;                       // 直接命中第 n 章
            return low.contains("第" + kw + "章") || low.contains("第 " + kw + " 章");
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
                    // 目录 tab（唯一会有几千条的那个）走真正的复用：以前这里无视 reuse，
                    // 每行都 new 一个 LinearLayout + TextView，快速滚 801 章时每帧都在建视图。
                    if (mode == 0) {
                        LinearLayout row;
                        TextView a;
                        ImageView cloud;
                        // 行结构固定为「标题(weight=1) + 云（= 本章还没缓存）」，复用按 child
                        // 下标取，图标常驻只切 visibility —— 目录几千章不能每次重建视图。
                        // 已缓存的不画任何东西（满屏「已缓存」标签比没有更吵）。
                        if (reuse instanceof LinearLayout
                                && ((LinearLayout) reuse).getOrientation() == LinearLayout.HORIZONTAL
                                && ((LinearLayout) reuse).getChildCount() == 2
                                && ((LinearLayout) reuse).getChildAt(1) instanceof ImageView) {
                            row = (LinearLayout) reuse;
                            a = (TextView) row.getChildAt(0);
                            cloud = (ImageView) row.getChildAt(1);
                        } else {
                            row = new LinearLayout(NativeReaderActivity.this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.CENTER_VERTICAL);
                            row.setPadding(dp(8), dp(11), dp(8), dp(11));
                            a = ModouUi.tv(NativeReaderActivity.this, "", 14, theme.text);
                            a.setSingleLine(true);
                            a.setEllipsize(TextUtils.TruncateAt.END);
                            row.addView(a, new LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                            cloud = new ImageView(NativeReaderActivity.this);
                            cloud.setImageResource(R.drawable.ic_cloud);
                            cloud.setColorFilter(ModouUi.alpha(theme.sub, 0xB0));
                            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(17), dp(17));
                            clp.leftMargin = dp(8);
                            row.addView(cloud, clp);
                        }
                        a.setText(r.label);
                        a.setTextColor(r.cur ? theme.acc : theme.text);
                        a.setTypeface(Typeface.DEFAULT, r.cur ? Typeface.BOLD : Typeface.NORMAL);
                        cloud.setVisibility(r.cached ? View.GONE : View.VISIBLE);
                        return row;
                    }
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
                    ImageView del = ModouUi.topBtn(NativeReaderActivity.this,
                            ModouGlyph.TRASH, 48, theme.sub, v -> {
                                ModouStore.get(NativeReaderActivity.this).del("bookmarks", r.id);
                                bms.clear();
                                bms.addAll(bookmarksOf());
                                if (tabTv.length > 1) tabTv[1].setText("书签 · " + bms.size());
                                fill();
                                toast("已删除书签");
                            });
                    del.setContentDescription("删除书签：" + r.label);
                    del.setTooltipText(del.getContentDescription());
                    wrap.addView(del, new LinearLayout.LayoutParams(dp(48), dp(48)));
                    return wrap;
                }
            };
        }

        /**
         * 目录右侧的快速滚动条（自绘）。
         *
         * <p>按住条上任意位置就开始拖（不用精确命中滑块，26dp 宽的条带都是热区），
         * 手指纵向位置按比例换算成条目下标并 {@code setSelectionFromTop}，
         * 同时在列表中央浮出「第 N 章 · 章名」。松手后 0.45s 淡出。
         *
         * <p>条目太少（&lt;20）时不出现也不吃触摸：书签 tab 只有几条，加条反而碍事。
         */
        private class TocFastScroll extends View {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF track = new RectF();
            private final RectF thumb = new RectF();
            private boolean dragging;

            TocFastScroll() {
                super(NativeReaderActivity.this);
            }

            private int count() {
                return rows == null ? 0 : rows.size();
            }

            @Override
            protected void onDraw(Canvas c) {
                int n = count();
                if (n < 20) return;
                float cx = getWidth() - dp(9);
                float pad = dp(4);
                track.set(cx - dp(2), pad, cx + dp(2), getHeight() - pad);
                float trackH = track.height();
                if (trackH <= 0) return;

                int first = lv.getFirstVisiblePosition();
                int visible = Math.max(1, lv.getLastVisiblePosition() - first + 1);
                float thumbH = Math.max(dp(42), trackH * Math.min(1f, visible / (float) n));
                float frac = n <= 1 ? 0f : Math.min(1f, first / (float) (n - 1));
                float ty = track.top + (trackH - thumbH) * frac;
                thumb.set(cx - dp(3), ty, cx + dp(3), ty + thumbH);

                if (dragging) {
                    p.setColor(ModouUi.alpha(theme.sub, 0x1F));
                    c.drawRoundRect(track, dp(2), dp(2), p);
                }
                p.setColor(ModouUi.alpha(theme.text, dragging ? 0x99 : 0x38));
                c.drawRoundRect(thumb, dp(3), dp(3), p);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (count() < 20) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        jump(e.getY());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) return false;
                        jump(e.getY());
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) return false;
                        dragging = false;
                        invalidate();
                        if (tocBubble != null) {
                            tocBubble.animate().cancel();
                            tocBubble.animate().alpha(0f).setStartDelay(450).setDuration(160)
                                    .withEndAction(() -> tocBubble.setVisibility(View.GONE)).start();
                        }
                        return true;
                    default:
                        return false;
                }
            }

            private void jump(float y) {
                int n = count();
                if (n <= 0) return;
                float top = dp(4);
                float bottom = getHeight() - dp(4);
                float f = (y - top) / Math.max(1f, bottom - top);
                f = Math.max(0f, Math.min(1f, f));
                int pos = Math.round(f * (n - 1));
                pos = Math.max(0, Math.min(n - 1, pos));
                lv.setSelectionFromTop(pos, 0);
                if (tocBubble != null) {
                    TocRow r = rows.get(pos);
                    String label = mode == 0
                            ? "第 " + (r.chapter + 1) + " 章 · " + r.label
                            : r.label;
                    tocBubble.setText(label);
                    tocBubble.setVisibility(View.VISIBLE);
                    tocBubble.setAlpha(1f);
                }
                invalidate();
            }
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
        DesignSpec lsp = rd();
        if (lsp.rdTocDividerStartDp > 0) {
            // iOS grouped list：行间 0.5px、从文字左缩进起画（与设置页同一套）
            lv.setDivider(new android.graphics.drawable.InsetDrawable(
                    new android.graphics.drawable.ColorDrawable(ModouUi.alpha(theme.text, 0x1A)),
                    dp(lsp.rdTocDividerStartDp), 0, dp(16), 0));
            lv.setDividerHeight(Math.max(1, ModouUi.dp(this, 0.5f)));
        } else {
            lv.setDivider(null);
            lv.setDividerHeight(0);
        }
        lv.setVerticalScrollBarEnabled(false);
        lv.setCacheColorHint(Color.TRANSPARENT);
        lv.setAdapter(sheetAdapter(items, subs, current));
        return lv;
    }

    /** 抽屉行的可变部分：目录动辄几千章，行视图必须复用 */
    private static final class SheetRowHolder {
        LinearLayout row;
        TextView title;
        TextView sub;
    }

    /** 抽屉列表的适配器：主行 + 可选副行，当前项用强调色加粗 */
    private BaseAdapter sheetAdapter(String[] items, String[] subs, int current) {
        final String[] sub2 = subs;
        return new BaseAdapter() {
            @Override public int getCount() { return items.length; }
            @Override public Object getItem(int i) { return items[i]; }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View reuse, ViewGroup parent) {
                // 以前忽略 reuse 每行都 new：目录几千章 = 几千套 TextView，滑动必掉帧
                SheetRowHolder h;
                if (reuse != null && reuse.getTag() instanceof SheetRowHolder) {
                    h = (SheetRowHolder) reuse.getTag();
                } else {
                    h = new SheetRowHolder();
                    LinearLayout row = new LinearLayout(NativeReaderActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    DesignSpec rowSp = rd();
                    int pd = ModouUi.dp(NativeReaderActivity.this, rowSp.rdTocSelectedFill ? 12 : 8);
                    row.setPadding(pd, ModouUi.dp(NativeReaderActivity.this, rowSp.rdTocPadV), pd,
                            ModouUi.dp(NativeReaderActivity.this, rowSp.rdTocPadV));
                    TextView t = new TextView(NativeReaderActivity.this);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, rowSp.rdTocTitleSp);
                    t.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(t);
                    TextView s = new TextView(NativeReaderActivity.this);
                    s.setTextSize(TypedValue.COMPLEX_UNIT_SP, rowSp.rdTocSubSp);
                    s.setSingleLine(true);
                    s.setEllipsize(TextUtils.TruncateAt.END);
                    s.setTextColor(theme.sub);
                    s.setPadding(0, ModouUi.dp(NativeReaderActivity.this, 3), 0, 0);
                    row.addView(s);
                    h.row = row;
                    h.title = t;
                    h.sub = s;
                    row.setTag(h);
                }
                boolean on = i == current;
                DesignSpec rsp = rd();
                // M3：当前章整行铺 tonal 底（primaryContainer 的纸张色近似），其余清空
                if (rsp.rdTocSelectedFill) {
                    h.row.setBackground(on
                            ? ModouUi.squircle(NativeReaderActivity.this,
                                    ModouUi.alpha(theme.acc, 0x14), 10)
                            : null);
                }
                h.title.setText(items[i]);
                h.title.setSingleLine(sub2 == null);
                h.title.setTextColor(on ? theme.acc : theme.text);
                h.title.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
                boolean hasSub = sub2 != null && i < sub2.length && !sub2[i].isEmpty();
                h.sub.setVisibility(hasSub ? View.VISIBLE : View.GONE);
                if (hasSub) h.sub.setText(sub2[i]);
                return h.row;
            }
        };
    }

    /* ---------------- 选区动作 ---------------- */

    private void copySelection() {
        String txt = pageView.selectionText();
        if (txt.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("轻阅", txt));
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
    /**
     * 顶栏圆钮的左右安全边距。
     *
     * <p>与正文左右边距对齐（默认 24dp），同时不小于全站标准 16dp ——
     * 正文边距可被用户调到 8dp，顶栏若跟着缩就又贴边了。
     */
    private int headInset() {
        float d = getResources().getDisplayMetrics().density;
        return Math.max(ModouUi.topInset(this), Math.round(marginPx * d));
    }

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
        ModouUi.toast(this, msg);
    }
}
