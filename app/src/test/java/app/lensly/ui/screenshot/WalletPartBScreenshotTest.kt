package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.ui.flow.RevealPart
import app.lensly.ui.flow.RevealStep
import app.lensly.ui.flow.RevealedSecret
import app.lensly.ui.wallet.Bip39Autocomplete
import app.lensly.ui.wallet.DEFAULT_WALLET_NAME
import app.lensly.ui.wallet.MnemonicDisplayScreen
import app.lensly.ui.wallet.PassphraseScreen
import app.lensly.ui.wallet.PassphraseState
import app.lensly.ui.wallet.PassphraseStep
import app.lensly.ui.wallet.RestoreEntryScreen
import app.lensly.ui.wallet.RestoreState
import app.lensly.ui.wallet.ShowSecretScreen
import app.lensly.ui.wallet.SkipWarningScreen
import app.lensly.ui.wallet.WalletNameScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Test vectors only. The 24 words are the public BIP39 all-zero vector, and the key is the public Anvil dev key.
 * Neither is a real wallet, and no real seed is used here.
 */
private val TEST_WORDS = List(23) { "abandon" } + "art"
private val INVALID_WORDS = List(24) { "abandon" }
private const val TEST_PASSPHRASE = "TREZOR"
private const val TEST_KEY_HEX = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"
private const val NAME_AT_LIMIT = "Cold storage, family vault 2026!"

/** Every screen of the wallet flow's second half, in each state it can show, rendered once for visual review. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletPartBScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mnemonicDisplayScreen() = compose.snapshot("mnemonic_display_screen") {
        MnemonicDisplayScreen(words = TEST_WORDS, onWrittenDown = {})
    }

    @Test
    fun skipWarningScreen() = compose.snapshot("skip_warning_screen") {
        SkipWarningScreen(onConfirm = {}, onBack = {})
    }

    @Test
    fun passphraseAskScreen() = passphrase("passphrase_ask_screen", PassphraseState(step = PassphraseStep.Ask))

    @Test
    fun passphraseEnterScreen() = passphrase(
        "passphrase_enter_screen",
        PassphraseState(step = PassphraseStep.Enter, length = 7),
    )

    @Test
    fun passphraseEmptyEnterScreen() = passphrase(
        "passphrase_empty_enter_screen",
        PassphraseState(step = PassphraseStep.Enter, length = 0),
    )

    @Test
    fun passphraseMismatchScreen() = passphrase(
        "passphrase_mismatch_screen",
        PassphraseState(step = PassphraseStep.Confirm, length = 4, mismatch = true),
    )

    @Test
    fun restoreEmptyScreen() = restore("restore_empty_screen", RestoreState())

    @Test
    fun restoreSuggestionsScreen() = restore(
        "restore_suggestions_screen",
        RestoreState(
            words = TEST_WORDS.take(5),
            activeIndex = 5,
            prefix = "ab",
            suggestions = Bip39Autocomplete().suggest("ab"),
        ),
    )

    @Test
    fun restoreNoMatchScreen() = restore(
        "restore_no_match_screen",
        RestoreState(words = TEST_WORDS.take(5), activeIndex = 5, prefix = "zq", suggestions = emptyList()),
    )

    @Test
    fun restoreInvalidChecksumScreen() = restore(
        "restore_invalid_checksum_screen",
        RestoreState(words = INVALID_WORDS, activeIndex = 24, invalidChecksum = true),
    )

    @Test
    fun restoreCompleteScreen() = restore(
        "restore_complete_screen",
        RestoreState(words = TEST_WORDS, activeIndex = 24),
    )

    @Test
    fun showSecretPasswordScreen() = showSecret(
        "show_secret_password_screen",
        RevealStep.Password(busy = false),
        passwordLength = 4,
    )

    @Test
    fun showSecretCheckingScreen() = showSecret(
        "show_secret_checking_screen",
        RevealStep.Password(busy = true),
        passwordLength = 8,
    )

    @Test
    fun showSecretWarningScreen() = showSecret("show_secret_warning_screen", RevealStep.Warning)

    @Test
    fun showSecretWordsScreen() = showSecret(
        "show_secret_words_screen",
        revealed(RevealedSecret.ofMnemonic(TEST_WORDS, null), RevealPart.WORDS, index = 2, secondsLeft = 45),
    )

    @Test
    fun showSecretPassphraseScreen() = showSecret(
        "show_secret_passphrase_screen",
        revealed(RevealedSecret.ofMnemonic(TEST_WORDS, TEST_PASSPHRASE), RevealPart.PASSPHRASE, index = 0, secondsLeft = 8),
    )

    @Test
    fun showSecretKeyScreen() = showSecret(
        "show_secret_key_screen",
        revealed(RevealedSecret.ofKey(testKeyBytes()), RevealPart.KEY, index = 0, secondsLeft = 30),
    )

    @Test
    fun walletNameEmptyScreen() = walletName(
        "wallet_name_empty_screen",
        name = "",
        placeholder = DEFAULT_WALLET_NAME,
    )

    @Test
    fun walletNameTypedScreen() = walletName(
        "wallet_name_typed_screen",
        name = "Savings",
        placeholder = DEFAULT_WALLET_NAME,
    )

    @Test
    fun walletNameFullScreen() = walletName(
        "wallet_name_full_screen",
        name = NAME_AT_LIMIT.take(DISPLAY_NAME_MAX_LENGTH),
        placeholder = DEFAULT_WALLET_NAME,
    )

    @Test
    fun accountNameResetScreen() = compose.snapshot("account_name_reset_screen") {
        WalletNameScreen(
            name = "Trip",
            onKey = {},
            onBackspace = {},
            onDone = {},
            placeholder = "Account 2",
            onReset = {},
        )
    }

    private fun passphrase(name: String, state: PassphraseState) = compose.snapshot(name) {
        PassphraseScreen(
            state = state,
            onYes = {},
            onSkip = {},
            onKey = {},
            onBackspace = {},
            onSubmit = {},
        )
    }

    private fun restore(name: String, state: RestoreState) = compose.snapshot(name) {
        RestoreEntryScreen(
            state = state,
            onKey = {},
            onBackspace = {},
            onPickSuggestion = {},
            onEditWord = {},
            onSubmit = {},
        )
    }

    private fun showSecret(name: String, step: RevealStep, passwordLength: Int = 0) = compose.snapshot(name) {
        ShowSecretScreen(
            step = step,
            passwordLength = passwordLength,
            onKey = {},
            onBackspace = {},
            onSubmit = {},
            onShow = {},
            onMove = {},
            onSelect = {},
            onDone = {},
            onLock = {},
        )
    }

    private fun walletName(snapshotName: String, name: String, placeholder: String?) = compose.snapshot(snapshotName) {
        WalletNameScreen(
            name = name,
            onKey = {},
            onBackspace = {},
            onDone = {},
            placeholder = placeholder,
        )
    }

    private fun revealed(secret: RevealedSecret, part: RevealPart, index: Int, secondsLeft: Int) =
        RevealStep.Revealed(secret = secret, part = part, index = index, secondsLeft = secondsLeft)

    private fun testKeyBytes(): ByteArray =
        ByteArray(TEST_KEY_HEX.length / 2) { i -> TEST_KEY_HEX.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
