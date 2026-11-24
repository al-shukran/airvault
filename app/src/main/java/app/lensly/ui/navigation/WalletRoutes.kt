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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import app.lensly.BuildConfig
import app.lensly.app.AlgorandCapReached
import app.lensly.app.AppNotInstalled
import app.lensly.app.BitcoinAccountRecord
import app.lensly.app.BitcoinCapReached
import app.lensly.app.DISPLAY_NAME_MAX_LENGTH
import app.lensly.app.EthereumCapReached
import app.lensly.app.RegistryHolder
import app.lensly.app.ReviewHistory
import app.lensly.app.SaveState
import app.lensly.app.SettingsStore
import app.lensly.app.SolanaCapReached
import app.lensly.app.VaultHolder
import app.lensly.app.WalletApp
import app.lensly.app.WalletDraftViewModel
import app.lensly.app.WalletKind
import app.lensly.app.WalletListing
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletReviewRecord
import app.lensly.app.WalletStore
import app.lensly.app.WalletWriter
import app.lensly.app.displayNameOf
import app.lensly.app.export.AccountExport
import app.lensly.app.sessionReads
import app.lensly.app.sign.ReviewRecord
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.update.RegistryUpdate
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.BulkPolicy
import app.lensly.core.wallet.Mnemonic
import app.lensly.ui.flow.AccountExportGate
import app.lensly.ui.flow.PairWalletRoute
import app.lensly.ui.flow.SignWalletRoute
import app.lensly.ui.flow.checkAppPassword
import app.lensly.ui.home.ActivityScreen
import app.lensly.ui.home.AlgorandAccountsScreen
import app.lensly.ui.home.BitcoinAccountsScreen
import app.lensly.ui.home.BulkGenerateScreen
import app.lensly.ui.home.BulkTarget
import app.lensly.ui.home.DEFAULT_ALGORAND
import app.lensly.ui.home.DEFAULT_BITCOIN
import app.lensly.ui.home.DEFAULT_EVM
import app.lensly.ui.home.DEFAULT_SOLANA
import app.lensly.ui.home.EthereumAccountsScreen
import app.lensly.ui.home.HomeReadiness
import app.lensly.ui.home.ImportState
import app.lensly.ui.home.RegistryImportScreen
import app.lensly.ui.home.SolanaAccountsScreen
import app.lensly.ui.home.WalletAppsScreen
import app.lensly.ui.home.WalletDetailScreen
import app.lensly.ui.home.WalletHistoryScreen
import app.lensly.ui.home.WalletHomeScreen
import app.lensly.ui.home.WalletsScreen
import app.lensly.ui.home.effectiveCatalogSequence
import app.lensly.ui.home.importedState
import app.lensly.ui.home.refusalReason
import app.lensly.ui.home.runBulk
import app.lensly.ui.wallet.Bip39Autocomplete
import app.lensly.ui.wallet.PassphraseBackupScreen
import app.lensly.ui.wallet.PassphraseEntry
import app.lensly.ui.wallet.PassphraseGate
import app.lensly.ui.wallet.PassphraseScreen
import app.lensly.ui.wallet.PassphraseState
import app.lensly.ui.wallet.RestoreEntryScreen
import app.lensly.ui.wallet.RestoreState
import app.lensly.ui.wallet.WalletNameScreen
import app.lensly.ui.wallet.effectiveWalletName
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
    vaultComposable(HiddenRoute.HOME, motion = NavMotion.ROOT) { entry ->
        HiddenBackdrop { HomeRoute(navController, holder, entry) }
    }
    vaultComposable(HiddenRoute.WALLET_START) { entry ->
        HiddenBackdrop { WalletStartRoute(navController, entry) }
    }
    createRoutes(navController, holder)
    importRoutes(navController, holder)
    vaultComposable(HiddenRoute.RESTORE) { entry -> HiddenBackdrop { RestoreRoute(navController, entry) } }
    vaultComposable(HiddenRoute.PASSPHRASE) { entry -> HiddenBackdrop { PassphraseRoute(navController, entry) } }
    vaultComposable(HiddenRoute.NAME) { entry -> HiddenBackdrop { NameRoute(navController, holder, entry) } }
    vaultComposable(HiddenRoute.PASSPHRASE_BACKUP) { entry ->
        HiddenBackdrop { PassphraseBackupRoute(navController, holder, entry) }
    }
    vaultComposable(HiddenRoute.WALLETS) { HiddenBackdrop { WalletsRoute(navController, holder) } }
    vaultComposable(HiddenRoute.PAIR, motion = NavMotion.RISE) { HiddenBackdrop { PairWalletRoute(navController, holder, walletId = null) } }
    vaultComposable(HiddenRoute.DETAIL, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { DetailRoute(navController, holder, entry.walletId()) }
    }
    vaultComposable(HiddenRoute.APPS, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { AppsRoute(navController, holder, entry.walletId()) }
    }
    vaultComposable(HiddenRoute.APP, arguments = walletIdArguments() + appArgument()) { entry ->
        HiddenBackdrop { AppRoute(navController, holder, entry.walletId(), entry.walletApp()) }
    }
    vaultComposable(HiddenRoute.BULK, arguments = walletIdArguments() + appArgument()) { entry ->
        HiddenBackdrop { BulkRoute(navController, holder, entry.walletId(), entry.walletApp()) }
    }
    vaultComposable(HiddenRoute.EXPORT, arguments = walletIdArguments(), motion = NavMotion.RISE) { entry ->
        HiddenBackdrop { PairWalletRoute(navController, holder, walletId = entry.walletId()) }
    }
    vaultComposable(
        HiddenRoute.EXPORT_ACCOUNTS,
        arguments = walletIdArguments() + navArgument(HiddenRoute.ACCOUNT_INDICES_ARG) { type = NavType.StringType },
    ) { entry ->
        HiddenBackdrop { ExportAccountsRoute(navController, holder, entry.walletId(), entry.accountIndices()) }
    }
    vaultComposable(HiddenRoute.SCAN, arguments = walletIdArguments(), motion = NavMotion.RISE) { entry ->
        HiddenBackdrop { SignWalletRoute(navController, holder, entry.walletId()) }
    }
    // A scan of any wallet of the vault: the wallet the request is for is found among them.
    vaultComposable(HiddenRoute.SCAN_ANY, motion = NavMotion.RISE) {
        HiddenBackdrop { SignWalletRoute(navController, holder, walletId = null) }
    }
    vaultComposable(HiddenRoute.SHOW_SECRET, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { ShowSecretRoute(navController, holder, entry.walletId()) }
    }
    vaultComposable(HiddenRoute.DELETE_WALLET, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { DeleteWalletRoute(navController, holder, entry.walletId()) }
    }
    vaultComposable(HiddenRoute.HISTORY, arguments = walletIdArguments()) { entry ->
        HiddenBackdrop { HistoryRoute(navController, holder, entry.walletId()) }
    }
    vaultComposable(HiddenRoute.ACTIVITY) { HiddenBackdrop { ActivityRoute(navController, holder) } }
    vaultComposable(HiddenRoute.CATALOG) { HiddenBackdrop { RegistryImportRoute(navController, holder) } }
    renameRoutes(navController, holder)
    profileRoutes(navController, holder)
}

