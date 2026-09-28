@echo off
setlocal
chcp 65001 >nul
title MindPet - local SQLite development

set "ROOT=%~dp0"
set "BACKEND_DIR=%ROOT%MindPet-java"
set "APP_DIR=%ROOT%MindPet"
set "EXIT_CODE=1"

echo ========================================
echo  MindPet - local SQLite development
echo  Workspace: %ROOT%
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
call mvn -DskipTests clean package
set "BUILD_EXIT=%ERRORLEVEL%"
popd
if not "%BUILD_EXIT%"=="0" (
    echo [ERROR] Backend build failed with exit code %BUILD_EXIT%.
    set "EXIT_CODE=%BUILD_EXIT%"
    goto :finish
)

if not exist "%APP_DIR%\node_modules" (
    echo [2/3] Installing frontend dependencies...
    pushd "%APP_DIR%"
    call npm ci
    set "INSTALL_EXIT=%ERRORLEVEL%"
    popd
    if not "%INSTALL_EXIT%"=="0" (
        echo [ERROR] Frontend dependency installation failed with exit code %INSTALL_EXIT%.
        set "EXIT_CODE=%INSTALL_EXIT%"
        goto :finish
    )
) else (
    echo [2/3] Frontend dependencies are present.
)

echo [3/3] Starting Electron. Logs will remain visible in this window.
pushd "%APP_DIR%"
call npm run dev
set "EXIT_CODE=%ERRORLEVEL%"
popd
if not "%EXIT_CODE%"=="0" echo [ERROR] Electron development process exited with code %EXIT_CODE%.
goto :finish

:missing_java
echo [ERROR] Java 21 JDK is required for source development.
goto :finish

:missing_maven
echo [ERROR] Maven is required to build the backend.
goto :finish

:missing_node
echo [ERROR] Node.js 20+ and npm are required for source development.
goto :finish

:finish
echo.
echo Launcher stopped with exit code %EXIT_CODE%.
echo Press any key to close this window.
pause >nul
exit /b %EXIT_CODE%
