#!/usr/bin/env bash
# Keeps only the build-cache entries one CI run used, so a lane's saved cache does not grow with every master push (#1115).
#   prune-build-cache.sh age    after the cache restore: every entry gets an access time of 1970, and the run's start is marked
#   prune-build-cache.sh prune  after the run: deletes the entries nobody read or stored since that mark
# Gradle reads an entry it reuses and writes one it stores, so those two kinds have a newer access time than the mark. This needs
# an access time that updates on a read (Linux relatime does that for a file whose access time is not newer than its modification
# time). `age` checks it on a scratch file and, when the filesystem does not update it, leaves the entries alone: `prune` then does nothing.
set -euo pipefail

dir="$HOME/.gradle/caches/build-cache-1"
state="${RUNNER_TEMP:-/tmp}/bc-prune"
[ -d "$dir" ] || exit 0

case "${1:-}" in
    age)
        mkdir -p "$state"
        rm -f "$state/enabled"
        probe="$state/probe"
        : > "$probe"
        touch -a -d @0 "$probe"
        cat "$probe" > /dev/null
        if [ "$(stat -c %X "$probe")" -eq 0 ]; then
            echo "prune-build-cache: this filesystem does not update access times on a read, so the cache is saved unpruned"
            exit 0
        fi
        find "$dir" -type f -exec touch -a -d @0 {} +
        touch "$state/mark"
        touch "$state/enabled"
        ;;
    prune)
        [ -f "$state/enabled" ] || exit 0
        before="$(find "$dir" -type f | wc -l)"
        find "$dir" -type f ! -anewer "$state/mark" -delete
        echo "prune-build-cache: kept $(find "$dir" -type f | wc -l) of $before entries ($(du -sm "$dir" | cut -f1) MB)"
        ;;
    *)
        echo "usage: prune-build-cache.sh age|prune" >&2
        exit 2
        ;;
esac
