package app.lensly.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslySpacing

/** Placeholder for the pair and sign screens, which are not built yet. */
@Composable
fun SoonScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = LenslySpacing.md),
    ) {
        Spacer(Modifier.height(LenslySpacing.lg))
        Text(
            text = "NOT BUILT YET",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(LenslySpacing.sm))
        DotHeadline(text = "SOON")

        Spacer(Modifier.weight(1f))

        PillButton(
            text = "Back",
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(LenslySpacing.md))
    }
}
