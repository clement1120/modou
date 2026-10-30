/**
 * 书源服务层 —— 运行在「常驻无头 WebView」里的门面。
 *
 * 存在的理由：书源规则本质是「抓 HTML + 执行 JS + CSS/JSON/XPath 取字段」，
 * 这套能力只有 WebView 有，用原生重写等于重做一个 JS 引擎。
 * 所以做法不是搬走引擎，而是把引擎关进一个没有界面的 WebView，
 * 对原生暴露「搜索 / 发现 / 详情 / 目录 / 正文」五个动作。
 *
 * 这样原生屏（搜书、详情、阅读）拿数据不必再经过 UI WebView，
 * 将来 UI WebView 整个退役，这一层也不用动。
 *
 * 调用协议：原生通过 ModouSourceNative 传入 {seq, ...}，
 * 本层算完后**必定**回传一次 onResult(seq, json) —— 成功失败都回，
 * 否则原生的回调会一直挂到超时。
 */

import { BookSource } from './source.js';

/** 结果回传上限：目录这类大结果单次过桥太重时，宁可截断并告知，也不能卡死调用方 */
const MAX_REPLY_CHARS = 900000;

const native = () =>
  typeof window !== 'undefined' && window.ModouSourceNative ? window.ModouSourceNative : null;

function reply(seq, obj) {
  const n = native();
  if (!n || typeof n.onResult !== 'function') return;
  let s;
  try {
    s = JSON.stringify(obj);
  } catch (e) {
    s = JSON.stringify({ ok: false, error: '结果无法序列化：' + (e && e.message) });
  }
  // 超大结果：截断前先带上明确标记，避免原生拿到一个"看起来成功其实是空"的结果
  if (s.length > MAX_REPLY_CHARS) {
    s = JSON.stringify({
      ok: false,
      error: '结果过大（' + Math.round(s.length / 1024) + 'KB），已放弃回传。请缩小范围或分页。',
    });
  }
  try {
    n.onResult(String(seq), s);
  } catch (e) {
    /* 桥断了就没办法了，调用方有超时兜底 */
  }
}

const msg = (s) => {
  try {
    const n = native();
    if (n && typeof n.onLog === 'function') n.onLog(String(s));
  } catch (e) {
    /* 日志失败无所谓 */
  }
};

/** 把书源定义实例化；单个书源构造失败不能拖垮整批 */
function makeSources(defs) {
  const out = [];
  for (const d of defs || []) {
    try {
      out.push(BookSource.fromJson(d));
    } catch (e) {
      msg('书源构造失败：' + ((d && (d.bookSourceName || d.bookSourceUrl)) || '?') + ' ' + (e && e.message));
    }
  }
  return out;
}

const brief = (e) => {
  let s = String((e && e.message) || e || '未知错误').replace(/\s+/g, ' ').trim();
  const m = s.match(/(CSS 无效|XPath 无效|JSON 无效|正则无效|规则无效|请求失败|超时|HTTP\s*\d+|未命中|空结果|未配置)[\s\S]*/i);
  if (m) s = m[0];
  return s.slice(0, 200);
};

/* ==================== 动作 ==================== */

/**
 * 搜索：多书源并发，逐源回传进度，最后汇总。
 * 与网页版 doSearch 保持一致的两个关键处理：
 *  - 相关性过滤：书源搜索规则失效时会返回与关键词无关的整页书，宁可丢弃并报给用户；
 *  - 同名归并：同一本书来自多个源时归为一组，方便横向选源。
 */
