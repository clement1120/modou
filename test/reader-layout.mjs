/**
 * 阅读页版式/朗读浮层回归（Playwright + Chromium，无头）
 * 前置：MODOU_FIXTURES=1 npm start
 *
 * 针对实机反馈的三个问题：
 *   1. 「最底下文字太低」——滚动模式正文底部只有 22px 且不含 safe-area，最后一行贴屏幕底
 *   2. 「朗读条和正文冲突」——TTS 控制条是浮层，正文没给它让位，窄屏按钮还被压成小点
 *   3. 「上下滑动翻到章节末划不动」——滚动模式章末没有任何续章出口
 */
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';

const require = createRequire('/root/.nvm/versions/node/v22.13.1/lib/node_modules/');
const { chromium } = require('playwright');

const BASE = 'http://127.0.0.1:8080';
const TXT = '/workspace/modou/test/fixtures/local/reader-long.txt';

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

/** 假桥：引擎就绪；回调拖到 8s，避免用例跑一半整章播完自动退出 */
const STUB = `
(() => {
  let state = 'ready';
  window.__ttsLog = [];
  const base = {
    ttsState: () => state,
    ttsAvailable: () => state === 'ready',
    ttsRetry: () => { state = 'ready'; },
    ttsSpeak: (t, r, id) => { window.__ttsLog.push({ id, text: t }); setTimeout(() => window.__modouTtsDone && window.__modouTtsDone(id), 8000); return true; },
    ttsStop: () => {}, ttsPause: () => {}, ttsResume: () => {}, ttsSetRate: () => {},
    ttsOpenSettings: () => true, ttsEngineName: () => 'com.test.tts',
    isSystemDark: () => false, setAppTheme: () => {}, setImmersive: () => {},
    setEditing: () => {}, setReaderMode: () => {}, shareText: () => {},
    saveFile: () => {}, appVersion: () => '0.0.0-layout-test',
  };
  window.ModouBridge = new Proxy(base, { get: (t, k) => (k in t ? t[k] : () => {}) });
  /* 预分页槽位（.pg-slot）里也挂着 .pages，且不在视口里（visibility:hidden）。
     用 querySelector('#r-body .pages') 会拿到槽位里那份，断言于是全部空转 ——
     之前的「容器不位移」用例可能一直是假绿。统一走这个 helper 取真正在显示的容器。 */
  window.__AP = () =>
    [...document.querySelectorAll('#r-body .pages')].find((e) => {
      for (let n = e; n && n !== document.body; n = n.parentElement) {
        const cs = getComputedStyle(n);
        if (cs.visibility === 'hidden' || cs.display === 'none') return false;
      }
      return true;
    }) || null;
})();
`;

const browser = await chromium.launch(process.env.PW_CHROME ? { executablePath: process.env.PW_CHROME } : {});
const ctx = await browser.newContext({ hasTouch: true, viewport: { width: 390, height: 844 } });
await ctx.addInitScript(STUB);

const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));

await page.goto(BASE, { waitUntil: 'networkidle' });
await page.waitForSelector('#btn-local-empty, .book-card', { timeout: 20000 });
if (!(await page.locator('.book-card').count())) {
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click('#btn-local-empty')]);
  await chooser.setFiles([TXT]);
  await page.waitForSelector('.book-card', { timeout: 20000 });
}
await page.locator('.book-card').first().click();
await page.waitForSelector('#r-body .content, #r-body .page', { timeout: 25000 });

/**
 * 工具坞「听书」现在点一下就直接开念（不再先弹面板、再点「开始朗读」）。
 * 已经在念时再点一次才开面板调语速/定时。
 */
const startTtsViaPanel = async () => {
  await page.evaluate(() => document.querySelector('#r-tts').click());
  await page.waitForTimeout(900);
};

/* ========== 场景 1：朗读控制条与工具坞的层叠关系 ========== */
console.log('\n[分页模式] 朗读控制条与工具坞');
const pageInfo = () =>
  page.evaluate(() => {
    const pages = [...document.querySelectorAll('#r-body > .pages > .page')];
    const stage = document.querySelector('#rd-stage').getBoundingClientRect();
    const kids = pages[0] ? [...pages[0].querySelectorAll('.pg-body > *')] : [];
    const last = kids[kids.length - 1];
    return {
      pages: pages.length,
      stageBottom: Math.round(stage.bottom),
      lastBottom: last ? Math.round(last.getBoundingClientRect().bottom) : null,
    };
  });

/** 整章逐页快照：页码不一定是唯一依据（重排后页数可能凑巧相同），要看每页装的文字 */
const pagesSnap = () =>
  page.evaluate(() =>
    [...document.querySelectorAll('#r-body .pages .page')].map((p) => {
      const b = p.querySelector('.pg-body');
      return (b ? b.textContent : '').slice(0, 16);
    })
  );

const pBefore = await pageInfo();
const snapBefore = await pagesSnap();
await startTtsViaPanel();
await page.waitForTimeout(700);
check('开始朗读：控制条出现', await page.evaluate(() => {
  const b = document.querySelector('#tts-bar');
  return !!b && !b.hidden;
}));
check(
  '朗读中：页脚仍然显示（不再为横条让位）',
  await page.evaluate(() => {
    const f = document.querySelector('#r-body .page .pg-foot');
    return !!f && getComputedStyle(f).visibility !== 'hidden' && f.getBoundingClientRect().height > 4;
  }),
);
const gap = await page.evaluate(() => {
  const p = document.querySelector('#r-body .pages .page');
  const kids = [...p.querySelectorAll('.pg-body > *')];
  const last = kids[kids.length - 1];
  const lb = document.querySelector('#listen-bar');
  return {
    lastBottom: Math.round(last.getBoundingClientRect().bottom),
    barTop: Math.round(lb.getBoundingClientRect().top),
    pad: parseFloat(getComputedStyle(p).paddingBottom),
  };
});
check('正文末行在横条之上（横条落在页脚那一行，不压字）', gap.lastBottom <= gap.barTop, JSON.stringify(gap));
// 页脚改成「左页码 / 中间横条 / 右时间」同一行：三者中垂线对齐，横条夹在左右文字之间
const footRow = await page.evaluate(() => {
  const f = document.querySelector('#r-body .page .pg-foot');
  const lb = document.querySelector('#listen-bar');
  const sides = [...f.querySelectorAll('.pg-side')].map((s) => s.getBoundingClientRect());
  const r = lb.getBoundingClientRect();
  const fr = f.getBoundingClientRect();
  const mid = (b) => (b.top + b.bottom) / 2;
  return {
    sameLine: Math.abs(mid(r) - mid(fr)) <= 6,
    leftOk: sides[0] && sides[0].left < r.left - 4,
    rightOk: sides[1] && sides[1].right > r.right + 4,
    noOverlap: sides.every((b) => b.right <= r.left + 1 || b.left >= r.right - 1),
    texts: [...f.querySelectorAll('.pg-side')].map((s) => s.textContent),
  };
});
check('页脚：左页码 / 中间横条 / 右时间 在同一行', footRow.sameLine, JSON.stringify(footRow));
check('页脚：左右两项都贴边（不再堆在左下角）', footRow.leftOk && footRow.rightOk, JSON.stringify(footRow));
check('页脚：横条不与左右文字重叠', footRow.noOverlap, JSON.stringify(footRow));
check('页脚：默认带时间（右侧）', footRow.texts.length === 2 && /^\d{2}:\d{2}$/.test((footRow.texts[1] || '').trim()), footRow.texts.join('|'));

// 唤出工具坞：控制条应叠在它上方
const rbox = await page.locator('#rd-stage').boundingBox();
await page.mouse.click(rbox.x + rbox.width / 2, rbox.y + rbox.height / 2);
await page.waitForTimeout(800);
const lay = await page.evaluate(() => {
  const inPanel = !!document.querySelector('.rd-panel #tts-bar');
  // 同一行内比较，避免把上下两行的按钮误判为重叠
  let overlap = false;
  for (const row of document.querySelectorAll('#tts-bar .tts-top, #tts-bar .tts-ctl')) {
    const btns = [...row.querySelectorAll('button')].map((b) => b.getBoundingClientRect());
    for (let i = 0; i < btns.length; i++) {
      for (let j = i + 1; j < btns.length; j++) {
        if (btns[i].left < btns[j].right - 1 && btns[j].left < btns[i].right - 1) overlap = true;
      }
    }
  }
  const all = [...document.querySelectorAll('#tts-bar button')].map((b) => b.getBoundingClientRect().height);
  return {
    inPanel,
    rows: document.querySelectorAll('#tts-bar .tts-top, #tts-bar .tts-ctl').length,
    // 与工具坞同一套字号/图标：说明文字 12px、按钮 11.5px、图标 20px
    sizes: (() => {
      const cs = (sel) => (document.querySelector(sel) ? parseFloat(getComputedStyle(document.querySelector(sel)).fontSize) : NaN);
      const ico = (sel) => {
        const el = document.querySelector(sel);
        return el ? Math.round(el.getBoundingClientRect().width) : NaN;
      };
      return {
        now: cs('#tts-now'),
        meta: cs('.rd-prog-meta'),
        chip: cs('#tts-rate'),
        mini: cs('.rd-mini'),
        lbl: cs('#tts-toggle-lbl'),
        actLbl: cs('.rd-act em'),
        ico: ico('#tts-toggle svg'),
        dockIco: ico('.rd-act svg'),
      };
    })(),
    overlap,
    minBtnH: Math.min(...all),
  };
});
check('控制条嵌在工具坞面板内（同一张卡片）', lay.inPanel);
check('朗读条：固定两行布局', lay.rows === 2, `rows=${lay.rows}`);
check('朗读条：同一行内按钮不重叠', !lay.overlap);
// chip 高度与工具坞 .rd-mini 同源（11.5px 字号 + 上下 5px），不是被压扁
check('朗读条：按钮未被压扁（高≥22）', lay.minBtnH >= 22, `minH=${lay.minBtnH}`);
check(
  '朗读条：字号与工具坞一致（说明 12 / 按钮 11.5）',
  Math.abs(lay.sizes.now - lay.sizes.meta) < 0.6 && Math.abs(lay.sizes.chip - lay.sizes.mini) < 0.6,
  JSON.stringify(lay.sizes),
);
check('朗读条：图标尺寸与工具坞一致（20px）', lay.sizes.ico === lay.sizes.dockIco, `tts=${lay.sizes.ico} dock=${lay.sizes.dockIco}`);
check('朗读段落：按段落入队而非整页', (await page.evaluate(() => (window.__ttsLog || []).length)) >= 1);

