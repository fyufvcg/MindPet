@echo off
chcp 65001 >nul
call "%~dp0..\MindPet-java\start.bat" %*
exit /b %ERRORLEVEL%
