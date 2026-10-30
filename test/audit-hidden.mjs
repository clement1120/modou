/**
 * 运行时审计：扫出「标记了 hidden 但仍在渲染」的元素。
 *
 * 背景：CSS 里的 display 会盖掉 UA 的 [hidden]{display:none}，
 * 于是「已隐藏」的元素照样占位 —— 曾导致书源页顶部一条空橙带、底部空栏压住标签栏。
 * 这里遍历各页面把同类隐患一次性揪出来，避免再靠肉眼发现。
 */
import { createRequire } from 'node:module';
const require = createRequire('/root/.nvm/versions/node/v22.13.1/lib/node_modules/');
const { chromium } = require('playwright');
const BASE = 'http://127.0.0.1:8080';

const browser = await chromium.launch({});
const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
const errs = [];
page.on('pageerror', (e) => errs.push('pageerror: ' + e.message));
page.on('console', (m) => {
  if (m.type() === 'error') errs.push('console: ' + m.text());
});

await page.goto(BASE, { waitUntil: 'networkidle' }).catch(() => {});
// 载入示例书源，让书源页有数据
await page.click('.tabbar a[href="#/sources"]').catch(() => {});
await page.waitForSelector('#btn-menu', { timeout: 8000 }).catch(() => {});
await page.click('#btn-menu').catch(() => {});
await page.waitForSelector('.sheet .opt-item', { timeout: 5000 }).catch(() => {});
await page.click('#mi-sample').catch(() => {});
await page.waitForTimeout(700);

/** 扫当前页：hidden 却仍在渲染 + 横向溢出 + 明显越界 */
const scan = () =>
  page.evaluate(() => {
    const bad = [];
    document.querySelectorAll('[hidden]').forEach((el) => {
      const cs = getComputedStyle(el);
      if (cs.display !== 'none') {
        const r = el.getBoundingClientRect();
        const id = el.id ? '#' + el.id : '';
        bad.push({
          sel: el.tagName.toLowerCase() + id + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : ''),
          display: cs.display,
          h: Math.round(r.height),
          y: Math.round(r.y),
        });
      }
    });
    // 横向溢出（分页模式除外）
    const overflow = document.documentElement.scrollWidth > window.innerWidth + 1
      ? document.documentElement.scrollWidth - window.innerWidth
      : 0;
    return { bad, overflow, hash: location.hash };
  });

const routes = ['#/shelf', '#/search', '#/sources', '#/settings'];
const results = [];
for (const r of routes) {
  await page.evaluate((h) => { location.hash = h; }, r).catch(() => {});
  await page.waitForTimeout(500);
  results.push(await scan());
}

console.log('===== hidden 失效扫描 =====');
let total = 0;
for (const r of results) {
  const label = r.hash || '(空)';
  if (!r.bad.length) {
    console.log(`  ✓ ${label} 无隐藏失效元素` + (r.overflow ? `（横向溢出 ${r.overflow}px）` : ''));
  } else {
    total += r.bad.length;
    console.log(`  ✗ ${label}：${r.bad.length} 个` + (r.overflow ? `（横向溢出 ${r.overflow}px）` : ''));
    r.bad.forEach((b) => console.log(`      ${b.sel} display=${b.display} h=${b.h} y=${b.y}`));
  }
}
console.log(`合计：${total} 个隐藏失效元素`);

// 弹层样态也要扫（sheet / dialog 打开时内部可能隐藏失效）
await page.evaluate(() => { location.hash = '#/sources'; });
await page.waitForTimeout(400);
await page.click('#btn-menu').catch(() => {});
await page.waitForSelector('.sheet .opt-item', { timeout: 5000 }).catch(() => {});
const inSheet = await scan();
console.log(`  ┇菜单打开时：${inSheet.bad.length ? inSheet.bad.map((b) => b.sel + '(' + b.display + ')').join(', ') : '无'}`);
await page.locator('.sheet .dialog-x, .sheet-overlay').first().click().catch(() => {});
await page.waitForTimeout(300);

console.log('\n===== 运行期错误 =====');
console.log(errs.length ? errs.slice(0, 10).join('\n') : '  无');

await browser.close();
