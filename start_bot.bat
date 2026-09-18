@echo off

cd /d "%~dp0MindPet-java"

setlocal enabledelayedexpansion



echo ========================================

echo   MindPet - Bot 完整启动

echo ========================================

echo.



REM === 自动检测 Redis 安装位置 ===

set REDIS_DIR=

REM 1) 检查环境变量 REDIS_HOME

if defined REDIS_HOME if exist "%REDIS_HOME%\redis-server.exe" set REDIS_DIR=%REDIS_HOME%

REM 2) 检查 redis-cli 是否在 PATH 中

if not defined REDIS_DIR (

    for /f "delims=" %%i in ('where redis-cli 2^>nul') do (

        if exist "%%~dpi..\redis-server.exe" set REDIS_DIR=%%~dpi..

        if exist "%%~dpiredis-server.exe"  set REDIS_DIR=%%~dpi

    )

)

REM 3) 常见安装路径

if not defined REDIS_DIR (

    for %%d in (

        "C:\Program Files\Redis"

        "D:\youkeda\Redis-8.8.0"

        "D:\Redis"

        "%USERPROFILE%\Redis"

    ) do if not defined REDIS_DIR if exist "%%~d\redis-server.exe" set REDIS_DIR=%%~d

)






REM 停止已有 Bot 进程，避免 JAR 被锁

powershell -NoProfile -Command "$p=Get-CimInstance Win32_Process ^| Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*weather-wechat-bot-1.0.0.jar*' }; $p ^| ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>&1



REM --- 编译项目 ---

echo [0/2] 编译项目...

call mvn package -DskipTests -q

if errorlevel 1 (

    echo [错误] 编译失败，请检查代码

    pause

    exit /b 1

)

echo       编译完成 (target\weather-wechat-bot-1.0.0.jar)

echo.



REM --- 启动 Redis ---
echo [1/2] 启动 Redis...
set REDIS_RUNNING=0
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":6379" 2^>nul') do set REDIS_RUNNING=1
if !REDIS_RUNNING! equ 1 (
    echo       Redis 已在运行
    goto :skip_redis
)
if not defined REDIS_DIR (
    echo       [警告] 未找到 Redis，请设置 REDIS_HOME 环境变量或安装到默认路径
    echo       短期记忆将降级使用本地内存
    goto :skip_redis
)

start "Redis" /B /D "!REDIS_DIR!" "!REDIS_DIR!\redis-server.exe" "redis.conf"
echo       Redis 启动中...
set retry=0
:wait_redis
timeout /t 1 /nobreak >nul
"!REDIS_DIR!\redis-cli.exe" ping >nul 2>&1
if not errorlevel 1 goto :redis_ready
set /a retry+=1
if !retry! lss 15 goto :wait_redis
echo       [警告] Redis 启动超时
goto :skip_redis
:redis_ready
echo       Redis 已就绪
:skip_redis

:skip_redis



echo.









REM --- 启动 Java Bot ---

echo [2/2] 启动 MindPet Bot...

echo    (Playwright 浏览器由 Java 在应用启动时自动打开)

echo ========================================

echo.

java -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true -jar target\weather-wechat-bot-1.0.0.jar --mode=bot

set EXIT_CODE=!errorlevel!

echo.

echo ========================================

if !EXIT_CODE! equ 0 (

    echo   MindPet 已正常退出

) else (

    echo   MindPet 异常退出 (错误码: !EXIT_CODE!)

)

echo ========================================

pause

