/**
 * sources.js — 书源管理（v0.6.47 统筹重做）
 *
 * 以前是「功能清单 → 按钮清单」：顶栏 6 个按钮平铺，卡片里堆开关 + 4 个 mini 按钮 +
 * 标签 + 内联失效原因，批量操作藏在筛选里，弹层三种风格混用。
 * 现在按信息架构分层：
 *   浏览态：标题+计数 + 🔍 + ┇菜单；卡片只有「名称 / 一行元信息 / 状态色点 / 启用开关」
 *   多选态：长按或勾选进入 → 顶部「已选 N · 全选」，底部浮动操作栏（启用/禁用/校验/分组/删除/导出）
 *   详情态：点卡片开右侧抽屉（失效原因、规则摘要、编辑、分组、深度校验、删除）
 * 删除卡片内 4 个 mini 按钮与内联原因（改由 swipe 与抽屉承载），状态改用色点。
 */
import { state, reloadSources, enabledSources, toast, confirmBox } from './app.js';
import { BookSource } from '../engine/source.js';
import { db } from '../db.js';
import { h, $, $$, esc, openModal, openSheet, saveFile, registerBack } from './dom.js';

const checkResults = new Map(); // bookSourceUrl → 'ok' | 'bad' | 'skip' | 'nosearch' | 'run'
const deepResults = new Map(); // bookSourceUrl → { state:'run'|'ok'|'bad', step, reason, ms, chars }
const failReasons = new Map(); // bookSourceUrl → 失败原因
const checkDiag = new Map(); // bookSourceUrl → {logs,status,finalUrl,snip,bytes,ms}（导出报告用）
const srcFilter = { check: 'all', enabled: 'all', group: '' }; // 顶部 chip 行筛选
const srcKw = { v: '' }; // 名称搜索词
let checkAllRunning = false;
let checkAllStop = false;
let checkToken = null;

/* 批量体检的预算。Legado 默认给单源 180s，手机端完全等不起；
 * 这里压到 45s（搜索+详情+目录+最多两章正文），单请求 12s 且不重试，
 * 一个假死源最多占一个 worker 45s，不会把整批拖死。 */
const CHECK_TIMEOUT_MS = 45000;
const CHECK_REQUEST_TIMEOUT_MS = 12000;
const CHECK_THREADS = 4;
const CHECK_CONTENT_CHAPTERS = 2;
const checkArtifacts = new Map(); // 快速校验产物，深度校验复用，避免重复请求
/** 本次体检要跑的档位（对齐 Legado 的 checkSearch/checkInfo/checkToc/checkContent 五档，
 *  modou 无发现页故为四档）。弹层勾选后写入；单源弹窗的勾选会持久化到书源记录。 */
let curTiers = { checkSearch: true, checkInfo: true, checkToc: true, checkContent: true };

/** 失败原因压成一行，别把整段堆栈塞进 toast/相机 */
const brief = (e) => String((e && e.message) || e || '').replace(/\s+/g, ' ').slice(0, 120);
const yieldUI = (ms = 16) => new Promise((r) => setTimeout(r, ms));

/* ---------- 多选态 ---------- */
const selected = new Set(); // 存 keyOf(src)
let selectMode = false;
/** 唯一键：同名同址的源（查重场景）也要能分别选中 */
const keyOf = (s) => s.bookSourceUrl + '\u0000' + (s.bookSourceName || '');

export async function renderSources(view) {
  // 路由切换时清掉可能残留的抽屉（与阅读页目录抽屉同处理）
  $$('.src-dmask, .src-drawer').forEach((e) => e.remove());
  view.innerHTML = `
    <div class="page-head">
      <div>
        <h1>书源管理</h1>
        <p class="muted">共 <b id="src-total">0</b> 个 · 启用 <b id="src-on">0</b> 个</p>
      </div>
      <div class="src-head-acts">
        <button class="head-ic" id="btn-search" title="搜索书源" aria-label="搜索书源">🔍</button>
        <button class="head-ic" id="btn-menu" title="更多操作" aria-label="更多操作">┇</button>
      </div>
    </div>
    <div class="src-searchrow" id="searchrow" hidden>
      <input id="src-kw" placeholder="搜索书源名称" autocomplete="off">
    </div>
    <div class="src-chips" id="src-chips"></div>
    <div class="src-selbar" id="src-selbar" hidden></div>
    <div id="checkall-bar" hidden></div>
    <input type="file" id="file-input" accept=".json,.txt" multiple hidden>
    <div id="src-list"></div>
    <div class="src-fab" id="src-fab" hidden></div>
  `;
  await reloadSources();
  selected.clear();
  selectMode = false;
  renderList();
  // 体检结果/进度是模块级状态：从别的页面切回来要接着显示，而不是从头空白
  paintCheckBar();

  $('#btn-search').onclick = () => {
    const row = $('#searchrow');
    row.hidden = !row.hidden;
    if (!row.hidden) $('#src-kw').focus();
    else {
      srcKw.v = '';
      $('#src-kw').value = '';
      renderList();
    }
  };
  // 搜索框：按帧合并重绘。原来每敲一个字符就整表重建（上百张卡 + 近千个监听），
  // 中文输入法连打时肉眼可见地掉帧。
  let kwRaf = 0;
  $('#src-kw').oninput = (e) => {
    srcKw.v = e.target.value.trim();
    if (kwRaf) cancelAnimationFrame(kwRaf);
    kwRaf = requestAnimationFrame(() => {
      kwRaf = 0;
      renderList();
    });
  };
  $('#btn-menu').onclick = openSrcMenu;

  // 文件导入（隐藏 input，由动作面板触发）
  const fileInput = $('#file-input');
  fileInput.onchange = async (e) => {
    const files = [...e.target.files];
    let all = [];
    for (const f of files) {
      try {
        all.push(...parseSourcesJson(await f.text()));
      } catch (err) {
        toast(`《${f.name}》解析失败：${err.message}`);
      }
    }
    await importSources(all);
    e.target.value = '';
  };
}

/* ---------- ┇ 菜单（导入 / 校验全部 / 检查重复 / 导出 / 载入示例） ---------- */

/** 统一弹层：菜单项面板（与导入/筛选同风格，不再三种混用） */
function openSrcMenu() {
  const body = h('div', { class: 'opt-list' });
  const items = [
    { id: 'mi-import', label: '导入书源', desc: '本地文件 / 网络地址 / 粘贴 JSON', run: openImportSheet },
    { id: 'mi-multiselect', label: '多选 / 全选', desc: '进入多选态并全选当前筛选结果，可批量启停 / 校验 / 删除', run: selectAllFiltered },
    { id: 'mi-checkall', label: '书源体检', desc: '全量快速校验 → 深度校验 → 清理两次不合格', run: openCheckAllSheet },
    { id: 'mi-dedup', label: '检查重复', desc: '按地址或名称查重，每组保留 1 个', run: () => openDedupSheet() },
    { id: 'mi-export', label: '导出全部', desc: '导出为 Legado 兼容 JSON', run: exportAll },
    { id: 'mi-sample', label: '载入示例', desc: '内置示例书源，用于体验引擎', run: loadSamples },
  ];
  const sheet = openSheet('书源管理', body);
  for (const it of items) {
    const btn = h(
      'button',
      {
        class: 'opt-item',
        id: it.id,
        onclick: () => {
          sheet.close();
          it.run();
        },
      },
      h('span', { class: 'opt-tx' }, h('b', {}, it.label), h('small', {}, it.desc))
    );
    body.append(btn);
  }
}

/* ---------- 筛选：顶部常驻可折叠 chip 行 ---------- */
let chipsOpen = false; // 面板展开态跨重绘保留：点选 chip 后面板不该自己关掉

function renderChips() {
  const box = $('#src-chips');
  if (!box) return;
  const active = filterSummary();
  const groups = new Map();
  for (const s of state.sources) {
    for (const g of String(s.bookSourceGroup || '').split(/[,，]/)) {
      const t = g.trim();
      if (t) groups.set(t, (groups.get(t) || 0) + 1);
    }
  }
  const row = (items, current, onPick) => {
    const r = h('div', { class: 'chip-row' });
    for (const [val, label] of items) {
      const c = h('button', { class: 'chip-btn' + (current === val ? ' on' : ''), type: 'button' }, label);
      c.onclick = () => {
        onPick(val);
        renderChips();
        renderList();
      };
      r.append(c);
    }
    return r;
  };
  box.innerHTML = '';
  const toggle = h(
    'button',
    { class: 'chip-btn chip-toggle' + (active ? ' on' : ''), type: 'button' },
    active ? `筛选：${active}` : '筛选'
  );
  const panel = h('div', { class: 'chip-panel' });
  panel.hidden = !chipsOpen;
  panel.append(
    row([['all', '全部'], ['ok', '✓ 有效'], ['bad', '✗ 失效'], ['both', '✗✗ 两次不合格'], ['unck', '未校验']], srcFilter.check, (v) => (srcFilter.check = v)),
    row([['all', '全部'], ['on', '已启用'], ['off', '已禁用']], srcFilter.enabled, (v) => (srcFilter.enabled = v))
  );
  if (groups.size) {
    panel.append(
      row([['', '全部分组'], ...[...groups.keys()].map((g) => [g, g])], srcFilter.group, (v) => (srcFilter.group = v))
    );
  }
  const clear = h('button', { class: 'chip-btn', type: 'button' }, '清除筛选');
  clear.onclick = () => {
    srcFilter.check = 'all';
    srcFilter.enabled = 'all';
    srcFilter.group = '';
    renderChips();
    renderList();
  };
  panel.append(clear);
  toggle.onclick = () => {
    chipsOpen = !chipsOpen;
    panel.hidden = !chipsOpen;
  };
  box.append(toggle, panel);
}

