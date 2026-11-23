<p align="center"><img src="docs/brand/airvault-mark.svg" width="96" alt="AirVault mark"></p>

# AirVault

## Scam Warning

> **AirVault does not have, and never intends to launch, any cryptocurrency, token, coin, airdrop, ICO or presale.**

> Anyone who contacts you on behalf of AirVault, Lensly, Lumio or [alshukran.com](https://alshukran.com) is a scammer, 100%. This is especially true for anyone who asks for a seed phrase or recovery phrase, a private key, a password, or any kind of payment. Never share these with anyone.

AirVault is a crypto wallet split across two Android apps. Lensly keeps the keys offline and signs transactions. Lumio, its online companion, tracks accounts and balances and broadcasts transactions.

AirVault is created by [alshukran.com](https://alshukran.com).

## The Names

AirVault is the air gap plus a vault. It is the project and system name, and it is never an app on the phone.

Lensly is the AirVault vault. It sees the outside world only through its camera lens, by scanning QR codes, and never through a network. It holds the keys and signs.

Lumio comes from *lumen*, the Latin word for light. It is the online side of AirVault. It shows accounts and balances, and it carries signed results out to the network. It never sees a private key.

Lensly and Lumio work as a pair over QR codes in both directions. Lumio sends requests to Lensly as QR codes, and Lensly returns signed results to Lumio as QR codes.

## Apps

Both apps come from one code base.

- **Lensly** (`app/`) is the offline vault and signer. It holds the seed, parses requests, shows the review, and signs.
- **Lumio** (`lumio/`) is the online companion wallet. It tracks accounts and balances, builds transactions, and broadcasts them.

## Data Loss

Lensly erases the vault if it cannot verify the vault's state, for example after a storage or Keystore fault or a power cut. It does this before it checks your password. Keep a separate recovery phrase for every wallet.

## Supported Chains

AirVault supports Bitcoin, Ethereum and other EVM networks, Solana, and Algorand. Each chain family is a separate chain app with `common`, `signer` and `wallet` modules.

## Repository Layout

| Path | Contents |
|---|---|
| `app/` | Lensly, the offline vault and signer. |
| `lumio/` | Lumio, the online companion wallet. |
| `shared/` | Code both apps use: crypto, UR/QR transport, the parser sandbox, and the address book. |
| `core/` | Chain-neutral interfaces (`model`, `signer-api`, `wallet-api`) and the `conformance` test kit. |
| `chains/` | One chain app per family: `algorand`, `bitcoin`, `evm`, `solana`. `_template` is a stub to copy when a new chain is added. |
| `abi/` | ABI decoder, a pure JVM module. |
| `benchmark/` | Macrobenchmark module that measures the installed app from outside. |
| `tools/` | Helper tools for the screen gallery, icons, registry data, and registry packages. |
| `docs/` | Chain app guide, Ledger recovery guide, and testing docs. |
| `scripts/` | CI and helper scripts. |
| `gradle/` | Gradle wrapper and version catalog (`libs.versions.toml`). |
| `licenses/` | Font licenses and third-party notices. |
| `.github/workflows/` | CI workflows. |

## Build and Test

You need a JDK and the Android SDK.

```bash
# Debug builds
./gradlew :app:assembleDebug       # Lensly
./gradlew :lumio:assembleDebug     # Lumio

# Unit tests
./gradlew :shared:testDebugUnitTest
./gradlew :app:testDebugUnitTest
./gradlew :lumio:testDebugUnitTest
```

To run one CI partition locally, use `scripts/ci/run-partition.sh <partition>`. See [docs/testing/README.md](docs/testing/README.md) for the partition names.

## Documentation

- [Creating AirVault chain apps](docs/creating-airvault-apps.md): how a blockchain becomes a chain app, and what each chain app must provide.
- [Recover your wallet with a Ledger](docs/recover-with-ledger.md): move a Lensly wallet to a Ledger, and a Ledger wallet back to Lensly.
- [Testing](docs/testing/README.md): test layers, CI design, and local commands.

## Status

AirVault is pre-release. No release has been published yet.

## License

Apache License 2.0. See [LICENSE](LICENSE).
