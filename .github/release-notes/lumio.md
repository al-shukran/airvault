# Lumio

AirVault is the air gap plus a vault. Lensly is the AirVault vault: it sees the outside world only through its camera lens, by scanning QR codes, and never through a network. Lumio comes from lumen, light: it is the online side of AirVault.

Lumio shows accounts and balances and broadcasts signed transactions. It never sees a private key.

## How to Verify This Release

Run these in the folder that holds the APK. Replace `<apk>` with the APK file name and `<owner/repo>` with the repository name.

1. Compare the APK with the published checksum:

   ```sh
   sha256sum -c SHA256SUMS
   ```

2. Check the build provenance attestation, which shows that GitHub Actions built this APK from this repository:

   ```sh
   gh attestation verify <apk> --repo <owner/repo>
   ```

3. Check the signing certificate. The `Signer #1 certificate SHA-256 digest` line must match the signing certificate SHA-256 under Build below:

   ```sh
   apksigner verify --print-certs <apk>
   ```

## Scam Warning

> **AirVault does not have, and never intends to launch, any cryptocurrency, token, coin, airdrop, ICO or presale.**

> Anyone who contacts you on behalf of AirVault, Lensly, Lumio or [alshukran.com](https://alshukran.com) is a scammer, 100%. This is especially true for anyone who asks for a seed phrase or recovery phrase, a private key, a password, or any kind of payment. Never share these with anyone.
