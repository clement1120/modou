/**
 * store-layer.mjs — 共享存储层（SQLite 通道 + IndexedDB 迁移）单测（Node，无浏览器）
 *
 * 覆盖：
 *  1. 无桥时回退 IndexedDB（桌面浏览器行为）
 *  2. 有桥时切到 SQLite 通道
 *  3. 各表 put/get 往返（books 自由字段不丢、chapters 正文不丢）
 *  4. settings 的 v 编解码（字符串 / 对象 / 数组 三类值往返一致）——最容易出错的一处
 *  5. putMany 批量、del / clear / keys / forEach
 *  6. IndexedDB → SQLite 迁移（含 settings 编码正确、分批、标记）
 *
 * 两个 mock 都按真实实现对表：
 *  - mkBridge 对齐 ModouStore.java 的表规则（chapters 拆 content/cachedAt、
 *    settings 拆 k/v、其余存整对象 json）；
 *  - mkIndexedDB 对齐 db.js 实际用到的 IndexedDB 子集。
 */
let pass = 0;
let fail = 0;
const check = (name, ok, detail = '') => {
  if (ok) {
    pass++;
    console.log('  ✓', name);
  } else {
    fail++;
    console.log('  ✗', name, '—', detail);
  }
};

/* ---------- mock：原生存储桥（对齐 ModouStore.java） ---------- */

const pkOf = (t) => (t === 'progress' ? 'bookId' : t === 'settings' ? 'k' : 'id');

function mkBridge() {
  const T = {};
  const table = (t) => T[t] || (T[t] = new Map());
  const rowOut = (t, rec) => {
    if (t === 'chapters') return JSON.stringify({ id: rec.id, content: rec.content, cachedAt: rec.cachedAt });
    if (t === 'settings') return JSON.stringify({ k: rec.k, v: rec.v });
    return rec.json;
  };
  return {
    _t: T,
    _migrated: false,
    storeReady: () => true,
    storeMigrated() {
      return this._migrated;
    },
    setStoreMigrated(v) {
      this._migrated = !!v;
    },
    storePut(t, objJson) {
      const o = JSON.parse(objJson);
      const key = o[pkOf(t)];
      if (key == null) return false;
      if (t === 'chapters') table(t).set(key, { id: key, content: o.content ?? null, cachedAt: o.cachedAt ?? 0 });
      else if (t === 'settings') table(t).set(key, { k: key, v: o.v ?? null });
      else table(t).set(key, { json: objJson });
      return true;
    },
    storeGet(t, key) {
      const rec = table(t).get(String(key));
      return rec == null ? null : rowOut(t, rec);
    },
    storeAll(t) {
      return JSON.stringify([...table(t).values()].map((r) => JSON.parse(rowOut(t, r))));
    },
    storeKeys(t) {
      return JSON.stringify([...table(t).keys()]);
    },
    storePutMany(t, arrJson) {
      let n = 0;
      for (const o of JSON.parse(arrJson)) if (this.storePut(t, JSON.stringify(o))) n++;
      return n;
    },
    storeDel(t, key) {
      return table(t).delete(String(key));
    },
    storeClear(t) {
      table(t).clear();
      return true;
    },
    storeCount(t) {
      return table(t).size;
    },
  };
}

/* ---------- mock：IndexedDB（对齐 db.js 用到的子集） ---------- */

