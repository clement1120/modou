package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 原生界面（书架 / 详情 / 设置）的公共外观与组件工厂。
 *
 * <p>0.9.158 起：配色、形状、排版、弹层策略全部由 {@link DesignSpec} 提供 ——
 * 三套设计语言（墨读 / Material 3 / iOS 26）共用这一套组件工厂，组件只读规格、
 * 不判断语言，因此改一套语言只改规格表，全 App 一改全改。
 *
 * <p>字段名 {@code bg / card / text / sub / line / accent / danger / dark} 刻意保留，
 * 这样 15 个 {@code ModouUi.of()} 调用点一行都不用改。
 *
 * 全部用 framework 组件代码构建（项目零第三方依赖、无 androidx），
 * 与 NativeReaderActivity 的做法一致。
 */
final class ModouUi {

    /** 全局外观存档：与 MainActivity 完全同一份（否则两边主题会打架） */
    private static final String PREFS = "modou";
    private static final String PREF_APP_THEME = "appTheme";

    /** 当前设计语言规格：组件工厂的一切形状/排版/弹层决策都从这里取 */
    final DesignSpec spec;

    final boolean dark;
    final int bg;
    final int card;
    final int text;
    final int sub;
    final int line;
    final int accent;
    final int danger;

    private ModouUi(DesignSpec spec) {
        this.spec = spec;
        this.dark = spec.dark;
        this.bg = spec.bg;
        this.card = spec.surface;
        this.text = spec.text;
        this.sub = spec.sub;
        this.line = spec.line;
        this.accent = spec.accent;
        this.danger = spec.danger;
    }

    /** 按当前生效的设计语言 + 外观（light / dark / auto）取配色；auto 跟随系统 */
    static ModouUi of(Context c) {
        boolean dark = isDark(c);
        return new ModouUi(DesignSpec.of(c, dark));
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

    /**
     * 系统是否深色。
     *
     * <p>顺序：系统全局设置（YES/NO 直接给答案）→ **本 App 的 Configuration**
     * → 系统资源配置。中间这一层是关键：App 是「跟随系统」时，它的 Configuration
     * 就是系统的真实状态，而且随配置变更**同步刷新**；{@code Resources.getSystem()}
     * 在部分 ROM 上刷新滞后，会让「跟随系统」慢半拍甚至要重启才认。
     */
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
        int ui = c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (ui == android.content.res.Configuration.UI_MODE_NIGHT_YES) return true;
        if (ui == android.content.res.Configuration.UI_MODE_NIGHT_NO) return false;
        ui = android.content.res.Resources.getSystem().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 写外观存档，**并且**立刻把「本 App 用哪种深浅」告知系统。
     *
     * <p>{@code setApplicationNightMode} 会触发一次应用级 uiMode 变更。以前怕它把后台
     * 页面销毁所以故意不调，结果留下一个更隐蔽的坑：只要这个进程里被强制过一次
     * （比如用户在网页版里选过「深色」），之后在设置里切到「跟随系统 / 浅色」，
     * 系统层面记住的**仍然是强制深色** —— 新开的页面、窗口底色、系统栏全是深的，
     * 直到用户把 App 杀掉重进才恢复。这正是「切到跟随系统必须先重启」的根因。
     *
     * <p>现在本 App 所有 Activity 都声明了 configChanges=uiMode，配置变更不会重建页面
     * （见 {@code ModouApp.onConfigurationChanged}：由它按需重建当前页），所以可以放心同步。
     */
    static void saveThemeMode(Context c, String mode) {
        String m = "dark".equals(mode) || "auto".equals(mode) ? mode : "light";
        // 先对齐系统，再落存档：页面重建时读到的配置已经是新的
        applyApplicationNightMode(c, m);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_APP_THEME, m).apply();
    }

    /**
     * 保存「整 App 深浅」的<b>唯一入口</b>（0.9.161）。
     *
     * <p>四处副作用必须一起写，少一处就会出现「原生换了、网页版还是旧的」：
     * 系统夜间模式 + SharedPreferences（下次冷启动开屏底色）+ settings 表
     * {@code appTheme}（网页版读它）+ 网页版 JS 钩子。
     *
     * <p>调用方：主题外观画廊的深浅三行、阅读纸张画廊选夜间/浅色纸、
     * 阅读页 {@code applyTheme} 的全局联动。设置页原三段 segment 已按用户要求
     * 移除，深浅不再有第二个入口，因此这里补齐缺口是去重的必要前提。
     */
    static void saveAppTheme(Context c, String mode) {
        String m = "dark".equals(mode) || "auto".equals(mode) ? mode : "light";
        saveThemeMode(c, m);
        try {
            ModouStore.get(c).putSettingStr("appTheme", m);
        } catch (Throwable ignore) {
            /* 数据库暂不可用：下次启动以 pref 为准 */
        }
        try {
            ModouBridge br = ModouBridge.shared();
            if (br != null) {
                br.evalJs("window.__modouSaveAppTheme && window.__modouSaveAppTheme('"
                        + m + "')");
            }
        } catch (Throwable ignore) {
            /* 网页版还没加载这个钩子：不影响原生 */
        }
    }

