#!/usr/bin/env bash
# Nightly Jazzer run: 15 minutes of fuzzing per target (docs/testing/guideline.md 1.8, checklist 7).
#
# The PR lane runs the same targets in regression mode with no flags: Jazzer replays the committed seeds as
# ordinary JUnit tests. Here JAZZER_FUZZ turns one target at a time into a real fuzzing run.
#
# Duration comes from the @FuzzTest annotation (maxDuration = "15m"), which Jazzer passes to libFuzzer as
# -max_total_time=900. The JUnit integration has no -Djazzer.max_total_time property; if a run ever needs a
# different length, change the annotation or pass jazzer.internal.arg.max_duration to the test JVM.
#
# A crash is written under app/src/test/resources/app/lensly/fuzz/<Class>Inputs/<method>/, which the regression
# run replays as soon as the file is committed.
#
# Usage: scripts/ci/fuzz-nightly.sh [--list] [target]
#   no target  fuzzes all four, one Gradle invocation each (about an hour)
#   target     fuzzes that one target only; the nightly workflow uses one matrix job per target
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

usage() {
    cat >&2 <<'EOF'
usage: scripts/ci/fuzz-nightly.sh [--list] [target]

  --list   print the four target class names and exit
  target   fuzz only this class (default: all four)
EOF
}

targets=(
    app.lensly.fuzz.UrScannerFuzzTest
    app.lensly.fuzz.CborFuzzTest
    app.lensly.fuzz.EthSignRequestFuzzTest
    app.lensly.fuzz.UnsignedTxFuzzTest
)

if [ "${1:-}" = "--list" ] && [ "$#" -eq 1 ]; then
    printf '%s\n' "${targets[@]}"
    exit 0
fi

if [ "$#" -gt 1 ]; then
    usage
    exit 2
fi

if [ "$#" -eq 1 ]; then
    selected="$1"
    found=false
    for target in "${targets[@]}"; do
        if [ "$target" = "$selected" ]; then
            found=true
        fi
    done
    if [ "$found" != true ]; then
        echo "fuzz-nightly: unknown target: $selected" >&2
        usage
        exit 2
    fi
    targets=("$selected")
fi

status=0
for target in "${targets[@]}"; do
    echo "fuzz-nightly: $target, 15 minutes"
    # The test task's inputs do not change between runs, so drop its outputs first or Gradle skips the run.
    # Each target is its own Gradle invocation: Jazzer only fuzzes one @FuzzTest per run.
    JAZZER_FUZZ=1 ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "$target" || status=1
done

exit "$status"