function mkIndexedDB(seed) {
  const maps = {};
  for (const [s, arr] of Object.entries(seed || {})) maps[s] = new Map(arr);
  const queue = (fn) => setTimeout(fn, 0);
  let deleteCount = 0;
  let closed = 0;
  // db.js 的迁移按主键范围游标分批读，mock 需提供这套 API
  globalThis.IDBKeyRange = {
    lowerBound: (lower, open) => ({ lower, open: !!open }),
  };
  return {
    _deleteCount: () => deleteCount,
    _closed: () => closed,
    deleteDatabase(name) {
      deleteCount++;
      const req = {};
      queue(() => {
        for (const k of Object.keys(maps)) delete maps[k];
        req.onsuccess && req.onsuccess();
      });
      return req;
    },
    open() {
      const req = {};
      queue(() => {
        const db = {
          close() { closed++; },
          objectStoreNames: { contains: (s) => !!maps[s] },
          transaction(store) {
            if (!maps[store]) maps[store] = new Map();
            const m = maps[store];
            const tx = {};
            const done = () => queue(() => tx.oncomplete && tx.oncomplete());
            tx.objectStore = () => ({
              getAllKeys: () => {
                const r = { result: [...m.keys()] };
                done();
                return r;
              },
              getAll: () => {
                const r = { result: [...m.values()] };
                done();
                return r;
              },
              get: (k) => {
                const r = { result: m.get(k) };
                done();
                return r;
              },
              put: (o) => {
                m.set(o.id ?? o.k ?? o.bookId, o);
                const r = { result: o };
                done();
                return r;
              },
              delete: (k) => {
                m.delete(k);
                const r = {};
                done();
                return r;
              },
              clear: () => {
                m.clear();
                const r = {};
                done();
                return r;
              },
              openCursor: (range) => {
                const r = {};
                // 真实 IndexedDB 按主键排序遍历（字符串即字典序），不是插入顺序
                let keys = [...m.keys()].sort();
                if (range && range.lower !== undefined) {
                  keys = range.open
                    ? keys.filter((k) => k > range.lower)
                    : keys.filter((k) => k >= range.lower);
                }
                let i = 0;
                const next = () => {
                  if (i >= keys.length) {
                    r.result = null;
                    done(); // 游标走完 → 事务完成
                    return;
                  }
                  const k = keys[i++];
                  let advanced = false;
                  r.result = {
                    key: k,
                    value: m.get(k),
                    continue: () => {
                      advanced = true;
                      queue(next);
                    },
                  };
                  if (r.onsuccess) r.onsuccess();
                  // 真实 IndexedDB：onsuccess 里没继续推进，事务就自动提交。
                  // db.js 正是靠「满一批就不再 continue」来结束本批，必须模拟。
                  queue(() => {
                    if (!advanced) done();
                  });
                };
                queue(next);
                return r;
              },
            });
            return tx;
          },
        };
        req.result = db;
        req.onsuccess && req.onsuccess();
      });
      return req;
    },
  };
}

/* ---------- 工具：每个场景加载一份干净的 db.js ---------- */

let uid = 0;
const freshDb = () => import('../web/js/db.js?case=' + ++uid);

/* ==================== 1. 无桥：回退 IndexedDB ==================== */
{
  delete globalThis.window;
  const m = await freshDb();
  const ok = await m.initStore();
  check('无桥：initStore 返回 false（桌面浏览器不迁移）', ok === false);
  check('无桥：storeKind 为 indexeddb', m.storeKind() === 'indexeddb', m.storeKind());
}

/* ==================== 2a. 有桥 + 未迁移（首次装新版） ==================== */
{
  const bridge = mkBridge();
  globalThis.window = { ModouBridge: bridge };
  globalThis.indexedDB = mkIndexedDB({}); // 空库：迁移空转成功
  const m = await freshDb();
  const t0 = Date.now();
  const ok = await m.initStore();
  const ms = Date.now() - t0;
  // 关键回归：迁移曾挡在首屏 await 上，界面几十秒不出内容（白屏）
  check('有桥未迁移：initStore 返回 false（首屏不等迁移）', ok === false, String(ok));
  check('有桥未迁移：首屏仍走 indexeddb 通道', m.storeKind() === 'indexeddb', m.storeKind());
  check('有桥未迁移：initStore 立即返回，不阻塞首屏（<50ms）', ms < 50, ms + 'ms');
  const done = await m.whenMigrationDone();
  check('有桥未迁移：后台迁移完成', done === true);
  await m.whenLegacyIdbCleared();
  check('有桥未迁移：迁完写入标记', bridge.storeMigrated() === true);
  check('有桥未迁移：迁移成功后立即切 SQLite', m.storeKind() === 'sqlite', m.storeKind());
  check('有桥未迁移：旧 IndexedDB 连接已关闭', globalThis.indexedDB._closed() >= 1, String(globalThis.indexedDB._closed()));
  check('有桥未迁移：旧 IndexedDB 已删除（不再占双份）', globalThis.indexedDB._deleteCount() === 1, String(globalThis.indexedDB._deleteCount()));
}

