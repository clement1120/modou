package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * ColorOS 级联菜单（Cascading Menu）：紧凑悬浮卡片 + 就地展开的二级子菜单。
 *
 * <p><b>0.9.52 起彻底放弃 PopupWindow</b>。之前两版都是 PopupWindow：
 * 0.9.50 用嵌套 PopupWindow（子菜单 token 挂在父弹层上 → Android 13+ 窗口校验直接抛异常，
 * 点箭头必崩）；0.9.51 改成单个全屏 PopupWindow，但在部分 ROM（ColorOS / Android 17）上
 * 全屏 PopupWindow + showAtLocation 仍有兼容性崩溃。
 *
 * <p><b>0.9.143 起菜单自身就是一个独立浮动窗口</b>（{@code Dialog(Theme_Modou_Glass)} +
 * {@code ModouGlass.attach}），与底栏 / 底部面板走同一条系统级实时背景模糊管线 ——
 * 弹出菜单因此和底栏「一样的底色、一样的模糊」。用 Dialog 而非 PopupWindow，沿用底栏
 * ModouDockWindow / 面板 sheet 的同一种机制，规避了当年 PopupWindow 在 ColorOS /
 * Android 13+ 的窗口校验崩溃；子菜单是同一窗口内的兄弟 View，不再嵌套窗口。宽度按
 * <b>文字实际宽度</b>量出（{@code TextView.measure(UNSPECIFIED)}），只在 168dp~220dp 之间收放，不撑满。
 * <p><b>0.9.144 修正</b>：窗口尺寸从「全屏」改为「精确等于卡片」。<b>关键</b>：
 * {@code Window.setBackgroundBlurRadius} 模糊的是<b>整个窗口</b>背后的画面，全屏窗口会把整屏都糊掉
 * （0.9.143 实测）；卡片大小的窗口只糊卡片背后那一块，屏幕其余部分保持清晰，同时仍和底栏 / 面板同源。
 * 子菜单展开时窗口动态扩展到「主卡 + 子卡」外接矩形，模糊区随之收紧到两块卡片，不会糊到屏幕其它地方。
 * 区外触摸以 {@code ACTION_OUTSIDE} 落到 {@code dispatchTouchEvent} 由我们拦截，关菜单前先收子菜单。
 */
final class CascadingMenu {

    private static final int W_MIN = 168;      // 主卡片最窄
    // 248 → 220：卡片顶到上限时，标题与右侧数值之间会空出一大段（「校验当前列表 —— 全部104个」），
    // 看着像两列被硬拉开。收到 220 后这段留白明显收紧，最长的那几行仍然放得下。
    private static final int W_MAX = 220;      // 主卡片最宽
    private static final int W_SUB_MAX = 196;  // 子卡片最宽（跟着主卡等比收窄，两张卡宽度差保持 24dp）
    private static final int W_SUB_MIN = 132;  // 子卡片最窄（再窄就撑不住四字标题）
    private static final int MAX_H = 72;       // 卡片最大高度（屏高百分比）
    /** 子卡与主卡之间的缝隙：8dp 是「两张卡」不是「一张卡裂开」的最小视觉间距 */
    private static final int SIDE_GAP = 8;
    // 0.9.159：Scrim 浓度收口到 DesignSpec.menuScrim（墨读 / M3 / iOS 各自取值）。
    // 墨读仍为 0x17000000，与 0.9.158 之前一致，零回归。
    /** 主菜单是否从锚点上方展开（showAbove）—— iOS popover 指示三角据此决定朝向 */
    private boolean menuAbove;

    /* ---------------- 数据模型 ---------------- */

    static final class Item {
        final String label;
        final String sub;          // 父项副标题（当前值），可 null
        final String value;        // 行右侧次要文本，可 null
        final boolean checked;     // ✓ + 浅灰高亮
        final Runnable action;     // 叶子动作
        final List<Item> children; // 非空 = 父项（可展开）

        private Item(String label, String sub, String value, boolean checked,
                     Runnable action, List<Item> children) {
            this.label = label;
            this.sub = sub;
            this.value = value;
            this.checked = checked;
            this.action = action;
            this.children = children;
        }

        static Item leaf(String label, Runnable a) { return new Item(label, null, null, false, a, null); }

        static Item leaf(String label, String value, Runnable a) {
            return new Item(label, null, value, false, a, null);
        }

        static Item check(String label, boolean checked, Runnable a) {
            return new Item(label, null, null, checked, a, null);
        }

        static Item parent(String label, String sub, List<Item> children) {
            return new Item(label, sub, null, false, null, children);
        }

        static Item divider() { return new Item(null, null, null, false, null, null); }
    }

    /* ---------------- 实例 ---------------- */

    private final Activity host;
    private final ModouUi ui;
    /** 当前设计语言规格：菜单卡片的材质 / 圆角 / 模糊 / 行高 / 箭头全部只读它，页面不写语言分支 */
    private final DesignSpec spec;
    private FrameLayout root;        // 全屏透明覆盖层（对话框的内容根）

    /** 每个宿主 Activity 当前打开的菜单（窗口方案下，返回键/侧滑必须被它拦截） */
    private static final Map<Activity, CascadingMenu> ACTIVE = new WeakHashMap<>();
    /** OnBackInvokedCallback 实例，仅 API33+ 存在；用 Object 存，避免低版本类找不到 */
    private Object backCb;
    /** 菜单自身的浮动窗口（0.9.143 起：与底栏 / 面板同走 ModouGlass.attach 系统模糊） */
    private Dialog dialog;
    /** API33+ 返回拦截所挂的 dispatcher 宿主：对话框自身的 dispatcher（不是宿主 Activity，窗口改由对话框持有） */
    private android.window.OnBackInvokedDispatcher backHost;

    private LinearLayout mainCard;
    /** 主卡片的真模糊容器（ModouFrost）：定位改它，卡片的 lp 已被容器接管 */
    private View mainBox;
    /** 主卡退后时压在最上层的浅黑 Scrim（不透明卡面 + 遮罩，而不是把整卡调成半透明） */
    private View mainScrim;
    private int mainW;
    private int mainH;
    /** 主卡当前的屏幕左上角坐标（place() 记录；toggleSub / closeSub 用它算外接矩形与复位） */
    private int mainX, mainY;

    private LinearLayout subCard;
    /** 子菜单的真模糊容器 */
    private View subBox;
    private View subOwner;      // 当前展开子菜单的那一行

    private CascadingMenu(Activity host) {
        this.host = host;
        this.ui = ModouUi.of(host);
        this.spec = ui.spec;
    }

