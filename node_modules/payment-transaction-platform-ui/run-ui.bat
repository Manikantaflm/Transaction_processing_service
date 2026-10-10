@echo off
cd /d "%~dp0"
if not exist node_modules (
  echo Installing UI dependencies...
  call npm install || exit /b 1
)
call npm run dev
