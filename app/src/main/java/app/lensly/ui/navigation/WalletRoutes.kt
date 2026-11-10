package app.lensly.ui.navigation

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.lensly.BuildConfig
import app.lensly.app.RegistryHolder
import app.lensly.app.VaultHolder
import app.lensly.app.WalletMeta
import app.lensly.app.WalletStore
import app.lensly.core.registry.update.RegistryUpdate
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.flow.PairWalletRoute
import app.lensly.ui.flow.SignWalletRoute
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.home.ImportState
import app.lensly.ui.home.RegistryImportScreen
import app.lensly.ui.home.SoonScreen
import app.lensly.ui.home.WalletDetailScreen
import app.lensly.ui.home.WalletHomeScreen
import app.lensly.ui.home.importedState
import app.lensly.ui.home.refusalReason
import app.lensly.ui.wallet.Bip39Autocomplete
import app.lensly.ui.wallet.PassphraseScreen
import app.lensly.ui.wallet.PassphraseState
import app.lensly.ui.wallet.PassphraseStep
import app.lensly.ui.wallet.RestoreEntryScreen
import app.lensly.ui.wallet.RestoreState
import app.lensly.ui.wallet.WALLET_NAME_MAX_LENGTH
import app.lensly.ui.wallet.WalletNameScreen
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The longest BIP39 word has eight letters, so a longer prefix cannot be a word. */
private const val MAX_WORD_LETTERS = 8

/**
 * The wallet routes of the hidden graph: the list, the start screen, restore, the catalog import, the shared
 * passphrase and name screens, a wallet's detail, and the pair and sign flows. The create flow is in [createRoutes].
 */
internal fun NavGraphBuilder.walletRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.HOME) { HiddenBackdrop { HomeRoute(navController, holder) } }
    composable(HiddenRoute.WALLET_START) { HiddenBackdrop { WalletStartRoute(navController) } }
    createRoutes(navController)
    composable(HiddenRoute.RESTORE) { HiddenBackdrop { RestoreRoute(navController) } }
    composable(HiddenRoute.PASSPHRASE) { HiddenBackdrop { PassphraseRoute(navController) } }
    composable(HiddenRoute.NAME) { HiddenBackdrop { NameRoute(navController, holder) } }
    composable(HiddenRoute.DETAIL, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { DetailRoute(navController, holder, entry.walletId()) }
    }
    composable(HiddenRoute.EXPORT, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { PairWalletRoute(navController, holder, entry.walletId()) }
    }
    composable(HiddenRoute.SCAN, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { SignWalletRoute(navController, holder, entry.walletId()) }
    }
    composable(HiddenRoute.CATALOG) { HiddenBackdrop { RegistryImportRoute(navController, holder) } }
}

private fun walletIdArguments() = listOf(navArgument(HiddenRoute.WALLET_ID_ARG) { type = NavType.StringType })

private fun NavBackStackEntry.walletId(): String = arguments?.getString(HiddenRoute.WALLET_ID_ARG).orEmpty()

@Composable
private fun HomeRoute(navController: NavHostController, holder: VaultHolder) {
    val draft = draftOf(navController)
    val session = rememberOpenSession(navController, holder) ?: return
    val debugNotice = remember { debugKeystoreNotice(holder) }
    var wallets by remember { mutableStateOf<List<WalletMeta>>(emptyList()) }

    // The draft only lives while a create or restore flow runs. Arriving here ends the flow.
    LaunchedEffect(Unit) { draft.wipe() }
    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            wallets = withContext(Dispatchers.IO) { WalletStore(session).list() }
        }
    }

    BackHandler { leaveHidden(navController, holder) }
    WalletHomeScreen(
        wallets = wallets,
        onOpen = { walletId -> navController.navigate(HiddenRoute.detail(walletId)) },
        onAdd = { navController.navigate(HiddenRoute.WALLET_START) },
        onLock = { leaveHidden(navController, holder) },
        onUpdateCatalog = { navController.navigate(HiddenRoute.CATALOG) },
        debugNotice = debugNotice,
    )
}

/**
 * The catalog import. The owner picks a file with the system picker, which needs no permission and works offline. The
 * bytes are read with a size cap, checked against the release key and the baked catalog, and stored by
 * [RegistryHolder]. Reading and checking run off the main thread, and the sign flow sees an accepted package at once.
 */
