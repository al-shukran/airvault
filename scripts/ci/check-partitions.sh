#!/usr/bin/env bash
# Guard for the automatic test sharding (guideline 2.2). The shards read every test class, so a class cannot be left
# out of the PR lane by a missing list entry. This guard fails on the ways a test still escapes the shards:
#   - a @Test under app, shared or lumio outside src/test/java and src/androidTest (instrumented tests run nightly),
#     because no shard reads that directory;
#   - a test file in src/test/java where no class declaration is recognised, so shards.sh cannot name its class;
#   - two test classes with the same fully qualified name.
#
# Usage: scripts/ci/check-partitions.sh
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"
# shellcheck source=scripts/ci/shards.sh
. "$ROOT/scripts/ci/shards.sh"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
status=0

misplaced=$({ grep -rl --include='*.kt' --include='*.java' '@Test' app/src shared/src lumio/src 2>/dev/null || true; } \
    | grep -v -E '^(app|shared|lumio)/src/(test/java|androidTest)/' || true)
if [ -n "$misplaced" ]; then
    echo "check-partitions: @Test outside src/test/java and src/androidTest; no PR shard reads it:" >&2
    printf '  %s\n' $misplaced >&2
    status=1
fi

: > "$tmp/test-files"
: > "$tmp/declared-files"
: > "$tmp/names"
for module in shared app lumio; do
    { grep -rl --include='*.kt' --include='*.java' '@Test' "$module/src/test/java" 2>/dev/null || true; } >> "$tmp/test-files"
    test_classes "$module" > "$tmp/classes-$module"
    cut -f1 "$tmp/classes-$module" >> "$tmp/declared-files"
    cut -f2 "$tmp/classes-$module" >> "$tmp/names"
done

LC_ALL=C sort -u "$tmp/test-files" > "$tmp/test-files-sorted"
LC_ALL=C sort -u "$tmp/declared-files" > "$tmp/declared-sorted"
undeclared=$(LC_ALL=C comm -23 "$tmp/test-files-sorted" "$tmp/declared-sorted" || true)
if [ -n "$undeclared" ]; then
    echo "check-partitions: test files with no recognised class declaration (shards.sh cannot name them):" >&2
    printf '  %s\n' $undeclared >&2
    status=1
fi

duplicates=$(LC_ALL=C sort "$tmp/names" | uniq -d || true)
if [ -n "$duplicates" ]; then
    echo "check-partitions: test classes declared twice:" >&2
    printf '  %s\n' $duplicates >&2
    status=1
fi

if [ "$status" -ne 0 ]; then
    exit 1
fi
echo "check-partitions: $(wc -l < "$tmp/test-files-sorted" | tr -d ' ') test file(s) under src/test/java, each declares a class the shards can name"