function applyFilter(s) {
  if (srcKw.v && !String(s.bookSourceName || '').toLowerCase().includes(srcKw.v.toLowerCase())) return false;
  const res = checkResults.get(s.bookSourceUrl);
  if (srcFilter.check === 'ok' && res !== 'ok') return false;
  if (srcFilter.check === 'bad' && res !== 'bad') return false;
  // 两次都不合格：快速校验判失效 + 深度校验四步也没跑通 → 可安全清理
  if (srcFilter.check === 'both') {
    const d = deepResults.get(s.bookSourceUrl);
    if (!(res === 'bad' && d && d.state === 'bad')) return false;
  }
  if (srcFilter.check === 'unck' && res) return false;
  if (srcFilter.enabled === 'on' && !s.enabled) return false;
  if (srcFilter.enabled === 'off' && s.enabled) return false;
  if (srcFilter.group) {
    const groups = String(s.bookSourceGroup || '').split(/[,，]/).map((t) => t.trim());
    if (!groups.includes(srcFilter.group)) return false;
  }
  return true;
}

function filterSummary() {
  const parts = [];
  if (srcFilter.check === 'ok') parts.push('仅有效');
  if (srcFilter.check === 'bad') parts.push('仅失效');
  if (srcFilter.check === 'both') parts.push('仅两次不合格');
  if (srcFilter.check === 'unck') parts.push('仅未校验');
  if (srcFilter.enabled === 'on') parts.push('仅启用');
  if (srcFilter.enabled === 'off') parts.push('仅禁用');
  if (srcFilter.group) parts.push(`分组「${srcFilter.group}」`);
  return parts.join(' + ');
}

/* ---------- 列表 ---------- */

function renderList() {
  const wrap = $('#src-list');
  if (!wrap) return;
  // 重绘前先收起已滑开的卡片：否则卡片被替换后，挂在 document/window 上的
  // 「点别处 / 滚动即收起」监听会残留，越用越多
  if (openedSwipe) openedSwipe.reset();
  const tEl = $('#src-total');
  const oEl = $('#src-on');
  if (tEl) tEl.textContent = state.sources.length;
  if (oEl) oEl.textContent = enabledSources().length;
  renderChips();

  if (!state.sources.length) {
    wrap.innerHTML = `<div class="empty">
      <h2>还没有书源</h2>
      <p>导入你自己的 Legado 书源 JSON，或先载入示例体验引擎。</p>
      <div class="empty-cta">
        <button class="btn primary" id="btn-sample-empty">载入示例书源</button>
        <button class="btn ghost" id="btn-import-empty">导入书源</button>
      </div>
    </div>`;
    $('#btn-sample-empty').onclick = loadSamples;
    $('#btn-import-empty').onclick = openImportSheet;
    return;
  }
  const shown = state.sources.filter(applyFilter);
  if (!shown.length) {
    wrap.innerHTML = `<div class="empty"><h2>没有符合条件的书源</h2><p>当前条件：${esc(filterSummary() || srcKw.v || '—')}。</p></div>`;
    return;
  }
  wrap.innerHTML = shown.map(rowHtml).join('');
  bindRows(wrap);
  updateSelBar();
}

/** 上次校验结果（持久化在书源记录里，进页面直接可见，不用重跑） */
function lastCheckTag(s) {
  const lc = s.lastCheck;
  if (!lc || !lc.t) return '';
  const d = new Date(lc.t);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const st = lc.state === 'ok' ? '有效' : lc.state === 'bad' ? '失效' : '跳过';
  return `<span class="src-last" data-st="${esc(lc.state || '')}">上次 ${hh}:${mm} ${st}</span>`;
}

/** 校验完成时即时刷新卡片上的「上次校验」标签，不用等整列表重渲染 */
function paintLastCheck(src) {
  if (typeof document === 'undefined') return;
  const card = document.querySelector(
    `.src-card[data-url="${CSS.escape(src.bookSourceUrl)}"][data-name="${CSS.escape(src.bookSourceName || '')}"]`
  );
  if (!card) return;
  const meta = card.querySelector('.src-meta');
  if (!meta) return;
  const old = card.querySelector('.src-last');
  const tag = lastCheckTag(src);
  if (tag) {
    const wrap = document.createElement('span');
    wrap.innerHTML = tag;
    const node = wrap.firstChild;
    if (old) old.replaceWith(node);
    else meta.append(node);
  } else if (old) {
    old.remove();
  }
}

function rowHtml(s) {
  const st = srcState(s);
  const sub = [s.bookSourceGroup || '未分组', s.searchUrl && s.exploreUrl ? '搜索/发现' : s.searchUrl ? '仅搜索' : s.exploreUrl ? '仅发现' : '仅登录态'].join(' · ');
  return `
    <div class="src-card res-${st.key}" data-url="${esc(s.bookSourceUrl)}" data-name="${esc(s.bookSourceName || '')}">
      <div class="sw-acts">
        <button class="sw-act" data-act="more" title="更多">更多</button>
        <button class="sw-act danger" data-act="del" title="删除">删除</button>
      </div>
      <div class="sw-face">
        <span class="src-chk" data-act="chk" title="选择"></span>
        <div class="src-main">
          <b class="src-name">${esc(s.bookSourceName || '(未命名)')}</b>
          <div class="src-meta">
            <span class="src-sub">${esc(sub)}</span>
            ${statusTag(s)}${lastCheckTag(s)}
          </div>
        </div>
        <label class="switch" title="启用/禁用">
          <input type="checkbox" data-act="toggle" ${s.enabled ? 'checked' : ''}>
          <span></span>
        </label>
      </div>
    </div>`;
}

/**
 * 一个书源的综合状态。深度校验结果优先（它比快速校验更权威）：
 *   深度通过 → 「深度通过」（可能是快速误判，救回来了）
 *   深度失败 + 快速失败 → 「两次失效」（可安全清理）
 *   深度失败 + 快速通过 → 「深度失效」
 */
function srcState(s) {
  const u = s.bookSourceUrl;
  const d = deepResults.get(u);
  if (d && d.state === 'run') return { key: 'run', label: '深度校验中', cls: 'ck-run', title: '' };
  if (d && d.state === 'ok') {
    return { key: 'deepok', label: '深度通过', cls: 'ck-ok', title: `四步全通过 · ${Math.round((d.ms || 0) / 1000)}s` };
  }
  if (d && d.state === 'bad') {
    const both = checkResults.get(u) === 'bad';
    return { key: 'deepbad', label: both ? '两次失效' : '深度失效', cls: 'ck-bad', title: `${d.step || ''}：${d.reason || ''}` };
  }
  const q = checkResults.get(u);
  if (q === 'pending') return { key: 'run', label: '排队中', cls: 'ck-run', title: '' };
  if (q === 'ok') return { key: 'ok', label: '有效', cls: 'ck-ok', title: '' };
  if (q === 'bad') return { key: 'bad', label: '失效', cls: 'ck-bad', title: failReasons.get(u) || '' };
  if (q === 'run') return { key: 'run', label: '校验中', cls: 'ck-run', title: '' };
  if (q === 'skip') return { key: 'skip', label: '未判定', cls: 'ck-skip', title: failReasons.get(u) || '' };
  if (q === 'nosearch') return { key: 'nosearch', label: '无搜索', cls: 'ck-skip', title: '该源没有搜索地址，不参与搜索校验' };
  return { key: 'none', label: '未校验', cls: 'ck-none', title: '' };
}

function statusTag(s) {
  const st = srcState(s);
  const t = st.title ? ` title="${esc(st.title)}"` : '';
  return `<span class="tag ${st.cls}"${t}><i class="sdot"></i>${st.label}</span>`;
}

/** 批量/单源校验时只刷新状态，不整表重画（避免打断滑动与多选） */
function updateRowStatus(url, name) {
  const card = document.querySelector(`.src-card[data-url="${CSS.escape(url)}"][data-name="${CSS.escape(name || '')}"]`);
  if (!card) return;
  const src = state.sources.find((s) => s.bookSourceUrl === url && (s.bookSourceName || '') === (name || ''));
  if (!src) return;
  const st = srcState(src);
  card.classList.remove('res-none', 'res-ok', 'res-bad', 'res-skip', 'res-nosearch', 'res-run', 'res-deepok', 'res-deepbad');
  card.classList.add('res-' + st.key);
  const meta = card.querySelector('.src-meta');
  const old = meta && meta.querySelector('.tag');
  if (old) old.remove();
  if (meta) meta.insertAdjacentHTML('beforeend', statusTag(src));
}

