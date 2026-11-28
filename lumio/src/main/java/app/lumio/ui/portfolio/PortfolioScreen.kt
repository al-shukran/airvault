package app.lumio.ui.portfolio

import app.lumio.DebugDemoData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.config.Fiat
import app.lumio.data.net.NetworkId
import app.lumio.home.Masking
import app.lumio.portfolio.AllocationSlice
import app.lumio.portfolio.BalanceSort
import app.lumio.portfolio.ChainFamily
import app.lumio.portfolio.PortfolioFilter
import app.lumio.portfolio.PortfolioFormat
import app.lumio.portfolio.PortfolioHolding
import app.lumio.portfolio.PortfolioRow
import app.lumio.portfolio.PortfolioView
import app.lumio.portfolio.SentencePicker
import app.lumio.portfolio.sentence
import app.lumio.ui.home.AssetDisc
import app.lumio.ui.home.AssetIconSpec
import app.lumio.ui.home.ChainDots
import app.lumio.ui.home.letterOf
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirAmount
import app.lumio.ui.kit.ClearAirChangeLine
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirFilterSentence
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirGlyphDisc
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirTestnetHatch
import app.lumio.ui.kit.HiddenSmallLine
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.kit.ROW_DESCRIPTION
import app.lumio.ui.kit.inLineBox
import java.time.ZoneId

/**
 * The Portfolio tab: the total of the side, what it is made of, and the balances that the filter sentence describes. Each underlined phrase opens its
 * radio sheet through [onPick] ([PortfolioSheet] draws it over the whole root). [holdings] is null until the side is read, so the tab shows its frame and
 * no number. A row that adds up several networks shows their logos ([chainIconsOf]) after its description, and a token on one network names it
 * ([networkName]).
 */
@Composable
fun PortfolioScreen(
    holdings: List<PortfolioHolding>?,
    filter: PortfolioFilter,
    onPick: (SentencePicker) -> Unit,
    walletName: String,
    showBalance: Boolean,
    hidesSmall: (Double?) -> Boolean = { false },
    testnet: Boolean,
    zone: ZoneId,
    nowMs: Long = System.currentTimeMillis(),
    otherSide: OtherSide? = null,
    onReceive: (() -> Unit)? = null,
    iconOf: (PortfolioRow) -> AssetIconSpec = { AssetIconSpec.Letter(letterOf(it.coin)) },
    chainIconsOf: (PortfolioRow) -> List<AssetIconSpec> = { emptyList() },
    networkName: (NetworkId) -> String = { it.name },
) {
    var showSmall by rememberSaveable { mutableStateOf(false) }
    if (holdings != null && PortfolioView.isEmpty(holdings)) {
        EmptyBalances(walletName = walletName, testnet = testnet, showBalance = showBalance, otherSide = otherSide, onReceive = onReceive)
        return
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = ClearAirSpacing.xxl)) {
        // The hero is centred over its numerals, with its eyebrow over it.
        ClearAirEyebrow(
            text = "$walletName · Total Balance",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = ClearAirSpacing.gutter),
            top = EYEBROW_TOP,
            bottom = 0.dp,
        )
        if (holdings == null) return@Column
        val total = PortfolioView.total(holdings)
        ClearAirAmount(
            // Funds with no price have no total, so the hero says so and the rows below show the coins.
            text = total.usd?.let { LocalFiat.current.amount(it) } ?: "—",
            hidden = !showBalance,
            style = ClearAirType.hero.inLineBox(),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = HERO_TOP),
        )
        ClearAirChangeLine(
            changePct = total.changePct,
            changeUsd = total.changeUsd,
            shown = showBalance,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = CHANGE_TOP),
        )
        AllocationLine(slices = PortfolioView.allocation(holdings))
        ClearAirFilterSentence(parts = filter.sentence(), onPick = onPick, modifier = Modifier.padding(top = SENTENCE_TOP))
        // Small balances are left out of the list only: the total above still counts every asset.
        val rows = PortfolioView.rows(holdings, filter)
        val listed = if (showSmall) rows else rows.filterNot { hidesSmall(it.usd) }
        Spacer(Modifier.height(LIST_TOP))
        listed.forEachIndexed { index, row ->
            val chains = if (row.merged) chainIconsOf(row) else emptyList()
            ClearAirRow(
                title = row.title,
                description = describe(row, showBalance, networkName),
                descriptionSuffix = if (chains.isEmpty()) null else ({ ChainDots(chains) }),
                leading = { AssetDisc(iconOf(row)) },
                trailing = { RowValue(row = row, shown = showBalance, zone = zone, nowMs = nowMs) },
                divider = index > 0,
                lineBox = true,
            )
        }
        if (listed.isEmpty() && rows.isNotEmpty()) HiddenSmallLine(count = rows.size, onShow = { showSmall = true })
    }
}

