@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM  Stop MindPet backend infrastructure
REM ============================================================
REM  Usage:
REM    scripts\stop.bat            stop containers, KEEP all data
REM    scripts\stop.bat --purge    stop AND delete data volumes
REM
REM  --purge erases PostgreSQL data, Redis data and the
REM  downloaded Ollama model. It cannot be undone.
REM ============================================================

cd /d "%~dp0.."
set COMPOSE=docker compose -f docker\compose.yaml

if /i "%1"=="--purge" goto purge

echo Stopping MindPet containers (data preserved)...
%COMPOSE% --profile local-embedding down
if errorlevel 1 (
    echo [ERROR] stop failed.
    exit /b 1
)
echo.
echo   Stopped. Data volumes kept: pgdata, redisdata, ollama, backenddata
echo   Restart with: scripts\start.bat
exit /b 0

:purge
echo ========================================
echo  WARNING: this deletes ALL MindPet data
echo ========================================
echo   - PostgreSQL (long-term memory, profiles, knowledge graph)
echo   - Redis (short-term context)
echo   - Ollama models (1.1GB, needs re-pull)
set /p CONFIRM=Type YES to confirm: 
if /i not "%CONFIRM%"=="YES" (
    echo Aborted. Nothing was deleted.
    exit /b 0
)
%COMPOSE% --profile local-embedding down -v
if errorlevel 1 (
    echo [ERROR] purge failed.
    exit /b 1
)
echo.
echo   All containers and volumes removed.
exit /b 0
