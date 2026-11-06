package cn.modou.app;

import android.content.Context;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 逐源搜索池：**每搜完一个源就回调一次**，界面边搜边出结果。
 *
 * <p>存在的理由：原来的多源搜索是一次 bridge 调用、等全部源跑完才回一个总结果。
 * 书源动辄上百个，只要有一个卡满超时，用户就要对着「搜索中…」干等半分钟 ——
 * 这期间界面没有任何变化，看起来就是卡死或 bug。
 *
 * <h3>线程模型</h3>
 * 固定 8 条 IO 线程（{@link TaskPools#io()}），每个源一个任务；任务内部用
 * {@link CountDownLatch} 把线程占住到"该源结束或超时"，因此线程数天然就是并发窗口 ——
 * 134 个源不会在一瞬间全砸给 WebView 的 JS 单线程排队，否则每个源都要排队等前面跑完，
 * 8 秒超时一视同仁，最后一片"全部超时"。
 *
 * <h3>单源超时</h3>
 * 8 秒（引擎内请求超时也是 8s，这里留 500ms 收尾余量）。到点：
 * 用 {@link AtomicBoolean} 把该源标记为已收尾，立刻回调一次 `ok=false, error=本次超时`，
 * UI 把这一条标红略过，**其余源继续跑**。迟到的真实结果直接丢弃，不会重复追加。
 */
final class SourceSearchPool {

    interface Listener {
        /** 某个源跑完（失败也会回调，res.ok=false 或 res 里带 fails） */
        void onSource(JSONObject source, JSONObject res);

        /** 全部跑完；failed 是失败源数量 */
        void onFinished(int total, int failed);
    }

    /** 并发窗口 = 线程数：再多会把手机网络栈和站点风控同时踩满 */
    private static final int CONCURRENCY = 8;

    /** 单源硬超时：引擎内 8s 请求超时 + 500ms 解析收尾 */
    static final long SOURCE_TIMEOUT_MS = 8500;

    /**
     * 轮次号：用户点「停止」或重新搜一次时 +1。
     * 在跑的任务看到号变了就立刻放手 —— 不放手的话，8 条线程还攥在上一轮手里，
     * 新一轮的前 8 个源要干等最多 8.5 秒才发得出去，点「停止再搜」像没反应。
     */
    private static final java.util.concurrent.atomic.AtomicInteger RUN =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 停止/换关键词时调用：让在跑的任务尽快退出，不再占用并发窗口 */
    static void cancelAll() {
        RUN.incrementAndGet();
    }

    private SourceSearchPool() {
    }

    static void start(Context ctx, List<JSONObject> sources, String keyword, int page, Listener l) {
        final int total = sources == null ? 0 : sources.size();
        final SourceService svc = SourceService.get();
        if (svc == null || total == 0) {
            l.onFinished(total, total);
            return;
        }
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicInteger failed = new AtomicInteger(0);
        final ExecutorService pool = TaskPools.io();
        final int myRun = RUN.incrementAndGet();
        final Context app = ctx == null ? null : ctx.getApplicationContext();

        for (int i = 0; i < total; i++) {
            final JSONObject src = sources.get(i);
            pool.execute(() -> runOne(svc, app, src, keyword, page, total, done, failed, l, myRun));
        }
    }

    /** 一个源 = 一个任务；线程被 latch 占住，直到该源收尾 */
    private static void runOne(SourceService svc, Context ctx, JSONObject src, String keyword, int page,
                               int total, AtomicInteger done, AtomicInteger failed,
                               Listener l, int myRun) {
        final String srcUrl = src == null ? "" : src.optString("bookSourceUrl", "");
        if (RUN.get() != myRun) return; // 上一轮已作废：连请求都别发
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean settled = new AtomicBoolean(false);
        final ScheduledFuture<?>[] guard = new ScheduledFuture<?>[1];

        // 看门狗：不依赖 WebView 是否还活着。JS 死循环时引擎永远不会回调，
        // 这条路径是唯一能把 UI 从「转圈」里救出来的。
        guard[0] = TaskPools.after(SOURCE_TIMEOUT_MS, () -> {
            if (!settled.compareAndSet(false, true)) return;
            failed.incrementAndGet();
            JSONObject t = timeoutResult(src);
            SourceHealth.fail(ctx, srcUrl, SourceHealth.KIND_TIMEOUT, t.optString("error", ""));
            TaskPools.ui(() -> l.onSource(src, t));
            latch.countDown();
            settle(done, total, failed, l);
        });

        try {
            svc.searchOne(src, keyword, page, res -> {
                if (!settled.compareAndSet(false, true)) return; // 已按超时收尾：迟到结果丢弃
                cancel(guard[0]);
                boolean bad = res == null || !res.optBoolean("ok", false);
                if (!bad) {
                    // 单源失败：引擎把它放在 fails 里、ok 仍为 true
                    org.json.JSONArray fs = res.optJSONArray("fails");
                    org.json.JSONArray gs = res.optJSONArray("groups");
                    boolean noHit = gs == null || gs.length() == 0;
                    if (noHit && fs != null && fs.length() > 0) bad = true;
                }
                if (bad) {
                    failed.incrementAndGet();
                    SourceHealth.fail(ctx, srcUrl, SourceHealth.classify(res), failReason(res));
                } else {
                    SourceHealth.ok(ctx, srcUrl);
                }
                // 先 post 再放行线程：同一 Handler 队列 FIFO，onSource 一定排在 onFinished 之前
                TaskPools.ui(() -> l.onSource(src, res));
                latch.countDown();
                settle(done, total, failed, l);
            });
            // 占住线程直到该源收尾（回调 / 超时二选一），形成真正的并发窗口。
            // 每 250ms 抬头看一眼轮次号：被「停止」了就不再排队，把线程让给下一轮。
            while (latch.getCount() > 0) {
                if (RUN.get() != myRun) break;
                if (!latch.await(250, TimeUnit.MILLISECONDS)) continue;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel(guard[0]);
            if (settled.compareAndSet(false, true)) {
                failed.incrementAndGet();
                // 主动中断（用户点停止）不算源的错，不记健康度
                TaskPools.ui(() -> l.onSource(src, timeoutResult(src)));
                settle(done, total, failed, l);
            }
        }
    }

    private static void settle(AtomicInteger done, int total, AtomicInteger failed, Listener l) {
        if (done.incrementAndGet() == total) {
            TaskPools.ui(() -> l.onFinished(total, failed.get()));
        }
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    /** 失败原因原文：先看顶层 error，再看引擎 fails 里第一条 */
    private static String failReason(JSONObject res) {
        if (res == null) return "无返回";
        String err = res.optString("error", "");
        if (!err.isEmpty()) return err;
        org.json.JSONArray fs = res.optJSONArray("fails");
        if (fs != null && fs.length() > 0) {
            JSONObject f = fs.optJSONObject(0);
            if (f != null) {
                String name = f.optString("name", "");
                String reason = f.optString("reason", "失败");
                return name.isEmpty() ? reason : name + "：" + reason;
            }
        }
        org.json.JSONArray gs = res.optJSONArray("groups");
        if (gs == null || gs.length() == 0) return "没有解析出任何结果（列表规则未命中）";
        return "";
    }

    /** 超时的统一形状：与引擎失败同构，UI 不用为它单独写分支 */
    private static JSONObject timeoutResult(JSONObject src) {
        JSONObject o = new JSONObject();
        try {
            String name = src == null ? ""
                    : src.optString("bookSourceName", src.optString("bookSourceUrl", ""));
            String why = "本次超时（" + (SOURCE_TIMEOUT_MS / 1000) + " 秒未返回，已跳过）";
            o.put("ok", false);
            o.put("error", name.isEmpty() ? why : name + "：" + why);
            o.put("timeout", true);
        } catch (Exception ignore) {
            /* 不可能 */
        }
        return o;
    }
}
