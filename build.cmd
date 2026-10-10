@echo off
rem Compiles the application into out\ and packs rdm.jar (double-click it, or: java -jar rdm.jar).
setlocal
cd /d "%~dp0"
if exist out\classes rmdir /s /q out\classes
mkdir out\classes
dir /s /b src\*.java > out\sources.txt
javac -encoding UTF-8 --release 21 -d out\classes @out\sources.txt || exit /b 1
rem the Oracle installer only puts java and javac on the PATH: find jar in the JDK itself
set JAR=jar
where jar >nul 2>nul || (
  for /f "tokens=2 delims==" %%h in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set JH=%%h
)
if defined JH set JAR="%JH:~1%\bin\jar.exe"
%JAR% --create --file rdm.jar --main-class rdm.App -C out\classes . || exit /b 1
echo rdm.jar built.
