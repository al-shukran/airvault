# Testing guideline

Status: implemented on `phase-1-5` (Phase 1.5). Describes the shipped state; see "Implementation notes" at the end for the places where the build differs from the YAML below.

Today on `master`: JUnit 4 and `kotlin.test` unit tests under `shared/src/test` (the code Lensly and Lumio share: `core/*`, `fuzz`, and the shared screens and components) and under `app/src/test` (the Lensly-only code). `androidTest` sources, Robolectric, Compose UI tests, and `.github/workflows` exist. `org.gradle.caching=true` is set; configuration cache is not.

Packages: `core/wallet`, `core/ur`, `core/vault`, `ui/camera`, `ui/gate`, `ui/wallet`, `ui/components`, `ui/theme`, `ui/navigation`. `core/evm` and `core/registry` are planned.

---

## 1. Test layers

Run commands assume the wrapper at the repo root.

### 1.1 Unit (JVM)

- **Purpose:** pure logic: derivation, encoding, parsing, policy, state machines.
- **Scope:** `core/*` and pure view-model code. No Android types.
- **Tools:** JUnit 4 (`junit` 4.13.2, in the catalog), `kotlin-test`.
- **Files:** `shared/src/test/java/app/lensly/core/<pkg>/<Subject>Test.kt`; fixtures in `shared/src/test/resources/`. Test helpers that both applications' tests use live in `shared/src/testFixtures/`.
- **Run:** `./gradlew :shared:testDebugUnitTest --tests 'app.lensly.core.wallet.*'`

### 1.2 Integration (Robolectric)

- **Purpose:** Android-dependent logic without a device: file writes, `Context`-bound code.
- **Scope:** `core/vault` persistence (`AtomicFile`, `FailureStore`), `ui/*` logic needing a `Context`.
- **Tools:** Robolectric 4.x (`RobolectricTestRunner`), AndroidX Test core. Pin the SDK to 36 in `robolectric.properties`.
- **Files:** same source set as unit tests.
- **Run:** `./gradlew :shared:testDebugUnitTest --tests 'app.lensly.core.vault.*'`

### 1.3 Instrumented (device or emulator)

- **Purpose:** what only real Android provides: Keystore key creation and wrapping, CameraX binding, real file permissions.
- **Scope:** Keystore key wrapping in `core/vault` once it exists; `ui/camera` binding.
- **Tools:** AndroidX Test runner 1.7.0 and `androidx.test.ext:junit` 1.3.0 (in the catalog), Espresso.
- **Files:** `app/src/androidTest/java/app/lensly/...`. Use camelCase method names; dex rejects spaces.
- **Run:** `./gradlew :app:connectedDebugAndroidTest` (API 31+).

### 1.4 UI (Compose UI test)

- **Purpose:** screen behavior: password entry, mnemonic confirm quiz, lockout and error states, navigation.
- **Scope:** `ui/gate`, `ui/wallet`, `ui/camera`, `ui/components`.
- **Tools:** `androidx.compose.ui:ui-test-junit4` (Compose BOM; add to the catalog) with `createComposeRule()`, run on Robolectric.
- **Files:** `app/src/test/java/app/lensly/ui/<area>/<Screen>Test.kt` for Lensly screens. The shared screens and components (`camera`, `components`, `gate`, `qr`, `theme`) are in `shared/src/test/java/app/lensly/ui/<area>/`. Set `mainClock.autoAdvance = false`; no `Thread.sleep`.
- **Run:** `./gradlew :app:testDebugUnitTest --tests 'app.lensly.ui.*' :shared:testDebugUnitTest --tests 'app.lensly.ui.*'`

### 1.5 Screenshot (Roborazzi)

- **Purpose:** catch unintended visual change per screen.
- **Scope:** each screen, dark theme only. `LenslyTheme` has no light scheme, so one golden per screen.
- **Tools:** Roborazzi (`roborazzi-compose`) on Robolectric.
- **Why Roborazzi, not Paparazzi:** Roborazzi captures from the same Robolectric `createComposeRule()` tests as 1.4, so one test source checks behavior and pixels. Paparazzi renders through layoutlib, not Robolectric, and cannot reuse those tests or their shadows.
- **Files:** goldens in `app/src/test/screenshots/`; move to Git LFS if they exceed about 2 MB.
- **Run:** `./gradlew :app:verifyRoborazziDebug` (and `:lumio:verifyRoborazziDebug`) locally, which compares against the committed PNGs with a 0.0005 pixel-change threshold (`ScreenshotHarness.kt`). CI does not verify pixels: the nightly `screenshots` job re-records them on each push to master and opens the `goldens-refresh` pull request for any change.
- **Record:** on Linux CI only. The nightly `screenshots` job records them on the same runner image; review its `goldens-refresh` pull request, which is the diff to commit. To record now, run the `record-goldens` workflow (`gh workflow run record-goldens`), download the `goldens-linux` artifact, review the PNG diff, and commit it. A macOS render differs from the Linux runner on anti-aliased edges, so a local macOS run is advisory, not the gate.

