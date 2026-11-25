#!/usr/bin/env bash
# Self-check for lib.sh: the stamp guards and the temp files check_screen must not leave behind. Needs ffmpeg and
# magick, no device. Run it after changing lib.sh, capture.sh, compose.sh or post.sh.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"

work=$(mktemp -d "${TMPDIR:-/tmp}/pr-recording-selftest.XXXXXX")
trap 'rm -rf "$work"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

# A 720x1600 screen with a bright status bar and navigation bar, as a still and as a one-second clip.
magick -size 720x1600 xc:white -fill black -draw 'rectangle 0,0 360,90' -draw 'rectangle 0,1510 360,1600' "$work/screen.png"
ffmpeg -v error -y -loop 1 -framerate 5 -t 1 -i "$work/screen.png" -c:v libx264 -pix_fmt yuv420p "$work/screen.mp4"

# check_screen leaves nothing in TMPDIR, whether it accepts or rejects.
mkdir "$work/tmp"
for f in screen.png screen.mp4; do
  TMPDIR=$work/tmp check_screen "$work/$f" || fail "check_screen rejected $f"
done
magick -size 700x1500 xc:white "$work/small.png"
ffmpeg -v error -y -loop 1 -framerate 5 -t 1 -i "$work/small.png" -c:v libx264 -pix_fmt yuv420p "$work/small.mp4"
TMPDIR=$work/tmp check_screen "$work/small.mp4" 2>/dev/null && fail "check_screen accepted a wrong-size clip"
[ -z "$(ls -A "$work/tmp")" ] || fail "check_screen left temp files: $(ls "$work/tmp")"

# Each stamp passes for an unmodified file, and fails for a missing stamp or a changed file.
for kind in capture compose; do
  f=$work/$kind.png
  cp "$work/screen.png" "$f"
  "check_$kind" "$f" 2>/dev/null && fail "check_$kind accepted an unstamped file"
  "stamp_$kind" "$f"
  "check_$kind" "$f" || fail "check_$kind rejected a stamped file"
  magick "$f" -fill red -draw 'point 5,5' "$f"
  "check_$kind" "$f" 2>/dev/null && fail "check_$kind accepted an edited file"
done
cp "$work/screen.png" "$work/mixed.png"
stamp_capture "$work/mixed.png"
check_compose "$work/mixed.png" 2>/dev/null && fail "a capture stamp passed check_compose"

echo "ok"
