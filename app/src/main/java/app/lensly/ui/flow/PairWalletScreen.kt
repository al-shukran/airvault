package app.lensly.ui.flow

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import app.lensly.R
import app.lensly.app.RegistryHolder
import app.lensly.app.VaultHolder
import app.lensly.app.WalletKind
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletStore
import app.lensly.app.export.PairingExport
import app.lensly.core.registry.WalletEntry
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.components.LoadingState
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.gate.PasswordBuffer
import app.lensly.ui.home.WalletCard
import app.lensly.ui.navigation.HiddenRoute
import app.lensly.ui.navigation.LenslyRoute
import app.lensly.ui.navigation.openHidden
import app.lensly.ui.navigation.rememberWalletListing
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.wallet.PasswordStep
import app.lensly.ui.wallet.WalletEyebrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_PAIR_NAME = "Lensly"
private const val PAIR_TITLE = "Pair Wallet"
private const val PAIR_PASSWORD_PROMPT = "Enter your Application Password to show the pairing code."
private const val STEP_ENTER_MS = 240
private const val STEP_EXIT_MS = 160

/** The message Home shows once a pairing is done. */
internal const val PAIRED_MESSAGE = "Wallet Paired"

/** The four steps of the pairing wizard, in order. The wallet step is skipped when there is only one wallet to pair. */
internal enum class PairStep { WALLET, APP, INSTRUCTIONS, CODE }

/**
 * The online wallets that pair with Lensly, the first one recommended. Each one is paired with one code for the whole wallet, so an App
 * is never paired on its own. [LUMIO] is the AirVault companion app: it reads the AirVault pairing, which holds the keys and names of every
 * App of the wallet. [RABBY] and [METAMASK] read only the Ethereum account key, as one ur:crypto-hdkey, because they hold no Bitcoin
 * account.
 */
internal enum class PairFormat(
    @StringRes val labelRes: Int,
    @StringRes val instructionRes: Int,
    val walletName: String?,
    val recommended: Boolean = false,
) {
    LUMIO(
        labelRes = R.string.pair_format_lumio_label,
        instructionRes = R.string.pair_format_lumio_instruction,
        // Not in the Wallet Compatibility Registry, so there is no Wallet Entry note to show.
        walletName = null,
        recommended = true,
    ),
    RABBY(
        labelRes = R.string.pair_format_rabby_label,
        instructionRes = R.string.pair_format_rabby_instruction,
        walletName = "Rabby",
    ),
    METAMASK(
        labelRes = R.string.pair_format_metamask_label,
        instructionRes = R.string.pair_format_metamask_instruction,
        walletName = "MetaMask",
    ),
}

/**
 * The pairing code for [wallet]: the account key m/44'/60'/0' of account 0, with the fingerprints, named after the wallet, or "Lensly" when it
 * has no name. Public data only. The fingerprint is stored as hex, so it is read as an unsigned value.
 */
internal fun pairingParts(wallet: WalletMeta): List<String> = PairingExport.urParts(
    accountKeys = requireNotNull(wallet.accountKeys.takeIf { it.isNotEmpty() }) { "Only a Mnemonic wallet has an account public key." },
    masterFingerprint = requireNotNull(wallet.masterFingerprintHex) { "Only a Mnemonic wallet has a fingerprint." }
        .toLong(16)
        .toInt(),
    name = wallet.name.ifBlank { DEFAULT_PAIR_NAME },
)

/**
 * PAIR WALLET, as the wizard. [walletId] names the wallet when the route was opened for one, as a wallet's own pairing action
 * does. Null means the owner opened it from the dock and chooses. It reads only public wallet data, so the open session is all
 * it needs. When the code is done, Home shows the confirmation and this route goes back to it.
 */
@Composable
internal fun PairWalletRoute(navController: NavHostController, holder: VaultHolder, walletId: String?) {
    val session = holder.openSession()
    LaunchedEffect(session == null) {
        if (session == null) navController.openHidden(HiddenRoute.GATE)
    }
    val listing = rememberWalletListing(navController, holder, session)
    val haptics = LocalHapticFeedback.current
    // The Wallet Compatibility Registry is read off the main thread, and each wallet's note appears when it arrives.
    val context = LocalContext.current
    val walletEntries by produceState(initialValue = emptyList<WalletEntry>(), context) {
        value = withContext(Dispatchers.IO) { RegistryHolder.get(context).walletEntries() }
    }
    if (listing == null) {
        // The wallets are read off the main thread. Until they arrive, the screen says so instead of staying blank.
        PairLoading(label = "Loading Wallets", onBack = { navController.popBackStack() })
        return
    }
    val preselected = walletId?.let { id -> listing.wallets.firstOrNull { it.id == id } }
    if (walletId != null && preselected == null) {
        LaunchedEffect(walletId) { navController.popBackStack() }
        return
    }
    PairWizard(
        wallets = listing.wallets,
        profiles = listing.profiles,
        preselected = preselected,
        walletEntries = walletEntries,
        lumioParts = { meta ->
            withContext(Dispatchers.IO) {
                WalletStore(requireNotNull(session) { "The vault is locked." }).pairingParts(meta.id)
            }
        },
        checkPassword = { password -> checkAppPassword(holder, password) },
        onUnavailable = { navController.openHidden(HiddenRoute.UNAVAILABLE) },
        onDone = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            navController.getBackStackEntry(LenslyRoute.HIDDEN).savedStateHandle[HiddenRoute.NOTICE] = PAIRED_MESSAGE
            if (!navController.popBackStack(HiddenRoute.HOME, inclusive = false)) {
                navController.openHidden(HiddenRoute.HOME)
            }
        },
        onLeave = { navController.popBackStack() },
    )
}

