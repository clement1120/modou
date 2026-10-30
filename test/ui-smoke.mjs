/**
 * UI 冒烟测试（Playwright + Chromium，无头）
 * 前置：MODOU_FIXTURES=1 npm start（fixture 回放模式，确定且快）
 * 流程：启动 → 载入示例书源 → 校验书源 → 搜书 → 打开书籍 → 阅读 →
 *       键盘翻章/主题(4套)/行距/字号/边距/亮度(排版面板)/TTS+定时 → 下一章 →
 *       M5：目录抽屉跳章 / 书签 / 正文搜索 / 自动阅读 → 书架进度 → 设置（缓存/净化/统计/导入入口）→
 *       移出清缓存 → 移动视口：底部导航 + 本地导入 TXT(GBK) → 阅读 → 分页全套 →
 *       书架分组（管理/新建/移动）
 * 截图输出到 /tmp/ui-*.png
 */
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const BASE = process.env.MODOU_BASE || 'http://127.0.0.1:8080';
let pass = 0;
let fail = 0;
const check = (name, ok, detail = '') => {
  if (ok) {
    pass++;
    console.log('  ✓', name);
  } else {
    fail++;
    console.log('  ✗', name, '—', detail);
  }
};

const browser = await chromium.launch(process.env.PW_CHROME ? { executablePath: process.env.PW_CHROME } : {});
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const errors = [];
const extFlakes = []; // 源站直连图片（<img> 不走 /proxy，fixture 不覆盖）的网络波动
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('requestfailed', (r) => {
  const u = r.url();
  if (u.startsWith('http://127.0.0.1')) errors.push('app resource failed: ' + u);
  else extFlakes.push((r.failure()?.errorText || '?') + ' ' + u);
});
page.on('console', (m) => {
  if (m.type() !== 'error') return;
  // 外部图片重置会伴随一条无 URL 的 console 错误；存在外部波动时不计失败
  if (/Failed to load resource/.test(m.text()) && extFlakes.length > 0) return;
  errors.push('console: ' + m.text());
});

await page.goto(BASE, { waitUntil: 'networkidle' });
// 自绘确认弹层（替代 window.confirm）：点击确定按钮
const confirmOk = async () => {
  const btn = page.locator('.dialog.confirm [data-ok]');
  await btn.waitFor({ timeout: 6000 });
  await btn.click();
  await page.waitForSelector('.dialog.confirm', { state: 'detached', timeout: 6000 }).catch(() => {});
};
// 阅读页默认沉浸（chrome 隐藏），点正文中部唤出顶栏/工具坞后再操作
const ensureChrome = async () => {
  const hidden = await page.evaluate(() => document.querySelector('#view')?.classList.contains('chrome-hidden'));
  if (!hidden) return;
  const box = await page.locator('#rd-stage').boundingBox();
  if (!box) return;
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await page
    .waitForFunction(() => !document.querySelector('#view').classList.contains('chrome-hidden'), null, { timeout: 3000 })
    .catch(() => {});
};
/* 网页版书架与网页版本地导入已整页原生化（v0.6.104/0.6.105），Web 冒烟测试无法再经书架/
   本地导入取得书。搜索源（gutenberg）的 fixture 只覆盖 Pride 前几章，_全书检索 / 跨章分页_ 会去
   抓 55 个未缓存章节而卡死，不适合当通用测试书。
   因此这里在网页端 IndexedDB 直接种一本「全章已缓存」的合成书（数据层直接写入，等价原本地导入
   上书架的结果），内容确定性可控，全书检索 / 分页 / 跨章均在毫秒级完成。toc-window（260 章
   虚拟目录）依赖专门的本地导入合成书，见下方「原生化」标注跳过。 */
