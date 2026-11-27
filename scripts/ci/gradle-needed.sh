#!/usr/bin/env bash
# Decides whether the Gradle steps of the pull request gate (ci.yml) run. Prints true or false on stdout and one log line on stderr.
#
# Usage: scripts/ci/gradle-needed.sh <base-sha>   the files changed since <base-sha> (git diff base...HEAD)
#        scripts/ci/gradle-needed.sh --files      the changed file names on stdin, one per line (the self-test uses this)
#
# Gradle runs (true) when any changed file is a build input: anything under a Gradle module directory (the first segment of each
# include() in settings.gradle.kts), any .kt, .kts or .java file, gradle/ (the version catalog, the wrapper, the dependency
# verification), gradlew and gradlew.bat, gradle.properties, a settings or build gradle file, build-logic/, buildSrc/ and licenses/
# (the third-party notices that the Gradle build and tests read). Docs,
# changelog fragments, scripts/ and .github/ are not build inputs. It also runs when the base is empty (a push to master), when the
# diff fails, or when the list is empty: any doubt runs Gradle. The result is never an error, so the gate cannot fail here.
set -uo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root" || exit 0

modules=$(sed -n 's/^include(":\([^:"]*\).*/\1/p' settings.gradle.kts | sort -u | paste -sd'|' -)
build_input="^(${modules:-app|lumio|shared})/|\.(kt|kts|java)$|^gradle/|^gradlew|^gradle\.properties$|^(settings|build)\.gradle(\.kts)?$|^(build-logic|buildSrc)/|^licenses/"

if [ "${1:-}" = "--files" ]; then
    files=$(cat)
elif [ -n "${1:-}" ]; then
    files=$(git diff --name-only --no-renames "$1...HEAD" 2>/dev/null) || {
        echo "gradle-needed: the diff against $1 failed, so Gradle runs" >&2
        echo true
        exit 0
    }
else
    echo "gradle-needed: no base commit (a push), so Gradle runs" >&2
    echo true
    exit 0
fi

if [ -z "$files" ]; then
    echo "gradle-needed: no changed files listed, so Gradle runs" >&2
    echo true
elif hit=$(grep -E -m 1 "$build_input" <<<"$files"); then
    echo "gradle-needed: $hit is a build input, so Gradle runs" >&2
    echo true
else
    echo "Gradle steps skipped: no Kotlin or Android build input changed ($(wc -l <<<"$files" | tr -d ' ') changed file(s))" >&2
    echo false
fi
