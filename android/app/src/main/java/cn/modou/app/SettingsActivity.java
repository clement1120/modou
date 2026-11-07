package cn.modou.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 设置页薄壳：UI 与逻辑在 SettingsPanel（同一份面板也嵌入首页底部导航）。
 * 这个 Activity 只保留独立打开时的窗口壳与 onActivityResult 转发。
 */
public class SettingsActivity extends Activity {

    private SettingsPanel panel;

    /** 「启动即进原生书架」的开关：存原生偏好，MainActivity 在 WebView 起来之前就要读它 */
    static final String PREF_NATIVE_START = "nativeShelfStart";

    static void launch(Activity from) {
        ModouTransition.push(from, new Intent(from, SettingsActivity.class));
    }

    /** 统一返回转场：三套语言各自的 pop 动作（见 {@link ModouTransition}） */
    @Override
    public void finish() {
        super.finish();
        ModouTransition.back(this);
    }

    @Override
    protected void onCreate(Bundle b) {
        ModouUi.applyTheme(this);
        super.onCreate(b);
        // 0.9.185：沉浸式 —— 底衬铺到状态栏下面；内容位置由 SettingsPanel 的
        // statusBarPad() 顶回来，不再重复留白。
        ModouUi.edgeToEdge(this);
        panel = new SettingsPanel(this);
        setContentView(panel.view(false));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (panel != null) panel.onActivityResult(req, res, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 外观可能在别处被改过（阅读页夜间 / 设置页 / 系统深浅）：整页重建才换得掉
        // 已经写死在 View 上的颜色，否则会出现「同一屏两套色调」
        ModouUi.syncTheme(this);
    }
}
