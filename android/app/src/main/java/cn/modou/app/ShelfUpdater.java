package cn.modou.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 书架批量更新（无界面）。
 *
 * <p>只做一件事：把书架上的在线书逐本刷一次目录，有新增章节就把目录写回去。
 * 不抓正文 —— 正文由阅读时的按需抓取负责，后台整本抓等于把手机流量和电量
 * 全花在用户可能永远不看的章节上。
 *
 * <p>为什么不是「一本书一个定时器」：几十本书就是几十个闹钟，每个都要唤醒一次
 * CPU、醒一次无线电，一晚上能把待机耗电拉到 10% 以上（这就是「夜间掉电」的来源）。
 * 合并成一个系统任务后，只在 WiFi + 充电时醒一次，醒着把该做的事一次性做完。
 */
final class ShelfUpdater {

    private static final String TAG = "ShelfUpdater";

    /** 单本超时：目录规则再慢，15 秒也该有结果 */
    private static final long BOOK_TIMEOUT_MS = 15000;
    /** 整轮预算：40 本 × 15 秒的最坏情况也就 10 分钟，到点收手不再开新的书 */
    private static final long TOTAL_BUDGET_MS = 10 * 60 * 1000;
    /** 设置表里记「这本书目录什么时候刷过」的前缀（比改写整条书籍记录便宜得多） */
    private static final String PREF_TOC_AT = "tocAt:";
    /** 轮转游标：下次从第几本开始刷 */
    private static final String CURSOR_KEY = "shelfUpdateCursor";
    /** 上次「尝试更新」的时间：前台兜底与后台任务共用，防止重复跑 */
    static final String TRY_AT_KEY = "shelfUpdateTryAt";
    /** 前台兜底的最小间隔：6 小时。国产 ROM 上 JobScheduler 常被拦，不能只靠系统唤醒 */
    private static final long CATCHUP_GAP_MS = 6L * 60 * 60 * 1000;

    interface Callback {
        /**
         * @param checked 实际去请求了的书本数
         * @param updated 真有新章节的书本数
         * @param failed  请求失败 / 目录为空的书本数
         */
        void onDone(int checked, int updated, int failed);
    }

    private final Context app;
    private final SourceService svc;
    private final Callback cb;
    private final List<String> ids = new ArrayList<>();
    private final long deadline;

    private int pos;
    /** 本轮从第几本开始（轮转起点）：预算用完时下轮接着往后，不会永远只刷开头几本 */
    private int cursor;
    private int checked;
    private int updated;
    private int failed;
    private boolean finished;

    private ShelfUpdater(Context c, SourceService svc, Callback cb) {
        this.app = c.getApplicationContext();
        this.svc = svc;
        this.cb = cb;
        this.deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
    }