/**
 * The pairing wizard, with its steps: the wallet, then the online wallet, its steps, and the code. A [preselected] wallet, or the
 * only wallet, skips the first step. [onDone] runs when the code is done, and [onLeave] when the owner backs out of the first
 * step shown. Back moves one step at a time.
 */
@Composable
internal fun PairWizard(
    wallets: List<WalletMeta>,
    profiles: Map<String, WalletProfile>,
    preselected: WalletMeta?,
    onDone: () -> Unit,
    onLeave: () -> Unit,
    walletEntries: List<WalletEntry> = emptyList(),
    /** The AirVault pairing of a wallet: the keys and names of every App it holds, in one code, read off the main thread. */
    lumioParts: suspend (WalletMeta) -> List<String> = { emptyList() },
    /** The code of Rabby or MetaMask: the Ethereum account key of each account of the wallet, read off the main thread. */
    ethereumParts: (WalletMeta) -> List<String> = ::pairingParts,
    /**
     * Checks the App Password before a pairing code is built. The wizard owns the password and zeroes it. The default refuses every
     * password, so a caller that does not check one never shows the code.
     */
    checkPassword: suspend (CharArray) -> PasswordCheck = { PasswordCheck.Rejected },
    /** The check could not read the vault, so the wizard leaves for the unavailable notice. */
    onUnavailable: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val skipsWalletStep = preselected != null || wallets.size == 1
    var wallet by remember { mutableStateOf(preselected ?: wallets.singleOrNull()) }
    var format by remember { mutableStateOf<PairFormat?>(null) }
    var step by remember { mutableStateOf(if (skipsWalletStep) PairStep.APP else PairStep.WALLET) }
    // Every pairing code holds the wallet's account keys, so it is built only once the App Password is right. It is locked again when
    // the owner leaves the code, and when the app leaves the foreground.
    var codeUnlocked by remember { mutableStateOf(false) }
    val chosen = wallet
    val inCode = step == PairStep.CODE
    // The AirVault code needs the seed's account keys, so only a Mnemonic wallet, the one with an account public key, has a code.
    val hasAccountKeys = chosen?.accountKeys?.isNotEmpty() == true
    // The code is built only when the owner reaches it and the App Password is right, from public wallet data.
    val frames by produceState<List<String>?>(initialValue = null, chosen, inCode, format, hasAccountKeys, codeUnlocked) {
        value = when {
            !inCode || !codeUnlocked || chosen == null -> null
            format == PairFormat.LUMIO -> if (hasAccountKeys) lumioParts(chosen) else null
            else -> withContext(Dispatchers.Default) { ethereumParts(chosen) }
        }
    }
    val passwordAsked = inCode && !codeUnlocked
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val password = remember { PasswordBuffer() }
    var passwordLength by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                password.clear()
                passwordLength = 0
                codeUnlocked = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            password.clear()
        }
    }
    fun back() {
        // A check that is running cannot be left, as at the gate, so the owner is never sent back to a code that then unlocks.
        if (checking) return
        when (step) {
            PairStep.WALLET -> onLeave()
            PairStep.APP -> if (skipsWalletStep) onLeave() else step = PairStep.WALLET
            PairStep.INSTRUCTIONS -> step = PairStep.APP
            PairStep.CODE -> {
                password.clear()
                passwordLength = 0
                codeUnlocked = false
                step = PairStep.INSTRUCTIONS
            }
        }
    }
    fun submitPassword() {
        if (checking || password.isEmpty()) return
        val entered = password.toCharArray()
        password.clear()
        passwordLength = 0
        checking = true
        scope.launch {
            val check = try {
                checkPassword(entered)
            } finally {
                entered.fill('\u0000')
            }
            checking = false
            when (check) {
                // A check that ends after the app left the foreground does not unlock the code, as the lock on leaving has already run.
                PasswordCheck.Accepted -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) codeUnlocked = true
                PasswordCheck.Rejected -> Unit
                PasswordCheck.Unavailable -> onUnavailable()
            }
        }
    }
    BackHandler { back() }
    if (passwordAsked) {
        // The App Password step, as Show Secret asks it. It is the code's step, so it reads step 4 of 4 like the code does.
        PasswordStep(
            busy = checking,
            length = passwordLength,
            onKey = { c ->
                if (!checking && password.append(c)) passwordLength = password.length
            },
            onBackspace = {
                if (!checking) {
                    password.backspace()
                    passwordLength = password.length
                }
            },
            onSubmit = { submitPassword() },
            onBack = { back() },
            modifier = modifier,
            title = PAIR_TITLE,
            prompt = PAIR_PASSWORD_PROMPT,
            stepCount = PairStep.entries.size,
            step = PairStep.CODE.ordinal + 1,
        )
    } else {
        PairWizardScreen(
            step = step,
            wallets = wallets,
            profiles = profiles,
            wallet = chosen,
            format = format,
            walletEntry = format?.walletName?.let { name -> walletEntries.firstOrNull { it.name == name } },
            frames = frames,
            onPickWallet = { picked ->
                wallet = picked
                step = PairStep.APP
            },
            onPickFormat = { picked ->
                format = picked
                step = PairStep.INSTRUCTIONS
            },
            onShowCode = { step = PairStep.CODE },
            onDone = onDone,
            onBack = { back() },
            modifier = modifier,
        )
    }
}

