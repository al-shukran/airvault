package app.lumio.ui.send

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.groupAddress
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.evm.SendInput
import app.lumio.receive.monogramOf
import app.lumio.ui.kit.ClearAirEyebrow
import app.lumio.ui.kit.ClearAirField
import app.lumio.ui.kit.ClearAirGlyph
import app.lumio.ui.kit.ClearAirIconButton
import app.lumio.ui.kit.ClearAirMonogram
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSheet
import app.lumio.ui.kit.ClearAirStatusMark
import app.lumio.ui.kit.ClearAirTextAction
import app.lumio.ui.kit.ClearAirTone
import app.lumio.ui.kit.StepThread
import app.lumio.ui.kit.rejectOf
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

/** The rows of the addresses a recipient can be picked from: a monogram, the name, its detail and short address, and a chevron. */
@Composable
private fun ChoiceRows(choices: List<SendChoice>, onPick: (SendChoice) -> Unit) {
    choices.forEachIndexed { index, choice ->
        ClearAirRow(
            title = choice.title,
            description = joined(choice.detail, shortOf(choice.address)),
            leading = { ClearAirMonogram(text = monogramOf(choice.title)) },
            onClick = { onPick(choice) },
            divider = index > 0,
            modifier = Modifier.fullBleed(),
        )
    }
}

/** Lays the content out across the gutter of the send page, so a list row is full-bleed as every list row is. */
private fun Modifier.fullBleed(): Modifier = layout { measurable, constraints ->
    val gutter = ClearAirSpacing.gutter.roundToPx()
    val width = constraints.maxWidth + 2 * gutter
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
}

/** An address as a recipient row shows it: its first six and last four characters. */
private fun shortOf(address: String): String = if (address.length <= SHORT_ADDRESS_KEEP) address else "${address.take(6)}…${address.takeLast(4)}"

private const val SHORT_ADDRESS_KEEP = 14

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
                .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
            content = content,
        )
        bottomAction?.let {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm)) { it() }
        }
    }
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
            ClearAirPrimaryButton(text = "Continue", onClick = onContinue, enabled = canContinue)
        },
    ) {
        StepThread(current = STEP_RECIPIENT, steps = 5)
        Spacer(Modifier.height(ClearAirSpacing.sm))
        Text(text = "Recipient · 1 of 5", style = ClearAirType.label, color = palette.ink2)
        Text(text = "Who Are You Sending To?", style = ClearAirType.titleXL, color = palette.ink)
        Spacer(Modifier.height(ClearAirSpacing.md))
        ClearAirField(
            label = "Address or Name",
            value = value,
            onValueChange = onValueChange,
            placeholder = "0x…, bc1…, or a name",
            error = error,
            monospace = true,
            trailing = {
                if (value.isNotEmpty()) ClearAirIconButton(glyph = ClearAirGlyph.CLOSE, label = "Clear", onClick = { onValueChange("") })
                ClearAirIconButton(glyph = ClearAirGlyph.SCAN, label = "Scan", onClick = onScan)
            },
        )
        Spacer(Modifier.height(ClearAirSpacing.xs))
        Text(
            text = hint ?: "Tap a name, or scan an address.",
            style = ClearAirType.body,
            color = palette.ink2,
        )
        if (accounts.isNotEmpty()) {
            ClearAirEyebrow(text = "Your Accounts", modifier = Modifier.padding(top = ClearAirSpacing.lg))
            ChoiceRows(choices = accounts, onPick = onPick)
        }
        if (addressBook.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = ClearAirSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                ClearAirEyebrow(text = "Address Book", modifier = Modifier.weight(1f))
                Text(text = "from Lensly", style = ClearAirType.body, color = palette.ink2)
            }
            ChoiceRows(choices = addressBook, onPick = onPick)
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
        StepThread(current = STEP_AMOUNT, steps = 5)
        Spacer(Modifier.height(ClearAirSpacing.sm))
        Text(text = "Amount · 2 of 5", style = ClearAirType.label, color = palette.ink2)
        ClearAirEyebrow(text = "To", modifier = Modifier.padding(top = ClearAirSpacing.xs))
        ClearAirRow(
            title = toTitle,
            description = joined(toDetail, groupAddress(toAddress)),
            trailing = { ClearAirTextAction(text = "Change", onClick = onChangeRecipient) },
        )
        ClearAirEyebrow(text = "From", modifier = Modifier.padding(top = ClearAirSpacing.xs))
        ClearAirRow(title = fromTitle, description = fromDetail)
        if (asset != null && onAsset != null) {
            ClearAirRow(
                title = "Asset",
                trailing = { Text(text = asset, style = ClearAirType.rowValue, color = palette.ink) },
                onClick = onAsset,
            )
        }
        Spacer(Modifier.height(ClearAirSpacing.lg))
        AmountInput(value = amount, onValueChange = onAmount, symbol = symbol, error = amountError)
        amountError?.let {
            ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Failed)
        } ?: leaves?.let {
            Text(text = it, style = ClearAirType.body, color = palette.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        onUseMax?.let {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClearAirTextAction(text = "Use Max", onClick = it)
            }
        }
        Spacer(Modifier.height(ClearAirSpacing.md))
        ClearAirRow(
            title = "Network",
            trailing = { Text(text = networkName, style = ClearAirType.rowValue, color = palette.ink) },
        )
        ClearAirRow(
            title = "Network Fee",
            trailing = { Text(text = feeText ?: "Reading the network", style = ClearAirType.rowValue, color = palette.ink) },
            onClick = onFee,
        )
        note?.let {
            Spacer(Modifier.height(ClearAirSpacing.sm))
            Text(text = it, style = ClearAirType.body, color = palette.ink2)
        }
    }
}

