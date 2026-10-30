/**
 * jsonpath.js — 极简 JSONPath（覆盖 Legado 书源常用子集）
 * 支持: $  .key  ['key']  [n]  [*]  .*  [a:b]  ..key  [?(@.k=='v')]  [?(@.k)]  [?(@.k>1)]
 * 始终返回数组（Legado 语义：单值规则由调用方取 [0]）。
 */

function matchBracket(p, i) {
  let depth = 0;
  let q = null;
  for (let j = i; j < p.length; j++) {
    const c = p[j];
    if (q) {
      if (c === q) q = null;
      continue;
    }
    if (c === "'" || c === '"') q = c;
    else if (c === '[') depth++;
    else if (c === ']') {
      depth--;
      if (depth === 0) return j;
    }
  }
  return p.length - 1;
}

function stripQuotes(s) {
  const t = s.trim();
  if ((t.startsWith("'") && t.endsWith("'")) || (t.startsWith('"') && t.endsWith('"'))) {
    return t.slice(1, -1);
  }
  return t;
}

function tokenize(path) {
  let p = String(path).trim();
  if (p.startsWith('$')) p = p.slice(1);
  const steps = [];
  let i = 0;
  while (i < p.length) {
    const c = p[i];
    if (c === '.') {
      if (p[i + 1] === '.') {
        i += 2;
        const m = /^[A-Za-z_$][\w$]*/.exec(p.slice(i));
        if (m) {
          steps.push({ t: 'rec', key: m[0] });
          i += m[0].length;
        } else {
          steps.push({ t: 'rec' });
        }
        continue;
      }
      if (p[i + 1] === '*') {
        steps.push({ t: 'wild' });
        i += 2;
        continue;
      }
      i++;
      const m = /^[A-Za-z_$][\w$-]*/.exec(p.slice(i));
      if (m) {
        steps.push({ t: 'key', key: m[0] });
        i += m[0].length;
      }
      continue;
    }
    if (c === '[') {
      const end = matchBracket(p, i);
      const inner = p.slice(i + 1, end).trim();
      i = end + 1;
      if (inner === '*' || inner === '') steps.push({ t: 'wild' });
      else if (/^-?\d+$/.test(inner)) steps.push({ t: 'idx', idx: Number(inner) });
      else if (inner.includes(':')) {
        const seg = inner.split(':').map((s) => (s.trim() === '' ? undefined : Number(s)));
        steps.push({ t: 'slice', a: seg[0], b: seg[1], c: seg[2] });
      } else if (inner.startsWith('?')) steps.push({ t: 'filter', expr: inner });
      else steps.push({ t: 'key', key: stripQuotes(inner) });
      continue;
    }
    // 容忍裸 key（如 data.title）
    const m = /^[A-Za-z_$][\w$-]*/.exec(p.slice(i));
    if (m) {
      steps.push({ t: 'key', key: m[0] });
      i += m[0].length;
    } else i++;
  }
  return steps;
}

function getProp(obj, prop) {
  if (obj == null) return undefined;
  return obj[prop];
}

function matchFilter(item, expr) {
  // 支持: @.k  @['k']  @.k=='v'  @.k!='v'  @.k>1 @.k<1 @.k>=1 @.k<=1 ，以及 && / ||
  const orParts = expr.replace(/^\?\(/, '').replace(/\)$/, '').split('||');
  return orParts.some((orPart) => {
    const andParts = orPart.split('&&');
    return andParts.every((cond) => {
      const m = /^@\.?\[?['"]?([\w$.-]+)['"]?\]?\s*(==|!=|>=|<=|>|<)?\s*(.*)$/.exec(cond.trim());
      if (!m) return false;
      const v = getProp(item, m[1]);
      const op = m[2];
      if (!op) return v !== undefined && v !== null && v !== '';
      let rhs = m[3].trim();
      if (/^'.*'$/.test(rhs) || /^".*"$/.test(rhs)) rhs = rhs.slice(1, -1);
      else if (/^-?\d+(\.\d+)?$/.test(rhs)) rhs = Number(rhs);
      switch (op) {
        case '==': return String(v) === String(rhs);
        case '!=': return String(v) !== String(rhs);
        case '>': return Number(v) > Number(rhs);
        case '<': return Number(v) < Number(rhs);
        case '>=': return Number(v) >= Number(rhs);
        case '<=': return Number(v) <= Number(rhs);
        default: return false;
      }
    });
  });
}

function collectRec(v, key, out) {
  if (v == null || typeof v !== 'object') return;
  if (key) {
    if (Array.isArray(v)) {
      if (v[key] !== undefined) out.push(v[key]);
      v.forEach((it) => collectRec(it, key, out));
    } else {
      if (v[key] !== undefined) out.push(v[key]);
      Object.values(v).forEach((it) => collectRec(it, key, out));
    }
  } else {
    (Array.isArray(v) ? v : Object.values(v)).forEach((it) => collectRec(it, key, out));
  }
}

export function jsonPath(obj, path) {
  const steps = tokenize(path);
  let cur = [obj];
  for (const s of steps) {
    const next = [];
    for (const v of cur) {
      if (v == null) continue;
      switch (s.t) {
        case 'key':
          if (typeof v === 'object' && v[s.key] !== undefined) next.push(v[s.key]);
          break;
        case 'wild':
          if (Array.isArray(v)) next.push(...v);
          else if (typeof v === 'object') next.push(...Object.values(v));
          break;
        case 'idx': {
          if (Array.isArray(v)) {
            const i = s.idx < 0 ? v.length + s.idx : s.idx;
            if (i >= 0 && i < v.length) next.push(v[i]);
          } else if (typeof v === 'object') {
            const ks = Object.keys(v);
            const i = s.idx < 0 ? ks.length + s.idx : s.idx;
            if (ks[i] !== undefined) next.push(v[ks[i]]);
          }
          break;
        }
        case 'slice':
          if (Array.isArray(v)) next.push(...v.slice(s.a, s.b));
          break;
        case 'rec':
          collectRec(v, s.key, next);
          break;
        case 'filter':
          if (Array.isArray(v)) {
            for (const it of v) if (matchFilter(it, s.expr)) next.push(it);
          } else if (typeof v === 'object' && matchFilter(v, s.expr)) next.push(v);
          break;
      }
    }
    cur = next;
  }
  return cur;
}
