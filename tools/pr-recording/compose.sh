#!/usr/bin/env bash
# Put a screen recording or a screenshot inside a Pixel 8 for a pull request.
#
#   compose.sh --before a.mp4 --after b.mp4 --out out   two phones with Before and After labels
#   compose.sh --before a.png --after b.png --out out   the same two phones as one transparent PNG
#   compose.sh --demo a.mp4 --out out                   one phone
#   compose.sh shot in.png out.png                      one phone and one screenshot
#   add --app-only directly before an input that has no system bars, e.g. --app-only --after g.mp4
#
# The input should be a real emulator capture at 1080x2400 (adb exec-out screencap -p for a still,
# or a screenrecord clip). Those already show the status bar and the navigation bar. An app screen
# without system bars (a golden) needs --app-only, placed directly before that input: the real bars
# from bars/ are placed around it, chosen by the brightness of the input's top and bottom.
#
# The content is never cropped. It is scaled down, if it must be, and centred so that all four of
# its corners lie inside the phone's visible screen opening, which is worked out from the device art,
# with at least 2 px to spare. The space around it is filled with the colours of its own edges.
#
# A pair or a demo writes out.mp4 (H.264, at most 60 s, black around the phones) and out.gif
# (transparent around the phones), both under 9.5 MB. A still pair writes out.png, a 984 px wide PNG
# with the two phones 24 px apart, transparent outside them. A shot writes a 760 px wide PNG,
# transparent outside the phone. The phone is Android Studio's Pixel 8 device art (1080x2400 display), read at
# run time from DEVICE_ART_DIR. The art is not stored in the repository.
# Needs ffmpeg, ImageMagick (magick) and that device art. See CONTRIBUTING.md.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
font=${PR_RECORDING_FONT:-$here/../../shared/src/main/res/font/archivo_variable.ttf}
art=${DEVICE_ART_DIR:-/Applications/Android Studio.app/Contents/plugins/android/resources/device-art-resources/pixel_8}
bars="$here/bars"
max_seconds=60
max_bytes=9500000

# Pixel 8 device art (pixel_8/layout): the phone is back.webp, and the 1080x2400 display sits at
# (49, 55) in it. mask.webp is the camera and the black corners, drawn over the screen.
disp_x=49 disp_y=55 disp_w=1080 disp_h=2400
# A length in phone-image pixels, scaled to a phone $2 px wide and rounded to the nearest pixel.
at() { echo $(( ($1 * $2 * 2 + body_w) / (2 * body_w) )); }

usage() {
  echo "usage: $0 --before a.mp4 --after b.mp4 --out out" >&2
  echo "       $0 --before a.png --after b.png --out out" >&2
  echo "       $0 --demo a.mp4 --out out" >&2
  echo "       $0 shot [--app-only] in.png out.png" >&2
  echo "       add --app-only directly before an input that has no system bars" >&2
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

# The phone at $1 px wide, written as $2-back.png, $2-mask.png, $2-shape.png and $2-open.png. Sets PH
# (phone height), and DX, DY, DW, DH (the display's offset and size). The shape is the transparent hole
# in the phone image: the body surrounds it, so flood-filling from the middle selects only the hole.
# The opening is what is visible through the hole, which is the hole without the mask's black corners.
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
  magick "$work/$name-shape.png" \( "$work/$name-mask.png" -alpha extract -negate \) \
    -compose Multiply -composite "$work/$name-open.png"
}

# The size and place for an $1 x $2 image inside the display opening $3, with all four corners at least
# 2 px inside it. The image is scaled down a step at a time, from its full size, until that holds. Prints
# "width height x y". Fails when no size works, which is not expected for a phone screen.
fit_inside() {
  local w=$1 h=$2 open=$3 k fw fh x0 y0 v
  for k in $(seq 1000 -5 800); do
    read -r fw fh < <(awk -v w="$w" -v h="$h" -v dw="$DW" -v dh="$DH" -v k="$k" 'BEGIN {
      f = (dw / w < dh / h) ? dw / w : dh / h
      fw = int(w * f * k / 1000); fh = int(h * f * k / 1000)
      print fw - fw % 2, fh - fh % 2 }')
    x0=$(( (DW - fw) / 2 )) y0=$(( (DH - fh) / 2 ))
    v=$(magick "$open" -format "%[fx:p{$((x0 + 2)),$((y0 + 2))}] %[fx:p{$((x0 + fw - 3)),$((y0 + 2))}] %[fx:p{$((x0 + 2)),$((y0 + fh - 3))}] %[fx:p{$((x0 + fw - 3)),$((y0 + fh - 3))}]" info:)
    if awk -v v="$v" 'BEGIN { split(v, a, " "); for (i = 1; i <= 4; i++) if (a[i] < 0.5) exit 1; exit 0 }'; then
      echo "$fw $fh $x0 $y0"
      return 0
    fi
  done
  echo "no size of the content fits inside the display opening" >&2
  return 1
}

