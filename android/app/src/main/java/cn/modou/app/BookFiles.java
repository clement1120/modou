package cn.modou.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * 本地书的「文件大小 / 存在哪」。
 *
 * 经历过一次改版：最早导入是「读流 → 解析 → 正文入库」，顺手还在应用私有目录
 * 留了一份**原文件副本**，好让「大小 / 位置 / 删除本地文件」答得上来。代价是每本书
 * 占两份空间（正文一份、副本一份），书一多就是几百 MB —— 用户明确反馈过。
 *
 * 现在改成：选定一个存放书籍的文件夹之后，原文件就在用户自己的文件夹里，
 * 我们只读它、不再复制它。大小是原文件的真实字节数，位置是那个文件夹，
 * 空间只占正文那一份。旧版本留下的副本目录在启动时清一次。
 */
final class BookFiles {

    private static final String PREFS = "modou";
    private static final String PREF_PLACE = "localBooksPlace";
    private static final String PREF_CLEARED = "bookCopiesCleared";

    private BookFiles() {
    }

    /** 人类可读大小 */
    static String sizeText(long b) {
        if (b <= 0) return "未知";
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    /* ---------------- EPUB 插图 ---------------- */

    /**
     * 一本书的插图目录：files/epub/<书名 id 的指纹>/。
     *
     * 为什么按指纹而不是原样 id：本地书 id 是 `local://||local://文件名#字节数`，
     * 里面有斜杠和中文，直接当目录名会建不出来（或建到奇怪的地方）。
     */
    static File imageDir(Context c, String bookId) {
        File root = new File(c.getFilesDir(), "epub");
        return new File(root, fp(bookId));
    }

    /** 清掉一本书的插图目录（重导 / 删书时调用） */
    static void clearImages(Context c, String bookId) {
        try {
            deleteDir(new File(new File(c.getFilesDir(), "epub"), fp(bookId)));
        } catch (Exception ignore) {
            /* 删不掉就算了，下次重导会覆盖同名文件 */
        }
    }

    /**
     * 插图落盘器：解析时把图写成文件，章节 HTML 里只留 file:// 地址。
     *
     * 同一张图在 zip 里只存一份，但可能被多章引用；调用方（EpubBook.ImgCache）
     * 已经按条目路径做过去重，这里再按「文件名 + 字节数」做一次幂等，
     * 重复导入同一本书时不会重复写盘。
     */
    static EpubBook.ImageSink imageSink(Context c, String bookId) {
        final File dir = imageDir(c, bookId);
        return (key, bytes, mime) -> {
            if (bytes == null || bytes.length == 0) return "";
            if (!dir.exists() && !dir.mkdirs()) return "";
            String ext = extOfMime(mime);
            File f = new File(dir, fp(key) + "." + ext);
            if (f.isFile() && f.length() == bytes.length) return "file://" + f.getAbsolutePath();
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                o.write(bytes);
                return "file://" + f.getAbsolutePath();
            } catch (Exception e) {
                return "";
            }
        };
    }

