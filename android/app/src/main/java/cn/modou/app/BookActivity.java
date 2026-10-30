package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;

import java.net.HttpURLConnection;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生书籍详情 + 目录（渐进式原生化第 5 步）。
 *
 * 目录可能有几千章，所以沿用网页版的做法：一次只渲染 200 章，点「加载更多」
 * 再追加。一次性全塞进 ListView 会在打开大书时卡住好几秒。
 *
 * 「刷新目录」走原生 SourceService，结果直接写库，不再跳网页版。
 * 本版新增：缓存全书（逐章抓取 + 进度 + 可中断）、在线换源（按书名搜其他书源）、
 * 详情页头图 + 26sp 大标题 + 作者/来源/N 章副行，以及按钮分组。
 */
public class BookActivity extends Activity {

    private static final int PAGE = 200;

    /** 刷新目录进行中：连着点两次会拉两遍目录，白等也白写库 */
    private boolean refreshingToc;
    /** 缓存全书进行中 */
    private boolean caching;
    /** 缓存被用户取消：本批次停下，已抓的章节保留 */
    private boolean cacheCancel;

    static void launch(Activity from, String bookId) {
        Intent i = new Intent(from, BookActivity.class);
        i.putExtra("id", bookId);
        from.startActivity(i);
    }

    private ModouUi ui;
    private ModouStore store;
    /** 首屏给的是轻量记录（无 chapters / bookUrl），完整记录后台补进来（见 loadFullAsync） */
    private JSONObject book;
    private boolean fullLoaded;
    private JSONObject prog;
    private int shown = PAGE;

    private ListView tocView;
    private TextView moreBtn;
    private TextView countView;
    private int curIndex = -1;
    /** 内容容器：编辑信息后原地重绘（比 recreate 快，也不会丢滚动位置） */
    private LinearLayout bodyView;

    /* 缓存全书进度 UI */
    private Dialog cacheDialog;
    private TextView cacheText;
    private View cacheFill;
    private LinearLayout cacheTrack;
    private int cacheTotal;
    private int cacheDone;
    private int cacheFail;

    @Override
    protected void onCreate(Bundle b) {
        ModouUi.applyTheme(this);
        super.onCreate(b);
        ui = ModouUi.of(this);
        store = ModouStore.get(this);
        try {
            buildPage();
        } catch (Throwable t) {
            // 详情页构建失败绝不能静默退回书架——用户看到的就是「点了书又回到首页」，
            // 完全无法上报。把错误原样摆出来，截图发回来即可定位。
            showError(t);
        }
    }

    private void buildPage() {
        String id = getIntent().getStringExtra("id");
        // 先拿轻量记录（几百字节）出界面：整条记录里带着几千章目录 + 内联封面，
        // 主线程 new JSONObject 解析它就是「点了封面半天没反应」的主因。
        book = store.bookLite(id);
        if (book == null) {
            LinearLayout miss = new LinearLayout(this);
            miss.setOrientation(LinearLayout.VERTICAL);
            miss.setGravity(Gravity.CENTER);
            miss.setBackgroundColor(ui.bg);
            TextView mt = ModouUi.empty(this, "书籍不存在");
            miss.addView(mt);
            TextView back = ModouUi.tv(this, "‹ 返回书架", 15, ui.accent);
            back.setGravity(Gravity.CENTER);
            back.setPadding(0, ModouUi.dp(this, 16), 0, 0);
            back.setOnClickListener((x) -> finish());
            miss.addView(back);
            setContentView(miss);
            return;
        }
        prog = load(store.get("progress", id));
        curIndex = prog == null ? -1 : prog.optInt("index", -1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        root.setPadding(ModouUi.dp(this, 12), statusBarPad(), ModouUi.dp(this, 12), ModouUi.dp(this, 12));

        // 顶部：返回 + 书名
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = ModouUi.tv(this, "‹ 返回", 15, ui.accent);
        back.setPadding(0, ModouUi.dp(this, 6), ModouUi.dp(this, 10), ModouUi.dp(this, 6));
        back.setOnClickListener((x) -> finish());
        head.addView(back);
        TextView title = ModouUi.tv(this, book.optString("name", ""), 18, ui.text);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head);

        ScrollView sc = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sc.addView(body);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        bodyView = body;
        paint(body);
        loadFullAsync(body);
    }

