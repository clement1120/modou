package cn.modou.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「本地书籍」页：选定一个存放书籍的文件夹，以后打开就只看这个文件夹里的书。
 *
 * 以前是每次导入都全机扫一遍 MediaStore：书一多就慢，扫出来的还混着「下载」里的
 * 缓存文本，而且已导入的书默认不勾选 —— 全导完之后再点「导入」就什么都不做，
 * 看着像「导入没反应」。
 *
 * 现在改成：**一次选定文件夹**（SAF 目录树授权，可持续），之后进来只列这一个文件夹
 * 里的 .txt / .epub：没导入的右边一个「＋」，点一下就导；导入过的不再显示加号。
 * 不再全机扫描，也不再在应用私有目录另存一份原文件副本（那会让每本书占两份空间）。
 */
public class LocalBooksActivity extends Activity {

    static final String PREF_URI = "localBooksUri";
    static final String PREF_PLACE = "localBooksPlace";
    static final String PREF_SORT = "localBooksSort";
    /** 上次导入失败的原因（按文件 Uri 记），重进页面也还看得见 */
    static final String PREF_ERRS = "localImportErrs";
    private static final String PREFS = "modou";
    static final int REQ_TREE = 4801;
    /** 系统没有目录选择器时退回单文件选择 */
    private static final int REQ_FILE = 4802;

    private ModouUi ui;
    private ModouStore store;

    private TextView place;      // 副标题：当前文件夹
    private EditText searchBox;
    private LinearLayout searchRow;
    private ListView list;
    private View emptyBox;
    /** 0.9.177：顶部「N 本缺正文，一键补齐」条（重装 / 换机后才会出现） */
    private LinearLayout repairBar;
    private TextView repairText;

    private final List<Row> rows = new ArrayList<>();
    /**
     * 当前列表实际显示的行（先按搜索词过滤 rows 的结果）。
     * 单独留一份是为了让 ListView 的 Adapter 只建一次、之后只 notifyDataSetChanged——
     * 每次重扫/导入都 new 一个新 Adapter 会让 ListView 把滚动位置重置回顶部。
     */
    private final List<Row> shown = new ArrayList<>();
    private BaseAdapter adapter;
    private String sortKey = "name_asc";
    private String query = "";
    private Uri tree;

    /** 多选模式：长按任意一本进入，此时点击行 = 勾选，不再直接导入 */
    private boolean selecting;
    private LinearLayout selectBar;
    private TextView selAll;
    private TextView selGo;
    private TextView selInfo;
    /** 正在导入的队列进度（多选批量导入时用） */
    private int queueTotal;
    private int queueDone;
    private int queueOk;
    /** 已入队还没结束的任务数：中途又点一本也能正确计数 */
    private int queuePending;
    /** 这一批的失败原因（去重后拼一句，汇总时一起说） */
    private final List<String> queueErrs = new ArrayList<>();

    /** 失败提示色：浅色底用深红，深色底用浅红，保证两边都看得清 */

    /* ---------------- 文件夹存档 ---------------- */

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 已经选过文件夹吗（决定书架的「导入」按钮直接进哪一页） */
    static boolean hasFolder(Context c) {
        String s = prefs(c).getString(PREF_URI, "");
        return !TextUtils.isEmpty(s);
    }

    static String savedUri(Context c) {
        return prefs(c).getString(PREF_URI, "");
    }

    static void saveFolder(Context c, Uri uri, String place) {
        String old = prefs(c).getString(PREF_URI, "");
        if (!TextUtils.isEmpty(old) && !old.equals(uri.toString())) dropOldPermission(c, old);
        try {
            c.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignore) {
            /* 部分 ROM 不给长期授权：本次能用就行，下次再选 */
        }
        prefs(c).edit().putString(PREF_URI, uri.toString())
                .putString(PREF_PLACE, place).apply();
        BookFiles.savePlace(c, place);
    }

    /** 打开系统目录选择器；不支持的机型返回 false（调用方退回单文件选择） */
    static boolean pickFolder(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            a.startActivityForResult(i, REQ_TREE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 目录选择结果（ImportActivity 与本页面共用同一个处理） */
    static boolean handleResult(Activity a, int req, int res, Intent data) {
        if (req != REQ_TREE || res != Activity.RESULT_OK || data == null) return false;
        Uri u = data.getData();
        if (u == null) return false;
        String place = BookFiles.displayOfTree(u.toString());
        if (place.isEmpty()) {
            try {
                // tree URI 的文档 id 形如「primary:Download/books」，取最后一段当名字
                String id = DocumentsContract.getTreeDocumentId(u);
                if (id != null) {
                    int k = id.lastIndexOf('/');
                    place = k >= 0 ? id.substring(k + 1) : id;
                    int c = place.indexOf(':');
                    if (c >= 0) place = place.substring(c + 1);
                }
            } catch (Exception ignore) {
                /* 拿不到就用下面的兜底 */
            }
        }
        saveFolder(a, u, place.isEmpty() ? "所选文件夹" : place);
        return true;
    }

    static void launch(Activity from) {
        ModouTransition.push(from, new Intent(from, LocalBooksActivity.class));
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
        ModouUi.applyTheme(this);
        super.onCreate(savedInstanceState);
        ui = ModouUi.of(this);
        store = ModouStore.get(this);
        sortKey = prefs(this).getString(PREF_SORT, "name_asc");
        String u = savedUri(this);
        if (!TextUtils.isEmpty(u)) {
            try {
                tree = Uri.parse(u);
            } catch (Exception ignore) {
                tree = null;
            }
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        int p = ModouUi.dp(this, 14);
        int e = ModouUi.topInset(this);
        // 左右用全站顶栏标准：原来 14dp 比正文行还靠外一点点，圆钮看着发飘
        root.setPadding(e, p + statusBarPx(), e, p);

        root.addView(buildHead());

        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setVisibility(View.GONE);
        searchBox = buildSearchField();
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = String.valueOf(s).trim();
                paintList();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        // 墨读仍按内容撑高；iOS / M3 的搜索框是固定高度的控件（36dp / 56dp），
        // 由 searchBarHDp 给出，不能再 wrap_content 否则灰底会被文字顶成一条瘦条。
        int sbH = ui.spec.ioStyle == 0 ? ViewGroup.LayoutParams.WRAP_CONTENT
                : ModouUi.dp(this, ui.spec.searchBarHDp);
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, sbH, 1f));
        TextView clear = ModouUi.tv(this, "清除", ui.spec.ioStyle == 2 ? 14 : 13,
                ui.spec.ioStyle == 1 ? ui.spec.sysBlue : ui.accent);
        clear.setPadding(ModouUi.dp(this, 10), 0, ModouUi.dp(this, 2), 0);
        clear.setOnClickListener((v) -> {
            searchBox.setText("");
            searchRow.setVisibility(View.GONE);
            query = "";
            paintList();
        });
        searchRow.addView(clear);
        if (ui.spec.ioStyle != 0) {
            // iOS / M3 的搜索框与大标题之间要有呼吸：上下各 6dp，不再紧贴副标题
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = ModouUi.dp(this, 6);
            rlp.bottomMargin = ModouUi.dp(this, 6);
            root.addView(searchRow, rlp);
        } else {
            root.addView(searchRow);
        }

        emptyBox = buildEmpty();
        root.addView(emptyBox);

        repairBar = buildRepairBar();
        root.addView(repairBar);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        selectBar = buildSelectBar();
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (ui.spec.localSelBarStyle == 1) {
            // iOS 工具栏贴底通栏：左右不留边，只压一条顶部 0.5px 分隔线
            selectBar.setBackground(new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{
                            new android.graphics.drawable.ColorDrawable(ui.card),
                            new android.graphics.drawable.InsetDrawable(
                                    new android.graphics.drawable.ColorDrawable(ui.spec.divider),
                                    0, 0, 0, 0)}));
        } else if (ui.spec.localSelBarStyle == 2) {
            sblp.leftMargin = ModouUi.dp(this, 8);
            sblp.rightMargin = ModouUi.dp(this, 8);
            sblp.bottomMargin = ModouUi.dp(this, 8);
        }
        root.addView(selectBar, sblp);

        setContentView(root);
        ModouUi.enter(root);
        load();
    }

