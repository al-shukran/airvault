package app.lensly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import app.lensly.BuildConfig
import app.lensly.app.AppSettings
import app.lensly.app.HideDelay
import app.lensly.app.DecoyVault
import app.lensly.app.SettingsStore
import app.lensly.app.VaultHolder
import app.lensly.app.entryGestureHolder
import app.lensly.app.withFeeRate
import app.lensly.app.withFeeShare
import app.lensly.app.withHideDelayMax
import app.lensly.app.withHideDelayMin
import app.lensly.core.btc.InputVerificationPolicy
import app.lensly.core.btc.SigningSourcePolicy
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.VaultException
import app.lensly.core.vault.VaultSession
import app.lensly.ui.settings.Catalog
import app.lensly.ui.settings.ChangeOutcome
import app.lensly.ui.settings.ChangePasswordFlow
import app.lensly.ui.settings.changeAppPassword
import app.lensly.ui.settings.ChangeCopy
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.settings.BitcoinPolicyScreen
import app.lensly.ui.settings.FeeWarningScreen
import app.lensly.ui.settings.HideDelayScreen
import app.lensly.ui.settings.SettingsScreen
import app.lensly.ui.settings.NoticeBlock
import app.lensly.ui.settings.ThirdPartyLicencesScreen
import app.lensly.ui.settings.readThirdPartyNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The Settings screen and the App Password change, in the hidden graph. */
internal fun NavGraphBuilder.settingsRoutes(navController: NavHostController, holder: VaultHolder) {
    composable(HiddenRoute.SETTINGS) { HiddenBackdrop { SettingsRoute(navController, holder) } }
    composable(HiddenRoute.CHANGE_PASSWORD) { HiddenBackdrop { ChangePasswordRoute(navController, holder) } }
    composable(HiddenRoute.DURESS) { HiddenBackdrop { ChangePasswordRoute(navController, holder, duress = true) } }
    composable(HiddenRoute.DURESS_REMOVE) { HiddenBackdrop { RemoveDuressRoute(navController, holder) } }
    composable(HiddenRoute.FAILURE_CAP) { HiddenBackdrop { FailureCapRoute(navController, holder) } }
    composable(HiddenRoute.HIDE_DELAY) { HiddenBackdrop { HideDelayRoute(navController, holder) } }
    composable(HiddenRoute.BITCOIN_POLICY) { HiddenBackdrop { BitcoinPolicyRoute(navController, holder) } }
    composable(HiddenRoute.BITCOIN_FEE_WARNINGS) { HiddenBackdrop { BitcoinFeeWarningRoute(navController, holder) } }
    composable(HiddenRoute.PROXY_POLICY) { HiddenBackdrop { ProxyPolicyRoute(navController, holder) } }
    composable(HiddenRoute.LICENCES) { HiddenBackdrop { LicencesRoute(navController) } }
    composable(HiddenRoute.WIPE) { HiddenBackdrop { WipeRoute(navController, holder) } }
    entryGestureRoutes(navController, holder)
    catalogRoutes(navController, holder)
}

/**
 * Settings: the saved Bulk Cap, which a tap on an option saves at once, the wrong password lockout, the catalog, and the
 * About notices. Every store read and write runs off the main thread, and a closed session returns the owner to the camera.
 */
