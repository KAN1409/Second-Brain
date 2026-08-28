#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

APK_INPUT="${1:-}"
KEYSTORE="${SECOND_BRAIN_KEYSTORE:-$HOME/.secondbrain-signing/second-brain-permanent.p12}"
ALIAS="secondbrain"
EXPECTED_CERT_SHA256="fd402eefcec5b"
PACKAGE="com.kareem.secondbrain"
OUT_DIR="${HOME}/storage/downloads"
OUT_APK="${OUT_DIR}/Cortex-Relay-update.apk"

if [[ -z "$APK_INPUT" || ! -f "$APK_INPUT" ]]; then
  echo "Usage: $0 /path/to/app-release-unsigned.apk"
  exit 2
fi

if [[ ! -f "$KEYSTORE" ]]; then
  echo "Permanent keystore not found: $KEYSTORE"
  exit 3
fi

find_tool() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return 0
  fi
  local candidate
  candidate="$(find "${ANDROID_HOME:-$HOME/android-sdk}" -type f -name "$name" 2>/dev/null | sort -V | tail -n 1 || true)"
  [[ -n "$candidate" ]] || return 1
  printf '%s\n' "$candidate"
}

APKSIGNER="$(find_tool apksigner || true)"
if [[ -z "$APKSIGNER" ]]; then
  echo "apksigner not found. Install Android build-tools first."
  exit 4
fi

mkdir -p "$OUT_DIR"
cp -f "$APK_INPUT" "$OUT_APK"

read -r -s -p "Permanent keystore password: " KS_PASS
echo

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
EXPECTED="$(printf '%s' "$EXPECTED_CERT_SHA256" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"

if [[ "$ACTUAL_CERT_SHA256" != "$EXPECTED" ]]; then
  echo "ERROR: signer mismatch"
  echo "Expected: $EXPECTED"
  echo "Actual:   $ACTUAL_CERT_SHA256"
  rm -f "$OUT_APK"
  exit 5
fi

echo "Signer verified: $ACTUAL_CERT_SHA256"
echo "Signed APK: $OUT_APK"

if command -v rish >/dev/null 2>&1; then
  echo "Installing update-in-place through Shizuku/rish..."
  rish -c "pm install -r '$OUT_APK'"
  echo "Installed package: $PACKAGE"
else
  echo "rish not found; APK is signed and ready at: $OUT_APK"
fi
