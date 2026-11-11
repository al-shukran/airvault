package app.lensly.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.lensly.app.ProfileColour
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.components.WalletArt
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyAccent
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceHigh
import app.lensly.ui.wallet.WALLET_GUTTER
import app.lensly.ui.wallet.WalletEyebrow
import kotlinx.coroutines.delay

/** How many wallet cards Home shows. More than this, the section links to the full Wallets list. */
private const val HOME_CARD_LIMIT = 3

/** Each card after the first arrives this much later, so the list fills in one card after another. */
private const val STAGGER_STEP_MS = 70L
private const val ENTER_MS = 240

/** How long the pairing confirmation stays on screen. */
private const val NOTICE_MS = 2_400L

/** Room under the last card, so the dock never covers it. */
private val DOCK_CLEARANCE = 120.dp

/** The bottom edge of the pairing confirmation: it sits just above the dock. */
private val NOTICE_BOTTOM = 100.dp

/**
 * Home: the vault's front page. A header with the Lensly wordmark, Settings and Lock; a status line; the WALLETS section with
 * up to three cards, then the add tile; and the action dock at the foot. [wallets] is null until the list has been read. The
 * callbacks take a wallet's id where the action is about one wallet. [debugNotice], when set, shows a warning, for example that
 * the keystore is software-only. [notice], when set, shows a short confirmation such as "Wallet paired", and [onNoticeShown] is
 * called once it has appeared, so the caller can forget it.
 */
@Composable
fun WalletHomeScreen(
    wallets: List<WalletMeta>?,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onPair: (String) -> Unit,
    onAdd: () -> Unit,
    onWallets: () -> Unit,
    onSettings: () -> Unit,
    onLock: () -> Unit,
    onStartPairing: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    onActivity: () -> Unit = {},
    profiles: Map<String, WalletProfile> = emptyMap(),
    debugNotice: String? = null,
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
) {
    var sheetWallet by remember { mutableStateOf<WalletMeta?>(null) }
    Box(modifier = modifier.fillMaxSize().background(LenslyBlack)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = WALLET_GUTTER),
        ) {
            HomeHeader(onSettings = onSettings, onLock = onLock)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                WalletEyebrow(statusLine(wallets?.size))
                if (debugNotice != null) {
                    Spacer(Modifier.height(LenslySpacing.sm))
                    InlineBanner(message = debugNotice, tone = BannerTone.Warning)
                }
                if (wallets != null) {
                    Spacer(Modifier.height(LenslySpacing.lg))
                    WalletsSection(
                        wallets = wallets,
                        profiles = profiles,
                        onOpen = onOpen,
                        onAdd = onAdd,
                        onWallets = onWallets,
                        onLongPress = { sheetWallet = it },
                    )
                }
                Spacer(Modifier.height(DOCK_CLEARANCE))
            }
        }
        ActionDock(
            onPair = onStartPairing,
            onScan = onScan,
            onWallets = onWallets,
            onActivity = onActivity,
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

/** The header row: the Lensly wordmark in the dot-matrix face, then Settings and Lock as icon buttons. */
@Composable
private fun HomeHeader(onSettings: () -> Unit, onLock: () -> Unit) {
    val haptics = rememberKeyHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LENSLY",
            style = MaterialTheme.typography.headlineMedium,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSettings, modifier = Modifier.tapHaptic(haptics)) {
            Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings", tint = LenslyOnSurface)
        }
        IconButton(onClick = onLock, modifier = Modifier.tapHaptic(haptics)) {
            Icon(imageVector = Icons.Filled.Lock, contentDescription = "Lock the vault", tint = LenslyOnSurface)
        }
    }
}

/** The status line under the header, such as "Offline vault · 2 wallets". */
private fun statusLine(count: Int?): String = when (count) {
    null -> "Offline vault"
    0 -> "Offline vault · no wallets"
    1 -> "Offline vault · 1 wallet"
    else -> "Offline vault · $count wallets"
}

/**
 * The WALLETS section: its heading, a "See all" link once there are more wallets than fit, the first [HOME_CARD_LIMIT] cards,
 * and the add tile. With no wallets it shows the empty state above the add tile.
 */
@Composable
private fun WalletsSection(
    wallets: List<WalletMeta>,
    profiles: Map<String, WalletProfile>,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onWallets: () -> Unit,
    onLongPress: (WalletMeta) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "WALLETS",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            color = LenslyOnSurface,
        )
        Spacer(Modifier.weight(1f))
        if (wallets.size > HOME_CARD_LIMIT) {
            SeeAllLink(count = wallets.size, onClick = onWallets)
        }
    }
    Spacer(Modifier.height(LenslySpacing.md))
    if (wallets.isEmpty()) {
        EmptyVault()
        Spacer(Modifier.height(LenslySpacing.lg))
    }
    Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm)) {
        wallets.take(HOME_CARD_LIMIT).forEachIndexed { index, wallet ->
            StaggeredIn(index = index) {
                WalletCard(
                    wallet = wallet,
                    profile = profiles[wallet.id] ?: WalletProfile(),
                    position = index,
                    onClick = { onOpen(wallet.id) },
                    onLongPress = { onLongPress(wallet) },
                )
            }
        }
        StaggeredIn(index = minOf(wallets.size, HOME_CARD_LIMIT)) {
            AddWalletTile(onClick = onAdd)
        }
    }
}

/** The text link that opens the Wallets list, which shows every wallet. */
@Composable
private fun SeeAllLink(count: Int, onClick: () -> Unit) {
    val haptics = rememberKeyHaptics()
    Text(
        text = "See all $count",
        modifier = Modifier
            .tapHaptic(haptics)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LenslySpacing.xxs, vertical = LenslySpacing.xs),
        style = MaterialTheme.typography.labelLarge,
        color = LenslyOnSurface,
        textDecoration = TextDecoration.Underline,
    )
}

/** Fades and lifts [content] in after [index] steps, so a list arrives one item after another. */
@Composable
private fun StaggeredIn(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * STAGGER_STEP_MS)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(ENTER_MS)) + slideInVertically(tween(ENTER_MS)) { it / 6 },
    ) {
        content()
    }
}

/**
 * The empty state: a lens drawn in the wallet art style, then the invitation. The add tile below it is the way to start.
 */
@Composable
private fun EmptyVault() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VaultLens(modifier = Modifier.size(136.dp))
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "Create or restore your first wallet",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = LenslyOnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LenslySpacing.xs))
        Text(
            text = "Start with a new wallet of 24 words, or restore one you already have.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A circle of wallet art crossed by rings, with the accent at its centre. Drawn, so it needs no image. */
@Composable
private fun VaultLens(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(LenslySurfaceHigh),
    ) {
        WalletArt(seed = "lensly-vault", colour = ProfileColour.RED, modifier = Modifier.matchParentSize())
        Canvas(modifier = Modifier.matchParentSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            for (ring in 1..4) {
                drawCircle(
                    color = LenslyOnSurface.copy(alpha = 0.2f),
                    radius = radius * ring / 4f,
                    center = centre,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(color = LenslyAccent, radius = 6.dp.toPx(), center = centre)
        }
    }
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
