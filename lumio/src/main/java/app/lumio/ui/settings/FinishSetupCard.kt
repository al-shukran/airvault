package app.lumio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette
import app.lumio.data.config.FinishItem
import app.lumio.data.config.FinishSetup
import app.lumio.ui.kit.ClearAirRow
import app.lumio.ui.kit.ClearAirSegments

/** The title of a checklist item, in CMOS title case like every label. */
internal val FinishItem.title: String
    get() = when (this) {
        FinishItem.DURESS -> "Set a Duress Password"
        FinishItem.DEVICE_PROTECTION -> "Enable Device Protection"
        FinishItem.PAIR -> "Pair with Lensly"
    }

/** The Settings page where an item is done: the duress password and device protection are in Security, and pairing is in Wallets. */
internal val FinishItem.page: SettingsPage
    get() = when (this) {
        FinishItem.DURESS, FinishItem.DEVICE_PROTECTION -> SettingsPage.SECURITY
        FinishItem.PAIR -> SettingsPage.WALLETS
    }

/**
 * The "Finish Setting Up" checklist, the card at the top of Settings. It shows how many items are ticked, a segment for each item, and a row for
 * each item still open, which opens the page where that item is done. [LumioSettingsScreen] shows it only until every item is ticked.
 */
@Composable
internal fun FinishSetupCard(setup: FinishSetup, onOpen: (FinishItem) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.raised)
                .padding(horizontal = ClearAirSpacing.md, vertical = ClearAirSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Finish Setting Up", style = ClearAirType.rowTitle, color = palette.ink)
                Text(text = "${setup.done.size} of ${FinishItem.entries.size}", style = ClearAirType.rowValue, color = palette.ink2)
            }
            ClearAirSegments(
                fractions = FinishItem.entries.map { item -> if (item in setup.done) 1f else 0f },
                modifier = Modifier.padding(vertical = ClearAirSpacing.xs),
            )
            setup.open.forEach { item -> ClearAirRow(title = item.title, onClick = { onOpen(item) }) }
        }
    }
}
