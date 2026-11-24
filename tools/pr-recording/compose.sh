#!/usr/bin/env bash
# Compose a screen recording inside phone frames for a pull request.
#
#   compose.sh --before a.mp4 --after b.mp4 --out out   two devices with labels
#   compose.sh --demo a.mp4 --out out                   one device
#
# Writes out.mp4 (H.264, at most 60 s, at most 10 MB) and out.gif (960 px wide for
# a pair, 720 for a demo, at most 10 MB). Needs ffmpeg and ImageMagick (magick).
# See CONTRIBUTING.md.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
frame="$here/device-frame.png" # 376x796 bezel with a transparent 360x780 screen cutout
font=${PR_RECORDING_FONT:-$here/../../shared/src/main/res/font/archivo_variable.ttf}
max_seconds=60
max_bytes=$((10 * 1024 * 1024))

usage() {
  echo "usage: $0 --before a.mp4 --after b.mp4 --out out" >&2
  echo "       $0 --demo a.mp4 --out out" >&2
  exit 2
}

before='' after='' demo='' out=''
while [ $# -gt 0 ]; do
  case $1 in
    --before) before=${2:?}; shift 2 ;;
    --after) after=${2:?}; shift 2 ;;
    --demo) demo=${2:?}; shift 2 ;;
    --out) out=${2:?}; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$out" ] || usage
if [ -n "$demo" ]; then
  [ -z "$before$after" ] || usage
  clips=("$demo")
else
  [ -n "$before" ] && [ -n "$after" ] || usage
  clips=("$before" "$after")
fi
for tool in ffmpeg ffprobe magick; do
  command -v "$tool" >/dev/null || { echo "$tool not found (brew install ffmpeg imagemagick)" >&2; exit 1; }
done
for clip in "${clips[@]}"; do
  [ -f "$clip" ] || { echo "no such clip: $clip" >&2; exit 1; }
done

n=${#clips[@]}
work=$(mktemp -d "${TMPDIR:-/tmp}/pr-recording.XXXXXX")
trap 'rm -rf "$work"' EXIT

# Layout in pixels. A device is the 376x796 frame; its screen starts at (8, 8).
# A pair sits on two tinted halves with a VS badge between the devices and a
# label pill above each. A demo is one device on a neutral background.
top=172
if [ "$n" -eq 2 ]; then
  width=1120 height=1024 xs=(92 652)
  magick -size "560x$height" gradient:'#FDECEA-#F4CECC' "$work/half-before.png"
  magick -size "560x$height" gradient:'#EAF9F0-#CDEEDB' "$work/half-after.png"
  stage=(-size "${width}x${height}" xc:none "$work/half-before.png" -geometry +0+0 -composite
    "$work/half-after.png" -geometry +560+0 -composite)
else
  width=720 height=1024 xs=(172)
  stage=(-size "${width}x${height}" xc:'#F3F0EA')
fi

# Stage: the background, a soft shadow under each frame, and for a pair the
# VS badge and the label pills.
magick "$frame" -background none -gravity center -extent 576x996 +repage \
  -background '#2B2530' -shadow 30x22+0+24 "$work/shadow.png"
for x in "${xs[@]}"; do
  stage+=("$work/shadow.png" -geometry "$(printf '%+d%+d' $((x - 100)) $((top - 100)))" -composite)
done
if [ "$n" -eq 2 ]; then
  stage+=(-fill '#1C191F' -draw "circle 560,570 618,570"
    -fill '#D9463E' -draw "roundrectangle 140,56 419,131 38,38"
    -fill '#1E9E62' -draw "roundrectangle 700,56 979,131 38,38"
    -font "$font" -pointsize 42 -gravity center -fill white -stroke white -strokewidth 0.8
    -annotate +0+58 VS -annotate -280-418 Before -annotate +280-418 After)
fi
magick "${stage[@]}" "$work/stage.png"

# Screen mask: 360x780 with the same corner radius as the screen cutout.
magick -size 360x780 xc:black -fill white -draw 'roundrectangle 0,0 359,779 48,48' "$work/mask.png"

# Filter graph: each clip is fitted to the screen, freeze-padded, masked to the
# rounded screen, and overlaid under its frame. Input 0 is the stage, inputs
# 1..n the clips, n+1 the frame and n+2 the mask.
parts=("[0:v]format=yuv420p[s0]")
split_m='' split_f=''
for i in $(seq 0 $((n - 1))); do
  split_m+="[m$i]"
  split_f+="[f$i]"
done
parts+=("[$((n + 2)):v]format=gray,split=$n$split_m")
parts+=("[$((n + 1)):v]format=rgba,split=$n$split_f")
cur=s0
for i in $(seq 0 $((n - 1))); do
  x=${xs[i]}
  parts+=("[$((i + 1)):v]fps=30,scale=360:780:force_original_aspect_ratio=decrease,pad=360:780:(ow-iw)/2:(oh-ih)/2:0x0C0B0E,setsar=1,format=yuv420p,tpad=stop_mode=clone:stop_duration=${max_seconds}[c$i]")
  parts+=("[c$i][m$i]alphamerge[k$i]")
  parts+=("[$cur][k$i]overlay=$((x + 8)):$((top + 8))[t$i]")
  parts+=("[t$i][f$i]overlay=${x}:${top}[u$i]")
  cur=u$i
done
parts+=("[$cur]format=yuv420p[out]")
graph=$(IFS=';'; echo "${parts[*]}")

# Length: the longest clip, capped at 60 s.
secs=$(for clip in "${clips[@]}"; do
  ffprobe -v error -show_entries format=duration -of csv=p=0 "$clip"
done | awk -v cap="$max_seconds" '{ if ($1 > m) m = $1 } END { if (m > cap) m = cap; printf "%.2f", m }')

size_of() { wc -c <"$1" | tr -d ' '; }

inputs=(-framerate 30 -loop 1 -i "$work/stage.png")
for clip in "${clips[@]}"; do inputs+=(-i "$clip"); done
inputs+=(-framerate 30 -loop 1 -i "$frame" -framerate 30 -loop 1 -i "$work/mask.png")

for crf in 22 25 28 31; do
  ffmpeg -v error -y "${inputs[@]}" -filter_complex "$graph" -map '[out]' -t "$secs" -an \
    -r 30 -c:v libx264 -preset slow -crf "$crf" -pix_fmt yuv420p -movflags +faststart "$out.mp4"
  [ "$(size_of "$out.mp4")" -le "$max_bytes" ] && break
done

# GIF: step the frame rate and width down until it fits.
for pass in "10 960" "8 960" "8 720" "6 720"; do
  read -r fps max_width <<<"$pass"
  ffmpeg -v error -y -i "$out.mp4" -filter_complex \
    "fps=$fps,scale='min($max_width,iw)':-2:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=sierra2_4a:diff_mode=rectangle" \
    -loop 0 "$out.gif"
  [ "$(size_of "$out.gif")" -le "$max_bytes" ] && break
done

for f in "$out.mp4" "$out.gif"; do
  [ "$(size_of "$f")" -le "$max_bytes" ] || { echo "$f is larger than 10 MB" >&2; exit 1; }
done
echo "wrote $out.mp4 ($secs s) and $out.gif"