internal fun walletIdArguments() = listOf(navArgument(HiddenRoute.WALLET_ID_ARG) { type = NavType.StringType })

internal fun NavBackStackEntry.walletId(): String = arguments?.getString(HiddenRoute.WALLET_ID_ARG).orEmpty()

/** The argument of an App route: the name of one App. */
internal fun appArgument() = navArgument(HiddenRoute.APP_ARG) { type = NavType.StringType }

/** The App that a route names. A route that names no App is a navigation bug, so it fails loudly. */
internal fun NavBackStackEntry.walletApp(): WalletApp =
    requireNotNull(WalletApp.fromRouteId(arguments?.getString(HiddenRoute.APP_ARG))) { "The route names no App." }

@Composable
private fun HomeRoute(
    navController: NavHostController,
    holder: VaultHolder,
    entry: NavBackStackEntry,
) {
    val model = draftModelOf(navController, entry)
    val draft = model.draft
    val session = rememberOpenSession(navController, holder) ?: return
    val debugNotice = remember { debugKeystoreNotice(holder) }
    val listing = rememberWalletListing(navController, holder, session)
    val recent = rememberRecentActivity(navController, holder, entry)
    val readiness = rememberHomeReadiness(navController, holder, entry)
    // The graph's entry carries the pairing confirmation. It is resolved once, as draftOf does, because the graph may pop.
    val hidden = remember(entry) { navController.getBackStackEntry(LenslyRoute.HIDDEN) }
    val notice by hidden.savedStateHandle.getStateFlow<String?>(HiddenRoute.NOTICE, null).collectAsState()

    // The draft only lives while a create or restore flow runs. Arriving here ends the flow.
    LaunchedEffect(Unit) {
        draft.wipe()
        model.clearSaveState()
    }

    BackHandler { leaveHidden(navController, holder) }
    WalletHomeScreen(
        wallets = listing?.wallets,
        profiles = listing?.profiles.orEmpty(),
        accounts = listing?.accounts.orEmpty(),
        recent = recent,
        readiness = readiness,
        nowMs = System.currentTimeMillis(),
        onOpen = { walletId -> navController.navigate(HiddenRoute.detail(walletId)) },
        onEdit = { walletId -> navController.navigate(HiddenRoute.editWallet(walletId)) },
        onPair = { walletId -> navController.navigate(HiddenRoute.export(walletId)) },
        // Create starts the same ceremony as the start screen's Create, so the flow reads the same from either place.
        onCreate = {
            draft.startCeremony()
            navController.navigate(HiddenRoute.CEREMONY_CHOICE)
        },
        onRestore = { navController.navigate(HiddenRoute.RESTORE) },
        onScan = { navController.navigate(HiddenRoute.SCAN_ANY) },
        onActivity = { navController.navigate(HiddenRoute.activity()) },
        onWallets = { navController.navigate(HiddenRoute.WALLETS) },
        onSettings = { navController.navigate(HiddenRoute.SETTINGS) },
        onLock = { leaveHidden(navController, holder) },
        onStartPairing = { navController.navigate(HiddenRoute.PAIR) },
        onNetworks = { navController.navigate(HiddenRoute.NETWORKS) },
        onTokens = { navController.navigate(HiddenRoute.TOKENS) },
        onCatalog = { navController.navigate(HiddenRoute.CATALOG) },
        onLockout = { navController.navigate(HiddenRoute.FAILURE_CAP) },
        onBackup = { walletId -> navController.navigate(HiddenRoute.showSecret(walletId)) },
        debugNotice = debugNotice,
        notice = notice,
        onNoticeShown = { hidden.savedStateHandle[HiddenRoute.NOTICE] = null },
    )
}

