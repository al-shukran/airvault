package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.lensly.app.AddressNotes
import app.lensly.ui.components.AddressText
import app.lensly.ui.components.BannerTone
import app.lensly.ui.components.PillChip
import app.lensly.ui.components.rememberKeyHaptics
import app.lensly.ui.components.tapHaptic
import app.lensly.ui.theme.LenslyError
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslyRadius
import app.lensly.ui.theme.LenslySansFamily
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess
import app.lensly.ui.theme.LenslyWarning
import app.lensly.ui.theme.SpaceMonoFamily

// The review building blocks that the Bitcoin and the transaction reviews share, so both read alike. A review screen is a
// composition of these: the chips under the header, the summary sentence and its key/value list, one risk banner for every
// warning, the collapsible details, and the bottom actions. Body text is sans. Mono is for amounts, addresses, hashes and data.

/** One row of a summary's key/value list. [mono] is set for an amount or data. A plain word such as "Unknown" is set in sans. */
internal data class ReviewFact(val label: String, val value: String, val mono: Boolean = true)

/** A check the owner ticks inside a risk banner before the review can be signed. */
internal data class RiskCheck(val label: String, val checked: Boolean, val onCheckedChange: (Boolean) -> Unit)

/** One warning in the risk banner: its text, its severity, and the check it needs, when it needs one. */
internal data class RiskItem(val text: String, val tone: BannerTone, val check: RiskCheck? = null)

/**
 * The chip row under the header: the network and the account that signs. A testnet's network is drawn in the warning colour, so a
 * test network cannot pass for the main one.
 */
@Composable
internal fun ReviewChips(network: String, testnet: Boolean, account: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs)) {
        if (testnet) NetworkBadge(text = network) else PillChip(text = network)
        PillChip(text = account)
    }
}

/** The testnet badge: a [PillChip] in the warning colour. */
@Composable
private fun NetworkBadge(text: String) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .border(1.dp, LenslyWarning, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LenslyWarning)
    }
}

/** The summary of a review: one large sentence that names the verb, the amount and the counterparty, then the key/value list. */
@Composable
internal fun ReviewSummary(sentence: AnnotatedString, facts: List<ReviewFact>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = sentence,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = LenslySansFamily, fontWeight = FontWeight.Normal),
            color = LenslyOnSurface,
        )
        Spacer(Modifier.height(LenslySpacing.md))
        facts.forEach { fact -> ReviewFactRow(label = fact.label, value = fact.value, mono = fact.mono) }
    }
}

/** One fact: its label in sans on the left, and its value on the right, in mono when [mono] is set. */
@Composable
internal fun ReviewFactRow(label: String, value: String, mono: Boolean = true, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
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
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily) else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

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
            Icon(Icons.Filled.Check, contentDescription = null, tint = LenslySuccess, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(LenslySpacing.xs))
            Text(text = "No warnings", style = MaterialTheme.typography.bodyMedium, color = LenslySuccess)
        }
        return
    }
    val color = if (items.maxOf { it.tone } == BannerTone.Error) LenslyError else LenslyWarning
    val count = items.size
    val title = if (count == 1) "1 warning — review before signing" else "$count warnings — review before signing"
    val shape = RoundedCornerShape(LenslyRadius.md)
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
                color = LenslyOnSurface,
            )
        }
        items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(LenslySpacing.xxs)) {
                Text(text = item.text, style = MaterialTheme.typography.bodyMedium, color = LenslyOnSurface)
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
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(LenslySpacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LenslyOnSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A collapsible section of the details. It starts collapsed, so the summary and the risks are what the eye reads first, and it opens
 * on a tap of its title row. A review screen keys its details to the review, so a new review starts collapsed again.
 */
@Composable
internal fun DetailsSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClick = { open = !open }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = LenslyOnSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = LenslySpacing.sm), content = content)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The bottom of a review: the line that says what still stands between the owner and signing, the primary Hold to sign control at
 * full width, and Reject as a text button under it. The line keeps its height when it is empty, so the buttons do not move.
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
                .height(LenslySpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
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
 * An address with what the Address Book and the device's accounts say of it. The name it goes by sits above the address, and the
 * notes that are not a name sit under it, so the owner reads who it is before the characters.
 */
@Composable
internal fun ReviewAddress(address: String, notes: AddressNotes?, bitcoin: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        notes?.shownName()?.let { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = LenslyOnSurface,
            )
            Spacer(Modifier.height(LenslySpacing.xxs))
        }
        AddressText(address = address, bitcoin = bitcoin)
        notes?.let { AddressNoteLines(notes = it) }
    }
}

/** The notes of an address that are not its name, in sans: what this device's account is, its wallet, and any Address Book label. */
@Composable
internal fun AddressNoteLines(notes: AddressNotes, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        notes.internal?.let { account ->
            NoteText("An account of this device")
            account.walletName?.let { ReviewFactRow(label = "Wallet", value = it, mono = false) }
            account.label?.let { ReviewFactRow(label = "Label", value = it, mono = false) }
        }
        notes.entry?.label?.let { ReviewFactRow(label = "Address label", value = it, mono = false) }
    }
}

/**
 * The name an address goes by: its Address Book name, or else the name of this device's account. Null when neither gives one.
 */
internal fun AddressNotes.shownName(): String? = entry?.name ?: internal?.let { account -> account.displayName ?: account.autoName }

/** Appends [text] in the mono face, for an amount or an address inside a sentence. */
internal fun AnnotatedString.Builder.appendMono(text: String) {
    withStyle(SpanStyle(fontFamily = SpaceMonoFamily)) { append(text) }
}
