package app.lumio.ui.transactions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lensly.app.AddressBookEntry
import app.lensly.app.addressKeyOf
import app.lensly.app.plainName
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirPalette
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.Masking
import app.lumio.portfolio.PortfolioFormat
import app.lumio.transactions.ActivityText
import app.lumio.transactions.TxDetail
import app.lumio.ui.kit.ClearAirAddressGrid
import app.lumio.ui.kit.ClearAirDialog
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirTestnetHatch
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.LocalFiat
import app.lumio.ui.kit.rememberCopyAction
import app.lumio.ui.shell.LumioPageScreen
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The test tag of the explorer action, which asks before it opens a browser. */
const val TX_EXPLORER_TAG = "tx-explorer"

/** The side of the status disc, which is the mock's 56 dp circle, and the side of a row's monogram. */
private val STATUS_DISC = 56.dp
private val ADDRESS_DISC = 36.dp

/** The side of the monogram on the address sheet, which is larger than a row's. */
internal val MONOGRAM_BIG = 48.dp

/** The side of the spark on a pending status disc, and of the spark that leads a pending status line. */
private val STATUS_SPARK = 7.dp
private val LINE_SPARK = 5.dp

/** The corner and outline of a check card. */
private val CARD_CORNER = 18.dp
private val CARD_BORDER = 1.5.dp

/** The height of an address row, which is one line of name and one of detail. */
private val ADDRESS_ROW_MIN_HEIGHT = 54.dp

/**
 * The transaction page: its status, its two sides, its details and its hash. The page is titled by the transaction itself, such as "Sent 0.25 MON".
 * Every address on the page is tappable and opens its address-book sheet, so a counterparty is named the same way everywhere. [accountNames] names the
 * wallet's own accounts, and [book] is the wallet's address book, both by the address key. The page is read only: names are made on Lensly.
 */
@Composable
fun TransactionDetailScreen(
    detail: TxDetail,
    accountNames: Map<String, String>,
    book: List<AddressBookEntry>,
    backLabel: String,
    showBalance: Boolean,
    onAddress: (String) -> Unit,
    onExplorer: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val palette = LocalClearAirPalette.current
    val tx = detail.tx
    val title = titleOf(detail, zone = zone, shown = showBalance)
    var askExplorer by remember { mutableStateOf(false) }
    LumioPageScreen(title = title, backLabel = backLabel, offline = null, onBack = onBack, onLock = onLock) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = ClearAirSpacing.xxl)) {
            if (tx.network.testnet) ClearAirTestnetHatch(text = "TESTNET · TEST FUNDS HAVE NO VALUE")
            StatusHero(detail = detail, title = title, zone = zone, shown = showBalance)
            val counterparty = tx.counterparty
            val incoming = tx.direction == TxDirection.IN
            if (counterparty != null) {
                CheckCard(
                    eyebrow = if (incoming) "From" else "To",
                    address = counterparty,
                    line = counterpartyLine(entryOf(counterparty, book)),
                    onClick = { onAddress(counterparty) },
                )
            }
            Sectioned(label = if (incoming) "To" else "From")
            AddressRow(
                line = ownLine(tx.account, name = accountNames[addressKeyOf(tx.account)]),
                onClick = { onAddress(tx.account) },
            )
            tx.asset.contract?.let { contract ->
                Sectioned(label = "Token Contract")
                val entry = entryOf(contract, book)
                AddressRow(
                    line = AddressLine(
                        name = tx.asset.name,
                        detail = entry?.let(::addressBookSourceOf) ?: "Tap to add it on Lensly",
                        named = true,
                    ),
                    onClick = { onAddress(contract) },
                )
            }
            Spacer(Modifier.height(ClearAirSpacing.lg))
            Details(detail = detail)
            Spacer(Modifier.height(ClearAirSpacing.md))
            ClearAirTextAction(
                text = "View on Explorer",
                onClick = { if (detail.explorerUrl != null) askExplorer = true },
                modifier = Modifier.padding(start = ClearAirSpacing.gutter),
            )
            if (detail.flags.isNotEmpty()) {
                Spacer(Modifier.height(ClearAirSpacing.lg))
                Text(
                    text = "Flagged by the Spam Check",
                    style = ClearAirType.label,
                    color = palette.ink2,
                    modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter),
                )
                for (flag in detail.flags) {
                    Text(text = flag, style = ClearAirType.body, color = palette.ink2, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter))
                }
            }
        }
    }
    if (askExplorer) {
        val host = detail.explorerUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: "the explorer"
        ClearAirDialog(
            title = "Open $host in Your Browser?",
            onDismiss = { askExplorer = false },
            actions = {
                ClearAirPrimaryButton(
                    text = "Open",
                    onClick = {
                        askExplorer = false
                        onExplorer()
                    },
                    modifier = Modifier.testTag(TX_EXPLORER_TAG),
                )
                ClearAirTextAction(text = "Cancel", onClick = { askExplorer = false }, modifier = Modifier.align(Alignment.CenterHorizontally))
            },
        )
    }
}

