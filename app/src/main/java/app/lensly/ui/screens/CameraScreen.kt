package app.lensly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.DotHeadline
import app.lensly.ui.components.RoundKey
import app.lensly.ui.components.RoundKeyVariant
import app.lensly.ui.theme.LenslyBlack

private data class DemoKey(
    val label: String,
    val sublabel: String?,
    val variant: RoundKeyVariant = RoundKeyVariant.Normal,
)

private val demoKeys = listOf(
    DemoKey("1", null),
    DemoKey("2", "ABC"),
    DemoKey("3", "DEF"),
    DemoKey("4", "GHI"),
    DemoKey("5", "JKL"),
    DemoKey("6", "MNO"),
    DemoKey("7", "PQRS"),
    DemoKey("8", "TUV"),
    DemoKey("9", "WXYZ"),
    DemoKey("*", null, RoundKeyVariant.Outline),
    DemoKey("0", "+"),
    DemoKey("#", null, RoundKeyVariant.Accent),
)

/** Placeholder start screen. The real camera UI replaces this later. */
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LenslyBlack)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DotHeadline(text = "LENSLY")
        Spacer(modifier = Modifier.height(40.dp))
        demoKeys.chunked(3).forEach { rowKeys ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                rowKeys.forEach { key ->
                    RoundKey(
                        label = key.label,
                        sublabel = key.sublabel,
                        onClick = {},
                        variant = key.variant,
                    )
                }
            }
        }
    }
}
