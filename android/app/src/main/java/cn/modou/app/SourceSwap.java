package cn.modou.app;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 换源器（书源的「换一本同样的书」）。
 *
 * <p>以前换源逻辑全锁在 {@link BookActivity} 里，阅读页想换源只能先跳详情页，麻烦；
 * 而且换源复用了搜书引擎，结果里会混进书名不完全一致的书（搜《花间客传奇》却出《水浒传》）。
 *
 * <p>这里把换源全流程抽成独立类，通过 {@link Host} 接口解耦宿主 Activity，
 * 让<b>书籍详情页</b>和<b>阅读页</b>都能原地弹换源面板。并顺手修了三件事：
 * <ol>
 *   <li><b>严格同名</b>：换源候选书名必须与原书名归一化后相等，否则丢弃——
 *       宽松关联只该留给搜书界面，换源必须「完全一样」。</li>
 *   <li><b>结果缓存</b>：按书存上次换源结果，重开面板固定秒出（不再自动重搜），手动点「重新搜索」才刷新；搜到一半关掉也会保留已搜到的量。</li>
 *   <li><b>复用</b>：进度映射（matchByTitle 等）提级到此处，两个宿主共用。</li>
 * </ol>
 */
final class SourceSwap {

    /** 宿主：提供书记录、颜色、持久化与换源后的刷新动作 */
    interface Host {
        /** 宿主 Activity（ModouUi.sheet 等需要 Activity） */
        Activity getContext();

        /** 主文字色（标题、候选书名） */
        int textColor();

        /** 次要文字色（副标题、提示） */
        int subColor();

        /** 当前书记录（可变，换源时直接改它的字段） */
        JSONObject getBook();

        String getBookId();

        /** 是否本地书（本地书无书源，不能换） */
        boolean isLocalBook();

        /** 确保拿到完整记录（含 sourceUrl / chapters）。返回是否就绪 */
        boolean ensureFullRecord();

        /** 持久化书记录 */
        void saveBook();

        /** 换源前读旧位置（必须在覆盖目录之前调用） */
        OldPos readOldPosition();

        /** 换源后刷新：目录已写回 book，chapters 是新目录；宿主负责定位进度 + 重绘 */
        void afterSourceApplied(OldPos old, JSONArray chapters);

        /** 页面中央轻提示 */
        void centerToast(String s);
    }

    /** 换源前的旧位置：第几章、旧目录共几章、那一章的章名 */
    static final class OldPos {
        int idx = -1;
        int total = 0;
        String title = "";
    }

    /** 一个候选源（同一本书在该源上的入口） */
    static final class SourcePick {
        String sourceUrl;
        String sourceName;
        String bookUrl;
        String name;
        String author;
        String coverUrl;
        /** 该源所属分组（用于面板顶部的分组筛选）；来自 bookSourceGroup 的显示名 */
        String group = SourceGroup.NONE;
    }

    private SourceSwap() {
    }

