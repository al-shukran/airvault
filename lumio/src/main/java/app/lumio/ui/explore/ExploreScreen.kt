package app.lumio.ui.explore

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lensly.app.familyLabel
import app.lensly.ui.components.lumioPress
import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.NetworkKind
import app.lensly.core.registry.Registry
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.net.Networks
import app.lumio.network.NetworkMode
import app.lumio.ui.home.AssetDisc
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirGlyphDisc
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ROW_DESCRIPTION
import app.lumio.ui.kit.Gap
import app.lumio.ui.kit.RowStack
import app.lumio.ui.kit.designedHeight
import app.lumio.ui.kit.inLineBox
import app.lumio.ui.settings.ProviderRow
import app.lumio.ui.settings.ProviderStatus
import app.lumio.ui.settings.providerName
import app.lumio.ui.settings.providerCoverage
import app.lumio.ui.settings.providerStatusName
import app.lumio.ui.settings.providerStatusNote
import app.lumio.wallets.WalletAccount
import app.lumio.wallets.WalletAccounts

/**
 * A row of the chains group: a network's [title], its [description], such as "Layer 1 · 4 of your accounts" or "L2 on Ethereum · ID 8453", and its
 * logo [icon].
 */
data class ChainRow(val title: String, val description: String, val icon: AssetIconSpec)

/**
 * The rows of the chains group on [mode]'s side, in family order. Each Ethereum network that is featured or holds one of [accounts] has a row, in
 * chain-ID order. Bitcoin, Solana and Algorand have a row for each network that holds accounts, as they are Layer 1 chains the registry does not list.
 */
internal fun chainRows(registry: Registry, mode: NetworkMode, accounts: List<WalletAccount>): List<ChainRow> {
    val onChain = accounts.groupingBy { it.id.chainId }.eachCount()
    return WalletAccounts.FAMILIES.flatMap { family ->
        if (family == Networks.EIP155) {
            registry.networks()
                .filter { mode.holds(it.testnet) && (it.featured || Networks.evm(it.chainId) in onChain) }
                .map { network -> evmRow(registry, network, onChain[Networks.evm(network.chainId)] ?: 0) }
        } else {
            onChain.filterKeys { it.namespace == family }
                .map { (_, count) -> ChainRow(familyLabel(family), "Layer 1 · $count of your accounts", familyIcon(family)) }
        }
    }
}

/** The row of [network], which reads its count of [accounts], or its chain ID when no account sits on it. */
private fun evmRow(registry: Registry, network: NetworkEntry, accounts: Int): ChainRow {
    val kind = kindOf(registry, network)
    val line = if (accounts > 0) "$kind · $accounts of your accounts" else "$kind · ID ${network.chainId}"
    return ChainRow(network.name, line, AssetIconSpec.Chain(network))
}

/** The logo of a chain family that is not Ethereum: its one Layer 1 chain. */
private fun familyIcon(family: String): AssetIconSpec = when (family) {
    Networks.BIP122 -> AssetIconSpec.Bitcoin
    Networks.SOLANA -> AssetIconSpec.Solana
    else -> AssetIconSpec.Algorand
}

/** "Layer 1", or "L2 on" the name of the parent chain, such as "L2 on Ethereum". */
private fun kindOf(registry: Registry, network: NetworkEntry): String = when (network.kind) {
    NetworkKind.LAYER_1 -> "Layer 1"
    NetworkKind.LAYER_2 -> "L2 on ${checkNotNull(registry.network(checkNotNull(network.parentChainId))).name}"
}

/**
 * The Explore landing (B3): the chains, the data providers, then Learn AirVault. The chains group names each network the owner's accounts sit on, and each
 * featured network, with its kind and its count of accounts or its chain ID. Its action reads "All" and the count of [networks] on the side. The providers
 * group reads "All" and [providerCount], the number of providers the Data providers page lists. Each provider row opens its Provider page, and each
 * article of Learn AirVault opens its page ([onArticle]).
 */
@Composable
fun ExploreScreen(
    chains: List<ChainRow>,
    networks: Int,
    providers: List<ProviderRow>,
    providerCount: Int,
    onChains: () -> Unit,
    onProviders: () -> Unit,
    onProvider: (String) -> Unit,
    onArticle: (String) -> Unit,
) {
    RowStack(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ExploreGroup(title = "Chains and L2s", top = FIRST_GROUP_TOP, action = "All $networks", onAction = onChains) {
            chains.forEachIndexed { index, chain ->
                ClearAirRow(
                    title = chain.title,
                    description = chain.description,
                    leading = { AssetDisc(chain.icon) },
                    onClick = onChains,
                    divider = index > 0,
                    lineBox = true,
                )
            }
        }
        ExploreGroup(title = "Data Providers", top = GROUP_TOP, action = "All $providerCount", onAction = onProviders) {
            providers.forEachIndexed { index, row ->
                ClearAirRow(
                    title = providerName(row.id),
                    descriptionLine = { ProviderLine(row) },
                    leading = { ClearAirGlyphDisc(ClearAirGlyph.NODE, size = 19.dp) },
                    onClick = { onProvider(row.id) },
                    divider = index > 0,
                    lineBox = true,
                )
            }
        }
        ExploreGroup(title = "Learn AirVault", top = LEARN_GROUP_TOP, action = null, onAction = {}) {
            LearnArticles.ALL.forEachIndexed { index, article ->
                ClearAirRow(
                    title = article.title,
                    description = article.readTime,
                    leading = { ClearAirGlyphDisc(ClearAirGlyph.BOOK, size = 19.dp) },
                    onClick = { onArticle(article.id) },
                    divider = index > 0,
                    lineBox = true,
                )
            }
        }
    }
}

