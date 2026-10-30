/**
 * reader-toc.js — 阅读内目录抽屉（章节 / 书签双 tab）
 *
 * 用法：
 *   const drawer = createTocDrawer({
 *     book,                       // 书架书籍记录（含 chapters）
 *     onJump: (index) => {},      // 跳转章节（由调用方改 hash）
 *     onJumpBookmark: (bm) => {}, // 跳转书签位置
 *   });
 *   drawer.open(); drawer.close(); drawer.refresh();
 */
import { $, h, esc } from './dom.js';
import { getBookmarks, delBookmark } from './app.js';

const PAGE = 200; // 章节列表每次渲染条数（与详情页一致）

export function createTocDrawer(opts) {
  const mask = h('div', { class: 'toc-mask' });
  const drawer = h('div', { class: 'toc-drawer' });
  const head = h(
    'div',
    { class: 'toc-d-head' },
    h('span', { class: 'toc-d-title' }, esc(opts.book.name || '目录')),
    h('button', { class: 'dialog-x', title: '关闭', onclick: () => close() }, '✕')
  );
  const tabChap = h('button', { class: 'on', onclick: () => switchTab('chap') }, '目录');
  const tabBm = h('button', { onclick: () => switchTab('bm') }, '书签');
  const tabs = h('div', { class: 'toc-tabs' }, tabChap, tabBm);
  const searchBox = h('div', { class: 'toc-d-search' }, h('input', { placeholder: '搜索章节名', oninput: () => paintChap() }));
  const body = h('div', { class: 'toc-d-body' });
  drawer.append(head, tabs, searchBox, body);
  document.body.append(mask, drawer);

  let tab = 'chap';
  let bmList = [];
  // 章节窗口：几千章的书不能一次性建 DOM，但也不能永远从第 1 章开始——
  // 那样读到第 500 章的人打开目录看到的是第 1 章，得往下翻几百条才找到自己。
  let winStart = 0; // 窗口起始章号
  let chapShown = PAGE; // 窗口内条数

  const switchTab = (t) => {
    tab = t;
    tabChap.classList.toggle('on', t === 'chap');
    tabBm.classList.toggle('on', t === 'bm');
    searchBox.style.display = t === 'chap' ? '' : 'none';
    paint();
    if (t === 'chap') requestAnimationFrame(scrollToCurrent);
  };

  /** 把当前章滚到可视区偏上位置（抽屉已打开、尺寸已定） */
  function scrollToCurrent() {
    const on = body.querySelector('.dw-item.on');
    if (!on) return;
    const r = on.getBoundingClientRect();
    const br = body.getBoundingClientRect();
    if (!br.height) return;
    body.scrollTop += r.top - br.top - Math.round(br.height * 0.28);
  }

  const paint = () => (tab === 'chap' ? paintChap() : paintBm());

  function paintChap() {
    const kw = (searchBox.querySelector('input').value || '').trim();
    const chapters = opts.book.chapters || [];
    const list = kw ? chapters.map((c, i) => ({ c, i })).filter((x) => (x.c.name || '').includes(kw)) : chapters.map((c, i) => ({ c, i }));
    if (!list.length) {
      body.innerHTML = '<div class="dw-empty">没有匹配的章节</div>';
      return;
    }
    chapShown = Math.max(PAGE, chapShown);
    // 搜索时结果通常不多，一次给全；不搜索时按窗口渲染
    const from = kw ? 0 : Math.min(winStart, Math.max(0, list.length - 1));
    const to = kw ? list.length : Math.min(list.length, from + chapShown);
    const slice = list.slice(from, to);
    body.innerHTML = '';
    if (from > 0) {
      body.append(
        h(
          'button',
          {
            class: 'mini dw-more',
            onclick: () => {
              winStart = Math.max(0, winStart - PAGE);
              chapShown += PAGE;
              paintChap();
              body.scrollTop = 0;
            },
          },
          `加载更早的章节（当前从第 ${from + 1} 章起）`
        )
      );
    }
    const frag = document.createDocumentFragment();
    for (const { c, i } of slice) {
      frag.append(
        h(
          'button',
          {
            class: 'dw-item' + (i === opts.currentIndex() ? ' on' : ''),
            onclick: () => {
              close();
              opts.onJump(i);
            },
          },
          h('span', { class: 'dw-i' }, String(i + 1)),
          h('span', { class: 'dw-n' }, esc(c.name || '(未命名章节)'))
        )
      );
    }
    body.append(frag);
    if (to < list.length) {
      body.append(
        h(
          'button',
          {
            class: 'mini dw-more',
            onclick: () => {
              chapShown += PAGE;
              paintChap();
            },
          },
          `加载更多（还剩 ${list.length - to} 章）`
        )
      );
    }
  }

  function paintBm() {
    body.innerHTML = '';
    if (!bmList.length) {
      body.append(h('div', { class: 'dw-empty' }, '还没有书签', h('br'), '阅读时点顶栏书签按钮即可添加'));
      return;
    }
    for (const bm of bmList) {
      const cap = h(
        'button',
        {
          class: 'bm-cap',
          onclick: () => {
            close();
            opts.onJumpBookmark(bm);
          },
        },
        h('div', { class: 'bm-t' }, `${esc(bm.chapterName || '')} · 第 ${(bm.page || 0) + 1} 页`),
        bm.text ? h('div', { class: 'bm-s' }, esc(bm.text)) : null
      );
      body.append(
        h(
          'div',
          { class: 'bm-item' },
          cap,
          h('span', { class: 'bm-time' }, new Date(bm.createdAt).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })),
          h('button', { class: 'bm-del', title: '删除书签', onclick: async () => { await delBookmark(bm.id); await refresh(); paint(); } }, '✕')
        )
      );
    }
  }

  const refresh = async () => {
    bmList = await getBookmarks(opts.book.id);
  };

  const open = async () => {
    await refresh();
    // 让当前章落在渲染窗口里（默认从第 1 章起的话，读得越深越找不到自己）
    const cur = Math.max(0, Number(opts.currentIndex()) || 0);
    winStart = cur >= PAGE ? Math.max(0, cur - 60) : 0;
    chapShown = PAGE;
    paint();
    mask.classList.add('open');
    drawer.classList.add('open');
    // 等这一帧布局完成再滚，否则量到的还是打开前的尺寸
    requestAnimationFrame(scrollToCurrent);
  };
  const close = () => {
    mask.classList.remove('open');
    drawer.classList.remove('open');
  };
  mask.addEventListener('click', close);

  return { open, close, refresh, isOpen: () => drawer.classList.contains('open') };
}
