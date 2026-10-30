/**
 * db.js — 存储层：SQLite（原生共享层）优先，IndexedDB 回退。
 *
 * 双通道的由来：数据原本只存在 WebView 的 IndexedDB 里，原生屏要读一本书只能
 * RPC 进 JS，这是「逐模块原生化」的地基障碍。现在原生侧有一份 SQLite（ModouStore），
 * 本模块在 App 内把读写切到 SQLite，让原生与网页共用同一份数据（唯一真相源）；
 * 桌面浏览器没有桥，自动回退 IndexedDB。
 *
 * 对外 API（db.* / settings.*）与旧版完全一致，上层 app.js / reader.js / book.js /
 * sources.js / settings.js 零改动。
 *
 * 迁移：App 内首次调用 initStore() 把 IndexedDB 全量搬进 SQLite（分批，控制内存峰值），
 * 完成与否记在原生 SharedPreferences（不写业务表，避免污染设置导出）。迁移中断不会
 * 切换通道，下次启动重试——宁可多跑一次，也不让上层读到半份数据。
 *
 * stores: sources / books / progress / chapters / replaceRules / settings / bookmarks
 */
const NAME = 'modou';
const VERSION = 3;
let _db = null;

/** 各 store 的主键路径 */
const KEY_PATHS = {
  sources: 'id',
  books: 'id',
  progress: 'bookId',
  chapters: 'id',
  replaceRules: 'id',
  settings: 'k',
  bookmarks: 'id',
};

/* ==================== 通道一：IndexedDB ==================== */

function open() {
  if (_db) return Promise.resolve(_db);
  return new Promise((resolve, reject) => {
    const r = indexedDB.open(NAME, VERSION);
    r.onupgradeneeded = (e) => {
      const d = e.target.result;
      for (const s of Object.keys(KEY_PATHS)) {
        if (!d.objectStoreNames.contains(s)) d.createObjectStore(s, { keyPath: KEY_PATHS[s] });
      }
    };
    r.onsuccess = () => {
      _db = r.result;
      resolve(_db);
    };
    r.onerror = () => reject(r.error);
    // blocked = 别的连接还占着旧版本，此时既不会 success 也不会 error，
    // Promise 会永远挂着、启动流程卡死在白屏。宁可失败让上层走兜底。
    r.onblocked = () => reject(new Error('indexeddb blocked'));
  });
}

function tx(store, mode, fn) {
  return open().then(
    (d) =>
      new Promise((resolve, reject) => {
        const t = d.transaction(store, mode);
        const req = fn(t.objectStore(store));
        t.oncomplete = () => resolve(req && req.result !== undefined ? req.result : undefined);
        t.onerror = () => reject(t.error);
        t.onabort = () => reject(t.error);
      })
  );
}

const idb = {
  all: (store) => tx(store, 'readonly', (s) => s.getAll()),
  keys: (store) => tx(store, 'readonly', (s) => s.getAllKeys()),
  get: (store, key) => tx(store, 'readonly', (s) => s.get(key)),
  put: (store, obj, key) =>
    tx(store, 'readwrite', (s) => (key !== undefined ? s.put(obj, key) : s.put(obj))),
  del: (store, key) => tx(store, 'readwrite', (s) => s.delete(key)),
  clear: (store) => tx(store, 'readwrite', (s) => s.clear()),
  /**
   * 游标逐条遍历（内存峰值只有一条记录）。
   *
   * 统计正文缓存总大小时千万别 db.all('chapters')：那会把每个章节的完整 HTML
   * 一次性搬进 JS 堆，书一多就是几百 MB 甚至上 GB，直接卡死/闪退。游标流式累加。
   */
  forEach: (store, fn) =>
    open().then(
      (d) =>
        new Promise((resolve, reject) => {
          const t = d.transaction(store, 'readonly');
          const req = t.objectStore(store).openCursor();
          req.onsuccess = () => {
            const c = req.result;
            if (c) {
              try {
                fn(c.value, c.key);
              } catch (e) {
                /* 单条处理异常不中断 */
              }
              c.continue();
            }
          };
          t.oncomplete = () => resolve();
          t.onerror = () => reject(t.error);
        })
    ),
  /** 批量写（同事务） */
  putMany: (store, objs) =>
    open().then(
      (d) =>
        new Promise((resolve, reject) => {
          const t = d.transaction(store, 'readwrite');
          const s = t.objectStore(store);
          for (const o of objs) s.put(o);
          t.oncomplete = () => resolve();
          t.onerror = () => reject(t.error);
        })
    ),
};