/**
 * The sheet that [picker] names, over the whole root: the chains, the grouping or the sort. A choice applies through [onFilter] and closes the sheet, as
 * [onClose] does. Each chain row leads with its logo ([familyIcon]), and All Chains with the compass.
 */
@Composable
fun PortfolioSheet(
    picker: SentencePicker?,
    onClose: () -> Unit,
    holdings: List<PortfolioHolding>?,
    filter: PortfolioFilter,
    onFilter: (PortfolioFilter) -> Unit,
    accounts: Map<ChainFamily, Int>,
    showBalance: Boolean,
    familyIcon: (ChainFamily) -> AssetIconSpec,
) {
    val pick: (PortfolioFilter) -> Unit = { onClose(); onFilter(it) }
    ClearAirSheet(visible = picker == SentencePicker.CHAIN, onClose = onClose) {
        ClearAirChoiceSheet(
            title = "Chains",
            groups = listOf(ChoiceGroup(null, chainRows(holdings.orEmpty(), filter, accounts, showBalance, LocalFiat.current, familyIcon, pick))),
        )
    }
    ClearAirSheet(visible = picker == SentencePicker.GROUPING, onClose = onClose) {
        ClearAirChoiceSheet(title = "Grouping", groups = listOf(ChoiceGroup(null, groupingRows(filter, pick))))
    }
    ClearAirSheet(visible = picker == SentencePicker.SORT, onClose = onClose) {
        ClearAirChoiceSheet(title = "Sort Balances", groups = sortGroups(filter, pick))
    }
}

/** The sheet a debug demo opens with the Portfolio, from its flags, or none. */
fun demoPicker(): SentencePicker? = when {
    DebugDemoData.flag("filter:chain") -> SentencePicker.CHAIN
    DebugDemoData.flag("filter:group") -> SentencePicker.GROUPING
    DebugDemoData.flag("filter:sort") -> SentencePicker.SORT
    else -> null
}

/**
 * The empty side: nothing is held yet, so there is no total to show. It draws the dashed slab, names the side, says why [walletName] is empty, and offers
 * the one action that fills the screen. [otherSide] shows under the words when the other side holds funds, and [onReceive] adds the Receive capsule.
 */
@Composable
private fun EmptyBalances(walletName: String, testnet: Boolean, showBalance: Boolean, otherSide: OtherSide?, onReceive: (() -> Unit)?) {
    val palette = LocalClearAirPalette.current
    val side = if (testnet) "Testnet" else "Mainnet"
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ClearAirSpacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(SLAB_TOP))
        EmptySlab()
        Text(
            text = "Nothing on $side Yet",
            modifier = Modifier.padding(top = EMPTY_TITLE_TOP),
            style = ClearAirType.titleXL.inLineBox(),
            color = palette.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "$walletName has no ${side.lowercase()} balances so far. That's normal for a new wallet. Anything you receive shows up here within seconds.",
            modifier = Modifier.padding(top = EMPTY_BODY_TOP),
            style = ClearAirType.body.inLineBox(),
            color = palette.ink2,
            textAlign = TextAlign.Center,
        )
        otherSide?.let { OtherSideCard(other = it, shown = showBalance) }
        onReceive?.let { receive ->
            ClearAirPrimaryButton(
                text = "Receive on $side",
                onClick = receive,
                icon = ClearAirGlyph.RECEIVE,
                fullWidth = false,
                labelStyle = ClearAirType.button,
                modifier = Modifier.padding(top = ClearAirSpacing.lg, bottom = ClearAirSpacing.xl),
            )
        }
    }
}

/**
 * The other side's balance: its coin's logo, its amount and side, its network, and the tap that switches to that side. A testnet side wears the testnet
 * hatch. A hidden balance ([shown] off) masks the amount and leaves the side and the network.
 */
