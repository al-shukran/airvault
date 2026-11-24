# Contributing

AirVault is the project name. Lensly is the offline vault and signer in `app/`. Lumio is the online companion in `lumio/`. Do not use the repository folder name as the project name.

## Contributor License Agreement

Sign the [Contributor License Agreement](CLA.md) on your first pull request. Comment exactly this sentence on the pull request:

> I have read the CLA Document and I hereby sign the CLA

The CLA workflow records your GitHub login, name, email, the date, and the commit you signed at in `signatures/cla.json` on the `cla-signatures` branch. You sign once. Later pull requests pass with no further action. The `CLA` check must be green before a pull request merges.

## Developer Certificate of Origin

Sign off every commit with `git commit -s`. This adds a `Signed-off-by: Your Name <you@example.com>` trailer to the commit message. The email in the trailer must match the commit author email. The DCO check reads the pull request's commits and fails if any of them lacks a matching trailer, so it must be green before a pull request merges.

The DCO and the CLA are separate requirements. The DCO says you have the right to submit the work under the project's licence. The CLA grants AirVault the copyright and patent licences it needs. Signing one does not sign the other.

## Changelog

Never edit `CHANGELOG-lensly.md` or `CHANGELOG-lumio.md` by hand on a normal pull request. Add one fragment per user-facing change under `changelog.d/lensly/` or `changelog.d/lumio/`, named `<slug>.<category>.md`, for example `add-user-authentication.feature.md`. The slug is lowercase words joined by hyphens, at least 8 characters, and it ends with `-issues-<numbers>` when the change has issues, for example `fix-session-expiration-issues-45-46.bugfix.md`. The name carries no pull request number: the number does not exist yet when the file is written, and the name must already be unique inside its app directory. One fragment holds one logical change.

The category is `feature`, `bugfix`, or `improvement`, which the release build renders as `Added`, `Fixed`, or `Changed` under [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). A security fix, a deprecation, or a removal still starts as a fragment with the closest category and a plain name; the release manager places it under the right heading when building the release. Empty sections are left out.

Which app owns a change:

- Lensly: `app/` and `chains/*/signer/`.
- Lumio: `lumio/` and `chains/*/wallet/`.
- Shared: `shared/`, `core/`, `abi/`, and `chains/*/common/`. If the change affects both apps, add one fragment in each app directory. If it affects one app, add only that app's fragment.

The specification says nothing about linking, so this is our choice: the release build appends `[Issue #N]` links from the `-issues-` marker in the filename, so a reader can trace the change to its discussion. A pull request link is not required on a new fragment, because the number does not exist when the file is written. The backfilled entries carry pull request links from their commits.

Check the fragments before you push:

```
python3 scripts/changelog_fragments.py --check --base origin/master
python3 scripts/changelog_fragments.py --self-test
```

The first command validates every name and paragraph and fails a pull request that changes product code with no fragment added. A production-code change with no user-visible effect carries exactly `No user-facing change.`, which the check accepts and the release build leaves out. CI runs the same check through `.github/workflows/changelog.yml`.

### Writing the entry

Write for a person deciding whether to upgrade. Lead with what they will notice, not with what moved between files. A refactor, a test, or a rename is not an entry unless it changes what a user sees.

An entry is as long as the change deserves. Most are one sentence. A change that breaks someone's setup gets more: what broke, what they must do, and whether there is a way to keep the old behaviour. A security fix gets a paragraph saying what an attacker could do.

That specification is explicit that a changelog mentioning only some changes is as bad as no changelog, because a reader will treat it as the whole truth. So write down the change even when it is easy to describe wrongly.

## Pull requests

Use a descriptive title that says what changed. Many titles end with the pull request number, for example `Lumio: harden CoinGecko token logos (#767)`. Prefixes such as `Fix:`, `CI:`, and `Docs:` are common but not required. The changelog is written by hand, not generated from titles. Commit every change with `git commit -s` so the DCO check passes.

Label pull requests and issues with one `scope:` label (`scope:lensly`, `scope:lumio`, `scope:common`), one `app:` label when a chain is involved, one `difficulty:` label, and one `area:` label. The full list is in `CLAUDE.md`.

## Screen recordings

Every UI change, feature, or bug fix pull request carries a short recording (15 to 60 seconds) that reviewers can watch inline, or a framed screenshot for a static screen. A fix or UI change gets a before/after pair; a new feature gets one demo. If before and after look the same, skip the video and say so in the pull request instead.

1. Capture on an emulator at 1080x2400, so the status bar and the navigation bar are real. Put the system UI in demo mode first, so the clock and the battery are clean:

   ```
   adb shell settings put global sysui_demo_allowed 1
   adb shell am broadcast -a com.android.systemui.demo -e command enter
   adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
   adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
   ```

   A still is `adb exec-out screencap -p > shot.png`. A clip is a loop of screencaps, about four a second, with the swipes or taps in between, then `ffmpeg -framerate 4 -i frame%02d.png -c:v libx264 -pix_fmt yuv420p clip.mp4`. On the emulator tested for this tool, `screenrecord` stopped after one frame while the screen was still moving, so use the screencap loop there. On a physical phone, `adb shell screenrecord --time-limit 30 /sdcard/clip.mp4`, then `adb pull`, works as well.
