package app.lensly.ui.wallet

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.applySecureWindow
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyTheme

private const val SCREEN_START = "start"
private const val SCREEN_CHOICE = "choice"
private const val SCREEN_SKIP = "skip"
private const val SCREEN_DICE = "dice"
private const val SCREEN_COINS = "coins"
private const val SCREEN_RESULT = "result"
private const val SCREEN_WORDS = "words"
private const val SCREEN_CONFIRM = "confirm"
private const val SCREEN_PASSPHRASE = "passphrase"
private const val SCREEN_RESTORE = "restore"
private const val SCREEN_NAME = "name"
private const val SCREEN_ICONS = "icons"
private val SCREENS = setOf(
    SCREEN_START,
    SCREEN_CHOICE,
    SCREEN_SKIP,
    SCREEN_DICE,
    SCREEN_COINS,
    SCREEN_RESULT,
    SCREEN_WORDS,
    SCREEN_CONFIRM,
    SCREEN_PASSPHRASE,
    SCREEN_RESTORE,
    SCREEN_NAME,
    SCREEN_ICONS,
)

/**
 * Debug-only harness that hosts each wallet screen with local fake state, so the flow can be checked
 * on a device without the real crypto.
 *
 * Launch with:
 *   adb shell am start -n app.lensly/app.lensly.ui.wallet.WalletPreviewActivity --es screen words
 *
 * Screens: start, choice, skip, dice, coins, result, words, confirm, passphrase, restore, name, icons. Unknown
 * values show start. The icons screen lists every logo and icon in a grid. Every input screen is interactive. The fake data is a fixed commitment and mixed
 * hash, plus a valid 24-word phrase generated from fixed bytes.
 */
class WalletPreviewActivity : ComponentActivity() {

    private val screen = mutableStateOf(SCREEN_START)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindow()
        screen.value = screenFrom(intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.BLACK),
        )

        setContent {
            val current by screen
            LenslyTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LenslyBlack),
                ) {
                    PreviewHost(screen = current)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screen.value = screenFrom(intent)
    }

    private fun screenFrom(intent: Intent): String {
        val requested = intent.getStringExtra(EXTRA_SCREEN)
        return if (requested != null && requested in SCREENS) requested else SCREEN_START
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
    }
}

private const val PREVIEW_COMMITMENT =
    "5c1e8a0f3b92d4716e0a9b3c48f2d1e7" + "a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1"
private const val PREVIEW_MIXED =
    "e2d4a8c0b7f19635d0a4e8b2c6f3a9d1" + "7b5e0c8a4f2d6b9e1c3a7f5d8b0e2c4a"

/** A valid 24-word phrase, fixed so screenshots are repeatable. */
private val previewWords: List<String> by lazy {
    Mnemonic.fromEntropy(ByteArray(Mnemonic.ENTROPY_BYTES) { (it * 7 + 3).toByte() })
}