@Composable
private fun OtherSideCard(other: OtherSide, shown: Boolean) {
    val palette = LocalClearAirPalette.current
    val side = if (other.testnet) "Testnet" else "Mainnet"
    val shape = RoundedCornerShape(CARD_CORNER)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = CARD_TOP)
            .clip(shape)
            .border(1.dp, palette.hairline, shape)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = other.onSwitch),
    ) {
        if (other.testnet) ClearAirTestnetHatch(text = "TESTNET")
        Row(modifier = Modifier.padding(horizontal = CARD_SIDE, vertical = CARD_VERTICAL), verticalAlignment = Alignment.CenterVertically) {
            AssetDisc(other.icon)
            Spacer(Modifier.width(ClearAirSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "${Masking.amount(other.amount, hidden = !shown)} on $side", style = ClearAirType.rowTitle.inLineBox(), color = palette.ink)
                Text(text = "${other.network} · tap to switch sides", style = ROW_DESCRIPTION.inLineBox(), color = palette.ink2)
            }
            Spacer(Modifier.width(ClearAirSpacing.sm))
            ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3, size = CHEVRON)
        }
    }
}

/** The vault's slab drawn dashed, with the spark above it: the mock's empty-side illustration, drawn on its own 128 × 112 grid. */
@Composable
private fun EmptySlab() {
    val palette = LocalClearAirPalette.current
    Canvas(modifier = Modifier.size(SLAB_WIDTH, SLAB_HEIGHT)) {
        val unit = size.width / SLAB_GRID
        val dashed = Stroke(width = SLAB_STROKE.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), GAP.toPx())))
        drawPath(polyline(unit, 64f, 34f, 106f, 58f, 64f, 82f, 22f, 58f, closed = true), palette.ink2, style = dashed)
        drawPath(polyline(unit, 22f, 58f, 22f, 78f, 64f, 102f, 106f, 78f, 106f, 58f), palette.ink2, style = dashed)
        drawPath(polyline(unit, 64f, 82f, 64f, 102f), palette.ink2, style = dashed)
        drawPath(polyline(unit, 64f, 8f, 70f, 17f, 64f, 26f, 58f, 17f, closed = true), palette.spark)
    }
}

/** A path through [xy], given as x and y pairs on the mock's grid and scaled by [unit] to the canvas, closed when [closed] is set. */
private fun polyline(unit: Float, vararg xy: Float, closed: Boolean = false): Path = Path().apply {
    moveTo(xy[0] * unit, xy[1] * unit)
    for (i in 2 until xy.size step 2) lineTo(xy[i] * unit, xy[i + 1] * unit)
    if (closed) close()
}

/**
 * The other side of the Portfolio, when it holds funds: its [amount], such as "12.5 MON", its [network], such as "Monad Testnet", whether it is [testnet],
 * its coin's logo ([icon]), and [onSwitch], which moves the page to that side. An empty Portfolio shows it under its words.
 */
data class OtherSide(val amount: String, val network: String, val testnet: Boolean, val icon: AssetIconSpec, val onSwitch: () -> Unit)

/**
 * The allocation line: the three largest coins and the rest, each a rounded segment in ink at its own strength with a gap between them, and under it a
 * legend that gives each coin a square of its segment's strength and its share. The legend is centred and wraps rather than cuts a long ticker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllocationLine(slices: List<AllocationSlice>) {
    val palette = LocalClearAirPalette.current
    if (slices.isEmpty()) return
    fun strength(index: Int) = palette.ink.copy(alpha = STRENGTHS[index.coerceAtMost(STRENGTHS.lastIndex)])
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = BAR_TOP)
            .height(ALLOCATION_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
    ) {
        slices.forEachIndexed { index, slice ->
            Box(
                modifier = Modifier
                    .weight(slice.share.toFloat().coerceAtLeast(MIN_SEGMENT))
                    .fillMaxSize()
                    .clip(RoundedCornerShape(SEGMENT_CORNER))
                    .background(strength(index)),
            )
        }
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = LEGEND_TOP),
        horizontalArrangement = Arrangement.spacedBy(LEGEND_GAP, Alignment.CenterHorizontally),
    ) {
        slices.forEachIndexed { index, slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(LEGEND_SWATCH).clip(RoundedCornerShape(LEGEND_SWATCH_CORNER)).background(strength(index)))
                Spacer(Modifier.width(LEGEND_SWATCH_GAP))
                Text(text = "${slice.label} ${Math.round(slice.share * PERCENT)}%", style = LEGEND, color = palette.ink2, maxLines = 1)
            }
        }
    }
}

/**
 * The value at the end of a row: its amount in dollars, and under it its change in the secondary colour. A value made from a last-known price shows in
 * the secondary colour, and the time of that price, such as "as of 14:05", follows the last-known change on its line: "▲ 1.82% · as of 14:05".
 */
