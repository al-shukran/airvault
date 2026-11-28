package app.lensly.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.airvault.core.model.AddressGroups
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalMonoFamily

/** The test tag of the address text, so a test can long-press it. */
internal const val ADDRESS_TEXT_TAG = "address-text"

/** The test tag of the Ethereum logo before the address, so a test can find it. */
internal const val ADDRESS_ICON_TAG = "address-icon"

/** What a long press and the copy button are called for assistive technology. */
internal const val COPY_ADDRESS_LABEL = "Copy Address"

/** The short confirmation after a copy. */
internal const val ADDRESS_COPIED = "Address copied"

/**
 * An address the owner can copy. [address] is the full address and it is what gets copied, so a copy is whole even when [shown] prints
 * the address shortened or grouped. The copy button and a long press on the text copy it to the clipboard, and a short toast confirms
 * it. [icon] puts the Ethereum logo before the address, which every EVM chain shares; it is off by default, so no other chain shows that
 * logo. [onClick], when set, runs on a tap of the text, for an address that sits in a row which is itself tapped. The clip is marked
 * sensitive and cleared after a minute, as every copy in Lensly is.
 */
@Composable
fun AddressText(
    address: String,
    modifier: Modifier = Modifier,
    shown: String = groupAddress(address),
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current),
    color: Color = MaterialTheme.colorScheme.onSurface,
    icon: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon) {
            AddressIcon(size = 16.dp, modifier = Modifier.testTag(ADDRESS_ICON_TAG))
            Spacer(Modifier.width(LenslySpacing.xs))
        }
        Text(
            text = shown,
            style = style,
            color = color,
            textAlign = textAlign,
            modifier = Modifier
                .weight(1f)
                .testTag(ADDRESS_TEXT_TAG)
                .combinedClickable(
                    role = if (onClick != null) Role.Button else null,
                    onClick = onClick ?: {},
                    onLongClickLabel = COPY_ADDRESS_LABEL,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        copyAddress(context, address)
                    },
                ),
        )
        IconButton(onClick = { copyAddress(context, address) }) {
            Icon(
                imageVector = LenslyIcons.Copy,
                contentDescription = COPY_ADDRESS_LABEL,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * [address] in groups of four characters separated by spaces. A `0x` prefix stays in front of the first group and is not counted in
 * the groups, so an EVM address reads `0x1a2B 3c4D …`. Only the display changes: the full address is kept, and text wraps only at the
 * spaces.
 */
fun groupAddress(address: String): String = AddressGroups.group(address)

/** Copies [address] through [copyToClipboard], then confirms with a short toast. */
internal fun copyAddress(context: Context, address: String) {
    copyToClipboard(context, "Address", address)
    Toast.makeText(context, ADDRESS_COPIED, Toast.LENGTH_SHORT).show()
}
