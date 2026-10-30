/**
 * zip.js — 最小 ZIP 读取器（EPUB 用）：中央目录 + 本地头 + deflate-raw 解压。
 * 零依赖；deflate 走 DecompressionStream('deflate-raw')（Chrome/WebView 103+、Node 18+），
 * stored 直接切片。返回 Map<文件名, Uint8Array>。
 */

const EOCD_SIG = 0x06054b50; // PK\x05\x06
const CDH_SIG = 0x02014b50; // PK\x01\x02
const LFH_SIG = 0x04034b50; // PK\x03\x04

class ZipError extends Error {}

function u16(b, o) {
  return b[o] | (b[o + 1] << 8);
}
function u32(b, o) {
  return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0;
}

/** 从尾部向前找 EOCD（注释最长 65555 字节） */
function findEOCD(b) {
  const min = Math.max(0, b.length - 22 - 65535);
  for (let i = b.length - 22; i >= min; i--) {
    if (u32(b, i) === EOCD_SIG) return i;
  }
  throw new ZipError('不是有效的 ZIP/EPUB 文件（未找到结尾记录）');
}

async function inflateRaw(data) {
  if (typeof DecompressionStream === 'undefined') {
    throw new ZipError('当前环境不支持 EPUB 解压（需较新的系统 WebView）');
  }
  const ds = new DecompressionStream('deflate-raw');
  const stream = new Blob([data]).stream().pipeThrough(ds);
  const buf = await new Response(stream).arrayBuffer();
  return new Uint8Array(buf);
}

/**
 * @param {ArrayBuffer|Uint8Array} input
 * @returns {Promise<Map<string, Uint8Array>>}
 */
export async function unzip(input) {
  const b = input instanceof Uint8Array ? input : new Uint8Array(input);
  const eocd = findEOCD(b);
  const cdSize = u32(b, eocd + 12);
  const cdOffset = u32(b, eocd + 16);
  const count = u16(b, eocd + 10);
  const out = new Map();
  let p = cdOffset;
  for (let i = 0; i < count; i++) {
    if (u32(b, p) !== CDH_SIG) throw new ZipError('ZIP 中央目录损坏');
    const method = u16(b, p + 10);
    const compSize = u32(b, p + 20);
    const nameLen = u16(b, p + 28);
    const extraLen = u16(b, p + 30);
    const commentLen = u16(b, p + 32);
    const lho = u32(b, p + 42);
    const name = new TextDecoder().decode(b.subarray(p + 46, p + 46 + nameLen));
    p += 46 + nameLen + extraLen + commentLen;

    if (name.endsWith('/')) continue; // 目录项
    if (u32(b, lho) !== LFH_SIG) throw new ZipError('ZIP 本地头损坏：' + name);
    const lNameLen = u16(b, lho + 26);
    const lExtraLen = u16(b, lho + 28);
    const dataStart = lho + 30 + lNameLen + lExtraLen;
    const comp = b.subarray(dataStart, dataStart + compSize);
    if (method === 0) out.set(name, comp);
    else if (method === 8) out.set(name, await inflateRaw(comp));
    // 其他压缩方法（bzip2/lzma 等）跳过
  }
  if (!out.size) throw new ZipError('ZIP 内没有文件');
  return out;
}