/* ==================== 通道二：SQLite（原生桥） ==================== */

const BR = () => (typeof window !== 'undefined' && window.ModouBridge) || null;

/** SQLite 通道是否可用（有桥且存储层就绪） */
const sqlReady = () => {
  const b = BR();
  return !!(b && typeof b.storeGet === 'function' && (!b.storeReady || b.storeReady()));
};

const parseArr = (s) => {
  try {
    return JSON.parse(s || '[]') || [];
  } catch (e) {
    return [];
  }
};

/**
 * settings 的 v 编解码。
 *
 * SQLite 里 v 列统一存 JSON.stringify 后的文本，Java 侧不做值的语义解析：
 * 存 '"paper"'（带引号），取回 JSON.parse 得到 'paper'；对象/数组同理。
 * 这样 db.get('settings', k) 拿到的 {k, v} 与 IndexedDB 完全一致，上层无感。
 * 历史脏数据（v 是非 JSON 裸字符串）parse 失败时保持原值，不抛错。
 */
const encodeSetting = (o) => ({ k: o.k, v: o.v === undefined ? null : JSON.stringify(o.v) });
const decodeSetting = (o) => {
  if (!o || o.k === undefined) return o;
  let v = o.v;
  if (typeof v === 'string') {
    try {
      v = JSON.parse(v);
    } catch (e) {
      /* 非 JSON 裸字符串：保持原样 */
    }
  }
  return { k: o.k, v };
};

const sql = {
  all: async (store) => {
    const rows = parseArr(BR().storeAll(store));
    return store === 'settings' ? rows.map(decodeSetting) : rows;
  },
  /**
   * books 的轻量全表：只含书名/作者/封面/分组/时间/章节数，不含 chapters 数组。
   * 书架页已原生化，网页启动只需要这些做标记；详情页/阅读页要章节时按 id 单独取
   * （见 findBook），避免启动时把每本书几千项章节表跨桥搬进 JS 堆。
   */
  allBooksLite: async () => parseArr(BR().storeBooksLite()),
  keys: async (store) => parseArr(BR().storeKeys(store)),
  get: async (store, key) => {
    const s = BR().storeGet(store, String(key));
    if (s == null) return undefined;
    try {
      const o = JSON.parse(s);
      return store === 'settings' ? decodeSetting(o) : o;
    } catch (e) {
      return undefined;
    }
  },
  put: async (store, obj) =>
    BR().storePut(store, JSON.stringify(store === 'settings' ? encodeSetting(obj) : obj)),
  putMany: async (store, objs) =>
    BR().storePutMany(
      store,
      JSON.stringify(objs.map((o) => (store === 'settings' ? encodeSetting(o) : o)))
    ),
  del: async (store, key) => BR().storeDel(store, String(key)),
  clear: async (store) => BR().storeClear(store),
  /** SQLite 没有游标：按 keys 逐条取，保持「内存峰值一条」的语义 */
  forEach: async (store, fn) => {
    const keys = parseArr(BR().storeKeys(store));
    for (const k of keys) {
      const v = await sql.get(store, k);
      if (v != null) {
        try {
          fn(v, k);
        } catch (e) {
          /* 单条处理异常不中断 */
        }
      }
    }
  },
};

/* ==================== 通道切换与迁移 ==================== */

/** true = 走 SQLite；false = 走 IndexedDB（迁移未完成 / 桌面浏览器） */
let useSqlite = false;

let mirror = false; // 后台迁移完成后进入双写，见 db.put 注释

/** 单表分批：chapters 正文可达数 MB，批次必须小，否则一次 JSON.stringify 就是几十 MB */
const BATCH = { chapters: 20 };
const batchOf = (store) => BATCH[store] || 200;

/** 每批之间让出主线程：迁移再要紧也不能把界面按死 */
const yieldNow = () => new Promise((r) => setTimeout(r, 0));

/**
 * 从 afterKey 之后取一批（每批**一个**游标事务）。
 *
 * 早先是「取全部 key → 逐条 get」，等于每条记录开一个 IndexedDB 事务：书架里
 * 几千章正文时，光读取就是几千次往返——叠加跨语言桥的开销，首屏会白等几十秒。
 * 改成每批一个游标事务后，往返次数从「记录数」降到「批次数」。
 */
