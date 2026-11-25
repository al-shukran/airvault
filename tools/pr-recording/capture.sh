#!/usr/bin/env bash
# Takes one emulator screen for a pull request, with identical system bars every time.
#
#   capture.sh --serial emulator-5554 --out shot.png
#   capture.sh --serial emulator-5554 --out clip.mp4 [--seconds 10]
#
# The emulator goes into SystemUI demo mode first (clock 12:00, full battery, wifi with 4 bars, no
# notifications) and leaves it afterwards. The result must be 9:20 and at least 720 px wide, with a real
# status bar and navigation bar; anything else is refused. Feed the files to compose.sh. See README.md.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"

usage() { echo "usage: capture.sh --serial SERIAL --out FILE.png|FILE.mp4 [--seconds N]" >&2; exit 2; }

serial='' out='' seconds=10
while [ $# -gt 0 ]; do
  case $1 in
    --serial) serial=${2:?}; shift 2 ;;
    --out) out=${2:?}; shift 2 ;;
    --seconds) seconds=${2:?}; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$serial" ] && [ -n "$out" ] || usage
case $out in *.png | *.mp4) ;; *) usage ;; esac
for tool in adb ffmpeg ffprobe magick awk; do
  command -v "$tool" >/dev/null || { echo "$tool not found" >&2; exit 1; }
done

adbs() { adb -s "$serial" "$@"; }
demo() { adbs shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
adbs get-state >/dev/null 2>&1 && [[ $serial == emulator-* ]] || { echo "no connected emulator $serial. Start one, or pass its serial from adb devices (emulator-NNNN)." >&2; exit 1; }

adbs shell settings put global sysui_demo_allowed 1
trap 'demo exit || true' EXIT
demo enter
demo clock -e hhmm 1200
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo notifications -e visible false
sleep 1

mkdir -p "$(dirname "$out")"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/capture.XXXXXX")
trap 'demo exit || true; rm -rf "$tmp"' EXIT
case $out in
  *.png) adbs exec-out screencap -p >"$tmp/raw.png" ;;
  *.mp4)
    # screenrecord writes a frame only when the screen changes, and stops early on the emulator, so a clip is
    # a loop of screencaps, played back at the rate it was taken.
    n=0 begin=$SECONDS
    while [ $((SECONDS - begin)) -lt "$seconds" ]; do
      adbs exec-out screencap -p >"$tmp/f$(printf %04d $n).png"
      n=$((n + 1))
    done
    [ "$n" -ge 2 ] || { echo "only $n frames in ${seconds}s" >&2; exit 1; }
    ffmpeg -v error -y -framerate "$(awk -v n="$n" -v s="$seconds" 'BEGIN { printf "%.3f", n / s }')" -i "$tmp/f%04d.png" \
      -c:v libx264 -pix_fmt yuv420p "$tmp/raw.mp4" ;;
esac
raw="$tmp/raw.${out##*.}"
check_screen "$raw" || exit 1
cp "$raw" "$out"
stamp_capture "$out"
echo "wrote $out"
