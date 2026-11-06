/**
 * source.js — 书源对象：把一份 JSON 规则变成可执行的搜索/详情/目录/正文链路
 * 兼容 Legado 书源 JSON 的常用子集（见 README「兼容性清单」）。
 */
import { fetchViaProxy, resolveUrl } from '../http.js';
import { runJs } from './sandbox.js';
import { blockedReason, netReason } from './reason.js';
import {
  autoBookList,
  createRuleContext,
  evalOne,
  evalList,
  evalRule,
  evalText,
  extractRows,
  parseDoc,
  reCompile,
  subContext,
  toFieldHtml,
  toFieldText,
} from './rule.js';

const SEARCH_FIELDS = ['name', 'author', 'kind', 'intro', 'lastChapter', 'updateTime', 'bookUrl', 'coverUrl', 'wordCount'];

/**
 * 「必须走浏览器内核」的 host 表（host → 过期时间戳）。
 *
 * 必须是**模块级**：每次 action（搜索/详情/目录/正文）都会新建一个 BookSource 实例，
 * 记在实例上等于每次都忘 —— 用户点开一本书，详情、目录、正文各重撞一次 403、各多等 5 秒。
 * 服务页是常驻的，模块级变量能跨实例活到整个进程结束。
 */
const WEB_HOSTS = new Map();
// CF 兜底（浏览器内核取页）的异步回传注册表：原生侧取完页面后回调 window.__modouWebFetchDone(seq, json)。
// 必须模块级：BookSource 实例随每次 action 重建，记在实例上 Promise 就找不回来了。
let WEB_FETCH_SEQ = 0;
const WEB_FETCH_CALLBACKS = new Map();
if (typeof window !== 'undefined') {
  window.__modouWebFetchDone = (seq, json) => {
    const cb = WEB_FETCH_CALLBACKS.get(seq);
    if (cb) { WEB_FETCH_CALLBACKS.delete(seq); cb(json); }
  };
}
function safeJson(s) { try { return JSON.parse(s || '{}'); } catch (e) { return null; } }
const INFO_FIELDS = ['name', 'author', 'intro', 'kind', 'lastChapter', 'updateTime', 'wordCount', 'coverUrl', 'tocUrl', 'canReName'];
const TOC_FIELDS = ['chapterName', 'chapterUrl', 'isVolume', 'isVip', 'isPay'];

// 风控/挑战页嗅探：拿到响应后、按规则解析前调用。返回 null=未命中；命中返回 {kind,label}。
// 命中只表示「当前链路被拦截」，绝不意味整源失效（搜索被风控 ≠ 目录/正文也挂）。
// 短路顺序（从最硬到最软）：① 响应头特征 → ② Cloudflare body 特征 →
// ③ 登录墙/限流文案 → ④ 长度启发式（仅 text/html 且正文无任何标签）。
// 文案匹配用原始文本（中文关键词不含空白，无需归一化）；英文/Cloudflare 特征转小写匹配。
const BLOCK_WORDS = [
  '请登录', '登录后', '需要登录', '未登录', '登录即可',
  '访问过于频繁', '请求过于频繁', '请稍后再试', '操作过于频繁',
  '验证码', '滑动验证', '系统检测到异常', '安全风险', '确认您是真人', '安全验证',
];
// 强特征：只放「真挑战页」独有的隐藏字段/脚本名，正常页面不会出现
// ⚠ 绝不能把 cdn-cgi/challenge-platform 放进来：站点只要开了 Cloudflare 的 Bot Fight Mode /
//   托管挑战，**每个正常页面**都会被注入 /cdn-cgi/challenge-platform/scripts/jsd/main.js。
//   0.9.115 之前把它当强特征，导致 qinkan 这类 45KB 的正常首页被判成挑战页，
//   搜索入口探测直接放弃 —— 和 0.9.82 那次「用 cf-ray 判拦截」是同一类误判。
const CF_STRONG = ['__cf_chl_opt', '_cf_chl_opt', 'cf_chl_prog'];
// 弱特征：英文正文里也可能出现，必须配合「标题命中」或「页面很短」才判
// ⚠ 这里也不能放 challenge-platform：它出现在本站**每个正常页面**的 head 里（Cloudflare
//   自动注入的 jsd 脚本），配上「短页面」这条就等于「所有短页面都算挑战页」——
//   实测把 qinkan 的「信息提示 / 没有搜索到相关的内容」判成了挑战页。
//   真挑战页靠 CF_STRONG 的 _cf_chl_opt 或下面这些标题级特征识别，漏不了。
const CF_WEAK = ['just a moment', 'checking your browser', 'attention required! | cloudflare',
  'enable javascript and cookies to continue'];

// 内置现代浏览器 UA：只在「已经被 Cloudflare 拦下」时用它兜底重发一次，绝不主动覆盖书源的 UA。
//
// 导入源的 UA 大多是抄来的、停留在好几年前（Chrome 91 = 2021、微信 X5 更老）。Cloudflare 的
// WAF 有一条很常见的规则：UA 版本过旧 → 直接判爬虫 → 403「Attention Required! | Cloudflare」。
// 这不是站点挂了，也不是源写错了，改任何规则都没用 —— 换个 UA 立刻就过。
// 实测 97k.cc 搜索接口（POST /so/）：源里 Chrome 91 → 403；Chrome 131 / 安卓 Chrome 120 → 200 且正常出结果。
const MODERN_UA = 'Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';

/** 取 <title>，去掉多余空白；没有就返回空串 */
function titleOf(raw) {
  const m = /<title[^>]*>([\s\S]{0,400}?)<\/title>/i.exec(raw);
  return m ? m[1].replace(/\s+/g, ' ').trim() : '';
}

function detectBlockedPage(text, status, opts) {
  opts = opts || {};
  const headers = opts.headers || {};
  const ct = String(opts.contentType || '').toLowerCase();
  const raw = String(text || '');
  const tl = raw.toLowerCase();
  const title = titleOf(raw).toLowerCase();
  const len = raw.trim().length;

  // 1) 真·挑战头：Cloudflare 只有在「确实下发了挑战」时才加 cf-mitigated。
  //    ⚠ 绝不能用 server: cloudflare / cf-ray 判断 —— 站点只要挂在 Cloudflare 后面，
  //    每个正常响应都带这两个头。0.9.82 就是把 52书库的正常搜索页（状态 200、标题
  //    「为您找到：我的 相关内容」、.excerpt 命中 20 条）判成了挑战页。
  const mitigated = String(headers['cf-mitigated'] || headers['CF-Mitigated'] || '').toLowerCase();
  if (mitigated.indexOf('challenge') >= 0) return { kind: 'cf', label: 'Cloudflare 挑战页' };
  if (headers['x-challenge'] || headers['X-Challenge']) return { kind: 'cf', label: '挑战页' };

  // 2) 强 body 特征：challenge 平台脚本，正常页面不会出现
  for (const w of CF_STRONG) if (tl.indexOf(w) >= 0) return { kind: 'cf', label: 'Cloudflare 挑战页' };

  // 2.4) Cloudflare 的 WAF 拒绝页：「Attention Required! | Cloudflare」。
  //      这和「挑战页」是两回事 —— 挑战页是让你点一下/等 JS 跑完就放行，拒绝页是**直接按规则拒**，
  //      刷新多少次都一样。最常见触发原因就是 UA 太旧（源里写死 Chrome 91 之类），
  //      CF 判定为爬虫直接 403。所以单独给 waf 标记，好让上层「换现代 UA 重发」，
  //      也免得用户以为是站点在让他过验证。实测 97k.cc 就是这一类。
  if (title.indexOf('attention required') >= 0 && tl.indexOf('cloudflare') >= 0) {
    return { kind: 'cf', label: 'Cloudflare 拒绝访问（UA 被拦）', waf: true };
  }

  // 2.5) 站点明说「搜索间隔限制」：帝国CMS 一类站点在两次搜索之间限制 N 秒，
  //      回一张「信息提示 / 系统限制的搜索时间间隔为 10 秒,请稍后再搜索」的 3 秒跳转页。
  //      这既不是登录墙、也不是站点故障，更不是参数名写错 —— 必须单独归类。否则会被
  //      误诊成「核对 searchUrl 的参数名」，用户改半天 searchUrl 也毫无变化。
  //      ⚠ 必须排在弱特征之前：这类页通常只有 2~3KB，而挂了 Cloudflare 的站连限流页也会
  //      被注入 /cdn-cgi/challenge-platform 脚本，先跑弱特征就会把它误判成「挑战页」
  //      （0.9.115 实测 qinkan：明写「10 秒间隔」的页被判成 Cloudflare 挑战页）。
  //      站点自己写清楚的文案，优先级高于任何英文特征猜测。
  const rateM = /(?:搜索|请求|访问)[^。；;<]{0,10}间隔[^0-9]{0,8}(\d{1,3})\s*秒/.exec(raw)
    || /间隔[^0-9]{0,8}(\d{1,3})\s*秒[^。；;<]{0,10}(?:搜索|再搜)/.exec(raw);
  const ratePhrase = /系统限制[^。；;<]{0,10}搜索|搜索[^。；;<]{0,8}间隔|请稍后再搜索|搜索(?:过于|太)?频繁|搜索太频繁/;
  if (len < 8000 && ratePhrase.test(raw)) {
    const sec = rateM ? Math.max(1, Math.min(60, parseInt(rateM[1], 10))) : 10;
    // label 不能带数字（reason 契约）：秒数单独用 waitSec 字段带出去，只出现在日志里
    return { kind: 'ratelimit', label: '站点限制搜索间隔', waitSec: sec };
  }

  // 3) 弱特征：必须「标题就是挑战页」或「页面很短」，否则只是正文里的普通英文
  for (const w of CF_WEAK) {
    if (tl.indexOf(w) >= 0 && (title.indexOf(w) >= 0 || len < 4000)) return { kind: 'cf', label: 'Cloudflare 挑战页' };
  }

  // 4) 登录墙 / 限流文案：必须在「标题命中」或「页面极短」时才算。
  //    正常搜索结果页的简介、页脚导航里经常出现「请登录」「验证码」，全文一扫就误判；
  //    52书库那份 15310 字符的正常页面就是这么被判死的。
  const gatedTitle = /(登录|登入|验证码|安全验证|访问受限|访问被拒|访问异常|拦截)/.test(title);
  for (const w of BLOCK_WORDS) {
    if (raw.indexOf(w) >= 0 && (gatedTitle || len < 3000)) {
      return { kind: 'gated', label: '登录墙/访问限制（' + w + '）' };
    }
  }

  // 5) 长度启发式：仅 text/html，2xx 但正文极短且**没有任何标签**——纯文本壳/JS 跳转/站点报错。
  //    JSON 短响应（{"data":[]}）是合法空结果，用 contentType 排除；阈值取 800 防误伤极简列表页。
  if (status >= 200 && status < 300 && ct.indexOf('text/html') >= 0 && len < 800 && raw.indexOf('<') < 0) {
    // 站点自己的报错文本以前都被扣上「风控拦截」：中文（「数据库连接出错，请打开 conn.asp
    // 文件检查连接字串」）、英文（「Cann't connect to DB!」「too many connections」）都是。
    // 用户于是去换 UA、挂代理，方向全错 —— 这类是站点故障，不是拦截，必须单独归 sitedown。
    const msg = raw.replace(/\s+/g, ' ').trim().slice(0, 80).replace(/\d/g, '#');
    if (/数据库|连接出错|连接失败|连接字串|系统错误|服务端错误|服务器错误|出错|error|exception|can'?t connect|connect to (?:the )?db|database|db connection|too many connections|connection (?:failed|refused|error|timed out)|sql (?:error|state)/i.test(msg)) {
      return { kind: 'sitedown', label: msg };
    }
    return { kind: 'short', label: '响应体异常偏短且无标签（疑似拦截壳）' };
  }
  return null;
}

/**
 * 只是「JS 跳转壳页」吗？
 *
 * 有些站（WP-Manga 系、部分漫画站）不直接发内容，先回一页 5KB 左右的小 HTML：
 * 标题写 Redirecting...，body 里只有一个 loading 动画 + 一段
 * `document.cookie="xxx=1"` / `location.replace(...)`，要浏览器把 cookie 算出来再刷回去。
 * 这种页面**本身就没有书**，规则写得再对也是 0 条 —— 必须跟「规则失效」分开说，
 * 否则用户会一直去改选择器。
 *
 * 三个条件同时成立才算，宁可漏判也不能误伤：正常页面里也常有一段
 * `window.location=...` 的广告/统计脚本。
 *   ① 标题是「跳转/加载/请稍候/安全验证」，或标题为空且一个链接都没有；
 *   ② 有客户端跳转 / 写 cookie 的脚本；
 *   ③ 可点链接 ≤ 8 个（真结果页/详情页不会这么少），且整页 < 60KB。
 */
