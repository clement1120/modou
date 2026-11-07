package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 书源管理面板（可嵌入首页底部导航，也可由 SourcesActivity 薄壳承载）。
 *
 * 对齐网页版 sources.js 的书源管理重设计（docs/书源页重设计研究.md）：
 *  - 书源体检：批量只跑快速校验（搜索一步，同域串行 / 跨域并发 5）→ 诊断报告。
 *    想验「详情→目录→正文」用单源的「规则调试」（那里才跑三步，并发 2）。
 *    判定分两层：**网络层失败**（超时/403/DNS/风控页拦截）只算未判定，永不进删除清单；
 *    只有**规则层失败**才判失效。未启用的源照常校验 —— 启用与否只影响搜书。
 *  - 行内状态点：有效 / 失效 / 规则失效 / 深度失效 / 未判定 / 未校验 / 校验中，
 *    失效原因写在行副行（网页版「状态用色点、原因进详情」的移植）。
 *  - 筛选：只在 ⋮ 的级联菜单里（筛选状态 / 分组 / 更多筛选条件），顶栏不再放漏斗。
 *  - 已激活条件：工具栏 chip 摘要，点一下清掉该条件。
 *  - 多选批量：长按行进入多选，底部操作栏 启用/禁用/校验/删除/导出。
 *  - 检查重复：按地址、名称两轮判定，每组保留分最高的一条，一键删除其余。
 *
 * 校验状态只存内存（与网页版一致，不落库）：书源 id → 状态。
 * 引擎能力来自 SourceService（隐藏 WebView 跑 JS 规则），回调全部在主线程，
 * 所以队列驱动不需要任何锁：完成一个取下一个即可。
 */
final class SourcesPanel {

    /** 深度校验结论 */
    private static final class Deep {
        String state;   // run / ok / bad
        String step;    // 搜索 / 详情 / 目录 / 正文 / 全部通过
        String reason;
        long ms;
        int chars;
        /** 失败原因是网络层（超时/403/DNS），不是规则失效 —— 不能据此判死 */
        boolean netFail;
        /** 过程中的非致命提示（如「详情页失败已跳过」），成功时也要能看到 */
        String note;
    }

    /** 从本地文件导入书源的请求码（由 ShelfActivity 转发 onActivityResult） */
    static final int REQ_IMPORT_FILE = 8111;

    private final Activity host;
    private ModouUi ui;
    /**
     * 批量修复搜索入口的弹层（进度 / 结果）。
     *
     * <p>同一时刻只允许存在一个：探测一轮几十秒，用户很容易在中途退出去又点一次，
     * 或者切小窗回来再点 —— 旧弹层没关，新的又开一层，两张卡片叠在一起，
     * 上层较窄就会从下层边缘露出半行字（实测截图里就是「没修好的原因…」露出卡片顶）。
     */
    private android.app.Dialog fixDialog;
    /** 顶栏 + 工具栏的固定区：与列表解耦，搜索输入时不动它（保住焦点/光标） */
    private LinearLayout headerBox;
    /** 书源列表：ListView 行视图按屏复用 + 局部重绑（0.9.43 性能重构） */
    private androidx.recyclerview.widget.RecyclerView recycler;
    private SourceAdapter adapter;
    private androidx.recyclerview.widget.ItemTouchHelper dragHelper;
    /** 扁平化的行模型：组标题 / 书源行 / 空态提示 */
    private final List<Row> rows = new ArrayList<>();
    /** 书源 key → rows 下标：校验完要精确定位到那一行刷新，不用遍历 137 行 */
    private final Map<String, Integer> rowIndex = new HashMap<>();
    private TextView cabCount;          // 多选态顶栏「已选 N 项」
    private TextView cabSelectAll;      // 多选态顶栏「全选 / 取消全选」
    private TextView status;
    private TextView checkBtnText;      // 体检 sheet 里的主按钮（开始/停止/重新校验）
    /** 体检面板的正文与按钮行：阶段切换时就地 removeAllViews 重画，不重建 Dialog */
    private LinearLayout checkBody;
    private LinearLayout checkFooterRow;
    private EditText checkKw;           // 未开始页的搜索词输入框（重画时要读回当前值）
    private TextView checkProgress;     // 体检 sheet 里的大数字进度（0 / 131）
    private TextView checkProgressStat; // 大数字右侧的小字统计（有效 3 · 失效 1）
    private android.widget.ProgressBar checkProgressBar; // 体检 sheet 里的可视化进度条
    private TextView checkSummary;      // 体检 sheet 里的阶段小结
    private LinearLayout checkActions;  // 体检 sheet 里的结果动作区
    private LinearLayout checkBar;      // 底部批量操作栏
    private TextView switchLabel;       // 底栏「启用/禁用/切换」文案（随选中项变化）

    private final List<JSONObject> items = new ArrayList<>();
    /**
     * 校验状态（static）：体检必须能在后台继续 —— 用户关掉 sheet、切到别的 tag，
     * 校验都不能断。状态挂 static 上（进程级），面板只是它的一个观察窗口；
     * uiTarget 指向当前活动的面板实例，后台进度回调统一经 uiPost 路由到它。
     */
    /**
     * 状态键：优先用书源 id（`地址|名称`），退回 url。
     *
     * <p>同一地址挂两条不同名字的书源（导入重复包时很常见）是合法的，
     * 用 url 当键会让两行共享一份结果：校验 A 会同时改掉 B 的状态点，
     * 深度校验还可能拿 A 的书去验 B 的规则。
     */
    private static String keyOf(JSONObject o) {
        if (o == null) return "";
        String id = o.optString("id", "");
        return id.isEmpty() ? o.optString("bookSourceUrl", "") : id;
    }

    /**
     * 取书源自己声明的「校验关键词」（Legado 的 ruleSearch.checkKeyWord）。
     *
     * <p>两个坑一起踩过：
     * <ul>
     *   <li>它住在 {@code ruleSearch} 子对象里，不在顶层 —— 以前 {@code o.optString("checkKeyWord")}
     *       永远读到空串，所有源一律退化成「三体」，作者精心挑的词一次都没用过；</li>
     *   <li>Legado 规定：这个词里如果出现 http / :: / ++ / --，说明它不是搜索词
     *       （而是调试入口或模板残留），必须忽略。照抄会拿整条 URL 去搜，必然 0 条。</li>
     * </ul>
     * 所以调用方拿到空串时，按「没有自带关键词」处理。
     */
    static String checkWordOf(JSONObject o) {
        if (o == null) return "";
        String w = "";
        JSONObject rs = o.optJSONObject("ruleSearch");
        if (rs != null) w = rs.optString("checkKeyWord", "").trim();
        // 兼容把 ruleSearch 字段拍平到顶层的旧导入数据
        if (w.isEmpty()) w = o.optString("checkKeyWord", "").trim();
        if (w.contains("http") || w.contains("::") || w.contains("++") || w.contains("--")) return "";
        return w;
    }
    /** key（书源 id）→ ok / bad / skip / nosearch / run / none */
    private static final Map<String, String> quick = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> qReason = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * key → true：已确认是「规则层失效」——请求通了（HTTP 2xx），换了两个不同关键词
     * 都一条解析不出来。只有这种才允许进待删清单；超时/403/风控页拦截 一律不算。
     */
    private static final Map<String, Boolean> ruleBad = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * 「未判定」的细分原因（0.9.136）：net=请求没通/被拦，rule=请求通了但没解析到书，
     * unsure=关键词都没命中、原因待确认。
     *
     * <p>以前报告分类靠错误文案里的关键字猜，文案一改口径就漂；而且超时/403/服务未启动
     * 一律写 quick="bad"，statusKey 直接显示成红色「失效」、还被「仅看校验失败」全捞走 ——
     * 一轮 5 路并发的批量体检下来，慢站和风控站成片变红，就是「新源一半校验失败」的主因。
     */
    private static final Map<String, String> skipKind = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Deep> deep = new java.util.concurrent.ConcurrentHashMap<>();
    /** 快速校验命中的第一本书（深度校验的起点） */
    private static final Map<String, List<JSONObject>> foundBook = new java.util.concurrent.ConcurrentHashMap<>();
    /** 行内状态控件引用（paint 时登记），单行状态变化时只改这一行不整表重画 */
    /** 书源健康度快照：一次查询读全表，渲染时按 url 取，避免每行一次 SQL */
    private Map<String, JSONObject> health = new HashMap<>();
    private static String keyword = "我的";
    private static String progressText = "";   // 最近一条进度（同步到统计行）

    /* ---------------- 体检状态机 ---------------- */

    /**
     * 体检阶段。整个面板只认这一个枚举，不再用几个 boolean 去猜「跑没跑完」。
     *
     * <pre>
     * IDLE ──开始──▶ QUICK ──快速完成──▶ REPORT（批量体检：无深度阶段）
     *                    └─ full=true 且有书 ─▶ DEEP ─深度完成─▶ REPORT（单源规则调试）
     * REPORT ──重新校验──▶ QUICK        REPORT ──清理完且三类全空──▶ DONE
     * </pre>
     *
     * <p>0.9.62 之前用 checkRunning / checkFinished 两个位表示，结果页与未开始页
     * 在某些组合下长得一样，结果页会重新长出「开始校验」按钮：点下去清空刚跑完的
     * 结论又从头跑，就是用户看到的「结果 → 进度 → 结果」死循环。现在阶段唯一，
     * 按钮由阶段决定，不可能再出现「报告页上放着开始校验」。
     */
    enum CheckStage { IDLE, QUICK, DEEP, REPORT, DONE }

    private static CheckStage stage = CheckStage.IDLE;
    private static boolean checkStop;
    /** 本轮是否跑完整了（false = 中途停止/异常，报告里的「空」不等于「全部通过」） */
    private static boolean reportComplete;

    /** 是否正在跑某一轮（快速或深度） */
    private static boolean checkRunning() {
        return stage == CheckStage.QUICK || stage == CheckStage.DEEP;
    }

    /* 诊断报告的三类清单（按失败原因分，不再只给一堆数字） */
    private static final int CAT_RULE = 0;   // 规则失效：请求通了但抓不到内容 → 建议删除
    private static final int CAT_NET = 1;    // 网络问题：超时 / 403 / 风控页 → 建议停用保留
    private static final int CAT_DEAD = 2;   // 站点疑似死亡：DNS 失败 / 域名过期 → 建议删除

    private static final List<JSONObject> catRule = new ArrayList<>();
    private static final List<JSONObject> catNet = new ArrayList<>();
    private static final List<JSONObject> catDead = new ArrayList<>();
    /** 本轮参与诊断的批次：报告页与「重新校验」都要用它，不能依赖当次作用域计算 */
    private static final List<JSONObject> reportBatch = new ArrayList<>();
    /**
     * 本轮明确指定的校验批次（单源调试 / 多选批量）。非 null 时优先于
     * 「选中 > 筛选 > 全部」，这样结果面板点【重新校验】跑的仍是同一批，
     * 不会莫名其妙变成全量。⋮ 菜单入口会把它清空。
     */
    private List<JSONObject> rerunScope;
    /** 本轮是否连「搜索通过」的源也做详情/目录/正文校验（单源校验时为 true） */
    private boolean deepFull;
    /** 通知栏进度用：当前阶段已完成 / 总数（progressText 只有文字，通知要画条） */
    private static volatile int nDone;
    private static volatile int nTotal;
    private static String checkDoneText = "";  // 结束语（重开 sheet 时回显）

    /* ================= 体检弹窗排版规范（0.9.102） =================
     * 「校验中 / 诊断报告 / 已处理完毕」三张面板曾是三套手感：标题 21sp 与 17sp 混着用、
     * 按钮靠 padV 11/12 各撑各的、正文底部留 6dp 又不留。下面四个常量是唯一口径，
     * 三张面板的标题、图标、按钮、行距全部读这里，改一次三处一起变。
     */
    private static final float SHEET_TITLE_SP = 19f;   // 面板标题
    private static final int SHEET_BTN_H = 48;         // 按钮固定高度（不靠内边距撑）
    private static final int SHEET_FOOTER_GAP = 18;    // 正文 → 按钮行的固定间距
    private static final int SHEET_BADGE = 48;         // 结果图标（52/58 都太抢眼）
    /** 校验作用域：false=按「选中 > 筛选 > 全部」取当前可见/选中集合；true=强制全部（预留）。
     *  ⋮「校验当前列表」与多选「批量校验」都设 false，绝不跑范围外的源。 */
    private boolean checkForceAll;

    /** 当前活动的面板实例（进入书源页时替换；后台回调只投递给活动面板） */
    private static SourcesPanel uiTarget;

