/**
 * 应用骨架：启动、路由、全局状态、书库（shelf + 试读暂存）
 *
 * 书库设计：搜书/发现点开的书**不默认进书架**，先落「试读暂存」（sessionStorage），
 * 试读、排版、书签、进度都可正常使用；用户点「加入书架」才把 record + 章节缓存
 * 固化到 IndexedDB。换浏览器标签/应用进程重启后暂存自动失效（书架才是长期书库）。
 */
import { db, settings, initStore, storeKind } from '../db.js';
import { BookSource, applyReplaceString } from '../engine/source.js';
import { $, $$, toast, confirmBox, closeAllOverlays } from './dom.js';
import { formatContent } from '../lib/fmt.js';
import { BUILTIN_RULES, dedupChapterTitle, dedupTitleFormatted } from '../lib/builtin-purify.js';

export const state = {
  sources: [], // BookSource[]
  books: [], // 书架（IndexedDB books 表）
  replaceRules: [], // [{pattern, replacement, isRegex, scope:'content'|'title', enabled}]
};

/* ---------- 全局主题（light / dark / auto）：整 App 配色统一 ----------
 * 阅读页选「夜间」会临时把整 App 切到 dark；选回纸白/米黄/青石则恢复用户设定
 * （auto 时跟随系统深色）。styles.css 的 [data-theme="dark"] 变量集据此整站生效。 */
let appThemeMode = 'light';
// 系统深色状态：null = 未知（退回 matchMedia）；true/false = 原生壳告知的权威值。
// WebView 里的 prefers-color-scheme 并不可靠（有的机型恒为 light、系统切换时也不刷新），
// 所以「跟随系统」以原生的 Configuration.uiMode 为准。
let systemDarkHint = null;
const darkMq =
  typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : { matches: false, addEventListener() {} };

/** 先问原生要系统深色状态；没有桥（浏览器调试）就退回 matchMedia */
const refreshSystemDark = () => {
  try {
    if (window.ModouBridge && typeof window.ModouBridge.isSystemDark === 'function') {
      systemDarkHint = !!window.ModouBridge.isSystemDark();
      return;
    }
  } catch (e) {
    /* 桥异常：退回 matchMedia */
  }
  systemDarkHint = null;
};

export const docThemeNow = () => {
  if (appThemeMode === 'dark') return 'dark';
  if (appThemeMode === 'auto') {
    const sysDark = systemDarkHint === null ? darkMq.matches : systemDarkHint;
    return sysDark ? 'dark' : 'light';
  }
  return 'light';
};
let themeChangeCb = null;
/** 订阅全局主题变化（阅读页用来让正文底色跟随设置页的深浅）；返回注销函数 */
export const onThemeChange = (cb) => {
  themeChangeCb = cb;
  return () => {
    if (themeChangeCb === cb) themeChangeCb = null;
  };
};
const paintDocTheme = () => {
  document.documentElement.dataset.theme = docThemeNow();
  if (themeChangeCb) {
    try {
      themeChangeCb();
    } catch (e) {
      /* 忽略订阅方异常 */
    }
  }
};
// 原生在系统切换深浅（onConfigurationChanged）/ 页面加载完成时调用，实时同步。
// 用 typeof 守卫：引擎单测在 Node/JSDOM 里 import 本模块，没有 window 全局。
const onSystemTheme = (dark) => {
  systemDarkHint = !!dark;
  if (appThemeMode === 'auto') paintDocTheme();
};
if (typeof window !== 'undefined') window.__modouSystemTheme = onSystemTheme;
/** 动效三档（对齐 legado-with-MD3 的动画等级设计）：
 *  0 关闭 / 1 简约（仅淡入淡出）/ 2 MD3 标准（emphasized 曲线 + 抽屉上滑 + 微反馈）。
 *  值存在设置表，WebView 与原生阅读页各读一份。 */
