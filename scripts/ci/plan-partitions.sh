#!/usr/bin/env bash
# Plans the PR partitions (docs/testing/guideline.md 2.2) for the `changes` job of pr-fast.yml. Prints the matrix of the
# `test` job as a JSON array, and whether the `lint` job runs. Environment, set by the workflow:
#   EVENT                           the GitHub event name; anything but pull_request runs every partition
#   SHARED APP LUMIO REGISTRY MANIFEST BUILD CI_CHANGED
#                                   "true" or "false" per path filter (dorny/paths-filter; empty on push)
# BUILD (Gradle build logic) and CI (scripts/ci, the PR workflows) run every partition: either can change any test.
# SHARED covers :shared and the core and chains modules it is built from, so it reaches every Gradle test module.
# :app and :lumio both depend on :shared, so a shared change runs their shards too.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
# shellcheck source=scripts/ci/shards.sh
. "$ROOT/scripts/ci/shards.sh"

all=false
if [ "${EVENT:-}" != pull_request ] || [ "${BUILD:-}" = true ] || [ "${CI_CHANGED:-}" = true ]; then
    all=true
fi

# wants <FLAG>: true when every partition runs or the path filter of that name matched.
wants() {
    [ "$all" = true ] || [ "${!1:-}" = true ]
}

partitions=()
want_shared=false; wants SHARED && want_shared=true
want_app=false; { wants APP || [ "$want_shared" = true ]; } && want_app=true
want_lumio=false; { wants LUMIO || [ "$want_shared" = true ]; } && want_lumio=true
want_manifest=false; wants MANIFEST && want_manifest=true
want_registry=false; wants REGISTRY && want_registry=true

for module in shared app lumio; do
    case "$module" in
        shared) want=$want_shared ;;
        app) want=$want_app ;;
        lumio) want=$want_lumio ;;
    esac
    [ "$want" = true ] || continue
    count=$(shard_count "$module")
    index=1
    while [ "$index" -le "$count" ]; do
        if [ -n "$(shard_classes "$module" "$index" "$count" | head -n 1)" ]; then
            partitions+=("$module-$index")
        fi
        index=$((index + 1))
    done
done
[ "$want_shared" = true ] && partitions+=(fuzz)
[ "$want_lumio" = true ] && partitions+=(lumio-apk)
[ "$want_manifest" = true ] && partitions+=(manifest)
[ "$want_registry" = true ] && partitions+=(registry-pack)
# Any test module, or the manifest or registry, runs the guard; the guard reads every test source set.
[ "${#partitions[@]}" -gt 0 ] && partitions+=(ci)

lint=$want_lumio

if [ "${#partitions[@]}" -eq 0 ]; then
    json='[]'
else
    json=$(printf '%s\n' "${partitions[@]}" | jq -R . | jq -sc .)
fi

echo "partitions=$json"
echo "lint=$lint"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
        echo "partitions=$json"
        echo "lint=$lint"
    } >> "$GITHUB_OUTPUT"
fi
