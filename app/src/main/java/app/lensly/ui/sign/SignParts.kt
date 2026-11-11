package app.lensly.ui.sign

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.AddressIcon
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.SpaceMonoFamily

/** One fact: a muted uppercase label above its value, with a divider underneath. */
@Composable
internal fun FactRow(
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LenslySpacing.sm),
    ) {
        MutedText(label.uppercase())
        Spacer(Modifier.height(LenslySpacing.xs))
        content()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/**
 * An address in the monospace face, so each character can be compared by eye, with the Ethereum logo beside it. Every
 * address shows that logo, whichever chain it is used on. The full address is kept, in groups of four.
 */
@Composable
internal fun AddressText(address: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AddressIcon(size = 16.dp)
        Spacer(Modifier.width(LenslySpacing.xs))
        Text(
            text = groupAddress(address),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Text in the monospace face, such as a selector or a hex word. It wraps and is never cut short. */
@Composable
internal fun MonoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceMonoFamily),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** A sentence of explanation, such as a fee or amount note. It uses the sans body face, which reads better than the tracked label face. */
@Composable
internal fun NoteText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun MutedText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
