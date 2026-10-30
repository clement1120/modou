package cn.modou.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 书源管理页薄壳：UI 与逻辑在 SourcesPanel（同一份面板也嵌入首页底部导航）。
 */
public class SourcesActivity extends Activity {

    static void launch(Activity from) {
        from.startActivity(new Intent(from, SourcesActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ModouUi.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(new SourcesPanel(this).view(false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 外观可能在别处被改过（阅读页夜间 / 设置页 / 系统深浅）：整页重建才换得掉
        // 已经写死在 View 上的颜色，否则会出现「同一屏两套色调」
        ModouUi.syncTheme(this);
    }
}
