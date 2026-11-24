package app.lumio.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.lensly.ui.components.lumioPress
import app.lensly.ui.theme.ClearAirMotion
import app.lensly.ui.theme.ClearAirSpacing
import app.lensly.ui.theme.ClearAirType
import app.lensly.ui.theme.LocalClearAirPalette

/** The height of a row with a one-line subtitle, from the proposal. A two-line subtitle takes the row a little taller. */
private val ROW_HEIGHT = 62.dp

/**
 * A row of a list: an optional [leading] logo or monogram, a [title], a [description] under it when given, an optional [trailing] value, and a chevron
 * when the row opens something ([onClick]). A [descriptionSuffix] sits after the description's text on its line, such as a row of chain logos. It is
 * full-bleed with the page gutter on each side, and it scales to 0.98 while pressed. A long title truncates; the trailing value never does. A
 * [destructive] row, such as a deletion, has its title in rose.
 */
@Composable
fun ClearAirRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    descriptionSuffix: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    destructive: Boolean = false,
) {
    val palette = LocalClearAirPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ROW_HEIGHT)
            .then(
                if (onClick != null) {
                    Modifier.lumioPress(scale = ClearAirMotion.PRESS_SCALE, releaseSpring = ClearAirMotion.press, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ClearAirSpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(ClearAirSpacing.sm))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = ClearAirType.rowTitle,
                color = if (destructive) palette.rose else palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = description,
                        style = ClearAirType.body,
                        color = palette.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (descriptionSuffix == null) Modifier else Modifier.weight(1f, fill = false),
                    )
                    if (descriptionSuffix != null) {
                        Spacer(Modifier.width(ClearAirSpacing.xxs))
                        descriptionSuffix()
                    }
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(ClearAirSpacing.sm))
            trailing()
        }
        if (onClick != null) {
            Spacer(Modifier.width(ClearAirSpacing.xs))
            ClearAirChevron(color = palette.ink3)
        }
    }
}

/** The row chevron, drawn on the 24 dp grid with a 1.6 dp round stroke. It mirrors in a right-to-left layout. */
@Composable
private fun ClearAirChevron(color: Color) {
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.size(24.dp).graphicsLayer { scaleX = if (mirrored) -1f else 1f }) {
        val unit = size.width / GRID
        val path = Path().apply {
            moveTo(9f * unit, 6f * unit)
            lineTo(15f * unit, 12f * unit)
            lineTo(9f * unit, 18f * unit)
        }
        drawPath(path = path, color = color, style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The side of the icon grid that the chevron is drawn on. */
private const val GRID = 24f
