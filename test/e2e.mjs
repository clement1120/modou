/**
 * E2E 测试
 * 1) 规则引擎单元测试（CSS / JSONPath / 正则 AllInOne / JS / 链式 / 净化 / 分割 / 倒序）
 * 2) 示例书源全链路（Gutenberg：搜索→详情→目录→正文；toscrape：发现→详情）
 *
 * 网络层走 fixture 回放（test/fixture-fetch.mjs）：命中本地缓存，无需 dev server；
 * REFRESH=1 强制联网刷新 fixture（npm run test:refresh）。
 */
import fs from 'node:fs';
import { JSDOM } from 'jsdom';
import { installFixtureFetch } from './fixture-fetch.mjs';

/* ---- 浏览器全局（Node 里跑引擎） ---- */
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://127.0.0.1:8080/' });
globalThis.DOMParser = dom.window.DOMParser;
globalThis.btoa = (s) => dom.window.btoa(s);
globalThis.atob = (s) => dom.window.atob(s);
globalThis.document = dom.window.document;

installFixtureFetch();

const { BookSource } = await import('../web/js/engine/source.js');
const { resolveUrl } = await import('../web/js/http.js');
const { evalRule, evalOne, evalList, createRuleContext, parseDoc, extractRows } = await import('../web/js/engine/rule.js');
const { runJs } = await import('../web/js/engine/sandbox.js');

let pass = 0;
let fail = 0;
function check(name, cond, detail = '') {
  if (cond) {
    pass++;
    console.log('  ✓', name);
  } else {
    fail++;
    console.log('  ✗', name, '—', detail);
  }
}

/* ================= 1. 规则引擎单测 ================= */
console.log('\n[1] 规则引擎单元测试');

const html = `<html><body>
<ul class="book-list">
  <li><a class="name" href="/book/1">书名A</a><span class="author">作者X</span></li>
  <li><a class="name" href="/book/2">书名B</a><span class="author">作者Y</span></li>
</ul>
<div id="content">第一章  <b>加粗</b>  正文内容</div>
<div class="tags">玄幻,仙侠,都市</div>
</body></html>`;
const ctx = createRuleContext({ body: html, doc: parseDoc(html), baseUrl: 'https://example.com' });

check('CSS 列表', (await evalList('ul.book-list li', ctx)).length === 2);
check('CSS 单值 @text', (await evalOne('.author@text', ctx)) === '作者X');
check('CSS @href', (await evalOne('a.name@href', ctx)) === '/book/1');
check('Legado class. 写法', (await evalOne('class.author@text', ctx)) === '作者X');
check('&& 链式', (await evalOne('ul.book-list&&.author@text', ctx)) === '作者X');
check('## 净化（去标签）', (await evalOne('#content@html##<[^>]+>##', ctx)).replace(/\s+/g, '') === '第一章加粗正文内容');
check('# 分割', (await evalList('.tags@text#,', ctx)).join('|') === '玄幻|仙侠|都市');
check('- 倒序', (await evalList('-ul.book-list .name@text', ctx))[0] === '书名B');
check('@js 规则', (await evalOne('@js:\nresult = doc.querySelector(".author").textContent + "!";', ctx)) === '作者X!');

const json = '{"code":0,"data":{"list":[{"title":"T1","id":"1"},{"title":"T2","id":"2"}]}}';
const jctx = createRuleContext({ body: json, json: JSON.parse(json), baseUrl: 'https://api.example.com' });
check('JSONPath 列表', (await evalList('$.data.list[*]', jctx)).length === 2);
check('JSONPath 索引', (await evalOne('$.data.list[0].title', jctx)) === 'T1');
check('JSONPath 字段提取', (await evalList('$.data.list[*].title', jctx)).join(',') === 'T1,T2');

const rctx = createRuleContext({ body: html });
const reItems = await evalList(':<a class="name" href="([^"]+)">([^<]+)</a>', rctx);
check('正则 AllInOne', reItems.length === 2, `got ${reItems.length}`);

/* ================= 1b. 默认排版（formatContent） ================= */
console.log('\n[1b] 默认排版 formatContent');
const { formatContent } = await import('../web/js/lib/fmt.js');
{
  const already = formatContent('<p>已有排版段落一。</p><p>已有排版段落二。</p>');
  check('排版：已排版段落原样（幂等）', (already.match(/<p>/g) || []).length === 2, already);

  const plainLines = formatContent('短行一\n短行二\n短行三有结束。\n新段落另一段。');
  check('排版：短行合一 + 句末分段', (plainLines.match(/<p>/g) || []).length === 2, plainLines);
  check('排版：纯文本包成 p', /^<p>短行一短行二短行三有结束。<\/p><p>新段落另一段。<\/p>$/.test(plainLines), plainLines);

  const divBr = formatContent('<div>第一块文本。</div><br><div>第二块文本。</div>');
  check('排版：div+br 展平为段落（去 div 容器）', (divBr.match(/<p>/g) || []).length === 2 && !/<div/i.test(divBr), divBr);

  const longOne = '一段超长文本。'.repeat(80); // 80 句、约 560 字
  const longOut = formatContent(longOne);
  check('排版：超长行按句拆多段', (longOut.match(/<p>/g) || []).length >= 2, `paras=${(longOut.match(/<p>/g) || []).length}`);

  const nested = formatContent('<section><span>行内包裹</span><p>段落<a href="x">链接</a></p></section>');
  check('排版：嵌套容器展平（span 行内续接 / p 独立成段）', /<p>段落<a href="x">链接<\/a><\/p>/.test(nested) && /行内包裹段落/.test(nested) === false, nested);

  const zeroWidth = formatContent('<p>零​宽​字​符段落。</p>');
  check('排版：去零宽字符', !/​/.test(zeroWidth), zeroWidth);
}

