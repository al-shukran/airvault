package app.lumio.ui.portfolio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.config.Fiat
import app.lumio.home.Masking
import app.lumio.portfolio.AllocationSlice
import app.lumio.portfolio.BalanceSort
import app.lumio.portfolio.ChainFamily
import app.lumio.portfolio.PortfolioFormat
import app.lumio.portfolio.PortfolioFilter
import app.lumio.portfolio.PortfolioHolding
import app.lumio.portfolio.PortfolioRow
import app.lumio.portfolio.PortfolioView
import app.lumio.portfolio.SentencePicker
import app.lumio.portfolio.sentence
import app.lumio.transactions.TxFeedItem
import app.lumio.ui.kit.ChoiceGroup
import app.lumio.ui.kit.ChoiceRow
import app.lumio.ui.kit.ClearAirAmount
import app.lumio.ui.kit.ClearAirChangeLine
import app.lumio.ui.kit.ClearAirChangePercent
import app.lumio.ui.kit.ClearAirChoiceSheet
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirFilterSentence
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirTestnetHatch
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.transactions.ActivityRowItem
import java.time.ZoneId

/**
 * The Portfolio tab: the total of the side, what it is made of, and the balances that the filter sentence describes. Each underlined phrase opens a
 * radio sheet, and a choice applies and closes it. [holdings] is null until the side is read, so the tab shows its frame and no number.
 */
@Composable
fun PortfolioScreen(
    holdings: List<PortfolioHolding>?,
    filter: PortfolioFilter,
    onFilter: (PortfolioFilter) -> Unit,
    walletName: String,
    accounts: Map<ChainFamily, Int>,
    showBalance: Boolean,
    hidesSmall: (Double?) -> Boolean = { false },
    testnet: Boolean,
    recent: List<TxFeedItem>,
    names: Map<String, String>,
    zone: ZoneId,
    onOpenActivity: () -> Unit,
    onOpenRow: (TxFeedItem) -> Unit,
    otherSide: OtherSide? = null,
    onReceive: (() -> Unit)? = null,
) {
    var picker by remember { mutableStateOf<SentencePicker?>(null) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = ClearAirSpacing.xxl)) {
        ClearAirEyebrow(text = "$walletName · Total Balance", modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs))
        if (holdings == null) return@Column
        val total = PortfolioView.total(holdings)
        val usd = total.usd
        val fiat = LocalFiat.current
        if (usd == null) {
            EmptyBalances(testnet = testnet, showBalance = showBalance, otherSide = otherSide, onReceive = onReceive)
        } else {
            ClearAirAmount(
                text = fiat.amount(usd),
                hidden = !showBalance,
                style = ClearAirType.hero,
                modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter),
            )
            ClearAirChangeLine(
                changePct = total.changePct,
                changeUsd = total.changeUsd,
                shown = showBalance,
                modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter),
            )
            AllocationLine(slices = PortfolioView.allocation(holdings))
            ClearAirFilterSentence(
                parts = filter.sentence(),
                onPick = { picker = it },
                modifier = Modifier.padding(top = ClearAirSpacing.md),
            )
            // Small balances are left out of the list only: the total above still counts every asset.
            for (row in PortfolioView.rows(holdings, filter).filterNot { hidesSmall(it.usd) }) {
                ClearAirRow(
                    title = row.title,
                    description = describe(row, showBalance),
                    trailing = { RowValue(row = row, shown = showBalance) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = ClearAirSpacing.gutter, end = ClearAirSpacing.gutter, top = ClearAirSpacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClearAirEyebrow(text = "Activity", modifier = Modifier.weight(1f))
            ClearAirTextAction(text = "All", onClick = onOpenActivity)
        }
        for (item in recent) {
            ActivityRowItem(item = item, names = names, zone = zone, shown = showBalance, onClick = { onOpenRow(item) })
        }
    }
    ClearAirSheet(visible = picker == SentencePicker.CHAIN, onClose = { picker = null }) {
        ClearAirChoiceSheet(
            title = "Chains",
            groups = listOf(ChoiceGroup(null, chainRows(holdings.orEmpty(), filter, accounts, showBalance, LocalFiat.current) { picker = null; onFilter(it) })),
        )
    }
    ClearAirSheet(visible = picker == SentencePicker.GROUPING, onClose = { picker = null }) {
        ClearAirChoiceSheet(title = "Grouping", groups = listOf(ChoiceGroup(null, groupingRows(filter) { picker = null; onFilter(it) })))
    }
    ClearAirSheet(visible = picker == SentencePicker.SORT, onClose = { picker = null }) {
        ClearAirChoiceSheet(title = "Sort Balances", groups = sortGroups(filter) { picker = null; onFilter(it) })
    }
}

/**
 * The empty side: nothing is priced yet, so there is no total to show. It draws the dashed slab, names the side, says why it is empty, and offers the one
 * action that fills the screen. [otherSide] shows under the words when the other side holds funds, and [onReceive] adds the Receive capsule.
 */
