#!/usr/bin/env bash
# Put a screen recording or a screenshot inside a Pixel 8 for a pull request.
#
#   compose.sh --before a.mp4 --after b.mp4 --out out   two phones with Before and After labels
#   compose.sh --demo a.mp4 --out out                   one phone
#   compose.sh shot in.png out.png                      one phone and one screenshot, no timer
#
# A pair or a demo writes out.mp4 (H.264, at most 60 s, black around the phones) and out.gif
# (transparent around the phones), both at most 10 MB, with an elapsed / total timer under
# the phones. A shot writes a 760 px wide PNG, transparent outside the phone.
# Nothing is cropped. Each clip or screenshot is scaled to fit the display, and a strip left
# over at the bottom is filled with the colour of its own bottom edge. The screen is clipped
# to the rounded corners that the device art leaves open.
# The phone is Android Studio's Pixel 8 device art (1080x2400 display), read at run time from
# DEVICE_ART_DIR. The art is not stored in the repository.
# Needs ffmpeg, ImageMagick (magick) and that device art. See CONTRIBUTING.md.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
font=${PR_RECORDING_FONT:-$here/../../shared/src/main/res/font/archivo_variable.ttf}
art=${DEVICE_ART_DIR:-/Applications/Android Studio.app/Contents/plugins/android/resources/device-art-resources/pixel_8}
max_seconds=60
max_bytes=$((10 * 1024 * 1024))

# Pixel 8 device art (pixel_8/layout): the phone is back.webp, and the 1080x2400 display sits at
# (49, 55) in it. mask.webp is the camera and the black corners, drawn over the screen.
disp_x=49 disp_y=55 disp_w=1080 disp_h=2400
# A length in phone-image pixels, scaled to a phone $2 px wide and rounded to the nearest pixel.
at() { echo $(( ($1 * $2 * 2 + body_w) / (2 * body_w) )); }

usage() {
  echo "usage: $0 --before a.mp4 --after b.mp4 --out out" >&2
  echo "       $0 --demo a.mp4 --out out" >&2
  echo "       $0 shot in.png out.png" >&2
  exit 2
}

for tool in ffmpeg ffprobe magick od awk; do
  command -v "$tool" >/dev/null || { echo "$tool not found (brew install ffmpeg imagemagick)" >&2; exit 1; }
done
for file in "$art/back.webp" "$art/mask.webp"; do
  [ -f "$file" ] || { echo "Pixel 8 device art not found: $file (install Android Studio, or set DEVICE_ART_DIR)" >&2; exit 1; }
done
read -r body_w body_h < <(magick identify -format '%w %h\n' "$art/back.webp" | head -1)
work=$(mktemp -d "${TMPDIR:-/tmp}/pr-recording.XXXXXX")
trap 'rm -rf "$work"' EXIT

# The phone, the display mask and the screen shape at $1 px wide, written as $2-back.png,
# $2-mask.png and $2-shape.png. Sets PH (phone height), and DX, DY, DW, DH (the display's offset
# and size). The shape is the transparent hole in the phone image: the body surrounds it, so the
# corners outside the phone are left out, and flood-filling from the middle selects only the hole.
layers() {
  local pw=$1 name=$2 cx cy
  PH=$(at "$body_h" "$pw") DX=$(at "$disp_x" "$pw") DY=$(at "$disp_y" "$pw")
  # Even sizes for yuv420p, rounded up so the screen reaches under the bezel's edge.
  DW=$(( ($(at "$disp_w" "$pw") + 1) / 2 * 2 )) DH=$(( ($(at "$disp_h" "$pw") + 1) / 2 * 2 ))
  magick "$art/back.webp" -resize "${pw}x${PH}!" "$work/$name-back.png"
  magick "$art/mask.webp" -resize "${DW}x${DH}!" "$work/$name-mask.png"
  # The mask's corner tips lie outside the phone's outline, so keep it only where the phone is.
  magick "$work/$name-mask.png" \( "$work/$name-back.png" -crop "${DW}x${DH}+$DX+$DY" +repage \) \
    -compose DstIn -composite "$work/$name-mask.png"
  cx=$((DX + DW / 2)) cy=$((DY + DH / 2))
  magick "$work/$name-back.png" -alpha extract -fill 'gray(50%)' -draw "color $cx,$cy floodfill" \
    -fill black +opaque 'gray(50%)' -fill white -opaque 'gray(50%)' \
    -morphology Dilate Disk:2 -crop "${DW}x${DH}+$DX+$DY" +repage "$work/$name-shape.png"
}

# The size an $1 x $2 image takes when it fits inside the display, rounded down to even pixels.
fit() {
  local iw=$1 ih=$2 w h
  if [ $((iw * DH)) -gt $((ih * DW)) ]; then w=$DW h=$((ih * DW / iw)); else h=$DH w=$((iw * DH / ih)); fi
  echo $((w / 2 * 2)) $((h / 2 * 2))
}

