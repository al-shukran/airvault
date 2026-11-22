# Testing docs

Start with [guideline.md](guideline.md): test layers, CI design, conventions. Its section 5 lists where the shipped build differs from the spec text.

## Related docs

- [accessibility.md](accessibility.md): the accessibility decision and what gates a release.
- [release-checklist.md](release-checklist.md): manual device steps before shipping.

## Local commands

Run from the repo root. These wrap the Gradle commands in the guideline.

| Command | What it runs |
|---|---|
| `scripts/ci/run-partition.sh <partition>` | One PR partition. Names: `core-wallet`, `core-ur-evm`, `core-parse`, `core-vault`, `core-registry`, `core-btc`, `ui-a`, `ui-b`, `ui-c`, `lumio-a`, `lumio-apk`, `lumio-b`, `lumio-c`, `lumio-d`, `app`, `registry-pack`, `chain-api`, `manifest`, `ci`. |
| `scripts/ci/run-extended.sh <kind>` | One nightly layer. Kinds: `instrumented`, `e2e`, `screenshot-testing`, `perf`, `security`, `fuzz`. |
| `scripts/ci/fuzz-nightly.sh` | Jazzer targets, 15 min each. Slow: CI only. |
| `scripts/ci/benchmark-gate.sh <result.json>` | Compares a benchmark result against the reference-device baseline; fails past 10% regression. `--record` rewrites the baseline. |
| `scripts/emu-ready.sh [serial]` | Builds, installs and seeds Lumio's debug build on an emulator, waits for the demo wallet to open, then checks its account list shows a balance. Only emulators are touched. |

## Lumio demo seed

On an emulator, Lumio's debug build opens at its demo wallet with no camera, password keypad or physical Lensly:

```sh
adb -s emulator-5554 shell am start -n app.lumio/.MainActivity --es debugDemoSeed "'$DEMO_MNEMONIC'"
```

It creates the demo vault with a fixed App Password, or opens it when it already holds that password. It pairs the throwaway wallet whose 24 words you pass in `DEMO_MNEMONIC` (keep them out of the repository), selects testnet, reads its balances and opens the wallet over the Wallets home. The code is in the debug source sets only (`lumio/src/debug` and `shared/src/debug`), so release and benchmark builds ignore the extra. On a device that is not an emulator the seed does nothing. The emulator's vault uses a Keystore key that needs no screen lock, as the instrumented tests do. `scripts/emu-ready.sh` installs the debug build, sends this command, waits for the seed to open the wallet, and checks the account list on screen; `FRESH=1` starts from a new vault.

## Notes

- Add `-PprLane` to a local Gradle run to match the PR lane for `ui` (skips pixel tests).
- `scripts/ci/run-extended.sh` exits 0 for layers that are not configured yet; set `CI=true` to make that a failure.