/** What an address reads as on the page: its name, the line under it, and whether the name is a known one that gets a sunk monogram. */
internal data class AddressLine(val name: String, val detail: String, val named: Boolean)

/** The title of the page: the transaction's line, its amount and its ticker, such as "Sent 0.25 MON". The amount is hidden when balances are. */
private fun titleOf(detail: TxDetail, zone: ZoneId, shown: Boolean): String {
    val tx = detail.tx
    val line = ActivityText.line(tx, chain = detail.chain, usd = detail.usd, party = null, zone = zone)
    val coins = PortfolioFormat.coins(BigDecimal(tx.amount).movePointLeft(tx.asset.decimals))
    return "${line.title} ${Masking.amount(coins, hidden = !shown)} ${tx.asset.symbol}"
}

/** The address-book entry of [address], matched by its key, or null when the book has none. */
private fun entryOf(address: String, book: List<AddressBookEntry>): AddressBookEntry? =
    book.firstOrNull { addressKeyOf(it.address) == addressKeyOf(address) }

/** The other side's line: its entry's name, as plain text, when the address book has it, and otherwise "Not in Address Book", with the invitation to add it. */
private fun counterpartyLine(entry: AddressBookEntry?): AddressLine = if (entry != null) {
    AddressLine(name = entry.plainName(), detail = addressBookSourceOf(entry), named = true)
} else {
    AddressLine(name = "Not in Address Book", detail = "Tap to add it on Lensly", named = false)
}

/** The wallet's own account: its name when it has one, and the line that says it is the owner's, with its short address. */
private fun ownLine(address: String, name: String?): AddressLine = AddressLine(
    name = name ?: ActivityText.shortAddress(address),
    detail = "Your account · ${ActivityText.shortAddress(address)}",
    named = true,
)

/**
 * The status hero: the status glyph in a disc, the title with its amount, and under it the time, the status and the value in dollars. A pending item
 * leads its line with the spark and says "Confirming", and a failed one shows its alert in a rose ring.
 */
@Composable
private fun StatusHero(detail: TxDetail, title: String, zone: ZoneId, shown: Boolean) {
    val palette = LocalClearAirPalette.current
    val tx = detail.tx
    val pending = tx.status == TxStatus.PENDING
    val fiat = detail.usd?.takeIf { !tx.network.testnet }?.let { Masking.amount(LocalFiat.current.amount(it), hidden = !shown) }
    val date = dateOf(tx.timestampMs, zone)
    val summary = if (pending) "${statusOf(tx.status)} · $date" else listOfNotNull(date, statusOf(tx.status), fiat).joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDisc(
            glyph = heroGlyph(tx),
            color = heroColor(tx, palette),
            failed = tx.status == TxStatus.FAILED,
            pending = pending,
        )
        Spacer(Modifier.width(ClearAirSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = ClearAirType.titleL, color = palette.ink)
            Row(verticalAlignment = Alignment.Top) {
                if (pending) {
                    SparkMark(side = LINE_SPARK, modifier = Modifier.padding(top = 7.dp))
                    Spacer(Modifier.width(ClearAirSpacing.xs))
                }
                Text(text = summary, style = ClearAirType.body, color = palette.ink2)
            }
        }
    }
}

/** The status disc: a sunk circle with the glyph in it, a rose ring when the transaction failed, and the spark when it is pending. */
@Composable
private fun StatusDisc(glyph: TxGlyph, color: Color, failed: Boolean, pending: Boolean) {
    val palette = LocalClearAirPalette.current
    Box(
        modifier = Modifier
            .size(STATUS_DISC)
            .clip(CircleShape)
            .background(if (failed) Color.Transparent else palette.raised)
            .then(if (failed) Modifier.border(2.dp, palette.rose, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        TxGlyphIcon(glyph = glyph, color = color)
        if (pending) SparkMark(side = STATUS_SPARK, modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp))
    }
}

/** The glyph of the status: a failed transaction is an alert, a settled send is a tick, and every other one is its direction. */
private fun heroGlyph(tx: LumioTx): TxGlyph = when {
    tx.status == TxStatus.FAILED -> TxGlyph.FAILED
    tx.status == TxStatus.CONFIRMED && tx.direction == TxDirection.OUT -> TxGlyph.SETTLED
    else -> glyphOf(tx.direction)
}

/** The colour of the status: rose when it failed, ink while it is pending or moves to the account itself, and vault blue when it settled. */
private fun heroColor(tx: LumioTx, palette: ClearAirPalette): Color = when {
    tx.status == TxStatus.FAILED -> palette.rose
    tx.status == TxStatus.PENDING || tx.direction == TxDirection.SELF -> palette.ink
    else -> palette.vaultBlue
}

/**
 * The check card of the other side of a transaction: an outline in ink around its eyebrow, its name with the line that says how Lumio knows it, and the
 * full address in groups of four. The whole card opens the address's sheet.
 */
@Composable
private fun CheckCard(eyebrow: String, address: String, line: AddressLine, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    val shape = RoundedCornerShape(CARD_CORNER)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, shape = shape, releaseSpring = ClearAirMotion.press, onClick = onClick)
            .clip(shape)
            .background(palette.sheet)
            .border(CARD_BORDER, palette.ink, shape)
            .padding(horizontal = 14.dp, vertical = ClearAirSpacing.xs),
    ) {
        ClearAirEyebrow(text = eyebrow)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Monogram(text = monogramOf(line.name, named = line.named), known = line.named)
            Spacer(Modifier.width(ClearAirSpacing.sm))
            AddressText(line = line, modifier = Modifier.weight(1f))
            ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3)
        }
        ClearAirAddressGrid(address = address, modifier = Modifier.padding(top = ClearAirSpacing.xs, bottom = ClearAirSpacing.xxs))
    }
}

