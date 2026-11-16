package app.lensly.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.LenslyScreen
import app.lensly.ui.theme.LenslySpacing

/** Placeholder for the pair and sign screens, which are not built yet. */
@Composable
fun SoonScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LenslyScreen(title = "Pair and sign", modifier = modifier, onBack = onBack, scrollable = false) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "NOT BUILT YET",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        DotHeadline(text = "SOON")
    }
}
