# Recover your wallet with a Ledger

This guide moves a Lensly wallet to a Ledger, and a Ledger wallet to Lensly.

## What you need

- Your 24-word Lensly recovery phrase.
- Your passphrase, if you set one.

## Back up

1. Open the recovery phrase in Lensly and write the 24 words on paper, in order.
2. If you set a passphrase, write it down separately from the 24 words.
3. Never photograph the words or the passphrase, and never store them in a notes app or cloud drive.

Lensly erases the vault if it cannot verify the vault's state, for example after a storage or Keystore fault or a power cut. It does this before it checks your password, so keep this paper copy.

A lost passphrase cannot be recovered. Without it, the 24 words restore a different, empty wallet.

## Move to a Ledger

1. On the Ledger, choose "Restore from recovery phrase" and enter the 24 words.
2. If you used a passphrase, set it on the Ledger under Settings > Security > Passphrase. Use "attach to a PIN", or a temporary passphrase for one session.
3. In Ledger Live, add accounts for each chain you use. Ledger Live finds the same addresses that Lensly shows.

## Move from a Ledger to Lensly

Lensly accepts only 24-word phrases. A Ledger phrase with 12 or 15 words cannot be imported.

1. In Lensly, choose restore and enter the 24 words.
2. If you used a passphrase on the Ledger, enter the same passphrase.
3. Create the same number of accounts per chain that you had on the Ledger.

## Derivation paths

Accounts start at 0. Replace N with the account number.

| Chain | Path |
|---|---|
| Bitcoin, native segwit | m/84'/0'/N' |
| Bitcoin, taproot | m/86'/0'/N' |
| Bitcoin, nested segwit | m/49'/0'/N' |
| Bitcoin, legacy | m/44'/0'/N' |
| Ethereum and EVM chains | m/44'/60'/N'/0/0 |
| Solana | m/44'/501'/N' |
| Algorand | m/44'/283'/N'/0/0 |

- Bitcoin receive addresses use /0/i and change addresses use /1/i, where i is the address index.
- Ethereum and Solana match Ledger Live.
- Algorand matches the Ledger Algorand app. Other Algorand wallets that use the xHD standard show different addresses.

## Passphrase limits

- A Ledger accepts up to 100 characters, typed on its own keyboard.
- Lensly warns you if your passphrase is over 100 characters or has characters a Ledger cannot type.
- Passphrases are case-sensitive. Type it the same way every time.

## Keystone

Keystone's default Ethereum layout (m/44'/60'/0'/0/N) is different from Ledger Live. To match Lensly, choose Keystone's "Ledger Live" option.
