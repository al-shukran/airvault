#!/usr/bin/env bash
# Put a screen recording or a screenshot inside a phone frame for a pull request.
#
#   compose.sh --before a.mp4 --after b.mp4 --out out   two devices with labels
#   compose.sh --demo a.mp4 --out out                   one device
#   compose.sh shot in.png out.png                      one device, one screenshot
#
# A pair or a demo writes out.mp4 (H.264, at most 60 s) and out.gif (960 px wide for
# a pair, 720 for a demo), both at most 10 MB, with an elapsed / total timer under the
# phones. A shot writes out.png, 760 px wide. Each clip or screenshot covers the screen
# and is cropped to it: at the sides when it is too wide, at the bottom when it is too
# tall, so the status bar at the top stays whole.
# Needs ffmpeg and ImageMagick (magick). See CONTRIBUTING.md.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
font=${PR_RECORDING_FONT:-$here/../../shared/src/main/res/font/archivo_variable.ttf}
max_seconds=60
max_bytes=$((10 * 1024 * 1024))

# Device geometry in pixels at 1x. The frame and the screen mask are both drawn from
# these values, so the clip and the bezel cannot disagree.
dev_w=376 dev_h=796 dev_radius=56 bezel=8
screen_w=$((dev_w - 2 * bezel)) screen_h=$((dev_h - 2 * bezel)) screen_radius=48
cam_x=$((dev_w / 2)) cam_y=$((bezel + 12)) cam_r=6 # punch-hole camera, centred in the status bar

usage() {
  echo "usage: $0 --before a.mp4 --after b.mp4 --out out" >&2
  echo "       $0 --demo a.mp4 --out out" >&2
  echo "       $0 shot in.png out.png" >&2
  exit 2
}

for tool in ffmpeg ffprobe magick; do
  command -v "$tool" >/dev/null || { echo "$tool not found (brew install ffmpeg imagemagick)" >&2; exit 1; }
done
work=$(mktemp -d "${TMPDIR:-/tmp}/pr-recording.XXXXXX")
trap 'rm -rf "$work"' EXIT
frame="$work/frame.png"
mask="$work/mask.png"

# Frame: a generic Android bezel with a transparent screen cutout and a punch-hole camera.
magick -size "${dev_w}x${dev_h}" xc:none -fill '#16171C' \
  -draw "roundrectangle 0,0 $((dev_w - 1)),$((dev_h - 1)) $dev_radius,$dev_radius" \
  \( -size "${dev_w}x${dev_h}" xc:none -fill black \
    -draw "roundrectangle $bezel,$bezel $((bezel + screen_w - 1)),$((bezel + screen_h - 1)) $screen_radius,$screen_radius" \) \
  -compose DstOut -composite \
  -fill '#050506' -draw "circle $cam_x,$cam_y $((cam_x + cam_r)),$cam_y" "$frame"
# Screen mask: the same cutout size and corner radius as the frame.
magick -size "${screen_w}x${screen_h}" xc:black -fill white \
  -draw "roundrectangle 0,0 $((screen_w - 1)),$((screen_h - 1)) $screen_radius,$screen_radius" "$mask"
# Drop shadow of a frame, on a canvas 100 px larger on every side.
shadow() {
  magick "$1" -bordercolor none -border 100 -background '#2B2530' -shadow 30x22+0+24 "$2"
}

