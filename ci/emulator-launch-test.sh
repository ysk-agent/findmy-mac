#!/usr/bin/env bash
# Emulator launch test for Find My Mac.
#
# Installs the signed release APK, launches the launcher activity
# (MainActivityNew, the login screen), and verifies that:
#   - the app process is alive,
#   - the launcher activity is the top resumed activity,
#   - logcat contains no FATAL EXCEPTION for the app.
# A screenshot of the login screen is captured for the workflow artifacts.
#
# This script exists as a file (not inline in the workflow) because
# reactivecircus/android-emulator-runner executes an inline `script:` block
# line-by-line in separate shells, so shell state and `exit` do not work there.
set -u
cd "$(dirname "$0")/.."

APK=app/build/outputs/apk/release/findmy-mac.apk
PKG=dev.moriya.findmymac
FAIL=0

note_fail() { echo "LAUNCH-TEST FAILURE: $1"; FAIL=1; }

adb install -r "$APK" || note_fail "adb install failed"

adb logcat -c || true
adb shell am start -n $PKG/.MainActivityNew
sleep 8

PID=$(adb shell pidof $PKG | tr -d '\r\n')
echo "PID after launch: '$PID'"
[ -n "$PID" ] || note_fail "app process not alive after launch"

TOP=$(adb shell dumpsys activity activities 2>/dev/null | grep "topResumedActivity" | head -1)
echo "top activity: $TOP"
if echo "$TOP" | grep -q "$PKG/.MainActivityNew"; then
  echo "OK: MainActivityNew is top resumed activity"
else
  note_fail "MainActivityNew is not the top resumed activity"
fi

adb exec-out screencap -p > screenshot-login.png 2>/dev/null || true
if [ -s screenshot-login.png ]; then
  echo "screenshot saved: screenshot-login.png ($(stat -c%s screenshot-login.png) bytes)"
else
  echo "WARNING: screenshot-login.png empty/missing"
fi

adb logcat -d -v threadtime > logcat.txt 2>&1 || true
adb shell dumpsys activity activities > dumpsys-activities.txt 2>&1 || true

if grep -q "FATAL EXCEPTION" logcat.txt && grep -A3 "FATAL EXCEPTION" logcat.txt | grep -q "$PKG"; then
  note_fail "FATAL EXCEPTION for $PKG in logcat"
fi

echo "LAUNCH_TEST_FAIL=$FAIL"
exit $FAIL
