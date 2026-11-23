package app.lumio.ui.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lensly.ui.theme.LocalReducedMotion
import app.lumio.network.NetworkMode
import app.lumio.ui.home.LumioMark
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirSegmented
import app.lumio.ui.kit.ClearAirWalletEmblem

/** The width of the profile panel, from the proposal. */
private val PANEL_WIDTH = 304.dp

/** The corner radius of the panel's open edge. */
private val PANEL_CORNER = 28.dp

/** The scrim's opacity over the page beneath the panel, in the light theme and in the dark one. */
private const val SCRIM_LIGHT = 0.34f
private const val SCRIM_DARK = 0.58f

/** A row of the panel is at least as tall as a Clear Air row, and the test networks row is 48 dp, as the mock draws it. */
private val ROW_MIN_HEIGHT = 62.dp
private val COMPACT_ROW_MIN_HEIGHT = 48.dp

/** The radio of a wallet row: a 22 dp ring with a 12 dp dot when the wallet is the chosen one. */
private val RADIO_SIZE = 22.dp
private val RADIO_DOT = 12.dp
private val RADIO_STROKE = 1.6.dp

/** The EVM test network that Send starts on, which the sentence marks as the default. */
private const val EVM_DEFAULT = "Monad Testnet"

/** The test networks that have no switch: one each on Bitcoin, Solana and Algorand. */
private val FIXED_TEST_NETWORKS = listOf("Bitcoin Testnet4", "Solana Devnet", "Algorand Testnet")

/**
 * A Lensly wallet as the panel lists it: its [id], its [name], and its [detail] line, such as "30 accounts · paired 21 Nov".
 */
data class PanelWallet(val id: String, val name: String, val detail: String)

/**
 * The sentence under the Mainnet · Testnet control. It names the test network that each chain uses: the EVM test networks that are on, with the default
 * one marked, then the networks that have no switch. [evmNames] are the names of the EVM test networks that are on.
 */
fun testNetworkSentence(evmNames: List<String>): String {
    val names = evmNames.map { if (it == EVM_DEFAULT) "$it (EVM default)" else it } + FIXED_TEST_NETWORKS
    return "Each chain uses its test network: ${names.dropLast(1).joinToString(", ")} and ${names.last()}."
}

/**
 * The profile panel, opened from the wallet emblem (section A4). It slides in from the left edge over a scrim, and the status bar stays above it. It
 * holds Scan to Pay, the Mainnet and Testnet side, the test networks page, the Lensly wallets as radio rows, and Pair Another Wallet. Each choice applies
 * at once and closes the panel. Tapping the scrim closes it too.
 */
@Composable
fun ProfilePanel(
    visible: Boolean,
    mode: NetworkMode,
    testNetworks: Int,
    networkSentence: String,
    wallets: List<PanelWallet>,
    activeWalletId: String?,
    onMode: (NetworkMode) -> Unit,
    onScan: () -> Unit,
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
                    .verticalScroll(rememberScrollState()),
            ) {
                Brand()
                PanelRow(
                    title = "Scan to Pay",
                    description = "Send to a scanned address",
                    leading = { PanelGlyph(SCAN_GLYPH, palette.ink) },
                    onClick = {
                        onDismiss()
                        onScan()
                    },
                )
                ClearAirEyebrow("Networks", modifier = Modifier.padding(start = ClearAirSpacing.gutter, top = 14.dp))
                Column(modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter)) {
                    ClearAirSegmented(
                        options = NetworkMode.entries,
                        selected = mode,
                        label = { it.label() },
                        onSelect = { next ->
                            onMode(next)
                            onDismiss()
                        },
                    )
                    Text(
                        text = networkSentence,
                        style = ClearAirType.body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = palette.ink2,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                PanelRow(
                    title = "Choose Test Networks",
                    titleStyle = ClearAirType.rowTitle.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    trailing = { Text(text = testNetworks.toString(), style = ClearAirType.label, color = palette.ink2) },
                    minHeight = COMPACT_ROW_MIN_HEIGHT,
                    onClick = {
                        onDismiss()
                        onTestNetworks()
                    },
                )
                ClearAirEyebrow("Lensly Wallets", modifier = Modifier.padding(start = ClearAirSpacing.gutter, top = 10.dp))
                wallets.forEach { wallet ->
                    val chosen = wallet.id == activeWalletId
                    PanelRow(
                        title = wallet.name,
                        description = wallet.detail,
                        leading = { ClearAirWalletEmblem(tile = 36.dp) },
                        trailing = { PanelRadio(selected = chosen) },
                        selected = chosen,
                        onClick = {
                            onDismiss()
                            onWallet(wallet.id)
                        },
                    )
                }
                PairAction(
                    onClick = {
                        onDismiss()
                        onPair()
                    },
                )
                Spacer(Modifier.height(ClearAirSpacing.lg))
            }
        }
    }
}

/** The panel's head: the mark, then "Lumio" and "AirVault Companion" beside it. */
@Composable
private fun Brand() {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.padding(start = ClearAirSpacing.gutter, top = 8.dp, end = ClearAirSpacing.gutter, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LumioMark(size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = "Lumio", style = ClearAirType.titleL.copy(fontSize = 19.sp, lineHeight = 24.sp), color = palette.ink)
            Text(text = "AirVault Companion", style = ClearAirType.label, color = palette.ink2)
        }
    }
}

/**
 * A row of the panel, drawn as a Clear Air row is: an optional [leading] mark, a [title] with a [description] under it, and an optional [trailing] value.
 * A row that opens something has a chevron. A row with a [selected] state is a radio choice instead: it has no chevron, and a tap chooses it. The
 * [titleStyle] and [minHeight] fit a row to the mock where the mock differs.
 */
@Composable
private fun PanelRow(
    title: String,
    onClick: () -> Unit,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean? = null,
    titleStyle: TextStyle = ClearAirType.rowTitle,
    minHeight: Dp = ROW_MIN_HEIGHT,
) {
    val palette = LocalClearAirPalette.current
    val tap = if (selected == null) {
        Modifier.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
    } else {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .then(tap)
            .padding(horizontal = ClearAirSpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(ClearAirSpacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = titleStyle, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                Text(text = description, style = ClearAirType.body, color = palette.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            trailing()
        }
        if (selected == null) {
            Spacer(Modifier.width(ClearAirSpacing.xs))
            ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3)
        }
    }
}

/** The radio of a wallet row: an ink ring, with a dot in it when the wallet is the chosen one. */
@Composable
private fun PanelRadio(selected: Boolean) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = Modifier.size(RADIO_SIZE).border(RADIO_STROKE, if (selected) palette.ink else palette.ink3, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(modifier = Modifier.size(RADIO_DOT).clip(CircleShape).background(palette.ink))
        }
    }
}

/** Pair Another Wallet: an underlined text action with a plus in front of it, at least 48 dp square. */
@Composable
private fun PairAction(onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .padding(horizontal = ClearAirSpacing.gutter)
            .defaultMinSize(minHeight = ClearAirSpacing.touchTarget)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelGlyph(PLUS_GLYPH, palette.ink)
        Spacer(Modifier.width(ClearAirSpacing.xs))
        Text(
            text = "Pair Another Wallet",
            style = ClearAirType.rowTitle.copy(textDecoration = TextDecoration.Underline),
            color = palette.ink,
        )
    }
}

/** The panel's slide: spring ζ1.0, k400, a critically damped move that settles in about 300 ms. */
private val panelOffset = spring<IntOffset>(dampingRatio = 1f, stiffness = 400f)
