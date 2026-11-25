#!/usr/bin/env bash
# One comparison style for screen recordings in pull requests. Shows a Before and After pair, or one
# feature panel, on Android Studio's Pixel 7 device art. The art is read at run time and not stored here.
#
#   compose.sh --before a.mp4 --after b.mp4 --before-note TEXT --after-note TEXT --out DIR
#              [--before-start S] [--after-start S] [--key S]
#   compose.sh --clip c.mp4 --label TEXT --note TEXT [--tone neutral|green|red] [--start S] [--key S] --out DIR
#
# An input is a capture.sh screen (9:20, at least 720 px wide, real bars), as a clip or as a PNG still. Each one fills
# the phone's screen exactly. The device art gives the rounded corners and the camera. The start offsets
# trim the clips, so that both sides line up on the same moment.
#
# Writes comparison.mp4 (H.264, yuv420p, faststart), comparison.gif and comparison.png, or single.* for
# the feature panel. The PNG is the frame at --key seconds (default 3). Each file is at most 10 MB. Inputs
# that are all stills write only the PNG. Needs ffmpeg, ffprobe and ImageMagick (magick). See README.md.
set -euo pipefail

max_bytes=10000000
max_seconds=60
font=${PR_RECORDING_FONT:-/System/Library/Fonts/Supplemental/DIN Alternate Bold.ttf}

usage() {
  cat >&2 <<'EOF'
usage: compose.sh --before IN --after IN --before-note TEXT --after-note TEXT --out DIR [--before-start S] [--after-start S] [--key S]
       compose.sh --clip IN --label TEXT --note TEXT [--tone neutral|green|red] [--start S] [--key S] --out DIR
EOF
  exit 2
}

before='' after='' before_note='' after_note='' before_start=0 after_start=0
clip='' label='' note='' tone=neutral start=0 key=3 out=''
while [ $# -gt 0 ]; do
  case $1 in
    --before) before=${2:?}; shift 2 ;;
    --after) after=${2:?}; shift 2 ;;
    --before-note) before_note=${2:?}; shift 2 ;;
    --after-note) after_note=${2:?}; shift 2 ;;
    --before-start) before_start=${2:?}; shift 2 ;;
    --after-start) after_start=${2:?}; shift 2 ;;
    --clip) clip=${2:?}; shift 2 ;;
    --label) label=${2:?}; shift 2 ;;
    --note) note=${2:?}; shift 2 ;;
    --tone) tone=${2:?}; shift 2 ;;
    --start) start=${2:?}; shift 2 ;;
    --key) key=${2:?}; shift 2 ;;
    --out) out=${2:?}; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$out" ] || usage

if [ -n "$clip" ]; then
  [ -z "$before$after" ] && [ -n "$label" ] && [ -n "$note" ] || usage
  inputs=("$clip") starts=("$start") labels=("$label") notes=("$note") tones=("$tone") name=single
else
  [ -z "$label$note" ] && [ -n "$before" ] && [ -n "$after" ] && [ -n "$before_note" ] && [ -n "$after_note" ] || usage
  inputs=("$before" "$after") starts=("$before_start" "$after_start") labels=(Before After)
  notes=("$before_note" "$after_note") tones=(red green) name=comparison
fi
for t in "${tones[@]}"; do
  case $t in red | green | neutral) ;; *) usage ;; esac
done
n=${#inputs[@]}

here=$(cd "$(dirname "$0")" && pwd)
. "$here/lib.sh"

for tool in ffmpeg ffprobe magick awk; do
  command -v "$tool" >/dev/null || { echo "$tool not found (brew install ffmpeg imagemagick)" >&2; exit 1; }
done
[ -f "$font" ] || { echo "font not found: $font (set PR_RECORDING_FONT to a bold sans)" >&2; exit 1; }

art=${PR_RECORDING_DEVICE_ART:-}
if [ -z "$art" ]; then
  for d in "/Applications/Android Studio"*.app/Contents/plugins/android/resources/device-art-resources/pixel_7; do
    if [ -d "$d" ]; then art=$d; break; fi
  done
fi
for file in "${art:-/nonexistent}/back.webp" "${art:-/nonexistent}/mask.webp"; do
  [ -f "$file" ] || {
    echo "Pixel 7 device art not found in /Applications/Android Studio*.app (install Android Studio, or set PR_RECORDING_DEVICE_ART)" >&2
    exit 1
  }
done

for f in "${inputs[@]}"; do [ -f "$f" ] || { echo "no such file: $f" >&2; exit 1; }; done

# Each input must be a real screen: 9:20, at least 720 px wide, with its status bar and navigation bar.
# still[i] is 1 for a PNG.
still=()
for ((i = 0; i < n; i++)); do
  f=${inputs[i]}
  case $f in *.png | *.PNG) still[i]=1 ;; *) still[i]=0 ;; esac
  check_capture "$f" || exit 1
  check_screen "$f" "${starts[i]}" || exit 1
