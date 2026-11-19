#!/usr/bin/env bash
# Run one PR-lane partition (docs/testing/guideline.md 2.2).
# Each partition is a single Gradle invocation with one filtered task per module, so the build cache
# absorbs unchanged compile work and the log names exactly one selector. The one exception is
# core-ur-evm: its fuzz replay runs as a second invocation, because Jazzer and Robolectric cannot
# share a JVM (see the -PfuzzLane note in shared/build.gradle.kts).
#
# Tests live in three modules. :shared (shared/src/test) holds the code Lensly and Lumio share. :app
# (app/src/test) holds the Lensly-only code, and :lumio (lumio/src/test) the companion's. A --tests filter that matches nothing in its module fails
# the task, so every selector runs in the module that owns its package.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Watchdog, kept in the PR on purpose. On 2026-11-19 a test (app) partition ran for 4 min 20 s after its configuration
# with no Gradle output and was cut by the job cap, with nothing to say where it stopped. If this partition is still
# running after 240 s, the job log gets the JVM thread dumps (every Gradle daemon, worker and wrapper JVM) and the open
# sockets. The watchdog only dumps: the job's own timeout still ends a stalled run, and nothing is retried.
WATCHDOG_SECONDS=240
watchdog() {
    sleep "$WATCHDOG_SECONDS"
    kill -0 "$1" 2>/dev/null || exit 0
    echo "::warning::run-partition: still running after ${WATCHDOG_SECONDS} s; dumping JVM stacks and open sockets"
    echo "::group::jps -l"
    jps -l 2>&1 || true
    echo "::endgroup::"
    jps -l 2>/dev/null | while read -r pid name; do
        case "$name" in
            *Jps*) continue ;;
        esac
        echo "::group::jstack $pid $name"
        jstack "$pid" 2>&1 | head -c 300000 || true
        echo "::endgroup::"
    done
    echo "::group::open sockets (ss -tnp)"
    ss -tnp 2>&1 | head -n 200 || true
    echo "::endgroup::"
}
watchdog "$$" &
WATCHDOG_PID=$!
trap 'kill "$WATCHDOG_PID" 2>/dev/null || true' EXIT

usage() {
    cat >&2 <<'USAGE_END'
usage: scripts/ci/run-partition.sh <partition>

partitions:
  core-wallet    :shared:testDebugUnitTest --tests 'app.lensly.core.wallet.*' --tests 'app.lensly.core.solana.*' --tests 'app.lensly.core.algorand.*'
  core-ur-evm    :shared:testDebugUnitTest --tests 'app.lensly.core.ur.*' --tests 'app.lensly.core.evm.*'
                 (then a second invocation, -PfuzzLane: app.lensly.fuzz.* covers UR/CBOR, eth-sign and RLP)
  core-parse     :shared:testDebugUnitTest --tests 'app.lensly.core.parse.*'
  core-vault     :shared:testDebugUnitTest --tests 'app.lensly.core.vault.*'
  core-registry  :shared:testDebugUnitTest --tests 'app.lensly.core.registry.*' --tests 'app.lensly.core.registry.update.*'
  core-btc       :shared:testDebugUnitTest --tests 'app.lensly.core.btc.*'
  ui-a           :app:testDebugUnitTest -PprLane --tests 'app.lensly.ui.settings.*' --tests 'app.lensly.ui.wallet.*'
                 --tests 'app.lensly.ui.navigation.*' --tests 'app.lensly.ui.a11y.*' --tests 'app.lensly.ui.screenshot.*'
                 :shared:testDebugUnitTest -PprLane --tests 'app.lensly.ui.camera.*' --tests 'app.lensly.ui.qr.*'
                 --tests 'app.lensly.ui.theme.*' --tests 'app.lensly.ui.navigation.*' --tests 'app.lensly.ui.settings.*'
  ui-b           :app:testDebugUnitTest -PprLane --tests 'app.lensly.ui.flow.*' --tests 'app.lensly.ui.profile.*'
                 --tests 'app.lensly.ui.components.*'
                 :shared:testDebugUnitTest -PprLane --tests 'app.lensly.ui.components.*' --tests 'app.lensly.ui.gate.*'
                 --tests 'app.lensly.ui.SecureWindowTest'
  ui-c           :app:testDebugUnitTest -PprLane --tests 'app.lensly.ui.home.*' --tests 'app.lensly.ui.sign.*'
  app            :app:testDebugUnitTest --tests 'app.lensly.app.*' --tests 'app.lensly.SmokeTest'
                 --tests 'app.lensly.core.vault.BackupExclusionTest'
  lumio-a        :lumio:testDebugUnitTest --tests 'app.lumio.ui.*'
  lumio-apk      :lumio:assembleDebug
  lumio-b        :lumio:testDebugUnitTest --tests 'app.lumio.LumioBackdropTest' --tests 'app.lumio.EntryGestureSettingTest'
                 --tests 'app.lumio.CoverLockTest' --tests 'app.lumio.SendRouteTest' --tests 'app.lumio.chains.*'
                 --tests 'app.lumio.evm.*' --tests 'app.lumio.home.*' --tests 'app.lumio.network.*' --tests 'app.lumio.pair.*'
                 --tests 'app.lumio.send.*' --tests 'app.lumio.tokens.*' --tests 'app.lumio.wallets.*'
  lumio-c        :lumio:testDebugUnitTest --tests 'app.lumio.btc.*'
  lumio-d        :lumio:testDebugUnitTest --tests 'app.lumio.data.*'
  registry-pack  :tools:registry-pack:test
  chain-api      :core:model:test :chains:algorand:common:test :core:signer-api:test :core:conformance:test, then compiles the API and Algorand modules
  manifest       scripts/ci/check-manifest.sh
  ci             bash -n on every script, scripts/ci/check-partitions.sh, then
                 scripts/ci/check-test-network.sh (no Gradle)
USAGE_END
}

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi

