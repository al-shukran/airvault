#!/usr/bin/env bash
# Renders a lumio screenshot test at 1 px = 1 dp (or any density) into a scratch folder, without touching the goldens, for measure-bands.py.
#   scripts/design/render-at-density.sh 1 '*ExploreScreenshotTest*' '*ProfilePanelScreenshotTest*'   (PNGs in build/density-1/)
set -euo pipefail
density=$1; shift
out="$(cd "$(dirname "$0")/../.." && pwd)/build/density-$density"
mkdir -p "$out"
args=(); for f in "$@"; do args+=(--tests "$f"); done
SCREENSHOT_DENSITY=$density SCREENSHOT_OUT=$out "$(dirname "$0")/../../gradlew" -p "$(dirname "$0")/../.." :lumio:cleanTestDebugUnitTest :lumio:recordRoborazziDebug --no-build-cache "${args[@]}"
echo "rendered into $out"
