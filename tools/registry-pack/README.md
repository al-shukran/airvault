# registry-pack

Builds, verifies and signs the offline registry packages that the application installs from removable storage
(`Settings > Update catalog`). The application reader is `shared/src/main/java/app/lensly/core/registry/update/`, and the
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
2. Add that entry to `shared/src/main/assets/registry/trusted-keys.json` with `"status": "active"`.
3. Sign packages with `build --key <dir>/release.key`.

Rotation: list the new key as `active` and sign with it, then set the old key's `status` to `revoked`. The application refuses a
package from a revoked key, including one already installed. The installed sequence stays recorded, so an older package
still cannot be installed.

The release key in use is pinned in `shared/src/main/assets/registry/trusted-keys.json`. Its passphrase is in the macOS keychain
(service `lensly-registry-release`, account `registry-release`); its key file and fingerprint are in the keys folder outside the repository.

An offline backup key is pinned beside it, also `active`, so that a lost release key does not end catalog updates. Its passphrase is in
the macOS keychain (service `lensly-registry-backup`, account `registry-backup`); its key file and fingerprint are in the keys folder
outside the repository.

## Shipped bundle

`shared/src/main/assets/registry/bundled.lrp` is the signed package the application ships with its approved contracts. Build it with:

```
tools/registry-pack/sign-candidate.sh --sequence <N> --key <dir>/release.key [--created yyyy-mm-dd]
```

The script keeps only the entries listed in `tools/registry-data/approved-contracts/verified-selectors.txt` from
`candidate-whitelist.json`, writes the entries file in a scratch directory, builds and verifies the package, and refuses a key that is
not pinned as active. Each line of the selection must match exactly one candidate entry. Bump `--sequence` whenever the content
changes: the application installs the bundle on first load (`RegistryHolder`), through the same checks as an offline update, and an
equal or older sequence is refused.

The development key is listed in `shared/src/debug/assets/registry/trusted-keys.debug.json`. Only debug builds read that
file. Never list a development key in the release file.

## Entries file

The entries file is the source a package is built from. Entries may appear in any order; the output is canonical.

| Field | Meaning |
| --- | --- |
| `formatVersion` | Must be `2`. |
| `minSchemaVersion` | Optional, default `1`. The catalog schema the package needs; `3` is the newest the application reads. A package with a `denylist` or `wallets` list needs `2`. A package with a whitelist argument that is dynamic, an array or a tuple, or that names tuple fields, needs `3`. |
| `sequence` | At least 1. The application refuses a package whose sequence is not higher than the last one it accepted. |
| `createdAt` | ISO date, `yyyy-mm-dd`. |
| `networks` | Networks the package adds. A package cannot add a testnet. |
| `tokens` | Tokens the package adds. Each token's chain must be baked into the application or added by this package. |
| `whitelist` | Optional. The contract calls that may be signed despite a Decode Gap (see below). Omitted, the application keeps its built-in list; `[]` clears it. |
| `denylist` | Optional. Known-dangerous requests that get a warning or a refusal (see below). Omitted, the application keeps its built-in list; `[]` clears it. |
| `wallets` | Optional. The Wallet Compatibility Registry: online wallets and their code formats (see below). Omitted, the application keeps its built-in list; `[]` clears it. |

The application applies a package's `whitelist`, `denylist` and `wallets` only when its `sequence` is higher than the built-in catalog's.
A list the package omits keeps the application's own.

### Denylist entries

A denylist entry marks a request as known-dangerous. Before signing, the application shows the reason on the review and asks the owner to
tick a check. A `BLOCK` entry refuses the request instead.

| Field | Meaning |
| --- | --- |
| `chainId` | Optional. Chain the entry applies to; omit it for every chain. Required when `contract` is set. |
| `contract` | Optional. Lowercase `0x` address the entry applies to. |
| `selector` | Optional. Lowercase `0x` and 8 hex digits: the Function Selector of the call. |
| `primaryType` | Optional. An EIP-712 primary type such as `Permit`. It matches typed data of that type, never a transaction, and cannot be combined with `selector`. |
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

A whitelist entry approves one contract call that Lensly cannot decode. The application signs such a call only when its
chain, contract and selector match an entry here. Its calldata is decoded from `params` and shown in full, and it is signed only
when re-encoding the decoded values gives back the calldata exactly. Each offset, length and count must lie inside the calldata,
and a decode that would read more values than the limit is refused. A `bytes` or `string` argument, an array and a tuple are
laid out as the Solidity ABI lays them out, and they need `minSchemaVersion` 3.

| Field | Meaning |
| --- | --- |
| `chainId` | Chain the entry applies to. It must be baked into the application or added by this package. |
| `contract` | Lowercase `0x` address of the contract the call goes to. |
| `selector` | Lowercase `0x` and 8 hex digits. Must be the Keccak-256 selector of `signature` (first four bytes). |
| `signature` | The canonical ABI signature, such as `swapExactTokensForTokens(uint256,uint256,address[],address,uint256)`. Each type is spelled in its canonical form: `uint256` and never `uint`, `address[]`, `bytes32[2]`, and `(address,uint24)` with no spaces. Types nest at most 8 levels, and a fixed array holds at most 512 items. |
| `params` | One entry per argument, in signature order. Each has a `name` (printable, at most 64 characters), a `type` (equal to that argument's type in the signature), an optional `tokenRef` (default `false`, and `true` only for a top-level `uint`, which is then shown as a quantity of the contract's token), and optional `components` (schema 3). A tuple argument may name its fields in `components`: one entry per field, in field order, each with its own `name`, `type`, and `components` when that field is a tuple. |
| `meaning` | What the call does, in plain words. Printable, at most 240 characters. It begins with the protocol name and a colon, such as `Aave V3: deposit an asset into the pool.`; the application lists the call under that name. |
| `proxy` | `null` for a contract that is not an upgradeable proxy. Otherwise `{ "implementationAddress": ..., "implementationCodeHash": ... }`, with at least one of the two set. |

Each (`chainId`, `contract`, `selector`) appears once. A package's whitelist replaces the whitelist of the application as a
whole. Omitted, the application keeps its built-in whitelist, and `[]` clears it. The `whitelist` key is written only when the
entries file has one.

## Samples

- `samples/example-entries.json` is the source of `samples/example.lrp`. The sample is signed by the development key listed
  in the debug file. Its private half was discarded after signing, so the sample is not re-signed when the format changes:
  list a new development key in the debug file, then sign the sample with it.
- `samples/example-whitelist-entries.json` shows whitelist entries, with a plain call and a proxy call. Its contracts are
  placeholders. Build it with your own key to try the format; it is not a real approval of any contract.

## Tests

`./gradlew :tools:registry-pack:test` (also `scripts/ci/run-partition.sh registry-pack`) covers the round trip with a
passphrase, a wrong passphrase, tampering, invalid entries, and that the committed sample verifies with the debug key.
