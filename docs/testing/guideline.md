# Testing guideline

Status: implemented on `phase-1-5` (Phase 1.5). Describes the shipped state; see "Implementation notes" at the end for the places where the build differs from the YAML below.

Today on `master`: JUnit 4 and `kotlin.test` unit tests under `app/src/test` (`core/wallet`, `core/ur`, `core/vault`, `ui/*`). No `androidTest` sources, no Robolectric, no Compose UI tests, no `.github/workflows`. `org.gradle.caching=true` is set; configuration cache is not.

Packages: `core/wallet`, `core/ur`, `core/vault`, `ui/camera`, `ui/gate`, `ui/wallet`, `ui/components`, `ui/theme`, `ui/navigation`. `core/evm` and `core/registry` are planned; their CI partitions are reserved (2.2).

---

## 1. Test layers

Run commands assume the wrapper at the repo root.

### 1.1 Unit (JVM)

- **Purpose:** pure logic: derivation, encoding, parsing, policy, state machines.
- **Scope:** `core/*` and pure view-model code. No Android types.
- **Tools:** JUnit 4 (`junit` 4.13.2, in the catalog), `kotlin-test`.
- **Files:** `app/src/test/java/app/lensly/core/<pkg>/<Subject>Test.kt`; fixtures in `app/src/test/resources/`.
- **Run:** `./gradlew :app:testDebugUnitTest --tests 'app.lensly.core.wallet.*'`

### 1.2 Integration (Robolectric)

- **Purpose:** Android-dependent logic without a device: file writes, `Context`-bound code.
- **Scope:** `core/vault` persistence (`AtomicFile`, `FailureStore`), `ui/*` logic needing a `Context`.
- **Tools:** Robolectric 4.x (`RobolectricTestRunner`), AndroidX Test core. Pin the SDK to 36 in `robolectric.properties`.
- **Files:** same source set as unit tests.
- **Run:** `./gradlew :app:testDebugUnitTest --tests 'app.lensly.core.vault.*'`

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
- **Files:** `app/src/test/java/app/lensly/ui/<area>/<Screen>Test.kt`. Set `mainClock.autoAdvance = false`; no `Thread.sleep`.
- **Run:** `./gradlew :app:testDebugUnitTest --tests 'app.lensly.ui.*'`

### 1.5 Screenshot (Roborazzi)

- **Purpose:** catch unintended visual change per screen.
- **Scope:** each screen, dark theme only. `LenslyTheme` has no light scheme, so one golden per screen.
- **Tools:** Roborazzi (`roborazzi-compose`) on Robolectric.
- **Why Roborazzi, not Paparazzi:** Roborazzi captures from the same Robolectric `createComposeRule()` tests as 1.4, so one test source checks behavior and pixels. Paparazzi renders through layoutlib, not Robolectric, and cannot reuse those tests or their shadows.
- **Files:** goldens in `app/src/test/snapshots/`; move to Git LFS if they exceed about 2 MB.
- **Run:** `./gradlew :app:verifyRoborazziDebug`; update with `:app:recordRoborazziDebug` and review the PNG diff.

### 1.6 End-to-end