    /** 主菜单：显示在 anchor 正下方（顶栏 ⋮ / 胶囊入口用），右缘与 anchor 对齐 */
    static void show(Activity host, View anchor, List<Item> items) {
        try {
            new CascadingMenu(host).open(anchor, items, false);
        } catch (Throwable e) {
            Diagnostics.capture("menu.show", e);
            ModouUi.toast(host, "菜单打开失败：" + e.getClass().getSimpleName());
        }
    }

    /** 主菜单：显示在 anchor 正上方（底部工具坞入口用） */
    static void showAbove(Activity host, View anchor, List<Item> items) {
        try {
            new CascadingMenu(host).open(anchor, items, true);
        } catch (Throwable e) {
            Diagnostics.capture("menu.showAbove", e);
            ModouUi.toast(host, "菜单打开失败：" + e.getClass().getSimpleName());
        }
    }

    /* ---------------- 打开 / 定位 ---------------- */

    private void open(View anchor, List<Item> items, boolean above) {
        this.menuAbove = above;
        Card c = build(items, ModouUi.dp(host, W_MIN), ModouUi.dp(host, W_MAX),
                maxCardH(), true);
        mainCard = c.card;
        mainW = c.w;
        mainH = c.h;

        root = new FrameLayout(host);
        root.setClickable(true);
        // 点卡片外的空白：有子菜单就先把子菜单「出栈」收回去，没有才整卡关闭。
        // 子菜单是盖在主菜单上的，不先退回主菜单的话，用户想换个父项就只能全部重开。
        root.setOnClickListener(v -> {
            if (subCard != null) closeSub();
            else dismiss();
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(mainW, mainH);
        mainCard.setLayoutParams(lp);
        // 0.9.159：容器按设计语言三态出图 —— 墨读 / iOS 走 ModouFrost 玻璃材质层，
        // M3 是实色填充列表（不叠玻璃、不挂泛光），阴影由卡片自身 elevation 提供。
        // 窗口尺寸仍精确等于卡片（0.9.144 的翻车修正），骨架一律不动。
        mainBox = wrapCard(mainCard, lp);
        root.addView(mainBox, lp);

        // 退后遮罩：盖在整张卡（含文字与边缘）之上的一层极浅黑。
        // 之前是给整卡设 alpha 0.5 —— 那等于把卡片变成半透明玻璃纸，
        // 背后的书名封面直接透上来，四个圆角也跟着发虚。正确做法是卡面保持不透明，
        // 用一层同轮廓的 Scrim 压暗，视觉上「退到后层」但依然干净。
        mainScrim = new View(host);
        mainScrim.setBackground(scrimDrawable());
        mainScrim.setAlpha(0f);
        ((ViewGroup) mainBox).addView(mainScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 入场动画不在这里做：卡片还没定位，此时显形就会「先在左上角闪一下」。
        // 先整体隐身，等 place() 把坐标算好，再从入口按钮那一角 0.90 → 1.0 / 淡入。
        // 注意只动外层玻璃容器：卡面 alpha 始终保持 1，否则会透出背后的书名封面。
        mainCard.setAlpha(1f);
        if (mainBox != null) {
            mainBox.setAlpha(0f);
            mainBox.setScaleX(0.90f);
            mainBox.setScaleY(0.90f);
        }

        // 0.9.144：菜单是独立浮动窗口 + ModouGlass.attach()，与底栏 / 面板同一条系统级实时
        // 模糊管线（弹出菜单和底栏「一样的底色、一样的模糊」）。关键修正：窗口尺寸从「全屏」
        // 改为「精确等于卡片」——setBackgroundBlurRadius 模糊的是整个窗口背后的画面，全屏窗口
        // 会把整屏都糊掉（0.9.143 实测）；卡片大小的窗口只糊卡片背后那一块，屏幕其余保持清晰。
        // 用 Dialog（非 PopupWindow）走窗口模糊，与底栏 ModouDockWindow / 面板 sheet 同机制，
        // 经验证在 ColorOS / Android 13+ 稳定（底栏与面板已长时间运行）。
        // 区外触摸以 ACTION_OUTSIDE 落到 dispatchTouchEvent，由我们拦截后关菜单（先收子菜单）。
        Dialog d = new Dialog(host, R.style.Theme_Modou_Glass) {
            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                if (ev.getAction() == android.view.MotionEvent.ACTION_OUTSIDE) {
                    back();
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);                 // 返回 / 外点由我们接管，绝不自动关
        d.setCanceledOnTouchOutside(false);
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            // 窗口尺寸精确等于卡片：模糊只作用于卡片背后的矩形，屏幕其余部分保持清晰。
            w.setGravity(Gravity.TOP | Gravity.LEFT);
            w.setLayout(mainW, mainH);
            // 0.9.210：弹出时底层压暗 50%，把视觉焦点完全集中到菜单上（用户要求加遮罩）
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.5f);
            // 圆角卡片投影，强调层级、不显脏
            w.setElevation(ModouUi.dp(host, 12));
            // 非触摸模态 + 监听区外触摸：屏幕其余区域照常可点（书架可滚），
            // 但第一次区外触摸会以 ACTION_OUTSIDE 通知我们 → 关菜单（先收子菜单）。
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        }
        dialog = d;

        // 先 show 再挂模糊：attach 前拿不到 decor 实际参数，setBackgroundBlurRadius 不生效
        d.show();
        // 0.9.159：只有「需要折射背后内容」的语言才挂系统跨窗口实时模糊（墨读玻璃 +
        // iOS Liquid Glass），M3 是实色填充列表、规范明确无模糊 → 不挂。圆角按各自卡片走。
        // 窗口尺寸仍精确等于卡片（0.9.144 翻车修正），骨架不动。
        if (w != null && spec.menuBlur) ModouGlass.attach(w, menuCornerPx());

        // 返回键 / 侧滑返回 必须被菜单拦截并消费：只关菜单，绝不触发页面回退。
        // API33+ 走 OnBackInvokedCallback（挂在对话框自己的 dispatcher 上，连侧滑手势一起拦）；
        // API<33 走「覆盖层获焦 + OnKeyListener 吃掉 KEYCODE_BACK」。
        registerBack();
        root.setFocusableInTouchMode(true);
        root.setFocusable(true);
        root.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) back();
                return true;   // 消费掉，不传给 Activity.onBackPressed
            }
            return false;
        });
        root.requestFocus();

        try {
            place(anchor, above);
        } catch (Throwable e) {
            Diagnostics.capture("menu.place", e);
            showNow();
        }
    }

    /** 菜单是否正在显示（用于返回拦截判定） */
    private boolean isShowing() {
        return root != null && dialog != null;
    }

    /** 注册返回拦截：清掉同宿主残留的旧菜单，登记自身，API33+ 挂 OnBackInvokedCallback */
    private void registerBack() {
        CascadingMenu prev = ACTIVE.get(host);
        if (prev != null && prev != this) prev.dismiss();
        ACTIVE.put(host, this);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                // 菜单现在是独立窗口：返回拦截必须挂到「对话框自己的 dispatcher」上，
                // 而不是宿主 Activity 的 —— 窗口改由对话框持有后，Activity 的 dispatcher
                // 不再接收到焦点窗口的返回事件。
                if (dialog != null && dialog.getOnBackInvokedDispatcher() != null) {
                    Api33.register(dialog.getOnBackInvokedDispatcher(), this);
                }
            } catch (Throwable ignore) {
                /* 个别 ROM 的 dispatcher 异常：放弃拦截，菜单仍可点外部关闭 */
            }
        }
    }

    /** 注销返回拦截并从活动表移除 */
    private void unregisterBack() {
        ACTIVE.remove(host);
        if (Build.VERSION.SDK_INT >= 33 && backCb != null) {
            try {
                if (backHost != null) Api33.unregister(backHost, backCb);
            } catch (Throwable ignore) {
                /* dispatcher 已随对话框销毁：无需处理 */
            }
            backCb = null;
        }
    }

    /** API33+ 专用：把 OnBackInvokedCallback 关在一个只在 33+ 才加载的内部类里，
     *  避免低版本设备加载 CascadingMenu 时因引用不存在的类而 NoClassDefFoundError。 */
    private static final class Api33 {
        static void register(android.window.OnBackInvokedDispatcher disp, CascadingMenu m) {
            OnBackInvokedCallback cb = () -> { if (m.isShowing()) m.back(); };
            disp.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
            m.backCb = cb;
            m.backHost = disp;
        }
        static void unregister(android.window.OnBackInvokedDispatcher disp, Object cb) {
            disp.unregisterOnBackInvokedCallback((OnBackInvokedCallback) cb);
        }
    }

    private void place(View anchor, boolean above) {
        try {
            if (mainCard == null || anchor == null || dialog == null) return;
            int[] a = new int[2];
            anchor.getLocationOnScreen(a);                  // 入口按钮的屏幕坐标
            int sw = host.getResources().getDisplayMetrics().widthPixels;
            int sh = host.getResources().getDisplayMetrics().heightPixels;
            int gap = ModouUi.dp(host, 8);

            // 卡片左上角的屏幕坐标：右缘对齐入口按钮
            int left = a[0] + anchor.getWidth() - mainW;
            left = Math.max(gap, Math.min(left, sw - mainW - gap));
            int top = above ? a[1] - mainH - gap : a[1] + anchor.getHeight() + gap;
            if (top + mainH > sh - gap) top = sh - mainH - gap;   // 下面放不下就往上顶
            if (top < gap) top = gap;
            mainX = left;
            mainY = top;

            // 卡片就位在窗口里 (0,0)；窗口自身移到屏幕 (left, top)
            View box = mainBox != null ? mainBox : mainCard;
            Window w = dialog.getWindow();
            if (w != null) {
                WindowManager.LayoutParams al = w.getAttributes();
                al.x = left;
                al.y = top;
                w.setAttributes(al);
            }
            animIn(box, mainW, above);
        } catch (Throwable e) {
            Diagnostics.capture("menu.place", e);
            showNow();
        }
    }

    /**
     * 菜单入场：0.90 → 1.0 + 淡入，150ms。
     *
     * <p>缩放锚点贴着入口按钮（右上角的 ⋮）：卡片从按钮那一角「撑开」，
     * 而不是从自身中心胀大 —— 后者会让顶边和右缘同时位移，看着像整块弹窗在跳。
     * 曲线用 DecelerateInterpolator(1.8)：前段快、尾段黏，是 ColorOS 的收尾手感。
     */
    private void animIn(View box, float pivotX, boolean above) {
        if (box == null) return;
        box.animate().cancel();
        // 0.9.181：三套语言的「弹出」动作分开 —— 之前共用 0.90→1，换语言只换材质和颜色，
        // 菜单看起来还是同一套东西。
        //   M3 Expressive：从锚点 84% 放大（Material menu 的 container transform 手感）
        //   iOS popover   ：94% 轻缩放 + 上浮 6dp（从按钮「浮出来」而不是「胀出来」）
        //   墨读           ：完全不缩放，只上浮 10dp + 淡入（纸片被抽出来）
        float fromScale = spec.material == DesignSpec.Material.TONAL ? 0.84f
                : spec.material == DesignSpec.Material.LIQUID ? 0.94f : 1f;
        float fromY = spec.material == DesignSpec.Material.TONAL ? 0f
                : ModouUi.dp(host, spec.material == DesignSpec.Material.LIQUID ? 6 : 10);
        box.setAlpha(0f);
        box.setScaleX(fromScale);
        box.setScaleY(fromScale);
        box.setTranslationY(fromY);
        box.setPivotX(Math.max(0f, Math.min(box.getWidth() > 0 ? box.getWidth() : mainW, pivotX)));
        box.setPivotY(above ? (box.getHeight() > 0 ? box.getHeight() : mainH) : 0f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(animInMs())
                .setInterpolator(menuInterp(spec.material == DesignSpec.Material.GLASS ? 1.4f : 1.8f,
                        spec.overshoot > 0f))
                .start();
    }

    /** 兜底显形：定位失败时也要保证菜单可见（不留一个 alpha 0 的空壳） */
    private void showNow() {
        View box = mainBox != null ? mainBox : mainCard;
        if (box == null) return;
        box.animate().cancel();
        box.setAlpha(1f);
        box.setScaleX(1f);
        box.setScaleY(1f);
    }

    /**
     * 关闭菜单：先播 130ms 的淡出 + 缩回 0.94，动画结束再摘掉覆盖层。
     *
     * <p>以前是 addView / removeView 硬切 —— 打开时白块凭空出现，关闭时整卡瞬间消失，
     * 这就是用户说的「硬生生蹦出来」。现在入场与出场都是「淡入淡出 + 轻微缩放」，
     * 只动外层玻璃容器，卡面 alpha 永远是 1，背后内容不会透上来。
     */
    private void dismiss() {
        if (root == null) return;
        unregisterBack();
        final FrameLayout r = root;
        final Dialog dlg = dialog;
        final View box = mainBox != null ? mainBox : mainCard;
        final View sub = subBox != null ? subBox : subCard;
        // 立刻摘状态：动画期间再点外部 / 再按返回不会重复触发；isShowing() 马上变 false
        root = null;
        dialog = null;
        mainCard = null;
        mainBox = null;
        mainScrim = null;
        subCard = null;
        subBox = null;
        subOwner = null;

        // 菜单是独立窗口：动画播完再 dismiss 整个对话框（连同 root 一起回收）
        final Runnable remove = () -> {
            try {
                if (dlg != null) dlg.dismiss();
            } catch (Throwable ignore) {
                /* 对话框已随宿主销毁：无需处理 */
            }
        };
        if (box == null || dlg == null) { remove.run(); return; }

        if (sub != null) {
            sub.animate().cancel();
            sub.animate().alpha(0f).setDuration(animOutMs()).start();
        }
        box.animate().cancel();
        // 出场与入场同源：M3 缩回锚点、iOS 轻缩 + 下沉、墨读只下沉不缩放
        float toScale = spec.material == DesignSpec.Material.TONAL ? 0.90f
                : spec.material == DesignSpec.Material.LIQUID ? 0.96f : 1f;
        float toY = spec.material == DesignSpec.Material.TONAL ? 0f
                : ModouUi.dp(host, spec.material == DesignSpec.Material.LIQUID ? 4 : 8);
        box.animate().alpha(0f).scaleX(toScale).scaleY(toScale).translationY(toY)
                .setDuration(animOutMs())
                .setInterpolator(menuInterp(1.6f, false))
                .withEndAction(remove)
                .start();
        // 安全阀：视图被 detach 或动画被系统掐掉时 withEndAction 不保证回调
        r.postDelayed(remove, animOutMs() + 140);
    }

    /* ---------------- 三态外观（0.9.159 · 只读 spec，不写语言分支） ----------------
       窗口骨架（卡片尺寸 Dialog / place 定位 / 子菜单外接矩形 / 返回拦截）一律保持不动，
       本节只负责「这张卡长什么样」：材质、圆角、描边、箭头、行、图标、按压、动效。
       墨读路径逐位对齐 0.9.158 之前的实现（零回归）；M3 走实色填充列表；
       iOS 走 Liquid Glass（半透基色 + 镜面高光 + 指向锚点的箭头 + 系统实时模糊）。 */

    /** 卡片圆角（px）：墨读 20 / M3 8 / iOS 14 */
    private float menuCornerPx() { return ModouUi.dp(host, spec.menuCornerDp); }

    /** 按语言包一层容器：墨读走玻璃材质 FrostBox；M3 / iOS 用不裁剪的 FrameLayout，
     *  让卡片自带背景（含 iOS 凸出箭头）完整画出，不被 FrostBox 的 clipToOutline 切掉。 */
    private ViewGroup wrapCard(LinearLayout card, FrameLayout.LayoutParams lp) {
        if (spec.menuStyle == DesignSpec.MenuStyle.GLASS_ANCHOR) {
            // 墨读：原路径逐位不变（20dp 超椭圆玻璃材质层 + 系统模糊）
            ModouFrost.FrostBox fb = ModouFrost.wrap(card, menuCornerPx());
            fb.setLayoutParams(lp);
            return fb;
        }
        FrameLayout box = new FrameLayout(host);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setLayoutParams(lp);
        if (spec.menuStyle == DesignSpec.MenuStyle.FILLED_ANCHOR) {
            // M3：elevation 3 由卡片自身投影（GradientDrawable 提供圆角 outline，不会出灰边）
            card.setElevation(ModouUi.dp(host, spec.menuElevationDp));
        }
        return box;
    }

    /** 卡片背景：墨读 squircle 玻璃 / M3 普通圆弧实色 / iOS 液态玻璃 popover。
     *  {@code withArrow} 只有主卡为 true —— 箭头只指向锚点，子卡不带。 */
    private Drawable cardBackground(boolean withArrow) {
        float r = menuCornerPx();
        switch (spec.menuStyle) {
            case FILLED_ANCHOR: {
                GradientDrawable g = new GradientDrawable();
                g.setColor(spec.menuSurface);       // surfaceContainerHigh 实色
                g.setCornerRadius(r);               // M3 普通圆弧，不用连续曲率
                return g;
            }
            case LIQUID_POPOVER: {
                return new LiquidPopover(spec.menuSurface, r,
                        spec.menuRimLight, ModouUi.dp(host, spec.menuRimDp),
                        withArrow ? ModouUi.dp(host, spec.menuArrowDp) : 0f,
                        menuAbove, spec.dark);
            }
            case GLASS_ANCHOR:
            default: {
                // 0.9.210：纯实色卡片，严禁半透明/毛玻璃——之前 0x12 alpha 玻璃让底层书名直接穿透菜单文字
                GradientDrawable g = new GradientDrawable();
                g.setColor(spec.menuSurface);       // 实色 surface（深色主题下即 #242731 一档）
                g.setCornerRadius(r);
                if (spec.menuRimLight != 0) {
                    g.setStroke(Math.max(1, ModouUi.dp(host, spec.menuRimDp)),
                            ModouUi.alpha(spec.menuRimLight, 0x59));
                }
                return g;
            }
        }
    }

    /** 退后遮罩：形状跟各自卡片一致（墨读 / iOS squircle，M3 普通圆弧） */
    private Drawable scrimDrawable() {
        float r = menuCornerPx();
        if (spec.menuStyle == DesignSpec.MenuStyle.FILLED_ANCHOR) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(spec.menuScrim);
            g.setCornerRadius(r);
            return g;
        }
        return new SquircleFill(spec.menuScrim, r);
    }

    /** 行背景：墨读零回归（仅 checked 时圆角高亮）；M3 state layer 叠色、
     *  iOS 行整体浅灰高亮 —— 都是「按下才叠一层」，无涟漪外扩。 */
    private Drawable rowBg(boolean checked) {
        float r = ModouUi.dp(host, spec.menuRowRadiusDp);
        if (spec.menuStateLayer == 0) {
            // 墨读：与 0.9.158 之前一致 —— 仅选中行有 8% 墨的圆角底
            if (!checked) return null;
            GradientDrawable hl = new GradientDrawable();
            hl.setCornerRadius(ModouUi.dp(host, 14));
            hl.setColor(spec.menuHighlight);
            return hl;
        }
        GradientDrawable press = new GradientDrawable();
        press.setColor(spec.menuStateLayer);
        press.setCornerRadius(r);
        GradientDrawable base = new GradientDrawable();
        base.setColor(checked ? spec.menuHighlight : 0x00000000);
        base.setCornerRadius(r);
        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, press);
        sld.addState(new int[]{}, base);
        return sld;
    }

    /** 语义图标推断：调用点零改动，集中一张表，Stage 4 可逐页精确覆盖。
     *  返回 {@link ModouGlyph} 的 kind，-1 = 该项不配图标。 */
    private static int glyphFor(String s) {
        if (s == null) return -1;
        String t = s;
        if (t.contains("刷新") || t.contains("重载") || t.contains("重新加载")) return ModouGlyph.REFRESH;
        if (t.contains("编辑") || t.contains("修改") || t.contains("重命名")) return ModouGlyph.EDIT;
        if (t.contains("删除") || t.contains("清空") || t.contains("清理") || t.contains("移除")) return ModouGlyph.TRASH;
        if (t.contains("搜索") || t.contains("查找") || t.contains("检索")) return ModouGlyph.SEARCH;
        if (t.contains("设置") || t.contains("配置") || t.contains("偏好")) return ModouGlyph.GEAR;
        if (t.contains("筛选") || t.contains("过滤") || t.contains("分组") || t.contains("排序")) return ModouGlyph.FILTER;
        if (t.contains("导入") || t.contains("新建") || t.contains("新增") || t.contains("添加")) return ModouGlyph.ADD;
        if (t.contains("校验") || t.contains("检测") || t.contains("检查") || t.contains("验证")) return ModouGlyph.CHECK;
        if (t.contains("关于") || t.contains("帮助") || t.contains("说明") || t.contains("详情")) return ModouGlyph.INFO;
        if (t.contains("书签") || t.contains("收藏")) return ModouGlyph.BOOKMARK;
        if (t.contains("书源") || t.contains("书城")) return ModouGlyph.BOOK;
        if (t.contains("网格") || t.contains("布局") || t.contains("视图") || t.contains("排布")) return ModouGlyph.GRID;
        if (t.contains("返回") || t.contains("关闭") || t.contains("退出")) return ModouGlyph.CLOSE;
        if (t.contains("禁用") || t.contains("屏蔽")) return ModouGlyph.BLOCK;
        if (t.contains("拖动") || t.contains("拖动排序")) return ModouGlyph.DRAG;
        if (t.contains("切换") || t.contains("交换")) return ModouGlyph.SWITCH;
        if (t.contains("导出") || t.contains("备份") || t.contains("分享")) return ModouGlyph.ARROW_UP;
        if (t.contains("展开") || t.contains("收起") || t.contains("上一级")) return ModouGlyph.BACK;
        if (t.contains("更多")) return ModouGlyph.MORE_H;
        return -1;
    }

    /** 单个行图标：M3 24dp leading（onSurfaceVariant）/ iOS 22dp trailing（label tint）。
     *  线性 stroke 图标与 Material Symbols outlined / SF Symbols regular 同一观感。 */
    private View glyphView(int kind) {
        int box = ModouUi.dp(host, spec.menuItemIconDp);
        int color = spec.menuIconLeading ? ui.sub
                : ModouUi.alpha(ui.text, 0xD9);
        View v = new View(host);
        v.setBackground(new ModouGlyph(kind, color,
                box * 0.60f, ModouUi.dp(host, 1.7f)));
        return v;
    }

    /** 动效曲线（0.9.162 A3/B9 收口）：所有曲线只在 {@link DesignSpec#interpolator}
     *  定义一份，菜单 / 画廊 / 弹层共用。墨读仍是 Decelerate(decel)，零回归。
     *  {@code overshootAllowed=false} 用于出场/收起，避免关闭时「弹一下」。 */
    private android.view.animation.Interpolator menuInterp(float decel,
                                                           boolean overshootAllowed) {
        return DesignSpec.interpolator(spec, overshootAllowed, decel);
    }

    /* ---------------- 卡片构建 ---------------- */

    private static final class Card {
        LinearLayout card;
        int w;
        int h;
    }

    /**
     * iOS 26 Liquid Glass popover：连续曲率卡片 + 顶部镜面高光带 + 白色细高光描边 +
     * <b>真实凸出、指向锚点的小箭头</b>。
     *
     * <p>关键：卡片 bounds 内专门留出 {@code arrowPx} 高的「箭头带」，圆角主体从带的
     * 内侧画起，三角画在带里、尖端指向窗口外缘的锚点。这样箭头凸出于圆角矩形但仍在
     * 窗口 bounds 内 —— 既不触发 §6「扩大窗口 bounds」的骨架改动，也不会被
     * FrostBox 的 clipToOutline 切掉（iOS 容器 deliberately 不裁剪）。
     *
     * <p>背后内容由 {@code ModouGlass.attach} 的系统跨窗口实时模糊折射，本 Drawable
     * 只负责材质外观（半透基色 + 高光），不自己抓屏。
     */
    private static final class LiquidPopover extends Drawable {
        private final Path body = new Path();
        private final Path arrow = new Path();
        private final Path combined = new Path();
        private final RectF rect = new RectF();
        private final RectF inset = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int baseColor, rimColor;
        private final float radiusPx, arrowPx, rimPx;
        private final boolean arrowAtBottom, dark;
        private android.graphics.LinearGradient sheenGrad;

        LiquidPopover(int baseColor, float radiusPx, int rimColor, float rimPx,
                      float arrowPx, boolean arrowAtBottom, boolean dark) {
            this.baseColor = baseColor;
            this.radiusPx = radiusPx;
            this.rimColor = rimColor;
            this.rimPx = Math.max(1f, rimPx);
            this.arrowPx = arrowPx;
            this.arrowAtBottom = arrowAtBottom;
            this.dark = dark;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(baseColor);
            rim.setStyle(Paint.Style.STROKE);
            rim.setColor(rimColor);
            rim.setStrokeWidth(this.rimPx);
            sheen.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            rect.set(b);
            inset.set(b);
            if (arrowPx > 0f) {
                if (arrowAtBottom) inset.bottom -= arrowPx;
                else inset.top += arrowPx;
            }
            body.set(ModouShape.rect(inset, radiusPx, false));
            arrow.reset();
            if (arrowPx > 0f) {
                // 箭头对齐锚点：菜单右缘始终与 ⋮ 右缘对齐，故水平落在卡片右侧
                float cx = rect.left + rect.width() * 0.86f;
                float half = arrowPx * 1.15f;
                if (arrowAtBottom) {
                    arrow.moveTo(cx - half, inset.bottom - 1f);
                    arrow.lineTo(cx, rect.bottom);
                    arrow.lineTo(cx + half, inset.bottom - 1f);
                } else {
                    arrow.moveTo(cx - half, inset.top + 1f);
                    arrow.lineTo(cx, rect.top);
                    arrow.lineTo(cx + half, inset.top + 1f);
                }
                arrow.close();
            }
            combined.set(body);
            if (arrowPx > 0f) combined.addPath(arrow);

            // 镜面高光：从受光边往内快速衰减的一道白，Liquid Glass 的「凝光」感
            float h = inset.height();
            if (h > 0f) {
                sheenGrad = new android.graphics.LinearGradient(0, inset.top, 0,
                        inset.top + h * 0.34f,
                        new int[]{(dark ? 0x38FFFFFF : 0x66FFFFFF), 0x00000000},
                        new float[]{0f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            }
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            if (rect.width() <= 0 || rect.height() <= 0) return;
            int save = canvas.save();
            canvas.clipPath(combined);
            fill.setColor(baseColor);
            canvas.drawRect(rect, fill);
            if (sheenGrad != null) {
                sheen.setShader(sheenGrad);
                canvas.drawRect(inset, sheen);
            }
            canvas.restoreToCount(save);
            // 镜面高光描边：沿合并轮廓（含箭头）描一圈极细白，边界靠光影立起来
            if (rimColor != 0) canvas.drawPath(combined, rim);
        }

        @Override
        public void setAlpha(int a) { fill.setAlpha(a); invalidateSelf(); }
        @Override
        public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); invalidateSelf(); }
        @Override
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * @param minWpx 卡片宽度下限（px）：主菜单 168，子菜单 132（内容再短也不再窄下去）
     * @param maxWpx 卡片宽度上限（px）：主菜单 248，子菜单按可用空间收放
     * @param maxHpx 卡片高度上限（px）：超出的部分走内部滚动，绝不撑成巨型方块
     */
    private Card build(List<Item> items, int minWpx, int maxWpx, int maxHpx,
                       boolean withArrow) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        // 0.9.159：卡片背景按语言出图（墨读 squircle 玻璃 / M3 普通圆弧实色 /
        // iOS 带箭头的液态玻璃）。墨读路径与 0.9.158 之前逐位一致，零回归。
        card.setBackground(cardBackground(withArrow));
        // 上下内边距：三语言各自档位；iOS 主卡额外在箭头那一侧让出 arrowDp，
        // 保证凸出的三角有 bounds 空间、文字又不压到它。
        // 0.9.209：面板顶部额外 10dp 内边距，让首个选中态高亮块不再顶到上边缘
        int padTop = ModouUi.dp(host, spec.menuRowPadVDp + 4 + 10);
        int padBottom = ModouUi.dp(host, spec.menuRowPadVDp + 4);
        if (withArrow && spec.menuPopoverArrow) {
            int extra = ModouUi.dp(host, spec.menuArrowDp);
            if (menuAbove) padBottom += extra; else padTop += extra;
        }
        // 左右只留 4dp（行内已有 menuRowPadL，合计视觉边距按语言走）
        card.setPadding(ModouUi.dp(host, 4), padTop, ModouUi.dp(host, 4), padBottom);
        // 卡片自己吃掉点击：点在内边距上不该把菜单关掉（会冒泡到 root 的关闭监听）
        card.setClickable(true);

        LinearLayout rows = new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        // 标签先按 WRAP_CONTENT 建，量出「文字真正需要多宽」，再决定卡片宽度
        for (Item it : items) {
            if (it.label == null) addDivider(rows);
            else if (it.children != null && !it.children.isEmpty()) addParentRow(rows, it);
            else addLeafRow(rows, it);
        }

        int natural = 0;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            row.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            natural = Math.max(natural, row.getMeasuredWidth() + ModouUi.dp(host, 4));
        }
        int w = natural + ModouUi.dp(host, 4) * 2 + ModouUi.dp(host, 6);  // 卡片左右 padding + 6dp 余量
        w = Math.max(Math.min(w, maxWpx), Math.min(minWpx, maxWpx));

        // 宽度定了：标签改成 weight=1，超长文本走省略号，右侧的值/✓ 贴右对齐
        fitWidth(rows);

        ScrollView sv = new ScrollView(host);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = card.getMeasuredHeight();
        if (h > maxHpx) h = maxHpx;
        if (h <= 0) h = ModouUi.dp(host, 60);

        Card out = new Card();
        out.card = card;
        out.w = w;
        out.h = h;
        return out;
    }

    /** 卡片最大高度：屏高 72%，上下留出 「点空白关菜单」的余地 */
    private int maxCardH() {
        return (int) (host.getResources().getDisplayMetrics().heightPixels * MAX_H / 100f);
    }

    /**
     * 宽度量完之后的「定型」：行撑满卡片宽、主标签吃掉剩余空间并允许省略。
     * 量宽度时行是 WRAP_CONTENT（否则量不出文字的真实需要），这里再切回 MATCH_PARENT。
     */
    private void fitWidth(LinearLayout rows) {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout r = (LinearLayout) row;
            LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) r.getLayoutParams();
            rlp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            r.setLayoutParams(rlp);
            for (int j = 0; j < r.getChildCount(); j++) {
                View ch = r.getChildAt(j);
                if (ch instanceof LinearLayout) {         // 父项的「标题 + 副标题」竖列
                    LinearLayout col = (LinearLayout) ch;
                    LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) col.getLayoutParams();
                    clp.width = 0;
                    clp.weight = 1f;
                    col.setLayoutParams(clp);
                    for (int k = 0; k < col.getChildCount(); k++) {
                        View t = col.getChildAt(k);
                        if (t instanceof TextView) {
                            ((TextView) t).setSingleLine(true);
                            ((TextView) t).setEllipsize(TextUtils.TruncateAt.END);
                        }
                    }
                } else if (ch instanceof TextView && LABEL_TAG.equals(ch.getTag())) {
                    LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) ch.getLayoutParams();
                    tlp.width = 0;
                    tlp.weight = 1f;
                    ch.setLayoutParams(tlp);
                }
            }
        }
    }

    private static final String LABEL_TAG = "casc:label";

    /** 行容器：行高 / 内边距按语言（墨读 52·16/14·13 / M3 48·12·6 / iOS 44·16·5） */
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ModouUi.dp(host, spec.menuRowHeightDp));
        row.setPadding(ModouUi.dp(host, spec.menuRowPadLDp), ModouUi.dp(host, spec.menuRowPadVDp),
                ModouUi.dp(host, spec.menuRowPadRDp), ModouUi.dp(host, spec.menuRowPadVDp));
        row.setClickable(true);
        return row;
    }

    /** 往行里插图标：M3 左置（leading）、iOS 右置（trailing）；墨读不插 */
    private void addIcon(LinearLayout row, int kind, boolean leading) {
        if (spec.menuItemIconDp <= 0 || kind < 0) return;
        int box = ModouUi.dp(host, spec.menuItemIconDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(box, box);
        lp.leftMargin = leading ? 0 : ModouUi.dp(host, 10);
        lp.rightMargin = leading ? ModouUi.dp(host, 12) : 0;
        row.addView(glyphView(kind), leading ? 0 : row.getChildCount(), lp);
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ModouUi.dp(host, 2);
        lp.rightMargin = ModouUi.dp(host, 2);
        return lp;
    }

    /** 叶子行：标签（+右侧次要值 / ✓）+ 语言专属图标。选中态走 rowBg。 */
    private void addLeafRow(LinearLayout box, Item it) {
        LinearLayout row = row();
        Drawable bg = rowBg(it.checked);
        if (bg != null) row.setBackground(bg);
        // M3 leading icon / iOS trailing icon，墨读不插
        int glyph = glyphFor(it.label);
        if (spec.menuItemIconDp > 0 && glyph >= 0 && spec.menuIconLeading) {
            addIcon(row, glyph, true);
        }
        TextView t = ModouUi.tv(host, it.label, spec.menuTitleSp,
                it.checked ? ui.accent : ui.text);
        t.setTag(LABEL_TAG);
        if (it.checked) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(t, tlp);
        if (it.value != null && !it.value.isEmpty()) {
            TextView v = ModouUi.tv(host, it.value, spec.menuHintSp, ui.sub);
            v.setSingleLine(true);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = ModouUi.dp(host, 14);
            row.addView(v, vlp);
        }
        if (it.checked) {
            TextView ck = ModouUi.tv(host, "✓", spec.menuTitleSp, ui.accent);
            ck.setTypeface(ck.getTypeface(), Typeface.BOLD);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = ModouUi.dp(host, 6);
            row.addView(ck, clp);
        }
        if (spec.menuItemIconDp > 0 && glyph >= 0 && !spec.menuIconLeading) {
            addIcon(row, glyph, false);
        }
        row.setOnClickListener(x -> {
            dismiss();
            if (it.action != null) it.action.run();
        });
        box.addView(row, rowLp());
    }

    /** 父项行：标签 + 副标题（当前值）+ 语言专属图标 / 展开记号；点击展开子菜单 */
    private void addParentRow(LinearLayout box, Item it) {
        LinearLayout row = row();
        Drawable bg = rowBg(false);
        if (bg != null) row.setBackground(bg);
        int glyph = glyphFor(it.label);
        if (spec.menuItemIconDp > 0 && glyph >= 0 && spec.menuIconLeading) {
            addIcon(row, glyph, true);
        }
        LinearLayout textCol = new LinearLayout(host);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView t = ModouUi.tv(host, it.label, spec.menuTitleSp, ui.text);
        textCol.addView(t);
        if (it.sub != null && !it.sub.isEmpty()) {
            TextView s = ModouUi.tv(host, it.sub, spec.menuHintSp, ui.sub);
            s.setPadding(0, ModouUi.dp(host, 3), 0, 0);
            textCol.addView(s);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 右侧「可展开」记号：细线条上下双箭头。iOS 用 disclosure 感更轻的 tint，
        // 与系统菜单一致 —— 粗箭头会跟标题抢视线，这条只提示「里面还有一层」。
        View ud = new View(host);
        ud.setBackground(new ModouGlyph(ModouGlyph.UP_DOWN,
                ModouUi.alpha(ui.text, 0x8C), ModouUi.dp(host, 13), ModouUi.dp(host, 1.2f)));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ModouUi.dp(host, 13), ModouUi.dp(host, 16));
        ulp.leftMargin = ModouUi.dp(host, 12);
        row.addView(ud, ulp);
        if (spec.menuItemIconDp > 0 && glyph >= 0 && !spec.menuIconLeading) {
            // iOS：trailing 图标放在展开记号之后会挤，改为不重复放（展开记号已占右位）
            // —— context menu 里子菜单行的右侧就是 chevron，不再叠第二个图标。
        }

        List<Item> children = it.children;
        row.setOnClickListener(x -> toggleSub(row, children));
        box.addView(row, rowLp());
    }

    private void addDivider(LinearLayout box) {
        View d = new View(host);
        d.setBackground(new ColorDrawable(spec.menuDividerColor));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ModouUi.dp(host, 1)));
        lp.leftMargin = ModouUi.dp(host, spec.menuDividerInsetDp);
        lp.rightMargin = ModouUi.dp(host, spec.menuDividerInsetDp);
        lp.topMargin = ModouUi.dp(host, 5);
        lp.bottomMargin = ModouUi.dp(host, 5);
        box.addView(d, lp);
    }

    /* ---------------- 子菜单（层叠出栈） ---------------- */

    /** 动效时长（0.9.159）：三语言分别由 spec.durMenuIn / Out / Sub 决定，
     *  墨读取值 150 / 130 / 190 与旧常量逐位一致（零回归）。不再用 static final 常量，
     *  时长才能随当前设计语言变化。 */
    private long animSubMs() { return spec.durMenuSub; }
    private long animInMs() { return spec.durMenuIn; }
    private long animOutMs() { return spec.durMenuOut; }

    /**
     * ColorOS 17 的「层叠出栈」，但两张卡是<b>并排</b>而不是叠在一起：
     * <ol>
     *   <li>主卡<b>不消失、不透明</b>，只缩到 95% 并在其上压一层约 9% 的黑 Scrim，
     *       退到后层；</li>
     *   <li>子卡<b>接在主卡右侧</b>（右侧不够就翻到左侧），顶边<b>对齐被点那一行</b>，
     *       从主卡那一侧「抽」出来：96% → 100% + 淡入 + 8dp 位移；</li>
     *   <li>收起时子卡原地淡出缩小，主卡缩放回位、Scrim 淡出。</li>
     * </ol>
     * 同一父行再点 / 点外部 / 返回键 = 出栈回主菜单。
     */
    private void toggleSub(View ownerRow, List<Item> children) {
        try {
            if (ownerRow == null || children == null) return;
            if (subCard != null && subOwner == ownerRow) {
                closeSub();
                return;
            }
            // 换一个父项：旧子卡直接撤掉，不要两张卡同时在场。
            // 此时主卡已经处于「退后」状态，不要把它拉回来再推回去 —— 那会闪。
            boolean switching = subCard != null;
            hardRemoveSub(switching);

            int[] o = new int[2];
            ownerRow.getLocationOnScreen(o);
            int oy = o[1];                 // 被点那一行的**顶边**（屏幕坐标）
            int gap = ModouUi.dp(host, 8);
            int sideGap = ModouUi.dp(host, SIDE_GAP);
            int sw = host.getResources().getDisplayMetrics().widthPixels;
            int sh = host.getResources().getDisplayMetrics().heightPixels;

            View mainHolder = mainBox != null ? mainBox : mainCard;
            int mw = mainCard.getWidth() > 0 ? mainCard.getWidth() : mainW;
            int mh = mainCard.getHeight() > 0 ? mainCard.getHeight() : mainH;
            int mainLeft = mainX;          // 主卡屏幕左/上（place() 记录）
            int mainTop = mainY;
            int mainRight = mainLeft + mw;

            // ---- 竖直：子卡顶边对齐被点那一行的顶边 ----
            int y = Math.max(gap, oy);
            int maxH = Math.min(maxCardH(), sh - gap - y);
            if (maxH < ModouUi.dp(host, 140)) {
                // 行太靠下：这时才允许往上收一点，收完仍保证整张卡在屏内
                y = Math.max(gap, sh - gap - ModouUi.dp(host, 140));
                maxH = sh - gap - y;
            }

            // ---- 水平：接在主卡右侧；右侧放不下就翻到左侧 ----
            // 主卡通常右缘对齐顶栏 ⋮，右边几乎没有余地，所以实际多落在左侧。
            // 两侧都紧时允许略微压住主卡的缝边，但绝不允许整张盖在主卡中央。
            int rightAvail = sw - gap - mainRight - sideGap;
            int leftAvail = mainLeft - sideGap - gap;
            boolean toRight = rightAvail >= leftAvail;
            int avail = Math.max(0, toRight ? rightAvail : leftAvail);
            int maxWpx = Math.min(ModouUi.dp(host, W_SUB_MAX),
                    Math.max(avail, ModouUi.dp(host, W_SUB_MIN)));

            Card c = build(children, ModouUi.dp(host, W_SUB_MIN), maxWpx, maxH, false);
            subCard = c.card;
            subOwner = ownerRow;

            int x = toRight ? mainRight + sideGap : mainLeft - sideGap - c.w;
            x = Math.max(gap, Math.min(x, sw - gap - c.w));

            // 窗口扩展到「主卡 + 子卡」外接矩形：模糊只糊这一块，其余屏幕保持清晰
            int bx = Math.min(mainLeft, x);
            int by = Math.min(mainTop, y);
            int bw = Math.max(mainLeft + mw, x + c.w) - bx;
            int bh = Math.max(mainTop + mh, y + c.h) - by;
            Window w = dialog != null ? dialog.getWindow() : null;
            if (w != null) {
                w.setLayout(bw, bh);
                WindowManager.LayoutParams al = w.getAttributes();
                al.x = bx;
                al.y = by;
                w.setAttributes(al);
            }
            // 主卡在窗口内重新定位（相对外接矩形左上角）
            FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) mainHolder.getLayoutParams();
            mlp.leftMargin = mainLeft - bx;
            mlp.topMargin = mainTop - by;
            mainHolder.setLayoutParams(mlp);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(c.w, c.h);
            lp.leftMargin = x - bx;
            lp.topMargin = y - by;

            // 子卡容器同主卡三态（但 withArrow=false，见上方 build 调用）
            subBox = wrapCard(subCard, lp);
            root.addView(subBox, lp);

            // ---- 主卡退居幕后：保持不透明，只缩一点 + 压一层浅黑 Scrim ----
            // 缩放锚点：Y 放在被点那一行的顶边（缩放时行顶不动，子卡才能一直对齐它），
            // X 放在子卡那一侧的边 —— 这样两张卡之间的 8dp 缝不会被缩放吃掉。
            // 换父项时主卡已经退到位，pivot / 缩放 / Scrim 全部原样保留。
            if (!switching) {
                mainHolder.animate().cancel();
                mainHolder.setPivotX(toRight ? mw : 0f);
                mainHolder.setPivotY(Math.max(0, Math.min(mh, oy - mainTop)));
                mainHolder.animate().scaleX(0.95f).scaleY(0.95f)
                        .setDuration(animSubMs())
                        .setInterpolator(menuInterp(1f, false))
                        .start();
                if (mainScrim != null) {
                    mainScrim.animate().cancel();
                    mainScrim.animate().alpha(1f)
                            .setDuration(animSubMs())
                            .setInterpolator(menuInterp(1f, false))
                            .start();
                }
            }

            // ---- 子卡从主卡那一侧「抽」出来 ----
            subBox.setPivotX(toRight ? 0f : c.w);
            subBox.setPivotY(0f);
            subBox.setAlpha(0f);
            subBox.setScaleX(0.96f);
            subBox.setScaleY(0.96f);
            subBox.setTranslationX(ModouUi.dp(host, toRight ? -8 : 8));
            subBox.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f)
                    .setDuration(animSubMs())
                    .setInterpolator(menuInterp(1f, spec.overshoot > 0f))
                    .start();
        } catch (Throwable e) {
            Diagnostics.capture("menu.toggleSub", e);
            ModouUi.toast(host, "子菜单展开失败：" + e.getClass().getSimpleName());
        }
    }

    /** 返回 / 点外部：子菜单在场就先出栈，否则整卡关闭 */
    private void back() {
        if (subCard != null) closeSub();
        else dismiss();
    }

    /** 收子菜单：子卡原处淡出缩小，主卡缩放回位、Scrim 淡出 */
    private void closeSub() {
        if (subCard == null && subBox == null) return;
        final View v = subBox != null ? subBox : subCard;
        subCard = null;
        subBox = null;
        subOwner = null;

        v.animate().cancel();
        v.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(animOutMs())
                .setInterpolator(menuInterp(1f, false))
                .withEndAction(() -> {
                    if (root != null) root.removeView(v);
                })
                .start();

        restoreMain();
    }

    /** 主卡回位：撤销缩放，Scrim 淡出（卡面从不改 alpha，所以永远不会透出背后内容） */
    private void restoreMain() {
        View mb = mainBox != null ? mainBox : mainCard;
        if (mb == null) return;
        // 子卡收起：窗口缩回主卡尺寸，主卡回到窗口 (0,0)
        Window w = dialog != null ? dialog.getWindow() : null;
        if (w != null) {
            w.setLayout(mainW, mainH);
            WindowManager.LayoutParams al = w.getAttributes();
            al.x = mainX;
            al.y = mainY;
            w.setAttributes(al);
        }
        FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) mb.getLayoutParams();
        mlp.leftMargin = 0;
        mlp.topMargin = 0;
        mb.setLayoutParams(mlp);
        mb.animate().cancel();
        mb.animate().scaleX(1f).scaleY(1f)
                .setDuration(animSubMs())
                .setInterpolator(menuInterp(1f, false))
                .start();
        if (mainScrim != null) {
            mainScrim.animate().cancel();
            mainScrim.animate().alpha(0f)
                    .setDuration(animSubMs())
                    .setInterpolator(menuInterp(1f, spec.overshoot > 0f))
                    .start();
        }
    }

    /** 立即撤掉子卡（换父项时用） */
    private void hardRemoveSub(boolean keepMain) {
        View v = subBox != null ? subBox : subCard;
        if (v != null && root != null) {
            v.animate().cancel();
            root.removeView(v);
        }
        subCard = null;
        subBox = null;
        subOwner = null;
        if (!keepMain) restoreMain();
    }

    /**
     * 一块纯色 squircle —— 只用来做「退后遮罩」和分隔底。
     *
     * <p>为什么不用 {@link GradientDrawable}：它的圆角是四分之一圆弧，卡面走的是
     * {@link ModouShape} 的 Lp 超椭圆，两者在 45° 处差 2~3dp，遮罩四角就会露出一圈
     * 「玻璃没盖住」的亮边 —— 又是一层假边框。这里统一走同一条路径。
     */
    private static final class SquircleFill extends Drawable {
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;

        SquircleFill(int color, float radiusPx) {
            this.radius = radiusPx;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            rect.set(b);
            path.set(ModouShape.rect(rect, radius, false));
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
