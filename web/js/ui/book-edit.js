/**
 * book-edit.js — 编辑书籍信息（书名 / 作者 / 封面 URL）。
 *
 * 从已删除的 shelf.js 里搬出来的：书架整页改成原生后，只剩这个弹层还被网页版详情页用到
 * （book.js 的「编辑信息」按钮）。依赖与原来完全一致，行为没有改动。
 */
import { saveBook, toast, enabledSources } from './app.js';
import { h, esc, openSheet, coverImg } from './dom.js';

function cleanCoverKw(name) {
  const c = String(name || '')
    .replace(/[（(【\[][^）)】\]]*[）)】\]]/g, ' ')
    .replace(/[《》「」『』\u3000]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return c || String(name || '').trim();
}

/**
 * 按书名在线搜封面：
 * 1. 各启用书源并发 search，收结果里的封面（去重）；
 * 2. 某源搜索结果一条封面都没有时，取该源最相关的一条结果进详情页兜底
 *    （详情页封面规则几乎都有，搜索页规则经常缺失——这是"搜不到封面"的主因）；
 * 3. 收集到的地址都过 URL 校验。
 * @param {string} kw 搜索词（书名）
 * @param {number} maxSrc 最多参与的书源数（控时长）
 * @param {number} maxOut 最多返回的候选数
 * @returns {Promise<{urls: string[], srcTotal: number, srcHit: number}>}
 */
