/**
 * tts.js — 章节朗读：双后端队列 + 段落高亮
 *
 * 后端抽象：
 *   - App 壳内（window.ModouBridge 存在）：走 Android TextToSpeech，逐句 utteranceId 回调推进
 *   - 桌面浏览器：走 Web Speech API（speechSynthesis）逐句 onend 链式推进
 * 两条路径共用同一套队列/暂停/恢复状态机，上层 reader.js 无感知。
 *
 * 设计要点：
 * - 逐句入队（而非整章一个 utterance），翻句/暂停才够灵敏
 * - Android 引擎初始化是异步的：状态由原生回调 __modouTtsState 推进，
 *   上层先 ttsPrepare() 等待就绪，不能拿 available() 一次判定就报「没有语音」
 * - generation 令牌防护 cancel() 的异步回调，避免 ttsNext/ttsPrev 双重推进
 * - speechSynthesis 存在但语音列表为空的环境（部分无头/精简 Linux）由调用方
 *   先查 ttsHasVoice() 降级，不抛异常
 */

const supported =
  typeof speechSynthesis !== 'undefined' && typeof SpeechSynthesisUtterance !== 'undefined';

/** App 壳桥（MainActivity 注入）；桌面浏览器为 null */
const bridge =
  typeof window !== 'undefined' && window.ModouBridge && typeof window.ModouBridge.ttsAvailable === 'function'
    ? window.ModouBridge
    : null;

let voices = [];
let queue = [];
let qi = 0;
let gen = 0;
let playing = false;
let paused = false;
let rate = 1;
let onSentence = null;
let onEnd = null;
let onError = null;

if (supported) {
  const load = () => {
    voices = speechSynthesis.getVoices() || [];
  };
  load();
  speechSynthesis.addEventListener?.('voiceschanged', load);
}

/* ---------- App 桥：引擎状态 ---------- */
/** Android TextToSpeech 绑定引擎服务要几百毫秒，冷启动首点必然还在 init */
let bridgeState = '';
let stateWaiters = [];

function readBridgeState() {
  if (!bridge) return '';
  try {
    if (typeof bridge.ttsState === 'function') return String(bridge.ttsState() || 'init');
    return bridge.ttsAvailable() ? 'ready' : 'init';
  } catch (e) {
    return 'init';
  }
}

function setBridgeState(s) {
  bridgeState = s;
  const waiters = stateWaiters;
  stateWaiters = [];
  for (const fn of waiters) {
    try {
      fn(s);
    } catch (e) {
      /* 单个等待者异常不影响其它 */
    }
  }
}

function waitBridgeState(pred, timeout) {
  if (!bridge) return Promise.resolve('');
  if (pred(bridgeState)) return Promise.resolve(bridgeState);
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      stateWaiters = stateWaiters.filter((x) => x !== fn);
      resolve(bridgeState);
    }, timeout);
    const fn = (s) => {
      if (!pred(s)) {
        stateWaiters.push(fn);
        return;
      }
      clearTimeout(timer);
      resolve(s);
    };
    stateWaiters.push(fn);
  });
}

if (bridge) {
  bridgeState = readBridgeState();
  window.__modouTtsState = (s) => {
    setBridgeState(String(s || 'init'));
  };
  /* Android TTS 的单句完成回调（utteranceId === 队列序号） */
  window.__modouTtsDone = (id) => {
    if (!playing || paused) return;
    if (id !== qi) return; // 过期回调（stop 后残留）
    qi++;
    if (qi >= queue.length) return finish();
    onSentence?.(qi, queue[qi].el);
    speakCurrent();
  };
}

export function ttsSupported() {
  return supported || !!bridge;
}

/** 当前是否真有用得上的语音（App 内看引擎状态；浏览器看语音列表） */
export function ttsHasVoice() {
  if (bridge) return bridgeState === 'ready';
  return supported && voices.length > 0;
}

/** 当前状态：ready / init / no_engine / no_data / no_voice / unsupported */
export function ttsState() {
  if (bridge) return bridgeState || 'init';
  if (!supported) return 'unsupported';
  return voices.length ? 'ready' : 'no_voice';
}

