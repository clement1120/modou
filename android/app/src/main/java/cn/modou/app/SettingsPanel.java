package cn.modou.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private TextView cacheStat;
    private TextView readStat;
    private TextView storageVal;

    SettingsPanel(Activity host) {
        this.host = host;
    }

    /** @param embedded 嵌入首页时为 true：不留状态栏偏移、不画「‹ 返回」 */
    View view(boolean embedded) {
        ui = ModouUi.of(host);
        store = ModouStore.get(host);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        root.setPadding(ModouUi.dp(host, 14), embedded ? ModouUi.dp(host, 6) : statusBarPad(),
                ModouUi.dp(host, 14), ModouUi.dp(host, 20));

        if (!embedded) {
            TextView back = ModouUi.tv(host, "‹ 返回", 14, ui.accent);
            back.setPadding(ModouUi.dp(host, 4), ModouUi.dp(host, 2), 0, ModouUi.dp(host, 6));
            back.setOnClickListener((v) -> host.finish());
            root.addView(back);
        }

        TextView title = ModouUi.tv(host, "设置", 26, ui.text);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setPadding(ModouUi.dp(host, 4), 0, 0, ModouUi.dp(host, 10));
        root.addView(title);

        ScrollView sc = new ScrollView(host);
        sc.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
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

        /* ---------- 外观 ---------- */
        body.addView(ModouUi.groupTitle(host, "外观 · 整 App 配色"));
        LinearLayout look = ui.groupCard(host);
        look.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 12), ModouUi.dp(host, 14), ModouUi.dp(host, 14));
        TextView cur = ModouUi.tv(host, "当前：" + themeName(ModouUi.themeMode(host)), 12, ui.sub);
        cur.setPadding(0, 0, 0, ModouUi.dp(host, 10));
        look.addView(cur);
        // iOS 风格分段控件：一个圆角灰槽，选中项是圆角accent胶囊，三段之间不留缝
        String[] modes = {"light", "dark", "auto"};
        String[] names = {"浅色", "深色", "跟随系统"};
        String m0 = ModouUi.themeMode(host);
        LinearLayout seg = new LinearLayout(host);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(ModouUi.round(ui.line, 8));
        seg.setPadding(ModouUi.dp(host, 2), ModouUi.dp(host, 2), ModouUi.dp(host, 2), ModouUi.dp(host, 2));
        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i];
            boolean on = m.equals(m0);
            TextView t = ModouUi.tv(host, names[i], 13.5f, on ? 0xFFFFFFFF : ui.text);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, ModouUi.dp(host, 8), 0, ModouUi.dp(host, 8));
            if (on) {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setColor(ui.accent);
                g.setCornerRadius(ModouUi.dp(host, 7));
                t.setBackground(g);
            }
            t.setOnClickListener((x) -> setTheme(m));
            seg.addView(t, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        look.addView(seg);
        body.addView(look);

        /* ---------- 章节缓存 ---------- */
        body.addView(ModouUi.groupTitle(host, "章节缓存 · 断网可读"));
        LinearLayout cache = ui.groupCard(host);
        cacheStat = ModouUi.tv(host, "统计中…", 13, ui.text);
        LinearLayout statLine = new LinearLayout(host);
        statLine.setOrientation(LinearLayout.HORIZONTAL);
        statLine.setGravity(Gravity.CENTER_VERTICAL);
        statLine.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 11), ModouUi.dp(host, 14), ModouUi.dp(host, 11));
        TextView st1 = ModouUi.tv(host, "已缓存", 14.5f, ui.text);
        st1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statLine.addView(st1);
        statLine.addView(cacheStat);
        cache.addView(statLine);
        cache.addView(ModouUi.groupDivider(host));
        cache.addView(ui.groupRow(host, "清空正文缓存", "", "已读章节需要联网重新获取", this::clearCache));
        body.addView(cache);

        /* ---------- 数据 ---------- */
        body.addView(ModouUi.groupTitle(host, "数据 · 备份 / 恢复"));
        LinearLayout data = ui.groupCard(host);
        readStat = ModouUi.tv(host, "统计中…", 13, ui.text);
        LinearLayout readLine = new LinearLayout(host);
        readLine.setOrientation(LinearLayout.HORIZONTAL);
        readLine.setGravity(Gravity.CENTER_VERTICAL);
        readLine.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 11), ModouUi.dp(host, 14), ModouUi.dp(host, 11));
        TextView st2 = ModouUi.tv(host, "阅读时间", 14.5f, ui.text);
        st2.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        readLine.addView(st2);
        readLine.addView(readStat);
        data.addView(readLine);
        data.addView(ModouUi.groupDivider(host));
        LinearLayout storageRow = ui.groupRow(host, "书库占用",
                BookFiles.sizeText(ModouStore.get(host).dbBytes()),
                "数据库 + 插图 + 封面 + 临时文件，点开做体检与回收", this::openStorage);
        // groupRow 的第 2 个 child 是右侧数值，保留引用以便回收后原地刷新
        if (storageRow.getChildCount() > 1 && storageRow.getChildAt(1) instanceof TextView) {
            storageVal = (TextView) storageRow.getChildAt(1);
        }
        data.addView(storageRow);
        data.addView(ModouUi.groupDivider(host));
        data.addView(ui.groupRow(host, "导出全部数据", "", "JSON 备份", this::exportBackup));
        data.addView(ModouUi.groupDivider(host));
        data.addView(ui.groupRow(host, "导入备份", "", "同 id 覆盖写入，不删现有数据", this::importBackup));
        data.addView(ModouUi.groupDivider(host));
        LinearLayout clear = ui.groupRow(host, "清空所有数据", "", "书源 / 书架 / 进度 / 书签全部删除且不可恢复",
                this::clearAll);
        ((TextView) ((LinearLayout) clear.getChildAt(0)).getChildAt(0)).setTextColor(ui.danger);
        data.addView(clear);
        body.addView(data);

        /* ---------- 关于 ---------- */
        body.addView(ModouUi.groupTitle(host, "关于"));
        LinearLayout about = ui.groupCard(host);
        about.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 12), ModouUi.dp(host, 14), ModouUi.dp(host, 14));
        String ver;
        try {
            ver = host.getPackageManager().getPackageInfo(host.getPackageName(), 0).versionName;
        } catch (Exception e) {
            ver = "?";
        }
        TextView a = ModouUi.tv(host,
                "墨读 v" + ver + "。书架、搜书、导入、详情、阅读、书源管理均已本地完成；"
                        + "书源抓取由内置引擎在后台完成，不依赖任何界面。",
                12.5f, ui.sub);
        a.setLineSpacing(ModouUi.dp(host, 2), 1.3f);
        about.addView(a);
        body.addView(about);

        refreshCache();
        refreshReadStat();
    }

    private static String themeName(String m) {
        if ("dark".equals(m)) return "深色";
        if ("auto".equals(m)) return "跟随系统";
        return "浅色";
    }

    private void setTheme(String mode) {
        ModouUi.saveThemeMode(host, mode);       // 原生偏好 + 系统夜间模式（决定下次开屏）
        store.putSettingStr("appTheme", mode);   // settings 表：网页版从这里读
        ModouBridge br = ModouBridge.shared();
        if (br != null) {
            br.evalJs("window.__modouSaveAppTheme && window.__modouSaveAppTheme('" + mode + "')");
        }
        // Activity 主题在 onCreate 前定型，改完必须重建才能换掉窗口底色与系统栏图标。
        // 重建前记住当前 tag：否则「切深色」后总是被踹回书架首页。
        if (host instanceof ShelfActivity) {
            ShelfActivity.rememberPage((ShelfActivity) host, ((ShelfActivity) host).currentPage());
        }
        host.recreate();
    }

    /** 存储体检：先给明细，再让用户决定是否深度回收 */
    private void openStorage() {
        new Thread(() -> {
            final String report = StorageJanitor.report(host);
            host.runOnUiThread(() -> ModouUi.confirm(host, "存储体检",
                    report + "\n\n「深度回收」会删除移出书架超过 7 天的孤儿正文、"
                            + "已无书引用的插图和封面、导入残留文件，并压缩数据库空页。"
                            + "数据库很大时可能需要一会儿。",
                    this::deepCleanStorage));
        }, "modou-storage-report").start();
    }

    private void deepCleanStorage() {
        ModouUi.toast(host, "正在回收空间…");
        new Thread(() -> {
            final long freed = StorageJanitor.clean(host, true);
            host.runOnUiThread(() -> {
                ModouUi.toast(host, freed > 0
                        ? "已回收 " + BookFiles.sizeText(freed)
                        : "没有可回收的空间");
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

    private void refreshCache() {
        new Thread(() -> {
            final long n = store.count("chapters");
            final long chars = store.sumLength("chapters", "content");
            host.runOnUiThread(() -> {
                if (cacheStat != null) cacheStat.setText(n + " 章 · 约 "
                        + String.format(java.util.Locale.CHINA, "%.1f", chars / 1048576.0) + " MB");
            });
        }).start();
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

    private void exportBackup() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "modou-backup.json");
            host.startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            ModouUi.toast(host, "无法打开文件保存： " + e.getMessage());
        }
    }

    private void importBackup() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
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

    private void writeBackup(Uri u) {
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject();
                for (String t : Arrays.asList("sources", "books", "progress", "bookmarks", "settings")) {
                    o.put(t, new JSONArray(store.all(t)));
                }
                String rules = store.settingRaw("replaceRules");
                o.put("replaceRules", rules == null ? new JSONArray() : new JSONArray(rules));
                o.put("exportedAt", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",
                        java.util.Locale.CHINA).format(new java.util.Date()));

                OutputStream os = host.getContentResolver().openOutputStream(u);
                if (os == null) throw new Exception("无法写入");
                try {
                    os.write(o.toString(2).getBytes("UTF-8"));
                } finally {
                    os.close();
                }
                host.runOnUiThread(() -> ModouUi.toast(host, "备份已导出"));
            } catch (Exception e) {
                host.runOnUiThread(() -> ModouUi.toast(host, "导出失败：" + e.getMessage()));
            }
        }).start();
    }

    private void readBackup(Uri u) {
        new Thread(() -> {
            try {
                InputStream in = host.getContentResolver().openInputStream(u);
                if (in == null) throw new Exception("无法读取");
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                in.close();
                JSONObject o = new JSONObject(sb.toString());

                final int[] counts = new int[ALL_TABLES.size()];
                for (int i = 0; i < ALL_TABLES.size(); i++) {
                    String t = ALL_TABLES.get(i);
                    JSONArray a = o.optJSONArray(t);
                    if (a == null) continue;
                    counts[i] = a.length();
                    // 逐表覆盖写入：同 id 覆盖，不删现有数据
                    JSONArray save = new JSONArray();
                    for (int k = 0; k < a.length(); k++) {
                        JSONObject r = a.optJSONObject(k);
                        if (r == null) continue;
                        String pk = "progress".equals(t) ? "bookId" : "settings".equals(t) ? "k" : "id";
                        if (!r.has(pk)) continue;
                        save.put(r);
                    }
                    if (save.length() > 0) store.putMany(t, save.toString());
                }
                JSONArray rules = o.optJSONArray("replaceRules");
                if (rules != null) store.putSetting("replaceRules", rules.toString());

                final String msg = "恢复完成：书 " + counts[1] + " 本 · 进度 " + counts[2]
                        + " · 书签 " + counts[5] + " · 设置 " + counts[4];
                host.runOnUiThread(() -> ModouUi.toast(host, msg));
            } catch (Exception e) {
                host.runOnUiThread(() -> ModouUi.toast(host, "导入失败：" + e.getMessage()));
            }
        }).start();
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
