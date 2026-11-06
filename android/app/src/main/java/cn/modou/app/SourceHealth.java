package cn.modou.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 书源健康度：谁坏了、什么时候坏的、坏在哪一步。
 *
 * <p>以前书源"天天坏"只能靠猜：搜索少了几个源，用户根本分不清是站点改版、
 * 网络抽风，还是自己把规则改坏了。这里记三件事：
 * <ul>
 *   <li>最近一次成功的时间；</li>
 *   <li>近 7 天的成功 / 失败次数（滚动窗口，过期自动清零，不用存每次请求）；</li>
 *   <li>最近一次失败的原因分类：网络错误 / 超时 / 规则未匹配 / 正则解析报错。</li>
 * </ul>
 *
 * <p>为什么用真实列而不是一条 JSON：这些字段要按"近 7 天规则全部失效"筛出来做批量禁用，
 * 存 JSON 就得把全表读出来在 Java 里算；建列之后一条 WHERE 就够，几百个源也是毫秒级。
 *
 * <p>写操作全部丢到 IO 池：记录健康度是搜索回调里的附带动作，
 * 绝不能因为一次 SQLite 写把正在刷新的搜索结果卡住。
 */
final class SourceHealth {

    private static final String TAG = "SourceHealth";

    static final String KIND_NET = "连接错误";
    static final String KIND_TIMEOUT = "超时";
    static final String KIND_EMPTY = "规则未匹配";
    static final String KIND_REGEX = "正则解析报错";
    /**
     * 4xx（鉴权/限流/路径过期）/5xx（服务端错误）：站点「回了响应但拒绝/出错」，
     * 与「连接层失败（超时/DNS/SSL/连不上）」语义完全不同。后者才算「不可达」，
     * 前者网络是通的、源没坏，只是被封或路径失效 —— 绝不能累计进 unreach_cnt 当「疑似不可达」。
     */
    static final String KIND_REJECT = "被拒绝/服务端错误";
    static final String KIND_OTHER = "未知原因";
    /**
     * 体检/批量校验阶段的失败：只进失败原文流水供诊断，不写 source_health。
     * 它不是一次真实的阅读请求，不能当「这个源坏了」的样本。
     */
    static final String KIND_CHECK = "体检未判定";

    /** 统计窗口：7 天。过期后计数清零重来，避免"半年前失败过一次"永远挂着 */
    private static final long WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
    /**
     * 一键禁用的最低样本数：只失败过 1 次就禁用太武断 ——
     * 很可能只是那一刻网络抖了一下，用户第二天会发现源被自己"误杀"。
     */
    static final int MIN_SAMPLE = 2;

    private SourceHealth() {
    }

