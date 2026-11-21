#!/usr/bin/env bash
# Trims the Gradle build cache to the entries this job used, before pr-fast.yml saves it, so each saved lane entry is one run's
# working set. Gradle does not mark an entry it loads (the file keeps the time it was stored), so the keys come from the debug log:
# run-partition.sh runs the build-cache lanes' Gradle calls with org.gradle.caching.debug=true, which prints "Build cache key for ...
# is <key>" for each cacheable task and transform. Files the job created are kept too, since Gradle stores a few entries without one.
# A lane above its budget exits 1 before the save, which the workflow then skips. A pull request only reports the size.
set -euo pipefail

lane="${1:?usage: trim-build-cache.sh <lane>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
state="$ROOT/.gradle"
log="$state/build-cache-keys.log"
start="$state/build-cache-start"
cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/build-cache-1"

# The lane's budget in MB (build-cache-budgets.txt).
budget_mb="$(awk -v lane="$lane" '$1 == lane { print $2 }' "$ROOT/scripts/ci/build-cache-budgets.txt")"
[ -n "$budget_mb" ] || { echo "::error::trim-build-cache: no budget for lane $lane in build-cache-budgets.txt" >&2; exit 2; }

[ -s "$log" ] || { echo "::error::trim-build-cache: $log is missing or empty, so the entries this job used are unknown" >&2; exit 1; }
[ -e "$start" ] || { echo "::error::trim-build-cache: $start is missing, so the entries this job created are unknown" >&2; exit 1; }
used="$(grep -oE 'Build cache key for .* is [0-9a-f]{32}$' "$log" | awk '{ print $NF }' | sort -u || true)"
[ -n "$used" ] || { echo "::error::trim-build-cache: the Gradle log names no build-cache key" >&2; exit 1; }

kept=0
removed=0
for entry in "$cache"/*; do
    name="${entry##*/}"
    [[ "$name" =~ ^[0-9a-f]{32}$ && -f "$entry" ]] || continue
    if [ "$entry" -nt "$start" ] || grep -qx "$name" <<< "$used"; then
        kept=$((kept + 1))
    else
        rm -f "$entry"
        removed=$((removed + 1))
    fi
done

kb="$(du -sk "$cache" "$state/configuration-cache" 2>/dev/null | awk '{ sum += $1 } END { print sum + 0 }')" || true
mb=$(((kb + 1023) / 1024))
echo "trim-build-cache: $lane keeps $kept entries, removes $removed, and holds $mb MB (budget $budget_mb MB)"
if [ "$mb" -gt "$budget_mb" ]; then
    level=error
    [ "${GITHUB_EVENT_NAME:-}" != pull_request ] || level=warning
    echo "::$level::trim-build-cache: $lane holds $mb MB, over its $budget_mb MB budget (scripts/ci/README.md)"
    [ "$level" = warning ] || exit 1
fi
