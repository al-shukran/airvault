# CI scripts

Thin wrappers over the Gradle commands in `docs/testing/guideline.md`. Workflows call them; you can run the same commands locally,
after one `./gradlew resolveAll` (online) fills the Gradle module cache.

## Gate lanes — `run-partition.sh <lane>`

`.github/workflows/pr-fast.yml` runs the same lanes for pull requests, the merge queue and master, so what a pull request
verifies is what master builds after it lands. `pr-ok` is the single required check: every lane must succeed, and a skipped lane
fails it, except dependency review, which is skipped until `DEPENDENCY_GRAPH_ENABLED=true`.

| Lane | Runs | Job |
|---|---|---|
| `resolve` | `./gradlew resolveAll`, the only online step; retried when a repository is unreachable | `deps`, on a cache miss |
| `shard-1` … `shard-6` | the unit tests listed under the lane in `shards.txt`; `shard-6` also runs every unlisted unit; pure JVM modules run whole | `shard` (matrix) |
| `verify` | the Roborazzi pixel tests of `:app` and `:lumio` (listed, or unlisted and named `*ScreenshotTest`), with `verifyRoborazziDebug` | `roborazzi` |
| `fuzz` | the Jazzer seed replays, `app.lensly.fuzz.*`, with `-PfuzzLane` | `fuzz` |
| `apk` | the Lumio debug APK, `:lumio:assembleDebug` | `android` |
| `android-tests` | androidTest and benchmark compile of `:app` and `:lumio`; master and merge queue only (a step of `android`) | `android` |
| `lint-app`, `lint-shared`, `lint-lumio` | `lintReportDebug` of one module's main sources; the unit-test, androidTest and test-fixture analyses run in nightly `lintDebug`; a leg compiles its module and the modules it depends on | `lint` (matrix) |
| `manifest` | merged manifests (no INTERNET or ACCESS_NETWORK_STATE in `:app`, Lumio's allow-list), no OkHttp on `:app`'s release classpath | `manifest` |
| `ci` | `bash -n` on every script, `check-partitions.sh`, `check-test-network.sh` (no Gradle) | `ci` |

`secrets` (gitleaks) and `dependency_review` run beside them and need no Gradle.

Every Gradle call runs with `--offline`, except `resolve`. A missing artifact then fails by name. The fix is in the build's
`resolveAll` wiring (`build.gradle.kts`), not a rerun of the job.

## Test classes — `shards.txt`

One line per test class: `<lane> <gradle module> <fully qualified class>`. A pure JVM module is one line, with the class `*`.
The lines balance the lanes. A test class with no line still runs, so adding a test never needs an edit here:

- `shard-6` runs every unlisted unit, except the two cases below. Its log names each one (`run-partition: shard-6 runs the unlisted ...`).
- `verify` takes an unlisted `*ScreenshotTest` of `:app` or `:lumio`, so its golden is checked. `fuzz` takes an unlisted
  `app.lensly.fuzz` target, because the shard runs leave that package out (`shared/build.gradle.kts`).
- `scripts/ci/test-units.sh` lists every unit. `check-partitions.sh` (the `ci` lane) fails when a class is listed twice or is
  listed but no longer exists. Remove or rename such a line.
- Keep the six shards balanced. Each test lane uploads its JUnit XML as `junit-shard-N` (kept 3 days). Sum the `time` attribute of
  each class, and move classes between lanes until the shard totals are close.
- `verify` passes its `--tests` filter to the test task before `verifyRoborazziDebug`. The verify task depends on the whole
  `testDebugUnitTest` of its module, so without the filter every unit test would run twice. Roborazzi switches verify mode on from
  the requested tasks, so the filtered test task runs the pixel tests in verify mode.
- Ui tests with the `prLane` property are no longer a separate partition: the pixel tests live in `verify`, and the rest in the shards.

## Dependencies and the build cache

- `deps-v3-<os>-<hash>` restores the directories in `DEPS_CACHE_PATHS` (`pr-fast.yml`): `modules-2`, the wrapper distributions,
  and the generated directories the configuration cache names as inputs (`dependencies-accessors`, `transforms`, `kotlin-dsl`,
  `scripts`, `generated-gradle-jars`, `jars-9`). The hash covers the version catalog, `verification-metadata.xml`, the wrapper
  properties, every `*.gradle.kts`, `settings.gradle.kts` and `gradle.properties`. The `deps` job saves it on a miss only.
- `bc-<lane>-<scope>-<sha>` holds `~/.gradle/caches/build-cache-1` and `.gradle/configuration-cache`. The lint legs name their
  module in the lane (`bc-lint-app-<scope>-<sha>`). The scope is `master` on a push or a queue entry, and `pr<number>` in a pull
  request, which saves nothing. A push to master and a queue entry save every lane's entry, even when it fails or is cancelled.
  A pull request restores master's entries (`bc-<lane>-master-`) only. Its own entries would be restored ahead of master's, and a
  lint entry a pull request saved lacked the compile outputs master's entries hold, so the next run of that pull request compiled
  cold. Each lane's entry is 0.1 to 0.7 GB, so one push to master saves about 5.5 GB. `cache-prune` (`pr-fast.yml`, running
  `prune-caches.sh`, one at a time in `master-cache-prune`) runs after the lanes of each push. On master a cache's group is its key
  without the trailing hash, and each group keeps its newest entry; no entry newer than the prune's own run and no entry of master's
  current head (its commit, and the dependency key of its tree) is deleted. So neither two pushes close together, a late prune, nor
  a rerun of an older push can delete the newest caches, and the setup-gradle `gradle-*` caches of nightly and other workflows keep
  their newest entry. Merge-queue caches (`gh-readonly-queue/...`), which no other run can restore, are deleted. The listing pages
  through every cache. GitHub evicts least recently used entries over 10 GB per repository (run 44727796023).
