#!/usr/bin/env bash
# Runs one PR partition (docs/testing/guideline.md 2.2). Each partition is one Gradle invocation.
#
#   shared-N, app-N, lumio-N   the unit tests of shard N of the module. shards.sh decides the classes; the
#                              shard's classes go to one --tests filter each, so a new test class needs no edit here
#   fuzz                       the Jazzer seeds, in their own JVM without Robolectric (-PfuzzLane)
#   lint                       :lumio:lintDebug (its own job; the workflow runs it for shared and lumio changes)
#   lumio-apk                  :lumio:assembleDebug (no tests: dexing takes most of the time)
#   registry-pack              :tools:registry-pack:test
#   manifest                   check-manifest.sh (the workflow adds the release-manifest checks around it)
#   ci                         bash -n on every script, then check-partitions.sh (no Gradle)
#
# The plan of partitions for a change is scripts/ci/plan-partitions.sh.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"
# shellcheck source=scripts/ci/shards.sh
. "$ROOT/scripts/ci/shards.sh"

usage() {
    cat >&2 <<'USAGE_END'
usage: scripts/ci/run-partition.sh <partition>

partitions:
  shared-1 .. shared-4   :shared:testDebugUnitTest -PprLane, the classes of that shard
  app-1 .. app-2         :app:testDebugUnitTest -PprLane, the classes of that shard
  lumio-1 .. lumio-4     :lumio:testDebugUnitTest, the classes of that shard
  fuzz                   :shared:testDebugUnitTest -PfuzzLane --tests 'app.lensly.fuzz.*'
  lint                   :lumio:lintDebug
  lumio-apk              :lumio:assembleDebug
  registry-pack          :tools:registry-pack:test
  manifest               scripts/ci/check-manifest.sh
  ci                     bash -n on every script, then scripts/ci/check-partitions.sh
USAGE_END
}

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi

partition="$1"

case "$partition" in
    shared-[0-9]* | app-[0-9]* | lumio-[0-9]*)
        module=${partition%-*}
        index=${partition##*-}
        count=$(shard_count "$module")
        if [ "$index" -lt 1 ] || [ "$index" -gt "$count" ]; then
            echo "run-partition: $partition is outside 1..$count" >&2
            exit 2
        fi
        tests=()
        while IFS= read -r fqcn; do
            tests+=(--tests "$fqcn")
        done < <(shard_classes "$module" "$index" "$count")
        if [ "${#tests[@]}" -eq 0 ]; then
            echo "run-partition: $partition holds no test class" >&2
            exit 1
        fi
        echo "run-partition: $partition runs $(( ${#tests[@]} / 2 )) test class(es) in :$module"
        if [ "$module" = lumio ]; then
            ./gradlew ":$module:testDebugUnitTest" "${tests[@]}"
        else
            ./gradlew ":$module:testDebugUnitTest" -PprLane "${tests[@]}"
        fi
        ;;
    fuzz)
        # The fuzz targets replay their committed seeds in their own JVM. Jazzer and Robolectric cannot share one
        # (see the -PfuzzLane note in shared/build.gradle.kts).
        ./gradlew :shared:testDebugUnitTest -PfuzzLane --tests 'app.lensly.fuzz.*'
        ;;
    lint)
        ./gradlew :lumio:lintDebug
        ;;
    lumio-apk)
        ./gradlew :lumio:assembleDebug
        ;;
    registry-pack)
        ./gradlew :tools:registry-pack:test
        ;;
    manifest)
        exec "$ROOT/scripts/ci/check-manifest.sh"
        ;;
    ci)
        scripts=0
        while IFS= read -r script; do
            bash -n "$script"
            scripts=$((scripts + 1))
        done < <(find "$ROOT/scripts" -type f -name '*.sh' | LC_ALL=C sort)
        echo "run-partition: bash -n passed for $scripts script(s)"
        exec "$ROOT/scripts/ci/check-partitions.sh"
        ;;
    *)
        echo "run-partition: unknown partition: $partition" >&2
        usage
        exit 2
        ;;
esac