@Composable
private fun PreviewHost(screen: String) {
    when (screen) {
        SCREEN_CHOICE -> ChoicePreview()
        SCREEN_SKIP -> SkipPreview()
        SCREEN_DICE -> EntropyPreview(mode = EntropyMode.Dice)
        SCREEN_COINS -> EntropyPreview(mode = EntropyMode.Coins)
        SCREEN_RESULT -> ResultPreview()
        SCREEN_WORDS -> WordsPreview()
        SCREEN_CONFIRM -> ConfirmPreview()
        SCREEN_PASSPHRASE -> PassphrasePreview()
        SCREEN_RESTORE -> RestorePreview()
        SCREEN_NAME -> NamePreview()
        SCREEN_ICONS -> IconsPreview()
        else -> StartPreview()
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

@Composable
private fun StartPreview() {
    val context = LocalContext.current
    WalletStartScreen(
        onCreate = { toast(context, "Preview: create") },
        onRestore = { toast(context, "Preview: restore") },
        onImportKey = { toast(context, "Preview: import key") },
    )
}

@Composable
private fun ChoicePreview() {
    val context = LocalContext.current
    CeremonyChoiceScreen(
        commitmentHex = PREVIEW_COMMITMENT,
        onDice = { toast(context, "Preview: dice") },
        onCoins = { toast(context, "Preview: coins") },
        onSkip = { toast(context, "Preview: skip") },
    )
}

@Composable
private fun SkipPreview() {
    val context = LocalContext.current
    SkipWarningScreen(
        onConfirm = { toast(context, "Preview: skip confirmed") },
        onBack = { toast(context, "Preview: back") },
    )
}

@Composable
private fun ResultPreview() {
    val context = LocalContext.current
    CeremonyResultScreen(
        commitmentHex = PREVIEW_COMMITMENT,
        mixedHashHex = PREVIEW_MIXED,
        checksumWord = previewWords.first(),
        onConfirm = { toast(context, "Preview: compared") },
    )
}

@Composable
private fun WordsPreview() {
    val context = LocalContext.current
    MnemonicDisplayScreen(
        words = previewWords,
        onWrittenDown = { toast(context, "Preview: written down") },
    )
}

/** Dice and coin entry, starting partway through so the progress and strip are visible. */
@Composable
private fun EntropyPreview(mode: EntropyMode) {
    val context = LocalContext.current
    val prefill = if (mode == EntropyMode.Dice) 23 else 41
    var entered by remember(mode) { mutableStateOf(fakeEntries(mode, prefill)) }
    DiceEntryScreen(
        mode = mode,
        entered = entered,
        minimumCount = EntropyTargets.minimumFor(mode),
        fullStrengthCount = EntropyTargets.fullStrengthFor(mode),
        onSymbol = { symbol -> entered += symbol },
        onBackspace = { entered = entered.dropLast(1) },
        onDone = { toast(context, "Preview: ${entered.length} entries") },
    )
}

private fun fakeEntries(mode: EntropyMode, count: Int): String = buildString {
    repeat(count) { append(mode.symbols[(it * 5 + 1) % mode.symbols.length]) }
}

@Composable
private fun ConfirmPreview() {
    val context = LocalContext.current
    val quiz = remember { MnemonicQuiz(previewWords) }
    var state by remember { mutableStateOf(quiz.stateFor(failed = false)) }
    MnemonicConfirmScreen(
        state = state,
        onPick = { word ->
            state = when (quiz.answer(word)) {
                MnemonicQuiz.Answer.Correct -> quiz.stateFor(failed = false)
                MnemonicQuiz.Answer.Wrong -> quiz.stateFor(failed = true)
                MnemonicQuiz.Answer.Finished -> {
                    toast(context, "Preview: quiz passed")
                    quiz.restart()
                    quiz.stateFor(failed = false)
                }
            }
        },
        onBack = { toast(context, "Preview: back") },
    )
}

@Composable
private fun PassphrasePreview() {
    val context = LocalContext.current
    val buffer = remember { PasswordBuffer() }
    var first by remember { mutableStateOf<CharArray?>(null) }
    var step by remember { mutableStateOf(PassphraseStep.Ask) }
    var length by remember { mutableIntStateOf(0) }
    var mismatch by remember { mutableStateOf(false) }

    fun reset(to: PassphraseStep) {
        buffer.clear()
        first?.fill('\u0000')
        first = null
        length = 0
        mismatch = false
        step = to
    }

    PassphraseScreen(
        state = PassphraseState(step = step, length = length, mismatch = mismatch),
        onYes = { step = PassphraseStep.Enter },
        onSkip = {
            reset(PassphraseStep.Ask)
            toast(context, "Preview: no passphrase")
        },
        onKey = { c ->
            if (buffer.append(c)) {
                length = buffer.length
                mismatch = false
            }
        },
        onBackspace = {
            buffer.backspace()
            length = buffer.length
            mismatch = false
        },
        onSubmit = {
            when (step) {
                PassphraseStep.Ask -> Unit
                PassphraseStep.Enter -> {
                    first?.fill('\u0000')
                    first = buffer.toCharArray()
                    buffer.clear()
                    length = 0
                    step = PassphraseStep.Confirm
                }
                PassphraseStep.Confirm -> {
                    val second = buffer.toCharArray()
                    val matches = first?.contentEquals(second) == true
                    second.fill('\u0000')
                    if (matches) {
                        toast(context, "Preview: passphrase set")
                        reset(PassphraseStep.Ask)
                    } else {
                        buffer.clear()
                        length = 0
                        mismatch = true
                    }
                }
            }
        },
    )
}

/**
 * Restore flow with nine words already entered and "ab" typed for word 10, so the suggestions show
 * in the screenshot.
 */
@Composable
private fun RestorePreview() {
    val context = LocalContext.current
    val autocomplete = remember { Bip39Autocomplete() }
    var words by remember { mutableStateOf(previewWords.take(9)) }
    var activeIndex by remember { mutableIntStateOf(9) }
    var prefix by remember { mutableStateOf("ab") }
    var invalid by remember { mutableStateOf(false) }

    fun commit(word: String) {
        words = if (activeIndex < words.size) {
            words.toMutableList().also { it[activeIndex] = word }
        } else {
            words + word
        }
        activeIndex = words.size
        prefix = ""
        invalid = false
    }

    RestoreEntryScreen(
        state = RestoreState(
            words = words,
            activeIndex = activeIndex,
            prefix = prefix,
            suggestions = autocomplete.suggest(prefix),
            invalidChecksum = invalid,
        ),
        onKey = { c ->
            when {
                c == ' ' -> if (autocomplete.isWord(prefix)) commit(prefix)
                c.isLetter() && prefix.length < MAX_PREVIEW_PREFIX -> {
                    prefix += c.lowercaseChar()
                    invalid = false
                }
                else -> Unit
            }
        },
        onBackspace = { prefix = prefix.dropLast(1) },
        onPickSuggestion = { commit(it) },
        onEditWord = { index ->
            activeIndex = index
            prefix = ""
        },
        onSubmit = {
            if (words.size == Mnemonic.WORD_COUNT) {
                invalid = !Mnemonic.isValid(words)
                if (!invalid) toast(context, "Preview: phrase valid")
            }
        },
    )
}

private const val MAX_PREVIEW_PREFIX = 8

@Composable
private fun NamePreview() {
    val context = LocalContext.current
    var name by remember { mutableStateOf(DEFAULT_WALLET_NAME) }
    WalletNameScreen(
        name = name,
        onKey = { c ->
            if (name.length < WALLET_NAME_MAX_LENGTH) name += c
        },
        onBackspace = { name = name.dropLast(1) },
        onDone = { toast(context, "Preview: named $name") },
    )
}
