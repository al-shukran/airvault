#!/usr/bin/env bash
# Builds the screen gallery of :app (Lensly) and :lumio (Lumio) from the committed Roborazzi goldens. No Gradle run is needed.
# Usage: tools/gallery/build-gallery.sh [--single] [--width 360] [--out DIR]. See tools/gallery/README.md.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v sips >/dev/null 2>&1; then
    echo "build-gallery: sips is required (macOS)." >&2
    exit 1
fi

# -I: isolated mode, so the environment and the working directory cannot change what the script runs.
exec python3 -I "$here/build_gallery.py" "$@"
