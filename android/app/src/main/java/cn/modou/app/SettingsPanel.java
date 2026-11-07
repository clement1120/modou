package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 设置面板（可嵌入首页底部导航，也可由 SettingsActivity 薄壳承载）。
 *
 * 只做「设置得动、看得见、不会把数据搞丢」的部分：外观、缓存、备份、统计。
 *
 * 关键一致性：外观同时写 SharedPreferences（决定下次冷启动开屏底色）与 settings 表
 * （网页版读它），并通过桥让网页版立即重绘。少写任何一处都会出现
 * 「这边深色那边还是浅色」这种两边对不上的情况。
 */
final class SettingsPanel {

    private static final int REQ_EXPORT = 8101;
    private static final int REQ_IMPORT = 8102;
    private static final List<String> ALL_TABLES = Arrays.asList(
            "sources", "books", "progress", "chapters", "settings", "bookmarks", "replaceRules");

    private final Activity host;
    private ModouUi ui;
    private ModouStore store;
    private LinearLayout body;
    /** 0.9.183：首屏错峰入场只播一次，避免每次 onResume 重播 */
    private boolean staggeredOnce;
    private TextView cacheStat;
    private TextView readStat;
    private TextView storageVal;

    SettingsPanel(Activity host) {
        this.host = host;
    }