export const applyAnimGrade = (grade) => {
  const g = [0, 1, 2].includes(Number(grade)) ? Number(grade) : 2;
  if (typeof document !== 'undefined') document.body.dataset.anim = ['off', 'simple', 'md3'][g];
  return g;
};
export const saveAnimGrade = (grade) => {
  applyAnimGrade(grade);
  return settings.set('uiAnimGrade', grade);
};
export async function initAppTheme() {
  applyAnimGrade(await settings.get('uiAnimGrade', 2));
  const m = await settings.get('appTheme', 'light');
  appThemeMode = m === 'dark' || m === 'auto' ? m : 'light';
  refreshSystemDark();
  mirrorTheme(appThemeMode); // 补齐副本（首次运行 / 旧版本升级时 localStorage 里还没有）
  syncThemeToNative(appThemeMode); // 原生也补一份：否则升级后第一次启动开屏仍是浅色
  paintDocTheme();
  if (darkMq.addEventListener) {
    darkMq.addEventListener('change', () => {
      if (appThemeMode !== 'auto') return;
      refreshSystemDark(); // 原生可用时以原生为准；否则用 matchMedia
      paintDocTheme();
    });
  }
}
/** 阅读页「夜间」按钮调用：forceDark=true 全局切暗；false 恢复成用户设定 */
export const setGlobalDark = (force) => {
  document.documentElement.dataset.theme = force ? 'dark' : docThemeNow();
};
/** 设置页切换全局主题 */
export const saveAppTheme = (mode) => {
  appThemeMode = mode === 'dark' || mode === 'auto' ? mode : 'light';
  if (appThemeMode === 'auto') refreshSystemDark(); // 选「跟随系统」立即对齐当前系统深浅
  settings.set('appTheme', appThemeMode);
  mirrorTheme(appThemeMode); // 同步镜像到 localStorage：下次开场首帧就是暗的
  syncThemeToNative(appThemeMode); // 通知原生存一份：下次启动连开屏底色一起变暗
  paintDocTheme();
};
/**
 * 原生设置页改主题后调用：两边必须落在同一份值上。
 * 原生写 SharedPreferences（决定下次冷启动的开屏底色），这里写设置表并立即重绘，
 * 否则会出现「原生是深色、回到网页版还是浅色」的对不上。
 */
if (typeof window !== 'undefined') window.__modouSaveAppTheme = saveAppTheme;
/** 把主题选择告诉原生（仅持久化项；阅读页临时夜间不走这里） */
const syncThemeToNative = (mode) => {
  try {
    if (window.ModouBridge && window.ModouBridge.setAppTheme) window.ModouBridge.setAppTheme(mode);
  } catch (e) {
    /* 非 App 环境（浏览器调试）没有桥，忽略 */
  }
};
/**
 * 把主题选择镜像到 localStorage。
 * 设置存在 IndexedDB（异步读），开场首帧等不到它返回，必须另存一份同步可读的副本。
 */
const mirrorTheme = (mode) => {
  try {
    localStorage.setItem('modou.theme', mode);
  } catch (e) {
    /* 隐私模式下不可用：只是开场会闪一下，不影响功能 */
  }
};

/* ---------- 外部文件打开（「用其他应用打开 → 墨读」） ----------
 * 原生壳把文件内容（书籍为 Base64 原始字节 / 书源为文本）通过桥回调推过来。
 * 回调可能在页面尚未 boot 完成时就被触发，所以先缓存，boot 完成后统一处理。 */
let pendingBookFile = null;
let pendingSourceJson = null;
let fileIntentReady = false;

async function importBookFileBytes(b64, name) {
  try {
    const { importLocalFiles } = await import('./local-import.js');
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    // 交给本地导入：TXT 编码探测（UTF-8/GBK）与 EPUB 解析都走同一条路
    const file = new File([bytes], name || '导入.txt');
    const r = await importLocalFiles([file]);
    if (r.added || r.updated) {
      toast(`已导入《${name}》`);
      // 书架已原生化：能叫原生就叫原生，网页端不再有书架页
      if (window.ModouBridge && window.ModouBridge.openNativeShelf) {
        window.ModouBridge.openNativeShelf();
      } else {
        location.hash = '#/search';
      }
    } else if (r.failed.length) {
      toast(`导入失败：${r.failed[0].name}（${r.failed[0].why}）`);
    }
  } catch (e) {
    toast('打开书籍失败：' + (e && e.message ? e.message : e));
  }
}

async function importSourceJsonText(text, name) {
  try {
    const { parseSourcesJson, importSources } = await import('./sources.js');
    const list = parseSourcesJson(text);
    if (!list || !list.length) {
      toast('没有解析到书源');
      return;
    }
    await importSources(list);
    location.hash = '#/sources';
  } catch (e) {
    toast('导入书源失败：' + (e && e.message ? e.message : e));
  }
}

