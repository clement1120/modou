/** dom.js — 迷你 DOM 助手 */
export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => [...r.querySelectorAll(s)];

/* ---------- 原生选区菜单开关（全局，只挂一次） ----------
 * 壳（ReaderWebView）默认屏蔽长按弹出的系统 ActionMode —— 阅读页有自己的选词工具条，
 * 书架长按封面有编辑弹层，系统那条（复制/分享/全选/AI 写作）只会碍事。
 * 但输入框（搜索词、替换为、书名、封面 URL…）里长按仍要能复制/粘贴，
 * 所以焦点进出输入框时把「编辑态」同步给原生，临时放行系统菜单。
 * （桌面浏览器没有 ModouBridge，静默跳过。）
 */
const isFieldEl = (el) => {
  if (!el || el.nodeType !== 1) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || !!el.isContentEditable;
};
const setNativeEditing = (on) => {
  try {
    const b = typeof window !== 'undefined' && window.ModouBridge;
    if (b && typeof b.setEditing === 'function') b.setEditing(!!on);
  } catch (e) {
    /* 无桥环境忽略 */
  }
};
if (typeof document !== 'undefined') {
  document.addEventListener('focusin', (e) => setNativeEditing(isFieldEl(e.target)), true);
  document.addEventListener(
    'focusout',
    () => {
      // 焦点在框与框之间移动时 focusout 先于 focusin，延后一拍再看落点，避免误关
      setTimeout(() => setNativeEditing(isFieldEl(document.activeElement)), 0);
    },
    true
  );
}

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'html') el.innerHTML = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(9)) {
    if (c == null || c === false) continue;
    el.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return el;
}

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/* ---------- 弹层返回键（统一返回栈） ----------
 * 唯一入口是原生：MainActivity.onBackPressed 询问 window.__modouBack，
 * 按打开顺序 LIFO 逐层关：后开的先关（从「页眉页脚」返回应回到「排版」，
 * 目录抽屉开着时返回应先关抽屉，而不是一路退回书架）。
 *
 * 所有「应被返回键优先处理的浮层」都要 registerBack：
 * - openModal / openSheet / confirmBox（自动注册）
 * - 页面自定义浮层（目录抽屉、搜索条等）各自注册
 *
 * 这里刻意**不用** history 占位：关弹层时若 history.back()，会和紧随其后的路由跳转
 * （阅读页「更多 → 书籍详情」）抢时序，把跳转撤销掉——已被实测证实。
 * 浏览器预览没有返回键，弹层用 ✕ / 点背景关闭即可。
 */
const backHandlers = [];
/**
 * 注册一个返回处理器。返回注销函数（close 时务必调用，避免泄漏）。
 * @param {() => boolean} fn 返回 true = 消费本次返回；false = 跳过交给下一个
 */
export function registerBack(fn) {
  backHandlers.push(fn);
  return () => {
    const i = backHandlers.indexOf(fn);
    if (i >= 0) backHandlers.splice(i, 1);
  };
}
if (typeof window !== 'undefined') {
  /**
   * 原生返回键的第一站（由 MainActivity 调用）。
   * @returns {boolean} true = 已消费（关掉一层浮层），原生不再回退历史
   */
  window.__modouBack = () => {
    for (let i = backHandlers.length - 1; i >= 0; i--) {
      let consumed = false;
      try {
        consumed = backHandlers[i]() !== false;
      } catch (e) {
        consumed = false;
      }
      if (consumed) return true;
    }
    return false;
  };
}

/** 路由切换时清掉残留弹层（防止弹层跨页残留；对应 handler 由 isConnected 防护自净） */
export function closeAllOverlays() {
  if (typeof document === 'undefined') return;
  document.querySelectorAll('.overlay').forEach((e) => e.remove());
  // 直接移除不会走 close()，计数要归零，否则 body 一直锁滚动
  overlayCount = 0;
  document.body.classList.remove('no-scroll');
}

/** openModal/openSheet/confirmBox 共用：挂载后注册返回键关闭，close 时注销 */
function bindOverlayBack(overlay, close) {
  return registerBack(() => {
    if (!overlay.isConnected) return false; // 已被路由清理/直接移除：跳过并自净
    close();
    return true;
  });
}

/* ---------- 弹层背景滚动锁 ----------
 * 弹层打开时锁住 body 滚动：否则手指在弹层上往下一滑，背后的书源列表/正文
 * 会跟着滚（用户反馈"这个页面我居然还能滑动背景"）。
 * 用引用计数：弹层可叠加，最后一个关掉才解锁。
 */
