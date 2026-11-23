# Accessibility

This page records accessibility decisions that the maintainers have reviewed and accepted. Check it before you file a
finding about screen readers. The test coverage of the Compose screens is in [testing/accessibility.md](testing/accessibility.md).

## Accepted, Do Not Re-Report

### TalkBack Reads the Confirm-Word Quiz and the Restore Suggestions

TalkBack speaks two kinds of recovery-phrase words on purpose. Both are in Lensly. Lumio has neither screen.

1. **Confirm-word quiz.** `MnemonicConfirmScreen` shows four word choices for each question (`MnemonicQuiz`). Each
   choice is a button, and TalkBack reads it. The position prompt and the "Wrong word" banner are read too.
2. **Restore word suggestions.** `RestoreEntryScreen` shows up to four BIP39 words that start with the letters typed so
   far (`Bip39Autocomplete`). TalkBack reads each suggestion. The prefix being typed and the "No word starts with these
   letters" message are plain text, so they are read too.

**Why it is accepted.** A blind owner has to hear the four choices to answer the quiz, and has to hear the suggestions to
pick a restore word. If these words were hidden from accessibility, the owner could not finish the backup or the
restore. Hiding them would lock those owners out of their wallet. It would not make the phrase safer.

**Residual risk.** Someone who can hear the device can hear these words. So can an accessibility service that the owner
has enabled, because such a service reads the window tree and FLAG_SECURE does not stop it. Each quiz question offers
one correct word among four, and each suggestion list gives candidates for the letters typed so far. Screenshots and
screen recordings of these screens are blocked by FLAG_SECURE on release builds (`SecureWindow.kt`).

**What stays hidden.** The code hides the phrase itself in these places:

- Every recovery word on the display screen is hidden from accessibility. The node speaks `Word N of 24` instead
  (`MnemonicDisplayScreen`, `ShowSecretScreen`, through `hiddenFromAccessibility`).
- Every filled word slot on the restore screen is hidden the same way (`WordSlot`).
- No screen in the wallet flow merges the phrase into one spoken node. Nothing there uses `mergeDescendants`, so the
  phrase is never read as a block.

**Do not re-report.** Do not file a finding that TalkBack speaks quiz choices or restore suggestions. Do not propose
hiding them. A change to the quiz or the restore screen must keep these words speakable. It must also keep the words
hidden on the display and restore slot screens.
