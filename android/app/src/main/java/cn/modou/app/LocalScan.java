package cn.modou.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 扫描本机书籍（.txt / .epub），给「导入本地书籍」页出列表用。
 *
 * 走 MediaStore 而不是自己遍历目录：Android 11 起 File 遍历只能看到自己有权限的目录，
 * 硬扫要么空、要么慢；MediaStore 一次查询就带回了名字、大小、修改时间和所在目录，
 * 打开时用 content:// 的 id 直接流式读，不需要真实路径（也就不受分区存储限制）。
 *
 * 是否「导入过」按 bookUrl 判定：本地书的 bookUrl 是 local://名字#字节数，
 * 与导入时落在 books 表里的那条完全一致 —— 名字和大小都对得上才算同一本书，
 * 同名但改过内容的文件会单独标出来，不会悄悄当成「已导入」而漏掉更新。
 */
final class LocalScan {

    /** 单本上限，与 ImportActivity 的 MAX_BYTES 一致（大于它的解析出来也装不下） */
    static final long MAX_BYTES = 60L * 1024 * 1024;
    /** 小于这个大小的基本是空文件/占位文件，列出来只会碍眼 */
    private static final long MIN_BYTES = 1024;

    static final int NEW = 0;      // 没导入过
    static final int SAME = 1;     // 已导入，大小一致
    static final int CHANGED = 2;  // 导入过同名文件，但大小变了（多半是重新下载/替换过）

    static final class Item {
        String name = "";
        String dir = "";
        String ext = "";
        long size;
        long mtime;
        Uri uri;
        int state = NEW;
    }

    private LocalScan() {
    }

    /** 扫描外部存储里的书；按「未导入在前，同组内新文件在前」排好序 */
    static List<Item> scan(Context ctx) {
        List<Item> out = new ArrayList<>();
        Uri base = MediaStore.Files.getContentUri("external");
        boolean q = Build.VERSION.SDK_INT >= 29;
        String[] proj = q
                ? new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH}
                : new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA};
        // 后缀兜一层：不少网文是 .TXT 大写、或者被系统报成 octet-stream，只按 mime 会漏
        String sel = "(" + MediaStore.MediaColumns.MIME_TYPE + " IN ('text/plain','application/epub+zip')"
                + " OR " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE '%.txt'"
                + " OR " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE '%.epub')";
        Cursor c = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            c = cr.query(base, proj, sel, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c == null) return out;
            int iId = c.getColumnIndex(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int iTime = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int iDir = c.getColumnIndex(q ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA);
            while (c.moveToNext()) {
                String name = iName >= 0 ? c.getString(iName) : null;
                if (name == null || name.startsWith(".")) continue;
                String ext = extOf(name);
                if (!"txt".equals(ext) && !"epub".equals(ext)) continue;
                long size = iSize >= 0 ? c.getLong(iSize) : 0;
                if (size < MIN_BYTES || size > MAX_BYTES) continue;

                Item it = new Item();
                it.name = name;
                it.ext = ext;
                it.size = size;
                it.mtime = iTime >= 0 ? c.getLong(iTime) * 1000L : 0;
                it.dir = dirOf(iDir >= 0 ? c.getString(iDir) : null, q);
                it.uri = ContentUris.withAppendedId(base, c.getLong(iId));
                out.add(it);
            }
        } catch (Exception e) {
            /* 没权限/ROM 把 MediaStore 裁了：返回已扫到的部分，页面会提示去开权限 */
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
        return out;
    }

    /** 用书架里已有的本地书标记每一本的导入状态（只查主键，不碰几十 KB 的 json） */
    static void markImported(List<Item> items, List<String> localUrls) {
        Set<String> urls = new HashSet<>(localUrls);
        Set<String> names = new HashSet<>();
        for (String u : localUrls) {
            String n = nameOfUrl(u);
            if (!n.isEmpty()) names.add(n);
        }
        for (Item it : items) {
            if (urls.contains(urlOf(it))) it.state = SAME;
            else it.state = names.contains(it.name) ? CHANGED : NEW;
        }
    }

    /** 与导入时写入 books 的 bookUrl 保持同一格式 */
    static String urlOf(Item it) {
        return "local://" + it.name + "#" + it.size;
    }

    /** bookUrl → 文件名（去掉 local:// 前缀和 #字节数 后缀） */
    private static String nameOfUrl(String url) {
        if (url == null || !url.startsWith("local://")) return "";
        String s = url.substring(8);
        int i = s.lastIndexOf('#');
        return i > 0 ? s.substring(0, i) : s;
    }

    private static String dirOf(String raw, boolean relative) {
        if (raw == null || raw.isEmpty()) return "内部存储";
        String s = raw;
        if (!relative) {
            int i = s.indexOf("/0/");     // /storage/emulated/0/Download/…
            s = i >= 0 ? s.substring(i + 3) : s;
            int k = s.lastIndexOf('/');
            s = k > 0 ? s.substring(0, k) : s;
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("/")) s = s.substring(1);
        return s.isEmpty() ? "内部存储" : s;
    }

    static String extOf(String name) {
        String n = String.valueOf(name == null ? "" : name).toLowerCase();
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i + 1) : "";
    }

    /** 未导入在前；同状态内按修改时间新→旧 */
    static void sort(List<Item> items) {
        Collections.sort(items, (a, b) -> {
            if (a.state != b.state) return a.state - b.state;
            return Long.compare(b.mtime, a.mtime);
        });
    }
}
