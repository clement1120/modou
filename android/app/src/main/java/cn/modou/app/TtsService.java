package cn.modou.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 听书的前台服务：只做「让系统知道正在放东西」这件事。
 *
 * <p>TTS 的排队、切句、跨页跨章逻辑全在 {@code NativeReaderActivity} 里（那边握着
 * 页表与章节状态，搬出来等于重写一遍阅读器）。这里负责三件它做不了的事：
 * <ol>
 *   <li>前台服务：退到后台/锁屏后进程不被随手回收，朗读才真的「不退就断」；</li>
 *   <li>MediaSession：音量键、蓝牙耳机线控、锁屏的播放/暂停都走它；</li>
 *   <li>MediaStyle 通知：下拉通知栏那张卡片，带「上一句 / 暂停 / 下一句」。</li>
 * </ol>
 *
 * <p>通知上的按钮通过 {@code startService} 回到这里，再转给 Activity 的监听器 ——
 * 同一进程，主线程直调，不需要广播，也不用 Binder。Activity 销毁时监听器置空，
 * 通知点上去只会把 App 拉起来，不会崩。
 */
public class TtsService extends Service {

    private static final String TAG = "TtsService";
    static final String CH_ID = "modou_tts";
    private static final int NID = 0x7A01;
    private static final String ACT_TOGGLE = "cn.modou.app.tts.TOGGLE";
    private static final String ACT_PREV = "cn.modou.app.tts.PREV";
    private static final String ACT_NEXT = "cn.modou.app.tts.NEXT";

    /** 宿主回调：都在主线程 */
    public interface Listener {
        void onTtsToggle();

        void onTtsPrev();

        void onTtsNext();
    }

    private static Listener listener;
    private static TtsService inst;

    private MediaSession session;
    private NotificationManager nm;
    private String title = "轻阅";
    private String sub = "";
    private boolean playing;

    /** Activity 在 onCreate 挂、onDestroy 摘 */
    static void listen(Listener l) {
        listener = l;
    }

    static void start(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, TtsService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
        }
    }

    static void stop(Context c) {
        if (c == null) return;
        try {
            c.stopService(new Intent(c, TtsService.class));
        } catch (Throwable t) {
            Log.w(TAG, "stop failed", t);
        }
    }

    /** 更新标题/副标题/播放态；服务没起来就什么都不做（Activity 自己还会再拉起） */
    static void update(String t, String s, boolean isPlaying) {
        TtsService v = inst;
        if (v == null) return;
        v.title = t == null || t.isEmpty() ? "轻阅" : t;
        v.sub = s == null ? "" : s;
        v.playing = isPlaying;
        v.refresh();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "听书",
                    NotificationManager.IMPORTANCE_LOW); // LOW：常驻但不响、不亮屏
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        try {
            session = new MediaSession(this, "ModouTts");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    if (listener != null && !playing) listener.onTtsToggle();
                }

                @Override
                public void onPause() {
                    if (listener != null && playing) listener.onTtsToggle();
                }

                @Override
                public void onSkipToNext() {
                    if (listener != null) listener.onTtsNext();
                }

                @Override
                public void onSkipToPrevious() {
                    if (listener != null) listener.onTtsPrev();
                }
            });
            session.setActive(true);
        } catch (Throwable t) {
            Log.w(TAG, "session failed", t);
        }
        // 必须在 5 秒内 startForeground，否则系统直接 ANR/杀掉
        try {
            startForeground(NID, build());
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
        }
        refresh();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_TOGGLE.equals(a)) {
            if (listener != null) listener.onTtsToggle();
        } else if (ACT_PREV.equals(a)) {
            if (listener != null) listener.onTtsPrev();
        } else if (ACT_NEXT.equals(a)) {
            if (listener != null) listener.onTtsNext();
        }
        return START_NOT_STICKY; // 朗读状态在内存里，被杀后重启一个空壳没有意义
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        try {
            if (session != null) {
                session.setActive(false);
                session.release();
                session = null;
            }
        } catch (Throwable ignore) {
            /* 释放失败不影响退出 */
        }
        try {
            stopForeground(true);
        } catch (Throwable ignore) {
            /* 已经不在前台 */
        }
        super.onDestroy();
    }

    private void refresh() {
        if (nm == null) return;
        try {
            if (session != null) {
                MediaMetadata.Builder m = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, "轻阅 · 听书");
                session.setMetadata(m.build());
                int st = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
                session.setPlaybackState(new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE
                                | PlaybackState.ACTION_SKIP_TO_NEXT
                                | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build());
            }
            nm.notify(NID, build());
        } catch (Throwable t) {
            Log.w(TAG, "refresh failed", t);
        }
    }

    private PendingIntent actionPi(String act) {
        Intent i = new Intent(this, TtsService.class).setAction(act);
        int flag = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, act.hashCode(), i, flag);
    }

    private Notification build() {
        // 用启动 Intent 而不是 new Intent(MainActivity)：后者在栈里已有实例时会再压一层，
        // 点通知就变成「回到首页」，而听书的人想回的是正在读的那一页。
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flag = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flag);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(sub)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC); // 锁屏上要看得到

        b.addAction(new Notification.Action(android.R.drawable.ic_media_previous,
                "上一句", actionPi(ACT_PREV)));
        b.addAction(new Notification.Action(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "暂停" : "继续", actionPi(ACT_TOGGLE)));
        b.addAction(new Notification.Action(android.R.drawable.ic_media_next,
                "下一句", actionPi(ACT_NEXT)));

        if (session != null) {
            Notification.MediaStyle ms = new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2); // 折叠态也保留三个键
            b.setStyle(ms);
        }
        return b.build();
    }
}
