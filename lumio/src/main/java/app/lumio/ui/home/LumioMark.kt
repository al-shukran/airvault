package app.lumio.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lumio.R

/**
 * The AirVault mark in its own colours, on either theme. It is decorative, since the screen title names the screen. The artwork is
 * ic_airvault_mark.xml.
 */
@Composable
fun LumioMark(size: Dp = 24.dp) {
    Image(
        painter = painterResource(R.drawable.ic_airvault_mark),
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}
