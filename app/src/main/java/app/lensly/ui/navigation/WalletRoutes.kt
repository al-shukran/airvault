package app.lensly.ui.navigation

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import app.lensly.BuildConfig
import app.lensly.app.AccountProfile
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.app.RegistryHolder
import app.lensly.app.SettingsStore
import app.lensly.app.VaultHolder
import app.lensly.app.WalletDraft
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletStore
import app.lensly.app.export.AccountExport
import app.lensly.core.registry.update.RegistryUpdate
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.flow.AccountExportScreen
import app.lensly.ui.flow.AccountExportView
import app.lensly.ui.flow.PairWalletRoute
import app.lensly.ui.flow.SignWalletRoute
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.home.BulkGenerateScreen
import app.lensly.ui.home.ImportState
import app.lensly.ui.home.RegistryImportScreen
import app.lensly.ui.home.SoonScreen
import app.lensly.ui.home.WalletDetailScreen
import app.lensly.ui.home.WalletHomeScreen
import app.lensly.ui.home.importedState
import app.lensly.ui.home.refusalReason
import app.lensly.ui.wallet.Bip39Autocomplete
import app.lensly.ui.wallet.PassphraseBackupScreen
import app.lensly.ui.wallet.PassphraseGate
import app.lensly.ui.wallet.PassphraseScreen
import app.lensly.ui.wallet.PassphraseState
import app.lensly.ui.wallet.PassphraseStep
import app.lensly.ui.wallet.RestoreEntryScreen
import app.lensly.ui.wallet.RestoreState
import app.lensly.ui.wallet.WalletNameScreen
import app.lensly.ui.wallet.effectiveWalletName
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The longest BIP39 word has eight letters, so a longer prefix cannot be a word. */
private const val MAX_WORD_LETTERS = 8

/**
 * The wallet routes of the hidden graph: the list, the start screen, restore, the catalog import, the shared
 * passphrase and name screens, a wallet's detail, and the pair and sign flows. The create flow is in [createRoutes].
 */
internal fun NavGraphBuilder.walletRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.HOME) { entry ->
        HiddenBackdrop { HomeRoute(navController, holder, entry) }
    }
    composable(HiddenRoute.WALLET_START) { entry ->
        HiddenBackdrop { WalletStartRoute(navController, entry) }
    }
    createRoutes(navController)
    importRoutes(navController, holder)
    composable(HiddenRoute.RESTORE) { entry -> HiddenBackdrop { RestoreRoute(navController, entry) } }
    composable(HiddenRoute.PASSPHRASE) { entry -> HiddenBackdrop { PassphraseRoute(navController, entry) } }
    composable(HiddenRoute.NAME) { entry -> HiddenBackdrop { NameRoute(navController, holder, entry) } }
    composable(HiddenRoute.PASSPHRASE_BACKUP) { entry ->
        HiddenBackdrop { PassphraseBackupRoute(navController, holder, entry) }
    }
    composable(HiddenRoute.DETAIL, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { DetailRoute(navController, holder, entry.walletId()) }
    }
    composable(HiddenRoute.BULK, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { BulkRoute(navController, holder, entry.walletId()) }
    }
    composable(HiddenRoute.EXPORT, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { PairWalletRoute(navController, holder, entry.walletId()) }
    }
    composable(
        HiddenRoute.EXPORT_ACCOUNT,
        arguments = walletIdArguments() + navArgument(HiddenRoute.ACCOUNT_INDEX_ARG) { type = NavType.IntType },
    ) { entry ->
        HiddenBackdrop { ExportAccountRoute(navController, holder, entry.walletId(), entry.accountIndex()) }
    }
    composable(HiddenRoute.SCAN, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { SignWalletRoute(navController, holder, entry.walletId()) }
    }
    composable(HiddenRoute.SHOW_SECRET, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { ShowSecretRoute(navController, holder, entry.walletId()) }
    }
    composable(HiddenRoute.DELETE_WALLET, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { DeleteWalletRoute(navController, holder, entry.walletId()) }
    }
    composable(HiddenRoute.CATALOG) { HiddenBackdrop { RegistryImportRoute(navController, holder) } }
    renameRoutes(navController, holder)
    profileRoutes(navController, holder)
}

