# Accessibility

Status: measured in Phase 1.5, checking on the device. The Compose screens are covered by semantics assertions on the
JVM and a manual TalkBack pass per release. The Accessibility Test Framework runs too, but it does not gate.

## The spike

Question: does Espresso's Accessibility Test Framework (ATF) report Compose nodes on this stack, so it can gate the PR?

What ran: `app.lensly.a11y.AccessibilityTest`, on an arm64 emulator of API 36 (AVD `phase15_e2e`, `emulator-5580`), with
`AccessibilityChecks.enable().setRunChecksFromRootView(true)` and the results read from the whole activity.

Stack at the time of the measurement:

| Part | Version |
| --- | --- |
| Compose BOM | 2026.06.01 |
| `androidx.test.espresso:espresso-accessibility` | 3.7.0 (pulls ATF 3.1.2) |
| Emulator | API 36, arm64, 1080 × 2400 |
| Date | 2026-11-11 |

Three probes, five tests:

1. **Positive control.** A plain `ImageButton`, 20 px square, with no `contentDescription`, set as the activity content.
   ATF reported two errors: `TouchTargetSizeCheck` ("size is 8dp x 8dp, consider making this touch target 32dp") and
   `SpeakableTextPresentCheck`. The framework is armed and the test can fail.
2. **Three real Compose screens**: the unlock gate (`UnlockScreen`, password field plus the in-app keyboard), the wallet
   list (`WalletHomeScreen`), and the sign review (`TxReviewScreen` for an unknown chain, so the warnings and the chain
   check render). Each produced **one** error: `SpeakableTextPresentCheck` against the host `AndroidComposeView`
   ("This item may not have a label readable by screen readers"). Every other check came back `NOT_RUN` against each
   view in the tree, with reasons such as "not an editable TextView", "not an ImageView", "not found to be important for
   accessibility".
3. **Negative control.** The same activity, but the content is a 20 dp, unlabelled, clickable Compose box on a black
   background, well below any touch target and with nothing to read out. ATF reported exactly the same single
   `SpeakableTextPresentCheck` on `AndroidComposeView`. Nothing about the box.

ATF walks the `View` hierarchy. Compose exposes its layout and semantics nodes through a virtual accessibility node
provider under a single `AndroidComposeView`, and ATF 3.1.2 does not descend into it. The one result that does appear is
the host view, which never carries a `contentDescription` itself; it is a false positive that fires on every Compose
screen.

Two further limits of this version, even if it did walk Compose: its `TouchTargetSizeCheck` minimum is 32 dp, not the
48 dp the guideline asks for, so a 40 dp key would pass. And `TextContrastCheck` runs only on `TextView`, so it would not
reach Compose text either.

## Decision

**ATF does not gate.** It cannot distinguish a good Compose screen from a deliberately broken one, so a green ATF run
would say nothing, and a red one would only ever report the host view.

- `AccessibilityTest` stays in `androidTest` as a **tripwire**, not a gate. It asserts that the three screens and the
  broken screen each report only the host `AndroidComposeView`. If a Compose or Espresso upgrade starts reporting Compose
  nodes, that assertion fails and the spike has to be run again and this decision remade. It runs in the emulator lane,
  not on every PR.
- The PR lane checks what it can, cheaply: **semantics assertions on the JVM** (`app.lensly.ui.a11y.SemanticsTest`).

## What the PR lane checks

`./gradlew :app:testDebugUnitTest --tests 'app.lensly.ui.*'` runs `app.lensly.ui.a11y.SemanticsTest` on Robolectric. For
the highest-risk controls it asserts:

| Control | Label | 48 dp |
| --- | --- | --- |
| Password field (`PasswordField`) | "Password empty" or "N characters entered" | not a target |
| Numeric keypad keys (`NumericKeypad`) | each digit, and "Backspace" | height |
| Password keyboard keys (`SecureKeyboard`) | letters, "Shift", "Space", "Backspace", "Done", "Symbols" | height |
| Screen frame back button (`LenslyScreen`) | "Back" | width and height |

A key is 54 dp tall but stretches with its row: ten keys on a phone are narrower than 48 dp. That is a layout choice, not
a semantics gap, and the width cannot grow without dropping keys from a row. It is recorded here and left alone. A
screen reader user still reaches every key; the size only matters for a coarse touch, which the keyboard is not for.

The line of text contrast is a separate JVM test over the theme colours (guideline 1.9).

## What no automated test covers

Reading order, focus on screen change, live-region announcements, how a long address or a truncated wallet name is
spoken, and whether TalkBack can finish the sign flow without sight. Those need a device and ears. The procedure is in
[release-checklist.md](release-checklist.md).
