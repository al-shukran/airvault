# Ledger compatibility check

This guide checks that Lensly derives the same addresses a Ledger would, using software tools that implement the same derivation. You do not need a Ledger device.

Pass rule: on every chain, index 0 and index 4 must match between Lensly and the software tool. If they match on every chain, Ledger compatibility is accepted.

## Safety

- Use the public TEST seed only: `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about`. Never type a real recovery phrase into software.
- Turn on airplane mode before you enter the test seed, and keep it on until you finish.
- Do not send funds to any address from this check. The seed is public, so anything sent to these addresses can be taken.
- Never write a real seed into a file. Use `<TEST_SEED>` as a placeholder in your notes.

## What Lensly derives

"Index 4" means account 4, except for Bitcoin, where it means receive address 4 of account 0. The paths come from `shared/src/main/java/app/lensly/core/btc/ScriptType.kt`, `core/wallet/HdWallet.kt`, `core/solana/SolanaAccount.kt` and `core/algorand/LedgerEd25519.kt`.

| Chain | Index 0 | Index 4 | Path pattern |
|---|---|---|---|
| Bitcoin, native segwit (mainnet) | receive address 0 of account 0 | receive address 4 of account 0 | `m/84'/0'/0'/0/i` |
| Ethereum (Ledger Live) | account 0 | account 4 | `m/44'/60'/N'/0/0` |
| Solana | account 0 | account 4 | `m/44'/501'/N'` |
| Algorand (Ledger Algorand app) | account 0 | account 4 | `m/44'/283'/N'/0/0` |

Where to read them in Lensly: the chain's accounts screen shows the address for account 0 and account 4. For Bitcoin, read the receive address with index 4.

## Verification status

| Chain | Tool | Status |
|---|---|---|
| Bitcoin | Sparrow Wallet | Mnemonic import verified in the Sparrow quick start. The default path `m/84'/0'/0'` is from a third-party guide. Version not pinned. |
| Ethereum | Ian Coleman's BIP39 tool (offline HTML) | Offline use verified in the README. The BIP32 tab has an editable path and a Custom option (source `src/index.html`). Whether the row index is appended to the path is NOT verified. |
| Solana | `solana-keygen` (Agave CLI) | NOT verified. The Agave docs say `key=N` is the account index, hardened, but the code that turns it into a path was not readable. |
| Algorand | Ledger's app-algorand in Speculos | Build and run commands verified from the app README. `--seed` verified in `speculos/main.py`. The APDU call and address read-out are NOT verified end to end. |

Algorand and Solana cannot pass yet. Mark them "not checked" until you confirm them.

## Bitcoin: Sparrow Wallet

Tool: Sparrow Wallet. Pin the release you use and record it in the results table. Source: https://sparrowwallet.com/docs/quick-start.html

Path: `m/84'/0'/0'`. This is Sparrow's default for Native Segwit, per the Asigna Sparrow setup guide (https://asigna.gitbook.io/asigna/bitcoin-multisig/recovery/software-wallet-setup-in-sparrow). Receive address i is `m/84'/0'/0'/0/i`, so you do not need to change the path.

Steps:
1. Start Sparrow with airplane mode on.
2. Click New Wallet, name it `ledger-check`, and keep the default script type, Native Segwit (P2WPKH).
3. Choose New or Imported Software Wallet.
4. Choose Mnemonic Words (BIP39). Click Enter 24 Words, then Enter 12 Words.
5. Enter the 12 test words. Sparrow shows "Valid checksum".
6. Click Create Keystore and check the derivation path shows `m/84'/0'/0'`.
7. Click Import Keystore, then Apply. Set a throwaway password.
8. Open the Addresses tab. Read the receive addresses at index 0 and index 4 (`m/84'/0'/0'/0/0` and `m/84'/0'/0'/0/4`).

Testnet4: this check uses mainnet. Lensly's testnet4 path uses coin type 1 (`m/84'/1'/0'`). Sparrow's testnet4 support is not verified, so testnet4 is not covered.

## Ethereum: Ian Coleman's BIP39 tool

Tool: Ian Coleman's BIP39 tool, run offline. Source: https://github.com/iancoleman/bip39 (download `bip39-standalone.html` from the releases page; the README says the standalone file works offline).

Steps:
1. With airplane mode on, open `bip39-standalone.html` in a browser.
2. Enter the 12 test words in the mnemonic field, with no passphrase.
3. Choose Ethereum as the coin.
4. Open the BIP32 tab. Choose "Custom derivation path" and set the path to `m/44'/60'/4'/0`. This is account 4 of Ledger Live.
5. The first row of the table should show the path `m/44'/60'/4'/0/0`. Check that before you read the address. If the row path shows anything else, stop and do not record a result.
6. Read the address on that row. For account 0, repeat with `m/44'/60'/0'/0` and read row 0.

