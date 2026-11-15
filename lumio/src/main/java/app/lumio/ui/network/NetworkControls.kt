package app.lumio.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import app.lensly.ui.components.PillButton
import app.lensly.ui.theme.LenslyBlack
import app.lensly.ui.theme.LenslySpacing
import app.lensly.ui.theme.LenslyWarning
import app.lumio.network.NetworkMode

/** The test tag of the testnet banner. */
const val TESTNET_BANNER_TAG = "testnet-banner"

/** The label of a side: "Mainnet" or "Testnet". */
fun NetworkMode.label(): String = when (this) {
    NetworkMode.MAINNET -> "Mainnet"
    NetworkMode.TESTNET -> "Testnet"
}

/**
 * The band over every Lumio screen while testnet mode is on. It fills the top edge, under the status bar, in the theme's warning colour, so a
 * testnet balance is never mistaken for a mainnet one.
 */
@Composable
fun TestnetBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(LenslyWarning)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xs)
            .testTag(TESTNET_BANNER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "TESTNET", style = MaterialTheme.typography.labelLarge, color = LenslyBlack)
    }
}

/** The Mainnet and Testnet switch at the top of Home. The side that is on is in the accent colour and reads as selected. */
@Composable
fun NetworkSwitch(mode: NetworkMode, onChange: (NetworkMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LenslySpacing.xs),
    ) {
        NetworkMode.entries.forEach { option ->
            PillButton(
                text = option.label(),
                onClick = { onChange(option) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { selected = option == mode },
                accent = option == mode,
            )
        }
    }
}
