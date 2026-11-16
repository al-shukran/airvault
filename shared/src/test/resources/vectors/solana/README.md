# Solana message vectors

- Generator: `solders` 0.29.0 (PyPI, Rust-backed Solana SDK bindings), Python 3.12.0. Chosen because it is a separate
  implementation from the Lensly parser.
- Retrieved: 2026-11-16.

`vectors.txt` holds one serialized message per row: `name<TAB>hex`. Each message is what a signer signs, with no signatures. A legacy
message starts with its header (first byte below 0x80). A v0 message starts with the 0x80 version prefix.

Inputs, so the fixtures can be regenerated:

- Keys are `SHA-256(label)`, 32 bytes each. They do not have to be on the curve, because a message does not check that.
  Labels: `payer`, `recipient`, `lookup-table`, `alt-0`, `alt-2`, `unknown-program`, `source-token-account`, `destination-ata`,
  `owner`, `mint-usdc`, `mint-token-2022`, `owner-2022`, `ata-2022`, `source-2`, `destination-2`.
- Recent blockhash: `SHA-256("lensly wi5 blockhash")`.
- Program IDs are the well-known base58 strings. solders checks them as 32-byte keys.

The vectors are built with solders' `Message.new_with_blockhash` (legacy) and `MessageV0.try_compile` (v0). Each serialized message is
checked against solders' `VersionedTransaction` serialization, which carries it after a one-byte signature count and a placeholder
64-byte signature (`VersionedTransaction.populate`). solders' v0 `bytes()` is the body only, so the 0x80 prefix is added to it, and the
result is checked against the transaction tail.

| Name | Shape |
|---|---|
| `legacy-sol-transfer` | Legacy. System transfer of 1.5 SOL, payer to recipient. |
| `v0-sol-transfer-alt` | v0. System transfer of 2.5M lamports. The recipient is loaded from one lookup table, at writable index 1. |
| `v0-alt-readonly-raw` | v0. A transfer to an ALT writable account, plus an unknown program that reads an ALT readonly account. |
| `legacy-spl-checked-ata-compute` | Legacy. Compute limit and price, ATA CreateIdempotent, classic Token TransferChecked (1,234,567 units, 6 decimals). |
| `v0-token-programs` | v0 with no lookups. ATA Create (empty data) under Token-2022, Token-2022 TransferChecked (42, 9 decimals), classic Token Transfer (7). |
| `legacy-unknown-program` | Legacy. A transfer, and an unknown program with data `deadbeef0001`. |
| `legacy-max-size` | Legacy, exactly 1232 bytes (the largest accepted). |
| `legacy-over-size` | Legacy, 1233 bytes (one byte over the limit). |

The negative cases are made in the tests from these vectors by changing one byte, and the test says which byte.