/**
 * The wizard's screen for one [step]: the title, the step indicator, the step's content and its action button. It holds no
 * state of its own, so every step can be shown on its own.
 */
@Composable
internal fun PairWizardScreen(
    step: PairStep,
    wallets: List<WalletMeta>,
    profiles: Map<String, WalletProfile>,
    wallet: WalletMeta?,
    format: PairFormat?,
    /** The Wallet Entry of the chosen online wallet, or null when the registry has none. Its note shows on the instructions step. */
    walletEntry: WalletEntry? = null,
    frames: List<String>?,
    onPickWallet: (WalletMeta) -> Unit,
    onPickFormat: (PairFormat) -> Unit,
    onShowCode: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action: (@Composable ColumnScope.() -> Unit)? = when (step) {
        PairStep.INSTRUCTIONS -> {
            {
                PillButton(
                    text = "Show Code",
                    onClick = onShowCode,
                    accent = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        PairStep.CODE -> {
            { PillButton(text = "Done", onClick = onDone, accent = true, modifier = Modifier.fillMaxWidth()) }
        }
        else -> null
    }
    LenslyScreen(
        title = PAIR_TITLE,
        modifier = modifier,
        onBack = onBack,
        // The code takes the space that is left, so it stays whole on a small screen.
        scrollable = step != PairStep.CODE,
        bottomAction = action,
    ) {
        PairStepIndicator(step = step)
        Spacer(Modifier.height(LenslySpacing.lg))
        AnimatedContent(
            targetState = step,
            modifier = (if (step == PairStep.CODE) Modifier.weight(1f) else Modifier).fillMaxWidth(),
            transitionSpec = {
                val forward = targetState > initialState
                val enter = fadeIn(tween(STEP_ENTER_MS)) +
                    slideInHorizontally(tween(STEP_ENTER_MS)) { if (forward) it / 8 else -it / 8 }
                val exit = fadeOut(tween(STEP_EXIT_MS)) +
                    slideOutHorizontally(tween(STEP_EXIT_MS)) { if (forward) -it / 8 else it / 8 }
                enter togetherWith exit
            },
            label = "pair-step",
        ) { shown ->
            Column(modifier = if (shown == PairStep.CODE) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                when (shown) {
                    PairStep.WALLET -> WalletStep(wallets = wallets, profiles = profiles, onPick = onPickWallet)
                    PairStep.APP ->
                        if (wallet?.kind == WalletKind.SINGLE_KEY) SingleKeyNotice() else FormatStep(onPick = onPickFormat)
                    PairStep.INSTRUCTIONS -> InstructionsStep(format = format, walletEntry = walletEntry)
                    PairStep.CODE -> CodeStep(format = format, frames = frames)
                }
            }
        }
    }
}

/** Four segments, one per step. The segments up to the current step fill, and the fill animates as the owner moves on. */
@Composable
private fun PairStepIndicator(step: PairStep) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xxs)) {
            PairStep.entries.forEach { segment ->
                val colour by animateColorAsState(
                    targetValue = if (segment <= step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                    label = "pair-segment",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(LenslyPillShape)
                        .background(colour),
                )
            }
        }
        Spacer(Modifier.height(LenslySpacing.xs))
        WalletEyebrow("Step ${step.ordinal + 1} of ${PairStep.entries.size}")
    }
}

