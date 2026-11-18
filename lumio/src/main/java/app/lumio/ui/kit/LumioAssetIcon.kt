package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.LocalLumenPalette

/**
 * The frame of an asset's logo (section 1.4): the art in a 40 dp circle on surfaceContainerHigh, with a 1 dp outlineVariant halo. A testnet account's
 * icon has a 12 dp testnet dot at its bottom right.
 */
@Composable
fun LumioAssetIcon(modifier: Modifier = Modifier, testnet: Boolean = false, content: @Composable () -> Unit) {
    Box(modifier = modifier.size(40.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        if (testnet) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(LocalLumenPalette.current.testnet),
            )
        }
    }
}
