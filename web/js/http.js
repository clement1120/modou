/**
 * http.js — 所有网络请求统一走本地代理（绕 CORS + 编码归一）
 * 对应最终架构中 Java 壳代理层；这里由 Node dev server 扮演。
 */

/** @returns {Promise<{ok:boolean,status:number,text:string,finalUrl:string,contentType:string,charset:string}>} */
export async function fetchViaProxy(url, opts = {}) {
  const reqUrl = String(url == null ? '' : url).trim();
  if (!reqUrl) throw new Error('请求地址为空（书源规则未解析出 URL）');
  const q = new URLSearchParams();
  q.set('url', reqUrl);
  if (opts.method && opts.method !== 'GET') q.set('method', opts.method);
  if (opts.headers && Object.keys(opts.headers).length) {
    q.set('header', JSON.stringify(opts.headers));
  }
  if (opts.body != null) q.set('body', String(opts.body));
  // 书源选项里的 charset（如 {'charset':'gbk'}）：部分老站没有 meta/header 声明，必须靠它解码
  if (opts.charset) q.set('charset', String(opts.charset));

  // 浏览器里用相对路径（同源）；Node 测试环境用绝对地址
  const origin = typeof location !== 'undefined' ? location.origin : 'http://127.0.0.1:8080';
  const target = origin + '/proxy?' + q.toString();

  // 简易重试：应对站点限流/抖动（书源场景很常见）。批量体检时关掉重试，
  // 否则一个假死源要等 2 个超时窗口，整批体检会被拖到不可用。
  let lastErr = null;
  // 每个请求超时兜底：书源站点慢/假死时不能让 fetch 永久挂起，
  // 否则正文/搜索会一直"加载中"。opts.timeout 可覆盖（ms）。
  const timeout = Number(opts.timeout) > 0 ? Number(opts.timeout) : 15000;
  const retries = Number.isFinite(Number(opts.retries))
    ? Math.max(0, Math.min(3, Number(opts.retries)))
    : 1;
  for (let attempt = 0; attempt <= retries; attempt++) {
    if (opts.signal && opts.signal.aborted) throw new Error('已取消');
    const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
    let timedOut = false;
    const timer = ctrl ? setTimeout(() => { timedOut = true; ctrl.abort(); }, timeout) : null;
    const onOuterAbort = () => ctrl && ctrl.abort();
    if (opts.signal && ctrl) opts.signal.addEventListener('abort', onOuterAbort, { once: true });
    try {
      const r = await fetch(target, { method: 'GET', signal: ctrl ? ctrl.signal : undefined });
      if (timer) clearTimeout(timer);
      if (opts.signal) opts.signal.removeEventListener('abort', onOuterAbort);
      const j = await r.json();
      if (j.ok === false && j.error) throw new Error(j.error);
      return {
        ok: j.ok,
        status: j.status,
        text: j.text,
        finalUrl: j.finalUrl,
        contentType: j.contentType,
        charset: j.charset,
      };
    } catch (e) {
      if (timer) clearTimeout(timer);
      if (opts.signal) opts.signal.removeEventListener('abort', onOuterAbort);
      lastErr = e;
      // 主动取消 / 已经超时：不再重试，直接把可读原因抛给上层
      if ((opts.signal && opts.signal.aborted) || e.name === 'AbortError') {
        if (timedOut) throw new Error(`请求超时（${Math.round(timeout / 1000)}s）`);
        if (opts.signal && opts.signal.aborted) throw new Error('已取消');
        throw e;
      }
      if (attempt < retries) await new Promise((res) => setTimeout(res, 800));
    }
  }
  throw lastErr instanceof Error ? lastErr : new Error('网络请求失败');
}

/** 解析相对 URL（bookUrl/tocUrl/chapterUrl 经常是相对路径） */
export function resolveUrl(base, u) {
  if (!u) return '';
  u = String(u).trim();
  if (!u) return '';
  u = fixNestedUrl(u);
  if (/^https?:\/\//i.test(u)) return u;
  if (u.startsWith('//')) return 'https:' + u;
  if (!base) return u;
  try {
    return new URL(u, base).href;
  } catch (e) {
    return u;
  }
}

/**
 * 规则里常见的坏拼接：`book-detail/https://host/path`（把绝对地址又拼在路径后面）。
 * 直接 new URL 会得到 `.../book-detail/https://...` 这种 404 地址，这里把内嵌的完整 URL 取出来。
 */
export function fixNestedUrl(u) {
  const s = String(u || '');
  if (!s || /^https?:\/\//i.test(s)) return s;
  const m = s.match(/(https?:\/\/[^\s]+)$/i);
  return m ? m[1] : s;
}
