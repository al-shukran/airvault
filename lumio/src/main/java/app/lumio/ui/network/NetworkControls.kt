package app.lumio.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.LenslySegmentedControl
import app.lensly.ui.theme.LenslySpacing
import app.lumio.network.NetworkMode
import app.lumio.ui.theme.LocalLumioPalette

/** The test tag of the testnet strip. */
const val TESTNET_BANNER_TAG = "testnet-banner"

/** The height of the testnet strip, under the status bar (section 2). */
private val STRIP_HEIGHT = 28.dp

/** The label of a side: "Mainnet" or "Testnet". */
fun NetworkMode.label(): String = when (this) {
    NetworkMode.MAINNET -> "Mainnet"
    NetworkMode.TESTNET -> "Testnet"
}

/** What a side holds, in one line, for the side sheet. */
private fun NetworkMode.summary(): String = when (this) {
    NetworkMode.MAINNET -> "Your balances on the main networks."
    NetworkMode.TESTNET -> "Test networks, with test coins that have no value."
}

/**
 * The strip under the status bar while testnet is on (section 2): 28 dp, the testnet colour at 14 %, a dot and "Testnet" in the testnet colour, so a
 * testnet balance is never mistaken for a mainnet one. Tapping it opens [NetworkSideSheet].
 */
@Composable
fun TestnetStrip(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val testnet = LocalLumioPalette.current.testnet
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .background(testnet.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(TESTNET_BANNER_TAG),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(testnet))
        Spacer(Modifier.width(LenslySpacing.xs))
        Text(text = "Testnet", style = MaterialTheme.typography.labelMedium, color = testnet)
    }
}

/**
 * The side sheet that the strip opens (section 2): Mainnet and Testnet as radio rows, each with a line on what its side holds. A choice applies at once,
 * through [onSelect], which also closes the sheet, so there is no Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSideSheet(mode: NetworkMode, onSelect: (NetworkMode) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(start = LenslySpacing.md, end = LenslySpacing.md, bottom = LenslySpacing.xl)) {
            Text(text = "Network", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NetworkMode.entries.forEach { side ->
                SideRow(side = side, selected = side == mode, onClick = { onSelect(side) })
            }
        }
    }
}

/** One side as a radio row: its label and its line, with the radio on the right. */
@Composable
private fun SideRow(side: NetworkMode, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = side.label(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = side.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

/**
 * The Mainnet and Testnet side of the networks, as one segmented control. The side that is on is the selected segment. Choosing the other side shows
 * only its accounts and balances.
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
