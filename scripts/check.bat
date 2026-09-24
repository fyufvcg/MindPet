@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  MindPet deployment verification
REM ============================================================
REM  Checks, in order:
REM    1. containers running
REM    2. backend HTTP health
REM    3. PostgreSQL: required tables + vector dimension
REM    4. Redis: PING
REM    5. Embedding: active provider + real 1024-dim vector
REM    6. LLM responds
REM
REM  Step 5 matters most: the backend silently degrades to
REM  "no memory" if Embedding is unreachable, and still reports
REM  itself as healthy. Never trust the health endpoint alone.
REM ============================================================

cd /d "%~dp0.."
set PASS=0
set FAIL=0
set COMPOSE=docker compose -f docker\compose.yaml

echo ========================================
echo  MindPet verification
echo ========================================
echo.

REM ---------- 1. containers ----------
echo [1/6] Container status
for %%S in (postgres redis backend) do (
    for /f "delims=" %%T in ('docker inspect --format="{{.State.Status}}" mindpet-%%S 2^>nul') do set ST=%%T
    if /i "!ST!"=="running" (
        echo    OK    mindpet-%%S = running
        set /a PASS+=1
    ) else (
        echo    FAIL  mindpet-%%S = !ST!
        set /a FAIL+=1
    )
    set ST=
)
echo.

REM ---------- 2. backend health ----------
echo [2/6] Backend HTTP health
set HEALTH_BODY=
for /f "delims=" %%R in ('powershell -NoProfile -Command "try{(Invoke-WebRequest -Uri 'http://localhost:8080/api/desktop/health' -TimeoutSec 8 -UseBasicParsing).Content}catch{''}"') do set HEALTH_BODY=%%R
echo !HEALTH_BODY! | findstr /c:"mindpet-desktop-api" >nul 2>&1
if errorlevel 1 (
    echo    FAIL  no valid response from /api/desktop/health
    echo          body: !HEALTH_BODY!
    set /a FAIL+=1
) else (
    echo    OK    !HEALTH_BODY!
    set /a PASS+=1
)
echo.

REM ---------- 3. database schema ----------
echo [3/6] PostgreSQL tables and vector dimension
set TABLES=llm_growth,long_term_memory,user_insight,user_profile
docker exec mindpet-postgres psql -U postgres -d mindpet -tAc "SELECT string_agg(tablename,',' ORDER BY tablename) FROM pg_tables WHERE schemaname='public' AND tablename IN ('long_term_memory','user_profile','user_insight','llm_growth');" >"%TEMP%\mp_tables.txt" 2>nul
set FOUND=
for /f "delims=" %%T in ('type "%TEMP%\mp_tables.txt" 2^>nul') do set FOUND=%%T
if "!FOUND!"=="%TABLES%" (
    echo    OK    all required tables present
    set /a PASS+=1
) else (
    echo    FAIL  tables mismatch
    echo          expected: %TABLES%
    echo          found   : !FOUND!
    set /a FAIL+=1
)

REM vector dimension must match the embedding model (1024)
docker exec mindpet-postgres psql -U postgres -d mindpet -tAc "SELECT atttypmod FROM pg_attribute WHERE attrelid='long_term_memory'::regclass AND attname='embedding';" >"%TEMP%\mp_dim.txt" 2>nul
set DIM=
for /f "delims=" %%D in ('type "%TEMP%\mp_dim.txt" 2^>nul') do set DIM=%%D
if "!DIM!"=="1024" (
    echo    OK    long_term_memory.embedding = vector^(1024^)
    set /a PASS+=1
) else (
    echo    FAIL  embedding dimension is "!DIM!", expected 1024
    set /a FAIL+=1
)
echo.

REM ---------- 4. redis ----------
echo [4/6] Redis
set PONG=
for /f "delims=" %%P in ('docker exec mindpet-redis redis-cli ping 2^>nul') do set PONG=%%P
if /i "!PONG!"=="PONG" (
    echo    OK    PING ^-^> PONG
    set /a PASS+=1
) else (
    echo    FAIL  unexpected reply: !PONG!
    set /a FAIL+=1
)
echo.

REM ---------- 5. embedding ----------
echo [5/6] Embedding service
echo    ^(if this fails the backend still looks healthy, but long-term memory is dead^)
set EMB_STATUS=
for /f "delims=" %%S in ('powershell -NoProfile -Command "try{(Invoke-WebRequest -Uri 'http://localhost:8080/api/desktop/embedding-status' -TimeoutSec 20 -UseBasicParsing).Content}catch{''}"') do set EMB_STATUS=%%S
echo    provider: !EMB_STATUS!
set EMB_BODY=
for /f "delims=" %%E in ('powershell -NoProfile -Command "try{(Invoke-WebRequest -Uri 'http://localhost:8080/api/desktop/embedding-test' -Method POST -ContentType 'application/json' -Body '{}' -TimeoutSec 90 -UseBasicParsing).Content}catch{'REQUEST_FAILED'}"') do set EMB_BODY=%%E
echo !EMB_BODY! | findstr /c:"\"ok\":true" >nul 2>&1
if errorlevel 1 (
    echo    FAIL  !EMB_BODY!
    set /a FAIL+=1
) else (
    echo    OK    !EMB_BODY!
    set /a PASS+=1
)
echo.

REM ---------- 6. LLM ----------
echo [6/6] LLM service
set LLM_BODY=
for /f "delims=" %%L in ('powershell -NoProfile -Command "try{(Invoke-WebRequest -Uri 'http://localhost:8080/api/desktop/llm-test' -Method POST -ContentType 'application/json' -Body '{}' -TimeoutSec 90 -UseBasicParsing).Content}catch{'REQUEST_FAILED'}"') do set LLM_BODY=%%L
echo !LLM_BODY! | findstr /c:"\"ok\":true" >nul 2>&1
if errorlevel 1 (
    echo    FAIL  !LLM_BODY!
    set /a FAIL+=1
) else (
    echo    OK    !LLM_BODY!
    set /a PASS+=1
)
echo.

REM ---------- summary ----------
echo ========================================
echo  Result:  !PASS! passed,  !FAIL! failed
echo ========================================
if !FAIL! gtr 0 (
    echo.
    echo  Troubleshooting:
    echo    docker logs mindpet-backend  --tail 100
    echo    docker logs mindpet-postgres --tail 50
    echo    docker logs mindpet-ollama   --tail 50   ^(local mode only^)
    echo.
    echo  If step 5 failed:
    echo    - AUTO/DOUBAO : set the Doubao Embedding key in Settings - Embedding,
    echo                    or fill DOUBAO_EMBEDDING_API_KEY in docker\.env
    echo    - OLLAMA mode : docker exec mindpet-ollama ollama pull bge-m3
    echo    Tip: open Settings - Embedding to see active provider and reason.
    exit /b 1
)
echo.
echo  All good. Launch the MindPet desktop client and it will
echo  connect to http://localhost:8080 automatically.
exit /b 0
