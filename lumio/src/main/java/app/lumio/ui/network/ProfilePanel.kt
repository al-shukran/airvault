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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
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

/** The divider between two rows starts after a row's disc, at the gutter, the disc and its gap: 20 + 36 + 12 dp. */
private val DIVIDER_START = 68.dp

/** The thickness of a row's divider. */
private val DIVIDER_HAIRLINE = 1.dp

/** The leading disc of a row and the glyph in it, and the chevron that a row which opens something ends with. */
private val DISC_GLYPH = 21.dp
private val CHEVRON_SIZE = 16.dp

/** The radio of a wallet row: a 22 dp ring, 2 dp thick, with a 10 dp dot when the wallet is the chosen one. */
private val RADIO_SIZE = 22.dp
private val RADIO_STROKE = 2.dp
private val RADIO_DOT = 10.dp

/** The mock's text sizes for the panel: the caption under the Lumio name, the sentence under the control and a row's description. */
private val CAPTION: TextStyle = ClearAirType.body.copy(fontSize = 13.sp, lineHeight = 18.sp)
private val DESCRIPTION: TextStyle = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)

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
    /** Set while Scan to Pay cannot open a send: the row says what to turn on, and a tap does nothing. */
    scanNote: String? = null,
    /** False until the side's settings are read: the row does nothing on a tap until then, so a send the settings would refuse never opens. */
    scanReady: Boolean = true,
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
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Brand()
                PanelRow(
                    title = "Scan to Pay",
                    description = scanNote ?: "Send to a scanned address",
                    leading = { GlyphDisc(ClearAirGlyph.SCAN, glyphSize = DISC_GLYPH) },
                    onClick = {
                        if (scanReady && scanNote == null) {
                            onDismiss()
                            onScan()
                        }
                    },
                )
                ClearAirEyebrow("Networks", modifier = Modifier.padding(start = ClearAirSpacing.gutter), top = 14.dp, bottom = ClearAirSpacing.xxs)
                Column(modifier = Modifier.padding(start = ClearAirSpacing.gutter, top = ClearAirSpacing.xxs, end = ClearAirSpacing.gutter)) {
                    ClearAirSegmented(
                        options = NetworkMode.entries,
                        selected = mode,
                        label = { it.label() },
                        onSelect = { next ->
                            onMode(next)
                            onDismiss()
                        },
                    )
                    Text(text = networkSentence, style = CAPTION, color = palette.ink2, modifier = Modifier.padding(top = 10.dp))
                }
                PanelRow(
                    title = "Choose Test Networks",
                    titleStyle = ClearAirType.rowTitle.copy(fontSize = 14.sp),
                    trailing = { Text(text = testNetworks.toString(), style = ClearAirType.address, color = palette.ink2) },
                    minHeight = COMPACT_ROW_MIN_HEIGHT,
                    onClick = {
                        onDismiss()
                        onTestNetworks()
                    },
                )
                ClearAirEyebrow("Lensly Wallets", modifier = Modifier.padding(start = ClearAirSpacing.gutter), top = 10.dp, bottom = ClearAirSpacing.xxs)
                wallets.forEachIndexed { index, wallet ->
                    val chosen = wallet.id == activeWalletId
                    PanelRow(
                        title = wallet.name,
                        description = wallet.detail,
                        leading = { ClearAirWalletEmblem(tile = 36.dp) },
                        trailing = { PanelRadio(selected = chosen) },
                        selected = chosen,
                        divider = index > 0,
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
            }
        }
    }
}

/** The panel's head: the mark, then "Lumio" and "AirVault Companion" beside it, 10 dp under the status bar as the mock sets them. */
@Composable
private fun Brand() {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.padding(start = ClearAirSpacing.gutter, top = 10.dp, end = ClearAirSpacing.gutter, bottom = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LumioMark(size = 34.dp)
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column {
            Text(text = "Lumio", style = ClearAirType.titleL.copy(fontSize = 19.sp, lineHeight = 25.sp), color = palette.ink)
            Text(text = "AirVault Companion", style = CAPTION, color = palette.ink2)
        }
    }
}

/**
 * A row of the panel, drawn as the mock draws a row: an optional [leading] disc, a [title] with a [description] under it, and an optional [trailing]
 * value. A row that opens something ends with a chevron. A row with a [selected] state is a radio choice instead: it has no chevron, and a tap chooses
 * it. A [divider] row has a hairline above it, from the disc's edge to the gutter. The [titleStyle] and [minHeight] fit a row to the mock where the mock
 * differs.
 */
@Composable
private fun PanelRow(
    title: String,
    onClick: () -> Unit,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean? = null,
    divider: Boolean = false,
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
            .then(if (divider) Modifier.divider(palette.hairline) else Modifier)
            .then(tap)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(ClearAirSpacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = titleStyle, color = palette.ink)
            if (description != null) {
                Text(text = description, style = DESCRIPTION, color = palette.ink2)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            trailing()
        }
        if (selected == null) {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3, size = CHEVRON_SIZE)
        }
    }
}

/** The hairline above a row, from [DIVIDER_START] to the gutter on the right, as the mock draws a row's divider. */
private fun Modifier.divider(color: Color): Modifier = drawBehind {
    val thickness = DIVIDER_HAIRLINE.toPx()
    val end = size.width - ClearAirSpacing.gutter.toPx()
    drawLine(color = color, start = Offset(DIVIDER_START.toPx(), thickness / 2), end = Offset(end, thickness / 2), strokeWidth = thickness)
}

/**
 * A row's leading glyph in a raised disc of 36 dp, as the mock draws a row's logo: the [glyph] at [glyphSize] in ink, centred in the disc.
 */
@Composable
internal fun GlyphDisc(glyph: ClearAirGlyph, glyphSize: Dp = DISC_GLYPH) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(palette.raised),
        contentAlignment = Alignment.Center,
    ) {
        ClearAirIcon(glyph = glyph, color = palette.ink, size = glyphSize)
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

/**
 * Pair Another Wallet: a plus and the words in ink, as the mock's text action sets them. It is not underlined, and it is at least 48 dp tall.
 */
@Composable
private fun PairAction(onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .padding(horizontal = ClearAirSpacing.gutter)
            .defaultMinSize(minHeight = ClearAirSpacing.touchTarget)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIcon(glyph = ClearAirGlyph.PLUS, color = palette.ink, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(text = "Pair Another Wallet", style = ClearAirType.rowTitle, color = palette.ink)
    }
}

/** The panel's slide: spring ζ1.0, k400, a critically damped move that settles in about 300 ms. */
private val panelOffset = spring<IntOffset>(dampingRatio = 1f, stiffness = 400f)
