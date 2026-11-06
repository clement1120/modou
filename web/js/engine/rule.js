/**
 * rule.js — Legado 规则语法子集求值器
 *
 * 支持：
 *   CSS 选择器（含 Legado 扩展 tag./class./id. 写法）  @text/@html/@ownText/@textNodes/@all/@href/@src/@任意属性名
 *   @XPath: 或 // 开头的 XPath
 *   @Json: 或 $. 开头的 JSONPath
 *   : 开头的正则 AllInOne（对原始响应文本或列表项字符串匹配）
 *   @js: 或 {{ }} 内的 JavaScript（结果写回 result）
 *   && 链式、## 净化替换、# 分割、- 前缀倒序
 */
import { jsonPath } from './jsonpath.js';
import { runJs } from './sandbox.js';

/** 常见 HTML 标签名：`@li` 这类要当子选择器，`@href` 这类才当属性 */
const TAG_NAMES = new Set(
  'a p div span ul ol li dl dd dt tr td th table tbody thead tfoot h1 h2 h3 h4 h5 h6 img em strong b i u s small sub sup section article aside header footer nav main form input button select option textarea label br hr pre code blockquote figure figcaption video audio source iframe svg font center'.split(' ')
);

/* ---------- 正则执行的三道防线 ---------- */

/**
 * 书源规则是用户从别处抄来的，一条写坏的正则能让整章净化卡十几秒：
 * `(a+)+b` 配一段 aaa...a 是经典的指数回溯，JS 单线程 100%、整个服务页不再响应，
 * 原生侧看到的是「所有源一起超时」。下面三道防线不去"优化"用户的正则，
 * 只保证它**没机会跑满**。
 */

/** 1) 编译缓存：同一条规则每渲染一次就 new RegExp 一次，是白烧 CPU */
const RE_CACHE = new Map();
const RE_CACHE_MAX = 256;

/**
 * 2) 灾难回溯预检：分组内已有量词、分组本身又被量词修饰 → `(a+)+` `(\d*)*` `(.*)*$`。
 * 这类模式匹配失败时会尝试所有切分方式，长度每加一个字符耗时翻倍。
 * 只拦这一类（占线上「正则卡死」的绝大多数），误判代价是这条规则本次不生效，
 * 比卡死整页轻得多。
 */
export function reExplosive(pattern) {
  if (!pattern || pattern.length < 4) return false;
  const n = pattern.length;
  let depth = 0;
  const inner = [];
  for (let i = 0; i < n; i++) {
    const c = pattern[i];
    if (c === '\\') {
      i++;
      continue;
    }
    if (c === '[') {
      i++;
      while (i < n) {
        const d = pattern[i];
        if (d === '\\') i += 2;
        else if (d === ']') break;
        else i++;
      }
      continue;
    }
    if (c === '(') {
      depth++;
      inner[depth] = false;
      continue;
    }
    if (c === ')') {
      if (depth > 0) {
        const had = inner[depth];
        depth--;
        if (had && i + 1 < n) {
          const q = pattern[i + 1];
          if (q === '+' || q === '*') return true;
          if (q === '{' && i + 2 < n && /\d/.test(pattern[i + 2])) return true;
        }
        if (had) inner[depth] = true; // 向上传递：((a+)*)+ 也拦
      }
      continue;
    }
    if (depth > 0 && (c === '+' || c === '*' ||
        (c === '{' && i + 1 < n && /\d/.test(pattern[i + 1])))) {
      inner[depth] = true;
    }
  }
  return false;
}

/**
 * 编译（带缓存）。带 g 的正则有 lastIndex 状态，复用前必须归零 ——
 * 否则第二次调用会从上次停下的位置继续，静默漏掉前半段。
 */
export function reCompile(pattern, flags = '') {
  const key = flags + '|' + pattern;
  const hit = RE_CACHE.get(key);
  if (hit) {
    hit.lastIndex = 0;
    return hit;
  }
  if (reExplosive(pattern)) throw new Error('规则疑似灾难性回溯，已跳过');
  const re = new RegExp(pattern, flags);
  if (RE_CACHE.size >= RE_CACHE_MAX) RE_CACHE.clear(); // 规则集不会很大，直接清空即可
  RE_CACHE.set(key, re);
  return re;
}

/** 3) 单轮净化预算：一条规则正常几毫秒，整轮超过 400ms 就收手，下次渲染再来 */
const RE_BUDGET_MS = 400;

/* ---------- 解析 ---------- */

/**
 * 按顶层 && 切分链式规则。
 * 两个 JS 边界（与 Legado AppPattern.JS_PATTERN 对齐）：
 *   - {{ }} 占位符内部的 && 不切分；
 *   - @js: 段贪婪到规则串结尾——@js: 之后的 && 属于 JS 代码，不再切分
 *     （所以 Legado 书源里 @js: 总是写在链的最后）。
 */
export function splitChain(rule) {
  const segs = [];
  let cur = '';
  let inPh = 0;
  for (let i = 0; i < rule.length; i++) {
    const two = rule.slice(i, i + 2);
    if (two === '{{') {
      inPh++;
      cur += two;
      i++;
      continue;
    }
    if (two === '}}') {
      if (inPh > 0) inPh--;
      cur += two;
      i++;
      continue;
    }
    if (!inPh && two === '&&') {
      const head = cur.trimStart().replace(/^-+/, '');
      if (head.startsWith('@js:')) {
        cur += rule.slice(i); // 含 && 本身，全部留给 JS
        break;
      }
      segs.push(cur);
      cur = '';
      i++;
      continue;
    }
    cur += rule[i];
  }
  if (cur !== '') segs.push(cur);
  return segs;
}

/** 解析单段：剥离 ## 净化对、# 分割、@attr、- 倒序 */
/**
 * 方括号索引：`[0]` / `[!0]` / `[!-1]` / `[!0:-1]` / `[!0,-1]`。
 * Legado 里极常见（`p[!0:-1]`、`a[!-1]@href`），但方括号同时是 CSS 属性选择器
 * （`a[href]`、`div[class="x"]`），所以只在「内容全是数字/!:,」时才当索引，
 * 否则原样留给 CSS —— 拆错会把正常选择器变成 0 结果。
 */
function isIndexExpr(s) {
  const t = String(s).trim();
  if (!t || !/[0-9:]/.test(t)) return false;
  return /^[!\-0-9,: ]+$/.test(t);
}

