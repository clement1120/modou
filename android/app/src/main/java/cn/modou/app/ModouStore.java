package cn.modou.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ModouStore — 共享存储层：SQLite 作为唯一真相源（原生与 WebView 共用同一份数据）。
 *
 * 为什么需要它：数据原本全在 WebView 的 IndexedDB 里，原生屏想读一本书只能 RPC 进 JS
 * （桥 → JS → IndexedDB → 回传），这是「逐模块原生化」真正的地基障碍。把数据落到
 * App 私有目录的 SQLite 后，JS 经 ModouBridge 的存储桥读写这一份库，将来原生书架 /
 * 原生阅读页直接 SQL 查询，不再绕 WebView。
 *
 * 设计取舍：
 *  - 零第三方依赖（不用 Room/androidx）：沿用项目「NanoHTTPD 内嵌、零依赖」的既定原则，
 *    也因为构建是 --offline、gradle 缓存里没有 androidx/room。这里只用 framework 自带的
 *    SQLiteOpenHelper + org.json。
 *  - 「常用字段建列 + 整对象留 json」：books 把 name/author/grp/kind/addedAt/updatedAt
 *    建成真列，将来原生书架能直接 ORDER BY name / 按分组过滤；同时 json 列保留完整对象，
 *    绝不丢 IndexedDB 里的自由字段。
 *  - chapters 不存 json 列、只存 content/cachedAt：正文是最大的数据（EPUB 内嵌 base64
 *    插图单章可达数 MB），再存一份 json 会让库体积直接翻倍。
 *  - settings 的 v 列存的是 JSON.stringify 后的文本，Java 不做值的语义解析，
 *    编解码统一放在 JS 侧，避免双重编码。
 *
 * 表 → 主键（与 web/js/db.js 的 KEY_PATHS 保持一致）：
 *   sources / books / chapters / bookmarks → id；progress → bookId；settings → k
 *
 * 所有 public 方法都 synchronized：桥方法跑在 WebView 的 JavaBridge 线程，
 * 原生阅读页将来也会从别的线程读库。
 */
public class ModouStore extends SQLiteOpenHelper {

    private static final int DB_VERSION = 1;

    /**
     * 表名白名单：表名只能从这里取，杜绝拼接 SQL 注入。
     * 与 web/js/db.js 的 KEY_PATHS 七个 store 一一对应（replaceRules 虽实际把数据
     * 存在 settings 里，settings.js 仍会对它调 db.clear，表必须对得上，否则调用落空）。
     */
    private static final String[] TABLES = {
            "sources", "books", "progress", "chapters", "replaceRules", "settings", "bookmarks"
    };

    private static volatile ModouStore instance;

    /** 迁移标记存在 SharedPreferences，需要一个 Context 才读得到 */
    private final Context ctx;

    private ModouStore(Context ctx) {
        super(ctx.getApplicationContext(), "modou.db", null, DB_VERSION);
        this.ctx = ctx.getApplicationContext();
        // WAL 用官方入口开：在 onConfigure 里直接调 db.enableWriteAheadLogging()
        // 有可能被框架拒绝（当时连接还在初始化），失败又没有回调，等于悄悄退回老模式。
        // 这里挂在 helper 上，由它自己决定在合适的时机启用。
        try {
            setWriteAheadLoggingEnabled(true);
        } catch (Exception ignore) {
            /* 少数 ROM/文件系统不支持 WAL：退回默认模式，功能不受影响 */
        }
    }

    /** 进程内单例（App 只有一个 WebView，共享一份连接即可） */
    public static ModouStore get(Context ctx) {
        if (instance == null) {
            synchronized (ModouStore.class) {
                if (instance == null) instance = new ModouStore(ctx);
            }
        }
        return instance;
    }

