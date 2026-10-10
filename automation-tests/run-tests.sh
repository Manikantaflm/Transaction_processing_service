#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BASE_URL=${BASE_URL:-http://localhost:8080}
curl --fail --silent "${BASE_URL%/}/health/live" >/dev/null || { echo "Start the Compose stack first; service not reachable at $BASE_URL" >&2; exit 2; }
exec "$ROOT/../mvnw" -f "$ROOT/pom.xml" clean test -Dheadless=true -DbaseUrl="${BASE_URL%/}"
