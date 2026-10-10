#!/usr/bin/env sh
# Full production module verification: real PostgreSQL via Testcontainers, JUnit, Flyway migrations.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if ! command -v docker >/dev/null 2>&1; then echo "Docker is required for Testcontainers integration tests." >&2; exit 2; fi
docker info >/dev/null 2>&1 || { echo "Docker Engine is not running." >&2; exit 2; }
cd "$ROOT"
exec ./mvnw -f production-multinode/pom.xml clean verify