// 退出朗读
await page.evaluate(() => document.querySelector('#tts-exit').click());
await page.waitForTimeout(900);
check('退出朗读：控制条隐藏', await page.locator('#tts-bar').isHidden());
check('退出朗读：页数不变', (await pageInfo()).pages === pBefore.pages);
// 横条现在占的是页脚中间那一格，不再单独吃一行 —— 开关朗读不应改变分页
// 横条占的是页脚中间那格，版心必须毫无变化 —— 页数相同还不够，每页装的文字也要一字不差
check('开关朗读：分页完全不变（横条不额外占行）', (await pageInfo()).pages === pBefore.pages, `${pBefore.pages} → ${(await pageInfo()).pages}`);
const snapAfter = await pagesSnap();
check(
  '开关朗读：逐页文字完全不变（当前页与前面几页都不挪位）',
  snapBefore.length === snapAfter.length && snapBefore.every((t, i) => t === snapAfter[i]),
  `${snapBefore.length} vs ${snapAfter.length}; 首处差异=${
    snapBefore.findIndex((t, i) => t !== snapAfter[i]) < 0
      ? '无'
      : `第${snapBefore.findIndex((t, i) => t !== snapAfter[i]) + 1}页「${snapBefore[snapBefore.findIndex((t, i) => t !== snapAfter[i])]}」→「${
          snapAfter[snapBefore.findIndex((t, i) => t !== snapAfter[i])]
        }」`
  }`,
);

/* ========== 场景 2：朗读从当前页开始 + 翻页/跨章后的入口 ========== */
console.log('\n[分页模式] 朗读起点与「从本页开始」');
// 场景 1 已经朗读过一轮，日志里留着旧记录，先清空再测起点
await page.evaluate(() => { window.__ttsLog = []; });
const pageFirstText = (i) =>
  page.evaluate((idx) => {
    const p = document.querySelectorAll('#r-body > .pages > .page')[idx];
    const el = p ? [...p.querySelectorAll('.pg-body > *')].find((x) => x.textContent.trim()) : null;
    return el ? el.textContent.trim() : '';
  }, i);
const t1 = await pageFirstText(0);
const t2 = await pageFirstText(1);
const paraNo = (s) => {
  const m = String(s).match(/第(\d+)段/);
  return m ? +m[1] : -1;
};
/** 当前页正文第一段（重排后页码内容会变，必须现场取） */
const curPageFirst = () =>
  page.evaluate(() => {
    const m = (document.querySelector('#r-meta-page')?.textContent || '').match(/第 (\d+)/);
    const idx = m ? +m[1] - 1 : 0;
    const p = document.querySelectorAll('#r-body > .pages > .page')[idx];
    // 章题会被塞进第一页的 pg-body，但朗读会过滤掉它，这里也要跳过
    const el = p
      ? [...p.querySelectorAll('.pg-body > *')].find(
          (x) => !x.classList.contains('chapter-title') && x.textContent.trim(),
        )
      : null;
    return el ? el.textContent.trim() : '';
  });

const pbox = await page.locator('#rd-stage').boundingBox();
await page.mouse.click(pbox.x + pbox.width * 0.85, pbox.y + pbox.height / 2); // 翻到第 2 页
await page.waitForTimeout(500);
check('已翻到第 2 页', (await page.locator('#r-meta-page').textContent()).includes('2 /'));

await startTtsViaPanel();
await page.waitForTimeout(400);
const spokenFirst = await page.evaluate(() => (window.__ttsLog || [])[0]?.text || '');
const wantFirst = await curPageFirst();
// 重排后这一页可能从上一段的后半截开始（分页是按行切，不按段切），
// 所以按「文字前缀一致 + 不是章首」判断，而不是靠段落编号。
check(
  '朗读从当前页开始（不是章首）',
  !!spokenFirst &&
    wantFirst.startsWith(spokenFirst.slice(0, 10)) &&
    spokenFirst !== t1 &&
    spokenFirst.slice(0, 6) !== t1.slice(0, 6),
  `spoken=${spokenFirst.slice(0, 12)} 当前页首段=${wantFirst.slice(0, 12)} 章首=${t1.slice(0, 12)}`,
);

// 底部小横条：在本页时是「暂停播放」，翻到别页就变「从此页开始听」
const listenText = () => page.evaluate(() => document.querySelector('#listen-bar span')?.textContent || '');
check('朗读且在本页：小横条显示「暂停播放」', /暂停/.test(await listenText()), await listenText());

await page.mouse.click(pbox.x + pbox.width * 0.15, pbox.y + pbox.height / 2);
await page.waitForTimeout(500);
check('翻到别页：小横条变「从此页开始听」', /从此页开始听/.test(await listenText()), await listenText());

await page.evaluate(() => document.querySelector('#listen-bar').click());
await page.waitForTimeout(900);
const spokenBack = await page.evaluate(() => {
  const l = window.__ttsLog || [];
  return l[l.length - 1]?.text || '';
});
const wantBack = await curPageFirst();
check(
  '点小横条：从当前页重新开始',
  paraNo(spokenBack) > 0 && paraNo(spokenBack) === paraNo(wantBack),
  `spoken=${spokenBack.slice(0, 12)} 当前页首段=${wantBack.slice(0, 12)}`,
);
check('重新开始后：小横条回到「暂停播放」', /暂停/.test(await listenText()), await listenText());

// 小横条右侧的叉：整段退出听书（不是暂停）
check('朗读中：小横条右侧有退出叉', await page.locator('#listen-x').isVisible());
await page.evaluate(() => document.querySelector('#listen-x').click());
await page.waitForTimeout(800);
check('点叉退出朗读：小横条隐藏（不占底部）', await page.locator('#listen-bar').isHidden());
check(
  '点叉退出朗读：控制条一起收起',
  await page.evaluate(() => {
    const b = document.querySelector('#tts-bar');
    return !b || b.hidden;
  }),
);

// 跨章：朗读停但不能静默消失
await startTtsViaPanel();
await page.waitForTimeout(900);
const beforeJump = await page.evaluate(() => (window.__ttsLog || []).length);
check('重新开始朗读：小横条回到「暂停播放」', /暂停/.test(await listenText()), await listenText());
await page.evaluate(() => document.querySelector('#r-next').click());
await page.waitForTimeout(1400);
check('跨章后：朗读停下，小横条隐藏', await page.locator('#listen-bar').isHidden());
await page.evaluate(() => document.querySelector('#listen-bar').click());
await page.waitForTimeout(900);
check('跨章后点小横条：新章开始朗读', (await page.evaluate(() => (window.__ttsLog || []).length)) > beforeJump);

/* ========== 场景 3：滚动模式底部留白 + 章末续章 ========== */
console.log('\n[滚动模式] 底部留白与章末续章');
// 场景 2 把书翻到了末章（只有一段，凑不出滚动），先回第一章再测
const prevInfo = await page.evaluate(() => {
  const b = document.querySelector('#r-prev');
  if (!b) return { exists: false };
  b.click();
  return { exists: true, disabled: b.disabled };
});
await page.waitForTimeout(1400);
check('已切回第一章', (await page.locator('#r-meta-chap').textContent()).includes('第 1 /'), `${JSON.stringify(prevInfo)} ${await page.locator('#r-meta-chap').textContent()}`);
let box = await page.locator('#rd-stage').boundingBox();
await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
await page.waitForTimeout(700);
await page.evaluate(() => document.querySelector('#r-typo').click());
await page.waitForTimeout(500);
await page.evaluate(() => {
  const b = [...document.querySelectorAll('.seg button')].find((x) => x.textContent.trim() === '上下滚动');
  if (b) b.click();
});
await page.waitForTimeout(1200);
check('已切到上下滚动', !(await page.evaluate(() => document.body.classList.contains('reader-paged'))));

const scrollPad = () =>
  page.evaluate(() => ({
    pad: parseFloat(getComputedStyle(document.querySelector('#r-body')).paddingBottom) || 0,
    reserve: parseFloat(getComputedStyle(document.querySelector('#rd-shell')).getPropertyValue('--tts-reserve')) || 0,
  }));
const sBase = await scrollPad();
check('滚动模式：底部留白 ≥ 24px（含安全区变量）', sBase.pad >= 24, `pad=${sBase.pad}`);

// 未朗读时不显示小横条，入口是工具坞「听书」
check('未开听书：底部不显示小横条', await page.locator('#listen-bar').isHidden());
await startTtsViaPanel();
await page.waitForTimeout(900);
const sTts = await scrollPad();
const sDiag = await page.evaluate(() => ({
  barHidden: document.querySelector('#tts-bar')?.hidden,
  ttsBtn: !!document.querySelector('#r-tts'),
  listenHidden: document.querySelector('#listen-bar')?.hidden,
  listenText: document.querySelector('#listen-bar span')?.textContent || '',
  toast: document.querySelector('#toast')?.textContent || '',
}));
check(
  '滚动模式：朗读时文末多留白（横条不压最后一行）',
  sDiag.barHidden === false && sTts.pad > sBase.pad,
  `${JSON.stringify(sTts)} ${JSON.stringify(sDiag)}`,
);
await page.evaluate(() => document.querySelector('#tts-exit').click());
await page.waitForTimeout(700);

