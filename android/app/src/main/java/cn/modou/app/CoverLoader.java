package cn.modou.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 书籍封面加载（原生书架用）。
 *
 * 为什么要自己写：项目零第三方依赖，没有 Glide/Picasso 可用；而书架是三列网格，
 * 一屏十几个封面，若在 UI 线程同步下载会直接卡死滚动。
 * 所以：内存 LruCache（8MB）+ 固定 3 线程的线程池 + 回调回主线程；
 * 下载前先读一次 bounds 算采样率，避免把 1000px 的原图当缩略图塞进内存。
 *
 * 占位用「书名首字 + 由书名散列出的底色」，这样没封面（本地书常见）的书
 * 在网格里也能一眼分辨，不会是一片相同的灰块。
 */
final class CoverLoader {

    private static final int TARGET_PX = 300; // 缩略图目标宽度，够三列网格用
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private CoverLoader() {
    }

    /**
     * 加载封面。未命中时先摆占位，下载成功后回调主线程替换。
     * key 用 url+name 而不是 url：同一个 url 换了书名（改名）时占位色也要跟着变。
     */
    static void load(ImageView iv, String url, String name) {
        if (iv == null) return;
        final String key = (url == null || url.isEmpty() ? "ph:" : url) + "|" + (name == null ? "" : name);
        iv.setTag(key);
        iv.setImageDrawable(placeholder(name));

        if (url == null || url.isEmpty()) return;
        Bitmap cached = MEM.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        POOL.execute(() -> {
            Bitmap b = fetch(url);
            if (b == null) return;
            MEM.put(key, b);
            iv.post(() -> {
                // 网格 item 会被复用：取回来时这张 View 可能已经滚动到别的书了
                if (key.equals(iv.getTag())) iv.setImageBitmap(b);
            });
        });
    }

    /** data: URL 直接解码；http(s) 走连接（带超时与重定向） */
    /** 取位图（data:URL 或 http 地址）。包级可见：阅读页的正文插图也用它 */
    static Bitmap fetch(String url) {
        return fetch(url, TARGET_PX, false);
    }

    /**
     * 取位图并指定解码目标宽度。
     *
     * 封面只要 300px 够看，正文插图要铺满版心（1200px 级），
     * 都走 300px 的话插图会被横向拉伸、糊成一条 —— 所以目标宽度与是否保留透明都要能指定。
     */
    static Bitmap fetch(String url, int targetPx, boolean keepAlpha) {
        try {
            if (url.startsWith("data:image")) {
                int i = url.indexOf("base64,");
                if (i < 0) return null;
                byte[] bs = android.util.Base64.decode(url.substring(i + 7), android.util.Base64.DEFAULT);
                return decodeSampled(bs, targetPx, keepAlpha);
            }
            if (url.startsWith("file://")) {
                // 本地导入的 EPUB 封面落盘后就是这种 URL：比内联 base64 省内存，
                // 也不必每次解码时先展开几 MB 的字符串
                java.io.File f = new java.io.File(java.net.URI.create(url));
                java.io.InputStream in = new java.io.FileInputStream(f);
                try {
                    return decodeSampled(readAll(in), targetPx, keepAlpha);
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
            try {
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ModouReader");
                InputStream in = c.getInputStream();
                try {
                    return decodeSampled(readAll(in), targetPx, keepAlpha);
                } finally {
                    try {
                        in.close();
                    } catch (Exception e) {
                        /* 关闭失败：不致命 */
                    }
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null; // 无封面是常态：占位已经摆好了
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** 先读 bounds 算采样率再真正解码，避免大图直接进内存 */
    private static Bitmap decodeSampled(byte[] data, int targetPx, boolean keepAlpha) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int w = o.outWidth;
            o.inSampleSize = w > 0 ? Math.max(1, Math.round((float) w / Math.max(1, targetPx))) : 1;
            o.inJustDecodeBounds = false;
            // 封面不需要透明度（省一半内存）；正文插图可能是透明 PNG/WebP，必须留 alpha
            o.inPreferredConfig = keepAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
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

        NameDrawable(int bg, String name) {
            this.bg = bg;
            this.name = name;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect r = getBounds();
            GradientDrawable g = new GradientDrawable();
            g.setColor(bg);
            g.setCornerRadius(r.width() * 0.08f);
            g.setBounds(r);
            g.draw(canvas);
            if (r.width() < 10 || r.height() < 10) return;

            // 折行排版：内边距 14% 宽；字号从「单行能放 6 字」起，行数越多越小，
            // 最小到 0.11 倍宽（太小的字不如直接截断）
            float pad = r.width() * 0.14f;
            float maxW = r.width() - pad * 2;
            List<String> lines = new ArrayList<>();
            float size = r.width() / 6f;
            for (; size >= r.width() * 0.11f; size *= 0.88f) {
                p.setTextSize(size);
                lines = wrap(maxW);
                if (lines.size() <= Math.max(2, (int) ((r.height() - pad * 2) / (size * 1.32f)))) break;
            }
            p.setTextSize(size);
            p.setColor(0xE6FFFFFF);
            p.setTextAlign(Paint.Align.CENTER);
            p.getFontMetrics(fm);
            float lineH = size * 1.32f;
            float total = lines.size() * lineH;
            // 垂直居中：整体块落在版心中央，略偏上更稳（封面下部常被角标压住）
            float top = (r.height() - total) / 2f - r.height() * 0.02f;
            float y = top - fm.ascent;
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
