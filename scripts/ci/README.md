# CI scripts

Thin wrappers over the Gradle commands in `docs/testing/guideline.md`. Workflows call them; you can run the same commands locally.

## PR partitions — `run-partition.sh <partition>`

The partitions are generated; no list names a test class. `plan-partitions.sh` maps the changed areas to a matrix, and `run-partition.sh` runs one entry of it.

| Partition | What runs |
|---|---|
| `shared-1` … `shared-4` | `:shared:testDebugUnitTest -PprLane` with the `--tests` filters of that shard |
| `app-1`, `app-2` | `:app:testDebugUnitTest -PprLane` with the filters of that shard |
| `lumio-1` … `lumio-4` | `:lumio:testDebugUnitTest` with the filters of that shard |
| `fuzz` | `:shared:testDebugUnitTest -PfuzzLane --tests 'app.lensly.fuzz.*'`, in its own JVM |
| `lint` | `:lumio:lintDebug` (its own job in `pr-fast.yml`, not a matrix entry) |
| `lumio-apk` | `:lumio:assembleDebug` (no tests) |
| `registry-pack` | `:tools:registry-pack:test` |
| `manifest` | `check-manifest.sh` (the workflow adds the release-manifest and OkHttp checks) |
| `ci` | `bash -n` on every script, then `check-partitions.sh` (no Gradle) |

`shards.sh` reads every test class: each top-level class in a file under `<module>/src/test/java` that contains `@Test`, named from its package line and declaration, so a file may hold several classes. A class joins the shard its fully qualified name selects by CRC-32, so a new test class needs no edit and an existing class never moves between shards. The shard count per module is the only setting (`shard_count`). The fuzz package and the `app` screenshot package are left out of the shards: they run in their own lanes.

`plan-partitions.sh` reads the path filters from the `changes` job and prints the matrix. `shared/`, `core/` and `chains/` count as `:shared`, and `:app` and `:lumio` are built from `:shared`, so a shared change runs every shard. Build logic (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/**`) and CI files (`scripts/ci/**`, `.github/workflows/pr-fast.yml`, `.github/workflows/cache-warm.yml`, `.gitleaks.toml`) run every partition. A push to `master` runs every partition too. Docs and other paths run none.

`check-partitions.sh` (the `ci` partition) fails on three things the shards cannot see: a `@Test` outside `src/test/java` and `src/androidTest` (instrumented tests run nightly), a test file where no class declaration is recognised, and a class declared twice.

Ui partitions pass `-PprLane`, which makes `app/build.gradle.kts` exclude `app.lensly.ui.screenshot.*`. The Roborazzi pixel tests are night-only: the nightly workflow runs `./gradlew ... verifyRoborazziDebug` against the committed goldens (guideline 2.7). Adding `-PprLane` to a local run reproduces the PR lane; leave it off to run everything.

```bash
scripts/ci/run-partition.sh shared-1
env EVENT=pull_request SHARED=true scripts/ci/plan-partitions.sh   # the matrix for a shared change
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
installed application and measure it from the outside, so they live in their own `com.android.test` module that targets
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

Emulator numbers are trend data only. Only a physical reference device records a baseline, and it needs the application's
`benchmark` variant installed, which `connectedBenchmarkAndroidTest` does for you.

## Benchmark gate — `benchmark-gate.sh [--record] [--reference-device] <result.json>`

Compares an AndroidX Benchmark result against `app/src/androidTest/benchmark-baselines/baselines.json` and fails above `BENCHMARK_MAX_REGRESSION_PCT` (default 10) or when a result has no baseline entry. The gate only judges the reference device: without `--reference-device` (or `BENCHMARK_REFERENCE_DEVICE=1`) it reports that it made no verdict and exits 0, because an emulator run is trend data. On the reference device a missing baseline fails closed. `--record` writes the baseline and refuses `CI=true`. No baseline numbers are committed yet; see the README in the baseline directory for how to record them on the reference device.
