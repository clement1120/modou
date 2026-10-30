/**
 * search.js — 搜书：多书源并发搜索 → 聚合结果 → 加入书架
 * 版式：顶部「搜索 / 发现」两个 Tab；书源默认全选，可按书源分组勾选。
 */
import { enabledSources, openPreview, toast } from './app.js';
import { $, $$, h, esc, openModal, openSheet, coverImg } from './dom.js';

let picked = null; // 已选书源 url 数组；null 表示「默认全部」
let lastGroups = [];

/**
 * 搜索任务状态放在模块级：离开搜书页后任务继续跑，回来时按状态恢复。
 * DOM 只做「投影」——页面不在时 paintSearchUI 直接跳过。
 */
const searchJob = {
  keyword: '',
  total: 0,
  done: 0,
  groups: new Map(),
  fails: [],
  filtered: 0,
  running: false,
  finished: false,
  gen: 0,
};
let uiTimer = 0;
let uiLast = 0;

/**
 * 发现页翻页状态（模块级）：离开发现页后仍保留，回来可按状态恢复；
 * 换源自动重置。loading 期间显示「加载中…」，到底显示「已到底」，中间显示「加载更多」。
 */
const exploreState = {
  srcUrl: null,
  page: 0,
  map: new Map(),
  groups: [],
  loading: false,
  done: false,
  error: '',
};

function scheduleSearchUI() {
  // 节流而非防抖：源接连返回时，防抖会被不断重置，进度条长时间不动、看着像卡死。
  // 这里最多 150ms 重绘一次，且结束时的末次重绘一定会落地。
  if (uiTimer) return;
  const wait = Math.max(0, 150 - (performance.now() - uiLast));
  uiTimer = setTimeout(() => {
    uiTimer = 0;
    uiLast = performance.now();
    paintSearchUI();
  }, wait);
}

function paintSearchUI() {
  const prog = document.querySelector('#search-progress');
  if (!prog) return; // 不在搜书页：任务继续，UI 跳过
  const job = searchJob;
  const box = document.querySelector('#results');
  const fails = document.querySelector('#search-fails');
  const failList = document.querySelector('#fail-list');
  const kw = document.querySelector('#kw');
  if (kw && document.activeElement !== kw && job.keyword && !kw.value) kw.value = job.keyword;

  if (!job.total) {
    prog.textContent = '';
    if (box) box.innerHTML = '';
    if (fails) fails.hidden = true;
    return;
  }
  const tail = `${job.filtered ? ` · 已过滤 ${job.filtered} 条无关` : ''}${job.fails.length ? ` · 失败 ${job.fails.length}` : ''}`;
  prog.textContent = job.finished
    ? `完成：${job.total} 个源 · ${job.groups.size} 个结果${tail}`
    : `搜索中 ${job.done}/${job.total} · 已发现 ${job.groups.size} 个结果${tail}`;

  if (box) {
    if (job.groups.size) renderResultsInto(box, [...job.groups.values()]);
    else if (job.finished)
      box.innerHTML = `<div class="empty"><h2>没有结果</h2><p>${job.total} 个源均未命中。可展开上方「失败源」查看原因，或更换关键词 / 校验书源。</p></div>`;
    else box.innerHTML = '';
  }
  if (fails && failList) {
    if (job.fails.length) {
      fails.hidden = false;
      const sum = fails.querySelector('summary');
      if (sum) sum.textContent = `失败源（${job.fails.length}）`;
      failList.innerHTML = job.fails
        .slice(0, 200)
        .map((f) => `<div class="fail-item"><b>${esc(f.name)}</b><span>${esc(f.reason)}</span></div>`)
        .join('');
    } else {
      fails.hidden = true;
    }
  }
}

/** 书源分组名：Legado bookSourceGroup 可能含多个（逗号分隔），取第一个 */
function groupOf(s) {
  const g = String((s && s.bookSourceGroup) || '').split(/[,，]/)[0].trim();
  return g || '未分组';
}

/** 归一化：忽略大小写与空白（中文关键词常带空格） */
function normKw(s) {
  return String(s || '')
    .toLowerCase()
    .replace(/[\s\u3000]+/g, '');
}