2. Compose the clip inside a phone frame with `tools/pr-recording/compose.sh`. A before/after pair becomes two devices side by side with Before and After labels; a demo becomes one device:

   ```
   tools/pr-recording/compose.sh --before before.mp4 --after after.mp4 --out pr-954
   tools/pr-recording/compose.sh --demo demo.mp4 --out pr-945
   ```

   This writes `<out>.mp4` (H.264, at most 60 s, black around the phones) and `<out>.gif` (transparent around the phones, stepped down when needed), both under 9.5 MB. Nothing is cropped. Each clip is scaled down, if it must be, and centred so that its corners lie inside the phone's visible screen, which is worked out from the device art; the space around it takes the colours of its own edges. An input that has no system bars, such as a golden screenshot from the test suite, goes in with `--app-only` directly before its file, for example `--before before.mp4 --app-only --after golden.mp4`. The real status and navigation bars from `tools/pr-recording/bars/` are placed around it, chosen by the brightness of its top and bottom edges. The bars were captured from the same emulator in demo mode (light and dark), so do not use `--app-only` on a capture that already has them. It needs `ffmpeg`, ImageMagick (`brew install ffmpeg imagemagick`), and the Pixel 8 device art from Android Studio. The art is read at run time and is not stored in the repository; `DEVICE_ART_DIR` overrides its path. A pair may hold clips of different lengths, and the shorter one freezes on its last frame.
3. Attach both files to the pull request description or a comment, each under 9.5 MB. Drag the GIF into the box first, so it renders inline, then drag the MP4 under it, so it plays inline. From a terminal with GitHub CLI 2.99.0 or later, `gh pr comment <number> --attach <out>.gif --attach <out>.mp4` uploads both; a video that is not alone in its paragraph shows as a link.
4. Never commit a video. `.gitignore` ignores `*.mp4`, `*.mov`, and `*.webm`, and the files belong on the pull request, not in the repository.

A static screen gets a framed screenshot instead of a video. `tools/pr-recording/compose.sh shot shot.png shot-framed.png` writes a 760 px wide PNG of the phone, transparent outside it. Use the screencap from step 1; add `--app-only` before the file for a golden. Attach it to the pull request, then embed the uploaded URL as `<img src="URL" width=380 />`. Do not embed screenshots with Markdown `![]()`.

## The gate

CI is the gate. `.github/workflows/` holds one file per workflow:

- `pr-fast.yml` runs the blocking checks (unit-test shards that also compile both apps' debug code, fuzz seed replays, CI guards, secrets scan, dependency review, manifest guard) on pull requests, the merge queue, and master, then aggregates them into `pr-ok`, the only required check. Each one finishes in about 3 minutes or less.
- `changelog.yml` warns on master pushes that change product code without a changelog fragment. It never blocks.
- `cla.yml` sets the `CLA` status on every pull request.
- `dco.yml` checks that every commit in a pull request has a matching `Signed-off-by` trailer.
- `nightly.yml` runs on master pushes and on a schedule: screenshot golden refresh, lint, and APK compile. The schedule adds the unit-test shards, the R8 build, CVE scan, real fuzzing, emulator, end-to-end, and benchmark jobs. Nothing in it blocks a pull request; a failing check opens or refreshes one issue for that check.
- `codeql.yml` scans four languages on a schedule and on master pushes, and `codeql-command.yml` runs it on `/run-codeql`. Neither runs on pull requests by itself.
- `comment-run.yml` runs host, emulator, and device jobs from trusted `/run-*` comments.
- `dependency-graph.yml` submits the Gradle dependency graph so dependency review can diff it.
- `release.yml` builds the APK for a `lensly-v*` or `lumio-v*` tag and publishes that app's changelog section with the release notes.
- `master-red.yml` opens the rolling `ci-red` issue when `pr-fast` fails on master. `cache-cleanup.yml` maintains stale caches, and `record-goldens.yml` records screenshot goldens on demand.

Nothing about a change is verified until CI says so. Before you push, run the checks that finish quickly:

```
python3 scripts/changelog_fragments.py --check --base origin/master
./gradlew :app:testDebugUnitTest :lumio:testDebugUnitTest
```

For a single CI partition, use `scripts/ci/run-partition.sh <partition>`. The partition names are in [docs/testing/README.md](docs/testing/README.md). Gradle needs a JDK and the Android SDK.

## Releasing

See [docs/releasing.md](docs/releasing.md). Each app has its own tag: `lensly-vX.Y.Z` for Lensly and `lumio-vX.Y.Z` for Lumio. `versionCode` is the commit count, so a full git history is required. A release must have a matching section in that app's changelog.

## Security and license

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md). AirVault has no token, coin, or presale. Never ask anyone for a seed phrase, recovery phrase, private key, or payment.

AirVault is licensed under Apache License 2.0. See [LICENSE](LICENSE). The CLA also grants the project and its users the copyright and patent licences needed to distribute contributions.
