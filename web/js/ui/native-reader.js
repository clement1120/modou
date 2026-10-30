/**
 * native-reader.js — 原生阅读页（实验）的前端入口与桥接。
 *
 * 定位：只把「正文这一屏」交给 Android 本地绘制（StaticLayout + 原生选区），
 * 书架 / 书源 / 净化 / 导入 / 朗读仍全部留在网页版。所以这里不做任何业务逻辑，
 * 只干三件事：
 *   1. 把当前章打成 payload 交给原生（原生拿到就能立刻出第一屏，不等 JS）；
 *   2. 原生翻到章边界时，按需回取相邻章正文（复用阅读页同一套净化/缓存链路）；
 *   3. 把原生的进度写回 IndexedDB，退出时按最新章节重渲染阅读页。
 *
 * 协议见 NativeReaderActivity 类注释。
 */

/** 当前原生会话（一本书一次）。退出后置空，避免旧回调影响新会话。 */
let session = null;

/** 原生阅读是否可用（桌面浏览器 / 旧包没有这个桥） */
export const nativeReaderReady = () =>
  !!(window.ModouBridge && typeof window.ModouBridge.openNativeReader === 'function');

/**
 * 章节 HTML → 段落数组（原生侧自己排版，不需要标签）。
 *
 * <br> 按换行切成多段：正文里「一句一行」的诗体/对话体，硬合成一段会看不出分行。
 * 空段直接丢弃，原生那边统一加段首缩进。
 */
export function parasFromHtml(html, chapterName) {
  const box = document.createElement('div');
  box.innerHTML = html || '';
  const out = [];
  const push = (t) => {
    const s = (t || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
    if (s) out.push(s);
  };
  const blocks = [...box.children];
  if (!blocks.length) {
    (box.textContent || '').split(/\n+/).forEach(push);
  } else {
    for (const el of blocks) {
      if (/^(P|DIV|H[1-6]|BLOCKQUOTE|LI|SECTION|ARTICLE)$/.test(el.tagName)) {
        const tmp = document.createElement('div');
        tmp.innerHTML = el.innerHTML.replace(/<br\s*\/?>/gi, '\n');
        (tmp.textContent || '').split(/\n+/).forEach(push);
      } else {
        push(el.textContent);
      }
    }
  }
  // 首段常是章节标题（不同书源标题写法不一致，净化没去掉）：原生顶部已有标题，去掉重复
  const name = (chapterName || '').replace(/\s+/g, '').trim();
  if (name && out.length && out[0].replace(/\s+/g, '') === name) out.shift();
  return out;
}

/**
 * 打开原生阅读页。
 *
 * @param {object} o
 *   bookId/bookTitle/index/total/titles/title/paras/offset
 *   fontSize/lineHeight/margin/font/weight/theme
 *   onChapter(i) → Promise<{title, paras}>   按需取章
 *   onProgress(i, offset)                    原生报进度
 *   onBack()                                 退出原生，由调用方决定是否重渲染
 * @returns {boolean} 是否成功发起
 */
export function openNativeReader(o) {
  if (!nativeReaderReady()) return false;
  session = o;
  const payload = {
    bookId: o.bookId || '',
    bookTitle: o.bookTitle || '',
    index: o.index || 0,
    total: o.total || 0,
    titles: o.titles || [],
    title: o.title || '',
    paras: o.paras || [],
    offset: o.offset || 0,
    fontSize: o.fontSize || 18,
    lineHeight: o.lineHeight || 1.9,
    margin: o.margin || 24,
    font: o.font || 'song',
    weight: o.weight || 500,
    theme: o.theme || 'paper',
  };
  try {
    window.ModouBridge.openNativeReader(JSON.stringify(payload));
    return true;
  } catch (e) {
    session = null;
    return false;
  }
}

/* ---------- 原生 → 前端的全局回调（只装一次） ---------- */

if (!window.__modouNativeWired) {
  window.__modouNativeWired = true;

  /** 原生请求某章正文 */
  window.__modouNativeChapter = async (i) => {
    const s = session;
    if (!s) return;
    const reply = (obj) => {
      try {
        window.ModouBridge.nativeChapterData(JSON.stringify(obj));
      } catch (e) {
        /* 桥已断（App 退出）：忽略 */
      }
    };
    try {
      const r = await s.onChapter(i);
      const paras = (r && r.paras) || [];
      if (!paras.length) {
        reply({ index: i, error: '这一章没有取到正文' });
        return;
      }
      reply({ index: i, title: (r && r.title) || '', paras });
    } catch (e) {
      reply({ index: i, error: '这一章加载失败，请检查网络' });
    }
  };

  /** 原生报进度：章节 + 章内字符偏移 */
  window.__modouNativeProgress = (i, offset) => {
    const s = session;
    if (!s || typeof s.onProgress !== 'function') return;
    try {
      s.onProgress(i, offset);
    } catch (e) {
      /* 忽略 */
    }
  };

  /** 原生页退出：清会话，交回调用方 */
  window.__modouNativeBack = () => {
    const s = session;
    session = null;
    if (s && typeof s.onBack === 'function') {
      try {
        s.onBack();
      } catch (e) {
        /* 忽略 */
      }
    }
  };
}
