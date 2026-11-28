package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.airvault.core.model.ChainId
import app.lensly.app.ADDRESS_NOTES_MAX_LENGTH
import app.lensly.app.AddressBookEntry
import app.lensly.app.AddressNotes
import app.lensly.app.EVM_FAMILY
import app.lensly.app.headline
import app.lensly.app.isAddressValue
import app.lensly.app.lookalikeWarningText
import app.lensly.app.plainName
import app.lensly.app.plainNameOf
import app.lensly.app.plainNameOrNull
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.LetterMonogram
import app.lensly.ui.components.RowArrow
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalLumenPalette
import app.lensly.ui.theme.LocalMonoFamily

// The review building blocks that the Bitcoin and the transaction reviews share, so both read alike. A review screen is a
// composition of these: the header, the summary sentence and its key/value list, one risk banner for every
// warning, the collapsible details, and the bottom actions. Body text is sans. Mono is for amounts, addresses, hashes and data.

/** One row of a summary's key/value list. [mono] is set for an amount or data. A plain word such as "Unknown" is set in sans. */
internal data class ReviewFact(val label: String, val value: String, val mono: Boolean = true)

/** A check the owner ticks inside a risk banner before the review can be signed. */
internal data class RiskCheck(val label: String, val checked: Boolean, val onCheckedChange: (Boolean) -> Unit)

/** One warning in the risk banner: its text, its severity, and the check it needs, when it needs one. */
internal data class RiskItem(val text: String, val tone: BannerTone, val check: RiskCheck? = null)

/**
 * The header of a review: the network, named in full and large, with its raw [chainDetail] under it and the testnet badge in the warning
 * colour when [testnet] is set. Under it come the [networkFact] row, when a review has one, then the account that signs and its wallet, and
 * the address of that account in full, beside [addressLogo], the logo the review's other addresses show. [networkIcon] is the logo of
 * the network, when it has one.
 */
@Composable
internal fun ReviewHeader(
    network: String,
    testnet: Boolean,
    chainDetail: String?,
    account: String,
    wallet: String?,
    address: String,
    addressLogo: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    networkIcon: (@Composable () -> Unit)? = null,
    networkFact: ReviewFact? = null,
    accountLabel: String = "Account",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            networkIcon?.let {
                it()
                Spacer(Modifier.width(LenslySpacing.xs))
            }
            Text(
                text = network,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = if (testnet) LocalLumenPalette.current.warning else MaterialTheme.colorScheme.onSurface,
            )
            if (testnet) {
                Spacer(Modifier.width(LenslySpacing.xs))
                ReviewChip(text = "Testnet", tone = LocalLumenPalette.current.warning)
            }
        }
        chainDetail?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(LenslySpacing.sm))
        networkFact?.let { ReviewFactRow(label = it.label, value = it.value, mono = it.mono) }
        ReviewFactRow(label = accountLabel, value = account, mono = false)
        wallet?.let { ReviewFactRow(label = "Wallet", value = it, mono = false) }
        Spacer(Modifier.height(LenslySpacing.xxs))
        Text(text = "Address", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            addressLogo()
            Spacer(Modifier.width(LenslySpacing.xs))
            AddressText(address = address, icon = false, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * A chip with the look of PillChip: an outline and its text in the small label face. [tone] colours the outline and the text, and
 * null keeps the page's own colours.
 */
@Composable
internal fun ReviewChip(text: String, tone: Color? = null, leading: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .border(1.dp, tone ?: colors.outline, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(LenslySpacing.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tone ?: colors.onSurface,
        )
    }
}

/** How far a row's drawing may reach past its sides: a glyph of normal text can overhang its box by a pixel or two. */
private val ROW_SIDE_OVERHANG = 16.dp

/**
 * Clips what a row draws to its own height, so a text with stacked combining marks cannot cover the row above or below it. The sides are left
 * open by [ROW_SIDE_OVERHANG], since a clip at the exact width would cut the last pixel of an overhanging glyph of a normal text.
 */
private fun Modifier.clipToRow(): Modifier = drawWithContent {
    val overhang = ROW_SIDE_OVERHANG.toPx()
    clipRect(left = -overhang, right = size.width + overhang) { this@drawWithContent.drawContent() }
}

/**
 * The summary of a review: one large sentence that names the verb, the amount and the counterparty, with the [icon] of the amount
 * beside it when there is one, then the key/value list.
 */
@Composable
internal fun ReviewSummary(
    sentence: AnnotatedString,
    facts: List<ReviewFact>,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().clipToRow()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                it()
                Spacer(Modifier.width(LenslySpacing.sm))
            }
            Text(
                text = sentence,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(LenslySpacing.md))
        facts.forEach { fact -> ReviewFactRow(label = fact.label, value = fact.value, mono = fact.mono) }
    }
}

/**
 * One fact: its label in sans on the left, and its value on the right, in mono when [mono] is set. [valueColor] colours the value, such
 * as a name, and null keeps the text colour.
 */
