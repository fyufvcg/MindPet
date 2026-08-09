@echo off

cd /d "%~dp0"

setlocal enabledelayedexpansion



echo ========================================

echo   MindPet - 启动脚本

echo ========================================

echo.



REM 检查 Java 21+

echo [检查] Java...

java -version >nul 2>&1

if errorlevel 1 (

    echo [错误] 未找到 Java，请安装 JDK 21+

    pause

    exit /b 1

)

echo        Java 已就绪



REM 检查 Maven

echo [检查] Maven...

mvn -version >nul 2>&1

if errorlevel 1 (

    echo [错误] 未找到 Maven，请安装 Maven 3.6+

    pause

    exit /b 1

)

echo        Maven 已就绪



REM === 自动检测 Redis 安装位置 ===

set REDIS_DIR=

if defined REDIS_HOME if exist "%REDIS_HOME%\redis-server.exe" set REDIS_DIR=%REDIS_HOME%

if not defined REDIS_DIR (

    for /f "delims=" %%i in ('where redis-cli 2^>nul') do (

        if exist "%%~dpi..\redis-server.exe" set REDIS_DIR=%%~dpi..

        if exist "%%~dpiredis-server.exe"  set REDIS_DIR=%%~dpi

    )

)

if not defined REDIS_DIR (

    for %%d in (

        "C:\Program Files\Redis"

        "D:\youkeda\Redis-8.8.0"

        "D:\Redis"

        "%USERPROFILE%\Redis"

    ) do if not defined REDIS_DIR if exist "%%~d\redis-server.exe" set REDIS_DIR=%%~d

)



REM === 启动 Redis ===

echo [检查] Redis...

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":6379" 2^>nul') do set REDIS_OK=1

if "!REDIS_OK!"=="1" goto :redis_ok

if not defined REDIS_DIR (

    echo        [提示] 未找到 Redis，短期记忆将使用本地内存

    echo        如需 Redis，请设置 REDIS_HOME 环境变量或安装到默认路径

    goto :redis_ok

)

start "Redis" /B /D "!REDIS_DIR!" "!REDIS_DIR!\redis-server.exe" "redis.conf"

timeout /t 2 /nobreak >nul

echo        Redis 已启动

:redis_ok



echo.

echo [编译] 正在编译项目...

call mvn compile -q

if errorlevel 1 (

    echo [错误] 编译失败

    pause

    exit /b 1

)

echo        编译完成



echo.

echo [启动] 正在启动服务 (port 8080)...

echo ========================================

echo.

mvn exec:java "-Dexec.args="

echo.

echo ========================================

echo   MindPet 已退出

echo ========================================

pause

