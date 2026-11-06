// 净化 / 排版引擎函数（从 web/js/ui/app.js 抽出；不依赖任何 UI 模块）。
// 供原生排版与引擎单测（test/e2e.mjs）共用：原生侧 Rules.java 是这些函数的 Java 移植，
// 这里保留 JS 原版仅用于单测比对与网页调试，运行时以原生移植为准。
import { BUILTIN_RULES, dedupChapterTitle, dedupTitleFormatted } from '../lib/builtin-purify.js';
import { formatContent } from '../lib/fmt.js';

export const state = {
  sources: [], // BookSource[]
  books: [], // 书架（IndexedDB books 表）
  replaceRules: [], // [{pattern, replacement, isRegex, scope:'content'|'title', enabled}]
};

/** 应用净化到正文：先内置规则库（随版本更新），再用户自定义规则（enabled === false 的跳过） */
export function purifyContent(html, chapterName) {
  let out = String(html || '');
  // ① 内置净化：正文开头重复章节标题（需要标题上下文，代码级处理）
  out = dedupChapterTitle(out, chapterName);
  // ② 内置通用正则规则（lib/builtin-purify.js，遇到新的通用杂质往库里加即可）
  for (const r of BUILTIN_RULES) {
    if (!r || !r.pattern || r.enabled === false) continue;
    if ((r.scope || 'content') !== 'content') continue;
    try {
      out = r.isRegex ? out.replace(new RegExp(r.pattern, 'g'), r.replacement || '') : out.split(r.pattern).join(r.replacement || '');
    } catch (e) {
      /* 跳过坏规则 */
    }
  }
  // ③ 用户自定义规则
  for (const r of state.replaceRules) {
    if (!r || !r.pattern || r.enabled === false) continue;
    if ((r.scope || 'content') !== 'content') continue;
    try {
      out = r.isRegex ? out.replace(new RegExp(r.pattern, 'g'), r.replacement || '') : out.split(r.pattern).join(r.replacement || '');
    } catch (e) {
      /* 跳过坏规则 */
    }
  }
  return out;
}

/** 应用 scope='title' 的规则到章节标题（阅读页章题/进度标签展示用） */
export function purifyTitle(title) {
  let out = String(title || '');
  for (const r of state.replaceRules) {
    if (!r || !r.pattern || r.enabled === false || r.scope !== 'title') continue;
    try {
      out = r.isRegex ? out.replace(new RegExp(r.pattern, 'g'), r.replacement || '') : out.split(r.pattern).join(r.replacement || '');
    } catch (e) {
      /* 跳过坏规则 */
    }
  }
  return out;
}

/**
 * 正文最终 HTML：净化规则 + 默认排版。
 * 默认排版对所有书生效（本地 TXT/EPUB、搜书暂存、书架网络书同一套规则）：
 * 自动分段（短行合一 / 超长行按句拆段 / 纯文本行包 p）、去零宽与首行空白、
 * 强制 <p> 段落（缩进与行距由 .content p / .page p 的排版设置统一控制）。
 */
export function renderChapterHtml(html, chapterName) {
  // 两道去重：raw 阶段处理纯文本/独立段落形态；排版后再兜底一次——
  // formatContent 已把 <br>/<div>/行内标签全部归一，此时按首段文本比对最可靠
  return dedupTitleFormatted(formatContent(purifyContent(html, chapterName)), chapterName);
}