    /**
     * 完整记录（含章节表）后台读：目录卡片等它回来再补。
     *
     * 只重建目录卡片，不动上面两张卡 —— 整页 re-paint 会把滚动位置也一起清掉。
     */
    private void loadFullAsync(final LinearLayout body) {
        final String id = book.optString("id", "");
        if (id.isEmpty()) return;
        new Thread(() -> {
            JSONObject full = null;
            try {
                String s = store.get("books", id);
                if (s != null) full = new JSONObject(s);
            } catch (Throwable ignore) {
                /* 记录损坏：保持轻量记录，目录卡片显示为空 */
            }
            final JSONObject f = full;
            runOnUiThread(() -> {
                if (f == null || isFinishing() || isDestroyed()) return;
                mergeFull(f);
                if (body != null && body.getChildCount() > 2) {
                    body.removeViewAt(2);
                    body.addView(tocCard());
                }
            });
        }).start();
    }

    /** 内存里的字段优先（用户可能刚改过书名），缺的从完整记录补 */
    private void mergeFull(JSONObject full) {
        JSONObject lite = book;
        book = full;
        fullLoaded = true;
        if (lite == null) return;
        try {
            java.util.Iterator<String> it = lite.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!full.has(k)) full.put(k, lite.opt(k));
            }
        } catch (Exception ignore) {
            /* 合并失败就用完整记录 */
        }
    }

    /**
     * 写回书籍记录。
     *
     * 手里可能只有轻量记录：直接 put 会把几千章目录整段写没（书就「目录空了」）。
     * 所以先读回库里的完整记录，再把内存里改过的字段盖上去。
     */
    private void putBook() {
        if (!fullLoaded) {
            try {
                String s = store.get("books", book.optString("id", ""));
                if (s != null) mergeFull(new JSONObject(s));
            } catch (Exception ignore) {
                /* 读不到就按手里的写，总比不保存强 */
            }
        }
        store.put("books", book.toString());
    }

    /** 需要目录 / bookUrl 的操作（刷新目录、缓存全书、换源）前确保拿到完整记录 */
    private boolean ensureFull() {
        if (fullLoaded) return true;
        try {
            String s = store.get("books", book.optString("id", ""));
            if (s == null) return false;
            mergeFull(new JSONObject(s));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 详情页构建失败时的可见错误页（替代静默崩溃退栈） */
    private void showError(Throwable t) {
        LinearLayout err = new LinearLayout(this);
        err.setOrientation(LinearLayout.VERTICAL);
        err.setBackgroundColor(ui.bg);
        err.setPadding(ModouUi.dp(this, 20), statusBarPad() + ModouUi.dp(this, 16),
                ModouUi.dp(this, 20), ModouUi.dp(this, 20));
        TextView h = ModouUi.tv(this, "详情页打开出错", 18, ui.text);
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        err.addView(h);
        TextView msg = ModouUi.tv(this,
                t.getClass().getSimpleName() + ": " + t.getMessage() + "\n\n"
                        + android.util.Log.getStackTraceString(t), 11, ui.sub);
        msg.setTextIsSelectable(true);
        err.addView(msg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView back = ModouUi.tv(this, "‹ 返回书架", 15, ui.accent);
        back.setPadding(0, ModouUi.dp(this, 16), 0, 0);
        back.setOnClickListener((x) -> finish());
        err.addView(back);
        setContentView(err);
    }

    private int statusBarPad() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : ModouUi.dp(this, 24);
    }

    private static JSONObject load(String s) {
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    private void paint(LinearLayout body) {
        body.removeAllViews();
        body.addView(infoCard());
        body.addView(actions());
        body.addView(tocCard());
    }

    private String bookId() {
        return book.optString("id", "");
    }

    private boolean isLocal() {
        return "local://".equals(book.optString("sourceUrl", ""));
    }

    /* ---------------- 书信息（头图 + 大标题 + 副行） ---------------- */

    private View infoCard() {
        LinearLayout card = ui.card(this);
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);

        ImageView iv = new ImageView(this);
        int w = ModouUi.dp(this, 92);
        iv.setLayoutParams(new LinearLayout.LayoutParams(w, (int) (w * 1.35f)));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setColor(0xFFD8CFBB);
        cbg.setCornerRadius(ModouUi.dp(this, 8));
        iv.setBackground(cbg);
        iv.setClipToOutline(true);
        iv.setElevation(ModouUi.dp(this, 2));
        CoverLoader.load(iv, book.optString("coverUrl", ""), book.optString("name", ""));
        h.addView(iv);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(ModouUi.dp(this, 12), 0, 0, 0);

        TextView t = ModouUi.tv(this, book.optString("name", ""), 26, ui.text);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        t.setLineSpacing(ModouUi.dp(this, 1), 1.05f);
        meta.addView(t);

        // 章节数：轻量记录里就有（chapterCount），不必等整份目录解析完
        int total = book.optInt("chapterCount", 0);
        JSONArray chsNow = chapters();
        if (chsNow.length() > 0) total = chsNow.length();
        int pct = total > 0 ? Math.round((Math.max(0, curIndex) / (float) total) * 100) : 0;
        String src = isLocal() ? "本地导入"
                : book.optString("sourceName", book.optString("sourceUrl", "—"));
        StringBuilder sub = new StringBuilder();
        String author = book.optString("author", "");
        if (!author.isEmpty()) sub.append(author).append(" · ");
        sub.append(src);
        if (total > 0) sub.append(" · ").append(total).append(" 章");
        TextView s = ModouUi.tv(this, sub.toString(), 12.5f, ui.sub);
        s.setPadding(0, ModouUi.dp(this, 6), 0, 0);
        s.setLineSpacing(ModouUi.dp(this, 1), 1.1f);
        meta.addView(s);

        String progTxt = curIndex >= 0 ? ("阅读进度 " + pct + "%") : "未读过";
        TextView pv = ModouUi.tv(this, progTxt, 12.5f, ui.accent);
        pv.setPadding(0, ModouUi.dp(this, 4), 0, 0);
        meta.addView(pv);

        h.addView(meta, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(h);

        String intro = book.optString("intro", "");
        if (!intro.isEmpty()) {
            TextView it = ModouUi.tv(this, intro, 13, ui.sub);
            it.setPadding(0, ModouUi.dp(this, 10), 0, 0);
            it.setLineSpacing(ModouUi.dp(this, 2), 1.2f);
            card.addView(it);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ModouUi.dp(this, 12);
        card.setLayoutParams(lp);
        return card;
    }

    /* ---------------- 操作（主按钮 + 次按钮分组） ---------------- */

    private View actions() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, 0, 0, ModouUi.dp(this, 12));

        // 第一行：主操作（整行宽）
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        int start = curIndex >= 0 ? curIndex : 0;
        row1.addView(btn(curIndex >= 0 ? "继续阅读" : "开始阅读", true,
                () -> ReaderLaunch.open(this, ModouBridge.shared(), book, start)), btnLp());
        if (book.optBoolean("preview", false)) {
            row1.addView(btn("加入书架", false, this::addToShelf), btnLp());
        }
        wrap.addView(row1);

        // 第二行：次操作（等宽、可多可少）
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, ModouUi.dp(this, 8), 0, 0);
        row2.addView(btn("编辑信息", false, this::editBook), btnLp());
        if (!isLocal()) {
            row2.addView(btn("刷新目录", false, this::refreshToc), btnLp());
            row2.addView(btn("缓存全书", false, this::cacheAll), btnLp());
            row2.addView(btn("换源", false, this::changeSource), btnLp());
        }
        wrap.addView(row2);
        return wrap;
    }

    /** 试读转正式：去掉 preview 标记，书架就会显示它 */
    private void addToShelf() {
        try {
            book.put("preview", false);
            book.put("addedAt", System.currentTimeMillis());
            book.put("updatedAt", System.currentTimeMillis());
            ModouStore.get(this).put("books", book.toString());
            ModouUi.toast(this, "已加入书架");
            if (bodyView != null) paint(bodyView);
        } catch (Exception e) {
            ModouUi.toast(this, "加入书架失败：" + e.getMessage());
        }
    }

    /** 按钮等宽平分：权重 1 + 左右留缝（margins 只能在 LayoutParams 上设） */
    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(ModouUi.dp(this, 3), 0, ModouUi.dp(this, 3), 0);
        return lp;
    }

    private TextView btn(String text, boolean primary, Runnable r) {
        TextView t = ModouUi.tv(this, text, 14, primary ? 0xFFFFFFFF : ui.text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ModouUi.dp(this, 8), ModouUi.dp(this, 10), ModouUi.dp(this, 8), ModouUi.dp(this, 10));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(primary ? ui.accent : ui.card);
        g.setCornerRadius(ModouUi.dp(this, 8));
        t.setBackground(g);
        t.setOnClickListener((x) -> r.run());
        return t;
    }

    /**
     * 刷新目录（原生）：把解析规则交给 SourceService，结果直接写回 books 记录。
     * 章节数变少时不清旧正文缓存（宁可留几章孤儿，也不删可能还在读的内容）。
     */
    private void refreshToc() {
        if (refreshingToc) return;
        if (!ensureFull()) {
            ModouUi.toast(this, "还没读到书籍信息，稍后再试");
            return;
        }
        // 书源抓取由常驻的 SourceService 负责，与界面 WebView 无关：
        // 以前这里判断「桥是否就绪」，桥改成按需创建后会把功能误挡在门外
        if (SourceService.get() == null) {
            ModouUi.toast(this, "书源服务还没启动，请重开应用");
            return;
        }
        refreshingToc = true;
        ModouUi.toast(this, "正在刷新目录…");
        SourceService.get().toc(book, new SourceService.Callback() {
            @Override
            public void onDone(JSONObject res) {
                refreshingToc = false;
                try {
                    if (res == null) throw new Exception("没有返回结果");
                    if (!res.optBoolean("ok", true)) {
                        throw new Exception(res.optString("error", "刷新失败"));
                    }
                    JSONArray chs = res.optJSONArray("chapters");
                    if (chs == null || chs.length() == 0) throw new Exception("目录是空的");
                    book.put("chapters", chs);
                    JSONObject last = chs.optJSONObject(chs.length() - 1);
                    if (last != null) book.put("lastChapter", last.optString("name", ""));
                    book.put("updatedAt", System.currentTimeMillis());
                    putBook();
                    ModouUi.toast(BookActivity.this, "目录已更新（" + chs.length() + " 章）");
                    if (bodyView != null) paint(bodyView);
                } catch (Exception e) {
                    String m = e.getMessage() == null ? "刷新失败" : e.getMessage();
                    ModouUi.toast(BookActivity.this, "刷新目录失败：" + m);
                }
            }
        });
    }

    /**
     * 缓存全书：逐章交给 SourceService 抓取，结果写进 chapters 缓存表。
     * 已缓存的章节直接跳过（不重复下载），所以「再点一次」只补没抓到的。
     * 进度条实时显示，可「取消」（停下本批次，已抓的保留）或「后台」（关弹层继续抓）。
     */
    private void cacheAll() {
        if (caching) {
            ModouUi.toast(this, "正在缓存中…");
            return;
        }
        if (isLocal()) {
            ModouUi.toast(this, "本地书已完整保存，无需缓存");
            return;
        }
        if (!ensureFull()) {
            ModouUi.toast(this, "还没读到书籍信息，稍后再试");
            return;
        }
        final JSONArray chs = chapters();
        if (chs.length() == 0) {
            ModouUi.toast(this, "还没有目录，请先刷新目录");
            return;
        }
        // 书源抓取由常驻的 SourceService 负责，与界面 WebView 无关：
        // 以前这里判断「桥是否就绪」，桥改成按需创建后会把功能误挡在门外
        if (SourceService.get() == null) {
            ModouUi.toast(this, "书源服务还没启动，请重开应用");
            return;
        }
        caching = true;
        cacheCancel = false;
        cacheTotal = chs.length();
        cacheDone = 0;
        cacheFail = 0;
        buildCacheSheet();
        cacheFrom(0);
    }

    private void buildCacheSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        cacheText = ModouUi.tv(this, "0 / " + cacheTotal, 13, ui.sub);
        cacheText.setPadding(0, 0, 0, ModouUi.dp(this, 8));
        box.addView(cacheText);

        cacheTrack = new LinearLayout(this);
        cacheTrack.setOrientation(LinearLayout.HORIZONTAL);
        cacheTrack.setBackgroundColor(ModouUi.alpha(ui.text, 0x18));
        int th = ModouUi.dp(this, 8);
        cacheTrack.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, th));
        cacheFill = new View(this);
        android.graphics.drawable.GradientDrawable fb = new android.graphics.drawable.GradientDrawable();
        fb.setColor(ui.accent);
        fb.setCornerRadius(th / 2f);
        cacheFill.setBackground(fb);
        cacheTrack.addView(cacheFill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0f));
        box.addView(cacheTrack);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        cacheDialog = ModouUi.sheet(this, "缓存全书", box, footer);
        footer.addView(ModouUi.sheetButtons(this, cacheDialog, "取消", "后台",
                () -> {
                    cacheCancel = true;
                    ModouUi.toast(this, "已停止缓存");
                },
                () -> {
                    /* 关弹层但继续在后台抓 */
                    ModouUi.toast(this, "正在后台缓存…");
                }));
    }

    private void cacheFrom(final int i) {
        if (!caching || cacheCancel) {
            finishCache();
            return;
        }
        if (i >= cacheTotal) {
            finishCache();
            ModouUi.toast(this, "已缓存 " + cacheDone + " 章"
                    + (cacheFail > 0 ? "，" + cacheFail + " 章失败" : ""));
            return;
        }
        // 已缓存的章节直接跳过，不重复下载
        if (ModouStore.get(this).chapterContent(bookId(), i) != null) {
            cacheDone++;
            updateCacheUi();
            cacheFrom(i + 1);
            return;
        }
        JSONObject ch = chapters().optJSONObject(i);
        if (ch == null || ch.optString("url", "").isEmpty()) {
            cacheDone++;
            updateCacheUi();
            cacheFrom(i + 1);
            return;
        }
        final int idx = i;
        SourceService.get().content(book, ch, 30000, new SourceService.Callback() {
            @Override
            public void onDone(JSONObject res) {
                if (!cacheCancel && caching) {
                    if (res != null && res.optBoolean("ok", false)) {
                        String html = res.optString("content", "");
                        if (!html.trim().isEmpty()) {
                            ModouStore.get(BookActivity.this).cacheChapter(bookId(), idx, html);
                            cacheDone++;
                        } else {
                            cacheFail++;
                        }
                    } else {
                        cacheFail++;
                    }
                    updateCacheUi();
                }
                cacheFrom(idx + 1);
            }
        });
    }

    private void updateCacheUi() {
        runOnUiThread(() -> {
            if (cacheText != null) {
                cacheText.setText(cacheDone + " / " + cacheTotal
                        + (cacheFail > 0 ? "（失败 " + cacheFail + "）" : ""));
            }
            if (cacheTrack != null && cacheFill != null) {
                int w = cacheTrack.getWidth();
                if (w > 0) {
                    float f = cacheTotal > 0 ? (float) cacheDone / cacheTotal : 1f;
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) cacheFill.getLayoutParams();
                    lp.width = (int) (w * f);
                    lp.weight = 0;
                    cacheFill.setLayoutParams(lp);
                }
            }
        });
    }

    private void finishCache() {
        caching = false;
        if (cacheDialog != null) {
            final Dialog d = cacheDialog;
            cacheDialog = null;
            runOnUiThread(d::dismiss);
        }
    }

    /**
     * 换源：按书名搜其他书源，结果按「同名书」归并；点一个就切到那个源，
     * 然后刷新详情 + 目录。与网页版换源同一套来源（SourceService.search）。
     */
    private void changeSource() {
        if (isLocal()) {
            ModouUi.toast(this, "本地书无法换源");
            return;
        }
        // 书源抓取由常驻的 SourceService 负责，与界面 WebView 无关：
        // 以前这里判断「桥是否就绪」，桥改成按需创建后会把功能误挡在门外
        if (SourceService.get() == null) {
            ModouUi.toast(this, "书源服务还没启动，请重开应用");
            return;
        }
        ModouUi.toast(this, "正在按书名搜索其他书源…");
        SourceService.get().search(book.optString("name", ""), null, 1,
                new SourceService.Callback() {
                    @Override
                    public void onDone(JSONObject res) {
                        List<SourcePick> picks = parseSourcePicks(res);
                        runOnUiThread(() -> {
                            if (picks.isEmpty()) {
                                ModouUi.toast(BookActivity.this, "没找到其他书源的同一本书");
                                return;
                            }
                            String[] labels = new String[picks.size()];
                            for (int i = 0; i < picks.size(); i++) {
                                labels[i] = picks.get(i).name + " · " + picks.get(i).sourceName;
                            }
                            ModouUi.sheetList(BookActivity.this, "选择书源", labels, w -> {
                                applySource(picks.get(w));
                            });
                        });
                    }
                });
    }

    private static List<SourcePick> parseSourcePicks(JSONObject res) {
        List<SourcePick> out = new ArrayList<>();
        if (res == null) return out;
        JSONArray groups = res.optJSONArray("groups");
        if (groups == null) return out;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            JSONArray hits = g.optJSONArray("hits");
            if (hits == null) continue;
            for (int j = 0; j < hits.length(); j++) {
                JSONObject hit = hits.optJSONObject(j);
                if (hit == null) continue;
                JSONObject b = hit.optJSONObject("book");
                if (b == null) continue;
                SourcePick p = new SourcePick();
                p.sourceUrl = hit.optString("sourceUrl", "");
                p.sourceName = hit.optString("sourceName", "");
                p.bookUrl = b.optString("bookUrl", "");
                p.name = b.optString("name", "");
                p.author = b.optString("author", "");
                p.coverUrl = b.optString("coverUrl", "");
                if (!p.sourceUrl.isEmpty() && !p.bookUrl.isEmpty()) out.add(p);
            }
        }
        return out;
    }

    private void applySource(SourcePick p) {
        ensureFull(); // 换源要写回 bookUrl + 新目录，手里必须是完整记录
        try {
            book.put("sourceUrl", p.sourceUrl);
            book.put("sourceName", p.sourceName);
            book.put("bookUrl", p.bookUrl);
            book.put("name", p.name);
            book.put("author", p.author);
            book.put("coverUrl", p.coverUrl);
            putBook();
        } catch (Exception e) {
            ModouUi.toast(this, "切换书源失败：" + e.getMessage());
            return;
        }
        ModouUi.toast(this, "已切换书源，正在刷新…");
        SourceService.get().info(book, new SourceService.Callback() {
            @Override
            public void onDone(JSONObject r) {
                if (r != null && r.optBoolean("ok", false)) applyInfo(r);
                SourceService.get().toc(book, new SourceService.Callback() {
                    @Override
                    public void onDone(JSONObject r2) {
                        runOnUiThread(() -> {
                            try {
                                if (r2 != null && r2.optBoolean("ok", false)) {
                                    JSONArray chs = r2.optJSONArray("chapters");
                                    if (chs != null && chs.length() > 0) {
                                        book.put("chapters", chs);
                                        JSONObject last = chs.optJSONObject(chs.length() - 1);
                                        if (last != null) {
                                            book.put("lastChapter", last.optString("name", ""));
                                        }
                                    }
                                }
                                book.put("updatedAt", System.currentTimeMillis());
                                putBook();
                                if (bodyView != null) paint(bodyView);
                                ModouUi.toast(BookActivity.this, "目录已更新");
                            } catch (Exception e) {
                                ModouUi.toast(BookActivity.this, "更新目录失败：" + e.getMessage());
                            }
                        });
                    }
                });
            }
        });
    }

    /** 把 info 结果里有的字段写回 book（空字段不覆盖） */
    private void applyInfo(JSONObject r) {
        try {
            putIf(r, "kind");
            putIf(r, "lastChapter");
            putIf(r, "intro");
            putIf(r, "coverUrl");
            putIf(r, "author");
            putIf(r, "name");
        } catch (Exception ignore) {
            /* 单字段失败不影响整体 */
        }
    }

    private void putIf(JSONObject r, String k) throws Exception {
        if (r.has(k)) {
            String v = r.optString(k, "");
            if (!v.isEmpty()) book.put(k, v);
        }
    }

    private static final class SourcePick {
        String sourceUrl;
        String sourceName;
        String bookUrl;
        String name;
        String author;
        String coverUrl;
    }

    /** 编辑书名/作者/封面；封面支持按书名在线搜（对齐网页版 fetchCovers） */
    private void editBook() {
        try {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 8), ModouUi.dp(this, 14), 0);

        final ImageView iv = new ImageView(this);
        int cw = ModouUi.dp(this, 84);
        iv.setLayoutParams(new LinearLayout.LayoutParams(cw, (int) (cw * 1.35f)));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        CoverLoader.load(iv, book.optString("coverUrl", ""), book.optString("name", ""));
        box.addView(iv);

        EditText name = ModouUi.input(this, "书名", book.optString("name", ""), 1);
        box.addView(name);
        EditText author = ModouUi.input(this, "作者", book.optString("author", ""), 1);
        box.addView(author);
        EditText cover = ModouUi.input(this, "封面 URL", book.optString("coverUrl", ""), 1);
        box.addView(cover);

        TextView search = ModouUi.tv(this, "在线搜封面", 14, ui.accent);
        search.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
        search.setOnClickListener((x) -> searchCovers(this, name.getText().toString().trim(), () -> {
            search.setText("搜索封面中…");
            search.setEnabled(false);
        }, urls -> {
            search.setText("在线搜封面");
            search.setEnabled(true);
            if (urls.isEmpty()) {
                ModouUi.toast(BookActivity.this, "没搜到可用封面，可手动填封面 URL");
                return;
            }
            showCoverCandidates(urls, (url) -> {
                cover.setText(url);
                CoverLoader.load(iv, url, name.getText().toString().trim());
            });
        }));
        box.addView(search);

        // 文件大小 / 本地保存位置：只读，长按路径可复制
        box.addView(BookFiles.infoCard(this, book));

        // 表单在滚动区里滚（字段多、键盘还会顶），「取消 / 保存」固定在滚动区外，
        // 怎么滚都看得见。
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        final Dialog d = ModouUi.sheet(this, "编辑信息", box, footer);
        // 同上：编辑信息是表单，点面板外不关（否则一敲键盘就把输入丢了）
        d.setCanceledOnTouchOutside(false);
        footer.addView(ModouUi.sheetButtons(this, d, "取消", "保存", null, () -> {
            try {
                book.put("name", name.getText().toString().trim());
                book.put("author", author.getText().toString().trim());
                book.put("coverUrl", cover.getText().toString().trim());
                putBook();
                ModouUi.toast(this, "已保存");
                paint(bodyView);
            } catch (Exception e) {
                ModouUi.toast(this, "保存失败");
            }
        }));
        } catch (Throwable t) {
            // 编辑信息弹窗构建失败绝不能静默崩回书架：摆出原因，方便定位
            ModouUi.toast(this, "编辑信息打开失败：" + t.getClass().getSimpleName());
        }
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
        gv.setAdapter(new android.widget.BaseAdapter() {
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
                ImageView im = new ImageView(BookActivity.this);
                im.setLayoutParams(new ViewGroup.LayoutParams(cw, ch));
                im.setScaleType(ImageView.ScaleType.CENTER_CROP);
                CoverLoader.load(im, urls.get(i), "");
                return im;
            }
        });
        // 网格放进 sheet 的内容区（以前是 sheet 建好再 setContentView，把卡片底和圆角
        // 整个盖掉了，深色下就是一块白板）
        int rows = (urls.size() + 2) / 3;
        gv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                rows * ch + Math.max(0, rows - 1) * ModouUi.dp(this, 8)));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(gv);
        final Dialog d = ModouUi.sheet(this, "选择封面", box);
        gv.setOnItemClickListener((a, v, i, id) -> {
            d.dismiss();
            if (cb != null) cb.on(urls.get(i));
        });
    }

    private interface CoverPick {
        void on(String url);
    }

    /**
     * 按书名搜封面：**书源搜索 + Bing 图片搜索两路合并兜底**，结果去重后回调。
     *
     * 为什么加 Bing 兜底：书源搜索结果的 coverUrl 经常是空的（很多书源搜索接口
     * 只回书名/作者/最新章节，不带封面字段），老逻辑因此「点一下立刻没搜到」——
     * 不是没搜，是根本没东西可搜。对齐 Legado 的做法：书源没有就给一次网络图片搜索。
     *
     * started 在真正开始搜索前回调（主线程），调用方可把按钮切成「搜索中…」；
     * 两路都在 15 秒内收敛（书源 60s 超时窗口内提前收敛、Bing 8s 超时），回调只发一次。
     * 复用于书架内的编辑（ShelfActivity.pickCover）与书籍详情页（editBook）。
     */
    static void searchCovers(Activity a, String name, CoverCb cb) {
        searchCovers(a, name, null, cb);
    }

    static void searchCovers(Activity a, String name, Runnable started, CoverCb cb) {
        final List<String> src = new ArrayList<>();
        final List<String> web = new ArrayList<>();
        final boolean[] srcDone = {false};
        final boolean[] webDone = {false};
        if (started != null) a.runOnUiThread(started);

        // 两路都收敛后合并回调一次：书源优先，Bing 补位，去重、最多 12 张
        final java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable maybe = new Runnable() {
            @Override
            public void run() {
                if (!srcDone[0] || !webDone[0]) return;
                if (!sent.compareAndSet(false, true)) return;
                final List<String> out = new ArrayList<>();
                for (String u : src) {
                    if (u != null && !u.isEmpty() && !out.contains(u)) out.add(u);
                }
                for (String u : web) {
                    if (u != null && !u.isEmpty() && !out.contains(u) && out.size() < 12) out.add(u);
                }
                a.runOnUiThread(() -> cb.on(out));
            }
        };

        // 路 1：启用书源搜（结果结构 res.groups[].hits[].book.coverUrl）
        SourceService svc = SourceService.get();
        if (svc == null) {
            srcDone[0] = true;
        } else {
            svc.search(name, null, 1, new SourceService.Callback() {
                @Override
                public void onDone(JSONObject res) {
                    JSONArray groups = res == null ? null : res.optJSONArray("groups");
                    if (groups != null) {
                        for (int i = 0; i < groups.length(); i++) {
                            JSONObject grp = groups.optJSONObject(i);
                            if (grp == null) continue;
                            JSONArray hits = grp.optJSONArray("hits");
                            if (hits == null) continue;
                            for (int j = 0; j < hits.length(); j++) {
                                JSONObject hit = hits.optJSONObject(j);
                                if (hit == null) continue;
                                JSONObject b = hit.optJSONObject("book");
                                String u = b == null ? "" : b.optString("coverUrl", "");
                                if (!u.isEmpty() && !src.contains(u)) src.add(u);
                            }
                        }
                    }
                    srcDone[0] = true;
                    maybe.run();
                }
            });
        }

        // 路 2：Bing 图片搜索兜底（后台线程，8s 超时）
        Thread t = new Thread(() -> {
            try {
                web.addAll(bingCovers(name));
            } catch (Exception ignore) {
                /* 兜底失败不阻塞书源结果 */
            }
            webDone[0] = true;
            maybe.run();
        }, "modou-cover");
        t.setDaemon(true);
        t.start();

    }

    /** 按书名从 Bing 图片搜索抓候选封面（零依赖：纯 HttpURLConnection + 正则） */
    static List<String> bingCovers(String name) throws Exception {
        List<String> out = new ArrayList<>();
        if (name == null || name.trim().isEmpty()) return out;
        String q = java.net.URLEncoder.encode(name.trim(), "UTF-8");
        java.net.URL u = new java.net.URL(
                "https://www.bing.com/images/search?q=" + q + "&qft=+filterui:imagesize-large");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        try {
            int code = c.getResponseCode();
            if (code != 200) return out;
            // m="{...murl:"..."...}"：HTML 里是 &quot; 转义，个别时候是原生双引号，两种都试
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "murl&quot;:&quot;(.*?)&quot;|murl\":\"(.*?)\"");
            StringBuilder sb = new StringBuilder();
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                int cap = 4 * 1024 * 1024;
                int total = 0;
                while ((n = in.read(buf)) > 0 && total < cap) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                    total += n;
                }
            }
            java.util.regex.Matcher m = p.matcher(sb.toString());
            while (m.find() && out.size() < 9) {
                String url = m.group(1) != null ? m.group(1) : m.group(2);
                if (url == null) continue;
                url = url.replace("\\u0026", "&");
                url = unescapeHtml(url);
                if (!url.startsWith("http")) continue;
                if (!out.contains(url)) out.add(url);
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    static String unescapeHtml(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
    }

    /** 封面搜索回调：回调一个候选 URL 列表（可能为空） */
    public interface CoverCb {
        void on(List<String> urls);
    }

    /* ---------------- 目录 ---------------- */

    private JSONArray chapters() {
        JSONArray a = book.optJSONArray("chapters");
        return a == null ? new JSONArray() : a;
    }

    private View tocCard() {
        LinearLayout card = ui.card(this);
        JSONArray chs = chapters();
        countView = ModouUi.tv(this, "", 12, ui.sub);
        card.addView(countView);

        tocView = new ListView(this);
        tocView.setBackgroundColor(Color.TRANSPARENT);
        tocView.setDividerHeight(1);
        tocView.setAdapter(new TocAdapter());
        tocView.setOnItemClickListener((a, v, i, id) ->
                ReaderLaunch.open(this, ModouBridge.shared(), book, i));
        card.addView(tocView);

        // 必须先建「加载更多」再量高度：measureToc 里要控制它的显隐。
        // 0.6.106 重写时顺序颠倒，measureToc 打在还没创建的 moreBtn 上直接 NPE——
        // 有目录的书一点开详情就崩（0.6.106/107 书卡点不开，这条路径从没跑过）
        moreBtn = ModouUi.tv(this, "加载更多", 14, ui.accent);
        moreBtn.setGravity(Gravity.CENTER);
        moreBtn.setPadding(0, ModouUi.dp(this, 12), 0, ModouUi.dp(this, 4));
        moreBtn.setOnClickListener((x) -> {
            shown += PAGE;
            ((TocAdapter) tocView.getAdapter()).notifyDataSetChanged();
            measureToc(chs.length());
        });
        card.addView(moreBtn);

        measureToc(chs.length());
        return card;
    }

    /** ListView 嵌在 ScrollView 里：按行数算出总高度再写死 LayoutParams */
    private void measureToc(int total) {
        int n = Math.min(shown, total);
        if (countView != null) {
            countView.setText(total > 0
                    ? "第 1 - " + n + " 章 / 共 " + total + " 章"
                    : (fullLoaded ? "还没有目录" : "正在读取目录…"));
        }
        if (moreBtn != null) moreBtn.setVisibility(n >= total ? View.GONE : View.VISIBLE);
        if (n == 0) return;
        View one = new TocAdapter().getView(0, null, tocView);
        one.measure(View.MeasureSpec.makeMeasureSpec(
                        getResources().getDisplayMetrics().widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = one.getMeasuredHeight() * n;
        tocView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
    }

    private final class TocAdapter extends android.widget.BaseAdapter {
        @Override
        public int getCount() {
            return Math.min(shown, chapters().length());
        }

        @Override
        public Object getItem(int i) {
            return chapters().optJSONObject(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            JSONArray chs = chapters();
            JSONObject c = chs.optJSONObject(i);
            String name = c == null ? "(未命名章节)" : c.optString("name", "(未命名章节)");
            TextView t = ModouUi.tv(BookActivity.this, (i + 1) + ". " + name, 14,
                    i == curIndex ? ui.accent : ui.text);
            if (i == curIndex) t.setTypeface(t.getTypeface(), Typeface.BOLD);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(ModouUi.dp(BookActivity.this, 4), ModouUi.dp(BookActivity.this, 9),
                    ModouUi.dp(BookActivity.this, 4), ModouUi.dp(BookActivity.this, 9));
            return t;
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
