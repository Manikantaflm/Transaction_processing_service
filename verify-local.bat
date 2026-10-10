@echo off
setlocal
set ROOT=%~dp0
set BUILD=%ROOT%build\verification
if exist "%BUILD%" rmdir /s /q "%BUILD%"
mkdir "%BUILD%\demo"
mkdir "%BUILD%\production"
for /r "%ROOT%src\main\java" %%f in (*.java) do echo %%f>>"%BUILD%\demo-sources.txt"
for /r "%ROOT%src\test\java" %%f in (*.java) do echo %%f>>"%BUILD%\demo-sources.txt"
javac --release 21 -Xlint:all -Werror -d "%BUILD%\demo" @"%BUILD%\demo-sources.txt"
if errorlevel 1 exit /b 1
java -ea -cp "%BUILD%\demo" com.example.txn.SelfTest
if errorlevel 1 exit /b 1
for /r "%ROOT%production-multinode\src\main\java" %%f in (*.java) do echo %%f>>"%BUILD%\production-sources.txt"
javac --release 21 -Xlint:all -Werror -d "%BUILD%\production" @"%BUILD%\production-sources.txt"
if errorlevel 1 exit /b 1
echo PASS: demo tests passed and PostgreSQL production source compiled with -Werror.
echo NOTE: PostgreSQL/Testcontainers integration tests were not executed by this offline command.
