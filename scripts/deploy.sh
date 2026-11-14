#!/usr/bin/env bash
# Build the debug APK, then install and launch it on the target devices.
# Default target is the emulator only; the physical phone belongs to the owner.
# Override with DEVICES="serial1 serial2" or DEVICES=all.
# APP picks the app: lensly (the default, module :app) or lumio (module :lumio).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -f "$ROOT/scripts/env.sh" ]; then
    # shellcheck source=/dev/null
    . "$ROOT/scripts/env.sh"
fi

case "${APP:-lensly}" in
    lensly) MODULE=app; COMPONENT="app.lensly/.MainActivity" ;;
    lumio) MODULE=lumio; COMPONENT="app.lumio/.MainActivity" ;;
    *) echo "deploy: unknown APP=${APP:-}; use lensly or lumio" >&2; exit 2 ;;
esac
APK="$ROOT/$MODULE/build/outputs/apk/debug/$MODULE-debug.apk"

./gradlew ":$MODULE:assembleDebug"

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
