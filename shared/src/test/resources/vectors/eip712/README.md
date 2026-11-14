# EIP-712 vectors

- Source: https://eips.ethereum.org/EIPS/eip-712 (the Mail example and its signature)
- Source: https://github.com/MetaMask/eth-sig-util (`src/sign-typed-data.test.ts` for the signing key,
  and `src/__snapshots__/sign-typed-data.test.ts.snap` for the V4 array signature)
- Retrieved: 2026-11-14

`vectors.txt` holds key=value lines:

- `mailSignature`: the signature the standard gives for its Mail example. Its signer is
  0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826, and `TypedDataHashTest` checks that the digest recovers to it.
- `signingKey`: the test key of eth-sig-util's signing vectors. It has never held funds.
- `arrayMailSignature`: eth-sig-util's V4 signature of its array Mail vector, with an empty domain and `signingKey`.

The Mail documents and the array message are written out in `TypedDataHashTest`. `TypedDataSignerTest` signs with
`signingKey`.
