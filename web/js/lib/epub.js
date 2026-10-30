/**
 * epub.js — EPUB 解析：container.xml → OPF → spine → XHTML → 章节。
 * 用正则提取 XML（OPF/container 为机器生成的规整文件），不依赖 DOMParser，
 * 浏览器与 Node 行为一致，可单测。
 */

import { unzip } from './zip.js';

const dec = new TextDecoder();

function xmlUnescape(s) {
  return String(s || '').replace(/&(amp|lt|gt|quot|apos|nbsp|#x?[0-9a-fA-F]+);/g, (m, e) => {
    switch (e) {
      case 'amp':
        return '&';
      case 'lt':
        return '<';
      case 'gt':
        return '>';
      case 'quot':
        return '"';
      case 'apos':
        return "'";
      case 'nbsp':
        return ' ';
      default:
        try {
          return e[1] === 'x' || e[1] === 'X' ? String.fromCodePoint(parseInt(e.slice(2), 16)) : String.fromCodePoint(parseInt(e.slice(1), 10));
        } catch (err) {
          return m;
        }
    }
  });
}

/** 取 <tag ...>...</tag> 的文本（容忍命名空间前缀与属性顺序） */
function tagText(xml, tag) {
  const m = xml.match(new RegExp('<' + tag + '(?:\\s[^>]*)?>([\\s\\S]*?)</' + tag + '>', 'i'));
  return m ? xmlUnescape(m[1]).trim() : '';
}

