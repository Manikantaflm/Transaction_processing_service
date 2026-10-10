#!/usr/bin/env sh
# Offline checks: compile both Java backends and run the dependency-free demo test suite.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/verification"
rm -rf "$BUILD"
mkdir -p "$BUILD/demo" "$BUILD/production"
find "$ROOT/src/main/java" "$ROOT/src/test/java" -name '*.java' -print > "$BUILD/demo-sources.txt"
javac --release 21 -Xlint:all -Werror -d "$BUILD/demo" @"$BUILD/demo-sources.txt"
java -ea -cp "$BUILD/demo" com.example.txn.SelfTest
find "$ROOT/production-multinode/src/main/java" -name '*.java' -print > "$BUILD/production-sources.txt"
javac --release 21 -Xlint:all -Werror -d "$BUILD/production" @"$BUILD/production-sources.txt"
echo "PASS: dependency-free demo tests passed and PostgreSQL production source compiled with -Werror."
echo "NOTE: this offline command does not execute PostgreSQL/Testcontainers integration tests."
