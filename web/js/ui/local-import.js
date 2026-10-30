/**
 * local-import.js — 本地书籍导入（TXT / EPUB）
 *
 * 落地方式：books 记录 + chapters 缓存预写。
 * 阅读器 paint() 缓存优先 → 本地书零改动可读；预加载对本地书自动跳过（无 src）。
 * 移出书架时 removeBook 按 id 前缀清理缓存，本地书同样适用。
 */
import { state, saveBook, removeBook, toast, confirmBox } from './app.js';
import { db } from '../db.js';
import { parseTxt } from '../lib/txt.js';
import { parseEpub } from '../lib/epub.js';

/** 本地书固定 sourceUrl；bookUrl 为 local://<文件名>#<大小>，同文件重复导入即更新 */
export const LOCAL_SOURCE_URL = 'local://';

const MAX_BYTES = 60 * 1024 * 1024; // 60MB 软上限

function extOf(name) {
  const m = String(name || '').match(/\.([^.]+)$/);
  return m ? m[1].toLowerCase() : '';
}

/** 弹出文件选择器（浏览器 <input>；Android 壳已实现 onShowFileChooser） */
export function pickLocalFiles() {
  return new Promise((resolve) => {
    const inp = document.createElement('input');
    inp.type = 'file';
    inp.accept = '.txt,.epub';
    inp.multiple = true;
    inp.hidden = true;
    document.body.append(inp);
    inp.onchange = () => {
      const files = [...(inp.files || [])];
      inp.remove();
      resolve(files);
    };
    inp.oncancel = () => {
      inp.remove();
      resolve([]);
    };
    inp.click();
  });
}

/**
 * 导入一批本地文件。
 * @returns {Promise<{added:number, updated:number, failed:Array<{name:string,why:string}>}>}
 */
export async function importLocalFiles(files) {
  const result = { added: 0, updated: 0, failed: [] };
  for (const f of files) {
    const ext = extOf(f.name);
    if (ext !== 'txt' && ext !== 'epub') {
      result.failed.push({ name: f.name, why: '仅支持 .txt / .epub' });
      continue;
    }
    if (f.size > MAX_BYTES) {
      result.failed.push({ name: f.name, why: '文件超过 60MB' });
      continue;
    }
    try {
      const buf = await f.arrayBuffer();
      const parsed = ext === 'txt' ? parseTxt(buf, f.name) : await parseEpub(buf);
      const bookUrl = LOCAL_SOURCE_URL + f.name + '#' + f.size;
      const id = LOCAL_SOURCE_URL + '||' + bookUrl;
      const existed = state.books.find((b) => b.id === id);
      if (existed) {
        // 重新导入 = 更新：先清旧记录与缓存，避免章节数不一致的残留
        await removeBook(id);
      }
      const rec = await saveBook(
        {
          sourceUrl: LOCAL_SOURCE_URL,
          sourceName: '本地导入',
          name: parsed.title || f.name,
          author: parsed.author || '',
          intro: parsed.intro || '',
          coverUrl: parsed.cover || '', // EPUB 封面（dataURL）；TXT 无封面 → 书架用占位封面
          kind: ext.toUpperCase(),
          lastChapter: parsed.chapters.length ? parsed.chapters[parsed.chapters.length - 1].name : '',
          bookUrl,
          addedAt: existed ? existed.addedAt : Date.now(),
        },
        parsed.chapters.map((c, i) => ({ name: c.name, url: LOCAL_SOURCE_URL + 'ch/' + i }))
      );
      // 章节正文预写缓存（阅读器读取路径与网络书完全一致）。
      // 分片写入：几千章（含内嵌图片）一次性 putMany 会长时间锁住主线程 → 切页卡顿
      const rows = parsed.chapters.map((c, i) => ({ id: rec.id + '||' + i, content: c.html, cachedAt: Date.now() }));
      for (let i = 0; i < rows.length; i += 80) {
        await db.putMany('chapters', rows.slice(i, i + 80));
        await new Promise((r) => setTimeout(r, 0));
      }
      if (existed) result.updated++;
      else result.added++;
      toast(`已导入《${rec.name}》（${parsed.chapters.length} 章）`);
    } catch (e) {
      result.failed.push({ name: f.name, why: e.message || String(e) });
    }
  }
  return result;
}

/** 书架入口：选文件 → 导入 → 汇总提示 */
export async function importLocalBooksFlow() {
  const files = await pickLocalFiles();
  if (!files.length) return null;
  const big = files.filter((f) => f.size > MAX_BYTES);
  if (big.length && !(await confirmBox(`${big.map((f) => f.name).join('、')} 超过 60MB，仍要尝试导入吗？`))) {
    return null;
  }
  toast(`正在导入 ${files.length} 个文件…`, 60000);
  const r = await importLocalFiles(files);
  const parts = [];
  if (r.added) parts.push(`新增 ${r.added}`);
  if (r.updated) parts.push(`更新 ${r.updated}`);
  if (r.failed.length) parts.push(`失败 ${r.failed.length}（${r.failed[0].name}：${r.failed[0].why}）`);
  toast(parts.length ? '导入完成：' + parts.join('，') : '没有可导入的文件', r.failed.length ? 5000 : 2400);
  return r;
}
