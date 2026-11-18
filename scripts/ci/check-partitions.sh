#!/usr/bin/env bash
# Fails when a test class is in no PR partition, or in more than one (guideline 2.2).
#
# Every class with an @Test under app/src/test/java, shared/src/test/java and lumio/src/test/java must match a --tests
# selector in exactly one arm of scripts/ci/run-partition.sh, and that selector must sit in the Gradle call for the
# module that holds the class. A package can have classes in two modules (app.lensly.ui.navigation has one in :app and
# one in :shared), and a selector runs only in its own module, so matching on the package name alone is not enough.
# tools/registry-pack is not checked: its partition runs the whole module without a filter.
#
# Usage: scripts/ci/check-partitions.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

partition_script=scripts/ci/run-partition.sh

# One line per selector: "module selector arm". An arm runs from its "    name)" line to the line holding ";;". Inside an arm,
# a ":module:task" token sets the module for the --tests selectors after it. Comment lines are skipped.
selectors=$(awk '
  /^    [a-z][a-z0-9-]*\)$/ { arm = $1; sub(/\)$/, "", arm); inarm = 1; mod = ""; next }
  inarm && $1 !~ /^#/ {
    for (i = 1; i <= NF; i++) {
      if ($i ~ /^:(app|shared|lumio):/) { split($i, t, ":"); mod = t[2] }
      else if ($i == "--tests" && mod != "" && i < NF) print mod, $(i + 1), arm
    }
    if ($0 ~ /;;/) inarm = 0
  }
' "$partition_script")

if [ -z "$selectors" ]; then
  echo "check-partitions: no --tests selector found in $partition_script" >&2
  exit 1
fi

# One line per test class: "module fully.qualified.Name".
classes=""
for module in app shared lumio; do
  while IFS= read -r file; do
    grep -q '@Test' "$file" || continue
    pkg=$(sed -n 's/^package \([A-Za-z0-9_.]*\).*/\1/p' "$file")
    cls=$(basename "$file")
    classes="$classes$module $pkg.${cls%.*}
"
  done < <(find "$module/src/test/java" -type f \( -name '*.kt' -o -name '*.java' \) | sort)
done

seen=0
missing=""
doubled=""
while IFS=' ' read -r module fqn; do
  [ -n "$module" ] || continue
  seen=$((seen + 1))
  arms=""
  while IFS=' ' read -r smod sel sarm; do
    [ "$smod" = "$module" ] || continue
    sel=${sel//\'/}
    if [ "$sel" = "$fqn" ] || { [[ $sel == *'.*' ]] && [[ $fqn == "${sel%\*}"* ]]; }; then
      case " $arms " in
        *" $sarm "*) ;;
        *) arms="$arms $sarm" ;;
      esac
    fi
  done <<<"$selectors"
  set -- $arms
  case $# in
    0) missing="$missing  :$module $fqn
" ;;
    1) ;;
    *) doubled="$doubled  :$module $fqn in$arms
" ;;
  esac
done <<<"$classes"

if [ "$seen" -eq 0 ]; then
  echo "check-partitions: no test class found under app, shared or lumio" >&2
  exit 1
fi

if [ -n "$missing" ]; then
  echo "check-partitions: test classes in no partition arm of $partition_script." >&2
  echo "Add a --tests selector for each to one arm, in the Gradle call for its module:" >&2
  printf '%s' "$missing" >&2
fi
if [ -n "$doubled" ]; then
  echo "check-partitions: test classes in more than one partition arm of $partition_script." >&2
  echo "Each class must be in exactly one arm:" >&2
  printf '%s' "$doubled" >&2
fi
if [ -n "$missing$doubled" ]; then
  exit 1
fi

echo "check-partitions: $seen test classes, each in exactly one partition arm"
