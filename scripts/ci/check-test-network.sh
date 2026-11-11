#!/usr/bin/env bash
# Fails when test code can reach the network.
#
# Guideline docs/testing/guideline.md §3: the release has no INTERNET permission, so tests
# must use local fixtures only. Test code must not reference `java.net.`, `HttpURLConnection`,
# or `URL(`.
#
# Scope: test source sets only. Fixture text in `src/test/resources` is not scanned.
#
# Allowlist: put the marker CI_ALLOW_NETWORK_REFERENCE on the same line as the reference,
# with a short reason, when the reference is a fixture string and not a real call. Keep it
# rare and narrow; the marker never belongs in src/main.
#
# Usage: scripts/ci/check-test-network.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"

marker='CI_ALLOW_NETWORK_REFERENCE'
# `URL(` needs a boundary so base64URL( and similar names do not match.
pattern='java\.net\.|HttpURLConnection|(^|[^[:alnum:]_])URL\('

scanned=0
failed=0

while IFS= read -r hit; do
  scanned=$((scanned + 1))
  text=${hit#*:}
  text=${text#*:}
  if [[ $text == *"$marker"* ]]; then
    printf 'allowlisted: %s\n' "$hit"
    continue
  fi
  printf 'network reference in test code: %s\n' "$hit"
  failed=1
done < <(
  grep -rnIE \
    --include='*.kt' --include='*.java' \
    "$pattern" \
    app/src/test app/src/androidTest tools/*/src/test tools/*/src/androidTest \
    2>/dev/null || true
)

if ((failed)); then
  cat <<'EOF'

Test code must not reach the network (guideline §3: release has no INTERNET permission).
Use local fixtures under app/src/test/resources instead. If the match is a fixture string
and not a real call, add CI_ALLOW_NETWORK_REFERENCE with a reason on the same line.
EOF
  exit 1
fi

printf 'check-test-network: %d reference(s) found, 0 unallowlisted\n' "$scanned"
