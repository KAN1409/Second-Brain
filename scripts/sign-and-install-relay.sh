#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_INPUT="${1:-$SCRIPT_DIR/Cortex-Relay-V1-UNSIGNED.apk}"
KEYSTORE="${SECOND_BRAIN_KEYSTORE:-$HOME/.secondbrain-signing/second-brain-permanent.p12}"
PASSWORD_FILE="${SECOND_BRAIN_PASSWORD_FILE:-$HOME/.secondbrain-signing/password.txt}"
ALIAS="secondbrain"
EXPECTED_CERT_SHA256="fd402eefcec5b1576d6e7b1e5663a835d4c439d03baaa04506dd662e4b4c7d74"
PACKAGE="com.kareem.secondbrain"
OUT_APK="/sdcard/Download/Cortex-Relay-v0.8.0-permanent.apk"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[ -f "$APK_INPUT" ] || fail "Input APK not found: $APK_INPUT"
[ -f "$KEYSTORE" ] || fail "Permanent keystore not found: $KEYSTORE"
[ -f "$PASSWORD_FILE" ] || fail "Signing password file not found: $PASSWORD_FILE"
[ -s "$PASSWORD_FILE" ] || fail "Signing password file is empty: $PASSWORD_FILE"

APKSIGNER="${APKSIGNER:-}"
if [ -z "$APKSIGNER" ] && [ -n "${ANDROID_HOME:-}" ]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
fi
if [ -z "$APKSIGNER" ]; then
  APKSIGNER="$(command -v apksigner 2>/dev/null || true)"
fi
[ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ] || fail "apksigner not found"

rm -f "$OUT_APK" "$OUT_APK.idsig"

TMP_KS_PASS="$(mktemp)"
TMP_KEY_PASS="$(mktemp)"
cleanup() {
  rm -f "$TMP_KS_PASS" "$TMP_KEY_PASS"
}
trap cleanup EXIT HUP INT TERM
chmod 600 "$TMP_KS_PASS" "$TMP_KEY_PASS"
PASSWORD_VALUE="$(tr -d '\r\n' < "$PASSWORD_FILE")"
[ -n "$PASSWORD_VALUE" ] || fail "Signing password is empty after normalization"
printf '%s\n' "$PASSWORD_VALUE" > "$TMP_KS_PASS"
printf '%s\n' "$PASSWORD_VALUE" > "$TMP_KEY_PASS"
unset PASSWORD_VALUE

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-type PKCS12 \
  --ks-key-alias "$ALIAS" \
  --ks-pass "file:$TMP_KS_PASS" \
  --key-pass "file:$TMP_KEY_PASS" \
  --min-sdk-version 24 \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUT_APK" \
  "$APK_INPUT"

VERIFY_OUTPUT="$($APKSIGNER verify --verbose --print-certs --min-sdk-version 24 "$OUT_APK")"
printf '%s\n' "$VERIFY_OUTPUT"

printf '%s\n' "$VERIFY_OUTPUT" | grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' \
  || fail "APK Signature Scheme v2 verification failed"
printf '%s\n' "$VERIFY_OUTPUT" | grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' \
  || fail "APK Signature Scheme v3 verification failed"

ACTUAL_CERT_SHA256="$(printf '%s\n' "$VERIFY_OUTPUT" \
  | sed -n -e 's/^Signer #1 certificate SHA-256 digest: //p' -e 's/^V3\.0 Signer: certificate SHA-256 digest: //p' \
  | head -n1 \
  | tr -d ':[:space:]' \
  | tr '[:upper:]' '[:lower:]')"

[ "$ACTUAL_CERT_SHA256" = "$EXPECTED_CERT_SHA256" ] \
  || fail "Unexpected signer. Expected $EXPECTED_CERT_SHA256, got ${ACTUAL_CERT_SHA256:-<missing>}"

echo "Permanent signer verified: $ACTUAL_CERT_SHA256"
echo "Signed APK: $OUT_APK"
sha256sum "$OUT_APK"

if command -v rish >/dev/null 2>&1; then
  echo "Installing update-in-place through /data/local/tmp..."
  cat "$OUT_APK" | rish -c '
TMP=/data/local/tmp/Cortex-Relay.apk
cat > "$TMP"
chmod 644 "$TMP"
pm install -r "$TMP"
RC=$?
rm -f "$TMP"
exit $RC
'
  echo "INSTALL_OK"
  rish -c "dumpsys package $PACKAGE | grep -E 'versionCode=|versionName=' | head -n 4" || true
else
  echo "rish not found; signed APK is ready at: $OUT_APK"
fi
