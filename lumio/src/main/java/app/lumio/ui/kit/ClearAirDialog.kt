package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The corner of a dialog card, as the spec draws the cards that sit over a page. */
private val DIALOG_CORNER = 24.dp

/**
 * A Clear Air dialog: a card over the page with its [title], a [text] line under it when it has one, and the [actions] below, which are one capsule and one
 * text action. It closes on [onDismiss], which a tap outside the card or the system back also reaches.
 */
@Composable
fun ClearAirDialog(title: String, text: String? = null, onDismiss: () -> Unit, actions: @Composable ColumnScope.() -> Unit) {
    val palette = LocalClearAirPalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DIALOG_CORNER))
                .background(palette.sheet)
                .padding(ClearAirSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(ClearAirSpacing.sm),
        ) {
            Text(text = title, style = ClearAirType.rowTitle, color = palette.ink)
            if (text != null) Text(text = text, style = ClearAirType.body, color = palette.ink2)
            actions()
        }
    }
}
