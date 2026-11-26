# Design frames and the design gate

The approved frames of Lumio are the reference for its screens. A screen whose looks differ from its approved frame cannot merge, unless the screen is a listed known violation, and that list only shrinks. The goldens in `lumio/src/test/screenshots` are not the reference: they are the app's own past output, and the nightly job re-records them. The gate reads no golden.

## The rule for every UI change

1. The approved frame's per-element computed values (`design/frames/<id>.spec.json`, light and dark) and its picture (`design/frames/<id>-<theme>.png`) are the reference.
2. A pull request that touches a Lumio UI file must leave the `design gate` job green. The job runs on every pull request, never behind a path filter, so a screen that the change affects is checked whether or not the change names it. A pull request does not add a known violation: the only way to a new entry is the label `design:approved-change` (the coordinator's decision, tied to an issue). An entry is removed, never added, and what it still differs in only shrinks (the ratchet, below).
3. The job uploads the per-element expected-versus-actual table and the 4 times crops of every difference as the artifact `design-gate` (`report.md`, `<screen>.json`, `<screen>.png`, `crops/<screen>/`), and writes the summary to the job summary. Read them before you merge.
4. The guard `design guard` compares the whole of `design/registry.json` with the merge base and fails a pull request that changes what the gate measures against, unless it carries the label `design:approved-change`. Free: removing an entry from `known_violations`, `unregistered`, `no_frame` or a screen's `masks`, a known violation's baseline that only shrinks, and adding a screen row that has no masks and no key but `frame`, `theme`, `test` and `insetBottom`. Everything else needs the label: any file under `design/frames/`, `scripts/design/` or `lumio/src/test/java/app/lumio/ui/design/`, `LumioSnapshot.kt`, `tools/design-frames/render.py`, what runs the gate (`.github/workflows/pr-fast.yml`, `scripts/ci/run-partition.sh`, `scripts/ci/check-workflow-guards.sh`), a changed tolerance, a removed or renamed screen, a changed row, an entry added to a list, a new mask, a new top-level key. It reads the labels when it runs: add the label, then re-run the job. The job runs `guard.py` as it is at the base commit (`git show $BASE_SHA:scripts/design/guard.py`), not the pull request's copy, so a pull request cannot edit the check that judges it; only while the base has no guard does it run the checked-out file. `scripts/ci/check-workflow-guards.sh` asserts that `pr-ok` needs `design` and `design_guard`, that its aggregate checks them, that `design` has no condition that can skip it, and that the guard runs the base copy.

## Agents: the design-lock skill

`.design-lock.json` at the repo root (hidden by a global `.*` gitignore, so it was added with `git add -f`) adopts the design-lock skill on top of this gate; it adds no second gate. Its `gate` command is the `design` lane (`./scripts/ci/run-partition.sh design`), its `screens` mirror the rows of `design/registry.json` (frame spec, frame picture, and the per-screen result the lane writes to `lumio/build/design-gate/<screen>.json`), and `ui` lists what changes Lumio's rendering. CI is unchanged: `design gate` and `design guard` in `pr-ok` are the required check.

- With the skill's hooks installed, a session that edits a `ui` file runs the lane at its stop and cannot finish while it fails. The hook runs it with `ANDROID_HOME` set, and a cold run takes minutes. Run it yourself first when you touch a screen: the same command, then read `lumio/build/design-gate/report.md`.
- The hooks also deny an edit to `design/frames/`, `scripts/design/`, the gate's Kotlin (`lumio/src/test/java/app/lumio/ui/design/`, `LumioSnapshot.kt`) and `render.py`. Only the owner changes them, and CI still requires the label `design:approved-change`. A session the owner starts with `DESIGN_LOCK_CHANGE` may edit them.
- `design/registry.json` is not locked: the ratchet (below) lets an agent shrink `known_violations` and add a plain screen row without the label, and `design guard` judges every other change.
- Constants and variables mean here what the section below says (`"var"` in the spec); the skill's `flex` and `variable` fields are not used.
- `.design-lock.json` is itself locked, and the lane reads only the registry: a new registry row works at once, and the owner adds its row to `screens` when they next touch the config.

## The ratchet

A known violation records what it differs in today, so a regression on a screen that already fails cannot pass unseen. `known_violations[screen]` holds the `issue`, `findings` (the `layer:element` key of every difference of a frame element: `text:e12`, `box:e4`, `icon:e9`, `band:band2`), `regions` (the count of regions, which have no stable element) and `extra` (the count of texts the app shows and the frame lacks). The gate fails when a screen differs in a key that is not recorded, when `regions` or `extra` is higher than recorded, and when a recorded key now passes (remove it: the list shrinks with every fix, and the failure names the key). A screen with no differences left leaves `known_violations` as before. After a fix, `python3 scripts/design/check-results.py --record` (with the results of `./scripts/ci/run-partition.sh design` in `lumio/build/design-gate`) rewrites every baseline from the run; the guard lets a baseline that only shrinks through without the label (same `issue`, fewer keys, no higher count) and requires the label for one that grows. The baseline is seeded from the CI run (Linux), the reference renderer: a run on another machine may differ in a region or two.

## Constants and variables

The owner's rule: the **constants** of a frame must match, the **variables** may differ.

- Constants: layout (positions and sizes), containers (fills, borders, radii, shadows), fonts (size, weight, line height, letter spacing), colours, the UI's own icons, and every word that is copy.
- Variables: what the data decides, the **content** of a region and never the region itself. A token or chain logo (its image), an avatar, a monogram, a chart's line (a sparkline, the Asset page's price plot); the words of a data row (a name, a symbol, a price, a 24 h change: every text in a `.row` that holds a logo); and any text that is wholly data (an amount, a percentage, an address, a date, a counter).

