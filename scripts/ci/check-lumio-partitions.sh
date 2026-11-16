#!/usr/bin/env bash
# Fails when a lumio test package is in neither PR partition, lumio-a or lumio-b (guideline 2.2).
#
# Every subpackage of lumio/src/test/java/app/lumio/, and every root-level test class in that directory (a file with @Test that sits
# directly in app.lumio), must have a --tests selector inside the lumio-a or lumio-b arm of scripts/ci/run-partition.sh. A missing one
# would run in no partition at all, so its tests would never run on a PR.
#
# Usage: scripts/ci/check-lumio-partitions.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

test_dir=lumio/src/test/java/app/lumio
partition_script=scripts/ci/run-partition.sh

# Only the lumio-a and lumio-b arms count, so the usage text cannot satisfy the check.
arms=$(awk '
  /^    lumio-(a|b)\)$/ { inarm = 1; next }
  inarm && /;;/ { inarm = 0 }
  inarm { print }
' "$partition_script")

if [ -z "$arms" ]; then
  echo "check-lumio-partitions: no lumio-a or lumio-b arm found in $partition_script" >&2
  exit 1
fi

covered=0
missing=()

check() {
  local selector=$1
  if grep -qF -- "--tests '$selector'" <<<"$arms"; then
    covered=$((covered + 1))
  else
    missing+=("$selector")
  fi
}

for dir in "$test_dir"/*/; do
  [ -d "$dir" ] || continue
  check "app.lumio.$(basename "$dir").*"
done

for file in "$test_dir"/*.kt; do
  [ -f "$file" ] || continue
  if grep -q '@Test' "$file"; then
    check "app.lumio.$(basename "$file" .kt)"
  fi
done

if [ "${#missing[@]}" -gt 0 ]; then
  echo "check-lumio-partitions: not covered by lumio-a or lumio-b in $partition_script:" >&2
  for selector in "${missing[@]}"; do
    echo "  --tests '$selector'" >&2
  done
  echo "Add each one to exactly one of the lumio-a and lumio-b arms." >&2
  exit 1
fi

echo "check-lumio-partitions: $covered lumio selector(s) covered by lumio-a or lumio-b"
