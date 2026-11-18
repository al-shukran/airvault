package app.lumio.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumio.R

/**
 * The Lumio mark, the Lumen ring, tinted for the theme: gold on ink in Night and ink on paper in Day. It is decorative, since the screen
 * title names the screen. The geometry is documented in ic_lumio_mark.xml.
 */
@Composable
fun LumioMark(size: Dp = 24.dp) {
    val tint = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
    Image(
        painter = painterResource(R.drawable.ic_lumio_mark),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(size),
    )
}