# "dark" or "light": the mean brightness of the top (or bottom, for $2) 40 rows of image $1.
theme_of() {
  local w h y=0 v
  read -r w h < <(magick identify -format '%w %h\n' "$1" | head -1)
  [ "$2" = bottom ] && y=$((h - 40))
  v=$(magick "$1" -alpha off -crop "${w}x40+0+$y" +repage -colorspace gray -format '%[fx:mean]' info:)
  awk -v v="$v" 'BEGIN { print (v < 0.5) ? "dark" : "light" }'
}

# The size an $1 x $2 image takes when it fits inside a $3 x $4 box, rounded down to even pixels.
fit_box() {
  local iw=$1 ih=$2 bw=$3 bh=$4 w h
  if [ $((iw * bh)) -gt $((ih * bw)) ]; then w=$bw h=$((ih * bw / iw)); else h=$bh w=$((iw * bh / ih)); fi
  echo $((w / 2 * 2)) $((h / 2 * 2))
}

# The colour of the pixel at $2,$3 of image $1, as #RRGGBB.
pixel_hex() {
  magick "$1" -alpha off -depth 8 -crop "1x1+$2+$3" +repage txt:- | grep -o -m1 '#[0-9A-Fa-f]\{6\}'
}

# A 1080x2400 background for an app screen without system bars, written to $2: the real status bar
# and navigation bar from bars/ that match the image $1, with the space between them filled with
# the colour of the image's left edge. The content is then placed inside the space by the same code
# that places every other input.
bars_background() {
  local w h st nv sh nh box pad fw fh
  [ -f "$bars/status-light.png" ] && [ -f "$bars/nav-dark.png" ] || { echo "system bars missing in $bars" >&2; exit 1; }
  read -r w h < <(magick identify -format '%w %h\n' "$1" | head -1)
  st="$bars/status-$(theme_of "$1" top).png"
  nv="$bars/nav-$(theme_of "$1" bottom).png"
  sh=$(magick identify -format '%h' "$st")
  nh=$(magick identify -format '%h' "$nv")
  box=$((2400 - sh - nh))
  pad=$(pixel_hex "$1" 0 $((h / 2)))
  read -r fw fh < <(fit_box "$w" "$h" 1080 "$box")
  magick -size 1080x2400 "xc:$pad" "$st" -geometry +0+0 -composite "$nv" -geometry "+0+$((2400 - nh))" \
    -composite \( "$1" -resize "${fw}x${fh}!" \) -geometry "+$(( (1080 - fw) / 2 ))+$((sh + (box - fh) / 2))" \
    -composite -depth 8 "$2"
}

# The colour of the bottom-middle pixel of a still, as #RRGGBB.
still_edge() {
  local w h
  read -r w h < <(magick identify -format '%w %h\n' "$1" | head -1)
  pixel_hex "$1" $((w / 2)) $((h - 1))
}

# A display-sized canvas for content placed at $3,$4 with size $5 x $6 in it, written to $2. The space
# around the content takes the colours of the sample image $1's own edges: top, bottom, left and right.
display_canvas() {
  local sample=$1 out=$2 fw=$3 fh=$4 x0=$5 y0=$6 w h top bottom left right
  local draws=(-fill black)
  read -r w h < <(magick identify -format '%w %h\n' "$sample" | head -1)
  top=$(pixel_hex "$sample" $((w / 2)) 1)
  bottom=$(pixel_hex "$sample" $((w / 2)) $((h - 2)))
  left=$(pixel_hex "$sample" 1 $((h / 2)))
  right=$(pixel_hex "$sample" $((w - 2)) $((h / 2)))
  if [ "$y0" -gt 0 ]; then draws+=(-fill "$top" -draw "rectangle 0,0 $((DW - 1)),$((y0 - 1))"); fi
  if [ "$x0" -gt 0 ]; then draws+=(-fill "$left" -draw "rectangle 0,$y0 $((x0 - 1)),$((y0 + fh - 1))"); fi
  if [ $((x0 + fw)) -lt "$DW" ]; then draws+=(-fill "$right" -draw "rectangle $((x0 + fw)),$y0 $((DW - 1)),$((y0 + fh - 1))"); fi
  magick -size "${DW}x${DH}" "xc:$bottom" "${draws[@]}" -depth 8 "$out"
}

