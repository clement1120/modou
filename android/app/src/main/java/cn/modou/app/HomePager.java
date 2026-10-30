package cn.modou.app;

import android.content.Context;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.widget.OverScroller;

/**
 * 首页横滑容器：4 个功能页并排，手势左右滑或点底部 tag 原地切换。
 *
 * 自己写而不是用 ViewPager 的原因：项目零第三方依赖、无 androidx，
 * framework 的 ViewPager 没有横向版。实现要点：
 *  - onInterceptTouchEvent 里判「横向位移 > 纵向」才拦截——子页都是纵向
 *    ScrollView / 书架的纵向滚动容器，斜着拖不能误触发翻页；
 *  - 手速够快时松手翻页（VelocityTracker + fling 阈值），否则回弹；
 *  - 切页回调给 ShelfActivity 刷新底部 tag 的高亮。
 */
final class HomePager extends ViewGroup {

    interface OnPageChange {
        void onPage(int index);
    }

    /** 懒加载：第一次滑到/点到某页时才真正创建它 */
    interface LazyPage {
        View create(int index);
    }

    /**
     * 占位槽：页数先按 4 页定下来（手势、底部 tag 的索引都依赖页数），
     * 内容等第一次访问再填进去。
     */
    static View slot(Context c) {
        return new Slots(c);
    }

    private static final class Slots extends android.widget.FrameLayout {
        Slots(Context c) {
            super(c);
        }
    }

    private LazyPage lazy;

    void setLazy(LazyPage l) {
        lazy = l;
    }

    /** 该页如果是占位就现建一个换上（保持索引不变，滚动位置不用重算） */
    private void ensure(int i) {
        if (lazy == null || i < 0 || i >= getChildCount()) return;
        View c = getChildAt(i);
        if (!(c instanceof Slots)) return;
        View v;
        try {
            v = lazy.create(i);
        } catch (Throwable t) {
            v = new android.widget.FrameLayout(getContext());
        }
        if (v == null) return;
        removeViewAt(i);
        addView(v, i, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private final OverScroller scroller;
    private VelocityTracker velocity;
    private int cur = 0;
    private OnPageChange listener;

    /** 拖动起点 */
    private float downX, downY;
    /** 判定拦截后记录的横向起点 */
    private float dragFrom;
    private boolean dragging = false;
    private static final int TOUCH_SLOP = 36; // px：比系统默认更保守——宁可少翻页，不能吞点击

    HomePager(Context c) {
        super(c);
        scroller = new OverScroller(c);
    }

    void setOnPageChange(OnPageChange l) {
        listener = l;
    }

    int page() {
        return cur;
    }

    /** 程序切页（点底部 tag）：smooth=false 直接跳，true 带滑动动画 */
    void setPage(int i, boolean smooth) {
        i = Math.max(0, Math.min(i, getChildCount() - 1));
        ensure(i);
        if (i == cur && !smooth) {
            // 已在该页：仅对齐（可能停留在一半）
            if (!scroller.isFinished()) scroller.abortAnimation();
            scrollTo(i * getWidth(), 0);
            return;
        }
        cur = i;
        int x = i * getWidth();
        if (smooth) {
            int dx = x - getScrollX();
            scroller.startScroll(getScrollX(), 0, dx, 0, 320);
            invalidate();
        } else {
            if (!scroller.isFinished()) scroller.abortAnimation();
            scrollTo(x, 0);
        }
        if (listener != null) listener.onPage(cur);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cw, ch);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(i * w, 0, (i + 1) * w, b - t);
        }
        // 宽度变化（旋转等）：对齐当前页
        if (changed) scrollTo(cur * w, 0);
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragFrom = getScrollX();
                dragging = false;
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                    // 动画中被按下：从当前位置重新判定，避免跳变
                    dragFrom = getScrollX();
                }
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (Math.abs(dx) > TOUCH_SLOP && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true;
                        if (velocity == null) velocity = VelocityTracker.obtain();
                        velocity.clear();
                    }
                }
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragFrom = getScrollX();
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    float dx0 = ev.getX() - downX;
                    float dy0 = ev.getY() - downY;
                    if (Math.abs(dx0) > TOUCH_SLOP && Math.abs(dx0) > Math.abs(dy0) * 1.5f) {
                        dragging = true;
                    } else {
                        return false;
                    }
                }
                float dx = dragFrom - ev.getX() + downX;
                int max = (getChildCount() - 1) * Math.max(1, getWidth());
                int x = Math.max(0, Math.min(max, (int) dx));
                scrollTo(x, 0);
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
            case android.view.MotionEvent.ACTION_UP: {
                if (!dragging) return false;
                dragging = false;
                velocity.computeCurrentVelocity(1000);
                float vx = velocity.getXVelocity();
                int w = Math.max(1, getWidth());
                int target;
                if (Math.abs(vx) > 2400) {
                    // 快速甩动：跟手方向翻一页
                    target = vx < 0 ? cur + 1 : cur - 1;
                    // 但如果当前位置离目标页更近，就落在当前页
                    int nearest = Math.round(getScrollX() / (float) w);
                    if (Math.abs(vx) < 5000 && nearest == target) target = nearest;
                } else {
                    target = Math.round(getScrollX() / (float) w);
                }
                target = Math.max(0, Math.min(target, getChildCount() - 1));
                velocity.recycle();
                velocity = null;
                cur = target;
                int to = target * w;
                scroller.startScroll(getScrollX(), 0, to - getScrollX(), 0, 300);
                invalidate();
                if (listener != null) listener.onPage(cur);
                // 动画走完再把新页建出来：翻页手势本身不能等一次「读库 + 建控件」
                final int built = target;
                postDelayed(() -> ensure(built), 320);
                return true;
            }
            default:
                return super.onTouchEvent(ev);
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            invalidate();
        }
    }
}
