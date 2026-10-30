/**
 * source.js — 书源对象：把一份 JSON 规则变成可执行的搜索/详情/目录/正文链路
 * 兼容 Legado 书源 JSON 的常用子集（见 README「兼容性清单」）。
 */
import { fetchViaProxy, resolveUrl } from '../http.js';
import { runJs } from './sandbox.js';
import {
  createRuleContext,
  evalOne,
  evalList,
  evalRule,
  extractRows,
  parseDoc,
  subContext,
} from './rule.js';

const SEARCH_FIELDS = ['name', 'author', 'kind', 'intro', 'lastChapter', 'updateTime', 'bookUrl', 'coverUrl', 'wordCount'];
const INFO_FIELDS = ['name', 'author', 'intro', 'kind', 'lastChapter', 'updateTime', 'coverUrl', 'tocUrl', 'canReName'];
const TOC_FIELDS = ['chapterName', 'chapterUrl', 'isVolume', 'isVip', 'isPay'];

/** "##pat##rep##pat##rep" 串行替换 */
export function applyReplaceString(s, spec) {
  if (!s || !spec) return s || '';
  const parts = String(spec).split('##');
  let out = String(s);
  for (let i = 0; i + 1 < parts.length; i += 2) {
    try {
      out = out.replace(new RegExp(parts[i], 'g'), parts[i + 1]);
    } catch (e) {
      /* 跳过坏正则 */
    }
  }
  return out;
}

/* ---------- 目录兜底识别：规则一条没中时，按数据结构自动找章节 ---------- */

const AUTO_NAME_KEYS = ['chapterName', 'chapter_name', 'chapterTitle', 'chapter_title', 'chaptername', 'cname', 'title', 'name'];
const AUTO_URL_KEYS = ['chapterUrl', 'chapter_url', 'chapterurl', 'url', 'link', 'href', 'path'];

/** JSON 里递归找「对象数组」，且同时具备章节名与链接字段，取最有把握的一组 */
/** 在 JSON 里找最像章节表的对象数组（字段名 + 链接字段都齐） */
export function findChapterArray(json) {
  if (!json || typeof json !== 'object') return null;
  let best = null;
  const walk = (v, depth) => {
    if (!v || typeof v !== 'object' || depth > 6) return;
    if (Array.isArray(v)) {
      const first = v[0];
      if (v.length >= 2 && first && typeof first === 'object' && !Array.isArray(first)) {
        const nk = AUTO_NAME_KEYS.find((k) => typeof first[k] === 'string' && first[k].trim());
        const uk = AUTO_URL_KEYS.find((k) => typeof first[k] === 'string' && first[k].trim());
        // 没有链接字段也可能是章节表（地址由规则用 cid 拼出来），给低分但保留
        if (nk) {
          const score = v.length * 2 + (uk ? 60 : 0) - depth * 4;
          if (!best || score > best.score) best = { arr: v, nk, uk: uk || null, score };
        }
      }
      v.forEach((it) => walk(it, depth + 1));
    } else {
      Object.keys(v).forEach((k) => walk(v[k], depth + 1));
    }
  };
  walk(json, 0);
  return best;
}

export function autoChaptersFromJson(json, baseUrl) {
  const best = findChapterArray(json);
  if (!best || !best.uk) return [];
  const seen = new Set();
  const out = [];
  for (const it of best.arr) {
    const name = String(it[best.nk] == null ? '' : it[best.nk]).trim();
    const url = resolveUrl(baseUrl, String(it[best.uk] == null ? '' : it[best.uk]).trim());
    if (!name || !/^https?:/i.test(url) || seen.has(url)) continue;
    seen.add(url);
    out.push({ name, url, isVolume: false });
  }
  return out.length >= 2 ? out : [];
}