- **Automated (emulator, CI):** create wallet, export the `crypto-hdkey` UR, scan injected `eth-sign-request` frames, review, sign, show the `eth-signature` UR. Tools: Compose test plus UI Automator (`androidx.test.uiautomator:uiautomator`). Camera input comes from a `FrameSource` seam fed by fixture frames in `app/src/androidTest/assets/ur/`. The seam is a Phase 1.5 deliverable; `CameraController` binds CameraX directly today.
- **Run:** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lensly.e2e.WalletSignFlowTest`
- **Manual (release checklist, not CI):** on a physical phone, import Lensly's export into Rabby, then MetaMask, through the hardware-wallet (Keystone-style) flow. Use a public test mnemonic and a faucet-funded account on Monad testnet (chain ID 10143; confirm before the run). Sign a transaction through Lensly's QR round trip and confirm it broadcasts. Record date, app commit, and wallet version.

### 1.7 Performance

- **Microbenchmark** (`androidx.benchmark:benchmark-junit4`): Argon2 calibration targets about 1 s on the reference device (accept 0.8 to 1.2 s); the calibrated parameters are stored with the vault. QR decode rate of `UrScanner` over fixture frames, in frames per second.
- **Macrobenchmark** (`androidx.benchmark:benchmark-macro-junit4`): cold start via `StartupTimingMetric`. It lives in the separate `:benchmark` module (`com.android.test`, targeting `:app`'s `benchmark` variant), because a macrobenchmark launches the installed app from outside.
- **Rules:** run only on a `benchmark` build type. Gate on the reference device only, at more than 10% regression from its baseline. Emulator numbers are trend data.
- **Run:** `./gradlew :app:connectedBenchmarkAndroidTest` (microbenchmarks) and `./gradlew :benchmark:connectedBenchmarkAndroidTest` (macrobenchmarks, needs a device)

### 1.8 Security

- **Static:** Android Lint security checks (`./gradlew :app:lintDebug`, `SECURITY` issues as errors). Dependencies: OWASP `org.owasp.dependencycheck` nightly; `actions/dependency-review-action` on PRs touching dependency files. Secrets: `gitleaks` on changed files, allowlisting only `vectors/**`.
- **Manifest guard:** check the merged manifest, since libraries add permissions during merge. Assert no `android.permission.INTERNET`, no `ACCESS_NETWORK_STATE`, and `android:allowBackup="false"`. Script: `scripts/ci/check-manifest.sh` after `:app:processDebugMainManifest`.
- **Exact-match vectors:** BIP39 (mnemonic-to-seed), BIP32 (test vectors 1 and 2), EIP-155 and EIP-1559 signing examples, in `app/src/test/resources/vectors/<standard>/`. Each directory has a `README.md` with the source URL and retrieval date.
- **Fuzzing (Jazzer, `com.code-intelligence:jazzer-junit`):** `UrScanner`/`UrFrames`, `Cbor`, `EthSignRequest`, and the RLP transaction decoder once it lands. PR lane replays seeds as regular tests (JUnit regression mode). Nightly fuzzes each target for 15 minutes. Minimized crashes become inputs in `app/src/test/resources/fuzz-regressions/`.

### 1.9 Accessibility

- **Compose semantics:** interactive nodes have text or a `contentDescription`; assert with `onNodeWithContentDescription`, `assertHasClickAction()`, and a 48 dp minimum size.
- **Accessibility Test Framework:** Espresso `AccessibilityChecks.enable()` (`androidx.test.espresso:espresso-accessibility`) in instrumented tests. Phase 1.5 first verifies it reports Compose nodes; if not, rely on semantics assertions plus a manual TalkBack pass per release.
- **Contrast:** dark-only app. A pure JVM test computes WCAG 2.x ratios for each foreground/background pair in `ui/theme/Color.kt`: 4.5:1 for body text, 3:1 for large text and UI components.

### 1.10 Regression

Each fixed bug lands with a failing test in the layer where it was found. Nightly runs every layer across the whole module, which catches cross-package breakage that path filters miss.

---

## 2. CI design

### 2.1 Hard requirements

- **No test demand for untouched code.** A partition runs only when its own paths change. No repo-wide coverage gate; any JaCoCo report is informational and limited to changed files.
- **PR runs finish in 3 minutes wall clock.** Each partition job sets `timeout-minutes: 3`, covering checkout, JDK, and Gradle setup.
- **Over-budget work moves** to a nightly schedule and a comment command (2.5, 2.6).
- **Docs, text, and non-build config changes run zero test jobs.** The aggregate job in 2.4 keeps branch protection satisfied.

### 2.2 Partition map

| Partition | Triggers (paths) | Selector |
|---|---|---|
| `core-wallet` | `core/wallet/**`, its tests, `vectors/bip39/**`, `vectors/bip32/**` | `app.lensly.core.wallet.*` |
| `core-ur-evm` | `core/ur/**`, its tests, `keystone/**`, `vectors/eip155/**`, `vectors/eip1559/**`, `fuzz/**`, `fuzz-seeds/**` | `app.lensly.core.ur.*` (add `core.evm.*` when it lands) and `app.lensly.fuzz.*` |
| `core-vault` | `core/vault/**`, its tests | `app.lensly.core.vault.*` |
| `core-registry` | `core/registry/**` (reserved) | `app.lensly.core.registry.*` |
| `ui` | `ui/**`, its tests, `res/values/strings.xml` | `app.lensly.ui.*` |
| `manifest` | `app/src/**/AndroidManifest.xml`, `gradle/libs.versions.toml`, `app/build.gradle.kts` | `check-manifest.sh` |
| `ci` | `scripts/ci/**`, `.github/workflows/**`, `.gitleaks.toml` | `bash -n` on every script, then `check-test-network.sh` |

Build paths (`build.gradle.kts`, `gradle.properties`, `settings.gradle.kts`, `gradle/**`) trigger every unit partition. This is the one exception to "touched code only", because build changes can affect any package. Owner to confirm.

A `core` change does not run `ui`; `ui` depends on `core`, and the nightly run covers that gap.

### 2.3 Speed levers

- Enable `org.gradle.configuration-cache=true`. Build cache is already on.
- `gradle/actions/setup-gradle` caches the Gradle user home (saved on `master`, restored on PRs). Also cache `~/.m2/repository/org/robolectric`, keyed on the Robolectric version.
- One filtered Gradle task per job; the build cache absorbs unchanged compile tasks. Measure warm runs in Phase 1.5 and split or move any partition over 3 minutes.

### 2.4 PR fast lane

```yaml
# .github/workflows/pr-fast.yml
name: pr-fast
on:
  pull_request:
    branches: [master]
concurrency:
  group: pr-fast-${{ github.event.pull_request.number }}
  cancel-in-progress: true
permissions:
  contents: read
jobs:
  changes:
    runs-on: ubuntu-latest
    outputs:
      partitions: ${{ steps.filter.outputs.changes }}
    steps:
      - uses: actions/checkout@v4
      - id: filter
        uses: dorny/paths-filter@v3
        with:
          filters: |
            core-wallet:
              - 'app/src/main/java/app/lensly/core/wallet/**'
              - 'app/src/test/java/app/lensly/core/wallet/**'
              - 'app/src/test/resources/vectors/bip39/**'
              - 'app/src/test/resources/vectors/bip32/**'
              - 'build.gradle.kts'
              - 'app/build.gradle.kts'
              - 'gradle/**'
              - 'gradle.properties'
            # one entry per row of the 2.2 table
  test:
    needs: changes
    if: needs.changes.outputs.partitions != '[]'
    runs-on: ubuntu-latest
    timeout-minutes: 3
    strategy:
      fail-fast: false
      matrix:
        partition: ${{ fromJSON(needs.changes.outputs.partitions) }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - run: scripts/ci/run-partition.sh "${{ matrix.partition }}"
  pr-ok:                       # the single required check
    needs: [changes, test]
    if: always()
    runs-on: ubuntu-latest
    steps:
      - run: '! { [ "${{ needs.test.result }}" = failure ] || [ "${{ needs.test.result }}" = cancelled ]; }'
```

`scripts/ci/run-partition.sh` maps each name to its selector (2.2) and runs `check-manifest.sh` for `manifest`.

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
| Unit `core/*` | PR fast lane, partition paths | 1 to 2 min warm |
| Integration and UI (Robolectric) | PR fast lane, `ui` partition | up to 3 min warm; split or move if over |
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
- **Vectors:** `app/src/test/resources/vectors/<standard>/`, or `keystone/` for UR captures. Assert exact bytes or addresses, never "not null".
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

- **JDK:** CI uses temurin 21, not the `java-version: '17'` in the 2.4 and 2.5 YAML. The app still targets Java 17.
- **Partitions:** the PR lane also has `core-parse`, `app` and `registry-pack`, because tests already live in those packages. `core-ur-evm` also selects `app.lensly.core.evm.*`.
- **`ui` and pixels:** the `ui` partition passes `-PprLane`, which excludes `app.lensly.ui.screenshot.*` from the PR lane. Pixel tests run nightly via `verifyRoborazziDebug`.
- **Dependency graph:** GitHub Dependency graph is disabled for this repo, so `dependency_review` and the snapshot submission are advisory (`continue-on-error`) until a human enables it at https://github.com/heyaibi/lensly/settings/security_analysis.
- **OWASP:** `dependencyCheckAnalyze` runs nightly and reads an optional `NVD_API_KEY`. A cold run takes 10 to 15 min.
- **Macrobenchmark:** lives in the separate `:benchmark` module (`com.android.test`). Baselines must be recorded on the reference device.
- **Cache warm:** `scripts/ci/cache-warm.yml` warms the Gradle cache on pushes to `master` and `phase-1-5`. A cold branch previously hit the 3-minute job cap.
- **E2E:** the `FrameSource` seam plus `app.lensly.e2e.WalletSignFlowTest` are verified on an API 36 arm64 emulator. The `/run-*` comment commands only work once the workflow exists on the default branch.
- **Policy, `FixtureDecoder` in `src/main`:** `FixtureDecoder` and the public `frameSource` parameter ship in `src/main` so the E2E can feed frames through the real decoder. This is a deliberate exception to §3; moving it to `src/test` would change the app's camera API.
- **Policy, benchmark gate scope:** the 10% gate enforces only on the reference device (`--reference-device`, or `BENCHMARK_REFERENCE_DEVICE=1`). Elsewhere it prints "no verdict" and never passes silently.
- **Policy, required check:** `pr-ok` is the required check; `master` and `phase-1-5` are protected and require it, and admins may bypass.
- **Policy, `/run-fuzz` and `/run-perf`:** `/run-fuzz` runs each Jazzer target for 60 seconds; `/run-perf` runs on the self-hosted `android-ref` runner, not an emulator.
- **Policy, interactive borders:** interactive input borders use `LenslyOutlineInteractive` (3:1 contrast); `LenslyOutline` stays decorative.

The five policy entries above state agreed target policy for changes still in review. They are not claims that the code has landed yet.

