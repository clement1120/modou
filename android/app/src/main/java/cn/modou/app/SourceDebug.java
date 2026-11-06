package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 书源调试台：把一个源在「搜索 → 详情 → 目录 → 正文」四步里到底发生了什么摊开给用户看。
 *
 * <p>为什么需要它：书源失效时，原生侧只有一句「没有解析出结果」。到底是没联网、被风控、
 * 站点改版、CSS 选错、还是 JS 规则报错 —— 用户完全无从判断，只能盲猜删源。
 * 这里把每一步的**请求地址、HTTP 状态、编码、字节数、命中的规则、提取到的字段、响应原文片段**
 * 一次性列出来，源坏在哪一眼就能看见。
 *
 * <p>数据全部来自引擎回传的 {@code trace}（见 web/js/engine/source.js）：引擎本来就记录了这些，
 * 以前只在日志里滚一下没人看得到；这里只负责排版展示，不重复发请求。
 */
final class SourceDebug {

    private final Activity host;
    private final ModouUi ui;
    private final SpannableStringBuilder buf = new SpannableStringBuilder();

    private TextView view;
    private Dialog dialog;
    private JSONObject source;
    private boolean stopped;
    private boolean running;
    private android.widget.EditText kwEdit;
    /** 搜索 0 结果时，这里放「自动探测到的搜索入口 + 应用」按钮 */
    private LinearLayout fixBox;

    SourceDebug(Activity h) {
        this.host = h;
        this.ui = ModouUi.of(h);
    }

    /** 打开调试台并立刻开跑 */
    void open(JSONObject src) {
        source = src;
        String name = src.optString("bookSourceName", src.optString("bookSourceUrl", "书源"));
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView tip = ModouUi.tv(host,
                "先在上方填要搜的书名（默认「我的」，可直接删改成真实书名），再点「开始调试」。\n"
                        + "按「搜索 → 详情 → 目录 → 正文」逐步执行，显示请求地址、返回状态与规则提取结果。"
                        + "不发真实书籍请求之外的东西，随时可以关掉。", 12f, ui.sub);
        tip.setLineSpacing(ModouUi.dp(host, 2), 1.35f);
        tip.setPadding(0, 0, 0, ModouUi.dp(host, 10));
        box.addView(tip);

        // 校验前手动填关键词：默认「我的」（源里写死的就是它），用户可直接删改成真实书名。
        // 留空则自动从首页取书名；填了就用填的，跳过自动探测（避免探测词不准 / 一连发请求触发站点风控）。
        kwEdit = ModouUi.input(host, "要搜的书名（可直接删改，留空自动取首页）", "我的", 1);
        box.addView(kwEdit);
        LinearLayout kwBar = new LinearLayout(host);
        kwBar.setOrientation(LinearLayout.HORIZONTAL);
        kwBar.setPadding(0, 0, 0, ModouUi.dp(host, 6));
        kwBar.addView(pill("开始调试", true, () -> {
            if (running) return;
            String kw = kwEdit.getText().toString().trim();
            if (kw.isEmpty()) autoProbeAndBegin();
            else begin(source, kw, source.optString("bookSourceUrl", ""));
        }), weight(1f, 0));
        box.addView(kwBar);

        view = ModouUi.tv(host, "填好书名后点「开始调试」。", 11.5f, ui.text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setLineSpacing(ModouUi.dp(host, 2), 1.15f);
        view.setTextIsSelectable(true);
        ScrollView sc = new ScrollView(host);
        sc.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ModouUi.dp(host, 360)));

        // 搜索入口自动修复区：仅当探测到「站点真实搜索入口」时才填充，平时为空不占位
        fixBox = new LinearLayout(host);
        fixBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(fixBox);