const flushFileIntent = () => {
  if (!fileIntentReady) return;
  if (pendingBookFile) {
    const p = pendingBookFile;
    pendingBookFile = null;
    importBookFileBytes(p.b64, p.name);
  }
  if (pendingSourceJson) {
    const p = pendingSourceJson;
    pendingSourceJson = null;
    importSourceJsonText(p.text, p.name);
  }
};

if (typeof window !== 'undefined') {
  window.__modouBookFile = (b64, name) => {
    pendingBookFile = { b64, name };
    flushFileIntent();
  };
  window.__modouFileResult = (text, name) => {
    pendingSourceJson = { text, name };
    flushFileIntent();
  };
}

export async function boot() {
  // 系统栏避让先挂上：原生可能在任意时刻推 insets，别等读库
  installSystemInsets();
  try {
    // 存储层：App 内若上次已迁到原生 SQLite，这里切过去（微秒级，不搬数据）。
    // 首次迁移是后台任务，绝不在这里 await——它挡在首屏前面就是一片空白。
    await initStore();
    // 只有仍在 IndexedDB 通道（桌面浏览器 / 迁移未完成）才需要开库；
    // 已切到 SQLite 时再 open 只是白建一个空库。
    if (storeKind() === 'indexeddb') await db.open();
    // 三份数据互不依赖，并行读：串行等于把 4 次 IndexedDB 往返排队，冷启动白等
    await Promise.all([
      reloadSources(),
      reloadBooks(),
      reloadReplaceRules(),
      initAppTheme(), // 同样是一次 settings 读；与上面并行，且必须在 route 前完成避免首屏闪主题
    ]);
  } catch (e) {
    // 读库任何一步失败都不能把用户留在白屏：记日志后照常渲染，
    // 书架可能是空的，但界面在、可操作、可重试。
    console.error('[boot] 初始化失败，降级继续', e);
    try {
      await db.open();
    } catch (_) {
      /* 连回退都失败：仍然要渲染，下面 route 会兜住 */
    }
  }
  window.addEventListener('hashchange', route);
  fileIntentReady = true;
  flushFileIntent(); // 处理冷启动时通过「用其他应用打开」进来的文件
  try {
    route();
  } catch (e) {
    console.error('[boot] 渲染失败', e);
    showBootError(e);
  }
}

/**
 * 启动兜底：渲染彻底失败时给一块可见的错误卡片 + 重试入口。
 * 白屏最难排查——用户只看到「什么都没有」，连哪里出错都无从反馈。
 */
function showBootError(e) {
  try {
    const host = document.getElementById('view') || document.body;
    if (!host || host.querySelector('.boot-error')) return;
    const el = document.createElement('div');
    el.className = 'boot-error';
    el.style.cssText =
      'position:fixed;inset:0;z-index:99999;display:flex;flex-direction:column;' +
      'align-items:center;justify-content:center;gap:14px;padding:28px;text-align:center;' +
      'background:var(--bg,#F2EEE3);color:var(--fg,#333)';
    const msg = (e && (e.message || String(e))) || '未知错误';
    el.innerHTML =
      '<div style="font-size:17px;font-weight:600">启动出错</div>' +
      '<div style="font-size:13px;opacity:.75;max-width:320px;line-height:1.6">' +
      '界面没能渲染出来。可以先重试；若反复出现，请把下面这行反馈给开发者。</div>' +
      '<div style="font-size:12px;opacity:.6;max-width:320px;word-break:break-all"></div>';
    el.lastElementChild.textContent = msg;
    const btn = document.createElement('button');
    btn.textContent = '重试';
    btn.style.cssText =
      'padding:10px 26px;border-radius:999px;border:0;background:#D96C47;color:#fff;font-size:15px';
    btn.onclick = () => location.reload();
    el.appendChild(btn);
    host.appendChild(el);
  } catch (_) {
    /* 兜底的兜底：实在不行也不能抛出去 */
  }
}

/* ---------- 系统栏避让（edge-to-edge） ----------
 * 原生壳把状态栏/导航栏真实高度通过 window.__modouInsets 推进来，
 * 写成 --safe-top/--safe-bottom；CSS 里 var(--safe-*, env(safe-area-inset-*)) 取用。
 * 页面自身绘制系统栏区域（同色），从结构上消除「小白条附近色差」。 */
