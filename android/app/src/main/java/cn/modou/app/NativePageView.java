package cn.modou.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.List;

import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ImageSpan;
import android.text.style.ReplacementSpan;
import android.text.style.LineHeightSpan;
import android.text.style.AlignmentSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 一页正文：StaticLayout 排版 + 原生选区。
 *
 * 和网页版最本质的区别在这里：取字用 Layout.getOffsetForHorizontal(line, x) ——
 * 直接问排版引擎「这一行的这个 x 是第几个字」，像素级准确。
 * 没有 DOM 命中测试、没有浮层遮挡、没有「换算失败就兜底到页首/页末」的分支，
 * 而后者正是网页版长按反复选到一整段的根源。
 *
 * 交互（与系统阅读器一致）：
 *   长按 → 在手指处锚定 1 个字，并震动一下；
 *   长按后不松手直接拖 → 选区按手指方向增选，选多少完全由手指决定；
 *   松手后拖两端圆点 → 精确微调；
 *   轻点左/右三分之一 → 上一页 / 下一页；轻点中间 → 呼出菜单；
 *   按住横向拖 → 跟手翻页。
 */
final class NativePageView extends View {

    interface Host {
        /** 页码变化（0 基） */
        void onPageChanged(int page, int total);

        /** 走到章边界还要继续翻：-1 上一章，+1 下一章 */
        void onNeedChapter(int delta);

        /** 选区矩形（View 坐标）；无选区时传 null */
        void onSelRect(RectF r);

        /** 选区文字变化 */
        void onSelText(String text);

        /** 选区被清除 */
        void onSelectionClosed();

        /** 轻点中间 */
        void onTapCenter();
    }

    private static final int LONG_PRESS_MS = 380;

    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 搜索命中底纹：「其余命中」淡一点，「当前这一处」用与长按选区同一个色 */
    private final Paint findPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint findCurPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path selPath = new Path();
    private final RectF selBounds = new RectF();

    private Host host;
    private NativeTheme theme = NativeTheme.of("paper");

    /**
     * 当前章的排版内容。
     *
     * 早先这里是纯 String，插图支持后改成 CharSequence ——
     * 图片以 ImageSpan 挂在占位字符 \uFFFC 上，StaticLayout 原生就认，
     * 于是分页、绘制、选区三条链路一行都不用重写。
     */
    private CharSequence text = "";
    /** 块序列（文字段 + 插图）；纯文字章为 null，此时 text 直接就是正文 */
    private List<Paras.Block> blocks;
    /**
     * 正文插图与封面**共用** ImagePool 的一个池（总预算 = 堆的 1/8）。
     *
     * <p>以前这里单独开 48MB、封面再开 16MB，2GB 机上光图片就是 64MB；
     * 图文混排的书翻两章，进程会被系统直接杀掉。合并成一个池之后，
     * 封面多占时就少放两张插图，反过来也一样，总量永远不超。
     *
     * <p>换章不再 evictAll：那会把书架正在用的封面一起清掉，回到书架又得重新解码。
     * 让 LRU 自己淘汰（最久未看的插图最先走）。
     */
    private static android.graphics.Bitmap imgGet(String url) {
        return url == null ? null : ImagePool.image(url);
    }

    private static void imgPut(String url, android.graphics.Bitmap b) {
        if (url != null && b != null) ImagePool.putImage(url, b);
    }

