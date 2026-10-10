#!/usr/bin/env sh
# Small Maven bootstrap for contributors who do not have Maven installed globally.
# Requires JDK 21+ and network access for the first Maven/dependency download.
set -eu
VERSION=3.9.11
BASE="${MAVEN_USER_HOME:-${HOME}/.maven-bootstrap}"
HOME_DIR="$BASE/apache-maven-$VERSION"
if [ ! -x "$HOME_DIR/bin/mvn" ]; then
  mkdir -p "$BASE"
  TMP="$BASE/apache-maven-$VERSION.tar.gz.tmp"
  URL="https://archive.apache.org/dist/maven/maven-3/$VERSION/binaries/apache-maven-$VERSION-bin.tar.gz"
  echo "Maven $VERSION is not installed; downloading it to $HOME_DIR ..." >&2
  if command -v curl >/dev/null 2>&1; then curl --fail --location --silent --show-error "$URL" -o "$TMP"
  elif command -v wget >/dev/null 2>&1; then wget -O "$TMP" "$URL"
  else echo "Install curl or wget to bootstrap Maven." >&2; exit 2; fi
  tar -xzf "$TMP" -C "$BASE"
  rm -f "$TMP"
fi
exec "$HOME_DIR/bin/mvn" "$@"