function installSystemInsets() {
  window.__modouInsets = (top, bottom) => {
    const r = document.documentElement.style;
    r.setProperty('--safe-top', Math.max(0, top | 0) + 'px');
    r.setProperty('--safe-bottom', Math.max(0, bottom | 0) + 'px');
  };
}

export async function reloadSources() {
  const rows = await db.all('sources');
  state.sources = rows
    .map((r) => BookSource.fromJson(r))
    .sort((a, b) => String(a.bookSourceName || '').localeCompare(String(b.bookSourceName || ''), 'zh'));
}

export async function reloadBooks() {
  // 只取轻量记录：完整记录里每本书都带几千项章节表，几十本一起跨桥搬进
  // JS 堆就是启动卡顿的主因。要章节的地方（详情/阅读）用 findBook 按 id 补。
  state.books = await db.allBooksLite();
}

export async function reloadReplaceRules() {
  state.replaceRules = (await settings.get('replaceRules', [])) || [];
}

export const enabledSources = () => state.sources.filter((s) => s.enabled);

/**
 * 书籍规范 id：源地址 + 书地址（原始值，不入库编码）。
 * 约定：hash / DOM 属性里出现 id 时必须 encodeURIComponent，路由解一次码后交还视图，
 * 因此 findBook/getProgress/saveProgress 全程用原始 id。
 */
export function bookId(b) {
  return b.sourceUrl + '||' + b.bookUrl;
}

/** 应用净化到正文：先内置规则库（随版本更新），再用户自定义规则（enabled === false 的跳过） */
export function purifyContent(html, chapterName) {
  let out = String(html || '');
  // ① 内置净化：正文开头重复章节标题（需要标题上下文，代码级处理）
  out = dedupChapterTitle(out, chapterName);
  // ② 内置通用正则规则（lib/builtin-purify.js，遇到新的通用杂质往库里加即可）
  for (const r of BUILTIN_RULES) {
    if (!r || !r.pattern || r.enabled === false) continue;
    if ((r.scope || 'content') !== 'content') continue;
    try {
      out = r.isRegex ? out.replace(new RegExp(r.pattern, 'g'), r.replacement || '') : out.split(r.pattern).join(r.replacement || '');
    } catch (e) {
      /* 跳过坏规则 */
    }
  }
  // ③ 用户自定义规则
  for (const r of state.replaceRules) {
    if (!r || !r.pattern || r.enabled === false) continue;
    if ((r.scope || 'content') !== 'content') continue;
    try {
      out = r.isRegex ? out.replace(new RegExp(r.pattern, 'g'), r.replacement || '') : out.split(r.pattern).join(r.replacement || '');
    } catch (e) {
      /* 跳过坏规则 */
    }
  }
  return out;
}

/** 应用 scope='title' 的规则到章节标题（阅读页章题/进度标签展示用） */
export function purifyTitle(title) {
  let out = String(title || '');
  for (const r of state.replaceRules) {
    if (!r || !r.pattern || r.enabled === false || r.scope !== 'title') continue;
    try {
      out = r.isRegex ? out.replace(new RegExp(r.pattern, 'g'), r.replacement || '') : out.split(r.pattern).join(r.replacement || '');
    } catch (e) {
      /* 跳过坏规则 */
    }
  }
  return out;
}

/**
 * 正文最终 HTML：净化规则 + 默认排版。
 * 默认排版对所有书生效（本地 TXT/EPUB、搜书暂存、书架网络书同一套规则）：
 * 自动分段（短行合一 / 超长行按句拆段 / 纯文本行包 p）、去零宽与首行空白、
 * 强制 <p> 段落（缩进与行距由 .content p / .page p 的排版设置统一控制）。
 */
export function renderChapterHtml(html, chapterName) {
  // 两道去重：raw 阶段处理纯文本/独立段落形态；排版后再兜底一次——
  // formatContent 已把 <br>/<div>/行内标签全部归一，此时按首段文本比对最可靠
  return dedupTitleFormatted(formatContent(purifyContent(html, chapterName)), chapterName);
}