let overlayCount = 0;
function lockScroll() {
  overlayCount++;
  document.body.classList.add('no-scroll');
}
function unlockScroll() {
  overlayCount = Math.max(0, overlayCount - 1);
  if (overlayCount === 0) document.body.classList.remove('no-scroll');
}

let toastTimer = null;
export function toast(msg, ms = 2400) {
  let box = $('#toast');
  if (!box) {
    box = h('div', { id: 'toast', class: 'toast' });
    document.body.append(box);
  }
  box.textContent = msg;
  box.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.classList.remove('show'), ms);
}

/**
 * 破坏性操作统一确认弹层（自绘，Promise 化）。
 * 注意：不可用 window.confirm —— Android WebView 在自定义 WebChromeClient
 * 未实现 onJsConfirm 时恒返回 false，会导致所有「移出/删除/清空」静默失效。
 * @returns {Promise<boolean>} 确认=true / 取消=false
 */
export function confirmBox(msg, opts = {}) {
  return new Promise((resolve) => {
    const overlay = h('div', { class: 'overlay confirm-overlay' });
    const dialog = h('div', { class: 'dialog confirm' });
    const head = h('div', { class: 'dialog-head' }, h('span', {}, opts.title || '请确认'));
    const body = h('div', { class: 'dialog-body' }, h('p', { class: 'confirm-msg' }, msg));
    const cancel = h('button', { class: 'btn ghost', 'data-cancel': '' }, opts.cancelText || '取消');
    const ok = h('button', { class: 'btn primary', 'data-ok': '' }, opts.okText || '确定');
    const acts = h('div', { class: 'confirm-acts' }, cancel, ok);
    dialog.append(head, body, acts);
    overlay.append(dialog);

    let settled = false;
    const done = (val) => {
      if (settled) return;
      settled = true;
      unlockScroll();
      unBack();
      document.removeEventListener('keydown', onKey, true);
      overlay.remove();
      resolve(val);
    };
    const unBack = bindOverlayBack(overlay, () => done(false));
    const onKey = (e) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        done(false);
      } else if (e.key === 'Enter') {
        e.stopPropagation();
        done(true);
      }
    };
    ok.onclick = () => done(true);
    cancel.onclick = () => done(false);
    overlay.addEventListener('click', (e) => {
      if (e.target === overlay) done(false);
    });
    document.addEventListener('keydown', onKey, true);
    lockScroll();
    document.body.append(overlay);
      // 被系统返回键关闭时，Promise 也要落地为「取消」
    const watcher = new MutationObserver(() => {
      if (!document.body.contains(overlay)) {
        watcher.disconnect();
        if (!settled) {
          settled = true;
          document.removeEventListener('keydown', onKey, true);
          resolve(false);
        }
      }
    });
    watcher.observe(document.body, { childList: true });
    requestAnimationFrame(() => ok.focus());
  });
}

/** 弹出层（书源导入/编辑/校验用） */
export function openModal(title, bodyNode, opts = {}) {
  const overlay = h('div', { class: 'overlay' });
  const dialog = h('div', { class: 'dialog' + (opts.wide ? ' wide' : '') });
  const head = h('div', { class: 'dialog-head' }, h('span', {}, title));
  const close = () => {
    if (!overlay.isConnected) return;
    unlockScroll();
    unBack();
    overlay.remove();
  };
  const unBack = bindOverlayBack(overlay, close);
  head.append(h('button', { class: 'dialog-x', title: '关闭', onclick: close }, '✕'));
  const body = h('div', { class: 'dialog-body' }, bodyNode);
  dialog.append(head, body);
  overlay.append(dialog);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay && !opts.sticky) close();
  });
  lockScroll();
  document.body.append(overlay);
  return { overlay, close };
}

/** 底部动作面板（导入选项 / 阅读排版设置用） */
export function openSheet(title, bodyNode) {
  const overlay = h('div', { class: 'overlay sheet-overlay' });
  const sheet = h('div', { class: 'sheet' });
  const handle = h('div', { class: 'sheet-handle' });
  const head = h('div', { class: 'sheet-head' }, h('span', {}, title));
  const close = () => {
    if (!overlay.isConnected) return;
    unlockScroll();
    unBack();
    overlay.remove();
  };
  const unBack = bindOverlayBack(overlay, close);
  head.append(h('button', { class: 'dialog-x', title: '关闭', onclick: close }, '✕'));
  const body = h('div', { class: 'sheet-body' }, bodyNode);
  sheet.append(handle, head, body);
  overlay.append(sheet);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) close();
  });
  lockScroll();
  document.body.append(overlay);
  return { overlay, close };
}

