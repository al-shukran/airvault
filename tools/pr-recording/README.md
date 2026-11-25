# PR screen recordings

Pull request media is produced only with these three scripts, in this order. Do not hand-frame, crop or hand-post anything: not Robolectric goldens, not cropped captures, not your own phone frame, not hand-written image markdown.

1. **Capture** each state on an emulator. `capture.sh` puts SystemUI in demo mode (12:00, full battery, wifi with 4 bars, no notifications), takes the shot, and refuses anything that is not 9:20 at 720 px wide or more with a real status bar and navigation bar:

   ```
   capture.sh --serial emulator-5554 --out before.png
   capture.sh --serial emulator-5554 --out clip.mp4 --seconds 10    # a clip
   ```

   Take Before on a build of the commit before your change, and After on your branch, with the same app state, the same text, and the same font scale. A clip is a loop of screencaps, because `screenrecord` stops early on the emulator, so move the screen while it runs.
2. **Compose** the pair into the one Before/After design: `compose.sh --before before.png --after after.png --before-note "..." --after-note "..." --out out-1.0`.
3. **Post** it: `post.sh --pr 1030 --dir out-1.0 --title "Font Scale 1.0"`. This is the only way media reaches a pull request. It uploads the files with `gh pr comment --attach` and writes the fixed block: a `###` title, the comparison as `<img width="760">`, and the MP4 for clips. Run it once per comparison, then link the comments from the PR description.

`compose.sh` and `capture.sh` share one screen check (`lib.sh`), so stills and clips look the same.

The phone is Android Studio's Pixel 7 device art, read at run time from `/Applications/Android Studio*.app`. The art is not stored in this repository.

## Usage

```
# A before/after pair: comparison.mp4, comparison.gif, comparison.png
compose.sh --before before.mp4 --after after.mp4 \
  --before-note "Names Lensly inside Lumio" --after-note "Names Lumio" \
  --before-start 0.6 --after-start 0.6 --key 3 --out pr-1015

# One feature: single.mp4, single.gif, single.png
compose.sh --clip demo.mp4 --label New --note "Shows the new flow" [--tone neutral|green|red] [--start 0.5] --out pr-945

# A still screen: give PNG inputs, and only single.png is written
compose.sh --clip shot.png --label New --note "Shows the new screen" --out pr-945
```

| Option | Meaning |
| --- | --- |
| `--before`, `--after` | Input for each side: a 9:20 clip or a PNG. Both sides may mix clips and stills. |
| `--before-note`, `--after-note` | One line of text under the Before or After label. |
| `--before-start`, `--after-start` | Seconds to cut from the start of a clip, to line the two sides up on the same moment. |
| `--clip`, `--label`, `--note`, `--tone`, `--start` | Feature mode: one input, one label, one note, and a tone. Tones are `neutral` (default), `green` and `red`. |
| `--key` | The second of the video whose frame becomes the PNG. Default 3. |
| `--out` | Output directory. It is created if needed. |

Trims only cut the start of a clip. To cut the end, trim the clip before running the tool.

## Inputs

Each input is a `capture.sh` file: 9:20, at least 720 px wide, with a real status bar and navigation bar. `compose.sh` rejects anything else. Each screen is scaled to fill the phone's screen exactly. The device art gives the rounded corners and the camera. Nothing is cropped.

## Outputs

- Pair: `comparison.mp4` (H.264, yuv420p, faststart, 2400 px wide), `comparison.gif` (1200 px wide) and `comparison.png` (the key frame).
- Feature: the same files named `single.*`, 1210 px wide.
- Each file is at most 10 MB. The MP4 steps its quality down, and the GIF steps its frame rate, width and palette down, until each one fits.
- A run where every input is a still writes only the PNG.
- The video is at most 60 seconds long. A shorter side holds its last frame.

## Style rules

Every recording follows these rules, so that all of them read as one set:

- Two rounded panels on a white canvas. The tint is red `#FDECEC` for Before, green `#E8F6EE` for After, and neutral `#EEF2F7` for a feature.
- Each panel has a pill label at the top, in bold white text: red `#E5484D` for Before, green `#1F9D55` for After, and navy `#1B2A4A` for a feature.
- Each panel has a one-line callout under its pill: an icon disc and the note. The icon is a red cross, a green check, or a navy dot, drawn as shapes, not as glyphs.
- Labels and callouts sit outside the phone. Nothing is drawn over the app content.
- In pair mode, a round navy VS badge sits on the seam between the panels, level with the middle of the phones. It is never drawn in feature mode.
- The screen fills the phone's cutout exactly, from the status bar to the gesture area. Nothing of the app is cropped.
- No timer. No overlays on app content.
- The font is DIN Alternate Bold, which ships with macOS. Arial is never used.

## Requirements

- `adb`, `ffmpeg`, `ffprobe`, ImageMagick 7 (`magick`), `awk`, and GitHub CLI 2.99 or later. Install with `brew install ffmpeg imagemagick`.
- Android Studio with its Pixel 7 device art. Set `PR_RECORDING_DEVICE_ART` to point at another copy of that art folder. Set `PR_RECORDING_FONT` to use another bold sans.
