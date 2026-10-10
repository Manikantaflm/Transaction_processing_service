@echo off
setlocal
cd /d "%~dp0"
where docker >nul 2>nul || (echo Docker is required for Testcontainers integration tests.& exit /b 2)
docker info >nul 2>nul || (echo Docker Engine is not running.& exit /b 2)
call mvnw.cmd -f production-multinode/pom.xml clean verify
exit /b %ERRORLEVEL%
