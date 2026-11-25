package app.lumio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** A line of facts under a title: its [label] in ink2 at the start, its [value] in bold ink at the end, and a hairline under it unless it is [last]. */
@Composable
internal fun SettingsFact(label: String, value: String, last: Boolean = false) {
    val palette = LocalClearAirPalette.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ClearAirSpacing.gutter)) {
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = ClearAirType.body, color = palette.ink2)
            Text(text = value, style = ClearAirType.rowTitle, color = palette.ink, textAlign = TextAlign.End)
        }
        if (!last) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.hairline))
    }
}