function bindRows(wrap) {
  // 先建索引：原来每张卡都 state.sources.find() 一遍，134 条就是 1.8 万次字符串比较，
  // 每次重绘都白烧一遍。Map 一次建好，逐卡 O(1) 取。
  const byKey = new Map();
  for (const s of state.sources) byKey.set(s.bookSourceUrl + '\u0000' + (s.bookSourceName || ''), s);
  wrap.querySelectorAll('.src-card').forEach((card) => {
    const src = byKey.get(card.dataset.url + '\u0000' + card.dataset.name);
    if (!src) return;
    const k = keyOf(src);

    card.querySelector('[data-act=toggle]').onchange = async (e) => {
      src.enabled = e.target.checked;
      await db.put('sources', src);
      toast(src.enabled ? `已启用「${src.bookSourceName}」` : `已禁用「${src.bookSourceName}」`);
      const oEl = $('#src-on');
      if (oEl) oEl.textContent = enabledSources().length;
    };
    card.querySelector('[data-act=chk]').onclick = (e) => {
      e.stopPropagation();
      toggleSel(src);
    };
    // 单条删除（含左滑删除）也要确认：滑手滑错不说话就删掉，代价太大
    card.querySelector('[data-act=del]').onclick = async (e) => {
      e.stopPropagation();
      if (!(await confirmBox(`删除书源「${src.bookSourceName || src.bookSourceUrl}」？此操作不可撤销。`))) return;
      await delSources([src]);
    };
    card.querySelector('[data-act=more]').onclick = (e) => {
      e.stopPropagation();
      openDetail(src);
    };

    // 点卡片（非控件）→ 详情抽屉；多选态下改为切换选中
    const face = card.querySelector('.sw-face');
    let holdFiredAt = 0; // 长按刚触发：随后那次 click 要忽略，否则立刻又把选中取消了
    face.onclick = (e) => {
      if (e.target.closest('button,label,input,.src-chk')) return;
      if (card.dataset.swiped === '1') {
        card.dataset.swiped = ''; // 刚滑动完的抬手，不当成点击
        return;
      }
      if (Date.now() - holdFiredAt < 700) return;
      if (selectMode) toggleSel(src);
      else openDetail(src);
    };

    // 长按进入多选态（并选中该条）
    let holdT = null;
    face.addEventListener('pointerdown', () => {
      holdT = setTimeout(() => {
        holdFiredAt = Date.now();
        if (!selectMode) enterSelect();
        toggleSel(src, true);
      }, 450);
    });
    const clearHold = () => {
      if (holdT) clearTimeout(holdT);
      holdT = null;
    };
    face.addEventListener('pointerup', clearHold);
    face.addEventListener('pointermove', clearHold);
    face.addEventListener('pointercancel', clearHold);

    bindSwipe(card);
  });
}

/* ---------- swipe：左滑露出「更多 / 删除」 ----------
 * v0.6.50 修复「只抖一下就弹回」：真机上横向拖动会被浏览器判成滚动手势，
 * pointermove 收到两三帧就 pointercancel → 回弹。三处一起改：
 *   1) .src-card 设 touch-action:pan-y（横向手势不再被浏览器接管）
 *   2) 判定为横向拖动后才 setPointerCapture（按压即捕获会把 click 改派到卡片上，开关就点不动了）
 *   3) 命中判定从 40% 放宽到 35%，并支持「滑开一张自动收起上一张」
 */
const SW_W = 132; // 露出宽度
let openedSwipe = null; // 当前滑开的卡片：{ reset }

function bindSwipe(card) {
  const face = card.querySelector('.sw-face');
  let sx = 0;
  let sy = 0;
  let dx = 0;
  let pid = null;
  let open = false;
  let moved = false;
  let axis = 0; // 0 未定 / 1 横向 / 2 纵向

  const setX = (x, anim) => {
    face.style.transition = anim ? 'transform .22s cubic-bezier(.22,1,.36,1)' : 'none';
    face.style.transform = x ? `translateX(${x}px)` : '';
  };
  const detachDoc = () => {
    document.removeEventListener('pointerdown', onDocOutside);
    window.removeEventListener('scroll', onScrollAway);
  };
  const reset = () => {
    open = false;
    setX(0, true);
    detachDoc();
    // 收起动画结束后再摘掉合成层提示，避免动画期间降级
    setTimeout(() => card.classList.remove('swiping'), 260);
    if (openedSwipe && openedSwipe.card === card) openedSwipe = null;
  };
  const onDocOutside = (ev) => {
    if (card.contains(ev.target)) return;
    reset();
  };
  const onScrollAway = () => {
    if (open) reset();
  };

  card.addEventListener('pointerdown', (e) => {
    if (selectMode || (e.pointerType === 'mouse' && e.button > 0)) return;
    if (openedSwipe && openedSwipe.card !== card) openedSwipe.reset();
    sx = e.clientX;
    sy = e.clientY;
    dx = 0;
    moved = false;
    axis = 0;
    pid = e.pointerId;
  });

  card.addEventListener('pointermove', (e) => {
    if (pid == null || e.pointerId !== pid || selectMode) return;
    const mx = e.clientX - sx;
    const my = e.clientY - sy;
    if (!axis) {
      if (Math.abs(mx) < 8 && Math.abs(my) < 8) return;
      if (Math.abs(my) > Math.abs(mx)) {
        axis = 2; // 竖向滚动：让浏览器拿走，别抢
        pid = null;
        return;
      }
      axis = 1;
      card.classList.add('swiping'); // 仅拖动期间提升为合成层
      // 判定为横向拖动后再捕获指针。触摸指针本来就带隐式捕获（事件照旧冒泡到卡片），
      // 再显式 setPointerCapture 反而会在子节点上触发 lostpointercapture，把手势打断在半路。
      if (e.pointerType !== 'touch') {
        try {
          card.setPointerCapture(pid);
        } catch {}
      }
    }
    moved = true;
    card.dataset.swiped = '1'; // 抬手时抑制误触发的 click
    dx = Math.max(-SW_W, Math.min(0, (open ? -SW_W : 0) + mx));
    setX(dx, false);
  });

  const end = () => {
    if (pid == null) return;
    const id = pid;
    pid = null;
    try {
      if (card.hasPointerCapture && card.hasPointerCapture(id)) card.releasePointerCapture(id);
    } catch {}
    if (!moved) return;
    open = dx < -SW_W * 0.35;
    setX(open ? -SW_W : 0, true);
    setTimeout(() => card.classList.remove('swiping'), 260);
    if (open) {
      openedSwipe = { card, reset };
      // 本轮抬手不该立刻把自己关掉：下一轮事件循环再挂「点别处 / 滚动即收起」
      setTimeout(() => {
        if (!open) return;
        document.addEventListener('pointerdown', onDocOutside);
        window.addEventListener('scroll', onScrollAway, { passive: true });
      }, 0);
    }
  };
  card.addEventListener('pointerup', end);
  card.addEventListener('pointercancel', end);
}

/* ---------- 多选态 + 底部浮动操作栏 ---------- */

function enterSelect() {
  selectMode = true;
  document.body.classList.add('src-selecting');
  updateSelBar();
}

function exitSelect() {
  selectMode = false;
  selected.clear();
  document.body.classList.remove('src-selecting');
  const fab = $('#src-fab');
  if (fab) fab.hidden = true;
  updateSelBar();
  $$('.src-card .src-chk.on').forEach((e) => e.classList.remove('on'));
}

function toggleSel(src, forceOn = false) {
  if (!selectMode) enterSelect();
  const k = keyOf(src);
  if (selected.has(k) && !forceOn) selected.delete(k);
  else selected.add(k);
  const card = document.querySelector(`.src-card[data-url="${CSS.escape(src.bookSourceUrl)}"][data-name="${CSS.escape(src.bookSourceName || '')}"]`);
  if (card) {
    const chk = card.querySelector('.src-chk');
    if (chk) chk.classList.toggle('on', selected.has(k));
  }
  updateSelBar();
}

function selSources() {
  return state.sources.filter((s) => selected.has(keyOf(s)));
}

/** 进入多选态并全选「当前筛选结果」（┇ 菜单入口与多选条上的「全选」共用） */
function selectAllFiltered() {
  enterSelect();
  state.sources.filter(applyFilter).forEach((s) => selected.add(keyOf(s)));
  $$('.src-card').forEach((c) => {
    const s = state.sources.find((x) => x.bookSourceUrl === c.dataset.url && (x.bookSourceName || '') === c.dataset.name);
    const chk = c.querySelector('.src-chk');
    if (chk && s) chk.classList.toggle('on', selected.has(keyOf(s)));
  });
  updateSelBar();
  toast(`已全选 ${selected.size} 个书源`);
}

function updateSelBar() {
  const bar = $('#src-selbar');
  const fab = $('#src-fab');
  if (!bar) return;
  bar.hidden = !selectMode;
  if (fab) fab.hidden = !selectMode || !selected.size;
  if (!selectMode) return;
  const n = selected.size;
  bar.innerHTML = '';
  bar.append(h('span', { class: 'ss-n' }, `已选 ${n}`));
  const all = h('button', { class: 'ss-a', type: 'button' }, '全选');
  all.onclick = selectAllFiltered;
  const none = h('button', { class: 'ss-a', type: 'button' }, '取消');
  none.onclick = exitSelect;
  bar.append(all, none);
  paintFab();
}

function paintFab() {
  const fab = $('#src-fab');
  if (!fab) return;
  const acts = [
    { t: '启用', g: '◉', run: async () => {
        const list = selSources();
        for (const s of list) if (!s.enabled) { s.enabled = true; await db.put('sources', s); }
        await reloadSources();
        toast(`已启用 ${list.length} 个书源`);
        afterBatch();
      } },
    { t: '禁用', g: '◌', run: async () => {
        const list = selSources();
        for (const s of list) if (s.enabled) { s.enabled = false; await db.put('sources', s); }
        await reloadSources();
        toast(`已禁用 ${list.length} 个书源`);
        afterBatch();
      } },
    { t: '校验', g: '✓', run: () => {
        const list = selSources();
        exitSelect();
        runCheckAll('我的', list, { autoDeep: false });
      } },
    { t: '分组', g: '▤', run: () => {
        const list = selSources();
        if (!list.length) return;
        openGroupModal(list[0], list);
      } },
    { t: '删除', g: '🗑', danger: true, run: async () => {
        const list = selSources();
        if (!list.length) return;
        const names = list.map((s) => `「${s.bookSourceName || s.bookSourceUrl}」`);
        if (!(await confirmBox(`将删除 ${names.length} 个书源（${names.slice(0, 6).join('、')}${names.length > 6 ? ` 等 ${names.length} 个` : ''}），此操作不可撤销。`))) return;
        await delSources(list);
        exitSelect();
      } },
    { t: '导出', g: '↧', run: () => {
        exportSources(selSources());
      } },
  ];
  fab.innerHTML = '';
  for (const a of acts) {
    fab.append(
      h(
        'button',
        { class: 'sf-b' + (a.danger ? ' danger' : ''), type: 'button', onclick: a.run },
        h('span', { class: 'sf-g' }, a.g),
        h('span', {}, a.t)
      )
    );
  }
}

