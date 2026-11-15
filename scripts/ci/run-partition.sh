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

usage() {
    cat >&2 <<'USAGE_END'
usage: scripts/ci/run-partition.sh <partition>

partitions:
  core-wallet    :shared:testDebugUnitTest --tests 'app.lensly.core.wallet.*'
  core-ur-evm    :shared:testDebugUnitTest --tests 'app.lensly.core.ur.*' --tests 'app.lensly.core.evm.*'
                 (then a second invocation, -PfuzzLane: app.lensly.fuzz.* covers UR/CBOR, eth-sign and RLP)
  core-parse     :shared:testDebugUnitTest --tests 'app.lensly.core.parse.*'
  core-vault     :shared:testDebugUnitTest --tests 'app.lensly.core.vault.*'
  core-registry  :shared:testDebugUnitTest --tests 'app.lensly.core.registry.*' --tests 'app.lensly.core.registry.update.*'
  core-btc       :shared:testDebugUnitTest --tests 'app.lensly.core.btc.*'
  ui-a           :app:testDebugUnitTest -PprLane --tests 'app.lensly.ui.settings.*' --tests 'app.lensly.ui.wallet.*'
                 --tests 'app.lensly.ui.navigation.*' --tests 'app.lensly.ui.a11y.*' --tests 'app.lensly.ui.screenshot.*'
                 :shared:testDebugUnitTest -PprLane --tests 'app.lensly.ui.camera.*' --tests 'app.lensly.ui.qr.*'
                 --tests 'app.lensly.ui.theme.*'
  ui-b           :app:testDebugUnitTest -PprLane --tests 'app.lensly.ui.flow.*' --tests 'app.lensly.ui.home.*'
                 --tests 'app.lensly.ui.sign.*' --tests 'app.lensly.ui.profile.*' --tests 'app.lensly.ui.components.*'
                 :shared:testDebugUnitTest -PprLane --tests 'app.lensly.ui.components.*' --tests 'app.lensly.ui.gate.*'
                 --tests 'app.lensly.ui.SecureWindowTest'
  app            :app:testDebugUnitTest --tests 'app.lensly.app.*' --tests 'app.lensly.SmokeTest'
                 --tests 'app.lensly.core.vault.BackupExclusionTest'
  lumio          :lumio:assembleDebug :lumio:testDebugUnitTest
  registry-pack  :tools:registry-pack:test
  manifest       scripts/ci/check-manifest.sh
  ci             bash -n on every script, scripts/ci/check-ui-partitions.sh, then scripts/ci/check-test-network.sh (no Gradle)
USAGE_END
}

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi

partition="$1"

case "$partition" in
    core-wallet)
        ./gradlew :shared:testDebugUnitTest --tests 'app.lensly.core.wallet.*'
        ;;
    core-ur-evm)
        # Gradle ORs repeated --tests filters. evm landed after the 2.2 table was written.
        # The fuzz targets replay their committed seeds in their own JVM (Jazzer and Robolectric cannot share
        # one, see the -PfuzzLane note in shared/build.gradle.kts). They cover UrScanner/UR, CBOR, the eth-sign
        # request and the unsigned (RLP) transaction decoder, so they belong to this partition.
        ./gradlew :shared:testDebugUnitTest \
            --tests 'app.lensly.core.ur.*' \
            --tests 'app.lensly.core.evm.*'
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
    # The app.lensly.ui tests are split in two halves so each stays under the 3-minute cap (guideline 2.1).
    # A new ui subpackage, or a test class directly in app.lensly.ui, must be added to ONE of these two
    # lists. scripts/ci/check-ui-partitions.sh (run by the ci partition) fails when one is in neither.
    # Each half runs both modules: :app for the Lensly screens, :shared for the code both apps reuse.
    # A package with tests in both modules (components) appears in both invocations of its half.
    # -PprLane drops app.lensly.ui.screenshot.* (Roborazzi pixel tests) from both halves. They are
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
            --tests 'app.lensly.ui.theme.*'
        ;;
    ui-b)
        ./gradlew :app:testDebugUnitTest -PprLane \
            --tests 'app.lensly.ui.flow.*' \
            --tests 'app.lensly.ui.home.*' \
            --tests 'app.lensly.ui.sign.*' \
            --tests 'app.lensly.ui.profile.*' \
            --tests 'app.lensly.ui.components.*' \
            :shared:testDebugUnitTest -PprLane \
            --tests 'app.lensly.ui.components.*' \
            --tests 'app.lensly.ui.gate.*' \
            --tests 'app.lensly.ui.SecureWindowTest'
        ;;
    app)
        # Wallet stores, settings, export and sign pipelines, plus the module smoke test. BackupExclusionTest
        # reads the app's own manifest and backup rules, so it runs here and not in :shared.
        ./gradlew :app:testDebugUnitTest \
            --tests 'app.lensly.app.*' \
            --tests 'app.lensly.SmokeTest' \
            --tests 'app.lensly.core.vault.BackupExclusionTest'
        ;;
    lumio)
        # Lumio, the companion app: its debug APK and its Home test. Its cover, gate and theme come from :shared, which the
        # shared partitions test.
        ./gradlew :lumio:assembleDebug :lumio:testDebugUnitTest
        ;;
    registry-pack)
        ./gradlew :tools:registry-pack:test
        ;;
    manifest)
        exec "$ROOT/scripts/ci/check-manifest.sh"
        ;;
    ci)
        # CI's own files: a syntax check of every shell script plus the test-network guard.
        # No Gradle, so this stays well inside the 3-minute PR budget.
        scripts=0
        while IFS= read -r script; do
            bash -n "$script"
            scripts=$((scripts + 1))
        done < <(find "$ROOT/scripts" -type f -name '*.sh' | sort)
        echo "run-partition: bash -n passed for $scripts script(s)"
        "$ROOT/scripts/ci/check-ui-partitions.sh"
        exec "$ROOT/scripts/ci/check-test-network.sh"
        ;;
    *)
        echo "run-partition: unknown partition: $partition" >&2
        usage
        exit 2
        ;;
esac