/**
 * The Review Records of every wallet in the open session, newest first. Home shows the first few. A kept read is shown at once. Otherwise
 * the read runs off the main thread, and a new signature shows once it finishes. Null until then.
 */
@Composable
private fun rememberRecentActivity(
    navController: NavHostController,
    holder: VaultHolder,
    entry: NavBackStackEntry,
): List<WalletReviewRecord>? {
    var records by remember(entry) { mutableStateOf(holder.sessionReads()?.cachedRecent()) }
    LaunchedEffect(entry) {
        whileUnlocked(navController, holder) {
            val reads = holder.sessionReads() ?: return@whileUnlocked
            records = withContext(Dispatchers.IO) { reads.recent() }
        }
    }
    return records
}

/**
 * The readiness tiles: the networks and tokens of the catalog, the version of the installed catalog package, and the lockout cap. The
 * catalog and lockout reads run off the main thread. They run again each time Home is shown, so a package installed from Update catalog
 * or a changed cap shows at once. Null until the reads finish.
 */
@Composable
private fun rememberHomeReadiness(
    navController: NavHostController,
    holder: VaultHolder,
    entry: NavBackStackEntry,
): HomeReadiness? {
    val context = LocalContext.current.applicationContext
    var readiness by remember(entry) { mutableStateOf(holder.sessionReads()?.lastReadiness()) }
    LaunchedEffect(entry) {
        whileUnlocked(navController, holder) {
            val catalog = readCatalog(context)
            val (installed, baked) = withContext(Dispatchers.IO) {
                RegistryHolder.installed(context) to RegistryHolder.get(context).catalogSequence
            }
            val lockout = withContext(Dispatchers.IO) { readFailureCap(holder) }
            val fresh = HomeReadiness(
                networkCount = catalog.networks.size,
                tokenCount = catalog.tokens.size,
                // The sequence in effect: the installed package's only when it replaces the built-in one.
                catalogSequence = effectiveCatalogSequence(baked, installed?.sequence),
                lockout = lockout,
            )
            holder.sessionReads()?.keepReadiness(fresh)
            readiness = fresh
        }
    }
    return readiness
}

/** The Wallets list, every wallet as a card. It reads the wallets the same way Home does. */
@Composable
private fun WalletsRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val listing = rememberWalletListing(navController, holder, session)
    WalletsScreen(
        wallets = listing?.wallets,
        profiles = listing?.profiles.orEmpty(),
        accounts = listing?.accounts.orEmpty(),
        onOpen = { walletId -> navController.navigate(HiddenRoute.detail(walletId)) },
        onEdit = { walletId -> navController.navigate(HiddenRoute.editWallet(walletId)) },
        onPair = { walletId -> navController.navigate(HiddenRoute.export(walletId)) },
        onAdd = { navController.navigate(HiddenRoute.WALLET_START) },
        onBack = { navController.popBackStack() },
    )
}

/**
 * The wallets and their profiles while the vault is open, or null until the first read finishes. A profile is read with its
 * wallet, so a card never shows a default look that a profile replaces. A kept read is shown at once. Otherwise it is read off the
 * main thread, once a wallet or a Review Record has changed since the last read.
 */