/** Step one: the wallet to pair. Each wallet is a card, and a tap chooses it. */
@Composable
private fun WalletStep(wallets: List<WalletMeta>, profiles: Map<String, WalletProfile>, onPick: (WalletMeta) -> Unit) {
    Text(
        text = "Choose the wallet you are pairing. Only its public account key is shared.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(LenslySpacing.lg))
    if (wallets.isEmpty()) {
        Text(
            text = "There are no wallets to pair yet. Create or restore one first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        return
    }
    wallets.forEachIndexed { position, wallet ->
        WalletCard(
            wallet = wallet,
            profile = profiles[wallet.id] ?: WalletProfile(),
            position = position,
            compact = true,
            onClick = { onPick(wallet) },
        )
        Spacer(Modifier.height(LenslySpacing.sm))
    }
}

/** Step two: the online wallet being paired. The app never guesses it. */
@Composable
private fun FormatStep(onPick: (PairFormat) -> Unit) {
    Text(
        text = "Choose the online wallet you are pairing with. Each one has its own steps.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(LenslySpacing.lg))
    PairFormat.entries.forEach { format ->
        PairOptionCard(format = format, onClick = { onPick(format) })
        Spacer(Modifier.height(LenslySpacing.sm))
    }
}

/**
 * Step two for a Single Key. Pairing hands an online wallet an account's extended public key and derivation path, and a single
 * key has neither, so no code is shown. Signing does not need pairing: SIGN reads a request that names the wallet's address.
 */
@Composable
private fun SingleKeyNotice() {
    InlineBanner(
        title = "Cannot Be Paired",
        message = "Pairing shares an account's extended public key and derivation path, and a single key has " +
            "neither. An online wallet that pairs by extended public key cannot watch it.",
        tone = BannerTone.Warning,
    )
    Spacer(Modifier.height(LenslySpacing.lg))
    Text(
        text = "To sign with this key, open SIGN on this wallet and scan a request that names its address.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Step three: the steps for the chosen app, with a drawing of one phone scanning the other, and its Wallet Entry note. */
@Composable
private fun InstructionsStep(format: PairFormat?, walletEntry: WalletEntry?) {
    PairIllustration(modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(LenslySpacing.lg))
    if (format == null) return
    Text(
        text = stringResource(format.instructionRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(LenslySpacing.sm))
    Text(
        text = "Then tap Show Code, and hold the camera of your online wallet over the code on this screen.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (walletEntry != null) {
        Spacer(Modifier.height(LenslySpacing.lg))
        WalletEntryNote(entry = walletEntry)
    }
}

/**
 * The Wallet Entry review note for the chosen online wallet: the support statement, the code formats the vault reads and writes,
 * and the known limitations. It is the owner's reading before pairing, not a condition for it.
 */
@Composable
private fun WalletEntryNote(entry: WalletEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(LenslySpacing.md),
    ) {
        Text(
            text = "Wallet Entry",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = entry.note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "Formats: ${entry.formats.joinToString(", ")}. Apps: ${entry.ecosystems.joinToString(", ")}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        Text(
            text = "Known limitations: ${entry.limitations}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Step four: the code the online wallet reads, animated when it has several parts. It fills the space above the Done button.
 * Until the parts are ready, it says so.
 */
@Composable
private fun ColumnScope.CodeStep(format: PairFormat?, frames: List<String>?) {
    if (format != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddressIcon(size = 16.dp)
            Spacer(Modifier.width(LenslySpacing.xs))
            Text(
                text = stringResource(format.instructionRes),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.weight(1f))
    if (frames == null) {
        LoadingState(label = "Preparing Code")
    } else {
        AnimatedQr(parts = frames, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.weight(1f))
}

/** One online wallet to pair with: its name, its steps, and an arrow. It dims while pressed and plays the key haptic. */
@Composable
private fun PairOptionCard(format: PairFormat, onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.large
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressFeedback(interaction, enabled = true)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .tapHaptic(haptics)
            .clickable(
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(LenslySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (format.recommended) {
                PillChip(text = stringResource(R.string.pair_format_recommended))
                Spacer(Modifier.height(LenslySpacing.xs))
            }
            Text(
                text = stringResource(format.labelRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(LenslySpacing.xxs))
            Text(
                text = stringResource(format.instructionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(LenslySpacing.sm))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A pairing screen that is still reading its data: the frame with a centred loading state. */
@Composable
internal fun PairLoading(label: String, onBack: () -> Unit) {
    LenslyScreen(
        title = PAIR_TITLE,
        onBack = onBack,
        scrollable = false,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            LoadingState(label = label)
        }
    }
}
