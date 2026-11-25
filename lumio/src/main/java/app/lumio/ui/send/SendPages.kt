package app.lumio.ui.send

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.evm.SendInput
import app.lumio.receive.monogramOf
import app.lumio.receive.shortAddress
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIcon
import app.lumio.ui.kit.ClearAirMonogram
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirStatusMark
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.ClearAirTone
import app.lumio.ui.shell.LumioPageScreen

/** The five steps of a send, in the order the owner goes through them: Recipient, Amount, Review, Lensly Signs, Send. */
internal const val STEP_RECIPIENT = 0
internal const val STEP_AMOUNT = 1
internal const val STEP_REVIEW = 2
internal const val STEP_SIGN = 3
internal const val STEP_SEND = 4

/** An address the recipient can be picked from: its [title], its [detail] line and the [address] it sends to. */
class SendChoice(val title: String, val detail: String, val address: String)

/** One line of the Review page's facts, under the recipient: its [label], its [value], and whether the value is set in the mono figures. */
class SummaryRow(val label: String, val value: String, val mono: Boolean = false)

/** One option of a choice sheet, such as a coin of the send: its [title] and what it says under it. */
class SendOption(val title: String, val detail: String)

/** A small sheet that lists [options] under [heading]; the owner picks one by its index. */
@Composable
fun SendOptionSheet(visible: Boolean, heading: String, options: List<SendOption>, onPick: (Int) -> Unit, onClose: () -> Unit) {
    ClearAirSheet(visible = visible, onClose = onClose) {
        Column(modifier = Modifier.fillMaxWidth().padding(ClearAirSpacing.lg)) {
            Text(text = heading, style = ClearAirType.titleL, color = LocalClearAirPalette.current.ink)
            options.forEachIndexed { index, option ->
                ClearAirRow(title = option.title, description = option.detail, onClick = { onPick(index) }, divider = index > 0)
            }
        }
    }
}

/** The words of a row's description: the non-blank parts, joined with a middle dot. */
private fun joined(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(" · ")

/** Why [text] is not a recipient the EVM send can pay, or null when it is one. The words are the ones the send refuses with. */
internal fun recipientError(text: String): String? = runCatching { SendInput.recipient(text) }
    .exceptionOrNull()
    ?.let { it.message ?: "Enter a valid recipient address." }

/**
 * The shell every send page shares: the page top bar with a back arrow to [backLabel], the offline band under it when [offline] is set, the page
 * body, and the page's one action in [bottomAction]. The band says which balances are shown; a send needs a connection, so its action is disabled
 * while the band shows (K2).
 */
@Composable
internal fun SendPage(
    title: String,
    backLabel: String,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
    bottomAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    LumioPageScreen(title = title, backLabel = backLabel, offline = offline, onBack = onBack, onLock = onLock) {
        SendPageFrame(bottomAction = bottomAction, content = content)
    }
}

/**
 * The body of a send page: its content scrolls, and the page's one action is held at the foot, under the content, above the navigation bar.
 */
@Composable
private fun SendPageFrame(bottomAction: (@Composable () -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ClearAirSpacing.gutter)
                .padding(top = 6.dp, bottom = ClearAirSpacing.sm),
            content = content,
        )
        bottomAction?.let {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { it() }
        }
    }
}

/** The line above a page's action, such as what to do next. */
@Composable
private fun SendHint(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        style = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
        color = LocalClearAirPalette.current.ink2,
        textAlign = TextAlign.Center,
    )
}

/**
 * The Recipient page, step 1 of 5: one field for an address or a name, with a clear icon while it holds text and a scan icon at its end, then the
 * owner's own accounts and the Address Book, each row showing its address in groups. [error] is the words for a typed value that is not a recipient,
 * and [hint] says what Lumio can tell about it. Continue is disabled until [canContinue], and the hint line says what is missing.
 */