@Composable
internal fun rememberWalletListing(
    navController: NavHostController,
    holder: VaultHolder,
    session: VaultSession?,
): WalletListing? {
    var listing by remember(session) { mutableStateOf(holder.sessionReads()?.cachedListing()) }
    LaunchedEffect(session) {
        if (session == null) return@LaunchedEffect
        whileUnlocked(navController, holder) {
            val reads = holder.sessionReads() ?: return@whileUnlocked
            listing = withContext(Dispatchers.IO) { reads.listing() }
        }
    }
    return listing
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
 * Restore: the owner types the 24 words one at a time. Letters build the word in progress. A word that no other BIP39
 * word starts with commits as soon as it is typed. SPACE or a suggestion commits the rest, and a filled slot can be
 * retyped. A complete phrase must pass [Mnemonic.isValid], or the screen shows the checksum error.
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
                    if (autocomplete.isUniqueWord(prefix)) commit(prefix)
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
 * means no passphrase. A mismatch goes back to entering it, and the words stay in the draft. The entry is zeroed as
 * soon as it has been copied into the draft.
 */
@Composable
private fun PassphraseRoute(navController: NavHostController, entry: NavBackStackEntry) {
    val draft = draftOf(navController, entry)
    RequireDraft(navController, ready = draft.words.isNotEmpty())
    val typed = remember {
        PassphraseEntry { settled ->
            draft.setPassphrase(settled)
            navController.navigate(HiddenRoute.NAME)
        }
    }
    DisposableEffect(Unit) {
        onDispose { typed.clear() }
    }

    PassphraseScreen(
        state = PassphraseState(
            step = typed.step,
            length = typed.length,
            mismatch = typed.mismatch,
            ledgerIssue = typed.ledgerIssue,
        ),
        onYes = { typed.startEntry() },
        onSkip = { typed.skip() },
        onKey = { c -> typed.key(c) },
        onBackspace = { typed.backspace() },
        onSubmit = { typed.submit() },
    )
}

/** Shown under the write-down or name screen when a save fails. The words or key stay, so the owner can try again. */
internal const val SAVE_FAILED_MESSAGE = "The wallet could not be saved. Try again."

/**
 * Names the wallet, then saves it and opens its detail. The suggested name counts the wallets already saved. An imported
 * key, and a wallet with a passphrase, keep their name in the draft and go on to their write-down, which saves the
 * wallet.
 */
@Composable
private fun NameRoute(navController: NavHostController, holder: VaultHolder, entry: NavBackStackEntry) {
    val model = draftModelOf(navController, entry)
    val draft = model.draft
    val save by model.saveState.collectAsState()
    // A saved draft is wiped, so the screen stays ready through its save until the flow ends.
    RequireDraft(
        navController,
        ready = save is SaveState.Saved || draft.words.isNotEmpty() || draft.singleKey != null,
    )
    val session = rememberOpenSession(navController, holder) ?: return
    val suggested by produceState<String?>(initialValue = null, session) {
        value = withContext(Dispatchers.IO) { "WALLET ${WalletStore(session).list().size + 1}" }
    }
    var name by remember { mutableStateOf("") }
    var refusal by remember { mutableStateOf<String?>(null) }
    val saving = save == SaveState.Saving
    // Back is ignored while the save runs, and the screen shows "Saving…" until it ends.
    BackHandler(enabled = saving) {}
    FollowSave(navController, holder, save)

    WalletNameScreen(
        name = name,
        placeholder = suggested,
        onKey = { c ->
            if (!saving && name.length < DISPLAY_NAME_MAX_LENGTH) name += c
        },
        onBackspace = {
            if (!saving && name.isNotEmpty()) name = name.dropLast(1)
        },
        errorMessage = refusal ?: if (save == SaveState.Failed) SAVE_FAILED_MESSAGE else null,
        saving = saving,
        onDone = {
            // A name refused here is never saved, so the owner sees why on this screen rather than a failed save.
            val finalName = effectiveWalletName(name, suggested)
            val reason = nameRefusal(finalName)
            refusal = reason
            if (!saving && reason == null && draft.singleKey != null) {
                draft.setName(finalName)
                navController.navigate(HiddenRoute.KEY_BACKUP)
            } else if (!saving && reason == null && draft.words.isNotEmpty()) {
                if (PassphraseGate.needsWriteDown(draft.passphrase)) {
                    draft.setName(finalName)
                    navController.navigate(HiddenRoute.PASSPHRASE_BACKUP)
                } else {
                    model.save(finalName, walletWriter(session, draft.backedUp))
                }
            }
        },
    )
}

/** Why [name] cannot be a wallet's name, or null when it can. */
private fun nameRefusal(name: String): String? = try {
    displayNameOf(name)
    null
} catch (e: IllegalArgumentException) {
    e.message
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
    val model = draftModelOf(navController, entry)
    val draft = model.draft
    val save by model.saveState.collectAsState()
    val draftReady = draft.words.isNotEmpty() && PassphraseGate.needsWriteDown(draft.passphrase)
    RequireDraft(navController, ready = save is SaveState.Saved || draftReady)
    val session = rememberOpenSession(navController, holder) ?: return
    var writtenDown by remember { mutableStateOf(false) }
    val saving = save == SaveState.Saving
    // Back is ignored while the save runs, and the button reads "Saving…" until it ends.
    BackHandler(enabled = saving) {}
    FollowSave(navController, holder, save)

    PassphraseBackupScreen(
        writtenDown = writtenDown,
        onWrittenDownChange = { writtenDown = it },
        errorMessage = if (save == SaveState.Failed) SAVE_FAILED_MESSAGE else null,
        saving = saving,
        onCreate = {
            if (!saving && PassphraseGate.canSave(draft.passphrase, writtenDown)) {
                model.save(draft.name, walletWriter(session, draft.backedUp))
            }
        },
    )
}

/**
 * Acts on the outcome of a save. A saved wallet opens its detail, and a vault that closed during the write returns the
 * owner to the camera. The outcome stays in the model until the flow ends, so a screen that is composed again after a
 * rotation still acts on it.
 */
@Composable
internal fun FollowSave(navController: NavHostController, holder: VaultHolder, state: SaveState) {
    LaunchedEffect(state) {
        when (state) {
            is SaveState.Saved -> navController.navigate(HiddenRoute.detail(state.walletId)) {
                popUpTo(HiddenRoute.HOME) { inclusive = false }
            }
            SaveState.Locked -> leaveHidden(navController, holder)
            SaveState.Idle, SaveState.Saving, SaveState.Failed -> Unit
        }
    }
}

/** The ViewModel that holds the draft of the hidden graph. It is resolved as [draftOf] resolves the draft. */
@Composable
internal fun draftModelOf(navController: NavHostController, entry: NavBackStackEntry): WalletDraftViewModel {
    val owner = remember(entry) { navController.getBackStackEntry(LenslyRoute.HIDDEN) }
    return viewModel<WalletDraftViewModel>(viewModelStoreOwner = owner)
}

/**
 * The writer of a draft's save: a new Mnemonic wallet with one account, in [session]. [seedBackedUp] is the draft's backed-up flag, read
 * before the save starts.
 */
private fun walletWriter(session: VaultSession, seedBackedUp: Boolean): WalletWriter = { name, deviceGenerated, words, passphrase ->
    WalletStore(session).create(
        name = name,
        deviceGenerated = deviceGenerated,
        words = words,
        passphrase = passphrase,
        nowMs = System.currentTimeMillis(),
        seedBackedUp = seedBackedUp,
    )
}

/**
 * The wallet screen of [walletId]: its name, its label and groups, its Apps with their account counts, and its options. The accounts
 * are on the Apps, so this route reads the wallet, its profile, and how many Bitcoin accounts it has.
 */
@Composable
private fun DetailRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val session = rememberOpenSession(navController, holder) ?: return
    var wallet by remember(walletId) { mutableStateOf<WalletMeta?>(null) }
    var walletProfile by remember(walletId) { mutableStateOf(WalletProfile()) }
    var position by remember(walletId) { mutableIntStateOf(0) }
    var accounts by remember(walletId) { mutableStateOf<Map<WalletApp, Int>?>(null) }

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
    LaunchedEffect(walletId) {
        whileUnlocked(navController, holder) {
            accounts = withContext(Dispatchers.IO) { WalletStore(session).accountCounts(walletId) }
        }
    }

    val shown = wallet ?: return
    // The App rows wait for the counts, so a row never shows a zero that is about to change.
    val counts = accounts ?: return
    WalletDetailScreen(
        wallet = shown,
        walletProfile = walletProfile,
        walletPosition = position,
        accounts = counts,
        onBack = { navController.popBackStack() },
        onOpenApp = { app -> navController.navigate(HiddenRoute.app(walletId, app)) },
        onManageApps = { navController.navigate(HiddenRoute.apps(walletId)) },
        onShowSecret = { navController.navigate(HiddenRoute.showSecret(walletId)) },
        onHistory = { navController.navigate(HiddenRoute.history(walletId)) },
        onEditWallet = { navController.navigate(HiddenRoute.editWallet(walletId)) },
        onDeleteWallet = { navController.navigate(HiddenRoute.deleteWallet(walletId)) },
    )
}

