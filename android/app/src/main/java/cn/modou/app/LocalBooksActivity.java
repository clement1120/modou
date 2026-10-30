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

    private final List<Row> rows = new ArrayList<>();
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
    private int warnColor() {
        int c = ui.text;
        int lum = (299 * android.graphics.Color.red(c)
                + 587 * android.graphics.Color.green(c)
                + 114 * android.graphics.Color.blue(c)) / 1000;
        return lum > 150 ? 0xFFE89484 : 0xFFB33A2B;
    }

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
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
        from.startActivity(new Intent(from, LocalBooksActivity.class));
    }

    /* ---------------- 生命周期 ---------------- */

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
        root.setPadding(p, p + statusBarPx(), p, p);

        root.addView(buildHead());

        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setVisibility(View.GONE);
        searchBox = ModouUi.input(this, "搜索书名", "", 1);
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
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = ModouUi.tv(this, "清除", 13, ui.accent);
        clear.setPadding(ModouUi.dp(this, 10), 0, 0, 0);
        clear.setOnClickListener((v) -> {
            searchBox.setText("");
            searchRow.setVisibility(View.GONE);
            query = "";
            paintList();
        });
        searchRow.addView(clear);
        root.addView(searchRow);

        emptyBox = buildEmpty();
        root.addView(emptyBox);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        selectBar = buildSelectBar();
        root.addView(selectBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        load();
    }

    /** 底部多选条：长按进入后才出现（全选 / 已选 N 本 · 导入） */
    private LinearLayout buildSelectBar() {
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
            importUri(data.getData(), guessName(data.getData()), 0);
        }
    }

    /* ---------------- 头部 ---------------- */

    private View buildHead() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = ModouUi.tv(this, "‹ 返回", 15, ui.accent);
        back.setPadding(0, 0, ModouUi.dp(this, 10), 0);
        back.setOnClickListener((v) -> finish());
        top.addView(back);

        TextView title = ModouUi.tv(this, "本地书籍", 20, ui.text);
        title.setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        top.addView(headBtn("搜索", (v) -> {
            boolean on = searchRow.getVisibility() == View.VISIBLE;
            searchRow.setVisibility(on ? View.GONE : View.VISIBLE);
            if (!on) searchBox.requestFocus();
        }));
        top.addView(headBtn("文件夹", (v) -> {
            if (!pickFolder(this)) pickFile();
        }));
        top.addView(headBtn("排序", (v) -> chooseSort()));
        h.addView(top);

        place = ModouUi.tv(this, "", 12, ui.sub);
        place.setSingleLine(true);
        place.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        place.setPadding(0, ModouUi.dp(this, 4), 0, ModouUi.dp(this, 6));
        h.addView(place);
        return h;
    }

    private TextView headBtn(String s, View.OnClickListener cb) {
        // 三个入口 + 返回 + 标题要挤在一行里：窄屏（360dp）上按 12sp / 8dp 内边距排，
        // 否则标题会被挤到看不见
        TextView t = ModouUi.tv(this, s, 12, ui.text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ModouUi.dp(this, 8), ModouUi.dp(this, 6),
                ModouUi.dp(this, 8), ModouUi.dp(this, 6));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(ui.card);
        g.setCornerRadius(ModouUi.dp(this, 999));
        g.setStroke(Math.max(1, ModouUi.dp(this, 1)), ui.line);
        t.setBackground(g);
        t.setOnClickListener(cb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ModouUi.dp(this, 4);
        t.setLayoutParams(lp);
        return t;
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
            for (Row r : got) {
                r.imported = known.contains(LocalImport.urlOf(r.name, r.size))
                        || uniq.contains(r.name);
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
        List<Row> show = new ArrayList<>();
        for (Row r : rows) {
            if (query.isEmpty() || r.name.toLowerCase(Locale.ROOT)
                    .contains(query.toLowerCase(Locale.ROOT))) show.add(r);
        }
        final List<Row> data = show;
        boolean none = data.isEmpty();
        emptyBox.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        list.setAdapter(new BaseAdapter() {
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
            public View getView(int i, View cv, ViewGroup p) {
                return rowView(data.get(i));
            }
        });
    }

    private View rowView(Row r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ModouUi.dp(this, 10), ModouUi.dp(this, 10),
                ModouUi.dp(this, 6), ModouUi.dp(this, 10));
        row.setBackground(ModouUi.round(ui.card, 12));

        // 多选态：左侧勾选圈。已导入的书不给勾（再导一次只是覆盖，没有意义）
        if (selecting) {
            TextView dot = ModouUi.tv(this, r.picked ? "✓" : "", 13,
                    r.picked ? 0xFFFFFFFF : ui.line);
            dot.setGravity(Gravity.CENTER);
            int dd = ModouUi.dp(this, 22);
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(r.picked ? ui.accent : 0x00000000);
            g.setStroke(Math.max(1, ModouUi.dp(this, 1.5f)), r.picked ? ui.accent : ui.line);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dd, dd);
            lp.rightMargin = ModouUi.dp(this, 10);
            row.addView(dot, lp);
        }

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t = ModouUi.tv(this, strip(r.name), 15, ui.text);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(t);
        boolean failed = !r.imported && !r.busy && !r.err.isEmpty();
        TextView s = ModouUi.tv(this, failed ? r.err
                        : BookFiles.sizeText(r.size) + " · " + date(r.mtime),
                12, failed ? warnColor() : ui.sub);
        s.setSingleLine(!failed);
        if (!failed) s.setEllipsize(TextUtils.TruncateAt.END);
        if (failed) {
            s.setPadding(0, ModouUi.dp(this, 2), 0, 0);
            s.setMaxLines(2);
        }
        txt.addView(s);
        row.addView(txt, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (r.imported) {
            TextView tag = ModouUi.tv(this, "已导入", 12.5f, ui.sub);
            tag.setPadding(ModouUi.dp(this, 8), ModouUi.dp(this, 4),
                    ModouUi.dp(this, 8), ModouUi.dp(this, 4));
            row.addView(tag);
        } else if (!selecting) {
            TextView add = ModouUi.tv(this, r.busy ? "…" : "＋", 18,
                    r.busy ? ui.sub : ui.accent);
            add.setGravity(Gravity.CENTER);
            int d = ModouUi.dp(this, 34);
            add.setLayoutParams(new LinearLayout.LayoutParams(d, d));
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setColor(0x00000000);
            g.setCornerRadius(d / 2f);
            g.setStroke(Math.max(1, ModouUi.dp(this, 1.5f)), ui.line);
            add.setBackground(g);
            add.setOnClickListener((v) -> importRow(r));
            row.addView(add);
        }

        // 整行也可点：未导入的等同点「＋」（手指不一定点得准那个小圆），
        // 已导入的给一句明确回话，避免「点了没反应」
        row.setOnClickListener((v) -> {
            if (selecting) {
                if (r.imported) {
                    ModouUi.toast(this, "《" + strip(r.name) + "》已经在书架里了");
                    return;
                }
                r.picked = !r.picked;
                updateSelBar();
                paintList();
            } else if (r.imported) {
                ModouUi.toast(this, "《" + strip(r.name) + "》已经在书架里了");
            } else {
                importRow(r);
            }
        });
        // 长按任意一本进入多选：整本书太多时，一本本点「＋」太慢
        row.setOnLongClickListener((v) -> {
            if (!selecting) {
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
        wrap.setPadding(0, 0, 0, ModouUi.dp(this, 8));
        return wrap;
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

    private View buildEmpty() {
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
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(ModouUi.dp(this, 10));
        g.setStroke(Math.max(1, ModouUi.dp(this, 1)), ui.accent);
        t.setBackground(g);
        t.setOnClickListener(cb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ModouUi.dp(this, 8);
        lp.rightMargin = ModouUi.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
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
        if (r.busy || r.imported) return;
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

        LocalImport.enqueue(this, r.uri, r.name, r.size, new LocalImport.Callback() {
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
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
