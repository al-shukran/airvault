#!/usr/bin/env bash
# Checks scripts/ci/shards.txt against the test units (scripts/ci/test-units.sh). Fails when a line is malformed, a class is listed
# twice, or a listed class no longer exists. A test class that no line lists is not an error: run-partition.sh runs it in its
# default lane, so a new test needs no line in shards.txt.
#
# Usage: scripts/ci/check-partitions.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

lanes_file=scripts/ci/shards.txt
export LC_ALL=C

if [ ! -f "$lanes_file" ]; then
  echo "check-partitions: $lanes_file is missing" >&2
  exit 1
fi

listed=$(mktemp)
trap 'rm -f "$listed"' EXIT

# Lanes: "<lane> <gradle module> <class>". A lane is one of the shard lanes, verify-app, verify-lumio or fuzz.
line_no=0
bad=""
while IFS= read -r line; do
  line_no=$((line_no + 1))
  case "$line" in
    '' | \#*) continue ;;
  esac
  # read, not word splitting: the pure-module lines end in `*`, which an unquoted expansion would glob.
  read -r lane_name module_name class_name extra <<< "$line"
  if [ -z "$class_name" ] || [ -n "$extra" ]; then
    bad="$bad  $lanes_file:$line_no: expected '<lane> <module> <class>'
"
    continue
  fi
  case "$lane_name" in
    shard-[1-6] | verify-app | verify-lumio | fuzz) ;;
    *) bad="$bad  $lanes_file:$line_no: unknown lane '$lane_name'
" ;;
  esac
  case "$module_name" in
    :*) ;;
    *) bad="$bad  $lanes_file:$line_no: the module must be a Gradle path such as :app
" ;;
  esac
  printf '%s %s\n' "$module_name" "$class_name" >> "$listed"
done < "$lanes_file"

if [ -n "$bad" ]; then
  echo "check-partitions: malformed lines in $lanes_file." >&2
  printf '%s' "$bad" >&2
  exit 1
fi

known=$(scripts/ci/test-units.sh | cut -d' ' -f1,2 | sort -u)
listed_sorted=$(sort "$listed")

stale=$(comm -13 <(printf '%s\n' "$known") <(printf '%s\n' "$listed_sorted" | sort -u))
doubled=$(printf '%s\n' "$listed_sorted" | uniq -d)
unlisted=$(comm -23 <(printf '%s\n' "$known") <(printf '%s\n' "$listed_sorted" | sort -u) | grep -c . || true)

status=0
if [ -n "$doubled" ]; then
  echo "check-partitions: test classes listed in more than one lane of $lanes_file. Each class must be in exactly one lane:" >&2
  printf '%s\n' "$doubled" | sed 's/^/  /' >&2
  status=1
fi
if [ -n "$stale" ]; then
  echo "check-partitions: $lanes_file lists classes that no longer exist. Remove or rename them:" >&2
  printf '%s\n' "$stale" | sed 's/^/  /' >&2
  status=1
fi
if [ "$status" -ne 0 ]; then
  exit 1
fi

units=$(printf '%s\n' "$known" | grep -c . || true)
echo "check-partitions: $units test units, $unlisted of them not listed in $lanes_file (they run in their default lane)"
