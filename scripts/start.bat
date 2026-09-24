@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  Start MindPet backend infrastructure (Docker Compose)
REM ============================================================
REM  Usage:
REM    scripts\start.bat                  cloud mode (no Ollama)
REM    scripts\start.bat local            local privacy mode (+Ollama)
REM ============================================================

cd /d "%~dp0.."

set COMPOSE=docker compose -f docker\compose.yaml
set MODE=%1

echo ========================================
echo  MindPet backend - environment check
echo ========================================
echo.

REM ---- 1. docker available? ----
where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] docker not found in PATH.
    echo         Install Docker Desktop first: https://www.docker.com/products/docker-desktop/
    exit /b 1
)
echo   OK  docker found

REM ---- 2. docker daemon running? ----
docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker daemon is not running. Start Docker Desktop and retry.
    exit /b 1
)
echo   OK  docker daemon running

REM ---- 3. .env present? ----
if not exist "docker\.env" (
    echo [ERROR] docker\.env not found.
    echo         Run:  copy docker\.env.example docker\.env
    echo         Then edit it and fill in POSTGRES_PASSWORD / LLM_API_KEY.
    exit /b 1
)
echo   OK  docker\.env present

REM ---- 4. jar present? ----
if not exist "docker\backend\weather-wechat-bot-1.0.0.jar" (
    echo [ERROR] backend jar not found in docker\backend\
    echo         Run:  scripts\build-backend.bat
    exit /b 1
)
echo   OK  backend jar present

REM ---- 5. port 8080 free? ----
netstat -ano | findstr ":8080 " | findstr LISTENING >nul 2>&1
if not errorlevel 1 (
    echo [WARN] port 8080 is already in use. If an old MindPet backend is running,
    echo        stop it first, otherwise the container will fail to bind.
)

echo.
echo ========================================
if /i "%MODE%"=="local" (
    echo  Starting LOCAL PRIVACY mode ^(with Ollama^)
    echo ========================================
    %COMPOSE% --profile local-embedding up -d --build
) else (
    echo  Starting CLOUD mode ^(no Ollama^)
    echo ========================================
    %COMPOSE% up -d --build
)
if errorlevel 1 (
    echo.
    echo [ERROR] docker compose up failed. See the output above.
    exit /b 1
)

echo.
echo ========================================
echo  Waiting for services to become healthy
echo ========================================
set /a WAIT=0
:wait_loop
timeout /t 3 /nobreak >nul
set /a WAIT+=3
for /f "delims=" %%H in ('docker inspect --format="{{.State.Health.Status}}" mindpet-backend 2^>nul') do set HEALTH=%%H
if /i "!HEALTH!"=="healthy" goto ready
if !WAIT! geq 120 goto timeout
echo   ... !WAIT!s  backend=!HEALTH!
goto wait_loop

:ready
echo.
echo   OK  backend is healthy
echo.
if /i "%MODE%"=="local" (
    echo ========================================
    echo  NOTE: local privacy mode needs the model
    echo ========================================
    docker exec mindpet-ollama ollama list 2>nul | findstr "bge-m3" >nul 2>&1
    if errorlevel 1 (
        echo   Model bge-m3 not found. Pulling now ^(about 1.1GB, one time^)...
        docker exec mindpet-ollama ollama pull bge-m3
    ) else (
        echo   OK  model bge-m3 already present
    )
    echo.
)
echo ========================================
echo  MindPet backend started
echo ========================================
echo   Backend : http://localhost:8080
echo   Health  : http://localhost:8080/api/desktop/health
echo.
echo   Next: run scripts\check.bat to verify (including Embedding).
echo         Then launch the MindPet desktop client.
exit /b 0

:timeout
echo.
echo [WARN] backend did not report healthy within 120s.
echo        Inspect logs with:  docker logs mindpet-backend --tail 100
echo        Then run:           scripts\check.bat
exit /b 1
