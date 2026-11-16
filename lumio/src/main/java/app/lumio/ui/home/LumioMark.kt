package app.lumio.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.lumio.R

/** The Lumio mark beside the Home wordmark: the launcher glyph at 24 dp. It is decorative, since the wordmark names the app. */
@Composable
fun LumioMark() {
    Image(
        painter = painterResource(R.drawable.ic_lumio_mark),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
    )
}