async function afterBatch() {
  await reloadSources();
  const oEl = $('#src-on');
  if (oEl) oEl.textContent = enabledSources().length;
  renderList();
}

/* ---------- 详情抽屉 ---------- */

function openDetail(src) {
  $$('.src-dmask, .src-drawer').forEach((e) => e.remove());
  const mask = h('div', { class: 'src-dmask' });
  const drawer = h('div', { class: 'src-drawer' });
  const st = srcState(src);
  const d = deepResults.get(src.bookSourceUrl);
  const reason = failReasons.get(src.bookSourceUrl) || '';
  const rules = [
    s2('搜索', !!src.searchUrl),
    s2('发现', !!src.exploreUrl),
    s2('目录', !!(src.ruleBookInfo && (src.ruleBookInfo.tocUrl || src.ruleBookInfo.init))),
    s2('正文', !!(src.ruleContent && src.ruleContent.content)),
  ];

  drawer.append(
    h('div', { class: 'sd-head' },
      h('div', {}, h('b', {}, src.bookSourceName || '(未命名)'), h('small', {}, src.bookSourceGroup || '未分组')),
      h('button', { class: 'dialog-x', title: '关闭', onclick: close }, '✕')
    )
  );
  drawer.append(h('div', { class: 'sd-url' }, esc(src.bookSourceUrl)));
  const stBox = h('div', { class: 'sd-sec' }, h('p', { class: 'sd-k' }, '校验状态'));
  stBox.append(h('p', { class: 'sd-v' }, st.label));
  drawer.append(stBox);
  if (reason) {
    const rb = h('div', { class: 'sd-sec bad' }, h('p', { class: 'sd-k' }, '快速校验失败原因'));
    rb.append(h('p', { class: 'sd-v' }, esc(reason)));
    drawer.append(rb);
  }
  if (d && (d.state === 'ok' || d.state === 'bad')) {
    const db = h('div', { class: 'sd-sec' + (d.state === 'bad' ? ' bad' : '') });
    db.append(h('p', { class: 'sd-k' }, '深度校验（四步）'));
    db.append(h('p', { class: 'sd-v' }, d.state === 'ok' ? `全部通过 · ${Math.round((d.ms || 0) / 1000)}s` : `卡在「${d.step}」：${d.reason}`));
    drawer.append(db);
  }
  const rBox = h('div', { class: 'sd-sec' }, h('p', { class: 'sd-k' }, '规则摘要'));
  rBox.append(h('p', { class: 'sd-v' }, rules.join('  ')));
  drawer.append(rBox);

  const mk = (label, cls, fn) => {
    const b = h('button', { class: 'sd-btn ' + cls, type: 'button' }, label);
    b.onclick = fn;
    return b;
  };
  drawer.append(
    mk('编辑规则', '', () => { close(); openEditModal(src); }),
    mk('移动分组', '', () => { close(); openGroupModal(src); }),
    mk('深度校验（四步）', '', () => { close(); openCheckModal(src); }),
    mk('删除书源', 'danger', async () => {
      close();
      if (!(await confirmBox(`删除书源「${src.bookSourceName}」？此操作不可撤销。`))) return;
      await delSources([src]);
    })
  );
  document.body.append(mask, drawer);
  // 返回键第一站关掉抽屉：以前没注册，按返回会 goBack 回到上一个 tag 页
  const unBack = registerBack(() => {
    close();
    return true;
  });
  requestAnimationFrame(() => {
    mask.classList.add('open');
    drawer.classList.add('open');
  });
  mask.onclick = close;
  let closed = false;
  function close() {
    if (closed) return;
    closed = true;
    unBack();
    mask.classList.remove('open');
    drawer.classList.remove('open');
    setTimeout(() => {
      mask.remove();
      drawer.remove();
    }, 240);
  }
}

const s2 = (k, on) => `${k}${on ? '✓' : '✗'}`;

/* ---------- 删除 / 导出 ---------- */

async function delSources(list) {
  for (const s of list) {
    await db.del('sources', s.id);
    selected.delete(keyOf(s));
  }
  await reloadSources();
  toast(`已删除 ${list.length} 个书源`);
  renderList();
  updateSelBar();
}

function exportSources(list) {
  if (!list.length) return toast('没有选中书源');
  const data = JSON.stringify(list.map((s) => ({ ...s, id: undefined })), null, 2);
  toast(saveFile('modou-sources.json', data, 'application/json') ? `已导出 ${list.length} 个书源` : '导出失败');
}

/* ---------- 导入 ---------- */

function openImportSheet() {
  const body = h('div', { class: 'opt-list' });
  const items = [
    { label: '本地文件', desc: '从手机选择书源 JSON 文件', run: () => $('#file-input').click() },
    { label: '网络地址', desc: '输入书源 JSON 的链接', run: () => openImportModal('从网络地址导入书源 JSON', 'url') },
    { label: '粘贴 JSON', desc: '粘贴单个对象或数组', run: () => openImportModal('粘贴书源 JSON', 'paste') },
  ];
  // 先建面板再挂选项：onclick 用的是 sheet（含 close），不是 DOM 节点
  const sheet = openSheet('导入书源', body);
  for (const it of items) {
    body.append(
      h(
        'button',
        { class: 'opt-item', onclick: () => { sheet.close(); it.run(); } },
        h('span', { class: 'opt-tx' }, h('b', {}, it.label), h('small', {}, it.desc))
      )
    );
  }
}

export function parseSourcesJson(text) {
  let j = JSON.parse(text);
  if (!Array.isArray(j)) j = [j];
  if (!j.length) throw new Error('空数组');
  return j.map((o) => {
    if (!o || typeof o !== 'object' || !o.bookSourceUrl) throw new Error('缺少 bookSourceUrl 字段');
    return BookSource.fromJson(o);
  });
}

export async function importSources(list) {
  if (!list.length) {
    toast('没有可导入的书源');
    return;
  }
  const rows = list.map((s) => ({ ...s, id: s.id, enabled: s.enabled !== false }));
  await db.putMany('sources', rows);
  await reloadSources();
  toast(`导入 ${rows.length} 个书源`);
  renderList();
}

function openImportModal(title, mode) {
  const body = h('div', { class: 'form' });
  const ta = h('textarea', { class: 'json-input', rows: 12, placeholder: '[\n  {\n    "bookSourceName": "...",\n    "bookSourceUrl": "https://...",\n    "searchUrl": "...",\n    "ruleSearch": { "bookList": "..." }\n  }\n]' });
  if (mode === 'url') {
    const inp = h('input', { type: 'url', placeholder: 'https://example.com/shuyuan.json' });
    const btn = h('button', { class: 'btn primary' }, '拉取并导入');
    const log = h('p', { class: 'muted' });
    btn.onclick = async () => {
      btn.disabled = true;
      log.textContent = '请求中…';
      try {
        const r = await fetch('/proxy?' + new URLSearchParams({ url: inp.value.trim() }));
        const j = await r.json();
        if (j.ok === false) throw new Error(j.error || '代理错误');
        await importSources(parseSourcesJson(j.text));
        close();
      } catch (e) {
        log.textContent = '失败：' + e.message;
        btn.disabled = false;
      }
    };
    body.append(inp, btn, log);
  } else {
    const btn = h('button', { class: 'btn primary' }, '导入');
    const log = h('p', { class: 'muted' });
    btn.onclick = async () => {
      try {
        await importSources(parseSourcesJson(ta.value));
        close();
      } catch (e) {
        log.textContent = '解析失败：' + e.message;
      }
    };
    body.append(ta, btn, log);
  }
  const { close } = openModal(title, body, { wide: true });
}

async function loadSamples() {
  if (state.sources.some((s) => s.bookSourceGroup === '示例')) {
    toast('示例书源已存在');
    return;
  }
  try {
    const r = await fetch('js/assets/sample-sources.json');
    await importSources(parseSourcesJson(await r.text()));
  } catch (e) {
    toast('示例加载失败：' + e.message);
  }
}

function exportAll() {
  const data = JSON.stringify(state.sources.map((s) => ({ ...s, id: undefined })), null, 2);
  toast(saveFile('modou-sources.json', data, 'application/json') ? `已导出 ${state.sources.length} 个书源` : '导出失败');
}

/* ---------- 编辑 / 分组 ---------- */

function openEditModal(src) {
  const body = h('div', { class: 'form' });
  const ta = h('textarea', { class: 'json-input', rows: 16 });
  ta.value = JSON.stringify({ ...src, id: undefined }, null, 2);
  const log = h('p', { class: 'muted' });
  const btn = h(
    'button',
    {
      class: 'btn primary',
      onclick: async () => {
        try {
          const [obj] = parseSourcesJson(ta.value);
          await db.put('sources', { ...obj, id: src.id, enabled: obj.enabled !== false });
          await reloadSources();
          toast('已保存');
          close();
          renderList();
        } catch (e) {
          log.textContent = '保存失败：' + e.message;
        }
      },
    },
    '保存'
  );
  body.append(ta, btn, log);
  const { close } = openModal(`编辑书源 · ${src.bookSourceName || src.bookSourceUrl}`, body, { wide: true });
}

