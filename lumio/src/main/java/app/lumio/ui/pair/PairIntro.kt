package app.lumio.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.ui.kit.ClearAirPrimaryButton

/**
 * The Pair with Lensly intro, the empty state of Wallets: three numbered steps in the owner's words, and the one primary capsule that starts the scan.
 */
@Composable
fun PairIntro(onScan: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = ClearAirSpacing.md),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(text = "Pair with Lensly", style = ClearAirType.titleXL, color = palette.ink)
            Spacer(Modifier.height(ClearAirSpacing.md))
            IntroStep(number = 1, text = "On Lensly, open Pair and choose the wallet to share.")
            IntroStep(number = 2, text = "Hold Lensly's code inside the frame.")
            IntroStep(number = 3, text = "Lumio reads public addresses only. Your keys stay on Lensly.")
        }
        Spacer(Modifier.height(ClearAirSpacing.md))
        ClearAirPrimaryButton(text = "Scan Pairing Code", onClick = onScan)
    }
}

/** One numbered step of the intro: the number in ink-3, and the words beside it. */
@Composable
private fun IntroStep(number: Int, text: String) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ClearAirSpacing.xxs),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = "$number", style = ClearAirType.rowTitle, color = palette.ink3)
        Spacer(Modifier.width(ClearAirSpacing.md))
        Text(text = text, style = ClearAirType.body, color = palette.ink)
    }
}
