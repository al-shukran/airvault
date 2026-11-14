# CI scripts

Thin wrappers over the Gradle commands in `docs/testing/guideline.md`. Workflows call them; you can run the same commands locally.

## PR partitions — `run-partition.sh <partition>`

| Partition | Selector |
|---|---|
| `core-wallet` | `:shared` `app.lensly.core.wallet.*` |
| `core-ur-evm` | `:shared` `app.lensly.core.ur.*` and `app.lensly.core.evm.*`, plus `app.lensly.fuzz.*` |
| `core-parse` | `:shared` `app.lensly.core.parse.*` and `app.lensly.core.qr.*` |
| `core-vault` | `:shared` `app.lensly.core.vault.*` |
| `core-registry` | `:shared` `app.lensly.core.registry.*` and `app.lensly.core.registry.update.*` |
| `core-btc` | `:shared` `app.lensly.core.btc.*` |
| `ui-a` | `:app` `app.lensly.ui.{settings,wallet,navigation,a11y,screenshot}.*`; `:shared` `app.lensly.ui.{camera,qr,theme}.*`; the pixel tests are excluded (see below) |
| `ui-b` | `:app` `app.lensly.ui.{flow,home,sign,profile,components}.*`; `:shared` `app.lensly.ui.{components,gate}.*` and `app.lensly.ui.SecureWindowTest` |
| `app` | `:app` `app.lensly.app.*`, `app.lensly.SmokeTest` and `app.lensly.core.vault.BackupExclusionTest` |
| `registry-pack` | `:tools:registry-pack:test` |
| `manifest` | `check-manifest.sh` |
| `ci` | `bash -n` on every script, then `check-ui-partitions.sh` and `check-test-network.sh` (no Gradle) |

`core-parse`, `app` and `registry-pack` are not in the guideline 2.2 table: tests already live in those packages, so leaving them out would run nothing for changes there.

`core-ur-evm` also replays the Jazzer fuzz targets (`app.lensly.fuzz.*`): they cover UR/CBOR, the eth-sign request and the unsigned (RLP) transaction decoder. The PR lane runs them as ordinary JUnit tests, so committed seeds are replayed on every change to that partition. They run as a second Gradle invocation with `-PfuzzLane`, in their own JVM: once a fuzz target has run, Jazzer's agent breaks Robolectric's sandbox class loader, so the shared unit-test JVM skips the fuzz package (see the note in `shared/build.gradle.kts`).

`ci` covers CI's own files (`scripts/ci/**`, `.github/workflows/**`, `.gitleaks.toml`). It needs no Gradle: it syntax-checks every script under `scripts/` and runs the test-network guard, so a workflow or script edit no longer merges without a check.

Each partition is one Gradle call per module, `./gradlew :shared:testDebugUnitTest --tests ...` (or `:app:testDebugUnitTest`, for the Lensly-only packages); repeated `--tests` filters are ORed. The `ui-a` and `ui-b` partitions run both modules in one call. Gradle's `*` matches across dots, so `registry.*` already reaches `registry.update`; the explicit second filter is belt-and-braces. Unknown names print usage and exit 2.

The `ui-a` and `ui-b` partitions split the `app.lensly.ui` tests so each stays under the 3-minute cap (guideline 2.1). Every ui subpackage in `app/src/test` or `shared/src/test`, and any test class directly in `app.lensly.ui`, must appear in exactly one of the two halves in `run-partition.sh`. `check-ui-partitions.sh` (run by the `ci` partition) fails when one is missing from both.

Both halves pass `-PprLane`, which makes `app/build.gradle.kts` exclude `app.lensly.ui.screenshot.*`. The Roborazzi pixel tests would push the partitions past the cap, so they are night-only: the nightly workflow runs `./gradlew ... verifyRoborazziDebug` against the committed goldens (guideline 2.7). Adding `-PprLane` to a local run reproduces the PR lane; leave it off to run everything.

