/**
 * reader.js — 阅读页：章节渲染 + 翻页（上下滚动/左右分页）+ 进度 + 排版面板 + 朗读(TTS)
 *             + 目录抽屉 + 书签 + 正文搜索 + 自动阅读 + 定时停止 + 阅读统计
 */
import {
  state,
  findBook,
  getProgress,
  saveProgress,
  renderChapterHtml,
  purifyTitle,
  toast,
  addBookmark,
  getBookmarks,
  previewChapterGet,
  previewChapterPut,
  promotePreview,
  addReadMsMonth,
  saveAppTheme,
  docThemeNow,
  onThemeChange,
  takePrefetch,
} from './app.js';
import { settings, db } from '../db.js';
import { $, $$, h, esc, openSheet, registerBack } from './dom.js';
import { createTocDrawer } from './reader-toc.js';
import { openReplaceSheet } from './replace.js';
import {
  ttsSupported,
  ttsHasVoice,
  ttsPrepare,
  ttsState,
  ttsOpenSettings,
  ttsEngineName,
  ttsStart,
  ttsStop,
  ttsPause,
  ttsResume,
  ttsNext,
  ttsPrev,
  ttsSetRate,
  ttsIsPaused,
  ttsCount,
} from './tts.js';
import { nativeReaderReady, openNativeReader, parasFromHtml } from './native-reader.js';

const THEMES = ['paper', 'cream', 'gray', 'dark'];
const THEME_LABEL = { paper: '纸白', cream: '米黄', gray: '青石', dark: '夜间' };
/** 阅读主题背景色（原生沉浸时用于填充系统栏区域，避免黑边） */
const RBG = { paper: '#F6F1E5', cream: '#EFE7D2', gray: '#E9EAE3', dark: '#17191D' };
const LH_DEFAULT = 1.9; // 行距默认（1.4–2.6 数值可调）
const RATES = [0.8, 1.0, 1.2];
const PAGE_MODES = [
  ['scroll', '上下滚动'],
  ['page', '左右翻页'],
];
const PG_DEFAULT = 1.0; // 段距默认（0–2 数值可调）
const FONTS = [
  ['song', '宋体'],
  ['hei', '黑体'],
  ['kai', '楷体'],
];
// 字重（很多机型的中文衬线体默认偏细，给一个可调档位）
const WEIGHTS = [
  ['light', '细', 300],
  ['regular', '常规', 400],
  ['medium', '中粗', 500],
  ['bold', '粗', 600],
];
// 页边距（阅读栏左右内边距）
const MARGINS = [
  ['narrow', '窄', 16],
  ['md', '中', 24],
  ['wide', '宽', 36],
];
// TTS 定时停止（分钟，0 = 关）
const TTS_TIMERS = [0, 15, 30, 60, 90];

/**
 * 正文搜索会话（模块级）：跳章会整页重渲染，搜索状态必须活在渲染函数之外，
 * 否则跨章导航时结果就丢了。
 * bookId 记录搜索会话归属的书——换书（进入另一本书）立即作废会话，
 * 避免上一本书的全书检索在新书里继续跑、进度/结果张冠李戴。
 */
const gFind = { kw: '', hits: [], pos: -1, open: false, gen: 0, pendCi: -1, pendOrd: -1, busy: false, progress: '', bookId: '' };

/** 换书时中止在飞的全书搜索并清空会话（保留 gen 自增以让 worker 退出） */
function cancelFindSession() {
  gFind.gen++;
  gFind.kw = '';
  gFind.hits = [];
  gFind.pos = -1;
  gFind.pendCi = -1;
  gFind.pendOrd = -1;
  gFind.open = false;
  gFind.busy = false;
  gFind.progress = '';
  gFind.bookId = '';
}

/**
 * 跨章翻页落点：goChapter(ni, targetPage) 时记录，renderReader/paint 消费后清空。
 * null = 按阅读进度恢复；>=0 = 指定页码；-1 = 落目标章末页（用于向后跨章翻页）。
 */
let gPendingPage = null;

/**
 * 跨章滑入方向：turnPage 在章边界跨章时置位（1=去下一章，新页从右滑入；
 * -1=回上一章，末页从左滑入），paint 渲染完成后消费，让跨章与正常翻页
 * 共用同一套滑动动画，而不是瞬间跳变。0 = 无方向（目录/按钮跳章）。
 */
let gPendingSlide = 0;
let gSlideStartPx = 0;
let gGestureAt = 0; // 最近一次手势时间戳（预分页避开手势，防止抢主线程） // 跨章滑入的起始位移（松手时手指已经拖走的像素）

/**
 * 跳章锁定期：显式跳章（上一章/下一章按钮、目录、书签）后短时间内，分页边界滑动
 * 不跨章——避免「刚点下一章随手一滑就退回上一章」的误触；过期后右滑可正常跨回上一章。
 */
let gJumpLockUntil = 0;

/**
 * 自动阅读跨章续读：自动阅读走到本章末尾且有下一章时置位，goChapter 重渲染后由
 * renderReader 在 paint 之后重新 startAuto，实现「自动翻到下一章继续读」而非停在章末。
 */
let gAutoContinue = false;
/**
 * 阅读页渲染代号。renderReader 里有大量 await（读设置/取正文/排版），
 * 期间路由可能已经重绘了别的页面。旧的一轮醒来后若继续往 DOM 上绑监听，
 * 轻则 null.onclick 抛错中断后续绑定（翻页/选词全失灵），重则把新页面的
 * 处理器覆盖成旧书的闭包。每轮领号，关键节点比对，过期即退出。
 */
let gRenderGen = 0;
const JUMP_LOCK_MS = 450;

const IC = {
  back: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 5.5L8 12l6.5 6.5"/></svg>',
  prev: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 5.5L8 12l6.5 6.5"/></svg>',
  next: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.5 5.5L16 12l-6.5 6.5"/></svg>',
  toc: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 6h16M4 12h16M4 18h9"/></svg>',
  bmk: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M6.5 3.5h11a1 1 0 0 1 1 1v15.2l-6.5-4.2-6.5 4.2V4.5a1 1 0 0 1 1-1z"/></svg>',
  find: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="11" cy="11" r="6.5"/><path d="M15.8 15.8L20.5 20.5"/></svg>',
  auto: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.5"/><path d="M10 8.4l6 3.6-6 3.6z"/></svg>',
  tts: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4z"/><path d="M15.5 9a4.5 4.5 0 0 1 0 6"/><path d="M18 6.3a8 8 0 0 1 0 11.4"/></svg>',
  sun: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="12" r="4.2"/><path d="M12 3.2v2.1M12 18.7v2.1M3.2 12h2.1M18.7 12h2.1M5.7 5.7l1.5 1.5M16.8 16.8l1.5 1.5M18.3 5.7l-1.5 1.5M7.2 16.8l-1.5 1.5"/></svg>',
  moon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20.5 14.6A8.6 8.6 0 0 1 9.4 3.5a8.6 8.6 0 1 0 11.1 11.1z"/></svg>',
  more: '<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>',
  purify: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5h16l-6.2 7.1v5.6L9.9 19.6v-7.5L4 5z"/></svg>',
  swap: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 7h11M8 7l3-3M8 7l3 3"/><path d="M16 17H5M16 17l-3-3M16 17l-3 3"/></svg>',
};

/* ---------- 底部工具坞：图标注册表 + 用户排布 ----------
 * key → {icon,label,text?}；排布（两行顺序 + 隐藏项）存 settings.readerMenuLayout，
 * 在「更多 → 图标排布」里调整。事件用委托分发到 MENU_ACTIONS，重画不丢绑定。 */
const MENU_ITEMS = {
  toc: { icon: IC.toc, label: '目录' },
  bmk: { icon: IC.bmk, label: '书签' },
  find: { icon: IC.find, label: '搜索' },
  auto: { icon: IC.auto, label: '自动' },
  tts: { icon: IC.tts, label: '听书' },
  purify: { icon: IC.purify, label: '净化' },
  prev: { icon: IC.prev, label: '上一章' },
  next: { icon: IC.next, label: '下一章' },
  typo: { icon: '', label: '排版', text: 'Aa' },
  night: { icon: IC.moon, label: '夜间' },
  more: { icon: IC.more, label: '更多' },
};
const MENU_DEFAULT = { r1: ['toc', 'bmk', 'find', 'auto', 'tts', 'purify'], r2: ['prev', 'next', 'typo', 'night', 'more'] };
/** 校正排布配置：去重、滤掉失效 key、把新增功能补进第一行 */
function normMenuCfg(cfg) {
  const keys = Object.keys(MENU_ITEMS);
  const uniq = (arr) => [...new Set((Array.isArray(arr) ? arr : []).filter((k) => keys.includes(k)))];
  const r1 = uniq(cfg && cfg.r1);
  const r2 = uniq(cfg && cfg.r2);
  const hidden = uniq(cfg && cfg.hidden).filter((k) => !r1.includes(k) && !r2.includes(k));
  const missing = keys.filter((k) => !r1.includes(k) && !r2.includes(k) && !hidden.includes(k));
  return { r1: [...r1, ...missing], r2, hidden };
}

