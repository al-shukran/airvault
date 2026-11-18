#!/usr/bin/env bash
# Automatic test sharding for the PR lane (docs/testing/guideline.md 2.2). Sourced by plan-partitions.sh,
# run-partition.sh and check-partitions.sh; it runs nothing by itself.
#
# A test class belongs to the shard its name hashes to, so a new test class needs no edit here. The shard count per
# module is the only setting: raise it when a shard's job nears the 2:15 budget (guideline 2.1).

shard_count() {
    case "$1" in
        shared) echo 4 ;;
        app) echo 2 ;;
        lumio) echo 4 ;;
        *) echo "shards.sh: no shard count for module '$1'" >&2; return 1 ;;
    esac
}

# Prints "<source file><TAB><fully qualified name>" for each top-level class declared in a file under
# <module>/src/test/java that contains @Test. The name comes from the package line and the declaration, not from the
# file name, so a file may declare more than one test class.
test_classes() {
    local module=$1 dir file pkg name
    dir="$ROOT/$module/src/test/java"
    [ -d "$dir" ] || return 0
    while IFS= read -r file; do
        grep -q '@Test' "$file" || continue
        pkg=$(sed -n 's/^package \([A-Za-z0-9_.]*\).*/\1/p' "$file" | head -n 1)
        { grep -E '^([^[:space:]/*].*)?(class|object) [A-Za-z_]' "$file" || true; } \
            | sed -E 's/.*(class|object) ([A-Za-z_][A-Za-z0-9_]*).*/\2/' \
            | while IFS= read -r name; do
                printf '%s\t%s%s\n' "${file#"$ROOT"/}" "${pkg:+$pkg.}" "$name"
            done
    done < <(find "$dir" -type f \( -name '*.kt' -o -name '*.java' \) | LC_ALL=C sort)
}

# Shard (1..count) that holds a class: a CRC-32 of its name, so an existing class never changes shard.
shard_of() {
    local crc
    crc=$(printf '%s\n' "$1" | cksum | cut -d' ' -f1)
    echo $((crc % $2 + 1))
}

# Prints the fully qualified names that run in shard <index> of <count> of <module>, sorted. The lanes the module's build
# keeps out of the unit-test JVM are left out: the fuzz package in :shared (its own -PfuzzLane partition) and the
# screenshot package in :app (-PprLane; the nightly run covers it).
shard_classes() {
    local module=$1 index=$2 count=$3 file fqcn
    {
        test_classes "$module" | while IFS=$'\t' read -r file fqcn; do
            case "$module:$fqcn" in
                shared:app.lensly.fuzz.* | app:app.lensly.ui.screenshot.*) continue ;;
            esac
            if [ "$(shard_of "$fqcn" "$count")" = "$index" ]; then
                printf '%s\n' "$fqcn"
            fi
        done
    } | LC_ALL=C sort -u
}