internal fun walletIdArguments() = listOf(navArgument(HiddenRoute.WALLET_ID_ARG) { type = NavType.StringType })

internal fun NavBackStackEntry.walletId(): String = arguments?.getString(HiddenRoute.WALLET_ID_ARG).orEmpty()

@Composable
private fun HomeRoute(
    navController: NavHostController,
    holder: VaultHolder,
    entry: NavBackStackEntry,
) {
    val draft = draftOf(navController, entry)
    val session = rememberOpenSession(navController, holder) ?: return
    val debugNotice = remember { debugKeystoreNotice(holder) }
    var wallets by remember { mutableStateOf<List<WalletMeta>>(emptyList()) }
    var profiles by remember { mutableStateOf<Map<String, WalletProfile>>(emptyMap()) }

    // The draft only lives while a create or restore flow runs. Arriving here ends the flow.
    LaunchedEffect(Unit) { draft.wipe() }
    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            val store = WalletStore(session)
            val listed = withContext(Dispatchers.IO) { store.list() }
            profiles = withContext(Dispatchers.IO) { listed.associate { it.id to store.walletProfile(it.id) } }
            wallets = listed
        }
    }

    BackHandler { leaveHidden(navController, holder) }
    WalletHomeScreen(
        wallets = wallets,
        profiles = profiles,
        onOpen = { walletId -> navController.navigate(HiddenRoute.detail(walletId)) },
        onAdd = { navController.navigate(HiddenRoute.WALLET_START) },
        onSettings = { navController.navigate(HiddenRoute.SETTINGS) },
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
        onSuccess = { importedState(it, chains = RegistryHolder.get(context).networks()) },
        onFailure = { ImportState.Refused(refusalReason(it)) },
    )
}

/**
 * Restore: the owner types the 24 words one at a time. Letters build the word in progress, SPACE or a suggestion
 * commits it, and a filled slot can be retyped. A complete phrase must pass [Mnemonic.isValid], or the screen
 * shows the checksum error.
 */
@Composable
private fun RestoreRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
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
private fun PassphraseRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
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
                    val matches = PassphraseGate.matches(expected, second)
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

/**
 * Names the wallet, then saves it and opens its detail. The suggested name counts the wallets already saved. An imported
 * key, and a wallet with a passphrase, keep their name in the draft and go on to their write-down, which saves the
 * wallet.
 */
@Composable
private fun NameRoute(navController: NavHostController, holder: VaultHolder, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
    RequireDraft(navController, ready = draft.words.isNotEmpty() || draft.singleKey != null)
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    val suggested by produceState<String?>(initialValue = null, session) {
        value = withContext(Dispatchers.IO) { "WALLET ${WalletStore(session).list().size + 1}" }
    }
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    WalletNameScreen(
        name = name,
        placeholder = suggested,
        onKey = { c ->
            if (!saving && name.length < DISPLAY_NAME_MAX_LENGTH) name += c
        },
        onBackspace = {
            if (!saving && name.isNotEmpty()) name = name.dropLast(1)
        },
        onDone = {
            if (!saving && draft.singleKey != null) {
                draft.setName(effectiveWalletName(name, suggested))
                navController.navigate(HiddenRoute.KEY_BACKUP)
            } else if (!saving && draft.words.isNotEmpty()) {
                val finalName = effectiveWalletName(name, suggested)
                if (PassphraseGate.needsWriteDown(draft.passphrase)) {
                    draft.setName(finalName)
                    navController.navigate(HiddenRoute.PASSPHRASE_BACKUP)
                } else {
                    saving = true
                    saveMnemonicWallet(navController, holder, session, draft, scope, finalName)
                }
            }
        },
    )
}

/**
 * The write-down of a wallet with a passphrase, the last step before it is saved. CREATE WALLET stays off until the
 * owner confirms the passphrase is written down. Then the wallet is saved from the draft, and its detail opens.
 */
