@echo off
rem Compiles the solver with the verification suite and runs it (exit code 1 on a failure).
setlocal
cd /d "%~dp0"
if exist out\test rmdir /s /q out\test
mkdir out\test
dir /s /b src\*.java test\*.java > out\test-sources.txt
javac -encoding UTF-8 --release 21 -d out\test @out\test-sources.txt || exit /b 1
chcp 65001 >nul
java -Dstdout.encoding=UTF-8 -cp out\test rdm.Verify
exit /b %errorlevel%