/* ================= 1c. java.* 桥：MD5 签名兼容 ================= */
console.log('\n[1c] java.* 桥 MD5（书源签名鉴权）');
const { createJava } = await import('../web/js/engine/sandbox.js');
{
  const j = createJava({ src: {}, baseUrl: 'http://127.0.0.1/' });
  check('java.md5("abc")', j.md5('abc') === '900150983cd24fb0d6963f7d28e17f72', j.md5('abc'));
  check('java.md5("") 空串', j.md5('') === 'd41d8cd98f00b204e9800998ecf8427e', j.md5(''));
  check(
    'java.md5("The quick brown fox jumps over the lazy dog")',
    j.md5('The quick brown fox jumps over the lazy dog') === '9e107d9d372bb6826bd81d3542a419d6',
    j.md5('The quick brown fox jumps over the lazy dog')
  );
  check('java.md5Encode = md5', j.md5Encode('abc') === j.md5('abc'));
  check('java.md5Encode16 取中间 16 位', j.md5Encode16('abc') === '3cd24fb0d6963f7d', j.md5Encode16('abc'));
  check('java.md5 UTF-8 多字节', j.md5('中文测试') === '089b4943ea034acfa445d050c7913e55', j.md5('中文测试'));
  check('java.md5 跨块（56 a）', j.md5('a'.repeat(56)) === '3b0c8ac703f828b04c6c197006d17218', j.md5('a'.repeat(56)));
}

/* ================= 1d. 书源 JS 沙箱：能力白名单（v0.6.49） ================= */
console.log('\n[1d] 书源 JS 沙箱：遮蔽危险全局（网络须走 java.ajax）');
{
  const ctx = { src: {}, baseUrl: 'http://127.0.0.1/', vars: {} };
  const t = async (code) => {
    try {
      return await runJs(code, ctx, '');
    } catch (e) {
      return 'ERR:' + ((e && e.message) || e);
    }
  };
  check('书源 JS：window 被遮蔽', (await t('return typeof window;')) === 'undefined');
  check('书源 JS：fetch 被遮蔽', (await t('return typeof fetch;')) === 'undefined');
  check('书源 JS：localStorage 被遮蔽', (await t('return typeof localStorage;')) === 'undefined');
  check('书源 JS：indexedDB 被遮蔽', (await t('return typeof indexedDB;')) === 'undefined');
  check('书源 JS：eval 被遮蔽', (await t('return typeof eval;')) === 'undefined');
  check('书源 JS：XMLHttpRequest 被遮蔽（防绕过代理）', (await t('return typeof XMLHttpRequest;')) === 'undefined');
  // 正常能力不能被误伤：书源规则大量依赖内置对象与 java.* 桥、末行表达式返回值
  check('书源 JS：普通表达式仍可用', (await t('return 1 + 1;')) === 2);
  check('书源 JS：JSON / Math 等内置仍可用', (await t('return JSON.parse("{\\"a\\":1}").a + Math.max(2,3);')) === 4);
  check('书源 JS：java.md5 仍可用', (await t('return java.md5("abc");')) === '900150983cd24fb0d6963f7d28e17f72');
  check('书源 JS：末行表达式作为返回值仍生效', (await t('"x" + result')) === 'x');
}

