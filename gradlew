#!/usr/bin/env sh
set -eu
VERSION=9.5.0
SHA256=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/second-brain-bootstrap"
ZIP="$BASE/gradle-$VERSION-bin.zip"
HOME_DIR="$BASE/gradle-$VERSION"
if [ ! -x "$HOME_DIR/bin/gradle" ]; then
  mkdir -p "$BASE"
  if [ ! -f "$ZIP" ]; then
    URL="https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$URL" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "$URL"
    else
      echo "curl or wget is required to bootstrap Gradle $VERSION" >&2
      exit 1
    fi
  fi
  ACTUAL=$(sha256sum "$ZIP" | awk '{print $1}')
  [ "$ACTUAL" = "$SHA256" ] || { echo "Gradle checksum mismatch" >&2; rm -f "$ZIP"; exit 1; }
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required" >&2; exit 1; }
  unzip -q -o "$ZIP" -d "$BASE"
fi
exec "$HOME_DIR/bin/gradle" "$@"