@Composable
fun SendRecipientPage(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    hint: String?,
    canContinue: Boolean,
    accounts: List<SendChoice>,
    addressBook: List<SendChoice>,
    onPick: (SendChoice) -> Unit,
    onScan: () -> Unit,
    onContinue: () -> Unit,
    backLabel: String,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    SendPage(
        title = "Send",
        backLabel = backLabel,
        offline = offline,
        onBack = onBack,
        onLock = onLock,
        bottomAction = {
            SendHint(text = hint ?: "Tap a name, or paste or scan an address.")
            ClearAirPrimaryButton(text = "Continue", onClick = onContinue, enabled = canContinue)
        },
    ) {
        SendStepper(current = STEP_RECIPIENT)
        SendStepLabel(name = "Recipient", number = STEP_RECIPIENT + 1)
        SendHeading(text = "Who Are You Sending To?", top = 18.dp, bottom = 18.dp)
        SendRecipientField(
            label = "Address or Name",
            value = value,
            onValueChange = onValueChange,
            placeholder = "0x…, bc1…, or a name",
            error = error,
            onScan = onScan,
        )
        if (accounts.isNotEmpty()) {
            SendGroupHeading(text = "Your Accounts", top = 22.dp)
            accounts.forEachIndexed { index, choice ->
                ClearAirRow(
                    title = choice.title,
                    description = joined(choice.detail, shortAddress(choice.address)),
                    leading = { ClearAirMonogram(text = monogramOf(choice.title)) },
                    onClick = { onPick(choice) },
                    divider = index > 0,
                    modifier = Modifier.fullBleed(),
                )
            }
        }
        if (addressBook.isNotEmpty()) {
            SendGroupHeading(text = "Address Book", top = ClearAirSpacing.lg, note = "from Lensly")
            addressBook.forEachIndexed { index, choice ->
                ClearAirRow(
                    title = choice.title,
                    description = joined(choice.detail, shortAddress(choice.address)),
                    leading = { ClearAirMonogram(text = monogramOf(choice.title)) },
                    onClick = { onPick(choice) },
                    divider = index > 0,
                    modifier = Modifier.fullBleed(),
                )
            }
        }
    }
}

/**
 * The Amount page, step 2 of 5: the owner's amount set large and typed on the system's decimal keyboard, the To and From accounts, and the network
 * fee, which opens the fee sheet. [leaves] is what stays after the fee, and [amountError] replaces it, so the line under the amount never jumps.
 * Use Max is a text action. [note] says what the app chose for the owner, when it chose anything.
 */
@Composable
fun SendAmountPage(
    symbol: String,
    toTitle: String,
    toDetail: String,
    toAddress: String,
    fromTitle: String,
    fromDetail: String,
    /** The coin the send moves, when the owner can pick among several; the row opens [onAsset]. Null when the coin is chosen for them. */
    asset: String?,
    onAsset: (() -> Unit)?,
    amount: String,
    onAmount: (String) -> Unit,
    amountError: String?,
    leaves: String?,
    onUseMax: (() -> Unit)?,
    networkName: String,
    feeText: String?,
    /** Opens the fee sheet. Null when the chain has no fee to choose, and the fee is then a plain line. */
    onFee: (() -> Unit)?,
    note: String?,
    canReview: Boolean,
    onReview: () -> Unit,
    onChangeRecipient: () -> Unit,
    backLabel: String,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    SendPage(
        title = "Send $symbol",
        backLabel = backLabel,
        offline = offline,
        onBack = onBack,
        onLock = onLock,
        bottomAction = {
            ClearAirPrimaryButton(text = "Review", onClick = onReview, enabled = canReview && offline == null)
            if (offline != null) Text(text = "Sending needs a connection", style = ClearAirType.label, color = palette.ink2)
        },
    ) {
        SendStepper(current = STEP_AMOUNT)
        SendStepLabel(name = "Amount", number = STEP_AMOUNT + 1)
        SendGroupHeading(text = "To", top = ClearAirSpacing.md)
        ClearAirRow(
            title = toTitle,
            description = joined(toDetail, shortAddress(toAddress)),
            leading = { ClearAirMonogram(text = monogramOf(toTitle)) },
            trailing = { ClearAirTextAction(text = "Change", onClick = onChangeRecipient) },
            compact = true,
            modifier = Modifier.fullBleed(),
        )
        SendGroupHeading(text = "From", top = ClearAirSpacing.xs)
        ClearAirRow(
            title = fromTitle,
            description = fromDetail,
            leading = { ClearAirMonogram(text = monogramOf(fromTitle)) },
            compact = true,
            modifier = Modifier.fullBleed(),
        )
        Spacer(Modifier.height(22.dp))
        SendAmountInput(value = amount, onValueChange = onAmount, symbol = symbol, error = amountError)
        amountError?.let {
            ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Failed, modifier = Modifier.padding(top = ClearAirSpacing.xxs))
        } ?: leaves?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.xxs),
                style = ClearAirType.label.copy(fontWeight = FontWeight.Normal),
                color = palette.ink2,
                textAlign = TextAlign.Center,
            )
        }
        onUseMax?.let {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClearAirTextAction(text = "Use Max", onClick = it)
            }
        }
        Spacer(Modifier.height(ClearAirSpacing.xxs))
        SendFactRow(label = "Network", value = networkName, text = true)
        if (asset != null && onAsset != null) SendFactRow(label = "Asset", value = asset, text = true, onClick = onAsset)
        SendFactRow(label = "Network Fee", value = feeText ?: "Reading the network", hairline = false, onClick = onFee)
        note?.let { SendInfoNote(text = it, modifier = Modifier.padding(top = ClearAirSpacing.xs)) }
    }
}

