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
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

targets=(
    app.lensly.fuzz.UrScannerFuzzTest
    app.lensly.fuzz.CborFuzzTest
    app.lensly.fuzz.EthSignRequestFuzzTest
    app.lensly.fuzz.UnsignedTxFuzzTest
)

if [ "${1:-}" = "--list" ]; then
    printf '%s\n' "${targets[@]}"
    exit 0
fi

if [ "$#" -ne 0 ]; then
    echo "usage: scripts/ci/fuzz-nightly.sh [--list]" >&2
    exit 2
fi

status=0
for target in "${targets[@]}"; do
    echo "fuzz-nightly: $target, 15 minutes"
    # The test task's inputs do not change between runs, so drop its outputs first or Gradle skips the run.
    # Each target is its own Gradle invocation: Jazzer only fuzzes one @FuzzTest per run.
    JAZZER_FUZZ=1 ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests "$target" || status=1
done

exit "$status"
