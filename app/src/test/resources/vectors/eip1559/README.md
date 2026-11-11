# EIP-1559 signing vectors

- Source: https://github.com/ethereumjs/ethereumjs-monorepo/blob/master/packages/tx/test/testData/eip1559.ts
- Raw file: https://raw.githubusercontent.com/ethereumjs/ethereumjs-monorepo/master/packages/tx/test/testData/eip1559.ts
- Retrieved: 2026-11-11
- Specification: https://eips.ethereum.org/EIPS/eip-1559

`vectors.txt` holds three of the ten published entries (nonces 819, 985 and 119; recovery ids
0 and 1). Each row is
`chainId<TAB>nonce<TAB>value<TAB>gasLimit<TAB>maxPriorityFeePerGas<TAB>maxFeePerGas<TAB>to<TAB>privateKey<TAB>signedTx`.

Each entry in the upstream file has a known private key and the expected signed transaction, so
the signature can be reproduced exactly rather than only checked by public-key recovery. The
upstream `signedTransactionRLP` field wraps the typed transaction in an RLP byte string; the
`signedTx` column is the inner `0x02 || rlp([...])` bytes, extracted once with a small script.
Chain ID 4 is the first field of every published transaction. The other columns are copied
unchanged from the upstream entry.

`Eip1559VectorTest` decodes the published transaction, re-encodes the unsigned payload from the
decoded fields, signs it, and compares `yParity`, `r`, `s` and the whole reassembled signed
transaction with the published bytes.
