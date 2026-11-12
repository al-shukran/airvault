package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.lensly.app.WalletDraft
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.wallet.CeremonyChoiceScreen
import app.lensly.ui.wallet.CeremonyResultScreen
import app.lensly.ui.wallet.DiceEntryScreen
import app.lensly.ui.wallet.EntropyMode
import app.lensly.ui.wallet.EntropyTargets
import app.lensly.ui.wallet.MnemonicConfirmScreen
import app.lensly.ui.wallet.MnemonicDisplayScreen
import app.lensly.ui.wallet.MnemonicQuiz
import app.lensly.ui.wallet.SkipWarningScreen
import app.lensly.ui.wallet.WalletStartScreen

/**
 * The create flow: start, the device commitment, the owner's dice or coins (or the skip warning), the result, the
 * 24 words, and the quiz. The passphrase and name screens are shared with restore, in [walletRoutes]. Each flow
 * screen needs the draft of a started ceremony, so a missing draft sends the owner back to the start.
 */
internal fun NavGraphBuilder.createRoutes(navController: NavHostController) {
    composable(HiddenRoute.CEREMONY_CHOICE) { entry ->
        HiddenBackdrop { CeremonyChoiceRoute(navController, entry) }
    }
    composable(HiddenRoute.DICE) { entry ->
        HiddenBackdrop { EntropyRoute(navController, EntropyMode.Dice, entry) }
    }
    composable(HiddenRoute.COINS) { entry ->
        HiddenBackdrop { EntropyRoute(navController, EntropyMode.Coins, entry) }
    }
    composable(HiddenRoute.SKIP) { entry -> HiddenBackdrop { SkipRoute(navController, entry) } }
    composable(HiddenRoute.RESULT) { entry -> HiddenBackdrop { ResultRoute(navController, entry) } }
    composable(HiddenRoute.WORDS) { entry -> HiddenBackdrop { WordsRoute(navController, entry) } }
    composable(HiddenRoute.CONFIRM) { entry -> HiddenBackdrop { ConfirmRoute(navController, entry) } }
}

/**
 * The start screen. Create begins a ceremony with fresh device randomness, restore is the restore flow, and import takes
 * one private key instead of 24 words.
 */
@Composable
internal fun WalletStartRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
    WalletStartScreen(
        onCreate = {
            draft.startCeremony()
            navController.navigate(HiddenRoute.CEREMONY_CHOICE)
        },
        onRestore = { navController.navigate(HiddenRoute.RESTORE) },
        onImportKey = { navController.navigate(HiddenRoute.IMPORT_KEY) },
    )
}

@Composable
private fun CeremonyChoiceRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
    RequireDraft(navController, ready = draft.commitmentHex.isNotEmpty())
    CeremonyChoiceScreen(
        commitmentHex = draft.commitmentHex,
        onDice = { navController.navigate(HiddenRoute.DICE) },
        onCoins = { navController.navigate(HiddenRoute.COINS) },
        onSkip = { navController.navigate(HiddenRoute.SKIP) },
    )
}

/**
 * Dice or coin entry. DONE enables at the minimum count, and entry does not stop at full strength. The whole entered
 * string is the mixer input, whatever its length.
 */
@Composable
private fun EntropyRoute(navController: NavHostController, mode: EntropyMode, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
    RequireDraft(navController, ready = draft.commitmentHex.isNotEmpty())
    val minimum = EntropyTargets.minimumFor(mode)
    val fullStrength = EntropyTargets.fullStrengthFor(mode)
    var entered by remember(mode) { mutableStateOf("") }
    DiceEntryScreen(
        mode = mode,
        entered = entered,
        minimumCount = minimum,
        fullStrengthCount = fullStrength,
        onSymbol = { symbol ->
            if (mode.accepts(symbol) && entered.length < EntropyTargets.MAX_SYMBOLS) entered += symbol
        },
        onBackspace = { entered = entered.dropLast(1) },
        onDone = {
            if (entered.length >= minimum && draft.commitmentHex.isNotEmpty()) {
                draft.mixInput(entered)
                navController.navigate(HiddenRoute.RESULT)
            }
        },
    )
}

@Composable
private fun SkipRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
    RequireDraft(navController, ready = draft.commitmentHex.isNotEmpty())
    SkipWarningScreen(
        onConfirm = {
            if (draft.commitmentHex.isNotEmpty()) {
                draft.skipCeremony()
                navController.navigate(HiddenRoute.WORDS)
            }
        },
        onBack = { navController.popBackStack() },
    )
}

@Composable
private fun ResultRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
    RequireDraft(navController, ready = draft.mixedHashHex.isNotEmpty())
    CeremonyResultScreen(
        commitmentHex = draft.commitmentHex,
        mixedHashHex = draft.mixedHashHex,
        checksumWord = draft.checksumWord,
        onConfirm = { navController.navigate(HiddenRoute.WORDS) },
    )
}

@Composable
private fun WordsRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
    RequireDraft(navController, ready = draft.words.isNotEmpty())
    MnemonicDisplayScreen(
        words = draft.words,
        onWrittenDown = { navController.navigate(HiddenRoute.CONFIRM) },
    )
}

/**
 * The quiz that checks the owner wrote the words down. A wrong answer restarts it with new questions. Answering
 * every question moves on to the passphrase.
 */
@Composable
private fun ConfirmRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val draft: WalletDraft = draftOf(navController, entry)
    RequireDraft(navController, ready = draft.words.size == Mnemonic.WORD_COUNT)
    if (draft.words.size != Mnemonic.WORD_COUNT) return

    val quiz = remember(draft.words) { MnemonicQuiz(draft.words) }
    var state by remember(draft.words) { mutableStateOf(quiz.stateFor(failed = false)) }
    MnemonicConfirmScreen(
        state = state,
        onPick = { word ->
            when (quiz.answer(word)) {
                MnemonicQuiz.Answer.Correct -> state = quiz.stateFor(failed = false)
                MnemonicQuiz.Answer.Wrong -> state = quiz.stateFor(failed = true)
                MnemonicQuiz.Answer.Finished -> {
                    draft.markBackedUp()
                    navController.navigate(HiddenRoute.PASSPHRASE)
                }
            }
        },
        onBack = { navController.popBackStack() },
    )
}
