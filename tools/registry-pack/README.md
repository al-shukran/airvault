# registry-pack

Builds, verifies and signs the offline registry packages that the app installs from removable storage
(`Settings > Update catalog`). The app reader is `app/src/main/java/app/lensly/core/registry/update/`, and the
two must agree on the wire format (`LRP2`, see `PackageFormat.kt`).

```
./gradlew :tools:registry-pack:run --args="keygen --out <dir>"
./gradlew :tools:registry-pack:run --args="build --in <entries.json> --key <dir>/release.key --out <file.lrp>"
./gradlew :tools:registry-pack:run --args="verify --pub <dir>/release.pub <file.lrp>"
```

Paths are relative to the repository root. The private key (`release.key`) is encrypted, but keep it out of the
repository all the same, and keep its passphrase apart from it.

## Passphrase

`keygen` seals the private key under a passphrase, and `build` opens it with the same one. The passphrase comes from
the `LENSLY_REGISTRY_PASSPHRASE` environment variable when it is set. Otherwise the tool asks at the terminal, and
`keygen` asks twice. Gradle `run` has no terminal, so set the variable there:

```
LENSLY_REGISTRY_PASSPHRASE='...' ./gradlew :tools:registry-pack:run --args="build ..."
```

A passphrase has at least 12 characters. `release.key` is one line of hex: `LRK1`, a salt, a nonce, then the seed sealed with
AES-256-GCM. The AES key comes from scrypt (N = 2^17, r = 8, p = 1). A lost passphrase means a lost key.

## Release key

1. `keygen --out <dir>` writes `release.key` and `release.pub`, and prints the entry for the release key list.
2. Add that entry to `app/src/main/assets/registry/trusted-keys.json` with `"status": "active"`.
3. Sign packages with `build --key <dir>/release.key`.

Rotation: list the new key as `active` and sign with it, then set the old key's `status` to `revoked`. The app refuses a
package from a revoked key, including one already installed. The installed sequence stays recorded, so an older package
still cannot be installed.

The development key is listed in `app/src/debug/assets/registry/trusted-keys.debug.json`. Only debug builds read that
file. Never list a development key in the release file.

## Entries file

The entries file is the source a package is built from. Entries may appear in any order; the output is canonical.

| Field | Meaning |
| --- | --- |
| `formatVersion` | Must be `2`. |
| `minSchemaVersion` | Optional, default `1`. The catalog schema the package needs; `2` is the newest the app reads. A package with a `denylist` or `wallets` list needs `2`. |
| `sequence` | At least 1. The app refuses a package whose sequence is not higher than the last one it accepted. |
| `createdAt` | ISO date, `yyyy-mm-dd`. |
| `networks` | Networks the package adds. A package cannot add a testnet. |
| `tokens` | Tokens the package adds. Each token's chain must be baked into the app or added by this package. |
| `whitelist` | Optional. The contract calls that may be signed despite a Decode Gap (see below). |
| `denylist` | Optional. Known-dangerous requests that get a warning or a refusal (see below). Omitted, the app keeps its built-in list; `[]` clears it. |
| `wallets` | Optional. The Wallet Compatibility Registry: online wallets and their code formats (see below). Omitted, the app keeps its built-in list; `[]` clears it. |

The app applies a package's `whitelist`, `denylist` and `wallets` only when its `sequence` is higher than the built-in catalog's.
A list the package omits keeps the app's own.

### Denylist entries

A denylist entry marks a request as known-dangerous. Before signing, the app shows the reason on the review and asks the owner to
tick a check. A `BLOCK` entry refuses the request instead.

| Field | Meaning |
| --- | --- |
| `chainId` | Optional. Chain the entry applies to; omit it for every chain. Required when `contract` is set. |
| `contract` | Optional. Lowercase `0x` address the entry applies to. |
| `selector` | Optional. Lowercase `0x` and 8 hex digits: the Function Selector of the call. |
| `primaryType` | Optional. An EIP-712 primary type such as `Permit`. Typed data is not reviewed yet, so no transaction matches it. |
| `condition` | `ANY` (default), `UNLIMITED` (the amount is unlimited, for approve and increaseAllowance) or `APPROVED` (an operator grant of `true`, for setApprovalForAll). Needs a `selector`. |
| `severity` | `WARN` (the owner must confirm) or `BLOCK` (refused). |
| `reason` | Printable, at most 240 characters. Shown on the warning or the refusal. |

An entry names a `selector`, a `contract`, or a `primaryType`.

### Wallet entries

A wallet entry is a support statement, never a condition for signing. The pairing wizard shows it for the chosen wallet.

| Field | Meaning |
| --- | --- |
| `name` | The online wallet's name. Printable, at most 64 characters. Each name appears once. |
| `ecosystems` | At least one, such as `EVM`. |
| `formats` | At least one code format the vault reads and writes for this wallet, such as `ur:eth-sign-request`. |
| `note` | The support statement, printable, at most 240 characters. |
| `limitations` | The known limitations, printable, at most 240 characters. |

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
the canonical payload when the list is empty.

## Samples

- `samples/example-entries.json` is the source of `samples/example.lrp`. The sample is signed by the development key listed
  in the debug file. Its private half was discarded after signing, so the sample is not re-signed when the format changes:
  list a new development key in the debug file, then sign the sample with it.
- `samples/example-whitelist-entries.json` shows whitelist entries, with a plain call and a proxy call. Its contracts are
  placeholders. Build it with your own key to try the format; it is not a real approval of any contract.

## Tests

`./gradlew :tools:registry-pack:test` (also `scripts/ci/run-partition.sh registry-pack`) covers the round trip with a
passphrase, a wrong passphrase, tampering, invalid entries, and that the committed sample verifies with the debug key.