    private static String extOfMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("jpeg") || m.contains("jpg")) return "jpg";
        if (m.contains("png")) return "png";
        if (m.contains("gif")) return "gif";
        if (m.contains("webp")) return "webp";
        if (m.contains("bmp")) return "bmp";
        return "img";
    }

    /** 稳定短指纹（SHA-1 前 16 位十六进制）：跨进程、跨版本都一样，便于复用已落盘的图 */
    static String fp(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((s == null ? "" : s).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString((s == null ? "" : s).hashCode());
        }
    }

    /* ---------------- 书籍文件夹 ---------------- */

    static void savePlace(Context c, String place) {
        if (place == null || place.isEmpty()) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_PLACE, place).apply();
    }

    /** 用户选定的书籍文件夹（显示名）；没选过返回空串 */
    static String placeOf(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_PLACE, "");
    }

    /**
     * SAF 目录 URI → 能读的中文路径。
     * `content://.../tree/primary%3ADownload%2Fbooks` →「内部存储/Download/books」
     */
    static String displayOfTree(String uri) {
        if (uri == null) return "";
        int i = uri.lastIndexOf("/tree/");
        if (i < 0) return "";
        String s = uri.substring(i + 6);
        int cut = s.indexOf('/');
        if (cut >= 0) s = s.substring(0, cut);
        try {
            s = java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception ignore) {
            /* 解码失败就用原样 */
        }
        if (s.startsWith("primary:")) s = "内部存储/" + s.substring(8);
        else {
            int c = s.indexOf(':');
            if (c > 0) s = s.substring(c + 1); // 扩展卡：xxxx-xxxx:books → books
        }
        return s;
    }

    /* ---------------- 旧副本清理 ---------------- */

    /**
     * 清掉旧版本留下的原文件副本目录（Android/data/cn.modou.app/files/books）。
     *
     * 只做一次：删的是我们自己写进去的副本（文件名是 id 的 hash），不是用户的书。
     * 不清的话，升级之后那几百 MB 会一直躺着。
     */
    static void clearOldCopies(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(PREF_CLEARED, false)) return;
        sp.edit().putBoolean(PREF_CLEARED, true).apply();
        new Thread(() -> {
            try {
                File base = c.getExternalFilesDir(null);
                if (base == null) base = c.getFilesDir();
                File d = new File(base, "books");
                deleteDir(d);
            } catch (Exception ignore) {
                /* 清不掉也不影响使用 */
            }
        }).start();
    }

    private static void deleteDir(File d) {
        if (d == null || !d.exists()) return;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) deleteDir(f);
            else {
                try {
                    f.delete();
                } catch (Exception ignore) {
                    /* 无所谓 */
                }
            }
        }
        try {
            d.delete();
        } catch (Exception ignore) {
            /* 无所谓 */
        }
    }

    /* ---------------- 编辑信息里的只读块 ---------------- */

    /**
     * 编辑信息面板里的「文件大小 / 保存位置」一块。
     *
     * 大小是导入时读到的原文件真实字节数；位置是用户选定的那个书籍文件夹
     * （原文件就在那里，我们没有复制它）。早期导入的书两者都没有，
     * 就如实写「书库内 · 正文已分章入库」，不编一个路径出来。
     */
    static LinearLayout infoCard(Context c, JSONObject b) {
        ModouUi u = ModouUi.of(c);
        long bytes = b.optLong("bytes", 0);
        String path = b.optString("localPath", "");
        String kind = b.optString("kind", "");
        // 旧版本存的是私有目录里的副本路径，副本已经清掉了，再显示就是假信息
        if (path.contains("/files/books") || path.contains("应用私有")) path = "";

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = ModouUi.dp(c, 12), padV = ModouUi.dp(c, 9);
        box.setPadding(0, ModouUi.dp(c, 4), 0, 0);

        box.addView(infoRow(c, u, "文件大小",
                bytes > 0 ? sizeText(bytes) + (kind.isEmpty() ? "" : " · " + kind)
                        : "未知（早期导入的书）", "", padH, padV));
        box.addView(ModouUi.divider(c));
        String place = path.isEmpty() ? placeOf(c) : path;
        box.addView(infoRow(c, u, "所在文件夹",
                place.isEmpty() ? "书库内 · 正文已分章入库" : place, "", padH, padV));
        return box;
    }

    /** 一行只读信息：左标题、右值（值可能很长，允许折行） */
    private static LinearLayout infoRow(Context c, ModouUi u, String label, String value,
                                       String copy, int padH, int padV) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(padH, padV, padH, padV);

        TextView l = ModouUi.tv(c, label, 13, u.sub);
        l.setSingleLine(true);
        row.addView(l, new LinearLayout.LayoutParams(
                ModouUi.dp(c, 72), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = ModouUi.tv(c, value, 12.5f, u.text);
        v.setLineSpacing(ModouUi.dp(c, 1), 1.25f);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (copy != null && !copy.isEmpty()) {
            row.setOnClickListener((x) -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            c.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", copy));
                    ModouUi.toast(c, "路径已复制");
                } catch (Exception e) {
                    /* 剪贴板被限制：忽略 */
                }
            });
        }
        return row;
    }
}
