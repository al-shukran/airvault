#!/usr/bin/env bash
# Jazzer fuzzing run: 15 minutes per target by default (docs/testing/guideline.md 1.8, checklist 7).
#
# The ci.yml gate runs the same targets in regression mode with no flags: Jazzer replays the committed seeds as
# ordinary JUnit tests. Here JAZZER_FUZZ turns one target at a time into a real fuzzing run.
#
# Duration comes from the @FuzzTest annotation (maxDuration = "15m"), which Jazzer passes to libFuzzer as
# -max_total_time=900. A shorter run passes --seconds: the JUnit integration appends extra libFuzzer flags
# from jazzer.internal.arg.<n> after the annotation value, so a later -max_total_time wins (shared/build.gradle.kts
# turns -PfuzzMaxSeconds into that configuration parameter).
#
# A crash is written under shared/src/test/resources/app/lensly/fuzz/<Class>Inputs/<method>/, which the regression
# run replays as soon as the file is committed.
#
# Usage: scripts/ci/fuzz-nightly.sh [--list] [--seconds N] [target]
#   no target  fuzzes all five, one Gradle invocation each (about 75 minutes at 15 minutes per target); nightly runs this
#   target     fuzzes that one target only
#   --seconds  cap each target at N seconds instead of the 15-minute annotation (/run-fuzz uses 60)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

usage() {
    cat >&2 <<'EOF'
usage: scripts/ci/fuzz-nightly.sh [--list] [--seconds N] [target]

  --list      print the five target class names and exit
  --seconds   fuzz each target for N seconds instead of the 15-minute annotation
  target      fuzz only this class (default: all five)
EOF
}

targets=(
    app.lensly.fuzz.UrScannerFuzzTest
    app.lensly.fuzz.CborFuzzTest
    app.lensly.fuzz.EthSignRequestFuzzTest
    app.lensly.fuzz.UnsignedTxFuzzTest
    app.lensly.fuzz.AlgorandRequestFuzzTest
)

list=false
seconds=""
selected=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --list) list=true; shift ;;
        --seconds)
            if [ "$#" -lt 2 ]; then
                usage
                exit 2
            fi
            seconds="$2"
            shift 2
            ;;
        -*) echo "fuzz-nightly: unknown option: $1" >&2; usage; exit 2 ;;
        *)
            if [ -n "$selected" ]; then
                echo "fuzz-nightly: only one target is accepted" >&2
                usage
                exit 2
            fi
            selected="$1"
            shift
            ;;
    esac
done

if [ "$list" = true ]; then
    printf '%s\n' "${targets[@]}"
    exit 0
fi

if [ -n "$seconds" ]; then
    case "$seconds" in
        '' | *[!0-9]*)
            echo "fuzz-nightly: --seconds needs a whole number: $seconds" >&2
            usage
            exit 2
            ;;
        0)
            echo "fuzz-nightly: --seconds must be at least 1" >&2
            exit 2
            ;;
    esac
fi

if [ -n "$selected" ]; then
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

if [ -n "$seconds" ]; then
    label="${seconds}s"
else
    label="15 minutes"
fi

# Fuzzing runs no Robolectric test, so it does not fetch the Robolectric SDK jar (-x). Its remaining Maven Central
# downloads get more retries than gradle.properties sets (-D, Gradle's repository retry): a HTTP 429 burst from Maven Central
# outlasted that setting in nightly fuzz runs. Changing gradle.properties or a build script would also change the
# dependency-cache key in ci.yml and force an online resolve in the gate, so the extra retries are set here, for fuzzing only.
fuzz_flags=(-x :syncRobolectricSdk -Dorg.gradle.internal.repository.max.tentatives=8)

status=0
for target in "${targets[@]}"; do
    echo "fuzz-nightly: $target, $label"
    # The test task's inputs do not change between runs, so drop its outputs first or Gradle skips the run.
    # Each target is its own Gradle invocation: Jazzer only fuzzes one @FuzzTest per run.
    # -PfuzzLane selects the fuzz package, which the shared unit-test JVM skips: Jazzer and Robolectric
    # cannot share a JVM (see the note in shared/build.gradle.kts).
    # --no-build-cache: JAZZER_FUZZ is not a task input, so a cache hit restores old outputs and runs no fuzzing.
    if [ -n "$seconds" ]; then
        JAZZER_FUZZ=1 ./gradlew --no-build-cache "${fuzz_flags[@]}" :shared:cleanTestDebugUnitTest :shared:testDebugUnitTest \
            --tests "$target" -PfuzzLane "-PfuzzMaxSeconds=$seconds" || status=1
    else
        JAZZER_FUZZ=1 ./gradlew --no-build-cache "${fuzz_flags[@]}" :shared:cleanTestDebugUnitTest :shared:testDebugUnitTest \
            --tests "$target" -PfuzzLane || status=1
    fi
done

exit "$status"