# A still scaled and centred inside the display opening $3, as a display-sized PNG in $2.
display_fit_still() {
  local w h fitline fw fh x0 y0
  read -r w h < <(magick identify -format '%w %h\n' "$1" | head -1)
  fitline=$(fit_inside "$w" "$h" "$3") || exit 1
  read -r fw fh x0 y0 <<< "$fitline"
  display_canvas "$1" "$work/canvas.png" "$fw" "$fh" "$x0" "$y0"
  magick "$work/canvas.png" \( "$1" -resize "${fw}x${fh}!" \) -geometry "+$x0+$y0" -composite -depth 8 "$2"
}

# A clip scaled and centred inside the display opening $3, as a display-sized MP4 in $2.
display_fit_clip() {
  local w h fitline fw fh x0 y0
  read -r w h < <(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "$1" | tr ',' ' ')
  fitline=$(fit_inside "$w" "$h" "$3") || exit 1
  read -r fw fh x0 y0 <<< "$fitline"
  ffmpeg -v error -y -i "$1" -frames:v 1 "$work/first.png"
  display_canvas "$work/first.png" "$work/canvas.png" "$fw" "$fh" "$x0" "$y0"
  ffmpeg -v error -y -loop 1 -framerate 30 -i "$work/canvas.png" -i "$1" -filter_complex \
    "[1:v]fps=30,scale=$fw:$fh,format=yuv420p[c];[0:v]format=yuv420p[b];[b][c]overlay=$x0:$y0:shortest=1,format=yuv420p[out]" \
    -map '[out]' -shortest -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p "$2"
}

# The still $1 in a phone $2 px wide, as a transparent PNG of that phone at $4. The phone is the one that
# layers $3 made, so call layers first. Outside the phone the PNG is transparent.
framed_still() {
  local src=$1 pw=$2 name=$3 out=$4
  display_fit_still "$src" "$work/$name-fit.png" "$work/$name-open.png"
  magick -size "${pw}x$PH" xc:none \
    \( "$work/$name-fit.png" "$work/$name-shape.png" -compose CopyOpacity -composite \) \
    -compose Over -gravity NorthWest -geometry "+$DX+$DY" -composite \
    "$work/$name-back.png" -geometry +0+0 -composite \
    "$work/$name-mask.png" -geometry "+$DX+$DY" -composite -depth 8 "$out"
}

if [ "${1:-}" = shot ]; then
  app=0
  if [ "${2:-}" = --app-only ]; then app=1; set -- shot "${@:3}"; fi
  [ $# -eq 3 ] || usage
  src=$2 out=$3
  [ -f "$src" ] || { echo "no such image: $src" >&2; exit 1; }
  if [ $app -eq 1 ]; then
    bars_background "$src" "$work/still-bars.png"
    src=$work/still-bars.png
  fi
  layers 760 shot
  framed_still "$src" 760 shot "$out"
  echo "wrote $out"
  exit 0
fi

before='' after='' demo='' out='' still=0
pending=0 before_app=0 after_app=0 demo_app=0
while [ $# -gt 0 ]; do
  case $1 in
    --before) before=${2:?}; before_app=$pending; pending=0; shift 2 ;;
    --after) after=${2:?}; after_app=$pending; pending=0; shift 2 ;;
    --demo) demo=${2:?}; demo_app=$pending; pending=0; shift 2 ;;
    --out) out=${2:?}; shift 2 ;;
    --app-only) pending=1; shift ;;
    *) usage ;;
  esac
done
[ -n "$out" ] || usage
if [ -n "$demo" ]; then
  [ -z "$before$after" ] || usage
  clips=("$demo") apps=("$demo_app")
else
  [ -n "$before" ] && [ -n "$after" ] || usage
  if [ "${before##*.}" = png ] || [ "${after##*.}" = png ]; then
    [ "${before##*.}" = png ] && [ "${after##*.}" = png ] || { echo "--before and --after must both be PNG stills, or both clips" >&2; exit 2; }
    still=1
  fi
  clips=("$before" "$after") apps=("$before_app" "$after_app")
fi
for clip in "${clips[@]}"; do
  [ -f "$clip" ] || { echo "no such clip: $clip" >&2; exit 1; }
done

