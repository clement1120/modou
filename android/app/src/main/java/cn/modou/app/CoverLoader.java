package cn.modou.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 书籍封面加载（原生书架用）。
 *
 * 为什么要自己写：项目零第三方依赖，没有 Glide/Picasso 可用；而书架是三列网格，
 * 一屏十几个封面，若在 UI 线程同步下载会直接卡死滚动。
 * 所以：位图进 ImagePool（全应用一个池，堆的 1/8）+ 固定 3 线程的线程池 + 回调回主线程；
 * 下载前先读一次 bounds 算采样率，避免把 1000px 的原图当缩略图塞进内存。
 *
 * 占位用「书名首字 + 由书名散列出的底色」，这样没封面（本地书常见）的书
 * 在网格里也能一眼分辨，不会是一片相同的灰块。
 */
final class CoverLoader {

    /** 缩略图兜底宽度：拿不到屏幕信息时用它，正常路径走 {@link #coverTargetPx()} */
    private static final int TARGET_PX = 300;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    /*
     * 位图不再由本类自己缓存：封面与正文插图共用 ImagePool 的**一个**池，
     * 总预算 = 堆上限的 1/8。两份缓存各算 1/8 加起来就是 1/4，
     * 2GB 机上等于 64MB，图文混排的书一翻章就被系统杀掉。
     */

    /** 缓存预算（交给 ImagePool）：堆上限的 1/8，最低 6MB */
    static int memBudget() {
        return ImagePool.budget();
    }

    /** 单张图字节上限 = 池预算的一半：配合下面的宽高上限，正常插图不会被压糊 */
    private static int maxBitmapBytes() {
        return Math.max(1, ImagePool.budget() / 2);
    }

    /**
     * 封面解码目标宽度：按屏幕宽度算，三列网格每格一列。
     *
     * <p>固定 300px 在 1080p 手机上其实偏小（三列每格约 330px，封面会略糊），
     * 在 2K 平板上又浪费；按屏宽推算是「刚好够清楚，且不多占一点内存」。
     */
    static int coverTargetPx() {
        try {
            int sw = android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
            if (sw <= 0) return TARGET_PX;
            int px = Math.round(sw / 3.2f); // 三列 + 边距，除以 3.2 留余量
            return Math.max(240, Math.min(px, 560));
        } catch (Throwable t) {
            return TARGET_PX;
        }
    }

    private CoverLoader() {
    }

    /* 滚动防卡：书架/搜索结果在快速滚动时暂停发起新下载（占位已经先摆上了），
     * 停稳 120ms 再补发。避免「一屏 12 本 → 滚动扫过几十本」时 3 条下载线程
     * 全部被看不见的封面占满，真正要看的封面反而排队。对齐 Legado 的滑动暂停策略。 */
    private static volatile boolean scrolling;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final List<Runnable> pending = new ArrayList<>();
    private static final Runnable STOP = () -> {
        scrolling = false;
        List<Runnable> drain = new ArrayList<>();
        synchronized (pending) {
            drain.addAll(pending);
            pending.clear();
        }
        for (Runnable r : drain) POOL.execute(r);
    };

    /** 滚动容器每帧滚动都调一次：标记「在滚动」并重置 120ms 停稳计时 */
    static void markScroll() {
        scrolling = true;
        H.removeCallbacks(STOP);
        H.postDelayed(STOP, 120);
    }

    /** 手动停用滚动态（列表销毁时调用，防止计时器把已销毁页面的任务再拉起来） */
    static void pause() {
        scrolling = false;
        H.removeCallbacks(STOP);
        List<Runnable> drain = new ArrayList<>();
        synchronized (pending) {
            drain.addAll(pending);
            pending.clear();
        }
        for (Runnable r : drain) POOL.execute(r);
    }

