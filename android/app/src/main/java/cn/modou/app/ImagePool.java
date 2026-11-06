package cn.modou.app;

import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * 全应用共用的位图缓存池。
 *
 * <p>封面和正文插图以前各开一个 LruCache（分别 16MB / 48MB），两份加起来在 2GB 机型上
 * 就是 64MB —— 快够 App 堆的 1/4，碰上文内插图多的书，系统直接杀进程。
 * 现在只有**一个池**，总预算固定在堆上限的 1/8，谁用得多谁占得多，不会互相叠加。
 *
 * <p>键加了前缀（`c:` 封面 / `i:` 插图）：两类图不会互相顶掉，同一张 URL 也不会串。
 */
final class ImagePool {

    private static final String PREFIX_COVER = "c:";
    private static final String PREFIX_IMG = "i:";

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(budget()) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };

    private ImagePool() {
    }

    /** 总预算：堆上限的 1/8，最低 6MB */
    static int budget() {
        long max = Runtime.getRuntime().maxMemory();
        long b = max / 8;
        if (b < 6L * 1024 * 1024) b = 6L * 1024 * 1024;
        return (int) Math.min(b, Integer.MAX_VALUE);
    }

    /**
     * 单张图最大宽度：屏宽的 1.5 倍。
     *
     * <p>比屏幕宽一半就够了 —— 插图/封面最宽也只是铺满版心，再宽是白占内存。
     * 超出的图片在解码阶段强压（inSampleSize），不靠事后回收。
     */
    static int maxW() {
        try {
            int sw = android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
            return sw > 0 ? Math.round(sw * 1.5f) : 1600;
        } catch (Throwable t) {
            return 1600;
        }
    }

    /**
     * 单张图最大高度：屏高的 3 倍。
     *
     * <p>为什么不是 2 倍：条漫/长图一张图就能有 12000px 高，2 倍屏高会把宽度一起压到 200px，
     * 字直接看不清。3 倍是一个既能按住「4K 壁纸级大图」、又不毁条漫的位置。
     */
    static int maxH() {
        try {
            int sh = android.content.res.Resources.getSystem().getDisplayMetrics().heightPixels;
            return sh > 0 ? sh * 3 : 4800;
        } catch (Throwable t) {
            return 4800;
        }
    }

    static Bitmap cover(String key) {
        return CACHE.get(PREFIX_COVER + key);
    }

    static void putCover(String key, Bitmap b) {
        if (b != null) CACHE.put(PREFIX_COVER + key, b);
    }

    static Bitmap image(String url) {
        return CACHE.get(PREFIX_IMG + url);
    }

    static void putImage(String url, Bitmap b) {
        if (b != null) CACHE.put(PREFIX_IMG + url, b);
    }

    /** 当前占用，给日志/诊断用 */
    static int used() {
        return CACHE.size();
    }
}
