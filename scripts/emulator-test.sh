#!/usr/bin/env bash
set -euo pipefail
mkdir -p artifacts
adb shell am force-stop com.android.launcher3 || true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install -r dist/sososafe.apk
adb install -r dist/app-debug-androidTest.apk
adb shell am start -W -n app.sososafe.mobile/.MainActivity > artifacts/launch.txt
sleep 3
adb exec-out screencap -p > artifacts/phone-light.png
adb shell cmd uimode night yes
adb shell settings put system font_scale 1.3
sleep 2
adb exec-out screencap -p > artifacts/phone-dark-large-text.png
adb shell wm size 1600x2560
adb shell wm density 240
sleep 2
adb exec-out screencap -p > artifacts/tablet.png
adb shell wm size reset
adb shell wm density reset
adb shell settings put system font_scale 1.0
adb shell cmd uimode night no
python3 scripts/relay-fixture.py > artifacts/relay-fixture.log 2>&1 &
FIXTURE_PID=$!
cleanup() {
    adb logcat -d > artifacts/device-logcat.txt || true
    adb pull /sdcard/Android/data/app.sososafe.mobile/files artifacts/device-files >/dev/null 2>&1 || true
    adb exec-out screencap -p > artifacts/final-screen.png || true
    kill "$FIXTURE_PID" 2>/dev/null || true
}
trap cleanup EXIT
adb shell am instrument -w -r -e class app.sososafe.mobile.DeviceTest app.sososafe.mobile.test/androidx.test.runner.AndroidJUnitRunner | tee artifacts/instrumentation.txt
python3 scripts/verify-instrumentation.py artifacts/instrumentation.txt artifacts/device-tests.xml
adb logcat -d -s AndroidRuntime:E > artifacts/crashes.txt
if grep -q 'FATAL EXCEPTION' artifacts/crashes.txt; then cat artifacts/crashes.txt; exit 1; fi
