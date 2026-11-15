package app.lensly.ui.components

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily

/** The test tag of the address text, so a test can long-press it. */
internal const val ADDRESS_TEXT_TAG = "address-text"

/** What a long press and the copy button are called for assistive technology. */
internal const val COPY_ADDRESS_LABEL = "Copy address"

/** The short confirmation after a copy. */
internal const val ADDRESS_COPIED = "Address copied"

/**
 * An address the owner can copy. [address] is the full address and it is what gets copied, so a copy is whole even when [shown] prints
 * the address shortened or grouped. The copy button and a long press on the text copy it to the clipboard, and a short toast confirms
 * it. [icon] puts the chain's logo before the address: the Bitcoin logo when [bitcoin], else the Ethereum logo, which every EVM
 * chain shares. [onClick], when set, runs on a tap of the text, for an address that sits in a
 * row which is itself tapped. The clip is not marked sensitive, because an address is public.
 */
@Composable
fun AddressText(
    address: String,
    modifier: Modifier = Modifier,
    shown: String = groupAddress(address),
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
    color: Color = MaterialTheme.colorScheme.onSurface,
    icon: Boolean = true,
    bitcoin: Boolean = false,
    textAlign: TextAlign = TextAlign.Start,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon) {
            if (bitcoin) BitcoinIcon(size = 16.dp) else AddressIcon(size = 16.dp)
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

/** [address] in groups of four characters separated by spaces. The full address is kept, and text wraps only at the spaces. */
fun groupAddress(address: String): String = address.chunked(4).joinToString(" ")

/**
 * Puts [address] on the clipboard as plain text, then confirms with a short toast. The clip is not marked sensitive: an address is
 * public, and a sensitive clip would hide it from the clipboard preview.
 */
internal fun copyAddress(context: Context, address: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Address", address))
    Toast.makeText(context, ADDRESS_COPIED, Toast.LENGTH_SHORT).show()
}
