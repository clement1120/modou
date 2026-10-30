package cn.modou.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * ModouBridge — JS 桥（@JavascriptInterface，挂 window.ModouBridge）。
 * 能力：TTS（Android TextToSpeech）、系统分享、版本号。
 *
 * 对应最终架构里「Java 原生壳」对前端暴露的系统能力；
 * 桥不存在时（桌面浏览器）前端自动回退 Web Speech。
 * 文件导入不走桥：<input type="file"> 由 MainActivity.onShowFileChooser 标准承接，
 * 「用本应用打开」JSON 由 MainActivity.handleViewIntent 直接注入 JS 回调。
 */
public class ModouBridge {
    private final Activity activity;
    private final WebView webView;
    private final TTSController tts;
    /** 共享存储层（SQLite）：与 WebView 共用同一份数据，原生屏将来直接读库 */
    private final ModouStore store;

    /**
     * 当前活着的桥。原生屏（书架/详情/设置）由网页版唤起后仍要用到桥——
     * 比如打开阅读页时未缓存的章节得回书源引擎取——但它们拿不到 MainActivity
     * 的实例，所以这里留一份静态引用；release 时清空，不持有已销毁的 WebView。
     */
    private static ModouBridge sShared;

    public ModouBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.store = ModouStore.get(activity);
        this.tts = new TTSController(
                activity,
                id -> fireJs("window.__modouTtsDone && window.__modouTtsDone(" + id + ")"),
                state -> fireJs("window.__modouTtsState && window.__modouTtsState('" + state + "')"));
        webView.addJavascriptInterface(this, "ModouBridge");
        sShared = this;
    }

    /** 原生屏取用（包内可见）；可能为 null（MainActivity 已销毁） */
    static ModouBridge shared() {
        return sShared;
    }

    /** 启动原生阅读页时需要一个 Activity 作为起点 */
    Activity activity() {
        return activity;
    }

    /* ---------- 原生界面（书架 / 设置）：渐进式原生化的入口 ---------- */

    /** 从网页版进入原生书架 */
    @JavascriptInterface
    public void openNativeShelf() {
        activity.runOnUiThread(() -> ShelfActivity.launch(activity));
    }

    /** 从网页版进入原生设置 */
    @JavascriptInterface
    public void openNativeSettings() {
        activity.runOnUiThread(() -> SettingsActivity.launch(activity));
    }

    /** 从网页版进入原生搜书（走书源服务，不依赖网页阅读页） */
    @JavascriptInterface
    public void openNativeSearch() {
        activity.runOnUiThread(() -> SearchActivity.launch(activity));
    }

    /** 从网页版进入原生本地导入 */
    @JavascriptInterface
    public void openNativeImport() {
        activity.runOnUiThread(() -> ImportActivity.launch(activity));
    }

    /** 从网页版进入原生替换规则 */
    @JavascriptInterface
    public void openNativeRules() {
        activity.runOnUiThread(() -> RulesActivity.launch(activity));
    }

    /** 从网页版进入原生书源管理 */
    @JavascriptInterface
    public void openNativeSources() {
        activity.runOnUiThread(() -> SourcesActivity.launch(activity));
    }

    /** 是否「启动即进原生书架」（原生设置页里可关，避免进不去网页版） */
    @JavascriptInterface
    public boolean nativeStart() {
        return activity.getSharedPreferences("modou", Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_NATIVE_START, false);
    }

    @JavascriptInterface
    public void setNativeStart(boolean on) {
        activity.getSharedPreferences("modou", Context.MODE_PRIVATE)
                .edit().putBoolean(SettingsActivity.PREF_NATIVE_START, on).apply();
    }

    /* ---------- TTS ---------- */

    @JavascriptInterface
    public boolean ttsAvailable() {
        return tts.available();
    }

    /**
     * 引擎状态：init（绑定中）/ ready / no_engine（没有引擎或绑定失败）/ no_data（缺中文语音数据）。
     * 前端页面加载时先同步读一次，之后靠 __modouTtsState 回调更新。
     */
    @JavascriptInterface
    public String ttsState() {
        return tts.state();
    }

    /** 用户刚装完语音引擎/语音包后重建引擎，无需重启 App */
    @JavascriptInterface
    public void ttsRetry() {
        // 桥方法跑在 WebView 的 JavaBridge 线程：重建引擎涉及 bindService，
        // 统一回主线程做，避免个别 ROM 在无 Looper 线程上初始化失败
        activity.runOnUiThread(() -> tts.retry());
    }

    /** 打开系统「文字转语音」设置页 */
    @JavascriptInterface
    public boolean ttsOpenSettings() {
        return tts.openSettings();
    }

    /** 当前系统默认语音引擎包名（诊断/提示用） */
    @JavascriptInterface
    public String ttsEngineName() {
        return tts.engineName();
    }

    /** 朗读一句；utteranceId 由前端队列管理，onDone 回调同一 id 驱动推进。返回是否成功入队 */
    @JavascriptInterface
    public boolean ttsSpeak(String text, double rate, int utteranceId) {
        return tts.speak(text, rate, utteranceId);
    }

    @JavascriptInterface
    public void ttsStop() {
        tts.stop();
    }

    /* ---------- 分享 ---------- */

    @JavascriptInterface
    public void shareText(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
        activity.startActivity(Intent.createChooser(i, "分享"));
    }

    /* ---------- 导出（系统「另存为」，无需存储权限） ---------- */

    @JavascriptInterface
    public void saveFile(String name, String mime, String text) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).startSave(name, mime, text);
        }
    }

    /* ---------- 沉浸式 ---------- */

    @JavascriptInterface
    public void setImmersive(boolean on, String color) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).setImmersive(on, color);
        }
    }

    /* ---------- 全局主题 ---------- */

    /**
     * 前端切换「浅色/深色/跟随系统」时同步给原生，存进 SharedPreferences。
     *
     * 用途：下次启动在 setContentView 前按存档选 Theme.Modou(.Dark)，
     * 开屏底色才不会在夜间模式下仍是浅色。阅读页临时「夜间」不调这里（那是阅读态）。
     */
    @JavascriptInterface
    public void setAppTheme(String mode) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).saveAppTheme(mode);
        }
    }

    /**
     * 系统当前是否深色（读 Configuration.uiMode）。
     *
     * WebView 的 prefers-color-scheme 在部分机型上恒为 light，或系统切换时不刷新，
     * 导致「跟随系统」不生效；前端改为以这里为准。同步返回 boolean，可在解析期直接调用。
     */
    @JavascriptInterface
    public boolean isSystemDark() {
        if (activity instanceof MainActivity) {
            return ((MainActivity) activity).isSystemDark();
        }
        return false;
    }

    /* ---------- 选区菜单 ---------- */

    /**
     * 前端 focusin / focusout 时同步编辑态。
     *
     * 正文长按：屏蔽原生 ActionMode，只留自绘选词工具条（复制 / 净化替换 / 搜索）。
     * 输入框长按：必须放行，否则搜索词、替换为、书源配置这些框里没法复制粘贴。
     *
     * @param editing true = 正在编辑输入框（放行系统菜单）
     */
    @JavascriptInterface
    public void setEditing(boolean editing) {
        if (webView instanceof ReaderWebView) {
            ((ReaderWebView) webView).setSuppressActionMode(!editing);
        }
    }

    /**
     * 当前是否在阅读页。只有阅读页需要「空 ActionMode + 保留选区」这套；
     * 其它页面（书架/书源/设置）没有可选文字，直接不创建 ActionMode，
     * 避免空白处连点时反复生成看不见的浮动工具条。
     */
    @JavascriptInterface
    public void setReaderMode(boolean on) {
        if (webView instanceof ReaderWebView) {
            ((ReaderWebView) webView).setReaderMode(on);
        }
    }

    /* ---------- 原生阅读页（实验） ---------- */

    /**
     * 打开原生阅读页：只把正文这一屏交给本地绘制，其余功能仍在网页版。
     *
     * payload 是前端拼好的 JSON（书/章/目录/正文/排版），原生页拿到就能立刻出第一屏；
     * 后续换章由原生反向调用 window.__modouNativeChapter(index) 按需取。
     */
    @JavascriptInterface
    public void openNativeReader(String payloadJson) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).openNativeReader(this, payloadJson);
        }
    }

    /** 原生页请求的章节回来了（由前端拿到正文后回传） */
    @JavascriptInterface
    public void nativeChapterData(String json) {
        NativeReaderActivity.deliverChapter(json);
    }

    /** 原生页需要往网页侧发指令（取章节 / 报进度 / 通知退出） */
    public void evalJs(String js) {
        fireJs(js);
    }

    /* ---------- 共享存储层（SQLite，唯一真相源） ---------- */

    /**
     * 存储层是否可用。前端据此决定走 SQLite 还是回退 IndexedDB：
     * 桥不存在时（桌面浏览器）storeReady 为 false，db.js 自动回退。
     */
    @JavascriptInterface
    public boolean storeReady() {
        return store != null;
    }

    /** 单条读取；不存在返回 null（对齐 IndexedDB get 的语义） */
    @JavascriptInterface
    public String storeGet(String table, String key) {
        return store == null ? null : store.get(table, key);
    }

    /** 全表对象数组（JSON 字符串）。chapters 慎用：会整表搬进内存 */
    @JavascriptInterface
    public String storeAll(String table) {
        return store == null ? "[]" : store.all(table);
    }

    /**
     * 书架用的轻量记录（不含 chapters 数组、不含内联封面）。
     *
     * 网页启动时会 reloadBooks()，原先走 storeAll("books")：几十本书的完整记录
     * （每本一份几千项章节表）跨桥搬进 JS 堆，启动就卡。书架页已经原生化，
     * 网页侧只需要书名/封面做标记，详情页要章节再按 id 单独取。
     */
    @JavascriptInterface
    public String storeBooksLite() {
        return store == null ? "[]" : store.booksLite();
    }

    @JavascriptInterface
    public String storeKeys(String table) {
        return store == null ? "[]" : store.keys(table);
    }

    /** 写一条（整对象 JSON 字符串） */
    @JavascriptInterface
    public boolean storePut(String table, String objJson) {
        return store != null && store.put(table, objJson);
    }

    /** 批量写（同一事务）；返回成功条数 */
    @JavascriptInterface
    public int storePutMany(String table, String arrJson) {
        return store == null ? 0 : store.putMany(table, arrJson);
    }

    @JavascriptInterface
    public boolean storeDel(String table, String key) {
        return store != null && store.del(table, key);
    }

    @JavascriptInterface
    public boolean storeClear(String table) {
        return store != null && store.clear(table);
    }

    @JavascriptInterface
    public long storeCount(String table) {
        return store == null ? 0 : store.count(table);
    }

    /**
     * 某列总字节数（只用于统计体积，不搬运内容）。
     * 清缓存前的「正文占用 xx MB」提示走它，避免把每章 HTML 读进 JS 堆。
     */
    @JavascriptInterface
    public long storeSumLength(String table, String column) {
        return store == null ? 0 : store.sumLength(table, column);
    }

    /**
     * IndexedDB → SQLite 的一次性迁移是否已完成。
     *
     * 标记放 SharedPreferences 而不是业务表里：settings 表会被导出/导入，
     * 塞一条 __migrated 进去会跟着备份文件到处跑。
     */
    @JavascriptInterface
    public boolean storeMigrated() {
        try {
            return activity.getSharedPreferences("modou_store", Context.MODE_PRIVATE)
                    .getBoolean("migrated", false);
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void setStoreMigrated(boolean done) {
        try {
            activity.getSharedPreferences("modou_store", Context.MODE_PRIVATE)
                    .edit().putBoolean("migrated", done).apply();
        } catch (Exception e) {
            /* 下次启动会重试迁移 */
        }
    }

    /* ---------- 版本 ---------- */

    @JavascriptInterface
    public String appVersion() {
        return BuildConfig.VERSION_NAME;
    }

    /**
     * 给网页侧发指令。走 webView.post 而不是直接执行：
     * 原生页会在 onPageChanged / onPause 里报进度，这些时机可能正处在
     * View 的布局阶段，排队到下一帧更安全（且 View 已 detach 时自然丢弃）。
     */
    private void fireJs(String js) {
        try {
            webView.post(() -> {
                try {
                    webView.evaluateJavascript(js, null);
                } catch (Exception e) {
                    /* WebView 已销毁（App 退出中）：这次指令直接丢掉 */
                }
            });
        } catch (Exception e) {
            /* webView 已被回收 */
        }
    }

    public void release() {
        webView.removeJavascriptInterface("ModouBridge");
        tts.release();
        if (sShared == this) sShared = null;
    }
}
