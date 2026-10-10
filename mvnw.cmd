@echo off
setlocal EnableExtensions
set VERSION=3.9.11
if "%MAVEN_USER_HOME%"=="" set MAVEN_USER_HOME=%USERPROFILE%\.maven-bootstrap
set MAVEN_HOME=%MAVEN_USER_HOME%\apache-maven-%VERSION%
if exist "%MAVEN_HOME%\bin\mvn.cmd" goto runmaven
if not exist "%MAVEN_USER_HOME%" mkdir "%MAVEN_USER_HOME%"
echo Maven %VERSION% is not installed; downloading it to %MAVEN_HOME% ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://archive.apache.org/dist/maven/maven-3/%VERSION%/binaries/apache-maven-%VERSION%-bin.zip' -OutFile '%MAVEN_USER_HOME%\maven.zip'; Expand-Archive -Force '%MAVEN_USER_HOME%\maven.zip' '%MAVEN_USER_HOME%'; Remove-Item '%MAVEN_USER_HOME%\maven.zip'"
if errorlevel 1 exit /b 2
:runmaven
call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
