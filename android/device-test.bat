@echo off
setlocal EnableDelayedExpansion
:: 墨读 真机检验脚本 —— 连接手机后一键安装/启动/截图/抓日志
:: 用法：双击本文件，按菜单选择。手机需先开启 USB 调试并允许本机。

set "ADB=C:\modou-sdk\platform-tools\adb.exe"
set "APK=C:\modou-src\android\app\build\outputs\apk\release\modou-0.9.178-release.apk"
set "PKG=cn.modou.app"
set "ACT=cn.modou.app.MainActivity"
set "OUT=C:\modou-src\android\device-shots"
if not exist "%OUT%" mkdir "%OUT%"

echo ============================================
echo   墨读 真机检验工具  (adb v1.0.41)
echo   包名: %PKG%   产物: modou-0.9.178-release.apk
echo ============================================
echo.
echo [1] 检查 adb 版本与已连接设备
echo [2] 等待设备接入 (wait-for-device)
echo [3] 安装/重装 APK (-r 保留数据)
echo [4] 启动 App
echo [5] 切换深色  (uimode night yes)
echo [6] 切换浅色  (uimode night no)
echo [7] 截图 (交互输入文件名)
echo [8] 开始/停止 后台 logcat 抓取
echo [9] 清空 App 数据 (谨慎)
echo [0] 退出
echo --------------------------------------------

:menu
set "CHOICE="
set /p CHOICE=请选择: 
if "%CHOICE%"=="1" goto check
if "%CHOICE%"=="2" goto wait
if "%CHOICE%"=="3" goto install
if "%CHOICE%"=="4" goto launch
if "%CHOICE%"=="5" goto dark
if "%CHOICE%"=="6" goto light
if "%CHOICE%"=="7" goto shot
if "%CHOICE%"=="8" goto logcat
if "%CHOICE%"=="9" goto clear
if "%CHOICE%"=="0" goto end
echo 无效输入，请重试。
goto menu

:check
echo === adb version ===
"%ADB%" version
echo === 已连接设备 ===
"%ADB%" devices -l
echo.
echo 若状态为 unauthorized: 请在手机上点"允许 USB 调试"。
echo 若设备未列出: 检查数据线/驱动(OPPO·小米·华为需对应 USB 驱动)。
goto menu

:wait
echo 请用数据线连接手机，并在手机上允许 USB 调试...
"%ADB%" wait-for-device
echo 设备已连接，当前列表:
"%ADB%" devices -l
goto menu

:install
if not exist "%APK%" (
  echo [错误] 找不到 APK: %APK%
  goto menu
)
echo 正在安装 %APK% ...
"%ADB%" install -r "%APK%"
echo 安装完成（成功会显示 Success）。
goto menu

:launch
echo 启动 %PKG%/%ACT% ...
"%ADB%" shell am start -n %PKG%/.MainActivity
goto menu

:dark
"%ADB%" shell cmd uimode night yes
echo 已切换深色。
goto menu

:light
"%ADB%" shell cmd uimode night no
echo 已切换浅色。
goto menu

:shot
set "LABEL="
set /p LABEL=截图文件名(如 m3_dark_reader，留空自动命名): 
if "%LABEL%"=="" (
  for /f "tokens=1-3 delims=/: " %%a in ("%date% %time%") do set "LABEL=shot_%%a%%b%%c"
)
"%ADB%" exec-out screencap -p > "%OUT%\%LABEL%.png"
echo 已保存: %OUT%\%LABEL%.png
goto menu

:logcat
if exist "%OUT%\device_logcat.txt" (
  echo device_logcat.txt 已存在。
  set "KILL="
  set /p KILL=输入 k 停止后台 logcat，其它键跳过: 
  if /i "!KILL!"=="k" (
    taskkill /fi "WINDOWTITLE eq modou_logcat*" >nul 2>&1
    echo 已请求停止后台 logcat。
  )
  goto menu
)
echo 正在后台启动 logcat 抓取 -> %OUT%\device_logcat.txt
start "modou_logcat" cmd /c ""%ADB%" logcat -v time > "%OUT%\device_logcat.txt""
echo logcat 已在后台运行。复现问题后选 [8] 输入 k 停止。
goto menu

:clear
set "CONF="
set /p CONF=确认清空 App 数据? 输入 yes 继续: 
if /i "%CONF%"=="yes" (
  "%ADB%" shell pm clear %PKG%
  echo 已清空 %PKG% 数据。
) else (
  echo 已取消。
)
goto menu

:end
echo 退出。截图与日志在: %OUT%
endlocal
