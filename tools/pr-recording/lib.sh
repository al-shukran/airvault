# shellcheck shell=bash
# Shared by capture.sh, compose.sh and post.sh: the one definition of an acceptable screen. Sourced, not run.

# check_screen FILE [START]: FILE is a PNG or a clip. It must be 9:20 and at least 720 px wide, and its
# top (status bar) and bottom (navigation bar) bands must hold something. For a clip, the frame at START
# seconds (default 0) is checked. Prints the problem and returns 1 when the screen is not acceptable.
check_screen() {
  local f=$1 start=${2:-0} png tmp='' w h band top bottom
  png=$f
  case $f in
    *.png | *.PNG) read -r w h < <(magick identify -format '%w %h\n' "$f" | head -1) ;;
    *)
      read -r w h < <(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "$f" | tr ',' ' ')
      tmp=$(mktemp -d "${TMPDIR:-/tmp}/screen-check.XXXXXX")
      png=$tmp/frame.png
      ffmpeg -v error -y -ss "$start" -i "$f" -frames:v 1 "$png" || { rm -rf "$tmp"; echo "$f: cannot read a frame at ${start}s" >&2; return 1; } ;;
  esac
  [ -n "${w:-}" ] && [ -n "${h:-}" ] || { [ -z "$tmp" ] || rm -rf "$tmp"; echo "$f: cannot read its size" >&2; return 1; }
  if [ $((w * 20)) -ne $((h * 9)) ] || [ "$w" -lt 720 ]; then
    [ -z "$tmp" ] || rm -rf "$tmp"
    echo "$f is ${w}x${h}. A screen must be 9:20 and at least 720 px wide (720x1600 or 1080x2400)." >&2
    echo "Take it with capture.sh on an emulator. Test goldens and cropped shots are not accepted." >&2
    return 1
  fi
  band=$((h * 6 / 100))
  top=$(magick "$png" -crop "${w}x${band}+0+0" +repage -colorspace Gray -format '%[fx:standard_deviation]' info:)
  bottom=$(magick "$png" -crop "${w}x${band}+0+$((h - band))" +repage -colorspace Gray -format '%[fx:standard_deviation]' info:)
  [ -z "$tmp" ] || rm -rf "$tmp"
  awk -v t="$top" -v b="$bottom" 'BEGIN { exit !(t > 0.002 && b > 0.002) }' || {
    echo "$f has an empty status bar or navigation bar band. Take it with capture.sh on an emulator, uncropped." >&2
    return 1
  }
}

# stamp_capture FILE: called by capture.sh only. Writes FILE.capture, the SHA-256 of FILE, next to it.
stamp_capture() { shasum -a 256 <"$1" | cut -d' ' -f1 >"$1.capture"; }

# check_capture FILE: FILE must carry the stamp capture.sh wrote for it, and must not have changed since.
# Prints the problem and returns 1 otherwise. This is what keeps test goldens and edited shots out.
check_capture() {
  if [ ! -f "$1.capture" ] || [ "$(cat "$1.capture")" != "$(shasum -a 256 <"$1" | cut -d' ' -f1)" ]; then
    echo "$1 has no matching $1.capture stamp. Only an unmodified capture.sh screen is accepted." >&2
    echo "Take it with capture.sh on an emulator. Test goldens and edited shots are not accepted." >&2
    return 1
  fi
}

# stamp_compose FILE: called by compose.sh only. Writes FILE.compose, the SHA-256 of FILE, next to it.
stamp_compose() { shasum -a 256 <"$1" | cut -d' ' -f1 >"$1.compose"; }

# check_compose FILE: FILE must carry the stamp compose.sh wrote for it, and must not have changed since.
# Prints the problem and returns 1 otherwise. This is what keeps raw captures and hand-made media out of post.sh.
check_compose() {
  if [ ! -f "$1.compose" ] || [ "$(cat "$1.compose")" != "$(shasum -a 256 <"$1" | cut -d' ' -f1)" ]; then
    echo "$1 has no matching $1.compose stamp. Only an unmodified compose.sh output is accepted." >&2
    return 1
  fi
}