partition="$1"

case "$partition" in
    core-wallet)
        # Gradle ORs repeated --tests filters. algorand has no tests on master yet; solana and wallet keep the task non-empty.
        ./gradlew :shared:testDebugUnitTest --tests 'app.lensly.core.wallet.*' --tests 'app.lensly.core.solana.*' --tests 'app.lensly.core.algorand.*'
        ;;
    core-ur-evm)
        # Gradle ORs repeated --tests filters. evm landed after the 2.2 table was written.
        # The fuzz targets replay their committed seeds in their own JVM (Jazzer and Robolectric cannot share
        # one, see the -PfuzzLane note in shared/build.gradle.kts). They cover UrScanner/UR, CBOR, the eth-sign
        # request and the unsigned (RLP) transaction decoder, so they belong to this partition.
        # app.lensly.app.export is the AirVault pairing, a UR and CBOR payload that Lumio scans, so it runs with the UR tests.
        ./gradlew :shared:testDebugUnitTest \
            --tests 'app.lensly.core.ur.*' \
            --tests 'app.lensly.core.evm.*' \
            --tests 'app.lensly.app.export.*'
        ./gradlew :shared:testDebugUnitTest --tests 'app.lensly.fuzz.*' -PfuzzLane
        ;;
    core-parse)
        # Parsing untrusted payloads (DecodeBudget), and the QR Decode Sandbox that takes untrusted camera frames.
        # Paired with core-ur-evm in the workflow.
        ./gradlew :shared:testDebugUnitTest \
            --tests 'app.lensly.core.parse.*' \
            --tests 'app.lensly.core.qr.*'
        ;;
    core-vault)
        ./gradlew :shared:testDebugUnitTest --tests 'app.lensly.core.vault.*'
        ;;
    core-registry)
        # The second filter is explicit so registry.update stays covered even if a Gradle
        # version ever stops letting '*' cross package separators.
        ./gradlew :shared:testDebugUnitTest \
            --tests 'app.lensly.core.registry.*' \
            --tests 'app.lensly.core.registry.update.*'
        ;;
    core-btc)
        ./gradlew :shared:testDebugUnitTest --tests 'app.lensly.core.btc.*'
        ;;
    # The app.lensly.ui tests are split in three so each stays under the 3-minute cap (guideline 2.1).
    # Each test class must be in exactly one arm, under the Gradle call for its own module (:app or :shared).
    # scripts/ci/check-partitions.sh (run by the ci partition) fails when a class is in none, or in two.
    # ui-a, ui-b and ui-c hold the Lensly screens (:app) and the code both apps reuse (:shared). ui-c holds
    # the home and sign screens, split out of ui-b.
    # -PprLane drops app.lensly.ui.screenshot.* (Roborazzi pixel tests) from every ui arm. They are
    # verified nightly by verifyRoborazziDebug instead (guideline 2.7).
    ui-a)
        # screenshot is listed so the coverage guard sees it. -PprLane excludes its pixel tests anyway.
        ./gradlew :app:testDebugUnitTest -PprLane \
            --tests 'app.lensly.ui.screenshot.*' \
            --tests 'app.lensly.ui.settings.*' \
            --tests 'app.lensly.ui.wallet.*' \
            --tests 'app.lensly.ui.navigation.*' \
            --tests 'app.lensly.ui.a11y.*' \
            :shared:testDebugUnitTest -PprLane \
            --tests 'app.lensly.ui.camera.*' \
            --tests 'app.lensly.ui.qr.*' \
            --tests 'app.lensly.ui.theme.*' \
            --tests 'app.lensly.ui.navigation.*' \
            --tests 'app.lensly.ui.settings.*'
        ;;
    ui-b)
        ./gradlew :app:testDebugUnitTest -PprLane \
            --tests 'app.lensly.ui.flow.*' \
            --tests 'app.lensly.ui.profile.*' \
            --tests 'app.lensly.ui.components.*' \
            :shared:testDebugUnitTest -PprLane \
            --tests 'app.lensly.ui.components.*' \
            --tests 'app.lensly.ui.gate.*' \
            --tests 'app.lensly.ui.SecureWindowTest'
        ;;
    ui-c)
        ./gradlew :app:testDebugUnitTest -PprLane \
            --tests 'app.lensly.ui.home.*' \
            --tests 'app.lensly.ui.sign.*'
        ;;
    app)
        # Wallet stores, settings, export and sign pipelines, plus the module smoke test. BackupExclusionTest
        # reads the app's own manifest and backup rules, so it runs here and not in :shared.
        ./gradlew :app:testDebugUnitTest \
            --tests 'app.lensly.app.*' \
            --tests 'app.lensly.SmokeTest' \
            --tests 'app.lensly.core.vault.BackupExclusionTest'
        ;;
    # The app.lumio tests and the debug APK are split into five partitions so each lumio test job stays under its
    # 5-minute cap (guideline 2.1). lumio-a holds the ui packages, which include the Roborazzi pixel tests.
    # lumio-apk assembles the debug APK alone: dexing takes about 85 s on a cold cache, and no test partition
    # dexes. lumio-c holds btc, whose BtcScanTest alone takes about 50 s. lumio-d holds data, the largest package,
    # and lumio-b holds the rest. A new lumio package, or a test class directly in app.lumio, must be added to ONE
    # of the four test arms (lumio-a, lumio-b, lumio-c, lumio-d); scripts/ci/check-partitions.sh checks it.
    # Lumio's cover, gate and theme come from :shared, which the shared partitions test.
    lumio-a)
        ./gradlew :lumio:testDebugUnitTest --tests 'app.lumio.ui.*'
        ;;
    lumio-apk)
        ./gradlew :lumio:assembleDebug
        ;;
    lumio-b)
        ./gradlew :lumio:testDebugUnitTest \
            --tests 'app.lumio.LumioBackdropTest' \
            --tests 'app.lumio.LumioBarIconsTest' \
            --tests 'app.lumio.EntryGestureSettingTest' \
            --tests 'app.lumio.CoverLockTest' \
            --tests 'app.lumio.SendRouteTest' \
            --tests 'app.lumio.chains.*' \
            --tests 'app.lumio.evm.*' \
            --tests 'app.lumio.home.*' \
            --tests 'app.lumio.network.*' \
            --tests 'app.lumio.pair.*' \
            --tests 'app.lumio.send.*' \
            --tests 'app.lumio.tokens.*' \
            --tests 'app.lumio.wallets.*'
        ;;
    lumio-c)
        ./gradlew :lumio:testDebugUnitTest --tests 'app.lumio.btc.*'
        ;;
    lumio-d)
        ./gradlew :lumio:testDebugUnitTest --tests 'app.lumio.data.*'
        ;;
    registry-pack)
        ./gradlew :tools:registry-pack:test
        ;;
    chain-api)
        # The chain-app contract, the registry, the key-scope enforcer, the conformance kit (which runs on the Algorand
        # signer and checks the signer side for network libraries) and the Algorand manifest. No --tests filter: each module's
        # whole test set runs.
        ./gradlew :core:model:test :chains:algorand:common:test :core:signer-api:test :core:conformance:test \
            :core:signer-api:classes :core:wallet-api:classes \
            :chains:algorand:signer:classes :chains:algorand:wallet:classes
        ;;
    manifest)
        "$ROOT/scripts/ci/check-manifest.sh"
        ;;
    ci)
        # CI's own files: a syntax check of every shell script, the partition coverage check, plus the test-network
        # guard. No Gradle, so this stays well inside the 3-minute PR budget. The ci filter in pr-fast.yml also
        # runs it on every change to a test source set, so a new test class is checked on its PR.
        scripts=0
        while IFS= read -r script; do
            bash -n "$script"
            scripts=$((scripts + 1))
        done < <(find "$ROOT/scripts" -type f -name '*.sh' | sort)
        echo "run-partition: bash -n passed for $scripts script(s)"
        "$ROOT/scripts/ci/check-partitions.sh"
        "$ROOT/scripts/ci/check-test-network.sh"
        ;;
    *)
        echo "run-partition: unknown partition: $partition" >&2
        usage
        exit 2
        ;;
esac