/**
 * The Apps of wallet [walletId], each installed or uninstalled from its row. The counts are read again after every change, so the rows show
 * the new state.
 */
@Composable
private fun AppsRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var accounts by remember(walletId) { mutableStateOf<Map<WalletApp, Int>?>(null) }
    var changes by remember(walletId) { mutableIntStateOf(0) }
    // Why the last change was refused or not saved, shown above the list. Null when the last change saved.
    var refusal by remember(walletId) { mutableStateOf<String?>(null) }

    LaunchedEffect(walletId, changes) {
        whileUnlocked(navController, holder) {
            accounts = withContext(Dispatchers.IO) { WalletStore(session).accountCounts(walletId) }
        }
    }

    fun change(write: (WalletStore) -> Unit) {
        scope.launch {
            whileUnlocked(navController, holder) {
                refusal = withContext(Dispatchers.IO) { refusalOf { write(WalletStore(session)) } }
                changes++
            }
        }
    }

    val counts = accounts ?: return
    WalletAppsScreen(
        accounts = counts,
        refusal = refusal,
        onBack = { navController.popBackStack() },
        onInstall = { app -> change { it.installApp(walletId, app) } },
        onUninstall = { app -> change { it.uninstallApp(walletId, app) } },
    )
}

/** What the owner is told when a change to an App is refused or cannot be saved, or null when [write] saved it. */
private fun refusalOf(write: () -> Unit): String? = try {
    write()
    null
} catch (e: IllegalArgumentException) {
    "This wallet's Apps cannot be changed."
} catch (e: IOException) {
    "The change could not be saved. Try again."
}

/**
 * Keeps an App's screens on the screen when [app] is installed in wallet [walletId]. Otherwise the owner is sent to the wallet's Apps screen,
 * and the App's screens, the App itself and any bulk run of it, leave the back stack, so Back never returns to them. Returns true when the
 * screen may show.
 */
internal fun NavController.keepInstalledApp(walletId: String, app: WalletApp, installed: Set<WalletApp>): Boolean {
    if (app in installed) return true
    showAppsScreen(walletId)
    return false
}

/**
 * Shows the Apps of wallet [walletId] in place of an App's screens, which leave the back stack. A bulk run is opened from its App's screen,
 * so the App's entry takes the run with it. A bulk run opened directly has no App entry, so its own entry leaves instead.
 */
private fun NavController.showAppsScreen(walletId: String) {
    val leaving = if (currentBackStack.value.any { it.destination.route == HiddenRoute.APP }) HiddenRoute.APP else HiddenRoute.BULK
    navigate(HiddenRoute.apps(walletId)) { popUpTo(leaving) { inclusive = true } }
}

/**
 * One App of wallet [walletId]. Ethereum lists the EVM accounts with their addresses and profiles, and Bitcoin lists the Bitcoin
 * accounts. The Ethereum view is kept by the open session while no write has passed it, so a visit shows its list at once. Otherwise
 * it is read off the main thread, and the screen shows its frame, and the addresses as they are derived. A run that comes back here
 * reads the view again, so it shows its new accounts.
 */
