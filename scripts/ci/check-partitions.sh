#!/usr/bin/env bash
# Fails unless every test class is listed in exactly one lane of scripts/ci/shards.txt, and every listed class exists.
#
# A test class is a top-level class or object of a file under app/src/test/java, shared/src/test/java or lumio/src/test/java
# that holds a JUnit test annotation (@Test, @ParameterizedTest, @RepeatedTest, @TestFactory or @FuzzTest). A pure JVM module
# with tests (core, chains, abi, tools) is listed once, as its whole module (class `*`).
# tools/registry-pack is covered the same way.
#
# Usage: scripts/ci/check-partitions.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

lanes_file=scripts/ci/shards.txt
test_annotation='@(Test|FuzzTest|ParameterizedTest|RepeatedTest|TestFactory)'
export LC_ALL=C

if [ ! -f "$lanes_file" ]; then
  echo "check-partitions: $lanes_file is missing" >&2
  exit 1
fi

# Prints "<module> <class>" for every test class under the source directory $2 of the Gradle module $1.
test_classes() {
  local module=$1 dir=$2 file pkg
  [ -d "$dir" ] || return 0
  while IFS= read -r file; do
    grep -q -E "$test_annotation" "$file" || continue
    pkg=$(sed -n 's/^package \([A-Za-z0-9_.]*\).*/\1/p' "$file" | head -n 1)
    { grep -E '^([^[:space:]/*].*)?(class|object) [A-Za-z_]' "$file" || true; } \
      | sed -E 's/.*(class|object) ([A-Za-z_][A-Za-z0-9_]*).*/\2/' \
      | while IFS= read -r name; do
          printf '%s %s%s\n' "$module" "${pkg:+$pkg.}" "$name"
        done
  done < <(find "$dir" -type f \( -name '*.kt' -o -name '*.java' \) | sort)
}

known=$(mktemp)
trap 'rm -f "$known" "$listed"' EXIT
listed=$(mktemp)

for module in app shared lumio; do
  test_classes ":$module" "$module/src/test/java" >> "$known"
done

# Pure JVM modules: every module with a src/test set is one whole-module unit. Its :module:test task runs whole, so each one
# must be listed, or a module added later would run nowhere.
pure_count=0
while IFS= read -r test_dir; do
  module_path=${test_dir%/src/test}
  module=":$(printf '%s' "$module_path" | tr '/' ':')"
  printf '%s *\n' "$module" >> "$known"
  pure_count=$((pure_count + 1))
done < <(find core chains abi tools -type d -path '*/src/test' -not -path '*/build/*' | sort)

# Lanes: "<lane> <module> <class>". A lane is one of the shard lanes, verify or fuzz.
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
    shard-[1-6] | verify | fuzz) ;;
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

known_sorted=$(sort -u "$known")
listed_sorted=$(sort "$listed")

missing=$(comm -23 <(printf '%s\n' "$known_sorted") <(printf '%s\n' "$listed_sorted" | sort -u))
stale=$(comm -13 <(printf '%s\n' "$known_sorted") <(printf '%s\n' "$listed_sorted" | sort -u))
doubled=$(printf '%s\n' "$listed_sorted" | uniq -d)

status=0
if [ -n "$missing" ]; then
  echo "check-partitions: test classes in no lane of $lanes_file. Add each to one lane (shard-1..shard-6 or verify for a Roborazzi pixel test):" >&2
  printf '%s\n' "$missing" | sed 's/^/  /' >&2
  status=1
fi
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

units=$(printf '%s\n' "$known_sorted" | grep -c . || true)
classes=$((units - pure_count))
echo "check-partitions: $classes test classes and $pure_count pure JVM modules, each in exactly one lane"
