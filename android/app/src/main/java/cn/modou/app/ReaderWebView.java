package cn.modou.app;

import android.content.Context;
import android.graphics.Rect;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;

/**
 * ReaderWebView — 阅读壳用的 WebView 子类。
 *
 * 长按选中正文时，WebView 会弹原生 ActionMode（复制 / 分享 / 全选，部分 ROM 还会注入
 * 「AI 写作」）。阅读页自带一条选词工具条（复制 / 净化替换 / 搜索），两条框上下叠在一起
 * 既难看，系统菜单里又偏偏没有阅读场景最需要的「净化替换」。
 *
 * 处理方式（开源阅读同款思路）：**让 ActionMode 正常建立但把菜单清空**，并把内容矩形
 * onGetContentRect 定位到屏幕外——这样浮动菜单渲染在看不见的地方，视觉上等于没有；
 * 关键是选区得以保留（startActionMode 直接返回 null 会让 Chromium 连长按选择一起取消，
 * v0.6.26 已实测踩坑），前端的 window.getSelection() 与自绘工具条照常工作。
 *
 * v0.6.53：只有阅读页需要这套「空菜单 + 选区保留」。书架 / 书源 / 设置本来就
 * user-select:none，压根不需要选区；以前不论在哪都照样建一个 ActionMode（外加一个
 * 定位到 -10000 的浮动工具条），在空白处反复点按/长按时会不断新建这些看不见的原生对象，
 * 越积越卡、甚至把渲染进程拖死。现在非阅读页直接返回 null。
 *
 * 输入框（搜索词、替换为、书源配置、书名等）里长按仍要系统菜单做复制 / 粘贴 / 全选，
 * 因此保留 editing 开关：editing=true 即完全放行。
 */
public class ReaderWebView extends WebView {

    /** 是否处于阅读页（需要「空菜单 + 选区保留」） */
    private boolean readerMode = false;
    /** 是否正在编辑输入框（此时完全放行系统菜单） */
    private boolean editing = false;

    /**
     * 复用一个 Callback：每次 startActionMode 都 new 一个，会在连点时不断产生
     * 只活一瞬间的匿名对象与浮动工具条。
     */
    private final ActionMode.Callback2 emptyCallback = new ActionMode.Callback2() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            menu.clear();
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            menu.clear();
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            /* 空 ActionMode 无需清理 */
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            // 浮动菜单贴着这个矩形弹：挪到屏幕外，视觉上等于没有系统菜单
            outRect.set(-10000, -10000, -9990, -9990);
        }
    };

    public ReaderWebView(Context context) {
        super(context);
    }

    /** 阅读页进出时同步：只有阅读页才需要拦截 ActionMode */
    public void setReaderMode(boolean on) {
        this.readerMode = on;
    }

    /**
     * @param suppress true 压制原生 ActionMode，false 恢复系统菜单
     */
    public void setSuppressActionMode(boolean suppress) {
        this.editing = !suppress;
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback) {
        return startActionMode(callback, ActionMode.TYPE_PRIMARY);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback, int type) {
        // 输入框编辑态：完全放行（复制 / 粘贴 / 全选）
        if (editing) return super.startActionMode(callback, type);
        // 非阅读页：不要选区，也不要那个定位到屏幕外的浮动工具条 —— 直接不建
        if (!readerMode) return null;
        // 阅读页：空菜单 + 选区保留（前端自绘选词工具条接管）
        return super.startActionMode(emptyCallback, type);
    }
}