        LinearLayout footer = new LinearLayout(host);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, ModouUi.dp(host, 12), 0, 0);
        footer.addView(pill("复制日志", false, () -> copyLog()), weight(1f, 0));
        footer.addView(pill("重新调试", true, () -> {
            if (running) return;
            restart();
        }), weight(1.3f, ModouUi.dp(host, 10)));

        dialog = ModouUi.sheet(host, "调试 · " + name, box, footer);
        dialog.setOnDismissListener(d -> stopped = true);
    }

    private void restart() {
        buf.clear();
        render();
        String kw = kwEdit != null ? kwEdit.getText().toString().trim() : "";
        if (kw.isEmpty()) autoProbeAndBegin();
        else begin(source, kw, source.optString("bookSourceUrl", ""));
    }

    private void autoProbeAndBegin() {
        JSONObject src = source;
        if (src == null) return;
        final String url = src.optString("bookSourceUrl", "");
        SourceService svc = SourceService.get();
        if (svc == null) {
            line("书源服务未启动：请退出重进 App 后再试。", ui.sub);
            return;
        }
        stopped = false;
        running = true;
        // 把源地址写进日志正文：用户复制这一段给谁都能直接定位是哪个源
        line("源 " + src.optString("bookSourceName", "（未命名）") + " · " + url, ui.sub);
        // 关键词阶梯：源作者的 checkKeyWord → 站点首页上的真实书名（探针）→ 兜底词。
        // 不再拿「三体」这种题材词去问言情站 —— 0 条只说明站里没这本书，不说明源坏了。
        String ck = SourcesPanel.checkWordOf(src);
        if (!ck.isEmpty()) {
            begin(src, ck, url);
            return;
        }
        line("正在从站点首页取一个真实书名当探测词…", ui.sub);
        svc.probe(src, probeRes -> {
            if (stopped) return;
            String p = probeRes != null && probeRes.optBoolean("ok", false)
                    ? probeRes.optString("keyword", "").trim() : "";
            if (p.isEmpty()) line("首页没认出书名，改用兜底词「我的」再试。", ui.sub);
            else line("探测词：站点首页的《" + p + "》（站内一定有这本书）", ui.sub);
            begin(src, p.isEmpty() ? "我的" : p, url, probeRes == null ? null : probeRes.optJSONArray("candidates"), 0);
        });
    }

    /** 拿到关键词后真正开始四步调试 */
    private void begin(final JSONObject src, final String kw, final String url) {
        begin(src, kw, url, null, 0);
    }

    /**
     * @param cands 首页上的候选书名（探针词）。这个词搜不到时自动换下一个 ——
     *              站内搜索多为模糊匹配，完整书名未必命中（实测「翘臀小奶狗」0 条、「小奶狗」4 条）。
     * @param ci    当前用的是第几个候选
     */
    private void begin(final JSONObject src, final String kw, final String url,
                       final JSONArray cands, final int ci) {
        SourceService svc = SourceService.get();
        if (svc == null) {
            line("书源服务未启动：请退出重进 App 后再试。", ui.sub);
            return;
        }
        line("① 搜索 · 关键词「" + kw + "」", ui.accent, true);
        final long tSearch = System.currentTimeMillis();
        // waitRate=true：单源调试时站点若回「搜索间隔限制」，等够它要求的时间再搜一次，
        // 而不是把「站点防刷」误报成源失效、让用户白改 searchUrl。
        svc.search(kw, Collections.singleton(url), 1, true, true, true, res -> {
            if (stopped) return;
            renderSearch(res, url);
            line("耗时 " + (System.currentTimeMillis() - tSearch) + " ms", ui.sub);
            render();
            JSONObject book = firstBook(res, url);
            if (book == null && cands != null && ci + 1 < cands.length()) {
                String next = cands.optString(ci + 1, "").trim();
                if (!next.isEmpty() && !next.equals(kw)) {
                    line("这个词没搜到书，换首页上的下一个候选词再试。", ui.sub);
                    begin(src, next, url, cands, ci + 1);
                    return;
                }
            }
            if (book == null) {
                running = false;
                line("\n搜索没有拿到可用书籍（书名 + 详情地址），后续步骤无法继续。", ui.sub);
                line("正在自动探测站点真实搜索入口（扒首页搜索框 / 试常见路径）…", ui.sub);
                render();
                discoverAndOffer(kw);
                return;
            }
            line("\n② 详情 · " + safe(book.optString("bookUrl")), ui.accent, true);
            render();
            final long tInfo = System.currentTimeMillis();
            svc.info(book, 30000, infoRes -> {
                if (stopped) return;
                renderStep("详情", infoRes);
                line("耗时 " + (System.currentTimeMillis() - tInfo) + " ms", ui.sub);
                JSONObject info = infoRes == null ? null : infoRes.optJSONObject("info");
                JSONObject forToc = info != null ? info : book;
                if (info == null) {
                    // 与体检同一口径：详情页挂了不代表书打不开，有目录地址就继续
                    String tu = safe(book.optString("tocUrl"));
                    line(tu.isEmpty()
                            ? "详情未通过，且搜索没有给出目录地址。"
                            : "详情未通过，继续用搜索给出的目录地址：" + tu, ui.sub);
                }
                line("\n③ 目录 · " + safe(forToc.optString("tocUrl", forToc.optString("bookUrl"))), ui.accent, true);
                render();
                final long tToc = System.currentTimeMillis();
                svc.toc(forToc, 45000, tocRes -> {
                    if (stopped) return;
                    renderStep("目录", tocRes);
                    line("耗时 " + (System.currentTimeMillis() - tToc) + " ms", ui.sub);
                    JSONArray chs = tocRes == null ? null : tocRes.optJSONArray("chapters");
                    JSONObject ch = chs == null || chs.length() == 0 ? null : chs.optJSONObject(0);
                    if (ch == null) {
                        running = false;
                        line("\n目录为空，正文步骤跳过。", ui.sub);
                        render();
                        return;
                    }
                    line("\n④ 正文 · " + safe(ch.optString("url")), ui.accent, true);
                    render();
                    final long tContent = System.currentTimeMillis();
                    svc.content(forToc, ch, 30000, cRes -> {
                        if (stopped) return;
                        renderStep("正文", cRes);
                        line("耗时 " + (System.currentTimeMillis() - tContent) + " ms", ui.sub);
                        running = false;
                        line("\n调试结束。", ui.sub);
                        render();
                    });
                });
            });
        });
    }

    /* ---------------- 渲染 ---------------- */

    private void renderSearch(JSONObject res, String url) {
        if (res == null) {
            line("没有返回结果（书源服务无响应）。", ui.sub);
            return;
        }
        JSONArray tr = null;
        JSONObject traces = res.optJSONObject("traces");
        if (traces != null) tr = traces.optJSONArray(url);
        if (tr != null && tr.length() > 0) {
            appendTrace(tr);
        } else {
            // 老引擎/请求在发出去之前就失败了：只能给出错误原文
            JSONObject f = firstFail(res, url);
            if (f != null) line("失败：" + safe(f.optString("reason")), ui.sub);
            else if (!res.optBoolean("ok", false)) line("失败：" + safe(res.optString("error")), ui.sub);
        }
        JSONObject book = firstBook(res, url);
        if (book != null) {
            line("→ 取到书：《" + safe(book.optString("name")) + "》 "
                    + safe(book.optString("author")) + " · " + safe(book.optString("bookUrl")), ui.text);
        }
        render();
    }

    private void renderStep(String label, JSONObject res) {
        if (res == null) {
            line("没有返回结果（步骤超时或服务无响应）。", ui.sub);
            render();
            return;
        }
        JSONArray tr = res.optJSONArray("trace");
        if (tr != null && tr.length() > 0) appendTrace(tr);
        if (!res.optBoolean("ok", false)) {
            line("失败：" + safe(res.optString("error")), ui.sub);
        }
        render();
    }

    /** 一条链路追踪：URL / 状态 / 编码 / 规则 / 命中 / 原文片段 */
    private void appendTrace(JSONArray tr) {
        for (int i = 0; i < tr.length(); i++) {
            JSONObject t = tr.optJSONObject(i);
            if (t == null) continue;
            String st = safe(t.optString("status"));
            String meth = safe(t.optString("method", "GET"));
            String u = safe(t.optString("url"));
            if (!u.isEmpty()) line(meth + " " + u, ui.text);
            // 301/302 跳转后的真实地址：po5.net → 52shuku.net 这类跨域跳转很常见，
            // 不显示出来用户会以为是「请求发错了域名」，也看不出规则该按哪个域解析。
            String fin = safe(t.optString("finalUrl"));
            if (!fin.isEmpty() && !fin.equals(u)) line("→ 跳转至 " + fin, ui.sub);
            // 书源里的地址常带 ,{JSON选项}；实际发出去的是剥掉选项后的 URL。
            // 两者不一致时把模板也写出来，才能一眼看出「地址被拼坏了」还是「源本来就这么写」。
            String tpl = safe(t.optString("tpl"));
            if (!tpl.isEmpty() && !tpl.equals(u)) {
                line("   源里写的是: " + cut(tpl, 240), ui.sub);
            }
            StringBuilder meta = new StringBuilder();
            if (!st.isEmpty()) meta.append("状态 ").append(st);
            String cs = safe(t.optString("charset"));
            if (!cs.isEmpty()) meta.append(" · ").append(cs);
            int bytes = t.optInt("bytes", 0);
            if (bytes > 0) meta.append(" · ").append(bytes).append(" 字符");
            String ct = safe(t.optString("contentType"));
            if (!ct.isEmpty()) meta.append(" · ").append(ct);
            if (meta.length() > 0) line(meta.toString(), ui.sub);

            // 证书降级：站方证书过期/不受信时网络层会自动改 http 或忽略校验。
            // 这行必须显眼 —— 否则用户会以为「源还是坏的」或「我读的是正常 HTTPS」。
            String sslNote = safe(t.optString("sslNote"));
            if (!sslNote.isEmpty()) line("⚠ " + sslNote, ui.accent);

            // POST 源的关键词只活在请求体里：0 条时这一行能直接看出参数名对不对、
            // 关键词有没有真的编码进去（曾经因为编码问题整源永远 0 条）
            String reqBody = safe(t.optString("body"));
            if (!reqBody.isEmpty()) line("请求体: " + cut(reqBody, 200), ui.sub);

            String rule = safe(t.optString("rule"));
            if (!rule.isEmpty()) {
                line("规则: " + (rule.length() > 400 ? rule.substring(0, 400) + "…" : rule), ui.sub);
            }
            if (t.has("hits") || t.has("kept")) {
                line("命中 " + t.optInt("hits", 0) + " 条 / 保留 " + t.optInt("kept", 0) + " 条", ui.sub);
            }
            if (t.has("chapters")) line("章节 " + t.optInt("chapters", 0) + " 条", ui.sub);
            if (t.has("contentLen")) line("正文长度 " + t.optInt("contentLen", 0) + " 字符", ui.sub);

            // 引擎给的「为什么 0 条」结论。以前只写进 trace.diag 供校验列表拼错误文案，
            // 调试台一个字都不显示 —— 用户只能对着「命中 0 条」猜，正是最需要答案的时候没答案。
            String diag = safe(t.optString("diag"));
            if (!diag.isEmpty()) line("诊断: " + diag, ui.accent);

            // 正文规则失效（站点改版，老的 #nr1 之类直接消失）时引擎会兜底识别，
            // 这里把建议规则直接写出来，用户复制粘贴就能修好这个源。
            String autoRule = safe(t.optString("autoRule"));
            if (!autoRule.isEmpty()) {
                // 搜索链路给的是列表规则、正文链路给的是正文容器规则，建议文案由引擎决定。
                // 写死成 ruleContent 会让搜索兜底提示用户去改正文规则，纯属误导。
                String hint = safe(t.optString("autoHint"));
                line("规则未命中，已自动识别为: " + autoRule, ui.text);
                line(hint.isEmpty() ? "建议把 ruleContent.content 改成上面这条" : hint, ui.sub);
            }
            JSONArray cands = t.optJSONArray("candidates");
            if (cands != null && cands.length() > 0) {
                line("候选容器（按可能性排序）:", ui.sub);
                for (int k = 0; k < cands.length(); k++) {
                    JSONObject c = cands.optJSONObject(k);
                    if (c == null) continue;
                    line("  · " + safe(c.optString("rule")) + "  （" + c.optInt("cn", 0) + " 字）", ui.sub);
                }
            }

            JSONArray sample = t.optJSONArray("sample");
            if (sample != null && sample.length() > 0) {
                for (int k = 0; k < Math.min(5, sample.length()); k++) {
                    JSONObject c = sample.optJSONObject(k);
                    if (c == null) continue;
                    line("  · " + safe(c.optString("name", c.optString("chapterName")))
                            + " → " + safe(c.optString("url", c.optString("bookUrl"))), ui.sub);
                }
            }
            JSONArray raw = t.optJSONArray("raw");
            if (raw != null && raw.length() > 0) {
                line("原始提取（未过滤）:", ui.sub);
                for (int k = 0; k < Math.min(3, raw.length()); k++) {
                    JSONObject r = raw.optJSONObject(k);
                    if (r == null) continue;
                    line("  · name=" + safe(r.optString("name"))
                            + " | author=" + safe(r.optString("author"))
                            + " | bookUrl=" + safe(r.optString("bookUrl")), ui.sub);
                }
            }
            JSONObject fields = t.optJSONObject("fields");
            if (fields != null) {
                // 只列有值的字段会让「配了规则却取空」隐形 —— 用户看到少了 intro 却不知道原因。
                // 这里把规则原文一起对上：空值且配了规则的，直接标出来。
                Map<String, String> ruleMap = ruleMapOf(safe(t.optString("rule")));
                LinkedHashSet<String> names = new LinkedHashSet<>();
                JSONArray ns = fields.names();
                if (ns != null) for (int k = 0; k < ns.length(); k++) names.add(ns.optString(k));
                names.addAll(ruleMap.keySet());
                for (String n : names) {
                    String v = safe(fields.optString(n));
                    if (!v.isEmpty()) {
                        line("  " + n + " = " + cut(v, 120), ui.sub);
                        continue;
                    }
                    String r0 = ruleMap.get(n);
                    if (r0 == null || r0.isEmpty()) continue;   // 源没配这条规则，空着是正常的
                    line("  " + n + " =（空）  ← 规则: " + cut(r0, 90), ui.sub);
                }
            }
            String prev = safe(t.optString("preview"));
            if (!prev.isEmpty()) line("正文开头: " + cut(prev, 220), ui.sub);
            JSONArray logs = t.optJSONArray("logs");
            if (logs != null) {
                for (int k = 0; k < Math.min(12, logs.length()); k++) {
                    String s = logs.optString(k, "");
                    // 上面已经用醒目色单独渲染过诊断，日志里这条同义句就不再重复
                    if (!s.isEmpty() && !diag.isEmpty() && s.startsWith("诊断: ")) continue;
                    if (!s.isEmpty()) line("  log: " + cut(s, 200), ui.sub);
                }
            }
            String err = safe(t.optString("error"));
            if (!err.isEmpty()) line("错误: " + err, ui.sub);

            String html = safe(t.optString("html"));
            if (!html.isEmpty()) {
                line("—— 响应片段 ——", ui.sub);
                line(cut(html, 900), ui.text);
            }
        }
    }

    /* ---------------- 搜索入口自动修复 ---------------- */

    /** 搜索 0 结果时，让引擎去首页扒搜索框、试常见路径，探测站点真实搜索入口 */
    private void discoverAndOffer(String kw) {
        final SourceService svc = SourceService.get();
        if (svc == null) {
            line("书源服务未启动：请退出重进 App 后再试。", ui.sub);
            render();
            return;
        }
        final JSONObject src = source;
        if (src == null) return;
        svc.discoverSearch(src, kw, res -> {
            if (stopped) return;
            boolean ok = res != null && res.optBoolean("ok", false);
            if (!ok) {
                String reason = res == null ? "无响应"
                        : res.optString("reason", res.optString("error", "未找到"));
                line("自动探测未找到可用搜索入口：" + reason
                        + "（可能是搜索由 JS 渲染、或需登录后才能搜）。", ui.sub);
                render();
                return;
            }
            final String newSearch = res.optString("searchUrl", "").trim();
            String cur = src.optString("searchUrl", "").trim();
            if (newSearch.isEmpty() || newSearch.equals(cur)) {
                line("探测到的入口与当前一致，问题可能不在搜索地址（看上面的诊断）。", ui.sub);
                render();
                return;
            }
            line("✓ 探测到站点真实搜索入口：", ui.text);
            line("    " + newSearch, ui.accent);
            line("    当前源搜索地址疑似写错（缺 {{key}} 或路径失效），可一键改写。", ui.sub);
            render();
            showSearchFix(newSearch);
        });
    }

    /** 在日志下方展示「应用 / 忽略」按钮 */
    private void showSearchFix(final String newSearch) {
        if (fixBox == null || host.isFinishing()) return;
        host.runOnUiThread(() -> {
            fixBox.removeAllViews();
            TextView tip = ModouUi.tv(host, "用上面探测到的搜索地址改写这个源？", 13f, ui.text);
            tip.setPadding(0, ModouUi.dp(host, 6), 0, ModouUi.dp(host, 8));
            fixBox.addView(tip);
            LinearLayout bar = new LinearLayout(host);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.addView(pill("应用此搜索地址", true, () -> applySearchFix(newSearch)), weight(1f, 0));
            bar.addView(pill("忽略", false, () -> fixBox.removeAllViews()), weight(1f, ModouUi.dp(host, 10)));
            fixBox.addView(bar);
        });
    }

    /** 改写该源 searchUrl 并持久化，然后重跑校验确认 */
    private void applySearchFix(final String newSearch) {
        if (source == null) return;
        final String url = source.optString("bookSourceUrl", "");
        boolean ok = SourcesPanel.patchSourceSearchUrl(host, url, newSearch);
        if (!ok) {
            ModouUi.toast(host, "改写失败：源可能已被删除");
            return;
        }
        try { source.put("searchUrl", newSearch); } catch (Exception ignore) { /* 忽略 */ }
        ModouUi.toast(host, "已改写搜索地址，正在重跑校验…");
        if (fixBox != null) fixBox.removeAllViews();
        buf.clear();
        render();
        String kw = kwEdit != null ? kwEdit.getText().toString().trim() : "";
        begin(source, kw.isEmpty() ? "我的" : kw, url);
    }

    /* ---------------- 小工具 ---------------- */

    private JSONObject firstBook(JSONObject res, String url) {
        if (res == null) return null;
        JSONArray groups = res.optJSONArray("groups");
        if (groups == null) return null;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            JSONArray hits = g.optJSONArray("hits");
            if (hits == null) continue;
            for (int k = 0; k < hits.length(); k++) {
                JSONObject h = hits.optJSONObject(k);
                if (h == null) continue;
                if (!url.equals(h.optString("sourceUrl"))) continue;
                JSONObject b = h.optJSONObject("book");
                if (b != null && !safe(b.optString("bookUrl")).isEmpty()) return b;
            }
        }
        return null;
    }

    private JSONObject firstFail(JSONObject res, String url) {
        if (res == null) return null;
        JSONArray fails = res.optJSONArray("fails");
        if (fails == null) return null;
        for (int i = 0; i < fails.length(); i++) {
            JSONObject f = fails.optJSONObject(i);
            if (f == null) continue;
            // fails 只带书源名，单源搜索时它就是我们要的那条
            if (fails.length() == 1) return f;
            String n = f.optString("name", "");
            if (name().equals(n)) return f;
        }
        return null;
    }

    private String name() {
        return source == null ? "" : source.optString("bookSourceName", "");
    }

    private void line(String s, int color) {
        line(s, color, false);
    }

    /** 追加一行：stage=true 时加粗并留出段前空行 */
    private void line(String s, int color, boolean stage) {
        int start = buf.length();
        if (stage && start > 0) buf.append('\n');
        int s0 = buf.length();
        buf.append(s).append('\n');
        if (stage) {
            buf.setSpan(new StyleSpan(Typeface.BOLD), s0, buf.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        buf.setSpan(new ForegroundColorSpan(color), s0, buf.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void render() {
        if (view == null || stopped || host.isFinishing()) return;
        view.setText(buf);
        // 自动滚到底：调试时用户关心的是「最新那一步」
        View parent = (View) view.getParent();
        if (parent instanceof ScrollView) ((ScrollView) parent).post(() -> {
            if (!stopped) ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
        });
    }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("书源调试日志",
                        name() + "\n" + buf.toString()));
                ModouUi.toast(host, "调试日志已复制");
            }
        } catch (Throwable t) {
            ModouUi.toast(host, "复制失败：" + t.getMessage());
        }
    }

    private TextView pill(String text, boolean primary, Runnable r) {
        TextView t = ModouUi.tv(host, text, 15, primary ? 0xFFFFFFFF : ui.text);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, ModouUi.dp(host, 12), 0, ModouUi.dp(host, 12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(ModouUi.dp(host, 12));
        bg.setColor(primary ? ui.accent : ModouUi.alpha(ui.text, 0x0E));
        t.setBackground(bg);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private LinearLayout.LayoutParams weight(float w, int leftMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        p.leftMargin = leftMargin;
        return p;
    }

    private static String safe(String s) {
        // org.json 的 optString 对 JSON null 会返回字符串 "null"，直接显示成「name = null」
        // 会让人以为字段里有值叫 null。统一当空处理。
        if (s == null || "null".equals(s)) return "";
        return s;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.replace("\r", "");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** trace.rule 里的「name=h1@text / author=...」解析成字段 → 规则，用于标出取空的字段 */
    private static Map<String, String> ruleMapOf(String rule) {
        Map<String, String> m = new LinkedHashMap<>();
        if (rule == null || rule.isEmpty()) return m;
        for (String ln : rule.split("\n")) {
            int i = ln.indexOf('=');
            if (i <= 0) continue;
            String k = ln.substring(0, i).trim();
            // 只认单标识符键，避免把「规则: xxx」这类说明行也当字段
            if (k.isEmpty() || !k.matches("[A-Za-z_][\\w]*")) continue;
            m.put(k, ln.substring(i + 1).trim());
        }
        return m;
    }
}