`render.py` marks them in the spec: an element gets `"var": "logo" | "chart" | "row" | "data"`, and a variable text also `"anchor": "left" | "right" | "center"`, the edge that does not move when its words change (measured by shrinking the words to one letter). The classification is in `EXTRACT_JS` (`MARK`, `isData`): a rule that wrongly calls copy a variable is fixed there and the frames are re-rendered.

The gate checks a variable's **presence** and **place**, never its content. A variable text is paired with the app's Text on the frame's line over its place (not by its words); its font size, weight, line height, letter spacing and colour are compared as for any text; its ink must sit on the frame's line and at its anchor edge (the right edge of a price, the left edge of a name), not at the other edge, because the width is the data's. A variable logo must be there, about its size and centre; a chart must be there; what either draws is not compared. The container around the content is a constant and is compared as any box: the disc behind a logo, its border and ring, the fill and border behind a plot. The content's own pixels are left out of the region cells (a content region, not a mask), and the pixels around it are not. A badge or an action beside a logo (`.ta`, `.tg`, `.dim-badge`: "Change", "NEW", "DEFAULT") is copy, so it is a constant even inside a data row. A constant text is paired by its words, and compared on all four edges of its ink.

## What the gate measures

`./scripts/ci/run-partition.sh design` (the `design` lane of `pr-fast.yml`, part of `pr-ok`; a skipped job fails `pr-ok`):