@Composable
internal fun ReviewFactRow(label: String, value: String, mono: Boolean = true, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clipToRow()
            .padding(vertical = LenslySpacing.xxs),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current) else MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The tap of an address row of a review on [chain]: a tap calls [onOpenAddress] with the address and the chain, so the Address Book opens
 * it. Null when the review has no tap, and its rows are then shown without one.
 */
internal fun addressTapOn(chain: ChainId, onOpenAddress: ((String, ChainId) -> Unit)?): ((String) -> Unit)? =
    onOpenAddress?.let { tap -> { address -> tap(address, chain) } }

/**
 * One argument or field of a review: its label above, and its value below. An address is shown with its names, and with whether the
 * Address Book holds it, through [ReviewAddress]. Any other value is in mono, since it is data. [isAddress] says the value is an address,
 * [lines] are the notes of the row, in sans under the value.
 */
@Composable
internal fun ReviewField(
    label: String,
    value: String,
    logo: @Composable () -> Unit,
    notes: AddressNotes? = null,
    modifier: Modifier = Modifier,
    onOpen: ((String) -> Unit)? = null,
    isAddress: Boolean = isAddressValue(value),
    lines: List<String> = emptyList(),
) {
    Column(modifier = modifier
        .fillMaxWidth()
        .clipToRow()
        .padding(vertical = LenslySpacing.xxs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.xxs))
        if (isAddress) {
            ReviewAddress(address = value, notes = notes, logo = logo, onClick = onOpen?.let { open -> { open(value) } })
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        lines.forEach { NoteText(it) }
    }
}

/** A message the owner reads whole: its label above it, and its text in the monospace face on a raised block, so each character is checkable. */
@Composable
internal fun MessageBlock(label: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().clipToRow().padding(vertical = LenslySpacing.xxs)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(LenslySpacing.xxs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(LenslySpacing.sm),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A label with no value under it, such as the name of a struct that the rows below it belong to. */
@Composable
internal fun ReviewHeading(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier.fillMaxWidth().clipToRow().padding(vertical = LenslySpacing.xxs),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One numbered step: its number in the muted colour, then its sentence, which wraps when it must. */
@Composable
internal fun ReviewStep(number: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().clipToRow().padding(bottom = LenslySpacing.sm)) {
        Text(text = "$number.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(LenslySpacing.sm))
        Text(
            text = keepAddressesWhole(text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private val GROUPED_ADDRESS = Regex("0x[0-9A-Fa-f]{4}(?: (?:[0-9A-Fa-f]{4}|…)){1,9}(?![0-9A-Fa-f])")

/** [text] with the groups of each address in it joined by no-break spaces, so a line breaks before an address and never inside it. */
internal fun keepAddressesWhole(text: String): String = GROUPED_ADDRESS.replace(text) { it.value.replace(' ', '\u00A0') }

/**
 * The one banner for every warning a review holds. Its colour is the highest severity among its rows, and its title counts them. A
 * row's check sits inside that row, under its text. With no warnings it shows a quiet green line instead.
 */
@Composable
internal fun RiskBanner(items: List<RiskItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = LenslySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = LocalLumenPalette.current.positive, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(LenslySpacing.xs))
            Text(text = "No warnings", style = MaterialTheme.typography.bodyMedium, color = LocalLumenPalette.current.positive)
        }
        return
    }
    val color = if (items.maxOf { it.tone } == BannerTone.Error) MaterialTheme.colorScheme.error else LocalLumenPalette.current.warning
    val count = items.size
    val title = if (count == 1) "1 warning — review before signing" else "$count warnings — review before signing"
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), shape)
            .padding(LenslySpacing.sm),
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(LenslySpacing.xs))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs)) {
                Text(text = keepAddressesWhole(item.text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                item.check?.let { check ->
                    AckCheckbox(label = check.label, checked = check.checked, onCheckedChange = check.onCheckedChange)
                }
            }
        }
    }
}

/**
 * An acknowledgement: a standard Material checkbox with a plain sentence beside it. The whole row is the target, so a tap on the
 * sentence ticks the box too.
 */
@Composable
internal fun AckCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberKeyHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .tapHaptic(haptics)
            .ignoreObscuredTouches()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(LenslySpacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A section of the details. Its content is always shown, so every field of a review is on screen on first render, and the owner reads
 * each one. The section has a title and a divider under it.
 */
@Composable
internal fun ReviewSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.padding(vertical = LenslySpacing.xs),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = LenslySpacing.sm), content = content)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The bottom of a review: the line that says what still stands between the owner and signing, the primary Hold to sign control at
 * full width, and Reject as a text button under it. The line keeps a minimum height when it is empty, so the buttons do not move, and
 * it wraps at large font sizes.
 */