/** 分组：单个源直接设；多选态下传入 list 表示批量归入同一分组 */
function openGroupModal(src, list = null) {
  const body = h('div', { class: 'form' });
  const curGroup = String(src.bookSourceGroup || '').split(/[,，]/)[0].trim();
  const inp = h('input', { placeholder: '分组名称（留空 = 未分组）', value: curGroup });
  const groups = [
    ...new Set(state.sources.map((s) => String(s.bookSourceGroup || '').split(/[,，]/)[0].trim()).filter(Boolean)),
  ];
  const chips = h('div', { class: 'chips' });
  for (const g of groups) chips.append(h('button', { class: 'chip-btn', type: 'button', onclick: () => (inp.value = g) }, g));
  const log = h('p', { class: 'muted small' }, list ? `将把选中的 ${list.length} 个书源归入该分组。` : '用于搜索页按组选择书源；Legado 多分组只取第一个。');
  const btn = h('button', { class: 'btn primary' }, '保存');
  btn.onclick = async () => {
    const g = inp.value.trim();
    const targets = list || [src];
    for (const t of targets) await db.put('sources', { ...t, id: t.id, bookSourceGroup: g });
    await reloadSources();
    toast(g ? `已归入分组「${g}」` : '已设为未分组');
    close();
    if (list) exitSelect();
    renderList();
  };
  body.append(chips, inp, log, btn);
  const { close } = openModal(list ? `批量分组（${list.length} 个）` : `设置分组 · ${src.bookSourceName || src.bookSourceUrl}`, body);
}

/* ---------- 书源体检（快速校验 → 深度校验 → 一键清理） ----------
 * 用户真正要的是一条链，而不是三个按钮：
 *   ① 全体跑一次轻量搜索校验 → 挑出「不合格」
 *   ② 只对不合格的跑四步全链路（搜索→详情→目录→正文）
 *   ③ 两次都不合格 = 基本可以确认是死源 → 一键删掉
 * 进度条按 checkUI 状态整体重绘：中途离开书源页再回来，进度不会丢。
 */

const checkUI = {
  stage: 'idle', // idle | quick | quickdone | stopped | deep | done
  keyword: '',
  cur: '',
  active: [],
  quick: { done: 0, total: 0, ok: 0, bad: 0 },
  deep: { done: 0, total: 0, pass: 0, fail: 0 },
};

function mkStopBtn() {
  const b = h('button', { class: 'mini', type: 'button' }, checkAllStop ? '停止中…' : '停止');
  b.disabled = checkAllStop;
  b.onclick = () => {
    checkAllStop = true;
    // 立即断掉在途 fetch，而不是等 12s 单请求超时；worker 会在 catch 里退出。
    try { checkToken && checkToken.ctrl.abort(); } catch (e) { /* 已结束 */ }
    b.disabled = true;
    b.textContent = '停止中…';
    paintCheckBar();
  };
  return b;
}

const mkMini = (label, cls, fn) => h('button', { class: 'mini ' + cls, type: 'button', onclick: fn }, label);

/** 两次都不合格：快速判失效 + 四步全链路也没跑通 */
function bothBadList() {
  return state.sources.filter(
    (s) => checkResults.get(s.bookSourceUrl) === 'bad' && (deepResults.get(s.bookSourceUrl) || {}).state === 'bad'
  );
}

/** 体检进度条：唯一绘制入口（状态在 checkUI，换页重绘也不丢） */
function paintCheckBar() {
  const bar = $('#checkall-bar');
  if (!bar) return;
  const st = checkUI.stage;
  if (st === 'idle') {
    bar.hidden = true;
    bar.classList.remove('running');
    return;
  }
  bar.hidden = false;
  const busy = st === 'quick' || st === 'deep';
  bar.classList.toggle('running', busy);
  const q = checkUI.quick;
  const d = checkUI.deep;
  let head = '';
  let subText = '';
  let prog = null;
  const acts = [];

  if (st === 'quick') {
    head = `① 快速校验 <b>${q.done}</b>/${q.total} · 有效 <b class="c-ok">${q.ok}</b> · 失效 <b class="c-bad">${q.bad}</b>`;
    subText = checkUI.cur || '搜索 → 详情 → 目录，单源总预算 45s';
    prog = q.total ? q.done / q.total : 0;
    acts.push(mkStopBtn());
  } else if (st === 'deep') {
    head = `② 深度校验 <b>${d.done}</b>/${d.total} · 通过 <b class="c-ok">${d.pass}</b> · 失败 <b class="c-bad">${d.fail}</b>`;
    subText = checkUI.cur || '搜索 → 详情 → 目录 → 正文×2，单源总预算 45s';
    prog = d.total ? d.done / d.total : 0;
    acts.push(mkStopBtn());
  } else if (st === 'quickdone' || st === 'stopped') {
    head = `${st === 'stopped' ? '已停止' : '① 快速校验完成'} ${q.done}/${q.total} · 有效 <b class="c-ok">${q.ok}</b> · 失效 <b class="c-bad">${q.bad}</b>`;
    const badList = state.sources.filter((s) => checkResults.get(s.bookSourceUrl) === 'bad');
    if (badList.length) {
      subText = '深度校验会逐个跑 搜索→详情→目录→正文，比快速校验慢，可随时停止';
      acts.push(mkMini(`② 深度校验失效源（${badList.length}）`, 'primary', () => runDeepOnly(badList, checkUI.keyword)));
      acts.push(mkMini('只看失效', '', () => {
        srcFilter.check = srcFilter.check === 'bad' ? 'all' : 'bad';
        renderChips();
        renderList();
        paintCheckBar();
      }));
    } else {
      subText = '全部可用 🎉 没有需要深度校验的源';
    }
    acts.push(mkMini('导出报告', '', () => exportCheckReport(checkUI.keyword)));
  } else if (st === 'done') {
    head = `体检完成 ${d.done}/${d.total} · 深度通过 <b class="c-ok">${d.pass}</b> · 深度失败 <b class="c-bad">${d.fail}</b>`;
    const bothBad = bothBadList();
    if (bothBad.length) {
      subText = `两次都不合格 ${bothBad.length} 个：快速搜索没结果，四步全链路也没跑通`;
      acts.push(mkMini(`③ 一键删除（${bothBad.length}）`, 'danger', () => deleteBothBad(bothBad)));
      acts.push(mkMini('只看两次不合格', '', () => {
        srcFilter.check = 'both';
        renderChips();
        renderList();
        paintCheckBar();
      }));
    } else {
      subText = d.fail ? '深度失败的源不在快速失效列表里，没有可安全清理的' : '没有两次都不合格的源，全部可用 🎉';
    }
    acts.push(mkMini('导出报告', '', () => exportCheckReport(checkUI.keyword)));
  }

  bar.innerHTML = '';
  bar.append(h('div', { class: 'checkall-line', html: head }));
  if (subText) bar.append(h('div', { class: 'checkall-sub' }, subText));
  if (prog != null) {
    const fill = h('i');
    fill.style.width = (prog * 100).toFixed(1) + '%';
    bar.append(h('div', { class: 'checkall-prog' }, fill));
  }
  if (acts.length) bar.append(h('div', { class: 'checkall-acts' }, ...acts));
}

function openCheckAllSheet() {
  if (!state.sources.length) return toast('还没有书源');
  if (checkAllRunning) return toast('体检进行中');
  const body = h('div', { class: 'form' });
  body.append(
    h('p', { class: 'muted small' },
      `对 ${state.sources.length} 个书源先各跑一次「搜索→详情→目录」（快），再只对失败的补跑正文深度校验（最多两章）。单源 45 秒、4 并发，可随时停止。最后列出「两次都不合格」的源，可一键删除。`)
  );
  const kw = h('input', { value: '我的', placeholder: '校验关键词' });
  const auto = h('input', { type: 'checkbox', checked: true });
  const autoRow = h('label', { class: 'check-opt' }, auto,
    h('span', {}, h('b', {}, '自动接着深度校验'), h('small', {}, '关掉则先看快速结果，再手动决定要不要深度校验'))
  );
  // 校验档位（对齐 Legado checkSearch/checkInfo/checkToc/checkContent）：默认全开
  const tierRow = h('div', { class: 'tier-row' });
  const tdefs = [['checkSearch', '搜索'], ['checkInfo', '详情'], ['checkToc', '目录'], ['checkContent', '正文']];
  const tboxes = {};
  for (const [k, label] of tdefs) {
    const c = h('input', { type: 'checkbox', class: 'tier-opt', checked: true, 'data-tier': k });
    tboxes[k] = c;
    tierRow.append(h('label', { class: 'tier-opt' }, c, h('span', {}, label)));
  }
  tierRow.append(h('span', { class: 'muted small' }, '勾选本次体检要跑的档位（搜索 / 详情 / 目录 / 正文）'));
  const go = h('button', { class: 'btn primary', style: 'width:100%;justify-content:center;margin-top:12px', type: 'button' }, '开始体检');
  go.onclick = () => {
    const word = kw.value.trim();
    if (!word) return toast('先输入关键词');
    const tiers = {};
    for (const k in tboxes) tiers[k] = tboxes[k].checked;
    sheet.close();
    runCheckAll(word, null, { autoDeep: auto.checked, tiers });
  };
  body.append(kw, autoRow, tierRow, go);
  const sheet = openSheet('书源体检', body);
}

/* ---------- 体检执行 ---------- */

function newCheckToken() {
  const ctrl = new AbortController();
  return { ctrl, signal: ctrl.signal, active: new Set(), cancelled: false };
}

