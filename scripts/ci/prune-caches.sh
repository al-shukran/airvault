#!/usr/bin/env bash
# Deletes superseded master caches. pr-fast.yml runs it in `cache-prune`, after every lane of a push to master has saved.
# A cache's group is its key without the trailing commit or content hash (bs-shard-1-master, deps-v3-Linux, a setup-gradle
# entry of a nightly job). Each group keeps its newest entry, so the build state of every lane, the dependency cache and the
# setup-gradle caches that nightly and other workflows restore survive. An entry created after this run started is never
# deleted: two pushes close together each keep the other's fresh entries. Legacy build caches (bc-*) are deleted.
# Pull request caches are not listed: cache-cleanup.yml deletes them when the pull request closes. --dry-run prints only.
set -euo pipefail

run_id="${GITHUB_RUN_ID:?GITHUB_RUN_ID is not set}"
started="$(gh api "repos/{owner}/{repo}/actions/runs/$run_id" --jq .run_started_at)"
dry_run=0
if [ "${1:-}" = "--dry-run" ]; then
    dry_run=1
fi

# Every page: one push saves about 14 entries, and a fixed --limit hid older ones.
doomed="$(gh api --paginate "repos/{owner}/{repo}/actions/caches?ref=refs/heads/master&per_page=100" \
    --jq '.actions_caches[] | [.id, .key, .created_at] | @tsv' |
    jq -R '
        split("\t") | {id: .[0], key: .[1], created: .[2]}
        | .group = (.key | sub("-[0-9a-f]{8,}$"; ""))' |
    jq -s -r --arg started "$started" '
        group_by(.group)
        | map(sort_by(.created) | reverse | to_entries[]
              | select(.value.created < $started)
              | select(.key > 0 or (.value.key | startswith("bc-")))
              | .value)
        | .[] | "\(.id)\t\(.key)"')"

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