/** 纯标签名或带 . # > 空格 的才算 CSS；`data[0]` 这种单标识符留给 JSONPath */
function looksCss(sel) {
  const t = String(sel).trim();
  if (!t) return false;
  if (/[.#>\s:+~]/.test(t)) return true;
  return typeof TAG_NAMES !== 'undefined' && TAG_NAMES.has(t.toLowerCase());
}

function splitRange(s) {
  const m = /\[([^\[\]]*)\]\s*$/.exec(String(s));
  if (!m || !isIndexExpr(m[1])) return { sel: s, range: null };
  return { sel: String(s).slice(0, m.index).trim(), range: m[1].replace(/\s+/g, '') };
}

function applyRange(list, range) {
  if (!Array.isArray(list) || !range) return list;
  const n = list.length;
  if (!n) return list;
  const at = (raw) => {
    const v = Number(String(raw).replace('!', ''));
    return v < 0 ? n + v : v;
  };
  if (range.indexOf(',') >= 0) {
    const out = [];
    for (const p of range.split(',')) {
      const i = at(p);
      if (i >= 0 && i < n && out.indexOf(list[i]) < 0) out.push(list[i]);
    }
    return out;
  }
  if (range.indexOf(':') >= 0) {
    const ab = range.split(':');
    const s = ab[0] === '' ? 0 : at(ab[0]);
    const e = ab[1] === '' ? n - 1 : at(ab[1]);
    if (s < 0 || e < s) return [];
    return list.slice(s, e + 1);
  }
  const i = at(range);
  return i >= 0 && i < n ? [list[i]] : [];
}

export function parseSegment(seg) {
  let s = String(seg);
  let reverse = false;
  if (s.startsWith('-')) {
    reverse = true;
    s = s.slice(1);
  }
  // JS 规则原样返回（代码里可能含 # / ## / @ 等字符，不能做后续解析）
  const head = s.trimStart();
  // <js>...</js> 是 Legado 的另一种 JS 规则写法，与 @js: 等价（不少源整条规则就是 <js>）
  const jsTag = /^<js>([\s\S]*?)<\/js>\s*$/i.exec(head);
  if (jsTag) {
    return { expr: '@js:' + jsTag[1], attr: null, replaces: [], sep: null, reverse, puts: [], gets: [] };
  }
  if (head.startsWith('@js:') || head.startsWith('{{')) {
    return { expr: head, attr: null, replaces: [], sep: null, reverse, puts: [], gets: [] };
  }

  // @js: 后缀（Legado 高频）：先求前缀选择器，再把结果作为 result 交给 JS 后处理。
  // 例：$.book_id@js:"https://x/" + result + ".txt"、@onclick@js:result.match(/\('(.*?)'/)[1]
  let jsAfter = null;
  const jsAt = s.indexOf('@js:');
  if (jsAt > 0) {
    jsAfter = s.slice(jsAt + 4);
    s = s.slice(0, jsAt);
  } else {
    // <js> 后缀：$.bid<js>java.put('bid',result); 'http://x/' + result</js>
    const mjs = /^([\s\S]*?)<js>([\s\S]*?)<\/js>\s*$/i.exec(s);
    if (mjs && mjs[1].trim()) {
      s = mjs[1];
      jsAfter = mjs[2];
    }
  }

  // @put:{别名:路径} / @get:{别名}：Legado 的"规则内变量"，不属于选择器，先剥离。
  // 不剥离的话会残留在 CSS/JSONPath 里 → 查询必失败 → 字段为空 → 整行被丢弃。
  // 注意内容是不带引号的对象字面量（{bid:_id}），JSON.parse 会失败，要按 k:v 解析。
  const parseMap = (str) => {
    const out = {};
    const body = String(str).replace(/^\{|\}$/g, '');
    const re = /([A-Za-z_$][\w$]*)\s*:\s*(?:"([^"]*)"|'([^']*)'|([^,}\s]+))/g;
    let m;
    while ((m = re.exec(body))) out[m[1]] = (m[2] ?? m[3] ?? m[4] ?? '').trim();
    return out;
  };
  const puts = [];
  const gets = [];
  s = s.replace(/@put:(\{[^{}]*\})/g, (_, j) => {
    puts.push(parseMap(j));
    return '';
  });
  s = s.replace(/@get:(\{[^{}]*\})/g, (_, j) => {
    gets.push(parseMap(j));
    return '';
  });

  // 模板字符串：{{}} 与普通文本混排（字段规则极常见，如 bookUrl "/books/{{$.id}}"、
  // kind "{{$.cat}},{{$.status}}@js:..."）。整条按模板渲染，绝不能当 CSS 选择器——
  // 否则 bookUrl 永远为空 → 整行被丢弃，表现为"命中 N 条丢弃 N 条"。
  const sTrim = s.trim();
  const hasTpl = /\{\{[\s\S]*?\}\}/.test(s) || /\{\$[^}]*\}/.test(s);
  if (hasTpl && !/^\{\{[\s\S]*\}\}$/.test(sTrim) && !sTrim.startsWith('@js')) {
    return { expr: sTrim, attr: null, replaces: [], sep: null, reverse, puts, gets, tpl: true, jsAfter };
  }

  const parts = s.split('##');
  const base0 = parts[0];
  const replaces = [];
  // ## 净化：既支持 pattern##replacement，也支持单段 pattern（= 删除匹配内容，极常见）
  for (let i = 1; i < parts.length; i += 2) replaces.push([parts[i], parts[i + 1] || '']);

  let sep = null;
  let base = base0;
  // `#` 分割（与 `#id` 选择器冲突时的取舍同 Legado：段首 # 视为 id 选择器，不分割；
  // 需要 id 选择时推荐 Legado 的 id.X 写法）。
  // 先切 # 再剥 @attr——否则 `.tags@text#,` 这类「属性 + 分割」组合会把 @text 吞进 sep。
  const hi = base.indexOf('#');
  if (hi > 0) {
    sep = base.slice(hi + 1);
    base = base.slice(0, hi);
  }

  // CSS 段的 @后缀 = 取属性值：任意属性名（@class/@data-id/...）；
  // text/html/ownText/textNodes/all 为特殊值（见 applyAttr）。
  // 只对 CSS 段剥离——@js:/@XPath:/@Json: 是类型前缀，//a/@href 是 XPath 属性轴。
  let attr = null;
  let idx = null;
  let selfAttr = false;
  // 裸词属性：Legado 允许 chapterName: "text"、chapterUrl: "href"（也是 ruleToc 的默认写法）。
  // 这类规则作用在「列表项元素自身」，不能当 CSS 标签去子元素里找 —— HTML 里没有
  // <text>/<href> 标签，查出来必然是空，整源 0 章且看不出原因。
  if (['text', 'href', 'html', 'ownText', 'textNodes', 'all'].includes(base.trim())) {
    attr = base.trim();
    base = '';
    selfAttr = true;
  }
  // "@onclick" / "@data-id"：没有选择器，取「当前元素自身」的属性（列表项常见写法）
  if (/^@[A-Za-z_][\w-]*$/.test(base.trim())) {
    attr = base.trim().slice(1);
    base = '';
    selfAttr = true;
  }
  if (!selfAttr && segType(base.trim()) === 'css') {
    const at = base.lastIndexOf('@');
    // 尾巴是标签名时不能当属性名：`ul@li!-1@a` 的 `@a` 是「再往下取 a」，
    // 当成属性就去取 <li> 的 a 属性 → 空字符串 → 目录 0 章。
    // 属性名（href/text/html/src/ownText…）都不在 TAG_NAMES 里，不会误伤。
    const tailRaw = at > 0 ? base.slice(at + 1).trim().replace(/!-?\d+/g, '').replace(/\.-?\d+$/, '') : '';
    const tailIsTag = TAG_NAMES.has(tailRaw.toLowerCase());
    // 后缀可为「属性名」，也兼容 Legado 的 @attr.0 / @attr[0]（属性 + 索引）
    const m = at > 0 && !tailIsTag
        ? base.slice(at + 1).match(/^([A-Za-z_][\w-]*)(?:\.(\d+)|\[(\d+)\])?$/) : null;
    if (m) {
      attr = m[1];
      if (m[2] != null) idx = Number(m[2]);
      else if (m[3] != null) idx = Number(m[3]);
      base = base.slice(0, at);
    }
  }
  // 方括号索引：p[0] / p[!-1] / p[!0:-1]（Legado 高频，之前完全没实现 →
  // 这类字段一律取空，表现为「原文有内容、规则用过、结果 0 条」）
  const rr = looksCss(base) ? splitRange(base) : { sel: base, range: null };
  let range = rr.range;
  base = rr.sel;

  // @@ 前缀：忽略当前列表项，直接在整篇文档上查（Legado 语义）
  let wholeDoc = false;
  if (base.trim().startsWith('@@')) {
    base = base.trim().slice(2);
    wholeDoc = true;
  }

  // 链式选择器 + 排除索引（Legado 高频）：
  //   class.novelslist2@ul@li     → .novelslist2 内找 ul 再找 li
  //   .txt-list-row5@li!0         → 同上，排除第 0 个
  //   class.name.0@tag.a.0@href   → 逐级下降后取 href
  // 之前把 @li/@ul 当属性名 → 选择器合法但取不到 → 整源 0 条。
  if (segType(base.trim()) === 'css' && base.includes('@') && !base.startsWith('@')) {
    const rawParts = base.split('@').filter(Boolean);
    const lastPart = (rawParts[rawParts.length - 1] || '').trim();
    const lastClean = lastPart.replace(/!-?\d+/g, '');
    const lastIsSubsel =
      /^(tag|class|id)\./.test(lastPart) || TAG_NAMES.has(lastClean.toLowerCase()) || /[.#\[\]()>+~: ]/.test(lastClean);
    const selParts = lastIsSubsel ? rawParts : rawParts.slice(0, -1);
    if (selParts.length) {
      const steps = selParts.map((seg1) => {
        let sel = seg1.trim();
        let sidx = null;
        const excl = [];
        // 每级也能带方括号索引：.list@li[0]、@div@a[!0:-1]
        let srange = null;
        if (looksCss(sel)) {
          const sr = splitRange(sel);
          sel = sr.sel;
          srange = sr.range;
        }
        sel = sel.replace(/!(-?\d+)/g, (_, n) => {
          excl.push(Number(n));
          return '';
        });
        const m = /^(.*?)\.(-?\d+)$/.exec(sel.trim());
        if (m && m[1]) {
          sel = m[1];
          sidx = Number(m[2]);
        }
        return { sel: sel.trim(), idx: sidx, excl, range: srange };
      });
      return {
        expr: '',
        chain: steps,
        attr: lastIsSubsel ? attr : lastPart,
        idx,
        range,
        replaces,
        sep,
        reverse,
        puts,
        gets,
        jsAfter,
      };
    }
  }

  // 选择器尾部的 .N = 取第 N 个结果（Legado 高频写法：a.0、class.name.0、tag.a.1；
  // 不做这一步时整个选择器会被当非法 CSS → 空值 → 整行丢弃）
  let elemIdx = null;
  if (segType(base.trim()) === 'css') {
    const im = /^(.*?)\.(-?\d+)$/.exec(base.trim());
    if (im && im[1]) {
      base = im[1];
      elemIdx = Number(im[2]);
    }
  }
  return { expr: base.trim(), attr, idx, range, elemIdx, selfAttr, wholeDoc, replaces, sep, reverse, puts, gets, jsAfter };
}

function segType(expr, ctx) {
  if (expr.startsWith('@js:') || expr.startsWith('{{')) return 'js';
  if (expr.startsWith('@css:')) return 'css';
  if (expr.startsWith('@XPath:') || expr.startsWith('//')) return 'xpath';
  if (expr.startsWith('@Json:') || expr.startsWith('@json:') || expr.startsWith('$.') || expr.startsWith('$[')) return 'json';
  // 兼容未写 $. 前缀、但明显是 JSONPath 的规则（如 data.state[*]、list[?(...)]）
  if (/\[\*\]|\[\?\(/.test(expr)) return 'json';
  // JSON 数据上的普通字段名（title / url / chapterName…）按 JSON 键取，
  // 否则会被当 CSS 选择器去 HTML 文档里查，结果永远为空——JSON 接口型书源因此「打不开」。
  if (ctx && ctx.json != null && /^[A-Za-z_$][\w$]*(\.[\w$]+|\[\d+\])*$/.test(expr)) return 'json';
  if (expr.startsWith(':')) return 'regex';
  return 'css';
}

/* ---------- 输入适配 ---------- */

function parseHtml(str) {
  const s = String(str);
  const wrap = /<html[\s>]/i.test(s) ? s : '<div id="__frag">' + s + '</div>';
  return new DOMParser().parseFromString(wrap, 'text/html');
}

function toJson(input, ctx) {
  if (input && typeof input === 'object') return input;
  if (typeof input === 'string') {
    try {
      return JSON.parse(input);
    } catch (e) {
      /* fallthrough */
    }
  }
  return ctx.json || null;
}

/**
 * CSS/XPath 的查询根：
 * 链式中间元素 > 列表项元素（ctx.result）> HTML 字符串（解析）> 整页 doc
 */
function queryRoot(input, ctx) {
  if (input && input.nodeType) return input;
  if (input == null && ctx.result && ctx.result.nodeType) return ctx.result;
  if (typeof input === 'string' && /<[a-z][\s\S]*>/i.test(input)) return parseHtml(input);
  return ctx.doc || null;
}

function toText(input) {
  if (input == null) return '';
  if (typeof input === 'string') return input;
  if (Array.isArray(input)) return input.map(toText).join('\n');
  if (input.nodeType) return input.textContent || '';
  return String(input);
}

/* ---------- 各类型求值 ---------- */

/** Legado 允许 @css: 前缀，等价于普通 CSS 规则 */
function cssSel(expr) {
  return cssTranslate(String(expr).replace(/^@css:/i, ''));
}

function cssTranslate(sel) {
  return sel
    .trim()
    .replace(/\btag\.([\w-]+)/g, '$1')
    .replace(/\bclass\.([\w-]+)/g, '.$1')
    .replace(/\bid\.([\w-]+)/g, '#$1');
}

function cssEval(root, sel) {
  if (!root) return [];
  try {
    return Array.from(root.querySelectorAll(sel));
  } catch (e) {
    // 非法选择器不抛错：返回空，避免单个坏规则中断整本书/整次搜索
    return [];
  }
}

function xpathEval(doc, xp, contextNode) {
  if (!doc || typeof doc.evaluate !== 'function') {
    throw new Error('当前环境不支持 XPath（缺少 document.evaluate，浏览器 WebView 中可用）');
  }
  const r = doc.evaluate(xp, contextNode || doc, null, XPathResult.ANY_TYPE, null);
  const out = [];
  let n;
  switch (r.resultType) {
    case XPathResult.STRING_TYPE:
      return r.stringValue;
    case XPathResult.NUMBER_TYPE:
      return String(r.numberValue);
    case XPathResult.BOOLEAN_TYPE:
      return String(r.booleanValue);
    default:
      while ((n = r.iterateNext())) out.push(n);
      return out;
  }
}

function regexAll(pattern, text) {
  if (text == null) return [];
  const re = reCompile(pattern, 'g');
  const out = [];
  let m;
  while ((m = re.exec(text)) !== null) {
    out.push(m.length > 1 ? m[1] : m[0]);
    if (m.index === re.lastIndex) re.lastIndex++;
  }
  return out;
}

function applyAttr(out, attr) {
  if (!attr) return out;
  // @children：取子元素列表（不转字符串，供后续链式继续查）
  if (attr === 'children') {
    const arr = Array.isArray(out) ? out : [out];
    return arr.flatMap((v) => (v && v.nodeType === 1 ? Array.from(v.children) : []));
  }
  const one = (v) => {
    if (v == null) return '';
    if (typeof v === 'string') return v;
    if (v.nodeType !== 1) return String(v);
    switch (attr) {
      case 'text':
        return (v.textContent || '').trim();
      case 'ownText':
        return Array.from(v.childNodes)
          .filter((n) => n.nodeType === 3)
          .map((n) => n.textContent)
          .join('')
          .trim();
      case 'textNodes':
        return (v.textContent || '').replace(/\s+/g, ' ').trim();
      case 'html':
        return v.innerHTML || '';
      case 'all':
        return v.outerHTML || '';
      default:
        return v.getAttribute(attr) || '';
    }
  };
  return Array.isArray(out) ? out.map(one) : one(out);
}

function applyReplaces(out, replaces) {
  if (!replaces.length) return out;
  const t0 = Date.now();
  const one = (v) => {
    if (typeof v !== 'string') return v;
    let s = v;
    for (const [pat, rep] of replaces) {
      if (Date.now() - t0 > RE_BUDGET_MS) break; // 整轮超预算：放弃剩余净化，别拖着卡
      try {
        s = s.replace(reCompile(pat, 'g'), rep);
      } catch (e) {
        /* 跳过坏正则 */
      }
    }
    return s;
  };
  return Array.isArray(out) ? out.map(one) : one(out);
}

function splitResult(out, sep) {
  if (out == null) return [];
  const s = typeof out === 'string' ? out : toText(out);
  return sep === '' ? Array.from(s) : s.split(sep);
}

/**
 * JS 规则的 result 入参。
 *
 * Legado 的 @js 里 result 是**字符串**（上一步的文本，没上一步就是整页 HTML），
 * 不是 Jsoup 元素。以前这里把列表项 DOM 元素原样塞进去，源里写
 * `result.replace(...)` / `result.match(...)` 直接 TypeError → 字段为空 → 整行丢弃，
 * 表现为「搜到了页面却 0 条」。元素一律降级成 HTML 串。
 */
function jsInputOf(input, ctx) {
  const pick = input != null ? input : ctx.result != null ? ctx.result : ctx.body;
  const one = (v) => {
    if (v == null) return '';
    if (v.nodeType === 1) return v.outerHTML || v.textContent || '';
    if (v.nodeType === 9) return ctx.body || '';
    return v;
  };
  if (Array.isArray(pick)) {
    const parts = pick.map(one);
    return parts.every((p) => typeof p === 'string') ? parts.join('\n') : parts;
  }
  return one(pick);
}

/** 单段执行 */

/**
 * 模板渲染：{{}} 与普通文本混排，逐段求值拼接；尾部 @js: 以渲染结果为 result 继续跑。
 *
 * <p>抽成独立函数是因为「字段值本身就是一段模板」很常见：bookUrl 写成
 * `@js:'/app/info/bookindex,{"method":"POST","body":"...bID={{$.KEYID}}..."}'` 时，
 * JS 字符串不会解析 {{}}（$ 在 JS 里也未定义），占位符会原样留在地址里发出去。
 * extractRows 拿到结果后再渲染一次，这条链路才闭合。
 */
async function renderTpl(expr, input, ctx, reverse = false) {
  let tpl = expr;
  let jsCode = null;
  const ji = String(expr).indexOf('@js:');
  if (ji > 0) {
    tpl = String(expr).slice(0, ji);
    jsCode = String(expr).slice(ji + 4);
  }
  const parts = tpl.split(/(\{\{[\s\S]*?\}\}|\{\$[^}]*\})/g);
  for (let i = 1; i < parts.length; i += 2) {
    const rawPh = parts[i];
    let inner = rawPh.startsWith('{{') ? rawPh.slice(2, -2).trim() : rawPh.slice(1, -1).trim();
    // 插值内可带 ## 净化：{{$..docId##.*_}}
    const clean = [];
    if (inner.includes('##')) {
      const seg2 = inner.split('##');
      inner = seg2[0].trim();
      for (let k = 1; k < seg2.length; k += 2) clean.push([seg2[k], seg2[k + 1] || '']);
    }
    let v = '';
    try {
      if (/^\$/.test(inner)) {
        const obj = toJson(input, ctx);
        const got = obj == null ? [] : jsonPath(obj, inner);
        v = Array.isArray(got) ? got[0] : got;
      } else if (/^[A-Za-z_$][\w$]*$/.test(inner) && ctx.vars && ctx.vars[inner] != null) {
        v = ctx.vars[inner];
      } else {
        v = await runJs(`return (${inner});`, ctx, input != null ? input : ctx.result != null ? ctx.result : ctx.body);
      }
    } catch (e) {
      v = '';
    }
    let val = v == null ? '' : String(v);
    for (const [pat, rep] of clean) {
      try {
        val = val.replace(reCompile(pat, 'g'), rep);
      } catch (e) {
        /* 坏正则忽略 */
      }
    }
    parts[i] = val;
  }
  let rendered = parts.join('');
  if (jsCode) {
    try {
      rendered = await runJs(jsCode, ctx, rendered);
    } catch (e) {
      /* JS 失败保留模板结果 */
    }
  }
  let outT = Array.isArray(rendered) ? rendered : [rendered];
  if (reverse) outT = [...outT].reverse();
  return outT;
}

async function evalSegment(seg, input, ctx) {
  let { expr } = seg;
  expr = expr.replace(/\{\{baseUrl\}\}/g, ctx.baseUrl || '');
  const type = segType(expr, ctx);

  // 模板字符串：{{}} 与文本混排，逐段求值拼接；尾部 @js: 以渲染结果为 result 继续跑
  if (seg.tpl) return renderTpl(expr, input, ctx, seg.reverse);

  let out;
  if (type === 'js') {
    let code = expr;
    if (code.startsWith('@js:')) code = code.slice(4);
    else if (code.startsWith('{{') && code.endsWith('}}')) code = code.slice(2, -2);
    // Legado 的 {{$._id}} / {{.name}} 是「在当前列表项上取 JSONPath」，不是 JS 表达式。
    // 当 JS 执行会 ReferenceError（$ 未定义）→ 字段为空 → 整行被丢弃（"命中 N 条丢弃 N 条"）。
    const inner = code.trim();
    if (expr.startsWith('{{') && /^\$/.test(inner)) {
      const obj = toJson(input, ctx);
      out = obj == null ? [] : jsonPath(obj, inner);
    } else if (expr.startsWith('{{') && /^[A-Za-z_$][\w$]*$/.test(inner) && ctx.vars && ctx.vars[inner] != null) {
      // {{别名}}：@put 存下的规则变量（如 name 规则里 put 的 bid）
      out = ctx.vars[inner];
    } else {
      // Legado 语义：result 初始值为前一段结果（列表项场景即列表项本身）；
      // 没有上游（如 bookList 的 <js>）时用页面正文——否则 result=null，源里读 result 的规则全废
      try {
        out = await runJs(code, ctx, jsInputOf(input, ctx));
      } catch (e) {
        // 书源规则质量参差：一段坏 JS 不该抛穿整条规则（否则整次解析直接「失败」，
        // 连其它正常字段一起没了）。按 Legado 语义降级为空结果，并留日志供排查。
        out = [];
        if (ctx.logs) ctx.logs.push('JS 规则执行失败: ' + ((e && e.message) || e));
      }
    }
  } else if (type === 'json') {
    const obj = toJson(input, ctx);
    const rawP = expr.replace(/^@[Jj]son:/, '').trim();
    // 兼容未写 $. 的 JSONPath（如 data.state[*] → $.data.state[*]）
    const p = rawP.startsWith('$') || rawP.startsWith('.') ? rawP : '$.' + rawP;
    if (obj == null) out = [];
    else if (Array.isArray(input) && typeof input[0] === 'object' && p.startsWith('.')) {
      out = input.map((it) => jsonPath(it, '$' + p)[0]); // 逐项
    } else out = jsonPath(obj, p);
  } else if (type === 'xpath') {
    const root = queryRoot(input, ctx);
    if (!root) out = [];
    else if (root.nodeType === 9) out = xpathEval(root, expr.replace(/^@XPath:/, ''));
    else out = xpathEval(root.ownerDocument, expr.replace(/^@XPath:/, ''), root);
  } else if (type === 'regex') {
    out = regexAll(expr.slice(1), toText(input != null ? input : ctx.body));
  } else if (seg.selfAttr) {
    // 当前元素自身（列表项）：input 是元素就用它，否则用 ctx.result
    const el = input && input.nodeType === 1 ? input : ctx.result && ctx.result.nodeType === 1 ? ctx.result : null;
    out = el ? [el] : [];
  } else if (seg.wholeDoc) {
    // @@ 规则：无视当前项，从整篇文档查
    const root = ctx.doc;
    out = root ? cssEval(root, cssSel(expr)) : [];
    if (seg.elemIdx != null && Array.isArray(out)) out = seg.elemIdx < 0 ? out.slice(seg.elemIdx) : out.slice(seg.elemIdx, seg.elemIdx + 1);
  } else if (seg.chain) {
    // 链式选择器：逐级在当前结果集内查询，每级可带 .N 索引
    let els = null;
    for (const st of seg.chain) {
      const sel = cssSel(st.sel);
      if (els === null) {
        const root = queryRoot(input, ctx);
        els = root ? cssEval(root, sel) : [];
      } else {
        els = els.flatMap((el) => {
          try {
            return Array.from(el.querySelectorAll(sel));
          } catch (e) {
            return [];
          }
        });
      }
      // 先应用排除（!0 / !-1），再按 .N 取单个
      if (st.excl && st.excl.length) {
        els = els.filter((_, i) => !st.excl.includes(i) && !st.excl.includes(i - els.length));
      }
      // 方括号索引（li[0] / li[!0:-1]）：作用在完整结果集上
      if (st.range) els = applyRange(els, st.range);
      if (st.idx != null) {
        const at = st.idx < 0 ? els.length + st.idx : st.idx;
        els = els[at] ? [els[at]] : [];
      }
    }
    out = els || [];
  } else if (Array.isArray(input) && input.length && input[0] && input[0].nodeType === 1) {
    // 元素数组输入（列表规则之后的链式段）→ 逐元素查询后合并（Legado 语义）
    out = input.flatMap((el) => {
      try {
        const sel = cssSel(expr);
        const got = Array.from(el.querySelectorAll(sel));
        return seg.elemIdx != null ? (seg.elemIdx < 0 ? got.slice(seg.elemIdx) : got.slice(seg.elemIdx, seg.elemIdx + 1)) : got;
      } catch (e) {
        return [];
      }
    });
  } else {
    const root = queryRoot(input, ctx);
    out = root ? cssEval(root, cssSel(expr)) : [];
    if (seg.elemIdx != null && Array.isArray(out)) out = seg.elemIdx < 0 ? out.slice(seg.elemIdx) : out.slice(seg.elemIdx, seg.elemIdx + 1);
  }

  // 方括号索引必须作用在「元素列表」上、且在取属性之前：
  // p[0]@text 是「第 0 段的文本」，先 @text 会把整段拼成一串，索引就失效了。
  if (seg.range) out = applyRange(out, seg.range);
  out = applyAttr(out, seg.attr);
  if (seg.idx != null && Array.isArray(out)) out = out.slice(seg.idx, seg.idx + 1);
  // @js: 后缀处理：result = 前一步结果（属性值/元素文本）
  if (seg.jsAfter) {
    try {
      out = await runJs(seg.jsAfter, ctx, Array.isArray(out) ? out[0] : out);
    } catch (e) {
      /* JS 后处理失败保留原值 */
    }
  }
  out = applyReplaces(out, seg.replaces);
  if (seg.sep != null) out = splitResult(out, seg.sep);
  if (seg.reverse && Array.isArray(out)) out = [...out].reverse();

  // @put：把当前项的字段存进规则变量（后续字段的 {{别名}} 能取到，如 name 存 _id 给 bookUrl 用）
  if (seg.puts && seg.puts.length) {
    for (const p of seg.puts) {
      for (const [k, path] of Object.entries(p)) {
        try {
          const obj = toJson(input, ctx);
          if (obj != null) {
            const pth = String(path).startsWith('$') ? String(path) : '$.' + String(path);
            const v = jsonPath(obj, pth);
            const val = Array.isArray(v) ? v[0] : v;
            ctx.vars[k] = val;
            if (ctx.src) ctx.src[k] = val; // 同步到书源级（URL 模板里的 {{别名}} 也要能取）
          }
        } catch (e) {
          /* 单个别名失败不影响字段本身 */
        }
      }
    }
  }
  // @get：{别名} → 输出变量值
  if (seg.gets && seg.gets.length) {
    const key = Object.keys(seg.gets[0])[0];
    if (key && ctx.vars && ctx.vars[key] != null) out = ctx.vars[key];
  }
  return out;
}

/* ---------- 对外 API ---------- */

/** 求值整条规则（可能多段链式） *//** 按顶层 || 拆分（{{}} 与 @js 段内的 || 不切） */
function splitOr(rule) {
  const out = [];
  let cur = '';
  let inPh = 0;
  for (let i = 0; i < rule.length; i++) {
    const two = rule.slice(i, i + 2);
    if (two === '{{') {
      inPh++;
      cur += two;
      i++;
      continue;
    }
    if (two === '}}') {
      if (inPh > 0) inPh--;
      cur += two;
      i++;
      continue;
    }
    if (!inPh && two === '||') {
      // @js 段里的 || 是 JS 逻辑或，不是「规则兜底」分隔符：
      // 一旦当前段以 @js: 开头，后面整串都归它（与 splitChain 对 && 的处理一致）
      if (cur.trimStart().replace(/^-+/, '').startsWith('@js:')) {
        cur += rule.slice(i);
        break;
      }
      out.push(cur);
      cur = '';
      i++;
      continue;
    }
    cur += rule[i];
  }
  out.push(cur);
  return out;
}

export async function evalRule(rule, ctx) {
  if (rule == null || rule === '') return null;
  const ruleStr = String(rule);
  // 「规则1||规则2」：依次尝试，取第一个有结果（Legado 的多写法兜底）。
  // 以前只要整串里出现 @js: 就整体跳过 || —— 于是「css规则||@js:兜底」这种最常见的写法
  // 被当成一条规则执行，css 段没命中就直接空，兜底 JS 永远轮不到。
  if (ruleStr.includes('||')) {
    const parts = splitOr(ruleStr);
    if (parts.length > 1) {
      for (const p of parts) {
        const v = await evalRule(p, ctx);
        const empty = v == null || (Array.isArray(v) && !v.length) || String(v).trim() === '';
        if (!empty) return v;
      }
      return null;
    }
  }
  const segs = splitChain(ruleStr);
  let input = null;
  for (const s of segs) input = await evalSegment(parseSegment(s), input, ctx);
  return input;
}

function firstOf(v) {
  if (Array.isArray(v)) return v.length ? v[0] : null;
  return v;
}

/** 单值规则（取首个） */
export async function evalOne(rule, ctx) {
  return firstOf(await evalRule(rule, ctx));
}

/** 列表规则 */
export async function evalList(rule, ctx) {
  const v = await evalRule(rule, ctx);
  if (v == null) return [];
  const arr = Array.isArray(v) ? v : [v];
  // JSONPath 命中数组时结果常是「只含一个数组」的包装层（如 $.data.chapters），拆开
  return arr.length === 1 && Array.isArray(arr[0]) ? arr[0] : arr;
}

/* ---------- 标量字段取值（Legado 语义） ---------- */

/**
 * 把规则结果归一成「一个字段该有的字符串」。
 *
 * Legado 里 `name: '.book-name'` 这种不写 @text 的写法是**取元素文本**；
 * 但 CSS 查询本身只能返回元素对象。之前直接把元素丢给上层：
 * 浏览器里元素会被 String() 成 `[object HTMLSpanElement]`，
 * 于是书名/作者全是这串垃圾 —— 相关性过滤一看不含关键词，
 * 整页结果被当成「与关键词无关」全部丢弃，搜索就永远空。
 * 这里统一按 Legado 语义取 textContent；字符串（@href/@text 的结果）原样返回。
 */
export function toFieldText(v) {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (Array.isArray(v)) {
    for (const it of v) {
      const s = toFieldText(it);
      if (s) return s;
    }
    return '';
  }
  if (v.nodeType) return (v.textContent || '').replace(/\s+/g, ' ').trim();
  return String(v);
}

/** 正文取值：元素保留内部 HTML（段落/换行要留下来），字符串原样 */
export function toFieldHtml(v) {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  if (Array.isArray(v)) return v.map(toFieldHtml).filter(Boolean).join('\n');
  if (v.nodeType) return v.innerHTML || '';
  return String(v);
}

/** 求一个字段的文本值（规则里没写 @attr 时也不会拿到元素对象） */
export async function evalText(rule, ctx) {
  if (rule == null || rule === '') return '';
  const v = toFieldText(await evalOne(rule, ctx));
  // 同 extractRows：@js 返回的地址里可能还嵌着 {{$.xxx}}（JS 字符串不解析占位符），
  // 详情页的 tocUrl、目录页的 chapterUrl 都会踩这个坑 —— 地址带着 "{{$.ID}}" 发出去。
  if (typeof v === 'string' && (v.includes('{{') || v.includes('{$'))) {
    const rr = await renderTpl(v, ctx.json != null ? ctx.json : null, ctx, false);
    const filled = rr == null ? '' : Array.isArray(rr) ? rr[0] : rr;
    if (filled != null && String(filled).trim()) return String(filled);
  }
  return v;
}

/* ---------- 同步求值（供 java.getString / getStringList 使用） ---------- */

/** 与 evalSegment 相同，但遇到 @js 直接报错（同步上下文无法 await） */
function evalSegmentSync(seg, input, ctx) {
  let { expr } = seg;
  expr = expr.replace(/\{\{baseUrl\}\}/g, ctx.baseUrl || '');
  const type = segType(expr, ctx);
  let out;
  if (type === 'js') {
    throw new Error('同步规则不支持内嵌 @js');
  } else if (seg.selfAttr) {
    const el = input && input.nodeType === 1 ? input : ctx.result && ctx.result.nodeType === 1 ? ctx.result : null;
    out = el ? [el] : [];
  } else if (seg.chain) {
    // 链式选择器（java.getElements('#main@class.list') 这类写法走同步路径）
    let els = null;
    for (const st of seg.chain) {
      const sel = cssSel(st.sel);
      if (els === null) {
        const root = queryRoot(input, ctx);
        els = root ? cssEval(root, sel) : [];
      } else {
        els = els.flatMap((el) => {
          try {
            return Array.from(el.querySelectorAll(sel));
          } catch (e) {
            return [];
          }
        });
      }
      if (st.excl && st.excl.length) els = els.filter((_, i) => !st.excl.includes(i) && !st.excl.includes(i - els.length));
      if (st.idx != null) {
        const at = st.idx < 0 ? els.length + st.idx : st.idx;
        els = els[at] ? [els[at]] : [];
      }
    }
    out = els || [];
  } else if (type === 'json') {
    const obj = toJson(input, ctx);
    const rawP = expr.replace(/^@[Jj]son:/, '').trim();
    const p = rawP.startsWith('$') || rawP.startsWith('.') ? rawP : '$.' + rawP;
    if (obj == null) out = [];
    else if (Array.isArray(input) && typeof input[0] === 'object' && p.startsWith('.')) out = input.map((it) => jsonPath(it, '$' + p)[0]);
    else out = jsonPath(obj, p);
  } else if (type === 'xpath') {
    const root = queryRoot(input, ctx);
    if (!root) out = [];
    else if (root.nodeType === 9) out = xpathEval(root, expr.replace(/^@XPath:/, ''));
    else out = xpathEval(root.ownerDocument, expr.replace(/^@XPath:/, ''), root);
  } else if (type === 'regex') {
    out = regexAll(expr.slice(1), toText(input != null ? input : ctx.body));
  } else if (Array.isArray(input) && input.length && input[0] && input[0].nodeType === 1) {
    out = input.flatMap((el) => {
      try {
        const got = Array.from(el.querySelectorAll(cssSel(expr)));
        return seg.elemIdx != null ? (seg.elemIdx < 0 ? got.slice(seg.elemIdx) : got.slice(seg.elemIdx, seg.elemIdx + 1)) : got;
      } catch (e) {
        return [];
      }
    });
  } else {
    const root = queryRoot(input, ctx);
    out = root ? cssEval(root, cssSel(expr)) : [];
    if (seg.elemIdx != null && Array.isArray(out)) out = seg.elemIdx < 0 ? out.slice(seg.elemIdx) : out.slice(seg.elemIdx, seg.elemIdx + 1);
  }
  if (seg.range) out = applyRange(out, seg.range);
  out = applyAttr(out, seg.attr);
  if (seg.idx != null && Array.isArray(out)) out = out.slice(seg.idx, seg.idx + 1);
  out = applyReplaces(out, seg.replaces);
  if (seg.sep != null) out = splitResult(out, seg.sep);
  if (seg.reverse && Array.isArray(out)) out = [...out].reverse();
  return out;
}

export function evalRuleSync(rule, ctx) {
  if (rule == null || rule === '') return null;
  const segs = splitChain(String(rule));
  let input = null;
  for (const s of segs) input = evalSegmentSync(parseSegment(s), input, ctx);
  return input;
}

export function evalOneSync(rule, ctx) {
  const v = evalRuleSync(rule, ctx);
  return Array.isArray(v) ? (v.length ? v[0] : null) : v;
}

export function evalListSync(rule, ctx) {
  const v = evalRuleSync(rule, ctx);
  if (v == null) return [];
  const arr = Array.isArray(v) ? v : [v];
  return arr.length === 1 && Array.isArray(arr[0]) ? arr[0] : arr;
}

/** 为列表项创建子上下文（element / HTML 字符串 / JSON 对象） */
export function subContext(ctx, item) {
  const sub = {
    doc: ctx.doc,
    json: null,
    body: ctx.body,
    baseUrl: ctx.baseUrl,
    source: ctx.source,
    logs: ctx.logs,
    src: ctx.src,
    vars: ctx.vars,
    runJs: ctx.runJs,
    // 规则 JS 里可直接引用的 Legado 全局，列表项子上下文同样要带上
    book: ctx.book,
    chapter: ctx.chapter,
    title: ctx.title,
    cookie: ctx.cookie,
  };
  if (item && item.nodeType === 1) {
    sub.doc = item.ownerDocument;
    sub.result = item;
    sub.body = item.outerHTML; // :正则 字段规则作用于列表项自身 HTML
  } else if (typeof item === 'string') {
    sub.doc = parseHtml(item);
    sub.body = item;
    sub.result = item;
  } else if (item && typeof item === 'object') {
    sub.json = item;
    sub.result = item;
  } else {
    sub.result = item;
  }
  return sub;
}

/**
 * 列表 + 字段批量提取（搜索/发现/目录通用）
 * @param rules 规则对象（含 listKey 指定的列表规则）
 * @param fields 字段名数组（对应 rules 中的同名字段规则）
 */
export async function extractRows(rules, ctx, listKey, fields) {
  // Legado 的 ruleToc 有两个**默认值**：chapterName = "text"、chapterUrl = "href"。
  // 大量书源作者因此只写 chapterList，靠默认值取章节名和链接（52书库② 就是
  // chapterList=".mulu a" 且两个字段全空）。我们不补默认值 → 每行 name/url 都空
  // → 被 mapRows 过滤干净 → 整源「目录 0 章、规则可能失效」。
  // 用 @text / @href（自身属性）而不是 text / href，避免被当成 CSS 标签去子元素里查。
  const eff = Object.assign({}, rules);
  if (listKey === 'chapterList') {
    if (!eff.chapterName) eff.chapterName = '@text';
    if (!eff.chapterUrl) eff.chapterUrl = '@href';
  }
  let items;
  try {
    items = await evalList(eff[listKey], ctx);
  } catch (e) {
    ctx.logs.push(`${listKey} 解析失败: ${e.message}`);
    return [];
  }
  const rows = [];
  for (const item of items) {
    const sub = subContext(ctx, item);
    const row = {};
    for (const f of fields) {
      const fr = eff[f];
      if (!fr) {
        row[f] = null;
        continue;
      }
      try {
        // 走 toFieldText：CSS 规则不写 @text 时取元素文本（Legado 语义），
        // 否则字段会拿到 DOM 元素对象，书名/作者变成「[object HTMLSpanElement]」
        let v = toFieldText(await evalOne(fr, sub));
        // 字段值本身仍是模板（典型：bookUrl 写成 @js 返回
        // '/app/info/bookindex,{"method":"POST","body":"...bID={{$.KEYID}}..."}'）。
        // JS 字符串不解析 {{}}，占位符会一路留在地址里：详情请求带着 "{{$.KEYID}}" 发出去，
        // 站点当然查不到书。这里用当前列表项再渲染一次。
        if (typeof v === 'string' && (v.includes('{{') || v.includes('{$'))) {
          const rr = await renderTpl(v, item, sub);
          const filled = rr == null ? '' : Array.isArray(rr) ? rr[0] : rr;
          if (filled != null && String(filled).trim()) v = String(filled);
        }
        row[f] = v;
      } catch (e) {
        row[f] = null;
        ctx.logs.push(`${f} 解析失败: ${e.message}`);
      }
    }
    rows.push(row);
  }
  return rows;
}

/** 创建规则上下文 */
export function createRuleContext({ doc = null, json = null, body = '', baseUrl = '', source = null, logs = [] }) {
  return { doc, json, body, baseUrl, source, logs, src: {}, vars: {} };
}

/** HTML 字符串 → Document（片段自动包裹） */
export function parseDoc(str) {
  if (!str) return null;
  const s = String(str);
  const wrap = /<html[\s>]/i.test(s) ? s : '<div id="__frag">' + s + '</div>';
  return new DOMParser().parseFromString(wrap, 'text/html');
}

/* ---------------- 搜索列表兜底识别 ---------------- */

/** 导航/功能词：锚文本是这些，说明命中的是菜单不是书名 */
const NAV_WORDS = new RegExp('^(首页|主页|书架|书库|排行|排行榜|分类|全部|更多|更多»|更多>>|登录|注册|退出|'
  + '搜索|搜书|下一页|上一页|尾页|末页|作者|最新章节|查看全部|全部作品|标签|排行版)$');

/**
 * 搜索页「书名 + 详情链接」自动识别 —— bookList 规则一条没中时的最后一道兜底。
 *
 * <p>为什么需要它：站点改版（换个 class、把 table 改成 div）之后，规则就静默 0 条，
 * 用户看到的永远是「列表规则未命中，站点可能已改版」，只能自己猜新规则。
 * 但搜索结果页有个天然特征：**一堆结构相同的块，每块一个书名链接**。
 * 按这个特征找，比按作者写死的 class 找稳得多。
 *
 * <p>判据刻意收紧（宁可返回 null 也不给错结果）：
 * <ul>
 *   <li>同一容器下 ≥3 个结构相似的子块，命中率 ≥60%（列表必须"整齐"）；</li>
 *   <li>href 同域、非根路径、非 #/javascript:；锚文本 2~40 字且不是导航词；</li>
 *   <li><b>必须至少有一条锚文本含关键词</b> —— 否则「搜不到任何书」的结果页
 *       会把侧栏推荐当成搜索结果，比 0 条更误导人。</li>
 * </ul>
 *
 * @returns {{items: {name:string,url:string}[], rule:string, hint:string}|null}
 */
export function autoBookList(doc, keyword, baseUrl) {
  if (!doc || !doc.body) return null;
  const kw = String(keyword || '').trim();
  if (!kw) return null;

  let origin = '';
  try {
    origin = new URL(baseUrl).origin;
  } catch (e) {
    origin = '';
  }

  // 一个锚合不合格：href 可用、同域、非根路径；锚文本 2~40 字且不是导航词
  const okAnchor = (a, holder) => {
    const href = (a.getAttribute('href') || '').trim();
    if (!href || href === '#' || /^(javascript:|mailto:|tel:)/i.test(href)) return null;
    let abs;
    let path;
    try {
      abs = new URL(href, baseUrl || undefined).href;
      path = new URL(abs).pathname;
    } catch (e) {
      return null;
    }
    // 跨域（广告位、友链）一律不要
    if (origin && abs.indexOf(origin) !== 0) return null;
    if (!path || path === '/') return null;
    const name = (a.textContent || '').replace(/\s+/g, ' ').trim();
    if (name.length < 2 || name.length > 40) return null;
    if (NAV_WORDS.test(name)) return null;
    return {
      name,
      url: abs,
      path,
      hit: name.indexOf(kw) >= 0,
      hasCover: !!(holder && holder.querySelector && holder.querySelector('img')),
    };
  };

  /**
   * 在一个条目里找「书名链接」。
   *
   * 旧实现只取 `querySelector('a[href]')` 第一个 —— 这是个隐蔽的坑：封面外面
   * 常包一层 `<a><img></a>`，文本为空 → 整条被判无效 → 好好的结果列表识别出 0 条。
   * 正确做法是把条目里所有锚都过一遍，取第一个文本合格的。
   */
  const bestAnchorIn = (el) => {
    const list = [];
    if (el.matches && el.matches('a[href]')) list.push(el);
    if (el.querySelectorAll) {
      for (const a of el.querySelectorAll('a[href]')) list.push(a);
    }
    for (const a of list) {
      const it = okAnchor(a, el);
      if (it) return it;
    }
    return null;
  };

  let best = null;
  const seen = new Set();
  const anchors = doc.body.querySelectorAll('a[href]');
  const cap = Math.min(anchors.length, 3000);

  for (let i = 0; i < cap; i++) {
    let p = anchors[i].parentElement;
    // 往上爬 1~4 层：`ul>li>a`、`div.bookbox>h4>a`、`tr>td>a` 三种常见结构都能罩住
    for (let d = 0; d < 4 && p; d++, p = p.parentElement) {
      if (seen.has(p)) continue;
      seen.add(p);
      if (seen.size > 2500) break;
      if (!p.children || p.children.length < 2) continue;
      if (p.closest && p.closest('nav,header,footer,aside')) continue;

      const kids = Array.from(p.children);
      const items = [];
      for (const kid of kids) {
        const it = bestAnchorIn(kid);
        if (it) items.push(it);
      }
      const uniq = new Set(items.map((x) => x.url));
      const hits = items.filter((x) => x.hit).length;
      if (items.length < 2 || uniq.size < 2) continue;
      // 2 条也收：搜「三体」这种词，有的站就返回两三本。但 2 条必须**都**含关键词，
      // 否则随便两个侧栏链接就能凑成一组。3 条以上按整齐度判定。
      if (items.length / kids.length < 0.45) continue;
      if (items.length === 2 && hits < 2) continue;
      if (!hits) continue; // 没有一条含关键词 → 不是这个关键词的结果，放弃
      const score = items.length * 2 + uniq.size + hits * 12
        + (items.some((x) => x.hasCover) ? 3 : 0);
      if (!best || score > best.score) best = { score, node: p, items };
    }
  }

  // 兜底二：DOM 不成组时，按「链接路径模式」聚类。
  // 有的站把每个结果块包在各自的 wrapper 里，从锚往上爬永远凑不齐一组；
  // 但它们的详情链接长得一模一样（/book/123/、/txt/456.html），按模式就能捞出来。
  if (!best) {
    const groups = new Map();
    for (const a of doc.body.querySelectorAll('a[href]')) {
      const it = okAnchor(a, a.parentElement);
      if (!it) continue;
      const pat = it.path.replace(/\d+/g, '#');
      if (pat.split('/').filter(Boolean).length < 1) continue;
      if (!groups.has(pat)) groups.set(pat, []);
      groups.get(pat).push(it);
    }
    for (const arr of groups.values()) {
      const uniq = new Set(arr.map((x) => x.url));
      if (uniq.size < 3) continue;
      const hits = arr.filter((x) => x.hit).length;
      if (!hits) continue;
      const score = arr.length * 2 + uniq.size + hits * 12;
      if (!best || score > best.score) best = { score, node: null, items: arr };
    }
  }
  if (!best) return null;

  // 聚类命中给不出可靠选择器，只返回结果、不给规则建议 ——
  // 给一条命中不了的规则，等于让用户白改一次，比不给更糟。
  if (!best.node) {
    return { items: best.items.map((x) => ({ name: x.name, url: x.url })), rule: '', hint: '' };
  }

  // 生成一条「用户复制粘贴就能用」的规则，并且验证它真能命中原节点数量。
  // 生成不出来（命中的是 body 这种没法当选择器的节点）或者验证不过，都仍然返回
  // 识别到的条目 —— 出结果比给建议重要，没建议顶多是用户少一个可复制的规则。
  const noRule = { items: best.items.map((x) => ({ name: x.name, url: x.url })), rule: '', hint: '' };
  const sel = suggestSelector(doc, best.node);
  if (!sel) return noRule;
  let verified = 0;
  try {
    verified = doc.querySelectorAll(sel).length;
  } catch (e) {
    verified = 0;
  }
  if (verified !== 1) return noRule;

  const itemSel = `${sel} > ${best.node.children[0].tagName.toLowerCase()}`;
  return {
    items: best.items.map((x) => ({ name: x.name, url: x.url })),
    rule: `${itemSel}`,
    hint: `把 ruleSearch.bookList 改成 ${itemSel}，书名用 a@text、书籍地址用 a@href`,
  };
}

/** 给节点生成一个尽量稳的 CSS 选择器（id > tag.class > tag:nth-of-type 链） */
function suggestSelector(doc, node) {
  if (!node || node.nodeType !== 1) return '';
  const tag = node.tagName.toLowerCase();
  if (tag === 'body' || tag === 'html') return '';
  if (node.id) return '#' + cssEscape(node.id);
  const cls = (node.getAttribute('class') || '').split(/\s+/).filter(Boolean);
  if (cls.length) {
    const s = tag + '.' + cssEscape(cls[0]);
    try {
      if (doc.querySelectorAll(s).length === 1) return s;
    } catch (e) {
      /* 类名含特殊字符，落到下面按层级拼 */
    }
  }
  // 逐级向上拼，最多 3 层，父级用 id 锚定
  let path = tag;
  let cur = node;
  for (let i = 0; i < 3; i++) {
    const parent = cur.parentElement;
    if (!parent || parent === doc.documentElement) break;
    const ptag = parent.tagName.toLowerCase();
    if (parent.id) {
      path = '#' + cssEscape(parent.id) + ' > ' + path;
      break;
    }
    path = ptag + ' > ' + path;
    cur = parent;
  }
  return path;
}

function cssEscape(s) {
  return String(s).replace(/([^\w-])/g, '\\$1');
}