@Composable
internal fun ReviewActions(
    hint: String?,
    approvable: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberKeyHaptics()
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LenslySpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        HoldToSignButton(enabled = approvable, onSign = onApprove, modifier = Modifier.fillMaxWidth())
        TextButton(
            onClick = onReject,
            modifier = Modifier
                .fillMaxWidth()
                .tapHaptic(haptics),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Text(text = "Reject", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/**
 * An address with what the Address Book and the vault's accounts say of it. The bold line above the address is [headline]: an account of
 * this device takes it, and the full address follows. The line is coloured by the role of its name, see [nameRoleOf]. Under it: the wallet
 * of an account of another wallet, the Address Book name when an account holds the headline, the address-book label and notes, or "Not in
 * address book" when the Address Book holds no entry for it. The Address Book is read for every counterparty, so a miss is shown, and it
 * is not a warning.
 *
 * [beforeSigning] is false in the Activity detail of a record already signed: the first-time and lookalike lines are for a review that still
 * has a decision to make, so they are left out there.
 *
 * With [onClick] the address is a list row: a tap opens it, and a chevron shows that it does. It is the one address component every review
 * and the Activity detail share.
 */
@Composable
internal fun ReviewAddress(
    address: String,
    notes: AddressNotes?,
    logo: @Composable () -> Unit = { LetterMonogram(letter = "?", size = 16.dp) },
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    beforeSigning: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        notes?.headline()?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = nameRoleOf(notes)?.colourIn(LocalLumenPalette.current) ?: MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(LenslySpacing.xxs))
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            logo()
            Spacer(Modifier.width(LenslySpacing.xs))
            AddressText(address = address, icon = false, modifier = Modifier.weight(1f), onClick = onClick)
            if (onClick != null) RowArrow()
        }
        AddressNoteLines(notes = notes, beforeSigning = beforeSigning)
    }
}

/**
 * The notes of an address that are not its bold line, in sans: the wallet of an account of another wallet, the Address Book name when an
 * account holds the bold line, the Address Book label and notes, and "Not in address book" when the Address Book holds no entry for it.
 * Before signing, an address in no entry and no account also gets the first-time line and the lookalike warning.
 */
@Composable
internal fun AddressNoteLines(notes: AddressNotes?, modifier: Modifier = Modifier, beforeSigning: Boolean = true) {
    Column(modifier = modifier.fillMaxWidth()) {
        plainNameOrNull(notes?.internal?.walletName)?.let { ReviewFactRow(label = "Wallet", value = it, mono = false) }
        val entry = notes?.entry
        if (entry == null) {
            NoteText(NOT_IN_ADDRESS_BOOK)
            if (beforeSigning && notes?.internal == null) {
                NoteText(FIRST_TIME_CHECK)
                notes?.lookalike?.let { LookalikeWarning(it) }
            }
        } else {
            if (notes.internal != null) {
                ReviewFactRow(
                    label = "Address Name",
                    value = entry.plainName(),
                    mono = false,
                    valueColor = NameRole.ACCOUNT.colourIn(LocalLumenPalette.current),
                )
            }
            plainNameOrNull(entry.label)?.let { ReviewFactRow(label = "Address Label", value = it, mono = false) }
            plainNameOf(entry.notes.orEmpty(), ADDRESS_NOTES_MAX_LENGTH).takeIf { it.isNotEmpty() }?.let { ReviewFactRow(label = "Address Notes", value = it, mono = false) }
            if (entry.chain?.namespace == EVM_FAMILY.namespace) NoteText(SAVED_FOR_ALL_EVM)
        }
    }
}

/**
 * The warning of an address that is not saved but looks like [saved]: the same first and last 4 characters. It names the saved entry and
 * shows its full address, so the two can be compared. The address above it is never given the entry's name.
 */
@Composable
private fun LookalikeWarning(saved: AddressBookEntry) {
    val color = LocalLumenPalette.current.warning
    Spacer(Modifier.height(LenslySpacing.xs))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(LenslySpacing.xs))
        Text(
            text = lookalikeWarningText(saved),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = color,
        )
    }
    Spacer(Modifier.height(LenslySpacing.xxs))
    AddressText(address = saved.address, icon = false)
}

/** The line under an address that is in no Address Book entry and no account of this device. */
internal const val FIRST_TIME_CHECK = "First time. Check every group."

/** The line under an address that a saved EVM entry names. An EVM address is the same account on every EVM network. */
internal const val SAVED_FOR_ALL_EVM = "Saved for all EVM networks"

/** The line a counterparty shows when the Address Book holds no entry for it. It is plain text, not a warning. */
internal const val NOT_IN_ADDRESS_BOOK = "Not in address book"

/**
 * The name an address goes by in a sentence: the name of this device's account when it is one, else its Address Book name. Null when
 * neither gives one.
 */
internal fun AddressNotes.shownName(): String? =
    internal?.plainName() ?: if (internal == null) plainNameOrNull(entry?.name) else null

/** Appends [text] in the mono face, for an amount or an address inside a sentence. */
internal fun AnnotatedString.Builder.appendMono(text: String, mono: FontFamily) {
    withStyle(SpanStyle(fontFamily = mono)) { append(text) }
}

/** Addresses longer than this are shortened in a sentence. */
private const val SHORT_ADDRESS_LIMIT = 16

/** An address shortened to its first and last characters, for a sentence with no room for the full one. */
internal fun shortAddress(address: String): String =
    if (address.length <= SHORT_ADDRESS_LIMIT) address else "${address.take(10)}...${address.takeLast(6)}"
