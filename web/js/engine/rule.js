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
  // "@onclick" / "@data-id"：没有选择器，取「当前元素自身」的属性（列表项常见写法）
  if (/^@[A-Za-z_][\w-]*$/.test(base.trim())) {
    attr = base.trim().slice(1);
    base = '';
    selfAttr = true;
  }
  if (!selfAttr && segType(base.trim()) === 'css') {
    const at = base.lastIndexOf('@');
    // 后缀可为「属性名」，也兼容 Legado 的 @attr.0 / @attr[0]（属性 + 索引）
    const m = at > 0 ? base.slice(at + 1).match(/^([A-Za-z_][\w-]*)(?:\.(\d+)|\[(\d+)\])?$/) : null;
    if (m) {
      attr = m[1];
      if (m[2] != null) idx = Number(m[2]);
      else if (m[3] != null) idx = Number(m[3]);
      base = base.slice(0, at);
    }
  }
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
        sel = sel.replace(/!(-?\d+)/g, (_, n) => {
          excl.push(Number(n));
          return '';
        });
        const m = /^(.*?)\.(-?\d+)$/.exec(sel.trim());
        if (m && m[1]) {
          sel = m[1];
          sidx = Number(m[2]);
        }
        return { sel: sel.trim(), idx: sidx, excl };
      });
      return {
        expr: '',
        chain: steps,
        attr: lastIsSubsel ? attr : lastPart,
        idx,
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
  return { expr: base.trim(), attr, idx, elemIdx, selfAttr, wholeDoc, replaces, sep, reverse, puts, gets, jsAfter };
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
  const re = new RegExp(pattern, 'g');
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
  const one = (v) => {
    if (typeof v !== 'string') return v;
    let s = v;
    for (const [pat, rep] of replaces) {
      try {
        s = s.replace(new RegExp(pat, 'g'), rep);
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

/* ---------- 单段执行 ---------- */

async function evalSegment(seg, input, ctx) {
  let { expr } = seg;
  expr = expr.replace(/\{\{baseUrl\}\}/g, ctx.baseUrl || '');
  const type = segType(expr, ctx);

  // 模板字符串：{{}} 与文本混排，逐段求值拼接；尾部 @js: 以渲染结果为 result 继续跑
  if (seg.tpl) {
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
          val = val.replace(new RegExp(pat, 'g'), rep);
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
    if (seg.reverse) outT = [...outT].reverse();
    return outT;
  }

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
        out = await runJs(code, ctx, input != null ? input : ctx.result != null ? ctx.result : ctx.body);
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

/** 求值整条规则（可能多段链式） */
/** 按顶层 || 拆分（{{}} 与 @js 段内的 || 不切） */
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
  // 「规则1||规则2」：依次尝试，取第一个有结果（Legado 的多写法兜底）
  if (ruleStr.includes('||') && !ruleStr.includes('@js:')) {
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
  let items;
  try {
    items = await evalList(rules[listKey], ctx);
  } catch (e) {
    ctx.logs.push(`${listKey} 解析失败: ${e.message}`);
    return [];
  }
  const rows = [];
  for (const item of items) {
    const sub = subContext(ctx, item);
    const row = {};
    for (const f of fields) {
      const fr = rules[f];
      if (!fr) {
        row[f] = null;
        continue;
      }
      try {
        row[f] = await evalOne(fr, sub);
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