/** 保存书籍（含目录）到书架 */
export async function saveBook(book, chapters) {
  const rec = {
    id: bookId(book),
    sourceUrl: book.sourceUrl,
    sourceName: book.sourceName,
    name: book.name,
    author: book.author,
    intro: book.intro,
    coverUrl: book.coverUrl,
    bookUrl: book.bookUrl,
    tocUrl: book.tocUrl,
    kind: book.kind,
    lastChapter: book.lastChapter,
    group: book.group || '默认',
    chapters: chapters || [],
    addedAt: book.addedAt || Date.now(),
    updatedAt: Date.now(),
  };
  await db.put('books', rec);
  await reloadBooks();
  return rec;
}

/* ---------- 试读暂存（打开新书不入书架；加入书架时才固化） ---------- */

const PREVIEW_KEY = 'modou:preview-book';
const PREVIEW_CH_PREFIX = 'pch:';
let previewRec = null;
let previewChs = null;

function loadPreviewCache() {
  if (previewRec !== null) return;
  try {
    const raw = sessionStorage.getItem(PREVIEW_KEY);
    previewRec = raw ? JSON.parse(raw) : null;
  } catch (e) {
    previewRec = null;
  }
  previewChs = new Map();
  try {
    for (let i = 0; i < sessionStorage.length; i++) {
      const k = sessionStorage.key(i);
      const v = sessionStorage.getItem(k);
      // 丢弃历史脏数据（旧实现曾把 "null" 当缓存正文写入）
      if (k && k.startsWith(PREVIEW_CH_PREFIX) && v && v !== 'null') previewChs.set(k, v);
    }
  } catch (e) {
    /* sessionStorage 不可用时退化为内存态 */
  }
}

/** 打开一本书做试读：不写书架，只暂存 record + 已取到的章节正文 */
export function openPreview(rec, chHtmls = []) {
  previewRec = { ...rec, preview: true, id: rec.id || bookId(rec) };
  previewChs = new Map();
  chHtmls.forEach((html, i) => {
    if (html) previewChs.set(PREVIEW_CH_PREFIX + i, html);
  });
  try {
    sessionStorage.setItem(PREVIEW_KEY, JSON.stringify(previewRec));
    for (const [k, v] of previewChs) sessionStorage.setItem(k, v);
  } catch (e) {
    /* 超限时只留内存（本会话仍可试读） */
  }
  return previewRec;
}

/** 试读章正文缓存读写（key 与书架章节缓存同构：bookId + '||' + index） */
export function previewChapterKey(bookId, index) {
  return PREVIEW_CH_PREFIX + index;
}

export function previewChapterPut(index, html) {
  loadPreviewCache();
  if (!previewChs) return;
  const key = previewChapterKey(null, index);
  if (html == null) {
    // 「重新加载本章」语义：清除该章缓存（绝不能 setItem 写入字符串 "null"，否则重载后会被当作缓存正文）
    previewChs.delete(key);
    try {
      sessionStorage.removeItem(key);
    } catch (e) {
      /* 忽略 */
    }
    return;
  }
  previewChs.set(key, html);
  try {
    sessionStorage.setItem(key, html);
  } catch (e) {
    /* 忽略 */
  }
}

export function previewChapterGet(index) {
  loadPreviewCache();
  return (previewChs && previewChs.get(previewChapterKey(null, index))) || null;
}

export function previewBook() {
  loadPreviewCache();
  return previewRec && previewRec.preview ? previewRec : null;
}

export function clearPreview() {
  previewRec = null;
  previewChs = null;
  try {
    sessionStorage.removeItem(PREVIEW_KEY);
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
      const k = sessionStorage.key(i);
      if (k && k.startsWith(PREVIEW_CH_PREFIX)) sessionStorage.removeItem(k);
    }
  } catch (e) {
    /* 忽略 */
  }
}

/** 把试读书固化进书架（章节正文缓存从试读缓存迁到 chapters 表） */
export async function promotePreview() {
  const rec = previewBook();
  if (!rec) return null;
  loadPreviewCache();
  const saved = await saveBook({ ...rec, preview: undefined, addedAt: rec.addedAt || Date.now() }, rec.chapters || []);
  const rows = [];
  (rec.chapters || []).forEach((c, i) => {
    const html = previewChapterGet(i);
    if (html) rows.push({ id: saved.id + '||' + i, content: html, cachedAt: Date.now() });
  });
  if (rows.length) await db.putMany('chapters', rows);
  clearPreview();
  return saved;
}

