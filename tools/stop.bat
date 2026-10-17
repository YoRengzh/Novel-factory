@echo off
:: ============================================================
:: novel-factory 停止脚本（双击运行）：按端口精确杀进程，不按进程名
:: （按进程名会误杀用户其他 java 程序）
:: ============================================================
setlocal enabledelayedexpansion

cd /d "%~dp0"
chcp 65001 >nul

:: 没在跑就直接说，别让 taskkill 输出吓人的错误
netstat -ano | findstr :8080 | findstr LISTENING >nul 2>nul
if !errorlevel! neq 0 (
    echo [信息] 服务当前没有在运行，无需停止。
    pause
    exit /b 0
)

:: ⚠️ ^| 是管道符在 for 里的转义写法；tokens=5 取 netstat 第 5 列 = PID
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /pid %%a /f >nul 2>nul

:: 等 1 秒让端口释放，然后复核结果——成功失败都要让用户看得见
timeout /t 1 /nobreak >nul
netstat -ano | findstr :8080 | findstr LISTENING >nul 2>nul
if !errorlevel! equ 0 (
    echo [警告] 端口 8080 仍被占用，请打开任务管理器手动结束 java 进程。
) else (
    echo [成功] 服务已停止，端口已释放。
)
pause
exit /b 0