/* ==================== 2a2. 已迁移过：启动直接切 SQLite ==================== */
{
  const bridge = mkBridge();
  bridge.setStoreMigrated(true);
  globalThis.window = { ModouBridge: bridge };
  const m = await freshDb();
  const ok = await m.initStore();
  check('已迁移过：initStore 返回 true', ok === true);
  check('已迁移过：storeKind 切到 sqlite', m.storeKind() === 'sqlite', m.storeKind());
}

/* ==================== 2a3. 大量缓存章节：首屏不阻塞 + 后台搬完 + 双写 ======== */
{
  const bridge = mkBridge();
  globalThis.window = { ModouBridge: bridge };
  const chapters = [];
  for (let i = 0; i < 120; i++)
    chapters.push(['b1||' + i, { id: 'b1||' + i, content: '<p>' + 'x'.repeat(2000) + '</p>', cachedAt: 1 }]);
  globalThis.indexedDB = mkIndexedDB({ chapters });
  const m = await freshDb();
  const t0 = Date.now();
  const ok = await m.initStore();
  const ms = Date.now() - t0;
  check('大量章节：initStore 不阻塞首屏（<50ms）', ms < 50, ms + 'ms', ok === false);
  check('大量章节：首屏走 indexeddb', m.storeKind() === 'indexeddb', m.storeKind());
  await m.whenMigrationDone();
  check('大量章节：120 章全部搬进 SQLite', bridge.storeCount('chapters') === 120, String(bridge.storeCount('chapters')));
  check('大量章节：迁完立即切 SQLite，不再双写', m.storeKind() === 'sqlite', m.storeKind());
  // 切换后本次会话直接写 SQLite，不会出现「IndexedDB 有一份、SQLite 没有」
  await m.db.put('progress', { bookId: 'b9', index: 3 });
  check('迁移后新写入直接进 SQLite', bridge.storeGet('progress', 'b9') != null);
}

/* ==================== 2b. 有桥但 IndexedDB 不可用：绝不半切换 ==================== */
{
  const bridge = mkBridge();
  globalThis.window = { ModouBridge: bridge };
  globalThis.indexedDB = undefined; // 迁移无从下手
  const m = await freshDb();
  const ok = await m.initStore();
  check('有桥但无 IndexedDB：initStore 返回 false', ok === false, String(ok));
  check('有桥但无 IndexedDB：保持 indexeddb 通道（不半切换）', m.storeKind() === 'indexeddb', m.storeKind());
  check('有桥但无 IndexedDB：不写迁移标记（下次重试）', bridge.storeMigrated() === false);
}

/* ==================== 3. 各表 put/get 往返 ==================== */
{
  const bridge = mkBridge();
  bridge.setStoreMigrated(true);
  globalThis.window = { ModouBridge: bridge };
  const m = await freshDb();
  await m.initStore();

  // books：自由字段（含嵌套目录数组、group）必须不丢
  const book = {
    id: 'b1',
    sourceUrl: 'https://x/y',
    name: '测试书',
    author: '某人',
    intro: '简介',
    coverUrl: 'data:image/png;base64,AAA',
    kind: 'local',
    lastChapter: '第三章',
    group: '默认',
    chapters: [{ name: '第一章' }, { name: '第二章' }],
    addedAt: 111,
    updatedAt: 222,
  };
  await m.db.put('books', book);
  const got = await m.db.get('books', 'b1');
  check('books：往返字段不丢（含嵌套 chapters 目录）', JSON.stringify(got) === JSON.stringify(book), JSON.stringify(got).slice(0, 120));

  // chapters：正文与时间戳
  await m.db.put('chapters', { id: 'b1||0', content: '<p>正文一段</p>', cachedAt: 12345 });
  const ch = await m.db.get('chapters', 'b1||0');
  check('chapters：content/cachedAt 往返一致', ch && ch.content === '<p>正文一段</p>' && ch.cachedAt === 12345, JSON.stringify(ch));

  // progress：主键是 bookId
  await m.db.put('progress', { bookId: 'b1', index: 3, offset: 88 });
  const pg = await m.db.get('progress', 'b1');
  check('progress：bookId 主键往返', pg && pg.index === 3 && pg.offset === 88, JSON.stringify(pg));

  // 不存在的键 → undefined（对齐 IndexedDB 语义）
  const none = await m.db.get('books', 'nope');
  check('不存在的键返回 undefined', none === undefined, String(none));
}