async function fetchCovers(kw, maxSrc = 24, maxOut = 24) {
  const word = cleanCoverKw(kw);
  const flat = (s) => String(s || '').replace(/\s+/g, '').toLowerCase();
  const needle = flat(word);
  const sources = enabledSources()
    .filter((s) => typeof s.search === 'function')
    .slice(0, maxSrc);
  const out = [];
  const seen = new Set();
  let stop = false;
  let srcHit = 0;
  const queue = [...sources];
  const push = (u) => {
    const url = String(u || '').trim();
    if (!/^https?:\/\//i.test(url) || seen.has(url)) return;
    seen.add(url);
    out.push(url);
    if (out.length >= maxOut) stop = true;
  };
  const worker = async () => {
    while (queue.length && !stop) {
      const src = queue.shift();
      let rows = [];
      try {
        const r = await src.search(word);
        rows = ((r && r.list) || []).filter((b) => b.name);
      } catch (e) {
        /* 单个书源失败就跳过，不影响别的源 */
      }
      // 相关的排前面（书名双向包含），供详情兜底挑第一条
      rows.sort((a, b) => {
        const na = flat(a.name).includes(needle) ? 0 : 1;
        const nb = flat(b.name).includes(needle) ? 0 : 1;
        return na - nb;
      });
      for (const b of rows) push(b.coverUrl);
      if (out.length >= maxOut) return;
      // 本源搜索页一张封面都没有 → 进详情页兜底（只试最相关的一条，控开销）
      if (!rows.some((b) => /^https?:\/\//i.test(String(b.coverUrl || '').trim()))) {
        const top = rows.find((b) => b.bookUrl && (flat(b.name).includes(needle) || needle.includes(flat(b.name))));
        if (top) {
          try {
            const info = await src.getBookInfo({ bookUrl: top.bookUrl, name: top.name });
            push(info && info.info && info.info.coverUrl);
          } catch (e) {
            /* 详情失败忽略 */
          }
        }
      }
      if (out.length) srcHit++;
    }
  };
  await Promise.all(Array.from({ length: Math.min(6, queue.length) }, worker));
  return { urls: out, srcTotal: sources.length, srcHit };
}

/** 编辑书籍信息（书名 / 作者 / 封面 URL）。书架长按卡片、⋯ 菜单、详情页「编辑信息」共用。 */
export function openEditBook(book, onSaved) {
  const box = h('div', { class: 'form edit-book-form' });
  const field = (label, val, ph) => {
    const ta = h('input', { value: val || '', placeholder: ph || '' });
    ta.style.cssText = 'width:100%;padding:10px 12px;border:none;border-radius:12px;background:rgba(43,37,27,.06);color:var(--text);font-size:14px;outline:none;font-family:inherit';
    const row = h('label', { class: 'edit-row' }, h('span', { class: 'edit-k' }, label), ta);
    return { row, input: ta };
  };
  const f = {
    name: field('书名', book.name),
    author: field('作者', book.author, '佚名'),
    cover: field('封面 URL', book.coverUrl, '留空则使用自动生成的占位封面'),
  };
  Object.values(f).forEach((x) => box.append(x.row));

  /* 在线搜封面：拿当前书名去各启用书源搜一轮，把结果里的封面收集成候选网格，
   * 点一张就填进「封面 URL」。书源并发拉取，个别源失败不影响整体。 */
  const pickBtn = h('button', { class: 'btn ghost', style: 'width:100%;justify-content:center;margin-top:10px', type: 'button' }, '按书名在线搜封面');
  const zone = h('div', { style: 'margin-top:10px', hidden: true });
  let picking = false;
  pickBtn.onclick = async () => {
    if (picking) return;
    const kw = f.name.input.value.trim();
    if (!kw) return toast('先填书名');
    picking = true;
    pickBtn.disabled = true;
    zone.hidden = false;
    zone.innerHTML = '<div class="muted small" style="text-align:center;padding:14px 0">正在按「' + esc(kw) + '」搜索封面…（书源无封面字段时会自动进详情页找）</div>';
    const { urls, srcTotal, srcHit } = await fetchCovers(kw, 24, 24);
    picking = false;
    pickBtn.disabled = false;
    if (!zone.isConnected) return; // 弹层已被关掉
    zone.innerHTML = '';
    if (!urls.length) {
      zone.innerHTML =
        '<div class="muted small" style="text-align:center;padding:10px 0">' +
        `搜了 ${srcTotal} 个书源都没找到封面` +
        (srcTotal ? '（可尝试改一下书名再搜，或手动粘贴图片地址）' : '（先在「书源」里导入并启用书源）') +
        '</div>';
      return;
    }
    const grid = h('div', { class: 'cover-grid' });
    urls.forEach((u) => {
      const cell = h('button', { class: 'cover-cell', type: 'button', title: '选用这张封面' });
      // 加载失败（防盗链/失效地址）的候选自动隐藏，不占格子
      cell.innerHTML = `<img src="${esc(u)}" loading="lazy" referrerpolicy="no-referrer" alt="" onerror="this.closest('.cover-cell').style.display='none'">`;
      cell.onclick = () => {
        f.cover.input.value = u;
        paintPreview();
        toast('已选用，点「保存」生效');
      };
      grid.append(cell);
    });
    zone.append(grid);
    if (srcHit) {
      const tip = h('div', { class: 'muted small', style: 'text-align:center;margin-top:6px' }, `${urls.length} 张候选 · ${srcHit}/${srcTotal} 个书源有封面`);
      zone.append(tip);
    }
  };
  box.append(pickBtn, zone);

  const paintPreview = () => {
    const pv = h('div', { style: 'display:flex;justify-content:center;margin:12px 0 4px' });
    pv.innerHTML = coverImg({
      name: f.name.input.value.trim(),
      id: book.id,
      coverUrl: f.cover.input.value.trim(),
    });
    const old = box.querySelector('.edit-preview');
    if (old) old.replaceWith(pv);
    else box.append(pv);
    pv.classList.add('edit-preview');
  };
  f.cover.input.oninput = paintPreview;
  f.name.input.oninput = paintPreview;
  paintPreview();

  const save = h('button', { class: 'btn primary', style: 'width:100%;justify-content:center;margin-top:10px' }, '保存');
  save.onclick = async () => {
    const name = f.name.input.value.trim();
    if (!name) return toast('书名不能为空');
    await saveBook(
      {
        ...book,
        name,
        author: f.author.input.value.trim(),
        coverUrl: f.cover.input.value.trim(),
      },
      book.chapters
    );
    toast('已保存');
    sheet.close();
    if (onSaved) onSaved();
  };
  box.append(save);
  const sheet = openSheet(`编辑《${book.name}》`, box);
}