// 章末：滚到底出提示，继续上滑切下一章
const hash0 = await page.evaluate(() => location.hash);
await page.evaluate(() => {
  const s = document.querySelector('#rd-stage');
  s.scrollTop = s.scrollHeight;
});
await page.waitForTimeout(500);
const endToast = (await page.locator('#toast').textContent().catch(() => '')) || '';
check('滚动模式：滚到章末提示续章', /本章末尾/.test(endToast), endToast);

await page.evaluate(() => {
  const s = document.querySelector('#rd-stage');
  const mk = (type, y) => {
    const t = new Touch({ identifier: 1, target: s, clientX: 200, clientY: y });
    return new TouchEvent(type, {
      touches: type === 'touchend' ? [] : [t],
      changedTouches: [t],
      bubbles: true,
      cancelable: true,
    });
  };
  s.dispatchEvent(mk('touchstart', 760));
  for (let y = 760; y >= 520; y -= 40) s.dispatchEvent(mk('touchmove', y));
  s.dispatchEvent(mk('touchend', 520));
});
await page.waitForTimeout(1600);
const afterScroll = await page.evaluate(() => ({
  hash: location.hash,
  chap: document.querySelector('#r-chap')?.textContent || '',
  top: document.querySelector('#rd-stage')?.scrollTop ?? -1,
}));
check('滚动模式：章末继续上滑进入下一章', afterScroll.hash !== hash0, `${hash0} → ${afterScroll.hash}`);
check('滚动模式：新章从顶部开始', afterScroll.top === 0, `top=${afterScroll.top}`);
check('滚动模式：章名已更新', /破晓/.test(afterScroll.chap), afterScroll.chap);

/* ========== 场景 4：长按划词，版面必须留在原位 ==========
 * 实机反馈：「长按复制，选的字多一点，它就自动进入某个状态，恢复不了，得退出去再进来」。
 * 现在选区是自绘的（正文 user-select:none，系统选区根本起不来），长按只改选区数据、
 * 不碰 transform。这里仍要盯住「长按 + 扩选」全程 transform 是不是整格。
 */
console.log('\n[分页模式] 长按划词后版面不位移');
await page.evaluate(() => document.querySelector('#r-typo').click());
await page.waitForTimeout(500);
await page.evaluate(() => {
  const b = [...document.querySelectorAll('.seg button')].find((x) => x.textContent.trim() === '左右翻页');
  if (b) b.click();
});
await page.waitForTimeout(1400);
await page.locator('.sheet .dialog-x, .sheet .sheet-x').first().click().catch(() => {});
await page.waitForTimeout(400);
check('已切回左右翻页', await page.evaluate(() => document.body.classList.contains('reader-paged')));
// 回到第一章：长章才有「翻页」可测（末章只剩一页）
const goFirst = await page.evaluate(() => {
  const b = document.querySelector('#r-prev');
  if (!b || b.disabled) return false;
  b.click();
  return true;
});
if (goFirst) {
  await page.waitForTimeout(1600);
  await page.evaluate(() => {
    const b = document.querySelector('#r-prev');
    if (b && !b.disabled) b.click();
  });
  await page.waitForTimeout(1600);
}
const firstPage = await page.locator('#r-meta-page').textContent();
check('已回到多页章节', /^第 1 \/ [2-9]|^第 1 \/ \d\d/.test(firstPage || ''), firstPage);

const cdpHold = await ctx.newCDPSession(page);
/** 取当前页某段落的第 n 行的中心点（viewport 坐标） */
const linePoint = (lineNo = 1) =>
  page.evaluate((n) => {
    const p = window.__AP();
    const st = document.querySelector('#rd-stage').getBoundingClientRect();
    const pg = [...p.querySelectorAll('.page')]
      .map((e) => ({ e, r: e.getBoundingClientRect() }))
      .sort((a, b) => Math.abs(a.r.left - st.left) - Math.abs(b.r.left - st.left))[0].e;
    if (!pg) return null;
    const body = pg.querySelector('.pg-body');
    const par = [...body.querySelectorAll('p')].filter((x) => x.textContent.trim().length > 30)[0];
    if (!par) return null;
    const rg = document.createRange();
    rg.selectNodeContents(par);
    const rects = [...rg.getClientRects()].filter((r) => r.height > 4);
    const r = rects[Math.min(n, rects.length - 1)];
    if (!r) return null;
    return {
      x: Math.round(r.left + Math.min(40, r.width * 0.3)),
      y: Math.round(r.top + r.height / 2),
      lh: Math.round(rects[0].height),
      bodyLen: body.textContent.length,
      lines: rects.length,
    };
  }, lineNo);

const s4at = await linePoint(1);
await page.evaluate(() => window.__sel.clear());
await page.waitForTimeout(80);
const s4page0 = await page.locator('#r-meta-page').textContent();
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: s4at.x, y: s4at.y }] });
await page.waitForTimeout(620);
const s4a = await page.evaluate(() => ({
  has: window.__sel.has(),
  len: window.__sel.len(),
  text: window.__sel.text(),
  tf: window.__AP().style.transform,
}));
// 按住不动：不用抬手，工具条自己就该浮出来（系统也是这个节奏）
await page.waitForTimeout(280);
const s4barDown = await page.evaluate(() => document.querySelector('.sel-bar').classList.contains('show'));
check('按住不动：工具条在手指还按着时就浮出来', s4barDown === true, String(s4barDown));
// 手指继续往下拖两行：选区跟着走，版面一动不动，菜单自动让开
for (let i = 1; i <= 8; i++) {
  await cdpHold.send('Input.dispatchTouchEvent', {
    type: 'touchMove',
    touchPoints: [{ x: s4at.x + i * 3, y: s4at.y + (i * s4at.lh * 2) / 8 }],
  });
  await page.waitForTimeout(24);
}
const s4b = await page.evaluate(() => ({
  len: window.__sel.len(),
  text: window.__sel.text(),
  bodyLen: window.__sel.bodyLen(),
  tf: window.__AP().style.transform,
  bar: document.querySelector('.sel-bar').classList.contains('show'),
}));
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
await page.waitForTimeout(280);
const s4c = await page.evaluate(() => ({
  tf: window.__AP().style.transform,
  bar: document.querySelector('.sel-bar').classList.contains('show'),
  page: document.querySelector('#r-meta-page').textContent,
}));
check('长按 600ms：锚定最小选区（不是整段）', s4a.has && s4a.len >= 1 && s4a.len <= 8, JSON.stringify(s4a));
check('长按全程：容器没有跟手位移', /^translateX\(-?\d+%\)$/.test(s4a.tf), s4a.tf);
check('长按 + 拖两行：容器仍然没有位移', /^translateX\(-?\d+%\)$/.test(s4b.tf), s4b.tf);
check('拖动调整范围时：工具条自动收起（不挡着选字）', s4b.bar === false, String(s4b.bar));
check(
  '长按 + 拖两行：选区只有两行左右（不是整页剩下的全部）',
  s4b.len > s4a.len && s4b.len < s4b.bodyLen * 0.6,
  JSON.stringify({ a: s4a.len, b: s4b.len, bodyLen: s4b.bodyLen, text: s4b.text }),
);
check('长按划词：没有误翻页', s4c.page === s4page0, `${s4page0} → ${s4c.page}`);
check('松手后：选词工具条出现', s4c.bar === true, JSON.stringify(s4c));
// 点空白处：收选区，且这一下不翻页
await page.mouse.click(195, 300);
await page.waitForTimeout(240);
const s4d = await page.evaluate(() => ({ has: window.__sel.has(), page: document.querySelector('#r-meta-page').textContent }));
check('点按空白处：收掉选区', !s4d.has, JSON.stringify(s4d));
check('点按收选区那一下：不翻页', s4d.page === s4page0, `${s4page0} → ${s4d.page}`);

/* ========== 场景 4b：按住不动再拖选区手柄（实机「字选多了 + 卡在半格」） ==========
 * 实机时序：手指按住约 500ms，系统起选区；随后用户拖手柄继续扩选。
 * 老代码在按住阶段不交出手势，拖手柄的横向位移被当翻页跟手 → 容器停在半格；
 * 而 .dragging 带来的 user-select:none 会让 Chrome 把进行中的选区撑成整段（越选越多）。
 * 断言：按住后不再有 dragging 态、拖手柄期间容器零位移、没有误翻页。
 */