/**
 * 保存/导出文本文件。
 * Android 壳内走 ModouBridge.saveFile（系统「另存为」，WebView 不支持 blob 下载）；
 * 浏览器内回退为 blob 下载。
 * @returns {boolean} 是否已发起保存
 */
export function saveFile(filename, content, mime = 'application/json') {
  const text = String(content ?? '');
  if (typeof window !== 'undefined' && window.ModouBridge && typeof window.ModouBridge.saveFile === 'function') {
    try {
      window.ModouBridge.saveFile(filename, mime, text);
      return true;
    } catch (e) {
      /* 桥异常则回退浏览器下载 */
    }
  }
  try {
    const blob = new Blob([text], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = h('a', { href: url, download: filename });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 3000);
    return true;
  } catch (e) {
    return false;
  }
}

/* ---------- 封面：占位生成 + 统一 <img> ---------- */

/** 封面配色（赤陶/黛青/黛绿/藤黄轮换，按书名散列取色，书架不会一片惨白） */
const COVER_PALETTES = [
  ['#D96C47', '#F7EDE2', '#5C4433'],
  ['#5E6B5A', '#EEF0E6', '#333D30'],
  ['#4E6E81', '#E8EFF2', '#28393F'],
  ['#B08968', '#F5EFE6', '#4A3826'],
  ['#8E5572', '#F4E9EE', '#3F2530'],
];

/** FNV-1a 字符串散列：把任意 id/名称映射成稳定、分布均匀的 32 位无符号整数 */
function hashStr(s) {
  let h = 2166136261 >>> 0;
  s = String(s == null ? '' : s);
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return h >>> 0;
}

/**
 * 生成占位封面（SVG data URL）：书名竖排/大字 + 衬线底纹。
 * 没有封面的书（本地 TXT / 网络书封面失效）也能在书架上有一张像样的卡面。
 *
 * 结果按 name+seed 缓存：书架每次重绘都会为每本书再要一次封面，不缓存的话
 * 每切一次页签就要重新拼 60 份 SVG 并 encodeURIComponent（手机上很贵）。
 */
const _coverCache = new Map();
export function coverArt(name, seed = 0) {
  const t = String(name || '未命名').replace(/\s+/g, ' ').trim();
  const cacheKey = seed + '\u0000' + t;
  const hit = _coverCache.get(cacheKey);
  if (hit) return hit;
  let hs = seed;
  for (let i = 0; i < t.length; i++) hs = (hs * 31 + t.charCodeAt(i)) >>> 0;
  const [bg, panel, ink] = COVER_PALETTES[hs % COVER_PALETTES.length];
  // 标题取前 6 字，竖排两列更像书封
  const chars = [...t.slice(0, 6)];
  const col1 = chars.slice(0, Math.ceil(chars.length / 2)).join('');
  const col2 = chars.slice(Math.ceil(chars.length / 2)).join('');
  const exml = (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c] || c);
  const vertical = (s, x) =>
    [...s]
      .map((c, i) => `<text x="${x}" y="${58 + i * 46}" font-size="38" fill="${ink}" font-family="serif" text-anchor="middle">${exml(c)}</text>`)
      .join('');
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="180" height="252" viewBox="0 0 180 252">
  <rect width="180" height="252" fill="${bg}"/>
  <rect x="10" y="10" width="160" height="232" rx="10" fill="none" stroke="${panel}" stroke-opacity=".55" stroke-width="2"/>
  <rect x="22" y="22" width="136" height="208" rx="6" fill="${panel}" fill-opacity=".38"/>
  ${col2 ? vertical(col1, 68) : vertical(col1, 90)}${col2 ? vertical(col2, 116) : ''}
</svg>`;
  const url = 'data:image/svg+xml,' + encodeURIComponent(svg);
  // 上限保护：书特别多时也不无限涨（超出清掉重算，代价远小于内存）
  if (_coverCache.size > 400) _coverCache.clear();
  _coverCache.set(cacheKey, url);
  return url;
}

/**
 * 统一封面 <img>：有 coverUrl 用真图（no-referrer 绕过防盗链），失败回退占位封面；
 * 没有封面直接用占位封面。
 */
export function coverImg(book, cls = '') {
  // 按 id 字符串散列取色（而非 id 长度），同名不同书、同长不同书都能均匀分色，避免撞色
  const fb = coverArt(book && book.name, hashStr((book && book.id) || (book && book.name) || ''));
  const src = book && book.coverUrl ? esc(book.coverUrl) : fb;
  const onerr = book && book.coverUrl ? ` onerror="this.onerror=null;this.src='${fb}'"` : '';
  return `<img class="cover ${cls}" src="${src}" loading="lazy" referrerpolicy="no-referrer" alt=""${onerr}>`;
}
