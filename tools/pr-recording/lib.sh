# Shared by capture.sh and compose.sh: the one definition of an acceptable screen. Sourced, not run.

# check_screen FILE [START]: FILE is a PNG or a clip. It must be 9:20 and at least 720 px wide, and its
# top (status bar) and bottom (navigation bar) bands must hold something. For a clip, the frame at START
# seconds (default 0) is checked. Prints the problem and returns 1 when the screen is not acceptable.
check_screen() {
  local f=$1 start=${2:-0} png w h band top bottom
  png=$f
  case $f in
    *.png | *.PNG) read -r w h < <(magick identify -format '%w %h\n' "$f" | head -1) ;;
    *)
      read -r w h < <(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "$f" | tr ',' ' ')
      png=$(mktemp "${TMPDIR:-/tmp}/screen-check.XXXXXX").png
      ffmpeg -v error -y -ss "$start" -i "$f" -frames:v 1 "$png" || { echo "$f: cannot read a frame at ${start}s" >&2; return 1; } ;;
  esac
  [ -n "${w:-}" ] && [ -n "${h:-}" ] || { echo "$f: cannot read its size" >&2; return 1; }
  if [ $((w * 20)) -ne $((h * 9)) ] || [ "$w" -lt 720 ]; then
    echo "$f is ${w}x${h}. A screen must be 9:20 and at least 720 px wide (720x1600 or 1080x2400)." >&2
    echo "Take it with capture.sh on an emulator. Test goldens and cropped shots are not accepted." >&2
    return 1
  fi
  band=$((h * 6 / 100))
  top=$(magick "$png" -crop "${w}x${band}+0+0" +repage -colorspace Gray -format '%[fx:standard_deviation]' info:)
  bottom=$(magick "$png" -crop "${w}x${band}+0+$((h - band))" +repage -colorspace Gray -format '%[fx:standard_deviation]' info:)
  [ "$png" = "$f" ] || rm -f "$png"
  awk -v t="$top" -v b="$bottom" 'BEGIN { exit !(t > 0.002 && b > 0.002) }' || {
    echo "$f has an empty status bar or navigation bar band. Take it with capture.sh on an emulator, uncropped." >&2
    return 1
  }
}
