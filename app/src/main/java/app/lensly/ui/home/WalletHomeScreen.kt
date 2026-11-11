package app.lensly.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletReviewRecord
import app.lensly.ui.components.LenslyIcons
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.pressFeedback
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceHigh
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletWarningChip
import kotlinx.coroutines.delay

/** The test tag of the Scan to sign button. */
internal const val HOME_SCAN_TAG = "home-scan"

/** The test tag of the scrolling content under the top bar. */
internal const val HOME_SCROLL_TAG = "home-scroll"

/** The top bar: the wordmark and the lock. */
private val HEADER_HEIGHT = 56.dp

/** The box of the aperture. Its glow reaches past the box, and the scroll clips it at the edge of the content. */
private val HERO_HEIGHT = 256.dp

/** The box of the aperture with no wallet: larger, so the empty state fills the screen as the first wallet would. */
private val EMPTY_HERO_HEIGHT = 440.dp

private val SCAN_HEIGHT = 56.dp

/** The room between two parts of the content. */
private val SECTION_GAP = 20.dp

/** The room before Readiness, which is the group of device checks rather than of the wallet. */
private val READINESS_GAP = 40.dp

/** The room the content keeps clear of the dock: the bar, the dock's bottom margin, and the page gutter. */
private val DOCK_CLEARANCE = DOCK_BAR_HEIGHT + LenslySpacing.xs + WALLET_GUTTER

/**
 * How far above the dock's top edge the content fades to black. The fade starts below the last card of the first screen, so the
 * cards stay whole, and it is solid from the dock's edge down.
 */
private val DOCK_FADE = 16.dp

/** The bottom edge of the pairing confirmation: it sits just above the dock. */
private val NOTICE_BOTTOM = 100.dp

private const val ENTER_MS = 240

/** How long the pairing confirmation stays on screen. */
private const val NOTICE_MS = 2_400L

/**
 * Home: the vault's front page, from the top down. A small wordmark with the lock; the aperture, with a line that counts
 * this session's wallets and accounts; the Scan to sign button; Recent activity; the wallets as a row of cards; and the readiness
 * tiles. The action dock floats at the foot over a fade, and the content keeps clear of it. With no wallet, the sections give way to
 * the empty state with Create and Restore, and the aperture stays above it.
 *
 * [wallets], [recent] and [readiness] are null until each has been read. [nowMs] is the time that the relative times are read
 * against. The callbacks take a wallet's id where the action is about one wallet. [debugNotice], when set, shows a short warning chip
 * beside the wordmark, for example that the keystore is software-only. [notice], when set, shows a short confirmation such as "Wallet
 * paired", and [onNoticeShown] is called once it has appeared, so the caller can forget it.
 */
@Composable
fun WalletHomeScreen(
    wallets: List<WalletMeta>?,
    recent: List<WalletReviewRecord>?,
    readiness: HomeReadiness?,
    nowMs: Long,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onPair: (String) -> Unit,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onScan: () -> Unit,
    onActivity: () -> Unit,
    onWallets: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onStartPairing: () -> Unit,
    onNetworks: () -> Unit,
    onTokens: () -> Unit,
    onCatalog: () -> Unit,
    onLockout: () -> Unit,
    modifier: Modifier = Modifier,
    profiles: Map<String, WalletProfile> = emptyMap(),
    debugNotice: String? = null,
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
) {
    var sheetWallet by remember { mutableStateOf<WalletMeta?>(null) }
    val walletNames = remember(wallets) { wallets.orEmpty().associate { it.id to it.name } }
    val newest = remember(recent) { recent?.sortedByDescending { it.record.atMs }?.take(HOME_ACTIVITY_LIMIT) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // A vault with no wallet shows only the aperture, the line under it and the empty state.
    val empty = wallets != null && wallets.isEmpty()
    val fadeHeight = DOCK_BAR_HEIGHT + LenslySpacing.xs + DOCK_FADE + navBottom
    Box(modifier = modifier.fillMaxSize().background(LenslyBlack)) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            HomeHeader(debugNotice = debugNotice, onLock = onLock)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag(HOME_SCROLL_TAG)
                    .verticalScroll(rememberScrollState()),
            ) {
                ApertureHero(modifier = Modifier.fillMaxWidth().height(if (empty) EMPTY_HERO_HEIGHT else HERO_HEIGHT))
                StatusLine(text = wallets?.let { sealedLine(it.size, it.sumOf { wallet -> wallet.accountCount }) } ?: "Sealed")
                Spacer(Modifier.height(SECTION_GAP))
                if (empty) {
                    EmptyVault(onCreate = onCreate, onRestore = onRestore, modifier = Modifier.padding(horizontal = WALLET_GUTTER))
                } else {
                    ScanToSignButton(onClick = onScan, modifier = Modifier.padding(horizontal = WALLET_GUTTER))
                    if (wallets != null) {
                        Spacer(Modifier.height(SECTION_GAP))
                        RecentActivitySection(records = newest, walletNames = walletNames, nowMs = nowMs, onSeeAll = onActivity)
                        Spacer(Modifier.height(SECTION_GAP))
                        WalletCarouselSection(
                            wallets = wallets,
                            profiles = profiles,
                            onOpen = onOpen,
                            onLongPress = { sheetWallet = it },
                            onAllWallets = onWallets,
                        )
                        if (readiness != null) {
                            // Readiness starts beneath the dock on the first screen, so none of its heading shows above the dock.
                            Spacer(Modifier.height(READINESS_GAP))
                            ReadinessSection(
                                readiness = readiness,
                                onNetworks = onNetworks,
                                onTokens = onTokens,
                                onCatalog = onCatalog,
                                onLockout = onLockout,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(DOCK_CLEARANCE))
            }
        }
        DockFade(
            height = fadeHeight,
            stop = DOCK_FADE / fadeHeight,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        ActionDock(
            onPair = onStartPairing,
            onWallets = onWallets,
            onActivity = onActivity,
            onSettings = onSettings,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = WALLET_GUTTER, end = WALLET_GUTTER, bottom = LenslySpacing.xs),
        )
        HomeNotice(
            notice = notice,
            onShown = onNoticeShown,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = NOTICE_BOTTOM),
        )
    }
    sheetWallet?.let { wallet ->
        WalletContextSheet(
            wallet = wallet,
            onDismiss = { sheetWallet = null },
            onOpen = onOpen,
            onEdit = onEdit,
            onPair = onPair,
        )
    }
}

