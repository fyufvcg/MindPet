@echo off
chcp 65001 >nul
setlocal

cd /d "%~dp0"
set "MINDPET_DATA_DIR=%~dp0..\data\desktop"

echo ========================================
echo  MindPet - SQLite local backend
echo ========================================
echo Data: %MINDPET_DATA_DIR%\mindpet.db
echo.

java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] JDK 21 or newer is required for source development.
    pause
    exit /b 1
)

call mvn -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Maven is required to build the backend.
    pause
    exit /b 1
)

if not exist "%MINDPET_DATA_DIR%" mkdir "%MINDPET_DATA_DIR%"

echo [1/2] Building backend...
call mvn -q -DskipTests package
if errorlevel 1 (
    echo [ERROR] Maven package failed.
    pause
    exit /b 1
)

echo [2/2] Starting SQLite-backed MindPet backend on port 8080...
pushd "%MINDPET_DATA_DIR%"
java -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true -jar "%~dp0target\weather-wechat-bot-1.0.0.jar" --mode=bot --spring.config.location=classpath:/application-desktop.yml --app.storage.sqlite.path="%MINDPET_DATA_DIR%\mindpet.db" --server.port=8080
set "EXIT_CODE=%ERRORLEVEL%"
popd

echo.
echo MindPet backend exited with code %EXIT_CODE%.
pause
exit /b %EXIT_CODE%