- Lane budget: `pr-ok` runs `lane-budget.sh` after every lane. Each job's time (start to end, without a `Save ...` cache upload)
  goes to the step summary, and a job over 5 minutes gets a warning. On a push to master it opens, or comments on, the open
  `ci-budget` issue. It never fails the gate: fix a slow lane in a pull request, never rerun a run to make it green.
- The configuration cache is encrypted with `GRADLE_ENCRYPTION_KEY`, a repository secret. Without it, each runner encrypts with its
  own keystore, a restored entry reads as absent ("no cached configuration is available"), and no lane reuses the configuration.
  Empty means no key, which is how a fork's pull request runs.
- `lintAnalyze*` tasks are cacheable (root `build.gradle.kts`). AGP leaves them uncacheable by default.
- `setup-gradle` runs with `cache-disabled: true`: its own cache cleanup purged plugin marker POMs, which made later runs fail with
  "plugin not found".

## Manifest guard — `check-manifest.sh`

Builds the merged debug manifests of `:app` and `:lumio` (offline), then checks them. `:app` (Lensly) must declare no
`INTERNET` and no `ACCESS_NETWORK_STATE`. `:lumio` may request only the permissions on its allow-list. `android:allowBackup`
must be `"false"` in both. The `manifest` lane also checks the release manifest of `:app` for `INTERNET` and `ACCESS_NETWORK_STATE`, and the release runtime
classpath for OkHttp.

## Extended kinds — `run-extended.sh <kind>`

`instrumented`, `e2e`, `screenshot-testing`, `perf`, `security`, `fuzz`. Layers that have not landed yet print "not configured yet"
and exit 0 locally; set `CI=true` to make that case fail.

Where each kind runs (`comment-run.yml`): `screenshot-testing`, `security` and `fuzz` on `ubuntu-latest`; `instrumented` and `e2e`
on an emulator; `perf` on the self-hosted reference device (`[self-hosted, android-ref]`), because the 10% benchmark gate only
judges that phone.

```bash
scripts/ci/run-extended.sh security   # lintDebug + dependencyCheckAnalyze, then check-manifest.sh
CI=true scripts/ci/run-extended.sh e2e
```

`security` is the on-demand trigger for guideline 2.7's "Lint (security) and OWASP" row: `:app:lintDebug` and
`:app:dependencyCheckAnalyze` in one Gradle call with `--continue`, then `check-manifest.sh`. Expect 10 to 15 minutes, mostly the
OWASP scan. `NVD_API_KEY` is optional: without it the NVD API is rate-limited, so the scan is slower but still runs.

`fuzz` is real fuzzing, not a replay: it calls `fuzz-nightly.sh --seconds 60`, so each of the four targets fuzzes for 60 seconds.
`perf` runs the microbenchmarks on the reference device and applies the gate when `BENCHMARK_RESULT` names the result file.

## CodeQL — `/run-codeql`

`/run-codeql` as the whole text of a pull request comment runs a full CodeQL analysis of the pull request head with all four
languages (`actions`, `javascript-typescript`, `python`, `java-kotlin`), and reports as the check `codeql / on demand`. Expect 6 to
8 minutes; `java-kotlin` is the long leg because it compiles `:app` and `:tools:registry-pack` first.

It is a separate workflow (`codeql-command.yml` → `codeql.yml`), not a command in `comment-run.yml`: the comment must be exactly
`/run-codeql`, and the gate denies anyone without repository write access. The command only works once the workflow exists on the
default branch, because GitHub reads `issue_comment` workflows from the default branch and not from the pull request the comment
is on. It is not a required check and does not block a merge, and it never runs on `pull_request`.

