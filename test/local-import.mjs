/**
 * local-import.mjs — 本地导入解析模块单测（Node，无浏览器依赖）
 * 覆盖：txt.js 编码探测/智能分章/目录剔除、zip.js 打包-解包、epub.js 全链路
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { parseTxt, splitTxtChapters, decodeText, guessMetaFromName, isTitleLine, textToHtml } from '../web/js/lib/txt.js';
import { unzip } from '../web/js/lib/zip.js';
import { parseEpub } from '../web/js/lib/epub.js';

const here = dirname(fileURLToPath(import.meta.url));
let pass = 0;
let fail = 0;
const check = (name, ok, detail = '') => {
  if (ok) {
    pass++;
    console.log('  ✓', name);
  } else {
    fail++;
    console.log('  ✗', name, '—', detail);
  }
};

/* ---------- txt.js ---------- */

// UTF-8 + 标准分章
{
  const text = `简介：这是一个测试。

第一章 起点
少年站在山门前。
“这就是修仙么？”

第二章 风云
风云变色。
他踏入了宗门。

尾声 归宿
故事落幕。`;
  const buf = new TextEncoder().encode(text);
  const r = parseTxt(buf, '测试小说.txt');
  const names = r.chapters.map((c) => c.name);
  check('TXT：标准分章 3 章（短简介并入首章）', names.length === 3 && names[0] === '第一章 起点' && names[2] === '尾声 归宿', JSON.stringify(names));
  check('TXT：首章前置简介并入', r.chapters[0].html.includes('这是一个测试'), r.chapters[0].html.slice(0, 60));
  check('TXT：正文含段落', r.chapters[0].html.includes('<p>少年站在山门前。</p>'), r.chapters[0].html.slice(0, 80));
  check('TXT：HTML 转义', textToHtml('a & b < c').includes('a &amp; b &lt; c'));
  check('TXT：简介 intro 截断', r.intro.length <= 120);
}

// GBK 编码（fixture 由 Python 生成）
{
  const buf = readFileSync(join(here, 'fixtures', 'local', 'gbk-sample.txt'));
  const t = decodeText(buf);
  check('TXT：GBK 编码探测', t.includes('编码测试') && !t.includes('\uFFFD'), t.slice(0, 40));
  const r = parseTxt(buf, 'gbk-sample.txt');
  check('TXT：GBK 分章', r.chapters.length === 2 && r.chapters[0].name === '第一章 乱码', r.chapters.map((c) => c.name).join(','));
}

// 目录区剔除
{
  const text = `目录
第一章 甲
第二章 乙
第三章 丙
第四章 丁

第一章 甲
正文甲一。
正文甲二。

第二章 乙
正文乙一。`;
  const r = splitTxtChapters(text);
  const names = r.map((c) => c.name);
  check('TXT：目录区剔除', names.length === 2 && names[0] === '第一章 甲', JSON.stringify(names));
}

// 带标题的阿拉伯数字章节：不能被切成「按字数聚合」的块
{
  const text = Array.from({ length: 6 }, (_, i) => `第${i + 1}章 少年不识愁滋味之第${i + 1}回\n正文内容第${i + 1}段，` + '字'.repeat(600)).join('\n');
  const r = splitTxtChapters(text);
  check('TXT：阿拉伯数字 + 长标题正常分章', r.length === 6 && r[5].name.startsWith('第6章'), `${r.length} 章 / ${r[0].name}`);
}

// 无分章兜底
{
  const text = Array.from({ length: 40 }, (_, i) => `第${i}段内容，用来填充字数。`).join('\n');
  const r = splitTxtChapters(text);
  check('TXT：无分章按长度兜底', r.length >= 1 && r[0].name.startsWith('第'), `${r.length} 章`);
}

// 标题行误判防御
{
  check('TXT：正文句不当标题', !isTitleLine('第三章讲的是一个关于勇气与爱的长篇故事，非常精彩'));
  check('TXT：正常标题识别', isTitleLine('第一章 起点') && isTitleLine('第10章') && isTitleLine('序章') && isTitleLine('番外篇 后日谈'));
  check('TXT：过长行不当标题', !isTitleLine('第一章 ' + 'x'.repeat(50)));
  // v0.6.43 回归：带标题的章节（章号 + 分隔符 + 长标题）必须识别。
  // 之前先 trim 再判断分隔符，长标题一律被拒 → 整本书按 5000 字硬切，目录全乱。
  check(
    'TXT：带长标题的章节可识别',
    isTitleLine('第1章 少年不识愁滋味') && isTitleLine('第12章 风起于青萍之末') && isTitleLine('第一章 甲'),
    ''
  );
}

