#!/usr/bin/env bash
# Deletes the caches a push to master supersedes. pr-fast.yml runs it in `cache-prune`, after every lane has saved.
# Kept: the build caches this push saved ($GITHUB_SHA) and the dependency cache of this tree ($DEPS_KEY).
# Deleted: the other master build caches (bc-*) and dependency caches (deps-v3-*), and every setup-gradle cache (gradle-*),
# which no pull request lane restores. Pull request caches are not listed: cache-cleanup.yml deletes them when the pull
# request closes. --dry-run prints what would be deleted.
set -euo pipefail

sha="${GITHUB_SHA:?GITHUB_SHA is not set}"
deps_key="${DEPS_KEY:?DEPS_KEY is not set}"
dry_run=0
if [ "${1:-}" = "--dry-run" ]; then
    dry_run=1
fi

doomed="$(gh cache list --ref refs/heads/master --limit 200 --json id,key |
    jq -r --arg sha "$sha" --arg deps "$deps_key" '
        .[]
        | select(
            (.key | (startswith("bc-") and (endswith("-" + $sha) | not)))
            or (.key | (startswith("deps-v3-") and (. != $deps)))
            or (.key | startswith("gradle-"))
        )
        | "\(.id)\t\(.key)"')"

if [ -z "$doomed" ]; then
    echo "prune-caches: nothing to delete"
    exit 0
fi

while IFS=$'\t' read -r id key; do
    if [ "$dry_run" -eq 1 ]; then
        echo "would delete $id $key"
    elif gh cache delete "$id"; then
        echo "deleted $id $key"
    else
        echo "::warning::prune-caches: could not delete $id ($key)"
    fi
done <<< "$doomed"
