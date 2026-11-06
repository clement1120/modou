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
import { jsErrorReason } from './reason.js';

/** 结果回传上限：目录这类大结果单次过桥太重时，宁可截断并告知，也不能卡死调用方 */
const MAX_REPLY_CHARS = 900000;

/**
 * 回传超限时的逐级瘦身：体积几乎全在 trace 的响应片段（html/raw/sample）和 logs 上。
 * 批量校验只需要「哪步失败、什么状态」，不需要那 1600 字的 HTML 原文 ——
 * 单源调试走 one()，体积小，不受影响，仍然看得到片段。
 */
function slimTraces(traces, level) {
  const out = {};
  for (const k of Object.keys(traces || {})) {
    out[k] = (traces[k] || []).map((t) => {
      if (!t || typeof t !== 'object') return t;
      const o = Object.assign({}, t);
      delete o.html;
      if (level >= 2) {
        delete o.raw;
        delete o.sample;
        delete o.fields;
        delete o.autoHint;
      }
      if (level >= 3) delete o.logs;
      return o;
    });
  }
  return out;
}

/**
 * 结果集也一起瘦身：校验只关心「这个源有没有出书」，不需要 900 条完整书目。
 * level 1 删简介；level 2 只留每组前 3 个来源；level 3 连封面/最新章节都去掉。
 */
function slimGroups(groups, level) {
  if (!Array.isArray(groups)) return groups;
  return groups.map((g) => {
    const hits = (g.hits || []).map((h) => {
      const b = Object.assign({}, h.book || {});
      if (level >= 1) delete b.intro;
      if (level >= 2) {
        delete b.lastChapter;
        delete b.updateTime;
        delete b.wordCount;
        delete b.coverUrl;
      }
      return Object.assign({}, h, { book: b });
    });
    return Object.assign({}, g, { hits: level >= 2 ? hits.slice(0, 3) : hits });
  });
}

/**
 * 搜索结果里每本书都带一条 intro，批量校验 45 个源时能堆出几百 KB。
 * 列表只显示书名/作者/封面，简介留给详情页去拉，这里截到一段即可。
 */
function slimBook(b) {
  if (!b || typeof b !== 'object') return b;
  const o = Object.assign({}, b);
  const intro = String(o.intro || '');
  if (intro.length > 160) o.intro = intro.slice(0, 160);
  return o;
}

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
  // 超大结果：先给 trace 瘦身再试，实在还超才放弃回传。
  // 以前直接报「结果过大」，用户拿到的是一句错误，连哪些源通过都看不到。
  if (s.length > MAX_REPLY_CHARS && obj && (obj.traces || obj.groups)) {
    for (let lvl = 1; lvl <= 3; lvl++) {
      if (obj.traces) obj.traces = slimTraces(obj.traces, lvl);
      if (obj.groups) obj.groups = slimGroups(obj.groups, lvl);
      obj.traceTrimmed = lvl;
      s = JSON.stringify(obj);
      if (s.length <= MAX_REPLY_CHARS) break;
    }
  }
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
  // 去掉 JS 引擎附加的调用栈片段（"at xxx (yyy:1:2)"），原因本身要完整留着。
  // 以前这里是「从「未命中」这类关键词开始截到末尾」，结果把前半句吃掉了：
  // 「没有解析出任何结果（列表规则未命中…）」变成「未命中，站点可能已改版）」
  s = s.replace(/\s+at\s+[\w$.<>]+\s*\([^)]*\)/g, ' ').replace(/\s+/g, ' ').trim();
  return s.slice(0, 200);
};

/**
 * 区分「规则执行异常」与「请求没通」。
 *
 * <p>以前用的是反向排除：「只要不含请求失败/超时/风控/HTTP 就算 JS 错」。
 * 于是「未配置 ruleSearch.bookList」「解析到结果但缺书名」「未解析到章节」这些**规则本身**
 * 的问题全被打成「规则执行异常」，原生产侧据此归灰（不判失效）—— 用户看到一片灰，
 * 既不知道哪条规则坏了，也没法清理。现在只认真正的 JS 引擎异常特征。
 */