/**
 * 等到引擎可用（App 内可能正在 init）。
 * 处于失败态时会重建一次引擎——用户可能刚在系统里装完语音包。
 * @returns {Promise<{ok: boolean, state: string}>}
 */
export async function ttsPrepare(opts = {}) {
  const timeout = opts.timeout ?? 2600;
  if (!bridge) {
    if (!supported) return { ok: false, state: 'unsupported' };
    if (!voices.length) {
      try {
        voices = speechSynthesis.getVoices() || voices;
      } catch (e) {
        /* 忽略 */
      }
    }
    return { ok: voices.length > 0, state: voices.length ? 'ready' : 'no_voice' };
  }
  if (bridgeState === 'ready') return { ok: true, state: 'ready' };

  if (!bridgeState || bridgeState === 'init') {
    await waitBridgeState((s) => s !== 'init', timeout);
    if (bridgeState === 'ready') return { ok: true, state: 'ready' };
  }

  if (bridgeState === 'no_engine' || bridgeState === 'no_data') {
    try {
      bridge.ttsRetry?.();
    } catch (e) {
      /* 忽略 */
    }
    await waitBridgeState((s) => s !== 'init', timeout);
  }
  return { ok: bridgeState === 'ready', state: bridgeState || 'no_engine' };
}

/** 打开系统「文字转语音（TTS）」设置页（仅 App 壳内可用） */
export function ttsOpenSettings() {
  if (!bridge || typeof bridge.ttsOpenSettings !== 'function') return false;
  try {
    return bridge.ttsOpenSettings() === true;
  } catch (e) {
    return false;
  }
}

/** 系统默认语音引擎包名（提示里带上，便于定位） */
export function ttsEngineName() {
  if (!bridge || typeof bridge.ttsEngineName !== 'function') return '';
  try {
    return String(bridge.ttsEngineName() || '');
  } catch (e) {
    return '';
  }
}

/**
 * 把段落切成句子队列。
 * 段落可以是字符串、{text, el}，或直接是 DOM 元素（阅读页传的就是元素）——
 * 早先只认 {text} 导致元素取不到文本、队列恒为空，朗读点了没反应。
 * @param startIndex 从第几段开始（「从本页开始朗读」跳过前面的段落）
 */
export function ttsBuildQueue(paragraphs, startIndex = 0) {
  const q = [];
  for (let pi = Math.max(0, startIndex | 0); pi < paragraphs.length; pi++) {
    const p = paragraphs[pi];
    let text = '';
    let el = null;
    if (typeof p === 'string') {
      text = p;
    } else if (p && typeof p.text === 'string') {
      text = p.text;
      el = p.el || null;
    } else if (p && typeof p.textContent === 'string') {
      text = p.textContent;
      el = p;
    } else {
      continue;
    }
    text = text.replace(/\s+/g, ' ').trim();
    if (!text) continue;
    for (const s of splitSentences(text)) q.push({ text: s, el });
  }
  return q;
}

/** 按中文标点断句；超长句按逗号二次切分，避免单句过长无法中断 */
function splitSentences(text) {
  const parts = text
    .split(/(?<=[。！？；…])/)
    .map((s) => s.trim())
    .filter(Boolean);
  const out = [];
  for (let s of parts) {
    while (s.length > 90) {
      const head = s.slice(0, 90);
      const cut = head.search(/[，、][^，、]*$/);
      const at = cut > 30 ? cut + 1 : 90;
      out.push(s.slice(0, at));
      s = s.slice(at);
    }
    if (s) out.push(s);
  }
  return out;
}

function pickVoice() {
  return voices.find((v) => /^zh/i.test(v.lang || '')) || voices.find((v) => /zh|中文|Chinese/i.test((v.lang || '') + (v.name || ''))) || voices[0] || null;
}