```bash
scripts/ci/run-partition.sh core-wallet
./gradlew :shared:testDebugUnitTest --tests 'app.lensly.core.wallet.*'   # same thing
```

## Manifest guard — `check-manifest.sh`

Fails if the merged debug manifest declares `INTERNET` or `ACCESS_NETWORK_STATE`, or if `android:allowBackup` is not `"false"`. It always runs `:app:processDebugMainManifest` first, then reads the intermediate at `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`. The task always runs because reusing whatever is in `app/build` can check a manifest built from older inputs and pass a change it never saw.

## Extended kinds — `run-extended.sh <kind>`

`instrumented`, `e2e`, `screenshot-testing`, `perf`, `security`, `fuzz`. Layers that have not landed yet print "not configured yet" and exit 0 locally; set `CI=true` to make that case fail.

Where each kind runs (`comment-run.yml`): `screenshot-testing`, `security` and `fuzz` on `ubuntu-latest`; `instrumented` and `e2e` on an emulator; `perf` on the self-hosted reference device (`[self-hosted, android-ref]`), because the 10% benchmark gate only judges that phone.

```bash
scripts/ci/run-extended.sh security   # lintDebug + dependencyCheckAnalyze, then check-manifest.sh
CI=true scripts/ci/run-extended.sh e2e
```

`security` is the on-demand trigger for guideline 2.7's "Lint (security) and OWASP" row: `:app:lintDebug` and `:app:dependencyCheckAnalyze` in one Gradle call with `--continue`, then `check-manifest.sh`. Each step reports its own result and the run still exits non-zero if any of them failed, so one pass lists every broken gate. Expect 10 to 15 minutes, mostly the OWASP scan. `NVD_API_KEY` is optional: without it the NVD API is rate-limited, so the scan is slower but still runs.

`fuzz` is real fuzzing, not a replay: it calls `fuzz-nightly.sh --seconds 60`, so each of the four targets fuzzes for 60 seconds. `perf` runs the microbenchmarks on the reference device and applies the gate when `BENCHMARK_RESULT` names the result file.

## CodeQL — `/run-codeql`

`/run-codeql` as the whole text of a pull request comment runs a full CodeQL analysis of the pull request head with all four languages (`actions`, `javascript-typescript`, `python`, `java-kotlin`), and reports as the check `codeql / on demand`. Expect 6 to 8 minutes; `java-kotlin` is the long leg because it compiles `:app` and `:tools:registry-pack` first.

It is a separate workflow (`codeql-command.yml` → `codeql.yml`), not a command in `comment-run.yml`: the comment must be exactly `/run-codeql`, and the gate denies anyone without repository write access. The command only works once the workflow exists on the default branch, because GitHub reads `issue_comment` workflows from the default branch and not from the pull request the comment is on. It is not a required check and does not block a merge, and it never runs on `pull_request`.

`codeql.yml` also runs the same four languages on a daily schedule (04:23 UTC) and writes the result into one open issue labelled `codeql`, rather than into the pull request path. No local wrapper exists for it: the CodeQL CLI is not installed locally, so `run-extended.sh` has no `codeql` kind.

## Nightly fuzz — `fuzz-nightly.sh`