    /**
     * 打开连接时的 PRAGMA。
     *
     * 导入一本书要写几千行正文（几百 MB 的书更夸张），默认 journal 模式下
     * 每个事务提交都要 fsync 一次 journal + db，实测是「导入等好久」的主因。
     * WAL + synchronous=NORMAL 把每次提交降成「写 WAL 页、不 fsync」，
     * 崩溃时最多丢最后一个事务（重导一次即可），批量写快很多。
     */
    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        try {
            // 每个事务提交都 fsync 一次 journal 太贵（导入一本书就是上百次），
            // NORMAL 只保证不损坏，崩溃时最多丢最后一个事务 —— 重导一次即可。
            db.execSQL("PRAGMA synchronous=NORMAL");
            // 页缓存放大到 8MB：导入时按 1.2MB 一批写，缓存够大才能少读回磁盘
            db.execSQL("PRAGMA cache_size=-8000");
        } catch (Exception ignore) {
            /* PRAGMA 不支持就按默认值来 */
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sources (id TEXT PRIMARY KEY, json TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS replaceRules (id TEXT PRIMARY KEY, json TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS books ("
                + "id TEXT PRIMARY KEY, name TEXT, author TEXT, grp TEXT, kind TEXT, "
                + "addedAt INTEGER, updatedAt INTEGER, json TEXT, lite TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS progress (bookId TEXT PRIMARY KEY, json TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS chapters (id TEXT PRIMARY KEY, content TEXT, cachedAt INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id TEXT PRIMARY KEY, json TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // v1 是首个版本：后续加列走 ALTER TABLE，暂不做破坏性重建（丢不起用户书架）
        db.execSQL("CREATE TABLE IF NOT EXISTS sources (id TEXT PRIMARY KEY, json TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS replaceRules (id TEXT PRIMARY KEY, json TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS books ("
                + "id TEXT PRIMARY KEY, name TEXT, author TEXT, grp TEXT, kind TEXT, "
                + "addedAt INTEGER, updatedAt INTEGER, json TEXT, lite TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS progress (bookId TEXT PRIMARY KEY, json TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS chapters (id TEXT PRIMARY KEY, content TEXT, cachedAt INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id TEXT PRIMARY KEY, json TEXT)");
    }

    /**
     * 书架要用的字段（不含 chapters 大数组、不含长简介）。
     *
     * 一本书的 json 里 chapters 数组能有几千项、EPUB 封面还是 base64 内联，
     * 三十多本加起来十几 MB；书架只需要这几个字段，却每次冷启动都要把整份
     * json 读出来解析一遍 —— 书一多就卡死（这就是「导入多了以后点什么都卡」）。
     */
    /** lite 列是否可用（老库 ALTER 失败时退回旧读法，不能让写书整个失败） */
    private volatile boolean liteReady;

    /* ---------------- 设置内存快照 ----------------
     *
     * 打开一本书要在主线程读十几个设置项（字号、行距、页边距、字体、页眉页脚……）。
     * 以前每一项都是一次 SQLite 查询：冷启动第一次进书时数据库连接还没热起来，
     * 十几条 SELECT 串着打，用户看到的就是「点开书，白屏好久」。
     *
     * 进程启动时在后台把 settings 整表（几十行、几 KB）读进内存，之后所有读设置
     * 都是 HashMap 命中；写设置时同步更新这一份，JS 侧经桥写入同样覆盖到。
     */
    private final java.util.Map<String, String> setCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean setCacheReady = false;

    /**
     * 后台预热设置快照；顺带把数据库连接打开（WAL 恢复等一次性开销挪出主线程）。
     *
     * 特意**不加** synchronized：打开连接可能要做 WAL 恢复（几百毫秒到几秒），
     * 占着 store 锁会让此刻正想读章节的阅读页干等。缓存本身用 ConcurrentHashMap，
     * 填完最后一步才置 ready 标志，读侧看不到半份数据。
     */
    public void preloadSettings() {
        if (setCacheReady) return;
        java.util.Map<String, String> tmp = new java.util.HashMap<>();
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.rawQuery("SELECT k, v FROM settings", null);
            while (c.moveToNext()) {
                String k = c.isNull(0) ? null : c.getString(0);
                if (k != null) tmp.put(k, c.isNull(1) ? null : c.getString(1));
            }
            setCache.putAll(tmp);
            setCacheReady = true;
        } catch (Exception e) {
            /* 读不到就保持 false：settingRaw 仍走库，功能不受影响 */
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
    }

    private static final String[] LITE_KEYS = {
            "id", "sourceUrl", "sourceName", "name", "author", "kind",
            "lastChapter", "group", "addedAt", "updatedAt", "fav", "preview",
            "bytes", "localPath", "coverUrl"
    };

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // 老库补 lite 列：CREATE TABLE IF NOT EXISTS 不会给已有表加列，
        // 而 DB_VERSION 还停在 1（不想为加一列去触发整套 onUpgrade）。
        try {
            if (!hasColumn(db, "books", "lite")) {
                db.execSQL("ALTER TABLE books ADD COLUMN lite TEXT");
            }
            liteReady = hasColumn(db, "books", "lite");
        } catch (Exception ignore) {
            liteReady = false; // 加不上就退回旧的整表读法，功能不受影响
        }
        // 两个索引，都是为了「读书架不碰 json 大字段」：
        //
        //  · idx_books_lite  —— 找「还没补 lite 的书」用。没有索引时
        //    `WHERE lite IS NULL` 要逐行判断，而判断得先把整条记录（含几 MB 的
        //    json、几百章目录）从磁盘读出来，几十本书就是几十 MB 的读放大。
        //    阅读页等一次正文就排在这次全表扫后面 —— 表现是「下一章死活加载不出来」。
        //  · idx_books_shelf —— 覆盖索引：booksLite() 要的列全在里面，
        //    SQLite 只扫索引、不读表记录，书架刷新与 json 大小彻底解耦。
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_lite ON books(lite)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_shelf ON books("
                    + "id, name, author, grp, kind, addedAt, updatedAt, lite)");
        } catch (Exception ignore) {
            /* 建不上索引只是慢一点，功能不受影响 */
        }
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String col) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (c.moveToNext()) {
                if (col.equalsIgnoreCase(c.getString(c.getColumnIndexOrThrow("name")))) return true;
            }
        } catch (Exception ignore) {
            /* 查不到当没有 */
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
        return false;
    }

    /** 表名合法（白名单） */
    private static boolean valid(String table) {        if (table == null) return false;
        for (String t : TABLES) if (t.equals(table)) return true;
        return false;
    }

    /** 表 → 主键列名 */
    private static String pkOf(String table) {
        if ("progress".equals(table)) return "bookId";
        if ("settings".equals(table)) return "k";
        return "id";
    }

    /** 游标当前行 → 完整对象的 JSON 字符串 */
    private static String rowToJson(String table, Cursor c) throws Exception {
        String pk = pkOf(table);
        String key = c.getString(c.getColumnIndexOrThrow(pk));
        if ("chapters".equals(table)) {
            JSONObject o = new JSONObject();
            o.put("id", key);
            o.put("content", c.getString(c.getColumnIndexOrThrow("content")));
            o.put("cachedAt", c.getLong(c.getColumnIndexOrThrow("cachedAt")));
            return o.toString();
        }
        if ("settings".equals(table)) {
            JSONObject o = new JSONObject();
            o.put("k", key);
            o.put("v", c.getString(c.getColumnIndexOrThrow("v")));
            return o.toString();
        }
        String json = c.getString(c.getColumnIndexOrThrow("json"));
        return json != null ? json : new JSONObject().put(pk, key).toString();
    }

    /* ---------- 读 ---------- */

    /** 单条；不存在返回 null（与 IndexedDB 的 get 语义一致） */
    public synchronized String get(String table, String key) {
        if (!valid(table) || key == null) return null;
        // chapters 的正文可能超过 CursorWindow 单行上限（内联插图的章节能到几 MB），
        // 走分片读，别让桥那边也拿到一个 null
        if ("chapters".equals(table)) {
            String content = readTextByKey("chapters", "content", key);
            if (content == null) return null;
            try {
                Cursor ct = getReadableDatabase().rawQuery(
                        "SELECT cachedAt FROM chapters WHERE id=?", new String[]{key});
                long at = 0;
                if (ct != null) {
                    if (ct.moveToFirst() && !ct.isNull(0)) at = ct.getLong(0);
                    ct.close();
                }
                JSONObject o = new JSONObject();
                o.put("id", key);
                o.put("content", content);
                o.put("cachedAt", at);
                return o.toString();
            } catch (Exception e) {
                return null;
            }
        }
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.query(table, null, pkOf(table) + "=?", new String[]{key}, null, null, null, "1");
            if (c.moveToFirst()) return rowToJson(table, c);
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
    }

    /** 全表对象数组（JSON 字符串）。chapters 慎用：会把全部正文搬进内存 */
    public synchronized String all(String table) {
        if (!valid(table)) return "[]";
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.query(table, null, null, null, null, null, null);
            while (c.moveToNext()) {
                String s = rowToJson(table, c);
                if (s != null) arr.put(new JSONObject(s));
            }
        } catch (Exception e) {
            /* 返回已取到的部分 */
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
        return arr.toString();
    }

    /** 全表主键数组（JSON 字符串） */
    public synchronized String keys(String table) {
        if (!valid(table)) return "[]";
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.query(table, new String[]{pkOf(table)}, null, null, null, null, null);
            while (c.moveToNext()) arr.put(c.getString(0));
        } catch (Exception e) {
            /* 忽略 */
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
        return arr.toString();
    }