    private final java.util.Set<String> imgLoading =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    /** 换章计数：旧章的异步解码回来就直接丢掉，别塞进新章 */
    private int imgGen;
    /** 插图解码线程池：以前是一图一线程，图多的章一次开几十条，直接 OOM */
    private static final java.util.concurrent.ExecutorService IMG_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2);
    /** 图到位后合并重排：一章几十张图时，来一张重排一次会把整章排版做成几十遍 */
    private boolean imgDirty;
    /**
     * 本次排版允许「同步解码」的本地插图数（data:/file:）。
     * 本地图解码很快，首次排版就把真实尺寸量出来 → 占位高度一次到位，
     * 不会「先 3:2 占位 → 图到 → 整章重排」那种闪一下再重排。
     * 给个上限是防「漫画型」章几十张大图全在主线程解，宁可超出的走异步也不 ANR。
     */
    private int imgSyncBudget;
    private final Runnable imgRelayout = new Runnable() {
        @Override
        public void run() {
            if (!imgDirty) return;
            imgDirty = false;
            relayoutImages();
        }
    };
    /** 本章插图在文本里的落点（排版后按行号取位置画图），随每次排版重建 */
    private final List<ImgSlot> imgSlots = new ArrayList<>();
    private StaticLayout layout;
    private int[] pStart = new int[0];

    /* ---------------- 行字框缓存（选区 / 手柄 / 工具条的唯一度量来源） ---------------- */

    /**
     * 每一行的「字框」上下沿，单位是 **layout 自己的坐标**（尚未 translate 到版心原点）。
     *
     * <p>由 {@link #rebuildBands()} 在每次排版落定后统一算一次，绘制时只读不猜：
     * 高亮条、手柄竖线、工具条定位全部读同一份值，三处不可能再各算各的。
     * 以前是每个绘制点各自用「textSizePx × 0.92」这类写死系数推，字号/字重一变
     * 就对不上 —— 高亮溢出、竖线比高亮条高一截，根子都在这里。
     */
    private float[] bandTop = new float[0];
    private float[] bandBot = new float[0];
    private int bandN = 0;

    /** 行尾标点悬挂：开时不压缩行尾句读（标点按全宽落版心，不挤半字）。默认关 = 压缩。
     *  两端对齐本身始终开启（见 {@link #typeset} / {@link #justifyByTrack}），不再设开关。 */
    private boolean justifyHang;

    /**
     * 隐藏书内图片：排版时**整块跳过**插图（不占位、不解码、不加载），
     * 效果等于这本书本来就没有图。只影响图文混排，纯文字书不受影响。
     */
    private boolean hideImages;

    /* ---------------- 搜索命中高亮 ---------------- */

    /** 单个关键词最多描多少处（防止「的」这类字把整章铺满底纹、也防止内存爆掉） */
    private static final int MAX_FIND = 2000;

    /** 当前搜索关键词（空 = 不高亮） */
    private String findKw = "";
    /** 当前跳到的那一处（字符偏移）：用来在多个命中里挑出「用户正在看的这一处」 */
    private int findCur = -1;
    /**
     * 命中索引缓存。文本（layout.getText()）没变就直接复用 —— 翻页、唤出菜单、
     * 重绘都不会重复扫全文；换章/改字号重排后引用一变自动重算。
     */
    private CharSequence findSrc;
    private int[] findAt = new int[0];
    /** 当前命中在 findAt 里的下标（-1 = 无） */
    private int findCurAt = -1;
    /**
     * 已应用到 findCurAt 的「当前命中」偏移。与 findCur 不等时只需在已算好的 findAt 里
     * 找最近一处（O(命中数)），不必重扫全文 —— 翻页/挪指针都不触发全文扫描。
     */
    private int findCurApplied = -2;

    private int[] pEnd = new int[0];
    private int[] pTop = new int[0];
    private int page = 0;

    /**
     * 宿主设定的页边距（排版时会被「整字数对齐」微调，这里是原始值）。
     *
     * 中文正文每个字一样宽，屏幕宽度却很少正好是字宽的整数倍：1440px 的屏、24dp 边距、
     * 18sp 字号，可用宽度 1272px 而一个字 88px —— 一行排 14 个字只占 1232px，剩下 40px
     * 全堆在右边。读者看到的就是「右边距比左边距大一半」，而且每行末尾都空小半格。
     * 排版时把这块余量对半分到左右两边（见 {@link #applyCellGrid()}），
     * 左右留白才真正相等、右边界也才齐。
     */
    private int insL, insR;
    /** 实际排版用的左右留白：等于用户设定的页边距（余量改由字距吸收，不再塞进留白） */
    private int padL, padT, padR, padB;
    /** 一个汉字的基准宽度（px，不含字距）：整字格与标点挤压都按它算 */
    private float cellBase;
    /** 为了把整行撑满，每个字额外加的间距（px）；0 表示不需要补 */
    private float trackPx;
    /** 页脚带高度（dp→px 由宿主换算）：页脚绘制钉在这条带里，不随「下边距」漂移 */
    private int footBandH;
    private int headBandH;
    /** 最近一次成功分页时的几何（onDraw 自愈比对用） */
    private int laidW = -1, laidH = -1, laidPT = -1, laidPB = -1;

    /* ---------------- 排版调度（大章切片，避免翻章瞬间卡主线程） ---------------- */

    /**
     * 超过这个字符数才走异步排版。
     *
     * 网文单章多在 2000~4000 字，StaticLayout 构建 + 分页合起来 10~30ms，
     * 同步排完最干脆，也完全不改变原有行为（不会多等一帧、不会闪）。
     * 只有 8000 字以上的大章，同步排版才会被用户感知成「翻章后卡一下」。
     */
    private static final int ASYNC_TYPESET_CHARS = 8000;

    /** 分片分页的粒度：每片最多切这么多页就让出一帧 */
    private static final int PAGINATE_CHUNK = 200;

    /** 每次 relayout 递增；异步任务回来时比对，过期的结果直接丢弃 */
    private int layoutGen = 0;
    /** 是否有分片分页正在进行（宿主可据此避免重复触发） */
    private boolean typesetting = false;
    /** 版心边界辅助线：0 不画，1 顶部，2 底部（拖上/下边距时由宿主打开） */
    private int guideMode = 0;
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 后台是否已有一份排版在跑：拖动滑杆时 60ms 就来一次请求，不能并发开线程 */
    private boolean tsBusy = false;
    private int tsNextGen = -1;
    private Runnable tsNext = null;

    /**
     * 分页结果缓存（3 槽 LRU）。
     *
     * <p>切页表要逐行走一遍 {@link StaticLayout#getLineTop}，两万字一章约几十毫秒。
     * 但「重进同一章」「把字号调大再调回来」「旋转一下又转回来」「上一章看错了退回来」
     * 这些操作，参数和正文其实完全没变 —— 以前每次都从头再切一遍，用户看到的就是白等一瞬。
     *
     * <p>键含正文内容与全部排版参数（宽高、字号、行距、字距、两端对齐、上下内边距），
     * 任何一项变了都自然不命中，不会拿到过期页表。留三份：当前章 + 前后各一章，
     * 正好覆盖「来回翻」这个最常用的动作，常驻内存约 1~2MB（单章页表 + 其 StaticLayout）；
     * 再多就收益递减，不如把内存留给图片。
     */
    private static final class PageCache {
        String key;
        StaticLayout layout;
        CharSequence text;
        int[] s, e, t;
    }

    /**
     * 全局页表缓存：跨书共享，切书再切回来不用重新 measure。
     *
     * <p>原来每个阅读页（=每个 Activity =每本书）各一份 3 槽 LRU，书架切书时进程里
     * 确实还活着另一份，但新阅读页拿不到 —— 于是「切回来」等于从零排一遍。
     * 现在提到静态：键里带 bookId，容量按「最近 25 章」算。
     *
     * <p>为什么不无限大：一条 PageCache = StaticLayout + 三段 int 数组 + 正文 CharSequence，
     * 几十章就是几十 MB，2GB 机上会直接触发系统杀进程。所以除了 25 条的条数上限，
     * 还有一条**字符总量上限**（正文长度之和）：大章节先把自己淘汰掉，
     * 短章节能多留几章。命中失败只是重排一次（约几十毫秒），不会出错。
     */
    private static final int G_CACHE_SLOTS = 25;
    private static final long G_CACHE_CHARS = 2_000_000L;
    private static final LinkedHashMap<String, PageCache> G_CACHE =
            new LinkedHashMap<String, PageCache>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Entry<String, PageCache> eldest) {
                    if (size() <= G_CACHE_SLOTS && gChars <= G_CACHE_CHARS) return false;
                    PageCache v = eldest.getValue();
                    if (v != null && v.text != null) gChars -= v.text.length();
                    return true;
                }
            };
    private static long gChars = 0;
    /** serif / sans-serif 是否同一份字体：-1 未知，0 不同，1 相同（主题字体统一替换的 ROM） */
    private static int serifSansSame = -1;

    /**
     * serif 与 sans-serif 在这台机器上是不是同一份字体。
     *
     * ColorOS / 部分国产 ROM 的主题字体会把这两个族名都指向同一份 ttc，
     * 于是「宋体」和「黑体」点起来毫无区别。用同一串字量宽度判断：
     * 同一份字体量出来完全相等，不同字体几乎不可能撞上。
     */
    private static boolean serifSansAreSame() {
        if (serifSansSame < 0) {
            try {
                TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                p.setTextSize(100f);
                p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                float a = p.measureText("国永Aa");
                p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                float b = p.measureText("国永Aa");
                serifSansSame = Math.abs(a - b) < 0.5f ? 1 : 0;
            } catch (Throwable t) {
                serifSansSame = 0;
            }
        }
        return serifSansSame == 1;
    }

    /** 当前书的 id：只用来拼缓存键，保证同一章在不同书里不会互相命中（同名章节很常见） */
    private String cacheBook = "";
    /** 分片分页的中间态：正在切的页表，切完才整体换成 pStart/pEnd/pTop */
    private int asyncGen = -1;
    private int[] aS = new int[0], aE = new int[0], aT = new int[0];
    private int aLine = 0, aCount = 0;
    private float textSizePx = 54f;
    private float lineMul = 1.9f;
    /**
     * 行距里「多出来的那部分」（px）。
     *
     * 以前用 setLineSpacing(0, multiplier) 实现：multiplier 会连字体的 ascent 一起放大，
     * 而 StaticLayout 又把多出来的行距在字上下各分一半 —— 于是每一行的字顶都比行顶高
     * 几个像素，首行（尤其是放大 1.22 倍的章标题）会被正文裁剪区切掉一条。
     * 改成「倍数恒为 1、多出来的高度全走 add」，行高总和不变（分页结果不变），
     * 但多余空间只落在字下方，字顶与行顶对齐，标题再也不会被切。
     */
    private float lineAdd = 0f;
    private boolean bold = false;
    /** 当前字重档位（300 / 400 / 500 / 600）：300 细 · 400 常规 · 500 中粗 · 600 粗 */
    private int weight = 500;
    /**
     * 每行字框是否已经按**这一行真实的字**量过。
     *
     * <p>以前是拿一支示例字符串（"国永宁，"）量一个通用高度，再往每行上套 ——
     * 一行里有「永」的顶点、有「，」的下伸，示例字未必比它高，于是总差几个像素。
     * 现在逐行量：选中哪几行就量哪几行，量一次缓存，排版变了才重算。
     */
    private boolean[] bandOk = new boolean[0];
    /** 量墨迹用的临时画笔与矩形：不动 layout 自己的 Paint，避免顺手改掉绘制状态 */
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Rect inkRect = new android.graphics.Rect();
    private String family = "song";
    /** 段距（单位：倍，与网页版 --para-gap 一致；0 = 无间距）。复用纯文字章原文以便实时调距重排 */
    private float paraGap = 1.0f;
    private CharSequence rawBody = "";
    /** 章标题：排在正文首行、居中加粗 1.22 倍（对齐网页版 .chapter-title） */
    private String title = "";

    /** 选区：selA 起、selB 止（不含）；anchor = 长按锚点，拖动时固定不动 */
    private int selA = -1;
    private int selB = -1;
    private int anchor = -1;
    /** 换章时要跳到的字符偏移；有尺寸之后才排得出版，所以延后到 relayout 里消费 */
    private int pendingOffset = -1;
    /**
     * 「落到本章最后一页」的意图。
     *
     * 从章首页往回翻时，宿主传的偏移是 {@link Integer#MAX_VALUE}；但换章是
     * setChapter/setBlocks + setTitle 两次排版（标题先沿用旧章的，随后才换），
     * 用「字符偏移」中转会在第二次排版里失真：标题多出/少掉一行，页数就变一页，
     * 原来的「末页页首偏移」正好落在新的倒数第二页上。这里单独记一个意图位，
     * 两次排版都直接取最后一页，直到用户真的翻页为止。
     */
    private boolean pendingLast;

    /**
     * 点击区域映射：0 上一页 / 1 下一页 / 2 呼出菜单 / 3 无动作。
     *
     * <p>默认「左上一页、右下一页」只照顾右手单手握持：拇指够不到左半边，
     * 左手用户反过来握就成了「想往前翻却一直在退」。
     */
    private int tapLeft = 0, tapRight = 1;
    /**
     * 边缘安全区（px）：单手握持时拇指根、掌心肉常年压在屏幕最边上。
     * 这一条既防「没想翻页却翻了页」，也防手掌压住时把长按选词唤醒。
     * 由宿主按密度换算后传入，0 表示不启用。
     */
    private int edgeGuard = 0;
    /** 滚动模式下这一下按在边缘：只挡「点击翻屏」，上下滑照样能滚 */
    private boolean edgeTap;
    private float downX, downY, lastX, lastY;
    private long downAt;
    private boolean pressing, selecting, draggingHandle, dragA, turning;
    /** 抓住手柄那一刻的手指 y：用来区分「指尖压在圆点上」和「真的要上下扩选」 */
    private float dragFromY;
    /** 本次按下是「点空白处收起选区」：这一下不翻页（网页版同样行为，避免误触） */
    private boolean selDismissed;
    /** 拖动中已经把工具条收起来了；松手后要让它重新出现 */
    private boolean handleHidden;
    private float turnDx;
    /** 松手未达阈值时的回弹动画（单独持有，避免和翻页动画互相 cancel 出错） */
    private ValueAnimator springBack;
    /** 已在本章边界、还想继续翻：记住方向，松手时交给宿主去取相邻章 */
    private int edgeDir;

    /* ---------------- 翻页方式：左右分页 / 上下滚动 ----------------
     * 两种模式共用同一份 StaticLayout 与同一套 pStart/pEnd/pTop 页表：
     * 分页模式按「页」画，滚动模式按连续的 scrollPx 画，page 由 scrollPx 反查。
     * 这样进度保存、跳转、工具坞进度条、正文搜索全都不用改。 */
    private boolean scrollMode = false;
    /** 滚动模式下的正文纵向偏移（px，0 = 章首） */
    private float scrollPx = 0f;
    private float downScroll;
    private boolean scrolling;
    private float flingVel;
    /** 顶到边界的瞬间手指落在哪：只有越过边界后继续拉够距离才跨章 */
    private float edgeAtY;
    private ValueAnimator flingAnim;
    private final android.view.VelocityTracker vtrack = android.view.VelocityTracker.obtain();

    /* ---------------- 页眉页脚（仅分页模式，与网页版一致） ---------------- */
    /** 0 不显示 / 1 章节名 / 2 书名 / 3 书名+章节 */
    private int headMode = 0;
    /** 位掩码：1 页码 / 2 时间 / 4 电量 / 8 全书进度 / 16 本章进度 / 32 剩余章数 */
    private int footMask = 0;
    private String hfBook = "";
    private String hfChapter = "";
    private int hfBookIndex = 0;
    private int hfBookTotal = 0;
    private int batteryPct = -1;
    private final Paint hfPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 同章翻页动画：dir=±1，dx 从 ±width 回到 0 */
    private int animDir;
    private float animDx;
    private int animOld = -1;
    private ValueAnimator anim;
    /**
     * 跟手/翻页动画时的整页预渲染位图（两槽：当前页 + 相邻页）。
     *
     * 以前每个 ACTION_MOVE 都 invalidate，onDraw 里把「当前页」和「相邻页」各用
     * layout.draw(c) 画一遍 —— 而 drawPage 画的是**整章** StaticLayout（长章几千行），
     * 只是最后裁到页框。等于每一帧都把整章重画两遍，长章节滑动肉眼可见地卡。
     * 现在每页只在手势开始渲染一次到位图，之后逐帧只贴图；排版/主题一变就作废重来。
     */
    private Bitmap[] pbmp = new Bitmap[3];
    private int[] pbmpIdx = new int[]{-1, -1, -1};
    /** 位图对应的排版代数：和 layoutGen 不一致就作废 */
    private int pbmpGen = -1;
    /** 曾经分配失败（OOM）：本次排版内不再重试，直接逐帧直画，避免每帧都去撞一次内存 */
    private boolean pbmpOom;
    /** 空闲预渲染任务（见 prefetchPageBmp） */
    private final Runnable prefetchBmpTask = this::prefetchPageBmp;
    /** 跨章翻页动画：旧页整屏快照 + 新章内容滑入（与同章翻页同一套 180ms 平移） */
    private Bitmap crossBmp;
    /** 已请求换章、正等正文到位：+1 下一章 / -1 上一章 */
    private int crossPendingDir;
    /** 跨章动画进行中的方向（0 = 没在动） */
    private int crossDir;
    /** 旧页快照的横向偏移：跟手时从手指位置起，动画跑到 ∓width */
    private float crossDx;
    private ValueAnimator crossAnim;
    /**
     * 本次手势内不再自动预取相邻章。
     *
     * <p>拖动时会「提前取章」，但如果宿主明确回「没有上一章/下一章」，每个 MOVE
     * 都再试一次就会反复建整屏快照（每帧一张位图）。失败一次就锁到抬手为止。
     */
    private boolean crossNoRetry;

    /** 文字亮度/柔化系数：1 = 主题原色，0.5 = 半透（夜间白字 → 灰）。只改正文文字 Paint，不动背景与面板 */
    private float textBright = 1.0f;

    private final int slop;
    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!pressing || selecting || turning) return;
            beginSel(lastX, lastY);
        }
    };

    NativePageView(Context c) {
        super(c);
        // 原来乘了 2（≈16dp），要滑出这么远页面才开始跟手，轻扫会觉得「划不动」；
        // 收到 1.2 倍（≈10dp）既保留防抖，又让第一下就有反应。
        slop = Math.round(ViewConfiguration.get(c).getScaledTouchSlop() * 1.2f);
        tp.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        tp.setColor(theme.text);
        selPaint.setStyle(Paint.Style.FILL);
        handlePaint.setStyle(Paint.Style.FILL);
        // 页眉页脚：11dp 无衬线、字距、7 成不透明度（对齐网页版 .pg-head/.pg-foot）
        hfPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        hfPaint.setTextSize(dp(11));
        try {
            hfPaint.setLetterSpacing(0.03f);
        } catch (Throwable ignored) {
            /* 旧系统没有字距 */
        }
    }

    /** 文字亮度/柔化：把正文文字 Paint 的透明度按 f 缩放（f∈[0.5,1]）。 */
    void setBright(float f) {
        f = Math.max(0.5f, Math.min(1.0f, f));
        if (Math.abs(f - textBright) < 0.001f) return;
        textBright = f;
        applyTextColor();
        invalidate();          // 当前帧立即用新颜色重画
        invalidatePageBmps();  // 预渲染位图作废，下一帧按新透明度重烘
    }

    /** 正文文字颜色 = 主题基础文字色 + 按 textBright 缩放的透明度。背景与面板文字走别的 Paint，不受影响。 */
    private void applyTextColor() {
        int base = theme != null ? theme.text : 0xFF000000;
        tp.setColor(base);
        tp.setAlpha((int) Math.round(Color.alpha(base) * textBright));
        syncLayoutPaintColor();
    }

    /**
     * 把当前字色/透明度同步进活动版面的 Paint。
     *
     * <p>为什么必须有这一步：异步排版（大章）与跨章预热用的都是 {@code new TextPaint(tp)}
     * 的<b>副本</b>构建版面，而 StaticLayout 画字用的正是构建时传入的那支 Paint ——
     * 主题、亮度一变只改 tp，副本的字色永远停在排版那一刻。大章在日间主题下排好版，
     * 切到夜间后整章还是纸色主题的深棕字，画在黑底上就是「个别章节整体发暗」；
     * 同理「文字亮度」滑杆对它也不生效。版面装上（分页完成/缓存命中）或字色一变，
     * 都把副本拉回与 tp 同色。几何属性（字号/字距/描边）绝不同步：那会和已构建的
     * 断行不一致，几何一变必须走缓存键整体重排。
     */
    private void syncLayoutPaintColor() {
        Paint p = layout != null ? layout.getPaint() : null;
        if (p == null || p == tp) return;
        p.setColor(tp.getColor());
        p.setAlpha(tp.getAlpha());
    }

    /* ---------------- 翻页方式 ---------------- */

    /** 切换翻页方式；按当前阅读位置对接，读者不会跳回章首 */
    void setScrollMode(boolean on) {
        if (this.scrollMode == on) return;
        if (on) {
            scrollPx = pStart.length > 0 ? pTop[Math.max(0, Math.min(page, pStart.length - 1))] : 0;
        } else if (layout != null && pStart.length > 0) {
            page = pageOfScroll(scrollPx);
        }
        this.scrollMode = on;
        if (flingAnim != null) {
            flingAnim.cancel();
            flingAnim = null;
        }
        clearSelInternal();
        invalidate();
        notifyPage();
    }

    boolean isScrollMode() {
        return scrollMode;
    }

    /**
     * 页眉页脚配置。这里只改字段、不重排：高度变化由宿主改 padT/padB 走 setStyle 统一重排，
     * 免得「改配置」和「改留白」各排一次、正文闪两下。
     */
    /** 点击区域：0 上一页 / 1 下一页 / 2 呼出菜单 / 3 无动作 */
    void setTapZones(int l, int r) {
        tapLeft = l;
        tapRight = r;
    }

    /** 边缘安全区宽度（px）：区域内按下即整段手势不生效 */
    void setEdgeGuardPx(int px) {
        edgeGuard = Math.max(0, px);
    }

    void setHeadFoot(int head, int foot) {
        this.headMode = head;
        this.footMask = foot;
        invalidate();
    }

    /**
     * 页脚带高度（px）：页脚画在距屏幕底 footBandH 的带内固定位置。
     * 之前用 padB 反推（padB = footH + 下边距），用户把下边距调大后页脚
     * 也跟着往上漂，页脚下沿空出一大条 —— 页脚应该钉底，下边距只控制
     * 正文末行与页脚顶的间隙。
     */
    void setFootBandH(int h) {
        this.footBandH = Math.max(0, h);
    }

    /**
     * 页眉带高度（px）：页眉在这条带子里垂直居中。
     *
     * 以前基线写死 `padT - 24dp`，字号一调大（16sp 以上）字就被推到带子上沿、
     * 撞进状态栏甚至被切掉一半。改成「按带高居中」后，字号多大都还在自己的带里。
     */
    void setHeadBandH(int h) {
        this.headBandH = Math.max(0, h);
    }

    /** 页眉页脚字号（px）。改完只需重绘：正文的可用区域由 Activity 端重新算 padT/padB 触发重排 */
    void setHeadFootSize(float px) {
        if (Math.abs(hfPaint.getTextSize() - px) < 0.1f) return;
        hfPaint.setTextSize(Math.max(1f, px));
        invalidate();
    }

    /** 页眉页脚文案与全书进度来源 */
    void setHeadFootText(String bookName, String chapterName, int bookIndex, int bookTotal) {
        this.hfBook = bookName == null ? "" : bookName;
        this.hfChapter = chapterName == null ? "" : chapterName;
        this.hfBookIndex = Math.max(0, bookIndex);
        this.hfBookTotal = Math.max(0, bookTotal);
        invalidate();
    }

    void setBattery(int pct) {
        if (batteryPct == pct) return;
        batteryPct = pct;
        invalidate();
    }

    /** 页眉或页脚是否会占正文高度（滚动模式不画，与网页版一致） */
    boolean headFootOn() {
        return !scrollMode && (headMode != 0 || footMask != 0);
    }

    private float maxScroll() {
        if (layout == null) return 0;
        int h = getHeight() - padT - padB;
        return Math.max(0, layout.getHeight() - h);
    }

    /** scrollPx 落在第几页：滚动模式下「页」只用于进度与页码 */
    private int pageOfScroll(float sy) {
        if (layout == null || pStart.length == 0) return 0;
        for (int i = 0; i < pStart.length; i++) {
            int bottom = (i + 1 < pStart.length) ? pTop[i + 1] : layout.getHeight();
            if (sy < bottom) return i;
        }
        return pStart.length - 1;
    }

    /** 滚动模式：当前屏顶对应的字符偏移（重排后用它找回位置） */
    private int offsetAtScrollTop() {
        if (layout == null || layout.getHeight() <= 0) return 0;
        int line = layout.getLineForVertical(
                (int) Math.max(0f, Math.min(scrollPx, layout.getHeight() - 1)));
        return layout.getLineStart(line);
    }

    /** 页（或滚动位置）在 view 里的纵向起点：两种模式统一的换算入口 */
    private float topOf(int p) {
        if (scrollMode) return scrollPx;
        if (pTop.length == 0) return 0;
        return pTop[Math.max(0, Math.min(p, pTop.length - 1))];
    }

    /** 滚动到指定像素：夹在 [0, maxScroll]，并把 page 同步到宿主 */
    private void setScroll(float v) {
        float max = maxScroll();
        int prev = edgeDir;
        if (v < 0) {
            edgeDir = -1;
            v = 0;
        } else if (v > max) {
            edgeDir = 1;
            v = max;
        } else {
            edgeDir = 0;
        }
        // 记下「刚顶到边界」那一瞬的手指位置：只有越过边界后还继续拉，才算要换章。
        // 否则正常滑到底也会被当成跨章请求（手指位移早就超过阈值了）。
        if (edgeDir != 0 && prev == 0) edgeAtY = lastY;
        if (Math.abs(v - scrollPx) < 0.5f) {
            invalidate();
            return;
        }
        scrollPx = v;
        int np = pageOfScroll(scrollPx);
        if (np != page) {
            page = np;
            notifyPage();
        }
        invalidate();
    }

    /** 平滑滚到某处（点按翻屏 / 惯性收尾共用） */
    private void animateScrollTo(float to, long dur) {
        if (layout == null) return;
        to = Math.max(0, Math.min(maxScroll(), to));
        if (Math.abs(to - scrollPx) < 1f) return;
        if (flingAnim != null) flingAnim.cancel();
        ValueAnimator va = ValueAnimator.ofFloat(scrollPx, to);
        flingAnim = va;
        va.setDuration(dur);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> setScroll((float) a.getAnimatedValue()));
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (flingAnim == an) flingAnim = null;
            }
        });
        va.start();
    }

    /** 滚动模式下点左/右三分区：上下滚一屏 */
    private void scrollOneScreen(int dir) {
        float h = getHeight() - padT - padB;
        float to = scrollPx + dir * h * 0.92f;
        if (to < -1) {
            if (host != null) host.onNeedChapter(-1);
        } else if (to > maxScroll() + 1) {
            if (host != null) host.onNeedChapter(1);
        } else {
            animateScrollTo(to, 220);
        }
    }


    void setHost(Host h) {
        host = h;
    }

    /**
     * 搜索高亮：正文里所有命中都描底，「当前这一处」用长按选区同色。
     *
     * 关键字为空或 offset&lt;0 时等同于 clearFind()。offset 是搜索扫描时的字符偏移，
     * 与排版文本偶有几十字的偏差（段距/标点重排），这里按**最近的一处**认当前命中，
     * 不要求精确相等。
     */
    void setFind(String kw, int offset) {
        String k = kw == null ? "" : kw.trim();
        if (k.equals(findKw) && (k.isEmpty() || offset == findCur)) return;
        boolean kwChanged = !k.equals(findKw);
        findKw = k;
        findCur = k.isEmpty() ? -1 : offset;
        // 只有换关键词才作废命中表（下次绘制重扫全文）；只移动「当前命中」指针绝不清表，
        // 否则翻页/上下一处时每帧都重扫整章（大章主线程卡顿 = 概率性闪烁）
        if (kwChanged) findSrc = null;
        invalidate();
    }

    void clearFind() {
        if (findKw.isEmpty()) return;
        findKw = "";
        findCur = -1;
        findSrc = null;
        findAt = new int[0];
        findCurAt = -1;
        findCurApplied = -2;
        invalidate();
    }

    /**
     * 建/复用命中索引：只在 layout 文本或关键词变了之后扫一遍全文。
     *
     * 用 regionMatches(true,...) 而不是 toLowerCase 后 indexOf —— 个别字符的大小写映射
     * 会改长度（土耳其 İ 等），一旦长度变了，偏移就和排版文本对不上，高亮会整体错位。
     *
     * 关键：挪「当前命中」指针（上一处/下一处）时 findKw 与文本都没变，findSrc 仍命中，
     * 这里不再扫全文，只在一个轻量的循环里从已算好的 findAt 挑最近的一处 —— 大章翻页、
     * 上下一处都不会再在主线程重扫整章（那才是概率性闪烁的根）。
     */
    private void ensureFindIndex() {
        CharSequence src = layout == null ? null : layout.getText();
        // 文本或关键词变了：才重扫一遍全文命中表（含 toString 物化带 justify span 的长串）
        if (src != findSrc) {
            findSrc = src;
            findAt = new int[0];
            findCurAt = -1;
            findCurApplied = -2;
            if (src == null || src.length() == 0 || findKw.isEmpty()) return;
            String hay = src.toString();
            int m = findKw.length();
            if (m == 0 || hay.length() < m) return;
            ArrayList<Integer> xs = new ArrayList<>();
            int i = 0;
            while (xs.size() < MAX_FIND) {
                int p = indexOfFold(hay, findKw, i);
                if (p < 0) break;
                xs.add(p);
                i = p + m;
            }
            if (xs.isEmpty()) return;
            findAt = new int[xs.size()];
            for (int k = 0; k < findAt.length; k++) findAt[k] = xs.get(k);
        }
        // 只移动了「当前命中」指针：在已算好的命中表里找最近的一处，O(命中数) 不扫全文
        if (findCur != findCurApplied) {
            if (findCur >= 0 && findAt.length > 0) {
                int best = 0, bd = Integer.MAX_VALUE;
                for (int k = 0; k < findAt.length; k++) {
                    int d = Math.abs(findAt[k] - findCur);
                    if (d < bd) { bd = d; best = k; }
                }
                // 取最近的一处：段距/标点压缩会把偏移推后几十字，但绝不会推到「另一处」后面去。
                // 不设距离阈值 —— 设了就会出现「跳过去却一处都没认领」的情况（满屏淡底纹）。
                findCurAt = best;
            } else {
                findCurAt = -1;
            }
            findCurApplied = findCur;
        }
    }

    /** 大小写不敏感查找，返回原文偏移；不改变长度，偏移可直接用于排版文本 */
    private static int indexOfFold(String hay, String ned, int from) {
        int last = hay.length() - ned.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (hay.regionMatches(true, i, ned, 0, ned.length())) return i;
        }
        return -1;
    }

    /**
     * 画本页（本可视窗）内的命中底纹。调用前画布已 translate 到正文排版原点，
     * 且已裁到页面范围，所以越界的命中直接被裁掉。
     */
    private void drawFindBands(Canvas c, int firstLine, int lastLine) {
        if (findKw.isEmpty() || layout == null) return;
        ensureFindIndex();
        if (findAt.length == 0) return;
        int n = layout.getLineCount();
        if (n <= 0) return;
        int a = layout.getLineStart(Math.max(0, Math.min(n - 1, firstLine)));
        int b = layout.getLineEnd(Math.max(0, Math.min(n - 1, lastLine)));
        int len = findKw.length();
        for (int k = 0; k < findAt.length; k++) {
            int s = findAt[k], e = s + len;
            if (e <= a || s >= b) continue; // 不在本页：跳过（章头章的命中也不白算一次行距）
            drawBand(c, s, e, k == findCurAt ? findCurPaint : findPaint);
        }
    }

    void setStyle(NativeTheme t, float sizePx, float lineMul, String family, int weight,
                  float paraGap, int padL, int padT, int padR, int padB,
                  boolean justifyHang) {
        // 阈值必须小于滑杆最小档位（行距 0.01、段距 0.05）：判成「没变」就等于
        // 那一档白拖了 —— 用户看到的就是「再拖也不动」。
        final String oldFamily = this.family;
        boolean dirty = Math.abs(this.textSizePx - sizePx) > 0.5f
                || Math.abs(this.lineMul - lineMul) > 0.004f
                || this.insL != padL || this.padT != padT || this.insR != padR || this.padB != padB
                || !this.family.equals(family)
                || this.weight != weight
                || this.bold != (weight >= 600)
                || Math.abs(this.paraGap - paraGap) > 0.004f
                || this.justifyHang != justifyHang;
        this.theme = t;
        this.textSizePx = sizePx;
        this.lineMul = lineMul;
        this.family = family == null ? "song" : family;
        this.weight = weight;
        this.bold = weight >= 600;
        this.paraGap = paraGap;
        this.justifyHang = justifyHang;
        this.insL = padL;
        this.insR = padR;
        this.padL = padL;
        this.padT = padT;
        this.padR = padR;
        this.padB = padB;
        setBackgroundColor(t.bg);
        tp.setTextSize(sizePx);
        applyTextColor();
        // 三档字体必须「看起来就是三种字」。
        // 旧的写法是 SERIF / SANS_SERIF / (SERIF+8% 倾斜)，问题在 ColorOS 这类
        // 「主题字体统一替换」的 ROM 上：serif 与 sans-serif 会被绑成同一份字体，
        // 楷体只是同一份字倾斜 8% —— 三档点下来一模一样，用户看到的就是「切换没反应」。
        // 现在：楷体改用等宽（MONOSPACE，字面比例完全不同，必然可辨），
        // 并且检测 serif/sans 是不是同一份，是的话把黑体压窄 6% 造出确定差异。
        // 只有 600 才是真 Bold 字面；300~500 一律 NORMAL，靠描边做连续加厚。
        // 旧写法是 500 = BOLD + fakeBold、600 = BOLD —— 中粗因此比粗还粗。
        final int style = this.bold ? Typeface.BOLD : Typeface.NORMAL;
        Typeface base;
        float skew = 0f;
        if ("kai".equals(this.family)) {
            base = Typeface.create("monospace", style);
            // 等宽 + 明显右倾：Android 的 monospace 只覆盖拉丁字形，中文仍会回退到
            // 无衬线，光靠族名中文看不出差别 —— 倾斜是几何变换，一定看得见。
            skew = 0.14f;
        } else if ("hei".equals(this.family)) {
            // 上一版为了「让黑体看得出和宋体不同」，在 serif/sans 同族时把黑体压窄 7%。
            // 结果是笔画没变粗、字反而更瘦更挤（用户原话：极细、行与行粘连）。
            // 现在改成往「厚」的方向区分：同族时用 sans-serif-medium（500 字重），
            // 既能一眼分辨，又只会让黑体更饱满，不会再把它压瘦。
            base = Typeface.create(serifSansAreSame() ? "sans-serif-medium" : "sans-serif", style);
        } else {
            base = Typeface.create("serif", style);
        }
        tp.setTypeface(base);
        tp.setTextSkewX(skew);
        // 缩放一律归位：任何 textScaleX 都会连带改变字宽与字距，
        // 一旦小于 1 就是肉眼可见的「瘦 + 挤」，不能拿它当字体差异手段
        tp.setTextScaleX(1f);
        // 抗锯齿 + 亚像素 + 抖动：CJK 笔画细，关掉抗锯齿会碎，开着但没亚像素会发虚发淡
        tp.setAntiAlias(true);
        tp.setSubpixelText(true);
        tp.setDither(true);
        // fakeBold 是「轮廓整体横移 1px 再画一遍」，加厚量是个死数，和不存在的
        // 字重档之间根本对不上；这里彻底不用它，改由 applyStrokeWeight 做四档。
        tp.setFakeBoldText(false);
        applyStrokeWeight(weight);
        // 量字格必须先清掉字距（上一轮排版可能给它加过），否则字格会越量越大。
        // 注意 Android 的 letterSpacing 单位是 em（0 = 正常，0.1 = 每个字多 0.1 个字高），
        // 不是「倍数」，所以复位用 0 而不是 1。
        tp.setLetterSpacing(0f);
        cellBase = tp.measureText("国");
        // 行字框不再在这里预估：setStyle 之后必然重排，rebuildBands() 会作废缓存，
        // 真正画到哪一行时再按那一行的文字量一次（见 ensureLineInk）。
        // 这里只把量尺的属性对齐到当前字体/字号/描边，攒着给那一处处用。
        inkPaint.setTypeface(base);
        inkPaint.setTextSize(textSizePx);
        inkPaint.setTextSkewX(skew);
        inkPaint.setLetterSpacing(0f);
        selPaint.setColor(t.sel);
        // 其余命中 22%：能看到「这里还有一处」，又不至于抢正文；
        // 当前命中 42%：比长按选区（30%）再重一点，跳过来第一眼就落在它上面
        findPaint.setColor((t.acc & 0x00FFFFFF) | 0x38000000);
        findCurPaint.setColor((t.acc & 0x00FFFFFF) | 0x6B000000);
        handlePaint.setColor(t.handle);
        // 主题色可能变了但 dirty 为 false（只换配色、字号没动）：位图也得作废，否则夜间模式
        // 下滑动翻页贴出来的还是白天的旧配色
        invalidatePageBmps();
        if (dirty) {
            // 字体变了必须把页表缓存清干净：字宽变了但字号/边距这些键里的字段没变，
            // 只要键里少一段就会把旧字体的页表原样装回去 —— 屏幕上「点了一点反应没有」。
            if (oldFamily == null || !oldFamily.equals(this.family)) clearPageCache();
            // 纯文字章：段距变化要重排原文（段间插入等高的空白行）；图文章在 relayout 里重建
            if (blocks == null && rawBody != null) text = withTitle(bodyText());
            relayout();
        }
        invalidate();
    }

    /**
     * 四档字重：300 细 · 400 常规 · 500 中粗 · 600 粗。
     *
     * <p>旧实现是「500 = Bold 字面 + fakeBold、600 = Bold 字面」，于是**中粗比粗还粗** ——
     * 用户看到的「档位乱」不是观感问题，是这一行代码本身的错。
     *
     * <p>现在只有 600 用真 Bold 字面，300~500 一律走 NORMAL 字面的**同色描边**做连续加厚。
     * 描边以字形轮廓为中心向外扩半个线宽，加厚量是精确可控的连续量：
     *
     * <ul>
     * <li>300 细：不描边，作为基准</li>
     * <li>400 常规：+0.009em（+15% 笔画，就是「稍微饱满一点」）</li>
     * <li>500 中粗：+0.020em（+34%，明显更厚）</li>
     * <li>600 粗：Bold 字面 + 0.036em（+59%，再叠真粗体字面）</li>
     * </ul>
     *
     * <p>600 必须**一定**比 500 粗，所以它不是「只换个字面」：国产 ROM 上的中文字体
     * 常常没有独立 Bold 字面，{@code Typeface.create(BOLD)} 直接回退到 Regular，
     * 于是 600 与 500 只差 0.006em，肉眼看不出区别（这正是上一版的问题）。
     * 现在 600 的字面**和**描边都比 500 大，两条路任意一条失效仍能分出档位。
     *
     * <p>描边不改 advance，所以断行、字宽、页表都不受影响，只有笔画厚度变 ——
     * 也就不会出现「切字重一下、整章重新分页」。
     */
    private void applyStrokeWeight(int w) {
        float sw;
        if (w >= 600) sw = textSizePx * 0.036f;
        else if (w >= 500) sw = textSizePx * 0.020f;
        else if (w >= 400) sw = textSizePx * 0.009f;
        else sw = 0f;
        if (sw <= 0.01f) {
            tp.setStyle(Paint.Style.FILL);
            tp.setStrokeWidth(0f);
        } else {
            // 描边与填充同色：只加厚，不产生第二圈轮廓
            tp.setStyle(Paint.Style.FILL_AND_STROKE);
            tp.setStrokeWidth(sw);
        }
    }

    /**
     * 隐藏 / 显示书内图片。
     *
     * <p>插图在排版里是「占 k 行」的一块，开关一变整章行数就变，必须重排一次；
     * 重排走 relayout 的现有的位置恢复（pendingOffset / 当前页首字符偏移），
     * 读者不会被打回章首。关掉时先把已解码位图放掉，省内存也省得残留旧图。
     */
    void setHideImages(boolean hide) {
        if (hideImages == hide) return;
        hideImages = hide;
        if (hide) dropImages();
        relayout();
        invalidate();
    }

    /** 换章（纯文字）；offset>=0 时跳到该字符所在页（尺寸未就绪时延后消费） */
    void setChapter(String body, int offset) {
        dropImages(); // 上一章的插图全部释放：位图是内存大头
        this.blocks = null;
        this.rawBody = body == null ? "" : body;
        this.text = withTitle(bodyText());
        this.pendingLast = offset == Integer.MAX_VALUE;
        this.pendingOffset = pendingLast ? -1 : offset;
        clearSelInternal();
        relayout();
        invalidate();
        maybeStartCross();
    }

    /**
     * 换章（纯文字，标题一起给）：只排一次版。
     *
     * 以前是 setChapter(...) + setTitle(...)：第一次排版用着上一章的标题，
     * 标题一变又整章重排一遍 —— 一章两万字的书，等于白排一次 StaticLayout，
     * 用户看到的就是「翻下一章要等一下」。合起来之后换章只量一次。
     */
    void setChapter(String body, String title, int offset) {
        dropImages();
        this.blocks = null;
        this.rawBody = body == null ? "" : body;
        this.title = title == null ? "" : title.trim();
        this.text = withTitle(bodyText());
        this.pendingLast = offset == Integer.MAX_VALUE;
        this.pendingOffset = pendingLast ? -1 : offset;
        clearSelInternal();
        relayout();
        invalidate();
        maybeStartCross();
    }

    /** 设置章标题：已排好的正文按同一套规则重排，标题始终是首行 */
    void setTitle(String t) {
        String nt = t == null ? "" : t.trim();
        if (nt.equals(title)) return;
        title = nt;
        if (blocks != null) {
            text = buildContent(blocks, title, getWidth() - padL - padR, imgSlots);
            relayout();
            invalidate();
        } else if (rawBody != null && rawBody.length() > 0) {
            text = withTitle(bodyText());
            relayout();
            invalidate();
        }
    }

    /** 把标题拼到正文前面：居中 + 加粗 + 1.22 倍字号，后接一行段距高度的留白 */
    private CharSequence withTitle(CharSequence body) {
        if (title == null || title.isEmpty()) return body;
        SpannableStringBuilder out = new SpannableStringBuilder();
        appendTitle(out, title);
        out.append('\n'); // 标题行保持自然高度：这里不能压行高，否则标题顶部会被裁
        out.append(body == null ? "" : body);
        return out;
    }

    /** 章名归一化：去掉缩进/换行/多余空白，只留可比较的字面 */
    private static String normHead(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean sp = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\u3000' || ch == '\u00a0' || ch == ' ' || ch == '\t'
                    || ch == '\n' || ch == '\r') {
                if (!sp && sb.length() > 0) {
                    sb.append(' ');
                    sp = true;
                }
            } else {
                sb.append(ch);
                sp = false;
            }
        }
        return sb.toString().trim();
    }

    private static boolean isBlankCh(char ch) {
        return ch == '\u3000' || ch == '\u00a0' || ch == ' ' || ch == '\t' || ch == '\r';
    }

    /**
     * 去掉正文开头那一行「和章标题重复的标题行」。
     *
     * 书源抓下来的正文格式极不统一：有的首行带全角缩进、有的前面还空一行，
     * 于是「目录章名」和「正文里的标题行」会同时出现，屏幕上就是两个一样的标题。
     * 判断放在最终排版这一层兜底，纯文字/图文、缓存/网络任何入口都不会漏。
     */
    private static String stripDupTitle(String body, String title) {
        String t = normHead(title);
        if (body == null || t.isEmpty()) return body == null ? "" : body;
        int n = body.length(), i = 0;
        while (i < n && (isBlankCh(body.charAt(i)) || body.charAt(i) == '\n')) i++;
        int e = body.indexOf('\n', i);
        if (e < 0) e = n;
        if (!normHead(body.substring(i, e)).equals(t)) return body;
        int cut = Math.min(n, e + 1);
        while (cut < n && (body.charAt(cut) == '\n' || isBlankCh(body.charAt(cut)))) cut++;
        return body.substring(Math.min(cut, n));
    }

    /** 正文（已去掉重复首行）→ 带段距的排版文本；标题变了也要重新取一次 */
    private CharSequence bodyText() {
        String raw = rawBody == null ? "" : rawBody.toString();
        return applyParaGaps(stripDupTitle(raw, title));
    }

    /** 标题行：居中、加粗、放大到正文的 1.22 倍（不缩进） */
    private void appendTitle(SpannableStringBuilder sb, String ttl) {
        if (ttl == null || ttl.isEmpty()) return;
        int s = sb.length();
        sb.append(ttl);
        int e = sb.length();
        sb.setSpan(new RelativeSizeSpan(1.22f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * 换章（图文混排）：把「文字段 + 插图」的块序列排进同一个 StaticLayout。
     *
     * 插图走 ImageSpan 而不是另画一层，原因很实际：
     * StaticLayout 会自动为它留出行高、处理换行、参与分页，
     * 也就是说插图是「正文的一部分」而不是「盖在正文上的浮层」——
     * 不会出现图压字、翻页时图断在两页之间。
     *
     * 网络图是异步的：先放占位符占住位置（页数与字符偏移因此稳定），
     * 图到了再换上真图重排。反过来（等图到齐再排）会让首屏白等一张慢图。
     */
    void setBlocks(List<Paras.Block> list, int offset) {
        dropImages(); // 同上：换章先把上一章的位图放掉
        this.blocks = list == null ? null : new ArrayList<>(list);
        this.pendingLast = offset == Integer.MAX_VALUE;
        this.pendingOffset = pendingLast ? -1 : offset;
        clearSelInternal();
        // 图文排版交给 relayout 统一做一次：它先按整字格校准版心宽度，
        // 再据此量插图占位。这里预先排一次等于白排（用的是上一章的版心）。
        relayout();
        invalidate();
        loadImages();
        maybeStartCross();
    }

    /** 换章（图文混排，标题一起给）：与纯文字版同理，只排一次版 */
    void setBlocks(List<Paras.Block> list, String title, int offset) {
        dropImages();
        this.blocks = list == null ? null : new ArrayList<>(list);
        this.title = title == null ? "" : title.trim();
        this.pendingLast = offset == Integer.MAX_VALUE;
        this.pendingOffset = pendingLast ? -1 : offset;
        clearSelInternal();
        // 同上：图文排版只在 relayout 里做一次，这里不要抢先排
        relayout();
        invalidate();
        loadImages();
        maybeStartCross();
    }

    /**
     * 块序列 → 可排版的 CharSequence。
     *
     * 图片占位字符 \uFFFC 无论图有没有加载完都会先放进去：
     * 少了它，图加载完成的那一刻字符偏移会整体位移，读者正在看的那一页会跳走。
     * 段与段（含插图）之间用「等高的空白行」隔开——高度 = 段距 × 字号，与网页版
     * --para-gap 的语义完全一致（段距 1.0 = 1 个字号高）。
     *
     * <p>参数化版本：正文块、标题、图片槽列表都从外面传，这样「预排版相邻章」可以复用同一套
     * 正文拼装逻辑，又绝不碰实例的 {@code imgSlots}（当前章正在显示/加载的图片），
     * 也不会因为传入别的章的块而污染当前章。
     */
    private CharSequence buildContent(List<Paras.Block> blks, String ttl, int widthPx,
                                     List<ImgSlot> outSlots) {
        outSlots.clear();
        imgSyncBudget = 16;   // 本次排版最多同步解 16 张本地图（见 drawableFor）
        if (blks == null) return text == null ? "" : text;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (ttl != null && !ttl.isEmpty()) {
            appendTitle(sb, ttl);
            sb.append('\n'); // 同上：标题行不压高
        }
        int bodyStart = sb.length();
        boolean first = true;
        // 首个文本块若就是章标题（书源正文常自带一行标题），跳过它：否则屏幕上两个标题
        String nt = normHead(ttl);
        for (Paras.Block b : blks) {
            if (skipLeadBlock(b, first, nt)) continue;
            // 隐藏图片：插图整块跳过 —— 不占行、不进 imgSlots，等于图不存在。
            // 必须在 appendGap 之前 continue，否则会留下一个空段落把正文拉开一条缝
            if (hideImages && b.isImage()) continue;
            if (!first) appendGap(sb); // 段间留白：首段前、末段后不加
            first = false;
            if (b.isImage()) {
                /* 插图用「占 k 行」的方式占位，而不是靠 span 撑行高。
                 *
                 * 试过两条路都不行：ImageSpan 只报宽度不报行高；ReplacementSpan /
                 * AbsoluteSizeSpan 在 StaticLayout 的测量路径里也改不动这一行的高度
                 * （实测图仍然压住上下的字）。而「插入 k-1 个真的换行」是排版器一定
                 * 会照做的事：一行一个字高，k 行就是一整块，图在这块里居中画。
                 * 占位字符用全角空格，看不见，也不会被当成缺字方框。 */
                android.graphics.drawable.Drawable d = drawableFor(b.value, widthPx);
                int padV = Math.max(2, Math.round(textSizePx * 0.5f));
                int lineH = Math.max(1, Math.round(textSizePx * lineMul));
                int want = (d == null ? 0 : d.getBounds().height()) + padV * 2;
                int k = Math.max(1, (int) Math.ceil(want / (double) lineH));
                int start = sb.length();
                sb.append('\u3000');
                for (int i = 1; i < k; i++) sb.append('\n');
                if (d != null) outSlots.add(new ImgSlot(start, d, k));
            } else {
                sb.append("　　").append(b.value); // 与纯文字版一致：段首全角缩进
            }
        }
        return sb;
    }


    /**
     * 段落分隔：换行符 + 段距。
     *
     * 注意这里**只给标题用**的版本不同：正文段距沿用一直以来的实现（把段末行压到
     * 段距高），观感是之前调好的那种紧凑段距；标题行则绝不能这么做 ——
     * LineHeightSpan.Standard 是「把这一行设成这么高」，标题字号 1.22 倍，
     * 压到 0.8 倍字号后装不下，字就向上溢出被裁掉。
     */
    private void appendGap(SpannableStringBuilder sb) {
        int start = sb.length();
        sb.append('\n');
        int gapPx = Math.max(2, Math.round(paraGap * textSizePx));
        sb.setSpan(new LineHeightSpan.Standard(gapPx), start, start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 纯文字章：按段距在段间插入等高空白行；段距为 0 时原样返回 */
    private CharSequence applyParaGaps(CharSequence src) {
        if (paraGap <= 0.01f || src == null) return src;
        String s = src.toString();
        String[] parts = s.split("\n", -1);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int gapPx = Math.max(2, Math.round(paraGap * textSizePx));
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                int start = sb.length();
                sb.append('\n');
                sb.setSpan(new LineHeightSpan.Standard(gapPx), start, start + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append(parts[i]);
        }
        return sb;
    }

    /**
     * 供正文搜索复用：把块序列排成与阅读页完全一致的带段距 CharSequence。
     * 不设置插图位图——偏移只取决于字符，有无位图不影响字符位置，因此搜索得到的
     * offset 可以直接喂给 setBlocks / setChapter，落点和阅读页对得上。
     */
    static CharSequence gapped(CharSequence rawText, List<Paras.Block> blocks,
                               float paraGap, float textSizePx) {
        return gapped(rawText, blocks, paraGap, textSizePx, null);
    }

    /**
     * 首段要不要跳过：空段，或者就是章标题本身（书源正文常自带一行标题，会造成双标题）。
     * 排版与搜索共用同一条判断，否则搜索偏移会和屏幕上的字符差一行，跳转会错位。
     */
    private static boolean skipLeadBlock(Paras.Block b, boolean first, String normTitle) {
        if (b == null || !first || b.isImage()) return false;
        String v = normHead(b.value);
        return v.isEmpty() || (!normTitle.isEmpty() && v.equals(normTitle));
    }

    /**
     * 同上，并把章标题算进去 —— 阅读页标题占字符，搜索偏移必须跟着算，
     * 否则命中位置会整体前移「标题字数 + 换行」个字符。
     */
    static CharSequence gapped(CharSequence rawText, List<Paras.Block> blocks,
                               float paraGap, float textSizePx, String title) {
        String t = title == null ? "" : title.trim();
        if (blocks != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            int gp0 = Math.max(2, Math.round(paraGap * textSizePx));
            if (!t.isEmpty()) {
                sb.append(t);
                sb.append('\n');   // 标题行：自然高度，不压
            }
            String nt = normHead(t);
            boolean first = true;
            for (Paras.Block b : blocks) {
                if (skipLeadBlock(b, first, nt)) continue;
                if (!first) {
                    int s = sb.length();
                    sb.append('\n');
                    sb.setSpan(new LineHeightSpan.Standard(gp0), s, s + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                first = false;
                if (b.isImage()) sb.append('\uFFFC');
                else sb.append("　　").append(b.value);
            }
            return sb;
        }
        String src = stripDupTitle(rawText == null ? "" : rawText.toString(), t);
        if (paraGap <= 0.01f) {
            return t.isEmpty() ? src : (t + "\n" + src);
        }
        String[] parts = src.split("\n", -1);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int gp = Math.max(2, Math.round(paraGap * textSizePx));
        if (!t.isEmpty()) {
            sb.append(t);
            sb.append('\n');   // 标题行自然高度
        }
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                int s = sb.length();
                sb.append('\n');
                sb.setSpan(new LineHeightSpan.Standard(gp), s, s + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append(parts[i]);
        }
        return sb;
    }

    /** 已加载的图用真图；本地图首次排版就同步解码量真实尺寸；网络图没到才用占位 */
    private android.graphics.drawable.Drawable drawableFor(String url, int widthPx) {
        android.graphics.Bitmap bm = imgGet(url);
        int w = widthPx > 0 ? widthPx : 300;
        // 本地图（data:/file:）同步解码：首次排版就拿到真实比例，占位高度一次到位，
        // 图到后不必再整章重排。本地书跳章/重新进章不再「闪一下再重新排版」。
        if (bm == null && url != null && imgSyncBudget > 0 && isLocalImage(url)) {
            imgSyncBudget--;
            try {
                bm = CoverLoader.fetch(url, w, true);
                if (bm != null) imgPut(url, bm);
            } catch (Throwable ignore) {
                /* 解码失败：退回占位，走异步加载 */
            }
        }
        if (bm != null && !bm.isRecycled()) {
            /* 宽度铺满版心，高度按原始比例算 —— 关键是「宽高用同一个系数」。
             * 旧代码把宽固定成版心宽、只缩高度，300px 的采样图被横向拉开 4 倍，
             * 画出来就是一条被压扁的横条。 */
            int dw = w;
            int dh = Math.max(1, Math.round((float) bm.getHeight() * w / Math.max(1, bm.getWidth())));
            // 太高的图（长条插画）缩到「一屏的九成」，宽度同步收，比例不变
            int usable = getHeight() - padT - padB;
            int maxH = Math.max(240, Math.round((usable > 0 ? usable : 800) * 0.8f));
            if (dh > maxH) {
                dw = Math.max(1, Math.round(dw * (float) maxH / dh));
                dh = maxH;
            }
            android.graphics.drawable.BitmapDrawable d =
                    new android.graphics.drawable.BitmapDrawable(getResources(), bm);
            d.setBounds(0, 0, dw, dh);
            return d;
        }
        // 图还没到：按 3:2 占位。以前是 w/3（又矮又扁），图一到整章要重排两次
        android.graphics.drawable.ColorDrawable ph =
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);
        ph.setBounds(0, 0, w, Math.max(60, Math.round(w * 0.66f)));
        return ph;
    }

    /** 本地图（内联 base64 或本地文件）：解码不依赖网络，可同步解出真实尺寸 */
    private static boolean isLocalImage(String url) {
        return url.startsWith("data:image") || url.startsWith("file://");
    }

    /** 一张插图在排版里的落点：从第 offset 个字符起，占 lines 行，图在这几行里居中 */
    private static final class ImgSlot {
        final int offset;
        final Drawable d;
        final int lines;

        ImgSlot(int offset, Drawable d, int lines) {
            this.offset = offset;
            this.d = d;
            this.lines = Math.max(1, lines);
        }
    }

    /**
     * 把插图画进它占住的那几行里（在 layout.draw 之后、同一套坐标里）。
     *
     * 之所以自己画而不是挂 ImageSpan：ImageSpan 与 ReplacementSpan 都改不动行高，
     * 图会以「一行的高度」为容器、从基线往上溢出，正好盖住上一行（章标题就是这么没的）。
     * 这里行高由真正的换行撑出来，图只在自己的行块内居中，绝不可能越过边界。
     */
    private void drawImgSlots(Canvas c, int firstLine, int lastLine) {
        if (layout == null || imgSlots.isEmpty()) return;
        int contentW = getWidth() - padL - padR;
        int n = layout.getLineCount();
        for (ImgSlot s : imgSlots) {
            int line = layout.getLineForOffset(Math.min(s.offset, Math.max(0, text.length() - 1)));
            int endLine = Math.min(line + s.lines, n);
            if (endLine - 1 < firstLine || line > lastLine) continue;
            int top = layout.getLineTop(line);
            int bot = layout.getLineTop(endLine);
            int h = s.d.getBounds().height();
            int w = s.d.getBounds().width();
            float left = Math.max(0, (contentW - w) / 2f);
            float ty = top + Math.max(0, (bot - top - h) / 2f);
            c.save();
            c.translate(left, ty);
            s.d.draw(c);
            c.restore();
        }
    }

    /** 该行是不是纯空白行（段距空行）：分页时不能被当作页首 */
    private boolean blankLine(int line) {
        return blankLine(layout, text, line);
    }

    /** 分页时用的是「还没挂到成员上的」StaticLayout，故显式传进来 */
    private static boolean blankLine(StaticLayout sl, CharSequence src, int line) {
        if (sl == null) return false;
        int a0 = sl.getLineStart(line);
        int b0 = sl.getLineEnd(line);
        for (int i = a0; i < b0; i++) {
            char ch = src.charAt(i);
            if (ch != '\n' && !isBlankCh(ch)) return false;
        }
        return true;
    }

    /** 某个行号若是插图的起始行，返回这块图的结束行（不含）；否则 -1 */
    private int imgBlockEnd(int line) {
        return imgBlockEnd(layout, text, line);
    }

    private int imgBlockEnd(StaticLayout sl, CharSequence src, int line) {
        if (sl == null) return -1;
        for (ImgSlot s : imgSlots) {
            int l = sl.getLineForOffset(Math.min(s.offset, Math.max(0, src.length() - 1)));
            if (l == line) return line + s.lines;
        }
        return -1;
    }

    /** 异步拉取没到位的插图（data:URL 是本地导入的书，解码很快） */
    private void loadImages() {
        if (blocks == null) return;
        if (hideImages) return; // 已隐藏图片：一张都不解码，省内存也省一次重排
        final List<String> todo = new ArrayList<>();
        for (Paras.Block b : blocks) {
            if (b == null || !b.isImage()) continue;
            String u = b.value;
            if (u == null || u.isEmpty()) continue;
            if (imgGet(u) != null || imgLoading.contains(u)) continue;
            imgLoading.add(u);
            todo.add(u);
        }
        if (todo.isEmpty()) return;
        final int gen = imgGen;
        for (final String u : todo) {
            IMG_POOL.execute(() -> {
                android.graphics.Bitmap bm = null;
                try {
                    // 正文插图按版心宽度解码：300px 的封面采样率铺到 1200px 版心上会糊成一条
                    int tw = Math.max(600, getWidth() - padL - padR);
                    bm = CoverLoader.fetch(u, tw, true);
                } catch (Throwable t) {
                    bm = null;
                }
                final android.graphics.Bitmap got = bm;
                post(() -> {
                    imgLoading.remove(u);
                    if (got == null) return;
                    if (gen != imgGen) {
                        got.recycle(); // 已经翻到别的章了：这张图用不上
                        return;
                    }
                    imgPut(u, got);
                    // 合并重排：120ms 内到的图算一批，只重排一次。
                    // 来一张重排一次的话，一章几十张图等于把整章排版重复做几十遍，卡到不能用。
                    imgDirty = true;
                    removeCallbacks(imgRelayout);
                    postDelayed(imgRelayout, 120);
                });
            });
        }
    }

    /** 换章时放掉上一章的所有位图，并让在途的解码作废 */
    private void dropImages() {
        imgGen++;
        imgLoading.clear();
        removeCallbacks(imgRelayout);
        imgDirty = false;
        // 不再 evictAll：池是全局共用的，清空会把书架的封面一起扫地出门。
        // 内存上限由 ImagePool 的 1/8 预算守住，旧章插图会被 LRU 自然淘汰。
    }

    /** 图到了：重排一次。keep 住当前位置，读者不会因插图撑开而跳页 */
    private void relayoutImages() {
        int keep = layout != null && pStart.length > 0 ? pageStartOffset() : 0;
        this.pendingOffset = keep;
        // 同上：图到了之后由 relayout 统一重排一次，这里不再抢先排一遍
        relayout();
        invalidate();
    }

    void clearSel() {
        if (selA < 0) return;
        clearSelInternal();
        invalidate();
        if (host != null) {
            host.onSelRect(null);
            host.onSelectionClosed();
        }
    }

    private void clearSelInternal() {
        selA = -1;
        selB = -1;
        anchor = -1;
        draggingHandle = false;
        selecting = false;
    }

    boolean hasSelection() {
        return selA >= 0 && selB > selA;
    }

    String selectionText() {
        if (!hasSelection()) return "";
        int a = Math.max(0, Math.min(selA, text.length()));
        int b = Math.max(0, Math.min(selB, text.length()));
        if (b <= a) return "";
        // 选区可能跨过图片占位符 \uFFFC：复制出来前换成空格，否则粘出去是个不可见字符
        return text.subSequence(a, b).toString().replace('\uFFFC', ' ');
    }

    /** 全选：整章（番茄/微信的「全选」就是整章，不是当前这一页） */
    void selectAll() {
        if (layout == null || text.length() == 0) return;
        int n = text.length();
        int a = 0, b = n;
        // 首尾的换行/缩进不进选区：复制出去带着一串全角空格和空行很难看
        while (a < b && isBlank(text.charAt(a))) a++;
        while (b > a && isBlank(text.charAt(b - 1))) b--;
        if (b <= a) return;
        selA = a;
        selB = b;
        anchor = a;
        invalidate();
        if (host != null) {
            host.onSelRect(selRectView());
            host.onSelText(selectionText());
        }
    }

    int page() {
        return page;
    }

    int pageCount() {
        return pStart.length;
    }

    /**
     * 大章是否正在后台排版。
     *
     * 宿主用它决定「加载中」什么时候收：大章从 setChapter 到真正可画要一两百毫秒，
     * 这期间屏幕还停在上一章，先把「排版中」留着，比让画面莫名不动要清楚。
     */
    boolean isTypesetting() {
        return typesetting;
    }

    int pageStartOffset() {
        if (layout == null || pStart.length == 0) return 0;
        // 滚动模式：屏顶那个字才是真正的阅读位置，记页首会让重进时倒退一屏
        if (scrollMode) return offsetAtScrollTop();
        return layout.getLineStart(pStart[Math.min(page, pStart.length - 1)]);
    }

    int pageEndOffset() {
        if (layout == null || pStart.length == 0) return 0;
        return layout.getLineEnd(pEnd[Math.min(page, pEnd.length - 1)]);
    }

    int textLength() {
        return text.length();
    }

    /** 当前章的排版内容（书签摘要、同章跳转要用） */
    CharSequence currentText() {
        return text;
    }

    void gotoPage(int p) {
        if (pStart.length == 0) return;
        dropCross();
        pendingLast = false;
        page = Math.max(0, Math.min(p, pStart.length - 1));
        if (scrollMode) scrollPx = pTop[page];
        // 记住位置：后续若因字号/主题/插图触发重排，分页完按这个字符偏移复位，不会回跳
        pendingOffset = (layout != null && pStart.length > 0)
                ? layout.getLineStart(pStart[Math.min(page, pStart.length - 1)]) : -1;
        clearSelInternal();
        invalidate();
        // 跳转（搜索/目录/滑杆）落定后也把邻页备好，接着翻页不再临时栅格化
        removeCallbacks(prefetchBmpTask);
        postDelayed(prefetchBmpTask, 140);
        if (host != null) {
            host.onSelRect(null);
            host.onPageChanged(page, pStart.length);
        }
    }

    /** 回到首屏（新章节加载完成后调用） */
    void resetToFirstPage() {
        page = 0;
        pendingLast = false;
        scrollPx = 0f;
        clearSelInternal();
        invalidate();
        notifyPage();
    }

    private void notifyPage() {
        if (host != null) host.onPageChanged(page, pStart.length);
    }

    /* ---------------- 排版与分页 ---------------- */

    /**
     * 整字格 + 字距补余：正文按整字排版，把不足一格的余量摊到字距上。
     *
     * 中文一个字就是一格，但可用宽度几乎不可能是字宽的整数倍：1272px 的版面、88px 的字，
     * 一行排 14 字只占 1232px，剩下 40px 要么全落在右边（右边界不齐、右边距明显偏大），
     * 要么两边各分一半（左右是齐了，可两边留白都被悄悄撑大半个字 —— 用户看到的就是
     * 「左右边距都太宽」）。开源阅读 legado 的做法是标点挤压 + 让右边界自然参差，
     * 但那样右边仍会多出近一格。这里改成第三条路：把这 40px 平摊给这一行的 14 个字
     * （每个字多 2.9px，肉眼不可见），左右留白就严格等于用户设定的页边距，
     * 右边界也真正齐平。
     *
     * 余量小于 4 个字宽（窄屏/超大字号）时不折腾，免得把正文挤成一条。
     */
    private void applyCellGrid() {
        padL = insL;
        padR = insR;
        // ★ 这里以前把「每行装不满一格的那点余量」平摊成字距给全章（trackPx → letterSpacing）。
        // 那是「处 那个房间」这种字中间凭空一道缝的根源：字距是给整章 paint 设的，
        // 一旦某行的 span 边界把它截断，补出来的宽度就全堆在断点上，成一个空洞。
        // 现在一像素都不补 —— 两端对齐交给系统 justifier（见 buildLayout），
        // 它按行自己算，行内均匀、绝不只在某一处张开。
        trackPx = 0f;
        tp.setLetterSpacing(0f);
    }

    /** 一个汉字占多宽（px）：中文排版的一格。用不带字距的基准值，避免越量越宽 */
    private float cellWidth() {
        return cellBase > 1f ? cellBase : Math.max(1f, textSizePx);
    }

    private void relayout() {
        applyCellGrid();
        // 排版一变，整页预渲染位图立即作废（分页/字号/边距/主题都可能变）
        invalidatePageBmps();
        // 代数在入口就递增：任何一次排版（包括下面「尺寸没就绪」的空跑）都让在途的
        // 异步结果作废 —— 否则切到空章/零尺寸之后，旧任务回来还会把上一章排回去。
        final int gen = ++layoutGen;
        int w = getWidth() - padL - padR;
        int h = getHeight() - padT - padB;
        // 行高余量必须在这里算，而不是等到 typeset 之前。
        // 旧顺序是「先 cacheKey、后 lineAdd」：拖行距时键里还是上一档的 lineAdd，
        // 键一模一样 → 直接命中上次的页表 → 屏幕上纹丝不动。
        // 这就是「从最左拖到最右都没变化」的根因，跟滑杆范围一点关系没有。
        Paint.FontMetricsInt fm0 = tp.getFontMetricsInt();
        lineAdd = Math.max(0f, textSizePx * lineMul - (fm0.descent - fm0.ascent));
        if (blocks != null) text = buildContent(blocks, title, w, imgSlots); // 宽度变了，占位图尺寸要跟着变
        else imgSlots.clear();                      // 纯文字章：上一章的插图槽位必须丢掉
        if (w > 0 && h > 0 && text.length() > 0) {
            // 参数与正文都没变 → 复用上次的页表，本次不 measure
            String ck = cacheKey(w, h);
            PageCache c = gGet(ck);
            if (c != null && c.layout != null && c.s != null && c.s.length > 0) {
                applyCache(c, pendingLast ? Integer.MAX_VALUE
                        : pendingOffset >= 0 ? pendingOffset
                        : (layout != null ? (scrollMode ? offsetAtScrollTop() : pageStartOffset()) : 0));
                return;
            }
        }
        if (w <= 0 || h <= 0 || text.length() == 0) {
            layout = null;
            rebuildBands();     // layout 为空 → bandN 归零，避免用上一章的旧行框画高亮
            pStart = new int[0];
            pEnd = new int[0];
            pTop = new int[0];
            typesetting = false;
            return;
        }
        // 关键修复（根治大章「概率性重排/闪烁」）：
        // laidW/laidH/laidPT/laidPB 原本只在 paginate 末尾（异步分页全部切完）才写入，
        // 大章异步分页要分好几帧，这期间它们一直是初始的 -1。onDraw 的「几何自愈」会把
        // 「-1 ≠ 当前宽」误判成尺寸变了，反复 post(relayout) 把在途的异步分页取消又重排，
        // 拍翅膀式重排 —— 小章同步分页瞬间完成遇不到这窗口，只有大章（有的书）会中招，
        // 且取决于点击时机，故表现为「概率性」。这里在排版一开始就锁住真实几何，
        // 异步期间自愈不再误触发；真正尺寸变化仍由 onSizeChanged → relayout 处理，自愈兜底不失效。
        if (w > 0 && h > 0) {
            laidW = getWidth();
            laidH = getHeight();
            laidPT = padT;
            laidPB = padB;
        }
        int keep = pendingLast ? Integer.MAX_VALUE
                : pendingOffset >= 0 ? pendingOffset
                : (layout != null ? (scrollMode ? offsetAtScrollTop() : pageStartOffset()) : 0);
        // CSS 的 line-height 语义是「行高 = 字号 × 倍数」，而 StaticLayout 的行高是
        // 字体自身 ascent+descent 再乘 multiplier。这里把差额算成 add、multiplier 固定 1：
        // 行高总和与旧算法完全一致（分页不变），但多余空间只落在一行字的下方 ——
        // 字的顶部与行顶对齐，首行（章标题）不会再被正文裁剪区切掉一截。
        // （lineAdd 已在函数入口算好，缓存键要用它，这里只补注意事项：别挪回去。）
        // 字距补余之后每行末端会多出「最后一个字之后的那点间距」：版面宽度放宽 1px
        // 才不会把最后一字挤到下一行，裁切仍按 padR 走，视觉上不会溢出
        int layoutW = w + (trackPx > 0f ? 1 : 0);
        // 大章：StaticLayout 构建挪到后台、分页分片，排完自己 return（不冻主线程）；
        // 小章继续走下面的同步路径 —— 与旧实现完全一致，不多一帧、不闪。
        if (text.length() > ASYNC_TYPESET_CHARS) {
            startAsyncTypeset(gen, text, keep, w, h, layoutW);
            return;
        }
        // 同步路径也走同一个分页函数（budget 无限 = 一次切完）：
        // 大小章共用同一份分页算法，不会出现「短章和大章分页结果不一致」的漂移。
        Typeset ts = typeset(text, tp, layoutW, lineAdd, w, justifyHang);
        text = ts.text;   // 补白 span 挂在副本上，版面必须和这份文本配套
        paginate(gen, ts.layout, text, keep, w, h, Integer.MAX_VALUE);
    }

    /* ---------------- 分页缓存 ---------------- */

    /** 缓存键：正文内容 + 全部会影响分页的参数 */
    /** 分页算法版本：构建期由 NativePageView.java 内容哈希注入（见 build.gradle），改一行算法哈希即变、旧页表整体作废 */
    private static final String PAGINATE_VER = BuildConfig.PAGINATE_VER;

    private String cacheKey(int w, int h) {
        // debug 守卫：防止被手改回硬编码 "p3" 而绕过算法哈希（release 下无开销）
        if (BuildConfig.DEBUG && PAGINATE_VER.equals("p3")) {
            android.util.Log.w("ModouPageView", "PAGINATE_VER 未注入：缓存键不会随算法变化而失效");
        }
        return cacheKeyFor(text, w, h);
    }

    /**
     * 参数化缓存键：与 {@link #cacheKey(int, int)} 同源，但正文从参数取。
     * 预排版相邻章时用它按「那章的正文」算键，保证跨章后 relayout 生成同样的键、能命中。
     */
    private String cacheKeyFor(CharSequence body, int w, int h) {
        // 键首段是 bookId：不同书里同名的「第 1 章」不会互相命中
        // family 必须进键：宋体/黑体/楷体的字宽不同，同一段文字断行位置也不同。
        // 少了这一段，切字体时键完全不变 → 直接命中旧页表（旧 StaticLayout），
        // 屏幕上一个字都不会变 —— 这就是「点字体没反应」的根因。
        return PAGINATE_VER + "|" + cacheBook + "|" + body.length() + ":" + body.toString().hashCode()
                + ":" + w + "x" + h
                + ":" + Math.round(textSizePx * 100)
                + ":" + Math.round(lineAdd * 100)
                // 行距 / 段距直接进键：lineAdd 是派生值，段距根本不体现在页表几何里
                // （只改段间空白），少了这两段，改完命中旧页表 —— 拖到底也没反应。
                + ":" + Math.round(lineMul * 1000)
                + ":" + Math.round(paraGap * 1000)
                + ":" + Math.round(trackPx * 100)
                + ":" + (justifyHang ? 1 : 0)
                + ":" + weight
                + ":" + family
                + ":" + padT + ":" + padB;
    }

    /** 当前书 id（只影响缓存键，不参与排版） */
    void setCacheBook(String id) {
        cacheBook = id == null ? "" : id;
    }

    /** 拖上/下边距时打开版心边界线，松手关掉 */
    void setGuide(int m) {
        if (guideMode == m) return;
        guideMode = m;
        invalidate();
    }

    /**
     * 清空页表缓存。
     *
     * 换字体这类「字宽变了但几何参数都没变」的操作，只要缓存键有一丝对不上，
     * 旧页表就会被原样装回去 —— 屏幕上一个字都不动。与其继续猜键里少了哪一段，
     * 不如清干净重排一次：一次几十毫秒，换来「点了一定有反应」。
     */
    void clearPageCache() {
        synchronized (G_CACHE) {
            G_CACHE.clear();
            gChars = 0;
        }
        invalidatePageBmps();
    }

    private static PageCache gGet(String key) {
        synchronized (G_CACHE) {
            return G_CACHE.get(key);
        }
    }

    private static void gPut(String key, PageCache pc) {
        synchronized (G_CACHE) {
            PageCache old = G_CACHE.put(key, pc);
            if (old != null && old.text != null) gChars -= old.text.length();
            gChars += pc.text != null ? pc.text.length() : 0;
            // removeEldestEntry 在 put 里同步执行，这里再压一次字符总量：
            // 单条超大章节（几十万字）可能一次就把预算吃满，只剩它自己也不划算
            while (gChars > G_CACHE_CHARS && G_CACHE.size() > 1) {
                java.util.Iterator<Entry<String, PageCache>> it = G_CACHE.entrySet().iterator();
                if (!it.hasNext()) break;
                PageCache v = it.next().getValue();
                it.remove();
                if (v != null && v.text != null) gChars -= v.text.length();
            }
        }
    }

    /** 命中缓存：把页表整体装回去，效果与 paginate 收尾一致 */
    private void applyCache(PageCache c, int keep) {
        text = c.text;          // 版面必须配这份文本（补白 span 挂在它上面）
        layout = c.layout;
        syncLayoutPaintColor(); // 缓存版面可能是预热时按旧主题排的：字色先拉齐再上屏
        pStart = c.s;
        pEnd = c.e;
        pTop = c.t;
        rebuildBands();         // 版面换了，行字框必须同步换（选区/手柄全靠它）
        invalidatePageBmps();
        laidW = getWidth();
        laidH = getHeight();
        laidPT = padT;
        laidPB = padB;
        page = keep == Integer.MAX_VALUE ? Math.max(0, pStart.length - 1) : pageOfOffset(keep);
        if (page >= pStart.length) page = Math.max(0, pStart.length - 1);
        if (scrollMode) scrollPx = pTop.length > 0 ? pTop[Math.max(0, Math.min(page, pTop.length - 1))] : 0;
        pendingOffset = -1;
        typesetting = false;
        notifyPage();
        invalidate();
        if (!scrollMode) {
            removeCallbacks(prefetchBmpTask);
            postDelayed(prefetchBmpTask, 140);
        }
    }

    /* ---------------- 大章异步排版 ---------------- */

    /**
     * 大章排版：StaticLayout 在后台线程构建，回主线程后分片切页表。
     *
     * 构建阶段占了大章排版的绝大部分时间（两万字约 100ms 以上），单独挪走之后，
     * 主线程在等待期间仍能响应手势与绘制 —— 翻章不再「顿一下才出字」。
     * 画笔必须复制一份交给后台：StaticLayout 构建时会持续读它，而主线程同时在画旧页。
     *
     * 等待期间不清空 layout / pStart：屏幕上继续显示上一页（换章时是上一章），
     * 直到新页表切完才整体切换。所有绘制与偏移换算都对 layout 判空，
     * 这层「旧画面兜底」是安全的。
     */
    private void startAsyncTypeset(int gen, CharSequence src, int keep, int w, int h, int layoutW) {
        typesetting = true;
        // 拖动滑杆时每 60ms 就请求一次重排：并发开线程只会互相抢 CPU，
        // 结果是「拖了很久画面还停在同一页」。这里只保留最新的一次排队，
        // 正在跑的那份排完立刻接力 —— 输出会滞后一帧，但一直跟着手指走。
        if (tsBusy) {
            if (gen >= tsNextGen) {
                tsNextGen = gen;
                tsNext = () -> startAsyncTypeset(gen, src, keep, w, h, layoutW);
            }
            return;
        }
        tsBusy = true;
        final TextPaint bgPaint = new TextPaint(tp);
        bgPaint.setLetterSpacing(tp.getLetterSpacing());
        final CharSequence bgText = src;
        final boolean bgJustifyHang = justifyHang;
        new Thread(() -> {
            Typeset ts;
            try {
                // 补白要多排一两遍，全放在后台线程做（主线程只在结果回来时换页）
                ts = typeset(bgText, bgPaint, layoutW, lineAdd, w, bgJustifyHang);
            } catch (Throwable t) {
                Log.w("ModouPageView", "async typeset failed: " + t);
                ts = null;
            }
            final Typeset ft = ts;
            post(() -> {
                try {
                    if (gen != layoutGen) return;   // 已被更新的一次排版取代
                    if (ft == null) {
                        // 后台构建失败（极端字号/字体）：退回同步再排一次，别停在旧画面上
                        typesetting = false;
                        relayout();
                        return;
                    }
                    text = ft.text;   // 与版面配套的那份文本（带按行补白 span）
                    paginate(gen, ft.layout, ft.text, keep, w, h, PAGINATE_CHUNK);
                } finally {
                    finishTs();
                }
            });
        }, "modou-typeset").start();
    }

    /** 本次后台排版收尾：放行排队中的最新一次请求（有就接力，没有就闲下来） */
    private void finishTs() {
        tsBusy = false;
        Runnable r = tsNext;
        tsNext = null;
        tsNextGen = -1;
        if (r != null) r.run();
    }

    /**
     * 切页表（可分片）。
     *
     * @param budget 本次最多切多少页；{@link Integer#MAX_VALUE} 表示一次切完（同步路径）
     *
     * 分片只影响「切多快」，不影响切出来的页：所有分片都在主线程串行执行，
     * 中途若又发生一次 relayout，{@link #layoutGen} 变化会让剩余分片直接退出。
     * 切完才把页表整体换到 pStart/pEnd/pTop，不会出现「半张页表被画出来」。
     */
    private void paginate(int gen, StaticLayout sl, CharSequence src, int keep,
                          int w, int h, int budget) {
        if (gen != layoutGen) return;   // 已被更新的一次排版取代，中间态直接丢弃
        int n = sl.getLineCount();
        if (asyncGen != gen) {
            asyncGen = gen;
            aS = new int[n];
            aE = new int[n];
            aT = new int[n];
            aLine = 0;
            aCount = 0;
        }
        int count = aCount;
        int line = aLine;
        // 底部只留 1dp 安全边：StaticLayout 行框与实际字形在个别 ROM 上有 1~2px 出入，
        // 卡着 h 精确放行时末行下缘会被裁掉一点点（「逗号缺一角」）。
        int fitH = h - Math.round(dp(1));
        // 判定用「字形底」而不是「行框底」：StaticLayout 的行框底部含着整份行距余量
        // （lineMul 1.85 时约 0.7em），按行框卡高，每页页尾都会空出那截 ——
        // 下边距拖到 0 也贴不住页脚，读者看到的就是「最后一行离页脚一大段白」。
        // 字形底才是眼里那一行，行距余量留给页与页之间，页内不再重复占位。
        int glyphDesc = Math.round(tp.getFontMetrics().descent);
        // 分片预算自适应：按总行数的 1/8 切，最多 8 帧切完。
        // 固定 200 页/片在 90 万字（约 3 万行）的极端章节上要切 150 帧、拖到两三秒；
        // 按比例切则单帧耗时随章长线性增长但仍有上限（一帧几毫秒），整体收在 8 帧内。
        int left = Math.max(Math.max(1, budget), Math.max(64, n / 8));
        while (line < n && count < n && left-- > 0) {
            // 页首不要落在段距空行上：那会看起来像顶部凭空多了一段白。
            // 空行既不属于本页也不属于下一页 —— 页顶从第一个非空行算起，两边都不多不少。
            while (line < n && blankLine(sl, src, line)) line++;
            if (line >= n) break;
            int top = sl.getLineTop(line);
            int last = line;
            // 至少一行；能在剩余高度里放下就继续加行
            while (last + 1 < n) {
                // 段距空行只是一条视觉分隔：落在页尾就是一块纯白，还白白吃掉一行的高度。
                // 往后找到第一行真正文：放得下就把它收进来（顺带吃掉这条空行），放不下就此收页。
                int nxt = last + 1;
                while (nxt < n && blankLine(sl, src, nxt)) nxt++;
                if (nxt >= n) break;
                if (sl.getLineBaseline(nxt) + glyphDesc - top > fitH) break;
                // 下一行如果是插图的起始行，要求整块图都放得下：否则图会被切成两页
                int ie = imgBlockEnd(sl, src, nxt);
                if (ie > nxt && sl.getLineBottom(Math.min(ie, n) - 1) - top > fitH) break;
                last = nxt;
            }
            aS[count] = line;
            aE[count] = last;
            aT[count] = top;
            count++;
            line = last + 1;
        }
        aLine = line;
        aCount = count;

        if (line < n && count < n) {
            // 还没切完：让出一帧，主线程先把手势与绘制做完
            post(() -> paginate(gen, sl, src, keep, w, h, PAGINATE_CHUNK));
            return;
        }

        // 切完：一次性整体切换。旧的分页位图（可能是在异步排版完成前按旧页表渲染的）一并作废
        invalidatePageBmps();
        layout = sl;
        syncLayoutPaintColor();   // 异步版面是 tp 副本构建的：装上先把字色拉齐（夜间发暗）
        rebuildBands();
        pStart = Arrays.copyOf(aS, count);
        pEnd = Arrays.copyOf(aE, count);
        pTop = Arrays.copyOf(aT, count);
        aS = new int[0];
        aE = new int[0];
        aT = new int[0];
        asyncGen = -1;
        laidW = getWidth();
        laidH = getHeight();
        laidPT = padT;
        laidPB = padB;
        page = pendingLast ? Math.max(0, count - 1) : pageOfOffset(keep);
        if (page >= count) page = Math.max(0, count - 1);
        // 滚动模式：重排后回到该页页首（与网页版「按页号近似恢复」一致，不回到章首）
        if (scrollMode) scrollPx = count > 0 ? pTop[Math.max(0, Math.min(page, count - 1))] : 0;
        pendingOffset = -1;
        typesetting = false;
        // 记下这次的结果：下次同参重排（重进本章、字号改回、旋转回来）直接复用页表
        try {
            PageCache pc = new PageCache();
            pc.key = cacheKey(w, h);
            pc.layout = sl;
            pc.text = text;
            pc.s = pStart;
            pc.e = pEnd;
            pc.t = pTop;
            gPut(pc.key, pc);
        } catch (Throwable ignore) {
            /* 缓存只影响速度，失败就当没缓存 */
        }
        notifyPage();
        if (budget != Integer.MAX_VALUE) invalidate(); // 异步：旧帧还画着上一章，主动刷一次
        // 排完就先画好第一帧，再把下一页位图备好（大章首屏之后立刻翻页也不顿）
        if (!scrollMode) {
            removeCallbacks(prefetchBmpTask);
            postDelayed(prefetchBmpTask, 140);
        }
    }

    /**
     * 局部分页：把一页页的起止行算出来，**不碰任何实例显示字段**（blocks/text/layout/
     * pStart/imgSlots 都不动），可在后台线程跑。供 {@link #precacheChapter} 把相邻章提前
     * 排版落缓存用 —— 与 {@link #paginate} 的切片算法逐行一致，只是结果交给调用方而非写屏。
     */
    private int[][] computePages(StaticLayout sl, CharSequence src, int w, int h) {
        int n = sl.getLineCount();
        if (n <= 0) return null;
        int[] aS = new int[n], aE = new int[n], aT = new int[n];
        int count = 0, line = 0;
        // 底部只留 1dp 安全边（与 paginate 同源，避免末行被裁）
        int fitH = h - Math.round(dp(1));
        int glyphDesc = Math.round(tp.getFontMetrics().descent);
        while (line < n) {
            // 页首不要落在段距空行上（与 paginate 同源）
            while (line < n && blankLine(sl, src, line)) line++;
            if (line >= n) break;
            int top = sl.getLineTop(line);
            int last = line;
            while (last + 1 < n) {
                int nxt = last + 1;
                while (nxt < n && blankLine(sl, src, nxt)) nxt++;
                if (nxt >= n) break;
                if (sl.getLineBaseline(nxt) + glyphDesc - top > fitH) break;
                int ie = imgBlockEnd(sl, src, nxt);
                if (ie > nxt && sl.getLineBottom(Math.min(ie, n) - 1) - top > fitH) break;
                last = nxt;
            }
            aS[count] = line;
            aE[count] = last;
            aT[count] = top;
            count++;
            line = last + 1;
        }
        if (count == 0) return null;
        return new int[][]{Arrays.copyOf(aS, count), Arrays.copyOf(aE, count), Arrays.copyOf(aT, count)};
    }

    /**
     * 跨章预热排版：把相邻章（已取到内存的正文块）提前 typeset + 分页并落进全局页表缓存
     * {@code G_CACHE}，跨章翻页时 {@link #relayout} 直接 {@code gGet} 命中、零现场排版，
     * 对面不再空白 / 延迟出现。
     *
     * <p>与 {@link #relayout} 的根本区别：全程不碰实例显示字段（blocks/text/layout/pStart/
     * imgSlots 一律不动），分页在后台线程用 {@link #computePages} 的局部分组算，结果只
     * {@code gPut} 进缓存。内存由 {@code G_CACHE} 的 LRU 字符上限托管，不会无限膨胀；
     * 缓存键走 {@link #cacheKeyFor} 与 relayout 同源，排版参数一变即自动不命中。
     *
     * <p>只对「隐藏图片」模式生效：该模式下插图不占位、{@link #buildContent} 不会触发另一章的
     * 图片解码，无副作用；显示图片时跳过（避免为还没看的章提前解码图片，且占位尺寸不定会导致
     * 键对不上），跨章仍走原来的实时排版。
     */
    public void precacheChapter(List<Paras.Block> blocks, String title) {
        if (blocks == null || blocks.isEmpty()) return;
        if (!hideImages) return;            // 显示图片时不预热（见上方说明）
        if (getWidth() <= 0 || getHeight() <= 0) return;
        if (typesetting || animDir != 0 || turning || springBack != null) return; // 当前章忙就不抢
        if (crossDir != 0 || crossPendingDir != 0) return;
        int w = getWidth() - padL - padR;
        int h = getHeight() - padT - padB;
        if (w <= 0 || h <= 0) return;
        // 用临时 imgSlots，绝不动实例的 imgSlots（当前章正在显示 / 加载的图片）
        List<ImgSlot> tmp = new ArrayList<>();
        CharSequence body = buildContent(blocks, title, w, tmp);
        if (body == null || body.length() == 0) return;
        String key = cacheKeyFor(body, w, h);
        if (gGet(key) != null) return; // 已经缓存放着，别重复排版
        final int fw = w + (trackPx > 0f ? 1 : 0);
        final TextPaint bp = new TextPaint(tp);
        bp.setLetterSpacing(tp.getLetterSpacing());
        final CharSequence fbody = body;
        final int fh = h;
        // 构建 StaticLayout（大章耗时大头）挪到后台：主线程在等待期间照常响应手势与绘制
        new Thread(() -> {
            Typeset ts;
            try {
                ts = typeset(fbody, bp, fw, lineAdd, w, justifyHang);
            } catch (Throwable t) {
                return; // 极端字号 / 字体下构建失败：放弃预热，跨章时再现场排
            }
            if (ts == null) return;
            int[][] pages = computePages(ts.layout, ts.text, w, fh);
            if (pages == null) return;
            PageCache pc = new PageCache();
            pc.key = key;
            pc.layout = ts.layout;
            pc.text = ts.text;
            pc.s = pages[0];
            pc.e = pages[1];
            pc.t = pages[2];
            gPut(key, pc);
        }, "modou-precache").start();
    }

    /**
     * StaticLayout 的构建参数集中在这里，同步与后台两条路共用同一套取值。
     *
     * <p><b>不再交给系统的 inter-word justifier。</b>它是「画的时候拉」：draw 期把每行
     * 均摊拉满，而 {@code getPrimaryHorizontal / getOffsetForHorizontal} 仍返回没拉伸的
     * 位置。同一行越靠右，画出来的字和算出来的坐标差得越多（一行末尾能差半个字），
     * 于是选区高亮「框住半个字」、长按取字「差一个字」—— 这不是补几个像素能抹平的，
     * 是两套坐标本身就不是一套。
     *
     * <p>现在两端对齐改由 {@link #justifyByTrack} 逐行挂字距 span：拉伸发生在<b>测量</b>期，
     * 字的位置、行宽、选区命中、搜索底纹全部出自同一份 StaticLayout，天然一致。
     */
    private static StaticLayout buildLayout(CharSequence src, TextPaint paint,
                                            int layoutW, float add) {
        // 对齐方式恒为 ALIGN_NORMAL：Android 的 Layout.Alignment 里**根本没有**
        // ALIGN_JUSTIFIED（以前用 valueOf 取，必然抛异常再吞掉，所以那个开关一直是失效的）
        return StaticLayout.Builder.obtain(src, 0, src.length(), paint, layoutW)
                .setLineSpacing(add, 1f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build();
    }

    /* ---------------- 中文排版：两端对齐 + 标点避头尾 ---------------- */

    /**
     * 行首禁则。
     *
     * 这些标点绝不能出现在一行的最开头：句读、收尾的括号引号、以及它们的全角变体。
     * 断行时若真把它们挤到了下一行行首，就把上一行压回去一个字，把它们带回上一行
     * （中文排版里的「追い込み」/ 标点挤压）。
     */
    private static final String NO_LINE_START =
            "，。、；：？！）〕】》」』”’,.;:?!)]}>»";

    /** 行尾禁则：这些标点绝不能留在一行的最末尾（开引号/开括号后必须还有字） */
    private static final String NO_LINE_END = "（〔【《「『“‘([{«";

    /** 一次排版的产物：版面 + 真正参与排版的文本（可能挂着按行缩放 span） */
    private static final class Typeset {
        final StaticLayout layout;
        final CharSequence text;

        Typeset(StaticLayout layout, CharSequence text) {
            this.layout = layout;
            this.text = text;
        }
    }

    /**
     * 整行字距微调：一行挂一个。
     *
     * 两端对齐为什么用「字距」而不是「字宽」（ScaleXSpan）：汉字是方块字，横向缩放
     * 会把字拉扁/拉胖，一眼就假；调字距只是把行内每个字之间多出的那点空隙均摊掉，
     * 字形完全不变。观感和纸书/微信读书一致。
     *
     * 走 MetricAffectingSpan 这条路的另一层好处：字符位置、行框、选区命中、搜索高亮
     * 全都还是 StaticLayout 自己算的，外面那些 getPrimaryHorizontal / getOffsetForHorizontal
     * 一行都不用改。
     */
    private static final class TrackSpan extends android.text.style.MetricAffectingSpan {
        private final float em;

        TrackSpan(float em) {
            this.em = em;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setLetterSpacing(em);
        }

        @Override
        public void updateMeasureState(TextPaint tp) {
            tp.setLetterSpacing(em);
        }
    }

    /**
     * 排版入口。
     *
     * <p>两端对齐交给 {@link #justifyByTrack}：逐行挂字距 span，让 StaticLayout 在
     * <b>测量期</b>就把字拉开。这样版面里算出来的位置 == 屏幕上画出来的位置，
     * 选区、手柄、长按取字、搜索底纹共用同一份坐标，不需要任何补偿。
     */
    private Typeset typeset(CharSequence src, TextPaint paint, int layoutW, float add,
                            int avail, boolean hang) {
        // 统一拷一份可写副本：span 要挂在这份上，绝不能改动外面还在用的原文本
        SpannableStringBuilder base = new SpannableStringBuilder(src == null ? "" : src);
        // 上一版排版留下的行字距必须先摘掉：重排（改字号/行距/边距）时 text 里还带着旧几何
        // 的字距，不摘的话第一遍量的是「旧拉伸」，断行与宽度全跟着旧参数走
        for (TrackSpan old : base.getSpans(0, base.length(), TrackSpan.class)) {
            base.removeSpan(old);
        }
        StaticLayout first = buildLayout(base, paint, layoutW, add);
        // 两端对齐始终开启：逐行挂字距 span，让 StaticLayout 在测量期就把字拉开，
        // 版面坐标 == 屏幕坐标，选区/长按取字/搜索底纹共用一份（见 justifyByTrack）。
        return justifyByTrack(first, base, paint, layoutW, add, avail, hang);
    }

    /**
     * 两端对齐（真正的 justify）：断行由我们自己算，不再在 StaticLayout 的断行结果上打补丁。
     *
     * <p>流程：① 用无字距版面（first）的实测坐标逐字符校准宽度表（与 StaticLayout 同源，
     * 不做任何测量假设）；② 按宽度表逐字断行，应用中文排版规则 —— 避头（行首禁则标点
     * 把上一行末字拉下来）、避尾（开引号开括号不能收行尾）、行尾句读压缩（句读收进本行、
     * 压掉它的多余 advance，右缘仍贴版心）；③ 按每行真实的字距实例数把剩余空白均摊成
     * 字距挂 span（成对标点首字符不挂、拉丁单词内部不挂，分母同步少一个，账是平的），
     * 段末行若字数过少也不拉；④ <b>实测闭环（迭代）</b>：对最终版面逐行 getLineWidth 量真实
     * 宽度，残差按实例数折算回字距再挂 —— 无论上游哪一环（字宽校准、ROM 字形、标点度量）
     * 有误差，行行强制收敛到版心。上限 em≤1.2（极短行也撑满）、下限 em≥-0.30（只消测量
     * 残差），残差 ≤0.75px 视为已贴齐。最多 3 轮，无变化即停。
     *
     * <p>断行为什么能「锁死」：终版版面按 <b>版心宽度</b>（buildW = column，而非版心+1）
     * 构建，每行拉伸后总宽恒为 column ≤ buildW，StaticLayout 没有多余 1px 可塞下一个字符，
     * 断行必然与我们的行表一致 —— 每一行都能匹配上、都能被修正，不再有「个别行整行没挂
     * 字距」。段末行且字数过少（≤3 字）仍保持左对齐，避免一个巨大空档。
     *
     * <p>为什么必须走 span、不能交给系统 justifier：系统 justifier 只在 draw 里拉伸，
     * Layout 的度量仍是没拉伸的宽度 —— 「画在哪」和「算在哪」是两套坐标。挂 span 则
     * 字距在测量期就生效，选区、长按取字、搜索底纹共用同一份坐标。
     */
    private Typeset justifyByTrack(StaticLayout first, CharSequence src, TextPaint paint,
                                   int layoutW, float add, int avail, boolean hang) {
        final float column = Math.max(1f, (float) avail - 1f);
        final int buildW = (int) Math.ceil(column);
        final float px = Math.max(1f, textSizePx);
        final int len = src.length();
        if (len <= 0) return new Typeset(first, src);

        // ① 字符宽度表：逐字 measure，再用无字距版面校准。
        final float[] cw = new float[len];
        measureChars(src, paint, cw);
        calibChars(first, src, cw);

        // ② 自控断行：避头尾 + 行尾句读压缩 → 每行 [start, end, flags]（ kinsoku 断点）
        final int[][] rows = new int[len + 2][];
        int n = 0;
        int ps = 0;
        while (ps < len) {
            int pe = ps;
            while (pe < len && src.charAt(pe) != '\n') pe++;
            // 标题（RelativeSizeSpan）、插图（\uFFFC）、空行：交给 StaticLayout，不参与 justify
            if (pe > ps && !skipJustifyPara(src, ps, pe)) {
                n = breakPara(src, ps, pe, cw, column, px, rows, n, hang);
            }
            ps = pe + 1;
        }
        if (n == 0) return new Typeset(first, src);

        // ③ 强制断行：在「内部」断点（段末行之后本就有 '\n'，不重复插）插入 '\n'，
        //    让 StaticLayout 无法再重断 —— 行表与版面行逐行严格对应，拉伸不再有匹配失败，
        //    每一行都能被精确拉伸到版心（根治个别行残留 30~150px / 整页参差）。
        //    SpannableStringBuilder.insert 会自动平移已有 span 的起止，标题/插图 span 不受影响。
        final SpannableStringBuilder fb = new SpannableStringBuilder(src);
        final int[][] fbRows = new int[n][3];
        {
            int shift = 0;
            for (int i = 0; i < n; i++) {
                final int s = rows[i][0] + shift;
                final int e = rows[i][1] + shift;
                fbRows[i][0] = s;
                fbRows[i][1] = e;
                fbRows[i][2] = rows[i][2];
                // 段末行之后本就有 '\n'（段落分隔），不重复插；只有内部断点才插强制换行
                if (i < n - 1 && (rows[i][2] & FLAG_PARAEND) == 0) {
                    fb.insert(e, "\n");
                    shift++;
                }
            }
        }

        // ④ 在 fb 上重新校准宽度（含强制换行后的行结构），得到与 fb 同坐标系的宽度表
        final float[] fcw = new float[fb.length()];
        measureChars(fb, paint, fcw);
        final StaticLayout firstFb = buildLayout(fb, paint, layoutW, add);  // 无字距
        calibChars(firstFb, fb, fcw);

        // ⑤ 逐行把剩余空白均摊成字距挂上（段末行不拉）；行尾句读压缩保留
        final float[] emArr = new float[fb.length()];
        final float[] rowEm = new float[n];
        for (int i = 0; i < n; i++) {
            final int s = fbRows[i][0], e = fbRows[i][1], flags = fbRows[i][2];
            if ((flags & FLAG_PARAEND) != 0) continue;
            final float wn = rowNaturalWidth(fcw, new int[]{s, e});
            float compress = 0f;
            if ((flags & FLAG_SQUEEZE) != 0) {
                compress = Math.max(0f, Math.min(0.5f * px, wn - column));
            }
            final int gaps = rowGapCount(fb, new int[]{s, e});
            if (gaps < 1) continue;
            float em = (column - wn + compress) / (gaps * px);
            if (em < 0f) em = 0f;                                   // 压缩行断行时已保证不超半字，兜个底
            if (em < 0.001f && compress < 0.001f) continue;
            rowEm[i] = em;
            applyEmToRange(emArr, fb, s, e, em);
            if (compress > 0.001f) emArr[e - 1] = -compress / px;  // 行尾句读压缩：负字距只挂句读
        }

        // ⑥ 实测反馈：断点已强制锁死，逐行量真实宽度，残差折算回字距再修（必收敛）。
        //    上限 em≤1.2（极短行也尽量撑满，宁可稍松也不留白）、下限 em≥-0.30（只消测量残差）。
        //    行表与版面行通过「行首偏移」对应：标题 / 段间空行在行表之前或之间，不能按序号对应。
        final int[] rowOfStart = new int[fb.length() + 1];
        for (int i = 0; i < n; i++) rowOfStart[fbRows[i][0]] = i + 1;  // 0=未命中，i+1=行表序号
        StaticLayout sl = buildLayout(boxFromEm(fb, emArr), paint, buildW, add);
        for (int pass = 0; pass < 4; pass++) {
            int changed = 0;
            final int lc = sl.getLineCount();
            for (int L = 0; L < lc; L++) {
                final int s = sl.getLineStart(L);
                final int e = sl.getLineEnd(L);
                final int ri = rowOfStart[s] - 1;
                if (ri < 0 || fbRows[ri][1] != e) continue;   // 标题 / 空行 / 对应不上
                final int flags = fbRows[ri][2];
                if ((flags & FLAG_PARAEND) != 0) continue;
                final int gaps = rowGapCount(fb, new int[]{s, e});
                if (gaps < 1) continue;
                final float residual = column - sl.getLineWidth(L);
                if (Math.abs(residual) <= 0.5f) continue;          // 亚像素级：已经贴齐
                final float wn = rowNaturalWidth(fcw, new int[]{s, e});
                float em2 = (column - wn) / (gaps * px);
                if (em2 < 0f) em2 = 0f;
                if (em2 > 1.2f) em2 = 1.2f;
                if (Math.abs(em2 - rowEm[ri]) < 0.0005f) continue;
                rowEm[ri] = em2;
                applyEmToRange(emArr, fb, s, e, em2);
                final float compress = ((flags & FLAG_SQUEEZE) != 0)
                        ? Math.max(0f, Math.min(0.5f * px, wn - column)) : 0f;
                emArr[e - 1] = compress > 0.001f ? -compress / px : 0f;
                changed++;
            }
            if (changed == 0) break;
            sl = buildLayout(boxFromEm(fb, emArr), paint, buildW, add);
        }
        return new Typeset(sl, boxFromEm(fb, emArr));
    }

    /** 把字距数组落到文本上：清除旧 TrackSpan，按 emArr 重挂（emArr[j]!=0 即对该字符挂字距） */
    private static SpannableStringBuilder boxFromEm(CharSequence base, float[] emArr) {
        final SpannableStringBuilder box = new SpannableStringBuilder(base);
        for (TrackSpan old : box.getSpans(0, box.length(), TrackSpan.class)) {
            box.removeSpan(old);
        }
        for (int j = 0; j < box.length() && j < emArr.length; j++) {
            if (emArr[j] != 0f) {
                box.setSpan(new TrackSpan(emArr[j]), j, j + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return box;
    }

    /** 把一行（[s,e) 可见正文，末字不挂）的字距 em 写入 emArr 的可拉伸空档位 */
    private static void applyEmToRange(float[] emArr, CharSequence src, int s, int e, float em) {
        // 末字（e-1）不挂：letterSpacing 加在字符 advance 之后，末字挂了行尾拖空隙、
        // 右缘永远差一截。成对标点 ——/…… 首字符不挂（避免劈裂）；拉丁单词内部不挂
        // （英文保持自然字距，拉伸只发生在词间空格与中日韩字之间）。可拉伸空档数量与
        // rowGapCount 严格一致 —— 分母与实例数永远对得上，账不会平白差出一个空距。
        for (int j = s; j < e - 1; j++) {
            final char c = src.charAt(j);
            final char c2 = src.charAt(j + 1);
            if ((c == '—' || c == '…') && c2 == c) continue;        // 成对标点首字符
            if (isWordCh(c) && isWordCh(c2)) continue;               // 拉丁词内部
            emArr[j] = em;
        }
    }

    /** 一行有多少个真实字距实例：倒数第二字之前每字一个，成对标点首字符、拉丁单词内部除外 */
    private static int rowGapCount(CharSequence src, int[] row) {
        final int s = row[0], e = row[1];
        int gaps = 0;
        for (int j = s; j < e - 1; j++) {
            final char c = src.charAt(j);
            if ((c == '—' || c == '…') && j + 1 < e && src.charAt(j + 1) == c) continue;
            // 拉丁字母/数字单词内部不打散：英文单词的字距保持自然，拉伸只发生在词间空格
            // 与中日韩字之间，混排时英文才不会被拉成一串松垮的字母
            if (isWordCh(c) && isWordCh(src.charAt(j + 1))) continue;
            gaps++;
        }
        return gaps;
    }

    /** 这行的自然总宽（cw 之和，代理对后半宽度并入前一字） */
    private static float rowNaturalWidth(float[] cw, int[] row) {
        float wn = 0f;
        for (int j = row[0]; j < row[1]; j++) wn += (cw[j] < 0 ? 0f : cw[j]);
        return wn;
    }

    /** 行表 flag：本行末尾收进了一个句读（要做 advance 压缩） */
    private static final int FLAG_SQUEEZE = 1;
    /** 行表 flag：段末行（保持左对齐，不参与拉伸） */
    private static final int FLAG_PARAEND = 2;

    /** 行尾可以收进来做压缩的句读（行首禁则的子集，压缩后仍贴版心） */
    private static final String SQUEEZE_PUNCT = "，。、；：？！,.:;?!";

    private static boolean isHangPunct(char c) {
        return SQUEEZE_PUNCT.indexOf(c) >= 0;
    }

    private static boolean isWordCh(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** 段内逐字断行：避头（禁则标点不上行首）+ 避尾（开引号不收行尾）+ 行尾句读压缩 */
    private static int breakPara(CharSequence src, int ps, int pe, float[] cw,
                                 float column, float px, int[][] out, int n, boolean hang) {
        int ls = ps;
        float x = 0f;
        int i = ps;
        while (i < pe) {
            final float w = cw[i] < 0 ? 0f : cw[i];   // 代理对后半：宽度并进前一字
            if (x + w <= column) {
                x += w;
                i++;
                continue;
            }
            // i 装不下 → 本行候选 [ls, i)，决定真正的断点
            int cut = i;
            boolean squeeze = false;
            // 行尾句读压缩（仅压缩模式）：句读本身收进本行，超出量 ≤ 半个字宽才成立。
            // 悬挂模式（hang）下不做压缩：标点按全宽落版心、不挤半字。
            if (!hang && isHangPunct(src.charAt(i)) && (x + w - column) <= 0.5f * px) {
                x += w;
                i++;
                cut = i;
                squeeze = true;
            }
            // 避头：下一行行首不能是句读/收口标点 → 把本行末字拉下去陪着它
            int guard = 0;
            while (cut > ls && cut < pe && NO_LINE_START.indexOf(src.charAt(cut)) >= 0
                    && guard++ < 3) {
                cut--;
                squeeze = false;
            }
            // 避尾：本行末字不能是开引号/开括号（后面必须还有字）
            guard = 0;
            while (cut > ls && NO_LINE_END.indexOf(src.charAt(cut - 1)) >= 0 && guard++ < 3) {
                cut--;
                squeeze = false;
            }
            // 英文/数字单词不从中间断
            guard = 0;
            while (cut > ls && cut < pe && isWordCh(src.charAt(cut - 1)) && isWordCh(src.charAt(cut))
                    && guard++ < 12) {
                cut--;
            }
            // 代理对后半不能当行首：退到对的前半
            while (cut > ls && cut < pe && Character.isLowSurrogate(src.charAt(cut))) {
                cut--;
            }
            if (cut <= ls) cut = Math.min(pe, ls + 1);   // 兜底：一行至少一个字
            out[n++] = new int[]{ls, cut, squeeze ? FLAG_SQUEEZE : 0};
            // 下一行从 cut 重新起算，宽度累到当前探测位 i
            ls = cut;
            x = 0f;
            for (int j = cut; j < i; j++) {
                if (cw[j] > 0) x += cw[j];
            }
        }
        if (ls < pe) out[n++] = new int[]{ls, pe, FLAG_PARAEND};
        return n;
    }

    /** 这一段是否不参与 justify：标题（RelativeSizeSpan）、插图（\uFFFC）、纯空白 */
    private static boolean skipJustifyPara(CharSequence src, int ps, int pe) {
        for (int j = ps; j < pe; j++) {
            if (src.charAt(j) == '\uFFFC') return true;
        }
        if (src instanceof Spanned
                && ((Spanned) src).getSpans(ps, pe, RelativeSizeSpan.class).length > 0) {
            return true;
        }
        return isBlankRun(src, ps, pe);
    }

    /** 逐字符自然宽度：代理对整体测量，后半标记 -1 */
    private static void measureChars(CharSequence src, TextPaint paint, float[] cw) {
        final int len = src.length();
        int i = 0;
        while (i < len) {
            final char c = src.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < len) {
                cw[i] = paint.measureText(src, i, i + 2);
                cw[i + 1] = -1f;
                i += 2;
            } else {
                cw[i] = paint.measureText(src, i, i + 1);
                i++;
            }
        }
    }

    /**
     * 用无字距版面的实测坐标校准字符宽：同一行内相邻两字的 x 差就是前一字符的
     * advance。校准后的宽度与 StaticLayout 的断行测量同源，我们的贪心断行
     * 才不会和它的贪心断行打架。行末字符（x 差跨行取不到）保留 measure 估计值。
     */
    private static void calibChars(StaticLayout sl, CharSequence src, float[] cw) {
        final int n = sl.getLineCount();
        for (int L = 0; L < n; L++) {
            final int s = sl.getLineStart(L);
            final int e = sl.getLineEnd(L);
            for (int j = s; j + 1 < e; j++) {
                final char c = src.charAt(j);
                if (c == '\n' || Character.isSurrogate(c)) continue;
                try {
                    final float x1 = sl.getPrimaryHorizontal(j);
                    final float x2 = sl.getPrimaryHorizontal(j + 1);
                    if (x2 > x1) cw[j] = x2 - x1;
                } catch (Exception ignore) {
                    /* 越界等异常：保留 measure 值 */
                }
            }
        }
    }

    /** 这一段（不含换行）是不是纯空白/缩进 */
    private static boolean isBlankRun(CharSequence src, int s, int e) {
        for (int i = s; i < e; i++) {
            if (!isBlankCh(src.charAt(i))) return false;
        }
        return true;
    }

    /** 是不是章标题那一行（挂过 RelativeSizeSpan）：标题居中、字号不同，不参与补白 */
    private static boolean isTitleLine(CharSequence src, int s, int e) {
        return src instanceof Spanned
                && ((Spanned) src).getSpans(s, e, RelativeSizeSpan.class).length > 0;
    }


    /** 字符偏移 → 页码（书签刻度用，故包级可见） */
    int pageOfOffset(int off) {
        if (layout == null || pStart.length == 0) return 0;
        for (int i = 0; i < pStart.length; i++) {
            int e = (i + 1 < pStart.length) ? layout.getLineStart(pStart[i + 1]) : text.length();
            if (off < e) return i;
        }
        return pStart.length - 1;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
    }

    /* ---------------- 坐标 ↔ 偏移 ---------------- */

    /**
     * 手指坐标 → 本页字符偏移。
     *
     * y 超出页底就夹到最后一行、x 超出右边就夹到行尾 —— 和系统阅读器一致：
     * 手指滑出正文区域时选到边界，而不是「没有反应」。
     *
     * @return -1 = 当前无法定位（无排版 / 不在页内）
     */
    private int offsetAt(float x, float y) {
        if (layout == null || pStart.length == 0) return -1;
        int pg = Math.max(0, Math.min(page, pStart.length - 1));
        float ly;
        int lineMin, lineMax;
        if (scrollMode) {
            // 滚动模式没有页边界：整章都是一个连续面
            ly = y - padT + scrollPx;
            lineMin = 0;
            lineMax = layout.getLineCount() - 1;
        } else {
            ly = y - padT + pTop[pg];
            lineMin = pStart[pg];
            lineMax = pEnd[pg];
        }
        int line = layout.getLineForVertical((int) Math.max(0f, Math.min(ly, layout.getHeight() - 1)));
        // 夹到本页的行范围：手指落在页底空白处也能锚到最后一行
        if (line < lineMin) line = lineMin;
        if (line > lineMax) line = lineMax;
        float lx = x - padL;
        int maxW = Math.max(1, getWidth() - padL - padR);
        lx = Math.max(0f, Math.min(lx, maxW));
        try {
            // 字距在测量期就生效，getOffsetForHorizontal 用的就是屏幕上那套坐标，
            // 不需要任何反向补偿（以前那段「扣两次」正是在补 justifier 的两套坐标）
            return layout.getOffsetForHorizontal(line, lx);
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isBlank(char ch) {
        return ch == ' ' || ch == '\u3000' || ch == '\n' || ch == '\r' || ch == '\t';
    }

    /**
     * 起点归一：落在换行/段首缩进上时，挪到第一个真字。
     *
     * <p>段首那两格全角空白也是字符：手指（或手柄）停在缩进里，端点就在空白上，
     * 高亮会多涂一块空方格 —— 看着像「选了不该选的东西」，复制出去也带一串空格。
     * 行中间的空格原样保留，只有「从行首到这一点全是空白」才挪。
     */
    private int snapA(int p) {
        if (layout == null) return p;
        final int n = text.length();
        int q = Math.max(0, Math.min(p, n - 1));
        for (int guard = 0; guard < 12 && q < n - 1; guard++) {
            final char ch = text.charAt(q);
            if (ch == '\n') { q++; continue; }      // 段落换行：锚到下一段行首
            if (!isBlank(ch)) break;
            final int ls = layout.getLineStart(layout.getLineForOffset(q));
            boolean leading = true;
            for (int i = ls; i < q; i++) {
                if (!isBlank(text.charAt(i))) { leading = false; break; }
            }
            if (!leading) break;                     // 行中间的空格：不动
            q++;
        }
        return q;
    }

    /** 终点归一（q 为不含端）：行尾换行、段首缩进都不进选区 */
    private int snapB(int p) {
        if (layout == null) return p;
        int q = Math.max(0, Math.min(p, text.length()));
        for (int guard = 0; guard < 12 && q > 1; guard++) {
            final char ch = text.charAt(q - 1);
            if (ch == '\n') { q--; continue; }
            if (!isBlank(ch)) break;
            final int ls = layout.getLineStart(layout.getLineForOffset(q - 1));
            boolean leading = true;
            for (int i = ls; i < q - 1; i++) {
                if (!isBlank(text.charAt(i))) { leading = false; break; }
            }
            if (!leading) break;
            q = ls;                                   // 整行还在缩进里：退到行首
        }
        return q;
    }

    /** 选区端点统一收口：夹进正文 → 挪开空白 → 保证非空 */
    private void normalizeSel() {
        final int n = text.length();
        if (n == 0) { selA = 0; selB = 0; return; }
        selA = snapA(Math.max(0, Math.min(selA, n - 1)));
        selB = snapB(Math.max(0, Math.min(selB, n)));
        if (selB <= selA) selB = Math.min(n, selA + 1);
    }

    /* ---------------- 手势 ---------------- */

    private void beginSel(float x, float y) {
        int off = offsetAt(x, y);
        if (off < 0) return;
        int n = text.length();
        if (n == 0) return;
        selA = Math.max(0, Math.min(off, n - 1));
        selB = Math.min(n, selA + 1);
        normalizeSel();
        anchor = selA;
        selecting = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        // 手指还按在屏幕上：先只画高亮，工具条等松手再弹（否则正好压在手指底下）
        if (host != null) host.onSelText(selectionText());
    }

    /** 长按后拖动：以 anchor 为不动端，手指方向决定扩哪边 */
    private void extendSel(float x, float y) {
        int off = offsetAt(x, y);
        if (off < 0) return;
        int n = text.length();
        if (n == 0 || anchor < 0) return;
        int oldA = selA, oldB = selB;
        if (off >= anchor) {
            // 末端用「手指落在哪个字界」而不是「再 +1 个字」：
            // 高亮的右沿必须正好在指尖下，多一点就是「多选了一个字」，
            // 少一点就是「拖到底还缺一个」，两种都被当成坐标不准。
            selA = anchor;
            selB = Math.min(n, off);
        } else {
            selA = Math.max(0, off);
            selB = Math.min(n, anchor + 1);
        }
        normalizeSel();
        // 每扩/缩一个字震一下：选到哪儿只有那两个小圆点知道，手上给个刻度感
        if (selA != oldA || selB != oldB) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        invalidate();
        if (host != null) host.onSelText(selectionText());
    }

    /**
     * 已有选区时按下屏幕：命中手柄拖那一端；按在选区里就就近拖（想多选、想少选都直接拖，
     * 不用先精确戳中那两个小圆点）；两者都没中返回 false，交给调用方清选区重来。
     *
     * 这一步是「选完就没法再调整」的根因修复：以前只要不是正好按在手柄上，
     * 一碰就把整段选区清掉，等于选中之后只剩「全选本页」和「取消」两条路。
     */
    private boolean grabSelection(float x, float y) {
        if (!hasSelection()) return false;
        if (nearHandle(x, y, selA, true)) {
            startHandleDrag(true);
            return true;
        }
        if (nearHandle(x, y, selB, false)) {
            startHandleDrag(false);
            return true;
        }
        RectF r = selRectView();
        if (r == null) return false;
        // 选区矩形按整段算，纵向可能只剩几像素的一行：上下各放宽 10dp 才好按
        if (x < r.left - dp(8) || x > r.right + dp(8)
                || y < r.top - dp(10) || y > r.bottom + dp(10)) return false;
        float[] ha = handlePos(selA, true);
        float[] hb = handlePos(selB, false);
        boolean nearA = ha == null ? true
                : hb == null ? false
                : Math.abs(y - ha[3]) <= Math.abs(y - hb[3]);
        startHandleDrag(nearA);
        return true;
    }

    private void startHandleDrag(boolean a) {
        draggingHandle = true;
        dragA = a;
        dragFromY = lastY;
        pressing = false;
        handleHidden = false;
        removeCallbacks(longPress);
        // 抓住手柄那一下先给个长震：确认「已经抓住这一端」，之后拖动才不会怀疑没按上
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    /** 拖手柄：只动对应那一端，不允许两端交叉 */
    private void dragHandleTo(float x, float y) {
        // 手指刚压上手柄时要有个「死区」：圆点画在字框外侧，指尖常落在字框下沿之外，
        // 直接拿原始 y 去 offsetAt，第一帧就会跳到下一行（手感是「一碰就乱跳」）。
        //
        // 但死区只能用「相对按下位置」来算，不能用「基线 ± 半行」去夹 y：
        // 行距 2.0 时半行还落在本行行框之内（行框把多出来的行距都装进去了），
        // 于是往下拖永远出不了这一行 —— 这就是「只能往上扩、往下拉不动」。
        // 现在只有按下后的前 10dp 不算纵向位移，越过就完全跟随手指，上下都通。
        float yy = y;
        int cur = dragA ? selA : Math.max(0, selB - 1);
        float[] hp = handlePos(cur, dragA);
        if (hp != null && Math.abs(y - dragFromY) < dp(10)) yy = hp[3];
        int off = offsetAt(x, yy);
        if (off < 0) return;
        int n = text.length();
        int oldA = selA, oldB = selB;
        if (dragA) {
            selA = Math.max(0, Math.min(off, Math.max(0, selB - 1)));
        } else {
            // 同上：终点就是指尖所在的字界，拖动时高亮边缘与手指严丝合缝
            selB = Math.min(n, off);
        }
        normalizeSel();
        if (selA != oldA || selB != oldB) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        invalidate();
        // 拖动过程中不回调 onSelRect：工具条若每帧跟着重新定位，就会一直压在手指底下
        // 拖着走。松手（ACTION_UP）时再定位一次；这里只在刚产生位移时收起它，
        // 单纯点一下选区不会把工具条闪掉。
        if (!handleHidden && host != null) {
            handleHidden = true;
            host.onSelRect(null);
        }
        if (host != null) host.onSelText(selectionText());
    }

    /**
     * 手柄热区：竖线本身只有 2dp 宽，按线判断根本摸不到，
     * 所以按「竖线左右各 22dp、字框上下各 10dp」的矩形算（含圆点那一截）。
     */
    private boolean nearHandle(float x, float y, int off, boolean start) {
        float[] p = handlePos(off, start);
        if (p == null) return false;
        return Math.abs(x - p[0]) <= dp(22)
                && y >= p[1] - dp(14) && y <= p[2] + dp(14);
    }

    /**
     * 手柄锚点（View 坐标）：{x, 字框顶, 字框底, 基线}。
     *
     * 纵坐标按**字框**（ascent..descent）而不是行框算：行距 1.9 倍时行框比字高出一大截，
     * 手柄按行框画会飘在半空、点也够不着字；按字框画，竖线正好齐字高。
     */
    private float[] handlePos(int off, boolean start) {
        if (layout == null || pStart.length == 0) return null;
        ensureBands();
        int at = start ? off : Math.max(0, off - 1);
        if (at < 0 || at >= text.length()) return null;
        int line = layout.getLineForOffset(at);
        // 手柄必须和字待在同一根竖线上：横坐标直接读版面（字距已在测量期生效）
        float x = start ? xOf(at) : endX(at, line);
        float dy = scrollMode ? -scrollPx : -topOf(Math.max(0, Math.min(page, pStart.length - 1)));
        float base = padT + layout.getLineBaseline(line) + dy;
        // 竖线高度直接取行字框缓存 —— 与高亮条同一份值，不可能再出现
        // 「蓝底到这儿、竖线还高出一截」的错位
        return new float[] { padL + x, padT + bandTopOf(line) + dy,
                padT + bandBotOf(line) + dy, base };
    }

    /**
     * 选区末端手柄的横坐标 = 最后一个字的**右缘**。
     *
     * 不能用 getSecondaryHorizontal(off-1)：LTR 段落里它返回的就是这个字的左缘
     * （与 getPrimaryHorizontal 一样），于是「只选一个字」时两端手柄叠在同一根竖线上，
     * 屏幕上只剩一根线，而且正好卡在两个字中间。改用「下一个字的左缘」，
     * 这个字若在行尾，则用行右缘。
     */
    private float endX(int at, int line) {
        if (at + 1 < text.length() && layout.getLineForOffset(at + 1) == line) {
            return xOf(at + 1);
        }
        // 行尾：必须取 getLineRight（行右缘）。getPrimaryHorizontal(getLineEnd(line)) 是错的 ——
        // 那个偏移已经属于下一行，返回的是下一行行首，整行选区会缩成一条缝。
        return lineRightOf(line);
    }

    private RectF selRectView() {
        if (!hasSelection() || layout == null || pStart.length == 0) return null;
        ensureBands();
        if (bandN == 0) return null;
        try {
            // selPath 只在这次里取「横向」范围：左右是准的（字宽由 layout 算），
            // 上下是行框（不准），所以纵向改用行字框缓存
            selPath.reset();
            layout.getSelectionPath(selA, selB, selPath);
            selPath.computeBounds(selBounds, true);
            float dy = scrollMode ? -scrollPx : -topOf(Math.max(0, Math.min(page, pStart.length - 1)));
            int l1 = Math.max(0, Math.min(layout.getLineForOffset(selA), bandN - 1));
            int l2 = Math.max(l1, Math.min(layout.getLineForOffset(Math.max(selA, selB - 1)), bandN - 1));
            float bandT = Float.MAX_VALUE, bandB = -Float.MAX_VALUE;
            for (int l = l1; l <= l2; l++) {
                bandT = Math.min(bandT, bandTopOf(l));
                bandB = Math.max(bandB, bandBotOf(l));
            }
            if (bandB <= bandT) return null;
            float top = bandT + padT + dy;
            float bottom = bandB + padT + dy;
            // 整章全选会跨页：矩形上下要夹在当前可视区内，否则工具条按负的 top 定位会飞出屏幕
            float clipTop = padT - dp(6);
            float clipBottom = getHeight() - padB + dp(3);
            if (bottom < clipTop || top > clipBottom) return null;
            top = Math.max(top, clipTop);
            bottom = Math.min(bottom, clipBottom);
            // 横向直接用选区路径的包围盒：它和绘制出自同一份版面，不能再叠加补偿
            float left = Math.max(padL, selBounds.left + padL);
            float right = Math.min(getWidth() - padR, selBounds.right + padL);
            return new RectF(left, top, right, bottom);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (scrollMode) return touchScroll(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 正在等相邻章（手指已经拖出去了）：这一小段时间内吞掉手势。
                // 否则手势会改写 turnDx、而画面正按 crossDx 停在半路，两者互相拉扯，
                // 表现就是「拖不动」或「松手后卡在空白处」。
                if (crossPendingDir != 0) return true;
                // 边缘安全区：吞掉整段手势（不翻页、不长按选词、不弹菜单）。
                // 这里必须 return true 而不是 super：交给系统会让事件继续冒泡给父容器。
                if (edgeGuard > 0
                        && (ev.getX() < edgeGuard || ev.getX() > getWidth() - edgeGuard)) {
                    pressing = false;
                    turning = false;
                    edgeDir = 0;
                    turnDx = 0f;
                    removeCallbacks(longPress);
                    vtrack.clear();   // 残留的旧采样会让下一次轻扫算出离谱的速度
                    return true;
                }
                // 翻页动画还没走完就再次触摸：直接落到目标页，别让动画和手势互相拉扯
                if (anim != null) anim.cancel();
                if (springBack != null) springBack.cancel();
                turnDx = 0f;
                vtrack.clear();
                vtrack.addMovement(ev);
                downX = lastX = ev.getX();
                downY = lastY = ev.getY();
                downAt = System.currentTimeMillis();
                pressing = true;
                turning = false;
                crossNoRetry = false;
                edgeDir = 0;
                draggingHandle = false;
                selDismissed = false;
                // 已有选区时：按在手柄 / 选区里 → 拖着调；按在别处 → 清选区重新开始
                if (hasSelection()) {
                    if (grabSelection(downX, downY)) return true;
                    clearSel();
                    selDismissed = true;
                }
                postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                vtrack.addMovement(ev);
                lastX = ev.getX();
                lastY = ev.getY();
                if (draggingHandle) {
                    dragHandleTo(lastX, lastY);
                    return true;
                }
                if (selecting) {
                    extendSel(lastX, lastY);
                    return true;
                }
                if (!pressing) return true;
                {
                    float dx = lastX - downX;
                    float dy = lastY - downY;
                    if (!turning) {
                        // 还没确定意图：先用 slop 判定是翻页还是点击 / 垂直滚动。
                        // 一旦确认是横向翻页，后面全程跟手、不再回退 —— 否则反向撤销时
                        // 手指轻微上下抖一下就会被下面的 else 打回，turnDx 冻在半路、
                        // 画面僵死到松手（即「滑不回去」的根因）。
                        if (Math.abs(dx) <= slop && Math.abs(dy) <= slop) return true;
                        removeCallbacks(longPress);
                        if (Math.abs(dy) > Math.abs(dx)) {
                            pressing = false; // 垂直滑动：放弃翻页
                            return true;
                        }
                        turning = true;
                        // 确定翻页：通知父布局别再抢事件，防止滑动中途被截断导致断触
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    // 已确认是横向翻页：每一帧都 100% 跟随手指位移，
                    // 反向滑动时 turnDx 正常递减，不再有 slop 死区、不再被 dy 截断。
                    boolean atEdge = false;
                    if (dx < 0 && page + 1 >= pStart.length) {
                        edgeDir = 1;
                        atEdge = true;
                    } else if (dx > 0 && page <= 0) {
                        edgeDir = -1;
                        atEdge = true;
                    } else {
                        edgeDir = 0;
                    }
                    turnDx = dx;
                    /* 章边界拖过阈值就先取相邻章，而不是等松手。
                     *
                     * 以前是松手才 onNeedChapter：整个拖动过程另一侧永远是空的，
                     * 用户看到的就是「翻页时对面一片空白」。提前取，内容常常在手指还没抬起时就排好了，
                     * 后面的拖动与松手动画都能直接画出来。 */
                    if (atEdge && !crossNoRetry && crossDir == 0 && crossPendingDir == 0
                            && Math.abs(dx) > getWidth() * 0.12f) {
                        startCrossChapter(edgeDir);
                    }
                    // 等章 / 新章就位期间画面由 crossDx 驱动（旧页快照 + 新章首页），
                    // 手指继续移动时要同步它，turnDx 归零避免两套位移打架
                    if (crossDir != 0 || crossPendingDir != 0) {
                        float w = Math.max(1, getWidth());
                        crossDx = Math.max(-w, Math.min(w, dx));
                        turnDx = 0f;
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPress);
                // 翻页手势结束：把事件权交还父布局（中途 disallow 只针对本次拖动）
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                boolean cancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (draggingHandle) {
                    draggingHandle = false;
                    if (host != null) host.onSelRect(selRectView());
                    return true;
                }
                if (selecting) {
                    selecting = false;
                    if (!cancel && host != null) host.onSelRect(selRectView());
                    return true;
                }
                if (turning) {
                    turning = false;
                    float dx = turnDx;
                    int ed = edgeDir;
                    edgeDir = 0;
                    // 相邻章已在拖动时预取到位：内容已经接管当前页（旧章只剩快照），
                    // 松手就滑完，不能再按位移阈值回弹 —— 回弹回来也无处可回。
                    if (crossDir != 0) {
                        startCrossAnim();
                        return true;
                    }
                    // 已请求、内容还没到：保持「等章」状态，到达后由 maybeStartCross 起动画
                    if (crossPendingDir != 0) return true;
                    if (!cancel) {
                        vtrack.addMovement(ev);
                        vtrack.computeCurrentVelocity(1000);
                        float vx = vtrack.getXVelocity();
                        // 阈值再收到 12%：这版仍有人觉得「要拖很远」，拇指一划的距离就该算数
                        float trigger = getWidth() * 0.12f;
                        float raw = ev.getX() - downX;
                        // 距离不够但甩得快（>600dp/s）也翻，短促轻扫不该被回弹掉
                        boolean fling = Math.abs(vx) > dp(600);
                        if (dx <= -trigger || (dx < -dp(16) && fling && vx < 0)) turn(1);
                        else if (dx >= trigger || (dx > dp(16) && fling && vx > 0)) turn(-1);
                        else if (ed != 0 && Math.abs(raw) > trigger) turn(ed); // 章边界：直接跨章
                        else settleBack();
                    } else {
                        settleBack();
                    }
                    return true;
                }
                if (pressing) {
                    pressing = false;
                    if (cancel) return true;
                    if (selDismissed) { // 这一下只是收起选区，别顺手翻页
                        selDismissed = false;
                        return true;
                    }
                    long dt = System.currentTimeMillis() - downAt;
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (dt < LONG_PRESS_MS && dx * dx + dy * dy < slop * slop) {
                        float w = getWidth();
                        float x = ev.getX();
                        // 抬手也判一次边缘：按下在里侧、松手蹭到边上不该误翻
                        if (edgeGuard > 0 && (x < edgeGuard || x > w - edgeGuard)) return true;
                        if (x < w * 0.32f) tapDo(tapLeft);
                        else if (x > w * 0.68f) tapDo(tapRight);
                        else if (host != null) host.onTapCenter();
                    }
                }
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    /** 滚动模式的触摸：只做上下滚 + 点击；左右拖拉不再翻页（无「页」可翻） */
    private boolean touchScroll(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 滚动模式只把「点击翻屏」挡掉：从边缘开始上下滑照样要能滚，
                // 否则贴边拿手机的人会觉得整块屏幕左边一条「死区」。
                edgeTap = edgeGuard > 0
                        && (ev.getX() < edgeGuard || ev.getX() > getWidth() - edgeGuard);
                if (flingAnim != null) {
                    flingAnim.cancel();
                    flingAnim = null;
                }
                downX = lastX = ev.getX();
                downY = lastY = ev.getY();
                downAt = System.currentTimeMillis();
                downScroll = scrollPx;
                edgeAtY = downY;
                pressing = true;
                scrolling = false;
                draggingHandle = false;
                edgeDir = 0;
                selDismissed = false;
                vtrack.clear();
                vtrack.addMovement(ev);
                if (hasSelection()) {
                    if (grabSelection(downX, downY)) return true;
                    clearSel();
                    selDismissed = true;
                }
                if (!edgeTap) postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                vtrack.addMovement(ev);
                lastX = ev.getX();
                lastY = ev.getY();
                if (draggingHandle) {
                    dragHandleTo(lastX, lastY);
                    return true;
                }
                if (selecting) {
                    extendSel(lastX, lastY);
                    return true;
                }
                if (!pressing) return true;
                {
                    float dy = lastY - downY;
                    if (Math.abs(dy) > slop) {
                        removeCallbacks(longPress);
                        scrolling = true;
                        setScroll(downScroll - dy);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPress);
                boolean cancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (draggingHandle) {
                    draggingHandle = false;
                    if (host != null) host.onSelRect(selRectView());
                    return true;
                }
                if (selecting) {
                    selecting = false;
                    if (!cancel && host != null) host.onSelRect(selRectView());
                    return true;
                }
                if (scrolling) {
                    scrolling = false;
                    if (cancel) return true;
                    if (selDismissed) {
                        selDismissed = false;
                        return true;
                    }
                    int ed = edgeDir;
                    float over = ed > 0 ? (edgeAtY - lastY) : ed < 0 ? (lastY - edgeAtY) : 0f;
                    // 顶到章首/章尾后继续拉够一段：交给宿主换章（与分页模式的跨章语义一致）
                    if (ed != 0 && over > dp(56)) {
                        if (host != null) host.onNeedChapter(ed);
                        return true;
                    }
                    vtrack.addMovement(ev);
                    vtrack.computeCurrentVelocity(1000);
                    flingVel = vtrack.getYVelocity();
                    if (Math.abs(flingVel) > dp(180)) {
                        // 手指向上滑是负速度，内容要往下走：方向取反
                        float to = scrollPx - flingVel * 0.16f;
                        animateScrollTo(to, (long) Math.min(560, Math.max(220, Math.abs(flingVel) * 0.3f)));
                    }
                    vtrack.clear();
                    return true;
                }
                if (pressing) {
                    pressing = false;
                    if (cancel) return true;
                    if (selDismissed) { // 这一下只是收起选区，别顺手翻页
                        selDismissed = false;
                        return true;
                    }
                    long dt = System.currentTimeMillis() - downAt;
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (dt < LONG_PRESS_MS && dx * dx + dy * dy < slop * slop) {
                        if (edgeTap) { // 边缘那一条：只当没点过
                            edgeTap = false;
                            return true;
                        }
                        float w = getWidth();
                        if (ev.getX() < w * 0.32f) scrollOneScreen(-1);
                        else if (ev.getX() > w * 0.68f) scrollOneScreen(1);
                        else if (host != null) host.onTapCenter();
                    }
                }
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    /** 执行一次点击动作（左右区域共用一套语义） */
    private void tapDo(int action) {
        if (action == 0) turn(-1);
        else if (action == 1) turn(1);
        else if (action == 2) {
            if (host != null) host.onTapCenter();
        }
        // 3 = 无动作：明确留给「只想选中文字/不想翻页」的用户
    }

    /** 翻页：同章内做短动画，到边界交给宿主去取相邻章 */
    /** 松手但没到翻页阈值：把跟手位移平滑收回原位。
     *  以前这里直接 invalidate()，画面上「啪」地跳回整屏，手感就是「抖一下、划不动」。 */
    private void settleBack() {
        if (Math.abs(turnDx) < 0.5f) {
            turnDx = 0f;
            invalidate();
            return;
        }
        if (springBack != null) springBack.cancel();
        final ValueAnimator va = ValueAnimator.ofFloat(turnDx, 0f);
        springBack = va;
        va.setDuration(150);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> {
            if (springBack != va) return;
            turnDx = (float) a.getAnimatedValue();
            invalidate();
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (springBack != va) return;
                springBack = null;
                turnDx = 0f;
                invalidate();
            }
        });
        va.start();
    }

    private void turn(int dir) {
        if (pStart.length == 0) return;
        pendingLast = false; // 用户真的翻页了：此后的重排按「当前页」恢复，不再钉末页
        if (scrollMode) {
            scrollOneScreen(dir);
            return;
        }
        if (crossDir != 0 || crossPendingDir != 0) return; // 换章途中不接新翻页
        int np = page + dir;
        if (np < 0) {
            startCrossChapter(-1);
            return;
        }
        if (np >= pStart.length) {
            startCrossChapter(1);
            return;
        }
        animOld = page;
        page = np;
        animDir = dir;
        // 正在翻页：别让空闲预渲染插进来抢同一帧
        removeCallbacks(prefetchBmpTask);
        float w = getWidth();
        /* 动画起点 = 「新页」当前的横向位置，不是手指位移。
         *
         * 跟手时画的是：旧页在 turnDx、新页在 turnDx ± w。松手后如果直接把 turnDx
         * 当成新页位置，新页会从小半个屏的地方起跳、旧页瞬间消失，中间露出一条底色 ——
         * 这就是「翻页闪一下」。所以跟手松手要从 turnDx + (dir>0 ? w : -w) 接着走。 */
        boolean fromDrag = Math.abs(turnDx) > dp(4);
        float end = dir > 0 ? w : -w;
        animDx = fromDrag ? turnDx + end : end;
        // 连点时上一段动画还没跑完：先掐掉，否则两个动画同时改 animDx 会抖
        if (anim != null) anim.cancel();
        ValueAnimator va = ValueAnimator.ofFloat(animDx, 0f);
        anim = va;
        // 剩下的路程越短，动画越短：松手时已经拖过大半屏的，不该再匀速滑 180ms
        int dur = fromDrag
                ? (int) Math.max(90, Math.min(170, 90 + 80 * Math.abs(animDx) / Math.max(1f, w)))
                : 180;
        va.setDuration(dur);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> {
            animDx = (float) a.getAnimatedValue();
            invalidate();
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (anim != animation) return; // 已被新动画接管
                animDir = 0;
                animDx = 0f;
                turnDx = 0f;
                animOld = -1;
                anim = null;
                invalidate();
                // 落定后把下一页提前画好：下一次翻页就不会在主线程临时栅格化
                removeCallbacks(prefetchBmpTask);
                postDelayed(prefetchBmpTask, 90);
            }
        });
        // 翻页预热：进动画前把「旧页 + 新页」都栅格化进槽，第一帧直接贴现成位图，
        // 不在主线程现场画整章 —— 刚进章 / 跳转后第一下翻页，目标页是冷的，这一步最明显。
        pageBitmap(animOld);
        pageBitmap(page);
        va.start();
        // 翻页前若还留着选区，工具条会飘在新页上：一并收掉
        if (selA >= 0) {
            clearSelInternal();
            if (host != null) {
                host.onSelRect(null);
                host.onSelectionClosed();
            }
        }
        invalidate();
        notifyPage();
    }

    /* ---------------- 绘制 ---------------- */

    /* ---------------- 跨章翻页动画 ---------------- */

    /**
     * 跨章翻页：先把当前页拍成整屏快照，再请宿主去取上一/下一章。
     *
     * 不这么做的话，章边界那一翻是「内容整体替换」——同章翻页是 180ms 横向平移，
     * 跨章却是瞬间换页，手感完全对不上。现在快照留在原位，新章正文到位后
     * 按同一套平移动画把旧页滑出去、新章首页滑进来。
     */
    private void startCrossChapter(int dir) {
        if (host == null) return;
        if (crossDir != 0 || crossPendingDir != 0) return;
        if (layout != null && pStart.length > 0 && getWidth() > 0 && getHeight() > 0) {
            try {
                // 快照只用于翻章动画：RGB_565 省一半内存（2K 屏约 18MB → 9MB），
                // 动画只持续几百毫秒，肉眼几乎看不出色差。
                Bitmap bm = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.RGB_565);
                Canvas bc = new Canvas(bm);
                bc.drawColor(theme.bg);
                // 快照必须是「原位」的整页：手指拖出来的位移由绘制期的 crossDx 施加。
                // 0.9.72 之前章边界不跟手（turnDx 恒 0），这里曾把 turnDx 烘进快照；
                // 现在若两边都加，会把位移算两遍 —— 松手瞬间快照突然多滑出去半个屏。
                drawPage(bc, page);
                drawHeadFoot(bc);
                if (crossBmp != null) crossBmp.recycle();
                crossBmp = bm;
            } catch (Throwable t) {
                crossBmp = null; // 内存不够就退回「直接换页」，不能因此崩掉
            }
        }
        // 跟手位移原样带进跨章动画；夹到一屏内，拖过头也不会让旧页停在屏幕外
        float w0 = Math.max(1, getWidth());
        crossDx = Math.max(-w0, Math.min(w0, turnDx));
        crossPendingDir = dir;
        // 注意：这里不能清 turning。拖动中预取（手指还按着）时清了它，
        // 后续 MOVE 会重新走「刚开始拖」的分支，ACTION_UP 还会被当成点击。
        turnDx = 0f;
        if (springBack != null) {
            springBack.cancel();
            springBack = null;
        }
        removeCallbacks(crossTimeout);
        postDelayed(crossTimeout, 6000); // 加载失败/超时兜底：别一直卡在「等章」
        // turnDx 刚被清零、crossDx 接手：必须重绘一帧，否则画面会跳回原位再等加载
        invalidate();
        host.onNeedChapter(dir);
    }

    private final Runnable crossTimeout = new Runnable() {
        @Override
        public void run() {
            if (crossPendingDir != 0 && crossDir == 0) dropCross();
        }
    };

    /**
     * 新章正文到位：开始滑入动画（setChapter / setBlocks 末尾调用）。
     * 注意宿主是「setChapter 之后再 setTitle」两步，setTitle 会再重排一次；
     * 所以这里只把绘制切到跨章模式（这一帧画面不变、不闪），动画放到下一轮消息里起，
     * 等两次排版都落定再滑，否则排到一半被重排会跳一下。
     */
    private void maybeStartCross() {
        if (crossPendingDir == 0) return;
        removeCallbacks(crossTimeout);
        if (layout == null || pStart.length == 0 || getWidth() <= 0) {
            dropCross();
            return;
        }
        crossDir = crossPendingDir;
        crossPendingDir = 0;
        removeCallbacks(crossRun);
        // 手指还按着（拖动中预取到位）：先把绘制切到「旧页快照 + 新章首页」，
        // 让新章跟着手指走；现在就起动画会和手势抢位移，看着像「没松手页面自己滑走了」。
        // 松手时 ACTION_UP 里直接 startCrossAnim()。
        if (turning) {
            invalidate();
            return;
        }
        post(crossRun);
    }

    private final Runnable crossRun = new Runnable() {
        @Override
        public void run() {
            startCrossAnim();
        }
    };

    private void startCrossAnim() {
        if (crossDir == 0) return;
        if (crossBmp == null || layout == null || pStart.length == 0 || getWidth() <= 0) {
            dropCross();
            return;
        }
        final int dir = crossDir;
        final float w = getWidth();
        final float end = dir > 0 ? -w : w;
        final float start = crossDx;
        if (crossAnim != null) crossAnim.cancel();
        ValueAnimator va = ValueAnimator.ofFloat(start, end);
        crossAnim = va;
        int dur = (int) Math.max(110,
                Math.min(190, 70 + 120 * Math.abs(end - start) / Math.max(1f, w)));
        va.setDuration(dur);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> {
            crossDx = (float) a.getAnimatedValue();
            invalidate();
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (crossAnim != animation) return;
                crossDir = 0;
                crossDx = 0f;
                crossAnim = null;
                recycleCross();
                invalidate();
            }
        });
        va.start();
    }

    /** 宿主明确告知「没有相邻章 / 取章失败」：立刻收起跨章等待，别让页面停在半路等超时 */
    void cancelCrossChapter() {
        // 本章到头了：这一次手势里别再试，否则每个 MOVE 都会重建一张整屏快照
        crossNoRetry = true;
        if (crossPendingDir != 0 || crossDir != 0) dropCross();
    }

    /** 清掉跨章状态（目录跳章、加载失败、退出页面） */
    private void dropCross() {        removeCallbacks(crossTimeout);
        removeCallbacks(crossRun);
        crossPendingDir = 0;
        crossDir = 0;
        crossDx = 0f;
        if (crossAnim != null) {
            crossAnim.cancel();
            crossAnim = null;
        }
        recycleCross();
        invalidate();   // 清掉「停在半路」的那一帧，别把旧画留着
    }

    private void recycleCross() {
        if (crossBmp != null) {
            crossBmp.recycle();
            crossBmp = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        dropImages(); // 页面走了：位图别再占着
        dropCross();
        invalidatePageBmps();
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        super.onDetachedFromWindow();
    }

    /** 释放整页预渲染位图（排版/主题/尺寸一变就作废，下一帧按需重建） */
    private void invalidatePageBmps() {
        for (int i = 0; i < pbmp.length; i++) {
            if (pbmp[i] != null && !pbmp[i].isRecycled()) pbmp[i].recycle();
            pbmp[i] = null;
            pbmpIdx[i] = -1;
        }
        pbmpGen = -1;
        pbmpOom = false;
    }

    /**
     * 取第 p 页的预渲染位图（三槽缓存）。返回 null 时调用方回退到 drawPage 直接画。
     * 只在跟手/翻页动画的短暂窗口里用，位图随排版代数作废，不会跨章/跨主题留旧图。
     *
     * <p>三槽是为了同时留住「当前页 + 上一页 + 下一页」：空闲时把两侧都预热，
     * 用户往任意方向翻都命中现成位图，不再主线程现场栅格化。
     */
    private Bitmap pageBitmap(int p) {
        if (p < 0 || p >= pStart.length || getWidth() <= 0 || getHeight() <= 0) return null;
        if (pbmpGen != layoutGen) {
            invalidatePageBmps();
            pbmpGen = layoutGen;
        }
        if (pbmpOom) return null; // 这次排版已经确认分配不了：保持逐帧直画，别再撞内存
        // 命中任一槽直接返回
        for (int i = 0; i < pbmp.length; i++) {
            if (pbmpIdx[i] == p && pbmp[i] != null && !pbmp[i].isRecycled()) return pbmp[i];
        }
        // 选一个「不是当前页」的槽覆盖（绝不把当前页挤掉，否则下一帧又得重画当前页）。
        // 三槽里当前页恒占其一，剩下两个给邻页；极端情况（理论上不会）全被当前页占满时退回 0。
        int victim = 0;
        for (int i = 0; i < pbmp.length; i++) {
            if (pbmpIdx[i] != page) { victim = i; break; }
        }
        Bitmap slot = pbmp[victim];
        Bitmap b;
        try {
            boolean reuse = slot != null && !slot.isRecycled()
                    && slot.getWidth() == getWidth() && slot.getHeight() == getHeight()
                    && slot.getConfig() == Bitmap.Config.RGB_565;
            // 页背景是实色、正文是文字，无透明需求：RGB_565 比 ARGB_8888 省一半内存与分配时间，
            // 颜色保真肉眼几乎无感（跨章快照早就用 RGB_565）。
            b = reuse ? slot
                    : Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.RGB_565);
            Canvas bc = new Canvas(b);
            bc.drawColor(theme.bg);   // 复用旧位图时这一笔就把上一页盖干净
            drawPage(bc, p);
        } catch (Throwable t) {
            pbmpOom = true; // 内存不够就退回逐帧直画：慢一点，但不崩、也不每帧重试
            return null;
        }
        if (pbmp[victim] != null && pbmp[victim] != b && !pbmp[victim].isRecycled()) pbmp[victim].recycle();
        pbmp[victim] = b;
        pbmpIdx[victim] = p;
        return b;
    }

    /**
     * 空闲时把「当前页 + 上一页 + 下一页」提前栅格化。
     *
     * <p>翻页动画每帧贴的是整页位图，而某一页第一次被用到时，要在主线程把整章
     * layout 画进一张全屏位图（页内文字越多越慢）。隐藏书内图片后每页都是满页文字，
     * 这步正好落在抬手、动画刚要开始的那一帧 —— 手感就是「翻页变慢/顿一下」。
     * 提前在空闲帧把两侧都做好，往任意方向翻都只剩现成位图平移。
     */
    private void prefetchPageBmp() {
        if (layout == null || pStart.length == 0 || scrollMode) return;
        if (typesetting || pbmpOom) return;
        if (animDir != 0 || anim != null || turning || springBack != null) return;
        if (crossDir != 0 || crossPendingDir != 0) return;
        if (getWidth() <= 0 || getHeight() <= 0) return;
        // 当前页 + 双向邻页一起预热：pageBitmap 内部命中即返回，已暖的不重复画。
        // 三槽足够同时留住这三页，往回翻也暖，不再冷栅格化。
        int lo = page - 1, hi = page + 1;
        int[] want = new int[]{page, lo, hi};
        for (int q : want) {
            if (q >= 0 && q < pStart.length) pageBitmap(q);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        drawAll(c);
        drawGuide(c);
    }

    /**
     * 上/下边距的版心边界线（只在拖动那两根滑杆时出现）。
     *
     * 下边距很特殊：正文永远从版心顶往下排，改底部留白只影响「这一页还能再放几行」，
     * 行是整行跳的 —— 48dp 的范围常常只够跨一两行，中间几十档看上去一动不动。
     * 一条跟着手走的边界线，才是逐 dp 的反馈。
     */
    private void drawGuide(Canvas c) {
        if (guideMode == 0 || layout == null) return;
        int y = guideMode == 1 ? padT : getHeight() - padB;
        guidePaint.setColor((theme.acc & 0x00FFFFFF) | 0x88000000);
        guidePaint.setStrokeWidth(Math.max(1f, dp(1)));
        c.drawLine(padL, y, getWidth() - padR, y, guidePaint);
    }

    /** 原绘制主体：多段 return，抽出来之后辅助线才能统一画在最上层 */
    private void drawAll(Canvas c) {
        // 几何自愈：分页之后视口/留白变过（漏发 onSizeChanged 的路径），先重排再画，
        // 否则画出来的是按旧几何切的页 —— 正文底部会多出半行被裁切的字
        // 防御：异步分页进行中（typesetting）绝不在这里 post(relayout) —— 否则会把在途的
        // 异步分页取消又重排，大章上表现为概率性闪烁/重排。排版入口已先锁住真实几何，
        // 这里即便几何暂时对不上也只画当前帧，等分页完成自然对齐。
        if (layout != null && pStart.length > 0 && !typesetting
                && (getWidth() != laidW || getHeight() != laidH
                || padT != laidPT || padB != laidPB)) {
            post(this::relayout);
            c.drawColor(theme.bg);
            return;
        }
        c.drawColor(theme.bg);
        if (layout == null || pStart.length == 0) return;
        if (scrollMode) {
            drawScroll(c);
            return;
        }
        // 等相邻章期间：当前页停在手指拖出来的位置（另一侧是底色），
        // 等新章排版落定后 startCrossAnim 从同一个 crossDx 接着滑 —— 中间不回到原位。
        if (crossDir == 0 && crossPendingDir != 0 && crossDx != 0f) {
            if (crossBmp != null) {
                // 快照里已经烘过页眉页脚，直接贴即可
                c.save();
                c.translate(crossDx, 0);
                c.drawBitmap(crossBmp, 0, 0, null);
                c.restore();
            } else {
                c.save();
                c.translate(crossDx, 0);
                drawPage(c, page);
                c.restore();
                drawHeadFoot(c);
            }
            return;
        }
        // 跨章：旧页快照滑出、新章内容滑入（和同章翻页同一套平移）
        if (crossDir != 0 && crossBmp != null) {
            float nx = (crossDir > 0 ? getWidth() : -getWidth()) + crossDx;
            c.save();
            c.translate(nx, 0);
            drawPage(c, page);
            drawHeadFoot(c);
            c.restore();
            c.save();
            c.translate(crossDx, 0);
            c.drawBitmap(crossBmp, 0, 0, null);
            c.restore();
            return;
        }
        float shift = (turning || springBack != null) ? turnDx : (animDir != 0 ? animDx : 0f);
        if (shift == 0f) {
            drawPage(c, page);
        } else {
            // 滚动/动画/跟手都可能走到这里：逐帧贴预渲染位图，不再每帧重画整章。
            // 位图取不到（OOM/尺寸未就绪）时回退到 drawPage 直接画，行为与旧版一致。
            if (animDir != 0) {
                // 动画：新页从侧边滑入，旧页滑出
                int old = animOld >= 0 ? animOld : page;
                Bitmap bp = pageBitmap(page);
                Bitmap bo = pageBitmap(old);
                c.save();
                c.translate(shift, 0);
                if (bp != null) c.drawBitmap(bp, 0, 0, null);
                else drawPage(c, page);
                c.restore();
                c.save();
                c.translate(shift + (animDir > 0 ? -getWidth() : getWidth()), 0);
                if (bo != null) c.drawBitmap(bo, 0, 0, null);
                else drawPage(c, old);
                c.restore();
            } else {
                // 跟手：当前页跟随手指，相邻页从另一侧进入
                int other = turnDx < 0 ? page + 1 : page - 1;
                boolean hasOther = other >= 0 && other < pStart.length;
                Bitmap bp = pageBitmap(page);
                Bitmap bo = hasOther ? pageBitmap(other) : null;
                c.save();
                c.translate(turnDx, 0);
                if (bp != null) c.drawBitmap(bp, 0, 0, null);
                else drawPage(c, page);
                c.restore();
                if (bo != null) {
                    c.save();
                    c.translate(turnDx + (turnDx < 0 ? getWidth() : -getWidth()), 0);
                    c.drawBitmap(bo, 0, 0, null);
                    c.restore();
                } else if (hasOther) {
                    c.save();
                    c.translate(turnDx + (turnDx < 0 ? getWidth() : -getWidth()), 0);
                    drawPage(c, other);
                    c.restore();
                }
            }
        }
        drawHeadFoot(c);
    }

    /** 滚动模式绘制：整章连续铺开，按 scrollPx 裁出可视窗 */
    private void drawScroll(Canvas c) {
        c.save();
        // 裁到可视窗本身：上下各切半行是滚动模式该有的样子，但不能越界画进
        // 页眉/页脚带里（以前上下各多给 6dp / 3dp，滚动时字会蹭到页眉上）
        c.clipRect(padL, padT, getWidth() - padR, getHeight() - padB);
        c.translate(padL, padT - scrollPx);
        int visH = getHeight() - padT - padB;
        int vf = layout.getLineForVertical(Math.max(0, (int) scrollPx));
        int vl = layout.getLineForVertical((int) (scrollPx + Math.max(1, visH)));
        drawFindBands(c, vf, vl);
        layout.draw(c);
        drawImgSlots(c, vf, vl);
        if (selA >= 0 && selB > selA) {
            try {
                drawSelBands(c);
            } catch (Exception e) {
                /* 选区越界：跳过本帧高亮 */
            }
        }
        c.restore();
        if (hasSelection()) {
            drawHandle(c, selA, true);
            drawHandle(c, selB, false);
        }
    }

    /* ---------------- 页眉页脚（分页模式） ---------------- */

    private void drawHeadFoot(Canvas c) {
        if (headMode == 0 && footMask == 0) return;
        hfPaint.setColor(theme.sub);
        hfPaint.setAlpha(179); // 0.7，与网页版 opacity:.7 一致
        // 页眉：居中一行（章节名 / 书名 / 书名 · 章节名）
        if (headMode != 0) {
            String t = headMode == 1 ? hfChapter
                    : headMode == 2 ? hfBook
                    : (hfBook.isEmpty() ? hfChapter : hfBook + " · " + hfChapter);
            if (!t.isEmpty()) {
                hfPaint.setTextAlign(Paint.Align.CENTER);
                // 页眉贴着「页眉带」的顶边画，不再在带内垂直居中：
                // 带高 = 字高 + 6dp，居中会把 3dp 分到上方，字号一调大字形就顶出带子。
                // 现在字顶固定距带顶 2dp，字底到正文第一行恒为 4dp —— 上边距拖到 0 时
                // 首行就贴在页眉下边缘，不多一条白。
                Paint.FontMetrics fm = hfPaint.getFontMetrics();
                float base;
                if (headBandH > 0) {
                    float bandTop = padT - headBandH;
                    base = bandTop + dp(2) - fm.ascent;
                    float bot = base + fm.descent;
                    float limit = padT - dp(1);
                    if (bot > limit) base -= bot - limit;   // 带子被挤扁时至少不压进正文
                } else {
                    base = padT - dp(24);
                }
                c.drawText(fit(t, getWidth() - padL - padR), getWidth() / 2f, base, hfPaint);
            }
        }
        if (footMask != 0 && pStart.length > 0) {
            int n = Math.max(1, pStart.length);
            int pg = Math.max(0, Math.min(page, n - 1));
            // 位置类（页码/剩余章数）靠左，状态类（时间/电量/进度）靠右 —— 与网页版一致
            List<String> left = new ArrayList<>();
            List<String> right = new ArrayList<>();
            if ((footMask & 1) != 0) left.add((pg + 1) + " / " + n);
            if ((footMask & 32) != 0 && hfBookTotal > 0) {
                int rest = hfBookTotal - 1 - hfBookIndex;
                left.add(rest > 0 ? "剩 " + rest + " 章" : "末章");
            }
            if ((footMask & 2) != 0) right.add(nowHM());
            if ((footMask & 4) != 0 && batteryPct >= 0) right.add(batteryPct + "%");
            if ((footMask & 8) != 0 && hfBookTotal > 0) {
                right.add("全书 " + Math.round(((hfBookIndex + (pg + 1f) / n) / hfBookTotal) * 100) + "%");
            }
            if ((footMask & 16) != 0) right.add("本章 " + Math.round((pg + 1f) / n * 100) + "%");
            if (left.isEmpty() && right.size() > 1) left.add(right.remove(0));
            // 页脚钉底：距屏底约 10dp（基线），不随「下边距」变化。
            // 宿主没传带高时回退旧的 padB 反推（兼容滚动模式等未接线路径）
            float y;
            Paint.FontMetrics fm = hfPaint.getFontMetrics();
            if (footBandH > 0) {
                // 页脚钉在屏幕最底（字底距屏底 2dp），带高 = 字高 + 2dp
                // → 字形上边缘正好等于正文可用区域的底边。下边距拖到 0 时，
                //   最后一行就贴着页脚，中间不再隔任何固定留白。
                y = getHeight() - dp(2) - fm.descent;
                float top = y + fm.ascent;
                float limit = getHeight() - footBandH + dp(1);
                if (top < limit) y += limit - top;      // 带子太矮时往下推，保证不被切顶
            } else {
                y = getHeight() - padB + dp(15);
            }
            if (!left.isEmpty()) {
                hfPaint.setTextAlign(Paint.Align.LEFT);
                String ls = join(left);
                // 左右都是多选、且一行放不下时，左边（位置类）让位：截断加省略号，
                // 绝不压到右边的状态类上。之前是两边各画各的，长串直接叠在一起。
                if (!right.isEmpty()) {
                    String rs = join(right);
                    float avail = getWidth() - padL - padR;
                    float lw = hfPaint.measureText(ls);
                    float rw = hfPaint.measureText(rs);
                    if (lw + rw + dp(10) > avail) {
                        ls = fit(ls, Math.max((int) dp(40), (int) (avail - rw - dp(10))));
                    }
                }
                c.drawText(ls, padL, y, hfPaint);
            }
            if (!right.isEmpty()) {
                hfPaint.setTextAlign(Paint.Align.RIGHT);
                c.drawText(join(right), getWidth() - padR, y, hfPaint);
            }
        }
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    /** 页眉过长时截断加省略号（网页版是 text-overflow:ellipsis） */
    private String fit(String s, int maxW) {
        if (maxW <= 0 || hfPaint.measureText(s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && hfPaint.measureText(s.substring(0, n) + "…") > maxW) n--;
        return s.substring(0, n) + "…";
    }

    private static String nowHM() {
        java.util.Calendar d = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%02d:%02d",
                d.get(java.util.Calendar.HOUR_OF_DAY), d.get(java.util.Calendar.MINUTE));
    }

    /**
     * 画第 p 页。
     *
     * 关键在裁剪范围：分页是按「本页首行 top → 本页末行 bottom」切出来的，绘制却一直是
     * 把**整章**画上去、再按可用高度粗裁一刀。可用高度比本页行框高出 2~5dp，那一刀就
     * 正好切在下一页首行的字头上 —— 于是同一行字在页底露出小半截、翻过去又完整出现
     * 一遍（用户说的「一段话在上一页末尾出现，下一页又出现」）；反过来页顶那道 6dp 的
     * 缝也会漏出上一页末行的一点点，看起来就是「当页最后几个字和下一页开头对不上」。
     *
     * 现在按本页的行框精确裁：上一像素不多、下一像素不少。
     */
    private void drawPage(Canvas c, int p) {
        if (layout == null || p < 0 || p >= pStart.length) return;
        int first = pStart[p];
        int last = Math.min(pEnd[p], layout.getLineCount() - 1);
        if (last < first) return;
        int top = layout.getLineTop(first);
        int bot = layout.getLineBottom(last);
        c.save();
        /* 裁到「本页的行框」还不够：行框与字墨并不重合。
         *
         * 中文字形的墨迹会比行框上沿再高出 2~4dp（页首那一行看着像被削平了字头），
         * 下一页首行的墨迹同样会比本页末行的行框下沿高出一截——于是页底永远露着
         * 下一行的一条字头，用户看到的就是「底部字只显示一半」。
         * 上下各让出这段溢出量：页顶字头完整，页底也不会漏下一行。
         */
        int bleed = inkBleed();
        float clipTop = padT - bleed;
        float clipBot = padT + (bot - top) - bleed;
        if (clipBot <= clipTop + 1) clipBot = clipTop + 1;
        c.clipRect(padL, clipTop, getWidth() - padR, clipBot);
        c.translate(padL, padT - pTop[p]);
        drawFindBands(c, first, last); // 底纹必须在字下面：先铺条，再由 layout 把字盖上去
        layout.draw(c);
        drawImgSlots(c, first, last);
        c.restore();
        if (p == page && hasSelection()) drawSelection(c, p);
    }

    /**
     * 字形墨迹超出「行框」的余量（px）：按当前字号取，字号越大溢出越多。
     * 实测 18sp / 行距 1.9 时约 0.1 倍字号（6px 上下），这里给 0.14 倍 + 2dp 兜底。
     */
    private int inkBleed() {
        return Math.max(Math.round(dp(2)), Math.round(textSizePx * 0.14f));
    }

    /* ---------------- 行字框：唯一度量来源 ---------------- */

    /**
     * 作废行字框缓存 —— 每次排版落定（换 layout）都要调一次。
     *
     * <p>字框不再在排版期一次算完：那是「用一支样字蒙一个高度」，一行里比它高的字
     * 就会被切掉半边。现在只把「已量」标记全部清掉，真正要画的那些行由
     * {@link #ensureLineInk(int)} 逐行量、逐行存。
     */
    private void rebuildBands() {
        bandN = 0;
        if (layout == null) return;
        final int n = layout.getLineCount();
        if (n <= 0) return;
        if (bandTop.length < n) {
            bandTop = new float[n];
            bandBot = new float[n];
        }
        if (bandOk.length < n) bandOk = new boolean[n];
        java.util.Arrays.fill(bandOk, 0, Math.min(bandOk.length, n), false);
        bandN = n;
    }

    /**
     * 量第 line 行的**真实字框**，结果直接写进 bandTop[i] / bandBot[i]。
     *
     * <p>用 {@code Paint.getTextBounds} 量这一行实际要画的字，而不是拿「国永宁，」
     * 之类的样字蒙一个通用高度 —— 每行的字都不一样（有的行全是「一」，有的行末尾
     * 挂着一个下伸的逗号，有的行是「永」的顶点），蒙出来的框必然有的框多了、有的框少了。
     *
     * <p>量不出来（空行、纯空格、字体报怪值）时退回 FontMetrics：宁可框高一点，
     * 也不能让高亮消失。
     */
    private void ensureLineInk(int line) {
        if (layout == null || line < 0 || line >= bandN) return;
        if (bandOk[line]) return;
        bandOk[line] = true;
        final int base = layout.getLineBaseline(line);
        final Paint lp = layout.getPaint() != null ? layout.getPaint() : tp;
        final Paint.FontMetricsInt fm = lp.getFontMetricsInt();
        float t = base + fm.ascent;
        float b = base + fm.descent;
        try {
            int s = layout.getLineStart(line);
            int e = layout.getLineEnd(line);
            // getLineEnd 含行尾换行符：量墨迹时要把它剥掉，否则会多出一整个空行的高度
            if (e > s && e <= text.length() && text.charAt(e - 1) == '\n') e--;
            if (e > s) {
                inkPaint.set(lp);
                inkPaint.setStyle(Paint.Style.FILL);
                inkPaint.setStrokeWidth(0f);   // 量墨迹不能带描边：描边会被算进 bounds
                inkPaint.setTextSkewX(lp.getTextSkewX());
                inkPaint.setLetterSpacing(lp.getLetterSpacing());
                CharSequence seg = text.subSequence(s, e);
                inkRect.setEmpty();
                inkPaint.getTextBounds(seg.toString(), 0, seg.length(), inkRect);
                if (inkRect.height() > 1) {
                    // 描边（字重）让轮廓外扩半个线宽，余量跟着它走：切字重时框自动变高
                    final float guard = dp(0.7f) + lp.getStrokeWidth() * 0.5f;
                    float mt = base + inkRect.top - guard;
                    float mb = base + inkRect.bottom + guard;
                    if (mb - mt > 1f) {
                        t = mt;
                        b = mb;
                    }
                }
            }
        } catch (Exception ignore) {
            /* 量不出来就用 FontMetrics 兜底 */
        }
        // 行距被拉到 1 以下时行框会比字还矮：这时才夹一次，保证相邻两行的高亮不粘连。
        // 行距正常（≥1）时行框本来就比墨迹高，夹不到任何东西，也就不必担心切字。
        final float lt = layout.getLineTop(line) + dp(0.5f);
        final float lb = layout.getLineBottom(line) - dp(0.5f);
        if (lb - lt >= 4f) {
            t = Math.max(t, lt);
            b = Math.min(b, lb);
        }
        if (b - t < 1f) {
            final float mid = base + (fm.ascent + fm.descent) * 0.5f;
            t = mid - 0.5f;
            b = mid + 0.5f;
        }
        bandTop[line] = t;
        bandBot[line] = b;
    }

    /** 第 line 行的字框上沿（layout 坐标；首次访问时现量） */
    private float bandTopOf(int line) {
        ensureLineInk(line);
        return line >= 0 && line < bandN ? bandTop[line] : 0f;
    }

    private float bandBotOf(int line) {
        ensureLineInk(line);
        return line >= 0 && line < bandN ? bandBot[line] : 0f;
    }

    /**
     * 惰性兜底：layout 已经换了、缓存还是上一份（异步分页刚切完的那一帧）时补建一次。
     */
    private void ensureBands() {
        if (layout == null) {
            bandN = 0;
            return;
        }
        if (bandN != layout.getLineCount()) rebuildBands();
    }

    /* ---------------- 两端对齐的横向补偿 ---------------- */

    /* ---------------- 横向坐标：唯一来源是版面 ---------------- */

    /**
     * 字符偏移 → 排版横坐标。
     *
     * <p>只有这一个出口：两端对齐的字距在**测量期**就进了 StaticLayout，所以
     * {@code getPrimaryHorizontal} 给的就是屏幕上画的位置，任何地方都不许再叠加补偿。
     */
    private float xOf(int off) {
        if (layout == null) return 0f;
        return layout.getPrimaryHorizontal(off);
    }

    /**
     * 某一行的右缘（整行选区的终点、行尾手柄的横坐标）。
     *
     * <p>必须用 {@code getLineRight}：{@code getPrimaryHorizontal(getLineEnd(line))} 里的
     * 偏移已经属于下一行，量出来是下一行行首，整行会被画成一条缝。
     */
    private float lineRightOf(int line) {
        if (layout == null) return 0f;
        return layout.getLineRight(line);
    }
    /**
     * 选区高亮：逐行画圆角条，上下沿一律取 {@link #rebuildBands()} 缓存的行字框。
     *
     * <p>不再用 {@code layout.getSelectionPath()}：它给的是**行框**
     * （lineTop..lineBottom），行距 1.9 倍时行框比字高出近一倍，多出来的空间还全在
     * 基线下方 —— 于是下沿糊到下一行、上沿刚好切着字头，看起来就是「红色偏下、溢出」。
     *
     * <p>调用前画布必须已经 translate 到正文排版原点（分页与滚动两种模式的偏移不同）。
     */
    private void drawSelBands(Canvas c) {
        if (layout == null || selB <= selA) return;
        drawBand(c, selA, selB, selPaint);
    }

    /**
     * 逐行把 [s0,e0) 这段文字按**行字框**涂成圆角条（选区与搜索命中共用）。
     *
     * <p>上下沿读行字框缓存（逐行实测的真实墨迹），横向读 {@link #xOf}／{@link #lineRightOf}
     * —— 版面和屏幕是同一套坐标，两个方向都不需要「估算」或补偿。
     */
    private void drawBand(Canvas c, int s0, int e0, Paint p) {
        if (layout == null || e0 <= s0) return;
        ensureBands();
        if (bandN == 0) return;
        int l1 = Math.max(0, Math.min(layout.getLineForOffset(s0), bandN - 1));
        int l2 = Math.max(l1, Math.min(layout.getLineForOffset(Math.max(s0, e0 - 1)), bandN - 1));
        float r = dp(2.5f);
        for (int line = l1; line <= l2; line++) {
            int ls = layout.getLineStart(line);
            int le = layout.getLineEnd(line);
            int s = Math.max(s0, ls);
            int e = Math.min(e0, le);
            if (e <= s) continue; // 空行/整行未选：不涂，留出行的缝
            // 这一段从行首起（跨行选中的中间行 / 整行段）：跳过段首缩进的那两格空白。
            // 缩进在文本里就是两个全角空格，不跳的话高亮会先把一段空白涂上，
            // 看起来就是「行首那两个字的位置也选中了」。
            if (s == ls) {
                while (s < e && isBlank(text.charAt(s))) s++;
                if (e <= s) continue;
            }
            float x1 = xOf(s);
            float x2 = e >= le ? lineRightOf(line) : xOf(e);
            if (x2 <= x1) continue;
            c.drawRoundRect(x1, bandTopOf(line), x2, bandBotOf(line), r, r, p);
        }
    }

    private void drawSelection(Canvas c, int p) {
        if (layout == null || p < 0 || p >= pStart.length) return;
        // 与 drawPage 同一套页范围：整章全选时选区会跨页，裁剪必须停在页边界上，
        // 否则相邻页的选区条会一起画进来（页底出现一条不属于本页的高亮）
        int first = pStart[p];
        int last = Math.min(pEnd[p], layout.getLineCount() - 1);
        if (last < first) return;
        c.save();
        c.clipRect(padL, padT - dp(1), getWidth() - padR,
                padT + (layout.getLineBottom(last) - layout.getLineTop(first)));
        c.translate(padL, padT - topOf(p));
        try {
            drawSelBands(c);
        } catch (Exception ex) {
            /* 选区越界：跳过本帧高亮 */
        }
        c.restore();
        drawHandle(c, selA, true);
        drawHandle(c, selB, false);
    }

    /**
     * 手柄：一条 2dp 竖线 + 末端一个圆点。
     *
     * 竖线只占字框高度（ascent..descent），圆点落在字框**外侧**：起点朝上、终点朝下。
     * 两个点各在选区外侧，一来竖线不会压到字，二来拖起点时手指在文字上方、
     * 拖终点时在下方，正好让开正在扩选的那一行。（对调前两点都朝下，
     * 拖终点时圆点正压在下一行的字上，看着像「挡字」。）
     */
    private void drawHandle(Canvas c, int off, boolean start) {
        float[] p = handlePos(off, start);
        if (p == null) return;
        // 往选区外侧让 2dp：竖线正好骑在笔画上既显脏，也让人以为「位置不对」
        float x = p[0] + (start ? -dp(2f) : dp(2f));
        float top = p[1];
        float bot = p[2];
        // 高度就是行字框高度，不再「不足 10dp 就拉到 10dp」——
        // 那个兜底会让小字号下的竖线比高亮条高出一截，正是「光标错位」的来源之一
        if (bot - top < 1f) return;
        float w = dp(1.1f);
        c.drawRoundRect(x - w, top, x + w, bot, w, w, handlePaint);
        // 圆点与竖线**相切**（不留缝）：留缝像断开的两个零件，相切才是一根「棒棒糖」
        float r = dp(4.6f);
        c.drawCircle(x, start ? top - r : bot + r, r, handlePaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
