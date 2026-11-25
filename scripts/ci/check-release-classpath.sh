#!/usr/bin/env bash
# Fails when Netty or Vert.x is on the release runtime classpath of :app.
#
# web3j 6 declares both for its optional network services. :app and :shared never call them, and Lensly is offline, so
# shared/build.gradle.kts excludes them from web3j. This guard keeps a later dependency change from bringing them back.
#
# Usage: scripts/ci/check-release-classpath.sh   (nightly, next to the :app:assembleRelease step)

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

deps=$(./gradlew --offline -q :app:dependencies --configuration releaseRuntimeClasspath)
if hits=$(printf '%s\n' "$deps" | grep -E 'io\.(netty|vertx)'); then
  printf '%s\n' "$hits" >&2
  echo "error: Netty or Vert.x is on the release runtime classpath of :app (see shared/build.gradle.kts)" >&2
  exit 1
fi
echo "check-release-classpath: no Netty or Vert.x on the release runtime classpath of :app"
