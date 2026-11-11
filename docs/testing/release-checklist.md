# Release checklist

Run before tagging a release. Everything here needs a person, a physical phone, and a watch or a second device. CI does
not and cannot cover it. Record the run in the release PR: date, app commit, phone model, Android version, wallet
versions.

## 1. Wallet round trip: Rabby, then MetaMask

Lensly is a signer, so the test is that a real online wallet can pair with it, ask it to sign, and broadcast the result.
Run the same steps twice, once per wallet. If the first wallet fails, stop and fix; do not continue to the second.

Setup, once per run:

- [ ] On the phone, install the build under test: `./gradlew :app:installRelease` (or the release APK from CI).
- [ ] Use a **public test mnemonic** in Lensly: the BIP39 `abandon … about` words, no passphrase. Never a seed that has
      held funds.
- [ ] Fund that account on **Monad testnet, chain ID 10143**. Confirm the chain ID in the wallet before the run; if the
      testnet or its faucet has moved, record the chain ID you actually used.
- [ ] Install Rabby on the desktop or phone, create a **fresh, empty** profile there. Do not use a wallet with funds.

Run, for Rabby:

- [ ] In Lensly, open the wallet, then the pairing export. In Rabby, add a hardware wallet, choose the Keystone-style QR
      flow, and scan the export Lensly shows.
- [ ] Check that Rabby shows the same address as Lensly for account 0.
- [ ] In Rabby, send a small transaction on chain 10143 to any address, and approve it with the hardware-wallet flow.
- [ ] Lensly shows the scan screen; scan Rabby's request. Read the review: chain, from, to, amount, fee, nonce.
- [ ] Approve with the App Password, scan the signature back into Rabby, and confirm Rabby broadcasts it.
- [ ] Open the explorer and confirm the transaction is mined. Record the transaction hash.

Then uninstall the test wallet from Rabby and repeat every step above in **MetaMask**. Do not reuse a profile.

Record:

| Field | Rabby | MetaMask |
| --- | --- | --- |
| Wallet version | | |
| Broadcast (hash) | | |
| Date | | |
| App commit | | |
| Phone / Android | | |

Stop the release if either wallet cannot pair, or if a review in Lensly shows a field that differs from the wallet's own
request, or if the broadcast transaction differs from what the review said.

## 2. TalkBack pass

Run on the same phone, with the release build.

- [ ] Turn on TalkBack: Settings > Accessibility > TalkBack > Use TalkBack.
- [ ] Walk every screen, top to bottom, and listen. Unlock, wallet list, wallet detail, add wallet, export and pairing,
      scan, review, sign, password change, shred, settings.
- [ ] For every control you can see, check that it is spoken with a name and a role, and that a double tap does what the
      label says.
- [ ] Check the reading order: it must follow the screen top to bottom, and the bottom action (APPROVE, REJECT, Cancel)
      must come last. Note any control read out of order.
- [ ] Check the small targets listed in [accessibility.md](accessibility.md): every keypad key and every keyboard key
      must be reachable and announce itself. They are narrower than 48 dp by design, so confirm they can still be hit.
- [ ] Type an App Password with TalkBack on: the field must announce the character count and must never read the
      characters.
- [ ] On the scan screen, confirm the progress and the status line are announced when they change.
- [ ] On the review, confirm every fact is read before APPROVE, and that APPROVE stays announced as disabled until it is
      enabled.
- [ ] Check truncation: a long wallet name, a full address, and the fee rows. Nothing important may be cut off without
      being spoken in full.
- [ ] Check screen changes: after unlocking or after a signature, focus must land on the new screen's title, not stay
      where it was.

Record:

| Field | Value |
| --- | --- |
| Date | |
| App commit | |
| Phone / Android | |
| TalkBack version | |
| Issues filed | |

A control with no spoken label, or one that cannot be reached, blocks the release. Reading-order and verbosity problems
are filed as issues and do not block unless they hide a fact the user signs on.

## 3. When Compose or Espresso is upgraded

Not part of every release, but do it when the Compose BOM or `espresso-accessibility` changes: run
`app.lensly.a11y.AccessibilityTest` on an emulator. It asserts that the Accessibility Test Framework still sees only the
host Compose view. If it fails, the framework has started reading Compose nodes, and the gate decision in
[accessibility.md](accessibility.md) has to be made again.
