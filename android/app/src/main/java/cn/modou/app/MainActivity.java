package cn.modou.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * MainActivity — WebView 壳：启动本地服务，加载 http://127.0.0.1:PORT/ 的 SPA。
 * 架构对应 Node dev server + 浏览器那一套：引擎/UI 纯前端零改动。
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    // v0.6.22 回退：v0.6.21 曾改成 43921 想绕开持久化缩放，但端口是 origin 的一部分——
    // IndexedDB/localStorage 按 origin 隔离，换端口等于让 App 改看空库（用户数据并未被删，
    // 只是指错了地方）。缩放态清理改用「删 Preferences + 运行时复位」，绝不碰 origin。
    private static final int PORT = 39617;
    private static final int REQ_PICK_FILE = 39618;
    private static final int REQ_SAVE_FILE = 39619;
    /** 全局主题存档（开屏底色用，与 WebView 内 IndexedDB 的设置互为副本） */
    private static final String PREFS = "modou";
    private static final String PREF_APP_THEME = "appTheme";

    private ReaderWebView webView;
    private LocalServer server;
    private ModouBridge bridge;
    /** <input type="file"> 的文件选择回调（onShowFileChooser 挂起，onActivityResult 归还） */
    private ValueCallback<Uri[]> fileChooserCallback;
    /** 导出「另存为」挂起的字节（startActivityForResult 后写入用户选择的 Uri） */
    private byte[] pendingSaveBytes;
    /** 本次启动是否为深色主题（决定 WebView 绘制前的底色，避免夜间冷启动闪白） */
    private boolean darkTheme = false;
    /** 当前是否处于沉浸式（阅读页），以及它用的底色：系统深浅切换时要原样回放，别把阅读页底色冲掉 */
    private boolean immersiveOn = false;
    private String immersiveColor = null;
    /** 上次渲染进程被杀的时间：崩溃恢复只在 60s 内做一次，避免无限重建 */
    private long lastRendererGoneAt = 0;
    /** WebView 首页是否已加载完（此后 evaluateJavascript 才可靠） */
    private boolean webViewReady = false;
    /** 页面未就绪时缓存的待注入 JS（外部文件打开 / 分享导入） */
    private String pendingJs = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 主题必须在 setContentView 之前定下：开屏那一瞬间显示的是 windowBackground，
        // 夜间模式下若还是浅色底，启动就会"亮一下"。存档由前端通过桥写入 SharedPreferences。
        applySavedTheme();
        super.onCreate(savedInstanceState);

        // 1. 本地服务（静态 assets + 代理）
        server = new LocalServer(getApplicationContext(), PORT);
        try {
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "local server start failed", e);
        }

        // 1.2 书源服务：常驻无头 WebView 的原生侧门面。
        // 必须在本地服务起来之后 init —— 服务页要访问同源的 /proxy 才能发书源请求。
        SourceService.init(getApplicationContext(), PORT);

        // 1.5 清理 WebView 历史遗留的缩放态（必须在 new WebView 前，文件无句柄占用）
        resetWebViewZoomState();

        // 冷启动先给一个与主题同色的空背景：WebView 延迟到书架盖住本页后再创建，
        // 这期间本窗口只在书架底下，用户看不到
        android.widget.FrameLayout boot = new android.widget.FrameLayout(this);
        boot.setBackgroundColor(darkTheme ? 0xFF1B1A17 : 0xFFF5F0E2);
        setContentView(boot);

        // 兜底：万一本地服务/装配异常，书架起不来，这里不再抢着建 WebView
        // （建引擎要 1~3 秒主线程，冷启动第一本书正好会被它堵住）。
        // 需要引擎的地方（迁移旧数据、刷新目录、取未缓存章节）显式调 ensureBridge()。

        // 后台把设置表读进内存，同时把 SQLite 连接打开（WAL 恢复、表结构检查
        // 这些一次性开销都挪出主线程）。阅读页打开时要读十几个设置项，
        // 以前每项一次 SELECT，冷启动第一次进书就卡在这串查询上。
        Thread warm = new Thread(() -> {
            try {
                ModouStore st = ModouStore.get(getApplicationContext());
                st.preloadSettings();
                // v0.6.133：页边距默认值从 24dp 收到 16dp（对齐开源阅读那种「贴边」的手感）。
                // 老存档里的 24 绝大多数只是默认值 —— 用户拖过滑杆才会落库，
                // 而拖滑杆时 24 是起点。这里只做一次：真把 24 当选择的人，
                // 重新拖一下即可，之后不会再被改。
                if (st.settingStr("marginTuned133", "").isEmpty()) {
                    if ("24".equals(st.settingStr("readerMargin", ""))) {
                        st.putSetting("readerMargin", "16");
                    }
                    st.putSettingStr("marginTuned133", "1");
                }
            } catch (Throwable ignore) {
                /* 预热失败不影响功能，读设置会退回直接查库 */
            }
        }, "modou-warm");
        warm.setPriority(Thread.MIN_PRIORITY);
        warm.start();

        sMe = new java.lang.ref.WeakReference<>(this);
        ShelfActivity.launch(this);

        // 6. edge-to-edge：系统栏区域交给页面自绘（栏区与页面同色，根除「小白条附近色差」）
        enableEdgeToEdge();
        // 系统栏高度变化（旋转/手势栏显隐）时实时推给前端，写进 --safe-top/--safe-bottom
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            pushWindowInsets(insets);
            return insets; // 不消费：页面 CSS 用 var(--safe-*) 自行避让
        });

        // 7. 系统栏与页面同色（首帧前的窗口底色 + 图标明暗自适应）
        setImmersive(false, null);

        // 8. 处理「用本应用打开」的书源 JSON（VIEW intent）
        handleViewIntent(getIntent());
    }

    /**
     * WebView 引擎创建（按需）。
     *
     * 以前冷启动必然要建一次（onStop 里延迟 300ms），理由是「阅读页取未缓存章节要靠它」。
     * 现在在线取章由常驻的书源服务（SourceService）负责，UI WebView 只在迁移旧数据、
     * 刷新目录、缓存全书、老书源兜底这类**明确的用户动作**上才需要 —— 让它按需创建，
     * 冷启动那 1~3 秒的主线程开销就彻底没有了。
     */
    /** WebView 引擎是否已创建（按需初始化：冷启动只让原生书架露脸） */
    private boolean webViewInited;

    /** 进程内唯一实例：书架/详情/阅读页要「引擎」时可以回来找它按需创建 */
    private static java.lang.ref.WeakReference<MainActivity> sMe;

    /**
     * 按需取桥（没有就创建 WebView）。
     *
     * 冷启动不再自动建引擎：Chromium 初始化是主线程 1~3 秒的活，以前挂在
     * MainActivity.onStop 上（书架刚盖上来的那一刻），用户紧接着点开一本书，
     * 阅读页的排版、进书都在同一条主线程上排队 —— 表现就是「冷启动后第一本书
     * 究极慢」，而第二次进书又正常。
     *
     * 现在只有真的需要 JS 引擎（迁移旧数据、刷新目录、缓存全书、书源取章）
     * 才会创建；纯本地阅读一个字节的 WebView 代码都不跑。
     *
     * 主线程调用；后台线程调用会被转到主线程并返回当前（可能还是 null 的）桥。
     */
    static ModouBridge ensureBridge() {
        MainActivity a = sMe == null ? null : sMe.get();
        if (a == null) return null;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            a.runOnUiThread(a::initWebViewOnce);
            return a.bridge;
        }
        a.initWebViewOnce();
        return a.bridge;
    }

    /**
     * WebView 引擎创建（原 onCreate 主体）。
     *
     * 为什么延迟：WebView 首次构造要初始化整个 Chromium 栈，主线程 1~3 秒，
     * 这正是「冷启动进书架要卡几秒才见书」的主因。原生书架/阅读对 bridge 缺失
     * 全部有容错（ReaderLaunch、BookActivity 均判 null），引擎完全可以等书架
     * 盖住本页（onStop）之后再建 —— 用户感知不到，书架先出来。
     */
    private void initWebViewOnce() {
        if (webViewInited || isFinishing() || isDestroyed()) return;
        webViewInited = true;

        // 2. WebView 配置
        // ReaderWebView：默认屏蔽长按正文弹出的原生 ActionMode（阅读页用自绘选词工具条），
        // 输入框聚焦时由前端通过 ModouBridge.setEditing 临时放行复制/粘贴菜单。
        webView = new ReaderWebView(this);
        // WebView 在页面首帧绘制前显示的是它自己的背景色，默认白色。
        // 夜间模式下这段白底就是「冷启动闪一下亮色」的来源，按主题先铺成同色。
        webView.setBackgroundColor(darkTheme ? 0xFF1B1A17 : 0xFFF5F0E2);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);          // localStorage / IndexedDB
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setTextZoom(100);                    // 不跟随系统字体缩放，阅读字号由 App 内部控制
        // 关掉 WebView 默认的缩放：这是 App 不是浏览器，字号走排版面板（字号/字距/行距滑杆）
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        // 滚到边缘的蓝色荧光也是"网页感"来源之一，一并去掉
        webView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);

        // 3. JS 桥：TTS / 系统分享 / 版本号
        bridge = new ModouBridge(this, webView);

        // 4. 外链交给系统浏览器；本地请求留在壳内
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String s = u.toString();
                if (s.startsWith("http://127.0.0.1:" + PORT)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    Log.w(TAG, "no activity for " + s, e);
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 页面（重）加载后补推一次栏高：首帧时 JS 桥可能尚未就绪
                pushWindowInsets(view.getRootWindowInsets());
                // 同步系统深色状态：前端「跟随系统」不依赖 WebView 的 prefers-color-scheme
                final String sysJs = "window.__modouSystemTheme && window.__modouSystemTheme(" + isSystemDark() + ")";
                view.evaluateJavascript(sysJs, null);
                // 冷启动时通过「用其他应用打开」进来的文件，此刻页面就绪了，补注入
                webViewReady = true;
                flushPendingJs();
                // 兜底：万一 WebView 仍恢复了历史缩放态，把 scale 拉回 1.0
                resetPageScale(view, 0);
            }

            /**
             * 渲染进程被杀（OOM / Chromium 崩溃）。
             *
             * 默认行为是整个 App 一起闪退；这里接管：清掉死掉的 WebView，重建一次页面。
             * 页面状态都在 IndexedDB 里，重载后书架/书源/进度都还在。60s 内再次崩溃
             * 说明不是偶发，直接给提示退出，避免无限重建把设备拖死。
             */
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                boolean crashed = detail != null && detail.didCrash();
                Log.e(TAG, "renderer gone, didCrash=" + crashed);
                try {
                    if (view.getParent() instanceof android.view.ViewGroup) {
                        ((android.view.ViewGroup) view.getParent()).removeView(view);
                    }
                    view.destroy();
                    webView = null;
                } catch (Exception e) {
                    Log.w(TAG, "destroy dead webview failed", e);
                }
                long now = System.currentTimeMillis();
                if (now - lastRendererGoneAt > 60000) {
                    lastRendererGoneAt = now;
                    android.widget.Toast.makeText(MainActivity.this, "页面已重新加载", android.widget.Toast.LENGTH_SHORT).show();
                    recreate();
                } else {
                    android.widget.Toast.makeText(MainActivity.this, "内存不足，请重新打开应用", android.widget.Toast.LENGTH_LONG).show();
                    finish();
                }
                return true; // 已处理，别让系统把整个 App 一起杀掉
            }
        });

        // 5. <input type="file"> 文件选择器（WebView 默认不弹，必须壳提供）
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                fileChooserCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypesFor(params.getAcceptTypes()));
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(i, REQ_PICK_FILE);
                } catch (Exception e) {
                    Log.w(TAG, "no file picker", e);
                    fileChooserCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });

        setContentView(webView);
        webView.loadUrl("http://127.0.0.1:" + PORT + "/");

        // WebView 只当引擎（迁移旧数据、刷新目录、缓存全书、书源兜底），不再让人看见它。
        // 网页版书架已经删除，原生书架就是唯一的书架，所以「启动即进原生」不再是开关：
        // 之前它默认关闭，冷启动会先亮一下网页界面再跳原生 —— 用户看到的就是那次闪屏。
        webView.setVisibility(android.view.View.INVISIBLE);
        webViewInited = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 这里**不再**顺手创建 WebView：书架盖上来的那一刻正是用户要翻书的时候，
        // 建引擎的 1~3 秒会直接压在后面所有主线程任务上（首次进书卡顿的主因）。
    }

    /* ---------- 全局主题（开屏底色 / 系统栏） ---------- */

    /**
     * 按存档选择主题（只影响开屏底色与系统栏，页面内容由前端 data-theme 决定）。
     *
     * 存档是「用户在设置里选的 light/dark/auto」，与阅读页临时「夜间」无关——
     * 后者是阅读态，不应把下次启动的开屏也改成暗色。
     */
    private void applySavedTheme() {
        String m = getSavedThemeMode();
        boolean dark = "dark".equals(m);
        if ("auto".equals(m)) dark = isSystemDark();
        darkTheme = dark;
        if (dark) setTheme(R.style.Theme_Modou_Dark);
        // light 用 manifest 的默认 Theme.Modou，不必显式 setTheme
        // 把偏好同步给系统：下次冷启动的系统闪屏才能匹配（API 31+）
        syncApplicationNightMode(m);
    }

    private String getSavedThemeMode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_APP_THEME, "light");
    }

    /**
     * 让系统知道本 App 当前用浅色还是深色（API 31+）。
     *
     * 官方文档：用 UiModeManager#setApplicationNightMode 告知系统，系统就能
     * 「在启动画面期间匹配主题」。这是夜间模式冷启动不再先闪一屏浅色的关键——
     * 平台闪屏在 onCreate 之前绘制，setTheme() 来不及，但系统记得这个偏好。
     */
    private void syncApplicationNightMode(String mode) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            android.app.UiModeManager um =
                    (android.app.UiModeManager) getSystemService(android.content.Context.UI_MODE_SERVICE);
            if (um == null) return;
            int m;
            if ("dark".equals(mode)) m = android.app.UiModeManager.MODE_NIGHT_YES;
            // 注意：UiModeManager 没有 MODE_NIGHT_FOLLOW_SYSTEM；MODE_NIGHT_AUTO 会被框架
            // 映射成 UI_MODE_NIGHT_UNDEFINED，即「不覆盖、跟随系统」，正是「跟随系统」需要的
            else if ("auto".equals(mode)) m = android.app.UiModeManager.MODE_NIGHT_AUTO;
            else m = android.app.UiModeManager.MODE_NIGHT_NO;
            um.setApplicationNightMode(m);
        } catch (Exception e) {
            Log.w(TAG, "setApplicationNightMode failed", e);
        }
    }

    /**
     * 系统当前是否深色。
     *
     * 先问 UiModeManager.getNightMode()（系统全局设置，与应用自身被强制成什么无关）；
     * 系统的 AUTO/CUSTOM（按时间/日落）无法从该值直接看出结果，再落到系统资源配置。
     * 不用 getResources()（那是 App 配置）——应用自身可能已被 setApplicationNightMode 强制成夜间。
     */
    public boolean isSystemDark() {
        try {
            android.app.UiModeManager um =
                    (android.app.UiModeManager) getSystemService(android.content.Context.UI_MODE_SERVICE);
            if (um != null) {
                int n = um.getNightMode();
                if (n == android.app.UiModeManager.MODE_NIGHT_YES) return true;
                if (n == android.app.UiModeManager.MODE_NIGHT_NO) return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "getNightMode failed", e);
        }
        int ui = android.content.res.Resources.getSystem().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 系统深浅色切换（本 Activity 声明了 configChanges=uiMode，不会重建）。
     *
     * 两件事：① 告诉前端新的系统深色状态，「跟随系统」立即重绘；
     * ② 若当前就是「跟随系统」，原生窗口底/系统栏也跟着换，避免边缘露出旧底色。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // newConfig 是 App 的配置；「跟随系统」时它就等于系统状态，正是前端需要的值
        final boolean sysDark = (newConfig.uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (webView != null) {
            final String js = "window.__modouSystemTheme && window.__modouSystemTheme(" + sysDark + ")";
            webView.post(() -> {
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Exception e) {
                    /* 页面未就绪：忽略 */
                }
            });
        }
        if ("auto".equals(getSavedThemeMode())) {
            darkTheme = sysDark;
            setImmersive(immersiveOn, immersiveColor); // 内部按 darkTheme 选窗口底色（阅读页则回放原底色）
        }
    }

    /** 前端切换全局主题时同步存档，决定下次启动的开屏底色 */
    public void saveAppTheme(String mode) {
        String m = "dark".equals(mode) || "auto".equals(mode) ? mode : "light";
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_APP_THEME, m)
                .apply();
        // 告知系统本 App 的深浅：下次冷启动的系统闪屏会直接匹配（API 31+）
        syncApplicationNightMode(m);
        // 立即把原生窗口底/系统栏对齐（edge-to-edge 下栏区露出的就是窗口底）
        darkTheme = "dark".equals(m) || ("auto".equals(m) && isSystemDark());
        setImmersive(immersiveOn, immersiveColor);
    }

    /**
     * edge-to-edge：内容布局延伸到状态栏/导航栏区域，系统栏本身透明。
     *
     * 之前给系统栏「涂成页面同色」，但 Android 10+ 系统会在栏区叠一层半透明 scrim
     * （手势导航条尤其明显），涂色再准也有一圈轻微色差；Android 15 起
     * setNavigationBarColor 更是直接失效。改成页面自绘栏区后，栏区显示的就是
     * WebView 内容本身，结构上不可能出现色差。栏高由 window insets 推给前端。
     */
    private void enableEdgeToEdge() {
        try {
            android.view.Window w = getWindow();
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    | android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                w.setNavigationBarDividerColor(android.graphics.Color.TRANSPARENT);
            }
            // 关掉系统给导航/状态栏自动叠的半透明底（API 29+ 才有这个开关）
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                w.setNavigationBarContrastEnforced(false);
                w.setStatusBarContrastEnforced(false);
            }
            // 布局延伸到系统栏区域（View 级标志；setImmersive 里也会带上，这里先设一次）
            int vis = w.getDecorView().getSystemUiVisibility();
            vis |= android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            w.getDecorView().setSystemUiVisibility(vis);
        } catch (Exception e) {
            Log.w(TAG, "enableEdgeToEdge failed", e);
        }
    }

    /**
     * 删掉 WebView profile 的 Preferences 文件。Chromium 把 per-host 缩放级别写在这里，
     * ≤0.6.20 的用户可能已捏出放大态并被持久化——本 App 不需要任何持久化缩放，
     * 每次冷启动都应回到 1:1。只删这一个文件：Cookies/IndexedDB/书源数据都不动，
     * Chromium 下次启动自动重建默认值。
     */
    private void resetWebViewZoomState() {
        try {
            java.io.File prefs = new java.io.File(getApplicationInfo().dataDir,
                    "app_webview/Default/Preferences");
            if (prefs.exists()) {
                Log.i(TAG, "resetWebViewZoomState: delete " + prefs);
                if (!prefs.delete()) Log.w(TAG, "resetWebViewZoomState: delete failed");
            }
        } catch (Exception e) {
            Log.w(TAG, "resetWebViewZoomState failed", e);
        }
    }

    /**
     * 把页面缩放复位到 1.0（Preferences 已删情况下的运行时兜底）。
     * 注意：getScale() 的更新是异步的（带动画），不能同步循环猛按——会过冲；
     * 每轮只按一次，postDelayed 复检，最多 3 轮，到边界即停。
     */
    private void resetPageScale(WebView view, int round) {
        try {
            if (round >= 3) return;
            float s = view.getScale();
            if (s <= 0f) return;                        // 首帧未就绪：交给下次 onPageFinished
            if (s >= 0.98f && s <= 1.02f) return;       // 已复位
            if (!(s > 1.02f ? view.zoomOut() : view.zoomIn())) return;  // 到边界了
            final int r = round + 1;
            view.postDelayed(() -> resetPageScale(view, r), 350);
        } catch (Exception e) {
            Log.w(TAG, "resetPageScale failed", e);
        }
    }

    /** 状态栏/导航栏（含挖孔）真实高度 → window.__modouInsets(top, bottom) → CSS --safe-* */
    private void pushWindowInsets(android.view.WindowInsets insets) {
        if (webView == null) return;
        int top = 0;
        int bottom = 0;
        if (android.os.Build.VERSION.SDK_INT >= 30 && insets != null) {
            android.graphics.Insets sb = insets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            top = sb.top;
            bottom = sb.bottom;
        } else {
            // API 26-29：getSystemWindowInsets() 在该版本段返回 Rect（API 30 起改成返回 Insets，
            // 按新版签名编译后旧设备会 NoSuchMethodError），故用反射按名字调用；失败则取资源高度兜底
            int[] legacy = legacySystemBars(insets);
            top = legacy[0];
            bottom = legacy[1];
        }
        // 关键换算：insets 是物理像素，CSS px 是密度无关像素（1 CSS px = 1 dp）。
        // 直接推物理值会让 3x 屏上 99px 的状态栏变成 99 CSS px——留白大三倍（v0.6.22 修复）。
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) density = 1f;
        top = Math.round(top / density);
        bottom = Math.round(bottom / density);
        // 设备畸形上报的保险丝：状态栏最高约 50dp（含挖孔）、三键导航 48dp，72dp 足够覆盖
        if (top > 72) top = 72;
        if (bottom > 72) bottom = 72;
        final String js = "window.__modouInsets && window.__modouInsets(" + top + "," + bottom + ")";
        webView.post(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                /* 页面未就绪：忽略，onPageFinished 会补推 */
            }
        });
    }

    /** API 26-29 的系统栏高度：反射调 getSystemWindowInsets()（旧版返回 Rect），兜底读资源 dimen */
    private int[] legacySystemBars(android.view.WindowInsets insets) {
        if (insets != null) {
            try {
                Object r = insets.getClass().getMethod("getSystemWindowInsets").invoke(insets);
                if (r instanceof android.graphics.Rect) {
                    android.graphics.Rect rect = (android.graphics.Rect) r;
                    return new int[] { rect.top, rect.bottom };
                }
            } catch (Exception e) {
                /* 反射失败：落到资源高度 */
            }
        }
        return new int[] { systemBarHeight("status_bar_height"), systemBarHeight("navigation_bar_height") };
    }

    private int systemBarHeight(String name) {
        try {
            int id = getResources().getIdentifier(name, "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_FILE) {
            finishSave(resultCode, data);
            return;
        }
        if (requestCode != REQ_PICK_FILE) return;
        ValueCallback<Uri[]> cb = fileChooserCallback;
        fileChooserCallback = null;
        if (cb == null) return;
        Uri[] uris = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                uris = new Uri[n];
                for (int i = 0; i < n; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        cb.onReceiveValue(uris);
    }

    /** 从外部接收 .json 书源文件：读出内容，注入到前端全局回调 */
    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        String name = queryName(uri);
        String mime = intent.getType();
        boolean isJson = "application/json".equals(mime)
                || (name != null && name.toLowerCase().endsWith(".json"));
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) != -1) bos.write(tmp, 0, n);
            is.close();
            byte[] data = bos.toByteArray();
            final String js;
            if (isJson) {
                // 书源 JSON：文本即可，前端走 parseSourcesJson
                String text = new String(data, "UTF-8");
                if (text.length() > 4 * 1024 * 1024) text = text.substring(0, 4 * 1024 * 1024);
                js = "window.__modouFileResult && window.__modouFileResult("
                        + org.json.JSONObject.quote(text) + "," + org.json.JSONObject.quote(name) + ")";
            } else {
                // 书籍（TXT/EPUB）：传原始字节的 Base64，前端交给本地导入（编码探测/EPUB 解析）
                if (data.length > 20 * 1024 * 1024) {
                    android.widget.Toast.makeText(this, "文件过大（>20MB），请在书架里用本地导入", android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                js = "window.__modouBookFile && window.__modouBookFile("
                        + org.json.JSONObject.quote(b64) + "," + org.json.JSONObject.quote(name) + ")";
            }
            // 「用本应用打开」的书源/书籍文件要交给前端处理：这条路径必须要有引擎，
            // 显式建一次（冷启动只在用户真的选了文件时才付这个代价）
            ensureBridge();
            injectWhenReady(js);
        } catch (Exception e) {
            Log.w(TAG, "read view intent failed", e);
        }
    }

    /** 页面未就绪时先缓存，onPageFinished 后补注入；就绪时直接注入 */
    private void injectWhenReady(String js) {
        if (webViewReady && webView != null) {
            webView.post(() -> {
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Exception e) {
                    Log.w(TAG, "inject js failed", e);
                }
            });
        } else {
            pendingJs = js;
        }
    }

    private void flushPendingJs() {
        if (pendingJs == null || webView == null) return;
        final String js = pendingJs;
        pendingJs = null;
        webView.post(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "flush pending js failed", e);
            }
        });
    }

    /** <input accept> 的扩展名 → 系统选择器 MIME（书源 JSON / TXT / EPUB） */
    private String[] mimeTypesFor(String[] accepts) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String a : accepts) {
            if (a == null) continue;
            String s = a.toLowerCase();
            if (s.contains("json")) out.add("application/json");
            if (s.contains("epub")) out.add("application/epub+zip");
            if (s.contains("txt") || s.contains("text")) out.add("text/plain");
            if (s.contains("octet") || s.contains("zip")) out.add("application/octet-stream");
        }
        if (out.isEmpty()) out.add("*/*");
        else out.add("application/octet-stream"); // 文件管理器常把未知类型报为 octet-stream
        return out.toArray(new String[0]);
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception e) {
            /* 忽略 */
        }
        return "import.json";
    }

    /* ---------- 导出：系统「另存为」（SAF，无需存储权限） ---------- */

    /** 由 ModouBridge.saveFile 调用：弹系统保存对话框，用户选位置后写入 */
    public void startSave(String name, String mime, String text) {
        final String safeName = (name == null || name.trim().isEmpty()) ? "modou-export.json" : name;
        final String safeMime = (mime == null || mime.isEmpty()) ? "application/json" : mime;
        final byte[] bytes = (text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        runOnUiThread(() -> {
            try {
                pendingSaveBytes = bytes;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(safeMime);
                i.putExtra(Intent.EXTRA_TITLE, safeName);
                startActivityForResult(i, REQ_SAVE_FILE);
            } catch (Exception e) {
                Log.w(TAG, "startSave failed", e);
                pendingSaveBytes = null;
                android.widget.Toast.makeText(this, "无法打开保存对话框", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void finishSave(int resultCode, Intent data) {
        byte[] bytes = pendingSaveBytes;
        pendingSaveBytes = null;
        Uri target = data == null ? null : data.getData();
        if (resultCode != Activity.RESULT_OK || target == null || bytes == null) return;
        try (java.io.OutputStream os = getContentResolver().openOutputStream(target)) {
            if (os != null) {
                os.write(bytes);
                os.flush();
                android.widget.Toast.makeText(this, "已保存", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.w(TAG, "save file failed", e);
            android.widget.Toast.makeText(this, "保存失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------- 沉浸式（系统栏透明，页面自绘栏区；这里只管底色与图标明暗） ---------- */

    private int parseColor(String hex, int def) {
        try {
            return android.graphics.Color.parseColor(hex);
        } catch (Exception e) {
            return def;
        }
    }

    /** 背景浅色 → 状态栏用深色图标；深色（夜间模式）→ 用浅色图标 */
    private boolean isLightColor(int color) {
        double lum = 0.299 * android.graphics.Color.red(color)
                + 0.587 * android.graphics.Color.green(color)
                + 0.114 * android.graphics.Color.blue(color);
        return lum > 150;
    }

    /**
     * 由 ModouBridge.setImmersive 调用；colorHex 为阅读背景色。
     *
     * edge-to-edge 下系统栏是透明的，栏区显示的就是 WebView 内容（body 背景随阅读主题
     * 切换），所以这里只做两件事：① 首帧前的窗口/WebView 底色与页面一致，避免露白；
     * ② 状态栏/导航栏图标明暗自适应（浅底深图标、深色底浅图标）。
     * 严禁在这里清掉 FLAG_LAYOUT_FULLSCREEN / FLAG_LAYOUT_HIDE_NAVIGATION——那会让布局
     * 退出 edge-to-edge，栏区重新露出窗口底色，色差复发。
     */
    public void setImmersive(final boolean on, final String colorHex) {
        immersiveOn = on;
        immersiveColor = colorHex;
        runOnUiThread(() -> {
            try {
                android.view.Window w = getWindow();
                android.view.View decor = w.getDecorView();
                int color = on
                        ? parseColor(colorHex, 0xFFF6F1E5)
                        : getResources().getColor(darkTheme ? R.color.app_bg_dark : R.color.app_bg);
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    w.setNavigationBarDividerColor(android.graphics.Color.TRANSPARENT);
                }
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    w.setNavigationBarContrastEnforced(false);
                    w.setStatusBarContrastEnforced(false);
                }
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(color));
                if (webView != null) webView.setBackgroundColor(color);

                int vis = decor.getSystemUiVisibility();
                // 保持 edge-to-edge 布局标志 + LAYOUT_STABLE（旋转时不跳动）
                vis |= android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (isLightColor(color)) {
                    vis |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        vis |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                } else {
                    vis &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        vis &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                decor.setSystemUiVisibility(vis);
            } catch (Exception e) {
                Log.w(TAG, "setImmersive failed", e);
            }
        });
    }

    /** 返回键询问前端：弹层（排版/替换净化等）开着时只关最上层，否则交给 WebView 历史 */
    private boolean backPending = false;
    /** 上次在顶层 tag 页按返回的时间：2 秒内第二次 = 退出 */
    private long lastTagBackAt = 0;

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        if (backPending) return; // 上一次询问还没回来，忽略重复手势
        backPending = true;
        final boolean[] settled = {false};
        // 兜底：前端若因页面卡顿/异常没回调，600ms 后按「无弹层」处理，
        // 不能让一次失败的询问把返回键永久卡死
        webView.postDelayed(
            () -> {
                if (settled[0]) return;
                settled[0] = true;
                backPending = false;
                handleBackNotConsumed();
            },
            600
        );
        webView.evaluateJavascript(
            "(function(){try{return !!(window.__modouBack&&window.__modouBack())}catch(e){return false}})()",
            value -> {
                if (settled[0]) return;
                settled[0] = true;
                backPending = false;
                if ("true".equals(value)) return; // 前端已消费（关掉一层弹层）
                handleBackNotConsumed();
            }
        );
    }

    /**
     * 前端没有浮层可关时的返回行为。
     *
     * 顶层四个 tag 页（书架/搜书/书源/设置）：不回退 tag 历史（否则「返回变成另一个 tag」），
     * 改为提示「再按一次返回退出」，2 秒内再按才真正退出；
     * 其它页面（阅读/书籍详情）：照旧沿历史回退，退到底则退出。
     */
    private void handleBackNotConsumed() {
        if (webView == null) {
            finish();
            return;
        }
        if (isTopTagPage()) {
            long now = System.currentTimeMillis();
            if (now - lastTagBackAt < 2000) {
                finish();
                return;
            }
            lastTagBackAt = now;
            android.widget.Toast.makeText(this, "再按一次返回退出", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (webView.canGoBack()) webView.goBack();
        else finish();
    }

    /** 当前是否停在顶层 tag 页 */
    private boolean isTopTagPage() {
        try {
            String url = webView.getUrl();
            if (url == null) return false;
            int i = url.indexOf('#');
            String hash = i >= 0 ? url.substring(i) : "";
            String path = hash.replaceFirst("^#/?", "");
            int slash = path.indexOf('/');
            if (slash >= 0) path = path.substring(0, slash);
            return path.isEmpty() || path.equals("shelf") || path.equals("search")
                    || path.equals("sources") || path.equals("settings");
        } catch (Exception e) {
            return false;
        }
    }

    /** 打开原生阅读页（实验）：payload 由前端拼好，这里只负责启动 */
    void openNativeReader(ModouBridge b, String payload) {
        try {
            NativeReaderActivity.launch(this, b, payload);
        } catch (Exception e) {
            Log.e(TAG, "open native reader failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        NativeReaderActivity.clearBridge();
        if (sMe != null && sMe.get() == this) sMe = null;
        if (server != null) server.stop();
        if (bridge != null) bridge.release();
        super.onDestroy();
    }
}