/**
 * 结果相关性打分：书名命中 3 / 作者命中 2 / 简介、分类等命中 1 / 完全无关 0。
 * 用于过滤「搜索规则失效后返回整页推荐」的书源——这类条目既打不开也不符合搜索意图。
 */
function relevance(book, kw) {
  const k = normKw(kw);
  if (!k) return 1;
  if (normKw(book && book.name).includes(k)) return 3;
  if (normKw(book && book.author).includes(k)) return 2;
  const extra = normKw(`${(book && book.intro) || ''}${(book && book.lastChapter) || ''}${(book && book.kind) || ''}`);
  return extra.includes(k) ? 1 : 0;
}

/** 按分组聚合（组名排序） */
function groupsOf(list) {
  const m = new Map();
  for (const s of list) {
    const g = groupOf(s);
    if (!m.has(g)) m.set(g, []);
    m.get(g).push(s);
  }
  return [...m.entries()].sort((a, b) => a[0].localeCompare(b[0], 'zh'));
}

/** 解析实际选中的书源（默认全部；用户显式清空则保持为空） */
function resolvePicked() {
  const all = enabledSources().map((s) => s.bookSourceUrl);
  if (!Array.isArray(picked)) return all;
  return picked.filter((u) => all.includes(u));
}

export async function renderSearch(view, query) {
  view.innerHTML = `
    <div class="page-head">
      <div>
        <h1>搜书</h1>
        <p class="sub">同时搜索已启用的书源</p>
      </div>
    </div>

    <div class="seg page-tabs" id="search-tabs">
      <button class="on" data-tab="search">搜索</button>
      <button data-tab="discover">发现</button>
    </div>

    <section id="panel-search">
      <form class="search-pill" id="search-form" autocomplete="off">
        <svg class="search-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>
        <input id="kw" name="q" placeholder="书名 / 作者" autocomplete="off" autocapitalize="off" autocorrect="off" enterkeyhint="search">
        <button class="search-go" id="btn-go" type="submit" aria-label="搜索">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h13M13 6.5l5.5 5.5-5.5 5.5"/></svg>
        </button>
      </form>
      <div class="src-summary" id="src-summary"></div>
      <div id="search-progress"></div>
      <details class="search-fails" id="search-fails" hidden>
        <summary>失败源</summary>
        <div class="fail-list" id="fail-list"></div>
      </details>
      <div id="results"></div>
    </section>

    <section id="panel-discover" hidden>
      <div class="pick-row">
        <button class="pick" id="explore-pick">
          <span id="explore-src-name">选择书源</span>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9.5l6 6 6-6"/></svg>
        </button>
        <button class="btn primary" id="btn-explore">浏览</button>
      </div>
      <p class="muted small" style="margin-top:12px">仅列出配置了 exploreUrl 的书源，结果同样可加入书架。</p>
      <div id="explore-results"></div>
    </section>
  `;

  /* Tab 切换 */
  const tabs = $$('.page-tabs button');
  const setTab = (t) => {
    tabs.forEach((b) => b.classList.toggle('on', b.dataset.tab === t));
    $('#panel-search').hidden = t !== 'search';
    $('#panel-discover').hidden = t !== 'discover';
  };
  tabs.forEach((b) => (b.onclick = () => setTab(b.dataset.tab)));
  setTab('search');

  const input = $('#kw');
  const go = () => {
    const kw = input.value.trim();
    input.blur();
    Promise.resolve(doSearch(kw))
      .then(() => $('#search-progress')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }))
      .catch(() => {});
  };
  $('#search-form').onsubmit = (e) => {
    e.preventDefault();
    go();
  };

  // 从书籍详情页「在线搜书」跳过来：预填书名并自动搜索，然后清掉 query
  // （不清的话返回键/刷新会重复触发一次搜索）
  let prefill = '';
  try {
    prefill = (query || '').split('&').reduce((acc, kv) => {
      const i = kv.indexOf('=');
      if (i > 0 && kv.slice(0, i) === 'kw') acc = decodeURIComponent(kv.slice(i + 1).replace(/\+/g, ' '));
      return acc;
    }, '');
  } catch (e) {
    prefill = '';
  }
  if (prefill) {
    input.value = prefill;
    history.replaceState(null, '', location.pathname + location.search + '#/search');
    go();
  }

  /* 书源选择（默认全部，可按分组勾选） */
  const paintSummary = () => {
    const all = enabledSources();
    const sel = resolvePicked();
    const box = $('#src-summary');
    if (!box) return;
    if (!all.length) {
      box.innerHTML = `<span class="muted">没有启用的书源</span><a class="mini" href="#/sources">去书源管理</a>`;
      return;
    }
    const selSet = new Set(sel);
    const groups = new Set(all.filter((s) => selSet.has(s.bookSourceUrl)).map(groupOf)).size;
    box.innerHTML = `<span>书源：<b>${sel.length}</b> / ${all.length} 个${groups ? ` · ${groups} 组` : ''}</span><button class="mini" id="btn-pick">选择</button>`;
    $('#btn-pick').onclick = openPicker;
  };

  const openPicker = () => {
    const body = h('div', { class: 'src-pick' });
    let cur = new Set(resolvePicked());
    const tools = h(
      'div',
      { class: 'btn-row' },
      h('button', { class: 'btn small', onclick: () => { cur = new Set(enabledSources().map((s) => s.bookSourceUrl)); commit(); } }, '全选'),
      h('button', { class: 'btn ghost small', onclick: () => { cur = new Set(); commit(); } }, '清空')
    );
    const list = h('div', { class: 'src-pick-list' });
    body.append(tools, list);

    const commit = () => {
      picked = [...cur];
      paintSummary();
      paint();
    };
    const paint = () => {
      list.innerHTML = '';
      const gs = groupsOf(enabledSources());
      if (!gs.length) {
        list.append(h('p', { class: 'muted small' }, '没有启用的书源'));
        return;
      }
      for (const [g, arr] of gs) {
        const on = arr.filter((s) => cur.has(s.bookSourceUrl)).length;
        const head = h(
          'button',
          {
            class: 'src-group-head',
            onclick: () => {
              const turnOn = on !== arr.length;
              arr.forEach((s) => (turnOn ? cur.add(s.bookSourceUrl) : cur.delete(s.bookSourceUrl)));
              commit();
            },
          },
          h('b', {}, g),
          h('span', {}, `${on}/${arr.length}`),
          h('em', {}, on === arr.length ? '全不选' : '全选')
        );
        list.append(head);
        for (const s of arr) {
          const checked = cur.has(s.bookSourceUrl);
          const label = h(
            'label',
            { class: 'chip-check' + (checked ? ' on' : '') },
            h('input', { type: 'checkbox' }),
            document.createTextNode(s.bookSourceName || s.bookSourceUrl)
          );
          const cb = label.querySelector('input');
          cb.checked = checked;
          cb.onchange = () => {
            if (cb.checked) cur.add(s.bookSourceUrl);
            else cur.delete(s.bookSourceUrl);
            label.classList.toggle('on', cb.checked);
            commit();
          };
          list.append(label);
        }
      }
    };
    paint();
    openSheet('选择书源', body);
  };

  paintSummary();
  // 回到本页时，若后台搜索仍在进行/已完成，按状态重建进度与结果
  paintSearchUI();

  /* 发现浏览：自定义选源 + 翻页 + 加载态 */
  const withExplore = enabledSources().filter((s) => s.exploreUrl);
  let exploreSrc = withExplore.find((s) => s.bookSourceUrl === exploreState.srcUrl) || withExplore[0] || null;
  const paintPick = () => {
    const nameEl = $('#explore-src-name');
    if (nameEl) nameEl.textContent = exploreSrc ? exploreSrc.bookSourceName || exploreSrc.bookSourceUrl : '（没有可浏览的书源）';
    const btn = $('#btn-explore');
    if (btn) btn.disabled = !exploreSrc;
  };
  paintPick();

  const renderExplore = () => {
    const box = $('#explore-results');
    if (!box) return; // 不在发现页：状态保留，跳过 UI
    const st = exploreState;
    if (!st.srcUrl) {
      box.innerHTML = '<p class="muted small">请选择一个书源后点击「浏览」。</p>';
      return;
    }
    if (!st.groups.length && st.loading) {
      box.innerHTML = '<p class="muted small">加载中…</p>';
      return;
    }
    if (!st.groups.length && !st.loading) {
      box.innerHTML = st.error
        ? `<p class="muted small">失败：${esc(st.error)}</p>`
        : '<div class="empty"><h2>没有内容</h2></div>';
      return;
    }
    const wrap = h('div', {});
    renderResultsInto(wrap, st.groups); // 写入 wrap.innerHTML，footer 其后追加
    if (st.loading) {
      wrap.append(h('div', { class: 'explore-foot muted small' }, '加载中…'));
    } else if (st.done) {
      wrap.append(h('div', { class: 'explore-foot muted small' }, '—— 已到底 ——'));
    } else {
      wrap.append(h('button', { class: 'btn ghost block', onclick: () => loadExplore(false) }, '加载更多'));
    }
    if (st.error && st.groups.length) {
      wrap.append(h('p', { class: 'muted small' }, `最近一次加载失败：${esc(st.error)}`));
    }
    box.innerHTML = '';
    box.append(wrap);
  };

  const loadExplore = async (reset) => {
    const src = exploreSrc;
    if (!src) return;
    if (reset) {
      exploreState.srcUrl = src.bookSourceUrl;
      exploreState.page = 0;
      exploreState.map = new Map();
      exploreState.groups = [];
      exploreState.done = false;
      exploreState.error = '';
    } else if (exploreState.srcUrl !== src.bookSourceUrl) {
      // 选源已变，先重置再续
      exploreState.srcUrl = src.bookSourceUrl;
      exploreState.page = 0;
      exploreState.map = new Map();
      exploreState.groups = [];
      exploreState.done = false;
      exploreState.error = '';
    }
    const st = exploreState;
    if (st.loading || st.done) return;
    st.loading = true;
    st.error = '';
    renderExplore();
    try {
      const r = await src.explore(st.page + 1);
      if (r.error) throw r.error;
      const rows = r.list || [];
      let added = 0;
      for (const b of rows) {
        const key = normKw(b.name) || b.bookUrl;
        if (!st.map.has(key)) st.map.set(key, { name: b.name, hits: [], score: 1 });
        const g = st.map.get(key);
        if (g.hits.some((hh) => hh.src.bookSourceUrl === src.bookSourceUrl && hh.book.bookUrl === b.bookUrl)) continue;
        g.hits.push({ src, book: b });
        added++;
      }
      if (!rows.length || added === 0) st.done = true; // 无新内容即到底（兼容非分页源）
      else {
        st.groups = [...st.map.values()];
        st.page += 1;
      }
    } catch (e) {
      st.error = (e && e.message) || String(e);
    } finally {
      st.loading = false;
      renderExplore();
    }
  };

  $('#explore-pick').onclick = () => {
    if (!withExplore.length) return toast('没有可浏览的书源，先去书源管理导入或启用');
    const list = h('div', { class: 'pick-list' });
    const sheet = openSheet('选择书源', list);
    withExplore.forEach((s) => {
      list.append(
        h(
          'button',
          {
            class: 'pick-item' + (s === exploreSrc ? ' on' : ''),
            onclick: () => {
              if (exploreSrc !== s) {
                exploreSrc = s;
                paintPick();
                if (exploreState.srcUrl !== s.bookSourceUrl) renderExplore(); // 换源清空旧结果
              }
              sheet.close();
            },
          },
          s.bookSourceName || s.bookSourceUrl
        )
      );
    });
  };
  $('#btn-explore').onclick = () => loadExplore(true);
  // 进入发现页时按已存状态恢复（换源也同步）
  if (exploreSrc && exploreState.srcUrl !== exploreSrc.bookSourceUrl) {
    exploreState.srcUrl = null;
    exploreState.groups = [];
    exploreState.page = 0;
    exploreState.done = false;
    exploreState.error = '';
  }
  renderExplore();
}

