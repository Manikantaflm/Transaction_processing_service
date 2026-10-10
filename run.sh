#!/usr/bin/env sh
# Production-oriented multi-instance local environment. Docker Engine + Compose required.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT"
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for the PostgreSQL multi-instance profile. Install Docker Desktop/Engine and retry." >&2
  exit 2
fi
if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example. Edit DB_PASSWORD, API_BEARER_TOKEN, and OPS_BEARER_TOKEN before running again." >&2
  exit 2
fi
if grep -Eq 'replace-with-|replace-this-' .env; then
  echo "Replace the placeholder secrets in .env before starting the stack." >&2
  exit 2
fi
exec docker compose up --build --remove-orphans
