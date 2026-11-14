# BIP39 vectors

- Source: https://github.com/trezor/python-mnemonic/blob/master/vectors.json
- Raw file: https://raw.githubusercontent.com/trezor/python-mnemonic/master/vectors.json
- Retrieved: 2026-11-11
- Reference: https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki

`vectors.txt` holds the eight English entries whose mnemonic has 24 words. Each row is
`entropy-hex<TAB>mnemonic<TAB>seed-hex<TAB>passphrase`. The seed column is copied unchanged
from the published `seed` field; upstream computes it with the passphrase `TREZOR` and no
further normalization.

The upstream file also has 12-word entries for these same entropies. They are omitted because
Lensly's `Mnemonic` supports 24 words only: `Mnemonic.toSeed` rejects any mnemonic that is not
exactly 24 valid English words. Covering the 12-word entries would require changing that policy,
which is out of scope for this test PR.