async function doSearch(keyword) {
  if (!keyword) return toast('先输入关键词');
  const pickedUrls = resolvePicked();
  const sources = enabledSources().filter((s) => pickedUrls.includes(s.bookSourceUrl));
  if (!sources.length) return toast('没有选中的书源');

  const gen = ++searchJob.gen;
  searchJob.keyword = keyword;
  searchJob.total = sources.length;
  searchJob.done = 0;
  searchJob.groups = new Map();
  searchJob.fails = [];
  searchJob.filtered = 0;
  searchJob.running = true;
  searchJob.finished = false;
  paintSearchUI();

  const queue = [...sources];
  const workers = Array.from({ length: Math.min(6, queue.length) }, async () => {
    while (queue.length) {
      if (gen !== searchJob.gen) return;
      const src = queue.shift();
      try {
        const r = await src.search(keyword);
        if (gen !== searchJob.gen) return;
        if (r.error) throw r.error;
        const all = r.list || [];
        // 相关性过滤：书源搜索规则失效时（关键词没进请求、列表选择器匹配到推荐位），
        // 会返回整页与关键词无关的书。这种结果既打不开也没意义，宁可丢弃并报给用户。
        const rows = all.filter((b) => relevance(b, keyword) > 0);
        const dropped = all.length - rows.length;
        searchJob.filtered += dropped;
        if (all.length && !rows.length) {
          throw new Error(`返回 ${all.length} 条结果均与关键词无关（搜索规则可能失效）`);
        }
        for (const b of rows) {
          // 同名归为同一本书：方便在同一张卡里横向选源（作者信息在 hits 里展示）
          const key = normKw(b.name) || b.bookUrl;
          if (!searchJob.groups.has(key)) {
            searchJob.groups.set(key, { name: b.name, hits: [], score: 0 });
          }
          const g = searchJob.groups.get(key);
          // 同一书源的同一条结果只留一份
          if (g.hits.some((h) => h.src.bookSourceUrl === src.bookSourceUrl && h.book.bookUrl === b.bookUrl)) continue;
          g.score = Math.max(g.score, relevance(b, keyword));
          g.hits.push({ src, book: b });
        }
      } catch (e) {
        if (gen !== searchJob.gen) return;
        searchJob.fails.push({ name: src.bookSourceName || src.bookSourceUrl, reason: briefError(e) });
      }
      if (gen !== searchJob.gen) return;
      searchJob.done++;
      scheduleSearchUI();
    }
  });
  await Promise.all(workers);
  if (gen !== searchJob.gen) return;
  searchJob.running = false;
  searchJob.finished = true;
  lastGroups = [...searchJob.groups.values()];
  paintSearchUI();
}