/** HTML 目录页：找不到规则匹配时，按「章节样式文本 + 章节样式链接」收集 */
export function autoChaptersFromDoc(doc, baseUrl) {
  if (!doc || typeof doc.querySelectorAll !== 'function') return [];
  const NAV = /^(首页|目录|上一页|下一页|上一章|下一章|返回|登录|注册|书架|排行|分类|搜索|更多|展开|收起|加入书签)$/;
  const seen = new Set();
  const out = [];
  for (const a of doc.querySelectorAll('a[href]')) {
    const href = a.getAttribute('href') || '';
    if (!href || /^(#|javascript:|mailto:|tel:)/i.test(href)) continue;
    const text = (a.textContent || '').replace(/\s+/g, ' ').trim();
    if (text.length < 2 || text.length > 60 || NAV.test(text)) continue;
    const url = resolveUrl(baseUrl, href);
    if (!/^https?:/i.test(url) || seen.has(url)) continue;
    const looksChapter =
      /第\s*[0-9零一二三四五六七八九十百千万两]+\s*[章节回卷话]/.test(text) ||
      /^\d{1,4}$/.test(text) ||
      (/^\d{1,4}[\s.、:：]/.test(text) && /(chapter|\/read\/|\/\d+\.html)/i.test(url));
    if (!looksChapter) continue;
    seen.add(url);
    out.push({ name: text, url, isVolume: false });
  }
  // 少于 5 条多半是导航/推荐位，不当作目录
  return out.length >= 5 ? out : [];
}

export class BookSource {
  constructor(def) {
    // id 是由 bookSourceUrl 派生的只读 getter；入库记录若带 id 字段需忽略，否则 Object.assign 会抛错
    const { id: _ignored, ...rest } = def || {};
    Object.assign(this, rest);
    // Legado 书源 URL 偶见 "##" 后缀（旧语法残留），会污染所有相对地址拼接，先清掉
    if (this.bookSourceUrl) this.bookSourceUrl = String(this.bookSourceUrl).replace(/#+.*$/, '').trim();
    this.enabled = def.enabled !== false;
  }

  static fromJson(o) {
    const d = { ...o };
    // 容忍整段字符串化的规则对象
    for (const k of ['ruleSearch', 'ruleBookInfo', 'ruleToc', 'ruleContent', 'ruleExplore']) {
      if (typeof d[k] === 'string') {
        try {
          d[k] = JSON.parse(d[k]);
        } catch (e) {
          /* 保持原样 */
        }
      }
    }
    return new BookSource(d);
  }

  get id() {
    return this.bookSourceUrl + '|' + (this.bookSourceName || '');
  }

  /** Legado 兼容：{{source.getKey()}} 在书源规则里极常见，返回书源 URL 作为唯一键 */
  getKey() {
    return this.bookSourceUrl;
  }

  /** Legado 兼容：source.key 别名 */
  get key() {
    return this.bookSourceUrl;
  }

  /** Legado 兼容：书源级变量（源里常用于暂存 token / 验证码状态） */
  getVariable() {
    return this.variable == null ? '' : String(this.variable);
  }

  setVariable(v) {
    this.variable = v == null ? '' : String(v);
  }

  /* ---------- 请求 ---------- */

  /** 相对地址补全：以 bookSourceUrl 为 base（'/search.php' 常见于国产源） */
  _abs(u) {
    return resolveUrl(String(this.bookSourceUrl || '').trim(), u);
  }

  /** {{...}} 里的 JS 表达式求值（source.getKey()、source.bookSourceUrl 等） */
  async _evalTpl(expr, vars) {
    const inner = String(expr).trim();
    // {{别名}}：@put / java.put 存下的规则变量（如封面 id、token）
    const store = this.vars || {};
    if (/^[A-Za-z_$][\w$]*$/.test(inner) && store[inner] != null) return String(store[inner]);
    const ctx = this._ctx({ text: '', finalUrl: this.bookSourceUrl }, this.bookSourceUrl, vars);
    const r = await runJs(`return (${inner});`, ctx, '');
    return r == null ? '' : String(r);
  }

  /** 执行 @js: 段代码，取返回的地址；没显式 return 时退化为「最后一行表达式」求值 */
  async _evalJsCode(code, vars) {
    const ctx = this._ctx({ text: '', finalUrl: this.bookSourceUrl }, this.bookSourceUrl, vars);
    let out = '';
    try {
      out = await runJs(code, ctx, '');
    } catch (e) {
      out = '';
    }
    if (out == null || out === '') {
      // 没写 return：把最后一个表达式在**同一段代码的上下文里**执行（变量还在作用域内）。
      // 兼容两种写法：多行最后一行是表达式；单行用 ; 分隔、末段是表达式。
      const raw = String(code).trim();
      let lines = raw.split('\n');
      let last = (lines.pop() || '').trim();
      if (!lines.length && last.includes(';')) {
        const segs = last.split(';');
        const tail = (segs.pop() || '').trim();
        if (tail) {
          lines = [segs.join(';')];
          last = tail;
        }
      }
      last = last.replace(/;$/, '');
      if (last && !/^(return|var|let|const|if|for|while|\}|\{|\/\/)/.test(last) && !last.endsWith('{') && !last.endsWith('}')) {
        try {
          const body = lines.join('\n') + `\nreturn (${last});`;
          out = await runJs(body, ctx, '');
        } catch (e) {
          out = '';
        }
      }
    }
    return out == null ? '' : String(out);
  }

  /**
   * 解析 URL 模板（Legado 常见三种写法，缺一个都会整源失效）：
   *  1. 尾随 ,{"method":"POST","body":"...","headers":{}} 选项
   *  2. "@js:" 动态地址：执行 JS 得到真实 URL（签名/重定向）
   *  3. "{{js表达式}}" 插值 + {{key}}/{{page}} 标准变量
   * 最后统一做相对路径补全。
   */
  async buildRequest(template, vars = {}) {
    let t = String(template || '').trim();
    let opts = {};
    const m = /,(\{[\s\S]*\})\s*$/.exec(t);
    if (m) {
      const parsed = parseLooseOpts(m[1]);
      if (parsed) {
        opts = parsed;
        t = t.slice(0, m.index);
      }
    }
    const enc = encodeURIComponent;

    // @js: 动态地址 —— Legado 常见写法是 @js 返回 "url,{JSON选项}" 组合串，
    // 求值后必须再拆一次选项（否则整串被当 URL，实际请求到站点根/非法地址）
    if (/^@js[:;]/i.test(t)) {
      const code = t.replace(/^@js[:;]\s*/i, '');
      t = String((await this._evalJsCode(code, vars)) || '').trim();
      const m2 = /,(\{[\s\S]*\})\s*$/.exec(t);
      if (m2) {
        const parsed2 = parseLooseOpts(m2[1]);
        if (parsed2) {
          opts = { ...opts, ...parsed2 };
          t = t.slice(0, m2.index);
        }
      }
    }

    // {{...}} 逐个求值：key/searchKey 走标准编码，page 转字符串，其余当 JS 表达式
    const fill = async (s) => {
      // {{key}} / {{$..id}} / {$..id} —— 书源里两种花括号写法都常见
      const parts = String(s).split(/(\{\{[\s\S]*?\}\}|\{\$[^}]*\})/g);
      for (let i = 1; i < parts.length; i += 2) {
        const rawPh = parts[i];
        const inner = rawPh.startsWith('{{') ? rawPh.slice(2, -2).trim() : rawPh.slice(1, -1).trim();
        let val = '';
        if (/^(key|searchKey)$/.test(inner)) val = enc(vars.key ?? '');
        else if (inner === 'page') val = String(vars.page ?? 1);
        else {
          try {
            val = await this._evalTpl(inner, vars);
          } catch (e) {
            val = '';
          }
        }
        parts[i] = val;
      }
      return parts.join('');
    };

    const url = this._abs(await fill(t));
    // body 可能是对象（@js 里 JSON.stringify 前就给了对象/选项里直接写对象）→ 序列化成 JSON
    let bodyOut = opts.body;
    const headers = { ...parseHeader(this.header), ...(opts.headers || {}) };
    if (bodyOut != null && typeof bodyOut === 'object') {
      bodyOut = JSON.stringify(bodyOut);
      if (!Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) headers['content-type'] = 'application/json';
    } else if (bodyOut != null) {
      bodyOut = await fill(String(bodyOut));
    }
    return {
      url,
      method: (opts.method || 'GET').toUpperCase(),
      body: bodyOut,
      headers,
      charset: opts.charset || undefined,
    };
  }

  async fetchText(template, vars) {
    const { url, method, body, headers, charset } = await this.buildRequest(template, vars);
    const r = await fetchViaProxy(url, { method, headers, body, charset });
    r.method = method;
    r.reqUrl = url;
    return r;
  }

  /* ---------- 上下文 ---------- */

  _ctx(r, baseUrl, vars) {
    const ctx = createRuleContext({ body: r.text, baseUrl: baseUrl || r.finalUrl, source: this, logs: [] });
    // java.put/java.get 的存储跟随书源实例（Legado 语义：同一书源内跨规则共享）；
    // 之前每次请求都新建 {}，导致「搜索里 put 的 token，详情里 get 不到」→ url 为空
    ctx.src = this.vars || (this.vars = {});
    if (vars) ctx.vars = { ...ctx.vars, ...vars };
    try {
      ctx.json = JSON.parse(r.text);
    } catch (e) {
      /* 非 JSON */
    }
    ctx.doc = parseDoc(r.text);
    return ctx;
  }

  /** init / preUpdateJs 预处理：其返回值替换 doc 或 json */
  async _runPre(rule, ctx) {
    if (!rule) return;
    const out = await evalRule(rule, ctx);
    if (typeof out === 'string' && /<[a-z][\s\S]*>/i.test(out)) ctx.doc = parseDoc(out);
    else if (out && typeof out === 'object') ctx.json = out;
    else if (typeof out === 'string') {
      try {
        ctx.json = JSON.parse(out);
      } catch (e) {
        ctx.body = out;
      }
    }
  }

  /* ---------- 搜索 / 发现 ---------- */

  async search(keyword, page = 1) {
    const logs = [];
    if (!this.searchUrl) return { list: [], logs: ['未配置 searchUrl'] };
    let r;
    try {
      r = await this.fetchText(this.searchUrl, { key: keyword, page });
    } catch (e) {
      return { list: [], logs: [...logs, '请求失败: ' + e.message], error: e, snip: '' };
    }
    const snip = String(r.text || '').replace(/\s+/g, ' ').slice(0, 200);
    const meta = { status: r.status, finalUrl: r.finalUrl, snip, charset: r.charset, bytes: (r.text || '').length };
    logs.push(`${r.method || 'GET'} ${r.reqUrl || r.finalUrl} → ${r.status}（${r.charset || '?'}）${(r.text || '').length} 字节`);
    const rs = this.ruleSearch || {};
    if (rs.checkKeyWord && !String(r.text).includes(rs.checkKeyWord)) {
      return { list: [], logs: [...logs, 'checkKeyWord 未命中，跳过'], ...meta };
    }
    const ctx = this._ctx(r, r.finalUrl, { key: keyword, page });
    if (!rs.bookList) return { list: [], logs: [...logs, '未配置 ruleSearch.bookList'], ...meta };
    const rows = await extractRows(rs, ctx, 'bookList', SEARCH_FIELDS);
    logs.push(`bookList 命中 ${rows.length} 条`);
    const list = rows
      .map((row) => ({
        name: (row.name || '').toString().trim(),
        author: (row.author || '').toString().trim(),
        intro: row.intro || '',
        coverUrl: resolveUrl(r.finalUrl, row.coverUrl) || resolveUrl(this.bookSourceUrl, row.coverUrl),
        bookUrl: resolveUrl(r.finalUrl, row.bookUrl) || resolveUrl(this.bookSourceUrl, row.bookUrl),
        kind: row.kind || '',
        lastChapter: row.lastChapter || '',
        wordCount: row.wordCount || '',
        sourceUrl: this.bookSourceUrl,
        sourceName: this.bookSourceName || this.bookSourceUrl,
      }))
      // 没有 bookUrl 的条目既打不开也不可信（多半是列表选择器匹配到了推荐位），直接丢弃
      .filter((b) => b.name && b.bookUrl);
    const dropped = rows.length - list.length;
    if (dropped > 0) logs.push(`丢弃 ${dropped} 条缺少书名/书籍地址的结果`);
    return { list, logs, dropped, ...meta };
  }

  async explore(page = 1, exploreUrl) {
    const url = exploreUrl || this.exploreUrl || this.bookSourceUrl;
    const logs = [];
    let r;
    try {
      r = await this.fetchText(url, { page });
    } catch (e) {
      return { list: [], logs: ['请求失败: ' + e.message], error: e };
    }
    logs.push(`GET ${r.finalUrl} → ${r.status} ${r.text.length} 字节`);
    const re = this.ruleExplore || {};
    if (!re.bookList) return { list: [], logs: [...logs, '未配置 ruleExplore.bookList'] };
    const ctx = this._ctx(r);
    const rows = await extractRows(re, ctx, 'bookList', SEARCH_FIELDS);
    logs.push(`bookList 命中 ${rows.length} 条`);
    const list = rows
      .map((row) => ({
        name: (row.name || '').toString().trim(),
        author: (row.author || '').toString().trim(),
        intro: row.intro || '',
        coverUrl: resolveUrl(r.finalUrl, row.coverUrl) || resolveUrl(this.bookSourceUrl, row.coverUrl),
        bookUrl: resolveUrl(r.finalUrl, row.bookUrl) || resolveUrl(this.bookSourceUrl, row.bookUrl),
        kind: row.kind || '',
        lastChapter: row.lastChapter || '',
        sourceUrl: this.bookSourceUrl,
        sourceName: this.bookSourceName || this.bookSourceUrl,
      }))
      .filter((b) => b.name || b.bookUrl);
    return { list, logs };
  }

  /* ---------- 详情 ---------- */

  async getBookInfo(book) {
    const logs = [];
    if (!book || !book.bookUrl) {
      return { info: book, logs: ['缺少书籍地址（bookUrl）'], error: new Error('缺少书籍地址（bookUrl 规则未解析出内容）') };
    }
    let r;
    try {
      r = await this.fetchText(book.bookUrl, {});
    } catch (e) {
      return { info: book, logs: ['详情请求失败: ' + e.message], error: e };
    }
    const ctx = this._ctx(r, book.bookUrl);
    ctx.book = book;
    ctx.title = book.name || '';
    const rb = this.ruleBookInfo || {};
    // init 只用于预处理页面（常见写法是 JS 里换 JSON 接口），失败不该挡住整本
    try {
      await this._runPre(rb.init, ctx);
    } catch (e) {
      logs.push('init 失败（已忽略）: ' + e.message);
    }
    const row = {};
    // 字段级容错：单个字段规则失败不应让整本书「打不开」
    for (const f of INFO_FIELDS) {
      if (!rb[f]) {
        row[f] = null;
        continue;
      }
      try {
        row[f] = await evalOne(rb[f], ctx);
      } catch (e) {
        row[f] = null;
        logs.push(`字段 ${f} 解析失败: ${e.message}`);
      }
    }
    const info = {
      ...book,
      name: (row.name || book.name || '').toString().trim(),
      author: (row.author || book.author || '').toString().trim(),
      intro: row.intro || book.intro || '',
      coverUrl: resolveUrl(r.finalUrl, row.coverUrl) || book.coverUrl,
      kind: row.kind || book.kind || '',
      lastChapter: row.lastChapter || book.lastChapter || '',
      tocUrl: resolveUrl(r.finalUrl, row.tocUrl) || book.bookUrl,
    };
    return { info, logs };
  }

  /* ---------- 目录 ---------- */

  async getToc(book) {
    const logs = [];
    const rt = this.ruleToc || {};
    const first = book.tocUrl || book.bookUrl;
    if (!first) return { chapters: [], logs: ['缺少目录地址'], error: new Error('缺少目录地址') };
    if (!rt.chapterList) {
      return { chapters: [], logs: ['未配置 ruleToc.chapterList'], error: new Error('该书源未配置目录规则（ruleToc.chapterList）') };
    }

    /** 用书源自己的 chapterName/chapterUrl 规则映射 JSON 条目（可处理 cid 拼地址这类写法） */
    const mapJsonWithRules = async (arr, rules, ctx) => {
      const out = [];
      for (const item of arr.slice(0, 4000)) {
        if (!item || typeof item !== 'object') continue;
        const sub = subContext(ctx, item);
        let name = '';
        let url = '';
        try {
          if (rules.chapterName) name = String((await evalOne(rules.chapterName, sub)) || '').trim();
        } catch (e) {
          /* 忽略单条失败 */
        }
        try {
          if (rules.chapterUrl) url = String((await evalOne(rules.chapterUrl, sub)) || '').trim();
        } catch (e) {
          /* 忽略单条失败 */
        }
        if (!name || !url) continue;
        out.push({ name, url: resolveUrl(ctx.baseUrl, url), isVolume: false });
      }
      // 映射结果全是重复项时说明规则套错了（多半把整页 CSS 当成字段），放弃，交给自动识别
      const uniq = new Set(out.map((c) => c.name + '\n' + c.url));
      if (out.length < 2 || uniq.size !== out.length) return [];
      return out;
    };

    /** 抓一页并按规则解析章节（含 nextTocUrl 翻页） */
    const parsePage = async (url) => {
      let r;
      try {
        r = await this.fetchText(url, {});
      } catch (e) {
        logs.push(`目录请求失败（${url}）: ${e.message}`);
        return [];
      }
      logs.push(`目录页 ${r.finalUrl} → ${r.status} ${r.text.length} 字节`);
      const ctx = this._ctx(r, r.finalUrl || url);
      ctx.book = book;
      ctx.title = book.name || '';
      try {
        await this._runPre(rt.preUpdateJs, ctx);
      } catch (e) {
        logs.push('preUpdateJs 失败（已忽略）: ' + e.message);
      }

      const mapRows = (rows) =>
        rows
          .map((c) => ({
            name: (c.chapterName || '').toString().trim(),
            url: resolveUrl(ctx.baseUrl, c.chapterUrl),
            isVolume: !!c.isVolume,
          }))
          .filter((c) => c.name || c.url);

      let chapters = mapRows(await extractRows(rt, ctx, 'chapterList', TOC_FIELDS));
      if (chapters.length) {
        logs.push(`chapterList 命中 ${chapters.length} 章`);
      } else {
        // 规则一条没中时的通用兜底：先用书源自己的字段规则套 JSON 条目（可处理 cid 拼地址），
        // 再退回按字段名自动映射；HTML 则按章节链接自动收集
        let auto = [];
        if (ctx.json) {
          const found = findChapterArray(ctx.json);
          if (found) auto = await mapJsonWithRules(found.arr, rt, ctx);
          if (!auto.length) auto = autoChaptersFromJson(ctx.json, ctx.baseUrl);
        } else {
          auto = autoChaptersFromDoc(ctx.doc, ctx.baseUrl);
        }
        if (auto.length) {
          chapters = auto;
          logs.push(`chapterList 未命中（规则 ${String(rt.chapterList).slice(0, 60)}），自动识别 ${auto.length} 章`);
        } else {
          logs.push(`chapterList 命中 0 章（规则 ${String(rt.chapterList).slice(0, 60)}）`);
        }
      }
      if (!chapters.length) return [];

      // nextTocUrl 翻页
      if (rt.nextTocUrl) {
        let nextUrl = await evalOne(rt.nextTocUrl, ctx);
        let pages = 1;
        while (nextUrl && pages < 20) {
          const u = resolveUrl(ctx.baseUrl, nextUrl);
          let r2;
          try {
            r2 = await this.fetchText(u, {});
          } catch (e) {
            break;
          }
          const ctx2 = this._ctx(r2, u);
          try {
            await this._runPre(rt.preUpdateJs, ctx2);
          } catch (e) {
            logs.push('翻页 preUpdateJs 失败（已忽略）: ' + e.message);
          }
          const more = mapRows(await extractRows(rt, ctx2, 'chapterList', TOC_FIELDS));
          if (!more.length) break;
          chapters.push(...more);
          nextUrl = await evalOne(rt.nextTocUrl, ctx2);
          pages++;
        }
        logs.push(`翻页后共 ${chapters.length} 章`);
      }
      return chapters;
    };

    let chapters = await parsePage(first);
    // 兜底：不少书源的 tocUrl 规则会抓到「最新章节」之类的小组件，真正的目录其实在详情页。
    // 目录页 0 章时用 bookUrl 再解析一次，能救回一大批「打开失败」。
    if (!chapters.length && book.bookUrl && book.bookUrl !== first) {
      logs.push('目录页未命中，回退用详情页解析');
      chapters = await parsePage(book.bookUrl);
    }
    if (!chapters.length) {
      return { chapters, logs, error: new Error('未解析到章节（目录规则可能失效）') };
    }
    return { chapters, logs };
  }

  /* ---------- 正文 ---------- */

  async getContent(book, chapter) {
    const logs = [];
    let r;
    try {
      r = await this.fetchText(chapter.url, {});
    } catch (e) {
      return { content: '', logs: ['正文请求失败: ' + e.message], error: e };
    }
    const rc = this.ruleContent || {};
    const vars = { chapterName: chapter.name, chapterUrl: chapter.url };
    const ctx = this._ctx(r, chapter.url);
    ctx.vars = vars;
    ctx.book = book;
    ctx.chapter = chapter;
    ctx.title = chapter.name || '';

    let content = rc.content ? (await evalOne(rc.content, ctx)) || '' : '';
    if (!rc.content) return { content: '', logs: ['未配置 ruleContent.content'] };

    // nextContentUrl 翻页拼接
    if (rc.nextContentUrl) {
      let nextUrl = await evalOne(rc.nextContentUrl, ctx);
      let pages = 1;
      while (nextUrl && pages < 10) {
        const u = resolveUrl(ctx.baseUrl, nextUrl);
        let r2;
        try {
          r2 = await this.fetchText(u, {});
        } catch (e) {
          break;
        }
        const ctx2 = this._ctx(r2, u);
        ctx2.vars = vars;
        ctx2.book = book;
        ctx2.chapter = chapter;
        ctx2.title = chapter.name || '';
        const more = (await evalOne(rc.content, ctx2)) || '';
        if (!more) break;
        content += '\n' + more;
        nextUrl = await evalOne(rc.nextContentUrl, ctx2);
        pages++;
      }
    }

    if (rc.replaceRegex) content = applyReplaceString(content, rc.replaceRegex);
    content = absolutizeContentUrls(content, ctx.baseUrl);
    return { content, logs };
  }
}

/**
 * 补全正文 HTML 中的相对图片/链接地址。
 * 源站正文常用 <img src="images/x.jpg">，在 App 壳（origin 是本地）里会 404，
 * 需按章节页 base 补全为绝对地址（Legado 同款处理）。
 */
function absolutizeContentUrls(html, base) {
  if (!html || !base) return html || '';
  const fix = (m, attr, q, u) => {
    const t = String(u).trim();
    if (!t || /^(#|data:|javascript:|mailto:|tel:)/i.test(t)) return m;
    return attr + q + resolveUrl(base, t) + q;
  };
  return String(html)
    .replace(/(<img\b[^>]*?\ssrc\s*=\s*)(["'])([^"']*)\2/gi, fix)
    .replace(/(<a\b[^>]*?\shref\s*=\s*)(["'])([^"']*)\2/gi, fix);
}

/**
 * 宽松 JSON 解析：Legado 书源的 header / URL 选项大量使用单引号伪 JSON
 * （{'User-Agent':'...'}、{'method':'POST','body':'...'}），严格 JSON.parse 会失败，
 * 结果是 header 全丢（站点 403）或整串被当 URL 请求（404）——两者都表现为"源失效"。
 */
function parseLooseOpts(s) {
  if (!s) return null;
  try {
    return JSON.parse(s);
  } catch (e) {
    /* 继续宽松解析 */
  }
  try {
    const t = String(s)
      .replace(/'/g, '"')
      .replace(/([{,]\s*)([A-Za-z_$][\w$]*)\s*:/g, '$1"$2":');
    return JSON.parse(t);
  } catch (e) {
    return null;
  }
}

/** 解析书源 header 字段（Legado 存的是 JSON 字符串，常见单引号写法） */
function parseHeader(h) {
  if (!h) return {};
  if (typeof h === 'object') return h;
  return parseLooseOpts(h) || {};
}
