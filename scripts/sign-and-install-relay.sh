#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

EXPECTED_CERT_SHA256="fd402eefcec5b1576d6e7b1e5663a835d4c439d03baaa04506dd662e4b4c7d74"
KEYSTORE="${SECOND_BRAIN_KEYSTORE:-$HOME/.secondbrain-signing/second-brain-permanent.p12}"
PASSWORD_FILE="${SECOND_BRAIN_PASSWORD_FILE:-$HOME/.secondbrain-signing/password.txt}"
ALIAS="secondbrain"
PACKAGE="com.kareem.secondbrain"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_INPUT="${1:-$SCRIPT_DIR/Cortex-Relay-V1-UNSIGNED.apk}"
OUT_APK="$HOME/storage/downloads/Cortex-Relay-V1-v0.6.0-permanent.apk"

fail() { echo "ERROR: $*" >&2; exit 1; }

[[ -f "$APK_INPUT" ]] || fail "Unsigned APK not found: $APK_INPUT"
[[ -f "$KEYSTORE" ]] || fail "Permanent keystore not found: $KEYSTORE"
[[ -f "$PASSWORD_FILE" ]] || fail "Signing password file not found: $PASSWORD_FILE"

find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return
  fi
  find \
    "$PREFIX" \
    "${ANDROID_HOME:-$HOME/android-sdk}" \
    "$HOME" \
    -type f -name apksigner 2>/dev/null | sort -V | tail -n 1
}

APKSIGNER="$(find_apksigner)"
[[ -n "$APKSIGNER" && -x "$APKSIGNER" ]] || fail "apksigner not found"

KS_PASS="$(cat "$PASSWORD_FILE")"
cp -f "$APK_INPUT" "$OUT_APK"

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-type PKCS12 \
  --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  "$OUT_APK"
unset KS_PASS

VERIFY_OUTPUT="$("$APKSIGNER" verify --verbose --print-certs "$OUT_APK")"
printf '%s\n' "$VERIFY_OUTPUT"

ACTUAL_CERT_SHA256="$(printf '%s\n' "$VERIFY_OUTPUT" | sed -n 's/.*certificate SHA-256 digest: //p' | head -n1 | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
[[ "$ACTUAL_CERT_SHA256" == "$EXPECTED_CERT_SHA256" ]] || fail "Signer mismatch. Expected $EXPECTED_CERT_SHA256, got $ACTUAL_CERT_SHA256"
printf '%s\n' "$VERIFY_OUTPUT" | grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' || fail "APK v2 signature missing"
printf '%s\n' "$VERIFY_OUTPUT" | grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' || fail "APK v3 signature missing"

echo "SIGNER_OK"
echo "Signed APK: $OUT_APK"

command -v rish >/dev/null 2>&1 || fail "rish not found. Start Shizuku and make sure rish is available in Termux."

echo "Installing update-in-place (NO UNINSTALL)..."
cat "$OUT_APK" | rish -c '
TMP=/data/local/tmp/Cortex-Relay-v0.6.0.apk
cat > "$TMP"
chmod 644 "$TMP"
pm install -r "$TMP"
RC=$?
rm -f "$TMP"
exit $RC
'

echo "INSTALL_OK"
rish -c "dumpsys package $PACKAGE | grep -m1 -E 'versionCode=|versionName='" || true