function abortToken(token) {
  if (!token) return;
  token.cancelled = true;
  try { token.ctrl.abort(); } catch (e) { /* 已结束 */ }
}

/** 单源总预算：超时就 abort 本源的请求，worker 继续下一个 */
function runWithDeadline(fn, ms, ctrl) {
  return new Promise((resolve, reject) => {
    let done = false;
    const timer = setTimeout(() => {
      if (done) return;
      done = true;
      try { ctrl.abort(); } catch (e) { /* 已结束 */ }
      reject(new Error('超时'));
    }, ms);
    Promise.resolve()
      .then(fn)
      .then(
        (v) => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          resolve(v);
        },
        (e) => {
          if (done) return;
          done = true;
          clearTimeout(timer);
          reject(e);
        }
      );
  });
}

function activeText(token) {
  if (!token || !token.active.size) return '';
  return '正在：' + [...token.active].join('、');
}

function paintActive(token) {
  checkUI.active = token ? [...token.active] : [];
  checkUI.cur = activeText(token);
}

function reasonOf(e, step) {
  const m = brief(e);
  if (/超时|timeout/i.test(m)) return `${step}超时`;
  if (/取消|cancel|abort/i.test(m)) return '已取消';
  if (/403|401/.test(m)) return `${step}被拒绝（HTTP 401/403，可能反爬）`;
  if (/404/.test(m)) return `${step}地址失效（HTTP 404）`;
  return `${step}：${m || '未知错误'}`;
}

/**
 * 单源全链路。
 *
 * quick = 搜索 → 详情 → 目录（Legado 默认也会校验 info/category；只跑搜索会
 *         把「能搜到但详情/目录全坏」的源误判为有效，用户点进去才发现打不开）。
 * deep  = 再加前两章正文，专抓 nextContentUrl / 第二章起规则失效。
 */
async function checkOne(src, mode, keyword, token, tiers) {
  const t0 = Date.now();
  const cfg = { checkSearch: true, checkInfo: true, checkToc: true, checkContent: true, ...(tiers || {}) };
  const tierRows = [];
  const tier = (name, ok, reason) => tierRows.push({ name, ok: !!ok, reason: reason || '' });
  const tierSnapshot = () => [...tierRows];
  const fail = (step, reason) => ({ state: 'bad', step, reason, ms: Date.now() - t0, tiers: tierSnapshot() });
  const local = new AbortController();
  const onGlobalAbort = () => { try { local.abort(); } catch (e) { /* 已结束 */ } };
  if (token) token.signal.addEventListener('abort', onGlobalAbort, { once: true });
  src.setRequestPolicy({ timeout: CHECK_REQUEST_TIMEOUT_MS, retries: 0, signal: local.signal });
  const logs = [];
  const diag = {};
  try {
    const work = async () => {
      if (!src.searchUrl) {
        tier('搜索', false, '没有 searchUrl');
        return { state: 'nosearch', step: '搜索', reason: '没有 searchUrl，不参与搜索校验', ms: Date.now() - t0, tiers: tierSnapshot() };
      }
      const cached = mode === 'deep' ? checkArtifacts.get(src.bookSourceUrl) : null;
      if (cached && cached.keyword === keyword) {
        // 快速校验已经拿到详情/目录：深度只补正文，少打两次请求；档位结论直接复用
        if (!tierRows.length && cached.tiers) tierRows.push(...cached.tiers);
        if (mode !== 'deep' || !cfg.checkContent) {
          return { state: 'ok', step: '搜索 / 详情 / 目录', reason: '', ms: Date.now() - t0, tiers: tierSnapshot() };
        }
        const cc = await checkContentChapters(src, cached.info, cached.chapters, logs, t0);
        tier('正文', cc.ok, cc.reason);
        if (!cc.ok) return fail('正文', cc.reason);
        return { state: 'ok', step: '搜索 / 详情 / 目录 / 正文', reason: '', ms: Date.now() - t0, chars: cc.chars, tiers: tierSnapshot() };
      }
      if (mode === 'quick') checkArtifacts.delete(src.bookSourceUrl);

      // ① 搜索：即使「搜索」档没勾，也要用它拿第一本书，供详情/目录/正文档用
      const s = await src.search(keyword);
      diag.status = s.status || 0;
      diag.finalUrl = s.finalUrl || '';
      diag.snip = s.snip || '';
      diag.bytes = s.bytes || 0;
      diag.charset = s.charset || '';
      if (s.logs && s.logs.length) logs.push(...s.logs.slice(-12));
      const needBook = cfg.checkInfo || cfg.checkToc || cfg.checkContent;
      if (s.error || s.status === 403 || s.status === 401 || s.status === 404 || !Array.isArray(s.list) || !s.list.length) {
        const why = s.error
          ? reasonOf(s.error, '搜索')
          : s.status === 403 || s.status === 401
            ? `搜索被拒绝（HTTP ${s.status}，可能反爬）`
            : s.status === 404
              ? '搜索地址失效（HTTP 404）'
              : s.logs && s.logs.some((l) => /checkKeyWord/.test(l))
                ? '校验词未命中页面关键词（换词再试）'
                : (s.logs && s.logs.filter((l) => !/^GET /.test(l)).join(' | ')) || '返回 0 条（查 searchUrl / ruleSearch.bookList）';
        tier('搜索', false, why);
        if (!needBook) return { state: 'skip', step: '搜索', reason: '校验范围全关，取书失败', ms: Date.now() - t0, tiers: tierSnapshot() };
        return fail('搜索', why);
      }
      tier('搜索', true, '');
      const first = s.list[0];
      if (!first || !first.bookUrl) {
        tier('详情', false, '第一条结果缺少 bookUrl');
        return fail('详情', '第一条结果缺少 bookUrl');
      }

      // ② 详情：信息/目档需要 info；档没勾也静默取书
      if (cfg.checkInfo || cfg.checkToc) {
        const bi = await src.getBookInfo(first);
        if (bi.logs && bi.logs.length) logs.push(...bi.logs.slice(-8));
        if (bi.error || !bi.info) {
          const why = reasonOf(bi.error, '详情') || '详情页解析为空';
          tier('详情', false, why);
          return fail('详情', why);
        }
        tier('详情', true, '');
        const info = bi.info;

        // ③ 目录：正文档需要目录；档没勾也静默取
        if (cfg.checkToc || cfg.checkContent) {
          const toc = await src.getToc(info);
          if (toc.logs && toc.logs.length) logs.push(...toc.logs.slice(-12));
          if (toc.error || !toc.chapters || !toc.chapters.length) {
            const why = toc.error ? reasonOf(toc.error, '目录') : '未解析到章节（查 ruleBookInfo.tocUrl / ruleToc.chapterList）';
            tier('目录', false, why);
            return fail('目录', why);
          }
          tier('目录', true, '');
          // 只留前几章：给深度校验复用，也避免把几千章数组整场攥在内存里
          checkArtifacts.set(src.bookSourceUrl, {
            keyword,
            info,
            chapters: toc.chapters.slice(0, CHECK_CONTENT_CHAPTERS),
            tiers: tierSnapshot(),
          });
          if (mode !== 'deep' || !cfg.checkContent) {
            return { state: 'ok', step: cfg.toc ? '搜索 / 详情 / 目录' : '搜索 / 详情', reason: '', ms: Date.now() - t0, tiers: tierSnapshot() };
          }
          const cc = await checkContentChapters(src, info, toc.chapters, logs, t0);
          tier('正文', cc.ok, cc.reason);
          if (!cc.ok) return fail('正文', cc.reason);
          return { state: 'ok', step: '搜索 / 详情 / 目录 / 正文', reason: '', ms: Date.now() - t0, chars: cc.chars, tiers: tierSnapshot() };
        }
        return { state: 'ok', step: '搜索 / 详情', reason: '', ms: Date.now() - t0, tiers: tierSnapshot() };
      }
      // 详情/目录/正文全关：只按「搜索」判定
      return { state: 'ok', step: '搜索', reason: '', ms: Date.now() - t0, tiers: tierSnapshot() };
    };

    const r = await runWithDeadline(work, CHECK_TIMEOUT_MS, local);
    checkDiag.set(src.bookSourceUrl, { ...diag, logs: logs.slice(-30), ms: Date.now() - t0 });
    await persistCheck(src, r);
    return r;
  } catch (e) {
    checkDiag.set(src.bookSourceUrl, { ...diag, logs: logs.slice(-30), ms: Date.now() - t0 });
    if (token && token.cancelled) return { state: 'cancelled', step: '已取消', reason: '已取消', ms: Date.now() - t0 };
    if (String(e && e.message) === '超时') {
      const r = { state: 'bad', step: '超时', reason: `单源超过 ${CHECK_TIMEOUT_MS / 1000}s（搜索/详情/目录/正文未跑完）`, ms: Date.now() - t0, tiers: tierSnapshot() };
      await persistCheck(src, r);
      return r;
    }
    const r = fail('异常', reasonOf(e, '异常'));
    await persistCheck(src, r);
    return r;
  } finally {
    if (token) {
      try { token.signal.removeEventListener('abort', onGlobalAbort); } catch (e) { /* 已结束 */ }
    }
    src.clearRequestPolicy();
  }
}