/* ==================== 4. settings 的 v 编解码 ==================== */
{
  const bridge = mkBridge();
  bridge.setStoreMigrated(true);
  globalThis.window = { ModouBridge: bridge };
  const m = await freshDb();
  await m.initStore();

  await m.settings.set('theme', 'paper');
  await m.settings.set('fontSize', 18);
  await m.settings.set('rules', [{ pattern: 'a', replacement: 'b', isRegex: true }]);
  await m.settings.set('arr', [1, 2, 3]);

  const theme = await m.settings.get('theme', null);
  check('settings：字符串值往返（不被双重编码）', theme === 'paper', JSON.stringify(theme));
  const fs = await m.settings.get('fontSize', null);
  check('settings：数字值往返', fs === 18, String(fs));
  const rules = await m.settings.get('rules', null);
  check('settings：对象数组往返', Array.isArray(rules) && rules[0].pattern === 'a' && rules[0].isRegex === true, JSON.stringify(rules));
  const arr = await m.settings.get('arr', null);
  check('settings：数字数组往返', Array.isArray(arr) && arr.length === 3 && arr[2] === 3, JSON.stringify(arr));

  const all = await m.settings.getAll();
  check(
    'settings：getAll 拆包为 {key:value}',
    all.theme === 'paper' && all.fontSize === 18 && Array.isArray(all.rules),
    JSON.stringify(all)
  );
  check('settings：getAll 不掺入迁移标记等脏键', Object.keys(all).every((k) => !k.startsWith('__')), Object.keys(all).join(','));
}

/* ==================== 5. 批量 / 删除 / keys / forEach ==================== */
{
  const bridge = mkBridge();
  bridge.setStoreMigrated(true);
  globalThis.window = { ModouBridge: bridge };
  const m = await freshDb();
  await m.initStore();

  const rows = Array.from({ length: 25 }, (_, i) => ({ id: 'c' + i, content: '<p>' + i + '</p>', cachedAt: i }));
  await m.db.putMany('chapters', rows);
  check('putMany：25 条全部写入', bridge._t.chapters.size === 25, String(bridge._t.chapters.size));

  const keys = await m.db.keys('chapters');
  check('keys：返回全部 25 个主键', keys.length === 25, String(keys.length));

  let n = 0;
  await m.db.forEach('chapters', () => n++);
  check('forEach：逐条遍历到 25 条', n === 25, String(n));

  await m.db.del('chapters', 'c0');
  check('del：目标已删除', (await m.db.get('chapters', 'c0')) === undefined);

  await m.db.clear('chapters');
  check('clear：整表已清空', bridge._t.chapters.size === 0, String(bridge._t.chapters.size));
}