done

# Length: the longest clip after its start offset, capped at max_seconds. Stills take any length.
secs=0
for ((i = 0; i < n; i++)); do
  [ "${still[i]}" -eq 1 ] && continue
  d=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "${inputs[i]}")
  secs=$(awk -v a="$secs" -v d="$d" -v s="${starts[i]}" -v cap="$max_seconds" 'BEGIN {
    x = d - s; if (x <= 0) exit 1; if (x > a) a = x; if (a > cap) a = cap; printf "%.3f", a }') \
    || { echo "${inputs[i]}: start ${starts[i]} is past the end of the clip" >&2; exit 1; }
done

# Layout, in output pixels. The Pixel 7 art is 1200x2541, and its 1080x2400 display sits at (59, 58) in it.
margin=40 panel_w=1130 gap=60 pad=60 radius=48 dev_top=266
disp_x=59 disp_y=58 disp_w=1080 disp_h=2400
read -r art_w art_h < <(magick identify -format '%w %h\n' "$art/back.webp" | head -1)
dev_w=$((panel_w - 2 * pad))
dev_h=$(awk -v h="$art_h" -v w="$art_w" -v dw="$dev_w" 'BEGIN { printf "%d", h * dw / w + 0.5 }')
panel_h=$((dev_top + dev_h + pad))
canvas_w=$((2 * panel_w + gap + 2 * margin))
[ "$n" -eq 2 ] || canvas_w=$((panel_w + 2 * margin))
canvas_h=$((panel_h + 2 * margin))
canvas_h=$((canvas_h + canvas_h % 2))

# The screen rectangle in device coordinates, rounded outwards so that it covers the display cutout.
sx=$(awk -v a="$dev_w" -v b="$art_w" 'BEGIN { printf "%.12f", a / b }')
sy=$(awk -v a="$dev_h" -v b="$art_h" 'BEGIN { printf "%.12f", a / b }')
scr_x0=$(awk -v v="$disp_x" -v s="$sx" 'BEGIN { printf "%d", int(v * s) }')
scr_y0=$(awk -v v="$disp_y" -v s="$sy" 'BEGIN { printf "%d", int(v * s) }')
scr_x1=$(awk -v v="$((disp_x + disp_w))" -v s="$sx" 'BEGIN { x = v * s; printf "%d", (x == int(x)) ? x : int(x) + 1 }')
scr_y1=$(awk -v v="$((disp_y + disp_h))" -v s="$sy" 'BEGIN { x = v * s; printf "%d", (x == int(x)) ? x : int(x) + 1 }')
scr_w=$((scr_x1 - scr_x0)) scr_h=$((scr_y1 - scr_y0))

work=$(mktemp -d "${TMPDIR:-/tmp}/pr-recording.XXXXXX")
trap 'rm -rf "$work"' EXIT

# Width and height of a text line at point size $2, and the same text escaped for ImageMagick.
text_width() { magick -font "$font" -pointsize "$2" label:"$(printf '%s' "$1" | sed 's/%/%%/g')" -format '%w' info:; }
escaped() { printf '%s' "$1" | sed 's/%/%%/g'; }

# A pill label of text $1 in colour $2, as PNG $3. Its width is left in PILL_W.
pill() {
  local ps=44 ph=88 pw
  pw=$(( $(text_width "$1" $ps) + 2 * 44 ))
  magick -size "${pw}x${ph}" xc:none -fill "$2" -draw "roundrectangle 0,0 $((pw - 1)),$((ph - 1)) $((ph / 2)),$((ph / 2))" \
    -font "$font" -pointsize $ps -fill white -gravity center -annotate +0+0 "$(escaped "$1")" "$3"
  PILL_W=$pw
}

