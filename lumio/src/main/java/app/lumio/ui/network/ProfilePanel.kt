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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import app.lumio.ui.kit.Designed
import app.lumio.ui.kit.RowStack
import app.lumio.ui.kit.designedHeight
import app.lumio.ui.kit.CssCentre
import app.lumio.ui.kit.WalletEmblemTone
import app.lumio.ui.kit.cssLine
import app.lumio.ui.kit.inLineBox

/** The width of the profile panel, from the proposal. */
private val PANEL_WIDTH = 304.dp

/** The corner radius of the panel's open edge. The mock sets the panel past the top of the screen, so only its bottom corner shows. */
private val PANEL_CORNER = 28.dp

/** A row of the panel is at least as tall as a Clear Air row, and the test networks row is 48 dp, as the mock draws it. */
private val ROW_MIN_HEIGHT = 62.dp
private val COMPACT_ROW_MIN_HEIGHT = 48.dp
private val PAIR_ROW_HEIGHT = 45.dp

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

/**
 * The mock's text sizes for the panel, each in the line box of the mock's browser: the caption under the Lumio name, the sentence under the control and a
 * row's description, the title of Lumio, a row's title and the label of a text action.
 */
private val CAPTION: TextStyle = ClearAirType.body.copy(fontSize = 13.sp, lineHeight = 18.sp).inLineBox()
private val DESCRIPTION: TextStyle = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp).inLineBox()
private val BRAND_TITLE: TextStyle = ClearAirType.titleL.copy(fontSize = 19.sp, lineHeight = 25.sp).inLineBox()
private val ROW_TITLE: TextStyle = ClearAirType.rowTitle.inLineBox()
private val COMPACT_ROW_TITLE: TextStyle = ClearAirType.rowTitle.copy(fontSize = 14.sp).inLineBox()
private val COUNT: TextStyle = ClearAirType.address.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.02).em).inLineBox()
private val PAIR_TITLE: TextStyle = ClearAirType.rowTitle.copy(lineHeight = 21.sp).inLineBox()

/** The height of an eyebrow's line, and the sentence's space under the control and after it, as the mock sets them. */
private val EYEBROW_LINE = 14.dp
private val SENTENCE_TOP = 10.dp
private val SENTENCE_BOTTOM = 12.dp

/**
 * The heights the mock gives the blocks above a row: the head (10 dp, the 43 dp of its two lines and 8 dp), each eyebrow (its top space, the line and 4 dp) and the
 * control's track. The panel is a [RowStack], so each of these is placed by its designed height, as the rows are (#1742).
 */
private val BRAND_HEIGHT = 61.dp
private val NETWORKS_EYEBROW_HEIGHT = 14.dp + EYEBROW_LINE + ClearAirSpacing.xxs
private val WALLETS_EYEBROW_HEIGHT = 10.dp + EYEBROW_LINE + ClearAirSpacing.xxs
private val SEGMENTED_HEIGHT = 43.dp

/** The EVM test network that Send starts on, which the sentence marks as the default. */
private const val EVM_DEFAULT = "Monad Testnet"

/** The test networks that have no switch: one each on Bitcoin, Solana and Algorand. */
private val FIXED_TEST_NETWORKS = listOf("Bitcoin Testnet4", "Solana Devnet", "Algorand Testnet")

/**
 * A Lensly wallet as the panel lists it: its [id], its [name], and its [detail] line, such as "30 accounts · paired 21 Nov".
 */
data class PanelWallet(val id: String, val name: String, val detail: String, val tone: WalletEmblemTone = WalletEmblemTone.BLUE)

