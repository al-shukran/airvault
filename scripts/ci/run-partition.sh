#!/usr/bin/env bash
# Runs one CI lane (scripts/ci/README.md). Every Gradle call runs --offline except `resolve`, the one online step.
# The test lanes come from scripts/ci/shards.txt, where each test class is in exactly one lane.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
SHARDS="$ROOT/scripts/ci/shards.txt"

# Watchdog, kept on purpose. If this lane is still running after 240 s, the job log gets the JVM thread dumps (every Gradle
# daemon, worker and wrapper JVM) and the open sockets. The watchdog only dumps: the job's own timeout still ends a stalled
# run, and nothing is retried.
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
usage: scripts/ci/run-partition.sh <lane>

lanes:
  resolve      the one online step: ./gradlew resolveAll, retried only on network errors
  shard-1..6   the unit tests listed under that lane in scripts/ci/shards.txt; pure JVM modules run whole
  verify       the Roborazzi pixel tests of :app and :lumio, with verifyRoborazziDebug
  fuzz         the Jazzer seed replays (app.lensly.fuzz.*), with -PfuzzLane
  android      androidTest and benchmark compile of :app and :lumio, and the Lumio debug APK
  lint         lintDebug of every module
  manifest     merged manifests: no INTERNET in :app, Lumio's allow-list, OkHttp off the release classpath of :app
  ci           bash -n on every script, check-partitions.sh and check-test-network.sh (no Gradle)
USAGE_END
}

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi
lane="$1"

# Prints the modules that a lane lists, in file order, once each.
lane_modules() {
    local l m c
    while read -r l m c; do
        case "$l" in
            '' | \#*) continue ;;
        esac
        if [ "$l" = "$1" ]; then printf '%s\n' "$m"; fi
    done < "$SHARDS" | awk '!seen[$0]++'
}

# Prints the classes that a lane lists for one module.
lane_classes() {
    local l m c
    while read -r l m c; do
        case "$l" in
            '' | \#*) continue ;;
        esac
        if [ "$l" = "$1" ] && [ "$m" = "$2" ]; then printf '%s\n' "$c"; fi
    done < "$SHARDS"
}

# Runs one online Gradle call. Network errors are retried, because a dropped connection is not a defect. Any other failure
# fails at once: retrying a verification or a missing artifact only hides it.
online() {
    local attempt log
    for attempt in 1 2 3; do
        log="$(mktemp)"
        if "$@" 2>&1 | tee "$log"; then
            rm -f "$log"
            return 0
        fi
        if ! grep -q -E 'Could not GET|Could not HEAD|Connect timed out|Read timed out|Connection reset|failed: timeout|Received status code|status code (429|50[0-9])' "$log"; then
            rm -f "$log"
            echo "run-partition: $1 failed without a network error, so it is not retried" >&2
            exit 1
        fi
        rm -f "$log"
        echo "::warning::run-partition: $1 hit a network error (attempt $attempt of 3); retrying in 30 s"
        sleep 30
    done
    echo "run-partition: $1 failed after 3 attempts" >&2
    exit 1
}

# A configuration-cache store resolves the plugins' detached configurations (AGP's aapt2, KSP's symbol processor), which no
# project lists. The offline lanes store the same entries, so their stores need those artifacts cached. A dry run with the
# configuration cache on stores the entry, online, for the tasks the lanes run.
prime_tasks=(
    :app:testDebugUnitTest :app:processReleaseMainManifest :app:verifyRoborazziDebug
    :app:compileDebugAndroidTestKotlin :app:compileBenchmarkKotlin :app:compileBenchmarkAndroidTestKotlin
    :lumio:testDebugUnitTest :lumio:verifyRoborazziDebug :lumio:compileDebugAndroidTestKotlin :lumio:compileBenchmarkKotlin
    :lumio:assembleDebug :shared:testDebugUnitTest lintDebug
)

run_resolve() {
    online ./gradlew --no-configuration-cache resolveAll
    online ./gradlew --dry-run --configuration-cache "${prime_tasks[@]}"
}

