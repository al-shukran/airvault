package app.lumio.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import app.lensly.ui.components.LenslySegmentedControl
import app.lensly.ui.theme.LenslySpacing
import app.lumio.network.NetworkMode
import app.lensly.ui.theme.LocalLumenPalette

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
            .background(LocalLumenPalette.current.warning)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = LenslySpacing.md, vertical = LenslySpacing.xs)
            .testTag(TESTNET_BANNER_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Testnet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.background)
    }
}

/**
 * The Mainnet and Testnet side of the networks, as one segmented control under the Home top bar. The side that is on is the selected
 * segment. Choosing the other side shows only its accounts and balances.
 */
@Composable
fun NetworkSwitch(mode: NetworkMode, onChange: (NetworkMode) -> Unit, modifier: Modifier = Modifier) {
    LenslySegmentedControl(
        options = NetworkMode.entries,
        selected = mode,
        label = { it.label() },
        onSelect = onChange,
        modifier = modifier,
    )
}
