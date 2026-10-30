/**
 * TTS 桥接入测试（Playwright + Chromium，无头）
 * 前置：MODOU_FIXTURES=1 npm start
 *
 * 目的：验证「App 壳内走系统 TextToSpeech」这条链路的前端部分——
 *   1. 引擎还在 init 时点朗读，必须等就绪而不是直接报「没有语音」
 *   2. 逐句推进：ttsSpeak 被调用、__modouTtsDone 回来才说下一句
 *   3. 暂停/继续/上一句/下一句/退出 不卡队列
 *   4. 引擎失败（no_engine）时给出原因面板 + 一键跳系统语音设置
 *   5. ttsSpeak 返回 false（引擎掉线）时必须自己收尾，不能卡在「正在朗读」
 */
import { createRequire } from 'node:module';

const require = createRequire('/root/.nvm/versions/node/v22.13.1/lib/node_modules/');
const { chromium } = require('playwright');

const BASE = 'http://127.0.0.1:8080';
// 专用长章 fixture：朗读用例要跑暂停/继续/上下一句，短章（2 段）三两句就播完了
const TXT = '/workspace/modou/test/fixtures/local/tts-long.txt';

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

/** 注入假桥：mode=ready 时 init 800ms 后变 ready；mode=noengine 永远失败 */
const stubBridge = (mode) => `
(() => {
  const mode = ${JSON.stringify(mode)};
  let state = mode === 'noengine' ? 'no_engine' : 'init';
  const log = [];
  window.__ttsLog = log;
  window.__openedSettings = false;
  const push = (s) => { state = s; if (window.__modouTtsState) window.__modouTtsState(s); };
  window.ModouBridge = {
    ttsState: () => state,
    ttsAvailable: () => state === 'ready',
    ttsRetry: () => { push(mode === 'noengine' ? 'no_engine' : 'ready'); },
    ttsSpeak: (text, rate, id) => {
      if (state !== 'ready') return false;
      log.push({ text, rate, id });
      setTimeout(() => { if (window.__modouTtsDone) window.__modouTtsDone(id); }, 350);
      return true;
    },
    ttsStop: () => {},
    ttsOpenSettings: () => { window.__openedSettings = true; return true; },
    ttsEngineName: () => 'com.test.tts',
    isSystemDark: () => false,
    setAppTheme: () => {},
    setImmersive: () => {},
    setEditing: () => {},
    setReaderMode: () => {},
    shareText: () => {},
    saveFile: () => {},
    appVersion: () => '0.0.0-tts-test',
  };
  if (mode !== 'noengine') setTimeout(() => { if (state === 'init') push('ready'); }, 800);
})();
`;

const browser = await chromium.launch(process.env.PW_CHROME ? { executablePath: process.env.PW_CHROME } : {});

/** 导入本地 TXT 并进入阅读页 */
async function openReader(page) {
  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForSelector('#btn-local-empty, .book-card', { timeout: 20000 });
  if (!(await page.locator('.book-card').count())) {
    const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click('#btn-local-empty')]);
    (chooser.acceptFiles || chooser.setFiles).call(chooser, [TXT]);
    await page.waitForSelector('.book-card', { timeout: 20000 });
  }
  await page.locator('.book-card').first().click();
  await page.waitForSelector('#r-body .content, #r-body .page', { timeout: 20000 });
  // 阅读页默认沉浸：chrome 隐藏时点正文中部唤出顶栏/工具坞
  const hidden = await page.evaluate(() => document.querySelector('#view')?.classList.contains('chrome-hidden'));
  if (hidden) {
    const box = await page.locator('#rd-stage').boundingBox();
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  }
  await page.waitForSelector('#r-tts', { timeout: 8000 });
  await page.waitForFunction(() => !document.querySelector('#view')?.classList.contains('chrome-hidden'), null, {
    timeout: 8000,
  });
}