Fuzzes each Jazzer target for 15 minutes, one Gradle invocation per target (`JAZZER_FUZZ=1`, which Jazzer's JUnit integration reads). `--list` prints the targets. Pass one target to fuzz only that one; the nightly workflow runs one matrix leg per target. `--seconds N` caps each target at N seconds.

```bash
scripts/ci/fuzz-nightly.sh          # all four targets, about an hour
scripts/ci/fuzz-nightly.sh app.lensly.fuzz.CborFuzzTest   # one target, 15 minutes
scripts/ci/fuzz-nightly.sh --seconds 60                   # /run-fuzz: four minutes in total
scripts/ci/fuzz-nightly.sh --list
```

Duration comes from each `@FuzzTest` annotation: `maxDuration = "15m"`, which Jazzer passes to libFuzzer as `-max_total_time=900`. `--seconds` passes `-PfuzzMaxSeconds=N`; `shared/build.gradle.kts` turns that into the `jazzer.internal.arg.0` JUnit configuration parameter, and Jazzer appends it after the annotation value, so the shorter cap wins.

**PR lane.** The same targets run with no environment variable, as ordinary JUnit tests: Jazzer replays the committed seeds. `@FuzzTest` is a JUnit 5 `@ParameterizedTest`, so the unit-test JVM runs both engines — Jupiter for the fuzz targets, Vintage for every existing JUnit 4 test. Pass `-PfuzzLane` so the run includes `app.lensly.fuzz.*`: without it the shared JVM skips that package, because Jazzer and Robolectric cannot share a JVM.

**Seeds.** `shared/src/test/resources/fuzz-seeds/<target>/` holds valid and malformed inputs, named in that directory's `index.txt`; the tests read them through `@MethodSource`. A crash a nightly run finds lands in `shared/src/test/resources/app/lensly/fuzz/<Class>Inputs/<method>/`, which the regression run replays as soon as the file is committed.

`instrumented` skips `app.lensly.benchmark`: those classes need `AndroidBenchmarkRunner`, which only the `benchmark` build type gets. `perf` runs `:app:connectedBenchmarkAndroidTest` on the reference device; set `BENCHMARK_RESULT=<benchmarkData.json>` to make it compare that run against the baseline.

## Nightly and comment runs — one report each

`nightly.yml` runs `full`, `fuzz`, `instrumented`, `e2e` and `benchmark` as separate jobs. A single `report` job (`needs` them all, `if: failure()`) opens or updates the `nightly-failure` issue once, listing which jobs failed; parallel jobs cannot open four issues. In `comment-run.yml`, a read-only `gate` job authorizes the comment before any PR code is checked out, the run jobs have `contents: read`, and one `report` job posts the result comment.

## Macrobenchmarks — `:benchmark`

Cold start of `app.lensly.MainActivity` through `StartupTimingMetric` (guideline 1.7). Macrobenchmarks launch the
installed app and measure it from the outside, so they live in their own `com.android.test` module that targets
`:app`'s `benchmark` variant. Nothing in `:app` depends on it, so the PR lanes (`:app:testDebugUnitTest`,
`:app:assembleDebug`, `:app:verifyRoborazziDebug`) never configure or build it.

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest   # needs a device or emulator
./gradlew :benchmark:assembleBenchmark               # compiles the test APK, no device needed
```

Five iterations, `CompilationMode.None`, so every run measures the same bytecode. Changing the compilation mode,
iterations or startup mode invalidates a recorded baseline. Results are written under
`benchmark/build/outputs/androidTest-results/benchmark/<run>/benchmarkData.json`; the same
`benchmark-gate.sh` applies, with no baseline numbers invented for it:

```bash
scripts/ci/benchmark-gate.sh benchmark/build/outputs/androidTest-results/benchmark/<run>/benchmarkData.json
```

Emulator numbers are trend data only. Only a physical reference device records a baseline, and it needs the app's
`benchmark` variant installed, which `connectedBenchmarkAndroidTest` does for you.

## Benchmark gate — `benchmark-gate.sh [--record] [--reference-device] <result.json>`

Compares an AndroidX Benchmark result against `app/src/androidTest/benchmark-baselines/baselines.json` and fails above `BENCHMARK_MAX_REGRESSION_PCT` (default 10) or when a result has no baseline entry. The gate only judges the reference device: without `--reference-device` (or `BENCHMARK_REFERENCE_DEVICE=1`) it reports that it made no verdict and exits 0, because an emulator run is trend data. On the reference device a missing baseline fails closed. `--record` writes the baseline and refuses `CI=true`. No baseline numbers are committed yet; see the README in the baseline directory for how to record them on the reference device.
