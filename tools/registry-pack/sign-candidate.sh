#!/usr/bin/env bash
# Signs the verified approved-contract entries into the bundled LRP2 package the app ships.
#
# usage: tools/registry-pack/sign-candidate.sh --sequence N --key FILE [--created yyyy-mm-dd] [--out FILE]
#
#   --sequence  the package sequence (at least 1). Bump it whenever the content changes: an installed
#               package with an equal or higher sequence is never replaced.
#   --created   the package date (default: today).
#   --key       the sealed release key, required. Its public half is read from the same path with .pub.
#   --out       the package to write (default: shared/src/main/assets/registry/bundled.lrp).
#
# The entries are the lines of approved-contracts/verified-selectors.txt, taken from approved-contracts/candidate-whitelist.json.
# Every line must match exactly one candidate entry, or the script stops. The package is built by :tools:registry-pack (the writer
# the app reads), verified with the public key, and refused unless that public key is an active key pinned in trusted-keys.json.
#
# The passphrase comes from LENSLY_REGISTRY_PASSPHRASE, or else from the macOS keychain item
# (service lensly-registry-release, account registry-release). It is never printed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
REG_DATA="$ROOT/tools/registry-data/approved-contracts"
CANDIDATE="$REG_DATA/candidate-whitelist.json"
SELECTION="$REG_DATA/verified-selectors.txt"
PINNED="$ROOT/shared/src/main/assets/registry/trusted-keys.json"
KEY=""
OUT="$ROOT/shared/src/main/assets/registry/bundled.lrp"
SEQUENCE=""
CREATED="$(date +%Y-%m-%d)"

die() { echo "error: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --sequence) SEQUENCE="${2:-}"; shift 2 ;;
    --created) CREATED="${2:-}"; shift 2 ;;
    --key) KEY="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) die "unknown argument $1" ;;
  esac
done

case "$SEQUENCE" in ''|*[!0-9]*) die "--sequence must be a whole number, at least 1" ;; esac
[ "$SEQUENCE" -ge 1 ] || die "--sequence must be at least 1"
case "$CREATED" in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;; *) die "--created must be yyyy-mm-dd" ;; esac
[ -n "$KEY" ] || die "--key is required: the sealed release key"
[ -f "$KEY" ] || die "no release key at $KEY"
PUB="${KEY%.key}.pub"
[ -f "$PUB" ] || die "no public key at $PUB"
[ -f "$CANDIDATE" ] || die "no candidate at $CANDIDATE"
[ -f "$SELECTION" ] || die "no selection at $SELECTION"

# Refuse to sign with a key the app does not pin as active.
PUB_HEX="$(tr -d ' \n\r\t' < "$PUB")"
jq -e --arg pk "$PUB_HEX" '.keys[] | select(.publicKey == $pk and .status == "active")' "$PINNED" >/dev/null \
  || die "the public key in $PUB is not an active key in $PINNED"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/registry-sign.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# The entries file: the selected candidate entries, in the package's own shape. Networks and tokens are not part of this package.
jq -n --slurpfile cand "$CANDIDATE" --rawfile sel "$SELECTION" --arg created "$CREATED" --argjson seq "$SEQUENCE" '
  [ $sel | split("\n")[] | select(length > 0 and (startswith("#") | not)) | split(" ")
    | { chainId: (.[0] | tonumber), contract: .[1], selector: .[2] } ] as $want
  | [ $cand[0].whitelist[] | . as $e
      | select(any($want[]; .chainId == $e.chainId and .contract == $e.contract and .selector == $e.selector)) ] as $keep
  | if ($keep | length) != ($want | length) then
      error("the selection names \($want | length) entries, the candidate has \($keep | length) of them")
    else . end
  | { formatVersion: 2, minSchemaVersion: 3, sequence: $seq, createdAt: $created,
      networks: [], tokens: [], whitelist: $keep }
' > "$WORK/entries.json" || die "could not build the entries from $SELECTION"

if [ -z "${LENSLY_REGISTRY_PASSPHRASE:-}" ] && command -v security >/dev/null 2>&1; then
  LENSLY_REGISTRY_PASSPHRASE="$(security find-generic-password -a registry-release -s lensly-registry-release -w 2>/dev/null || true)"
  export LENSLY_REGISTRY_PASSPHRASE
fi
[ -n "${LENSLY_REGISTRY_PASSPHRASE:-}" ] || die "no passphrase: set LENSLY_REGISTRY_PASSPHRASE or store it in the keychain"

cd "$ROOT"
./gradlew --max-workers=4 -q :tools:registry-pack:run \
  --args="build --in $WORK/entries.json --key $KEY --out $OUT"
./gradlew --max-workers=4 -q :tools:registry-pack:run \
  --args="verify --pub $PUB $OUT"
echo "entries: $(jq '.whitelist | length' "$WORK/entries.json") whitelist entries, sequence $SEQUENCE, created $CREATED"
echo "wrote $OUT (sha256 $(shasum -a 256 "$OUT" | cut -d' ' -f1))"
