# 墨读（Modou）源码审查说明

版本：**v0.6.38**（versionCode 44）
打包日期：2026-10-26
打包用途：外部代码审查（结构、算法、安全、性能）

---

## 一、这是什么

一个 **Android 本地阅读器**：WebView 壳加载本地 HTTP 服务托管的单页应用（SPA），
支持导入 txt / epub 本地书、导入 Legado（开源阅读）书源 JSON 在线搜书阅读。

设计约束（决定了架构，不是随意选择）：

1. **完全离线运行**：数据存 IndexedDB，无后端、无账号。
2. **书源兼容 Legado**：书源是 JSON 规则（选择器 / JSONPath / 正则 / JS），需要自研规则引擎。
3. **壳很薄**：Java 只负责本地服务、代理、TTS、文件选择、返回键、系统分享。

---

## 二、技术栈与目录

```
web/                     前端（无框架，原生 ES Module，约 1.2 万行）
  index.html
  styles.css             全部样式（单文件，CSS 变量做主题）
  js/
    app.js               应用入口 / 路由 / 全局状态（书架数据源）
    db.js                IndexedDB 封装（books / chapters / sources / settings）
    http.js              统一网络出口（走本地 /proxy 绕 CORS）
    lib/
      epub.js            EPUB 解析（container→OPF→spine→章节，封面/插图提取）
      zip.js             最小 ZIP 解压（无第三方依赖）
    engine/
      source.js          书源对象：搜索/详情/目录/正文链路 + URL 模板求值
      rule.js            Legado 规则语法求值器（CSS/JSONPath/XPath/正则/JS/链式/净化）
      jsonpath.js        JSONPath 子集
      sandbox.js         规则内 JS 沙箱 + java.* 桥（ajax/getElements/md5/…）
    ui/
      shelf.js  book.js  reader.js  reader-toc.js  search.js
      sources.js        书源管理（导入/校验/查重/筛选）
      replace.js        替换净化规则
      settings.js  local-import.js  tts.js  dom.js
server/
  proxy-server.js        开发期服务器（静态资源 + /proxy 转发），与 Android 端职责对应
android/                 原生壳（Java，约 1.5k 行）
  app/src/main/java/cn/modou/app/
    MainActivity.java    入口、WebView 配置、返回键询问前端
    LocalServer.java     内置 HTTP 服务（NanoHTTPD），托管 assets + /proxy
    ProxyHandler.java    代理实现（UA/Referer/Cookie 罐/解压/编码纠错）
    ModouBridge.java     @JavascriptInterface 桥（TTS/分享/保存文件/编辑态）
    ReaderWebView.java   WebView 子类（压制原生选区菜单）
    TTSController.java   系统 TTS 封装
test/                    测试（Playwright 浏览器 + JSDOM 引擎单测）
README.md                开发日志（按版本记录每个问题的根因与修法，约 80KB）
```

**构建**：`cd android && ./gradlew assembleDebug`（minSdk 26 / target 34）

---

## 三、怎么跑起来

```bash
npm install            # jsdom + playwright（仅测试用）
npm start              # 起服务 http://127.0.0.1:8080
npm test               # 引擎单测 + 本地导入单测（e2e 78 + local 33）
npm run test:ui        # UI 冒烟（Playwright，需先起 server）
```

UI 冒烟要求 `MODOU_FIXTURES=1 npm start`（网络回放模式，避免真实联网）。
本包已剔除 `test/fixtures/`（真实网站 HTML 快照，含第三方内容），
**因此 `npm test` 中依赖 fixture 的用例会失败**，属预期。要完整跑请自行提供 fixture。

---

## 四、建议重点审查的地方

按我认为的风险从高到低：

### 1. 书源规则引擎（web/js/engine/）
- `rule.js` 的 `parseSegment`：一段规则要拆成「选择器链 / 属性 / 索引 / 排除 / 净化 / 变量存储 / JS 后处理」，
  分支很多，边界容易出错（历史 bug 大多出在这里）。
- `sandbox.js` 的 `runJs`：用 `new Function` 执行书源里的 JS。**这是设计上的最大安全面**——
  书源是用户导入的第三方 JSON，等价于允许执行任意脚本。当前没有沙箱隔离（同源执行，能读 IndexedDB、
  发网络请求）。若要做安全加固，这里应优先（例如 Worker + 能力白名单）。
- `source.js` 的 `buildRequest`：URL 模板求值（`@js:` / `{{js}}` / `{$path}` / 相对路径 / 尾随选项），
  与 `parseLooseOpts`（单引号伪 JSON）配合，注意注入与异常路径。

