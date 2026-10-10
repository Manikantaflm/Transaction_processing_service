#!/usr/bin/env sh
# Single-process, file-backed demo only; never use for multi-instance financial processing.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/demo"
mkdir -p "$BUILD/classes"
find "$ROOT/src/main/java" -name '*.java' -print > "$BUILD/sources.txt"
javac --release 21 -Xlint:all -Werror -d "$BUILD/classes" @"$BUILD/sources.txt"
exec java -cp "$BUILD/classes" com.example.txn.HttpApplication
