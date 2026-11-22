#!/usr/bin/env bash
# Sums the size of the repository's live Actions caches against an 8 GB guard, below GitHub's 10 GB limit, which evicts the least
# recently used entries first. The cache usage endpoint lags after deletes (issue #688), so the sum reads the paginated cache list.
# `error` fails the step (master, after cache-prune). `warning` only reports (pull requests).
set -euo pipefail

level="${1:?usage: check-cache-usage.sh error|warning}"
limit=8000000000
cache_bytes() {
    gh api --paginate "repos/{owner}/{repo}/actions/caches?per_page=100" --jq '.actions_caches[].size_in_bytes' | awk '{s+=$1} END{print s+0}'
}
# A failed read says nothing about usage (issue #727): retry, then warn and pass.
bytes=""
for attempt in 1 2 3; do
    if bytes="$(cache_bytes)"; then
        break
    fi
    bytes=""
    sleep $((attempt * 2))
done
if [ -z "$bytes" ]; then
    echo "::warning::check-cache-usage: the cache list could not be read after 3 tries, so the guard is skipped"
    exit 0
fi
echo "check-cache-usage: the repository's caches hold $bytes bytes (guard $limit)"
if [ "$bytes" -gt "$limit" ]; then
    echo "::$level::check-cache-usage: the caches hold $bytes bytes, over the 8 GB guard (scripts/ci/README.md)"
    if [ "$level" = error ]; then
        exit 1
    fi
fi
