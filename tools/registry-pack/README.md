# registry-pack

Builds, verifies and signs the offline registry packages that the app installs from removable storage
(`Settings > Update catalog`). The app reader is `app/src/main/java/app/lensly/core/registry/update/`, and the
two must agree on the wire format.

```
./gradlew :tools:registry-pack:run --args="keygen --out <dir>"
./gradlew :tools:registry-pack:run --args="build --in <entries.json> --key <release.key> --out <file.lrp>"
./gradlew :tools:registry-pack:run --args="verify --pub <release.pub> <file.lrp>"
```

Paths are relative to the repository root. `keygen` writes a private seed (`release.key`, mode 600) and its public key.
Keep the private seed out of the repository.

## Entries file

The entries file is the source a package is built from. Entries may appear in any order; the output is canonical.

| Field | Meaning |
| --- | --- |
| `formatVersion` | Must be `1`. |
| `sequence` | At least 1. The app refuses a package whose sequence is not higher than the installed one. |
| `createdAt` | ISO date, `yyyy-mm-dd`. |
| `networks` | Networks the package adds. A package cannot add a testnet. |
| `tokens` | Tokens the package adds. Each token's chain must be baked into the app or added by this package. |
| `whitelist` | Optional. The contract calls that may be signed despite a Decode Gap (see below). |

### Whitelist entries

A whitelist entry approves one contract call that Lensly cannot decode. The app signs such a call only when its
chain, contract and selector match an entry here.

| Field | Meaning |
| --- | --- |
| `chainId` | Chain the entry applies to. It must be baked into the app or added by this package. |
| `contract` | Lowercase `0x` address of the contract the call goes to. |
| `selector` | Lowercase `0x` and 8 hex digits. Must be the Keccak-256 selector of `signature` (first four bytes). |
| `signature` | The ABI signature, such as `swapExactTokensForTokens(uint256,uint256,address[],address,uint256)`. |
| `meaning` | What the call does, in plain words. Printable, at most 240 characters. |
| `proxy` | `null` for a contract that is not an upgradeable proxy. Otherwise `{ "implementationAddress": ..., "implementationCodeHash": ... }`, with at least one of the two set. |

Each (`chainId`, `contract`, `selector`) appears once. A package's whitelist replaces the whitelist of the app as a
whole, and an empty or missing `whitelist` leaves the app with no whitelisted calls. The `whitelist` key is omitted from
the canonical payload when the list is empty, so a package without one has the same bytes as before the whitelist existed.

## Samples

- `samples/example-entries.json` is the source of `samples/example.lrp`, which is signed with the baked release key.
  Its private half is not in the repository, so the sample is not re-signed when the format changes.
- `samples/example-whitelist-entries.json` shows whitelist entries, with a plain call and a proxy call. Its contracts are
  placeholders. Build it with your own key to try the format; it is not a real approval of any contract.

## Tests

`./gradlew :tools:registry-pack:test` (also `scripts/ci/run-partition.sh registry-pack`) covers the round trip, tampering,
invalid entries, and that the committed sample still verifies.