/** 校验结果持久化：写回书源记录，下次进书源页直接显示上次结果（对齐 Legado 的校验历史） */
async function persistCheck(src, r) {
  if (!r || !src || !src.id) return;
  if (r.state === 'cancelled' || r.state === 'run') return;
  try {
    // 剥离请求期临时字段：cancelSignal 是 AbortSignal，IndexedDB 无法克隆；
    // 直接展开 src 会把整条写入变成异常（被上面 catch 吞掉 → 结果永远存不上）
    const lc = {
      t: Date.now(),
      state: r.state === 'nosearch' ? 'skip' : r.state,
      step: r.step || '',
      reason: r.reason || '',
      ms: r.ms || 0,
      tiers: Array.isArray(r.tiers) ? r.tiers : [],
    };
    src.lastCheck = lc; // 同步内存实例：paintLastCheck 立刻能读到，不用等重渲染
    const { cancelSignal: _cs, ...safe } = src;
    await db.put('sources', { ...safe, id: src.id, lastCheck: lc });
  } catch (e) {
    /* 持久化失败不阻塞校验 */
  }
  try {
    paintLastCheck(src);
  } catch (e) {
    /* 刷新卡片失败不阻塞校验 */
  }
}

/** 深度校验正文两章；抽出来给「快速校验产物复用」和完整链路共用 */
async function checkContentChapters(src, info, chapters, logs, t0) {
  const n = Math.min(CHECK_CONTENT_CHAPTERS, chapters ? chapters.length : 0);
  let chars = 0;
  for (let i = 0; i < n; i++) {
    const ch = chapters[i];
    const c = await src.getContent(info, ch);
    if (c.logs && c.logs.length) logs.push(...c.logs.slice(-8));
    if (c.error) return { ok: false, reason: reasonOf(c.error, i === 0 ? '正文' : `正文（第 ${i + 1} 章）`) };
    if (!c.content) return { ok: false, reason: i === 0 ? '正文为空（查 ruleContent.content）' : `正文（第 ${i + 1} 章）为空` };
    chars += c.content.length;
  }
  return { ok: true, reason: '', chars };
}

async function runCheckAll(keyword, only = null, opts = {}) {
  if (checkAllRunning) return toast('体检进行中');
  const sources = (only || [...state.sources]).filter(Boolean);
  if (!sources.length) return toast('没有可校验的书源');
  if (!$('#checkall-bar')) return;
  checkAllRunning = true;
  checkAllStop = false;
  const token = newCheckToken();
  checkToken = token;
  curTiers = { checkSearch: true, checkInfo: true, checkToc: true, checkContent: true, ...(opts.tiers || {}) };
  checkArtifacts.clear();
  try {
    await quickPass(sources, keyword, token);
    if (token.cancelled || checkAllStop) {
      checkUI.stage = 'stopped';
      return;
    }
    const badList = sources.filter((s) => checkResults.get(s.bookSourceUrl) === 'bad');
    if (!badList.length || !opts.autoDeep) {
      checkUI.stage = 'quickdone';
      return;
    }
    await deepPass(badList, keyword, token);
    checkUI.stage = token.cancelled ? 'stopped' : 'done';
  } finally {
    checkToken = null;
    checkAllRunning = false;
    checkArtifacts.clear();
    checkUI.active = [];
    checkUI.cur = '';
    paintCheckBar();
  }
}

/** ① 快速校验：搜索 → 详情 → 目录。不再只凭「搜到东西」判有效。 */
async function quickPass(sources, keyword, token) {
  checkUI.stage = 'quick';
  checkUI.keyword = keyword;
  checkUI.active = [];
  checkUI.cur = '';
  checkUI.quick = { done: 0, total: sources.length, ok: 0, bad: 0 };
  paintCheckBar();
  const q = checkUI.quick;

  for (const s of sources) {
    checkResults.delete(s.bookSourceUrl);
    deepResults.delete(s.bookSourceUrl);
    failReasons.delete(s.bookSourceUrl);
    checkResults.set(s.bookSourceUrl, 'pending');
  }
  renderList();

  const queue = [...sources];
  const worker = async () => {
    while (queue.length && !token.cancelled) {
      const s = queue.shift();
      if (!s) return;
      checkResults.set(s.bookSourceUrl, 'run');
      token.active.add(s.bookSourceName || s.bookSourceUrl);
      paintActive(token);
      updateRowStatus(s.bookSourceUrl, s.bookSourceName);
      paintCheckBar();

      const r = await checkOne(s, 'quick', keyword, token, curTiers);
      token.active.delete(s.bookSourceName || s.bookSourceUrl);
      paintActive(token);

      if (r.state === 'cancelled') {
        checkResults.set(s.bookSourceUrl, 'skip');
        failReasons.set(s.bookSourceUrl, '已取消');
      } else if (r.state === 'ok') {
        q.ok++;
        checkResults.set(s.bookSourceUrl, 'ok');
      } else if (r.state === 'skip') {
        checkResults.set(s.bookSourceUrl, 'skip');
        failReasons.set(s.bookSourceUrl, r.reason || '未判定');
      } else if (r.state === 'nosearch') {
        checkResults.set(s.bookSourceUrl, 'nosearch');
        failReasons.set(s.bookSourceUrl, r.reason || '没有 searchUrl');
      } else {
        q.bad++;
        checkResults.set(s.bookSourceUrl, 'bad');
        failReasons.set(s.bookSourceUrl, `${r.step || ''}：${r.reason || '未通过'}`.replace(/^：/, ''));
      }
      q.done++;
      updateRowStatus(s.bookSourceUrl, s.bookSourceName);
      paintCheckBar();
      await yieldUI(8);
    }
  };
  await Promise.all(Array.from({ length: Math.min(CHECK_THREADS, queue.length) }, worker));
  if (token.cancelled) {
    // 停止后队列里没跑的源不能永远停在「排队中」
    for (const s of sources) {
      if (checkResults.get(s.bookSourceUrl) === 'pending' || checkResults.get(s.bookSourceUrl) === 'run') {
        checkResults.set(s.bookSourceUrl, 'skip');
        failReasons.set(s.bookSourceUrl, '已停止，未校验');
        updateRowStatus(s.bookSourceUrl, s.bookSourceName);
      }
    }
  }
  checkUI.active = [];
  checkUI.cur = '';
}

async function runDeepOnly(list, keyword) {
  if (checkAllRunning) return toast('体检进行中');
  if (!$('#checkall-bar')) return;
  checkAllRunning = true;
  checkAllStop = false;
  const token = newCheckToken();
  checkToken = token;
  try {
    await deepPass(list, keyword, token);
    checkUI.stage = token.cancelled ? 'stopped' : 'done';
  } finally {
    checkToken = null;
    checkAllRunning = false;
    paintCheckBar();
  }
}

/** ② 深度校验：搜索 → 详情 → 目录 → 正文 ×2（复用快速校验的结果，少打两次请求） */
async function deepPass(list, keyword, token) {
  checkUI.stage = 'deep';
  checkUI.keyword = keyword;
  checkUI.active = [];
  checkUI.cur = '';
  checkUI.deep = { done: 0, total: list.length, pass: 0, fail: 0 };
  paintCheckBar();
  const d = checkUI.deep;

  const queue = [...list];
  const worker = async () => {
    while (queue.length && !token.cancelled) {
      const s = queue.shift();
      if (!s) return;
      deepResults.set(s.bookSourceUrl, { state: 'run' });
      token.active.add(s.bookSourceName || s.bookSourceUrl);
      paintActive(token);
      updateRowStatus(s.bookSourceUrl, s.bookSourceName);
      paintCheckBar();

      const r = await checkOne(s, 'deep', keyword, token, curTiers);
      token.active.delete(s.bookSourceName || s.bookSourceUrl);
      paintActive(token);
      if (r.state === 'cancelled') {
        deepResults.delete(s.bookSourceUrl);
        checkResults.set(s.bookSourceUrl, 'skip');
        failReasons.set(s.bookSourceUrl, '已取消');
      } else {
        deepResults.set(s.bookSourceUrl, r);
        if (r.state === 'ok') d.pass++;
        else d.fail++;
      }
      d.done++;
      updateRowStatus(s.bookSourceUrl, s.bookSourceName);
      paintCheckBar();
      await yieldUI(8);
    }
  };
  await Promise.all(Array.from({ length: Math.min(CHECK_THREADS, queue.length) }, worker));
  if (!token.cancelled) checkUI.stage = 'done';
}

/** ③ 一键删除「两次都不合格」 */
async function deleteBothBad(list) {
  if (!list.length) return;
  const names = list.map((s) => `「${s.bookSourceName || s.bookSourceUrl}」`);
  const tip = `将删除 ${list.length} 个两次都不合格的书源：${names.slice(0, 10).join('、')}${names.length > 10 ? ` 等 ${list.length} 个` : ''}。此操作不可撤销。`;
  if (!(await confirmBox(tip))) return;
  for (const s of list) {
    await db.del('sources', s.id);
    checkResults.delete(s.bookSourceUrl);
    deepResults.delete(s.bookSourceUrl);
    failReasons.delete(s.bookSourceUrl);
    checkDiag.delete(s.bookSourceUrl);
    checkArtifacts.delete(s.bookSourceUrl);
  }
  await reloadSources();
  if (srcFilter.check === 'both') srcFilter.check = 'all';
  checkUI.stage = 'idle';
  renderList();
  paintCheckBar();
  toast(`已删除 ${list.length} 个书源`);
}

/* ---------- 查重 ---------- */

