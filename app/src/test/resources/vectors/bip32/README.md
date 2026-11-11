# BIP32 vectors

- Source: https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki
- Raw file: https://raw.githubusercontent.com/bitcoin/bips/master/bip-0032.mediawiki
- Retrieved: 2026-11-11

`vectors.txt` holds the seeds and master extended public keys of BIP32 test vectors 1 and 2.
Each row is `vector<TAB>seed-hex<TAB>master-xpub<TAB>master-pubkey-hex<TAB>master-fingerprint-hex`.

The xpub strings are copied unchanged from the specification. The last two columns were derived
from the published xpub once, with an independent script (base58check decode, take the 33-byte
public key, fingerprint = first 4 bytes of HASH160), so that the test does not need a base58
decoder:

```
payload = base58check_decode(xpub)          # 78 bytes
public_key = payload[-33:]                  # compressed secp256k1 key
fingerprint = ripemd160(sha256(public_key))[:4]
```

`Bip32VectorTest` recomputes the fingerprint from `master-pubkey-hex` as well, so a typo in the
derived columns fails the fixture check.

`HdWallet` only exposes the master fingerprint and the fixed `m/44'/60'/0'` account, so the
child paths published by the specification (`m/0H`, `m/0H/1`, ...) cannot be observed through
the production API. The master fingerprint still ties the seed-to-master-key step to published
data; the Ethereum derivation below that point is covered by the viem vectors in
`HdWalletTest`.