Not verified: how the tool builds row paths from the BIP32 path. Step 5 is the check for that.

Legacy note: Ledger Legacy uses `m/44'/60'/0'/0/i`, which Lensly does not derive. Compare only against the Ledger Live path. If a Ledger shows a different Ethereum address, confirm the account was added in Ledger Live.

Rabby: a GridPlus doc says Rabby can switch the seed import to a Ledger Live HD path, but that is a third-party page, not Rabby's documentation. It is not used in this guide.

## Solana: solana-keygen

Tool: the Solana CLI (`solana-keygen`). Install from https://docs.anza.xyz/cli/install. Run the commands offline after install.

NOT verified: that `key=N` maps to `m/44'/501'/N'`. The Agave docs and `clap-v3-utils` describe `key=` as the account index, hardened, but the code that builds the path was not readable. A third-party mirror shows `key=0/0` as `m/44'/501'/0'/0'`. Treat the mapping as a hypothesis until step 5 passes.

Steps:
1. With airplane mode on, run `solana-keygen recover 'prompt://?key=0' --outfile /tmp/ledger-check-0.json` and enter the 12 test words when prompted.
2. Run `solana-keygen pubkey /tmp/ledger-check-0.json`. This is the account 0 address.
3. Repeat with `key=4` and `/tmp/ledger-check-4.json` for account 4.
4. Delete both files: `rm /tmp/ledger-check-0.json /tmp/ledger-check-4.json`.
5. Confirm the mapping before you rely on it. Run `solana-keygen recover --help` and read the `prompt:` section. If it does not state that `key=N` is `m/44'/501'/N'`, mark Solana "not checked".

Compare the two pubkeys with Lensly's Solana addresses for accounts 0 and 4.

## Algorand: Ledger's app-algorand in Speculos

Tool: Speculos, Ledger's emulator, running Ledger's app-algorand. This is the same app code a Ledger runs. Speculos is at https://github.com/LedgerHQ/speculos. The app is at https://github.com/LedgerHQ/app-algorand.

Why this route: no software wallet was found that implements the Ledger Algorand path. Lensly's own doc says xHD wallets, including Pera, show different addresses.

Verified:
- Speculos takes `--seed` as a BIP39 mnemonic (or `hex:` prefix for a hex seed), from `speculos/main.py`.
- app-algorand builds with the `ghcr.io/ledgerhq/ledger-app-builder/ledger-app-dev-tools` image and `make DEBUG=1`, and its README says to run `speculos --model nanosp app/build/nanos2/bin/app.elf`.

Steps (Linux or macOS with Docker):
1. Clone app-algorand: `git clone https://github.com/LedgerHQ/app-algorand` and check out `develop`.
2. Build it inside the builder image, following the README's `docker run` block (it mounts the repo at `/app`). Then run `make DEBUG=1`. The output is `app/build/nanos2/bin/app.elf`.
3. Install Speculos with `pip install speculos` (or use its Docker image), then run `speculos --model nanosp app/build/nanos2/bin/app.elf --seed "<TEST_SEED>"` with airplane mode on.
4. Read the address for account 0 and account 4. The app-algorand test client (`tests/application_client/algorand_command_sender.py`) sends GET PUBLIC KEY with CLA `0x80`, INS `0x03`, P1 `0x00`, P2 `0x00`, and data built by `pack_account_id`. Use its `get_public_key(account_id=N)` through the same Speculos APDU port (default 9999).

NOT verified: the exact Python connection to the Speculos APDU port, and how `pack_account_id` encodes the path. The test client uses Ragger. Run `pytest tests/` with the README's `--device` option to confirm the address, rather than hand-building APDUs.

Status: Algorand index 0 and index 4 are "not checked" until steps 2 to 4 produce addresses. Speculos is an emulator, and its README warns that its behaviour is not guaranteed to match hardware.

## Ethereum legacy path note

Ledger Legacy uses `m/44'/60'/0'/0/i`. Lensly does not derive that path. If a Ledger shows a different Ethereum address, confirm the account was added in Ledger Live.

## Results

Fill each cell with the first 10 characters of the address, then mark ✓ or ✗. Use "not checked" where a step was not run.

| Chain | Tool and version | Lensly, index 0 | Tool, index 0 | Match 0 | Lensly, index 4 | Tool, index 4 | Match 4 |
|---|---|---|---|---|---|---|---|
| Bitcoin (native segwit) | Sparrow ____ | | | | | | |
| Ethereum (Ledger Live) | Ian Coleman tool ____ | | | | | | |
| Solana | solana-keygen ____ | | | | | | |
| Algorand | app-algorand in Speculos ____ | | | | | | |

Accept Ledger compatibility only when every row shows ✓ for index 0 and index 4. A "not checked" row blocks acceptance.

## After the check

- Clear the clipboard and close the tools.
- Turn airplane mode off.
- Delete any files you created and keep no results files with seeds.