# A callout line for text $1 with the icon for tone $2, as PNG $3. Its width is left in CALLOUT_W.
callout() {
  local ps=34 dw=44 gap=16 h=52 w disc icon
  w=$(( dw + gap + $(text_width "$1" $ps) + 4 ))
  case $2 in
    red)
      disc='#E5484D'
      icon=(-stroke white -strokewidth 5 -fill none -draw "line 15,19 29,33" -draw "line 29,19 15,33") ;;
    green)
      disc='#1F9D55'
      icon=(-stroke white -strokewidth 5 -fill none -draw "polyline 15,27 20,32 30,20") ;;
    *)
      disc='#1B2A4A'
      icon=(-stroke none -fill white -draw "circle 22,26 22,19") ;;
  esac
  magick -size "${w}x${h}" xc:none -fill "$disc" -stroke none -draw "circle 22,26 22,4" "${icon[@]}" \
    -font "$font" -pointsize $ps -fill '#1C1C1E' -stroke none -gravity west \
    -annotate +$((dw + gap))+0 "$(escaped "$1")" "$3"
  CALLOUT_W=$w
}

# Panel tints and pill colours, by tone.
tint() { case $1 in red) echo '#FDECEC' ;; green) echo '#E8F6EE' ;; *) echo '#EEF2F7' ;; esac; }
pill_colour() { case $1 in red) echo '#E5484D' ;; green) echo '#1F9D55' ;; *) echo '#1B2A4A' ;; esac; }

# The phone body, scaled to the panel, and the screen mask over it, as the layers in $work.
magick "$art/back.webp" -resize "${dev_w}x${dev_h}!" "$work/body.png"
magick "$art/mask.webp" -resize "${scr_w}x${scr_h}!" "$work/mask.png"
# The soft shadow under the phone: a blurred rounded rectangle, 14 px lower, with 60 px of room around it.
magick -size "$((dev_w + 120))x$((dev_h + 120))" xc:none -fill '#00000066' \
  -draw "roundrectangle 60,74 $((60 + dev_w - 1)),$((74 + dev_h - 1)) 90,90" -blur 0x16 "$work/shadow.png"

# The stage: panels, labels, callouts, phone shadows and the VS badge (pair only). Nothing is drawn over a screen.
bg=(-size "${canvas_w}x${canvas_h}" xc:white)
comp=()
for ((i = 0; i < n; i++)); do
  if [ "$n" -eq 2 ]; then px=$((margin + i * (panel_w + gap))); else px=$margin; fi
  bg+=(-fill "$(tint "${tones[i]}")" -draw "roundrectangle $px,$margin $((px + panel_w - 1)),$((margin + panel_h - 1)) $radius,$radius")
  pill "${labels[i]}" "$(pill_colour "${tones[i]}")" "$work/pill-$i.png"
  comp+=("$work/pill-$i.png" -geometry "+$((px + (panel_w - PILL_W) / 2))+$((margin + 56))" -composite)
  callout "${notes[i]}" "${tones[i]}" "$work/callout-$i.png"
  comp+=("$work/callout-$i.png" -geometry "+$((px + (panel_w - CALLOUT_W) / 2))+$((margin + 166))" -composite)
  comp+=("$work/shadow.png" -geometry "+$((px + pad - 60))+$((margin + dev_top - 60))" -composite)
done
if [ "$n" -eq 2 ]; then
  magick -size 220x220 xc:none -fill '#00000080' -draw "circle 110,118 110,28" -blur 0x10 \
    -fill '#1B2A4A' -draw "circle 110,110 110,30" \
    -font "$font" -pointsize 60 -fill white -gravity center -annotate +0+0 VS "$work/badge.png"
  comp+=("$work/badge.png" -geometry "+$((canvas_w / 2 - 110))+$((margin + dev_top + dev_h / 2 - 110))" -composite)
fi
magick "${bg[@]}" "${comp[@]}" -depth 8 "$work/stage.png"

