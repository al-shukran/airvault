package app.lumio.ui.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.airvault.core.model.AddressGroups
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import org.web3j.crypto.Keys
import kotlin.math.roundToInt

/** The address in a row, as the project's address style: mono 13.5/20, no tracking. It is the copy that a field makes of the address style. */
internal val ADDRESS_LINE_STYLE: TextStyle = ClearAirType.address.copy(fontSize = 13.5.sp, lineHeight = 20.sp, letterSpacing = 0.sp)

/** The test tag of the address text in [AddressLine]. */
const val ADDRESS_LINE_TAG = "address-line"

/** The gap between a row's header band and the address line under it. */
internal val ADDRESS_GAP = 2.dp

/** The top and bottom padding of a row. */
private val ROW_PADDING = 8.dp

private const val NO_BREAK = '\u00A0'
private const val ELLIPSIS = "…"

/**
 * [address] as a row writes it: [AddressGroups.ends], the groups and the ellipsis joined by no-break spaces, so the only place where a line may break is
 * after the ellipsis. A group is never split and the address has no line limit. An EVM address is in its EIP-55 checksum case, whatever case it was
 * stored in.
 */
internal fun addressLineText(address: String): String = unbroken(AddressGroups.ends(checksummed(address)))

/** A transaction hash as a row writes it: the groups of [addressLineText], with the hash's own case. */
internal fun hashLineText(hash: String): String = unbroken(AddressGroups.ends(hash))

private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

private fun checksummed(address: String): String = if (EVM_ADDRESS.matches(address)) Keys.toChecksumAddress(address) else address

private fun unbroken(ends: String): String = ends.replace(' ', NO_BREAK).replace("$ELLIPSIS$NO_BREAK", "$ELLIPSIS ")

/**
 * What a screen reader says of [address]: [lead], then each group spelled out character by character and the groups set off by commas, with the ellipsis
 * read as "to", as in "address 0 x F 2 7 8, c F 5 9, to f 2 5 C, 1 c d b". A null [lead] leaves it out.
 */
internal fun spokenAddress(address: String, lead: String? = "address "): AnnotatedString = spoken(AddressGroups.ends(checksummed(address)), lead)

/** What a screen reader says of a transaction [hash]: its groups verbatim, as [spokenAddress] says an address, with no lead. */
internal fun spokenHash(hash: String): AnnotatedString = spoken(AddressGroups.ends(hash), lead = null)

/**
 * What a screen reader says of a token [name] that is an unlisted mint written by [addressLineText]: its groups verbatim, as [spokenAddress] says an
 * address, with no lead. Null for any other name, which is read as it is written.
 */
internal fun spokenMintName(name: String): AnnotatedString? =
    if (name.contains(ELLIPSIS) && name.contains(NO_BREAK)) spoken(name.replace(NO_BREAK, ' '), lead = null) else null

private fun spoken(ends: String, lead: String?): AnnotatedString = buildAnnotatedString {
    lead?.let(::append)
    val parts = ends.split(' ')
    parts.forEachIndexed { index, part ->
        if (part == ELLIPSIS) {
            append("to ")
        } else {
            withVerbatim(part)
            if (index < parts.lastIndex) append(", ")
        }
    }
}

private fun AnnotatedString.Builder.withVerbatim(text: String) {
    pushTtsAnnotation(VerbatimTtsAnnotation(text))
    append(text)
    pop()
}

/**
 * The address line of a row: [address] as [addressLineText] writes it, in the address style, in [color] with its ellipsis in [ellipsisColor]. A [word]
 * such as "From" leads it, in [wordStyle], and the address is set on the word's baseline; when the address takes a second line, that line starts under
 * the first line's address and not under the word. The row stays one stop for a screen reader: the address says its groups verbatim after [lead].
 */
@Composable
internal fun AddressLine(
    address: String,
    modifier: Modifier = Modifier,
    color: Color = LocalClearAirPalette.current.ink2,
    ellipsisColor: Color = color,
    word: String? = null,
    wordStyle: TextStyle = ClearAirType.body,
    lead: String? = "address ",
) {
    val shown = addressLineText(address)
    val text = buildAnnotatedString {
        val cut = shown.indexOf(ELLIPSIS)
        if (cut < 0 || ellipsisColor == color) {
            append(shown)
        } else {
            append(shown.substring(0, cut))
            withStyle(SpanStyle(color = ellipsisColor)) { append(ELLIPSIS) }
            append(shown.substring(cut + 1))
        }
    }
    Row(modifier = modifier) {
        if (word != null) {
            Text(text = word, style = wordStyle, color = LocalClearAirPalette.current.ink2, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(with(LocalDensity.current) { (wordStyle.fontSize.value * WORD_GAP_EM).sp.toDp() }))
        }
        Text(
            text = text,
            style = ADDRESS_LINE_STYLE,
            color = color,
            modifier = Modifier
                .weight(1f, fill = false)
                .alignByBaseline()
                .testTag(ADDRESS_LINE_TAG)
                .clearAndSetSemantics { this.text = spokenAddress(address, lead) },
        )
    }
}

/** The gap after a direction word, as a share of the word's size. */
private const val WORD_GAP_EM = 0.3f

/**
 * A list row: [content] in a row of at least [minHeight], padded 8 dp above and below and by the gutter at the sides. With a [footerAddress], led by a
 * [footerWord] such as "From", the row is a [HeaderWithAddress] instead, whose band is [content]. [modifier] comes before the row's height and [press] after it, so a press scales the whole row.
 */
@Composable
internal fun AddressRow(
    minHeight: Dp,
    addressInset: Dp,
    footerAddress: String?,
    modifier: Modifier = Modifier,
    press: Modifier = Modifier,
    footerWord: String? = null,
    footerWordStyle: TextStyle = ClearAirType.body,
    content: @Composable RowScope.() -> Unit,
) {
    if (footerAddress != null) {
        HeaderWithAddress(
            minHeight = minHeight,
            addressInset = addressInset,
            modifier = modifier.fillMaxWidth().then(press).padding(horizontal = ClearAirSpacing.gutter),
            header = { Row(verticalAlignment = Alignment.CenterVertically, content = content) },
            address = { AddressLine(address = footerAddress, word = footerWord, wordStyle = footerWordStyle) },
        )
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .then(press)
                .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * A row's header band with its [address] under it. The band sits where a row of [minHeight] puts it: centred in the height less its 8 dp of padding,
 * and never closer than 8 dp to the top. The address starts [addressInset] in, 2 dp under the band, and runs to the right edge; 8 dp of padding follows it.
 * The caller sets the row's horizontal padding. [header] and [address] each emit one layout.
 */
@Composable
internal fun HeaderWithAddress(
    minHeight: Dp,
    addressInset: Dp,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    address: @Composable () -> Unit,
) {
    Layout(content = { header(); address() }, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val inset = addressInset.roundToPx()
        val band = measurables[0].measure(loose.copy(minWidth = constraints.maxWidth))
        val line = measurables[1].measure(loose.copy(maxWidth = (constraints.maxWidth - inset).coerceAtLeast(0)))
        val padding = ROW_PADDING.roundToPx()
        val top = maxOf(padding, ((minHeight.toPx() - 2 * ROW_PADDING.toPx() - band.height) / 2 + ROW_PADDING.toPx()).roundToInt())
        val gap = ADDRESS_GAP.roundToPx()
        layout(constraints.maxWidth, top + band.height + gap + line.height + padding) {
            band.place(0, top)
            line.place(inset, top + band.height + gap)
        }
    }
}