@Composable
private fun SettingsRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val context = LocalContext.current
    val entryGestures = context.entryGestureHolder()
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    var catalog by remember { mutableStateOf<Catalog?>(null) }
    var failureCap by remember { mutableStateOf<FailureCapChoice?>(null) }
    var proxyPolicy by remember { mutableStateOf<ProxyPolicy?>(null) }

    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            settings = withContext(Dispatchers.IO) { SettingsStore(session).load() }
        }
    }
    // Read again each time the screen is shown, so a package installed from Update catalog shows, and a lockout or a proxy
    // policy saved on its screen shows.
    val entry by navController.currentBackStackEntryAsState()
    LaunchedEffect(entry) {
        catalog = readCatalog(context)
        failureCap = readFailureCap(holder)
        proxyPolicy = readProxyPolicy(holder)
        // A hide delay saved on its screen shows here when the owner comes back.
        whileUnlocked(navController, holder) {
            settings = withContext(Dispatchers.IO) { SettingsStore(session).load() }
        }
    }

    SettingsScreen(
        bulkCap = settings?.bulkCap,
        onBulkCap = { cap ->
            scope.launch {
                whileUnlocked(navController, holder) {
                    // Only the cap changes, so the saved Proxy Policy is kept.
                    val next = withContext(Dispatchers.IO) {
                        SettingsStore(session).let { store -> store.load().copy(bulkCap = cap).also(store::save) }
                    }
                    settings = next
                }
            }
        },
        unknownCallPolicy = settings?.unknownCallPolicy,
        onUnknownCallPolicy = { policy ->
            scope.launch {
                whileUnlocked(navController, holder) {
                    // Only the policy changes, so the other saved settings are kept.
                    val next = withContext(Dispatchers.IO) {
                        SettingsStore(session).let { store -> store.load().copy(unknownCallPolicy = policy).also(store::save) }
                    }
                    settings = next
                }
            }
        },
        onChangePassword = { navController.navigate(HiddenRoute.CHANGE_PASSWORD) },
        entryGesture = entryGestures.current,
        onEntryGesture = { navController.navigate(HiddenRoute.ENTRY_GESTURE) },
        failureDelayCap = failureCap,
        onFailureDelayCap = { navController.navigate(HiddenRoute.FAILURE_CAP) },
        catalog = catalog,
        onNetworks = { navController.navigate(HiddenRoute.NETWORKS) },
        onTokens = { navController.navigate(HiddenRoute.TOKENS) },
        onApprovedContracts = { navController.navigate(HiddenRoute.APPROVED_CONTRACTS) },
        onAddressBook = { navController.navigate(HiddenRoute.ADDRESS_BOOK) },
        proxyPolicy = proxyPolicy,
        onProxyPolicy = { navController.navigate(HiddenRoute.PROXY_POLICY) },
        hideDelay = settings?.hideDelay,
        bitcoinInputPolicy = settings?.bitcoinInputPolicy,
        bitcoinSigningSource = settings?.bitcoinSigningSource,
        onBitcoinPolicy = { navController.navigate(HiddenRoute.BITCOIN_POLICY) },
        bitcoinFeeThresholds = settings?.bitcoinFeeThresholds,
        onBitcoinFeeWarnings = { navController.navigate(HiddenRoute.BITCOIN_FEE_WARNINGS) },
        onHideDelay = { navController.navigate(HiddenRoute.HIDE_DELAY) },
        onUpdateCatalog = { navController.navigate(HiddenRoute.CATALOG) },
        onThirdPartyLicences = { navController.navigate(HiddenRoute.LICENCES) },
        onWipe = { navController.navigate(HiddenRoute.WIPE) },
        onDuressPassword = { navController.navigate(HiddenRoute.DURESS) },
        onRemoveDuressPassword = { navController.navigate(HiddenRoute.DURESS_REMOVE) },
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        onBack = { navController.popBackStack() },
    )
}

/** The third-party licences, from the notice packed into the app. The text is read off the main thread when the screen opens. */
@Composable
private fun LicencesRoute(navController: NavHostController) {
    val context = LocalContext.current
    var blocks by remember { mutableStateOf<List<NoticeBlock>?>(null) }
    LaunchedEffect(Unit) {
        blocks = readThirdPartyNotice(context)
    }
    ThirdPartyLicencesScreen(blocks = blocks, onBack = { navController.popBackStack() })
}

/**
 * The shortest and the longest hide delay of the dice and coin tiles, as two number fields on one screen. Each number is saved as it
 * is typed while the range stays one Settings accepts, so the screen never shows a range that is not saved. The saved range is read
 * back when the screen opens.
 */
@Composable
private fun HideDelayRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var shortest by remember { mutableStateOf<Int?>(null) }
    var longest by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            val saved = withContext(Dispatchers.IO) { SettingsStore(session).load().hideDelay }
            shortest = saved.minMs.toInt()
            longest = saved.maxMs.toInt()
        }
    }
    HideDelayScreen(
        shortest = shortest,
        longest = longest,
        onShortest = { min ->
            shortest = min
            scope.launch { writeSettings(navController, holder, session) { it.withHideDelayMin(min.toLong()) } }
        },
        onLongest = { max ->
            longest = max
            scope.launch { writeSettings(navController, holder, session) { it.withHideDelayMax(max.toLong()) } }
        },
        onBack = { navController.popBackStack() },
    )
}

/**
 * The Bitcoin policies: how input amounts are verified and which online side may ask for a Bitcoin signature. The saved choices are read
 * back when the screen opens. A tap applies its choice at once, and only that field of the saved settings is written.
 */
@Composable
private fun BitcoinPolicyRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var inputPolicy by remember { mutableStateOf(InputVerificationPolicy.STRICT) }
    var signingSource by remember { mutableStateOf(SigningSourcePolicy.ANY) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            val saved = withContext(Dispatchers.IO) { SettingsStore(session).load() }
            inputPolicy = saved.bitcoinInputPolicy
            signingSource = saved.bitcoinSigningSource
            ready = true
        }
    }
    BitcoinPolicyScreen(
        inputPolicy = inputPolicy,
        signingSource = signingSource,
        ready = ready,
        onInputPolicy = { policy ->
            inputPolicy = policy
            scope.launch { writeSettings(navController, holder, session) { it.copy(bitcoinInputPolicy = policy) } }
        },
        onSigningSource = { source ->
            signingSource = source
            scope.launch { writeSettings(navController, holder, session) { it.copy(bitcoinSigningSource = source) } }
        },
        onBack = { navController.popBackStack() },
    )
}

