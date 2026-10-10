@echo off
rem Builds and starts the application.
call "%~dp0build.cmd" || exit /b 1
start "" javaw -jar "%~dp0rdm.jar"
