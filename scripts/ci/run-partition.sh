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
  core-vault     :app:testDebugUnitTest --tests 'app.lensly.core.vault.*'
  core-registry  :app:testDebugUnitTest --tests 'app.lensly.core.registry.*' --tests 'app.lensly.core.registry.update.*'
  ui             :app:testDebugUnitTest --tests 'app.lensly.ui.*'
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
        ./gradlew :app:testDebugUnitTest --tests 'app.lensly.ui.*'
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