// 文件名元信息
{
  const m = guessMetaFromName('《斗破苍穹》天蚕土豆.txt');
  check('TXT：书名作者识别', m.name === '斗破苍穹' && m.author === '天蚕土豆', JSON.stringify(m));
  const m2 = guessMetaFromName('普通书名.txt');
  check('TXT：无书名号回退', m2.name === '普通书名' && m2.author === '');
}

/* ---------- zip.js ---------- */

/** 构造 ZIP：stored + deflate-raw 两种压缩方法 */
async function makeZip(entries) {
  const enc = new TextEncoder();
  const chunks = [];
  const central = [];
  let offset = 0;
  const crcTable = [];
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crcTable[n] = c >>> 0;
  }
  const crc32 = (bytes) => {
    let c = 0xffffffff;
    for (const x of bytes) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const u16 = (v) => new Uint8Array([v & 0xff, (v >> 8) & 0xff]);
  const u32 = (v) => new Uint8Array([v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >>> 24) & 0xff]);
  for (const [name, content, method] of entries) {
    const nameB = enc.encode(name);
    const raw = typeof content === 'string' ? enc.encode(content) : content;
    let data = raw;
    if (method === 8) {
      const cs = new CompressionStream('deflate-raw');
      const stream = new Blob([raw]).stream().pipeThrough(cs);
      data = new Uint8Array(await new Response(stream).arrayBuffer());
    }
    const crc = crc32(raw);
    const lfh = new Uint8Array([...u32(0x04034b50), ...u16(20), ...u16(0), ...u16(method), ...u16(0), ...u16(0), ...u32(crc), ...u32(data.length), ...u32(raw.length), ...u16(nameB.length), ...u16(0), ...nameB]);
    chunks.push(lfh, data);
    central.push(new Uint8Array([...u32(0x02014b50), ...u16(20), ...u16(20), ...u16(0), ...u16(method), ...u16(0), ...u16(0), ...u32(crc), ...u32(data.length), ...u32(raw.length), ...u16(nameB.length), ...u16(0), ...u16(0), ...u16(0), ...u16(0), ...u32(0), ...u32(offset), ...nameB]));
    offset += lfh.length + data.length;
  }
  const cdStart = offset;
  let cdSize = 0;
  for (const c of central) {
    chunks.push(c);
    cdSize += c.length;
  }
  chunks.push(new Uint8Array([...u32(0x06054b50), ...u16(0), ...u16(0), ...u16(central.length), ...u16(central.length), ...u32(cdSize), ...u32(cdStart), ...u16(0)]));
  const total = chunks.reduce((s, c) => s + c.length, 0);
  const out = new Uint8Array(total);
  let p = 0;
  for (const c of chunks) {
    out.set(c, p);
    p += c.length;
  }
  return out;
}

{
  const zip = await makeZip([
    ['a.txt', '你好，世界'.repeat(100), 0], // stored
    ['dir/b.json', JSON.stringify({ x: 1, y: '测试' }), 8], // deflate
    ['empty/', '', 0], // 目录项
  ]);
  const files = await unzip(zip);
  check('ZIP：解压条目数', files.size === 2, `${files.size}`);
  const a = new TextDecoder().decode(files.get('a.txt'));
  check('ZIP：stored 内容', a === '你好，世界'.repeat(100), a.slice(0, 20));
  const b = new TextDecoder().decode(files.get('dir/b.json'));
  check('ZIP：deflate 内容', JSON.parse(b).y === '测试', b);
  let threw = false;
  try {
    await unzip(new TextEncoder().encode('not a zip file at all'));
  } catch (e) {
    threw = true;
  }
  check('ZIP：非 ZIP 报错', threw);
}

/* ---------- epub.js ---------- */

function buildEpub() {
  const container = `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`;
  const opf = `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>测试之书</dc:title><dc:creator>测试作者</dc:creator><dc:language>zh</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="cover-img" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="nav"/><itemref idref="c1"/><itemref idref="c2"/></spine></package>`;
  const nav = `<html><body><nav><h1>目录</h1><p>第一章</p></nav></body></html>`;
  const c1 = `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章 起点</title></head><body><h1>第一章 起点</h1><p>少年站在山门前。</p><p>“这就是修仙么？”</p></body></html>`;
  const c2 = `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第二章 风云</title></head><body><h2>第二章 风云</h2><p>风云变色。</p><br/><p>他踏入宗门。</p></body></html>`;
  // 最小 JPEG（SOI + EOI），只验证二进制封面能解出 dataURL
  const jpg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);
  return makeZip([
    ['mimetype', 'application/epub+zip', 0],
    ['META-INF/container.xml', container, 8],
    ['OEBPS/content.opf', opf, 8],
    ['OEBPS/nav.xhtml', nav, 8],
    ['OEBPS/c1.xhtml', c1, 8],
    ['OEBPS/c2.xhtml', c2, 8],
    ['OEBPS/images/cover.jpg', jpg, 0],
  ]);
}