    static void ensureTable(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS source_health ("
                    + "url TEXT PRIMARY KEY,"
                    + "last_ok INTEGER NOT NULL DEFAULT 0,"
                    + "last_fail INTEGER NOT NULL DEFAULT 0,"
                    + "ok_cnt INTEGER NOT NULL DEFAULT 0,"
                    + "fail_cnt INTEGER NOT NULL DEFAULT 0,"
                    + "unreach_cnt INTEGER NOT NULL DEFAULT 0,"
                    + "win_start INTEGER NOT NULL DEFAULT 0,"
                    + "err_kind TEXT,"
                    + "err_msg TEXT)");
            // 旧库升级：表已存在时上面的 CREATE 不会加新列，这里补 ALTER（列已存在则忽略）
            try {
                db.execSQL("ALTER TABLE source_health ADD COLUMN unreach_cnt INTEGER NOT NULL DEFAULT 0");
            } catch (Throwable ignore) {
                /* 列已存在 */
            }
            // 失败原文流水：列表里那一行只够放「上次坏在哪」，用户想知道「到底报了什么」
            // 必须留最近几次的原文。只留失败（成功没什么可看的），按 url+时间建索引。
            db.execSQL("CREATE TABLE IF NOT EXISTS source_err ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "url TEXT NOT NULL,"
                    + "ts INTEGER NOT NULL,"
                    + "kind TEXT,"
                    + "msg TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_source_err_url ON source_err(url, ts)");
        } catch (Throwable t) {
            Log.w(TAG, "create table failed", t);
        }
    }

    /* ---------------- 记录 ---------------- */

    static void ok(Context c, String url) {
        record(c, url, true, null, null);
    }

    static void fail(Context c, String url, String kind, String msg) {
        // 断网是手机的问题，不是书源的问题：记进去会把一屏好好的源标红，
        // 用户顺手「一键禁用失败率 100%」就等于把自己能看的书全删了。
        if (msg != null && msg.contains("没有网络")) return;
        record(c, url, false, kind, msg);
    }

    /**
     * 体检/批量校验的失败：只留一条原文到 source_err 供「书源详情 → 最近失败」查看，
     * <b>绝不</b>增加 fail_cnt、不改 err_kind、不累计 unreach_cnt。
     *
     * <p>以前体检直接调 {@link #fail}：一轮 5 路并发打几百个站，慢站和风控站成片超时，
     * 刚导入的新源一次真实阅读都没跑过就背上两三条失败样本，行尾立刻挂「高病发」，
     * 还凑够了 {@link #MIN_SAMPLE} 被「一键禁用规则失效源」捞走。体检是探测，不是使用记录。
     */
    static void noteCheck(Context c, String url, String msg) {
        if (c == null || url == null || url.isEmpty() || url.startsWith("local://")) return;
        if (msg != null && msg.contains("没有网络")) return;
        final Context app = c.getApplicationContext();
        final String m = cut(msg, 160);
        TaskPools.io().execute(() -> {
            try {
                SQLiteDatabase db = ModouStore.get(app).getWritableDatabase();
                long now = System.currentTimeMillis();
                ContentValues e = new ContentValues();
                e.put("url", url);
                e.put("ts", now);
                e.put("kind", KIND_CHECK);
                e.put("msg", m);
                db.insert("source_err", null, e);
                db.execSQL("DELETE FROM source_err WHERE ts<?", new Object[]{now - WINDOW_MS});
                db.execSQL("DELETE FROM source_err WHERE url=? AND id NOT IN "
                                + "(SELECT id FROM source_err WHERE url=? ORDER BY ts DESC LIMIT 30)",
                        new Object[]{url, url});
            } catch (Throwable t) {
                Log.w(TAG, "noteCheck failed: " + url, t);
            }
        });
    }

    private static void record(Context ctx, String url, boolean success, String kind, String msg) {
        if (ctx == null || url == null || url.isEmpty()) return;
        // 本地书没有「书源」这回事：记进去只会在书源列表里多出一条永远匹配不到的脏数据
        if (url.startsWith("local://")) return;
        final Context app = ctx.getApplicationContext();
        final String k = kind == null ? "" : kind;
        final String m = cut(msg, 160);
        TaskPools.io().execute(() -> write(app, url, success, k, m));
    }

    private static void write(Context app, String url, boolean success, String kind, String msg) {
        try {
            SQLiteDatabase db = ModouStore.get(app).getWritableDatabase();
            long now = System.currentTimeMillis();
            long edge = now - WINDOW_MS;
            // 先确保行存在（主键冲突时什么都不做）
            ContentValues seed = new ContentValues();
            seed.put("url", url);
            seed.put("last_ok", 0);
            seed.put("last_fail", 0);
            seed.put("ok_cnt", 0);
            seed.put("fail_cnt", 0);
            seed.put("win_start", now);
            seed.put("err_kind", "");
            seed.put("err_msg", "");
            db.insertWithOnConflict("source_health", null, seed, SQLiteDatabase.CONFLICT_IGNORE);

            // 窗口过期 → 计数清零、窗口起点挪到今天（一条 UPDATE 里做，避免读写之间被插队）
            ContentValues reset = new ContentValues();
            reset.put("ok_cnt", 0);
            reset.put("fail_cnt", 0);
            reset.put("win_start", now);
            db.update("source_health", reset, "url=? AND win_start<?",
                    new String[]{url, String.valueOf(edge)});

            ContentValues v = new ContentValues();
            if (success) {
                // 成功一次即清零「累计不可达」计数：期间有过成功就不算「长期不可达」
                db.execSQL("UPDATE source_health SET ok_cnt=ok_cnt+1, unreach_cnt=0, last_ok=?, err_kind='', err_msg='' WHERE url=?",
                        new Object[]{now, url});
            } else {
                v.put("err_kind", kind);
                v.put("err_msg", msg);
                v.put("last_fail", now);
                db.execSQL("UPDATE source_health SET fail_cnt=fail_cnt+1, last_fail=?, err_kind=?, err_msg=? WHERE url=?",
                        new Object[]{now, kind, msg, url});
                // 连接层失败（超时 / DNS / SSL / 连不上，不含 4xx/5xx 与规则失效）才累计进
                // unreach_cnt，用于跨会话二阶判定「疑似不可达」。4xx/5xx 是「被拒绝/服务端错误」，
                // 网络通、源没坏，绝不累计（否则会把「被封的源」误提示成「疑似不可达」误导停用）。
                if (KIND_NET.equals(kind) || KIND_TIMEOUT.equals(kind)) {
                    db.execSQL("UPDATE source_health SET unreach_cnt=unreach_cnt+1 WHERE url=?",
                            new Object[]{url});
                }
                ContentValues e = new ContentValues();
                e.put("url", url);
                e.put("ts", now);
                e.put("kind", kind);
                e.put("msg", msg);
                db.insert("source_err", null, e);
                // 只留最近 30 条 + 7 天内：详情面板只显示 5 条，存太多纯占空间
                db.execSQL("DELETE FROM source_err WHERE ts<?", new Object[]{edge});
                db.execSQL("DELETE FROM source_err WHERE url=? AND id NOT IN "
                                + "(SELECT id FROM source_err WHERE url=? ORDER BY ts DESC LIMIT 30)",
                        new Object[]{url, url});
            }
        } catch (Throwable t) {
            Log.w(TAG, "write failed: " + url, t);
        }
    }

    /* ---------------- 读取 ---------------- */

    /** 单个源的记录；没有记录返回 null（不是"全 0"，两者含义不同：没搜过 vs 搜过全失败） */
    static JSONObject get(Context c, String url) {
        if (c == null || url == null || url.isEmpty()) return null;
        try {
            SQLiteDatabase db = ModouStore.get(c).getReadableDatabase();
            Cursor cur = db.query("source_health", null, "url=?", new String[]{url}, null, null, null, "1");
            try {
                return cur.moveToFirst() ? toJson(cur) : null;
            } finally {
                cur.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 批量读（列表渲染用一次查询，不要每行查一次） */
    static Map<String, JSONObject> all(Context c) {
        Map<String, JSONObject> out = new HashMap<>();
        if (c == null) return out;
        try {
            SQLiteDatabase db = ModouStore.get(c).getReadableDatabase();
            Cursor cur = db.query("source_health", null, null, null, null, null, null);
            try {
                while (cur.moveToNext()) {
                    JSONObject o = toJson(cur);
                    String url = o.optString("url", "");
                    if (!url.isEmpty()) out.put(url, o);
                }
            } finally {
                cur.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "read all failed", t);
        }
        return out;
    }

    private static JSONObject toJson(Cursor cur) {
        JSONObject o = new JSONObject();
        try {
            long win = cur.getLong(cur.getColumnIndexOrThrow("win_start"));
            boolean fresh = win > 0 && System.currentTimeMillis() - win <= WINDOW_MS;
            o.put("url", cur.getString(cur.getColumnIndexOrThrow("url")));
            o.put("lastOk", cur.getLong(cur.getColumnIndexOrThrow("last_ok")));
            o.put("lastFail", cur.getLong(cur.getColumnIndexOrThrow("last_fail")));
            // 窗口外的旧计数不返回：查询侧不用再关心过期
            o.put("okCnt", fresh ? cur.getInt(cur.getColumnIndexOrThrow("ok_cnt")) : 0);
            o.put("failCnt", fresh ? cur.getInt(cur.getColumnIndexOrThrow("fail_cnt")) : 0);
            o.put("errKind", cur.getString(cur.getColumnIndexOrThrow("err_kind")));
            o.put("errMsg", cur.getString(cur.getColumnIndexOrThrow("err_msg")));
        } catch (Throwable ignore) {
            /* 字段缺失按 0 处理 */
        }
        return o;
    }

    /** 最近几次失败的原文，最新的在前：String[]{时间戳, 分类, 原文} */
    static List<String[]> recentFails(Context c, String url, int limit) {
        List<String[]> out = new ArrayList<>();
        if (c == null || url == null || url.isEmpty()) return out;
        try {
            SQLiteDatabase db = ModouStore.get(c).getReadableDatabase();
            Cursor cur = db.query("source_err", new String[]{"ts", "kind", "msg"},
                    "url=?", new String[]{url}, null, null, "ts DESC", String.valueOf(Math.max(1, limit)));
            try {
                while (cur.moveToNext()) {
                    out.add(new String[]{String.valueOf(cur.getLong(0)),
                            cur.getString(1) == null ? "" : cur.getString(1),
                            cur.getString(2) == null ? "" : cur.getString(2)});
                }
            } finally {
                cur.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "recentFails failed: " + url, t);
        }
        return out;
    }

    /**
     * 近 7 天失败流水里，撞到「明确登录页」文案的 url → 第一条原文（0.9.157）。
     *
     * <p>供书源列表<b>一次性</b>判断「哪些源需要登录」：几百个源若逐个 recentFails
     * 就是几百次 DB 查询，打开筛选/清理面板会明显卡顿。这里一条 SQL 扫完，结果按 url
     * 去重。SQL 的 LIKE 放宽（只要含「登录/登陆」），Java 侧再用
     * {@link SourceLogin#wallWord(String)} 精确收窄 —— 泛化的 401/403「需登录/鉴权」
     * 不算登录墙，避免把临时限流的好源摆上删除台面。
     */
    static Map<String, String> loginHistory(Context c) {
        Map<String, String> out = new HashMap<>();
        if (c == null) return out;
        try {
            SQLiteDatabase db = ModouStore.get(c).getReadableDatabase();
            long since = System.currentTimeMillis() - WINDOW_MS;
            Cursor cur = db.query("source_err", new String[]{"url", "msg"},
                    "ts>=? AND (msg LIKE ? OR msg LIKE ?)",
                    new String[]{String.valueOf(since), "%登录%", "%登陆%"},
                    null, null, "ts DESC");
            try {
                while (cur.moveToNext()) {
                    String url = cur.getString(0);
                    String msg = cur.getString(1);
                    if (url == null || url.isEmpty()) continue;
                    if (!SourceLogin.wallWord(msg)) continue;
                    if (!out.containsKey(url)) out.put(url, msg == null ? "" : msg);
                }
            } finally {
                cur.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "loginHistory failed", t);
        }
        return out;
    }

    /** 清掉一个源的全部记录（统计 + 失败流水），用于「规则修好了，重新观察」 */
    static void reset(Context c, String url) {
        if (c == null || url == null || url.isEmpty()) return;
        final Context app = c.getApplicationContext();
        TaskPools.io().execute(() -> {
            try {
                SQLiteDatabase db = ModouStore.get(app).getWritableDatabase();
                db.delete("source_health", "url=?", new String[]{url});
                db.delete("source_err", "url=?", new String[]{url});
            } catch (Throwable t) {
                Log.w(TAG, "reset failed: " + url, t);
            }
        });
    }

    /* ---------------- 失败分类 ---------------- */

    /**
     * 把一次失败翻译成人能看懂的一类。
     *
     * <p>顺序有意义：先看 timeout 标记（我们自己打的，最准），再看引擎给的 reason。
     * 4xx/5xx（被拒绝/服务端错误）与连接层失败（超时/DNS/SSL/连不上）严格区分：
     * 前者网络通、源没坏，不累计 unreach_cnt；后者才算「不可达」，累计进二阶判定。
     */
    static String classify(JSONObject res) {
        if (res == null) return KIND_NET;
        if (res.optBoolean("timeout", false)) return KIND_TIMEOUT;
        String err = res.optString("error", "");
        if (!err.isEmpty()) return classifyText(err);
        JSONArray fs = res.optJSONArray("fails");
        if (fs != null && fs.length() > 0) {
            JSONObject f = fs.optJSONObject(0);
            if (f != null) {
                String reason = f.optString("reason", "");
                if (!reason.isEmpty()) return classifyText(reason);
            }
        }
        JSONArray gs = res.optJSONArray("groups");
        if (gs == null || gs.length() == 0) return KIND_EMPTY;
        return KIND_OTHER;
    }

    static String classifyText(String text) {
        if (text == null) return KIND_OTHER;
        String s = text.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("超时") || s.contains("timeout") || s.contains("timed out")) {
            return KIND_TIMEOUT;
        }
        if (s.contains("正则") || s.contains("regex") || s.contains("regexp")
                || s.contains("回溯") || s.contains("pattern")) {
            return KIND_REGEX;
        }
        // 4xx/5xx 必须排在「没有解析出任何结果」之前：引擎的失败原文常是
        // 「HTTP 403 · 没有解析出任何结果」这种复合句。以前 EMPTY 判断在前，
        // 一条被站点拒绝的请求被记成 KIND_EMPTY —— 而 disableAllBroken 只认
        // EMPTY / REGEX，于是 403 的源会被「一键禁用」捞走，和注释「403 一律不算」直接打架。
        if (s.contains("403") || s.contains("404") || s.contains("401") || s.contains("429")
                || s.contains("500") || s.contains("502") || s.contains("503")
                || s.contains("被站点拒绝") || s.contains("限流") || s.contains("服务端错误")
                || s.contains("路径可能过期")) {
            return KIND_REJECT;
        }
        if (s.contains("没有解析出") || s.contains("规则未命中") || s.contains("未匹配")
                || s.contains("没有找到") || s.contains("空列表") || s.contains("0 条")
                || s.contains("列表规则")) {
            return KIND_EMPTY;
        }
        // 连接层失败（超时已在最前处理）：DNS / SSL / 连不上 / 网络断开。这些才算"不可达"，
        // 累计进 unreach_cnt 作为二阶判定的弱候选。不再包含 4xx/5xx。
        if (s.contains("unknownhost") || s.contains("连接") || s.contains("connect")
                || s.contains("refused") || s.contains("ssl") || s.contains("网络")
                || s.contains("socket") || s.contains("unreachable") || s.contains("dns")) {
            return KIND_NET;
        }
        return KIND_OTHER;
    }

    /* ---------------- 一键禁用 ---------------- */

    /**
     * 禁用所有"近 7 天一次都没成功、且失败 ≥ {@link #MIN_SAMPLE} 次"的源。
     *
     * <p>只认「规则未匹配 / 正则解析报错」两类失败：超时、403、DNS 全是网络层，
     * 站点抽风一晚上就能让一屏好源变成"失败率 100%"，照着禁用等于把能看的书删了。
     * 这条规则必须和体检的判定口径一致，否则两个入口互相拆台。
     *
     * @return 实际禁用的数量
     */
    static int disableAllBroken(Context c) {
        if (c == null) return 0;
        Set<String> broken = new LinkedHashSet<>();
        try {
            SQLiteDatabase db = ModouStore.get(c).getReadableDatabase();
            long edge = System.currentTimeMillis() - WINDOW_MS;
            Cursor cur = db.query("source_health", new String[]{"url"},
                    "ok_cnt=0 AND fail_cnt>=? AND win_start>=? AND err_kind IN (?,?)",
                    new String[]{String.valueOf(MIN_SAMPLE), String.valueOf(edge),
                            KIND_EMPTY, KIND_REGEX},
                    null, null, null);
            try {
                while (cur.moveToNext()) broken.add(cur.getString(0));
            } finally {
                cur.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "query broken failed", t);
            return 0;
        }
        if (broken.isEmpty()) return 0;

        int n = 0;
        try {
            ModouStore store = ModouStore.get(c);
            JSONArray arr = new JSONArray(store.all("sources"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("bookSourceUrl", "");
                if (!broken.contains(url)) continue;
                if (!o.optBoolean("enabled", true)) continue; // 已经禁用的不重复计数
                o.put("enabled", false);
                store.put("sources", o.toString());
                n++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "disable failed", t);
        }
        return n;
    }

    /**
     * 统计「还能被一键禁用」的源有多少个（只给按钮显示数量，不执行禁用）。
     *
     * <p>必须和 {@link #disableAllBroken} 完全同口径：那份会遍历当前书源、跳过已停用的，
     * 而这里以前只查 source_health 表 —— 表里的记录不会因为源被删而消失，于是
     * 「删掉两个失败源、回菜单还显示 35 个」几乎必然发生，用户点下去又只禁掉少数几个。
     * 现在两者都只认「当前存在且启用」的源。
     */
    static List<String> brokenUrls(Context c) {
        List<String> out = new ArrayList<>();
        if (c == null) return out;
        List<String> cand = new ArrayList<>();
        try {
            SQLiteDatabase db = ModouStore.get(c).getReadableDatabase();
            long edge = System.currentTimeMillis() - WINDOW_MS;
            Cursor cur = db.query("source_health", new String[]{"url"},
                    "ok_cnt=0 AND fail_cnt>=? AND win_start>=? AND err_kind IN (?,?)",
                    new String[]{String.valueOf(MIN_SAMPLE), String.valueOf(edge),
                            KIND_EMPTY, KIND_REGEX},
                    null, null, null);
            try {
                while (cur.moveToNext()) cand.add(cur.getString(0));
            } finally {
                cur.close();
            }
        } catch (Throwable ignore) {
            return out;   // 查不到就当没有
        }
        if (cand.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(ModouStore.get(c).all("sources"));
            Set<String> live = new LinkedHashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.optBoolean("enabled", true)) continue;
                String u = o.optString("bookSourceUrl", "");
                if (!u.isEmpty()) live.add(u);
            }
            for (String u : cand) {
                if (live.contains(u)) out.add(u);
            }
        } catch (Throwable t) {
            return cand;   // 读不到书源列表时退回原口径：宁可多显示，也不隐藏坏源
        }
        return out;
    }

    /**
     * 疑似不可达：跨会话累计网络层失败（超时 / 4xx / DNS / SSL）达到阈值，且期间无成功。
     * 这是二阶判定——不靠单轮内连续计数（会被一次「诈尸」成功清零），而是看长期累积。
     * 命中只进「弱候选」：不自动删除、不标红，仅提示用户「这些源长期连不上，建议停用」。
     *
     * @param threshold 累计次数阈值（默认 5）
     * @return 疑似不可达的书源 URL 集合
     */
    static java.util.Set<String> unreachableUrls(Context c, int threshold) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (c == null) return out;
        try {
            SQLiteDatabase db = ModouStore.get(c).getReadableDatabase();
            Cursor cur = db.query("source_health", new String[]{"url"},
                    "unreach_cnt>=?", new String[]{String.valueOf(threshold)},
                    null, null, null);
            try {
                while (cur.moveToNext()) out.add(cur.getString(0));
            } finally {
                cur.close();
            }
        } catch (Throwable ignore) {
            /* 查不到就当没有 */
        }
        return out;
    }

    /**
     * 规约自检（规约 5）：风控/失败 reason 绝不得含 "HTTP 2" 子串、不得含任何数字。
     * 一旦含，Java 侧 reached() 的 `reason.contains("HTTP 2")` 会把它误判成「请求通了→规则失效」，
     * 进而推进删除清单误删好源。
     *
     * <p>这是第二道网。第一道网在生成点：web/js/engine/reason.js 的 blockedReason/netReason/
     * jsErrorReason/ruleMissReason 工厂，构造时即过数字断言（debug 抛 Error、release 记 warn）。
     *
     * <p><b>维护约定（务必遵守，否则自检半年后必腐化）：新增任何风控/失败文案，
     * 必须同时 (a) 在 reason.js 的对应工厂里实现、(b) 在下方 samples 补一条等价样例。
     * 只改文案不补样例 = 让这道网形同虚设。</b>
     */
    static boolean verifyBlockedReasonContract() {
        // 样例须与 web/js/engine/reason.js 工厂产出的文案保持一致：
        // blockedReason(label) 包装「风控拦截：<label>」，jsErrorReason(msg) 包装「规则执行异常：<msg>」
        final String[] samples = {
                "风控拦截：Cloudflare 挑战页",          // blockedReason('Cloudflare 挑战页')
                "风控拦截：登录墙/访问限制（请登录）",   // blockedReason('登录墙/访问限制（请登录）')
                "风控拦截：挑战页",                      // blockedReason('挑战页')
                "风控拦截：响应体异常偏短且无标签（疑似拦截壳）",
                "规则执行异常：正则回溯",                // jsErrorReason('正则回溯')
                "路径可能过期",                          // netReason('路径可能过期') —— 4xx 收敛后纯中文
                "被站点拒绝（需登录/鉴权）",
        };
        boolean ok = true;
        java.util.regex.Pattern digit = java.util.regex.Pattern.compile("\\d");
        for (String s : samples) {
            if (s.toLowerCase(java.util.Locale.ROOT).contains("http 2")) {
                Log.w(TAG, "风控/JSError reason 含 HTTP 2 子串（违反规约5）: " + s);
                ok = false;
            }
            if (digit.matcher(s).find()) {
                Log.w(TAG, "风控/JSError reason 含数字（违反规约5）: " + s);
                ok = false;
            }
        }
        return ok;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
