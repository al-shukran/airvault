package app.lensly.ui.sign

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LocalMonoFamily

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
        MutedText(label)
        Spacer(Modifier.height(LenslySpacing.xs))
        content()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Text in the monospace face, such as a selector or a hex word. It wraps and is never cut short. */
@Composable
internal fun MonoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LocalMonoFamily.current),
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
