/**
 * sandbox.js — 书源 JS 规则执行环境
 * 对应 Legado 的 Rhino + JsExtensions（java.* 桥）。
 * 个人自用场景：new Function + 受限全局；webView 等重能力留待 M2。
 */
import { evalOne, evalList, evalOneSync, evalListSync, parseDoc } from './rule.js';

function b64encode(s) {
  return btoa(unescape(encodeURIComponent(s)));
}
function b64decode(s) {
  return decodeURIComponent(escape(atob(s)));
}

/** 同步请求：Legado 的 java.ajax 是同步返回，经本地代理绕 CORS */
/** 书源 JS 里 java.ajax 等同步请求的等待上限（ms）。
 *  同步请求会阻塞主线程，30s 太久——批量校验时一个慢源就能卡住半分钟。 */
const SYNC_TIMEOUT_MS = 12000;

function syncProxy(url, opts = {}) {
  if (typeof XMLHttpRequest === 'undefined') {
    throw new Error('java.ajax 同步请求仅支持浏览器 / WebView 环境');
  }
  const u = String(url == null ? '' : url).trim();
  // 空地址是书源规则失效的典型表现（变量没取到值）。直接给可读错误，
  // 否则会把代理的 "missing url" 当成 JSON 去 parse，最终报错莫名其妙。
  if (!u) throw new Error('java.ajax 地址为空（规则未生成 URL，通常是变量/键值未取到）');
  const q = new URLSearchParams();
  q.set('url', u);
  // 同步 XHR 会阻塞 WebView 主线程，等待上限必须比普通请求短：
  // 按规范同步请求还不能设 xhr.timeout（会抛 InvalidAccessError），所以超时交给代理侧控制。
  q.set('timeout', String(opts.timeout || SYNC_TIMEOUT_MS));
  const method = opts.method ? String(opts.method).toUpperCase() : 'GET';
  if (method !== 'GET') q.set('method', method);
  if (opts.headers && Object.keys(opts.headers).length) q.set('header', JSON.stringify(opts.headers));
  if (opts.charset) q.set('charset', String(opts.charset));
  if (opts.body != null) q.set('body', typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body));
  const xhr = new XMLHttpRequest();
  xhr.open('GET', '/proxy?' + q.toString(), false); // 同步
  try {
    xhr.send(null);
  } catch (e) {
    throw new Error('网络请求失败：' + ((e && e.message) || e));
  }
  if (xhr.status < 200 || xhr.status >= 300) throw new Error('HTTP ' + xhr.status);
  let j = null;
  try {
    j = JSON.parse(xhr.responseText);
  } catch (e) {
    return xhr.responseText;
  }
  if (j && j.ok === false && j.error) throw new Error(j.error);
  return j && typeof j.text === 'string' ? j.text : xhr.responseText;
}

function parseReqOptions(options) {
  if (!options) return {};
  if (typeof options === 'string') {
    try {
      return JSON.parse(options) || {};
    } catch (e) {
      return {};
    }
  }
  return typeof options === 'object' ? options : {};
}

/** Legado 的 JS 里操作的是 Jsoup Element，常用 .text()/.attr()/.select()。
 *  这里给 DOM 元素套一层同名方法，源里 `m[0].text()` 这类写法才不会报错。 */
const trSel = (sel) =>
  String(sel)
    .trim()
    .replace(/\btag\.([\w-]+)/g, '$1')
    .replace(/\bclass\.([\w-]+)/g, '.$1')
    .replace(/\bid\.([\w-]+)/g, '#$1');

/** 把 Document 包成 Jsoup 风格（org.jsoup.Jsoup.parse(...) 返回值的常用方法） */
function wrapDoc(doc) {
  if (!doc) return doc;
  return new Proxy(doc, {
    get(t, k) {
      if (k === 'select') return (sel) => wrapElems(Array.from(t.querySelectorAll(trSel(sel))));
      if (k === 'selectFirst') return (sel) => wrapEl(t.querySelector(trSel(sel)));
      if (k === 'text') return () => ((t.body && t.body.textContent) || '').trim();
      if (k === 'html') return () => (t.body ? t.body.innerHTML : '');
      if (k === 'body') return wrapEl(t.body);
      const v = t[k];
      return typeof v === 'function' ? v.bind(t) : v;
    },
  });
}