`codeql.yml` also runs the same four languages on a daily schedule (04:23 UTC) and writes the result into one open issue labelled
`codeql`, rather than into the pull request path. No local wrapper exists for it: the CodeQL CLI is not installed locally, so
`run-extended.sh` has no `codeql` kind.

## Nightly fuzz — `fuzz-nightly.sh`

Fuzzes each Jazzer target for 15 minutes, one Gradle invocation per target (`JAZZER_FUZZ=1`, which Jazzer's JUnit integration
reads). `--list` prints the targets. Pass one target to fuzz only that one; the nightly workflow runs one matrix leg per target.
`--seconds N` caps each target at N seconds.

```bash
scripts/ci/fuzz-nightly.sh          # all four targets, about an hour
scripts/ci/fuzz-nightly.sh app.lensly.fuzz.CborFuzzTest   # one target, 15 minutes
scripts/ci/fuzz-nightly.sh --seconds 60                   # /run-fuzz: four minutes in total
scripts/ci/fuzz-nightly.sh --list
```

Duration comes from each `@FuzzTest` annotation: `maxDuration = "15m"`, which Jazzer passes to libFuzzer as `-max_total_time=900`.
`--seconds` passes `-PfuzzMaxSeconds=N`; `shared/build.gradle.kts` turns that into the `jazzer.internal.arg.0` JUnit configuration
parameter, and Jazzer appends it after the annotation value, so the shorter cap wins.

**PR lane.** The `fuzz` lane runs the same targets as ordinary JUnit tests with `-PfuzzLane`: Jazzer replays the committed seeds.
The fuzz package needs its own JVM, because Jazzer and Robolectric cannot share one (see the note in `shared/build.gradle.kts`).

**Seeds.** `shared/src/test/resources/fuzz-seeds/<target>/` holds valid and malformed inputs, named in that directory's `index.txt`;
the tests read them through `@MethodSource`. A crash a nightly run finds lands in
`shared/src/test/resources/app/lensly/fuzz/<Class>Inputs/<method>/`, which the regression run replays as soon as the file is committed.

## Nightly and comment runs — one report each

`nightly.yml` runs `full` (R8 and OWASP), `fuzz`, `instrumented`, `e2e` and `benchmark` as separate jobs. A single `report` job
(`needs` them all, `if: failure()`) opens or updates the `nightly-failure` issue once, listing which jobs failed; parallel jobs
cannot open four issues. In `comment-run.yml`, a read-only `gate` job authorizes the comment before any PR code is checked out, the
run jobs have `contents: read`, and one `report` job posts the result comment.

`instrumented` skips `app.lensly.benchmark`: those classes need `AndroidBenchmarkRunner`, which only the `benchmark` build type gets.
`perf` runs `:app:connectedBenchmarkAndroidTest` on the reference device; set `BENCHMARK_RESULT=<benchmarkData.json>` to make it
compare that run against the baseline.

## Macrobenchmarks — `:benchmark`

Cold start of `app.lensly.MainActivity` through `StartupTimingMetric` (guideline 1.7). Macrobenchmarks launch the installed
application and measure it from the outside, so they live in their own `com.android.test` module that targets `:app`'s `benchmark`
variant. Nothing in `:app` depends on it, so the PR lanes never configure or build it.

```bash
./gradlew :benchmark:connectedBenchmarkAndroidTest   # needs a device or emulator
./gradlew :benchmark:assembleBenchmark               # compiles the test APK, no device needed
```

Five iterations, `CompilationMode.None`, so every run measures the same bytecode. Changing the compilation mode, iterations or
startup mode invalidates a recorded baseline. Results are written under
`benchmark/build/outputs/androidTest-results/benchmark/<run>/benchmarkData.json`; the same `benchmark-gate.sh` applies, with no
baseline numbers invented for it.

Emulator numbers are trend data only. Only a physical reference device records a baseline, and it needs the application's
`benchmark` variant installed, which `connectedBenchmarkAndroidTest` does for you.

## Benchmark gate — `benchmark-gate.sh [--record] [--reference-device] <result.json>`

Compares an AndroidX Benchmark result against `app/src/androidTest/benchmark-baselines/baselines.json` and fails above
`BENCHMARK_MAX_REGRESSION_PCT` (default 10) or when a result has no baseline entry. The gate only judges the reference device:
without `--reference-device` (or `BENCHMARK_REFERENCE_DEVICE=1`) it reports that it made no verdict and exits 0, because an emulator
run is trend data. On the reference device a missing baseline fails closed. `--record` writes the baseline and refuses `CI=true`.
No baseline numbers are committed yet; see the README in the baseline directory for how to record them on the reference device.
