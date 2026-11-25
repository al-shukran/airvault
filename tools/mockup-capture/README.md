# Mockup capture

Opens every frame of the approved Lumio mockups on an emulator, with the same demo values the mockups show, and saves one PNG per frame and theme.
It works with debug builds only. Release and benchmark builds contain none of it.

## Run

```
./gradlew :lumio:assembleDebug
tools/mockup-capture/capture.sh emulator-5554 /tmp/lumio-frames        # every frame
tools/mockup-capture/capture.sh emulator-5554 /tmp/lumio-frames p1-12 p2-05   # chosen frames
```

The output is `<id>-light.png` and `<id>-dark.png`, for the themes that the mockup has. `ids.tsv` lists the frames: the id (`p1` or `p2` for the
part of the mockups, then the frame number, as in the rendered mockup PNGs), its themes, and its title.

## What the debug build does

Launch extras of `app.lumio/.MainActivity`:

| Extra | Effect |
| :---- | :----- |
| `--ez debugDemoData true` | Every network source is replaced by one fixture provider (`DemoProvider`): prices, market data, Ethereum, Bitcoin, Solana and Algorand reads, history. The vault is seeded with the demo wallet Air 1 (accounts PulsarNova, FelixStone and NothingLess) from the public BIP39 test vector. |
| `--es debugScreen <id>` | Opens the frame `<id>` directly, on the side (Mainnet or Testnet) the mockup shows. Implies `debugDemoData`. |
| `--es debugProfile full\|empty\|offline\|error\|slow` | How the fixture answers: all values, no mainnet funds, a dropped connection, a provider error, or never. |

The capture script also sets the system font scale (`p2-11`, 200%), the screen size (`p1-09`, 360x640) and the system theme, and clears the app data for the frames
that need a vault with no cached reads. An emulator is required: the seeded vault and its fixed password never exist on a phone.

Where the code lives: `lumio/src/debug/java/app/lumio/demo/` (fixtures, provider, id table) and `DebugDemoData.kt`. The sources are swapped at the places that already build
a provider list (`LumioApp`, `NativeBalances`, `EvmReads`). Screens read a few flags from `DebugDemoData.flag(...)` to open a sheet or a step.
