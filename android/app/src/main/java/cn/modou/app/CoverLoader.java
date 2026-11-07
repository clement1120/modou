package cn.modou.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
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
        load(iv, url, name, "", 0f);
    }

    /** 极简·纪占位封面额外需要作者与进度；另外两套忽略这两个参数。 */
    static void load(ImageView iv, String url, String name, String author, float progress) {
        if (iv == null) return;
        final String key = (url == null || url.isEmpty() ? "ph:" : url) + "|" + (name == null ? "" : name);
        iv.setTag(key);
        DesignSpec.Lang lang = DesignSpec.current(iv.getContext());
        int accent = lang == DesignSpec.Lang.MONOLITH
                ? DesignSpec.of(iv.getContext()).accent : 0;
        iv.setImageDrawable(placeholder(name, author, progress,
                ModouUi.isDark(iv.getContext()), lang, accent));

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

    /** 无封面书占位底色：每套设计语言各一组低饱和、同色系的纸感色板，按书名哈希稳定取色。
     *  同一本书每次一致；整墙封面的色相收在同一族里，不再「一册一色」地发噪。 */
    private static final int[][] LIGHT_PALETTES = {
            // 墨读：宣纸 / 米纸 / 淡碧 —— 纸墨同族，暖中性（比页面底略深，卡片要立得住）
            {0xFFE6DCC8, 0xFFDED8C6, 0xFFD8E0DA, 0xFFE4D9C6, 0xFFD3DEDB, 0xFFEBE1CE},
            // M3：tonal neutral surface，冷暖交替但压在同一明度带
            {0xFFDED8D2, 0xFFDAD8DF, 0xFFD6DCD9, 0xFFE2DACF, 0xFFD8D9DE, 0xFFDEDCD6},
            // iOS 27：systemFill 灰，几乎无色相，只留一点温度
            {0xFFDCDCE2, 0xFFE0DCD5, 0xFFD8DEE3, 0xFFDEDADB, 0xFFDADBD9, 0xFFE4DFD6}
    };
    private static final int[][] DARK_PALETTES = {
            {0xFF2E2A24, 0xFF2A2823, 0xFF262C29, 0xFF2E2922, 0xFF242B2A, 0xFF322C24},
            {0xFF2B2A2D, 0xFF29282C, 0xFF272C2A, 0xFF2E2B28, 0xFF28292D, 0xFF2C2B29},
            {0xFF2C2C2E, 0xFF2E2B28, 0xFF282B2E, 0xFF2C2A2B, 0xFF2A2B2A, 0xFF302D29}
    };

    static int tintOf(String name, boolean dark, DesignSpec.Lang lang) {
        String s = name == null ? "" : name;
        // & 0x7FFFFFFF 而不是 Math.abs：Integer.MIN_VALUE 取绝对值仍是负数，会数组越界
        int h = (s.isEmpty() ? 0 : s.hashCode()) & 0x7FFFFFFF;
        int idx = lang == DesignSpec.Lang.MATERIAL ? 1 : lang == DesignSpec.Lang.MONOLITH ? 2 : 0;
        int[] pal = (dark ? DARK_PALETTES : LIGHT_PALETTES)[idx];
        return pal[h % pal.length];
    }

    /** 占位：渐变底 + 书脊 + 首字水印（书名由卡片下方单独成行，封面内不再重复） */
    static Drawable placeholder(String name, boolean dark, DesignSpec.Lang lang) {
        return placeholder(name, "", 0f, dark, lang, 0);
    }

    /** 极简·纪走独立绘制：莫兰迪底 + 顶部短横线 + 居中书名 + 作者 + 底部进度条。 */
    static Drawable placeholder(String name, String author, float progress,
                                boolean dark, DesignSpec.Lang lang, int accent) {
        if (lang == DesignSpec.Lang.MONOLITH) {
            return new MonolithDrawable(name, author, accent, progress);
        }
        String s = name == null ? "" : name.trim();
        return new NameDrawable(tintOf(s, dark, lang), s.isEmpty() ? "书" : s, dark);
    }

    private static int lighten(int c, float amt) {
        int a = (c >> 24) & 0xFF, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r = (int) Math.min(255, r + (255 - r) * amt);
        g = (int) Math.min(255, g + (255 - g) * amt);
        b = (int) Math.min(255, b + (255 - b) * amt);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    private static String firstChar(String s) {
        if (s == null || s.isEmpty()) return "书";
        return String.valueOf(s.trim().charAt(0));
    }

    /** 无封面占位：渐变底 + 书脊 + 居中首字水印。
     *  书脊是「这是一本书」的最短视觉暗示，比纯色块更立得住；书名由卡片下方承担，
     *  封面内不再重复文字，避免和标题打架。 */
    private static final class NameDrawable extends Drawable {
        private final int bg;
        private final String ch;
        private final boolean dark;
        private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();
        private final GradientDrawable gd = new GradientDrawable();
        private final Path clip = new Path();
        private final RectF rf = new RectF();
        private float monoSize = 0;
        private float corner = 0;
        private float spineW = 0;
        private float spineX = 0;

        NameDrawable(int bg, String name, boolean dark) {
            this.bg = bg;
            this.ch = firstChar(name);
            this.dark = dark;
            int top = lighten(bg, 0.18f);
            gd.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
            gd.setColors(new int[]{top, bg});
            // 首字压到 15% 左右：是「纹理」不是「文字」，不与封面上的任何信息争读
            mono.setColor(dark ? 0x38FFFFFF : 0x2614171C);
            mono.setTextAlign(Paint.Align.CENTER);
            mono.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            spine.setColor(dark ? 0x1F000000 : 0x0D000000);
            edge.setColor(dark ? 0x1AFFFFFF : 0x12000000);
        }

        @Override
        protected void onBoundsChange(Rect r) {
            super.onBoundsChange(r);
            int w = r.width(), h = r.height();
            corner = w * 0.08f;
            gd.setCornerRadius(corner);
            gd.setBounds(r);
            clip.reset();
            rf.set(r);
            clip.addRoundRect(rf, corner, corner, Path.Direction.CW);
            spineW = w * 0.052f;
            spineX = r.left + spineW;
            if (w < 10 || h < 10) {
                monoSize = 0;
                return;
            }
            monoSize = Math.min(w, h) * 0.50f;
            mono.setTextSize(monoSize);
            // 顶部一层极淡冷光，让平面色块有一点「纸面受光」的体积感
            gloss.setShader(new LinearGradient(r.left, r.top, r.left, r.top + h * 0.55f,
                    dark ? 0x0EFFFFFF : 0x18FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect r = getBounds();
            gd.draw(canvas);
            int w = r.width(), h = r.height();
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawRect(r.left, r.top, r.right, r.top + h * 0.55f, gloss);
            // 书脊：一条比底色略深的窄带 + 右侧一条发丝线
            if (spineW > 0) {
                canvas.drawRect(r.left, r.top, spineX, r.bottom, spine);
                float lw = Math.max(1f, w * 0.0035f);
                canvas.drawRect(spineX, r.top, spineX + lw, r.bottom, edge);
            }
            canvas.restore();
            if (monoSize <= 0) return;
            mono.setTextSize(monoSize);
            mono.getFontMetrics(fm);
            float y = h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(ch, w / 2f, y, mono);
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

    /** 极简·纪（Monolith）无封面占位：莫兰迪底 + 顶部短横线 + 居中书名 + 作者 + 底部进度条。 */
    private static final class MonolithDrawable extends Drawable {
        private static final int[] MORANDI = {0xFFE6DFD3, 0xFFDCE5DF, 0xFFE3DFE8};
        private final String name, author;
        private final int bg, accent;
        private final float progress, density, scaledDensity;
        private final GradientDrawable gd = new GradientDrawable();
        private final RectF rf = new RectF();
        private final Paint topLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint authorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barFg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();
        private StaticLayout title;

        MonolithDrawable(String name, String author, int accent, float progress) {
            this.name = name == null ? "" : name.trim();
            this.author = author == null ? "" : author.trim();
            this.accent = accent;
            this.progress = Math.max(0f, Math.min(1f, progress));
            android.util.DisplayMetrics dm =
                    android.content.res.Resources.getSystem().getDisplayMetrics();
            this.density = dm.density;
            this.scaledDensity = dm.scaledDensity;
            int hash = (this.name.isEmpty() ? 0 : this.name.hashCode()) & 0x7FFFFFFF;
            this.bg = MORANDI[hash % MORANDI.length];
            gd.setColor(bg);
            topLine.setColor(0xFF1A1C23);                 // 暗色短横线
            titlePaint.setColor(0xFF1A1C23);              // 深灰书名
            titlePaint.setTextSize(18f * scaledDensity); // 18sp Bold
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            authorPaint.setColor(0xFF7A8299);             // 中灰作者
            authorPaint.setTextSize(12f * scaledDensity); // 12sp
            authorPaint.setTextAlign(Paint.Align.CENTER);
            barBg.setColor(0x33000000);                   // 半透明底
            barFg.setColor(accent);                       // 进度 = 强调色
        }

        @Override
        protected void onBoundsChange(Rect r) {
            super.onBoundsChange(r);
            int w = r.width();
            gd.setCornerRadius(w * 0.08f);
            gd.setBounds(r);
            String s = name.isEmpty() ? "书" : name;
            int pad = (int) (16 * density);
            int avail = Math.max(1, w - pad * 2);
            title = StaticLayout.Builder.obtain(s, 0, s.length(), titlePaint, avail)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setMaxLines(3)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect r = getBounds();
            int w = r.width(), h = r.height();
            gd.draw(canvas);
            float cx = r.left + w / 2f;
            // 顶部：24dp 宽、2dp 高的暗色短横线
            float lineW = 24 * density, lineH = 2 * density;
            float lineY = r.top + 16 * density;
            rf.set(cx - lineW / 2f, lineY, cx + lineW / 2f, lineY + lineH);
            canvas.drawRoundRect(rf, lineH / 2f, lineH / 2f, topLine);
            // 底部：2dp 高圆角进度条，半透明底 + 强调色进度
            float barH = 2 * density;
            float left = r.left + 16 * density, right = r.right - 16 * density;
            float barY = r.bottom - 14 * density - barH;
            rf.set(left, barY, right, barY + barH);
            canvas.drawRoundRect(rf, barH / 2f, barH / 2f, barBg);
            if (progress > 0f && right > left) {
                rf.set(left, barY, left + (right - left) * progress, barY + barH);
                canvas.drawRoundRect(rf, barH / 2f, barH / 2f, barFg);
            }
            // 作者：12sp 中灰，压在进度条上方
            if (!author.isEmpty()) {
                authorPaint.getFontMetrics(fm);
                canvas.drawText(author, cx, barY - 8 * density - fm.descent, authorPaint);
            }
            // 书名：18sp Bold 深灰，居中且允许换行
            if (title != null) {
                canvas.save();
                canvas.translate(r.left + (w - title.getWidth()) / 2f,
                        r.top + (h - title.getHeight()) / 2f);
                title.draw(canvas);
                canvas.restore();
            }
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
