/**
 * fixture-fetch.mjs — 测试用 fetch 包装：把 /proxy 请求落到本地 fixture 回放
 *
 * - 命中 fixture → 直接回放（零网络，测试确定且快）
 * - 未命中 → 走真实网络，并把响应写入 fixture（下次即可回放）
 * - REFRESH=1 → 忽略 fixture 强制联网刷新（书源失效时用它重建）
 *
 * 供 e2e.mjs 使用；Playwright 冒烟走 dev server 的 MODOU_FIXTURES=1 模式（同一目录）。
 */
import fs from 'node:fs';
import crypto from 'node:crypto';

const DIR = new URL('./fixtures/', import.meta.url);
const REFRESH = process.env.REFRESH === '1';
const realFetch = globalThis.fetch;

/** URL → fixture 文件名（可读前缀 + 短哈希防碰撞） */
export function fixtureName(url) {
  const safe = String(url).replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 100);
  const hash = crypto.createHash('sha1').update(String(url)).digest('hex').slice(0, 8);
  return `${safe}-${hash}.json`;
}

export function fixturePath(url) {
  return new URL(fixtureName(url), DIR);
}

/** 在 import 引擎模块之前调用 */
export function installFixtureFetch() {
  globalThis.fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : input?.url || '';
    let target = null;
    try {
      const m = /^https?:\/\/127\.0\.0\.1:\d+\/proxy\?/.exec(url);
      if (m) target = new URL(url).searchParams.get('url');
    } catch (e) {
      /* 非代理请求，原样放行 */
    }
    if (!target) return realFetch(input, init);

    const fp = fixturePath(target);
    if (!REFRESH && fs.existsSync(fp)) {
      const j = JSON.parse(fs.readFileSync(fp, 'utf8'));
      return new Response(JSON.stringify(j), { status: 200, headers: { 'content-type': 'application/json' } });
    }

    const r = await realFetch(input, init);
    if (r.ok) {
      try {
        const j = await r.clone().json();
        fs.mkdirSync(DIR, { recursive: true });
        fs.writeFileSync(fp, JSON.stringify(j));
        console.log('  [fixture] 已缓存', target);
      } catch (e) {
        /* 非 JSON 响应不缓存 */
      }
    }
    return r;
  };
}