/** Elements：Jsoup 的 select 返回集合，带 .text()/.size()/.get()/.first()/.last() */
function wrapElems(arr) {
  const list = (arr || []).map(wrapEl);
  const def = (k, v) => {
    try {
      Object.defineProperty(list, k, { value: v, enumerable: false });
    } catch (e) {
      /* 忽略 */
    }
  };
  def('text', () => list.map((e) => (e && e.text ? e.text() : '')).join(' ').trim());
  def('size', () => list.length);
  def('get', (i) => list[i]);
  def('first', () => list[0]);
  def('last', () => list[list.length - 1]);
  def('html', () => list.map((e) => (e && e.html ? e.html() : '')).join(''));
  return list;
}

function wrapEl(el) {
  if (!el || el.nodeType !== 1) return el;
  const tr = trSel;
  return new Proxy(el, {
    get(t, k) {
      if (k === 'text') return () => (t.textContent || '').trim();
      if (k === 'ownText')
        return () =>
          Array.from(t.childNodes)
            .filter((n) => n.nodeType === 3)
            .map((n) => n.textContent)
            .join('')
            .trim();
      if (k === 'html') return () => t.innerHTML || '';
      if (k === 'outerHtml') return () => t.outerHTML || '';
      if (k === 'attr') return (name) => t.getAttribute(name) || '';
      if (k === 'select') return (sel) => wrapElems(Array.from(t.querySelectorAll(tr(sel))));
      if (k === 'selectFirst') return (sel) => wrapEl(t.querySelector(tr(sel)));
      if (k === 'children') return () => wrapElems(Array.from(t.children));
      if (k === 'parent') return () => wrapEl(t.parentElement);
      if (k === 'tagName') return t.tagName;
      const v = t[k];
      return typeof v === 'function' ? v.bind(t) : v;
    },
  });
}

