# 墨读 · 构建与协作说明

这份文件写给「接手的人或 AI」：拿到仓库后怎么跑起来、怎么验证、改哪里。

## 一、这个项目是什么

个 Android 阅读器，两套实现并存：

| 层 | 位置 | 说明 |
|---|---|---|
| 原生外壳与 UI | `android/app/src/main/java/cn/modou/app/` | 纯 Java，零第三方依赖。书架、阅读页、书源管理、导入、设置全部自绘（`ModouUi`） |
| 规则引擎（JS） | `web/js/` | 跑在 WebView 里：书源规则解析、搜索、取章、净化。与 Legado 书源 JSON 常用子集兼容 |
| 本地调试服务器 | `server/proxy-server.js` | 零依赖 Node HTTP 服务，Android 壳把它当静态资源与 CORS 代理用 |

数据全在 SQLite（`ModouStore`，单表 KV 化：`books` / `chapters` / `sources` / `settings` / `progress` …），
不再依赖 IndexedDB —— 老数据的迁移在 `ModouBridge` 里做。

## 二、构建 Android APK

需要：JDK 17、Android SDK（platform 34 + build-tools）、Gradle 用仓库自带 wrapper。

```bash
cd android
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
./gradlew :app:assembleDebug --no-daemon
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

注意两点：

1. **`local.properties` 不进仓库**（里面有本机 SDK 路径），构建前自己建一份：
   ```
   sdk.dir=/path/to/android-sdk
   ```
2. **前端资源是 Gradle 生成的**：`android/app/build.gradle` 里的 `syncWeb` 任务把仓库根目录的 `web/`
   复制到 `build/generated/webAssets/web` 并注册为 assets 源目录。**不要**在 `android/app/src/main/assets/`
   下再放一份副本（历史上踩过：两份 29 个文件逐字节重复，改一份忘另一份）。

发布版本号改 `android/app/build.gradle` 的 `versionCode` / `versionName`，并同步 README 的版本日志。

## 三、跑测试

```bash
npm install                      # 只有 jsdom + playwright（测试用）
npm start                        # 起本地 dev server，默认 8080 端口（PORT=xxxx 可改）
npm test                         # 规则引擎 e2e + 本地导入解析
npm run test:ui                  # 界面冒烟（Playwright + Chromium），当前基线 187 通过 / 0 失败
npm run test:layout              # 排版分页单测
```

> APK 内部的静态服务是 Java 内嵌的（`LocalServer`，端口 **39617**），与上面这个 Node dev server 是两套东西：
> 前者给 WebView 加载前端，后者只用于本地调试与 Playwright 测试。

- 测试默认读 `test/fixtures/` 里的抓包快照，**不联网**；`MODOU_FIXTURES=1` 环境变量会让服务走离线夹具。
- `npm run test:ui` 之前要先 `npm start`（在另一个终端或后台）。
- 改动排版（`NativePageView`）或规则引擎（`web/js/source/`）之后，至少跑 `npm run test:layout` 与 `npm run test:ui`。

## 四、代码地图

```
android/app/src/main/java/cn/modou/app/
  MainActivity.java         WebView 容器（按需创建，冷启动不建引擎）→ http://127.0.0.1:39617/
  ShelfActivity.java        书架 + 底部四页（书架/书源/规则/设置），HomePager 横滑
  NativeReaderActivity.java 阅读页：取章、预取、目录、TTS、换章
  NativePageView.java       排版与绘制：StaticLayout 分页、整字格、标点挤压、选区、图片
  BookActivity.java         书籍详情（目录、书源切换、缓存）
  SourcesPanel.java         书源管理：体检、去重、导入导出、多选
  ModouStore.java           SQLite 存取层（books 有 lite 轻量列，书架只读它）
  ModouBridge.java          JS 与原生之间的桥
  LocalImport.java          本地 TXT/EPUB 导入队列（单线程流式）
  EpubBook.java / TxtBook.java  解析器（流式；EPUB 插图落文件，不内联 base64）

web/js/
  source/                   规则引擎：搜索、详情、目录、正文、净化
  ui/                       网页版界面（现主要作为引擎与调试面板）
```

## 五、几条硬约束（改之前先读，改了会出问题）

- **Binder 1MB 上限**：`ReaderLaunch` 只传 `bookId + index + offset + 排版参数`，
  禁止把目录数组或正文塞进 Intent。
- **CursorWindow 单行 2MB 上限**：`chapters.content` 可能很长（图片多的 EPUB），
  读取必须走 `ModouStore.readTextByKey` 的分片逻辑，直接 `SELECT content` 会抛
  `Row too big to fit into CursorWindow`。
- **书架只读 `books.lite`**：完整 json 里有几千章目录和内联封面，整表读会卡死。
  写回时必须**先读完整记录再覆盖字段**（`ShelfActivity.saveBook`），否则目录会被抹掉。
- **阅读页在前台时后台要让路**：`ModouStore.setQuiet(true)` 期间不做 lite 回填、不搜封面。
- **换章请求用代次（`chapterGen`）顶替**，不要用布尔量挡重复请求 —— 一旦卡住会把后续点击全吃掉。
- **标点挤压不改字符数**：`HalfPunctSpan` 只压显示宽度，偏移量（书签、进度、选区）依赖字符数不变。

## 六、和外部 AI 协作时

- 改动尽量小步：一次改一个可验证的问题，改完 `npm run test:ui` 跑一遍。
- 每版都更新 README 的版本日志（写清「改前什么表现 → 根因 → 现在怎么处理」），
  这份日志是判断改动是否真的落地的主要依据。
- 涉及数据库结构（加列/索引）时，要考虑老库升级路径（`ModouStore.onCreate/onUpgrade`），
  且失败要能退回旧读法（参考 `liteReady` 标志）。
