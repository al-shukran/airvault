#!/usr/bin/env bash
# Run one PR-lane partition (docs/testing/guideline.md 2.2).
# Each partition is a single Gradle invocation with one filtered task, so the build cache
# absorbs unchanged compile work and the log names exactly one selector.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

usage() {
    cat >&2 <<'EOF'
usage: scripts/ci/run-partition.sh <partition>

partitions:
  core-wallet    :app:testDebugUnitTest --tests 'app.lensly.core.wallet.*'
  core-ur-evm    :app:testDebugUnitTest --tests 'app.lensly.core.ur.*' --tests 'app.lensly.core.evm.*'
  core-parse     :app:testDebugUnitTest --tests 'app.lensly.core.parse.*'
  core-vault     :app:testDebugUnitTest --tests 'app.lensly.core.vault.*'
  core-registry  :app:testDebugUnitTest --tests 'app.lensly.core.registry.*' --tests 'app.lensly.core.registry.update.*'
  ui             :app:testDebugUnitTest --tests 'app.lensly.ui.*'
  app            :app:testDebugUnitTest --tests 'app.lensly.app.*' --tests 'app.lensly.SmokeTest'
  registry-pack  :tools:registry-pack:test
  manifest       scripts/ci/check-manifest.sh
EOF
}

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi

partition="$1"

case "$partition" in
    core-wallet)
        ./gradlew :app:testDebugUnitTest --tests 'app.lensly.core.wallet.*'
        ;;
    core-ur-evm)
        # Gradle ORs repeated --tests filters. evm landed after the 2.2 table was written.
        ./gradlew :app:testDebugUnitTest \
            --tests 'app.lensly.core.ur.*' \
            --tests 'app.lensly.core.evm.*'
        ;;
    core-parse)
        # Parsing untrusted payloads (DecodeBudget). Paired with core-ur-evm in the workflow.
        ./gradlew :app:testDebugUnitTest --tests 'app.lensly.core.parse.*'
        ;;
    core-vault)
        ./gradlew :app:testDebugUnitTest --tests 'app.lensly.core.vault.*'
        ;;
    core-registry)
        # The second filter is explicit so registry.update stays covered even if a Gradle
        # version ever stops letting '*' cross package separators.
        ./gradlew :app:testDebugUnitTest \
            --tests 'app.lensly.core.registry.*' \
            --tests 'app.lensly.core.registry.update.*'
        ;;
    ui)
        # -PprLane drops app.lensly.ui.screenshot.* (Roborazzi pixel tests) from this partition.
        # They push it past the 3-minute cap (guideline 2.1) and are verified nightly by
        # verifyRoborazziDebug instead (guideline 2.7).
        ./gradlew :app:testDebugUnitTest --tests 'app.lensly.ui.*' -PprLane
        ;;
    app)
        # Wallet stores, settings, export and sign pipelines, plus the module smoke test.
        ./gradlew :app:testDebugUnitTest \
            --tests 'app.lensly.app.*' \
            --tests 'app.lensly.SmokeTest'
        ;;
    registry-pack)
        ./gradlew :tools:registry-pack:test
        ;;
    manifest)
        exec "$ROOT/scripts/ci/check-manifest.sh"
        ;;
    *)
        echo "run-partition: unknown partition: $partition" >&2
        usage
        exit 2
        ;;
esac