@Composable
private fun AppRoute(navController: NavHostController, holder: VaultHolder, walletId: String, app: WalletApp) {
    val session = rememberOpenSession(navController, holder) ?: return
    val reads = holder.sessionReads() ?: return
    val scope = rememberCoroutineScope()
    // Nothing of the App is shown or loaded until the check passes: an App the wallet does not have, such as one uninstalled since this
    // screen was opened, leads to the Apps screen instead.
    var allowed by remember(walletId, app) { mutableStateOf(false) }
    LaunchedEffect(walletId, app) {
        whileUnlocked(navController, holder) {
            val installed = withContext(Dispatchers.IO) { WalletStore(session).installedApps(walletId) }
            allowed = navController.keepInstalledApp(walletId, app, installed)
        }
    }
    if (!allowed) return
    var wallet by remember(walletId) { mutableStateOf<WalletMeta?>(null) }
    var ethereum by remember(walletId) { mutableStateOf(reads.cachedEthereumAccounts(walletId)) }
    var bitcoin by remember(walletId) { mutableStateOf<List<BitcoinAccountRecord>?>(null) }
    var solana by remember(walletId) { mutableStateOf<List<ByteArray>?>(null) }
    var algorand by remember(walletId) { mutableStateOf<List<ByteArray>?>(null) }
    // Why the last single add was refused at the account cap, shown on the account list. Null when the last add saved.
    var addRefusal by remember(walletId, app) { mutableStateOf<String?>(null) }

    LaunchedEffect(walletId, app) {
        whileUnlocked(navController, holder) {
            if (app == WalletApp.ETHEREUM) {
                if (ethereum == null) {
                    // The metadata comes first, so the App's frame shows while the addresses are derived.
                    wallet = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
                    ethereum = withContext(Dispatchers.IO) { reads.ethereumAccounts(walletId) }
                }
            } else if (app == WalletApp.SOLANA) {
                wallet = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
                solana = withContext(Dispatchers.IO) { WalletStore(session).solanaKeys(walletId) }
            } else if (app == WalletApp.ALGORAND) {
                wallet = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
                algorand = withContext(Dispatchers.IO) { WalletStore(session).algorandKeys(walletId) }
            } else {
                wallet = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
                bitcoin = withContext(Dispatchers.IO) { WalletStore(session).bitcoinAccounts(walletId) }
            }
        }
    }
    val shown = ethereum?.meta ?: wallet ?: return
    when (app) {
        WalletApp.ETHEREUM -> EthereumAccountsScreen(
            wallet = shown,
            addresses = ethereum?.addresses,
            accountProfiles = ethereum?.profiles.orEmpty(),
            refusal = addRefusal,
            onBack = { navController.popBackStack() },
            onAddAccount = {
                scope.launch {
                    whileUnlocked(navController, holder) {
                        // At the cap the add is refused and nothing is saved: the refusal is shown on the list. The write moves the
                        // session's write count, so the view is read again with the new account.
                        try {
                            addRefusal = withContext(Dispatchers.IO) {
                                try {
                                    WalletStore(session).addAccount(walletId)
                                    null
                                } catch (e: EthereumCapReached) {
                                    e.message
                                }
                            }
                        } catch (e: AppNotInstalled) {
                            // Ethereum was uninstalled while this list was open, so there is nothing to add to: the Apps screen is shown.
                            navController.showAppsScreen(walletId)
                            return@whileUnlocked
                        }
                        ethereum = withContext(Dispatchers.IO) { reads.ethereumAccounts(walletId) }
                    }
                }
            },
            onBulkGenerate = { navController.navigate(HiddenRoute.bulk(walletId, app)) },
            onPair = { navController.navigate(HiddenRoute.export(walletId)) },
            onScan = { navController.navigate(HiddenRoute.scan(walletId)) },
            onEditAccount = { index -> navController.navigate(HiddenRoute.editAccount(walletId, index)) },
            onExportAccounts = { indices -> navController.navigate(HiddenRoute.exportAccounts(walletId, indices)) },
            onRenameAccounts = { indices -> navController.navigate(HiddenRoute.renameAccounts(walletId, indices)) },
            onStyleAccounts = { indices -> navController.navigate(HiddenRoute.styleAccounts(walletId, indices)) },
            onResetAccountNames = { indices ->
                scope.launch {
                    whileUnlocked(navController, holder) {
                        // One write removes the selected names. The view is read again, so the rows show the Auto-Names.
                        ethereum = withContext(Dispatchers.IO) {
                            WalletStore(session).resetAccountNames(walletId, indices)
                            reads.ethereumAccounts(walletId)
                        }
                    }
                }
            },
        )
        WalletApp.SOLANA -> {
            val keys = solana ?: return
            SolanaAccountsScreen(
                wallet = shown,
                addresses = keys.map { SolanaAccount.addressOf(it) },
                refusal = addRefusal,
                onBack = { navController.popBackStack() },
                onAddAccount = {
                    scope.launch {
                        whileUnlocked(navController, holder) {
                            // At the cap the add is refused and nothing is saved: the refusal is shown on the list. The write moves
                            // the Solana record, so the list is read again and shows the new account.
                            addRefusal = withContext(Dispatchers.IO) {
                                try {
                                    val cap = SettingsStore(session).load().bulkCap.accounts
                                    WalletStore(session).addSolanaAccounts(walletId, 1, cap)
                                    null
                                } catch (e: SolanaCapReached) {
                                    e.message
                                }
                            }
                            solana = withContext(Dispatchers.IO) { WalletStore(session).solanaKeys(walletId) }
                        }
                    }
                },
                onBulkGenerate = { navController.navigate(HiddenRoute.bulk(walletId, app)) },
                onPair = { navController.navigate(HiddenRoute.export(walletId)) },
                onScan = { navController.navigate(HiddenRoute.scan(walletId)) },
            )
        }
        WalletApp.ALGORAND -> {
            val keys = algorand ?: return
            AlgorandAccountsScreen(
                wallet = shown,
                addresses = keys.map { AlgorandAccount.addressOf(it) },
                refusal = addRefusal,
                onBack = { navController.popBackStack() },
                onAddAccount = {
                    scope.launch {
                        whileUnlocked(navController, holder) {
                            // At the cap the add is refused and nothing is saved: the refusal is shown on the list. The write moves
                            // the Algorand record, so the list is read again and shows the new account.
                            addRefusal = withContext(Dispatchers.IO) {
                                try {
                                    val cap = SettingsStore(session).load().bulkCap.accounts
                                    WalletStore(session).addAlgorandAccounts(walletId, 1, cap)
                                    null
                                } catch (e: AlgorandCapReached) {
                                    e.message
                                }
                            }
                            algorand = withContext(Dispatchers.IO) { WalletStore(session).algorandKeys(walletId) }
                        }
                    }
                },
                onBulkGenerate = { navController.navigate(HiddenRoute.bulk(walletId, app)) },
                onPair = { navController.navigate(HiddenRoute.export(walletId)) },
                onScan = { navController.navigate(HiddenRoute.scan(walletId)) },
            )
        }
        WalletApp.BITCOIN -> {
            val accounts = bitcoin ?: return
            BitcoinAccountsScreen(
                wallet = shown,
                accounts = accounts,
                onBack = { navController.popBackStack() },
                onGenerate = { navController.navigate(HiddenRoute.bulk(walletId, app)) },
                onPair = { navController.navigate(HiddenRoute.export(walletId)) },
                onScan = { navController.navigate(HiddenRoute.scan(walletId)) },
                onEditAccount = { position -> navController.navigate(HiddenRoute.editBitcoinAccount(walletId, position)) },
            )
        }
    }
}