/** 统一入口：按当前后端朗读第 qi 句 */
function speakCurrent() {
  if (!playing || qi >= queue.length) return;
  const item = queue[qi];
  onSentence?.(qi, item.el);

  if (bridge) {
    // Android TTS：单句模式，完成经 __modouTtsDone 推进
    if (bridgeState !== 'ready') return abort('engine_lost');
    let ok = false;
    try {
      ok = bridge.ttsSpeak(item.text, rate, qi) !== false;
    } catch (e) {
      ok = false;
    }
    // 入队失败就没有回调来推进队列，必须自己收尾，否则卡在「正在朗读」
    if (!ok) abort('speak_failed');
    return;
  }

  const g = ++gen;
  const u = new SpeechSynthesisUtterance(item.text);
  u.rate = rate;
  const v = pickVoice();
  if (v) u.voice = v;
  u.onend = () => {
    if (g !== gen || !playing) return;
    advance();
  };
  u.onerror = () => {
    if (g !== gen || !playing) return;
    advance();
  };
  speechSynthesis.speak(u);
}

function advance() {
  qi++;
  if (qi >= queue.length) return finish();
  onSentence?.(qi, queue[qi].el);
  speakCurrent();
}

function finish() {
  playing = false;
  paused = false;
  onSentence?.(null, null);
  onEnd?.();
}

/** 中途失败（引擎掉线 / 单句入队失败）：收尾并上报，避免队列静默卡死 */
function abort(reason) {
  const cb = onError;
  ttsStop(true);
  cb?.(reason);
}

/**
 * 开始朗读。paragraphs 为段落数组（字符串或 {text, el}）。
 * opts: { rate, startIndex, onSentence(i, el|null), onEnd(), onError(reason) }
 * startIndex 为起始段落下标（「从本页开始朗读」）；返回 false 表示无可朗读内容。
 */
export function ttsStart(paragraphs, opts = {}) {
  if (!supported && !bridge) return false;
  // 引擎还没就绪（调用方应先 await ttsPrepare）；这里兜底，避免「起了个空队列」
  if (bridge && bridgeState !== 'ready') return false;
  ttsStop(true);
  onSentence = opts.onSentence || null;
  onEnd = opts.onEnd || null;
  onError = opts.onError || null;
  rate = opts.rate || 1;
  queue = ttsBuildQueue(paragraphs, opts.startIndex || 0);
  qi = 0;
  if (!queue.length) return false;
  playing = true;
  paused = false;
  speakCurrent();
  return true;
}

export function ttsPause() {
  if (!playing || paused) return;
  paused = true;
  if (bridge) {
    // Android TTS 无 pause 原语：停掉当前句，恢复时重说本句
    bridge.ttsStop();
  } else if (supported) {
    speechSynthesis.pause();
  }
}

export function ttsResume() {
  if (!playing || !paused) return;
  paused = false;
  if (bridge) {
    speakCurrent(); // 重说当前句（qi 未推进）
  } else if (supported) {
    speechSynthesis.resume();
  }
}

export function ttsIsPaused() {
  return paused;
}

export function ttsNext() {
  if (!playing) return;
  if (bridge) {
    // stop() 后立即 speak() 在部分 Android 版本会被一并 flush，稍候重说本句
    const target = Math.min(queue.length - 1, qi + 1);
    qi = target;
    bridge.ttsStop();
    setTimeout(() => {
      if (playing && !paused && qi === target) speakCurrent();
    }, 80);
  } else {
    gen++; // 作废当前 utterance 的回调
    qi = Math.min(queue.length - 1, qi + 1);
    if (supported) speechSynthesis.cancel();
    speakCurrent();
  }
}

export function ttsPrev() {
  if (!playing) return;
  if (bridge) {
    const target = Math.max(0, qi - 1);
    qi = target;
    bridge.ttsStop();
    setTimeout(() => {
      if (playing && !paused && qi === target) speakCurrent();
    }, 80);
  } else {
    gen++;
    qi = Math.max(0, qi - 1);
    if (supported) speechSynthesis.cancel();
    speakCurrent();
  }
}

/** 修改语速：对下一句生效 */
export function ttsSetRate(r) {
  rate = r;
}

export function ttsCount() {
  return queue.length;
}

/** 停止朗读；silent=true 时不触发 onEnd（路由切换清理用） */
export function ttsStop(silent = false) {
  gen++;
  playing = false;
  paused = false;
  queue = [];
  qi = 0;
  if (bridge) bridge.ttsStop();
  if (supported) speechSynthesis.cancel();
  if (!silent) {
    onSentence?.(null, null);
    onEnd?.();
  }
  onSentence = null;
  onEnd = null;
  onError = null;
}
