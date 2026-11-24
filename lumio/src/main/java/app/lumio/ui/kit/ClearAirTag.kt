package app.lumio.ui.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** A status word beside a title, such as ACTIVE: capitals in the mono face, in the spark's text colour, inside a hairline border. */
@Composable
fun ClearAirTag(text: String, modifier: Modifier = Modifier) {
    val color = LocalClearAirPalette.current.sparkText
    Text(
        text = text,
        modifier = modifier.border(1.dp, color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
        style = ClearAirType.address.copy(fontSize = 10.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em),
        color = color,
        maxLines = 1,
    )
}