/**
 * The sentence under the Mainnet · Testnet control, shown on the Testnet side. It names the test network that each chain uses: the EVM test networks that are on, with the default
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
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(ClearAirMotion.SCRIM_MS)),
            exit = fadeOut(tween(ClearAirMotion.SCRIM_MS)),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(palette.scrim).clickable(onClick = onDismiss))
        }
        AnimatedVisibility(
            visible = visible,
            enter = if (reduced) fadeIn(tween(ClearAirMotion.CROSS_FADE_MS)) else slideInHorizontally(panelOffset) { -it },
            exit = if (reduced) fadeOut(tween(ClearAirMotion.CROSS_FADE_MS)) else slideOutHorizontally(panelOffset) { -it },
        ) {
            RowStack(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(PANEL_WIDTH)
                    .clip(RoundedCornerShape(bottomEnd = PANEL_CORNER))
                    .background(palette.sheet)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Designed(BRAND_HEIGHT) { Brand() }
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
                Designed(NETWORKS_EYEBROW_HEIGHT) { PanelEyebrow("Networks", top = 14.dp) }
                Designed(ClearAirSpacing.xxs + SEGMENTED_HEIGHT) {
                    ClearAirSegmented(
                        options = NetworkMode.entries,
                        selected = mode,
                        label = { it.label() },
                        onSelect = { next ->
                            onMode(next)
                            onDismiss()
                        },
                        modifier = Modifier.padding(start = ClearAirSpacing.gutter, top = ClearAirSpacing.xxs, end = ClearAirSpacing.gutter),
                    )
                }
                // The mock draws the sentence on the Testnet side only.
                if (mode == NetworkMode.TESTNET) {
                    Sentence(networkSentence)
                }
                PanelRow(
                    title = "Choose Test Networks",
                    titleStyle = COMPACT_ROW_TITLE,
                    trailing = { Text(text = testNetworks.toString(), style = COUNT, color = palette.ink2) },
                    minHeight = COMPACT_ROW_MIN_HEIGHT,
                    onClick = {
                        onDismiss()
                        onTestNetworks()
                    },
                )
                Designed(WALLETS_EYEBROW_HEIGHT) { PanelEyebrow("Lensly Wallets", top = 10.dp) }
                wallets.forEachIndexed { index, wallet ->
                    val chosen = wallet.id == activeWalletId
                    PanelRow(
                        title = wallet.name,
                        description = wallet.detail,
                        leading = { ClearAirWalletEmblem(tile = 36.dp, tone = wallet.tone) },
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

/**
 * The sentence under the control, [SENTENCE_TOP] under it and [SENTENCE_BOTTOM] above the next row. Each line is rounded up to a whole pixel, which at a fractional density
 * is more than the mock's line, so the block's designed height is its lines of the mock's line height, and its count of lines is the one the text itself wraps to. The
 * space above the text is rounded down, as the half pixel the lines have over their design is not added to it as well.
 */
@Composable
private fun Sentence(text: String) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val lines = measurer.measure(text, CAPTION, constraints = Constraints(maxWidth = with(density) { (PANEL_WIDTH - ClearAirSpacing.gutter * 2).roundToPx() })).lineCount
    Designed(SENTENCE_TOP + with(density) { CAPTION.lineHeight.toDp() } * lines + SENTENCE_BOTTOM) {
        Text(
            text = text,
            style = CAPTION,
            color = LocalClearAirPalette.current.ink2,
            modifier = Modifier
                .offset { IntOffset(0, SENTENCE_TOP.toPx().toInt()) }
                .padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter),
        )
    }
}

/** An eyebrow of the panel, [top] under what is above it, in a line box of [EYEBROW_LINE] and [ClearAirSpacing.xxs] above what follows. */
@Composable
private fun PanelEyebrow(text: String, top: Dp) {
    ClearAirEyebrow(text, modifier = Modifier.padding(start = ClearAirSpacing.gutter, top = top, bottom = ClearAirSpacing.xxs).cssLine(EYEBROW_LINE), top = 0.dp, bottom = 0.dp)
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
            Text(text = "Lumio", style = BRAND_TITLE, color = palette.ink)
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
    titleStyle: TextStyle = ROW_TITLE,
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
            .designedHeight(minHeight)
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
        Column(modifier = Modifier.weight(1f).align(CssCentre)) {
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
 * Pair Another Wallet: a plus and the words in ink, as the mock's text action sets them. It is not underlined, and it is drawn 45 dp tall as the mock has it, with
 * its press measured at the 48 dp touch target ([pairTouchTarget]).
 */
@Composable
private fun PairAction(onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .padding(horizontal = ClearAirSpacing.gutter)
            .pairTouchTarget(Modifier.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirIcon(glyph = ClearAirGlyph.PLUS, color = palette.ink, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(text = "Pair Another Wallet", style = PAIR_TITLE, color = palette.ink)
    }
}

/**
 * Lays the pair row out [PAIR_ROW_HEIGHT] tall and gives its [press] the 48 dp touch target centred on it, so a tap 1.5 dp past either edge still counts. The
 * press box reaches over the rows beside it, and the content inside keeps the exact pixels of a [PAIR_ROW_HEIGHT] row: the outer layout moves the box up by
 * the same inset that the inner one moves the content down.
 */
private fun Modifier.pairTouchTarget(press: Modifier): Modifier = this
    .layout { measurable, constraints ->
        val height = PAIR_ROW_HEIGHT.roundToPx()
        val target = ClearAirSpacing.touchTarget.roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = target, maxHeight = target))
        layout(placeable.width, height) { placeable.place(0, (height - target) / 2) }
    }
    .then(press)
    .layout { measurable, constraints ->
        val height = PAIR_ROW_HEIGHT.roundToPx()
        val target = ClearAirSpacing.touchTarget.roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, target) { placeable.place(0, (target - height) / 2) }
    }

/** The panel's slide: spring ζ1.0, k400, a critically damped move that settles in about 300 ms. */
private val panelOffset = spring<IntOffset>(dampingRatio = 1f, stiffness = 400f)
