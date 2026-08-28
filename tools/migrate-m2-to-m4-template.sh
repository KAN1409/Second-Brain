#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PKG='com.kareem.secondbrain'
DOWNLOAD_DIR='/sdcard/Download'
M2_APK="$DOWNLOAD_DIR/Second-Brain-M2-debug.apk"
EXPECTED_SHA='__APK_SHA__'
APK=''
TMP='/data/local/tmp/Second-Brain-M4-permanent.apk'
TMP_M2='/data/local/tmp/Second-Brain-M2-debug.apk'
STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP="/sdcard/Download/Second-Brain-M2-data-${STAMP}.tar"

say(){ printf '\n==> %s\n' "$*"; }
fail(){ printf '\nERROR: %s\n' "$*" >&2; exit 1; }
command -v rish >/dev/null 2>&1 || fail 'rish not found. Start Shizuku and ensure rish works.'

say 'Finding exact permanent M4 APK by SHA-256'
while IFS= read -r -d '' f; do
  h="$(sha256sum "$f" | awk '{print $1}')"
  printf 'Checked: %s  %s\n' "$(basename "$f")" "$h"
  if [ "$h" = "$EXPECTED_SHA" ]; then APK="$f"; break; fi
done < <(find "$DOWNLOAD_DIR" -maxdepth 1 -type f -iname 'Second-Brain-M4-permanent*.apk' -print0 2>/dev/null)
[ -n "$APK" ] || fail "Correct APK not found. Nothing changed. Expected SHA-256: $EXPECTED_SHA"

say 'Checking installed Second Brain M2'
INFO="$(rish -c "dumpsys package $PKG | grep -E 'versionCode=|versionName='" 2>/dev/null || true)"
printf '%s\n' "$INFO"
printf '%s' "$INFO" | grep -q 'versionCode=3' || fail 'Expected M2 versionCode=3. Nothing changed.'
rish -c "run-as $PKG id" >/dev/null || fail 'run-as unavailable. Nothing changed.'

say 'Saving current access state'
NOTIF="$(rish -c 'settings get secure enabled_notification_listeners' 2>/dev/null | tr -d '\r' || true)"
ACCESS="$(rish -c 'settings get secure enabled_accessibility_services' 2>/dev/null | tr -d '\r' || true)"
ACCESS_ON="$(rish -c 'settings get secure accessibility_enabled' 2>/dev/null | tr -d '\r' || true)"
USAGE=0; rish -c "cmd appops get $PKG GET_USAGE_STATS" 2>/dev/null | grep -qi allow && USAGE=1 || true
MIC=0; rish -c "dumpsys package $PKG" 2>/dev/null | grep -q 'android.permission.RECORD_AUDIO: granted=true' && MIC=1 || true
POST=0; rish -c "dumpsys package $PKG" 2>/dev/null | grep -q 'android.permission.POST_NOTIFICATIONS: granted=true' && POST=1 || true

say "Backing up M2 data to $BACKUP"
rish -c "am force-stop $PKG" >/dev/null 2>&1 || true
rish -c "run-as $PKG sh -c 'cd /data/user/0/$PKG && tar -cf - .'" > "$BACKUP"
[ -s "$BACKUP" ] || fail 'Backup is empty. Nothing uninstalled.'
tar -tf "$BACKUP" >/dev/null || fail 'Backup validation failed. Nothing uninstalled.'

after_install(){
  say 'Restoring app data'
  cat "$BACKUP" | rish -c "run-as $PKG sh -c 'cd /data/user/0/$PKG && tar -xf -'"
  rish -c "am force-stop $PKG" >/dev/null 2>&1 || true
  [ -z "$NOTIF" ] || [ "$NOTIF" = null ] || rish -c "settings put secure enabled_notification_listeners '$NOTIF'" >/dev/null 2>&1 || true
  [ -z "$ACCESS" ] || [ "$ACCESS" = null ] || rish -c "settings put secure enabled_accessibility_services '$ACCESS'" >/dev/null 2>&1 || true
  [ "$ACCESS_ON" = 1 ] && rish -c 'settings put secure accessibility_enabled 1' >/dev/null 2>&1 || true
  [ "$USAGE" = 1 ] && rish -c "cmd appops set $PKG GET_USAGE_STATS allow" >/dev/null 2>&1 || true
  [ "$MIC" = 1 ] && rish -c "pm grant $PKG android.permission.RECORD_AUDIO" >/dev/null 2>&1 || true
  [ "$POST" = 1 ] && rish -c "pm grant $PKG android.permission.POST_NOTIFICATIONS" >/dev/null 2>&1 || true
}

rollback(){
  printf '\nM4 install failed; attempting M2 rollback.\n' >&2
  [ -f "$M2_APK" ] || fail "M2 APK missing. Backup is safe at $BACKUP"
  cat "$M2_APK" | rish -c "cat > '$TMP_M2'; chmod 644 '$TMP_M2'"
  out="$(rish -c "pm install '$TMP_M2'" 2>&1 || true)"; printf '%s\n' "$out"
  rish -c "rm -f '$TMP_M2'" >/dev/null 2>&1 || true
  printf '%s' "$out" | grep -q Success || fail "Rollback install failed. Keep backup: $BACKUP"
  after_install
  fail 'M4 installation failed; M2 was restored.'
}

say 'Staging M4'
cat "$APK" | rish -c "cat > '$TMP'; chmod 644 '$TMP'"
say 'Uninstalling old debug-signed M2 after verified backup'
out="$(rish -c "pm uninstall $PKG" 2>&1 || true)"; printf '%s\n' "$out"
printf '%s' "$out" | grep -q Success || fail "Uninstall failed. Backup kept: $BACKUP"

say 'Installing permanent-signed M4'
out="$(rish -c "pm install '$TMP'" 2>&1 || true)"; printf '%s\n' "$out"
rish -c "rm -f '$TMP'" >/dev/null 2>&1 || true
printf '%s' "$out" | grep -q Success || rollback

after_install
say 'Verifying M4'
FINAL="$(rish -c "dumpsys package $PKG | grep -E 'versionCode=|versionName='" 2>/dev/null || true)"
printf '%s\n' "$FINAL"
printf '%s' "$FINAL" | grep -q 'versionCode=5' || fail "Version verification failed. Backup kept: $BACKUP"
rish -c "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1 || true
printf '\nMIGRATION_SUCCESS\nBackup kept at: %s\n' "$BACKUP"
