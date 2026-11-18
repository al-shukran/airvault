# Testing guideline

Status: implemented on `phase-1-5` (Phase 1.5). Describes the shipped state; see "Implementation notes" at the end for the places where the build differs from the YAML below.

Today on `master`: JUnit 4 and `kotlin.test` unit tests under `shared/src/test` (the code Lensly and Lumio share: `core/*`, `fuzz`, and the shared screens and components) and under `app/src/test` (the Lensly-only code). `androidTest` sources, Robolectric, Compose UI tests, and `.github/workflows` exist. `org.gradle.caching=true` is set; configuration cache is not.

Packages: `core/wallet`, `core/ur`, `core/vault`, `ui/camera`, `ui/gate`, `ui/wallet`, `ui/components`, `ui/theme`, `ui/navigation`. `core/evm` and `core/registry` are planned; their CI partitions are reserved (2.2).

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
- **Run:** `./gradlew :app:verifyRoborazziDebug` (and `:lumio:verifyRoborazziDebug`) in the nightly, which compares against the committed PNGs with a 0.0005 pixel-change threshold (`ScreenshotHarness.kt`).
- **Record:** on Linux CI only. Run the `record-goldens` workflow (`gh workflow run record-goldens`) on the same runner image the nightly uses, download the `goldens-linux` artifact, review the PNG diff, and commit it. A macOS render differs from the Linux runner on anti-aliased edges, so a local macOS run is advisory, not the gate.

### 1.6 End-to-end

- **Automated (emulator, CI):** create wallet, export the `crypto-multi-accounts` UR, scan injected `eth-sign-request` frames, review, sign, show the `eth-signature` UR. Tools: Compose test plus UI Automator (`androidx.test.uiautomator:uiautomator`). Camera input comes from a `FrameSource` seam fed by fixture frames in `app/src/androidTest/assets/ur/`. The seam is a Phase 1.5 deliverable; `CameraController` binds CameraX directly today.
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
- **Fuzzing (Jazzer, `com.code-intelligence:jazzer-junit`):** `UrScanner`/`UrFrames`, `Cbor`, `EthSignRequest`, and the RLP transaction decoder once it lands. PR lane replays seeds as regular tests (JUnit regression mode), in their own JVM through `-PfuzzLane`: Jazzer's agent and Robolectric's sandbox cannot share one. Nightly fuzzes each target for 15 minutes. Minimized crashes become inputs in `shared/src/test/resources/fuzz-regressions/`.

### 1.9 Accessibility

- **Compose semantics:** interactive nodes have text or a `contentDescription`; assert with `onNodeWithContentDescription`, `assertHasClickAction()`, and a 48 dp minimum size.
- **Accessibility Test Framework:** Espresso `AccessibilityChecks.enable()` (`androidx.test.espresso:espresso-accessibility`) in instrumented tests. Phase 1.5 first verifies it reports Compose nodes; if not, rely on semantics assertions plus a manual TalkBack pass per release.
- **Contrast:** dark-only application. A pure JVM test computes WCAG 2.x ratios for each foreground/background pair in `ui/theme/Color.kt`: 4.5:1 for body text, 3:1 for large text and UI components.

### 1.10 Regression

Each fixed bug lands with a failing test in the layer where it was found. Nightly runs every layer across the whole module, which catches cross-package breakage that path filters miss.

---

## 2. CI design

### 2.1 Hard requirements

- **PR verification finishes in 3 minutes wall clock**, from the first PR job queued to `pr-ok` done. Every job in `pr-fast.yml` sets `timeout-minutes: 3`, which covers checkout, JDK and Gradle setup. The 2026-11-12 amendment that gave the `test` job 5 minutes is withdrawn: the shards in 2.2 bring each job under the cap.
- **Work that cannot fit moves.** Anything longer goes to the nightly schedule (2.5) or to the push to `master`, where `pr-fast.yml` runs every partition.
- **No test demand for untouched code.** A partition runs only when a module it can reach changed (2.2). Build logic and CI files are the exceptions: they can change any test, so they run every partition.
- **Every push to `master` verifies the merge commit** with the same partitions and the same 3-minute cap, so a broken merge shows on the merge commit.
- **Docs, text, and non-build config changes run zero test jobs.** The `pr-ok` aggregate keeps branch protection satisfied.

