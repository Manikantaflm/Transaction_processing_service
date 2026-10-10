@echo off
setlocal
set ROOT=%~dp0
set BUILD=%ROOT%build\demo
if not exist "%BUILD%\classes" mkdir "%BUILD%\classes"
dir /s /b "%ROOT%src\main\java\*.java" > "%BUILD%\sources.txt"
javac --release 21 -Xlint:all -Werror -d "%BUILD%\classes" @"%BUILD%\sources.txt"
if errorlevel 1 exit /b 1
java -cp "%BUILD%\classes" com.example.txn.HttpApplication
