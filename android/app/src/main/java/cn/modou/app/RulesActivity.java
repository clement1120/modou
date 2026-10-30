package cn.modou.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/**
 * 正文净化规则页薄壳：UI 与逻辑在 RulesPanel（同一份面板也嵌入首页底部导航）。
 */
public class RulesActivity extends Activity {

    static void launch(Activity from) {
        from.startActivity(new Intent(from, RulesActivity.class));
    }

    /**
     * 从阅读页「替换净化」进来：带着选中的文字，直接开新规则编辑器，
     * 光标落在「替换为」（选中即建规则，与网页版选词工具条一致）。
     */
    static void launchAdd(Activity from, String pattern) {
        Intent i = new Intent(from, RulesActivity.class);
        i.putExtra("addPattern", pattern == null ? "" : pattern);
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ModouUi.applyTheme(this);
        super.onCreate(savedInstanceState);
        RulesPanel panel = new RulesPanel(this);
        View pv = panel.view(false);
        setContentView(pv);
        // 从阅读页选词进来：列表先画出来，再把「添加规则」的编辑器弹起来并预填选中的文字。
        // 必须 post：onCreate 时窗口还没 attach，Dialog 直接 show 会崩
        String add = getIntent() == null ? "" : getIntent().getStringExtra("addPattern");
        if (add != null && !add.trim().isEmpty()) {
            final String pat = add.trim();
            pv.post(() -> panel.edit(-1, pat));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 外观可能在别处被改过（阅读页夜间 / 设置页 / 系统深浅）：整页重建才换得掉
        // 已经写死在 View 上的颜色，否则会出现「同一屏两套色调」
        ModouUi.syncTheme(this);
    }
}