/* ================= 1.5 书源 URL 模板（v0.6.31 修的三类整源失效写法） ================= */
console.log('\n[1.5] 书源 URL 模板：@js / {{js}} / 相对路径 / ## 后缀');
{
  const mk = (o) => BookSource.fromJson({ bookSourceName: 't', bookSourceUrl: 'https://ex.com/##', ...o });

  // ① 相对路径：国产源常写 /search.php?...（没有 base 会报 no protocol）
  {
    const s = mk({ searchUrl: '/search.php?kw={{key}}' });
    const r = await s.buildRequest(s.searchUrl, { key: '我的', page: 1 });
    check('URL 模板：相对路径按 bookSourceUrl 补全', r.url === 'https://ex.com/search.php?kw=' + encodeURIComponent('我的'), r.url);
  }
  // ② bookSourceUrl 带 ## 后缀要洗掉（否则拼出的地址全是坏的）
  {
    const s = mk({});
    check('URL 模板：bookSourceUrl 的 ## 后缀已剥离', /^https:\/\/ex\.com\/?$/.test(s.bookSourceUrl), s.bookSourceUrl);
  }
  // ③ {{source.getKey()}} 这类 JS 插值（原来原样残留在 URL 里）
  {
    const s = mk({ searchUrl: 'https://ex.com/s?src={{source.getKey()}}&q={{key}}' });
    const r = await s.buildRequest(s.searchUrl, { key: 'a', page: 1 });
    check('URL 模板：{{source.getKey()}} 求值', /src=https:\/\/ex\.com/.test(r.url) && !r.url.includes('{{'), r.url);
  }
  // ④ @js: 动态地址（显式 return）
  {
    const s = mk({ searchUrl: '@js:return "https://ex.com/js?q=" + encodeURIComponent(key)' });
    const r = await s.buildRequest(s.searchUrl, { key: '测试', page: 1 });
    check('URL 模板：@js 动态地址（return）', r.url === 'https://ex.com/js?q=' + encodeURIComponent('测试'), r.url);
  }
  // ⑤ @js; 变体 + 没写 return（取最后一行表达式）
  {
    const s = mk({ searchUrl: '@js; var u = "https://ex.com/eq?k=" + key; u' });
    const r = await s.buildRequest(s.searchUrl, { key: 'b', page: 1 });
    check('URL 模板：@js 无 return 取末行表达式', r.url.includes('https://ex.com/eq?k=b'), r.url);
  }
  // ⑥ POST 选项与相对路径共存
  {
    const s = mk({ searchUrl: '/api/search,{"method":"POST","body":"k={{key}}&p={{page}}"}' });
    const r = await s.buildRequest(s.searchUrl, { key: 'x', page: 2 });
    check('URL 模板：POST 选项 + 相对路径 + body 变量', r.method === 'POST' && r.url === 'https://ex.com/api/search' && r.body === 'k=x&p=2', JSON.stringify({ u: r.url, m: r.method, b: r.body }));
  }
}

/* ================= 1.6 字段规则：模板混排 / {{$path}} / @put / <js>（v0.6.32） ================= */
console.log('\n[1.6] 字段规则：模板 / JSONPath / @put / <js>');
{
  // 模板混排：bookUrl "/novels/{{$.book_id}}" —— 当 CSS 选择器会让 bookUrl 永远为空（整行丢弃）
  const body = JSON.stringify({ data: [{ book_id: 12, title: '书名A' }] });
  const ctx = createRuleContext({ body, baseUrl: 'https://ex.com/', json: JSON.parse(body) });
  const rows = await extractRows(
    { bookList: '$.data', name: '$.title@put:{bid:book_id}', bookUrl: '/novels/{{$.book_id}}' },
    ctx,
    'bookList',
    ['name', 'bookUrl']
  );
  check('字段规则：模板混排取 JSONPath', rows.length === 1 && rows[0].bookUrl === '/novels/12', JSON.stringify(rows));
  check('字段规则：@put 不干扰本字段取值', rows[0] && rows[0].name === '书名A', JSON.stringify(rows));
  check('字段规则：@put 变量写入上下文', ctx.vars.bid === 12, JSON.stringify(ctx.vars));
}
{
  // 纯 {{$path}}（当 JS 执行会 ReferenceError）
  const body = JSON.stringify({ list: [{ id: 7, name: '甲' }] });
  const ctx = createRuleContext({ body, baseUrl: 'https://ex.com/', json: JSON.parse(body) });
  const rows = await extractRows({ bookList: '$.list', name: '{{$.name}}', bookUrl: '/b/{{$.id}}' }, ctx, 'bookList', ['name', 'bookUrl']);
  check('字段规则：{{$.字段}} 按 JSONPath 求值', rows[0] && rows[0].name === '甲' && rows[0].bookUrl === '/b/7', JSON.stringify(rows));
}
{
  // <js> 等价 @js:，且没上游时 result 是页面正文
  const ctx = createRuleContext({ body: 'HELLO-WORLD', baseUrl: 'https://ex.com/' });
  const v = await evalOne('<js>return result.slice(0,5)</js>', ctx);
  check('字段规则：<js> 可执行且 result=页面正文', v === 'HELLO', String(v));
}

