#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PACKAGE="com.kareem.secondbrain"
EXPECTED_CERT_SHA256="fd402eefcec5b1576d6e7b1e5663a835d4c439d03baaa04506dd662e4b4c7d74"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SIGNED_APK="${SECOND_BRAIN_DEVICE_APK:-/sdcard/Download/Cortex-Relay-v2.0.0-candidate2-permanent.apk}"
INSTALLED_COPY="${TMPDIR:-/data/data/com.termux/files/usr/tmp}/cortex-relay-installed-base.apk"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

need rish
need sed
need grep
need sha256sum
need bash

APKSIGNER="${APKSIGNER:-}"
if [ -z "$APKSIGNER" ] && [ -n "${ANDROID_HOME:-}" ]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
fi
if [ -z "$APKSIGNER" ]; then
  APKSIGNER="$(find "$HOME/android-sdk/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
fi
if [ -z "$APKSIGNER" ]; then
  APKSIGNER="$(command -v apksigner 2>/dev/null || true)"
fi
[ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ] || fail "apksigner not found"
export APKSIGNER

cert_of_apk() {
  "$APKSIGNER" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
    | head -n1 \
    | tr -d ':' \
    | tr '[:upper:]' '[:lower:]'
}

resolve_installed_path() {
  local output=""
  local pkg_path=""
  local attempt

  for attempt in 1 2 3; do
    output="$(rish -c "pm path $PACKAGE" 2>/dev/null || true)"
    pkg_path="$(printf '%s\n' "$output" \
      | tr -d '\r' \
      | sed -n 's/^package://p' \
      | head -n1)"
    [ -n "$pkg_path" ] && {
      printf '%s\n' "$pkg_path"
      return 0
    }
    sleep 1
  done

  output="$(rish -c "cmd package path $PACKAGE" 2>/dev/null || true)"
  pkg_path="$(printf '%s\n' "$output" \
    | tr -d '\r' \
    | sed -n 's/^package://p' \
    | head -n1)"
  [ -n "$pkg_path" ] || return 1
  printf '%s\n' "$pkg_path"
}

copy_installed_base() {
  local pkg_path
  pkg_path="$(resolve_installed_path || true)"
  [ -n "$pkg_path" ] || fail "$PACKAGE is not currently visible to PackageManager after retries"
  rm -f "$INSTALLED_COPY"
  rish -c "cat '$pkg_path'" > "$INSTALLED_COPY" || fail "Could not read installed base APK"
  [ -s "$INSTALLED_COPY" ] || fail "Installed APK copy is empty"
}

echo "==> Guard 1/5: verify currently installed permanent signer"
copy_installed_base
CURRENT_CERT="$(cert_of_apk "$INSTALLED_COPY")"
[ "$CURRENT_CERT" = "$EXPECTED_CERT_SHA256" ] \
  || fail "Installed signer mismatch. Expected $EXPECTED_CERT_SHA256, got ${CURRENT_CERT:-<missing>}. No install attempted."
echo "Installed signer OK: $CURRENT_CERT"

echo
echo "==> Build 2/5: Cortex Relay v2.0.0 combined candidate release"
cd "$ROOT_DIR"
./gradlew \
  -Dorg.gradle.jvmargs='-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' \
  :app:assembleRelease \
  --no-daemon \
  --no-configuration-cache \
  --max-workers=1 \
  --console=plain

UNSIGNED_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
if [ ! -f "$UNSIGNED_APK" ]; then
  UNSIGNED_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
fi
[ -f "$UNSIGNED_APK" ] || fail "Build completed but release APK not found"

echo
echo "==> Sign 3/5: permanent Cortex Relay identity"
bash "$ROOT_DIR/scripts/sign-secondbrain-device-apk.sh" "$UNSIGNED_APK" "$SIGNED_APK"
SIGNED_CERT="$(cert_of_apk "$SIGNED_APK")"
[ "$SIGNED_CERT" = "$EXPECTED_CERT_SHA256" ] || fail "Signed APK certificate verification failed"
echo "Signed APK signer OK: $SIGNED_CERT"

echo
echo "==> Install 4/5: update-in-place only (NO UNINSTALL)"
TMP_REMOTE="/data/local/tmp/Cortex-Relay-v2.0.0-candidate2-permanent.apk"
cat "$SIGNED_APK" | rish -c "cat > '$TMP_REMOTE'" || fail "Failed to stage APK in /data/local/tmp"
INSTALL_OUT="$(rish -c "chmod 644 '$TMP_REMOTE'; pm install -r '$TMP_REMOTE'; rc=\$?; rm -f '$TMP_REMOTE'; exit \$rc" 2>&1)" || {
  printf '%s\n' "$INSTALL_OUT"
  fail "Update-in-place failed. Existing installation was not uninstalled."
}
printf '%s\n' "$INSTALL_OUT"
printf '%s\n' "$INSTALL_OUT" | grep -q 'Success' || fail "Package manager did not report Success"

echo
echo "==> Verify 5/5: installed package + signer"
rish -c "dumpsys package $PACKAGE | grep -E 'versionCode=|versionName=' | head -n2"
copy_installed_base
FINAL_CERT="$(cert_of_apk "$INSTALLED_COPY")"
[ "$FINAL_CERT" = "$EXPECTED_CERT_SHA256" ] \
  || fail "Post-install signer mismatch: ${FINAL_CERT:-<missing>}"

rm -f "$INSTALLED_COPY"
echo "Installed signer OK: $FINAL_CERT"
echo "APK SHA-256: $(sha256sum "$SIGNED_APK" | awk '{print $1}')"
echo "APK: $SIGNED_APK"
echo
echo "CORTEX_RELAY_V2_CANDIDATE2_UPDATE_SUCCESS"