function detectJsShell(text, doc) {
  const raw = String(text || '');
  if (!raw || raw.length > 60000) return null;
  let links = 0;
  try {
    links = doc && doc.body ? doc.body.querySelectorAll('a[href]').length : 0;
  } catch (e) {
    links = 0;
  }
  if (links > 8) return null;
  const t = raw.toLowerCase();
  if (!/location\.replace\s*\(|window\.location\s*(?:=|\.(?:replace|assign|href))|document\.cookie\s*=|http-equiv=["']?refresh/.test(t)) {
    return null;
  }
  const title = titleOf(raw);
  const titleHit = /redirect|loading|please wait|just a moment|正在跳转|跳转中|加载中|请稍候|安全验证|人机验证|验证中/.test(title.toLowerCase());
  if (!titleHit && !(title === '' && links === 0)) return null;
  return { title, links };
}

/** 壳页的统一说法（带标题，让用户一眼认出这不是他的页面） */
function jsShellHint(shell) {
  const t = shell && shell.title ? `（页面标题「${shell.title}」）` : '';
  return `站点返回的是 JS 跳转页${t}，真实内容要浏览器执行脚本后才出现，本次请求拿不到内容 —— 换源，改选择器无效`;
}

/**
 * 从首页里挑「像书名」的链接文本，给探针词用。
 *
 * 判据只认结构，不认站点：2~16 字、不是导航词、不是「第 N 章 xxx」这种章节标题、
 * 地址里带两位以上数字（书籍 id）。认不出来就返回空，上层退回兜底词 —— 不猜。
 */
const PROBE_NAV = /^(首页|书架|我的|分类|排行|排行榜|全部|更多|登录|注册|搜索|下载|客户端|app|上一页|下一页|上一章|下一章|完本|连载|目录|详情|加入书架|开始阅读|推荐|最新|热门|全本|免费|专题|作者|公告|返回|顶部)$/i;
/** 明显不是书名的文案（站点把「2026年排行榜」这种链接也放在带数字的路径下） */
const PROBE_BAD = /排行|榜单|专题|下载|阅读|全文|最新章节|作者|分类|更多|免费|书库|^第\s*[0-9一二三四五六七八九十百千]+\s*章/;

/**
 * 把列表上的书名清洗成「搜索友好」的样子：
 *   囚帝珠_平流雾【完结】 → 囚帝珠      男人都想日她(高HNP) → 男人都想日她
 * 站点搜索大多按书名主体模糊匹配，带着【完结】/作者后缀/括号标签去搜反而命中不了。
 */
function cleanProbeName(txt) {
  return String(txt || '')
    .replace(/【[^】]*】/g, '')
    .replace(/\([^)]*\)/g, '')
    .replace(/（[^）]*）/g, '')
    .replace(/[[\]［］]/g, '')
    .replace(/[_\-—–|].*$/, '')
    .replace(/\s+/g, '')
    .trim();
}

function probeNames(doc, baseUrl) {
  const out = [];
  if (!doc || !doc.body) return out;
  let as = [];
  try {
    as = doc.body.querySelectorAll('a[href]');
  } catch (e) {
    return out;
  }
  const scored = [];
  // 书籍详情地址特征：/b/、/book/、/read/ 等已知详情前缀，或「数字 id.html」，或 /id/id/
  const BOOK_DETAIL_RE = /\/(?:b|book|read|novel|info|detail|chapter|show|art)\/|\/\d+[\w-]*\.html$|\/\d+\/\d+\/?$/i;
  // 分类/列表/排行页特征（阅友的 /l/sc/0/21.html 就是分类，带数字但不是书）
  const CAT_RE = /\/(?:l|sc|category|sort|list|cate|tag|rank|top|class|fenlei|channel|column|zt|topic|search|so)\//i;
  for (const a of as) {
    const raw = String(a.textContent || '').replace(/[\s\u3000]+/g, '').trim();
    if (raw.length < 2 || raw.length > 24) continue;
    if (PROBE_NAV.test(raw) || PROBE_BAD.test(raw)) continue;
    const txt = cleanProbeName(raw);
    if (txt.length < 2 || txt.length > 16) continue;
    if (!/[\u4e00-\u9fa5]/.test(txt)) continue;            // 纯英文/数字的多半是功能入口
    const href = String(a.getAttribute('href') || '').trim();
    if (!href || /^(javascript:|#|mailto:|tel:)/i.test(href)) continue;
    const u = resolveUrl(baseUrl, href);
    let path = '';
    try {
      path = new URL(u).pathname;
    } catch (e) {
      continue;
    }
    // 只认「书籍详情」地址：带已知详情前缀 / 数字 id.html，且不是分类列表页
    // （分类页也带数字，不拦会把「女频/精选」这类标签当书名）
    if (CAT_RE.test(path) || !BOOK_DETAIL_RE.test(path)) continue;
    // 排序：短书名优先 —— 站内搜索多是模糊匹配，越短越容易命中
    // （实测「翘臀小奶狗」整串搜不到，但「小奶狗」能搜出 4 本）
    let score = 0;
    if (txt.length <= 6) score += 5;
    else if (txt.length <= 10) score += 2;
    if (!/[()（）\[\]【】a-zA-Z_]/.test(raw)) score += 2;
    if (/^\/\d+\/\d+\/?$/.test(path)) score += 1;
    if (!out.includes(txt)) {
      out.push(txt);
      scored.push({ txt, score, src: 2 });
    }
    if (out.length >= 6) break;
  }
  // SPA 站点（如阅友小说）详情入口是 onclick="newWebView('/b/123.html')" 而非 <a href>，
  // 上一遍 a[href] 捞不到书名会直接退回兜底词。这里只在「一遍没认出任何书名」时才退一步：
  // 找带详情路径（/b/数字、/book/数字.html 等）的 onclick 元素，取它内部书名文本当探针词。
  // 这类是「确定是书」的入口，权重最高（src=1），优先于普通 a[href] 详情链接。
  if (out.length === 0) {
    let els = [];
    try {
      els = doc.body.querySelectorAll('*[onclick]');
    } catch (e) {
      els = [];
    }
    for (const el of els) {
      const oc = String(el.getAttribute('onclick') || '');
      // 与 a[href] 同一套判据：只认书籍详情路径（/b/数字、数字 id.html 等），排除分类页
      if (!BOOK_DETAIL_RE.test(oc) || CAT_RE.test(oc)) continue;
      // 优先取内部「标题/书名」子节点，避免把作者、分类等串进书名
      let node = el.querySelector('.title, .name, [class*="title"], [class*="name"]');
      const raw = String((node || el).textContent || '')
        .replace(/[\s\u3000]+/g, '')
        .trim();
      const txt = cleanProbeName(raw);
      if (txt.length < 2 || txt.length > 16) continue;
      if (!/[\u4e00-\u9fa5]/.test(txt)) continue;
      if (PROBE_NAV.test(txt) || PROBE_BAD.test(txt)) continue;
      let score = txt.length <= 6 ? 5 : txt.length <= 10 ? 2 : 0;
      if (!/[()（）\[\]【】a-zA-Z_]/.test(raw)) score += 2;
      if (!out.includes(txt)) {
        out.push(txt);
        scored.push({ txt, score, src: 1 });
      }
      if (out.length >= 6) break;
    }
  }
  // 最后一道兜底：两种详情特征都认不出来时（盗墓笔记的 /dao-mu-bi-ji-1 这种 slug 就是），
  // 退成「首页里出现次数最多的中文短标题」。探针词只要**站内确实存在**就行，
  // 不需要它一定是详情链接 —— 一本书的标题在首页「最新更新」「推荐」里通常反复出现，
  // 比硬编码「斗破苍穹」靠谱得多（很多站根本没这本书，会得出「站内没有这本书」的错误结论）。
  if (out.length === 0) {
    // 首页高频词里混着大量评论区/功能词（实测盗墓笔记首页出现最多的是「回复」）。
    // 这些词站内搜索根本检索不到，拿它当探针会得出「站内没有这本书」的错误结论，必须排掉。
    const STOP = /^(回复|评论|查看|更多|全部|详情|展开|收起|点赞|收藏|推荐|热门|最新|全文|阅读|章节|目录|上一章|下一章|上一页|下一页|加入书架|开始阅读|投推荐票|月票|打赏|作者|字数|状态|更新时间|点击|下载|登录|注册|搜索|首页|书架|分类|排行|完本|连载|免费|全本|投票|分享|举报|返回|顶部|网友评论|发表评论|查看全部|查看更多|评论列表|置顶|精华|沙发|楼主|举报该帖|广告|正文|简介|封面|标签|全部作品|连载中|已完结)$/;
    const freq = new Map();
    try {
      for (const a of doc.body.querySelectorAll('a[href]')) {
        const txt = String(a.textContent || '').replace(/[\s\u3000]+/g, '').trim();
        if (txt.length < 2 || txt.length > 12) continue;
        if (!/^[\u4e00-\u9fa5]+$/.test(txt)) continue;          // 纯中文：排除「APP」「TOP10」
        if (PROBE_NAV.test(txt) || PROBE_BAD.test(txt) || STOP.test(txt)) continue;
        const href = String(a.getAttribute('href') || '');
        // 评论/回复类锚点统一指向这几个特征，别让它们参与投票
        if (/comment|reply|respond|#/i.test(href)) continue;
        freq.set(txt, (freq.get(txt) || 0) + 1);
      }
    } catch (e) { /* 忽略 */ }
    // 出现 ≥2 次的优先（重复＝列表里的书名，而不是一句正文里的词），再按次数、长书名排序
    // （长一点更不像功能入口：2 字词里「回复/更多」这类占比太高）
    const cand = [...freq.entries()].filter(([, n]) => n >= 2)
      .sort((a, b) => b[1] - a[1] || b[0].length - a[0].length)
      .map((x) => x[0]);
    for (const t of cand) {
      if (out.length >= 3) break;
      out.push(t);
    }
  }
  // 选词优先级：onclick 详情入口(src=1) > a[href] 详情链接(src=2)；同档内短书名优先
  // （站内搜索多为模糊匹配，越短越易命中）。
  scored.sort((a, b) => b.src - a.src || b.score - a.score || a.txt.length - b.txt.length);
  return scored.length ? scored.map((x) => x.txt) : out;
}

/**
 * 站点是不是**明说**了「没有匹配结果」？
 *
 * 这类页面有固定文案（搜索"xx"无结果 / 没有找到 / 抱歉…），且体积很小、一个书名链接都没有。
 * 区分它的意义：以前这种页会被诊断成「关键词没出现，参数可能不对」，把用户往改 searchUrl
 * 上带 —— 其实参数完全正确、规则也没坏，就是站内没这本书。换关键词或换源才是出口。
 *
 * 判据收窄：只认中文固定短语 + 页面 < 60KB，避免正常列表页里偶然出现这些字眼被误判。
 */
// 站点「明说没有结果」的文案。命中即证明搜索链路是通的（编码/参数/规则都没坏，
// 只是这个词站内没有），校验必须据此判可用，绝不能把源判成失效。
// 0.9.136 扩充：以前只有 8 个词，站点回「暂无数据」「没有查询到」「No results」
// 就识别不到，两个关键词都 0 条时被整批误判成规则失效 —— 新导入源成片变红的主嫌之一。
const NO_RESULT_WORDS = [
  '无结果', '没有找到', '未找到', '没有搜到', '搜不到', '暂无相关', '没有相关', '搜索结果为空',
  '暂无数据', '没有数据', '没有查询到', '未查询到', '查询无结果', '搜索无结果', '无搜索结果',
  '暂无搜索结果', '未搜索到', '没有符合条件', '没有找到相关', '暂无相关结果', '没有相关内容',
  '暂无记录', '没有这本书', '暂无书籍', '未找到相关', '找不到相关', '暂无结果',
  'no results', 'no result found', 'no books found', 'nothing found', 'no matches', '0 results'
];
function detectNoResult(text) {
  const raw = String(text || '');
  if (!raw || raw.length > 60000) return '';
  // 英文站大小写不一，统一转小写再比；中文不受影响
  const low = raw.toLowerCase();
  for (const w of NO_RESULT_WORDS) {
    if (low.indexOf(w.toLowerCase()) >= 0) return w;
  }
  return '';
}

/**
 * 嗅探命中但规则解析出了东西 → 撤销标记。
 *
 * 嗅探天生是启发式：站点挂 CDN、简介里写「请登录」、正文里出现 "just a moment"，
 * 都可能误判。所以在判死之前必须让规则先说话 —— 能把书名/章节/正文解出来，
 * 就证明页面是正常的，那条「风控拦截」只是噪声。
 * 反过来，解析 0 条时保留标记，才能给出「是被拦截了还是规则失效」的准确原因。
 */
function clearBlockIfParsed(r, logs, what) {
  if (!r || !r.blocked) return;
  logs.push(`嗅探曾命中「${r.blocked}」，但已解析出${what}，按正常页面继续`);
  r.blocked = null;
}

/**
 * 搜索响应是 JSON 接口、但 ruleSearch 没匹配上时，按「一大堆结构相同的对象、每项含书名+地址」
 * 的特征自动找出结果数组，并给出可直接复制的 JSON 规则。HTML 源靠 autoBookList 找 <a> 链接，
 * JSON 源没有 <a>，必须走这一条，否则用户只会看到一句「没拿到可用书籍」无从下手（阅友小说就是）。
 *
 * @returns {{items:{name:string,bookUrl:string}[], hint:string, ruleStr:string}|null}
 */
function autoBookListJson(json) {
  if (!json || typeof json !== 'object') return null;
  const isName = (v) => typeof v === 'string' && v.length >= 2 && v.length <= 40 && /[一-龥]/.test(v);
  const isUrl = (v) => typeof v === 'string' && (/^https?:\/\//i.test(v) || v.startsWith('/'));
  const isId = (v) => typeof v === 'number' || (typeof v === 'string' && /^\d{2,}$/.test(v));

  // 收候选数组：项全是对象、长度 >= 3
  const cands = [];
  const walk = (node, path) => {
    if (Array.isArray(node)) {
      if (node.length >= 3 && node.every((x) => x && typeof x === 'object' && !Array.isArray(x))) {
        cands.push({ arr: node, path });
      }
      for (const it of node) if (it && typeof it === 'object') walk(it, path);
      return;
    }
    if (node && typeof node === 'object') {
      for (const k of Object.keys(node)) {
        if (node[k] && typeof node[k] === 'object') walk(node[k], path + '.' + k);
      }
    }
  };
  walk(json, '$');
  if (!cands.length) return null;

  const IMG_RE = /pic|cover|img|image|avatar|logo|thumb|banner/i;
  let best = null;
  for (const c of cands) {
    const keys = Object.keys(c.arr[0]);
    const nameKey = keys.find((k) => isName(c.arr[0][k]));
    if (!nameKey) continue;
    // 地址字段：是 URL 串、且不像图片/头像（详情页链接才当 bookUrl，封面图不算）
    const urlKey = keys.find((k) => isUrl(c.arr[0][k]) && !IMG_RE.test(k)) || null;
    const coverKey = keys.find((k) => isUrl(c.arr[0][k]) && IMG_RE.test(k)) || null;
    // 没有现成详情地址 → 退到数字 id（bookUrl 需用户用 @js 拼前缀，不能直接造链接）
    // id 字段优先挑书名相关的（wapBookId / bookId 比裸 id 更准，值通常相同）
    const idKey = urlKey ? null : (
      keys.find((k) => isId(c.arr[0][k]) && /book|wap|bid|novel/i.test(k))
      || keys.find((k) => isId(c.arr[0][k]))
      || null);
    const nameCnt = c.arr.filter((it) => isName(it[nameKey])).length;
    const hasAddr = !!(urlKey || idKey);
    // 书名命中过半才算「书列表」；有地址字段额外加权（这才是能点开的结果）
    const score = nameCnt * 2 + (hasAddr ? c.arr.length : 0);
    if (nameCnt >= Math.max(2, Math.floor(c.arr.length * 0.5)) && (!best || score > best.score)) {
      best = { c, nameKey, urlKey, coverKey, idKey, score };
    }
  }
  if (!best) return null;
  const { c, nameKey, urlKey, coverKey, idKey } = best;

  // 现成详情 URL → 直接可用；只有 id → 不自动造地址（避免拼出打不开的链接），交给用户用 @js 拼
  const items = urlKey
    ? c.arr
        .map((it) => ({ name: String(it[nameKey] || ''), bookUrl: String(it[urlKey] || '') }))
        .filter((it) => it.name && it.bookUrl)
    : [];

  const keys = Object.keys(c.arr[0]);
  const authorKey = keys.find((k) => /author|writer|zuozhe/i.test(k) && isName(c.arr[0][k])) || null;
  let ruleStr = `  bookList: ${c.path}\n  name: $.${nameKey}`;
  if (authorKey) ruleStr += `\n  author: $.${authorKey}`;
  if (coverKey) ruleStr += `\n  coverUrl: $.${coverKey}`;
  if (urlKey) {
    ruleStr += `\n  bookUrl: $.${urlKey}`;
  } else if (idKey) {
    ruleStr += `\n  bookUrl: $.${idKey}@js:'/详情前缀/' + result + '.html'  // ${idKey} 是数字 id、无现成详情地址，需按站点真实详情页 URL 规律拼接前缀（可在书籍详情页或首页条目里看到）`;
  }
  const hint = `响应是 JSON 接口，已找到结果数组「${c.path}」（共 ${c.arr.length} 项，书名字段「${nameKey}」）`
    + (urlKey ? `，详情地址字段「${urlKey}」已可用。` : `，但只有 id 字段「${idKey}」、没有现成详情地址，bookUrl 需用 @js 按站点真实详情页规律拼接前缀。`)
    + ` 把下列「搜索规则」复制进书源即可（搜索/详情/目录/正文按需再补）：\n${ruleStr}`;
  return { items, hint, ruleStr };
}

/** 取 URL 的 host（小写）；非法 URL 返回空串 */
function hostOfUrl(u) {
  try {
    return new URL(String(u)).host.toLowerCase();
  } catch (e) {
    return '';
  }
}

/** URL 所在目录（含末尾斜杠）；非法 URL 返回空串 */
function dirOfUrl(u) {
  try {
    const p = new URL(String(u)).pathname;
    const i = p.lastIndexOf('/');
    return i >= 0 ? p.slice(0, i + 1) : p;
  } catch (e) {
    return '';
  }
}

/**
 * 剪掉混进目录的站内导航/推荐链接。
 *
 * 裸标签规则（`ul@li!-1@a`）会在整页所有 <ul> 上生效：分类菜单、作者其他作品、
 * 页脚导航全都被当成章节。用户看到的是「目录里第一本是耽美、第二是言情」。
 * 判据只看 URL：真章节一定和详情页同一目录（/novel/72122/xxx.html），
 * 分类/推荐落在别的目录（/wapsort/5_1.html）。
 *
 * 只在「同目录 ≥3 条且确实有异类」时才剪 —— 认不出来就原样返回，不猜。
 */
function pruneAlienChapters(list, bookUrl) {
  if (!Array.isArray(list) || list.length < 4) return list;
  const bd = dirOfUrl(bookUrl);
  if (!bd) return list;
  const same = list.filter((c) => {
    const d = dirOfUrl(c && c.url);
    if (!d) return false;
    return d.indexOf(bd) === 0 || bd.indexOf(d) === 0;
  });
  if (same.length < 3 || same.length === list.length) return list;
  return same;
}

/** 取 URL 的 origin（协议 + host，不含路径）；非法 URL 返回空串 */
function originOfUrl(u) {
  try {
    const x = new URL(String(u));
    return x.protocol + '//' + x.host;
  } catch (e) {
    return '';
  }
}

/* ---------- 调试追踪：给「书源详情 → 调试」用的结构化日志 ---------- */

/** 响应片段上限：够看清是登录墙 / 改版页 / 正常列表，又不至于把回传撑爆 */
const TRACE_HTML_MAX = 1600;

function snipHtml(t, n = TRACE_HTML_MAX) {
  const s = String(t == null ? '' : t);
  if (s.length <= n) return s;
  return s.slice(0, n) + '\n…（共 ' + s.length + ' 字符，已截断）';
}

/** 一条链路追踪：URL / 状态 / 编码 / 字节数 / 规则原文 / 命中数 / 原文片段 */
function traceStep(stage, label, r, extra) {
  return Object.assign({
    stage,
    label,
    url: r && (r.reqUrl || r.finalUrl) || '',
    finalUrl: r && r.finalUrl || '',
    method: r && r.method || 'GET',
    status: r ? Number(r.status) || 0 : 0,
    charset: r && r.charset || '',
    contentType: r && r.contentType || '',
    bytes: r && r.text ? String(r.text).length : 0,
    html: r ? snipHtml(r.text) : '',
    // 证书降级：Java 层在严格校验失败后自动改 http / 忽略校验，这里把痕迹带进 trace，
    // 调试台会明写一行「⚠ 站点证书已过期…」。降级绝不能是静默的。
    ssl: r && r.ssl || '',
    sslNote: r && r.sslNote || '',
  }, extra || {});
}

/** "##pat##rep##pat##rep" 串行替换 */
export function applyReplaceString(s, spec) {
  if (!s || !spec) return s || '';
  const parts = String(spec).split('##');
  let out = String(s);
  for (let i = 0; i + 1 < parts.length; i += 2) {
    try {
      // 复用 rule.js 的 reCompile：带 RE_CACHE 缓存 + reExplosive 灾难性回溯拦截，
      // 避免规则里一条 (a+)+ 把整个书源 JS 线程按死（也绕开了每次 new RegExp 的开销）。
      out = out.replace(reCompile(parts[i], 'g'), parts[i + 1]);
    } catch (e) {
      /* 跳过坏正则 / 被防爆拦截的规则 */
    }
  }
  return out;
}

/* ---------- 目录兜底识别：规则一条没中时，按数据结构自动找章节 ---------- */

const AUTO_NAME_KEYS = ['chapterName', 'chapter_name', 'chapterTitle', 'chapter_title', 'chaptername', 'cname', 'title', 'name'];
const AUTO_URL_KEYS = ['chapterUrl', 'chapter_url', 'chapterurl', 'url', 'link', 'href', 'path'];

/** JSON 里递归找「对象数组」，且同时具备章节名与链接字段，取最有把握的一组 */
/** 在 JSON 里找最像章节表的对象数组（字段名 + 链接字段都齐） */
export function findChapterArray(json) {
  if (!json || typeof json !== 'object') return null;
  let best = null;
  const walk = (v, depth) => {
    if (!v || typeof v !== 'object' || depth > 6) return;
    if (Array.isArray(v)) {
      const first = v[0];
      if (v.length >= 2 && first && typeof first === 'object' && !Array.isArray(first)) {
        const nk = AUTO_NAME_KEYS.find((k) => typeof first[k] === 'string' && first[k].trim());
        const uk = AUTO_URL_KEYS.find((k) => typeof first[k] === 'string' && first[k].trim());
        // 没有链接字段也可能是章节表（地址由规则用 cid 拼出来），给低分但保留
        if (nk) {
          const score = v.length * 2 + (uk ? 60 : 0) - depth * 4;
          if (!best || score > best.score) best = { arr: v, nk, uk: uk || null, score };
        }
      }
      v.forEach((it) => walk(it, depth + 1));
    } else {
      Object.keys(v).forEach((k) => walk(v[k], depth + 1));
    }
  };
  walk(json, 0);
  return best;
}

export function autoChaptersFromJson(json, baseUrl) {
  const best = findChapterArray(json);
  if (!best || !best.uk) return [];
  const seen = new Set();
  const out = [];
  for (const it of best.arr) {
    const name = String(it[best.nk] == null ? '' : it[best.nk]).trim();
    const url = resolveUrl(baseUrl, String(it[best.uk] == null ? '' : it[best.uk]).trim());
    if (!name || !/^https?:/i.test(url) || seen.has(url)) continue;
    seen.add(url);
    out.push({ name, url, isVolume: false });
  }
  return out.length >= 2 ? out : [];
}

/** HTML 目录页：找不到规则匹配时，按「章节样式文本 + 章节样式链接」收集 */
export function autoChaptersFromDoc(doc, baseUrl) {
  if (!doc || typeof doc.querySelectorAll !== 'function') return [];
  const NAV = /^(首页|目录|上一页|下一页|上一章|下一章|返回|登录|注册|书架|排行|分类|搜索|更多|展开|收起|加入书签)$/;
  const seen = new Set();
  const out = [];
  for (const a of doc.querySelectorAll('a[href]')) {
    const href = a.getAttribute('href') || '';
    if (!href || /^(#|javascript:|mailto:|tel:)/i.test(href)) continue;
    const text = (a.textContent || '').replace(/\s+/g, ' ').trim();
    if (text.length < 2 || text.length > 60 || NAV.test(text)) continue;
    const url = resolveUrl(baseUrl, href);
    if (!/^https?:/i.test(url) || seen.has(url)) continue;
    const looksChapter =
      /第\s*[0-9零一二三四五六七八九十百千万两]+\s*[章节回卷话]/.test(text) ||
      /^\d{1,4}$/.test(text) ||
      (/^\d{1,4}[\s.、:：]/.test(text) && /(chapter|\/read\/|\/\d+\.html)/i.test(url));
    if (!looksChapter) continue;
    seen.add(url);
    out.push({ name: text, url, isVolume: false });
  }
  // 少于 5 条多半是导航/推荐位，不当作目录
  return out.length >= 5 ? out : [];
}

/* ==================== 搜索入口自动探测 ==================== */
/*
 * 对付「书源 searchUrl 写错」这一类：源里没写 {{key}}（或路径写死成死链），关键词根本没发出去，
 * 请求打到 /- 这种死路径 → 404 / 0 结果。站点本身往往能搜，坏的是源的 searchUrl。
 * 下面这组函数去首页扒搜索框、再试常见搜索路径，真发请求验证哪个能用，
 * 把可用的 searchUrl（含 GET/POST 选项串）回传，原生侧一键改写即可，不用用户逐个手改。
 */

/** 首页上「更深一层」的内链数量（排除站点根本身），用作结果页判定基线 */
function countDeepLinks(doc, baseUrl) {
  let n = 0;
  const base = hostOfUrl(baseUrl);
  try {
    for (const a of Array.from(doc.querySelectorAll('a'))) {
      const href = a.getAttribute('href') || '';
      if (!href || href.charAt(0) === '#' || /^javascript:/i.test(href)) continue;
      const abs = resolveUrl(baseUrl, href);
      if (!abs || hostOfUrl(abs) !== base) continue;
      const root = base.replace(/\/+$/, '');
      if (abs.replace(/\/+$/, '') === root) continue;
      const path = abs.replace(/^https?:\/\/[^/]+/, '');
      if ((path.match(/\//g) || []).length >= 2) n++;
    }
  } catch (e) { /* 忽略 */ }
  return n;
}

/** 首页上「同站内链」总数（含浅层导航），用来判断候选响应是不是「跟首页一模一样」（参数被忽略） */
function countAllLinks(doc, baseUrl) {
  let n = 0;
  const base = hostOfUrl(baseUrl);
  try {
    for (const a of Array.from(doc.querySelectorAll('a'))) {
      const href = a.getAttribute('href') || '';
      if (!href || href.charAt(0) === '#' || /^javascript:/i.test(href)) continue;
      const abs = resolveUrl(baseUrl, href);
      if (abs && hostOfUrl(abs) === base) n++;
    }
  } catch (e) { /* 忽略 */ }
  return n;
}

/** 从首页 HTML 构造候选搜索入口：表单里的搜索框 + 常见启发式路径 */
function buildSearchCandidates(doc, homeUrl) {
  const out = [];
  const seen = new Set();
  const home = homeUrl.replace(/\/+$/, '');
  const push = (url, param, method, fields, fromForm) => {
    if (!url || !param) return;
    const key = method + '|' + url + '|' + param + '|' + (fields ? fields.join('&') : '');
    if (seen.has(key)) return;
    seen.add(key);
    out.push({ url, param, method, fields: fields || null, fromForm: !!fromForm });
  };
  // 常见搜索框字段名（WordPress 的 s、Discuz 的 srchtxt、帝国 CMS 的 keyboard、通用 q/wd/kw…）
  const KW_NAME = /^(s|q|wd|kw|key|keyword|keyboard|search|searchkey|searchword|searchkeyboard|query|word|bookname|book|name|title|srchtxt|txt|text)$/i;
  // 非搜索表单的痕迹（评论 / 登录 / 注册 / 订阅 / 反馈）：绝不能往里 POST，会真发表评论
  const NOT_SEARCH = /comment|login|log-in|signin|sign-in|signup|sign-up|register|logout|subscribe|feedback|contact|mail|report|vote|poll|captcha/i;
  if (doc) {
    for (const form of Array.from(doc.querySelectorAll('form'))) {
      const method = (form.getAttribute('method') || 'GET').toUpperCase();
      const action = form.getAttribute('action') || homeUrl;
      const url = resolveUrl(homeUrl, action);
      if (!url) continue;
      const formSig = (form.getAttribute('id') || '') + ' ' + (form.getAttribute('class') || '')
        + ' ' + (form.getAttribute('role') || '') + ' ' + (form.getAttribute('name') || '') + ' ' + action;
      if (NOT_SEARCH.test(formSig)) continue;
      const inputs = Array.from(form.querySelectorAll('input,select'));
      const isSearchForm = /search|sousuo|so\b/i.test(formSig);
      let qName = null; const fields = [];
      let hasPassword = false;
      for (const el of inputs) {
        const tag = String(el.tagName || el.nodeName || 'INPUT').toUpperCase();
        const type = (el.getAttribute('type') || (tag === 'INPUT' ? 'text' : '')).toLowerCase();
        const name = el.getAttribute('name');
        if (type === 'password') hasPassword = true;
        if (!name) continue;
        // 按钮类不进请求体：值可能是「搜索」这类文案，甚至带引号把 body JSON 弄坏；
        // 服务端不会因为少了 submit 就拒绝（表单按钮只是给浏览器用的）。
        if (type === 'submit' || type === 'button' || type === 'reset' || type === 'image' || type === 'file') continue;
        if (type === 'checkbox' || type === 'radio') {
          // 未勾选的勾选框不提交（与浏览器行为一致）
          if (el.getAttribute('checked') === null) continue;
        }
        if (type === 'hidden') { fields.push(name + '=' + encodeURIComponent(el.getAttribute('value') || '')); continue; }
        // 只有「名字像搜索词」或「type=search」或「整个表单已判定为搜索表单」才认作查询框；
        // 否则首页的评论表单（author/email/url）会被误当搜索框，往评论接口 POST 关键词。
        // ⚠ select / textarea 没有 type 属性，绝不能当查询框：搜索表单里常带「分类」下拉，
        //   否则会把 select name="type" 误当关键词字段，POST 出去等于没搜（0.9.115 实测 biquge365）。
        const isInput = tag === 'INPUT';
        if (isInput && !qName
          && (type === 'search' || KW_NAME.test(name) || (isSearchForm && (type === 'text' || type === '')))) {
          qName = name;
          continue;
        }
        // select 的提交值不是 value 属性（它没有），而是「选中项」的 value：
        // 搜索表单里的「书名 / 作者」下拉必须带上，否则 POST 出去是 type= 空值，站点当非法请求。
        if (tag === 'SELECT') {
          let v = '';
          try { v = el.value != null ? String(el.value) : ''; } catch (e) { v = ''; }
          if (!v) {
            const opt = el.querySelector('option[selected]') || el.querySelector('option');
            v = opt ? String(opt.getAttribute('value') || '') : '';
          }
          fields.push(name + '=' + encodeURIComponent(v));
          continue;
        }
        fields.push(name + '=' + encodeURIComponent(el.getAttribute('value') || ''));
      }
      if (hasPassword) continue;
      if (qName) push(url, qName, method, fields, true);
    }
  }
  // 启发式 GET：站点没暴露表单、或表单是 JS 提交时兜底
  push(home + '/', 's', 'GET', null, false);            // WordPress 等（带斜杠，避免 "host?s=" 被某些站回落首页）
  push(home + '/search', 'q', 'GET', null, false);
  push(home + '/search', 'keyword', 'GET', null, false);
  push(home + '/', 'keyword', 'GET', null, false);
  push(home + '/index.php', 'keyword', 'GET', null, false);
  return out;
}

/** 探测一个候选：真发请求，看它是否真能搜出含探针词的页面。返回 {score,...} */
async function scoreSearchCandidate(src, c, probeWord, homeDeep, homeAll) {
  let template;
  if (c.method === 'POST') {
    const body = c.param + '={{key}}' + (c.fields && c.fields.length ? '&' + c.fields.join('&') : '');
    template = c.url + ',{"method":"POST","body":"' + body + '"}';
  } else {
    template = c.url + (c.url.indexOf('?') >= 0 ? '&' : '?') + c.param + '={{key}}';
  }
  let r;
  try { r = await src.fetchText(template, { key: probeWord, page: 1 }); }
  catch (e) {
    // 必须带「原因代码」上去：只回 score:0 时，上层只能说一句「没找到入口」，
    // 用户和开发者都分不清是封 IP、超时、还是路径 404（0.9.117 真机排查就卡在这）。
    const msg = String((e && e.message) || '');
    const kind = /超时|timeout/i.test(msg) ? 'timeout'
      : /路径可能过期|404/.test(msg) ? 'http404'
        : /证书|SSL|TLS|握手/i.test(msg) ? 'tls'
          : 'error';
    return { score: 0, kind, why: msg };
  }
  const blocked = detectBlockedPage(r.text, r.status, { headers: r.headers, contentType: r.contentType });
  if (blocked && blocked.kind === 'cf') return { score: 0, blocked: true, kind: 'cf' };
  // 搜索直接 302 到 login.html：站点把搜索锁在登录之后。这是死路，不是「地址猜错了」，
  // 必须在结论里明说，否则用户会反复点批量修复。
  if (blocked && blocked.kind === 'gated') return { score: 0, blocked: true, gated: true, kind: 'gated' };
  if (blocked && blocked.kind === 'ratelimit') {
    // 「系统限制的搜索时间间隔为 N 秒」：只能证明「端点大概存在」，**不能**证明参数与编码都对。
    // 实测 biquge365 就是反例：限流页出现时，它的 POST 体是 UTF-8 而站点要 GBK，
    // 真去搜依然 0 条。若把限流当及格分，就会把一个坏地址写进源里 —— 比不改更糟。
    // 所以只记一个「因限流未能验证」的标记，让上层如实告诉用户稍后再试。
    return { score: 0, blocked: true, rateLimited: true, kind: 'rate' };
  }
  const text = String(r.text || '');
  const okStatus = r.status >= 200 && r.status < 400;
  const hasEcho = text.indexOf(probeWord) >= 0;
  // 站点**明确**回了「没有搜到」：说明路径/参数/编码全对，只是站内没这本书。
  // 这跟「找不到搜索入口」是两件事，必须分开告诉用户，否则他会一直去改源。
  const noRes = !blocked && text.length < 20000
    && /没有搜索到|无搜索结果|没有找到|未找到相关|暂无[^。；<]{0,6}结果|no results? found|nothing found/i.test(text);
  let deep = 0; let all = 0;
  try {
    const doc = parseDoc(text);
    if (doc) { deep = countDeepLinks(doc, r.finalUrl || c.url); all = countAllLinks(doc, r.finalUrl || c.url); }
  } catch (e) { /* 忽略 */ }
  const delta = deep - homeDeep;
  // 候选响应跟首页一模一样（参数被站点忽略，等于又回了首页）→ 这条候选无效。
  // ⚠ 必须要求「首页本身确实解析出了链接」（homeAll > 0）：首页是 JS 渲染、或 HTML 残缺导致
  //   解析不出 a 标签时 homeAll=0，若不设这道门槛，任何候选都会满足「0 个链接 == 首页 0 个链接」，
  //   全部被判成"回了首页"，探测永远失败（0.9.115 实测盗墓笔记就是这样全灭的）。
  const sameAsHome = homeAll > 0 && (deep === homeDeep)
    && Math.abs(all - homeAll) <= Math.max(4, homeAll * 0.2);
  let score = 0;
  if (okStatus && hasEcho && text.length > 1200 && (!sameAsHome || c.fromForm)) {
    score = c.fromForm ? 8 : 5;
    if (delta >= 3) score += 2;
  }
  let kind = 'ok';
  if (!okStatus) kind = 'http' + r.status;
  else if (blocked && blocked.kind === 'sitedown') kind = 'down';
  else if (sameAsHome) kind = 'same';
  else if (!hasEcho) kind = 'noecho';
  else if (score === 0) kind = 'weak';
  return { score, hasEcho, delta, status: r.status, sameAsHome, noResult: noRes && !hasEcho, kind };
}

export class BookSource {
  constructor(def) {
    // id 是由 bookSourceUrl 派生的只读 getter；入库记录若带 id 字段需忽略，否则 Object.assign 会抛错
    const { id: _ignored, ...rest } = def || {};
    Object.assign(this, rest);
    // Legado 书源 URL 偶见 "##" 后缀（旧语法残留），会污染所有相对地址拼接，先清掉
    if (this.bookSourceUrl) this.bookSourceUrl = String(this.bookSourceUrl).replace(/#+.*$/, '').trim();
    this.enabled = def.enabled !== false;
    // 单次请求策略（批量体检会临时覆盖成「短超时 + 不重试」）
    this.reqTimeout = 15000;
    this.reqRetries = 1;
    this.reqConnect = 10000;
    this.reqRead = 15000;
    this.cancelSignal = null;
    // 站点「搜索间隔限制」时要不要等够时间再重发一次。默认关：批量校验里几十个源，
    // 每个等 10 秒会直接把总预算耗光。只有单源调试会把它打开（等一次，真能搜出书）。
    this.reqRateWait = false;
    // 需要「浏览器内核」才能读的站点（Cloudflare 人机验证）见模块级 WEB_HOSTS：
    // 记在实例上没意义，每次 action 都是新实例。
    this._webHosts = WEB_HOSTS;
  }

  static fromJson(o) {
    const d = { ...o };
    // 容忍整段字符串化的规则对象
    for (const k of ['ruleSearch', 'ruleBookInfo', 'ruleToc', 'ruleContent', 'ruleExplore']) {
      if (typeof d[k] === 'string') {
        try {
          d[k] = JSON.parse(d[k]);
        } catch (e) {
          /* 保持原样 */
        }
      }
    }
    return new BookSource(d);
  }

  get id() {
    return this.bookSourceUrl + '|' + (this.bookSourceName || '');
  }

  /** Legado 兼容：{{source.getKey()}} 在书源规则里极常见，返回书源 URL 作为唯一键 */
  getKey() {
    return this.bookSourceUrl;
  }

  /** Legado 兼容：source.key 别名 */
  get key() {
    return this.bookSourceUrl;
  }

  /** Legado 兼容：书源级变量（源里常用于暂存 token / 验证码状态） */
  getVariable() {
    return this.variable == null ? '' : String(this.variable);
  }

  setVariable(v) {
    this.variable = v == null ? '' : String(v);
  }

  /* ---------- 请求 ---------- */

  /** 相对地址补全：以 bookSourceUrl 为 base（'/search.php' 常见于国产源） */
  _abs(u) {
    return resolveUrl(String(this.bookSourceUrl || '').trim(), u);
  }

  /** {{...}} 里的 JS 表达式求值（source.getKey()、source.bookSourceUrl 等） */
  async _evalTpl(expr, vars) {
    const inner = String(expr).trim();
    // {{别名}}：@put / java.put 存下的规则变量（如封面 id、token）
    const store = this.vars || {};
    if (/^[A-Za-z_$][\w$]*$/.test(inner) && store[inner] != null) return String(store[inner]);
    const ctx = this._ctx({ text: '', finalUrl: this.bookSourceUrl }, this.bookSourceUrl, vars);
    const r = await runJs(`return (${inner});`, ctx, '');
    return r == null ? '' : String(r);
  }

  /** 执行 @js: 段代码，取返回的地址；没显式 return 时退化为「最后一行表达式」求值 */
  async _evalJsCode(code, vars) {
    const ctx = this._ctx({ text: '', finalUrl: this.bookSourceUrl }, this.bookSourceUrl, vars);
    let out = '';
    try {
      out = await runJs(code, ctx, '');
    } catch (e) {
      out = '';
    }
    if (out == null || out === '') {
      // 没写 return：把最后一个表达式在**同一段代码的上下文里**执行（变量还在作用域内）。
      // 兼容两种写法：多行最后一行是表达式；单行用 ; 分隔、末段是表达式。
      const raw = String(code).trim();
      let lines = raw.split('\n');
      let last = (lines.pop() || '').trim();
      if (!lines.length && last.includes(';')) {
        const segs = last.split(';');
        const tail = (segs.pop() || '').trim();
        if (tail) {
          lines = [segs.join(';')];
          last = tail;
        }
      }
      last = last.replace(/;$/, '');
      if (last && !/^(return|var|let|const|if|for|while|\}|\{|\/\/)/.test(last) && !last.endsWith('{') && !last.endsWith('}')) {
        try {
          const body = lines.join('\n') + `\nreturn (${last});`;
          out = await runJs(body, ctx, '');
        } catch (e) {
          out = '';
        }
      }
    }
    return out == null ? '' : String(out);
  }

  /**
   * 解析 URL 模板（Legado 常见三种写法，缺一个都会整源失效）：
   *  1. 尾随 ,{"method":"POST","body":"...","headers":{}} 选项
   *  2. "@js:" 动态地址：执行 JS 得到真实 URL（签名/重定向）
   *  3. "{{js表达式}}" 插值 + {{key}}/{{page}} 标准变量
   * 最后统一做相对路径补全。
   */
  async buildRequest(template, vars = {}) {
    let t = String(template || '').trim();
    let opts = {};
    // 逗号和 { 之间可能夹着看不见的东西：全角逗号、全角空格、零宽空格、BOM。
    // 以前死认半角 ",{" —— 源里只要有一个不可见字符，整串就匹配不上，于是 URL 带着
    // {JSON选项} 一路发出去，站点不回应，用户看到的是「请求超时（站点无响应）」。
    const GAP = '[\\s\\u200B-\\u200D\\uFEFF\\u3000]*';
    const optsRe = new RegExp(GAP + '[,，]' + GAP + '(\\{[\\s\\S]*\\})\\s*$');
    const m = optsRe.exec(t);
    let looseOpts = false;
    if (m) {
      const parsed = parseLooseOpts(m[1]);
      if (parsed) {
        opts = parsed;
        t = t.slice(0, m.index).trim();
      } else {
        // 选项串解析失败时，**绝不能**把整串（含 {…}、换行、引号）当 URL 发出去：
        // 请求会打到一个畸形路径上，站点不回应 → 十几秒后报「站点无响应」，
        // 用户完全看不出是书源里的 JSON 写坏了。这里退化为「逗号前的地址 + 硬捞选项」。
        const raw = m[1];
        const grab = (k) => {
          const mm = new RegExp('["\u201C]' + k + '["\u201D]\\s*:\\s*["\u201C]([\\s\\S]*?)["\u201D]\\s*[,}]').exec(raw);
          return mm ? mm[1] : '';
        };
        const mth = grab('method');
        const bd = grab('body');
        const cs = grab('charset');
        if (mth) opts.method = mth;
        if (bd) opts.body = bd;
        if (cs) opts.charset = cs;
        t = t.slice(0, m.index).trim();
        looseOpts = true;
      }
    }
    const enc = encodeURIComponent;
    // 中文关键词的编码：GB2312/GBK 站点必须按 GBK 发（浏览器只能 UTF-8，站点按 GBK
    // 解出来是乱码，永远搜不到）。桥在原生侧，同步返回百分号串；拿不到就退回 UTF-8。
    // 只在 fetchText 判定「这个站确实是 GBK 且关键词没落地」时才打开 _gbkOn，
    // 正常 UTF-8 站点一步都不受影响。
    const encKey = (s) => {
      const t = String(s == null ? '' : s);
      if (this._gbkOn && /[^\x00-\x7F]/.test(t)) {
        try {
          const br = (typeof window !== 'undefined') ? window.ModouSourceNative : null;
          if (br && typeof br.gbk === 'function') {
            const q = br.gbk(t);
            if (q) return q;
          }
        } catch (e) {
          /* 没有桥（如离线回归）→ 退回 UTF-8 */
        }
      }
      return enc(t);
    };

    // @js: 动态地址 —— Legado 常见写法是 @js 返回 "url,{JSON选项}" 组合串，
    // 求值后必须再拆一次选项（否则整串被当 URL，实际请求到站点根/非法地址）
    if (/^@js[:;]/i.test(t)) {
      const code = t.replace(/^@js[:;]\s*/i, '');
      t = String((await this._evalJsCode(code, vars)) || '').trim();
      const m2 = optsRe.exec(t);
      if (m2) {
        const parsed2 = parseLooseOpts(m2[1]);
        if (parsed2) {
          opts = { ...opts, ...parsed2 };
          t = t.slice(0, m2.index).trim();
        } else {
          t = t.slice(0, m2.index).trim();
          looseOpts = true;
        }
      }
    }

    // {{...}} 逐个求值：key/searchKey 走标准编码，page 转字符串，其余当 JS 表达式
    const fill = async (s) => {
      // {{key}} / {{$..id}} / {$..id} —— 书源里两种花括号写法都常见
      // {{key}} / {$..id} / {key} —— 书源里三种写法都见过。
      // 单花括号 {key} 是早期源的写法，不认它，关键词就原样留在地址里发出去（= 空搜索）。
      const parts = String(s).split(/(\{\{[\s\S]*?\}\}|\{\$[^}]*\}|\{(?:key|searchKey|page)\})/g);
      for (let i = 1; i < parts.length; i += 2) {
        const rawPh = parts[i];
        const inner = rawPh.startsWith('{{') ? rawPh.slice(2, -2).trim() : rawPh.slice(1, -1).trim();
        let val = '';
        if (/^(key|searchKey)$/.test(inner)) val = encKey(vars.key ?? '');
        else if (inner === 'page') val = String(vars.page ?? 1);
        else {
          try {
            val = await this._evalTpl(inner, vars);
          } catch (e) {
            val = '';
          }
        }
        parts[i] = val;
      }
      return parts.join('');
    };

    const url = this._abs(await fill(t));
    // body 可能是对象（@js 里 JSON.stringify 前就给了对象/选项里直接写对象）→ 序列化成 JSON
    let bodyOut = opts.body;
    const headers = { ...parseHeader(this.header), ...(opts.headers || {}) };
    // Referer 默认用「书源地址」而不是站点根域名：Legado 就是这么做的。
    // 不少站只认书源首页（带路径、带子域）当来源，根域名 https://host/ 直接 403；
    // 书源自己写了 Referer 的照旧以它为准。
    //
    // 但书源地址与请求地址**跨域**时必须改用请求地址的同源首页：
    // 52书库的书源写在旧域名 po5.net，实际 301 到 www.52shuku.net，
    // 带着 po5.net 的 Referer 过去会被 WAF 判 "Invalid Referer" 直接 403。
    if (this.bookSourceUrl && !Object.keys(headers).some((k) => k.toLowerCase() === 'referer')) {
      const srcHost = hostOfUrl(this.bookSourceUrl);
      const reqHost = hostOfUrl(url);
      headers['Referer'] = srcHost && reqHost && srcHost !== reqHost ? originOfUrl(url) + '/' : this.bookSourceUrl;
    }
    if (bodyOut != null && typeof bodyOut === 'object') {
      bodyOut = JSON.stringify(bodyOut);
      if (!Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) headers['content-type'] = 'application/json';
    } else if (bodyOut != null) {
      bodyOut = await fill(String(bodyOut));
      // 表单式 POST 必须自带 application/x-www-form-urlencoded：不带这个头，请求层会按
      // text/plain 发出去，PHP 的 $_POST 根本不解析 body —— 服务端看到的关键词是空的。
      // 实测 97k.cc（action="/so/" method="post"）：body 明明发了 s=%E5%A4%A7…，
      // 页面却回「搜索的“”不存在」，于是探测把一条完全正确的入口判成不可用。
      // 书源自己写了 content-type 的照旧以它为准（有些站要 json / 要 GBK 声明）。
      if (!Object.keys(headers).some((k) => k.toLowerCase() === 'content-type')) {
        const b = String(bodyOut).trim();
        // body 是 JSON 串的（有些源直接写 {"a":1}）仍按 json 发，别好心办坏事
        headers['content-type'] = /^[\{\[]/.test(b) ? 'application/json' : 'application/x-www-form-urlencoded';
      }
    }
    return {
      url,
      method: (opts.method || 'GET').toUpperCase(),
      body: bodyOut,
      headers,
      charset: opts.charset || undefined,
      // 选项串是靠正则硬捞出来的（JSON 本身不合法）：日志要把这件事说出来，
      // 否则用户会一直以为源没问题，只是「站点无响应」
      looseOpts,
    };
  }

  /**
   * 批量体检用：把整源请求压到固定预算内。
   * timeout 是单请求上限；retries=0 保证一个死源最多等一个窗口。
   * signal 用于「停止体检」立即断掉在途请求。
   */
  setRequestPolicy(policy = {}) {
    this.reqTimeout = Number(policy.timeout) > 0 ? Number(policy.timeout) : 15000;
    this.reqRetries = Number.isFinite(Number(policy.retries)) ? Math.max(0, Number(policy.retries)) : 1;
    this.cancelSignal = policy.signal || null;
    // 连接/读取超时分链路（毫秒）：校验场景更短，避免慢站拖垮批量体检
    this.reqConnect = Number(policy.connectTimeout) > 0 ? Number(policy.connectTimeout) : 10000;
    this.reqRead = Number(policy.readTimeout) > 0 ? Number(policy.readTimeout) : 15000;
    // 只有显式传 true 才等（单源调试）；批量校验一律不传，保持快。
    this.reqRateWait = policy.rateWait === true;
  }

  clearRequestPolicy() {
    this.reqTimeout = 15000;
    this.reqRetries = 1;
    this.reqConnect = 10000;
    this.reqRead = 15000;
    this.cancelSignal = null;
    this.reqRateWait = false;
  }

  /** 原生桥上有没有 GBK 编码能力（离线回归没有，此时不做任何 GBK 相关动作） */
  _canGbk() {
    try {
      const br = (typeof window !== 'undefined') ? window.ModouSourceNative : null;
      return !!(br && typeof br.gbk === 'function');
    } catch (e) {
      return false;
    }
  }

  /** 关键词「UTF-8 字节被 GBK 解开」的样子（借原生桥算），拿不到返回空串 */
  _gbkMojibake(k) {
    if (!k) return '';
    try {
      const br = (typeof window !== 'undefined') ? window.ModouSourceNative : null;
      if (br && typeof br.mojibake === 'function') return String(br.mojibake(k) || '');
    } catch (e) {
      /* 没有桥 → 空串，退回旧判据 */
    }
    return '';
  }

  /**
   * 这次响应像是「GBK 站把 UTF-8 关键词解成了乱码」吗？（只用于决定要不要重发一次）
   *
   * 判据故意收得很窄，避免给正常站点白白多打一次请求：
   * 关键词含中文 + 响应是 gbk 系编码 + 页面里根本没有这个关键词 + 页面不大。
   */
  _needGbkRetry(vars, r) {
    if (this._gbkOn) return false;
    const k = String((vars && (vars.key != null ? vars.key : vars.keyword)) || '');
    if (!k || !/[^\x00-\x7F]/.test(k)) return false;
    const cs = String((r && r.charset) || '').toLowerCase();
    if (cs.indexOf('gb') < 0 && cs.indexOf('2312') < 0 && cs.indexOf('18030') < 0) return false;
    const text = String((r && r.text) || '');
    if (!text || text.length > 40000) return false;
    try {
      const br = (typeof window !== 'undefined') ? window.ModouSourceNative : null;
      if (!br || typeof br.gbk !== 'function') return false;
      // 硬证据优先：页面里出现了「UTF-8 字节被 GBK 解开」的那串乱码，说明关键词就是这么坏的。
      // 只看「页面里有没有原词」会被导航文案骗过 —— 搜「我的」时页面本来就有「我的书架」，
      // 旧判据据此认为「关键词落地了」，于是永不重发，这个源就永远是 0 条。
      const moj = this._gbkMojibake(k);
      if (moj && text.indexOf(moj) >= 0) return true;
    } catch (e) {
      return false;
    }
    if (text.indexOf(k) >= 0) return false;
    return true;
  }

  /* ---------- 浏览器内核通道（Cloudflare 人机验证站） ---------- */

  /**
   * 能不能借浏览器内核抓页面。
   *
   * 背景：Cloudflare 的 `Just a moment...` 要求执行一段 JS 才发通行证，HTTP 客户端永远拿不到。
   * 而且通行证绑 **TLS 指纹** —— 实测用 Chromium 过完挑战，再拿同一个 cf_clearance + 同一份 UA
   * 用 curl 请求，照样 403 `Attention Required!`。所以只能让页面在 WebView 里跑完再取 HTML。
   */
  _webBridge() {
    try {
      const b = typeof window !== 'undefined' ? window.ModouSourceNative : null;
      // 优先用异步桥（不阻塞 JS 引擎）；没有则回退同步桥；都没有返回 null。
      if (b && typeof b.webFetchAsync === 'function') { this._webAsync = true; return b; }
      if (b && typeof b.webFetch === 'function') { this._webAsync = false; return b; }
      return null;
    } catch (e) {
      return null;
    }
  }

  _webHostKey(url) {
    try { return new URL(String(url)).host; } catch (e) { return ''; }
  }

  /** 这个 host 之前已经证明「必须走 WebView」吗 */
  _webKnown(url) {
    const h = this._webHostKey(url);
    const until = h ? this._webHosts.get(h) : 0;
    return !!(until && until > Date.now());
  }

  /**
   * 用浏览器内核取一页（异步）：原生侧取完页面才回传，期间不阻塞 JS 引擎，
   * 其它源的搜索照常进行。返回 Promise<{status,finalUrl,html,ms}|null>。
   * 同步兜底（_webAsync=false）仅作兼容，正常构建都用异步桥。
   */
  async _webFetch(url) {
    const br = this._webBridge();
    if (!br || !url) return null;
    if (this._webAsync) {
      const seq = ++WEB_FETCH_SEQ;
      return await new Promise((resolve) => {
        WEB_FETCH_CALLBACKS.set(seq, (json) => resolve(json && json.length ? safeJson(json) : null));
        try { br.webFetchAsync(String(url), 18000, seq); }
        catch (e) { WEB_FETCH_CALLBACKS.delete(seq); resolve(null); }
      });
    }
    let o = null;
    try { o = safeJson(br.webFetch(String(url), 18000) || '{}'); }
    catch (e) { return null; }
    return o;
  }

  /** 包成和 fetchViaProxy 同形状的响应，下游解析逻辑完全不用改 */
  _webResp(o, url) {
    return {
      text: String(o.html || ''), status: o.status || 200, finalUrl: o.finalUrl || url,
      charset: 'utf-8', contentType: 'text/html', headers: {},
      method: 'GET', reqUrl: o.finalUrl || url, reqMethod: 'GET', reqBody: '',
      webFetched: true, webMs: o.ms || 0,
    };
  }

  async fetchText(template, vars) {
    // 每次重发都重新 buildRequest：GBK 纠错那一次要换编码，沿用首次的 url 就白改了
    const send = async (uaFix) => {
      const { url, method, body, headers, charset, looseOpts } = await this.buildRequest(template, vars);
      // 已知必须走浏览器内核的站：直接走，省掉「先吃一次 403、再换 UA、再等 2 秒」三轮浪费。
      // 只对 GET 生效 —— WebView 没法替我们发 POST body。
      if (method === 'GET' && this._webKnown(url)) {
        const wf = await this._webFetch(url);
        if (wf) return this._webResp(wf, url);
      }
      if (uaFix) {
        // 书源自带的 UA 先删干净（大小写写法不统一），再写内置现代 UA。
        // 只删 UA：Referer / Cookie / content-type 这些站点要的照样保留。
        for (const k of Object.keys(headers)) {
          if (k.toLowerCase() === 'user-agent') delete headers[k];
        }
        headers['User-Agent'] = MODERN_UA;
      }
      let res;
      try {
        res = await fetchViaProxy(url, {
          method, headers, body, charset,
          timeout: this.reqTimeout,
          retries: this.reqRetries,
          connectTimeout: this.reqConnect,
          readTimeout: this.reqRead,
          signal: this.cancelSignal || undefined,
        });
      } catch (e) {
        // 请求失败时把「实际发出去的地址/方法」挂到异常上：调试台以前只能显示 searchUrl
        // 原文，地址被拼坏（比如选项串没解析、整串当 URL）时完全看不出来。
        if (e && typeof e === 'object') {
          e.reqUrl = url;
          e.reqMethod = method;
        }
        throw e;
      }
      res.method = method;
      res.reqUrl = url;
      res.reqMethod = method;
      res.reqBody = body == null ? '' : String(body);
      if (looseOpts) res.optsLoose = true;
      return res;
    };
    let r = await send();
    let blocked = detectBlockedPage(r.text, r.status, { headers: r.headers, contentType: r.contentType });
    // 站点「搜索间隔限制」：正文明说「10 秒内别再搜」。这不是拦截也不是源坏了，
    // 但它会毁掉后面所有动作 —— 尤其 GBK 站：编码重发如果落在 10 秒窗口里，会再吃一次
    // 限流，还可能把计时器重置。所以这里必须最先处理：
    //   · 单源调试（reqRateWait）→ 等够站点要求的时间再重发一次；GBK 站顺便按 GBK 编好，
    //     省掉后面那次「再发一遍」（否则刚等完又因编码重发，等于再吃一次限流）。
    //   · 批量校验（reqRateWait=false）→ 不等，直接给准确结论，不拖垮整批。
    if (blocked && blocked.kind === 'ratelimit') {
      const waitSec = Number(blocked.waitSec) || 10;
      r.rateLimited = true;
      r.rateWaitSec = waitSec;
      if (this.reqRateWait && waitSec <= 15) {
        await new Promise((res) => setTimeout(res, waitSec * 1000 + 400));
        const kwNow = String((vars && (vars.key != null ? vars.key : vars.keyword)) || '');
        const csNow = String((r.charset) || '').toLowerCase();
        const gbkSite = csNow.indexOf('gb') >= 0 || csNow.indexOf('2312') >= 0 || csNow.indexOf('18030') >= 0;
        if (gbkSite && /[^\x00-\x7F]/.test(kwNow) && this._canGbk()) this._gbkOn = true;
        const encodedGbk = this._gbkOn;
        try {
          const rn = await send();
          const bn = detectBlockedPage(rn.text, rn.status, { headers: rn.headers, contentType: rn.contentType });
          r = rn;
          blocked = bn || null;
          r.rateWaited = true;
          r.rateWaitSec = waitSec;          // 记的是「等了多久」，不是新响应里的东西
          r.gbkRateEncoded = encodedGbk;
        } catch (e) {
          /* 等待后重发失败：保留第一次的限流结论，别把错误吞成「0 条」 */
          r.rateWaited = false;
          r.rateWaitSec = waitSec;
        } finally {
          this._gbkOn = false;
        }
      }
    }
    // 站点偶发抽风：2xx 但只回一句「数据库连接出错，请打开 conn.asp 文件检查连接字串」
    // 这类纯文本。实测这种站是「随机挑请求回错」——连发 10 次有 4 次是 48 字节报错。
    // 只重试一次 = 还有 16% 概率连续撞上，用户看到的就是「源坏了」。
    // 改成最多再试 2 次（共 3 次）：随机失败率 40% 时，三次全挂只剩 6.4%。
    // 只在 sitedown 时触发，正常站一个字节都不多请求。
    const SITEDOWN_TRIES = 3;
    if (blocked && blocked.kind === 'sitedown') {
      for (let i = 1; i < SITEDOWN_TRIES; i++) {
        // 抖动跟时间无关，退避只为别把压力全砸在同一毫秒上
        await new Promise((res) => setTimeout(res, i === 1 ? 250 : 600));
        const rn = await send();
        const bn = detectBlockedPage(rn.text, rn.status, { headers: rn.headers, contentType: rn.contentType });
        r = rn;
        blocked = bn || null;
        r.sitedownTries = i + 1;
        r.sitedownRetried = true;
        if (!blocked || blocked.kind !== 'sitedown') break;
      }
    }
    // Cloudflare 拦截：分两步救，顺序很关键。
    //
    // ① 先「换现代 UA」重发一次（不等）。绝大多数情况是源里写死的 UA 太旧，被 WAF 按爬虫直接 403
    //    （「Attention Required」）。这一步是即时的，不用等，也最可能一次就过 —— 实测 97k.cc
    //    从 403 直接变 200 并正常出结果。只在「已经被拦」时才换，不会干扰必须特定 UA 的站点。
    if (blocked && blocked.kind === 'cf') {
      try {
        const ru = await send(true);
        const bu = detectBlockedPage(ru.text, ru.status, { headers: ru.headers, contentType: ru.contentType });
        r = ru;
        blocked = bu || null;
        r.cfUaFixed = true;
        r.cfRetried = true;
      } catch (e) {
        /* 换 UA 重发失败：保留第一次的结论，交给下面的等待重试 */
      }
    }
    // ② 还不行 → 换浏览器内核真渲染一次。
    //    「Just a moment...」这类 Managed Challenge 必须执行 JS 才过，HTTP 客户端无解；
    //    换 UA / 等待重发都只是碰运气（站点按 IP+频率挑请求）。
    //    放在「等 2 秒重发」之前：这一步大概率能成，先做可以省掉 2 秒空等。
    if (blocked && blocked.kind === 'cf' && this._webBridge() && r.reqMethod === 'GET') {
      const wf = await this._webFetch(r.reqUrl || r.finalUrl || '');
      if (wf) {
        const keepUa = !!r.cfUaFixed;
        r = this._webResp(wf, r.reqUrl || r.finalUrl);
        if (keepUa) r.cfUaFixed = true;   // 换 UA 试过，日志要能说明
        blocked = detectBlockedPage(r.text, r.status, { headers: r.headers, contentType: r.contentType });
      }
    }
    // ③ 还是不行，再等 2 秒用原样重发一次：这类是「间歇性 / 按频率触发」的挑战，
    //    连发请求就会撞，手机单发通常能过，等一下就自恢复，不必让用户手动验证。
    if (blocked && blocked.kind === 'cf') {
      for (let i = 1; i < 2; i++) {
        await new Promise((res) => setTimeout(res, 2000));
        const rn = await send();
        const bn = detectBlockedPage(rn.text, rn.status, { headers: rn.headers, contentType: rn.contentType });
        r = rn;
        blocked = bn || null;
        r.cfRetried = true;
        if (r.cfUaFixed) r.cfUaFixed = true;   // 换 UA 试过但没成功，标记保留给日志用
        if (!blocked || blocked.kind !== 'cf') break;
      }
    }
    // GBK 站编码纠错：站点是 GB2312/GBK，浏览器只能把中文编成 UTF-8 字节，
    // 站点按 GBK 解出来是乱码 —— 关键词压根没落地（页面里一次都不出现），怎么改规则都 0 条。
    // 判据很窄：响应确实是 gbk 系 + 页面里没有关键词 + 页面不大（真结果页比空壳大得多）。
    // 已经因为限流等待重发过（多半顺带按 GBK 编好了），或者此刻仍是限流页 —— 都不要再发：
    // 在 10 秒窗口里多发一次，既拿不到内容，还会把站点的计时器重置，越试越死。
    const skipGbkForRate = !!r.rateWaited || (blocked && blocked.kind === 'ratelimit');
    if (!skipGbkForRate && this._needGbkRetry(vars, r)) {
      this._gbkOn = true;
      try {
        const r3 = await send();
        // 「重发有没有用」不能只看体积：GBK 版把乱码（UTF-8 三个汉字 9 字节）换回真词
        // （两个汉字 6 字节），页面甚至可能更短 —— 新御书屋搜「三体」就是这样，
        // 重发明明发生了，却因为「没变长」被丢掉，日志里一个字都不留，用户只能看到 0 条。
        // 判据改成：关键词终于落地 > 明显变大（真结果页）> 其余算「试过但没改善」。
        const kw = String((vars && (vars.key != null ? vars.key : vars.keyword)) || '');
        const moj = this._gbkMojibake(kw);
        const t0 = String(r.text || '');
        const t3 = String(r3.text || '');
        // 乱码消失才算「救回来了」：搜「我的」时 t0 里本来就有「我的书架」，
        // 只比「原词有没有出现」永远判不出改进；但乱码「鎴戠殑」只会在编码错时出现。
        const moj0 = !!moj && t0.indexOf(moj) >= 0;
        const moj3 = !!moj && t3.indexOf(moj) >= 0;
        const kwLanded = (moj0 && !moj3) || (!!kw && t3.indexOf(kw) >= 0 && t0.indexOf(kw) < 0);
        const grew = t3.length > t0.length * 1.05;
        if (kwLanded || grew) {
          // 前面可能已经为站点抖动重试过，标记要跟着换过来的响应走，否则日志会少报重试次数
          if (r.sitedownRetried) {
            r3.sitedownRetried = true;
            r3.sitedownTries = r.sitedownTries;
          }
          r = r3;
          r.gbkRetried = true;
          blocked = detectBlockedPage(r.text, r.status, { headers: r.headers, contentType: r.contentType });
        } else {
          // 试过但没换来有用的页面，同样要留痕：否则用户永远不知道「编码问题已经排除」，
          // 下次还在这条路上反复怀疑。
          r.gbkTried = true;
        }
      } catch (e) {
        /* 纠错失败就用第一次的结果，不影响主流程 */
      } finally {
        this._gbkOn = false;
      }
    }
    // 风控/挑战页嗅探：拿到响应就标记，五条链路在解析前统一检查 r.blocked。
    // 命中只说明「当前链路被拦截」，绝不意味整源失效（搜索被风控 ≠ 目录/正文也挂）。
    if (blocked) {
      if (blocked.kind === 'ratelimit') {
        // 站点防刷：搜索太密，明说「N 秒内别再搜」。这是**最不该**让用户去改规则的一种，
        // 用 netReason 归灰（网络/站点侧），别进「规则失效（红）」删除清单。
        // reason 不能含数字：秒数只在日志里说，结论里用「稍等片刻」
        r.blocked = netReason('站点限制搜索间隔：这是站点防刷，不是源的问题，等一会儿再搜即可');
      } else if (blocked.kind === 'sitedown') {
        // 站点故障（短文本报错）不是拦截：用「服务端错误：…」归灰，别把用户往换 UA/挂代理上带
        // 括号里那句是给用户的出口：站方数据库偶发抽风，不是书源坏了，过一会儿再来
        r.blocked = netReason('服务端错误：' + blocked.label + '（站点自身故障，稍后重试即可）');
      } else {
        r.blocked = blockedReason(blocked.label);
      }
    }
    // 4xx/5xx：站点回了响应但拒绝/出错。网络是通的、源没坏，必须和「连接失败」严格区分，
    // 也绝不进「规则失效（红）」删除清单——reason 经 netReason 收敛为纯中文 label 归灰。
    // 这里是 4xx/5xx 的单一出口：以前这类响应被当成「正常响应解析」，在深度校验里会退化成
    // 「详情页解析为空」而非灰档，正是隐患。404 单独标注「路径可能过期」供用户判断（删不删）。
    // 关键：reason 文本不带任何数字（status 由 r.status 字段单独带出供 UI），符合「error.message 无数字」。
    if (!r.blocked && r.status >= 400 && r.status < 600) {
      const label = r.status === 404 ? '路径可能过期'
          : r.status === 429 ? '被限流（请求过于频繁）'
          : (r.status === 401 || r.status === 403) ? '被站点拒绝（需登录/鉴权）'
          : r.status >= 500 ? '站点服务端错误' : '站点拒绝访问';
      const err = new Error(netReason(label));
      // 把响应本身挂在异常上：403 的验证页 / 429 的限流页 / 404 的站内错误页
      // 恰恰是判断「源坏了还是被封了」的唯一线索。以前只抛一句话，调试台就只剩
      // 「路径可能过期」六个字，用户完全看不出站点到底回了个什么。
      err.httpStatus = r.status;
      err.resp = r;
      throw err;
    }
    return r;
  }

  /* ---------- 上下文 ---------- */

  _ctx(r, baseUrl, vars) {
    const ctx = createRuleContext({ body: r.text, baseUrl: baseUrl || r.finalUrl, source: this, logs: [] });
    // java.put/java.get 的存储跟随书源实例（Legado 语义：同一书源内跨规则共享）；
    // 之前每次请求都新建 {}，导致「搜索里 put 的 token，详情里 get 不到」→ url 为空
    ctx.src = this.vars || (this.vars = {});
    if (vars) ctx.vars = { ...ctx.vars, ...vars };
    try {
      ctx.json = JSON.parse(r.text);
    } catch (e) {
      /* 非 JSON */
    }
    ctx.doc = parseDoc(r.text);
    return ctx;
  }

  /** init / preUpdateJs 预处理：其返回值替换 doc 或 json */
  async _runPre(rule, ctx) {
    if (!rule) return;
    const out = await evalRule(rule, ctx);
    if (typeof out === 'string' && /<[a-z][\s\S]*>/i.test(out)) ctx.doc = parseDoc(out);
    else if (out && typeof out === 'object') ctx.json = out;
    else if (typeof out === 'string') {
      try {
        ctx.json = JSON.parse(out);
      } catch (e) {
        ctx.body = out;
      }
    }
  }

  /* ---------- 搜索 / 发现 ---------- */

  /**
   * 探针词：从书源首页挑一个**站内真实存在**的书名，供「这个源能不能搜」的校验使用。
   *
   * 校验的难点从来不是「源坏没坏」，而是「拿什么词去问」：用「三体」问言情站、用「我的」
   * 问英文站，答案都是 0 条，可源是好的。首页推荐位上一定有书，拿它的书名去搜 ——
   * 搜不到才是源的问题。这比猜一个「通用热词」可靠得多。
   */
  async probeKeyword() {
    const home = String(this.bookSourceUrl || this.exploreUrl || '').trim();
    if (!home) return { keyword: '', url: '' };
    let r;
    try {
      r = await this.fetchText(home, { page: 1 });
    } catch (e) {
      return { keyword: '', url: home, error: e.message };
    }
    const doc = parseDoc(r.text || '');
    const names = probeNames(doc, r.finalUrl || home);
    return { keyword: names[0] || '', url: r.finalUrl || home, candidates: names.slice(0, 5) };
  }

  async search(keyword, page = 1) {
    const logs = [];
    if (!this.searchUrl) return { list: [], logs: ['未配置 searchUrl'], trace: [] };
    let r;
    try {
      r = await this.fetchText(this.searchUrl, { key: keyword, page });
    } catch (e) {
      const t = e && e.resp
        ? traceStep('search', '搜索', e.resp, { error: e.message })
        // 有实际请求地址就用它：模板（含 ,{JSON选项}）和真正发出去的 URL 常常不是一回事，
        // 只显示模板的话，地址被拼坏也看不出来
        : { stage: 'search', label: '搜索', url: (e && e.reqUrl) || this.searchUrl,
            tpl: this.searchUrl, method: (e && e.reqMethod) || 'GET',
            status: 0, error: e.message, html: '' };
      return { list: [], logs: [...logs, '请求失败: ' + e.message], error: e, snip: '', trace: [t] };
    }
    // 嗅探命中先只当「待定」：下面的解析一旦出结果就撤销（clearBlockIfParsed）。
    if (r.sitedownRetried) {
      logs.push(r.blocked
        ? `站点连续 ${r.sitedownTries || 2} 次只回了报错文本，重试后仍是服务端错误`
        : `站点偶发报错（数据库连接失败之类），已自动重试 ${(r.sitedownTries || 2) - 1} 次后成功`);
    }
    if (r.cfUaFixed) {
      // 这条一定要说：否则用户看到「403 Cloudflare」只会去换源/挂代理，
      // 而真正的原因是他源里抄了三年前的 UA，App 已经自己换掉重发了。
      // 已经改用浏览器内核救回来的，别在这儿说「通过」—— 换 UA 那一步并没过，是内核过的。
      logs.push(r.webFetched || r.blocked
        ? '站点用 Cloudflare 拦了源里的旧 UA（403 拒绝访问），已换现代 UA 重发一次，仍是验证页'
        : '站点用 Cloudflare 拦了源里的旧 UA（403 拒绝访问），已自动换现代 UA 重发并通过');
    }
    if (r.webFetched) {
      // 人机验证这条路必须让用户知道：它慢（5~20 秒），但下一次同站会直接走内核、不再卡住。
      logs.push(r.blocked
        ? '站点人机验证没通过：浏览器内核也没被放行（站点可能限制了这台设备/网络）'
        : `站点有人机验证（Cloudflare），已用浏览器内核打开并通过（${((r.webMs || 0) / 1000).toFixed(1)} 秒）`);
    }
    if (r.rateWaited) {
      logs.push(`站点限制搜索间隔（约 ${r.rateWaitSec || 10} 秒），已等够时间重发一次`
        + (r.gbkRateEncoded ? '，并按 GBK 编码了关键词' : ''));
    } else if (r.rateLimited) {
      logs.push(`站点限制搜索间隔（约 ${r.rateWaitSec || 10} 秒）：本轮未等待重试（批量校验求快），稍后再搜即可`);
    }
    if (r.gbkRetried && !r.rateWaited) logs.push('站点是 GBK 编码，关键词已按 GBK 编码重发一次');
    else if (r.gbkTried) logs.push('站点是 GBK 编码，已按 GBK 重发一次：页面与 UTF-8 那次基本一致（编码不是问题）');
    if (r.optsLoose) logs.push('⚠ searchUrl 的选项串不是合法 JSON（多为中文引号/全角符号/全角空格），已按捞出来的 method/body 继续；建议改成半角英文标点');
    // 以前在这里直接 return，等于让一个启发式判断凌驾于真实解析之上。
    const blockHit = r.blocked || '';
    const snip = String(r.text || '').replace(/\s+/g, ' ').slice(0, 200);
    const meta = { status: r.status, finalUrl: r.finalUrl, snip, charset: r.charset, bytes: (r.text || '').length };
    logs.push(`${r.method || 'GET'} ${r.reqUrl || r.finalUrl} → ${r.status}（${r.charset || '?'}）${(r.text || '').length} 字节`);
    const rs = this.ruleSearch || {};
    // checkKeyWord 只当线索，不再当场判死。
    // Legado 的语义是「页面里必须出现这个词」，但大量站点会把关键词拆词、转义、
    // 或把搜索结果塞进 iframe / JS 渲染的容器 —— 页面源码里根本找不到原词，
    // 规则却照样能解析出书。以前在这里直接 return error，等于把好源判成失效。
    const kwMiss = !!rs.checkKeyWord && !String(r.text).includes(rs.checkKeyWord);
    if (kwMiss) logs.push('checkKeyWord 未命中（仅记录，继续按规则解析）');
    const ctx = this._ctx(r, r.finalUrl, { key: keyword, page });
    const tBase = (extra) => traceStep('search', '搜索', r, Object.assign({
      rule: String(rs.bookList || ''),
      fields: SEARCH_FIELDS.map((f) => f + '=' + String(rs[f] == null ? '' : rs[f])).join('\n'),
      // 实际发出去的请求体：POST 源 0 条时，这是唯一能看出「参数名对不对、关键词有没有编码进去」
      // 的地方（搜索词常常只活在 body 里，URL 上是干净的）
      body: String(r.reqBody || '').slice(0, 300),
      tpl: String(this.searchUrl || ''),
    }, extra || {}));
    if (!rs.bookList) {
      logs.push('未配置 ruleSearch.bookList');
    }
    // 规则一条没中 ≠ 这个源没救了。站点改版（换个 class、table 改 div）最常见的
    // 后果就是「静默 0 条」，用户只能自己猜新规则。这里按「一堆结构相同的书名链接」
    // 这个结构特征再找一遍：找到就直接用，并把建议规则写进调试台，用户复制即可永久修好。
    let rows = rs.bookList ? await extractRows(rs, ctx, 'bookList', SEARCH_FIELDS) : [];
    if (rs.bookList) logs.push(`bookList 命中 ${rows.length} 条`);
    let autoList = null;
    if (!rows.length && ctx.doc) {
      autoList = autoBookList(ctx.doc, keyword, r.finalUrl);
      if (autoList) {
        rows = autoList.items.map((it) => ({ name: it.name, bookUrl: it.url }));
        logs.push(`已自动识别 ${rows.length} 条（原规则${rs.bookList ? '未命中' : '未配置'}）`);
      }
    }
    // JSON 接口源：HTML 的 autoBookList 找不到 <a>，这里按「对象数组」特征再找一遍，
    // 命中就直接给出可复制的 ruleSearch，用户不必再对着「没拿到可用书籍」干猜。
    let autoJson = null;
    if (!rows.length && ctx.json) {
      autoJson = autoBookListJson(ctx.json);
      if (autoJson) {
        if (autoJson.items.length) {
          rows = autoJson.items.map((it) => ({ name: it.name, bookUrl: it.bookUrl }));
          logs.push(`已自动识别 ${rows.length} 条（JSON 接口，原规则${rs.bookList ? '未命中' : '未配置'}）`);
        }
        logs.push('诊断: ' + autoJson.hint);
      }
    }
    if (!rows.length && !rs.bookList) {
      return { list: [], logs: [...logs, '未配置 ruleSearch.bookList'], error: new Error('未配置搜索列表规则（ruleSearch.bookList）'),
        trace: [tBase({ error: '未配置 ruleSearch.bookList' })], ...meta };
    }
    const list = rows
      .map((row) => ({
        name: (row.name || '').toString().trim(),
        author: (row.author || '').toString().trim(),
        intro: row.intro || '',
        coverUrl: resolveUrl(r.finalUrl, row.coverUrl) || resolveUrl(this.bookSourceUrl, row.coverUrl),
        bookUrl: resolveUrl(r.finalUrl, row.bookUrl) || resolveUrl(this.bookSourceUrl, row.bookUrl),
        kind: row.kind || '',
        lastChapter: row.lastChapter || '',
        updateTime: row.updateTime || '',
        wordCount: row.wordCount || '',
        sourceUrl: this.bookSourceUrl,
        sourceName: this.bookSourceName || this.bookSourceUrl,
      }))
      // 没有 bookUrl 的条目既打不开也不可信（多半是列表选择器匹配到了推荐位），直接丢弃
      .filter((b) => b.name && b.bookUrl);
    const dropped = rows.length - list.length;
    if (dropped > 0) logs.push(`丢弃 ${dropped} 条缺少书名/书籍地址的结果`);
    // 解析出可用结果 → 撤销嗅探标记：能解出书名+地址，说明页面就是正常结果页
    if (list.length) clearBlockIfParsed(r, logs, `${list.length} 条搜索结果`);
    // 原始提取结果（未过滤）前 3 条：调试时能直接看出是「列表没中」还是「中了但某字段空」
    const rawSample = rows.slice(0, 3).map((row) => {
      const o = {};
      for (const k of Object.keys(row)) {
        const v = row[k];
        if (v == null) { o[k] = null; continue; }
        const s = typeof v === 'string' ? v : String(v);
        o[k] = s.length > 300 ? s.slice(0, 300) + '…' : s;
      }
      return o;
    });
    const trace = [tBase(Object.assign(
      { hits: rows.length, kept: list.length, raw: rawSample, logs: logs.slice() },
      autoList ? { autoRule: autoList.rule, autoHint: autoList.hint } : {},
    ))];
    // 命中了列表却一条都拼不出书名/地址：规则错位（比如 bookList 命中了推荐位）要报出来，
    // 否则又是一次「静默 0 条」
    if (!list.length && rows.length) {
      return {
        list: [],
        logs: [...logs, `命中 ${rows.length} 条但都缺书名或书籍地址`],
        error: new Error(`HTTP ${r.status} · 解析到结果但缺书名/书籍地址（列表或字段规则不匹配）`),
        trace,
        ...meta,
      };
    }
    // 请求地址里到底有没有关键词？GET 源没展开 {{key}}（或参数名跟站点对不上）时，
    // 站点收到的是空参数，回一句「数据库连接出错」之类的短文本 —— 这种源改选择器
    // 永远改不好，必须在结论里点出来。POST 的 key 在 body 里，不参与判断。
    //
    // 三种情况要分开说，用户才知道该改哪：
    //   ① searchUrl 里压根没写 {{key}}          → 改书源
    //   ② 写了，但最终地址里没有（被重写/丢参）  → 改跳转或换地址
    //   ③ 请求地址有、跳转后没了                 → 站点把参数吃了
    let kwSent = true;
    let kwNote = '';
    const tpl = String(this.searchUrl || '');
    const tplHasKey = /\{\{?\s*(?:key|searchKey)\s*\}?\}/i.test(tpl);
    if (String(r.method || 'GET').toUpperCase() === 'GET') {
      const k = String(keyword || '');
      if (k) {
        let enc = k;
        try { enc = encodeURIComponent(k); } catch (e) { enc = k; }
        const has = (u) => {
          const s = String(u || '');
          if (!s) return false;
          return s.indexOf(enc) >= 0 || s.indexOf(k) >= 0;
        };
        const inReq = has(r.reqUrl);
        const inFinal = has(r.finalUrl);
        kwSent = inReq || inFinal;
        if (!kwSent) {
          kwNote = tplHasKey
            ? '{{key}} 写了，但发出去的地址里没有关键词（参数被站点重写，或在跳转中丢了）'
            : 'searchUrl 里没有 {{key}} 占位符，关键词根本没发出去';
        } else if (inReq && !inFinal) {
          kwNote = '关键词在跳转后丢了（站点把参数吃掉了）';
        }
      }
    }
    if (!list.length && kwNote) {
      logs.push('⚠ ' + kwNote);
      logs.push('   实际请求: ' + String(r.reqUrl || '').slice(0, 150));
      if (r.finalUrl && r.finalUrl !== r.reqUrl) logs.push('   最终地址: ' + String(r.finalUrl).slice(0, 150));
    }
    if (!list.length && blockHit) {
      // 解析 0 条 + 嗅探命中：这时「风控」才是可信解释，把原因如实给出去
      logs.push('解析 0 条，站点没返回正常网页（' + blockHit + '）');
      // 地址里明明带着关键词，站点却回了「空参数」式的报错 → 多半是参数名不对
      // （站点读 word，源里写 searchkey，值就丢了）。这条比「改选择器」有用得多。
      if (kwSent && /^服务端错误/.test(String(blockHit))) {
        logs.push('地址里带着关键词，站点却像收到了空参数：核对 searchUrl 的参数名是否与站点搜索表单一致');
      }
      trace[0].error = blockHit;
      trace[0].logs = logs.slice();
      return { list: [], logs, error: new Error(blockHit), trace, ...meta };
    }
    // 0 条时给出「为什么 0 条」的具体线索，别让用户对着「可能已改版」干猜。
    // 两层判据：① 页面里有没有关键词；② 有没有「含关键词的链接」——
    // 第二层才有诊断价值：关键词只出现在正文/推荐文案里，说明拿到的根本不是结果页。
    let diag = '';
    // 壳页优先：站点压根没发内容，比「关键词没出现 / 结构拼不起来」更接近根因，
    // 而且能直接判死 —— 改选择器无效，只能换源。
    const shell = (!list.length && !autoList && !autoJson) ? detectJsShell(r.text, ctx.doc) : null;
    // 「站点明说没有结果」比任何结构判断都硬：新御书屋搜「三体」直接回「搜索"三体"无结果」，
    // 2KB、一个书名链接都没有。以前会被诊断成「关键词没出现，参数可能不对」，把用户往改
    // searchUrl 上带 —— 其实编码/参数都对，就是站内没这本书。
    const noRes = (!list.length && !autoList && !autoJson) ? detectNoResult(r.text) : '';
    if (noRes) {
      const kw = keyword ? `「${keyword}」` : '这个词';
      // 已经用 GBK 重发过还是「无结果」—— 这才是可以下结论的状态：编码排除、参数排除、规则排除。
      diag = r.gbkTried
        ? `站点是 GBK 编码，已按 GBK 重发一次仍返回「${noRes}」：站内确实没有${kw}（换关键词或换源，规则没问题）`
        : `站点返回「${noRes}」页：站内没有匹配${kw}的内容，换关键词或换源即可（规则与参数都没问题）`;
      logs.push('诊断: ' + diag);
      trace[0].diag = diag;
    } else if (shell) {
      diag = jsShellHint(shell);
      logs.push('诊断: ' + diag);
      trace[0].diag = diag;
    } else if (!list.length && kwNote && !autoJson) {
      // 比「关键词没出现」更靠前的一层：关键词根本没发出去，页面当然不会有它
      diag = kwNote;
      logs.push('诊断: ' + diag);
      trace[0].diag = diag;
    } else if (!list.length && !autoList && !autoJson && keyword) {
      const hay = String(r.text || '');
      const k = String(keyword);
      let hits = 0;
      for (let i = hay.indexOf(k); i >= 0; i = hay.indexOf(k, i + k.length)) hits++;
      let kwLinks = 0;
      try {
        if (ctx.doc) {
          for (const a of ctx.doc.body.querySelectorAll('a')) {
            if ((a.textContent || '').indexOf(k) >= 0) kwLinks++;
          }
        }
      } catch (e) {
        kwLinks = 0;
      }
      if (hits === 0) {
        // 已经为 GBK 重发过一次，关键词还是没出现 —— 编码这条路排除了，问题只可能在参数名
        // （站点读 s，源里写 word，值就丢在门外）或站点把搜索关进了登录墙。
        diag = r.gbkTried
          ? '站点是 GBK 编码，已按 GBK 重发一次仍未出现关键词：核对 searchUrl 的参数名是否与站点搜索表单一致'
          : '返回页面里没出现关键词，搜索地址或参数可能不对（有的站要求 POST）';
      } else if (kwLinks === 0) {
        diag = '关键词只出现在正文里、不在任何链接上，返回的很可能不是搜索结果页（登录页/首页）';
      } else {
        diag = '页面里有含关键词的链接，但拼不成结果列表（结构被拆散，或结果由 JS 渲染）';
      }
      logs.push('诊断: ' + diag);
      trace[0].diag = diag;
    }
    // JSON 接口命中了结果数组、但只有 id 没有现成地址（没法自动造链接）时，
    // diag 仍是空——把自动识别给的规则建议补进诊断，调试台才能直接看到怎么改。
    if (!diag && autoJson) {
      diag = autoJson.hint;
      trace[0].diag = diag;
    }
    // noResult：站点**明确**回了「没有结果」页。这是「搜索链路是通的」的硬证据
    // （编码对、参数对、规则也没坏，只是这个词在站内没有），校验不能据此判源失效。
    return { list, logs, dropped, trace, diag, noResult: !!noRes, ...meta };
  }

  /**
   * 自动探测站点真实搜索入口（见文件顶部 buildSearchCandidates / scoreSearchCandidate）。
   * 专门对付「searchUrl 没写 {{key}} / 路径写死成死链」这类：关键词没发出去，请求打到死路径 → 404 / 0 结果。
   * 站点本身往往能搜，坏的是源的 searchUrl。这里去首页扒搜索框、试常见路径，真发请求验证哪个能用，
   * 把可用的 searchUrl（含 GET/POST 选项串）回传，原生侧一键改写即可，不用用户逐个手改。
   *
   * 返回 { ok, searchUrl, method, body, note } 或 { ok:false, reason }。
   */
  async discoverSearchUrl(opts = {}) {
    const home = this.bookSourceUrl;
    if (!home) return { ok: false, reason: '书源地址为空' };
    // 探测是额外请求，给短预算，别拖垮调用方
    this.setRequestPolicy({ timeout: 8000, retries: 0, connectTimeout: 6000, readTimeout: 8000 });
    let probeWord = String(opts.keyword || '').trim();
    let homeResp;
    try {
      // 必须走 fetchText，不能直接 fetchViaProxy：fetchText 会按书源带上 Referer / UA / header，
      // 还有 Cloudflare 重试与 GBK 纠错。裸请求什么都不带，很多站直接 403，
      // 结果被误报成「站点已下线」，用户就以为源坏了（0.9.115 实测踩到）。
      homeResp = await this.fetchText(home, { page: 1 });
    } catch (e) {
      return { ok: false, reason: '首页打不开：' + ((e && e.message) || '网络错误') };
    }
    const homeBlocked = detectBlockedPage(homeResp.text, homeResp.status, { headers: homeResp.headers, contentType: homeResp.contentType });
    if (homeBlocked && homeBlocked.kind === 'cf') return { ok: false, reason: '首页被 Cloudflare 拦，无法自动探测' };
    if (homeBlocked && homeBlocked.kind === 'gated') return { ok: false, reason: '首页要求登录/验证码，无法自动探测' };
    if (homeResp.status && homeResp.status >= 400) {
      return { ok: false, reason: '首页返回 ' + homeResp.status + '（站点拒绝访问，可能已下线或只对浏览器开放）' };
    }
    const homeUrl = homeResp.finalUrl || home;
    let doc = null;
    try { doc = parseDoc(homeResp.text || ''); } catch (e) { doc = null; }
    // 优先用首页真实存在的书名当探针：站内一定有这本书，搜索能返回结果页，
    // 比固定词更能区分「真结果页」和「无结果/壳页」。
    if (!probeWord && doc) {
      const names = probeNames(doc, homeUrl);
      if (names && names.length) probeWord = names[0];
    }
    if (!probeWord) probeWord = '斗破苍穹';
    const homeDeep = doc ? countDeepLinks(doc, homeUrl) : 0;
    const homeAll = doc ? countAllLinks(doc, homeUrl) : 0;
    const cands = buildSearchCandidates(doc, homeUrl);
    let best = null;
    let sawRate = false;
    let sawNoRes = false;
    let sawGated = false;
    // 每条候选的结局都要计数：失败时把它拼成「试了 6 条：被 Cloudflare 拦 4、超时 2」，
    // 用户一眼能判断「是整站访问不了」还是「我的规则写错」。只说「没找到」等于什么都没说。
    const kindStat = {};
    for (const c of cands) {
      let s;
      try { s = await scoreSearchCandidate(this, c, probeWord, homeDeep, homeAll); }
      catch (e) { s = { score: 0 }; }
      if (s && s.rateLimited) sawRate = true;
      if (s && s.noResult) sawNoRes = true;
      if (s && s.gated) sawGated = true;
      if (s && s.kind) kindStat[s.kind] = (kindStat[s.kind] || 0) + 1;
      if (s && s.score > (best ? best.score : 0)) best = Object.assign({ cand: c }, s);
      // 已经拿到「关键词落地 + 是真结果页」的候选就收工：探测每多试一个候选就要多等一次
      // 网络往返，批量修复几十个源时会线性变慢。7 分已经要求「页面里出现关键词且有结果链接」，
      // 足够可信；表单候选 8 分以上更是站点自己的地址，直接采纳。
      if (best && best.score >= 7) break;
    }
    if (!best || best.score < 5) {
      // 把「每条候选怎么死的」摊开：用户看到「被 Cloudflare 拦 4、超时 2」就知道是站点/网络问题，
      // 看到「页面里没出现关键词 6」才知道该改规则。没有这行，两种完全不同的病因长得一模一样。
      const label = {
        cf: '被 Cloudflare 拦', gated: '跳登录页', rate: '被限流', down: '站点故障',
        timeout: '请求超时', http404: '路径 404', tls: '证书/握手失败', error: '请求失败',
        same: '返回原首页', noecho: '页面里没出现关键词', weak: '结果不像列表', ok: '可用',
      };
      const brief = Object.entries(kindStat).map(([k, n]) => {
        const m = /^http(\d+)$/.exec(k);
        return (label[k] || (m ? 'HTTP ' + m[1] : k)) + ' ' + n;
      }).join('、');
      const detail = '试了 ' + cands.length + ' 条候选：' + (brief || '（无）');
      // 全被限流挡掉：不是「找不到」，是「这会儿问不出来」。两者给用户的动作完全不同
      // （前者该放弃/换源，后者等十几秒重试就有结果），必须分开说。
      if (sawRate) return { ok: false, reason: '站点限制了搜索频率，本次未能验证（等十几秒再试即可）', detail, rateLimited: true };
      if (sawNoRes) return { ok: false, reason: '站点搜索能用，只是站内没有「' + probeWord + '」这本书（换本书名再试，源不用改）', detail };
      // 搜索被站点锁在登录之后（实测 3qzw.com：POST /search.html 直接 302 到 /login.html）。
      // 这不是地址猜错，任何 searchUrl 都过不去 —— 说清楚，别让用户反复重试。
      if (sawGated) return { ok: false, reason: '该站搜索要求登录后才能用，App 内无法自动探测（这类源只能换站）', detail };
      return { ok: false, reason: '未能自动找到可用的搜索入口（搜索可能由 JS 渲染、或需登录后才能搜）', detail };
    }
    let searchUrl;
    if (best.cand.method === 'POST') {
      const body = best.cand.param + '={{key}}'
        + (best.cand.fields && best.cand.fields.length ? '&' + best.cand.fields.join('&') : '');
      searchUrl = best.cand.url + ',{"method":"POST","body":"' + body + '"}';
    } else {
      searchUrl = best.cand.url + (best.cand.url.indexOf('?') >= 0 ? '&' : '?') + best.cand.param + '={{key}}';
    }
    return { ok: true, searchUrl, method: best.cand.method, body: best.cand.method === 'POST' ? (best.cand.param + '={{key}}') : '', note: '探测到站点真实搜索入口' };
  }

  async explore(page = 1, exploreUrl) {
    const url = exploreUrl || this.exploreUrl || this.bookSourceUrl;
    const logs = [];
    let r;
    try {
      r = await this.fetchText(url, { page });
    } catch (e) {
      const t = e && e.resp
        ? traceStep('explore', '发现', e.resp, { error: e.message })
        : { stage: 'explore', label: '发现', url, status: 0, error: e.message, html: '' };
      return { list: [], logs: ['请求失败: ' + e.message], error: e, trace: [t] };
    }
    logs.push(`GET ${r.finalUrl} → ${r.status} ${r.text.length} 字节`);
    const blockHit = r.blocked || '';
    const re = this.ruleExplore || {};
    if (!re.bookList) return { list: [], logs: [...logs, '未配置 ruleExplore.bookList'],
      trace: [traceStep('explore', '发现', r, { rule: '（未配置 ruleExplore.bookList）' })] };
    const ctx = this._ctx(r);
    const rows = await extractRows(re, ctx, 'bookList', SEARCH_FIELDS);
    logs.push(`bookList 命中 ${rows.length} 条`);
    const list = rows
      .map((row) => ({
        name: (row.name || '').toString().trim(),
        author: (row.author || '').toString().trim(),
        intro: row.intro || '',
        coverUrl: resolveUrl(r.finalUrl, row.coverUrl) || resolveUrl(this.bookSourceUrl, row.coverUrl),
        bookUrl: resolveUrl(r.finalUrl, row.bookUrl) || resolveUrl(this.bookSourceUrl, row.bookUrl),
        kind: row.kind || '',
        lastChapter: row.lastChapter || '',
        sourceUrl: this.bookSourceUrl,
        sourceName: this.bookSourceName || this.bookSourceUrl,
      }))
      .filter((b) => b.name || b.bookUrl);
    if (list.length) clearBlockIfParsed(r, logs, `${list.length} 条发现结果`);
    if (!list.length && blockHit) {
      logs.push('嗅探命中 ' + blockHit + '，且一条结果都没解析出来');
      return { list: [], logs, error: new Error(blockHit), status: r.status,
        trace: [traceStep('explore', '发现', r, { error: blockHit, rule: String(re.bookList), hits: 0, logs: logs.slice() })] };
    }
    return { list, logs, status: r.status,
      trace: [traceStep('explore', '发现', r, { rule: String(re.bookList), hits: rows.length, kept: list.length, logs: logs.slice() })] };
  }

  /* ---------- 详情 ---------- */

  async getBookInfo(book) {
    const logs = [];
    if (!book || !book.bookUrl) {
      return { info: book, logs: ['缺少书籍地址（bookUrl）'], error: new Error('缺少书籍地址（bookUrl 规则未解析出内容）'), trace: [] };
    }
    let r;
    try {
      r = await this.fetchText(book.bookUrl, {});
    } catch (e) {
      const t = e && e.resp
        ? traceStep('info', '详情', e.resp, { error: e.message })
        : { stage: 'info', label: '详情', url: book.bookUrl, status: 0, error: e.message, html: '' };
      return { info: book, logs: ['详情请求失败: ' + e.message], error: e, trace: [t] };
    }
    const blockHit = r.blocked || '';
    // baseUrl 取**实际请求地址**：bookUrl 可能带 ",{method:POST,...}" 选项串，
    // 拿它当相对地址基准会把 "bookindex,{...}" 当路径的一部分，规则里的相对地址全拼错。
    const ctx = this._ctx(r, r.finalUrl || book.bookUrl);
    ctx.book = book;
    ctx.title = book.name || '';
    const rb = this.ruleBookInfo || {};
    // init 只用于预处理页面（常见写法是 JS 里换 JSON 接口），失败不该挡住整本
    try {
      await this._runPre(rb.init, ctx);
    } catch (e) {
      logs.push('init 失败（已忽略）: ' + e.message);
    }
    const row = {};
    // 字段级容错：单个字段规则失败不应让整本书「打不开」
    for (const f of INFO_FIELDS) {
      if (!rb[f]) {
        row[f] = null;
        continue;
      }
      try {
        // 同 extractRows：没写 @attr 的 CSS 规则要取文本，别把元素对象当字段值
        row[f] = await evalText(rb[f], ctx);
      } catch (e) {
        row[f] = null;
        logs.push(`字段 ${f} 解析失败: ${e.message}`);
      }
    }
    const info = {
      ...book,
      name: (row.name || book.name || '').toString().trim(),
      author: (row.author || book.author || '').toString().trim(),
      intro: row.intro || book.intro || '',
      coverUrl: resolveUrl(r.finalUrl, row.coverUrl) || book.coverUrl,
      kind: row.kind || book.kind || '',
      lastChapter: row.lastChapter || book.lastChapter || '',
      updateTime: row.updateTime || book.updateTime || '',
      wordCount: row.wordCount || book.wordCount || '',
      tocUrl: resolveUrl(r.finalUrl, row.tocUrl) || book.bookUrl,
    };
    // 详情字段解出任意一项 → 页面正常，撤销嗅探标记
    const gotField = INFO_FIELDS.some((f) => row[f] != null && String(row[f]).trim());
    if (gotField) clearBlockIfParsed(r, logs, '详情字段');
    else if (blockHit) {
      logs.push('嗅探命中 ' + blockHit + '，且详情字段全部为空');
      return { info: book, logs, error: new Error(blockHit), status: r.status,
        trace: [traceStep('info', '详情', r, { error: blockHit,
          rule: INFO_FIELDS.map((f) => f + '=' + String(rb[f] == null ? '' : rb[f])).join('\n'),
          fields: row, logs: logs.slice() })] };
    }
    const trace = [traceStep('info', '详情', r, {
      rule: INFO_FIELDS.map((f) => f + '=' + String(rb[f] == null ? '' : rb[f])).join('\n'),
      fields: row,
      logs,
    })];
    return { info, logs, trace };
  }

  /* ---------- 目录 ---------- */

  async getToc(book) {
    const logs = [];
    const traces = [];
    const rt = this.ruleToc || {};
    const first = book.tocUrl || book.bookUrl;
    if (!first) return { chapters: [], logs: ['缺少目录地址'], error: new Error('缺少目录地址'), trace: traces };
    // 没有 chapterList 不再直接判死：很多源的目录页就是普通 <a> 列表，
    // 下面 parsePage 有「按章节链接自动收集」兜底。提前 return 等于把这类源全判成规则缺失。
    if (!rt.chapterList) logs.push('未配置 ruleToc.chapterList，改用自动识别');

    /** 用书源自己的 chapterName/chapterUrl 规则映射 JSON 条目（可处理 cid 拼地址这类写法） */
    const mapJsonWithRules = async (arr, rules, ctx) => {
      const out = [];
      for (const item of arr.slice(0, 4000)) {
        if (!item || typeof item !== 'object') continue;
        const sub = subContext(ctx, item);
        let name = '';
        let url = '';
        try {
          if (rules.chapterName) name = toFieldText(await evalOne(rules.chapterName, sub)).trim();
        } catch (e) {
          /* 忽略单条失败 */
        }
        try {
          if (rules.chapterUrl) url = toFieldText(await evalOne(rules.chapterUrl, sub)).trim();
        } catch (e) {
          /* 忽略单条失败 */
        }
        if (!name || !url) continue;
        out.push({ name, url: resolveUrl(ctx.baseUrl, url), isVolume: false });
      }
      // 映射结果全是重复项时说明规则套错了（多半把整页 CSS 当成字段），放弃，交给自动识别
      const uniq = new Set(out.map((c) => c.name + '\n' + c.url));
      if (out.length < 2 || uniq.size !== out.length) return [];
      return out;
    };

    /** 抓一页并按规则解析章节（含 nextTocUrl 翻页） */
    const parsePage = async (url) => {
      let r;
      try {
        r = await this.fetchText(url, {});
      } catch (e) {
        logs.push(`目录请求失败（${url}）: ${e.message}`);
        traces.push(e && e.resp
          ? traceStep('toc', '目录', e.resp, { error: e.message })
          : { stage: 'toc', label: '目录', url, status: 0, error: e.message, html: '' });
        return [];
      }
      logs.push(`目录页 ${r.finalUrl} → ${r.status} ${r.text.length} 字节`);
      // 嗅探命中不再直接放弃：先按规则解析，解析出章节就说明页面是好的
      const blockHit = r.blocked || '';
      if (blockHit) logs.push('嗅探命中 ' + blockHit + '，仍继续尝试解析目录');
      const ctx = this._ctx(r, r.finalUrl || url);
      ctx.book = book;
      ctx.title = book.name || '';
      try {
        await this._runPre(rt.preUpdateJs, ctx);
      } catch (e) {
        logs.push('preUpdateJs 失败（已忽略）: ' + e.message);
      }

      const mapRows = (rows) =>
        rows
          .map((c) => ({
            name: (c.chapterName || '').toString().trim(),
            url: resolveUrl(ctx.baseUrl, c.chapterUrl),
            isVolume: !!c.isVolume,
          }))
          .filter((c) => c.name || c.url);

      let chapters = mapRows(await extractRows(rt, ctx, 'chapterList', TOC_FIELDS));
      if (chapters.length) {
        logs.push(`chapterList 命中 ${chapters.length} 章`);
        // 裸标签规则会把分类菜单/推荐位一起抓进来，按「是否与详情页同目录」剪掉
        const pruned = pruneAlienChapters(chapters, book.bookUrl || first);
        if (pruned.length !== chapters.length) {
          logs.push(`目录里混入 ${chapters.length - pruned.length} 条非同目录链接（分类/推荐），已忽略`);
          chapters = pruned;
        }
      } else {
        // 规则一条没中时的通用兜底：先用书源自己的字段规则套 JSON 条目（可处理 cid 拼地址），
        // 再退回按字段名自动映射；HTML 则按章节链接自动收集
        let auto = [];
        if (ctx.json) {
          const found = findChapterArray(ctx.json);
          if (found) auto = await mapJsonWithRules(found.arr, rt, ctx);
          if (!auto.length) auto = autoChaptersFromJson(ctx.json, ctx.baseUrl);
        } else {
          auto = autoChaptersFromDoc(ctx.doc, ctx.baseUrl);
        }
        if (auto.length) {
          chapters = auto;
          logs.push(`chapterList 未命中（规则 ${String(rt.chapterList).slice(0, 60)}），自动识别 ${auto.length} 章`);
        } else {
          logs.push(`chapterList 命中 0 章（规则 ${String(rt.chapterList).slice(0, 60)}）`);
        }
      }
      // 目录规则原文（三行）：只显示 chapterList 时，「0 章」根本看不出是列表没中
      // 还是字段规则空（52书库② 就是后者：只写了 chapterList，靠 Legado 默认值工作）
      const tocRuleText = 'chapterList=' + String(rt.chapterList == null ? '（未配置，已尝试自动识别）' : rt.chapterList)
        + '\nchapterName=' + String(rt.chapterName || '（空 → 默认 text）')
        + '\nchapterUrl=' + String(rt.chapterUrl || '（空 → 默认 href）');
      if (!chapters.length) {
        // 目录 0 章也可能是壳页（详情页能开、目录页被挡），这句能省掉一次瞎改规则
        const shell = detectJsShell(r.text, ctx.doc);
        if (shell) logs.push('诊断: ' + jsShellHint(shell));
        traces.push(traceStep('toc', '目录', r, {
          rule: tocRuleText,
          chapters: 0, sample: [], logs: logs.slice(),
          error: blockHit || undefined,
        }));
        return [];
      }
      clearBlockIfParsed(r, logs, `${chapters.length} 章`);
      traces.push(traceStep('toc', '目录', r, {
        rule: tocRuleText,
        chapters: chapters.length,
        sample: chapters.slice(0, 5),
        logs: logs.slice(),
      }));

      // nextTocUrl 翻页
      if (rt.nextTocUrl) {
        let nextUrl = await evalText(rt.nextTocUrl, ctx);
        let pages = 1;
        while (nextUrl && pages < 20) {
          const u = resolveUrl(ctx.baseUrl, nextUrl);
          let r2;
          try {
            r2 = await this.fetchText(u, {});
          } catch (e) {
            break;
          }
          const ctx2 = this._ctx(r2, u);
          try {
            await this._runPre(rt.preUpdateJs, ctx2);
          } catch (e) {
            logs.push('翻页 preUpdateJs 失败（已忽略）: ' + e.message);
          }
          const more = mapRows(await extractRows(rt, ctx2, 'chapterList', TOC_FIELDS));
          if (!more.length) break;
          chapters.push(...more);
          nextUrl = await evalText(rt.nextTocUrl, ctx2);
          pages++;
        }
        logs.push(`翻页后共 ${chapters.length} 章`);
      }
      return chapters;
    };

    let chapters = await parsePage(first);
    // 兜底：不少书源的 tocUrl 规则会抓到「最新章节」之类的小组件，真正的目录其实在详情页。
    // 目录页 0 章时用 bookUrl 再解析一次，能救回一大批「打开失败」。
    if (!chapters.length && book.bookUrl && book.bookUrl !== first) {
      logs.push('目录页未命中，回退用详情页解析');
      chapters = await parsePage(book.bookUrl);
    }
    if (!chapters.length) {
      return { chapters, logs, error: new Error('未解析到章节（目录规则可能失效）'), trace: traces };
    }
    return { chapters, logs, trace: traces };
  }

  /* ---------- 正文 ---------- */

  async getContent(book, chapter) {
    const logs = [];
    let r;
    try {
      r = await this.fetchText(chapter.url, {});
    } catch (e) {
      const t = e && e.resp
        ? traceStep('content', '正文', e.resp, { error: e.message })
        : { stage: 'content', label: '正文', url: chapter.url, status: 0, error: e.message, html: '' };
      return { content: '', logs: ['正文请求失败: ' + e.message], error: e, trace: [t] };
    }
    const blockHit = r.blocked || '';
    const rc = this.ruleContent || {};
    const vars = { chapterName: chapter.name, chapterUrl: chapter.url };
    const ctx = this._ctx(r, chapter.url);
    ctx.vars = vars;
    ctx.book = book;
    ctx.chapter = chapter;
    ctx.title = chapter.name || '';

    const plainOf = (h) => String(h == null ? '' : h).replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
    let content = rc.content ? toFieldHtml(await evalOne(rc.content, ctx)) : '';

    // 规则没命中就兜底识别正文（站点改版最常见：#nr1 之类的老 id 直接消失）。
    // 只兜底、不改源：日志里给出建议规则，调试台能直接看到该改成什么。
    let auto = null;
    if (plainOf(content).length < 20) {
      auto = detectContent(ctx.doc);
      if (auto && auto.html) {
        logs.push('正文规则' + (rc.content ? '「' + String(rc.content) + '」未命中' : '未配置')
          + '，已自动识别容器 ' + auto.label + '（建议改为 ' + auto.rule + '）');
        content = auto.html;
      } else {
        // 规则没中、兜底也没找到正文 → 可能是站点回了个 JS 壳页，别让用户去改规则
        const shell = detectJsShell(r.text, ctx.doc);
        if (shell) logs.push('诊断: ' + jsShellHint(shell));
      }
    }
    if (!rc.content && !auto) {
      return { content: '', logs: ['未配置 ruleContent.content，且页面上没有识别到正文容器'],
        trace: [traceStep('content', '正文', r, { rule: '（未配置 ruleContent.content）', contentLen: 0 })] };
    }

    // nextContentUrl 翻页拼接。走兜底时规则本身已失效，翻页规则大概率同样失效，
    // 再发请求只会白等，直接跳过。
    if (rc.nextContentUrl && !auto) {
      let nextUrl = await evalText(rc.nextContentUrl, ctx);
      let pages = 1;
      while (nextUrl && pages < 10) {
        const u = resolveUrl(ctx.baseUrl, nextUrl);
        let r2;
        try {
          r2 = await this.fetchText(u, {});
        } catch (e) {
          break;
        }
        const ctx2 = this._ctx(r2, u);
        ctx2.vars = vars;
        ctx2.book = book;
        ctx2.chapter = chapter;
        ctx2.title = chapter.name || '';
        const more = rc.content ? toFieldHtml(await evalOne(rc.content, ctx2)) : '';
        if (!more) break;
        content += '\n' + more;
        nextUrl = toFieldText(await evalOne(rc.nextContentUrl, ctx2));
        pages++;
      }
    }

    if (rc.replaceRegex) content = applyReplaceString(content, rc.replaceRegex);
    content = absolutizeContentUrls(content, ctx.baseUrl);
    const plain = String(content).replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
    // 正文解出 ≥20 个非空白字符 → 页面正常，撤销嗅探标记
    if (plain.length >= 20) clearBlockIfParsed(r, logs, '正文');
    else if (blockHit) {
      logs.push('嗅探命中 ' + blockHit + '，且正文为空');
      return { content: '', logs, error: new Error(blockHit), status: r.status,
        trace: [traceStep('content', '正文', r, { error: blockHit, rule: String(rc.content), contentLen: 0, logs })] };
    }
    return { content, logs, trace: [traceStep('content', '正文', r, {
      rule: String(rc.content == null || rc.content === '' ? '（未配置）' : rc.content),
      contentLen: String(content).length,
      preview: plain.slice(0, 300),
      // 兜底命中时把建议规则和候选容器一起回传：调试台直接显示「改成 xxx」
      autoRule: auto ? auto.rule : undefined,
      autoLabel: auto ? auto.label : undefined,
      candidates: auto ? auto.candidates : undefined,
      logs,
    })] };
  }
}

/**
 * 补全正文 HTML 中的相对图片/链接地址。
 * 源站正文常用 <img src="images/x.jpg">，在 App 壳（origin 是本地）里会 404，
 * 需按章节页 base 补全为绝对地址（Legado 同款处理）。
 */
function absolutizeContentUrls(html, base) {
  if (!html || !base) return html || '';
  const fix = (m, attr, q, u) => {
    const t = String(u).trim();
    if (!t || /^(#|data:|javascript:|mailto:|tel:)/i.test(t)) return m;
    return attr + q + resolveUrl(base, t) + q;
  };
  return String(html)
    .replace(/(<img\b[^>]*?\ssrc\s*=\s*)(["'])([^"']*)\2/gi, fix)
    .replace(/(<a\b[^>]*?\shref\s*=\s*)(["'])([^"']*)\2/gi, fix);
}

/* ---------- 正文兜底识别：规则失效时在页面上找「最像正文」的容器 ---------- */

/**
 * 结构类 class/id 关键词：命中即排除。
 * 这些容器里的中文字也不少（导航、推荐位、评论区），但都不是正文；
 * 不排除的话，越大的盒子分越高，最后一定选中 <body> 或整页 wrapper。
 */
const BAD_BOX = /(^|[\s_-])(nav|navbar|menu|header|head|footer|sidebar|side|comment|comments|breadcrumb|breadcrumbs|ranking|rank|related|recommend|pagination|pager|copyright|ad|ads|banner|share|toolbar|search|login|vip)([\s_-]|$)/i;

function cnCount(s) {
  const m = String(s == null ? '' : s).match(/[\u4e00-\u9fa5]/g);
  return m ? m.length : 0;
}

/** tag#id.class1.class2，用于日志和候选列表展示 */
function boxLabel(el) {
  if (!el || !el.tagName) return '';
  const tag = el.tagName.toLowerCase();
  const id = (el.getAttribute && el.getAttribute('id')) || '';
  const cls = ((el.getAttribute && el.getAttribute('class')) || '').trim();
  return tag + (id ? '#' + id : '') + (cls ? '.' + cls.split(/\s+/).join('.') : '');
}

/** 把容器转成一条可以直接粘进 ruleContent.content 的规则 */
function ruleForBox(el) {
  if (!el || !el.tagName) return '';
  const tag = el.tagName.toLowerCase();
  const id = (el.getAttribute && el.getAttribute('id')) || '';
  if (/^[A-Za-z_][\w-]*$/.test(id)) return '#' + id + '@html';
  const cls = ((el.getAttribute && el.getAttribute('class')) || '').trim().split(/\s+/)
    .filter((c) => /^[A-Za-z_][\w-]*$/.test(c));
  if (cls.length) return tag + '.' + cls[0] + '@html';
  return tag + '@html';
}

/** 容器 → 干净的正文 HTML：有段落就只取段落，否则退回去标签后的整块 */
function pickContentHtml(el) {
  const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const ps = el.querySelectorAll('p');
  let out = '';
  if (ps.length >= 2) {
    for (let i = 0; i < ps.length; i++) {
      const t = String(ps[i].textContent || '').replace(/\s+/g, ' ').trim();
      if (t.length < 2) continue;
      // 段里带图的（部分站把插图直接塞在 <p> 里）保留原标签，纯文本段落才转义，
      // 否则兜底会把正文插图一并抹掉。
      const raw = String(ps[i].innerHTML || '');
      out += /<img\b/i.test(raw)
        ? '<p>' + raw.replace(/<(script|style)\b[\s\S]*?<\/\1>/gi, '') + '</p>'
        : '<p>' + esc(t) + '</p>';
    }
  }
  if (out) return out;
  const clone = el.cloneNode(true);
  const junk = clone.querySelectorAll('script,style,iframe,noscript,ins,form');
  for (let i = 0; i < junk.length; i++) {
    if (junk[i] && junk[i].parentNode) junk[i].parentNode.removeChild(junk[i]);
  }
  return clone.innerHTML || '';
}

/**
 * 在一页 HTML 里找出正文容器。
 * 评分 = 中文字数 − 链接文字字数×0.6 + 段落数×4：
 * 减去链接是压掉「目录页/推荐位」（全是链接），加段落数是偏向真正的连载正文。
 * 分数接近时取更深的那个 —— 同一段正文常被 div > div > article 层层包着，
 * 取外层会把上下章链接、广告一起带进来。
 */
function detectContent(doc) {
  if (!doc || !doc.body) return null;
  const nodes = doc.body.querySelectorAll('div,article,section,td,main');
  const pageCn = cnCount(doc.body.textContent);
  if (pageCn < 100) return null;
  const cands = [];
  const cap = Math.min(nodes.length, 500);
  for (let i = 0; i < cap; i++) {
    const el = nodes[i];
    if (/^(nav|header|footer|aside)$/i.test(el.tagName)) continue;
    if (BAD_BOX.test(boxLabel(el))) continue;
    const cn = cnCount(el.textContent);
    if (cn < 120 || cn / pageCn < 0.12) continue;
    let linkCn = 0;
    const as = el.querySelectorAll('a');
    for (let k = 0; k < as.length; k++) linkCn += cnCount(as[k].textContent);
    const score = cn - linkCn * 0.6 + Math.min(el.querySelectorAll('p').length, 60) * 4;
    if (score <= 0) continue;
    cands.push({ el, cn, score });
  }
  if (!cands.length) return null;
  cands.sort((a, b) => b.score - a.score);
  let best = cands[0];
  for (let i = 0; i < cands.length; i++) {
    const c = cands[i];
    if (c.el !== best.el && best.el.contains(c.el) && c.score >= best.score * 0.85) best = c;
  }
  const html = pickContentHtml(best.el);
  if (cnCount(html.replace(/<[^>]+>/g, '')) < 80) return null;
  return {
    html,
    label: boxLabel(best.el),
    rule: ruleForBox(best.el),
    // 被选中的排第一位，调试台第一眼就能看到「就是这个」
    candidates: [best].concat(cands.filter((c) => c.el !== best.el)).slice(0, 3)
      .map((c) => ({ rule: ruleForBox(c.el), label: boxLabel(c.el), cn: cnCount(c.el.textContent) })),
  };
}

/**
 * 宽松 JSON 解析：Legado 书源的 header / URL 选项大量使用单引号伪 JSON
 * （{'User-Agent':'...'}、{'method':'POST','body':'...'}），严格 JSON.parse 会失败，
 * 结果是 header 全丢（站点 403）或整串被当 URL 请求（404）——两者都表现为"源失效"。
 */
function parseLooseOpts(s) {
  if (!s) return null;
  // 从聊天窗口/网页复制书源时最容易被带进来的一类污染：中文引号、全角逗号冒号、
  // 全角空格、零宽字符、BOM。JSON.parse 一个都不认 —— 解析失败后整串会被当成 URL，
  // 请求打到一个带 {} 的畸形路径上，站点不回应，用户只看到「站点无响应」（26 秒）。
  // 先把这些统一成半角再来，绝大多数「源格式坏了」其实就是这一步。
  const norm = String(s)
    .replace(/[\u200B-\u200D\uFEFF]/g, '')
    .replace(/[\u201C\u201D\u2033\uFF02]/g, '"')
    .replace(/[\u2018\u2019]/g, "'")
    .replace(/\uFF0C/g, ',')
    .replace(/[\uFF1A\u2236]/g, ':')
    .replace(/\u3000/g, ' ')
    .replace(/,(\s*[}\]])/g, '$1');   // 尾随逗号：JSON 不允许，手写源里很常见
  try {
    return JSON.parse(norm);
  } catch (e) {
    /* 继续宽松解析 */
  }
  try {
    const t = norm
      .replace(/'/g, '"')
      .replace(/([{,]\s*)([A-Za-z_$][\w$]*)\s*:/g, '$1"$2":');
    return JSON.parse(t);
  } catch (e) {
    return null;
  }
}

/** 解析书源 header 字段（Legado 存的是 JSON 字符串，常见单引号写法） */
function parseHeader(h) {
  if (!h) return {};
  if (typeof h === 'object') return h;
  return parseLooseOpts(h) || {};
}
