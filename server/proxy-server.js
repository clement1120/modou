#!/usr/bin/env node
/**
 * 墨读 · 本地开发服务器
 * 双重职责（对应最终架构里 Android Java 壳的角色）：
 *   1. /proxy  HTTP 代理：转发请求、注入 UA/Header、处理 GBK 等编码，统一返回 UTF-8 JSON
 *   2. 静态服务 web/（SPA 走 hash 路由，无需 history fallback）
 *
 * 运行：npm start   （默认 8080，PORT=xxxx 可改）
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 8080);
const WEB_ROOT = path.join(__dirname, '..', 'web');
// MODOU_FIXTURES=1：/proxy 优先回放 test/fixtures/（Playwright 冒烟用，确定且快）；
// 未命中时仍走真实网络并顺手写入 fixture
const FIXTURES = process.env.MODOU_FIXTURES === '1';
const FIXTURE_DIR = path.join(__dirname, '..', 'test', 'fixtures');

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
};

const DEFAULT_UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36';

/**
 * Cookie 罐（按 host 存）：书源大量依赖"先访问首页拿 cookie，再搜索"的会话流程，
 * 之前每次请求都裸发，站点直接 403 / 返回空页 → 表现为"源失效"。
 * 同时给没有显式 Referer 的请求补同域 Referer（不少站点的搜索接口校验来源）。
 */
const cookieJar = new Map(); // host → "k=v; k2=v2"
function cookieHeaderFor(host) {
  return cookieJar.get(host) || '';
}
function mergeSetCookie(host, list) {
  if (!list || !list.length) return;
  const cur = new Map();
  for (const kv of (cookieJar.get(host) || '').split(/;\s*/)) {
    const i = kv.indexOf('=');
    if (i > 0) cur.set(kv.slice(0, i).trim(), kv.slice(i + 1).trim());
  }
  for (const sc of list) {
    const first = String(sc).split(';')[0];
    const i = first.indexOf('=');
    if (i <= 0) continue;
    const k = first.slice(0, i).trim();
    const v = first.slice(i + 1).trim();
    if (v === '' || /expires=Thu, 01 Jan 1970/i.test(String(sc))) cur.delete(k);
    else cur.set(k, v);
  }
  if (cur.size) cookieJar.set(host, [...cur].map(([k, v]) => `${k}=${v}`).join('; '));
}

/** 编码嗅探：响应头 → HTML meta → BOM → 默认 utf-8 */
function sniffCharset(buf, headerCt) {
  const m = /charset=["']?([\w-]+)/i.exec(headerCt || '');
  if (m) return m[1].toLowerCase();
  const head = buf.slice(0, 4096).toString('latin1');
  const m2 = /charset=["']?([\w-]+)/i.exec(head);
  if (m2) return m2[1].toLowerCase();
  if (buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) return 'utf-8';
  return 'utf-8';
}

/**
 * 解压响应体：书源站大量直接返回 gzip 字节（甚至无视 Accept-Encoding），
 * 不解压就当文本解码 → 页面全是乱码 → bookList 命中 0 条（"书源全失效"的头号原因）。
 * 既看 content-encoding，也看压缩 magic（0x1f8b / 0x78）兜底。
 */
function maybeDecompress(buf, contentEncoding) {
  const ce = String(contentEncoding || '').toLowerCase();
  try {
    if (buf.length > 2 && buf[0] === 0x1f && buf[1] === 0x8b) return zlib.gunzipSync(buf);
    if (ce.includes('br') && buf.length) return zlib.brotliDecompressSync(buf);
    if (ce.includes('deflate') || (buf.length > 2 && (buf[0] & 0x0f) === 8 && (buf[0] & 0x80) === 0 && buf[0] === 0x78)) {
      return zlib.inflateSync(buf);
    }
  } catch (e) {
    return buf; // 解压失败按原始字节处理，不能把请求搞挂
  }
  return buf;
}

/**
 * 判断字节流是否「像 UTF-8」：前 64KB 严格校验 + 整页替换字符比例兜底。
 * 整页 fatal 判定太脆——一个坏字节就否决整页，反而按 GBK 解出天书。
 */
function isStrictUtf8(buf) {
  if (!buf || !buf.length) return true;
  const probe = buf.subarray(0, Math.min(buf.length, 64 * 1024));
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(probe);
  } catch (e) {
    return false;
  }
  const text = buf.toString('utf8');
  let bad = 0;
  for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 0xfffd) bad++;
  return bad * 200 < Math.max(1, text.length);
}

function decodeBody(buf, charset) {
  const declared = String(charset || 'utf-8').toLowerCase();
  try {
    // 站点常把 UTF-8 页面声明成 gbk（或反着来）：声明的编码解出来是乱码。
    // GBK 中文的字节流几乎不可能整体是合法 UTF-8，所以「合法 UTF-8 + 声明非 utf-8」→ 按 UTF-8 解。
    // 报告里「搜索"鎴戠殑"结果」这类整页乱码→bookList 0 条，就是这条规则救的。
    if (!/^utf-?8/.test(declared) && isStrictUtf8(buf)) return buf.toString('utf8');
    if (/^utf-?8$/.test(declared)) return buf.toString('utf8');
    return new TextDecoder(declared).decode(buf);
  } catch (e) {
    return buf.toString('utf8');
  }
}

