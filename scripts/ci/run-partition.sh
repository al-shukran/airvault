#!/usr/bin/env bash
# Runs one CI lane (scripts/ci/README.md). Every Gradle call runs --offline except `resolve`, the one online step.
# The test lanes come from scripts/ci/shards.txt. A test class the file does not list runs in its default lane (unlisted_units).
set -euo pipefail

# Gradle encrypts configuration-cache entries with GRADLE_ENCRYPTION_KEY when it is set, and otherwise with a keystore in the
# runner's Gradle user home. A fresh runner has a new keystore, so it cannot read an entry another runner saved. An empty value
# is not a key (a fork's pull request gets no secret), so it is unset and Gradle uses its own keystore.
if [ -z "${GRADLE_ENCRYPTION_KEY:-}" ]; then
    unset GRADLE_ENCRYPTION_KEY
fi

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
  shard-1..6   the unit tests shards.txt lists under the lane; shard-6 also runs the unlisted ones; pure JVM modules run whole
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

# Prints "<lane> <module> <class>" for every test unit that shards.txt does not list. A Jazzer target runs in fuzz, a screenshot
# test of :app or :lumio in verify, and every other unit in shard-6, the catch-all lane.
unlisted_units() {
    "$ROOT/scripts/ci/test-units.sh" | awk -v lanes="$SHARDS" '
        BEGIN {
            while ((getline line < lanes) > 0) {
                if (line ~ /^(#|[ \t]*$)/) continue
                split(line, f, " ")
                listed[f[2] " " f[3]] = 1
            }
        }
        !(($1 " " $2) in listed)
    ' | while read -r module class _; do
        case "$module:$class" in
            ':shared:app.lensly.fuzz.'*) echo "fuzz $module $class" ;;
            ':app:'*ScreenshotTest | ':lumio:'*ScreenshotTest) echo "verify $module $class" ;;
            *) echo "shard-6 $module $class" ;;
        esac
    done
}

# Prints "<module> <class>" for the test units of one lane: the classes that shards.txt lists in it, then the unlisted units that
# unlisted_units routes to it. $2 is the output of unlisted_units, so the test-unit scan runs once per invocation.
lane_units() {
    local lane=$1 unlisted=$2 l m c
    while read -r l m c; do
        case "$l" in
            '' | \#*) continue ;;
        esac
        if [ "$l" = "$lane" ]; then printf '%s %s\n' "$m" "$c"; fi
    done < "$SHARDS"
    printf '%s\n' "$unlisted" | while read -r l m c; do
        if [ "$l" = "$lane" ]; then printf '%s %s\n' "$m" "$c"; fi
    done
}

# Runs one online Gradle call. Network errors are retried, because a dropped connection is not a defect. Any other failure
# fails at once: retrying a verification only hides it. An unreachable repository shows as "Could not find ... Searched in the
# following locations" (or "Could not parse POM") for artifacts that exist, with no HTTP status (run 44603626048: every
# repository at once), so those count as network errors. A missing coordinate matches that text too and fails after three tries.
online() {
    local attempt log
    for attempt in 1 2 3; do
        log="$(mktemp)"
        if "$@" 2>&1 | tee "$log"; then
            rm -f "$log"
            return 0
        fi
        if ! grep -q -E 'Could not GET|Could not HEAD|Connect timed out|Read timed out|Connection reset|failed: timeout|Received status code|status code (429|50[0-9])|Searched in the following locations|Could not parse POM' "$log"; then
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
    local unlisted units m c
    unlisted=$(unlisted_units)
    units=$(lane_units "$1" "$unlisted")
    if [ -z "$units" ]; then
        echo "run-partition: lane $1 has no test units" >&2
        exit 1
    fi
    printf '%s\n' "$unlisted" | awk -v lane="$1" '$1 == lane { printf "run-partition: %s runs the unlisted %s %s\n", lane, $2, $3 }'
    while IFS= read -r m; do
        case "$m" in
            :app | :lumio | :shared)
                local -a tests=()
                while IFS= read -r c; do
                    tests+=(--tests "$c")
                done < <(printf '%s\n' "$units" | awk -v m="$m" '$1 == m { print $2 }')
                tasks+=("$m:testDebugUnitTest" "${tests[@]}")
                ;;
            *)
                tasks+=("$m:test")
                ;;
        esac
    done < <(printf '%s\n' "$units" | awk '!seen[$1]++ { print $1 }')
    ./gradlew --offline "${tasks[@]}"
}

run_verify() {
    local -a app=() lumio=()
    local unlisted m c
    unlisted=$(unlisted_units)
    while read -r m c; do
        case "$m" in
            :app) app+=(--tests "$c") ;;
            :lumio) lumio+=(--tests "$c") ;;
            *)
                echo "run-partition: verify lists $m $c, and only :app and :lumio have Roborazzi tasks" >&2
                exit 1
                ;;
        esac
    done < <(lane_units verify "$unlisted")
    # The filtered test task runs the pixel tests in verify mode (Roborazzi switches it on when the verify task is requested), so
    # each pixel test runs once. Without the filter, verifyRoborazziDebug would run every unit test of the module again.
    ./gradlew --offline :app:testDebugUnitTest "${app[@]}" :app:verifyRoborazziDebug \
        :lumio:testDebugUnitTest "${lumio[@]}" :lumio:verifyRoborazziDebug
}

run_fuzz() {
    local -a tests=()
    local unlisted c
    unlisted=$(unlisted_units)
    while IFS= read -r c; do
        tests+=(--tests "$c")
    done < <(lane_units fuzz "$unlisted" | awk '$1 == ":shared" { print $2 }')
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