# The colour of the bottom-middle pixel of a still, as #RRGGBB.
still_edge() {
  local w h
  read -r w h < <(magick identify -format '%w %h\n' "$1" | head -1)
  magick "$1" -alpha off -depth 8 -crop "1x1+$((w / 2))+$((h - 1))" +repage txt:- | grep -o -m1 '#[0-9A-Fa-f]\{6\}'
}

# The average colour of the bottom two rows of a clip's first frame, as 0xRRGGBB for ffmpeg.
clip_edge() {
  ffmpeg -v error -i "$1" -frames:v 1 -vf 'crop=iw:2:0:ih-2,scale=1:1:flags=area' \
    -f rawvideo -pix_fmt rgb24 - | od -An -tu1 | awk 'NR == 1 { printf "0x%02x%02x%02x", $1, $2, $3 }'
}

if [ "${1:-}" = shot ]; then
  [ $# -eq 3 ] || usage
  [ -f "$2" ] || { echo "no such image: $2" >&2; exit 1; }
  # A 760 px phone, with nothing around it: the PNG is transparent outside the device.
  layers 760 shot
  bg=$(still_edge "$2")
  read -r iw ih < <(magick identify -format '%w %h\n' "$2" | head -1)
  read -r fw fh < <(fit "$iw" "$ih")
  magick -size "760x$PH" xc:none \
    \( "$2" -resize "${fw}x${fh}!" -background "$bg" -gravity NorthWest -extent "${DW}x${DH}" +repage \
      "$work/shot-shape.png" -compose CopyOpacity -composite \) \
    -compose Over -gravity NorthWest -geometry "+$DX+$DY" -composite \
    "$work/shot-back.png" -geometry +0+0 -composite \
    "$work/shot-mask.png" -geometry "+$DX+$DY" -composite -depth 8 "$3"
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
pad_c=()
for clip in "${clips[@]}"; do pad_c+=("$(clip_edge "$clip")"); done

# Layout in pixels, with no margin. A pair puts two 480 px phones side by side, 24 px apart,
# under a thin strip of label pills; a demo puts one 600 px phone. The timer is a thin strip
# directly under the phones.
if [ "$n" -eq 2 ]; then pw=480 top=48 gap=24; else pw=600 top=0 gap=0; fi
layers "$pw" dev
fit_w=() fit_h=()
for clip in "${clips[@]}"; do
  read -r iw ih < <(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "$clip" | tr ',' ' ')
  read -r fw fh < <(fit "$iw" "$ih")
  fit_w+=("$fw") fit_h+=("$fh")
done
if [ "$n" -eq 2 ]; then
  width=$((2 * pw + gap)) xs=(0 $((pw + gap)))
else
  width=$pw xs=(0)
fi
strip_y=$((top + PH))
height=$((strip_y + 44))
height=$((height + height % 2))

# Stage: the background, and for a pair the Before and After label pills. The black stage
# is for the MP4; the clear one is for the GIF.
draws=(-fill black)
if [ "$n" -eq 2 ]; then
  labels=(Before After) fills=('#D9463E' '#1E9E62')
  draws+=(-font "$font" -pointsize 26 -stroke white -strokewidth 0.6)
  for i in 0 1; do
    cx=$((xs[i] + pw / 2))
    draws+=(-fill "${fills[i]}" -draw "roundrectangle $((cx - 130)),4 $((cx + 130)),44 20,20"
      -fill white -gravity center -annotate "$(printf '%+d%+d' $((cx - width / 2)) $((24 - height / 2)))" "${labels[i]}" -gravity NorthWest)
  done
fi
magick -size "${width}x${height}" xc:black "${draws[@]}" "$work/stage-black.png"
magick -size "${width}x${height}" xc:none "${draws[@]}" "PNG32:$work/stage-clear.png"

# Length: the longest clip, capped at 60 s.
secs=$(for clip in "${clips[@]}"; do
  ffprobe -v error -show_entries format=duration -of csv=p=0 "$clip"
done | awk -v cap="$max_seconds" '{ if ($1 > m) m = $1 } END { if (m > cap) m = cap; printf "%.2f", m }')

# Timer: one label per elapsed second, as "MM:SS / MM:SS", in a black pill with white text.
# The labels are rendered with ImageMagick because some ffmpeg builds lack drawtext.
total=$(awk -v s="$secs" 'BEGIN { t = int(s); if (t < s) t++; print t }')
for ((k = 0; k < total; k++)); do
  printf -v label '%02d:%02d / %02d:%02d' $((k / 60)) $((k % 60)) $((total / 60)) $((total % 60))
  magick -size "${width}x44" xc:none -fill black -draw "roundrectangle $((width / 2 - 140)),4 $((width / 2 + 140)),40 18,18" \
    -font "$font" -pointsize 26 -fill white -gravity center -annotate +0+0 "$label" \
    "PNG32:$(printf '%s/t%03d.png' "$work" "$k")"
done

# Filter graph for one output, with the stage in pixel format $1 (yuv420p or rgba). Each clip is
# scaled to fit the display and padded with its edge colour, then clipped to the screen shape. The
# screen goes under the phone, and the mask over both. Input 0 is the stage, inputs 1..n the clips, n+1 the
# phone, n+2 the mask, n+3 the screen shape and n+4 the timer labels.
build_graph() {
  local fmt=$1 parts=() split_b='' split_m='' split_h='' cur=s0 i x
  parts+=("[0:v]format=$fmt[s0]")
  for i in $(seq 0 $((n - 1))); do
    split_b+="[b$i]"
    split_m+="[m$i]"
    split_h+="[h$i]"
  done
  parts+=("[$((n + 1)):v]format=rgba,split=$n$split_b")
  parts+=("[$((n + 2)):v]format=rgba,split=$n$split_m")
  parts+=("[$((n + 3)):v]format=gray,split=$n$split_h")
  for i in $(seq 0 $((n - 1))); do
    x=${xs[i]}
    parts+=("[$((i + 1)):v]fps=30,scale=${fit_w[i]}:${fit_h[i]},pad=$DW:$DH:0:0:color=${pad_c[i]},setsar=1,format=yuv420p,tpad=stop_mode=clone:stop_duration=${max_seconds}[c$i]")
    parts+=("[c$i][h$i]alphamerge[k$i]")
    parts+=("[$cur][k$i]overlay=$((x + DX)):$((top + DY)):format=auto[t$i]")
    parts+=("[t$i][b$i]overlay=$x:$top:format=auto[u$i]")
    parts+=("[u$i][m$i]overlay=$((x + DX)):$((top + DY)):format=auto[v$i]")
    cur=v$i
  done
  parts+=("[$((n + 4)):v]format=rgba,fps=30[tm]")
  parts+=("[$cur][tm]overlay=0:$strip_y:format=auto,format=$fmt[out]")
  (IFS=';'; echo "${parts[*]}")
}

size_of() { wc -c <"$1" | tr -d ' '; }

# ffmpeg inputs, with the stage image $1.
inputs_with() {
  inputs=(-framerate 30 -loop 1 -i "$1")
  for clip in "${clips[@]}"; do inputs+=(-i "$clip"); done
  inputs+=(-framerate 30 -loop 1 -i "$work/dev-back.png" -framerate 30 -loop 1 -i "$work/dev-mask.png")
  inputs+=(-framerate 30 -loop 1 -i "$work/dev-shape.png")
  inputs+=(-framerate 1 -start_number 0 -i "$work/t%03d.png")
}

# MP4: step the quality down, then the frame rate, until it fits.
inputs_with "$work/stage-black.png"
graph=$(build_graph yuv420p)
for pass in "22 30" "25 30" "28 30" "31 30" "31 24" "31 15"; do
  read -r crf fps <<<"$pass"
  ffmpeg -v error -y "${inputs[@]}" -filter_complex "$graph" -map '[out]' -t "$secs" -an \
    -r "$fps" -c:v libx264 -preset slow -crf "$crf" -pix_fmt yuv420p -movflags +faststart "$out.mp4"
  [ "$(size_of "$out.mp4")" -le "$max_bytes" ] && break
done

# GIF: transparent around the phones, with one palette entry reserved for transparency.
# Step the frame rate, width and palette size down until it fits.
inputs_with "$work/stage-clear.png"
graph=$(build_graph rgba)
for pass in "10 960 256" "8 960 256" "8 720 256" "6 720 256" "5 600 256" "5 600 128" "4 480 128" "4 480 64"; do
  read -r fps max_width colors <<<"$pass"
  ffmpeg -v error -y "${inputs[@]}" -filter_complex \
    "$graph;[out]trim=duration=$secs,setpts=PTS-STARTPTS,fps=$fps,scale='min($max_width,iw)':-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=$colors:stats_mode=diff:reserve_transparent=1[p];[b][p]paletteuse=dither=sierra2_4a:diff_mode=rectangle:alpha_threshold=128" \
    -loop 0 "$out.gif"
  [ "$(size_of "$out.gif")" -le "$max_bytes" ] && break
done

for f in "$out.mp4" "$out.gif"; do
  [ "$(size_of "$f")" -le "$max_bytes" ] || { echo "$f is larger than 10 MB" >&2; exit 1; }
done
echo "wrote $out.mp4 ($secs s) and $out.gif"
