/**
 * txt.js — 本地 TXT 书籍解析：编码探测 + 智能分章
 * 零 DOM 依赖（TextDecoder 浏览器/Node 均有），可在 Node 下单测。
 */

/**
 * 编码探测：BOM → UTF-8 严格解码 → GBK 回退。
 * 中文网文常见 UTF-8 与 GBK 混存；无 BOM 的 GBK 文本几乎必然含非法 UTF-8 序列。
 */
export function decodeText(buf) {
  if (buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    return new TextDecoder('utf-8').decode(buf);
  }
  if (buf.length >= 2 && buf[0] === 0xff && buf[1] === 0xfe) {
    return new TextDecoder('utf-16le').decode(buf);
  }
  if (buf.length >= 2 && buf[0] === 0xfe && buf[1] === 0xff) {
    return new TextDecoder('utf-16be').decode(buf);
  }
  try {
    const s = new TextDecoder('utf-8', { fatal: true }).decode(buf);
    if (!s.includes('\uFFFD')) return s;
  } catch (e) {
    /* 落入 GBK 分支 */
  }
  try {
    return new TextDecoder('gbk').decode(buf);
  } catch (e) {
    return new TextDecoder('utf-8').decode(buf);
  }
}

/** 《书名》（作者）/《书名》作者 试探 */
export function guessMetaFromName(fileName) {
  const base = String(fileName || '').replace(/\.[^.]+$/, '');
  let name = base;
  let author = '';
  const m = base.match(/《([^》]{1,60})》/);
  if (m) {
    name = m[1].trim();
    let rest = base.replace(m[0], ' ').trim();
    const am = rest.match(/[（(]\s*([一-龥A-Za-z0-9_· ]{1,20})\s*[）)]/);
    if (am) author = am[1].trim();
    else if (rest && rest.length <= 12 && !/[下载全集txtTXT完本精校版]/.test(rest)) author = rest.trim();
  }
  return { name: name || base, author };
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** 纯文本 → 段落 HTML（与引擎正文一致：reader 的 .content p 样式直接复用） */
export function textToHtml(text) {
  return String(text || '')
    .split(/\n+/)
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => `<p>${esc(l)}</p>`)
    .join('');
}

const CN_NUM = '0-9零〇一二三四五六七八九十百千万两';
const SEP = ' \\t　、,，:：\\-—~－(（【[「';
const SPECIAL = '序章|序言|楔子|引子|终章|终曲|尾声|终言|番外篇?|特别篇|后记|跋';

/**
 * 判定一行是否为章节标题：
 * - 第x章/节/卷/回/部/篇/集 + 分隔符 + ≤36 字，或无分隔符时后缀 ≤4 字
 * - 序章/楔子/番外等特殊章名同理
 * - 整行 ≤42 字（行首锚定由调用方保证）
 */
export function isTitleLine(t) {
  if (!t || t.length > 42) return false;
  /**
   * 章号后面那段是不是合法标题。
   * 关键：**先判断有没有分隔符，再 trim**。
   * 原来先 trim 再判断，分隔符被吃掉，于是「第1章 少年不识愁滋味」这种
   * 正常的带标题章节被判成正文 —— 整本书于是按 5000 字硬切成若干段，
   * 目录彻底错乱（40 章的书被切成 22 段）。
   */
  const restOk = (raw) => {
    const rest = String(raw || '').trim();
    if (!rest) return true; // 「第3章」单独成行
    if (new RegExp('^[' + SEP + ']').test(String(raw || ''))) return rest.length <= 40; // 有分隔符：标题可以长
    return rest.length <= 4; // 紧贴章号（如「第3章完」）：只接受极短后缀，避免把正文当成标题
  };
  let m = t.match(new RegExp('^(第[' + CN_NUM + ']+[章节卷回部篇集])(.*)$'));
  if (m) return restOk(m[2]);
  m = t.match(new RegExp('^(' + SPECIAL + ')(.*)$'));
  if (m) return restOk(m[2]);
  return false;
}

/**
 * 智能分章：返回 [{name, html}]。
 * - 标题行必须独立成行且短，避免把正文里的「第三章讲的是…」误判为标题
 * - 第一个标题前的实质内容（>300 字）单独成「首章」，否则并入首章正文
 * - 文件开头的目录区（连续多个空正文标题行）会被剔除
 * - 完全找不到标题：按 ~5000 字在段落边界兜底切分
 */
export function splitTxtChapters(text) {
  const src = String(text || '').replace(/\r\n?/g, '\n');
  const lines = src.split('\n');
  const marks = []; // {line, name}
  for (let i = 0; i < lines.length; i++) {
    const t = lines[i].trim();
    if (isTitleLine(t)) marks.push({ line: i, name: t });
  }

  let raw;
  if (marks.length) {
    raw = [];
    const pre = lines.slice(0, marks[0].line).join('\n');
    if (pre.replace(/\s+/g, '').length > 300) raw.push({ name: '首章', text: pre });
    else if (pre.trim()) marks[0].pre = pre;
    for (let i = 0; i < marks.length; i++) {
      const start = marks[i].line + 1;
      const end = i + 1 < marks.length ? marks[i + 1].line : lines.length;
      let body = lines.slice(start, end).join('\n');
      if (marks[i].pre) body = marks[i].pre + '\n' + body;
      raw.push({ name: marks[i].name, text: body });
    }
  } else {
    // 无分章：按段落聚合到 ~5000 字
    raw = [];
    const paras = src.split(/\n+/).filter((l) => l.trim());
    let cur = [];
    let curLen = 0;
    let idx = 1;
    for (const p of paras) {
      cur.push(p);
      curLen += p.length;
      if (curLen >= 5000) {
        raw.push({ name: `第 ${idx++} 节`, text: cur.join('\n') });
        cur = [];
        curLen = 0;
      }
    }
    if (cur.length) raw.push({ name: `第 ${idx++} 节`, text: cur.join('\n') });
  }

  const chapters = raw
    .map((c) => ({ name: c.name.replace(/\s+/g, ' ').trim() || '(未命名章节)', text: c.text, html: textToHtml(c.text) }))
    // 剔除空正文章节（目录区行会成串出现；"目录"等短前置也会并进来）
    .filter((c) => c.text.replace(/\s+/g, '').length >= 5);
  // 文件开头可能残留目录碎片（标题行被上一个目录项吃掉），持续剔除"正文只有标题行"的开头章节
  const titleOnly = (text) => {
    const ls = String(text).split('\n').map((l) => l.trim()).filter(Boolean);
    return ls.length <= 2 && ls.every(isTitleLine);
  };
  while (chapters.length > 1 && titleOnly(chapters[0].text)) chapters.shift();
  return chapters.map((c) => ({ name: c.name, html: c.html }));
}

/** 入口：ArrayBuffer → {title, author, intro, chapters:[{name, html}]} */
export function parseTxt(buf, fileName) {
  const text = decodeText(buf);
  const meta = guessMetaFromName(fileName);
  const chapters = splitTxtChapters(text);
  if (!chapters.length) throw new Error('未解析到任何内容');
  const firstText = chapters[0].html.replace(/<[^>]+>/g, '');
  return {
    title: meta.name,
    author: meta.author,
    intro: firstText.slice(0, 120),
    chapters,
  };
}