export function findBook(id) {
  const hit = state.books.find((b) => b.id === id);
  if (hit) {
    // 轻量记录没有 chapters：详情页/阅读页要用章节表，按 id 单独取一次并缓存在这
    if (hit.chapters === undefined) {
      try {
        const b = typeof window !== 'undefined' && window.ModouBridge;
        if (b && typeof b.storeGet === 'function') {
          const full = JSON.parse(b.storeGet('books', id) || 'null');
          if (full) Object.assign(hit, full);
        }
      } catch (e) {
        /* 取不到就按轻量记录返回：详情页会显示 0 章，不至于整页崩掉 */
      }
    }
    return hit;
  }
  return previewBook() && previewBook().id === id ? previewBook() : null;
}

export async function removeBook(id) {
  await db.del('books', id);
  await db.del('progress', id);
  // 同步清理该书章节缓存（chapters 主键为 bookId + '||' + index）
  const prefix = id + '||';
  const keys = await db.keys('chapters');
  await Promise.all(keys.filter((k) => String(k).startsWith(prefix)).map((k) => db.del('chapters', k)));
  await reloadBooks();
}

export async function getProgress(bookId) {
  return (await db.get('progress', bookId)) || null;
}

/* ---------- 章节预取（点书到进阅读页之间的网络往返，藏到导航里） ---------- */
const prefetchMap = new Map(); // key -> { p: Promise<string|null>, at: number }
const PREFETCH_TTL = 60000;
const PREFETCH_TIMEOUT = 4000; // 预取联网兜底：4s 内拿不到就放弃，别把正文渲染挂死

/**
 * 书架按下封面就开始取这一章。
 *
 * 点开一本没缓存过的网络书，原流程是「导航 → 渲染阅读页 → 才发请求 → 等正文」，
 * 用户盯着「正文加载中…」好几百毫秒。改成按下就发，请求与路由/渲染并行，
 * 进阅读页时多半已经落地。
 */
export function prefetchChapter(book, index) {
  try {
    if (!book || book.preview) return null;
    if (!book.sourceUrl || book.sourceUrl === 'local://') return null;
    const src = state.sources.find((s) => s.bookSourceUrl === book.sourceUrl);
    if (!src || !book.chapters || !book.chapters[index]) return null;
    const key = bookId(book) + '||' + index;
    const hit = prefetchMap.get(key);
    if (hit && Date.now() - hit.at < PREFETCH_TTL) return hit.p;
    const p = (async () => {
      // 已缓存的章节秒回：别为它再发一次网络请求。
      // （书源失效/慢时，联网会把 Promise 挂起，进而卡住进阅读页后的正文渲染）
      try {
        const c = await db.get('chapters', key);
        if (c && c.content) return c.content;
      } catch (e) {
        /* 忽略，继续联网取 */
      }
      // 未缓存才联网；套超时兜底，慢书源也不至于让预取 Promise 永远挂起
      return await Promise.race([
        (async () => {
          try {
            const r = await src.getContent(book, book.chapters[index]);
            return !r.error && r.content ? r.content : null;
          } catch (e) {
            return null;
          }
        })(),
        new Promise((res) => setTimeout(() => res(null), PREFETCH_TIMEOUT)),
      ]);
    })();
    prefetchMap.set(key, { p, at: Date.now() });
    // 只留最近几条，避免长期占内存
    if (prefetchMap.size > 8) prefetchMap.delete(prefetchMap.keys().next().value);
    return p;
  } catch (e) {
    return null;
  }
}

/** 取走预取结果（一次性）：返回 Promise，调用方 await 即可；没有预取则 null */
export function takePrefetch(book, index) {
  if (!book) return null;
  const key = bookId(book) + '||' + index;
  const hit = prefetchMap.get(key);
  if (!hit) return null;
  prefetchMap.delete(key);
  return hit.p;
}

/**
 * 一次性取回全部进度 → Map<bookId, progress>。
 *
 * 书架/历史/收藏每次重绘都要按书查进度；逐本 getProgress() 会开 N 个
 * IndexedDB 事务（60 本书 = 60 个事务，手机上肉眼可见地卡）。这里合并成
 * 一次 getAll，再在内存里按 bookId 建索引，N 次 → 1 次。
 */
