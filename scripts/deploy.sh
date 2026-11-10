#!/usr/bin/env bash
# Build the debug APK, then install and launch it on the target devices.
# Default target is the emulator only; the physical phone belongs to the owner.
# Override with DEVICES="serial1 serial2" or DEVICES=all.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -f "$ROOT/scripts/env.sh" ]; then
    # shellcheck source=/dev/null
    . "$ROOT/scripts/env.sh"
fi

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
COMPONENT="app.lensly/.MainActivity"

./gradlew :app:assembleDebug

attached="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
case "${DEVICES:-emulator-5554}" in
    all) devices="$attached" ;;
    *) devices="$(for d in ${DEVICES:-emulator-5554}; do echo "$attached" | grep -qx "$d" && echo "$d"; done)" ;;
esac
if [ -z "$devices" ]; then
    echo "deploy: target device(s) not attached: ${DEVICES:-emulator-5554} (see 'adb devices')" >&2
    exit 1
fi

for device in $devices; do
    echo "== $device: installing $(basename "$APK")"
    adb -s "$device" install -r "$APK"

    echo "== $device: launching $COMPONENT"
    output="$(adb -s "$device" shell am start -n "$COMPONENT")"
    echo "$output"
    case "$output" in
        *Error*) echo "deploy: launch failed on $device" >&2; exit 1 ;;
    esac
done
