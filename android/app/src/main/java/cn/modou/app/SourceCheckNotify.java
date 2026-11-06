package cn.modou.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 书源体检的常驻通知。
 *
 * <p>体检是「一个源 20 秒」的慢活，137 个源就是十几分钟。以前进度只画在面板里，
 * 用户一退出书源页（或切到后台回个消息）就完全不知道还在不在跑 —— 只能靠「再进来看看
 * 数字有没有动」来猜。Legado 的做法是挂一条带进度条的常驻通知，这里对齐。
 *
 * <p>用普通通知而不是前台服务：任务本来就是随 App 进程活的，进程没了任务也没了，
 * 起前台服务只会多一份 ANR / FGS 类型合规的负担。
 *
 * <p>渠道 IMPORTANCE_LOW：进度条要能看见，但一声都不能响。
 */
final class SourceCheckNotify {

    private static final String CHANNEL = "source_check";
    private static final int ID = 0x4D0C;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** 结束摘要的自动收走延迟：够用户瞟一眼，又不至于在通知栏留一条死记录 */
    private static final long AUTO_HIDE_MS = 8000;

    private static Runnable hideTask;
    /** 上次推送通知的时刻：进度更新按 250ms 合并，别把通知服务刷爆 */
    private static long lastAt;

    private SourceCheckNotify() {
    }

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** 通知被关了 / Android 13 未授权：静默跳过，不影响体检本身 */
    private static boolean allowed(Context c) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager m = nm(c);
        return m != null && m.areNotificationsEnabled();
    }

    private static void ensureChannel(Context c) {
        NotificationManager m = nm(c);
        if (m == null || m.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "书源校验",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("校验书源时的进度提示");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        m.createNotificationChannel(ch);
    }

    private static PendingIntent tap(Context c) {
        Intent i = new Intent(c, SourcesActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flag |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, 0, i, flag);
    }

    /**
     * 开始 / 更新进度。
     *
     * @param done 已完成数；{@code total <= 0} 时画不确定态转圈（阶段切换的瞬间）
     */
    static void progress(Context c, String text, int done, int total) {
        if (!allowed(c)) return;
        // 137 个源 × 每源两次回调 = 几百次 notify()，系统每秒只肯渲染几条，
        // 中间那些直接被丢掉，进度条看上去是「卡一下跳三格」。
        // 按 250ms 合并一次：进度条仍然连续，通知服务不再被刷屏。
        long now = System.currentTimeMillis();
        if (lastAt != 0 && done < total && now - lastAt < 250) return;
        lastAt = now;
        ensureChannel(c);
        if (hideTask != null) {
            UI.removeCallbacks(hideTask);
            hideTask = null;
        }
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_nav_source)
                .setContentTitle("正在校验书源")
                .setContentText(text)
                .setOnlyAlertOnce(true)   // 只有第一条会提醒，后续静默更新
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(tap(c));
        if (total > 0) {
            b.setProgress(total, Math.max(0, Math.min(done, total)), false);
        } else {
            b.setProgress(0, 0, true);
        }
        try {
            NotificationManager m = nm(c);
            if (m != null) m.notify(ID, b.build());
        } catch (Throwable ignore) {
            /* 通知失败不能影响校验流程 */
        }
    }

    /** 结束：换成一条可点的结果摘要，8 秒后自动收走 */
    static void finish(Context c, String text) {
        if (!allowed(c)) return;
        ensureChannel(c);
        lastAt = 0;   // 下一轮体检的第一条进度不该被上一轮的节流窗口吃掉
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_nav_source)
                .setContentTitle("书源校验结束")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(tap(c));
        try {
            NotificationManager m = nm(c);
            if (m != null) m.notify(ID, b.build());
        } catch (Throwable ignore) {
            return;
        }
        if (hideTask != null) UI.removeCallbacks(hideTask);
        hideTask = () -> {
            hideTask = null;
            cancel(c);
        };
        UI.postDelayed(hideTask, AUTO_HIDE_MS);
    }

    /** 立刻收走（用户主动停止 / 关闭面板时不想留尾巴） */
    static void cancel(Context c) {
        if (hideTask != null) {
            UI.removeCallbacks(hideTask);
            hideTask = null;
        }
        try {
            NotificationManager m = nm(c);
            if (m != null) m.cancel(ID);
        } catch (Throwable ignore) {
            /* 无所谓 */
        }
    }

    /** 首次体检前申请通知权限（Android 13+），拒绝也不影响校验本身 */
    static void askPermission(android.app.Activity a) {
        if (a == null || Build.VERSION.SDK_INT < 33) return;
        if (a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0x5A11);
        } catch (Throwable ignore) {
            /* 部分 ROM 限制申请：静默 */
        }
    }
}
