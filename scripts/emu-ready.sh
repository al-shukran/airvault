#!/usr/bin/env bash
# Puts Lumio's debug build on an emulator with its demo seed in place, so a motion gate can start at once. It builds and installs the
# debug APK, launches it with the seed extra (no taps), waits until the seed has the demo wallet on the Wallets home, and then checks on
# screen that the wallet's account list shows a balance.
#
#   scripts/emu-ready.sh [serial]   the serial defaults to the first attached emulator
#   FRESH=1        uninstall first, so the vault and its pairing are made anew
#   SKIP_BUILD=1   install the debug APK that is already built
#   DEMO_MNEMONIC  required: the throwaway test wallet's 24 words (never committed)
#   TIMEOUT=10     seconds to wait for the wallet to open after the launch (default 10)
#
# Only emulators are touched. A physical device is refused, since it belongs to the owner.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=app.lumio
APK="$ROOT/lumio/build/outputs/apk/debug/lumio-debug.apk"
DUMP=/data/local/tmp/emu-ready-window.xml
TIMEOUT="${TIMEOUT:-10}"

serial="${1:-}"
: "${DEMO_MNEMONIC:?emu-ready: set DEMO_MNEMONIC to the throwaway test wallet 24 words}"
if [ -z "$serial" ]; then
    serial="$(adb devices | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print $1; exit }')"
fi
case "$serial" in
    emulator-*) ;;
    *) echo "emu-ready: no emulator to use (got '${serial:-none}'). Start one, then rerun." >&2; exit 1 ;;
esac

if [ "${SKIP_BUILD:-0}" != 1 ]; then
    "$ROOT/gradlew" ":lumio:assembleDebug"
fi
if [ "${FRESH:-0}" = 1 ]; then
    adb -s "$serial" uninstall "$PKG" >/dev/null 2>&1 || true
fi
adb -s "$serial" install -r "$APK" >/dev/null
# The cover opens the camera, and a permission dialog would cover the wallet.
adb -s "$serial" shell pm grant "$PKG" android.permission.CAMERA >/dev/null 2>&1 || true

# The seed logs under its own tag. The log is cleared first, so only this launch's lines are read.
adb -s "$serial" logcat -c
start="$(date +%s)"
output="$(adb -s "$serial" shell am start -n "$PKG/.MainActivity" --es debugDemoSeed "'$DEMO_MNEMONIC'")"
case "$output" in
    *Error*) echo "emu-ready: launch failed on $serial: $output" >&2; exit 1 ;;
esac

# The seed logs once the wallet is on the Wallets home, or why it is not.
while :; do
    seed="$(adb -s "$serial" logcat -d -s 'DebugDemoSeed:I' 2>/dev/null || true)"
    if grep -q 'The demo wallet is on the Wallets home' <<< "$seed"; then
        break
    fi
    if grep -q 'was not seeded\|could not be seeded' <<< "$seed"; then
        grep 'DebugDemoSeed' <<< "$seed" | tail -n 3 >&2
        echo "emu-ready: the demo seed failed on $serial" >&2
        exit 1
    fi
    if [ $(( $(date +%s) - start )) -ge "$TIMEOUT" ]; then
        echo "emu-ready: the demo wallet did not open within ${TIMEOUT}s on $serial" >&2
        exit 1
    fi
    sleep 0.2
done
echo "emu-ready: the demo wallet opened $(( $(date +%s) - start ))s after the launch on $serial"

# The on-screen check: the wallet's account list, and a balance on it. The dump waits for the screen to settle, which a spinner keeps from
# happening, so each attempt can take a few seconds.
for _ in 1 2 3 4 5; do
    adb -s "$serial" shell rm -f "$DUMP" >/dev/null 2>&1 || true
    adb -s "$serial" shell uiautomator dump "$DUMP" >/dev/null 2>&1 || true
    window="$(adb -s "$serial" shell cat "$DUMP" 2>/dev/null || true)"
    accounts="$(printf '%s\n' "$window" | grep -Eoi 'text="[^"]*[0-9]+ accounts[^"]*"' | head -n 1 || true)"
    balance="$(printf '%s\n' "$window" | grep -Eo 'text="[0-9][0-9.,]* [A-Z]{2,6}"' | head -n 1 || true)"
    if [ -n "$accounts" ] && [ -n "$balance" ]; then
        echo "emu-ready: $accounts and $balance on screen"
        exit 0
    fi
done
echo "emu-ready: the account list with a balance is not on screen on $serial" >&2
exit 1