function idbRangeBatch(store, afterKey, size) {
  const kp = KEY_PATHS[store] || 'id';
  return open().then(
    (d) =>
      new Promise((resolve, reject) => {
        const t = d.transaction(store, 'readonly');
        const os = t.objectStore(store);
        const req =
          afterKey == null ? os.openCursor() : os.openCursor(IDBKeyRange.lowerBound(afterKey, true));
        const out = [];
        req.onsuccess = () => {
          const c = req.result;
          if (!c) return;
          out.push(c.value);
          if (out.length < size) c.continue();
        };
        t.oncomplete = () => resolve(out);
        t.onerror = () => reject(t.error);
        t.onabort = () => reject(t.error);
      })
  );
}

async function migrateStore(store) {
  const b = BR();
  if (!b) return;
  const kp = KEY_PATHS[store] || 'id';
  const size = batchOf(store);
  let after = null;
  for (;;) {
    const rows = await idbRangeBatch(store, after, size);
    if (!rows.length) return;
    const last = rows[rows.length - 1];
    const next = last ? last[kp] : null;
    const objs = rows.map((v) => (store === 'settings' ? encodeSetting(v) : v));
    if (objs.length) b.storePutMany(store, JSON.stringify(objs));
    if (next == null) return;
    after = next;
    await yieldNow();
  }
}

/** 当前旧 IndexedDB 清理任务的句柄（测试/诊断用） */
let legacyDropPromise = null;

/**
 * 删除迁移前的 IndexedDB。
 *
 * 迁移完成后 SQLite 就是唯一真相源，旧库留着只会让「书库体积」翻倍；
 * 这正是 README 里一直挂着的「迁移后磁盘占用暂时翻倍」。删除前必须先关连接，
 * 否则 Chromium 会以 blocked 挂住；blocked 时直接放弃，下次启动再试，不冒险。
 */
function dropLegacyIndexedDb() {
  if (legacyDropPromise) return legacyDropPromise;
  if (!sqlReady() || typeof indexedDB === 'undefined' || typeof indexedDB.deleteDatabase !== 'function') {
    return Promise.resolve(false);
  }
  try {
    if (_db) {
      _db.close();
      _db = null;
    }
  } catch (e) {
    _db = null;
  }
  legacyDropPromise = new Promise((resolve) => {
    let req;
    try {
      req = indexedDB.deleteDatabase(NAME);
    } catch (e) {
      resolve(false);
      return;
    }
    let settled = false;
    const done = (ok) => {
      if (settled) return;
      settled = true;
      resolve(ok);
    };
    req.onsuccess = () => done(true);
    req.onerror = () => done(false);
    // blocked = 还有旧连接没关（例如网页版另一个标签页）：这次不强行删，
    // 下次启动再试。绝不能为了省空间把当前会话的读写打断。
    req.onblocked = () => done(false);
  });
  return legacyDropPromise;
}

/** 旧 IndexedDB 是否已经清掉（测试/诊断用） */
export const whenLegacyIdbCleared = () => (legacyDropPromise ? legacyDropPromise : Promise.resolve(false));

/**
 * 初始化存储层。**全量迁移绝不能挡在首屏前面**——曾把几千章正文的搬迁放在
 * 这里 await，界面几十秒不出内容，用户杀进程后下次启动又重来，卡成死循环。
 *
 * 现在只在「上次已迁完」时立刻切 SQLite（一次 SharedPreferences 读取，微秒级）；
 * 否则本次照旧走 IndexedDB 秒开，迁移丢到后台空闲时慢慢搬，搬完下次启动生效。
 *
 * @returns {boolean} 本次是否运行在 SQLite 通道上
 */
export async function initStore() {
  if (useSqlite) return true;
  if (!sqlReady()) return false;
  try {
    if (BR().storeMigrated && BR().storeMigrated()) {
      useSqlite = true;
      // 旧版 IndexedDB 只是迁移源，SQLite 已是唯一真相源。继续留着等于把
      // 整份书库再存一遍（带插图的 EPUB 往往就是几个 GB）。这里只删旧库，
      // 不碰 SQLite；删除失败也不影响本次使用，下次启动再试。
      dropLegacyIndexedDb();
      return true;
    }
  } catch (e) {
    return false;
  }
  startBackgroundMigration();
  return false;
}

/* ---------- 后台迁移（不阻塞首屏） ---------- */

let bgPromise = null;

/**
 * 原生屏（书架/详情/设置）直接查 SQLite，而用户数据可能还没从 IndexedDB 迁过去
 * （首次装含存储层的版本、或上次迁移被中断）。这时原生屏会读到空库，
 * 用户看到的就是「书架空了」。所以给原生一个主动催迁移的入口：
 * 它发现库是空的就调这个，而不是把空书架当成真相。
 */
if (typeof window !== 'undefined') {
  window.__modouMigrateNow = () => {
    startBackgroundMigration();
    return storeKind();
  };
}