/** 把异常压成一行可读摘要，避免把书源规则源码刷到界面上 */
function briefError(e) {
  let s = String((e && e.message) || e || '').replace(/\s+/g, ' ').trim();
  const m = s.match(/(CSS 无效|XPath 无效|JSON 无效|正则无效|规则无效|请求失败|超时|HTTP\s*\d+|未命中|空结果)[\s\S]*/i);
  if (m) s = m[0];
  return s.slice(0, 120) || '未知错误';
}

function renderResultsInto(box, groups) {
  if (!groups.length) {
    box.innerHTML = '<div class="empty"><h2>没有结果</h2><p>换个关键词，或去书源管理校验书源是否有效。</p></div>';
    return;
  }
  // 书名命中优先，其次多源命中
  groups = [...groups].sort((a, b) => (b.score || 0) - (a.score || 0) || b.hits.length - a.hits.length);
  box.innerHTML = groups
    .map((g, i) => {
      const best = g.hits[0].book;
      const authors = [...new Set(g.hits.map((h) => (h.book.author || '').trim()).filter(Boolean))];
      const authorText = authors.length ? `${authors.slice(0, 3).join(' / ')}${authors.length > 3 ? ' 等' : ''}` : '佚名';
      const srcCount = new Set(g.hits.map((h) => h.src.bookSourceUrl)).size;
      const hits = g.hits
        .map(
          (hit, j) => `<div class="hit-row">
          <span class="hit-src">${esc(hit.src.bookSourceName || hit.src.bookSourceUrl)}</span>
          ${authors.length > 1 && hit.book.author ? `<span class="hit-author">${esc(hit.book.author)}</span>` : ''}
          <span class="hit-chap">${esc(hit.book.lastChapter || hit.book.kind || hit.book.wordCount || '')}</span>
          <button class="mini" data-g="${i}" data-h="${j}">打开</button>
        </div>`
        )
        .join('');
      return `<div class="card result-card">
        <div class="result-main">
          ${coverImg({ name: g.name, id: g.hits[0].src.bookSourceUrl + g.hits[0].book.bookUrl, coverUrl: best.coverUrl })}
          <div class="result-info">
            <div class="result-name">${esc(g.name)}</div>
            <div class="result-sub">${esc(authorText)}${srcCount > 1 ? ` · ${srcCount} 个源` : ''}</div>
            ${best.intro ? `<div class="result-intro">${esc(best.intro.slice(0, 120))}</div>` : ''}
          </div>
        </div>
        <div class="hit-list">${hits}</div>
      </div>`;
    })
    .join('');
  box.querySelectorAll('button[data-g]').forEach((btn) => {
    btn.onclick = () => openBook(groups[+btn.dataset.g].hits[+btn.dataset.h]);
  });
}