/** Serialises the writes of the saved settings, so two quick changes cannot overwrite each other. */
private val settingsWrites = Mutex()

/**
 * Applies [change] to the saved settings and writes them, keeping every other saved setting. The screen has already shown the change.
 * A null from [change] means the value is refused, so nothing is written. The write waits for the writes before it, and does nothing
 * if the vault is locked.
 */
private suspend fun writeSettings(
    navController: NavHostController,
    holder: VaultHolder,
    session: VaultSession,
    change: (AppSettings) -> AppSettings?,
) {
    settingsWrites.withLock {
        whileUnlocked(navController, holder) {
            withContext(Dispatchers.IO) {
                SettingsStore(session).let { store -> store.load().let(change)?.also(store::save) }
            }
        }
    }
}

/** The saved hide delay, or the default when the vault cannot be read. The dice and coin screens start from it. */
internal suspend fun readHideDelay(holder: VaultHolder): HideDelay = withContext(Dispatchers.IO) {
    val session = holder.openSession() ?: return@withContext HideDelay.DEFAULT
    try {
        SettingsStore(session).load().hideDelay
    } catch (e: IllegalStateException) {
        HideDelay.DEFAULT
    }
}

/**
 * The fee warnings of a Bitcoin review: the fee rate and the fee share above which it shows a WARN notice. Each limit is saved as it
 * changes, and only that limit is written, so the other saved settings are kept. The saved limits are read back when the screen opens.
 */
@Composable
private fun BitcoinFeeWarningRoute(navController: NavHostController, holder: VaultHolder) {
    val session = rememberOpenSession(navController, holder) ?: return
    val scope = rememberCoroutineScope()
    var rate by remember { mutableStateOf<Int?>(null) }
    var share by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(session) {
        whileUnlocked(navController, holder) {
            val saved = withContext(Dispatchers.IO) { SettingsStore(session).load().bitcoinFeeThresholds }
            rate = saved.highFeeRateSatPerVb
            share = saved.feeSharePercent
        }
    }
    FeeWarningScreen(
        rate = rate,
        share = share,
        onRate = { value ->
            rate = value
            scope.launch { writeSettings(navController, holder, session) { it.withFeeRate(value) } }
        },
        onShare = { value ->
            share = value
            scope.launch { writeSettings(navController, holder, session) { it.withFeeShare(value) } }
        },
        onBack = { navController.popBackStack() },
    )
}

/**
 * Change the App Password, or set the Duress Password, in the hidden graph. The steps and their checks are shared with Lumio through
 * [ChangePasswordFlow]. The App Password is re-wrapped by [changeAppPassword], and the Duress Password is set by [setDuressWith].
 */
@Composable
private fun ChangePasswordRoute(navController: NavHostController, holder: VaultHolder, duress: Boolean = false) {
    ChangePasswordFlow(
        holder = holder,
        copy = if (duress) ChangeCopy.DURESS_PASSWORD else ChangeCopy.APP_PASSWORD,
        change = { password -> if (duress) setDuressWith(holder, password) else changeAppPassword(holder, password) },
        onLeave = { navController.popBackStack() },
        onLocked = { leaveHidden(navController, holder) },
        onUnavailable = { navController.openHidden(HiddenRoute.UNAVAILABLE) },
    )
}

/**
 * Sets the Duress Password from the open session, with the decoy's first wallet made by [DecoyVault]. On a decoy session the
 * same call changes the decoy's own password. NonCancellable, so a set that has started runs to its outcome. The vault zeroes
 * [password] on every path.
 */
private suspend fun setDuressWith(holder: VaultHolder, password: CharArray): ChangeOutcome =
    withContext(NonCancellable) {
        val session = holder.openSession()
        if (session == null) {
            password.fill('\u0000')
            return@withContext ChangeOutcome.Locked
        }
        try {
            when (val result = holder.vault.setDuress(session, password, DecoyVault::populate)) {
                DuressResult.Enabled -> ChangeOutcome.Changed
                is DuressResult.PolicyRejected -> ChangeOutcome.Refused(result.reason)
                DuressResult.SameAsAppPassword -> ChangeOutcome.SameAsApp
                DuressResult.DecoyEmpty, DuressResult.Failed -> ChangeOutcome.Unavailable
            }
        } catch (e: VaultException) {
            ChangeOutcome.Unavailable
        } catch (e: IllegalStateException) {
            // The session was closed by a lock while the set ran.
            ChangeOutcome.Locked
        }
    }