@Composable
private fun RegistryImportRoute(navController: NavHostController, holder: VaultHolder) {
    rememberOpenSession(navController, holder) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        PickerStop.disarm()
        if (uri != null) {
            state = ImportState.Checking
            scope.launch {
                state = withContext(Dispatchers.IO) { importPackage(context, uri) }
            }
        }
    }

    RegistryImportScreen(
        state = state,
        onChooseFile = {
            PickerStop.arm()
            chooseFile.launch(arrayOf("*/*"))
        },
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
    )
}

/** Reads and installs the file at [uri]. Every failure becomes a refusal, so the screen always has a reason to show. */
private fun importPackage(context: Context, uri: Uri): ImportState {
    val outcome = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { RegistryUpdate.readPackage(it) }
            ?: throw IOException("the file could not be opened")
        RegistryHolder.install(context, bytes).getOrThrow()
    }
    return outcome.fold(
        onSuccess = { importedState(it) },
        onFailure = { ImportState.Refused(refusalReason(it)) },
    )
}

/**
 * Restore: the owner types the 24 words one at a time. Letters build the word in progress, SPACE or a suggestion
 * commits it, and a filled slot can be retyped. A complete phrase must pass [Mnemonic.isValid], or the screen
 * shows the checksum error.
 */
@Composable
private fun RestoreRoute(navController: NavHostController) {
    val draft = draftOf(navController)
    val autocomplete = remember { Bip39Autocomplete() }
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeIndex by remember { mutableIntStateOf(0) }
    var prefix by remember { mutableStateOf("") }
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
                c.isLetter() && activeIndex < Mnemonic.WORD_COUNT && prefix.length < MAX_WORD_LETTERS -> {
                    prefix += c.lowercaseChar()
                    invalid = false
                }
                else -> Unit
            }
        },
        onBackspace = {
            prefix = prefix.dropLast(1)
            invalid = false
        },
        onPickSuggestion = { word -> commit(word) },
        onEditWord = { index ->
            activeIndex = index
            prefix = ""
            invalid = false
        },
        onSubmit = {
            if (activeIndex < Mnemonic.WORD_COUNT && autocomplete.isWord(prefix)) commit(prefix)
            if (words.size == Mnemonic.WORD_COUNT) {
                if (Mnemonic.isValid(words)) {
                    draft.setTypedWords(words)
                    navController.navigate(HiddenRoute.PASSPHRASE)
                } else {
                    invalid = true
                }
            }
        },
    )
}

/**
 * The optional BIP39 passphrase, shared by restore and create. Ask, then enter it, then confirm it. An empty entry
 * means no passphrase. The entry is zeroed as soon as it has been copied into the draft.
 */
