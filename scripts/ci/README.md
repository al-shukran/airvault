# CI scripts

Thin wrappers over the Gradle commands in `docs/testing/guideline.md`. Workflows call them; you can run the same commands locally,
after one `./gradlew resolveAll` (online) fills the Gradle module cache.

## Gate steps — `run-step.sh <step>`

`.github/workflows/ci.yml` is the gate for pull requests and master, so what a pull request verifies is what master builds after
it lands. It is one job, `pr-ok`, the single required check. Its steps run in this order and the job stops at the first failure,
so the cheapest checks that catch the most mistakes report first:

`pr-ok` is never skipped: a skipped required check counts as passing, so the job has no `if`, and `ci.yml` does not run on `closed`
(`check-workflow-guards.sh` enforces both). A gate still running when its pull request closes runs to its end, and a retarget to
master starts no run, so push a commit to start the gate.

1. DCO (`scripts/dco_check.py`, pull requests only).
2. `static`: no Gradle.
3. The secrets scan (gitleaks over the commits of the event) and dependency review (pull requests only, once
   `DEPENDENCY_GRAPH_ENABLED=true`).
4. The dependency cache, and `resolve` on a miss.
5. `manifest`, `compile`, `unit`, `fuzz`.

| Step | Runs | Where |
|---|---|---|
| `resolve` | `./gradlew resolveAll`, the only online step; retried when a repository is unreachable | `ci.yml` and nightly `build`, on a dependency-cache miss |
| `static` | `bash -n` on every script, `check-test-network.sh`, `check-mnemonics.sh`, `check-cleartext-sources.sh`, `check-workflow-guards.sh`, `selftest-guards.sh` | `ci.yml` |
| `manifest` | merged manifests (no INTERNET or ACCESS_NETWORK_STATE in `:app`, Lumio's allow-list), no OkHttp on `:app`'s release classpath | `ci.yml` |
| `compile` | the main and unit-test code of `:app`, `:lumio`, `:shared` and every pure JVM module with a `src/test` set | `ci.yml` |
| `unit` | every unit test with `--fail-fast` (it fails first when `app`, `lumio` or `shared` has no `src/test` folder with a `*Test.kt` file, which Gradle would pass as NO-SOURCE), except the pixel tests of `:app` and `:lumio` (`-PskipScreenshotTests` leaves out `*ScreenshotTest`) and the fuzz targets | `ci.yml`; nightly `build` on the schedule |
| `fuzz` | the Jazzer seed replays, `app.lensly.fuzz.*`, with `-PfuzzLane` in their own JVM | `ci.yml` |
| `lint` | `lintReportDebug` of the main sources of `:app`, `:lumio` and `:shared` | nightly `build` |
| `apk` | the Lumio debug APK, `:lumio:assembleDebug` | nightly `build` |
| `android-tests` | androidTest and benchmark compile of `:app` and `:lumio` | nightly `build` |

Every Gradle call runs with `--offline`, except `resolve`, and none runs with `--continue`. A missing artifact then fails by name.
The fix is in the build's `resolveAll` wiring (`build.gradle.kts`), not a rerun of the job.

New tests need no CI change: `unit` runs the whole test task of each module, and a new pure JVM module under `core`, `chains`,
`abi` or `tools` is found by its `src/test` directory.

## Load

One job per pull request push (plus the `CLA` status job, which takes seconds). A newer push to the same pull request, or closing
it, cancels the running gate. With 15 open pull requests that is at most 15 gate jobs, under the account's ceiling of 20
concurrent jobs. A master push adds the `ci` run, then nightly `build` followed by `screenshots`, CodeQL one language at a time,
and the dependency-graph submission: about four jobs at once.

Runs that never start a job:

- A branch push runs no workflow except on master. A pull request branch runs through `pull_request` only.
- A comment starts `comment-run`, `codeql-command` and `CLA`, and each skips its job unless the comment is its command (for `CLA`,
  the signing sentence on a pull request).
- `master-red` starts only for `ci` runs on master.
- A scheduled `nightly` or `codeql` run on a master that the last successful run already checked skips its checks (`changes`),
  except the OWASP scan in nightly `full`.

## Dependencies and the build cache

- `deps-v3-<os>-<hash>` restores the directories in `DEPS_CACHE_PATHS` (`ci.yml`): `modules-2`, the wrapper distributions,
  and the generated directories the configuration cache names as inputs (`dependencies-accessors`, `transforms`, `kotlin-dsl`,
  `scripts`, `generated-gradle-jars`, `jars-9`). The hash covers the version catalog, `verification-metadata.xml`, the wrapper
  properties, every `*.gradle.kts`, `settings.gradle.kts` and `gradle.properties`. A miss resolves online and saves the key at the
  end of a green job, so a pull request that changes the dependency files saves its own key, which `cache-cleanup.yml` deletes when
  the pull request closes.
- `bc-master` holds `~/.gradle/caches/build-cache-1`. Only a push to master saves it, even when a step fails. GitHub cache entries
  cannot be overwritten, so the push deletes the key and saves it again. Master pushes queue in one concurrency group, so no two of
  them replace the key at once. A pull request restores the key and saves nothing. A master push saves only the entries its run
  read or stored (`prune-build-cache.sh`: every entry gets an access time of 1970 after the restore, and the entries not read since
  are deleted before the save). Unpruned, an entry grew to 2.3 GB in two days (#1115).
- GitHub evicts least recently used entries over 10 GB per repository. After an eviction the next run compiles cold, which is
  slower but correct.
- Nightly saves no build cache: its setup-gradle steps run with `cache-disabled` or `cache-read-only`. Its `build` job restores the
  dependency cache under the key master saved (#770) and saves it only on a miss. `record-goldens` runs setup-gradle with
  `cache-disabled` and restores only the dependency cache.
- The configuration cache is never saved or restored. It records the JDK home path of the runner that stored it, and runner images
  install the same Temurin build under different directories (`21.0.13-9` and `21.0.13-9.0.LTS`), so a restored entry failed
  `:shared:compileDebugKotlin` with "JDK home directory does not exist". Each run configures from scratch.
- `lintAnalyze*` tasks are cacheable (root `build.gradle.kts`). AGP leaves them uncacheable by default.
- `setup-gradle` runs with `cache-disabled: true`: its own cache cleanup purged plugin marker POMs, which made later runs fail with
  "plugin not found".

## Manifest guard — `check-manifest.sh`

Builds the merged debug manifests of `:app` and `:lumio` (offline), then checks them. `:app` (Lensly) must declare no
`INTERNET` and no `ACCESS_NETWORK_STATE`. `:lumio` may request only the permissions on its allow-list. `android:allowBackup`
must be `"false"` in both. The `manifest` step also checks the release manifest of `:app` for `INTERNET` and `ACCESS_NETWORK_STATE`, and the release runtime
classpath for OkHttp. Cleartext HTTP is checked in the sources by `check-cleartext-sources.sh`, which parses every XML file under any
module's `src/` outside `src/debug/` (so quotes, spacing, case and `shared/` resources cannot hide `cleartextTrafficPermitted` or
`usesCleartextTraffic` set to true). It runs in the `static` step, with no Gradle.

## Guard self-test — `selftest-guards.sh`

Runs in the `static` step. It feeds `check-cleartext-sources.sh` and `check-workflow-guards.sh` broken copies (single quotes, upper case,
a shared resource, a bare `author_association`, a commented-out or late release refusal, an extra step in a sign job, a quoted `gr""adlew`, a command added to `sign-release.sh`, an `if` on `pr-ok`, a `closed` pull request type) and expects each to fail, and it runs
`close-refresh-pr.sh` against a stub `gh` whose first close fails and expects one comment. Add a case here with every change to a guard.

## Release classpath guard — `check-release-classpath.sh`

Runs in the nightly workflow after `:app:assembleRelease`. It fails when Netty or Vert.x (web3j 6 declares both) is on the release
runtime classpath of `:app`. `shared/build.gradle.kts` excludes them from web3j.

## Mnemonic guard — `check-mnemonics.sh`

Runs in the `static` step of `ci.yml`, with no Gradle and no build. It fails when a tracked text file holds 12 or more consecutive
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
`:app:dependencyCheckAnalyze` in one Gradle call, then `check-manifest.sh`; the first failure stops the run. Expect 10 to 15
minutes, mostly the OWASP scan. `NVD_API_KEY` is optional: without it the NVD API is rate-limited, so the scan is slower but still runs.

`fuzz` is real fuzzing, not a replay: it calls `fuzz-nightly.sh --seconds 60`, so each of the five targets fuzzes for 60 seconds.
`perf` runs the microbenchmarks on the reference device and applies the gate when `BENCHMARK_RESULT` names the result file.

## CodeQL — `/run-codeql`

`/run-codeql` as the whole text of a pull request comment runs a full CodeQL analysis of the pull request head with all four
languages (`actions`, `javascript-typescript`, `python`, `java-kotlin`), one at a time in that order on one runner, and reports as
the check `codeql / on demand`. The first language whose scan breaks stops the run. `java-kotlin` is the long one because it
compiles `:app` and `:tools:registry-pack` first.

It is a separate workflow (`codeql-command.yml` → `codeql.yml`), not a command in `comment-run.yml`: the comment must be exactly
`/run-codeql`, and the gate denies anyone without repository write access. The command only works once the workflow exists on the
default branch, because GitHub reads `issue_comment` workflows from the default branch and not from the pull request the comment
is on. It is not a required check and does not block a merge, and it never runs on `pull_request`.

`codeql.yml` also runs the same four languages on a daily schedule (04:23 UTC) and on every master push, and writes the result into one open issue labelled
`codeql`, rather than into the pull request path. No local wrapper exists for it: the CodeQL CLI is not installed locally, so
`run-extended.sh` has no `codeql` kind.

## Nightly fuzz — `fuzz-nightly.sh`

Fuzzes each Jazzer target for 15 minutes, one Gradle invocation per target (`JAZZER_FUZZ=1`, which Jazzer's JUnit integration
reads). `--list` prints the targets. Pass one target to fuzz only that one; the nightly `fuzz` job runs all five, one after another.
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

**Gate.** The `fuzz` step runs the same targets as ordinary JUnit tests with `-PfuzzLane`: Jazzer replays the committed seeds.
The fuzz package needs its own JVM, because Jazzer and Robolectric cannot share one (see the note in `shared/build.gradle.kts`).

**Seeds.** `shared/src/test/resources/fuzz-seeds/<target>/` holds valid and malformed inputs, named in that directory's `index.txt`;
the tests read them through `@MethodSource`. A crash a nightly run finds lands in
`shared/src/test/resources/app/lensly/fuzz/<Class>Inputs/<method>/`, which the regression run replays as soon as the file is committed.

## Nightly and comment runs — one report each

`nightly.yml` runs `build` first (lint, the Lumio APK and the androidTest compile; on the schedule also the unit tests), then
`screenshots` (records the goldens, and proposes changes in the `goldens-refresh` pull request). On the schedule `fuzz` (real
fuzzing) and `emulator` (the instrumented tests, which include the end-to-end sign flows, then the microbenchmarks, on one
emulator) also need `build`, and `full` (R8 and OWASP) runs on its own so a broken build never hides a CVE. A single `report` job
(`needs` them all, `if: failure()`) opens or comments on one issue per failing check, titled `Nightly check failing: <check>` and
labelled `area:ci`. In `comment-run.yml`, a read-only `gate` job authorizes the comment before any PR code is checked out, the
run jobs have `contents: read`, and one `report` job posts the result comment.

The instrumented run skips `app.lensly.benchmark`: those classes need `AndroidBenchmarkRunner`, which only the `benchmark` build type gets.
`perf` runs `:app:connectedBenchmarkAndroidTest` on the reference device; set `BENCHMARK_RESULT=<benchmarkData.json>` to make it
compare that run against the baseline.

## Macrobenchmarks — `:benchmark`

Cold start of `app.lensly.MainActivity` through `StartupTimingMetric` (guideline 1.7). Macrobenchmarks launch the installed
application and measure it from the outside, so they live in their own `com.android.test` module that targets `:app`'s `benchmark`
variant. Nothing in `:app` depends on it, so the gate never builds it.

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
