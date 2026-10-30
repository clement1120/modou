/**
 * replace.js — 替换净化（对齐开源阅读 ReplaceRule 的常用子集）
 *
 * 规则：{ id, pattern, replacement, isRegex, scope: 'content'|'title', enabled }
 * - scope=content：对取回的正文 HTML 按顺序替换（app.js purifyContent）
 * - scope=title  ：对阅读页展示的章节标题替换（app.js purifyTitle）
 * - isRegex      ：pattern 作为正则（/g）使用，否则纯文本替换
 *
 * 阅读页底部菜单「净化」与设置页「正文净化」共用本编辑器；数据同在 settings.replaceRules。
 */
import { state, reloadReplaceRules, toast } from './app.js';
import { settings } from '../db.js';
import { $, $$, h, esc, openSheet } from './dom.js';
import { BUILTIN_RULES } from '../lib/builtin-purify.js';

const SCOPE_LABEL = { content: '正文', title: '标题' };

/** 规范化一条规则（兼容旧数据/JSON 导入的缺字段） */
function normRule(r) {
  return {
    id: r && r.id != null ? String(r.id) : 'r' + Math.random().toString(36).slice(2, 9),
    pattern: String((r && r.pattern) || ''),
    replacement: String((r && r.replacement) || ''),
    isRegex: !!(r && r.isRegex),
    scope: r && r.scope === 'title' ? 'title' : 'content',
    enabled: !(r && r.enabled === false),
  };
}

/** 保存并热更新（不重渲染页面；阅读页下次取章即生效） */
async function commit(rules) {
  state.replaceRules = rules;
  await settings.set('replaceRules', rules);
  await reloadReplaceRules();
}

/** 渲染规则列表（设置页与阅读面板共用）；返回重新绘制函数 */
export function renderRuleList(box, rules, onChange) {
  const paint = () => {
    box.innerHTML = '';
    // 内置规则库（随版本更新、自动生效、不可编辑）；遇到新的通用杂质往库里加即可
    const builtin = h('div', { class: 'replace-builtin' });
    builtin.append(h('p', { class: 'muted small', style: 'margin-bottom:4px' }, h('b', {}, `内置规则库（${BUILTIN_RULES.length} 条，自动生效）`), h('span', { class: 'muted small' }, ' · 随版本更新')));
    for (const r of BUILTIN_RULES) {
      builtin.append(h('p', { class: 'muted small', style: 'margin:2px 0' }, '· ' + (r.name || r.pattern)));
    }
    builtin.append(h('p', { class: 'muted small', style: 'margin-top:4px' }, '正文开头重复章节标题的清理也是内置的（无需配置）。'));
    box.append(builtin);
    if (!rules.length) {
      box.append(h('p', { class: 'muted small' }, '暂无自定义规则。常见用法：去掉「笔趣阁」「www.xxx.com」等广告尾巴。'));
      return;
    }
    rules.forEach((r, i) => {
      const row = h('div', { class: 'replace-row' });
      const sw = h('input', { type: 'checkbox' });
      sw.checked = r.enabled;
      sw.title = '启用/停用';
      sw.onchange = async () => {
        r.enabled = sw.checked;
        await commit(rules);
        onChange && onChange();
      };
      const pat = h('input', { value: r.pattern, placeholder: '匹配内容（如：www.xxx.com）' });
      const rep = h('input', { value: r.replacement, placeholder: '替换为（留空 = 删除）' });
      const save = async () => {
        r.pattern = pat.value;
        r.replacement = rep.value;
        await commit(rules);
        onChange && onChange();
      };
      pat.onchange = save;
      rep.onchange = save;
      // 作用范围：正文 / 标题
      const scope = h('div', { class: 'rr-scope' });
      for (const [v, label] of Object.entries(SCOPE_LABEL)) {
        const b = h('button', { class: r.scope === v ? 'on' : '' }, label);
        b.onclick = async () => {
          r.scope = v;
          await commit(rules);
          paint();
          onChange && onChange();
        };
        scope.append(b);
      }
      const rx = h('input', { type: 'checkbox' });
      rx.checked = r.isRegex;
      rx.onchange = async () => {
        r.isRegex = rx.checked;
        await commit(rules);
        onChange && onChange();
      };
      const ctl = h(
        'div',
        { class: 'replace-ctl' },
        h('label', {}, rx, document.createTextNode('正则')),
        h('button', { class: 'mini', title: '上移', disabled: i === 0 }, '↑'),
        h('button', { class: 'mini', title: '下移', disabled: i === rules.length - 1 }, '↓'),
        h('button', { class: 'mini danger', title: '删除' }, '删')
      );
      const [up, down, del] = ctl.querySelectorAll('button');
      up.onclick = async () => {
        if (i === 0) return;
        [rules[i - 1], rules[i]] = [rules[i], rules[i - 1]];
        await commit(rules);
        paint();
        onChange && onChange();
      };
      down.onclick = async () => {
        if (i === rules.length - 1) return;
        [rules[i + 1], rules[i]] = [rules[i], rules[i + 1]];
        await commit(rules);
        paint();
        onChange && onChange();
      };
      del.onclick = async () => {
        rules.splice(i, 1);
        await commit(rules);
        paint();
        onChange && onChange();
      };
      row.append(h('label', { class: 'switch', title: '启用/停用' }, sw, h('span')));
      row.append(h('div', { class: 'replace-fields' }, pat, rep, scope, ctl));
      box.append(row);
    });
  };
  paint();
  return paint;
}

