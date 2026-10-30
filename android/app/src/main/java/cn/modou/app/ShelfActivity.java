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
    /** 筛选弹层是否处于「管理分组」模式（点 chip 即删除） */
    private boolean filterManage = false;

    private LinearLayout root;
    private FrameLayout paneBox;
    /** 首页横滑容器：书架 / 书源 / 规则 / 设置 四页原地切换 */
    private HomePager pager;
    /** 书源页 / 设置页的实例：它们要开系统文件选择器，回调得转发回去 */
    private SourcesPanel sourcesPanel;
    private SettingsPanel settingsPanel;
    private LinearLayout headBox;
    /** 头部大标题：随「书架 / 阅读历史 / 收藏」切换文案 */
    private TextView headTitle;
    /** 非书架视图时出现的「‹」返回书架（页签整行已去掉，靠它回退） */
    private TextView headBack;
    /** 底部 tag 的图标+文字，切页时刷新选中色 */
    private final List<LinearLayout> navItems = new ArrayList<>();
    private final List<ImageView> navIcons = new ArrayList<>();
    private final List<TextView> navLabels = new ArrayList<>();

    /** onCreate 也会走一次 onResume，首次不必重读（reload 已在 onCreate 末尾调过） */
    private boolean resumed;

    /** 本页是否被盖住了（阅读页/导入页在上面）：盖住期间一律不读库、不刷新 */
    private boolean paused;
    /** lite 回填线程是否在跑（避免重复起线程） */
    private boolean backfilling;

    @Override
    protected void onCreate(Bundle b) {
        ModouUi.applyTheme(this); // 必须早于 super：窗口底色/系统栏图标在此定型
        super.onCreate(b);
        ui = ModouUi.of(this);
        store = ModouStore.get(this);
        // 旧版本导入时会另存一份原文件副本到应用私有目录（每本书占两份空间）。
        // 现在改成读用户自己的文件夹、不再复制，这里把历史遗留的副本目录清一次。
        BookFiles.clearOldCopies(this);
        // 旧版 IndexedDB / 导入临时文件 / 已删书的孤儿插图：启动后低优先级清一次。
        // VACUUM 容易长时间占写锁，放到设置页的显式「深度回收」。
        StorageJanitor.autoClean(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        // 左右不给 root 加 padding：底部导航要通栏；头部/页签自己补
        root.setPadding(0, statusBarPad(), 0, 0);
        setContentView(root);
        // 系统栏与页面同色：导航条（手势条）区域不吃系统默认底色，
        // 否则底栏下面会垫出一截白
        getWindow().setStatusBarColor(ui.bg);
        getWindow().setNavigationBarColor(ui.bg);

        // 书架 pane：头部 + 内容区（书架 / 阅读历史 / 收藏 共用这一个头部），
        // 作为横滑容器的第 0 页。原来的页签整行已去掉：它占掉一条书区高度，
        // 而三个视图的入口收进头部「更多」、返回靠头部的 ‹。
        buildHead();
        paneBox = new FrameLayout(this);
        // 原来靠 root 的左右 padding，root 通栏后由这里统一给内容页留边
        paneBox.setPadding(ModouUi.dp(this, 12), 0, ModouUi.dp(this, 12), 0);
        LinearLayout shelfPane = new LinearLayout(this);
        shelfPane.setOrientation(LinearLayout.VERTICAL);
        shelfPane.addView(headBox);
        shelfPane.addView(paneBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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
            slot.setBackgroundColor(ui.bg); // 未建好时也是一块页面底色，不是白纸
            pager.addView(slot);
        }
        pager.setLazy(this::buildTagPage);
        pager.setOnPageChange(this::navRefresh);
        root.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(
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
        // 从导入页/详情页回来要重读一遍：新导的书、刚改的分组、阅读进度都只写在库里，
        // 不重读就会看到一本「刚才明明导入成功了却不在书架」的错觉
        paused = false;
        if (resumed) {
            reload();
            backfillLite();
        }
        resumed = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 盖住本页就收手：阅读页正在取章节，书架不该再占着数据库锁和主线程
        paused = true;
    }

    /**
     * 书架就是应用根页：返回应当回桌面。
     * 以前是 finish() 落到下面那层网页壳（它的书架已被删除，只会看到一张空页），
     * 所以改成 moveTaskToBack——任务栈不动，下次进来还是这个书架。
     * 停在其他功能 tag 时，返回先回到书架页。
     */
    @Override
    public void onBackPressed() {
        if (pager != null && pager.page() != 0) {
            pager.setPage(0, true);
            return;
        }
        // 阅读历史 / 收藏是从「更多」进的次级视图：返回先回书架（等同于头部 ‹）
        if (!"shelf".equals(tab)) {
            switchTab("shelf");
            return;
        }
        moveTaskToBack(true);
    }

    private int statusBarPad() {
        // 状态栏高度拿不准时给个保守值：宁可多一点，也别把标题顶到状态栏底下
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : ModouUi.dp(this, 24);
    }

    private int navBarPad() {
        // 手势条/导航条高度：底部导航内容不能被它压住
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /* ---------------- 底部导航 ---------------- */

    /**
     * 常驻功能入口：书架 / 书源 / 规则 / 设置。
     * 以前这些页只能从设置页一层层点进去，首页没有任何功能 tag —— 找个书源管理
     * 要三步。现在一级入口放底部，和网页版原来的侧栏/底栏同级。
     * 点 tag 原地切换（横滑容器），不跳 Activity；左右滑动同样可以翻页。
     */
    private View buildBottomNav() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        navItems.clear();
        navIcons.clear();
        navLabels.clear();
        bar.addView(navItem("书架", R.drawable.ic_nav_shelf, 0));
        bar.addView(navItem("书源", R.drawable.ic_nav_source, 1));
        bar.addView(navItem("规则", R.drawable.ic_nav_rule, 2));
        bar.addView(navItem("设置", R.drawable.ic_nav_gear, 3));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(ui.bg);
        View line = new View(this);
        line.setBackgroundColor(ui.line);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ModouUi.dp(this, 1))));
        // 内容行高度写死：图标 24 + 间距 2 + 文字 ≈ 40，上下各留 8 —— 比例不随机型漂移
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(this, 56));
        wrap.addView(bar, bl);

        // 手势条区域单独一个同色 spacer：有些系统会在这块垫自己的底色，
        // 出现「底栏一大截发白」的色差 —— 主动铺一层页面底色盖掉它
        int navPad = navBarPad();
        if (navPad > 0) {
            View spacer = new View(this);
            spacer.setBackgroundColor(ui.bg);
            wrap.addView(spacer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, navPad));
        }
        return wrap;
    }

    /** 单个 tag：图标 24dp 紧贴文字 11sp，四等分；点击切页 */
    private View navItem(String label, int iconRes, final int idx) {
        LinearLayout it = new LinearLayout(this);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER);

        ImageView iv = new ImageView(this);
        int s = ModouUi.dp(this, 24);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
        ip.bottomMargin = ModouUi.dp(this, 2);
        iv.setLayoutParams(ip);
        // 图标资源本身是白色 path，必须立刻染色 —— 否则首屏四个 tag 全是白图标，
        // 在米黄底上若隐若现（用户看到的「图标不对」就是这么来的）
        android.graphics.drawable.Drawable d = getResources().getDrawable(iconRes, getTheme()).mutate();
        d.setTint(idx == 0 ? ui.accent : ui.sub);
        iv.setImageDrawable(d);
        it.addView(iv);
        navIcons.add(iv);

        // 文字必须显式铺满整格宽 + 自身内容居中。真机实测：依赖容器 gravity 居中
        // wrap_content 的文字时，部分设备上文字整体贴在格子左缘（图标居中、文字偏左，
        // 看起来就是「图标歪到文字的右上角」）。显式 MATCH_PARENT + CENTER 不给 ROM
        // 任何发挥空间，像素测量偏移为 0。
        TextView t = ModouUi.tv(this, label, 11, idx == 0 ? ui.accent : ui.sub);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        it.addView(t);
        navLabels.add(t);
        navItems.add(it);

        // 按压反馈同样用有界 ripple：tag 区域不该出现越界的扩散圆
        android.graphics.drawable.GradientDrawable navMask = new android.graphics.drawable.GradientDrawable();
        navMask.setColor(0xFFFFFFFF);
        android.graphics.drawable.RippleDrawable rip = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModouUi.alpha(ui.text, 0x1E)),
                null, navMask);
        it.setBackground(rip);
        it.setOnClickListener(v -> {
            if (pager.page() != idx) pager.setPage(idx, true);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        it.setLayoutParams(lp);
        return it;
    }

    /** 切页回调：刷新 4 个 tag 的选中色（选中朱砂、未选灰） */
    private void navRefresh(int idx) {
        for (int i = 0; i < navIcons.size(); i++) {
            boolean on = i == idx;
            navIcons.get(i).getDrawable().setTint(on ? ui.accent : ui.sub);
            navLabels.get(i).setTextColor(on ? ui.accent : ui.sub);
        }
    }

    /* ---------------- 头部 ---------------- */

    private void buildHead() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(ModouUi.dp(this, 16), ModouUi.dp(this, 8), ModouUi.dp(this, 4), ModouUi.dp(this, 2));

        headBack = ModouUi.tv(this, "‹", 24, ui.text);
        headBack.setGravity(Gravity.CENTER);
        headBack.setPadding(0, 0, ModouUi.dp(this, 6), 0);
        headBack.setVisibility(View.GONE);
        headBack.setOnClickListener((v) -> switchTab("shelf"));
        h.addView(headBack);

        headTitle = ModouUi.tv(this, "书架", 26, ui.text);
        headTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        h.addView(headTitle);

        View spacer = new View(this);
        h.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // 头部图标：搜索 / 导入 / 更多（对齐网页版 .shelf-head-actions）
        h.addView(iconBtn(R.drawable.ic_sh_search, v -> SearchActivity.launch(this)));
        h.addView(iconBtn(R.drawable.ic_sh_import, v -> ImportActivity.launch(this)));
        h.addView(iconBtn(R.drawable.ic_sh_more, v -> openMore()));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        h.setLayoutParams(hp);
        headBox = h;
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
        android.graphics.drawable.RippleDrawable rip = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModouUi.alpha(ui.text, 0x14)),
                null, null);
        iv.setBackground(rip);
        iv.setOnClickListener(c);
        return iv;
    }

    private void openMore() {
        // 系统 AlertDialog 的白底列表与纸墨界面不是一套语言，统一走自绘底部 sheet。
        // 页签整行去掉后，「阅读历史 / 收藏」的入口就放这里。
        // 「排序方式 / 分组」不再重复放这里：书架顶部已经各有一个直接显示当前值的入口
        ModouUi.sheetList(this, "更多",
                new String[]{"阅读历史", "收藏", "分组管理", "设置"}, w -> {
            if (w == 0) switchTab("history");
            else if (w == 1) switchTab("fav");
            else if (w == 2) manageGroups();
            else SettingsActivity.launch(this);
        });
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
        headTitle.setText("shelf".equals(tab) ? "书架" : "history".equals(tab) ? "阅读历史" : "收藏");
        if (headBack != null) headBack.setVisibility("shelf".equals(tab) ? View.GONE : View.VISIBLE);
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
            runOnUiThread(() -> {
                books.clear();
                books.addAll(bs);
                prog.clear();
                prog.putAll(pm);
                groups.clear();
                groups.addAll(gs);
                sort = so;
                monthMs = ms;
                if (!groups.contains(group)) group = ALL;
                paintHeadTitle();
                // 「库是空的」有两种真相：真没书，或数据还在 WebView 的 IndexedDB 里
                // 没迁过来。后者必须说清楚，否则用户第一反应是「书丢了」。
                if (books.isEmpty() && !store.migrated()) {
                    paintMigrating();
                    return;
                }
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
        if (lastScroller != null) {
            if (lastScroller instanceof AbsListView) {
                AbsListView lv = (AbsListView) lastScroller;
                lastFirstPos = lv.getFirstVisiblePosition();
                View first = lv.getChildCount() > 0 ? lv.getChildAt(0) : null;
                lastFirstTop = first == null ? 0 : first.getTop();
            } else {
                lastScrollY = lastScroller.getScrollY();
            }
        }
        final String key = paneKey();
        final boolean keepScroll = lastScroller != null && key.equals(lastScrollerKey);
        lastScroller = null;

        paneBox.removeAllViews();
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
        if (keepScroll && lastScroller != null) restoreScroll();
    }

    /** 当前列表的身份：tab / 分组 / 排序任一变化都算换了一个列表 */
    private String paneKey() {
        return tab + "|" + group + "|" + sort;
    }

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
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(ui.accent);
        g.setCornerRadius(ModouUi.dp(this, 20));
        retry.setBackground(g);
        retry.setOnClickListener((x) -> reload());
        p.addView(retry);
        paneBox.addView(p);
    }

    private View buildShelfPane() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);

        // 原来这里有一整条「本月阅读」横幅：占书架一行的高度，已经挪到
        // 「更多 → 阅读历史」页的顶部（见 buildHistory），首页只留书和筛选栏。

        // 排序 / 分组栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        // 排序 / 分组各一个入口。
        // 原来这两项挤在同一个叫「筛选」的弹层里：排序不属于筛选，用户每次想换排序
        // 都得先点一个名字对不上的按钮。现在把当前值直接写在按钮上（「最近阅读 ⌄」
        // 「全部 ⌄」），一眼看得出当前是什么状态。
        List<JSONObject> inGroup = filtered();
        TextView cnt = ModouUi.tv(this, countText(inGroup), 12.5f, ui.sub);
        bar.addView(cnt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sc = pill(sortLabel() + " ⌄");
        sc.setOnClickListener((x) -> chooseSort());
        bar.addView(sc);

        TextView gc = pill(groupShort() + " ⌄");
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = ModouUi.dp(this, 8);
        gc.setOnClickListener((x) -> openGroupSheet());
        bar.addView(gc, glp);
        p.addView(bar);

        // 网格要占据剩余高度（权重 1）而不是 wrap_content：后者在 LinearLayout 里
        // 只会画出一行的高度，书一多就滚不动
        View grid = buildGrouped(inGroup, books.isEmpty()
                ? "书架是空的\n导入手机里的 TXT / EPUB，或联网搜书加入。"
                : "这个分组还是空的");
        p.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return p;
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
        String id = b.optString("id", "");
        long t = 0;
        JSONObject p = prog.get(id);
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

    /** 排序方式选择：最近阅读 / 加入时间 / 书名 / 手动 */
    private void chooseSort() {
        final String[] keys = {"recent", "added", "name", "manual"};
        final String[] names = {"最近阅读", "加入时间", "书名", "手动排序"};
        String cur = store.settingStr("shelfSort", "recent");
        String[] labels = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            labels[i] = (keys[i].equals(cur) ? "✓ " : "") + names[i];
        }
        ModouUi.sheetList(this, "排序方式", labels, w -> {
            store.putSettingStr("shelfSort", keys[w]);
            // 再存一份到 SharedPreferences：settings 表读不出来时书架顺序不至于「变回去」
            getSharedPreferences("shelf_prefs", MODE_PRIVATE).edit()
                    .putString("shelfSort", keys[w]).apply();
            sort = keys[w];
            paintPane(); // 顶部按钮上的排序名会跟着一起变，不必再弹 toast 复述
        });
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

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(buildMonthStat()); // 原来压在书架顶上的「本月阅读」，挪到这一页

        if (rows.isEmpty()) {
            box.addView(ModouUi.empty(this, "还没有阅读记录"), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return box;
        }

        ListView lv = new ListView(this);
        lv.setBackgroundColor(Color.TRANSPARENT);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setVerticalScrollBarEnabled(false);
        lv.setClipToPadding(false);
        lv.setPadding(0, 0, 0, ModouUi.dp(this, 20));
        lv.setAdapter(new BaseAdapter() {
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
            public View getView(int i, View cv, ViewGroup parent) {
                JSONObject b = rows.get(i);
                JSONObject p = prog.get(b.optString("id", ""));

                // 行外留白 + 卡片底，做出网页版 .hrow 的「一张一条」
                LinearLayout outer = new LinearLayout(ShelfActivity.this);
                outer.setOrientation(LinearLayout.VERTICAL);
                outer.setPadding(0, ModouUi.dp(ShelfActivity.this, 4), 0, ModouUi.dp(ShelfActivity.this, 4));

                LinearLayout card = new LinearLayout(ShelfActivity.this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
                cg.setColor(ui.card);
                cg.setCornerRadius(ModouUi.dp(ShelfActivity.this, 14));
                card.setBackground(cg);
                card.setElevation(ModouUi.dp(ShelfActivity.this, 1));
                card.setPadding(ModouUi.dp(ShelfActivity.this, 10), ModouUi.dp(ShelfActivity.this, 10),
                        ModouUi.dp(ShelfActivity.this, 12), ModouUi.dp(ShelfActivity.this, 10));

                FrameLayout cf = new FrameLayout(ShelfActivity.this);
                android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
                cbg.setColor(0xFFD8CFBB);
                cbg.setCornerRadius(ModouUi.dp(ShelfActivity.this, 6));
                cf.setBackground(cbg);
                cf.setClipToOutline(true);
                ImageView iv = new ImageView(ShelfActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cf.addView(iv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                CoverLoader.load(iv, b.optString("coverUrl", ""), b.optString("name", ""));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ModouUi.dp(ShelfActivity.this, 44), ModouUi.dp(ShelfActivity.this, 60));
                clp.rightMargin = ModouUi.dp(ShelfActivity.this, 12);
                card.addView(cf, clp);

                LinearLayout info = new LinearLayout(ShelfActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                TextView t = ModouUi.tv(ShelfActivity.this, b.optString("name", ""), 14, ui.text);
                t.setTypeface(Typeface.SERIF, Typeface.BOLD);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                info.addView(t);
                int idx = p == null ? 0 : p.optInt("index", 0);
                String sub = "读到第 " + (idx + 1) + " 章";
                TextView s = ModouUi.tv(ShelfActivity.this, sub, 11.5f, ui.sub);
                s.setSingleLine(true);
                s.setEllipsize(TextUtils.TruncateAt.END);
                s.setPadding(0, ModouUi.dp(ShelfActivity.this, 4), 0, 0);
                info.addView(s);
                card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView time = ModouUi.tv(ShelfActivity.this,
                        relTime(p == null ? 0 : p.optLong("updatedAt", 0)), 11, ui.sub);
                time.setSingleLine(true);
                time.setPadding(ModouUi.dp(ShelfActivity.this, 8), 0, 0, 0);
                card.addView(time);

                outer.addView(card);
                return outer;
            }
        });
        // 历史列表与书卡同语义：单击直接续读，长按进详情
        lv.setOnItemClickListener((a, v, i, id) -> {
            JSONObject b = rows.get(i);
            JSONObject p = prog.get(b.optString("id", ""));
            ReaderLaunch.open(ShelfActivity.this, ModouBridge.shared(), b, p == null ? 0 : p.optInt("index", 0));
        });
        lv.setOnItemLongClickListener((a, v, i, id) -> {
            openBook(rows.get(i));
            return true;
        });
        box.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        lastScroller = lv;
        lastScrollerKey = paneKey();
        return box;
    }

    /** 本月阅读统计条：原先是书架顶部的一条横幅，占书架的书区，现在只出现在阅读历史页 */
    private View buildMonthStat() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bgr = new android.graphics.drawable.GradientDrawable();
        bgr.setColor(ui.card);
        bgr.setCornerRadius(ModouUi.dp(this, 14));
        banner.setBackground(bgr);
        banner.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 9),
                ModouUi.dp(this, 12), ModouUi.dp(this, 9));

        TextView bico = ModouUi.tv(this, "时", 13, ui.accent);
        bico.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable ibg = new android.graphics.drawable.GradientDrawable();
        ibg.setColor(ModouUi.alpha(ui.accent, 0x22));
        ibg.setCornerRadius(ModouUi.dp(this, 11));
        bico.setBackground(ibg);
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
        gv.setNumColumns(3);
        gv.setHorizontalSpacing(ModouUi.dp(this, 8));
        gv.setVerticalSpacing(ModouUi.dp(this, 12));
        gv.setBackgroundColor(Color.TRANSPARENT);
        gv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int coverW = (screenW - ModouUi.dp(this, 24) - ModouUi.dp(this, 16)) / 3;
        final int coverH = (int) (coverW * 1.35f);

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
                return cardView(data.get(i), coverH);
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

    /**
     * 按分组渲染书架：每个分组一块「标题（分组名 · 组内计数）+ 三列书卡」。
     * 滚动时当前分组的标题吸顶（SectionScroll 的 pinned 头部），
     * 滚到下一个分组时被新标题顶走——和网页版的分组书架一致。
     */
    private View buildGrouped(List<JSONObject> inGroup, String emptyText) {
        if (inGroup.isEmpty()) return ModouUi.empty(this, emptyText);

        FrameLayout pane = new FrameLayout(this);

        SectionScroll scroll = new SectionScroll(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int coverW = (screenW - ModouUi.dp(this, 24) - ModouUi.dp(this, 16)) / 3;
        final int coverH = (int) (coverW * 1.35f);

        List<String> ordered = orderedGroups(inGroup);
        for (String g : ordered) {
            List<JSONObject> sec = new ArrayList<>();
            for (JSONObject b : inGroup) {
                if (g.equals(b.optString("group", DEF_GROUP))) sec.add(b);
            }
            if (sec.isEmpty()) continue;
            String label = g + "  ·  " + sec.size() + " 本";
            TextView h = sectionHeader(label);
            content.addView(h);
            scroll.headers.add(h);
            scroll.headerText.add(label);
            for (int i = 0; i < sec.size(); i += 3) {
                int e = Math.min(i + 3, sec.size());
                content.addView(rowOfCards(sec.subList(i, e), coverH));
            }
        }

        pane.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 吸顶头部：盖在滚动区上方，滚到分组边界时显示当前分组名
        TextView pinned = sectionHeader("");
        pinned.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP;
        pane.addView(pinned, plp);
        scroll.pinned = pinned;
        lastScroller = scroll;
        lastScrollerKey = paneKey();
        return pane;
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

    /** 一行最多三张书卡，不足三张时用空白占位保持对齐 */
    private View rowOfCards(List<JSONObject> three, int coverH) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int gap = ModouUi.dp(this, 8);
        for (int i = 0; i < 3; i++) {
            View cell = i < three.size() ? cardView(three.get(i), coverH) : new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = gap;
            row.addView(cell, lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = ModouUi.dp(this, 12);
        row.setLayoutParams(rlp);
        return row;
    }

    /** 分组标题：实心底（吸顶时盖住下方书卡）+ 强调色粗体 */
    private TextView sectionHeader(String text) {
        TextView h = ModouUi.tv(this, text, 13, ui.accent);
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        h.setPadding(ModouUi.dp(this, 4), ModouUi.dp(this, 10),
                ModouUi.dp(this, 4), ModouUi.dp(this, 10));
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
    private View cardView(JSONObject b, int coverH) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        // 点击语义与网页版一致：单击 = 直接进阅读（从进度处续读），长按 = 书籍详情。
        // 之前单击开详情、续读藏在「⋯→继续阅读」里，与直觉相反。
        // 按压反馈用有界 ripple（圆角矩形 mask）：无界 ripple 会从按压点扩散出
        // 一个盖过整屏的浅色大圆，压在相邻封面上非常突兀
        android.graphics.drawable.GradientDrawable cardMask = new android.graphics.drawable.GradientDrawable();
        cardMask.setCornerRadius(ModouUi.dp(this, 10));
        cardMask.setColor(0xFFFFFFFF);
        android.graphics.drawable.RippleDrawable rip = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ModouUi.alpha(ui.text, 0x1E)),
                null, cardMask);
        l.setBackground(rip);
        l.setOnClickListener(v -> {
            JSONObject p = prog.get(b.optString("id", ""));
            int ci = p == null ? 0 : p.optInt("index", 0);
            ReaderLaunch.open(ShelfActivity.this, ModouBridge.shared(), b, ci);
        });
        l.setLongClickable(true);
        l.setOnLongClickListener(v -> {
            // 手动排序模式下长按 = 调整位置；其余模式长按 = 详情
            if ("manual".equals(sort)) showManualSortMenu(b);
            else openBook(b);
            return true;
        });

        FrameLayout cover = new FrameLayout(this);
        cover.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, coverH));
        // 封面即卡片：圆角 10dp + 轻阴影，和网页版 .bc-cover 一致
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setColor(0xFFD8CFBB);
        cbg.setCornerRadius(ModouUi.dp(this, 10));
        cover.setBackground(cbg);
        cover.setClipToOutline(true);
        cover.setElevation(ModouUi.dp(this, 2));

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        CoverLoader.load(iv, b.optString("coverUrl", ""), b.optString("name", ""));

        boolean local = "local://".equals(b.optString("sourceUrl", ""));
        TextView badge = ModouUi.tv(this, local ? "完结" : "连载", 9.5f, 0xFFFFFFFF);
        badge.setTypeface(badge.getTypeface(), Typeface.BOLD);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(local ? 0xFF6B8E5A : ui.accent);
        // 左圆右平，贴着封面右边缘（网页版 .bbadge 的胶囊造型）
        float r = ModouUi.dp(this, 999);
        bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        badge.setBackground(bg);
        badge.setPadding(ModouUi.dp(this, 6), ModouUi.dp(this, 3),
                ModouUi.dp(this, 6), ModouUi.dp(this, 3));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.TOP | Gravity.END;
        bp.topMargin = ModouUi.dp(this, 6);
        cover.addView(badge, bp);
        l.addView(cover);

        TextView name = ModouUi.tv(this, b.optString("name", ""), 12.5f, ui.text);
        name.setTypeface(Typeface.SERIF, Typeface.BOLD);
        // 固定两行高度：书名一行的卡与两行的卡如果各按内容撑高，
        // 下面「未读 N 章」这一行就会一高一低，整排看着像没对齐（用户看到的就是这个）。
        // minLines 让一行的书名也占满两行的高度，卡片高度因此完全一致。
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
        JSONObject p = prog.get(b.optString("id", ""));
        int total = b.optInt("chapterCount", 0);
        String pt;
        if (p == null) {
            pt = "未读过";
        } else if (total <= 0) {
            // 轻量记录还没补完章节数：别显示「已读完」这种明显不对的话
            pt = "读到第 " + (p.optInt("index", 0) + 1) + " 章";
        } else {
            pt = p.optInt("index", 0) >= total - 1
                    ? "已读完" : "未读 " + Math.max(0, total - p.optInt("index", 0) - 1) + " 章";
        }
        TextView pv = ModouUi.tv(this, pt, 10.5f, ui.sub);
        pv.setSingleLine(true);
        pv.setEllipsize(TextUtils.TruncateAt.END);
        foot.addView(pv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView more = ModouUi.tv(this, "⋯", 15, ui.sub);
        more.setGravity(Gravity.CENTER);
        int ms = ModouUi.dp(this, 26);
        more.setLayoutParams(new LinearLayout.LayoutParams(ms, ms));
        more.setOnClickListener((x) -> cardMenu(b));
        foot.addView(more);
        l.addView(foot);
        return l;
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
        CoverLoader.load(iv, b.optString("coverUrl", ""), b.optString("name", ""));
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
        search.setOnClickListener((x) -> pickCover(b.optString("name", ""), (url) -> {
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
        gv.setHorizontalSpacing(ModouUi.dp(this, 8));
        gv.setVerticalSpacing(ModouUi.dp(this, 8));
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

    private void pickCover(String name, CoverPick cb) {
        BookActivity.searchCovers(this, name, urls -> {
            if (urls.isEmpty()) {
                ModouUi.toast(ShelfActivity.this, "没搜到可用封面");
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
                        ? "本地书：原文件留在你选的文件夹里不动；勾选后连库内缓存的正文一起删掉，以后要读得重新导入。"
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
            store.del("books", id);
            store.del("progress", id);
            if (delFile[0]) {
                store.delChaptersOf(id);
                BookFiles.clearImages(ShelfActivity.this, id); // 正文清了，插图目录也别留着
            }
            ModouUi.toast(this, delFile[0] ? "已移出书架，缓存正文已删除" : "已移出书架");
            reload();
        }));
    }

    /**
     * 分组弹层：只负责「按哪个分组看」与分组增删。
     *
     * 排序原本也塞在这里，但排序不是筛选 —— 想换排序要先点一个名字对不上的「筛选」，
     * 这是用户直接指出的别扭处。现在排序独立成顶部入口（按钮上直接写当前排序方式），
     * 这个弹层只剩分组，标题就叫「分组」。
     */
    private void openGroupSheet() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog d = ModouUi.sheet(this, "分组", box);
        fillGroup(box);
        box.addView(ModouUi.sheetButtons(this, d, "新建分组", "完成", () -> newGroup(), null));
    }

    /** 重新铺一遍分组内容：改一项就地重画，不关弹层（关了再开会闪） */
    private void fillGroup(final LinearLayout box) {
        box.removeAllViews();

        // 标题行：左侧说明，右侧「管理 / 完成」
        LinearLayout gh = new LinearLayout(this);
        gh.setOrientation(LinearLayout.HORIZONTAL);
        gh.setGravity(Gravity.CENTER_VERTICAL);
        gh.setPadding(0, ModouUi.dp(this, 2), 0, ModouUi.dp(this, 8));
        TextView lbl = ModouUi.tv(this, "只显示某个分组里的书", 12.5f, ui.sub);
        gh.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mg = ModouUi.tv(this, filterManage ? "完成" : "管理", 13, ui.accent);
        mg.setPadding(ModouUi.dp(this, 8), ModouUi.dp(this, 2), 0, ModouUi.dp(this, 2));
        mg.setOnClickListener((v) -> {
            filterManage = !filterManage;
            fillGroup(box);
        });
        gh.addView(mg);
        box.addView(gh);

        // 分组可能很多：放一条横向滚动，不撑破弹层
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout gr = new LinearLayout(this);
        gr.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < groups.size(); i++) {
            final String g = groups.get(i);
            boolean locked = ALL.equals(g);
            String label = g;
            if (filterManage && !locked) label = g + "  ×";

            TextView c = chip(label, g.equals(group), () -> {
                if (filterManage && !locked) {
                    delGroup(g, () -> fillGroup(box)); // 管理模式：点一下就是删这一个
                } else {
                    group = g;
                    paintPane();
                    fillGroup(box);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = ModouUi.dp(this, 8);
            gr.addView(c, lp);
        }
        hs.addView(gr);
        box.addView(hs);

        if (filterManage) {
            TextView tip = ModouUi.tv(this, "管理模式：点分组即删除（「默认」不可删），组内书籍回到「默认」。",
                    11.5f, ui.sub);
            tip.setPadding(0, ModouUi.dp(this, 10), 0, 0);
            box.addView(tip);
        }
    }

    /** 顶部两个小按钮上的排序名 */
    private String sortLabel() {
        if ("added".equals(sort)) return "添加时间";
        if ("name".equals(sort)) return "书名";
        if ("manual".equals(sort)) return "手动排序";
        return "最近阅读";
    }

    /** 分组按钮文案：「全部」显示成「全部分组」，过长分组名截断 */
    private String groupShort() {
        if (ALL.equals(group)) return "全部分组";
        if (group.length() <= 5) return group;
        return group.substring(0, 4) + "…";
    }

    /** 顶部小按钮：卡片底 + 1dp 描边 + 胶囊圆角，与书架卡片同一套语言 */
    private TextView pill(String s) {
        TextView t = ModouUi.tv(this, s, 12.5f, ui.sub);
        t.setSingleLine(true);
        t.setPadding(ModouUi.dp(this, 12), ModouUi.dp(this, 6),
                ModouUi.dp(this, 12), ModouUi.dp(this, 6));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(ui.card);
        g.setCornerRadius(ModouUi.dp(this, 999));
        g.setStroke(Math.max(1, ModouUi.dp(this, 1)), ui.line);
        t.setBackground(g);
        return t;
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
