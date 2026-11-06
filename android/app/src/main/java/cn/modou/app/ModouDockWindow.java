package cn.modou.app;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/**
 * 底栏独立窗口（0.9.138）——把玻璃胶囊从 Activity 内容树里搬到一个自己的窗口。
 *
 * <p><b>为什么必须这样</b>：{@code View.setRenderEffect} 只作用于 View 自己的绘制输出，
 * 采不到兄弟节点；{@code Window.setBackgroundBlurRadius} 才是「模糊窗口背后的屏幕」，
 * 但它只在 translucent + floating 窗口上生效，且模糊区域就是窗口 bounds。
 * 所以底栏必须是一个独立窗口，且窗口 bounds 精确等于胶囊那一块 —— 系统在每帧合成时
 * 把它下面的书架画面实时卷积，封面滚动时玻璃一帧一帧跟着变，不需要任何抓屏与刷新调用。
 *
 * <p><b>触摸</b>：{@code FLAG_NOT_TOUCH_MODAL} 让窗口 bounds 之外的触摸透传给下层 Activity
 * （书架照常点、照常滚），窗口内那块胶囊仍由底栏自己处理；{@code FLAG_NOT_FOCUSABLE}
 * 不抢焦点，返回键与输入法仍归 Activity。两者一起设，才不会把整页点死。
 *
 * <p><b>生命周期</b>：Activity 不可见时必须 hide，否则这扇窗口会浮在下一个 Activity 上面；
 * destroy 时 dismiss，避免窗口泄漏。
 */
final class ModouDockWindow {

    private final Activity act;
    private Dialog dialog;
    private View content;
    private int widthPx;
    private int heightPx;
    private int bottomPx;
    private float cornerPx;
    private boolean showing;

    ModouDockWindow(Activity a) {
        act = a;
    }

    /**
     * 建窗口并挂上实时背景模糊。必须在 Activity 的 decor 已经 attach 之后调用
     * （onCreate 里用 {@code decor.post} 延一帧，否则部分 ROM 报 token null）。
     *
     * @param dockContent 胶囊本体（ModouFrost.FrostBox）
     * @param bottomGapPx 胶囊下沿到屏幕底边的距离（含手势条）
     * @param corner      圆角 px；胶囊传短边的一半
     */
    void attach(View dockContent, int wPx, int hPx, int bottomGapPx, float corner) {
        this.content = dockContent;
        this.widthPx = wPx;
        this.heightPx = hPx;
        this.bottomPx = bottomGapPx;
        this.cornerPx = corner;

        if (dialog != null) {
            resize();
            show();
            return;
        }
        if (content.getParent() instanceof ViewGroup) {
            ((ViewGroup) content.getParent()).removeView(content);
        }

        Dialog d = new Dialog(act, R.style.Theme_Modou_Glass);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 常驻件：绝不允许点外面 / 按返回把它关掉
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        d.setContentView(content);
        Window w = d.getWindow();
        if (w == null) return;
        w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0f);
        dialog = d;

        d.show();
        // setLayout / setAttributes 必须在 show() 之后：attach 前拿不到 decor 的实际参数
        resize();
        showing = true;
        ModouGlass.attach(w, cornerPx);
    }

    /** 分屏 / 小窗 / 转屏后重算窗口尺寸与离底距离 */
    void resize() {
        if (dialog == null) return;
        Window w = dialog.getWindow();
        if (w == null) return;
        w.setLayout(widthPx, heightPx);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.y = bottomPx;   // Gravity.BOTTOM 时 y 是「离底边多远」，正值向上
        w.setAttributes(lp);
    }

    void update(int wPx, int hPx, int bottomGapPx, float corner) {
        widthPx = wPx;
        heightPx = hPx;
        bottomPx = bottomGapPx;
        cornerPx = corner;
        resize();
    }

    void show() {
        if (dialog == null || showing) return;
        if (act.isFinishing() || act.isDestroyed()) return;
        try {
            dialog.show();
            resize();
            showing = true;
        } catch (Throwable ignore) {
            // Activity 正在销毁 / token 失效：下一轮 onResume 会重建
        }
    }

    void hide() {
        if (dialog == null || !showing) return;
        try {
            dialog.hide();
        } catch (Throwable ignore) {
        }
        showing = false;
    }

    void dismiss() {
        if (dialog == null) return;
        try {
            dialog.dismiss();
        } catch (Throwable ignore) {
        }
        dialog = null;
        showing = false;
        content = null;
    }
}