console.log('\n[分页模式] 长按按住不动 + 拖选区手柄');
const holdAt = await page.evaluate(() => {
  const el = window.__AP();
  const r = el.getBoundingClientRect();
  return { x: r.x + r.width * 0.5, y: r.y + r.height * 0.5 };
});
const pageNoHold0 = await page.locator('#r-meta-page').textContent();
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: holdAt.x, y: holdAt.y }] });
await page.waitForTimeout(420); // 按住不动：系统在此起选区
const holdMid = await page.evaluate(() => {
  const p = window.__AP();
  return { tf: p.style.transform, dragging: p.classList.contains('dragging') };
});
for (let i = 1; i <= 8; i++) {
  // 拖选区手柄：斜向大幅拖动（既有横向也有纵向，和真人扩选一致）
  await cdpHold.send('Input.dispatchTouchEvent', {
    type: 'touchMove',
    touchPoints: [{ x: holdAt.x + i * 14, y: holdAt.y + i * 10 }],
  });
  await page.waitForTimeout(20);
}
const dragSelState = await page.evaluate(() => {
  const p = window.__AP();
  const body = p.querySelector('.page .pg-body');
  return {
    tf: p.style.transform,
    dragging: p.classList.contains('dragging'),
    us: body ? getComputedStyle(body).userSelect : '',
  };
});
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
await page.waitForTimeout(380);
const afterHold = await page.evaluate(() => {
  const p = window.__AP();
  return { tf: p.style.transform, dragging: p.classList.contains('dragging') };
});
const pageNoHold1 = await page.locator('#r-meta-page').textContent();
check('长按不动：手势已交还选区（不残留 dragging）', !holdMid.dragging && !dragSelState.dragging, JSON.stringify({ holdMid, dragSelState }));
check(
  '长按拖选区：容器全程不位移',
  /^translateX\(-?\d+%\)$/.test(dragSelState.tf) && /^translateX\(-?\d+%\)$/.test(afterHold.tf),
  `${holdMid.tf} → ${dragSelState.tf} → ${afterHold.tf}`,
);
check('长按拖选区：没有误翻页', pageNoHold1 === pageNoHold0, `${pageNoHold0} → ${pageNoHold1}`);
// 触摸拖拽期间正文一直 user-select:none：系统选区压根不会出现，
// 也就没有「选区被撑到块级边界」这回事。
check(
  '触摸拖拽期间不挂 user-select（系统选区根本起不来，无从撑成整段）',
  dragSelState.us === 'none' && !dragSelState.dragging,
  JSON.stringify(dragSelState),
);

/* ========== 场景 4b-2：按住 250ms 后「慢慢拖」 ==========
 * 长按起选区的判定是「按住 460ms 不动」。250ms 就开始拖、且速度 0.4px/ms 的，
 * 是「想翻一页」而不是「想划词」——这类手势必须照常跟手翻页，
 * 不能因为长按逻辑存在就卡住不动（那是另一个方向的故障）。
 * 断言：拖的过程有跟手位移，松手后要么翻页、要么干净回弹，不留半格。
 */
console.log('\n[分页模式] 按住 250ms 后慢速拖动（按翻页处理）');
await page.evaluate(() => window.__sel.clear());
await page.waitForTimeout(200);
const slowAt = await page.evaluate(() => {
  const el = window.__AP();
  const r = el.getBoundingClientRect();
  return { x: r.x + r.width * 0.6, y: r.y + r.height * 0.45 };
});
const pageNoSlow0 = await page.locator('#r-meta-page').textContent();
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: slowAt.x, y: slowAt.y }] });
await page.waitForTimeout(250);
const slowTfs = [];
for (let i = 1; i <= 14; i++) {
  await cdpHold.send('Input.dispatchTouchEvent', {
    type: 'touchMove',
    touchPoints: [{ x: slowAt.x - i * 8, y: slowAt.y + i * 2 }],
  });
  slowTfs.push(await page.evaluate(() => window.__AP().style.transform));
  await page.waitForTimeout(20);
}
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
await page.waitForTimeout(380);
const pageNoSlow1 = await page.locator('#r-meta-page').textContent();
const slowAfter = await page.evaluate(() => window.__AP().style.transform);
const movedPx = slowTfs.filter((t) => /px\)$/.test(t));
check(
  '按住 250ms 后慢拖：仍然跟手（没有被长按逻辑卡死）',
  movedPx.length > 0 || pageNoSlow1 !== pageNoSlow0,
  `${movedPx.slice(0, 3).join(' | ')} 序列=${JSON.stringify(slowTfs.slice(0, 5))}`,
);
check(
  '按住 250ms 后慢拖：松手停在整页位置（无半格残留）',
  /^translateX\(-?\d+%\)$/.test(slowAfter),
  slowAfter,
);

/* ========== 场景 4b-3：系统选区彻底关掉，选区由自绘层接管 ==========
 * 系统手柄是原生 View（那对青色圆点），样式改不了；而且手柄一拖过正文边界，
 * Chromium 就把端点钳到块边界 —— 「想选两行」会吃满整页。现在正文 user-select:none，
 * 系统选区根本起不来，选区完全由 .sel-layer 自绘。
 */
const selCss = await page.evaluate(() => {
  const pg = document.querySelector('#r-body .page');
  const head = pg.querySelector('.pg-head');
  const body = pg.querySelector('.pg-body');
  const layer = document.querySelector('.sel-layer');
  return {
    page: getComputedStyle(pg).userSelect,
    head: head ? getComputedStyle(head).userSelect : '(无页眉)',
    body: body ? getComputedStyle(body).userSelect : '',
    layer: layer ? getComputedStyle(layer).pointerEvents : '(无自绘层)',
  };
});
check('正文不参与系统选区（user-select:none）', selCss.body === 'none', JSON.stringify(selCss));
check('自绘选区层不吃触摸（pointer-events:none）', selCss.layer === 'none', JSON.stringify(selCss));

/* ========== 场景 4b-4：拖到页底之外，选区最多到正文末尾 ==========
 * 端点用「正文文本里的整数偏移」表示，越界在数据结构上就不可能 ——
 * 这条守的就是「整屏全红」不再出现。
 */
console.log('\n[分页模式] 选区范围与两端竖线');
let s5 = { err: 'no setup' };
const s5pt = await linePoint(1);
if (s5pt) {
  await page.evaluate(() => window.__sel.set(0, 4));
  await page.waitForTimeout(120);
  const hB = await page.evaluate(() => {
    const r = document.querySelector('.sel-h.b').getBoundingClientRect();
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };
  });
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: hB.x, y: hB.y }] });
  await page.waitForTimeout(40);
  for (let i = 1; i <= 12; i++) {
    await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: hB.x, y: hB.y + i * 45 }] });
    await page.waitForTimeout(20);
  }
  s5 = await page.evaluate(() => ({
    len: window.__sel.len(),
    max: window.__sel.maxLen(), // 模型里含段落之间的换行，比 textContent 略长
    tail: window.__sel.text().slice(-8),
  }));
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(200);
}
check(
  '拖到页底之外：选区到正文末尾为止，绝不超出',
  s5.len > 0 && s5.len <= s5.max && s5.len >= s5.max * 0.7,
  JSON.stringify(s5),
);

/* ========== 场景 4b-5：想选两行就只选两行 ==========
 * 实机原话：「只想选中两行或者 3 行，现在是做不到的，一选就给你整页剩下的全部内容」。
 * 这里复刻真机动作：长按选词 → 手指继续往下拖恰好两行 → 选区必须只多出这两行。
 */
let s6 = { err: 'no setup' };
const s6pt = await linePoint(1);
if (s6pt) {
  await page.evaluate(() => window.__sel.clear());
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: s6pt.x, y: s6pt.y }] });
  await page.waitForTimeout(600);
  const before = await page.evaluate(() => window.__sel.len());
  for (let i = 1; i <= 10; i++) {
    await cdpHold.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: s6pt.x + i * 2, y: s6pt.y + (i * s6pt.lh * 2) / 10 }],
    });
    await page.waitForTimeout(22);
  }
  s6 = await page.evaluate(() => ({
    len: window.__sel.len(),
    bodyLen: window.__sel.bodyLen(),
    text: window.__sel.text(),
    rects: window.__sel.rects().length,
  }));
  s6.before = before;
  s6.pageLines = Math.round((s6.len / Math.max(1, s6.bodyLen)) * s6pt.lines);
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(260);
}
check(
  '拖两行：选中范围只有两行上下（不是整页剩下的全部）',
  s6.len > s6.before && s6.rects <= 4 && s6.len < s6.bodyLen * 0.5,
  JSON.stringify(s6),
);
check('拖两行：松手后工具条重新出现（拖动过程中收起）', await page.evaluate(() => document.querySelector('.sel-bar').classList.contains('show')));

/* ========== 场景 4b-6：两端竖线的几何 ==========
 * 要的是「最前、最后各一根细竖线」：位置贴住首行左缘 / 末行右缘，高度盖住整行。
 */
const hgeo = await page.evaluate(() => {
  const rects = window.__sel.rects();
  const a = document.querySelector('.sel-h.a').getBoundingClientRect();
  const b = document.querySelector('.sel-h.b').getBoundingClientRect();
  return {
    n: rects.length,
    first: rects[0],
    last: rects[rects.length - 1],
    a: { x: Math.round(a.left + a.width / 2), top: Math.round(a.top), h: Math.round(a.height) },
    b: { x: Math.round(b.left + b.width / 2), top: Math.round(b.top), h: Math.round(b.height) },
  };
});
check(
  '起点竖线贴住首行左缘',
  Math.abs(hgeo.a.x - Math.round(hgeo.first.l)) <= 3 && Math.abs(hgeo.a.top - (Math.round(hgeo.first.t) - 2)) <= 3,
  JSON.stringify(hgeo),
);
check(
  '终点竖线贴住末行右缘',
  Math.abs(hgeo.b.x - Math.round(hgeo.last.r)) <= 3 && Math.abs(hgeo.b.top - (Math.round(hgeo.last.t) - 2)) <= 3,
  JSON.stringify(hgeo),
);
check(
  '两端竖线高度盖住整行（比行高多 4px 余量）',
  hgeo.a.h >= hgeo.first.h && hgeo.b.h >= hgeo.last.h,
  JSON.stringify(hgeo),
);

/* ========== 场景 4b-7：已有选区时，长按另一处直接改选 ==========
 * 菜单浮在正文上，用户最常见的动作是「换个地方再选一次」。原生行为是直接替换选区，
 * 不该要求先点一下把旧选区收掉。
 */