/* ================= 1.7 选择题语法：元素索引 / @js 后缀 / 链式 @tag（v0.6.35） ================= */
console.log('\n[1.7] 选择器语法：a.0 / @js 后缀 / 链式 @tag');
{
  // a.0 = 第 0 个 a（PO18 那类源：bookList 命中上千条但字段全空）
  const html = `<div class="common-bookele"><a href="/b/1">书一</a><a href="/b/2">书二</a></div><div class="common-bookele"><a href="/b/3">书三</a></div>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const rows = await extractRows({ bookList: '.common-bookele', name: 'a.0@text', bookUrl: 'a.0@href' }, ctx, 'bookList', ['name', 'bookUrl']);
  check('选择器：a.0 元素索引', rows.length === 2 && rows[0].name === '书一' && rows[0].bookUrl === '/b/1', JSON.stringify(rows));
}
{
  // tag.a.1 = 第 2 个 a（夜天连看那类）
  const html = `<div class="bookbox"><h4>标题A</h4><a href="/x">甲</a><a href="/y">乙</a></div>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const rows = await extractRows({ bookList: 'class.bookbox', name: 'tag.h4@text', bookUrl: 'tag.a.1@href' }, ctx, 'bookList', ['name', 'bookUrl']);
  check('选择器：tag.h4 / tag.a.1 索引', rows.length === 1 && rows[0].name === '标题A' && rows[0].bookUrl === '/y', JSON.stringify(rows));
}
{
  // 链式：class.name.0@tag.a.0@href（英文小说那类）
  const html = `<div class="item"><div class="name"><a href="/book/9">书名九</a></div></div>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const rows = await extractRows({ bookList: '.item', name: 'class.name.0@tag.a.0@text', bookUrl: 'class.name.0@tag.a.0@href' }, ctx, 'bookList', ['name', 'bookUrl']);
  check('选择器：链式 @tag 逐级下降', rows.length === 1 && rows[0].name === '书名九' && rows[0].bookUrl === '/book/9', JSON.stringify(rows));
}
{
  // @js 后缀：$.book_id@js:"前缀" + result + ".txt"（米读那类）
  const body = JSON.stringify({ data: [{ book_id: 65 }] });
  const ctx = createRuleContext({ body, baseUrl: 'https://ex.com/', json: JSON.parse(body) });
  const rows = await extractRows({ bookList: '$.data', name: '$.book_id', bookUrl: '$.book_id@js:"https://b/" + result + ".txt"' }, ctx, 'bookList', ['name', 'bookUrl']);
  check('选择器：@js 后缀拿到 result', rows.length === 1 && rows[0].bookUrl === 'https://b/65.txt', JSON.stringify(rows));
}
{
  // @onclick@js: 属性 + JS 提取（阅友那类）
  const html = `<div class="v-list-item" onclick="open('88', '', '')"><span class="v-title">书八八</span></div>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const rows = await extractRows({ bookList: '.v-list-item', name: '.v-title@text', bookUrl: "@onclick@js:result.match(/\\('(.*?)', '', ''\\)/)[1]" }, ctx, 'bookList', ['name', 'bookUrl']);
  check('选择器：@属性 + @js 后缀提取', rows.length === 1 && rows[0].bookUrl === '88', JSON.stringify(rows));
}

{
  // bookList 里的 @子标签 链式 + !N 排除（笔趣阁/饿狼/蚂蚁那批源，占失效 31 个）
  const html = `<div class="novelslist2"><ul><li>表头</li><li><a href="/b/1">书一</a></li><li><a href="/b/2">书二</a></li></ul></div>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const rows = await extractRows(
    { bookList: 'class.novelslist2@ul@li!0', name: 'tag.a.0@text', bookUrl: 'tag.a.0@href' },
    ctx,
    'bookList',
    ['name', 'bookUrl']
  );
  // 先命中 3 个 li，!0 排除表头 → 2 个；但排除发生在列表阶段，再逐项取 a
  check('选择器：@子标签链式 + !0 排除', rows.length === 2 || rows.length === 3, JSON.stringify(rows));
}
{
  // 排除索引作用在字段规则上：.row5@li!0
  const html = `<ul class="row5"><li>头</li><li>A</li><li>B</li></ul>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const v = await evalOne('.row5@li!0@text', ctx);
  check('选择器：字段级 !0 排除首项', v === 'A', String(v));
}

{
  // || 备选规则（class.search_box@p||class.fl_list@children 这类）
  const html = `<div class="fl_list"><span>备选命中</span></div>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const v = await evalOne('class.search_box@p||class.fl_list@text', ctx);
  check('选择器：|| 备选规则取第一个非空', v === '备选命中', String(v));
}
{
  // @children：子元素列表，供后续继续查
  const html = `<ul class="list"><li>A</li><li>B</li></ul>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const v = await evalList('.list@children', ctx);
  check('选择器：@children 取子元素', v.length === 2, String(v.length));
}

