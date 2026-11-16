package app.lensly.ui.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.app.AddressNotes
import app.lensly.ui.theme.LenslyPillShape
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslySuccess

/**
 * What the Address Book and the device's accounts say of an address, under the address. Every part that is there shows, so an address that
 * is both an account of this device and an entry shows both. The account part starts with the Internal Account cue.
 */
@Composable
internal fun AddressNotesBlock(notes: AddressNotes, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = LenslySpacing.xs)) {
        notes.internal?.let { account ->
            InternalAccountCue()
            Spacer(Modifier.height(LenslySpacing.xs))
            account.walletName?.let { LabelledValue(label = "Wallet", value = it) }
            account.displayName?.let { LabelledValue(label = "Display Name", value = it) }
            LabelledValue(label = "Auto-Name", value = account.autoName)
            account.label?.let { LabelledValue(label = "Label", value = it) }
        }
        notes.entry?.let { entry ->
            LabelledValue(label = "Address Name", value = entry.name)
            entry.label?.let { LabelledValue(label = "Address Label", value = it) }
        }
    }
}

/** The cue that an address is one of this device's accounts: a pill in the success colour. */
@Composable
internal fun InternalAccountCue(modifier: Modifier = Modifier) {
    Text(
        text = "INTERNAL ACCOUNT",
        style = MaterialTheme.typography.labelMedium,
        color = LenslySuccess,
        modifier = modifier
            .clip(LenslyPillShape)
            .background(LenslySuccess.copy(alpha = 0.14f), LenslyPillShape)
            .border(1.dp, LenslySuccess, LenslyPillShape)
            .padding(horizontal = LenslySpacing.sm, vertical = LenslySpacing.xxs),
    )
}
