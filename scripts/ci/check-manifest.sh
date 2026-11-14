#!/usr/bin/env bash
# Guard the merged debug manifests (docs/testing/guideline.md 1.8).
# Libraries add permissions during the merge, so the checks run on the merged output.
#   :app (Lensly) is offline: no android.permission.INTERNET and no android.permission.ACCESS_NETWORK_STATE.
#   :lumio (the companion) is the one app that uses the network. It may request only the permissions on the allow-list below.
#   Both merged manifests set android:allowBackup to exactly "false".
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# The permissions Lumio may request. Any other permission in its merged manifest fails the check.
LUMIO_ALLOWED_PERMISSIONS="android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.CAMERA android.permission.VIBRATE"

# Prints the merged debug manifest of one module, or nothing. AGP has moved this intermediate before, so a search is the fallback.
merged_manifest() {
    local module="$1"
    local path="$ROOT/$module/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
    if [ ! -f "$path" ]; then
        path="$(find "$ROOT/$module/build/intermediates" -type f -name AndroidManifest.xml -path '*merged_manifest*/debug/*' 2>/dev/null | head -n 1 || true)"
    fi
    printf '%s' "$path"
}

# Prints the android.permission.* names that the merged manifest requests, one per line. Only <uses-permission> elements count. A
# component's android:permission attribute demands a permission from callers, so it is not a request, and it is skipped.
requested_permissions() {
    awk '
        /<uses-permission/ { inside = 1; text = "" }
        inside { text = text " " $0 }
        inside && />/ {
            inside = 0
            if (match(text, /android:name="android\.permission\.[A-Z_]*"/)) {
                name = substr(text, RSTART, RLENGTH)
                sub(/^android:name="/, "", name)
                sub(/"$/, "", name)
                print name
            }
        }
    ' "$1" | sort -u
}

# android:allowBackup must be exactly "false". $1 is the merged manifest and $2 its module, for the message.
check_allow_backup() {
    if grep -q 'android:allowBackup="false"' "$1"; then
        return 0
    fi
    if grep -q 'android:allowBackup=' "$1"; then
        echo "check-manifest: error: android:allowBackup must be \"false\" in the merged manifest of $2" >&2
    else
        echo "check-manifest: error: android:allowBackup is missing in the merged manifest of $2; it must be set to \"false\"" >&2
    fi
    return 1
}

# Always run the tasks. Reusing whatever intermediate happens to be in a build directory can check a manifest built from older
# inputs and pass a change it never saw.
echo "check-manifest: building the merged debug manifests"
./gradlew :app:processDebugMainManifest :lumio:processDebugMainManifest

MERGED="$(merged_manifest app)"
LUMIO_MERGED="$(merged_manifest lumio)"

if [ -z "$MERGED" ] || [ ! -f "$MERGED" ]; then
    echo "check-manifest: error: merged debug manifest of :app not found under app/build/intermediates" >&2
    exit 1
fi
if [ -z "$LUMIO_MERGED" ] || [ ! -f "$LUMIO_MERGED" ]; then
    echo "check-manifest: error: merged debug manifest of :lumio not found under lumio/build/intermediates" >&2
    exit 1
fi

status=0

echo "check-manifest: checking $MERGED"
for permission in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE; do
    if grep -q "\"$permission\"" "$MERGED"; then
        echo "check-manifest: error: $permission is declared in the merged manifest; Lensly is offline by design" >&2
        status=1
    fi
done
check_allow_backup "$MERGED" ":app" || status=1

echo "check-manifest: checking $LUMIO_MERGED"
for permission in $(requested_permissions "$LUMIO_MERGED"); do
    case " $LUMIO_ALLOWED_PERMISSIONS " in
        *" $permission "*) ;;
        *)
            echo "check-manifest: error: $permission is not on the allow-list for :lumio" >&2
            status=1
            ;;
    esac
done
check_allow_backup "$LUMIO_MERGED" ":lumio" || status=1

if [ "$status" -ne 0 ]; then
    echo "check-manifest: failed" >&2
    exit 1
fi

echo "check-manifest: ok (:app has no network permissions; :lumio requests only allow-listed permissions; allowBackup=false in both)"
