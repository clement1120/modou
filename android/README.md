# 墨读 · Android 壳

WebView 壳 + 内嵌本地 HTTP 服务，把 `web/` 里的 SPA 原样装进 APK。引擎与 UI 代码零改动——浏览器开发和 App 里跑的是同一份前端。

## 架构

```
APK (cn.modou.app)
├─ MainActivity        WebView 容器（按需创建）→ http://127.0.0.1:39617/
├─ LocalServer         NanoHTTPD：静态服务 assets/web/ + /proxy 代理
├─ ProxyHandler        /proxy 逻辑：UA 注入 / 编码嗅探（GBK→UTF-8）/ JSON 包装
├─ ModouBridge         JS 桥（window.ModouBridge）：TTS / 分享 / 版本
├─ TTSController       Android TextToSpeech 封装（中文优先，缺包回退默认）
└─ assets/web/         ← 构建时由 syncWeb 任务从 ../../web 同步（生成在 build/generated/webAssets，不落源码树）
```

**WebView 是按需创建的**：书架、阅读、搜书、书源管理都已原生实现，界面 WebView 只在
迁移旧数据（IndexedDB → SQLite）、刷新目录、缓存全书、书源兜底这些要 JS 引擎的动作里
才建（`MainActivity.ensureBridge()`）。冷启动不再碰 Chromium —— 那 1~3 秒的主线程初始化
正是「冷启动后第一本书究极慢」的根因。

**为什么是本地 server 而不是 file://**：`file://` 下 `location.origin` 为 `null`，前端 `http.js` 的代理请求会失效；IndexedDB 在 file:// 下也受限。起本地服务后前端代码路径与桌面开发完全一致（同一条 `/proxy` 通道）。

## 构建

前置：JDK 17+、Android SDK（platform android-34、build-tools 34.0.0）。

```bash
# 方式一：Android Studio 直接打开 android/ 目录，Run ▶

# 方式二：命令行
cd android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # Windows: sdk.dir=C\:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      #  release 需配置签名（见下）
```

`preBuild` 依赖 `syncWeb` 任务：每次构建自动把 `web/` 同步到 `build/generated/webAssets/web/` 并作为 assets 源目录，**只改 web/ 就行，不用手动同步**。源码树里不再保留第二份 `src/main/assets/web/` 镜像（少 29 个重复文件，也不会出现两份不同步的前端代码）。

## 本地导入（v0.6.127 起为流式）

`LocalImport` 是唯一入口，`ImportActivity`（选文件夹）与 `LocalBooksActivity`（按文件夹列书、点＋导入、长按多选）都走它：

1. SAF 的 `Uri` 先复制到 `cacheDir/import/`，解析器按文件随机读（SAF 的流不能回退，`ZipFile` 需要可寻址）；
2. **TXT**：读头 64KB 探编码（BOM/严格 UTF-8/GBK）→ `TxtBook.parseStream` 逐行分章；
   **EPUB**：`EpubBook.parse(File, sink)` 用 `ZipFile` 按需取条目，不再整本解压；
3. 每产出一章就交给 `Writer` 分批入库（约 120 万字符或一批），写完即弃；
4. 全部成功后写 `books` 记录（`id`/`bookUrl` = `local://文件名#字节数`，与网页版同一条记录）。

三个约束不能随手改回去：

- **别把整本书读进 byte[]**：40MB 图文合集解压后上百 MB，低端机必 OOM，进程被杀 = 用户看到的「点了没反应 / 闪回桌面」。
- **别并发导入**：`LocalImport.enqueue` 内部是单线程队列，连点几本也只排队；同时开两个解析线程会让内存翻倍。
- **别只 catch Exception**：`OutOfMemoryError` 是 Error，漏掉它进程直接死；`run` 里必须兜 `Throwable`。

## 安装与使用

- 安装 APK 后打开即用；首次进「书源管理 → 载入示例书源」
- 书源导入三条路：页面内「粘贴/网络/文件导入」（`<input type=file>` 由壳的 `onShowFileChooser` 承接）；或从文件管理器「用其他应用打开 → 墨读」分享 JSON 进来（VIEW intent，内容经 `window.__modouFileResult` 注入）
- 朗读：阅读页顶栏「▶ 朗读」，走系统 TTS（设置 → 系统 → 语言 → 文字转语音，装中文语音包效果更好）
- 外链（正文里的原文链接）用系统浏览器打开，不留在壳内

## 发布签名

`assembleRelease` 默认未签名。固定签名二选一：

1. Android Studio：Build → Generate Signed Bundle / APK，自动写入 `app/build.gradle` 的 `signingConfigs`
2. 命令行：`keytool -genkey -v -keystore modou.jks -alias modou -keyalg RSA -keysize 2048 -validity 10000`，然后在 `app/build.gradle` 配 `signingConfigs.release` 的四个参数

> 内置更新分发（坚果云）留待 M3；`ModouBridge.appVersion()` 已就位。

## 已知事项

- `usesCleartextTraffic="true"`：本地代理是 http（127.0.0.1），外站请求仍由代理侧 https 完成
- minSdk 26（Android 8.0）：IndexedDB/WebView 行为稳定；WebView 组件随系统更新
- NanoHTTPD 以源码内嵌（`java/org/nanohttpd/`，BSD-3-Clause，来自 NanoHttpd/nanohttpd），零第三方 Gradle 依赖
- 壳未做 `WebView.setWebContentsDebuggingEnabled`——真机调试时可在 MainActivity 里临时打开，用 chrome://inspect 看前端