let __seedId = {};
const seedBook = async (tag = '') => {
  if (__seedId[tag]) return __seedId[tag];
  const sourceUrl = 'local://seed' + tag;
  const bookUrl = 'seed-book' + tag;
  const bid = sourceUrl + '||' + bookUrl;
  const N = 4;
  const chapters = [];
  const contents = [];
  for (let i = 0; i < N; i++) {
    chapters.push({ name: `第${i + 1}章 测试章节${i + 1}`, url: `seed://c${i}` });
    const paras = [`第${i + 1}章 测试章节${i + 1}`];
    // 20 段常规正文：撑开多页，每段带「茶铺」保证全书检索必中
    for (let k = 0; k < 20; k++) {
      paras.push(`这是第${i + 1}章的第${k + 1}段正文，用于撑开分页排版。茶铺的伙计忙着招呼客人，茶铺里人声鼎沸，热气升腾，窗外的雨丝斜斜落下。`);
    }
    // 一段超长正文：跨页时会被切成「续段 .cont」，验证长段填页
    paras.push('这一段特别长，用来触发分页时的续段切分。'.repeat(40) + '茶铺。');
    if (i === 1) paras.push('这一章单独出现一个关键词，用于验证全书检索能跨章定位。');
    contents.push(paras.map((t) => `<p>${t}</p>`).join(''));
  }
  const book = {
    id: bid, sourceUrl, sourceName: '本地导入', name: '测试合成书', author: '测试',
    kind: '本地', bookUrl, tocUrl: '', coverUrl: '', intro: '',
    lastChapter: chapters[N - 1].name, group: '默认',
    chapters, addedAt: Date.now(), updatedAt: Date.now(),
  };
  await page.evaluate(async ({ book, contents, bid }) => {
    const app = await import('/js/ui/app.js');
    const { db } = await import('/js/db.js');
    await app.saveBook(book, book.chapters); // 写 books 表并 reloadBooks（进内存 state）
    for (let i = 0; i < contents.length; i++) {
      await db.put('chapters', { id: bid + '||' + i, content: contents[i], cachedAt: Date.now() });
    }
  }, { book, contents, bid });
  __seedId[tag] = bid;
  return bid;
};
// 种书（幂等）并直接进阅读第 0 章
const openSeededBook = async (tag = '') => {
  const bid = await seedBook(tag);
  await page.evaluate((id) => { location.hash = `#/read/${encodeURIComponent(id)}/0`; }, bid);
  await page.waitForSelector('.rd-shell', { timeout: 20000 });
  await page.waitForSelector('.reader-body .content, .reader-body .page', { timeout: 20000 });
};
// 切到左右翻页（分页）模式，排版类用例前置
const ensurePaged = async () => {
  if (await page.evaluate(() => document.body.classList.contains('reader-paged'))) return;
  await ensureChrome();
  await page.click('#r-typo');
  await page.waitForSelector('.sheet .seg', { timeout: 5000 });
  await page.locator('.sheet .seg button', { hasText: '左右翻页' }).last().click();
  await page.waitForSelector('body.reader-paged', { timeout: 5000 });
  await page.locator('.sheet .dialog-x').click().catch(() => {});
  await page.waitForFunction(() => !document.querySelector('.sheet'), null, { timeout: 5000 });
};
check('页面加载（侧边栏出现）', await page.locator('.sidebar').isVisible());
check('纸墨令牌：朱砂强调色', await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--acc').trim() === '#AE4A2C'));
check('纸墨令牌：侧边栏实底无玻璃拟态', await page.evaluate(() => {
  const s = document.querySelector('.sidebar');
  return !!s && getComputedStyle(s).backdropFilter === 'none' && getComputedStyle(s).backgroundColor !== 'rgba(0, 0, 0, 0)';
}));
await page.screenshot({ path: '/tmp/ui-1-shelf.png' });

/* 书源管理（v0.6.47 改版：入口收敛到 ┇ 菜单，卡片组件化） */
await page.click('.sidebar nav a[href="#/sources"]');
await page.waitForSelector('#btn-menu');
/* 顶栏不许再平铺一堆按钮：主操作只保留 搜索 与 ┇ */
check('书源页 v0.6.47：顶栏收敛为 🔍 + ┇（无平铺按钮）', await page.evaluate(() => {
  const has = (s) => !!document.querySelector(s);
  return has('#btn-menu') && has('#btn-search') && !has('#btn-import') && !has('#btn-checkall') && !has('#btn-filter') && !has('#btn-export');
}));

// ┇ 菜单：导入 / 校验全部 / 检查重复 / 导出全部 / 载入示例
const srcMenu = async (id) => {
  await page.click('#btn-menu');
  await page.waitForSelector('.sheet .opt-item', { timeout: 5000 });
  await page.click('#' + id);
};
const rowBox = async (sel) => (await page.locator(sel).first().boundingBox());
/** 左滑露出单条操作（卡片内已无 mini 按钮） */
const swipeRowLeft = async (sel) => {
  const b = await rowBox(sel);
  await page.mouse.move(b.x + b.width * 0.72, b.y + b.height / 2);
  await page.mouse.down();
  await page.mouse.move(b.x + b.width * 0.72 - 150, b.y + b.height / 2, { steps: 10 });
  await page.mouse.up();
  await page.waitForTimeout(320);
};
/** 长按进入多选态 */
const longPressRow = async (sel) => {
  const b = await rowBox(sel);
  await page.mouse.move(b.x + b.width * 0.32, b.y + b.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(600);
  await page.mouse.up();
  await page.waitForTimeout(200);
};
/** 点卡片开详情抽屉 */
const openRowDetail = async (sel) => {
  const b = await rowBox(sel);
  await page.mouse.click(b.x + b.width * 0.5, b.y + b.height / 2);
  await page.waitForSelector('.src-drawer.open', { timeout: 5000 });
};

await srcMenu('mi-sample');
await page.waitForFunction(() => document.querySelectorAll('.src-card').length > 0, null, { timeout: 15000 });
const srcCount = await page.locator('.src-card').count();
check('载入示例书源（2 个）', srcCount === 2, `got ${srcCount}`);
/* 卡片不许再堆 mini 按钮与内联原因 */
check('书源页 v0.6.47：卡片内无 mini 按钮与内联原因', await page.evaluate(() => {
  const c = document.querySelector('.src-card');
  return !!c && !c.querySelector('.src-ops') && !c.querySelector('.src-why') && !c.querySelector('[data-act=edit]') && !c.querySelector('[data-act=group]');
}));
/* 状态用色点表达，不是一整串文字标签 */
check('书源页 v0.6.47：状态用色点（.sdot）', (await page.locator('.src-card .sdot').count()) >= 1);
/* 回归：浏览态下「选择条 / 底部操作栏」必须真的不渲染。
   display:flex 会盖掉 UA 的 [hidden]{display:none} —— 曾导致顶部一条空橙带 + 底部空栏压住标签栏。 */
check('书源页 v0.6.47：浏览态不显示选择条/浮动操作栏（不遮挡标签栏）', await page.evaluate(() => {
  const none = (s) => {
    const e = document.querySelector(s);
    return !e || getComputedStyle(e).display === 'none';
  };
  return none('#src-selbar') && none('#src-fab');
}));

// 导入书源面板：三个选项都可点开（回归：曾误用 overlay.close 导致全部无反应）
await srcMenu('mi-import');
await page.waitForSelector('.sheet .opt-item');
const optCount = await page.locator('.sheet .opt-item').count();
await page.locator('.sheet .opt-item').nth(2).click(); // 粘贴 JSON
await page.waitForSelector('.dialog', { timeout: 5000 });
check('导入书源面板：3 个选项可点开弹窗', optCount === 3 && (await page.locator('.dialog').isVisible()));
await page.locator('.dialog .dialog-x').click();
await page.screenshot({ path: '/tmp/ui-2-sources.png' });

/* v0.6.33：重复书源检测（同名/同地址）+ 一键清理 */
{
  const mk = (name) =>
    JSON.stringify([
      {
        bookSourceName: name,
        bookSourceUrl: 'https://dup.example.com',
        searchUrl: 'https://dup.example.com/s?q={{key}}',
        ruleSearch: { bookList: 'class.item', name: 'tag.a.0@text', bookUrl: 'tag.a.0@href' },
      },
    ]);
  for (const n of ['重复源A', '重复源B']) {
    await srcMenu('mi-import');
    await page.waitForSelector('.sheet .opt-item');
    await page.locator('.sheet .opt-item').nth(2).click();
    await page.waitForSelector('.dialog .json-input');
    await page.fill('.dialog .json-input', mk(n));
    await page.locator('.dialog .btn.primary', { hasText: '导入' }).click();
    await page.waitForFunction(
      (nm) => [...document.querySelectorAll('.src-card .src-name')].some((e) => (e.textContent || '').includes(nm)),
      n,
      { timeout: 8000 }
    );
  }
  check('查重：同地址双源已导入', (await page.locator('.src-card[data-url="https://dup.example.com"]').count()) === 2);
  await srcMenu('mi-dedup');
  await page.waitForSelector('.dedup-body', { timeout: 5000 });
  const dedupText = (await page.locator('.dedup-body').textContent()) || '';
  check('查重：识别出重复组并给出保留/删除清单', /发现 1 组重复/.test(dedupText) && /保留：/.test(dedupText) && /删除：/.test(dedupText), dedupText.slice(0, 120));
  await page.locator('.sheet .btn.primary', { hasText: '删除 1 个重复源' }).click();
  await page.waitForSelector('.dialog.confirm', { timeout: 5000 });
  await page.locator('.dialog .btn.primary').click();
  await page.waitForFunction(
    () => document.querySelectorAll('.src-card[data-url="https://dup.example.com"]').length === 1,
    null,
    { timeout: 8000 }
  );
  check('查重：一键删除后每组只剩 1 个', true);
  // 清理测试源：左滑露出「删除」再点（单条操作已不在卡片内平铺）
  await swipeRowLeft('.src-card[data-url="https://dup.example.com"]');
  await page.locator('.src-card[data-url="https://dup.example.com"] [data-act=del]').click();
  await page.waitForSelector('.dialog.confirm');
  await page.locator('.dialog .btn.primary').click();
  await page.waitForFunction(() => document.querySelectorAll('.src-card[data-url="https://dup.example.com"]').length === 0, null, { timeout: 8000 });
}

/* 详情抽屉：点卡片打开，含规则摘要（失效原因不再 inline 撑破列表） */
await openRowDetail('.src-card[data-url*="gutenberg"]');
{
  const d = await page.evaluate(() => {
    const el = document.querySelector('.src-drawer.open');
    return { has: !!el, txt: (el && el.textContent) || '' };
  });
  check('书源页 v0.6.47：点卡片打开详情抽屉（含规则摘要/编辑/分组/校验/删除）',
    d.has && /规则摘要/.test(d.txt) && /编辑规则/.test(d.txt) && /移动分组/.test(d.txt) && /删除书源/.test(d.txt),
    d.txt.slice(0, 90));
}
/* 校验书源（全链路）——从抽屉进入（卡片内已无 mini 按钮） */
await page.locator('.src-drawer .sd-btn', { hasText: '深度校验' }).click();
await page.waitForSelector('.check input');
await page.fill('.check input', 'pride');
await page.click('.check .btn.primary');
await page
  .waitForFunction(() => /全部通过|失败|异常/.test((document.querySelector('.check-out') || {}).textContent || ''), null, { timeout: 240000 })
  .catch(() => {});
const checkText = (await page.locator('.check-out').textContent()) || '';
console.log('  [check]', checkText.slice(0, 400));
if (errors.length) console.log('  [page-errors]', errors.slice(0, 5).join(' || '));
check('书源校验：搜索→详情→目录→正文 全通过', /全部通过/.test(checkText), checkText.slice(-100));
await page.screenshot({ path: '/tmp/ui-3-check.png' });
await page.click('.dialog-x');

/* v0.6.47 多选态：长按进入 → 顶部「已选 N」+ 底部浮动操作栏 */
{
  await longPressRow('.src-card[data-url*="gutenberg"]');
  const sel = await page.evaluate(() => ({
    selecting: document.body.classList.contains('src-selecting'),
    bar: (document.querySelector('#src-selbar') || {}).textContent || '',
    fabVisible: !!document.querySelector('#src-fab') && !document.querySelector('#src-fab').hidden,
    fabActs: [...document.querySelectorAll('#src-fab .sf-b')].map((b) => (b.textContent || '').trim()),
  }));
  check('书源页 v0.6.47：长按进入多选态 + 底部浮动操作栏',
    sel.selecting && /已选 1/.test(sel.bar) && sel.fabVisible,
    JSON.stringify(sel));
  check('书源页 v0.6.47：批量动作在底部栏（启用/禁用/校验/分组/删除/导出）',
    sel.fabActs.length === 6 && sel.fabActs.some((t) => /启用/.test(t)) && sel.fabActs.some((t) => /删除/.test(t)),
    JSON.stringify(sel.fabActs));
  // 取消多选，恢复浏览态
  await page.locator('#src-selbar .ss-a', { hasText: '取消' }).click();
  await page.waitForFunction(() => !document.body.classList.contains('src-selecting'), null, { timeout: 3000 });
  check('书源页 v0.6.47：取消多选回到浏览态', !(await page.evaluate(() => document.body.classList.contains('src-selecting'))));
}

/* v0.6.50：书源体检 = 快速校验 → 深度校验失效源 → 两次都不合格 → 一键删除 */
{
  await srcMenu('mi-checkall');
  await page.waitForSelector('.sheet .form input', { timeout: 5000 });
  check('书源体检：弹层含默认关键词 + 自动深度校验开关',
    (await page.locator('.sheet .form input').first().inputValue()) === '我的' && (await page.locator('.sheet .check-opt input').count()) === 1);
  await page.fill('.sheet .form input', 'pride'); // fixture 命中 gutenberg 搜索回放
  await page.locator('.sheet .check-opt input').uncheck();
  await page.locator('.sheet .btn.primary', { hasText: '开始体检' }).click();
  await page.waitForFunction(() => {
    const bar = document.querySelector('#checkall-bar');
    return bar && !bar.hidden && /快速校验完成/.test(bar.textContent || '');
  }, null, { timeout: 120000 });
  const stat = await page.evaluate(() => {
    const bar = document.querySelector('#checkall-bar');
    const ok = document.querySelectorAll('.src-card .tag.ck-ok').length;
    const bad = document.querySelectorAll('.src-card .tag.ck-bad').length;
    const skip = document.querySelectorAll('.src-card .tag.ck-skip').length;
    return { text: (bar.textContent || '').trim().slice(0, 60), ok, bad, skip, hasDeep: /深度校验失效源/.test(bar.textContent || '') };
  });
  // gutenberg 有搜索且命中 → 有效；toscrape 无 searchUrl → 「无搜索」跳过、不算失效
  check('书源体检：快速校验统计有效/失效并上角标', stat.ok >= 1 && stat.bad === 0 && stat.skip === 1, JSON.stringify(stat));
  check('书源体检：无失效时不提供深度校验入口', !stat.hasDeep && /全部可用/.test(stat.text), stat.text);
  check('书源体检：完成态提供导出报告入口', await page.evaluate(() => {
    const bar = document.querySelector('#checkall-bar');
    return !![...bar.querySelectorAll('button')].find((b) => /导出报告/.test(b.textContent || ''));
  }));
  // 不真删：gutenberg 还要用（后面搜书/校验用例依赖它）。删除链路由下面的死源用例覆盖
  /* 筛选：v0.6.47 改为顶部常驻可折叠 chip 行（不再藏在二级面板里） */
  await page.click('#src-chips .chip-toggle');
  check('书源页 v0.6.47：筛选为顶部可折叠 chip 行', await page.evaluate(() => {
    const p = document.querySelector('#src-chips .chip-panel');
    return !!p && !p.hidden && document.querySelectorAll('#src-chips .chip-row').length >= 2;
  }));
  await page.locator('.chip-panel .chip-btn', { hasText: '已启用' }).click();
  const fs = await page.evaluate(() => ({
    cards: document.querySelectorAll('.src-card').length,
    toggle: (document.querySelector('#src-chips .chip-toggle') || {}).textContent || '',
  }));
  check('筛选：仅启用过滤生效 + 摘要写在 chip 上', /仅启用/.test(fs.toggle) && fs.cards >= 1, JSON.stringify(fs));
  // 清除筛选恢复（点选 chip 后面板保持展开，可直接点清除）
  await page.locator('.chip-panel .chip-btn', { hasText: '清除筛选' }).click();
  await page.waitForFunction(() => document.querySelectorAll('.src-card').length >= 2, null, { timeout: 5000 });
  check('筛选：清除后恢复全部', (await page.locator('.src-card').count()) >= 2);
  // 筛选 chip 行可折叠收起（不占主按钮位）
  const panelHidden = () => page.evaluate(() => document.querySelector('#src-chips .chip-panel').hidden);
  if (await panelHidden()) await page.click('#src-chips .chip-toggle'); // 先展开
  const expanded = !(await panelHidden());
  await page.click('#src-chips .chip-toggle'); // 再收起
  const collapsed = await panelHidden();
  check('书源页 v0.6.47：筛选 chip 行可折叠收起', expanded && collapsed, `expanded=${expanded} collapsed=${collapsed}`);
}

/* v0.6.50：书源页宽度对齐 + 左滑手势 + 「两次都不合格 → 一键删除」完整链路 */
{
  // 回归：卡片曾多叠一层 24px 左右边距，整页比书架/设置窄一圈
  const geom = await page.evaluate(() => {
    const hd = document.querySelector('.page-head').getBoundingClientRect();
    const cd = document.querySelector('.src-card').getBoundingClientRect();
    return { dx: Math.round(cd.x - hd.x), dw: Math.round(cd.width - hd.width) };
  });
  check('书源页 v0.6.50：卡片与页头同宽（不再额外内缩）', geom.dx === 0 && Math.abs(geom.dw) <= 1, JSON.stringify(geom));
  // 真机「左滑只抖一下」的根因：横向手势被浏览器当滚动吃掉 → 必须声明 touch-action:pan-y
  check('书源页 v0.6.50：卡片声明 touch-action:pan-y', await page.evaluate(() => {
    const e = document.querySelector('.src-card');
    return !!e && getComputedStyle(e).touchAction === 'pan-y';
  }));

  // 导入一个死域名源：搜索/详情/目录/正文必然全失败
  const deadJson = JSON.stringify([{
    bookSourceName: '死源校验',
    bookSourceUrl: 'https://dead-check.invalid',
    searchUrl: 'https://dead-check.invalid/s?q={{key}}',
    ruleSearch: { bookList: 'class.item', name: 'tag.a@text', bookUrl: 'tag.a@href' },
  }]);
  await srcMenu('mi-import');
  await page.waitForSelector('.sheet .opt-item');
  await page.locator('.sheet .opt-item').nth(2).click();
  await page.waitForSelector('.dialog .json-input');
  await page.fill('.dialog .json-input', deadJson);
  await page.locator('.dialog .btn.primary', { hasText: '导入' }).click();
  await page.waitForFunction(
    () => [...document.querySelectorAll('.src-card .src-name')].some((e) => (e.textContent || '').includes('死源校验')),
    null,
    { timeout: 8000 }
  );

  // 一键体检（默认勾选「自动接着深度校验」）
  await srcMenu('mi-checkall');
  await page.waitForSelector('.sheet .form input');
  await page.fill('.sheet .form input', 'pride');
  await page.locator('.sheet .btn.primary', { hasText: '开始体检' }).click();
  await page.waitForFunction(
    () => /体检完成/.test((document.querySelector('#checkall-bar') || {}).textContent || ''),
    null,
    { timeout: 120000 }
  );
  const done = await page.evaluate(() => {
    const bar = document.querySelector('#checkall-bar');
    const dead = document.querySelector('.src-card[data-url="https://dead-check.invalid"]');
    return {
      text: (bar.textContent || '').replace(/\s+/g, ' ').trim(),
      deadTag: dead ? (dead.querySelector('.tag') || {}).textContent || '' : '',
      hasDel: !![...bar.querySelectorAll('button')].find((b) => /一键删除/.test(b.textContent || '')),
    };
  });
  check('书源体检：深度校验后死源标为「两次失效」', /两次都不合格/.test(done.text) && /两次失效/.test(done.deadTag), JSON.stringify(done));
  check('书源体检：两次都不合格时给出一键删除入口', done.hasDel);

  await page.locator('#checkall-bar button', { hasText: '只看两次不合格' }).click();
  await page.waitForFunction(() => document.querySelectorAll('.src-card').length === 1, null, { timeout: 5000 });
  check('书源体检：只看两次不合格 → 只剩死源', /死源校验/.test((await page.locator('.src-card .src-name').textContent()) || ''));

  await page.locator('#checkall-bar button', { hasText: '一键删除' }).click();
  await page.waitForSelector('.dialog.confirm');
  check('书源体检：删除前二次确认', /两次都不合格/.test((await page.locator('.dialog.confirm').textContent()) || ''));
  await page.locator('.dialog .btn.primary').click();
  await page.waitForFunction(() => !document.querySelector('.src-card[data-url="https://dead-check.invalid"]'), null, { timeout: 8000 });
  check('书源体检：一键删除后列表恢复（好源不动）', (await page.locator('.src-card').count()) >= 2);
}

/* 搜书 */
await page.click('.sidebar nav a[href="#/search"]');
await page.waitForSelector('#kw');
check('搜书栏：胶囊容器 + 圆形搜索钮', (await page.locator('.search-pill .search-ic').count()) === 1 && (await page.locator('.search-pill .search-go').count()) === 1);
check('搜书：不自动聚焦（不弹输入法）', await page.evaluate(() => document.activeElement !== document.querySelector('#kw')));
check('搜书：搜索 / 发现 顶部 Tab', (await page.locator('.page-tabs button').count()) === 2);
check('搜书：书源默认全选（2 / 2）', /2 \/ 2/.test((await page.locator('#src-summary').textContent()) || ''));
await page.locator('.page-tabs button', { hasText: '发现' }).click();
check('发现：顶部 Tab 可切换到发现页', await page.locator('#panel-discover').isVisible());
await page.locator('.page-tabs button', { hasText: '搜索' }).click();
await page.click('#btn-pick');
await page.waitForSelector('.src-group-head', { timeout: 5000 });
check('书源选择：按分组列出', (await page.locator('.src-group-head').count()) >= 1);
await page.locator('.sheet .dialog-x').click();
await page.fill('#kw', 'pride');
await page.click('#btn-go');
await page.waitForSelector('.result-card', { timeout: 120000 });
const results = await page.locator('.result-card').count();
check('搜索有聚合结果', results > 0, `got ${results}`);
{
  // 关键词相关性过滤：书源搜索规则失效时会返回整页推荐，这类结果必须被挡掉
  const names = await page.locator('.result-name').allTextContents();
  check('搜索结果均与关键词相关', names.length > 0 && names.every((n) => /pride/i.test(n)), names.slice(0, 3).join(' | '));
}
check('搜索进度不刷规则源码', !/json1=|result=|<\/js>/.test((await page.locator('#search-progress').textContent()) || ''));
check('命中行：来源弹性列（非 180px 固定）', await page.evaluate(() => {
  const el = document.querySelector('.hit-src');
  return !!el && getComputedStyle(el).flexBasis === 'auto';
}));
check('发现浏览：自定义选源（无原生 select）', await page.evaluate(() => !document.querySelector('select') && !!document.querySelector('#explore-pick')));
// 搜索任务跨页保持：切走再回来，结果与关键词仍在（任务状态在模块级）
await page.evaluate(() => { location.hash = '#/shelf'; });
await page.waitForTimeout(300);
await page.evaluate(() => { location.hash = '#/search'; });
await page.waitForSelector('.result-card', { timeout: 8000 });
check(
  '搜索任务跨页保持（返回后结果与关键词仍在）',
  (await page.locator('.result-card').count()) > 0 && (await page.locator('#kw').inputValue()) === 'pride'
);
await page.screenshot({ path: '/tmp/ui-4-search.png' });

/* 打开第一本书 → 试读（默认不入书架），可从「更多」进详情 */
await page.locator('.result-card button[data-g]').first().click();
await page.waitForSelector('.rd-shell', { timeout: 240000 }).catch(async () => {
  const hash = await page.evaluate(() => location.hash);
  console.log('  [open-fail] hash=' + hash + ' errors=' + errors.slice(0, 5).join(' || '));
});
check('搜书点开 → 直接进阅读（试读，不自动入书架）', await page.locator('.rd-shell').isVisible());
await page.waitForSelector('.reader-body .content', { timeout: 120000 });
check('正文渲染（>500 字符）', (await page.evaluate(() => (document.querySelector('.reader-body .content') || {}).textContent?.trim().length || 0)) > 500);
await page.screenshot({ path: '/tmp/ui-6-reader.png' });

/* v0.6.19：工具坞两行按钮（事件委托渲染）+ 净化入口 + 图标排布 */
await ensureChrome();
const dockActs = await page.locator('#rd-dock .rd-act').count();
check('工具坞：两行按钮渲染（默认 11 项）', dockActs === 11, `got ${dockActs}`);
await page.click('#r-purify');
await page.waitForSelector('.sheet .replace-list', { timeout: 5000 });
check('阅读页净化：共享规则面板打开', true);
await page.locator('.sheet .dialog-x').click();
await page.waitForFunction(() => !document.querySelector('.sheet'), null, { timeout: 5000 });
await page.click('#r-more');
await page.locator('.pick-item', { hasText: '图标排布' }).click();
await page.waitForSelector('.ml-row', { timeout: 5000 });
const mlRows = await page.locator('.ml-row').count();
check('坞图标排布：编辑器（两行 + 已隐藏区）', mlRows >= 11, `got ${mlRows}`);
await page.locator('.sheet button', { hasText: '恢复默认排布' }).click();
await page.waitForTimeout(300);
await page.locator('.sheet .dialog-x').click();
await page.waitForFunction(() => !document.querySelector('.sheet'), null, { timeout: 5000 });

// 试读书 → 详情页（含「加入书架」按钮）
await ensureChrome();
await page.click('#r-more');
await page.locator('.pick-item', { hasText: '书籍详情' }).click();
await page.waitForSelector('.book-head', { timeout: 10000 });
check('书籍详情页打开', await page.locator('.book-head').isVisible());
check('试读详情：有「加入书架」按钮', await page.locator('#btn-shelf').isVisible().catch(() => false));
const tocCount = await page.locator('.toc-item').count();
check('目录渲染（>20 章）', tocCount > 20, `got ${tocCount}`);
await page.screenshot({ path: '/tmp/ui-5-book.png' });

// v0.6.19：详情页「在线搜书」→ 搜索页预填书名并自动搜索；query 消费后回详情页
const detailHash = await page.evaluate(() => location.hash);
await page.click('#btn-search');
await page.waitForFunction(() => location.hash.indexOf('#/search') === 0, null, { timeout: 5000 });
await page.waitForSelector('#kw', { timeout: 5000 });
const kwFilled = await page.locator('#kw').inputValue();
check('在线搜书：跳转搜索页并预填书名', /Pride/i.test(kwFilled), kwFilled);
await page.waitForFunction(() => /搜索中|完成：/.test((document.querySelector('#search-progress') || {}).textContent || ''), null, { timeout: 30000 });
check('在线搜书：自动执行搜索', true);
check('在线搜书：query 已消费（hash 无 kw=）', !/kw=/.test(await page.evaluate(() => location.hash)));
await page.evaluate((h) => { location.hash = h; }, detailHash);
await page.waitForSelector('.book-head', { timeout: 10000 });

// 加入书架 → 此时才上书架
await page.click('#btn-shelf');
await page.waitForSelector('#btn-shelf', { state: 'detached', timeout: 10000 }).catch(() => {});
check('加入书架：详情按钮消失（已入架）', (await page.locator('#btn-shelf').count()) === 0);

/* 阅读（第 0 章） */
await page.click('.page-head a.btn.primary');
await page.waitForSelector('.reader-body .content', { timeout: 120000 });
// 打开阅读页默认显出工具坞：目录等按钮必须真的在屏内可点
// （旧行为是打开即沉浸隐藏，目录被 translateY 移出屏幕 + pointer-events:none，用户点不到）
{
  const st = await page.evaluate(() => {
    const v = document.querySelector('#view');
    const btn = document.querySelector('#r-toc');
    if (!v || !btn) return { ok: false, why: 'no #view or #r-toc' };
    const r = btn.getBoundingClientRect();
    return {
      shown: !v.classList.contains('chrome-hidden'),
      inView: r.top >= 0 && r.bottom <= window.innerHeight && r.width > 0,
    };
  });
  check('阅读页默认显出工具坞：目录按钮在屏内可点', st.shown === true && st.inView === true, JSON.stringify(st));
}
await ensureChrome();
const len = await page.evaluate(() => (document.querySelector('.reader-body .content') || {}).textContent?.trim().length || 0);
check('正文渲染（>500 字符）', len > 500, `got ${len}`);

/* M2：键盘 → 翻下一章 */
const chapBefore = await page.locator('.reader-chap').textContent();
await page.keyboard.press('ArrowRight');
await page.waitForFunction((prev) => (document.querySelector('.reader-chap') || {}).textContent !== prev, chapBefore, { timeout: 120000 });
check('键盘 → 翻下一章', true);

/* M2/M5：主题四套（纸白/米黄/青石/夜间，页面沉浸切换；排版面板内切换） */
await page.click('#r-typo');
await page.waitForSelector('.sheet .seg');
check('排版：四套阅读主题', (await page.locator('.sheet .seg button', { hasText: '纸白' }).count()) === 1 && (await page.locator('.sheet .seg button', { hasText: '青石' }).count()) === 1);
const themeBtn = (label) => page.locator('.sheet .seg button', { hasText: label }).last();
await themeBtn('米黄').click();
await page.waitForFunction(() => document.body.dataset.readerTheme === 'cream', null, { timeout: 10000 });
await page.waitForTimeout(600); // 等背景过渡动画（.45s）走完再取色
const bgCream = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
check('主题切换：米黄（沉浸）', bgCream === 'rgb(239, 231, 210)', bgCream);
await themeBtn('青石').click();
await page.waitForFunction(() => document.body.dataset.readerTheme === 'gray', null, { timeout: 10000 });
await page.waitForTimeout(600);
const bgGray = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
check('主题切换：青石（M5 新增）', bgGray === 'rgb(233, 234, 227)', bgGray);
await themeBtn('夜间').click();
await page.waitForFunction(() => document.body.dataset.readerTheme === 'dark', null, { timeout: 10000 });
check('主题切换：夜间', true);
await themeBtn('纸白').click();
await page.waitForFunction(() => !document.body.dataset.readerTheme, null, { timeout: 10000 });
check('主题切回纸白', true);

/* M2：行距滑杆（排版面板，数值可调） */
await page.evaluate(() => {
  const rs = [...document.querySelectorAll('.sheet input[type=range]')];
  const r = rs[0]; // 第 1 个滑杆 = 行距
  r.value = '2.2';
  r.dispatchEvent(new Event('input', { bubbles: true }));
});
await page.waitForFunction(() => document.querySelector('#r-body').style.lineHeight === '2.2', null, { timeout: 5000 });
check('行距切换', true);

/* M3：字号 +/-（排版面板，即时生效） */
await page.locator('.sheet button', { hasText: 'A+' }).click();
await page.waitForFunction(() => parseInt(document.querySelector('#r-body').style.fontSize, 10) === 19, null, { timeout: 5000 });
check('字号放大', true);

/* M5：页边距滑杆 + 亮度遮罩（排版面板） */
await page.evaluate(() => {
  const rs = [...document.querySelectorAll('.sheet input[type=range]')];
  const r = rs[2]; // 第 3 个滑杆 = 页边距
  r.value = '36';
  r.dispatchEvent(new Event('input', { bubbles: true }));
});
await page.waitForFunction(() => document.querySelector('#r-body').style.getPropertyValue('--page-pad-x') === '36px', null, { timeout: 5000 });
check('排版：页边距可调（36px）', true);
await page.evaluate(() => {
  const rs = [...document.querySelectorAll('.sheet input[type=range]')];
  const r = rs[3]; // 第 4 个滑杆 = 亮度
  r.value = '0.8';
  r.dispatchEvent(new Event('input', { bubbles: true }));
});
await page.waitForFunction(() => {
  const dim = document.querySelector('#dim-layer');
  const sheet = document.querySelector('.sheet');
  if (!dim || !sheet) return false;
  const cs = getComputedStyle(dim);
  const dz = parseInt(cs.zIndex, 10);
  const sz = parseInt(getComputedStyle(sheet).zIndex, 10) || 0;
  const dr = dim.getBoundingClientRect();
  // 调暗效果必须盖得住排版面板本身（用户在面板上拖动时要能看出调了多少）
  return parseFloat(dim.style.opacity) > 0 && cs.position === 'fixed' && dz > sz && dr.width >= window.innerWidth && dr.height >= window.innerHeight;
}, null, { timeout: 5000 });
check('排版：亮度遮罩（body 级 fixed，连排版面板一起变暗）', true);
await page.locator('.sheet button', { hasText: '恢复默认' }).click();
await page.waitForFunction(() => document.querySelector('#dim-layer').style.opacity === '0', null, { timeout: 5000 });
check('排版：亮度恢复默认', true);

await page.click('.sheet .dialog-x');
await page.waitForFunction(() => !document.querySelector('.sheet'), null, { timeout: 5000 });

/* ---------- M5：目录抽屉 / 书签 / 正文搜索 / 自动阅读 ---------- */
// 目录抽屉 → 跳到第 3 章（fixture 已录音的章节，保证离线确定；后续「下一章」也落在录音章节上）
await page.click('#r-toc');
await page.waitForSelector('.toc-drawer.open', { timeout: 5000 });
const drawerChaps = await page.locator('.toc-drawer .dw-item').count();
check('目录抽屉：章节列表', drawerChaps > 5, `got ${drawerChaps}`);
const chapBeforeDrawer = await page.locator('#r-chap').textContent();
await page.locator('.toc-drawer .dw-item').nth(2).click();
await page.waitForSelector('.reader-body .content', { timeout: 120000 });
await page.waitForFunction((prev) => (document.querySelector('#r-chap') || {}).textContent !== prev, chapBeforeDrawer, { timeout: 120000 });
check('目录抽屉：跳转章节', true, await page.locator('#r-chap').textContent());

// 书签 → 抽屉书签 tab 可见
await page.click('#r-bmk');
await page.waitForFunction(() => /已添加书签/.test((document.querySelector('#toast') || {}).textContent || ''), null, { timeout: 5000 });
check('书签：添加提示', true);
await page.click('#r-toc');
await page.waitForSelector('.toc-drawer.open', { timeout: 5000 });
await page.locator('.toc-tabs button', { hasText: '书签' }).click();
await page.waitForFunction(() => document.querySelectorAll('.bm-item').length >= 1, null, { timeout: 5000 });
check('书签：抽屉书签 tab 可见', true);
await page.locator('.toc-drawer .dialog-x').click();
await page.waitForFunction(() => !document.querySelector('.toc-drawer.open'), null, { timeout: 5000 });

// 正文搜索改为默认全书，跨章检索较慢，统一放到后面用「本地三章样本」验证

// 自动阅读（滚动模式）→ 出现控制条 → 停止
await page.click('#r-auto');
await page.waitForFunction(() => document.querySelector('#auto-bar') && !document.querySelector('#auto-bar').hidden, null, { timeout: 5000 });
check('自动阅读：控制条出现', true);
await page.click('#auto-stop');
await page.waitForFunction(() => document.querySelector('#auto-bar').hidden, null, { timeout: 5000 });
check('自动阅读：可停止', true);

/* M2/M5：听书 —— 点一下直接开念（不再二次确认）；有语音出控制条，无语音给原因面板 */
await page.click('#r-tts');
await page.waitForTimeout(900);
const ttsToast = (await page.locator('#toast').textContent().catch(() => '')) || '';
const ttsBar = await page.locator('#tts-bar').isVisible().catch(() => false);
const ttsSheet = (await page.locator('.sheet-head').textContent().catch(() => '')) || '';
check('听书：点一下直接开念（不再弹「开始朗读」确认面板）', !/^听书$/.test(ttsSheet.trim()), ttsSheet);
check(
  'TTS：控制条 / 无语音时的原因面板',
  ttsBar || /语音/.test(ttsToast) || /朗读不可用/.test(ttsSheet),
  `bar=${ttsBar} toast=${ttsToast} sheet=${ttsSheet}`,
);
if (!ttsBar && /朗读不可用/.test(ttsSheet)) {
  const body = (await page.locator('.sheet-body').textContent()) || '';
  check('TTS：无语音时说明具体原因', /没有可用的语音|语音引擎/.test(body), body.slice(0, 40));
  await page.locator('.sheet-head .dialog-x').click();
  await page.waitForSelector('.sheet-head', { state: 'detached', timeout: 5000 }).catch(() => {});
}
if (ttsBar) {
  await page.click('#tts-timer');
  await page.waitForFunction(() => /15/.test((document.querySelector('#tts-timer') || {}).textContent || ''), null, { timeout: 5000 });
  check('TTS：定时停止 15 分', true, await page.locator('#tts-timer').textContent());
  await page.click('#tts-timer');
  await page.waitForFunction(() => /30/.test((document.querySelector('#tts-timer') || {}).textContent || ''), null, { timeout: 5000 });
  check('TTS：定时停止 30 分', true, await page.locator('#tts-timer').textContent());
  await page.click('#tts-timer');
  await page.click('#tts-timer');
  await page.click('#tts-timer');
  await page.waitForFunction(() => /关/.test((document.querySelector('#tts-timer') || {}).textContent || ''), null, { timeout: 5000 });
  check('TTS：定时档位循环回关', true);
  await page.click('#tts-exit');
}

/* 下一章（按钮翻页） */
const histBefore = await page.evaluate(() => history.length);
await page.click('#r-next');
await page.waitForFunction(
  (prev) => (document.querySelector('.reader-chap') || {}).textContent !== prev,
  await page.locator('.reader-chap').textContent(),
  { timeout: 120000 }
);
await page.waitForSelector('.reader-body .content', { timeout: 120000 }); // 等正文落地，保证进度已写库
check('下一章翻页', true);
check('翻章不压历史栈（系统返回可退出阅读页）', (await page.evaluate(() => history.length)) === histBefore, `before=${histBefore}`);
await page.screenshot({ path: '/tmp/ui-7-next.png' });

/* 网页版书架已删除（整页原生化，见 v0.6.105）：#/shelf 仅留说明卡 + 打开书架入口。
   书架相关的页签 / 横幅 / 进度 / 分组 / 本地导入 / 编辑信息等场景现由原生
   ShelfActivity / ImportActivity 覆盖，不再在 Web 冒烟测试中验证。 */
await page.evaluate(() => { location.hash = '#/shelf'; });
await page.waitForTimeout(300);
check('网页版书架退役：#/shelf 显示说明卡', await page.evaluate(() => !!document.querySelector('#gone-shelf')));
check('网页版书架退役：提供「打开书架」入口', await page.evaluate(() => {
  const b = document.querySelector('#gone-shelf');
  return !!b && /打开书架/.test(b.textContent || '');
}));

/* M2：设置 — 缓存统计 + 净化规则增删/停用 + 清缓存 */
await page.click('.sidebar nav a[href="#/settings"]');
await page.waitForSelector('#cache-stat');
const stat = (await page.locator('#cache-stat').textContent()) || '';
// v0.6.55：进入设置只数章数（避免整表读正文撑爆内存）；精确大小在「刷新统计」里用游标流式算
check('缓存统计（N 章）', /^\d+ 章$/.test(stat.trim()), stat);
await page.click('#cache-refresh');
await page.waitForFunction(() => /章 · 约 /.test(document.querySelector('#cache-stat').textContent), null, { timeout: 5000 });
const stat2 = (await page.locator('#cache-stat').textContent()) || '';
check('缓存统计（刷新后 N 章 · 约 X MB）', /章 · 约 \d+(\.\d+)? MB/.test(stat2), stat2);
// v0.6.19：设置页与阅读页共用同一份规则编辑器（replace.js 共享面板）
await page.click('#r-manage');
await page.waitForSelector('.sheet .replace-list', { timeout: 5000 });
await page.locator('.sheet button.btn.block', { hasText: '添加规则' }).click();
await page.waitForFunction(() => document.querySelectorAll('.sheet .replace-row').length === 1, null, { timeout: 5000 });
check('净化规则可添加（共享面板）', true);
await page.locator('.sheet .replace-row .switch').first().click();
check('净化规则可停用', !(await page.locator('.sheet .replace-row .switch input').first().isChecked()));
check('净化规则：作用域分段（正文/标题）', (await page.locator('.sheet .rr-scope button').count()) === 2);
await page.locator('.sheet .rr-scope button', { hasText: '标题' }).click();
await page.waitForFunction(() => (document.querySelector('.sheet .rr-scope button.on') || {}).textContent === '标题', null, { timeout: 5000 });
check('净化规则：作用域可切到标题', (await page.locator('.sheet .rr-scope button.on').textContent()) === '标题');
await page.locator('.sheet .replace-ctl button.danger').first().click();
await page.waitForFunction(() => document.querySelectorAll('.sheet .replace-row').length === 0, null, { timeout: 5000 });
check('净化规则可删除', true);
await page.locator('.sheet .dialog-x').click();
await page.waitForFunction(() => !document.querySelector('.sheet'), null, { timeout: 5000 });
page.on('dialog', (d) => d.accept());
await page.click('#cache-clear');
await confirmOk();
await page.waitForFunction(() => /0 章/.test((document.querySelector('#cache-stat') || {}).textContent || ''), null, { timeout: 8000 });
check('清空正文缓存', true);

/* M5：阅读统计行 + 导入备份入口 */
const readStat = (await page.locator('#read-stat').textContent()) || '';
check('M5：累计阅读统计行', /\d+ 小时 \d+ 分钟 · \d+ 本书/.test(readStat), readStat);
check('M5：导入备份入口', await page.locator('#d-import').isVisible().catch(() => false));

/* M2「移出书架 → 清缓存」：网页版书架已删除，该流程现由原生 ShelfActivity 覆盖
   （破坏性操作 / 章节缓存同步清零在安卓端验证）。Web 冒烟测试不再验证书架内的破坏性操作。 */

/* ---------- M3：移动端视口 + 底部导航 + 本地导入（TXT/GBK） ----------
   这些场景依赖网页版书架（底部导航 / 本地导入上书架 / 长按编辑 / 分组筛选），
   现已整页原生化：移动端底部导航、本地一键扫描导入、长按编辑信息、分组与筛选
   均在原生 ShelfActivity / ImportActivity 实现。Web 冒烟测试不再覆盖书架内交互，
   相关回归在安卓端进行。 */

/* ---------- M5：书架分组（排序 / 分组管理 / 新建 / 移动 / 过滤 / 删除） ----------
   网页版书架筛选面板已删除，分组与筛选现由原生 ShelfActivity 的「筛选」sheet 覆盖
   （吸顶分组标题 + 组内计数见 v0.6.106）。以下不再在 Web 冒烟测试中验证。 */

/* 本地书 GBK 解码 / 分章 / 分页：本地导入与阅读已完全原生化（ImportActivity + NativeReaderActivity），
   不再经由网页版书架。排版 / 弹层回归需回到阅读页继续（回溯历史栈回到阅读页）。 */
// 以下阅读回归（页眉页脚 / 分页 / 底部导航显隐）按移动视口设计：≤760px 默认左右翻页、
// 底部导航可见。原 M3 块在此切到移动视口，整页原生化后由这里显式恢复为 390×844。
await page.setViewportSize({ width: 390, height: 844 });
// 原：回溯历史栈回到阅读页（那本经本地导入的书）。现直接种一本全缓存的合成书并进阅读
await openSeededBook();
await ensureChrome();
// 页眉页脚仅在左右翻页模式生效（滚动模式下排版面板只显示「仅左右翻页模式生效」）；
// 视口 >760px 默认滚动，这里显式切到分页再测
await ensurePaged();

/* v0.6.23：页眉页脚为独立小标题 + 纯入口行；页眉 4 选项 / 页脚 6 选项（同一套分段控件，多选） */
await page.click('#r-typo');
await page.waitForSelector('.sheet .hf-row', { timeout: 5000 });
check('排版：页眉页脚入口行（摘要 + 箭头）', await page.locator('.sheet .hf-row').isVisible());
check('排版：页眉页脚是独立小标题', (await page.locator('.sheet .sheet-row.hf-sec .row-label').textContent()) === '页眉页脚');
const hfSummary = (await page.locator('.sheet .hf-v').textContent()) || '';
check('排版：页眉页脚摘要（当前配置）', /页眉/.test(hfSummary), hfSummary);
await page.locator('.sheet .hf-row').click();
const hfSheet = page.locator('.sheet').last();
await hfSheet.waitFor({ timeout: 5000 });
check('页眉页脚：页眉 4 选项（分段控件）', (await hfSheet.locator('.seg').first().locator('button').count()) === 4, `got ${await hfSheet.locator('.seg').first().locator('button').count()}`);
check('页眉页脚：页脚 6 选项（同一套分段控件，多选）', (await hfSheet.locator('.seg.multi button').count()) === 6, `got ${await hfSheet.locator('.seg.multi button').count()}`);
for (const label of ['全书进度', '本章进度', '剩余章数']) {
  const b = hfSheet.locator('.seg.multi button', { hasText: label });
  if (!((await b.getAttribute('class')) || '').includes('on')) await b.click();
}
// 几何回归：多选按钮必须完整落在灰底容器内（不只靠肉眼判断"是否突出"）；
// 应用按钮应撑满面板宽度（曾经的 62px 小胶囊与 350px 面板不成比例）
const hfGeo = await hfSheet.evaluate((s) => {
  const seg = s.querySelector('.seg.multi');
  const sr = seg.getBoundingClientRect();
  const out = [...seg.querySelectorAll('button')].filter((b) => {
    const r = b.getBoundingClientRect();
    return r.left < sr.left - 0.5 || r.right > sr.right + 0.5 || r.top < sr.top - 0.5 || r.bottom > sr.bottom + 0.5;
  }).length;
  const ap = [...s.querySelectorAll('.btn')].find((b) => b.textContent.trim() === '应用');
  return { out, segW: sr.width, apW: ap ? ap.getBoundingClientRect().width : 0 };
});
check('页脚：按钮不越出容器', hfGeo.out === 0, `越界 ${hfGeo.out} 个`);
check('应用按钮：与面板同宽（整行）', hfGeo.apW >= hfGeo.segW - 1, `${Math.round(hfGeo.apW)} / ${Math.round(hfGeo.segW)}`);
await hfSheet.locator('.btn.primary', { hasText: '应用' }).click();
await page.waitForFunction(() => !document.querySelector('.sheet'), null, { timeout: 20000 });
// 应用后重渲染 + 重新分页，页脚回填全书/本章进度（含 %）
await page.waitForFunction(() => {
  const f = document.querySelector('.reader-body .pg-foot');
  return f && /%/.test(f.textContent || '');
}, null, { timeout: 20000 });
check('页眉页脚：全书/本章进度渲染到页脚', true, (await page.locator('.reader-body .pg-foot').first().textContent()) || '');

/* v0.6.25：多层弹层逐级返回（侧滑应回上一层，而不是一路退回阅读页）+ 亮度与常亮相邻 */
await page.click('#r-typo');
await page.waitForSelector('.sheet .typo-rows', { timeout: 5000 });
const typoOrder = await page.evaluate(() => [...document.querySelectorAll('.sheet .typo-rows .row-label > span:first-child')].map((el) => el.textContent.trim()));
check('排版：屏幕常亮紧挨亮度', typoOrder.indexOf('屏幕常亮') === typoOrder.indexOf('亮度') + 1, typoOrder.join(' → '));
await page.locator('.sheet .hf-row').click();
await page.waitForSelector('.seg.multi', { timeout: 5000 });
check('排版：子面板叠加（两层弹层）', (await page.locator('.overlay').count()) === 2);
// 真实入口是原生返回键 → window.__modouBack（不走 history，避免与路由跳转抢时序）
const consumed1 = await page.evaluate(() => window.__modouBack());
await page.waitForTimeout(320);
const back1 = await page.evaluate(() => ({ n: document.querySelectorAll('.overlay').length, hf: !!document.querySelector('.seg.multi'), typo: !!document.querySelector('.typo-rows') }));
check('返回一次：只关页眉页脚，排版面板保留', consumed1 === true && back1.n === 1 && !back1.hf && back1.typo, JSON.stringify({ consumed1, ...back1 }));
const consumed2 = await page.evaluate(() => window.__modouBack());
await page.waitForTimeout(320);
check('再返回：关闭排版面板', consumed2 === true && (await page.locator('.overlay').count()) === 0);
check('无弹层时返回交给路由（返回 false）', (await page.evaluate(() => window.__modouBack())) === false);

/* v0.6.27：越级返回全面核查——目录抽屉 / 底部搜索条必须被返回键优先关闭，而不是退回书架 */
{
  // ① 目录抽屉：返回一次只关抽屉，人还在阅读页
  await ensureChrome();
  await page.click('#r-toc');
  await page.waitForSelector('.toc-drawer.open', { timeout: 5000 });
  const c1 = await page.evaluate(() => window.__modouBack());
  await page.waitForTimeout(320);
  const s1 = await page.evaluate(() => ({ drawer: !!document.querySelector('.toc-drawer.open'), reading: !!document.querySelector('.rd-shell') }));
  check('返回键：先关目录抽屉（不越级回书架）', c1 === true && !s1.drawer && s1.reading, JSON.stringify({ c1, ...s1 }));
  check('抽屉关闭后再返回：交给路由（返回 false）', (await page.evaluate(() => window.__modouBack())) === false);
  // ② 搜索：点搜索先开汇总页（history 占位，返回=goBack 关页）。
  //    v0.6.79 起新增规则：没搜东西就返回，会连底部条一起收掉（见 closeFindPage）。
  //    所以这里先真搜一次，验证「已搜过 → 返回后底部条仍在 → 返回键关条」这条链路。
  await ensureChrome();
  await page.click('#r-find');
  await page.waitForSelector('#find-page:not([hidden])', { timeout: 5000 });
  await page.fill('#fp-input', '茶铺');
  await page.click('.fp-go');
  await page.waitForFunction(
    () => /共 \d+ 条结果/.test((document.querySelector('#fp-title') || {}).textContent || ''),
    null,
    { timeout: 20000 }
  );
  await page.goBack(); // 模拟原生返回：汇总页有 history 占位，popstate 关闭
  await page.waitForFunction(() => {
    const fp = document.querySelector('#find-page');
    return fp && fp.hidden;
  }, null, { timeout: 5000 });
  await page.waitForTimeout(300);
  const s2a = await page.evaluate(() => ({ bar: !(document.querySelector('#find-bar') || {}).hidden, reading: !!document.querySelector('.rd-shell') }));
  check('汇总页返回后：底部搜索条仍在（已搜过，人还在阅读页）', s2a.bar && s2a.reading, JSON.stringify(s2a));
  const c2 = await page.evaluate(() => window.__modouBack());
  await page.waitForTimeout(320);
  const s2b = await page.evaluate(() => ({ bar: !(document.querySelector('#find-bar') || {}).hidden, reading: !!document.querySelector('.rd-shell') }));
  check('返回键：关闭底部搜索条（不退出阅读）', c2 === true && !s2b.bar && s2b.reading, JSON.stringify({ c2, ...s2b }));
  // ②b 新规则（v0.6.79）：完全没搜就返回 → 底部搜索条不显示。用户原话：
  //    「从搜索结果返回后，如果我没有搜东西的话，返回后就不要显示底下那个搜索框了」。
  await ensureChrome();
  await page.click('#r-find');
  await page.waitForSelector('#find-page:not([hidden])', { timeout: 5000 });
  await page.goBack();
  await page.waitForFunction(() => {
    const fp = document.querySelector('#find-page');
    return fp && fp.hidden;
  }, null, { timeout: 5000 });
  await page.waitForTimeout(300);
  const s2c = await page.evaluate(() => ({ bar: !(document.querySelector('#find-bar') || {}).hidden, reading: !!document.querySelector('.rd-shell') }));
  check('没搜东西就返回：底部搜索条不显示（人还在阅读页）', !s2c.bar && s2c.reading, JSON.stringify(s2c));
  // 说明：抽屉 mask 全屏遮挡，工具坞按钮点不到，抽屉与 overlay 天然不会同开；
  // LIFO 顺序由统一返回栈保证——overlay 关完才轮到常驻的阅读页浮层处理器（用例①②已覆盖）
}

/* ---------- 分页阅读：多页 / 点击分区 / 键盘 / 拖拽 / 沉浸显隐 / 模式切换 ---------- */
// 原：经网页书架 + 网页本地导入打开 long-sample（均已原生化）。现直接由当前详情页重进阅读，
// 并切到左右翻页（分页）模式验证分页回归；long-sample 的「长章多页」特性由 Pride 长章覆盖。
await page.click('#rd-back');
await page.waitForSelector('.book-head', { timeout: 5000 });
await page.click('.page-head a.btn.primary'); // 详情页主按钮：继续/开始阅读
await page.waitForSelector('.reader-body .content, .reader-body .page', { timeout: 20000 });
await ensurePaged();
await page.waitForSelector('.reader-body .page', { timeout: 15000 });
check('分页正文铺满（底部只留页脚一行，无额外留白）', await page.evaluate(() => {
  const rb = document.querySelector('#r-body');
  const pg = document.querySelector('.page');
  const pad = parseFloat(getComputedStyle(pg).paddingBottom);
  // 听书横条改为「占用页脚位置」：正文不再为它预留 44px，只剩 16px 基础内边距 + 页脚
  const foot = pg.querySelector('.pg-foot');
  return Math.round(rb.getBoundingClientRect().top) <= 2 && pad < 30 && !!foot;
}));
// 没开听书时不该看到底部小横条（否则等于白占一行正文）
check('未开听书：底部小横条不存在', await page.evaluate(() => {
  const b = document.querySelector('#listen-bar');
  return !b || b.hidden || b.offsetHeight === 0;
}));
await ensureChrome();
const pageCount = await page.locator('.reader-body .page').count();
check('分页模式：长章分出多页', pageCount > 1, `页数=${pageCount}`);
// 长段放不下时按剩余高度切段填充（续段 .cont），避免半页空白
const fillStat = await page.evaluate(() => {
  const pgs = [...document.querySelectorAll('.reader-body .page')];
  const cont = document.querySelectorAll('.reader-body .page .cont').length;
  const first = pgs.find((p) => p.offsetHeight > 100) || pgs[0];
  const pr = first.getBoundingClientRect();
  const cs = getComputedStyle(first);
  let bottom = pr.top;
  for (const ch of first.children) bottom = Math.max(bottom, ch.getBoundingClientRect().bottom);
  return { cont, remain: Math.round(pr.bottom - (parseFloat(cs.paddingBottom) || 0) - bottom) };
});
check('分页：长段切段填满页面（有续段且剩余<80px）', fillStat.cont > 0 && fillStat.remain < 80, JSON.stringify(fillStat));

/* 底部工具坞：两行均衡布局 / 进度条可拖 / 字重可调 */
const dockStat = await page.evaluate(() => {
  const rows = [...document.querySelectorAll('.rd-acts')];
  const prog = document.querySelector('#r-prog');
  return {
    rows: rows.length,
    counts: rows.map((r) => r.querySelectorAll('.rd-act').length),
    balanced: rows.length === 2 && rows[0].getBoundingClientRect().top < rows[1].getBoundingClientRect().top,
    labels: [...document.querySelectorAll('.rd-acts-2 .rd-act em')].map((b) => b.textContent.trim()),
    progH: Math.round(prog.getBoundingClientRect().height),
    meta: {
      chap: (document.querySelector('#r-meta-chap') || {}).textContent || '',
      page: (document.querySelector('#r-meta-page') || {}).textContent || '',
    },
    fw: getComputedStyle(document.querySelector('#r-body')).fontWeight,
  };
});
check('阅读工具坞：两行均衡分布（6 + 5）', dockStat.rows === 2 && dockStat.counts.join(',') === '6,5', JSON.stringify(dockStat));
check('阅读工具坞：第二行含上一章/下一章/更多（图标+文字）', dockStat.labels.join('|') === '上一章|下一章|排版|夜间|更多', dockStat.labels.join('|'));
check('阅读进度条：触摸区 ≥24px', dockStat.progH >= 24, String(dockStat.progH));
check('阅读进度：显示第几章 / 第几页', /第 \d+ \/ \d+ 章/.test(dockStat.meta.chap) && /第 \d+ \/ \d+ 页/.test(dockStat.meta.page), JSON.stringify(dockStat.meta));
check('阅读字重：默认中粗（500）', dockStat.fw === '500', dockStat.fw);

/* 自动阅读：顶部页进度条 */
await page.click('#r-auto');
await page.waitForFunction(() => !document.querySelector('#auto-tick').hidden, null, { timeout: 5000 });
const tickStat = await page.evaluate(() => {
  const el = document.querySelector('#auto-tick');
  return {
    dur: el.style.getPropertyValue('--dur'),
    anim: getComputedStyle(el.querySelector('i')).animationName,
    h: Math.round(el.getBoundingClientRect().height),
  };
});
check('自动阅读：顶部页进度条出现并按秒/页计时', /^\d+s$/.test(tickStat.dur) && tickStat.anim === 'autoFill' && tickStat.h >= 2, JSON.stringify(tickStat));
await page.click('#auto-stop');
await page.waitForTimeout(200);

{
  const before = await page.locator('#r-meta-page').textContent();
  const box = await page.locator('#r-prog').boundingBox();
  const y = box.y + box.height / 2;
  await page.mouse.move(box.x + box.width * 0.15, y);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.85, y, { steps: 6 });
  await page.mouse.up();
  await page.waitForTimeout(260);
  const after = await page.locator('#r-meta-page').textContent();
  check('阅读进度条：拖动可跳页', before !== after, `${before} → ${after}`);
  // 拖回开头，后续断言仍从第 1 页开始
  await page.mouse.click(box.x + 1, y);
  await page.waitForTimeout(200);
}

await page.click('#r-typo');
await page.waitForSelector('.sheet');
await page.waitForTimeout(420);
/* v0.6.23：排版面板 UI 修复（步进按钮边框 / 行间距 / 页眉页脚独立小标题） */
check('排版：字号步进按钮边框明显（≥1px 实线）', await page.evaluate(() => {
  const bs = [...document.querySelectorAll('.sheet .btn.stepper')];
  if (bs.length !== 2) return false;
  return bs.every((b) => {
    const cs = getComputedStyle(b);
    return parseFloat(cs.borderTopWidth) >= 1 && cs.borderTopStyle !== 'none' && cs.borderTopStyle !== 'dashed';
  });
}));
check('排版：各行间距统一（小标题不贴上方控件）', await page.evaluate(() => {
  const rows = [...document.querySelectorAll('.sheet .typo-rows > .sheet-row')];
  if (rows.length < 8) return false;
  return rows.every((r, i) => (i === 0 ? true : r.getBoundingClientRect().top - rows[i - 1].getBoundingClientRect().bottom >= 14));
}));
check('排版：页眉页脚为独立小标题且与上一块拉开', await page.evaluate(() => {
  const rows = [...document.querySelectorAll('.sheet .typo-rows > .sheet-row')];
  const i = rows.findIndex((r) => r.classList.contains('hf-sec'));
  if (i <= 0) return false;
  const label = rows[i].querySelector('.row-label');
  if (!label || label.textContent.trim() !== '页眉页脚') return false;
  return rows[i].getBoundingClientRect().top - rows[i - 1].getBoundingClientRect().bottom >= 20;
}));
check('排版：页眉页脚入口是纯摘要行（标题已拎出）', (await page.locator('.sheet .hf-row .hf-k').count()) === 0 && /页眉/.test((await page.locator('.sheet .hf-v').textContent()) || ''));
await page.locator('.sheet .sheet-row', { hasText: '字重' }).getByText('粗', { exact: true }).click();
await page.waitForTimeout(450);
check(
  '阅读字重：可切到粗（600）',
  (await page.evaluate(() => getComputedStyle(document.querySelector('#r-body')).fontWeight)) === '600'
);
await page.locator('.sheet .dialog-x').click();
const pageLabel = await page.locator('#r-meta-page').textContent();
check('分页模式：页码指示', /^第 1 \//.test(pageLabel || ''), pageLabel);
await page.screenshot({ path: '/tmp/ui-13-paged.png' });

// 点击右 1/3 区域 → 下一页
// 预分页会挂隐藏的 .pages 槽位（相邻章预取），直接 locator('.pages') 会命中多个 → 只取「可见」的
const pbox = await page.evaluate(() => {
  const el = [...document.querySelectorAll('.reader-body .pages')].find((e) => getComputedStyle(e).visibility !== 'hidden');
  if (!el) return null;
  const r = el.getBoundingClientRect();
  return { x: r.x, y: r.y, width: r.width, height: r.height };
});
const pcy = pbox.y + pbox.height * 0.5;
await page.mouse.click(pbox.x + pbox.width * 0.85, pcy);
await page.waitForFunction(() => /^第 2 \//.test((document.querySelector('#r-meta-page') || {}).textContent || ''), null, { timeout: 3000 });
check('分页模式：点击右侧翻页', true, await page.locator('#r-meta-page').textContent());
const tx1 = await page.evaluate(() => {
  const el = [...document.querySelectorAll('.reader-body .pages')].find((e) => getComputedStyle(e).visibility !== 'hidden');
  return el ? el.style.transform : '';
});
check('分页模式：容器平移', /translateX\(-100%\)/.test(tx1), tx1);

// 键盘 → / ← 翻页
await page.keyboard.press('ArrowRight');
await page.waitForFunction(() => /^第 3 \//.test((document.querySelector('#r-meta-page') || {}).textContent || ''), null, { timeout: 3000 });
check('分页模式：键盘 → 翻页', true);
await page.keyboard.press('ArrowLeft');
await page.waitForFunction(() => /^第 2 \//.test((document.querySelector('#r-meta-page') || {}).textContent || ''), null, { timeout: 3000 });
check('分页模式：键盘 ← 翻页', true);

// 鼠标拖拽（向右）→ 上一页
await page.mouse.move(pbox.x + pbox.width * 0.85, pcy);
await page.mouse.down();
await page.mouse.move(pbox.x + pbox.width * 0.85 + 140, pcy, { steps: 8 });
await page.mouse.up();
await page.waitForFunction(() => /^第 1 \//.test((document.querySelector('#r-meta-page') || {}).textContent || ''), null, { timeout: 3000 });
await page.waitForTimeout(400); // 等回弹动画结束，避免动画中矩形偏移干扰点击分区判定
check('分页模式：拖拽翻回上一页', true);

// （章首右滑边界/跨章用例已移至 three-chapters 多章书上下文，见下方「正文搜索」段前）

// 点按正文中部 → 沉浸显隐
// 翻页会收起 chrome（v0.6.45 新行为）；本组测「点中部显隐」，先确保起点为显示态
if (await page.evaluate(() => document.querySelector('#view').classList.contains('chrome-hidden'))) {
  await page.mouse.click(pbox.x + pbox.width * 0.5, pcy);
  await page.waitForTimeout(140);
}
await page.mouse.click(pbox.x + pbox.width * 0.5, pcy);
check('分页模式：点按中部隐藏 chrome', await page.evaluate(() => document.querySelector('#view').classList.contains('chrome-hidden')));
await page.mouse.click(pbox.x + pbox.width * 0.5, pcy);
check('分页模式：再点恢复 chrome', !(await page.evaluate(() => document.querySelector('#view').classList.contains('chrome-hidden'))));

/* v0.6.23：正文划选（自绘选区 + 点按收起选区不误翻页）
 * 注：v0.6.77 起正文改成 user-select:none —— 系统手柄是原生 View，样式改不了，
 * 而且手柄一拖过正文边界就会被 Chromium 钳到块边界（「想选两行」变整页）。 */
check('正文不参与系统选区（user-select:none，选区由自绘层接管）', await page.evaluate(() => {
  const pb = document.querySelector('.reader-body .page .pg-body');
  if (!pb) return false;
  const cs = getComputedStyle(pb);
  return cs.userSelect === 'none' || cs.webkitUserSelect === 'none';
}));
check('自绘选区层已就位', await page.evaluate(() => !!document.querySelector('.sel-layer')));
check('分页容器放行纵向手势（touch-action:pan-y，长按划选不被屏蔽）', await page.evaluate(() => {
  const p = document.querySelector('.reader-body .pages');
  return !!p && getComputedStyle(p).touchAction === 'pan-y';
}));
{
  const pageBefore = await page.locator('#r-meta-page').textContent();
  await page.evaluate(() => {
    const n = window.__sel.bodyLen();
    window.__sel.set(0, Math.min(60, n));
  });
  check('分页正文可划词（自绘选区成立）', (await page.evaluate(() => window.__sel.len())) > 0);
  // 点按正文（左侧翻页区）：有选区时先收起选区，不翻页
  await page.waitForTimeout(160);
  await page.mouse.click(pbox.x + pbox.width * 0.1, pcy);
  const selLen = await page.evaluate(() => window.__sel.len());
  const pageAfter = await page.locator('#r-meta-page').textContent();
  check('划选后点按：收起选区且不误翻页', selLen === 0 && pageAfter === pageBefore, `${pageBefore} → ${pageAfter}`);
}

/* v0.6.25：选词工具条（复制 / 净化替换 / 搜索）+ 净化替换预填 */
{
  const picked = await page.evaluate(() => {
    window.__sel.set(6, 14);
    return window.__sel.text();
  });
  await page.waitForTimeout(320);
  const bar = await page.evaluate(() => {
    const b = document.querySelector('.sel-bar');
    if (!b) return null;
    const r = b.getBoundingClientRect();
    const rects = window.__sel.rects();
    return { shown: b.classList.contains('show'), text: b.textContent, x: r.x, y: r.y, w: r.width, h: r.height, vw: innerWidth, vh: innerHeight, selBottom: rects.length ? rects[rects.length - 1].b : 0 };
  });
  check('选词工具条：出现且含三项动作', !!bar && bar.shown && /复制/.test(bar.text) && /净化替换/.test(bar.text) && /搜索/.test(bar.text), bar && bar.text);
  check('选词工具条：完整落在视口内', !!bar && bar.x >= 0 && bar.x + bar.w <= bar.vw && bar.y >= 0 && bar.y + bar.h <= bar.vh);
  check('选词工具条：不压住选区末行', !!bar && bar.y >= bar.selBottom, `${bar && Math.round(bar.y)} / ${bar && Math.round(bar.selBottom)}`);
  await page.locator('.sel-bar button', { hasText: '净化替换' }).click();
  await page.waitForTimeout(760);
  const rep = await page.evaluate(() => {
    const s = document.querySelectorAll('.sheet');
    const last = s[s.length - 1];
    if (!last) return null;
    const rows = [...last.querySelectorAll('.replace-row')];
    const row = rows[rows.length - 1];
    const ins = row ? [...row.querySelectorAll('.replace-fields input')] : [];
    return { title: last.querySelector('.sheet-head span')?.textContent || '', pattern: ins[0]?.value || '', focused: document.activeElement === ins[1] };
  });
  // 跨段选区里带着换行，替换面板会把空白折叠掉 —— 比对时一并去掉空白
  check(
    '净化替换：打开面板并预填选中文字',
    !!rep && rep.title === '替换净化' && rep.pattern.length > 0 && !!picked && picked.replace(/\s+/g, '').includes(rep.pattern.replace(/\s+/g, '')),
    JSON.stringify({ rep, picked }),
  );
  check('净化替换：光标落在「替换为」', !!rep && rep.focused);
  // 清理临时规则并关闭面板，避免影响后续用例
  await page.evaluate(() => {
    const s = document.querySelectorAll('.sheet');
    const last = s[s.length - 1];
    const rows = [...last.querySelectorAll('.replace-row')];
    const row = rows[rows.length - 1];
    const del = row && [...row.querySelectorAll('.replace-ctl button')].find((b) => b.title === '删除');
    if (del) del.click();
  });
  await page.waitForTimeout(220);
  await page.locator('.sheet .dialog-x').last().click();
  await page.waitForTimeout(320);
}

// 阅读页沉浸：外层底部导航隐藏
check('阅读页：底部导航隐藏', !(await page.locator('.tabbar').isVisible()));

// 翻页会收起工具栏（v0.6.45 新行为），点排版前先显出菜单（若已隐藏）
if (await page.evaluate(() => document.querySelector('#view').classList.contains('chrome-hidden'))) {
  await page.mouse.click(pbox.x + pbox.width * 0.5, pcy);
  await page.waitForTimeout(140);
}
// 排版面板：段距 + 字体
await page.click('#r-typo');
await page.evaluate(() => {
  const rs = [...document.querySelectorAll('.sheet input[type=range]')];
  const r = rs[1]; // 第 2 个滑杆 = 段距
  r.value = '1.6';
  r.dispatchEvent(new Event('input', { bubbles: true }));
});
await page.waitForFunction(() => getComputedStyle(document.querySelector('#r-body')).getPropertyValue('--para-gap').trim() === '1.6em', null, { timeout: 3000 });
check('排版：段距可调（1.6 倍）', true);
await page.locator('.sheet .seg button', { hasText: '黑体' }).click();
await page.waitForFunction(() => /font-hei/.test(document.querySelector('#r-body').className), null, { timeout: 3000 });
check('排版：字体可切换（黑体）', true);
check('排版：屏幕常亮开关', (await page.locator('.sheet .switch input[type=checkbox]').count()) === 1);

// 排版面板切换为上下滚动
await page.locator('.sheet .seg button', { hasText: '上下滚动' }).click();
await page.waitForSelector('body:not(.reader-paged)', { timeout: 5000 });
// 切回滚动后阅读器重新渲染并异步重取本章正文，给足加载时间（网络/ fixture 回放可能偏慢）
await page.waitForSelector('.reader-body .content', { timeout: 30000 }).catch(async () => {
  console.log('  [scroll-fail] errors=' + errors.slice(0, 5).join(' || '));
  console.log('  [scroll-fail] dom=' + (await page.evaluate(() => {
    const b = document.querySelector('.reader-body');
    return b ? b.innerHTML.slice(0, 200) : 'no .reader-body; view=' + (document.querySelector('#view') || {}).innerHTML?.slice(0, 200);
  })));
});
check('分页模式：可切换回上下滚动', (await page.locator('.reader-body .content').count()) > 0);
await page.screenshot({ path: '/tmp/ui-14-scroll-mode.png' });

// 再切回左右翻页
await page.click('#r-typo');
await page.locator('.sheet .seg button', { hasText: '左右翻页' }).click();
await page.waitForSelector('body.reader-paged .reader-body .page', { timeout: 5000 });
check('分页模式：可切回左右翻页', true);

// 离开阅读页后底部导航恢复
await page.click('#rd-back');
await page.waitForSelector('.book-head', { timeout: 5000 });
check('离开阅读页：底部导航恢复', await page.locator('.tabbar').isVisible());

/* 正文搜索：默认全书（跨章 ↑/↓）。原用 three-chapters 本地样本，现改搜索打开 Pride（多章），
   跨章边界 / 原地切章等通用行为在任意多章书上成立 */
await openSeededBook();
await ensurePaged();
await ensureChrome();

/* 章首右滑边界行为（three-chapters 多章书）：① 首章不跨（书到头）② 显式跳章后锁定期内不立即跨回 ③ 锁定期过后可跨回上一章 */
const dragRightAtEdge = async () => {
  await page.mouse.move(pbox.x + pbox.width * 0.85, pcy);
  await page.mouse.down();
  await page.mouse.move(pbox.x + pbox.width * 0.85 + 140, pcy, { steps: 8 });
  await page.mouse.up();
};
const chapText = () => page.locator('#r-meta-chap').textContent();
{
  await page.waitForFunction(() => { const e = document.querySelector('#r-meta-chap'); return e && /^第 1 \//.test(e.textContent || ''); }, null, { timeout: 3000 }).catch(() => {});
  // ① 首章章首右滑：书已到头，不跨
  const before1 = await chapText();
  await dragRightAtEdge();
  await page.waitForTimeout(400);
  const after1 = await chapText();
  check('分页边界：首章章首右滑不跨回（书已到头）', after1 === before1, `${before1} → ${after1}`);
  // ② 显式跳到下一章（进入锁定期），立刻右滑不应立即跨回（防误触退回上一章）
  await page.click('#r-next');
  await page.waitForFunction(() => { const e = document.querySelector('#r-meta-chap'); return e && /^第 2 \//.test(e.textContent || ''); }, null, { timeout: 3000 }).catch(() => {});
  const locked = await chapText();
  await dragRightAtEdge();
  await page.waitForTimeout(200);
  check('跳章锁定期内：章首右滑不立即跨回', (await chapText()) === locked, `${locked} → ${await chapText()}`);
  // ③ 锁定期（450ms）过期后，章首右滑应跨回上一章（修复：换章后右滑失效）
  await page.waitForTimeout(600);
  const before3 = await chapText();
  await dragRightAtEdge();
  await page.waitForTimeout(400);
  const after3 = await chapText();
  check('换章后锁定期过：章首右滑跨回上一章', /^第 2 \//.test(before3) && /^第 1 \//.test(after3), `${before3} → ${after3}`);
  // 跨章滑入收尾：容器不应残留 no-anim（残留会导致后续翻页无过渡），
  // 且 transform 必须落在整页位置（不能停在滑动中间）
  await page.waitForTimeout(420);
  const slideEnd = await page.evaluate(() => {
    // 预分页会挂隐藏槽位，必须取「可见」的那个 .pages
    const p = [...document.querySelectorAll('.pages')].find((e) => getComputedStyle(e).visibility === 'visible');
    if (!p) return null;
    return { noAnim: p.classList.contains('no-anim'), tf: p.style.transform || '' };
  });
  check(
    '跨章滑入：收尾无 no-anim 残留且停在整页位',
    !!slideEnd && !slideEnd.noAnim && /^translateX\(-?\d+%\)$/.test(slideEnd.tf),
    JSON.stringify(slideEnd)
  );
}
{
  // v0.6.42：跨章翻页必须「原地切章」——外壳 #rd-shell 必须是同一个 DOM 节点。
  // 若整页重建（renderReader），要重跑十几次设置读取 + 全套事件绑定 + 面板构建，
  // 滑入动画只能等这些做完才开始，用户手感就是「跨章比普通翻页卡一下」。
  await page.waitForTimeout(400);
  await page.evaluate(() => {
    const s = document.querySelector('#rd-shell');
    if (s) s.__keepMarker = 1;
  });
  const beforeKeep = await chapText();
  for (let i = 0; i < 40; i++) {
    const atLast = await page.evaluate(() => {
      const m = /第 (\d+) \/ (\d+) 页/.exec((document.querySelector('#r-meta-page') || {}).textContent || '');
      return !m || m[1] === m[2];
    });
    if (atLast) break;
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(60);
  }
  await page.mouse.move(pbox.x + pbox.width * 0.5, pcy);
  await page.mouse.down();
  await page.mouse.move(pbox.x + pbox.width * 0.5 - 170, pcy, { steps: 8 });
  await page.mouse.up();
  await page.waitForTimeout(600);
  const keepRes = await page.evaluate(() => ({
    keep: (document.querySelector('#rd-shell') || {}).__keepMarker === 1,
    bodies: document.querySelectorAll('#r-body').length,
    // 预分页会在正文里挂隐藏槽位，所以 .pages 总数会 >1；真正该为 1 的是「可见」的那个
    pageBoxes: [...document.querySelectorAll('.reader-body .pages')].filter((e) => getComputedStyle(e).visibility === 'visible').length,
  }));
  const afterKeep = await chapText();
  check(
    '跨章翻页：原地切章（外壳 DOM 复用，不整页重建）',
    keepRes.keep && keepRes.bodies === 1 && keepRes.pageBoxes === 1 && afterKeep !== beforeKeep,
    JSON.stringify({ ...keepRes, from: beforeKeep, to: afterKeep })
  );
}
{
  // v0.6.43：跨章滑入要和章内翻页同款——旧页向左滑出 + 新页从右滑入；
  // 只在过渡期间存在旧页，动画结束必须清理干净（否则会叠影/内存泄漏）。
  await page.waitForTimeout(420);
  for (let i = 0; i < 40; i++) {
    const atLast = await page.evaluate(() => {
      const m = /第 (\d+) \/ (\d+) 页/.exec((document.querySelector('#r-meta-page') || {}).textContent || '');
      return !m || m[1] === m[2];
    });
    if (atLast) break;
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(60);
  }
  await page.mouse.move(pbox.x + pbox.width * 0.5, pcy);
  await page.mouse.down();
  await page.mouse.move(pbox.x + pbox.width * 0.5 - 170, pcy, { steps: 8 });
  await page.mouse.up();
  // 预分页会在正文里挂若干「隐藏槽位」（最多前后两章），所以 .pages 总数不是 1；
  // 真正该断言的是「可见」的容器数量：正常 1 个，过渡期间 2 个（旧页 + 新页）。
  const visPages = () =>
    page.evaluate(() => {
      const all = [...document.querySelectorAll('#r-body .pages')];
      return {
        visible: all.filter((e) => getComputedStyle(e).visibility === 'visible').length,
        ghosts: document.querySelectorAll('#r-body .pages.ghost').length,
        total: all.length,
        slots: document.querySelectorAll('#r-body .pg-slot').length,
      };
    });
  const midGhost = await visPages();
  check(
    '跨章滑入：过渡期间保留旧页做滑出（不是从空白里滑进来）',
    midGhost.ghosts === 1 && midGhost.visible === 2,
    JSON.stringify(midGhost)
  );
  await page.waitForTimeout(720);
  const endGhost = await visPages();
  check(
    '跨章滑入：结束后只剩一个可见容器，旧页与多余槽位清理干净',
    endGhost.ghosts === 0 && endGhost.visible === 1 && endGhost.slots <= 3,
    JSON.stringify(endGhost)
  );
}
{
  // v0.6.43：连点「下一章」时预读跟不上会走异步路径——必须先给「正在加载本章…」的页做反馈，
  // 且加载完不能留残留（留一个 .rd-loading 或 loading-slot 都会挡住正文）。
  await page.waitForTimeout(300);
  await page.evaluate(() => document.querySelector('#view').classList.remove('chrome-hidden'));
  for (let i = 0; i < 5; i++) {
    await page.click('#r-next').catch(() => {});
    await page.waitForTimeout(60);
  }
  await page.waitForTimeout(900);
  const loadRes = await page.evaluate(() => ({
    loading: document.querySelectorAll('#r-body .rd-loading').length,
    loadSlots: document.querySelectorAll('#r-body .loading-slot').length,
    visible: [...document.querySelectorAll('#r-body .pages')].filter((e) => getComputedStyle(e).visibility === 'visible').length,
    slots: document.querySelectorAll('#r-body .pg-slot').length,
    bodyText: (document.querySelector('#r-body .pg-body') || {}).textContent || '',
  }));
  check(
    '连点下一章：加载页不残留且正文正常',
    loadRes.loading === 0 && loadRes.loadSlots === 0 && loadRes.visible === 1 && loadRes.slots <= 4,
    JSON.stringify({ ...loadRes, bodyText: loadRes.bodyText.slice(0, 20) })
  );
}

check('按钮禁止长按选中', await page.evaluate(() => getComputedStyle(document.querySelector('#r-typo')).userSelect === 'none'));
await page.click('#r-typo');
await page.waitForSelector('.sheet', { timeout: 5000 });
await page.waitForTimeout(500); // 等上滑动画结束再量位置
const shBox = await page.locator('.sheet').boundingBox();
const vp3 = page.viewportSize();
check('排版面板贴底（上方圆角正确）', !!shBox && shBox.y > 60 && Math.round(shBox.y + shBox.height) === vp3.height, JSON.stringify(shBox));
await page.evaluate(() => window.__modouBack());
await page.waitForTimeout(500);
check('返回键关闭面板且留在阅读页', (await page.locator('.sheet').count()) === 0 && (await page.locator('.rd-shell').count()) === 1);
await page.click('#r-find');
// ⑤ 点搜索直接进汇总（结果/进度）页，而非只弹底部搜索条
await page.waitForSelector('#find-page:not([hidden])', { timeout: 5000 });
check('正文搜索：点搜索直接进汇总页（进度/结果在此）', await page.locator('#find-page').isVisible());
const vpSize2 = page.viewportSize();
const fbBox2 = await page.locator('#find-bar').boundingBox();
check('正文搜索：搜索条在底部（拇指可达）', !!fbBox2 && fbBox2.y > vpSize2.height * 0.5, JSON.stringify(fbBox2));
check('正文搜索：搜索时隐藏工具坞', await page.evaluate(() => document.querySelector('#rd-shell').classList.contains('finding')));
// 原 three-chapters（3 章）专属的「第 2/3 章」断言改为对 Pride 通用：搜一个必中词，
// 验证全书检索 + 汇总页 + 点击跳章 + 下一个推进，不绑定具体章数。
await page.fill('#fp-input', '茶铺');
await page.click('.fp-go');
// 搜索完成 → 汇总页显示结果
await page.waitForFunction(() => /共 \d+ 条结果/.test((document.querySelector('#fp-title') || {}).textContent || ''), null, { timeout: 20000 });
await page.waitForTimeout(280);
check('正文搜索：结果在汇总页展示', true);
const srCount = await page.locator('.fp-item').count();
check('正文搜索：默认全书命中若干条', srCount > 0, `got ${srCount}`);
await page.locator('.fp-item').nth(1).click();
await page.waitForFunction(
  () => /第 \d+ \/ \d+ 章/.test((document.querySelector('#r-meta-chap') || {}).textContent || ''),
  null,
  { timeout: 15000 }
);
check('正文搜索：结果页点击跳到命中章', true, await page.locator('#r-meta-chap').textContent());
await page.click('#find-next');
await page.waitForFunction(
  () => /^\d+\/\d+$/.test((document.querySelector('#find-count') || {}).textContent || '') && /第 \d+ \/ \d+ 章/.test((document.querySelector('#r-meta-chap') || {}).textContent || ''),
  null,
  { timeout: 15000 }
);
check('正文搜索：再下一个推进到下一命中', true, await page.locator('#r-meta-chap').textContent());
check('正文搜索：命中处高亮', (await page.locator('.reader-body .find-hl.cur').count()) >= 1);
await page.click('#find-close');
await page.waitForFunction(() => document.querySelector('#find-bar').hidden, null, { timeout: 5000 });
check('正文搜索：关闭后清除高亮', (await page.locator('#r-body .find-hl').count()) === 0);

/* ④ 换书取消搜索：在一本书里搜索后退出、进另一本，搜索不得继续 */
{
  // 在当前书（Pride）里启动一个全书搜索
  await page.click('#r-find');
  await page.waitForSelector('#find-page:not([hidden])', { timeout: 5000 });
  await page.fill('#fp-input', '茶铺');
  await page.click('.fp-go');
  await page.waitForFunction(() => /共 \d+ 条结果/.test((document.querySelector('#fp-title') || {}).textContent || ''), null, { timeout: 20000 });
  // 原：退出本书 → 网页书架 → 进另一本（long-sample，本地导入）。现种一本 id 不同的
  // 合成书再进入（同 id 的 hash 直跳是「跳章」语义，不取消搜索会话——跨章搜索依赖它）
  await openSeededBook('-swap');
  await ensureChrome();
  // 新书不应残留上一本书的搜索会话（底部搜索条/结果页/高亮都应清空）
  const residue = await page.evaluate(() => ({
    barHidden: document.querySelector('#find-bar') ? document.querySelector('#find-bar').hidden : true,
    pageHidden: document.querySelector('#find-page') ? document.querySelector('#find-page').hidden : true,
    hl: document.querySelectorAll('#r-body .find-hl').length,
  }));
  check('④ 换书后取消搜索（无残留搜索条/结果页/高亮）', residue.barHidden && residue.pageHidden && residue.hl === 0, JSON.stringify(residue));
}

/* 夜间 / 日间一键切换 */
{
  await ensureChrome(); // 换到新书后 chrome 可能是隐藏的，先唤出工具坞
  const lightBefore = await page.evaluate(() => document.body.getAttribute('data-reader-theme'));
  await page.click('#r-night');
  await page.waitForTimeout(180);
  const dark = await page.evaluate(() => document.body.getAttribute('data-reader-theme'));
  await page.click('#r-night');
  await page.waitForTimeout(180);
  const back = await page.evaluate(() => document.body.getAttribute('data-reader-theme'));
  check('阅读：夜间/日间一键切换（日间回到原主题）', dark === 'dark' && back === lightBefore, `${lightBefore} → ${dark} → ${back}`);
}

/* 搜索结果汇总页（在当前书 Pride 内检索一个必然命中的词） */
await page.click('#r-find');
await page.waitForSelector('#find-page:not([hidden])', { timeout: 5000 });
await page.waitForSelector('#fp-input');
// 种子书正文里「茶铺」每章高频出现，用它做确定性检索
await page.fill('#fp-input', '茶铺');
await page.click('.fp-go');
await page.waitForFunction(() => /共 \d+ 条结果/.test((document.querySelector('#fp-title') || {}).textContent || ''), null, { timeout: 30000 });
await page.waitForTimeout(300);
check('搜索结果页：搜索完成自动进入', true);
check('搜索结果页：汇总命中', /共 \d+ 条结果/.test((await page.locator('#fp-title').textContent()) || ''));
const fpItems = await page.locator('.fp-item').count();
const fpMeta = await page.evaluate(() => {
  const g = document.querySelector('.fp-group');
  const el = document.querySelector('.fp-item');
  if (!g || !el) return {};
  return {
    chap: (g.querySelector('.fp-gnum') || {}).textContent || '',
    pct: (el.querySelector('.fp-pct') || {}).textContent || '',
    mark: !!el.querySelector('.fp-snip mark'),
  };
});
check('搜索结果页：章节号突出 + 进度 + 命中片段', fpItems >= 1 && /第 \d+ 章/.test(fpMeta.chap) && /%$/.test(fpMeta.pct) && fpMeta.mark, JSON.stringify(fpMeta));
await page.locator('.fp-item').first().click();
await page.waitForTimeout(700);
check('搜索结果页：点击跳转对应章节', /第 \d+ \/ \d+ 章/.test((await page.locator('#r-meta-chap').textContent()) || ''));

/* v0.6.44：打开目录定位到当前章（含 200+ 章虚拟目录窗口化、不从头渲染）。
   该用例依赖「本地导入 260 章合成书」(toc-window)，本地导入已整页原生化（ImportActivity），
   Web 冒烟测试无法取得此类本地书；目录定位与窗口化在原生端随本地导入一并覆盖。此处跳过。 */

/* v0.6.44：跨章往回翻必须落在上一章「末页」——而且要断言**画面**，
 * 只断言角标会漏（角标来自 pageIdx，容器位移写错时角标仍然是对的）。 */
{
  // 原：经网页书架 + 网页本地导入打开 multi-page（均已原生化）。现改搜索打开 Pride（多章）
  // 并切到左右翻页；「跨章往回翻停在上一章末页」是通用分页行为，在任意多章书上成立
  await openSeededBook();
  await ensurePaged();
  await page.evaluate(() => document.querySelector('#view').classList.remove('chrome-hidden'));
  // 到第 2 章章首
  await page.click('#r-next');
  await page.waitForFunction(() => /^第 2 \//.test((document.querySelector('#r-meta-chap') || {}).textContent || ''), null, { timeout: 5000 }).catch(() => {});
  await page.waitForTimeout(900);
  const mb = await page.evaluate(() => {
    const el = [...document.querySelectorAll('.reader-body .pages')].find((e) => getComputedStyle(e).visibility !== 'hidden');
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: r.x, y: r.y, width: r.width, height: r.height };
  });
  await page.mouse.move(mb.x + mb.width * 0.5, mb.y + mb.height * 0.5);
  await page.mouse.down();
  await page.mouse.move(mb.x + mb.width * 0.5 + 170, mb.y + mb.height * 0.5, { steps: 8 });
  await page.mouse.up();
  await page.waitForTimeout(900);
  const back = await page.evaluate(() => {
    const rb = document.querySelector('#r-body').getBoundingClientRect();
    const box = [...document.querySelectorAll('#r-body .pages')].filter((e) => getComputedStyle(e).visibility === 'visible').pop();
    if (!box) return { err: 'no-box' };
    const pages = [...box.querySelectorAll('.page')];
    let best = null;
    let bd = 1e9;
    for (const pg of pages) {
      const d = Math.abs(pg.getBoundingClientRect().left - rb.left);
      if (d < bd) {
        bd = d;
        best = pg;
      }
    }
    const idx = best ? pages.indexOf(best) : -1;
    return {
      n: pages.length,
      idx,
      tf: box.style.transform,
      want: `translateX(${-(pages.length - 1) * 100}%)`,
      chap: ((document.querySelector('#r-meta-chap') || {}).textContent || '').trim(),
    };
  });
  check(
    '跨章往回翻：画面真的停在上一章末页（不只是角标）',
    !back.err && back.n > 1 && back.idx === back.n - 1 && back.tf === back.want && /^第 1 \//.test(back.chap),
    JSON.stringify(back)
  );
  // 跨章翻页收起 chrome（v0.6.45 新行为），点返回前先显出顶栏
  if (await page.evaluate(() => document.querySelector('#view').classList.contains('chrome-hidden'))) {
    await page.mouse.click(mb.x + mb.width * 0.5, mb.y + mb.height * 0.5);
    await page.waitForTimeout(140);
  }
  await page.click('#rd-back');
  await page.waitForSelector('.book-head', { timeout: 5000 });
}

// ===== v0.6.45 针对性验证（全局夜间联动 / 选区不横向偏移 / 末行利用率）=====
// 全局夜间：设置页切换应改变整 App 主题（documentElement[data-theme]）
await page.evaluate(() => { location.hash = '#/settings'; });
await page.waitForSelector('#theme-seg', { timeout: 5000 });
await page.locator('#theme-seg button', { hasText: '深色' }).click();
check('全局夜间：设置选深色 → 整 App dark', (await page.evaluate(() => document.documentElement.dataset.theme)) === 'dark');
await page.locator('#theme-seg button', { hasText: '浅色' }).click();
check('全局夜间：设置选浅色 → 整 App light', (await page.evaluate(() => document.documentElement.dataset.theme)) === 'light');

// 阅读页：点夜间同步全局；点日间恢复用户设定；选区不引发横向偏移；末行利用率
// 原：经网页书架点击 multi-page 卡片进入（已原生化）。现改搜索打开 Pride 并切分页
await openSeededBook();
await ensurePaged();
await page.waitForSelector('#rd-stage', { timeout: 5000 });
await page.waitForSelector('.reader-body .pages', { timeout: 5000 });
await page.waitForTimeout(400);
if (await page.evaluate(() => document.querySelector('#view').classList.contains('chrome-hidden'))) {
  const b = await page.locator('#rd-stage').boundingBox();
  await page.mouse.click(b.x + b.width / 2, b.y + b.height / 2);
  await page.waitForTimeout(140);
}
await page.click('#r-night');
await page.waitForTimeout(120);
check('全局夜间：阅读页点夜间 → 全局 dark', (await page.evaluate(() => document.documentElement.dataset.theme)) === 'dark');
await page.click('#r-night');
await page.waitForTimeout(120);
check('全局夜间：阅读页点日间 → 全局恢复 light', (await page.evaluate(() => document.documentElement.dataset.theme)) === 'light');

// 选区跨行不引发横向偏移（.page 改 transform 定位后，scrollWidth 不再撑大）
await page.evaluate(() => window.__sel.set(0, window.__sel.bodyLen()));
await page.waitForTimeout(250);
const off = await page.evaluate(() => {
  const stage = document.querySelector('#rd-stage');
  const vis = [...document.querySelectorAll('.reader-body .page')].filter((p) => {
    if (getComputedStyle(p).visibility === 'hidden') return false; // 排除预分页隐藏槽位
    const r = p.getBoundingClientRect();
    return r.width > 0 && r.right > 1 && r.left < innerWidth - 1;
  });
  return { sl: stage.scrollLeft, vis: vis.length };
});
check('选区跨行不横向偏移（scrollLeft=0 且同屏仅 1 页）', off.sl === 0 && off.vis <= 1, JSON.stringify(off));
await page.evaluate(() => window.__sel.clear());

// 末行利用率：连翻 8 页，取每页末段末行宽占比，应 ≥ 0.5（修复前常见「只写几个字」）
let minUtil = 1;
for (let i = 0; i < 8; i++) {
  const u = await page.evaluate(() => {
    const pb = document.querySelector('#r-body > .pages .pg-body');
    if (!pb) return 1;
    let last = pb.lastElementChild;
    while (last && (!last.textContent || !last.textContent.trim())) last = last.previousElementSibling;
    if (!last) return 1;
    const rects = last.getClientRects();
    if (!rects.length) return 1;
    return Math.min(1, rects[rects.length - 1].width / pb.clientWidth);
  });
  minUtil = Math.min(minUtil, u);
  const b = await page.locator('#r-body > .pages').boundingBox();
  await page.mouse.click(b.x + b.width * 0.85, b.y + b.height * 0.5);
  await page.waitForTimeout(130);
}
check('末行利用率：连翻 8 页末段末行宽占比均 ≥ 0.5', minUtil >= 0.5, 'minUtil=' + minUtil.toFixed(2));

// ===== v0.6.46 针对性验证（开屏防闪：深色存档时首帧就是 dark）=====
// 主题存在 IndexedDB（异步），等它读完首帧已经按 light 画完 → 夜间模式开屏会亮一下。
// 修法是把选择镜像到 localStorage，在 head 内联脚本里同步定好 data-theme。
// 这里记录 data-theme 的变更序列：初始 HTML 是 light，head 脚本应立刻改成 dark。
{
  await page.evaluate(() => localStorage.setItem('modou.theme', 'dark'));
  // 让 App 入口脚本慢 1.2s 才返回：这样采样到的 dark 只可能来自 head 内联脚本，
  // 而不是「等 IndexedDB 读完设置后才切过去」（后者正是开屏发亮的原因）。
  await page.route('**/js/ui/app.js', async (route) => {
    await new Promise((r) => setTimeout(r, 1200));
    await route.continue();
  });
  await page.goto(BASE, { waitUntil: 'commit' });
  await page.waitForTimeout(500);
  const early = await page.evaluate(() => ({
    theme: document.documentElement.getAttribute('data-theme'),
    bg: getComputedStyle(document.body).backgroundColor,
  }));
  check('开屏防闪：深色存档时首帧即 dark（App JS 尚未执行）', early.theme === 'dark', JSON.stringify(early));
  const rgb = (early.bg.match(/\d+/g) || []).map(Number);
  const lum = rgb.length >= 3 ? (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000 : 255;
  check('开屏防闪：首屏底色为暗色（亮度<60，不是白/奶油闪一下）', lum < 60, early.bg);
  await page.unroute('**/js/ui/app.js');
  await page.evaluate(() => localStorage.setItem('modou.theme', 'light')); // 还原，不影响后续
}

check('无 JS 运行时错误', errors.length === 0, errors.slice(0, 3).join(' | '));
if (extFlakes.length) console.log(`  （源站直连图片网络波动 ${extFlakes.length} 次，不计失败：${extFlakes[0].slice(0, 90)}…）`);

console.log(`\nUI 冒烟结果: ${pass} 通过, ${fail} 失败`);
await browser.close();
process.exit(fail ? 1 : 0);