/* ==================== 6. IndexedDB → SQLite 迁移 ==================== */
{
  const bridge = mkBridge(); // storeMigrated 默认 false → 触发迁移
  globalThis.window = { ModouBridge: bridge };
  globalThis.indexedDB = mkIndexedDB({
    books: [['b1', { id: 'b1', name: '迁移书', group: '默认', chapters: [{ name: '第一章' }] }]],
    progress: [['b1', { bookId: 'b1', index: 2, offset: 10 }]],
    chapters: [['b1||0', { id: 'b1||0', content: '<p>迁移正文</p>', cachedAt: 999 }]],
    settings: [
      ['theme', { k: 'theme', v: 'paper' }], // IndexedDB 里 v 是真实值（非 JSON 串）
      ['fontSize', { k: 'fontSize', v: 18 }],
    ],
  });
  const m = await freshDb();
  const ok = await m.initStore();
  check('迁移：首屏不切通道（仍走 IndexedDB，界面不空等）', ok === false && m.storeKind() === 'indexeddb', m.storeKind());

  // 首屏照常从 IndexedDB 读到数据（用户体验与迁移前完全一致）
  const bk0 = await m.db.get('books', 'b1');
  check('迁移中：界面照常读到书架', bk0 && bk0.name === '迁移书', JSON.stringify(bk0));

  await m.whenMigrationDone();
  await m.whenLegacyIdbCleared();
  check('迁移：完成后写入标记（下次不再重迁）', bridge.storeMigrated() === true);
  check('迁移：完成后立即切 SQLite', m.storeKind() === 'sqlite', m.storeKind());
  check('迁移：旧 IndexedDB 已删除', globalThis.indexedDB._deleteCount() >= 1, String(globalThis.indexedDB._deleteCount()));

  // 数据确实落到了原生 SQLite（下次启动起就是唯一真相源）
  const jb = JSON.parse(bridge.storeGet('books', 'b1') || 'null');
  check('迁移：books 数据到达 SQLite', jb && jb.name === '迁移书' && jb.chapters.length === 1, String(bridge.storeGet('books', 'b1')));
  const jp = JSON.parse(bridge.storeGet('progress', 'b1') || 'null');
  check('迁移：progress 数据到达 SQLite', jp && jp.index === 2, String(bridge.storeGet('progress', 'b1')));
  const jc = JSON.parse(bridge.storeGet('chapters', 'b1||0') || 'null');
  check('迁移：chapters 正文到达 SQLite', jc && jc.content === '<p>迁移正文</p>' && jc.cachedAt === 999, String(bridge.storeGet('chapters', 'b1||0')));
  // SQLite 里 settings.v 存的是 JSON.stringify 后的文本（Java 侧不解析值语义），
  // 所以这里断言的是「编码格式正确」，真正解码正确性由下面「下次启动」验证
  const js2 = JSON.parse(bridge.storeGet('settings', 'theme') || 'null');
  check('迁移：settings 字符串以 JSON 形式落库', js2 && JSON.parse(js2.v) === 'paper', JSON.stringify(js2));
  const jf = JSON.parse(bridge.storeGet('settings', 'fontSize') || 'null');
  check('迁移：settings 数字以 JSON 形式落库', jf && JSON.parse(jf.v) === 18, JSON.stringify(jf));

  // 端到端：模拟「下次启动」——标记已写、同一份原生库，这次应切到 SQLite 并正确解码
  const m2 = await freshDb();
  await m2.initStore();
  check('迁移后下次启动：切到 sqlite', m2.storeKind() === 'sqlite', m2.storeKind());
  check(
    '迁移后下次启动：settings 字符串值正确（非双重编码）',
    (await m2.settings.get('theme', null)) === 'paper',
    JSON.stringify(await m2.settings.get('theme', null))
  );
  check(
    '迁移后下次启动：settings 数字值正确',
    (await m2.settings.get('fontSize', null)) === 18,
    JSON.stringify(await m2.settings.get('fontSize', null))
  );
  const bk2 = await m2.db.get('books', 'b1');
  check('迁移后下次启动：书架从 SQLite 读回完整', bk2 && bk2.name === '迁移书' && bk2.chapters.length === 1, JSON.stringify(bk2));
}

/* ==================== 7. 已迁移过：不重复迁移 ==================== */
{
  const bridge = mkBridge();
  bridge.setStoreMigrated(true);
  globalThis.window = { ModouBridge: bridge };
  // 若发生迁移就必然访问 indexedDB；这里故意不提供 indexedDB，
  // 能跑通即证明没有重复迁移（否则会抛错/失败）
  globalThis.indexedDB = undefined;
  const m = await freshDb();
  const ok = await m.initStore();
  check('已迁移：直接切换，不再访问 IndexedDB', ok === true && m.storeKind() === 'sqlite', m.storeKind());
}

console.log(`\n存储层：${pass} 通过 / ${fail} 失败`);
if (fail) process.exit(1);