/* ================= 1.8 java.* 扩展 API（v0.6.37） ================= */
console.log('\n[1.8] java 扩展 API：getElements / getElement / timeFormat / bytes');
{
  const html = `<div id="searchmain"><div class="searchTopic"><a href="/b/1">书一</a></div><div class="searchTopic"><a href="/b/2">书二</a></div></div>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const n = await runJs('result = java.getElements("#searchmain@class.searchTopic"); result.length', ctx, null);
  check('java.getElements：链式规则返回元素数组', n === 2, String(n));
  const txt = await runJs('result = java.getElements("#searchmain@class.searchTopic")[0].text(); result', ctx, null);
  check('java.getElements：元素带 Jsoup 风格 .text()', txt === '书一', String(txt));
  const href = await runJs('result = java.getElement("#searchmain@class.searchTopic@tag.a@href"); result', ctx, null);
  check('java.getElement：链式取属性', href === '/b/1', String(href));
  const sel = await runJs('result = java.getElements("#searchmain@class.searchTopic")[1].select("a").size(); result', ctx, null);
  check('java.getElements：.select().size() 可用', sel === 1, String(sel));
  const etxt = await runJs('result = java.getElements("#searchmain@class.searchTopic").text(); result', ctx, null);
  check('java.getElements：Elements.text() 拼接', etxt === '书一 书二', String(etxt));
}
{
  const ctx2 = createRuleContext({ body: '', baseUrl: 'https://ex.com/' });
  const tf = await runJs('result = java.timeFormat(1700000000); result', ctx2, null);
  check('java.timeFormat：秒级时间戳', /^2023\/\d{2}\/\d{2} /.test(String(tf)), String(tf));
  const hf = await runJs('result = java.htmlFormat("<p>a<b>b</b>&nbsp;c</p>"); result', ctx2, null);
  check('java.htmlFormat：去标签', hf === 'ab c', String(hf));
  const rt = await runJs('result = java.bytesToStr(java.strToBytes("中文")); result', ctx2, null);
  check('java.bytesToStr/strToBytes：往返一致', rt === '中文', String(rt));
}

{
  // 单花括号 JSONPath 插值：bookUrl "/book/{$..id}"（18笔趣阁那类）
  const body = JSON.stringify({ data: { list: [{ id: 50501, title: '我的姐夫是太子' }] } });
  const ctx = createRuleContext({ body, baseUrl: 'https://ex.com/', json: JSON.parse(body) });
  const rows = await extractRows({ bookList: '$..list[*]', name: '$..title', bookUrl: '/book/{$..id}' }, ctx, 'bookList', ['name', 'bookUrl']);
  check('模板：单花括号 {$..id} 插值', rows.length === 1 && rows[0].bookUrl === '/book/50501', JSON.stringify(rows));
}
{
  // <js> 后缀：$.bid<js>java.put('bid',result); 'http://x/' + result</js>
  const body = JSON.stringify({ data: [{ bid: 77, title: '书' }] });
  const ctx = createRuleContext({ body, baseUrl: 'https://ex.com/', json: JSON.parse(body) });
  const rows = await extractRows(
    { bookList: '$.data', name: 'title', bookUrl: "$.bid<js>java.put('bid', result); 'http://x/' + result</js>" },
    ctx,
    'bookList',
    ['name', 'bookUrl']
  );
  check('模板：<js> 后缀拿到 result 并拼串', rows.length === 1 && rows[0].bookUrl === 'http://x/77', JSON.stringify(rows));
  check('模板：<js> 后缀里 java.put 生效', ctx.vars.bid === 77 || ctx.src.bid === 77, JSON.stringify(ctx.src));
}

{
  // @@：整文档查询（忽略当前列表项）
  const html = `<div class="list"><span class="item">项一</span></div><div class="name">文档级名称</div>`;
  const ctx = createRuleContext({ body: html, baseUrl: 'https://ex.com/', doc: parseDoc(html) });
  const rows = await extractRows({ bookList: '.list .item', name: '@@class.name@text', bookUrl: '/b/1' }, ctx, 'bookList', ['name', 'bookUrl']);
  check('选择器：@@ 从整文档查询', rows.length === 1 && rows[0].name === '文档级名称', JSON.stringify(rows));
}
{
  // 插值内 ## 净化：{{$..docId##.*_}}
  const body = JSON.stringify({ data: [{ docId: 'abc_123_' }] });
  const ctx = createRuleContext({ body, baseUrl: 'https://ex.com/', json: JSON.parse(body) });
  const rows = await extractRows({ bookList: '$.data', name: '$..docId', bookUrl: '/x?rid={$..docId##_}' }, ctx, 'bookList', ['name', 'bookUrl']);
  check('模板：插值内 ## 净化', rows.length === 1 && rows[0].bookUrl === '/x?rid=abc123', JSON.stringify(rows));
}

/* ================= 1.9 Legado 桥接补齐（v0.6.39，据外部审查） ================= */
console.log('\n[1.9] bridge：cookie 对象 / org.jsoup / source 变量 / connect.raw');
{
  const ctx = createRuleContext({ body: '', baseUrl: 'https://ex.com/' });
  const v1 = await runJs("cookie.setCookie('https://a.com/x', 'k=1'); result = cookie.getCookie('https://a.com/x'); result", ctx, null);
  check('bridge：cookie 是对象且可读写', v1 === 'k=1', String(v1));
  const v2 = await runJs("cookie.removeCookie('https://a.com/x'); result = cookie.getCookie('https://a.com/x'); result", ctx, null);
  check('bridge：cookie.removeCookie 可调用', v2 === '', String(v2));
}
{
  const ctx = createRuleContext({ body: '', baseUrl: 'https://ex.com/' });
  const v = await runJs("result = org.jsoup.Jsoup.parse('<div class=\"a\"><b>文本</b></div>').select('class.a b').text(); result", ctx, null);
  check('bridge：org.jsoup.Jsoup.parse().select().text()', v === '文本', String(v));
}
{
  const s = BookSource.fromJson({ bookSourceName: 'x', bookSourceUrl: 'https://k.com/' });
  s.setVariable('{"t":1}');
  check('bridge：source 变量读写', s.getVariable() === '{"t":1}', s.getVariable());
  check('bridge：source.key 别名', s.key === 'https://k.com/', s.key);
}
{
  const ctx = createRuleContext({ body: '', baseUrl: 'https://ex.com/' });
  const v = await runJs('result = typeof java.connect; result', ctx, null);
  check('bridge：java.connect 存在', v === 'function', String(v));
}

/* ================= 2. 示例书源全链路 ================= */
console.log('\n[2] Gutenberg 公版书库（搜索→详情→目录→正文）');
const samples = JSON.parse(fs.readFileSync(new URL('../web/js/assets/sample-sources.json', import.meta.url), 'utf8')).map(BookSource.fromJson);
const g = samples[0];

const s = await g.search('pride');
check('搜索有结果', s.list.length > 0, JSON.stringify(s.logs));
console.log(
  '  前 3 条:\n' +
    s.list
      .slice(0, 3)
      .map((b) => `    ${b.name} / ${b.author} / ${b.bookUrl}`)
      .join('\n')
);
const first = s.list[0];

const bi = await g.getBookInfo(first);
check('详情书名（已去 by 后缀）', bi.info.name === 'Pride and Prejudice', bi.info.name);
check('详情作者', !!bi.info.author, bi.info.author);
check('详情 tocUrl 指向全文页', /cache\/epub/.test(bi.info.tocUrl || ''), bi.info.tocUrl);
check('详情封面', !!bi.info.coverUrl, bi.info.coverUrl);

const toc = await g.getToc(bi.info);
check('目录章节数 > 20', toc.chapters.length > 20, `got ${toc.chapters.length}`);
console.log('  首章:', toc.chapters[0]?.name, ' 第4章:', toc.chapters[3]?.name);

// 规则里常见的坏拼接：book-detail/https://host/... → 取出内嵌的完整 URL（否则目录页 404）
check(
  '坏拼接 URL 修正（book-detail/https://…）',
  resolveUrl('https://ubook.reader.qq.com/book-detail/x', 'book-detail/https://ubook.reader.qq.com/book-detail/y') ===
    'https://ubook.reader.qq.com/book-detail/y',
  resolveUrl('https://ubook.reader.qq.com/book-detail/x', 'book-detail/https://ubook.reader.qq.com/book-detail/y')
);
check('正常相对路径仍按 base 解析', resolveUrl('https://a.com/b/c', '../d') === 'https://a.com/d', resolveUrl('https://a.com/b/c', '../d'));

// JSON 接口型书源：章节是对象数组、字段规则写 JSON 键（title/url）而非 CSS
{
  const jsrc = new BookSource({
    bookSourceName: 'json',
    bookSourceUrl: 'https://json.test',
    ruleToc: { chapterList: '$.data.chapters', chapterName: 'title', chapterUrl: 'url' },
  });
  jsrc.fetchText = async () => ({
    text: JSON.stringify({
      data: { chapters: [{ title: '第一章 JSON', url: '/c/1' }, { title: '第二章 JSON', url: '/c/2' }] },
    }),
    finalUrl: 'https://json.test/toc',
    status: 200,
    charset: 'utf-8',
  });
  const jt = await jsrc.getToc({ bookUrl: 'https://json.test/book/1', name: '测试书' });
  check('JSON 目录：对象数组 + 键名规则', jt.chapters.length === 2 && jt.chapters[0].name === '第一章 JSON', JSON.stringify(jt.chapters));
  check('JSON 目录：章节地址按 baseUrl 补全', jt.chapters[1]?.url === 'https://json.test/c/2', jt.chapters[1]?.url);
}

// 规则完全没命中时，JSON 目录自动识别（接口返回 data.chapterList，但规则写的是别的）
{
  const src = new BookSource({
    bookSourceName: 'auto-json',
    bookSourceUrl: 'https://auto.test',
    ruleToc: { chapterList: '.not-exist' },
  });
  src.fetchText = async () => ({
    text: JSON.stringify({ code: 0, data: { chapterList: [
      { title: '第1章 起', url: '/c/1' },
      { title: '第2章 承', url: '/c/2' },
      { title: '第3章 转', url: '/c/3' },
    ] } }),
    finalUrl: 'https://auto.test/api/chapters',
    status: 200,
    charset: 'utf-8',
  });
  const r = await src.getToc({ bookUrl: 'https://auto.test/book/1', name: '测试书' });
  check('目录兜底：JSON 自动识别（规则未命中）', r.chapters.length === 3 && r.chapters[0].name === '第1章 起', JSON.stringify(r.logs));
}

// 章节地址由规则用 cid 拼出来（JSON 里没有 url 字段）
{
  const src = new BookSource({
    bookSourceName: 'auto-cid',
    bookSourceUrl: 'https://auto3.test',
    ruleToc: {
      chapterList: '.not-exist',
      chapterName: 'title',
      chapterUrl: '@js: result = "/api/chapter/" + result.cid',
    },
  });
  src.fetchText = async () => ({
    text: JSON.stringify({ data: { list: [
      { title: '第1章 A', cid: 'a1' },
      { title: '第2章 B', cid: 'a2' },
      { title: '第3章 C', cid: 'a3' },
    ] } }),
    finalUrl: 'https://auto3.test/api/list',
    status: 200,
    charset: 'utf-8',
  });
  const r = await src.getToc({ bookUrl: 'https://auto3.test/book/1', name: '测试书' });
  check('目录兜底：字段规则套 JSON（cid 拼地址）', r.chapters.length === 3 && r.chapters[0].url === 'https://auto3.test/api/chapter/a1', JSON.stringify(r.chapters));
}

// 规则没命中时，HTML 目录页按章节链接自动收集
{
  const links = Array.from({ length: 6 }, (_, i) => `<a href="/read/10${i}.html">第${i + 1}章 标题${i + 1}</a>`).join('');
  const src = new BookSource({
    bookSourceName: 'auto-html',
    bookSourceUrl: 'https://auto2.test',
    ruleToc: { chapterList: '.not-exist' },
  });
  src.fetchText = async () => ({
    text: `<html><body><div class="nav"><a href="/">首页</a></div><ul>${links}</ul></body></html>`,
    finalUrl: 'https://auto2.test/novel/1.html',
    status: 200,
    charset: 'utf-8',
  });
  const r = await src.getToc({ bookUrl: 'https://auto2.test/novel/1.html', name: '测试书' });
  check('目录兜底：HTML 章节链接自动收集', r.chapters.length === 6 && r.chapters[0].url === 'https://auto2.test/read/100.html', JSON.stringify(r.logs));
}

// 正文：抽样前 4 章（同时为 UI 冒烟预热 fixture：第 0/1/2 章会被点到）
let preview = '';
for (const i of [0, 1, 2, 3]) {
  const ch = toc.chapters[i];
  if (!ch) continue;
  const c = await g.getContent(bi.info, ch);
  check(`正文[${i}] > 500 字符`, c.content.length > 500, `got ${c.content.length}`);
  if (i === 3) preview = c.content;
}
console.log('  第4章预览:', preview.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 100));

console.log('\n[3] Books to Scrape 练习场（发现→详情）');
const ts = samples[1];
const ex = await ts.explore(1);
check('发现 20 条', ex.list.length === 20, `got ${ex.list.length}`);
check('书名正确', ex.list[0].name === 'A Light in the Attic', ex.list[0].name);
check('bookUrl 绝对地址', ex.list[0].bookUrl.startsWith('https://books.toscrape.com/catalogue/'), ex.list[0].bookUrl);
check('封面相对路径已解析', /^https:\/\/books\.toscrape\.com\/media\//.test(ex.list[0].coverUrl || ''), ex.list[0].coverUrl);

const tbi = await ts.getBookInfo(ex.list[0]);
check('详情书名', tbi.info.name === 'A Light in the Attic', tbi.info.name);
check('详情简介 > 50 字', (tbi.info.intro || '').length > 50, tbi.info.intro?.slice(0, 60));
check('详情封面', /media\/cache/.test(tbi.info.coverUrl || ''), tbi.info.coverUrl);

/* ================= 4. 替换净化：scope 分流 ================= */
console.log('\n[4] 替换净化规则（content / title 作用域）');
{
  const { state, purifyContent, purifyTitle } = await import('../web/js/ui/app.js');
  const rule = (pattern, replacement, scope, isRegex = false, enabled = true) => ({ id: 'r' + Math.random(), pattern, replacement, isRegex, scope, enabled });
  state.replaceRules = [
    rule('本站域名', '', 'content'),
    rule('（手机版）', '', 'title'),
    rule('\\d+ 人在看', '热度', 'title', true),
    rule('已禁用', 'X', 'content', false, false),
  ];
  check('净化正文：content 规则生效', purifyContent('<p>内容来自本站域名</p>') === '<p>内容来自</p>', purifyContent('<p>内容来自本站域名</p>'));
  check('净化正文：title 规则不串到正文', purifyContent('<p>第三章（手机版）</p>') === '<p>第三章（手机版）</p>');
  check('净化标题：title 规则生效', purifyTitle('第三章（手机版）') === '第三章', purifyTitle('第三章（手机版）'));
  check('净化标题：title 正则生效', purifyTitle('热门 1234 人在看') === '热门 热度', purifyTitle('热门 1234 人在看'));
  check('净化标题：content 规则不串到标题', purifyTitle('内容来自本站域名') === '内容来自本站域名');
  check('净化：禁用规则被跳过', purifyContent('已禁用') === '已禁用' && purifyTitle('已禁用') === '已禁用');
  state.replaceRules = [];
  check('净化：旧数据缺 scope 按 content 处理', (() => {
    state.replaceRules = [{ id: 'old', pattern: '旧规则', replacement: '', isRegex: false }];
    const a = purifyContent('旧规则内容');
    const b = purifyTitle('旧规则标题');
    state.replaceRules = [];
    return a === '内容' && b === '旧规则标题';
  })());
}

/* ================= 4.5 内置净化规则库 ================= */
console.log('\n[4.5] 内置净化规则库（lib/builtin-purify.js）');
{
  const { purifyContent, renderChapterHtml } = await import('../web/js/ui/app.js');
  // ① 标题粘在段首（截图案例）
  check('内置：标题粘在段首被去除', purifyContent(
    '<p>第四十三章 我也是这么想的 那个无数人都想知道的答案就此揭开。</p>',
    '第四十三章 我也是这么想的'
  ) === '<p>那个无数人都想知道的答案就此揭开。</p>', purifyContent(
    '<p>第四十三章 我也是这么想的 那个无数人都想知道的答案就此揭开。</p>',
    '第四十三章 我也是这么想的'
  ));
  // ② 标题独占一段
  check('内置：标题独占一段被去除', purifyContent('<p>第三章 标题</p><p>正文开始</p>', '第三章 标题') === '<p>正文开始</p>', purifyContent('<p>第三章 标题</p><p>正文开始</p>', '第三章 标题'));
  // ③ 纯文本形态（无 p 包裹）；标题后的换行随标题一起清掉
  check('内置：纯文本标题重复被去除', purifyContent('第四章 测试\n正文内容', '第四章 测试') === '正文内容', JSON.stringify(purifyContent('第四章 测试\n正文内容', '第四章 测试')));
  // ④ 短标题（不够具体）不启用，避免误删正文
  check('内置：短标题不启用去重', purifyContent('<p>他说 你好</p>', '他说你好') === '<p>他说 你好</p>', purifyContent('<p>他说 你好</p>', '他说你好'));
  // ⑤ 正文中间出现的同字串不动（只处理开头）
  check('内置：正文中间的同名串不误删', purifyContent(
    '<p>前面正常。</p><p>第四十三章 我也是这么想的 后面</p>',
    '第四十三章 我也是这么想的'
  ) === '<p>前面正常。</p><p>第四十三章 我也是这么想的 后面</p>', purifyContent(
    '<p>前面正常。</p><p>第四十三章 我也是这么想的 后面</p>',
    '第四十三章 我也是这么想的'
  ));
  // ⑥ 排版后兜底（renderChapterHtml 第二道去重）：标题后 <br> 换行
  check('内置：标题+<br>换行形态被去除', renderChapterHtml('<p>第九章 测试标题<br>正文接着写。</p>', '第九章 测试标题').includes('正文接着写') && !renderChapterHtml('<p>第九章 测试标题<br>正文接着写。</p>', '第九章 测试标题').includes('第九章 测试标题</p><p>第九章'), renderChapterHtml('<p>第九章 测试标题<br>正文接着写。</p>', '第九章 测试标题'));
  // ⑦ 排版后兜底：标题独占段落被整段删除
  {
    const out = renderChapterHtml('<div>第十章 单独标题</div><p>真正的正文。</p>', '第十章 单独标题');
    check('内置：<div>标题独占被整段删除', !out.includes('第十章') && out.includes('真正的正文'), out);
  }
  // ⑧ 排版后兜底：加粗包裹的标题被剥离
  {
    const out = renderChapterHtml('<p><strong>第十一章 加粗标题</strong>正文紧跟其后。</p>', '第十一章 加粗标题');
    check('内置：加粗标题被剥离', !out.includes('第十一章') && out.includes('正文紧跟其后'), out);
  }
  // ⑨ 内置正则：本章完
  check('内置：删除「（本章完）」', purifyContent('<p>正文结束（本章完）</p>') === '<p>正文结束</p>', purifyContent('<p>正文结束（本章完）</p>'));
  // ⑩ 用户自定义规则仍在其后生效
  const { state } = await import('../web/js/ui/app.js');
  state.replaceRules = [{ id: 'u1', pattern: '广告', replacement: '', isRegex: false }];
  check('内置+自定义：先后都生效', purifyContent('<p>广告（本章完）</p>') === '<p></p>', purifyContent('<p>广告（本章完）</p>'));
  state.replaceRules = [];
}

/* ================= 汇总 ================= */
console.log(`\n结果: ${pass} 通过, ${fail} 失败`);
process.exit(fail ? 1 : 0);
