#!/usr/bin/env bash
# Dispatch the /run-* comment commands (docs/testing/guideline.md 2.6) to their layers.
#
# kinds:
#   instrumented       1.3  :app:connectedDebugAndroidTest
#   e2e                1.6  :app:connectedDebugAndroidTest -P...class=app.lensly.e2e.WalletSignFlowTest
#   screenshot-testing 1.5  :app:verifyRoborazziDebug
#   perf               1.7  :app:connectedBenchmarkAndroidTest
#   security           1.8  :app:lintDebug then check-manifest.sh
#   fuzz               1.8  :app:testDebugUnitTest replayed against the fuzz targets
#
# Layers that have not landed yet print "not configured yet" and exit 0, so the script is
# usable locally before those stages exist. With CI=true the same case exits 1, so a
# scheduled or comment-triggered run can never pass by silently skipping.
#
# Not wired here: the nightly Jazzer run (15 min per target) and gitleaks; those arrive with
# their own stages (checklist 4 and 7). OWASP dependencyCheckAnalyze is deliberately absent too:
# guideline 1.8 keeps it nightly, and the nightly workflow already runs it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

usage() {
    cat >&2 <<'EOF'
usage: scripts/ci/run-extended.sh <kind>

kinds: instrumented | e2e | screenshot-testing | perf | security | fuzz
EOF
}

not_configured() {
    echo "run-extended: not configured yet: $1" >&2
    if [ "${CI:-}" = "true" ]; then
        echo "run-extended: CI=true, so this run fails instead of skipping" >&2
        exit 1
    fi
    echo "run-extended: exiting 0 for local use" >&2
    exit 0
}

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi

kind="$1"

case "$kind" in
    instrumented)
        # The benchmark classes need AndroidBenchmarkRunner, which only the benchmark variant gets, so a debug
        # instrumented run skips that package instead of failing on the runner check.
        ./gradlew :app:connectedDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.notPackage=app.lensly.benchmark
        ;;
    e2e)
        [ -d "$ROOT/app/src/androidTest/java/app/lensly/e2e" ] ||
            not_configured "E2E flow: the FrameSource seam and app.lensly.e2e.WalletSignFlowTest (guideline 1.6)"
        ./gradlew :app:connectedDebugAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.class=app.lensly.e2e.WalletSignFlowTest
        ;;
    screenshot-testing)
        ls "$ROOT"/app/src/test/screenshots/*.png >/dev/null 2>&1 ||
            not_configured "Roborazzi goldens under app/src/test/screenshots (guideline 1.5)"
        ./gradlew :app:verifyRoborazziDebug
        ;;
    perf)
        grep -q 'benchmark' "$ROOT/app/build.gradle.kts" ||
            not_configured "benchmark build type and :app:connectedBenchmarkAndroidTest (guideline 1.7)"
        ./gradlew :app:connectedBenchmarkAndroidTest
        # AGP's result path moves between versions, so the caller names the result file. Without it the gate is not
        # run here; the nightly job passes BENCHMARK_RESULT after pulling the JSON off the device.
        if [ -n "${BENCHMARK_RESULT:-}" ]; then
            "$ROOT/scripts/ci/benchmark-gate.sh" "$BENCHMARK_RESULT"
        else
            echo "run-extended: set BENCHMARK_RESULT=<benchmarkData.json> to compare against the reference baseline" >&2
        fi
        ;;
    security)
        ./gradlew :app:lintDebug
        "$ROOT/scripts/ci/check-manifest.sh"
        ;;
    fuzz)
        targets="$(find "$ROOT/app/src/test/java" -name '*Fuzz*.kt' 2>/dev/null || true)"
        [ -n "$targets" ] ||
            not_configured "Jazzer fuzz targets under app/src/test (guideline 1.8)"
        ./gradlew :app:testDebugUnitTest --tests 'app.lensly.*Fuzz*'
        ;;
    *)
        echo "run-extended: unknown kind: $kind" >&2
        usage
        exit 2
        ;;
esac