/**
 * The top bar: the Lensly wordmark in the dot-matrix face, on the left, and the lock as an icon button on the right. Settings is in the
 * dock. A debug build that runs on a software keystore shows its warning as a chip beside the wordmark, so the content keeps its place.
 */
@Composable
private fun HomeHeader(debugNotice: String?, onLock: () -> Unit) {
    val haptics = rememberKeyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(horizontal = WALLET_GUTTER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LENSLY",
            style = MaterialTheme.typography.headlineSmall,
            color = LenslyOnSurface,
        )
        if (debugNotice != null) {
            Spacer(Modifier.width(LenslySpacing.sm))
            WalletWarningChip(text = debugNotice)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onLock, modifier = Modifier.tapHaptic(haptics)) {
            Icon(imageVector = Icons.Filled.Lock, contentDescription = "Lock the vault", tint = LenslyOnSurface)
        }
    }
}

/** The line under the aperture, in the body style: the session's counts, as the caller words them. */
@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = WALLET_GUTTER),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** The large primary button: the accent fill, the viewfinder glyph and the words. It opens the scan of any wallet. */
@Composable
private fun ScanToSignButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberKeyHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SCAN_HEIGHT)
            .testTag(HOME_SCAN_TAG)
            .pressFeedback(interaction, enabled = true)
            .clip(LenslyPillShape)
            .background(LenslyAccent)
            .tapHaptic(haptics)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LenslyIcons.QrScan,
            contentDescription = null,
            tint = LenslyOnSurface,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(LenslySpacing.sm))
        Text(
            text = "Scan to sign".uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = LenslyOnSurface,
        )
    }
}

/**
 * The empty state, for a vault with no wallet: the invitation, then the two ways to start. Create begins a wallet's ceremony, as the
 * start screen's Create does, and Restore opens the restore flow.
 */
@Composable
private fun EmptyVault(onCreate: () -> Unit, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            // The break keeps the two lines even, so "wallet" never stands alone on the second line.
            text = "Create or restore\nyour first wallet",
            style = MaterialTheme.typography.titleLarge,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = "Start with a new wallet of 24 words, or restore one you already have.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
            PillButton(text = "Create", onClick = onCreate, accent = true, modifier = Modifier.weight(1f))
            PillButton(text = "Restore", onClick = onRestore, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(LenslySpacing.md))
        Text(
            text = "Nothing leaves this device.",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The fade above the dock: clear at the top, then black from [stop] of the way down, which is the dock's line. Content dims as it
 * passes under the dock, and the dock itself sits on black.
 */
@Composable
private fun DockFade(height: Dp, stop: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    0f to LenslyBlack.copy(alpha = 0f),
                    stop to LenslyBlack,
                    1f to LenslyBlack,
                ),
            ),
    )
}

/**
 * The confirmation after a pairing: a pill that shows [notice] for a few seconds, then fades. It reports the notice once, through
 * [onShown], as soon as it appears, so a later visit does not show it again.
 */
@Composable
private fun HomeNotice(notice: String?, onShown: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(notice) {
        if (notice != null) {
            text = notice
            visible = true
            onShown()
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            delay(NOTICE_MS)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(ENTER_MS)) + slideInVertically(tween(ENTER_MS)) { it / 2 },
        exit = fadeOut(tween(ENTER_MS)),
    ) {
        Text(
            text = text.uppercase(),
            modifier = Modifier
                .clip(LenslyPillShape)
                .background(LenslySurfaceHigh)
                .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.sm),
            style = MaterialTheme.typography.labelMedium,
            color = LenslyOnSurface,
        )
    }
}
