package cn.modou.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 书架自动更新：**一个**系统任务，替掉「每本书一个闹钟」。
 *
 * <p>为什么不是 WorkManager：项目零第三方依赖（`--offline` 构建，缓存里没有 androidx.work），
 * 而 WorkManager 的约束 / 重试 / 持久化这三件事，Android 系统自带的 JobScheduler
 * 本来就全都有 —— WorkManager 在 6.0+ 上底层就是它。所以直接用 JobScheduler，
 * 少的是一层包装，不是能力。
 *
 * <p>约束：**仅 WiFi（不计费网络）且充电中**。这两个条件同时满足时手机基本在夜间闲置，
 * 更新几十本书既不动用户的流量，也不跟白天的使用抢电。
 *
 * <p>指数退避：一轮里有书失败时用 {@code jobFinished(params, true)} 交还任务，
 * 系统按 30 分钟起、每次翻倍的间隔重试（1h、2h、4h…），最多重试到系统上限。
 * 自己没有写「第几次重试」的计数 —— 那个计数器在进程被杀后会丢，
 * 系统的退避是存在系统服务里的，杀进程也不丢。
 */
public class ShelfUpdateJob extends JobService {

    private static final String TAG = "ShelfUpdateJob";

    /** 任务 ID：固定值，重复 schedule 只会覆盖，不会堆出多个任务 */
    private static final int JOB_ID = 0x4D44_0001;
    /** 正常周期：一天一次 */
    private static final long EVERY_MS = 24L * 60 * 60 * 1000;
    /** 首次安装后别立刻跑：给用户一个晚上，别在刚装好就偷偷占 WiFi */
    private static final long FIRST_DELAY_MS = 3L * 60 * 60 * 1000;
    /** 整轮看门狗：比 ShelfUpdater 自己的预算多留 30 秒收尾 */
    private static final long WATCHDOG_MS = 10L * 60 * 1000 + 30_000;
    /** 连续几轮「有失败」就不再指数退避，直接等一天 —— 见 onStartJob 里的说明 */
    private static final int FAIL_STREAK_LIMIT = 3;
    private static final String STREAK_KEY = "shelfUpdateFailStreak";

    private final Handler h = new Handler(Looper.getMainLooper());
    private ShelfUpdater running;
    private boolean jobClosed;
    private final Runnable watchdog = () -> {
        if (jobClosed) return;
        Log.w(TAG, "整轮超时，强制收尾");
        if (running != null) running.cancel();
        // 收尾时告诉系统「还没做完」：让它按退避重试剩下的书
        close(null, true);
    };

    /* ---------------- 排程 ---------------- */