### 2.2 Partitions

Partitions are generated. No list names a test class, so a new test class cannot be left out of the PR lane by an author.

- **Shards.** `scripts/ci/shards.sh` reads every test class: each top-level class in a file under `<module>/src/test/java` that contains `@Test`, named from its package line and declaration (a file may declare several). A class joins the shard that the CRC-32 of its fully qualified name selects, so a new class needs no edit and an existing class never moves. The shard count per module (`shared` 4, `app` 2, `lumio` 4) is the only setting. Raise it when a shard's job nears the 2:15 target in 2.3.
- **Lanes kept out of the shards.** The fuzz package (`app.lensly.fuzz.*`) runs as the `fuzz` partition with `-PfuzzLane`, in its own JVM, because Jazzer and Robolectric cannot share one. The screenshot package (`app.lensly.ui.screenshot.*` in `:app`) is excluded by `-PprLane` and runs nightly (2.7).
- **Areas.** The `changes` job reads path filters (dorny/paths-filter), and `scripts/ci/plan-partitions.sh` turns them into the matrix:

| Changed paths | Partitions |
|---|---|
| `shared/**`, `core/**`, `chains/**` | every `shared-N`, every `app-N`, every `lumio-N`, `fuzz`, `lumio-apk`, `lint`, `ci` (`:app` and `:lumio` are built from `:shared`) |
| `app/**` | every `app-N`, `ci` |
| `lumio/**` | every `lumio-N`, `lumio-apk`, `lint`, `ci` |
| `tools/registry-pack/**` | `registry-pack`, `ci` |
| `AndroidManifest.xml`, `*.gradle.kts`, `gradle/libs.versions.toml`, `scripts/ci/check-manifest.sh` | `manifest` (added to the rows above) |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/**`, `scripts/ci/**`, `.github/workflows/pr-fast.yml`, `.github/workflows/cache-warm.yml`, `.gitleaks.toml` | every partition |
| Docs and other paths | none |
| Any push to `master` | every partition |

- **Lint.** `lint` is `:lumio:lintDebug` in a job of its own rather than a matrix entry, because setup-gradle keys its cache entry by job id. It runs for shared and lumio changes, since lumio is built from shared.
- **Guard.** `scripts/ci/check-partitions.sh` (the `ci` partition) fails on a `@Test` outside `src/test/java` and `src/androidTest` (instrumented tests run nightly), on a test file where no class declaration is recognised, and on a class declared twice. The shards read every test source set, so these are the only ways a test can escape them.
- **Known gap.** The tests of `:core:model` and `:chains:algorand:common` (`core/model/src/test`, `chains/algorand/common/src/test`) are not in any partition. They run nightly with `./gradlew test`, not on the PR lane.

### 2.3 Speed levers

- **Configuration cache.** `org.gradle.configuration-cache=true` is set in `gradle.properties`. Without a cache that survives to the runner, each job paid about 70 s of configuration. Each partition restores `.gradle/configuration-cache` (about 2 MB) from the latest master push, and every shard of a module shares one entry, because the `--tests` filters are not part of the cache key.
- **One writer per cache key.** setup-gradle keys its Gradle User Home entry by job id and matrix values, so thirteen matrix entries would write thirteen partial copies. `cache-warm.yml` writes the `test` entry on master (compile and dependency resolution). The `lint` job writes its `lint` entry on master pushes. Partitions on pull requests restore read-only.
- **Dexing only where it is needed.** The unit-test partitions never assemble the APK; `lumio-apk` does.
- **Lint.** `lumio/build.gradle.kts` sets `checkTestSources` and `checkDependencies` to `false`. Lint's unit-test analysis still runs (about 25 s in a warm run); see the measurements in the pull request.
- **Setup costs on CI.** Checkout takes about 5 s, the JDK about 1 s from the runner's tool cache, and the setup-gradle restore about 14 s.

### 2.4 PR fast lane

`pr-fast.yml` runs on pull requests to `master` and `phase-1-5`, and on pushes to `master`. Abridged; the file is the source of truth:

```yaml
jobs:
  changes:      # dorny/paths-filter areas, then scripts/ci/plan-partitions.sh; timeout-minutes: 3
  test:         # matrix over the partitions; read-only setup-gradle; configuration cache; timeout-minutes: 3
  lint:         # :lumio:lintDebug when shared or lumio changed; timeout-minutes: 3
  secrets:      # gitleaks over the PR's commits (or the pushed commits); timeout-minutes: 3
  dependency_review:  # only when dependency files changed and DEPENDENCY_GRAPH_ENABLED=true
  test_network: # scripts/ci/check-test-network.sh; timeout-minutes: 3
  pr-ok:        # the single required check; timeout-minutes: 3
```

`scripts/ci/run-partition.sh <partition>` runs one matrix entry, and the local equivalent of every entry is listed in `scripts/ci/README.md`.

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
      - run: ./gradlew test lintDebug verifyRoborazziDebug dependencyCheckAnalyze --continue
  # A single report job (needs: every job, if: failure()) opens or comments on the
  # nightly-failure issue once, listing the failed jobs. .github/workflows/nightly.yml
  # holds the gh commands; no job copies them.
```

Create the `nightly-failure` label once. Device and fuzz jobs are added to this file as separate jobs. Instead of a copy of the issue step in each job, one `report` job takes `needs` on every job, runs `if: failure()`, and opens or comments once with the run URL and the list of failed jobs, so parallel failures cannot open several issues.

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
| Unit (`shared`, `app`, `lumio`) | PR fast lane, shards (2.2) | under 2:15 per shard warm |
| Integration and UI (Robolectric) | PR fast lane, shards (2.2) | under 2:15 per shard warm |
| Manifest guard | PR fast lane, manifest and dependency paths | about 1 min |
| Secrets scan, dependency review | PR fast lane, changed files | under 30 s |
| Screenshot (Roborazzi verify) | nightly 03:17 UTC; `/run-screenshot-testing` | 5 to 10 min |
| Lint (security) and OWASP | nightly; `/run-security` | 10 to 15 min |
| Instrumented (emulator) | nightly; `/run-instrumented` | 15 to 25 min |
| E2E flow (emulator) | nightly; `/run-e2e` | 20 to 30 min |
| Fuzz (4 targets in parallel) | nightly; `/run-fuzz` | about 20 min |
| Benchmarks | nightly trend (emulator); `/run-perf` gates on device | 20 to 30 min |
| Manual Rabby and MetaMask | release checklist | not CI |

---

## 3. Conventions

- **Names:** `<Subject>Test.kt` mirrors the main package. Methods are camelCase and state the expectation (`addressZeroMatchesStandardVector`), in `androidTest` too.
- **Vectors:** `shared/src/test/resources/vectors/<standard>/`, or `keystone/` for UR captures. Assert exact bytes or addresses, never "not null".
- **No network:** release has no INTERNET permission. Tests use local fixtures only. The PR lane fails if test code references `java.net.`, `HttpURLConnection`, or `URL(`.
- **Determinism:** inject `Clock` (`core/vault/Clock.kt`; `FakeClock` exists) and an entropy interface. Use seeded `kotlin.random.Random` in tests. No wall-clock time or `Thread.sleep`. For `SecureRandom` output, assert length and uniqueness only.
- **Secrets:** fixtures hold only public material: the BIP39 `abandon … about` mnemonic and published vectors. Fixture passwords start with `TEST_ONLY_`. Never a key that has held funds. `gitleaks` allowlists `vectors/**` only.
- **Test-only code** lives in `src/test` and `src/androidTest`, never in `src/main`.

---

## 4. Phase 1.5 implementation checklist

1. Enable `org.gradle.configuration-cache=true`; confirm the build passes.
2. Add `scripts/ci/run-partition.sh`, `run-extended.sh`, `check-manifest.sh`.
3. Add `pr-fast.yml` (2.4) with a filter row for each partition in 2.2. Create the `nightly-failure` label.
4. Add the manifest guard, `gitleaks`, and dependency review to the PR lane. Measure warm PR runs; split or move anything over 3 minutes.
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
- **Partitions:** generated from the test sources by `scripts/ci/shards.sh`; no list names a test class (2.2). The fuzz and screenshot packages have their own lanes.
- **`ui` and pixels:** the `ui-a`, `ui-b` and `ui-c` partitions pass `-PprLane`, which excludes `app.lensly.ui.screenshot.*` from the PR lane. Pixel tests run nightly via `verifyRoborazziDebug`.
- **Dependency graph:** enabled. The repository variable `DEPENDENCY_GRAPH_ENABLED=true` keeps both halves live: the submission job in `dependency-graph.yml` submits the Gradle graph and succeeds, and `dependency_review` in `pr-fast.yml` diffs it on dependency changes. There is no `continue-on-error`, so a high-severity finding fails `pr-ok`. Reference: https://github.com/heyaibi/lensly/settings/security_analysis.
- **OWASP:** `dependencyCheckAnalyze` runs nightly and reads an optional `NVD_API_KEY`. A cold run takes 10 to 15 min.
- **Fuzz isolation:** Jazzer and Robolectric cannot share a JVM. Running a fuzz target first corrupts the Robolectric sandbox, and later Robolectric tests fail with `NoClassDefFoundError: JazzerInternal`. The fuzz tests therefore sit behind `-PfuzzLane`, which `run-partition.sh`, `fuzz-nightly.sh` and `nightly.yml` each pass in a separate Gradle invocation.
- **Benchmark lane:** the benchmark variant needs `benchmarkImplementation` so `androidx.benchmark.IsolationActivity` merges into the application APK; without it every run fails with "Unable to resolve activity for androidx.benchmark.IsolationActivity". The lane runs only `app.lensly.benchmark.*`. Baselines need a manual AOT compile on a reference device, because a Gradle run reinstalls and clears the compiled code; the procedure is in `app/src/androidTest/benchmark-baselines/README.md`.
- **Macrobenchmark:** lives in the separate `:benchmark` module (`com.android.test`). Baselines must be recorded on the reference device.
- **Cache warm:** `.github/workflows/cache-warm.yml` is the only writer of the `test` cache entry on pushes to `master` and `phase-1-5`; the PR partitions restore it read-only (2.3). A cold branch previously hit the 3-minute job cap.
- **E2E:** the `FrameSource` seam plus `app.lensly.e2e.WalletSignFlowTest` are verified on an API 36 arm64 emulator. The `/run-*` comment commands only work once the workflow exists on the default branch.
- **Policy, `FixtureDecoder` in `src/main`:** `FixtureDecoder` and the public `frameSource` parameter ship in `src/main` so the E2E can feed frames through the real decoder. This is a deliberate exception to §3; moving it to `src/test` would change the application's camera API.
- **Policy, benchmark gate scope:** the 10% gate enforces only on the reference device (`--reference-device`, or `BENCHMARK_REFERENCE_DEVICE=1`). Elsewhere it prints "no verdict" and never passes silently.
- **Policy, required check:** `pr-ok` is the required check; `master` and `phase-1-5` are protected and require it, and admins may bypass.
- **Policy, `/run-fuzz` and `/run-perf`:** `/run-fuzz` runs each Jazzer target for 60 seconds; `/run-perf` runs on the self-hosted `android-ref` runner, not an emulator.
- **Policy, interactive borders:** interactive input borders use `LenslyOutlineInteractive` (3:1 contrast); `LenslyOutline` stays decorative.