/** HISTORY of wallet [walletId]: its Review Records, newest first, read from the open session. The registry gives each network's logo. */
@Composable
private fun HistoryRoute(navController: NavHostController, holder: VaultHolder, walletId: String) {
    val session = rememberOpenSession(navController, holder) ?: return
    val context = LocalContext.current.applicationContext
    var wallet by remember(walletId) { mutableStateOf<WalletMeta?>(null) }
    var records by remember(walletId) { mutableStateOf<List<ReviewRecord>?>(null) }
    var networks by remember { mutableStateOf<List<NetworkEntry>?>(null) }
    val book = rememberAddressBook(navController, session)
    val owned = rememberInternalAccounts(navController, session, signingWalletId = walletId)
    LaunchedEffect(walletId) {
        whileUnlocked(navController, holder) {
            wallet = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
            records = withContext(Dispatchers.IO) { ReviewHistory(session).list(walletId) }
            networks = withContext(Dispatchers.IO) { RegistryHolder.get(context).networks() }
        }
    }
    val shown = wallet ?: return
    val list = records ?: return
    val known = networks ?: return
    val entries = book ?: return
    val accounts = owned ?: return
    WalletHistoryScreen(
        walletName = shown.name,
        records = list,
        networks = known,
        onBack = { navController.popBackStack() },
        addressBook = entries,
        accounts = accounts,
        onOpenAddress = { address, chain -> navController.openAddressBookFor(entries, address, chain) },
    )
}

/** ACTIVITY: the Review Records of every wallet in the open session, newest first, each with its wallet. The registry gives each network's logo. */
@Composable
private fun ActivityRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val context = LocalContext.current.applicationContext
    val listing = rememberWalletListing(navController, holder, session)
    var records by remember { mutableStateOf<List<WalletReviewRecord>?>(null) }
    var networks by remember { mutableStateOf<List<NetworkEntry>?>(null) }
    val book = rememberAddressBook(navController, session)
    val owned = rememberInternalAccounts(navController, session, signingWalletId = null)
    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            records = withContext(Dispatchers.IO) { ReviewHistory(session).listAll() }
            networks = withContext(Dispatchers.IO) { RegistryHolder.get(context).networks() }
        }
    }
    val list = records ?: return
    val known = networks ?: return
    val wallets = listing ?: return
    val entries = book ?: return
    val accounts = owned ?: return
    ActivityScreen(
        records = list,
        wallets = wallets.wallets,
        profiles = wallets.profiles,
        networks = known,
        onBack = { navController.popBackStack() },
        addressBook = entries,
        accounts = accounts,
        onOpenAddress = { address, chain -> navController.openAddressBookFor(entries, address, chain) },
    )
}

/**
 * EXPORT of the accounts [indices] of wallet [walletId]: each account's code, and its plain-text fields. An index the wallet does
 * not have, or an empty list, goes back to the wallet without showing anything. The accounts' addresses come from the wallet's
 * public data, so a Single Key's address comes from its key, as before.
 */
