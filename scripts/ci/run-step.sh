#!/usr/bin/env bash
# Runs one CI step (scripts/ci/README.md). ci.yml runs the gate steps in this order, in one job: static, manifest, compile, unit,
# fuzz. nightly.yml runs lint, apk and android-tests. Every Gradle call runs --offline except `resolve`, the one online step, and
# none runs --continue: the first failing task stops the step, and a test task stops at its first failing test (--fail-fast).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

usage() {
    cat >&2 <<'USAGE_END'
usage: scripts/ci/run-step.sh <step>

steps:
  resolve        the one online step: ./gradlew resolveAll, retried only on network errors
  static         no Gradle: bash -n on every script, the test-network, workflow, cleartext and mnemonic guards, the guard self-test
  manifest       merged manifests: no INTERNET or ACCESS_NETWORK_STATE in :app, Lumio's allow-list, OkHttp off :app's release classpath
  compile        the main and unit-test code of :app, :lumio, :shared and of every pure JVM module with tests
  unit           every unit test except the Roborazzi pixel tests of :app and :lumio and the fuzz targets
  fuzz           the Jazzer seed replays (app.lensly.fuzz.*), in their own JVM with -PfuzzLane
  lint           lintReportDebug of the main sources of :app, :lumio and :shared (nightly)
  apk            the Lumio debug APK, :lumio:assembleDebug (nightly)
  android-tests  androidTest and benchmark compile of :app and :lumio (nightly)
USAGE_END
}

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi
step="$1"

gradle() {
    ./gradlew --offline "$@"
}

# The pure JVM modules with a src/test set, as Gradle paths (core/model becomes :core:model).
jvm_test_modules() {
    find core chains abi tools -type d -path '*/src/test' -not -path '*/build/*' | sort \
        | sed -e 's#/src/test$##' -e 's#/#:#g' -e 's#^#:#'
}

# Runs one online Gradle call. Network errors are retried, because a dropped connection is not a defect. Any other failure
# fails at once: retrying a verification only hides it. An unreachable repository shows as "Could not find ... Searched in the
# following locations", so that message counts as a network error too.
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
            echo "run-step: $1 failed without a network error, so it is not retried" >&2
            exit 1
        fi
        rm -f "$log"
        echo "::warning::run-step: $1 hit a network error (attempt $attempt of 3); retrying in 30 s"
        sleep 30
    done
    echo "run-step: $1 failed after 3 attempts" >&2
    exit 1
}

# A configuration-cache store resolves the plugins' detached configurations (AGP's aapt2, KSP's symbol processor), which no
# project lists. The offline steps store the same entries, so their stores need those artifacts cached. A dry run with the
# configuration cache on stores the entry, online, for the tasks the offline steps run.
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

run_static() {
    local scripts=0 script
    while IFS= read -r script; do
        bash -n "$script"
        scripts=$((scripts + 1))
    done < <(find "$ROOT/scripts" -type f -name '*.sh' | sort)
    echo "run-step: bash -n passed for $scripts script(s)"
    scripts/ci/check-test-network.sh
    scripts/ci/check-mnemonics.sh
    scripts/ci/check-cleartext-sources.sh
    scripts/ci/check-workflow-guards.sh
    scripts/ci/selftest-guards.sh
}

run_manifest() {
    # The merged debug manifests of :app and :lumio (check-manifest.sh runs its own Gradle task, offline).
    scripts/ci/check-manifest.sh
    # Lensly (:app) is offline in release too. check-manifest.sh reads only the debug merge, so the release merge is read here.
    gradle :app:processReleaseMainManifest
    local merged
    merged="$(find app/build/intermediates -path '*merged_manifest*/release/*' -name AndroidManifest.xml | head -n 1)"
    if [ -z "$merged" ]; then
        echo "error: merged release manifest of :app not found under app/build/intermediates" >&2
        exit 1
    fi
    for permission in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE; do
        if grep -q "\"$permission\"" "$merged"; then
            echo "error: $permission is in the merged release manifest of :app; Lensly is offline by design" >&2
            exit 1
        fi
    done
    echo "ok: the merged release manifest of :app has no INTERNET and no ACCESS_NETWORK_STATE permission"
    # R56: web3j brings OkHttp, the HTTP client, and shared excludes it from web3j. :app never calls it.
    local deps
    deps="$(gradle :app:dependencies --configuration releaseRuntimeClasspath -q)"
    if printf '%s\n' "$deps" | grep -q 'com\.squareup\.okhttp3'; then
        echo "error: OkHttp is on the release runtime classpath of :app; Lensly is offline by design (see shared/build.gradle.kts)" >&2
        exit 1
    fi
    echo "ok: no OkHttp on the release runtime classpath of :app"
}