/**
 * The Review page, step 3 of 5, in order of risk: the heading in Lensly's own sentence, then the recipient with the full address in two rows of five
 * groups, then the amount, the most the fee can be, the network and the sender. [warnings] and [firstTime] say what to look at before signing.
 * Show to Lensly is the one primary action; Edit goes back to the Amount page with the send kept.
 */
@Composable
fun SendReviewPage(
    heading: String,
    toTitle: String,
    toDetail: String,
    toAddress: String,
    validLine: String,
    firstTime: String?,
    rows: List<SummaryRow>,
    warnings: List<String>,
    notice: String?,
    onShow: () -> Unit,
    onEdit: () -> Unit,
    backLabel: String,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    SendPage(
        title = "Review",
        backLabel = backLabel,
        offline = offline,
        onBack = onBack,
        onLock = onLock,
        bottomAction = {
            SendHint(text = "Lensly shows this same sentence, address and fee next.")
            ClearAirPrimaryButton(text = "Show to Lensly", onClick = onShow)
            ClearAirTextAction(text = "Edit", onClick = onEdit)
        },
    ) {
        SendStepper(current = STEP_REVIEW)
        SendHeading(text = heading, top = 14.dp, bottom = ClearAirSpacing.sm, small = true)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.sheet)
                .border(1.5.dp, palette.ink, RoundedCornerShape(18.dp))
                .padding(start = 14.dp, top = ClearAirSpacing.sm, end = 14.dp, bottom = 10.dp),
        ) {
            Text(
                text = "TO",
                style = ClearAirType.eyebrow.copy(letterSpacing = 0.09.em),
                color = palette.ink2,
            )
            Row(
                modifier = Modifier.padding(top = ClearAirSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClearAirMonogram(text = monogramOf(toTitle))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = toTitle, style = ClearAirType.titleL.copy(fontSize = 16.sp, lineHeight = 21.sp), color = palette.ink)
                    Text(text = toDetail, style = ClearAirType.label.copy(fontWeight = FontWeight.Normal), color = palette.ink2)
                }
            }
            AddressGrid(address = toAddress, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.xs)) {
                ClearAirIcon(glyph = ClearAirGlyph.CHECK, color = palette.vaultBlue, size = 15.dp)
                Text(text = validLine, style = ClearAirType.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = palette.ink2)
            }
        }
        firstTime?.let {
            Spacer(Modifier.height(ClearAirSpacing.sm))
            ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Pending)
        }
        Spacer(Modifier.height(ClearAirSpacing.xxs))
        rows.forEachIndexed { index, row ->
            val figure = row.label !in TEXT_ROWS
            SendFactRow(
                label = row.label,
                value = row.value,
                text = !figure,
                figureSize = if (index == 0 && figure) 16f else 0f,
                hairline = index < rows.lastIndex,
            )
        }
        warnings.forEach { warning ->
            Spacer(Modifier.height(ClearAirSpacing.xs))
            ClearAirStatusMark(glyph = "!", text = warning, tone = ClearAirTone.Pending)
        }
        notice?.let {
            Spacer(Modifier.height(ClearAirSpacing.sm))
            ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Failed)
        }
    }
}

/** The facts of a review that are words, not figures: the network and the accounts are set in the heading face. */
private val TEXT_ROWS = setOf("Network", "From", "Asset")

/**
 * An address in rows of five groups, the groups of four that every screen breaks an address into, so Lensly and Lumio break it in the same places.
 * The `0x` of an EVM address leads the first row in ink3, and the first and last groups are bold ink, because that is where people compare.
 */
