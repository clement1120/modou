package cn.modou.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 原生搜书（步骤 7）。
 *
 * 数据来自第 6 步抽出的书源服务（常驻无头 WebView），**不经过 UI WebView** ——
 * 所以将来网页版整个退役，搜书照样能用。
 *
 * 与网页版搜书保持一致的三处，否则用户两边切换会觉得「结果不一样」：
 *  - 相关性过滤：书源搜索规则失效时会返回整页无关的书，宁可丢弃并报给用户；
 *  - 同名归并：同一本书的多个来源归到一张卡里；
 *  - 打开流程：详情失败不阻断，用搜索结果里的书名/作者兜底继续取目录。
 *
 * 打开方式上是「试读」而不是直接入书架（与网页版 openPreview 一致）：
 * 写进 books 表但带 preview 标记，书架列表过滤掉它，详情页里再点「加入书架」才固化。
 * 否则搜一下就往书架里塞一本书，书架很快会变成搜索历史。
 */
public class SearchActivity extends Activity {

    static final String PREF_PICKED = "search.pickedSources";
    /** 搜索历史：SharedPreferences 里一个 JSON 数组，最多留 12 条 */
    private static final String PREF_HISTORY = "search.history";
    private static final int HISTORY_MAX = 12;

    private EditText input;
    private ImageView clearBtn;
    private TextView statusPill;
    private TextView failNote;
    private LinearLayout historyBox;
    private ListView list;
    private View fab;
    private ModouUi ui;

    /* ---------- 0.9.170 搜索框焦点态 ----------
       仅 M3 / iOS 会创建；墨读 searchFocusStyle=0 时这些字段全为 null、连监听都不注册，
       逐像素零回归。iOS：未聚焦「放大镜 + 占位符」整体居中，聚焦后靠左并滑入 Cancel；
       M3：聚焦时 tonal elevation 3→6dp、底色升一级。改动只在本页，不碰 doSearch 链路。 */
    private LinearLayout searchBox;      // 搜索框本体（聚焦时切背景 / elevation）
    private ImageView magIcon;           // 框内左侧放大镜（iOS 未聚焦隐藏，交给居中提示层）
    private View hintLayer;              // iOS 未聚焦时「放大镜 + 占位符」居中层
    private TextView cancelBtn;          // iOS 聚焦时右侧滑入的「取消」
    private boolean searchFocused;       // 当前焦点态，避免重复刷新
    private ValueAnimator cancelAnim;    // Cancel 宽度动画，切焦点时先取消上一次

    private final List<JSONObject> sources = new ArrayList<>();
    private final Set<String> picked = new LinkedHashSet<>();
    private final List<Group> groups = new ArrayList<>();
    private final List<FailItem> fails = new ArrayList<>();
    /** 「站点没这本书」的源（无结果页）：不算失败，单独提示，避免吓人 */
    private final List<String> noResultFails = new ArrayList<>();
    private int gen = 0;

    /* 流式搜索的进度（每源回来刷新一次） */
    private int searched, totalSrc, filteredN, weakN;
    private boolean searching;
    /** 0 相关度（来源数）/ 1 书名 */
    private int sortMode = 0;
    /**
     * 来源筛选：空串 = 全部。存 bookSourceUrl。
     * 搜索是把多个书源的结果按「书名+作者」合并的，所以「按书源筛」实际是
     * 只保留在指定源里命中的卡片，并把卡片里的命中项也收窄到那个源。
     */
    private String srcFilter = "";
    /** 经过来源筛选后真正显示在列表里的卡片（adapter 只认这个） */
    private final List<Group> visible = new ArrayList<>();
    /** 最近一次搜索词（失败弹窗里点明「没有哪本书」用） */
    private String lastKw = "";
    /** 屏蔽过的书（本次会话内）：用户在这一页「不要看到它」 */
    private final Set<String> blocked = new LinkedHashSet<>();

    /** 列表刷新节流：一次 notify 就要重排可见行，源多时每回一条都刷会打断滑动。
     *  排序也并入这里（180ms 去抖），避免每个源回来都跑一次全量排序。 */
    private boolean paintPending;
    private final Runnable paintTask = new Runnable() {
        @Override
        public void run() {
            paintPending = false;
            sortGroups(); // 含 rebuildVisible：把这段时间累积的新书按相关性排好再刷新
            adapter.notifyDataSetChanged();
        }
    };

    private void requestPaint() {
        if (paintPending) return;
        paintPending = true;
        list.postDelayed(paintTask, 180);
    }

    static void launch(Activity from) {
        from.startActivity(new Intent(from, SearchActivity.class));
    }