/** 取某标签的所有属性（用于 item / itemref / rootfile） */
function tagAttrs(xml, tag) {
  const re = new RegExp('<' + tag + '(\\s[^>]*?)/?>', 'gi');
  const list = [];
  let m;
  while ((m = re.exec(xml))) {
    const attrs = {};
    const ar = /([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
    let a;
    while ((a = ar.exec(m[1]))) attrs[a[1].toLowerCase()] = xmlUnescape(a[2] ?? a[3] ?? '');
    list.push(attrs);
  }
  return list;
}

/** 折叠 ./ ../ 与重复斜杠（zip key 匹配用；不同打包工具生成的目录层数不一致） */
function normSegments(p) {
  const out = [];
  for (const seg of String(p || '').split('/')) {
    if (!seg || seg === '.') continue;
    if (seg === '..') {
      out.pop();
      continue;
    }
    out.push(seg);
  }
  return out.join('/');
}

/**
 * href 相对 OPF 所在目录拼接，并解码 %xx。
 * - '/Images/x.jpg'（包根绝对路径）→ 去掉前导 / 后按包根解析，不再拼 OPF 目录
 * - '../Images/x.jpg'、'./a/b.jpg' → 归一化 .. 后再与 zip key 匹配
 */
function resolvePath(opfPath, href) {
  let h = decodeURIComponent(String(href || '').split('#')[0]).trim();
  if (!h) return '';
  if (h.startsWith('/')) return normSegments(h);
  const dir = opfPath.includes('/') ? opfPath.replace(/\/[^/]*$/, '') : '';
  return normSegments((dir ? dir + '/' : '') + h);
}

function stripTags(html) {
  return html
    .replace(/<(script|style)[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<[^>]+>/g, '\n')
    .replace(/&nbsp;/g, ' ');
}

/** XHTML → {name, html}（按 <p>/<br> 分段，纯文本换行兜底）
 * imgResolver：把 <img>/<image> 标签解析成 dataURL（正文插图）。缺省时图片被丢弃（旧行为）。
 * 图片以 token 过不了 stripTags 的特性保留下来：先换成 token，分段后按 token 拆段回填，
 * 图片独立成段（.img-p），与文字混排时拆成多个段落。
 */
const IMG_TOKEN = '\u0001IMG\u0002';
const escHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function htmlToChapter(xhtml, fallbackName, imgResolver) {
  const bodyM = xhtml.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
  const body = bodyM ? bodyM[1] : xhtml;
  let name = '';
  const hM = body.match(/<h[1-6][^>]*>([\s\S]*?)<\/h[1-6]>/i) || xhtml.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  if (hM) name = stripTags(hM[1]).replace(/\s+/g, ' ').trim();
  // 图片先换成 token（stripTags 只清标签不清 token），解析失败的图直接丢弃
  const imgTags = [];
  let body2 = body;
  if (imgResolver) {
    body2 = body.replace(/<(?:svg:)?(?:img|image)\b[^>]*>/gi, (tag) => {
      const src = imgResolver(tag);
      if (!src) return ' ';
      imgTags.push(`<img src="${src}" referrerpolicy="no-referrer" alt="">`);
      return `${IMG_TOKEN}${imgTags.length - 1}${IMG_TOKEN}`;
    });
  }
  const paras = body2
    .replace(/<(script|style)[\s\S]*?<\/\1>/gi, '')
    .replace(/<h[1-6][^>]*>[\s\S]*?<\/h[1-6]>/gi, '') // 标题已提取为章名，不再进正文
    .split(/(?:<\/p>|<br\s*\/?>|<\/div>|<\/li>)/i)
    .map((p) => stripTags(p).replace(/[ \t]+/g, ' ').trim())
    .filter((p) => p.length > 0 && !/^(mobipocket|calibre|copyright|www\.)\b/i.test(p));
  const html = paras
    .flatMap((p) => {
      // 按 token 拆段：图片独立成段（分页器整段搬运不切割），文字片段照常转义
      const parts = p.split(new RegExp(`${IMG_TOKEN}(\\d+)${IMG_TOKEN}`));
      const out = [];
      for (let i = 0; i < parts.length; i++) {
        if (i % 2 === 1) {
          const node = imgTags[+parts[i]];
          if (node) out.push(`<p class="img-p">${node}</p>`);
        } else if (parts[i]) {
          out.push(`<p>${escHtml(parts[i])}</p>`);
        }
      }
      return out;
    })
    .join('');
  return { name: name || fallbackName, html, hasImg: html.includes('class="img-p"') };
}

/**
 * @param {ArrayBuffer|Uint8Array} buf
 * @returns {Promise<{title, author, intro, chapters: [{name, html}]}>}
 */
export async function parseEpub(buf) {
  const { unzip } = await import('./zip.js');
  const files = await unzip(buf);
  // 大小写不敏感的 basename 索引：zip key 与 OPF href 的大小写/目录层数常不一致
  const byBase = new Map();
  for (const [k, v] of files) {
    const b = k.toLowerCase().split('/').pop();
    if (b && !byBase.has(b)) byBase.set(b, v);
  }
  /** 按路径取 zip 条目：精确 → 归一化 → basename 兜底 */
  const lookup = (path) => {
    if (!path) return null;
    if (files.has(path)) return files.get(path);
    const p = normSegments(path);
    if (files.has(p)) return files.get(p);
    const b = p.toLowerCase().split('/').pop();
    return byBase.get(b) || null;
  };
  const getText = (path) => {
    const f = lookup(path);
    return f ? dec.decode(f) : '';
  };

  // ① container.xml → OPF 路径
  const container = getText('META-INF/container.xml');
  let opfPath = '';
  const rf = tagAttrs(container, 'rootfile')[0];
  if (rf && rf['full-path']) opfPath = rf['full-path'];
  if (!opfPath) {
    // 兜底：找 META-INF 外第一个 .opf
    for (const k of files.keys()) if (k.toLowerCase().endsWith('.opf')) (opfPath = k);
  }
  if (!opfPath) throw new Error('EPUB 缺少 OPF 清单');
  const opf = getText(opfPath);
  if (!opf) throw new Error('OPF 读取失败');

  const title = tagText(opf, 'dc:title') || tagText(opf, 'title') || '未命名 EPUB';
  const author = tagText(opf, 'dc:creator') || '';

  // ② manifest：id → href
  const manifest = new Map();
  for (const it of tagAttrs(opf, 'item')) {
    if (it.id && it.href) manifest.set(it.id, { href: it.href, type: (it['media-type'] || '').toLowerCase(), props: it.properties || '' });
  }
  // ③ spine：阅读顺序（缺省用 manifest 顺序）
  const idrefs = tagAttrs(opf, 'spine').length ? tagAttrs(opf, 'itemref').map((a) => a.idref) : [...manifest.keys()];
  const order = (idrefs.length ? idrefs : [...manifest.keys()]).map((id) => manifest.get(id)).filter((m) => m && (m.type.includes('xhtml') || m.type.includes('html') || m.href.toLowerCase().endsWith('.xhtml') || m.href.toLowerCase().endsWith('.html')));

  const chapters = [];
  let idx = 0;
  const spineDocs = []; // 所有 spine 文档的 {path, href}，供封面兜底扫描
  const EXT_MIME = { jpg: 'image/jpeg', jpeg: 'image/jpeg', jfif: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp', svg: 'image/svg+xml' };
  const mimeOf = (p) => EXT_MIME[(String(p).split('.').pop() || '').toLowerCase()] || 'image/jpeg';
  // 正文插图解析：<img src> / <svg image href> → 相对路径 → zip 字节 → dataURL（4MB 上限）
  const toDataUrl = (bytes, path, maxBytes = 6 * 1024 * 1024) => {
    if (!bytes || !bytes.length || bytes.length >= maxBytes) return '';
    try {
      let s = '';
      const CH = 0x8000;
      for (let i = 0; i < bytes.length; i += CH) s += String.fromCharCode.apply(null, bytes.subarray(i, i + CH));
      return `data:${mimeOf(path)};base64,${btoa(s)}`;
    } catch (e) {
      return '';
    }
  };
  const resolveImg = (docPath) => (tag) => {
    const m = tag.match(/\bsrc=["']([^"']+)["']/i) || tag.match(/\b(?:xlink:)?href=["']([^"']+)["']/i);
    if (!m) return '';
    return toDataUrl(lookup(resolvePath(docPath, m[1])) || lookup(m[1]), m[1], 4 * 1024 * 1024);
  };
  const yieldUI = () => new Promise((r) => setTimeout(r, 0));
  for (const m of order) {
    idx++;
    if (/nav/i.test(m.props)) continue; // 跳过导航页
    const path = resolvePath(opfPath, m.href);
    spineDocs.push({ path, href: m.href });
    const xhtml = getText(path) || (files.get(m.href) ? dec.decode(files.get(m.href)) : '');
    if (!xhtml.trim()) continue;
    const ch = htmlToChapter(xhtml, `第 ${idx} 章`, resolveImg(path));
    // 空页/插图页过滤：纯文本 <2 字的保留插图页（hasImg），但书首的纯图页按封面页跳过
    if (ch.html.replace(/<[^>]+>/g, '').trim().length < 2) {
      if (!ch.hasImg || chapters.length === 0) continue;
    }
    chapters.push(ch);
    // 大书（几百章 + 内嵌图片 base64）连续解析会把主线程锁死，每 4 章让出一拍
    if (idx % 4 === 0) await yieldUI();
  }
  if (!chapters.length) throw new Error('EPUB 未解析到正文章节');
  const intro = chapters[0].html.replace(/<[^>]+>/g, '').slice(0, 120);

  // ④ 封面（多级兜底，逐级降级直到取出图）：
  //    a. properties="cover-image"       b. <meta name="cover" content=id>
  //    c. manifest 里 id/href 含 cover     d. 首个 spine 文档里的第一张图（封面页常只有图、被正文过滤）
  //    e. 直接扫 zip 内文件名含 cover 的图片
  const IMG_RE = /<img[^>]+src=["']([^"']+)["']/i;
  const SVG_IMG_RE = /<(?:svg:)?image[^>]+?(?:xlink:)?href=["']([^"']+)["']/i;
  let cover = '';
  try {
    // a/b/c：先按 manifest 元数据定位候选 item，再取字节。
    // 注意 item 必须真的是图片：EPUB2 的 <meta name="cover"> 常指向 HTML 封面页的 id，
    // 把 xhtml 字节硬转成 data:image/jpeg 只会得到裂图，要跳过继续降级。
    let item = null;
    for (const it of manifest.values()) {
      if (/cover-image/i.test(it.props) && it.type.startsWith('image/')) {
        item = it;
        break;
      }
    }
    if (!item) {
      const mm = opf.match(/<meta[^>]*name=["']cover["'][^>]*content=["']([^"']+)["']/i) || opf.match(/<meta[^>]*content=["']([^"']+)["'][^>]*name=["']cover["']/i);
      const cand = mm ? manifest.get(mm[1]) : null;
      if (cand && cand.type.startsWith('image/')) item = cand;
    }
    if (!item) {
      for (const it of manifest.values()) {
        if (it.type.startsWith('image/') && /cover/i.test(it.href + ' ' + it.props)) {
          item = it;
          break;
        }
      }
    }
    if (item) cover = toDataUrl(lookup(resolvePath(opfPath, item.href)) || lookup(item.href), item.href);

    // d：封面页正文常只有一张图（被「空页过滤」跳过），从 spine 文档里挖第一张图
    if (!cover) {
      for (const doc of spineDocs) {
        const xhtml = getText(doc.path);
        if (!xhtml) continue;
        const m = xhtml.match(IMG_RE) || xhtml.match(SVG_IMG_RE);
        if (!m) continue;
        cover = toDataUrl(lookup(resolvePath(doc.path, m[1])), m[1]);
        if (cover) break;
      }
    }

    // e：扫 zip 内文件名含 cover 的图片（ manifest 没登记也可能存在）
    if (!cover) {
      for (const [k, v] of files) {
        if (/cover/i.test(k) && /\.(jpe?g|png|gif|webp|bmp|svg)$/i.test(k)) {
          cover = toDataUrl(v, k);
          if (cover) break;
        }
      }
    }
  } catch (e) {
    /* 封面提取失败不影响导入 */
  }
  return { title, author, intro, cover, chapters };
}