export async function loadProgressMap() {
  const rows = (await db.all('progress')) || [];
  const m = new Map();
  for (const p of rows) if (p && p.bookId) m.set(p.bookId, p);
  return m;
}

/** 保存进度；addMs 为本次新增阅读毫秒数（阅读统计累计） */
export async function saveProgress(bookId, index, chapterUrl, page = 0, addMs = 0) {
  const old = (await db.get('progress', bookId)) || {};
  await db.put('progress', {
    bookId,
    index,
    chapterUrl,
    page,
    readMs: (old.readMs || 0) + (addMs > 0 ? addMs : 0),
    updatedAt: Date.now(),
  });
}

/** 本月 key（本地时间 YYYY-MM） */
const monthKey = (d = new Date()) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');

/**
 * 月度阅读时长累计（书架「本月阅读」横幅用）。阅读页 15 秒心跳调一次。
 * 存 settings.readStatsByMonth：{ 'YYYY-MM': ms }，只保留最近 6 个月。
 */
export async function addReadMsMonth(addMs) {
  if (!(addMs > 0)) return;
  const all = (await settings.get('readStatsByMonth', {})) || {};
  const mk = monthKey();
  all[mk] = (all[mk] || 0) + addMs;
  const keys = Object.keys(all).sort();
  while (keys.length > 6) delete all[keys.shift()];
  await settings.set('readStatsByMonth', all);
}

/** 本月阅读时长（毫秒） */
export async function getReadMsMonth() {
  const all = (await settings.get('readStatsByMonth', {})) || {};
  return all[monthKey()] || 0;
}

/** 阅读统计：累计时长（毫秒）与有进度的书籍数 */
export async function getReadStats() {
  const all = await db.all('progress');
  return {
    totalMs: all.reduce((s, p) => s + (p.readMs || 0), 0),
    books: all.length,
  };
}

/* ---------- 书签 ---------- */

export async function addBookmark(rec) {
  const bm = {
    id: rec.bookId + '||' + Date.now(),
    bookId: rec.bookId,
    bookName: rec.bookName,
    chapterIndex: rec.chapterIndex,
    chapterName: rec.chapterName,
    page: rec.page || 0,
    text: rec.text || '',
    createdAt: Date.now(),
  };
  await db.put('bookmarks', bm);
  return bm;
}

export async function delBookmark(id) {
  await db.del('bookmarks', id);
}

/** 某书的书签（新的在前） */
export async function getBookmarks(bookId) {
  const all = await db.all('bookmarks');
  return all.filter((b) => b.bookId === bookId).sort((a, b) => b.createdAt - a.createdAt);
}

export { toast, confirmBox };

/* ---------- 路由 ---------- */

const NAV = [
  ['shelf', '书架'],
  ['search', '搜书'],
  ['sources', '书源管理'],
  ['settings', '设置'],
];

/**
 * 网页版书架已下线（整页原生化）：这里只给一张说明卡。
 *
 * 为什么还留一条路由：书签、旧链接、网页版详情页的「回书架」都指向 #/shelf，
 * 直接 404 会让人以为书丢了；给一句实话 + 一个原生入口更省事。
 */
function renderShelfGone(view) {
  const canNative = !!(window.ModouBridge && typeof window.ModouBridge.openNativeShelf === 'function');
  view.innerHTML = `
    <div class="page-head"><div><h1>书架</h1><p class="muted">App 内打开更顺手</p></div></div>
    <div class="card">
      <div class="sec-head"><h3>书架在 App 里</h3><span class="muted small">App 内直达</span></div>
      <p class="muted small">书架整页已由 App 内界面接管（书架 / 历史 / 收藏、分组、筛选都在那里）。
        两边读写同一份数据，这里删掉这一页不会影响任何一本书。</p>
      <div class="btn-row" style="margin-top:10px">
        <button class="btn primary" id="gone-shelf">打开书架</button>
      </div>
    </div>`;
  const b = document.getElementById('gone-shelf');
  if (b) {
    b.onclick = () => {
      if (!canNative) return toast('当前不是 App 环境');
      window.ModouBridge.openNativeShelf();
    };
  }
}

