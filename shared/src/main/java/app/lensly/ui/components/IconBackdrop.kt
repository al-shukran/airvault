package app.lensly.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import app.lensly.ui.theme.LenslyLogoBackdrop

/** The logo's width and height as a share of the backdrop circle, which leaves a margin of 15% of the circle per side. */
private const val BACKDROP_LOGO_SHARE = 0.7f

/**
 * A logo at [size]. With [backdrop] set, the logo sits centred on a light circle of [size], so a dark brand colour stays
 * visible on black. Without it, the logo fills [size] exactly as it did before backdrops existed.
 */
@Composable
fun BackdropLogo(painter: Painter, size: Dp, backdrop: Boolean, modifier: Modifier = Modifier) {
    if (backdrop) {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(LenslyLogoBackdrop))
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(size * BACKDROP_LOGO_SHARE),
            )
        }
    } else {
        Image(painter = painter, contentDescription = null, modifier = modifier.size(size))
    }
}