    /** 按作者搜书：详情页点作者名进来，关键词直接填好并开搜 */
    static void launchByAuthor(Activity from, String author) {
        Intent i = new Intent(from, SearchActivity.class);
        i.putExtra("kw", author);
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ModouUi.applyTheme(this);
        super.onCreate(savedInstanceState);
        ui = ModouUi.of(this);

        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(ui.bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.topInset(this);
        col.setPadding(p, ModouUi.dp(this, 2), p, 0);

        col.addView(buildNav());
        col.addView(buildTitle());
        col.addView(buildSearchBox());
        col.addView(buildStatusRow());

        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(historyBox);

        // 结果列表自己占满剩余高度（不要再套 ScrollView：那会让 ListView 只量一行）
        list = new ListView(this);
        // 0.9.169：结果行的间距 / 分隔线交给语言（用 ListView 原生 divider，
        // 不动复用行结构）：墨读无 divider；M3 透明 divider 撑出 filled card 的
        // 8dp 间距；iOS 从封面之后（88dp）起画一条 0.5px 缩进线。
        if (ui.spec.searchBarStyle == 2) {
            list.setDivider(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            list.setDividerHeight(ModouUi.dp(this, ui.spec.searchHitGapDp));
        } else if (ui.spec.searchBarStyle == 1) {
            android.graphics.drawable.GradientDrawable sep =
                    new android.graphics.drawable.GradientDrawable();
            sep.setColor(ui.spec.divider);
            list.setDivider(new android.graphics.drawable.InsetDrawable(sep,
                    ModouUi.dp(this, ui.spec.searchHitDividerStartDp), 0, 0, 0));
            list.setDividerHeight(Math.max(1, ModouUi.dp(this, 0.5f)));
        } else {
            list.setDivider(null);
            list.setDividerHeight(0);
        }
        list.setBackgroundColor(ui.bg);
        list.setClipToPadding(false);
        list.setPadding(0, ModouUi.dp(this, 6), 0, ModouUi.dp(this, 96));
        list.setVerticalScrollBarEnabled(false);
        list.setAdapter(adapter);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) { }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) {
                CoverLoader.markScroll(); // 滚动时暂停封面加载，避免滚动中抢占解码
            }
        });
        col.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        shell.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fab = buildFab();
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ModouUi.dp(this, 54), ModouUi.dp(this, 54));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.setMargins(0, 0, ModouUi.dp(this, 20), ModouUi.dp(this, 20));
        shell.addView(fab, flp);
        fab.setVisibility(View.GONE);

        setContentView(shell);

        loadSources();
        syncHistory();
        syncStatus();
        SourceService svc = SourceService.get();
        if (svc != null) svc.warmUp(); // 预热：等用户输完词再起服务页就慢了

        // 从详情页点作者名进来：关键词已经填好，直接开搜 —— 不必让用户再打一遍
        String preset = getIntent().getStringExtra("kw");
        if (preset != null && !preset.trim().isEmpty()) {
            input.setText(preset.trim());
            input.post(this::doSearch);
        }
    }

    /* ---------------- 顶部 ---------------- */

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);

        // 左上：圆形细线 < 箭头（不带「返回」二字）
        nav.addView(roundIcon(ModouGlyph.BACK, v -> finish()));

        View gap = new View(this);
        nav.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));

        // 右上：细线漏斗 + 圆形底。筛选（书源 / 排序）是这一页唯一的设置入口
        nav.addView(roundIcon(ModouGlyph.FILTER, v -> showFilter()));
        return nav;
    }

    /**
     * 顶部圆钮：与书架、书源同一套 {@link ModouUi#topBtn} 规格
     * （19dp 字形 / 1.8dp 线宽 / 40dp 正圆），不再单独维护一份。
     */
    private View roundIcon(int glyph, View.OnClickListener c) {
        return ModouUi.topBtn(this, glyph, 40, c);
    }

    private View navIcon(int res, View.OnClickListener c) {
        ImageView iv = new ImageView(this);
        int s = ModouUi.dp(this, 40);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setPadding(ModouUi.dp(this, 9), ModouUi.dp(this, 9),
                ModouUi.dp(this, 9), ModouUi.dp(this, 9));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        android.graphics.drawable.Drawable d =
                getResources().getDrawable(res, getTheme()).mutate();
        d.setTint(ui.sub);
        iv.setImageDrawable(d);
        android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(ModouUi.dp(this, 999));
        iv.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModouUi.alpha(ui.text, 0x14)),
                null, mask));
        iv.setOnClickListener(c);
        return iv;
    }

    private View buildTitle() {
        // 0.9.169：M3 / iOS 走统一 A8 字阶（32sp Medium / 34sp Bold、无衬线），
        // 不再是墨读那行 28sp 衬线老标题；墨读 searchBarStyle=0 逐像素保持衬线原样。
        TextView t = ui.spec.searchBarStyle != 0
                ? ModouUi.pageTitle(this, "搜索")
                : ModouUi.tv(this, "搜索", 28, ui.text);
        if (ui.spec.searchBarStyle == 0) {
            t.setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD);
        }
        t.setPadding(ModouUi.dp(this, 2), ModouUi.dp(this, 6), 0, ModouUi.dp(this, 14));
        return t;
    }

    private View buildSearchBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        // 0.9.169：搜索框背景交给语言规格（墨读玻璃胶囊 / iOS 灰圆角矩形 /
        // M3 surfaceContainerHigh 全圆胶囊）。颜色由派生色板给，深浅自动适配。
        box.setBackground(ModouUi.searchBarBg(this));
        // M3 search bar 靠 tonal elevation 表达层级；墨读 / iOS 为 0。
        if (ui.spec.searchBarElevDp > 0f) {
            box.setElevation(ModouUi.dp(this, ui.spec.searchBarElevDp));
        }
        int h = ModouUi.dp(this, ui.spec.searchBarHDp);
        box.setPadding(ModouUi.dp(this, 14), 0, ModouUi.dp(this, 6), 0);
        searchBox = box;

        ImageView mag = new ImageView(this);
        int ms = ModouUi.dp(this, ui.spec.searchBarIconDp);
        mag.setLayoutParams(new LinearLayout.LayoutParams(ms, ms));
        android.graphics.drawable.Drawable md =
                getResources().getDrawable(R.drawable.ic_sh_search, getTheme()).mutate();
        md.setTint(ui.sub);
        mag.setImageDrawable(md);
        // 放大镜本身就是「开始搜」：中文输入法不一定有搜索键，光靠 IME 动作会让人以为没反应
        int mp = ModouUi.dp(this, 3);
        mag.setPadding(mp, mp, mp, mp);
        mag.setOnClickListener(v -> doSearch());
        magIcon = mag;
        box.addView(mag);

        input = new EditText(this);
        input.setHint("书名 / 作者");
        input.setSingleLine(true);
        input.setTextSize(ui.spec.searchBarTextSp);
        input.setTextColor(ui.text);
        input.setHintTextColor(ui.sub);
        input.setBackground(null);
        input.setPadding(ModouUi.dp(this, 10), 0, ModouUi.dp(this, 6), 0);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        box.addView(input, new LinearLayout.LayoutParams(0, h, 1f));

        clearBtn = new ImageView(this);
        int cs = ModouUi.dp(this, 30);
        clearBtn.setLayoutParams(new LinearLayout.LayoutParams(cs, cs));
        clearBtn.setPadding(ModouUi.dp(this, 7), ModouUi.dp(this, 7),
                ModouUi.dp(this, 7), ModouUi.dp(this, 7));
        clearBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        android.graphics.drawable.Drawable cd =
                getResources().getDrawable(R.drawable.ic_rd_close, getTheme()).mutate();
        cd.setTint(ui.sub);
        clearBtn.setImageDrawable(cd);
        clearBtn.setOnClickListener(v -> {
            input.setText("");
            groups.clear();
            fails.clear();
            searched = 0;
            adapter.notifyDataSetChanged();
            syncHistory();
            syncStatus();
        });
        clearBtn.setVisibility(View.GONE);
        box.addView(clearBtn);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                clearBtn.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                // 带 preset 关键词进来时（无焦点但有文字），别还挂着「居中占位」层。
                // 只同步 iOS 提示层，不重建背景、不打断 Cancel 的宽度动画。
                if (ui != null && ui.spec.searchFocusStyle == 1 && !searchFocused) {
                    syncIosHintLayer();
                }
            }
        });

        // 0.9.170：墨读逐像素零回归——不注册焦点监听、不建任何层，直接返回原 box。
        if (ui.spec.searchFocusStyle == 0) return box;

        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (searchFocused == hasFocus) return;
            searchFocused = hasFocus;
            applySearchChrome(hasFocus, true);
        });

        // M3：只切背景 / elevation，不改结构、不加 Cancel（M3 search view 的 back 箭头
        // 与本页左上返回重复，故不做）。
        if (ui.spec.searchFocusStyle == 2) {
            applySearchChrome(false, false);
            return box;
        }

        /* iOS 26：外面再包一层。fieldWrap 里叠一个「放大镜 + 占位符」居中层，
           聚焦时淡出、框内放大镜与占位符回到左侧；右侧 Cancel 宽度从 0 动到 58dp，
           搜索框随之平滑让出宽度——和 UISearchBar 的焦点过渡同构。 */
        FrameLayout fieldWrap = new FrameLayout(this);
        fieldWrap.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));

        LinearLayout hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER);
        hint.setClickable(true);
        hint.setOnClickListener(v -> focusSearchInput());
        ImageView hm = new ImageView(this);
        int hs = ModouUi.dp(this, ui.spec.searchBarIconDp);
        hm.setLayoutParams(new LinearLayout.LayoutParams(hs, hs));
        int hp = ModouUi.dp(this, 3);
        hm.setPadding(hp, hp, hp, hp);
        android.graphics.drawable.Drawable hd =
                getResources().getDrawable(R.drawable.ic_sh_search, getTheme()).mutate();
        hd.setTint(ui.sub);
        hm.setImageDrawable(hd);
        hint.addView(hm);
        TextView ht = ModouUi.tv(this, "书名 / 作者", ui.spec.searchBarTextSp, ui.sub);
        ht.setPadding(ModouUi.dp(this, 6), 0, 0, 0);
        hint.addView(ht);
        hintLayer = hint;
        fieldWrap.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(fieldWrap, new LinearLayout.LayoutParams(0, h, 1f));

        cancelBtn = ModouUi.tv(this, "取消", ui.spec.searchBarTextSp, ui.spec.sysBlue);
        cancelBtn.setSingleLine(true);
        cancelBtn.setGravity(Gravity.CENTER_VERTICAL);
        cancelBtn.setPadding(ModouUi.dp(this, 10), 0, ModouUi.dp(this, 2), 0);
        cancelBtn.setClickable(true);
        cancelBtn.setOnClickListener(v -> exitSearchFocus());
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(0, h));
        cancelBtn.setVisibility(View.GONE);
        wrap.addView(cancelBtn);

        applySearchChrome(false, false);   // 初始为未聚焦：居中提示层显示、Cancel 收起
        return wrap;
    }

    /* ---------------- 0.9.170 搜索框焦点态 ---------------- */

    /**
     * 焦点态总入口。{@code animate=false} 用于初始同步，避免进场就闪一下。
     * 墨读 {@code searchFocusStyle=0} 直接返回——它根本不会走到这个方法。
     */
    private void applySearchChrome(boolean focused, boolean animate) {
        if (ui == null || ui.spec.searchFocusStyle == 0) return;
        boolean ios = ui.spec.searchFocusStyle == 1;
        boolean empty = input.getText() == null || input.getText().length() == 0;
        boolean showHint = ios && !focused && empty;

        // 背景 / elevation：M3 聚焦升一级 tonal，iOS 聚焦灰底深一档。
        if (searchBox != null) {
            searchBox.setBackground(ModouUi.searchBarBg(this, focused));
            if (ui.spec.searchFocusStyle == 2) {
                searchBox.setElevation(ModouUi.dp(this,
                        focused ? ui.spec.searchFocusElevDp : ui.spec.searchBarElevDp));
            }
        }
        if (!ios) return;

        // iOS：居中提示层 <-> 框内放大镜 + 靠左占位符
        input.setHint(showHint ? "" : "书名 / 作者");
        if (magIcon != null) magIcon.setVisibility(showHint ? View.GONE : View.VISIBLE);
        if (hintLayer != null) {
            if (animate) fadeView(hintLayer, showHint);
            else {
                hintLayer.animate().cancel();
                hintLayer.setVisibility(showHint ? View.VISIBLE : View.GONE);
                hintLayer.setAlpha(1f);
            }
        }
        if (showHint) clearBtn.setVisibility(View.GONE);
        animateCancelWidth(focused, animate);
    }

    /** 无焦点时文字变化（含 preset）只同步提示层：不重建背景、不碰 Cancel 动画。 */
    private void syncIosHintLayer() {
        boolean empty = input.getText() == null || input.getText().length() == 0;
        input.setHint(empty ? "" : "书名 / 作者");
        if (magIcon != null) magIcon.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (hintLayer != null) {
            hintLayer.animate().cancel();
            hintLayer.setVisibility(empty ? View.VISIBLE : View.GONE);
            hintLayer.setAlpha(1f);
        }
    }

    /** 提示层淡入 / 淡出；先取消上一次，避免快速切焦点时动画叠加。 */
    private void fadeView(View v, boolean show) {
        if (v == null) return;
        v.animate().cancel();
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(180)
                    .setInterpolator(DesignSpec.interpolator(ui.spec, false, 1.6f)).start();
        } else {
            v.animate().alpha(0f).setDuration(140)
                    .setInterpolator(DesignSpec.interpolator(ui.spec, false, 1.6f))
                    .withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    /**
     * Cancel 用「宽度 0 -> 58dp」而不是 GONE->VISIBLE：这样 fieldWrap(weight=1)
     * 会跟着平滑收窄，和 iOS 聚焦时搜索框给 Cancel 让位的过渡一致，不会突然跳一下。
     */
    private void animateCancelWidth(boolean show, boolean animate) {
        if (cancelBtn == null) return;
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) cancelBtn.getLayoutParams();
        int target = show ? ModouUi.dp(this, 58) : 0;
        if (cancelAnim != null) { cancelAnim.cancel(); cancelAnim = null; }
        if (!animate) {
            lp.width = target;
            cancelBtn.setLayoutParams(lp);
            cancelBtn.setVisibility(show ? View.VISIBLE : View.GONE);
            cancelBtn.setAlpha(1f);
            return;
        }
        int from = lp.width;
        if (from == target) {
            cancelBtn.setVisibility(show ? View.VISIBLE : View.GONE);
            cancelBtn.setAlpha(1f);
            return;
        }
        cancelBtn.setVisibility(View.VISIBLE);
        ValueAnimator va = ValueAnimator.ofInt(from, target);
        va.setDuration(show ? 240 : 180);
        va.setInterpolator(DesignSpec.interpolator(ui.spec, show, 1.6f));
        va.addUpdateListener(a -> {
            lp.width = (Integer) a.getAnimatedValue();
            cancelBtn.setLayoutParams(lp);
            float f = a.getAnimatedFraction();
            cancelBtn.setAlpha(show ? Math.min(1f, f) : Math.max(0f, 1f - f));
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (!show) cancelBtn.setVisibility(View.GONE);
                cancelBtn.setAlpha(1f);
                cancelAnim = null;
            }
            @Override public void onAnimationCancel(Animator a) {
                cancelBtn.setAlpha(1f);
            }
        });
        cancelAnim = va;
        va.start();
    }

    /** 点居中提示层：把焦点交给真正的 EditText 并弹键盘。 */
    private void focusSearchInput() {
        input.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, 0);
    }

    /**
     * 点「取消」：只退出焦点态、收键盘，保留已输入文字和搜索结果——
     * 不像系统 UISearchBar 那样顺手清空，避免误触把用户刚搜到的东西抹掉。
     * 焦点监听负责把提示层 / Cancel 收回去；若 clearFocus 没触发监听则兜底一次。
     */
    private void exitSearchFocus() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        if (searchFocused) {
            searchFocused = false;
            applySearchChrome(false, true);
        }
    }

    /** 状态胶囊 + 失败提示：一行居中，比原来那条左对齐的灰字更像「系统在说话」 */
    private View buildStatusRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER);
        center.setPadding(0, ModouUi.dp(this, 12), 0, 0);
        statusPill = ModouUi.tv(this, "", 12, ui.sub);
        statusPill.setGravity(Gravity.CENTER);
        statusPill.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 7),
                ModouUi.dp(this, 14), ModouUi.dp(this, 7));
        statusPill.setBackground(ModouUi.round(ModouUi.alpha(ui.text, 0x0C), 999));
        center.addView(statusPill);
        wrap.addView(center);

        failNote = ModouUi.tv(this, "", 11.5f, ui.danger);
        failNote.setGravity(Gravity.CENTER);
        failNote.setPadding(0, ModouUi.dp(this, 6), 0, 0);
        failNote.setVisibility(View.GONE);
        failNote.setOnClickListener(v -> showFails());
        wrap.addView(failNote);
        return wrap;
    }

    private View buildFab() {
        FrameLayout f = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(ui.accent);
        g.setCornerRadius(ModouUi.dp(this, 999));
        f.setBackground(g);
        f.setElevation(ModouUi.dp(this, 8));

        View square = new View(this);
        int s = ModouUi.dp(this, 14);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(s, s);
        slp.gravity = Gravity.CENTER;
        square.setBackground(ModouUi.round(0xFFFFFFFF, 3));
        f.addView(square, slp);

        f.setOnClickListener(v -> stopSearch());
        f.setContentDescription("停止搜索");
        return f;
    }

    /* ---------------- 搜索 ---------------- */

    private void doSearch() {
        String kw = input == null ? "" : String.valueOf(input.getText()).trim();
        lastKw = kw;
        if (kw.isEmpty()) {
            ModouUi.toast(this, "先输入关键词");
            return;
        }
        if (picked.isEmpty()) {
            ModouUi.toast(this, "没有选中的书源");
            return;
        }
        final List<JSONObject> use = new ArrayList<>();
        for (JSONObject o : sources) {
            if (picked.contains(o.optString("bookSourceUrl", ""))) use.add(o);
        }
        if (use.isEmpty()) {
            ModouUi.toast(this, "没有可用的书源");
            return;
        }
        if (SourceService.get() == null) {
            ModouUi.toast(this, "书源服务未启动");
            return;
        }
        rememberKeyword(kw);

        final int myGen = ++gen;
        groups.clear();
        visible.clear();
        srcFilter = "";
        fails.clear();
        noResultFails.clear();
        blocked.clear();
        searched = 0;
        totalSrc = use.size();
        filteredN = 0;
        weakN = 0;
        searching = true;
        adapter.notifyDataSetChanged();
        fab.setVisibility(View.VISIBLE);
        syncStatus();

        // 逐源搜索：每回来一个就合并、就刷新 —— 不再等最慢的那个源
        SourceSearchPool.start(this, use, kw, 1, new SourceSearchPool.Listener() {
            @Override
            public void onSource(JSONObject src, JSONObject res) {
                if (myGen != gen) return;
                searched++;
                merge(res);
                syncStatus();
                requestPaint();
            }

            @Override
            public void onFinished(int total, int failed) {
                if (myGen != gen) return;
                searching = false;
                fab.setVisibility(View.GONE);
                sortGroups();
                list.removeCallbacks(paintTask);
                paintPending = false;
                adapter.notifyDataSetChanged();
                syncStatus();
            }
        });
    }

    /** 停止：丢掉这一轮后续结果，已回来的留在列表里 */
    private void stopSearch() {
        if (!searching) return;
        SourceSearchPool.cancelAll(); // 放开 8 条线程，别让它们在后台空占 8 秒
        gen++;
        searching = false;
        fab.setVisibility(View.GONE);
        sortGroups();
        adapter.notifyDataSetChanged();
        syncStatus();
        ModouUi.toast(this, "已停止搜索，保留已返回的 " + groups.size() + " 本");
    }

    private void sortGroups() {
        if (sortMode == 1) {
            final java.text.Collator col = java.text.Collator.getInstance(java.util.Locale.CHINA);
            groups.sort((a, b) -> col.compare(a.name, b.name));
        } else {
            // 相关性优先：命中书名（score≥80）的永远压在弱相关（score 0）之上，
            // 同分再按来源数 —— 否则「来源多的热门书」会把你搜的那本挤下去。
            groups.sort((a, b) -> {
                int c = Integer.compare(b.score, a.score);
                if (c != 0) return c;
                return Integer.compare(b.hits.size(), a.hits.size());
            });
        }
        rebuildVisible();
    }

    /**
     * 按来源过滤出可见卡片。筛选时克隆一份 Group，只留该来源的命中项：
     * 卡片右上角会显示「3 个来源」，若整卡照搬，筛完还是写着 3 个，等于没筛。
     */
    private void rebuildVisible() {
        visible.clear();
        if (srcFilter.isEmpty()) {
            visible.addAll(groups);
            return;
        }
        for (Group g : groups) {
            List<Hit> hs = new ArrayList<>();
            for (Hit h : g.hits) if (srcFilter.equals(h.sourceUrl)) hs.add(h);
            if (!hs.isEmpty()) visible.add(new Group(g.name, g.author, hs));
        }
    }

    /** 当前结果里出现过的书源（按首次出现顺序），供筛选面板列表用 */
    private List<String[]> sourceChoices() {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Group g : groups) {
            for (Hit h : g.hits) {
                if (h.sourceUrl.isEmpty() || !seen.add(h.sourceUrl)) continue;
                out.add(new String[]{h.sourceUrl, h.sourceName.isEmpty() ? h.sourceUrl : h.sourceName});
            }
        }
        return out;
    }

    /** 合并一个源的结果：同名同作者归到同一张卡，失败原因与过滤计数累加 */
    private void merge(JSONObject res) {
        if (res == null) return;
        if (!res.optBoolean("ok", false)) {
            fails.add(new FailItem(res.optString("error", "搜索失败"), "失败", ""));
            return;
        }
        filteredN += res.optInt("filtered", 0);
        JSONArray wk = res.optJSONArray("weak");
        if (wk != null) weakN += wk.length();
        JSONArray fs = res.optJSONArray("fails");
        if (fs != null) {
            for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.optJSONObject(i);
                if (f == null) continue;
                // 「站点明确回无结果页」= 站点没这本书，不是源坏了：单独记，不进红色失败数。
                // 否则校验用「站点自己的书」测过、真实搜索用你的词，站点没这本就被误算成「失败」。
                if (f.optBoolean("noResult", false)) {
                    noResultFails.add(f.optString("name", "?") + "：" + f.optString("reason", "站内无此书"));
                } else {
                    fails.add(new FailItem(f.optString("name", "?"),
                            f.optString("reason", "失败"), f.optString("sourceUrl", "")));
                }
            }
        }
        JSONArray arr = res.optJSONArray("groups");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject g = arr.optJSONObject(i);
            if (g == null) continue;
            JSONArray hits = g.optJSONArray("hits");
            if (hits == null || hits.length() == 0) continue;
            List<Hit> hs = new ArrayList<>();
            for (int j = 0; j < hits.length(); j++) {
                JSONObject h = hits.optJSONObject(j);
                if (h == null) continue;
                hs.add(new Hit(h.optString("sourceUrl", ""), h.optString("sourceName", ""),
                        h.optJSONObject("book")));
            }
            if (hs.isEmpty()) continue;
            String name = g.optString("name", "");
            String author = g.optString("author", "");
            if (blocked.contains(name + "\u0000" + author)) continue;
            Group same = findGroup(name, author);
            if (same == null) {
                Group ng = new Group(name, author, hs);
                ng.score = g.optInt("score", 0);
                ng.weak = g.optBoolean("weak", false);
                groups.add(ng);
            } else {
                same.hits.addAll(hs);
                // 同名卡若曾被任一源判定为弱相关，保留该标记（避免真命中被误沉底）
                if (g.optBoolean("weak", false)) same.weak = true;
                if (g.optInt("score", 0) > same.score) same.score = g.optInt("score", 0);
            }
        }
        // 流式合并只累积数据；排序并入 180ms 去抖的 paintTask（sortGroups 内置），
        // 避免 74 个源每个回来都跑一次全量排序。最终排序在 onFinished 也会再跑一次。
    }

    private Group findGroup(String name, String author) {
        for (Group g : groups) {
            if (g.name.equals(name) && g.author.equals(author)) return g;
        }
        return null;
    }

    /* ---------------- 状态条 ---------------- */

    private void syncStatus() {
        if (statusPill != null) {
            if (searched == 0 && !searching) {
                statusPill.setVisibility(View.GONE);
            } else {
                statusPill.setVisibility(View.VISIBLE);
                String s = "结果 " + groups.size()
                        + (searching ? " · 进度 " + searched + "/" + totalSrc
                        : " · 已搜 " + searched + "/" + totalSrc + " 个书源");
                // 按来源筛过之后，列表里只剩一部分：状态栏必须写清楚「显示几本」，
                // 否则用户会以为搜索结果变少了
                if (!srcFilter.isEmpty()) s += " · 显示 " + visible.size() + " 本";
                if (filteredN > 0) s += " · 过滤 " + filteredN;
                if (fails.size() > 0) s += " · " + fails.size() + " 个书源异常";
                if (noResultFails.size() > 0) s += " · " + noResultFails.size() + " 个源无此书";
                statusPill.setText(s);
            }
        }
        if (failNote != null) {
            if (fails.isEmpty() && noResultFails.isEmpty()) {
                failNote.setVisibility(View.GONE);
            } else {
                failNote.setVisibility(View.VISIBLE);
                // 红色只数「真异常」；「无此书」是正常情况，单独措辞，不吓人。
                if (fails.isEmpty()) {
                    failNote.setText(noResultFails.size() + " 个源没有这本书（正常）· 点这里看");
                } else if (noResultFails.isEmpty()) {
                    failNote.setText(fails.size() + " 个书源异常 · 点这里看原因");
                } else {
                    failNote.setText(fails.size() + " 个书源异常 · " + noResultFails.size()
                            + " 个源无此书 · 点这里看");
                }
            }
        }
        syncHistory();
    }

    private void showFails() {
        // sheet 自带限高滚动，这里只往里堆行
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ModouUi.dp(this, 2), 0, ModouUi.dp(this, 2), 0);
        if (!fails.isEmpty()) {
            TextView cap = ModouUi.tv(this, "这些书源真的出了问题（规则/风控/网络）", 13f, ui.text);
            cap.setTypeface(cap.getTypeface(), android.graphics.Typeface.BOLD);
            cap.setPadding(0, ModouUi.dp(this, 4), 0, ModouUi.dp(this, 2));
            box.addView(cap);
            TextView hint = ModouUi.tv(this,
                    "「请求超时 / Connection reset」多是搜索窗口（8s）太紧、源未必坏，建议单条甄别再禁", 11.5f, ui.sub);
            hint.setPadding(0, ModouUi.dp(this, 2), 0, ModouUi.dp(this, 6));
            box.addView(hint);
            for (FailItem f : fails) box.addView(failRow(f));
            // 一键只禁「规则/风控类」，超时类（App 侧问题）留给用户单条决定，避免误禁好源
            int pending = 0;
            for (FailItem f : fails) if (!f.invalid && !isTimeout(f.reason)) pending++;
            if (pending > 0) {
                TextView all = ModouUi.tv(this, "一键把以上 " + pending + " 个（规则/风控类）异常源标记为失效", 13f, ui.accent);
                all.setTypeface(all.getTypeface(), android.graphics.Typeface.BOLD);
                all.setPadding(0, ModouUi.dp(this, 10), 0, ModouUi.dp(this, 4));
                all.setOnClickListener((v) -> {
                    java.util.Set<String> urls = new java.util.HashSet<>();
                    for (FailItem f : fails) if (!f.invalid && !f.sourceUrl.isEmpty() && !isTimeout(f.reason)) urls.add(f.sourceUrl);
                    markInvalid(urls);
                    for (FailItem f : fails) if (urls.contains(f.sourceUrl)) f.invalid = true;
                    showFails();
                });
                box.addView(all);
            }
        }
        if (!noResultFails.isEmpty()) {
            TextView cap = ModouUi.tv(this, "这些源只是没有《" + lastKeyword() + "》（正常，换本书就有了）", 13f, ui.text);
            cap.setTypeface(cap.getTypeface(), android.graphics.Typeface.BOLD);
            cap.setPadding(0, ModouUi.dp(this, 10), 0, ModouUi.dp(this, 2));
            box.addView(cap);
            for (String f : noResultFails) {
                TextView t = ModouUi.tv(this, f, 12.5f, ui.sub);
                t.setPadding(0, ModouUi.dp(this, 6), 0, ModouUi.dp(this, 6));
                box.addView(t);
            }
        }
        ModouUi.sheet(this, "搜索情况", box);
    }

    /** 单行：源名+原因 + 「标记失效」按钮（已失效则显示「已失效」） */
    private View failRow(FailItem f) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView t = ModouUi.tv(this, f.name + "：" + f.reason, 12.5f, f.invalid ? ui.sub : 0xFFE06A6A);
        t.setPadding(0, ModouUi.dp(this, 6), 0, ModouUi.dp(this, 6));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!f.invalid && !f.sourceUrl.isEmpty()) {
            TextView btn = ModouUi.tv(this, "标记失效", 12.5f, ui.accent);
            btn.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 6), 0, ModouUi.dp(this, 6));
            btn.setOnClickListener((v) -> {
                java.util.Set<String> urls = new java.util.HashSet<>();
                urls.add(f.sourceUrl);
                markInvalid(urls);
                f.invalid = true;
                showFails();
            });
            row.addView(btn);
        } else if (f.invalid) {
            TextView tag = ModouUi.tv(this, "已失效", 12.5f, ui.sub);
            tag.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 6), 0, ModouUi.dp(this, 6));
            row.addView(tag);
        }
        return row;
    }

    /** 按 bookSourceUrl 把源标记为失效（enabled=false 并落库），下次搜索自动跳过 */
    private void markInvalid(java.util.Set<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        ModouStore store = ModouStore.get(this);
        int done = 0;
        try {
            JSONArray arr = new JSONArray(store.all("sources"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (!urls.contains(o.optString("bookSourceUrl", ""))) continue;
                o.put("enabled", false);
                store.put("sources", o.toString());
                done++;
            }
            SourceService svc = SourceService.get();
            if (svc != null) svc.invalidateDefs();
        } catch (Exception e) {
            ModouUi.toast(this, "标记失败：" + e.getMessage());
            return;
        }
        ModouUi.toast(this, "已标记失效 " + done + " 个源 · 下次搜索生效");
    }

    /** 超时 / 连接重置类：多为 App 侧搜索窗口太紧或限流，源未必坏，不进一键全禁 */
    private static boolean isTimeout(String r) {
        if (r == null) return false;
        return r.contains("超时") || r.contains("Connection reset")
                || r.contains("连接重置") || r.contains("连接被重置");
    }

    /** 最近一次搜索词，用于失败弹窗里点明「没有哪本书」 */
    private String lastKeyword() {
        return lastKw != null && !lastKw.isEmpty() ? lastKw : "你要搜的词";
    }

    /* ---------------- 搜索历史 ---------------- */

    private void rememberKeyword(String kw) {
        List<String> h = history();
        h.remove(kw);
        h.add(0, kw);
        while (h.size() > HISTORY_MAX) h.remove(h.size() - 1);
        putHistory(h);
    }

    private List<String> history() {
        List<String> out = new ArrayList<>();
        String raw = getSharedPreferences("modou", MODE_PRIVATE).getString(PREF_HISTORY, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignore) {
            /* 脏存档：当没有历史 */
        }
        return out;
    }

    private void putHistory(List<String> h) {
        getSharedPreferences("modou", MODE_PRIVATE).edit()
                .putString(PREF_HISTORY, new JSONArray(h).toString()).apply();
    }

    /** 没有结果、也没在搜时，把历史摆在搜索框下面；一开始搜就收起来 */
    private void syncHistory() {
        if (historyBox == null) return;
        historyBox.removeAllViews();
        boolean idle = !searching && searched == 0 && groups.isEmpty();
        List<String> h = history();
        if (!idle || h.isEmpty()) {
            historyBox.setVisibility(View.GONE);
            return;
        }
        historyBox.setVisibility(View.VISIBLE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ModouUi.dp(this, 2), ModouUi.dp(this, 14),
                ModouUi.dp(this, 2), ModouUi.dp(this, 6));
        TextView t = ModouUi.tv(this, "最近搜索", 12.5f, ui.sub);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = ModouUi.tv(this, "清空", 12.5f, ui.accent);
        clear.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 4),
                ModouUi.dp(this, 4), ModouUi.dp(this, 4));
        clear.setOnClickListener(v -> {
            putHistory(new ArrayList<>());
            syncHistory();
        });
        head.addView(clear);
        historyBox.addView(head);

        LinearLayout cardView = ui.groupCard(this);
        // 0.9.169：历史词区图标容器交给语言。墨读逐像素保持 0.9.168 的裸 16dp
        // 图标手写行；M3 / iOS 走与设置页同一套 iconRow（一改全改）。
        boolean styled = ui.spec.searchBarStyle != 0;
        for (int i = 0; i < h.size(); i++) {
            final String kw = h.get(i);
            if (i > 0) cardView.addView(styled
                    ? ModouUi.groupDivider(this, true) : ModouUi.groupDivider(this));
            if (styled) {
                // 时钟是「最近搜索」的固定语义（非动态实体，故不用 ItemBadge）：
                // iOS 彩色方块 + 缩进线 + ›；M3 tonal 圆 + 不画线。
                cardView.addView(ui.iconRow(this, ListIcon.CLOCK, kw, null, null, () -> {
                    input.setText(kw);
                    input.setSelection(kw.length());
                    doSearch();
                }));
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ModouUi.dp(this, 12), ModouUi.dp(this, 10),
                    ModouUi.dp(this, 12), ModouUi.dp(this, 10));

            ImageView iv = new ImageView(this);
            int s = ModouUi.dp(this, 16);
            iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            android.graphics.drawable.Drawable d =
                    getResources().getDrawable(R.drawable.ic_book_history, getTheme()).mutate();
            d.setTint(ui.sub);
            iv.setImageDrawable(d);
            row.addView(iv);

            TextView k = ModouUi.tv(this, kw, 14, ui.text);
            k.setSingleLine(true);
            k.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            klp.leftMargin = ModouUi.dp(this, 10);
            row.addView(k, klp);
            row.setOnClickListener(v -> {
                input.setText(kw);
                input.setSelection(kw.length());
                doSearch();
            });
            cardView.addView(row);
        }
        historyBox.addView(cardView);
    }

    /* ---------------- 结果筛选 ---------------- */

    private void showFilter() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // ① 按书源筛：搜索是「一个关键词 × 多个书源」，用户真正想收窄的往往是
        // 「我只信这几个源」，而不是排序。原来这里只有两条排序，等于挂错了入口。
        TextView cap = ModouUi.tv(this, "只看这些书源", 11.5f, ui.sub);
        cap.setPadding(ModouUi.dp(this, 4), ModouUi.dp(this, 4), 0, ModouUi.dp(this, 6));
        box.addView(cap);

        final android.app.Dialog[] holder = new android.app.Dialog[1];
        addFilterRow(box, holder, "全部来源", groups.size() + " 本", srcFilter.isEmpty(), "");
        for (String[] src : sourceChoices()) {
            int n = 0;
            for (Group g : groups) {
                for (Hit h : g.hits) {
                    if (src[0].equals(h.sourceUrl)) { n++; break; }
                }
            }
            addFilterRow(box, holder, src[1], n + " 本", src[0].equals(srcFilter), src[0]);
        }

        box.addView(ModouUi.groupDivider(this));
        TextView cap2 = ModouUi.tv(this, "排序", 11.5f, ui.sub);
        cap2.setPadding(ModouUi.dp(this, 4), ModouUi.dp(this, 12), 0, ModouUi.dp(this, 6));
        box.addView(cap2);

        String[] opts = {"来源多的在前", "书名排序"};
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ModouUi.dp(this, 4), ModouUi.dp(this, 12),
                    ModouUi.dp(this, 4), ModouUi.dp(this, 12));
            TextView t = ModouUi.tv(this, opts[i], 14, ui.text);
            row.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(ModouUi.tv(this, sortMode == i ? "✓" : "", 14, ui.accent));
            row.setOnClickListener(v -> {
                sortMode = idx;
                sortGroups();
                adapter.notifyDataSetChanged();
                if (holder[0] != null) holder[0].dismiss();
            });
            box.addView(row);
            if (i < opts.length - 1) box.addView(ModouUi.groupDivider(this));
        }

        holder[0] = ModouUi.sheet(this, "筛选结果", box);
    }

    /** 筛选面板里的一行：名称 + 命中本数 + 勾选；点一下立刻生效并收起 */
    private void addFilterRow(LinearLayout box, android.app.Dialog[] holder,
                              String label, String count, boolean on, String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ModouUi.dp(this, 4), ModouUi.dp(this, 12),
                ModouUi.dp(this, 4), ModouUi.dp(this, 12));

        TextView t = ModouUi.tv(this, label, 14, on ? ui.text : ui.text);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView c = ModouUi.tv(this, count, 12, ui.sub);
        c.setPadding(0, 0, ModouUi.dp(this, 8), 0);
        row.addView(c);
        row.addView(ModouUi.tv(this, on ? "✓" : "", 14, ui.accent));

        row.setOnClickListener(v -> {
            srcFilter = url;
            rebuildVisible();
            adapter.notifyDataSetChanged();
            syncStatus();
            if (holder[0] != null) holder[0].dismiss();
            ModouUi.toast(this, url.isEmpty() ? "已显示全部来源" : "只看：" + label);
        });
        box.addView(row);
        box.addView(ModouUi.groupDivider(this));
    }

    /* ---------------- 书源 ---------------- */

    private void loadSources() {
        sources.clear();
        try {
            String s = ModouStore.get(this).all("sources");
            if (s != null && !s.isEmpty()) {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    if (!o.optBoolean("enabled", true)) continue;
                    if (o.optString("searchUrl", "").trim().isEmpty()) continue;
                    sources.add(o);
                }
            }
        } catch (Exception e) {
            /* 读不到书源就当作没有，搜索时会提示 */
        }
        Set<String> saved = savedPicked();
        picked.clear();
        for (JSONObject o : sources) {
            String u = o.optString("bookSourceUrl", "");
            if (u.isEmpty()) continue;
            if (saved == null || saved.contains(u)) picked.add(u); // 默认全选，与网页版一致
        }
        // 存档里一个都匹配不上（换了书源 / 旧格式的存档）：退回全选。
        // 否则用户看到的是「一个都没选」，点搜索只得到一句「没有选中的书源」
        if (picked.isEmpty() && !sources.isEmpty()) {
            for (JSONObject o : sources) {
                String u = o.optString("bookSourceUrl", "");
                if (!u.isEmpty()) picked.add(u);
            }
        }
    }

    private Set<String> savedPicked() {
        String s = getSharedPreferences("modou", MODE_PRIVATE).getString(PREF_PICKED, null);
        if (s == null || s.isEmpty()) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String part : s.split("\n")) if (!part.isEmpty()) out.add(part);
        return out;
    }

    private void savePicked() {
        StringBuilder b = new StringBuilder();
        for (String u : picked) b.append(u).append("\n");
        getSharedPreferences("modou", MODE_PRIVATE).edit()
                .putString(PREF_PICKED, b.toString()).apply();
    }

    /**
     * 选择要搜索的书源。
     *
     * 原来是系统 AlertDialog 的 setMultiChoiceItems：深色模式下依然是一张白底列表，
     * 勾选框还是 Material 蓝方块。改成和书架一致的纸墨 sheet + 自绘勾选块。
     */
    private void pickSources() {
        if (sources.isEmpty()) {
            ModouUi.toast(this, "还没有可用的书源：请先导入书源");
            return;
        }
        ModouUi u = ModouUi.of(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final TextView summary = ModouUi.tv(this, "", 12.5f, u.sub);
        summary.setPadding(ModouUi.dp(this, 2), 0, 0, ModouUi.dp(this, 6));
        box.addView(summary);

        final LinearLayout[] rows = new LinearLayout[sources.size()];
        final TextView[] ticks = new TextView[sources.size()];
        final int tickSz = ModouUi.dp(this, 22);
        for (int i = 0; i < sources.size(); i++) {
            final String url = sources.get(i).optString("bookSourceUrl", "");
            JSONObject o = sources.get(i);
            String label = firstGroup(o.optString("bookSourceGroup", ""))
                    + " · " + o.optString("bookSourceName", url);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(ModouUi.dp(this, 2), ModouUi.dp(this, 8),
                    ModouUi.dp(this, 2), ModouUi.dp(this, 8));

            TextView tick = ModouUi.tv(this, "", 12, 0xFFFFFFFF);
            tick.setGravity(Gravity.CENTER);
            row.addView(tick, new LinearLayout.LayoutParams(tickSz, tickSz));

            TextView name = ModouUi.tv(this, label, 14, u.text);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nlp.leftMargin = ModouUi.dp(this, 10);
            row.addView(name, nlp);

            row.setOnClickListener((v) -> {
                if (url.isEmpty()) return;
                if (picked.contains(url)) picked.remove(url);
                else picked.add(url);
                paintTick(tick, picked.contains(url));
                paintSrcSummary(summary);
            });
            rows[i] = row;
            ticks[i] = tick;
            box.addView(row);
        }

        final Dialog d = ModouUi.sheet(this, "选择要搜索的书源", box);
        box.addView(ModouUi.sheetButtons(this, d, "全不选", "完成", () -> {
            picked.clear();
            savePicked();
        }, this::savePicked));
        // 顶部「全选」做成一个可点的文字，塞在标题下一行右侧
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // 直接复用第一行位置：把「全选」按钮加到 summary 那一行的右侧
        box.removeView(summary);
        head.addView(summary, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView all = ModouUi.tv(this, "全选", 13, u.accent);
        all.setPadding(ModouUi.dp(this, 10), 0, ModouUi.dp(this, 2), 0);
        all.setOnClickListener((v) -> {
            picked.clear();
            for (JSONObject o : sources) picked.add(o.optString("bookSourceUrl", ""));
            for (int i = 0; i < rows.length; i++) paintTick(ticks[i], true);
            paintSrcSummary(summary);
        });
        head.addView(all);
        box.addView(head, 0);

        for (int i = 0; i < sources.size(); i++) {
            paintTick(ticks[i], picked.contains(sources.get(i).optString("bookSourceUrl", "")));
        }
        paintSrcSummary(summary);
    }

    /** 自绘勾选块：选中＝强调色实底 + 勾，未选＝透明底 + 1.5dp 描边 */
    private void paintTick(TextView tick, boolean on) {
        ModouUi u = ModouUi.of(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(ModouUi.dp(this, 6));
        if (on) {
            g.setColor(u.accent);
            tick.setText("✓");
        } else {
            g.setColor(0x00000000);
            g.setStroke(Math.max(1, ModouUi.dp(this, 1.5f)), u.line);
            tick.setText("");
        }
        tick.setBackground(g);
    }

    private void paintSrcSummary(TextView tv) {
        int n = 0;
        for (JSONObject o : sources) if (picked.contains(o.optString("bookSourceUrl", ""))) n++;
        tv.setText("已选 " + n + " / " + sources.size() + " 个书源");
    }

    /** Legado 的分组字段常见「组1,组2」，取第一个作为主分组 */
    private static String firstGroup(String s) {
        // 与书源列表同一套清洗：批号 / 校验 / 测试类分组不显示给用户
        return SourceGroup.ofRaw(s);
    }

    /* ---------------- 打开 ---------------- */

    private void openGroup(Group g) {
        Hit h = g.hits.get(0);
        if (h == null || h.book == null || h.book.optString("bookUrl", "").isEmpty()) {
            ModouUi.toast(this, "该书源没解析出书籍地址");
            return;
        }
        SourceService svc = SourceService.get();
        if (svc == null) {
            ModouUi.toast(this, "书源服务未启动");
            return;
        }
        final int myGen = gen;
        statusPill.setVisibility(View.VISIBLE);
        statusPill.setText("正在获取《" + g.name + "》的信息…");

        JSONObject seed = h.book;
        try {
            seed.put("sourceUrl", h.sourceUrl);
            seed.put("sourceName", h.sourceName);
        } catch (Exception ignore) {
            /* 不可能 */
        }

        // 详情失败不阻断：搜索结果里已有书名/作者/封面，目录用 bookUrl 也能取
        svc.info(seed, res -> runOnUiThread(() -> {
            JSONObject info = seed;
            if (res != null && res.optBoolean("ok", false)) {
                JSONObject bi = res.optJSONObject("info");
                if (bi != null) {
                    info = bi;
                    try {
                        info.put("sourceUrl", h.sourceUrl);
                        info.put("sourceName", h.sourceName);
                        if (info.optString("bookUrl", "").isEmpty()) {
                            info.put("bookUrl", seed.optString("bookUrl", ""));
                        }
                    } catch (Exception ignore) {
                        /* 不可能 */
                    }
                }
            }
            fetchToc(myGen, info, h);
        }));
    }

    private void fetchToc(int myGen, JSONObject info, Hit h) {
        SourceService svc = SourceService.get();
        if (svc == null) return;
        statusPill.setText("正在获取目录…");
        svc.toc(info, res -> runOnUiThread(() -> {
            if (myGen != gen) return;
            if (res == null || !res.optBoolean("ok", false)) {
                statusPill.setText(res == null ? "目录获取失败" : res.optString("error", "目录获取失败"));
                return;
            }
            JSONArray chapters = res.optJSONArray("chapters");
            if (chapters == null || chapters.length() == 0) {
                statusPill.setText("该书源没有解析出章节");
                return;
            }
            String id = savePreview(info, h, chapters);
            if (id == null) {
                statusPill.setText("暂存书籍失败");
                return;
            }
            BookActivity.launch(this, id);
            statusPill.setVisibility(View.GONE);
        }));
    }

    /**
     * 试读暂存：写进 books 表但打 preview 标记，书架过滤掉它。
     * id 必须与网页版一致（sourceUrl||bookUrl），否则同一本书会在两边各存一份。
     */
    private String savePreview(JSONObject info, Hit h, JSONArray chapters) {
        try {
            String bookUrl = info.optString("bookUrl", "");
            String id = h.sourceUrl + "||" + bookUrl;
            JSONObject rec = new JSONObject();
            rec.put("id", id);
            rec.put("sourceUrl", h.sourceUrl);
            rec.put("sourceName", h.sourceName);
            rec.put("name", info.optString("name", ""));
            rec.put("author", info.optString("author", ""));
            rec.put("intro", info.optString("intro", ""));
            rec.put("coverUrl", info.optString("coverUrl", ""));
            rec.put("bookUrl", bookUrl);
            rec.put("tocUrl", info.optString("tocUrl", ""));
            rec.put("kind", info.optString("kind", ""));
            rec.put("lastChapter", info.optString("lastChapter", ""));
            // 详情页要显示字数与更新时间：搜索/详情阶段拿到的就顺手存下，
            // 否则书一旦入架就再也补不回来（下次开详情只剩书名作者）
            rec.put("wordCount", info.optString("wordCount", ""));
            rec.put("updateTime", info.optString("updateTime", ""));
            rec.put("group", "默认");
            rec.put("chapters", chapters);
            rec.put("addedAt", System.currentTimeMillis());
            rec.put("updatedAt", System.currentTimeMillis());
            rec.put("preview", true);
            ModouStore.get(this).put("books", rec.toString());
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------------- 列表 ---------------- */

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public Object getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Group g = visible.get(position);
            RowHolder h = (convertView != null && convertView.getTag() instanceof RowHolder)
                    ? (RowHolder) convertView.getTag() : newRow();
            bindRow(h, g);
            return h.row;
        }
    };

    /** 结果行只建一次 View 树：滚动时复用，不再每行 new 十几个 View/Drawable */
    private static final int MAX_TAGS = 4;

    private RowHolder newRow() {
        final RowHolder h = new RowHolder();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 0.9.169：M3 结果行是独立 filled card，左右留一点内缩让卡片不贴边；
        // 墨读 / iOS 透明行保持 0（逐像素零回归）。
        int hitPadH = ui.spec.searchHitCardFilled ? ModouUi.dp(this, 6) : 0;
        row.setPadding(hitPadH, ModouUi.dp(this, 8), hitPadH, ModouUi.dp(this, 8));
        h.row = row;

        /* 封面：右下角压书源名，右上角一个「✕」屏蔽 */
        FrameLayout coverBox = new FrameLayout(this);
        int cw = ModouUi.dp(this, 64);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cw, Math.round(cw * 1.36f));
        clp.rightMargin = ModouUi.dp(this, 12);
        int coverR = ui.spec.searchCoverCornerDp > 0 ? ui.spec.searchCoverCornerDp : 12;
        coverBox.setBackground(ModouUi.squircle(this, 0xFFD8CFBB, coverR));
        coverBox.setClipToOutline(true);
        h.cover = new ImageView(this);
        h.cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(h.cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        h.src = ModouUi.tv(this, "", 8.5f, 0xFFFFFFFF);
        h.src.setSingleLine(true);
        h.src.setEllipsize(TextUtils.TruncateAt.END);
        h.src.setPadding(ModouUi.dp(this, 3), ModouUi.dp(this, 1),
                ModouUi.dp(this, 3), ModouUi.dp(this, 1));
        h.src.setBackground(ModouUi.round(0x8C000000, 3));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.gravity = Gravity.BOTTOM | Gravity.START;
        slp.setMargins(ModouUi.dp(this, 3), 0, ModouUi.dp(this, 3), ModouUi.dp(this, 3));
        coverBox.addView(h.src, slp);

        TextView block = ModouUi.tv(this, "✕", 9, 0xFFFFFFFF);
        block.setGravity(Gravity.CENTER);
        block.setBackground(ModouUi.round(0x99000000, 999));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ModouUi.dp(this, 17), ModouUi.dp(this, 17));
        blp.gravity = Gravity.TOP | Gravity.END;
        blp.setMargins(0, ModouUi.dp(this, 3), ModouUi.dp(this, 3), 0);
        block.setOnClickListener(v -> {
            Group g = h.g;
            if (g == null) return;
            blocked.add(g.name + "\u0000" + g.author);
            groups.remove(g);
            adapter.notifyDataSetChanged();
            syncStatus();
            ModouUi.toast(this, "已屏蔽《" + g.name + "》");
        });
        coverBox.addView(block, blp);
        row.addView(coverBox, clp);

        /* 中间：书名 / 作者 + 最新章 / 标签 */
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        h.name = ModouUi.tv(this, "", 16, ui.text);
        h.name.setTypeface(h.name.getTypeface(), android.graphics.Typeface.BOLD);
        h.name.setSingleLine(true);
        h.name.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(h.name);
        h.meta = ModouUi.tv(this, "", 11.5f, ui.sub);
        h.meta.setSingleLine(true);
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        h.meta.setPadding(0, ModouUi.dp(this, 4), 0, 0);
        mid.addView(h.meta);

        LinearLayout tags = new LinearLayout(this);
        tags.setOrientation(LinearLayout.HORIZONTAL);
        tags.setPadding(0, ModouUi.dp(this, 6), 0, 0);
        h.tagViews = new TextView[MAX_TAGS];
        for (int i = 0; i < MAX_TAGS; i++) {
            TextView t = tagSmall("");
            tags.addView(t, tagLp(i > 0));
            h.tagViews[i] = t;
        }
        mid.addView(tags);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        /* 右侧：更多操作 */
        TextView more = ModouUi.tv(this, "⋯", 17, ui.sub);
        more.setGravity(Gravity.CENTER);
        more.setLayoutParams(new LinearLayout.LayoutParams(
                ModouUi.dp(this, 36), ModouUi.dp(this, 36)));
        more.setOnClickListener(v -> {
            if (h.g != null) showItemMenu(h.g);
        });
        row.addView(more);

        // 0.9.169：结果行背景交给语言。墨读 / iOS 是透明行 + 涟漪（原样）；
        // M3 是常驻 filled card（surface 分层）+ 叠在上面的涟漪。
        if (ui.spec.searchHitCardFilled) {
            android.graphics.drawable.GradientDrawable cardBg =
                    new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(ui.card);
            cardBg.setCornerRadius(ModouUi.dp(this, ui.spec.searchHitCardCornerDp));
            android.graphics.drawable.GradientDrawable mask =
                    new android.graphics.drawable.GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(ModouUi.dp(this, ui.spec.searchHitCardCornerDp));
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ModouUi.alpha(ui.text, 0x14)),
                    cardBg, mask));
        } else {
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                            ModouUi.alpha(ui.text, 0x14)), null,
                    ModouUi.squircle(this, 0xFFFFFFFF, 12)));
        }
        row.setOnClickListener(v -> {
            if (h.g != null) openGroup(h.g);
        });
        row.setTag(h);
        return h;
    }

    /** 只刷文本与可见性：复用行时零 View 创建 */
    private void bindRow(RowHolder h, Group g) {
        h.g = g;
        Hit h0 = g.hits.get(0);
        CoverLoader.load(h.cover, h0.book == null ? "" : h0.book.optString("coverUrl", ""), g.name);
        h.src.setText(h0.sourceName.isEmpty() ? "书源" : h0.sourceName);
        h.name.setText(g.name);

        StringBuilder meta = new StringBuilder();
        if (!g.author.isEmpty()) meta.append(g.author);
        String last = h0.book == null ? "" : h0.book.optString("lastChapter", "");
        if (!last.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("最新：").append(last);
        }
        if (meta.length() > 0) {
            h.meta.setText(meta.toString());
            h.meta.setVisibility(View.VISIBLE);
        } else {
            h.meta.setVisibility(View.GONE);
        }

        // 标签最多 4 个：分类/更新/来源数/可能不相关。预建的 TextView 按需显隐，避免每行 new。
        String[] texts = new String[MAX_TAGS];
        int n = 0;
        String kind = h0.book == null ? "" : h0.book.optString("kind", "").trim();
        if (kind.length() > 6) kind = kind.substring(0, 6);
        if (!kind.isEmpty()) texts[n++] = kind;
        String up = h0.book == null ? "" : h0.book.optString("updateTime", "").trim();
        if (up.length() > 10) up = up.substring(0, 10);
        if (!up.isEmpty()) texts[n++] = up;
        texts[n++] = g.hits.size() > 1 ? g.hits.size() + " 个来源" : "1 个来源";
        if (g.weak) texts[n++] = "可能不相关";
        int gap = ModouUi.dp(this, 5);
        for (int i = 0; i < MAX_TAGS; i++) {
            TextView t = h.tagViews[i];
            if (i < n) {
                t.setText(texts[i]);
                t.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
                if (lp != null) {
                    int want = i == 0 ? 0 : gap;
                    if (lp.leftMargin != want) {
                        lp.leftMargin = want;
                        t.setLayoutParams(lp);
                    }
                }
            } else {
                t.setVisibility(View.GONE);
            }
        }
    }

    private TextView tagSmall(String text) {
        TextView t = ModouUi.tv(this, text, 10.5f, ui.sub);
        t.setSingleLine(true);
        t.setPadding(ModouUi.dp(this, 6), ModouUi.dp(this, 2),
                ModouUi.dp(this, 6), ModouUi.dp(this, 2));
        // 0.9.169：标签形状交给语言 —— M3 8dp assist chip 带细描边、
        // iOS 全圆胶囊、墨读 5dp 圆角（逐像素原样）。
        float tagR = ui.spec.searchTagCornerDp > 0f ? ui.spec.searchTagCornerDp : 5f;
        if (ui.spec.searchTagStroke) {
            t.setBackground(ModouUi.squircle(this, ModouUi.alpha(ui.text, 0x0C),
                    tagR, ui.spec.divider, 0.5f));
        } else {
            t.setBackground(ModouUi.round(ModouUi.alpha(ui.text, 0x0C), tagR));
        }
        return t;
    }

    private LinearLayout.LayoutParams tagLp(boolean notFirst) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (notFirst) lp.leftMargin = ModouUi.dp(this, 5);
        return lp;
    }

    private void showItemMenu(final Group g) {
        ModouUi.sheetList(this, g.name, new String[]{"按书名再搜一次", "屏蔽这本书", "复制书名"}, w -> {
            if (w == 0) {
                input.setText(g.name);
                input.setSelection(g.name.length());
                doSearch();
            } else if (w == 1) {
                blocked.add(g.name + "\u0000" + g.author);
                groups.remove(g);
                adapter.notifyDataSetChanged();
                syncStatus();
            } else {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("书名", g.name));
                ModouUi.toast(this, "已复制");
            }
        });
    }

    private static List<String> sourceNames(Group g) {
        List<String> out = new ArrayList<>();
        for (Hit h : g.hits) out.add(h.sourceName.isEmpty() ? h.sourceUrl : h.sourceName);
        return out;
    }

    private static final class Hit {
        final String sourceUrl;
        final String sourceName;
        final JSONObject book;

        Hit(String u, String n, JSONObject b) {
            sourceUrl = u;
            sourceName = n;
            book = b;
        }
    }

    /** 搜索结果行的 ViewHolder：一次建树，滚动时只 bind 文本 */
    private static final class RowHolder {
        LinearLayout row;
        ImageView cover;
        TextView src, name, meta;
        TextView[] tagViews;
        Group g;
    }

    private static final class Group {
        final String name;
        final String author;
        final List<Hit> hits;
        /** 引擎算的相关性分（书名命中100/子序列80/作者60/简介20/无0）；弱相关源的书为 0 */
        int score;
        /** 来自「整源都没命中关键词」的弱相关源：基本可判定不相关，沉底并标注 */
        boolean weak;

        Group(String n, String a, List<Hit> h) {
            name = n;
            author = a;
            hits = h;
        }
    }

    /** 搜索失败的一条：源名 + 原因 + 源唯一标识（bookSourceUrl），用于「标记失效」精准禁用 */
    private static final class FailItem {
        final String name;
        final String reason;
        final String sourceUrl;
        boolean invalid; // 已被用户标记为失效
        FailItem(String n, String r, String u) {
            name = n; reason = r; sourceUrl = u; invalid = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 外观可能在别处被改过（阅读页夜间 / 设置页 / 系统深浅）：整页重建才换得掉
        // 已经写死在 View 上的颜色，否则会出现「同一屏两套色调」
        ModouUi.syncTheme(this);
    }
}