    /**
     * 书架用的轻量记录（不含 chapters 大数组、长简介）。
     *
     * 一本书完整 json 里 chapters 能有几千项，EPUB 封面还内联 base64；
     * 书架只要名字/作者/封面/分组/时间，没必要为它搬整份记录。
     */
    private static String liteOf(JSONObject o) {
        JSONObject l = new JSONObject();
        try {
            for (String k : LITE_KEYS) {
                if (!o.has(k) || o.isNull(k)) continue;
                Object v = o.opt(k);
                if (v != null) l.put(k, v);
            }
            String intro = o.optString("intro", "");
            if (intro.length() > 80) intro = intro.substring(0, 80);
            if (!intro.isEmpty()) l.put("intro", intro);
            JSONArray chs = o.optJSONArray("chapters");
            l.put("chapterCount", chs != null ? chs.length() : o.optInt("chapterCount", 0));
        } catch (Exception ignore) {
            /* 个别脏字段丢掉，别让整条记录写不进去 */
        }
        return l.toString();
    }

    /**
     * 内联 base64 封面落盘，返回新 URL（file://…）。
     *
     * 不落盘的话，一份 300KB 的封面会跟着 books 记录被书架、目录、存储桥各读一遍；
     * 三十多本本地书光封面就能把冷启动拖到几秒。小于 4KB 的小图标不值得为它建文件。
     */
    private String coverToFile(String url, String bookId) {
        if (url == null || !url.startsWith("data:image") || url.length() < 4096) return url;
        try {
            int i = url.indexOf("base64,");
            if (i < 0) return url;
            byte[] bs = android.util.Base64.decode(url.substring(i + 7), android.util.Base64.DEFAULT);
            if (bs == null || bs.length < 2048) return url;
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bs);
            File dir = new File(ctx.getFilesDir(), "covers");
            if (!dir.exists() && !dir.mkdirs()) return url;
            File f = new File(dir, Long.toHexString(crc.getValue()) + "-" + bs.length + ".img");
            if (!f.exists() || f.length() != bs.length) {
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                try {
                    out.write(bs);
                } finally {
                    out.close();
                }
            }
            return "file://" + f.getAbsolutePath();
        } catch (Throwable t) {
            return url; // 落盘失败就照旧存内联，功能不受影响
        }
    }

    /**
     * 书架全量数据：只读 lite 列（外加几列兜底），**永远不碰 json 大列**。
     *
     * lite 还没回填的老记录用建表列拼一个最小记录先顶上，书不会从书架上消失，
     * 封面等下次 {@link #ensureBookLite(long)} 补完自然出现。
     */
    /**
     * 单本轻量记录（详情页首屏用），读不到返回 null。
     *
     * 整条书籍记录里带着几千章目录和内联封面，动辄几百 KB～几 MB；详情页在主线程
     * `new JSONObject(那条记录)` 就是一次肉眼可见的卡顿（「点封面半天没反应」）。
     * 轻量记录只有几百字节，先把界面画出来，目录随后后台补。
     */
    public synchronized JSONObject bookLite(String id) {
        if (id == null || id.isEmpty()) return null;
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            // SELECT *：老库里可能还没有 lite 列（ALTER 失败过），按列名取才不会查崩
            c = db.rawQuery("SELECT * FROM books WHERE id = ?", new String[]{id});
            if (!c.moveToFirst()) return null;
            JSONObject o = null;
            int li = c.getColumnIndex("lite");
            if (li >= 0 && !c.isNull(li)) {
                String lite = c.getString(li);
                if (lite != null && lite.startsWith("{")) {
                    try {
                        o = new JSONObject(lite);
                    } catch (Exception ignore) {
                        o = null; // 坏 lite：退回列拼装
                    }
                }
            }
            if (o == null) {
                o = new JSONObject();
                o.put("id", c.getString(c.getColumnIndexOrThrow("id")));
                o.put("name", str(c, "name"));
                o.put("author", str(c, "author"));
                o.put("group", str(c, "grp"));
                o.put("kind", str(c, "kind"));
                o.put("addedAt", c.getLong(c.getColumnIndexOrThrow("addedAt")));
                o.put("chapterCount", 0);
            }
            if (c.getColumnIndex("updatedAt") >= 0) {
                long ut = c.getLong(c.getColumnIndex("updatedAt"));
                if (ut > 0) o.put("updatedAt", ut);
            }
            return o;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
    }

    /** 取一列字符串（列不存在或为空时给空串） */
    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return "";
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    public synchronized String booksLite() {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.rawQuery("SELECT lite, id, name, author, grp, kind, addedAt, updatedAt FROM books", null);
            while (c.moveToNext()) {
                String lite = c.isNull(0) ? null : c.getString(0);
                if (lite != null && lite.startsWith("{")) {
                    try {
                        JSONObject o = new JSONObject(lite);
                        // 列是权威：touchBook 只改列与 lite，但老记录可能只有列更新过，
                        // 「最近阅读」排序要拿到真实的那一个时间
                        long ut = c.getLong(7);
                        if (ut > 0) o.put("updatedAt", ut);
                        arr.put(o);
                        continue;
                    } catch (Exception ignore) {
                        /* 坏 lite：退回列拼装 */
                    }
                }
                JSONObject o = new JSONObject();
                o.put("id", c.getString(1));
                o.put("name", c.getString(2) == null ? "" : c.getString(2));
                o.put("author", c.getString(3) == null ? "" : c.getString(3));
                o.put("group", c.getString(4) == null ? "默认" : c.getString(4));
                o.put("kind", c.getString(5) == null ? "" : c.getString(5));
                o.put("addedAt", c.getLong(6));
                o.put("updatedAt", c.getLong(7));
                o.put("chapterCount", 0);
                arr.put(o);
            }
        } catch (Exception e) {
            /* 空表/脏数据：当空书架 */
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
        return arr.toString();
    }

    /**
     * 给老记录补 lite（顺带把内联封面落盘）。
     *
     * @param budgetMs 时间预算：书架要等它，不能让冷启动被几十本书拖住
     * @return 还没补完的条数（&gt;0 说明下次进来还要再来一轮）
     */
    public int ensureBookLite(long budgetMs) {
        // 阅读页在前台：lite 回填只是书架的优化项，读者不该为它等锁。
        // 冷启动后第一次开书之所以「究极慢」，一半原因就是这里正握着数据库锁
        // 解析整本 json（含几千章目录 + 内联封面），章节读取排在它后面。
        if (quietMode) return countLitePending();
        long end = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < end) {
            String id = null;
            String json = null;
            // 只把「取一条待补记录」放进锁里：解析 json、写封面文件都在锁外做。
            // 以前整个方法都是 synchronized，一本书要解析几十毫秒，七八本连着补，
            // 期间阅读页想读一章正文只能干等 —— 首次进书就卡在这上面。
            synchronized (this) {
                Cursor c = null;
                try {
                    SQLiteDatabase db = getWritableDatabase();
                    c = db.rawQuery("SELECT id, json FROM books WHERE lite IS NULL OR lite='' LIMIT 1", null);
                    if (c.moveToFirst()) {
                        id = c.getString(0);
                        json = c.isNull(1) ? null : c.getString(1);
                    }
                } catch (Exception ignore) {
                    /* 查询失败就直接收工 */
                } finally {
                    if (c != null) try {
                        c.close();
                    } catch (Exception ignore) {
                        /* 无所谓 */
                    }
                }
            }
            if (id == null) return 0;

            String lite = null;      // 算好的 lite 文本（锁外算）
            String fullJson = null;  // 封面落盘后要整条重写的 json
            boolean broken = false;
            try {
                if (json == null || json.isEmpty()) {
                    lite = "{}";
                } else {
                    JSONObject o = new JSONObject(json);
                    String cover = o.optString("coverUrl", "");
                    String nc = coverToFile(cover, id);
                    if (!nc.equals(cover)) {
                        // 有内联封面：整条重写一次，把封面换成文件路径
                        o.put("coverUrl", nc);
                        fullJson = o.toString();
                    } else {
                        lite = liteOf(o);
                    }
                }
            } catch (Exception e) {
                broken = true;
            }
            synchronized (this) {
                try {
                    SQLiteDatabase db = getWritableDatabase();
                    if (broken) {
                        // 坏记录：写个空 lite 占位，避免每次进来都卡在同一条上
                        db.execSQL("UPDATE books SET lite='{}' WHERE id=?", new Object[]{id});
                    } else if (fullJson != null) {
                        put("books", fullJson);
                    } else {
                        db.execSQL("UPDATE books SET lite=? WHERE id=?", new Object[]{lite, id});
                    }
                } catch (Exception ignore) {
                    /* 还是失败就下次再说 */
                }
            }
        }
        return countLitePending();
    }

    /** 阅读页是否在前台（true 时后台的整本 json 回填一律暂停） */
    private static volatile boolean quietMode;

    static void setQuiet(boolean q) {
        quietMode = q;
    }

    /** 阅读页是否在前台：书架的后台回填据此让路 */
    static boolean isQuiet() {
        return quietMode;
    }

    private int countLitePending() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(1) FROM books WHERE lite IS NULL OR lite=''", null);
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception ignore) {
            /* 查不到当没有剩余 */
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
        return 0;
    }

    /**
     * 全部本地导入书的 bookUrl（形如 local://名字#字节数）。
     *
     * 只读主键：books 的 json 列里塞着整份章节表，一本 2000 章的书几十 KB，
     * 为了判断「这本书导入过没有」把它读出来再解析，扫一次书就是白搬几 MB。
     * id 的前缀就是 bookUrl（id = "local://||" + bookUrl），截掉即可。
     */
    public synchronized List<String> localBookUrls() {
        List<String> out = new ArrayList<>();
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            // 前缀区间而不是 LIKE '%…%'：后者放弃主键索引，全表扫一遍
            c = db.rawQuery("SELECT id FROM books WHERE id >= ? AND id < ?",
                    new String[]{"local://||", "local://||\uffff"});
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id != null && id.length() > 10) out.add(id.substring(10));
            }
        } catch (Exception e) {
            /* 空表/脏数据：当没有本地书 */
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
        return out;
    }

    /**
     * 某列的总字节数（SQL SUM(LENGTH(...))，不把内容搬进内存）。
     *
     * 这是原生 SQL 相对 IndexedDB 的硬优势：统计正文缓存体积，IndexedDB 只能开游标
     * 把每章 HTML 逐条读进 JS 堆（db.js 注释明确警告过会卡死/闪退），SQL 一条聚合搞定。
     */
    public synchronized long sumLength(String table, String column) {
        if (!valid(table) || column == null) return 0;
        if (!"content".equals(column) && !"json".equals(column) && !"v".equals(column)) return 0;
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.rawQuery("SELECT SUM(LENGTH(" + column + ")) FROM " + table, null);
            if (c.moveToFirst()) return c.getLong(0);
            return 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
    }

    /** 行数 */
    public synchronized long count(String table) {
        if (!valid(table)) return 0;
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.rawQuery("SELECT COUNT(*) FROM " + table, null);
            if (c.moveToFirst()) return c.getLong(0);
            return 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
    }

    /* ---------- 写 ---------- */

    /** 写一条（整对象 JSON）。主键缺失或表名非法返回 false */
    public synchronized boolean put(String table, String objJson) {
        if (!valid(table) || objJson == null) return false;
        try {
            JSONObject o = new JSONObject(objJson);
            String pk = pkOf(table);
            String key = o.optString(pk, null);
            if (key == null) return false;

            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(pk, key);
            if ("books".equals(table)) {
                // 内联 base64 封面搬到文件：一份 300KB 的封面会被书架、目录、桥
                // 各读一遍，落到 files/covers/ 后记录里只剩一个几十字节的路径
                String cover = coverToFile(o.optString("coverUrl", ""), key);
                if (!cover.equals(o.optString("coverUrl", ""))) {
                    o.put("coverUrl", cover);
                    objJson = o.toString();
                }
                // 常用字段建列：将来原生书架排序/分组直接走 SQL
                cv.put("name", o.optString("name", null));
                cv.put("author", o.optString("author", null));
                cv.put("grp", o.optString("group", null));
                cv.put("kind", o.optString("kind", null));
                cv.put("addedAt", o.optLong("addedAt", 0));
                cv.put("updatedAt", o.optLong("updatedAt", 0));
                cv.put("json", objJson);
                if (liteReady) cv.put("lite", liteOf(o));
            } else if ("chapters".equals(table)) {
                cv.put("content", o.optString("content", null));
                cv.put("cachedAt", o.optLong("cachedAt", 0));
            } else if ("settings".equals(table)) {
                cv.put("v", o.optString("v", null));
            } else {
                cv.put("json", objJson);
            }
            long r = db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            if (r != -1 && "settings".equals(table)) cacheSetting(key, o.optString("v", null));
            return r != -1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 批量写（同一事务）。导入一本书上百章、迁移整个书架都靠它：
     * 逐条 autocommit 会把每章都刷一次磁盘，几百章直接卡到几十秒。
     */
    public synchronized int putMany(String table, String arrJson) {
        if (!valid(table) || arrJson == null) return 0;
        int n = 0;
        SQLiteDatabase db = null;
        try {
            JSONArray arr = new JSONArray(arrJson);
            String pk = pkOf(table);
            db = getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String key = o.optString(pk, null);
                    if (key == null) continue;

                    ContentValues cv = new ContentValues();
                    cv.put(pk, key);
                    if ("books".equals(table)) {
                        String cover = coverToFile(o.optString("coverUrl", ""), key);
                        if (!cover.equals(o.optString("coverUrl", ""))) o.put("coverUrl", cover);
                        cv.put("name", o.optString("name", null));
                        cv.put("author", o.optString("author", null));
                        cv.put("grp", o.optString("group", null));
                        cv.put("kind", o.optString("kind", null));
                        cv.put("addedAt", o.optLong("addedAt", 0));
                        cv.put("updatedAt", o.optLong("updatedAt", 0));
                        cv.put("json", o.toString());
                        if (liteReady) cv.put("lite", liteOf(o));
                    } else if ("chapters".equals(table)) {
                        cv.put("content", o.optString("content", null));
                        cv.put("cachedAt", o.optLong("cachedAt", 0));
                    } else if ("settings".equals(table)) {
                        cv.put("v", o.optString("v", null));
                    } else {
                        cv.put("json", o.toString());
                    }
                    if (db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1) {
                        if ("settings".equals(table)) cacheSetting(key, o.optString("v", null));
                        n++;
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            /* 事务已回滚，返回已成功条数（通常为 0） */
        }
        return n;
    }

    /**
     * 批量写（同一事务），入参已是 JSONObject 数组。
     *
     * 与 putMany 的区别只是**不再把数组序列化成字符串再解析回来**：
     * 导入一本带插图的书时这一来一回要搬几十 MB 文本，白白多两轮 GC。
     */
    public synchronized int putManyRows(String table, org.json.JSONArray rows) {
        if (!valid(table) || rows == null) return 0;
        int n = 0;
        try {
            String pk = pkOf(table);
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject o = rows.optJSONObject(i);
                    if (o == null) continue;
                    String key = o.optString(pk, null);
                    if (key == null) continue;
                    ContentValues cv = new ContentValues();
                    cv.put(pk, key);
                    if ("books".equals(table)) {
                        String cover = coverToFile(o.optString("coverUrl", ""), key);
                        if (!cover.equals(o.optString("coverUrl", ""))) o.put("coverUrl", cover);
                        cv.put("name", o.optString("name", null));
                        cv.put("author", o.optString("author", null));
                        cv.put("grp", o.optString("group", null));
                        cv.put("kind", o.optString("kind", null));
                        cv.put("addedAt", o.optLong("addedAt", 0));
                        cv.put("updatedAt", o.optLong("updatedAt", 0));
                        cv.put("json", o.toString());
                        if (liteReady) cv.put("lite", liteOf(o));
                    } else if ("chapters".equals(table)) {
                        cv.put("content", o.optString("content", null));
                        cv.put("cachedAt", o.optLong("cachedAt", 0));
                    } else if ("settings".equals(table)) {
                        cv.put("v", o.optString("v", null));
                    } else {
                        cv.put("json", o.toString());
                    }
                    if (db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1) {
                        if ("settings".equals(table)) cacheSetting(key, o.optString("v", null));
                        n++;
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            /* 事务已回滚 */
        }
        return n;
    }

    /**
     * 导入专用的正文批量写：预编译语句 + 单事务，直接吃字符串列表。
     *
     * 之前这条路是 JSONObject → JSONArray → ContentValues → insertWithOnConflict，
     * 一章正文要被复制三遍（JSONObject.put、optString、cv.put），几千章的书光拷贝
     * 就是几十 MB，中间还夹着大量临时对象让 GC 一直打断导入线程。
     * 这里 bind 的就是原文引用，SQL 也只编译一次。
     *
     * @param startIndex 批内第一项在全书里的章节下标（主键是 bookId||index）
     */
    public synchronized int putChapters(String bookId, int startIndex, List<String> htmls, long cachedAt) {
        if (bookId == null || bookId.isEmpty() || htmls == null || htmls.isEmpty()) return 0;
        int n = 0;
        SQLiteStatement st = null;
        try {
            SQLiteDatabase db = getWritableDatabase();
            st = db.compileStatement(
                    "INSERT OR REPLACE INTO chapters (id, content, cachedAt) VALUES (?,?,?)");
            db.beginTransactionNonExclusive();
            boolean ok = false;
            try {
                for (int i = 0; i < htmls.size(); i++) {
                    String h = htmls.get(i);
                    st.clearBindings();
                    st.bindString(1, chapterKey(bookId, startIndex + i));
                    st.bindString(2, h == null ? "" : h);
                    st.bindLong(3, cachedAt);
                    st.executeInsert();
                    n++;
                }
                db.setTransactionSuccessful();
                ok = true;
            } finally {
                db.endTransaction();
            }
            // 事务回滚了就等于一条都没写进去。以前照样把 n 当「成功条数」返回，
            // 导入侧于是认为这批写完了，最后书架上是一本章录齐全、正文缺几段的书 ——
            // 用户点进去只看到「这一章没有正文」。这里如实归零，让导入侧能发现问题。
            if (!ok) n = 0;
        } catch (Exception e) {
            /* 事务已回滚，返回已成功条数（调用方可按返回值提示） */
        } finally {
            if (st != null) {
                try {
                    st.close();
                } catch (Exception ignore) {
                    /* 关不掉也无所谓 */
                }
            }
        }
        return n;
    }

    public synchronized boolean del(String table, String key) {
        if (!valid(table) || key == null) return false;
        try {
            SQLiteDatabase db = getWritableDatabase();
            boolean ok = db.delete(table, pkOf(table) + "=?", new String[]{key}) > 0;
            if (ok && "settings".equals(table) && setCacheReady) setCache.remove(key);
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean clear(String table) {
        if (!valid(table)) return false;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(table, null, null);
            if ("settings".equals(table) && setCacheReady) setCache.clear();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------- 存储体检 / 回收 ---------- */

    /** 当前数据库全部页占用的字节（不等于 live bytes，空闲页也算） */
    private static long pragmaLong(SQLiteDatabase db, String sql) {
        Cursor c = null;
        try {
            c = db.rawQuery(sql, null);
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignore) {
            /* PRAGMA 不支持就当 0 */
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
        return 0;
    }

    /** SQLite freelist 的空闲页字节数：删书/清缓存后不 VACUUM 就一直占着 */
    public synchronized long dbFreeBytes() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            return pragmaLong(db, "PRAGMA freelist_count") * pragmaLong(db, "PRAGMA page_size");
        } catch (Exception e) {
            return 0;
        }
    }

    /** 当前库的页总数（含 freelist），用于判断真空比 */
    public synchronized long dbPageBytes() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            return pragmaLong(db, "PRAGMA page_count") * pragmaLong(db, "PRAGMA page_size");
        } catch (Exception e) {
            return 0;
        }
    }

    /** 把 WAL 里的已提交页并回主库，并尽量截断 WAL 文件 */
    public synchronized void checkpoint() {
        Cursor c = null;
        try {
            SQLiteDatabase db = getWritableDatabase();
            c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null);
        } catch (Exception ignore) {
            /* 不支持 WAL / 有读者占用：退化为下次再说 */
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
    }

    /**
     * 回收空页：只在「确实有大量 freelist 且磁盘余量足够」时做。
     *
     * VACUUM 会复制一份完整库，至少需要 1 倍库大小的临时空间；空间不够时宁可
     * 不整理，也不能把用户仅剩的空间撑爆。返回是否真的执行了。
     */
    public synchronized boolean compactIfNeeded(long minFreeBytes) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            checkpoint();
            long free = dbFreeBytes();
            if (free < Math.max(0, minFreeBytes)) return false;
            String path = db.getPath();
            long dbSize = new File(path).length();
            File dir = new File(path).getParentFile();
            android.os.StatFs stat = new android.os.StatFs(dir.getAbsolutePath());
            if (stat.getAvailableBytes() < dbSize * 2 + (32L << 20)) return false;
            db.execSQL("VACUUM");
            checkpoint();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 全部书 id；只读主键，不碰 json。 */
    public synchronized Set<String> bookIdSet() {
        Set<String> out = new HashSet<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT id FROM books", null);
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id != null) out.add(id);
            }
        } catch (Exception ignore) {
            /* 空库 */
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
        return out;
    }

    /**
     * chapters 里出现过的 bookId（主键最后一段是章号，前面的全是 bookId）。
     * 只读 id 列，千章一本书也只搬几 KB。
     */
    public synchronized Set<String> chapterBookIdSet() {
        Set<String> out = new HashSet<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT id FROM chapters", null);
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id == null) continue;
                int cut = id.lastIndexOf("||");
                if (cut > 0) out.add(id.substring(0, cut));
            }
        } catch (Exception ignore) {
            /* 空表 */
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
        return out;
    }

    /**
     * 删掉「书已经不在书架」的孤儿正文。
     *
     * removeBook 默认保留缓存是为了让用户反悔时还能读；但如果用户再也不加回来，
     * 这部分正文会永远占着库。这里只删「最后缓存时间早于 cutoff」的孤儿，
     * 给刚移出书架的书留一个后悔窗口。
     */
    public synchronized int deleteOrphanChapters(long cutoffMs) {
        Set<String> books = bookIdSet();
        Map<String, Long> lastAt = new HashMap<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT id, cachedAt FROM chapters", null);
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id == null) continue;
                int cut = id.lastIndexOf("||");
                if (cut <= 0) continue;
                String bid = id.substring(0, cut);
                if (books.contains(bid)) continue;
                long at = c.isNull(1) ? 0 : c.getLong(1);
                Long old = lastAt.get(bid);
                if (old == null || at > old) lastAt.put(bid, at);
            }
        } catch (Exception ignore) {
            return 0;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
        int n = 0;
        for (Map.Entry<String, Long> e : lastAt.entrySet()) {
            if (e.getValue() > 0 && e.getValue() >= cutoffMs) continue;
            n += delChaptersOf(e.getKey());
        }
        return n;
    }

    /**
     * 在线书章节缓存上限（LRU 近似：按 cachedAt 从旧到新删）。
     *
     * 本地导入书的正文是「书本身」，绝不能按缓存淘汰；只限制非 local:// 的在线缓存。
     * 这是 Legado ACache 的同款思路，只是数据在 SQLite 里，不需要把正文搬进内存。
     */
    public synchronized long trimOnlineChapterCache(long maxBytes) {
        if (maxBytes <= 0) return 0;
        long before = 0;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT SUM(LENGTH(CAST(content AS BLOB))) FROM chapters WHERE id NOT LIKE 'local://%'", null);
            if (c.moveToFirst() && !c.isNull(0)) before = c.getLong(0);
        } catch (Exception ignore) {
            return 0;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignore) { }
        }
        if (before <= maxBytes) return 0;
        long need = before - maxBytes;
        long freed = 0;
        while (freed < need) {
            List<String> ids = new ArrayList<>();
            long batch = 0;
            Cursor d = null;
            try {
                d = getReadableDatabase().rawQuery(
                        "SELECT id, LENGTH(CAST(content AS BLOB)) FROM chapters "
                                + "WHERE id NOT LIKE 'local://%' ORDER BY cachedAt ASC LIMIT 200", null);
                while (d.moveToNext() && batch < need - freed) {
                    String id = d.getString(0);
                    long len = d.isNull(1) ? 0 : d.getLong(1);
                    if (id != null) {
                        ids.add(id);
                        batch += len;
                    }
                }
            } catch (Exception ignore) {
                break;
            } finally {
                if (d != null) try { d.close(); } catch (Exception ignore) { }
            }
            if (ids.isEmpty()) break;
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransactionNonExclusive();
            try {
                for (String id : ids) db.delete("chapters", "id=?", new String[]{id});
                db.setTransactionSuccessful();
                freed += batch;
            } finally {
                db.endTransaction();
            }
        }
        return freed;
    }

    /* ---------- 原生阅读页专用（免 RPC 进 JS） ---------- */

    /** chapters 主键：bookId + '||' + index（与 web/js/ui/reader.js 一致） */
    public static String chapterKey(String bookId, int index) {
        return bookId + "||" + index;
    }

    /**
     * 取某章正文 HTML（未缓存返回 null）。
     *
     * 原生阅读页翻章时先查这里：命中就直接排版出屏，不再 evalJs 回网页侧；
     * 未命中（在线书源还没抓过的章）才回退 JS 的书源引擎去抓。
     */
    public synchronized String chapterContent(String bookId, int index) {
        if (bookId == null || bookId.isEmpty()) return null;
        return readTextByKey("chapters", "content", chapterKey(bookId, index));
    }

    /**
     * 按主键读一个可能很长的文本字段，**分段取**。
     *
     * 为什么不能直接 SELECT content：Android 的 CursorWindow 单行上限是 2MB，
     * 一行装不下时 SQLite 直接抛 "Row too big to fit into CursorWindow"，
     * 而这里过去把异常吞成 null —— 上层只看到「这一章没有正文」。
     *
     * 哪来的这么大的行：EPUB 里的插图会被内联成 base64 写进章节 HTML，
     * 一张 3~4MB 的图就让一行到 5MB 以上。表现就是：
     * 同一本 EPUB 别的软件打开正常，这里从第二、三章起「换章一直提示没有正文」，
     * 重新导入也没用 —— 因为问题不在导入，在读取。
     *
     * 现在先 length() 量长度（不搬正文），小字段一次取完，大的按 12 万字一片
     * 用 substr 拼回来。一片中文约 360KB，稳稳落在窗口内。
     */
    private String readTextByKey(String table, String col, String key) {
        final int CHUNK = 120000;
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            long len = -1;
            c = db.rawQuery("SELECT length(" + col + ") FROM " + table + " WHERE " + pkOf(table) + "=?",
                    new String[]{key});
            if (!c.moveToFirst()) return null;
            len = c.isNull(0) ? -1 : c.getLong(0);
            c.close();
            c = null;
            if (len < 0) return null;
            if (len == 0) return "";
            if (len <= CHUNK) {
                c = db.rawQuery("SELECT " + col + " FROM " + table + " WHERE " + pkOf(table) + "=?",
                        new String[]{key});
                if (c.moveToFirst()) return c.isNull(0) ? null : c.getString(0);
                return null;
            }
            StringBuilder sb = new StringBuilder((int) Math.min(len, 4L << 20));
            for (long start = 1; start <= len; start += CHUNK) {
                if (c != null) {
                    c.close();
                    c = null;
                }
                c = db.rawQuery("SELECT substr(" + col + ",?,?) FROM " + table
                                + " WHERE " + pkOf(table) + "=?",
                        new String[]{String.valueOf(start), String.valueOf(CHUNK), key});
                if (!c.moveToFirst()) break;
                String part = c.isNull(0) ? null : c.getString(0);
                if (part == null || part.isEmpty()) break;
                sb.append(part);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
    }

    /**
     * 缓存一章正文（原生侧自己抓到正文后写入，供下次本地命中）。
     * 主键与 JS 侧 chKey 保持一致（bookId||index），否则两边各存一份、白占一倍空间。
     */
    public synchronized boolean cacheChapter(String bookId, int index, String html) {
        if (bookId == null || bookId.isEmpty() || html == null) return false;
        try {
            JSONObject o = new JSONObject();
            o.put("id", chapterKey(bookId, index));
            o.put("content", html);
            o.put("cachedAt", System.currentTimeMillis());
            return put("chapters", o.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 写阅读进度（原生阅读页直接落库，不再依赖 JS 持久化）。
     *
     * 两个必须小心处理的字段：
     *  - chapterUrl：章节地址只有 JS 侧的书源引擎知道，原生拿不到，覆盖成空会让
     *    「重新加载本章」之类的功能失效；
     *  - page：网页版的「第几页」，与原生 StaticLayout 的字符偏移数值不通用，
     *    所以章内位置写原生专用的 nativeOffset，page 原样保留（见方法内注释）。
     * readMs 是累计值，addMs 只累加本次这一段。其余旧字段一并带走，避免被写窄。
     */
    public synchronized boolean putProgress(String bookId, int index, int nativeOffset, long addMs) {
        if (bookId == null || bookId.isEmpty()) return false;
        try {
            JSONObject old = null;
            String s = get("progress", bookId);
            if (s != null) {
                try {
                    old = new JSONObject(s);
                } catch (Exception e) {
                    old = null;
                }
            }
            JSONObject o = new JSONObject();
            o.put("bookId", bookId);
            o.put("index", index);
            o.put("chapterUrl", old != null ? old.optString("chapterUrl", "") : "");
            // 章内位置写原生专用字段：page 在网页版里是「第几页」，与原生字符偏移
            // 数值体系不通用，写入会让网页版把偏移当页码，所以原样保留不覆盖
            o.put("nativeOffset", nativeOffset);
            // 最近一次读这本书的时间：书架「按最近阅读排序」全靠这个字段。
            // 之前漏写，导致「最近阅读」实际退化成「加入时间」，读得再勤书架也不动。
            o.put("updatedAt", System.currentTimeMillis());
            if (old != null && old.has("page")) o.put("page", old.opt("page"));
            o.put("readMs", (old != null ? old.optLong("readMs", 0) : 0) + (addMs > 0 ? addMs : 0));
            if (old != null) {
                Iterator<String> it = old.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!o.has(k)) o.put(k, old.opt(k));
                }
            }
            // 书名/作者/最近阅读时间同时落到 books 行上。
            //
            // 「最近阅读」排序靠的是 books.updatedAt 与 progress.updatedAt 取大：
            // 只写 progress 的话，progress 里查不到这本（旧数据没有 bookId、或本地书 id
            // 里的字节数变了导致 id 对不上）时排序就退回「加入时间」——用户看到的正是
            // 「读了几本顺序对了，重启又变回去」。books 行是书架自己读的表，一定在。
            touchBook(bookId, o.optLong("updatedAt", System.currentTimeMillis()));
            return put("progress", o.toString());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 一本书的章数。
     *
     * 入口（书架 / 详情 / 目录）只需要知道「一共几章」来算进度和钳制章号，
     * 不必为了这个数字把整份目录（几千项 chapters 数组）解析进内存。
     * 优先读 lite 里的 chapterCount，实在没有才回落到 json。
     */
    public synchronized int chapterCount(String bookId) {
        if (bookId == null || bookId.isEmpty()) return 0;
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            c = db.rawQuery("SELECT lite, json FROM books WHERE id=?", new String[]{bookId});
            if (!c.moveToFirst()) return 0;
            String lite = c.isNull(0) ? null : c.getString(0);
            if (lite != null && lite.startsWith("{")) {
                try {
                    int n = new JSONObject(lite).optInt("chapterCount", 0);
                    if (n > 0) return n;
                } catch (Exception ignore) {
                    /* 坏 lite：往下读 json */
                }
            }
            String json = c.isNull(1) ? null : c.getString(1);
            if (json != null && json.startsWith("{")) {
                try {
                    JSONArray chs = new JSONObject(json).optJSONArray("chapters");
                    return chs == null ? 0 : chs.length();
                } catch (Exception ignore) {
                    /* 坏记录当 0 */
                }
            }
        } catch (Exception ignore) {
            /* 读不到当 0：入口会退化成「不知道总数」，阅读页仍能翻 */
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
        return 0;
    }

    /**
     * 只更新书籍行的「最近阅读时间」。
     *
     * 一条 UPDATE 改 columns 与 lite 里的 updatedAt（lite 就几百字节，不会像整份
     * json 那样把几千章搬进内存）。翻页保存进度时每秒最多一次，开销可忽略。
     */
    public synchronized void touchBook(String bookId, long at) {
        if (bookId == null || bookId.isEmpty() || at <= 0) return;
        Cursor c = null;
        try {
            SQLiteDatabase db = getWritableDatabase();
            c = db.query("books", new String[]{"lite"}, "id=?", new String[]{bookId},
                    null, null, null, "1");
            if (!c.moveToFirst()) return; // 试读/已删的书：没有行，不用记
            String lite = c.isNull(0) ? null : c.getString(0);
            ContentValues cv = new ContentValues();
            cv.put("updatedAt", at);
            if (lite != null && lite.startsWith("{")) {
                try {
                    JSONObject o = new JSONObject(lite);
                    o.put("updatedAt", at);
                    if (liteReady) cv.put("lite", o.toString());
                } catch (Exception ignore) {
                    /* lite 坏了：只更新列，读取侧会按列兜底 */
                }
            }
            db.update("books", cv, "id=?", new String[]{bookId});
        } catch (Exception ignore) {
            /* 时间戳写不上不影响阅读本身 */
        } finally {
            if (c != null) try {
                c.close();
            } catch (Exception ignore) {
                /* 无所谓 */
            }
        }
    }

    /**
     * 按书删除其全部章节缓存（移出书架时用）。
     *
     * chapters 主键是 `bookId||index`，按「前缀区间」删除：`id >= 'bookId||'` 且
     * `id < 'bookId||\uffff'`。bookId 走参数绑定，所以书 id 里带 % 或 _ 也不会误删。
     */
    /**
     * 书库占用的字节数（主库 + WAL + SHM）。
     *
     * 正文明文按章存在这里，是 App 占空间的大头；把它显示出来，
     * 「怎么占了这么多」才有得对照（以前只能靠猜）。
     */
    public long dbBytes() {
        long n = 0;
        try {
            String p = getReadableDatabase().getPath();
            for (String suf : new String[]{"", "-wal", "-shm"}) {
                java.io.File f = new java.io.File(p + suf);
                if (f.exists()) n += f.length();
            }
        } catch (Exception ignore) {
            /* 打不开就算 0 */
        }
        return n;
    }

    public synchronized int delChaptersOf(String bookId) {
        if (bookId == null || bookId.isEmpty()) return 0;
        try {
            SQLiteDatabase db = getWritableDatabase();
            // 用「前缀范围」而不是 LIKE：LIKE 'x%' 在 SQLite 默认配置下不会走主键索引，
            // 几千章的表要逐行扫一遍（每行还带着几 KB 正文）——重复导入一本书时最明显。
            // id 是 TEXT 主键，范围比较走索引，只碰真正要删的那几行。
            String lo = bookId + "||";
            return db.delete("chapters", "id >= ? AND id < ?", new String[]{lo, lo + '\uffff'});
        } catch (Exception e) {
            return 0;
        }
    }

    /** 某本书的章节数（详情页/书架显示用，只数行不搬正文） */
    public synchronized int countChaptersOf(String bookId) {
        if (bookId == null || bookId.isEmpty()) return 0;
        try {
            SQLiteDatabase db = getReadableDatabase();
            String lo = bookId + "||";
            android.database.Cursor c = db.rawQuery(
                    "SELECT COUNT(1) FROM chapters WHERE id >= ? AND id < ?",
                    new String[]{lo, lo + '\uffff'});
            try {
                return c.moveToFirst() ? c.getInt(0) : 0;
            } finally {
                c.close();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    /* ---------- settings 便捷读写（原生设置页 / 书架排序分组用） ----------
     * settings 表的 v 列存的是「值的 JSON 文本」（与 db.js 的 encodeSetting 一致）：
     * 字符串值存 "light"（带引号），数字存 18，数组存 [...]。
     * 所以写入时必须先 JSON 化，读出时再解一层，否则会写出裸字符串、
     * 前端 JSON.parse 失败后原样返回，表现为「设置改了没生效」。 */

    /**
     * IndexedDB → SQLite 的迁移是否已完成。
     *
     * 原生屏直接查 SQLite；若用户数据还留在 WebView 的 IndexedDB 里没迁过来，
     * 原生书架就会读到空库——用户只看到「书架空了」，根本想不到是迁移没跑完。
     * 所以原生屏要先问这个，空库 + 未完成 时催一次迁移并如实提示。
     */
    public boolean migrated() {
        try {
            return ctx.getSharedPreferences("modou_store", Context.MODE_PRIVATE)
                    .getBoolean("migrated", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 取某项设置的原始 JSON 文本（如 "light" / 18 / [...]）；不存在返回 null */
    public String settingRaw(String key) {
        if (key == null) return null;
        // 快照已就绪：全在内存，不再碰数据库（冷启动进书的主线程路径全靠这一条）
        if (setCacheReady) {
            if (setCache.containsKey(key)) return setCache.get(key);
            return null;
        }
        String s = get("settings", key);
        if (s == null) return null;
        try {
            return new JSONObject(s).optString("v", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 写设置时同步维护内存快照（快照没被预载过就不管，读的时候仍走库） */
    private void cacheSetting(String key, String v) {
        if (key == null || !setCacheReady) return;
        setCache.put(key, v);
    }

    /** 取字符串型设置 */
    public String settingStr(String key, String dft) {
        String raw = settingRaw(key);
        if (raw == null) return dft;
        try {
            return new JSONArray("[" + raw + "]").optString(0, dft);
        } catch (Exception e) {
            return raw; // 历史脏数据（裸字符串）：原样给
        }
    }

    /** 取数组型设置（shelfGroups 等）；不是数组时返回 null */
    public JSONArray settingArr(String key) {
        String raw = settingRaw(key);
        if (raw == null) return null;
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 写字符串型设置 */
    public boolean putSettingStr(String key, String value) {
        return putSetting(key, value == null ? "null" : JSONObject.quote(value));
    }

    /** 写设置；vJson 必须是合法的 JSON 文本（数字/布尔/数组/对象/带引号字符串） */
    public boolean putSetting(String key, String vJson) {
        if (key == null) return false;
        try {
            JSONObject o = new JSONObject();
            o.put("k", key);
            o.put("v", vJson == null ? "null" : vJson);
            return put("settings", o.toString());
        } catch (Exception e) {
            return false;
        }
    }
}