{
  const epub = await buildEpub();
  const r = await parseEpub(epub);
  check('EPUB：书名作者', r.title === '测试之书' && r.author === '测试作者', `${r.title}/${r.author}`);
  check('EPUB：章节数（跳过 nav）', r.chapters.length === 2, r.chapters.map((c) => c.name).join(','));
  check('EPUB：章节名取自标题', r.chapters[0].name === '第一章 起点' && r.chapters[1].name === '第二章 风云');
  check('EPUB：正文分段', r.chapters[0].html.includes('<p>少年站在山门前。</p>') && r.chapters[1].html.split('<p>').length === 3, r.chapters[1].html);
  check('EPUB：intro', r.intro.includes('少年') || r.intro.includes('风云'), r.intro);
  check('EPUB：封面提取（cover-image → dataURL）', /^data:image\/jpeg;base64,/.test(r.cover || ''), String(r.cover || '').slice(0, 60));
}

// 封面兜底链：manifest 元数据失效时依次降级
// （真实 EPUB 常见：href 带 ../、包根绝对路径、封面只在封面页里、图根本没登记）
const jpgMini = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);
const pngMini = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52]);

function epubOpf({ manifestExtra = '', spineExtra = '', coverHref = 'images/cover.jpg', coverProps = ' properties="cover-image"', metaCover = '' } = {}) {
  return `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>兜底之书</dc:title><dc:creator>作者</dc:creator>${metaCover}</metadata><manifest><item id="cover-img" href="${coverHref}" media-type="image/jpeg"${coverProps}/>${manifestExtra}</manifest><spine>${spineExtra}</spine></package>`;
}

{
  // ① manifest 登记了封面，但 href 是 '../Images/cover.jpg'（相对 OPF 的上级目录）
  const c1 = `<html><body><h1>第一章</h1><p>正文内容足够长。</p></body></html>`;
  const opf = epubOpf({
    coverHref: '../Images/cover.jpg',
    manifestExtra: '<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>',
    spineExtra: '<itemref idref="c1"/>',
  });
  const zip = await makeZip([
    ['mimetype', 'application/epub+zip', 0],
    ['META-INF/container.xml', `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`, 8],
    ['OEBPS/content.opf', opf, 8],
    ['OEBPS/c1.xhtml', c1, 8],
    ['Images/cover.jpg', jpgMini, 0],
  ]);
  const r = await parseEpub(zip).catch((e) => ({ error: e.message }));
  check('EPUB 封面：href 带 ../ 上级目录', !r.error && /^data:image\/jpeg;base64,/.test(r.cover || ''), r.error || String(r.cover).slice(0, 40));
}

{
  // ② href 是包根绝对路径 '/Images/cover.jpg'（不应再拼 OEBPS 前缀）
  const c1 = `<html><body><h1>第一章</h1><p>正文内容足够长。</p></body></html>`;
  const opf = epubOpf({
    coverHref: '/Images/cover.jpg',
    manifestExtra: '<item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>',
    spineExtra: '<itemref idref="c1"/>',
  });
  const zip = await makeZip([
    ['mimetype', 'application/epub+zip', 0],
    ['META-INF/container.xml', `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`, 8],
    ['OEBPS/content.opf', opf, 8],
    ['OEBPS/c1.xhtml', c1, 8],
    ['Images/cover.jpg', jpgMini, 0],
  ]);
  const r = await parseEpub(zip).catch((e) => ({ error: e.message }));
  check('EPUB 封面：href 为包根绝对路径', !r.error && /^data:image\/jpeg;base64,/.test(r.cover || ''), r.error || String(r.cover).slice(0, 40));
}

{
  // ③ manifest 完全没有图片登记；封面只在封面页（只有一张图、被正文过滤）里
  const opf = `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>兜底之书</dc:title></metadata><manifest><item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="cover-page"/><itemref idref="c1"/></spine></package>`;
  const coverPage = `<html><body><div><img src="images/cover.png" alt="cover"/></div></body></html>`;
  const c1 = `<html><body><h1>第一章</h1><p>正文内容足够长。</p></body></html>`;
  const zip = await makeZip([
    ['mimetype', 'application/epub+zip', 0],
    ['META-INF/container.xml', `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`, 8],
    ['OEBPS/content.opf', opf, 8],
    ['OEBPS/cover.xhtml', coverPage, 8],
    ['OEBPS/c1.xhtml', c1, 8],
    ['OEBPS/images/cover.png', pngMini, 0],
  ]);
  const r = await parseEpub(zip).catch((e) => ({ error: e.message }));
  check('EPUB 封面：兜底到封面页首图', !r.error && /^data:image\/png;base64,/.test(r.cover || ''), r.error || String(r.cover).slice(0, 40));
  check('EPUB 封面：封面页不计入正文章节', !r.error && r.chapters.length === 1, r.error || r.chapters.map((c) => c.name).join(','));
}

