#!/usr/bin/env bash
# Guard the merged debug manifest (docs/testing/guideline.md 1.8).
# Libraries add permissions during the merge, so the check runs on the merged output:
# no android.permission.INTERNET, no android.permission.ACCESS_NETWORK_STATE, and
# android:allowBackup must be exactly "false".
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

MERGED="$ROOT/app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"

# Always run the task: reusing whatever intermediate happens to be in app/build can check a
# manifest built from older inputs and pass a change it never saw.
echo "check-manifest: building the merged debug manifest"
./gradlew :app:processDebugMainManifest

if [ ! -f "$MERGED" ]; then
    # AGP has moved this intermediate before; fall back to a search instead of guessing again.
    candidates="$(find "$ROOT/app/build/intermediates" -type f -name AndroidManifest.xml -path '*merged_manifest*/debug/*' 2>/dev/null || true)"
    MERGED="${candidates%%$'\n'*}"
fi

if [ -z "$MERGED" ] || [ ! -f "$MERGED" ]; then
    echo "check-manifest: error: merged debug manifest not found under app/build/intermediates" >&2
    exit 1
fi

echo "check-manifest: checking $MERGED"
status=0

for permission in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE; do
    if grep -q "\"$permission\"" "$MERGED"; then
        echo "check-manifest: error: $permission is declared in the merged manifest; Lensly is offline by design" >&2
        status=1
    fi
done

if ! grep -q 'android:allowBackup="false"' "$MERGED"; then
    if grep -q 'android:allowBackup=' "$MERGED"; then
        echo "check-manifest: error: android:allowBackup must be \"false\" in the merged manifest" >&2
    else
        echo "check-manifest: error: android:allowBackup is missing; it must be set to \"false\"" >&2
    fi
    status=1
fi

if [ "$status" -ne 0 ]; then
    echo "check-manifest: failed" >&2
    exit 1
fi

echo "check-manifest: ok (no network permissions, allowBackup=false)"