const JS_ERROR_PATTERNS =
  /TypeError|ReferenceError|SyntaxError|RangeError|EvalError|URIError|Cannot read|Cannot set|is not a function|is not defined|is not iterable|Unexpected token|JavaException|ScriptException|WrappedException|正则|回溯/i;
const NET_ERROR_PATTERNS = /请求失败|超时|风控拦截|HTTP |没有网络|无法连接|连接失败|DNS|SSL/i;
const isJsError = (e) => {
  const m = String((e && e.message) || e || '');
  if (NET_ERROR_PATTERNS.test(m)) return false;
  return JS_ERROR_PATTERNS.test(m);
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
  // 去掉所有标点/符号/空白，只留字母数字 —— 用于「子序列」兜底匹配。
  // 书名里的间隔号、破折号、括号会把「我欲封天」这种连续词拆散，子序列判定能兜住。
  const normAlpha = (s) => norm(s).replace(/[^\p{L}\p{N}]/gu, '');
  // needle 的字符是否按顺序（不要求连续）出现在 hay 中：兜「简繁/多字/别名」导致的
  // 书名不完全一致，但字符顺序大体对得上。比「整词子串」宽松、比「整源放倒」精准得多。
  const isSubseq = (needle, hay) => {
    let i = 0;
    for (const ch of hay) {
      if (ch === needle[i]) i++;
      if (i === needle.length) return true;
    }
    return i === needle.length;
  };
  const relevance = (b, kw) => {
    const k = norm(kw);
    if (!k) return 1; // 关键词为空时不做过滤（与网页版一致）
    const name = norm(b.name);
    if (name.includes(k)) return 100;
    if (norm(b.author).includes(k)) return 60;
    // 书名去标点后，搜索词作为子序列出现：兜底别名/多字/间隔符导致的「不完全一致」，
    // 避免把这些本应命中的书误判成「弱相关」倒进底部。
    if (isSubseq(normAlpha(k), normAlpha(name))) return 80;
    const extra = norm(`${b.intro || ''}${b.lastChapter || ''}${b.kind || ''}`);
    return extra.includes(k) ? 20 : 0;
  };

  const groups = new Map();
  const fails = [];
  const weakSources = [];
  /** 每个源的链路追踪（书源详情「调试」用）：url → trace[] */
  const traces = {};
  let done = 0;
  let filtered = 0;

  // 搜索是广撒网：单源超时压短、不重试、并发拉高。
  // 否则几十个源里只要有两三个假死，就能把「60 秒总超时」耗光 ——
  // 用户看到的是「搜索中…」转一分钟然后失败，比缺几条结果糟糕得多。
  //
  // 但**校验**是另一回事：只跑一个源，而且要下「这个源行不行」的结论。
  // 8 秒预算下，慢站（尤其要跳 2~3 次重定向、走 CDN 的）几乎必被判死，
  // 于是「Legado 里好好的源，在体检里全红」。校验给 20 秒 + 允许一次重试。
  const isCheck = !!req.check;
  for (const s of sources) {
    try {
      // rateWait 只在单源调试时为 true：站点限流时等够时间重发一次（真能搜出书）。
      // 批量校验不传，保持「一个源最多等一个窗口」，几十个源不会被限流拖死。
      s.setRequestPolicy({ timeout: isCheck ? 20000 : 8000, retries: isCheck ? 1 : 0,
        connectTimeout: isCheck ? 8000 : 10000, readTimeout: isCheck ? 12000 : 15000,
        rateWait: req.waitRate === true });
    } catch (e) {
      /* 忽略：老实现没有这个方法的源不影响主流程 */
    }
  }

  const queue = [...sources];
  const workers = Array.from({ length: Math.min(8, queue.length) }, async () => {
    while (queue.length) {
      const src = queue.shift();
      const url = src.bookSourceUrl;
      // 结构化状态码：原生侧判「请求通没通」不能靠解析错误文案（中文一变就失效），
      // 有 status 就按 status 判。0 表示连请求都没发出去（DNS/连接失败）。
      let lastStatus = 0;
      // 站点明确回了「无结果」页：搜索链路是通的（编码、参数、规则都对），
      // 只是这个词站内没有。校验要拿它把这种情况从「源失效」里摘出来。
      let noResult = false;
      try {
        const r = await src.search(keyword, Number(req.page) > 0 ? Number(req.page) : 1);
        lastStatus = Number(r.status) || 0;
        noResult = !!r.noResult;
        if (r.trace) traces[url] = r.trace;
        if (r.error) throw r.error;
        const all = r.list || [];
        if (!all.length) {
          // 带上 HTTP 状态码：用户排查时"0 条"和"403 的 0 条"是两件事，
          // 前者可能只是关键词不收录，后者是被反爬。
          // 引擎能给出更具体的诊断（页面里有没有关键词、是不是 JS 渲染）就用它，
          // 只在拿不到时退回通用文案 —— 「可能已改版」对用户没有任何操作价值。
          const why = r.diag || '列表规则未命中，站点可能已改版';
          throw new Error(`HTTP ${r.status || '?'} · 没有解析出任何结果（${why}）`);
        }
        let rows = all.filter((b) => relevance(b, keyword) > 0);
        let weak = false;
        if (!rows.length) {
          // 关键词没命中标题/作者：可能是简繁差异、笔名别名，或作者/简介字段本来就没解析出来。
          // 这种情况**不再整源丢弃** —— 宁可给出「可能不相关」的结果让用户点开看，
          // 也比一片空白强。之前正是这一条把整页结果吃掉，搜索才会永远为空。
          rows = all;
          weak = true;
          weakSources.push(src.bookSourceName || url);
        } else {
          filtered += all.length - rows.length;
        }
        for (const b of rows) {
          const key = norm(b.name) || b.bookUrl;
          if (!groups.has(key)) groups.set(key, { name: b.name, author: b.author || '', hits: [], score: 0, weak: false });
          const g = groups.get(key);
          if (g.hits.some((h) => h.sourceUrl === url && h.bookUrl === b.bookUrl)) continue;
          g.score = Math.max(g.score, relevance(b, keyword));
          // 整源都没命中关键词：这些书基本可判定为不相关（坏源返回了站点默认热门列表），
          // 打上 weak 标，原生侧据此把它们沉到底部并标注「可能不相关」，绝不压在真命中之上。
          if (weak) g.weak = true;
          if (!g.author && b.author) g.author = b.author;
          g.hits.push({
            sourceUrl: url,
            sourceName: src.bookSourceName || url,
            book: slimBook(b),
          });
        }
    } catch (e) {
      const reason = brief(e);
      // 规则执行异常（正则回溯 / XPath 错误 / 字段脚本抛错）与「请求没通」是两回事：
      // 前者是源规则写坏了、页面其实是好的，绝不能当网络失败处理，更不能进删除清单。
      // 打上「规则执行异常：」前缀，Java 侧据此归灰（不删），与网络失败区分开。
      fails.push({ name: src.bookSourceName || url, sourceUrl: src.bookSourceUrl || '',
        reason: isJsError(e) ? jsErrorReason(reason) : reason, status: lastStatus, noResult });
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
  reply(seq, { ok: true, groups: list, fails, filtered, weak: weakSources, total: sources.length, traces });
}

/** 单书源动作（详情 / 目录 / 正文）的统一包装 */
async function one(req, fn) {
  const seq = req.seq;
  const srcs = makeSources([req.source]);
  if (!srcs.length) return reply(seq, { ok: false, error: '书源定义无效' });
  const src = srcs[0];
  const book = req.book || {};
  // 详情/目录/正文：体检深检时 req.check=true（SourceService 指定超时版本带 check），用校验超时；
  // 阅读时取内容用默认超时。Java 网络层据此切连接/读取细分（8/12 vs 10/15）。
  const isCheck = !!req.check;
  try { src.setRequestPolicy({ connectTimeout: isCheck ? 8000 : 10000, readTimeout: isCheck ? 12000 : 15000 }); } catch (e) {}
  try {
    const r = await fn(src, book);
    if (!r) return reply(seq, { ok: false, error: '书源没有返回结果' });
    // 失败也把 trace 带上：调试台最需要日志的正是「这一步失败了」，
    // 以前只回 error，用户看到一句「详情页解析为空」却看不到请求地址、状态码和响应片段。
    if (r.error) return reply(seq, {
      ok: false,
      error: brief(r.error),
      status: Number(r.status) || 0,
      logs: r.logs || [],
      trace: r.trace || [],
    });
    reply(seq, { ok: true, ...r, logs: r.logs || [] });
  } catch (e) {
    const msg = brief(e);
    reply(seq, { ok: false, error: isJsError(e) ? jsErrorReason(msg) : msg, logs: (e && e.logs) || [] });
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
  /** 发现页也留 trace：纯发现页的源没有搜索，失败时用户只能靠这段判断 */
  const traces = {};
  // 校验走的也是这个入口（纯发现页的源）：同样要给足预算，
  // 否则慢站被 8 秒默认超时一刀切，判出来还是「这个源坏了」。
  const isCheck = !!req.check;
  for (const s of sources) {
    try {
      s.setRequestPolicy({ timeout: isCheck ? 20000 : 12000, retries: isCheck ? 1 : 0,
        connectTimeout: isCheck ? 8000 : 10000, readTimeout: isCheck ? 12000 : 15000 });
    } catch (e) {
      /* 老实现没有这个方法不影响主流程 */
    }
  }
  for (const src of sources) {
    let lastStatus = 0;
    try {
      const r = await src.explore(Number(req.page) > 0 ? Number(req.page) : 1, req.exploreUrl);
      lastStatus = Number(r.status) || 0;
      if (r.trace) traces[src.bookSourceUrl] = r.trace;
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
      fails.push({ name: src.bookSourceName || src.bookSourceUrl, reason: brief(e), status: lastStatus });
    }
  }
  reply(seq, { ok: true, groups, fails, traces });
}

/**
 * 探针词：拿书源首页上真实存在的书名，给「这个源能不能搜」的校验当关键词。
 *
 * 只发一次首页请求，认不出书名就返回空串 —— 调用方退回兜底词，绝不猜。
 * 它解决的是校验里最根本的那个误判：用「三体」问言情站，0 条不代表源坏。
 */
async function probe(req) {
  const seq = req.seq;
  const sources = makeSources(req.sources);
  if (!sources.length) return reply(seq, { ok: false, error: '书源定义无效' });
  const src = sources[0];
  try {
    src.setRequestPolicy({ timeout: 8000, retries: 0, connectTimeout: 6000, readTimeout: 8000 });
  } catch (e) {
    /* 老实现没有这个方法，不影响 */
  }
  try {
    const r = await src.probeKeyword();
    reply(seq, {
      ok: true,
      keyword: r.keyword || '',
      url: r.url || '',
      candidates: r.candidates || [],
      error: r.error || '',
    });
  } catch (e) {
    reply(seq, { ok: false, error: brief(e) });
  }
}

/* ==================== 对外 ==================== */

/**
 * 搜索入口自动探测：传入一个书源，引擎去首页扒搜索框、试常见路径，真发请求验证，
 * 回传可用的 searchUrl（含 GET/POST 选项串）。原生侧据此一键改写或批量修复源。
 */
async function discoverSearch(req) {
  const seq = req.seq;
  const src = makeSources(req.sources)[0];
  if (!src) return reply(seq, { ok: false, error: '书源为空' });
  const kw = String(req.keyword || '').trim();
  try {
    const r = await src.discoverSearchUrl({ keyword: kw });
    reply(seq, {
      ok: !!r.ok,
      searchUrl: r.searchUrl || '',
      method: r.method || 'GET',
      body: r.body || '',
      note: r.note || '',
      reason: r.reason || '',
      // 失败时的「试了 N 条候选：被拦 X、超时 Y」必须透传，否则原生只能显示一句
      // 「没找到入口」，用户和开发者都看不出是站点拦还是规则错（0.9.117 排查卡在这）。
      detail: r.detail || '',
    });
  } catch (e) {
    reply(seq, { ok: false, error: brief(e) });
  }
}

const API = { search, explore, info, toc, content, probe, discoverSearch };

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

export { run, search, explore, info, toc, content, probe, discoverSearch };