/** 后台迁移任务句柄：仅测试/诊断用，业务代码不该 await 它 */
export const whenMigrationDone = () => bgPromise || Promise.resolve(false);

/**
 * 后台把 IndexedDB 搬进 SQLite。逐表进行，表与表之间让出主线程。
 * 任一表失败就不写「已迁移」标记，下次启动整表重试（按主键覆盖，幂等）。
 */
export function startBackgroundMigration() {
  if (bgPromise) return bgPromise;
  const idle = (fn) =>
    typeof requestIdleCallback === 'function'
      ? requestIdleCallback(fn, { timeout: 500 })
      : setTimeout(fn, 50);
  bgPromise = new Promise((resolve) => {
    const stores = Object.keys(KEY_PATHS);
    let i = 0;
    const finish = (ok) => {
      if (ok) {
        try {
          const b = BR();
          if (b && b.setStoreMigrated) b.setStoreMigrated(true);
        } catch (e) {
          /* 标记写失败：下次重迁一次，幂等 */
        }
        // 迁移完整成功：立刻切到 SQLite，当前会话也不会再读旧 IndexedDB。
        // 双写只适用于「迁移中」；切完再双写反而会多写一份。
        useSqlite = true;
        mirror = false;
        // 旧库删除放在切通道之后：即使删除失败，本次读的也是完整 SQLite。
        dropLegacyIndexedDb().finally(() => resolve(true));
        return;
      }
      mirror = false;
      useSqlite = false;
      resolve(false);
    };
    const step = () => {
      if (i >= stores.length) {
        finish(true);
        return;
      }
      const s = stores[i++];
      migrateStore(s)
        .then(() => idle(step))
        .catch(() => finish(false));
    };
    idle(step);
  });
  return bgPromise;
}

/** 影子写：失败不影响主通道，只影响「下次能否无缝切到 SQLite」 */
async function mirrorWrite(fn) {
  if (!mirror) return;
  try {
    await fn();
  } catch (e) {
    /* 忽略 */
  }
}

/** 当前通道（诊断/测试用） */
export const storeKind = () => (useSqlite ? 'sqlite' : 'indexeddb');

/* ==================== 对外 API（与旧版一致） ==================== */

export const db = {
  open,
  all: (store) => (useSqlite ? sql.all(store) : idb.all(store)),
  /** books 轻量版（无 chapters）：书架/标记用，详情页按 id 补全 */
  allBooksLite: () => (useSqlite ? sql.allBooksLite() : idb.all('books')),
  keys: (store) => (useSqlite ? sql.keys(store) : idb.keys(store)),
  get: (store, key) => (useSqlite ? sql.get(store, key) : idb.get(store, key)),
  put: async (store, obj, key) => {
    const r = await (useSqlite ? sql.put(store, obj) : idb.put(store, obj, key));
    await mirrorWrite(() => sql.put(store, obj));
    return r;
  },
  del: async (store, key) => {
    const r = await (useSqlite ? sql.del(store, key) : idb.del(store, key));
    await mirrorWrite(() => sql.del(store, key));
    return r;
  },
  clear: async (store) => {
    const r = await (useSqlite ? sql.clear(store) : idb.clear(store));
    await mirrorWrite(() => sql.clear(store));
    return r;
  },
  forEach: (store, fn) => (useSqlite ? sql.forEach(store, fn) : idb.forEach(store, fn)),
  putMany: async (store, objs) => {
    const r = await (useSqlite ? sql.putMany(store, objs) : idb.putMany(store, objs));
    await mirrorWrite(() => sql.putMany(store, objs));
    return r;
  },
};

/** 设置项读写（记录形如 {k, v}；get 负责拆包，与 set 对称） */
export const settings = {
  get: async (k, dft) => {
    const r = await db.get('settings', k);
    if (r == null) return dft;
    return typeof r === 'object' && 'v' in r ? r.v : r;
  },
  /**
   * 一次事务取回全部设置（拆包后是 { key: value }）。
   *
   * 阅读页初始化要读 11 个设置项，逐项 get 就是 11 次 IndexedDB 往返、串行等待；
   * 点开一本书肉眼可见地慢。合并成一次 getAll，往返 11 → 1。
   */
  getAll: async () => {
    const rows = (await db.all('settings')) || [];
    const out = {};
    for (const r of rows) {
      if (!r || r.k === undefined) continue;
      out[r.k] = typeof r === 'object' && 'v' in r ? r.v : r;
    }
    return out;
  },
  set: (k, v) => db.put('settings', { k, v }),
};