@Composable
internal fun AddressGrid(address: String, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val prefix = if (address.startsWith("0x")) "0x" else ""
    val groups = address.removePrefix(prefix).chunked(ADDRESS_GROUP)
    val style = ClearAirType.rowValue.copy(fontSize = 16.sp, lineHeight = 25.sp)
    Column(modifier = modifier.fillMaxWidth()) {
        groups.chunked(GRID_COLUMNS).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                if (prefix.isNotEmpty()) {
                    Text(text = prefix, style = style, color = if (rowIndex == 0) palette.ink3 else Color.Transparent)
                }
                row.forEachIndexed { column, group ->
                    val index = rowIndex * GRID_COLUMNS + column
                    val edge = index == 0 || index == groups.lastIndex
                    Text(
                        text = group,
                        style = if (edge) style.copy(fontWeight = FontWeight.Bold) else style,
                        color = if (edge) palette.ink else palette.ink2,
                    )
                }
            }
        }
    }
}

private const val ADDRESS_GROUP = 4
private const val GRID_COLUMNS = 5

/**
 * The Show to Lensly page, step 4 of 5: the request as an animated code on a white card, so it reads in either theme, with the words that say the
 * code moves and the brightness rises. [parts] are the frames of the request. Next moves on to the scan of Lensly's reply, and Cancel Send ends it.
 */
@Composable
fun SendShowPage(
    parts: List<String>,
    onNext: () -> Unit,
    onCancel: () -> Unit,
    backLabel: String,
    offline: String?,
    onBack: () -> Unit,
    onLock: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    SendPage(
        title = "Lensly Signs",
        backLabel = backLabel,
        offline = offline,
        onBack = onBack,
        onLock = onLock,
        bottomAction = {
            ClearAirPrimaryButton(text = "Next: Scan Lensly's Reply", onClick = onNext)
            ClearAirTextAction(text = "Cancel Send", onClick = onCancel)
        },
    ) {
        SendStepper(current = STEP_SIGN)
        CrossingLine()
        SendHeading(text = "Show This to Lensly", top = ClearAirSpacing.sm, bottom = 14.dp)
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(ClearAirSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            BrightnessWhileShown()
            AnimatedQr(parts = parts, modifier = Modifier.size(QR_SIZE_DP))
        }
        Text(
            text = "This code moves. Hold steady until Lensly shows the details.",
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            style = ClearAirType.label.copy(fontWeight = FontWeight.Normal),
            color = palette.ink2,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SendMarkIcon(mark = SendMark.SUN, color = palette.ink2, size = 15.dp)
            Text(text = "Brightness raised while the code shows", style = ClearAirType.label.copy(fontWeight = FontWeight.Normal), color = palette.ink2)
        }
    }
}

/** The crossing: Lumio on one side, Lensly on the other, and the spark between them, as the handoff is light that crosses the gap. */
@Composable
private fun CrossingLine() {
    val palette = LocalClearAirPalette.current
    val side = ClearAirType.label.copy(fontSize = 11.5.sp, lineHeight = 21.sp)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SendMarkIcon(mark = SendMark.PHONE, color = palette.ink, size = 18.dp)
        Text(text = "Lumio", style = side, color = palette.ink2)
        Row(modifier = Modifier.weight(1f).padding(horizontal = ClearAirSpacing.xxs), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            repeat(CROSSING_DOTS) { index ->
                if (index == CROSSING_SPARK) {
                    Canvas(Modifier.size(8.dp)) { rotate(45f) { drawRect(color = palette.spark) } }
                } else {
                    Canvas(Modifier.size(3.dp)) { drawCircle(color = palette.ink3) }
                }
            }
        }
        Text(text = "Lensly", style = side, color = palette.ink2)
        SendMarkIcon(mark = SendMark.PHONE, color = palette.ink, size = 18.dp)
    }
}

private const val CROSSING_DOTS = 6
private const val CROSSING_SPARK = 2

private val QR_SIZE_DP = 236.dp

/**
 * The page an opt-in shows while its asset is read from the network, before its review opens. It holds no action; [notice] is why the read failed,
 * and it is empty while the read runs.
 */
@Composable
fun SendReadingPage(title: String, notice: String?, backLabel: String, onBack: () -> Unit, onLock: () -> Unit) {
    val palette = LocalClearAirPalette.current
    SendPage(title = title, backLabel = backLabel, offline = null, onBack = onBack, onLock = onLock) {
        Text(text = notice ?: "Reading the network", style = ClearAirType.body, color = palette.ink2)
    }
}
