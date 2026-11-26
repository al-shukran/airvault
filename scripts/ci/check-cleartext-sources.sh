#!/usr/bin/env bash
# Cleartext HTTP may be allowed only under a debug source set. Fails when any XML file (any case of .xml) under the src/ directory of
# any module (app, lumio, shared, chains/*/signer, ...), outside that module's src/debug/, sets cleartextTrafficPermitted or
# usesCleartextTraffic to anything but "false". A resource reference (@bool/x) or a manifest placeholder (${x}) is a hit, because it
# can resolve to true. The files are parsed as XML, so single quotes, spaces around "=", another namespace prefix and character
# references all count the way Android reads them. An XML file that cannot be parsed fails too, because it cannot be checked.
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
    parts = os.path.relpath(directory, root).split(os.sep)
    # Build output and tool directories are skipped outside a src/ tree only; one inside src/ is scanned like any other.
    if "src" not in parts:
        subdirs[:] = [d for d in subdirs if d not in SKIP_DIRS]
    # Only a module's own src/debug is exempt: the first src component is the module's, and debug must follow it.
    if "src" not in parts or parts[parts.index("src") + 1:][:1] == ["debug"]:
        continue
    for name in files:
        if not name.lower().endswith(".xml"):
            continue
        path = os.path.join(directory, name)
        try:
            elements = ET.parse(path).iter()
        except ET.ParseError:
            unreadable.append(path)
            continue
        for element in elements:
            for key, value in element.attrib.items():
                if key.rsplit("}", 1)[-1] in ATTRIBUTES and value.strip().lower() != "false":
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