@Composable
private fun PassphraseBackupRoute(
    navController: NavHostController,
    holder: VaultHolder,
    entry: NavBackStackEntry,
) {
    val draft = draftOf(navController, entry)
    RequireDraft(navController, ready = draft.words.isNotEmpty() && PassphraseGate.needsWriteDown(draft.passphrase))
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var writtenDown by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    PassphraseBackupScreen(
        writtenDown = writtenDown,
        onWrittenDownChange = { writtenDown = it },
        onCreate = {
            if (!saving && PassphraseGate.canSave(draft.passphrase, writtenDown)) {
                saving = true
                saveMnemonicWallet(navController, holder, session, draft, scope, draft.name)
            }
        },
    )
}

/**
 * Saves the draft's Mnemonic wallet under [name], wipes the draft, and opens the wallet's detail. The passphrase is
 * read from the draft before it is wiped.
 */
private fun saveMnemonicWallet(
    navController: NavHostController,
    holder: VaultHolder,
    session: VaultSession,
    draft: WalletDraft,
    scope: CoroutineScope,
    name: String,
) {
    scope.launch {
        whileUnlocked(navController, holder) {
            val walletId = withContext(Dispatchers.IO) {
                WalletStore(session).create(
                    name = name,
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
    var profiles by remember(walletId) { mutableStateOf<Map<Int, AccountProfile>>(emptyMap()) }
    var walletProfile by remember(walletId) { mutableStateOf(WalletProfile()) }
    var position by remember(walletId) { mutableIntStateOf(0) }

    LaunchedEffect(walletId) {
        whileUnlocked(navController, holder) {
            val store = WalletStore(session)
            val meta = withContext(Dispatchers.IO) { store.meta(walletId) }
            if (meta != null) {
                // The profile and the place in the list come before the wallet, so the header never shows a default look.
                walletProfile = withContext(Dispatchers.IO) { store.walletProfile(walletId) }
                position = withContext(Dispatchers.IO) {
                    store.list().indexOfFirst { it.id == walletId }.coerceAtLeast(0)
                }
            }
            wallet = meta
        }
    }
    val current = wallet
    LaunchedEffect(walletId, current?.accountCount) {
        if (current != null) {
            addresses = null
            whileUnlocked(navController, holder) {
                // The profiles come first, so a row never shows a default name or look that a profile replaces.
                profiles = withContext(Dispatchers.IO) {
                    WalletStore(session).accountProfiles(walletId, current.accountCount)
                }
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
        accountProfiles = profiles,
        walletProfile = walletProfile,
        walletPosition = position,
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
        onAddAccount = {
            scope.launch {
                whileUnlocked(navController, holder) {
                    wallet = withContext(Dispatchers.IO) { WalletStore(session).addAccount(walletId) }
                }
            }
        },
        onBulkGenerate = { navController.navigate(HiddenRoute.bulk(walletId)) },
        onPair = { navController.navigate(HiddenRoute.export(walletId)) },
        onSign = { navController.navigate(HiddenRoute.scan(walletId)) },
        onShowSecret = { navController.navigate(HiddenRoute.showSecret(walletId)) },
        onEditWallet = { navController.navigate(HiddenRoute.editWallet(walletId)) },
        onEditAccount = { index -> navController.navigate(HiddenRoute.editAccount(walletId, index)) },
        onExportAccount = { index -> navController.navigate(HiddenRoute.exportAccount(walletId, index)) },
        onRenameAccounts = { indices -> navController.navigate(HiddenRoute.renameAccounts(walletId, indices)) },
        onStyleAccounts = { indices -> navController.navigate(HiddenRoute.styleAccounts(walletId, indices)) },
        onResetAccountNames = { indices ->
            scope.launch {
                whileUnlocked(navController, holder) {
                    // One write removes the selected names. The profiles are read again, so the rows show the Auto-Names.
                    profiles = withContext(Dispatchers.IO) {
                        val store = WalletStore(session)
                        store.resetAccountNames(walletId, indices)
                        store.accountProfiles(walletId, shown.accountCount)
                    }
                }
            }
        },
        onDeleteWallet = { navController.navigate(HiddenRoute.deleteWallet(walletId)) },
    )
}

/**
 * EXPORT of account [index] of wallet [walletId]: its address as a code. A Mnemonic account's address comes from its
 * public account key, and a Single Key's from its key. An index the wallet does not have goes back to the wallet.
 */
@Composable
private fun ExportAccountRoute(navController: NavHostController, holder: VaultHolder, walletId: String, index: Int) {
    val session = rememberOpenSession(navController, holder) ?: return
    var shown by remember(walletId, index) { mutableStateOf<AccountExportView?>(null) }
    LaunchedEffect(walletId, index) {
        whileUnlocked(navController, holder) {
            val store = WalletStore(session)
            val meta = withContext(Dispatchers.IO) { store.meta(walletId) }
            if (meta == null || index !in 0 until meta.accountCount) {
                navController.popBackStack()
                return@whileUnlocked
            }
            val address = withContext(Dispatchers.IO) { store.accountAddress(walletId, index) }
            shown = AccountExportView(
                address = address,
                derivationPath = if (meta.kind == WalletKind.MNEMONIC) AccountExport.derivationPath(index) else null,
            )
        }
    }
    val current = shown ?: return
    AccountExportScreen(
        address = current.address,
        derivationPath = current.derivationPath,
        onDone = { navController.popBackStack() },
        onBack = { navController.popBackStack() },
    )
}

/**
 * The bulk screen of one wallet. GENERATE runs [WalletStore.addAccounts] off the main thread, and its progress is
 * shown. A finished run returns to the wallet, which reloads its accounts. A run that fails, or that finds the wallet
 * changed meanwhile, saves nothing, and the screen says so. Back is held while a run derives, so the owner waits.
 */
@Composable
private fun BulkRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var wallet by remember(walletId) { mutableStateOf<WalletMeta?>(null) }
    var bulkCap by remember(walletId) { mutableStateOf<BulkCap?>(null) }
    var digits by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val derivedCount = remember { MutableStateFlow(0) }
    val derived by derivedCount.collectAsState()

    // The Bulk Cap is read each time this screen is shown, so a cap saved in Settings is the one this screen uses. The
    // screen and the run both use the cap read here.
    val entry by navController.currentBackStackEntryAsState()
    LaunchedEffect(walletId) {
        whileUnlocked(navController, holder) {
            wallet = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
        }
    }
    LaunchedEffect(walletId, entry) {
        whileUnlocked(navController, holder) {
            bulkCap = withContext(Dispatchers.IO) { SettingsStore(session).load().bulkCap }
        }
    }
    val shown = wallet ?: return
    val cap = bulkCap ?: return

    BackHandler(enabled = running) {}
    BulkGenerateScreen(
        existingAccounts = shown.accountCount,
        autoNameBase = shown.autoNameBase,
        cap = cap,
        digits = digits,
        running = running,
        derived = derived,
        failure = failure,
        onDigit = { digit -> if (!running) digits = BulkPolicy.appendDigit(digits, digit) },
        onBackspace = { if (!running) digits = digits.dropLast(1) },
        onPreset = { preset -> if (!running) digits = preset.toString() },
        onGenerate = generate@{
            val check = BulkPolicy.checkCount(digits.toIntOrNull(), cap.accounts)
            val count = (check as? BulkPolicy.CountCheck.Valid)?.count
            if (running || count == null) return@generate
            running = true
            failure = null
            derivedCount.value = 0
            scope.launch {
                whileUnlocked(navController, holder) {
                    val saved = try {
                        withContext(Dispatchers.IO) {
                            WalletStore(session).addAccounts(walletId, count, cap.accounts) { derivedCount.value = it }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IllegalStateException) {
                        // A closed session or a damaged vault. whileUnlocked returns the owner to the camera.
                        throw e
                    } catch (e: Exception) {
                        running = false
                        failure = "Run failed. Nothing was saved"
                        return@whileUnlocked
                    }
                    running = false
                    if (saved == null) {
                        failure = "Wallet changed. Nothing was saved"
                    } else {
                        navController.popBackStack()
                    }
                }
            }
        },
        onBack = { navController.popBackStack() },
        onLock = { leaveHidden(navController, holder) },
    )
}

/**
 * The open session, or null while locked. If the vault is locked while a hidden screen is showing, the gate
 * takes over again.
 */
@Composable
internal fun rememberOpenSession(navController: NavHostController, holder: VaultHolder): VaultSession? {
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
internal suspend fun whileUnlocked(
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