/** A row of an address on the page: its monogram, its name with the line under it, and a chevron, since the row opens the address's sheet. */
@Composable
private fun AddressRow(line: AddressLine, onClick: () -> Unit) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ADDRESS_ROW_MIN_HEIGHT)
            .lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(text = monogramOf(line.name, named = line.named), known = line.named)
        Spacer(Modifier.width(ClearAirSpacing.sm))
        AddressText(line = line, modifier = Modifier.weight(1f))
        ClearAirIcon(glyph = ClearAirGlyph.CHEVRON, color = palette.ink3)
    }
}

/** The name of an address with the line under it, as a column that wraps to a second line rather than clipping. */
@Composable
private fun AddressText(line: AddressLine, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Column(modifier = modifier) {
        Text(text = line.name, style = ClearAirType.rowTitle, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = line.detail, style = ClearAirType.body, color = palette.ink2)
    }
}

/** The details: the network, the fee, and the hash, which copies on tap and says so for a moment. */
@Composable
private fun Details(detail: TxDetail) {
    val palette = LocalClearAirPalette.current
    val copy = rememberCopyAction(value = detail.tx.hash, label = "Transaction Hash")
    KeyValue(label = "Network", value = detail.chain)
    KeyValue(label = "Fee", value = detail.feeText ?: "None")
    detail.tx.blockNumber?.let { KeyValue(label = "Block", value = String.format(Locale.ROOT, "%,d", it)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = copy.copy)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Hash", style = ClearAirType.body, color = palette.ink2, modifier = Modifier.weight(1f))
        Text(
            text = if (copy.copied) "Hash copied" else ActivityText.shortAddress(detail.tx.hash),
            style = ClearAirType.rowValue,
            color = palette.ink,
            textAlign = TextAlign.End,
        )
    }
}

/** One label and its value, as a row of the details. */
@Composable
internal fun KeyValue(label: String, value: String) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = ClearAirType.body, color = palette.ink2, modifier = Modifier.weight(1f))
        Text(text = value, style = ClearAirType.rowValue, color = palette.ink, textAlign = TextAlign.End)
    }
}

/** A small label above a group of addresses. */
@Composable
private fun Sectioned(label: String) {
    ClearAirEyebrow(text = label, modifier = Modifier.padding(start = ClearAirSpacing.gutter, top = ClearAirSpacing.md))
}

/** The initials in a circle: a sunk disc for a known party, and a dashed ring for one that is not in the address book. */
@Composable
internal fun Monogram(text: String, known: Boolean = true, size: Dp = ADDRESS_DISC) {
    val palette = LocalClearAirPalette.current
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        if (known) {
            Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(palette.raised))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 1.6.dp.toPx()
                drawCircle(
                    color = palette.ink3,
                    radius = this.size.minDimension / 2 - stroke / 2,
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP))),
                )
            }
        }
        Text(text = text, style = ClearAirType.label, color = palette.ink)
    }
}

/** The initials of [name]: its first letters, or a question mark for an address that no one has named. */
internal fun monogramOf(name: String, named: Boolean): String {
    if (!named) return "?"
    val words = name.split(' ', '#').filter { it.isNotBlank() }
    return words.take(2).joinToString("") { it.first().uppercase(Locale.ROOT) }.ifEmpty { "?" }
}

/** The date and clock of [epochMs], such as "21 Nov · 14:02" in the wording of the page. */
private fun dateOf(epochMs: Long, zone: ZoneId): String {
    val time = Instant.ofEpochMilli(epochMs).atZone(zone)
    return "${DATE.format(time)} · ${CLOCK.format(time)}"
}

/** The status in words: confirmed, confirming, or failed. */
private fun statusOf(status: TxStatus): String = when (status) {
    TxStatus.CONFIRMED -> "Confirmed"
    TxStatus.PENDING -> "Confirming"
    TxStatus.FAILED -> "Failed"
}

private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private const val DASH = 4f
private const val GAP = 3f
