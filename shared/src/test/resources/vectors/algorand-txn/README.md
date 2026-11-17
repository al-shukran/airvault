# Algorand transaction vectors

- Producer: py-algorand-sdk 2.12.0 and msgpack 1.2.4, an independent encoder for the decoder in
  `app.lensly.core.algorand`. Written 2026-11-16.
- Generator: `gen_txn_vectors.py` (this folder). Output: `vectors.json`, read by `AlgoTxnVectors` in
  `shared/src/test/java/app/lensly/core/algorand/`.
- Network values: `gen` `testnet-v1.0` and `gh` from the testnet block 0 `gen` and `gh` fields
  (`https://testnet-api.algonode.cloud/v2/blocks/0`).
- Rules the vectors test: Algorand "Canonical Msgpack" (https://specs.algorand.co/crypto/crypto-msgpack) and
  go-algorand's codec settings (`protocol/codec.go`).

`positive` entries are transactions the SDK encodes: `pay_basic`, `pay_note_close`, `axfer`, `axfer_optin`,
`pay_rekey`, and `axfer_full_16_keys` (every field set, so the map header is map16). Asset transfers on testnet use asset
10458941 (USDC). The later entries are opt-outs (`axfer_closeout`, `axfer_optout_to_other`), a transfer that also closes
the holding (`axfer_close_with_amount`), a clawback (`axfer_clawback`, `asnd`), an asset rekey (`axfer_rekey`), and
mainnet USDC (asset 31566704, `axfer_mainnet_usdc` and `axfer_optin_mainnet_usdc`), and an asset that no bundled catalog
names (`axfer_unknown_asset`, asset 1234567). Each one carries the fields it must
decode to and the SDK's `bytes_to_sign()`. The generator checks these against an independent msgpack read before it
writes the file.

`negative` entries are SDK output re-packed with msgpack's own packer, each breaking one rule: `unsorted_keys` (`fee`
and `fv` swapped), `zero_amount_present`, `zero_address_present`, `empty_note_present`, and `appl_noop` (an application
call).

Regenerate from the repository root:

    python3 -m venv "$VENV"
    "$VENV/bin/python" -m pip install py-algorand-sdk==2.12.0 msgpack==1.2.4
    "$VENV/bin/python" -I shared/src/test/resources/vectors/algorand-txn/gen_txn_vectors.py \
        > shared/src/test/resources/vectors/algorand-txn/vectors.json
