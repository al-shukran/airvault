#!/usr/bin/env bash
# Reads the repository's active Actions cache size against an 8 GB guard, below GitHub's 10 GB limit, which evicts the least
# recently used entries first. `error` fails the step (master, after cache-prune). `warning` only reports (pull requests).
set -euo pipefail

level="${1:?usage: check-cache-usage.sh error|warning}"
limit=8000000000
bytes="$(gh api "repos/{owner}/{repo}/actions/cache/usage" --jq .active_caches_size_in_bytes)"
echo "check-cache-usage: the repository's caches hold $bytes bytes (guard $limit)"
if [ "$bytes" -gt "$limit" ]; then
    echo "::$level::check-cache-usage: the caches hold $bytes bytes, over the 8 GB guard (scripts/ci/README.md)"
    if [ "$level" = error ]; then
        exit 1
    fi
fi
