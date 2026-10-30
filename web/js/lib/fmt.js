/**
 * fmt.js — 默认排版：正文 HTML 自动整理
 *
 * 解决「没有排版的书」（网络书正文常是整页纯文本 + <br>，或一段到底）：
 * 1. 自动分段：超长行按句末标点拆段；短行（无终止标点）合并成自然段
 * 2. 去零宽/全角空格/行首行尾空白（全角空格可作段首缩进，统一交给 CSS text-indent）
 * 3. 纯文本行包成 <p>；单个 <div>/<span> 容器展平为段落
 * 4. 换行标签转段落；多余空段剔除
 *
 * 之后由 .content p / .page p 统一施加缩进（2em）、行距、段距（排版面板可调）。
 * 幂等：已排好版的段落再次处理结果不变，可对缓存正文反复调用。
 */

/** HTML 实体/标签 → 纯文本（用于判句读，不改最终 HTML 结构） */
function toPlain(s) {
  return String(s || '')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&#\d+;/g, ' ');
}

const escMap = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => escMap[c]);
}

/** 段内清理：去零宽、压缩空白、去掉行首缩进空白（缩进交给 CSS） */
function tidyInline(html) {
  return String(html || '')
    .replace(/[\u200B-\u200D\uFEFF]/g, '')
    .replace(/[\u3000\u00A0]/g, ' ')
    .replace(/[ \t]+/g, ' ')
    .replace(/\s*\n\s*/g, ' ')
    .trim();
}

/** 超长纯文本按句末标点拆成多段（保持 HTML 片段归属所在句） */
function splitLongParagraph(html, limit = 420) {
  const plain = toPlain(html);
  if (plain.length <= limit) return [html];
  // 按句读符号后的位置切；找不到句读则按 limit 硬切
  const breaks = [];
  const re = /[。！？!?；;…]/g;
  let m;
  while ((m = re.exec(plain))) {
    const at = m.index + m[0].length;
    if (at >= 80) breaks.push(at);
  }
  if (!breaks.length) {
    const out = [];
    for (let i = 0; i < plain.length; i += limit) out.push(i);
    return out.map((start, i) => slicePlainHtml(html, start, i + 1 < out.length ? start + limit : plain.length));
  }
  // 贪心聚句：累积到 ~limit 就断一段
  const parts = [];
  let start = 0;
  for (const b of breaks) {
    if (b - start >= limit) {
      parts.push([start, b]);
      start = b;
    }
  }
  if (start < plain.length) parts.push([start, plain.length]);
  return parts.map(([a, b]) => slicePlainHtml(html, a, b));
}

/**
 * 按纯文本区间切 HTML：文本节点按字符计数切开，标签完整保留。
 * 仅用于长段拆分，结构简单的正文片段足够。
 */
function slicePlainHtml(html, from, to) {
  const div = doc().createElement('div');
  div.innerHTML = String(html || '');
  const out = doc().createElement('div');
  let pos = 0;
  const walk = (src, dst) => {
    for (const n of [...src.childNodes]) {
      if (pos >= to) return;
      if (n.nodeType === 3) {
        const text = n.nodeValue || '';
        const s = Math.max(0, from - pos);
        const e = Math.min(text.length, to - pos);
        if (e > s) dst.append(doc().createTextNode(text.slice(s, e)));
        pos += text.length;
      } else if (n.nodeType === 1) {
        const clone = n.cloneNode(false);
        dst.append(clone);
        walk(n, clone);
        if (!clone.childNodes.length) clone.remove();
      }
    }
  };
  walk(div, out);
  return out.innerHTML;
}

/** 浏览器 DOM（Node 测试环境由 JSDOM 注入 document/DOMParser） */
function doc() {
  return globalThis.document;
}

/**
 * 正文 HTML → 默认排版后的段落 HTML（不含外层 .content 包裹）。
 * @param {string} html
 * @returns {string} 若干 <p> / <img> / <h*> / <blockquote> … 的拼接
 */
export function formatContent(html) {
  const raw = String(html || '').trim();
  if (!raw) return '';
  const root = doc().createElement('div');
  root.innerHTML = raw;

  // 1) <br> 断行 → 段落边界（连续 <br> 只算一次）
  root.querySelectorAll('br').forEach((br) => br.replaceWith(doc().createTextNode('\n')));

  // 2) 收集「顶层块」；行内容器（div/span/section 等非语义块）拆开重排
  const blocks = [];
  const BLOCK = /^(p|div|section|article|li|h[1-6]|blockquote|pre|ul|ol|table|figure|figcaption|hr|img)$/i;
  const INLINE_KEEP = /^(img|h[1-6]|blockquote|pre|ul|ol|table|figure|hr)$/i;

  // src: 'raw' = 原始文本行（无显式段落语义，短行可续接）；'para' = 显式段落（<p>/<li> 或已终止句）
  const pushText = (text, src) => {
    for (const line of String(text).split('\n')) {
      const t = line.replace(/[\u200B-\u200D\uFEFF\u3000\u00A0]/g, ' ').replace(/[ \t]+/g, ' ').trim();
      if (t) blocks.push({ type: 'text', src, html: esc(t) });
    }
  };

  const visit = (node, src) => {
    for (const n of [...node.childNodes]) {
      if (n.nodeType === 3) {
        pushText(n.nodeValue || '', src);
        continue;
      }
      if (n.nodeType !== 1) continue;
      const tag = n.tagName.toUpperCase();
      if (INLINE_KEEP.test(tag)) {
        blocks.push({ type: 'keep', html: n.outerHTML });
        continue;
      }
      if (tag === 'P' || tag === 'LI') {
        // 段内可能含 \n（来自 <br>）→ 按行拆段
        const inner = n.innerHTML;
        const parts = String(inner).split('\n').map((s) => tidyInline(s)).filter(Boolean);
        if (!parts.length) continue;
        for (const part of parts) {
          for (const seg of splitLongParagraph(part)) {
            const t = tidyInline(seg);
            if (t) blocks.push({ type: 'text', src: 'para', html: t });
          }
        }
        continue;
      }
      // 语义/行内容器：内部文本按 raw 收集（保留续行合并能力）
      visit(n, src === 'para' ? 'para' : 'raw');
    }
  };
  visit(root, 'raw');

  // 3) 短行合并（仅原始文本行续接；<p>/<li> 与已终止句各自成段）+ 超长段再拆
  const paras = [];
  let buf = '';
  let bufSrc = '';
  const flush = () => {
    if (!buf) return;
    for (const seg of splitLongParagraph(buf)) {
      const t = tidyInline(seg);
      if (t) paras.push(t);
    }
    buf = '';
    bufSrc = '';
  };
  const endsPara = (t) => /[。！？!?…；;：:]$/.test(t) || t.length >= 60;
  for (const b of blocks) {
    if (b.type !== 'text') {
      flush();
      paras.push(b.html);
      continue;
    }
    if (!buf) {
      buf = b.html;
      bufSrc = b.src;
      continue;
    }
    // 显式段落 / 已终止句 → 断段；两个裸文本短行才续接
    if (bufSrc === 'para' || b.src === 'para' || endsPara(buf) || b.html.length > 60) {
      flush();
      buf = b.html;
      bufSrc = b.src;
    } else {
      buf = buf + b.html;
    }
  }
  flush();

  return paras.map((t) => (/^<(img|h[1-6]|blockquote|pre|ul|ol|table|figure|hr)/i.test(t) ? t : `<p>${t}</p>`)).join('');
}
