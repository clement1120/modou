package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 原生界面（书架 / 详情 / 设置）的公共外观与组件工厂。
 *
 * 配色对齐网页版的奶油 + 赤陶（#F5F0E2 底 / #D96C47 强调），深浅两套随全局
 * 「外观」设置走。单独抽出来的原因：三个原生屏若各写一套色值，改主题时必然
 * 出现「书架变了详情没变」的色差，而这种色差最难在代码审查里发现。
 *
 * 全部用 framework 组件代码构建（项目零第三方依赖、无 androidx），
 * 与 NativeReaderActivity 的做法一致。
 */
final class ModouUi {

    /** 全局外观存档：与 MainActivity 完全同一份（否则两边主题会打架） */
    private static final String PREFS = "modou";
    private static final String PREF_APP_THEME = "appTheme";

    final boolean dark;
    final int bg;
    final int card;
    final int text;
    final int sub;
    final int line;
    final int accent;
    final int danger;

    private ModouUi(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xFF1B1A17;
            card = 0xFF24231F;
            text = 0xFFE8E2D4;
            sub = 0xFF8E8878;
            line = 0xFF33322C;
            accent = 0xFFE07A55;
            danger = 0xFFE5544B;
        } else {
            bg = 0xFFF5F0E2;
            card = 0xFFFCF8EE;
            text = 0xFF2B251B;
            sub = 0xFF7A6F5C;
            line = 0xFFE3DCCB;
            accent = 0xFFD96C47;
            danger = 0xFFC8453C;
        }
    }

    /** 按当前生效的外观（light / dark / auto）取配色；auto 跟随系统 */
    static ModouUi of(Context c) {
        return new ModouUi(isDark(c));
    }

    static boolean isDark(Context c) {
        String m = themeMode(c);
        if ("dark".equals(m)) return true;
        if ("auto".equals(m)) return systemDark(c);
        return false;
    }

    static String themeMode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_APP_THEME, "light");
    }

    /** 系统是否深色：问 UiModeManager，取不到再退到系统资源配置 */
    private static boolean systemDark(Context c) {
        try {
            android.app.UiModeManager um =
                    (android.app.UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            if (um != null) {
                int n = um.getNightMode();
                if (n == android.app.UiModeManager.MODE_NIGHT_YES) return true;
                if (n == android.app.UiModeManager.MODE_NIGHT_NO) return false;
            }
        } catch (Exception e) {
            /* 取不到：走资源配置兜底 */
        }
        int ui = android.content.res.Resources.getSystem().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 只写外观存档，**不**在这里调用 setApplicationNightMode。
     *
     * 为什么：setApplicationNightMode 会触发一次「应用级 uiMode 配置变更」，
     * 凡是 manifest 里没声明 configChanges=uiMode 的 Activity（书架、详情页…）
     * 都会被系统销毁并重建。结果就是：在阅读页切个深浅色，连带把后台的书架/
     * 详情页一起干掉，用户看到的就是「全屏黑一下、退回首页」。系统夜间模式
     * 只影响冷启动闪屏底色，交给 MainActivity 在 onCreate（applySavedTheme）和
     * 前端 __modouSaveAppTheme（saveAppTheme，MainActivity 已声明 configChanges=uiMode
     * 不会重建）里同步即可，无需在每次会话内切换时都搅动一次全局配置。
     */
    static void saveThemeMode(Context c, String mode) {
        String m = "dark".equals(mode) || "auto".equals(mode) ? mode : "light";
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_APP_THEME, m).apply();
    }

    /**
     * 每个 Activity 建界面时套的是哪套色。
     *
     * Activity 的主题在 onCreate 之前定型、页面里的颜色又是建 View 时一次性取好写死的，
     * 所以「在阅读页点了夜间 / 在设置里改了外观 / 系统日落切了深浅」之后，已经建好的
     * 那些页面不会自己变色——书架还是黑的，新弹的菜单却是浅的，这就是用户看到的
     * 「同一屏里两套色调」。这里记下建页时的深浅，回到前台比对一次，不一致就整页重建。
     */
    private static final java.util.WeakHashMap<Activity, Boolean> APPLIED =
            new java.util.WeakHashMap<>();

    /**
     * 在 setContentView 之前套用深色主题（浅色用 manifest 默认 Theme.Modou）。
     * 必须早于任何 View 创建，否则窗口底色/系统栏图标颜色对不上。
     */
    static void applyTheme(Activity a) {
        boolean dark = isDark(a);
        APPLIED.put(a, dark);
        if (dark) a.setTheme(R.style.Theme_Modou_Dark);
    }

    /**
     * 回到前台时对一次色：外观在别处被改过（阅读页夜间、设置页、系统深浅）就重建本页。
     *
     * 调用点必须放在 onResume 最前面并 `if (...) return;`——recreate 之后 onResume 会再进
     * 一次，这次比对已经一致，才继续正常刷新数据。
     *
     * @return true 表示已经触发了重建，本次 onResume 不要继续做事
     */
    static boolean syncTheme(Activity a) {
        if (!themeChanged(a)) return false;
        a.recreate();
        return true;
    }

    /** 本页建界面时的深浅，与此刻的全局外观是否已经不一致（不一致就得重建） */
    static boolean themeChanged(Activity a) {
        Boolean was = APPLIED.get(a);
        if (was == null) {
            APPLIED.put(a, isDark(a));
            return false;
        }
        return was != isDark(a);
    }

    /* ---------- 基础组件 ---------- */

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    static TextView tv(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** 圆角纯色背景 */
    static GradientDrawable round(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(rDp * 3.2f); // 近似 dp→px（无 Context 时够用）
        return g;
    }

    /** 卡片：圆角 + 内边距 + 分隔线色 */
    LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(card, 12));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    /* ---------- Inset Grouped 组列表 ----------
     * 网页版（iOS 风格）的分组列表是「一个圆角组容器 + 紧贴的行 + 行间细分隔线」；
     * 之前原生页把每行做成独立灰块卡片堆起来，行距稀疏、灰底糊成一片，
     * 和网页版一比就像没设计过。统一改用这套组件。
     */

    /** 组容器：圆角取 M3 中档 {@link ModouTokens#CORNER_M} 的实底卡片，内部不加 padding（行自己留白） */
    LinearLayout groupCard(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(card, ModouTokens.CORNER_M));
        return l;
    }

    /**
     * 组行：左「标题 + 说明」竖排、右值；onClick 非空则整行可点（按压态走 ripple，
     * 不再给行铺常驻灰底——灰底一多整个组看起来就是一坨）。
     */
    LinearLayout groupRow(Context c, String title, String value, String hint, Runnable onClick) {
        ModouUi u = of(c);
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(c, ModouTokens.ROW_PAD_H), dp(c, ModouTokens.ROW_PAD_V),
                dp(c, ModouTokens.ROW_PAD_H), dp(c, ModouTokens.ROW_PAD_V));

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(tv(c, title, 14.5f, u.text));
        if (hint != null && !hint.isEmpty()) {
            TextView s = tv(c, hint, 11.5f, u.sub);
            s.setPadding(0, dp(c, 2), 0, 0);
            left.addView(s);
        }
        l.addView(left);

        if (value != null && !value.isEmpty()) {
            TextView v = tv(c, value, 13, u.sub);
            v.setPadding(dp(c, 10), 0, 0, 0);
            l.addView(v);
        }
        if (onClick != null) {
            l.setOnClickListener((x) -> onClick.run());
            android.graphics.drawable.RippleDrawable rip = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(alpha(u.text, 0x14)),
                    null, null);
            l.setBackground(rip);
        }
        return l;
    }

    /** 组内行间分隔线：左右缩进 14，1px，与行底色区分 */
    static View groupDivider(Context c) {
        ModouUi u = of(c);
        View v = new View(c);
        v.setBackgroundColor(alpha(u.line, 0xFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, ModouTokens.DIVIDER)));
        lp.leftMargin = dp(c, ModouTokens.ROW_PAD_H);
        lp.rightMargin = dp(c, ModouTokens.ROW_PAD_H);
        v.setLayoutParams(lp);
        return v;
    }

    /** 组标题：放在组容器**外**上方的小字（iOS grouped 惯例），13sp 灰 */
    static TextView groupTitle(Context c, String text) {
        TextView t = tv(c, text, 13, of(c).sub);
        t.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 6));
        return t;
    }

    /** 卡片标题行：主标题 + 右侧灰色小字说明 */
    LinearLayout headRow(Context c, String title, String hint) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(c, 8));
        TextView h = tv(c, title, 15, text);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(h);
        if (hint != null && !hint.isEmpty()) {
            TextView s = tv(c, hint, 12, sub);
            s.setPadding(dp(c, 8), 0, 0, 0);
            row.addView(s);
        }
        return row;
    }

    /**
     * 设置行：左侧「标题 + 说明」竖排，右侧值；onClick 非空则可点。
     * 横向布局而不是把值塞进竖排——竖排里 gravity=END 对宽度不生效，
     * 值会贴在最左边，看着像标题而不是结果。
     */
    LinearLayout row(Context c, String title, String value, String hint, Runnable onClick) {
        ModouUi u = of(c);
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(c, 12);
        l.setPadding(pad, pad, pad, pad);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(tv(c, title, 15, u.text));
        if (hint != null && !hint.isEmpty()) {
            TextView s = tv(c, hint, 12, u.sub);
            s.setPadding(0, dp(c, 3), 0, 0);
            left.addView(s);
        }
        l.addView(left);

        if (value != null) {
            TextView v = tv(c, value, 14, u.accent);
            v.setGravity(Gravity.END);
            v.setPadding(dp(c, 10), 0, 0, 0);
            l.addView(v);
        }
        if (onClick != null) {
            l.setOnClickListener((x) -> onClick.run());
            l.setBackground(rowBg(c, u));
        }
        return l;
    }

    /** 可点行的按压反馈：用 selector 太重，这里给一个半透明高亮底 */
    private static GradientDrawable rowBg(Context c, ModouUi u) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(u.dark ? 0x1AFFFFFF : 0x0F000000);
        g.setCornerRadius(dp(c, ModouTokens.CORNER_S));
        return g;
    }

    static void toast(Context c, String s) {
        try {
            Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            /* 通知栏受限的 ROM：忽略，不能因提示失败而崩 */
        }
    }

    /**
     * 危险操作确认：墨读的惯例是两次确认（清空数据）。
     *
     * 走自绘 sheet，不用系统 AlertDialog —— 后者在深色下依然是白底黑字，
     * 一弹出来就是「整 App 深色、独这一块浅色」的突兀感。
     */
    static void confirm(Context c, String title, String msg, Runnable ok) {
        if (c instanceof Activity) {
            Activity a = (Activity) c;
            try {
                ModouUi u = of(a);
                LinearLayout box = new LinearLayout(a);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(a, 14), dp(a, 4), dp(a, 14), 0);
                TextView t = tv(a, msg, 13.5f, u.sub);
                t.setLineSpacing(dp(a, 3), 1.3f);
                box.addView(t);
                Dialog d = sheet(a, title, box);
                box.addView(sheetButtons(a, d, "取消", "确定", null, ok));
                return;
            } catch (Throwable e) {
                // 面板起不来就别执行删除类操作，宁可没反应也不能误删
                toast(a, "面板打开失败，请重试");
                return;
            }
        }
        try {
            new android.app.AlertDialog.Builder(c)
                    .setTitle(title)
                    .setMessage(msg)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定", (d, w) -> ok.run())
                    .show();
        } catch (Exception e) {
            toast(c, "面板打开失败，请重试");
        }
    }

    /** 分隔线 */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(of(c).line);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /** 空态 */
    static TextView empty(Context c, String s) {
        TextView t = tv(c, s, 14, of(c).sub);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 40), dp(c, 16), dp(c, 40));
        return t;
    }

    static int alpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /* ---------------- 底部 sheet ----------------
     * 系统 AlertDialog 是白底 + Material 大字标题 + 全宽直角列表，
     * 在纸墨界面里一眼就是「另一个软件」。这里统一改用自绘底部卡片：
     * 圆角 22dp、卡片色底、1dp 极淡描边、顶部一根拖动条。
     * 与 NativeReaderActivity 的工具坞同一套视觉（它有自己的 overlay，不复用）。
     */

    /** 底部 sheet 外壳：返回 dialog 以便关闭；content 由调用方填充 */
    static Dialog sheet(Activity a, String title, LinearLayout content) {
        return sheet(a, title, content, null);
    }

    /**
     * 带固定底部按钮的 sheet：content 放进限高滚动区，footer（按钮行）留在滚动区外。
     *
     * 表单（编辑信息这类）字段多、又怕键盘顶起来，按钮塞进滚动区就会被推到屏幕外，
     * 「保存」看不见也点不到。footer 允许先传一个空容器，等 dialog 建好再 addView，
     * 这样按钮行拿得到 dismiss 用的 dialog 引用。
     */
    static Dialog sheet(Activity a, String title, LinearLayout content, LinearLayout footer) {
        ModouUi u = of(a);
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(a, 10), 0, dp(a, 10), dp(a, 10));

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(u.card);
        bg.setCornerRadius(dp(a, ModouTokens.CORNER_L));
        bg.setStroke(Math.max(1, dp(a, ModouTokens.DIVIDER)), alpha(u.text, 0x14));
        panel.setBackground(bg);
        panel.setPadding(dp(a, ModouTokens.PANEL_PAD_H), dp(a, ModouTokens.PANEL_PAD_T),
                dp(a, ModouTokens.PANEL_PAD_H), dp(a, ModouTokens.PANEL_PAD_B));

        View grab = new View(a);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(alpha(u.text, 0x26));
        gb.setCornerRadius(dp(a, ModouTokens.CORNER_XS));
        grab.setBackground(gb);
        LinearLayout.LayoutParams glp =
                new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(a, 12);
        panel.addView(grab, glp);

        if (title != null && !title.isEmpty()) {
            TextView t = tv(a, title, 17, u.text);
            t.setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD);
            t.setPadding(0, 0, 0, dp(a, 10));
            panel.addView(t);
        }
        if (content != null) {
            // 分组、书源这类列表可能几十行：套一层限高滚动，别让 sheet 顶穿屏幕。
            // 注意 content 必须还没有父容器——ScollView 只能有一个直接子节点，
            // 传进来已挂过父的 view 会直接抛 IllegalStateException（编辑信息就踩过）。
            if (content.getParent() != null) {
                ((ViewGroup) content.getParent()).removeView(content);
            }
            MaxHeightScroll sv = new MaxHeightScroll(a,
                    (int) (a.getResources().getDisplayMetrics().heightPixels * 0.62f));
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.addView(sv);
        }
        if (footer != null) panel.addView(footer);
        wrap.addView(panel);

        d.setContentView(wrap);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.35f);
            w.setGravity(Gravity.BOTTOM);
            // 面板里有输入框（编辑信息 / 自定义分组名等）：键盘弹出时整块上推，
            // 否则输入框会被键盘压住，这在旧版系统 AlertDialog 上是默认行为
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
        // setLayout 必须在 show() 之后：Dialog 在 attach 前拿不到 decor 的实际参数
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        return d;
    }

    /** 纯文字列表 sheet（替代 AlertDialog.setItems 的白底列表） */
    static void sheetList(Activity a, String title, String[] items, IntConsumer pick) {        ModouUi u = of(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] holder = new Dialog[1];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = tv(a, items[i], 15, u.text);
            t.setPadding(dp(a, 2), dp(a, 14), dp(a, 2), dp(a, 14));
            t.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                pick.accept(idx);
            });
            list.addView(t);
        }
        holder[0] = sheet(a, title, list);
    }

    /** 纸墨风格输入框（sheet 表单用）：卡片色块 + 1dp 描边 + 圆角 8dp */
    static android.widget.EditText input(Context c, String hint, String value, int minLines) {
        ModouUi u = of(c);
        android.widget.EditText ed = new android.widget.EditText(c);
        ed.setHint(hint);
        ed.setText(value == null ? "" : value);
        ed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        ed.setTextColor(u.text);
        ed.setHintTextColor(u.sub);
        ed.setMinLines(minLines);
        ed.setBackground(round(alpha(u.text, 0x0F), 8));
        ed.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        // 表单里常常是「书名 / 作者 / 封面」这样连着排几行：不给间距的话几个圆角色块
        // 会首尾贴在一起，圆角互相啃掉一块，看着就像「三行框叠在一起」。
        // 在这里给统一的下间距，竖排容器的调用点就不用各自补了。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 8);
        ed.setLayoutParams(lp);
        return ed;
    }

    /**
     * 自绘开关行（标题 + 胶囊开关）：系统 CheckBox / Switch 在纸墨底上是一块
     * Material 蓝方块，与阅读页里的分段控件完全不是一套语言。
     */
    static LinearLayout switchRow(Context c, String label, boolean on, Consumer<Boolean> cb) {
        ModouUi u = of(c);
        final boolean[] st = {on};
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 2), dp(c, 8), dp(c, 2), dp(c, 8));

        TextView t = tv(c, label, 14, u.text);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(t);

        final LinearLayout pill = new LinearLayout(c);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        final View knob = new View(c);
        GradientDrawable kb = new GradientDrawable();
        kb.setShape(GradientDrawable.OVAL);
        kb.setColor(0xFFFFFFFF);
        knob.setBackground(kb);
        final int trackW = dp(c, 40), trackH = dp(c, 24), knobS = dp(c, 18);
        pill.addView(knob, new LinearLayout.LayoutParams(knobS, knobS));

        final Runnable render = () -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(trackH / 2f);
            bg.setColor(st[0] ? u.accent : alpha(u.text, 0x22));
            pill.setBackground(bg);
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) knob.getLayoutParams();
            lp.leftMargin = st[0] ? trackW - knobS - dp(c, 3) : dp(c, 3);
            lp.rightMargin = 0;
            knob.setLayoutParams(lp);
        };
        pill.setPadding(0, 0, 0, 0);
        pill.setLayoutParams(new LinearLayout.LayoutParams(trackW, trackH));
        render.run();
        row.addView(pill);
        row.setOnClickListener(v -> {
            st[0] = !st[0];
            render.run();
            cb.accept(st[0]);
        });
        return row;
    }

    /** 限高滚动容器：内容不超过 maxH 时按内容高度，超出才滚 */
    private static final class MaxHeightScroll extends android.widget.ScrollView {
        private final int maxH;

        MaxHeightScroll(Context c, int maxH) {
            super(c);
            this.maxH = maxH;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        }
    }

    /** sheet 底部的按钮行：取消（淡底）+ 主操作（强调色实底） */
    static LinearLayout sheetButtons(Activity a, Dialog owner, String cancel, String ok,
                                     Runnable onCancel, Runnable onOk) {
        ModouUi u = of(a);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(a, 12), 0, 0);

        TextView c = tv(a, cancel, 15, u.text);
        c.setGravity(Gravity.CENTER);
        c.setPadding(0, dp(a, 12), 0, dp(a, 12));
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(alpha(u.text, 0x0A));
        cb.setCornerRadius(dp(a, 12));
        c.setBackground(cb);

        TextView k = tv(a, ok, 15, 0xFFFFFFFF);
        k.setGravity(Gravity.CENTER);
        k.setPadding(0, dp(a, 12), 0, dp(a, 12));
        GradientDrawable kb = new GradientDrawable();
        kb.setColor(u.accent);
        kb.setCornerRadius(dp(a, 12));
        k.setBackground(kb);

        row.addView(c, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        klp.leftMargin = dp(a, 10);
        row.addView(k, klp);

        c.setOnClickListener(v -> {
            if (owner != null) owner.dismiss();
            if (onCancel != null) onCancel.run();
        });
        k.setOnClickListener(v -> {
            if (owner != null) owner.dismiss();
            if (onOk != null) onOk.run();
        });
        return row;
    }

    /**
     * 单按钮提示（结果 / 说明类）。
     *
     * 不用 Toast：中文一句话就两行，系统 Toast 超过两行会被截断，
     * 「回收了多少、还差多少空间」这种关键数字正好被切掉。
     */
    static void alert(Context c, String title, String msg) {
        if (!(c instanceof Activity)) {
            toast(c, msg);
            return;
        }
        Activity a = (Activity) c;
        try {
            ModouUi u = of(a);
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(a, 14), dp(a, 4), dp(a, 14), 0);
            TextView t = tv(a, msg, 13.5f, u.sub);
            t.setLineSpacing(dp(a, 3), 1.3f);
            box.addView(t);
            Dialog d = sheet(a, title, box);

            TextView ok = tv(a, "知道了", 15, 0xFFFFFFFF);
            ok.setGravity(Gravity.CENTER);
            ok.setPadding(0, dp(a, 12), 0, dp(a, 12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(u.accent);
            bg.setCornerRadius(dp(a, 12));
            ok.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(a, 12);
            box.addView(ok, lp);
            ok.setOnClickListener(v -> d.dismiss());
        } catch (Throwable e) {
            toast(c, msg);
        }
    }
}