const reselect = { cur: '', next: '', len: 0 };
reselect.cur = await page.evaluate(() => window.__sel.text());
const altPt = await linePoint(3);
if (altPt) {
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: altPt.x, y: altPt.y }] });
  await page.waitForTimeout(560);
  const mid = await page.evaluate(() => ({ text: window.__sel.text(), len: window.__sel.len() }));
  reselect.next = mid.text;
  reselect.len = mid.len;
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(220);
}
check(
  '已有选区时长按另一处：直接改选成新词（不是先收起再选）',
  reselect.len >= 1 && reselect.len <= 4 && reselect.next !== reselect.cur,
  JSON.stringify(reselect),
);

/* ========== 场景 4b-8：长按默认只在手指处锚定 1 个字（不再整词抓取） ==========
 * 实机原话：「手指长按，按的范围不大，默认给我选了一堆，虽然可以调，但每次都调肯定不方便」。
 * 现在长按只在手指处锚定最小选区（1 字），真正「选多少」交给随后的拖动——
 * 手指往哪边移，选区就往哪边长。所以长按本身绝不会一下子选一大片。
 */
const wordLens = [];
for (const ln of [0, 1, 2, 3]) {
  const pt = await linePoint(ln);
  if (!pt) continue;
  await page.evaluate(() => window.__sel.clear());
  await page.waitForTimeout(60);
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: pt.x, y: pt.y }] });
  await page.waitForTimeout(600);
  wordLens.push(await page.evaluate(() => ({ len: window.__sel.len(), text: window.__sel.text() })));
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(140);
}
check(
  '长按默认锚定最小选区（1 字，不再一口气选一句）',
  wordLens.length >= 3 && wordLens.every((x) => x.len >= 1 && x.len <= 2),
  JSON.stringify(wordLens),
);
check(
  '长按默认选词是实字（不会只框住空白/换行）',
  wordLens.length >= 3 && wordLens.every((x) => x.text.trim().length > 0),
  JSON.stringify(wordLens),
);

/* ========== 场景 4b-8b：长按后拖手指，选区跟随手指增长 ==========
 * 实机要的新交互：「一按就选一大块」改为「按住锚定 1 字，拖手指才增选」。
 * 复刻：长按起选区（只应 1 字）→ 手指向右拖 → 选区明显变长，且版面不动。
 */
const growPt = await linePoint(1);
let growOK = false;
let growInfo = {};
if (growPt) {
  await page.evaluate(() => window.__sel.clear());
  await page.waitForTimeout(60);
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: growPt.x, y: growPt.y }] });
  await page.waitForTimeout(600);
  const g0 = await page.evaluate(() => ({ len: window.__sel.len(), text: window.__sel.text(), tf: window.__AP().style.transform }));
  // 斜着拖过一行：起手 10px 是抖动区（会被吃掉），所以拖幅要够大才看得出「跟随增长」
  for (let i = 1; i <= 14; i++) {
    await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: growPt.x + i * 9, y: growPt.y + i * 3 }] });
    await page.waitForTimeout(20);
  }
  const g1 = await page.evaluate(() => ({ len: window.__sel.len(), text: window.__sel.text(), tf: window.__AP().style.transform }));
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(140);
  growInfo = { g0, g1 };
  growOK = g0.len >= 1 && g0.len <= 2 && g1.len >= 10 && /^translateX\(-?\d+%\)$/.test(g1.tf);
}
check('长按锚定 1 字 → 拖手指：选区跟随增长（版面不动）', growOK, JSON.stringify(growInfo));

/* ========== 场景 4b-8c：长按后手指只抖 1~3px，选区必须原地不动 ==========
 * 实机原话：「你看，我随手长按，又是这么多字」。
 * 真因不在选词算法：.sel-layer 是铺满全屏的高亮层（z-index 890、pointer-events:none），
 * Chromium 的 caretRangeFromPoint 仍会把它当命中目标 → 换不到正文偏移 → 旧代码按
 * 「上半屏归页首、下半屏归页末」兜底 → 手指只抖 2px，选区就从 0 拉到几十字、或一路拉到页末。
 * 现在：浮层挡住时把它临时藏起来重取；仍然取不到就原地不动，绝不按半屏猜。
 */
const jitter = { seq: [], edge: [] };
const jPt = await linePoint(2);
if (jPt) {
  await page.evaluate(() => window.__sel.clear());
  await page.waitForTimeout(60);
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: jPt.x, y: jPt.y }] });
  await page.waitForTimeout(600);
  jitter.seq.push(await page.evaluate(() => ({ tag: 'anchor', ...window.__sel.dbg(), len: window.__sel.len() })));
  for (const d of [
    [1, 1],
    [2, 2],
    [-2, 1],
    [3, -1],
  ]) {
    await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: jPt.x + d[0], y: jPt.y + d[1] }] });
    await page.waitForTimeout(80);
    jitter.seq.push(
      await page.evaluate((t) => ({ tag: t, ...window.__sel.dbg(), len: window.__sel.len() }), `${d[0]},${d[1]}`)
    );
  }
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await page.waitForTimeout(160);
}
check(
  '长按后手指抖动 1~3px：选区原地不动（不再撑到页首/页末）',
  jitter.seq.length >= 4 && jitter.seq.every((x) => x.len === 1),
  JSON.stringify(jitter.seq)
);

/* 顺带守住同一条链路上的另一个坑：手指落在行尾空白 / 段与段之间的空档时，
 * caretRangeFromPoint 给的是「元素 + 子节点下标」（<div class=pg-body> / <p>），
 * 旧代码只往下取 firstChild，碰到 <p> 就失败 → 长按完全没反应。 */
if (jPt) {
  const edgePts = await page.evaluate((y0) => {
    const p = window.__AP();
    const st = document.querySelector('#rd-stage').getBoundingClientRect();
    const pg = [...p.querySelectorAll('.page')]
      .map((e) => ({ e, r: e.getBoundingClientRect() }))
      .sort((a, b) => Math.abs(a.r.left - st.left) - Math.abs(b.r.left - st.left))[0].e;
    const body = pg.querySelector('.pg-body');
    const par = [...body.querySelectorAll('p')].filter((x) => x.textContent.trim().length > 30)[0];
    const rg = document.createRange();
    rg.selectNodeContents(par);
    const rects = [...rg.getClientRects()].filter((r) => r.height > 4);
    const br = body.getBoundingClientRect();
    const r = rects[1] || rects[0];
    return [
      { tag: '行尾', x: Math.round(br.right - 4), y: Math.round(r.top + r.height / 2) },
      { tag: '段间', x: Math.round(r.left + 40), y: Math.round(r.bottom + 3) },
    ];
  }, jPt.y);
  for (const pt of edgePts) {
    await page.evaluate(() => window.__sel.clear());
    await page.waitForTimeout(50);
    await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: pt.x, y: pt.y }] });
    await page.waitForTimeout(560);
    jitter.edge.push(await page.evaluate((t) => ({ tag: t, len: window.__sel.len(), text: window.__sel.text() }), pt.tag));
    await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await page.waitForTimeout(120);
  }
}
check(
  '行尾空白 / 段间空档长按：也能锚定 1 字（不会整页没反应）',
  jitter.edge.length >= 2 && jitter.edge.every((x) => x.len === 1 && x.text.trim().length > 0),
  JSON.stringify(jitter.edge)
);

/* ========== 场景 4b-9：整段被完整包住时，一行只能有一个色块 ==========
 * 实机原话：「那一行『仙人就是无拘无束的』怎么出现了两个框」。
 * 根因在 Chromium：Range 完整包住一个块元素时，getClientRects 会额外吐一个
 * 「整块矩形」（整行宽 + 整行高），和文字行的矩形叠在一起、alpha 相加，
 * 看着就是一个大框套一个小框。现在改为逐文本节点取矩形 + 同行合并。
 */
const boxScan = await page.evaluate(() => {
  const N = window.__sel.bodyLen();
  const bad = [];
  let cases = 0;
  const overlap = (rs) => {
    for (let i = 0; i < rs.length; i++) {
      for (let j = i + 1; j < rs.length; j++) {
        const A = rs[i];
        const B = rs[j];
        const vy = Math.min(A.b, B.b) - Math.max(A.t, B.t);
        const vx = Math.min(A.r, B.r) - Math.max(A.l, B.l);
        if (vy > 3 && vx > 3) return { A, B };
      }
    }
    return null;
  };
  for (let a = 0; a < N; a += 9) {
    const b = Math.min(N, a + 100);
    if (b - a < 12) continue;
    window.__sel.set(a, b);
    cases++;
    const o = overlap(window.__sel.rects());
    if (o) bad.push({ a, b, ...o });
  }
  window.__sel.clear();
  return { cases, bad: bad.slice(0, 3) };
});
check(
  '跨段选区：一行只有一个色块（不再出现套着的第二个框）',
  boxScan.cases > 20 && boxScan.bad.length === 0,
  JSON.stringify(boxScan),
);

/* ========== 场景 4c：半格死状态自愈 ==========
 * 实机截图里最坏的形态：transform 停在 -42%，而 dragging/dx 全被清零。
 * 现在选区生效期间根本不会写 transform，但万一被别的路径弄脏，点一下必须回正。
 */
const healed = await page.evaluate(async () => {
  window.__sel.set(0, 6);
  await new Promise((r) => setTimeout(r, 60));
  const p = window.__AP();
  p.style.transform = 'translateX(-42%)'; // 伪造「卡住」
  const box = p.getBoundingClientRect();
  const x = box.x + box.width * 0.5;
  const y = box.y + box.height * 0.4;
  const ev = (type) =>
    new PointerEvent(type, {
      pointerId: 3,
      pointerType: 'touch',
      isPrimary: true,
      button: 0,
      buttons: type === 'pointerup' ? 0 : 1,
      clientX: x,
      clientY: y,
      bubbles: true,
      cancelable: true,
    });
  p.dispatchEvent(ev('pointerdown'));
  p.dispatchEvent(ev('pointerup'));
  await new Promise((r) => setTimeout(r, 80));
  return p.style.transform;
});
check('半格死状态：点一下即回正', /^translateX\(-?\d+%\)$/.test(healed), healed);
await page.evaluate(() => window.__sel.clear());

