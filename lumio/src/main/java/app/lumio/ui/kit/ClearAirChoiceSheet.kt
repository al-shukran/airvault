package app.lumio.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/**
 * One choice of a [ClearAirChoiceSheet]: its [title], the line under it, the value at its end, whether it is the current choice, and whether it can be
 * picked. [onPick] applies it, and the caller closes the sheet.
 */
class ChoiceRow(
    val title: String,
    val onPick: () -> Unit,
    val detail: String? = null,
    val trailing: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

/** The choices under one [label], or with no label. */
class ChoiceGroup(val label: String?, val rows: List<ChoiceRow>)

/**
 * The content of a short radio sheet: a [title], then its groups of radio rows. A disabled row keeps its reason in full-contrast text, and only its title
 * dims and its radio turns dashed. It is the content of a [ClearAirSheet], which the caller opens and closes.
 */
@Composable
fun ClearAirChoiceSheet(title: String, groups: List<ChoiceGroup>) {
    val palette = LocalClearAirPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = ClearAirSpacing.lg),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
            style = ClearAirType.titleL,
            color = palette.ink,
        )
        for (group in groups) {
            group.label?.let { ClearAirEyebrow(text = it, modifier = Modifier.padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.xs)) }
            for (row in group.rows) ChoiceRowItem(row)
        }
    }
}

@Composable
private fun ChoiceRowItem(row: ChoiceRow) {
    val palette = LocalClearAirPalette.current
    val titleColor = if (row.enabled) palette.ink else palette.ink.copy(alpha = DISABLED_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clickable(enabled = row.enabled, role = Role.RadioButton, onClick = row.onPick)
            .padding(horizontal = ClearAirSpacing.gutter, vertical = ClearAirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.title, style = ClearAirType.rowTitle, color = titleColor)
            row.detail?.let { Text(text = it, style = ClearAirType.caption, color = palette.ink2) }
        }
        row.trailing?.let {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            Text(text = it, style = ClearAirType.rowValue, color = palette.ink, textAlign = TextAlign.End)
        }
        Spacer(Modifier.width(ClearAirSpacing.sm))
        ClearAirRadio(selected = row.selected, enabled = row.enabled)
    }
}

/**
 * A radio control: a ring with a filled dot when [selected]. A disabled radio is dashed, so the control reads as unavailable without a second colour.
 */
@Composable
fun ClearAirRadio(selected: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val palette = LocalClearAirPalette.current
    Canvas(modifier.size(RADIO_SIZE)) {
        val stroke = RING_WIDTH.toPx()
        val radius = size.minDimension / 2 - stroke / 2
        val dash = if (enabled) null else PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF))
        drawCircle(color = if (selected) palette.ink else palette.ink3, radius = radius, style = Stroke(width = stroke, pathEffect = dash))
        if (selected) drawCircle(color = palette.ink, radius = DOT_RADIUS.toPx())
    }
}

/** The radio as the mock draws it: 22 dp with a 2 dp ring, and a dot of radius 5 dp. */
private val RADIO_SIZE = 22.dp
private val RING_WIDTH = 2.dp
private val DOT_RADIUS = 5.dp
private const val DASH_ON = 4f
private const val DASH_OFF = 3f
private const val DISABLED_ALPHA = 0.38f