    /** 后台线程 → 主线程，且只在面板仍然可用（宿主未销毁）时才执行 */
    private static void uiPost(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            SourcesPanel p = uiTarget;
            if (p == null || p.host.isFinishing() || p.host.isDestroyed()) return;
            r.run();
        });
    }

    /** 体检 sheet 的窗口引用：结束时/重开时收起旧窗口用 */
    private static Dialog checkSheetDialog;

    /** SourceEditor 保存后刷新当前活动面板（静态入口，避免反向持有面板引用） */
    static void reloadActive() {
        SourcesPanel p = uiTarget;
        if (p == null || p.host.isFinishing() || p.host.isDestroyed()) return;
        p.reload();
    }

    /* 多选态 */
    private boolean selMode;
    private final Set<String> sel = new LinkedHashSet<>();

    /* 组合筛选（AND 叠加）：分组 ∩ 状态 ∩ 启用 ∩ 健康度，再叠加关键字搜索。
       三个集合都为空 = 不限制该维度；同一维度多选取并集（维度内 OR、维度间 AND）。 */
    private final java.util.Set<String> groupFilter = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> statusFilter = new java.util.LinkedHashSet<>();   // ok / skip / none / nosearch
    private final java.util.Set<String> enabledFilter = new java.util.LinkedHashSet<>();  // on / off
    /** 仅看「高病发源」：近 7 天失败率 ≥80%（与行尾红色徽标、顶部统计同口径） */
    private boolean brokenOnly;
    /** 仅看「未判定」：最近一次校验下不了结论（超时 / 403 / 关键词 0 条）的源 */
    private boolean skipOnly;
    /**
     * 本轮校验未通过的源（keyOf 集合）。校验一结束就把这批留下来并自动筛出 ——
     * 用户点「校验当前列表」的目的就是「哪些坏了」，再让他去筛选菜单里手动挑一遍是多余的。
     */
    private final java.util.Set<String> roundFail = new java.util.LinkedHashSet<>();
    /** 是否正在只看「本轮未通过」 */
    private boolean roundFailOnly;
    /** 0.9.157：仅看「需登录」书源。命中集合在 reload / 校验收尾时重算，
     *  passFilter 只读这份内存集合 —— 绝不在 paint 的逐行判定里查库。 */
    private boolean loginOnly;
    private final java.util.Set<String> loginKeys = new java.util.HashSet<>();
    private String sortBy = "group";       // group / name / status / enabled
    private String searchKey = "";
    /** 顶栏 🔍 展开的搜索框（默认收起，Legado 顶栏三键：搜索 / 漏斗 / ⋮） */
    private boolean searchExpand;
    /** 规则调试目标源 key：该源单源校验结束时自动弹结果日志 */
    private String debugTargetKey;
    /** 排序是否反序（⋮ 排序方式二级菜单） */
    private boolean reverseSort;
    /** 快速校验响应耗时（本会话内存表）：url → 毫秒，「按响应时间」排序用 */
    static final java.util.Map<String, Long> respMs = new java.util.concurrent.ConcurrentHashMap<>();

    /** 多选态变化回调：宿主（书架首页）用它把底部全局导航让位给批量操作栏 */
    private java.util.function.Consumer<Boolean> selModeListener;
    private boolean selModeReported;

    void setOnSelModeChange(java.util.function.Consumer<Boolean> l) {
        selModeListener = l;
    }

    /** 只在状态真正翻转时通知一次：每勾选一项都会 paint()，不能每次都抖动全局导航 */
    private void notifySelModeChanged() {
        if (selModeListener == null || selModeReported == selMode) return;
        selModeReported = selMode;
        selModeListener.accept(selMode);
    }

    /** 响应时间排序键：没测过的源排最后 */
    private static long respMsOf(JSONObject o) {
        Long v = respMs.get(o.optString("bookSourceUrl", ""));
        return v == null ? Long.MAX_VALUE : v;
    }

    SourcesPanel(Activity host) {
        this.host = host;
        uiTarget = this;
    }

    /** @param embedded 嵌入首页时为 true：不留状态栏偏移、不画左上返回圆钮 */
    View view(boolean embedded) {
        ui = ModouUi.of(host);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        // 同上：嵌在首页里，纯色会把底衬的渐变盖死


        // 顶栏 + 工具栏：与列表解耦，搜索输入时只重绘列表，输入框不重建（保住焦点/光标）
        int p = ModouUi.topInset(host);
        headerBox = new LinearLayout(host);
        headerBox.setOrientation(LinearLayout.VERTICAL);
        headerBox.setPadding(p, embedded ? ModouUi.dp(host, 6) : statusBarPad(), p, 0);
        root.addView(headerBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 列表：0.9.43 从「137 个 View 全量新建的 LinearLayout」换成 ListView ——
        // 行视图按屏复用（同屏只留十几套），勾选/校验只重绑可见行，不再整表重建。
        recycler = new androidx.recyclerview.widget.RecyclerView(host);
        recycler.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(host));
        recycler.setVerticalScrollBarEnabled(false);
        // 默认动画会在「改一行」时整格闪一下：关掉 change 动画，多选勾选才不闪屏
        androidx.recyclerview.widget.DefaultItemAnimator anim =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        anim.setSupportsChangeAnimations(false);
        recycler.setItemAnimator(anim);
        // 底部浮岛压在列表上：内容底边留出它的高度，最后一项才能滚上来
        recycler.setPadding(p, ModouUi.dp(host, 2), p, ModouUi.dockFootPx(host));
        recycler.setClipToPadding(false);
        adapter = new SourceAdapter();
        recycler.setAdapter(adapter);
        dragHelper = new androidx.recyclerview.widget.ItemTouchHelper(new DragCallback());
        dragHelper.attachToRecyclerView(recycler);
        loadCheckStates();   // 上次的校验结论（状态点/失效标记）读回内存
        root.addView(recycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部批量操作栏：多选态才显示，圆角浮岛钉在列表下方（不随内容滚动）
        checkBar = buildCheckBar();
        checkBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbp.leftMargin = ModouUi.dp(host, 10);
        cbp.rightMargin = ModouUi.dp(host, 10);
        cbp.bottomMargin = ModouUi.dp(host, 6);
        root.addView(checkBar, cbp);

        reload();
        return root;
    }

    private int statusBarPad() {
        int id = host.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? host.getResources().getDimensionPixelSize(id) : ModouUi.dp(host, 24);
    }

    private void reload() {
        items.clear();
        try {
            String s = ModouStore.get(host).all("sources");
            if (s != null && !s.isEmpty()) {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) items.add(o);
                }
            }
        } catch (Exception e) {
            /* 空表 */
        }
        sortItems();
        refreshLoginKeys();
        // 保存书源后重画，滚动位置会被顶回顶部：137 个源里刚翻到的那一条又得重找。
        // 记下首行位置，重画完再滚回去（数量不变时位置依然有效）。
        int keepPos = 0;
        if (recycler != null && recycler.getLayoutManager() != null) {
            keepPos = ((androidx.recyclerview.widget.LinearLayoutManager) recycler.getLayoutManager())
                    .findFirstVisibleItemPosition();
        }
        paint();
        if (recycler != null && keepPos > 0) {
            final int pos = keepPos;
            recycler.post(() -> {
                try {
                    recycler.scrollToPosition(Math.min(pos, Math.max(0, rows.size() - 1)));
                } catch (Throwable ignore) {
                    /* 滚动失败无所谓 */
                }
            });
        }
    }

    /**
     * 排序：独立成方法，改排序方式时能立刻重排（以前只有 reload() 里排一次，
     * 菜单里点「按名称」只重画不重排，顺序纹丝不动）。
     *
     * <p>置顶 / 置底优先于一切；其余「组内排序」保持同组相邻。
     * 健康度排序是诊断视图，按全表排、不分组 —— 否则最毒的源会被压在某组末尾。
     */
    private void sortItems() {
        items.sort((x, y) -> {
            // 置顶 / 置底：钉在整张列表最前 / 最后，优先于一切排序方式与反序
            int px = x.optBoolean("pinTop", false) ? 0 : x.optBoolean("pinBottom", false) ? 2 : 1;
            int py = y.optBoolean("pinTop", false) ? 0 : y.optBoolean("pinBottom", false) ? 2 : 1;
            if (px != py) return Integer.compare(px, py);
            if (px == 0) return Long.compare(y.optLong("pinOrder", 0), x.optLong("pinOrder", 0)); // 后置顶的排更前
            if (px == 2) return Long.compare(x.optLong("pinOrder", 0), y.optLong("pinOrder", 0)); // 后置底的排更后
            final boolean flat = "custom".equals(sortBy) || isHealthSort();
            // 手动排序是全局顺序：再先按分组切块的话，跨组拖完一刷新就被拉回原组
            if (!flat) {
                int c = groupOf(x).compareToIgnoreCase(groupOf(y)); // 主：分组（保持同组相邻）
                if (c != 0) return reverseSort ? -c : c;
            }
            int c2;
            switch (sortBy) {
                case "name": c2 = nameOf(x).compareToIgnoreCase(nameOf(y)); break;
                case "update": c2 = Long.compare(y.optLong("lastModify", 0), x.optLong("lastModify", 0)); break;
                case "resp": c2 = Long.compare(respMsOf(y), respMsOf(x)); break; // 快的在前，反序翻转
                case "status": c2 = Integer.compare(statusRank(x), statusRank(y)); break;
                case "custom": c2 = Long.compare(x.optLong("customOrder", Long.MAX_VALUE),
                        y.optLong("customOrder", Long.MAX_VALUE));
                    break;
                case "enabled": c2 = Boolean.compare(y.optBoolean("enabled", true),
                        x.optBoolean("enabled", true));
                    break;
                case "health_fail":
                    // 失败率高的在前；同为 100% 时失败次数多的更靠前
                    c2 = Double.compare(failRateOf(y), failRateOf(x));
                    if (c2 == 0) c2 = Integer.compare(failCntOf(y), failCntOf(x));
                    break;
                case "health_ok":
                    c2 = Double.compare(okRateOf(y), okRateOf(x));
                    if (c2 == 0) c2 = Integer.compare(okCntOf(y), okCntOf(x));
                    break;
                case "group":
                default: c2 = nameOf(x).compareToIgnoreCase(nameOf(y));
            }
            if (c2 != 0) return reverseSort ? -c2 : c2;
            return 0;
        });
    }

    /** 健康度排序：全表平铺（不分组），让最差的源直接排到第一屏 */
    private boolean isHealthSort() {
        return "health_fail".equals(sortBy) || "health_ok".equals(sortBy);
    }

    /** 改排序方式并立刻重排（不再依赖 reload 的副作用） */
    private void applySort(String key) {
        sortBy = key;
        sortItems();
        paint();
    }

    /**
     * 分组名：全部走 {@link SourceGroup} 清洗。
     * 导入包里常见的「0905校验 / 2023年校验 / test / 备份」不再当标题显示，
     * 统一归到「导入书源」；真正为空才叫「未分组」。
     */
    private static String groupOf(JSONObject o) {
        return SourceGroup.of(o);
    }

    /* ================= 状态判定（与网页版 sources.js statusOf 同一套） ================= */

    /** 综合状态：深度校验结果优先（它比快速校验更权威） */
    private static String statusKey(String key) {
        String q = quick.get(key);
        Deep d = deep.get(key);
        if (d != null) {
            if ("run".equals(d.state)) return "drun";
            if ("ok".equals(d.state)) return "ok";
            if ("bad".equals(d.state)) {
                // 网络原因（超时/403）下不了结论：退回「未判定」，不能染红。
                // 只有「请求通了、规则抓不到」才是规则失效。
                if (d.netFail) return "skip";
                return "skip".equals(q) ? "both" : "deepbad";
            }
        }
        // 0.9.136 起快速校验不再写 bad，ruleBad 也不再置位；这里的 bad 只会来自
        // 历史落库数据或遗漏分支。一律退回「未判定」：状态点不染红，也不会被
        // 失效类筛选捞走。升级后不用重跑体检，旧版误判留下的那一片红当场消失。
        if (q != null && "bad".equals(q)) {
            if (Boolean.TRUE.equals(ruleBad.get(key))) return "both";
            return "skip";
        }
        if (q == null) return "none";
        return q;
    }

    private static boolean isCheckable(JSONObject o) {
        return !o.optString("searchUrl", "").trim().isEmpty();
    }

    /**
     * 重算「需登录」命中集合（0.9.157）：一次读体检失败流水 + 本轮内存结论，逐源走
     * {@link SourceLogin#check}。只在 reload / 校验收尾时调用；判定口径全在 SourceLogin，
     * 这里只负责把「体检原文」拼给它，做到一改全改。
     */
    private void refreshLoginKeys() {
        loginKeys.clear();
        java.util.Map<String, String> hist = SourceHealth.loginHistory(host);
        for (JSONObject o : items) {
            String histMsg = hist.get(o.optString("bookSourceUrl", ""));
            String qr = qReason.get(keyOf(o));
            String merged = (qr == null ? "" : qr) + (histMsg == null ? "" : " " + histMsg);
            if (SourceLogin.check(o, merged).level != SourceLogin.NONE) {
                loginKeys.add(keyOf(o));
            }
        }
    }

    /** 组合筛选命中判定：状态 ∩ 分组 ∩ 启用 ∩ 健康度 ∩ 关键字，维度间 AND、维度内 OR */
    private boolean passFilter(JSONObject o) {
        String key = statusKey(keyOf(o));
        if (!statusFilter.isEmpty()) {
            boolean hit = false;
            for (String s : statusFilter) {
                switch (s) {
                    case "ok": hit |= "ok".equals(key); break;
                    case "skip": hit |= "skip".equals(key); break;
                    case "none": hit |= "none".equals(key); break;
                    case "nosearch": hit |= "nosearch".equals(key); break;
                    // 兼容历史键值（菜单已不再产生，但旧数据/外部引用不至于筛出 0 条）
                    case "bad": hit |= "bad".equals(key) || "deepbad".equals(key)
                            || "both".equals(key); break;
                    case "both": hit |= "both".equals(key); break;
                    case "unck": hit |= "none".equals(key) || "nosearch".equals(key)
                            || "skip".equals(key); break;
                    default: break;
                }
            }
            if (!hit) return false;
        }
        if (!groupFilter.isEmpty() && !groupFilter.contains(groupOf(o))) return false;
        if (!enabledFilter.isEmpty()) {
            boolean en = o.optBoolean("enabled", true);
            boolean hit = (enabledFilter.contains("on") && en)
                    || (enabledFilter.contains("off") && !en);
            if (!hit) return false;
        }
        if (brokenOnly) {
            // 与行尾红色徽标、顶部「高病发 N」同一口径，三处数字必须永远对得上
            if (!isSick(o)) return false;
        }
        if (loginOnly && !loginKeys.contains(keyOf(o))) return false;
        if (roundFailOnly && !roundFail.contains(keyOf(o))) return false;
        if (!searchKey.isEmpty()) {
            String hay = (o.optString("bookSourceName", "") + " " + o.optString("bookSourceUrl", "")
                    + " " + o.optString("bookSourceGroup", "") + " " + groupOf(o)).toLowerCase();
            if (!hay.contains(searchKey.toLowerCase())) return false;
        }
        return true;
    }

    private static String nameOf(JSONObject o) {
        String n = o.optString("bookSourceName", "").trim();
        return n.isEmpty() ? o.optString("bookSourceUrl", "") : n;
    }

    private static int statusRank(JSONObject o) {
        switch (statusKey(keyOf(o))) {
            case "ok": return 0;
            case "none": case "nosearch": return 1;
            case "skip": return 2;
            case "bad": case "deepbad": return 3;
            case "both": return 4;
            default: return 5;
        }
    }

    private int stateColor(String key) {
        switch (key) {
            case "ok": return 0xFF5E6B5A;       // 网页版有效绿
            case "bad": case "deepbad": case "both": return 0xFFA8433A; // 网页版失效红
            case "run": case "drun": return ui.accent;
            default: return 0xFFA79C84;         // 未校验灰
        }
    }

    private String stateLabel(String key) {
        switch (key) {
            case "ok": return "有效";
            case "bad": return "失效";
            case "deepbad": return "深度失效";
            case "both": return "规则失效";
            case "skip": return "未判定";
            case "nosearch": return "无搜索";
            case "run": return "校验中";
            case "drun": return "深度校验中";
            default: return "未校验";
        }
    }

    /** 行副行文案：状态 · 能力标签 · 失效原因 */
    private String buildMeta(JSONObject o) {
        String url = o.optString("bookSourceUrl", "");
        String key = statusKey(keyOf(o));
        StringBuilder sb = new StringBuilder(stateLabel(key));
        if ("none".equals(key) && !isCheckable(o)
                && o.optString("exploreUrl", "").trim().isEmpty()) {
            sb.append("（未配置搜索）");
        }
        StringBuilder caps = new StringBuilder();
        if (!o.optString("searchUrl", "").isEmpty()) caps.append("搜索");
        if (!o.optString("exploreUrl", "").isEmpty()) caps.append(caps.length() > 0 ? " · 发现" : "发现");
        if (caps.length() > 0) sb.append(" · ").append(caps);
        // 0.9.37 卡片瘦身（Legado 同款）：行内不再铺失效原因长文 —— 失败原因
        // 挪到单源「规则调试」弹窗和健康度详情里看，列表行只留 状态 · 能力 · 分组。
        sb.append(" · ").append(groupOf(o));
        return sb.toString();
    }

    /* ================= 健康度：列表可视化 ================= */

    /** 近 7 天失败率的阈值：≥80% 且样本足够，才算「高病发」 */
    private static final double SICK_RATE = 0.8;
    /** 成功率 ≥90% 且近期成功过，才算「稳定」 */
    private static final double OK_RATE = 0.9;

    private JSONObject healthOf(JSONObject o) {
        return health.get(o.optString("bookSourceUrl", ""));
    }

    private int okCntOf(JSONObject o) {
        JSONObject h = healthOf(o);
        return h == null ? 0 : h.optInt("okCnt", 0);
    }

    private int failCntOf(JSONObject o) {
        JSONObject h = healthOf(o);
        return h == null ? 0 : h.optInt("failCnt", 0);
    }

    /** 失败率 0..1；没有样本返回 -1（「没搜过」不能算成「零失败」，排序时沉底） */
    private double failRateOf(JSONObject o) {
        int ok = okCntOf(o), fail = failCntOf(o), total = ok + fail;
        return total == 0 ? -1 : (double) fail / total;
    }

    private double okRateOf(JSONObject o) {
        int ok = okCntOf(o), fail = failCntOf(o), total = ok + fail;
        return total == 0 ? -1 : (double) ok / total;
    }

    /** 高病发：近 7 天失败率 ≥80%，且样本不少于 {@link SourceHealth#MIN_SAMPLE} */
    private boolean isSick(JSONObject o) {
        int total = okCntOf(o) + failCntOf(o);
        return total >= SourceHealth.MIN_SAMPLE && failRateOf(o) >= SICK_RATE;
    }

    /** 稳定：成功率 ≥90% 且近 7 天真的成功过（只有历史成功、近期全挂的不算） */
    private boolean isHealthy(JSONObject o) {
        int ok = okCntOf(o), fail = failCntOf(o);
        if (ok + fail < SourceHealth.MIN_SAMPLE || okRateOf(o) < OK_RATE) return false;
        JSONObject h = healthOf(o);
        long lastOk = h == null ? 0 : h.optLong("lastOk", 0);
        return lastOk > 0 && System.currentTimeMillis() - lastOk <= 7L * 24 * 3600 * 1000;
    }

    /** 全表高病发数量（不受当前筛选影响，给顶部统计用） */
    private int sickCount() {
        int n = 0;
        for (JSONObject o : items) if (isSick(o)) n++;
        return n;
    }

    /** Diff 用的健康度签名：徽标文字变了（83% → 100%）也要重绑这一行 */
    private String healthTag(JSONObject o) {
        if (isSick(o)) return "sick" + Math.round(failRateOf(o) * 100);
        if (isHealthy(o)) return "ok" + Math.round(okRateOf(o) * 100);
        return "";
    }

    /**
     * 行尾健康度徽标（复用同一个 View，不每行新建）：红=高病发，绿=稳定。
     * 没有足够样本就 GONE —— 每个源都挂一枚「0%」只会把副标题挤满，
     * 反而看不出谁真有问题。
     */
    private void bindHealthBadge(TextView v, JSONObject o) {
        if (v == null) return;
        boolean sick = isSick(o);
        boolean healthy = !sick && isHealthy(o);
        if (!sick && !healthy) {
            v.setVisibility(View.GONE);
            return;
        }
        int pct = (int) Math.round((sick ? failRateOf(o) : okRateOf(o)) * 100);
        int color = sick ? (ui.dark ? 0xFFE09086 : 0xFFA8433A)
                : (ui.dark ? 0xFF9FB49A : 0xFF5E6B5A);
        v.setVisibility(View.VISIBLE);
        v.setText((sick ? "失败 " : "✓ ") + pct + "%");
        v.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ModouUi.alpha(color, sick ? 0x1F : 0x16));
        bg.setCornerRadius(ModouUi.dp(host, 999));
        v.setBackground(bg);
        v.setPadding(ModouUi.dp(host, 6), ModouUi.dp(host, 1),
                ModouUi.dp(host, 6), ModouUi.dp(host, 1));
    }

    /* ================= 健康度 ================= */

    /**
     * 健康度详情：统计 + 最近 5 次失败原文 + 重置。
     *
     * <p>0.9.37 起入口在单源详情面板（列表卡片瘦身后不再铺健康度小字）。
     */
    private void openHealthSheet(final String url, String name) {
        final JSONObject h = SourceHealth.get(host, url);
        final int okN = h == null ? 0 : h.optInt("okCnt", 0);
        final int failN = h == null ? 0 : h.optInt("failCnt", 0);
        final int total = okN + failN;
        final long lastOk = h == null ? 0 : h.optLong("lastOk", 0);
        final long lastFail = h == null ? 0 : h.optLong("lastFail", 0);
        final String errKind = h == null ? "" : h.optString("errKind", "");
        final String errMsg = h == null ? "" : h.optString("errMsg", "");
        final java.util.List<String[]> fails = SourceHealth.recentFails(host, url, 5);

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, ModouUi.dp(host, 2), 0, 0);

        box.addView(healthKv("近 7 天", total == 0 ? "还没有记录"
                : okN + " 成功 · " + failN + " 失败（成功率 "
                + Math.round(okN * 100f / total) + "%）"));
        box.addView(healthKv("最近成功", lastOk > 0 ? ago(lastOk) : "近 7 天没成功过"));
        box.addView(healthKv("最近失败", lastFail > 0 ? ago(lastFail) : "没有失败记录"));
        if (!errKind.isEmpty()) {
            box.addView(healthKv("主要问题", errKind + (errMsg.isEmpty() ? "" : "：" + errMsg)));
        }
        if (total > 0) {
            // 文字趋势：一眼看出「越来越差」还是「偶发」
            int barN = 12;
            int okBar = Math.round(okN * barN / (float) total);
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < barN; i++) t.append(i < okBar ? '▮' : '▯');
            box.addView(healthKv("趋势", t + "  成功 " + okN + " / 共 " + total));
        }

        TextView cap = ModouUi.tv(host, fails.isEmpty() ? "最近 5 条诊断记录：暂无"
                : "最近 5 条诊断记录（体检探测不计入上方成功率）", 12, ui.sub);
        cap.setPadding(0, ModouUi.dp(host, 12), 0, ModouUi.dp(host, 4));
        box.addView(cap);
        if (fails.isEmpty()) {
            TextView tip = ModouUi.tv(host, "这个源最近没有失败或体检记录，或者已过期被清理。", 11.5f, ui.sub);
            box.addView(tip);
        }
        for (String[] f : fails) {
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 6));
            // 体检探测只留诊断原文、不写失败计数，明确标出来，别和上方「近 7 天」计数打架
            String kind = SourceHealth.KIND_CHECK.equals(f[1]) ? "体检 · 未下结论" : f[1];
            TextView head = ModouUi.tv(host, ago(Long.parseLong(f[0])) + " · " + kind, 12, ui.text);
            row.addView(head);
            TextView body = ModouUi.tv(host, f[2].isEmpty() ? "（没有错误详情）" : f[2], 11, ui.sub);
            body.setLineSpacing(ModouUi.dp(host, 1), 1.15f);
            row.addView(body);
            box.addView(row);
            box.addView(ModouUi.groupDivider(host));
        }

        LinearLayout footer = new LinearLayout(host);
        footer.setOrientation(LinearLayout.VERTICAL);
        final Dialog d = ModouUi.sheetForm(host, "健康度 · " + name, box, footer);

        TextView reset = ModouUi.tv(host, "重置该源健康度", 14, ui.danger);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, ModouUi.dp(host, 14), 0, ModouUi.dp(host, 8));
        reset.setOnClickListener((v) -> ModouUi.confirm(host, "重置健康度",
                "清掉这个源的全部成功/失败统计和失败记录，相当于重新开始观察。\n\n"
                        + "不会改动书源规则，也不会改变启用状态。", () -> {
                            SourceHealth.reset(host, url);
                            health.remove(url);
                            ModouUi.toast(host, "已重置");
                            d.dismiss();
                            paint();
                        }));
        footer.addView(reset);

        TextView close = ModouUi.tv(host, "关闭", 14, ui.sub);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, ModouUi.dp(host, 10), 0, ModouUi.dp(host, 4));
        close.setOnClickListener((v) -> d.dismiss());
        footer.addView(close);
    }

    /** 详情面板里的一行「标签 —— 值」，标签定宽，值自动换行 */
    private View healthKv(String k, String v) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ModouUi.dp(host, 4), 0, ModouUi.dp(host, 4));
        TextView kt = ModouUi.tv(host, k, 12, ui.sub);
        kt.setWidth(ModouUi.dp(host, 64));
        row.addView(kt);
        TextView vt = ModouUi.tv(host, v, 12.5f, ui.text);
        vt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        vt.setLineSpacing(ModouUi.dp(host, 1), 1.15f);
        row.addView(vt);
        return row;
    }

    private static String ago(long ts) {
        long d = System.currentTimeMillis() - ts;
        if (d < 0) d = 0;
        if (d < 60_000L) return "刚刚";
        if (d < 3_600_000L) return (d / 60_000L) + " 分钟前";
        if (d < 86_400_000L) return (d / 3_600_000L) + " 小时前";
        return (d / 86_400_000L) + " 天前";
    }

    /** 一键禁用：只禁用「近 7 天完全没搜到、失败 ≥2 次，且失败原因都是规则失效」的源 */
    private void confirmDisableBroken() {
        final java.util.List<String> urls = SourceHealth.brokenUrls(host);
        final int n = urls.size();
        if (n == 0) {
            ModouUi.toast(host, "近 7 天没有「规则失效」的源"
                    + "（规则失效少于 " + SourceHealth.MIN_SAMPLE + " 次的源不算）");
            return;
        }
        // 点名前三个：这个数字是「近 7 天累计」，跟刚跑完那轮校验不是一回事，
        // 报出名字用户才能自己核对「里面有没有我刚删的那两个」。
        StringBuilder who = new StringBuilder();
        int shown = 0;
        for (JSONObject o : items) {
            if (shown >= 3) break;
            if (!urls.contains(o.optString("bookSourceUrl", ""))) continue;
            if (shown > 0) who.append("、");
            who.append(nameOf(o));
            shown++;
        }
        String names = who.length() == 0 ? "" : "其中：" + who
                + (n > shown ? " 等 " + n + " 个" : "") + "\n\n";
        ModouUi.confirm(host, "禁用坏源",
                names + "将禁用 " + n + " 个「近 7 天一次都没搜到、失败至少 "
                        + SourceHealth.MIN_SAMPLE + " 次、且失败原因都是规则失效"
                        + "（规则未匹配 / 正则报错）」的书源。\n\n"
                        + "只失败过一次的、以及因超时 / 403 / 断网等网络问题失败的不会动"
                        + "（可能只是当时网络抖了一下）。"
                        + "禁用的源仍保留在列表里，随时可以重新打开。",
                () -> {
                    int done = SourceHealth.disableAllBroken(host);
                    ModouUi.toast(host, done > 0 ? "已禁用 " + done + " 个书源" : "没有需要禁用的书源");
                    // 批量禁用直接写库，内存里的 items 还是旧的：不 reload 开关不会关
                    reload();
                });
    }

    /* ================= 列表 ================= */

    /** 全量重画：顶栏重建（两三个视图）+ 列表数据重算（只重绑可见行） */
    private void paint() {
        paintHeader();
        paintList();
    }

    /** 只重画顶栏与工具栏的部分 */
    private void paintHeader() {
        headerBox.removeAllViews();
        health = SourceHealth.all(host);

        /* 顶栏：标题 + 计数（左） · [+]新建 + [┇]菜单（右） */
        LinearLayout h = new LinearLayout(host);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        if (!embedded()) {
            h.addView(ModouUi.backBtn(host, () -> host.finish()));
        }
        int iconColor = ModouUi.alpha(ui.text, 0xB3);
        // 40dp 圆钮逐个排：两枚并排时留 10dp 缝，既不粘连也不散成两簇。
        // 第三枚（漏斗）已删除 —— 它的全部能力都并进了 ⋮ 的级联菜单，
        // 同一个筛选留两个入口只会让右上角越堆越挤。
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ModouUi.dp(host, 40), ModouUi.dp(host, 40));
        ilp.leftMargin = ModouUi.dp(host, 10);

        if (selMode) {
            /* 多选态顶栏（Contextual Action Bar）：[X] 已选 N 项 [全选]。
               搜索 / ⋮ 在多选态整体让位 —— 批量操作的入口全部集中在
               底部升起的那条操作栏上，顶栏不再和它抢注意力。 */
            ImageView close = ModouUi.glyphBtn(host,
                    new ModouGlyph(ModouGlyph.CLOSE, iconColor,
                            ModouUi.dp(host, 15), ModouUi.dp(host, 1.6f)), 40, "退出多选");
            close.setOnClickListener((v) -> exitSelection());
            h.addView(close, ilp);
            TextView cnt = ModouUi.tv(host, "已选 " + sel.size() + " 项", 16, ui.text);
            cnt.setGravity(Gravity.CENTER);
            cnt.setTypeface(cnt.getTypeface(), android.graphics.Typeface.BOLD);
            cnt.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            h.addView(cnt);
            cabCount = cnt;
            TextView all = ModouUi.tv(host, allVisibleSelected() ? "取消全选" : "全选", 14, ui.accent);
            all.setPadding(ModouUi.dp(host, 10), ModouUi.dp(host, 8), 0, ModouUi.dp(host, 8));
            all.setOnClickListener((v) -> toggleSelectAll());
            h.addView(all);
            cabSelectAll = all;
        } else {
            LinearLayout tt = new LinearLayout(host);
            tt.setOrientation(LinearLayout.VERTICAL);
            tt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView t = ModouUi.pageTitle(host, "书源管理");
            tt.addView(t);
            int on = 0;
            for (JSONObject o : items) if (o.optBoolean("enabled", true)) on++;
            status = ModouUi.tv(host, items.isEmpty() ? "还没有书源"
                    : "共 " + items.size() + " 个 · 启用 " + on + " 个"
                        + (checkRunning() && !progressText.isEmpty() ? " · " + progressText : ""),
                    12, ui.sub);
            status.setSingleLine(true);
            status.setEllipsize(TextUtils.TruncateAt.END);

            // 副标题右侧挂一枚「高病发 N」：一眼看到全表有多少毒瘤，点一下直接筛出来。
            // 放在同一行而不是另起一行 —— 标题区只有两行，多一行会把列表顶下去。
            LinearLayout srow = new LinearLayout(host);
            srow.setOrientation(LinearLayout.HORIZONTAL);
            srow.setGravity(Gravity.CENTER_VERTICAL);
            srow.setPadding(0, ModouUi.dp(host, 2), 0, 0);
            srow.addView(status, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            final int sick = items.isEmpty() ? 0 : sickCount();
            if (sick > 0) {
                TextView chip = ModouUi.tv(host, (brokenOnly ? "已筛出 " : "高病发源 ") + sick,
                        10.5f, brokenOnly ? 0xFFFFFFFF : 0xFFA8433A);
                chip.setGravity(Gravity.CENTER);
                chip.setSingleLine(true);
                GradientDrawable cbg = new GradientDrawable();
                cbg.setColor(brokenOnly ? 0xFFA8433A : ModouUi.alpha(0xFFA8433A, 0x1A));
                cbg.setCornerRadius(ModouUi.dp(host, 999));
                chip.setBackground(cbg);
                chip.setPadding(ModouUi.dp(host, 7), ModouUi.dp(host, 2),
                        ModouUi.dp(host, 7), ModouUi.dp(host, 2));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.leftMargin = ModouUi.dp(host, 8);
                srow.addView(chip, clp);
                // 再点一次取消：同一个入口做「筛出 / 撤销」，不额外占一个清除按钮
                chip.setOnClickListener((v) -> {
                    if (brokenOnly) {
                        brokenOnly = false;
                    } else {
                        groupFilter.clear();
                        statusFilter.clear();
                        enabledFilter.clear();
                        searchKey = "";
                        brokenOnly = true;
                        skipOnly = false;
                    }
                    paint();
                });
            }
            tt.addView(srow);
            h.addView(tt);

            // 顶栏两键：搜索 / 全局菜单。
            // 「新建书源」「排序」「筛选」全部收进 ⋮ —— 顶栏只留最高频的两个动作。
            ImageView searchBtn = ModouUi.topBtn(host, ModouGlyph.SEARCH, 40, (v) -> {
                searchExpand = !searchExpand;
                paint();
            });
            h.addView(searchBtn, ilp);

            // ⋮ 全局菜单：校验当前列表 / 本地·网络导入 / 分组管理 / 排序 / 筛选 / 新建书源
            ImageView more = ModouUi.topBtn(host, ModouGlyph.MORE_V, 40,
                    (v) -> showTopMenu(v));
            h.addView(more, ilp);
        }
        headerBox.addView(h);

        /* 工具栏：搜索折叠框 + 已激活条件 chip（多选态顶栏已换成 CAB，工具栏同样让位） */
        if (!selMode) headerBox.addView(toolbar());
    }

    /**
     * 列表渲染（与顶栏分离，可单独重绘以保留搜索框焦点）。
     *
     * <p>0.9.43：行模型重建（纯数据，137 条也就一次遍历）→ notifyDataSetChanged，
     * ListView 只重绑**可见的那十几行**，不复用的旧表是整表 new 出 137 套 View。
     */
    private void paintList() {
        if (recycler == null) return;
        // 底部操作栏升起时全局导航已让位：浮岛 ≈78dp，再加 24dp 余量
        recycler.setPadding(recycler.getPaddingLeft(), recycler.getPaddingTop(),
                recycler.getPaddingRight(),
                ModouUi.dockFootPx(host) + (selMode ? ModouUi.dp(host, 24) : 0));
        submitRows();
        // 选中项变了，底栏「启用/禁用」文案跟着变
        if (switchLabel != null) switchLabel.setText(toggleLabel());

        // 底部操作栏：多选态平滑升起（滑上 + 淡入），退出即落回
        boolean show = selMode;
        boolean wasVisible = checkBar.getVisibility() == View.VISIBLE;
        checkBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && !wasVisible) {
            checkBar.setAlpha(0f);
            checkBar.setTranslationY(ModouUi.dp(host, 28));
            checkBar.animate().alpha(1f).translationY(0f)
                    .setDuration(210)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                    .start();
        } else if (!show) {
            checkBar.setAlpha(1f);
            checkBar.setTranslationY(0f);
        }
        // 多选态翻转后告诉宿主：全局导航要让位 / 回归
        notifySelModeChanged();
    }

    /** 行模型：把 items（已排序）摊平成「组标题 + 行」序列（纯数据，不动任何 View） */
    private List<Row> buildRows() {
        List<Row> out = new ArrayList<>();
        String lastGroup = null;
        List<Row> tail = null;   // 当前组已产出的行，回填首/末行标记（决定圆角与分隔线）
        int shown = 0;
        boolean flat = "custom".equals(sortBy) || isHealthSort();   // 手动排序 / 健康度诊断：不分组，整张表一张卡
        for (int i = 0; i < items.size(); i++) {
            JSONObject o = items.get(i);
            if (!passFilter(o)) continue;
            shown++;
            String g = o.optBoolean("pinTop", false) ? "置顶"
                    : o.optBoolean("pinBottom", false) ? "置底" : groupOf(o);
            if (!flat && !g.equals(lastGroup)) {
                lastGroup = g;
                if (tail != null && !tail.isEmpty()) {
                    tail.get(0).first = true;
                    tail.get(tail.size() - 1).last = true;
                }
                out.add(Row.header(g));
                tail = new ArrayList<>();
            }
            Row r = Row.item(o, flat ? "" : g);
            r.health = healthTag(o);
            r.selected = sel.contains(o.optString("id", ""));
            r.selMode = selMode;
            out.add(r);
            if (tail == null) tail = new ArrayList<>();
            tail.add(r);
        }
        if (tail != null && !tail.isEmpty()) {
            tail.get(0).first = true;
            tail.get(tail.size() - 1).last = true;
        }
        if (shown == 0) {
            out.add(Row.empty(items.isEmpty()
                    ? "还没有书源，用右上角 ⋮ 里的「新建书源」或导入。"
                    : "当前筛选没有命中的书源。"));
        }
        return out;
    }

    /** 书源 key → 行下标 */
    private void rebuildIndex(List<Row> list) {
        rowIndex.clear();
        for (int i = 0; i < list.size(); i++) {
            Row r = list.get(i);
            if (r.kind == Row.ITEM) rowIndex.put(r.key, i);
        }
    }

    /** 单行就地重绑（不惊动整表）：勾选走 payload，只换背景和勾选框，文字不动 → 零闪屏 */
    private void updateRowAt(int pos) {
        if (adapter == null || pos < 0 || pos >= rows.size()) return;
        adapter.notifyItemChanged(pos, PAYLOAD_SEL);
    }

    /** 状态点 / 失效结论变化：只重绑这一行（不在屏上由 RV 自己等到滚回来再绑） */
    private void updateRowStatus(int pos) {
        if (adapter == null || pos < 0 || pos >= rows.size()) return;
        adapter.notifyItemChanged(pos, PAYLOAD_STATUS);
    }

    /** 数据层重算 + DiffUtil 分发：只有真正变了的那些行会重绑 */
    private void submitRows() {
        List<Row> next = buildRows();
        final List<Row> old = new ArrayList<>(rows);
        androidx.recyclerview.widget.DiffUtil.DiffResult diff =
                androidx.recyclerview.widget.DiffUtil.calculateDiff(new RowDiff(old, next), false);
        rows.clear();
        rows.addAll(next);
        rebuildIndex(next);
        diff.dispatchUpdatesTo(adapter);
    }

    /** 按书源定位行号（校验回调只有 JSONObject，需要反查位置） */
    private int rowPosOf(JSONObject o) {
        Integer p = rowIndex.get(keyOf(o));
        return p == null ? -1 : p;
    }

    private boolean embedded() {
        return host instanceof ShelfActivity;
    }

    /**
     * 浏览态工具栏：可折叠搜索框 + 已激活条件 chip。
     * 0.9.37 重构（Legado 同款）：搜索框默认收起，由顶栏 🔍 展开；
     * 筛选与排序全部收在 ⋮ 菜单 —— 工具栏只留「已激活条件」的摘要。
     */
    private View toolbar() {
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, ModouUi.dp(host, 4), 0, 0);

        if (searchExpand || !searchKey.isEmpty()) {
            final EditText searchEd = ModouUi.searchField(host, "搜索书源名称 / 地址 / 分组");
            searchEd.setText(searchKey);
            searchEd.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(android.text.Editable e) {
                    searchKey = String.valueOf(e).trim();
                    paintList();
                }
            });
            col.addView(searchEd);
        }

        LinearLayout act = new LinearLayout(host);
        act.setOrientation(LinearLayout.HORIZONTAL);
        act.setGravity(Gravity.CENTER_VERTICAL);
        act.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 8));
        if (!groupFilter.isEmpty()) act.addView(condChip("分组：" + joinSet(groupFilter), () -> {
            groupFilter.clear(); paint();
        }));
        if (!statusFilter.isEmpty()) act.addView(condChip("状态：" + joinStatus(statusFilter), () -> {
            statusFilter.clear(); paint();
        }));
        if (!enabledFilter.isEmpty()) act.addView(condChip("启用：" + joinEnabled(enabledFilter), () -> {
            enabledFilter.clear(); paint();
        }));
        if (brokenOnly) act.addView(condChip("高失败源 ≥80%", () -> {
            brokenOnly = false; paint();
        }));
        if (loginOnly) act.addView(condChip("仅需登录 " + loginKeys.size(), () -> {
            loginOnly = false; paint();
        }));
        if (roundFailOnly) act.addView(condChip("本轮未通过 " + roundFail.size(), () -> {
            roundFailOnly = false; paint();
        }));
        if (!searchKey.isEmpty()) act.addView(condChip("搜索：" + searchKey, () -> {
            searchKey = ""; paint();
        }));
        col.addView(act);
        return col;
    }

    /** 已激活条件 chip：点一下清除该条件。
     *  0.9.173：iOS / M3 走统一 {@link ModouUi#chip}（胶囊 / 描边 chip 的选中态），
     *  墨读保留原来的「白卡 + 11.5sp 次级灰」—— 那是它自己的层级表达（卡片浮在灰页上），
     *  换成 6% 墨色会变成另一种语言，故按 chipStyle 分流而不是一把改掉。 */
    private TextView condChip(String label, Runnable clear) {
        if (ui.spec.chipStyle != 0) {
            return ModouUi.chip(host, "✕ " + label, true, v -> clear.run());
        }
        TextView c = ModouUi.tv(host, "✕ " + label, 11.5f, ui.sub);
        c.setGravity(Gravity.CENTER);
        c.setPadding(ModouUi.dp(host, 9), ModouUi.dp(host, 5), ModouUi.dp(host, 9), ModouUi.dp(host, 5));
        GradientDrawable g = new GradientDrawable();
        g.setColor(ui.card);
        g.setCornerRadius(ModouUi.dp(host, 999));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = ModouUi.dp(host, 6);
        c.setLayoutParams(lp);
        c.setOnClickListener((v) -> clear.run());
        return c;
    }

    /** 清空「状态」维度的全部条件（启用/停用、失效、高失败率、本轮未通过），分组与搜索词不动 */
    private void clearStatusFilters() {
        statusFilter.clear();
        enabledFilter.clear();
        brokenOnly = false;
        skipOnly = false;
        roundFailOnly = false;
        loginOnly = false;
    }

    private int activeFilterCount() {
        int n = 0;
        if (!groupFilter.isEmpty()) n++;
        if (!statusFilter.isEmpty()) n++;
        if (!enabledFilter.isEmpty()) n++;
        if (brokenOnly) n++;
        if (loginOnly) n++;
        if (roundFailOnly) n++;
        if (!searchKey.isEmpty()) n++;
        return n;
    }

    /** 集合标签：≤2 项直接列出，超过则显示数量，避免把工具条挤爆 */
    private static String joinSet(java.util.Set<String> s) {
        if (s.size() > 2) return s.size() + " 项";
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) sb.append("、");
            sb.append(x);
        }
        return sb.toString();
    }

    private String joinStatus(java.util.Set<String> s) {
        if (s.size() > 2) return s.size() + " 项";
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) sb.append("、");
            sb.append(statusLabel(x));
        }
        return sb.toString();
    }

    private static String joinEnabled(java.util.Set<String> s) {
        if (s.size() > 1) return "启用+禁用";
        return s.contains("on") ? "仅启用" : "仅禁用";
    }

    private String statusLabel(String k) {
        switch (k) {
            case "ok": return "有效";
            case "skip": return "未判定";
            case "none": case "unck": return "未校验";
            case "nosearch": return "无搜索";
            case "bad": return "失效";
            case "both": return "规则失效";
            default: return "全部";
        }
    }

    private String sortLabel() {
        switch (sortBy) {
            case "name": return "名称";
            case "update": return "更新时间";
            case "resp": return "响应时间";
            case "status": return "状态";
            case "enabled": return "启用状态";
            case "health_fail": return "失败率";
            case "health_ok": return "成功率";
            case "custom": return "手动（可拖拽）";
            case "group":
            default: return "分组";
        }
    }

    /**
     * 切到手动排序：先把当前顺序固化成 customOrder，否则第一次拖拽前
     * 整表 order 都是缺省值，一拖就会「按存储顺序」弹回原位。
     */
    private void switchToCustomSort() {
        sortBy = "custom";
        reverseSort = false;
        persistOrderQuietly();
        ModouUi.toast(host, "已切到手动排序：长按书源可上下拖动");
        paint();
    }

    /** 写 customOrder 但不弹提示（切模式时用） */
    private void persistOrderQuietly() {
        for (int i = 0; i < items.size(); i++) {
            try {
                JSONObject o = items.get(i);
                o.put("customOrder", i);
                ModouStore.get(host).put("sources", o.toString());
            } catch (Exception ignore) {
                /* 不可能 */
            }
        }
    }

    /** 当前是否处于多选态：书架页的返回键 / 侧滑闸门都要先问一句 */
    boolean inSelMode() {
        return selMode;
    }

    /** 退出多选：只清选择、恢复浏览工具条，不动任何书源数据 */
    void exitSelection() {
        if (!selMode) return;
        selMode = false;
        sel.clear();
        paint();
    }

    /** 可见项是否已全部勾选：决定顶栏右上是「全选」还是「取消全选」 */
    private boolean allVisibleSelected() {
        int n = 0;
        for (JSONObject o : items) {
            if (!passFilter(o)) continue;
            n++;
            if (!sel.contains(o.optString("id", ""))) return false;
        }
        return n > 0;
    }

    /** 顶栏右上：全选 ↔ 取消全选（两项行为互斥，不再做「部分选中就取反」的隐式操作） */
    private void toggleSelectAll() {
        if (allVisibleSelected()) {
            sel.clear();
            paint();
            return;
        }
        for (JSONObject o : items) {
            if (passFilter(o)) sel.add(o.optString("id", ""));
        }
        paint();
    }

    /* ================= 行模型 / DiffUtil / 适配器（RecyclerView） ================= */

    /** 局部刷新标记：勾选只换背景与勾选框，状态只换状态点与副行 —— 都不重设文字，不闪 */
    private static final String PAYLOAD_SEL = "sel";
    private static final String PAYLOAD_STATUS = "status";

    /** 一行：要么是组标题，要么是一个书源（kind 决定 ViewHolder 种类） */
    private static final class Row {
        static final int HEADER = 0, ITEM = 1, EMPTY = 2;
        final int kind;
        final String group;
        final JSONObject src;
        final String key;         // 书源键（ITEM 才有）：DiffUtil 比较同一行的依据
        boolean first, last;      // 组内首 / 末行：决定圆角与底部分隔线
        boolean selected;
        String status;            // 状态键（ok / bad / ...）
        String health;            // 健康度签名（sick83 / ok100 / 空）：徽标变了要重绑
        boolean enabled;
        boolean selMode;          // 建行时的模式：决定右侧是开关三件套还是勾选框

        private Row(int kind, String group, JSONObject src) {
            this.kind = kind;
            this.group = group;
            this.src = src;
            this.key = src == null ? "" : keyOf(src);
        }

        static Row header(String g) { return new Row(HEADER, g, null); }

        static Row item(JSONObject o, String g) {
            Row r = new Row(ITEM, g, o);
            r.status = statusKey(keyOf(o));
            r.enabled = o.optBoolean("enabled", true);
            return r;
        }

        static Row empty(String text) { return new Row(EMPTY, text, null); }
    }

    /** 行视图缓存：复用时**所有**可变状态都必须在 bind 里重设，
     *  任何一处漏设都会让滚出屏幕的行带着上一个书源的状态回来。 */
    private static final class Holder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        Row row;
        JSONObject src;
        TextView name;
        TextView meta;
        TextView badge;               // 行尾健康度徽标（红=高病发 / 绿=稳定），无样本时 GONE
        View dot;
        View divider;
        View itemBadge;               // 0.9.168 行首数据驱动字母块（墨读为 null）
        LinearLayout browseActions;   // 浏览态右侧：开关 + 铅笔 + ⋮
        View swRow;
        FrameLayout selBox;           // 多选态右侧：勾选框
        ImageView selCheck;
        ImageView handle;             // 手动排序模式的拖拽手柄
        View root;

        Holder(View v) { super(v); }
    }

    /** DiffUtil：只把「真变了的行」交给 onBind，勾选 129 个源也只有一行被重绑 */
    private final class RowDiff extends androidx.recyclerview.widget.DiffUtil.Callback {
        private final List<Row> oldList, newList;

        RowDiff(List<Row> o, List<Row> n) { oldList = o; newList = n; }

        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int o, int n) {
            Row a = oldList.get(o), b = newList.get(n);
            if (a.kind != b.kind) return false;
            if (a.kind == Row.ITEM) return a.key.equals(b.key);
            return eq(a.group, b.group);
        }

        @Override
        public boolean areContentsTheSame(int o, int n) {
            Row a = oldList.get(o), b = newList.get(n);
            if (a.kind != Row.ITEM) return eq(a.group, b.group);
            return a.selected == b.selected && a.enabled == b.enabled
                    && a.first == b.first && a.last == b.last && a.selMode == b.selMode
                    && eq(a.status, b.status) && eq(a.group, b.group)
                    && eq(a.health, b.health);
        }

        @Override
        public Object getChangePayload(int o, int n) {
            Row a = oldList.get(o), b = newList.get(n);
            if (a.kind != Row.ITEM) return null;
            if (a.selMode != b.selMode) return null;   // 模式变了必须整行重绑
            boolean onlySel = a.selected != b.selected
                    && a.enabled == b.enabled && eq(a.status, b.status)
                    && a.first == b.first && a.last == b.last && eq(a.group, b.group);
            if (onlySel) return PAYLOAD_SEL;
            boolean onlyStatus = !eq(a.status, b.status) && a.selected == b.selected
                    && a.enabled == b.enabled && a.first == b.first && a.last == b.last;
            if (onlyStatus) return PAYLOAD_STATUS;
            return null;
        }

        private boolean eq(String x, String y) {
            return x == null ? y == null : x.equals(y);
        }
    }

    private final class SourceAdapter
            extends androidx.recyclerview.widget.RecyclerView.Adapter<Holder> {

        @Override
        public int getItemViewType(int p) { return rows.get(p).kind; }

        @Override
        public int getItemCount() { return rows.size(); }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            if (type == Row.HEADER) return new Holder(newHeaderView());
            if (type == Row.EMPTY) return new Holder(newEmptyView());
            return newRowHolder();
        }

        @Override
        public void onBindViewHolder(Holder h, int p) { bindRow(h, rows.get(p), null); }

        @Override
        public void onBindViewHolder(Holder h, int p, List<Object> payloads) {
            Object pl = (payloads == null || payloads.isEmpty()) ? null : payloads.get(0);
            bindRow(h, rows.get(p), pl instanceof String ? (String) pl : null);
        }
    }

    private TextView newHeaderView() {
        TextView t = ModouUi.tv(host, "", 15, ui.sub);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(ModouUi.dp(host, 8), ModouUi.dp(host, 22),
                ModouUi.dp(host, 8), ModouUi.dp(host, 8));
        return t;
    }

    private TextView newEmptyView() {
        TextView t = ModouUi.tv(host, "", 13, ui.sub);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ModouUi.dp(host, 28), 0, ModouUi.dp(host, 28));
        return t;
    }

    /** 行视图只在这里 new 一次，之后全靠复用（同屏十几套，而不是 137 套） */
    private Holder newRowHolder() {
        LinearLayout wrap = new LinearLayout(host);
        wrap.setOrientation(LinearLayout.VERTICAL);
        Holder h = new Holder(wrap);
        // 0.9.168：M3 每个书源一张独立 filled card，靠纵向间距分开；iOS / 墨读
        // 仍是连续分组卡，间距 0。margin 只加在 ITEM 行上（HEADER/EMPTY 另有视图）。
        androidx.recyclerview.widget.RecyclerView.LayoutParams wlp =
                new androidx.recyclerview.widget.RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (ui.spec.itemCardGapDp > 0) {
            wlp.bottomMargin = ModouUi.dp(host, ui.spec.itemCardGapDp);
        }
        wrap.setLayoutParams(wlp);
        h.root = wrap;

        LinearLayout top = new LinearLayout(host);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ModouUi.dp(host, 10), ModouUi.dp(host, 7),
                ModouUi.dp(host, 10), ModouUi.dp(host, 4));

        // 0.9.168 行首字母块：数据驱动（首字 + 名称 hash 着色），墨读 create 返回 null
        // 时一行都不加，连一个空 View 都不留，保证逐像素零回归。
        h.itemBadge = ItemBadge.create(host, ui);
        if (h.itemBadge != null) top.addView(h.itemBadge);

        TextView n = ModouUi.tv(host, "", 14, ui.text);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        n.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(n);
        h.name = n;

        // 浏览态右侧三件套（预建，bind 时只切换显隐与开关状态）
        LinearLayout acts = new LinearLayout(host);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setGravity(Gravity.CENTER_VERTICAL);
        // 拖拽手柄：只有手动排序模式显示，长按它才拖（长按行仍进多选）
        // 手柄：8% 墨色的浅圆底 + 稍深的六点，白卡上看得出「这里能拖」，
        // 又不至于像第三颗功能按钮那样抢走书源名的视觉重心
        ImageView hd = ModouUi.glyphBtn(host,
                new ModouGlyph(ModouGlyph.DRAG, ModouUi.alpha(ui.text, 0xB3),
                        ModouUi.dp(host, 15), ModouUi.dp(host, 1.7f)), 34, "拖动排序");
        android.graphics.drawable.GradientDrawable hbg = new android.graphics.drawable.GradientDrawable();
        hbg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        hbg.setColor(ModouUi.alpha(ui.text, 0x0F));
        hd.setBackground(hbg);
        acts.addView(hd, new LinearLayout.LayoutParams(
                ModouUi.dp(host, 34), ModouUi.dp(host, 34)));
        final View sw = ModouUi.switchRow(host, "", true, on -> { /* bind 时替换 */ });
        acts.addView(sw);
        int iconColor = ModouUi.alpha(ui.text, 0xB3);
        ImageView pen = ModouUi.glyphBtn(host,
                new ModouGlyph(ModouGlyph.EDIT, iconColor,
                        ModouUi.dp(host, 13), ModouUi.dp(host, 1.4f)), 36, "编辑");
        acts.addView(pen, new LinearLayout.LayoutParams(
                ModouUi.dp(host, 36), ModouUi.dp(host, 36)));
        ImageView mv = ModouUi.glyphBtn(host,
                new ModouGlyph(ModouGlyph.MORE_V, iconColor,
                        ModouUi.dp(host, 14), ModouUi.dp(host, 1.5f)), 36, "更多");
        acts.addView(mv, new LinearLayout.LayoutParams(
                ModouUi.dp(host, 36), ModouUi.dp(host, 36)));
        top.addView(acts);
        h.browseActions = acts;
        h.swRow = sw;
        h.handle = hd;

        // 多选态勾选框（同样预建）
        FrameLayout box = new FrameLayout(host);
        ImageView ck = new ImageView(host);
        ck.setImageDrawable(new ModouGlyph(ModouGlyph.CHECK, 0xFFFFFFFF,
                ModouUi.dp(host, 12), ModouUi.dp(host, 2f)));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ModouUi.dp(host, 12), ModouUi.dp(host, 12));
        clp.gravity = Gravity.CENTER;
        box.addView(ck, clp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ModouUi.dp(host, 22), ModouUi.dp(host, 22));
        blp.rightMargin = ModouUi.dp(host, 6);
        box.setLayoutParams(blp);
        top.addView(box);
        h.selBox = box;
        h.selCheck = ck;

        wrap.addView(top);

        LinearLayout sub = new LinearLayout(host);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        sub.setGravity(Gravity.CENTER_VERTICAL);
        // 0.9.168：有字母块时副行从「块之后」起，和主标题左对齐；墨读沿用 10dp
        int subLeft = 10;
        if (ui.spec.itemBadgeStyle != 0) {
            subLeft = 10 + ui.spec.itemBadgeSizeDp + ui.spec.itemBadgeGapDp;
        }
        sub.setPadding(ModouUi.dp(host, subLeft), 0,
                ModouUi.dp(host, 12), ModouUi.dp(host, 7));
        View dot = new View(host);
        sub.addView(dot, new LinearLayout.LayoutParams(
                ModouUi.dp(host, 7), ModouUi.dp(host, 7)));
        TextView meta = ModouUi.tv(host, "", 11, ui.sub);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(ModouUi.dp(host, 6), 0, 0, 0);
        sub.addView(meta, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = ModouUi.tv(host, "", 10, ui.sub);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setVisibility(View.GONE);
        LinearLayout.LayoutParams hblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hblp.leftMargin = ModouUi.dp(host, 6);
        sub.addView(badge, hblp);
        wrap.addView(sub);
        h.dot = dot;
        h.meta = meta;
        h.badge = badge;

        View div = new View(host);
        div.setBackgroundColor(ModouUi.alpha(ui.text, 0x0D));
        // 0.9.168：M3 每项已是独立卡（有间距），组内不再画线；iOS 从字母块之后起
        // 画缩进线，与固定功能行 56dp 左缩线同一节奏；墨读逐像素沿用旧值。
        int divH = Math.max(1, ModouUi.dp(host, ui.spec.dividerDp));
        if (ui.spec.itemBadgeStyle == 2) divH = 0;      // M3
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, divH);
        if (ui.spec.itemDividerStartDp > 0) {
            dlp.leftMargin = ModouUi.dp(host, ui.spec.itemDividerStartDp);
        }
        div.setLayoutParams(dlp);
        wrap.addView(div);
        h.divider = div;

        // 点击一律读 holder 当前绑定的书源：复用时换了行也不会点错源
        wrap.setOnClickListener(v -> {
            Holder hh = holderOf(v);
            if (hh == null || hh.src == null) return;
            if (selMode) toggleOne(hh.src.optString("id", ""));
            else openDetailSheet(hh.src);
        });
        wrap.setOnLongClickListener(v -> {
            if (selMode) return false;
            Holder hh = holderOf(v);
            if (hh == null || hh.src == null) return false;
            enterSelection(hh.src);
            return true;
        });
        pen.setOnClickListener(v -> {
            Holder hh = holderOf(h.root);
            if (hh == null || hh.src == null) return;
            try {
                new SourceEditor(host).open(hh.src);
            } catch (Throwable e) {
                ModouUi.toast(host, "打开编辑器失败：" + e);
            }
        });
        mv.setOnClickListener(v -> {
            Holder hh = holderOf(h.root);
            if (hh == null || hh.src == null) return;
            openSourceMenu(hh.src);
        });
        hd.setOnTouchListener((v, ev) -> {
            if (!dragEnabled()) return false;
            if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.55f);          // 按下先给个「抓住了」的信号，再交给拖拽
                startDrag(h);
                return true;
            }
            if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                    || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                return true;
            }
            return false;
        });

        wrap.setTag(h);
        h.row = null;
        return h;
    }

    private static Holder holderOf(View v) {
        Object t = v == null ? null : v.getTag();
        return t instanceof Holder ? (Holder) t : null;
    }

    /** 把一行数据刷进复用的行视图：每个可变状态都无条件重设，杜绝复用错乱。
     *  payload 非空时只刷对应那一处（勾选 / 状态），文字一律不动 → 不闪屏 */
    private void bindRow(Holder h, Row r, String payload) {
        if (h == null || r == null) return;
        if (r.kind == Row.HEADER) {
            ((TextView) h.itemView).setText(r.group);
            return;
        }
        if (r.kind == Row.EMPTY) {
            ((TextView) h.itemView).setText(r.group == null ? "" : r.group);
            return;
        }
        h.row = r;
        h.src = r.src;
        final JSONObject o = r.src;
        boolean onlySel = PAYLOAD_SEL.equals(payload);
        boolean onlyStatus = PAYLOAD_STATUS.equals(payload);

        if (!(onlySel || onlyStatus)) {
            String rawName = o.optString("bookSourceName", "");
            String url = o.optString("bookSourceUrl", "");
            h.name.setText(rawName.isEmpty() ? url : rawName);
            // 0.9.168：字母块与文字同批刷新（局部 payload 时二者都不变，跳过）。
            // seed 用稳定的书源 URL，保证同一个源滚出屏幕再回来颜色不变。
            ItemBadge.bind(h.itemBadge, rawName.isEmpty() ? url : rawName, url);
            h.divider.setVisibility(r.last ? View.GONE : View.VISIBLE);
        }
        if (!onlySel) {
            h.meta.setText(buildMeta(o));
            h.dot.setBackground(dotDrawable(statusKey(keyOf(o))));
            bindHealthBadge(h.badge, o);
        }

        boolean on = sel.contains(o.optString("id", ""));
        h.itemView.setBackground(cardFor(r, on));
        h.selCheck.setVisibility(on ? View.VISIBLE : View.GONE);

        if (!onlySel && !onlyStatus) {
            if (selMode) {
                h.browseActions.setVisibility(View.GONE);
                h.selBox.setVisibility(View.VISIBLE);
                h.selBox.setBackground(selBoxBg(on));
            } else {
                h.handle.setVisibility(dragEnabled() ? View.VISIBLE : View.GONE);
                h.browseActions.setVisibility(View.VISIBLE);
                h.selBox.setVisibility(View.GONE);
                ModouUi.switchSet(h.swRow, o.optBoolean("enabled", true), on2 -> {
                    try {
                        // 用 holder 当前绑定的源，不用闭包捕获的那个（复用后可能是别人）
                        JSONObject cur = h.src;
                        if (cur == null) return;
                        cur.put("enabled", on2);
                        ModouStore.get(host).put("sources", cur.toString());
                    } catch (Exception ignore) {
                        /* 不可能 */
                    }
                });
            }
        } else if (selMode) {
            h.selBox.setBackground(selBoxBg(on));
        }
    }

    /** 组卡背景：组内首/末行带圆角；选中行底色调成强调色的极淡版。
     *  0.9.168：M3 每个书源是一张独立 filled card（四角全圆、不描边，靠 8dp
     *  间距和 surface 色分层）；iOS 仍是连续分组卡但改用 12dp 收敛圆角；
     *  墨读 styled=false，逐像素走 0.9.167 原路径。 */
    private GradientDrawable cardFor(Row r, boolean selected) {
        DesignSpec sp = ui.spec;
        boolean styled = sp.itemBadgeStyle != 0;
        boolean m3 = sp.itemBadgeStyle == 2;
        GradientDrawable g = new GradientDrawable();
        float rad = ModouUi.dp(host,
                (styled && sp.itemCardCornerDp > 0) ? sp.itemCardCornerDp : sp.cornerCard);
        float top, bot;
        if (m3) {
            top = bot = rad;                       // 独立卡：不再依赖 first/last
        } else {
            top = r.first ? rad : 0f;
            bot = r.last ? rad : 0f;
        }
        g.setCornerRadii(new float[]{top, top, top, top, bot, bot, bot, bot});
        g.setColor(selected ? mixColor(ui.card, ui.accent, 0.12f) : ui.card);
        if (!(styled && sp.itemCardNoStroke)) {
            g.setStroke(ModouUi.dp(host, 1), ModouUi.alpha(ui.text, 0x0A));
        }
        return g;
    }

    private GradientDrawable selBoxBg(boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(ModouUi.dp(host, 6));
        if (on) {
            g.setColor(ui.accent);
            g.setStroke(ModouUi.dp(host, 1.5f), ui.accent);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(ModouUi.dp(host, 1.6f), ModouUi.alpha(ui.text, 0x59));
        }
        return g;
    }

    /** 两色按 t 混合（选中行高亮用，避免半透明叠加在白卡上发灰） */
    private static int mixColor(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        int al = (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t);
        return Color.argb(al, r, g, bl);
    }

    private GradientDrawable dotDrawable(String key) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(stateColor(key));
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /* ---------- 拖拽排序（手动排序模式下长按手柄拖动） ---------- */

    /** 只有「手动排序」才允许拖：其余排序方式拖完会被排序规则立刻拉回去 */
    private boolean dragEnabled() { return "custom".equals(sortBy) && !selMode; }

    private void startDrag(Holder h) {
        if (dragHelper != null && h != null) dragHelper.startDrag(h);
    }

    private final class DragCallback extends androidx.recyclerview.widget.ItemTouchHelper.Callback {
        private int from = -1, to = -1;

        @Override
        public int getMovementFlags(androidx.recyclerview.widget.RecyclerView rv,
                                    androidx.recyclerview.widget.RecyclerView.ViewHolder vh) {
            if (!dragEnabled()) return makeMovementFlags(0, 0);
            int drag = androidx.recyclerview.widget.ItemTouchHelper.UP
                    | androidx.recyclerview.widget.ItemTouchHelper.DOWN;
            return makeMovementFlags(drag, 0);   // 不开放侧滑删除（有批量删除入口）
        }

        @Override
        public boolean onMove(androidx.recyclerview.widget.RecyclerView rv,
                              androidx.recyclerview.widget.RecyclerView.ViewHolder src,
                              androidx.recyclerview.widget.RecyclerView.ViewHolder dst) {
            int f = src.getBindingAdapterPosition(), t = dst.getBindingAdapterPosition();
            if (f < 0 || t < 0 || f >= rows.size() || t >= rows.size()) return false;
            Row rf = rows.get(f), rt = rows.get(t);
            if (rf.kind != Row.ITEM || rt.kind != Row.ITEM) return false;
            if (from < 0) from = f;
            to = t;
            // rows 与 items 同步搬动（手动排序模式下两者一一对应）
            Row moved = rows.remove(f);
            rows.add(t, moved);
            int fi = indexInItems(rf.key), ti = indexInItems(rt.key);
            if (fi >= 0 && ti >= 0) {
                JSONObject o = items.remove(fi);
                items.add(ti, o);
                // 落点决定归属：拖进置顶区就置顶、拖进置底区就置底、拖回中间就两头都解除。
                // 不处理的话，抬手一刷新 pin 规则会立刻把它弹回原位，用户以为「拖了没用」。
                applyPinByLanding(o, rt.src);
            }
            adapter.notifyItemMoved(f, t);
            rebuildIndex(rows);
            return true;
        }

        @Override
        public void onSwiped(androidx.recyclerview.widget.RecyclerView.ViewHolder vh, int dir) {}

        @Override
        public boolean isLongPressDragEnabled() { return false; }   // 自己控：长按要兼多选

        @Override
        public void onSelectedChanged(androidx.recyclerview.widget.RecyclerView.ViewHolder vh,
                                      int state) {
            super.onSelectedChanged(vh, state);
            if (state == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG
                    && vh != null) {
                // 震动反馈：用系统触感，不申请 VIBRATE 权限
                vh.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                vh.itemView.setAlpha(0.96f);
                vh.itemView.setScaleX(1.02f);
                vh.itemView.setScaleY(1.02f);
                vh.itemView.setElevation(ModouUi.dp(host, 6));   // 浮起来，明确「这张卡在你手上"
            }
        }

        @Override
        public void clearView(androidx.recyclerview.widget.RecyclerView rv,
                              androidx.recyclerview.widget.RecyclerView.ViewHolder vh) {
            super.clearView(rv, vh);
            vh.itemView.setAlpha(1f);
            vh.itemView.setScaleX(1f);
            vh.itemView.setScaleY(1f);
            vh.itemView.setElevation(0f);
            if (from >= 0 && to >= 0 && from != to) {
                persistOrder();
                // 落库后重排一次：置顶/置底的改动要立刻生效，customOrder 也重新编号，
                // 否则 rows 里的临时顺序与库里的顺序不一致，下次进来又乱了
                reload();
            }
            from = -1;
            to = -1;
        }
    }

    private int indexInItems(String key) {
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(keyOf(items.get(i)))) return i;
        }
        return -1;
    }

    /** 拖拽落点决定归属：置顶区 → 置顶，置底区 → 置底，中间 → 解除两端 */
    private void applyPinByLanding(JSONObject moved, JSONObject landing) {
        boolean top = landing.optBoolean("pinTop", false);
        boolean bottom = landing.optBoolean("pinBottom", false);
        try {
            if (top) {
                moved.put("pinTop", true);
                moved.put("pinBottom", false);
                moved.put("pinOrder", System.currentTimeMillis());
            } else if (bottom) {
                moved.put("pinBottom", true);
                moved.put("pinTop", false);
                moved.put("pinOrder", System.currentTimeMillis());
            } else {
                moved.put("pinTop", false);
                moved.put("pinBottom", false);
            }
        } catch (Exception ignore) {
            /* 不可能 */
        }
    }

    /** 手动排序结果落库：给每条源写 customOrder（连同 pin 归属一起），重启后顺序不变 */
    private void persistOrder() {
        boolean anyPin = false;
        for (int i = 0; i < items.size(); i++) {
            try {
                JSONObject o = items.get(i);
                o.put("customOrder", i);
                if (o.optBoolean("pinTop", false) || o.optBoolean("pinBottom", false)) anyPin = true;
                ModouStore.get(host).put("sources", o.toString());
            } catch (Exception ignore) {
                /* 不可能 */
            }
        }
        ModouUi.toast(host, anyPin ? "已保存新的书源顺序（含置顶/置底）" : "已保存新的书源顺序");
    }





    /* ================= 队列驱动 ================= */

    /**
     * 固定并发的异步队列：task 执行完必须回调 next；next 取下一个任务，
     * 全部完成后回调 onAllDone（一次）。回调都在主线程，无需加锁。
     */
    /**
     * 并发泵，但按 host 限并发（上限 {@link #HOST_MAX_CONCURRENCY_PER_HOST}）：
     * 跨域仍然全并发（受 workers 总并发度控制，提速），同域最多同时 N 个在途。
     *
     * <p>0.9.30 曾去掉同域锁改成纯并发，但书源生态里同域多源极常见——
     * 同域多源同一时刻并发打过去，站点限流直接回 429/403，被 netFail 判成灰、
     * 还累计进 unreach_cnt，等于「自己把源打死又说是源坏了」。这是比串行更糟的假失败。
     * 所以恢复按 host 计数，只是上限放宽到 2（非原来的布尔互斥锁），既防自造限流，又保留跨域提速。
     */
    private static final int HOST_MAX_CONCURRENCY_PER_HOST = 2;

    private void runQueue(List<JSONObject> queue, int workers,
                          BiConsumer<JSONObject, Runnable> task, Runnable onAllDone) {
        if (queue.isEmpty()) {
            onAllDone.run();
            return;
        }
        final java.util.ArrayDeque<JSONObject> pool = new java.util.ArrayDeque<>(queue);
        final AtomicInteger done = new AtomicInteger();
        final int total = queue.size();
        // 同域在途计数：限并发而非互斥，跨域不受影响
        final java.util.concurrent.ConcurrentHashMap<String, Integer> hostCount =
                new java.util.concurrent.ConcurrentHashMap<>();
        final Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            // 从池里找一个「同域未满」的 job；同域已满的放回末尾，扫一圈都没可运行的就退出，
            // 等某个在途任务 finish 释放同域额度后再被唤醒。
            JSONObject pick = null;
            String pickHost = "";
            int scanned = 0;
            int remaining = pool.size();
            while (scanned <= remaining) {
                JSONObject c = pool.pollFirst();
                if (c == null) break;
                scanned++;
                String h = hostOfSource(c);
                if (h.isEmpty() || hostCount.getOrDefault(h, 0) < HOST_MAX_CONCURRENCY_PER_HOST) {
                    pick = c;
                    pickHost = h;
                    break;
                }
                pool.addLast(c); // 同域已满，放回末尾稍后重试
            }
            if (pick == null) return; // 池空或全是同域已满：等在途完成回来唤醒
            if (!pickHost.isEmpty()) {
                hostCount.merge(pickHost, 1, Integer::sum);
            }
            final JSONObject job = pick;
            final String jobHost = pickHost;
            final java.util.concurrent.atomic.AtomicBoolean fired =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final Runnable finish = () -> {
                if (!fired.compareAndSet(false, true)) return; // 任务重复回调不能把队列算两遍
                if (!jobHost.isEmpty()) {
                    hostCount.computeIfPresent(jobHost, (k, v) -> v - 1 <= 0 ? null : v - 1);
                }
                if (done.incrementAndGet() >= total) onAllDone.run();
                else pump[0].run();
            };
            try {
                task.accept(job, finish);
            } catch (Throwable t) {
                // 某个源的规则把解析器搞崩了，不能连坐整批
                android.util.Log.w("SourcesPanel", "check task failed: " + t);
                finish.run();
            }
        };
        // 初始并发度：跨域全速，受 workers 限制；同域超额部分由上面的 hostCount 自然拦下
        for (int k = 0; k < Math.min(workers, total); k++) pump[0].run();
    }

    private static String hostOfSource(JSONObject o) {
        try {
            String h = new java.net.URL(o.optString("bookSourceUrl", "")).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /* ================= 校验（快速 + 深度） ================= */

    /**
     * 校验作用域集合：决定「开始校验」到底验哪些源。
     * 优先级：① 多选态且有勾选 → 选中项；② 应用了筛选 → 筛选后可见项；③ 否则全量。
     * 关键是第 ② 条：筛选（如只看失效源）后点体检，只跑屏幕上这批，绝不波及未显示的启用源。
     */
    private List<JSONObject> checkScopeList() {
        if (selMode && !sel.isEmpty()) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject o : items) if (sel.contains(o.optString("id", ""))) out.add(o);
            return out;
        }
        if (activeFilterCount() > 0) {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject o : items) if (passFilter(o)) out.add(o);
            return out;
        }
        return new ArrayList<>(items);
    }

    /** 作用域描述（给体检 sheet 当副标题，让用户一眼看清要验多少、按什么范围） */
    private String checkScopeLabel() {
        if (selMode && !sel.isEmpty()) return "选中的 " + sel.size() + " 个";
        if (activeFilterCount() > 0) return "筛选后 " + checkScopeList().size() + " 个";
        return "全部 " + items.size() + " 个";
    }

    /** 本轮实际校验范围：外部指定的批优先，其次「选中 > 筛选 > 全部」 */
    private List<JSONObject> checkScopeNow() {
        if (rerunScope != null) {
            // 按 key 比对，不能用 items.contains(o)：reload() 之后 items 里是**新对象**，
            // 老引用一个都匹配不上，作用域会莫名其妙变成空。
            Set<String> alive = new java.util.HashSet<>();
            for (JSONObject o : items) alive.add(keyOf(o));
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject o : rerunScope) if (alive.contains(keyOf(o))) out.add(o);
            if (!out.isEmpty()) return out;
        }
        return checkForceAll ? new ArrayList<>(items) : checkScopeList();
    }

    /* ================= 书源体检：状态机 + 诊断报告 ================= */

    /**
     * 打开体检面板。**全流程只创建一个 Dialog**：阶段变化（快速 → 深度 → 报告）
     * 只调 {@link #renderCheckSheet()} 就地重画正文与按钮行。
     *
     * <p>0.9.62 之前每跑完一轮都 dismiss 再 open 一次：面板闪一下、滚动位置归零、
     * 进度条从 0 重画 —— 用户看到的就是「又回到起点」。现在 Dialog 不动，
     * 只有里面的内容随阶段换，进度是连续的。
     */
    private void openCheckAllSheet() {
        if (items.isEmpty()) {
            ModouUi.toast(host, "还没有书源");
            return;
        }
        if (checkSheetDialog != null && checkSheetDialog.isShowing()) {
            renderCheckSheet();
            return;
        }
        checkBody = new LinearLayout(host);
        checkBody.setOrientation(LinearLayout.VERTICAL);
        checkFooterRow = new LinearLayout(host);
        checkFooterRow.setOrientation(LinearLayout.HORIZONTAL);
        checkFooterRow.setWeightSum(2f);

        Dialog d = ModouUi.sheet(host, null, checkBody, checkFooterRow);
        checkSheetDialog = d;
        d.setOnDismissListener(di -> {
            if (checkSheetDialog != d) return;
            checkSheetDialog = null;
            checkBody = null;
            checkFooterRow = null;
            checkProgress = null;
            checkProgressBar = null;
            checkSummary = null;
            checkKw = null;
            checkBtnText = null;
        });
        renderCheckSheet();
    }

    private void dismissCheckSheet() {
        if (checkSheetDialog != null) checkSheetDialog.dismiss();
    }

    /**
     * 从菜单发起新一轮体检：**必须重开面板**，不能复用正在显示的那个。
     *
     * <p>复用时 {@link #openCheckAllSheet()} 只调 renderCheckSheet()，阶段仍是上一轮的
     * REPORT / DONE —— 用户点「校验当前列表（全部 45 个）」，看到的却是「上一次那个源全部通过」。
     * 刚做过单源规则调试时必现，看起来就像「45 个源只校验了 1 个」。
     */
    private void openCheckAllSheetFresh() {
        if (checkRunning()) {
            ModouUi.toast(host, "体检进行中");
            openCheckAllSheet();   // 让用户看当前进度，而不是再起一轮
            return;
        }
        Dialog old = checkSheetDialog;
        // 先断开引用：onDismiss 里的 `checkSheetDialog != d` 判断会直接返回，
        // 不会把马上要新建的面板引用清掉
        checkSheetDialog = null;
        if (old != null) {
            try {
                old.dismiss();
            } catch (Throwable ignore) {
                /* 已经关了 */
            }
        }
        reportBatch.clear();
        // 上一轮单源调试的标记：不清的话批量跑完 finishReport 会弹出那个源的结论
        debugTargetKey = null;
        stage = CheckStage.IDLE;
        openCheckAllSheet();
    }

    /** 唯一的渲染入口：按 {@link CheckStage} 重画整个面板 */
    private void renderCheckSheet() {
        if (checkBody == null || checkFooterRow == null) return;
        checkBody.removeAllViews();
        checkFooterRow.removeAllViews();
        checkProgress = null;
        checkProgressBar = null;
        checkSummary = null;
        checkKw = null;
        checkBtnText = null;

        switch (stage) {
            case QUICK:
            case DEEP:
                buildRunningBody();
                buildRunningFooter();
                syncProgressViews();   // 重建后立刻把最新数字写回去
                break;
            case REPORT:
                buildReportBody();
                buildReportFooter(false);
                break;
            case DONE:
                buildDoneBody();
                buildReportFooter(true);
                break;
            default:
                buildIdleBody();
                buildIdleFooter();
                break;
        }
    }

    /** 面板内标题（sheet 外壳标题留空，标题随阶段就地变化，不重建窗口） */
    private void addHead(String title, String sub) {
        TextView t = ModouUi.tv(host, title, SHEET_TITLE_SP, ui.text);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        t.setIncludeFontPadding(false);
        checkBody.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = ModouUi.tv(host, sub, 12f, ui.sub);
            s.setLineSpacing(ModouUi.dp(host, 3), 1.45f);
            s.setPadding(0, ModouUi.dp(host, 4), 0, 0);
            checkBody.addView(s);
        }
    }

    /* ---------- 未开始 ---------- */

    private void buildIdleBody() {
        addHead("校验当前列表", "跑一轮搜索校验；网络失败不会被判死，想验详情/目录/正文请用单源的「规则调试」。");

        TextView hint = ModouUi.tv(host,
                "① 每个源搜一次，搜不到会换一个词再搜。\n"
                        + "② 批量体检只做搜索校验；想验详情 → 目录 → 正文，在单个书源里点「规则调试」。\n"
                        + "③ 全程同一个面板，进度条不会归零；超时 / 403 / DNS 等网络问题只标「未判定」。",
                12f, ui.sub);
        hint.setLineSpacing(ModouUi.dp(host, 4), 1.55f);
        hint.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 12));
        checkBody.addView(hint);

        String scopeLabel = checkScopeLabel();
        TextView scopeNote = ModouUi.tv(host,
                "将校验 " + scopeLabel
                        + (scopeLabel.startsWith("全部") ? " 书源" : "（仅这批，不波及未显示的源）"),
                12.5f, ui.accent);
        scopeNote.setPadding(0, 0, 0, ModouUi.dp(host, 10));
        checkBody.addView(scopeNote);

        // 没改过关键词时框里留空，默认值写进 hint：框里直接躺着「我的」会被当成用户输入
        String kwVal = "我的".equals(keyword) ? "" : keyword;
        EditText kw = ModouUi.input(host, "输入校验用的搜索词，默认：我的", kwVal, 1);
        GradientDrawable inBg = new GradientDrawable();
        inBg.setColor(ModouUi.alpha(ui.text, 0x0C));
        inBg.setCornerRadius(ModouUi.dp(host, 16));
        kw.setBackground(inBg);
        kw.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 13),
                ModouUi.dp(host, 14), ModouUi.dp(host, 13));
        kw.setTextSize(15);
        kw.setHintTextColor(ModouUi.alpha(ui.sub, 0xB0));
        checkBody.addView(kw);
        checkKw = kw;
    }

    private void buildIdleFooter() {
        checkFooterRow.setPadding(0, ModouUi.dp(host, SHEET_FOOTER_GAP), 0, 0);
        addFooterBtn("取消", ui.text, ModouUi.alpha(ui.text, 0x0E), false,
                v -> dismissCheckSheet());
        addFooterBtn("开始校验 · " + checkScopeNow().size(), 0xFFFFFFFF, ui.accent, true, v -> {
            String k = checkKw == null ? "" : String.valueOf(checkKw.getText()).trim();
            keyword = k.isEmpty() ? "我的" : k;
            beginCheck();
        });
    }

    /* ---------- 进行中（快速 / 深度共用） ---------- */

    private void buildRunningBody() {
        final boolean quick = stage != CheckStage.DEEP;

        // 标题与副标题走 addHead：三张面板同一个字号/同一份上边距，
        // 以前这里是 21sp + 自定义 padding，切到报告页会「字号突然小一圈」
        addHead(quick ? "正在快速校验…" : "正在深度校验…", "后台进行中，可随时收起");

        // 进度：大数字单独占一行压在进度条正上方，右侧留一行很淡的统计。
        // 以前数字和「① 快速校验」挤在同一行 12.5sp 里，跟标题重复且看不清。
        LinearLayout numRow = new LinearLayout(host);
        numRow.setOrientation(LinearLayout.HORIZONTAL);
        numRow.setGravity(Gravity.BOTTOM);
        numRow.setPadding(0, ModouUi.dp(host, 24), 0, 0);

        checkProgress = ModouUi.tv(host, "", 34f, ui.text);
        checkProgress.setIncludeFontPadding(false);
        checkProgress.setGravity(Gravity.BOTTOM);
        numRow.addView(checkProgress, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        checkProgressStat = ModouUi.tv(host, progressStatText(), 11.5f, ModouUi.alpha(ui.sub, 0xD0));
        checkProgressStat.setGravity(Gravity.BOTTOM | Gravity.END);
        checkProgressStat.setPadding(0, 0, 0, ModouUi.dp(host, 3));
        numRow.addView(checkProgressStat);
        checkBody.addView(numRow);

        checkProgressBar = new android.widget.ProgressBar(host, null,
                android.R.attr.progressBarStyleHorizontal);
        int max = Math.max(1, nTotal);
        checkProgressBar.setMax(max);
        checkProgressBar.setProgress(Math.min(nDone, max));
        checkProgressBar.setIndeterminate(false);
        ModouUi.thickBar(checkProgressBar, ui.accent, ModouUi.alpha(ui.text, 0x14));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(host, 10));
        blp.topMargin = ModouUi.dp(host, 12);
        checkBody.addView(checkProgressBar, blp);

        TextView note = ModouUi.tv(host, quick
                ? "下不了结论的源会标为「未判定」并写出原因；网络失败不会被判死"
                : "逐个打开详情、目录、正文；网络失败单独归类，不会被判死",
                10.5f, ModouUi.alpha(ui.sub, 0xCF));
        note.setLineSpacing(ModouUi.dp(host, 5), 1.35f);
        note.setPadding(0, ModouUi.dp(host, 14), 0, ModouUi.dp(host, 2));
        checkBody.addView(note);

        renderProgressNum();
    }

    /** 大数字进度：当前数 34sp 加粗，"/ 总数" 小一号浅色——视觉重心落在「已经跑了多少」 */
    private void renderProgressNum() {
        if (checkProgress == null) return;
        String done = String.valueOf(Math.max(0, nDone));
        String rest = " / " + Math.max(1, nTotal);
        SpannableString sp = new SpannableString(done + rest);
        sp.setSpan(new AbsoluteSizeSpan(34, true), 0, done.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new StyleSpan(Typeface.BOLD), 0, done.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(ui.text), 0, done.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new AbsoluteSizeSpan(17, true), done.length(), sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new ForegroundColorSpan(ModouUi.alpha(ui.sub, 0xE0)),
                done.length(), sp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        checkProgress.setText(sp);
    }

    /** 大数字右侧的统计：快速阶段数 quick，深度阶段数 deep（两者的结论口径不同，不能混着显示） */
    private String progressStatText() {
        int ok = 0, bad = 0, skip = 0;
        boolean deepPhase = stage == CheckStage.DEEP;
        for (JSONObject o : reportBatch) {
            String u = keyOf(o);
            if (deepPhase) {
                Deep d = deep.get(u);
                if (d == null) continue;
                if ("ok".equals(d.state)) ok++;
                else if ("bad".equals(d.state)) bad++;
            } else {
                String q = quick.get(u);
                if ("ok".equals(q)) ok++;
                else if ("bad".equals(q)) bad++;
                else if ("skip".equals(q)) skip++;
            }
        }
        // 0.9.136 起超时/403/0 条一律是「未判定」。只显示有效+失效会让剩下的源凭空消失，
        // 用户看着「有效 20 · 失效 0」根本不知道另外 200 个去哪了。
        return deepPhase ? "通过 " + ok + " · 未通过 " + bad
                : "有效 " + ok + " · 未判定 " + skip + (bad > 0 ? " · 失效 " + bad : "");
    }

    private void buildRunningFooter() {
        checkFooterRow.setPadding(0, ModouUi.dp(host, SHEET_FOOTER_GAP), 0, 0);
        addFooterBtn("收起", ui.text, ModouUi.alpha(ui.text, 0x0E), false,
                v -> dismissCheckSheet());
        addFooterBtn("停止校验", 0xFFE53935, 0x1AE53935, true, v -> stopCheck());
    }

    /* ---------- 诊断报告 ---------- */

    private void buildReportBody() {
        pruneCategories();
        final int total = reportBatch.size();
        final int bad = catRule.size() + catNet.size() + catDead.size();

        // 标题只说一次：以前 addHead 把 checkDoneText 当副标题塞进来，下面又补一句
        // 「没有需要处理的源，本轮全部通过」，两句话说的是同一件事，还占掉两行高度。
        addHead("诊断报告", null);

        if (bad == 0) {
            // 全部通过：一个绿色勾 + 一行结论，其余什么都不加。
            // 内容有多高面板就多高（wrap_content），下方不再出现一大片空白。
            LinearLayout box = new LinearLayout(host);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            // 底部不再补 padding：正文与按钮之间的间距统一由 SHEET_FOOTER_GAP 负责，
            // 两处都留就会叠成一截多余的空白
            box.setPadding(0, ModouUi.dp(host, 20), 0, 0);

            box.addView(successBadge(SHEET_BADGE), new LinearLayout.LayoutParams(
                    ModouUi.dp(host, SHEET_BADGE), ModouUi.dp(host, SHEET_BADGE)));

            String line = reportComplete
                    ? (total > 0 ? "本轮 " + total + " 个书源全部校验通过。" : "全部校验通过。")
                    : "已完成的源全部通过；没跑完的源这次没有下结论。";
            TextView ok = ModouUi.tv(host, line, 17f, ui.text);
            ok.setGravity(Gravity.CENTER);
            ok.setTypeface(ok.getTypeface(), Typeface.BOLD);
            ok.setLineSpacing(ModouUi.dp(host, 3), 1.35f);
            ok.setPadding(0, ModouUi.dp(host, 14), 0, 0);
            box.addView(ok);
            checkBody.addView(box);
            return;
        }

        // 有未通过：一行结论（放大加深，作为视觉重心）+ 分类卡片 + 一句操作提示
        TextView concl = ModouUi.tv(host, buildSummary(), 16f, ui.text);
        concl.setTypeface(concl.getTypeface(), Typeface.BOLD);
        concl.setLineSpacing(ModouUi.dp(host, 3), 1.4f);
        concl.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 14));
        checkBody.addView(concl);

        addCategory(CAT_RULE);
        addCategory(CAT_NET);
        addCategory(CAT_DEAD);

        TextView tip = ModouUi.tv(host,
                "「删除」不可撤销，删前请确认；「停用」只关开关，源仍保留。", 11.5f, ui.sub);
        tip.setLineSpacing(ModouUi.dp(host, 3), 1.4f);
        tip.setPadding(ModouUi.dp(host, 2), ModouUi.dp(host, 2), 0, 0);
        checkBody.addView(tip);
    }

    /** 绿色圆形对勾：全部通过时放在弹窗正中，一眼就知道「没事」 */
    private View successBadge(int sizeDp) {
        FrameLayout f = new FrameLayout(host);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF2FA860);   // 莫兰迪绿：比 Material 绿暗一档，和纸墨底不打架
        f.setBackground(bg);
        ImageView ic = new ImageView(host);
        ic.setScaleType(ImageView.ScaleType.CENTER);
        int inner = Math.max(12, (int) (sizeDp * 0.5f));
        ic.setImageDrawable(new ModouGlyph(ModouGlyph.CHECK, 0xFFFFFFFF,
                ModouUi.dp(host, inner), ModouUi.dp(host, 2.4f)));
        f.addView(ic, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    /** 一类失败原因的卡片：标题 + 数量 + 说明 + 点名 + 一个批量动作 */
    private void addCategory(int cat) {
        final List<JSONObject> list = listOf(cat);
        if (list.isEmpty()) return;

        final boolean danger = cat != CAT_NET;
        final String title = cat == CAT_RULE ? "规则失效"
                : cat == CAT_NET ? "暂时不可用" : "站点疑似死亡";
        final String desc = cat == CAT_RULE
                ? "请求通了但列表规则抓不到书 —— 建议人工确认后删除"
                : cat == CAT_NET
                ? "超时 / 403 / 风控拦截 / 关键词都没取到结果 —— 未下结论，建议停用保留，换个时间或换关键词重校验"
                : "DNS 解析失败 / 域名过期 —— 站点已经打不开，建议删除";
        final int color = danger ? DANGER : ui.text;

        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ModouUi.alpha(ui.text, 0x08));
        bg.setCornerRadius(ModouUi.dp(host, 14));
        card.setBackground(bg);
        card.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 12),
                ModouUi.dp(host, 14), ModouUi.dp(host, 2));

        LinearLayout top = new LinearLayout(host);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ModouUi.tv(host, title, 13.5f, color);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        top.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView count = ModouUi.tv(host, list.size() + " 个", 13f, color);
        count.setTypeface(count.getTypeface(), Typeface.BOLD);
        top.addView(count);
        card.addView(top);

        TextView d = ModouUi.tv(host, desc, 11.5f, ui.sub);
        d.setLineSpacing(ModouUi.dp(host, 3), 1.35f);
        d.setPadding(0, ModouUi.dp(host, 3), 0, 0);
        card.addView(d);

        TextView who = ModouUi.tv(host, namesLine(list), 11.5f, ui.sub);
        who.setPadding(0, ModouUi.dp(host, 5), 0, ModouUi.dp(host, 4));
        card.addView(who);

        // 动作按钮：危险操作（两类删除）红字，网络问题用正文色的停用 —— 只靠颜色分轻重
        card.addView(checkActionRow(danger ? ModouGlyph.TRASH : ModouGlyph.BLOCK,
                (danger ? "批量删除 " : "批量停用 ") + list.size() + " 个源",
                color, 0, danger ? () -> deleteCategory(cat) : () -> disableCategory(cat)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ModouUi.dp(host, 10);
        checkBody.addView(card, lp);
    }

    private void buildReportFooter(boolean done) {
        // 三张面板同一套间距与按钮高度：正文 → 18dp → 48dp 按钮，底下只剩面板内边距
        checkFooterRow.setPadding(0, ModouUi.dp(host, SHEET_FOOTER_GAP), 0, 0);
        addFooterBtn("关闭", ui.text, ModouUi.alpha(ui.text, 0x0E), false, v -> {
            dismissCheckSheet();
            // 用户手动开着「本轮未通过」时提示一下当前列表是筛选结果，免得以为源被删了
            if (roundFailOnly && !roundFail.isEmpty()) {
                ModouUi.toast(host, "当前只看本轮需处理的 " + roundFail.size() + " 个源");
            }
        });
        addFooterBtn(done ? "再校验一轮" : "重新校验", 0xFFFFFFFF, ui.accent, true,
                v -> rerunCheck());
    }

    private void buildDoneBody() {
        // 与「诊断报告」同一套语言：标题 + 一个勾 + 一行结论。
        // 结论用 afterCleanup/deleteCategory 真正做过的事（删了几个、停用了几个），
        // 不再是「列表与顶部统计已同步更新」这种内部实现说法。
        addHead("已处理完毕", null);
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, ModouUi.dp(host, 20), 0, 0);
        box.addView(successBadge(SHEET_BADGE), new LinearLayout.LayoutParams(
                ModouUi.dp(host, SHEET_BADGE), ModouUi.dp(host, SHEET_BADGE)));
        TextView ok = ModouUi.tv(host, doneLine(), 17f, ui.text);
        ok.setGravity(Gravity.CENTER);
        ok.setTypeface(ok.getTypeface(), Typeface.BOLD);
        ok.setLineSpacing(ModouUi.dp(host, 3), 1.35f);
        ok.setPadding(0, ModouUi.dp(host, 14), 0, 0);
        box.addView(ok);
        checkBody.addView(box);
    }

    /**
     * 「已处理完毕」那一行话术：说清做了什么，而不是「统计已同步」。
     *
     * <p>checkDoneText 是 afterCleanup 写下的真实动作（「已删除 2 个规则失效的书源。」），
     * 去掉句号再接一句结果；没有任何动作时退回「操作已生效。」——不编数字。
     */
    private String doneLine() {
        String s = checkDoneText == null ? "" : checkDoneText.trim();
        while (s.endsWith("。") || s.endsWith("，") || s.endsWith("；")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) return "操作已生效。";
        return s + "，列表已更新。";
    }

    /* ---------- 底部按钮 ---------- */

    private void addFooterBtn(String label, int textColor, int bgColor, boolean second,
                              View.OnClickListener click) {
        TextView b = ModouUi.tv(host, label, 15f, textColor);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setIncludeFontPadding(false);
        b.setBackground(pillBtn(bgColor,
                ModouUi.alpha(textColor == 0xFFFFFFFF ? 0xFFFFFFFF : textColor, 0x22)));
        b.setOnClickListener(click);
        // 固定 48dp 高：以前靠 padV 11/12 撑，三张面板的按钮一个高一个矮
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ModouUi.dp(host, SHEET_BTN_H), 1f);
        if (second) lp.leftMargin = ModouUi.dp(host, 5);
        else lp.rightMargin = ModouUi.dp(host, 5);
        checkFooterRow.addView(b, lp);
        checkBtnText = b;
    }

    /* ---------- 动作 ---------- */

    /** 危险动作色（删除）：只用于文字与图标，不铺成一整块红底 */
    private static final int DANGER = 0xFFA8433A;

    private static List<JSONObject> listOf(int cat) {
        return cat == CAT_RULE ? catRule : cat == CAT_NET ? catNet : catDead;
    }

    /** 清掉已经被删掉的书源：报告里不能留下点不动的条目 */
    private void pruneCategories() {
        Set<String> alive = new java.util.HashSet<>();
        for (JSONObject o : items) alive.add(keyOf(o));
        dropGone(catRule, alive);
        dropGone(catNet, alive);
        dropGone(catDead, alive);
    }

    private static void dropGone(List<JSONObject> list, Set<String> alive) {
        java.util.Iterator<JSONObject> it = list.iterator();
        while (it.hasNext()) {
            if (!alive.contains(keyOf(it.next()))) it.remove();
        }
    }

    /** 点名前三个 + 「等 N 个」：只剩一个数字用户根本不知道该不该删 */
    private String namesLine(List<JSONObject> list) {
        StringBuilder sb = new StringBuilder("其中：");
        for (int i = 0; i < list.size() && i < 3; i++) {
            if (i > 0) sb.append("、");
            sb.append(nameOf(list.get(i)));
        }
        if (list.size() > 3) sb.append(" 等 ").append(list.size()).append(" 个");
        return sb.toString();
    }

    /** 删除某一类的源：确认 → 删库 → 就地刷新报告（数字归零、卡片消失） */
    private void deleteCategory(final int cat) {
        final List<JSONObject> list = listOf(cat);
        final int n = list.size();
        if (n == 0) return;
        final String what = cat == CAT_DEAD ? "站点疑似死亡" : "规则失效";
        ModouUi.confirm(host, "删除" + what + "的书源",
                "将删除 " + n + " 个" + what + "的书源，删除后不可撤销。\n\n"
                        + "如果只是暂时不想用，请改用「停用」。", () -> {
                    for (JSONObject o : new ArrayList<>(list)) {
                        ModouStore.get(host).del("sources", o.optString("id", ""));
                        forget(o);
                    }
                    list.clear();
                    afterCleanup("已删除 " + n + " 个" + what + "的书源。");
                    ModouUi.toast(host, "已删除 " + n + " 个书源");
                });
    }

    /** 停用某一类的源：只关开关，源保留（网络问题以后可能恢复） */
    private void disableCategory(final int cat) {
        final List<JSONObject> list = listOf(cat);
        final int n = list.size();
        if (n == 0) return;
        int done = 0;
        for (JSONObject o : new ArrayList<>(list)) {
            try {
                o.put("enabled", false);
                if (ModouStore.get(host).put("sources", o.toString())) done++;
            } catch (Exception ignore) {
                /* 单条失败不影响其它 */
            }
        }
        final int okN = done;
        list.clear();
        afterCleanup("已停用 " + okN + " 个书源（筛选「停用」可找回）。");
        ModouUi.toast(host, "已停用 " + okN + " 个书源");
    }

    /** 清掉某个源在本轮的所有校验痕迹（它已经不在库里了） */
    private static void forget(JSONObject o) {
        String u = keyOf(o);
        quick.remove(u);
        qReason.remove(u);
        skipKind.remove(u);
        deep.remove(u);
        ruleBad.remove(u);
        foundBook.remove(u);
    }

    /**
     * 清理动作收尾：写库之后**必须重画当前弹窗**。
     *
     * <p>0.9.62 只调了 reload()（重画列表页），弹窗里的数字和按钮原封不动 ——
     * 用户点完只能靠一句 toast 猜到底成没成。这里统一「reload 列表 → 重算三类 →
     * 就地重画报告」，清空后卡片与数字一起消失。
     */
    private void afterCleanup(String msg) {
        reload();
        checkDoneText = msg;
        pruneCategories();
        // 删掉的源不能还挂在「本轮未通过」里，否则筛出来是空列表、chip 上却写着 N
        syncRoundFail();
        if (roundFail.isEmpty()) roundFailOnly = false;
        stage = (catRule.isEmpty() && catNet.isEmpty() && catDead.isEmpty())
                ? CheckStage.DONE : CheckStage.REPORT;
        renderCheckSheet();     // reload() 只重画列表页，弹窗是另一棵树，必须自己重画
    }

    /** 开始 / 重新开始一轮：显式清空上一轮结论，从快速校验重新跑 */
    private void beginCheck() {
        // 用户可能手动开着「本轮未通过」筛选，必须先撤掉：它只代表上一轮的结果，
        // 若继续生效，这一轮的范围会被悄悄缩成上一轮那几个源（用户点的是「校验当前列表」）。
        roundFailOnly = false;
        List<JSONObject> scope = checkScopeNow();
        if (scope.isEmpty()) {
            ModouUi.toast(host, "当前范围没有可校验的书源");
            return;
        }
        startWorkers(scope, deepFull);
    }

    /** 报告页的【重新校验】：仍跑同一批源，不因筛选变化而漂移 */
    private void rerunCheck() {
        // 仍跑同一批，但要用 items 里的**当前对象**：删过的已经不在库里，不能复活
        Set<String> want = new java.util.HashSet<>();
        for (JSONObject o : reportBatch) want.add(keyOf(o));
        List<JSONObject> scope = new ArrayList<>();
        for (JSONObject o : items) if (want.contains(keyOf(o))) scope.add(o);
        if (scope.isEmpty()) scope = checkScopeNow();
        if (scope.isEmpty()) {
            ModouUi.toast(host, "当前范围没有可校验的书源");
            return;
        }
        startWorkers(scope, deepFull);
    }

    /** 停止校验：立刻收尾，不等在途请求（否则要干等半分钟按钮才有反应） */
    private void stopCheck() {
        if (!checkRunning()) return;
        checkStop = true;
        if (checkBtnText != null) {
            checkBtnText.setText("正在停止…");
            checkBtnText.setEnabled(false);
        }
        finishReport("已停止（未完成的源未下结论）");
    }

    /* ---------- 分类 ---------- */

    /** 单个源归到哪一类；返回 -1 表示通过/不该出现在报告里 */
    private int catOf(JSONObject o) {
        String u = keyOf(o);
        Deep d = deep.get(u);
        if (d != null && "ok".equals(d.state)) return -1;
        if (d != null && "bad".equals(d.state)) {
            if (!d.netFail) return CAT_RULE;   // 真跑过详情/目录/正文且失败，才算确认规则失效
            // 快速阶段没拿到书 → 深检一步没跑，只是「没有可深检的书」。
            // 以前这一律归 CAT_RULE（报告直接挂「批量删除 N 个源」），于是 0 条的源
            // 顺着「skip → 自动进深检 → 没书 → 规则失效」被整批摆上删除台面。
            if (d.reason != null && d.reason.contains("没有可深检的书")) {
                return "rule".equals(skipKind.get(u)) ? CAT_RULE
                        : (isDeadReason(qReason.get(u)) ? CAT_DEAD : CAT_NET);
            }
            return isDeadReason(d.reason) ? CAT_DEAD : CAT_NET;
        }
        String q = quick.get(u);
        if ("ok".equals(q) || "nosearch".equals(q)) return -1;
        if ("bad".equals(q)) {
            if (Boolean.TRUE.equals(ruleBad.get(u))) return CAT_RULE;
            return isDeadReason(qReason.get(u)) ? CAT_DEAD : CAT_NET;
        }
        if ("skip".equals(q)) {
            // 按细分原因归类：只有「请求通了但没解析到书」才敢叫规则失效；
            // 网络层和「关键词都没命中」都归暂时不可用（停用保留，不进删除台面）。
            if ("rule".equals(skipKind.get(u))) return CAT_RULE;
            return isDeadReason(qReason.get(u)) ? CAT_DEAD : CAT_NET;
        }
        return -1;
    }

    /**
     * 站点是否「已经死了」：DNS 解析失败、域名过期、找不到主机。
     *
     * <p>与普通超时/403 分开：后者是「这次没通」，换网络/换时间可能恢复，只能停用；
     * 域名解析不了基本等于站点关停，留着只会一直占位。
     */
    private static boolean isDeadReason(String why) {
        if (why == null || why.isEmpty()) return false;
        String s = why.toLowerCase(java.util.Locale.ROOT);
        for (String w : new String[]{"unknownhost", "nxdomain", "no address", "host not found",
                "no such host", "name resolution", "域名解析", "解析失败", "无法解析",
                "找不到主机", "域名过期", "域名已过期", "dns"}) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    private void classifyReport() {
        catRule.clear();
        catNet.clear();
        catDead.clear();
        Set<String> seen = new java.util.HashSet<>();
        for (JSONObject o : reportBatch) {
            String u = keyOf(o);
            if (!seen.add(u)) continue;
            int c = catOf(o);
            if (c == CAT_RULE) catRule.add(o);
            else if (c == CAT_NET) catNet.add(o);
            else if (c == CAT_DEAD) catDead.add(o);
        }
    }

    private String buildSummary() {
        int r = catRule.size(), n = catNet.size(), d = catDead.size();
        int all = r + n + d;
        if (all == 0) {
            return "本轮 " + reportBatch.size() + " 个源全部通过，没有需要处理的。";
        }
        List<String> parts = new ArrayList<>();
        if (r > 0) parts.add(r + " 个规则失效");
        if (n > 0) parts.add(n + " 个暂时不可用（未下结论）");
        if (d > 0) parts.add(d + " 个疑似站点死亡");
        return "本轮 " + reportBatch.size() + " 个源，" + all + " 个未通过（"
                + TextUtils.join("、", parts) + "）。";
    }

    /** 动作行：左图标 + 文字，整行 48dp 可点（热区不再只有一行小字） */
    private View checkActionRow(int glyph, String label, int color, int padH, Runnable r) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ModouUi.dp(host, padH), 0, ModouUi.dp(host, padH), 0);
        row.setMinimumHeight(ModouUi.dp(host, 48));

        ImageView ic = new ImageView(host);
        ic.setScaleType(ImageView.ScaleType.CENTER);
        ic.setImageDrawable(new ModouGlyph(glyph, color,
                ModouUi.dp(host, 17), ModouUi.dp(host, 1.7f)));
        row.addView(ic, new LinearLayout.LayoutParams(
                ModouUi.dp(host, 18), ModouUi.dp(host, 18)));

        TextView t = ModouUi.tv(host, label, 13.5f, color);
        t.setPadding(ModouUi.dp(host, 12), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(ModouUi.dp(host, 12));
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        ModouUi.alpha(color, color == DANGER ? 0x1F : 0x14)), null, mask));
        row.setClickable(true);
        row.setOnClickListener((v) -> r.run());
        return row;
    }

    /**
     * 启动一轮体检：批量默认「快速（并发 5）→ 诊断报告」两步；
     * 只有 full=true（单源规则调试）才在搜索通过后追加「深度（并发 2）」。
     * 全程共用一个面板，进度不会再出现「归零重来」。
     *
     * @param full 单源规则调试时为 true：搜索通过的源也要跑 详情→目录→正文
     */
    private void startWorkers(List<JSONObject> list, boolean full) {
        SourceService svc = SourceService.get();
        if (svc == null) {
            ModouUi.toast(host, "书源服务未启动");
            return;
        }
        if (checkRunning()) {
            // 双保险：两个入口同时点「开始」时，第二份队列会和第一份抢同一个源的写权限
            ModouUi.toast(host, "体检进行中");
            return;
        }

        // 多源批量不属于「单源规则调试」：清掉可能残留的单源标记，
        // 否则批量跑完 finishReport 会弹出一个与本次无关的源的结果（「一个源全部通过」）
        if (list.size() != 1) debugTargetKey = null;

        // ---- 状态机显式回到起点：只有这里允许清空上一轮结论 ----
        stage = CheckStage.QUICK;
        checkStop = false;
        reportComplete = false;
        deepFull = full;
        catRule.clear();
        catNet.clear();
        catDead.clear();
        reportBatch.clear();
        reportBatch.addAll(list);
        ruleBad.clear();
        skipKind.clear();    // 上一轮的细分原因不能留到这轮，否则 catOf 会按旧口径归类
        deep.clear();        // 旧深检结论优先级高于 quick，不清会让本轮结果被上一轮盖住
        foundBook.clear();   // 上一轮残留的书会被深检借去用，验出来的结论是上一个源
        checkDoneText = "";
        nTotal = list.size();
        nDone = 0;

        // 丢掉书源定义缓存：刚编辑/导入的规则必须立刻生效，
        // 否则「改完规则点校验」跑的还是 2 秒前的旧定义，白改。
        svc.invalidateDefs();
        final List<JSONObject> queue = new ArrayList<>(list);
        AtomicInteger qOk = new AtomicInteger();
        AtomicInteger qBad = new AtomicInteger();
        AtomicInteger qSkip = new AtomicInteger();
        AtomicInteger qDone = new AtomicInteger();
        progressText = "快速校验 0/" + queue.size();

        // 面板如果正开着：就地切到进度态，不 dismiss、不重开
        renderCheckSheet();
        syncProgressViews();

        // 通知栏常驻进度：退出面板 / 切后台也能看到还在跑（Android 13+ 先要授权）
        SourceCheckNotify.askPermission(host);
        SourceCheckNotify.progress(host, progressText, 0, queue.size());

        // 并发 5：单源预算 20s 后，137 个源按 3 并发要跑 15 分钟以上；
        // 校验请求都是「一个源一次搜索」，5 路对站点和手机都不算重。
        try {
            runQueue(queue, 5, (o, next) -> quickCheckOne(o, () -> {
                String u = keyOf(o);
                if ("ok".equals(quick.get(u))) qOk.incrementAndGet();
                else if ("bad".equals(quick.get(u))) qBad.incrementAndGet();
                else if ("skip".equals(quick.get(u))) qSkip.incrementAndGet();
                nDone = qDone.incrementAndGet();
                refreshRow(o);
                progressText = "快速校验 " + nDone + "/" + queue.size()
                        + " · 有效 " + qOk.get() + " · 未判定 " + qSkip.get()
                        + (qBad.get() > 0 ? " · 失效 " + qBad.get() : "");
                syncProgressViews();
                next.run();
            }), () -> uiPost(() -> finishQuick(queue)));
        } catch (Throwable e) {
            // 队列没能启动（引擎/线程池异常）：必须把状态收回来，
            // 否则 stage 卡在 QUICK，之后每次点校验都只提示「体检进行中」。
            Diagnostics.capture("check.start", e);
            finishReport("校验收尾异常：" + e.getClass().getSimpleName());
        }
    }

    /** 把最新进度刷到统计行与体检面板（可能在后台回调线程被调用，统一投主线程） */
    private void syncProgressViews() {
        uiPost(() -> {
            if (status != null) status.setText("共 " + items.size() + " 个 · " + progressText);
            if (checkProgress != null) renderProgressNum();
            if (checkProgressStat != null) checkProgressStat.setText(progressStatText());
            if (checkProgressBar != null) {
                int max = Math.max(1, nTotal);
                checkProgressBar.setMax(max);
                checkProgressBar.setProgress(Math.min(nDone, max));
            }
        });
        // 通知栏同步：注意**不能**放进 uiPost —— 用户退出书源页后 uiPost 会整块丢弃，
        // 通知就永远停在「校验中」，跑完了也不收走。
        if (checkRunning()) SourceCheckNotify.progress(host, progressText, nDone, nTotal);
    }

    /**
     * 阶段 1 收尾：快速校验跑完 → 就地切到诊断报告。
     *
     * <p>0.9.137 起批量体检到此为止：未判定的源（超时/403/0 条）手上没有书，
     * 深检一步都跑不了，以前把它们塞进「深度校验」只是让进度条空转一圈、
     * 报告还误称「深检实测失败」。只有单源「规则调试」（{@code full=true}）
     * 才对搜索通过且抓到书的源追加跑 详情 → 目录 → 正文。
     */
    private void finishQuick(List<JSONObject> queue) {
        if (!checkRunning()) return;   // 已停止/已收尾：在途回调不能再走一遍
        if (checkStop) {
            finishReport("已停止：本轮只跑完部分源，以下仅就已完成的给出结论。");
            return;
        }
        int sOk = 0, sBad = 0, sSkip = 0;
        for (JSONObject o : queue) {
            String q = quick.get(keyOf(o));
            if ("ok".equals(q)) sOk++;
            else if ("bad".equals(q)) sBad++;
            else if ("skip".equals(q)) sSkip++;
        }
        progressText = "快速校验完成 " + queue.size() + "/" + queue.size()
                + " · 有效 " + sOk + " · 未判定 " + sSkip
                + (sBad > 0 ? " · 失效 " + sBad : "");
        syncProgressViews();

        List<JSONObject> deepList = new ArrayList<>();
        // 0.9.137：批量体检不再把「未判定」的源塞进深度阶段。这些源手上没有书，
        // deepCheckOne 会立刻 netFail 返回，一个详情/目录/正文请求都发不出去 ——
        // 进度条空转一圈，报告却写着「深检实测失败」，名不副实。
        // 深度校验只在单源「规则调试」（full=true）时，对搜索通过且抓到书的源真正跑三步。
        if (deepFull) {
            for (JSONObject o : queue) {
                String q = quick.get(keyOf(o));
                if ("ok".equals(q) && foundBook.containsKey(keyOf(o))) deepList.add(o);
            }
        }
        if (deepList.isEmpty()) {
            finishReport(null);
            return;
        }

        // ---- 第二阶段：同一个面板就地切标题与进度，进度条重新从 0 起算 ----
        stage = CheckStage.DEEP;
        nTotal = deepList.size();
        nDone = 0;
        progressText = "深度校验 0/" + deepList.size();
        renderCheckSheet();
        syncProgressViews();

        AtomicInteger dPass = new AtomicInteger();
        AtomicInteger dFail = new AtomicInteger();
        AtomicInteger dDone = new AtomicInteger();
        try {
            runQueue(deepList, 2, (o, next) -> deepCheckOne(o, () -> {
                String u = keyOf(o);
                Deep d = deep.get(u);
                if (d != null && "ok".equals(d.state)) dPass.incrementAndGet();
                else dFail.incrementAndGet();
                nDone = dDone.incrementAndGet();
                refreshRow(o);
                progressText = "深度校验 " + nDone + "/" + deepList.size()
                        + " · 通过 " + dPass.get() + " · 未通过 " + dFail.get();
                syncProgressViews();
                next.run();
            }), () -> uiPost(() -> {
                if (!checkRunning()) return;
                if (checkStop) {
                    finishReport("已停止：本轮只跑完部分源，以下仅就已完成的给出结论。");
                    return;
                }
                finishReport(null);
            }));
        } catch (Throwable e) {
            Diagnostics.capture("check.deep.start", e);
            finishReport("深度校验未能启动：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 收尾：把结果按失败原因分成三类，面板就地切到「诊断报告」。
     *
     * <p>以前这里是 dismiss 旧面板 + open 新面板：闪一下、进度归零，
     * 看起来就像「又回到起点」。现在只换内容，Dialog 不动。
     */
    private void finishReport(String msg) {
        if (!checkRunning()) return;   // 幂等：停止会先调一次，在途回调回来不能再走一遍
        stage = CheckStage.REPORT;

        // 停止时那些正在飞的请求不会回来写结果，状态会永远停在「校验中」。
        // 统一在这里抹掉 run：没结论比假结论好，用户还能再点一次。
        for (Map.Entry<String, String> e : quick.entrySet()) {
            if ("run".equals(e.getValue())) e.setValue("none");
        }
        java.util.Iterator<Map.Entry<String, Deep>> it = deep.entrySet().iterator();
        while (it.hasNext()) {
            Deep d = it.next().getValue();
            if (d != null && "run".equals(d.state)) it.remove();
        }

        classifyReport();
        reportComplete = (msg == null || msg.isEmpty());
        checkDoneText = reportComplete ? buildSummary() : msg;

        // 通知从「进度条」换成结果摘要，8 秒后自动收走。
        // 放在 uiPost 外面：面板已经不在了也必须把这条常驻通知收掉。
        // 中途停止/异常时额外补一句统计，否则通知里只剩「已停止」，看不出查出了什么。
        SourceCheckNotify.finish(host,
                reportComplete ? checkDoneText : checkDoneText + " " + buildSummary());
        uiPost(() -> {
            // 体检刚写过健康度，列表第二行还是旧快照 —— 不重读就会看到
            // 「刚刚判失效」的源旁边写着「近 7 天 100% 可用」。
            health = SourceHealth.all(host);
            refreshRoundFail();
            refreshLoginKeys();   // 体检可能刚撞到登录页，筛选/清理要立刻看到
            paint();
            pruneCategories();
            if (debugTargetKey != null) {
                // 单源调试：报告弹窗对「1 个源」没意义（只剩「1/1 通过」一句），
                // 直接关掉校验面板、只弹调试结果，避免「校验面板 + 调试结果」两层叠在一起。
                String k = debugTargetKey;
                debugTargetKey = null;
                dismissCheckSheet();
                showDebugResult(k);
            } else {
                renderCheckSheet();
            }
        });
    }

    /** 校验收尾只重建「本轮未通过」集合，供筛选菜单手动查看。
     *  0.9.137 起不再自动把列表切成这一批：校验完突然只剩几十条，
     *  正是用户以为「一半源都校验失败」的观感来源。想看就在「筛选状态」里点。 */
    private void refreshRoundFail() {
        syncRoundFail();
        if (roundFail.isEmpty()) roundFailOnly = false;
    }

    /** 重建「本轮未通过」集合：只收真正要处理的（规则失效 + 站点疑似死亡）。
     *  「暂时不可用」（超时 / 403 / 关键词 0 条）是未下结论，不该混进来 —— 否则一轮
     *  体检完列表被缩成一大片，看着就像「一半源都坏了」。 */
    private void syncRoundFail() {
        roundFail.clear();
        for (JSONObject o : catRule) roundFail.add(keyOf(o));
        for (JSONObject o : catDead) roundFail.add(keyOf(o));
    }

    /** 单行状态变化：只重绑那一行（不在屏上就等它滚回来），不整表重画 */
    private void refreshRow(JSONObject o) {
        uiPost(() -> {
            updateRowStatus(rowPosOf(o));
            saveCheckStates();
        });
    }

    /* ---------- 校验状态落库 ---------- */

    private static final String K_CHECK = "srcCheckState";   // settings 表的一个键

    /**
     * 校验兜底词。探针词（首页真书名）拿不到时才轮到它。
     * 不用「三体」：科幻词在言情/都市为主的免费站上天然 0 条，会把好源整批误判成失效。
     */
    private static final String FALLBACK_WORD = "我的";

    /** 0.9.43：校验结论落库。以前只挂内存，重启 App 137 个源一夜回到「未校验」 */
    private void saveCheckStates() {
        try {
            JSONObject o = new JSONObject();
            o.put("q", new JSONObject(quick));
            o.put("r", new JSONObject(qReason));
            o.put("sk", new JSONObject(skipKind));
            JSONArray rb = new JSONArray();
            for (Map.Entry<String, Boolean> e : ruleBad.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) rb.put(e.getKey());
            }
            o.put("rb", rb);
            JSONObject row = new JSONObject();
            row.put("k", K_CHECK);
            row.put("v", o.toString());
            ModouStore.get(host).put("settings", row.toString());
        } catch (Exception ignore) {
            /* 存不下最多是下次重新校验，不影响主流程 */
        }
    }

    /** 面板首次打开时把上次的结论读回内存（只在表空时读，不覆盖正在跑的这一轮） */
    private void loadCheckStates() {
        if (!quick.isEmpty() || !qReason.isEmpty()) return;
        try {
            String s = ModouStore.get(host).get("settings", K_CHECK);
            if (s == null) return;
            JSONObject w = new JSONObject(s);
            JSONObject o = new JSONObject(w.optString("v", "{}"));
            fillMap(quick, o.optJSONObject("q"));
            fillMap(qReason, o.optJSONObject("r"));
            fillMap(skipKind, o.optJSONObject("sk"));
            // 0.9.136 起快速校验不再凭「0 条」判死，也就不再产生 ruleBad。
            // 旧版落库的 rb 全是这类误判，读回来会让升级后仍显示一片「规则失效」红，
            // 所以这里刻意不读 —— 重新体检一次即按新口径给出结论。
        } catch (Exception ignore) {
            /* 老数据没存过 / 解析失败：当没校验过 */
        }
    }

    private static void fillMap(Map<String, String> m, JSONObject src) throws Exception {
        if (src == null) return;
        java.util.Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String k = it.next();
            m.put(k, src.optString(k, ""));
        }
    }

    /** 勾选一行：只重绑这一行 + 刷新顶栏计数，不再整页 paint（137 行也不掉帧） */
    private void toggleOne(String id) {
        if (sel.contains(id)) sel.remove(id);
        else sel.add(id);
        // rows 里的 selected 是 DiffUtil 的输入，先同步再局部刷，避免下次 submit 判成「没变」
        for (Row r : rows) {
            if (r.kind == Row.ITEM && id.equals(r.src.optString("id", ""))) {
                r.selected = sel.contains(id);
                break;
            }
        }
        syncSelectionViews(id);
    }

    /** 长按行进入多选（手动排序模式下长按是拖手柄，不走这里） */
    private void enterSelection(JSONObject o) {
        selMode = true;
        sel.clear();
        sel.add(o.optString("id", ""));
        paint();
    }

    /** 顶栏计数 / 全选文案 / 底栏启停文案：能就地改的就地改，不重建列表 */
    private void syncSelectionViews(String changedId) {
        if (cabCount != null) cabCount.setText("已选 " + sel.size() + " 项");
        if (cabSelectAll != null) cabSelectAll.setText(allVisibleSelected() ? "取消全选" : "全选");
        if (switchLabel != null) switchLabel.setText(toggleLabel());
        JSONObject o = null;
        for (JSONObject x : items) {
            if (changedId != null && changedId.equals(x.optString("id", ""))) { o = x; break; }
        }
        if (o != null) updateRowAt(rowPosOf(o));
    }

    /* ---------- 单源快速校验 ---------- */

    /**
     * 写一条「未判定」结论（灰）。体检阶段所有下不了结论的失败都走这里，一次收口三件事：
     * 状态值、细分原因、失败留档。
     *
     * <p>刻意不调 {@link SourceHealth#fail}：体检是 5 路并发打几百个站的探测，不是真实
     * 阅读请求，天然比日常搜索更容易超时/被限流。记进健康度会让刚导入、一次都没用过的
     * 新源立刻背上失败样本，行尾挂「高病发」，还凑够样本被「一键禁用」捞走。
     */
    private void markSkip(String key, String url, String kind, String reason) {
        quick.put(key, "skip");
        skipKind.put(key, kind);
        qReason.put(key, reason);
        SourceHealth.noteCheck(host, url, reason);
    }

    /**
     * 只有发现页的源：用 explore 拿一页列表来判活。
     *
     * <p>判定与搜索一致：拿到书算有效；页面通了但一条都没解析出来算未判定。
     * 这类源在 Legado 里也是靠"发现"校验的，不能因为没配 searchUrl 就说它坏了。
     */
    private void checkByExplore(JSONObject o, Runnable onDone) {
        final String url = o.optString("bookSourceUrl", "");
        final String key = keyOf(o);
        SourceService svc = SourceService.get();
        if (svc == null) {
            markSkip(key, url, "net", "书源服务未启动");
            onDone.run();
            return;
        }
        Set<String> one = new LinkedHashSet<>();
        one.add(url);
        // includeDisabled：体检要看「这个源还能不能用」，与「搜书时用不用它」无关
        svc.explore(one, 1, null, true, res -> {
            if (checkStop) {
                onDone.run();
                return;
            }
            try {
                if (res == null || !res.optBoolean("ok", false)) {
                    String err = res == null ? "无返回" : res.optString("error", "?");
                    if (err.contains("没有书源配置了发现规则") || err.contains("没有可用的书源")) {
                        // 配置问题不是源的死活问题：标「无搜索」，别染红
                        quick.put(key, "nosearch");
                        qReason.put(key, "没有可用的发现页规则");
                    } else {
                        // 发现页请求没通是网络/服务层问题，灰着不下结论，更不能记健康度
                        markSkip(key, url, "net", "发现页请求失败：" + err);
                    }
                } else {
                    // 引擎 explore 的返回是 {groups:[{sourceUrl, items:[书…]}], fails}，
                    // **没有 list 字段**。以前这里读 res.list，永远取到 null ——
                    // 于是所有「只有发现页」的源一律被判「未判定」，既不会变绿，
                    // 也永远拿不到一本书给深检用。这是校验链路里最不起眼的一处硬伤。
                    JSONObject first = null;
                    JSONArray groups = res.optJSONArray("groups");
                    String gUrl = url;
                    if (groups != null) {
                        for (int i = 0; i < groups.length() && first == null; i++) {
                            JSONObject g = groups.optJSONObject(i);
                            if (g == null) continue;
                            JSONArray its = g.optJSONArray("items");
                            if (its == null || its.length() == 0) continue;
                            first = its.optJSONObject(0);
                            String gu = g.optString("sourceUrl", "");
                            if (!gu.isEmpty()) gUrl = gu;
                        }
                    }
                    if (first != null) {
                        quick.put(key, "ok");
                        qReason.remove(key);
                        ruleBad.remove(key);
                        try {
                            first.put("sourceUrl", gUrl); // 深检要靠它反查书源
                        } catch (Exception ignore) {
                            /* 不可能 */
                        }
                        foundBook.put(key, java.util.Collections.singletonList(first));
                        SourceHealth.ok(host, url);
                    } else {
                        JSONObject f1 = firstFail(res);
                        String why = f1 == null ? "" : f1.optString("reason", "");
                        int st = f1 == null ? 0 : f1.optInt("status", 0);
                        if (!why.isEmpty() && !reached(why, st)) {
                            // 发现页也没请求通：网络层，不下结论
                            markSkip(key, url, "net", why);
                        } else {
                            markSkip(key, url, "rule", "发现页有响应但没解析到书，换时间再校验");
                        }
                    }
                }
            } catch (Exception e) {
                markSkip(key, url, "net", "异常：" + e.getMessage());
            }
            onDone.run();
        });
    }

    /**
     * 快速校验：只跑搜索一步。结果写 quick/qReason，命中书存 foundBook。
     *
     * <p>关键词按阶梯来：书源自带的 {@code checkKeyWord}（作者知道这个词在这个站搜得到）
     * → 用户填的词 → 兜底一个通用热词。以前只试一个词，一次 0 条就记「未判定」，
     * 一百多个源能剩一半灰的，用户根本不知道哪些该删 —— 换词再搜一次，绝大多数当场定案。
     */
    private void quickCheckOne(JSONObject o, Runnable onDone) {
        final String url = o.optString("bookSourceUrl", "");
        final String key = keyOf(o);
        if (checkStop) {
            onDone.run();
            return;
        }
        quick.put(key, "run");
        deep.remove(key);       // 重新快速校验 → 旧的深度结论作废，否则「两次失效」拿旧数据骗人
        foundBook.remove(key);  // 同上：上一次留下的书不能被这次深检借用
        ruleBad.remove(key);
        skipKind.remove(key);   // 本轮重新判定，别让上轮的细分原因留到这轮
        if (!isCheckable(o)) {
            // 没有 searchUrl 的源（纯发现页、登录态源）：只要有 exploreUrl 就走发现页校验。
            // Legado 校验也是这么兜底的 —— 只有两者都没有，才算"无从校验"。
            if (!o.optString("exploreUrl", "").trim().isEmpty()) {
                checkByExplore(o, onDone);
                return;
            }
            quick.put(key, "nosearch"); // 只有发现页/登录态的源没有搜索能力，不算失效
            onDone.run();
            return;
        }
        SourceService svc = SourceService.get();
        if (svc == null) {
            markSkip(key, url, "net", "书源服务未启动");
            onDone.run();
            return;
        }
        // 关键词阶梯（最多三个，再多就不是「校验」而是「替用户搜书」了）：
        //   ① 源作者自带的 checkKeyWord —— 他知道这个词在这个站搜得到
        //   ② 用户手动填的词
        //   ③ 探针词：本站首页上真实存在的书名（下面 probe），比任何「通用热词」都准
        //   ④ 兜底词「我的」：探针也拿不到时才会用到
        // 有 checkKeyWord 的源不再探针 —— 作者给的词已经是最好的，别浪费一次首页请求。
        final List<String> kws = new ArrayList<>();
        String ck = checkWordOf(o);   // ruleSearch.checkKeyWord，已过滤掉非搜索词
        if (!ck.isEmpty()) kws.add(ck);
        if (keyword != null && !keyword.trim().isEmpty() && !kws.contains(keyword.trim())) {
            kws.add(keyword.trim());
        }
        if (!ck.isEmpty()) {
            if (kws.size() < 2 && !kws.contains(FALLBACK_WORD)) kws.add(FALLBACK_WORD);
            tryKeyword(svc, o, url, key, kws, 0, System.currentTimeMillis(), onDone);
            return;
        }
        // 没有 checkKeyWord：先问这个站的首页要几个真实书名（探针词），再拿它们去搜。
        // 取两个候选而不是一个：站内搜索多为模糊匹配，完整书名未必命中（实测某站
        // 「翘臀小奶狗」0 条、「小奶狗」4 条），多试一个词能把命中率提上去。
        svc.probe(o, res -> {
            if (checkStop) {
                onDone.run();
                return;
            }
            String p = res != null && res.optBoolean("ok", false) ? res.optString("keyword", "").trim() : "";
            if (!p.isEmpty() && !kws.contains(p)) kws.add(p);
            org.json.JSONArray cands = res == null ? null : res.optJSONArray("candidates");
            for (int i = 0; cands != null && i < cands.length() && kws.size() < 3; i++) {
                String c = cands.optString(i, "").trim();
                if (!c.isEmpty() && !kws.contains(c)) kws.add(c);
            }
            if (!kws.contains(FALLBACK_WORD)) kws.add(FALLBACK_WORD);
            tryKeyword(svc, o, url, key, kws, 0, System.currentTimeMillis(), onDone);
        });
    }

    /**
     * 用一个关键词探一次；通了但 0 条就换下一个词，词用完了才下结论。
     *
     * <p>判定只有三档：拿到书 → 有效；请求没通 → 网络层失效（灰，永不删）；
     * 两个词都「HTTP 2xx + 0 条」→ 规则层失效（红，进待删清单）。
     */
    private void tryKeyword(final SourceService svc, final JSONObject o, final String url,
                            final String key, final List<String> kws, final int idx,
                            final long t0, final Runnable onDone) {
        if (checkStop) {
            onDone.run();
            return;
        }
        // 两次搜索超过 60 秒就收手：体检是批量活，不能让一个源吃掉整轮
        if (idx > 0 && System.currentTimeMillis() - t0 > 60000) {
            markSkip(key, url, "net", "换关键词重搜超时，未下结论");
            onDone.run();
            return;
        }
        final String kw = kws.get(idx);
        Set<String> one = new LinkedHashSet<>();
        one.add(url);
        // check=true：单源 20s + 一次重试；includeDisabled=true：停用源也要能体检
        svc.search(kw, one, 1, true, true, res -> {
            if (checkStop) {
                onDone.run();
                return;
            }
            // 记录本次搜索耗时（响应时间排序用）：无论结论如何，一次完整搜索的时间都算数
            respMs.put(url, System.currentTimeMillis() - t0);
            try {
                if (res == null || !res.optBoolean("ok", false)) {
                    // 引擎直接回错（无网络 / 服务未启动 / 参数异常）：网络层，不下结论
                    markSkip(key, url, "net",
                            "请求失败：" + (res == null ? "无返回" : res.optString("error", "?")));
                    onDone.run();
                    return;
                }
                // 取前 3 本候选（首本门槛：书名 + 详情链接非空，空壳已过滤）。
                // 快速校验「有效」只认首本是否合格；深度校验会按序上探，首本失败才试第 2、3 本
                // （短路上探：正常源只跑一本，成本与原来一致；坏源多探几本提升「确为失效」置信度）。
                List<JSONObject> books = firstBooksOf(res, url, 3);
                if (!books.isEmpty()) {
                    quick.put(key, "ok");
                    qReason.remove(key);
                    ruleBad.remove(key);
                    foundBook.put(key, books);
                    SourceHealth.ok(host, url);
                    onDone.run();
                    return;
                }
                // 首本门槛会过滤掉「只有书名、没有详情链接」的候选。这类不是网络问题，
                // 是 bookUrl 规则写坏；引擎没报 fails，旧代码会落进「返回 0 条 → 请求没通」
                // 的网络分支，诊断方向完全指反，还白记一次健康度失败。
                if (hasPartialBook(res, url)) {
                    markSkip(key, url, "rule", "解析到书名但缺详情链接（bookUrl 规则待确认）");
                    onDone.run();
                    return;
                }
                JSONObject f = firstFail(res);
                String reason = f == null ? "" : f.optString("reason", "");
                int st = f == null ? 0 : f.optInt("status", 0);
                if (reason.isEmpty()) reason = "返回 0 条（规则未解析到结果）";
                // 站点明确回了「无结果」页（搜索“xx”无结果 / 没有找到…）：说明站点确实执行了
                // 这次搜索 —— 编码对、参数对、规则也没坏，只是这个词站内没有。
                // 换词也许能出书，但**绝不能**因此把源判成失效；直接判可用，省掉后面的换词。
                if (f != null && f.optBoolean("noResult", false)) {
                    quick.put(key, "ok");
                    qReason.put(key, "搜索正常，站内没有「" + kw + "」的结果");
                    ruleBad.remove(key);
                    SourceHealth.ok(host, url);
                    onDone.run();
                    return;
                }
                // 风控/登录墙拦截：归网络/风控层（灰，不进删除清单）。
                // 优先于 reached —— 即使未来某次文案改动误带 "HTTP 2" 子串，也被这里截住，
                // 不会被当成「请求通了→规则失效」误推进删除清单。
                if (netFail(reason) || !reached(reason, st)) {
                    // 超时 / 403 / DNS / 空响应 / 风控页：这次没通或站点拦了，
                    // 换台网络或换时间可能就好。注释一直写着「绝不能判死」，
                    // 代码却写 quick="bad" 让状态点变红、还被「仅看校验失败」全捞走 ——
                    // 5 路并发批量体检时，慢站/风控站成片变红就是这么来的。
                    markSkip(key, url, "net", reason);
                    onDone.run();
                    return;
                }
                if (idx + 1 < kws.size()) {
                    // 站点有响应只是这个词没命中：换下一个词再探一次
                    tryKeyword(svc, o, url, key, kws, idx + 1, t0, onDone);
                    return;
                }
                // 多个词都「通了但 0 条」：可能是规则失效，也可能是站内压根没这些书、
                // 站点换了种「暂无数据」的文案、或返回纯 JS 空壳。快速校验只跑搜索一步，
                // 手上连一本书都没有，深检也无从确认；兜底词「我的」在垂类站又常常 0 条。
                // 以前这里直接 ruleBad=true 进待删清单，新导入的源因此成片被误杀。
                // 现在只标「未判定·待确认」，报告归「暂时不可用」（停用保留），不摆上删除台面。
                // 把引擎给的原话留下（去掉开头的「HTTP 200 ·」）：用户要的是
                // 「解析到结果但缺书名」还是「列表规则没命中」，这两件事修法完全不同
                String detail = reason.replaceFirst("^HTTP \\s*\\d{3} \\s*·\\s*", "").trim();
                if (detail.isEmpty()) detail = "列表规则未匹配";
                markSkip(key, url, "unsure", kws.size()
                        + " 个关键词都 0 条（未下结论，可能是站内没这些书或规则待确认）：" + detail);
                // 分类在报告阶段统一算（catOf），这里只打标记
            } catch (Exception e) {
                markSkip(key, url, "net", "异常：" + e.getMessage());
            }
            onDone.run();
        });
    }

    /** 该源这一轮的第一条失败记录（每个请求只带一个源，不会有别人的） */
    private static JSONObject firstFail(JSONObject res) {
        JSONArray fails = res == null ? null : res.optJSONArray("fails");
        if (fails == null || fails.length() == 0) return null;
        return fails.optJSONObject(0);
    }

    /**
     * 这次请求到底通没通。
     *
     * <p>优先看引擎回的结构化 status；老版本引擎不带这个字段，退回看错误原文里的
     * 「HTTP 2xx」。「均与关键词无关」是解析成功但被相关性过滤掉，也算通。
     */
    private static boolean reached(String reason, int status) {
        if (status >= 200 && status < 300) return true;
        if (reason == null) return false;
        return reason.contains("HTTP 2") || reason.contains("均与关键词无关");
    }

    /* ---------- 单源深度校验（详情→目录→正文；搜索一步快速校验已跑过） ---------- */

    private void deepCheckOne(JSONObject o, Runnable onDone) {
        final String url = o.optString("bookSourceUrl", "");
        final String key = keyOf(o);
        if (checkStop) {
            onDone.run();
            return;
        }
        final long t0 = System.currentTimeMillis();
        final Deep d = new Deep();
        d.state = "run";
        deep.put(key, d);
        refreshRow(o);   // 让这一行立刻显示「深度校验中」，否则整段深检期间状态点还是旧的
        final SourceService svc = SourceService.get();
        if (svc == null) {
            d.state = "bad";
            d.step = "服务";
            d.reason = "书源服务未启动";
            d.netFail = true;
            onDone.run();
            return;
        }
        // 先看书：纯发现页的源如果上一轮从发现页拿到过一本书，详情/目录/正文照样能验。
        // 以前把「没有 searchUrl」拦在前面，这类源永远停在「无法深度校验」，
        // 明明能验的三步一次都不跑。候选可能有多本（快速校验存的前 3 本），按序上探。
        List<JSONObject> books = foundBook.get(key);
        if (books != null && !books.isEmpty()) {
            probeList(svc, o, key, url, books, 0, d, onDone);
            return;
        }
        if (!isCheckable(o)) {
            // 纯发现页源：上一轮 explore 都没拿到书，换搜索也搜不了。
            // 标 netFail 让它退回「未判定」，绝不能因为「无从深检」就判规则失效。
            d.state = "bad";
            d.step = "发现页";
            d.reason = "该源没有搜索规则，发现页也没解析到书";
            d.netFail = true;
            d.ms = 0;
            onDone.run();
            return;
        }
        // 走到这里说明快速校验没拿到书，深检无从下手。
        // 以前这里再用空关键词重搜一次"抢救"，可引擎第一步就是
        // `if(!keyword) 关键词为空` —— 这一步从来没成功过，纯粹白等 20 秒。
        // 换个词再搜也没意义：快速校验已经把两个词都试遍了。
        // 所以直接记「未下结论」，不判死、也不浪费一次请求。
        d.state = "bad";
        d.step = "搜索";
        d.reason = "没有可深检的书（快速校验未取到结果）";
        d.netFail = true;
        d.ms = System.currentTimeMillis() - t0;
        onDone.run();
    }

    /** 详情 → 目录 → 正文。单步超时都收紧：深检是批量活，一步 30 秒会把整轮拖到天亮 */
    private void probe(SourceService svc, JSONObject o, String key, String url,
                       JSONObject book, Deep d, Runnable onDone) {
        final long t0 = System.currentTimeMillis();
        svc.info(book, 20000, r1 -> {
            if (checkStop) {
                onDone.run();
                return;
            }
            if (r1 == null || !r1.optBoolean("ok", false)) {
                String why = r1 == null ? "无返回" : r1.optString("error", "详情页解析为空");
                // 搜索/发现结果里已经带了目录地址：详情页挂了不代表这本书打不开。
                // Legado 的判定同样是「book.tocUrl 非空就跳过详情」，照抄这一条能救回
                // 一大批「详情页要登录 / 被墙，但目录与正文都是公开的」源。
                if (!book.optString("tocUrl", "").trim().isEmpty()) {
                    d.note = "详情页失败，已跳过（" + why + "）";
                    probeToc(svc, o, key, url, book, d, t0, onDone);
                    return;
                }
                deepFail(url, d, "详情", why, t0, onDone);
                return;
            }
            // info 里有的字段写回 book（toc 需要的信息可能藏在里面）
            for (String k : new String[]{"kind", "lastChapter", "intro", "coverUrl", "author", "name", "tocUrl"}) {
                try {
                    String v = r1.optString(k, "");
                    if (!v.isEmpty()) book.put(k, v);
                } catch (Exception ignore) {
                    /* 单字段失败不影响整体 */
                }
            }
            probeToc(svc, o, key, url, book, d, t0, onDone);
        });
    }

    /** 目录 → 正文：详情成功与否都要走这一段（详情可跳过） */
    private void probeToc(SourceService svc, JSONObject o, String key, String url,
                          JSONObject book, Deep d, long t0, Runnable onDone) {
        svc.toc(book, 25000, r2 -> {
            if (checkStop) {
                onDone.run();
                return;
            }
            JSONArray chs = r2 == null ? null : r2.optJSONArray("chapters");
            if (r2 == null || !r2.optBoolean("ok", false) || chs == null || chs.length() == 0) {
                deepFail(url, d, "目录", r2 == null ? "无返回" : r2.optString("error", "未解析到章节"), t0, onDone);
                return;
            }
            JSONObject ch = chs.optJSONObject(0);
            if (ch == null || ch.optString("url", "").isEmpty()) {
                deepFail(url, d, "目录", "第一章没有正文地址", t0, onDone);
                return;
            }
            svc.content(book, ch, 20000, r3 -> {
                if (checkStop) {
                    onDone.run();
                    return;
                }
                String content = r3 == null ? "" : r3.optString("content", "");
                // 正文判定门槛：≥20 字符（非空白）。只判 length>0 会放过「.」「-」这类
                // 实质空正文，用户点进去看到空白页才发现源是坏的。
                if (r3 != null && r3.optBoolean("ok", false) && content.trim().length() >= 20) {
                    d.state = "ok";
                    d.step = "全部通过";
                    d.reason = "";
                    d.ms = System.currentTimeMillis() - t0;
                    d.chars = content.length();
                    // 正文能读，但走的是引擎的兜底识别 —— 说明源规则已过期。
                    // 不提示的话，用户会以为是源好了，换个页面（结构不同）又坏。
                    String auto = autoRuleOf(r3);
                    if (!auto.isEmpty()) {
                        d.note = "正文规则已过期（选择器在页面上已匹配不到），本次由引擎自动识别正文。"
                                + "建议把 ruleContent.content 改为：" + auto;
                    }
                    deepHealth(url, d);
                } else {
                    deepFail(url, d, "正文", r3 == null ? "无返回" : r3.optString("error", "正文为空"), t0, onDone);
                    return;
                }
                onDone.run();
            });
        });
    }

    /** 深度校验按序上探候选书：首本通过即停；首本 toc/正文失败才上探第 2、3 本。
     *  短路上探：正常源只跑一本，成本与原来一致；坏源多探几本提升「确为失效」的置信度。 */
    private void probeList(SourceService svc, JSONObject o, String key, String url,
                           List<JSONObject> books, int idx, Deep d, Runnable onDone) {
        if (checkStop) { onDone.run(); return; }
        if (idx >= books.size()) { onDone.run(); return; }
        final int cur = idx;
        probe(svc, o, key, url, books.get(cur), d, () -> {
            if (checkStop) { onDone.run(); return; }
            if ("ok".equals(d.state)) { onDone.run(); return; }   // 首本通过即停
            probeList(svc, o, key, url, books, cur + 1, d, onDone); // 失败上探下一本
        });
    }

    /** 正文是否走了引擎的兜底识别；是则返回建议规则，否则空串 */
    private static String autoRuleOf(JSONObject r) {
        if (r == null) return "";
        JSONArray tr = r.optJSONArray("trace");
        if (tr == null || tr.length() == 0) return "";
        JSONObject t = tr.optJSONObject(0);
        return t == null ? "" : t.optString("autoRule", "");
    }

    private void deepFail(String url, Deep d, String step, String why, long t0, Runnable onDone) {        d.state = "bad";
        d.step = step;
        d.reason = why;
        d.netFail = netFail(why);
        d.ms = System.currentTimeMillis() - t0;
        deepHealth(url, d);
        onDone.run();
    }

    /** 深检与快速校验同属体检：成功记一次正面样本，失败只留诊断原文、不写失败计数 */
    private void deepHealth(String url, Deep d) {
        if (d == null) return;
        if ("ok".equals(d.state)) {
            SourceHealth.ok(host, url);
        } else {
            SourceHealth.noteCheck(host, url, d.step + "：" + d.reason);
        }
    }

    /**
     * 有没有「解析出了书名、但没有详情链接」的候选。
     *
     * <p>有就说明列表规则、书名规则都在正常干活，坏的只是 bookUrl 规则；
     * 这类源不该被当成「请求没通」。{@link #firstBooksOf} 会把它们过滤掉，
     * 所以必须单独探一次，否则 0 本候选时根本分不清这两种情况。
     */
    private static boolean hasPartialBook(JSONObject res, String url) {
        JSONArray groups = res == null ? null : res.optJSONArray("groups");
        if (groups == null) return false;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            JSONArray hits = g == null ? null : g.optJSONArray("hits");
            if (hits == null) continue;
            for (int j = 0; j < hits.length(); j++) {
                JSONObject h = hits.optJSONObject(j);
                if (h == null || !url.equals(h.optString("sourceUrl", ""))) continue;
                JSONObject b = h.optJSONObject("book");
                if (b == null) continue;
                if (!b.optString("name", "").trim().isEmpty()
                        && b.optString("bookUrl", "").trim().isEmpty()) return true;
            }
        }
        return false;
    }

    /** 从搜索回包里取该源解析到的前 max 本候选（不看相关性，只看能不能解析出来）。
     *  门槛：书名 + 详情链接非空，空壳不进候选——否则深检一步就失败，纯属浪费请求。 */
    private static List<JSONObject> firstBooksOf(JSONObject res, String url, int max) {
        List<JSONObject> out = new ArrayList<>();
        if (res == null || max <= 0) return out;
        JSONArray groups = res.optJSONArray("groups");
        if (groups == null) return out;
        for (int i = 0; i < groups.length() && out.size() < max; i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            JSONArray hits = g.optJSONArray("hits");
            if (hits == null) continue;
            for (int j = 0; j < hits.length() && out.size() < max; j++) {
                JSONObject h = hits.optJSONObject(j);
                if (h == null || !url.equals(h.optString("sourceUrl", ""))) continue;
                JSONObject b = h.optJSONObject("book");
                if (b == null) continue;
                String nm = b.optString("name", "").trim();
                String bu = b.optString("bookUrl", "").trim();
                if (nm.isEmpty() || bu.isEmpty()) continue; // 空壳过滤，深检才有意义
                try { b.put("sourceUrl", url); } catch (Exception ignore) { /* 单本失败不影响 */ }
                out.add(b);
            }
        }
        return out;
    }

    /**
     * 失败原因是不是网络层。
     *
     * <p>这条判断决定源会不会被摆到「一键删除」的台面上：超时、403、DNS 挂掉全是
     * 一次性的，明天可能就好；只有「请求通了但规则抓不到数据」才是源真的坏了。
     */
    private static boolean netFail(String why) {
        if (why == null || why.isEmpty()) return false;
        String s = why.toLowerCase();
        for (String w : new String[]{"超时", "请求失败", "域名", "dns", "重定向", "无返回",
                "服务未启动", "证书", "ssl", "无法连接", "连接被", "异常：", "http 4", "http 5",
                "风控拦截", "登录墙", "访问限制", "挑战页",
                // 4xx/5xx 收敛后的纯中文 label（由 reason.js netReason 产出，不带状态数字）：
                // 仍靠这些关键词唯一归类，且双保险 status 字段（reached 对非 2xx 返回假）。
                "路径可能过期", "被限流", "被站点拒绝", "服务端错误", "拒绝访问",
                "规则执行异常", "正则", "回溯", "xpath", "syntaxerror", "typeerror", "解析异常"}) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    /* ================= 批量操作栏 ================= */

    /** 多选底部操作栏（Contextual Action Bar）：五等分，删除红色高亮。
     *  批量分组 / 导出等低频动作收进「更多」。 */
    private LinearLayout buildCheckBar() {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // 五等分的硬保证：weightSum + 每格 0dp/weight=1，任何人加一项都不会再挤在左边
        bar.setWeightSum(5f);
        // 底部悬浮条（Legado 多选操作栏同款）：圆角浮岛，不再通栏贴底
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ModouUi.dp(host, 16));
        bg.setColor(ui.card);
        bg.setStroke(ModouUi.dp(host, 1), ModouUi.alpha(ui.text, 0x14));
        bar.setBackground(bg);
        bar.setPadding(ModouUi.dp(host, 4), ModouUi.dp(host, 6),
                ModouUi.dp(host, 4), ModouUi.dp(host, 5));

        LinearLayout swItem = (LinearLayout) barItem(ModouGlyph.BLOCK, toggleLabel(), ui.text,
                v -> doBatch("批量开关"));
        switchLabel = (TextView) swItem.getChildAt(1); // 文案随选中项实时变：启用/禁用/切换

        bar.addView(barItem(ModouGlyph.GRID, "反选", ui.text, v -> invertSelection()), cell());
        bar.addView(barItem(ModouGlyph.REFRESH, "校验", ui.text, v -> doBatch("批量校验")), cell());
        bar.addView(barItem(ModouGlyph.TRASH, "删除", ui.danger, v -> doBatch("批量删除")), cell());
        bar.addView(swItem, cell());
        bar.addView(barItem(ModouGlyph.MORE_V, "更多", ui.text, v -> doBatch("更多")), cell());
        return bar;
    }

    /** 五等分单元格：0dp + weight 1（每个视图独立一份，LinearLayout 会改写复用实例） */
    private LinearLayout.LayoutParams cell() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** 操作栏单枚图标位：图标 + 标签竖排水平居中，整块可点 */
    private View barItem(int glyph, String label, int color,
                         android.view.View.OnClickListener click) {
        LinearLayout it = new LinearLayout(host);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);
        ImageView ic = ModouUi.glyphBtn(host,
                new ModouGlyph(glyph, color, ModouUi.dp(host, 20), ModouUi.dp(host, 1.7f)), 40, label);
        ic.setOnClickListener(click);
        it.addView(ic, new LinearLayout.LayoutParams(
                ModouUi.dp(host, 40), ModouUi.dp(host, 40)));
        TextView t = ModouUi.tv(host, label, 12f, color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setSingleLine(true);
        t.setPadding(0, ModouUi.dp(host, 1), 0, 0);
        it.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        it.setOnClickListener(click); // 图标和标签整块可点，不用瞄准 20dp 的小图
        return it;
    }

    /** 底栏「反选」：可见范围内取反。全不选时等价于全选可见项，所以单独这一枚就够 */
    private void invertSelection() {
        for (JSONObject o : items) {
            if (!passFilter(o)) continue;
            String id = o.optString("id", "");
            if (sel.contains(id)) sel.remove(id);
            else sel.add(id);
        }
        paint();
    }

    /** 启停按钮文案：选中项全启用→「禁用」，全禁用→「启用」，混合→「切换」 */
    private String toggleLabel() {
        int on = 0, off = 0;
        for (JSONObject o : items) {
            if (!sel.contains(o.optString("id", ""))) continue;
            if (o.optBoolean("enabled", true)) on++;
            else off++;
        }
        if (on == 0 && off == 0) return "启停";
        if (off == 0) return "禁用";
        if (on == 0) return "启用";
        return "启停";   // 混选：点了弹「全部启用 / 全部禁用」二选一
    }

    private void doBatch(String act) {
        List<JSONObject> picked = new ArrayList<>();
        for (JSONObject o : items) if (sel.contains(o.optString("id", ""))) picked.add(o);
        if (picked.isEmpty()) {
            ModouUi.toast(host, "先选择书源");
            return;
        }
        switch (act) {
            case "批量校验": {
                selMode = false;
                sel.clear();
                quick.clear();
                qReason.clear();
                skipKind.clear();
                deep.clear();
                ruleBad.clear();
                foundBook.clear();
                checkDoneText = "";
                // 保留筛选：用户是在筛选结果里勾选的，验完回到原视图，不突然跳回全部
                paint();
                checkForceAll = false;      // 范围就是 picked，与 checkScopeList() ①分支一致
                rerunScope = new ArrayList<>(picked);   // 重新校验仍跑这一批
                startWorkers(picked, false); // 先开跑（后台继续，关面板不停）
                openCheckAllSheet();        // 再把进度面板摆出来
                break;
            }
            case "批量删除": {
                ModouUi.confirm(host, "删除书源",
                        "确定删除选中的 " + picked.size() + " 个书源吗？删除后不可撤销。",
                        () -> {
                    for (JSONObject o : picked) {
                        String u = keyOf(o);   // 状态表用 id 当键，不能拿 url 清
                        ModouStore.get(host).del("sources", o.optString("id", ""));
                        quick.remove(u);
                        qReason.remove(u);
                        deep.remove(u);
                        ruleBad.remove(u);
                        foundBook.remove(u);
                    }
                    ModouUi.toast(host, "已删除 " + picked.size() + " 个");
                    selMode = false;
                    sel.clear();
                    reload();
                });
                break;
            }
            case "批量开关": {
                // 状态一致 → 直接整体取反（全启用就全禁用，反之亦然）；
                // 混选 → 绝不猜：弹窗让用户明确选「全部启用」还是「全部禁用」。
                int nOn = 0, nOff = 0;
                for (JSONObject o : picked) {
                    if (o.optBoolean("enabled", true)) nOn++;
                    else nOff++;
                }
                if (nOff == 0) batchEnable(picked, false, "已禁用");
                else if (nOn == 0) batchEnable(picked, true, "已启用");
                else openEnableChoiceSheet(picked, nOn, nOff);
                break;
            }
            case "更多": {
                openBatchMoreSheet(picked);
                break;
            }
        }
    }

    /** 批量分组：把选中源归到指定分组（输入新组名，或点选已有组） */
    private void openBatchGroupSheet(List<JSONObject> picked) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final EditText ed = ModouUi.input(host, "输入新分组名，或点下方已有分组", "", 1);
        box.addView(ed);
        final Dialog[] self = new Dialog[1];

        box.addView(sectionLabel("已有分组"));
        LinearLayout g = ui.groupCard(host);
        java.util.Set<String> gs = new java.util.LinkedHashSet<>();
        for (JSONObject o : items) gs.add(groupOf(o));
        for (final String grp : gs) {
            menuRow(g, grp, null, () -> {
                if (self[0] != null) self[0].dismiss();
                applyBatchGroup(picked, grp);
            });
        }
        box.addView(g);

        TextView ok = ModouUi.tv(host, "使用输入的分组名", 14, ui.accent);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, ModouUi.dp(host, 14), 0, ModouUi.dp(host, 6));
        ok.setOnClickListener((v) -> {
            String grp = String.valueOf(ed.getText()).trim();
            if (grp.isEmpty()) {
                ModouUi.toast(host, "先输入分组名");
                return;
            }
            if (self[0] != null) self[0].dismiss();
            applyBatchGroup(picked, grp);
        });
        box.addView(ok);
        self[0] = ModouUi.sheet(host, "批量分组 · 已选 " + picked.size() + " 个", box);
    }

    private void applyBatchGroup(List<JSONObject> picked, String group) {
        int n = 0;
        for (JSONObject o : picked) {
            try {
                o.put("bookSourceGroup", group);
                o.put("sourceGroup", group);   // Legado 字段兼容
                ModouStore.get(host).put("sources", o.toString());
                n++;
            } catch (Exception ignore) {
                /* 不可能 */
            }
        }
        ModouUi.toast(host, "已把 " + n + " 个书源移入「" + group + "」");
        selMode = false;
        sel.clear();
        reload();
    }

    /** 批量「更多」：启用 / 禁用 / 导出 —— 低频批量操作收纳于此，操作栏只留三枚高频 */
    private void openBatchMoreSheet(List<JSONObject> picked) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] self = new Dialog[1];
        LinearLayout g1 = ui.groupCard(host);
        // 0.9.168：整组统一接语义图标（设置型弹层），组内不全接会左右错位
        menuRow(g1, ListIcon.FILTER, "启用选中", picked.size() + " 个", () -> {
            if (self[0] != null) self[0].dismiss();
            batchEnable(picked, true);
        });
        menuRow(g1, ListIcon.FILTER, "禁用选中", null, () -> {
            if (self[0] != null) self[0].dismiss();
            batchEnable(picked, false);
        });
        menuRow(g1, ListIcon.EXPORT, "导出到剪贴板", null, () -> {
            if (self[0] != null) self[0].dismiss();
            JSONArray a = new JSONArray();
            for (JSONObject o : picked) a.put(o);
            ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("轻阅读源", a.toString()));
            ModouUi.toast(host, "已复制 " + picked.size() + " 个书源到剪贴板");
        });
        menuRow(g1, ListIcon.FILTER, "批量分组", null, () -> {
            if (self[0] != null) self[0].dismiss();
            openBatchGroupSheet(picked);
        });
        box.addView(g1);
        self[0] = ModouUi.sheet(host, "更多批量操作 · 已选 " + picked.size() + " 个", box);
    }

    /** 批量启用 / 禁用：写 enabled 并落库，退出多选 */
    private void batchEnable(List<JSONObject> picked, boolean on) {
        batchEnable(picked, on, on ? "已启用" : "已禁用");
    }

    private void batchEnable(List<JSONObject> picked, boolean on, String verb) {
        for (JSONObject o : picked) {
            try {
                o.put("enabled", on);
                ModouStore.get(host).put("sources", o.toString());
            } catch (Exception ignore) {
                /* 不可能 */
            }
        }
        ModouUi.toast(host, verb + " " + picked.size() + " 个");
        selMode = false;
        sel.clear();
        reload();
    }

    /**
     * 混选时的启停二选一：不猜用户意图。
     * 「逐条反转」看着聪明，实际没人能在点之前预知结果 —— 一律换成两个明确选项。
     */
    private void openEnableChoiceSheet(List<JSONObject> picked, int nOn, int nOff) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] self = new Dialog[1];
        LinearLayout g = ui.groupCard(host);
        menuRow(g, ListIcon.FILTER, "全部启用", "把 " + nOff + " 个禁用的打开", () -> {
            if (self[0] != null) self[0].dismiss();
            batchEnable(picked, true, "已启用");
        });
        menuRow(g, ListIcon.FILTER, "全部禁用", "把 " + nOn + " 个启用的关掉", () -> {
            if (self[0] != null) self[0].dismiss();
            batchEnable(picked, false, "已禁用");
        });
        box.addView(g);
        self[0] = ModouUi.sheet(host, "选中的 " + picked.size() + " 个源状态不一致", box);
    }

    /* ================= 检查重复 ================= */

    /** 与网页版 findDuplicates 同判定：先按地址、再按名称；每组保留分最高的一条。
     *  返回的每组数组：[0]=保留，[1..]=待删除。 */
    private List<JSONObject[]> findDuplicates() {
        List<JSONObject[]> groups = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        collectDup(items, used, groups, true);
        collectDup(items, used, groups, false);
        return groups;
    }

    private static String normUrl(String u) {
        String s = String.valueOf(u == null ? "" : u).trim().toLowerCase();
        if (s.startsWith("http://")) s = s.substring(7);
        else if (s.startsWith("https://")) s = s.substring(8);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static int dupScore(JSONObject s) {
        return (s.optBoolean("enabled", true) ? 8 : 0) + (s.optString("searchUrl", "").isEmpty() ? 0 : 4)
                + (s.optString("exploreUrl", "").isEmpty() ? 0 : 1)
                + (s.optString("bookSourceComment", "").isEmpty() ? 0 : 1);
    }

    private void collectDup(List<JSONObject> all, Set<String> used, List<JSONObject[]> out, boolean byUrl) {
        Map<String, List<JSONObject>> map = new HashMap<>();
        for (JSONObject s : all) {
            if (used.contains(s.optString("id", ""))) continue;
            String k = byUrl ? normUrl(s.optString("bookSourceUrl", ""))
                    : String.valueOf(s.optString("bookSourceName", "")).trim();
            if (k.isEmpty()) continue;
            List<JSONObject> l = map.get(k);
            if (l == null) {
                l = new ArrayList<>();
                map.put(k, l);
            }
            l.add(s);
        }
        for (List<JSONObject> l : map.values()) {
            if (l.size() < 2) continue;
            l.sort((a, b) -> dupScore(b) - dupScore(a));
            for (JSONObject s : l) used.add(s.optString("id", ""));
            JSONObject[] arr = new JSONObject[l.size()];
            arr[0] = l.get(0); // 保留
            for (int i = 1; i < l.size(); i++) arr[i] = l.get(i); // 待删除
            out.add(arr);
        }
    }

    /* ================= 清理需登录书源（0.9.157） ================= */

    /**
     * ⋮「清理需登录书源」：把批量导入里带登录门槛的源一次筛出来，确认后删掉。
     *
     * <p>判定口径全在 {@link SourceLogin}，分「明确 / 疑似」两组，默认全选、点行可取消；
     * 删除不可撤销，所以先把名单和依据摆出来看清楚，再走 ModouUi.confirm 二次确认。
     * 复用 ui.groupRow（标题 + 依据两行 + 右侧值）和 ModouUi.sheet 的固定 footer，
     * 不新造列表控件，也不新建一套删除逻辑。
     */
    private void openLoginCleanSheet() {
        if (items.isEmpty()) {
            ModouUi.toast(host, "还没有书源");
            return;
        }
        refreshLoginKeys();

        // 重新取一次「依据」用于逐行展示（loginKeys 只存了命中集合，不含原因）
        final Map<String, String> hist = SourceHealth.loginHistory(host);
        final List<JSONObject> certain = new ArrayList<>();
        final List<JSONObject> suspect = new ArrayList<>();
        final Map<String, String> whyMap = new HashMap<>();
        for (JSONObject o : items) {
            String histMsg = hist.get(o.optString("bookSourceUrl", ""));
            String qr = qReason.get(keyOf(o));
            String merged = (qr == null ? "" : qr) + (histMsg == null ? "" : " " + histMsg);
            SourceLogin.Verdict v = SourceLogin.check(o, merged);
            if (v.level == SourceLogin.NONE) continue;
            (v.level == SourceLogin.CERTAIN ? certain : suspect).add(o);
            whyMap.put(keyOf(o), v.why);
        }

        if (certain.isEmpty() && suspect.isEmpty()) {
            LinearLayout box = new LinearLayout(host);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = ModouUi.dp(host, 14);
            box.setPadding(p + 4, ModouUi.dp(host, 8), p + 4, 0);
            TextView t = ModouUi.tv(host,
                    "已检查 " + items.size() + " 个书源，没有发现需要登录的源。\n\n"
                            + "判定依据：书源声明了 loginUrl / loginUi / loginCheckJs、"
                            + "名称或注释标注需登录、或体检撞到登录页；"
                            + "「免登录 / 无需登录」的源不会被误判。", 12.5f, ui.sub);
            t.setLineSpacing(ModouUi.dp(host, 3), 1.35f);
            box.addView(t);
            ModouUi.sheet(host, "清理需登录书源", box);
            return;
        }

        final Set<String> picked = new LinkedHashSet<>();
        for (JSONObject o : certain) picked.add(keyOf(o));
        for (JSONObject o : suspect) picked.add(keyOf(o));

        final int pad = ModouUi.dp(host, 18);
        final LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout footer = new LinearLayout(host);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(pad, ModouUi.dp(host, 10), pad, 0);
        final Dialog[] ds = new Dialog[1];
        final Runnable[] rebuild = new Runnable[1];

        final TextView go = ModouUi.tv(host, "", 14f, 0xFFFFFFFF);
        go.setGravity(Gravity.CENTER);
        GradientDrawable g2 = new GradientDrawable();
        g2.setColor(0xFFA8433A);
        g2.setCornerRadius(ModouUi.dp(host, 10));
        go.setBackground(g2);
        go.setPadding(0, ModouUi.dp(host, 11), 0, ModouUi.dp(host, 11));
        footer.addView(go, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rebuild[0] = () -> {
            box.removeAllViews();
            box.setPadding(pad, ModouUi.dp(host, 4), pad, 0);
            TextView head = ModouUi.tv(host,
                    "检出 " + (certain.size() + suspect.size()) + " 个需登录书源：明确 "
                            + certain.size() + " 个、疑似 " + suspect.size()
                            + " 个。默认全选，点条目可取消；疑似项可能只是会员内容，删前看一眼依据。",
                    12.5f, ui.sub);
            head.setLineSpacing(ModouUi.dp(host, 3), 1.35f);
            head.setPadding(0, 0, 0, ModouUi.dp(host, 8));
            box.addView(head);
            if (!certain.isEmpty() && !suspect.isEmpty()) {
                TextView onlyCertain = ModouUi.tv(host,
                        "只删明确的 " + certain.size() + " 个（取消勾选全部疑似）", 12.5f, ui.accent);
                onlyCertain.setPadding(0, 0, 0, ModouUi.dp(host, 8));
                onlyCertain.setOnClickListener(v -> {
                    for (JSONObject o : suspect) picked.remove(keyOf(o));
                    rebuild[0].run();
                });
                box.addView(onlyCertain);
            }
            addLoginGroup(box, "明确需登录 · " + certain.size(), certain, whyMap, picked, rebuild[0]);
            addLoginGroup(box, "疑似需登录 · " + suspect.size(), suspect, whyMap, picked, rebuild[0]);
            int n = picked.size();
            go.setText(n == 0 ? "未选中任何书源" : "删除选中的 " + n + " 个书源");
            go.setEnabled(n > 0);
            go.setAlpha(n > 0 ? 1f : 0.45f);
        };
        rebuild[0].run();

        go.setOnClickListener(v -> {
            final int n = picked.size();
            if (n == 0) return;
            // 名单预览只放前 3 个，别让确认面板被几百条撑爆
            StringBuilder preview = new StringBuilder();
            int shown = 0;
            for (JSONObject o : certain) {
                if (shown >= 3) break;
                if (!picked.contains(keyOf(o))) continue;
                preview.append("· ").append(nameOf(o)).append("\n");
                shown++;
            }
            for (JSONObject o : suspect) {
                if (shown >= 3) break;
                if (!picked.contains(keyOf(o))) continue;
                preview.append("· ").append(nameOf(o)).append("\n");
                shown++;
            }
            if (n > 3) preview.append("… 等共 ").append(n).append(" 个\n");
            ModouUi.confirm(host, "删除需登录书源",
                    "将删除 " + n + " 个书源，删除后不可撤销。\n\n" + preview
                            + "\n如果只是暂时不想用，请改用「停用」；疑似项可能误判，可返回取消勾选。",
                    () -> {
                        int doneN = 0;
                        for (JSONObject o : new ArrayList<>(certain)) {
                            if (!picked.contains(keyOf(o))) continue;
                            ModouStore.get(host).del("sources", o.optString("id", ""));
                            forget(o);
                            doneN++;
                        }
                        for (JSONObject o : new ArrayList<>(suspect)) {
                            if (!picked.contains(keyOf(o))) continue;
                            ModouStore.get(host).del("sources", o.optString("id", ""));
                            forget(o);
                            doneN++;
                        }
                        loginOnly = false;
                        ModouUi.toast(host, "已删除 " + doneN + " 个需登录书源");
                        if (ds[0] != null) ds[0].dismiss();
                        reload();
                    });
        });

        ds[0] = ModouUi.sheet(host, "清理需登录书源", box, footer);
    }

    /** 一组检出结果：组卡内逐行「书名 + 依据 + 待删/保留」，点行 toggle 并就地重建 */
    private void addLoginGroup(LinearLayout box, String title, List<JSONObject> list,
                               Map<String, String> whyMap, Set<String> picked, Runnable rebuild) {
        if (list.isEmpty()) return;
        box.addView(sectionLabel(title));
        LinearLayout card = ui.groupCard(host);
        for (JSONObject o : list) {
            if (card.getChildCount() > 0) card.addView(ModouUi.groupDivider(host));
            final String k = keyOf(o);
            boolean on = picked.contains(k);
            card.addView(ui.groupRow(host, nameOf(o), on ? "待删" : "保留", whyMap.get(k),
                    on ? ui.accent : 0, () -> {
                        if (picked.contains(k)) picked.remove(k);
                        else picked.add(k);
                        rebuild.run();
                    }));
        }
        box.addView(card);
    }

    private void openDedupSheet() {
        if (items.isEmpty()) {
            ModouUi.toast(host, "还没有书源");
            return;
        }
        List<JSONObject[]> groups = findDuplicates();
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(host, 14);
        box.setPadding(p + 4, ModouUi.dp(host, 8), p + 4, 0);

        if (groups.isEmpty()) {
            box.addView(ModouUi.tv(host,
                    "已检查 " + items.size() + " 个书源，没有发现重复（按书源地址或名称判定）。", 12.5f, ui.sub));
            ModouUi.sheet(host, "检查重复", box);
            return;
        }
        int dupCount = 0;
        StringBuilder preview = new StringBuilder();
        for (JSONObject[] g : groups) {
            JSONObject keep = g[0];
            dupCount += g.length - 1;
            preview.append("保留：《").append(keep.optString("bookSourceName", keep.optString("bookSourceUrl", "")))
                    .append("》\n");
            for (int i = 1; i < g.length; i++) {
                JSONObject d = g[i];
                preview.append("删除：《").append(d.optString("bookSourceName", d.optString("bookSourceUrl", "")))
                        .append("》").append(d.optBoolean("enabled", true) ? "（已启用）" : "").append("\n");
            }
            preview.append("\n");
        }
        TextView head = ModouUi.tv(host, "发现 " + groups.size() + " 组重复，共 " + dupCount
                + " 个多余书源。每组默认保留「已启用 / 有搜索地址 / 字段更全」的那一条：", 12.5f, ui.sub);
        head.setPadding(0, 0, 0, ModouUi.dp(host, 6));
        box.addView(head);
        ScrollView sv = new ScrollView(host);
        TextView list = ModouUi.tv(host, preview.toString().trim(), 12, ui.text);
        list.setLineSpacing(ModouUi.dp(host, 1), 1.3f);
        sv.addView(list);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(host, 220)));
        box.addView(sv);

        final Dialog d = ModouUi.sheet(host, "检查重复", box);
        final int dupFinal = dupCount;
        TextView go = ModouUi.tv(host, "删除 " + dupFinal + " 个重复源（每组保留 1 个）", 14, 0xFFFFFFFF);
        go.setGravity(Gravity.CENTER);
        GradientDrawable g2 = new GradientDrawable();
        g2.setColor(0xFFA8433A);
        g2.setCornerRadius(ModouUi.dp(host, 10));
        go.setBackground(g2);
        go.setPadding(0, ModouUi.dp(host, 10), 0, ModouUi.dp(host, 10));
        LinearLayout gow = new LinearLayout(host);
        gow.setPadding(0, ModouUi.dp(host, 10), 0, 0);
        gow.addView(go, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(gow);
        go.setOnClickListener((v) -> ModouUi.confirm(host, "删除重复书源",
                "将删除 " + dupFinal + " 个重复书源，每组保留 1 个。删除后不可撤销。",
                () -> {
                    int n = 0;
                    for (JSONObject[] g : groups) {
                        for (int i = 1; i < g.length; i++) {
                            JSONObject dd = g[i];
                            String u = keyOf(dd);
                            ModouStore.get(host).del("sources", dd.optString("id", ""));
                            quick.remove(u);
                            qReason.remove(u);
                            deep.remove(u);
                            ruleBad.remove(u);
                            foundBook.remove(u);
                            n++;
                        }
                    }
                    ModouUi.toast(host, "已删除 " + n + " 个重复书源");
                    d.dismiss();
                    reload();
                }));
    }

    /* ================= 编辑 / 导入导出（沿用） ================= */

    /* ⋮ 顶栏菜单已迁移到全局 CascadingMenu（ColorOS 级联菜单），见 CascadingMenu.java */

    /**
     * ⋮ 顶栏菜单：ColorOS 级联卡片（0.9.50 从「主菜单 → 二级菜单整体替换」升级为
     * 就地展开的级联子菜单）。结构：新建 → 健康 → 分组与排序（级联）→ 导入导出 → 整理。
     */
    private void showTopMenu(View anchor) {
        // 字段 items = 书源列表；下面的局部变量会把它遮住，先取个数快照给「导出书源」用
        final int srcCount = items.size();
        java.util.List<CascadingMenu.Item> items = new java.util.ArrayList<>();

        items.add(CascadingMenu.Item.leaf("新建书源", () -> later(() -> {
            try {
                new SourceEditor(host).open(null);
            } catch (Throwable e) {
                ModouUi.toast(host, "打开编辑器失败：" + e);
            }
        })));
        items.add(CascadingMenu.Item.divider());
        /* 源健康度 */
        items.add(CascadingMenu.Item.leaf("校验当前列表", checkScopeLabel(), () -> {
            rerunScope = null;   // 从 ⋮ 进来 = 按当前筛选/全部，不再沿用上次的单源批次
            checkForceAll = false;   // 严格按当前范围：筛选→筛选，全部→全量
            later(this::openCheckAllSheetFresh);
        }));
        int broken = SourceHealth.brokenUrls(host).size();
        // 副标题点明口径：只数「近 7 天真实使用」中规则未匹配 / 正则报错且从未成功的源。
        // 与顶部「高病发源」芯片（失败率≥80%、含超时/403）是两套计数；体检不再写健康度，
        // 所以这里也不含刚跑完那轮校验的结果，数字变小是预期改善。
        items.add(CascadingMenu.Item.leaf("禁用坏源",
                broken == 0 ? null : "近 7 天真实使用失效 " + broken + " 个",
                this::confirmDisableBroken));
        items.add(CascadingMenu.Item.divider());
        /* 分组与排序：三个级联父项 */
        java.util.List<CascadingMenu.Item> sort = new java.util.ArrayList<>();
        sort.add(CascadingMenu.Item.check("按名称", "name".equals(sortBy),
                () -> applySort("name")));
        sort.add(CascadingMenu.Item.check("按更新时间", "update".equals(sortBy),
                () -> applySort("update")));
        sort.add(CascadingMenu.Item.check("按响应时间", "resp".equals(sortBy),
                () -> applySort("resp")));
        sort.add(CascadingMenu.Item.check("按启用状态", "enabled".equals(sortBy),
                () -> applySort("enabled")));
        // 健康度排序：失败率高的排最前（找毒瘤） / 成功率高的排最前（找能用的）
        sort.add(CascadingMenu.Item.check("按失败率（高 → 低）", "health_fail".equals(sortBy),
                () -> applySort("health_fail")));
        sort.add(CascadingMenu.Item.check("按成功率（高 → 低）", "health_ok".equals(sortBy),
                () -> applySort("health_ok")));
        sort.add(CascadingMenu.Item.check("手动排序（长按拖拽）", "custom".equals(sortBy),
                this::switchToCustomSort));
        sort.add(CascadingMenu.Item.divider());
        sort.add(CascadingMenu.Item.check("反序", reverseSort,
                () -> { reverseSort = !reverseSort; sortItems(); paint(); }));
        items.add(CascadingMenu.Item.parent("排序方式",
                sortLabel() + (reverseSort ? " · 反序" : ""), sort));

        java.util.List<CascadingMenu.Item> st = new java.util.ArrayList<>();
        st.add(CascadingMenu.Item.check("全部", quickFilterIdle(), () -> {
            clearStatusFilters();
            paint();
        }));
        st.add(CascadingMenu.Item.check("仅启用",
                enabledFilter.size() == 1 && enabledFilter.contains("on"), () -> {
            clearStatusFilters();
            enabledFilter.add("on");
            paint();
        }));
        st.add(CascadingMenu.Item.check("仅停用",
                enabledFilter.size() == 1 && enabledFilter.contains("off"), () -> {
            clearStatusFilters();
            enabledFilter.add("off");
            paint();
        }));
        st.add(CascadingMenu.Item.check("高失败率（≥80%）", brokenOnly, () -> {
            clearStatusFilters();
            brokenOnly = true;
            paint();
        }));
        // 「未判定」是体检后占比最大的一类（超时 / 403 / 关键词 0 条），
        // 直接进主筛选，不用钻进「更多筛选条件 → 状态」去勾。
        st.add(CascadingMenu.Item.check("仅未判定", skipOnly, () -> {
            clearStatusFilters();
            skipOnly = true;
            statusFilter.add("skip");
            paint();
        }));
        st.add(CascadingMenu.Item.check("仅需登录（" + loginKeys.size() + "）", loginOnly, () -> {
            clearStatusFilters();
            loginOnly = true;
            paint();
        }));
        // 本轮真正要处理的那批（规则失效 / 站点疑似死亡），手动切过去看
        if (!roundFail.isEmpty()) {
            st.add(CascadingMenu.Item.check("本轮未通过（" + roundFail.size() + "）",
                    roundFailOnly, () -> {
                        clearStatusFilters();
                        // 分组/搜索词会把这批源筛成 0 条，手动切过去就等于「点了没反应」；
                        // 原来自动模式会让这些维度一并让位，改成手动后这里得自己清。
                        groupFilter.clear();
                        searchKey = "";
                        searchExpand = false;
                        roundFailOnly = true;
                        paint();
                    }));
        }
        // 顶栏漏斗删除后，组合条件（状态 / 健康度 / 多分组）必须还有入口：
        // 就放在这一级子菜单的最后，勾选面板仍然是原来那张，能力零损失。
        st.add(CascadingMenu.Item.divider());
        st.add(CascadingMenu.Item.leaf("更多筛选条件…", comboFilterLabel(),
                this::openFilterSheet));
        items.add(CascadingMenu.Item.parent("筛选状态", quickFilterLabel(), st));

        java.util.List<CascadingMenu.Item> gs = new java.util.ArrayList<>();
        gs.add(CascadingMenu.Item.check("全部分组", groupFilter.isEmpty(),
                () -> { groupFilter.clear(); paint(); }));
        for (String g : knownGroups()) {
            gs.add(CascadingMenu.Item.check(g,
                    groupFilter.size() == 1 && groupFilter.contains(g), () -> {
                groupFilter.clear();
                groupFilter.add(g);
                paint();
            }));
        }
        items.add(CascadingMenu.Item.parent("分组",
                groupFilter.isEmpty() ? "全部" : groupFilter.iterator().next(), gs));
        items.add(CascadingMenu.Item.leaf("分组管理",
                knownGroups().size() + " 个分组", this::openGroupManager));
        items.add(CascadingMenu.Item.divider());
        /* 导入导出 */
        items.add(CascadingMenu.Item.leaf("本地导入", ".json / .txt", this::pickSourceFile));
        items.add(CascadingMenu.Item.leaf("网络导入", "从链接下载", this::showImportUrl));
        items.add(CascadingMenu.Item.leaf("粘贴书源 JSON", null, this::showImport));
        items.add(CascadingMenu.Item.leaf("导出书源", srcCount + " 个", this::exportAll));
        items.add(CascadingMenu.Item.divider());
        /* 其他整理 */
        items.add(CascadingMenu.Item.leaf("检查重复", null, this::openDedupSheet));
        items.add(CascadingMenu.Item.leaf("清理需登录书源",
                loginKeys.isEmpty() ? "未检出" : "检出 " + loginKeys.size() + " 个",
                this::openLoginCleanSheet));
        // 搜索地址缺 {{key}} / 路径失效是「一大批源同时搜不到」的常见根因，给个一键探测+改写入口
        items.add(CascadingMenu.Item.leaf("批量修复搜索入口", "缺关键词/路径失效", this::fixAllSearchEntries));

        CascadingMenu.show(host, anchor, items);
    }

    /* ================= 搜索入口自动修复 ================= */

    /**
     * 把一个源的 searchUrl 改成新值并持久化（单源应用 / 批量修复共用）。
     *
     * <p>sources 表是「一条源一行」（id = bookSourceUrl|bookSourceName），所以这里按 id 单条 upsert，
     * 不是整表覆盖 —— 整表覆盖会丢掉并发写入，也没必要。返回是否真的写成功。
     */
    static boolean patchSourceSearchUrl(Context ctx, String bookSourceUrl, String newSearchUrl) {
        if (bookSourceUrl == null || bookSourceUrl.isEmpty()
                || newSearchUrl == null || newSearchUrl.isEmpty()) return false;
        try {
            ModouStore store = ModouStore.get(ctx);
            JSONArray arr = new JSONArray(store.all("sources"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (!bookSourceUrl.equals(o.optString("bookSourceUrl", ""))) continue;
                if (newSearchUrl.trim().equals(o.optString("searchUrl", "").trim())) return true; // 已是该值
                o.put("searchUrl", newSearchUrl);
                store.put("sources", o.toString());   // 按 id 单条 upsert
                SourceService svc = SourceService.get();
                if (svc != null) svc.invalidateDefs();  // 规则变了，缓存立刻失效，校验才按新地址跑
                reloadActive();
                return true;
            }
        } catch (Exception ignore) {
            /* 忽略：源可能已被删除 */
        }
        return false;
    }

    /** ⋮「批量修复搜索入口」：对缺少 {{key}} 的源逐一探测真实搜索入口，确认后批量改写 */
    private void fixAllSearchEntries() {
        SourceService svc = SourceService.get();
        if (svc == null) {
            ModouUi.toast(host, "书源服务未启动，请重进 App 后再试");
            return;
        }
        final java.util.List<JSONObject> targets = new java.util.ArrayList<>();
        for (JSONObject o : items) {
            if (o == null) continue;
            if (o.optString("bookSourceUrl", "").trim().isEmpty()) continue;
            String su = o.optString("searchUrl", "").trim();
            // 只处理「搜索地址缺失关键词」的可疑源；已经带占位符的视为正常，不动它
            boolean hasKey = su.contains("{{key}}") || su.contains("{{ searchKey }}")
                    || su.contains("{key}") || su.contains("searchKey") || su.contains("{{$");
            if (!hasKey) targets.add(o);
        }
        if (targets.isEmpty()) {
            ModouUi.toast(host, "没有需要修复的源：搜索地址都带有关键词占位符");
            return;
        }
        ModouUi.confirm(host, "批量修复搜索入口",
                "将逐一探测 " + targets.size() + " 个「搜索地址缺关键词」的源的真实搜索入口"
                        + "（扒首页搜索框、试常见路径并真发请求验证）。\n"
                        + "只改写探测成功、且与当前不同的源；其余保持不动。需要联网，可能耗时较长。",
                () -> runFixBatch(svc, targets));
    }

    /** 关掉还在显示的批量修复弹层（进度或结果），保证同一时刻只有一张卡片 */
    private void dismissFixDialog() {
        if (fixDialog == null) return;
        final android.app.Dialog d = fixDialog;
        fixDialog = null;
        try {
            if (d.isShowing()) d.dismiss();
        } catch (Throwable ignore) {
            /* 宿主已经销毁：Dialog 随窗口一起没了，这里不用再处理 */
        }
    }

    private void runFixBatch(final SourceService svc, final java.util.List<JSONObject> targets) {
        final int total = targets.size();
        final java.util.List<JSONObject> fixes = new java.util.ArrayList<>();
        // 失败也要留档：以前只把成功的收进 fixes，失败的连原因都丢了，最后只剩一句
        // 「没有探测到可用搜索入口」——用户完全不知道是站点挂了、被 Cloudflare 拦、还是站内没这本书，
        // 只能反复重试。这一版把每个源的原因都带出来。
        final java.util.List<JSONObject> fails = new java.util.ArrayList<>();
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView tip = ModouUi.tv(host, "准备中… 0 / " + total, 14f, ui.text);
        tip.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 6));
        box.addView(tip);
        dismissFixDialog();
        final Dialog dlg = ModouUi.sheet(host, "正在探测搜索入口", box);
        fixDialog = dlg;
        final boolean[] stopped = {false};
        dlg.setOnDismissListener(d -> {
            stopped[0] = true;
            if (fixDialog == d) fixDialog = null;
        });
        final int[] idx = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (stopped[0]) return;
            if (idx[0] >= total) {
                try { dlg.dismiss(); } catch (Throwable ignore) { /* 忽略 */ }
                showFixBatchResult(fixes, fails, total);
                return;
            }
            final JSONObject o = targets.get(idx[0]);
            String name = o.optString("bookSourceName", o.optString("bookSourceUrl", ""));
            tip.setText("正在探测 " + (idx[0] + 1) + " / " + total + "：" + name);
            svc.discoverSearch(o, "", res -> {
                if (stopped[0]) return;
                boolean ok = res != null && res.optBoolean("ok", false);
                String ns = ok ? res.optString("searchUrl", "").trim() : "";
                String cur = o.optString("searchUrl", "").trim();
                if (ok && !ns.isEmpty() && !ns.equals(cur)) {
                    JSONObject f = new JSONObject();
                    try {
                        f.put("name", name);
                        f.put("url", o.optString("bookSourceUrl", ""));
                        f.put("newSearch", ns);
                        f.put("old", cur);
                    } catch (Exception ignore) { /* 忽略 */ }
                    fixes.add(f);
                } else if (!ok) {
                    String why = res == null ? "没有响应（可能超时）"
                            : res.optString("reason", res.optString("error", ""));
                    if (why == null || why.trim().isEmpty()) why = "未探测到可用入口";
                    JSONObject f = new JSONObject();
                    try {
                        f.put("name", name);
                        f.put("why", why.trim());
                        // 引擎给的「试了 N 条候选：被拦 X、超时 Y」：没有这一行，
                        // 「网站拦了」和「规则写错」在界面上长得一模一样。
                        f.put("detail", res == null ? "" : res.optString("detail", "").trim());
                    } catch (Exception ignore) { /* 忽略 */ }
                    fails.add(f);
                }
                // ok 但和当前一致：本来就是对的，不算失败，也不列出来
                idx[0]++;
                host.getWindow().getDecorView().post(step[0]);
            });
        };
        host.getWindow().getDecorView().post(step[0]);
    }

    /** 把失败原因归成几类，好让用户一眼看出「是这批源普遍打不开」还是「个别源的问题」 */
    private static String whyKind(String why) {
        if (why == null) return "其他";
        if (why.contains("Cloudflare") || why.contains("挑战页")) return "被 Cloudflare 拦";
        if (why.contains("间隔") || why.contains("频繁")) return "站点限流";
        if (why.contains("没有「") || why.contains("站内没有")) return "站内没有这本书";
        if (why.contains("无法访问") || why.contains("超时") || why.contains("没有响应")) return "首页打不开";
        if (why.contains("首页返回")) return "首页报错/已下线";
        if (why.contains("JS 渲染")) return "搜不到入口（可能是 JS 搜索/需登录）";
        return "其他";
    }

    private void showFixBatchResult(final java.util.List<JSONObject> fixes,
                                    final java.util.List<JSONObject> fails, final int total) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int fixed = fixes.size();
        int bad = fails.size();
        TextView head = ModouUi.tv(host,
                "探测 " + total + " 个源：可修复 " + fixed + " 个" + (bad > 0 ? "，未修复 " + bad + " 个" : ""),
                14f, ui.text);
        head.setPadding(0, 0, 0, ModouUi.dp(host, 6));
        box.addView(head);
        // 归类统计：一眼看出是不是「整批站点都连不上」，这决定用户下一步该做什么
        if (bad > 0) {
            java.util.LinkedHashMap<String, Integer> stat = new java.util.LinkedHashMap<>();
            for (JSONObject f : fails) {
                String k = whyKind(f.optString("why", ""));
                Integer c = stat.get(k);
                stat.put(k, c == null ? 1 : c + 1);
            }
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, Integer> e : stat.entrySet()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(e.getKey()).append(' ').append(e.getValue());
            }
            TextView st = ModouUi.tv(host, sb.toString(), 12f, ui.sub);
            st.setLineSpacing(ModouUi.dp(host, 2), 1.15f);
            st.setPadding(0, 0, 0, ModouUi.dp(host, 8));
            box.addView(st);
        }
        int shown = 0;
        if (fixed > 0) {
            box.addView(fixSection("可修复（将改写搜索地址）"));
            for (JSONObject f : fixes) {
                box.addView(fixRow("· " + f.optString("name", "") + "\n    " + f.optString("newSearch", ""), ui.text));
                shown++;
            }
        }
        if (bad > 0) {
            box.addView(fixSection(fixed > 0 ? "没修好的原因" : "没修好的原因（这些源的站点大概率是打不开或被拦，不是地址写错）"));
            for (JSONObject f : fails) {
                if (shown >= 60) break;   // 长列表截断，避免弹层塞几千行
                String why = f.optString("why", "");
                String det = f.optString("detail", "");
                String line = "· " + f.optString("name", "") + "\n    " + why;
                if (!det.isEmpty()) line += "\n    " + det;
                box.addView(fixRow(line, ui.sub));
                shown++;
            }
            if (shown < fixed + bad) {
                box.addView(fixRow("…另有 " + (fixed + bad - shown) + " 个未列出", ui.sub));
            }
        }
        LinearLayout footer = new LinearLayout(host);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, ModouUi.dp(host, 12), 0, 0);
        final Dialog[] d = new Dialog[1];
        footer.addView(fixPill(fixed > 0 ? "取消" : "知道了", false,
                () -> { if (d[0] != null) d[0].dismiss(); }), fixWeight(1f, 0));
        if (fixed > 0) {
            footer.addView(fixPill("全部应用（" + fixed + "）", true, () -> {
                int n = 0;
                for (JSONObject f : fixes) {
                    if (patchSourceSearchUrl(host, f.optString("url", ""), f.optString("newSearch", ""))) n++;
                }
                if (d[0] != null) d[0].dismiss();
                ModouUi.toast(host, "已修复 " + n + " 个源");
                reload();
            }), fixWeight(1.6f, ModouUi.dp(host, 10)));
        }
        dismissFixDialog();
        d[0] = ModouUi.sheet(host, "修复结果", box, footer);
        fixDialog = d[0];
        d[0].setOnDismissListener(x -> { if (fixDialog == x) fixDialog = null; });
    }

    private TextView fixSection(String text) {
        TextView t = ModouUi.tv(host, text, 12.5f, ui.text);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, ModouUi.dp(host, 8), 0, ModouUi.dp(host, 3));
        return t;
    }

    private TextView fixRow(String text, int color) {
        TextView t = ModouUi.tv(host, text, 12f, color);
        t.setLineSpacing(ModouUi.dp(host, 2), 1.2f);
        t.setPadding(0, ModouUi.dp(host, 3), 0, ModouUi.dp(host, 3));
        return t;
    }

    private TextView fixPill(String text, boolean primary, Runnable r) {
        TextView t = ModouUi.tv(host, text, 15, primary ? 0xFFFFFFFF : ui.text);
        t.setGravity(android.view.Gravity.CENTER);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, ModouUi.dp(host, 12), 0, ModouUi.dp(host, 12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(ModouUi.dp(host, 12));
        bg.setColor(primary ? ui.accent : ModouUi.alpha(ui.text, 0x0E));
        t.setBackground(bg);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private LinearLayout.LayoutParams fixWeight(float w, int leftMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, w);
        p.leftMargin = leftMargin;
        return p;
    }

    /** ⋮「筛选状态」子菜单的当前值（组合筛选由「更多筛选条件」承载） */
    private String quickFilterLabel() {
        if (loginOnly) return "仅需登录 " + loginKeys.size();
        if (roundFailOnly) return "本轮未通过 " + roundFail.size();
        if (skipOnly) return "仅未判定";
        if (brokenOnly) return "高失败率";
        if (enabledFilter.size() == 1) return enabledFilter.contains("on") ? "仅启用" : "仅停用";
        if (!statusFilter.isEmpty() || enabledFilter.size() > 1) return "自定义";
        return "全部";
    }

    /** 「更多筛选条件」副标题：已经叠了几维组合条件，0 维时留空不占位 */
    private String comboFilterLabel() {
        int n = 0;
        if (!groupFilter.isEmpty()) n++;
        if (!statusFilter.isEmpty()) n++;
        if (enabledFilter.size() > 1) n++;
        return n == 0 ? null : "已设 " + n + " 项";
    }

    /** 「全部」是否处于选中态：状态维度的任何一项有余量都不算（以前漏了 statusFilter，「仅未判定」时「全部」也打勾） */
    private boolean quickFilterIdle() {
        return enabledFilter.isEmpty() && statusFilter.isEmpty()
                && !brokenOnly && !skipOnly && !roundFailOnly && !loginOnly;
    }

    /** 组标题：groupTitle 统一上留白，让分组之间有呼吸，而不是卡片叠卡片 */
    private TextView sectionLabel(String t) {
        TextView v = ModouUi.groupTitle(host, t);
        int h = ModouUi.dp(host, 6);
        v.setPadding(h, ModouUi.dp(host, 14), h, ModouUi.dp(host, 6));
        return v;
    }

    /** 组内一行（首行不画分隔线，与 iOS grouped 一致）。
     *  0.9.168：iconKey != 0 才接语义图标并启用「语言级分隔线构图」；
     *  书源详情菜单 / 置顶这类 context menu·action sheet 在 iOS / M3 里本就不带
     *  leading 彩色方块，保持纯文字行 + 旧分隔线才是忠实，按组区分而非一刀切。 */
    private void menuRow(LinearLayout group, String title, String value, Runnable r) {
        menuRow(group, 0, title, value, r);
    }

    private void menuRow(LinearLayout group, int iconKey, String title, String value, Runnable r) {
        if (group.getChildCount() > 0) group.addView(ModouUi.groupDivider(host, iconKey != 0));
        group.addView(iconKey != 0
                ? ui.iconRow(host, iconKey, title, value, null, r)
                : ui.groupRow(host, title, value, null, r));
    }

    private void menuRowDanger(LinearLayout group, String title, Runnable r) {
        if (group.getChildCount() > 0) group.addView(ModouUi.groupDivider(host, true));
        group.addView(ui.iconRow(host, ListIcon.DANGER, title, null, null, ui.danger, r));
    }

    private void exportAll() {
        JSONArray a = new JSONArray();
        for (JSONObject o : items) a.put(o);
        ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("轻阅读源", a.toString()));
        ModouUi.toast(host, "已复制 " + items.size() + " 个书源到剪贴板");
    }

    /** 组合筛选面板：分组 ∩ 状态 ∩ 启用 ∩ 健康度，维度间 AND、维度内多选 OR */
    private void openFilterSheet() {
        final LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] ds = new Dialog[1];
        final Runnable close = () -> {
            if (ds[0] != null) ds[0].dismiss();
        };
        // 每次勾选就地重建这张面板（保持打开，方便连续勾多项），同时刷新背后列表
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> buildFilterBox(box, rebuild[0], close);
        rebuild[0].run();
        ds[0] = ModouUi.sheet(host, "筛选", box);
    }

    /**
     * 构建 / 重建筛选面板：勾选后不关面板，就地重建（打勾取最新状态），
     * 并调用 {@link #paint()} 让背后列表实时过滤。
     */
    private void buildFilterBox(final LinearLayout box, final Runnable rebuild, final Runnable close) {
        box.removeAllViews();
        // 手动组合筛选时「本轮未通过」必须先退场：否则勾「有效」会和它相交成 0 条，看着像筛选坏了。
        // filterMulti 内部会自己 paint()，这里只负责撤掉那个标记。
        final Runnable dropRound = () -> roundFailOnly = false;
        box.addView(filterMulti("分组", groupOptions(), groupFilter, dropRound));
        box.addView(filterMulti("状态", new String[][]{{"ok", "有效"}, {"skip", "未判定"},
                {"none", "未校验"}, {"nosearch", "无搜索"}}, statusFilter, dropRound));
        box.addView(filterMulti("启用", new String[][]{{"on", "仅启用"},
                {"off", "仅禁用"}}, enabledFilter, dropRound));

        // 健康度：单开关（近 7 天失败率 ≥80%，与行尾红色徽标同口径）
        LinearLayout hSec = new LinearLayout(host);
        hSec.setOrientation(LinearLayout.VERTICAL);
        hSec.addView(ModouUi.groupTitle(host, "健康度"));
        LinearLayout hg = ui.groupCard(host);
        hg.addView(ui.groupRow(host, "仅高失败源（近 7 天失败率 ≥80%）", brokenOnly ? "✓" : null, null,
                brokenOnly ? ui.accent : 0, () -> {
                    brokenOnly = !brokenOnly;
                    roundFailOnly = false;
                    rebuild.run();
                    paint();
                }));
        hSec.addView(hg);
        box.addView(hSec);

        // 0.9.157：需登录书源单开关（与 ⋮ 一键清理同一份命中集合）
        LinearLayout lSec = new LinearLayout(host);
        lSec.setOrientation(LinearLayout.VERTICAL);
        lSec.addView(ModouUi.groupTitle(host, "登录门槛"));
        LinearLayout lg = ui.groupCard(host);
        lg.addView(ui.groupRow(host, "仅需登录书源", loginOnly ? "✓" : null,
                "检出 " + loginKeys.size() + " 个；⋮ 里可一键清理",
                loginOnly ? ui.accent : 0, () -> {
                    loginOnly = !loginOnly;
                    roundFailOnly = false;
                    rebuild.run();
                    paint();
                }));
        lSec.addView(lg);
        box.addView(lSec);

        TextView reset = ModouUi.tv(host, "重置全部条件", 13.5f, ui.sub);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, ModouUi.dp(host, 18), 0, ModouUi.dp(host, 8));
        reset.setOnClickListener((v) -> {
            groupFilter.clear();
            statusFilter.clear();
            enabledFilter.clear();
            brokenOnly = false;
            skipOnly = false;
            roundFailOnly = false;
            loginOnly = false;
            searchKey = "";
            rebuild.run();
            paint();
        });
        box.addView(reset);

        TextView done = ModouUi.tv(host, "完成", 14f, 0xFFFFFFFF);
        done.setGravity(Gravity.CENTER);
        done.setPadding(0, ModouUi.dp(host, 12), 0, ModouUi.dp(host, 12));
        done.setTypeface(done.getTypeface(), Typeface.BOLD);
        done.setBackground(pillBtn(ui.accent, 0x33FFFFFF));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = ModouUi.dp(host, 4);
        done.setOnClickListener((v) -> close.run());
        box.addView(done, dlp);
    }

    /**
     * 一组多选项：小标题 + 组卡内竖排多选行。顶部「全部」代表「不限制」（集合为空时打勾），
     * 其余每行独立 toggle；选中即重建面板并刷新列表。
     */
    private LinearLayout filterMulti(String title, String[][] defs,
                                     final java.util.Set<String> sel, final Runnable rebuild) {
        LinearLayout sec = new LinearLayout(host);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.addView(ModouUi.groupTitle(host, title));
        LinearLayout g = ui.groupCard(host);
        g.addView(ui.groupRow(host, "全部", sel.isEmpty() ? "✓" : null, null,
                sel.isEmpty() ? ui.accent : 0, () -> {
                    sel.clear();
                    rebuild.run();
                    paint();
                }));
        for (final String[] d : defs) {
            final boolean on = sel.contains(d[0]);
            if (g.getChildCount() > 0) g.addView(ModouUi.groupDivider(host));
            g.addView(ui.groupRow(host, d[1], on ? "✓" : null, null,
                    on ? ui.accent : 0, () -> {
                        if (on) sel.remove(d[0]);
                        else sel.add(d[0]);
                        rebuild.run();
                        paint();
                    }));
        }
        sec.addView(g);
        return sec;
    }

    /** 可选分组（不含「全部」；「全部」由多选面板顶部那一行代表） */
    private String[][] groupOptions() {
        String[][] out = new String[knownGroups().size()][2];
        int i = 0;
        for (String g : knownGroups()) out[i++] = new String[]{g, g};
        return out;
    }

    /* ---------- 分组注册表：让「分组管理」真的能建组 / 改名 / 删组 ---------- */

    private static final String K_GROUPS = "srcGroups";   // settings 表的一个键

    /** 已知分组 = 注册表中的分组 ∪ 书源实际使用的分组 */
    private java.util.LinkedHashSet<String> knownGroups() {
        java.util.LinkedHashSet<String> gs = new java.util.LinkedHashSet<>();
        try {
            String s = ModouStore.get(host).get("settings", K_GROUPS);
            if (s != null) {
                JSONObject wrap = new JSONObject(s);
                JSONArray a = new JSONArray(wrap.optString("v", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    String g = a.optString(i, "").trim();
                    if (!g.isEmpty()) gs.add(g);
                }
            }
        } catch (Exception ignore) {
            /* 没存过就是空表 */
        }
        for (JSONObject o : items) gs.add(groupOf(o));
        return gs;
    }

    private void saveGroups(java.util.Set<String> gs) {
        try {
            JSONArray a = new JSONArray();
            for (String g : gs) a.put(g);
            JSONObject row = new JSONObject();
            row.put("k", K_GROUPS);
            row.put("v", a.toString());
            ModouStore.get(host).put("settings", row.toString());
        } catch (Exception ignore) {
            /* 存不下不影响主流程 */
        }
    }

    /** 分组管理：新建 / 重命名 / 删除分组（以前的入口只弹筛选，名不副实） */
    private void openGroupManager() {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] self = new Dialog[1];

        final EditText ed = ModouUi.input(host, "新分组名", "", 1);
        box.addView(ed);
        TextView add = ModouUi.tv(host, "＋ 新建分组", 14, ui.accent);
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, ModouUi.dp(host, 10), 0, ModouUi.dp(host, 6));
        add.setOnClickListener(v -> {
            String g = String.valueOf(ed.getText()).trim();
            if (g.isEmpty()) {
                ModouUi.toast(host, "先输入分组名");
                return;
            }
            java.util.LinkedHashSet<String> gs = knownGroups();
            if (gs.contains(g)) {
                ModouUi.toast(host, "已存在同名分组");
                return;
            }
            gs.add(g);
            saveGroups(gs);
            ModouUi.toast(host, "已新建分组「" + g + "」");
            if (self[0] != null) self[0].dismiss();
            openGroupManager();
        });
        box.addView(add);

        box.addView(sectionLabel("已有分组"));
        LinearLayout g = ui.groupCard(host);
        java.util.Map<String, Integer> cnt = new java.util.LinkedHashMap<>();
        for (JSONObject o : items) {
            String k = groupOf(o);
            cnt.put(k, (cnt.containsKey(k) ? cnt.get(k) : 0) + 1);
        }
        for (final String grp : knownGroups()) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(ModouUi.dp(host, 12), ModouUi.dp(host, 12),
                    ModouUi.dp(host, 8), ModouUi.dp(host, 12));
            TextView nm = ModouUi.tv(host, grp, 14, ui.text);
            nm.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(nm);
            int n = cnt.containsKey(grp) ? cnt.get(grp) : 0;
            TextView c = ModouUi.tv(host, n + " 个", 12, ui.sub);
            line.addView(c, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView ren = ModouUi.tv(host, "重命名", 12.5f, ui.accent);
            ren.setPadding(ModouUi.dp(host, 10), ModouUi.dp(host, 8),
                    ModouUi.dp(host, 6), ModouUi.dp(host, 8));
            ren.setOnClickListener(v -> {
                if (self[0] != null) self[0].dismiss();
                askRenameGroup(grp);
            });
            line.addView(ren);
            TextView del = ModouUi.tv(host, "删除", 12.5f, ui.danger);
            del.setPadding(ModouUi.dp(host, 6), ModouUi.dp(host, 8), 0, ModouUi.dp(host, 8));
            del.setOnClickListener(v -> {
                if (self[0] != null) self[0].dismiss();
                confirmDeleteGroup(grp, n);
            });
            line.addView(del);
            if (g.getChildCount() > 0) g.addView(ModouUi.groupDivider(host));
            g.addView(line);
        }
        box.addView(g);
        self[0] = ModouUi.sheet(host, "分组管理", box);
    }

    private void askRenameGroup(final String old) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] self = new Dialog[1];
        final EditText ed = ModouUi.input(host, "新分组名", old, 1);
        box.addView(ed);
        TextView ok = ModouUi.tv(host, "保存", 14, ui.accent);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(0, ModouUi.dp(host, 14), 0, ModouUi.dp(host, 6));
        ok.setOnClickListener(v -> {
            String ng = String.valueOf(ed.getText()).trim();
            if (ng.isEmpty() || ng.equals(old)) {
                ModouUi.toast(host, "没改名");
                return;
            }
            int n = 0;
            for (JSONObject o : items) {
                if (!old.equals(groupOf(o))) continue;
                try {
                    o.put("bookSourceGroup", ng);
                    o.put("sourceGroup", ng);
                    ModouStore.get(host).put("sources", o.toString());
                    n++;
                } catch (Exception ignore) {
                    /* 不可能 */
                }
            }
            java.util.LinkedHashSet<String> gs = knownGroups();
            gs.remove(old);
            gs.add(ng);
            saveGroups(gs);
            ModouUi.toast(host, "已改名并把 " + n + " 个源移入「" + ng + "」");
            if (self[0] != null) self[0].dismiss();
            reload();
        });
        box.addView(ok);
        self[0] = ModouUi.sheet(host, "重命名分组", box);
    }

    /** 删除分组：分组是书源的一个字段，删组必须同时把这批源落到「未分组」 */
    private void confirmDeleteGroup(final String grp, int n) {
        String msg = n > 0
                ? "删除分组「" + grp + "」？组内 " + n + " 个书源将移到「未分组」。"
                : "删除空分组「" + grp + "」？";
        ModouUi.confirm(host, "删除分组", msg, () -> {
            for (JSONObject o : items) {
                if (!grp.equals(groupOf(o))) continue;
                try {
                    o.put("bookSourceGroup", SourceGroup.NONE);
                    o.put("sourceGroup", SourceGroup.NONE);
                    ModouStore.get(host).put("sources", o.toString());
                } catch (Exception ignore) {
                    /* 不可能 */
                }
            }
            java.util.LinkedHashSet<String> gs = knownGroups();
            gs.remove(grp);
            saveGroups(gs);
            ModouUi.toast(host, "已删除分组「" + grp + "」");
            reload();
        });
    }

    /** 详情面板：承接原行内按钮，点行体弹出。摘要 + 分组操作行（与菜单同一套行样式） */
    /**
     * 单源 ⋮ 菜单（Legado 书源行同款）：置顶 / 置底 / 搜索测试 / 规则调试 / 删除。
     * 行卡片瘦身后，原来铺在行内的信息与操作全部收纳到这里与详情面板。
     */
    private void openSourceMenu(JSONObject o) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] self = new Dialog[1];
        Runnable close = () -> {
            if (self[0] != null) self[0].dismiss();
        };

        LinearLayout g1 = ui.groupCard(host);
        final boolean pinTop = o.optBoolean("pinTop", false);
        final boolean pinBot = o.optBoolean("pinBottom", false);
        menuRow(g1, pinTop ? "取消置顶" : "置顶", null, () -> {
            close.run();
            setPin(o, "pinTop", !pinTop);
        });
        menuRow(g1, pinBot ? "取消置底" : "置底", null, () -> {
            close.run();
            setPin(o, "pinBottom", !pinBot);
        });
        menuRow(g1, "搜索测试", "跑一次快速搜索", () -> {
            close.run();
            runSingleCheck(o, false);
        });
        menuRow(g1, "调试日志", "请求 URL · 响应片段 · 提取结果", () -> {
            close.run();
            new SourceDebug(host).open(o);
        });
        box.addView(g1);

        box.addView(spacer());
        LinearLayout g2 = ui.groupCard(host);
        menuRowDanger(g2, "删除", () -> {
            close.run();
            ModouUi.confirm(host, "删除书源", "删除《" + nameOf(o) + "》？", () -> {
                ModouStore.get(host).del("sources", o.optString("id", ""));
                String u = keyOf(o);
                quick.remove(u);
                qReason.remove(u);
                deep.remove(u);
                ruleBad.remove(u);
                foundBook.remove(u);
                reload();
            });
        });
        box.addView(g2);
        self[0] = ModouUi.sheet(host, nameOf(o), box);
    }

    /** 置顶 / 置底：写 pinTop / pinBottom 标记并落库，排序时钉在最前 / 最后；再点一次取消 */
    private void setPin(JSONObject o, String field, boolean on) {
        try {
            o.put(field, on);
            if (on) o.put("pinOrder", System.currentTimeMillis());
            ModouStore.get(host).put("sources", o.toString());
        } catch (Exception ignore) {
            /* 不可能 */
        }
        reload();
    }

    /**
     * 单源定向校验：full=true 规则调试（快速搜索 + 详情→目录→正文，结束自动弹错误日志）；
     * full=false 仅搜索测试。范围永远只有这一个源。
     */
    private void runSingleCheck(JSONObject o, boolean full) {
        if (checkRunning()) {
            ModouUi.toast(host, "校验进行中");
            return;
        }
        // 只清该源自己的结果，不清整张表
        String u = keyOf(o);
        quick.remove(u);
        qReason.remove(u);
        deep.remove(u);
        foundBook.remove(u);
        ruleBad.remove(u);
        paint();
        debugTargetKey = u;
        List<JSONObject> one = new ArrayList<>();
        one.add(o);
        rerunScope = new ArrayList<>(one);   // 重新校验仍只跑这一个源
        startWorkers(one, full);
        openCheckAllSheet();
    }

    /** 规则调试结果弹窗：成功一句话；失败给出步骤 + 错误原文（一份迷你错误日志） */
    private void showDebugResult(String key) {
        JSONObject src = null;
        for (JSONObject o : items) if (keyOf(o).equals(key)) { src = o; break; }
        String name = src == null ? key : nameOf(src);
        Deep d = deep.get(key);
        String q = quick.get(key);
        String qr = qReason.get(key);
        StringBuilder sb = new StringBuilder();
        if (d != null) {
            if ("ok".equals(d.state)) {
                sb.append("深度校验通过：目录 / 正文正常。");
                if (d.note != null && !d.note.isEmpty()) sb.append("\n").append(d.note);
            } else sb.append("失败于「").append(d.step).append("」：\n").append(d.reason);
        } else if ("ok".equals(q)) {
            sb.append("快速校验通过：搜索已返回结果（未做深度校验）。");
        } else if (qr != null && !qr.isEmpty()) {
            sb.append("搜索失败：\n").append(qr);
        } else {
            sb.append("没有拿到校验结论（可能被停止或网络中断），可再跑一次。");
        }
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = ModouUi.tv(host, sb.toString(), 13, ui.text);
        t.setLineSpacing(ModouUi.dp(host, 2), 1.3f);
        t.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 10));
        t.setTextIsSelectable(true);   // 允许长按选词 / 复制整段说明
        box.addView(t);

        // 自动规则建议：一键把可粘贴的「搜索规则」复制到剪贴板（长按整段也能选）
        final String dbgMsg = sb.toString();
        int ruleAt = dbgMsg.indexOf("bookList:");
        if (ruleAt >= 0) {
            final String ruleText = dbgMsg.substring(ruleAt);
            TextView copy = ModouUi.tv(host, "复制规则", 13, ui.accent);
            android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
            cbg.setCornerRadius(ModouUi.dp(host, 15));
            cbg.setColor(ModouUi.alpha(ui.accent, 0x1A));
            copy.setBackground(cbg);
            copy.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 6),
                    ModouUi.dp(host, 14), ModouUi.dp(host, 6));
            copy.setGravity(Gravity.CENTER);
            copy.setOnClickListener(v -> {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("ruleSearch", ruleText));
                        ModouUi.toast(host, "已复制搜索规则，去书源编辑里粘贴");
                    }
                } catch (Exception ignore) {
                    ModouUi.toast(host, "复制失败");
                }
            });
            box.addView(copy);
        }
        // 只看一句「失败于目录」还是没法修源：直接给一个能看 URL / 响应片段的入口
        if (src != null) {
            final JSONObject target = src;
            TextView dbg = ModouUi.tv(host, "打开调试台（每一步的 URL 与响应）", 14, 0xFFFFFFFF);
            dbg.setGravity(Gravity.CENTER);
            dbg.setTypeface(dbg.getTypeface(), Typeface.BOLD);
            dbg.setPadding(0, ModouUi.dp(host, 12), 0, ModouUi.dp(host, 12));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(ModouUi.dp(host, 12));
            bg.setColor(ui.accent);
            dbg.setBackground(bg);
            dbg.setOnClickListener(v -> new SourceDebug(host).open(target));
            box.addView(dbg);
        }
        ModouUi.sheet(host, "规则调试 · " + name, box);
    }

    private void openDetailSheet(JSONObject o) {
        final String url = o.optString("bookSourceUrl", "");
        final String id = o.optString("id", "");
        final Dialog[] ds = new Dialog[1];
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);

        int gap = ModouUi.dp(host, 14);
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ModouUi.dp(host, 4), 0, gap, 0);
        TextView title = ModouUi.tv(host, nameOf(o), 17, ui.text);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        // 标题右侧一个「编辑」胶囊：详情面板一打开，第一个能点的东西就是它。
        // 入口藏进菜单行里，用户根本不会往下找。
        TextView editBtn = ModouUi.tv(host, "编辑", 13, ui.accent);
        GradientDrawable eb = new GradientDrawable();
        eb.setCornerRadius(ModouUi.dp(host, 15));
        eb.setColor(ModouUi.alpha(ui.accent, 0x1A));
        editBtn.setBackground(eb);
        editBtn.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 6),
                ModouUi.dp(host, 14), ModouUi.dp(host, 6));
        editBtn.setOnClickListener((v) -> {
            if (ds[0] != null) ds[0].dismiss();
            new SourceEditor(host).open(o);
        });
        head.addView(editBtn);
        box.addView(head);
        TextView meta = ModouUi.tv(host, buildMeta(o), 12.5f, ui.sub);
        meta.setPadding(ModouUi.dp(host, 4), ModouUi.dp(host, 4), gap, ModouUi.dp(host, 12));
        box.addView(meta);

        LinearLayout g1 = ui.groupCard(host);
        menuRow(g1, "编辑书源", "名称 · 分组 · 规则", () -> {
            if (ds[0] != null) ds[0].dismiss();
            new SourceEditor(host).open(o);
        });
        menuRow(g1, "只校验这一个", null, () -> {
            if (ds[0] != null) ds[0].dismiss();
            runSingleCheck(o, true);   // 结束后自动弹规则调试结果
        });
        menuRow(g1, "调试 / 日志", "请求 URL · 响应片段 · 规则提取", () -> {
            if (ds[0] != null) ds[0].dismiss();
            new SourceDebug(host).open(o);
        });
        menuRow(g1, "健康度详情", "成功率 · 失败记录", () -> {
            if (ds[0] != null) ds[0].dismiss();
            openHealthSheet(url, nameOf(o));
        });
        menuRow(g1, "导出 JSON", null, () -> {
            ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("轻阅读源", o.toString()));
            ModouUi.toast(host, "已复制该书源到剪贴板");
        });
        box.addView(g1);

        box.addView(spacer());
        LinearLayout g2 = ui.groupCard(host);
        menuRowDanger(g2, "删除书源", () -> {
            if (ds[0] != null) ds[0].dismiss();
            ModouUi.confirm(host, "删除书源", "删除《" + nameOf(o) + "》？", () -> {
                ModouStore.get(host).del("sources", id);
                quick.remove(keyOf(o));
                qReason.remove(keyOf(o));
                deep.remove(keyOf(o));
                ruleBad.remove(keyOf(o));
                foundBook.remove(keyOf(o));
                reload();
            });
        });
        box.addView(g2);

        ds[0] = ModouUi.sheet(host, null, box);
    }

    /**
     * 关闭面板之后再做下一件事。
     *
     * 以前写的是 `v.postDelayed(this::showImport, 120)` —— v 就是面板里那个按钮，
     * `dismiss()` 之后它已经从窗口上摘下来（mAttachInfo 为 null），这时 post 出去的
     * runnable 只会进 ViewRootImpl 的 RunQueue，要等这个 View 重新 attach 才执行 ——
     * 而它永远不会再 attach。用户看到的就是「点了导入，什么反应都没有」。
     * 改挂在主线程 Handler 上，跟 View 的 attach 状态无关。
     */
    private void later(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, 120);
    }

    /* ---------------- 从本地文件导入书源 ---------------- */

    private void pickSourceFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // 书源文件常见后缀是 .json，但各家文件管理器给的 MIME 五花八门
            // （application/json、text/plain、octet-stream 都见过），只写一种会选不中
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/json", "text/plain", "application/octet-stream", "text/json"});
            host.startActivityForResult(i, REQ_IMPORT_FILE);
        } catch (Exception e) {
            ModouUi.toast(host, "打不开文件选择器：" + e.getMessage());
        }
    }

    /** ShelfActivity 把 onActivityResult 转发到这里（面板是嵌在书架里的 View，没有自己的 Activity） */
    void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_IMPORT_FILE) return;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return; // 用户取消不打扰
        readAndImport(data.getData());
    }

    /** 读文件 → 交给 doImport（解析与写库在它自己的后台线程里） */
    private void readAndImport(Uri u) {
        ModouUi.toast(host, "正在读取书源文件…");
        new Thread(() -> {
            String text = null;
            String err = null;
            try {
                text = readText(u);
            } catch (Throwable e) {
                err = brief(e);
            }
            final String ft = text;
            final String fe = err;
            host.runOnUiThread(() -> {
                if (isGone()) return;
                if (fe != null) {
                    ModouUi.toast(host, "读取失败：" + fe);
                    return;
                }
                if (ft == null || ft.trim().isEmpty()) {
                    ModouUi.toast(host, "这个文件是空的");
                    return;
                }
                doImport(ft);
            });
        }, "modou-source-read").start();
    }

    /**
     * 整份读成字符串。书源合集常有几 MB，虽然不大，但也不能在主线程做。
     *
     * 注意 UTF-8 BOM：不少导出工具会在文件头写 EF BB BF，直接 new JSONObject 会报
     * 「不是合法 JSON」—— 用户完全看不出是 BOM 的问题，只觉得导入不进来。
     */
    private String readText(Uri u) throws Exception {
        java.io.InputStream in = host.getContentResolver().openInputStream(u);
        if (in == null) throw new Exception("无法读取");
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), "UTF-8");
            if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
            return s.trim();
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
    }

    /** 宿主已经销毁（文件选择器回来时书架可能被系统回收重建过） */
    private boolean isGone() {
        return host.isFinishing() || host.isDestroyed();
    }

    private View spacer() {
        View s = new View(host);
        s.setLayoutParams(new LinearLayout.LayoutParams(1, ModouUi.dp(host, 10)));
        return s;
    }

    private void showImport() {
        final EditText ed = ModouUi.input(host,
                "粘贴书源 JSON（单个对象或数组，可整段粘贴）", "", 6);
        ed.setMaxLines(10);
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout footer = new LinearLayout(host);
        footer.setOrientation(LinearLayout.VERTICAL);
        final Dialog d;
        try {
            d = ModouUi.sheetForm(host, "导入书源", box, footer);
        } catch (Throwable t) {
            ModouUi.toast(host, "面板打开失败：" + t.getMessage());
            return;
        }
        final boolean[] checking = {false};
        footer.addView(ModouUi.sheetButtonsGuarded(host, d, "取消", "导入", null, () -> {
            if (checking[0]) return false;
            final String raw = String.valueOf(ed.getText()).trim();
            if (raw.isEmpty()) {
                ed.setError("请粘贴书源 JSON");
                ed.requestFocus();
                return false;
            }
            checking[0] = true;
            ed.setEnabled(false);
            // 大合集也可能有几 MB：结构校验在后台做，失败不关窗、不丢草稿。
            new Thread(() -> {
                String error = validateSourcesJson(raw);
                host.runOnUiThread(() -> {
                    checking[0] = false;
                    if (isGone() || !d.isShowing()) return;
                    if (error != null) {
                        ed.setEnabled(true);
                        ed.setError(error);
                        ed.requestFocus();
                        return;
                    }
                    d.dismiss();
                    doImport(raw);
                });
            }, "modou-source-json-check").start();
            return false;
        }));
    }

    /** 只做导入前校验，不写库；返回 null 表示至少有一个带地址的书源对象。 */
    private static String validateSourcesJson(String raw) {
        try {
            int objects = 0;
            int valid = 0;
            if (raw.startsWith("[")) {
                JSONArray a = new JSONArray(raw);
                if (a.length() == 0) return "书源数组不能为空";
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    objects++;
                    if (!o.optString("bookSourceUrl", "").trim().isEmpty()) valid++;
                }
            } else if (raw.startsWith("{")) {
                JSONObject o = new JSONObject(raw);
                objects = 1;
                if (!o.optString("bookSourceUrl", "").trim().isEmpty()) valid = 1;
            } else {
                return "内容需以 { 或 [ 开头";
            }
            if (objects == 0) return "数组中没有书源对象";
            return valid > 0 ? null : "书源缺少 bookSourceUrl 地址";
        } catch (Exception e) {
            return "JSON 格式不正确：" + brief(e);
        }
    }

    /**
     * 导入书源。
     *
     * 解析 JSON 与逐条写库都放到后台：一份 Legado 导出的书源合集常有上百个源、
     * 几 MB 文本，`new JSONObject(s)` 加一百多次同步写库在主线程上就是几秒到十几秒的
     * 卡死 —— 用户看到的是「点了导入，什么反应也没有」。
     */
    /**
     * 从文本导入书源（静态入口，供「用其他应用打开」的 VIEW intent 等原生入口复用）。
     * 解析与逐条写库放到后台线程；完成后在主线程回调 onDone（可为 null）。
     */
    static void importSourcesText(Context ctx, String text, Runnable onDone) {
        final String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }
        ModouUi.toast(ctx, "正在导入…");
        new Thread(() -> {
            List<JSONObject> add = new ArrayList<>();
            String err = null;
            try {
                if (s.startsWith("[")) {
                    JSONArray a = new JSONArray(s);
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o != null) add.add(o);
                    }
                } else {
                    add.add(new JSONObject(s));
                }
            } catch (Throwable e) {
                err = "不是合法 JSON：" + brief(e);
            }
            int n = 0;
            int skip = 0;
            int dup = 0;
            int login = 0;   // 0.9.157：本次导入里需登录的源，完成时提示去 ⋮ 一键清理
            if (err == null) {
                ModouStore store = ModouStore.get(ctx);
                // 去重指纹 = 站点 + 书源名。
                // 以前只按 host 判重：一个站点下挂「主站 / 移动版 / 精校版」好几个源是常态，
                // 整包导入时后面这些全被当成重复丢掉 —— 用户看到「导入 137 个，实际进来 40 个」，
                // 而缺的那些恰恰是同一站点里规则更全的版本。
                java.util.Set<String> seen = new java.util.HashSet<>();
                try {
                    JSONArray cur = new JSONArray(store.all("sources"));
                    for (int i = 0; i < cur.length(); i++) {
                        JSONObject c = cur.optJSONObject(i);
                        if (c == null) continue;
                        String h = hostOf(c.optString("bookSourceUrl", ""));
                        if (!h.isEmpty()) {
                            seen.add(h + "|" + c.optString("bookSourceName", "").trim());
                        }
                    }
                } catch (Exception ignore) {
                    /* 读不到现有列表就退化成不去重 */
                }
                for (JSONObject o : add) {
                    String url = o.optString("bookSourceUrl", "").trim();
                    if (url.isEmpty()) {
                        skip++;
                        continue;
                    }
                    // 规则块若是字符串（部分导出工具会二次序列化），先还原成对象：
                    // 引擎能兼容，但编辑器读不到字段、用户想改也无从下手。
                    normalizeRuleBlocks(o);
                    String h = hostOf(url);
                    String fp = h + "|" + o.optString("bookSourceName", "").trim();
                    if (!h.isEmpty() && seen.contains(fp)) {
                        dup++;
                        continue;
                    }
                    try {
                        o.put("id", url + "|" + o.optString("bookSourceName", ""));
                        o.put("enabled", o.optBoolean("enabled", true));
                    } catch (Exception ignore) {
                        /* 不可能 */
                    }
                    if (store.put("sources", o.toString())) {
                        n++;
                        if (SourceLogin.check(o, "").level != SourceLogin.NONE) login++;
                        if (!h.isEmpty()) seen.add(fp);
                    }
                }
            }
            final int fn = n;
            final int fskip = skip;
            final int fdup = dup;
            final int flogin = login;
            final String ferr = err;
            // 新导入的源必须能被紧接着的校验看到，否则「导入完立刻体检」会少一批
            SourceService svc = SourceService.get();
            if (svc != null) svc.invalidateDefs();
            if (ctx instanceof Activity) {
                ((Activity) ctx).runOnUiThread(() -> {
                    if (ferr != null) {
                        ModouUi.toast(ctx, "导入失败：" + ferr);
                    } else if (fn == 0) {
                        ModouUi.toast(ctx, fdup > 0
                                ? "这 " + fdup + " 个书源的站点都已经有了，没有新增"
                                : fskip > 0
                                ? "没有可导入的书源：这 " + fskip + " 个都缺少 bookSourceUrl"
                                : "没有可导入的书源");
                    } else {
                        StringBuilder m = new StringBuilder("已导入 " + fn + " 个书源");
                        if (fdup > 0) m.append("，跳过 ").append(fdup).append(" 个（站点已存在）");
                        if (fskip > 0) m.append("，跳过 ").append(fskip).append(" 个（缺地址）");
                        if (flogin > 0) m.append("，检出 ").append(flogin)
                                .append(" 个需登录（⋮ 可一键清理）");
                        ModouUi.toast(ctx, m.toString());
                    }
                    if (onDone != null) onDone.run();
                });
            } else if (onDone != null) {
                onDone.run();
            }
        }, "modou-source-import").start();
    }

    /**
     * 规则块还原：Legado 导出里 ruleSearch / ruleBookInfo / ruleToc / ruleContent
     * 偶尔是「二次序列化后的字符串」。引擎能兼容（fromJson 会 parse），但书源编辑器
     * 是按对象读字段的 —— 不还原的话，用户在编辑器里看到的是四个空块，会以为导入丢了规则。
     * 解析失败就原样保留，绝不因为一次解析失败把用户的规则字段整块丢掉。
     */
    private static void normalizeRuleBlocks(JSONObject o) {
        for (String k : new String[]{"ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "ruleExplore"}) {
            String s = o.optString(k, "");
            if (s == null) continue;
            String t = s.trim();
            if (!t.startsWith("{")) continue;
            try {
                o.put(k, new JSONObject(t));
            } catch (Exception ignore) {
                /* 不是合法对象：保持字符串原样，引擎侧还有一次宽容解析 */
            }
        }
    }

    /** 面板内导入：复用静态入口，完成后刷新本面板 */
    private void doImport(String text) {
        final String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            ModouUi.toast(host, "内容为空");
            return;
        }
        importSourcesText(host, text, this::reload);
    }

    /* ================= 网络导入 ================= */

    /** 网络导入：粘贴 URL → 后台下载 → 校验 JSON → 合并（按 URL 去重）/ 覆盖导入 */
    private void showImportUrl() {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, ModouUi.dp(host, 4), 0, 0);

        TextView tip = ModouUi.tv(host,
                "粘贴书源 JSON 的网络地址（http / https），支持单个对象或合集。", 12f, ui.sub);
        tip.setLineSpacing(ModouUi.dp(host, 3), 1.4f);
        tip.setPadding(0, 0, 0, ModouUi.dp(host, 12));
        box.addView(tip);

        EditText ed = ModouUi.input(host, "https://example.com/sources.json", "", 1);
        box.addView(ed);

        final LinearLayout footer = new LinearLayout(host);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, ModouUi.dp(host, 12), 0, 0);
        final Dialog d;
        try {
            d = ModouUi.sheetForm(host, "网络导入", box, footer);
        } catch (Throwable t) {
            ModouUi.toast(host, "面板打开失败：" + t.getMessage());
            return;
        }

        TextView cancel = ModouUi.button(host, "取消",
                DesignSpec.ButtonKind.TONAL, ModouUi.CAPSULE, v -> d.dismiss());
        TextView merge = ModouUi.button(host, "合并导入",
                DesignSpec.ButtonKind.FILLED, ModouUi.CAPSULE, null);
        merge.setOnClickListener((v) -> {
            final String url = validatedImportUrl(ed);
            if (url == null) return;
            d.dismiss();
            startUrlImport(url, false);
        });

        TextView over = ModouUi.button(host, "覆盖导入",
                DesignSpec.ButtonKind.OUTLINED, ModouUi.CAPSULE, null);
        over.setTextColor(ui.danger);
        over.setOnClickListener((v) -> {
            final String url = validatedImportUrl(ed);
            if (url == null) return;
            // 原表单保持打开：用户取消覆盖确认后仍能回到原链接继续修改。
            ModouUi.confirm(host, "覆盖导入",
                    "覆盖导入会先清空当前全部书源，再写入下载到的书源。确定继续？",
                    () -> {
                        d.dismiss();
                        startUrlImport(url, true);
                    });
        });

        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f);
        lc.rightMargin = ModouUi.dp(host, 5);
        footer.addView(cancel, lc);
        LinearLayout.LayoutParams lm = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        lm.leftMargin = ModouUi.dp(host, 5);
        lm.rightMargin = ModouUi.dp(host, 5);
        footer.addView(merge, lm);
        LinearLayout.LayoutParams lo = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        lo.leftMargin = ModouUi.dp(host, 5);
        footer.addView(over, lo);
    }

    /** 表单内验证链接；失败时设置字段错误并保留弹窗与焦点。 */
    private String validatedImportUrl(EditText ed) {
        String url = String.valueOf(ed.getText()).trim();
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isEmpty()) {
                throw new IllegalArgumentException("invalid source url");
            }
            return url;
        } catch (Exception e) {
            ed.setError(url.isEmpty() ? "请输入链接" : "请输入有效的 http:// 或 https:// 链接");
            ed.requestFocus();
            return null;
        }
    }

    /** 下载并导入：Loading → 下载 → 校验 JSON → 写库 → 刷新。异常均有明确提示，绝不静默。 */
    private void startUrlImport(final String rawUrl, final boolean overwrite) {
        final String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) {
            ModouUi.toast(host, "请输入链接");
            return;
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            ModouUi.toast(host, "链接需以 http:// 或 https:// 开头");
            return;
        }
        if (isGone()) return;
        final Dialog loading = showLoading("正在下载书源…");
        new Thread(() -> {
            String text = null;
            String err = null;
            try {
                text = httpGetText(url);
            } catch (Throwable e) {
                err = brief(e);
            }
            final String fText = text;
            final String fErr = err;
            host.runOnUiThread(() -> {
                try {
                    if (loading.isShowing()) loading.dismiss();
                } catch (Throwable ignore) {
                    /* 已关闭 */
                }
                if (isGone()) return;
                if (fErr != null) {
                    ModouUi.toast(host, "下载失败：" + fErr);
                    return;
                }
                if (fText == null || fText.trim().isEmpty()) {
                    ModouUi.toast(host, "下载失败：内容为空");
                    return;
                }
                final String norm = normalizeSourceText(fText);
                if (norm == null) {
                    ModouUi.toast(host, "解析失败，非标准书源格式");
                    return;
                }
                applyNetworkImport(norm, overwrite);
            });
        }, "modou-source-net").start();
    }

    /** 把网络内容规整为「书源数组 JSON」；不是合法书源格式返回 null */
    private String normalizeSourceText(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return null;
        // 去 UTF-8 BOM：部分站点会带 BOM，直接解析会报「非合法 JSON」
        if (t.charAt(0) == '\uFEFF') t = t.substring(1).trim();
        try {
            if (t.startsWith("[")) {
                JSONArray a = new JSONArray(t);
                if (a.length() == 0) return null;
                boolean any = false;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && !o.optString("bookSourceUrl", "").isEmpty()) {
                        any = true;
                        break;
                    }
                }
                return any ? a.toString() : null;
            }
            JSONObject o = new JSONObject(t);
            // Legado 备份格式：{"bookSources":[...]} / {"sources":[...]}
            JSONArray inner = o.optJSONArray("bookSources");
            if (inner == null) inner = o.optJSONArray("sources");
            if (inner != null && inner.length() > 0) return inner.toString();
            if (o.optString("bookSourceUrl", "").isEmpty()) return null;
            JSONArray one = new JSONArray();
            one.put(o);
            return one.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 按模式写库：合并按 bookSourceUrl 精确去重；覆盖先清空。解析失败不动现有数据。 */
    private void applyNetworkImport(final String arrayJson, final boolean overwrite) {
        new Thread(() -> {
            java.util.List<JSONObject> parsed = new java.util.ArrayList<>();
            int skip = 0;
            try {
                JSONArray arr = new JSONArray(arrayJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String u = o.optString("bookSourceUrl", "").trim();
                    if (u.isEmpty()) {
                        skip++;
                        continue;
                    }
                    try {
                        o.put("id", u + "|" + o.optString("bookSourceName", ""));
                        o.put("enabled", o.optBoolean("enabled", true));
                    } catch (Exception ignore) {
                        /* 字段写不进去就用原对象 */
                    }
                    parsed.add(o);
                }
            } catch (Throwable e) {
                host.runOnUiThread(() -> {
                    if (!isGone()) ModouUi.toast(host, "解析失败，非标准书源格式");
                });
                return;
            }
            if (parsed.isEmpty()) {
                // 关键：没有一条合法源时绝不 clear，避免覆盖导入把现有数据清空
                host.runOnUiThread(() -> {
                    if (!isGone()) ModouUi.toast(host, "解析失败，非标准书源格式");
                });
                return;
            }
            ModouStore store = ModouStore.get(host);
            java.util.Set<String> urls = new java.util.HashSet<>();
            if (overwrite) {
                store.clear("sources");
            } else {
                try {
                    JSONArray cur = new JSONArray(store.all("sources"));
                    for (int i = 0; i < cur.length(); i++) {
                        JSONObject c = cur.optJSONObject(i);
                        if (c != null) urls.add(c.optString("bookSourceUrl", "").trim());
                    }
                } catch (Exception ignore) {
                    /* 读不到现有列表就退化成不去重 */
                }
            }
            int n = 0;
            int dup = 0;
            int noRule = 0;
            int login = 0;   // 0.9.157：同本地导入，完成时提示检出数量
            for (JSONObject o : parsed) {
                String u = o.optString("bookSourceUrl", "").trim();
                if (!overwrite && urls.contains(u)) {
                    dup++;
                    continue;
                }
                if (store.put("sources", o.toString())) {
                    n++;
                    if (SourceLogin.check(o, "").level != SourceLogin.NONE) login++;
                    urls.add(u);
                    // 搜索与发现规则都没配：体检会直接跳过，导入时先说清楚，
                    // 免得用户面对「一批校验失败」不知道有一半根本没配规则
                    if (o.optString("searchUrl", "").trim().isEmpty()
                            && o.optString("exploreUrl", "").trim().isEmpty()) noRule++;
                }
            }
            final int fn = n;
            final int fNoRule = noRule;
            final int fdup = dup;
            final int fskip = skip;
            final int fLogin = login;
            SourceService svc = SourceService.get();
            if (svc != null) svc.invalidateDefs();
            host.runOnUiThread(() -> {
                if (isGone()) return;
                if (fn == 0) {
                    ModouUi.toast(host, fdup > 0
                            ? "没有新增：这 " + fdup + " 个书源（URL）已存在"
                            : fskip > 0
                            ? "没有可导入的书源：这 " + fskip + " 个都缺少 bookSourceUrl"
                            : "没有可导入的书源");
                } else {
                    StringBuilder m = new StringBuilder("已导入 " + fn + " 个书源");
                    if (fdup > 0) m.append("，跳过 ").append(fdup).append(" 个（URL 已存在）");
                    if (fskip > 0) m.append("，跳过 ").append(fskip).append(" 个（缺地址）");
                    if (fNoRule > 0) m.append("，其中 ").append(fNoRule)
                            .append(" 个没配搜索/发现规则（体检会跳过）");
                    if (fLogin > 0) m.append("，检出 ").append(fLogin)
                            .append(" 个需登录（⋮ 可一键清理）");
                    ModouUi.toast(host, m.toString());
                }
                resetFiltersAfterImport();
                reload();
            });
        }, "modou-source-net-apply").start();
    }

    /** 导入后清空筛选与上一轮报告，避免新源被旧条件藏起来 */
    private void resetFiltersAfterImport() {
        groupFilter.clear();
        statusFilter.clear();
        enabledFilter.clear();
        brokenOnly = false;
        skipOnly = false;
        roundFailOnly = false;
        loginOnly = false;
        roundFail.clear();
        // 上一轮诊断报告的三张分类表也要清：否则导入后点开报告页还挂着旧数据
        catRule.clear();
        catNet.clear();
        catDead.clear();
        searchKey = "";
    }

    /** 下载文本：处理 404/5xx、跟随跳转、按实际编码解码（复用 NetClient 的编码嗅探） */
    private String httpGetText(String url) throws Exception {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) Modou/1.0");
            c.setRequestProperty("Accept", "application/json,text/plain,*/*");
            int code = c.getResponseCode();
            if (code == 404) throw new Exception("链接不存在（HTTP 404）");
            if (code >= 400) throw new Exception("服务器返回 HTTP " + code);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            try {
                in.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
            byte[] data = bos.toByteArray();
            String cs;
            try {
                cs = NetClient.sniffCharset(data, c.getContentType());
            } catch (Throwable ignore) {
                cs = "UTF-8";
            }
            if (cs == null || cs.isEmpty()) cs = "UTF-8";
            return new String(data, cs);
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignore) {
                    /* 无所谓 */
                }
            }
        }
    }

    /** 轻量 Loading：主题一致的底部小卡片 + 转圈，禁止点击外部取消 */
    private Dialog showLoading(String msg) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(ModouUi.dp(host, 4), ModouUi.dp(host, 6),
                ModouUi.dp(host, 4), ModouUi.dp(host, 6));
        android.widget.ProgressBar pb = new android.widget.ProgressBar(host);
        pb.setIndeterminate(true);
        box.addView(pb, new LinearLayout.LayoutParams(
                ModouUi.dp(host, 22), ModouUi.dp(host, 22)));
        TextView t = ModouUi.tv(host, msg, 14f, ui.text);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = ModouUi.dp(host, 12);
        box.addView(t, tlp);
        Dialog d = ModouUi.sheet(host, null, box);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        return d;
    }

    /** 站点指纹：小写、去 www. 前缀；解析不出来的（local:// 等）返回空串，不参与去重 */
    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h == null) return "";
            h = h.toLowerCase(java.util.Locale.ROOT);
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String brief(Throwable e) {
        String m = e == null ? "" : String.valueOf(e.getMessage());
        if (m.length() > 60) m = m.substring(0, 60) + "…";
        return m;
    }

    /** 无底色的点击反馈（文字按钮用）：只有水波纹，不铺常驻色块 */
    private static android.graphics.drawable.RippleDrawable rippleBg(int color, int alpha) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModouUi.alpha(color, alpha)), null, null);
    }

    /** 文字次要按钮的水波：有界（胶囊 mask），不会溢出 sheet 的圆角 */
    private android.graphics.drawable.Drawable textBtnRipple() {
        android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(ModouUi.dp(host, 999));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModouUi.alpha(ui.text, 0x0E)),
                null, mask);
    }

    /** 主色胶囊按钮：实底圆角 + 水波，替换原来「彩色文字 + 淡色块」的假按钮 */
    private android.graphics.drawable.Drawable pillBtn(int bgColor, int rippleColor) {
        int r = ModouUi.dp(host, 999);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(r);
        android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(r);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleColor), bg, mask);
    }

}
