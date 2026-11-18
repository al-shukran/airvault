package app.lumio.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * An island: the rows of one group on one rounded surface (section 1.3). It has no shadow. The light theme draws a 1 dp hairline instead,
 * because a light surface has no elevation to set it apart from the page.
 */
@Composable
fun LumioIsland(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    val hairline = if (isSystemInDarkTheme()) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(hairline),
        content = content,
    )
}