/** 打开失败时给出可诊断的信息（不是一句「失败」就完事） */
function showOpenError(info, err, logs) {
  const body = h('div', {});
  body.append(h('p', {}, `《${info.name || '未命名'}》`));
  body.append(h('p', { class: 'muted small' }, String((err && err.message) || err || '未知错误')));
  if (logs && logs.length) {
    body.append(h('pre', { class: 'diag-log' }, logs.slice(-14).join('\n')));
  }
  body.append(
    h(
      'p',
      { class: 'muted small' },
      '常见原因：目录地址规则抓到了错误链接、目录需要 JS 接口（java.ajax）、或该书源已失效。可以回到搜索结果换一个源打开；也可到「书源管理 → 校验」检查该源。'
    )
  );
  openModal('打开失败', body);
}

/** 打开一本书：拉详情 + 目录 → 存书架 → 跳转详情页 */
/**
 * 打开一本书：拉详情 + 目录 → 试读暂存（不入书架）→ 跳详情/阅读。
 * 用户在阅读页或详情页点「加入书架」才固化进书架。
 */
async function openBook(hit) {
  const { src, book } = hit;
  if (!book || !book.bookUrl) {
    return toast('打开失败：该书源没解析出书籍地址（bookUrl 规则可能失效）');
  }
  toast('正在获取书籍信息…');
  const logs = [];
  // 详情失败不再阻断：书名/作者/封面搜索结果里已有，目录用 bookUrl 也能试
  let info = book;
  try {
    const bi = await src.getBookInfo(book);
    if (bi && bi.info) info = bi.info;
    if (bi && bi.logs) logs.push(...bi.logs);
    if (bi && bi.error) console.warn('[modou] 详情获取失败，改用搜索结果打开：', bi.error.message || bi.error);
  } catch (e) {
    logs.push('详情异常: ' + (e.message || e));
  }
  let toc;
  try {
    toc = await src.getToc(info);
  } catch (e) {
    return showOpenError(info, new Error('目录解析异常：' + (e.message || e)), logs);
  }
  if (toc.logs) logs.push(...toc.logs);
  if (toc.error) return showOpenError(info, toc.error, logs);
  if (!toc.chapters.length) return showOpenError(info, new Error('该书源没有解析出章节'), logs);
  try {
    const rec = openPreview(
      {
        ...info,
        sourceUrl: src.bookSourceUrl,
        sourceName: src.bookSourceName,
        addedAt: Date.now(),
        chapters: toc.chapters, // 目录随 record 暂存，阅读器/详情页直接用
      },
      toc.chapters.map((c) => c.html || '')
    );
    toast(`已打开《${rec.name || '未命名'}》（${toc.chapters.length} 章）· 试读中`);
    location.hash = '#/read/' + encodeURIComponent(rec.id) + '/0';
  } catch (e) {
    toast('打开失败：' + (e.message || e));
  }
}