run_compile() {
    local -a tasks=(:app:compileDebugUnitTestKotlin :lumio:compileDebugUnitTestKotlin :shared:compileDebugUnitTestKotlin)
    local m
    while IFS= read -r m; do
        tasks+=("$m:testClasses")
    done < <(jvm_test_modules)
    gradle "${tasks[@]}"
}

# Gradle reports NO-SOURCE, and passes, for a module whose src/test folder was renamed or removed, so the unit step would go green
# with no tests. Each of the three Android modules must keep a test source file.
require_unit_tests() {
    local module
    for module in app lumio shared; do
        if [ -z "$(find "$module/src/test/java" "$module/src/test/kotlin" -type f -name '*Test.kt' -print -quit 2>/dev/null)" ]; then
            echo "error: no *Test.kt file under $module/src/test/java or $module/src/test/kotlin; the unit step would pass with no $module tests" >&2
            exit 1
        fi
    done
}

run_unit() {
    require_unit_tests
    # -PskipScreenshotTests leaves out the pixel tests of :app and :lumio (root build.gradle.kts); nightly records their goldens.
    # :shared leaves out the fuzz targets unless -PfuzzLane is set (shared/build.gradle.kts), so the fuzz step runs them alone.
    local -a tasks=(
        :app:testDebugUnitTest --fail-fast
        :lumio:testDebugUnitTest --fail-fast
        :shared:testDebugUnitTest --fail-fast
    )
    local m
    while IFS= read -r m; do
        tasks+=("$m:test" --fail-fast)
    done < <(jvm_test_modules)
    gradle -PskipScreenshotTests "${tasks[@]}"
}

run_fuzz() {
    # Jazzer and Robolectric cannot share a JVM, so the fuzz targets run in their own Gradle invocation.
    gradle :shared:testDebugUnitTest --tests 'app.lensly.fuzz.*' --fail-fast -PfuzzLane
}

# Lint analyses the main sources of each module; the unit-test, androidTest and test-fixture analyses are left out. Lint still
# compiles each module and the modules it depends on, because it analyses the compiled classes.
run_lint() {
    gradle :app:lintReportDebug :lumio:lintReportDebug :shared:lintReportDebug \
        -x :app:lintAnalyzeDebugUnitTest -x :app:lintAnalyzeDebugAndroidTest \
        -x :lumio:lintAnalyzeDebugUnitTest -x :lumio:lintAnalyzeDebugAndroidTest \
        -x :shared:lintAnalyzeDebugUnitTest -x :shared:lintAnalyzeDebugAndroidTest -x :shared:lintAnalyzeDebugTestFixtures
}

run_apk() {
    gradle :lumio:assembleDebug
}

run_android_tests() {
    gradle :app:compileDebugAndroidTestKotlin :app:compileBenchmarkKotlin :app:compileBenchmarkAndroidTestKotlin \
        :lumio:compileDebugAndroidTestKotlin :lumio:compileBenchmarkKotlin
}

case "$step" in
    resolve) run_resolve ;;
    static) run_static ;;
    manifest) run_manifest ;;
    compile) run_compile ;;
    unit) run_unit ;;
    fuzz) run_fuzz ;;
    lint) run_lint ;;
    apk) run_apk ;;
    android-tests) run_android_tests ;;
    *)
        echo "run-step: unknown step: $step" >&2
        usage
        exit 2
        ;;
esac