export async function renderReader(view, id, index, startHidden = false) {
  const myGen = ++gRenderGen;
  // 本轮渲染对应的视图代号（route 每次接管都会 +1）。中途被别的路由抢走时，
  // 这里能立刻发现，避免继续往已被清空/替换的 DOM 上绑监听。
  const myRoute = window.__routeGen || 0;
  const stale = () => myGen !== gRenderGen || myRoute !== (window.__routeGen || 0);
  // 重渲染（如主题切换）前先归还上一次注册的全局监听/朗读，避免累积
  if (typeof window.__readerCleanup === 'function') {
    try {
      window.__readerCleanup();
    } catch (e) {
      /* 忽略清理异常 */
    }
    window.__readerCleanup = null;
  }
  // 目录抽屉挂在 body 上，翻章/重渲染前先移除旧实例，避免残留孤儿节点
  $$('.toc-mask, .toc-drawer').forEach((e) => e.remove());
  // 进入/重渲染阅读页：重置全书搜索页的历史占位状态（声明亦在此处，避免 TDZ）。
  // 否则上次打开「搜索结果页」后切走（fpPushed=true 但 find-page 已被路由重渲染抹掉），
  // 本次再打开会误判「已 push」，不再压入正确的历史项，导致 history 栈错配。
  let fpPushed = false;
  // 换书：上一本书的全书搜索立即作废（否则在新书里继续跑/结果张冠李戴）
  if (gFind.bookId && gFind.bookId !== id) cancelFindSession();
  gFind.bookId = id;
  const book = findBook(id);
  if (!book) {
    view.innerHTML = '<div class="empty"><h2>书籍不存在</h2><p><a href="#/shelf">回书架</a></p></div>';
    return;
  }
  const total = book.chapters.length;
  index = Math.max(0, Math.min(index, total - 1));
  let chapter = book.chapters[index];
  if (!chapter) {
    view.innerHTML = '<div class="empty"><h2>目录为空</h2><p>回详情页刷新目录。</p></div>';
    return;
  }
  const src = state.sources.find((s) => s.bookSourceUrl === book.sourceUrl);
  // 一次事务取回全部设置。原来是 11 个 await 串行读，点开一本书要排 11 次 IndexedDB
  // 往返才见到正文，手机上就是一眼可见的「点了没反应」。
  const S = await settings.getAll();
  let fontSize = S.readerFontSize ?? 18;
  // 深浅色单一真源：全局主题（设置页浅色/深色/跟随系统）决定阅读页是深色还是浅色。
  // 阅读页只额外记住「浅色时用哪种纸色」——深色统一走 dark，浅色用用户选的纸白/米黄/青石。
  let lightTheme = S.readerLightTheme ?? 'paper';
  if (!['paper', 'cream', 'gray'].includes(lightTheme)) lightTheme = 'paper';
  let darkMode = docThemeNow() === 'dark';
  let theme = darkMode ? 'dark' : lightTheme;
  let lh = Number(S.readerLineHeight ?? LH_DEFAULT) || LH_DEFAULT;
  let pg = Number(S.readerParaGap ?? PG_DEFAULT);
  if (!Number.isFinite(pg)) pg = PG_DEFAULT;
  let fontFam = S.readerFontFamily ?? 'song';
  const savedWeight = S.readerFontWeight ?? 'medium';
  let weight = WEIGHTS.some((w) => w[0] === savedWeight) ? savedWeight : 'medium';
  let weightVal = (WEIGHTS.find((w) => w[0] === weight) || WEIGHTS[2])[2];
  // 页边距：数值 px（12–44）。兼容旧档位 key（narrow/md/wide）映射到对应数值
  const savedMargin = S.readerMargin ?? 16;
  let marginPx = typeof savedMargin === 'number' ? savedMargin : (MARGINS.find((m) => m[0] === savedMargin) || MARGINS[1])[2];
  marginPx = Math.min(44, Math.max(12, Math.round(marginPx)));
  let brightness = Math.min(1, Math.max(0.5, Number(S.readerBrightness ?? 1) || 1));
  // 底部工具坞图标排布（两行顺序 + 隐藏项）
  let menuCfg = normMenuCfg(S.readerMenuLayout ?? MENU_DEFAULT);
  let rateIdx = 1;
  let ttsOn = false;
  /** 朗读落点：分页记页号，滚动记当前段元素 —— 底部小横条据此判断「你是否还在朗读页」 */
  let ttsAnchorPage = -1;
  let ttsAnchorEl = null;

  // 翻页方式：首次按视口宽度默认（手机左右翻页 / 桌面上下滚动），选择后记住
  let pageMode = S.readerPageMode ?? '';
  if (pageMode !== 'page' && pageMode !== 'scroll') {
    pageMode = window.matchMedia('(max-width:760px)').matches ? 'page' : 'scroll';
  }
  const paged = pageMode === 'page';
  document.body.classList.toggle('reader-paged', paged);
  // 阅读页沉浸：隐藏外层底部导航（离开阅读页由路由还原）
  document.body.classList.add('in-reader');
  // 原生沉浸：隐藏状态栏/导航栏，并用阅读主题色填充（避免露黑底）
  try {
    if (window.ModouBridge && window.ModouBridge.setImmersive) {
      window.ModouBridge.setImmersive(true, RBG[theme] || RBG.paper);
    }
  } catch (e) {
    /* 忽略 */
  }
  // 工具坞第二行是「上一章/下一章」（整章跳转）；分页翻页靠点按/拖拽/键盘，
  // 章节边界允许跨章（标准阅读器行为：左滑/右滑/方向键均可前后翻章），
  // 仅书的首章首屏、末章末页不跨（无更多章节可去）。

  // 分页模式：恢复上次阅读页（同章才恢复；跨章翻页由 gPendingPage 指定落点）
  let restorePage = 0;
  if (paged) {
    if (gPendingPage != null && gPendingPage >= 0) {
      restorePage = gPendingPage;
    } else {
      const prog = await getProgress(id);
      if (prog && prog.index === index) restorePage = Math.max(0, Number(prog.page) || 0);
    }
  }

  // 沉浸主题：阅读页整体（侧栏/背景）跟随阅读主题，离开时由路由还原
  if (theme === 'paper') document.body.removeAttribute('data-reader-theme');
  else document.body.dataset.readerTheme = theme;
  // 深浅色统一由全局主题驱动，这里不再用 setGlobalDark 制造阅读页与设置页的不一致

  /**
   * 跨章翻页时，旧 DOM 还在（用户看到的是旧页静止）。此时先把新章正文取到手，
   * 再重建骨架——否则顺序反过来（先重建 → 再 await 取正文）会画出一帧空正文，
   * 那就是跨章翻页"闪一下"的第二个来源。
   */
  let prefetchedRaw = null;
  let prefetchedFor = -1;
  if (paged && gPendingSlide) {
    const key = id + '||' + index;
    try {
      if (book.preview) {
        prefetchedRaw = previewChapterGet(index);
      } else {
        const c = await db.get('chapters', key);
        if (c) prefetchedRaw = c.content;
      }
      if (prefetchedRaw == null && src) {
        const r = await src.getContent(book, book.chapters[index]);
        if (!r.error && r.content) {
          prefetchedRaw = r.content;
          if (book.preview) previewChapterPut(index, r.content);
          else {
            try {
              await db.put('chapters', { id: key, content: r.content, cachedAt: Date.now() });
            } catch (e) {
              /* 忽略缓存写失败 */
            }
          }
        }
      }
      prefetchedFor = index;
      if (prefetchedRaw != null) rawMemSet(index, prefetchedRaw);
    } catch (e) {
      prefetchedRaw = null;
    }
  }

  // 跨章翻页：外壳（顶栏/工具坞/正文容器）没有变化，复用即可。
  // 整壳重建要创建上千个节点，而且动画必须等它做完才开始 → 用户感觉「卡一下」；
  // 普通翻页只是一次 transform 过渡，没有这段同步开销。
  const reuseShell = !!gPendingSlide && !!view.querySelector('#rd-shell');
  if (!reuseShell) {
    view.innerHTML = `
      <div class="rd-shell" id="rd-shell">
        <header class="rd-head" id="reader-top">
          <a class="rd-circ" id="rd-back" href="#/book/${encodeURIComponent(id)}" aria-label="返回详情">${IC.back}</a>
          <div class="rd-meta">
            <span class="rd-book">${esc(book.name)}</span>
            <span class="rd-chap reader-chap" id="r-chap">${index + 1} / ${total}</span>
          </div>
          <span class="rd-circ" style="visibility:hidden"></span>
        </header>
        <div class="auto-tick" id="auto-tick" hidden><i></i></div>

        <div class="find-bar" id="find-bar" hidden>
          <input id="find-input" placeholder="搜索全书" enterkeyhint="search">
          <button class="find-count" id="find-count" title="查看全部结果">0/0</button>
          <button class="mini" id="find-prev" title="上一个">↑</button>
          <button class="mini" id="find-next" title="下一个">↓</button>
          <button class="mini" id="find-close" title="关闭">✕</button>
        </div>

        <div class="find-page" id="find-page" hidden>
          <div class="fp-head">
            <button class="fp-back" id="fp-back" aria-label="返回">‹</button>
            <div class="fp-title" id="fp-title">搜索结果</div>
            <button class="fp-again" id="fp-again" aria-label="重新搜索">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 5v6h-6"/></svg>
            </button>
          </div>
          <form class="fp-search" id="fp-form">
            <input id="fp-input" placeholder="搜索全书" enterkeyhint="search" autocomplete="off">
            <button type="submit" class="fp-go" aria-label="搜索">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="6.6"/><path d="M15.9 15.9L20.6 20.6"/></svg>
            </button>
          </form>
          <div class="fp-list" id="fp-list"></div>
        </div>

        <div class="rd-stage" id="rd-stage">
          <article class="reader-body ${theme} font-${FONTS.some((f) => f[0] === fontFam) ? fontFam : 'song'}" id="r-body" style="font-size:${fontSize}px;line-height:${lh};font-weight:${weightVal};--para-gap:${pg}em;--page-pad-x:${marginPx}px">
            <h2 class="chapter-title">${esc(chapter.name || '')}</h2>
            <div class="loading">正文加载中…</div>
          </article>
        </div>

        <div class="rd-dock" id="rd-dock">
          <div class="rd-panel">
            <div class="tts-bar" id="tts-bar" hidden>
              <div class="tts-top">
                <span class="tts-now" id="tts-now"></span>
                <span class="tts-timer" id="tts-timer-left" hidden></span>
                <button class="tts-chip" id="tts-rate" title="语速">1.0x</button>
                <button class="tts-chip" id="tts-timer" title="定时停止">定时 关</button>
                <button class="tts-chip ghost" id="tts-exit">退出</button>
              </div>
              <div class="tts-ctl">
                <button class="tts-btn" id="tts-prev" title="上一句" aria-label="上一句">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M18.5 6.5v11L10 12z"/><path d="M6.5 5.5v13"/></svg>
                </button>
                <button class="tts-btn main" id="tts-toggle" title="暂停">
                  <svg class="ic-pause" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"><path d="M9.5 5.5v13M14.5 5.5v13"/></svg>
                  <svg class="ic-play" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linejoin="round"><path d="M8 4.8l11 7.2-11 7.2z"/></svg>
                  <span id="tts-toggle-lbl">暂停</span>
                </button>
                <button class="tts-btn" id="tts-next" title="下一句" aria-label="下一句">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M5.5 6.5v11L14 12z"/><path d="M17.5 5.5v13"/></svg>
                </button>
              </div>
            </div>
            <div class="rd-prog-meta">
              <span id="r-meta-chap">第 ${index + 1} / ${total} 章</span>
              <span id="r-meta-page">${paged ? '第 1 页' : '0%'}</span>
            </div>
            <div class="rd-prog" id="r-prog" role="slider" aria-label="本章阅读进度" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">
              <span class="rd-track"><i class="rd-fill"></i></span>
              <b class="rd-knob"></b>
            </div>
            <div class="rd-acts" id="rd-acts-1"></div>
            <div class="rd-acts rd-acts-2" id="rd-acts-2"></div>
          </div>
          <div class="rd-auto auto-bar" id="auto-bar" hidden>
            <span class="rd-auto-label">自动阅读</span>
            <b id="auto-speed"></b>
            <button class="rd-mini" id="auto-slower" title="减速">慢</button>
            <button class="rd-mini" id="auto-faster" title="加速">快</button>
            <button class="rd-mini stop" id="auto-stop">停止</button>
          </div>
        </div>

        <div class="listen-bar" id="listen-bar" hidden>
          <button class="lb-main" id="listen-main" aria-label="从本页开始听书">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 14v-2a8 8 0 0 1 16 0v2"/><path d="M4 14h2.2v5H5.2A1.2 1.2 0 0 1 4 17.8V14z"/><path d="M20 14h-2.2v5h1a1.2 1.2 0 0 0 1.2-1.2V14z"/></svg>
            <span>从此页开始听</span>
          </button>
          <button class="lb-x" id="listen-x" aria-label="退出听书" hidden>✕</button>
        </div>
      </div>
    `;
  }
  const stage = $('#rd-stage');
  /**
   * 幂等绑定：同一元素 + 同一事件类型只保留最后一个回调。
   * 跨章翻页现在复用外壳（不整壳重建），若仍用裸 addEventListener，
   * 每翻一章就多一个监听 —— 表现就是"点一次排版按钮弹出两个面板"。
   */
  const bindOnce = (el, type, fn, opts) => {
    if (!el) return;
    const key = '__h_' + type;
    if (el[key]) el.removeEventListener(type, el[key], el.__h_opts && el.__h_opts[type]);
    el[key] = fn;
    (el.__h_opts || (el.__h_opts = {}))[type] = opts;
    el.addEventListener(type, fn, opts);
  };

  document.title = `${book.name} · ${chapter.name}`;

  /* ---------- 底部工具坞：按用户排布渲染 + 事件委托 ---------- */
  const MENU_ACTIONS = {};
  const menuBtnHtml = (key) => {
    const it = MENU_ITEMS[key];
    if (!it) return '';
    const dis = (key === 'prev' && index === 0) || (key === 'next' && index >= total - 1) ? ' disabled' : '';
    const ic = it.text ? `<span class="aa-t">${it.text}</span>` : it.icon;
    return `<button class="rd-act" id="r-${key}" title="${it.label}"${dis}><span class="rd-ic">${ic}</span><em>${it.label}</em></button>`;
  };
  const paintMenu = () => {
    const r1 = $('#rd-acts-1');
    const r2 = $('#rd-acts-2');
    if (r1) {
      r1.innerHTML = menuCfg.r1.map(menuBtnHtml).join('');
      r1.hidden = !menuCfg.r1.length;
    }
    if (r2) {
      r2.innerHTML = menuCfg.r2.map(menuBtnHtml).join('');
      r2.hidden = !menuCfg.r2.length;
    }
    paintNight(); // 按钮被 innerHTML 重建了，夜间/日间高亮要跟着刷新
  };
  /* 工具坞按钮：委托分发；按钮 disabled 时不响应。
   * 实机反馈「图标有反应、但目录唤不起来，时有时无」——根因是 WebView 的 click 合成：
   * 手指在按钮上微移几像素、或点按节奏被当成滚动/双击缩放的前摇，浏览器会把这次
   * 合成 click 吞掉（300ms 点击延迟 + 双击缩放判定），可 :active 的按压反馈照常出现，
   * 于是「图标动了、动作没发生」。按钮上加了 `touch-action: manipulation`（见 styles.css），
   * 去掉 300ms 延迟与双击缩放，click 立刻、稳定地到达，从根上消除这种偶发失效。 */
  bindOnce($('#rd-dock'), 'click', (e) => {
    const btn = e.target.closest && e.target.closest('.rd-act');
    if (!btn || btn.disabled) return;
    const fn = MENU_ACTIONS[btn.id.replace(/^r-/, '')];
    if (fn) fn();
  });

  // 边距 / 亮度即时应用
  const applyMargin = (px, persist = true) => {
    marginPx = Math.min(44, Math.max(12, Math.round(Number(px) || 24)));
    const b = $('#r-body');
    if (b) b.style.setProperty('--page-pad-x', marginPx + 'px');
    if (persist) settings.set('readerMargin', marginPx);
    scheduleRepage();
  };
  /** 亮度遮罩：挂在 body 下的全屏黑层（盖过面板/抽屉），拖动滑杆整屏一起变暗，反馈直观 */
  const dimLayer = () => {
    let d = $('#dim-layer');
    if (!d) {
      d = h('div', { class: 'dim-layer', id: 'dim-layer' });
      document.body.append(d);
    }
    return d;
  };
  const applyBrightness = (v, persist = true) => {
    brightness = Math.min(1, Math.max(0.5, Number(v) || 1));
    // 用纯黑遮罩而非 CSS filter：部分 Android WebView 对 filter 支持不全（表现为完全没反应），
    // 遮罩方案零依赖、性能也更好（避免整层重绘）。
    dimLayer().style.opacity = String(Math.round((1 - brightness) * 0.9 * 100) / 100);
    if (persist) settings.set('readerBrightness', brightness);
  };

  /**
   * 跳章：用 replaceState 就地更新地址、不压历史栈。
   * 若沿用 location.hash=，每次翻章都会新增一条历史，Android 系统返回/侧滑
   * 就会逐章回退而不是退出阅读页（用户反馈的核心 bug）。
   * 跳章后直接重渲染阅读器——renderReader 开头会自行清理上一实例的监听与朗读。
   */
  /**
   * 跳章（目录 / 上下一章 / 书签 / 搜索结果 / 自动阅读）统一入口。
   * 优先走**原地切章**：整页重渲染要重跑设置读取 + 全套绑定 + 面板构建（实测 150~200ms），
   * 「从目录往后翻几十章」每次点都吃这一下，就特别明显。
   */
  const goChapter = (ni, targetPage = null, lock = false, startPx = 0) => {
    if (ni < 0 || ni >= total || ni === index) return;
    if (lock) gJumpLockUntil = Date.now() + JUMP_LOCK_MS; // 显式跳章：短锁定期防误触跨章
    if (paged) {
      const dir = gPendingSlide; // 自动阅读会预设滑入方向
      const cont = gAutoContinue;
      gPendingSlide = 0;
      gAutoContinue = false;
      gPendingPage = null;
      // 原地换不会像 renderReader 那样顺带清浮层，目录抽屉要在这里收掉
      $$('.toc-mask, .toc-drawer').forEach((e) => e.remove());
      if (switchChapterInPlace(ni, dir, startPx)) {
        if (cont) startAuto(); // 自动阅读跨章续读
        return;
      }
      // 正文还没预热：异步补上再原地切（仍比整页重渲染快一个数量级）
      switchChapterAsync(ni, dir, startPx, targetPage, lock);
      return;
    }
    gPendingPage = targetPage; // 跨章翻页落点：null=按进度，>=0=指定页，-1=末页
    try {
      history.replaceState(null, '', `#/read/${encodeURIComponent(id)}/${ni}`);
    } catch (e) {
      /* 个别环境限制 replaceState，忽略即可 */
    }
    // 保持当前沉浸状态（隐藏则继续隐藏，显示则继续显示）
    renderReader(view, id, ni, view.classList.contains('chrome-hidden'));
  };
  const go = (d) => goChapter(index + d, null, true);

  /* ---------- 目录抽屉 ---------- */
  const drawer = createTocDrawer({
    book,
    currentIndex: () => index,
    onJump: (i) => {
      if (i === index) {
        if (paged) showPage(0);
        else stage.scrollTo({ top: 0 });
        return;
      }
      goChapter(i, null, true);
    },
    onJumpBookmark: async (bm) => {
      if (bm.chapterIndex === index) {
        if (paged) showPage(bm.page || 0);
        else stage.scrollTo({ top: 0 });
        await saveProgress(id, index, chapter.url, bm.page || 0);
      } else {
        await db.put('progress', {
          bookId: id,
          index: bm.chapterIndex,
          chapterUrl: book.chapters[bm.chapterIndex]?.url,
          page: bm.page || 0,
          updatedAt: Date.now(),
        });
        goChapter(bm.chapterIndex, bm.page || 0, true); // 原地切章不会回读 progress，必须显式给页号
      }
    },
  });
  MENU_ACTIONS.toc = () => drawer.open();

  /* ---------- 书签 ---------- */
  const paintTicks = async () => {
    const bar = $('#r-prog');
    if (!bar) return;
    $$('.bmk-tick', bar).forEach((e) => e.remove());
    if (!paged || !pageEls.length) return;
    const bms = (await getBookmarks(id)).filter((b) => b.chapterIndex === index);
    const n = Math.max(1, pageEls.length);
    for (const b of bms) {
      bar.append(h('i', { class: 'bmk-tick', style: `left:${Math.min(100, ((b.page || 0) / Math.max(1, n - 1)) * 100)}%` }));
    }
  };
  MENU_ACTIONS.bmk = async () => {
    let text = '';
    if (paged && pageEls[pageIdx]) {
      const p = pageEls[pageIdx].querySelector('p');
      text = (p?.textContent || '').slice(0, 40);
    } else {
      const p = $('#r-body .content p');
      text = (p?.textContent || '').slice(0, 40);
    }
    await addBookmark({
      bookId: id,
      bookName: book.name,
      chapterIndex: index,
      chapterName: chapter.name,
      page: paged ? pageIdx : 0,
      text,
    });
    toast('已添加书签');
    paintTicks();
  };

  /* ---------- 分页阅读引擎 ---------- */
  let chapterHtml = ''; // 净化后的正文 HTML（含章题），重分页时以此为原料
  /**
   * 原始正文内存缓存（章号 → 未净化的 HTML）。
   * 跨章翻页要走「原地切章」就必须同步拿到正文；本地书正文在 IndexedDB 里，
   * 取一次至少要等一个任务轮次（≈几毫秒，会让动画晚启动），所以读过的章节留一份在内存。
   * 由 chapterHtmlAt 顺手写入，prefetchNeighbors 在空闲时预热相邻章。
   */
  const rawMem = new Map();
  /**
   * rawMem 原来是只进不出的 Map：读得越多，整本书（甚至多本书）的正文 HTML 就
   * 一直留在 WebView 堆里。500MB 的书读一遍 = 再驻留一份 500MB，这是「占用是书
   * 大小十几倍」最直接的一块。Legado 的 ACache 有 sizeLimit/countLimit，这里给
   * rawMem 同样的两道闸：最多留 6 章、正文合计 8M 字符（实际 UTF-16 约 16MB）。
   * 当前章和刚写入的章永不淘汰，其余按 LRU 出队；需要时从 chapters 表重取。
   */
  const RAW_MEM_MAX_CHAPTERS = 6;
  const RAW_MEM_MAX_CHARS = 8 * 1024 * 1024;
  let rawMemChars = 0;
  const rawMemSet = (k, v) => {
    if (typeof v !== 'string' || !v) return;
    const old = rawMem.get(k);
    if (old != null) rawMemChars -= old.length;
    rawMem.delete(k);
    rawMem.set(k, v);
    rawMemChars += v.length;
    while ((rawMem.size > RAW_MEM_MAX_CHAPTERS || rawMemChars > RAW_MEM_MAX_CHARS) && rawMem.size > 1) {
      let victim = null;
      for (const key of rawMem.keys()) {
        if (key !== index && key !== k) {
          victim = key;
          break;
        }
      }
      if (victim == null) break;
      const gone = rawMem.get(victim);
      rawMem.delete(victim);
      rawMemChars -= gone ? gone.length : 0;
    }
  };
  const rawMemGet = (k) => {
    const v = rawMem.get(k);
    if (v != null) {
      rawMem.delete(k);
      rawMem.set(k, v); // 命中即置为最近使用
    }
    return v;
  };
  let switchWarmTimer = 0; // 切章后延迟预热的定时器（避开滑入动画）
  let switchGen = 0; // 切章代号：异步切章回来时若已换人，直接作废
  let pagesEl = null;
  let pageEls = [];
  let pageIdx = 0;
  let suppressClick = false; // 拖拽后抑制随后的 click
  let clickGuardUntil = 0; // 划词手势抬手后那个合成 click 的屏蔽窗口（ms 时间戳）
  let repageTimer = 0;
  let resetPos = null; // 由 bindPaging 注入：选区起手、点按这些外层出口也要把版面收回原位
  /**
   * 分页态把 .rd-stage 的滚动偏移钉回 0。
   * 这是「整页错位、完全用不了」的真正成因：页是并排摆的，溢出会撑大 scrollWidth，
   * 而 WebView 长按划词时会把光标位置 scrollIntoView —— scrollLeft 被推到接近一页宽，
   * 屏幕上就变成「左边一窄条是上一屏行末、右边是下一页开头」。transform 一切正常，
   * 所以以前所有只看 transform 的兜底全部空转。这里连滚动偏移一起收。
   * （CSS 侧已用 overflow:clip 从根上断掉滚动能力，这里兜老内核。）
   */
  const clearStageScroll = () => {
    if (stage.scrollLeft) stage.scrollLeft = 0;
    if (paged && stage.scrollTop) stage.scrollTop = 0;
  };

  /** 纯文本节点包成 <p>，保证装页迭代只面对块级元素 */
  const wrapTextNodes = (root) => {
    [...root.childNodes].forEach((n) => {
      if (n.nodeType !== 3) return;
      if (!n.textContent.trim()) {
        n.remove();
        return;
      }
      const p = document.createElement('p');
      p.textContent = n.textContent;
      n.replaceWith(p);
    });
  };

  /* ---------- 页眉页脚（分页模式可选） ----------
   * 设置：{ head: 'chapter'|'book'|'none', foot: ['page','time','battery'] 的子集 }
   * 高度必须从可用正文高度里扣除，否则正文会被盖住；所以这里先实测再分页。 */
  // 默认「左页码 + 右时间」：中间那格留给听书小横条
  const HF_DEFAULT = { head: 'chapter', foot: ['page', 'time'] };
  let hfCfg = await settings.get('readerHeadFoot', HF_DEFAULT);
  if (!hfCfg || typeof hfCfg !== 'object') hfCfg = HF_DEFAULT;
  hfCfg = { head: hfCfg.head || 'none', foot: Array.isArray(hfCfg.foot) ? hfCfg.foot : [] };
  // 旧存档的默认只有「页码」，右侧永远空着（看着就是一堆字挤在左下角）。
  // 升到 v2 时补一个「时间」，页脚才是左一格右一格；用户之后自己取消勾选则不再改。
  if (hfCfg.v !== 2) {
    if (hfCfg.foot.length === 1 && hfCfg.foot[0] === 'page') hfCfg.foot = ['page', 'time'];
    hfCfg.v = 2;
  }
  // 页眉可选项 / 页脚可选项（页脚支持多选组合）
  const HEAD_OPTS = [
    ['none', '不显示'],
    ['chapter', '章节名'],
    ['book', '书名'],
    ['both', '书名+章节'],
  ];
  const FOOT_LABEL = { page: '页码', time: '时间', battery: '电量', bookPct: '全书进度', chapPct: '本章进度', left: '剩余章数' };
  let showHead = false;
  let showFoot = false;
  let headText = '';
  let batteryPct = null; // 电量：异步取得，取不到就不显示
  if (hfCfg.foot.includes('battery') && typeof navigator !== 'undefined' && navigator.getBattery) {
    navigator
      .getBattery()
      .then((bt) => {
        batteryPct = Math.round((bt.level || 0) * 100);
        bt.addEventListener('levelchange', () => {
          batteryPct = Math.round((bt.level || 0) * 100);
          paintPgFooters();
        });
        paintPgFooters();
      })
      .catch(() => {});
  }
  const nowHM = () => {
    const d = new Date();
    return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  };

  /** 填回每页页脚：总页数要等分页完成才知道，因此延后到 paginate 之后 */
  const paintPgFooters = () => {
    if (!showFoot || !pageEls.length) return;
    const n = pageEls.length;
    const make = (k, i) => {
      if (k === 'page') return `${i + 1} / ${n}`;
      if (k === 'time') return nowHM();
      if (k === 'battery') return batteryPct == null ? '' : batteryPct + '%';
      // 全书进度按「章 + 章内页位置」平滑估算，比只按章更贴近真实阅读位置
      if (k === 'bookPct') return `全书 ${Math.round(((index + (i + 1) / n) / Math.max(1, total)) * 100)}%`;
      if (k === 'chapPct') return `本章 ${Math.round(((i + 1) / n) * 100)}%`;
      if (k === 'left') {
        const rest = total - 1 - index;
        return rest > 0 ? `剩 ${rest} 章` : '末章';
      }
      return '';
    };
    pageEls.forEach((pg, i) => {
      const f = pg.querySelector('.pg-foot');
      if (!f) return;
      // 位置类（页码/剩余章数）靠左，状态类（时间/电量/进度）靠右，中间那格留给听书小横条。
      // 只选了一侧时另一侧留空占位，space-between 才不会把所有项挤到一头。
      const all = hfCfg.foot.slice();
      let left = all.filter((x) => x === 'page' || x === 'left');
      let right = all.filter((x) => x !== 'page' && x !== 'left');
      if (!left.length && right.length > 1) left = [right.shift()];
      const txt = (arr) => arr.map((x) => make(x, i)).filter(Boolean).join(' · ');
      f.textContent = '';
      f.append(h('span', { class: 'pg-side' }, txt(left)));
      f.append(h('i', { class: 'pg-mid' })); // 中间空位：朗读时展开到「从本页听」胶囊的宽度
      f.append(h('span', { class: 'pg-side' }, txt(right)));
    });
  };
  // 时钟页脚：每分钟对齐刷新（电量由 levelchange 驱动）
  let hfTimer = 0;
  const startFootClock = () => {
    clearInterval(hfTimer);
    hfTimer = 0;
    if (!hfCfg.foot.includes('time')) return;
    hfTimer = setInterval(paintPgFooters, 30000);
  };

  /** 重分页：以 chapterHtml 为原料，经隐藏测量容器切分、贪心装页 */
  /**
   * @param restore 恢复到的页号（-1 表示末页，由调用方 slideIn 处理）
   * @param opts.deferFirstPaint 跨章滑入时置 true：不要把首帧画在正位，
   *        否则会先看到「新章首页在原位」再被挪走滑入（就是那一下闪）
   */
  const paginate = (restore = 0, opts = {}) => {
    const offline = !!opts.offline; // 离线预分页：在隐藏壳里跑，不碰当前画面、不动全局状态
    if (!offline && !paged) return;
    if (!offline) {
      // 实时分页会把 #r-body 整个重建（innerHTML），预分页的隐藏槽位随之被清掉，
      // 缓存里的元素已脱离文档 —— 必须一并作废，否则跨章会显示空白。
      pageCache.clear();
      pageCacheSig = '';
    }
    const srcHtml = opts.html != null ? opts.html : chapterHtml;
    if (!srcHtml) return;
    const b = opts.host || $('#r-body');
    if (!b) return;
    const chap = opts.chapter || chapter;
    b.innerHTML = srcHtml;
    markedCi = -1; // 正文重建，旧的搜索高亮已失效
    markedKw = '';
    // 隐藏测量容器（height:auto，visibility:hidden），与正式页同宽同内边距
    const measure = h('div', { class: 'page measure' });
    b.append(measure);
    while (b.firstChild && b.firstChild !== measure) measure.append(b.firstChild);
    // 展平 .content 包裹层，章题与段落同级
    const flat = [];
    for (const node of [...measure.childNodes]) {
      if (node.nodeType === 1 && node.classList && node.classList.contains('content')) {
        flat.push(...node.childNodes);
        node.remove();
      } else {
        flat.push(node);
      }
    }
    flat.forEach((n) => measure.append(n));
    wrapTextNodes(measure);
    // 正式页容器
    const pages = h('div', { class: 'pages' });
    b.append(pages);
    const probe = h('div', { class: 'page' });
    pages.append(probe);
    const pcs = getComputedStyle(probe);
    const padV = (parseFloat(pcs.paddingTop) || 0) + (parseFloat(pcs.paddingBottom) || 0);
    probe.remove();

    // 页眉页脚：把真实 DOM 挂进 .page 上下文实测高度（适应页边距/字体），
    // 再从可用正文高度里扣除——否则正文会被页眉/页脚压住。
    const hdText =
      hfCfg.head === 'book'
        ? String(book.name || '')
        : hfCfg.head === 'chapter'
          ? String(chap.name || '')
          : hfCfg.head === 'both'
            ? `${book.name || ''} · ${chap.name || ''}`
            : '';
    const sHd = hfCfg.head !== 'none' && !!hdText;
    const sFt = hfCfg.foot.length > 0;
    // 离线分页时全局的 headText/showHead/showFoot 属于「当前章」，目标章不能改它们
    if (!offline) {
      headText = hdText;
      showHead = sHd;
      showFoot = sFt;
    }
    let headH = 0;
    let footH = 0;
    if (sHd || sFt) {
      const pv = h('div', { class: 'page with-hf' });
      pages.append(pv);
      let ph = null;
      let pf = null;
      if (sHd) {
        // 「书名+章节」时分列左右两端（左书名右章节），其余单行居中展示
        ph =
          hfCfg.head === 'both'
            ? h('div', { class: 'pg-head' }, h('span', {}, String(book.name || '')), h('span', {}, String(chap.name || '')))
            : h('div', { class: 'pg-head' }, h('span', {}, hdText));
        pv.append(ph);
      }
      pv.append(h('div', { class: 'pg-body' }));
      if (sFt) {
        pf = h(
          'div',
          { class: 'pg-foot' },
          h('span', { class: 'pg-side' }, '1 / 1'),
          h('i', { class: 'pg-mid' }),
          h('span', { class: 'pg-side' }, '00:00')
        );
        pv.append(pf);
      }
      headH = ph ? ph.offsetHeight : 0;
      footH = pf ? pf.offsetHeight : 0;
      pv.remove();
    }
    // 小横条占的是页脚那一行的中间格子（左页码 / 中横条 / 右时间），不吃额外高度。
    // 开关朗读对版心的影响必须是 0：哪怕只差几像素，行断点也会重算——页数可能还一样，
    // 但每页的第一行会挪位（当前页和前面几页的内容跟着变）。
    // 只有页脚被关掉、没那一行可借时，才把整条算进版心。
    const lbH = !ttsOn || sFt ? 0 : ($('#listen-bar')?.offsetHeight || 26) + 6;
    const avail = Math.max(0, b.clientHeight - padV - headH - footH - lbH);

    /**
     * 测「某段文本在当前版心里能放下多少字」。
     * 旧实现按字数二分：每次 setTextContent + 读 offsetHeight 都是一次强制重排，
     * 一段 300 字要 9 次；一章几十段被切分就是数百次重排 —— 跨章翻页「卡一下」的主因之一。
     * 现在用「整段高度 / 行高 = 行数」反推每行字数，一次估算 + 最多 3 次修正，
     * 重排次数从 ~9 降到 ~2。
     * @param sampleEl 段落元素（同标签探针用来量）
     * @param maxH 本页剩余可用高度
     * @param knownH 已批量测过的整段高度（省一次重排）
     * @param knownLh 已缓存的该标签行高
     */
    const fitChars = (sampleEl, maxH, knownH, knownLh) => {
      const text = sampleEl.textContent || '';
      if (!text || maxH <= 0) return 0;
      const fullH = knownH || 0;
      const lh = knownLh || 0;
      if (fullH && lh && fullH <= maxH) return text.length; // 整段放得下：连探针都不用建
      const probeEl = sampleEl.cloneNode(false);
      probeEl.style.margin = '0';
      measure.append(probeEl);
      const done = (n) => {
        probeEl.remove();
        return n;
      };
      const h = fullH || (() => {
        probeEl.textContent = text;
        return probeEl.offsetHeight;
      })();
      const lineH = lh || parseFloat(getComputedStyle(probeEl).lineHeight) || 0;
      if (h <= maxH) return done(text.length);
      if (lineH <= 0) return done(0);
      const fits = (n) => {
        probeEl.textContent = text.slice(0, n);
        return probeEl.offsetHeight <= maxH;
      };
      const lines = Math.max(1, Math.round(h / lineH));
      const perLine = Math.max(1, text.length / lines);
      const step = Math.max(1, Math.ceil(perLine));
      let guess = Math.max(0, Math.min(text.length, Math.round(Math.floor(maxH / lineH) * perLine)));
      if (!fits(guess)) {
        // 估多了：按整行递减，最多退 6 行
        let n = guess;
        for (let i = 0; i < 6 && n > 0; i++) {
          n = Math.max(0, n - step);
          if (fits(n)) return done(n);
        }
        // 退了 6 行还放不下（标点/英文导致每行字数波动很大时会出现）：
        // 必须二分兜底，绝不能把一个「放不下」的字数返回去 ——
        // 那一段会超出页高被裁掉，下一页又从它之后开始，读者看到的就是「少了一段」。
        let lo = 0;
        let hi = n;
        let best = 0;
        while (lo <= hi) {
          const mid = (lo + hi) >> 1;
          if (fits(mid)) {
            best = mid;
            lo = mid + 1;
          } else {
            hi = mid - 1;
          }
        }
        return done(best);
      }
      // 估少或刚好：在 [guess, guess+step] 内二分逼近「末行最满」的切点。
      // DOM 高度对末行字数是阶梯函数（末行多塞几个字高度不变），用整行步长去补必然跨过台阶、
      // 末行只剩几个字。二分在这里找 <=maxH 的最大 n，约 5~6 次探针即可把末行填到 95%+。
      let lo = guess;
      let hi = Math.min(text.length, guess + step);
      let best = guess;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        if (fits(mid)) {
          best = mid;
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      return done(best);
    };

    const isSplittable = (el) =>
      el.nodeType === 1 &&
      !(el.classList && el.classList.contains('chapter-title')) &&
      !(el.querySelector && el.querySelector('img')) &&
      (el.textContent || '').trim().length > 0;

    let cur = null; // 当前页（作为「是否已开页」的判据）
    let cbox = null; // 当前页的正文容器（.pg-body）
    let used = 0;
    const arr = [];
    const newPage = () => {
      cur = h('div', { class: 'page' + (sHd || sFt ? ' with-hf' : '') });
      arr.push(cur);
      pages.append(cur);
      if (sHd) cur.append(h('div', { class: 'pg-head' }, h('span', {}, hdText)));
      cbox = h('div', { class: 'pg-body' });
      cur.append(cbox);
      if (sFt) cur.append(h('div', { class: 'pg-foot' })); // 页码等分页完成后回填
      used = 0;
    };

    // 贪心装页：整段放得下就整段放；放不下就按剩余高度切一段过来，
    // 余下部分留到下一页（续段去掉首行缩进），尽量把每页填满。
    const lineHeights = new Map();
    const lineHOf = (el) => {
      const key = el.tagName;
      if (lineHeights.has(key)) return lineHeights.get(key);
      const lh = parseFloat(getComputedStyle(el).lineHeight) || 0;
      lineHeights.set(key, lh);
      return lh;
    };
    /** 把 kid 按 maxH 切成两段：head 落当前页，rest 插回队列下一轮。切不动返回 false */
    const splitKid = (kid, maxH, qi) => {
      const text = kid.textContent || '';
      const knownH = kidH.has(kid) ? kidH.get(kid) : 0;
      const n = fitChars(kid, Math.max(0, maxH), knownH, lineHOf(kid));
      if (!text || n <= 0 || n >= text.length) return false;
      const head = kid.cloneNode(false);
      head.textContent = text.slice(0, n);
      head.style.marginBottom = '0'; // 本页最后一块，不留段后距
      cbox.append(head);
      const rest = kid.cloneNode(false);
      rest.classList.add('cont'); // 续段：不缩进
      rest.textContent = text.slice(n);
      measure.append(rest); // 挂回测量容器，下一轮才能量到真实高度
      if (kidM.has(kid)) kidM.set(rest, kidM.get(kid)); // 续段沿用原段落的段后距，别当成 0
      queue.splice(qi + 1, 0, rest); // 余下部分进入下一轮（落到新页）
      kid.remove();
      cur = null; // 本页已填满
      used = avail;
      return true;
    };

    // 给每段打上章节内的稳定序号：重排（字号/朗读/横屏）后靠它找回同一处，
    // 比按字数累加稳 —— 切分段会带着同一个序号，不会因为文本重复而认错行。
    let mi = 0;
    for (const k of [...measure.children]) k.dataset.mi = String(mi++);
    const queue = [...measure.children];
    // 批量测量高度/段距：先一次布局把整章读完，再开始搬节点。
    // 原来在循环里「读 offsetHeight → append 到页里（改 DOM）→ 再读」是典型的 layout thrashing，
    // 长章（上百段）会触发上百次同步重排 —— 这就是跨章翻页"卡一下"的主要来源。
    const kidH = new Map();
    const kidM = new Map();
    for (const k of queue) {
      kidH.set(k, k.offsetHeight);
      kidM.set(k, parseFloat(getComputedStyle(k).marginBottom) || 0);
    }
    for (let qi = 0; qi < queue.length; qi++) {
      const kid = queue[qi];
      if (!cur) newPage();
      const kh = kidH.has(kid) ? kidH.get(kid) : kid.offsetHeight;
      // 切分段（rest）是运行时新建的，不在这张表里：不能当成「没有段后距」，
      // 否则它会白占下一段的 26px，页尾被 overflow 裁掉一行。
      const km = kidM.has(kid) ? kidM.get(kid) : parseFloat(getComputedStyle(kid).marginBottom) || 0;
      const canSplit = isSplittable(kid);
      let room = avail - used;
      // used 里存的是「含段后距」的占位高度，所以这里只看 kh 就够：
      // 上一段的段距已经算进 used，而本段的段距要等下一段进来才真正占位。
      // （曾经在 splitKid 里新建续段时把段距漏记成 0，页尾就会多塞一段被裁掉 —— 见下面的 km）
      if (kh <= room + 1) {
        cbox.append(kid);
        used += kh + km;
        continue;
      }
      // 本页放不下：可切分就先切一段把本页补满
      if (canSplit) {
        const lh = lineHOf(kid);
        if (room >= (lh || 1) && splitKid(kid, room - 2, qi)) continue;
      }
      // 换到新的一页，用整页高度重新量：能整段放下就放，否则按整页切一段。
      // （原先换页后是无条件整段塞入，遇到比一整页还高的长段落就会溢出被裁掉）
      newPage();
      room = avail - used;
      // 新页的正文容器是空的，页首这段不需要段后距
      if (kh <= room + 1) {
        cbox.append(kid);
        used += kh + km;
        continue;
      }
      if (canSplit && splitKid(kid, room - 2, qi)) continue;
      // 整段比一整页还高且切不动（或图片/章题等不可分割块）：塞进新页，超高部分由页裁切
      cbox.append(kid);
      used += kh + km;
    }
    measure.remove();
    // 关键：用 transform 定位而非 left —— left 百分比会撑大 .rd-stage 的 scrollWidth，
    // 选区跨页时 WebView 的 scrollIntoView 会把 scrollLeft 改掉，整屏横向偏移、多页并排错位。
    // transform 不参与滚动区域计算，scrollWidth 恒等于视口宽，浏览器无从横向滚动。
    arr.forEach((p, i) => {
      p.style.transform = `translateX(${i * 100}%)`;
    });
    if (offline) return { pages, pageEls: arr, headText: hdText, showHead: sHd, showFoot: sFt };
    pageEls = arr;
    pagesEl = pages;
    paintPgFooters(); // 总页数此刻才确定 → 回填页码
    startFootClock();
    bindPaging();
    const first = Math.min(Math.max(0, restore), Math.max(0, arr.length - 1));
    if (opts.deferFirstPaint) {
      // 跨章滑入：**不要**先把新页画在正位——否则会先闪现一帧"新章首页在原位"，
      // 然后才被挪到旁边滑进来，这就是用户看到的"闪一下"。首次定位交给 slideIn 独占。
      // 注意：不能用 gPendingSlide 判断——paint() 在调用前已把它清零（旧逻辑因此失效）。
      pageIdx = first;
    } else {
      showPage(first, false);
    }
    paintTicks();
  };

  const updateChapLabel = () => {
    const el = $('#r-chap');
    if (el) el.textContent = purifyTitle(chapter.name || '') || `第 ${index + 1} 章`;
    const mc = $('#r-meta-chap');
    if (mc) mc.textContent = `第 ${index + 1} / ${total} 章`;
    // 原地切章复用外壳，不会重建工具坞 —— 首/末章的「上一章/下一章」禁用态必须在这里刷新，
    // 否则从第 1 章翻到第 2 章后，「上一章」仍然是灰的，点不动
    const pv = $('#r-prev');
    if (pv) pv.disabled = index <= 0;
    const nx = $('#r-next');
    if (nx) nx.disabled = index >= total - 1;
    const mp = $('#r-meta-page');
    if (mp) {
      if (paged) {
        mp.textContent = `第 ${pageIdx + 1} / ${Math.max(1, pageEls.length)} 页`;
      } else {
        const max = stage.scrollHeight - stage.clientHeight;
        mp.textContent = max > 0 ? `本章 ${Math.round((stage.scrollTop / max) * 100)}%` : '本章 100%';
      }
    }
  };

  const showPage = (i, anim = true) => {
    if (!pagesEl || !pageEls.length) return;
    if (csel) clearSel(); // 换页后旧选区的高亮/竖线坐标全部失效
    pageIdx = Math.max(0, Math.min(i, pageEls.length - 1));
    pagesEl.classList.toggle('no-anim', !anim);
    pagesEl.style.transform = `translateX(${-pageIdx * 100}%)`;
    updateChapLabel();
    paintProg();
    paintListenBar(); // 翻到别的页后，小横条要从「暂停播放」切回「从此页开始听」
    maybePrimeNext(); // 快到章尾就把下一章预分页，跨章时才不用现算
  };

  /**
   * 朗读开关会让版心少掉（/多出）横条那一行，必须重排；
   * 但重排后不能让读者「跳到别的页」——先记下当前页第一段在整章里的文字偏移，
   * 重排完再按偏移找回同一处。字号变化沿用原来的按页号近似即可。
   */
  const pageTextStart = (pi) => {
    let n = 0;
    for (let i = 0; i < pi && i < pageEls.length; i++) {
      const body = pageEls[i].querySelector('.pg-body');
      n += body ? body.textContent.length : 0;
    }
    return n;
  };
  const pageAtText = (off) => {
    let n = 0;
    for (let i = 0; i < pageEls.length; i++) {
      const body = pageEls[i].querySelector('.pg-body');
      const len = body ? body.textContent.length : 0;
      if (n + len > off) return i;
      n += len;
    }
    return Math.max(0, pageEls.length - 1);
  };
  /**
   * 当前页首行的定位锚：段落序号（paginate 打的 data-mi）+ 行首文字。
   * 只按字数累加定位是不够稳的：切分会把一个段落拆成多个 <p>，重排后每页边界前后挪一点，
   * 累加值就落到隔壁页（实机表现为开关朗读后页码往回跳）。序号才是稳定的身份。
   */
  const pageAnchor = () => {
    const page = pageEls[pageIdx];
    if (!page) return null;
    const el = [...page.querySelectorAll('.pg-body > *')].find((e) => e.textContent.trim());
    return el ? { mi: el.dataset.mi || '', head: el.textContent.trim().slice(0, 8) } : null;
  };
  const pageOfAnchor = (a) => {
    if (!a || !a.mi) return -1;
    const kidsOf = (i) => [...pageEls[i].querySelectorAll('.pg-body > *')];
    // 先找「同一段且同一处开头」的页；找不到就从后往前找这段最后一次出现的页 ——
    // 页首往往是某段被切开的后半截，它的位置一定在该段落的最后一次落点上
    for (let i = 0; i < pageEls.length; i++) {
      if (kidsOf(i).some((e) => e.dataset.mi === a.mi && e.textContent.trim().startsWith(a.head))) return i;
    }
    for (let i = pageEls.length - 1; i >= 0; i--) {
      if (kidsOf(i).some((e) => e.dataset.mi === a.mi)) return i;
    }
    return -1;
  };
  /** 按当前朗读状态重排版心（横条占一行 / 让回一行），位置跟着文字走 */
  const restack = () => {
    if (!paged) return;
    const a = pageAnchor();
    const off = pageTextStart(pageIdx);
    paginate(0, { deferFirstPaint: true });
    const i = pageOfAnchor(a);
    showPage(i >= 0 ? i : pageAtText(off), false);
  };

  /** 跨章滑入：新章首页从右滑入 / 上一章末页从左滑入，与章内翻页共用同一套 transition */
  const slideIn = (dir, startPx = 0, ghost = null, ghostIdx = 0) => {
    if (!pagesEl || !pageEls.length) return;
    const target = dir < 0 ? pageEls.length - 1 : 0;
    pageIdx = Math.max(0, Math.min(target, pageEls.length - 1));
    $$('#r-body .pages.ghost').forEach((e) => {
      if (e !== ghost) disposePages(e); // 清掉上一次没走完的旧页（连槽位一起，否则槽位会越积越多）
    });
    if (ghost) {
      ghost.classList.add('ghost');
      ghost.classList.remove('dragging');
      ghost.style.pointerEvents = 'none';
      ghost.classList.add('no-anim');
      ghost.style.transform = `translateX(calc(${startPx}px + ${-ghostIdx * 100}%))`;
    }
    pagesEl.classList.add('no-anim');
    // 起始位置不能只写 dir*100%：往回（dir=-1）落在上一章「最后一页」，目标页是 pageIdx 而非第 0 页，
    // 必须按 (dir - pageIdx) 偏移，否则末页会从右侧滑入（与前进动画不对称，像倒着播）。
    // 前进（dir=1）落新章首页（pageIdx=0），(dir-pageIdx)=dir，等价于原来的 dir*100%，不受影响。
    pagesEl.style.transform = `translateX(calc(${startPx}px + ${(dir - pageIdx) * 100}%))`;
    updateChapLabel();
    paintProg();
    // 强制一次样式计算，让上面「无过渡定位」落定，再改成目标位置 → 过渡当帧就开始。
    // 原来用嵌套双 rAF（等两帧）才开始，等于凭空多出 ~32ms 的启动延迟。
    // 读一次 offsetHeight 会把整篇文档的样式都刷新，两个容器一起生效。
    void pagesEl.offsetHeight;
    pagesEl.classList.remove('no-anim');
    // 注意：目标位置必须跟着 pageIdx 走。
    // 往回翻（dir=-1）落在上一章**末页**，容器要停在 -(n-1)*100%；
    // 这里写死 0% 的话，画面会停在第 1 页，只有角标显示「第 n / n 页」——
    // 就是「往前翻却翻到上一章第一页」的那个 bug。
    pagesEl.style.transform = `translateX(${-pageIdx * 100}%)`;
    if (ghost) {
      ghost.classList.remove('no-anim');
      ghost.style.transform = `translateX(calc(${startPx}px + ${(-ghostIdx - dir) * 100}%))`;
      // 过渡结束后收拾掉旧页（动画 300ms，留点余量）；槽位在就整块删
      setTimeout(() => disposePages(ghost), 420);
    }
  };

  /* ---------- 进度条：本章进度，可点击/拖动跳转 ---------- */
  const chapterRatio = () => {
    if (paged) {
      const n = pageEls.length;
      return n > 1 ? pageIdx / (n - 1) : 1;
    }
    const max = stage.scrollHeight - stage.clientHeight;
    return max > 0 ? stage.scrollTop / max : 1;
  };

  const paintProg = () => {
    const bar = $('#r-prog');
    if (!bar) return;
    const r = Math.max(0, Math.min(1, chapterRatio() || 0));
    const pct = (r * 100).toFixed(2) + '%';
    const fill = bar.querySelector('.rd-fill');
    const knob = bar.querySelector('.rd-knob');
    if (fill) fill.style.width = pct;
    if (knob) knob.style.left = pct;
    bar.setAttribute('aria-valuenow', String(Math.round(r * 100)));
  };

  const seekRatio = (r) => {
    const x = Math.max(0, Math.min(1, r));
    if (paged) {
      const n = Math.max(1, pageEls.length);
      showPage(Math.round(x * (n - 1)));
      saveProgress(id, index, chapter.url, pageIdx);
    } else {
      const max = Math.max(0, stage.scrollHeight - stage.clientHeight);
      stage.scrollTop = x * max;
    }
    paintProg();
  };

  (() => {
    const bar = $('#r-prog');
    if (!bar) return;
    const ratioAt = (clientX) => {
      const rect = bar.getBoundingClientRect();
      return (clientX - rect.left) / Math.max(1, rect.width);
    };
    let dragging = false;
    const start = (x) => {
      dragging = true;
      bar.classList.add('dragging');
      seekRatio(ratioAt(x));
    };
    const move = (x) => {
      if (dragging) seekRatio(ratioAt(x));
    };
    const end = () => {
      if (!dragging) return;
      dragging = false;
      bar.classList.remove('dragging');
    };
    // pointer 优先；部分 WebView 不派发 pointer*，用 touch/mouse 兜底（dragging 去重）
    bindOnce(bar, 'pointerdown', (e) => {
      e.preventDefault();
      e.stopPropagation();
      start(e.clientX);
      try { bar.setPointerCapture(e.pointerId); } catch (_) { /* 忽略 */ }
    });
    bindOnce(bar, 'pointermove', (e) => { if (dragging) { e.preventDefault(); move(e.clientX); } });
    bindOnce(bar, 'pointerup', end);
    bindOnce(bar, 'pointercancel', end);
    bindOnce(bar, 'touchstart', (e) => { if (dragging) return; start(e.touches[0].clientX); e.preventDefault(); }, { passive: false });
    bindOnce(bar, 'touchmove', (e) => { if (!dragging) return; move(e.touches[0].clientX); e.preventDefault(); }, { passive: false });
    bindOnce(bar, 'touchend', end);
    bindOnce(bar, 'mousedown', (e) => { if (dragging) return; start(e.clientX); });
    bindOnce(window, 'mousemove', (e) => move(e.clientX));
    bindOnce(window, 'mouseup', end);
    if (!paged)
      bindOnce(
        stage,
        'scroll',
        () => {
          hideChrome();
          paintProg();
          updateChapLabel();
        },
        { passive: true }
      );
  })();

  /** 翻一页；章内正常翻，到边界则跨章（左滑/右滑/方向键/点按分区均可前后翻章） */
  const turnPage = (d, startPx = 0) => {
    if (!paged || !pageEls.length) return;
    const ni = pageIdx + d;
    if (ni >= 0 && ni < pageEls.length) {
      hideChrome();
      showPage(ni);
      saveProgress(id, index, chapter.url, pageIdx);
      // 朗读中翻到别的页：小横条文案要跟着从「暂停播放」变成「从此页开始听」
      if (ttsOn) paintListenBar();
    } else {
      // 跨章翻页：前进（d>0，左滑）落新章首页；后退（d<0，右滑）落上一章末页
      const nci = index + (d > 0 ? 1 : -1);
      if (nci < 0 || nci >= total) {
        // 书的首尾不跨：拖拽产生的偏移要归位，不能把页面停在半空
        showPage(pageIdx);
        return;
      }
      if (Date.now() < gJumpLockUntil) {
        // 显式跳章后的短锁定期内不跨（防误触退回）：同样归位
        showPage(pageIdx);
        return;
      }
      hideChrome();
      // 原地切章优先：正文已预热时是一条同步路径（不重建、不 await），
      // 手感与章内翻页一致；未就绪才退回完整渲染（它会加载并正常滑入）
      if (switchChapterInPlace(nci, d, startPx)) return;
      gPendingSlide = d; // 跨章后按正常翻页的方向滑入（与章内翻页动画一致）
      gSlideStartPx = startPx;
      goChapter(nci, d > 0 ? 0 : -1, false, startPx);
    }
  };

  /** 点按正文中部：显隐沉浸 chrome（顶栏 / 底部工具坞） */
  const toggleChrome = () => view.classList.toggle('chrome-hidden');
  /** 进入沉浸：翻页 / 滚动时自动收起工具栏（只在点亮状态才加，避免无谓重排） */
  const hideChrome = () => {
    if (!view.classList.contains('chrome-hidden')) view.classList.add('chrome-hidden');
  };

  /** 当前是否有正文选区：划词后点按/拖拽要优先服务于选区，不能顺手翻页。
   *  选区是自绘的（见下方「自绘选区」），所以直接看状态，不用再问 window.getSelection */
  const hasSel = () => !!(csel && csel.a !== csel.b);

  // 滚动模式：点按正文中部同样切换 chrome（忽略链接/按钮/选中文字）
  bindOnce(stage, 'click', (e) => {
    if (paged) return;
    if (e.target.closest && e.target.closest('a,button,input,textarea,mark')) return;
    if (Date.now() < clickGuardUntil) return; // 划词抬手后的合成 click，不算点按
    if (hasSel()) {
      clearSel(); // 点按先收起选区，本次不切换 chrome
      return;
    }
    toggleChrome();
  });

  /** 指针拖拽跟手（边缘阻尼 0.35）+ 点击分区（左 1/3 上一页 / 右 1/3 下一页） */
  const bindPaging = () => {
    const pages = pagesEl;
    let startX = 0;
    let startY = 0;
    let dx = 0;
    let base = 0;
    let dragging = false;
    let decided = false;
    let horiz = false;
    let pid = null;
    let pidType = ''; // 上一次 pointerdown 的指针类型：只有触摸长按才会「被划选抢走手势」
    let startT = 0;
    let selAltX = 0; // 已有选区时长按「重新选词」的落点
    let selAltY = 0;
    let lastEvtT = 0;
    let watchdog = 0;
    let holdTimer = 0; // 长按判定计时：按住不动 ≈ 用户要划词，主动放弃翻页手势
    let holdClaimed = false; // 本次手势已判定为长按划选，后续 pointermove 一律不理
    const atRest = () => `translateX(${-pageIdx * 100}%)`;
    /**
     * 位置巡检：WebView 的选区 auto-scroll 会在我们归零之后继续把 scrollLeft 推出去，
     * 松手那一刻又被打回偏移状态，所以「一次性归零」靠不住。手势结束后 1.6s 内逐帧
     * 把滚动偏移钉回 0（有偏移才写，开销可忽略）。
     */
    let guardRAF = 0;
    let guardUntil = 0;
    const guardTick = () => {
      guardRAF = 0;
      if (Date.now() > guardUntil) {
        // 巡检收尾：手势早结束了还停在 px 位移上（不是整格），说明某条路径漏了归位。
        // 半格状态没有任何自愈出口，用户会一直看着「版面歪着」——这里补最后一刀。
        if (!dragging && !/^translateX\(-?\d+%\)$/.test(pages.style.transform)) snapHome(false);
        return;
      }
      clearStageScroll();
      guardRAF = requestAnimationFrame(guardTick);
    };
    const armGuard = (ms = 1600) => {
      guardUntil = Date.now() + ms;
      if (!guardRAF) guardRAF = requestAnimationFrame(guardTick);
    };
    /**
     * 无条件把整页容器校准回当前页。
     * resetPos 原来只在「记录到位移」（dragging/dx/class）时才写回 transform，
     * 可实机上出现的是「transform 停在半格、四条记录却都已被清零」——
     * 于是兜底成了空转：页面左边露着上一页残行、正文跑到屏外，怎么点都回不来。
     * 这里不再看记录，只比对 transform 本身，任何出口调它都能把版面收回原位。
     */
    const snapHome = (anim = true) => {
      clearStageScroll(); // 滚动偏移与 transform 是两套位移，只收后者等于没修
      if (dragging) return; // 正在跟手，交给 onUp 自己结算
      const want = atRest();
      if (pages.style.transform === want && !pages.classList.contains('dragging')) return;
      clearTimeout(watchdog);
      pages.classList.remove('dragging');
      pages.classList.toggle('no-anim', !anim);
      pages.style.transform = want;
    };
    /**
     * 判定为「长按划词」：把容器钉死，但**不结束本次手势**。
     * 关键点：绝不能在长按期间写 transform。容器一旦被平移，WebView 的选区 hit-test
     * 就按错位后的坐标去找手指下的字，选区会一路扩到页尾 —— 这才是「稍微多选几个字，
     * 整页全红了」的真正机制。这里保留 dragging 与累计位移，只停止跟手：
     * 松手时若正文里并没有选区（用户其实是想慢慢拖页），仍按距离正常翻页。
     */
    const holdFreeze = () => {
      if (holdClaimed) return;
      holdClaimed = true;
      decided = false;
      horiz = false;
      dx = 0;
      clearTimeout(watchdog);
      clearTimeout(holdTimer);
      pages.classList.remove('dragging');
      pages.classList.add('no-anim');
      pages.style.transform = atRest(); // 无条件写回（此时 dragging 仍为 true，snapHome 会跳过）
    };
    /** 放弃这次拖拽并把位移归零（不翻页）。长按划选、指针被系统抢走、看门狗超时都走这里 */
    resetPos = (anim = true) => {
      dragging = false;
      decided = false;
      horiz = false;
      dx = 0;
      clearTimeout(watchdog);
      clearTimeout(holdTimer);
      pages.classList.remove('dragging');
      try {
        if (pid != null) pages.releasePointerCapture(pid);
      } catch (err) {
        /* 忽略释放失败 */
      }
      pid = null;
      snapHome(anim);
      armGuard();
    };
    // 手指停下但没等到 pointerup（Android 长按选字时常见）：位移会永远卡在那里，
    // 用户以为「界面坏了」。超时就按正常抬手处理。
    // 只在真的开始拖拽后才武装——长按不动时没有位移可归，提前结算反而会掐断划选。
    const armWatchdog = () => {
      clearTimeout(watchdog);
      watchdog = setTimeout(() => {
        if (dragging && Date.now() - lastEvtT > 600) onUp({ pointerId: pid });
      }, 650);
    };
    const onDown = (e) => {
      if (e.button != null && e.button > 0) return;
      gGestureAt = Date.now();
      if (e.target.closest && e.target.closest('a,button')) return;
      // 已有选区：这一下要么是「点空白处收选区」（由 click 处理），
      // 要么是「长按另一个词重新选」——所以不启动翻页拖拽，但把长按计时挂上
      if (csel) {
        snapHome(true);
        clearTimeout(selHoldT);
        selAltX = e.clientX;
        selAltY = e.clientY;
        selHoldT = setTimeout(() => {
          selHoldT = 0;
          if (startSelAt(selAltX, selAltY)) selGesture = true;
        }, 460);
        return;
      }
      dragging = true;
      decided = false;
      horiz = false;
      holdClaimed = false;
      dx = 0;
      startX = e.clientX;
      startY = e.clientY;
      base = -pageIdx * pages.clientWidth;
      pid = e.pointerId;
      pidType = e.pointerType || '';
      startT = Date.now();
      lastEvtT = startT;
      // 触摸按住不动 300ms ≈ 用户要长按划词（系统 ActionMode 约 500ms 才起）。
      // 必须在这一刻主动交出手势：否则随后拖动选区手柄时，
      // ① 页面跟着横向平移，最后停在半格（用户看到「卡住、回不来」）；
      // ② 拖拽一开始给 .pages 加上 user-select:none，Chrome 会把正在进行的选区
      //    直接撑成整段/整页（用户看到的「字选多了」）。
      clearTimeout(holdTimer);
      holdTimer = setTimeout(() => {
        if (dragging && !decided) holdFreeze();
      }, 300);
      // 按住 460ms 不动 = 划词（比 300ms 的「冻结翻页」再晚一点，给正常翻页留余量）。
      // 选区一起来就由自绘引擎接管：容器钉死、后续拖动只改选区端点，页面绝不跟手。
      clearTimeout(selHoldT);
      selHoldT = setTimeout(() => {
        if (!dragging || decided || horiz) return;
        if (startSelAt(startX, startY)) {
          holdFreeze();
          dx = 0;
          horiz = false;
        }
      }, 460);
      armGuard();
    };
    const onMove = (e) => {
      // 已有选区时没在翻页：手指一挪出抖动区就取消「重新选词」的计时
      if (!dragging) {
        if (selHoldT && (Math.abs(e.clientX - selAltX) > 10 || Math.abs(e.clientY - selAltY) > 10)) {
          clearTimeout(selHoldT);
          selHoldT = 0;
        }
        return;
      }
      if (e.pointerId !== pid) return;
      // 正在选字：拖动只调整选区活动端，翻页彻底让路
      if (selDrag) {
        gGestureAt = Date.now();
        lastEvtT = Date.now();
        extendSelTo(e.clientX, e.clientY);
        return;
      }
      // 已判定长按划词：容器保持静止（绝不写 transform），只记录横向距离，
      // 松手时若其实没有选区（慢速拖动翻页），仍按距离正常翻页。
      if (holdClaimed) {
        gGestureAt = Date.now();
        lastEvtT = Date.now();
        const mx0 = e.clientX - startX;
        if (hasSel()) {
          dx = 0;
          horiz = false;
        } else {
          dx = mx0;
          if (Math.abs(mx0) > Math.abs(e.clientY - startY)) horiz = true;
        }
        return;
      }
      // 正文里已有选区：本次手势彻底不归翻页管
      if (hasSel()) {
        holdFreeze();
        return;
      }
      gGestureAt = Date.now();
      lastEvtT = Date.now();
      armWatchdog();
      const mx = e.clientX - startX;
      const my = e.clientY - startY;
      if (!decided) {
        const ax = Math.abs(mx);
        const ay = Math.abs(my);
        // ① 抖动区：8px 内容器一动不动。系统判定长按时手指本来就会有这点位移。
        if (ax <= 8 && ay <= 8) return;
        // ② 起手速度门槛（0.15px/ms ≈ 150px/s）：只用来区分「长按抖动」和「真的在滑」。
        //    长按等选区起来时抖动速度通常 <0.05；正常滑动 >0.3，慢手拖也有 0.15~0.2。
        //    旧值 0.5 把慢拖全判成划词，于是「翻页完全不跟手」——手感发死。
        //    大幅度拖动（>90px）一律放行，保证怎么拖都能翻。
        const dt = Math.max(1, Date.now() - startT);
        const main = Math.max(ax, ay);
        if (pidType === 'touch' && main / dt < 0.15 && main < 90) {
          holdFreeze();
          dx = mx;
          horiz = ax > ay;
          return;
        }
        decided = true;
        horiz = ax > ay;
        if (!horiz) return;
        // 只有鼠标需要「拖拽期间禁正文选中」+ 显式指针捕获：
        //   · 鼠标划过文字会原位拉起选区，与翻页打架，所以要禁选；
        //   · 拖到容器外还要继续跟手，所以要捕获。
        // 触摸两者都不要：触摸本来就隐式捕获，而 user-select:none 一旦在长按划词
        // 期间生效，Chrome 会立刻把正在进行的选区撑到最近的块级边界 ——
        // 这就是「手指几乎没动、字却一路选到整屏」的真正来源。
        if (pidType === 'mouse') {
          pages.classList.add('dragging');
          try {
            pages.setPointerCapture(pid);
          } catch (err) {
            /* 忽略捕获失败 */
          }
        }
      }
      let off = mx;
      // 首页右拖 / 末页左拖：边缘阻尼
      if ((pageIdx === 0 && off > 0) || (pageIdx === pageEls.length - 1 && off < 0)) off *= 0.35;
      pages.classList.add('no-anim');
      pages.style.transform = `translateX(${base + off}px)`;
      dx = mx;
    };
    const onUp = (e) => {
      if (!dragging) return;
      if (e.pointerId != null && e.pointerId !== pid) return;
      clearTimeout(watchdog);
      clearTimeout(selHoldT);
      dragging = false;
      pages.classList.remove('dragging');
      clearTimeout(holdTimer);
      // 本次手势是划词：选区定稿（工具条此刻才出现），翻页一律不算
      if (selGesture) {
        selGesture = false;
        try {
          pages.releasePointerCapture(pid);
        } catch (err) {
          /* 忽略释放失败 */
        }
        resetPos(true);
        endSel();
        // 触摸抬手后浏览器还会补一个 click（落在选区里）。它不是「点空白处」，
        // 必须挡掉，否则选区刚出现就被自己清掉；用 120ms 的短窗口挡住它，
        // 用户真去点空白时早就过了这个窗口，收选区照旧。
        suppressClick = false;
        clickGuardUntil = Date.now() + 120;
        dx = 0;
        decided = false;
        horiz = false;
        return;
      }
      try {
        pages.releasePointerCapture(pid);
      } catch (err) {
        /* 忽略释放失败 */
      }
      gGestureAt = Date.now();
      const w = pages.clientWidth || 1;
      const dist = Math.abs(dx);
      const dt = Math.max(1, Date.now() - startT);
      const threshold = Math.min(96, w * 0.16);
      // 两种都算翻页：拖够距离（16% 屏宽），或者「快速轻扫」（短距 + 快）——后者是手指最自然的动作，
      // 只看距离会让小幅度快速滑动变成"动了但不翻"，手感发死
      const flick = dt < 280 && dist > 22;
      // 松手时正文里已有选区 = 这次是划词，不是翻页，一律不翻
      if (horiz && (dist > threshold || flick) && !hasSel()) {
        // 边界处跟手位移带 0.35 阻尼，跨章滑入要接着这个位置走，否则会「跳回屏幕外重来」
        const edge = (pageIdx === 0 && dx > 0) || (pageIdx === pageEls.length - 1 && dx < 0);
        turnPage(dx < 0 ? 1 : -1, edge ? dx * 0.35 : 0);
      } else if (dist > 3) {
        // 平滑回弹：先在当前跟手位置移除 no-anim（让 transition 生效），下一帧再回原位。
        // 同帧改 transition + transform 浏览器不触发过渡 → 页面瞬移回位，看起来就是"抖一下"
        pages.classList.remove('no-anim');
        const backTo = `translateX(${-pageIdx * 100}%)`;
        requestAnimationFrame(() => {
          // 期间若又开始新的一次拖拽，别把跟手位移覆盖掉
          if (pagesEl === pages && !dragging) pages.style.transform = backTo;
        });
        updateChapLabel();
        paintProg();
      } else {
        // 没位移（或位移被清过）也要确保版面在正位：这里曾留下「transform 半格、
        // 记录全零」的死状态，随后任何兜底都判成「没动过」而跳过。
        snapHome(true);
      }
      suppressClick = horiz && dist > 8;
      dx = 0;
      decided = false;
      horiz = false;
      armGuard(); // 抬手后仍可能被选区 auto-scroll 推偏，巡检兜住
    };
    pages.addEventListener('pointerdown', onDown);
    pages.addEventListener('pointermove', onMove);
    pages.addEventListener('pointerup', onUp);
    // pointercancel：系统把触摸抢走（下拉通知栏…）。此时手指确实抬起了，按正常抬手结算，
    // 不能一律回弹，否则正常的快速滑动会被判成取消。
    pages.addEventListener('pointercancel', (e) => {
      // 选字拖动期间系统会以「你在滚动」为由取消指针流，可手指还在屏幕上：
      // 真正的收尾等 touchend，这里只当没发生
      if (selDrag) return;
      onUp(e);
    });
    // 不要监听 lostpointercapture：Chrome/WebView 在每次 touchmove 做滚动判定时都会
    // 派发一次它（即便 capture 随后又被隐式重建），在这里归零就等于「刚跟手就弹回」，
    // 表现是一滑就抖一下、然后整页再也不动。兜底交给 armWatchdog 与 pagingCancel。
    // 兜底出口：任何一次点按都顺手把容器位置校准回当前页。
    // 万一上面几条都没兜住（实机上确实出现过），用户点一下屏幕就能恢复，不必退出重进。
    pages.addEventListener('click', (e) => {
      snapHome(true);
      if (suppressClick || Date.now() < clickGuardUntil) {
        suppressClick = false;
        return;
      }
      if (e.target.closest && e.target.closest('a,button')) return;
      if (hasSel()) {
        clearSel(); // 点按收起选区，本次不翻页/不切换 chrome
        return;
      }
      // 用布局坐标判定分区：offsetParent/offsetWidth 不受滑动动画 transform 影响，
      // 避免动画进行中的点击被误判（曾出现动画中容器矩形仍带负偏移、中点点击变翻页）
      const host = pages.offsetParent;
      const hr = host ? host.getBoundingClientRect() : pages.getBoundingClientRect();
      const w = pages.offsetWidth || hr.width || 1;
      const x = (e.clientX - hr.left) / w;
      if (x < 0.33) turnPage(-1);
      else if (x > 0.67) turnPage(1);
      else toggleChrome();
    });
  };

  /* ---------- 自绘选区（长按选词 + 竖线手柄）+ 选词工具条 ----------
   * 为什么不再用系统选区：Android WebView 里那三件事都改不掉 ——
   *   ① 手柄是原生 View（就是那对青色圆点），样式动不了，也换不成细竖线；
   *   ② 手柄一拖过正文边界，Chromium 就把端点钳到最近的块边界，「想选两行」直接吃满整页；
   *   ③ 选区一起来，WebView 会替光标 scrollIntoView，版面被横向推走。
   * 现在正文整体 user-select:none（系统选区根本起不来），选区完全在这里自绘：
   * 高亮是色块，两端是细竖线 + 小圆点，范围就是手指真正划过的那些字。
   * 选区端点一律用「正文文本里的整数偏移」表示，越界在数据结构上就不可能 ——
   * 「只多选了几个字、整屏全红」这类问题从此不会再有。
   */
  const SEL_TEXT_RE = /^[\s\u3000]+|[\s\u3000]+$/g;
  /** 当前该在哪块正文里选字：分页 = 本页 .pg-body，滚动 = 整篇 #r-body */
  const selRootNow = () => {
    const el = paged ? pageEls[pageIdx] && pageEls[pageIdx].querySelector('.pg-body') : $('#r-body');
    return el && el.textContent.trim() ? el : null;
  };
  const selBar = h('div', { class: 'sel-bar', role: 'toolbar' });
  let selText = ''; // 缓存选中文字：点工具条时正文可能已经失焦，不能现取
  const hideSelBar = () => selBar.classList.remove('show');

  const clearSelText = () => {
    selText = '';
  };

  const pickCopy = async () => {
    const t = selText.trim();
    hideSelBar();
    if (!t) return;
    try {
      await navigator.clipboard.writeText(t);
      toast('已复制');
    } catch (e) {
      // 兜底：临时 textarea（不依赖选区，选区被清也能复制）
      const ta = h('textarea', { style: 'position:fixed;top:-9999px;left:-9999px;opacity:0' });
      ta.value = t;
      document.body.append(ta);
      ta.select();
      try {
        document.execCommand('copy');
        toast('已复制');
      } catch (err) {
        toast('复制失败');
      }
      ta.remove();
    }
    clearSel();
  };

  const pickPurify = () => {
    const t = selText.trim();
    hideSelBar();
    clearSel();
    if (!t) return;
    // 选中即建规则：pattern = 选中文字，替换为空 = 删除；光标直接落在「替换为」
    openReplaceSheet({ prefill: { pattern: t, scope: 'content' }, onApplied: () => paint() });
  };

  const pickFind = () => {
    const t = selText.trim();
    hideSelBar();
    clearSel();
    if (!t) return;
    gFind.kw = t;
    const inp = $('#find-input');
    if (inp) inp.value = t;
    openFind();
    setTimeout(runGlobalFind, 60);
  };

  const mkSelBtn = (label, fn) => {
    const b = h('button', { type: 'button' }, label);
    // 关键：按下即 preventDefault —— 否则 WebView 会把焦点切走、先清掉选区，click 时取不到文字
    // 按下即 preventDefault：不让 WebView 把焦点切走（选区在自绘层里，被切走会立刻消失）
    b.addEventListener('pointerdown', (e) => e.preventDefault());
    b.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      fn();
    });
    return b;
  };
  selBar.append(
    mkSelBtn('复制', pickCopy),
    h('i', { class: 'sel-sep' }),
    mkSelBtn('净化替换', pickPurify),
    h('i', { class: 'sel-sep' }),
    mkSelBtn('搜索', pickFind)
  );
  document.body.append(selBar);

  /* ---- 选区引擎 ---- */
  const selLayer = h('div', { class: 'sel-layer' });
  document.body.append(selLayer);
  const mkHandle = (cls) => {
    const el = h('div', { class: 'sel-h ' + cls });
    el.hidden = true;
    selLayer.append(el);
    return el;
  };
  const hA = mkHandle('a');
  const hB = mkHandle('b');
  const hlPool = []; // 高亮色块（按行复用，避免每次重画都造节点）
  let csel = null; // { root, nodes, text, a, b, rects }
  let selDrag = ''; // '' | 'a' | 'b'：正在拖哪一端
  let selGesture = false; // 本次手势已转成选字，翻页全程让路
  let selHoldT = 0; // 长按计时
  let selBarT = 0; // 「按住不动」时让工具条自己浮出来的小计时
  let selMoved = false; // 选区起来后手指是否已经开始拖动（一拖就收菜单）
  let selAnchorX = 0; // 长按落点：用来量「抖了多远」，10px 内不动选区
  let selAnchorY = 0;
  const SEL_DEAD = 10;

  /** 把手势点到的正文摊平成「一段字符串 + 文本节点索引表」 */
  const buildSelModel = (root) => {
    const nodes = [];
    let text = '';
    const add = (node) => {
      const v = node.nodeValue || '';
      if (!v) return;
      nodes.push({ n: node, s: text.length, e: text.length + v.length });
      text += v;
    };
    const walk = (el) => {
      for (const c of el.childNodes) {
        if (c.nodeType === 3) {
          add(c);
          continue;
        }
        if (c.nodeType !== 1) continue;
        if (c.tagName === 'BR') {
          text += '\n';
          continue;
        }
        let block = false;
        try {
          block = getComputedStyle(c).display !== 'inline';
        } catch (e) {
          block = false;
        }
        // 块与块之间补一个换行：复制出来才是分段的，不会糊成一长条
        if (block && text && !text.endsWith('\n')) text += '\n';
        walk(c);
      }
    };
    walk(root);
    return { root, nodes, text };
  };

  /** 偏移 → DOM 位置（落在段落分隔的换行上时贴到下一个文本节点开头） */
  // 说明：取高亮矩形已改成「逐文本节点建区间」（见 rawSelRects），
  // 不再需要把全局偏移还原成单个 DOM 位置，这段映射随之删掉。

  /** 在元素里找第一个/最后一个文本节点（跳过嵌套标签） */
  const textIn = (el, atEnd) => {
    const kids = el && el.childNodes;
    if (!kids || !kids.length) return null;
    const k = atEnd ? kids[kids.length - 1] : kids[0];
    return k.nodeType === 3 ? k : textIn(k, atEnd);
  };

  /** caret 命中的 DOM 位置 → 偏移（不在正文里返回 -1） */
  const idxOfPos = (m, node, offset) => {
    let n = node;
    let off = offset;
    // caretRangeFromPoint 落在段间空白、行尾、行首时，容器会给成元素（<div class=pg-body>
    // 或 <p>）加一个子节点下标，而不是文本节点。旧代码只往下取 firstChild，
    // 碰到 <p> 这种元素子节点就直接失败 —— 表现为「手指按在段与段之间的空档，长按没反应」。
    if (n && n.nodeType === 1) {
      const kids = n.childNodes;
      if (!kids.length) return -1;
      if (off >= kids.length) {
        const t = textIn(n, true);
        if (!t) return -1;
        n = t;
        off = t.nodeValue.length;
      } else {
        const k = kids[off];
        if (k.nodeType === 3) {
          n = k;
          off = 0;
        } else {
          const t = textIn(k, false);
          if (!t) return -1;
          n = t;
          off = 0;
        }
      }
    }
    if (!n || n.nodeType !== 3) return -1;
    for (const x of m.nodes) {
      if (x.n === n) return x.s + Math.min(off, n.nodeValue.length);
    }
    return -1;
  };

  /** caret 命中 → 正文偏移（不在正文里返回 -1） */
  const caretIdxAt = (m, x, y) => {
    let cr = null;
    try {
      cr = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
    } catch (e) {
      cr = null;
    }
    return cr ? idxOfPos(m, cr.startContainer, cr.startOffset) : -1;
  };

  /**
   * 手指坐标 → 正文偏移。**取不到就返回 -1，调用方保持原状**。
   *
   * 这里踩过一个很贵的坑：caretRangeFromPoint 会把人眼看不见、却盖在字上面的浮层
   * 当成命中目标 —— 即使它是 pointer-events:none。我们的 .sel-layer 正好是铺满全屏、
   * z-index 890 的高亮层，于是「画完第一块高亮之后再动一下手指」，取回来的容器是
   * <div class="sel-layer">，换成偏移必然失败。
   *
   * 旧代码在这时按「上半屏归页首、下半屏归页末」兜底 —— 手指只抖 2px，选区就从 0 拉到
   * 53 字、或者一路拉到页末，也就是用户反复看到的「随手长按又选了一大堆」。
   * 现在：先在浮层可见时取一次，失败就把浮层临时藏起来重取（同一任务内改回，浏览器
   * 不会真的重绘，不闪），仍然失败才返回 -1，让选区原地不动。
   */
  const idxAtPoint = (m, x, y) => {
    let br = null;
    try {
      br = m.root.getBoundingClientRect();
    } catch (e) {
      br = null;
    }
    if (!br) return -1;
    // 真的把手指拖出正文上下界（超过 6px）才归到首/末：这是「拖到页底就选到页末」的直觉。
    // 落在正文内部却取不到字时绝不能这么算，否则就是上面那个 bug。
    if (y < br.top - 6) return 0;
    if (y > br.bottom + 6) return m.text.length;
    const cx = Math.min(Math.max(x, br.left + 1), br.right - 1);
    const cy = Math.min(Math.max(y, br.top + 1), br.bottom - 1);
    let i = caretIdxAt(m, cx, cy);
    if (i < 0) {
      const hid = [];
      for (const el of [selLayer, selBar, hA, hB]) {
        if (el) {
          hid.push([el, el.style.visibility]);
          el.style.visibility = 'hidden';
        }
      }
      try {
        i = caretIdxAt(m, cx, cy);
      } finally {
        for (const [el, v] of hid) el.style.visibility = v;
      }
    }
    if (i < 0) return -1;
    return Math.max(0, Math.min(i, m.text.length));
  };

  // 选词锚点：手指常落在首行缩进/段间换行上，用 SEL_IS_SPACE 跳过这些空白，
  // 把锚点落到第一个真字上（见 startSelAt）。真正的「选多少」由随后的拖动决定。
  const SEL_IS_SPACE = (ch) => ch === ' ' || ch === '\u3000' || ch === '\n' || ch === '\r' || ch === '\t';

  /* 取高亮的行盒。两个坑都在 Chromium 的 Range.getClientRects 里：
     ① 某个块（段落）被区间完整包住时，它会额外吐一个「整块矩形」——就是
        「仙人就是无拘无束的」那一行套着的那个大框，块宽 354、行高 34，
        和高亮叠在一起alpha 相加，看着像两个框。所以逐文本节点取矩形：
        只在同一个文本节点内建区间，块永远不会被完整包住。
     ② 一条视觉行可能由多个文本节点/碎片拼成（分段、内联标签），各取一块会
        在接缝处叠色。合并同行的碎片，一行永远只画一个色块。 */
  const rawSelRects = (m, a, b) => {
    const out = [];
    for (const seg of m.nodes) {
      const s = Math.max(a, seg.s);
      const e = Math.min(b, seg.e);
      if (e <= s) continue;
      try {
        const rg = document.createRange();
        rg.setStart(seg.n, s - seg.s);
        rg.setEnd(seg.n, e - seg.s);
        for (const r of rg.getClientRects()) if (r.width > 0.5 && r.height > 1) out.push(r);
      } catch (err) {
        /* 节点被重排/失效：跳过这一段 */
      }
    }
    return out;
  };
  const mergeSelRects = (rs) => {
    const lines = [];
    for (const r of rs.slice().sort((p, q) => p.top - q.top || p.left - q.left)) {
      const ln = lines.find(
        (L) => Math.min(L.bottom, r.bottom) - Math.max(L.top, r.top) > Math.min(L.height, r.height) * 0.5
      );
      if (!ln) {
        lines.push({ left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width, height: r.height });
        continue;
      }
      ln.left = Math.min(ln.left, r.left);
      ln.right = Math.max(ln.right, r.right);
      ln.top = Math.min(ln.top, r.top);
      ln.bottom = Math.max(ln.bottom, r.bottom);
      ln.width = ln.right - ln.left;
      ln.height = ln.bottom - ln.top;
    }
    return lines;
  };

  /** 重画高亮色块与两端竖线（只在选区变化/尺寸变化时调用） */
  const paintSel = () => {
    if (!csel) return;
    const a = Math.min(csel.a, csel.b);
    const b = Math.max(csel.a, csel.b);
    let rects = [];
    try {
      rects = mergeSelRects(rawSelRects(csel, a, b));
    } catch (e) {
      rects = [];
    }
    csel.rects = rects;
    let k = 0;
    for (const r of rects) {
      let el = hlPool[k];
      if (!el) {
        el = h('div', { class: 'sel-hl' });
        selLayer.append(el);
        hlPool.push(el);
      }
      el.hidden = false;
      el.style.left = Math.round(r.left) + 'px';
      el.style.top = Math.round(r.top) + 'px';
      el.style.width = Math.max(1, Math.round(r.width)) + 'px';
      el.style.height = Math.round(r.height) + 'px';
      k++;
    }
    for (let i = k; i < hlPool.length; i++) hlPool[i].hidden = true;
    const first = rects[0];
    const last = rects[rects.length - 1];
    // 竖线只出现在「首行左缘」和「末行右缘」——多行选区中间不切，和纸书划线一致
    const place = (el, x, r) => {
      if (!r) {
        el.hidden = true;
        return;
      }
      el.hidden = false;
      el.style.left = Math.round(x) + 'px';
      el.style.top = Math.round(r.top) - 2 + 'px';
      el.style.height = Math.round(r.height) + 4 + 'px';
    };
    place(hA, first ? first.left : 0, first);
    place(hB, last ? last.right : 0, last);
  };

  /** 当前选中文字（去掉首尾空白，复制/搜索/净化都用它） */
  const selTextOf = () => {
    if (!csel) return '';
    const a = Math.min(csel.a, csel.b);
    const b = Math.max(csel.a, csel.b);
    return csel.text.slice(a, b).replace(SEL_TEXT_RE, '');
  };

  const clearSel = () => {
    csel = null;
    selDrag = '';
    selGesture = false;
    clearTimeout(selHoldT);
    clearTimeout(selBarT);
    for (const el of hlPool) el.hidden = true;
    hA.hidden = true;
    hB.hidden = true;
    clearSelText();
    hideSelBar();
  };

  /** 工具条浮到选区下方（下方放不下就翻到上方），左边跟着最后一行居中 */
  const placeSelBar = () => {
    const txt = selTextOf();
    if (!txt || !csel || !csel.rects || !csel.rects.length) return false;
    selText = txt;
    const rects = csel.rects;
    const head = rects[0];
    const tail = rects[rects.length - 1];
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const bw = selBar.offsetWidth || 210;
    const bh = selBar.offsetHeight || 40;
    let left = tail.left + tail.width / 2;
    left = Math.max(bw / 2 + 8, Math.min(vw - bw / 2 - 8, left));
    let top = tail.bottom + 12;
    if (top + bh > vh - 12) top = head.top - bh - 10;
    if (top < 8) top = 8;
    selBar.style.left = left + 'px';
    selBar.style.top = top + 'px';
    selBar.classList.add('show');
    return true;
  };

  /**
   * 长按落点：只在手指处锚定一个字的最小选区，并进入「拖动扩选」状态。
   * 为什么不再整词选中：实机反馈「一按就选一大块，虽然能调但每次都调不方便」。
   * 改成「锚定 1 字 + 拖手指增选」——手指往哪边移，选区就往哪边长（左右都能扩），
   * 选多少完全由手指决定，不再替用户先抓一大片。
   */
  const startSelAt = (x, y) => {
    const root = selRootNow();
    if (!root) return false;
    const m = buildSelModel(root);
    if (!m.text.trim()) return false;
    let i = idxAtPoint(m, x, y);
    if (i < 0) return false; // 换算不出落点：宁可不选，也不要瞎选一整页
    // 手指常落在段首缩进/段间换行上：向右找到第一个真字再锚定，别框住空白
    for (let k = 0; k < 8 && i < m.text.length - 1 && SEL_IS_SPACE(m.text[i]); k++) i++;
    // 最小选区 = 手指处的单个字；真正延伸交给随后的拖动（extendSelTo）
    const a = i;
    const b = Math.min(m.text.length, i + 1);
    if (b <= a) return false;
    csel = m;
    csel.a = a;
    csel.b = b;
    csel.rects = [];
    selDrag = 'b';
    selGesture = true;
    selMoved = false;
    selAnchorX = x;
    selAnchorY = y;
    hideSelBar(); // 手指还在屏幕上：这一段不该被菜单挡着
    clearSelText();
    paintSel();
    // 按住不动 300ms 就把工具条浮出来（和系统一样：选完词不用抬手就能点复制）；
    // 手指一挪就开始拖选区，这时菜单立刻收起，不挡视线
    clearTimeout(selBarT);
    selBarT = setTimeout(() => {
      if (selDrag && !selMoved) placeSelBar();
    }, 300);
    try {
      if (navigator.vibrate) navigator.vibrate(6);
    } catch (e) {
      /* 忽略：没有振动马达 */
    }
    return true;
  };

  /**
   * 手指移动 → 调整活动端（拖过对端就交换，和系统手柄的手感一致）。
   * 起手 10px 是抖动区：长按之后手指自带的 1~3px 位移不该改动选区，
   * 用户要的是「跟着我移动」，不是「跟着我哆嗦」。
   */
  const extendSelTo = (x, y) => {
    if (!csel || !selDrag) return;
    if (!selMoved) {
      if (Math.abs(x - selAnchorX) <= SEL_DEAD && Math.abs(y - selAnchorY) <= SEL_DEAD) return;
      selMoved = true;
      clearTimeout(selBarT);
      hideSelBar(); // 开始调整范围：菜单让开
    }
    const i = idxAtPoint(csel, x, y);
    if (i < 0) return; // 这一下换算不出落点：保持原选区，绝不跳到页首/页末
    if (selDrag === 'b') {
      if (i < csel.a) {
        csel.b = csel.a;
        csel.a = i;
        selDrag = 'a';
      } else {
        // 至少留一个字：手指在同一行里往回挪一点时，选区会算成 a===b 直接塌成 0，
        // 松手就整块消失（用户以为「长按没反应」）。
        csel.b = Math.max(i, csel.a + 1);
      }
    } else if (i > csel.b) {
      csel.a = csel.b;
      csel.b = i;
      selDrag = 'b';
    } else {
      csel.a = Math.min(i, csel.b - 1);
    }
    paintSel();
  };

  /** 松手：选区定稿，工具条重新出现 */
  const endSel = () => {
    selGesture = false; // 这一轮划词手势到此为止（翻页手势的收尾逻辑别再认它）
    clearTimeout(selBarT);
    if (!csel) {
      selDrag = '';
      return;
    }
    const same = csel.a === csel.b;
    selDrag = '';
    if (same) {
      clearSel();
      return;
    }
    paintSel();
    placeSelBar();
  };

  // 拖手柄：起点/终点各一个。手柄比竖线宽得多（24px 热区），手指才好按住。
  // 按下之后把 move/up 挂到 document 上（而不是手柄自己）：手指一拖就出了手柄的矩形，
  // 靠元素自身的 pointerup 收尾在实机上并不可靠（capture 也可能失败），
  // 结果就是「拖完松手，菜单再也不回来」。
  const bindHandle = (el, side) => {
    bindOnce(el, 'pointerdown', (e) => {
      if (!csel) return;
      e.preventDefault();
      e.stopPropagation();
      selDrag = side;
      selMoved = true; // 按手柄是明确动作，不走「起手抖动区」
      hideSelBar(); // 一按手柄就收菜单：否则它正好压在要拖的文字上
      const move = (ev) => {
        if (!selDrag) return;
        extendSelTo(ev.clientX, ev.clientY);
      };
      const done = () => {
        document.removeEventListener('pointermove', move, true);
        document.removeEventListener('pointerup', done, true);
        document.removeEventListener('pointercancel', done, true);
        if (selDrag) endSel();
      };
      document.addEventListener('pointermove', move, true);
      document.addEventListener('pointerup', done, true);
      document.addEventListener('pointercancel', done, true);
    });
  };
  bindHandle(hA, 'a');
  bindHandle(hB, 'b');

  // 扩选与收尾统一走 touch 事件（分页/滚动两种模式共用，所以不能挂在 bindPaging 里）：
  // WebView 一旦判定「你在滚动」就先派发 pointercancel、之后不再给 pointermove，
  // 只靠 pointer 事件的话实机上「拖到第二行就停住」。touchmove 在 cancel 之后照常来。
  bindOnce(
    document,
    'touchmove',
    (e) => {
      if (!selDrag) return;
      e.preventDefault(); // 拖动期间不许页面跟着滚，否则端点会按滚动后的版面乱跳
      const t = e.touches && e.touches[0];
      if (t) extendSelTo(t.clientX, t.clientY);
    },
    { passive: false },
  );
  // 手指抬起 = 选区定稿（touchend 比 pointerup 可靠：pointer 流可能已被 cancel）
  bindOnce(document, 'touchend', () => {
    if (selDrag) endSel();
  });
  bindOnce(document, 'touchcancel', () => {
    if (selDrag) endSel();
  });

  /** 滚动模式：长按正文任意处 460ms 起选区（分页模式在 bindPaging 里就手） */
  const bindScrollSel = () => {
    if (paged) return;
    const rb = $('#r-body');
    if (!rb) return;
    let sx = 0;
    let sy = 0;
    bindOnce(rb, 'pointerdown', (e) => {
      if (e.button != null && e.button > 0) return;
      if (e.target.closest && e.target.closest('a,button')) return;
      if (csel) return;
      sx = e.clientX;
      sy = e.clientY;
      clearTimeout(selHoldT);
      selHoldT = setTimeout(() => startSelAt(sx, sy), 460);
    });
    bindOnce(rb, 'pointermove', (e) => {
      if (selDrag) {
        extendSelTo(e.clientX, e.clientY);
        return;
      }
      if (Math.abs(e.clientX - sx) + Math.abs(e.clientY - sy) > 14) clearTimeout(selHoldT);
    });
    bindOnce(rb, 'pointerup', () => {
      clearTimeout(selHoldT);
      if (selDrag) endSel();
    });
    // 注意：这里不结束选区 —— 长按一起选区，WebView 往往同时发 pointercancel，
    // 收尾统一交给 document 上的 touchend
    bindOnce(rb, 'pointercancel', () => {
      clearTimeout(selHoldT);
    });
  };
  bindScrollSel();

  /** 回归测试用的只读/直设钩子：真机不依赖它，但让「选了哪几个字」可以被断言 */
  window.__sel = {
    text: () => selTextOf(),
    len: () => (csel ? Math.abs(csel.b - csel.a) : 0),
    has: () => hasSel(),
    clear: () => clearSel(),
    rects: () =>
      csel && csel.rects
        ? csel.rects.map((r) => ({ l: r.left, t: r.top, r: r.right, b: r.bottom, h: r.height }))
        : [],
    dbg: () => ({ drag: selDrag, a: csel && csel.a, b: csel && csel.b, tf: window.__AP && window.__AP() ? window.__AP().style.transform : '' }),
    maxLen: () => (csel ? csel.text.length : 0),
    bodyLen: () => {
      const root = selRootNow();
      return root ? root.textContent.length : 0;
    },
    set: (a, b) => {
      const root = selRootNow();
      if (!root) return false;
      const m = buildSelModel(root);
      csel = m;
      csel.a = Math.max(0, Math.min(a, m.text.length));
      csel.b = Math.max(0, Math.min(b, m.text.length));
      selDrag = '';
      paintSel();
      placeSelBar();
      return true;
    },
  };
  /* ---------- 滚动模式：章末续章 ----------
   * 上下滚动模式每章是独立渲染的，滚到章尾就真的到底了（分页模式还能左右滑到下一章），
   * 用户反馈「翻到章节末就划不动」。这里补一个自然出口：
   * 滚到底提示一次；继续上滑超过阈值直接切下一章，手感与继续滚动一致。 */
  let endAt = 0; // 首次滚到底的时间：刚到底的一小段时间内不切，留出看最后一段的余地
  let endHinted = false;
  let lastTouchY = 0;
  let overScroll = 0;
  let lastEndJump = 0;
  const atChapterEnd = () => {
    const max = stage.scrollHeight - stage.clientHeight;
    return max <= 0 ? true : stage.scrollTop >= max - 6;
  };
  bindOnce(
    stage,
    'touchstart',
    (e) => {
      lastTouchY = e.touches && e.touches[0] ? e.touches[0].clientY : 0;
      overScroll = 0;
    },
    { passive: true },
  );
  bindOnce(
    stage,
    'touchmove',
    (e) => {
      if (paged || !e.touches || !e.touches[0]) return;
      const y = e.touches[0].clientY;
      const dy = lastTouchY - y; // 手指向上滑为正
      lastTouchY = y;
      if (dy <= 0 || !atChapterEnd()) {
        overScroll = 0;
        return;
      }
      overScroll += dy;
      // 阈值 120px + 到底后静置 450ms：既不会滑快了误跳，也不至于划半天没反应
      if (overScroll < 120 || index + 1 >= total) return;
      if (Date.now() - endAt < 450 || Date.now() - lastEndJump < 900) return;
      lastEndJump = Date.now();
      overScroll = 0;
      endAt = 0;
      endHinted = false;
      goChapter(index + 1);
    },
    { passive: true },
  );
  /* 唯一的 stage scroll 处理（bindOnce 同类型只保留最后一个，拆成两个会互相覆盖）：
   * 1) 滚动/翻页后选区矩形失效，收起选词条；
   * 2) 滚动模式：到底给一次续章提示；
   * 3) 分页模式兜底：任何路径改动了 .rd-stage 的滚动偏移就归零
   *    （根治见 paginate 里 .page 改用 transform 定位，不再撑大 scrollWidth）。 */
  bindOnce(
    stage,
    'scroll',
    () => {
      // 选区高亮/竖线用的是视口坐标，滚动后必然错位 —— 滚动模式一律收掉选区
      if (csel) clearSel();
      hideSelBar();
      if (!paged) {
        // 滚动后朗读落点可能已不在视口里：小横条文案要跟着切回「从此页开始听」
        if (ttsOn) paintListenBar();
        if (atChapterEnd()) {
          if (!endAt) endAt = Date.now();
          if (!endHinted && index + 1 < total) {
            endHinted = true;
            toast('已到本章末尾，继续上滑进入下一章');
          }
        } else {
          endAt = 0;
          endHinted = false;
        }
        return;
      }
      clearStageScroll();
      if (pagesEl && pagesEl.scrollLeft) pagesEl.scrollLeft = 0;
    },
    { passive: true }
  );

  /* ---------- 阅读页自定义浮层的返回键（目录抽屉 / 搜索条） ----------
   * overlay 类弹层（排版/净化/图标排布/更多菜单）由 dom.js 统一注册。
   * 目录抽屉（.toc-mask/.toc-drawer）和底部搜索条是自绘浮层，不是 .overlay——
   * 之前返回键处理不到它们，一按返回就路由回退、直接退出阅读回到书架（越级）。
   * 这里常驻注册一个处理器：LIFO 顺序在 overlay 之后，overlay 关完才轮到它。
   * 搜索结果汇总页有 history 占位（popstate 关闭），仍返回 false 交给历史栈。
   */
  const unReaderBack = registerBack(() => {
    if (drawer && drawer.isOpen()) {
      // 目录抽屉开着：关抽屉（书签 tab、章节搜索都在抽屉内，一并收掉）
      drawer.close();
      return true;
    }
    const fp = $('#find-page');
    if (fp && !fp.hidden) return false; // 汇总页走 history 占位，由 popstate 关
    const fb = $('#find-bar');
    if (fb && !fb.hidden) {
      closeFind();
      return true;
    }
    return false;
  });

  /** 字号/行距/视口变化后防抖重分页 */
  const scheduleRepage = () => {
    if (!paged) return;
    clearTimeout(repageTimer);
    repageTimer = setTimeout(() => paginate(pageIdx), 160);
  };

  /* ---------- 正文搜索（默认全书；缓存优先，未缓存章节按需拉取） ---------- */
  let findMarks = [];
  let markedCi = -1; // 已插入高亮的章号（同章同词不重复重建）
  let markedKw = '';

  const setCount = (t) => {
    const c = $('#find-count');
    if (c) c.textContent = t;
  };

  const clearMarks = () => {
    $$('#r-body .find-hl').forEach((el) => {
      const p = el.parentNode;
      if (!p) return;
      p.replaceChild(document.createTextNode(el.textContent), el);
      p.normalize();
    });
    findMarks = [];
    markedCi = -1;
    markedKw = '';
  };

  /**
   * 章节正文 HTML（含默认排版）：试读缓存 → chapters 表 → 联网拉取。
   * 返回 null 表示没有取到（调用方各自处理兜底文案）。
   */
  const chapterHtmlAt = async (i) => {
    const isPreview = !!book.preview;
    const key = id + '||' + i;
    const chapName = (book.chapters[i] || {}).name || '';
    if (rawMem.has(i)) return renderChapterHtml(rawMemGet(i), chapName); // 内存命中：同步路径，跨章翻页用
    // 书架按下封面时已经预取这一章：直接等那个 Promise，省掉一次网络往返
    const prefetchP = takePrefetch(book, i);
    if (prefetchP) {
      const content = await prefetchP;
      if (content) {
        rawMemSet(i, content);
        if (!isPreview) {
          try {
            await db.put('chapters', { id: key, content, cachedAt: Date.now() });
          } catch (e) {
            /* 忽略 */
          }
        }
        return renderChapterHtml(content, chapName);
      }
    }
    let html = null;
    if (isPreview) {
      html = previewChapterGet(i);
    } else {
      try {
        const c = await db.get('chapters', key);
        if (c) html = c.content;
      } catch (e) {
        /* 忽略 */
      }
    }
    if (html == null && src) {
      try {
        const r = await src.getContent(book, book.chapters[i]);
        if (!r.error && r.content) {
          html = r.content;
          if (isPreview) previewChapterPut(i, html);
          else {
            try {
              await db.put('chapters', { id: key, content: html, cachedAt: Date.now() });
            } catch (e) {
              /* 忽略 */
            }
          }
        }
      } catch (e) {
        /* 跳过失败章节 */
      }
    }
    if (html == null) return null;
    rawMemSet(i, html); // 留一份原始正文，供跨章原地切换
    return renderChapterHtml(html, chapName);
  };

  /** 章节纯文本：缓存优先；网络书未缓存则拉取并回写缓存 */
  const chapterText = async (i) => {
    const html = await chapterHtmlAt(i);
    if (!html) return '';
    const div = document.createElement('div');
    div.innerHTML = html;
    return div.textContent || '';
  };

  /** 在当前章插入全部命中 mark（分片执行，长章不卡） */
  const markChapter = async (kw, gen) => {
    if (markedCi === index && markedKw === kw && findMarks.length) return; // 已标好
    clearMarks();
    markedCi = index;
    markedKw = kw;
    const root = paged ? pagesEl : $('#r-body');
    if (!root || !kw) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    let processed = 0;
    for (const n of nodes) {
      if (gen != null && gen !== gFind.gen) return;
      const text = n.nodeValue || '';
      let idx = text.indexOf(kw);
      if (idx >= 0) {
        const frag = document.createDocumentFragment();
        let last = 0;
        while (idx >= 0) {
          frag.append(document.createTextNode(text.slice(last, idx)));
          const mark = document.createElement('mark');
          mark.className = 'find-hl';
          mark.textContent = text.substr(idx, kw.length);
          frag.append(mark);
          findMarks.push(mark);
          last = idx + kw.length;
          idx = text.indexOf(kw, last);
        }
        frag.append(document.createTextNode(text.slice(last)));
        n.replaceWith(frag);
      }
      if (++processed % 150 === 0) {
        await new Promise((r) => setTimeout(r, 0));
        if (gen != null && gen !== gFind.gen) return;
      }
    }
  };

  const focusMarkAt = (k) => {
    $$('#r-body .find-hl.cur').forEach((el) => el.classList.remove('cur'));
    const mark = findMarks[k];
    if (!mark) return;
    mark.classList.add('cur');
    if (paged) {
      const pg = mark.closest('.page');
      const pi = pg ? pageEls.indexOf(pg) : -1;
      if (pi >= 0) { showPage(pi); return; }
    }
    mark.scrollIntoView({ block: 'center', behavior: 'smooth' });
  };

  /** 跳到全书第 pos 个命中；跨章时跳章，渲染后由 gFind.pend* 恢复定位 */
  const focusHit = async (pos) => {
    const hits = gFind.hits;
    if (!hits.length) return;
    gFind.pos = (pos + hits.length) % hits.length;
    const hit = hits[gFind.pos];
    setCount(`${gFind.pos + 1}/${hits.length}`);
    if (hit.ci !== index) {
      gFind.pendCi = hit.ci;
      gFind.pendOrd = hit.ord;
      goChapter(hit.ci);
      return;
    }
    await markChapter(gFind.kw, gFind.gen);
    focusMarkAt(hit.ord);
    if (!$('#find-page')?.hidden) renderFindPage();
  };

  /** 全书检索：逐章收集命中 → 定位第一个 */
  const runGlobalFind = async (stayOnPage) => {
    const kw = ($('#find-input')?.value || '').trim();
    const gen = ++gFind.gen;
    gFind.kw = kw;
    gFind.hits = [];
    gFind.pos = -1;
    gFind.pendCi = -1;
    gFind.pendOrd = -1;
    gFind.busy = !!kw;
    gFind.progress = '';
    clearMarks();
    if (!kw) {
      setCount('0/0');
      if (!$('#find-page')?.hidden) renderFindPage();
      return;
    }
    // 进度只在汇总页展示（底部搜索条只留 ↑/↓ 与计数）
    if (!$('#find-page')?.hidden) renderFindPage();
    await new Promise((r) => requestAnimationFrame(() => r()));

    // 4 并发逐章取文本；每章完成即并入 gFind.hits（按章节顺序重建），
    // 结果页边搜边显示（节流渲染），不用等全部章节跑完
    const perHit = new Array(total);
    let next = 0;
    let doneCh = 0;
    let found = 0;
    let lastPaint = 0;
    const mergeHits = () => {
      // 章节顺序重建（空洞 = 未完成章），保证 ↑/↓ 与结果页顺序稳定
      gFind.hits = [];
      for (let ci = 0; ci < total; ci++) {
        for (const h of perHit[ci] || []) gFind.hits.push({ ci, ord: h.ord, pct: h.pct, snip: h.snip });
      }
    };
    const flushPaint = (force = false) => {
      const now = Date.now();
      if (!force && now - lastPaint < 400) return;
      lastPaint = now;
      setCount(found ? `${found} 处` : `${doneCh}/${total}`);
      if (!$('#find-page')?.hidden) renderFindPage();
    };
    const worker = async () => {
      while (next < total) {
        if (gen !== gFind.gen) return;
        const i = next++;
        const text = await chapterText(i);
        if (gen !== gFind.gen) return;
        const arr = [];
        let p = text.indexOf(kw);
        while (p >= 0) {
          arr.push({
            ord: arr.length,
            at: p,
            pct: Math.round((p / Math.max(1, text.length)) * 1000) / 10,
            snip: text.slice(Math.max(0, p - 22), p + kw.length + 46).replace(/\s+/g, ' ').trim(),
          });
          p = text.indexOf(kw, p + kw.length);
        }
        perHit[i] = arr;
        found += arr.length;
        doneCh++;
        gFind.progress = `搜索中… ${doneCh}/${total} 章 · ${found} 处`;
        mergeHits();
        flushPaint();
        await new Promise((r) => setTimeout(r, 0));
      }
    };
    await Promise.all([worker(), worker(), worker(), worker()]);
    if (gen !== gFind.gen) return;
    mergeHits();
    gFind.busy = false;
    flushPaint(true);
    if (!gFind.hits.length) setCount('0/0');
    else setCount(`${gFind.pos + 1}/${gFind.hits.length}`);
  };

  const stepFind = (d) => {
    if (!gFind.hits.length) return;
    focusHit(gFind.pos + d);
  };

  /* ---------- 搜索结果页（全书命中汇总） ---------- */

  const hlSnip = (s, kw) => {
    const safe = esc(s || '');
    if (!kw) return safe;
    const k = esc(kw).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    try {
      return safe.replace(new RegExp(k, 'gi'), (m) => `<mark>${m}</mark>`);
    } catch (e) {
      return safe;
    }
  };

  const renderFindPage = () => {
    const list = $('#fp-list');
    const title = $('#fp-title');
    if (!list || !title) return;
    const kw = gFind.kw;
    if (!kw) {
      title.textContent = '搜索结果';
      list.innerHTML = '<p class="fp-empty">输入关键词，搜索全书内容</p>';
      return;
    }
    if (gFind.busy) {
      // 边搜边显示：已有命中先按分组渲染出来，标题行带实时进度
      title.textContent = gFind.progress || '搜索中…';
      if (!gFind.hits.length) {
        list.innerHTML = `<p class="fp-empty">${esc(gFind.progress || '正在检索…')}</p>`;
        return;
      }
      renderGrouped(list, kw, `搜索中 · 已找到 ${gFind.hits.length} 条`);
      return;
    }
    title.textContent = `共 ${gFind.hits.length} 条结果`;
    if (!gFind.hits.length) {
      list.innerHTML = '<p class="fp-empty">没有找到相关内容</p>';
      return;
    }
    renderGrouped(list, kw, '');
  };

  /** 命中按章分组渲染（搜索中/完成后共用）；busy 时限量渲染避免大列表反复重排 */
  const renderGrouped = (list, kw, note) => {
    // 按章分组：章节号单独凸显，命中片段隶属于该章，一眼看清每条来自哪一章
    const cap = note ? 300 : Infinity;
    const groups = [];
    let cut = false;
    gFind.hits.forEach((h, i) => {
      if (groups.length >= cap) {
        cut = true;
        return;
      }
      let g = groups[groups.length - 1];
      if (!g || g.ci !== h.ci) {
        g = { ci: h.ci, items: [] };
        groups.push(g);
      }
      g.items.push({ ...h, i });
    });
    const chapName = (ci) => {
      const raw = (book.chapters[ci] || {}).name || `第 ${ci + 1} 章`;
      return raw.replace(/^第[零一二三四五六七八九十百千万\d]+[章节回卷]\s*/, '') || raw;
    };
    // 重渲染保留滚动位置（边搜边显示时列表会频繁刷新，不能把用户看的位置顶回去）
    const keepTop = list.scrollTop;
    list.innerHTML =
      (note ? `<div class="fp-note">${esc(note)}</div>` : '') +
      groups
        .map(
          (g) => `<section class="fp-group">
        <div class="fp-ghead">
          <b class="fp-gnum">第 ${g.ci + 1} 章</b>
          <span class="fp-gname">${esc(chapName(g.ci))}</span>
          <span class="fp-gcount">${g.items.length} 处</span>
        </div>
        ${g.items
          .map(
            (h) => `<button class="fp-item${h.i === gFind.pos ? ' on' : ''}" data-i="${h.i}">
              <span class="fp-pct">${(Number(h.pct) || 0).toFixed(1)}%</span>
              ${h.i === gFind.pos ? '<span class="fp-badge">当前</span>' : ''}
              <span class="fp-snip">${hlSnip(h.snip, kw)}</span>
            </button>`
          )
          .join('')}
      </section>`
        )
        .join('') +
      (cut ? `<div class="fp-note">已显示前 300 条，搜索完成后查看全部…</div>` : '');
    list.scrollTop = keepTop;
    list.querySelectorAll('.fp-item').forEach((btn) => {
      btn.onclick = () => {
        const i = Number(btn.dataset.i);
        closeFindPage(() => focusHit(i));
      };
    });
  };

  const openFindPage = () => {
    const page = $('#find-page');
    if (!page) return;
    page.hidden = false;
    const inp = $('#fp-input');
    if (inp) inp.value = gFind.kw || ($('#find-input')?.value || '').trim();
    renderFindPage();
    if (!fpPushed) {
      try {
        history.pushState({ findPage: 1 }, '', location.href);
        fpPushed = true;
      } catch (e) {
        /* 忽略 */
      }
    }
    if (!gFind.kw && inp && inp.value.trim()) {
      $('#find-input').value = inp.value.trim();
      runGlobalFind(true);
    }
    if (inp && !gFind.busy) inp.focus();
  };

  const closeFindPage = (arg) => {
    const page = $('#find-page');
    if (page) page.hidden = true;
    const inp = $('#fp-input');
    if (inp) inp.blur();
    const cb = typeof arg === 'function' ? arg : null;
    // 没真正搜过（无关键词）就退出结果页：底部搜索条也一并收掉，
    // 否则「打开搜索 → 啥也没搜 → 返回」后底下还杵着一条搜索栏，没意义。
    // 已搜过（gFind.kw 有值，哪怕 0 命中）则保留底部条，方便 ↑/↓ 继续跳。
    const noSearch = !gFind.kw;
    const closeBar = () => {
      const fb = $('#find-bar');
      if (fb) fb.hidden = true;
      // 同时退出搜索模式：finding 会让工具坞整体隐藏，只有 closeFind 才清它；
      // 这里「没搜过就返回」也要把工具坞放回来，否则点完搜索返回，工具坞一直不出现。
      $('#rd-shell')?.classList.remove('finding');
    };
    if (arg !== true && fpPushed) {
      fpPushed = false;
      // 先回退占位历史，等 popstate 落地后再跳转：否则 router 会按旧 hash 把阅读页渲染回原章节
      const once = () => {
        window.removeEventListener('popstate', once);
        if (noSearch) closeBar();
        if (cb) setTimeout(cb, 0);
      };
      window.addEventListener('popstate', once);
      try {
        history.back();
      } catch (e) {
        window.removeEventListener('popstate', once);
        if (noSearch) closeBar();
        if (cb) cb();
      }
      return;
    }
    fpPushed = false;
    if (noSearch) closeBar();
    if (cb) setTimeout(cb, 0);
  };

  const onFindPagePop = () => {
    const page = $('#find-page');
    if (page && !page.hidden) closeFindPage(true);
  };
  window.addEventListener('popstate', onFindPagePop);

  // 到这里已经过几次 await（取设置/取正文）：若路由已切走（外壳被清掉或换了新的），
  // 本轮就不要再绑监听——否则 null.onclick 抛错会中断后面所有绑定，翻页/选词直接失灵。
  const fpBackEl = $('#fp-back');
  if (stale() || !fpBackEl) return;

  fpBackEl.onclick = () => closeFindPage();
  $('#fp-again').onclick = () => {
    const v = ($('#fp-input')?.value || '').trim();
    if (!v) return;
    $('#find-input').value = v;
    runGlobalFind(true);
  };
  $('#fp-form').onsubmit = (e) => {
    e.preventDefault();
    const v = ($('#fp-input')?.value || '').trim();
    if (!v) return;
    $('#fp-input').blur();
    $('#find-input').value = v;
    runGlobalFind(true);
  };

  /**
   * 点「搜索」直接进汇总页（结果/进度都在汇总页展示），
   * 底部只保留轻量搜索条（↑↓ 逐条跳转用），不再单独铺进度。
   */
  const openFind = () => {
    gFind.open = true;
    $('#rd-shell')?.classList.add('finding');
    const fb = $('#find-bar');
    if (fb) fb.hidden = false;
    openFindPage();
    const inp = $('#find-input');
    if (inp && gFind.kw) inp.value = gFind.kw;
    if (gFind.hits.length) setCount(`${gFind.pos + 1}/${gFind.hits.length}`);
  };
  const closeFind = () => {
    gFind.open = false;
    gFind.gen++;
    gFind.hits = [];
    gFind.pos = -1;
    gFind.pendCi = -1;
    gFind.pendOrd = -1;
    gFind.kw = '';
    gFind.busy = false;
    gFind.progress = '';
    $('#rd-shell')?.classList.remove('finding');
    const fp = $('#find-page');
    if (fp) fp.hidden = true;
    const fb = $('#find-bar');
    if (fb) fb.hidden = true;
    const inp = $('#find-input');
    if (inp) inp.value = '';
    clearMarks();
    setCount('0/0');
  };
  MENU_ACTIONS.find = openFind;
  $('#find-close').onclick = closeFind;
  // 点计数（n/N）打开全书命中汇总页
  $('#find-count').onclick = openFindPage;
  /**
   * ↑/↓ 绑定：
   * - pointerdown：抢在输入框失焦/键盘收起前执行（真机常见首次点击落空）
   * - click：部分 WebView 不派发 pointerdown，必须兜底真正执行；
   *   与 pointerdown 用 fromPointer 去重，避免一次点击跳两步。
   */
  const bindFindNav = (el, fn) => {
    if (!el) return;
    let fromPointer = false;
    el.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      fromPointer = true;
      fn();
      setTimeout(() => { fromPointer = false; }, 500);
    });
    el.addEventListener('click', (e) => {
      e.preventDefault();
      if (fromPointer) return;
      fn();
    });
  };
  bindFindNav($('#find-prev'), () => stepFind(-1));
  bindFindNav($('#find-next'), () => stepFind(1));

  // 输入即全书检索（防抖）；默认就是全书，不再需要额外的「全书」按钮
  let findTimer = 0;
  $('#find-input').oninput = () => {
    clearTimeout(findTimer);
    findTimer = setTimeout(runGlobalFind, 350);
  };
  $('#find-input').onkeydown = (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (!gFind.hits.length) runGlobalFind();
      else stepFind(e.shiftKey ? -1 : 1);
    }
    if (e.key === 'Escape') closeFind();
  };

  /* ---------- 自动阅读 ---------- */
  const AUTO_PAGED_SECS = [10, 20, 30]; // 分页：每页秒数
  const AUTO_SCROLL_PX = [1, 2, 3]; // 滚动：每帧像素
  let autoOn = false;
  let autoIdx = 0;
  let autoTimer = 0;
  let autoRaf = 0;
  const autoSpeedLabel = () => (paged ? `${AUTO_PAGED_SECS[autoIdx]} 秒/页` : `${AUTO_SCROLL_PX[autoIdx]} 档`);

  const paintAutoBar = () => {
    $('#auto-speed').textContent = autoSpeedLabel();
  };
  /** 顶部自动阅读进度条：按当前「秒/页」走满一格，翻页时重置 */
  const autoTick = (secs) => {
    const el = $('#auto-tick');
    if (!el) return;
    if (!secs) {
      el.hidden = true;
      el.classList.remove('run');
      return;
    }
    el.hidden = false;
    el.style.setProperty('--dur', secs + 's');
    el.classList.remove('run');
    void el.offsetWidth; // 强制回流，重新触发动画
    el.classList.add('run');
  };
  const stopAuto = (silent = false) => {
    autoOn = false;
    clearInterval(autoTimer);
    cancelAnimationFrame(autoRaf);
    autoTimer = 0;
    autoRaf = 0;
    autoTick(0);
    $('#r-auto')?.classList.remove('on');
    const bar = $('#auto-bar');
    if (bar) bar.hidden = true;
    window.removeEventListener('wheel', onUserInteract);
    document.removeEventListener('touchstart', onUserInteract);
    // 撤销待续的跨章续读：否则「停止」发生在跨章重渲染途中时，新实例会依据
    // 这个残留标记把自动阅读重新拉起来（用户明明已经停止，却仍在自动翻章）。
    gAutoContinue = false;
    if (!silent) toast('已停止自动阅读');
  };
  // 用户手动滚动/触摸即停止（滚动模式）
  const onUserInteract = () => stopAuto();

  const startAuto = () => {
    autoOn = true;
    $('#r-auto')?.classList.add('on');
    const bar = $('#auto-bar');
    if (bar) bar.hidden = false;
    paintAutoBar();
    if (paged) {
      autoTick(AUTO_PAGED_SECS[autoIdx]);
      autoTimer = setInterval(() => {
        if (pageIdx >= pageEls.length - 1) {
          if (index < total - 1) {
            // 自动阅读跨章续读：停掉本章定时器，跳到下一章由 renderReader 在 paint 后重新 startAuto
            stopAuto(true);
            gAutoContinue = true;
            gPendingSlide = 1; // 跨章按「去下一页」方向滑入，视觉与自动翻页连贯
            goChapter(index + 1, 0, false);
          } else {
            stopAuto(true);
            toast('已到全书末尾，自动停止');
          }
          return;
        }
        turnPage(1);
        autoTick(AUTO_PAGED_SECS[autoIdx]); // 新一页重新计时
      }, AUTO_PAGED_SECS[autoIdx] * 1000);
    } else {
      const px = AUTO_SCROLL_PX[autoIdx];
      const step = () => {
        if (!autoOn) return;
        stage.scrollBy(0, px);
        if (stage.scrollTop + stage.clientHeight >= stage.scrollHeight - 2) {
          if (index < total - 1) {
            stopAuto(true);
            gAutoContinue = true;
            goChapter(index + 1, 0, false);
          } else {
            stopAuto(true);
            toast('已到全书末尾，自动停止');
          }
          return;
        }
        autoRaf = requestAnimationFrame(step);
      };
      autoRaf = requestAnimationFrame(step);
      window.addEventListener('wheel', onUserInteract, { passive: true });
      document.addEventListener('touchstart', onUserInteract, { passive: true });
    }
  };
  MENU_ACTIONS.auto = () => (autoOn ? stopAuto() : startAuto());
  $('#auto-stop').onclick = () => stopAuto();
  $('#auto-slower').onclick = () => {
    autoIdx = Math.max(0, autoIdx - 1);
    paintAutoBar();
    if (autoOn) {
      stopAuto(true);
      startAuto();
    }
  };
  $('#auto-faster').onclick = () => {
    autoIdx = Math.min((paged ? AUTO_PAGED_SECS : AUTO_SCROLL_PX).length - 1, autoIdx + 1);
    paintAutoBar();
    if (autoOn) {
      stopAuto(true);
      startAuto();
    }
  };

  /* ---------- 章节渲染（缓存优先） ---------- */
  /** 后台预拉下一章入缓存，失败静默（本地书无 src，自动跳过） */
  const preloadNext = async () => {
    const ni = index + 1;
    if (ni >= total || !src) return;
    try {
      if (book.preview) {
        if (previewChapterGet(ni)) return;
        const r = await src.getContent(book, book.chapters[ni]);
        if (!r.error && r.content) previewChapterPut(ni, r.content);
        return;
      }
      const key = id + '||' + ni;
      const cached = await db.get('chapters', key);
      if (cached) {
        if (cached.content != null) rawMemSet(ni, cached.content); // 顺手进内存，跨章可走原地切换
        return;
      }
      const r = await src.getContent(book, book.chapters[ni]);
      if (!r.error && r.content) {
        rawMemSet(ni, r.content);
        await db.put('chapters', { id: key, content: r.content, cachedAt: Date.now() });
      }
    } catch (e) {
      /* 预加载失败不影响当前阅读 */
    }
  };

  /**
   * 空闲时把相邻两章的正文预热到内存。
   * 跨章翻页要走「原地切章」必须**同步**拿到正文；本地书的正文在 IndexedDB 里，
   * 取一次至少等一个任务轮次，会让滑入动画晚启动，所以提前备好。
   */
  /**
   * 预读相邻章的正文（内存 → db → 联网）。
   * 用普通 setTimeout 而不是 requestIdleCallback：idle 回调在连续翻页/动画期间会被一直推迟，
   * 结果就是「正文永远没预读、每章都掉进现算慢路径」——实测过，必须用定时器兜底。
   */
  const prefetchNeighbors = async () => {
    const base = index;
    // 往后预两章：用户连续翻几十章时，只备一章第二次就断粮
    for (const i of [base + 1, base + 2, base - 1]) {
      if (i < 0 || i >= total || rawMem.has(i)) continue;
      await loadRaw(i);
    }
    schedulePrime(0, true); // 正文就绪后立刻预分页
  };

  /* ---------- 预分页：提前把下一章切成页 ----------
   * 冷启动的那一次 paginate 在中端机（4× 降频实测）要 60ms+，这段时间滑入动画起不来，
   * 是跨章翻页「卡一下」的最后一个来源。做法：本章读到还剩几页时，用空闲时间
   * 把下一章在**隐藏的同尺寸副本**里分好页，跨章时直接把切好的 .pages 换进去。
   * 隐藏副本走的是同一套 paginate 代码，测量结果与真身一致，但完全不碰当前画面。
   */
  const pageCache = new Map(); // 章号 → { pages, pageEls, headText, showHead, showFoot }
  let pageCacheSig = '';

  /**
   * 版式签名：字号/行距/段距/边距/字体/字重/页眉页脚 任一变化，预分页结果即作废。
   * 刻意**不读** #r-body 的宽高——那是一次强制同步布局（切章热路径上白花十几毫秒）。
   * 视口变化由 resize → repaginate → paginate 处理，而 paginate 会清空整个缓存。
   */
  const layoutSig = () =>
    [fontSize, lh, pg, marginPx, fontFam, weightVal, hfCfg.head, hfCfg.foot.join(',')].join('|');
  /** 收拾一个已分好页的容器：外层槽位在就整块删，否则单删 .pages */
  const disposePages = (pages) => {
    if (!pages) return;
    const slot = pages.parentElement;
    if (slot && slot.classList && slot.classList.contains('pg-slot')) slot.remove();
    else pages.remove();
  };
  const dropStalePages = () => {
    const sig = layoutSig();
    if (sig !== pageCacheSig) {
      for (const v of pageCache.values()) disposePages(v.pages);
      pageCache.clear();
      pageCacheSig = sig;
    }
  };

  /**
   * 淘汰窗口外的预分页缓存。
   * 每换一章都会新增 +1/+2 的条目，不淘汰的话缓存和槽位会随章数无限增长
   * （实测连续翻 14 章堆到 17 个槽位），既是内存泄漏、也让布局越来越慢。
   * 只保留当前章前后各一章 + 往后两章。
   */
  const trimPageCache = () => {
    for (const [k, v] of [...pageCache.entries()]) {
      if (k >= index - 1 && k <= index + 2) continue;
      disposePages(v.pages);
      pageCache.delete(k);
    }
  };

  /**
   * 清扫正文里的预分页槽位：只保留「当前章」「缓存里待用的」「正在滑出的旧页」。
   * 不做这一步，连续翻几十章会一路堆积旧槽位（实测 14 次跨章堆到 17 个）——
   * 既是内存泄漏，也让每次布局要处理的节点越来越多。
   */
  const sweepSlots = () => {
    const keep = new Set();
    if (pagesEl && pagesEl.parentElement && pagesEl.parentElement.classList.contains('pg-slot')) keep.add(pagesEl.parentElement);
    for (const v of pageCache.values()) if (v.slot) keep.add(v.slot);
    $$('#r-body .pg-slot').forEach((slot) => {
      if (keep.has(slot) || slot.classList.contains('loading-slot')) return;
      if (slot.querySelector('.pages.ghost')) return; // 正在滑出的旧页，等它自己收
      slot.remove();
    });
  };

  /** 取走预分页结果（元素会被搬进正文，条目随即作废） */
  const takePrimed = (ni) => {
    dropStalePages();
    const hit = pageCache.get(ni);
    if (!hit) return null;
    pageCache.delete(ni);
    return hit;
  };

  /** 在隐藏副本里把第 i 章切成页并缓存；失败静默（跨章时退回现算） */
  const primeChapter = (i) => {
    if (!paged || i < 0 || i >= total) return;
    if (Date.now() - gGestureAt < 400) return; // 手势进行中/刚结束，别抢主线程
    dropStalePages();
    if (pageCache.has(i)) return;
    const raw = rawMemGet(i);
    if (raw == null) return; // 正文还没预热到，等 prefetchNeighbors 回来再触发
    const rb = $('#r-body');
    if (!rb) return;
    // 槽位直接挂在 #r-body 里（inset:0，与正文同尺寸），只把 visibility 关掉：
    // 版式在空闲时就算完，跨章时只改 visibility（不触发重排），省掉「插 DOM → 重新布局」那一笔。
    const slot = h('div', { class: 'pg-slot' });
    // 注意：不能用 pointer-events:none —— 显示出来之后要能接收手势（visibility:hidden 本身就不收事件）
    slot.style.cssText = 'position:absolute;inset:0;visibility:hidden';
    rb.append(slot);
    try {
      const chapObj = book.chapters[i];
      const html = `<h2 class="chapter-title">${esc(purifyTitle(chapObj.name || ''))}</h2><div class="content">${renderChapterHtml(raw, chapObj.name || '')}</div>`;
      const res = paginate(0, { offline: true, host: slot, html, chapter: chapObj });
      if (res && res.pageEls && res.pageEls.length) pageCache.set(i, Object.assign(res, { slot }));
      else slot.remove();
    } catch (e) {
      slot.remove(); // 预分页失败不影响正确性
    }
  };

  /**
   * 提前预分页相邻章。
   * 触发点放在「本章刚渲染好」而不是「读到章尾」——分页是一次 15~60ms 的同步计算，
   * 一旦它恰好和用户滑动撞上，动画就会被它压住（实测能多出 40ms 以上）。
   * 早做的话，等用户真翻到章尾时早就备好了。
   * 往后备两章：连续翻几十章的场景，只备一章第二次就断粮。
   */
  let primeTimer = 0;
  let primeGen = 0;
  let primeActive = false;
  /**
   * 排一轮预分页：串行处理 +1 / +2 / -1，每章之间让出一拍（30ms），
   * 避免一次性阻塞几十毫秒。
   * @param force true = 换章了，作废正在跑的那一轮（否则会一直给旧章做无用功）
   */
  const schedulePrime = (delay = 120, force = false) => {
    if (!paged) return;
    if (primeActive && !force) return; // 已有一轮在跑，别反复重启（翻页时 showPage 会被高频调用）
    primeGen++;
    const gen = primeGen;
    primeActive = true;
    clearTimeout(primeTimer);
    const queue = () => {
      const q = [index + 1, index + 2, index - 1].filter((i) => i >= 0 && i < total);
      let k = 0;
      const step = () => {
        if (gen !== primeGen) return; // 被换章作废
        if (k >= q.length) {
          primeActive = false;
          return;
        }
        if (Date.now() - gGestureAt < 400) {
          primeTimer = setTimeout(step, 200); // 手势进行中，让开
          return;
        }
        primeChapter(q[k++]);
        primeTimer = setTimeout(step, 30);
      };
      step();
    };
    primeTimer = setTimeout(queue, delay);
  };
  const maybePrimeNext = () => schedulePrime(120);

  /**
   * 原地切章：跨章翻页只换正文 + 重分页 + 滑入，**不重建整个阅读器**。
   *
   * 为什么单独做一条路径：走 renderReader 等于把阅读器重新初始化一遍——十几次设置读取、
   * 全套事件绑定、工具坞/目录/TTS 重建，而滑入动画必须等这些全部做完才能开始；
   * 章内翻页只有一次 transform。两者的手感差就是这么来的
   * （实测「松手 → 动画开始」约 50ms，手机上是 2~4 倍）。
   *
   * @returns {boolean} true = 已处理；false = 正文还没预热好，交给完整渲染兜底
   */
  /** 取某章原始正文（内存 → 试读缓存 → chapters 表 → 联网），并写入内存 */
  const loadRaw = async (i) => {
    if (rawMem.has(i)) return rawMemGet(i);
    let html = null;
    try {
      if (book.preview) {
        html = previewChapterGet(i);
      } else {
        const c = await db.get('chapters', id + '||' + i);
        if (c) html = c.content;
      }
      if (html == null && src) {
        const r = await src.getContent(book, book.chapters[i]);
        if (!r.error && r.content) {
          html = r.content;
          if (book.preview) previewChapterPut(i, html);
          else {
            try {
              await db.put('chapters', { id: id + '||' + i, content: html, cachedAt: Date.now() });
            } catch (e) {
              /* 忽略缓存写失败 */
            }
          }
        }
      }
    } catch (e) {
      html = null;
    }
    if (html != null) rawMemSet(i, html);
    return html;
  };

  /**
   * 就地切换到第 ni 章（正文必须已经在 rawMem 里）。
   *
   * 目录跳章、上下一章、翻页跨章**共用这一条路径**——它只换正文、重建分页、更新状态，
   * 不重建阅读器。原来这些都走 renderReader（十几次设置读取 + 全套事件绑定 + 面板重建），
   * 连续跳几十章时每次 150~200ms，这就是「从目录往后翻几十章会卡」的根因。
   *
   * @param opts.dir        0 = 直接显示（跳章）；±1 = 从该方向滑入（翻页跨章）
   * @param opts.startPx    滑入起始位移（接上松手时手指拖走的位置）
   * @param opts.targetPage null = 首页；>=0 = 指定页；-1 = 末页
   */
  const applyChapter = (ni, opts = {}) => {
    if (ni < 0 || ni >= total || ni === index) return false;
    const raw = rawMemGet(ni);
    if (raw == null) return false;
    const body = $('#r-body');
    if (!body) return false;
    const dir = opts.dir || 0;
    const sliding = dir !== 0;
    stopAuto(true);
    // 朗读中跨章：正文要重建，旧队列和旧 DOM 全部作废，只能停下并给「从本页开始」的出口。
    // 直接静默退出会让人以为朗读按钮坏了，所以切完章要把浮条亮出来。
    const wasTts = ttsOn;
    if (wasTts) exitTts({ restack: false });
    if (hasSel()) clearSel();
    // 旧页留下来做「滑出」：章内翻页是旧页滑出 + 新页滑入，只画新页滑入的话左半边是空白底。
    const ghost = sliding && pagesEl && pagesEl.parentElement ? pagesEl : null;
    const prevPages = pagesEl; // 跳章（sliding=false）时旧容器没人负责清理，下面收掉
    const ghostIdx = pageIdx;
    const cached = takePrimed(ni);
    // 只有走现算才需要先把旧页摘下来（paginate 会 innerHTML 清空正文）。
    // 命中预分页时旧页原地留着，一个 DOM 节点都不搬 —— 搬动会触发整章重新布局。
    if (!cached && ghost) ghost.remove();
    index = ni;
    chapter = book.chapters[ni];
    chapterHtml = `<h2 class="chapter-title">${esc(purifyTitle(chapter.name || ''))}</h2><div class="content">${renderChapterHtml(raw, chapter.name || '')}</div>`;
    try {
      history.replaceState(null, '', `#/read/${encodeURIComponent(id)}/${ni}`);
    } catch (e) {
      /* 个别环境限制 replaceState，忽略 */
    }
    const lhStr = String(lh);
    if (body.style.lineHeight !== lhStr) body.style.lineHeight = lhStr; // 值没变就别写，免得把整章标脏
    const wantPage = opts.targetPage == null ? 0 : opts.targetPage;
    if (cached) {
      // 预分的页本来就在 #r-body 的隐藏槽位里，显示出来即可：不动 DOM 结构 → 不触发重排。
      cached.slot.style.visibility = 'visible';
      pageEls = cached.pageEls;
      pagesEl = cached.pages;
      headText = cached.headText;
      showHead = cached.showHead;
      showFoot = cached.showFoot;
      pageIdx = 0;
      // 先把 transform 定下来（脏区最小），页脚/刻度/绑定都放到动画开始之后再改
      if (sliding) slideIn(dir, opts.startPx || 0, ghost, ghostIdx);
      else showPage(wantPage < 0 ? pageEls.length - 1 : Math.min(wantPage, pageEls.length - 1), false);
      paintPgFooters();
      startFootClock();
      bindPaging();
      paintTicks();
    } else if (sliding) {
      paginate(dir > 0 ? 0 : -1, { deferFirstPaint: true }); // 首帧交给 slideIn
      if (ghost && pagesEl) body.insertBefore(ghost, pagesEl);
      slideIn(dir, opts.startPx || 0, ghost, ghostIdx);
    } else {
      paginate(wantPage < 0 ? 0 : wantPage);
      if (wantPage < 0) showPage(pageEls.length - 1, false);
    }
    // 跳章不播动画：旧章容器直接收掉（滑入场景由 slideIn 的延时清理负责）
    if (!sliding && prevPages && prevPages !== pagesEl && prevPages.isConnected) disposePages(prevPages);
    trimPageCache(); // 淘汰窗口外的预分页（防缓存与槽位随章数增长）
    sweepSlots(); // 兜底：清掉没人认领的旧槽位
    saveProgress(id, index, chapter.url, pageIdx);
    // 预热（读正文 + 预分页）排到滑入动画之后：读正文要占主线程，排在动画首帧前面会把它压住；
    // 但也不能太晚——快速连续翻章时，晚一步就掉进「现算」慢路径。
    // 注意必须先 prefetchNeighbors（它负责读正文），再由它转 schedulePrime；只调预分页会因为没正文而空转。
    clearTimeout(switchWarmTimer);
    switchWarmTimer = setTimeout(prefetchNeighbors, 340);
    schedulePrime(340, true); // 换章：作废旧队列（+1/+2/-1 都变了）
    restoreFindAfterSwitch();
    if (wasTts) paintListenBar();
    return true;
  };

  /**
   * 切章后恢复全书搜索的高亮/定位。
   * 整页重渲染时这段逻辑在 renderReader 尾部；原地切章不经过那里，必须自己补上，
   * 否则「搜索跳到另一章」只会换章而不会高亮命中处。
   */
  const restoreFindAfterSwitch = () => {
    if (!gFind.open || !gFind.kw) return;
    const fb = $('#find-bar');
    if (fb) fb.hidden = false;
    $('#rd-shell')?.classList.add('finding');
    const inp = $('#find-input');
    if (inp && document.activeElement !== inp) inp.value = gFind.kw;
    if (!gFind.hits.some((x) => x.ci === index)) {
      if (gFind.hits.length) setCount(`${gFind.pos + 1}/${gFind.hits.length}`);
      return;
    }
    markChapter(gFind.kw, gFind.gen).then(() => {
      if (gFind.pendCi === index) {
        focusMarkAt(gFind.pendOrd);
        gFind.pendCi = -1;
        gFind.pendOrd = -1;
      }
      if (gFind.hits.length) setCount(`${gFind.pos + 1}/${gFind.hits.length}`);
      if (!$('#find-page')?.hidden) renderFindPage();
    });
  };

  /** 同步原地切章（正文已在内存：无重建、无 await） */
  const switchChapterInPlace = (ni, dir, startPx = 0) => {
    if (!paged || !rawMem.has(ni)) return false;
    return applyChapter(ni, { dir, startPx });
  };

  /**
   * 异步原地切章：正文没预热到时走这里。
   * 立刻画一张「正在加载本章…」的页（滑入方向与正常翻页一致）作为反馈，
   * 正文到了再无声替换成真正的页——不会再触发第二次动画。
   * 取不到内容才退回整页重渲染。
   */
  const switchChapterAsync = async (ni, dir, startPx, targetPage, lock = false) => {
    const gen = ++switchGen;
    if (lock) gJumpLockUntil = Date.now() + JUMP_LOCK_MS;
    const body = $('#r-body');
    if (!body || !paged) {
      // 滚动模式/异常情况：直接整页重渲染
      gPendingPage = targetPage == null ? 0 : targetPage;
      gPendingSlide = dir || 0;
      gSlideStartPx = startPx || 0;
      try {
        history.replaceState(null, '', `#/read/${encodeURIComponent(id)}/${ni}`);
      } catch (e) {
        /* 忽略 */
      }
      $$('.toc-mask, .toc-drawer').forEach((e) => e.remove());
      renderReader(view, id, ni, view.classList.contains('chrome-hidden'));
      return;
    }
    // ① 先摆一张加载页：让用户立刻看到「翻过去了，正在取内容」，而不是卡在上一页
    const ghost = dir !== 0 && pagesEl && pagesEl.parentElement ? pagesEl : null;
    const ghostIdx = pageIdx;
    const holder = h('div', { class: 'pg-slot loading-slot' });
    holder.style.cssText = 'position:absolute;inset:0';
    const loadPages = h('div', { class: 'pages' });
    const loadPg = h('div', { class: 'page' });
    loadPg.style.left = '0%';
    loadPg.append(h('div', { class: 'rd-loading' }, h('span', {}, '正在加载本章…')));
    loadPages.append(loadPg);
    holder.append(loadPages);
    body.append(holder);
    const prevPagesEl = pagesEl;
    const prevPageEls = pageEls;
    pagesEl = loadPages;
    pageEls = [loadPg];
    pageIdx = 0;
    try {
      if (dir !== 0) slideIn(dir, startPx, ghost, ghostIdx);
      else showPage(0, false);
    } catch (e) {
      /* 忽略 */
    }
    // 章名先切到目标章：加载页配的还是旧章名会让人以为没翻过去
    const tchap = book.chapters[ni];
    const labelEl = $('#r-chap');
    if (labelEl) labelEl.textContent = purifyTitle(tchap.name || '') || `第 ${ni + 1} 章`;
    const mchap = $('#r-meta-chap');
    if (mchap) mchap.textContent = `第 ${ni + 1} / ${total} 章`;
    // ② 取正文
    const raw = await loadRaw(ni);
    // 期间又换了章 / 阅读页已被销毁：本次作废（新请求会自己收拾）
    if (gen !== switchGen || !body.isConnected) return;
    if (raw == null) {
      holder.remove();
      pagesEl = prevPagesEl;
      pageEls = prevPageEls;
      toast('本章内容取不到，稍后再试');
      return;
    }
    // ③ 内容到了：先撤掉加载页，再无声换上真页（dir=0 → 不播动画）
    holder.remove();
    pagesEl = prevPagesEl;
    pageEls = prevPageEls;
    if (!applyChapter(ni, { dir: 0, targetPage })) {
      gPendingPage = targetPage == null ? 0 : targetPage;
      gPendingSlide = 0;
      gSlideStartPx = 0;
      renderReader(view, id, ni, view.classList.contains('chrome-hidden'));
    }
  };

  const paint = async () => {
    // 换章重置自动阅读；搜索会话保留（全书搜索可跨章导航，由渲染后恢复块重建高亮）
    stopAuto(true);
    const body = $('#r-body');
    let clean = null;
    try {
      // 跨章预取命中：直接同步渲染，避免再 await 一次
      clean = prefetchedFor === index && prefetchedRaw != null
        ? renderChapterHtml(prefetchedRaw, (chapter || {}).name || '')
        : await chapterHtmlAt(index);
    } catch (e) {
      body.innerHTML = `<div class="empty"><h2>正文获取失败</h2><pre class="err">${esc(String(e && e.message || e))}</pre></div>`;
      return;
    }
    if (!clean) {
      if (!src && !book.preview) {
        body.innerHTML = '<div class="empty"><h2>书源缺失</h2><p>该书来源的书源已被删除。</p></div>';
        return;
      }
      body.innerHTML = '<div class="empty"><h2>正文获取失败</h2><p>本章没有取到内容，稍后重试或回详情页刷新目录。</p></div>';
      return;
    }
    chapterHtml = `<h2 class="chapter-title">${esc(purifyTitle(chapter.name || ''))}</h2><div class="content">${clean}</div>`;
    body.style.lineHeight = lh;
    if (paged) {
      const pending = gPendingPage;
      const slideDir = gPendingSlide;
      gPendingPage = null;
      gPendingSlide = 0;
      paginate(pending != null && pending >= 0 ? pending : restorePage, { deferFirstPaint: !!slideDir });
      // 向前跨章落上一章末页也交给 slideIn（它会算 target=末页），避免多画一帧
      if (slideDir) {
        slideIn(slideDir, gSlideStartPx); // 跨章滑入：与正常翻页同款动画（目录/按钮跳章无此动画）
        gSlideStartPx = 0;
      }
      else if (pending === -1) showPage(pageEls.length - 1, false);
      await saveProgress(id, index, chapter.url, pageIdx);
    } else {
      body.innerHTML = chapterHtml;
      markedCi = -1; // 正文重建，旧高亮失效
      markedKw = '';
      stage.scrollTo({ top: 0 });
      await saveProgress(id, index, chapter.url, 0);
    }
    updateChapLabel();
    preloadNext();
    prefetchNeighbors();
    maybePrimeNext();
  };

  await paint();
  paintProg();
  applyBrightness(brightness, false); // 亮度遮罩初始同步（不重复落库）

  paintListenBar();
  paintDockHeight();
  // 自动阅读跨章续读：本章走完且仍有下一章时，重渲染后从下一章顶端继续（正文加载失败则不再续）
  if (gAutoContinue) {
    gAutoContinue = false;
    if (!$('#r-body .empty')) startAuto();
  }

  // 跳章重渲染后恢复搜索条与本章高亮（全书搜索跨章导航）
  if (gFind.open && gFind.kw) {
    const fb = $('#find-bar');
    if (fb) fb.hidden = false;
    $('#rd-shell')?.classList.add('finding');
    const inp = $('#find-input');
    if (inp && document.activeElement !== inp) inp.value = gFind.kw;
    if (gFind.hits.some((h) => h.ci === index)) {
      markChapter(gFind.kw, gFind.gen).then(() => {
        if (gFind.pendCi === index) {
          focusMarkAt(gFind.pendOrd);
          gFind.pendCi = -1;
          gFind.pendOrd = -1;
        }
        if (gFind.hits.length) setCount(`${gFind.pos + 1}/${gFind.hits.length}`);
      });
    } else if (gFind.hits.length) {
      setCount(`${gFind.pos + 1}/${gFind.hits.length}`);
    }
  }

  /* ---------- 屏幕常亮（Wake Lock；不支持的环境静默降级） ---------- */
  let keepAwake = await settings.get('readerKeepAwake', false);
  let wakeLock = null;
  const wakeSupported = typeof navigator !== 'undefined' && 'wakeLock' in navigator;
  const releaseWake = async () => {
    if (!wakeLock) return;
    try {
      await wakeLock.release();
    } catch (e) {
      /* 忽略释放异常 */
    }
    wakeLock = null;
  };
  const applyKeepAwake = async (on, silent = false) => {
    keepAwake = on;
    await settings.set('readerKeepAwake', on);
    if (!on) {
      await releaseWake();
      return;
    }
    if (!wakeSupported) {
      if (!silent) toast('当前环境不支持屏幕常亮');
      return;
    }
    try {
      wakeLock = await navigator.wakeLock.request('screen');
      wakeLock.addEventListener('release', () => {
        wakeLock = null;
      });
    } catch (e) {
      if (!silent) toast('屏幕常亮开启失败');
    }
  };
  // 应用切回前台时重新持有（系统会在页面隐藏时自动释放）
  const onVisible = async () => {
    if (document.visibilityState === 'visible' && keepAwake && wakeSupported && !wakeLock) {
      try {
        wakeLock = await navigator.wakeLock.request('screen');
      } catch (e) {
        /* 忽略 */
      }
    }
  };
  document.addEventListener('visibilitychange', onVisible);
  if (keepAwake) applyKeepAwake(true, true);

  /* ---------- 阅读统计（15 秒心跳累计） ---------- */
  const STAT_MS = 15000;
  const statTimer = setInterval(async () => {
    if (document.visibilityState !== 'visible') return;
    try {
      await saveProgress(id, index, chapter.url, paged ? pageIdx : 0, STAT_MS);
      await addReadMsMonth(STAT_MS); // 书架横幅「本月阅读」按自然月桶累计
    } catch (e) {
      /* 统计写入失败不影响阅读 */
    }
  }, STAT_MS);

  /* ---------- 排版面板（字号 / 行距 / 主题 / 段距 / 页边距 / 亮度 / 字体 / 翻页方式 / 常亮） ---------- */
  const applyTheme = (t) => {
    theme = t;
    if (t === 'paper') document.body.removeAttribute('data-reader-theme');
    else document.body.dataset.readerTheme = t;
    const body = $('#r-body');
    if (body) body.className = 'reader-body ' + t + ' font-' + fontFam;
    // 同步系统栏/窗口底色，避免沉浸时出现黑边
    try {
      if (window.ModouBridge && window.ModouBridge.setImmersive) {
        window.ModouBridge.setImmersive(true, RBG[t] || RBG.paper);
      }
    } catch (e) {
      /* 忽略 */
    }
  };

  /** 底部「夜间 / 日间」按钮：就是全局深浅开关，与设置页是同一个东西 */
  const paintNight = () => {
    const btn = $('#r-night');
    if (!btn) return;
    const dark = theme === 'dark';
    btn.innerHTML = `<span class="rd-ic">${dark ? IC.sun : IC.moon}</span><em>${dark ? '日间' : '夜间'}</em>`;
    btn.classList.toggle('on', dark);
    btn.setAttribute('aria-pressed', dark ? 'true' : 'false');
  };
  MENU_ACTIONS.night = () => {
    // 直接写全局主题，二者永远一致；saveAppTheme 会 paintDocTheme → onThemeChange 回调同步正文底色
    if (theme === 'dark') saveAppTheme('light');
    else saveAppTheme('dark');
    // 上面回调已同步；这里兜底再刷一次按钮，避免回调时序差异
    paintNight();
  };
  paintNight();
  paintMenu(); // 工具坞按用户排布渲染（须在 paintNight 之后：夜间按钮图标要同步）

  // 全局主题变化（设置页改深浅 / auto 跟随系统切换）时，正文底色同步，保证二者一致
  const unsubTheme = onThemeChange(() => {
    const nd = docThemeNow() === 'dark';
    darkMode = nd;
    theme = nd ? 'dark' : lightTheme;
    applyTheme(theme);
    paintNight();
  });

  MENU_ACTIONS.typo = () => {
    const body = h('div', { class: 'typo-rows' });
    // 字号
    const fontVal = h('b', {}, fontSize + 'px');
    const fontDown = h('button', { class: 'btn stepper', style: 'flex:1' }, 'A−');
    const fontUp = h('button', { class: 'btn stepper', style: 'flex:1' }, 'A+');
    const applyFont = (v) => {
      fontSize = v;
      fontVal.textContent = v + 'px';
      const b = $('#r-body');
      if (b) b.style.fontSize = v + 'px';
      settings.set('readerFontSize', v); // 先应用后落库，交互零延迟
      scheduleRepage();
    };
    fontDown.onclick = () => applyFont(Math.max(14, fontSize - 1));
    fontUp.onclick = () => applyFont(Math.min(26, fontSize + 1));
    body.append(
      h('div', { class: 'sheet-row' }, h('div', { class: 'row-label' }, h('span', {}, '字号'), fontVal), h('div', { class: 'row', style: 'display:flex;gap:10px' }, fontDown, fontUp))
    );
    // 字重（中文衬线体默认偏细时可调粗）
    const wtSeg = h('div', { class: 'seg' });
    for (const [k, label, v] of WEIGHTS) {
      const b = h('button', { class: k === weight ? 'on' : '' }, label);
      b.onclick = () => {
        weight = k;
        weightVal = v;
        wtSeg.querySelectorAll('button').forEach((x) => x.classList.remove('on'));
        b.classList.add('on');
        const bd = $('#r-body');
        if (bd) bd.style.fontWeight = String(v);
        settings.set('readerFontWeight', k);
        scheduleRepage();
      };
      wtSeg.append(b);
    }
    body.append(h('div', { class: 'sheet-row' }, h('div', { class: 'row-label' }, h('span', {}, '字重')), wtSeg));
    // 行距 / 段距 / 页边距：数值滑杆可调（不再固定档位），改动即生效并重分页
    const mkRange = (label, val, min, max, step, fmt, onapply) => {
      const valEl = h('b', {}, fmt(val));
      const range = h('input', { type: 'range', min: String(min), max: String(max), step: String(step), value: String(val) });
      range.oninput = () => {
        const v = Number(range.value);
        valEl.textContent = fmt(v);
        onapply(v);
      };
      body.append(
        h(
          'div',
          { class: 'sheet-row' },
          h('div', { class: 'row-label' }, h('span', {}, label), valEl),
          range
        )
      );
      return range;
    };
    mkRange('行距', lh, 1.4, 2.6, 0.1, (v) => v.toFixed(1) + ' 倍', (v) => {
      lh = v;
      const bd = $('#r-body');
      if (bd) bd.style.lineHeight = v;
      settings.set('readerLineHeight', v);
      scheduleRepage();
    });
    mkRange('段距', pg, 0, 2, 0.1, (v) => (v === 0 ? '无' : v.toFixed(1) + ' 倍'), (v) => {
      pg = v;
      const bd = $('#r-body');
      if (bd) bd.style.setProperty('--para-gap', v + 'em');
      settings.set('readerParaGap', v);
      scheduleRepage();
    });
    mkRange('页边距', marginPx, 12, 44, 2, (v) => v + 'px', (v) => applyMargin(v));
    // 亮度
    const brVal = h('b', {}, Math.round(brightness * 100) + '%');
    const brRange = h('input', { type: 'range', min: '0.5', max: '1', step: '0.05', value: String(brightness) });
    const brReset = h('button', { class: 'mini' }, '恢复默认');
    brRange.oninput = () => {
      const v = Number(brRange.value);
      brVal.textContent = Math.round(v * 100) + '%';
      applyBrightness(v);
    };
    brReset.onclick = () => {
      brRange.value = '1';
      brVal.textContent = '100%';
      applyBrightness(1);
    };
    body.append(
      h(
        'div',
        { class: 'sheet-row' },
        h('div', { class: 'row-label' }, h('span', {}, '亮度'), h('span', {}, brVal, brReset)),
        brRange,
        h('small', { class: 'muted' }, '降低亮度适合夜间窝灯阅读；不影响系统亮度')
      )
    );
    // 屏幕常亮：与亮度同属「屏幕/显示」，紧挨着放，不再被主题、字体隔开
    const kaInput = h('input', { type: 'checkbox' });
    kaInput.checked = keepAwake;
    kaInput.onchange = () => applyKeepAwake(kaInput.checked);
    body.append(
      h(
        'div',
        { class: 'sheet-row' },
        h('div', { class: 'row-label' }, h('span', {}, '屏幕常亮'), h('small', { class: 'muted' }, wakeSupported ? '阅读时保持亮屏' : '当前环境不支持')),
        h('label', { class: 'switch' }, kaInput, h('span'))
      )
    );
    // 主题（四档：三档浅色纸底 + 夜间；深浅统一写全局，与设置页一致）
    const thSeg = h('div', { class: 'seg' });
    for (const t of THEMES) {
      const b = h('button', { class: t === theme ? 'on' : '' }, THEME_LABEL[t]);
      b.onclick = () => {
        thSeg.querySelectorAll('button').forEach((x) => x.classList.remove('on'));
        b.classList.add('on');
        if (t === 'dark') {
          saveAppTheme('dark');
        } else {
          lightTheme = t;
          settings.set('readerLightTheme', t);
          saveAppTheme('light');
        }
        paintNight();
      };
      thSeg.append(b);
    }
    body.append(h('div', { class: 'sheet-row' }, h('div', { class: 'row-label' }, h('span', {}, '主题')), thSeg));
    // 字体
    const fontSeg = h('div', { class: 'seg' });
    for (const [v, label] of FONTS) {
      const b = h('button', { class: v === fontFam ? 'on' : '' }, label);
      b.onclick = () => {
        fontFam = v;
        fontSeg.querySelectorAll('button').forEach((x) => x.classList.remove('on'));
        b.classList.add('on');
        const bd = $('#r-body');
        if (bd) bd.className = 'reader-body ' + theme + ' font-' + v;
        settings.set('readerFontFamily', v);
        scheduleRepage();
      };
      fontSeg.append(b);
    }
    body.append(h('div', { class: 'sheet-row' }, h('div', { class: 'row-label' }, h('span', {}, '字体')), fontSeg));
    // 翻页方式（切换后重渲染阅读页）
    const modeSeg = h('div', { class: 'seg' });
    for (const [v, label] of PAGE_MODES) {
      const b = h('button', { class: v === pageMode ? 'on' : '' }, label);
      b.onclick = async () => {
        if (v === pageMode) return;
        await settings.set('readerPageMode', v);
        const hidden = view.classList.contains('chrome-hidden');
        sheet.close();
        renderReader(view, id, index, hidden);
      };
      modeSeg.append(b);
    }
    body.append(h('div', { class: 'sheet-row' }, h('div', { class: 'row-label' }, h('span', {}, '翻页方式')), modeSeg));

    // 页眉页脚（仅分页模式）：改动要重新分页（高度参与排版），因此保存后重渲染
    const hfSummary = () => {
      const headLabel = (HEAD_OPTS.find(([v]) => v === hfCfg.head) || HEAD_OPTS[0])[1];
      const foot = hfCfg.foot.map((k) => FOOT_LABEL[k]).filter(Boolean).join(' / ') || '不显示';
      return `页眉 ${headLabel} · 页脚 ${foot}`;
    };
    const openHeadFoot = () => {
      const sub = h('div', { class: 'src-pick hf-pick' });
      let head = hfCfg.head;
      let foot = [...hfCfg.foot];
      const headSeg = h('div', { class: 'seg' });
      for (const [v, label] of HEAD_OPTS) {
        const b = h('button', { class: v === head ? 'on' : '' }, label);
        b.onclick = () => {
          headSeg.querySelectorAll('button').forEach((x) => x.classList.remove('on'));
          b.classList.add('on');
          head = v;
        };
        headSeg.append(b);
      }
      // 页脚与页眉用同一套分段控件 UI（多选：每个选项独立开关态），不再混用两种样式
      const footSeg = h('div', { class: 'seg multi' });
      for (const [v, label] of Object.entries(FOOT_LABEL)) {
        const b = h('button', { class: foot.includes(v) ? 'on' : '' }, label);
        b.onclick = () => {
          if (foot.includes(v)) foot = foot.filter((x) => x !== v);
          else foot = [...foot, v];
          b.classList.toggle('on', foot.includes(v));
        };
        footSeg.append(b);
      }
      sub.append(
        h('div', { class: 'sheet-row' }, h('div', { class: 'row-label' }, h('span', {}, '页眉')), headSeg),
        h('div', { class: 'sheet-row' }, h('div', { class: 'row-label' }, h('span', {}, '页脚'), h('small', { class: 'muted' }, '可多选')), footSeg),
        h(
          'div',
          { class: 'btn-row', style: 'margin-top:14px' },
          h(
            'button',
            {
              class: 'btn primary hf-apply',
              onclick: async () => {
                await settings.set('readerHeadFoot', { head, foot, v: 2 });
                subSheet.close();
                sheet.close();
                renderReader(view, id, index, view.classList.contains('chrome-hidden'));
              },
            },
            '应用'
          )
        )
      );
      const subSheet = openSheet('页眉页脚', sub);
    };
    // 「页眉页脚」是独立小标题（与其他行的标签一致），下面是纯入口：当前配置摘要 + 箭头
    const hfRow = h(
      'button',
      { class: 'hf-row' },
      h('div', { class: 'hf-v' }, paged ? hfSummary() : '仅左右翻页模式生效 · 点此先设置'),
      h('span', { class: 'hf-go', html: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.5 5.5L16 12l-6.5 6.5"/></svg>' })
    );
    hfRow.onclick = openHeadFoot;
    // 与上一块（翻页方式）之间留出明显间距，不再连成一坨
    body.append(h('div', { class: 'sheet-row hf-sec' }, h('div', { class: 'row-label' }, h('span', {}, '页眉页脚')), hfRow));

    // 原生阅读（实验）：把「正文这一屏」交给 Android 本地绘制。
    // 分页、选区、手柄都由系统排版引擎算，长按不再出现「一按选一大段」；
    // 书架/书源/净化/朗读全部保持原样，点返回即回到这里，不影响任何既有功能。
    if (nativeReaderReady()) {
      const nRow = h(
        'button',
        { class: 'hf-row' },
        h('div', { class: 'hf-v' }, '本地渲染 · 长按选词更准'),
        h('span', {
          class: 'hf-go',
          html: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.5 5.5L16 12l-6.5 6.5"/></svg>',
        })
      );
      nRow.onclick = async () => {
        if (nRow.disabled) return;
        nRow.disabled = true;
        try {
          const name = (book.chapters[index] || {}).name || '';
          const html = await chapterHtmlAt(index);
          const paras = parasFromHtml(html, name);
          if (!paras.length) {
            toast('本章正文还没准备好，稍后再试');
            return;
          }
          let lastCi = index;
          // 恢复原生上次的章内位置：只有存档章节与当前章对得上才用（偏移是章内的）
          const pr = (await db.get('progress', id)) || null;
          const offset = pr && pr.index === index ? Math.max(0, Number(pr.nativeOffset) || 0) : 0;
          const ok = openNativeReader({
            bookId: id,
            bookTitle: book.name,
            index,
            total,
            titles: book.chapters.map((c) => (c && c.name) || ''),
            title: name,
            paras,
            offset,
            fontSize,
            lineHeight: lh,
            margin: marginPx,
            font: fontFam,
            weight: weightVal,
            theme,
            // 原生翻到章边界时按需取章：复用阅读页同一套净化 + 缓存链路
            onChapter: async (i) => {
              const nm = (book.chapters[i] || {}).name || '';
              return { title: nm, paras: parasFromHtml(await chapterHtmlAt(i), nm) };
            },
            // 进度已由原生直接落共享存储层（章节 index + 原生章内偏移 nativeOffset），
            // 这里不再写库：网页版的 page 是「第几页」，与原生字符偏移不同源，
            // 两边都写只会互相覆盖（曾会把原生偏移当成页码，恢复时跳到不存在的页）。
            // 只同步「最后读到哪一章」，退出时据此决定是否重渲染。
            onProgress: (i) => {
              lastCi = i;
            },
            // 退出原生：章节有变化才重渲染（否则用户回到的本来就是同一屏）。
            // 同步改 hash，否则地址还指着旧章，返回/前进会与画面错位。
            onBack: () => {
              if (lastCi === index) return;
              try {
                history.replaceState(null, '', `#/read/${encodeURIComponent(id)}/${lastCi}`);
              } catch (e) {
                /* 忽略：hash 写失败不影响阅读 */
              }
              renderReader(view, id, lastCi, view.classList.contains('chrome-hidden'));
            },
          });
          if (ok) sheet.close();
          else toast('当前版本不支持本地渲染');
        } finally {
          nRow.disabled = false;
        }
      };
      body.append(
        h('div', { class: 'sheet-row hf-sec' }, h('div', { class: 'row-label' }, h('span', {}, '渲染方式')), nRow)
      );
    }

    const sheet = openSheet('排版', body);
  };

  /* ---------- 朗读（TTS） ---------- */
  let ttsTimerMin = 0;
  let ttsTimerId = 0;
  let ttsTickId = 0;
  let ttsLeftSec = 0;

  /**
   * 当前可视位置的起始段落下标。
   * 分页：取当前页正文的第一段；滚动：取视口顶部第一条还有下半截露在外面的段落。
   * 点朗读/「从本页开始朗读」都从这里起，而不是永远从章首开始。
   *
   * 用 function 声明（而非 const 箭头）是为了提升：turnPage / applyChapter 这些更靠前的
   * 函数可能在渲染流程还没走到这里时就被调用，const 会撞 TDZ。
   */
  function ttsStartIndex(paras) {
    if (!paras.length) return 0;
    if (paged) {
      const page = pageEls[pageIdx];
      const first = page ? [...page.querySelectorAll('.pg-body > *')].find((el) => el.textContent.trim()) : null;
      const i = first ? paras.indexOf(first) : -1;
      return i >= 0 ? i : 0;
    }
    const top = stage.getBoundingClientRect().top + 8;
    for (let i = 0; i < paras.length; i++) {
      if (paras[i].getBoundingClientRect().bottom > top) return i;
    }
    return Math.max(0, paras.length - 1);
  }

  /** 当前阅读位置是否就在朗读落点上（分页比页号，滚动比段落是否还在视口内） */
  function ttsAtAnchor() {
    if (!ttsOn) return false;
    if (paged) return ttsAnchorPage >= 0 && ttsAnchorPage === pageIdx;
    const el = ttsAnchorEl;
    if (!el || !el.isConnected) return false;
    const r = el.getBoundingClientRect();
    const s = stage.getBoundingClientRect();
    return r.bottom > s.top + 4 && r.top < s.bottom - 4;
  }

  /**
   * 底部小横条：一个入口，三种状态。
   * - 没在朗读 → 🎧 从此页开始听
   * - 朗读中且当前页就是正在念的那页 → ⏸ 暂停播放（暂停后变成 ▶ 继续播放）
   * - 朗读中但翻到了别的页 → 🎧 从此页开始听（点了从这页重排，正是用户想要的）
   */
  function listenMode() {
    if (!ttsOn) return 'start';
    if (!ttsAtAnchor()) return 'start';
    return ttsIsPaused() ? 'resume' : 'pause';
  }
  function paintListenBar() {
    const b = $('#listen-bar');
    if (!b) return;
    // 没开听书就不占底部：这条只在朗读中出现（暂停/继续/翻走后的「从此页开始听」）
    if (!ttsOn) {
      b.hidden = true;
      b.style.bottom = ''; // 退出后回到 CSS 的贴底位置，下次开念再按页脚重新量
      return;
    }
    const mode = listenMode();
    // 文案就地算：这张表若放成模块级 const，paintListenBar 在渲染早期就被 showPage 调到，
    // 会撞上 TDZ（Cannot access before initialization），整个阅读页都建不起来
    const txt = mode === 'pause' ? '暂停播放' : mode === 'resume' ? '继续播放' : '从此页开始听';
    b.dataset.mode = mode;
    b.hidden = false;
    // 叉号只在朗读中给（点它整段退出听书）；没开朗读时横条本身就不显示
    const x = $('#listen-x');
    if (x) x.hidden = false;
    const t = b.querySelector('span');
    // 滚动/翻页会高频调用，文案没变就不写 DOM
    if (t && t.textContent !== txt) t.textContent = txt;
    placeListenBar();
  }

  /**
   * 分页模式：小横条落在页脚那一行的「中间格子」里，页码在左、时间在右，三者同一行。
   * （番茄就是这么排的：左右各占一角，中间留给听书。）
   * 页脚高度随字号/页边距变，按实际 rect 算位置，别写死；同时把横条宽度回写给页脚，
   * 让它把中间那一段撑出来，左右文字不会被压到。
   * 滚动模式没有页脚，回落到 CSS 里的贴底位置。
   */
  function placeListenBar() {
    const b = $('#listen-bar');
    const shell = $('#rd-shell');
    if (!b || !shell) return;
    b.style.bottom = '';
    shell.style.setProperty('--lb-w', '0px');
    if (!paged || !ttsOn) return;
    const pg = pageEls[pageIdx] || pageEls[0];
    const foot = pg && pg.querySelector('.pg-foot');
    const fr = foot && foot.getBoundingClientRect();
    if (!fr || fr.height < 4) return;
    const sr = shell.getBoundingClientRect();
    const bw = b.offsetWidth || 132;
    shell.style.setProperty('--lb-w', bw + 'px');
    // 让胶囊的垂直中心和页脚行的中心对齐 —— 视觉上就是同一行的中间那一格
    const footCenter = fr.top + fr.height / 2;
    const gap = Math.round(sr.bottom - footCenter - b.offsetHeight / 2);
    b.style.bottom = Math.max(4, gap) + 'px';
  }

  const paintTtsTimer = () => {
    const btn = $('#tts-timer');
    if (btn) btn.textContent = ttsTimerMin ? `定时 ${ttsTimerMin} 分` : '定时 关';
    const left = $('#tts-timer-left');
    if (left) {
      if (ttsTimerMin && ttsOn) {
        left.hidden = false;
        const m = Math.floor(ttsLeftSec / 60);
        const s = ttsLeftSec % 60;
        left.textContent = `${m}:${String(s).padStart(2, '0')}`;
      } else {
        left.hidden = true;
      }
    }
  };
  const clearTtsTimer = () => {
    clearTimeout(ttsTimerId);
    clearInterval(ttsTickId);
    ttsTimerId = 0;
    ttsTickId = 0;
    ttsTimerMin = 0;
    ttsLeftSec = 0;
    paintTtsTimer();
  };
  const setTtsTimer = (min) => {
    ttsTimerMin = min;
    ttsLeftSec = min * 60;
    clearTimeout(ttsTimerId);
    clearInterval(ttsTickId);
    if (!min) {
      paintTtsTimer();
      return;
    }
    ttsTimerId = setTimeout(() => {
      clearTtsTimer();
      exitTts();
      toast(`定时 ${min} 分钟到达，已停止朗读`);
    }, min * 60 * 1000);
    ttsTickId = setInterval(() => {
      ttsLeftSec = Math.max(0, ttsLeftSec - 1);
      paintTtsTimer();
      if (!ttsLeftSec) clearInterval(ttsTickId);
    }, 1000);
    paintTtsTimer();
  };
  // 到这里又过了取正文/排版的异步等待，可能已被路由接管：过期就停手
  if (stale()) return;
  const ttsTimerBtn = $('#tts-timer');
  if (ttsTimerBtn) ttsTimerBtn.onclick = () => {
    const i = TTS_TIMERS.indexOf(ttsTimerMin);
    setTtsTimer(TTS_TIMERS[(i + 1) % TTS_TIMERS.length]);
    if (ttsTimerMin) toast(`朗读 ${ttsTimerMin} 分钟后自动停止`);
  };

  /**
   * 朗读段落高亮：只包文字，不给整段 <p> 上底色。
   * 之前直接 el.classList.add('tts-hl')，背景从段落盒子左边开始 ——
   * text-indent:2em 那两个字宽的缩进也被涂成色块，看起来像「段首缺两个字」。
   * 现在把段落里的文本节点各自包一层行内 span，背景只覆盖字形本身。
   */
  const paintHi = (el) => {
    if (!el || !el.isConnected) return;
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => (n.nodeValue && n.nodeValue.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT),
    });
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const n of nodes) {
      const parent = n.parentNode;
      if (!parent) continue;
      const sp = document.createElement('span');
      sp.className = 'tts-hl';
      parent.insertBefore(sp, n);
      sp.append(n);
    }
  };
  const clearHi = () =>
    $$('#r-body .tts-hl').forEach((el) => {
      const p = el.parentNode;
      if (!p) return;
      while (el.firstChild) p.insertBefore(el.firstChild, el);
      p.removeChild(el);
      p.normalize(); // 相邻文本节点并回去，免得反复朗读把 DOM 切得粉碎
    });

  /**
   * 工具坞的实际高度写进 --dock-h。
   * 朗读控制条要叠在工具坞上方（番茄也是胶囊压在目录/设置那一排上面），
   * 不测高度就只能写死，菜单行数一变就会互相压住。
   */
  function paintDockHeight() {
    const shell = $('#rd-shell');
    const dock = $('#rd-dock');
    if (!shell || !dock || !document.contains(shell)) return;
    shell.style.setProperty('--dock-h', Math.round(dock.offsetHeight) + 'px');
  }

  const exitTts = (opts = {}) => {
    const wasOn = ttsOn;
    ttsStop(true);
    ttsOn = false;
    clearHi();
    clearTtsTimer();
    const bar = $('#tts-bar');
    if (bar) bar.hidden = true;
    const btn = $('#r-tts');
    if (btn) btn.classList.remove('on');
    $('#rd-shell')?.classList.remove('tts-on');
    paintListenBar();
    // 横条占的是页脚中间那格，版心没变 → 不用重排（重排会让当前页内容重新断行）。
    // 只有页脚被关掉、横条确实要吃一行时才需要；跨章时正文马上整个换掉，传 restack:false 跳过。
    if (wasOn && paged && opts.restack !== false && !showFoot) restack();
  };

  const onSentence = (i, el) => {
    clearHi();
    if (el && el.classList) {
      paintHi(el);
      ttsAnchorEl = el; // 小横条据此判断「当前页是不是正在念的这页」
      if (paged) {
        // 分页模式：高亮句跨页时容器跟随滑动
        const pg = el.closest('.page');
        if (pg) {
          const pi = pageEls.indexOf(pg);
          if (pi >= 0) {
            ttsAnchorPage = pi;
            if (pi !== pageIdx) showPage(pi);
          }
        }
      } else {
        el.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }
    }
    paintListenBar();
    const now = $('#tts-now');
    if (now) now.textContent = i == null ? '' : `第 ${i + 1} / ${ttsCount()} 句`;
  };

  /** 原生桥是否支持跳系统语音设置（浏览器里没有这一项） */
  const hasTtsBridge = () =>
    typeof window !== 'undefined' &&
    !!window.ModouBridge &&
    typeof window.ModouBridge.ttsOpenSettings === 'function';

  /** 朗读不可用：说清到底卡在哪一步，并给一条去系统设置的直达路 */
  const ttsUnavailable = (state) => {
    const engine = ttsEngineName();
    const msg =
      {
        no_engine:
          '系统里没有可用的语音合成引擎，或者本应用连不上它。在系统「文字转语音」设置里装一个引擎（如「文字转语音」/Google 语音服务）后再回来。',
        no_data: '找到了语音引擎，但它没有中文语音数据。在系统「文字转语音」设置里下载中文语音包后再回来。',
        no_voice: '当前浏览器 / WebView 没有可用的语音。',
        unsupported: '当前环境不支持语音合成。',
        engine_lost: '语音引擎在朗读过程中断开了，请重试。',
        speak_failed: '语音引擎没有响应，请重试，或在系统设置里换一个语音引擎。',
      }[state] || '语音引擎暂时不可用。';
    const wrap = h('div', {});
    const sheet = openSheet('朗读不可用', wrap);
    wrap.append(
      h('div', { style: 'padding:2px 4px 8px;line-height:1.75;font-size:14px;color:var(--text2)' }, msg),
    );
    if (engine) {
      wrap.append(
        h('div', { style: 'padding:0 4px 6px;font-size:12px;color:var(--text3)' }, '当前引擎：' + engine),
      );
    }
    if (hasTtsBridge()) {
      wrap.append(
        h(
          'button',
          {
            class: 'btn primary block',
            type: 'button',
            onclick: () => {
              sheet.close();
              if (!ttsOpenSettings()) toast('没能打开系统语音设置');
            },
          },
          '打开系统语音设置',
        ),
      );
    }
    wrap.append(
      h(
        'button',
        {
          class: 'btn ghost block',
          type: 'button',
          onclick: () => {
            sheet.close();
            startTts();
          },
        },
        '重试',
      ),
    );
  };

  /**
   * 启动朗读（已在朗读时调用 = 「从本页开始朗读」，队列重排、起点换成当前页）。
   * 起点不再固定为章首：分页取当前页第一段，滚动取视口顶部那一段。
   */
  const startTts = async () => {
    const wasOn = ttsOn;
    try {
      if (!ttsSupported()) return toast('当前环境不支持语音合成（需现代浏览器/WebView）');
      // 冷启动时引擎可能还在绑定：等一会儿再判定，别一上来就报「没有语音」
      if (!ttsHasVoice()) {
        const tip = setTimeout(() => toast('正在准备系统语音…'), 400);
        const r = await ttsPrepare();
        clearTimeout(tip);
        if (stale()) return;
        if (!r.ok) return ttsUnavailable(r.state);
      }
      // 顺序很重要：先把控制条亮起来并让正文让位（分页模式会重排），再抓段落节点。
      // 反过来的话，重排会把已经抓到的节点全部替换掉 —— 高亮和 scrollIntoView 都作用在废弃节点上，
      // 表现就是「念了但正文不跟、也看不到高亮」。
      ttsOn = true;
      $('#r-tts').classList.add('on');
      const tbar = $('#tts-bar');
      if (tbar) tbar.hidden = false;
      $('#rd-shell')?.classList.add('tts-on');
      // 开念后回到沉浸：底部只留一条「暂停播放」，想调节再点屏幕唤出工具坞
      hideChrome();
      paintListenBar();
      // 横条浮在页脚上方，版心要让出一行：只有「从没开」时才重排，
      // 已在朗读中点小横条重读当前页则保持版面不动。
      // 同理：开了页脚就不重排，版心没有任何变化，何必让当前页重新断行
      if (paged && !wasOn && !showFoot) restack();
      $('#tts-rate').textContent = RATES[rateIdx].toFixed(1) + 'x';
      paintTtsTimer();
      paintDockHeight();
      // 分页模式要抓「段落」而不是「整页」：.page 直接子级是 pg-head / pg-body / pg-foot，
      // 抓 .page > * 会把章名、整页正文、页码各当成一段（整页正文还会合并成一句，断句全乱）。
      // 直接从 pagesEl 取：跨章命中预分页时，页容器在隐藏槽位里，按 #r-body > .pages 选会选不到。
      const paras = paged
        ? (pagesEl ? [...pagesEl.querySelectorAll('.page > .pg-body > *')] : []).filter(
            // 章题会被分页器塞进第一页的 pg-body，不能当成正文读出来
            (el) => !(el.classList && el.classList.contains('chapter-title')) && el.textContent.trim(),
          )
        : $$('#r-body .content > *').filter((el) => el.textContent.trim());
      if (!paras.length) {
        exitTts();
        return toast('正文为空，无可朗读内容');
      }
      const startIndex = ttsStartIndex(paras);
      const ok = ttsStart(paras, {
        rate: RATES[rateIdx],
        startIndex,
        onSentence,
        onEnd: () => exitTts(),
        onError: (reason) => {
          exitTts();
          toast(reason === 'speak_failed' ? '语音引擎没有响应，已停止朗读' : '语音引擎断开，已停止朗读');
        },
      });
      if (!ok) {
        exitTts();
        if (!ttsHasVoice()) ttsUnavailable(ttsState());
        else toast('这段正文没有可朗读的文字');
      }
    } catch (e) {
      exitTts();
      toast('朗读启动失败：' + (e && e.message ? e.message : e));
    }
  };

  /**
   * 听书面板：语速、定时、上一句/下一句都集中在这里。
   * 工具坞的「听书」按钮点一下就直接开念；已经在念时再点一次才开这个面板。
   */
  const openTtsPanel = () => {
    if (!ttsSupported()) return toast('当前环境不支持语音合成（需现代浏览器/WebView）');
    const wrap = h('div', {});
    const sheet = openSheet('听书', wrap);
    const build = () => {
      wrap.innerHTML = '';
      const running = ttsOn;
      const paused = running && ttsIsPaused();
      const nowText = ($('#tts-now')?.textContent || '').trim();
      wrap.append(
        h(
          'div',
          { class: 'tts-state' },
          running ? (paused ? '已暂停' : '正在朗读') + (nowText ? '　·　' + nowText : '') : '将从本页开始朗读',
        ),
      );
      const main = h(
        'button',
        { class: 'btn primary block', type: 'button' },
        running ? (paused ? '继续朗读' : '暂停朗读') : '开始朗读',
      );
      main.onclick = () => {
        if (!running) {
          sheet.close();
          startTts();
          return;
        }
        if (paused) ttsResume();
        else ttsPause();
        syncTtsToggleText();
        paintListenBar();
        setTimeout(build, 80);
      };
      wrap.append(main);
      if (running) {
        const row = h('div', { class: 'tts-panel-row' });
        const prev = h('button', { class: 'btn ghost', type: 'button' }, '上一句');
        prev.onclick = () => ttsPrev();
        const next = h('button', { class: 'btn ghost', type: 'button' }, '下一句');
        next.onclick = () => ttsNext();
        row.append(prev, next);
        wrap.append(row);
      }
      const rs = h('div', { class: 'seg' });
      RATES.forEach((r, i) => {
        const b = h('button', { class: i === rateIdx ? 'on' : '' }, r.toFixed(1) + 'x');
        b.onclick = () => {
          rateIdx = i;
          ttsSetRate(RATES[i]);
          const rb = $('#tts-rate');
          if (rb) rb.textContent = RATES[i].toFixed(1) + 'x';
          rs.querySelectorAll('button').forEach((x) => x.classList.remove('on'));
          b.classList.add('on');
        };
        rs.append(b);
      });
      wrap.append(h('div', { class: 'sheet-row' }, h('div', { class: 'row-label' }, h('span', {}, '语速')), rs));
      const ts = h('div', { class: 'seg' });
      TTS_TIMERS.forEach((m) => {
        const b = h('button', { class: m === ttsTimerMin ? 'on' : '' }, m ? `${m}分` : '关');
        b.onclick = () => {
          setTtsTimer(m);
          ts.querySelectorAll('button').forEach((x) => x.classList.remove('on'));
          b.classList.add('on');
        };
        ts.append(b);
      });
      wrap.append(
        h('div', { class: 'sheet-row' }, h('div', { class: 'row-label' }, h('span', {}, '定时停止')), ts),
      );
      if (running) {
        const exit = h('button', { class: 'btn ghost block', type: 'button' }, '退出朗读');
        exit.onclick = () => {
          sheet.close();
          exitTts();
        };
        wrap.append(exit);
      }
    };
    build();
  };

  // 点「听书」直接开念（番茄也是一点就响），要调节再点一次打开面板
  MENU_ACTIONS.tts = () => (ttsOn ? openTtsPanel() : startTts());
  /** 控制条里的暂停按钮跟随朗读状态（小横条暂停/继续时也要同步） */
  const syncTtsToggleText = () => {
    const tg = $('#tts-toggle');
    if (!tg) return;
    const paused = ttsIsPaused();
    tg.classList.toggle('paused', paused);
    const lbl = tg.querySelector('#tts-toggle-lbl');
    if (lbl) lbl.textContent = paused ? '继续' : '暂停';
  };
  // 底部小横条：按当前状态分派 —— 开始听 / 暂停 / 继续
  const listenBtn = $('#listen-bar');
  if (listenBtn) {
    listenBtn.onclick = () => {
      if (!ttsSupported()) return toast('当前环境不支持语音合成（需现代浏览器/WebView）');
      const mode = listenMode();
      if (mode === 'pause') ttsPause();
      else if (mode === 'resume') ttsResume();
      else return startTts();
      syncTtsToggleText();
      paintListenBar();
    };
  }
  // 横条右侧的叉：整段退出听书（不能冒泡到横条本体，否则就变成「暂停」了）
  const listenX = $('#listen-x');
  if (listenX) {
    listenX.onclick = (e) => {
      e.stopPropagation();
      exitTts();
    };
  }
  $('#tts-toggle').onclick = () => {
    if (ttsIsPaused()) ttsResume();
    else ttsPause();
    syncTtsToggleText();
    paintListenBar();
  };
  $('#tts-prev').onclick = () => ttsPrev();
  $('#tts-next').onclick = () => ttsNext();
  $('#tts-rate').onclick = () => {
    rateIdx = (rateIdx + 1) % RATES.length;
    const r = RATES[rateIdx];
    ttsSetRate(r);
    $('#tts-rate').textContent = r.toFixed(1) + 'x';
    toast(`语速 ${r.toFixed(1)}x（下一句生效）`);
  };
  $('#tts-exit').onclick = () => exitTts();

  /* ---------- 翻章（工具坞固定「上一章/下一章」，翻页靠点按/拖拽/键盘） ---------- */
  MENU_ACTIONS.prev = () => go(-1);
  MENU_ACTIONS.next = () => go(1);

  /* ---------- 更多（书籍详情 / 重载本章 / 回本章开头 / 图标排布） ---------- */
  MENU_ACTIONS.more = () => {
    const wrap = h('div', { class: 'pick-list' });
    const sheet = openSheet(book.name || '书籍', wrap);
    const item = (label, fn) =>
      h('button', { class: 'pick-item', onclick: () => { sheet.close(); fn(); } }, label);
    wrap.append(
      item('书籍详情', () => { location.hash = '#/book/' + encodeURIComponent(id); }),
      // 试读书给「加入书架」入口（搜书点开的新书默认不入书架）
      ...(book.preview
        ? [
            item('加入书架', async () => {
              try {
                const saved = await promotePreview();
                if (saved) toast('已加入书架');
                renderReader(view, id, index, view.classList.contains('chrome-hidden'));
              } catch (e) {
                toast('加入失败：' + (e.message || e));
              }
            }),
          ]
        : []),
      item('重新加载本章', async () => {
        if (book.preview) previewChapterPut(index, null);
        else {
          try {
            await db.del('chapters', id + '||' + index);
          } catch (e) {
            /* 忽略 */
          }
        }
        await paint();
        toast('已重新加载本章');
      }),
      item('替换净化', () => MENU_ACTIONS.purify()),
      item('图标排布', openMenuLayout),
      item('回到本章开头', () => { if (paged) showPage(0); else stage.scrollTo({ top: 0 }); })
    );
  };

  /* ---------- 替换净化（底部菜单「净化」；与设置页共用同一份规则） ---------- */
  MENU_ACTIONS.purify = () => openReplaceSheet({ onApplied: () => paint() });

  /* ---------- 底部图标排布（更多 → 图标排布）：两行顺序 / 换行 / 隐藏 ---------- */
  const openMenuLayout = () => {
    const cfg = { r1: [...menuCfg.r1], r2: [...menuCfg.r2], hidden: [...menuCfg.hidden] };
    const body = h('div', { class: 'form' });
    const iconHtml = (key) => {
      const it = MENU_ITEMS[key];
      return it.text ? `<span class="aa-t">${it.text}</span>` : it.icon;
    };
    const save = async () => {
      menuCfg = normMenuCfg(cfg);
      await settings.set('readerMenuLayout', menuCfg);
      cfg.r1 = [...menuCfg.r1];
      cfg.r2 = [...menuCfg.r2];
      cfg.hidden = [...menuCfg.hidden];
      paintMenu();
      paintEditor();
    };
    const rowItem = (key, row, i) => {
      const rowEl = h('div', { class: 'ml-row' }, h('span', { class: 'ml-ic', html: iconHtml(key) }), h('span', { class: 'ml-name' }, MENU_ITEMS[key].label));
      const btns = h('div', { class: 'ml-btns' });
      const move = async (d) => {
        const j = i + d;
        if (j < 0 || j >= cfg[row].length) return;
        const a = cfg[row];
        [a[i], a[j]] = [a[j], a[i]];
        await save();
      };
      btns.append(
        h('button', { class: 'mini', title: '上移', onclick: () => move(-1) }, '↑'),
        h('button', { class: 'mini', title: '下移', onclick: () => move(1) }, '↓'),
        h('button', { class: 'mini', title: '换到另一行', html: IC.swap, onclick: async () => {
          const other = row === 'r1' ? 'r2' : 'r1';
          cfg[row].splice(i, 1);
          cfg[other].push(key);
          await save();
        } }),
        h('button', { class: 'mini', title: '隐藏', onclick: async () => {
          cfg[row].splice(i, 1);
          cfg.hidden.push(key);
          await save();
        } }, '隐')
      );
      rowEl.append(btns);
      return rowEl;
    };
    const paintEditor = () => {
      body.innerHTML = '';
      const sec = (t) => h('div', { class: 'ml-sec' }, t);
      body.append(sec('第一行'));
      if (!cfg.r1.length) body.append(h('p', { class: 'muted small' }, '（空）'));
      cfg.r1.forEach((k, i) => body.append(rowItem(k, 'r1', i)));
      body.append(sec('第二行'));
      if (!cfg.r2.length) body.append(h('p', { class: 'muted small' }, '（空）'));
      cfg.r2.forEach((k, i) => body.append(rowItem(k, 'r2', i)));
      body.append(sec('已隐藏'));
      if (!cfg.hidden.length) body.append(h('p', { class: 'muted small' }, '（无）'));
      cfg.hidden.forEach((k) => {
        const rowEl = h('div', { class: 'ml-row' }, h('span', { class: 'ml-ic', html: iconHtml(k) }), h('span', { class: 'ml-name' }, MENU_ITEMS[k].label));
        rowEl.append(
          h('button', { class: 'mini', onclick: async () => {
            cfg.hidden = cfg.hidden.filter((x) => x !== k);
            cfg.r1.push(k);
            await save();
          } }, '显示')
        );
        body.append(rowEl);
      });
      body.append(
        h('div', { class: 'btn-row', style: 'margin-top:14px' }, h('button', { class: 'btn ghost', onclick: async () => {
          cfg.r1 = [...MENU_DEFAULT.r1];
          cfg.r2 = [...MENU_DEFAULT.r2];
          cfg.hidden = [];
          await save();
        } }, '恢复默认排布'))
      );
    };
    paintEditor();
    openSheet('图标排布', body);
  };

  /* ---------- 键盘（桌面） ---------- */
  const onKey = (e) => {
    const t = e.target;
    if (t && t.closest && t.closest('input,textarea,select')) return;
    if (e.key === 'Escape') {
      if (drawer.isOpen()) drawer.close();
      else if (!$('#find-bar').hidden) closeFind();
      return;
    }
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      if (paged) turnPage(-1);
      else go(-1);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      if (paged) turnPage(1);
      else go(1);
    }
  };
  document.addEventListener('keydown', onKey);

  // 视口变化（横竖屏/窗口缩放）后重分页；朗读中控制条可能换行，高度变了要重新让位
  const onResize = () => {
    requestAnimationFrame(paintDockHeight);
    scheduleRepage();
  };
  window.addEventListener('resize', onResize);

  // 路由切换时清理：键盘监听 + 朗读 + 高亮 + 常亮 + 自动阅读 + 定时 + 统计 + 目录抽屉 + 亮度遮罩
  window.__readerCleanup = () => {
    document.removeEventListener('keydown', onKey);
    window.removeEventListener('resize', onResize);
    document.removeEventListener('visibilitychange', onVisible);
    window.removeEventListener('popstate', onFindPagePop);
    clearTimeout(selHoldT);
    unsubTheme(); // 注销全局主题订阅
    // 预分页/预热的定时器与槽位：离开阅读页必须收干净，否则会在别的页面继续跑
    switchGen++; // 作废在途的异步切章
    clearTimeout(primeTimer);
    clearTimeout(switchWarmTimer);
    if (typeof dropStalePages === 'function') {
      try {
        pageCacheSig = '';
        dropStalePages();
      } catch (e) {
        /* 忽略 */
      }
    }
    unReaderBack(); // 注销阅读页浮层返回处理器
    selBar.remove();
    selLayer.remove();
    delete window.__sel;
    stopAuto(true);
    clearTtsTimer();
    ttsStop(true);
    clearHi();
    clearInterval(statTimer);
    clearInterval(hfTimer);
    hfTimer = 0;
    releaseWake();
    $$('.toc-mask, .toc-drawer').forEach((e) => e.remove());
    const dim = $('#dim-layer'); // 亮度遮罩挂在 body 上，离开阅读页必须收起，否则其他页面全黑
    if (dim) dim.style.opacity = '0';
  };

  // 沉浸：首次进入默认隐藏顶栏/工具坞，正文满屏；单击中部唤出（浮层遮字）
  if (startHidden) view.classList.add('chrome-hidden');
  else view.classList.remove('chrome-hidden');
}
