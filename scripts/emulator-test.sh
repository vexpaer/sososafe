#!/usr/bin/env bash
set -euo pipefail
mkdir -p artifacts
adb install -r dist/sososafe.apk
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
chmod +x gradlew
./gradlew :app:connectedDebugAndroidTest --stacktrace
adb logcat -d -s AndroidRuntime:E > artifacts/crashes.txt
if grep -q 'FATAL EXCEPTION' artifacts/crashes.txt; then cat artifacts/crashes.txt; exit 1; fi
