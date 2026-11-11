#!/usr/bin/env bash
# Compare an AndroidX Benchmark result against the committed reference-device baseline (docs/testing/guideline.md 1.7).
#
#   scripts/ci/benchmark-gate.sh [--record] <benchmark-result.json>
#
# Fails when the median of a benchmark is more than BENCHMARK_MAX_REGRESSION_PCT (default 10) percent above the
# baseline, or when a benchmark in the result has no baseline entry.
#
# --record rewrites app/src/androidTest/benchmark-baselines/baselines.json from the result file. Recording belongs to a
# person on the reference device, so it refuses to run with CI=true.
#
# When baselines.json does not exist yet (no reference-device run has happened), the gate prints how to record one and,
# locally, exits 0. With CI=true it fails closed, so CI can never pass by silently having no baseline.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASELINE_DIR="${BENCHMARK_BASELINE_DIR:-$ROOT/app/src/androidTest/benchmark-baselines}"
BASELINE="$BASELINE_DIR/baselines.json"
MAX_PCT="${BENCHMARK_MAX_REGRESSION_PCT:-10}"

usage() {
    cat >&2 <<'EOF'
usage: scripts/ci/benchmark-gate.sh [--record] <benchmark-result.json>

  --record   write the baseline file from this result (reference device only; refuses CI=true)

env:
  BENCHMARK_MAX_REGRESSION_PCT  allowed regression, default 10
  BENCHMARK_BASELINE_DIR        baseline directory, default app/src/androidTest/benchmark-baselines
EOF
}

record=false
result=""
for arg in "$@"; do
    case "$arg" in
        --record) record=true ;;
        -h|--help) usage; exit 0 ;;
        -*) echo "benchmark-gate: unknown option: $arg" >&2; usage; exit 2 ;;
        *)
            [ -z "$result" ] || { echo "benchmark-gate: only one result file is accepted" >&2; exit 2; }
            result="$arg"
            ;;
    esac
done

[ -n "$result" ] || { usage; exit 2; }
[ -f "$result" ] || { echo "benchmark-gate: error: no such result file: $result" >&2; exit 1; }

command -v jq >/dev/null 2>&1 || { echo "benchmark-gate: error: jq is required" >&2; exit 1; }

# One line per benchmark: className#method, metric name, median value.
benchmarks() {
    jq -r '
        .benchmarks[] as $b
        | ($b.metrics // {}) as $m
        | (if ($m | has("frameDurationNs")) then "frameDurationNs"
           elif ($m | has("timeNs")) then "timeNs"
           else ($m | keys | .[0]) end) as $metric
        | [ (if $b.className then ($b.className + "#" + $b.name) else $b.name end), $metric,
            ($m[$metric].median // $m[$metric].minimum) ]
        | @tsv
    ' "$1"
}

if [ "$record" = true ]; then
    if [ "${CI:-}" = "true" ]; then
        echo "benchmark-gate: error: --record is not allowed with CI=true; record on the reference device" >&2
        exit 1
    fi

    recorded_at="$(date -u +%Y-%m-%d)"
    fresh="$(mktemp)"
    trap 'rm -f "$fresh"' EXIT

    jq --arg recordedAt "$recorded_at" '
        {
            device: (.context.build.device // "unknown"),
            recordedAt: $recordedAt,
            benchmarks: (
                reduce (.benchmarks[]) as $b ({};
                    ($b.metrics // {}) as $m
                    | (if ($m | has("frameDurationNs")) then "frameDurationNs"
                       elif ($m | has("timeNs")) then "timeNs"
                       else ($m | keys | .[0]) end) as $metric
                    | .[(if $b.className then ($b.className + "#" + $b.name) else $b.name end)] =
                        { metric: $metric, value: ($m[$metric].median // $m[$metric].minimum) }
                )
            )
        }
    ' "$result" > "$fresh"

    if [ -f "$BASELINE" ]; then
        merged="$(mktemp)"
        jq -s '.[0] as $old | .[1] as $new | $old * {
            device: $new.device,
            recordedAt: $new.recordedAt,
            benchmarks: ($old.benchmarks + $new.benchmarks)
        }' "$BASELINE" "$fresh" > "$merged"
        mv "$merged" "$fresh"
    fi

    mkdir -p "$BASELINE_DIR"
    mv "$fresh" "$BASELINE"
    trap - EXIT

    echo "benchmark-gate: recorded $(jq -r '.benchmarks | length' "$BASELINE") benchmark(s) from $(jq -r '.device' "$BASELINE") into $BASELINE"
    echo "benchmark-gate: commit this file only when the device in it is the reference device"
    exit 0
fi

if [ ! -f "$BASELINE" ]; then
    echo "benchmark-gate: no baseline yet at $BASELINE" >&2
    echo "benchmark-gate: record one on the reference device: scripts/ci/benchmark-gate.sh --record <result.json>" >&2
    if [ "${CI:-}" = "true" ]; then
        echo "benchmark-gate: CI=true, so a missing baseline fails instead of skipping" >&2
        exit 1
    fi
    echo "benchmark-gate: exiting 0 locally" >&2
    exit 0
fi

lines="$(benchmarks "$result")"
[ -n "$lines" ] || { echo "benchmark-gate: error: the result file lists no benchmarks" >&2; exit 1; }

status=0
missing=0
count=0

while IFS=$'\t' read -r key metric value; do
    count=$((count + 1))
    base="$(jq -r --arg k "$key" '.benchmarks[$k].value // empty' "$BASELINE")"

    if [ -z "$base" ]; then
        echo "benchmark-gate: no baseline for $key ($metric); record it with --record" >&2
        missing=$((missing + 1))
        continue
    fi

    pct="$(awk -v b="$base" -v c="$value" 'BEGIN { printf "%.1f", (c - b) / b * 100 }')"
    extra=""
    if [ "$metric" = "frameDurationNs" ]; then
        extra=" ($(awk -v v="$value" 'BEGIN { printf "%.1f", 1000000000 / v }') fps)"
    fi

    over="$(awk -v p="$pct" -v max="$MAX_PCT" 'BEGIN { print (p > max) ? 1 : 0 }')"
    if [ "$over" = "1" ]; then
        echo "benchmark-gate: FAIL $key: ${value}ns vs baseline ${base}ns, +${pct}% (limit ${MAX_PCT}%)${extra}" >&2
        status=1
    else
        echo "benchmark-gate: ok   $key: ${value}ns vs baseline ${base}ns, ${pct}%${extra}"
    fi
done <<< "$lines"

if [ "$missing" -gt 0 ]; then
    echo "benchmark-gate: error: $missing benchmark(s) in the result have no baseline" >&2
    status=1
fi

if [ "$status" -ne 0 ]; then
    echo "benchmark-gate: failed ($count benchmark(s) checked, limit ${MAX_PCT}%)" >&2
    exit 1
fi

echo "benchmark-gate: ok ($count benchmark(s) within ${MAX_PCT}% of the baseline on $(jq -r '.device' "$BASELINE"))"
