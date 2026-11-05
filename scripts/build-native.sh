#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="$ROOT/third_party/hev-socks5-tunnel"
test "$(git -C "$ENGINE" rev-parse HEAD)" = 21bb3d77e64ad5a413d4d851b9f4cacf2098cd77
cp "$ROOT/native/soso-jni.c" "$ENGINE/src/hev-jni.c"
python3 - "$ENGINE/src/hev-socks5-tunnel.c" <<'PY'
import sys
from pathlib import Path
p=Path(sys.argv[1]); text=p.read_text()
if 'void soso_observe' not in text:
    text=text.replace('static int run;', 'void soso_observe(struct pbuf *p, int outbound);\nstatic int run;', 1)
    old='    stat_rx_packets++;'
    assert text.count(old)==1
    text=text.replace(old,'    soso_observe(p, 0);\n'+old,1)
    old='        stat_tx_packets++;'
    assert text.count(old)==1
    text=text.replace(old,'        soso_observe(buf, 1);\n'+old,1)
    p.write_text(text)
PY
"$ANDROID_NDK_HOME/ndk-build" -C "$ENGINE" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk APP_ABI="arm64-v8a armeabi-v7a x86_64" APP_PLATFORM=android-26 -j2
mkdir -p "$ROOT/app/src/main/jniLibs"
for abi in arm64-v8a armeabi-v7a x86_64; do
    mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
    cp "$ENGINE/libs/$abi/libhev-socks5-tunnel.so" "$ROOT/app/src/main/jniLibs/$abi/"
done