/* ========== 场景 1：引擎 init → ready，点朗读要等就绪后正常逐句推进 ========== */
{
  const ctx = await browser.newContext();
  await ctx.addInitScript(stubBridge('ready'));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await openReader(page);

  // 工具坞「听书」：点一下就直接开念，不再先弹面板等二次确认
  await page.evaluate(() => document.querySelector('#r-tts').click());
  await page.waitForTimeout(450);
  const sheetCount = await page.locator('.sheet').count();
  check('听书：点一下直接开念（不弹二次确认面板）', sheetCount === 0, `sheets=${sheetCount}`);
  // 点击时引擎还在 init（800ms 后才 ready）：控制条应在就绪后出现，而不是弹「朗读不可用」
  const barShown = await page
    .waitForFunction(() => {
      const b = document.querySelector('#tts-bar');
      return !!b && !b.hidden;
    }, null, { timeout: 10000 })
    .then(() => true)
    .catch(() => false);
  const badSheet = await page.locator('.sheet-head', { hasText: '朗读不可用' }).count();
  check('引擎 init 中开始朗读：等就绪后出控制条', barShown && badSheet === 0);

  // 开念后自动进入沉浸（底部只剩「暂停播放」），点中部唤出工具坞才能操作完整控制条
  const rbox = await page.locator('#rd-stage').boundingBox();
  await page.mouse.click(rbox.x + rbox.width / 2, rbox.y + rbox.height / 2);
  await page.waitForTimeout(600);

  await page.waitForFunction(() => (window.__ttsLog || []).length >= 2, null, { timeout: 6000 }).catch(() => {});
  const spoken = await page.evaluate(() => (window.__ttsLog || []).map((x) => x.text));
  check('逐句朗读：ttsSpeak 连续入队', spoken.length >= 2, `spoken=${spoken.length}`);
  check('逐句朗读：句子非空且不重复整章', spoken[0] && spoken[0].length > 0 && spoken[0] !== spoken[1], `${spoken[0]}`);
  const rate = await page.evaluate(() => (window.__ttsLog || [])[0]?.rate);
  check('语速透传原生（0.8/1.0/1.2 档）', [0.8, 1, 1.2].includes(rate), `rate=${rate}`);
  check('当前朗读句高亮', (await page.locator('#r-body .tts-hl').count()) >= 1);
  check('进度文案（第 N / M 句）', /第 \d+ \/ \d+ 句/.test(await page.locator('#tts-now').textContent()));

  // 暂停：计数应停住
  await page.click('#tts-toggle');
  const n1 = await page.evaluate(() => (window.__ttsLog || []).length);
  await page.waitForTimeout(500);
  const n2 = await page.evaluate(() => (window.__ttsLog || []).length);
  check('暂停：不再推进', n1 === n2, `${n1}→${n2}`);
  check('暂停：按钮变「继续」', /继续/.test(await page.locator('#tts-toggle').textContent()));
  await page.click('#tts-toggle');
  await page.waitForTimeout(700);
  const n3 = await page.evaluate(() => (window.__ttsLog || []).length);
  check('继续：恢复推进', n3 > n2, `${n2}→${n3}`);

  // 下一句 / 上一句
  const before = await page.evaluate(() => (window.__ttsLog || []).length);
  await page.click('#tts-next');
  await page.waitForTimeout(600);
  check('下一句：重新入队', (await page.evaluate(() => (window.__ttsLog || []).length)) > before);
  await page.click('#tts-prev');
  await page.waitForTimeout(600);
  check('上一句：不崩且仍在推进', (await page.evaluate(() => (window.__ttsLog || []).length)) > before);
  check('朗读中无 JS 报错', errors.length === 0, errors.join(' | '));

  // 退出
  await page.click('#tts-exit');
  check('退出：控制条隐藏', await page.locator('#tts-bar').isHidden());
  const n4 = await page.evaluate(() => (window.__ttsLog || []).length);
  await page.waitForTimeout(300);
  check('退出后不再朗读', (await page.evaluate(() => (window.__ttsLog || []).length)) === n4);

  await ctx.close();
}

/* ========== 场景 2：引擎不可用 → 原因面板 + 跳系统语音设置 ========== */
{
  const ctx = await browser.newContext();
  await ctx.addInitScript(stubBridge('noengine'));
  const page = await ctx.newPage();
  await openReader(page);

  await page.evaluate(() => document.querySelector('#r-tts').click());
  await page.waitForSelector('.sheet-head', { timeout: 10000 }).catch(() => {});
  const title = await page.locator('.sheet-head').textContent().catch(() => '');
  check('引擎不可用：直接开念失败 → 弹出原因面板', /朗读不可用/.test(title), title);
  const body = await page.locator('.sheet-body').textContent();
  check('引擎不可用：说明是「没有引擎」而非笼统提示', /没有可用的语音合成引擎/.test(body), body.slice(0, 40));
  check('引擎不可用：显示当前引擎名（便于定位）', /com\.test\.tts/.test(body));
  check('引擎不可用：不出控制条', await page.locator('#tts-bar').isHidden());

  await page.locator('.sheet-body .btn.primary', { hasText: '打开系统语音设置' }).click();
  check('一键跳系统语音设置', await page.evaluate(() => window.__openedSettings === true));

  await ctx.close();
}

/* ========== 场景 3：朗读中引擎掉线（ttsSpeak 返回 false）→ 自动收尾 ========== */
{
  const ctx = await browser.newContext();
  await ctx.addInitScript(
    stubBridge('ready').replace(
      '(text, rate, id) => {\n      if (state !== \'ready\') return false;',
      "(text, rate, id) => {\n      if (log.length >= 1) return false;\n      if (state !== 'ready') return false;",
    ),
  );
  const page = await ctx.newPage();
  await openReader(page);
  await page.evaluate(() => document.querySelector('#r-tts').click());
  await page.waitForTimeout(1600);
  check('引擎中途失联：自动退出朗读，不卡在「正在朗读」', await page.locator('#tts-bar').isHidden());

  await ctx.close();
}

await browser.close();
console.log(`\nTTS 桥测试：${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);
