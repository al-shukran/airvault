#!/usr/bin/env bash
# Every short display of an address goes through AddressGroups (core/model), so an EVM 0x address reads in groups of 4 on every screen (#1684).
# Only app/, shared/, core/, chains/ and abi/ are checked for now: Lumio's address display waits for a design decision. Fails when a Kotlin or Java
# file under a module's shipped source set (src/main or src/release), outside the exceptions below, cuts a string with take(..), an ellipsis and
# takeLast(..) in one function, or with substring(0, N) and an ellipsis, which is how a local 4…4 helper is written. Each function is read whole,
# so a helper spread over several lines, a spaced call, a … escape or an ellipsis held in a constant is caught too.
#
# The exceptions name the functions that never show an 0x address: AddressGroups.short and ends, the Solana review (base58 only), the sentence
# helper of the Lensly review (it shortens non-0x addresses only, after an 0x address has gone to AddressGroups), and the CoW order digest, which is
# a hash. Any other function in those files is checked.
#
# Usage: scripts/ci/check-address-short.sh [root]   (root defaults to the repository root)
set -euo pipefail

root=${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}

python3 -I - "$root" <<'PY'
import os
import re
import sys

EXEMPT = {
    "core/model/src/main/kotlin/app/airvault/core/model/AddressGroups.kt": {"short", "ends"},
    "chains/solana/signer/src/main/kotlin/app/airvault/chains/solana/signer/SolanaReview.kt": {"shortAddress"},
    "app/src/main/java/app/lensly/ui/sign/ReviewBlocks.kt": {"shortAddress"},
    "chains/evm/signer/src/main/kotlin/app/lensly/core/registry/CowSteps.kt": {"describe"},
}
CHECKED = {"app", "shared", "core", "chains", "abi"}
SHIPPED = {"main", "release"}
SKIP_DIRS = {".git", ".gradle", "build", "node_modules"}
FUN = re.compile(r"\bfun\s+")
FUN_NAME = re.compile(r"fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")
ELLIPSIS_CONSTANT = re.compile(r'\bval\s+(\w+)\s*=\s*"(?:…|\.\.\.)"')
UNICODE_ESCAPE = re.compile(r"\\u([0-9a-fA-F]{4})")
root = sys.argv[1]
hits = []


def is_cut(chunk, ellipsis_names):
    has_ellipsis = "…" in chunk or "..." in chunk or any(re.search(rf"\b{name}\b", chunk) for name in ellipsis_names)
    takes = re.search(r"\btake\s*\(", chunk) and re.search(r"\btakeLast\s*\(", chunk)
    substring = re.search(r"\bsubstring\s*\(\s*0\s*,\s*[0-9A-Z_]+\s*\)", chunk)
    return has_ellipsis and bool(takes or substring)


def functions(text):
    # Yields (line, name, chunk) for the text before the first fun and for each fun declaration up to the next one.
    starts = sorted({0} | {m.start() for m in FUN.finditer(text)})
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else len(text)
        match = FUN_NAME.match(text, start)
        yield text.count("\n", 0, start) + 1, match.group(1) if match else None, text[start:end]


for directory, subdirs, files in os.walk(root):
    subdirs[:] = [d for d in subdirs if d not in SKIP_DIRS]
    parts = os.path.relpath(directory, root).split(os.sep)
    if parts[0] not in CHECKED and parts != ["."]:
        subdirs[:] = []
        continue
    # Only a module's own shipped source sets are production code: tests, androidTest and debug may spell a short address out.
    if "src" not in parts or parts.index("src") + 1 >= len(parts) or parts[parts.index("src") + 1] not in SHIPPED:
        continue
    for name in files:
        if not name.endswith((".kt", ".java")):
            continue
        path = os.path.join(directory, name)
        relative = os.path.relpath(path, root).replace(os.sep, "/")
        with open(path, encoding="utf-8") as source:
            text = UNICODE_ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), source.read())
        ellipsis_names = set(ELLIPSIS_CONSTANT.findall(text))
        for line, function, chunk in functions(text):
            if function in EXEMPT.get(relative, ()):
                continue
            if is_cut(chunk, ellipsis_names):
                hits.append(f"{relative}:{line}")

if hits:
    print("check-address-short: error: a local short-address helper; write the address with AddressGroups.short, ends or group:", file=sys.stderr)
    for hit in hits:
        print(hit, file=sys.stderr)
    sys.exit(1)
print("check-address-short: no local short-address helper outside AddressGroups")
PY
