# Ledger compatibility check

This guide checks that Lensly derives the same addresses a Ledger would, using software tools that implement the same derivation. You do not need a Ledger device.

Pass rule: on every chain, index 0 and index 4 must match between Lensly and the software tool. If they match on every chain, Ledger compatibility is accepted.

## Safety

- Use the public TEST seed only. It is the 24-word BIP39 test mnemonic, 23 times `abandon` then `art`, with no passphrase:
  `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art`
- Never type a real recovery phrase into software.
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

Algorand keys follow Ledger's own Ed25519 scheme (BOLOS `HDW_NORMAL`), not SLIP-0010. A SLIP-0010 tool gives different keys for the same phrase, so do not use one for Algorand.

## Verification status

| Chain | Tool | Status |
|---|---|---|
| Bitcoin | Sparrow Wallet | Verified. Sparrow's default Native Segwit path is `m/84'/0'/0'`, read from Sparrow's source (`ScriptType.P2WPKH`, drongo `protocol/ScriptType.java`, master, 2026-11-17). Address values were produced with `bip_utils`, which implements the same path. The Sparrow GUI was not driven in that run; the owner reproduces it. Sparrow release not pinned. |
| Ethereum | Ian Coleman's BIP39 tool (offline HTML) | Verified. In `src/js/index.js`, row i of the BIP32 table shows the custom path, then `/`, then i (`getDerivationPath() + "/" + index`), and its key is `derive(i)` of that path. Address values were produced with ethers 6.17.0. The standalone HTML was not driven in that run. |
| Solana | `solana-keygen` (Agave CLI 4.4.1) | Verified. `prompt://?key=N` gives `m/44'/501'/N'`. The Agave source describes `key` as the account index, and `derivation-path` builds `new_bip44(Some(N), None)` as `m/44'/501'/N'`. The pubkeys for N=0 and N=4 match an independent SLIP-0010 derivation of `m/44'/501'/N'`. |
| Algorand | Ledger's app-algorand in Speculos | Not checked. The app builds (see the Algorand section). The Speculos run and the address read-out have not been done, so Algorand blocks acceptance. |

## Bitcoin: Sparrow Wallet

Tool: Sparrow Wallet. Pin the release you use and record it in the results table. Source: https://sparrowwallet.com/docs/quick-start.html

Path: `m/84'/0'/0'`. Sparrow's `ScriptType.P2WPKH` default is `m/84'/0'/0'` on mainnet (it swaps `/0'/0'` for `/1'/0'` on other networks). Receive address i is `m/84'/0'/0'/0/i`, so you do not need to change the path.

Steps:
1. Start Sparrow with airplane mode on.
2. Click New Wallet, name it `ledger-check`, and keep the default script type, Native Segwit (P2WPKH).
3. Choose New or Imported Software Wallet.
4. Choose Mnemonic Words (BIP39). Choose 24 words.
5. Enter the 24 test words. Sparrow shows "Valid checksum".
6. Click Create Keystore and check the derivation path shows `m/84'/0'/0'`.
7. Click Import Keystore, then Apply. Set a throwaway password.
8. Open the Addresses tab. Read the receive addresses at index 0 and index 4 (`m/84'/0'/0'/0/0` and `m/84'/0'/0'/0/4`).

Testnet4: this check uses mainnet. Lensly's testnet4 path uses coin type 1 (`m/84'/1'/0'`). Sparrow's testnet4 support is not verified, so testnet4 is not covered.

## Ethereum: Ian Coleman's BIP39 tool

Tool: Ian Coleman's BIP39 tool, run offline. Source: https://github.com/iancoleman/bip39 (download `bip39-standalone.html` from the releases page; the README says the standalone file works offline).

Steps:
1. With airplane mode on, open `bip39-standalone.html` in a browser.
2. Enter the 24 test words in the mnemonic field, with no passphrase.
3. Choose Ethereum as the coin.
4. Open the BIP32 tab. Choose "Custom derivation path" and set the path to `m/44'/60'/4'/0`. This is account 4 of Ledger Live.
5. The first row of the table should show the path `m/44'/60'/4'/0/0`. Row i shows the custom path, then `/`, then i. Check the row path before you read the address. If the row path shows anything else, stop and do not record a result.
6. Read the address on that row. For account 0, set the path to `m/44'/60'/0'/0` and read row 0 (`m/44'/60'/0'/0/0`).

Legacy note: Ledger Legacy uses `m/44'/60'/0'/0/i`, which Lensly does not derive. Compare only against the Ledger Live path. If a Ledger shows a different Ethereum address, confirm the account was added in Ledger Live.

Rabby: a GridPlus doc says Rabby can switch the seed import to a Ledger Live HD path, but that is a third-party page, not Rabby's documentation. It is not used in this guide.

## Solana: solana-keygen

Tool: the Solana CLI (`solana-keygen`, Agave 4.4.1 was used). Install from https://docs.anza.xyz/cli/install. Run the commands offline after install.

Steps:
1. With airplane mode on, run `solana-keygen pubkey 'prompt://?key=0'`.
2. At `seed phrase:`, enter the 24 test words. At the passphrase prompt, press Enter with nothing typed. The output is the account 0 address (the base58 pubkey).
3. Repeat with `key=4` for account 4.
4. Do not use `key=0/0`. That is a different key, `m/44'/501'/0'/0'`.

No key file is written, because `pubkey` only prints the address. If you used `recover --outfile` earlier, delete that file.

Compare the two pubkeys with Lensly's Solana addresses for accounts 0 and 4.

## Algorand: Ledger's app-algorand in Speculos

Status: not checked. Lensly's values are recorded below, but the Speculos run has not been done, so no address has been read from app-algorand yet.