/* ========== 场景 4d：选区清掉后，翻页手感照旧 ========== */
const s7page0 = await page.locator('#r-meta-page').textContent();
await page.mouse.click(330, 420);
await page.waitForTimeout(420);
const s7page1 = await page.locator('#r-meta-page').textContent();
check('收掉选区后：点右侧仍能翻页', s7page1 !== s7page0, `${s7page0} → ${s7page1}`);
await page.mouse.click(60, 420);
await page.waitForTimeout(420);
check('点左侧：翻回上一页', (await page.locator('#r-meta-page').textContent()) === s7page0);

/* ========== 场景 5：真机触摸滑动 ==========
 * 实机反馈「改完又没法翻页了，一翻就哆嗦两下没反应」。
 * 根因：监听 lostpointercapture 后归零 —— Chrome/WebView 每次 touchmove 做滚动判定
 * 都会派发一次它，于是跟手 28px 就被弹回、dragging 置 false，之后整页不再响应。
 * 这里用 CDP 原生触摸事件（pointerType=touch）复现，鼠标拖拽测不出这条。
 */
console.log('\n[分页模式] 真机触摸滑动翻页');
const cdp = await ctx.newCDPSession(page);
const touchSwipe = async (x1, x2, y = 420, steps = 8) => {
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: x1, y }] });
  for (let i = 1; i <= steps; i++) {
    await cdp.send('Input.dispatchTouchEvent', {
      type: 'touchMove',
      touchPoints: [{ x: x1 + ((x2 - x1) * i) / steps, y }],
    });
    await page.waitForTimeout(16);
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
};
// 先确保选区是空的（场景 4 划选过）
await page.evaluate(() => window.__sel.clear());
const touchStartPage = await page.locator('#r-meta-page').textContent();
await touchSwipe(320, 80);
await page.waitForTimeout(700);
const touchMid = await page.evaluate(() => {
  const p = window.__AP();
  return { tf: p.style.transform, dragging: p.classList.contains('dragging') };
});
const afterTouch1 = await page.locator('#r-meta-page').textContent();
check('触摸左滑：翻到下一页', afterTouch1 !== touchStartPage, `${touchStartPage} → ${afterTouch1} (${touchMid.tf})`);
check('触摸左滑：不残留半格位移', /^translateX\(-?\d+%\)$/.test(touchMid.tf), touchMid.tf);
await touchSwipe(320, 80);
await page.waitForTimeout(700);
const afterTouch2 = await page.locator('#r-meta-page').textContent();
check('触摸左滑：连续第二次仍能翻页', afterTouch2 !== afterTouch1, `${afterTouch1} → ${afterTouch2}`);
await touchSwipe(80, 320);
await page.waitForTimeout(700);
const afterTouch3 = await page.locator('#r-meta-page').textContent();
check('触摸右滑：能翻回上一页', afterTouch3 === afterTouch1, `${afterTouch2} → ${afterTouch3}`);

/* ========== 场景 6：页脚关掉后（没有那一行可借），开关朗读也要能回到原处 ==========
 * 页脚全关时横条确实要吃一行，必然重排；但重排后必须锚回同一处，不能往回跳一页。 */
console.log('\n[分页模式] 页脚关闭时开关朗读的锚定');
await page.evaluate(() => document.querySelector('#r-typo').click());
await page.waitForTimeout(700);
await page.evaluate(() => document.querySelector('.hf-row')?.click());
await page.waitForTimeout(700);
const turnedOff = await page.evaluate(() => {
  // 「页眉页脚」那行本身不含多选控件，真正的选择器在带 .seg.multi 的那一行
  const row = [...document.querySelectorAll('.sheet-row')].find((r) => r.querySelector('.seg.multi'));
  if (!row) return -1;
  const on = [...row.querySelectorAll('button.on')];
  on.forEach((b) => b.click());
  return on.length;
});
check('已找到页脚多选（默认页码+时间）', turnedOff === 2, `关闭 ${turnedOff} 项`);
await page.evaluate(() => document.querySelector('.hf-apply')?.click());
await page.waitForTimeout(2400);
await page.waitForSelector('#r-body .page', { timeout: 20000 });
await page.keyboard.press('ArrowRight');
await page.waitForTimeout(420);
await page.keyboard.press('ArrowRight');
await page.waitForTimeout(520);
const spot = () =>
  page.evaluate(() => {
    const pages = [...document.querySelectorAll('#r-body .pages .page')];
    const cur = pages.findIndex((p) => {
      const r = p.getBoundingClientRect();
      return r.left > -10 && r.left < 10;
    });
    const vis = pages[cur < 0 ? 0 : cur];
    const body = vis.querySelector('.pg-body');
    return {
      cur: cur < 0 ? 0 : cur,
      first: (body.textContent || '').slice(0, 12),
      hasFoot: !!vis.querySelector('.pg-foot'),
    };
  });
const spot0 = await spot();
check('已切到无页脚模式', !spot0.hasFoot, JSON.stringify(spot0));
await page.evaluate(() => document.querySelector('#r-tts').click());
await page.waitForTimeout(1700);
const spot1 = await spot();
check('无页脚 + 朗读：不跳回章首', spot1.cur > 0, `${spot0.cur} → ${spot1.cur}`);
await page.evaluate(() => document.querySelector('#listen-x').click());
await page.waitForTimeout(1700);
const spot2 = await spot();
// 无页脚时横条确实要吃一行，必然重排；能做的是「同一个段落还在这一屏」，允许 ±1 页
check(
  '无页脚 + 退出朗读：位置不跑偏（同一段还在这一屏，±1 页）',
  Math.abs(spot2.cur - spot0.cur) <= 1,
  `${spot0.cur}/${spot0.first} → ${spot1.cur}/${spot1.first} → ${spot2.cur}/${spot2.first}`,
);

/* ========== 场景 7：横向分页不能「吞内容」 ==========
 * 实机反馈：「第一页最下方读完之后跟第二页最上方接不上，改竖向翻页就都在。」
 * 根因有两条，都在 splitKid 这条「把长段落切到下一页」的路径上：
 *   ① 切出来的续段（rest）是运行时新建的，测量表里没有它，段后距被当成 0，
 *      页尾因此多塞内容、超出部分被 .page 的 overflow:hidden 裁掉；
 *   ② fitChars 估多时只退 3 行，退不够就把「放不下」的字数返回去，切出的片段必然溢出。
 * 这里扫字号 × 行距，并额外跑一本「整段几千字不断段」的书（切分主路径）。
 */
console.log('\n[分页模式] 横向分页不吞内容');
const normText = (s) => s.replace(/[\s\u3000]+/g, '');
const HUGE = '/workspace/modou/test/fixtures/local/reader-huge.txt';

/** 打开一本书并保证处于左右翻页 */
const openPaged = async (pg, txt) => {
  await pg.goto(BASE, { waitUntil: 'networkidle' });
  await pg.waitForSelector('#btn-local-empty, .book-card', { timeout: 20000 });
  if (!(await pg.locator('.book-card').count())) {
    const [ch] = await Promise.all([pg.waitForEvent('filechooser'), pg.click('#btn-local-empty')]);
    await ch.setFiles([txt]);
    await pg.waitForSelector('.book-card', { timeout: 25000 });
  }
  await pg.locator('.book-card').first().click();
  await pg.waitForSelector('#r-body .page', { timeout: 25000 });
  await pg.evaluate(() => {
    if (!document.body.classList.contains('reader-paged')) {
      document.querySelector('#r-typo')?.click();
      setTimeout(() => {
        const b = [...document.querySelectorAll('.seg button')].find((x) => x.textContent.trim() === '左右翻页');
        if (b) b.click();
      }, 250);
    }
  });
  await pg.waitForTimeout(1800);
};
const setFontSize = async (pg, target) => {
  await pg.evaluate(() => {
    if (!document.querySelector('.typo-rows')) document.querySelector('#r-typo')?.click();
  });
  await pg.waitForTimeout(320);
  const cur = await pg.evaluate(() => {
    const b = document.querySelectorAll('.typo-rows .row-label b')[0];
    return b ? parseInt(b.textContent, 10) : 18;
  });
  const delta = target - cur;
  for (let i = 0; i < Math.abs(delta); i++) {
    await pg.evaluate((d) => {
      const bs = [...document.querySelectorAll('.typo-rows .stepper')];
      (d > 0 ? bs[1] : bs[0]).click();
    }, Math.sign(delta));
    await pg.waitForTimeout(70);
  }
  await pg.waitForTimeout(800);
};
const setLineHeight = async (pg, v) => {
  await pg.evaluate((val) => {
    const rs = [...document.querySelectorAll('.typo-rows input[type=range]')];
    if (!rs[0]) return;
    rs[0].value = String(val);
    rs[0].dispatchEvent(new Event('input', { bubbles: true }));
  }, v);
  await pg.waitForTimeout(800);
};
const audit = async (pg, src, label) => {
  const stats = await pg.evaluate(() => {
    const out = [];
    document.querySelectorAll('#r-body .pages .page').forEach((pgEl, i) => {
      const b = pgEl.querySelector('.pg-body');
      if (!b) return;
      out.push({ i, over: b.scrollHeight - b.clientHeight, text: [...b.children].map((k) => k.textContent).join('') });
    });
    return out;
  });
  const overPages = stats.filter((s) => s.over > 1);
  let cur = 0;
  let lost = 0;
  for (const s of stats) {
    const t = normText(s.text);
    if (!t) continue;
    const at = src.indexOf(t, cur);
    if (at > cur) lost += at - cur;
    if (at >= 0) cur = at + t.length;
  }
  check(`${label}：没有一页超出可用高度`, stats.length > 0 && overPages.length === 0, overPages.map((s) => `第${s.i + 1}页 ${s.over}px`).join(' '));
  check(`${label}：正文一个字都没丢`, lost === 0, `共 ${stats.length} 页，缺 ${lost} 字`);
};

