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

Media is made only with the three scripts in [`tools/pr-recording/`](tools/pr-recording/README.md): `capture.sh` (emulator capture in demo mode), `compose.sh` (the one Before/After design), and `post.sh` (the only way media reaches a pull request). Never hand-frame, crop or hand-post media, and never use test goldens.

Never commit a video. `.gitignore` ignores `*.mp4`, `*.mov`, and `*.webm`, and the files belong on the pull request, not in the repository.

## The gate

CI is the gate. `.github/workflows/` holds one file per workflow:

- `ci.yml` is the gate on pull requests and master: one job, `pr-ok`, the only required check. Its steps run cheapest first and stop at the first failure: DCO (every commit has a matching `Signed-off-by` trailer), the static guards, the secrets scan, dependency review, the manifest guards, compile, the unit tests, and the fuzz seed replays. On a master push it also warns when product code changed without a changelog fragment; that warning never blocks.
- `cla.yml` sets the `CLA` status on every pull request.
- `nightly.yml` runs on master pushes and on a schedule: lint, the APK and androidTest compile, then the screenshot golden refresh. The schedule adds the unit tests, the R8 build, the CVE scan, real fuzzing, and one emulator job for the instrumented, end-to-end and benchmark tests. Nothing in it blocks a pull request; a failing check opens or refreshes one issue for that check.
- `codeql.yml` scans four languages, one at a time, on a schedule and on master pushes, and `codeql-command.yml` runs it on `/run-codeql`. Neither runs on pull requests by itself.
- `comment-run.yml` runs host, emulator, and device jobs from trusted `/run-*` comments.
- `dependency-graph.yml` submits the Gradle dependency graph so dependency review can diff it.
- `release.yml` builds the APK for a `lensly-v*` or `lumio-v*` tag and publishes that app's changelog section with the release notes.
- `master-red.yml` opens the rolling `ci-red` issue when `ci` fails on master. `cache-cleanup.yml` maintains stale caches, and `record-goldens.yml` records screenshot goldens on demand.

Nothing about a change is verified until CI says so. Before you push, run the checks that finish quickly:

```
python3 scripts/changelog_fragments.py --check --base origin/master
./gradlew :app:testDebugUnitTest :lumio:testDebugUnitTest
```

For a single gate step, use `scripts/ci/run-step.sh <step>`. The step names are in [docs/testing/README.md](docs/testing/README.md). Gradle needs a JDK and the Android SDK.

## Releasing

See [docs/releasing.md](docs/releasing.md). Each app has its own tag: `lensly-vX.Y.Z` for Lensly and `lumio-vX.Y.Z` for Lumio. `versionCode` is the commit count, so a full git history is required. A release must have a matching section in that app's changelog.

## Security and license

Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md). AirVault has no token, coin, or presale. Never ask anyone for a seed phrase, recovery phrase, private key, or payment.

AirVault is licensed under Apache License 2.0. See [LICENSE](LICENSE). The CLA also grants the project and its users the copyright and patent licences needed to distribute contributions.
