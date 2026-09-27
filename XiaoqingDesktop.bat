@echo off
chcp 65001 >nul
setlocal

set "ROOT=%~dp0"
set "BACKEND_DIR=%ROOT%MindPet-java"
set "APP_DIR=%ROOT%MindPet"

echo ========================================
echo  MindPet - local SQLite development
echo ========================================
echo.

where java >nul 2>&1
if errorlevel 1 goto :missing_java
where mvn >nul 2>&1
if errorlevel 1 goto :missing_maven
where node >nul 2>&1
if errorlevel 1 goto :missing_node
where npm >nul 2>&1
if errorlevel 1 goto :missing_node

echo [1/3] Building the SQLite backend...
pushd "%BACKEND_DIR%"
call mvn -q -DskipTests package
set "BUILD_EXIT=%ERRORLEVEL%"
popd
if not "%BUILD_EXIT%"=="0" goto :build_failed

if not exist "%APP_DIR%\node_modules" (
    echo [2/3] Installing frontend dependencies...
    pushd "%APP_DIR%"
    call npm ci
    set "INSTALL_EXIT=%ERRORLEVEL%"
    popd
    if not "%INSTALL_EXIT%"=="0" goto :install_failed
) else (
    echo [2/3] Frontend dependencies are present.
)

echo [3/3] Starting Electron. It will start the Java backend automatically.
pushd "%APP_DIR%"
call npm run dev
set "APP_EXIT=%ERRORLEVEL%"
popd
exit /b %APP_EXIT%

:missing_java
echo [ERROR] Java 21 JDK is required for source development.
goto :failed

:missing_maven
echo [ERROR] Maven is required to build the backend.
goto :failed

:missing_node
echo [ERROR] Node.js 20+ and npm are required for source development.
goto :failed

:build_failed
echo [ERROR] Backend build failed.
goto :failed

:install_failed
echo [ERROR] Frontend dependency installation failed.
goto :failed

:failed
echo.
pause
exit /b 1
