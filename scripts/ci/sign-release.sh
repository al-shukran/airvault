#!/usr/bin/env bash
# Signs the unsigned release APK in the sign jobs of .github/workflows/release.yml. These jobs run no Gradle: the APK comes from the
# build job, so before anything else the script checks it against the SHA-256 that job reported, and refuses version code 1 (the
# value app/build.gradle.kts falls back to when it cannot count commits; a signed 1 could never update an installed copy).
#
# Environment: APP (lensly or lumio), EXPECTED_SHA256 (from the build job), KEYSTORE_B64 and KEYSTORE_PASS (that app's secrets).
# Optional: UNSIGNED (default unsigned/$APP.apk), OUT_DIR (default signed), BUILD_TOOLS (default: the newest build-tools directory
# under $ANDROID_HOME). The decoded keystore is written to $RUNNER_TEMP/release.keystore; the next workflow step deletes it.
# Writes $OUT_DIR/$APP.apk.

set -euo pipefail

app=${APP:?APP is not set}
unsigned=${UNSIGNED:-unsigned/$app.apk}
out_dir=${OUT_DIR:-signed}

if [ ! -f "$unsigned" ]; then
    echo "::error::The build job's artifact has no $unsigned."
    exit 1
fi
actual=$(sha256sum "$unsigned" | cut -d' ' -f1)
if [ -z "${EXPECTED_SHA256:-}" ] || [ "$actual" != "$EXPECTED_SHA256" ]; then
    echo "::error::The downloaded $unsigned has SHA-256 $actual, not the ${EXPECTED_SHA256:-empty} the build job reported."
    exit 1
fi

prefix=$(tr a-z A-Z <<<"$app")
if [ -z "${KEYSTORE_B64:-}" ] || [ -z "${KEYSTORE_PASS:-}" ]; then
    echo "::error::Signing $app needs the secrets ${prefix}_KEYSTORE_B64 and ${prefix}_KEYSTORE_PASS in the release environment (Settings > Environments > release)."
    exit 1
fi

if [ -z "${BUILD_TOOLS:-}" ]; then
    BUILD_TOOLS=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
fi
version_code=$("$BUILD_TOOLS/aapt2" dump badging "$unsigned" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p")
if [ -z "$version_code" ] || [ "$version_code" = 1 ]; then
    echo "::error::The APK has version code '${version_code}'. The build could not count commits (needs a full clone); a signed 1 could never update an installed copy."
    exit 1
fi

umask 077
keystore="${RUNNER_TEMP:?RUNNER_TEMP is not set}/release.keystore"
printf '%s' "$KEYSTORE_B64" | base64 --decode > "$keystore"
aligned="$RUNNER_TEMP/$app-aligned.apk"
"$BUILD_TOOLS/zipalign" -f -P 16 4 "$unsigned" "$aligned"
mkdir -p "$out_dir"
KEYSTORE_PASS=$KEYSTORE_PASS "$BUILD_TOOLS/apksigner" sign \
    --ks "$keystore" --ks-key-alias "$app" \
    --ks-pass env:KEYSTORE_PASS --key-pass env:KEYSTORE_PASS \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
    --out "$out_dir/$app.apk" "$aligned"
