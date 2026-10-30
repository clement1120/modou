package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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

    private LinearLayout body;
    private EditText input;
    private TextView srcBtn;
    private TextView status;
    private ModouUi ui;

    private final List<JSONObject> sources = new ArrayList<>();
    private final Set<String> picked = new LinkedHashSet<>();
    private final List<Group> groups = new ArrayList<>();
    private final List<String> fails = new ArrayList<>();
    private int gen = 0;

    static void launch(Activity from) {
        from.startActivity(new Intent(from, SearchActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ModouUi.applyTheme(this);
        super.onCreate(savedInstanceState);
        ui = ModouUi.of(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.bg);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(this, 14);
        body.setPadding(p, p, p, p);
        sv.addView(body);
        sv.setOnScrollChangeListener((v, sx, sy, osx, osy) -> CoverLoader.markScroll());

        body.addView(buildHead());
        body.addView(buildBar());
        body.addView(buildSourceRow());

        status = ModouUi.tv(this, "", 12, ui.sub);
        status.setPadding(ModouUi.dp(this, 4), ModouUi.dp(this, 8), ModouUi.dp(this, 4), ModouUi.dp(this, 8));
        body.addView(status);

        ListView list = new ListView(this);
        list.setDividerHeight(ModouUi.dp(this, 8));
        list.setBackgroundColor(ui.bg);
        list.setAdapter(adapter);
        // ListView 必须给定高度，否则 ScrollView 里它只显示一行（measure 拿不到总高）
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(list);

        setContentView(sv);

        loadSources();
        status.setText("输入书名或作者，从书源里找书。");
        SourceService svc = SourceService.get();
        if (svc != null) svc.warmUp(); // 预热：等用户输完词再起服务页就慢了
    }

    /* ---------------- 顶部 ---------------- */

    /** 头部与书架/规则页同一套：上一行「‹ 返回」，下一行 26sp 衬线大标题 */
    private View buildHead() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);

        TextView back = ModouUi.tv(this, "‹ 返回", 14, ui.accent);
        back.setPadding(0, ModouUi.dp(this, 4), 0, ModouUi.dp(this, 8));
        back.setOnClickListener((v) -> finish());
        head.addView(back);

        TextView t = ModouUi.tv(this, "搜书", 26, ui.text);
        t.setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, ModouUi.dp(this, 10));
        head.addView(t);
        return head;
    }

    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        input = new EditText(this);
        input.setHint("书名 / 作者");
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(ui.text);
        input.setHintTextColor(ui.sub);
        input.setBackground(ModouUi.round(ui.card, 10));
        int pd = ModouUi.dp(this, 10);
        input.setPadding(pd, pd, pd, pd);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = ModouUi.tv(this, "搜索", 14, 0xFFFFFFFF);
        go.setBackground(ModouUi.round(ui.accent, 10));
        int gp = ModouUi.dp(this, 10);
        go.setPadding(ModouUi.dp(this, 14), gp, ModouUi.dp(this, 14), gp);
        go.setOnClickListener((v) -> doSearch());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ModouUi.dp(this, 8);
        bar.addView(go, lp);
        return bar;
    }

    private View buildSourceRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int v = ModouUi.dp(this, 10);
        row.setPadding(0, v, 0, v);

        srcBtn = ModouUi.tv(this, "书源", 13, ui.accent);
        srcBtn.setBackground(ModouUi.round(ui.card, 8));
        int p = ModouUi.dp(this, 10);
        srcBtn.setPadding(p, p, p, p);
        srcBtn.setOnClickListener((x) -> pickSources());
        row.addView(srcBtn);

        row.addView(ModouUi.tv(this, "  点这里选择要搜哪些书源", 12, ui.sub));
        return row;
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
        paintSrcBtn();
    }

    private Set<String> savedPicked() {
        String s = getSharedPreferences("modou", MODE_PRIVATE).getString(PREF_PICKED, null);
        if (s == null || s.isEmpty()) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String part : s.split("")) if (!part.isEmpty()) out.add(part);
        return out;
    }

    private void savePicked() {
        StringBuilder b = new StringBuilder();
        for (String u : picked) b.append(u).append("");
        getSharedPreferences("modou", MODE_PRIVATE).edit()
                .putString(PREF_PICKED, b.toString()).apply();
    }

    private void paintSrcBtn() {
        int n = 0;
        for (JSONObject o : sources) if (picked.contains(o.optString("bookSourceUrl", ""))) n++;
        srcBtn.setText("书源 " + n + "/" + sources.size());
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
                paintSrcBtn();
            });
            rows[i] = row;
            ticks[i] = tick;
            box.addView(row);
        }

        final Dialog d = ModouUi.sheet(this, "选择要搜索的书源", box);
        box.addView(ModouUi.sheetButtons(this, d, "全不选", "完成", () -> {
            picked.clear();
            savePicked();
            paintSrcBtn();
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
            paintSrcBtn();
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
        String g = String.valueOf(s == null ? "" : s).split("[,，]")[0].trim();
        return g.isEmpty() ? "未分组" : g;
    }

    /* ---------------- 搜索 ---------------- */

    private void doSearch() {
        String kw = input == null ? "" : String.valueOf(input.getText()).trim();
        if (kw.isEmpty()) {
            ModouUi.toast(this, "先输入关键词");
            return;
        }
        if (picked.isEmpty()) {
            ModouUi.toast(this, "没有选中的书源");
            return;
        }
        SourceService svc = SourceService.get();
        if (svc == null) {
            ModouUi.toast(this, "书源服务未启动");
            return;
        }
        final int myGen = ++gen;
        groups.clear();
        fails.clear();
        adapter.notifyDataSetChanged();
        status.setTextColor(ui.sub);
        status.setText("搜索中… 0/" + picked.size());

        svc.search(kw, picked, 1, new SourceService.Callback() {
            @Override
            public void onProgress(int done, int total) {
                if (myGen != gen) return;
                runOnUiThread(() -> status.setText("搜索中… " + done + "/" + total));
            }

            @Override
            public void onDone(JSONObject res) {
                if (myGen != gen) return;
                runOnUiThread(() -> onSearchDone(res));
            }
        });
    }

    private void onSearchDone(JSONObject res) {
        groups.clear();
        fails.clear();
        if (res == null || !res.optBoolean("ok", false)) {
            status.setTextColor(ui.danger);
            status.setText(res == null ? "搜索失败" : res.optString("error", "搜索失败"));
            adapter.notifyDataSetChanged();
            return;
        }
        JSONArray arr = res.optJSONArray("groups");
        if (arr != null) {
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
                if (!hs.isEmpty()) {
                    groups.add(new Group(g.optString("name", ""), g.optString("author", ""), hs));
                }
            }
        }
        JSONArray fs = res.optJSONArray("fails");
        if (fs != null) {
            for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.optJSONObject(i);
                if (f == null) continue;
                fails.add(f.optString("name", "?") + "：" + f.optString("reason", "失败"));
            }
        }
        int filtered = res.optInt("filtered", 0);
        StringBuilder sb = new StringBuilder("找到 ").append(groups.size()).append(" 本书");
        if (filtered > 0) sb.append("（过滤 ").append(filtered).append(" 条无关结果）");
        if (!fails.isEmpty()) sb.append("\n").append(fails.size()).append(" 个书源失败：")
                .append(android.text.TextUtils.join("；", fails));
        if (groups.isEmpty()) sb.append("\n换个关键词，或检查书源搜索规则。");
        status.setTextColor(fails.isEmpty() ? ui.sub : ui.danger);
        status.setText(sb.toString());
        adapter.notifyDataSetChanged();
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
        status.setTextColor(ui.sub);
        status.setText("正在获取《" + g.name + "》的信息…");

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
        status.setText("正在获取目录…");
        svc.toc(info, res -> runOnUiThread(() -> {
            if (myGen != gen) return;
            if (res == null || !res.optBoolean("ok", false)) {
                status.setTextColor(ui.danger);
                status.setText(res == null ? "目录获取失败" : res.optString("error", "目录获取失败"));
                return;
            }
            JSONArray chapters = res.optJSONArray("chapters");
            if (chapters == null || chapters.length() == 0) {
                status.setTextColor(ui.danger);
                status.setText("该书源没有解析出章节");
                return;
            }
            String id = savePreview(info, h, chapters);
            if (id == null) {
                status.setTextColor(ui.danger);
                status.setText("暂存书籍失败");
                return;
            }
            BookActivity.launch(this, id);
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
            return groups.size();
        }

        @Override
        public Object getItem(int position) {
            return groups.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Group g = groups.get(position);
            LinearLayout card = ui.card(SearchActivity.this);
            Hit h0 = g.hits.get(0);

            // 封面 + 信息两列：搜到的是「一本书」，先给封面才看得出是哪本
            LinearLayout outer = new LinearLayout(SearchActivity.this);
            outer.setOrientation(LinearLayout.HORIZONTAL);

            FrameLayout cf = new FrameLayout(SearchActivity.this);
            cf.setBackground(ModouUi.round(ui.card, 8));
            ImageView iv = new ImageView(SearchActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cf.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            CoverLoader.load(iv, h0.book == null ? "" : h0.book.optString("coverUrl", ""), g.name);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ModouUi.dp(SearchActivity.this, 44), ModouUi.dp(SearchActivity.this, 60));
            clp.rightMargin = ModouUi.dp(SearchActivity.this, 12);
            outer.addView(cf, clp);

            LinearLayout info = new LinearLayout(SearchActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            outer.addView(info, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(outer);

            info.addView(ui.headRow(SearchActivity.this, g.name, g.hits.size() > 1
                    ? g.hits.size() + " 个来源" : h0.sourceName));

            StringBuilder meta = new StringBuilder();
            if (!g.author.isEmpty()) meta.append(g.author);
            String last = h0.book == null ? "" : h0.book.optString("lastChapter", "");
            if (!last.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(last);
            }
            if (meta.length() == 0 && h0.book != null) meta.append(h0.book.optString("kind", ""));
            if (meta.length() > 0) {
                TextView m = ModouUi.tv(SearchActivity.this, meta.toString(), 12, ui.sub);
                m.setSingleLine(true);
                m.setEllipsize(android.text.TextUtils.TruncateAt.END);
                info.addView(m);
            }
            if (g.hits.size() > 1) {
                TextView s = ModouUi.tv(SearchActivity.this,
                        android.text.TextUtils.join("、", sourceNames(g)), 11, ui.sub);
                s.setPadding(0, ModouUi.dp(SearchActivity.this, 4), 0, 0);
                info.addView(s);
            }
            card.setOnClickListener((v) -> openGroup(g));
            return card;
        }
    };

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

    private static final class Group {
        final String name;
        final String author;
        final List<Hit> hits;

        Group(String n, String a, List<Hit> h) {
            name = n;
            author = a;
            hits = h;
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
