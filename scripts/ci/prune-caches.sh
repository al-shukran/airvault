#!/usr/bin/env bash
# Deletes superseded caches. pr-fast.yml runs it in `cache-prune`, after every lane of a push to master has saved.
# On master, a cache's group is its key without the trailing commit or content hash (bc-shard-1-master, deps-v3-Linux, a
# setup-gradle entry of a nightly job). Each group keeps its newest entry, so every lane's build cache, the dependency cache and
# the setup-gradle caches that nightly and other workflows restore survive. An entry created after this run started is never
# deleted, and neither is an entry of the current master head (MASTER_SHA, MASTER_DEPS_KEY): two pushes close together, a late
# prune, or a rerun of an older push that saves again cannot delete what master's newest tree restores.
# A merge-queue entry (refs/heads/gh-readonly-queue/...) saves caches that no other run can restore, so all of them that are
# older than this run are deleted. Pull request caches are not touched: cache-cleanup.yml deletes them when the pull request
# closes. --dry-run prints only.
set -euo pipefail

run_id="${GITHUB_RUN_ID:?GITHUB_RUN_ID is not set}"
started="$(gh api "repos/{owner}/{repo}/actions/runs/$run_id" --jq .run_started_at)"
master_sha="${MASTER_SHA:?MASTER_SHA is not set}"
master_deps="${MASTER_DEPS_KEY:?MASTER_DEPS_KEY is not set}"
dry_run=0
if [ "${1:-}" = "--dry-run" ]; then
    dry_run=1
fi

# Every page: one push saves about 14 entries, and a fixed --limit hid older ones.
doomed="$(gh api --paginate "repos/{owner}/{repo}/actions/caches?per_page=100" \
    --jq '.actions_caches[]
        | select(.ref == "refs/heads/master" or (.ref | startswith("refs/heads/gh-readonly-queue/")))
        | [.id, .key, .created_at, .ref] | @tsv' |
    jq -R '
        split("\t") | {id: .[0], key: .[1], created: .[2], ref: .[3]}
        | .group = if .ref == "refs/heads/master" then (.key | sub("-[0-9a-f]{8,}$"; "")) else "queue \(.id)" end
        | .queue = (.ref != "refs/heads/master")' |
    jq -s -r --arg started "$started" --arg sha "$master_sha" --arg deps "$master_deps" '
        group_by(.group)
        | map(sort_by(.created) | reverse | to_entries[]
              | select(.value.created < $started)
              | select((.value.key | endswith("-" + $sha)) or .value.key == $deps | not)
              | select(.key > 0 or .value.queue)
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