/**
 * The line under a provider: its status mark and word in the colour of its state, blue for Working, amber for Rate-Limited and the quiet grey for Off, then the
 * note that says why when there is one, or else what the provider supplies. The mark is drawn, so the word is the line's one text for the status, and the
 * colour is never alone.
 */
@Composable
private fun RowScope.ProviderLine(row: ProviderRow) {
    val palette = LocalClearAirPalette.current
    val status = row.status
    val color = when (status) {
        ProviderStatus.Ready, is ProviderStatus.Healthy -> palette.vaultBlue
        is ProviderStatus.CircuitOpen, is ProviderStatus.CoolingDown -> palette.sparkText
        ProviderStatus.Off, ProviderStatus.NotActive, ProviderStatus.NeedsKey -> palette.ink2
    }
    StatusMark(status, color)
    Text(
        text = providerStatusName(status),
        style = ROW_DESCRIPTION.copy(fontFamily = ClearAirType.rowTitle.fontFamily, fontWeight = FontWeight.SemiBold).inLineBox(),
        color = color,
    )
    (providerStatusNote(status) ?: providerCoverage(row.id))?.let { note ->
        Spacer(Modifier.width(NOTE_GAP))
        Text(text = "· $note", style = ROW_DESCRIPTION.inLineBox(), color = palette.ink2)
    }
}

/** The mark before a status word, in the mock's size: a check for Working, a diamond for Rate-Limited and a dash for Off. */
@Composable
private fun StatusMark(status: ProviderStatus, color: Color) {
    val wide = status == ProviderStatus.Ready || status is ProviderStatus.Healthy
    Canvas(Modifier.size(width = if (wide) CHECK_BOX_WIDTH else MARK_BOX_WIDTH, height = MARK_BOX_HEIGHT)) {
        val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when {
            wide -> drawPath(
                Path().apply {
                    moveTo(1.5.dp.toPx(), 6.5.dp.toPx())
                    lineTo(4.dp.toPx(), 9.dp.toPx())
                    lineTo(9.5.dp.toPx(), 3.dp.toPx())
                },
                color,
                style = stroke,
            )
            status is ProviderStatus.CircuitOpen || status is ProviderStatus.CoolingDown -> drawPath(
                Path().apply {
                    moveTo(4.2.dp.toPx(), 3.8.dp.toPx())
                    lineTo(7.6.dp.toPx(), 7.2.dp.toPx())
                    lineTo(4.2.dp.toPx(), 10.6.dp.toPx())
                    lineTo(0.8.dp.toPx(), 7.2.dp.toPx())
                    close()
                },
                color,
            )
            else -> drawLine(color, Offset(1.dp.toPx(), 7.dp.toPx()), Offset(7.5.dp.toPx(), 7.dp.toPx()), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * A group of the landing: [top] under whatever is above it, its eyebrow with a text [action] to the full list when it has one, then its rows. The header is
 * 14 dp tall and the rows start 8 dp under it, or 4 dp under a header with no action, as the mock sets them. The action is pressed over a 48 dp box that does
 * not move the page.
 */
@Composable
private fun ExploreGroup(title: String, top: Dp, action: String?, onAction: () -> Unit, content: @Composable () -> Unit) {
    Gap(top)
    Row(
        modifier = Modifier.fillMaxWidth().designedHeight(HEADER_HEIGHT, exact = true).padding(horizontal = ClearAirSpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClearAirEyebrow(text = title, modifier = Modifier.weight(1f).offset(y = if (action != null) EYEBROW_DROP else 0.dp), top = 0.dp, bottom = 0.dp)
        if (action != null) {
            Box(
                modifier = Modifier
                    .requiredHeight(ClearAirSpacing.touchTarget)
                    .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onAction)
                    .padding(start = ClearAirSpacing.gutter),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(text = action, style = ClearAirType.rowTitle.copy(fontSize = 13.sp), color = LocalClearAirPalette.current.ink, maxLines = 1)
            }
        }
    }
    Gap(if (action != null) HEADER_GAP else HEADER_GAP_BARE)
    content()
}

/** The mock's spacing of the landing, from the top of the page and between groups. */
private val FIRST_GROUP_TOP = 12.5.dp
private val GROUP_TOP = 21.dp
private val LEARN_GROUP_TOP = 18.dp
private val HEADER_HEIGHT = 14.dp
private val HEADER_GAP = 8.dp

/** In the mock the "All N" action's line is taller than the eyebrow's and the row centres both, so the eyebrow of a group with an action sits this much lower. */
private val EYEBROW_DROP = 1.25.dp
private val HEADER_GAP_BARE = 4.dp

/** The size of the status mark's box, which sets where the word begins, and the gap before the note. */
private val CHECK_BOX_WIDTH = 14.5.dp
private val MARK_BOX_WIDTH = 12.4.dp
private val MARK_BOX_HEIGHT = 14.dp
private val NOTE_GAP = 3.5.dp
