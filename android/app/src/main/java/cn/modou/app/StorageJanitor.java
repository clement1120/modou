package cn.modou.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 存储体检 / 回收。
 *
 * 不是「清缓存」按钮的替代品，而是把三类以前看不见的占用摊开：
 *  - SQLite 主库 + WAL + 空闲页（删书/清缓存后不 VACUUM，文件不会自己变小）；
 *  - files/epub 与 files/covers 里已经没有书引用的孤儿文件；
 *  - cache/import 里进程被杀后残留的导入临时文件。
 *
 * 孤儿正文只回收「移出书架超过 7 天」的，给用户留反悔窗口；在线书章节缓存
 * 另有 512MB 上限，本地导入书的正文不参与淘汰。
 */
final class StorageJanitor {

    private static final long MB = 1024L * 1024L;
    private static final long ORPHAN_KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long ONLINE_CACHE_MAX = 128L * MB;
    private static final String PREFS = "modou";
    private static final String PREF_LAST = "storageJanitorLastAt";
    private static final long AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static volatile boolean running;

    /** 占用总量（appBytes）缓存：dirBytes 会递归遍历整个 files/cache/databases，
     *  书库大时一次要十几秒。设置页、存储体检都会用到，60s 内不重复遍历。 */
    private static volatile long appBytesCache = -1;
    private static volatile long appBytesCacheAt = 0;
    private static final long APPBYTES_TTL = 60L * 1000;

    private StorageJanitor() {
    }

    /** 清占用缓存：清理/删书后占用变了，下次取数应重新遍历 */
    static void invalidateAppBytes() {
        appBytesCache = -1;
    }

