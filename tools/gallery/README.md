# Screen gallery

One page with a screenshot of every golden of `:app` (Lensly) and `:lumio` (Lumio). The owner reviews it after each session. Goldens are
grouped by app, then screen, then scenario. The "Not covered" section lists the goldens that a known screen expects but does not have.

## Regenerate

```sh
tools/gallery/build-gallery.sh --single
```

This writes two files under `out/gallery/` (ignored by git):

- `index.html` with its images in `img/`, for a local browser.
- `gallery-single.html` with every image embedded, for publishing as one file. It must stay under 15 MB. The current build is about 10 MB.

Options: `--out DIR` (default `out/gallery`), `--width 360` (thumbnail width in px). The script needs macOS (`sips`) and `python3`, and it
does not run Gradle. Record new goldens first when a screen changes:

```sh
./gradlew :app:recordRoborazziDebug :lumio:recordRoborazziDebug
```

## How the page is built

- `build-gallery.sh` checks for `sips`, then runs `build_gallery.py` with the standard library only.
- Each golden is downscaled to the thumbnail width and written as JPEG at quality 80.
- `screens.tsv` maps screens to golden names, and it is the only list the page needs to know about. A row is `module`, `screen id`,
  `title`, and the expected golden names separated by spaces. A name ending in `*` matches any golden with that prefix. The screen id is
  the golden prefix that the scenario label drops, so `bulk_generate_ready` is shown as `ready`.
- A golden goes under the most specific row that matches it. A golden no row matches is listed under "Not in the manifest".
- A row whose expected golden is missing from disk is listed under "Not covered". Add a row for each new screen, with the goldens it is
  expected to have.
- `NAME_light` and `NAME_dark` goldens, when both exist, are shown side by side as one card.
