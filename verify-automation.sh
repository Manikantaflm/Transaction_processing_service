#!/usr/bin/env sh
# Requires the service to be running and Chrome/Chromium available. Dependencies bootstrap on first run.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BASE_URL=${BASE_URL:-http://localhost:8080}
BASE_URL=${BASE_URL%/}
curl --fail --silent "$BASE_URL/health/live" >/dev/null || { echo "Service is not reachable at $BASE_URL" >&2; exit 2; }
cd "$ROOT"
exec ./mvnw -f automation-tests/pom.xml clean test -DbaseUrl="$BASE_URL" -Dheadless=true