    /**
     * 前台兜底：距上次尝试超过 6 小时就静默跑一轮。
     *
     * <p>MIUI / ColorOS 会拦掉 JobScheduler 的定时唤醒（尤其开了省电策略之后），
     * 只靠系统任务，「自动更新」在很多人手机上等于不存在。所以在书架/首页回到前台时
     * 顺手补一次 —— 用户本来就在用手机，这时候跑不额外耗电，也不需要唤醒。
     *
     * <p>6 小时是「别太频繁」和「别太迟」的折中；先记账再跑，失败也不会每 3 分钟重来一次。
     */
    static void maybeCatchUp(Context c, Runnable onDone) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        long last = 0;
        try {
            last = Long.parseLong(ModouStore.get(app).settingStr(TRY_AT_KEY, "0"));
        } catch (Throwable ignore) {
            /* 脏值当没记录 */
        }
        long now = System.currentTimeMillis();
        if (last == 0) {
            // 刚装上：先记账，给用户留一个晚上，别一打开就偷偷刷几十本书
            markTry(app, now);
            return;
        }
        if (now - last < CATCHUP_GAP_MS) return;
        SourceService svc = SourceService.get();
        if (svc == null) return;
        markTry(app, now);
        start(app, svc, (checked, updated, failed) -> {
            if (onDone != null) onDone.run();
        });
    }

    /** 记一次「尝试过」：只写设置表一个短字符串，比改书籍记录便宜得多 */
    static void markTry(Context c, long at) {
        if (c == null) return;
        final Context a = c.getApplicationContext();
        final String v = String.valueOf(at);
        TaskPools.io().execute(() -> {
            try {
                ModouStore.get(a).putSettingStr(TRY_AT_KEY, v);
            } catch (Throwable ignore) {
                /* 记不住只影响节流，不影响正确性 */
            }
        });
    }

    /** 启动一轮。同步返回，结果走 callback（主线程） */
    static ShelfUpdater start(Context c, SourceService svc, Callback cb) {
        ShelfUpdater u = new ShelfUpdater(c, svc, cb);
        u.collect();
        u.next();
        return u;
    }

    /* ---------------- 目录新鲜度（1 小时 TTL） ---------------- */

    /** 这本书的目录上次成功刷新的时间；0 表示没记录 */
    static long tocFetchedAt(Context c, String bookId) {
        if (bookId == null || bookId.isEmpty()) return 0;
        try {
            String v = ModouStore.get(c).settingStr(PREF_TOC_AT + bookId, "");
            if (v.isEmpty()) return 0;
            return Long.parseLong(v);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 记一次「目录刚刷过」。落库很小，但仍丢到 IO 池：别在主线程上碰 SQLite */
    static void markTocFetched(Context c, String bookId, long at) {
        if (c == null || bookId == null || bookId.isEmpty()) return;
        final Context a = c.getApplicationContext();
        final String k = PREF_TOC_AT + bookId;
        final String v = String.valueOf(at);
        TaskPools.io().execute(() -> {
            try {
                ModouStore.get(a).putSettingStr(k, v);
            } catch (Throwable t) {
                Log.w(TAG, "markTocFetched failed", t);
            }
        });
    }

    /* ---------------- 主流程 ---------------- */

    private void collect() {
        try {
            JSONArray arr = new JSONArray(ModouStore.get(app).booksLite());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b == null) continue;
                String id = b.optString("id", "");
                String src = b.optString("sourceUrl", "");
                // 本地书目录来自文件本身，没有「更新」这回事，直接跳过
                if (id.isEmpty() || src.isEmpty() || src.startsWith("local://")) continue;
                ids.add(id);
            }
            try {
                String v = ModouStore.get(app).settingStr(CURSOR_KEY, "");
                if (!v.isEmpty()) cursor = Integer.parseInt(v);
            } catch (Throwable ignore) {
                /* 记不住就从头开始，不是致命问题 */
            }
            if (!ids.isEmpty() && cursor > 0) {
                Collections.rotate(ids, -(cursor % ids.size()));
            }
        } catch (Throwable t) {
            Log.w(TAG, "collect failed", t);
        }
    }

    private void next() {
        // 用循环跳过「本地书 / 记录坏了 / 没有 bookUrl」的条目：
        // 写成递归的话，几百本脏记录会把栈压穿
        while (true) {
            if (finished) return;
            if (pos >= ids.size() || System.currentTimeMillis() > deadline) {
                finish();
                return;
            }
            final String id = ids.get(pos++);

            JSONObject book = null;
            try {
                String s = ModouStore.get(app).get("books", id);
                if (s != null) book = new JSONObject(s);
            } catch (Throwable ignore) {
                /* 记录坏了：跳过这本，不能因为它整轮停下 */
            }
            if (book == null || book.optString("bookUrl", "").isEmpty()) continue;

            checked++;
            final JSONObject bk = book;
            svc.toc(bk, BOOK_TIMEOUT_MS, res -> {
                try {
                    apply(bk, res);
                } catch (Throwable t) {
                    failed++;
                    Log.w(TAG, "apply failed: " + id, t);
                }
                // 一本一本来：串行既省内存也省流量，后台任务不在乎多花几秒
                next();
            });
            return;
        }
    }

    private void apply(JSONObject book, JSONObject res) {
        String id = book.optString("id", "");
        if (res == null || !res.optBoolean("ok", true)) {
            failed++;
            return;
        }
        JSONArray chs = res.optJSONArray("chapters");
        if (chs == null || chs.length() == 0) {
            failed++;
            return;
        }
        JSONArray old = book.optJSONArray("chapters");
        int oldN = old == null ? 0 : old.length();
        if (chs.length() > oldN) {
            try {
                book.put("chapters", chs);
                JSONObject last = chs.optJSONObject(chs.length() - 1);
                if (last != null) book.put("lastChapter", last.optString("name", ""));
                book.put("updatedAt", System.currentTimeMillis());
                ModouStore.get(app).put("books", book.toString());
                updated++;
            } catch (Throwable t) {
                failed++;
                return;
            }
        }
        // 无论有没有新章都记账：TTL 的意义就是「这一小时内别再问了」
        markTocFetched(app, id, System.currentTimeMillis());
    }

    private void finish() {
        if (finished) return;
        finished = true;
        // 记下轮转位置：几十本书一次刷不完时，下一次从没刷到的那些开始
        try {
            int n = ids.size();
            if (n > 0) {
                ModouStore.get(app).putSettingStr(CURSOR_KEY, String.valueOf((cursor + pos) % n));
            }
        } catch (Throwable ignore) {
            /* 记不住顶多是下次重复刷同一批，不影响正确性 */
        }
        Log.i(TAG, "done: checked=" + checked + " updated=" + updated + " failed=" + failed);
        if (cb != null) cb.onDone(checked, updated, failed);
    }

    /** 外部（看门狗 / 用户取消）强停：不再发新请求，已有的回调自然收尾 */
    void cancel() {
        finished = true;
    }
}