@Composable
private fun RowValue(row: PortfolioRow, shown: Boolean, zone: ZoneId, nowMs: Long) {
    val palette = LocalClearAirPalette.current
    val staleAsOfMs = row.staleAsOfMs
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = Masking.amount(row.usd?.let { LocalFiat.current.amount(it) } ?: "—", hidden = !shown),
            style = ClearAirType.rowValue.inLineBox(),
            color = if (staleAsOfMs == null) palette.ink else palette.ink2,
            textAlign = TextAlign.End,
        )
        val change = row.change24h?.let(PortfolioFormat::change)
        val asOf = staleAsOfMs?.let { PortfolioFormat.asOf(it, nowMs, zone) }
        val note = listOfNotNull(change, asOf).joinToString(" · ")
        if (note.isNotEmpty()) Text(text = note, style = ROW_CHANGE, color = palette.ink2, textAlign = TextAlign.End)
    }
}

/**
 * The line under a row's title: its amount of coins with the coin, then how many networks it adds up, that it is bridged, how many accounts hold it on
 * its one network, or, for a token on one network, that network's name ([networkName]).
 */
private fun describe(row: PortfolioRow, shown: Boolean, networkName: (NetworkId) -> String): String {
    val coins = Masking.amount(PortfolioFormat.coins(row.amount), hidden = !shown)
    val where = when {
        row.merged -> "${row.networks.size} chains"
        row.bridged -> "Bridged"
        row.holdings > 1 -> "${row.holdings} accounts"
        row.contract != null -> networkName(row.networks.first())
        else -> null
    }
    return listOfNotNull("$coins ${row.coin}", where).joinToString(" · ")
}

/** The chain choices: all chains, then each family that holds something, largest first. */
private fun chainRows(
    holdings: List<PortfolioHolding>,
    filter: PortfolioFilter,
    accounts: Map<ChainFamily, Int>,
    shown: Boolean,
    fiat: Fiat,
    familyIcon: (ChainFamily) -> AssetIconSpec,
    pick: (PortfolioFilter) -> Unit,
): List<ChoiceRow> {
    val total = PortfolioView.total(holdings).usd
    val families = holdings.groupBy { it.family }
        .map { (family, items) -> family to items.mapNotNull { it.usd }.takeIf { it.isNotEmpty() }?.sum() }
        .sortedByDescending { it.second ?: 0.0 }
    val all = ChoiceRow(
        title = "All Chains",
        detail = "Grouped by asset",
        trailing = total?.let { Masking.amount(fiat.amount(it), hidden = !shown) },
        selected = filter.chain == null,
        onPick = { pick(filter.copy(chain = null)) },
        leading = { ClearAirGlyphDisc(glyph = ClearAirGlyph.EXPLORE, size = COMPASS) },
    )
    val each = families.map { (family, usd) ->
        val count = accounts[family] ?: 0
        ChoiceRow(
            title = family.label,
            detail = if (family == ChainFamily.EVM) "Ethereum and its L2s" else "$count ${if (count == 1) "account" else "accounts"}",
            trailing = usd?.let { Masking.amount(fiat.amount(it), hidden = !shown) },
            selected = filter.chain == family,
            onPick = { pick(filter.copy(chain = family)) },
            leading = { AssetDisc(familyIcon(family)) },
        )
    }
    return listOf(all) + each
}

