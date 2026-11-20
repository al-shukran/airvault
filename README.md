# AirVault

## Scam Warning

> **AirVault does not have, and never intends to launch, any cryptocurrency, token, coin, airdrop, ICO or presale.**

> Anyone who contacts you on behalf of AirVault, Lensly, Lumio or [alshukran.com](https://alshukran.com) is a scammer, 100%. This is especially true for anyone who asks for a seed phrase or recovery phrase, a private key, a password, or any kind of payment. Never share these with anyone.

AirVault is a crypto wallet split across two Android apps. Lensly keeps the keys offline and signs transactions. Lumio, its online companion, tracks accounts and balances and broadcasts transactions.

AirVault is created by [alshukran.com](https://alshukran.com).

## Apps

Both apps come from one code base.

- **Lensly** (`app/`) is the offline vault and signer. It holds the seed, parses requests, shows the review, and signs. It never touches a network.
- **Lumio** (`lumio/`) is the online companion wallet. It tracks accounts and balances, builds transactions, and broadcasts them. It never sees a private key.

The apps talk only through QR codes.

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