const p2 = await ctx.newPage();
const errors2 = [];
p2.on('pageerror', (e) => errors2.push(e.message));
await openPaged(p2, TXT);
const SRC = normText(readFileSync(TXT, 'utf8'));
for (const [fs, lh] of [[18, 1.6], [16, 1.4], [26, 1.7], [24, 2.0]]) {
  await setFontSize(p2, fs);
  await setLineHeight(p2, lh);
  await audit(p2, SRC, `字号${fs}/行距${lh}`);
}

// 几千字不断段的书：splitKid 每页都要切一刀，续段段距最容易漏
const ctxHuge = await browser.newContext({ hasTouch: true, viewport: { width: 390, height: 844 } });
await ctxHuge.addInitScript(STUB);
const p3 = await ctxHuge.newPage();
const errors3 = [];
p3.on('pageerror', (e) => errors3.push(e.message));
await openPaged(p3, HUGE);
const SRC_HUGE = normText(readFileSync(HUGE, 'utf8'));
for (const [fs, lh] of [[18, 1.6], [24, 1.8]]) {
  await setFontSize(p3, fs);
  await setLineHeight(p3, lh);
  await audit(p3, SRC_HUGE, `超长段落 字号${fs}/行距${lh}`);
}
await ctxHuge.close();

const chartErrs = [...errors, ...errors2, ...errors3];
check('全程无 JS 报错', chartErrs.length === 0, chartErrs.join(' | '));

/* ========== 场景 4c：版面不许被「横向滚动」推歪 ==========
 * 实机反馈「整页偏移、完全用不了」的根因不是 transform —— 页是并排摆的，10 页的书会把
 * 滚动容器的 scrollWidth 撑到 3900px；WebView 长按划词时会把光标位置 scrollIntoView，
 * scrollLeft 被推到接近一页宽：屏幕上左边一窄条是上一屏行末、右边直接接上下一页开头。
 * transform 全程正常，于是所有只看 transform 的兜底全在空转。
 * 现在 #rd-stage / #r-body / .page 三层都用 overflow:clip（不是 hidden）：压根不是滚动
 * 容器，scrollWidth 恒等于 clientWidth、scrollLeft 推不动，scrollIntoView 也无从滚动。
 */
console.log('\n[分页模式] 版面不许被横向滚动推歪');
const scrollGuard = await page.evaluate(() => {
  const probe = (el) => {
    const cs = getComputedStyle(el);
    const hw = el.scrollWidth === el.clientWidth;
    el.scrollLeft = 300; // 模拟 WebView 选区 auto-scroll
    const pushed = el.scrollLeft;
    el.scrollLeft = 0;
    return { hw, pushed, ox: cs.overflowX, sw: el.scrollWidth, cw: el.clientWidth };
  };
  return {
    stage: probe(document.querySelector('#rd-stage')),
    body: probe(document.querySelector('#r-body')),
    page: probe(window.__AP().querySelector('.page')),
  };
});
for (const [k, label] of [['stage', '#rd-stage'], ['body', '#r-body'], ['page', '.page']]) {
  // overflow:clip 的元素仍会把上层内容的溢出宽算进 scrollWidth（Chromium 行为），
  // 所以判定「不可滚」看的是 scrollLeft 推不动，而不是 scrollWidth 的数值
  check(`${label} 横向已被掐断（clip 或无溢出）`, scrollGuard[k].ox === 'clip' || scrollGuard[k].hw, JSON.stringify(scrollGuard[k]));
  check(`${label} 接受不了横向滚动（scrollLeft 推不动）`, scrollGuard[k].pushed === 0, JSON.stringify(scrollGuard[k]));
}

/* 几何才是用户看到的东西：只对 transform 字符串做断言，会漏掉滚动造成的偏移 */
const geo = () =>
  page.evaluate(() => {
    const st = document.querySelector('#rd-stage');
    const sr = st.getBoundingClientRect();
    const pages = window.__AP();
    const cur = [...pages.querySelectorAll('.page')]
      .map((el) => ({ el, r: el.getBoundingClientRect() }))
      .sort((a, b) => Math.abs(a.r.left - sr.left) - Math.abs(b.r.left - sr.left))[0];
    const br = cur.el.querySelector('.pg-body').getBoundingClientRect();
    return { pageLeft: Math.round(cur.r.left - sr.left), bodyLeft: Math.round(br.left - sr.left), bodyRight: Math.round(br.right - sr.left), sl: st.scrollLeft };
  });
await page.evaluate(() => window.__sel.clear());
await page.waitForTimeout(240);
const geo0 = await geo();
const gbox = await page.evaluate(() => {
  const r = window.__AP().getBoundingClientRect();
  return { x: r.x, y: r.y, w: r.width, h: r.height };
});
// 长按 520ms 起选区，再拖手柄扩选：整页几何必须一动不动
// 步长刻意压小（8px×6 = 48px < 翻页阈值），否则松手会正常翻页、几何断言就测不到原位
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: gbox.x + gbox.w * 0.5, y: gbox.y + gbox.h * 0.4 }] });
await page.waitForTimeout(520);
for (let i = 1; i <= 6; i++) {
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: gbox.x + gbox.w * 0.5 + i * 8, y: gbox.y + gbox.h * 0.4 + i * 6 }] });
  await page.waitForTimeout(20);
}
const geoDrag = await geo();
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
await page.waitForTimeout(420);
const geoAfter = await geo();
check(
  '长按划选期间：当前页仍钉在视口原位（pageLeft == 0）',
  geoDrag.pageLeft === 0 && geoAfter.pageLeft === 0,
  JSON.stringify({ geo0, geoDrag, geoAfter }),
);
check(
  '长按划选后：正文左右边界与基线一致（没有整页偏移）',
  geoAfter.bodyLeft === geo0.bodyLeft && geoAfter.bodyRight === geo0.bodyRight,
  `${JSON.stringify(geo0)} → ${JSON.stringify(geoAfter)}`,
);

/* ========== 场景 4d：翻页必须跟随手指 ==========
 * 上一版为了避让划词，把起手速度门槛抬到 0.5px/ms，慢一点的拖动全被判成「划词」，
 * 页面于是完全不跟手 —— 手感发死。门槛降到 0.15（仅用于区分长按抖动与真的在滑），
 * 且跟手位移必须与手指位移一致，不能「慢半拍」或跳变。
 */
console.log('\n[分页模式] 翻页跟随手指');
await page.evaluate(() => window.__sel.clear());
await page.waitForTimeout(260);
const followBox = await page.evaluate(() => {
  // 触摸点必须按「视口」算：.pages 自身的 rect 会跟着 transform 平移（截图外），
  // 拿它算坐标会把手指点到屏幕外面去 —— 这一条曾经让「跟手」用例空转成假绿。
  const st = document.querySelector('#rd-stage').getBoundingClientRect();
  const p = window.__AP();
  const m = /translateX\(-(\d+)%\)/.exec(p.style.transform) || [];
  return { x: st.x, y: st.y, w: st.width, h: st.height, idx: m[1] ? Number(m[1]) / 100 : 0 };
});
const fx = followBox.x + followBox.w * 0.7;
const fy = followBox.y + followBox.h * 0.5;
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: fx, y: fy }] });
for (let i = 1; i <= 6; i++) {
  await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: fx - i * 12, y: fy }] });
  await page.waitForTimeout(16);
}
const followRes = await page.evaluate(() => {
  const p = window.__AP();
  const m = /translateX\((-?[\d.]+)px\)/.exec(p.style.transform);
  return { tf: p.style.transform, px: m ? Number(m[1]) : null };
});
await cdpHold.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
await page.waitForTimeout(420);
const expectPx = -(followBox.idx * followBox.w) - 72;
// 容差放到 14px（≈一步）：CDP 连续派发 touchMove 时偶尔会与上一帧合并，
// 最后一帧的位移落在下一次 rAF 里，读到的值会差一步 —— 不是「跟手丢失」
check(
  '拖动 72px：容器跟手位移与手指一致（±14px）',
  followRes.px != null && Math.abs(followRes.px - expectPx) <= 14,
  `期望 ${expectPx.toFixed(1)}px，实际 ${followRes.tf}`,
);
const followAfter = await page.evaluate(() => window.__AP().style.transform);
check('拖动翻页后停在整页位置（无半格残留）', /^translateX\(-\d+%\)$/.test(followAfter), followAfter);

/* ========== 场景 4e：目录按钮轻点必须稳定唤起抽屉 ==========
 * 实机反馈：「目录按钮有时候点击后图标有反应、但唤不起目录，时有时无」。
 * 根因是 WebView 的 click 合成：手指在按钮上微移几像素、或点按节奏被当成滚动 /
 * 双击缩放的前摇，浏览器会把这次合成 click 吞掉（300ms 点击延迟 + 双击缩放判定），
 * 而 :active 的按压反馈照常出现，于是「图标动了、动作没发生」。
 * 按钮已加 `touch-action: manipulation`（见 styles.css）：去掉 300ms 延迟与双击缩放，
 * click 立刻、稳定地到达。这条守的是「轻点一定唤起抽屉」，并连点不漏。
 */
