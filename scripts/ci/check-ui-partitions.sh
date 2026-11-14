#!/usr/bin/env bash
# Fails when a ui test package is in neither PR partition, ui-a or ui-b (guideline 2.2).
#
# Every subpackage of app/src/test/java/app/lensly/ui/ and shared/src/test/java/app/lensly/ui/, and every
# root-level test class in those directories (a file with @Test that sits directly in app.lensly.ui), must
# have a --tests selector inside the ui-a or ui-b arm of scripts/ci/run-partition.sh. A missing one would run
# in no partition at all, so its tests would never run on a PR.
#
# Usage: scripts/ci/check-ui-partitions.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

ui_dirs=(app/src/test/java/app/lensly/ui shared/src/test/java/app/lensly/ui)
partition_script=scripts/ci/run-partition.sh

# Only the ui-a and ui-b arms count, so the usage text cannot satisfy the check.
arms=$(awk '
  /^    ui-(a|b)\)$/ { inarm = 1; next }
  inarm && /;;/ { inarm = 0 }
  inarm { print }
' "$partition_script")

if [ -z "$arms" ]; then
  echo "check-ui-partitions: no ui-a or ui-b arm found in $partition_script" >&2
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

for ui_dir in "${ui_dirs[@]}"; do
  for dir in "$ui_dir"/*/; do
    [ -d "$dir" ] || continue
    check "app.lensly.ui.$(basename "$dir").*"
  done

  for file in "$ui_dir"/*.kt; do
    [ -f "$file" ] || continue
    if grep -q '@Test' "$file"; then
      check "app.lensly.ui.$(basename "$file" .kt)"
    fi
  done
done

if [ "${#missing[@]}" -gt 0 ]; then
  echo "check-ui-partitions: not covered by ui-a or ui-b in $partition_script:" >&2
  for selector in "${missing[@]}"; do
    echo "  --tests '$selector'" >&2
  done
  echo "Add each one to exactly one of the ui-a and ui-b arms." >&2
  exit 1
fi

echo "check-ui-partitions: $covered ui selector(s) covered by ui-a or ui-b"
