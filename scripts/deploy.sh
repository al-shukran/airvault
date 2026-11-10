#!/usr/bin/env bash
# Build the debug APK, then install and launch it on every authorized adb device.
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

devices="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [ -z "$devices" ]; then
    echo "deploy: no authorized devices attached (see 'adb devices')" >&2
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