if [ "${1:-}" = shot ]; then
  [ $# -eq 3 ] || usage
  [ -f "$2" ] || { echo "no such image: $2" >&2; exit 1; }
  # 1.5x the frame, so the PNG is 760 px wide and the screen is 540x1170 px.
  magick "$frame" -filter Lanczos -resize 150% "$work/frame-big.png"
  magick "$mask" -filter Lanczos -resize 150% "$work/mask-big.png"
  magick "$2" -resize 540x1170^ -gravity north -extent 540x1170 +repage "$work/screen.png"
  magick "$work/screen.png" "$work/mask-big.png" -compose CopyOpacity -composite "$work/screen-masked.png"
  shadow "$work/frame-big.png" "$work/shadow.png"
  # The device body sits under the screen, so the anti-aliased edge of the cutout
  # shows dark bezel rather than the light stage.
  magick -size 760x1374 xc:'#F3F0EA' \
    "$work/shadow.png" -geometry "$(printf '%+d%+d' -2 -10)" -composite \
    -fill '#16171C' -draw "roundrectangle 98,90 661,1283 84,84" \
    "$work/screen-masked.png" -geometry +110+102 -composite \
    "$work/frame-big.png" -geometry +98+90 -composite -depth 8 "$3"
  echo "wrote $3"
  exit 0
fi

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
for clip in "${clips[@]}"; do
  [ -f "$clip" ] || { echo "no such clip: $clip" >&2; exit 1; }
done

n=${#clips[@]}

# Layout in pixels. A device is the frame, and its screen starts at (bezel, bezel).
# A pair sits on two tinted halves with a VS badge between the devices and a label
# pill above each. A demo is one device on a neutral background. The strip under
# the devices, 72 px from the bottom, holds the timer.
top=172
if [ "$n" -eq 2 ]; then
  width=1120 height=1056 xs=(92 652)
  magick -size "560x$height" gradient:'#FDECEA-#F4CECC' "$work/half-before.png"
  magick -size "560x$height" gradient:'#EAF9F0-#CDEEDB' "$work/half-after.png"
  stage=(-size "${width}x${height}" xc:none "$work/half-before.png" -geometry +0+0 -composite
    "$work/half-after.png" -geometry +560+0 -composite)
else
  width=720 height=1056 xs=(172)
  stage=(-size "${width}x${height}" xc:'#F3F0EA')
fi

# Stage: the background, a soft shadow under each frame, and for a pair the
# VS badge and the label pills.
shadow "$frame" "$work/shadow.png"
for x in "${xs[@]}"; do
  stage+=("$work/shadow.png" -geometry "$(printf '%+d%+d' $((x - 100)) $((top - 100)))" -composite
    -fill '#16171C' -draw "roundrectangle $x,$top $((x + dev_w - 1)),$((top + dev_h - 1)) $dev_radius,$dev_radius")
done
if [ "$n" -eq 2 ]; then
  stage+=(-fill '#1C191F' -draw "circle 560,570 618,570"
    -fill '#D9463E' -draw "roundrectangle 140,56 419,131 38,38"
    -fill '#1E9E62' -draw "roundrectangle 700,56 979,131 38,38"
    -font "$font" -pointsize 42 -gravity center -fill white -stroke white -strokewidth 0.8
    -annotate +0+42 VS -annotate -280-435 Before -annotate +280-435 After)
fi
magick "${stage[@]}" "$work/stage.png"

# Timer: one label per elapsed second, as "MM:SS / MM:SS". The labels are rendered
# with ImageMagick because some ffmpeg builds lack drawtext.
secs=$(for clip in "${clips[@]}"; do
  ffprobe -v error -show_entries format=duration -of csv=p=0 "$clip"
done | awk -v cap="$max_seconds" '{ if ($1 > m) m = $1 } END { if (m > cap) m = cap; printf "%.2f", m }')
total=$(awk -v s="$secs" 'BEGIN { t = int(s); if (t < s) t++; print t }')
for ((k = 0; k < total; k++)); do
  printf -v label '%02d:%02d / %02d:%02d' $((k / 60)) $((k % 60)) $((total / 60)) $((total % 60))
  magick -size "${width}x56" xc:none -font "$font" -pointsize 34 -fill '#1C191F' \
    -gravity center -annotate +0+0 "$label" "$(printf '%s/t%03d.png' "$work" "$k")"
done

# Filter graph: each clip covers the screen, is freeze-padded, masked to the
# rounded screen, and overlaid under its frame. Input 0 is the stage, inputs
# 1..n the clips, n+1 the frame, n+2 the mask and n+3 the timer labels.
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
  parts+=("[$((i + 1)):v]fps=30,scale=360:780:force_original_aspect_ratio=increase,crop=360:780:(iw-ow)/2:0,setsar=1,format=yuv420p,tpad=stop_mode=clone:stop_duration=${max_seconds}[c$i]")
  parts+=("[c$i][m$i]alphamerge[k$i]")
  parts+=("[$cur][k$i]overlay=$((x + 8)):$((top + 8))[t$i]")
  parts+=("[t$i][f$i]overlay=${x}:${top}[u$i]")
  cur=u$i
done
parts+=("[$((n + 3)):v]format=rgba,fps=30[tm]")
parts+=("[$cur][tm]overlay=0:$((height - 72))[tt]")
parts+=("[tt]format=yuv420p[out]")
graph=$(IFS=';'; echo "${parts[*]}")

size_of() { wc -c <"$1" | tr -d ' '; }

inputs=(-framerate 30 -loop 1 -i "$work/stage.png")
for clip in "${clips[@]}"; do inputs+=(-i "$clip"); done
inputs+=(-framerate 30 -loop 1 -i "$frame" -framerate 30 -loop 1 -i "$mask")
inputs+=(-framerate 1 -start_number 0 -i "$work/t%03d.png")

# MP4: step the quality down, then the frame rate, until it fits.
for pass in "22 30" "25 30" "28 30" "31 30" "31 24" "31 15"; do
  read -r crf fps <<<"$pass"
  ffmpeg -v error -y "${inputs[@]}" -filter_complex "$graph" -map '[out]' -t "$secs" -an \
    -r "$fps" -c:v libx264 -preset slow -crf "$crf" -pix_fmt yuv420p -movflags +faststart "$out.mp4"
  [ "$(size_of "$out.mp4")" -le "$max_bytes" ] && break
done

# GIF: step the frame rate, width and palette size down until it fits.
for pass in "10 960 256" "8 960 256" "8 720 256" "6 720 256" "5 600 256" "5 600 128" "4 480 128" "4 480 64"; do
  read -r fps max_width colors <<<"$pass"
  ffmpeg -v error -y -i "$out.mp4" -filter_complex \
    "fps=$fps,scale='min($max_width,iw)':-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=$colors:stats_mode=diff[p];[b][p]paletteuse=dither=sierra2_4a:diff_mode=rectangle" \
    -loop 0 "$out.gif"
  [ "$(size_of "$out.gif")" -le "$max_bytes" ] && break
done

for f in "$out.mp4" "$out.gif"; do
  [ "$(size_of "$f")" -le "$max_bytes" ] || { echo "$f is larger than 10 MB" >&2; exit 1; }
done
echo "wrote $out.mp4 ($secs s) and $out.gif"
