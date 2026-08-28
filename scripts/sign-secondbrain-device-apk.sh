#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

EXPECTED_CERT_SHA256="fd402eefcec5b1576d6e7b1e5663a835d4c439d03baaa04506dd662e4b4c7d74"
SIGNING_DIR="${SECOND_BRAIN_SIGNING_DIR:-$HOME/.secondbrain-signing}"
KEYSTORE="${SECOND_BRAIN_KEYSTORE:-$SIGNING_DIR/second-brain-permanent.p12}"
PASSWORD_FILE="${SECOND_BRAIN_SIGNING_PASSWORD_FILE:-$SIGNING_DIR/password.txt}"
KEY_ALIAS="${SECOND_BRAIN_KEY_ALIAS:-secondbrain}"
INPUT_APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
OUTPUT_APK="${2:-/sdcard/Download/Second-Brain-device-permanent.apk}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[ -f "$KEYSTORE" ] || fail "Permanent keystore not found: $KEYSTORE"
[ -f "$PASSWORD_FILE" ] || fail "Signing password file not found: $PASSWORD_FILE"
[ -f "$INPUT_APK" ] || fail "Input APK not found: $INPUT_APK"

APKSIGNER="${APKSIGNER:-}"
if [ -z "$APKSIGNER" ] && [ -n "${ANDROID_HOME:-}" ]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
fi
if [ -z "$APKSIGNER" ]; then
  APKSIGNER="$(command -v apksigner 2>/dev/null || true)"
fi
[ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ] || fail "apksigner not found"

mkdir -p "$(dirname "$OUTPUT_APK")"
rm -f "$OUTPUT_APK" "$OUTPUT_APK.idsig"

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-type PKCS12 \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "file:$PASSWORD_FILE" \
  --key-pass "file:$PASSWORD_FILE" \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUTPUT_APK" \
  "$INPUT_APK"

VERIFY_OUTPUT="$($APKSIGNER verify --verbose --print-certs "$OUTPUT_APK")"
printf '%s\n' "$VERIFY_OUTPUT"

printf '%s\n' "$VERIFY_OUTPUT" | grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' \
  || fail "APK Signature Scheme v2 verification failed"
printf '%s\n' "$VERIFY_OUTPUT" | grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' \
  || fail "APK Signature Scheme v3 verification failed"

ACTUAL_CERT_SHA256="$(printf '%s\n' "$VERIFY_OUTPUT" \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
  | head -n1 \
  | tr -d ':' \
  | tr '[:upper:]' '[:lower:]')"

[ "$ACTUAL_CERT_SHA256" = "$EXPECTED_CERT_SHA256" ] \
  || fail "Unexpected signer. Expected $EXPECTED_CERT_SHA256, got ${ACTUAL_CERT_SHA256:-<missing>}"

echo "Permanent signer verified: $ACTUAL_CERT_SHA256"
echo "APK: $OUTPUT_APK"
sha256sum "$OUTPUT_APK"
