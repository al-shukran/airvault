#!/usr/bin/env bash
# Fails when a tracked text file holds a BIP39 mnemonic: 12 or more consecutive English BIP39 words. The words may be split across
# lines by the quotes and "+" of a string concatenation. A run passes only when it equals a line of scripts/ci/mnemonic-allowlist.txt,
# which holds the standard published vectors. The report names the file and line, never the words, because CI logs are public.
#
# Usage: scripts/ci/check-mnemonics.sh

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
export LC_ALL=C

wordlist=scripts/ci/bip39-english.txt
allowlist=scripts/ci/mnemonic-allowlist.txt
for file in "$wordlist" "$allowlist"; do
  if [ ! -f "$file" ]; then
    echo "check-mnemonics: $file is missing" >&2
    exit 1
  fi
done

# Every line of every tracked text file as file:line:text. grep -I skips binary files, and the wordlist is left out.
scan=$(mktemp)
trap 'rm -f "$scan"' EXIT
git grep -I -n -e . -- . ":(exclude)$wordlist" > "$scan" || [ $? -eq 1 ]

awk -v wordlist="$wordlist" -v allowlist="$allowlist" -v q="'" '
  BEGIN {
    joinable = "^[[:space:]\"" q "+]*$"
    while ((getline w < wordlist) > 0) bip[w] = 1
    close(wordlist)
    while ((getline a < allowlist) > 0) {
      if (a ~ /^[[:space:]]*(#|$)/) continue
      gsub(/[[:space:]]+/, " ", a)
      sub(/^ /, "", a)
      sub(/ $/, "", a)
      allowed[tolower(a)] = 1
    }
    close(allowlist)
  }
  {
    if (!match($0, /^[^:]*:[0-9]+:/)) next
    split(substr($0, 1, RLENGTH - 1), where, ":")
    file = where[1]
    line = where[2]
    text = substr($0, RLENGTH + 1)
    if (file != current) {
      flush()
      current = file
      gap = ""
      files++
    } else {
      # Lines of the allowlist are separate mnemonics, so a line never joins the next one.
      gap = (file == allowlist) ? "|" : gap "\n"
    }
    rest = text
    while (match(rest, /[A-Za-z]+/)) {
      gap = gap substr(rest, 1, RSTART - 1)
      word = tolower(substr(rest, RSTART, RLENGTH))
      rest = substr(rest, RSTART + RLENGTH)
      token(word, line, file)
    }
    gap = gap rest
  }
  function token(word, line, file) {
    if ((word in bip) && gap ~ joinable && inrun > 0) {
      runwords = runwords " " word
      inrun++
    } else {
      flush()
      if (word in bip) {
        runwords = word
        inrun = 1
        runline = line
        runfile = file
      }
    }
    gap = ""
  }
  function flush() {
    if (inrun >= 12 && !(runwords in allowed)) {
      printf "%s:%s: %d consecutive BIP39 words, which is not an allowlisted mnemonic (%s)\n", runfile, runline, inrun, allowlist
      bad = 1
    }
    inrun = 0
    runwords = ""
  }
  END {
    flush()
    if (bad) exit 1
    printf "check-mnemonics: %d text files checked, no BIP39 mnemonic outside %s\n", files, allowlist
  }
' "$scan"
