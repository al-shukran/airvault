#!/usr/bin/env bash
# Every short display of an address goes through AddressGroups (core/model), so an EVM 0x address reads in groups of 4 on every screen (#1684).
# Only app/, lumio/, shared/, core/, chains/ and abi/ are checked. Fails when a Kotlin or Java file under a module's shipped source set
# (src/main or src/release), outside the exceptions below, holds an ellipsis and cuts a string, which is how a local 4...4 helper is written:
#   - an ellipsis is "…", "...", "..", a \u2026 escape, 0x2026, Typography.ellipsis, or a name (val NAME(: Type)? =) set from one of these;
#   - a cut is two slicing calls (take, takeLast, drop, dropLast, substring, slice, removeRange, subSequence, a %.Ns width) or a substring(0, ..);
#   - one unit is one function (to its closing brace or the end of its expression body) or the code between functions, so a lambda or a val
#     after a function is its own unit; a function that spreads the work over helpers is caught by the file rule: an ellipsis, a head slice
#     (take, substring(0, ..), dropLast) and a tail slice (takeLast, drop, substring(n)) anywhere in the file's checked code (#1766-#1769).
#
# The exceptions name the functions that never show an 0x address: AddressGroups.short and ends, the Solana review (base58 only), the sentence
# helper of the Lensly review (it shortens non-0x addresses only, after an 0x address has gone to AddressGroups), and the CoW order digest, which is
# a hash; AddressLine only splits the text AddressGroups wrote at its ellipsis, plainNameOf cuts a name and dynamicText previews call bytes. Only the function itself is exempt, and only while its name is declared once in the file, so an overload or the code after it is checked.
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
    "lumio/src/main/java/app/lumio/ui/kit/AddressLine.kt": {"AddressLine"},
    "shared/src/main/java/app/lensly/app/PlainName.kt": {"plainNameOf"},
    "chains/evm/signer/src/main/kotlin/app/lensly/core/registry/AbiRows.kt": {"dynamicText"},
}
CHECKED = {"app", "lumio", "shared", "core", "chains", "abi"}
SHIPPED = {"main", "release"}
SKIP_DIRS = {".git", ".gradle", "build", "node_modules"}
FUN = re.compile(r"\bfun\b")
FUN_NAME = re.compile(r"fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")
FUN_ANON = re.compile(r"fun\s*\(")
ELLIPSIS = r'…|"\.\.\.?|"[^"\n]*\.\.\.|0[xX]2026|\bTypography\.ellipsis\b'
ELLIPSIS_CONSTANT = re.compile(r"\b(?:val|var|String)\s+(\w+)\s*(?::\s*[\w.<>?]+\s*)?=[^\n]*(?:" + ELLIPSIS + ")")
UNICODE_ESCAPE = re.compile(r"\\u([0-9a-fA-F]{4})")
SLICE = re.compile(r"\b(?:take|takeLast|drop|dropLast|substring|slice|removeRange|subSequence)\s*\(|%\.\d+s")
HEAD = re.compile(r"\b(?:take|dropLast)\s*\(|\bsubstring\s*\(\s*0\s*,|%\.\d+s")
TAIL = re.compile(r"\b(?:takeLast|drop)\s*\(|\bsubstring\s*\(\s*[^0\s)]|%\.\d+s")
SUBSTRING_FROM_ZERO = re.compile(r"\bsubstring\s*\(\s*0\s*,\s*(?:0[xX][0-9a-fA-F]+|\d+|[A-Z][A-Z0-9_]*)\s*\)")
SKIP = re.compile(r'//[^\n]*|/\*.*?\*/|"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\\\'\n])\'', re.S)
CONTINUES = re.compile(r"\s*(?:[.+\-*/%&|?:]|else\b)")
root = sys.argv[1]
hits = []


def strip_comments(text):
    # Comments become blanks of the same shape, so a line number stays right and a "..." in a KDoc is not an ellipsis.
    blank = lambda m: re.sub(r"[^\n]", " ", m.group()) if m.group().startswith(("//", "/*")) else m.group()
    return SKIP.sub(blank, text)


def has_ellipsis(text, names):
    return bool(re.search(ELLIPSIS, text)) or any(re.search(rf"\b{name}\b", text) for name in names)


def is_cut(text, names):
    return has_ellipsis(text, names) and (len(SLICE.findall(text)) >= 2 or bool(SUBSTRING_FROM_ZERO.search(text)))


def is_split_cut(text, names):
    return has_ellipsis(text, names) and bool(HEAD.search(text)) and bool(TAIL.search(text))


def close(text, start, opener, closer):
    # The index after the bracket that closes the opener at text[start], skipping strings and comments.
    depth = 0
    index = start
    while index < len(text):
        skipped = SKIP.match(text, index)
        if skipped:
            index = skipped.end()
            continue
        if text[index] == opener:
            depth += 1
        elif text[index] == closer:
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return len(text)


def function_end(text, start, after_name):
    # The end of the function declared at start: its closing brace, the end of its expression body, or the end of its line without a body.
    index = close(text, text.index("(", after_name - 1), "(", ")")
    while index < len(text):
        skipped = SKIP.match(text, index)
        if skipped:
            index = skipped.end()
            continue
        if text[index] == "{":
            return close(text, index, "{", "}")
        if text[index] == "=":
            index += 1
            break
        if text[index] == "\n":
            return index
        index += 1
    else:
        return len(text)
    depth = 0
    while index < len(text):
        skipped = SKIP.match(text, index)
        if skipped:
            index = skipped.end()
            continue
        char = text[index]
        if char in "({[":
            depth += 1
        elif char in ")}]":
            depth -= 1
        elif char == "\n" and depth <= 0:
            before = text[:index].rstrip()
            if not CONTINUES.match(text, index) and not before.endswith(("+", "-", "*", "/", "&&", "||", "?:", "=", ",")):
                return index
        index += 1
    return len(text)


def units(text):
    # Yields (line, name, unit text): each function in full, and the code between functions as units of their own.
    found = []
    position = 0
    while True:
        match = FUN.search(text, position)
        if not match:
            break
        named = FUN_NAME.match(text, match.start())
        anonymous = FUN_ANON.match(text, match.start())
        if not (named or anonymous):
            position = match.end()
            continue
        end = function_end(text, match.start(), (named or anonymous).end())
        found.append((match.start(), end, named.group(1) if named else None))
        position = max(end, match.end())
    cursor = 0
    for start, end, name in found + [(len(text), len(text), None)]:
        if start > cursor:
            yield text.count("\n", 0, cursor) + 1, None, text[cursor:start]
        if end > start:
            yield text.count("\n", 0, start) + 1, name, text[start:end]
        cursor = end


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
        text = strip_comments(text)
        names = set(ELLIPSIS_CONSTANT.findall(text))
        parts = list(units(text))
        declared = [name for _, name, _ in parts if name]
        checked = []
        for line, function, chunk in parts:
            if function in EXEMPT.get(relative, ()) and declared.count(function) == 1:
                continue
            checked.append(chunk)
            if is_cut(chunk, names):
                hits.append(f"{relative}:{line}")
        if not any(hit.startswith(relative + ":") for hit in hits) and is_split_cut("\n".join(checked), names):
            hits.append(f"{relative}:1")

if hits:
    print("check-address-short: error: a local short-address helper; write the address with AddressGroups.short, ends or group:", file=sys.stderr)
    for hit in hits:
        print(hit, file=sys.stderr)
    sys.exit(1)
print("check-address-short: no local short-address helper outside AddressGroups")
PY