    /** @param embedded 嵌入首页时为 true：不留状态栏偏移、不画左上返回圆钮 */
    View view(boolean embedded) {
        ui = ModouUi.of(host);
        store = ModouStore.get(host);

        /* 0.9.163 · §2.11 B5：独立设置页接 iOS 26 Large Title 滚动收起 /
           M3 app bar tonal elevation。嵌在首页 tab 里的形态由书架统一头部管理，
           这里不动（避免两套大标题叠一起）；墨读两个滚动开关都是 false，走原结构零回归。 */
        DesignSpec.Gallery gallery = ui.spec.gallery;
        if (!embedded && (gallery.largeTitleScroll || gallery.appBarTonalScroll)) {
            LargeTitleHeader header = LargeTitleHeader.create(host,
                    "设置", "设置", "外观、书源、缓存与备份，都在这里", true);
            body = header.body();
            body.setPadding(0, 0, 0, ModouUi.dockFootPx(host));
            paint();
            return header.root();
        }

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        // 不铺纯色：这三块面板是嵌在书架首页里的，底衬的渐变与光斑要透上来。
        // 铺 ui.bg 的话，这一页整体变成一块平涂的灰，和旁边书架页、以及页面
        // 最底下那条手势条区域都不是一个颜色（用户看到的就是「底部空出一大块灰」）。

        // 底部 0：留白统一由 ScrollView 内容区的 dockFootPx 承担。
        // 这里再垫 20dp，设置页就比书源/规则页多空一截 —— 正是「只有设置页那么高」的来源。
        root.setPadding(ModouUi.topInset(host), embedded ? ModouUi.dp(host, 6) : statusBarPad(),
                ModouUi.topInset(host), 0);

        if (!embedded) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ModouUi.dp(host, 40), ModouUi.dp(host, 40));
            blp.bottomMargin = ModouUi.dp(host, 6);
            root.addView(ModouUi.backBtn(host, () -> host.finish()), blp);
        }

        TextView title = ModouUi.pageTitle(host, "设置");
        title.setPadding(ModouUi.dp(host, 4), 0, 0, ModouUi.dp(host, 10));
        root.addView(title);

        ScrollView sc = new ScrollView(host);
        sc.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        // 底部浮岛压在这一页上：底边留出它的高度
        body.setPadding(0, 0, 0, ModouUi.dockFootPx(host));
        sc.addView(body);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        paint();
        return root;
    }

    private int statusBarPad() {
        int id = host.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? host.getResources().getDimensionPixelSize(id) : ModouUi.dp(host, 24);
    }

    private void paint() {
        body.removeAllViews();
        /* 0.9.167 · iOS inset grouped：内缩加在内容容器上，一次管住组标题和所有组卡，
         * 不让单个 groupCard 去改自己的 LayoutParams（那会在 FrameLayout / 弹层里错位）。
         * 逐像素零回归：墨读 listGroupInsetDp = 0，padding 一个像素都不变。 */
        if (ui.spec.listGroupInsetDp > 0) {
            int ins = ModouUi.dp(host, ui.spec.listGroupInsetDp);
            body.setPadding(ins, body.getPaddingTop(), ins, body.getPaddingBottom());
        }

        /* ---------- 外观（0.9.161 去重后只剩两行入口） ----------
           原先这里还有一枚「浅色 / 深色 / 跟随系统」三段 segment，与「主题外观」
           画廊里的深浅三行是同一功能的两处入口。按用户要求删掉，深浅只在画廊里
           设置（ModouUi.saveAppTheme 是唯一写入点）；这里只保留状态展示。
           阅读纸张也做成画廊式，入口与主题外观并列，避免第三处切换逻辑。 */
        /* 0.9.167：这一页是「三语言列表行构图」的完整样板——每一行都接
           leading 语义图标，统计行也统一走 iconRow，不再有手写统计行。 */
        body.addView(ModouUi.groupTitle(host, "外观 · 设计语言与纸张"));
        LinearLayout lang = ui.groupCard(host);
        DesignSpec.Lang curLang = DesignSpec.current(host);
        lang.addView(ui.iconRow(host, ListIcon.APPEARANCE, "主题外观",
                brief(curLang.label, curLang == DesignSpec.Lang.MATERIAL ? "M3"
                        : curLang == DesignSpec.Lang.MONOLITH ? "极简" : curLang.label)
                        + " · " + themeName(ModouUi.themeMode(host)),
                brief("配色 / 圆角 / 弹窗 / 深浅", "配色、圆角与深浅"),
                () -> ThemeGalleryActivity.launch(host)));
        lang.addView(ModouUi.groupDivider(host, true));
        lang.addView(ui.iconRow(host, ListIcon.PAPER, "阅读纸张",
                DesignSpec.paperLabel(NativeTheme.currentName(host)),
                brief("素月白 / 晨光米 / 远山青 / 夜间 · 点开看大图预览", "四种纸张 · 可预览大图"),
                () -> PaperGalleryActivity.launch(host)));
        body.addView(lang);

        /* ---------- 章节缓存 ---------- */
        body.addView(ModouUi.groupTitle(host, "章节缓存 · 断网可读"));
        LinearLayout cache = ui.groupCard(host);
        /* 统计行保留手写布局：墨读下逐像素不变。仅当语言给了 leading 图标规格时
         * 在行首插一枚（ListIcon.create 返回 null 即墨读，直接跳过），并把行高对齐
         * 到带图标行的 listRowMinHDp，避免统计行比其它行矮一截。 */
        cacheStat = ModouUi.tv(host, "统计中…", 13, ui.text);
        LinearLayout statLine = new LinearLayout(host);
        statLine.setOrientation(LinearLayout.HORIZONTAL);
        statLine.setGravity(Gravity.CENTER_VERTICAL);
        statLine.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 11),
                ModouUi.dp(host, 14), ModouUi.dp(host, 11));
        View cacheIcon = ListIcon.create(host, ui, ListIcon.CACHE);
        if (cacheIcon != null) {
            statLine.addView(cacheIcon);
            statLine.setMinimumHeight(ModouUi.dp(host, ui.spec.listRowMinHDp));
        }
        TextView st1 = ModouUi.tv(host, "已缓存", 14.5f, ui.text);
        st1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statLine.addView(st1);
        statLine.addView(cacheStat);
        cache.addView(statLine);
        cache.addView(ModouUi.groupDivider(host, true));
        cache.addView(ui.iconRow(host, ListIcon.FILTER, "按书清理缓存", "",
                brief("占用最大的 8 本，可单独清正文或目录", "按书清理正文或目录"), this::openCacheByBook));
        cache.addView(ModouUi.groupDivider(host, true));
        cache.addView(ui.iconRow(host, ListIcon.TRASH, "清空正文缓存", "",
                brief("已读章节需要联网重新获取", "清理后需联网重取"), this::clearCache));
        body.addView(cache);

        /* ---------- 数据 ---------- */
        body.addView(ModouUi.groupTitle(host, "数据 · 备份 / 恢复"));
        LinearLayout data = ui.groupCard(host);
        /* 同上：统计行手写，墨读逐像素不变，仅在有图标规格时插图标并对齐行高。 */
        readStat = ModouUi.tv(host, "统计中…", 13, ui.text);
        LinearLayout readLine = new LinearLayout(host);
        readLine.setOrientation(LinearLayout.HORIZONTAL);
        readLine.setGravity(Gravity.CENTER_VERTICAL);
        readLine.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 11),
                ModouUi.dp(host, 14), ModouUi.dp(host, 11));
        View readIcon = ListIcon.create(host, ui, ListIcon.CLOCK);
        if (readIcon != null) {
            readLine.addView(readIcon);
            readLine.setMinimumHeight(ModouUi.dp(host, ui.spec.listRowMinHDp));
        }
        TextView st2 = ModouUi.tv(host, "阅读时间", 14.5f, ui.text);
        st2.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        readLine.addView(st2);
        readLine.addView(readStat);
        data.addView(readLine);
        data.addView(ModouUi.groupDivider(host, true));
        LinearLayout storageRow = ui.iconRow(host, ListIcon.STORAGE, "书库占用",
                BookFiles.sizeText(ModouStore.get(host).dbBytes()),
                brief("数据库 + 插图 + 封面 + 临时文件，点开做体检与回收", "查看占用并回收空间"), this::openStorage);
        // 0.9.167：值改从 tag 访问器取。加了 leading 图标后 child 下标整体右移，
        // 旧的 getChildAt(1) 会取错甚至 ClassCastException（同类 bug，一并修）。
        storageVal = ModouUi.rowValue(storageRow);
        data.addView(storageRow);
        data.addView(ModouUi.groupDivider(host, true));
        data.addView(ui.iconRow(host, ListIcon.EXPORT, "导出全部数据", "",
                "ZIP · 含封面", this::exportBackup));
        data.addView(ModouUi.groupDivider(host, true));
        data.addView(ui.iconRow(host, ListIcon.IMPORT, "导入备份", "",
                "ZIP / 旧版 JSON 都认", this::importBackup));
        data.addView(ModouUi.groupDivider(host, true));
        // 危险项：标题色走 titleColor 重载，不再反射 ((LinearLayout)getChildAt(0)).getChildAt(0)
        data.addView(ui.iconRow(host, ListIcon.DANGER, "清空所有数据", "",
                brief("书源 / 书架 / 进度 / 书签全部删除且不可恢复", "全部删除且不可恢复"),
                ui.danger, this::clearAll));
        body.addView(data);

        /* ---------- 关于 ---------- */
        body.addView(ModouUi.groupTitle(host, "关于"));
        LinearLayout about = ui.groupCard(host);
        String ver;
        try {
            ver = host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionName;
        } catch (Exception e) {
            ver = "?";
        }
        TextView a = ModouUi.tv(host,
                "轻阅 v" + ver + "。书架、搜书、导入、详情、阅读、书源管理均已本地完成；"
                        + "书源抓取由内置引擎在后台完成，不依赖任何界面。",
                12.5f, ui.sub);
        a.setLineSpacing(ModouUi.dp(host, 2), 1.3f);
        // 说明段自己留白，不再给整张组卡 setPadding —— 否则带图标行的左右缩进
        // 会比其他组多出 14dp，整页看起来不齐。
        a.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 12),
                ModouUi.dp(host, 14), ModouUi.dp(host, 14));
        // 隐藏入口：版本号连点 5 次（2.5 秒内连续）打开「代理层压测」。
        // 不放进正式菜单 —— 它是开发者自测工具，普通用户点进去只会看到一堆数字。
        final int[] tapN = {0};
        final long[] tapAt = {0};
        a.setOnClickListener((v) -> {
            long now = System.currentTimeMillis();
            if (now - tapAt[0] > 2500) tapN[0] = 0;
            tapAt[0] = now;
            tapN[0]++;
            if (tapN[0] >= 5) {
                tapN[0] = 0;
                ProxyStress.run(host);
            } else if (tapN[0] >= 3) {
                ModouUi.toast(host, "再点 " + (5 - tapN[0]) + " 次打开代理层压测");
            }
        });
        about.addView(a);
        // 诊断日志：出问题时点这里，把崩溃记录 + 运行环境拼成一段文字，
        // 走系统分享直接发出去（不需要存储权限，也没有「找不到文件」这一步）
        about.addView(ModouUi.groupDivider(host, true));
        about.addView(ui.iconRow(host, ListIcon.DIAG, "导出诊断日志", "",
                "崩溃记录 / 运行环境，可直接发微信", () -> Diagnostics.export(host)));
        body.addView(about);

        refreshCache();
        refreshReadStat();

        // 0.9.183：分组内容错峰入场。只在首次构建时播一次 —— 每次 onResume 重播会变成骚扰。
        if (!staggeredOnce) {
            staggeredOnce = true;
            ModouMotion.stagger(body);
        }
    }

    /**
     * 0.9.205：带行首图标的 M3 / iOS 文字区被挤窄，副标已改单行，继续用完整
     * 说明会在行尾挂一串「…」，比原来的换行更糙。这里按语言给短文案；
     * 墨读（listIconStyle=0）无图标块、文字区宽，走 full 分支，逐像素不变。
     */
    private String brief(String full, String concise) {
        return ui.spec.listIconStyle == 0 ? full : concise;
    }

    private static String themeName(String m) {
        if ("dark".equals(m)) return "深色";
        if ("auto".equals(m)) return "跟随系统";
        return "浅色";
    }

    /** 存储体检：先给明细，再让用户决定是否深度回收 */
    private void openStorage() {
        // 书库大时 report 要递归遍历整个目录（十几秒），先给个提示，
        // 否则用户以为点击没反应、反复点。
        ModouUi.toast(host, "存储体检中…");
        new Thread(() -> {
            final String report = StorageJanitor.report(host);
            host.runOnUiThread(() -> ModouUi.confirm(host, "存储体检",
                    report + "\n\n「深度回收」会删除已移出书架的正文（不再保留 7 天）、"
                            + "已无书引用的插图和封面、导入残留文件，然后压缩数据库。"
                            + "数据库很大时可能要几分钟，期间别进阅读页。",
                    this::deepCleanStorage));
        }, "modou-storage-report").start();
    }

    private void deepCleanStorage() {
        ModouUi.toast(host, "正在回收空间…数据库大时需要几分钟");
        new Thread(() -> {
            final StorageJanitor.Result r = StorageJanitor.clean(host, true);
            host.runOnUiThread(() -> {
                String msg;
                if (r.freed > 0) {
                    msg = "已回收 " + BookFiles.sizeText(r.freed);
                } else if (r.orphanChapters > 0 && !r.vacuumed) {
                    // 正文删了，但数据库文件要 VACUUM 才会缩；空间不够就压不动
                    msg = "已清理 " + r.orphanChapters + " 章孤儿正文，但压缩数据库还需约 "
                            + BookFiles.sizeText(r.needBytes) + " 空闲（当前 "
                            + BookFiles.sizeText(Math.max(0, r.freeBytes)) + "）。"
                            + "腾出空间后再点一次即可真正释放。";
                } else if (r.orphanChapters > 0) {
                    msg = "已清理 " + r.orphanChapters + " 章孤儿正文，数据库正在压缩，稍后重进本页看占用";
                } else {
                    msg = "没有可回收的空间：库里的正文都是书架上还在读的书。"
                            + "要省空间只能删书，或重新导入更小的版本。";
                }
                ModouUi.alert(host, "回收结果", msg);
                refreshCache();
                refreshStorage();
            });
        }, "modou-storage-clean").start();
    }

    private void refreshStorage() {
        new Thread(() -> {
            final long total = StorageJanitor.appBytes(host);
            host.runOnUiThread(() -> {
                if (storageVal != null) storageVal.setText(BookFiles.sizeText(total));
            });
        }).start();
    }

    /* ---------------- 缓存统计 ---------------- */

    /** 正文总量（设置页展示用）缓存：sumLength 全表扫描正文，书库大时一次十几秒，60s 内不重复扫 */
    private static long cacheCharsCache = -1;
    private static long cacheCharsAt = 0;
    private static final long CACHE_TTL = 60L * 1000;

    private void refreshCache() {
        long now = System.currentTimeMillis();
        if (cacheCharsCache >= 0 && now - cacheCharsAt < CACHE_TTL) {
            final long cached = cacheCharsCache;
            new Thread(() -> {
                final long n = store.count("chapters"); // 章数轻量（索引扫），不缓存
                host.runOnUiThread(() -> applyCacheStat(n, cached));
            }).start();
            return;
        }
        new Thread(() -> {
            final long n = store.count("chapters");
            final long chars = store.sumLength("chapters", "content");
            cacheCharsCache = chars;
            cacheCharsAt = now;
            host.runOnUiThread(() -> applyCacheStat(n, chars));
        }).start();
    }

    private void applyCacheStat(long n, long chars) {
        if (cacheStat != null) cacheStat.setText(n + " 章 · 约 "
                + String.format(java.util.Locale.CHINA, "%.1f", chars / 1048576.0) + " MB");
    }

    /**
     * 按书清理：列出正文缓存占用最大的几本，分别清「正文」或「目录」。
     *
     * <p>以前只有「一键清空所有」，用户往往只想腾掉那本 300 章的漫画型小说，
     * 不想把正在追的几本也一起清掉（下次翻章要重新联网抓）。
     */
    private void openCacheByBook() {
        final java.util.List<String[]> rows = store.topChapterCache(8);
        if (rows.isEmpty()) {
            ModouUi.toast(host, "还没有正文缓存");
            return;
        }
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, ModouUi.dp(host, 2), 0, 0);
        for (String[] r : rows) {
            box.addView(cacheRow(r));
            box.addView(ModouUi.groupDivider(host));
        }
        ModouUi.sheetForm(host, "按书清理缓存", box);
    }

    /** 一本书的缓存行：书名 + 正文/目录占用 + 两个单独清理入口 */
    private View cacheRow(final String[] r) {
        final String id = r[0];
        final String src = r[2];
        long bytes = 0;
        int n = 0;
        try {
            bytes = Long.parseLong(r[3]);
            n = Integer.parseInt(r[4]);
        } catch (Exception ignore) {
            /* 脏数据按 0 显示 */
        }
        final boolean local = src.startsWith("local://");
        final int chapN = n;
        int tocCount = 0;
        try {
            JSONObject l = store.bookLite(id);
            if (l != null) tocCount = l.optInt("chapterCount", 0);
        } catch (Exception ignore) {
            /* 目录数取不到就不显示 */
        }

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, ModouUi.dp(host, 8), 0, ModouUi.dp(host, 8));

        TextView name = ModouUi.tv(host, r[1] == null || r[1].isEmpty() ? id : r[1], 14, ui.text);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name);

        TextView sub = ModouUi.tv(host, "正文 " + BookFiles.sizeText(bytes) + " · " + n + " 章"
                + (tocCount > 0 ? " · 目录 " + tocCount + " 章" : ""), 11.5f, ui.sub);
        sub.setPadding(0, ModouUi.dp(host, 2), 0, ModouUi.dp(host, 6));
        row.addView(sub);

        LinearLayout acts = new LinearLayout(host);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        TextView c1 = smallBtn("清正文", ui.text);
        c1.setOnClickListener((v) -> ModouUi.confirm(host, "清理正文缓存",
                "清掉《" + r[1] + "》已缓存的 " + chapN + " 章正文？下次读到要重新联网获取。", () -> {
                    store.delChaptersOf(id);
                    store.checkpoint();
                    ModouUi.toast(host, "已清理正文缓存");
                    refreshCache();
                    refreshStorage();
                }));
        acts.addView(c1);
        if (!local) {
            TextView c2 = smallBtn("清目录", ui.text);
            c2.setOnClickListener((v) -> ModouUi.confirm(host, "清理目录缓存",
                    "清掉《" + r[1] + "》的目录快照，下次打开这本书会重新向书源拉取。\n\n"
                            + "如果这个源已经失效，重拉会失败，只能换源或删除。", () -> {
                                boolean ok = store.clearToc(id);
                                ModouUi.toast(host, ok ? "已清理目录，下次打开会重拉" : "清理失败");
                            }));
            acts.addView(c2);
        }
        row.addView(acts);
        return row;
    }

    /** 小按钮（清正文 / 清目录）：0.9.173 起 iOS / M3 走统一 chip，墨读保持原 8dp 圆角块 */
    private TextView smallBtn(String text, int color) {
        if (ui.spec.chipStyle != 0) {
            TextView c = ModouUi.chip(host, text, false, null);
            c.setTextColor(color);
            return c;
        }
        TextView t = ModouUi.tv(host, text, 12.5f, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 6),
                ModouUi.dp(host, 14), ModouUi.dp(host, 6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x14000000);
        g.setCornerRadius(ModouUi.dp(host, 8));
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = ModouUi.dp(host, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private void clearCache() {
        ModouUi.confirm(host, "清空正文缓存", "已读章节需要联网重新获取，确定清空？", () -> {
            new Thread(() -> {
                store.clear("chapters");
                // 删行只是把页标成空闲，不 VACUUM 文件大小不会变；
                // 这里先 checkpoint，再按空闲页量决定是否真正压缩。
                store.checkpoint();
                store.compactIfNeeded(8L * 1024 * 1024);
                host.runOnUiThread(() -> {
                    ModouUi.toast(host, "正文缓存已清空");
                    refreshCache();
                    refreshStorage();
                });
            }, "modou-clear-cache").start();
        });
    }

    /* ---------------- 阅读统计 ---------------- */

    private void refreshReadStat() {
        new Thread(() -> {
            long ms = 0;
            int books = 0;
            try {
                JSONArray a = new JSONArray(store.all("progress"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject p = a.optJSONObject(i);
                    if (p == null) continue;
                    ms += p.optLong("readMs", 0);
                    books++;
                }
            } catch (Exception e) {
                /* 空表：保持 0 */
            }
            final long fms = ms;
            final int fb = books;
            host.runOnUiThread(() -> {
                if (readStat != null) readStat.setText(fmt(fms) + " · " + fb + " 本书");
            });
        }).start();
    }

    private static String fmt(long ms) {
        long hh = ms / 3600000L;
        long mm = (ms % 3600000L) / 60000L;
        return hh + " 小时 " + mm + " 分钟";
    }

    /** 目录总字节数（含子目录） */
    private static long dirBytes(java.io.File d) {
        if (d == null || !d.exists()) return 0;
        if (d.isFile()) return d.length();
        long n = 0;
        java.io.File[] fs = d.listFiles();
        if (fs != null) for (java.io.File f : fs) n += dirBytes(f);
        return n;
    }

    /* ---------------- 备份导出 / 导入 ---------------- */

    /** 备份格式版本：只有结构变了才 +1。导入时高于本值直接拒绝，低版本走兼容读取 */
    private static final int BACKUP_VERSION = 3;
    /** ZIP 内的清单文件名：必须第一个写 —— 导入端是流式解压，读到它才知道版本 */
    private static final String ZIP_JSON = "backup.json";
    /** ZIP 内的封面目录前缀（与 files/covers 的落盘结构一一对应） */
    private static final String ZIP_COVERS = "covers/";
    /** 单张封面解压上限：防 zip 炸弹，正常封面几十 KB，1MB 都算大的 */
    private static final long MAX_COVER_BYTES = 32L * 1024 * 1024;
    /** 整包封面解压总量上限：再大就不是备份了 */
    private static final long MAX_COVERS_TOTAL = 1024L * 1024 * 1024;

    /* ---------------- 导出 ---------------- */

    private void exportBackup() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
                    .format(new java.util.Date());
            i.putExtra(Intent.EXTRA_TITLE, "modou-backup-" + day + ".zip");
            host.startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            ModouUi.toast(host, "无法打开文件保存： " + e.getMessage());
        }
    }

    private void importBackup() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // zip 的 MIME 在不少文件管理器里是 octet-stream，所以放开 + 额外声明
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed",
                    "application/json", "text/plain", "application/octet-stream"});
            host.startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            ModouUi.toast(host, "无法打开文件选择：" + e.getMessage());
        }
    }

    /** SettingsActivity 薄壳把 onActivityResult 转发到这里 */
    void onActivityResult(int req, int res, Intent data) {
        if (res != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == REQ_EXPORT) writeBackup(u);
        else if (req == REQ_IMPORT) readBackup(u);
    }

    /** 本地书正文进包的总量上限（字符数）：超过就不再带，至少已带的能救回来 */
    private static final long LOCAL_CHAPTERS_MAX = 12L * 1024 * 1024;

    /**
     * 只打包本地导入书的正文。
     *
     * <p>为什么不全量导 chapters：在线书正文是缓存，几十上百 MB，换机后重新抓即可；
     * 本地书没有书源，正文就是书本身，丢了等于书没了。导进来却读不到正文，
     * 用户看到的就是「有目录、每章都说没有正文」。
     */
    private JSONArray buildLocalChapters(JSONArray books) {
        JSONArray out = new JSONArray();
        if (books == null) return out;
        long used = 0;
        for (int i = 0; i < books.length(); i++) {
            JSONObject b = books.optJSONObject(i);
            if (b == null) continue;
            if (!b.optString("sourceUrl", "").startsWith(LocalImport.LOCAL)) continue;
            String id = b.optString("id", "");
            if (id.isEmpty()) continue;
            JSONArray dir = b.optJSONArray("chapters");
            int n = dir == null ? 0 : dir.length();
            for (int k = 0; k < n; k++) {
                String html = store.chapterContent(id, k);
                if (html == null || html.isEmpty()) continue;
                if (used + html.length() > LOCAL_CHAPTERS_MAX) return out;
                try {
                    JSONObject rec = new JSONObject();
                    rec.put("id", ModouStore.chapterKey(id, k));
                    rec.put("content", html);
                    rec.put("cachedAt", System.currentTimeMillis());
                    rec.put("book_id", id);
                    rec.put("idx", k);
                    out.put(rec);
                    used += html.length();
                } catch (Exception ignore) {
                    /* 单章序列化失败不影响其它章 */
                }
            }
        }
        return out;
    }

    /** 打包 JSON 清单：与旧版 JSON 备份完全同构，只是外层多了 ZIP 壳 */
    private JSONObject buildBackupJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("app", "modou");
        o.put("version", BACKUP_VERSION);
        o.put("format", "zip");
        try {
            o.put("appVersion", host.getPackageManager()
                    .getPackageInfo(host.getPackageName(), 0).versionName);
        } catch (Exception ignore) {
            /* 取不到版本号不影响备份本身 */
        }
        for (String t : Arrays.asList("sources", "books", "progress", "bookmarks", "settings")) {
            o.put(t, new JSONArray(store.all(t)));
        }
        // 本地书正文必须进包。
        // 在线书的正文只是缓存，清数据/换机后能重新抓；本地书没有书源可重抓，正文不进包，
        // 恢复出来就是「目录齐全、每章都提示没有正文」—— 清空数据再导入备份后正是这个现象。
        // 只挑 local:// 的书，并给总量封顶，别让几十 MB 的在线缓存把备份包撑爆。
        o.put("chapters", buildLocalChapters(o.optJSONArray("books")));
        String rules = store.settingRaw("replaceRules");
        o.put("replaceRules", rules == null ? new JSONArray() : new JSONArray(rules));
        o.put("exportedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",
                java.util.Locale.CHINA).format(new java.util.Date()));
        return o;
    }

    /**
     * 写 ZIP：清单 + 封面原图。
     *
     * <p>封面必须进包：书源图标是 URL（跟着 sources 走），但书架封面是
     * {@code file://…/files/covers/<crc>-<len>.img} 的**本地文件**，只导 JSON 的话，
     * 换台设备导入后每本书都是空白封面 —— 这正是上一版丢图的根因。
     */
    private void writeBackup(Uri u) {
        final Busy busy = showBusy("正在导出备份", "打包书源、书架与封面…");
        new Thread(() -> {
            java.util.zip.ZipOutputStream zos = null;
            try {
                JSONObject o = buildBackupJson();
                OutputStream os = host.getContentResolver().openOutputStream(u, "w");
                if (os == null) throw new Exception("无法写入");
                zos = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(os));
                zos.setLevel(6);
                zos.putNextEntry(new java.util.zip.ZipEntry(ZIP_JSON));
                zos.write(o.toString(2).getBytes("UTF-8"));
                zos.closeEntry();

                final File dir = new File(host.getFilesDir(), "covers");
                File[] fs = dir.listFiles();
                int total = 0;
                if (fs != null) {
                    for (File f : fs) if (f.isFile() && !f.getName().startsWith(".")) total++;
                }
                int n = 0;
                if (fs != null) {
                    for (File f : fs) {
                        if (!f.isFile() || f.getName().startsWith(".") || f.length() == 0) continue;
                        zos.putNextEntry(new java.util.zip.ZipEntry(ZIP_COVERS + f.getName()));
                        copyFile(f, zos);
                        zos.closeEntry();
                        n++;
                        if ((n & 7) == 0) busyText(busy, "打包封面 " + n + "/" + total + "…");
                    }
                }
                zos.finish();
                ensureNoMedia(dir);
                final int fn = n;
                dismissBusy(busy);
                host.runOnUiThread(() -> ModouUi.toast(host,
                        fn > 0 ? "备份已导出 · 含 " + fn + " 张封面" : "备份已导出"));
            } catch (Exception e) {
                dismissBusy(busy);
                final String m = e.getMessage();
                host.runOnUiThread(() -> ModouUi.toast(host, "导出失败：" + (m == null ? "写入出错" : m)));
            } finally {
                if (zos != null) {
                    try {
                        zos.close();
                    } catch (Exception ignore) {
                        /* 已经写完，关不掉也无所谓 */
                    }
                }
            }
        }, "modou-backup-export").start();
    }

    /* ---------------- 导入 ---------------- */

    /**
     * 读备份：先嗅探魔数决定 ZIP / 旧版 JSON。
     *
     * <p>ZIP 用 {@link java.util.zip.ZipInputStream} 顺序读：清单固定第一个条目，
     * 读到它就能校验版本；随后逐个条目抽封面。封面是内容寻址（crc-len），
     * 已存在同名文件直接跳过，不重复写盘。
     */
    private void readBackup(final Uri u) {
        final Busy busy = showBusy("正在读取备份", "解压并校验…");
        new Thread(() -> {
            InputStream in = null;
            try {
                in = host.getContentResolver().openInputStream(u);
                if (in == null) throw new Exception("无法读取");
                java.io.BufferedInputStream bin = new java.io.BufferedInputStream(in);
                bin.mark(4);
                byte[] magic = new byte[4];
                int got = readFully(bin, magic);
                bin.reset();
                final boolean zip = got == 4 && magic[0] == 'P' && magic[1] == 'K'
                        && magic[2] == 3 && magic[3] == 4;

                JSONObject o = null;
                int covers = 0;
                long coverBytes = 0;
                if (zip) {
                    java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(bin);
                    try {
                        java.util.zip.ZipEntry e;
                        while ((e = z.getNextEntry()) != null) {
                            String name = e.getName();
                            if (ZIP_JSON.equals(name)) {
                                o = new JSONObject(readText(z));
                            } else if (name.startsWith(ZIP_COVERS)) {
                                if ((covers & 7) == 0) busyText(busy, "恢复封面 " + covers + " 张…");
                                long w = restoreCover(name.substring(ZIP_COVERS.length()), z);
                                if (w > 0) {
                                    covers++;
                                    coverBytes += w;
                                    // 总量封顶：坏包/炸弹到此为止，已写出的封面仍然有效
                                    if (coverBytes > MAX_COVERS_TOTAL) break;
                                }
                            }
                            z.closeEntry();
                        }
                    } finally {
                        try {
                            z.close();
                        } catch (Exception ignore) {
                            /* 流已读完 */
                        }
                    }
                    if (o == null) throw new Exception("ZIP 里没有 " + ZIP_JSON);
                    ensureNoMedia(new File(host.getFilesDir(), "covers"));
                } else {
                    // 旧版纯 JSON：照旧整份读进来
                    o = new JSONObject(readText(bin));
                }

                final JSONObject fo = o;
                final int ver = o.optInt("version", 1); // 老备份没有 version，按 1 处理
                if (ver > BACKUP_VERSION) {
                    dismissBusy(busy);
                    host.runOnUiThread(() -> ModouUi.alert(host, "无法导入",
                            "这份备份来自更新版本的轻阅（备份格式 v" + ver + "，本机只认到 v"
                                    + BACKUP_VERSION + "）。\n\n请先把 App 升级到最新版再导入。"));
                    return;
                }
                final int nSrc = len(o.optJSONArray("sources"));
                final int nBook = len(o.optJSONArray("books"));
                final int nProg = len(o.optJSONArray("progress"));
                final int nMark = len(o.optJSONArray("bookmarks"));
                final String exp = o.optString("exportedAt", "未知时间");
                final int fc = covers;
                dismissBusy(busy);
                host.runOnUiThread(() -> askImportMode(fo, ver, nSrc, nBook, nProg, nMark, exp, fc));
            } catch (Exception e) {
                dismissBusy(busy);
                final String m = e.getMessage();
                host.runOnUiThread(() -> ModouUi.toast(host,
                        "导入失败：" + (m == null ? "文件格式不对" : m)));
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignore) {
                        /* 读完关闭失败可忽略 */
                    }
                }
            }
        }, "modou-backup-import").start();
    }

    /**
     * 把 ZIP 里的一张封面写回 files/covers。
     *
     * <p>只接受纯文件名：带路径分隔符、`..`、目录条目的统统丢弃，
     * 并再用 canonicalPath 兜一次 zip-slip —— 备份文件也可能被手工改过。
     */
    private long restoreCover(String name, InputStream in) {
        if (name == null || name.isEmpty()) return 0;
        name = name.trim();
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")
                || name.startsWith(".")) {
            return 0;
        }
        final File dir = new File(host.getFilesDir(), "covers");
        if (!dir.exists() && !dir.mkdirs()) return 0;
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                return 0;
            }
        } catch (Exception e) {
            return 0;
        }
        if (f.isFile() && f.length() > 0) return 0;   // 内容寻址：同名即同图，跳过
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_COVER_BYTES) throw new Exception("封面过大");
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
            ensureNoMedia(dir);
            return f.length();
        } catch (Exception e) {
            // 写坏的文件必须删掉：否则下次同名会被「已存在」跳过，永远是一张破图
            try {
                f.delete();
            } catch (Exception ignore) {
                /* 删不掉下次也会被回收 */
            }
            return 0;
        }
    }

    /** 封面目录加 .nomedia：避免被相册/媒体库扫成「一堆碎图」 */
    private void ensureNoMedia(File dir) {
        try {
            File nm = new File(dir, ".nomedia");
            if (!nm.exists()) nm.createNewFile();
        } catch (Exception ignore) {
            /* 写不了就算了，不影响功能 */
        }
    }

    /** 流式复制到 ZIP 条目：不能 close（会连带关掉 ZipOutputStream） */
    private static void copyFile(File f, OutputStream out) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    /** 读一个条目 / 整条流的文本（UTF-8） */
    private static String readText(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), "UTF-8");
    }

    private static int readFully(InputStream in, byte[] dst) {
        int off = 0;
        while (off < dst.length) {
            int n;
            try {
                n = in.read(dst, off, dst.length - off);
            } catch (Exception e) {
                return off;
            }
            if (n <= 0) break;
            off += n;
        }
        return off;
    }

    /* ---------------- 加载提示 ---------------- */

    /** 一次打包/解压的进度提示：不可取消（中途取消会留下半个 zip） */
    private static final class Busy {
        Dialog d;
        TextView text;
    }

    private Busy showBusy(String title, String msg) {
        Busy b = new Busy();
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ModouUi.dp(host, 2), ModouUi.dp(host, 2), ModouUi.dp(host, 2), 0);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(
                host, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        try {
            pb.getIndeterminateDrawable().setTint(ui.accent);
        } catch (Throwable ignore) {
            /* 老 ROM 不支持着色：保持默认色 */
        }
        pb.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(host, 3)));
        box.addView(pb);
        TextView t = ModouUi.tv(host, msg, 12.5f, ui.sub);
        t.setPadding(0, ModouUi.dp(host, 10), 0, ModouUi.dp(host, 2));
        box.addView(t);
        b.text = t;
        b.d = ModouUi.sheet(host, title, box);
        b.d.setCancelable(false);
        b.d.setCanceledOnTouchOutside(false);
        return b;
    }

    private void busyText(Busy b, String s) {
        if (b == null || b.text == null) return;
        host.runOnUiThread(() -> b.text.setText(s));
    }

    private void dismissBusy(Busy b) {
        if (b == null || b.d == null) return;
        host.runOnUiThread(() -> {
            try {
                if (b.d.isShowing()) b.d.dismiss();
            } catch (Exception ignore) {
                /* 已经关了 */
            }
        });
    }

    private static int len(JSONArray a) {
        return a == null ? 0 : a.length();
    }

    /** 导入方式：合并（保留现有） / 覆盖（先清空书源书架进度书签规则） */
    private void askImportMode(final JSONObject o, int ver, int nSrc, int nBook,
                               int nProg, int nMark, String exportedAt, int covers) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 4), ModouUi.dp(host, 14), 0);
        TextView info = ModouUi.tv(host, "备份时间 " + exportedAt + " · 格式 v" + ver + "\n"
                + "书源 " + nSrc + " · 书 " + nBook + " 本 · 进度 " + nProg + " · 书签 " + nMark
                + (covers > 0 ? " · 封面 " + covers + " 张" : " · 无封面")
                + "\n\n"
                + "合并：保留现在的内容，同 id 用备份里的覆盖。\n"
                + "覆盖：先清空书源/书架/进度/书签/替换规则，再整份写入（阅读设置不删）。",
                13, ui.sub);
        info.setLineSpacing(ModouUi.dp(host, 3), 1.3f);
        box.addView(info);

        final Dialog d = ModouUi.sheet(host, "导入备份", box);

        TextView merge = importBtn("合并（保留现有 + 新增）", ui.accent, 0xFFFFFFFF);
        merge.setOnClickListener((v) -> {
            d.dismiss();
            doImport(o, false);
        });
        TextView replace = importBtn("覆盖（清空后写入）", ui.danger, 0xFFFFFFFF);
        replace.setOnClickListener((v) -> {
            d.dismiss();
            ModouUi.confirm(host, "覆盖导入",
                    "会先删除当前全部书源、书架、阅读进度、书签和替换规则，再写入备份内容。"
                            + "此操作不可撤销。", () -> doImport(o, true));
        });
        TextView cancel = importBtn("取消", 0, ui.sub);
        cancel.setOnClickListener((v) -> d.dismiss());

        LinearLayout footer = new LinearLayout(host);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(0, ModouUi.dp(host, 12), 0, 0);
        footer.addView(merge);
        footer.addView(replace);
        footer.addView(cancel);
        box.addView(footer);
    }

    private TextView importBtn(String text, int bg, int fg) {
        TextView t = ModouUi.tv(host, text, 14.5f, fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ModouUi.dp(host, 11), 0, ModouUi.dp(host, 11));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg == 0 ? 0x00000000 : bg);
        g.setCornerRadius(ModouUi.dp(host, 10));
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ModouUi.dp(host, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private void doImport(final JSONObject o, final boolean replace) {
        final Busy busy = showBusy("正在导入", "写入书库…");
        new Thread(() -> {
            try {
                // 覆盖模式先清数据表。settings 不清：主题/字号是「这台设备的偏好」，
                // 清掉会让人导入完发现界面全变了。替换规则属于书源体系，跟着一起换。
                if (replace) {
                    for (String t : Arrays.asList("sources", "books", "progress", "bookmarks")) {
                        store.clear(t);
                    }
                    store.putSetting("replaceRules", "[]");
                }
                final int[] counts = new int[ALL_TABLES.size()];
                for (int i = 0; i < ALL_TABLES.size(); i++) {
                    String t = ALL_TABLES.get(i);
                    JSONArray a = o.optJSONArray(t);
                    if (a == null) continue;
                    counts[i] = a.length();
                    JSONArray save = new JSONArray();
                    for (int k = 0; k < a.length(); k++) {
                        JSONObject r = a.optJSONObject(k);
                        if (r == null) continue;
                        String pk = "progress".equals(t) ? "bookId" : "settings".equals(t) ? "k" : "id";
                        if (!r.has(pk)) continue;
                        if ("books".equals(t)) fixCoverPath(r);
                        save.put(r);
                    }
                    if (save.length() > 0) store.putMany(t, save.toString());
                }
                JSONArray rules = o.optJSONArray("replaceRules");
                if (rules != null) store.putSetting("replaceRules", rules.toString());

                // 老备份（v2 及以前）不含本地书正文：恢复后这些书目录齐全、正文是空的。
                // 能读到原文件就顺手重新解析一遍；读不到的统计出来，明确让用户重新导入。
                final int[] relink = new int[2];
                relinkLocalBooks(o.optJSONArray("books"), busy, relink);
                dismissBusy(busy);
                final String msg = (replace ? "已覆盖导入：" : "已合并导入：")
                        + "书 " + counts[1] + " 本 · 进度 " + counts[2]
                        + " · 书签 " + counts[5] + " · 设置 " + counts[4]
                        + (relink[0] > 0 ? "\n\n本地书正文已重建 " + relink[0] + " 本。" : "")
                        + (relink[1] > 0 ? "\n\n有 " + relink[1] + " 本本地书读不到原文件"
                        + "（重装 / 清空数据会撤销系统的文件夹授权）。\n"
                        + "去「本地书籍」重新授权一次那个文件夹，页面顶部会出现「一键补齐」——"
                        + "正文会重新从原文件解析，阅读进度、书签都不会丢。"
                        : "");
                host.runOnUiThread(() -> {
                    ModouUi.alert(host, "导入完成", msg + "\n\n重新进入书架即可看到。");
                    paint();
                });
            } catch (Exception e) {
                dismissBusy(busy);
                final String m = e.getMessage();
                host.runOnUiThread(() -> ModouUi.toast(host, "导入失败：" + (m == null ? "写入出错" : m)));
            }
        }).start();
    }

    /**
     * 修补「有目录没正文」的本地书（老备份 / 正文被跳过的情况）。
     *
     * <p>只处理 local:// 且 chapters 表为空的：先探一下 fileUri 还有没有读权限
     * （清空数据时系统会把 persisted URI 授权一起撤销），能读就交给 LocalImport
     * 重新解析一遍。这样正文回到了库里，之后连原文件都能不依赖。
     *
     * @param stat [0] 成功重建本数，[1] 读不到原文件本数
     */
    private void relinkLocalBooks(JSONArray books, Busy busy, int[] stat) {
        if (books == null) return;
        for (int i = 0; i < books.length(); i++) {
            JSONObject b = books.optJSONObject(i);
            if (b == null) continue;
            if (!b.optString("sourceUrl", "").startsWith(LocalImport.LOCAL)) continue;
            String id = b.optString("id", "");
            if (id.isEmpty()) continue;
            if (store.countChaptersOf(id) > 0) continue;   // 正文已在（新备份），不用管
            String fu = b.optString("fileUri", "");
            if (fu.isEmpty()) {
                stat[1]++;
                continue;
            }
            Uri u;
            try {
                u = Uri.parse(fu);
            } catch (Exception e) {
                stat[1]++;
                continue;
            }
            // 先探权限：没授权时 openInputStream 直接抛 SecurityException，
            // 交给下面计数，不再往下走解析（解析也读不到）。
            try (InputStream probe = host.getContentResolver().openInputStream(u)) {
                if (probe == null) {
                    stat[1]++;
                    continue;
                }
            } catch (Exception e) {
                stat[1]++;
                continue;
            }
            String bookUrl = b.optString("bookUrl", "");
            String fname = bookUrl.startsWith(LocalImport.LOCAL) && bookUrl.contains("#")
                    ? bookUrl.substring(LocalImport.LOCAL.length(), bookUrl.lastIndexOf('#'))
                    : b.optString("name", "");
            busyText(busy, "重建本地书正文：" + b.optString("name", fname) + "…");
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final boolean[] ok = new boolean[1];
            LocalImport.enqueue(host, u, fname, b.optLong("bytes", -1), new LocalImport.Callback() {
                @Override
                public void onStage(String text) {
                    /* 进度由 busy 统一显示 */
                }

                @Override
                public void onDone(LocalImport.Res r) {
                    ok[0] = r != null && r.ok;
                    latch.countDown();
                }
            });
            try {
                // 大书解析慢，给足时间；超时就当失败，不卡住整个导入流程
                latch.await(180, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (ok[0]) stat[0]++;
            else stat[1]++;
        }
    }

    /**
     * 封面路径归一化：备份里的 {@code file:///data/user/0/包名/files/covers/x.img}
     * 带着上一台设备的绝对路径，直接写回去，遇到多用户/换包名就是一张破图。
     * 这里只保留文件名，用本机的 files/covers 重新拼。
     */
    private void fixCoverPath(JSONObject book) {
        try {
            String cu = book.optString("coverUrl", "");
            int i = cu.lastIndexOf("/covers/");
            if (i < 0) return;
            String name = cu.substring(i + 8);
            int q = name.indexOf('?');
            if (q >= 0) name = name.substring(0, q);
            if (name.isEmpty() || name.contains("/")) return;
            File f = new File(new File(host.getFilesDir(), "covers"), name);
            book.put("coverUrl", "file://" + f.getAbsolutePath());
        } catch (Exception ignore) {
            /* 改不动就保留原值：大不了这张封面重新联网 */
        }
    }

    private void clearAll() {
        ModouUi.confirm(host, "清空所有数据", "书源、书架、进度、书签、规则全部删除且不可恢复！",
                () -> ModouUi.confirm(host, "再次确认", "此操作不可撤销。", () -> {
                    for (String t : ALL_TABLES) store.clear(t);
                    ModouUi.toast(host, "已清空");
                    paint();
                }));
    }
}