/** URL → fixture 文件名（与 test/fixture-fetch.mjs 保持一致的散列规则） */
function fixtureName(url) {
  const safe = String(url).replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 100);
  const hash = crypto.createHash('sha1').update(String(url)).digest('hex').slice(0, 8);
  return `${safe}-${hash}.json`;
}

async function handleProxy(res, u) {
  const target = u.searchParams.get('url');
  if (!target) {
    res.writeHead(400, { 'content-type': 'text/plain; charset=utf-8' });
    return res.end('missing url');
  }
  const method = (u.searchParams.get('method') || 'GET').toUpperCase();
  let headers = {};
  try {
    headers = JSON.parse(u.searchParams.get('header') || '{}');
  } catch (e) {
    /* 忽略坏 header */
  }
  const body = u.searchParams.get('body');
  const forceCharset = u.searchParams.get('charset');
  // 超时可配：书源 JS 里的 java.ajax 是**同步**请求（阻塞 WebView 主线程），
  // 等待太久等于把 App 卡住，所以调用方可以传更短的 timeout；这里只做上限/下限约束，
  // 避免外部传入离谱值（0 会立刻 abort，几分钟会让同步请求一直挂着）。
  const rawTimeout = Number(u.searchParams.get('timeout'));
  const timeout = Number.isFinite(rawTimeout) && rawTimeout > 0
    ? Math.min(30000, Math.max(1000, rawTimeout))
    : 30000;

  // fixture 回放
  if (FIXTURES) {
    const fp = path.join(FIXTURE_DIR, fixtureName(target));
    if (fs.existsSync(fp)) {
      const j = JSON.parse(fs.readFileSync(fp, 'utf8'));
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify(j));
    }
  }

  // 会话补齐：host cookie / 同域 Referer（书源显式指定时以书源为准）
  let host = '';
  let origin = '';
  try {
    const tu = new URL(target);
    host = tu.host;
    origin = tu.origin + '/';
  } catch (e) {
    /* 非法 URL 交给 fetch 报错 */
  }
  const hasHeader = (k) => Object.keys(headers).some((h) => h.toLowerCase() === k);
  const hdr = { 'user-agent': DEFAULT_UA, ...headers };
  if (host && !hasHeader('cookie')) {
    const ck = cookieHeaderFor(host);
    if (ck) hdr.cookie = ck;
  }
  if (origin && !hasHeader('referer')) hdr.referer = origin;

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  try {
    const r = await fetch(target, {
      method,
      headers: hdr,
      body: method === 'GET' || method === 'HEAD' ? undefined : body || null,
      redirect: 'follow',
      signal: ctrl.signal,
    });
    // 记下响应里的 Set-Cookie，供后续请求复用（登录态/防爬 token）
    if (host) {
      const sc = typeof r.headers.getSetCookie === 'function' ? r.headers.getSetCookie() : [];
      mergeSetCookie(host, sc);
    }
    const rawBuf = Buffer.from(await r.arrayBuffer());
    const buf = maybeDecompress(rawBuf, r.headers.get('content-encoding'));
    const ct = r.headers.get('content-type') || '';
    // 书源规则指定的编码优先（GBK 老站常常没有 meta 声明，嗅探会退化成 utf-8）
    const charset = forceCharset ? String(forceCharset).toLowerCase() : sniffCharset(buf, ct);
    const payload = {
      ok: r.ok,
      status: r.status,
      finalUrl: r.url,
      contentType: ct,
      charset,
      text: decodeBody(buf, charset),
    };
    if (FIXTURES) {
      try {
        fs.mkdirSync(FIXTURE_DIR, { recursive: true });
        fs.writeFileSync(path.join(FIXTURE_DIR, fixtureName(target)), JSON.stringify(payload));
      } catch (e) {
        /* 写 fixture 失败不影响响应 */
      }
    }
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(payload));
  } catch (e) {
    // abort 就是超时：写成可读文案，别让「This operation was aborted」出现在书源报错里
    const aborted = !!(e && (e.name === 'AbortError' || /abort/i.test(String(e.message || e))));
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(
      JSON.stringify({
        ok: false,
        error: aborted ? `请求超时（${timeout}ms）` : String((e && e.message) || e),
      })
    );
  } finally {
    clearTimeout(timer);
  }
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, `http://127.0.0.1:${PORT}`);
  if (u.pathname === '/proxy') return handleProxy(res, u);

  let p = decodeURIComponent(u.pathname);
  if (p === '/') p = '/index.html';
  const file = path.normalize(path.join(WEB_ROOT, p));
  if (!file.startsWith(WEB_ROOT)) {
    res.writeHead(403);
    return res.end();
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      return res.end('not found');
    }
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`墨读 dev server → http://127.0.0.1:${PORT}`);
});