run_shard() {
    local -a tasks=()
    local m c
    while IFS= read -r m; do
        case "$m" in
            :app | :lumio | :shared)
                local -a tests=()
                while IFS= read -r c; do
                    tests+=(--tests "$c")
                done < <(lane_classes "$1" "$m")
                tasks+=("$m:testDebugUnitTest" "${tests[@]}")
                ;;
            *)
                tasks+=("$m:test")
                ;;
        esac
    done < <(lane_modules "$1")
    if [ "${#tasks[@]}" -eq 0 ]; then
        echo "run-partition: lane $1 lists no tests in $SHARDS" >&2
        exit 1
    fi
    ./gradlew --offline "${tasks[@]}"
}

run_verify() {
    local -a app=() lumio=() c
    while IFS= read -r c; do
        app+=(--tests "$c")
    done < <(lane_classes verify :app)
    while IFS= read -r c; do
        lumio+=(--tests "$c")
    done < <(lane_classes verify :lumio)
    # The filtered test task runs the pixel tests in verify mode (Roborazzi switches it on when the verify task is requested), so
    # each pixel test runs once. Without the filter, verifyRoborazziDebug would run every unit test of the module again.
    ./gradlew --offline :app:testDebugUnitTest "${app[@]}" :app:verifyRoborazziDebug \
        :lumio:testDebugUnitTest "${lumio[@]}" :lumio:verifyRoborazziDebug
}

run_fuzz() {
    local -a tests=()
    local c
    while IFS= read -r c; do
        tests+=(--tests "$c")
    done < <(lane_classes fuzz :shared)
    # The fuzz targets read -PfuzzLane and run in their own JVM: Jazzer and Robolectric cannot share one.
    ./gradlew --offline :shared:testDebugUnitTest "${tests[@]}" -PfuzzLane
}

run_android() {
    ./gradlew --offline :app:compileDebugAndroidTestKotlin :app:compileBenchmarkKotlin :app:compileBenchmarkAndroidTestKotlin \
        :lumio:compileDebugAndroidTestKotlin :lumio:compileBenchmarkKotlin :lumio:assembleDebug
}

run_lint() {
    ./gradlew --offline lintDebug
}

run_manifest() {
    # The merged debug manifests of :app and :lumio (check-manifest.sh runs its own Gradle task, offline).
    scripts/ci/check-manifest.sh
    # Lensly (:app) is offline in release too. check-manifest.sh reads only the debug merge, so the release merge is read here.
    ./gradlew --offline :app:processReleaseMainManifest
    local merged
    merged="$(find app/build/intermediates -path '*merged_manifest*/release/*' -name AndroidManifest.xml | head -n 1)"
    if [ -z "$merged" ]; then
        echo "error: merged release manifest of :app not found under app/build/intermediates" >&2
        exit 1
    fi
    if grep -q '"android.permission.INTERNET"' "$merged"; then
        echo "error: android.permission.INTERNET is in the merged release manifest of :app; Lensly is offline by design" >&2
        exit 1
    fi
    echo "ok: the merged release manifest of :app has no android.permission.INTERNET"
    # R56: web3j brings OkHttp, the HTTP client, and shared excludes it from web3j. :app never calls it.
    local deps
    deps="$(./gradlew --offline :app:dependencies --configuration releaseRuntimeClasspath -q)"
    if printf '%s\n' "$deps" | grep -q 'com\.squareup\.okhttp3'; then
        echo "error: OkHttp is on the release runtime classpath of :app; Lensly is offline by design (see shared/build.gradle.kts)" >&2
        exit 1
    fi
    echo "ok: no OkHttp on the release runtime classpath of :app"
}

run_ci() {
    local scripts=0 script
    while IFS= read -r script; do
        bash -n "$script"
        scripts=$((scripts + 1))
    done < <(find "$ROOT/scripts" -type f -name '*.sh' | sort)
    echo "run-partition: bash -n passed for $scripts script(s)"
    "$ROOT/scripts/ci/check-partitions.sh"
    "$ROOT/scripts/ci/check-test-network.sh"
}

case "$lane" in
    resolve) run_resolve ;;
    shard-[1-6]) run_shard "$lane" ;;
    verify) run_verify ;;
    fuzz) run_fuzz ;;
    android) run_android ;;
    lint) run_lint ;;
    manifest) run_manifest ;;
    ci) run_ci ;;
    *)
        echo "run-partition: unknown lane: $lane" >&2
        usage
        exit 2
        ;;
esac
