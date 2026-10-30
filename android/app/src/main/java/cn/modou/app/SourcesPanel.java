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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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

/**
 * 书源管理面板（可嵌入首页底部导航，也可由 SourcesActivity 薄壳承载）。
 *
 * 对齐网页版 sources.js 的书源管理重设计（docs/书源页重设计研究.md）：
 *  - 书源体检：快速校验（搜索一步，并发 3）→ 深度校验失效源（详情→目录→正文，
 *    并发 2）→ 列出「两次都不合格」可一键删除。判定与网页版同一套
 *    （校验词未命中只算未判定，不据此判死；停用的源标未判定不误报）。
 *  - 行内状态点：有效 / 失效 / 两次失效 / 深度失效 / 未判定 / 未校验 / 校验中，
 *    失效原因写在行副行（网页版「状态用色点、原因进详情」的移植）。
 *  - 筛选 chips：全部 / 有效 / 失效 / 两次不合格 / 未校验。
 *  - 多选批量：长按行进入多选，底部操作栏 启用/禁用/校验/删除/导出。
 *  - 检查重复：按地址、名称两轮判定，每组保留分最高的一条，一键删除其余。
 *
 * 校验状态只存内存（与网页版一致，不落库）：URL → 状态。
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
    }

    /** 从本地文件导入书源的请求码（由 ShelfActivity 转发 onActivityResult） */
    static final int REQ_IMPORT_FILE = 8111;

    private final Activity host;
    private ModouUi ui;
    private LinearLayout body;
    private TextView status;
    private TextView checkBtnText;      // 体检 sheet 里的主按钮（开始/停止/重新体检）
    private TextView checkProgress;     // 体检 sheet 里的进度行
    private TextView checkSummary;      // 体检 sheet 里的阶段小结
    private LinearLayout checkActions;  // 体检 sheet 里的结果动作区
    private LinearLayout checkBar;      // 底部批量操作栏

    private final List<JSONObject> items = new ArrayList<>();
    /**
     * 校验状态（static）：体检必须能在后台继续 —— 用户关掉 sheet、切到别的 tag，
     * 校验都不能断。状态挂 static 上（进程级），面板只是它的一个观察窗口；
     * uiTarget 指向当前活动的面板实例，后台进度回调统一经 uiPost 路由到它。
     */
    /** url → ok / bad / skip / nosearch / run / none */
    private static final Map<String, String> quick = new HashMap<>();
    private static final Map<String, String> qReason = new HashMap<>();
    private static final Map<String, Deep> deep = new HashMap<>();
    /** 快速校验命中的第一本书（深度校验的起点） */
    private static final Map<String, JSONObject> foundBook = new HashMap<>();
    /** 行内状态控件引用（paint 时登记），单行状态变化时只改这一行不整表重画 */
    private final Map<String, TextView> statusMeta = new HashMap<>();
    private final Map<String, View> statusDot = new HashMap<>();
    private static String keyword = "我的";
    private static String progressText = "";   // 最近一条进度（同步到统计行）

    /* 体检运行态（static：跟面板生命周期解耦） */
    private static boolean checkRunning;
    private static boolean checkStop;
    private static final List<JSONObject> badQueue = new ArrayList<>(); // 快速校验判失效，等待深度校验
    /** 深度校验完成后「两次都不合格」的清单（重开 sheet 时还能一键删除） */
    private static final List<JSONObject> bothBad = new ArrayList<>();
    private static String checkDoneText = "";  // 结束语（重开 sheet 时回显）

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

    /* 多选态 */
    private boolean selMode;
    private final Set<String> sel = new LinkedHashSet<>();

    /* 筛选：all / ok / bad / both / unck */
    private String filter = "all";

    SourcesPanel(Activity host) {
        this.host = host;
        uiTarget = this;
    }

    /** @param embedded 嵌入首页时为 true：不留状态栏偏移、不画「‹ 返回」 */
    View view(boolean embedded) {
        ui = ModouUi.of(host);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        ScrollView sv = new ScrollView(host);
        sv.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(host, 14);
        body.setPadding(p, embedded ? ModouUi.dp(host, 6) : statusBarPad(), p, p);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部批量操作栏：多选态才显示，钉在列表下方（不随内容滚动）
        checkBar = buildCheckBar();
        checkBar.setVisibility(View.GONE);
        root.addView(checkBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        items.sort((x, y) -> groupOf(x).compareTo(groupOf(y)));
        paint();
    }

    private static String groupOf(JSONObject o) {
        String g = o.optString("bookSourceGroup", "");
        if (g.isEmpty()) g = "未分组";
        return g.split("[,，]")[0].trim();
    }

    /* ================= 状态判定（与网页版 sources.js statusOf 同一套） ================= */

    /** 综合状态：深度校验结果优先（它比快速校验更权威） */
    private String statusKey(String url) {
        String q = quick.get(url);
        Deep d = deep.get(url);
        if (d != null) {
            if ("run".equals(d.state)) return "drun";
            if ("ok".equals(d.state)) return "ok";
            if ("bad".equals(d.state)) return "bad".equals(q) ? "both" : "deepbad";
        }
        if (q == null) return "none";
        return q;
    }

    private static boolean isCheckable(JSONObject o) {
        return !o.optString("searchUrl", "").trim().isEmpty();
    }

    /** 筛选 chip 命中判定 */
    private boolean passFilter(String key) {
        switch (filter) {
            case "ok": return "ok".equals(key);
            case "bad": return "bad".equals(key) || "deepbad".equals(key) || "skip".equals(key);
            case "both": return "both".equals(key);
            case "unck": return "none".equals(key) || "nosearch".equals(key);
            default: return true;
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
            case "both": return "两次失效";
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
        String key = statusKey(url);
        StringBuilder sb = new StringBuilder(stateLabel(key));
        if ("none".equals(key) && !isCheckable(o)) sb.append("（未配置搜索）");
        StringBuilder caps = new StringBuilder();
        if (!o.optString("searchUrl", "").isEmpty()) caps.append("搜索");
        if (!o.optString("exploreUrl", "").isEmpty()) caps.append(caps.length() > 0 ? " · 发现" : "发现");
        if (caps.length() > 0) sb.append(" · ").append(caps);
        String reason = qReason.get(url);
        Deep d = deep.get(url);
        if (d != null && "bad".equals(d.state) && d.step != null) reason = d.step + "：" + d.reason;
        if (reason != null && !reason.isEmpty() && ("bad".equals(key) || "both".equals(key)
                || "deepbad".equals(key) || "skip".equals(key))) {
            sb.append(" · ").append(reason);
        }
        return sb.toString();
    }

    /* ================= 列表 ================= */

    private void paint() {
        body.removeAllViews();
        statusMeta.clear();
        statusDot.clear();

        /* page-head：26sp 大标题 + 新建（嵌入时导航由底部 tag 承担，不留返回键） */
        LinearLayout h = new LinearLayout(host);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        if (!embedded()) {
            TextView back = ModouUi.tv(host, "‹ 返回", 15, ui.accent);
            back.setPadding(0, ModouUi.dp(host, 6), ModouUi.dp(host, 10), ModouUi.dp(host, 6));
            back.setOnClickListener((v) -> host.finish());
            h.addView(back);
        }
        TextView t = ModouUi.tv(host, "书源管理", 26, ui.text);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView add = ModouUi.tv(host, "新建", 14, 0xFFFFFFFF);
        add.setGravity(Gravity.CENTER);
        GradientDrawable ag = new GradientDrawable();
        ag.setColor(ui.accent);
        ag.setCornerRadius(ModouUi.dp(host, 10));
        add.setBackground(ag);
        add.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 8), ModouUi.dp(host, 14), ModouUi.dp(host, 8));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = ModouUi.dp(host, 8);
        add.setOnClickListener((v) -> editSource(null));
        h.addView(add, alp);

        // 体检入口常驻头部：之前藏在列表最底部的按钮组里，源一多就要滚到底才找得到
        TextView chk = ModouUi.tv(host, checkRunning ? "体检中" : "体检", 14,
                checkRunning ? ui.sub : ui.text);
        chk.setGravity(Gravity.CENTER);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(ui.card);
        cg.setCornerRadius(ModouUi.dp(host, 10));
        chk.setBackground(cg);
        chk.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 8), ModouUi.dp(host, 14), ModouUi.dp(host, 8));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = ModouUi.dp(host, 8);
        chk.setOnClickListener((v) -> openCheckAllSheet());
        h.addView(chk, clp);

        // 「⋯」收纳低频操作（检查重复 / 导出全部 / 导入）：头部一行放不下 5 个按钮，
        // 底部按钮组在长列表里要滚到底才看得见，全部收进头部
        TextView more = ModouUi.tv(host, "⋯", 16, ui.text);
        more.setGravity(Gravity.CENTER);
        GradientDrawable mg = new GradientDrawable();
        mg.setColor(ui.card);
        mg.setCornerRadius(ModouUi.dp(host, 10));
        more.setBackground(mg);
        more.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 2), ModouUi.dp(host, 14), ModouUi.dp(host, 6));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = ModouUi.dp(host, 8);
        more.setOnClickListener((v) -> openMoreSheet());
        h.addView(more, mlp);
        body.addView(h);

        /* 统计行 / 多选计数行 */
        int on = 0;
        for (JSONObject o : items) if (o.optBoolean("enabled", true)) on++;
        status = ModouUi.tv(host, "", 12, ui.sub);
        status.setPadding(ModouUi.dp(host, 2), ModouUi.dp(host, 6), ModouUi.dp(host, 2), ModouUi.dp(host, 8));
        if (selMode) {
            status.setText("已选 " + sel.size() + " / " + items.size());
            status.setTextColor(ui.accent);
        } else if (items.isEmpty()) {
            status.setText("还没有书源。");
        } else {
            status.setText("共 " + items.size() + " 个，启用 " + on + " 个"
                    + (checkRunning && !progressText.isEmpty() ? " · " + progressText : ""));
        }
        body.addView(status);

        if (selMode) {
            body.addView(selBar());
        } else {
            body.addView(filterChips());
        }

        /* 分组小标题 + 组容器内紧贴的行：每组一个圆角组卡，行间 1px 分隔线 */
        String lastGroup = null;
        LinearLayout group = null;
        int shown = 0;
        for (int i = 0; i < items.size(); i++) {
            JSONObject o = items.get(i);
            if (!passFilter(statusKey(o.optString("bookSourceUrl", "")))) continue;
            shown++;
            String g = groupOf(o);
            if (!g.equals(lastGroup)) {
                lastGroup = g;
                if (group != null && group.getChildCount() > 0) body.addView(group);
                body.addView(ModouUi.groupTitle(host, g));
                group = ui.groupCard(host);
            }
            if (group.getChildCount() > 0) group.addView(ModouUi.groupDivider(host));
            group.addView(row(i));
        }
        if (group != null && group.getChildCount() > 0) body.addView(group);
        if (shown == 0 && !items.isEmpty()) {
            body.addView(ModouUi.tv(host, "当前筛选没有命中的书源。", 13, ui.sub));
        }

        checkBar.setVisibility(selMode ? View.VISIBLE : View.GONE);
    }

    private boolean embedded() {
        return host instanceof ShelfActivity;
    }

    /** 筛选 chips：全部 / 有效 / 失效 / 两次不合格 / 未校验 */
    private View filterChips() {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, ModouUi.dp(host, 8));
        String[][] defs = {{"all", "全部"}, {"ok", "有效"}, {"bad", "失效"},
                {"both", "两次不合格"}, {"unck", "未校验"}};
        for (final String[] d : defs) {
            boolean on = filter.equals(d[0]);
            TextView c = ModouUi.tv(host, d[1], 12.5f, on ? 0xFFFFFFFF : ui.text);
            c.setGravity(Gravity.CENTER);
            c.setPadding(ModouUi.dp(host, 12), ModouUi.dp(host, 5), ModouUi.dp(host, 12), ModouUi.dp(host, 5));
            GradientDrawable g = new GradientDrawable();
            g.setColor(on ? ui.accent : ui.card);
            g.setCornerRadius(ModouUi.dp(host, 999));
            c.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ModouUi.dp(host, 6);
            c.setOnClickListener((v) -> {
                filter = d[0];
                paint();
            });
            row.addView(c, lp);
        }
        return row;
    }

    /** 多选态顶条：全选 / 取消 */
    private View selBar() {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, ModouUi.dp(host, 8));
        TextView all = ModouUi.tv(host, "全选", 13.5f, ui.accent);
        all.setPadding(0, ModouUi.dp(host, 4), ModouUi.dp(host, 16), ModouUi.dp(host, 4));
        all.setOnClickListener((v) -> {
            sel.clear();
            for (JSONObject o : items) {
                if (passFilter(statusKey(o.optString("bookSourceUrl", "")))) sel.add(o.optString("id", ""));
            }
            paint();
        });
        row.addView(all);
        TextView cancel = ModouUi.tv(host, "取消多选", 13.5f, ui.sub);
        cancel.setPadding(0, ModouUi.dp(host, 4), 0, ModouUi.dp(host, 4));
        cancel.setOnClickListener((v) -> {
            selMode = false;
            sel.clear();
            paint();
        });
        row.addView(cancel);
        return row;
    }

    private View row(final int i) {
        final JSONObject o = items.get(i);
        final String url = o.optString("bookSourceUrl", "");
        final String id = o.optString("id", "");
        final String rawName = o.optString("bookSourceName", "");
        final String name = rawName.isEmpty() ? url : rawName;

        LinearLayout wrap = new LinearLayout(host);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(host);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ModouUi.dp(host, 10), ModouUi.dp(host, 7), ModouUi.dp(host, 12), ModouUi.dp(host, 4));

        if (selMode) {
            // 多选态：开关换成选择圈，整行点击切换选中
            top.addView(selCircle(sel.contains(id)));
            top.setOnClickListener((v) -> {
                if (sel.contains(id)) sel.remove(id);
                else sel.add(id);
                paint();
            });
        } else {
            // 自绘胶囊开关替系统 CheckBox：纸墨底上系统开关是一块 Material 蓝方块
            top.addView(ModouUi.switchRow(host, "", o.optBoolean("enabled", true), on -> {
                try {
                    o.put("enabled", on);
                    ModouStore.get(host).put("sources", o.toString());
                } catch (Exception ignore) {
                    /* 不可能 */
                }
            }));
        }

        LinearLayout mid = new LinearLayout(host);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView n = ModouUi.tv(host, name, 14, ui.text);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mid.addView(n);
        top.addView(mid);

        if (!selMode) {
            TextView edit = ModouUi.tv(host, "编辑", 12.5f, ui.accent);
            edit.setPadding(ModouUi.dp(host, 9), ModouUi.dp(host, 4), 0, ModouUi.dp(host, 4));
            edit.setOnClickListener((v) -> editSource(o));
            top.addView(edit);

            TextView ck = ModouUi.tv(host, "校验", 12.5f, ui.accent);
            ck.setPadding(ModouUi.dp(host, 9), ModouUi.dp(host, 4), 0, ModouUi.dp(host, 4));
            ck.setOnClickListener((v) -> {
                if (checkRunning) {
                    ModouUi.toast(host, "体检进行中");
                    return;
                }
                List<JSONObject> one = new ArrayList<>();
                one.add(o);
                quick.clear();
                qReason.clear();
                deep.clear();
                foundBook.clear();
                bothBad.clear();
                checkDoneText = "";
                filter = "all";
                paint();
                startWorkers(one, true);
                openCheckAllSheet(); // 直接亮出进度面板（后台可关）
            });
            top.addView(ck);

            TextView del = ModouUi.tv(host, "删除", 12.5f, ui.danger);
            del.setPadding(ModouUi.dp(host, 9), ModouUi.dp(host, 4), 0, ModouUi.dp(host, 4));
            del.setOnClickListener((v) -> ModouUi.confirm(host, "删除书源", "删除《" + name + "》？", () -> {
                ModouStore.get(host).del("sources", id);
                quick.remove(url);
                qReason.remove(url);
                deep.remove(url);
                reload();
            }));
            top.addView(del);

            // 长按行进入多选（初始选中本行），与网页版一致
            top.setOnLongClickListener((v) -> {
                selMode = true;
                sel.clear();
                sel.add(id);
                paint();
                return true;
            });
        }
        wrap.addView(top);

        /* 副行：状态点 + 状态字 · 能力标签 · 失效原因 */
        LinearLayout sub = new LinearLayout(host);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        sub.setGravity(Gravity.CENTER_VERTICAL);
        sub.setPadding(ModouUi.dp(host, 46), 0, ModouUi.dp(host, 12), ModouUi.dp(host, 7));

        View dot = new View(host);
        dot.setBackground(dotDrawable(statusKey(url)));
        sub.addView(dot, new LinearLayout.LayoutParams(ModouUi.dp(host, 7), ModouUi.dp(host, 7)));
        statusDot.put(url, dot);

        TextView meta = ModouUi.tv(host, buildMeta(o), 11, ui.sub);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(ModouUi.dp(host, 6), 0, 0, 0);
        sub.addView(meta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusMeta.put(url, meta);
        wrap.addView(sub);
        return wrap;
    }

    private GradientDrawable dotDrawable(String key) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(stateColor(key));
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /** 多选圈：ColorOS 风格圆环，选中填 accent 圆点 */
    private View selCircle(boolean on) {
        View v = new View(host);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (on) {
            g.setColor(ui.accent);
            g.setStroke(ModouUi.dp(host, 1.5f), ui.accent);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(ModouUi.dp(host, 1.5f), ui.sub);
        }
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ModouUi.dp(host, 20), ModouUi.dp(host, 20));
        lp.rightMargin = ModouUi.dp(host, 8);
        v.setLayoutParams(lp);
        return v;
    }

    /* ================= 队列驱动 ================= */

    /**
     * 固定并发的异步队列：task 执行完必须回调 next；next 取下一个任务，
     * 全部完成后回调 onAllDone（一次）。回调都在主线程，无需加锁。
     */
    private void runQueue(List<JSONObject> queue, int workers,
                          BiConsumer<JSONObject, Runnable> task, Runnable onAllDone) {
        if (queue.isEmpty()) {
            onAllDone.run();
            return;
        }
        final AtomicInteger idx = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final Runnable[] w = new Runnable[1];
        w[0] = () -> {
            int i = idx.getAndIncrement();
            if (i >= queue.size()) return; // 这个 worker 退出
            task.accept(queue.get(i), () -> {
                if (done.incrementAndGet() >= queue.size()) onAllDone.run();
                else w[0].run();
            });
        };
        for (int k = 0; k < Math.min(workers, queue.size()); k++) w[0].run();
    }

    /* ================= 书源体检 ================= */

    /**
     * 体检 sheet（三态渲染）：未开始给关键词表单；进行中给实时进度 + 停止；
     * 已结束给结果回显 + 一键删除。关掉 sheet 校验继续在后台跑 —— 关闭只是收起窗口。
     */
    private void openCheckAllSheet() {
        if (items.isEmpty()) {
            ModouUi.toast(host, "还没有书源");
            return;
        }
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(host, 14);
        box.setPadding(p + 4, ModouUi.dp(host, 8), p + 4, 0);
        final Dialog[] self = new Dialog[1]; // 点「开始体检」后要先关掉本窗口再重开成进度态

        TextView hint = ModouUi.tv(host, checkRunning
                ? "体检正在后台进行，关掉这个面板不影响进度，状态点会实时更新。"
                : "① 快速校验：每个源按关键词搜一次（快）。\n② 深度校验：只对快速判失效的源跑 详情→目录→正文。\n③ 两次都不合格的源可一键删除。",
                11.5f, ui.sub);
        hint.setPadding(0, ModouUi.dp(host, 2), 0, ModouUi.dp(host, 8));
        box.addView(hint);

        if (!checkRunning) {
            EditText kw = ModouUi.input(host, "校验关键词（如：我的 / pride）", keyword, 1);
            box.addView(kw);
            final boolean[] autoDeep = {true};
            box.addView(ModouUi.switchRow(host, "快速校验后自动深度校验失效源", true, on -> autoDeep[0] = on));

            final Dialog[] selfRef = self;
            checkBtnText = ModouUi.tv(host, "开始体检", 14, 0xFFFFFFFF);
            checkBtnText.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setColor(ui.accent);
            g.setCornerRadius(ModouUi.dp(host, 10));
            checkBtnText.setBackground(g);
            checkBtnText.setPadding(0, ModouUi.dp(host, 10), 0, ModouUi.dp(host, 10));
            box.addView(checkBtnText, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            checkBtnText.setOnClickListener((v) -> {
                String k = String.valueOf(kw.getText()).trim();
                keyword = k.isEmpty() ? "我的" : k;
                quick.clear();
                qReason.clear();
                deep.clear();
                foundBook.clear();
                bothBad.clear();
                checkDoneText = "";
                filter = "all";
                paint();
                startWorkers(new ArrayList<>(items), autoDeep[0]);
                // 表单就地切换成进度态：关掉当前 sheet 重开
                if (selfRef[0] != null) selfRef[0].dismiss();
                openCheckAllSheet();
            });
        } else {
            checkBtnText = ModouUi.tv(host, "停止", 14, 0xFFFFFFFF);
            checkBtnText.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFFA8433A);
            g.setCornerRadius(ModouUi.dp(host, 10));
            checkBtnText.setBackground(g);
            checkBtnText.setPadding(0, ModouUi.dp(host, 10), 0, ModouUi.dp(host, 10));
            box.addView(checkBtnText, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            checkBtnText.setOnClickListener((v) -> {
                checkStop = true;
                checkBtnText.setText("正在停止…");
                checkBtnText.setEnabled(false);
            });
        }

        checkProgress = ModouUi.tv(host, progressText, 12.5f, ui.text);
        checkProgress.setPadding(0, ModouUi.dp(host, 10), 0, 0);
        box.addView(checkProgress);

        checkSummary = ModouUi.tv(host, checkRunning ? "" : checkDoneText, 12.5f, ui.sub);
        checkSummary.setPadding(0, ModouUi.dp(host, 4), 0, 0);
        box.addView(checkSummary);

        checkActions = new LinearLayout(host);
        checkActions.setOrientation(LinearLayout.VERTICAL);
        checkActions.setPadding(0, ModouUi.dp(host, 8), 0, 0);
        box.addView(checkActions);
        if (!checkRunning && !bothBad.isEmpty()) renderBothBadActions();

        // 「返回」固定在滚动区外：它不等于停止 —— 关掉面板体检照样在后台跑，
        // 统计行和每行的状态点会继续实时更新，想再看得点顶部「体检」重开。
        LinearLayout footer = new LinearLayout(host);
        footer.setOrientation(LinearLayout.VERTICAL);
        final Dialog d = ModouUi.sheet(host, "书源体检", box, footer);
        self[0] = d; // 「开始体检」按钮重开窗口时要用
        checkSheetDialog = d;
        TextView back = ModouUi.tv(host, "返回", 15, ui.text);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, ModouUi.dp(host, 12), 0, ModouUi.dp(host, 12));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(ModouUi.alpha(ui.text, 0x0A));
        bb.setCornerRadius(ModouUi.dp(host, 12));
        back.setBackground(bb);
        back.setOnClickListener((v) -> d.dismiss());
        footer.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 关闭 ≠ 停止：只解除本窗口的控件引用，后台进度改投到统计行与状态点
        d.setOnDismissListener((di) -> {
            checkSheetDialog = null;
            checkBtnText = null;
            checkProgress = null;
            checkSummary = null;
            checkActions = null;
        });
    }

    /** 「两次都不合格」的一键删除按钮（体检结束态 / 重开 sheet 时复用） */
    private void renderBothBadActions() {
        if (checkActions == null) return;
        checkActions.removeAllViews();
        TextView go = ModouUi.tv(host, "删除 " + bothBad.size() + " 个两次不合格的书源", 13.5f, 0xFFFFFFFF);
        go.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFA8433A);
        g.setCornerRadius(ModouUi.dp(host, 10));
        go.setBackground(g);
        go.setPadding(0, ModouUi.dp(host, 10), 0, ModouUi.dp(host, 10));
        go.setOnClickListener((v) -> ModouUi.confirm(host, "删除两次不合格的书源",
                "将删除 " + bothBad.size() + " 个快速、深度校验都不通过的源，此操作不可撤销。", () -> {
                    for (JSONObject o : bothBad) {
                        ModouStore.get(host).del("sources", o.optString("id", ""));
                        String u = o.optString("bookSourceUrl", "");
                        quick.remove(u);
                        qReason.remove(u);
                        deep.remove(u);
                    }
                    ModouUi.toast(host, "已删除 " + bothBad.size() + " 个书源");
                    bothBad.clear();
                    checkDoneText = "";
                    reload();
                }));
        checkActions.addView(go);
    }

    /** 启动体检：quick 并发 3 → （可选）deep 并发 2 → 汇总 */
    private void startWorkers(List<JSONObject> list, boolean autoDeep) {
        SourceService svc = SourceService.get();
        if (svc == null) {
            ModouUi.toast(host, "书源服务未启动");
            return;
        }
        checkRunning = true;
        checkStop = false;
        badQueue.clear();
        bothBad.clear();
        checkDoneText = "";
        syncProgressViews();
        List<JSONObject> queue = new ArrayList<>(list);
        AtomicInteger qOk = new AtomicInteger();
        AtomicInteger qBad = new AtomicInteger();
        progressText = "① 快速校验 0/" + queue.size();
        syncProgressViews();

        runQueue(queue, 3, (o, next) -> quickCheckOne(o, () -> {
            String u = o.optString("bookSourceUrl", "");
            if ("ok".equals(quick.get(u))) qOk.incrementAndGet();
            else if ("bad".equals(quick.get(u))) qBad.incrementAndGet();
            refreshRow(o);
            progressText = "① 快速校验进行中 · 有效 " + qOk.get() + " · 失效 " + qBad.get();
            syncProgressViews();
            next.run();
        }), () -> uiPost(() -> finishQuick(queue, qOk.get(), qBad.get(), autoDeep)));
    }

    /** 把最新进度刷到统计行与体检 sheet（可能在后台回调线程被调用，统一投主线程） */
    private void syncProgressViews() {
        uiPost(() -> {
            if (status != null) status.setText("共 " + items.size() + " 个 · " + progressText);
            if (checkProgress != null) checkProgress.setText(progressText);
        });
    }

    /** 快速校验全部完成后的收尾（主线程） */
    private void finishQuick(List<JSONObject> queue, int okN, int badN, boolean autoDeep) {
        if (checkStop) {
            endCheck("已停止");
            return;
        }
        progressText = "① 快速校验完成 " + queue.size() + "/" + queue.size()
                + " · 有效 " + okN + " · 失效 " + badN;
        syncProgressViews();
        if (!autoDeep || badQueue.isEmpty()) {
            endCheck(badQueue.isEmpty()
                    ? "全部可用，没有需要深度校验的源" : "");
            return;
        }
        // ② 深度校验失效源
        List<JSONObject> badList = new ArrayList<>(badQueue);
        AtomicInteger dPass = new AtomicInteger();
        AtomicInteger dFail = new AtomicInteger();
        progressText = "② 深度校验 0/" + badList.size();
        syncProgressViews();
        runQueue(badList, 2, (o, next) -> deepCheckOne(o, () -> {
            String u = o.optString("bookSourceUrl", "");
            Deep d = deep.get(u);
            if (d != null && "ok".equals(d.state)) dPass.incrementAndGet();
            else dFail.incrementAndGet();
            refreshRow(o);
            progressText = "② 深度校验 · 通过 " + dPass.get() + " · 失败 " + dFail.get();
            syncProgressViews();
            next.run();
        }), () -> uiPost(() -> finishDeep(badList)));
    }

    /** 深度校验完成后的收尾（主线程）：列出两次不合格 + 一键删除 */
    private void finishDeep(List<JSONObject> badList) {
        if (checkStop) {
            endCheck("已停止");
            return;
        }
        bothBad.clear();
        for (JSONObject o : badList) {
            if ("both".equals(statusKey(o.optString("bookSourceUrl", "")))) bothBad.add(o);
        }
        checkDoneText = bothBad.isEmpty()
                ? "深度校验完成，没有「两次都不合格」的源。"
                : "发现 " + bothBad.size() + " 个两次都不合格的源，可一键删除。";
        if (checkSummary != null) checkSummary.setText(checkDoneText);
        if (!bothBad.isEmpty()) renderBothBadActions();
        endCheck(null);
    }

    private void endCheck(String msg) {
        checkRunning = false;
        if (msg != null) {
            progressText = msg;
            checkDoneText = msg;
        }
        uiPost(() -> {
            // 进度态的 sheet 就地收起：重开即是结束态（结果回显 + 一键删除），
            // 别留着一张显示「进行中」布局的死面板
            if (checkSheetDialog != null && checkSheetDialog.isShowing()) {
                checkSheetDialog.dismiss();
            }
            paint();
        });
    }

    /** 单行状态变化：只更新那一行的副行控件，不整表重画（重画会把滚动位置顶回顶部） */
    private void refreshRow(JSONObject o) {
        uiPost(() -> {
            String url = o.optString("bookSourceUrl", "");
            TextView meta = statusMeta.get(url);
            View dot = statusDot.get(url);
            if (meta != null) meta.setText(buildMeta(o));
            if (dot != null) dot.setBackground(dotDrawable(statusKey(url)));
        });
    }

    /* ---------- 单源快速校验 ---------- */

    /**
     * 快速校验：只跑搜索一步。结果写 quick/qReason，命中书存 foundBook。
     * 判定与网页版一致：返回结果即有效；「均与关键词无关」算未判定不算失效。
     */
    private void quickCheckOne(JSONObject o, Runnable onDone) {
        final String url = o.optString("bookSourceUrl", "");
        if (checkStop) {
            onDone.run();
            return;
        }
        quick.put(url, "run");
        deep.remove(url); // 重新快速校验 → 旧的深度结论作废，否则「两次失效」拿旧数据骗人
        if (!isCheckable(o)) {
            quick.put(url, "nosearch"); // 只有发现页/登录态的源没有搜索能力，不算失效
            onDone.run();
            return;
        }
        if (!o.optBoolean("enabled", true)) {
            // SourceService 只跑启用的源；停用的源标未判定，不误报失效
            quick.put(url, "skip");
            qReason.put(url, "已停用，启用后才能校验");
            onDone.run();
            return;
        }
        SourceService svc = SourceService.get();
        if (svc == null) {
            quick.put(url, "bad");
            qReason.put(url, "书源服务未启动");
            onDone.run();
            return;
        }
        Set<String> one = new LinkedHashSet<>();
        one.add(url);
        svc.search(keyword, one, 1, res -> {
            if (checkStop) {
                onDone.run();
                return;
            }
            try {
                if (res == null) {
                    quick.put(url, "bad");
                    qReason.put(url, "无返回");
                } else if (!res.optBoolean("ok", false)) {
                    quick.put(url, "bad");
                    qReason.put(url, "请求失败：" + res.optString("error", "?"));
                } else {
                    JSONObject hitBook = null;
                    JSONArray groups = res.optJSONArray("groups");
                    if (groups != null) {
                        for (int i = 0; i < groups.length() && hitBook == null; i++) {
                            JSONObject g = groups.optJSONObject(i);
                            if (g == null) continue;
                            JSONArray hits = g.optJSONArray("hits");
                            if (hits == null) continue;
                            for (int j = 0; j < hits.length(); j++) {
                                JSONObject hh = hits.optJSONObject(j);
                                if (hh == null || !url.equals(hh.optString("sourceUrl", ""))) continue;
                                JSONObject b = hh.optJSONObject("book");
                                if (b != null) {
                                    hitBook = b;
                                    break;
                                }
                            }
                        }
                    }
                    if (hitBook != null) {
                        quick.put(url, "ok");
                        qReason.remove(url);
                        hitBook.put("sourceUrl", url);
                        foundBook.put(url, hitBook);
                    } else {
                        JSONArray fails = res.optJSONArray("fails");
                        String reason = "返回 0 条（规则未解析到结果）";
                        if (fails != null && fails.length() > 0) {
                            String fr = fails.optJSONObject(0).optString("reason", "");
                            if (!fr.isEmpty()) reason = fr;
                        }
                        if (reason.contains("均与关键词无关")) {
                            quick.put(url, "skip"); // 校验词不对不证明源坏了
                        } else {
                            quick.put(url, "bad");
                        }
                        qReason.put(url, reason);
                    }
                }
            } catch (Exception e) {
                quick.put(url, "bad");
                qReason.put(url, "异常：" + e.getMessage());
            }
            if ("bad".equals(quick.get(url))) badQueue.add(o);
            onDone.run();
        });
    }

    /* ---------- 单源深度校验（详情→目录→正文；搜索一步快速校验已跑过） ---------- */

    private void deepCheckOne(JSONObject o, Runnable onDone) {
        final String url = o.optString("bookSourceUrl", "");
        if (checkStop) {
            onDone.run();
            return;
        }
        final Deep d = new Deep();
        d.state = "run";
        deep.put(url, d);
        SourceService svc = SourceService.get();
        JSONObject book = foundBook.get(url);
        if (svc == null || book == null) {
            d.state = "bad";
            d.step = "搜索";
            d.reason = "没有可用的搜索结果";
            onDone.run();
            return;
        }
        final long t0 = System.currentTimeMillis();
        svc.info(book, r1 -> {
            if (checkStop) {
                onDone.run();
                return;
            }
            if (r1 == null || !r1.optBoolean("ok", false)) {
                d.state = "bad";
                d.step = "详情";
                d.reason = r1 == null ? "无返回" : r1.optString("error", "详情页解析为空");
                d.ms = System.currentTimeMillis() - t0;
                onDone.run();
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
            svc.toc(book, r2 -> {
                if (checkStop) {
                    onDone.run();
                    return;
                }
                JSONArray chs = r2 == null ? null : r2.optJSONArray("chapters");
                if (r2 == null || !r2.optBoolean("ok", false) || chs == null || chs.length() == 0) {
                    d.state = "bad";
                    d.step = "目录";
                    d.reason = r2 == null ? "无返回" : r2.optString("error", "未解析到章节");
                    d.ms = System.currentTimeMillis() - t0;
                    onDone.run();
                    return;
                }
                JSONObject ch = chs.optJSONObject(0);
                if (ch == null || ch.optString("url", "").isEmpty()) {
                    d.state = "bad";
                    d.step = "目录";
                    d.reason = "第一章没有正文地址";
                    d.ms = System.currentTimeMillis() - t0;
                    onDone.run();
                    return;
                }
                svc.content(book, ch, 30000, r3 -> {
                    if (checkStop) {
                        onDone.run();
                        return;
                    }
                    String content = r3 == null ? "" : r3.optString("content", "");
                    if (r3 != null && r3.optBoolean("ok", false) && !content.trim().isEmpty()) {
                        d.state = "ok";
                        d.step = "全部通过";
                        d.reason = "";
                        d.ms = System.currentTimeMillis() - t0;
                        d.chars = content.length();
                    } else {
                        d.state = "bad";
                        d.step = "正文";
                        d.reason = r3 == null ? "无返回" : r3.optString("error", "正文为空");
                        d.ms = System.currentTimeMillis() - t0;
                    }
                    onDone.run();
                });
            });
        });
    }

    /* ================= 批量操作栏 ================= */

    private LinearLayout buildCheckBar() {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ModouUi.dp(host, 10), ModouUi.dp(host, 6), ModouUi.dp(host, 10), ModouUi.dp(host, 6));
        bar.setBackgroundColor(ui.card);
        String[] defs = {"启用", "禁用", "校验", "删除", "导出", "取消"};
        for (final String act : defs) {
            TextView b = ModouUi.tv(host, act, 13, "删除".equals(act) ? ui.danger : ui.text);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, ModouUi.dp(host, 8), 0, ModouUi.dp(host, 8));
            b.setOnClickListener((v) -> doBatch(act));
            bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return bar;
    }

    private void doBatch(String act) {
        List<JSONObject> picked = new ArrayList<>();
        for (JSONObject o : items) if (sel.contains(o.optString("id", ""))) picked.add(o);
        if (picked.isEmpty() && !"取消".equals(act)) {
            ModouUi.toast(host, "先选择书源");
            return;
        }
        switch (act) {
            case "启用":
            case "禁用": {
                boolean on = "启用".equals(act);
                for (JSONObject o : picked) {
                    try {
                        o.put("enabled", on);
                        ModouStore.get(host).put("sources", o.toString());
                    } catch (Exception ignore) {
                        /* 不可能 */
                    }
                }
                ModouUi.toast(host, (on ? "已启用 " : "已禁用 ") + picked.size() + " 个");
                selMode = false;
                sel.clear();
                reload();
                break;
            }
            case "校验": {
                selMode = false;
                sel.clear();
                filter = "all";
                quick.clear();
                qReason.clear();
                deep.clear();
                foundBook.clear();
                bothBad.clear();
                checkDoneText = "";
                paint();
                startWorkers(picked, true); // 先开跑（后台继续，关面板不停）
                openCheckAllSheet();        // 再把进度面板摆出来
                break;
            }
            case "删除": {
                ModouUi.confirm(host, "删除书源", "删除选中的 " + picked.size() + " 个书源？此操作不可撤销。", () -> {
                    for (JSONObject o : picked) {
                        String u = o.optString("bookSourceUrl", "");
                        ModouStore.get(host).del("sources", o.optString("id", ""));
                        quick.remove(u);
                        qReason.remove(u);
                        deep.remove(u);
                    }
                    ModouUi.toast(host, "已删除 " + picked.size() + " 个");
                    selMode = false;
                    sel.clear();
                    reload();
                });
                break;
            }
            case "导出": {
                JSONArray a = new JSONArray();
                for (JSONObject o : picked) a.put(o);
                ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("墨读书源", a.toString()));
                ModouUi.toast(host, "已复制 " + picked.size() + " 个书源到剪贴板");
                break;
            }
            default: {
                selMode = false;
                sel.clear();
                paint();
                break;
            }
        }
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
                "将删除 " + dupFinal + " 个重复书源，每组保留 1 个，此操作不可撤销。", () -> {
                    int n = 0;
                    for (JSONObject[] g : groups) {
                        for (int i = 1; i < g.length; i++) {
                            JSONObject dd = g[i];
                            String u = dd.optString("bookSourceUrl", "");
                            ModouStore.get(host).del("sources", dd.optString("id", ""));
                            quick.remove(u);
                            qReason.remove(u);
                            deep.remove(u);
                            n++;
                        }
                    }
                    ModouUi.toast(host, "已删除 " + n + " 个重复书源");
                    d.dismiss();
                    reload();
                }));
    }

    /* ================= 编辑 / 导入导出（沿用） ================= */

    /**
     * 书源编辑器：新建（src=null）或编辑（src=已有记录）。
     * 常用字段单独成行；复杂规则走「原始 JSON」编辑区，二者合并保存，
     * 不会因为只填了几个框就把嵌套的 ruleSearch / ruleContent 等丢掉。
     */
    private void editSource(JSONObject src) {
        ScrollView sc = new ScrollView(host);
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ModouUi.dp(host, 14), ModouUi.dp(host, 8), ModouUi.dp(host, 14), 0);

        EditText name = ModouUi.input(host, "书源名称", src == null ? "" : src.optString("bookSourceName", ""), 1);
        box.addView(name);
        EditText url = ModouUi.input(host, "书源 URL（bookSourceUrl）",
                src == null ? "" : src.optString("bookSourceUrl", ""), 1);
        box.addView(url);
        EditText group = ModouUi.input(host, "分组（bookSourceGroup）",
                src == null ? "" : src.optString("bookSourceGroup", ""), 1);
        box.addView(group);
        EditText search = ModouUi.input(host, "搜索 URL（searchUrl）",
                src == null ? "" : src.optString("searchUrl", ""), 1);
        box.addView(search);
        EditText explore = ModouUi.input(host, "发现 URL（exploreUrl）",
                src == null ? "" : src.optString("exploreUrl", ""), 1);
        box.addView(explore);

        TextView jsonLabel = ModouUi.tv(host, "原始 JSON（复杂规则在此编辑，留空则用上方字段）", 11.5f, ui.sub);
        jsonLabel.setPadding(0, ModouUi.dp(host, 10), 0, ModouUi.dp(host, 4));
        box.addView(jsonLabel);
        String jsonInit = "";
        if (src != null) {
            try {
                jsonInit = src.toString(2);
            } catch (Exception ignore) {
                /* 序列化失败就用空，用户手动填 */
            }
        }
        EditText json = ModouUi.input(host, "粘贴完整书源 JSON", jsonInit, 6);
        box.addView(json);

        final boolean[] enabled = {src == null || src.optBoolean("enabled", true)};
        box.addView(ModouUi.switchRow(host, "启用", enabled[0], on -> enabled[0] = on));

        sc.addView(box);
        final Dialog d = ModouUi.sheet(host, src == null ? "新建书源" : "编辑书源", box);
        box.addView(ModouUi.sheetButtons(host, d, "取消", "保存", null, () -> {
            try {
                JSONObject base;
                String raw = String.valueOf(json.getText()).trim();
                if (!raw.isEmpty()) {
                    base = new JSONObject(raw);
                } else if (src != null) {
                    // 没填原始 JSON：以原记录为底，只覆盖上方几个可见字段
                    base = new JSONObject(src.toString());
                } else {
                    base = new JSONObject();
                }
                base.put("bookSourceName", String.valueOf(name.getText()).trim());
                base.put("bookSourceUrl", String.valueOf(url.getText()).trim());
                base.put("bookSourceGroup", String.valueOf(group.getText()).trim());
                base.put("searchUrl", String.valueOf(search.getText()).trim());
                base.put("exploreUrl", String.valueOf(explore.getText()).trim());
                base.put("enabled", enabled[0]);
                String su = base.optString("bookSourceUrl", "").trim();
                if (su.isEmpty()) {
                    ModouUi.toast(host, "书源 URL 不能为空");
                    return;
                }
                // id 规则必须与网页版 BookSource 一致，否则同一书源会存成两条
                base.put("id", su + "|" + base.optString("bookSourceName", ""));
                ModouStore.get(host).put("sources", base.toString());
                ModouUi.toast(host, "已保存");
                reload();
            } catch (Exception e) {
                ModouUi.toast(host, "保存失败：" + e.getMessage());
            }
        }));
    }

    /** 头部「⋯」菜单：低频操作收纳（检查重复 / 导出全部 / 粘贴导入 / 从文件导入） */
    private void openMoreSheet() {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(host, 14);
        box.setPadding(p, ModouUi.dp(host, 8), p, 0);
        final Dialog[] self = new Dialog[1];
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        TextView dd = button("检查重复", false);
        dd.setOnClickListener((v) -> {
            if (self[0] != null) self[0].dismiss();
            later(this::openDedupSheet);
        });
        box.addView(dd, rowLp);
        box.addView(spacer());

        TextView exp = button("导出全部", false);
        exp.setOnClickListener((v) -> {
            if (self[0] != null) self[0].dismiss();
            JSONArray a = new JSONArray();
            for (JSONObject o : items) a.put(o);
            ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("墨读书源", a.toString()));
            ModouUi.toast(host, "已复制 " + items.size() + " 个书源到剪贴板");
        });
        box.addView(exp, rowLp);
        box.addView(spacer());

        // 两个入口分开：从文件（本地 json/txt）最常用，粘贴留给从别处复制来的文本
        TextView impFile = button("从文件导入（.json / .txt）", true);
        impFile.setOnClickListener((v) -> {
            if (self[0] != null) self[0].dismiss();
            later(this::pickSourceFile);
        });
        box.addView(impFile, rowLp);
        box.addView(spacer());

        TextView impPaste = button("粘贴书源 JSON", false);
        impPaste.setOnClickListener((v) -> {
            if (self[0] != null) self[0].dismiss();
            later(this::showImport);
        });
        box.addView(impPaste, rowLp);

        self[0] = ModouUi.sheet(host, "更多操作", box);
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
        EditText ed = new EditText(host);
        ed.setHint("粘贴书源 JSON（单个对象或数组，可整段粘贴）");
        ed.setTextSize(13);
        ed.setTextColor(ui.text);
        ed.setHintTextColor(ui.sub);
        ed.setMinLines(6);
        ed.setMaxLines(10);
        ed.setBackground(ModouUi.round(ui.card, 8));
        int p = ModouUi.dp(host, 10);
        ed.setPadding(p, p, p, p);
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p + 4, p, p + 4, 0);
        // 给宽高：以前 addView 不带参数，输入框按 wrap_content 缩成一条细缝，
        // 用户看着像「点了没反应」
        box.addView(ed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final Dialog d;
        try {
            d = ModouUi.sheet(host, "导入书源", box);
        } catch (Throwable t) {
            ModouUi.toast(host, "面板打开失败：" + t.getMessage());
            return;
        }
        box.addView(ModouUi.sheetButtons(host, d, "取消", "导入", null,
                () -> doImport(String.valueOf(ed.getText()))));
    }

    /**
     * 导入书源。
     *
     * 解析 JSON 与逐条写库都放到后台：一份 Legado 导出的书源合集常有上百个源、
     * 几 MB 文本，`new JSONObject(s)` 加一百多次同步写库在主线程上就是几秒到十几秒的
     * 卡死 —— 用户看到的是「点了导入，什么反应也没有」。
     */
    private void doImport(String text) {
        final String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            ModouUi.toast(host, "内容为空");
            return;
        }
        ModouUi.toast(host, "正在导入…");
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
            if (err == null) {
                ModouStore store = ModouStore.get(host);
                for (JSONObject o : add) {
                    String url = o.optString("bookSourceUrl", "").trim();
                    if (url.isEmpty()) {
                        skip++;
                        continue;
                    }
                    try {
                        o.put("id", url + "|" + o.optString("bookSourceName", ""));
                        o.put("enabled", o.optBoolean("enabled", true));
                    } catch (Exception ignore) {
                        /* 不可能 */
                    }
                    if (store.put("sources", o.toString())) n++;
                }
            }
            final int fn = n;
            final int fskip = skip;
            final String ferr = err;
            host.runOnUiThread(() -> {
                reload();
                if (ferr != null) {
                    ModouUi.toast(host, "导入失败：" + ferr);
                } else if (fn == 0) {
                    ModouUi.toast(host, fskip > 0
                            ? "没有可导入的书源：这 " + fskip + " 个都缺少 bookSourceUrl"
                            : "没有可导入的书源");
                } else {
                    ModouUi.toast(host, "已导入 " + fn + " 个书源"
                            + (fskip > 0 ? "，跳过 " + fskip + " 个（缺地址）" : ""));
                }
            });
        }, "modou-source-import").start();
    }

    private static String brief(Throwable e) {
        String m = e == null ? "" : String.valueOf(e.getMessage());
        if (m.length() > 60) m = m.substring(0, 60) + "…";
        return m;
    }

    private TextView button(String text, boolean primary) {
        TextView t = ModouUi.tv(host, text, 14, primary ? 0xFFFFFFFF : ui.text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ModouUi.dp(host, 8), ModouUi.dp(host, 10), ModouUi.dp(host, 8), ModouUi.dp(host, 10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ui.accent : ui.card);
        g.setCornerRadius(ModouUi.dp(host, 8));
        t.setBackground(g);
        return t;
    }

}
