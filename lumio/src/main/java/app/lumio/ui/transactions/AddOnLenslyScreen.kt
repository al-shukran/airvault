package app.lumio.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.groupAddress
import app.lensly.ui.qr.AnimatedQr
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirPrimaryButton
import app.lumio.ui.shell.LumioPageScreen

/** The side of the code. It is drawn on white, so it reads the same in both themes. */
private val CODE_SIZE = 196.dp

/** The space around the code, which keeps its quiet zone white. */
private val CODE_PAD = 12.dp

/**
 * The handoff page: a code that holds the public address, and three steps for Lensly to read it. It is a full page pushed from an address sheet, so
 * there is never a second sheet over the first. [edit] changes the first step to updating an existing entry, and [onDone] returns to the transaction.
 */
@Composable
fun AddOnLenslyScreen(
    address: String,
    edit: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onDone: () -> Unit,
) {
    val palette = LocalClearAirPalette.current
    LumioPageScreen(
        title = if (edit) "Edit on Lensly" else "Add on Lensly",
        backLabel = backLabel,
        offline = null,
        onBack = onBack,
        onLock = onLock,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Show This Address to Lensly",
                style = ClearAirType.titleXL,
                color = palette.ink,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(ClearAirSpacing.md))
            Step(number = 1, text = if (edit) "On Lensly, open the entry and choose Update by Scanning." else "On Lensly, open Address Book and choose Add by Scanning.")
            Step(number = 2, text = "Hold Lensly over this code, then name the entry there.")
            Step(number = 3, text = "Re-pair Lumio to see the new name here.")
            Spacer(Modifier.height(ClearAirSpacing.lg))
            Box(
                modifier = Modifier.size(CODE_SIZE + CODE_PAD * 2).background(Color.White).padding(CODE_PAD),
            ) {
                AnimatedQr(parts = listOf(address), modifier = Modifier.fillMaxSize(), keepCase = true)
            }
            Spacer(Modifier.height(ClearAirSpacing.md))
            Text(
                text = groupAddress(address),
                style = ClearAirType.address,
                color = palette.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(ClearAirSpacing.sm))
            Text(
                text = "This code holds only the public address.",
                style = ClearAirType.body,
                color = palette.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(ClearAirSpacing.lg))
            ClearAirPrimaryButton(text = "Done", onClick = onDone)
        }
    }
}

/** One numbered step of the handoff: its number, and what to do in Lensly. */
@Composable
private fun Step(number: Int, text: String) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ClearAirSpacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ClearAirSpacing.sm),
    ) {
        Text(text = "$number", style = ClearAirType.rowValue, color = palette.ink2)
        Text(text = text, style = ClearAirType.body, color = palette.ink, modifier = Modifier.weight(1f))
    }
}
