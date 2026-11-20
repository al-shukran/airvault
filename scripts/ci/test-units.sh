#!/usr/bin/env bash
# Prints "<module> <class> <file>" for every test unit of the repository, sorted. A unit is a top-level class or object of a file
# under app/src/test/java, shared/src/test/java or lumio/src/test/java that holds a JUnit test annotation (@Test,
# @ParameterizedTest, @RepeatedTest, @TestFactory or @FuzzTest). The file is its path from the root. Every module with a src/test
# set under core, chains, abi or tools is one unit: its class is `*` and its file is `-`, because its test task runs whole.
# check-partitions.sh and run-partition.sh read this list.
#
# Usage: scripts/ci/test-units.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
export LC_ALL=C
test_annotation='@(Test|FuzzTest|ParameterizedTest|RepeatedTest|TestFactory)'

# Prints "<module> <class> <file>" for every test class under the source directory $2 of the Gradle module $1.
test_classes() {
  local module=$1 dir=$2 file pkg
  [ -d "$dir" ] || return 0
  while IFS= read -r file; do
    grep -q -E "$test_annotation" "$file" || continue
    pkg=$(sed -n 's/^package \([A-Za-z0-9_.]*\).*/\1/p' "$file" | head -n 1)
    { grep -E '^([^[:space:]/*].*)?(class|object) [A-Za-z_]' "$file" || true; } \
      | sed -E 's/.*(class|object) ([A-Za-z_][A-Za-z0-9_]*).*/\2/' \
      | while IFS= read -r name; do
          printf '%s %s%s %s\n' "$module" "${pkg:+$pkg.}" "$name" "$file"
        done
  done < <(find "$dir" -type f \( -name '*.kt' -o -name '*.java' \) | sort)
}

{
  for module in app shared lumio; do
    test_classes ":$module" "$module/src/test/java"
  done
  # A pure JVM module is one unit, because its test task runs whole.
  while IFS= read -r test_dir; do
    module_path=${test_dir%/src/test}
    printf ':%s * -\n' "$(printf '%s' "$module_path" | tr '/' ':')"
  done < <(find core chains abi tools -type d -path '*/src/test' -not -path '*/build/*' | sort)
} | sort -u