async function search(req) {
  const seq = req.seq;
  const keyword = String(req.keyword || '').trim();
  if (!keyword) return reply(seq, { ok: false, error: '关键词为空' });
  const sources = makeSources(req.sources);
  if (!sources.length) return reply(seq, { ok: false, error: '没有可用的书源' });

  // 与网页版 search.js 的 relevance 保持同一套判定：
  // 用户在网页版和原生搜书之间来回切，若两边结果集不同会觉得「书自己变了」。
  const norm = (s) => String(s || '').toLowerCase().replace(/[\s　]+/g, '');
  const relevance = (b, kw) => {
    const k = norm(kw);
    if (!k) return 1; // 关键词为空时不做过滤（与网页版一致）
    if (norm(b.name).includes(k)) return 100;
    if (norm(b.author).includes(k)) return 60;
    const extra = norm(`${b.intro || ''}${b.lastChapter || ''}${b.kind || ''}`);
    return extra.includes(k) ? 20 : 0;
  };

  const groups = new Map();
  const fails = [];
  let done = 0;
  let filtered = 0;

  const queue = [...sources];
  const workers = Array.from({ length: Math.min(6, queue.length) }, async () => {
    while (queue.length) {
      const src = queue.shift();
      const url = src.bookSourceUrl;
      try {
        const r = await src.search(keyword, Number(req.page) > 0 ? Number(req.page) : 1);
        if (r.error) throw r.error;
        const all = r.list || [];
        const rows = all.filter((b) => relevance(b, keyword) > 0);
        filtered += all.length - rows.length;
        if (all.length && !rows.length) {
          throw new Error(`返回 ${all.length} 条结果均与关键词无关（搜索规则可能失效）`);
        }
        for (const b of rows) {
          const key = norm(b.name) || b.bookUrl;
          if (!groups.has(key)) groups.set(key, { name: b.name, author: b.author || '', hits: [], score: 0 });
          const g = groups.get(key);
          if (g.hits.some((h) => h.sourceUrl === url && h.bookUrl === b.bookUrl)) continue;
          g.score = Math.max(g.score, relevance(b, keyword));
          if (!g.author && b.author) g.author = b.author;
          g.hits.push({
            sourceUrl: url,
            sourceName: src.bookSourceName || url,
            book: b,
          });
        }
      } catch (e) {
        fails.push({ name: src.bookSourceName || url, reason: brief(e) });
      }
      done++;
      // 进度回传：让原生能显示「3/12」，而不是干等
      const n = native();
      if (n && typeof n.onProgress === 'function') {
        try {
          n.onProgress(String(seq), done, sources.length);
        } catch (e) {
          /* 忽略 */
        }
      }
    }
  });
  await Promise.all(workers);

  const list = [...groups.values()];
  list.sort((a, b) => b.score - a.score || b.hits.length - a.hits.length);
  reply(seq, { ok: true, groups: list, fails, filtered, total: sources.length });
}

/** 单书源动作（详情 / 目录 / 正文）的统一包装 */
async function one(req, fn) {
  const seq = req.seq;
  const srcs = makeSources([req.source]);
  if (!srcs.length) return reply(seq, { ok: false, error: '书源定义无效' });
  const src = srcs[0];
  const book = req.book || {};
  try {
    const r = await fn(src, book);
    if (!r) return reply(seq, { ok: false, error: '书源没有返回结果' });
    if (r.error) return reply(seq, { ok: false, error: brief(r.error), logs: r.logs || [] });
    reply(seq, { ok: true, ...r, logs: r.logs || [] });
  } catch (e) {
    reply(seq, { ok: false, error: brief(e), logs: (e && e.logs) || [] });
  }
}

const info = (req) => one(req, (src, book) => src.getBookInfo(book));

const toc = (req) =>
  one(req, async (src, book) => {
    const r = await src.getToc(book);
    if (r && r.ok === false && r.chapters) return { ...r, error: r.error, chapters: r.chapters };
    return r;
  });

const content = (req) =>
  one(req, async (src, book) => {
    const r = await src.getContent(book, req.chapter || {});
    return r;
  });

async function explore(req) {
  const seq = req.seq;
  const sources = makeSources(req.sources);
  if (!sources.length) return reply(seq, { ok: false, error: '没有可用的书源' });
  const groups = [];
  const fails = [];
  for (const src of sources) {
    try {
      const r = await src.explore(Number(req.page) > 0 ? Number(req.page) : 1, req.exploreUrl);
      if (r.error) throw r.error;
      const list = (r.list || []).filter((b) => b.name && b.bookUrl);
      if (list.length) {
        groups.push({
          name: src.bookSourceName || src.bookSourceUrl,
          sourceUrl: src.bookSourceUrl,
          items: list.map((b) => ({ ...b, sourceUrl: src.bookSourceUrl, sourceName: src.bookSourceName || src.bookSourceUrl })),
        });
      }
    } catch (e) {
      fails.push({ name: src.bookSourceName || src.bookSourceUrl, reason: brief(e) });
    }
  }
  reply(seq, { ok: true, groups, fails });
}

/* ==================== 对外 ==================== */

const API = { search, explore, info, toc, content };

/**
 * 原生入口：run(seq, action, payloadJson)。
 * 一律异步化并兜住异常 —— 任何漏出的异常都会让原生回调空等到超时，
 * 用户看到的就是「一直转圈」，比直接报错更难排查。
 */
async function run(seq, action, payloadJson) {
  const fn = API[action];
  if (!fn) return reply(seq, { ok: false, error: '未知动作：' + action });
  let req = {};
  try {
    req = typeof payloadJson === 'string' && payloadJson ? JSON.parse(payloadJson) : payloadJson || {};
  } catch (e) {
    return reply(seq, { ok: false, error: '参数解析失败：' + (e && e.message) });
  }
  req.seq = seq;
  try {
    await fn(req);
  } catch (e) {
    reply(seq, { ok: false, error: brief(e) });
  }
}

if (typeof window !== 'undefined') {
  window.ModouSource = { run, API };
  const n = native();
  if (n && typeof n.onReady === 'function') {
    try {
      n.onReady();
    } catch (e) {
      /* 忽略 */
    }
  }
}

export { run, search, explore, info, toc, content };