### 1.6 End-to-end

- **Automated (emulator, CI):** create wallet, export the `crypto-hdkey` pairing UR, scan injected `eth-sign-request` frames, review, sign, show the `eth-signature` UR. Tools: Compose test plus UI Automator (`androidx.test.uiautomator:uiautomator`). Camera input comes from a `FrameSource` seam fed by fixture frames in `app/src/androidTest/assets/ur/`. The seam is a Phase 1.5 deliverable; `CameraController` binds CameraX directly today.
- **Run:** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lensly.e2e.WalletSignFlowTest`
- **Manual (release checklist, not CI):** on a physical phone, import Lensly's export into Rabby, then MetaMask, through the hardware-wallet (Keystone-style) flow. Use a public test mnemonic and a faucet-funded account on Monad testnet (chain ID 10143; confirm before the run). Sign a transaction through Lensly's QR round trip and confirm it broadcasts. Record date, application commit, and wallet version.

### 1.7 Performance

- **Microbenchmark** (`androidx.benchmark:benchmark-junit4`): Argon2 calibration targets about 1 s on the reference device (accept 0.8 to 1.2 s); the calibrated parameters are stored with the vault. QR decode rate of `UrScanner` over fixture frames, in frames per second.
- **Macrobenchmark** (`androidx.benchmark:benchmark-macro-junit4`): cold start via `StartupTimingMetric`. It lives in the separate `:benchmark` module (`com.android.test`, targeting `:app`'s `benchmark` variant), because a macrobenchmark launches the installed application from outside.
- **Rules:** run only on a `benchmark` build type. Gate on the reference device only, at more than 10% regression from its baseline. Emulator numbers are trend data.
- **Run:** `./gradlew :app:connectedBenchmarkAndroidTest` (microbenchmarks) and `./gradlew :benchmark:connectedBenchmarkAndroidTest` (macrobenchmarks, needs a device)

### 1.8 Security

- **Static:** Android Lint security checks (`./gradlew :app:lintDebug`, `SECURITY` issues as errors). Dependencies: OWASP `org.owasp.dependencycheck` nightly; `actions/dependency-review-action` on PRs touching dependency files. Secrets: `gitleaks` on changed files, allowlisting only `vectors/**`.
- **Manifest guard:** check the merged manifest, since libraries add permissions during merge. Assert no `android.permission.INTERNET`, no `ACCESS_NETWORK_STATE`, and `android:allowBackup="false"`. Script: `scripts/ci/check-manifest.sh` after `:app:processDebugMainManifest`.
- **Exact-match vectors:** BIP39 (mnemonic-to-seed), BIP32 (test vectors 1 and 2), EIP-155 and EIP-1559 signing examples, in `shared/src/test/resources/vectors/<standard>/`. Each directory has a `README.md` with the source URL and retrieval date.
- **Fuzzing (Jazzer, `com.code-intelligence:jazzer-junit`):** `UrScanner`/`UrFrames`, `Cbor`, `EthSignRequest`, the RLP transaction decoder (`UnsignedTx`), and the Algorand request parser. The gate's `fuzz` step replays seeds as regular tests (JUnit regression mode), in their own JVM through `-PfuzzLane`: Jazzer's agent and Robolectric's sandbox cannot share one. Nightly fuzzes each target for 15 minutes. Minimized crashes become inputs in `shared/src/test/resources/fuzz-regressions/`.

### 1.9 Accessibility

- **Compose semantics:** interactive nodes have text or a `contentDescription`; assert with `onNodeWithContentDescription`, `assertHasClickAction()`, and a 48 dp minimum size.
- **Accessibility Test Framework:** Espresso `AccessibilityChecks.enable()` (`androidx.test.espresso:espresso-accessibility`) in instrumented tests. Phase 1.5 first verifies it reports Compose nodes; if not, rely on semantics assertions plus a manual TalkBack pass per release.
- **Contrast:** dark-only application. A pure JVM test computes WCAG 2.x ratios for each foreground/background pair in `ui/theme/Color.kt`: 4.5:1 for body text, 3:1 for large text and UI components.

### 1.10 Regression

Each fixed bug lands with a failing test in the layer where it was found. Nightly runs the layers the gate leaves out (2.5).

---

## 2. CI design

### 2.1 Hard requirements

- **Fail fast.** The gate runs the cheapest checks that catch the most failures first and stops at the first failure: DCO, the static guards, the secrets scan and dependency review, then the manifest guards, compile, the unit tests and the fuzz seed replays. No `continue-on-error` on a gating step, no Gradle `--continue`, and every test task runs with `--fail-fast`. Finishing fast matters less than failing fast; there is no time budget per job.
- **Predictable load.** The account runs at most 20 jobs at once, and 5 to 15 pull requests are usually open. Each pull request push starts one gate job (plus the seconds-long `CLA` status job), and a newer push to the same pull request cancels the running gate. Fifteen open pull requests therefore hold at most 15 runners. Heavy or optional work runs nightly, on master, or on a `/run-*` comment, never on every pull request push.
- **Every pull request runs the whole gate.** There are no path filters: a docs change runs the same job, so the one required check is always meaningful. No repo-wide coverage gate; any JaCoCo report is informational and limited to changed files.
- **Work that needs a device, the network or a long run goes** to nightly and to comment commands (2.5, 2.6).

### 2.2 What the gate runs

`.github/workflows/ci.yml` has one job, `pr-ok`, the single required check. It runs on every pull request to `master` and on every push to `master`. `scripts/ci/run-step.sh <step>` holds the commands of each Gradle step, so a step runs the same way locally.

| Order | Step | Checks |
|---|---|---|
| 1 | DCO | Every commit of the pull request has a matching `Signed-off-by` trailer (`scripts/dco_check.py`; pull requests only). |
| 2 | `static` | `bash -n` on every script, the test-network guard (3), the mnemonic, cleartext and workflow guards, and the guard self-test. No Gradle. |
| 3 | Secrets scan, dependency review | gitleaks over the commits of the event; `dependency-review-action` fails on a high-severity finding (pull requests only). |
| 4 | Dependencies | Restores the dependency cache; on a miss, `resolve` fetches every configuration online with retries. Every later step runs `--offline`. |
| 5 | `manifest` | `check-manifest.sh` (`:app` offline, `:lumio` allow-list), the release manifest of `:app`, no OkHttp on its release classpath. |
| 6 | `compile` | The main and unit-test code of `:app`, `:lumio`, `:shared` and every pure JVM module with tests. |
| 7 | `unit` | Every unit test, with `-PskipScreenshotTests` (the Roborazzi pixel tests of `:app` and `:lumio` are recorded nightly, 2.5). |
| 8 | `fuzz` | The Jazzer seed replays, in their own JVM through `-PfuzzLane`. |

New test classes and new pure JVM modules need no CI change: the unit step runs each module's whole test task and finds JVM modules by their `src/test` directory.

### 2.3 Speed levers

- `org.gradle.configuration-cache=true` and the build cache are on. CI never saves the configuration cache (it records the runner's JDK path).
- The dependency cache is keyed on the build files; master saves one pruned build cache (`bc-master`) that pull requests restore (`scripts/ci/README.md`).
- Gradle runs the modules' tasks in parallel inside the one job (`org.gradle.parallel=true`), so splitting the gate into more jobs would only add queueing.

### 2.4 Master and the ci-red alarm

The same `ci.yml` job runs on every push to `master`, so what a pull request verified is what master builds after it lands. On a master push it also saves the build cache and warns (never fails) when product code changed without a changelog fragment. `master-red.yml` opens or comments on the one open `ci-red` issue when that run fails on master, and closes it once master is green again.

### 2.5 Nightly, issue on failure

```yaml
# .github/workflows/nightly.yml
name: nightly
on:
  schedule:
    - cron: '17 3 * * *'       # 03:17 UTC daily
  workflow_dispatch:
permissions:
  contents: read
  issues: write
jobs:
  full:
    runs-on: ubuntu-latest
    timeout-minutes: 120
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew test lintDebug verifyRoborazziDebug dependencyCheckAnalyze
  # A single report job (needs: every job, if: failure()) opens or comments on the
  # nightly-failure issue once, listing the failed jobs. .github/workflows/nightly.yml
  # holds the gh commands; no job copies them.
```

Create the `nightly-failure` label once. The shipped file runs `build` (lint and the Android build) first and the goldens, fuzzing and one emulator job after it, so a master that does not build stops there; the R8 build and OWASP run on their own. Instead of a copy of the issue step in each job, one `report` job takes `needs` on every job, runs `if: failure()`, and opens or comments once with the run URL and the list of failed jobs, so parallel failures cannot open several issues.

### 2.6 Comment-triggered runs

```yaml
# .github/workflows/comment-run.yml
name: comment-run
on:
  issue_comment:
    types: [created]
permissions:
  contents: read
  pull-requests: read
jobs:
  run:
    if: github.event.issue.pull_request && startsWith(github.event.comment.body, '/run-')
    runs-on: ubuntu-latest
    timeout-minutes: 60
    steps:
      - id: gate
        env:
          GH_TOKEN: ${{ github.token }}
          BODY: ${{ github.event.comment.body }}
          PR: ${{ github.event.issue.number }}
          ACTOR: ${{ github.actor }}
        run: |
          perm=$(gh api "repos/$GITHUB_REPOSITORY/collaborators/$ACTOR/permission" --jq .permission)
          case "$perm" in admin|maintain|write) ;; *) echo "No write access"; exit 1;; esac
          kind=${BODY#/run-}; kind=${kind%%[[:space:]]*}
          echo "kind=$kind" >> "$GITHUB_OUTPUT"
          echo "sha=$(gh pr view "$PR" --json headRefOid -q .headRefOid)" >> "$GITHUB_OUTPUT"
      - uses: actions/checkout@v4
        with:
          ref: ${{ steps.gate.outputs.sha }}
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          script: scripts/ci/run-extended.sh ${{ steps.gate.outputs.kind }}
```

Commands: `/run-instrumented`, `/run-e2e`, `/run-screenshot-testing`, `/run-perf` (self-hosted reference device, label `android-ref`; the 10% benchmark gate needs that phone), `/run-security`, `/run-fuzz` (60 seconds of real fuzzing per target). The gate runs before PR code is checked out, so fork code never runs with secrets unless a collaborator with write access triggers it. The script posts the result and run link back to the PR.

### 2.7 Test type, trigger, expected duration

Durations are estimates, to be measured in Phase 1.5.

| Test type | Trigger | Expected wall clock |
|---|---|---|
| Unit, integration and UI (Robolectric), seed replays | gate (`ci.yml`), every pull request and master push | one job; no time budget |
| Manifest guard | gate | about 1 min |
| Secrets scan, dependency review | gate, the commits of the event | under 30 s |
| Screenshot (Roborazzi record) | nightly, on each master push and at 03:17 UTC; `/run-screenshot-testing` | 5 to 10 min |
| Lint (security) and OWASP | nightly; `/run-security` | 10 to 15 min |
| Instrumented (emulator) | nightly; `/run-instrumented` | 15 to 25 min |
| E2E flow (emulator) | nightly; `/run-e2e` | 20 to 30 min |
| Fuzz (5 targets, one after another) | nightly; `/run-fuzz` | about 75 min nightly, 5 min on `/run-fuzz` |
| Benchmarks | nightly trend (emulator); `/run-perf` gates on device | 20 to 30 min |
| Manual Rabby and MetaMask | release checklist | not CI |

---

## 3. Conventions

- **Names:** `<Subject>Test.kt` mirrors the main package. Methods are camelCase and state the expectation (`addressZeroMatchesStandardVector`), in `androidTest` too.
- **Vectors:** `shared/src/test/resources/vectors/<standard>/`, or `keystone/` for UR captures. Assert exact bytes or addresses, never "not null".
- **No network:** release has no INTERNET permission. Tests use local fixtures only. The gate fails if test code references `java.net.`, `HttpURLConnection`, or `URL(`.
- **Determinism:** inject `Clock` (`core/vault/Clock.kt`; `FakeClock` exists) and an entropy interface. Use seeded `kotlin.random.Random` in tests. No wall-clock time or `Thread.sleep`. For `SecureRandom` output, assert length and uniqueness only.
- **Secrets:** fixtures hold only public material: the BIP39 `abandon … about` mnemonic and published vectors. Fixture passwords start with `TEST_ONLY_`. Never a key that has held funds. `gitleaks` allowlists `vectors/**` only.
- **Test-only code** lives in `src/test` and `src/androidTest`, never in `src/main`.

---

## 4. Phase 1.5 implementation checklist

1. Enable `org.gradle.configuration-cache=true`; confirm the build passes.
2. Add `scripts/ci/run-step.sh`, `run-extended.sh`, `check-manifest.sh`.
3. Add `ci.yml` (2.2) with `pr-ok` as the required check. Create the `area:ci` label.
4. Add `gitleaks`, the manifest guard, and dependency review to the gate. Anything that needs a device or the network goes to nightly.
5. Add catalog entries: `ui-test-junit4`, Robolectric, Roborazzi, `espresso-accessibility`, UI Automator, Microbenchmark, Macrobenchmark, Jazzer. Pin versions at least two weeks old.
6. Add the WCAG contrast test for `ui/theme/Color.kt` and the standard vectors with exact-match tests.
7. Add Jazzer targets for `UrScanner`, `Cbor`, `EthSignRequest`; commit seeds; enable PR replay.
8. Introduce the `FrameSource` seam in `ui/camera`; write the emulator E2E flow.
9. Set up Roborazzi with dark-theme goldens per screen.
10. Add `nightly.yml` (2.5) and `comment-run.yml` (2.6). Verify issue creation on a throwaway failing branch, and the write-access gate with a non-collaborator account.
11. Add the `benchmark` build type; record reference-device baselines; set the 10% gate.
12. Run the Accessibility Test Framework spike on Compose screens and decide whether it gates. Add the manual device protocol to the release checklist.

---

## 5. Implementation notes

Where the shipped build differs from the text above:

- **JDK:** CI uses temurin `21.0.13+9` (pinned, not the floating `21`), not the `java-version: '17'` in the 2.4 and 2.5 YAML. The application still targets Java 17.
- **Pixels:** the gate's unit step passes `-PskipScreenshotTests`, which excludes every `*ScreenshotTest` of `:app` and `:lumio`. Pixel tests run nightly, where the `screenshots` job records the goldens.
- **Dependency graph:** enabled. The repository variable `DEPENDENCY_GRAPH_ENABLED=true` keeps both halves live: the submission job in `dependency-graph.yml` submits the Gradle graph and succeeds, and the Dependency review step of `ci.yml` diffs it on dependency changes. There is no `continue-on-error`, so a high-severity finding fails `pr-ok`. Reference: https://github.com/al-shukran/airvault/settings/security_analysis.
- **OWASP:** `dependencyCheckAnalyze` runs nightly and reads an optional `NVD_API_KEY`. A cold run takes 10 to 15 min.
- **Fuzz isolation:** Jazzer and Robolectric cannot share a JVM. Running a fuzz target first corrupts the Robolectric sandbox, and later Robolectric tests fail with `NoClassDefFoundError: JazzerInternal`. The fuzz tests therefore sit behind `-PfuzzLane`, which `run-step.sh` (the fuzz step) and `fuzz-nightly.sh` each pass in a separate Gradle invocation.
- **Benchmark run:** the benchmark variant needs `benchmarkImplementation` so `androidx.benchmark.IsolationActivity` merges into the application APK; without it every run fails with "Unable to resolve activity for androidx.benchmark.IsolationActivity". The run covers only `app.lensly.benchmark.*`. Baselines need a manual AOT compile on a reference device, because a Gradle run reinstalls and clears the compiled code; the procedure is in `app/src/androidTest/benchmark-baselines/README.md`.
- **Macrobenchmark:** lives in the separate `:benchmark` module (`com.android.test`). Baselines must be recorded on the reference device.
- **E2E:** the `FrameSource` seam plus `app.lensly.e2e.WalletSignFlowTest` are verified on an API 36 arm64 emulator. The `/run-*` comment commands only work once the workflow exists on the default branch.
- **Policy, `FixtureDecoder` in `src/main`:** `FixtureDecoder` and the public `frameSource` parameter ship in `src/main` so the E2E can feed frames through the real decoder. This is a deliberate exception to §3; moving it to `src/test` would change the application's camera API.
- **Policy, benchmark gate scope:** the 10% gate enforces only on the reference device (`--reference-device`, or `BENCHMARK_REFERENCE_DEVICE=1`). Elsewhere it prints "no verdict" and never passes silently.
- **Policy, required check:** `pr-ok` is the required check; `master` and `phase-1-5` are protected and require it, and admins may bypass.
- **Policy, `/run-fuzz` and `/run-perf`:** `/run-fuzz` runs each Jazzer target for 60 seconds; `/run-perf` runs on the self-hosted `android-ref` runner, not an emulator.
- **Policy, interactive borders:** interactive input borders use `LenslyOutlineInteractive` (3:1 contrast); `LenslyOutline` stays decorative.
