@echo off
chcp 65001 >nul
call "%~dp0MindPet-java\start.bat" %*
exit /b %ERRORLEVEL%