/**
 * The amount as a large figure in the owner's chosen decimal keyboard, with its [symbol] after it. There is no keypad and no preset amount.
 */
@Composable
private fun AmountInput(value: String, onValueChange: (String) -> Unit, symbol: String, error: String?) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().then(rejectOf(failed = error != null)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f, fill = false),
            singleLine = true,
            textStyle = ClearAirType.amountLarge.copy(color = palette.ink, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(palette.spark),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(text = "0", style = ClearAirType.amountLarge, color = palette.ink3)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(ClearAirSpacing.xs))
        Text(text = symbol, style = ClearAirType.titleL, color = palette.ink2)
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
            ClearAirPrimaryButton(text = "Show to Lensly", onClick = onShow)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClearAirTextAction(text = "Edit", onClick = onEdit)
            }
        },
    ) {
        StepThread(current = STEP_REVIEW, steps = 5)
        Spacer(Modifier.height(ClearAirSpacing.sm))
        Text(text = heading, style = ClearAirType.titleXL, color = palette.ink)
        Spacer(Modifier.height(ClearAirSpacing.md))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, palette.ink, RoundedCornerShape(ClearAirSpacing.md))
                .padding(ClearAirSpacing.md),
        ) {
            ClearAirEyebrow(text = "To")
            Text(text = toTitle, style = ClearAirType.rowTitle, color = palette.ink)
            Text(text = toDetail, style = ClearAirType.body, color = palette.ink2)
            Spacer(Modifier.height(ClearAirSpacing.xs))
            AddressGrid(address = toAddress)
            Spacer(Modifier.height(ClearAirSpacing.xs))
            ClearAirStatusMark(glyph = "✓", text = validLine, tone = ClearAirTone.Settled)
        }
        firstTime?.let {
            Spacer(Modifier.height(ClearAirSpacing.sm))
            ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Pending)
        }
        Spacer(Modifier.height(ClearAirSpacing.md))
        rows.forEach { row ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = ClearAirSpacing.xxs)) {
                Text(text = row.label, style = ClearAirType.label, color = palette.ink2)
                Text(text = row.value, style = if (row.mono) ClearAirType.address else ClearAirType.rowValue, color = palette.ink)
            }
        }
        warnings.forEach { warning ->
            Spacer(Modifier.height(ClearAirSpacing.xs))
            ClearAirStatusMark(glyph = "!", text = warning, tone = ClearAirTone.Pending)
        }
        notice?.let {
            Spacer(Modifier.height(ClearAirSpacing.sm))
            ClearAirStatusMark(glyph = "!", text = it, tone = ClearAirTone.Failed)
        }
        Spacer(Modifier.height(ClearAirSpacing.sm))
        Text(text = "Lensly shows this same sentence, address and fee next.", style = ClearAirType.body, color = palette.ink2)
    }
}

/**
 * An address in two rows of five groups, the groups from [groupAddress] so every screen breaks the address in the same places. The first and last
 * groups are bold, because that is where people compare.
 */
@Composable
internal fun AddressGrid(address: String, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    val groups = groupAddress(address).split(' ')
    Column(modifier = modifier.fillMaxWidth()) {
        groups.chunked(GRID_COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = ClearAirSpacing.xxs)) {
                row.forEach { group ->
                    val edge = group == groups.first() || group == groups.last()
                    Text(
                        text = group,
                        modifier = Modifier.weight(1f),
                        style = if (edge) ClearAirType.rowValue.copy(fontWeight = FontWeight.Bold) else ClearAirType.address,
                        color = palette.ink,
                    )
                }
            }
        }
    }
}

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
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClearAirTextAction(text = "Cancel Send", onClick = onCancel)
            }
        },
    ) {
        StepThread(current = STEP_SIGN, steps = 5)
        Spacer(Modifier.height(ClearAirSpacing.sm))
        Text(text = "Show This to Lensly", style = ClearAirType.titleXL, color = palette.ink)
        Spacer(Modifier.height(ClearAirSpacing.md))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ClearAirSpacing.md))
                .background(Color.White)
                .padding(ClearAirSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            BrightnessWhileShown()
            AnimatedQr(parts = parts, modifier = Modifier.size(QR_SIZE_DP))
        }
        Spacer(Modifier.height(ClearAirSpacing.md))
        Text(
            text = "This code moves. Hold steady until Lensly shows the details.",
            style = ClearAirType.body,
            color = palette.ink2,
        )
        Text(text = "Brightness raised while the code shows", style = ClearAirType.label, color = palette.ink3)
    }
}

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
