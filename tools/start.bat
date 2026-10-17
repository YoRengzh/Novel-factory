@echo off
:: ============================================================
:: novel-factory 启动脚本（双击运行）
:: 前提：本目录下有 novel_factory-app.jar（JDK 17+ 需已装并入 PATH）
:: ============================================================
setlocal enabledelayedexpansion

:: 1. 锚定工作目录（config/ 与 data/ 都按工作目录解析，此行漏了全盘失效）
cd /d "%~dp0"

:: 2. 控制台切 UTF-8，治中文乱码
chcp 65001 >nul

:: 3. 检查 JDK（⚠️ 用延迟展开，与全文统一）
where java >nul 2>nul
if !errorlevel! neq 0 (
    echo [错误] 未找到 Java。请安装 JDK 17+ 并加入 PATH 后重试。
    pause
    exit /b 1
)

:: 4. 检查 jar 是否就位（错误要在第一时间暴露，不能让它烂在 60 秒轮询后面）
if not exist novel_factory-app.jar (
    echo [错误] 当前目录没有 novel_factory-app.jar。
    echo        请先构建（仓库里运行 bash tools\build-release.sh），把产物放到本目录。
    pause
    exit /b 1
)

:: 5. 清空代理环境变量（⚠️ java 进程会继承它们；代理存在时 chat 调用会
::    "Network is unreachable"——run-app.sh 时代实测踩过的坑）
set http_proxy=
set https_proxy=
set HTTP_PROXY=
set HTTPS_PROXY=

:: 6. 已在运行就直接用，不重复拉起（避免第二个 java 起来注定失败再干等 60 秒）
curl -s -m 2 -o nul http://127.0.0.1:8080/ 2>nul
if !errorlevel! equ 0 (
    echo [信息] 服务已在运行（浏览器请手动访问 http://127.0.0.1:8080）。
    pause
    exit /b 0
)

:: 7. 后台最小化启动服务（⚠️ "novel-factory" 是窗口标题不是命令，start 的语法陷阱）
echo [信息] 正在后台启动 novel-factory，首次启动约需 10-30 秒……
start "novel-factory" /min java -Dfile.encoding=UTF-8 -jar novel_factory-app.jar

:: 8. 轮询等 8080 就绪（1 秒一次，上限 60 次）
set /a tries=0
:waitloop
timeout /t 1 /nobreak >nul
curl -s -m 2 -o nul http://127.0.0.1:8080/ 2>nul
if !errorlevel! equ 0 goto ready
set /a tries+=1
if !tries! geq 60 goto timeoutfail
goto waitloop

:ready
echo [成功] 服务已就绪，正在打开浏览器……（关闭服务请运行 stop.bat）
start http://127.0.0.1:8080
exit /b 0

:timeoutfail
echo [警告] 等待 60 秒仍未就绪。可能原因：端口被占用 / 启动报错。
echo        日志在 data\log\log_error.log，看完按任意键退出。
pause
exit /b 1