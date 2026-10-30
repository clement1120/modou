package cn.modou.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import java.util.ArrayList;
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
     * 已加载的插图：地址 → 位图，**按字节数封顶**。
     *
     * 以前是个没有上限的 HashMap，只在「纯文字章」那条分支上被动清过槽位、从没清过图。
     * 正文插图的解码目标是版心宽度（600~1200px），一张就是好几 MB；一本图文书一章
     * 几十张，翻上几章就是几百 MB 到几个 G —— 这是内存爆掉的主因。
     * 现在换成 48MB 的 LruCache（约放得下 4~8 张大图），换章再整体清一次。
     */
    private final android.util.LruCache<String, android.graphics.Bitmap> imgs =
            new android.util.LruCache<String, android.graphics.Bitmap>(48 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };
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
    /** 最近一次成功分页时的几何（onDraw 自愈比对用） */
    private int laidW = -1, laidH = -1, laidPT = -1, laidPB = -1;
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

    private float downX, downY, lastX, lastY;
    private long downAt;
    private boolean pressing, selecting, draggingHandle, dragA, turning;
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
    /** 跨章翻页动画：旧页整屏快照 + 新章内容滑入（与同章翻页同一套 180ms 平移） */
    private Bitmap crossBmp;
    /** 已请求换章、正等正文到位：+1 下一章 / -1 上一章 */
    private int crossPendingDir;
    /** 跨章动画进行中的方向（0 = 没在动） */
    private int crossDir;
    /** 旧页快照的横向偏移：跟手时从手指位置起，动画跑到 ∓width */
    private float crossDx;
    private ValueAnimator crossAnim;

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

    void setStyle(NativeTheme t, float sizePx, float lineMul, String family, int weight,
                  float paraGap, int padL, int padT, int padR, int padB) {
        boolean dirty = Math.abs(this.textSizePx - sizePx) > 0.5f
                || Math.abs(this.lineMul - lineMul) > 0.01f
                || this.insL != padL || this.padT != padT || this.insR != padR || this.padB != padB
                || !this.family.equals(family)
                || this.bold != (weight >= 500)
                || Math.abs(this.paraGap - paraGap) > 0.01f;
        this.theme = t;
        this.textSizePx = sizePx;
        this.lineMul = lineMul;
        this.family = family == null ? "song" : family;
        this.bold = weight >= 500;
        this.paraGap = paraGap;
        this.insL = padL;
        this.insR = padR;
        this.padL = padL;
        this.padT = padT;
        this.padR = padR;
        this.padB = padB;
        setBackgroundColor(t.bg);
        tp.setTextSize(sizePx);
        tp.setColor(t.text);
        Typeface tf = Typeface.SERIF;
        if ("hei".equals(this.family)) tf = Typeface.SANS_SERIF;
        else if ("kai".equals(this.family)) tf = Typeface.SERIF;
        tp.setTypeface(Typeface.create(tf, this.bold ? Typeface.BOLD : Typeface.NORMAL));
        tp.setFakeBoldText(this.bold && weight >= 500 && weight < 600);
        // 量字格必须先清掉字距（上一轮排版可能给它加过），否则字格会越量越大。
        // 注意 Android 的 letterSpacing 单位是 em（0 = 正常，0.1 = 每个字多 0.1 个字高），
        // 不是「倍数」，所以复位用 0 而不是 1。
        tp.setLetterSpacing(0f);
        cellBase = tp.measureText("国");
        selPaint.setColor(t.sel);
        handlePaint.setColor(t.handle);
        if (dirty) {
            // 纯文字章：段距变化要重排原文（段间插入等高的空白行）；图文章在 relayout 里重建
            if (blocks == null && rawBody != null) text = withTitle(bodyText());
            relayout();
        }
        invalidate();
    }

    /** 换章（纯文字）；offset>=0 时跳到该字符所在页（尺寸未就绪时延后消费） */
    void setChapter(String body, int offset) {
        dropImages(); // 上一章的插图全部释放：位图是内存大头
        this.blocks = null;
        this.rawBody = body == null ? "" : body;
        this.text = withTitle(bodyText());
        this.page = 0;
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
        this.page = 0;
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
            text = buildContent(getWidth() - padL - padR);
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
        appendTitle(out);
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
        return compressPunct(applyParaGaps(stripDupTitle(raw, title)), 0);
    }

    /** 标题行：居中、加粗、放大到正文的 1.22 倍（不缩进） */
    private void appendTitle(SpannableStringBuilder sb) {
        if (title == null || title.isEmpty()) return;
        int s = sb.length();
        sb.append(title);
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
        this.page = 0;
        this.pendingLast = offset == Integer.MAX_VALUE;
        this.pendingOffset = pendingLast ? -1 : offset;
        clearSelInternal();
        this.text = buildContent(getWidth() - padL - padR);
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
        this.page = 0;
        this.pendingLast = offset == Integer.MAX_VALUE;
        this.pendingOffset = pendingLast ? -1 : offset;
        clearSelInternal();
        this.text = buildContent(getWidth() - padL - padR);
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
     */
    private CharSequence buildContent(int widthPx) {
        imgSlots.clear();
        if (blocks == null) return text == null ? "" : text;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (title != null && !title.isEmpty()) {
            appendTitle(sb);
            sb.append('\n'); // 同上：标题行不压高
        }
        int bodyStart = sb.length();
        boolean first = true;
        // 首个文本块若就是章标题（书源正文常自带一行标题），跳过它：否则屏幕上两个标题
        String nt = normHead(title);
        for (Paras.Block b : blocks) {
            if (skipLeadBlock(b, first, nt)) continue;
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
                if (d != null) imgSlots.add(new ImgSlot(start, d, k));
            } else {
                sb.append("　　").append(b.value); // 与纯文字版一致：段首全角缩进
            }
        }
        return compressPunct(sb, bodyStart);
    }

    /* ---------------- 标点挤压（行尾标点不留半格） ---------------- */

    /**
     * 允许压成半格的行尾标点。
     *
     * 全角标点的字面只占格子左半边（“，”“。” 的墨迹都在左半），右边半格是空白：
     * 一行排到 14 个字正好卡在这个标点上时，右边就空出小半格 —— 一屏十几行里
     * 总有三四行差这半格，整段看起来就是「右边没对齐」。中文书籍排版的做法是
     * 把这些标点压成半角（标点挤压），开源阅读 legado 的 ZhLayout 也是这么做的。
     */
    private static final String TRAIL_PUNCT = "，。、；：？！）〕】》」』”’,.;:?!)>]}";

    /**
     * 正文里的行尾标点改成半格占位：字符数不变（选区、书签、进度偏移都不受影响），
     * 只是让后面那个字往前挪半格，于是同一行能多容一个字，右边不再空半格。
     *
     * @param from 从哪个字符开始压（标题行不压：它字号不同，压了会错位）
     */
    private CharSequence compressPunct(CharSequence src, int from) {
        if (src == null || src.length() == 0) return src;
        SpannableStringBuilder sb;
        if (src instanceof SpannableStringBuilder) {
            sb = (SpannableStringBuilder) src;
        } else {
            sb = new SpannableStringBuilder(src); // 拷一份再挂 span，原对象不动
        }
        int half = Math.max(1, Math.round(cellWidth() * 0.5f));
        for (int i = Math.max(0, from); i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '\n' || c == '\u3000' || c == '\uFFFC') continue;
            if (TRAIL_PUNCT.indexOf(c) < 0) continue;
            // 已经挂过（同一份 CharSequence 被复用）就跳过，别叠加
            if (sb.getSpans(i, i + 1, HalfPunctSpan.class).length == 0) {
                sb.setSpan(new HalfPunctSpan(half), i, i + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    /** 半格标点：量宽度时只报半格，画的时候照原样画（墨迹本来就在左半） */
    private static final class HalfPunctSpan extends android.text.style.ReplacementSpan {
        private final int boxW;

        HalfPunctSpan(int boxW) {
            this.boxW = boxW;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                           Paint.FontMetricsInt fm) {
            if (fm != null) {
                Paint.FontMetricsInt m = paint.getFontMetricsInt();
                fm.ascent = m.ascent;
                fm.descent = m.descent;
                fm.top = m.top;
                fm.bottom = m.bottom;
                fm.leading = m.leading;
            }
            return boxW;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, Paint paint) {
            canvas.drawText(text, start, end, x, y, paint);
        }
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

    /** 已加载的图用真图；没加载完用一块同比例透明占位（占住位置，图到了再重排） */
    private android.graphics.drawable.Drawable drawableFor(String url, int widthPx) {
        android.graphics.Bitmap bm = imgs.get(url);
        int w = widthPx > 0 ? widthPx : 300;
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
        if (layout == null) return false;
        int a0 = layout.getLineStart(line);
        int b0 = layout.getLineEnd(line);
        for (int i = a0; i < b0; i++) {
            char ch = text.charAt(i);
            if (ch != '\n' && !isBlankCh(ch)) return false;
        }
        return true;
    }

    /** 某个行号若是插图的起始行，返回这块图的结束行（不含）；否则 -1 */
    private int imgBlockEnd(int line) {
        if (layout == null) return -1;
        for (ImgSlot s : imgSlots) {
            int l = layout.getLineForOffset(Math.min(s.offset, Math.max(0, text.length() - 1)));
            if (l == line) return line + s.lines;
        }
        return -1;
    }

    /** 异步拉取没到位的插图（data:URL 是本地导入的书，解码很快） */
    private void loadImages() {
        if (blocks == null) return;
        final List<String> todo = new ArrayList<>();
        for (Paras.Block b : blocks) {
            if (b == null || !b.isImage()) continue;
            String u = b.value;
            if (u == null || u.isEmpty()) continue;
            if (imgs.get(u) != null || imgLoading.contains(u)) continue;
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
                    imgs.put(u, got);
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
        imgs.evictAll();
    }

    /** 图到了：重排一次。keep 住当前位置，读者不会因插图撑开而跳页 */
    private void relayoutImages() {
        int keep = layout != null && pStart.length > 0 ? pageStartOffset() : 0;
        this.pendingOffset = keep;
        this.text = buildContent(getWidth() - padL - padR);
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
        clearSelInternal();
        invalidate();
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
        trackPx = 0f;
        int avail = getWidth() - insL - insR;
        float cell = cellWidth();
        if (avail <= 0 || cell <= 0) {
            tp.setLetterSpacing(0f);
            return;
        }
        int cells = (int) Math.floor(avail / cell + 0.0001f);
        if (cells < 4) {
            tp.setLetterSpacing(0f);
            return;
        }
        float spare = avail - cells * cell;
        if (spare < 0.5f) {          // 正好整除：不需要补
            tp.setLetterSpacing(0f);
            return;
        }
        trackPx = spare / cells;
        tp.setLetterSpacing(trackPx / Math.max(1f, textSizePx));
    }

    /** 一个汉字占多宽（px）：中文排版的一格。用不带字距的基准值，避免越量越宽 */
    private float cellWidth() {
        return cellBase > 1f ? cellBase : Math.max(1f, textSizePx);
    }

    private void relayout() {
        applyCellGrid();
        int w = getWidth() - padL - padR;
        int h = getHeight() - padT - padB;
        if (blocks != null) text = buildContent(w); // 宽度变了，占位图尺寸要跟着变
        else imgSlots.clear();                      // 纯文字章：上一章的插图槽位必须丢掉
        if (w <= 0 || h <= 0 || text.length() == 0) {
            layout = null;
            pStart = new int[0];
            pEnd = new int[0];
            pTop = new int[0];
            return;
        }
        int keep = pendingLast ? Integer.MAX_VALUE
                : pendingOffset >= 0 ? pendingOffset
                : (layout != null ? (scrollMode ? offsetAtScrollTop() : pageStartOffset()) : 0);
        // CSS 的 line-height 语义是「行高 = 字号 × 倍数」，而 StaticLayout 的行高是
        // 字体自身 ascent+descent 再乘 multiplier。这里把差额算成 add、multiplier 固定 1：
        // 行高总和与旧算法完全一致（分页不变），但多余空间只落在一行字的下方 ——
        // 字的顶部与行顶对齐，首行（章标题）不会再被正文裁剪区切掉一截。
        Paint.FontMetricsInt fm = tp.getFontMetricsInt();
        int base = fm.descent - fm.ascent;
        lineAdd = Math.max(0f, textSizePx * lineMul - base);
        // 字距补余之后每行末端会多出「最后一个字之后的那点间距」：版面宽度放宽 1px
        // 才不会把最后一字挤到下一行，裁切仍按 padR 走，视觉上不会溢出
        int layoutW = w + (trackPx > 0f ? 1 : 0);
        StaticLayout.Builder b = StaticLayout.Builder.obtain(text, 0, text.length(), tp, layoutW)
                .setLineSpacing(lineAdd, 1f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL);
        layout = b.build();

        int n = layout.getLineCount();
        int[] s = new int[n];
        int[] e = new int[n];
        int[] t = new int[n];
        int count = 0;
        int line = 0;
        // 底部留 2dp 安全边：StaticLayout 行框与实际字形在个别 ROM 上有 1~2px 出入，
        // 卡着 h 精确放行时，最后一行的下缘会被裁剪线切掉一点点（用户看到「逗号缺一角」）
        int fitH = h - Math.round(dp(2));
        while (line < n && count < n) {
            // 页首不要落在段距空行上：那会看起来像顶部凭空多了一段白。
            //
            // 以前的做法是「把空行并回上一页」——错在空行也占高度，并回去之后本页的
            // 实际高度就超过了 fitH，末行被顶进页脚带里（看着像「底部少了一行」），
            // 而末行的行框又和下一页首行贴在一起。这里改成**跳过**：空行既不属于本页
            // 也不属于下一页，页顶从第一个非空行算起，两边都不多不少。
            while (line < n && blankLine(line)) line++;
            if (line >= n) break;
            int top = layout.getLineTop(line);
            int last = line;
            // 至少一行；能在剩余高度里放下就继续加行
            while (last + 1 < n) {
                // 段距空行只是一条视觉分隔：落在页尾就是一块纯白，还白白吃掉一行的高度，
                // 用户看到的就是「底部空得能再放一行、却什么都没有」。
                // 这里往后找到第一行真正文：放得下就把它收进来（顺带吃掉这条空行），
                // 放不下就此收页 —— 空行留给下一页，而下一页页顶本来就会跳过空行，
                // 所以不会出现「页尾空一截」或「同一段在两页各出现一次」。
                int nxt = last + 1;
                while (nxt < n && blankLine(nxt)) nxt++;
                if (nxt >= n) break;
                if (layout.getLineBottom(nxt) - top > fitH) break;
                // 下一行如果是插图的起始行，要求整块图都放得下：否则图会被切成两页
                int ie = imgBlockEnd(nxt);
                if (ie > nxt && layout.getLineBottom(Math.min(ie, n) - 1) - top > fitH) break;
                last = nxt;
            }
            s[count] = line;
            e[count] = last;
            t[count] = top;
            count++;
            line = last + 1;
        }
        pStart = new int[count];
        pEnd = new int[count];
        pTop = new int[count];
        System.arraycopy(s, 0, pStart, 0, count);
        System.arraycopy(e, 0, pEnd, 0, count);
        System.arraycopy(t, 0, pTop, 0, count);
        // 记下分页时的几何：绘制前若发现尺寸/留白变过（而 onSizeChanged 没来得及
        // 触发重排的路径），画出来的页就会多出一条被裁半行的正文 —— 直接强制重排
        laidW = getWidth();
        laidH = getHeight();
        laidPT = padT;
        laidPB = padB;

        page = pendingLast ? Math.max(0, count - 1) : pageOfOffset(keep);
        if (page >= count) page = Math.max(0, count - 1);
        // 滚动模式：重排后回到该页页首（与网页版「按页号近似恢复」一致，不回到章首）
        if (scrollMode) scrollPx = count > 0 ? pTop[Math.max(0, Math.min(page, count - 1))] : 0;
        pendingOffset = -1;
        notifyPage();
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
            return layout.getOffsetForHorizontal(line, lx);
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isBlank(char ch) {
        return ch == ' ' || ch == '\u3000' || ch == '\n' || ch == '\r' || ch == '\t';
    }

    /* ---------------- 手势 ---------------- */

    private void beginSel(float x, float y) {
        int off = offsetAt(x, y);
        if (off < 0) return;
        int n = text.length();
        if (n == 0) return;
        int p = Math.max(0, Math.min(off, n - 1));
        // 手指常落在段首缩进/段间换行上：向右找到第一个真字再锚定，别框住空白
        for (int k = 0; k < 8 && p < n - 1 && isBlank(text.charAt(p)); k++) p++;
        selA = p;
        selB = Math.min(n, p + 1);
        anchor = p;
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
        if (off >= anchor) {
            selA = anchor;
            selB = Math.min(n, off + 1);
        } else {
            selA = Math.max(0, off);
            selB = Math.min(n, anchor + 1);
        }
        if (selB <= selA) selB = Math.min(n, selA + 1);
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
        pressing = false;
        handleHidden = false;
        removeCallbacks(longPress);
    }

    /** 拖手柄：只动对应那一端，不允许两端交叉 */
    private void dragHandleTo(float x, float y) {
        int off = offsetAt(x, y);
        if (off < 0) return;
        int n = text.length();
        if (dragA) {
            selA = Math.max(0, Math.min(off, selB - 1));
        } else {
            selB = Math.max(selA + 1, Math.min(n, off + 1));
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
        int at = start ? off : Math.max(0, off - 1);
        if (at < 0 || at >= text.length()) return null;
        int line = layout.getLineForOffset(at);
        float x = start ? layout.getPrimaryHorizontal(at) : endX(at, line);
        float dy = scrollMode ? -scrollPx : -topOf(Math.max(0, Math.min(page, pStart.length - 1)));
        float base = padT + layout.getLineBaseline(line) + dy;
        Paint.FontMetricsInt fm = tp.getFontMetricsInt();
        return new float[] { padL + x, base + fm.ascent, base + fm.descent, base };
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
            return layout.getPrimaryHorizontal(at + 1);
        }
        return layout.getLineRight(line);
    }

    private RectF selRectView() {
        if (!hasSelection() || layout == null || pStart.length == 0) return null;
        try {
            selPath.reset();
            layout.getSelectionPath(selA, selB, selPath);
            selPath.computeBounds(selBounds, true);
            float dy = scrollMode ? -scrollPx : -topOf(Math.max(0, Math.min(page, pStart.length - 1)));
            float top = selBounds.top + padT + dy;
            float bottom = selBounds.bottom + padT + dy;
            // 整章全选会跨页：矩形上下要夹在当前可视区内，否则工具条按负的 top 定位会飞出屏幕
            float clipTop = padT - dp(6);
            float clipBottom = getHeight() - padB + dp(3);
            if (bottom < clipTop || top > clipBottom) return null;
            top = Math.max(top, clipTop);
            bottom = Math.min(bottom, clipBottom);
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
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        removeCallbacks(longPress);
                        if (Math.abs(dx) > Math.abs(dy)) {
                            turning = true;
                            // 到章边界时不跟手（否则拖出空白），只记住方向，松手再取相邻章
                            if (dx < 0 && page + 1 >= pStart.length) {
                                edgeDir = 1;
                                turnDx = 0f;
                            } else if (dx > 0 && page <= 0) {
                                edgeDir = -1;
                                turnDx = 0f;
                            } else {
                                edgeDir = 0;
                                turnDx = dx;
                            }
                            invalidate();
                        } else {
                            pressing = false; // 垂直滑动：本页不滚动，忽略
                        }
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
                if (turning) {
                    turning = false;
                    float dx = turnDx;
                    int ed = edgeDir;
                    edgeDir = 0;
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
                        if (ev.getX() < w * 0.32f) turn(-1);
                        else if (ev.getX() > w * 0.68f) turn(1);
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
            }
        });
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
                Bitmap bm = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                Canvas bc = new Canvas(bm);
                bc.drawColor(theme.bg);
                bc.save();
                bc.translate(turnDx, 0); // 跟手松手翻章：快照停在手指拖到的位置
                drawPage(bc, page);
                bc.restore();
                drawHeadFoot(bc);
                if (crossBmp != null) crossBmp.recycle();
                crossBmp = bm;
            } catch (Throwable t) {
                crossBmp = null; // 内存不够就退回「直接换页」，不能因此崩掉
            }
        }
        crossDx = Math.abs(turnDx) > dp(2) ? turnDx : 0f;
        crossPendingDir = dir;
        turning = false;
        turnDx = 0f;
        if (springBack != null) {
            springBack.cancel();
            springBack = null;
        }
        removeCallbacks(crossTimeout);
        postDelayed(crossTimeout, 6000); // 加载失败/超时兜底：别一直卡在「等章」
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

    /** 清掉跨章状态（目录跳章、加载失败、退出页面） */
    private void dropCross() {
        removeCallbacks(crossTimeout);
        removeCallbacks(crossRun);
        crossPendingDir = 0;
        crossDir = 0;
        crossDx = 0f;
        if (crossAnim != null) {
            crossAnim.cancel();
            crossAnim = null;
        }
        recycleCross();
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
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        // 几何自愈：分页之后视口/留白变过（漏发 onSizeChanged 的路径），先重排再画，
        // 否则画出来的是按旧几何切的页 —— 正文底部会多出半行被裁切的字
        if (layout != null && pStart.length > 0
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
        } else if (animDir != 0) {
            // 动画：新页从侧边滑入，旧页滑出
            int old = animOld >= 0 ? animOld : page;
            c.save();
            c.translate(shift, 0);
            drawPage(c, page);
            c.restore();
            c.save();
            c.translate(shift + (animDir > 0 ? -getWidth() : getWidth()), 0);
            drawPage(c, old);
            c.restore();
        } else {
            // 跟手：当前页跟随手指，相邻页从另一侧进入
            int other = turnDx < 0 ? page + 1 : page - 1;
            c.save();
            c.translate(turnDx, 0);
            drawPage(c, page);
            c.restore();
            if (other >= 0 && other < pStart.length) {
                c.save();
                c.translate(turnDx + (turnDx < 0 ? getWidth() : -getWidth()), 0);
                drawPage(c, other);
                c.restore();
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
        layout.draw(c);
        int visH = getHeight() - padT - padB;
        drawImgSlots(c, layout.getLineForVertical(Math.max(0, (int) scrollPx)),
                layout.getLineForVertical((int) (scrollPx + Math.max(1, visH))));
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
                // 页眉基线在 28dp 带子里往上靠 24dp：字框 11dp，离状态栏约 7dp，
                // 离正文顶留 22dp —— 这一条就是「页眉压着标题」和「清爽」的分界线。
                c.drawText(fit(t, getWidth() - padL - padR), getWidth() / 2f, padT - dp(24), hfPaint);
            }
        }
        if (footMask != 0 && pStart.length > 0) {
            int n = Math.max(1, pStart.length);
            int pg = Math.max(0, Math.min(page, n - 1));
            // 位置类（页码/剩余章数）靠左，状态类（时间/电量/进度）靠右 —— 与网页版一致
            List<String> left = new ArrayList<>();
            List<String> right = new ArrayList<>();
            if ((footMask & 1) != 0) left.add((pg + 1) + " / " + n);
            if ((footMask & 32) != 0) {
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
            float y = footBandH > 0 ? getHeight() - footBandH + dp(10)
                    : getHeight() - padB + dp(15);
            if (!left.isEmpty()) {
                hfPaint.setTextAlign(Paint.Align.LEFT);
                c.drawText(join(left), padL, y, hfPaint);
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

    /**
     * 选区高亮：按**字框**逐行画圆角条，而不是 layout.getSelectionPath。
     *
     * getSelectionPath 给的是**行框**（lineTop..lineBottom），行距 1.9 倍时行框比字高出
     * 一大截，而且多出来的空间全在基线下方：于是下沿糊到下一行、上沿又刚好切着字头，
     * 看着就是「红色偏下」。这里改用与手柄同一套度量（ascent..descent）上下各让 1.5dp，
     * 保证字被完全盖住、上下留白一样多，行与行之间自然空出一条缝。
     *
     * 调用前画布必须已经 translate 到正文排版原点（分页与滚动两种模式的偏移不同）。
     */
    private void drawSelBands(Canvas c) {
        if (layout == null || selB <= selA) return;
        Paint.FontMetricsInt fm = tp.getFontMetricsInt();
        int l1 = layout.getLineForOffset(selA);
        int l2 = layout.getLineForOffset(Math.max(selA, selB - 1));
        for (int line = l1; line <= l2; line++) {
            int ls = layout.getLineStart(line);
            int le = layout.getLineEnd(line);
            int s = Math.max(selA, ls);
            int e = Math.min(selB, le);
            if (e <= s) continue; // 空行/整行未选：不涂，留出行的缝
            float x1 = layout.getPrimaryHorizontal(s);
            float x2 = e >= le ? layout.getLineRight(line) : layout.getPrimaryHorizontal(e);
            float base = layout.getLineBaseline(line);
            float top = base + fm.ascent - dp(1.5f);
            float bot = base + fm.descent + dp(1.5f);
            float r = dp(2.5f);
            c.drawRoundRect(x1, top, x2, bot, r, r, selPaint);
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
        if (bot - top < dp(10)) { // 极小字号兜底：竖线至少 10dp，否则只剩一个点
            float mid = (top + bot) / 2f;
            top = mid - dp(5);
            bot = mid + dp(5);
        }
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
