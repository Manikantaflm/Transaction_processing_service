@echo off
setlocal
cd /d "%~dp0"
where docker >nul 2>nul
if errorlevel 1 (
  echo Docker is required for the PostgreSQL multi-instance profile. Install Docker Desktop and retry.
  exit /b 2
)
if not exist .env (
  copy .env.example .env >nul
  echo Created .env from .env.example. Edit DB_PASSWORD, API_BEARER_TOKEN, and OPS_BEARER_TOKEN before running again.
  exit /b 2
)
findstr /c:"replace-with-" /c:"replace-this-" .env >nul
if not errorlevel 1 (
  echo Replace the placeholder secrets in .env before starting the stack.
  exit /b 2
)
docker compose up --build --remove-orphans