    /**
     * 弹出换源面板。
     *
     * <p>面板立刻打开：先展示缓存（若有），同时后台按书名搜全部可用源，结果边回边填。
     */
    static void show(Host host) {
        Activity ctx = host.getContext();
        if (host.isLocalBook()) {
            ModouUi.toast(ctx, "本地书无法换源");
            return;
        }
        if (SourceService.get() == null) {
            ModouUi.toast(ctx, "书源服务还没启动，请重开应用");
            return;
        }
        final List<JSONObject> use = searchableSources(ctx);
        if (use.isEmpty()) {
            ModouUi.toast(ctx, "还没有配置搜索规则的书源");
            return;
        }
        host.ensureFullRecord();
        final JSONObject book = host.getBook();
        final String name = book.optString("name", "");
        final String selfSrc = book.optString("sourceUrl", "");
        final String bookId = host.getBookId();

        // 源 URL → 分组显示名（换源结果按此分组筛选）
        final java.util.Map<String, String> groupOf = new java.util.HashMap<>();
        for (JSONObject s : use) {
            String u = s.optString("bookSourceUrl", "");
            if (!u.isEmpty()) groupOf.put(u, SourceGroup.of(s));
        }

        final List<SourcePick> picks = new ArrayList<>();
        final List<SourcePick> display = new ArrayList<>(); // 当前分组下要显示的子集
        // 标题行：左「换源 · 书名」，右「重新搜索」小胶囊（搜索中显示「搜索中…」并禁用）
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView titleTv = ModouUi.tv(ctx, "换源 · " + name, 17, host.textColor());
        titleTv.setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerRow.addView(titleTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView refresh = ModouUi.tv(ctx, "↻ 重新搜索", 12.5f, host.subColor());
        android.graphics.drawable.GradientDrawable rbg = new android.graphics.drawable.GradientDrawable();
        rbg.setCornerRadius(ModouUi.dp(ctx, 13));
        rbg.setColor(ModouUi.alpha(host.subColor(), 0x18));
        refresh.setBackground(rbg);
        refresh.setPadding(ModouUi.dp(ctx, 12), ModouUi.dp(ctx, 5),
                ModouUi.dp(ctx, 12), ModouUi.dp(ctx, 5));
        refresh.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshLp.leftMargin = ModouUi.dp(ctx, 10);
        headerRow.addView(refresh, refreshLp);

        final TextView hint = ModouUi.tv(ctx, "正在按书名搜索 " + use.size() + " 个书源…", 12.5f, host.subColor());
        hint.setPadding(0, ModouUi.dp(ctx, 4), 0, 0);

        // 分组筛选 chip 行：全部 + 结果里出现的各个分组（横向可滚动，不挤压列表）
        final String[] activeGroup = {null}; // null = 全部
        final String[] chipSig = {""};
        final android.widget.HorizontalScrollView chipsScroll = new android.widget.HorizontalScrollView(ctx);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setClipToPadding(false);
        chipsScroll.setPadding(0, ModouUi.dp(ctx, 2), 0, 0);
        final LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, 0, ModouUi.dp(ctx, 8), 0);
        // 必须显式给 WRAP_CONTENT：横向 ScrollView 若把子容器量成「视口宽」，
        // 多出来的 chip 会被裁在右边而不是溢出，表现为「明明还有分组却滑不动」。
        chipsScroll.addView(chips, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 外层是 MaxHeightScroll（竖向）:横滑分组条时明确禁止它抢手势，
        // 否则手指带一点上下偏移，滑动就被判成「翻面板」，chip 条纹丝不动。
        chipsScroll.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return false; // 不消费：仍交给 HorizontalScrollView 自己滚
            }
        });

        // 左右滑结果区 = 切到相邻分组（纵向仍是外层 ScrollView 滚动，两者互不干扰）
        final Runnable[] onSwipe = new Runnable[1];
        final int[] swipeDir = new int[1];
        // 切组时刻：切完内容整体换了一批，行位置全变，紧接着的一下点击基本是误触
        final long[] lastSwipeAt = {0};

        // 结果行直接铺在 LinearLayout 里，交给外层 MaxHeightScroll 统一滚动。
        // 之前用「固定屏高 42% 的 ListView」套在 MaxHeightScroll 内：两层滚动打架，
        // 且 ListView 底部被外层裁掉 —— 表现为「下面明明还有行，却怎么都拉不到」。
        final LinearLayout rowsBox = new LinearLayout(ctx) {
            private float downX, downY;
            private boolean swiping;   // 已切组：本次手势锁定，不再重复切
            private boolean canceled;  // 已取消行点击：抬手不会误开书源

            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        swiping = false;
                        canceled = false;
                        break;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        if (swiping) return true;
                        float dx = e.getX() - downX;
                        float dy = e.getY() - downY;
                        final int slop = android.view.ViewConfiguration
                                .get(getContext()).getScaledTouchSlop();
                        // 第一段：只要横向压过纵向一点点，就先取消行点击。
                        // 以前只在滑够 44dp 才取消，于是「随手一抹」没到阈值，
                        // 抬手仍算一次点击 —— 这是误开书源的主要来源。
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                            if (!canceled) {
                                canceled = true;
                                android.view.MotionEvent cancel =
                                        android.view.MotionEvent.obtain(e);
                                cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                                super.dispatchTouchEvent(cancel);
                                cancel.recycle();
                            }
                            // 第二段：滑够 44dp 才真正切组，短促一划只吃掉点击、不动分组
                            if (Math.abs(dx) > ModouUi.dp(getContext(), 44)) {
                                swiping = true;
                                swipeDir[0] = dx < 0 ? 1 : -1; // 左滑 → 下一个分组
                                if (onSwipe[0] != null) onSwipe[0].run();
                            }
                            return true;
                        }
                        break;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        swiping = false;
                        canceled = false;
                        break;
                }
                return super.dispatchTouchEvent(e);
            }
        };
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        // 面板高度别跟着结果条数跳：切到只有一两条的分组时，整块会突然缩成一小条，
        // 手指底下的内容瞬间移位，很容易点到外面把面板关了。留一个最小高度顶住。
        // 上限按 ModouUi.sheet 的限高算（内容区 = 屏高 62% - 76dp），再扣掉标题/状态/chip
        // 行约 100dp —— 否则「只有两条也顶出滚动条」，反而更难滚。
        int sh = ctx.getResources().getDisplayMetrics().heightPixels;
        rowsBox.setMinimumHeight(Math.max(ModouUi.dp(ctx, 180),
                (int) (sh * 0.62f) - ModouUi.dp(ctx, 176)));
        // 关键：让结果区自己在空白处也「吃」下 DOWN。
        // 只有一两条结果时下方全是空白，DOWN 落不到任何子行上，ViewGroup 默认返回 false，
        // 事件链当场断掉 —— 后续 MOVE 根本不会回到 dispatchTouchEvent，横滑自然没反应。
        rowsBox.setClickable(true);
        rowsBox.setOnClickListener(v -> { /* 空白区只用来接横滑手势，不做任何事 */ });
        final int[] rendered = {0};

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(headerRow);
        box.addView(hint);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = ModouUi.dp(ctx, 8);
        chipLp.bottomMargin = ModouUi.dp(ctx, 8);
        box.addView(chipsScroll, chipLp);
        box.addView(rowsBox);

        // 标题已在 headerRow 里自绘，这里传空标题避免重复
        final android.app.Dialog d = ModouUi.sheetCompact(ctx, "", box);

        final int[] done = {0};
        final boolean[] searching = {false};
        final boolean[] alive = {true};

        // 按 activeGroup 过滤出 display（当前分组下要显示的候选）
        final Runnable fillDisplay = () -> {
            display.clear();
            for (SourcePick p : picks) {
                if (activeGroup[0] == null || activeGroup[0].equals(p.group)) display.add(p);
            }
        };
        // 全量重绘结果行（切换分组 / 读缓存时）
        final Runnable renderFull = () -> {
            rowsBox.removeAllViews();
            for (SourcePick p : display) {
                rowsBox.addView(rowView(ctx, p, host, () -> {
                    if (android.os.SystemClock.uptimeMillis() - lastSwipeAt[0] < 350) return;
                    d.dismiss();
                    applySource(host, p);
                }));
            }
            rendered[0] = display.size();
        };
        // 0.9.146：切分组不再是「闪一下换一批」。重绘后让结果区从滑入方向进场：
        // dir = 1 表示左滑（下一个分组，新内容从右侧进来），-1 表示右滑。
        // 位移控制在 30dp 内 —— 再多会露出上下相邻行，像整块面板在动。
        final java.util.function.IntConsumer swapAnim = dir -> {
            rowsBox.animate().cancel();
            rowsBox.setTranslationX(dir * ModouUi.dp(ctx, 30));
            rowsBox.setAlpha(0.3f);
            rowsBox.animate()
                    .translationX(0f).alpha(1f)
                    .setDuration(190)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        };

        // 增量追加（搜索持续回结果时只补新行，避免每来一个源就整表重绘）
        final Runnable renderAppend = () -> {
            for (int i = rendered[0]; i < display.size(); i++) {
                SourcePick p = display.get(i);
                rowsBox.addView(rowView(ctx, p, host, () -> {
                    if (android.os.SystemClock.uptimeMillis() - lastSwipeAt[0] < 350) return;
                    d.dismiss();
                    applySource(host, p);
                }));
            }
            rendered[0] = display.size();
        };

        // 分组顺序（null = 「全部」），左右滑时按它找相邻项
        final java.util.List<String> groupOrder = new ArrayList<>();
        // 切组后把外层滚动区拉回顶部：内容换了一批，停在原来的滚动位置会像「空了一块」。
        // 用立即 scrollTo 而不是平滑滚动——切组发生在手指还没抬起时，动画会和手势打架。
        final Runnable scrollRowsTop = () -> {
            android.view.ViewParent p = rowsBox.getParent();
            while (p != null && !(p instanceof android.widget.ScrollView)) p = p.getParent();
            if (p != null) ((android.widget.ScrollView) p).scrollTo(0, 0);
        };

        // 重建分组 chip 行（只在分组集合 / 选中项变化时重建，避免每次回调都闪）
        final Runnable[] refreshChips = new Runnable[1];
        refreshChips[0] = () -> {
            java.util.LinkedHashSet<String> gs = new java.util.LinkedHashSet<>();
            for (SourcePick p : picks) if (p.group != null && !p.group.isEmpty()) gs.add(p.group);
            String sig = String.valueOf(activeGroup[0]) + "|" + gs;
            if (sig.equals(chipSig[0])) return;
            chipSig[0] = sig;
            // 顺序与 chip 行一致：全部 → 各分组
            groupOrder.clear();
            groupOrder.add(null);
            for (String g : gs) groupOrder.add(g);
            chips.removeAllViews();
            chips.addView(groupChip(ctx, "全部", activeGroup[0] == null, host, () -> {
                int from = groupOrder.indexOf(activeGroup[0]);
                activeGroup[0] = null;
                fillDisplay.run();
                renderFull.run();
                scrollRowsTop.run();
                refreshChips[0].run();
                // 回到「全部」是往前走，内容从左边滑回来；已经就在「全部」则不动
                swapAnim.accept(from > 0 ? -1 : 0);
            }));
            for (String g : gs) {
                final String gg = g;
                chips.addView(groupChip(ctx, g, g.equals(activeGroup[0]), host, () -> {
                    int from = groupOrder.indexOf(activeGroup[0]);
                    int to = groupOrder.indexOf(gg);
                    activeGroup[0] = gg;
                    fillDisplay.run();
                    renderFull.run();
                    scrollRowsTop.run();
                    refreshChips[0].run();
                    // 点 chip 也按方向进场，和横滑保持一致的动效语言
                    swapAnim.accept(Integer.compare(to, from));
                }));
            }
            // 选中的 chip 只在看不见时才滚进来：每次重建都强行滚到最左，
            // 会把前面几个分组推出视口，左边留半截 chip，看着像渲染坏了。
            chips.post(() -> {
                int pad = ModouUi.dp(ctx, 12);
                for (int i = 0; i < chips.getChildCount(); i++) {
                    View c = chips.getChildAt(i);
                    if (!Boolean.TRUE.equals(c.getTag())) continue;
                    int vx = chipsScroll.getScrollX();
                    int vw = chipsScroll.getWidth();
                    int l = c.getLeft();
                    int r = c.getRight();
                    if (l < vx) chipsScroll.smoothScrollTo(Math.max(0, l - pad), 0);
                    else if (r > vx + vw) chipsScroll.smoothScrollTo(r - vw + pad, 0);
                    break;
                }
            });
        };

        // 左右滑结果区 → 直接切到相邻分组（不循环，到头给一句提示）
        onSwipe[0] = () -> {
            int cur = 0;
            for (int i = 0; i < groupOrder.size(); i++) {
                String g = groupOrder.get(i);
                if (g == null ? activeGroup[0] == null : g.equals(activeGroup[0])) {
                    cur = i;
                    break;
                }
            }
            int nx = cur + swipeDir[0];
            if (nx < 0 || nx >= groupOrder.size()) {
                host.centerToast(swipeDir[0] > 0 ? "已经是最后一个分组" : "已经是第一个分组");
                return;
            }
            activeGroup[0] = groupOrder.get(nx);
            lastSwipeAt[0] = android.os.SystemClock.uptimeMillis();
            fillDisplay.run();
            renderFull.run();
            scrollRowsTop.run();
            refreshChips[0].run();
            // 0.9.146：左滑（下一个分组）新内容从右侧进，右滑从左侧进
            swapAnim.accept(swipeDir[0]);
        };

        // 重新搜索：清空 → 搜全部源 → 实时填；搜完落盘（下次固定显示这次的量）
        final Runnable startSearch = () -> {
            searching[0] = true;
            picks.clear();
            display.clear();
            rowsBox.removeAllViews();
            rendered[0] = 0;
            activeGroup[0] = null; // 重新搜索回到「全部」
            chipSig[0] = "";
            refreshChips[0].run();
            done[0] = 0;
            refresh.setText("搜索中…");
            refresh.setEnabled(false);
            refresh.setAlpha(0.55f);
            hint.setText("正在按书名搜索 " + use.size() + " 个书源…");
            SourceSearchPool.start(ctx, use, name, 1, new SourceSearchPool.Listener() {
                @Override
                public void onSource(JSONObject src, JSONObject res) {
                    done[0]++;
                    if (res != null && res.optBoolean("ok", false)) {
                        collectPicks(res, selfSrc, name, picks, groupOf);
                        if (alive[0]) {
                            fillDisplay.run();
                            renderAppend.run();
                            refreshChips[0].run();
                        }
                    }
                    if (alive[0]) hint.setText("已搜 " + done[0] + "/" + use.size()
                            + " 个书源 · 找到 " + picks.size() + " 个来源");
                }

                @Override
                public void onFinished(int total, int failed) {
                    searching[0] = false;
                    refresh.setText("↻ 重新搜索");
                    refresh.setEnabled(true);
                    refresh.setAlpha(1f);
                    if (picks.isEmpty()) {
                        hint.setText("没找到其他书源的同一本书（已搜 " + total + " 个书源）");
                    } else {
                        hint.setText("共 " + picks.size() + " 个来源，点一个即可切换");
                    }
                    saveCache(ctx, bookId, picks);
                }
            });
        };
        refresh.setOnClickListener(v -> {
            if (searching[0]) return; // 搜索中不让重复触发
            startSearch.run();
        });

        // 先读缓存：只要有缓存就固定显示「上次搜到的量」，绝不自动重搜；
        // 只有从没搜过（无缓存）才自动搜一次。刷新一律靠上面的「重新搜索」按钮。
        if (loadCache(ctx, bookId, selfSrc, name, picks)) {
            fillDisplay.run();
            renderFull.run();
            refreshChips[0].run();
            hint.setText("上次搜到 " + picks.size() + " 个来源（点「重新搜索」刷新）");
        } else {
            startSearch.run();
        }

        // 关掉面板时把已搜到的量落盘：搜一半退出去，下次打开也是固定的这些，不会从头来
        d.setOnDismissListener((ignored) -> {
            alive[0] = false;
            if (!picks.isEmpty()) saveCache(ctx, bookId, picks);
        });
    }

    /** 换源候选的一行：源名（主）+ 书名 · 作者（次），整行可点 */
    private static View rowView(Activity ctx, SourcePick p, Host host, Runnable onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(ModouUi.dp(ctx, 6), ModouUi.dp(ctx, 11),
                ModouUi.dp(ctx, 6), ModouUi.dp(ctx, 11));
        TextView n = ModouUi.tv(ctx, p.sourceName, 14, host.textColor());
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(n);
        String sub = p.name + (p.author.isEmpty() ? "" : " · " + p.author);
        TextView s = ModouUi.tv(ctx, sub, 12, host.subColor());
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        s.setPadding(0, ModouUi.dp(ctx, 3), 0, 0);
        row.addView(s);
        row.setClickable(true);
        row.setOnClickListener(x -> onClick.run());
        return row;
    }

    /** 分组筛选 chip：选中态深底粗字、未选中态浅底副色字；点击回调执行过滤 */
    private static TextView groupChip(Activity ctx, String label, boolean on, Host host, Runnable onClick) {
        TextView v = ModouUi.tv(ctx, label, 12.5f, on ? host.textColor() : host.subColor());
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(ModouUi.dp(ctx, 15));
        bg.setColor(ModouUi.alpha(on ? host.textColor() : host.subColor(), on ? 0x2E : 0x12));
        v.setBackground(bg);
        v.setGravity(android.view.Gravity.CENTER);
        v.setMinHeight(ModouUi.dp(ctx, 30));
        v.setPadding(ModouUi.dp(ctx, 14), ModouUi.dp(ctx, 6),
                ModouUi.dp(ctx, 14), ModouUi.dp(ctx, 6));
        if (on) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        v.setTag(on); // 选中态打标：分组条溢出时据此把高亮项滚进视口
        v.setOnClickListener(x -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = ModouUi.dp(ctx, 8);
        v.setLayoutParams(lp);
        return v;
    }

    /** 可在搜索里用的书源（启用 + 配了搜索规则） */
    private static List<JSONObject> searchableSources(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        try {
            String s = ModouStore.get(ctx).all("sources");
            if (s == null || s.isEmpty()) return out;
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (!o.optBoolean("enabled", true)) continue;
                if (o.optString("searchUrl", "").trim().isEmpty()) continue;
                out.add(o);
            }
        } catch (Exception ignore) {
            /* 读不到就当没有 */
        }
        return out;
    }

    /**
     * 把一个源的结果并进候选列表。
     *
     * <p><b>换源严格同名</b>：候选书名必须与原书名归一化后一致才保留，否则整条丢弃——
     * 这就是「搜《花间客传奇》却出《水浒传》」的根因修复点。跳过当前源自己、同源去重。
     */
    static void collectPicks(JSONObject res, String selfSrc, String bookName, List<SourcePick> out,
                             java.util.Map<String, String> groupOf) {
        JSONArray groups = res.optJSONArray("groups");
        if (groups == null) return;
        String key = normBookName(bookName);
        if (key.isEmpty()) return;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            JSONArray hits = g.optJSONArray("hits");
            if (hits == null) continue;
            for (int j = 0; j < hits.length(); j++) {
                JSONObject hit = hits.optJSONObject(j);
                if (hit == null) continue;
                JSONObject b = hit.optJSONObject("book");
                if (b == null) continue;
                String nm = b.optString("name", "");
                if (!normBookName(nm).equals(key)) continue; // 换源：书名必须完全一样
                SourcePick p = new SourcePick();
                p.sourceUrl = hit.optString("sourceUrl", "");
                p.sourceName = hit.optString("sourceName", "");
                p.bookUrl = b.optString("bookUrl", "");
                p.name = nm;
                p.author = b.optString("author", "");
                p.coverUrl = b.optString("coverUrl", "");
                String grp = groupOf == null ? null : groupOf.get(p.sourceUrl);
                p.group = grp == null || grp.isEmpty() ? SourceGroup.NONE : grp;
                if (p.sourceUrl.isEmpty() || p.bookUrl.isEmpty()) continue;
                if (p.sourceUrl.equals(selfSrc)) continue; // 当前源自己不算「换源」
                boolean dup = false;
                for (SourcePick q : out) {
                    if (q.sourceUrl.equals(p.sourceUrl) && q.bookUrl.equals(p.bookUrl)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) out.add(p);
            }
        }
    }

    /** 应用某个候选源：写回书记录 → 拉详情/目录 → 交给宿主刷新 */
    static void applySource(Host host, SourcePick p) {
        Activity ctx = host.getContext();
        host.ensureFullRecord();
        final OldPos old = host.readOldPosition();
        final JSONObject book = host.getBook();
        try {
            book.put("sourceUrl", p.sourceUrl);
            book.put("sourceName", p.sourceName);
            book.put("bookUrl", p.bookUrl);
            book.put("name", p.name);
            book.put("author", p.author);
            book.put("coverUrl", p.coverUrl);
            host.saveBook();
        } catch (Exception e) {
            ModouUi.toast(ctx, "切换书源失败：" + e.getMessage());
            return;
        }
        ModouUi.toast(ctx, "已切换书源，正在刷新…");
        final SourceService svc = SourceService.get();
        if (svc == null) {
            ModouUi.toast(ctx, "书源服务未启动");
            return;
        }
        svc.info(book, new SourceService.Callback() {
            @Override
            public void onDone(JSONObject r) {
                if (r != null && r.optBoolean("ok", false)) applyInfo(book, r);
                svc.toc(book, new SourceService.Callback() {
                    @Override
                    public void onDone(JSONObject r2) {
                        TaskPools.ui(() -> {
                            try {
                                if (r2 != null && r2.optBoolean("ok", false)) {
                                    JSONArray chs = r2.optJSONArray("chapters");
                                    if (chs != null && chs.length() > 0) {
                                        book.put("chapters", chs);
                                        JSONObject last = chs.optJSONObject(chs.length() - 1);
                                        if (last != null) {
                                            book.put("lastChapter", last.optString("name", ""));
                                        }
                                    }
                                }
                                book.put("updatedAt", System.currentTimeMillis());
                                host.saveBook();
                                host.afterSourceApplied(old, book.optJSONArray("chapters"));
                            } catch (Exception e) {
                                ModouUi.toast(ctx, "更新目录失败：" + e.getMessage());
                            }
                        });
                    }
                });
            }
        });
    }

    /** 把 info 结果里有的字段写回 book（空字段不覆盖） */
    private static void applyInfo(JSONObject book, JSONObject r) {
        putIf(book, r, "kind");
        putIf(book, r, "lastChapter");
        putIf(book, r, "intro");
        putIf(book, r, "coverUrl");
        putIf(book, r, "author");
        putIf(book, r, "name");
    }

    private static void putIf(JSONObject book, JSONObject r, String k) {
        if (r.has(k)) {
            String v = r.optString(k, "");
            if (!v.isEmpty()) {
                try {
                    book.put(k, v);
                } catch (Exception ignore) {
                    /* 单字段失败不影响整体 */
                }
            }
        }
    }

    /* ---------------- 换源结果缓存 ---------------- */

    /** 读缓存；有缓存则并入 picks 并返回 true（调用方决定 hint 文案与是否自动重搜） */
    private static boolean loadCache(Context ctx, String bookId, String selfSrc, String bookName,
                                     List<SourcePick> picks) {
        try {
            String s = ModouStore.get(ctx).get("swapCache", bookId);
            if (s == null) return false;
            JSONObject c = new JSONObject(s);
            JSONArray ps = c.optJSONArray("picks");
            if (ps == null || ps.length() == 0) return false;
            int key = normBookName(bookName).hashCode();
            for (int i = 0; i < ps.length(); i++) {
                JSONObject o = ps.optJSONObject(i);
                if (o == null) continue;
                SourcePick p = new SourcePick();
                p.sourceUrl = o.optString("sourceUrl", "");
                p.sourceName = o.optString("sourceName", "");
                p.bookUrl = o.optString("bookUrl", "");
                p.name = o.optString("name", "");
                p.author = o.optString("author", "");
                p.coverUrl = o.optString("coverUrl", "");
                p.group = o.optString("group", SourceGroup.NONE);
                if (p.sourceUrl.isEmpty() || p.bookUrl.isEmpty()) continue;
                if (p.sourceUrl.equals(selfSrc)) continue;
                if (normBookName(p.name).hashCode() != key) continue; // 二次同名校验
                boolean dup = false;
                for (SourcePick q : picks) {
                    if (q.sourceUrl.equals(p.sourceUrl) && q.bookUrl.equals(p.bookUrl)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) picks.add(p);
            }
            if (!picks.isEmpty()) return true;
        } catch (Exception ignore) {
            /* 缓存坏了就当没有 */
        }
        return false;
    }

    private static void saveCache(Context ctx, String bookId, List<SourcePick> picks) {
        if (picks.isEmpty()) return;
        try {
            JSONObject c = new JSONObject();
            c.put("id", bookId); // 作为 swapCache 表主键（pkOf 默认返回 id）
            c.put("ts", System.currentTimeMillis());
            JSONArray ps = new JSONArray();
            for (SourcePick p : picks) {
                JSONObject o = new JSONObject();
                o.put("sourceUrl", p.sourceUrl);
                o.put("sourceName", p.sourceName);
                o.put("bookUrl", p.bookUrl);
                o.put("name", p.name);
                o.put("author", p.author);
                o.put("coverUrl", p.coverUrl);
                o.put("group", p.group == null ? SourceGroup.NONE : p.group);
                ps.put(o);
            }
            c.put("picks", ps);
            ModouStore.get(ctx).put("swapCache", c.toString());
        } catch (Exception ignore) {
            /* 存不上不影响换源本身 */
        }
    }

    /**
     * 书名归一化（换源同名判定的唯一标准）：去书名号/空白/标点，只留汉字字母数字，转小写。
     *
     * <p>这样「 花间客传奇 」「《花间客传奇》」「花间客传奇（全本）」都归一成一串，
     * 但《水浒传》之类完全不同名的书一定判不相等——满足「换源必须完全一样」。
     */
    static String normBookName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[《》<>「」『』“”\"'’‘\\s]", "");
        s = s.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fff]", "");
        return s.toLowerCase().trim();
    }

    /* ---------------- 进度映射（两宿主共用，原在 BookActivity） ---------------- */

    /**
     * 章名匹配，三重打分取最高：
     * <ol>
     *   <li>归一化后完全一致 = 100；</li>
     *   <li>两边都带「第 N 章」且 N 相同 = 92；</li>
     *   <li>编辑距离相似度：1 - lev / 较长长度，≥72 才算命中。</li>
     * </ol>
     */
    static int matchByTitle(String oldTitle, JSONArray chs) {
        String key = normChapterName(oldTitle);
        if (key.isEmpty()) return -1;
        int oldNo = chapterNo(oldTitle);
        int n = chs == null ? 0 : chs.length();
        int best = -1, bestScore = 0;
        for (int i = 0; i < n; i++) {
            JSONObject c = chs.optJSONObject(i);
            if (c == null) continue;
            String raw = c.optString("name", "");
            String t = normChapterName(raw);
            int sc = 0;
            if (!t.isEmpty() && t.equals(key)) {
                sc = 100;
            } else if (oldNo > 0) {
                int no = chapterNo(raw);
                if (no == oldNo) sc = 92;
            }
            if (sc < 92 && !t.isEmpty() && !key.isEmpty()) {
                int sim = similarity(key, t);
                if (sim > sc) sc = sim;
            }
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        return bestScore >= 72 ? best : -1;
    }

    /** 取「第 N 章」里的 N；中文数字只处理常见写法，认不出返回 -1 */
    private static int chapterNo(String raw) {
        if (raw == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第\\s*([0-9]+|[零一二三四五六七八九十百千两]+)\\s*[章节回卷集篇部]")
                .matcher(raw);
        if (!m.find()) return -1;
        String g = m.group(1);
        try {
            if (g.matches("[0-9]+")) return Integer.parseInt(g);
            return cnNum(g);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int cnNum(String s) {
        String digits = "零一二三四五六七八九";
        int total = 0, section = 0, number = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = digits.indexOf(c);
            if (c == '两') d = 2;
            if (d >= 0) {
                number = d;
            } else if (c == '十') {
                section += (number == 0 ? 1 : number) * 10;
                number = 0;
            } else if (c == '百') {
                section += (number == 0 ? 1 : number) * 100;
                number = 0;
            } else if (c == '千') {
                section += (number == 0 ? 1 : number) * 1000;
                number = 0;
            } else {
                return -1;
            }
        }
        total = section + number;
        return total <= 0 ? -1 : total;
    }

    /** 归一化后的相似度（0~100）：编辑距离 / 较长串长度 */
    private static int similarity(String a, String b) {
        int la = Math.min(a.length(), 64), lb = Math.min(b.length(), 64);
        String x = a.substring(0, la), y = b.substring(0, lb);
        int[] prev = new int[lb + 1], cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            char ci = x.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = ci == y.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        int d = prev[lb];
        int m = Math.max(la, lb);
        return m == 0 ? 0 : Math.round((1f - (float) d / m) * 100);
    }

    /**
     * 章名归一化：去掉「第 12 章」「第100节 / 回 / 卷」前缀、括号注释、空白与标点，
     * 只保留汉字/字母/数字。两个源的同一章常常只差这些噪音，剥掉就能对上。
     */
    static String normChapterName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[（(【\\[].{0,20}?[）)】\\]]", " ");
        s = s.replaceAll("^\\s*第\\s*[0-9一二三四五六七八九十百千零两]+\\s*[章节回卷集篇部][\\s:：.、·-]*", "");
        s = s.replaceAll("(求月票|求推荐票?|加更|第[0-9一二三四五六七八九十]+更|最新章节|本章完|大章)", " ");
        s = s.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fff]", "");
        return s.trim();
    }
}