n=${#clips[@]}
# Layout in pixels, with no margin. A pair puts two 480 px phones side by side, 24 px apart, under a
# thin strip for the Before and After labels; a demo puts one 600 px phone.
if [ "$n" -eq 2 ]; then pw=480 top=48 gap=24; else pw=600 top=0 gap=0; fi
layers "$pw" dev

if [ "$n" -eq 2 ]; then
  width=$((2 * pw + gap)) xs=(0 $((pw + gap)))
else
  width=$pw xs=(0)
fi
height=$((top + PH))
height=$((height + height % 2))

# Stage: the background, and for a pair the Before and After labels, centred above each phone. The
# label is neutral dark grey on a light pill, so it reads on GitHub's light and dark themes alike.
# The black stage is for the MP4; the clear one is for the GIF and the still.
draws=(-fill black)
if [ "$n" -eq 2 ]; then
  labels=(Before After)
  draws+=(-font "$font" -pointsize 28)
  for i in 0 1; do
    cx=$((xs[i] + pw / 2))
    draws+=(-fill '#f2f2f2' -draw "roundrectangle $((cx - 60)),4 $((cx + 60)),44 6,6"
      -fill '#3a3a3c' -gravity center
      -annotate "$(printf '%+d%+d' $((cx - width / 2)) $((24 - height / 2)))" "${labels[i]}" -gravity NorthWest)
  done
fi

if [ "$still" -eq 1 ]; then
  for i in 0 1; do
    src=${clips[i]}
    if [ "${apps[i]}" -eq 1 ]; then
      bars_background "$src" "$work/still-bars-$i.png"
      src=$work/still-bars-$i.png
    fi
    framed_still "$src" "$pw" dev "$work/still-$i.png"
  done
  magick -size "${width}x${height}" xc:none "${draws[@]}" \
    "$work/still-0.png" -geometry "+${xs[0]}+$top" -composite \
    "$work/still-1.png" -geometry "+${xs[1]}+$top" -composite -depth 8 "PNG32:$out.png"
  echo "wrote $out.png"
  exit 0
fi

for ((i = 0; i < n; i++)); do
  if [ "${apps[i]}" -eq 1 ]; then
    # An input marked --app-only has no system bars: it gets the real bars first, as a 1080x2400 clip.
    ffmpeg -v error -y -i "${clips[i]}" -frames:v 1 "$work/app-first-$i.png"
    bars_background "$work/app-first-$i.png" "$work/app-bars-$i.png"
    ffmpeg -v error -y -loop 1 -framerate 30 -i "$work/app-bars-$i.png" -i "${clips[i]}" -filter_complex \
      "[1:v]fps=30,format=yuv420p[c];[0:v][c]overlay=0:0:shortest=1,format=yuv420p[out]" \
      -map '[out]' -shortest -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p "$work/app-$i.mp4"
    clips[i]="$work/app-$i.mp4"
  fi
  display_fit_clip "${clips[i]}" "$work/fit-$i.mp4" "$work/dev-open.png"
  clips[i]="$work/fit-$i.mp4"
done

magick -size "${width}x${height}" xc:black "${draws[@]}" "$work/stage-black.png"
magick -size "${width}x${height}" xc:none "${draws[@]}" "PNG32:$work/stage-clear.png"

# Length: the longest clip, capped at 60 s.
secs=$(for clip in "${clips[@]}"; do
  ffprobe -v error -show_entries format=duration -of csv=p=0 "$clip"
done | awk -v cap="$max_seconds" '{ if ($1 > m) m = $1 } END { if (m > cap) m = cap; printf "%.2f", m }')

# Filter graph for one output, with the stage in pixel format $1 (yuv420p or rgba). Each clip, already
# the size of the display, is clipped to the screen shape. The phone goes under it and the mask over it.
# Input 0 is the stage, inputs 1..n the clips, n+1 the phone, n+2 the mask and n+3 the screen shape.
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
    parts+=("[$((i + 1)):v]fps=30,format=yuv420p,tpad=stop_mode=clone:stop_duration=${max_seconds}[c$i]")
    parts+=("[c$i][h$i]alphamerge[k$i]")
    parts+=("[$cur][k$i]overlay=$((x + DX)):$((top + DY)):format=auto[t$i]")
    parts+=("[t$i][b$i]overlay=$x:$top:format=auto[u$i]")
    parts+=("[u$i][m$i]overlay=$((x + DX)):$((top + DY)):format=auto[v$i]")
    cur=v$i
  done
  parts+=("[$cur]format=$fmt[out]")
  (IFS=';'; echo "${parts[*]}")
}

size_of() { wc -c <"$1" | tr -d ' '; }

# ffmpeg inputs, with the stage image $1.
inputs_with() {
  inputs=(-framerate 30 -loop 1 -i "$1")
  for clip in "${clips[@]}"; do inputs+=(-i "$clip"); done
  inputs+=(-framerate 30 -loop 1 -i "$work/dev-back.png" -framerate 30 -loop 1 -i "$work/dev-mask.png")
  inputs+=(-framerate 30 -loop 1 -i "$work/dev-shape.png")
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
  [ "$(size_of "$f")" -le "$max_bytes" ] || { echo "$f is larger than 9.5 MB" >&2; exit 1; }
done
echo "wrote $out.mp4 ($secs s) and $out.gif"