    /**
     * 只在队列里还没有这个任务时才排 —— 应用每次启动都调它。
     *
     * <p>直接调 {@link #schedule} 会把「还有 3 小时就到点」重置成「还有 24 小时」，
     * 天天开 App 的人于是永远等不到这次更新。
     */
    static void scheduleIfNeeded(Context c) {
        if (c == null) return;
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            if (js.getPendingJob(JOB_ID) != null) return; // 已在队列：保留它原本的到点时间
            schedule(c, FIRST_DELAY_MS);
        } catch (Throwable t) {
            // getPendingJob 在个别 ROM 上会抛：退化为「总是重排」，最多是被推迟，不会重复跑
            schedule(c, FIRST_DELAY_MS);
        }
    }

    /** 幂等：已有任务就覆盖（只有「跑完之后排下一次」用得到） */
    static void schedule(Context c) {
        schedule(c, EVERY_MS);
    }

    static void schedule(Context c, long delayMs) {
        if (c == null) return;
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(c.getApplicationContext(), ShelfUpdateJob.class))
                    // 不计费网络 = WiFi / 以太网；移动网络下永不触发
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setRequiresCharging(true)
                    // 重启后仍在队列里（JobScheduler 自己存，不需要 BOOT_COMPLETED 广播）
                    .setPersisted(true)
                    .setMinimumLatency(Math.max(0, delayMs));
            // 退避不显式设置：系统默认就是「30 分钟起、指数翻倍」，
            // 而各家 android.jar 里 setBackoffCriteria 的参数顺序不一致
            // （有的平台包是 (long,int)），显式调用反而可能编不过或运行期 NoSuchMethod。
            int r = js.schedule(b.build());
            if (r != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "schedule rejected: " + r);
        } catch (Throwable t) {
            // 部分 ROM 关掉了 JobScheduler / 用户限制了后台：不能因此让 App 崩
            Log.w(TAG, "schedule failed", t);
        }
    }

    /** 关掉自动更新（设置里留的开关，或用户手动清空书架时） */
    static void cancel(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Throwable ignore) {
            /* 取消失败没有补救手段 */
        }
    }

    /** 上次跑完的结果，给设置页显示（"更新 3 本 / 检查 41 本"） */
    static String lastSummary(Context c) {
        if (c == null) return "";
        try {
            long at = Long.parseLong(
                    ModouStore.get(c).settingStr("shelfUpdateLastAt", "0"));
            if (at <= 0) return "";
            String r = ModouStore.get(c).settingStr("shelfUpdateLastResult", "");
            return r.isEmpty() ? "" : r;
        } catch (Throwable t) {
            return "";
        }
    }

    /* ---------------- 执行 ---------------- */

    @Override
    public boolean onStartJob(final JobParameters params) {
        jobClosed = false;
        final Context app = getApplicationContext();

        // 后台更新要靠书源引擎（无头 WebView）+ 本地代理，两者都必须先就位。
        // 起不来不是「做完了」，而是「这次没做成」：返回 true 再立刻 jobFinished(true)，
        // 让系统按退避重排（直接 return false 等于当场放弃，今晚就不会再试）。
        if (!LocalServer.ensureRunning(app)) {
            Log.w(TAG, "本地服务起不来，本轮跳过");
            close(params, true);
            return true;
        }
        SourceService svc = SourceService.getOrInit(app, MainActivity.PORT);
        if (svc == null) {
            close(params, true);
            return true;
        }

        // 记账放在真正开始之前：前台兜底（ShelfUpdater.maybeCatchUp）看的就是这个时间，
        // 先记后跑，才不会两边同时开跑
        ShelfUpdater.markTry(app, System.currentTimeMillis());

        running = ShelfUpdater.start(app, svc, (checked, updated, failed) -> {
            int streak = record(app, checked, updated, failed);
            if (failed == 0) {
                close(params, false);
                schedule(app, EVERY_MS);
            } else if (streak >= FAIL_STREAK_LIMIT) {
                // 连续三轮都有失败：多半不是「这次网络抖」，而是 ROM 拦后台、或书源成片挂掉。
                // 继续指数退避（30m→1h→2h…）只会在夜里反复醒，跟「省电」的初衷正好相反。
                // 改成一天后再试：条件真恢复了，第二天那一轮自然会成功并清零。
                Log.w(TAG, "连续 " + streak + " 轮有失败，改为 24 小时后再试");
                close(params, false);
                schedule(app, EVERY_MS);
            } else {
                // 偶发失败：交给系统的指数退避（默认 30 分钟起翻倍）
                close(params, true);
            }
        });
        h.postDelayed(watchdog, WATCHDOG_MS);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 系统收回（拔掉电源 / 切走 WiFi / 内存紧张）：停掉当前这轮，等下次条件满足
        if (running != null) running.cancel();
        h.removeCallbacks(watchdog);
        jobClosed = true;
        // true = 这个任务还需要再跑一次（系统会按退避重新排队）
        return true;
    }

    private void close(JobParameters params, boolean retry) {
        if (jobClosed) return;
        jobClosed = true;
        h.removeCallbacks(watchdog);
        if (params != null) jobFinished(params, retry);
    }

    /** 记结果，返回「连续有失败」的轮数（成功一轮就清零） */
    private static int record(Context app, int checked, int updated, int failed) {
        String r = "检查 " + checked + " 本，更新 " + updated + " 本";
        if (failed > 0) r += "，失败 " + failed + " 本";
        int streak = 0;
        try {
            ModouStore st = ModouStore.get(app);
            streak = failed > 0
                    ? (int) parseLong(st.settingStr(STREAK_KEY, "0"), 0) + 1
                    : 0;
            st.putSettingStr("shelfUpdateLastAt", String.valueOf(System.currentTimeMillis()));
            st.putSettingStr("shelfUpdateLastResult", r);
            st.putSettingStr(STREAK_KEY, String.valueOf(streak));
        } catch (Throwable t) {
            Log.w(TAG, "record failed", t);
        }
        return streak;
    }

    private static long parseLong(String s, long dft) {
        try {
            return s == null || s.isEmpty() ? dft : Long.parseLong(s);
        } catch (Throwable t) {
            return dft;
        }
    }
}