    /** 启动时做一次低成本清理；VACUUM 留给设置页的显式「深度回收」 */
    static void autoClean(Context c) {
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - sp.getLong(PREF_LAST, 0) < AUTO_INTERVAL_MS) return;
        new Thread(() -> {
            // 让书架首屏查完、让用户先点走：1.2s 太急，书库大时清理的
            // checkpoint / 扫描正好撞上「冷启动第一次打开书」，两边抢库锁。
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ignore) {
                return;
            }
            if (ModouStore.isQuiet()) return; // 阅读页在前台：让路，下次启动再清
            try {
                clean(c, false);
                // 成功（即使是 0 字节）才记时间：clean 抛错时不更新，下次启动再试
                sp.edit().putLong(PREF_LAST, now).apply();
            } catch (Throwable ignore) {
                /* 清理失败不影响使用 */
            }
        }, "modou-storage-auto").start();
    }

    /** 体检结果 */
    static final class Result {
        long freed;
        int orphanChapters;
        boolean vacuumed;
        long needBytes;
        long freeBytes;
    }

    /** 人类可读的占用明细（设置页 / 诊断用） */
    static String report(Context c) {
        ModouStore store = ModouStore.get(c);
        long db = store.dbBytes();
        long page = store.dbPageBytes();
        long free = store.dbFreeBytes();
        int orphans = store.countOrphanChapters();
        long images = dirBytes(new File(c.getFilesDir(), "epub"));
        long covers = dirBytes(new File(c.getFilesDir(), "covers"));
        long imports = dirBytes(new File(c.getCacheDir(), "import"));
        long legacy = dirBytes(new File(c.getFilesDir(), "books"));
        File ext = c.getExternalFilesDir(null);
        if (ext != null) legacy += dirBytes(new File(ext, "books"));
        long total = db + images + covers + imports + legacy;
        long app = appBytes(c);
        long other = Math.max(0, app - total);
        // 可回收 = 空闲页 + 已移出书架的正文（按全书平均章大小估算，不为此再扫一遍正文）
        long chapters = store.count("chapters");
        long avg = chapters > 0 ? page / chapters : 0;
        long orphanBytes = orphans > 0 ? avg * orphans : 0;
        long recover = free + orphanBytes;
        return "总计 " + size(app)
                + "\n· 数据库 " + size(db) + (free > 0 ? "（空闲页 " + size(free) + "）" : "")
                + "\n· EPUB 插图 " + size(images)
                + "\n· 封面 " + size(covers)
                + "\n· 导入临时 " + size(imports)
                + "\n· 旧版副本 " + size(legacy)
                + (other > 0 ? "\n· 其他（WebView/日志/临时）" + size(other) : "")
                + "\n\n库页 " + size(page) + "，可回收约 " + size(recover)
                + (orphans > 0
                ? "\n其中 " + orphans + " 章是已移出书架的正文（约 " + size(orphanBytes) + "）"
                : "")
                + (recover == 0 ? "\n库里的正文都是书架上还在读的书，没有可删的余量。" : "");
    }

    /**
     * 执行清理。
     *
     * @param deep true = 连 VACUUM 一起做（会短暂持有写锁，适合设置页手动触发）
     */
    static Result clean(Context c, boolean deep) {
        Result r = new Result();
        // 阅读页在前台时不做批量删除/压缩，避免和取章、排版抢数据库
        if (ModouStore.isQuiet()) return r;
        synchronized (StorageJanitor.class) {
            if (running) return r;
            running = true;
        }
        try {
            // 字节计量只给手动「深度回收」用（会遍历整个目录，书库大时十几秒）。
            // 自动清理不计量：省掉两次全目录遍历，避免开书架时和首屏/阅读抢 I/O。
            long before = deep ? appBytesNow(c) : 0;
            ModouStore store = ModouStore.get(c);
            store.checkpoint();

            deleteStaleImports(c, deep);
            deleteOldCopies(c);
            deleteOrphanImages(c, store);
            deleteOrphanCovers(c, store);
            // 深度回收是用户明确点过「确定」的：孤儿正文全删，不再留 7 天。
            // 自动清理仍然只删早就过期的，避免把「刚删掉、还想加回来」的书洗掉。
            r.orphanChapters = store.deleteOrphanChapters(
                    deep ? 0 : System.currentTimeMillis() - ORPHAN_KEEP_MS);
            store.trimOnlineChapterCache(ONLINE_CACHE_MAX);
            store.checkpoint();

            if (deep) {
                // 删完正文必须压缩：SQLite 只把页标成空闲，文件不会自己变小。
                // 以前这里要求「2 倍库大小」的空闲空间，10GB 的库要 20GB 才肯动，
                // 手机上永远不满足 —— 于是回收完数字还是 0，用户以为清理没生效。
                long dbSize = store.dbBytes();
                r.needBytes = dbSize + (64L * MB);
                r.freeBytes = available(c);
                r.vacuumed = store.compactIfNeeded(0);
            }

            invalidateAppBytes(); // 清理后占用变了，下次取数重算（即使自动清理）
            long after = deep ? appBytesNow(c) : 0;
            r.freed = deep ? Math.max(0, before - after) : 0;
            return r;
        } catch (Throwable t) {
            return r;
        } finally {
            running = false;
        }
    }

    /** 应用可用的磁盘空间（用于判断能不能 VACUUM） */
    private static long available(Context c) {
        try {
            return new android.os.StatFs(c.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 应用私有目录总量（数据库在 files 的上一级，单独补上）。带 60s 缓存：
     *  设置页「书库占用」与存储体检都会读它，书库大时一次遍历十几秒，缓存避免重复吃 IO。 */
    static long appBytes(Context c) {
        long now = System.currentTimeMillis();
        if (appBytesCache >= 0 && now - appBytesCacheAt < APPBYTES_TTL) return appBytesCache;
        appBytesCache = appBytesNow(c);
        appBytesCacheAt = now;
        return appBytesCache;
    }

    /** 真实遍历（清理前后计量、缓存失效时重算用），绕过缓存 */
    private static long appBytesNow(Context c) {
        long n = dirBytes(c.getFilesDir()) + dirBytes(c.getCacheDir());
        // /data/data/<pkg>/databases 不在 files/cache 下
        File db = new File(c.getFilesDir().getParentFile(), "databases");
        n += dirBytes(db);
        return n;
    }

    private static void deleteStaleImports(Context c, boolean all) {
        File dir = new File(c.getCacheDir(), "import");
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        for (File f : fs) {
            // 手动深度清理可以全删；自动清理只碰 24 小时前的残留，
            // 避免和正在进行的导入抢同一个临时文件。
            if (all || now - f.lastModified() > 24L * 60 * 60 * 1000) {
                try {
                    if (f.isDirectory()) deleteDir(f);
                    else f.delete();
                } catch (Exception ignore) {
                    /* 正在被读：下次再删 */
                }
            }
        }
    }

    /** 旧版本会在应用私有目录另存一份原文件副本；现在只读用户文件夹，副本可以全删 */
    private static void deleteOldCopies(Context c) {
        try {
            deleteDir(new File(c.getFilesDir(), "books"));
            File ext = c.getExternalFilesDir(null);
            if (ext != null) deleteDir(new File(ext, "books"));
        } catch (Exception ignore) {
            /* 删不掉不影响正文 */
        }
    }

    /** files/epub/<bookId 指纹>/：只保留仍在书架里的书 */
    private static void deleteOrphanImages(Context c, ModouStore store) {
        Set<String> ids = store.bookIdSet();
        Set<String> valid = new HashSet<>();
        for (String id : ids) valid.add(BookFiles.fp(id));
        File root = new File(c.getFilesDir(), "epub");
        File[] fs = root.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (!f.isDirectory()) continue;
            if (!valid.contains(f.getName())) deleteDir(f);
        }
    }

    /** files/covers/<crc>-<len>.img：只保留仍被某本书 coverUrl 引用的文件 */
    private static void deleteOrphanCovers(Context c, ModouStore store) {
        Set<String> valid = new HashSet<>();
        try {
            JSONArray lite = new JSONArray(store.booksLite());
            for (int i = 0; i < lite.length(); i++) {
                JSONObject o = lite.optJSONObject(i);
                if (o == null) continue;
                String cover = o.optString("coverUrl", "");
                addCoverName(valid, cover);
                if (cover.isEmpty()) {
                    // 老 lite 可能没有封面字段：只对这类书记录回查一次完整 json
                    String id = o.optString("id", "");
                    String full = id.isEmpty() ? null : store.get("books", id);
                    if (full != null) {
                        try {
                            addCoverName(valid, new JSONObject(full).optString("coverUrl", ""));
                        } catch (Exception ignore) {
                            /* 坏记录跳过 */
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            // 读不到书架就不删封面，宁可少回收也不误删
            return;
        }
        File dir = new File(c.getFilesDir(), "covers");
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isFile() && !valid.contains(f.getName())) {
                try {
                    f.delete();
                } catch (Exception ignore) {
                    /* 下次再删 */
                }
            }
        }
    }

    private static void addCoverName(Set<String> out, String url) {
        if (url == null || url.isEmpty()) return;
        int i = url.lastIndexOf("/covers/");
        if (i < 0) return;
        String name = url.substring(i + 8);
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        if (!name.isEmpty()) out.add(name);
    }

    private static long dirBytes(File d) {
        if (d == null || !d.exists()) return 0;
        if (d.isFile()) return d.length();
        long n = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) n += dirBytes(f);
        return n;
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

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < MB) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        if (b < 1024L * MB) return String.format(Locale.ROOT, "%.1f MB", b / (double) MB);
        return String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * MB));
    }
}