@Composable
private fun EmptyBalances(testnet: Boolean, showBalance: Boolean, otherSide: OtherSide?, onReceive: (() -> Unit)?) {
    val palette = LocalClearAirPalette.current
    val side = if (testnet) "Testnet" else "Mainnet"
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xl)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { EmptySlab() }
        Spacer(Modifier.height(ClearAirSpacing.lg))
        Text(text = "Nothing on $side Yet", style = ClearAirType.titleXL, color = palette.ink)
        Spacer(Modifier.height(ClearAirSpacing.xs))
        Text(
            text = "No ${side.lowercase()} balances so far. That's normal for a new wallet. Anything you receive shows up here within seconds.",
            style = ClearAirType.body,
            color = palette.ink2,
        )
        otherSide?.let { OtherSideCard(other = it, shown = showBalance) }
        onReceive?.let { receive ->
            ClearAirPrimaryButton(text = "Receive on $side", onClick = receive, modifier = Modifier.padding(top = ClearAirSpacing.lg))
        }
    }
}

/**
 * The other side's balance: its amount and side, its network, and the tap that switches to that side. A testnet side wears the testnet hatch. A hidden
 * balance ([shown] off) masks the amount and leaves the side and the network.
 */
@Composable
private fun OtherSideCard(other: OtherSide, shown: Boolean) {
    val palette = LocalClearAirPalette.current
    val side = if (other.testnet) "Testnet" else "Mainnet"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ClearAirSpacing.lg)
            .clip(RoundedCornerShape(CARD_CORNER))
            .background(palette.raised)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = other.onSwitch),
    ) {
        if (other.testnet) ClearAirTestnetHatch(text = "TESTNET")
        Column(modifier = Modifier.padding(ClearAirSpacing.md)) {
            Text(text = "${Masking.amount(other.amount, hidden = !shown)} on $side", style = ClearAirType.rowTitle, color = palette.ink)
            Text(text = "${other.network} · tap to switch sides", style = ClearAirType.body, color = palette.ink2)
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
 * and [onSwitch], which moves the page to that side. An empty Portfolio shows it under its words.
 */
data class OtherSide(val amount: String, val network: String, val testnet: Boolean, val onSwitch: () -> Unit)

/** The allocation line: the three largest coins and the rest, each a segment in ink at its own strength, with its share written under it. */
@Composable
private fun AllocationLine(slices: List<AllocationSlice>) {
    val palette = LocalClearAirPalette.current
    if (slices.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm).height(ALLOCATION_HEIGHT),
    ) {
        slices.forEachIndexed { index, slice ->
            Box(
                modifier = Modifier
                    .weight(slice.share.toFloat().coerceAtLeast(MIN_SEGMENT))
                    .fillMaxSize()
                    .background(palette.ink.copy(alpha = STRENGTHS[index.coerceAtMost(STRENGTHS.lastIndex)])),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
        slices.forEachIndexed { index, slice ->
            if (index > 0) Spacer(Modifier.width(ClearAirSpacing.md))
            Text(
                text = "${slice.label} ${Math.round(slice.share * PERCENT)}%",
                style = ClearAirType.body,
                color = palette.ink2,
            )
        }
    }
}

/** The value at the end of a row: its amount in dollars, and under it its change. */
@Composable
private fun RowValue(row: PortfolioRow, shown: Boolean) {
    val palette = LocalClearAirPalette.current
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = Masking.amount(row.usd?.let { LocalFiat.current.amount(it) } ?: "—", hidden = !shown),
            style = ClearAirType.rowValue,
            color = palette.ink,
            textAlign = TextAlign.End,
        )
        row.change24h?.let { change -> ClearAirChangePercent(change) }
    }
}

/** The line under a row's title: its amount of coins with the coin, and how many networks it is on or that it is bridged. */
private fun describe(row: PortfolioRow, shown: Boolean): String {
    val coins = Masking.amount(PortfolioFormat.coins(row.amount), hidden = !shown)
    val where = when {
        row.merged -> "${row.networks.size} chains"
        row.bridged -> "Bridged"
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
    )
    val each = families.map { (family, usd) ->
        val count = accounts[family] ?: 0
        ChoiceRow(
            title = family.label,
            detail = if (family == ChainFamily.EVM) "Ethereum and its L2s" else "$count ${if (count == 1) "account" else "accounts"}",
            trailing = usd?.let { Masking.amount(fiat.amount(it), hidden = !shown) },
            selected = filter.chain == family,
            onPick = { pick(filter.copy(chain = family)) },
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

private val ALLOCATION_HEIGHT = 8.dp
private val MIN_SEGMENT = 0.01f
private const val PERCENT = 100
private val STRENGTHS = listOf(1f, 0.6f, 0.38f, 0.2f)
private val SLAB_WIDTH = 128.dp
private val SLAB_HEIGHT = 112.dp
private const val SLAB_GRID = 128f
private val SLAB_STROKE = 2.dp
private val DASH = 6.dp
private val GAP = 5.dp
private val CARD_CORNER = 16.dp
