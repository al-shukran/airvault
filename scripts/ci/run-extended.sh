#!/usr/bin/env bash
# Dispatch the /run-* comment commands (docs/testing/guideline.md 2.6) to their layers.
#
# kinds:
#   instrumented       1.3  :app:connectedDebugAndroidTest
#   e2e                1.6  :app:connectedDebugAndroidTest -P...class=app.lensly.e2e.WalletSignFlowTest
#   screenshot-testing 1.5  :app:verifyRoborazziDebug
#   perf               1.7  :app:connectedBenchmarkAndroidTest (reference device, self-hosted runner)
#   security           1.8  :app:lintDebug + :app:dependencyCheckAnalyze, then check-manifest.sh (10 to 15 min)
#   fuzz               1.8  scripts/ci/fuzz-nightly.sh --seconds 60 (real fuzzing, all four targets)
#
# Layers that have not landed yet print "not configured yet" and exit 0, so the script is
# usable locally before those stages exist. With CI=true the same case exits 1, so a
# scheduled or comment-triggered run can never pass by silently skipping.
#
# Not wired here: the nightly Jazzer run (15 min per target, scripts/ci/fuzz-nightly.sh) and
# gitleaks; the nightly workflow runs fuzzing as its own job. The OWASP scan is wired here, because
# guideline 2.7 maps both lint and OWASP to /run-security. NVD_API_KEY is optional: without it the
# NVD API rate limit makes the scan slower, but the scan still runs.
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
        # Runs on the self-hosted reference device (comment-run.yml), so no emulator suppression is needed
        # and the numbers can be judged against the committed baseline. The class filter keeps the a11y, e2e and
        # vault tests out of the benchmark APK run: the benchmark runner cannot measure them, and on the reference
        # device they would only add minutes to the gate.
        ./gradlew :app:connectedBenchmarkAndroidTest \
            -Pandroid.testInstrumentationRunnerArguments.class='app.lensly.benchmark.*'
        # Macrobenchmarks live in their own com.android.test module: they launch the installed app instead of running
        # inside it. Same device, same benchmark variant, so they run in the same pass.
        if [ -d "$ROOT/benchmark" ]; then
            ./gradlew :benchmark:connectedBenchmarkAndroidTest
        else
            echo "run-extended: no benchmark module, skipping the macrobenchmark (guideline 1.7)" >&2
        fi
        # AGP's result path moves between versions, so the caller names the result file. The runner is the
        # reference device, so the gate judges it; without a result file the gate makes no verdict.
        if [ -n "${BENCHMARK_RESULT:-}" ]; then
            "$ROOT/scripts/ci/benchmark-gate.sh" --reference-device "$BENCHMARK_RESULT"
        else
            echo "run-extended: set BENCHMARK_RESULT=<benchmarkData.json> to compare this run against the baseline" >&2
        fi
        ;;
    security)
        # Guideline 2.7: lint and OWASP together take 10 to 15 minutes. One Gradle invocation with
        # --continue, so a lint failure still lets the scan report its result, and check-manifest.sh
        # runs either way. Each step keeps its own line in the log, and the run exits non-zero when
        # any of them failed (same shape as the nightly gates in guideline 2.5).
        #
        # NVD_API_KEY is optional and read from the environment by app/build.gradle.kts. Without it
        # the NVD API is rate-limited, so the scan takes longer but still runs.
        status=0
        echo "run-security: :app:lintDebug :app:dependencyCheckAnalyze --continue"
        ./gradlew :app:lintDebug :app:dependencyCheckAnalyze --continue || status=1
        echo "run-security: check-manifest.sh"
        "$ROOT/scripts/ci/check-manifest.sh" || status=1
        if [ "$status" -ne 0 ]; then
            echo "run-security: at least one security step failed" >&2
        fi
        exit "$status"
        ;;
    fuzz)
        targets="$(find "$ROOT/app/src/test/java" -name '*Fuzz*.kt' 2>/dev/null || true)"
        [ -n "$targets" ] ||
            not_configured "Jazzer fuzz targets under app/src/test (guideline 1.8)"
        # Real fuzzing, not a seed replay: 60 seconds per target through the same script the nightly run uses.
        "$ROOT/scripts/ci/fuzz-nightly.sh" --seconds 60
        ;;
    *)
        echo "run-extended: unknown kind: $kind" >&2
        usage
        exit 2
        ;;
esac