{
  // ④ 图片既没登记也没被任何文档引用，只在 zip 里（文件名含 cover）
  const opf = `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>兜底之书</dc:title></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>`;
  const c1 = `<html><body><h1>第一章</h1><p>正文内容足够长。</p></body></html>`;
  const zip = await makeZip([
    ['mimetype', 'application/epub+zip', 0],
    ['META-INF/container.xml', `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`, 8],
    ['OEBPS/content.opf', opf, 8],
    ['OEBPS/c1.xhtml', c1, 8],
    ['OEBPS/Images/Cover.jpeg', jpgMini, 0],
  ]);
  const r = await parseEpub(zip).catch((e) => ({ error: e.message }));
  check('EPUB 封面：兜底扫描 zip 内 cover 图片', !r.error && /^data:image\/jpeg;base64,/.test(r.cover || ''), r.error || String(r.cover).slice(0, 40));
}

{
  // ⑤ 正文插图：<img src> 相对路径解析成 dataURL 并保留在章节 html 里（独立 .img-p 段）
  const opf = `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>插图之书</dc:title></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="pic" href="img/pic.png" media-type="image/png"/></manifest><spine><itemref idref="c1"/></spine></package>`;
  const c1 = `<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第一章</h1><p>图前的段落。</p><p><img src="img/pic.png" alt=""/></p><p>图后的段落。</p></body></html>`;
  const zip = await makeZip([
    ['mimetype', 'application/epub+zip', 0],
    ['META-INF/container.xml', `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`, 8],
    ['OEBPS/content.opf', opf, 8],
    ['OEBPS/c1.xhtml', c1, 8],
    ['OEBPS/img/pic.png', pngMini, 0],
  ]);
  const r = await parseEpub(zip).catch((e) => ({ error: e.message }));
  const html = (r.chapters[0] || {}).html || '';
  check('EPUB 插图：正文 img 保留为 dataURL', !r.error && /<p class="img-p"><img src="data:image\/png;base64,/.test(html), html.slice(0, 120));
  check('EPUB 插图：文字段落完整（图前/图后）', html.includes('<p>图前的段落。</p>') && html.includes('<p>图后的段落。</p>'), html);
}

{
  // ⑥ SVG <image xlink:href> 插图也能解析（EPUB 常见 svg 包裹）
  const opf = `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>SVG 插图</dc:title></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>`;
  const c1 = `<html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink"><body><h1>第一章</h1><svg><image xlink:href="pic.jpg"/></svg><p>svg 图后正文。</p></body></html>`;
  const zip = await makeZip([
    ['mimetype', 'application/epub+zip', 0],
    ['META-INF/container.xml', `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`, 8],
    ['OEBPS/content.opf', opf, 8],
    ['OEBPS/c1.xhtml', c1, 8],
    ['OEBPS/pic.jpg', jpgMini, 0],
  ]);
  const r = await parseEpub(zip).catch((e) => ({ error: e.message }));
  const html = (r.chapters[0] || {}).html || '';
  check('EPUB 插图：svg image href 同样保留', !r.error && /data:image\/jpeg;base64,/.test(html), html.slice(0, 120));
}

{
  // ⑦ EPUB2：meta name=cover 指向 HTML 封面页 id（不是图片）——必须跳过继续降级，不能产出裂图 dataURL
  const opf = `<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>EPUB2 之书</dc:title><meta name="cover" content="cover-page"/></metadata><manifest><item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/><item id="cover-img" href="images/cover.jpg" media-type="image/jpeg"/><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="cover-page"/><itemref idref="c1"/></spine></package>`;
  const coverPage = `<html><body><img src="images/cover.jpg"/></body></html>`;
  const c1 = `<html><body><h1>第一章</h1><p>正文内容足够长。</p></body></html>`;
  const zip = await makeZip([
    ['mimetype', 'application/epub+zip', 0],
    ['META-INF/container.xml', `<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`, 8],
    ['OEBPS/content.opf', opf, 8],
    ['OEBPS/cover.xhtml', coverPage, 8],
    ['OEBPS/c1.xhtml', c1, 8],
    ['OEBPS/images/cover.jpg', jpgMini, 0],
  ]);
  const r = await parseEpub(zip).catch((e) => ({ error: e.message }));
  check('EPUB2 封面：meta 指向 HTML 页时降级到真实图片', !r.error && /^data:image\/jpeg;base64,/.test(r.cover || ''), r.error || String(r.cover).slice(0, 60));
}

console.log(`\n本地导入单测结果: ${pass} 通过, ${fail} 失败`);
process.exit(fail ? 1 : 0);
