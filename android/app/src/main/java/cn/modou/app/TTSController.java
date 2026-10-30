package cn.modou.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * TTSController — Android 系统 TextToSpeech 封装，供 ModouBridge 调用。
 *
 * 队列与暂停/恢复逻辑在前端 tts.js（与 Web Speech 路径共用一套状态机），
 * 这里只提供「朗读一句 / 停止 / 引擎状态」这几个原语。
 *
 * 关键点（v0.6.62）：
 * 1. Android 11+ 的包可见性限制：要绑定别的应用里的 TTS 引擎服务，清单里必须声明
 *    <queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>，
 *    否则 TextToSpeech 绑定静默失败 → onInit(ERROR) → 前端只能提示「未检测到系统语音」。
 *    该声明在 AndroidManifest.xml。
 * 2. 引擎初始化是异步的（几百毫秒），前端不能一上来就问 available()：
 *    这里把状态暴露成 init / ready / no_engine / no_data，初始化完成后再回调通知前端。
 * 3. 用户可能中途装上语音包：提供 retry() 重建引擎，不用重启 App。
 */
public class TTSController {
    public interface DoneCallback {
        void onDone(int utteranceId);
    }

    /** 引擎状态变化回调（在主线程触发，用于推给前端） */
    public interface StateListener {
        void onState(String state);
    }

    private static final String TAG = "ModouTTS";

    public static final String ST_INIT = "init";           // 正在绑定引擎
    public static final String ST_READY = "ready";         // 可用
    public static final String ST_NO_ENGINE = "no_engine"; // 没有引擎 / 绑定失败
    public static final String ST_NO_DATA = "no_data";     // 有引擎但缺中文语音数据

    private final Context ctx;
    private final DoneCallback done;
    private final StateListener stateListener;

    private TextToSpeech tts;
    private volatile String state = ST_INIT;

    public TTSController(Context ctx, DoneCallback done, StateListener stateListener) {
        // 用 ApplicationContext：Activity 重建后引擎不会跟着失效
        this.ctx = ctx.getApplicationContext();
        this.done = done;
        this.stateListener = stateListener;
        create();
    }

    private void create() {
        shutdownEngine();
        state = ST_INIT;
        try {
            tts = new TextToSpeech(ctx, this::onInit);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    fire(utteranceId);
                }

                @Override
                public void onError(String utteranceId) {
                    // 单句失败也推进，避免前端队列卡死
                    fire(utteranceId);
                }

                private void fire(String utteranceId) {
                    try {
                        done.onDone(Integer.parseInt(utteranceId));
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "create TextToSpeech failed", t);
            tts = null;
            state = ST_NO_ENGINE;
            notifyState();
        }
    }

    /** TextToSpeech 引擎绑定回调（异步触发） */
    private void onInit(int status) {
        TextToSpeech e = tts;
        if (status != TextToSpeech.SUCCESS || e == null) {
            Log.w(TAG, "engine init failed, status=" + status);
            state = ST_NO_ENGINE;
            notifyState();
            return;
        }
        try {
            // 走媒体音量，否则部分机型把 TTS 塞进无障碍音量/通话通道，调音量键没反应
            e.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            e.setPitch(1.0f);
        } catch (Throwable ignored) {
        }

        Locale[] order = {Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.getDefault()};
        for (Locale loc : order) {
            int r = e.isLanguageAvailable(loc);
            if (r == TextToSpeech.LANG_AVAILABLE
                    || r == TextToSpeech.LANG_COUNTRY_AVAILABLE
                    || r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                e.setLanguage(loc);
                state = ST_READY;
                notifyState();
                return;
            }
        }
        // 引擎在，但没有任何可用语音数据（中文语音包没装）
        state = ST_NO_DATA;
        notifyState();
    }

    private void notifyState() {
        StateListener l = stateListener;
        if (l != null) {
            try {
                l.onState(state);
            } catch (Throwable ignored) {
            }
        }
    }

    public String state() {
        return state;
    }

    public boolean available() {
        return ST_READY.equals(state);
    }

    /** 当前系统默认语音引擎包名（诊断用，可能为 null） */
    public String engineName() {
        try {
            TextToSpeech e = tts;
            return e == null ? null : e.getDefaultEngine();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 重建引擎再判断一次。
     * 用户在系统里刚装好语音引擎/语音包后，前端点「重试」走这里，无需重启 App。
     */
    public synchronized void retry() {
        if (ST_READY.equals(state)) return;
        create();
    }

    public boolean speak(String text, double rate, int utteranceId) {
        TextToSpeech e = tts;
        if (!available() || e == null || text == null || text.isEmpty()) return false;
        try {
            float r = (float) Math.max(0.5, Math.min(2.0, rate));
            e.setSpeechRate(r);
            Bundle params = new Bundle();
            return e.speak(text, TextToSpeech.QUEUE_FLUSH, params, String.valueOf(utteranceId))
                    == TextToSpeech.SUCCESS;
        } catch (Throwable t) {
            Log.w(TAG, "speak failed", t);
            return false;
        }
    }

    public void stop() {
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
    }

    /** 打开系统「文字转语音（TTS）输出」设置页，让用户装引擎/下语音包/换引擎 */
    public boolean openSettings() {
        Intent i = new Intent("com.android.settings.TTS_SETTINGS");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "open TTS settings failed", t);
        }
        try {
            ctx.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void shutdownEngine() {
        TextToSpeech e = tts;
        tts = null;
        if (e != null) {
            try {
                e.stop();
                e.shutdown();
            } catch (Throwable ignored) {
            }
        }
    }

    public void release() {
        shutdownEngine();
    }
}
