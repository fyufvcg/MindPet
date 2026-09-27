@echo off

cd /d "%~dp0MindPet-java"

setlocal enabledelayedexpansion



echo ========================================

echo   MindPet - Bot ��������

echo ========================================

echo.



REM === �Զ���� Redis ��װλ�� ===

set REDIS_DIR=

REM 1) ��黷������ REDIS_HOME

if defined REDIS_HOME if exist "%REDIS_HOME%\redis-server.exe" set REDIS_DIR=%REDIS_HOME%

REM 2) ��� redis-cli �Ƿ��� PATH ��

if not defined REDIS_DIR (

    for /f "delims=" %%i in ('where redis-cli 2^>nul') do (

        if exist "%%~dpi..\redis-server.exe" set REDIS_DIR=%%~dpi..

        if exist "%%~dpiredis-server.exe"  set REDIS_DIR=%%~dpi

    )

)

REM 3) ������װ·��

if not defined REDIS_DIR (

    for %%d in (

        "C:\Program Files\Redis"

        "D:\youkeda\Redis-8.8.0"

        "D:\Redis"

        "%USERPROFILE%\Redis"

    ) do if not defined REDIS_DIR if exist "%%~d\redis-server.exe" set REDIS_DIR=%%~d

)






REM ֹͣ���� Bot ���̣����� JAR ����

powershell -NoProfile -Command "$p=Get-CimInstance Win32_Process ^| Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*weather-wechat-bot-1.0.0.jar*' }; $p ^| ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >nul 2>&1



REM --- ������Ŀ ---

echo [0/2] ������Ŀ...

call mvn package -DskipTests -q

if errorlevel 1 (

    echo [����] ����ʧ�ܣ��������

    pause

    exit /b 1

)

echo       ������� (target\weather-wechat-bot-1.0.0.jar)

echo.



REM --- ���� Redis ---
echo [1/2] ���� Redis...
set REDIS_RUNNING=0
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":6379" 2^>nul') do set REDIS_RUNNING=1
if !REDIS_RUNNING! equ 1 (
    echo       Redis ��������
    goto :skip_redis
)
if not defined REDIS_DIR (
    echo       [����] δ�ҵ� Redis�������� REDIS_HOME ����������װ��Ĭ��·��
    echo       ���ڼ��佫����ʹ�ñ����ڴ�
    goto :skip_redis
)

start "Redis" /B /D "!REDIS_DIR!" "!REDIS_DIR!\redis-server.exe" "redis.conf"
echo       Redis ������...
set retry=0
:wait_redis
timeout /t 1 /nobreak >nul
"!REDIS_DIR!\redis-cli.exe" ping >nul 2>&1
if not errorlevel 1 goto :redis_ready
set /a retry+=1
if !retry! lss 15 goto :wait_redis
echo       [����] Redis ������ʱ
goto :skip_redis
:redis_ready
echo       Redis �Ѿ���
:skip_redis

:skip_redis



echo.









REM --- ���� Java Bot ---

echo [2/2] ���� MindPet Bot...

echo    (Playwright ������� Java ��Ӧ������ʱ�Զ���)

echo ========================================

echo.

java -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true -jar target\weather-wechat-bot-1.0.0.jar --mode=bot

set EXIT_CODE=!errorlevel!

echo.

echo ========================================

if !EXIT_CODE! equ 0 (

    echo   MindPet �������˳�

) else (

    echo   MindPet �쳣�˳� (������: !EXIT_CODE!)

)

echo ========================================

pause