async function route() {
  // 离开阅读页时归还其注册的全局监听与朗读状态
  if (typeof window.__readerCleanup === 'function') {
    try {
      window.__readerCleanup();
    } catch (e) {
      /* 忽略清理异常 */
    }
    window.__readerCleanup = null;
  }
  document.body.removeAttribute('data-reader-theme');
  document.body.classList.remove('reader-paged');
  document.body.classList.remove('in-reader');
  // 路由切换：清掉可能残留的弹层（否则弹层会跨页停留）
  closeAllOverlays();
  // hash 形如 #/book/<id>、#/read/<id>/<index> 或 #/search?kw=书名；
  // <id> 已是 URL 安全形式，整段取用不再编解码；query 部分原样交给视图自行解析
  const raw = location.hash.replace(/^#\/?/, '') || 'search';
  const qm = raw.indexOf('?');
  const query = qm === -1 ? '' : raw.slice(qm + 1);
  const pathPart = qm === -1 ? raw : raw.slice(0, qm);
  const slash = pathPart.indexOf('/');
  const path = slash === -1 ? pathPart : pathPart.slice(0, slash);
  const rest = slash === -1 ? '' : pathPart.slice(slash + 1);
  // 离开阅读页恢复系统状态栏/导航栏
  if (path !== 'read') {
    try {
      if (window.ModouBridge && window.ModouBridge.setImmersive) window.ModouBridge.setImmersive(false, null);
    } catch (e) {
      /* 忽略 */
    }
    // 同时恢复全局主题：阅读页点过「夜间」后直接返回，data-theme 会停在 dark，
    // 设置里明明选的是浅色却整页深色 —— 必须在这里拉回用户设定
    setGlobalDark(false);
  }
  // 只有阅读页需要原生选区菜单；其它页面关掉，避免空白点按反复创建看不见的 ActionMode
  try {
    if (window.ModouBridge && window.ModouBridge.setReaderMode) window.ModouBridge.setReaderMode(path === 'read');
  } catch (e) {
    /* 忽略 */
  }
  const view = $('#view');
  // 视图代号：每次路由接管 +1。还在飞行中的视图渲染（阅读页有多次 await）
  // 据此发现自己已过期，别继续往新 DOM 上绑旧闭包。
  window.__routeGen = (window.__routeGen || 0) + 1;
  view.innerHTML = '';
  view.classList.remove('chrome-hidden');
  view.scrollTop = 0;
  try {
    switch (path) {
      case 'shelf':
        // 网页版书架已删除：书架整页移到原生（ShelfActivity），这里只留一张说明卡，
        // 老链接/书签点进来不至于看到「页面不存在」，也不会误以为书没了
        renderShelfGone(view);
        break;
      case 'search':
        (await import('./search.js')).renderSearch(view, query);
        break;
      case 'sources':
        (await import('./sources.js')).renderSources(view);
        break;
      case 'book':
        (await import('./book.js')).renderBook(view, safeDecode(rest));
        break;
      case 'read': {
        const li = rest.lastIndexOf('/');
        const id = li === -1 ? rest : rest.slice(0, li);
        const idx = li === -1 ? 0 : Number(rest.slice(li + 1)) || 0;
        (await import('./reader.js')).renderReader(view, safeDecode(id), idx);
        break;
      }
      case 'settings':
        (await import('./settings.js')).renderSettings(view);
        break;
      default:
        view.innerHTML = '<div class="empty"><h2>页面不存在</h2><p><a href="#/shelf">回书架</a></p></div>';
    }
  } catch (e) {
    console.error(e);
    view.innerHTML = `<div class="empty"><h2>渲染出错</h2><pre class="err">${escHtml(String(e && e.stack || e))}</pre></div>`;
  }
  for (const a of $$('.sidebar nav a, .tabbar a')) {
    a.classList.toggle('on', a.getAttribute('href') === '#/' + path);
  }
}

function safeDecode(s) {
  try {
    return decodeURIComponent(s);
  } catch (e) {
    return s;
  }
}

function escHtml(s) {
  return s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
}

// 包一层 catch：boot 是 async，任何未捕获的 reject 都会变成静默白屏
document.addEventListener('DOMContentLoaded', () => {
  boot().catch((e) => {
    console.error('[boot] 未捕获', e);
    showBootError(e);
  });
});
