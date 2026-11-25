#!/usr/bin/env bash
# Opens every mockup frame of ids.tsv on an emulator, in the debug build's demo data mode, and saves a PNG per frame and theme:
#   <outdir>/<id>-light.png and <outdir>/<id>-dark.png, only the themes the mockup has for that frame.
#
#   tools/mockup-capture/capture.sh <serial> <outdir> [id ...]
#   SKIP_INSTALL=1   use the APK that is already on the emulator
#   SETTLE=6         seconds to wait after a frame opens (default 3)
#
# Emulators only. See README.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG=app.lumio
APK="$ROOT/lumio/build/outputs/apk/debug/lumio-debug.apk"
SETTLE="${SETTLE:-3}"

serial="${1:?usage: capture.sh <serial> <outdir> [id ...]}"
out="${2:?usage: capture.sh <serial> <outdir> [id ...]}"
shift 2
case "$serial" in
    emulator-*) ;;
    *) echo "capture: $serial is not an emulator" >&2; exit 1 ;;
esac
adb_() { adb -s "$serial" "$@" </dev/null; }
mkdir -p "$out"

if [ "${SKIP_INSTALL:-0}" != 1 ]; then
    adb_ install -r "$APK" >/dev/null
fi
adb_ shell pm grant "$PKG" android.permission.CAMERA >/dev/null 2>&1 || true
adb_ shell settings put global window_animation_scale 0
adb_ shell settings put global transition_animation_scale 0
adb_ shell settings put global animator_duration_scale 0

restore() {
    adb_ shell settings put system font_scale 1.0 || true
    adb_ shell wm size reset || true
    adb_ shell cmd uimode night no || true
}
trap restore EXIT

# The font scale and the screen size of a frame, from the table in the debug build. Kept here so the script needs no build to read them.
font_of() { case "$1" in p2-11) echo 2.0 ;; *) echo 1.0 ;; esac; }
size_of() { case "$1" in p1-09) echo 360x640 ;; *) echo "" ;; esac; }
profile_of() {
    case "$1" in
        p2-00|p2-01) echo offline ;;
        p2-03|p2-04) echo slow ;;
        p2-15) echo error ;;
        p1-25) echo empty ;;
        *) echo full ;;
    esac
}
# Frames that need no seeded vault state beyond the demo wallet start from the running process. A first-run frame starts from a vault-less launch.
fresh_of() { case "$1" in p2-03|p2-04|p2-15|p1-19|p1-20|p1-21|p2-26|p2-27|p2-28|p2-29|p2-30) echo 1 ;; *) echo 0 ;; esac; }

want=("$@")
wanted() {
    [ ${#want[@]} -eq 0 ] && return 0
    local id
    for id in "${want[@]}"; do [ "$id" = "$1" ] && return 0; done
    return 1
}

failed=0
total=0
while IFS=$'\t' read -r id themes _title; do
    wanted "$id" || continue
    for theme in ${themes//,/ }; do
        total=$((total + 1))
        adb_ shell cmd uimode night "$([ "$theme" = dark ] && echo yes || echo no)" >/dev/null
        adb_ shell settings put system font_scale "$(font_of "$id")"
        size="$(size_of "$id")"
        if [ -n "$size" ]; then adb_ shell wm size "$size"; else adb_ shell wm size reset; fi
        adb_ shell am force-stop "$PKG"
        if [ "$(fresh_of "$id")" = 1 ]; then adb_ shell pm clear "$PKG" >/dev/null; adb_ shell pm grant "$PKG" android.permission.CAMERA >/dev/null 2>&1 || true; fi
        # The launch logs "opened <id>" when the frame is on screen, or why the seed did not run (an old process still dying). Then one retry.
        for attempt in 1 2 3; do
            adb_ logcat -c
            adb_ shell am start -W -n "$PKG/.MainActivity" --ez debugDemoData true --es debugScreen "$id" --es debugProfile "$(profile_of "$id")" >/dev/null
            for _ in $(seq 1 60); do
                log="$(adb_ logcat -d -s DebugDemoData:I DebugDemoSeed:E || true)"
                case "$log" in *"opened $id"*|*"not seeded"*|*"could not be seeded"*) break ;; esac
                sleep 1
            done
            case "$log" in *"opened $id"*) break ;; esac
            adb_ shell am force-stop "$PKG"
            sleep 2
        done
        sleep "$SETTLE"
        file="$out/$id-$theme.png"
        if adb_ exec-out screencap -p > "$file" && [ -s "$file" ]; then
            echo "ok   $id-$theme"
        else
            echo "FAIL $id-$theme" >&2
            failed=$((failed + 1))
        fi
    done
done < "$HERE/ids.tsv"
echo "captured $((total - failed)) of $total"
[ "$failed" -eq 0 ]