@Composable
private fun PassphraseRoute(navController: NavHostController) {
    val draft = draftOf(navController)
    RequireDraft(navController, ready = draft.words.isNotEmpty())
    val buffer = remember { PasswordBuffer() }
    var step by remember { mutableStateOf(PassphraseStep.Ask) }
    var first by remember { mutableStateOf<CharArray?>(null) }
    var length by remember { mutableIntStateOf(0) }
    var mismatch by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            buffer.clear()
            first?.fill('\u0000')
        }
    }

    fun finish(passphrase: String?) {
        draft.setPassphrase(passphrase)
        navController.navigate(HiddenRoute.NAME)
    }

    PassphraseScreen(
        state = PassphraseState(step = step, length = length, mismatch = mismatch),
        onYes = { step = PassphraseStep.Enter },
        onSkip = { finish(null) },
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
                    if (buffer.isEmpty()) {
                        finish(null)
                    } else {
                        first?.fill('\u0000')
                        first = buffer.toCharArray()
                        buffer.clear()
                        length = 0
                        step = PassphraseStep.Confirm
                    }
                }
                PassphraseStep.Confirm -> {
                    val second = buffer.toCharArray()
                    val expected = first
                    val matches = expected != null && expected.contentEquals(second)
                    second.fill('\u0000')
                    if (expected != null && matches) {
                        val text = String(expected)
                        expected.fill('\u0000')
                        first = null
                        buffer.clear()
                        finish(text)
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

/** Names the wallet, then saves it and opens its detail. The suggested name counts the wallets already saved. */
@Composable
private fun NameRoute(navController: NavHostController, holder: VaultHolder) {
    val draft = draftOf(navController)
    RequireDraft(navController, ready = draft.words.isNotEmpty())
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    val suggested by produceState<String?>(initialValue = null, session) {
        value = withContext(Dispatchers.IO) { "WALLET ${WalletStore(session).list().size + 1}" }
    }
    var name by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(suggested) {
        if (!typed) name = suggested.orEmpty()
    }

    WalletNameScreen(
        name = name,
        onKey = { c ->
            if (!saving && name.length < WALLET_NAME_MAX_LENGTH) {
                name += c
                typed = true
            }
        },
        onBackspace = {
            if (!saving) {
                name = name.dropLast(1)
                typed = true
            }
        },
        onDone = {
            if (!saving && draft.words.isNotEmpty()) {
                saving = true
                scope.launch {
                    whileUnlocked(navController, holder) {
                        val finalName = name.trim().ifEmpty { suggested ?: "WALLET" }
                        val walletId = withContext(Dispatchers.IO) {
                            WalletStore(session).create(
                                name = finalName,
                                deviceGenerated = draft.deviceGenerated,
                                words = draft.words,
                                passphrase = draft.passphrase,
                                nowMs = System.currentTimeMillis(),
                            )
                        }
                        draft.wipe()
                        navController.navigate(HiddenRoute.detail(walletId)) {
                            popUpTo(HiddenRoute.HOME) { inclusive = false }
                        }
                    }
                }
            }
        },
    )
}

/**
 * One wallet: its name, its accounts, and the actions. Accounts are derived from the secret each time the count
 * changes, and "+ ACCOUNT" saves the new count before the list is shown again.
 */
@Composable
private fun DetailRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var wallet by remember(walletId) { mutableStateOf<WalletMeta?>(null) }
    var addresses by remember(walletId) { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(walletId) {
        whileUnlocked(navController, holder) {
            wallet = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
        }
    }
    val current = wallet
    LaunchedEffect(walletId, current?.accountCount) {
        if (current != null) {
            addresses = null
            whileUnlocked(navController, holder) {
                addresses = withContext(Dispatchers.IO) {
                    WalletStore(session).addresses(walletId, current.accountCount)
                }
            }
        }
    }

    val shown = current ?: return
    WalletDetailScreen(
        wallet = shown,
        addresses = addresses,
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
        onAddAccount = {
            scope.launch {
                whileUnlocked(navController, holder) {
                    wallet = withContext(Dispatchers.IO) { WalletStore(session).addAccount(walletId) }
                }
            }
        },
        onPair = { navController.navigate(HiddenRoute.export(walletId)) },
        onSign = { navController.navigate(HiddenRoute.scan(walletId)) },
    )
}

/**
 * The open session, or null while locked. If the vault is locked while a hidden screen is showing, the gate
 * takes over again.
 */
@Composable
private fun rememberOpenSession(navController: NavHostController, holder: VaultHolder): VaultSession? {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(HiddenRoute.GATE)
    }
    return session
}

/**
 * A flow screen needs the draft of a started flow. Without one, for example after the process restarted, the
 * owner goes back to the start of the flow.
 */
@Composable
internal fun RequireDraft(navController: NavHostController, ready: Boolean) {
    LaunchedEffect(ready) {
        if (!ready) navController.openHidden(HiddenRoute.WALLET_START)
    }
}

/** Runs [block]. If the vault was locked while it ran, the owner is returned to the camera. Cancellation passes through. */
private suspend fun whileUnlocked(
    navController: NavHostController,
    holder: VaultHolder,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalStateException) {
        // A closed session throws IllegalStateException, and so does a damaged vault.
        leaveHidden(navController, holder)
    }
}

/** A warning chip in debug builds when the keystore is software-only, as on the emulator. Release builds never show it. */
private fun debugKeystoreNotice(holder: VaultHolder): String? =
    if (BuildConfig.DEBUG && !holder.vault.deviceSecurity().hardwareBacked) "Software keystore" else null