/** The grouping choices. Grouping needs all chains, so it is disabled for one chain, and the disabled row says why. */
private fun groupingRows(filter: PortfolioFilter, pick: (PortfolioFilter) -> Unit): List<ChoiceRow> {
    val allChains = filter.chain == null
    return listOf(
        ChoiceRow(
            title = "Grouped by Asset",
            detail = if (allChains) null else "Grouping needs all chains",
            selected = filter.isGrouped,
            enabled = allChains,
            onPick = { pick(filter.copy(grouped = true)) },
        ),
        ChoiceRow(
            title = "Not Grouped",
            detail = "Each chain's balance on its own row",
            selected = !filter.isGrouped,
            onPick = { pick(filter.copy(grouped = false)) },
        ),
    )
}

/** The sort choices: the field first, then its order. Picking a field sets the order it starts in. */
private fun sortGroups(filter: PortfolioFilter, pick: (PortfolioFilter) -> Unit): List<ChoiceGroup> {
    val byBalance = filter.sort == BalanceSort.BALANCE
    val orderRows = if (byBalance) {
        listOf(
            ChoiceRow(title = "Largest First", selected = !filter.ascending, onPick = { pick(filter.copy(ascending = false)) }),
            ChoiceRow(title = "Smallest First", selected = filter.ascending, onPick = { pick(filter.copy(ascending = true)) }),
        )
    } else {
        listOf(
            ChoiceRow(title = "A to Z", selected = filter.ascending, onPick = { pick(filter.copy(ascending = true)) }),
            ChoiceRow(title = "Z to A", selected = !filter.ascending, onPick = { pick(filter.copy(ascending = false)) }),
        )
    }
    return listOf(
        ChoiceGroup(
            label = "Sort By",
            rows = listOf(
                ChoiceRow(
                    title = "Balance",
                    detail = "Value in USD",
                    selected = byBalance,
                    onPick = { pick(filter.copy(sort = BalanceSort.BALANCE, ascending = false)) },
                ),
                ChoiceRow(
                    title = "Name",
                    detail = "Alphabetical",
                    selected = !byBalance,
                    onPick = { pick(filter.copy(sort = BalanceSort.NAME, ascending = true)) },
                ),
            ),
        ),
        ChoiceGroup(label = "Order", rows = orderRows),
    )
}

/** The gap under the top bar to the eyebrow's line box, and from the eyebrow to the hero's, as the mock sets them. */
private val EYEBROW_TOP = 9.dp
private val HERO_TOP = 16.dp
private val CHANGE_TOP = 4.dp
private val BAR_TOP = 14.dp
private val LEGEND_TOP = 8.dp
private val SENTENCE_TOP = 10.dp
private val LIST_TOP = 14.dp

/** The allocation bar: 6 dp segments with 3 dp corners and 3 dp between them. */
private val ALLOCATION_HEIGHT = 6.dp
private val SEGMENT_GAP = 3.dp
private val SEGMENT_CORNER = 3.dp
private val MIN_SEGMENT = 0.01f
private const val PERCENT = 100
private val STRENGTHS = listOf(1f, 0.6f, 0.38f, 0.2f)

/** The legend: a 7 dp square with 2 dp corners, 5 dp before its share, and 12 dp between the coins, in JetBrains Mono Medium 11.5 sp. */
private val LEGEND_SWATCH = 7.dp
private val LEGEND_SWATCH_CORNER = 2.dp
private val LEGEND_SWATCH_GAP = 5.dp
private val LEGEND_GAP = 12.dp
private val LEGEND = ClearAirType.rowValue.copy(fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.sp).inLineBox()

/** A row's change: JetBrains Mono Regular 12 sp, a little tight, in the secondary colour. */
private val ROW_CHANGE = ClearAirType.time.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.02).em).inLineBox()

/** The empty side: the slab under the top bar, the title and the words under it, and the card of the other side. */
private val SLAB_TOP = 56.dp
private val EMPTY_TITLE_TOP = 29.dp
private val EMPTY_BODY_TOP = 12.dp
private val CARD_TOP = 19.dp
private val CARD_SIDE = 15.dp
private val CARD_VERTICAL = 14.dp
private val CARD_CORNER = 18.dp
private val CHEVRON = 16.dp

/** The compass of the All Chains row, drawn at 19 dp as a list row's glyph. */
private val COMPASS = 19.dp

private val SLAB_WIDTH = 128.dp
private val SLAB_HEIGHT = 112.dp
private const val SLAB_GRID = 128f
private val SLAB_STROKE = 2.dp
private val DASH = 6.dp
private val GAP = 5.dp