    /** 把「本 App 的深浅」告知系统（API 31+）：窗口底色、开屏闪屏、本进程 Configuration 都跟着它 */
    static void applyApplicationNightMode(Context c, String mode) {
        // 0.9.138：minSdk 已提到 31，setApplicationNightMode 恒可用，不再需要低版本提前返回。
        try {
            android.app.UiModeManager um =
                    (android.app.UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            if (um == null) return;
            int n;
            if ("dark".equals(mode)) n = android.app.UiModeManager.MODE_NIGHT_YES;
                // UiModeManager 没有 MODE_NIGHT_FOLLOW_SYSTEM：AUTO 才是「不覆盖、跟随系统」
            else if ("auto".equals(mode)) n = android.app.UiModeManager.MODE_NIGHT_AUTO;
            else n = android.app.UiModeManager.MODE_NIGHT_NO;
            um.setApplicationNightMode(n);
        } catch (Throwable ignore) {
            /* 个别 ROM 没有这个能力：保持原样，页面颜色仍由 isDark() 决定 */
        }
    }

    /**
     * 系统深浅色变了（{@code ModouApp.onConfigurationChanged} 调进来）。
     *
     * <p>只重建**当前这一页**：后台页面回到前台时会走各自的 {@code syncTheme} 自行重建，
     * 一次全建一遍既慢又会把用户正在做的事打断。
     *
     * <p>判定「此刻深浅」必须用 {@code newConfig} 里系统刚发的 uiMode，<b>不能</b>重查
     * {@code a.getResources().getConfiguration()}：Application.onConfigurationChanged 被回调时，
     * 前台 Activity 的 Resources 往往还没刷新（部分 ROM 尤其明显），重查就会读到旧值，
     * 于是「建页时 == 此刻」误判成没变、不重建、切了没反应——这正是「跟随系统不及时变色」
     * 的根因。{@code newConfig} 是系统这一轮派发的权威配置，auto 模式下它就是系统真实深浅。
     */
    static void onUiModeChanged(android.content.res.Configuration newConfig) {
        Activity a = ModouApp.resumed();
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        // MainActivity 是隐藏的 WebView 壳（重建会把引擎连带拆掉）；
        // 阅读页的纸张主题独立于 App 外观（重建会打断正在读的那一页）
        if (a instanceof MainActivity || a instanceof NativeReaderActivity) return;
        // 此刻深浅：auto 直接读 newConfig（零滞后）；dark/light 是固定值，不受配置变更影响
        final boolean nowDark;
        if ("auto".equals(themeMode(a))) {
            nowDark = (newConfig.uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } else {
            nowDark = "dark".equals(themeMode(a));
        }
        // 0.9.158：比对的是「语言 + 深浅」整个状态，不是只比深浅。
        // 只比深浅的话，用户在设置里换了设计语言，系统夜间切换这一轮会误判成「没变」。
        String was = APPLIED.get(a);
        if (was == null || was.equals(DesignSpec.stateKey(a, nowDark))) return;
        // 等这一轮配置变更走完再重建，避免半新半旧的窗口
        try {
            a.getWindow().getDecorView().post(a::recreate);
        } catch (Throwable ignore) {
            /* 窗口还没建好：下一帧 onResume 的 syncTheme 会兜住 */
        }
    }

    /**
     * 每个 Activity 建界面时套的是哪套色。
     *
     * Activity 的主题在 onCreate 之前定型、页面里的颜色又是建 View 时一次性取好写死的，
     * 所以「在阅读页点了夜间 / 在设置里改了外观 / 系统日落切了深浅」之后，已经建好的
     * 那些页面不会自己变色——书架还是黑的，新弹的菜单却是浅的，这就是用户看到的
     * 「同一屏里两套色调」。这里记下建页时的深浅，回到前台比对一次，不一致就整页重建。
     */
    private static final java.util.WeakHashMap<Activity, String> APPLIED =
            new java.util.WeakHashMap<>();

    /**
     * 在 setContentView 之前套用「设计语言 × 深浅」对应的 Activity 主题。
     * 必须早于任何 View 创建，否则开屏底色/系统栏图标颜色对不上。
     *
     * <p>0.9.158：设计语言也参与比对，换语言后所有后台页面回前台都会重建。
     */
    static void applyTheme(Activity a) {
        boolean dark = isDark(a);
        DesignSpec.Lang lang = DesignSpec.current(a);
        APPLIED.put(a, DesignSpec.stateKey(a, dark));
        a.setTheme(themeRes(lang, dark));
    }

    /** 六套窗口底色的资源映射（3 语言 × 深浅）。冷启动闪屏由 values-night 兜底。 */
    static int themeRes(DesignSpec.Lang lang, boolean dark) {
        if (lang == null) lang = DesignSpec.Lang.MODOU;
        switch (lang) {
            case MATERIAL:
                return dark ? R.style.Theme_Modou_Material_Dark : R.style.Theme_Modou_Material;
            case LIQUID:
                return dark ? R.style.Theme_Modou_Liquid_Dark : R.style.Theme_Modou_Liquid;
            case MODOU:
            default:
                return dark ? R.style.Theme_Modou_Dark : R.style.Theme_Modou;
        }
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

    /** 本页建界面时的「语言 + 深浅」，与此刻是否已经不一致（不一致就得重建） */
    static boolean themeChanged(Activity a) {
        String now = DesignSpec.stateKey(a, isDark(a));
        String was = APPLIED.get(a);
        if (was == null) {
            APPLIED.put(a, now);
            return false;
        }
        return !was.equals(now);
    }

    /* ---------- 基础组件 ---------- */

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /**
     * 顶栏左右安全边距（全站唯一标准）。
     *
     * <p>以前各页各写一个数：阅读页 6dp、书架右 4dp、面板 14dp、详情 2dp ——
     * 圆钮几乎贴着屏幕边，一屏一屏翻过去像四套排版。40dp 的圆钮在 16dp 处
     * 才是「有呼吸感」的起点（ColorOS 同款），再小就会被屏幕圆角/保护壳压住。
     */
    static final int TOP_INSET_DP = 16;

    static int topInset(Context c) {
        return dp(c, DesignSpec.of(c).topInset);
    }

    static TextView tv(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /**
     * 全局统一「返回」圆钮：直接复用详情页那颗 40dp 正圆 + 19dp 细线箭头
     * （{@link #topBtn}），箭头由 {@link ModouGlyph} 以圆心为原点绘制，圆内绝对居中。
     *
     * <p>旧的「‹ 返回」文字钮已全部下线：字形高度决定基线，永远比圆钮偏一点；
     * 一屏里同时出现「‹ 返回」和小圆钮，也像两套图标。返回 = 纯图标，不带任何文字。
     */
    static ImageView backBtn(Context c, Runnable r) {
        return topBtn(c, ModouGlyph.BACK, 40, v -> {
            if (r != null) r.run();
        });
    }

    /**
     * 页面级顶栏标题：三页（书架 / 书源 / 设置）共用同一条样式——20sp 衬线粗体，
     * 比旧版的 24/26sp 收一档，更克制、更像「内容之上的一行标题」而不是抢戏的大字。
     * 衬线保留（奶油 + 赤陶 + 衬线是全 App 仅有的辨识度），只是降级尺寸与气场。
     */
    static TextView pageTitle(Context c, String s) {
        DesignSpec sp = DesignSpec.of(c);
        TextView t = tv(c, s, sp.pageTitleSp, of(c).text);
        // 三套语言各自的标题性格：墨读 26sp 粗黑 / M3 32sp Medium / iOS 34sp Bold 大标题
        applyType(t, sp.pageTitleWeight, sp.pageTitleTracking,
                sp.pageTitleSerif ? android.graphics.Typeface.SERIF
                        : android.graphics.Typeface.DEFAULT);
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * A8（0.9.163）：把字阶表的 weight / tracking 统一落到 TextView。
     * weight 编码：0 normal / 1 bold / 2 medium(500) / 3 semibold(600)。
     * 纯 Java 手绘拿不到 Roboto Flex 可变字重，medium 用系统 sans-serif-medium，
     * semibold 用 bold 近似 —— 但字距是官方精确值，不会再出现「三套都 0 字距」。
     */
    static void applyType(TextView t, int weight, float tracking,
                          android.graphics.Typeface base) {
        if (base == null) base = android.graphics.Typeface.DEFAULT;
        switch (weight) {
            case 2:
                t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.NORMAL));
                break;
            case 1:
            case 3:
                t.setTypeface(base, android.graphics.Typeface.BOLD);
                break;
            default:
                t.setTypeface(base, android.graphics.Typeface.NORMAL);
        }
        t.setLetterSpacing(tracking);
    }

    /** 圆角纯色背景 */
    static GradientDrawable round(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(rDp * 3.2f); // 近似 dp→px（无 Context 时够用）
        return g;
    }

    /**
     * 连续曲率圆角（Squircle）背景 —— COUI17「流体设计」的核心：边角是平滑过渡的
     * 超椭圆曲线，而不是普通的圆弧，视觉上更柔和、更「整块」。
     *
     * 曲线本身交给 {@link ModouShape}（Lp(2.5) 超椭圆的贝塞尔对位，n=2.5 时控制柄
     * 距角点 0.3123T），这里只负责把它包成 Drawable。旧的 k=0.62 是从「直线端点」量起的，
     * 折合距角点 0.38T —— 落在正圆（0.4477T）与超椭圆（0.3123T）之间，比系统曲线略「圆」。
     * 差异在 22dp 角上约 1.7px、8dp 角上约 0.6px：不是肉眼一惊的程度，但曲线性质对了，
     * 以后把卡片圆角往 28dp 提时不会跑形。
     */
    static Drawable squircle(int color, float rDp) {
        return new SquircleDrawable(color, rDp * 3.2f, false, 0, 0);
    }

    /**
     * 同上，但用真实密度换算（有 Context 时优先用这个重载）。
     *
     * <p>0.9.158：Material 3 的形状是<b>普通圆弧</b>而非连续曲率，这里按
     * {@code DesignSpec.squircle} 自动切曲线，调用方一行都不用改。
     */
    static Drawable squircle(Context c, int color, float rDp) {
        return new SquircleDrawable(color, dp(c, rDp), false, 0, 0,
                !DesignSpec.of(c).squircle);
    }

    /** 超椭圆圆角 + 描边（替代 GradientDrawable.setStroke 的圆角描边按钮/输入框）。 */
    static Drawable squircle(Context c, int color, float rDp, int strokeColor, float strokeDp) {
        return new SquircleDrawable(color, dp(c, rDp), false, strokeColor,
                Math.max(1f, dp(c, strokeDp)), !DesignSpec.of(c).squircle);
    }

    /** 仅上方两角做 squircle（底部 sheet 用，下方贴屏不圆） */
    static Drawable squircleTop(int color, float rDp) {
        return new SquircleDrawable(color, rDp * 3.2f, true, 0, 0);
    }

    /** 弹层底板：按语言曲率 + 按锚点决定「四角圆」还是「仅上两角圆」 */
    static Drawable panelBg(Context c, int color, float rDp, boolean topOnly) {
        return new SquircleDrawable(color, dp(c, rDp), topOnly, 0, 0,
                !DesignSpec.of(c).squircle);
    }

    /**
     * 页面底衬：斜向渐变 + 几团彩色光斑（见 {@link ModouBackdrop}）。
     *
     * 这是「有光影」和「平涂」的分界：整屏不再是 {@code setBackgroundColor(ui.bg)} 的单色，
     * 而是带光源方向的底色，卡片浮在这层光上才成立。挂在最外层容器上，
     * 内层凡是 {@code setBackgroundColor(ui.bg)} 的地方都要改成透明，否则会把光斑盖死。
     */
    /**
     * 浮岛压在内容上，页面/列表底部要留出的高度（px）。
     *
     * <p>以前这里是各页自己写死的数：书架用 {@code dockFoot()}（76dp）、设置/书源/规则
     * 三页各写 {@code p + 96dp}，设置页的根容器底下还多 20dp。于是同样滚到底，
     * 设置页底部露出的空白比别的页高出一大截，看着就是「只有设置页底下多一条灰带」。
     * 现在四处共用这一个值。
     */
    static int dockFootPx(Context c) {
        boolean land = c.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        // 0.9.158：高度由设计语言给出（墨读 76 = 6+50+8+12；M3 80；iOS 49）。
        // 横屏浮岛浮在内容中间，额外再垫 24。
        return dp(c, DesignSpec.of(c).navFootDp + (land ? 24 : 0));
    }

    static Drawable backdrop(Context c) {
        DesignSpec sp = DesignSpec.of(c);
        // 墨读才有那层渐变底衬；M3 / iOS 都是干净的纯色 system/surface 底
        return sp.gradientBackdrop ? ModouBackdrop.of(c) : new ColorDrawable(sp.bg);
    }

    /**
     * 冷调玻璃卡：半透明基色 + 顶亮底次亮的边缘泛光，<b>不做背板模糊</b>。
     *
     * 与 {@link #glassTop} 的区别就是「要不要抓屏」：卡片是列表里成排出现的，
     * 每张都抓一次背板会直接把滚动手感打没，所以卡片只做「透」+「边光」；
     * 真正需要模糊的是浮在内容之上的面板（sheet），那种一屏最多一个。
     */
    static Drawable glass(Context c, float rDp) {
        DesignSpec sp = DesignSpec.of(c);
        if (sp.material == DesignSpec.Material.TONAL) {
            // M3：不透明填充容器，靠 tonal 层级而不是玻璃表达层级
            GradientDrawable g = new GradientDrawable();
            g.setColor(sp.surfaceLow);
            g.setCornerRadius(dp(c, rDp));
            return g;
        }
        ModouGlassDrawable g = new ModouGlassDrawable(sp.glassBase, dp(c, rDp), false);
        g.setVeil(sp.glassVeil);
        g.setRimWidth(dp(c, 1));
        return g;
    }

    /**
     * 0.9.169 · 搜索框背景三形态（Stage 4 第 7 部分）。
     *
     * <p>搜索框是三套设计语言各自都有强规范的组件，此前却三套共用 {@code glass(999)}：
     * <ul>
     *   <li>style 0 墨读：原样返回 {@code glass(c, 999)} 玻璃胶囊（半透明白 + 白高光边）；</li>
     *   <li>style 1 iOS 26：{@code tertiarySystemFill} 的 10dp 连续曲率圆角矩形
     *       （{@link DesignSpec#searchBarCornerDp}），矮而收敛，无描边、无玻璃；</li>
     *   <li>style 2 M3 Expressive：{@code surfaceContainerHigh} 全圆胶囊（28dp），
     *       靠 tonal 层级 + elevation 表达，不用玻璃。</li>
     * </ul>
     * 颜色一律取 {@code DesignSpec} 派生色板（深浅自动适配），不写死 ARGB，也不写
     * {@code if (lang == ...)}。圆角半径 / 高度 / icon / elevation 由调用方读同一组
     * {@code search*} 规格，改规格即三套一起变。
     */
    static Drawable searchBarBg(Context c) {
        return searchBarBg(c, false);
    }

    /**
     * 0.9.170 焦点态重载：iOS 26 的 UISearchBar 聚焦时灰底会从 systemFill 3 档
     * 升到 4 档（略深一点），M3 search bar 聚焦时底色从 {@code surfaceContainerHigh}
     * 升到 {@code Highest}（与 elevation 3→6dp 的 tonal 升一级配套）。墨读仍是
     * 玻璃胶囊，{@code focused} 对它无效。颜色全部读派生色板，深浅自动适配。
     */
    static Drawable searchBarBg(Context c, boolean focused) {
        DesignSpec sp = DesignSpec.of(c);
        switch (sp.searchBarStyle) {
            case 1:  // iOS 26 search field
                return squircle(c, focused ? sp.sysFill4 : sp.sysFill3, sp.searchBarCornerDp);
            case 2:  // M3 search bar
                return squircle(c, focused ? sp.surfaceContainerHighest : sp.surfaceContainerHigh,
                        sp.searchBarCornerDp);
            case 0:  // 墨读玻璃胶囊
            default:
                return glass(c, 999);
        }
    }

    /** 玻璃胶囊（分段控件、chip 的选中态） */
    static Drawable glassPill(Context c) {
        DesignSpec sp = DesignSpec.of(c);
        if (sp.material == DesignSpec.Material.TONAL) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(sp.container);
            g.setCornerRadius(dp(c, 999));
            return g;
        }
        ModouGlassDrawable g = new ModouGlassDrawable(
                sp.glassBase, dp(c, 99), false);
        g.setVeil(sp.glassVeil);
        g.setRimWidth(Math.max(1.2f, dp(c, 1f)));
        return g;
    }

    /**
     * 液态玻璃卡片：半透明材质 + 顶亮底次亮的边缘泛光，营造 COUI17 的「凝光」通透感。
     *
     * 泛光分布照搬 coui-blur 的 BloomStroke：主光在上（强度 0.3）、副光在下
     * （0.16），所以描边「上亮 → 中间几乎消失 → 底部微亮」，而不是一圈等亮的灰线。
     *
     * <p>0.9.138：不再有背板位图，也不再抓屏。玻璃只由「半透明材质 + 薄纱 + 高光」组成；
     * 需要实时透出背后内容的面板，由独立窗口的 {@link ModouGlass#attach} 挂系统背景模糊。
     */
    static ModouGlassDrawable glassTop(int color, float rDp, int stroke) {
        ModouGlassDrawable g = new ModouGlassDrawable(color, rDp * 3.2f, true);
        g.setRimWidth(stroke);
        return g;
    }

    /** 连续曲率圆角 Drawable：四角（或全部/仅上）用超椭圆曲线 */
    private static final class SquircleDrawable extends Drawable {
        private final Path path = new Path();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        private float rPx;
        private final boolean topOnly;
        private final int strokeColor;   // 0 = 无描边（纯玻璃高光用）
        private final float strokePx;
        /** true = 普通圆弧（M3）；false = 连续曲率超椭圆（墨读 / iOS） */
        private final boolean arc;

        SquircleDrawable(int color, float rPx, boolean topOnly, int strokeColor, float strokePx) {
            this(color, rPx, topOnly, strokeColor, strokePx, false);
        }

        SquircleDrawable(int color, float rPx, boolean topOnly, int strokeColor,
                         float strokePx, boolean arc) {
            this.color = color;
            this.rPx = rPx;
            this.topOnly = topOnly;
            this.strokeColor = strokeColor;
            this.strokePx = strokePx;
            this.arc = arc;
        }

        void setColor(int c) {
            color = c;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect r) {
            build(r);
        }

        private final RectF rf = new RectF();

        private void build(android.graphics.Rect r) {
            rf.set(r);
            if (arc) {
                // M3 的 shape scale 是标准圆弧，不是连续曲率：用 Path 的 roundRect，
                // topOnly 时下面两角为 0（贴底 sheet 的下沿不能是圆的）
                path.reset();
                float rr = Math.max(0f, rPx);
                if (topOnly) {
                    path.addRoundRect(rf, new float[]{rr, rr, rr, rr, 0f, 0f, 0f, 0f},
                            Path.Direction.CW);
                } else {
                    path.addRoundRect(rf, rr, rr, Path.Direction.CW);
                }
            } else {
                Path p = topOnly
                        ? ModouShape.rectTop(rf, rPx, true)
                        : ModouShape.rect(rf, rPx, true);
                path.set(p);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            if (getBounds().width() <= 0 || getBounds().height() <= 0) return;
            if (strokePx > 0 && strokeColor != 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(strokePx);
                p.setColor(strokeColor);
                canvas.drawPath(path, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            canvas.drawPath(path, p);
        }

        @Override
        public void setAlpha(int alpha) {
            p.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            p.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return color >>> 24 == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
        }

        /**
         * 让 {@code setClipToOutline(true)} 能按超椭圆裁子 View（封面图、卡片内容）。
         *
         * 不实现这个方法的话，把背景换成 squircle 之后，clipToOutline 拿到的是空
         * outline —— 图片会直接溢出成直角，比原来的圆弧还难看。
         *
         * API 30+ 用 {@link Outline#setPath}，轮廓与填充完全一致；26–29 只有
         * setRoundRect，退化成圆弧（10dp 半径下差 2~3px，肉眼几乎看不出）。
         */
        @Override
        public void getOutline(android.graphics.Outline o) {
            android.graphics.Rect b = getBounds();
            if (b.isEmpty()) return;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    o.setPath(path);
                    return;
                } catch (Throwable ignore) {
                    /* 某些 ROM 不接受非标准路径：走下面的圆角兜底 */
                }
            }
            o.setRoundRect(b, Math.min(rPx, Math.min(b.width(), b.height()) / 2f));
        }
    }

    /** 卡片：按语言曲率圆角 + 内边距 + 投影 */
    LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(squircle(c, card, spec.cornerCard));
        softShadow(l, 2);
        int p = dp(c, spec.panelPadH);
        l.setPadding(p, p, p, p);
        return l;
    }

    /* ---------- Inset Grouped 组列表 ----------
     * 网页版（iOS 风格）的分组列表是「一个圆角组容器 + 紧贴的行 + 行间细分隔线」；
     * 之前原生页把每行做成独立灰块卡片堆起来，行距稀疏、灰底糊成一片，
     * 和网页版一比就像没设计过。统一改用这套组件。
     */

    /** 0.9.167：组卡圆角单独给开关，M3 Expressive 更圆、iOS grouped 更收敛。
     *  0 表示沿用旧的 cornerCard（墨读逐像素不变）。所有读取圆角的地方统一走这里，
     *  避免组卡、同心行、按压 mask 三处用了不同半径。 */
    private static int groupCorner(DesignSpec sp) {
        return sp.listCardCornerDp > 0 ? sp.listCardCornerDp : sp.cornerCard;
    }

    /** 组容器：按语言曲率圆角，内部不加 padding（行自己留白） */
    LinearLayout groupCard(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(squircle(c, card, groupCorner(spec)));
        // 0.9.167：组卡圆角全局按语言升级（M3 Expressive 更圆 / iOS grouped 更收敛）。
        // 左右内缩不放在这里 —— 全工程 47 处 groupCard 调用点，父容器有 LinearLayout /
        // FrameLayout / 弹层内紧凑卡片，统一加 margin 会让很多页面和顶部工具条错位。
        // inset 由「接了图标的样板页」在内容容器上给 padding 实现，见 SettingsPanel.paint()。
        // M3 靠 elevation、iOS 靠分组留白（0dp），只有墨读有那圈柔光
        softShadow(l, 2);
        if (spec.concentric) {
            // B2（0.9.163）：iOS 26 同心圆角。组内首行只圆上两角、末行只圆下两角、
            // 中间行直角，半径 = 外卡圆角 − 内边距（等差数列）。用层级监听在每次
            // addView 后重算，所有页面的 groupCard 调用点零改动即自动获得同心分组表格。
            l.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) {
                    settleConcentric((LinearLayout) parent, c);
                }
                @Override public void onChildViewRemoved(View parent, View child) {
                    settleConcentric((LinearLayout) parent, c);
                }
            });
        }
        return l;
    }

    /** 重算一组的同心圆角：只处理可点行（groupRow 返回的 LinearLayout），分隔线是普通 View。 */
    private static void settleConcentric(LinearLayout card, Context c) {
        DesignSpec sp = DesignSpec.of(c);
        float r = concentric(sp, groupCorner(sp));
        int first = -1, last = -1, n = 0;
        for (int i = 0; i < card.getChildCount(); i++) {
            View v = card.getChildAt(i);
            if (v instanceof LinearLayout && v.isClickable()) {
                if (first < 0) first = i;
                last = i;
                n++;
            }
        }
        if (n == 0) return;
        for (int i = 0; i < card.getChildCount(); i++) {
            View v = card.getChildAt(i);
            if (!(v instanceof LinearLayout) || !v.isClickable()) continue;
            float tl = 0, tr = 0, br = 0, bl = 0;
            if (n == 1) { tl = tr = br = bl = r; }
            else if (i == first) { tl = tr = r; }
            else if (i == last) { br = bl = r; }
            // GradientDrawable.setCornerRadii 收 px：左上(x,y) 右上 右下 左下
            float[] radii = new float[]{dp(c, tl), dp(c, tl), dp(c, tr), dp(c, tr),
                    dp(c, br), dp(c, br), dp(c, bl), dp(c, bl)};
            v.setBackground(concentricRowBg(sp, radii));
        }
    }

    /** 同心行按压：state layer 叠加（无涟漪外扩），四角半径按行位置给。 */
    private static Drawable concentricRowBg(DesignSpec sp, float[] radiiPx) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x00000000);
        normal.setCornerRadii(radiiPx);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(sp.statePressed);
        pressed.setCornerRadii(radiiPx);
        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{android.R.attr.state_focused}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /**
     * 柔和投影：默认的系统阴影边缘发硬、颜色偏黑，浅色卡片上像一道脏边。
     * API 28+ 可以把环境光/点光阴影染成极淡的墨色（10%），阴影就变成一圈柔光；
     * 低版本忽略，靠 elevation 兜底。
     */
    static void softShadow(View v, int dpElevation) {
        // 0.9.158：投影强度由语言决定（墨读 2dp 柔光 / M3 1dp / iOS 0）。
        // 保留旧参数只为兼容调用点，实际取值走规格表。
        float e = DesignSpec.of(v.getContext()).cardElevationDp;
        v.setElevation(e > 0f ? dp(v.getContext(), e) : 0f);
        if (e > 0f && android.os.Build.VERSION.SDK_INT >= 28) {
            v.setOutlineAmbientShadowColor(0x1A101A29);
            v.setOutlineSpotShadowColor(0x1A101A29);
        }
    }

    /**
     * 组行：左「标题 + 说明」竖排、右值；onClick 非空则整行可点（按压态走 ripple，
     * 不再给行铺常驻灰底——灰底一多整个组看起来就是一坨）。
     */
    /* 0.9.167：行内关键 TextView 用 tag 暴露，取代调用点对 groupRow 返回值的
     * getChildAt(0) / getChildAt(1) 反射。加了 leading 图标后 child 下标会整体右移，
     * 原来靠下标取标题改色、取值刷新的两处都会崩或取错——统一走访问器。 */
    private static final String TAG_ROW_TITLE = "modou.row.title";
    private static final String TAG_ROW_VALUE = "modou.row.value";

    static TextView rowTitle(View row) {
        return row == null ? null : (TextView) row.findViewWithTag(TAG_ROW_TITLE);
    }

    static TextView rowValue(View row) {
        return row == null ? null : (TextView) row.findViewWithTag(TAG_ROW_VALUE);
    }

    LinearLayout groupRow(Context c, String title, String value, String hint, Runnable onClick) {
        return rowImpl(c, title, value, hint, 0, 0, onClick);
    }

    /** 同上；titleColor 非 0 时覆盖标题色（危险项用 danger、选中项用 accent） */
    LinearLayout groupRow(Context c, String title, String value, String hint,
                          int titleColor, Runnable onClick) {
        return rowImpl(c, title, value, hint, 0, titleColor, onClick);
    }

    /** 0.9.167 · 带 leading 图标的列表行（三语言构图差异的入口）。 */
    LinearLayout iconRow(Context c, int iconKey, String title, String value, String hint,
                         Runnable onClick) {
        return rowImpl(c, title, value, hint, iconKey, 0, onClick);
    }

    LinearLayout iconRow(Context c, int iconKey, String title, String value, String hint,
                         int titleColor, Runnable onClick) {
        return rowImpl(c, title, value, hint, iconKey, titleColor, onClick);
    }

    private LinearLayout rowImpl(Context c, String title, String value, String hint,
                                 int iconKey, int titleColor, Runnable onClick) {
        ModouUi u = of(c);
        DesignSpec sp = spec;
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(c, sp.rowPadH), dp(c, sp.rowPadV),
                dp(c, sp.rowPadH), dp(c, sp.rowPadV));
        // 带图标行按语言给更高最小行高；不带图标 / 墨读沿用旧的 rowMinHeight
        int minH = (iconKey != 0 && sp.listRowMinHDp > 0) ? sp.listRowMinHDp : sp.rowMinHeight;
        if (minH > 0) l.setMinimumHeight(dp(c, minH));

        // leading 图标容器：墨读 / iconKey=0 时 ListIcon.create 返回 null，一行都不加
        View lead = ListIcon.create(c, u, iconKey);
        if (lead != null) l.addView(lead);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rt = tv(c, title, sp.rowTitleSp, titleColor != 0 ? titleColor : u.text);
        rt.setLetterSpacing(sp.rowTitleTracking);          // A8：M3 bodyLarge / iOS body 字距
        rt.setTag(TAG_ROW_TITLE);
        left.addView(rt);
        if (hint != null && !hint.isEmpty()) {
            TextView s = tv(c, hint, sp.rowHintSp, u.sub);
            s.setLetterSpacing(sp.rowHintTracking);
            s.setPadding(0, dp(c, 2), 0, 0);
            left.addView(s);
        }
        l.addView(left);

        if (value != null && !value.isEmpty()) {
            TextView v = tv(c, value, sp.rowHintSp + 1.5f, u.sub);
            v.setLetterSpacing(sp.rowHintTracking);
            v.setPadding(dp(c, 10), 0, 0, 0);
            v.setTag(TAG_ROW_VALUE);
            l.addView(v);
        }

        // iOS grouped list 的 disclosure ›；M3 list item 不画（规格里 listChevron=false）
        if (sp.listChevron && onClick != null) {
            TextView ch = tv(c, "›", sp.listChevronSp, alpha(u.text, 0x4D));
            ch.setIncludeFontPadding(false);
            ch.setPadding(dp(c, 8), 0, 0, 0);
            l.addView(ch);
        }

        if (onClick != null) {
            l.setOnClickListener((x) -> onClick.run());
            // 必须给 mask：RippleDrawable(csl, null, null) 是「无边界水波纹」，
            // 长按行时会从触点炸开一个半径按屏幕对角线算的大圆，溢出整张卡片
            // —— 设置页中间那团半透明大圆就是这么来的。mask 有边界 + 圆角，
            // 涟漪就被限制在这一行内，并按卡片圆角裁掉四角。
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(dp(c, groupCorner(sp)));
            l.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(alpha(u.text, 0x14)),
                    null, mask));
            // 注意：这里**不能** setClipToOutline(true)。
            // clipToOutline 用的是背景 drawable 的 Outline，而 RippleDrawable 的边界
            // 只有到绘制那一刻才被赋上；布局阶段拿到的是空 Outline → 整行被裁成 0，
            // 文字全部消失、只剩行高和分隔线（设置页「只剩几条横线」就是这么来的）。
            // 涟漪的裁剪本来由 mask 负责，不需要 View 再裁一次。
        }
        return l;
    }

    /** 组内行间分隔线：颜色 / 厚度 / 缩进都由语言规格给出。
     *  未接图标的旧组走这里，行为与 0.9.166 完全一致。 */
    static View groupDivider(Context c) {
        return groupDivider(c, false);
    }

    /**
     * 0.9.167 · iconized=true 表示这一组已接 leading 图标，才应用「语言级分隔线构图」：
     * iOS 从图标之后起画缩进细线；M3 干脆不画线（list item 靠 surface 层级区分）。
     * 全工程 47 处 groupCard 调用点里多数是书源名 / 规则名这类动态项和弹层内紧凑行，
     * 尚未接图标；若全局去掉分隔线，那些页面会糊成一片 —— 所以严格按组区分。
     * 墨读 listIconStyle=0，styled 恒为 false，逐像素零回归。
     */
    static View groupDivider(Context c, boolean iconized) {
        ModouUi u = of(c);
        DesignSpec sp = u.spec;
        View v = new View(c);
        boolean styled = iconized && sp.listIconStyle != 0;
        if (styled && sp.listNoDivider) {
            // 不能返回 null：所有 addView(groupDivider(...)) 调用点都还在；
            // 返回 0 高度 View 让调用点零改动，同心圆角的首末行判定也不受影响。
            v.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0));
            return v;
        }
        v.setBackgroundColor(sp.divider);
        // iOS 分隔线从 leading 图标之后起（不是贴着卡片左边），这是 grouped list 的关键细节
        int start = (styled && sp.listDividerStartDp > 0)
                ? sp.listDividerStartDp : sp.dividerInset;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, sp.dividerDp)));
        lp.leftMargin = dp(c, start);
        lp.rightMargin = dp(c, sp.dividerInset);
        v.setLayoutParams(lp);
        return v;
    }

    /** 组标题：组容器**外**上方的小字，字号/字重/上方间距按语言 */
    static TextView groupTitle(Context c, String text) {
        ModouUi u = of(c);
        DesignSpec sp = u.spec;
        int color = sp.material == DesignSpec.Material.TONAL ? u.accent : u.sub;
        TextView t = tv(c, text, sp.groupTitleSp, color);
        // 0.9.163 A8：字重与字距改从 15 档字阶表派生（M3 titleSmall 500/0.007em、
        // iOS footnote 13sp/0.006em、墨读 15sp 粗体），不再按 material 写死分支。
        applyType(t, sp.groupTitleWeight, sp.groupTitleTracking,
                android.graphics.Typeface.DEFAULT);
        t.setAllCaps(false);
        t.setPadding(dp(c, 8), dp(c, sp.groupGap), dp(c, 8), dp(c, 8));
        // 0.9.167：左右内缩不在这里给 margin —— 全工程 47 处 groupCard 调用点的
        // 父容器形态各异，统一加 margin 会让未接图标的页面与顶部工具条错位。
        // 内缩由「接了图标的样板页」在内容容器上给 padding，一次管住标题 + 所有组卡。
        return t;
    }

    /** 卡片标题行：主标题 + 右侧灰色小字说明 */
    LinearLayout headRow(Context c, String title, String hint) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(c, 8));
        TextView h = tv(c, title, spec.rowTitleSp, text);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        h.setLetterSpacing(spec.rowTitleTracking);   // A8：卡片标题也吃字阶字距
        row.addView(h);
        if (hint != null && !hint.isEmpty()) {
            TextView s = tv(c, hint, spec.rowHintSp, sub);
            s.setLetterSpacing(spec.rowHintTracking);
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
        DesignSpec sp = spec;
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(c, sp.rowPadH), padV = dp(c, sp.rowPadV);
        l.setPadding(padH, padV, padH, padV);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rt = tv(c, title, sp.rowTitleSp, u.text);
        rt.setLetterSpacing(sp.rowTitleTracking);          // A8：M3 bodyLarge 0.031em
        left.addView(rt);
        if (hint != null && !hint.isEmpty()) {
            TextView s = tv(c, hint, sp.rowHintSp, u.sub);
            s.setLetterSpacing(sp.rowHintTracking);
            s.setPadding(0, dp(c, 3), 0, 0);
            left.addView(s);
        }
        l.addView(left);

        if (value != null) {
            TextView v = tv(c, value, sp.rowHintSp + 1.5f, u.accent);
            v.setLetterSpacing(sp.rowHintTracking);
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
        g.setColor(u.spec.material == DesignSpec.Material.TONAL
                ? u.spec.container : (u.dark ? 0x1AFFFFFF : 0x0F000000));
        g.setCornerRadius(dp(c, u.spec.cornerS));
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
    /**
     * Dialog / alert 的 supporting text（0.9.162）：
     * M3 bodyMedium 14sp 左对齐、iOS alert 13sp 居中、墨读沿用 13.5sp 左对齐（零回归）。
     * 字号与对齐都只读 DesignSpec，不写语言分支。
     */
    private static TextView dialogBody(Activity a, ModouUi u, String msg) {
        DesignSpec sp = u.spec;
        boolean isDialog = sp.confirmAnchor == DesignSpec.SheetAnchor.CENTER;
        float size = isDialog ? sp.dialogSupportSp : 13.5f;
        TextView t = tv(a, msg, size, u.sub);
        t.setLineSpacing(dp(a, 3), 1.3f);
        if (isDialog && sp.dialogTitleCenter) {
            t.setGravity(Gravity.CENTER);          // iOS alert 正文居中
            t.setPadding(dp(a, 8), dp(a, 2), dp(a, 8), dp(a, 2));
        }
        return t;
    }

    static void confirm(Context c, String title, String msg, Runnable ok) {
        if (c instanceof Activity) {
            Activity a = (Activity) c;
            try {
                ModouUi u = of(a);
                LinearLayout box = new LinearLayout(a);
                box.setOrientation(LinearLayout.VERTICAL);
                // M3/iOS 居中弹层：内边距由 panel 的 panelPadH 规格统一管，box 不再叠 14dp；
                // 墨读浮岛保留历史 14dp（零回归）。
                boolean centeredDlg = u.spec.confirmAnchor == DesignSpec.SheetAnchor.CENTER;
                box.setPadding(centeredDlg ? 0 : dp(a, 14), dp(a, 4),
                        centeredDlg ? 0 : dp(a, 14), 0);
                box.addView(dialogBody(a, u, msg));
                // 确认/警示的锚点跟着语言走：墨读底部浮岛、M3 居中 Dialog、iOS 居中 alert。
                // 显式传 CONFIRM 语义：让 sheetImpl 走 Dialog 标题区 / 派生容器色 /
                // tonal elevation / fade+scale 入场（iOS 走 alert 正文居中 / 无把手）。
                Dialog d = sheetAnchored(a, title, box, u.spec.confirmAnchor,
                        DesignSpec.SheetSemantic.CONFIRM);
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

    /**
     * 粗圆角进度条：系统 progressBarStyleHorizontal 只有 2~3px 高，横在磨砂面板里像一根头发丝。
     * 统一改成 10dp 高、5dp 圆角（= 高度一半，标准胶囊）、轨道用浅墨色的自绘 drawable。
     */
    static void thickBar(android.widget.ProgressBar pb, int accent, int track) {
        Context c = pb.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(track);
        bg.setCornerRadius(dp(c, 5));
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(accent);
        fg.setCornerRadius(dp(c, 5));
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fg, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{bg, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        pb.setProgressDrawable(ld);
        pb.setMinimumHeight(dp(c, 10));
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

    /**
     * 柔和强调色：ColorOS / 莫兰迪那种「带灰调」的低饱和蓝。
     *
     * <p>开关、分段控件这类大面积色块，直接用 UI accent（#2F6BF0）会亮得像系统控件；
     * 掺 30% 灰蓝之后仍能看出是「开」，但不再抢视线。文字链接、进度条等小面积
     * 强调仍用原 accent。
     */
    static int toneAccent(Context c) {
        ModouUi u = of(c);
        // M3 / iOS 的主色本来就是设计好的系统色，不掺灰；只有墨读需要「莫兰迪化」
        if (u.spec.material != DesignSpec.Material.GLASS) return u.accent;
        return u.dark ? mix(u.accent, 0xFFA9B8CC, 0.14f)
                      : mix(u.accent, 0xFF8E9CB2, 0.32f);
    }

    /**
     * 细线图标按钮：圆形、8% 墨色底、深灰线性图标。
     *
     * <p>替代原来「一块强调色方块 + 22sp 字符加号」的 Material 式按钮：
     * 底几乎没有颜色，只有图标本身在说话。
     */
    static ImageView glyphBtn(Context c, Drawable glyph, int boxDp) {
        ModouUi u = of(c);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageDrawable(glyph);
        int b = dp(c, boxDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(b, b));
        // 与书架页右上角两颗钮完全同一套底：浅色纯白、深色 #1A1A1A。
        // 之前浅色只给 6% 墨色，落在灰页上几乎是无底裸图标，和书架那两个白的不是一类东西。
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(plateColor(u));
        bg.setCornerRadius(dp(c, 999));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(c, 999));
        iv.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        u.dark ? 0x26FFFFFF : 0x14000000), bg, mask));
        iv.setClickable(true);
        iv.setFocusable(true);
        return iv;
    }

    /* ---------------- 顶栏图标（全局唯一规格） ---------------- */

    /** 顶栏字形：19dp；线宽 1.8dp —— 书架、书源、搜索、详情、净化共用同一档 */
    static final float TOP_GLYPH_DP = 19f;
    static final float TOP_STROKE_DP = 1.8f;

    static ImageView topBtn(Context c, int glyph, View.OnClickListener l) {
        return topBtn(c, glyph, 40, 0, l);
    }

    static ImageView topBtn(Context c, int glyph, int boxDp, View.OnClickListener l) {
        return topBtn(c, glyph, boxDp, 0, l);
    }

    /**
     * 右上角圆形图标钮：全局唯一实现。
     *
     * <p>以前每页各写一份：书架 25dp 字形 / 1.9dp 线宽、书源 15dp / 1.6dp、
     * 书籍详情干脆用横三点 —— 同一竖排四个页面像三代产品。现在尺寸、线宽、
     * 圆底、图标种类全部收口到这里，调用方只给种类。
     *
     * @param boxDp 圆底直径；40 为标准，横屏 34（字形自动降 2dp）
     * @param color 0 = 跟随主题文字色（深色下自动提亮）
     */
    static ImageView topBtn(Context c, int glyph, int boxDp, int color, View.OnClickListener l) {
        ModouUi u = of(c);
        ImageView iv = new ImageView(c);
        int b = dp(c, boxDp);
        iv.setLayoutParams(new LinearLayout.LayoutParams(b, b));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        int fg = color != 0 ? color : (u.dark ? 0xFFE6E7E9 : u.text);
        float gs = boxDp < 40 ? TOP_GLYPH_DP - 2f : TOP_GLYPH_DP;
        iv.setImageDrawable(new ModouGlyph(glyph, fg, dp(c, gs), dp(c, TOP_STROKE_DP)));
        // 底板按语言：墨读纯白/深灰圆钮、M3 surface 容器圆钮、iOS 淡蓝 tint 圆钮
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(plateColor(u));
        bg.setCornerRadius(b / 2f);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(b / 2f);
        iv.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        u.dark ? 0x26FFFFFF : 0x14000000), bg, mask));
        iv.setOnClickListener(l);
        iv.setClickable(true);
        iv.setFocusable(true);
        return iv;
    }

    /** 顶栏圆钮 / 图标按钮的底板色：三套语言各自的层级表达 */
    static int plateColor(ModouUi u) {
        DesignSpec sp = u.spec;
        if (sp.material == DesignSpec.Material.TONAL) return sp.surfaceLow;
        if (sp.material == DesignSpec.Material.LIQUID) return sp.container;
        return u.dark ? 0xFF1A1A1A : 0xFFFFFFFF;
    }

    static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /* ---------------- 分段控件（全站唯一实现） ---------------- */

    /**
     * 三段 / 多段选择器：三套语言各自的分段控件形态。
     *
     * <p>墨读：全圆胶囊槽 + 全圆滑块（悬浮感）；M3 Segmented button：圆角 8dp 外框 +
     * primaryContainer 选中块；iOS Segmented Control：圆角 9dp 槽 + 白色选中片带投影。
     *
     * <p>设置页的「设计语言」与「外观深浅」共用它，以后调分段控件只改这一处。
     */
    static LinearLayout segment(Context c, String[] labels, int selected, IntConsumer onPick) {
        ModouUi u = of(c);
        DesignSpec sp = u.spec;
        boolean tonal = sp.material == DesignSpec.Material.TONAL;
        boolean liquid = sp.material == DesignSpec.Material.LIQUID;
        int radius = tonal ? 8 : liquid ? 9 : 999;

        LinearLayout seg = new LinearLayout(c);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setClipChildren(false);
        seg.setClipToPadding(false);
        GradientDrawable track = new GradientDrawable();
        track.setColor(tonal ? sp.surfaceLow : alpha(u.text, 0x12));
        track.setCornerRadius(dp(c, radius));
        if (tonal) track.setStroke(Math.max(1, dp(c, 1)), sp.line);
        seg.setBackground(track);
        int pad = dp(c, 3);
        seg.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == selected;
            int fg = on ? (tonal ? sp.onContainer : liquid ? u.text : 0xFFFFFFFF) : u.text;
            TextView t = tv(c, labels[i], liquid ? 13f : 13.5f, fg);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            if (on) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(tonal ? sp.container : liquid ? sp.surface : toneAccent(c));
                g.setCornerRadius(dp(c, radialCorner(sp, radius)));
                t.setBackground(g);
                t.setElevation(dp(c, liquid ? 1 : 2));
            }
            t.setOnClickListener(v -> {
                if (onPick != null) onPick.accept(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(c, 36), 1f);
            if (i > 0) lp.leftMargin = dp(c, 3);
            seg.addView(t, lp);
        }
        return seg;
    }

    private static int radialCorner(DesignSpec sp, int trackRadius) {
        if (sp.material == DesignSpec.Material.GLASS) return 999;
        return Math.max(2, trackRadius - 2);
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
    /**
     * 表单 sheet：带输入框的弹层（编辑信息、编辑章节、导入书源、规则）。
     *
     * <p>横屏下普通 sheet 是 40% 屏宽（列表行短一点更好扫），但表单塞三四个输入框
     * 就会被挤成一条细缝 —— 所以表单单独走 56%、上限 600dp。
     */
    static Dialog sheetForm(Activity a, String title, LinearLayout content) {
        return sheetForm(a, title, content, null);
    }

    static Dialog sheetForm(Activity a, String title, LinearLayout content, LinearLayout footer) {
        return sheetImpl(a, title, content, footer, true, false, null);
    }

    /** 紧凑 sheet：纯列表面板（换源等）用 —— 宽度回到 320dp、限高更矮，不再铺满屏。 */
    static Dialog sheetCompact(Activity a, String title, LinearLayout content) {
        return sheetImpl(a, title, content, null, false, true, null);
    }

    static Dialog sheet(Activity a, String title, LinearLayout content, LinearLayout footer) {
        return sheetImpl(a, title, content, footer, false, false, null);
    }

    /**
     * 指定锚点的弹层（0.9.158）：确认/警示走居中 Dialog/alert、纯列表走 Action Sheet，
     * 由 {@link DesignSpec} 的 confirmAnchor / listAnchor 决定，调用点不必判断语言。
     */
    static Dialog sheetAnchored(Activity a, String title, LinearLayout content,
                                DesignSpec.SheetAnchor anchor) {
        return sheetImpl(a, title, content, null, false, false, anchor,
                DesignSpec.SheetSemantic.PANEL);
    }

    /** 带语义的锚定弹层（0.9.162）：confirm/alert/sheetList 显式传 CONFIRM/ALERT/LIST，
     *  让 sheetImpl 在标题区、内容对齐、把手、入场动画上按语言做完整分流。 */
    static Dialog sheetAnchored(Activity a, String title, LinearLayout content,
                                DesignSpec.SheetAnchor anchor, DesignSpec.SheetSemantic sem) {
        return sheetImpl(a, title, content, null, false, false, anchor, sem);
    }

    /** 所有既有调用点保持零改动：默认 PANEL 语义。 */
    private static Dialog sheetImpl(Activity a, String title, LinearLayout content,
                                    LinearLayout footer, boolean wideForm, boolean compact,
                                    DesignSpec.SheetAnchor forced) {
        return sheetImpl(a, title, content, footer, wideForm, compact, forced,
                DesignSpec.SheetSemantic.PANEL);
    }

    private static Dialog sheetImpl(Activity a, String title, LinearLayout content,
                                    LinearLayout footer, boolean wideForm, boolean compact,
                                    DesignSpec.SheetAnchor forced,
                                    DesignSpec.SheetSemantic sem) {
        ModouUi u = of(a);
        final DesignSpec sp = u.spec;
        final DesignSpec.SheetAnchor anchor = forced != null ? forced : sp.sheetAnchor;
        final boolean centered = anchor == DesignSpec.SheetAnchor.CENTER;
        final boolean inset = anchor == DesignSpec.SheetAnchor.BOTTOM_INSET;
        // Stage 3 语义判定：iOS Action Sheet（LIST + 独立取消组）由 sheetList 自己画两个
        // 分组圆角卡，所以要跳过整块 panel 背景/把手/标题/内边距；窗口骨架一行业不动。
        final boolean actionSheet = sem == DesignSpec.SheetSemantic.LIST
                && sp.actionSheetSeparateCancel && inset;
        final boolean dialog = centered
                && (sem == DesignSpec.SheetSemantic.CONFIRM || sem == DesignSpec.SheetSemantic.ALERT);
        final boolean pagePeek = sp.pageSheetPeek
                && anchor == DesignSpec.SheetAnchor.BOTTOM_FULL
                && (sem == DesignSpec.SheetSemantic.PANEL || sem == DesignSpec.SheetSemantic.FORM);
        // 居中 alert / iOS Action Sheet 用紧凑圆角，贴底 sheet 用各语言的大圆角
        final float radius = (centered || inset) ? sp.cornerAlert : sp.sheetRadiusDp;
        // 0.9.138：sheet 本来就是独立窗口，正好满足「translucent + floating」两个硬条件。
        // 换成 Glass 主题后挂系统背景模糊，面板背后的内容每帧实时透出来；
        // 旧版套在 panel 外面的 ModouFrost 抓屏背板已整套删除。
        Dialog d = new Dialog(a, R.style.Theme_Modou_Glass);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final boolean land = a.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        // 用 FrameLayout：面板与真模糊背板叠在一起（背板由 ModouFrost 提供）
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(a);
        // 宽度一律交给窗口（见下面的 setLayout）：竖屏 320dp 居中、横屏 40% 屏宽居中。
        // 这里再垫左右边距的话，实际宽度会被削两遍，右侧永远空一条。
        // 墨读浮岛离底 10dp；M3 / iOS 的贴底与居中弹层不要底部留白（要真的贴屏底/居中）
        wrap.setPadding(0, 0, 0,
                anchor == DesignSpec.SheetAnchor.FLOAT_BOTTOM && !land ? dp(a, 10)
                        : inset ? dp(a, 8) : 0);

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        if (actionSheet) {
            // iOS Action Sheet：整块 panel 不铺底、不留内边距、四角不圆 ——
            // 由 sheetList 画「主项组 + 独立取消组」两张同心圆角卡，组间留缝。
            // 窗口仍是 BOTTOM_INSET + scrim，骨架不动。
            panel.setBackground(null);
        } else if (sp.material == DesignSpec.Material.GLASS) {
            // 墨读：浮岛只留边缘泛光 + 6% 色调，底层是 ModouFrost + 系统实时模糊，
            // 面板自己铺实色会把模糊整块盖死。
            final ModouGlassDrawable bg = new ModouGlassDrawable(
                    alpha(u.card, 0x0F), dp(a, radius), false);
            bg.setRimWidth(dp(a, 2.5f));
            panel.setBackground(bg);
        } else {
            // M3：Dialog 用派生 surfaceContainerHigh（neutral tone + primary tint，A1），
            //      再叠一层 tonal elevation；Bottom Sheet 用 surfaceHigh。
            // iOS：半透明液态玻璃基色。
            int base;
            if (sp.material == DesignSpec.Material.TONAL) {
                base = dialog ? sp.surfaceContainerHigh : sp.surfaceHigh;
            } else {
                base = sp.glassBase;
            }
            // BOTTOM_FULL 贴屏底：只圆上两角；居中/inset 四角都圆
            panel.setBackground(panelBg(a, base, radius,
                    anchor == DesignSpec.SheetAnchor.BOTTOM_FULL && !pagePeek));
            if (dialog && sp.dialogElevationDp > 0) {
                // M3 tonal elevation：一层 3% primary tint 叠在容器上（不是投影）
                GradientDrawable elev = new GradientDrawable();
                elev.setColor(alpha(sp.tonalPrimary != null && sp.tonalPrimary.length > 4
                        ? sp.tonalPrimary[4] : u.accent, 0x08));
                elev.setCornerRadius(dp(a, radius));
                panel.setForeground(elev);
                panel.setElevation(dp(a, sp.dialogElevationDp));
            }
        }
        if (!actionSheet) {
            panel.setPadding(dp(a, sp.panelPadH), dp(a, sp.panelPadT),
                    dp(a, sp.panelPadH), dp(a, sp.panelPadB));
        }

        // iOS Page Sheet：顶部露出「上一页」被压暗缩小的顶边，制造页卡堆叠（B7）。
        // 只在内容层加一条圆角装饰，不改窗口高度 / gravity / setLayout（骨架不动）。
        if (pagePeek) {
            View peek = new View(a);
            peek.setBackground(new PageSheetPeek(a, sp));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(a, sp.pageSheetPeekDp));
            plp.leftMargin = dp(a, 12);
            plp.rightMargin = dp(a, 12);
            plp.bottomMargin = dp(a, 6);
            panel.addView(peek, plp);
        }

        // 拖动目标：墨读外面还包着 FrostBox 材质层（panel 的兄弟节点），只拖 panel 会
        // 留下一块钉在原地的玻璃。这里用一格数组把外壳引用提前交给手指监听。
        final View[] shell = new View[1];
        // 把手：三种语言各有规矩 —— 墨读 40×4 可下拉关闭；M3 32×4 只是指示、不可拖；
        // iOS 36×5 grabber 可下拉关闭。外面统一包 20dp 高的透明触摸区。
        // iOS Action Sheet 没有 grabber（靠独立「取消」组关闭），居中 Dialog/alert 无把手。
        if (sp.sheetHandle && !centered && !actionSheet) {
            // M3 / iOS 的居中 Dialog/alert 没有把手，把手是贴底 sheet 的语言
            int grabW = sp.material == DesignSpec.Material.TONAL ? 32
                    : sp.material == DesignSpec.Material.LIQUID ? 36 : 40;
            int grabH = sp.material == DesignSpec.Material.LIQUID ? 5 : 4;
            View grab = new View(a);
            GradientDrawable gb = new GradientDrawable();
            gb.setColor(alpha(u.text, sp.material == DesignSpec.Material.LIQUID ? 0x33 : 0x42));
            gb.setCornerRadius(dp(a, sp.cornerXs));
            grab.setBackground(gb);
            android.widget.FrameLayout grabBox = new android.widget.FrameLayout(a);
            android.widget.FrameLayout.LayoutParams gbp =
                    new android.widget.FrameLayout.LayoutParams(dp(a, grabW), dp(a, grabH));
            gbp.gravity = Gravity.CENTER;
            grabBox.addView(grab, gbp);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 20));
            glp.bottomMargin = dp(a, 6);
            panel.addView(grabBox, glp);
            if (sp.sheetDraggable) {
                grabBox.setOnTouchListener(new View.OnTouchListener() {
                    private float downY;
                    private boolean moved;

                    private View target() {
                        return shell[0] != null ? shell[0] : panel;
                    }

                    @Override
                    public boolean onTouch(View v, android.view.MotionEvent e) {
                        switch (e.getActionMasked()) {
                            case android.view.MotionEvent.ACTION_DOWN:
                                downY = e.getRawY();
                                moved = false;
                                panel.animate().cancel();
                                target().animate().cancel();
                                return true;
                            case android.view.MotionEvent.ACTION_MOVE: {
                                float dy = e.getRawY() - downY;
                                if (dy > 0) {
                                    moved = true;
                                    target().setTranslationY(dy);
                                }
                                return true;
                            }
                            case android.view.MotionEvent.ACTION_UP:
                            case android.view.MotionEvent.ACTION_CANCEL: {
                                float dy = Math.max(0, e.getRawY() - downY);
                                View t = target();
                                if (moved && dy > dp(a, 80)) {
                                    t.animate().translationY(t.getHeight() + dp(a, 40))
                                            .alpha(0f).setDuration(sp.durMed)
                                            .withEndAction(d::dismiss).start();
                                } else {
                                    t.animate().translationY(0f).setDuration(sp.durMed).start();
                                }
                                return true;
                            }
                            default:
                                return false;
                        }
                    }
                });
            }
        }

        if (title != null && !title.isEmpty() && !actionSheet) {
            // 墨读 17sp 衬线粗体左对齐 / M3 24sp headlineSmall 常规左对齐 /
            // iOS 17sp 粗体居中。M3 Basic Dialog 规范无 hero icon（保持忠实，不画占位圆点）。
            // iOS alert 标题居中、正文居中（正文在 confirm/alert 里按语义对齐）。
            TextView t = tv(a, title, sp.sheetTitleSp, u.text);
            if (sp.sheetTitleSerif) {
                t.setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD);
            } else {
                t.setTypeface(android.graphics.Typeface.DEFAULT,
                        sp.sheetTitleWeight == 1
                                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
            boolean titleCenter = sp.sheetTitleCenter || (dialog && sp.dialogTitleCenter);
            t.setGravity(titleCenter ? Gravity.CENTER : Gravity.START);
            t.setPadding(0, 0, 0, dp(a, dialog ? 12 : 6));
            panel.addView(t);
            if (sp.sheetTitleDivider) {
                View sep = new View(a);
                sep.setBackgroundColor(sp.divider);
                panel.addView(sep, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1))));
            }
        }
        if (content != null) {
            // 分组、书源这类列表可能几十行：套一层限高滚动，别让 sheet 顶穿屏幕。
            // 注意 content 必须还没有父容器——ScollView 只能有一个直接子节点，
            // 传进来已挂过父的 view 会直接抛 IllegalStateException（编辑信息就踩过）。
            if (content.getParent() != null) {
                ((ViewGroup) content.getParent()).removeView(content);
            }
            int sh = a.getResources().getDisplayMetrics().heightPixels;
            // 横屏整块面板不超过屏幕 60%：先把把手/标题/内边距（约 76dp）扣掉，剩下的
            // 才是列表能占的高度。竖屏本来就宽裕，给 62% 只为防超长列表顶穿屏幕。
            // 竖屏限高沿用旧版 62%：0.9.138 把面板撑成整屏的根因在 ModouFrost（内容被强制
            // MATCH_PARENT），不是这里。尺寸恢复成前几个版本的样子，本轮只动玻璃不动布局。
            float ratio = land ? (wideForm ? 0.74f : 0.60f) : 0.62f;
            int chrome = dp(a, 76);
            int scrollMax = Math.max(dp(a, 96), (int) (sh * ratio) - chrome);
            MaxHeightScroll sv = new MaxHeightScroll(a, scrollMax);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.addView(sv);
        }
        if (footer != null) panel.addView(footer);
        // 0.9.138：ModouFrost 现在只补材质（遮罩 + 薄纱 + 高光），不再抓屏背板。
        // 真正的实时模糊由下面 ModouGlass.attach 挂在这个 Dialog 窗口上，由系统每帧合成。
        // 容器撑满窗口宽度、随面板高度自适应
        View panelBox;
        if (sp.sheetBlur) {
            // 墨读：独立窗口上挂系统实时模糊，FrostBox 只补玻璃材质层
            panelBox = ModouFrost.wrap(panel, radius);
        } else {
            // M3 / iOS：不挂模糊、不要玻璃材质层，面板自己就是完整底板
            panelBox = panel;
        }
        shell[0] = panelBox;   // 手指拖动时整块（背板 + 面板）一起走
        wrap.addView(panelBox, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        d.setContentView(wrap);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        if (w != null) {
            // 背景 drawable 由 ModouGlass.attach 设置：它既决定模糊区域的圆角形状，
            // 也承担模糊被系统禁用时的实底兜底。这里不要再铺一层 ColorDrawable(0)，
            // 否则系统拿不到轮廓，圆角处的模糊会退化成方角。
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(sp.scrim);
            // iOS Action Sheet 是两张分离的圆角卡：主题里 windowElevation=12dp 会沿
            // 整个矩形窗口 bounds 画一圈方形阴影。这里关掉窗口级投影，由两张分组卡
            // 各自的 Liquid Glass 描边 + 轻微 elevation 表达层次（不改窗口尺寸/位置）。
            if (actionSheet) w.setElevation(0f);
            // 弹层位置由语言规格决定：墨读底部悬浮浮岛（横屏居中）、M3/iOS 确认居中、
            // 表单/列表贴底全宽（iOS 纯列表两侧内缩 8dp）
            if (centered) {
                w.setGravity(Gravity.CENTER);
            } else if (anchor == DesignSpec.SheetAnchor.FLOAT_BOTTOM) {
                w.setGravity(land ? Gravity.CENTER
                        : (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            } else {
                w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            }
            // 面板里有输入框（编辑信息 / 自定义分组名等）：键盘弹出时整块上推，
            // 否则输入框会被键盘压住，这在旧版系统 AlertDialog 上是默认行为
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
        // A3/B9 弹层入场动画（0.9.162）：内容层做 alpha + 位移/缩放，走 DesignSpec 全局 spring。
        // 只动 panelBox 的绘制属性，不碰窗口 gravity / bounds / setLayout（骨架不动）。
        // 墨读沿用 Decelerate；M3 Dialog 是 fade+scale（不过冲），M3/iOS 贴底是上滑 spring。
        if (sp.sheetEnterAnim) {
            final View animTarget = panelBox;
            animTarget.animate().cancel();
            if (centered) {
                animTarget.setAlpha(0f);
                animTarget.setScaleX(0.92f);
                animTarget.setScaleY(0.92f);
                animTarget.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(sp.durMed)
                        .setInterpolator(DesignSpec.interpolator(sp, false, 1.6f))
                        .start();
            } else {
                animTarget.setAlpha(0f);
                animTarget.setTranslationY(dp(a, 36));
                animTarget.animate().alpha(1f).translationY(0f)
                        .setDuration(sp.durMed)
                        .setInterpolator(DesignSpec.interpolator(sp, true, 1.6f))
                        .start();
            }
        }
        // 挂系统实时背景模糊：窗口 bounds 内的下层画面由 GPU 每帧卷积，
        // 滚动、切页、键盘弹起都不需要任何抓屏或刷新调用。
        if (w != null && sp.sheetBlur) ModouGlass.attach(w, dp(a, radius));
        // setLayout 必须在 show() 之后：Dialog 在 attach 前拿不到 decor 的实际参数。
        // 宽度：竖屏 360dp 紧凑卡片、横屏列表类 ≤40% 屏宽；表单（wideForm）横屏放宽到
        // 56% / ≤600dp —— 40% 宽塞三个输入框，输入框会被挤成一条细缝。
        if (w != null) {
            final Window win = w;
            // ⚠⚠ 基准必须取 **Activity 的 decor 宽度**，绝不能取 Dialog 自己的 decor。
            //
            // Dialog 的宽度就是下面 setLayout 设的，拿它反推「窗口有多宽」会形成负反馈：
            // 第一轮 720 → 设 696；decor 变 696 → 下一轮算 672 → 设 672 …… 每次少 24dp，
            // 一路收缩到十几 dp。整张面板于是塌成屏幕中间一条竖线（0.9.119 真机翻车现场）。
            // 而 Activity 的 decor 宽度只跟窗口走，不受弹层自身宽度影响，是稳定的基准。
            final View baseView = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            final int[] appliedBase = {-1};
            final Runnable fitSheetWidth = () -> {
                int sw = baseView != null ? baseView.getWidth() : 0;
                if (sw <= 0) sw = a.getResources().getDisplayMetrics().widthPixels;
                // 基准没变就不重设：切小窗 / 旋转才需要跟着算，其余抖动一律忽略
                if (sw == appliedBase[0]) return;
                appliedBase[0] = sw;
                boolean landNow = a.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                int ww;
                switch (anchor) {
                    case CENTER:
                        // M3 Dialog / iOS alert：固定窄卡，屏幕正中
                        ww = Math.min(sw - dp(a, 32), dp(a, sp.sheetWidthDp));
                        break;
                    case BOTTOM_INSET:
                        // iOS Action Sheet：贴底、两侧各留 8dp
                        ww = sw - dp(a, sp.sheetSideMarginDp * 2);
                        break;
                    case BOTTOM_FULL:
                        // M3 Modal Bottom Sheet / iOS Page Sheet：贴底全宽，横屏限宽居中
                        ww = Math.min(sw, dp(a, sp.sheetMaxWidthDp));
                        break;
                    case FLOAT_BOTTOM:
                    default:
                        // 墨读：竖屏 320/360dp 悬浮浮岛，横屏 40% / 表单 56%
                        if (landNow) {
                            ww = wideForm
                                    ? Math.min((int) (sw * 0.56f), dp(a, 600))
                                    : Math.min((int) (sw * 0.40f), dp(a, 420));
                        } else {
                            ww = compact ? Math.min(sw - dp(a, 40), dp(a, 320))
                                         : Math.min(sw - dp(a, 24), dp(a, 360));
                        }
                        break;
                }
                if (ww > 0) win.setLayout(ww, WindowManager.LayoutParams.WRAP_CONTENT);
            };
            // 窗口尺寸会变（切小窗 / 分屏 / 旋转），而 setLayout 定的宽度不会自己跟着走：
            // 不重算就是「窗口窄了、弹层还是旧宽度」，正文溢出到卡片圆角外（实测过）。
            // 监听挂在 Dialog 的 decor 上：它随弹层销毁自动消失，不会在 Activity 上留下泄漏的回调。
            win.getDecorView().addOnLayoutChangeListener(
                    (v, l, t, r, b, ol, ot, or, ob) -> fitSheetWidth.run());
            fitSheetWidth.run();
        }

        // 背板模糊由 ModouFrost 在面板上屏后自己抓 + GPU 卷积，这里不再做 CPU 抓图
        return d;
    }

    /** 纯文字列表弹层：锚点按语言（M3 贴底全宽 sheet / iOS Action Sheet / 墨读浮岛） */
    static void sheetList(Activity a, String title, String[] items, IntConsumer pick) {
        ModouUi u = of(a);
        DesignSpec sp = u.spec;
        final Dialog[] holder = new Dialog[1];

        // ---- iOS 26 Action Sheet（B7）：主项组 + 独立「取消」组两张同心圆角玻璃卡，
        //      组间留缝、57dp 行、20sp 系统蓝、破坏项染红、标题是分组内灰色说明 ----
        if (sp.actionSheetSeparateCancel
                && sp.listAnchor == DesignSpec.SheetAnchor.BOTTOM_INSET) {
            LinearLayout groups = new LinearLayout(a);
            groups.setOrientation(LinearLayout.VERTICAL);

            LinearLayout main = new LinearLayout(a);
            main.setOrientation(LinearLayout.VERTICAL);
            main.setBackground(new ActionGroup(a, sp, sp.actionSheetGroupRadiusDp));
            main.setElevation(dp(a, 1.5f));   // 关掉窗口投影后，两张卡各自负责层次

            if (title != null && !title.isEmpty()) {
                TextView msg = tv(a, title, sp.actionSheetTitleSp, sp.labelSecondary);
                msg.setGravity(Gravity.CENTER);
                msg.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
                msg.setLineSpacing(dp(a, 2), 1.2f);
                main.addView(msg);
                View sep = new View(a);
                sep.setBackgroundColor(sp.separatorNonOpaque);
                main.addView(sep, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1))));
            }

            for (int i = 0; i < items.length; i++) {
                final int idx = i;
                boolean destructive = isDestructiveLabel(items[i]);
                TextView t = tv(a, items[i], sp.actionSheetItemSp,
                        destructive ? sp.destructive : sp.sysBlue);
                t.setGravity(Gravity.CENTER);
                t.setMinimumHeight(dp(a, sp.actionSheetRowDp));
                t.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
                t.setBackground(stateLayerBg(a, sp, 0x00000000, 0f));
                t.setClickable(true);
                t.setOnClickListener(v -> {
                    if (holder[0] != null) holder[0].dismiss();
                    pick.accept(idx);
                });
                main.addView(t, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                if (i < items.length - 1) {
                    View sep = new View(a);
                    sep.setBackgroundColor(sp.separatorNonOpaque);
                    main.addView(sep, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1))));
                }
            }
            groups.addView(main, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // 独立「取消」组：与主项组分离的另一张卡，加粗系统蓝
            LinearLayout cancelGroup = new LinearLayout(a);
            cancelGroup.setOrientation(LinearLayout.VERTICAL);
            cancelGroup.setBackground(new ActionGroup(a, sp, sp.actionSheetGroupRadiusDp));
            cancelGroup.setElevation(dp(a, 1.5f));
            TextView cancel = tv(a, "取消", sp.actionSheetItemSp, sp.sysBlue);
            cancel.setGravity(Gravity.CENTER);
            cancel.setMinimumHeight(dp(a, sp.actionSheetRowDp));
            cancel.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
            cancel.setTypeface(android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD);
            cancel.setBackground(stateLayerBg(a, sp, 0x00000000,
                    concentric(sp, sp.actionSheetGroupRadiusDp)));
            cancel.setClickable(true);
            cancel.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
            });
            cancelGroup.addView(cancel);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(a, sp.actionSheetCancelGapDp);
            groups.addView(cancelGroup, clp);

            // title 传 null：分组内已画说明文字，sheetImpl 的标题区跳过（actionSheet=true）
            holder[0] = sheetAnchored(a, null, groups, sp.listAnchor,
                    DesignSpec.SheetSemantic.LIST);
            return;
        }

        // ---- 墨读浮岛 / M3 贴底全宽 sheet：保持既有逐像素行为，零回归 ----
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = tv(a, items[i], sp.rowTitleSp, u.text);
            t.setPadding(dp(a, 2), dp(a, 9), dp(a, 2), dp(a, 9));
            if (sp.rowMinHeight > 0) t.setMinimumHeight(dp(a, sp.rowMinHeight));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                pick.accept(idx);
            });
            list.addView(t);
            if (i < items.length - 1 && sp.material != DesignSpec.Material.GLASS) {
                View sep = new View(a);
                sep.setBackgroundColor(sp.divider);
                list.addView(sep, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1))));
            }
        }
        holder[0] = sheetAnchored(a, title, list, sp.listAnchor,
                DesignSpec.SheetSemantic.LIST);
    }

    /** iOS Action Sheet 破坏项判定：删除/清空/移除/屏蔽/禁用/重置/清理/移出 等染 systemRed。 */
    private static boolean isDestructiveLabel(String s) {
        if (s == null) return false;
        String[] keys = {"删除", "清空", "移除", "移出", "屏蔽", "禁用", "重置", "清理",
                "清除", "注销", "退出登录", "取消收藏"};
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /** 纸墨风格输入框（sheet 表单用）：卡片色块 + 1dp 描边 + 圆角 8dp */
    static android.widget.EditText input(Context c, String hint, String value, int minLines) {
        ModouUi u = of(c);
        DesignSpec sp = u.spec;
        android.widget.EditText ed = new android.widget.EditText(c);
        ed.setHint(hint);
        ed.setText(value == null ? "" : value);
        ed.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.bodySp - 2f);
        ed.setTextColor(u.text);
        ed.setHintTextColor(u.sub);
        ed.setMinLines(minLines);
        if (sp.material == DesignSpec.Material.TONAL) {
            // M3 Outlined text field：透明底 + 1dp 描边 + 4dp 圆角
            GradientDrawable g = new GradientDrawable();
            g.setColor(0x00000000);
            g.setCornerRadius(dp(c, 4));
            g.setStroke(Math.max(1, dp(c, 1)), u.sub);
            ed.setBackground(g);
        } else if (sp.material == DesignSpec.Material.LIQUID) {
            // iOS：分组内的圆角输入框，弱底、10dp
            ed.setBackground(round(alpha(u.text, 0x0A), 10));
        } else {
            ed.setBackground(round(alpha(u.text, 0x0F), sp.cornerS));
        }
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
        // 三套开关各自的规格：墨读 44×26/球 20、M3 Switch 52×32/球 24、iOS 51×31/球 27
        DesignSpec sp = u.spec;
        boolean tonal = sp.material == DesignSpec.Material.TONAL;
        boolean liquid = sp.material == DesignSpec.Material.LIQUID;
        final int trackW = dp(c, tonal ? 52 : liquid ? 51 : 44);
        final int trackH = dp(c, tonal ? 32 : liquid ? 31 : 26);
        final int knobS = dp(c, tonal ? 24 : liquid ? 27 : 20);
        final int inset = dp(c, tonal ? 4 : liquid ? 2 : 3);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(knobS, knobS);
        klp.leftMargin = inset;
        pill.addView(knob, klp);
        knob.setElevation(dp(c, 1));
        final int travel = trackW - knobS - inset * 2;
        final boolean[] first = {true};
        final Runnable render = () -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(trackH / 2f);
            // 开：墨读用柔和灰蓝，M3 / iOS 用各自的标准主色；关：各自的轨道色
            int onColor = sp.material == DesignSpec.Material.GLASS ? toneAccent(c) : u.accent;
            int offColor = tonal ? u.spec.surfaceLow
                    : liquid ? alpha(u.text, 0x1F) : alpha(u.text, 0x14);
            bg.setColor(st[0] ? onColor : offColor);
            pill.setBackground(bg);
            float to = st[0] ? travel : 0f;
            if (first[0]) {
                knob.setTranslationX(to);
                first[0] = false;
            } else {
                knob.animate().translationX(to).setDuration(180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }
        };
        pill.setPadding(0, 0, 0, 0);
        pill.setLayoutParams(new LinearLayout.LayoutParams(trackW, trackH));
        render.run();
        row.addView(pill);
        // 回调装进数组再挂 tag：列表行复用时要换成新书的源，不能直接烧死在闭包里
        final java.util.function.Consumer<Boolean>[] cbRef = new java.util.function.Consumer[]{cb};
        row.setOnClickListener(v -> {
            st[0] = !st[0];
            render.run();
            if (cbRef[0] != null) cbRef[0].accept(st[0]);
        });
        row.setTag(new Object[]{st, (Runnable) render, cbRef});
        return row;
    }

    /**
     * 复用行换绑定的书源：把开关拨到新源的启用状态，并把回调换成新源的。
     * ListView/RecyclerView 复用 ViewHolder 时必调，否则滚一屏开关状态全串位。
     */
    static void switchSet(View row, boolean on, java.util.function.Consumer<Boolean> cb) {
        Object tag = row == null ? null : row.getTag();
        if (!(tag instanceof Object[])) return;
        Object[] t = (Object[]) tag;
        if (t.length < 3) return;
        boolean[] st = (boolean[]) t[0];
        Runnable render = (Runnable) t[1];
        @SuppressWarnings("unchecked")
        java.util.function.Consumer<Boolean>[] cbRef =
                (java.util.function.Consumer<Boolean>[]) t[2];
        st[0] = on;
        cbRef[0] = cb;
        render.run();
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

    /* ==================== A6 按钮族 / B2 同心圆角（0.9.162 Stage 3） ==================== */

    /**
     * B2 同心圆角：容器内圆角 = 外圆角 − 内边距，形成等差数列（iOS 26 concentric）。
     * 非 concentric 语言原样返回外圆角（墨读 / M3 用各自的 shape scale）。
     */
    static float concentric(DesignSpec sp, float outerDp) {
        if (sp == null || !sp.concentric) return outerDp;
        return Math.max(2f, outerDp - sp.concentricInsetDp);
    }

    /** state layer 按压背景：M3/iOS 叠一层半透明、无涟漪外扩；墨读保持原 RippleDrawable。 */
    private static Drawable stateLayerBg(Context c, DesignSpec sp, int normal, float rDp) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(normal);
        base.setCornerRadius(dp(c, rDp));
        if (sp.material == DesignSpec.Material.GLASS) {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(dp(c, rDp));
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(sp.ripple), base, mask);
        }
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(sp.statePressed);
        pressed.setCornerRadius(dp(c, rDp));
        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{android.R.attr.state_focused}, pressed);
        sld.addState(new int[]{}, base);
        return sld;
    }

    /**
     * A6 按钮族（M3 Expressive 四档层级）：Filled / Tonal / Outlined / Text。
     * 全圆胶囊、labelLarge(14sp)/Medium、40dp 高、64dp 最小宽、elevation 0、
     * state layer 按压（hover 8% / pressed 10%，叠一层不换底、无涟漪外扩）。
     *
     * <p>iOS 下同一 API 自然落到系统语言：Filled = 系统蓝填充胶囊 + 白字，
     * Text/Tonal = 系统蓝文字，Outlined = 系统蓝描边；字重 Regular、44dp 高。
     * 墨读沿用纸墨填充按钮（四档只在 M3/iOS 生效），保持零回归。
     *
     * <p>弹层按钮行与各页普通按钮共用这一个工厂 —— 一改全改，不散落写 if(lang==...)。
     */
    /** 页面按钮用的「全圆胶囊」标记：传它即按按钮高度取半径，不受弹层 8dp 圆角影响。 */
    static final float CAPSULE = 999f;

    static TextView button(Context c, String label, DesignSpec.ButtonKind kind,
                           View.OnClickListener l) {
        return button(c, label, kind, 0f, l);
    }

    /**
     * 同上，{@code radiusDp > 0} 时覆盖按钮圆角（{@link #CAPSULE} = 全圆胶囊）。
     * 页面里的按钮（如规则页导出 / 导入）与弹层按钮形状语境不同：前者是墨读一直沿用的
     * 胶囊，后者按语言 shape scale。加这一个参数，页面私有按钮就能统一到本工厂，
     * 同时墨读弹层按钮保持 0.9.162 的 8dp 完全不变 —— 一改全改，不散落 if(lang==...)。
     */
    static TextView button(Context c, String label, DesignSpec.ButtonKind kind,
                           float radiusDp, View.OnClickListener l) {
        ModouUi u = of(c);
        DesignSpec sp = u.spec;
        boolean tonal = sp.material == DesignSpec.Material.TONAL;
        boolean liquid = sp.material == DesignSpec.Material.LIQUID;

        int fill, fg, stroke;
        switch (kind) {
            case TONAL:
                fill = tonal ? sp.secondaryContainer : liquid ? sp.sysFill2 : alpha(u.text, 0x0A);
                fg = tonal ? u.text : liquid ? sp.sysBlue : u.text;
                stroke = 0;
                break;
            case OUTLINED:
                fill = 0x00000000;
                fg = tonal ? u.accent : liquid ? sp.sysBlue : u.accent;
                stroke = tonal ? sp.outline : liquid ? sp.sysBlue : u.line;
                break;
            case TEXT:
                fill = 0x00000000;
                fg = tonal ? u.accent : liquid ? sp.sysBlue : u.accent;
                stroke = 0;
                break;
            case FILLED:
            default:
                fill = tonal ? u.accent : liquid ? sp.sysBlue : u.accent;
                fg = tonal ? sp.onAccent : 0xFFFFFFFF;
                stroke = 0;
                break;
        }

        TextView t = tv(c, label, sp.buttonLabelSp, fg);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setLetterSpacing(sp.buttonTracking);   // A8：M3 labelLarge 0.007em / iOS −0.008em
        // M3 labelLarge 字重 500 ≈ medium；iOS 按钮 Regular；墨读 bold
        if (sp.buttonWeight == 2) {
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        } else if (sp.buttonWeight == 1) {
            t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        }
        int h = dp(c, sp.buttonHeightDp);
        if (sp.buttonMinWidthDp > 0) t.setMinWidth(dp(c, sp.buttonMinWidthDp));
        int ph = dp(c, tonal ? 20 : liquid ? 16 : 18);
        t.setPadding(ph, 0, ph, 0);

        // Outlined 单独带描边；其余档位（含 TEXT 透明底）统一走 state layer 按压背景。
        // 0.9.163：半径单位统一按 dp 交给 stateLayerBg 换算；旧写法把 px 的 h/2 再 dp()
        // 二次放大了一次（视觉仍是胶囊所以没暴露），这里顺手修正为规格里的按钮高度一半。
        float corner = radiusDp > 0f ? radiusDp : sp.cornerButton;
        float br = corner == 999 ? (float) sp.buttonHeightDp / 2f : corner;
        if (kind == DesignSpec.ButtonKind.OUTLINED) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(fill);
            g.setCornerRadius(dp(c, br));
            g.setStroke(Math.max(1, dp(c, 1)), stroke);
            // 描边底 + 按压高亮：用 LayerDrawable 叠一层 state layer
            android.graphics.drawable.LayerDrawable ld =
                    new android.graphics.drawable.LayerDrawable(new Drawable[]{
                            g, stateLayerBg(c, sp, 0x00000000, br)});
            t.setBackground(ld);
        } else {
            t.setBackground(stateLayerBg(c, sp, fill, br));
        }
        t.setElevation(0f);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, h));
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(l);
        return t;
    }

    /**
     * iOS Action Sheet 的单个分组材质：Liquid Glass 半透基色 + 顶部镜面凝光 +
     * 边缘高光描边 + 连续曲率。不抓屏、不挂系统模糊（Action Sheet 用材质层模拟）。
     */
    private static final class ActionGroup extends Drawable {
        private final int base, highlight, rim;
        private final float rPx, rimPx;
        private final boolean squircle;
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        ActionGroup(Context c, DesignSpec sp, float radiusDp) {
            base = sp.glassBase;
            highlight = sp.glassVeil;
            rim = sp.menuRimLight != 0 ? sp.menuRimLight : sp.glassRim;
            rPx = dp(c, radiusDp);
            rimPx = dp(c, 0.6f);
            squircle = sp.squircle;
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            path.reset();
            android.graphics.RectF r = new android.graphics.RectF(
                    bounds.left, bounds.top, bounds.right, bounds.bottom);
            if (squircle) path.set(ModouShape.rect(r, rPx, false));
            else path.addRoundRect(r, rPx, rPx, android.graphics.Path.Direction.CW);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            // 半透基色
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(base);
            canvas.drawPath(path, p);
            // 顶部镜面凝光（一条自上而下的窄渐变）
            p.setColor(highlight);
            canvas.save();
            canvas.clipPath(path);
            canvas.drawRect(b.left, b.top, b.right, b.top + b.height() * 0.34f, p);
            canvas.restore();
            // 边缘高光描边
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(rimPx);
            p.setColor(rim);
            canvas.drawPath(path, p);
        }

        @Override
        public void setAlpha(int a) { p.setAlpha(a); }
        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * iOS Page Sheet 顶部「上一页」堆叠顶边（B7）：一条比当前 sheet 略窄、被压暗、
     * 上两角圆角的卡片顶。前面这页盖住它的下半部分，视觉上就是上一页沉在后面。
     * 纯内容层装饰，不改窗口 bounds / gravity —— 严格遵守「窗口骨架不动」。
     */
    private static final class PageSheetPeek extends Drawable {
        private final int color;
        private final float rPx;
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        PageSheetPeek(Context c, DesignSpec sp) {
            // 上一页被压暗：浅色用 surface 掺 22% 黑、深色用纯黑掺一点表面色
            color = ModouUi.mix(sp.surface, sp.dark ? 0xFF000000 : 0xFF000000,
                    sp.dark ? 0.35f : 0.18f);
            rPx = dp(c, sp.cornerSheet);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            rect.set(getBounds());
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(color);
            // 只画上两角圆：底边延伸出 bounds，前面 sheet 会盖住
            canvas.drawRoundRect(rect.left, rect.top, rect.right,
                    rect.bottom + rPx * 2f, rPx, rPx, p);
        }

        @Override
        public void setAlpha(int a) { p.setAlpha(a); }
        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }
        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 弹层按钮行：墨读填充并排 / M3 右下文字按钮 / iOS 分隔线横排 */
    static LinearLayout sheetButtons(Activity a, Dialog owner, String cancel, String ok,
                                     Runnable onCancel, Runnable onOk) {
        ModouUi u = of(a);
        DesignSpec sp = u.spec;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(a, 12), 0, 0);

        final TextView c;
        final TextView k;
        if (sp.buttonStyle == DesignSpec.ButtonStyle.TEXT_RIGHT) {
            // M3 Dialog：右下角两个 TextButton —— 收口到 A6 按钮族（state layer /
            // labelLarge Medium 字重 / 全圆 / 最小宽同源），不再自绘一份文字按钮。
            // 监听传 null，与另外两态一样在方法末尾统一挂（带 onCancel/onOk）。
            row.setGravity(Gravity.END);
            c = button(a, cancel, DesignSpec.ButtonKind.TEXT, null);
            k = button(a, ok, DesignSpec.ButtonKind.TEXT, null);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            klp.leftMargin = dp(a, 8);
            row.addView(c);
            row.addView(k, klp);
        } else if (sp.buttonStyle == DesignSpec.ButtonStyle.IOS_STACK) {
            // iOS alert：上方 separator 细线 + 横排两个按钮 + 中间竖线。
            // 取消 Regular / 确定 Semibold，全部系统蓝；危险操作的「确定」染 systemRed。
            // 44dp 行、非不透明 separator、行按压走 systemFill（state layer）。
            row.setOrientation(LinearLayout.VERTICAL);
            // iOS alert：正文与按钮分隔线之间留 16dp 呼吸（HIG），不是紧贴
            row.setPadding(0, dp(a, 16), 0, 0);
            View top = new View(a);
            top.setBackgroundColor(sp.separatorNonOpaque);
            row.addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1))));
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            c = tv(a, cancel, 17, sp.sysBlue);
            boolean okDestructive = isDestructiveLabel(ok);
            k = tv(a, ok, 17, okDestructive ? sp.destructive : sp.sysBlue);
            k.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            c.setGravity(Gravity.CENTER);
            k.setGravity(Gravity.CENTER);
            int vpad = dp(a, 12);
            c.setPadding(0, vpad, 0, vpad);
            k.setPadding(0, vpad, 0, vpad);
            c.setBackground(stateLayerBg(a, sp, 0x00000000, 0f));
            k.setBackground(stateLayerBg(a, sp, 0x00000000, 0f));
            c.setClickable(true);
            k.setClickable(true);
            View mid = new View(a);
            mid.setBackgroundColor(sp.separatorNonOpaque);
            line.addView(c, new LinearLayout.LayoutParams(
                    0, dp(a, sp.dialogBtnHeightDp), 1f));
            line.addView(mid, new LinearLayout.LayoutParams(
                    Math.max(1, dp(a, 1)), ViewGroup.LayoutParams.MATCH_PARENT));
            line.addView(k, new LinearLayout.LayoutParams(
                    0, dp(a, sp.dialogBtnHeightDp), 1f));
            row.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            // 墨读：取消（淡底）+ 主操作（强调色实底）并排
            c = tv(a, cancel, 15, u.text);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(a, 12), 0, dp(a, 12));
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(alpha(u.text, 0x0A));
            cb.setCornerRadius(dp(a, sp.cornerButton));
            c.setBackground(cb);

            k = tv(a, ok, 15, sp.onAccent);
            k.setGravity(Gravity.CENTER);
            k.setPadding(0, dp(a, 12), 0, dp(a, 12));
            GradientDrawable kb = new GradientDrawable();
            kb.setColor(u.accent);
            kb.setCornerRadius(dp(a, sp.cornerButton));
            k.setBackground(kb);

            row.addView(c, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
            klp.leftMargin = dp(a, 10);
            row.addView(k, klp);
        }

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
            DesignSpec sp = u.spec;
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            // 与 confirm 同一口径：居中弹层内边距交给 panel 规格，墨读浮岛保留 14dp
            boolean centeredDlg = sp.confirmAnchor == DesignSpec.SheetAnchor.CENTER;
            box.setPadding(centeredDlg ? 0 : dp(a, 14), dp(a, 4),
                    centeredDlg ? 0 : dp(a, 14), 0);
            box.addView(dialogBody(a, u, msg));
            // 结果/说明类弹层与确认类同锚点：M3 / iOS 都是屏幕居中。
            // 显式 ALERT 语义：M3 走 Dialog 标题区 + 派生容器色，iOS 走 alert 正文居中。
            Dialog d = sheetAnchored(a, title, box, sp.confirmAnchor,
                    DesignSpec.SheetSemantic.ALERT);

            // 单按钮「知道了」：三态收口到 A6 按钮族工厂（state layer / 字重 / 圆角同源）
            if (sp.material == DesignSpec.Material.GLASS) {
                // 墨读：全宽填充按钮（保持原观感，零回归）
                TextView ok = tv(a, "知道了", 15, sp.onAccent);
                ok.setGravity(Gravity.CENTER);
                ok.setPadding(0, dp(a, 12), 0, dp(a, 12));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(u.accent);
                bg.setCornerRadius(dp(a, sp.cornerButton));
                ok.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(a, 12);
                box.addView(ok, lp);
                ok.setOnClickListener(v -> d.dismiss());
            } else if (sp.material == DesignSpec.Material.TONAL) {
                // M3：右下角 TextButton
                TextView ok = button(a, "知道了", DesignSpec.ButtonKind.TEXT,
                        v -> d.dismiss());
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.END);
                row.setPadding(0, dp(a, 8), 0, 0);
                row.addView(ok);
                box.addView(row);
            } else {
                // iOS：细线之上的整行蓝色按钮（alert 分隔线按钮，44dp 高）
                View sep = new View(a);
                sep.setBackgroundColor(sp.separatorNonOpaque);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1)));
                slp.topMargin = dp(a, 12);
                box.addView(sep, slp);
                TextView ok = button(a, "知道了", DesignSpec.ButtonKind.TEXT,
                        v -> d.dismiss());
                ok.setGravity(Gravity.CENTER);
                ok.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                ok.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(a, sp.dialogBtnHeightDp)));
                box.addView(ok);
            }
        } catch (Throwable e) {
            toast(c, msg);
        }
    }
}
