# SLIP-0010 Ed25519 vectors

- Source: https://github.com/satoshilabs/slips/blob/master/slip-0010.md
- Raw file: https://raw.githubusercontent.com/satoshilabs/slips/master/slip-0010.md
- Retrieved: 2026-11-16

`vectors.txt` holds the Ed25519 chain steps of SLIP-0010 test vectors 1 and 2, one row per step. Each row is
`vector<TAB>path<TAB>chain-code-hex<TAB>private-key-hex<TAB>public-key-hex`.

The path uses the specification's notation, where `H` marks a hardened child. The public key keeps the `00` prefix
that SLIP-0010 writes in front of an Ed25519 public key, so the raw key is the last 32 bytes. The fingerprint column of
the specification is left out, because `Slip10Ed25519Test` does not check it.

The rows were extracted from the specification's text by a script, not typed by hand, so they match the published
values character for character.