    /** 底部多选条：长按进入后才出现（全选 / 已选 N 本 · 导入） */
    private LinearLayout buildSelectBar() {
        DesignSpec sp = ui.spec;
        if (sp.localSelBarStyle == 1) return buildSelectToolbar(sp, true);
        if (sp.localSelBarStyle == 2) return buildSelectToolbar(sp, false);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ModouUi.dp(this, 6), ModouUi.dp(this, 8),
                ModouUi.dp(this, 8), ModouUi.dp(this, 8));
        bar.setBackground(ModouUi.round(ui.card, 14));
        bar.setVisibility(View.GONE);

        selAll = ModouUi.tv(this, "全选", 13.5f, ui.accent);
        selAll.setGravity(Gravity.CENTER);
        selAll.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 10),
                ModouUi.dp(this, 14), ModouUi.dp(this, 10));
        selAll.setOnClickListener((v) -> toggleAll());
        bar.addView(selAll);

        selInfo = ModouUi.tv(this, "", 12.5f, ui.sub);
        selInfo.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
        bar.addView(selInfo, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cancel = ModouUi.tv(this, "取消", 13.5f, ui.sub);
        cancel.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 10),
                ModouUi.dp(this, 10), ModouUi.dp(this, 10));
        cancel.setOnClickListener((v) -> exitSelect());
        bar.addView(cancel);

        selGo = ModouUi.tv(this, "导入", 14, 0xFFFFFFFF);
        selGo.setGravity(Gravity.CENTER);
        selGo.setPadding(ModouUi.dp(this, 20), ModouUi.dp(this, 10),
                ModouUi.dp(this, 20), ModouUi.dp(this, 10));
        selGo.setBackground(ModouUi.round(ui.accent, 999));
        selGo.setOnClickListener((v) -> importPicked());
        bar.addView(selGo);
        return bar;
    }

    /**
     * iOS 26 工具条 / M3 底部操作条。
     *
     * <p>iOS：49dp 贴底工具栏（白卡 + 顶部 0.5px 分隔线），左侧蓝色「全选 / 全不选」、
     * 中间 13sp 次级计数、右侧蓝色粗体「导入 N 本」，没有按钮底色 —— 这就是
     * UIToolbar 的写法。M3：surfaceContainer 圆角底栏（8dp 内缩 + 12dp 圆角），
     * 「全选」文本按钮 + 计数 + Filled 主按钮（40dp 全圆）。
     */
    private LinearLayout buildSelectToolbar(DesignSpec sp, boolean ios) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setVisibility(View.GONE);
        if (ios) {
            bar.setMinimumHeight(ModouUi.dp(this, 49));
            bar.setPadding(ModouUi.dp(this, 16), ModouUi.dp(this, 6),
                    ModouUi.dp(this, 16), ModouUi.dp(this, 6));
            bar.setBackgroundColor(ui.card);
        } else {
            bar.setMinimumHeight(ModouUi.dp(this, 56));
            bar.setPadding(ModouUi.dp(this, 8), ModouUi.dp(this, 8),
                    ModouUi.dp(this, 8), ModouUi.dp(this, 8));
            bar.setBackground(ModouUi.squircle(this, sp.surfaceContainer, 12));
        }

        selAll = ios
                ? ModouUi.tv(this, "全选", 17, sp.sysBlue)
                : ModouUi.button(this, "全选", DesignSpec.ButtonKind.TEXT,
                        ModouUi.CAPSULE, v -> toggleAll());
        if (ios) {
            selAll.setGravity(Gravity.CENTER_VERTICAL);
            selAll.setPadding(0, ModouUi.dp(this, 10), ModouUi.dp(this, 14), ModouUi.dp(this, 10));
            selAll.setOnClickListener((v) -> toggleAll());
        }
        bar.addView(selAll);

        selInfo = ModouUi.tv(this, "", ios ? 13 : 13, ui.sub);
        selInfo.setGravity(ios ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        bar.addView(selInfo, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (ios) {
            selGo = ModouUi.tv(this, "导入", 17, sp.sysBlue);
            selGo.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            selGo.setGravity(Gravity.CENTER_VERTICAL);
            selGo.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 10), 0, ModouUi.dp(this, 10));
            selGo.setOnClickListener((v) -> importPicked());
            bar.addView(selGo);
        } else {
            selGo = ModouUi.button(this, "导入", DesignSpec.ButtonKind.FILLED,
                    ModouUi.CAPSULE, v -> importPicked());
            bar.addView(selGo);
        }
        return bar;
    }

    private int statusBarPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : ModouUi.dp(this, 24);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ModouUi.syncTheme(this)) return;
        // 从别处回来可能刚导入过：就地重判一次「已导入」，不必重读文件夹
        refreshStates();
        if (selecting) updateSelBar();
    }

    /** 多选模式下返回键先退出多选，不要直接退出页面（会误触丢掉勾选） */
    @Override
    public void onBackPressed() {
        if (selecting) {
            exitSelect();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (handleResult(this, req, res, data)) {
            tree = Uri.parse(savedUri(this));
            load();
            return;
        }
        if (req == REQ_FILE && res == Activity.RESULT_OK && data != null
                && data.getData() != null) {
            Uri u = data.getData();
            // 长久读写权限：删除原文件 / 编辑后写回都需要写权限。
            // 以前这里只申请了读权限，deleteDocument 必然抛 SecurityException，
            // 表现就是「勾了删除，文件还在」。
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignore) {
                /* 有些 provider 不给长期授权：本次能用就行 */
            }
            importUri(u, guessName(u), 0);
        }
    }

    /* ---------------- 头部 ---------------- */

    private View buildHead() {
        if (ui.spec.localHeadAction != 0) return buildNavHead(ui.spec);
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // 整行统一 46dp 行高：返回、标题、三个胶囊全部垂直居中对齐。
        // 之前三者各自 wrap_content，返回 15sp、标题 20sp 衬线、按钮 12sp，
        // 同一行里三种字号三种基线，看着「高低都不对」。
        top.setMinimumHeight(ModouUi.dp(this, 46));

        // 左上纯圆图标返回（无文字）：与全站顶栏同一颗 40dp 正圆 + 细线箭头
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ModouUi.dp(this, 40), ModouUi.dp(this, 40));
        blp.rightMargin = ModouUi.dp(this, 8);
        top.addView(ModouUi.backBtn(this, () -> finish()), blp);

        TextView title = ModouUi.tv(this, "本地书籍", 21, ui.text);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.01f);
        title.setIncludeFontPadding(false);
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        top.addView(headBtn("搜索", (v) -> toggleSearch()));
        top.addView(headBtn("文件夹", (v) -> pickFolderOrFile()));
        top.addView(headBtn("排序", (v) -> chooseSort()));
        h.addView(top);

        // 副标题行：左「当前文件夹 · N 本」、右「刷新」。
        // 刷新放在这一行而不是顶栏：顶栏已经有 返回/搜索/文件夹/排序 四个控件，窄屏再加会挤没标题。
        LinearLayout placeRow = new LinearLayout(this);
        placeRow.setOrientation(LinearLayout.HORIZONTAL);
        placeRow.setGravity(Gravity.CENTER_VERTICAL);

        place = ModouUi.tv(this, "", 12, ui.sub);
        place.setSingleLine(true);
        place.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        place.setPadding(0, ModouUi.dp(this, 4), 0, ModouUi.dp(this, 6));
        placeRow.addView(place, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 外部往文件夹里加了新文件？点一下重新扫描即可（SAF 不会自动通知内容变化）
        TextView refresh = ModouUi.tv(this, "刷新", 12, ui.accent);
        refresh.setPadding(ModouUi.dp(this, 12), ModouUi.dp(this, 4), 0, ModouUi.dp(this, 6));
        refresh.setOnClickListener(v -> {
            ModouUi.toast(this, "重新扫描文件夹…");
            load();
        });
        placeRow.addView(refresh);
        h.addView(placeRow);
        return h;
    }

    private TextView headBtn(String s, View.OnClickListener cb) {
        // 三个入口 + 返回 + 标题要挤在一行里：12.5sp / 左右 9dp / 固定 30dp 高。
        // 固定高度是关键 —— 三个按钮高度一样、和标题同一条中轴，才不会一高一低。
        TextView t = ModouUi.tv(this, s, 12.5f, ModouUi.alpha(ui.text, 0xD6));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setPadding(ModouUi.dp(this, 9), 0, ModouUi.dp(this, 9), 0);
        t.setBackground(ModouUi.glass(this, 999));
        t.setOnClickListener(cb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ModouUi.dp(this, 30));
        lp.leftMargin = ModouUi.dp(this, 6);
        t.setLayoutParams(lp);
        // 0.9.201：30dp 视觉高不动，触控区补足 48dp，避免顶栏小按钮误触。
        ModouUi.expandTouch(t, 48);
        return t;
    }

    /* ================= 0.9.172 · 三语言顶栏（Stage 4 第 10 部分） =================

       此前这页的顶栏对三套语言是同一张皮：40dp 白圆返回 + 21sp 粗标题 +
       三颗玻璃文字胶囊。iOS 26 的导航栏是「细箭头 + 居中 17sp 标题 + 蓝色文字动作」，
       下面再压一条 34sp Large Title；M3 是 64dp TopAppBar（22sp Medium 标题 +
       40dp 图标钮 + 状态层）。这里按 spec 出图，墨读分支原样保留（ioStyle=0 不进本方法）。 */

    private View buildNavHead(DesignSpec sp) {
        final boolean ios = sp.localHeadAction == 1;
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setMinimumHeight(ModouUi.dp(this, sp.localHeadHDp));

        if (ios) {
            // 左 64dp 槽位（返回）+ 中 权重标题 + 右 64dp 槽位（排序）：三段定宽，
            // 标题才是真正居中在整条栏里，而不是被右侧按钮挤偏。
            top.addView(navSlot(64, true,
                    navGlyph(ModouGlyph.BACK, 44, 22f, 2.1f, sp.sysBlue, v -> finish())));
        } else {
            // M3：48dp 触控区 + 24dp 箭头（官方 icon button 的 48dp 最小触控）
            top.addView(navGlyph(ModouGlyph.BACK, 48, 24f, 2.0f, ui.text, v -> finish()));
        }

        TextView title = ModouUi.tv(this, "本地书籍", sp.localNavTitleSp, ui.text);
        applyNavTitleFace(title, sp);
        title.setSingleLine(true);
        title.setGravity(ios ? Gravity.CENTER : (Gravity.START | Gravity.CENTER_VERTICAL));
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (!ios) tlp.leftMargin = ModouUi.dp(this, 4);
        top.addView(title, tlp);

        if (ios) {
            TextView sort = ModouUi.tv(this, "排序", 17, sp.sysBlue);
            sort.setGravity(Gravity.CENTER_VERTICAL);
            sort.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
            sort.setOnClickListener(v -> chooseSort());
            top.addView(navSlot(64, false, sort));
        } else {
            top.addView(navVector(R.drawable.ic_sh_search, 40, v -> toggleSearch()));
            top.addView(navVector(R.drawable.ic_ls_folder, 40, v -> pickFolderOrFile()));
            top.addView(navVector(R.drawable.ic_ls_sort, 40, v -> chooseSort()));
        }
        h.addView(top);

        // iOS Large Title：34sp Bold + 右侧两颗蓝色图标（搜索 / 文件夹）。
        // 这是 iOS「大标题 + 行内动作」的标准构图，也是三套语言里最容易被认出来的一屏。
        if (sp.localLargeTitle) {
            LinearLayout lt = new LinearLayout(this);
            lt.setOrientation(LinearLayout.HORIZONTAL);
            lt.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
            llp.topMargin = ModouUi.dp(this, 2);
            TextView big = ModouUi.tv(this, "本地书籍", sp.localLargeTitleSp, ui.text);
            big.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            big.setLetterSpacing(-0.02f);
            big.setIncludeFontPadding(false);
            lt.addView(big, new LinearLayout.LayoutParams(0, -2, 1f));
            lt.addView(navVector(R.drawable.ic_sh_search, 44, v -> toggleSearch()));
            lt.addView(navVector(R.drawable.ic_ls_folder, 44, v -> pickFolderOrFile()));
            h.addView(lt, llp);
        }

        // 副标题行：左「当前文件夹 · N 本」、右「刷新」（iOS/M3 都走 TextButton，
        // 颜色与字重由 button() 按语言给：iOS 系统蓝 17sp / M3 accent 14sp medium）。
        LinearLayout placeRow = new LinearLayout(this);
        placeRow.setOrientation(LinearLayout.HORIZONTAL);
        placeRow.setGravity(Gravity.CENTER_VERTICAL);

        place = ModouUi.tv(this, "", sp.localSubSp, ui.sub);
        place.setSingleLine(true);
        place.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        place.setPadding(0, ModouUi.dp(this, 2), 0, ModouUi.dp(this, ios ? 6 : 4));
        placeRow.addView(place, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = ModouUi.button(this, "刷新", DesignSpec.ButtonKind.TEXT,
                ModouUi.CAPSULE, v -> {
                    ModouUi.toast(this, "重新扫描文件夹…");
                    load();
                });
        placeRow.addView(refresh);
        h.addView(placeRow);
        return h;
    }

    /** 顶栏标题字重：iOS 17sp semibold（用 bold 近似）/ M3 22sp Medium */
    private void applyNavTitleFace(TextView t, DesignSpec sp) {
        if (sp.localNavTitleWeight == 2) {
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        } else if (sp.localNavTitleWeight == 1) {
            t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        }
        t.setLetterSpacing(sp.localHeadAction == 1 ? -0.01f : 0f);
    }

    /** 左右定宽槽位：让 iOS 居中标题真的居中（返回钮在左、排序在右） */
    private LinearLayout navSlot(int wDp, boolean start, View child) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity((start ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
        box.addView(child);
        box.setLayoutParams(new LinearLayout.LayoutParams(ModouUi.dp(this, wDp), -1));
        return box;
    }

    /** 顶栏细线图标钮（有 ModouGlyph 的：返回 / 搜索） */
    private ImageView navGlyph(int glyph, int boxDp, float glyphDp, float strokeDp,
                              int color, View.OnClickListener cb) {
        ImageView iv = new ImageView(this);
        int b = ModouUi.dp(this, boxDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(b, b));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageDrawable(new ModouGlyph(glyph, color,
                ModouUi.dp(this, glyphDp), ModouUi.dp(this, strokeDp)));
        iv.setBackground(rippleCircle(b));
        iv.setOnClickListener(cb);
        iv.setClickable(true);
        iv.setFocusable(true);
        return iv;
    }

    /** 顶栏矢量图标钮（文件夹 / 排序：没有程序化 glyph，走 24dp 矢量 + tint） */
    private ImageView navVector(int resId, int boxDp, View.OnClickListener cb) {
        ImageView iv = new ImageView(this);
        int b = ModouUi.dp(this, boxDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(b, b));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageResource(resId);
        iv.setColorFilter(ui.spec.localHeadAction == 1 ? ui.spec.sysBlue : ui.sub);
        iv.setBackground(rippleCircle(b));
        iv.setOnClickListener(cb);
        iv.setClickable(true);
        iv.setFocusable(true);
        return iv;
    }

    /** 圆形状态层（图标钮的按压反馈）：墨读玻璃质感在这条路径上不参与 */
    private android.graphics.drawable.Drawable rippleCircle(int sizePx) {
        android.graphics.drawable.GradientDrawable mask =
                new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(sizePx / 2f);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        ui.dark ? 0x26FFFFFF : 0x14000000), null, mask);
    }

    private void toggleSearch() {
        boolean on = searchRow.getVisibility() == View.VISIBLE;
        searchRow.setVisibility(on ? View.GONE : View.VISIBLE);
        if (!on) searchBox.requestFocus();
    }

    private void pickFolderOrFile() {
        if (!pickFolder(this)) pickFile();
    }

    /**
     * 搜索框：墨读走 {@link ModouUi#input}（玻璃胶囊原样）；iOS / M3 走各自的
     * search field 背景（{@link ModouUi#searchBarBg}），高度与字号读 {@code search*}
     * —— 与搜索页共用同一组规格，改一处两页一起变。
     */
    private EditText buildSearchField() {
        DesignSpec sp = ui.spec;
        if (sp.ioStyle == 0) return ModouUi.input(this, "搜索书名", "", 1);
        EditText e = new EditText(this);
        e.setHint("搜索书名");
        e.setSingleLine(true);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp.searchBarTextSp);
        e.setTextColor(ui.text);
        e.setHintTextColor(ui.sub);
        e.setBackground(ModouUi.searchBarBg(this));
        int ph = ModouUi.dp(this, sp.searchBarStyle == 2 ? 20 : 12);
        e.setPadding(ph, 0, ph, 0);
        return e;
    }

    /* ---------------- 数据 ---------------- */

    static final class Row {
        String name = "";
        long size;
        long mtime;
        Uri uri;
        boolean imported;
        boolean busy;
        boolean picked;
        /** 上次导入失败的原因：留在行上，别只用 toast 一闪而过 */
        String err = "";
        /** 0.9.177：这本书库里已有记录但正文为空（重装/换机后 SAF 授权失效）。
         *  非空时点击行走「补齐正文」——沿用原 id 重解析，阅读进度按 bookId 保留。 */
        String repairId = "";
    }

    private void load() {
        place.setText("读取中…");
        final Uri t = tree;
        new Thread(() -> {
            final List<Row> got = new ArrayList<>();
            String err = "";
            if (t == null) {
                err = "还没选择文件夹";
            } else {
                try {
                    String docId = DocumentsContract.getTreeDocumentId(t);
                    if (docId == null) err = "文件夹读不了了，请重新选择";
                    else collect(t, docId, got, 0);
                } catch (Exception e) {
                    err = "读取失败：" + (e.getMessage() == null ? "" : e.getMessage());
                }
            }
            List<String> urls = store.localBookUrls();
            Set<String> known = new HashSet<>(urls);
            Set<String> uniq = uniqueNames(urls);
            // 0.9.177：哪些本地书「有记录没正文」——重装 / 换机后 SAF 授权被撤销，
            // 备份只带回了目录与进度，正文要重新从原文件解析。
            // 结果按 bookUrl（名字#字节数）与纯文件名各存一份：字节数可能被 provider 报得不一样。
            java.util.Map<String, String> brokenByUrl = new HashMap<>();
            java.util.Map<String, String> brokenByName = new HashMap<>();
            for (String u : urls) {
                String bid = LocalImport.LOCAL + "||" + u;
                if (store.countChaptersOf(bid) > 0) continue;
                brokenByUrl.put(u, bid);
                int hash = u.lastIndexOf('#');
                String nm = hash > 0 ? u.substring(0, hash) : u;
                if (nm.startsWith(LocalImport.LOCAL)) nm = nm.substring(LocalImport.LOCAL.length());
                brokenByName.put(nm, bid);
            }
            for (Row r : got) {
                String url = LocalImport.urlOf(r.name, r.size);
                r.imported = known.contains(url) || uniq.contains(r.name);
                String rep = brokenByUrl.get(url);
                if (rep == null) rep = brokenByName.get(r.name);
                if (rep != null) r.repairId = rep;
            }
            JSONObject errs = errMap();
            for (Row r : got) {
                String u = String.valueOf(r.uri);
                if (errs.has(u)) r.err = errs.optString(u, "");
            }
            sortRows(got);
            final String e = err;
            runOnUiThread(() -> {
                rows.clear();
                rows.addAll(got);
                if (!e.isEmpty()) {
                    place.setText(e);
                } else {
                    place.setText(BookFiles.placeOf(this) + " · " + got.size() + " 本");
                }
                paintList();
            });
        }).start();
    }

    /** 目录 + 一层子目录：再深就是「作者/作品/卷」这种嵌套了，列出来反而乱 */
    private void collect(Uri tree, String docId, List<Row> out, int depth) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = getContentResolver().query(kids, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                long sz = c.isNull(2) ? 0 : c.getLong(2);
                long mt = c.isNull(3) ? 0 : c.getLong(3);
                String mime = c.getString(4);
                if (name == null || name.startsWith(".") || id == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 1) collect(tree, id, out, depth + 1);
                    continue;
                }
                String ext = LocalScan.extOf(name);
                if (!"txt".equals(ext) && !"epub".equals(ext)) continue;
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                // 有些 provider 不报大小：问一次描述符，否则「是否已导入」判不出来
                if (sz < 1024) sz = probeSize(uri);
                if (sz < 1024 || sz > LocalImport.MAX_BYTES) continue;
                Row r = new Row();
                r.name = name;
                r.size = sz;
                r.mtime = mt;
                r.uri = uri;
                out.add(r);
            }
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
    }

    private long probeSize(Uri u) {
        try (android.content.res.AssetFileDescriptor afd =
                     getContentResolver().openAssetFileDescriptor(u, "r")) {
            return afd == null ? 0 : afd.getLength();
        } catch (Exception e) {
            return 0;
        }
    }

    private void refreshStates() {
        if (rows.isEmpty()) return;
        List<String> urls = store.localBookUrls();
        Set<String> known = new HashSet<>(urls);
        Set<String> uniq = uniqueNames(urls);
        boolean changed = false;
        for (Row r : rows) {
            boolean now = known.contains(LocalImport.urlOf(r.name, r.size))
                    || uniq.contains(r.name);
            if (now != r.imported) changed = true;
            r.imported = now;
        }
        if (changed) paintList();
    }

    /**
     * 库里已有的文件名（只收「名字在库中唯一」的）。
     *
     * 为什么不能只按「名字 + 字节数」判：SAF 的 COLUMN_SIZE 并不总是真实字节数
     * （云盘、部分文件管理器会给 0 或压缩后的大小）。那样这本明明导进去了，
     * 列表上却永远显示「未导入」，用户点一次覆盖一次，看起来就是「点了没反应」。
     * 名字唯一时按名字认，能兜住这一类。
     */
    private static Set<String> uniqueNames(List<String> urls) {
        Map<String, Integer> cnt = new HashMap<>();
        for (String u : urls) {
            String n = nameOfUrl(u);
            cnt.put(n, (cnt.containsKey(n) ? cnt.get(n) : 0) + 1);
        }
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Integer> e : cnt.entrySet()) {
            if (e.getValue() == 1) out.add(e.getKey());
        }
        return out;
    }

    private static String nameOfUrl(String u) {
        String s = u == null ? "" : u;
        if (s.startsWith(LocalImport.LOCAL)) s = s.substring(LocalImport.LOCAL.length());
        int h = s.lastIndexOf('#');
        return h > 0 ? s.substring(0, h) : s;
    }

    private void sortRows(List<Row> xs) {
        Comparator<Row> c;
        switch (sortKey) {
            case "name_desc":
                c = (a, b) -> b.name.compareToIgnoreCase(a.name);
                break;
            case "size_desc":
                c = (a, b) -> Long.compare(b.size, a.size);
                break;
            case "size_asc":
                c = (a, b) -> Long.compare(a.size, b.size);
                break;
            case "time_desc":
                c = (a, b) -> Long.compare(b.mtime, a.mtime);
                break;
            default:
                c = (a, b) -> a.name.compareToIgnoreCase(b.name);
                break;
        }
        Collections.sort(xs, c);
    }

    private void chooseSort() {
        final String[] keys = {"name_asc", "name_desc", "size_desc", "size_asc", "time_desc"};
        final String[] names = {"名称 A→Z", "名称 Z→A", "大小 大→小", "大小 小→大", "时间 最近在前"};
        String[] labels = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            labels[i] = (keys[i].equals(sortKey) ? "✓ " : "") + names[i];
        }
        ModouUi.sheetList(this, "排序", labels, w -> {
            sortKey = keys[w];
            prefs(this).edit().putString(PREF_SORT, sortKey).apply();
            sortRows(rows);
            paintList();
        });
    }

    /* ---------------- 列表 ---------------- */

    private void paintList() {
        shown.clear();
        for (Row r : rows) {
            if (query.isEmpty() || r.name.toLowerCase(Locale.ROOT)
                    .contains(query.toLowerCase(Locale.ROOT))) shown.add(r);
        }
        boolean none = shown.isEmpty();
        emptyBox.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        // iOS 连续分组的分隔线：ListView 原生 divider + InsetDrawable，从文件图标之后起画，
        // 右侧到卡边。不改行结构、不在行里塞线，滚动复用也不会和 bindRow 打架。
        DesignSpec ls = ui.spec;
        if (ls.localRowStyle == 1) {
            list.setDivider(new android.graphics.drawable.InsetDrawable(
                    new android.graphics.drawable.ColorDrawable(ls.divider),
                    ModouUi.dp(this, ls.localDividerStartDp), 0,
                    ModouUi.dp(this, ls.localRowPadH), 0));
            list.setDividerHeight(Math.max(1, ModouUi.dp(this, 0.5f)));
        } else {
            list.setDivider(null);
            list.setDividerHeight(0);
        }
        // Adapter 只建一次：之后统一 notifyDataSetChanged。以前每次重绘都 new 一个 Adapter
        // 塞给 ListView，等于告诉它「换了一整份数据」，滚动位置随之被重置回顶部——
        // 用户连续导入几本，列表就一次次自己弹回顶上。
        updateRepairBar();
        if (adapter == null) {
            adapter = new BaseAdapter() {
                @Override
                public int getCount() {
                    return shown.size();
                }

                @Override
                public Object getItem(int i) {
                    return shown.get(i);
                }

                @Override
                public long getItemId(int i) {
                    return i;
                }

                @Override
                public View getView(int i, View cv, ViewGroup p) {
                    LocalHolder h = (cv != null && cv.getTag() instanceof LocalHolder)
                            ? (LocalHolder) cv.getTag() : newRow();
                    // 位置要传下去：iOS 连续分组靠「首行圆上角 / 末行圆下角」拼成一张卡
                    bindRow(h, shown.get(i), i, shown.size());
                    return h.wrap;
                }
            };
            list.setAdapter(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    /** 本地书行的 ViewHolder：固定 View 树，滚动时只改文本/显隐，不再每行 new 一套 */
    private static final class LocalHolder {
        LinearLayout wrap;
        LinearLayout row;
        TextView dot, name, sub, tag, add;
        android.graphics.drawable.GradientDrawable dotBg;
        Row cur;
        /** 上一次绑定的位置：iOS 连续分组的圆角只跟位置有关，位置没变就不重建 Drawable */
        int bgPos = -1;
    }

    private LocalHolder newRow() {
        final DesignSpec sp = ui.spec;
        final boolean modern = sp.localRowStyle != 0;
        final LocalHolder h = new LocalHolder();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (modern) {
            // iOS / M3：留白、最小行高全部来自规格（58dp 连续分组 / 64dp filled 卡）。
            // iOS 的卡底色与圆角依赖「第几行」，放在 bindRow 里按位置刷，这里不设死。
            row.setPadding(ModouUi.dp(this, sp.localRowPadH), ModouUi.dp(this, sp.localRowPadV),
                    ModouUi.dp(this, sp.localRowPadH), ModouUi.dp(this, sp.localRowPadV));
            if (sp.localRowMinHDp > 0) row.setMinimumHeight(ModouUi.dp(this, sp.localRowMinHDp));
            if (sp.localRowStyle == 2) {
                row.setBackground(ModouUi.squircle(this, sp.surfaceContainerLow, sp.localRowCornerDp));
            }
            // 按压反馈：iOS 连续分组 / M3 filled 卡的背景是按位置算的，不能换成
            // StateListDrawable，改用 foreground 状态层叠在上面。
            row.setForeground(ModouUi.pressOverlay(this, sp.localRowCornerDp));
        } else {
            row.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 10),
                    ModouUi.dp(this, 6), ModouUi.dp(this, 10));
            row.setBackground(ModouUi.round(ui.card, 12));
        }

        // 多选态勾选圈：固定创建，靠 setVisibility 切换，避免切模式时重建 View 树
        h.dot = ModouUi.tv(this, "", 13, ui.line);
        h.dot.setGravity(Gravity.CENTER);
        int dd = ModouUi.dp(this, modern && sp.localRowStyle == 2 ? 20 : 22);
        h.dotBg = new android.graphics.drawable.GradientDrawable();
        h.dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        h.dot.setBackground(h.dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dd, dd);
        dlp.rightMargin = ModouUi.dp(this, modern ? 12 : 10);
        row.addView(h.dot, dlp);

        // 行首文件图标：iOS 29dp 系统蓝圆角方块 + 白纸；M3 40dp tonal 圆 + accent 纸
        if (modern && sp.localIconStyle != 0) {
            int isz = ModouUi.dp(this, sp.localIconDp);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
            ilp.rightMargin = ModouUi.dp(this, sp.localIconStyle == 1 ? 12 : 14);
            row.addView(fileIcon(sp), ilp);
        }

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        h.name = ModouUi.tv(this, "", sp.localNameSp, ui.text);
        h.name.setSingleLine(true);
        h.name.setEllipsize(TextUtils.TruncateAt.END);
        if (sp.localNameWeight == 2) {
            h.name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        }
        txt.addView(h.name);
        h.sub = ModouUi.tv(this, "", modern && sp.localRowStyle == 1 ? 13 : 12, ui.sub);
        txt.addView(h.sub);
        row.addView(txt, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        h.tag = ModouUi.tv(this, "已导入", sp.localTagSp, ui.sub);
        if (modern) {
            // iOS：systemFill 胶囊；M3：8dp 描边 chip（与书详情的标签同一档）
            int padH = ModouUi.dp(this, sp.localTagStyle == 1 ? 8 : 7);
            h.tag.setPadding(padH, ModouUi.dp(this, sp.localTagStyle == 1 ? 4 : 5),
                    padH, ModouUi.dp(this, sp.localTagStyle == 1 ? 4 : 5));
            if (sp.localTagStyle == 1) {
                h.tag.setBackground(ModouUi.squircle(this, sp.sysFill2, 999));
                h.tag.setTextColor(ui.sub);
            } else {
                h.tag.setBackground(ModouUi.squircle(this, 0x00000000, 8, sp.outline, 1f));
                h.tag.setTextColor(ui.sub);
            }
        } else {
            h.tag.setPadding(ModouUi.dp(this, 8), ModouUi.dp(this, 4),
                    ModouUi.dp(this, 8), ModouUi.dp(this, 4));
        }
        row.addView(h.tag);

        h.add = ModouUi.tv(this, "＋", modern ? (sp.localAddStyle == 1 ? 25 : 20) : 18, ui.accent);
        h.add.setGravity(Gravity.CENTER);
        if (modern) {
            int d = ModouUi.dp(this, sp.localAddStyle == 1 ? 44 : 36);
            h.add.setLayoutParams(new LinearLayout.LayoutParams(d, d));
            if (sp.localAddStyle == 2) {
                h.add.setBackground(ModouUi.squircle(this, ModouUi.alpha(ui.accent, 0x1F), 999));
            } else if (sp.localAddStyle == 1) {
                h.add.setTextColor(sp.sysBlue);
                h.add.setBackground(rippleCircle(d));
            }
        } else {
            int d = ModouUi.dp(this, 34);
            h.add.setLayoutParams(new LinearLayout.LayoutParams(d, d));
            android.graphics.drawable.GradientDrawable addBg =
                    new android.graphics.drawable.GradientDrawable();
            addBg.setColor(0x00000000);
            addBg.setCornerRadius(d / 2f);
            addBg.setStroke(Math.max(1, ModouUi.dp(this, 1.5f)), ui.line);
            h.add.setBackground(addBg);
        }
        h.add.setOnClickListener(v -> {
            if (h.cur != null) importRow(h.cur);
        });
        row.addView(h.add);

        // 监听器读 h.cur（当前绑定的行），不能捕获创建时的 r：复用后位置会串
        row.setOnClickListener(v -> {
            Row r = h.cur;
            if (r == null) return;
            if (selecting) {
                if (r.imported) {
                    ModouUi.toast(this, "《" + strip(r.name) + "》已经在书架里了");
                    return;
                }
                r.picked = !r.picked;
                updateSelBar();
                paintList();
            } else if (r.imported && r.repairId.isEmpty()) {
                ModouUi.toast(this, "《" + strip(r.name) + "》已经在书架里了");
            } else {
                // 未导入，或「已导入但正文缺失」→ 导入 / 补齐
                importRow(r);
            }
        });
        // 长按任意一本进入多选：整本书太多时，一本本点「＋」太慢
        row.setOnLongClickListener(v -> {
            Row r = h.cur;
            if (r != null && !selecting) {
                selecting = true;
                for (Row x : rows) x.picked = false;
                r.picked = !r.imported;
                updateSelBar();
                paintList();
            }
            return true;
        });

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        // 0.9.210：列表项之间至少 8dp 纵向间距（用户反馈卡片像一整块砖头贴在一起）
        int gap = sp.localRowStyle == 1 ? 8 : Math.max(8, sp.localRowGapDp);
        wrap.setPadding(0, 0, 0, ModouUi.dp(this, gap));
        wrap.setTag(h);
        h.wrap = wrap;
        h.row = row;
        return h;
    }

    /** 行首文件块：iOS systemBlue 圆角方块 / M3 tonal 圆，图标都是同一枚 24dp 矢量 */
    private View fileIcon(DesignSpec sp) {
        FrameLayout box = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(ModouUi.dp(this, sp.localIconCornerDp));
        bg.setColor(sp.localIconStyle == 1 ? sp.sysBlue : ModouUi.alpha(ui.accent, 0x1F));
        box.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_ls_file);
        iv.setColorFilter(sp.localIconStyle == 1 ? 0xFFFFFFFF : ui.accent);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int g = ModouUi.dp(this, sp.localGlyphDp);
        box.addView(iv, new FrameLayout.LayoutParams(g, g, Gravity.CENTER));
        return box;
    }

    /**
     * iOS 连续分组：同一张白卡被行切开，首行圆上两角、末行圆下两角、中间直角。
     * 半径与圆角规格同源，M3 不需要这套（每行本来就是独立 filled card）。
     */
    private android.graphics.drawable.Drawable groupedRowBg(int pos, int count) {
        DesignSpec sp = ui.spec;
        float r = sp.localRowCornerDp;
        boolean first = pos <= 0;
        boolean last = pos >= count - 1;
        float tl = first ? r : 0f, tr = tl;
        float bl = last ? r : 0f, br = bl;
        float[] radii = new float[]{ModouUi.dp(this, tl), ModouUi.dp(this, tl),
                ModouUi.dp(this, tr), ModouUi.dp(this, tr),
                ModouUi.dp(this, br), ModouUi.dp(this, br),
                ModouUi.dp(this, bl), ModouUi.dp(this, bl)};
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(ui.card);
        g.setCornerRadii(radii);
        // iOS 行按压是整行浅灰高亮（systemFill），不是水波外扩
        android.graphics.drawable.GradientDrawable press =
                new android.graphics.drawable.GradientDrawable();
        press.setColor(sp.statePressed);
        press.setCornerRadii(radii);
        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, press);
        sld.addState(new int[]{}, g);
        return sld;
    }

    private void bindRow(LocalHolder h, Row r, int pos, int count) {
        final DesignSpec sp = ui.spec;
        h.cur = r;
        boolean failed = !r.imported && !r.busy && !r.err.isEmpty();

        // iOS 连续分组：一行是不是首行/末行决定它圆哪两个角；位置不变就直接复用，
        // 滚动时不再每帧 new 两个 GradientDrawable（复用行会被反复 bind）。
        if (sp.localRowStyle == 1 && h.bgPos != pos) {
            h.bgPos = pos;
            h.row.setBackground(groupedRowBg(pos, count));
        }

        // 勾选圈：多选时才出现；颜色/描边随 picked 变，drawable 复用不重建
        h.dot.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            int accent = sp.localRowStyle == 1 ? sp.sysBlue : ui.accent;
            h.dot.setText(r.picked ? "✓" : "");
            h.dot.setTextColor(r.picked ? 0xFFFFFFFF : ui.line);
            h.dotBg.setColor(r.picked ? accent : 0x00000000);
            h.dotBg.setStroke(Math.max(1, ModouUi.dp(this, 1.5f)),
                    r.picked ? accent : ui.line);
        }

        h.name.setText(strip(r.name));
        h.sub.setText(failed ? r.err : BookFiles.sizeText(r.size) + " · " + date(r.mtime));
        h.sub.setTextColor(failed ? ui.danger : ui.sub);
        // 双向重置：failed 行放宽过 maxLines/padding，复用成普通行必须还原，否则串位
        if (failed) {
            h.sub.setSingleLine(false);
            h.sub.setMaxLines(2);
            h.sub.setEllipsize(null);
            h.sub.setPadding(0, ModouUi.dp(this, 2), 0, 0);
        } else {
            h.sub.setSingleLine(true);
            h.sub.setMaxLines(1);
            h.sub.setEllipsize(TextUtils.TruncateAt.END);
            h.sub.setPadding(0, 0, 0, 0);
        }

        boolean repair = !r.repairId.isEmpty();
        h.tag.setVisibility(r.imported || repair ? View.VISIBLE : View.GONE);
        if (repair) {
            h.tag.setText("待恢复");
            h.tag.setTextColor(ui.danger);
        } else {
            h.tag.setText("已导入");
        }
        // 待恢复的书必须还能点「＋」：这正是用户重装后唯一能走的路
        h.add.setVisibility((!r.imported || repair) && !selecting ? View.VISIBLE : View.GONE);
        if (!r.imported && !selecting) {
            h.add.setText(r.busy ? "…" : "＋");
            int on = sp.localAddStyle == 1 ? sp.sysBlue : ui.accent;
            h.add.setTextColor(r.busy ? ui.sub : on);
        }
    }

    private static String strip(String n) {
        String s = String.valueOf(n);
        int i = s.lastIndexOf('.');
        return i > 0 ? s.substring(0, i) : s;
    }

    private static String date(long ms) {
        if (ms <= 0) return "未知时间";
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        return f.format(new java.util.Date(ms));
    }

    /* ---------------- 空状态 ---------------- */

    /* ---------------- 0.9.177：换机 / 重装后的正文补齐 ---------------- */

    /**
     * 顶部提示条：库里已有这本书的目录与进度，但正文为空（备份不带正文 + SAF 授权被撤销）。
     *
     * <p>用户重装后只需要「重新授权一次文件夹」→ 点「一键补齐」→ 正文从原文件重新解析，
     * 沿用原 bookId，所以阅读进度、书签、分组都不会丢。不需要删书重导。
     */
    private LinearLayout buildRepairBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ModouUi.dp(this, 12), ModouUi.dp(this, 9),
                ModouUi.dp(this, 8), ModouUi.dp(this, 9));
        bar.setBackground(ModouUi.squircle(this, ModouUi.alpha(ui.danger, 0x18), 12));
        bar.setVisibility(View.GONE);

        repairText = ModouUi.tv(this, "", 12.5f, ui.danger);
        repairText.setSingleLine(true);
        repairText.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(repairText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = ModouUi.tv(this, "一键补齐", 13, ui.accent);
        go.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        go.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 6),
                ModouUi.dp(this, 6), ModouUi.dp(this, 6));
        go.setOnClickListener(v -> repairAll());
        bar.addView(go);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ModouUi.dp(this, 8);
        bar.setLayoutParams(lp);
        return bar;
    }

    /** 统计「缺正文」的行，决定提示条是否出现 */
    private void updateRepairBar() {
        if (repairBar == null) return;
        int n = 0;
        for (Row r : rows) if (!r.repairId.isEmpty() && !r.busy) n++;
        repairBar.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        if (n > 0) {
            repairText.setText(n + " 本有记录但缺正文 · 补齐后阅读进度不变");
        }
    }

    /** 一键补齐：全部走同一条串行队列，不会并发解析 */
    private void repairAll() {
        int n = 0;
        for (Row r : new ArrayList<>(rows)) {
            if (r.repairId.isEmpty() || r.busy) continue;
            importRow(r);
            n++;
        }
        if (n == 0) ModouUi.toast(this, "没有需要补齐的书");
        else ModouUi.toast(this, "开始补齐 " + n + " 本，正文会重新从原文件解析");
    }

    private View buildEmpty() {
        DesignSpec sp = ui.spec;
        if (sp.localEmptyStyle != 0) return buildEmptyModern(sp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, ModouUi.dp(this, 40), 0, ModouUi.dp(this, 20));

        // 加号占位：一个虚线圆 + 中间的「＋」，点它等于「重新选文件夹 / 导入」
        TextView plus = ModouUi.tv(this, "＋", 30, ui.sub);
        plus.setGravity(Gravity.CENTER);
        int d = ModouUi.dp(this, 92);
        plus.setLayoutParams(new LinearLayout.LayoutParams(d, d));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(d / 2f);
        g.setStroke(Math.max(1, ModouUi.dp(this, 1.5f)), ui.line);
        plus.setBackground(g);
        plus.setOnClickListener((v) -> {
            if (!pickFolder(this)) pickFile();
        });
        box.addView(plus);

        TextView tip = ModouUi.tv(this,
                "这个文件夹里还没有 TXT / EPUB\n点上面的加号重新选文件夹，或直接挑一个文件导入",
                13, ui.sub);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, ModouUi.dp(this, 16), 0, ModouUi.dp(this, 14));
        box.addView(tip);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        btns.addView(pillBtn("重新选择文件夹", (v) -> {
            if (!pickFolder(this)) pickFile();
        }));
        btns.addView(pillBtn("选择文件导入", (v) -> pickFile()));
        box.addView(btns);
        return box;
    }

    private TextView pillBtn(String s, View.OnClickListener cb) {
        TextView t = ModouUi.tv(this, s, 13.5f, ui.accent);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ModouUi.dp(this, 16), ModouUi.dp(this, 10),
                ModouUi.dp(this, 16), ModouUi.dp(this, 10));
        t.setBackground(ModouUi.squircle(this, 0x00000000, 10, ui.accent, 1));
        t.setOnClickListener(cb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ModouUi.dp(this, 8);
        lp.rightMargin = ModouUi.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * iOS / M3 的空态。两者都不是「虚线圆 + 加号」：
     * · iOS 26 —— 符号 + 标题 + 两句说明 + 两颗蓝色文字按钮（系统「无内容」页的写法）；
     * · M3 Expressive —— 大 tonal 圆底插画 + headline + supporting text + Filled/Text 按钮。
     * 按钮走全局 A6 按钮族，形状与配色由语言决定，这里不写 if (lang == ...)。
     */
    private View buildEmptyModern(DesignSpec sp) {
        final boolean ios = sp.localEmptyStyle == 1;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(ModouUi.dp(this, 12), ModouUi.dp(this, ios ? 64 : 56),
                ModouUi.dp(this, 12), ModouUi.dp(this, 24));

        int glyphDp = ios ? 46 : 44;
        if (ios) {
            ImageView g = new ImageView(this);
            g.setImageDrawable(new ModouGlyph(ModouGlyph.BOOK, ui.sub,
                    ModouUi.dp(this, glyphDp), ModouUi.dp(this, 1.6f)));
            box.addView(g, new LinearLayout.LayoutParams(
                    ModouUi.dp(this, glyphDp), ModouUi.dp(this, glyphDp)));
        } else {
            // M3：tonal 圆底 + 主色图标（插画位的规范做法，不引入位图）
            FrameLayout circle = new FrameLayout(this);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(ModouUi.alpha(ui.accent, 0x18));
            circle.setBackground(bg);
            int d = ModouUi.dp(this, 104);
            ImageView g = new ImageView(this);
            g.setImageDrawable(new ModouGlyph(ModouGlyph.BOOK, ui.accent,
                    ModouUi.dp(this, glyphDp), ModouUi.dp(this, 1.8f)));
            circle.addView(g, new FrameLayout.LayoutParams(
                    ModouUi.dp(this, glyphDp), ModouUi.dp(this, glyphDp), Gravity.CENTER));
            box.addView(circle, new LinearLayout.LayoutParams(d, d));
        }

        TextView title = ModouUi.tv(this, "这里还没有书", ios ? 22 : 24, ui.text);
        if (ios) {
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            title.setLetterSpacing(-0.015f);
        } else {
            title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        }
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, ModouUi.dp(this, ios ? 14 : 18), 0, 0);
        box.addView(title);

        TextView tip = ModouUi.tv(this,
                ios
                        ? "选一个专门放书的文件夹，之后这里就会列\n出里面的 TXT / EPUB，点一下即入书架。"
                        : "选一个专门放书的文件夹，之后这里就会列出里面的 TXT / EPUB，点一下就进书架。",
                ios ? 15 : 14, ui.sub);
        tip.setGravity(Gravity.CENTER);
        tip.setLineSpacing(ModouUi.dp(this, 3), 1f);
        tip.setPadding(ModouUi.dp(this, 8), ModouUi.dp(this, 8),
                ModouUi.dp(this, 8), ModouUi.dp(this, ios ? 22 : 24));
        box.addView(tip);

        if (ios) {
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.CENTER);
            btns.addView(ModouUi.button(this, "选择文件夹", DesignSpec.ButtonKind.TEXT,
                    ModouUi.CAPSULE, v -> pickFolderOrFile()));
            btns.addView(ModouUi.button(this, "选择文件导入", DesignSpec.ButtonKind.TEXT,
                    ModouUi.CAPSULE, v -> pickFile()));
            box.addView(btns);
        } else {
            TextView filled = ModouUi.button(this, "选择文件夹", DesignSpec.ButtonKind.FILLED,
                    ModouUi.CAPSULE, v -> pickFolderOrFile());
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ModouUi.dp(this, 200), ModouUi.dp(this, ui.spec.buttonHeightDp));
            box.addView(filled, flp);
            TextView text = ModouUi.button(this, "选择文件导入", DesignSpec.ButtonKind.TEXT,
                    ModouUi.CAPSULE, v -> pickFile());
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ModouUi.dp(this, 200), ModouUi.dp(this, ui.spec.buttonHeightDp));
            tlp.topMargin = ModouUi.dp(this, 4);
            box.addView(text, tlp);
        }
        return box;
    }

    /* ---------------- 多选 ---------------- */

    private void updateSelBar() {
        boolean show = selecting;
        selectBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        int n = 0;
        int can = 0;
        for (Row r : rows) {
            if (r.imported) continue;
            can++;
            if (r.picked) n++;
        }
        selAll.setText(n >= can && can > 0 ? "全不选" : "全选");
        selInfo.setText("已选 " + n + " 本");
        selGo.setText(n > 0 ? "导入 " + n + " 本" : "导入");
        selGo.setAlpha(n > 0 ? 1f : 0.45f);
    }

    private void toggleAll() {
        int can = 0;
        int n = 0;
        for (Row r : rows) {
            if (r.imported) continue;
            can++;
            if (r.picked) n++;
        }
        boolean all = can > 0 && n >= can;
        for (Row r : rows) r.picked = !r.imported && !all;
        updateSelBar();
        paintList();
    }

    private void exitSelect() {
        selecting = false;
        for (Row r : rows) r.picked = false;
        updateSelBar();
        paintList();
    }

    private void importPicked() {
        List<Row> picked = new ArrayList<>();
        for (Row r : rows) if (r.picked && !r.imported && !r.busy) picked.add(r);
        if (picked.isEmpty()) {
            ModouUi.toast(this, "先勾几本要导入的");
            return;
        }
        selecting = false;
        for (Row r : rows) r.picked = false;
        updateSelBar();
        for (Row r : picked) {
            r.busy = true;
            r.err = "";
            enqueueRow(r);
        }
        paintList();
    }

    /* ---------------- 导入 ---------------- */

    private void importRow(Row r) {
        if (r.busy) return;
        if (r.imported && r.repairId.isEmpty()) return;
        if (!r.repairId.isEmpty()) {
            ModouUi.toast(this, "正在补齐《" + strip(r.name) + "》的正文，阅读进度会保留");
        }
        r.busy = true;
        r.err = "";
        paintList();
        enqueueRow(r);
    }

    /**
     * 交给 LocalImport 的串行队列：连点几本也只会在后台一本一本处理，
     * 不会同时开两个解析线程把内存吃光（那正是「连点两本闪回桌面」的原因）。
     */
    private void enqueueRow(Row r) {
        // 第一批任务入队时重置计数；中途再点一本会并入当前批（分母跟着变大）
        if (queuePending <= 0) {
            queueTotal = 0;
            queueDone = 0;
            queueOk = 0;
            queueErrs.clear();
        }
        queuePending++;
        queueTotal++;

        LocalImport.enqueue(this, r.uri, r.name, r.size,
                r.repairId.isEmpty() ? null : r.repairId, new LocalImport.Callback() {
            @Override
            public void onStage(String text) {
                place.setText(queueTotal > 1
                        ? text + "（" + Math.min(queueDone + 1, queueTotal) + "/" + queueTotal + "）"
                        : text);
            }

            @Override
            public void onDone(LocalImport.Res res) {
                r.busy = false;
                if (res.ok) {
                    r.imported = true;
                    r.repairId = "";
                    r.err = "";
                    // 用真实字节数校正本行：provider 报的 size 可能不准，
                    // 不校正的话下次 refreshStates 会把「已导入」又算回「未导入」
                    if (res.bytes > 0) r.size = res.bytes;
                    rememberErr(r.uri, "");
                    queueOk++;
                } else {
                    // 失败原因留在这一行上：toast 一闪而过，用户根本来不及看清，
                    // 结果就是「点了没反应」。行上红字还能提示他该怎么处理。
                    r.err = res.msg;
                    rememberErr(r.uri, res.msg);
                    if (!queueErrs.contains(res.msg)) queueErrs.add(res.msg);
                }
                queueDone++;
                queuePending--;
                if (queuePending <= 0) {
                    // 这一批全结束了：给一条汇总，再把副标题还原成「文件夹 · N 本」
                    restorePlace();
                    if (queueTotal > 1) {
                        int bad = queueTotal - queueOk;
                        String why = queueErrs.isEmpty() ? "" : "\n" + queueErrs.get(0)
                                + (queueErrs.size() > 1 ? " 等" : "");
                        ModouUi.toast(LocalBooksActivity.this,
                                "导入完成：" + queueOk + " 本成功"
                                        + (bad > 0 ? "，" + bad + " 本失败" + why : ""));
                    } else if (res.ok) {
                        ModouUi.toast(LocalBooksActivity.this, res.msg);
                    } else {
                        ModouUi.toast(LocalBooksActivity.this, res.msg);
                    }
                    queueTotal = 0;
                    queueDone = 0;
                    queueOk = 0;
                    queueErrs.clear();
                }
                updateRepairBar();
                paintList();
            }
        });
    }

    private void restorePlace() {
        place.setText(BookFiles.placeOf(this) + " · " + rows.size() + " 本");
    }

    /** 失败原因表（文件 Uri → 原因）：重进页面也能看到，不用凭记忆复述 */
    private JSONObject errMap() {
        try {
            return new JSONObject(prefs(this).getString(PREF_ERRS, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void rememberErr(Uri u, String msg) {
        if (u == null) return;
        try {
            JSONObject o = errMap();
            String k = String.valueOf(u);
            if (msg == null || msg.isEmpty()) o.remove(k);
            else if (o.length() < 300) o.put(k, msg);
            prefs(this).edit().putString(PREF_ERRS, o.toString()).apply();
        } catch (Exception ignore) {
            /* 记不住也不影响导入 */
        }
    }

    /** 单文件兜底：系统没有目录选择器，或用户就想挑一个文件 */
    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_FILE);
        } catch (Exception e) {
            ModouUi.toast(this, "打不开文件选择器");
        }
    }

    private String guessName(Uri u) {
        String n = "";
        try {
            Cursor c = getContentResolver().query(u,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) n = c.getString(0);
                c.close();
            }
        } catch (Exception ignore) {
            /* 拿不到就用 Uri 最后一段 */
        }
        if (n == null) n = "";
        if (n.isEmpty()) {
            String s = String.valueOf(u.getLastPathSegment());
            int i = s.lastIndexOf('/');
            n = i >= 0 ? s.substring(i + 1) : s;
        }
        return n;
    }

    private void importUri(Uri u, String name, long size) {
        Row r = new Row();
        r.name = name;
        r.size = size;
        r.uri = u;
        r.mtime = System.currentTimeMillis();
        rows.add(r);
        importRow(r);
    }

    /**
     * 换文件夹时把**旧**目录的长期读权限放掉：一直挂着的话，用户在系统设置里会看到
     * 本 App 持有好几个目录的访问权限，删也删不干净。当前这个目录不能放 ——
     * 放了下次进来就又读不了了。
     */
    static void dropOldPermission(Context c, String oldUri) {
        if (TextUtils.isEmpty(oldUri)) return;
        try {
            Uri u = Uri.parse(oldUri);
            c.getContentResolver().releasePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignore) {
            /* 无所谓 */
        }
    }
}