function findDuplicates() {
  const norm = (u) => String(u || '').trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/+$/, '');
  const score = (s) => (s.enabled ? 8 : 0) + (s.searchUrl ? 4 : 0) + (s.exploreUrl ? 1 : 0) + (s.bookSourceComment ? 1 : 0);
  const groups = [];
  const used = new Set();
  const collect = (keyFn, reason) => {
    const map = new Map();
    for (const s of state.sources) {
      if (used.has(s.id)) continue;
      const k = keyFn(s);
      if (!k) continue;
      if (!map.has(k)) map.set(k, []);
      map.get(k).push(s);
    }
    for (const [, list] of map) {
      if (list.length < 2) continue;
      list.sort((a, b) => score(b) - score(a));
      list.forEach((s) => used.add(s.id));
      groups.push({ reason, keep: list[0], dups: list.slice(1) });
    }
  };
  collect((s) => norm(s.bookSourceUrl), '地址相同');
  collect((s) => String(s.bookSourceName || '').trim() || '', '名称相同');
  return groups;
}

function openDedupSheet() {
  if (!state.sources.length) return toast('还没有书源');
  const groups = findDuplicates();
  const body = h('div', { class: 'dedup-body' });
  if (!groups.length) {
    body.append(h('p', { class: 'muted small' }, `已检查 ${state.sources.length} 个书源，没有发现重复（按书源地址或名称判定）。`));
    openSheet('检查重复', body);
    return;
  }
  const dupCount = groups.reduce((n, g) => n + g.dups.length, 0);
  body.append(h('p', { class: 'muted small' }, `发现 ${groups.length} 组重复，共 ${dupCount} 个多余书源。每组默认保留「已启用 / 有搜索地址 / 字段更全」的那一条。`));
  const list = h('div', { class: 'dedup-list' });
  for (const g of groups) {
    const box = h('div', { class: 'dedup-row' });
    box.append(h('div', { class: 'dedup-head' }, h('b', {}, g.keep.bookSourceName || g.keep.bookSourceUrl), h('span', { class: 'tag mut' }, g.reason)));
    box.append(h('p', { class: 'muted small dedup-keep' }, `保留：${g.keep.bookSourceName || g.keep.bookSourceUrl}${g.keep.enabled ? '（已启用）' : ''}`));
    for (const d of g.dups) box.append(h('p', { class: 'muted small dedup-del' }, `删除：${d.bookSourceName || d.bookSourceUrl}${d.enabled ? '（已启用）' : ''}`));
    list.append(box);
  }
  body.append(list);
  const go = h('button', { class: 'btn primary', style: 'width:100%;justify-content:center;margin-top:10px', type: 'button' }, `删除 ${dupCount} 个重复源（每组保留 1 个）`);
  const sheet = openSheet('检查重复', body);
  go.onclick = async () => {
    if (!(await confirmBox(`将删除 ${dupCount} 个重复书源，每组保留 1 个，此操作不可撤销。`))) return;
    for (const g of groups) {
      for (const d of g.dups) {
        await db.del('sources', d.id);
        checkResults.delete(d.bookSourceUrl);
        failReasons.delete(d.bookSourceUrl);
      }
    }
    await reloadSources();
    sheet.close();
    renderList();
    toast(`已删除 ${dupCount} 个重复书源`);
  };
  body.append(go);
}

/* ---------- 校验报告 ---------- */

function exportCheckReport(keyword) {
  const items = state.sources.map((s) => {
    const st = checkResults.get(s.bookSourceUrl) || 'none';
    const dp = deepResults.get(s.bookSourceUrl) || null;
    const d = checkDiag.get(s.bookSourceUrl) || {};
    return {
      name: s.bookSourceName || '',
      url: s.bookSourceUrl,
      state: st,
      reason: failReasons.get(s.bookSourceUrl) || '',
      deep: dp ? { state: dp.state, step: dp.step || '', reason: dp.reason || '', ms: dp.ms || 0 } : null,
      bothBad: st === 'bad' && !!dp && dp.state === 'bad',
      enabled: !!s.enabled,
      hasSearch: !!s.searchUrl,
      searchUrl: String(s.searchUrl || '').slice(0, 400),
      checkKeyWord: (s.ruleSearch && s.ruleSearch.checkKeyWord) || '',
      ruleSearch: s.ruleSearch || null,
      header: s.header || '',
      http: d.status ? { status: d.status, bytes: d.bytes, charset: d.charset, finalUrl: d.finalUrl, ms: d.ms } : null,
      logs: d.logs || [],
      snip: d.snip || '',
    };
  });
  const sum = (k) => items.filter((x) => x.state === k).length;
  const report = {
    app: '墨读',
    version: (window.ModouBridge && window.ModouBridge.appVersion && window.ModouBridge.appVersion()) || 'dev',
    time: new Date().toISOString(),
    keyword,
    total: items.length,
    summary: {
      ok: sum('ok'),
      bad: sum('bad'),
      skip: sum('skip'),
      nosearch: sum('nosearch'),
      none: sum('none'),
      bothBad: items.filter((x) => x.bothBad).length,
    },
    items,
  };
  if (!saveFile(`modou-source-check-${Date.now()}.json`, JSON.stringify(report, null, 1), 'application/json')) return toast('导出失败');
  toast('已导出校验报告，请把这个文件发给我');
}

/* ---------- 单源深度校验（四步） ---------- */

function openCheckModal(src) {
  const body = h('div', { class: 'form check' });
  const kw = h('input', { placeholder: '校验关键词，如 pride' });
  const go = h('button', { class: 'btn primary' }, '开始校验');
  // 校验档位（对齐 Legado checkSearch/checkInfo/checkToc/checkContent）：勾选会持久化到书源记录，
  // 影响批量体检与「深度校验」跑哪些档；本调试窗口仍完整展示四步日志。
  const tierRow = h('div', { class: 'tier-row' });
  const tdefs = [['checkSearch', '搜索'], ['checkInfo', '详情'], ['checkToc', '目录'], ['checkContent', '正文']];
  const tboxes = {};
  for (const [k, label] of tdefs) {
    const c = h('input', { type: 'checkbox', class: 'tier-opt', checked: src[k] !== false, 'data-tier': k });
    tboxes[k] = c;
    tierRow.append(h('label', { class: 'tier-opt' }, c, h('span', {}, label)));
  }
  tierRow.append(h('span', { class: 'muted small' }, '勾选决定快速/深度校验跑的档位，勾选会保存到书源'));
  const out = h('div', { class: 'check-out' }, h('div', { class: 'check-line' }, h('span', { class: 'check-k' }, '说明'), h('span', { class: 'check-v' }, '输入关键词后点「开始校验」，将依次跑 搜索 → 详情 → 目录 → 正文 四步。')));
  body.append(h('div', { class: 'row' }, kw, go), tierRow, out);
  const { close } = openModal(`校验书源 · ${src.bookSourceName || src.bookSourceUrl}`, body, { wide: true, sticky: true });
  const line = (label, text, ok = true) =>
    h('div', { class: 'check-line ' + (ok ? 'ok' : 'bad') }, h('span', { class: 'check-k' }, label), h('span', { class: 'check-v' }, text));

  go.onclick = async () => {
    const keyword = kw.value.trim();
    if (!keyword) return toast('先输入关键词');
    // 档位选择持久化到书源记录（失败不影响校验继续）
    try {
      const save = {};
      for (const k in tboxes) save[k] = tboxes[k].checked;
      const { cancelSignal: _cs, ...safe } = src;
      await db.put('sources', { ...safe, id: src.id, ...save });
    } catch (e) { /* 保存失败不阻塞 */ }
    out.innerHTML = '';
    go.disabled = true;
    // 单源也走同一套预算：单请求 12s、不重试，避免一个死链把弹窗挂住几分钟
    src.setRequestPolicy({ timeout: CHECK_REQUEST_TIMEOUT_MS, retries: 0 });
    try {
      out.append(h('h4', {}, '① 搜索'));
      const s = await src.search(keyword);
      for (const l of s.logs) out.append(line('LOG', l));
      if (s.error || !s.list.length) {
        out.append(line('结果', s.error ? '请求失败' : '未命中（检查 searchUrl / ruleSearch.bookList）', false));
        return;
      }
      out.append(line('结果', `命中 ${s.list.length} 条`));
      const first = s.list[0];
      out.append(line('首条', `${first.name} / ${first.author} / ${first.bookUrl}`));
      s.list.slice(0, 3).forEach((b) => out.append(h('div', { class: 'check-book' }, `${esc(b.name)} — ${esc(b.author)}`)));

      out.append(h('h4', {}, '② 详情'));
      const bi = await src.getBookInfo(first);
      for (const l of bi.logs) out.append(line('LOG', l));
      if (bi.error) {
        out.append(line('结果', '详情失败', false));
        return;
      }
      out.append(line('结果', `${bi.info.name} / ${bi.info.author} / toc=${bi.info.tocUrl}`));

      out.append(h('h4', {}, '③ 目录'));
      const toc = await src.getToc(bi.info);
      for (const l of toc.logs) out.append(line('LOG', l));
      if (toc.error || !toc.chapters.length) {
        out.append(line('结果', toc.error ? '目录失败' : '未解析到章节', false));
        return;
      }
      out.append(line('结果', `${toc.chapters.length} 章 · 首章「${toc.chapters[0].name}」`));

      out.append(h('h4', {}, '④ 正文'));
      const c = await src.getContent(bi.info, toc.chapters[0]);
      for (const l of c.logs) out.append(line('LOG', l));
      if (c.error || !c.content) {
        out.append(line('结果', c.error ? '正文失败' : '正文为空', false));
        return;
      }
      const text = c.content.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
      out.append(line('结果', `取到 ${c.content.length} 字符`));
      out.append(h('div', { class: 'check-preview' }, text.slice(0, 300) + (text.length > 300 ? '…' : '')));
      out.append(line('链路', '✅ 搜索 → 详情 → 目录 → 正文 全部通过'));
    } catch (e) {
      out.append(line('异常', e.message, false));
    } finally {
      src.clearRequestPolicy();
      go.disabled = false;
    }
  };
}
