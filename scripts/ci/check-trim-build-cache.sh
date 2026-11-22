#!/usr/bin/env bash
# Checks the used-key match of trim-build-cache.sh against fixture logs in the format Gradle prints with
# org.gradle.caching.debug=true (scripts/ci/README.md). Each case runs a copy of the script in a temporary tree, so no real
# build cache is read or trimmed.
#
# Usage: scripts/ci/check-trim-build-cache.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
failures=0

fail() {
  echo "check-trim-build-cache: $1" >&2
  failures=$((failures + 1))
}

# new_tree <name>: a tree with a copy of the script, a one-lane budget file and an empty cache. The start file is dated 2020, so
# an entry touched now is newer than it, and an entry dated 2019 is not.
new_tree() {
  local dir="$work/$1"
  mkdir -p "$dir/scripts/ci" "$dir/.gradle" "$dir/gradle-home/caches/build-cache-1"
  cp "$root/scripts/ci/trim-build-cache.sh" "$dir/scripts/ci/"
  echo "fixture 100" > "$dir/scripts/ci/build-cache-budgets.txt"
  touch -t 202001010000 "$dir/.gradle/build-cache-start"
  printf '%s\n' "$dir"
}

# entry <tree> <name> <old|new>: a cache entry dated before the start (old) or after it (new)
entry() {
  local file="$1/gradle-home/caches/build-cache-1/$2"
  : > "$file"
  if [ "$3" = old ]; then touch -t 201912310000 "$file"; fi
}

# present <tree> <name> <yes|no>: whether the cache still holds the entry
present() {
  local path="$1/gradle-home/caches/build-cache-1/$2"
  if [ "$3" = yes ] && [ ! -e "$path" ]; then fail "$(basename "$1"): $2 was removed, expected kept"; fi
  if [ "$3" = no ] && [ -e "$path" ]; then fail "$(basename "$1"): $2 was kept, expected removed"; fi
}

# run_trim <tree>: runs the trim for lane fixture on the tree; sets rc and out
run_trim() {
  if out=$(GRADLE_USER_HOME="$1/gradle-home" bash "$1/scripts/ci/trim-build-cache.sh" fixture 2>&1); then
    rc=0
  else
    rc=$?
  fi
}

# Keys in the forms the regex sees: a task and a transform with lowercase keys, plus lines it must not match (uppercase, and a key
# one character too long). An entry named only by a non-matching line is removed; a new entry is kept whatever the log says.
t=$(new_tree keys)
printf '%s\n' \
  "Gradle is starting the build" \
  "Build cache key for task ':app:compileDebugKotlin' is 0123456789abcdef0123456789abcdef" \
  "Build cache key for transform 'lumio-classes.jar' is fedcba9876543210fedcba9876543210" \
  "Build cache key for task ':shared:testDebugUnitTest' is 0123456789abcdef0123456789abcdef" \
  "Build cache key for task ':app:upper' is BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB" \
  "Build cache key for task ':app:long' is aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa0" \
  > "$t/.gradle/build-cache-keys.log"
entry "$t" 0123456789abcdef0123456789abcdef old
entry "$t" fedcba9876543210fedcba9876543210 old
entry "$t" bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb old
entry "$t" aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa old
entry "$t" cccccccccccccccccccccccccccccccc old
entry "$t" dddddddddddddddddddddddddddddddd new
run_trim "$t"
[ "$rc" = 0 ] || fail "keys: trim exited $rc on a log with keys: $out"
present "$t" 0123456789abcdef0123456789abcdef yes
present "$t" fedcba9876543210fedcba9876543210 yes
present "$t" dddddddddddddddddddddddddddddddd yes
present "$t" bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb no
present "$t" aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa no
present "$t" cccccccccccccccccccccccccccccccc no

# A log with no key must fail before any entry is removed, so the save never stores a trim made without the keys.
t=$(new_tree nokey)
printf '%s\n' "Gradle is starting the build" "BUILD SUCCESSFUL" > "$t/.gradle/build-cache-keys.log"
entry "$t" eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee old
run_trim "$t"
[ "$rc" != 0 ] || fail "nokey: trim exited 0 on a log with no key"
case "$out" in *"names no build-cache key"*) ;; *) fail "nokey: no error names the missing key: $out" ;; esac
present "$t" eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee yes

# A missing log fails the same way.
t=$(new_tree nolog)
entry "$t" ffffffffffffffffffffffffffffffff old
run_trim "$t"
[ "$rc" != 0 ] || fail "nolog: trim exited 0 with no log"
case "$out" in *"is missing or empty"*) ;; *) fail "nolog: no error names the missing log: $out" ;; esac
present "$t" ffffffffffffffffffffffffffffffff yes

if [ "$failures" -ne 0 ]; then
  echo "check-trim-build-cache: $failures check(s) failed" >&2
  exit 1
fi
echo "check-trim-build-cache: the key match holds on every fixture log"