/** 构造 java.* 桥（每个 JS 规则一次） */
export function createJava(ctx) {
  const store = ctx.src || (ctx.src = {});

  /** 相对地址按 baseUrl 解析（书源里常见相对路径） */
  const absUrl = (u) => {
    const s = String(u || '');
    if (!s) return s;
    if (/^https?:\/\//i.test(s)) return s;
    if (s.startsWith('//')) return 'https:' + s;
    try {
      return new URL(s, ctx.baseUrl || (typeof location !== 'undefined' ? location.href : 'http://127.0.0.1/')).href;
    } catch (e) {
      return s;
    }
  };

  /** java.connect 的迷你连接对象（header/get/post/body/bytes） */
  const connect = (url, headers) => {
    const target = absUrl(url);
    const h = { ...(headers && typeof headers === 'object' ? headers : {}) };
    let method = 'GET';
    let body = null;
    let cache = null;
    const send = () => {
      if (cache != null) return cache;
      cache = syncProxy(target, { method, body, headers: h });
      return cache;
    };
    return {
      header(k, v) {
        if (v === undefined) return h[k];
        h[k] = v;
        return this;
      },
      headers(obj) {
        Object.assign(h, obj || {});
        return this;
      },
      get() {
        method = 'GET';
        return send();
      },
      post(b) {
        method = 'POST';
        body = b;
        return send();
      },
      body() {
        return send();
      },
      bytes() {
        return send();
      },
      /** Legado Connection 的常用别名 */
      raw() {
        return send();
      },
      byteArray() {
        return send();
      },
      responseCode() {
        return 200;
      },
      html() {
        return send();
      },
      url() {
        return target;
      },
    };
  };

  return {
    log: (...a) => ctx.logs.push(a.map((x) => (typeof x === 'object' ? JSON.stringify(x) : String(x))).join(' ')),
    toast: () => {},
    get: (k) => store[k],
    put: (k, v) => {
      store[k] = v;
    },
    /** 在当前上下文上再跑一条规则（同步，Legado 语义） */
    getString: (rule, str) => {
      const sub = str != null ? { ...ctx, body: String(str), doc: null, json: null, result: null } : ctx;
      return evalOneSync(rule, sub);
    },
    getStringList: (rule, str) => {
      const sub = str != null ? { ...ctx, body: String(str), doc: null, json: null, result: null } : ctx;
      return evalListSync(rule, sub);
    },
    /** 在当前上下文跑规则并返回**元素/对象**（Legado java.getElements，爱去小说这类源依赖它） */
    getElements: (rule, str) => {
      const sub = str != null ? { ...ctx, body: String(str), doc: null, json: null, result: null } : ctx;
      const v = evalListSync(rule, sub);
      const arr = Array.isArray(v) ? v : v == null ? [] : [v];
      return wrapElems(arr);
    },
    getElement: (rule, str) => {
      const sub = str != null ? { ...ctx, body: String(str), doc: null, json: null, result: null } : ctx;
      const v = evalOneSync(rule, sub);
      return wrapEl(Array.isArray(v) ? v[0] || null : v);
    },
    /** 时间戳 → "yyyy/MM/dd HH:mm"（书源更新时间的通用格式化） */
    timeFormat: (t) => {
      const d = new Date(Number(t) > 1e11 ? Number(t) : Number(t) * 1000);
      if (isNaN(d.getTime())) return String(t || '');
      const p = (n) => String(n).padStart(2, '0');
      return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
    },
    /** 去标签取纯文本 */
    htmlFormat: (str) =>
      String(str || '')
        .replace(/<(script|style)[\s\S]*?<\/\1>/gi, ' ')
        .replace(/<br\s*\/?>/gi, '\n')
        .replace(/<[^>]+>/g, '')
        .replace(/&nbsp;/g, ' ')
        .replace(/&amp;/g, '&')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .trim(),
    strToBytes: (str) => Array.from(new TextEncoder().encode(String(str || ''))),
    bytesToStr: (arr) => {
      try {
        return new TextDecoder().decode(new Uint8Array(Array.isArray(arr) ? arr : []));
      } catch (e) {
        return '';
      }
    },
    encodeURI: (str, enc) => {
      try {
        return encodeURIComponent(String(str || ''));
      } catch (e) {
        return String(str || '');
      }
    },
    /** cookie 读写：前端拿不到代理的罐，这里做个按 host 的本地存根（够书源自洽使用） */
    getCookie: (url) => {
      try {
        return store['__cookie__' + new URL(absUrl(url)).host] || '';
      } catch (e) {
        return '';
      }
    },
    setCookie: (url, v) => {
      try {
        store['__cookie__' + new URL(absUrl(url)).host] = String(v || '');
      } catch (e) {
        /* 忽略 */
      }
    },
    base64: { encode: b64encode, decode: b64decode },
    base64Encode: b64encode,
    base64Decode: b64decode,
    urlEncode: (s) => encodeURIComponent(String(s)),
    md5Encode: (s) => md5(String(s)),
    md5Encode16: (s) => md5(String(s)).slice(8, 24),
    /** Legado java.ajax：同步返回响应文本；options 可为 JSON 字符串或对象 */
    ajax: (url, options) => {
      const o = parseReqOptions(options);
      return syncProxy(absUrl(url), { method: o.method, body: o.body, headers: o.headers });
    },
    ajaxAll: (urls, options) => {
      const o = parseReqOptions(options);
      return (Array.isArray(urls) ? urls : [urls]).map((u) =>
        syncProxy(absUrl(u), { method: o.method, body: o.body, headers: o.headers })
      );
    },
    connect,
    http: {
      get: (url, headers) => syncProxy(absUrl(url), { headers }),
      post: (url, body, headers) => syncProxy(absUrl(url), { method: 'POST', body, headers }),
      connect: (url, headers) => connect(url, headers),
      ajax: (url, options) => syncProxy(absUrl(url), parseReqOptions(options)),
    },
    webView: () => {
      throw new Error('webView 能力未实现');
    },
    /** MD5（供书源签名鉴权兼容） */
    md5: (s) => md5(String(s)),
  };
}

/**
 * 书源 JS 里要**遮蔽**的全局名字。
 *
 * 背景：书源 JS 来自第三方，却用 `new Function` 执行，默认与页面同权——
 * 能直接碰 window / localStorage / indexedDB，甚至 fetch 绕过代理（丢掉 charset、cookie 处理）。
 * 这里用「同名参数遮蔽」：new Function 的参数会遮住外层同名全局，书源里写 `window.xxx`
 * 拿到的是参数（undefined）而不是真实 window。
 *
 * 说明：这是**收敛能力面**，不是真隔离——JS 里仍能经原型链（({}).constructor.constructor）逃逸到
 * Function。真正的隔离要 Worker，但 ctx.doc 是解析后的文档对象（含 Proxy/DOM 节点），
 * 无法跨 Worker 边界传递，整套搬过去不现实（见 REVIEW-NOTES）。
 * 所以策略是：挡住误用与常规路径，再靠调用点的错误边界保证坏规则不会拖垮解析。
 *
 * 注意：body 首行是 `var result = ...`，书源代码里的 "use strict" 不会成为函数体首条语句，
 * 因此始终是非严格函数——`eval` / `arguments` 作参数名才合法。
 */
const SHADOWED_GLOBALS = [
  'window', 'globalThis', 'self', 'top', 'parent', 'document', // 页面环境
  'localStorage', 'sessionStorage', 'indexedDB', // 本地存储
  'fetch', 'XMLHttpRequest', 'WebSocket', 'EventSource', // 网络：必须走 java.ajax 走代理
  'eval', 'Function', // 动态执行/逃逸
  'alert', 'prompt', 'confirm', 'open', 'print', // 弹窗会阻塞
  'importScripts', 'postMessage', 'close',
];

/** 执行一段 @js 规则代码，返回 result（Legado 语义：代码里读写 result 变量） */
export async function runJs(code, ctx, input) {
  const java = createJava(ctx);
  // Legado 的 cookie 是带方法的对象（cookie.getCookie/removeCookie），不是字符串。
  // 之前传 '' 时，源里 cookie.removeCookie(url) 直接 TypeError → 5 个源「请求地址为空」。
  const cookieObj = {
    getCookie: (u) => java.getCookie(u),
    setCookie: (u, v) => java.setCookie(u, v),
    removeCookie: (u) => java.setCookie(u, ''),
    toString: () => '',
  };
  // org.jsoup.Jsoup.parse(html).select(...) 在书源 JS 里很常见
  const orgObj = { jsoup: { Jsoup: { parse: (html) => wrapDoc(parseDoc(String(html || ''))) } } };
  // Legado 的 @js 语义：代码没写 return 时，**最后一行表达式**就是返回值
  // （书源里 `"https://x/" + result + ".txt"`、`result.match(...)[1]` 这类极多）。
  // 只认显式 return 的话这些规则会静默返回原值 → 字段为空 → 整行丢弃。
  const codeStr = String(code);
  const hasReturn = /(^|[\s;{}])return\b/.test(codeStr);
  let body;
  if (hasReturn) {
    body = `var result = input === undefined ? null : input;\n${codeStr}\n;return result;`;
  } else {
    let lines = codeStr.trim().split('\n');
    let last = (lines.pop() || '').replace(/;$/, '').trim();
    // 单行多语句（`result = xxx; result.length`）：按 ; 拆出最后一个表达式
    if (!lines.length && last.includes(';')) {
      const segs = last.split(';');
      const tail = (segs.pop() || '').trim();
      if (tail) {
        lines = [segs.join(';')];
        last = tail;
      }
    }
    const isExpr = last && !/^(var|let|const|if|for|while|switch|try|\}|\{|\/\/)/.test(last) && !last.endsWith('{') && !last.endsWith('}');
    body = isExpr
      ? `var result = input === undefined ? null : input;\n${lines.join('\n')}\n;return (${last});`
      : `var result = input === undefined ? null : input;\n${codeStr}\n;return result;`;
  }
  // 补齐 Legado 规则里能直接引用的全局：key/page/book/chapter/source/title。
  // 缺了它们，形如 java.ajax(baseUrl + "/search?q=" + key) 的规则会抛 ReferenceError，
  // 或者把 undefined 拼成坏地址，最终表现为「书源全失败」。
  const fn = new Function(
    'java',
    'src',
    'doc',
    'baseUrl',
    'cookie',
    'input',
    'vars',
    'key',
    'page',
    'book',
    'chapter',
    'source',
    'title',
    'org',
    ...SHADOWED_GLOBALS,
    body
  );
  const vars = ctx.vars || {};
  const out = await fn(
    java,
    ctx.src,
    ctx.doc,
    ctx.baseUrl,
    ctx.cookie && ctx.cookie !== '' ? ctx.cookie : cookieObj,
    input,
    vars,
    vars.key != null ? vars.key : '',
    vars.page != null ? vars.page : 1,
    ctx.book || null,
    ctx.chapter || null,
    ctx.source || null,
    ctx.title || '',
    orgObj,
    ...SHADOWED_GLOBALS.map(() => undefined)
  );
  return out;
}

/* --- MD5（供 java.md5 / md5Encode 兼容书源签名，完整 RFC 1321 实现） --- */
function md5(rawStr) {
  const str = String(rawStr);
  function rotateLeft(n, s) {
    return (n << s) | (n >>> (32 - s));
  }
  function add(x, y) {
    const lsw = (x & 0xffff) + (y & 0xffff);
    const msw = (x >> 16) + (y >> 16) + (lsw >> 16);
    return (msw << 16) | (lsw & 0xffff);
  }
  function cmn(q, a, b, x, s, t) {
    a = add(add(a, q), add(x, t));
    return add(rotateLeft(a, s), b);
  }
  function ff(a, b, c, d, x, s, t) {
    return cmn((b & c) | (~b & d), a, b, x, s, t);
  }
  function gg(a, b, c, d, x, s, t) {
    return cmn((b & d) | (c & ~d), a, b, x, s, t);
  }
  function hh(a, b, c, d, x, s, t) {
    return cmn(b ^ c ^ d, a, b, x, s, t);
  }
  function ii(a, b, c, d, x, s, t) {
    return cmn(c ^ (b | ~d), a, b, x, s, t);
  }
  // 以 UTF-8 字节参与运算（书源签名通常基于原始字节）
  const msg = unescape(encodeURIComponent(str));
  const len = msg.length;
  const nblk = ((len + 8) >> 6) + 1;
  const x = new Array(nblk * 16).fill(0);
  for (let i = 0; i < len; i++) x[i >> 2] |= msg.charCodeAt(i) << ((i % 4) * 8);
  x[len >> 2] |= 0x80 << ((len % 4) * 8);
  x[nblk * 16 - 2] = len * 8; // little-endian 32 位长度（文本 < 512MB 足够）
  let a = 1732584193,
    b = -271733879,
    c = -1732584194,
    d = 271733878;
  for (let i = 0; i < x.length; i += 16) {
    const oa = a,
      ob = b,
      oc = c,
      od = d;
    a = ff(a, b, c, d, x[i + 0], 7, -680876936);
    d = ff(d, a, b, c, x[i + 1], 12, -389564586);
    c = ff(c, d, a, b, x[i + 2], 17, 606105819);
    b = ff(b, c, d, a, x[i + 3], 22, -1044525330);
    a = ff(a, b, c, d, x[i + 4], 7, -176418897);
    d = ff(d, a, b, c, x[i + 5], 12, 1200080426);
    c = ff(c, d, a, b, x[i + 6], 17, -1473231341);
    b = ff(b, c, d, a, x[i + 7], 22, -45705983);
    a = ff(a, b, c, d, x[i + 8], 7, 1770035416);
    d = ff(d, a, b, c, x[i + 9], 12, -1958414417);
    c = ff(c, d, a, b, x[i + 10], 17, -42063);
    b = ff(b, c, d, a, x[i + 11], 22, -1990404162);
    a = ff(a, b, c, d, x[i + 12], 7, 1804603682);
    d = ff(d, a, b, c, x[i + 13], 12, -40341101);
    c = ff(c, d, a, b, x[i + 14], 17, -1502002290);
    b = ff(b, c, d, a, x[i + 15], 22, 1236535329);

    a = gg(a, b, c, d, x[i + 1], 5, -165796510);
    d = gg(d, a, b, c, x[i + 6], 9, -1069501632);
    c = gg(c, d, a, b, x[i + 11], 14, 643717713);
    b = gg(b, c, d, a, x[i + 0], 20, -373897302);
    a = gg(a, b, c, d, x[i + 5], 5, -701558691);
    d = gg(d, a, b, c, x[i + 10], 9, 38016083);
    c = gg(c, d, a, b, x[i + 15], 14, -660478335);
    b = gg(b, c, d, a, x[i + 4], 20, -405537848);
    a = gg(a, b, c, d, x[i + 9], 5, 568446438);
    d = gg(d, a, b, c, x[i + 14], 9, -1019803690);
    c = gg(c, d, a, b, x[i + 3], 14, -187363961);
    b = gg(b, c, d, a, x[i + 8], 20, 1163531501);
    a = gg(a, b, c, d, x[i + 13], 5, -1444681467);
    d = gg(d, a, b, c, x[i + 2], 9, -51403784);
    c = gg(c, d, a, b, x[i + 7], 14, 1735328473);
    b = gg(b, c, d, a, x[i + 12], 20, -1926607734);

    a = hh(a, b, c, d, x[i + 5], 4, -378558);
    d = hh(d, a, b, c, x[i + 8], 11, -2022574463);
    c = hh(c, d, a, b, x[i + 11], 16, 1839030562);
    b = hh(b, c, d, a, x[i + 14], 23, -35309556);
    a = hh(a, b, c, d, x[i + 1], 4, -1530992060);
    d = hh(d, a, b, c, x[i + 4], 11, 1272893353);
    c = hh(c, d, a, b, x[i + 7], 16, -155497632);
    b = hh(b, c, d, a, x[i + 10], 23, -1094730640);
    a = hh(a, b, c, d, x[i + 13], 4, 681279174);
    d = hh(d, a, b, c, x[i + 0], 11, -358537222);
    c = hh(c, d, a, b, x[i + 3], 16, -722521979);
    b = hh(b, c, d, a, x[i + 6], 23, 76029189);
    a = hh(a, b, c, d, x[i + 9], 4, -640364487);
    d = hh(d, a, b, c, x[i + 12], 11, -421815835);
    c = hh(c, d, a, b, x[i + 15], 16, 530742520);
    b = hh(b, c, d, a, x[i + 2], 23, -995338651);

    a = ii(a, b, c, d, x[i + 0], 6, -198630844);
    d = ii(d, a, b, c, x[i + 7], 10, 1126891415);
    c = ii(c, d, a, b, x[i + 14], 15, -1416354905);
    b = ii(b, c, d, a, x[i + 5], 21, -57434055);
    a = ii(a, b, c, d, x[i + 12], 6, 1700485571);
    d = ii(d, a, b, c, x[i + 3], 10, -1894986606);
    c = ii(c, d, a, b, x[i + 10], 15, -1051523);
    b = ii(b, c, d, a, x[i + 1], 21, -2054922799);
    a = ii(a, b, c, d, x[i + 8], 6, 1873313359);
    d = ii(d, a, b, c, x[i + 15], 10, -30611744);
    c = ii(c, d, a, b, x[i + 6], 15, -1560198380);
    b = ii(b, c, d, a, x[i + 13], 21, 1309151649);
    a = ii(a, b, c, d, x[i + 4], 6, -145523070);
    d = ii(d, a, b, c, x[i + 11], 10, -1120210379);
    c = ii(c, d, a, b, x[i + 2], 15, 718787259);
    b = ii(b, c, d, a, x[i + 9], 21, -343485551);

    a = add(a, oa);
    b = add(b, ob);
    c = add(c, oc);
    d = add(d, od);
  }
  const hex = (n) => {
    let s = '';
    for (let j = 0; j < 4; j++) s += ((n >> (j * 8)) & 0xff).toString(16).padStart(2, '0');
    return s;
  };
  return hex(a) + hex(b) + hex(c) + hex(d);
}
