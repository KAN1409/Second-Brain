#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

CORTEX_PACKAGE="com.kareem.cortex"
RELAY_PACKAGE="com.kareem.secondbrain"
RELAY_ACTIVITY="com.kareem.secondbrain.app.MainActivity"
TOKEN="RELAY_OUTBOX_$(date +%H%M%S)"
CORTEX_REENABLED=0

if ! command -v rish >/dev/null 2>&1; then
  echo "ERROR: rish not found. Start Shizuku and ensure rish is available in Termux."
  exit 2
fi

reenable_cortex() {
  if [[ "$CORTEX_REENABLED" -eq 0 ]]; then
    echo
    echo "Restoring Cortex package state..."
    rish -c "pm enable --user 0 '$CORTEX_PACKAGE' >/dev/null 2>&1 || true"
    CORTEX_REENABLED=1
  fi
}
trap reenable_cortex EXIT INT TERM

cat <<EOF
Cortex Relay v1 durable-outbox acceptance
Test token: $TOKEN

This test does NOT uninstall either app and does NOT clear app data.
It temporarily disables Cortex so Relay cannot deliver, then kills/restarts Relay,
then re-enables Cortex so the exact pending event can complete delivery.

IMPORTANT: after step 3 restores Cortex, DO NOT force-stop or restart Relay or Cortex
until the FINAL diagnostic report has been saved/shared. Relay diagnostics are process-session
state, so an extra restart would erase the proof even if delivery succeeded.

Recovery command if this shell is interrupted:
  rish -c "pm enable --user 0 $CORTEX_PACKAGE"
EOF

printf '\n[1/4] Making Cortex genuinely unavailable...\n'
rish -c "am force-stop '$CORTEX_PACKAGE'; pm disable-user --user 0 '$CORTEX_PACKAGE'"
echo "CORTEX_UNAVAILABLE"

echo
echo "Now send ONE real WhatsApp message to this phone containing exactly:"
echo "  $TOKEN"
echo
echo "Open Cortex Relay and verify the useful child signal appears as Waiting / in flight."
echo "Do NOT re-enable or open Cortex yet."
read -r -p "Press Enter only after Relay shows the pending signal... " _

printf '\n[2/4] Killing the Relay process while Cortex is still unavailable...\n'
rish -c "am force-stop '$RELAY_PACKAGE'"
sleep 2
rish -c "am start -n '$RELAY_PACKAGE/$RELAY_ACTIVITY' >/dev/null"
sleep 4
echo "RELAY_RESTARTED_WITH_CORTEX_STILL_UNAVAILABLE"

echo
echo "Open Cortex Relay now. The same logical pending event must still exist."
echo "In the diagnostic report, restored_pending_event_ids must contain the exact pending event id."
echo "SAVE/SHARE this recovered-pending diagnostic before continuing."
read -r -p "Press Enter only after the recovered-pending diagnostic is saved/shared... " _

printf '\n[3/4] Restoring Cortex...\n'
rish -c "pm enable --user 0 '$CORTEX_PACKAGE' >/dev/null"
CORTEX_REENABLED=1
# Explicit user-style launch clears any stopped state and allows the Local Bus endpoint to run.
rish -c "monkey -p '$CORTEX_PACKAGE' 1 >/dev/null 2>&1 || true"
sleep 2
# Bring Relay back to the foreground without killing it. This SAME recovered Relay process must drain.
rish -c "am start -n '$RELAY_PACKAGE/$RELAY_ACTIVITY' >/dev/null"
sleep 8

echo "CORTEX_RESTORED"
echo
echo "[4/4] FINAL checks in the SAME Relay process:"
echo "  - the SAME pending event becomes Delivered to Cortex"
echo "  - Waiting / in flight returns to 0"
echo "  - Cortex ACK / signal id is present"
echo "  - no second logical evidence row was created for the same message"
echo "  - final diagnostic explains any retry/send-attempt count for that event"
echo
echo "DO NOT force-stop/restart either app yet."
echo "Share the FINAL diagnostic report NOW, before any further restart."
echo "Test token was: $TOKEN"
read -r -p "Press Enter only after the FINAL diagnostic is saved/shared... " _
trap - EXIT
