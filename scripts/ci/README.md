# CI scripts

Thin wrappers over the Gradle commands in `docs/testing/guideline.md`. Workflows call them; you can run the same commands locally,
after one `./gradlew resolveAll` (online) fills the Gradle module cache.

## Gate lanes — `run-partition.sh <lane>`

`.github/workflows/pr-fast.yml` runs the blocking lanes for pull requests, the merge queue and master, so what a pull request
verifies is what master builds after it lands. The blocking set is `deps`, `shard`, `fuzz`, `ci`, `secrets`, `manifest` and `dependency_review`, and `pr-ok` is the single
required check over exactly those seven. A skipped or failed blocking lane fails it. The other lanes run in `nightly.yml`, on the
schedule and on pushes to master, and never block a pull request.

| Lane | Runs | Job |
|---|---|---|
| `resolve` | `./gradlew resolveAll`, the only online step; retried when a repository is unreachable | `deps`, on a cache miss |
| `shard-1` … `shard-10` | the unit tests listed under the lane in `shards.txt`; `shard-6` also runs every unlisted unit; pure JVM modules run whole; each lane also compiles the debug code of `:app` and `:lumio` | `shard` (matrix, pr-fast; nightly on the schedule) |
| `verify-app`, `verify-lumio` | the Roborazzi pixel tests of one module, `:app` or `:lumio` (listed, or unlisted and named `*ScreenshotTest`), with `verifyRoborazziDebug` | none in CI (local); nightly `screenshots` records the goldens instead |
| `fuzz` | the Jazzer seed replays, `app.lensly.fuzz.*`, with `-PfuzzLane` | `fuzz` (pr-fast) |
| `apk` | the Lumio debug APK, `:lumio:assembleDebug` | nightly `android` |
| `android-tests` | androidTest and benchmark compile of `:app` and `:lumio`; nightly only (a step of `android`) | nightly `android` |
| `lint-app`, `lint-shared`, `lint-lumio` | `lintReportDebug` of one module's main sources; the unit-test, androidTest and test-fixture analyses run in nightly `lintDebug`; a leg compiles its module and the modules it depends on | nightly `lint` (matrix) |
| `manifest` | merged manifests (no INTERNET or ACCESS_NETWORK_STATE in `:app`, Lumio's allow-list), no OkHttp on `:app`'s release classpath | `manifest` (pr-fast) |
| `ci` | `bash -n` on every script, `check-partitions.sh`, `check-test-network.sh` (no Gradle) | `ci` |

`secrets` (gitleaks) and `dependency_review` run beside them in `pr-fast.yml` and need no Gradle. `dependency_review` is skipped until `DEPENDENCY_GRAPH_ENABLED=true`.

Every Gradle call runs with `--offline`, except `resolve`. A missing artifact then fails by name. The fix is in the build's
`resolveAll` wiring (`build.gradle.kts`), not a rerun of the job.

## Test classes — `shards.txt`

One line per test class: `<lane> <gradle module> <fully qualified class>`. A pure JVM module is one line, with the class `*`.
The lines balance the lanes. A unit with no line goes to `shard-6`, so `check-partitions.sh` fails until the unit gets a line. The two cases below need none:

- `verify-app` and `verify-lumio` take an unlisted `*ScreenshotTest` of their module, so its golden is checked. A lane runs only its
  own module's pixel tests, and `run-partition.sh` fails when a line names another module. `fuzz` takes an unlisted
  `app.lensly.fuzz` target, because the shard runs leave that package out (`shared/build.gradle.kts`).
- `scripts/ci/test-units.sh` lists every unit. `check-partitions.sh` (the `ci` lane) fails when a class is listed twice, is
  listed but no longer exists, or is unlisted and not one of the two cases above. Remove or rename such a line, or add one.
- Keep the ten shards balanced. Each test lane uploads its JUnit XML as `junit-shard-N` (kept 3 days). Sum the `time` attribute of
  each class, and move classes between lanes until the shard totals are close.
- A verify lane passes its `--tests` filter to the test task before `verifyRoborazziDebug`. The verify task depends on the whole
  `testDebugUnitTest` of its module, so without the filter every unit test would run twice. Roborazzi switches verify mode on from
  the requested tasks, so the filtered test task runs the pixel tests in verify mode.
- Ui tests with the `prLane` property are no longer a separate partition: the pixel tests live in the verify lanes, and the rest in the shards.

## Dependencies and the build cache

- `deps-v3-<os>-<hash>` restores the directories in `DEPS_CACHE_PATHS` (`pr-fast.yml`): `modules-2`, the wrapper distributions,
  and the generated directories the configuration cache names as inputs (`dependencies-accessors`, `transforms`, `kotlin-dsl`,
  `scripts`, `generated-gradle-jars`, `jars-9`). The hash covers the version catalog, `verification-metadata.xml`, the wrapper
  properties, every `*.gradle.kts`, `settings.gradle.kts` and `gradle.properties`. The `deps` job saves it on a miss in any event, so a
  pull request that changes the dependency files saves its own key, which `cache-cleanup.yml` deletes when the pull request closes.
- `bc-<lane>-master` holds `~/.gradle/caches/build-cache-1` and `.gradle/configuration-cache`, one key per lane: `bc-shard-N-master`,
  `bc-verify-<module>-master`, `bc-fuzz-master`, `bc-android-master`, `bc-lint-<module>-master`. Only a push to master saves it, even
  when a lane fails or is cancelled. GitHub cache entries cannot be overwritten, so the push deletes the lane's key and saves it again.
  Master pushes queue in one `pr-fast-master` concurrency group, so no two of them replace a key at once. A pull request and a
  merge-queue entry restore the key and save nothing. A master push saves only the entries its run read or stored
  (`prune-build-cache.sh`: every entry gets an access time of 1970 after the restore, and the entries not read since are deleted
  before the save). Unpruned, each of the ten entries grew to 2.3 GB in two days (#1115); pruned, one is about 0.4 GB.
- The restore also names the prefix `bc-<lane>-master-`, which reads the per-commit keys of the scheme before this one until each fixed
  key exists. Drop that restore key once those entries have expired (GitHub deletes an entry unused for 7 days).
- Eviction: nothing in CI deletes a build cache except the push that replaces its own lane key. GitHub evicts least recently used
  entries over 10 GB per repository (run 44727796023). A lane whose entry was evicted compiles cold, and a cold lane can exceed the
  5-minute cap; `lane-budget.sh` reports it on master.
- Nightly saves no build cache: its setup-gradle steps run with `cache-disabled` or `cache-read-only`. Its `deps` job restores the
  dependency cache under the key master saved (#770) and saves it only on a miss. `record-goldens` runs setup-gradle with `cache-disabled` and restores only the dependency cache.
- Cold lanes: a lane with no entry compiles from scratch and is not capped in time. Measured cold on a 10-core Mac: shard-1 225 s,
  shard-6 177 s, verify 224 s (two pixel tests fail on macOS), fuzz 107 s, android 180 s, lint-app 159 s, lint-lumio 168 s. A CI
  runner is likely slower, so a cold pull request lane can exceed the 5-minute cap, and `lane-budget.sh` reports it on master.
  Cold lanes come from eviction.
- Lane budget: `pr-ok` runs `lane-budget.sh` after every lane. Each job's time (start to end, without a `Save ...` cache upload)
  goes to the step summary, a job over 4 minutes (240 s) gets a warning, and a job over 5 minutes gets a warning too. On a push
  to master it opens, or comments on, the open `ci-budget` issue for each job over 5 minutes. It never fails the gate: fix a
  slow lane in a pull request, never rerun a run to make it green.
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

## Release classpath guard — `check-release-classpath.sh`

Runs in the nightly workflow after `:app:assembleRelease`. It fails when Netty or Vert.x (web3j 6 declares both) is on the release
runtime classpath of `:app`. `shared/build.gradle.kts` excludes them from web3j.

## Mnemonic guard — `check-mnemonics.sh`

Runs in the `ci` job of `pr-fast.yml`, with no Gradle and no build. It fails when a tracked text file holds 12 or more consecutive
English BIP39 words, including words split across lines by a Kotlin string concatenation (`" +` and `"`). A run passes only when it
equals a line of `mnemonic-allowlist.txt`, which holds the standard published vectors of `shared/src/test/resources/vectors/bip39`.
The vendored wordlist `bip39-english.txt` is the official English list (SHA-256 `2f5eed53…dbda`) and is not scanned. The report names
the file and line only, never the words, because CI logs of this repository are public.

Tests use the published vectors, or the words of the test's own wallet. A new vector goes in the allowlist with its source, and
nothing else is allowed.

```bash
scripts/ci/check-mnemonics.sh   # prints the count of files checked, or each file:line hit and exits 1
```

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

`fuzz` is real fuzzing, not a replay: it calls `fuzz-nightly.sh --seconds 60`, so each of the five targets fuzzes for 60 seconds.
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
scripts/ci/fuzz-nightly.sh          # all five targets, about 75 minutes
scripts/ci/fuzz-nightly.sh app.lensly.fuzz.CborFuzzTest   # one target, 15 minutes
scripts/ci/fuzz-nightly.sh --seconds 60                   # /run-fuzz: five minutes in total
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

`nightly.yml` runs its checks as separate jobs: `screenshots` (records the goldens, and proposes changes in the `goldens-refresh`
pull request), `lint` and `android` (both on pushes to master too), and on the schedule only `shard`,
`full` (R8 and OWASP), `fuzz` (real fuzzing), `instrumented`, `e2e` and `benchmark`. A single `report` job (`needs` them all,
`if: failure()`) opens or comments on one issue per failing check, titled `Nightly check failing: <check>` and labelled `area:ci`.
Parallel jobs cannot open a dozen issues. In `comment-run.yml`, a read-only `gate` job authorizes the comment before any PR code is checked out, the
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
