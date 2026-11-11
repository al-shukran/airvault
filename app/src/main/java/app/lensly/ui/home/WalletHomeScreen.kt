package app.lensly.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import app.lensly.app.WalletMeta
import app.lensly.app.WalletProfile
import app.lensly.app.WalletReviewRecord
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.InlineBanner
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySurfaceHigh
import app.lensly.ui.wallet.WALLET_GUTTER
import kotlinx.coroutines.delay

/** How long the pairing confirmation stays on screen. */
private const val NOTICE_MS = 2_400L
private const val ENTER_MS = 240

/** Room under the last section, so the dock never covers it. */
private val DOCK_CLEARANCE = 120.dp

/** The bottom edge of the pairing confirmation: it sits just above the dock. */
private val NOTICE_BOTTOM = 100.dp

/**
 * Home: the vault's front page, and deliberately more than the Wallets list. The lens hero carries the wordmark and one status
 * line; the large Scan to sign action sits under it. Then the three newest Review Records of every wallet, a carousel of every
 * wallet, and the readiness chips. The action dock keeps Pair, Activity and Wallets. A wallet is added only from the empty
 * state, which appears when there are no wallets at all.
 *
 * [wallets] is null until the list has been read. [records] is null until the records have been read; an empty list means the
 * vault has none. [readiness] is the catalog and lockout summary for the chips. [debugNotice], when set, shows a warning, for
 * example that the keystore is software-only. [notice], when set, shows a short confirmation such as "Wallet paired", and
 * [onNoticeShown] is called once it has appeared, so the caller can forget it.
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
    onRestore: () -> Unit = {},
    records: List<WalletReviewRecord>? = null,
    readiness: HomeReadiness = HomeReadiness(),
    onNetworks: () -> Unit = {},
    onTokens: () -> Unit = {},
    onCatalog: () -> Unit = {},
    onLockout: () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
) {
    var sheetWallet by remember { mutableStateOf<WalletMeta?>(null) }
    Box(modifier = modifier.fillMaxSize().background(LenslyBlack)) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            HomeTopBar(onSettings = onSettings, onLock = onLock)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                HomeHero(
                    status = statusLine(wallets),
                )
                val loadedWallets = wallets.orEmpty()
                if (loadedWallets.isNotEmpty()) {
                    Spacer(Modifier.height(LenslySpacing.md))
                    Gutter { ScanToSignButton(onClick = onScan) }
                }
                if (debugNotice != null) {
                    Spacer(Modifier.height(LenslySpacing.sm))
                    Gutter { InlineBanner(message = debugNotice, tone = BannerTone.Warning) }
                }
                when {
                    wallets == null -> Unit
                    wallets.isEmpty() -> {
                        Spacer(Modifier.height(LenslySpacing.lg))
                        Gutter { HomeEmptyState(onCreate = onAdd, onRestore = onRestore) }
                    }
                    else -> {
                        Spacer(Modifier.height(LenslySpacing.lg))
                        Gutter {
                            RecentActivitySection(
                                records = records.orEmpty(),
                                wallets = wallets,
                                nowMs = nowMs,
                                onSeeAll = onActivity,
                            )
                        }
                        Spacer(Modifier.height(LenslySpacing.lg))
                        WalletPeekSection(
                            wallets = wallets,
                            profiles = profiles,
                            onOpen = onOpen,
                            onWallets = onWallets,
                            onLongPress = { sheetWallet = it },
                        )
                        Spacer(Modifier.height(LenslySpacing.lg))
                        Gutter {
                            ReadinessStrip(
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
        ActionDock(
            onPair = onStartPairing,
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

/**
 * The screen gutter around one block of Home. The wallet carousel sits outside it, so its cards bleed to the screen edges.
 */
@Composable
private fun Gutter(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = WALLET_GUTTER),
        content = content,
    )
}

/** The one status line under the wordmark: "Sealed · 2 wallets · 3 accounts". */
private fun statusLine(wallets: List<WalletMeta>?): String {
    val list = wallets ?: return "Sealed"
    val accounts = list.sumOf { it.accountCount }
    val walletWord = if (list.size == 1) "wallet" else "wallets"
    val accountWord = if (accounts == 1) "account" else "accounts"
    return "Sealed · ${list.size} $walletWord · $accounts $accountWord"
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
