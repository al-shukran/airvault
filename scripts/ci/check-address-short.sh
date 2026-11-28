#!/usr/bin/env bash
# Every short display of an address goes through AddressGroups (core/model), so an EVM 0x address reads in groups of 4 on every screen (#1684).
# app/, lumio/, shared/, core/, chains/ and abi/ are checked. Fails when a Kotlin file under a module's src/main, outside the exceptions below, cuts a string with take(..), an
# ellipsis ("…" or "...") and takeLast(..) on one line, or with substring(0, N) and an ellipsis, which is how a local 4…4 helper is written.
#
# The exceptions are files that never show an 0x address: AddressGroups itself, the Solana review (base58 only), the sentence helper of the
# Lensly review (it shortens non-0x addresses only, after an 0x address has gone to AddressGroups), and the CoW order digest and Lumio's
# transaction hash, which are hashes.
#
# Usage: scripts/ci/check-address-short.sh [root]   (root defaults to the repository root)
set -euo pipefail

root=${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}

python3 -I - "$root" <<'PY'
import os
import re
import sys

EXEMPT = {
    "core/model/src/main/kotlin/app/airvault/core/model/AddressGroups.kt",
    "chains/solana/signer/src/main/kotlin/app/airvault/chains/solana/signer/SolanaReview.kt",
    "app/src/main/java/app/lensly/ui/sign/ReviewBlocks.kt",
    "chains/evm/signer/src/main/kotlin/app/lensly/core/registry/CowSteps.kt",
    "lumio/src/main/java/app/lumio/transactions/TxHash.kt",
}
CHECKED = {"app", "lumio", "shared", "core", "chains", "abi"}
SKIP_DIRS = {".git", ".gradle", "build", "node_modules"}
CUT = re.compile(r"\btake\([^\n]*(…|\.\.\.)[^\n]*\btakeLast\(|\bsubstring\(0, ?[0-9A-Z_]+\)[^\n]*(…|\.\.\.)")
root = sys.argv[1]
hits = []

for directory, subdirs, files in os.walk(root):
    subdirs[:] = [d for d in subdirs if d not in SKIP_DIRS]
    parts = os.path.relpath(directory, root).split(os.sep)
    if parts[0] not in CHECKED and parts != ["."]:
        subdirs[:] = []
        continue
    # Only a module's own src/main is production code: tests and androidTest may spell a short address out.
    if "src" not in parts or parts[parts.index("src") + 1:][:1] != ["main"]:
        continue
    for name in files:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(directory, name)
        relative = os.path.relpath(path, root).replace(os.sep, "/")
        if relative in EXEMPT:
            continue
        with open(path, encoding="utf-8") as source:
            for number, line in enumerate(source, 1):
                if CUT.search(line):
                    hits.append(f"{relative}:{number}")

if hits:
    print("check-address-short: error: a local short-address helper; write the address with AddressGroups.short, ends or group:", file=sys.stderr)
    for hit in hits:
        print(hit, file=sys.stderr)
    sys.exit(1)
print("check-address-short: no local short-address helper outside AddressGroups")
PY
