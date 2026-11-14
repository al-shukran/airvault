# EIP-155 signing vector

- Source: https://eips.ethereum.org/EIPS/eip-155
- Retrieved: 2026-11-11

The specification's single worked example, copied unchanged:

- nonce 9, gas price 20 gwei, gas limit 21000,
  to `0x3535353535353535353535353535353535353535`, value `10^18` wei, empty data, chain ID 1
- private key `0x4646...46`
- unsigned payload, signing hash, and the published signed transaction

`vectors.txt` is four `key=value` lines: `unsigned`, `signingHash`, `privateKey`, `signed`.

`Eip155VectorTest` checks the signing hash, the decoded transaction fields, and that Lensly's
`r || s || v` is byte-for-byte the signature inside the published signed transaction.
