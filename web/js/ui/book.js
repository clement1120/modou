/**
 * book.js — 书籍详情 + 章节目录 + 缓存全书（M3：本地书适配）
 */
import { state, findBook, saveBook, removeBook, getProgress, toast, confirmBox, promotePreview, clearPreview } from './app.js';
import { db } from '../db.js';
import { $, esc, coverImg } from './dom.js';
import { openEditBook } from './book-edit.js';

export async function renderBook(view, id) {
  const book = findBook(id);
  if (!book) {
    view.innerHTML = '<div class="empty"><h2>书籍不存在</h2><p><a href="#/search">去搜书</a></p></div>';
    return;
  }
  const isLocal = book.sourceUrl === 'local://';
  const src = state.sources.find((s) => s.bookSourceUrl === book.sourceUrl);
  const prog = await getProgress(id);
  const pct = book.chapters.length ? Math.round(((prog?.index ?? 0) / book.chapters.length) * 100) : 0;

  view.innerHTML = `
    <button class="back-chip" id="book-back" aria-label="返回">‹ 返回</button>
    <div class="page-head">
      <div>
        <h1>${esc(book.name)}</h1>
        <p class="muted">${esc(book.author || '佚名')} · ${isLocal ? '本地导入' : esc(book.sourceName || book.sourceUrl)} · ${book.chapters.length} 章</p>
      </div>
      <div class="btn-row">
        <a class="btn primary" href="#/read/${encodeURIComponent(id)}/${prog?.index ?? 0}">${prog ? '继续阅读' : '开始阅读'}</a>
        <button class="btn" id="btn-search">在线搜书</button>
        <button class="btn" id="btn-edit">编辑信息</button>
        ${book.preview ? '<button class="btn" id="btn-shelf">加入书架</button>' : ''}
        ${isLocal ? '' : '<button class="btn" id="btn-cache">缓存全书</button>'}
        ${isLocal ? '' : '<button class="btn" id="btn-refresh">刷新目录</button>'}
        <button class="btn ghost" id="btn-del">${book.preview ? '关闭试读' : '移出书架'}</button>
      </div>
    </div>
    <div class="cache-line" id="cache-line" hidden></div>
    <div class="card book-head">
      ${coverImg(book, 'big')}
      <div class="book-meta">
        <div class="meta-row"><span>来源</span><b>${isLocal ? '本地导入' : esc(book.sourceName || book.sourceUrl)}</b></div>
        <div class="meta-row"><span>分类</span><b>${esc(book.kind || '—')}</b></div>
        <div class="meta-row"><span>最新章节</span><b>${esc(book.lastChapter || '—')}</b></div>
        <div class="meta-row"><span>阅读进度</span><b>${pct}%</b></div>
        ${book.intro ? `<div class="intro">${esc(book.intro)}</div>` : ''}
      </div>
    </div>
    <div class="card">
      <div class="sec-head"><h3>目录</h3><span class="muted small" id="toc-count"></span></div>
      <div class="toc-list" id="toc"></div>
      <div class="center"><button class="btn" id="btn-more" hidden>加载更多</button></div>
    </div>
  `;

  $('#book-back').onclick = () => {
    if (history.length > 1) history.back();
    else location.hash = '#/shelf';
  };
  // 在线搜书：按书名跳到搜索页并自动开搜（本地书/换源找同款都用它）
  $('#btn-search').onclick = () => {
    location.hash = '#/search?kw=' + encodeURIComponent(book.name || '');
  };
  $('#btn-edit').onclick = () => {
    if (book.preview) return toast('试读书请先「加入书架」，再编辑信息');
    openEditBook(book, () => renderBook(view, id));
  };
  $('#btn-del').onclick = async () => {
    if (book.preview) {
      // 试读关闭：清暂存，不涉及书架
      clearPreview();
      toast('已关闭试读');
      location.hash = '#/search';
      return;
    }
    if (!(await confirmBox(`将《${book.name}》移出书架？阅读进度与章节缓存也会删除。`))) return;
    await removeBook(id);
    toast('已移出书架');
    location.hash = '#/shelf';
  };
  const shelfBtn = $('#btn-shelf');
  if (shelfBtn) {
    shelfBtn.onclick = async () => {
      try {
        const saved = await promotePreview();
        if (saved) {
          toast('已加入书架');
          renderBook(view, saved.id);
        }
      } catch (e) {
        toast('加入失败：' + (e.message || e));
      }
    };
  }
  if (!isLocal) {
    // 缓存全书：4 并发拉取全部章节，已有缓存跳过
    let caching = false;
    const cacheBtn = $('#btn-cache');
    const cacheLine = $('#cache-line');
    cacheBtn.onclick = async () => {
      if (caching) return;
      if (!src) return toast('书源已不存在，无法缓存');
      const total = book.chapters.length;
      if (!total) return toast('目录为空，无可缓存章节');
      caching = true;
      cacheBtn.disabled = true;
      cacheBtn.textContent = '缓存中…';
      cacheLine.hidden = false;
      let cursor = 0;
      let done = 0;
      let skip = 0;
      let fail = 0;
      const paintLine = () => {
        cacheLine.innerHTML = `已处理 <b>${done + skip + fail}</b> / ${total} 章 · 新缓存 <b>${done}</b> · 已有 <b>${skip}</b> · 失败 <b>${fail}</b>`;
      };
      paintLine();
      const worker = async () => {
        for (let i = cursor++; i < total; i = cursor++) {
          const key = id + '||' + i;
          try {
            if (await db.get('chapters', key)) {
              skip++;
            } else {
              const r = await src.getContent(book, book.chapters[i]);
              if (r.error || !r.content) throw new Error(r.error || '正文为空');
              await db.put('chapters', { id: key, content: r.content, cachedAt: Date.now() });
              done++;
            }
          } catch (e) {
            fail++;
          }
          paintLine();
        }
      };
      await Promise.all([worker(), worker(), worker(), worker()]);
      caching = false;
      cacheBtn.disabled = false;
      cacheBtn.textContent = '缓存全书';
      cacheLine.innerHTML = `缓存完成：共 ${total} 章，新缓存 <b>${done}</b>，已有 <b>${skip}</b>，失败 <b>${fail}</b>`;
      toast(`缓存完成：新增 ${done} 章${fail ? `，失败 ${fail} 章` : ''}`);
    };

    $('#btn-refresh').onclick = async () => {
      if (!src) return toast('书源已不存在，无法刷新');
      toast('正在刷新目录…');
      try {
        const toc = await src.getToc(book);
        await saveBook({ ...book, addedAt: book.addedAt }, toc.chapters);
        toast(`目录已更新（${toc.chapters.length} 章）`);
        renderBook(view, id);
      } catch (e) {
        toast('刷新失败：' + e.message);
      }
    };
  }

  // 章节列表分页渲染
  const PAGE = 200;
  let shown = Math.min(PAGE, book.chapters.length);
  const tocBox = $('#toc');
  const paint = () => {
    $('#toc-count').textContent = `第 1 - ${shown} 章 / 共 ${book.chapters.length} 章`;
    tocBox.innerHTML = book.chapters
      .slice(0, shown)
      .map(
        (c, i) => `<a class="toc-item ${i === prog?.index ? 'on' : ''}" href="#/read/${encodeURIComponent(id)}/${i}">
        <span class="toc-i">${i + 1}</span><span class="toc-n">${esc(c.name || '(未命名章节)')}</span></a>`
      )
      .join('');
    $('#btn-more').hidden = shown >= book.chapters.length;
  };
  $('#btn-more').onclick = () => {
    shown = Math.min(shown + PAGE, book.chapters.length);
    paint();
  };
  paint();
}
