/**
 * builtin-purify.js — 内置净化规则库
 *
 * 目标：各书源常见的通用杂质，在这里修一次，所有书源受益。
 * 后续遇到新的通用问题 → 往 BUILTIN_RULES 加一条即可（与用户自定义规则同格式）。
 *
 * 与用户自定义规则（settings.replaceRules）的关系：
 * - 先跑内置，再跑用户自定义（用户规则可覆盖/补充内置没覆盖的）。
 * - 内置规则随版本发布，用户不可编辑；在「净化」面板里只读展示，方便知道有哪些。
 *
 * 规则格式（与用户规则一致）：
 *   { name, pattern, replacement, isRegex, scope: 'content' | 'title' }
 * 另有代码级净化 dedupChapterTitle(html, chapterName)：
 *   正文开头重复章节标题（书源常把标题粘进正文），需要标题上下文，规则表达不了。
 *
 * 注意：内置规则要「宁缺毋滥」——每条都必须几乎不可能误杀正常正文。
 */

/** 转义正则特殊字符 */
const escRe = (s) => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * 内置正则规则。scope=content 对正文生效；scope=title 对章节标题生效。
 * isRegex=true 时 pattern 按正则（/g）使用。
 */
export const BUILTIN_RULES = [
  {
    name: '删除「（本章完）」标记',
    pattern: '\\s*[（(]\\s*本章完\\s*[)）]\\s*',
    replacement: '',
    isRegex: true,
    scope: 'content',
  },
  {
    name: '删除「本章未完，点击下一页继续阅读」',
    pattern: '\\s*本章[^。，]{0,6}未完[^。<]{0,8}点击(?:下一页|继续阅读)\\s*',
    replacement: '',
    isRegex: true,
    scope: 'content',
  },
  {
    name: '删除「更多精彩…请点击/到xx网」推广句',
    pattern: '\\s*更多精彩[^。<]{0,20}(?:请点击|请到|请关注|点击)[^。<]{0,30}(?:阅读|小说|网)[。！!]?\\s*',
    replacement: '',
    isRegex: true,
    scope: 'content',
  },
];

/**
 * 正文开头与章节标题重复的清理。
 *
 * 案例：书源把标题粘进正文 ——
 *   顶部大标题：第四十三章 我也是这么想的
 *   正文第一段：第四十三章 我也是这么想的 那个无数人都想知道的答案就此揭开。
 * 处理：删除正文开头的这段标题（含紧随的分隔标点），保留真正的正文。
 *
 * 误杀保护：
 * - 只匹配**开头**（独占一段 / 粘在段首两种形态），正文中间出现的同字串不动；
 * - 标题要足够具体才启用（长度 ≥6，或「第x章/节/卷」形态）；
 * - 标题与正文间的空白差异用 \s* 容忍（全角空格也算）。
 */
export function dedupChapterTitle(html, chapterName) {
  const name = String(chapterName || '').trim();
  if (!name || name.length < 4) return html;
  const specific = name.length >= 6 || /^第\s*[0-9一二三四五六七八九十百千零两]+\s*[章节卷回部]/.test(name);
  if (!specific) return html;
  const body = escRe(name).replace(/\s+/g, '\\s*');
  // ① 标题独占正文开头一段（只认字符串开头，正文中段的同名字串不动）
  let out = html.replace(new RegExp('^\\s*<p[^>]*>\\s*(?:<br\\s*/?>\\s*)*' + body + '(?:\\s*<br\\s*/?>)*\\s*</p>\\s*', 'i'), '');
  // ② 标题粘在正文开头：第一段段首（保留 <p> 开标签；标题后允许 <br> 换行）
  out = out.replace(new RegExp('^(<p[^>]*>)\\s*' + body + '\\s*(?:<br\\s*/?>\\s*)*[：:，,、]?\\s*', 'i'), '$1');
  // ③ 纯文本形态（无 <p> 包裹）：标题在字符串最开头（后允许换行）
  out = out.replace(new RegExp('^\\s*' + body + '\\s*(?:<br\\s*/?>\\s*)*[：:，,、]?\\s*', 'i'), '');
  return out;
}

/**
 * 排版后兜底：formatContent 已把正文归一成干净段落（<br>/<div>/全角空格都展平），
 * 此时第一段要么整段就是标题，要么以标题开头——按纯文本比对，覆盖 raw 阶段
 * 认不出的所有形态（标题在 <h1>/<div>/<strong> 里、标题后 <br> 换行等）。
 */
export function dedupTitleFormatted(html, chapterName) {
  const name = String(chapterName || '').trim();
  if (!name || name.length < 4) return html;
  const specific = name.length >= 6 || /^第\s*[0-9一二三四五六七八九十百千零两]+\s*[章节卷回部]/.test(name);
  if (!specific) return html;
  const body = escRe(name).replace(/\s+/g, '\\s*');
  // 标题可能被行内标签包裹（加粗/斜体等）
  const IN = '(?:<(?:strong|b|em|i|span)[^>]*>\\s*)*';
  const OUT = '(?:\\s*</(?:strong|b|em|i|span)>)*';
  // ① 标题独占第一段 → 整段删除
  let out = html.replace(new RegExp('^\\s*<p[^>]*>\\s*' + IN + body + '\\s*' + OUT + '\\s*</p>\\s*'), '');
  // ② 标题在第一段段首（后可跟 <br> 或直接接正文）→ 只删标题部分
  out = out.replace(new RegExp('^(<p[^>]*>)\\s*' + IN + body + '\\s*' + OUT + '\\s*(?:<br\\s*/?>\\s*)*[：:，,、]?\\s*'), '$1');
  // 删完后若第一段空了，顺手移除空段
  out = out.replace(/^(\s*<p[^>]*>)\s*<\/p>/, '');
  return out;
}