@Composable
private fun ExportAccountsRoute(navController: NavHostController, holder: VaultHolder, walletId: String, indices: List<Int>) {
    val session = rememberOpenSession(navController, holder) ?: return
    // The wallet's public data is read first, to check the accounts. Their codes are read only once the App Password is right.
    var wallet by remember(walletId, indices) { mutableStateOf<WalletMeta?>(null) }
    LaunchedEffect(walletId, indices) {
        whileUnlocked(navController, holder) {
            val meta = withContext(Dispatchers.IO) { WalletStore(session).meta(walletId) }
            if (meta == null || indices.isEmpty() || indices.any { it !in 0 until meta.accountCount }) {
                navController.popBackStack()
                return@whileUnlocked
            }
            wallet = meta
        }
    }
    val meta = wallet ?: return
    AccountExportGate(
        count = indices.size,
        checkPassword = { password -> checkAppPassword(holder, password) },
        load = {
            withContext(Dispatchers.IO) {
                val store = WalletStore(session)
                indices.map { index -> AccountExport.view(meta, index, store.accountAddress(walletId, index)) }
            }
        },
        onUnavailable = { navController.openHidden(HiddenRoute.UNAVAILABLE) },
        onDone = { navController.popBackStack() },
        onBack = { navController.popBackStack() },
    )
}

/**
 * The bulk screen of one wallet, which starts on the chain of [app]. GENERATE runs [WalletStore.addAccounts] off the main thread, and its progress is
 * shown. A finished run returns to the wallet, which reloads its accounts. A run that fails, or that finds the wallet
 * changed meanwhile, saves nothing, and the screen says so. Back is held while a run derives, so the owner waits.
 */
@Composable
private fun BulkRoute(navController: NavHostController, holder: VaultHolder, walletId: String, app: WalletApp) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    // Nothing of the App is shown or run until the check passes: an App the wallet does not have leads to the Apps screen instead, so no
    // account is generated for it.
    var allowed by remember(walletId, app) { mutableStateOf(false) }
    LaunchedEffect(walletId, app) {
        whileUnlocked(navController, holder) {
            val installed = withContext(Dispatchers.IO) { WalletStore(session).installedApps(walletId) }
            allowed = navController.keepInstalledApp(walletId, app, installed)
        }
    }
    if (!allowed) return
    var wallet by remember(walletId) { mutableStateOf<WalletMeta?>(null) }
    var bulkCap by remember(walletId) { mutableStateOf<BulkCap?>(null) }
    var digits by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // The run starts on the chain of its App: Ethereum on EVM, Bitcoin on the default Bitcoin account.
    var chain by remember(walletId, app) {
        mutableStateOf<BulkTarget>(
            when (app) {
                WalletApp.BITCOIN -> DEFAULT_BITCOIN
                WalletApp.SOLANA -> DEFAULT_SOLANA
                WalletApp.ALGORAND -> DEFAULT_ALGORAND
                WalletApp.ETHEREUM -> DEFAULT_EVM
            },
        )
    }
    var solanaCount by remember(walletId) { mutableStateOf<Int?>(null) }
    var algorandCount by remember(walletId) { mutableStateOf<Int?>(null) }
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
    LaunchedEffect(walletId, app) {
        if (app == WalletApp.SOLANA) {
            whileUnlocked(navController, holder) {
                solanaCount = withContext(Dispatchers.IO) { WalletStore(session).solanaKeys(walletId).size }
            }
        }
        if (app == WalletApp.ALGORAND) {
            whileUnlocked(navController, holder) {
                algorandCount = withContext(Dispatchers.IO) { WalletStore(session).algorandKeys(walletId).size }
            }
        }
    }
    val shown = wallet ?: return
    val cap = bulkCap ?: return
    // A Solana or Algorand run numbers after the accounts of its App, and every other run after the wallet's accounts.
    val existing = when (app) {
        WalletApp.SOLANA -> solanaCount ?: return
        WalletApp.ALGORAND -> algorandCount ?: return
        else -> shown.accountCount
    }

    BackHandler(enabled = running) {}
    BulkGenerateScreen(
        existingAccounts = existing,
        autoNameBase = shown.autoNameBase,
        cap = cap,
        digits = digits,
        running = running,
        derived = derived,
        failure = failure,
        onDigits = { typed -> if (!running) digits = typed },
        chain = chain,
        onChain = { chosen -> chain = chosen },
        bitcoinAllowed = shown.kind == WalletKind.MNEMONIC,
        onGenerate = generate@{
            val check = BulkPolicy.checkTyped(digits, cap.accounts)
            val count = (check as? BulkPolicy.CountCheck.Valid)?.count
            val chosenRun = chain
            // Every run but Ethereum's needs a seed. The screen disables GENERATE for a Single Key, and this refuses it too.
            if (running || count == null || (chosenRun.needsSeed && shown.kind != WalletKind.MNEMONIC)) {
                return@generate
            }
            running = true
            failure = null
            derivedCount.value = 0
            scope.launch {
                whileUnlocked(navController, holder) {
                    val saved = try {
                        withContext(Dispatchers.IO) {
                            val progress: (Int) -> Unit = { derivedCount.value = it }
                            runBulk(WalletStore(session), walletId, chosenRun, count, cap.accounts, progress)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IllegalStateException) {
                        // A closed session or a damaged vault. whileUnlocked returns the owner to the camera.
                        throw e
                    } catch (e: BitcoinCapReached) {
                        // The owner is told the cap, and nothing is saved.
                        running = false
                        failure = e.message
                        return@whileUnlocked
                    } catch (e: Exception) {
                        running = false
                        failure = "Run failed. Nothing was saved"
                        return@whileUnlocked
                    }
                    running = false
                    if (!saved) {
                        failure = "Wallet changed. Nothing was saved"
                    } else {
                        navController.popBackStack()
                    }
                }
            }
        },
        onBack = { navController.popBackStack() },
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