    /**
     * 加载封面。未命中时先摆占位，下载成功后回调主线程替换。
     * key 用 url+name 而不是 url：同一个 url 换了书名（改名）时占位色也要跟着变。
     * 滚动中且未命中缓存：挂到 pending，停稳后再下载。
     */
    static void load(ImageView iv, String url, String name) {
        if (iv == null) return;
        final String key = (url == null || url.isEmpty() ? "ph:" : url) + "|" + (name == null ? "" : name);
        iv.setTag(key);
        iv.setImageDrawable(placeholder(name));

        if (url == null || url.isEmpty()) return;
        Bitmap cached = ImagePool.cover(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        final Runnable task = () -> {
            // 先看磁盘缓存（首屏/列表回滚都省一次网络）；
            // 命中就直接解码，未命中才联网，下完顺手落盘供下次复用。
            File df = diskFile(iv.getContext(), url);
            Bitmap b = null;
            if (df != null && df.exists()) b = fetch("file://" + df.getAbsolutePath());
            if (b == null) {
                byte[] data = download(url);
                if (data != null) {
                    if (df != null) saveDisk(df, data);
                    b = decodeSampled(data, coverTargetPx(), false);
                }
            }
            if (b == null) return;
            ImagePool.putCover(key, b);
            final Bitmap result = b;
            iv.post(() -> {
                // 网格 item 会被复用：取回来时这张 View 可能已经滚动到别的书了
                if (key.equals(iv.getTag())) iv.setImageBitmap(result);
            });
        };
        if (scrolling) {
            synchronized (pending) {
                pending.add(task);
            }
        } else {
            POOL.execute(task);
        }
    }

    /** 取位图（data:URL / http / 本地 file）。包级可见：阅读页的正文插图也用它 */
    static Bitmap fetch(String url) {
        return fetch(url, coverTargetPx(), false);
    }

    /**
     * 取位图并指定解码目标宽度。
     *
     * 封面只要 300px 够看，正文插图要铺满版心（1200px 级），
     * 都走 300px 的话插图会被横向拉伸、糊成一条 —— 所以目标宽度与是否保留透明都要能指定。
     */
    static Bitmap fetch(String url, int targetPx, boolean keepAlpha) {
        byte[] data = download(url);
        if (data == null) return null;
        return decodeSampled(data, targetPx, keepAlpha);
    }

    /**
     * 把 URL 读成原始字节（data: / file: / http(s) 统一走这里）。
     * 网络封面首下载成功后由上层落盘；这里只负责「拿到字节」。
     */
    private static byte[] download(String url) {
        try {
            if (url.startsWith("data:image")) {
                int i = url.indexOf("base64,");
                if (i < 0) return null;
                return android.util.Base64.decode(url.substring(i + 7), android.util.Base64.DEFAULT);
            }
            if (url.startsWith("file://")) {
                // 本地导入的 EPUB 封面落盘后就是这种 URL：比内联 base64 省内存
                java.io.File f = new java.io.File(java.net.URI.create(url));
                java.io.InputStream in = new java.io.FileInputStream(f);
                try {
                    return readAll(in);
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignore) {
                        /* 关闭失败：不致命 */
                    }
                }
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            boolean broken = false;
            try {
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                // 统一头：真实移动端 UA + 同源 Referer。
                // 旧代码自造的 "ModouReader" UA 在防盗链站眼里就是爬虫，封面直接 403；
                // 这里与书源请求共用同一份默认头，成片"封面加载不出来"基本会消失。
                NetClient nc = NetClient.get();
                if (nc != null) {
                    for (java.util.Map.Entry<String, String> e : nc.headersFor(url, null).entrySet()) {
                        if (e.getKey() != null && e.getValue() != null) {
                            c.setRequestProperty(e.getKey(), e.getValue());
                        }
                    }
                } else {
                    c.setRequestProperty("User-Agent", NetClient.DEFAULT_UA);
                }
                InputStream in = c.getInputStream();
                try {
                    return readAll(in);
                } finally {
                    try {
                        in.close();
                    } catch (Exception e) {
                        /* 关闭失败：不致命 */
                    }
                }
            } catch (Exception e) {
                broken = true;
                throw e;
            } finally {
                // 正常读完不 disconnect：连接回池，下一张图省一次握手
                if (broken) {
                    try {
                        c.disconnect();
                    } catch (Throwable ignore) {
                        /* 释放失败也没别的办法 */
                    }
                }
            }
        } catch (Exception e) {
            // 站点证书过期/不受信（笔趣阁 la 这类）→ 换 http 再取一次。
            // 封面不该因为站方懒得续证书就变成一块灰色占位；正文链路已经在
            // ProxyHandler 里做了同样的降级，这里补齐，避免"书能读、封面全空"。
            String plain = ProxyHandler.toHttpUrl(url);
            if (plain != null && ProxyHandler.isSslError(e)) return download(plain);
            return null; // 无封面是常态：占位已经摆好了
        }
    }

    /* ---------------- 磁盘缓存 ----------------
     * 网络封面首下载成功后写进应用 cache 目录（按 URL 哈希命名）。
     * 这样列表回滚、再次进搜书页都不必重新联网，封面「秒出」；
     * cache 目录由系统统一管理，空间紧张时自动回收，不会无限膨胀。
     * 本地 file:/data: 封面本来就在本地，不进磁盘缓存。 */

    private static File diskFile(android.content.Context ctx, String url) {
        if (ctx == null || url == null) return null;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
        try {
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "covers");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            return new java.io.File(dir, "c_" + Integer.toHexString(url.hashCode()) + "_"
                    + Integer.toHexString(url.length()));
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveDisk(java.io.File f, byte[] data) {
        if (f == null) return;
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try {
                out.write(data);
            } finally {
                out.close();
            }
        } catch (Exception e) {
            // 落盘失败不致命：下次还会重新联网
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * 先读 bounds 算采样率再真正解码，**永不把原图整个解进内存**。
     *
     * <p>三层约束，缺一不可：
     * <ol>
     *   <li>按目标宽度缩（封面 340 左右、插图 1000 左右）；</li>
     *   <li>**宽高硬上限**：宽不超过屏宽 1.5 倍、高不超过屏高 2 倍。
     *       800×12000 的长条图按宽度算只会缩 1 倍，这一条才会真正把它按住；</li>
     *   <li>字节上限（池预算的一半）：解出来还超就再加一档，最多三轮。</li>
     * </ol>
     * 采样率一律取 2 的幂 —— BitmapFactory 内部本来就会把任意值向下取整到 2 的幂，
     * 自己先取整才能保证「算出来多大就是多大」。
     */
    private static Bitmap decodeSampled(byte[] data, int targetPx, boolean keepAlpha) {
        if (data == null || data.length <= 0) return null;
        int target = Math.max(64, targetPx);
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int w = o.outWidth;
            int h = o.outHeight;
            if (w <= 0 || h <= 0) return null; // 不是图片（或截断的字节流）

            // 目标宽度留 20% 余量再向下取整：1080 宽的插图配 1000 的目标不该被当成 2 倍缩，
            // 但 1000 宽的封面配 340 的目标确实该缩成 500（再往上取就到 250，糊了）
            int byTarget = (int) Math.floor(w / (target * 1.2f));
            int need = Math.max(byTarget,
                    Math.max(ceilDiv(w, ImagePool.maxW()), ceilDiv(h, ImagePool.maxH())));
            int sample = pow2(Math.max(1, need));

            int maxBytes = maxBitmapBytes();
            Bitmap bm = null;
            for (int round = 0; round < 3; round++) {
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = sample;
                // 封面不需要透明度（RGB_565 省一半内存）；正文插图可能是透明 PNG/WebP，必须留 alpha
                d.inPreferredConfig = keepAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeByteArray(data, 0, data.length, d);
                if (bm == null) return null;
                if (bm.getByteCount() <= maxBytes) return bm;
                try {
                    bm.recycle();
                } catch (Throwable ignore) {
                    /* 回收失败无所谓，GC 会处理 */
                }
                bm = null;
                sample = pow2(sample * 2);
            }
            return null;
        } catch (Throwable e) {
            // OutOfMemoryError 也走这里：宁可这张图不显示，也不能让整个阅读页崩掉
            return null;
        }
    }

    private static int ceilDiv(int a, int b) {
        return b <= 0 ? 1 : (a + b - 1) / b;
    }

    /** 不小于 v 的最小 2 的幂 */
    private static int pow2(int v) {
        int p = 1;
        while (p < v && p < (1 << 30)) p <<= 1;
        return p;
    }

    /** 书名散列出的底色：同一本书每次进来颜色一致 */
    static int tintOf(String name) {
        String s = name == null ? "" : name;
        int h = s.isEmpty() ? 0 : s.hashCode();
        float hue = Math.abs(h % 360) / 360f;
        float[] hsv = {hue, 0.28f, 0.82f};
        return Color.HSVToColor(hsv);
    }

    /** 占位：底色块 + 书名全名（自动换行铺排，放不下截断） */
    static Drawable placeholder(String name) {
        String s = name == null ? "" : name.trim();
        return new NameDrawable(tintOf(s), s.isEmpty() ? "书" : s);
    }

    /** 画「色块 + 书名全名」的 Drawable：按可用宽度逐字折行，行数自适应字号 */
    private static final class NameDrawable extends Drawable {
        private final int bg;
        private final String name;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();

        // 排版结果缓存在 onBoundsChange，draw() 里零分配（避免每帧 new + 正则折行）
        private final GradientDrawable gd = new GradientDrawable();
        private List<String> lines = java.util.Collections.emptyList();
        private float textSize = 0;
        private float lineH = 0;
        private float baseY = 0;

        NameDrawable(int bg, String name) {
            this.bg = bg;
            this.name = name;
            p.setColor(0xE6FFFFFF);
            p.setTextAlign(Paint.Align.CENTER);
            gd.setColor(bg);
        }

        @Override
        protected void onBoundsChange(Rect r) {
            super.onBoundsChange(r);
            int w = r.width(), h = r.height();
            gd.setCornerRadius(w * 0.08f);
            gd.setBounds(r);
            if (w < 10 || h < 10) {
                lines = java.util.Collections.emptyList();
                return;
            }
            // 折行排版：内边距 14% 宽；字号从「单行能放 6 字」起，行数越多越小，
            // 最小到 0.11 倍宽（太小的字不如直接截断）
            float pad = w * 0.14f;
            float maxW = w - pad * 2;
            float size = w / 6f;
            List<String> best = null;
            for (; size >= w * 0.11f; size *= 0.88f) {
                p.setTextSize(size);
                List<String> ls = wrap(maxW);
                if (ls.size() <= Math.max(2, (int) ((h - pad * 2) / (size * 1.32f)))) {
                    best = ls;
                    break;
                }
            }
            if (best == null) { // 字号缩到最小仍未收敛，取最后一档
                p.setTextSize(size);
                best = wrap(maxW);
            }
            lines = best;
            textSize = size;
            lineH = size * 1.32f;
            p.getFontMetrics(fm); // 取当前字号下的 ascent/baseline 信息
            float total = lines.size() * lineH;
            baseY = (h - total) / 2f - h * 0.02f - fm.ascent;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect r = getBounds();
            gd.draw(canvas);
            if (lines.isEmpty()) return;
            p.setTextSize(textSize);
            p.getFontMetrics(fm); // fm 随 textSize 变，仍在 draw 里取（极轻量、无分配）
            float y = baseY;
            for (String ln : lines) {
                canvas.drawText(ln, r.centerX(), y, p);
                y += lineH;
            }
        }

        /** 按当前字号折行：CJK 逐字断行，拉丁词尽量不断在词中；超 8 行截断 */
        private List<String> wrap(float maxW) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            float cw = 0;
            String[] chars = name.split("(?<=.)");
            for (int i = 0; i < chars.length; i++) {
                String ch = chars[i];
                float w = p.measureText(ch);
                if (cw + w > maxW && cur.length() > 0) {
                    // 空格处优先断行：拉丁词不在词中间切开
                    int sp = lastSpace(cur);
                    if (sp > 0 && sp < cur.length() - 1) {
                        out.add(cur.substring(0, sp));
                        String rest = cur.substring(sp + 1);
                        cur.setLength(0);
                        cur.append(rest);
                        cw = p.measureText(rest);
                    } else {
                        out.add(cur.toString());
                        cur.setLength(0);
                        cw = 0;
                    }
                }
                cur.append(ch);
                cw += w;
                if (out.size() >= 8) break;
            }
            if (out.size() < 8 && cur.length() > 0) out.add(cur.toString());
            if (out.size() >= 8) {
                // 截断：最后一行保底留 2 字宽画省略号
                String last = out.get(7);
                while (p.measureText(last + "…") > maxW && last.length() > 1) {
                    last = last.substring(0, last.length() - 1);
                }
                out = out.subList(0, 8);
                out.set(7, last + "…");
            }
            return out;
        }

        private static int lastSpace(StringBuilder sb) {
            for (int i = sb.length() - 1; i >= 0; i--) {
                if (Character.isWhitespace(sb.charAt(i))) return i;
            }
            return -1;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }
}
