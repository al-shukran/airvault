#!/usr/bin/env bash
# Cleartext HTTP may be allowed only under a debug source set. Fails when any XML file under a src/ directory of any module (app,
# lumio, shared, chains, ...), outside src/debug/, sets cleartextTrafficPermitted or usesCleartextTraffic to true. The files are
# parsed as XML, so single quotes, spaces around "=", upper case TRUE, another namespace prefix and character references all count
# the way Android reads them. An XML file that cannot be parsed fails too, because it cannot be checked.
#
# Usage: scripts/ci/check-cleartext-sources.sh [root]   (root defaults to the repository root; check-manifest.sh calls it)
set -euo pipefail

root=${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}

python3 -I - "$root" <<'PY'
import os
import sys
import xml.etree.ElementTree as ET

ATTRIBUTES = {"cleartextTrafficPermitted", "usesCleartextTraffic"}
SKIP_DIRS = {".git", ".gradle", "build", "node_modules"}
root = sys.argv[1]
hits, unreadable = [], []

for directory, subdirs, files in os.walk(root):
    subdirs[:] = [d for d in subdirs if d not in SKIP_DIRS]
    parts = os.path.relpath(directory, root).split(os.sep)
    # Under a src/ directory, and not inside a src/debug/ source set.
    if "src" not in parts or any(parts[i] == "src" and parts[i + 1:i + 2] == ["debug"] for i in range(len(parts))):
        continue
    for name in files:
        if not name.endswith(".xml"):
            continue
        path = os.path.join(directory, name)
        try:
            elements = ET.parse(path).iter()
        except ET.ParseError:
            unreadable.append(path)
            continue
        for element in elements:
            for key, value in element.attrib.items():
                if key.rsplit("}", 1)[-1] in ATTRIBUTES and value.strip().lower() == "true":
                    hits.append(path)

if hits:
    print("check-manifest: error: cleartext traffic is allowed outside src/debug/; release must deny it:", file=sys.stderr)
    for path in sorted(set(hits)):
        print(os.path.relpath(path, root), file=sys.stderr)
if unreadable:
    print("check-manifest: error: XML that cannot be parsed, so its cleartext setting cannot be checked:", file=sys.stderr)
    for path in sorted(unreadable):
        print(os.path.relpath(path, root), file=sys.stderr)
sys.exit(1 if hits or unreadable else 0)
PY
