@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  Build MindPet backend jar into the Docker build context
REM ============================================================
REM  Usage:  scripts\build-backend.bat
REM
REM  Requires JDK 21 + Maven. Judges do NOT need this step --
REM  the prebuilt jar ships with the release package.
REM ============================================================

cd /d "%~dp0.."

echo ========================================
echo  [1/2] Building backend jar (mvn package)
echo ========================================
cd MindPet-java
REM 不执行 clean：全量重编会重新下载并解析全部依赖，耗时且易受网络影响。
REM 需要彻底重编时手动执行：mvn clean package
call mvn package -DskipTests
if errorlevel 1 (
    echo.
    echo [ERROR] mvn package failed.
    exit /b 1
)

set JAR=target\weather-wechat-bot-1.0.0.jar
if not exist "%JAR%" (
    echo [ERROR] jar not found: MindPet-java\%JAR%
    exit /b 1
)

echo.
echo ========================================
echo  [2/2] Copying jar to docker build context
echo ========================================
copy /y "%JAR%" "..\docker\backend\weather-wechat-bot-1.0.0.jar" >nul
if errorlevel 1 (
    echo [ERROR] copy failed.
    exit /b 1
)

for %%A in ("..\docker\backend\weather-wechat-bot-1.0.0.jar") do set SIZE=%%~zA
set /a SIZE_MB=!SIZE!/1048576

echo   OK  docker\backend\weather-wechat-bot-1.0.0.jar  (!SIZE_MB! MB)
echo.
echo Next step:
echo   copy docker\.env.example docker\.env    ^(then edit it^)
echo   scripts\start.bat
exit /b 0
