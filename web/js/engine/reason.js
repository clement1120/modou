/**
 * reason.js —— 所有风控/失败 reason 的唯一生成点（规约：error.message 不出现任何数字）。
 *
 * 为什么收敛到这里：0.9.30 用 Java 侧 verifyBlockedReasonContract() 事后抽检「样例字符串」，
 * 但样例会腐化——新增风控文案时没有任何机制强制同步样例。根本修法是把生成点收口到本模块，
 * 每次构造 reason 都过一遍数字断言，在源头拦截，比事后抽检可靠一个量级。
 *
 * 数字断言：
 *   - debug 构建（window.__MODOU_DEBUG__ 为真）→ 抛 Error，开发期立刻暴露；
 *   - 否则 → console.warn，release 不崩溃。
 *
 * 注意：status 字段（r.status / lastStatus）照常带数字供 UI 展示，只是 error/message 文本不含数字。
 *
 * Java 侧 SourceHealth.verifyBlockedReasonContract() 作为第二道网保留：
 * 新增任何风控文案，必须同步在本模块实现 + 在 Java 侧自检样例里加一条，否则半年后自检必腐化。
 */

const DEBUG = (typeof window !== 'undefined' && window.__MODOU_DEBUG__) === true;

function assertNoDigits(reason) {
  if (/\d/.test(reason)) {
    if (DEBUG) throw new Error('[reason 契约] 含数字，违反规约: ' + reason);
    try { console.warn('[reason 契约] 含数字:', reason); } catch (_) {}
  }
}

/** 风控/挑战页命中：只说明「当前链路被拦截」，绝不意味整源失效 */
export function blockedReason(label) {
  const r = '风控拦截：' + (label || '未知挑战页');
  assertNoDigits(r);
  return r;
}

/** 规则执行异常（正则回溯 / XPath 错误 / 字段脚本抛错）—— 归灰、不进删除清单 */
export function jsErrorReason(detail) {
  const r = '规则执行异常：' + (detail || '脚本执行失败');
  assertNoDigits(r);
  return r;
}

/** 网络层失败（超时 / DNS / 4xx / 5xx / 连接失败）—— 归灰、不进删除清单 */
export function netReason(detail) {
  const r = detail || '网络请求失败';
  assertNoDigits(r);
  return r;
}

/** 规则层未匹配（请求 2xx 但解析 0 条）—— 归红、进删除清单 */
export function ruleMissReason(detail) {
  const r = '规则未匹配：' + (detail || '页面结构可能已变更');
  assertNoDigits(r);
  return r;
}