1. Runs the test classes of `design/registry.json` with `-PdesignGate`. `lumioSnapshot` and `clearAirSnapshot` then skip every golden with no row, and render the registered ones at the size of their frame (the mock's 360 x 780 dp or 411 x 891 dp, less its 40 dp status bar, which the phone draws), at 1 px = 1 dp, with the gesture area under the content that a phone's navigation inset gives the frame (`insetBottom`, 24 dp; Home's tests add it themselves, so its rows say 0). They record and compare no golden.
2. `app.lumio.ui.design.DesignGate` compares each render with its frame, in five layers:
   - **Bands**: the horizontal runs of rows that hold ink. The count must match, and each band's top and bottom within `bandDp` (1 dp).
   - **Text**: every constant Text of the frame is matched by its words to a Text of the semantics tree, then every variable Text takes the Text on its line (see above). Font size, weight, line height (where lines stack), letter spacing and colour come from the Compose style and are compared with the frame's computed CSS. Where the words are drawn is measured on the pixels: the box of their ink, in the frame and in the screen, each edge within `textPosDp` (a variable: its line and its anchor edge). A Text the frame has and the screen lacks is `missing`; one the screen shows and the frame lacks is `absent from the frame`.
   - **Boxes**: every element of the frame with a fill, a border or a shadow is checked by the pixels around its edge, in the frame and in the screen: inside the fill, on the border, and outside it (an extra ring or a shadow). A pixel counts when it differs by more than `sample` of 255 from the closest of the nine pixels around the same place, so an edge half a pixel apart passes and a disc with another fill, a missing border or a ring fails.
   - **Icons and pictures**: each rectangle is compared by 4 dp cell averages (`cell`), and the colour the glyph is drawn in must match (`iconColor`). A variable picture is checked for presence, centre and size only.
   - **Regions**: every 4 dp cell whose average colour differs by more than `cell` is a difference no element explains. The pixels of the frame's words (and `textPosDp` around them), of its icons (and 1 dp around them) and, for a variable text, the room it may grow into up to the next word or icon of its line, are left out of the averages; nothing else is.
3. `scripts/design/check-results.py` settles the run: a registered screen that wrote no result fails (a skipped or filtered test cannot pass), a new violation fails, and a known violation that now matches fails (remove it from `known_violations`). It also checks that every golden is named once, in `screens`, `unregistered` or `no_frame`.

The failure reads, per element, `[layer] element: field expected X, actual Y`.

### What Chromium and Compose cannot agree on

**Renderer calibration (Archivo).** Robolectric's native graphics draws Archivo where Chromium does, for the same size, weight, width and line box: measured on the Clear Air styles (body, small, label, caption, row title, title L and XL, top bar, eyebrow) with the line box CSS sets (`LineHeightStyle(Alignment.Center, Trim.None)`), the ink bottom of "HHHH" is the same row in both and the ink top is 0 or 1 dp lower in Robolectric (anti-aliasing), never 2. So no offset is applied: the text tolerance of 2 dp covers the renderer, and a bigger difference is the screen's. `FontMetricsCalibrationTest` pins this against Chromium's measured values and fails if a Robolectric, Compose or font change moves Archivo by more than the tolerance, so a renderer artefact is never mistaken for the screen's (or the screen's for the renderer's). The ~2 dp that Archivo text seems to sit off is Compose's default line placement, not the renderer: without the CSS line box a single line is trimmed of its half-leading and the text sits 2 to 3 dp from where the frame has it, which is the screen's difference and is reported (the fix is the CSS line box in the screen). JetBrains Mono at a line height below its own height (the 28 and 50 sp numerals) sits 3 to 6 dp lower in Compose than in Chromium under the same line box; that is Compose's, the same on a phone, and is reported as the screen's difference.

Calibrated on the Home frames (#1340): text ink edges differ by up to 1 dp between Chromium and Robolectric (anti-aliasing and hinting), so the text tolerance is 2 dp and the band tolerance is 1 dp. The rise and fall arrows (U+25B2, U+25BC) come from a fallback font that differs between the machine that renders the frames and Robolectric, so the ink of the arrow is not compared: a Text that starts with one is compared by its line and its right edge, one that ends with one by its line and its left edge (its size, weight and colour are compared as always). A glyph drawn half a dp apart moves a 4 dp cell's average colour by about 100 of 255, so the pixels of a word or an icon are left to the word and icon checks. The comparison is not pixel-exact, and a tolerance is never raised to pass a screen.

`DesignGateCalibrationTest` pins what the gate must see, on drawn images: a disc with another fill, a missing disc, a missing border, an extra ring, a shadow, and a disc two dp away (the circle behind a logo in the dark Asset frame was the case that started it).

## The registry: `design/registry.json`

| Key | Holds |
| :- | :- |
| `tolerance` | The fixed tolerances. One set for every screen. |
| `screens` | The golden name (the first argument of `lumioSnapshot`) to `frame`, `theme`, `test` (the class that renders it), optional `insetBottom` and optional `masks`. |
| `known_violations` | Screens that differ today, each with the `issue` that tracks it. The list only shrinks to empty. |
| `unregistered` | Screens that have an obvious frame and are not gated yet, with the frame and the reason. |
| `no_frame` | The other goldens, with the reason they have no frame: a component sample, a state or variant of a screen. |

`scripts/design/check-results.py` also rejects a top-level key or a screen-row key the gate does not read, so an `ignore` or `skip` field cannot sit in the file unnoticed.

A mask names a rectangle (`rect`: x, y, width, height in dp) that is left out of every layer, with its `name`, a one-line `reason` and the issue or pull request `ref`. A mask is for a functional exception, such as live data, never for a difference in looks.

## Known limits

- The classification of variables is a rule on the mock's markup (`render.py`), not a hand-made list. Every text in a data row is a variable, except a badge or an action (`.ta`, `.tg`, `.dim-badge`) and a plain span of words with no figure or address in it (`SPL token`, `Network for this token`, `The only Bitcoin test network Lumio reads`), which are the row's copy and compared by their words. A subtitle that mixes copy with data (`Your account · 0x1020…122D`, `Layer 1 · 4 of your accounts`) cannot be told apart by markup, so it is a variable and its words are not compared; the PIN keypad's digits and the steps' numbers are classed as data. A sentence with an amount inside is a constant: the app must say the same amount.
- A variable's content is free by design: a Text whose words are wrong but whose place, font and colour are right passes.
- Arrow glyphs: a Text that starts with an arrow is not compared by its left edge, one that ends with an arrow not by its right edge, and one with an arrow inside by its line only.
- A pull request can edit the gate itself. The guard requires the label for the gate's code, frames, workflow and lane script, and runs from the base commit, so a pull request cannot edit the guard that judges it; but the label can be added by anyone who may label (below), and a change to the gate is still a change to review as such.
- The guard reads the labels when it runs: a label removed after a green run is not re-evaluated, and a merge-queue entry does not run the guard (#1452).
- A new test that calls `lumioSnapshot` without a recorded golden is not seen until the nightly records its golden; the refresh pull request then fails the lane (`golden ...: no row`) until the golden has a row.
- The label `design:approved-change` is read when the guard runs, and anyone who can label a pull request can add it. Branch protection decides who may.

## Adding a frame

1. Put the approved mock html where `tools/design-frames/render.py` can read it, and add it to `SOURCES` in the tool. Frames are the `.ph` phones: `p1` and `p2` are numbered in document order and named in `tools/mockup-capture/ids.tsv`; a mock with an id on each `.ph` (the Home mock) is named by it.
2. `python3 tools/design-frames/render.py --mocks <dir> [--only <id> ...]` (Python 3, Playwright, Chrome or Chromium). It renders each frame and theme at 1 px = 1 dp with the repo's Archivo and JetBrains Mono, hides the status bar and the gesture pill, and writes `design/frames/<id>-<theme>.png` (cropped below the status bar) and `design/frames/<id>.spec.json`: per theme the background, the ink bands, and every visible element with its rectangle, text, font (family, size, weight, stretch, line height, letter spacing), colour, fill, border, radius and shadow. Every y is the mock's y minus 40.
3. Commit the frames. The pull request needs the label `design:approved-change`: the frames are what the gate measures against.
4. Add the screen to `design/registry.json` under `screens`, with its frame, theme and test class. Run `./scripts/ci/run-partition.sh design`. The screen passes, or it goes to `known_violations` with a filed issue (label `design:approved-change`), and the issue lists what differs.
5. Fix the screen by making it match, never by loosening the tolerance, and delete its `known_violations` entry in the same pull request.

## Files

| Path | What |
| :- | :- |
| `tools/design-frames/render.py` | The renderer. Run by hand; CI never runs it. |
| `design/frames/` | The frames: PNGs and `<id>.spec.json`. |
| `design/registry.json` | Screens, tolerance, known violations. |
| `lumio/src/test/java/app/lumio/ui/design/DesignGate.kt` | The comparison. |
| `lumio/src/test/java/app/lumio/ui/screenshot/LumioSnapshot.kt` | The hook: the gate's render in place of the golden's. |
| `scripts/design/check-results.py`, `scripts/design/guard.py` | The settle step and the label guard; `scripts/design/test_design.py` tests them (the `ci` lane). |