/**
 * 打开「替换净化」管理面板（阅读页菜单 / 设置页共用）。
 * @param {{onApplied?:Function, prefill?:{pattern:string, replacement?:string, scope?:'content'|'title'}}} opts
 *        onApplied 在规则变化后回调（阅读页可用来重载本章）；
 *        prefill   选词直达：把选中文字预置成一条规则并聚焦「替换为」
 */
export function openReplaceSheet(opts = {}) {
  const rules = (state.replaceRules || []).map(normRule);
  const box = h('div', { class: 'replace-list' });
  const pre = opts.prefill && String(opts.prefill.pattern || '').trim();
  const note = h(
    'p',
    { class: 'muted small' },
    pre
      ? '已把选中文字填成新规则；在「替换为」里留空 = 直接从正文删掉。改动对之后读取的章节生效，当前章可用「更多 → 重新加载本章」立即应用。'
      : '按顺序对正文/标题执行替换；「标题」只改阅读页章题。改动对之后读取的章节生效，当前章可用「更多 → 重新加载本章」立即应用。'
  );
  const add = h('button', { class: 'btn block' }, '＋ 添加规则');
  const repaintList = renderRuleList(box, rules, opts.onApplied);
  // 选词直达：立即落库一条规则（pattern = 选中文字，替换为空 = 删除）
  if (pre) {
    rules.push(
      normRule({
        pattern: String(opts.prefill.pattern).trim(),
        replacement: opts.prefill.replacement || '',
        scope: opts.prefill.scope === 'title' ? 'title' : 'content',
        enabled: true,
      })
    );
    repaintList();
    commit(rules).then(() => opts.onApplied && opts.onApplied());
  }
  add.onclick = async () => {
    rules.push(normRule({}));
    await commit(rules);
    repaintList();
    opts.onApplied && opts.onApplied();
  };
  const body = h('div', { class: 'form' }, note, box, add);
  const sheet = openSheet('替换净化', body);
  if (pre) {
    // 滚到新规则并把光标放进「替换为」——选中即改，不用再找
    const rows = box.querySelectorAll('.replace-row');
    const row = rows[rows.length - 1];
    if (row) {
      try {
        row.scrollIntoView({ block: 'center' });
      } catch (e) {
        /* 忽略 */
      }
      const rep = row.querySelectorAll('.replace-fields input')[1];
      if (rep) setTimeout(() => rep.focus(), 280); // 等面板抬升动画结束
    }
  }
  return sheet;
}