# The front layer: the phone body and the mask, over the screens. Transparent everywhere else.
front=(-size "${canvas_w}x${canvas_h}" xc:none)
for ((i = 0; i < n; i++)); do
  if [ "$n" -eq 2 ]; then px=$((margin + i * (panel_w + gap))); else px=$margin; fi
  dx=$((px + pad)) dy=$((margin + dev_top))
  front+=("$work/body.png" -geometry "+$dx+$dy" -composite "$work/mask.png" -geometry "+$((dx + scr_x0))+$((dy + scr_y0))" -composite)
  screens_at[i]="$((dx + scr_x0)) $((dy + scr_y0))"
done
magick "${front[@]}" PNG32:"$work/front.png"

# Inputs: the stage, one per screen, then the front layer.
inputs_args=(-framerate 30 -loop 1 -i "$work/stage.png")
for ((i = 0; i < n; i++)); do
  if [ "${still[i]}" -eq 1 ]; then
    inputs_args+=(-framerate 30 -loop 1 -i "${inputs[i]}")
  else
    inputs_args+=(-ss "${starts[i]}" -i "${inputs[i]}")
  fi
done
inputs_args+=(-framerate 30 -loop 1 -i "$work/front.png")

# The filter graph, ending in pixel format $1. Each screen is scaled to the screen rectangle and laid on the
# stage; the front layer goes over it, so the cutout and the corners come from the device art.
build_graph() {
  local fmt=$1 parts=() i cur=s0 at
  parts+=("[0:v]format=rgba[s0]")
  for ((i = 0; i < n; i++)); do
    if [ "${still[i]}" -eq 1 ]; then
      parts+=("[$((i + 1)):v]format=rgba,scale=$scr_w:$scr_h:flags=lanczos[c$i]")
    else
      parts+=("[$((i + 1)):v]fps=30,scale=$scr_w:$scr_h:flags=lanczos,format=rgba,tpad=stop_mode=clone:stop_duration=$max_seconds[c$i]")
    fi
    at=(${screens_at[i]})
    parts+=("[$cur][c$i]overlay=${at[0]}:${at[1]}:format=rgb[s$((i + 1))]")
    cur=s$((i + 1))
  done
  parts+=("[$cur][$((n + 1)):v]overlay=0:0:format=rgb,format=$fmt[out]")
  (IFS=';'; echo "${parts[*]}")
}

size_of() { wc -c <"$1" | tr -d ' '; }

if [ "$secs" = 0 ]; then
  ffmpeg -v error -y "${inputs_args[@]}" -filter_complex "$(build_graph rgb24)" -map '[out]' -frames:v 1 "$work/$name.png"
else
  # MP4: step the quality down until it fits.
  for crf in 18 20 23 26; do
    ffmpeg -v error -y "${inputs_args[@]}" -filter_complex "$(build_graph yuv420p)" -map '[out]' -t "$secs" -an \
      -c:v libx264 -preset slow -crf "$crf" -pix_fmt yuv420p -movflags +faststart "$work/$name.mp4"
    [ "$(size_of "$work/$name.mp4")" -le "$max_bytes" ] && break
  done
  # GIF: step the frame rate, width and palette down until it fits.
  for pass in "12 1200 256" "10 1000 256" "10 900 128" "8 800 128"; do
    read -r gif_fps gif_w colours <<<"$pass"
    ffmpeg -v error -y -i "$work/$name.mp4" -filter_complex \
      "[0:v]fps=$gif_fps,scale=$gif_w:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=$colours:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
      -loop 0 "$work/$name.gif"
    [ "$(size_of "$work/$name.gif")" -le "$max_bytes" ] && break
  done
  # PNG: the key frame.
  ffmpeg -v error -y "${inputs_args[@]}" -filter_complex "$(build_graph rgb24)" -map '[out]' -ss "$key" -frames:v 1 "$work/$name.png"
fi

mkdir -p "$out"
for ext in mp4 gif png; do
  [ -f "$work/$name.$ext" ] || continue
  [ "$(size_of "$work/$name.$ext")" -le "$max_bytes" ] || { echo "$name.$ext is larger than 10 MB" >&2; exit 1; }
  cp "$work/$name.$ext" "$out/$name.$ext"
  stamp_compose "$out/$name.$ext"
  echo "wrote $out/$name.$ext ($(size_of "$out/$name.$ext") bytes)"
done
