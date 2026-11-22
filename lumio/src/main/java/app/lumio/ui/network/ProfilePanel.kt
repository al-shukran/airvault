package app.lumio.ui.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion
import app.lumio.network.NetworkMode
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSegmented
import app.lumio.ui.kit.ClearAirTextAction

/** The width of the profile panel, from the proposal. */
private val PANEL_WIDTH = 304.dp

/** The corner radius of the panel's open edge. */
private val PANEL_CORNER = 28.dp

/** The scrim's opacity over the page beneath the panel, in the light theme and in the dark one. */
private const val SCRIM_LIGHT = 0.34f
private const val SCRIM_DARK = 0.58f

/**
 * A Lensly wallet as the panel lists it: its [id], its [name], and its [detail] line, such as "30 accounts · paired 21 Nov".
 */
data class PanelWallet(val id: String, val name: String, val detail: String)

/**
 * The profile panel, opened from the wallet emblem (section A4). It slides in from the left edge over a scrim, and the status bar stays above it. It
 * holds the Mainnet and Testnet side, which applies at once and closes the panel, the test networks page, the Lensly wallets, and Pair Another
 * Wallet. Tapping the scrim closes it.
 */
@Composable
fun ProfilePanel(
    visible: Boolean,
    mode: NetworkMode,
    testNetworks: Int,
    wallets: List<PanelWallet>,
    onMode: (NetworkMode) -> Unit,
    onTestNetworks: () -> Unit,
    onWallet: (String) -> Unit,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    val reduced = LocalReducedMotion.current
    val scrim = Color.Black.copy(alpha = if (isSystemInDarkTheme()) SCRIM_DARK else SCRIM_LIGHT)
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(ClearAirMotion.SCRIM_MS)),
            exit = fadeOut(tween(ClearAirMotion.SCRIM_MS)),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(scrim).clickable(onClick = onDismiss))
        }
        AnimatedVisibility(
            visible = visible,
            enter = if (reduced) fadeIn(tween(ClearAirMotion.CROSS_FADE_MS)) else slideInHorizontally(panelOffset) { -it },
            exit = if (reduced) fadeOut(tween(ClearAirMotion.CROSS_FADE_MS)) else slideOutHorizontally(panelOffset) { -it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(PANEL_WIDTH)
                    .clip(RoundedCornerShape(topEnd = PANEL_CORNER, bottomEnd = PANEL_CORNER))
                    .background(palette.sheet)
                    .statusBarsPadding()
                    .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = "Lumio AirVault Companion", style = ClearAirType.titleL, color = palette.ink)
                Spacer(Modifier.height(ClearAirSpacing.lg))
                ClearAirEyebrow("Networks")
                ClearAirSegmented(
                    options = NetworkMode.entries,
                    selected = mode,
                    label = { it.label() },
                    onSelect = { next ->
                        onMode(next)
                        onDismiss()
                    },
                )
                Spacer(Modifier.height(ClearAirSpacing.sm))
                Text(
                    text = "Each chain uses its test network: Monad Testnet (EVM default), Bitcoin Testnet4, Solana Devnet and Algorand Testnet.",
                    style = ClearAirType.body,
                    color = palette.ink2,
                )
                ClearAirRow(
                    title = "Choose Test Networks",
                    trailing = { Text(text = testNetworks.toString(), style = ClearAirType.rowValue, color = palette.ink2) },
                    onClick = onTestNetworks,
                )
                Spacer(Modifier.height(ClearAirSpacing.lg))
                ClearAirEyebrow("Lensly Wallets")
                wallets.forEach { wallet ->
                    ClearAirRow(title = wallet.name, description = wallet.detail, onClick = { onWallet(wallet.id) })
                }
                ClearAirTextAction(text = "Pair Another Wallet", onClick = onPair)
            }
        }
    }
}

/** The panel's slide: spring ζ1.0, k400, a critically damped move that settles in about 300 ms. */
private val panelOffset = spring<IntOffset>(dampingRatio = 1f, stiffness = 400f)