console.log('\n[分页模式] 目录按钮轻点');
const showChrome = async () => {
  await page.evaluate(() => {
    document.querySelector('#view').classList.remove('chrome-hidden');
    if (window.__sel) window.__sel.clear();
    const d = document.querySelector('.toc-drawer');
    if (d && d.classList.contains('open')) d.classList.remove('open');
    const m = document.querySelector('.toc-mask');
    if (m && m.classList.contains('open')) m.classList.remove('open');
  });
  await page.waitForTimeout(360); // 等工具坞展开动画结束、选区工具条退场
};
const tapToc = async (via) => {
  await showChrome();
  const pt = await page.evaluate(() => {
    const b = document.querySelector('#r-toc');
    const r = b.getBoundingClientRect();
    const hit = document.elementFromPoint(Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2));
    return {
      x: Math.round(r.left + r.width / 2),
      y: Math.round(r.top + r.height / 2),
      pe: getComputedStyle(b).pointerEvents,
      onscreen: r.top >= 0 && r.bottom <= window.innerHeight + 2,
      hit: hit ? (hit.id || hit.className || hit.tagName) : null,
    };
  });
  if (via === 'click') {
    await page.evaluate(() => document.querySelector('#r-toc').click());
  } else {
    // 真实坐标级点击：走与触摸合成 click 相同的委托分发路径；先确认按钮可命中
    await page.mouse.click(pt.x, pt.y);
  }
  await page.waitForTimeout(220);
  return page.evaluate(() => !!document.querySelector('.toc-drawer.open'));
};
const tocTouchOK = await tapToc('touch');
const tocClickOK = await tapToc('click');
// 真实轻点连点 12 次：每次都必须唤起，不漏开
let tocFlap = 0;
const tpt = await page.evaluate(() => {
  const b = document.querySelector('#r-toc');
  const r = b.getBoundingClientRect();
  return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) };
});
for (let i = 0; i < 12; i++) {
  await showChrome();
  await page.mouse.click(tpt.x, tpt.y);
  await page.waitForTimeout(220);
  const opened = await page.evaluate(() => !!document.querySelector('.toc-drawer.open'));
  if (!opened) tocFlap++;
}
check('目录按钮：真实轻点（坐标点击 = 触摸合成 click 路径）唤起抽屉', tocTouchOK);
check('目录按钮：原生 click 路径仍能唤起抽屉', tocClickOK);
check('目录按钮：连点 12 次每次都能正常唤起', tocFlap === 0, `失败 ${tocFlap} 次`);
// 静态锁死修复本身：按钮加了 touch-action:manipulation（去掉 300ms 点击延迟/双击缩放）
const tocTa = await page.evaluate(() => getComputedStyle(document.querySelector('#r-toc')).touchAction);
check('目录按钮：已禁用 300ms 点击延迟（touch-action:manipulation）', tocTa === 'manipulation', tocTa);

/* ========== 场景 4f：正文搜索条的返回与计数按钮 ==========
 * 实机反馈：
 *   ①「从搜索结果返回后，如果没搜东西，返回后就不要显示底下那个搜索框」
 *   ②「单击搜索框里的数字进入结果页，但那数字没特殊处理，想不到去点它」
 */
console.log('\n[分页模式] 正文搜索条');
const openFindViaMenu = async () => {
  await showChrome();
  await page.evaluate(() => document.querySelector('#r-find').click());
  await page.waitForTimeout(360);
};
// ① 打开搜索（不搜）→ 返回：底部搜索条与搜索态都要收掉，工具坞要回来
await openFindViaMenu();
const fOpened = await page.evaluate(() => ({
  bar: !document.querySelector('#find-bar').hidden,
  page: !document.querySelector('#find-page').hidden,
}));
check('点搜索：出现底部搜索条 + 结果页', fOpened.bar && fOpened.page, JSON.stringify(fOpened));
// ② 计数按钮必须「看上去能点」：指针手型 + 有底色 + 强调色
const cntStyle = await page.evaluate(() => {
  const el = document.querySelector('#find-count');
  const cs = getComputedStyle(el);
  return { cursor: cs.cursor, bg: cs.backgroundColor, color: cs.color };
});
const bgVisible = !/rgba?\(0,\s*0,\s*0,\s*0\)/.test(cntStyle.bg);
check(
  '计数按钮：手型 + 有底色（一眼看出可点）',
  cntStyle.cursor === 'pointer' && bgVisible,
  JSON.stringify(cntStyle),
);
// ②b 实机反馈「最底下几个框，三种高度」：条内所有控件必须等高
const barH = await page.evaluate(() => {
  const pick = (sel) => Math.round(document.querySelector(sel).getBoundingClientRect().height);
  return {
    input: pick('#find-input'),
    count: pick('#find-count'),
    prev: pick('#find-prev'),
    next: pick('#find-next'),
    close: pick('#find-close'),
  };
});
check(
  '搜索条内控件等高（输入框/计数/↑↓✕ 同一高度，≥40）',
  new Set(Object.values(barH)).size === 1 && barH.input >= 40,
  JSON.stringify(barH),
);
await page.evaluate(() => document.querySelector('#fp-back').click());
await page.waitForTimeout(560);
const fClosed = await page.evaluate(() => ({
  bar: !document.querySelector('#find-bar').hidden,
  page: !document.querySelector('#find-page').hidden,
  finding: document.querySelector('#rd-shell').classList.contains('finding'),
}));
check(
  '没搜东西就返回：底部搜索条消失、工具坞恢复',
  !fClosed.bar && !fClosed.page && !fClosed.finding,
  JSON.stringify(fClosed),
);

// ③ 真搜一次再返回：底部条要保留（还要 ↑/↓ 继续跳）
await openFindViaMenu();
await page.evaluate(() => {
  document.querySelector('#fp-input').value = '的';
  document.querySelector('#fp-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
});
await page.waitForTimeout(2200);
const searched = await page.evaluate(() => ({
  count: (document.querySelector('#find-count').textContent || '').replace(/\s/g, ''),
}));
check('搜索「的」：命中并被计数（不是 0/0）', /\d+\/\d+/.test(searched.count) && searched.count !== '0/0', JSON.stringify(searched));
await page.evaluate(() => document.querySelector('#fp-back').click());
await page.waitForTimeout(560);
const fAfterSearch = await page.evaluate(() => ({
  bar: !document.querySelector('#find-bar').hidden,
  page: !document.querySelector('#find-page').hidden,
}));
check('搜过后返回：底部搜索条保留（可继续 ↑/↓）', fAfterSearch.bar && !fAfterSearch.page, JSON.stringify(fAfterSearch));
// ④ 点计数按钮 → 回到结果页
await page.evaluate(() => document.querySelector('#find-count').click());
await page.waitForTimeout(320);
check(
  '点计数按钮：进入搜索结果页',
  await page.evaluate(() => !document.querySelector('#find-page').hidden),
);
await page.evaluate(() => document.querySelector('#find-close').click());
await page.waitForTimeout(220);

/* ========== 场景 4g：原生阅读页的入口与数据协议 ==========
 * 原生阅读只承接「正文这一屏」，所以交给它的东西必须齐全：
 * 目录标题表、当前章正文（纯文本段落，不能带标签）、排版参数。
 * 这里用桩替掉桥方法，验证 payload 结构 + 「按需取下一章」的回传链路。
 */
console.log('\n[分页模式] 原生阅读入口');
await showChrome();
await page.evaluate(() => {
  window.__np = null;
  window.ModouBridge.openNativeReader = (json) => {
    window.__np = json;
  };
});
await page.evaluate(() => document.querySelector('#r-typo').click());
await page.waitForTimeout(420);
const hasNativeRow = await page.evaluate(() =>
  [...document.querySelectorAll('.hf-row')].some((e) => e.textContent.includes('原生阅读'))
);
check('排版面板里有「原生阅读（实验）」入口', hasNativeRow);
await page.evaluate(() => {
  const hit = [...document.querySelectorAll('.hf-row')].find((e) => e.textContent.includes('原生阅读'));
  if (hit) hit.click();
});
await page.waitForTimeout(2600);
const np = await page.evaluate(() => {
  const raw = window.__np;
  if (!raw) return null;
  const p = JSON.parse(raw);
  return {
    index: p.index,
    total: p.total,
    titles: (p.titles || []).length,
    paras: (p.paras || []).length,
    htmlTag: (p.paras || []).some((x) => /[<>]/.test(x)),
    fontSize: p.fontSize,
    lineHeight: p.lineHeight,
    theme: p.theme,
    bytes: raw.length,
  };
});
check(
  '交给原生：正文已切成段落且不含 HTML 标签',
  !!np && np.paras > 20 && !np.htmlTag,
  JSON.stringify(np),
);
check(
  '交给原生：目录标题数与章节数一致',
  !!np && np.titles === np.total && np.total > 0,
  JSON.stringify(np && { titles: np.titles, total: np.total }),
);
check(
  '交给原生：排版参数齐全（字号 / 行距 / 主题）',
  !!np && np.fontSize > 0 && np.lineHeight > 1 && !!np.theme,
  JSON.stringify(np),
);
const npReply = await page.evaluate(async () => {
  window.__reply = null;
  window.ModouBridge.nativeChapterData = (json) => {
    window.__reply = json;
  };
  await window.__modouNativeChapter(1);
  return window.__reply;
});
const npChap = npReply ? JSON.parse(npReply) : null;
check(
  '原生请求下一章：能回传正文（不是错误）',
  !!npChap && !npChap.error && (npChap.paras || []).length > 0,
  JSON.stringify(npChap && { index: npChap.index, paras: (npChap.paras || []).length, error: npChap.error || '' }),
);

await browser.close();
console.log(`\n阅读版式回归：${pass} 通过 / ${fail} 失败`);
process.exit(fail ? 1 : 0);