Tool: Speculos, Ledger's emulator, running Ledger's app-algorand. This is the same app code a Ledger runs. Speculos is at https://github.com/LedgerHQ/speculos. The app is at https://github.com/LedgerHQ/app-algorand. Speculos is an emulator, and its README warns that its behaviour is not guaranteed to match hardware.

Why this route: no software wallet was found that implements the Ledger Algorand path. Lensly's own doc says xHD wallets, including Pera, show different addresses.

Verified in this run:
- Clone `https://github.com/LedgerHQ/app-algorand` and check out `develop` (commit 62adedc was used). Then run `git submodule update --init --recursive --depth 1`. Without the submodules, `make` stops with `ICONNAME is not set`.
- Build inside the builder image (arm64 on an M1 Mac, no emulation needed):
  `docker run --rm --user "$(id -u):$(id -g)" -v "$(pwd -P):/app" ghcr.io/ledgerhq/ledger-app-builder/ledger-app-dev-tools:latest bash -c "cd /app && make DEBUG=1"`
  This exits 0 and writes `app/build/nanos2/bin/app.elf`. The build also prints two "must have indexed colors" lines for the apex icon; they do not fail the build.
- `--seed` takes a BIP39 mnemonic or a `hex:` seed (`speculos/main.py`). The APDU server listens on `0.0.0.0` at `--apdu-port` (default 9999).
- The APDU framing, from `speculos/mcu/apdu.py`: a request is a 4-byte big-endian length followed by the APDU. A reply is a 4-byte big-endian data length, then the data, then the 2-byte status word.
- GET PUBLIC KEY is CLA `0x80`, INS `0x03`, P1 `0x00`, P2 `0x00`. Its data is the account number as a 4-byte big-endian integer (`pack_account_id` in `tests/utils.py`), not a BIP32 path. The app builds `m/44'/283'/N'/0/0` itself.
- Address encoding: the Algorand address is base32 of the 32-byte public key followed by the last 4 bytes of SHA-512/256 of that key, without padding. This reproduces Lensly's addresses from Lensly's public keys (checked for accounts 0 and 4).

Lensly's public keys for the public mnemonic (from `LedgerEd25519.algorandAccount`):
- Account 0: `7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538`
- Account 4: `816cf2a86f72eb9d812f563eb4466aa30b179dc7b1f8e3e8840f06f40210b7dc`

Not verified yet (the remaining steps for this check):
1. Start Speculos with airplane mode on. The Speculos image was pulled, but was not started in this run. Use `--model nanosp --display headless --apdu-port 9999 --seed "<TEST_SEED>"` and the built `app.elf`. Confirm the container entrypoint before you rely on this.
2. Send GET PUBLIC KEY for account 0 and account 4 to port 9999, with the framing above.
3. Compare the returned public keys with the two keys above, then convert them to addresses and compare with Lensly's addresses.

Until steps 1 to 3 pass, Algorand index 0 and index 4 are "not checked".

## Ethereum legacy path note

Ledger Legacy uses `m/44'/60'/0'/0/i`. Lensly does not derive that path. If a Ledger shows a different Ethereum address, confirm the account was added in Ledger Live.

## Known-answer check (public test mnemonic)

The owner reproduces this table first, with the public 24-word test mnemonic, using the steps above. The addresses are public, so they are shown in full. Every row must show ✓ on both index 0 and index 4.

| Chain | Path | Lensly, idx 0 | Tool, idx 0 | Lensly, idx 4 | Tool, idx 4 | Match |
|---|---|---|---|---|---|---|
| Bitcoin (native segwit) | `m/84'/0'/0'/0/i` | `bc1qzmtrqsfuaf6l6kkcsseumq26ukaphfj9skkug6` | `bc1qzmtrqsfuaf6l6kkcsseumq26ukaphfj9skkug6` | `bc1qmml5thvhpyvqjfhj7hfrhqsd4lm86vn8dh4v02` | `bc1qmml5thvhpyvqjfhj7hfrhqsd4lm86vn8dh4v02` | ✓ |
| Ethereum (Ledger Live) | `m/44'/60'/N'/0/0` | `0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb` | `0xF278cF59F82eDcf871d630F28EcC8056f25C1cdb` | `0x290C2D6fa111595Df8A636d36763bFe4cD8D9e6F` | `0x290C2D6fa111595Df8A636d36763bFe4cD8D9e6F` | ✓ |
| Solana | `m/44'/501'/N'` | `4BZp4ci5rhNYqbayj1uppeTas1osK2Q4b74x7UENC5Hd` | `4BZp4ci5rhNYqbayj1uppeTas1osK2Q4b74x7UENC5Hd` | `D1FuzHHPZsU4GeFbcnM8ReDBUaXmoG1F2iVgp3CN8hxj` | `D1FuzHHPZsU4GeFbcnM8ReDBUaXmoG1F2iVgp3CN8hxj` | ✓ |
| Algorand | `m/44'/283'/N'/0/0` | `OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ` | not checked | `QFWPFKDPOLVZ3AJPKY7LIRTKUMFRPHOHWH4OH2EEB4DPIAQQW7OBI2YXUY` | not checked | not checked |

Lensly's values came from its own derivation code on the public mnemonic (`HdWallet`, `BtcAccount.fromSeed(..., NATIVE_SEGWIT, 0).receiveAddress`, `SolanaAccount.address`, `LedgerEd25519.algorandAccount` with `AlgoAddress.encode`). The Ethereum index 0 value is the one in `AccountAddressesTest`.

## Results (owner's test seed)

Use your own test seed, not the public one, and enter `<TEST_SEED>` in your notes. Fill each cell with the first 10 characters of the address, then mark ✓ or ✗. Use "not checked" where a step was not run.

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
