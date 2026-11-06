package cn.modou.app;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.os.Bundle;

import java.lang.ref.WeakReference;

/**
 * 进程壳：只做一件系统级的事 —— 接住「深浅色配置变更」。
 *
 * <p>为什么必须在这里：本 App 每个 Activity 都声明了 {@code configChanges=uiMode}
 * （为了转屏/字号/深浅色不重建，阅读到一半翻页不会被踢回首页）。代价是系统切深浅色时
 * Activity **不会**被系统重建，而页面里的颜色是建 View 时一次性写死的 —— 于是整页停在
 * 旧的一套配色里，用户看到的就是「切了没反应，得杀掉重进」。
 *
 * <p>Application 的 {@code onConfigurationChanged} 一定会被调到，由它判断「当前这页
 * 生效的深浅和建页时是否已经不一致」，再只把那一页重建。
 */
public class ModouApp extends Application {

    private static WeakReference<Activity> resumed = new WeakReference<>(null);

    /** 当前在前台的 Activity：配置变化时只需要重建它，后台页面回前台会自己 syncTheme */
    static Activity resumed() {
        return resumed.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                resumed = new WeakReference<>(a);
            }

            @Override
            public void onActivityPaused(Activity a) {
                if (resumed.get() == a) resumed = new WeakReference<>(null);
            }

            @Override
            public void onActivityDestroyed(Activity a) {
                if (resumed.get() == a) resumed = new WeakReference<>(null);
            }

            @Override
            public void onActivityCreated(Activity a, Bundle b) {
                /* 不需要 */
            }

            @Override
            public void onActivityStarted(Activity a) {
                /* 不需要 */
            }

            @Override
            public void onActivityStopped(Activity a) {
                /* 不需要 */
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
                /* 不需要 */
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ModouUi.onUiModeChanged(newConfig);
    }
}
