package app.lensly.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.lensly.ui.theme.LenslySpacing

/**
 * A raised block for a value the owner reads or copies, such as a hash, a key or an address. It sits one step up from the
 * page, with the standard padding and radius. [modifier] can add a tap action.
 */
@Composable
internal fun WalletPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(LenslySpacing.md),
        verticalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
        content = content,
    )
}