### 2. 代理层（ProxyHandler.java / proxy-server.js）
- 双端实现必须行为一致：UA 注入、Cookie 罐（按 host）、Referer 补齐、gzip/deflate 解压、
  charset 嗅探 + 「合法 UTF-8 纠错」。
- 潜在问题：Cookie 罐是进程级静态 Map，不区分书源（同域共享），也不持久化；
  解压与编码纠错是启发式，存在误判可能。
- 安全面：`/proxy` 无鉴权，但只绑 127.0.0.1；SSRF 风险（可请求任意地址，含内网）需评估。

### 3. 分页引擎（web/js/ui/reader.js 的 paginate）
- 文本按「剩余高度贪心切段」，用隐藏容器实测高度；含图片的段落整段搬运不切分。
- 已知边界：比一页还高的单段/图片会被页裁切；跨页选区的定位。

### 4. 返回键与浮层栈（web/js/ui/dom.js + MainActivity.onBackPressed）
- 前端维护 `backHandlers` 栈，原生 `onBackPressed` 通过 `evaluateJavascript` 询问 `window.__modouBack()`，
  600ms 兜底超时。
- 刻意**不使用 history 占位**（历史教训：`history.back()` 会与紧随的路由跳转抢时序）。
  请审查这个设计在快速连按、页面重渲染、异步弹层关闭时的竞态。

### 5. WebView 选区处理（ReaderWebView.java）
- 为了让自绘工具条独占，`startActionMode` 返回一个**空 ActionMode** 并把 `onGetContentRect` 定位到屏幕外。
- 这是 hack：依赖「ActionMode 建立但菜单不可见」这一行为。不同 ROM 可能有差异。

### 6. 数据层（web/js/db.js）
- 单库多表，章节正文按 `bookId||index` 存，EPUB 图片以 dataURL 内嵌在章节 HTML 里（体积换简单）。
- 大书（几千章 + 图片）导入时是分片写入 + 让出主线程，仍可能有内存峰值。

---

## 五、已知问题与限制（诚实清单）

| 项 | 现状 |
|---|---|
| 书源通过率 | 134 个真实源中约 44 个校验通过。失败主要分三类：源域名已失效（301/404）、站点反爬（403）、服务端拒绝（返回 `ok:false`，需签名/参数）。规则语法已覆盖到「选择器链/索引/排除/JS 前后缀/净化/模板/@@/`\|\|`」这一层 |
| JS 沙箱 | **v0.6.49 已做能力白名单（收敛）**：书源 JS 执行时把 `window / document / fetch / XMLHttpRequest / localStorage / indexedDB / eval / Function` 等 20 个危险全局用同名参数遮蔽为 `undefined`，网络只能走 `java.ajax`（经代理，保住 charset 与 cookie 处理）。**未做真隔离**：`ctx.doc` 是解析后的文档对象（含 Proxy / DOM 节点），无法跨 Worker 边界传递，整套搬进 Worker 不现实；且 JS 仍可经原型链（`({}).constructor.constructor`）逃逸到 `Function`。残余风险靠调用点错误边界兜底（见下） |
| 验证码 / 登录 | 不支持（`java.getVerificationCode` 未实现） |
| 云同步 | 无，纯本地 |
| 图片处理 | EPUB 插图转 dataURL 内嵌，无限流/压缩；超大图（>4MB）跳过 |
| 章节缓存 | 无上限管理，读得多占得多 |
| 多线程 | 全在主线程，靠 `setTimeout(0)` 让出；没有 Worker |
| i18n | 仅中文 |

---

## 六、代码规模

| 部分 | 约行数 |
|---|---|
| web/js（含 UI） | 12,000 |
| android java（自研，cn.modou.app） | 1,500 |
| server | 400 |
| test | 2,500 |
| README（开发日志） | 80 KB |

测试基线：e2e 90 / local-import 33 / UI 冒烟 186，全绿（本包缺 fixture，见上）。

---

## 七、第三方代码（不是本项目写的，审查时可略过）

| 位置 | 说明 |
|---|---|
| `android/app/src/main/java/org/nanohttpd/` | **NanoHTTPD**（BSD 许可）内嵌源码，提供 Android 端内置 HTTP 服务。仅 `LocalServer.java` 调用它 |
| 无其它第三方运行时依赖 | 前端零框架、零运行时库（zip/epub/jsonpath 均为自研）；`jsdom` 与 `playwright` 只用于测试 |

---

## 八、审查时可以跳过的东西

- `README.md`：是按版本累积的开发日志（80KB，记录每个 bug 的根因），不是架构文档，翻最后几节即可。
- `test/*.mjs`：断言较多但主要是回归护栏，逻辑比业务代码简单。

