package app.lumio.ui.transactions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lensly.app.AddressBookEntry
import app.lensly.app.addressBookEntryFor
import app.lensly.app.addressKeyOf
import app.lensly.app.chainOfAddress
import app.lensly.ui.components.groupAddress
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxStatus
import app.lumio.home.Masking
import app.lumio.portfolio.PortfolioFormat
import app.lumio.transactions.ActivityText
import app.lumio.transactions.TxDetail
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirTestnetHatch
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.shell.LumioPageScreen
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** The test tag of the explorer action, which asks before it opens a browser. */
const val TX_EXPLORER_TAG = "tx-explorer"

/** How long the hash says it was copied. */
private const val COPIED_MS = 1500L

/** The size of the status disc, and of the disc that an address sits in. */
private val STATUS_DISC = 56.dp
private val ADDRESS_DISC = 40.dp

/**
 * The transaction page: its status, its two sides, its details and its hash. Every address on the page is tappable and opens its address-book sheet, so
 * a counterparty is named the same way everywhere. [accountNames] names the wallet's own accounts, and [book] is the wallet's address book, both by the
 * address key. The page is read only: names are made on Lensly.
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
    var askExplorer by remember { mutableStateOf(false) }
    LumioPageScreen(title = "Transaction", backLabel = backLabel, offline = null, onBack = onBack, onLock = onLock) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = ClearAirSpacing.xxl)) {
            if (tx.network.testnet) ClearAirTestnetHatch(text = "TESTNET · TEST FUNDS HAVE NO VALUE")
            StatusHero(detail = detail, zone = zone, shown = showBalance)
            val counterparty = tx.counterparty
            if (counterparty != null) {
                val incoming = tx.direction == TxDirection.IN
                Sectioned(label = if (incoming) "From" else "To")
                AddressCard(
                    address = counterparty,
                    name = book.firstOrNull { addressKeyOf(it.address) == addressKeyOf(counterparty) }?.name,
                    book = book,
                    onClick = { onAddress(counterparty) },
                )
            }
            Sectioned(label = if (tx.direction == TxDirection.IN) "To" else "From")
            AddressCard(
                address = tx.account,
                name = accountNames[addressKeyOf(tx.account)],
                book = book,
                own = true,
                onClick = { onAddress(tx.account) },
            )
            tx.asset.contract?.let { contract ->
                Sectioned(label = "Token Contract")
                AddressCard(
                    address = contract,
                    name = tx.asset.name,
                    book = book,
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
                    text = "Flagged by the spam check",
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
        AlertDialog(
            onDismissRequest = { askExplorer = false },
            text = { Text(text = "Open $host in Your Browser?", style = ClearAirType.body) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag(TX_EXPLORER_TAG), onClick = { askExplorer = false; onExplorer() }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { askExplorer = false }) { Text("Cancel") } },
        )
    }
}

/** The status hero: the direction in a disc, the title with its amount, and the time, the status and the value in dollars. */
@Composable
private fun StatusHero(detail: TxDetail, zone: ZoneId, shown: Boolean) {
    val palette = LocalClearAirPalette.current
    val tx = detail.tx
    val line = ActivityText.line(tx, chain = detail.chain, usd = detail.usd, party = null, zone = zone)
    val coins = PortfolioFormat.coins(BigDecimal(tx.amount).movePointLeft(tx.asset.decimals))
    val title = "${line.title} ${Masking.amount(coins, hidden = !shown)} ${tx.asset.symbol}"
    val fiat = detail.usd?.takeIf { !tx.network.testnet }?.let { Masking.amount(PortfolioFormat.usd(it), hidden = !shown) }
    val summary = listOfNotNull(dateOf(tx.timestampMs, zone), statusOf(tx.status), fiat).joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(STATUS_DISC).clip(CircleShape).background(palette.raised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (tx.direction) {
                    TxDirection.IN -> TxIcons.In
                    TxDirection.OUT -> TxIcons.Out
                    TxDirection.SELF -> TxIcons.Self
                },
                contentDescription = null,
                tint = if (line.incoming) palette.vaultBlue else palette.ink,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(ClearAirSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = ClearAirType.titleL, color = palette.ink)
            Text(text = summary, style = ClearAirType.body, color = palette.ink2)
        }
    }
}

/** A row of an address on the page: its monogram, its name or "Not in address book", and under it the full address in groups of four. */
@Composable
private fun AddressCard(address: String, name: String?, book: List<AddressBookEntry>, onClick: () -> Unit, own: Boolean = false) {
    val palette = LocalClearAirPalette.current
    val chain = chainOfAddress(address)
    val known = chain?.let { addressBookEntryFor(address, it, book) }
    val title = name ?: known?.name ?: if (own) ActivityText.shortAddress(address) else "Not in Address Book"
    val detail = when {
        own -> "Your account · ${ActivityText.shortAddress(address)}"
        known != null -> "Address book · from Lensly"
        else -> "Not in address book"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(text = monogramOf(title, known != null || own))
        Spacer(Modifier.width(ClearAirSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = ClearAirType.rowTitle, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = detail, style = ClearAirType.body, color = palette.ink2)
        }
    }
    Text(
        text = groupAddress(address),
        style = ClearAirType.address,
        color = palette.ink2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xxs),
    )
}

/** The details: the network, the fee, and the hash, which copies on tap and says so for a moment. */
@Composable
private fun Details(detail: TxDetail) {
    val palette = LocalClearAirPalette.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    KeyValue(label = "Network", value = detail.chain)
    KeyValue(label = "Fee", value = detail.feeText ?: "None")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(detail.tx.hash))
                copied = true
            }
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Hash", style = ClearAirType.body, color = palette.ink2, modifier = Modifier.weight(1f))
        Text(
            text = if (copied) "Hash copied" else ActivityText.shortAddress(detail.tx.hash),
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
internal fun Monogram(text: String, known: Boolean = true) {
    val palette = LocalClearAirPalette.current
    Box(modifier = Modifier.size(ADDRESS_DISC), contentAlignment = Alignment.Center) {
        if (known) {
            Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(palette.raised))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 1.6.dp.toPx()
                drawCircle(
                    color = palette.ink3,
                    radius = size.minDimension / 2 - stroke / 2,
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
