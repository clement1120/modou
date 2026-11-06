package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
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
public class BookActivity extends Activity implements SourceSwap.Host {

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
    /** 目录单行高度（含分隔线）：快速定位时用它换算「第 i 章在第几像素」 */
    private int tocRowH;
    /** 搜索定位命中的章节（临时高亮），与 curIndex 区分 */
    private int hiIndex = -1;
    /** 内容容器：编辑信息后原地重绘（比 recreate 快，也不会丢滚动位置） */
    private LinearLayout bodyView;
    private ScrollView scroller;
    /** 目录卡片：完整记录回来后就地重建它（其余卡片不动） */
    private View tocCardRef;
    /** 右下角悬浮「阅读」 */
    private View fab;
    /** 整页的 FrameLayout：完整记录回来时要往它上面补悬浮按钮 */
    private FrameLayout shellRef;

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
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ModouUi.dp(this, 40), ModouUi.dp(this, 40));
            blp.topMargin = ModouUi.dp(this, 16);
            miss.addView(ModouUi.backBtn(this, () -> finish()), blp);
            setContentView(miss);
            return;
        }
        prog = load(store.get("progress", id));
        curIndex = prog == null ? -1 : prog.optInt("index", -1);

        // 整页是一个 FrameLayout：中间是可滚动的详情，右下角浮一枚「阅读」。
        // 悬浮按钮不参与滚动，滑到哪都能直接开读；内容区底部留出 92dp 让它压不到正文。
        FrameLayout shell = new FrameLayout(this);
        shellRef = shell;
        // 光斑底衬挂在最外层；root 必须留透明，否则一刀把光切平
        shell.setBackground(ModouUi.backdrop(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ModouUi.dp(this, 16), statusBarPad(),
                ModouUi.dp(this, 16), 0);
        root.addView(pageHead());

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, ModouUi.dp(this, 112));
        sc.addView(body);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroller = sc;

        shell.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (totalChapters() > 0) {
            fab = readFab();
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.gravity = Gravity.BOTTOM | Gravity.END;
            flp.setMargins(0, 0, ModouUi.dp(this, 16), ModouUi.dp(this, 20));
            shell.addView(fab, flp);
        }

        setContentView(shell);
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
                // 整页重绘（字数/更新时间/最新章节/总章数都来自完整记录），
                // 重绘后把滚动位置放回去 —— 只换目录卡片会让上半页一直停在轻量记录的旧值上
                int y = scroller == null ? 0 : scroller.getScrollY();
                if (body != null) paint(body);
                if (scroller != null) scroller.post(() -> scroller.scrollTo(0, y));
                if (fab == null && totalChapters() > 0) {
                    // 轻量记录里章数为 0 时没建悬浮按钮：现在知道有目录了，补上
                    fab = readFab();
                    FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    flp.gravity = Gravity.BOTTOM | Gravity.END;
                    flp.setMargins(0, 0, ModouUi.dp(this, 16), ModouUi.dp(this, 20));
                    if (shellRef != null) shellRef.addView(fab, flp);
                }
                // 完整记录到手才有 bookUrl / 章节数：目录超过 1 小时没刷过就静默补一次
                maybeAutoRefreshToc();
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

    /**
     * 异步写回书籍记录（只用于「自动/后台刷新目录」这种用户没在等的场景）。
     *
     * <p>一条记录里带着几千章目录，`toString()` + SQLite 同步写加起来 100~300ms。
     * 手动点「刷新目录」时用户有预期，等这一下没问题；但目录 TTL 自动刷新是
     * **进书时顺手做的**，用户正等着看详情页——那就成了「点进去顿一下」。
     * 所以这里把序列化和写库整段丢到 IO 线程。
     *
     * <p>不做深拷贝：此刻主线程刚改完字段，紧接着几乎不可能再改同一本书；
     * 万一真撞上，后台写失败会被 catch，下一次写覆盖。
     */
    private void putBookAsync() {
        if (!fullLoaded) {
            // 手里只有轻量记录：要先读回完整记录再合并，这条路径罕见，直接同步做
            putBook();
            return;
        }
        final JSONObject snap = book;
        final String id = book.optString("id", "");
        TaskPools.io().execute(() -> {
            try {
                store.put("books", snap.toString());
            } catch (Throwable t) {
                android.util.Log.w("BookActivity", "async putBook failed: " + id, t);
            }
        });
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
        err.setPadding(ModouUi.topInset(this), statusBarPad() + ModouUi.dp(this, 16),
                ModouUi.topInset(this), ModouUi.dp(this, 20));
        TextView h = ModouUi.tv(this, "详情页打开出错", 18, ui.text);
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        err.addView(h);
        TextView msg = ModouUi.tv(this,
                t.getClass().getSimpleName() + ": " + t.getMessage() + "\n\n"
                        + android.util.Log.getStackTraceString(t), 11, ui.sub);
        msg.setTextIsSelectable(true);
        err.addView(msg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ModouUi.dp(this, 40), ModouUi.dp(this, 40));
        blp.topMargin = ModouUi.dp(this, 16);
        err.addView(ModouUi.backBtn(this, () -> finish()), blp);
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
        body.addView(hero());
        View tags = tagRow();
        if (tags != null) body.addView(tags);
        View actions = actionRow();
        // iOS 连续分组卡自身不留底部 padding（白卡要紧贴内容），间距改由外边距给；
        // 墨读 / M3 仍是原来 actionRow 内部 14dp paddingBottom，逐像素不变。
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (ui.spec.bookActionStyle == 1) alp.bottomMargin = ModouUi.dp(this, 14);
        body.addView(actions, alp);
        body.addView(progressCard());
        View intro = introCard();
        if (intro != null) body.addView(intro);
        tocCardRef = tocCard();
        body.addView(tocCardRef);
    }

    private String bookId() {
        return book.optString("id", "");
    }

    private boolean isLocal() {
        return "local://".equals(book.optString("sourceUrl", ""));
    }

    /** 矢量图统一染色（图标资源本身是白色 path） */
    private android.graphics.drawable.Drawable tinted(int resId, int color) {
        android.graphics.drawable.Drawable d =
                getResources().getDrawable(resId, getTheme()).mutate();
        d.setTint(color);
        return d;
    }

    /* ---------------- 0.9.171 书详情三语言构图辅助 ----------------
       所有取值只读 ui.spec；墨读 bookStyle=0 时全部落到原写死值，逐像素零回归。
       标签、书源胶囊、目录条、动作块、FAB 共用这几个工厂，改规格即三套一起变。 */

    /** 标签 / 书源胶囊共用的背景：墨读全圆浅灰、iOS systemFill 全圆、M3 8dp assist chip 描边。 */
    private android.graphics.drawable.Drawable bookChipBg() {
        DesignSpec sp = ui.spec;
        int fill;
        switch (sp.bookTagFill) {
            case 1:  fill = sp.sysFill2; break;
            case 2:  fill = sp.surfaceContainerHigh; break;
            default: fill = ModouUi.alpha(ui.text, 0x0E);
        }
        return sp.bookTagStroke
                ? ModouUi.squircle(this, fill, sp.bookTagCornerDp, sp.divider, 0.5f)
                : ModouUi.round(fill, sp.bookTagCornerDp);
    }

    /** 封面未加载时露出的占位底色。 */
    private int bookCoverPlaceColor() {
        switch (ui.spec.bookCoverPlace) {
            case 1:  return ui.spec.sysFill3;
            case 2:  return ui.spec.surfaceContainerHigh;
            default: return 0xFFD8CFBB;
        }
    }

    /** 书名 / 统计数值的字重：墨读衬线粗体、iOS 无衬线粗体、M3 sans-medium。 */
    private void applyBookTitleFace(TextView t) {
        switch (ui.spec.bookTitleFace) {
            case 1:  t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); break;
            case 2:  t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); break;
            default: t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        }
    }

    /** 动作块底色：墨读 ui.card、M3 tonal；iOS 交给外层连续分组卡（返回 null）。 */
    private android.graphics.drawable.Drawable bookActionBlockBg() {
        DesignSpec sp = ui.spec;
        if (sp.bookActionStyle == 1) return null;
        int fill = sp.bookActionBg == 2 ? sp.surfaceContainerHigh : ui.card;
        return ModouUi.squircle(this, fill, sp.bookActionCornerDp);
    }

    /* ---------------- 顶部：返回 + 更多 ---------------- */

    private View pageHead() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, ModouUi.dp(this, 2), 0, ModouUi.dp(this, 2));

        // 纯圆形图标按钮：箭头由 ModouGlyph 以圆钮中心为原点绘制，
        // 不再用「‹ 返回」这种字形基线方案（字形高度决定基线，永远偏一点）。
        head.addView(roundBtn(ModouGlyph.BACK, 40, v -> finish()));

        View gap = new View(this);
        head.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));

        // 右上角「更多」：与返回同一规格的正圆，图标是竖三点（⋮）——
        // 与书架、书源、搜索页完全同一颗，不再用横三点自成一套。
        head.addView(roundBtn(ModouGlyph.MORE_V, 40, v -> openMore()));
        return head;
    }

    /** 40dp 正圆细线图标钮：规格与全站顶栏同源（{@link ModouUi#topBtn}） */
    private View roundBtn(int glyph, int boxDp, View.OnClickListener c) {
        return ModouUi.topBtn(this, glyph, boxDp, c);
    }

    private void openMore() {
        final boolean onShelf = !book.optBoolean("preview", false);
        List<String> items = new ArrayList<>();
        items.add("编辑信息");
        if (!isLocal()) {
            items.add("刷新目录");
            items.add("缓存全书");
        }
        if (onShelf) items.add("移出书架");
        ModouUi.sheetList(this, "更多操作", items.toArray(new String[0]), w -> {
            String pick = items.get(w);
            if ("编辑信息".equals(pick)) editBook();
            else if ("刷新目录".equals(pick)) refreshToc();
            else if ("缓存全书".equals(pick)) cacheAll();
            else if ("移出书架".equals(pick)) removeFromShelf();
        });
    }

    /* ---------------- 书信息（封面 + 书名 + 作者 + 书源） ---------------- */

    private View hero() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(ModouUi.dp(this, 2), ModouUi.dp(this, 6),
                ModouUi.dp(this, 2), ModouUi.dp(this, 10));

        DesignSpec bs = ui.spec;
        ImageView iv = new ImageView(this);
        int w = ModouUi.dp(this, bs.bookCoverWDp);
        iv.setLayoutParams(new FrameLayout.LayoutParams(w, Math.round(w * 1.38f)));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(ModouUi.squircle(this, bookCoverPlaceColor(), bs.bookCoverCornerDp));
        iv.setClipToOutline(true);
        iv.setElevation(ModouUi.dp(this, bs.bookCoverElevDp));
        CoverLoader.load(iv, book.optString("coverUrl", ""), book.optString("name", ""));
        // 封面包一层 FrameLayout：左下角放「换封面」小标，整块可点 → 按书名在线搜封面
        FrameLayout coverWrap = new FrameLayout(this);
        coverWrap.setLayoutParams(new LinearLayout.LayoutParams(w, Math.round(w * 1.38f)));
        coverWrap.addView(iv);
        TextView swap = ModouUi.tv(this, "换封面", 9.5f, 0xFFFFFFFF);
        swap.setTypeface(swap.getTypeface(), Typeface.BOLD);
        android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
        sg.setColor(ModouUi.alpha(ui.accent, 0xCC));
        float sr = ModouUi.dp(this, 999);
        sg.setCornerRadii(new float[]{0, 0, 0, 0, sr, sr, sr, sr});
        swap.setBackground(sg);
        swap.setPadding(ModouUi.dp(this, 6), ModouUi.dp(this, 3),
                ModouUi.dp(this, 6), ModouUi.dp(this, 3));
        swap.setTranslationY(-ModouUi.dp(this, 1));
        FrameLayout.LayoutParams swp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swp.gravity = Gravity.BOTTOM | Gravity.START;
        coverWrap.addView(swap, swp);
        coverWrap.setOnClickListener(v -> pickCover(iv));
        row.addView(coverWrap);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 2), 0, 0);

        TextView t = ModouUi.tv(this, book.optString("name", ""), bs.bookTitleSp, ui.text);
        applyBookTitleFace(t);
        t.setLineSpacing(ModouUi.dp(this, 1), 1.08f);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(t);

        // 作者独立一行且可点：点一下按作者去搜书，找同一个作者的其他书
        String author = book.optString("author", "").trim();
        if (!author.isEmpty()) {
            TextView au = ModouUi.tv(this, author + " ›", 13.5f, ui.accent);
            au.setPadding(0, ModouUi.dp(this, 8), 0, 0);
            au.setOnClickListener(v -> SearchActivity.launchByAuthor(this, author));
            meta.addView(au);
        }

        // 书源做成小胶囊：它是「这本书从哪来」的唯一标识，值一行灰字的最后一段太容易看漏
        String src = isLocal() ? "本地导入"
                : book.optString("sourceName", book.optString("sourceUrl", "—"));
        if (!src.isEmpty()) {
            TextView s = ModouUi.tv(this, src, 11.5f, ui.sub);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(ModouUi.dp(this, 8), ModouUi.dp(this, 3),
                    ModouUi.dp(this, 8), ModouUi.dp(this, 3));
            s.setBackground(bookChipBg());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = ModouUi.dp(this, 8);
            meta.addView(s, slp);
        }

        row.addView(meta, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * 点封面 → 按书名在线搜封面，挑一张就地换上。
     * 选中的封面写回 book 并落库（putBook），书架下次 onResume 会因 coverUrl 变化而重建、
     * 直接拿到新封面；CoverLoader 遇新 url 会自动下载并落磁盘缓存，无需手动处理。
     */
    private void pickCover(ImageView iv) {
        String name = book.optString("name", "").trim();
        if (name.isEmpty()) {
            ModouUi.toast(this, "书名为空，无法搜索封面");
            return;
        }
        searchCovers(this, name, null, urls -> {
            if (urls.isEmpty()) {
                ModouUi.toast(this, "没搜到可用封面，可去「⋯ → 编辑信息」手动填");
                return;
            }
            showCoverCandidates(urls, (url) -> {
                try {
                    book.put("coverUrl", url);
                    putBook();
                    CoverLoader.load(iv, url, name);
                    ModouUi.toast(this, "已更换封面");
                } catch (Exception e) {
                    ModouUi.toast(this, "更换封面失败");
                }
            });
        });
    }

    /** 标签行：分类 / 字数 / 更新时间 —— 书源给了才画，缺的跳过不留空位 */
    private View tagRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(ModouUi.dp(this, 2), 0, ModouUi.dp(this, 2), ModouUi.dp(this, 12));
        boolean any = false;

        String kind = book.optString("kind", "").trim();
        if (kind.length() > 8) kind = kind.substring(0, 8);
        if (!kind.isEmpty()) {
            row.addView(tag(kind));
            any = true;
        }
        String wc = wordText();
        if (!wc.isEmpty()) {
            row.addView(tag(wc), tagLp(any));
            any = true;
        }
        String up = book.optString("updateTime", "").trim();
        if (up.length() > 12) up = up.substring(0, 12);
        if (!up.isEmpty()) {
            row.addView(tag(up), tagLp(any));
            any = true;
        }
        return any ? row : null;
    }

    /**
     * 标签胶囊：与书名下「本地导入」那枚完全同一套样式（浅灰底 + 全圆角），
     * 之前这里是 6dp 小圆角 + 4% 墨色，和上面的胶囊放在一屏里像两种控件。
     */
    private TextView tag(String text) {
        TextView t = ModouUi.tv(this, text, 11.5f, ui.sub);
        t.setSingleLine(true);
        t.setPadding(ModouUi.dp(this, 9), ModouUi.dp(this, 4),
                ModouUi.dp(this, 9), ModouUi.dp(this, 4));
        t.setBackground(bookChipBg());
        return t;
    }

    private LinearLayout.LayoutParams tagLp(boolean notFirst) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (notFirst) lp.leftMargin = ModouUi.dp(this, 6);
        return lp;
    }

    /** 书源给的字数可能是「1656000」也可能是「165.6万字」，两种都按人读的方式显示 */
    private String wordText() {
        String raw = book.optString("wordCount", "").trim();
        if (raw.isEmpty()) return "";
        if (raw.matches("\\d{4,}")) {
            try {
                long n = Long.parseLong(raw);
                if (n >= 10000) {
                    return trimZero(n / 10000f) + " 万字";
                }
                return n + " 字";
            } catch (Exception ignore) {
                return raw;
            }
        }
        return raw;
    }

    private static String trimZero(float v) {
        String s = String.format(java.util.Locale.CHINA, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /* ---------------- 四个动作（图标 + 文字，等宽一行） ---------------- */

    private View actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 0.9.171：iOS 把四个动作收进一张 Inset Grouped 连续分组卡（一张白底 +
        // 竖向细线），墨读 / M3 仍是四块各自独立的卡片。
        boolean grouped = ui.spec.bookActionStyle == 1;
        if (grouped) {
            row.setPadding(0, 0, 0, 0);   // 间距改由 paint() 里的 bottomMargin 给
            row.setBackground(ModouUi.squircle(this, ui.card, ui.spec.bookActionCornerDp));
        } else {
            row.setPadding(0, 0, 0, ModouUi.dp(this, 14));
        }

        boolean onShelf = !book.optBoolean("preview", false);
        View a1 = action(R.drawable.ic_rd_bmk, onShelf ? "已在书架" : "放入书架",
                onShelf ? null : this::addToShelf);
        View a2 = action(R.drawable.ic_rd_toc, "查看目录", this::scrollToToc);
        View swap = action(R.drawable.ic_book_swap, "换源", isLocal() ? null : this::changeSource);
        if (isLocal()) swap.setAlpha(0.35f); // 本地书没有别的源，别给出一个点了没反应的按钮
        View a4 = action(R.drawable.ic_book_history, "阅读记录", this::showReadingRecord);

        row.addView(a1, actLp());
        if (grouped) row.addView(actionSplit());
        row.addView(a2, actLp());
        if (grouped) row.addView(actionSplit());
        row.addView(swap, actLp());
        if (grouped) row.addView(actionSplit());
        row.addView(a4, actLp());
        return row;
    }

    /** iOS 连续分组卡里的竖向细线：0.5px、上下留白，别把整格切死。 */
    private View actionSplit() {
        View v = new View(this);
        v.setBackgroundColor(ui.spec.divider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.max(1, ModouUi.dp(this, 0.5f)), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.topMargin = ModouUi.dp(this, 12);
        lp.bottomMargin = ModouUi.dp(this, 12);
        v.setLayoutParams(lp);
        return v;
    }

    private View action(int iconRes, String label, Runnable r) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, ModouUi.dp(this, 11), 0, ModouUi.dp(this, 10));
        android.graphics.drawable.Drawable blockBg = bookActionBlockBg();
        if (blockBg != null) b.setBackground(blockBg);

        ImageView iv = new ImageView(this);
        int s = ModouUi.dp(this, 22);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setImageDrawable(tinted(iconRes, r == null ? ModouUi.alpha(ui.sub, 0x88) : ui.text));
        b.addView(iv);

        TextView t = ModouUi.tv(this, label, 11, r == null ? ui.sub : ui.text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ModouUi.dp(this, 5), 0, 0);
        b.addView(t);

        if (r != null) b.setOnClickListener(v -> r.run());
        return b;
    }

    private LinearLayout.LayoutParams actLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        // iOS 连续分组卡靠细线分隔，不需要每块再留左右间距。
        if (ui.spec.bookActionStyle != 1) {
            lp.setMargins(ModouUi.dp(this, 3), 0, ModouUi.dp(this, 3), 0);
        }
        return lp;
    }

    private void scrollToToc() {
        if (scroller == null || tocCardRef == null) return;
        scroller.post(() -> scroller.smoothScrollTo(0, tocCardRef.getTop()));
    }

    /* ---------------- 阅读进度 / 最新章节 / 总章数 / 未读 ---------------- */

    private int totalChapters() {
        int total = book.optInt("chapterCount", 0);
        JSONArray chs = chapters();
        if (chs.length() > 0) total = chs.length();
        return total;
    }

    private View progressCard() {
        LinearLayout card = ui.card(this);
        int total = totalChapters();
        int read = curIndex >= 0 ? curIndex + 1 : 0;
        int pct = total > 0 ? Math.round((read / (float) total) * 100) : 0;

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.BOTTOM);
        // 「0%」与「345 章」是一组数据：两段都用 wrap_content + 固定 10dp 间距。
        // 之前第二格用 weight=1，被第一格「阅读进度」四个字的宽度撑开，中间空了近 40dp。
        row1.addView(stat("阅读进度", curIndex >= 0 ? pct + "%" : "未读"), statLp(true));
        row1.addView(stat("未读", total > 0 ? Math.max(0, total - read) + " 章" : "—"),
                statLpFollow());
        card.addView(row1);

        String last = book.optString("lastChapter", "").trim();
        if (last.isEmpty()) {
            JSONArray chs = chapters();
            JSONObject c = chs.optJSONObject(chs.length() - 1);
            if (c != null) last = c.optString("name", "");
        }
        if (!last.isEmpty()) {
            card.addView(hairline());
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, ModouUi.dp(this, 12), 0, ModouUi.dp(this, 12));
            row.addView(ModouUi.tv(this, "最新章节", 11.5f, ui.sub));
            TextView v = ModouUi.tv(this, last, 12.5f, ui.text);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setGravity(Gravity.END);
            row.addView(v, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(row);
        }

        card.addView(hairline());
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        row3.setPadding(0, ModouUi.dp(this, 12), 0, ModouUi.dp(this, 12));
        row3.addView(ModouUi.tv(this, "总章数", 11.5f, ui.sub));
        row3.addView(ModouUi.tv(this, total > 0 ? total + " 章" : "正在读取…",
                12.5f, ui.text), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row3);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ModouUi.dp(this, 12);
        card.setLayoutParams(lp);
        return card;
    }

    /**
     * 极细浅灰分隔线：原来用 theme.line（不透明）画，压在浅色卡上就是一道黑线，
     * 整张卡被切成表格。现在透明度降到 7%，高度 1px，且不缩进 —— 断口反而更碎。
     */
    private View hairline() {
        View v = new View(this);
        if (ui.spec.bookCardHairline) {
            v.setBackgroundColor(ModouUi.alpha(ui.text, 0x0D));
            v.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        } else {
            // M3 Filled Card 不画分隔线，靠 surface 分层；这里只留一段呼吸间距。
            v.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(this, 8)));
        }
        return v;
    }

    private View stat(String label, String value) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
        v.addView(ModouUi.tv(this, label, 11.5f, ui.sub));
        TextView val = ModouUi.tv(this, value, 15, ui.text);
        applyBookTitleFace(val);
        val.setPadding(0, ModouUi.dp(this, 3), 0, 0);
        v.addView(val);
        return v;
    }

    private LinearLayout.LayoutParams statLp(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!first) lp.leftMargin = ModouUi.dp(this, 24);
        return lp;
    }

    private LinearLayout.LayoutParams statLpWeight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    /** 紧跟在前一格之后：只留 10dp，让两个数字读成一个整体 */
    private LinearLayout.LayoutParams statLpFollow() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ModouUi.dp(this, 10);
        return lp;
    }

    /** 阅读记录：这本书上次读到哪、什么时候读的；可从头开始或直接续读 */
    private void showReadingRecord() {
        if (prog == null) {
            ModouUi.toast(this, "这本书还没有阅读记录");
            return;
        }
        int total = totalChapters();
        int idx = prog.optInt("index", 0);
        JSONArray chs = chapters();
        JSONObject c = chs.optJSONObject(idx);
        String name = c == null ? "" : c.optString("name", "");
        int pct = total > 0 ? Math.round(((idx + 1) / (float) total) * 100) : 0;
        long at = prog.optLong("at", prog.optLong("updatedAt", 0));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(this, 14);
        box.setPadding(p, 0, p, 0);
        String when = at > 0
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(new java.util.Date(at))
                : "时间未知";
        String[] rows = {
                "读到：第 " + (idx + 1) + " 章" + (name.isEmpty() ? "" : " · " + name),
                "进度：" + pct + "%（共 " + (total > 0 ? total : "?") + " 章）",
                "时间：" + when,
        };
        for (String s : rows) {
            TextView t = ModouUi.tv(this, s, 13.5f, ui.text);
            t.setPadding(0, ModouUi.dp(this, 8), 0, 0);
            box.addView(t);
        }
        final Dialog d = ModouUi.sheet(this, "阅读记录", box);
        box.addView(ModouUi.sheetButtons(this, d, "从头开始", "继续阅读",
                () -> ReaderLaunch.open(this, ModouBridge.shared(), book, 0),
                () -> ReaderLaunch.open(this, ModouBridge.shared(), book, idx)));
    }

    /* ---------------- 简介 ---------------- */

    private View introCard() {
        String intro = Paras.plain(book.optString("intro", ""));
        if (intro.isEmpty()) return null;
        LinearLayout card = ui.card(this);

        TextView h = ModouUi.tv(this, "简介", 13.5f, ui.text);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(h);

        final TextView it = ModouUi.tv(this, intro, 13.5f, ui.sub);
        it.setPadding(0, ModouUi.dp(this, 8), 0, 0);
        it.setLineSpacing(ModouUi.dp(this, 3), 1.28f);
        card.addView(it);

        // 长的简介先收 6 行：一屏里「简介 + 目录」才是用户真正要扫的两块，
        // 让简介把目录挤到屏幕外，等于每次都要先滑过一整页字
        if (intro.length() > 150) {
            it.setMaxLines(6);
            it.setEllipsize(TextUtils.TruncateAt.END);
            final boolean[] open = {false};
            final TextView toggle = ModouUi.tv(this, "展开全部", 12.5f, ui.accent);
            toggle.setPadding(0, ModouUi.dp(this, 8), 0, 0);
            toggle.setOnClickListener(v -> {
                open[0] = !open[0];
                it.setMaxLines(open[0] ? Integer.MAX_VALUE : 6);
                it.setEllipsize(open[0] ? null : TextUtils.TruncateAt.END);
                toggle.setText(open[0] ? "收起" : "展开全部");
            });
            card.addView(toggle);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ModouUi.dp(this, 12);
        card.setLayoutParams(lp);
        return card;
    }

    /* ---------------- 底部悬浮「阅读」 ---------------- */

    private View readFab() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(ModouUi.dp(this, 20), ModouUi.dp(this, 13),
                ModouUi.dp(this, 22), ModouUi.dp(this, 13));
        // 墨读：与卡片同色系 + 柔和投影的「白色浮片」，真胶囊（圆角 = 高度一半），
        // 靠描边 + 朱砂文字保持可点性；不用超椭圆，否则描边落到边界外被裁一角。
        // 0.9.171：iOS 26 改系统蓝填充胶囊 + 白字；M3 Expressive 改 16dp 圆角
        // tonal 容器（不是全圆、不描边、elevation 只 3dp），三套主操作各不相同。
        int fabH = ModouUi.dp(this, 46);
        DesignSpec bs = ui.spec;
        int style = bs.bookFabStyle;
        final float radius = style == 2 ? ModouUi.dp(this, 16) : fabH / 2f;
        int bgColor, fgColor;
        float elevDp;
        if (style == 1) {
            bgColor = bs.sysBlue; fgColor = 0xFFFFFFFF; elevDp = 8f;
        } else if (style == 2) {
            bgColor = ModouUi.alpha(ui.accent, 0x1F); fgColor = ui.accent; elevDp = 3f;
        } else {
            bgColor = ui.card; fgColor = ui.accent; elevDp = 10f;
        }
        android.graphics.drawable.GradientDrawable fbg =
                new android.graphics.drawable.GradientDrawable();
        fbg.setColor(bgColor);
        fbg.setCornerRadius(radius);
        if (style == 0) {
            fbg.setStroke(ModouUi.dp(this, 1), ModouUi.alpha(ui.accent, 0x2E));
        }
        b.setBackground(fbg);
        b.setMinimumHeight(fabH);
        b.setClipToOutline(true);
        b.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                // 墨读保持原来的「按实测高度取半」，其余两套用规格半径。
                float r = style == 0 ? v.getHeight() / 2f : radius;
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        b.setElevation(ModouUi.dp(this, elevDp));

        ImageView iv = new ImageView(this);
        int s = ModouUi.dp(this, 18);
        iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        iv.setImageDrawable(tinted(R.drawable.ic_book_read, fgColor));
        b.addView(iv);

        TextView t = ModouUi.tv(this, "阅读", 14.5f, fgColor);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(ModouUi.dp(this, 8), 0, 0, 0);
        b.addView(t);

        b.setOnClickListener(v -> ReaderLaunch.open(this, ModouBridge.shared(), book,
                curIndex >= 0 ? curIndex : 0));
        return b;
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
            if (fab != null) fab.setVisibility(View.GONE); // 已加入：悬浮阅读按钮的提示位让出来
        } catch (Exception e) {
            ModouUi.toast(this, "加入书架失败：" + e.getMessage());
        }
    }

    /**
     * 移出书架。
     *
     * <p>在线书只删书架记录；本地书多一个「同时删除手机内的本地文件」——
     * 默认不勾（移出书架是书库操作，删手机上的原文件是不可撤销的破坏性操作，
     * 必须用户亲手勾选、并且文字标红）。
     */
    private void removeFromShelf() {
        final boolean local = LocalImport.LOCAL.equals(book.optString("sourceUrl", ""));
        final String name = book.optString("name", "");

        if (!local) {
            ModouUi.confirm(this, "移出书架", "《" + name
                    + "》会从书架消失，阅读进度和已缓存正文保留。", () -> doRemove(false));
            return;
        }

        try {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 4), ModouUi.dp(this, 14), 0);

            TextView msg = ModouUi.tv(this, "《" + name + "》会从书架消失，阅读进度和已缓存正文保留。",
                    13.5f, ui.sub);
            msg.setLineSpacing(ModouUi.dp(this, 3), 1.3f);
            box.addView(msg);

            // 危险选项：默认不勾，勾中才删原文件
            final boolean[] del = {false};
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = ModouUi.dp(this, 10);
            row.setPadding(0, pad, 0, pad);

            final int boxSize = ModouUi.dp(this, 18);
            final TextView tick = ModouUi.tv(this, "", 12, 0xFFFFFFFF);
            tick.setGravity(Gravity.CENTER);
            tick.setIncludeFontPadding(false);
            tick.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            row.addView(tick, new LinearLayout.LayoutParams(boxSize, boxSize));

            TextView label = ModouUi.tv(this, "同时删除手机内的本地文件", 13, ui.danger);
            label.setPadding(ModouUi.dp(this, 10), 0, 0, 0);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final Runnable paintTick = () -> {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(ModouUi.dp(this, 5));
                if (del[0]) {
                    g.setColor(ui.danger);
                    tick.setText("✓");
                } else {
                    g.setColor(0x00000000);
                    g.setStroke(Math.max(1, ModouUi.dp(this, 1.5f)),
                            ModouUi.alpha(ui.danger, 0x99));
                    tick.setText("");
                }
                tick.setBackground(g);
            };
            paintTick.run();

            row.setOnClickListener(v -> {
                del[0] = !del[0];
                paintTick.run();
                // 勾上时把后果说清楚：这一步删掉的是手机上那个 .txt/.epub，不进回收站
                label.setText(del[0]
                        ? "同时删除手机内的本地文件（不可恢复）"
                        : "同时删除手机内的本地文件");
            });
            box.addView(row);

            Dialog d = ModouUi.sheet(this, "移出书架", box);
            box.addView(ModouUi.sheetButtons(this, d, "取消", "移出", null,
                    () -> doRemove(del[0])));
        } catch (Throwable e) {
            Diagnostics.event("移出书架面板失败: " + android.util.Log.getStackTraceString(e));
            ModouUi.toast(this, "面板打开失败，请重试");
        }
    }

    /** 真正执行移出；deleteFile 只对本地书生效 */
    private void doRemove(boolean deleteFile) {
        String err = null;
        if (deleteFile) {
            try {
                err = BookFiles.deleteOriginal(this, book);
            } catch (Throwable e) {
                // 没有权限 / 文件已被用户在别处删掉 / 网盘 URI：一律记日志，不崩
                err = "系统异常";
                Diagnostics.event("删除本地原文件失败: " + android.util.Log.getStackTraceString(e));
            }
            try {
                book.put("fileUri", "");
            } catch (Exception ignore) {
                /* 字段写不进去不影响移出 */
            }
        }
        try {
            book.put("preview", true);
            book.put("removedAt", System.currentTimeMillis());
            ModouStore.get(this).put("books", book.toString());
            if (deleteFile && err != null) {
                // 删除失败不能静默：用户以为文件没了，文件管理器里却还在
                ModouUi.toast(this, "已移出书架，但原文件没删掉：" + err);
            } else {
                ModouUi.toast(this, deleteFile ? "已移出书架，本地文件已删除" : "已移出书架");
            }
            finish();
        } catch (Exception e) {
            ModouUi.toast(this, "移出失败：" + e.getMessage());
        }
    }

    /**
     * 刷新目录（原生）：把解析规则交给 SourceService，结果直接写回 books 记录。
     * 章节数变少时不清旧正文缓存（宁可留几章孤儿，也不删可能还在读的内容）。
     */
    private void refreshToc() {
        refreshToc(false);
    }

    /**
     * @param silent 静默刷新：不弹「正在刷新」、没变化也不提示。
     *               用于进书时发现目录超过 1 小时没更新过的自动补刷。
     */
    private void refreshToc(final boolean silent) {
        if (refreshingToc) return;
        if (!ensureFull()) {
            if (!silent) ModouUi.toast(this, "还没读到书籍信息，稍后再试");
            return;
        }
        // 书源抓取由常驻的 SourceService 负责，与界面 WebView 无关：
        // 以前这里判断「桥是否就绪」，桥改成按需创建后会把功能误挡在门外
        if (SourceService.get() == null) {
            if (!silent) ModouUi.toast(this, "书源服务还没启动，请重开应用");
            return;
        }
        final int oldCount = totalChapters();
        refreshingToc = true;
        if (!silent) ModouUi.toast(this, "正在刷新目录…");
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
                    final int newCount = chs.length();
                    book.put("chapters", chs);
                    JSONObject last = chs.optJSONObject(chs.length() - 1);
                    if (last != null) book.put("lastChapter", last.optString("name", ""));
                    book.put("updatedAt", System.currentTimeMillis());
                    putBookAsync(); // 自动刷新时不许卡主线程
                    SourceHealth.ok(BookActivity.this, book.optString("sourceUrl", ""));
                    // 记下「这小时刷过了」：下次进书不必再问一遍站点
                    ShelfUpdater.markTocFetched(BookActivity.this, book.optString("id", ""),
                            System.currentTimeMillis());
                    if (silent) {
                        if (newCount > oldCount) {
                            ModouUi.toast(BookActivity.this,
                                    "发现 " + (newCount - oldCount) + " 章新章节");
                            if (bodyView != null) paint(bodyView);
                        }
                    } else {
                        ModouUi.toast(BookActivity.this, "目录已更新（" + newCount + " 章）");
                        if (bodyView != null) paint(bodyView);
                    }
                } catch (Exception e) {
                    String m = e.getMessage() == null ? "刷新失败" : e.getMessage();
                    SourceHealth.fail(BookActivity.this, book.optString("sourceUrl", ""),
                            SourceHealth.classifyText(m), m);
                    if (!silent) {
                        ModouUi.toast(BookActivity.this, "刷新目录失败：" + m);
                    } else {
                        // 静默刷新失败也要记账：否则每次进这本「源已失效」的书都会再请求一遍，
                        // 白白耗流量，还拖慢进书速度。一小时后再说。
                        ShelfUpdater.markTocFetched(BookActivity.this, book.optString("id", ""),
                                System.currentTimeMillis());
                    }
                }
            }
        });
    }

    /* ---------------- 目录 TTL ----------------
     * 目录（章节表）变化很快：连载书一天几更，缓存太久会「点最新章却是旧列表」；
     * 但它又只有几百 KB，每次都抓属于白花流量。
     * 折中：**一小时**内不重复抓；正文则相反，缓存下来就一直有效（见 ModouStore.chapterContent），
     * 只在空间不足时由 StorageJanitor 按最近使用淘汰 —— 正文抓一次要一次网络 + 一次解析，
     * 而它几乎不变，重抓纯属浪费。 */
    private static final long TOC_TTL_MS = 60L * 60 * 1000;
    private boolean autoTocTried;

    /** 进书时静默补刷过期目录（本地书跳过：目录来自文件本身） */
    private void maybeAutoRefreshToc() {
        if (autoTocTried) return;
        autoTocTried = true;
        if (isLocal()) return;
        if (refreshingToc) return;
        if (SourceService.get() == null) return;
        long at = ShelfUpdater.tocFetchedAt(this, book.optString("id", ""));
        if (at <= 0) at = book.optLong("updatedAt", 0);
        if (at > 0 && System.currentTimeMillis() - at < TOC_TTL_MS) return;
        refreshToc(true);
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
     * 换源：按书名搜其他书源。
     *
     * 以前是「点一下 → toast 正在搜索 → 等所有源跑完（常常半分钟）→ 才弹列表」，
     * 期间界面毫无变化，用户以为按钮没反应或卡死了。现在反过来：
     * **面板立刻打开**，结果一个一个往里填，进度一直可见。
     */
    /** 换源：委托给共享换源器（书籍详情页与阅读页共用） */
    private void changeSource() {
        SourceSwap.show(this);
    }

    /* ---------------- 换源时的进度映射 ---------------- */

    @Override
    public SourceSwap.OldPos readOldPosition() {
        SourceSwap.OldPos p = new SourceSwap.OldPos();
        try {
            JSONArray chs = book.optJSONArray("chapters");
            p.total = chs == null ? 0 : chs.length();
            String ps = ModouStore.get(this).get("progress", bookId());
            if (ps != null) p.idx = new JSONObject(ps).optInt("index", -1);
            if (p.idx >= 0 && chs != null && p.idx < p.total) {
                JSONObject c = chs.optJSONObject(p.idx);
                if (c != null) p.title = c.optString("name", "");
            }
        } catch (Exception ignore) {
            /* 读不到就当没有旧位置 */
        }
        return p;
    }

    /**
     * 换源后重新定位进度。两层策略：
     * <ol>
     *   <li>按章名模糊匹配（剥掉「第 12 章」「（求月票）」这类噪音再比）；</li>
     *   <li>匹配不上就按百分比：旧进度在旧目录里的比例 × 新目录章数。</li>
     * </ol>
     * 定位成功会提示「已定位至 第 XX 章 · YY%」，失败则从第一章开始。
     */
    private void relocProgress(SourceSwap.OldPos old) {
        JSONArray chs = book.optJSONArray("chapters");
        int n = chs == null ? 0 : chs.length();
        if (old == null || old.idx < 0 || n == 0) {
            ModouUi.toast(this, "已切换书源，目录已更新");
            return;
        }
        int hit = SourceSwap.matchByTitle(old.title, chs);
        if (hit < 0) {
            // 标题对不上（新源改了章名、或分卷拆并）→ 按进度百分比落到新目录
            hit = Math.max(0, Math.min(n - 1,
                    Math.round((float) old.idx / Math.max(1, old.total) * n)));
        }
        int pct = Math.round((hit + 1) * 100f / n);
        try {
            // nativeOffset 必须归零：换源后正文换了，旧偏移在新章里没有意义
            ModouStore.get(this).putProgress(bookId(), hit, 0, 0);
        } catch (Exception ignore) {
            /* 写不进就只是下次从旧位置开始，不影响换源本身 */
        }
        centerToast("已为你定位至 第 " + (hit + 1) + " 章 · " + pct + "%");
    }

    @Override public Activity getContext() { return this; }
    @Override public int textColor() { return ui.text; }
    @Override public int subColor() { return ui.sub; }
    @Override public JSONObject getBook() { return book; }
    @Override public String getBookId() { return bookId(); }
    @Override public boolean isLocalBook() { return isLocal(); }
    @Override public boolean ensureFullRecord() { return ensureFull(); }
    @Override public void saveBook() { putBookAsync(); }

    @Override
    public void afterSourceApplied(SourceSwap.OldPos old, JSONArray chapters) {
        relocProgress(old);
        if (bodyView != null) paint(bodyView);
    }

    /** 页面中央轻提示：换源定位这种「一次性告知」用底部 Toast 容易被忽略 */
    @Override
    public void centerToast(String s) {
        try {
            android.widget.Toast t = android.widget.Toast.makeText(this, s,
                    android.widget.Toast.LENGTH_SHORT);
            t.setGravity(android.view.Gravity.CENTER, 0, 0);
            t.show();
        } catch (Throwable e) {
            ModouUi.toast(this, s);
        }
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
        final Dialog d = ModouUi.sheetForm(this, "编辑信息", box, footer);
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

        // 目录快速定位：几千章的书靠「加载更多」翻到第 1500 章要点几十次。
        // 「定位到当前章」一步跳到阅读处；「搜索章节」按章节名一步跳过去。
        LinearLayout tocBar = new LinearLayout(this);
        tocBar.setOrientation(LinearLayout.HORIZONTAL);
        tocBar.setGravity(Gravity.CENTER_VERTICAL);
        tocBar.setPadding(0, ModouUi.dp(this, 6), 0, ModouUi.dp(this, 6));
        tocBar.setBackground(ModouUi.round(ModouUi.alpha(ui.text, 0x0A),
                ui.spec.bookTocBarCornerDp));

        TextView curBtn = tocChip("定位到当前章");
        curBtn.setOnClickListener(v -> {
            if (curIndex < 0) {
                ModouUi.toast(this, "还没开始读，先打开一章吧");
                return;
            }
            jumpToChapter(curIndex, true);
        });
        tocBar.addView(curBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView searchBtn = tocChip("搜索章节");
        searchBtn.setOnClickListener(v -> searchChapter());
        tocBar.addView(searchBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(tocBar);

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
        tocRowH = one.getMeasuredHeight() + tocView.getDividerHeight();
        int h = one.getMeasuredHeight() * n;
        tocView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
    }

    /** 目录条上的小胶囊按钮 */
    private TextView tocChip(String s) {
        TextView t = ModouUi.tv(this, s, 12.5f, ui.accent);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ModouUi.dp(this, 8), 0, ModouUi.dp(this, 8));
        return t;
    }

    /**
     * 跳到第 idx 章：先把目录展开到能覆盖它（几千章的书，不展开则这一行根本不存在），
     * 再把外层 ScrollView 滚到该行。scroll=true 时才滚动（定位当前章 / 搜索都传 true）。
     */
    private void jumpToChapter(int idx, boolean scroll) {
        int total = chapters().length();
        if (idx < 0 || idx >= total) return;
        hiIndex = idx;
        if (idx >= shown) shown = Math.min(total, idx + PAGE);
        if (tocView != null && tocView.getAdapter() != null) {
            ((TocAdapter) tocView.getAdapter()).notifyDataSetChanged();
        }
        measureToc(total);
        if (!scroll || scroller == null) return;
        final int target = idx;
        // 等一帧布局完再滚：此时 tocView.getTop()/tocRowH 才是最终值
        scroller.postDelayed(() -> {
            if (tocCardRef == null || tocView == null || tocRowH <= 0) return;
            int y = tocCardRef.getTop() + tocView.getTop() + target * tocRowH;
            scroller.smoothScrollTo(0, Math.max(0, y - ModouUi.dp(BookActivity.this, 60)));
        }, 70);
    }

    /** 按章节名搜索并定位（名称包含关键词即命中，取第一处） */
    private void searchChapter() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ModouUi.dp(this, 14), ModouUi.dp(this, 8), ModouUi.dp(this, 14), 0);
        EditText q = ModouUi.input(this, "章节名关键词，如「第 300 章」", "", 1);
        box.addView(q);
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        final Dialog d = ModouUi.sheet(this, "搜索章节", box, footer);
        footer.addView(ModouUi.sheetButtons(this, d, "取消", "定位", null, () -> {
            String kw = q.getText().toString().trim();
            if (kw.isEmpty()) return;
            int found = findChapter(kw);
            if (found < 0) {
                ModouUi.toast(this, "没有匹配的章节");
                return;
            }
            jumpToChapter(found, true);
        }));
    }

    private int findChapter(String kw) {
        JSONArray chs = chapters();
        String low = kw.toLowerCase();
        for (int i = 0; i < chs.length(); i++) {
            JSONObject o = chs.optJSONObject(i);
            String n = o == null ? "" : o.optString("name", "");
            if (n.toLowerCase().contains(low)) return i;
        }
        return -1;
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
            boolean hit = i == curIndex || i == hiIndex;
            TextView t = ModouUi.tv(BookActivity.this, (i + 1) + ". " + name, 14,
                    hit ? ui.accent : ui.text);
            if (hit) t.setTypeface(t.getTypeface(), Typeface.BOLD);
            // 搜索命中的那一行加一层淡色底，和「正在读的章节」区分开
            if (i == hiIndex && i != curIndex) {
                t.setBackgroundColor(ModouUi.alpha(ui.accent, 0x14));
            }
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
