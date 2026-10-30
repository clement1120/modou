/**
 * settings.js — 设置：正文净化 / 章节缓存 / 数据 / 关于（M5：导入备份 + 阅读统计）
 */
import { state, reloadReplaceRules, getReadStats, toast, confirmBox, saveAppTheme } from './app.js';
import { settings, db } from '../db.js';
import { $, h, openModal, saveFile } from './dom.js';
import { openReplaceSheet } from './replace.js';

export async function renderSettings(view) {
  const curTheme = await settings.get('appTheme', 'light');
  view.innerHTML = `
    <div class="page-head"><div><h1>设置</h1><p class="muted">外观 · 净化 · 缓存 · 数据</p></div></div>

    <div class="card">
      <div class="sec-head"><h3>外观</h3><span class="muted small">整 App 配色</span></div>
      <p class="muted small">夜间模式对所有页面统一生效（含书架 / 书源 / 设置）；阅读页单独选「纸白 / 米黄 / 青石」只改变阅读区底色，不会反向把全局切回浅色。</p>
      <div class="seg" id="theme-seg" style="margin-top:10px">
        <button type="button" data-mode="light" class="${curTheme === 'light' ? 'on' : ''}">浅色</button>
        <button type="button" data-mode="dark" class="${curTheme === 'dark' ? 'on' : ''}">深色</button>
        <button type="button" data-mode="auto" class="${curTheme === 'auto' ? 'on' : ''}">跟随系统</button>
      </div>
    </div>

    <div class="card">
      <div class="sec-head"><h3>App 内界面</h3><span class="muted small">全部页面直达</span></div>
      <p class="muted small">书架、搜书、导入、阅读、书源管理均已由 App 内界面完成；书源抓取由内置引擎在后台完成，不依赖界面。两边读写同一份数据。</p>
      <div class="btn-row">
        <button class="btn" id="n-shelf">打开书架</button>
        <button class="btn ghost" id="n-settings">打开设置</button>
      </div>
      <div class="btn-row" style="margin-top:8px">
        <button class="btn ghost" id="n-search">搜书</button>
        <button class="btn ghost" id="n-import">导入书籍</button>
        <button class="btn ghost" id="n-rules">替换规则</button>
        <button class="btn ghost" id="n-sources">书源管理</button>
      </div>
    </div>

    <div class="card">
      <div class="sec-head"><h3>正文净化</h3><span class="muted small">全局替换规则</span></div>
      <p class="muted small">按顺序对取回的正文执行替换，也可只作用于章节标题。<code>正则</code> 为正则模式，否则为纯文本替换；关闭开关可临时停用某条规则。与阅读页底部「净化」按钮共用同一份规则。</p>
      <div class="btn-row">
        <button class="btn" id="r-manage">管理规则</button>
        <button class="btn ghost" id="r-json">JSON 编辑</button>
      </div>
    </div>

    <div class="card">
      <div class="sec-head"><h3>章节缓存</h3></div>
      <div class="setting-group">
        <div class="set-row"><div class="set-k">已缓存<small>阅读过的章节会缓存到本地，断网可读；自动预缓存下一章；移出书架自动清理。</small></div><span class="set-v" id="cache-stat"></span></div>
      </div>
      <div class="btn-row" style="margin-top:12px">
        <button class="btn" id="cache-refresh">刷新统计</button>
        <button class="btn danger" id="cache-clear">清空正文缓存</button>
      </div>
    </div>

    <div class="card">
      <div class="sec-head"><h3>数据</h3><span class="muted small">备份 / 恢复 / 统计</span></div>
      <div class="setting-group">
        <div class="set-row"><div class="set-k">累计阅读<small>阅读时长由阅读页心跳累计，随备份一起导出。</small></div><span class="set-v" id="read-stat">—</span></div>
      </div>
      <div class="btn-row" style="margin-top:12px">
        <button class="btn" id="d-export">导出全部数据（JSON 备份）</button>
        <button class="btn" id="d-import">导入备份</button>
      </div>
      <div class="btn-row" style="margin-top:8px">
        <button class="btn danger" id="d-clear">清空所有数据</button>
      </div>
    </div>

    <div class="card" id="perf-card">
      <div class="sec-head"><h3>性能自检</h3><span class="muted small">排查卡顿用</span></div>
      <p class="muted small">在某页面复现卡顿后回到这里点「采样 2 秒」：它会记录 2 秒内每一帧的耗时、当前内存与节点规模。把结果复制发给开发者，就能定位是页面太重、内存泄漏还是渲染层问题，不用再猜。</p>
      <div class="btn-row" style="margin-top:10px">
        <button class="btn" id="perf-run">采样 2 秒</button>
        <button class="btn ghost" id="perf-copy" disabled>复制结果</button>
      </div>
      <pre id="perf-out" class="muted small" style="white-space:pre-wrap;margin-top:10px;display:none"></pre>
    </div>

    <div class="card about">
      <div class="sec-head"><h3>关于</h3></div>
      <p class="muted small">墨读 · M5（纸墨界面 / 目录书签 / 正文搜索 / 自动阅读 / 阅读统计 / 分组书架 / 备份恢复）。架构：WebView 壳 + HTML/JS SPA + IndexedDB；规则引擎兼容 Legado 书源 JSON 常用子集；网络经本地代理（Node dev server / Android Java 壳）。</p>
      <p class="muted small">仅限个人自用：请勿传播书源、勿用于营利。</p>
    </div>
  `;

  // 全局配色：浅色 / 深色 / 跟随系统
  const seg = $('#theme-seg');
  seg.querySelectorAll('button').forEach((b) => {
    b.onclick = () => {
      seg.querySelectorAll('button').forEach((x) => x.classList.remove('on'));
      b.classList.add('on');
      saveAppTheme(b.dataset.mode);
    };
  });

  /* ---------- 原生界面入口 ---------- */
  // 桥方法是新版本才有的：老版本装上旧 APK 时按钮点了没反应，要先判断再调用
  const hasNative = () => !!(window.ModouBridge && typeof window.ModouBridge.openNativeShelf === 'function');
  $('#n-shelf').onclick = () => {
    if (!hasNative()) return toast('当前不是 App 环境');
    window.ModouBridge.openNativeShelf();
  };
  $('#n-settings').onclick = () => {
    if (!hasNative()) return toast('当前不是 App 环境');
    window.ModouBridge.openNativeSettings();
  };
  $('#n-search').onclick = () => {
    if (!hasNative()) return toast('当前不是 App 环境');
    window.ModouBridge.openNativeSearch();
  };
  $('#n-import').onclick = () => {
    if (!hasNative()) return toast('当前不是 App 环境');
    window.ModouBridge.openNativeImport();
  };
  $('#n-rules').onclick = () => {
    if (!hasNative()) return toast('当前不是 App 环境');
    window.ModouBridge.openNativeRules();
  };
  $('#n-sources').onclick = () => {
    if (!hasNative()) return toast('当前不是 App 环境');
    window.ModouBridge.openNativeSources();
  };
  /* ---------- 性能自检：2 秒帧采样 + 内存/节点快照 ----------
   * 设备上才出现的卡顿没法在开发机复现时，让用户一键出数据：
   * 堆大小看是否泄漏、节点数看 DOM 是否堆积、帧耗时看主线程是否被占满。 */
  $('#perf-run').onclick = async () => {
    const out = $('#perf-out');
    const btn = $('#perf-run');
    btn.disabled = true;
    out.style.display = 'block';
    out.textContent = '采样中…（这两秒里随便滑动/点按，结果更真实）';
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    const deltas = [];
    let last = performance.now();
    const t0 = last;
    await new Promise((done) => {
      const tick = (t) => {
        deltas.push(t - last);
        last = t;
        if (t - t0 >= 2000) return done();
        requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    });
    deltas.shift();
    const frames = deltas.length;
    const sorted = [...deltas].sort((a, b) => a - b);
    const avg = frames ? deltas.reduce((a, b) => a + b, 0) / frames : 0;
    const worst = frames ? sorted[frames - 1] : 0;
    const jank = deltas.filter((d) => d > 32).length;
    const mem = performance.memory && performance.memory.usedJSHeapSize
      ? (performance.memory.usedJSHeapSize / 1048576).toFixed(1) + ' MB'
      : '不可用';
    const ver = (() => {
      try { return window.ModouBridge && window.ModouBridge.appVersion ? window.ModouBridge.appVersion() : 'web'; } catch (e) { return '?'; }
    })();
    const lines = [
      '版本: ' + ver,
      '页面: ' + location.hash,
      'JS堆: ' + mem,
      'DOM节点: ' + document.getElementsByTagName('*').length,
      '帧采样: ' + frames + ' 帧/2s · 平均 ' + avg.toFixed(1) + 'ms · 最差 ' + worst.toFixed(0) + 'ms · 超32ms的帧 ' + jank + ' 个',
      'UA: ' + String(navigator.userAgent).slice(0, 100),
    ];
    out.textContent = lines.join('\n');
    window.__perfReport = lines.join('\n');
    btn.disabled = false;
    $('#perf-copy').disabled = false;
  };
  $('#perf-copy').onclick = async () => {
    const text = window.__perfReport || '';
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      toast('已复制');
    } catch (e) {
      // WebView 里剪贴板可能受限：全选让用户手动复制
      const out = $('#perf-out');
      const range = document.createRange();
      range.selectNodeContents(out);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      toast('已全选，请长按复制');
    }
  };

  // 规则管理：阅读页「净化」与设置页共用 replace.js 的编辑器
  $('#r-manage').onclick = () => openReplaceSheet();

  $('#r-json').onclick = () => {
    const rules = state.replaceRules;
    const ta = h('textarea', { class: 'json-input', rows: 14 });
    ta.value = JSON.stringify(rules, null, 2);
    const btn = h('button', { class: 'btn primary' }, '保存');
    const log = h('p', { class: 'muted' });
    btn.onclick = async () => {
      try {
        const arr = JSON.parse(ta.value);
        if (!Array.isArray(arr)) throw new Error('需要数组');
        state.replaceRules = arr;
        await settings.set('replaceRules', arr);
        await reloadReplaceRules();
        toast('已保存');
        close();
      } catch (e) {
        log.textContent = '保存失败：' + e.message;
      }
    };
    const body = h('div', { class: 'form' }, ta, btn, log);
    const { close } = openModal('替换规则 JSON', body, { wide: true });
  };

  /* ---------- 章节缓存 ---------- */
  // 只数章节数（getAllKeys，很小）；绝不 db.all('chapters') —— 那会把所有正文
  // 一次性读进内存，书多时 JS 堆直接爆到几百 MB 甚至上 GB（卡顿/闪退根因）。
  const paintCache = async () => {
    const keys = await db.keys('chapters');
    $('#cache-stat').textContent = `${keys.length} 章`;
  };
  // 「刷新统计」要精确大小才用游标流式累加，峰值只占一条记录
  const refreshCacheSize = async () => {
    const keys = await db.keys('chapters');
    let chars = 0;
    await db.forEach('chapters', (r) => {
      chars += (r && r.content ? r.content.length : 0);
    });
    $('#cache-stat').textContent = `${keys.length} 章 · 约 ${(chars / 1048576).toFixed(1)} MB`;
  };
  paintCache();
  $('#cache-refresh').onclick = refreshCacheSize;
  $('#cache-clear').onclick = async () => {
    if (!(await confirmBox('确定清空全部正文缓存？已读章节需要联网重新获取。'))) return;
    await db.clear('chapters');
    toast('正文缓存已清空');
    paintCache();
  };

  /* ---------- 数据 ---------- */
  const paintStat = async () => {
    const el = $('#read-stat');
    if (!el) return;
    const st = await getReadStats();
    const hh = Math.floor(st.totalMs / 3600000);
    const mm = Math.floor((st.totalMs % 3600000) / 60000);
    el.textContent = `${hh} 小时 ${mm} 分钟 · ${st.books} 本书`;
  };
  paintStat();

  $('#d-export').onclick = async () => {
    const data = {
      sources: await db.all('sources'),
      books: await db.all('books'),
      progress: await db.all('progress'),
      bookmarks: await db.all('bookmarks'),
      replaceRules: state.replaceRules,
      settings: await db.all('settings'),
      exportedAt: new Date().toISOString(),
    };
    // 走系统「另存为」（APK）/ 浏览器下载，WebView 不支持 a.download 的 blob
    const ok = saveFile('modou-backup.json', JSON.stringify(data, null, 2), 'application/json');
    toast(ok ? '备份已导出' : '导出失败');
  };

  // 导入备份：校验结构 → confirmBox 说明合并策略 → 逐 store 恢复 → 重载
  $('#d-import').onclick = () => {
    const inp = h('input', { type: 'file', accept: '.json,application/json', style: 'display:none' });
    inp.onchange = async () => {
      const f = inp.files && inp.files[0];
      inp.remove();
      if (!f) return;
      let data;
      try {
        data = JSON.parse(await f.text());
      } catch (e) {
        return toast('导入失败：不是合法的 JSON 文件');
      }
      if (!data || typeof data !== 'object' || Array.isArray(data)) return toast('导入失败：备份格式不正确');
      const parts = [];
      if (Array.isArray(data.sources)) parts.push(`${data.sources.length} 个书源`);
      if (Array.isArray(data.books)) parts.push(`${data.books.length} 本书`);
      if (Array.isArray(data.progress)) parts.push(`${data.progress.length} 条进度`);
      if (Array.isArray(data.bookmarks)) parts.push(`${data.bookmarks.length} 个书签`);
      if (Array.isArray(data.replaceRules)) parts.push(`${data.replaceRules.length} 条净化规则`);
      if (Array.isArray(data.settings)) parts.push(`${data.settings.length} 项设置`);
      if (!parts.length) return toast('导入失败：备份中没有可恢复的数据');
      if (!(await confirmBox(`将从备份恢复：${parts.join(' · ')}。\n合并策略：同 id 记录覆盖写入，不会删除现有数据。确定继续？`))) return;
      try {
        if (Array.isArray(data.sources)) await db.putMany('sources', data.sources.filter((s) => s && s.id));
        if (Array.isArray(data.books)) await db.putMany('books', data.books.filter((b) => b && b.id));
        if (Array.isArray(data.progress)) await db.putMany('progress', data.progress.filter((p) => p && p.bookId));
        if (Array.isArray(data.bookmarks)) await db.putMany('bookmarks', data.bookmarks.filter((b) => b && b.id));
        if (Array.isArray(data.replaceRules)) await settings.set('replaceRules', data.replaceRules);
        if (Array.isArray(data.settings)) await db.putMany('settings', data.settings.filter((s) => s && s.k));
      } catch (e) {
        return toast('恢复失败：' + (e.message || e));
      }
      toast('恢复完成，正在重载…');
      setTimeout(() => location.reload(), 600);
    };
    document.body.append(inp);
    inp.click();
  };

  $('#d-clear').onclick = async () => {
    if (!(await confirmBox('确定清空所有数据？书源、书架、进度、书签、规则全部删除且不可恢复！'))) return;
    if (!(await confirmBox('再次确认：此操作不可撤销。'))) return;
    for (const s of ['sources', 'books', 'progress', 'chapters', 'bookmarks', 'replaceRules', 'settings']) await db.clear(s);
    toast('已清空，正在重载…');
    setTimeout(() => location.reload(), 600);
  };
}
