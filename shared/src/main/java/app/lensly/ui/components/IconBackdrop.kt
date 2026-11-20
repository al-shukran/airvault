package app.lensly.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import app.lensly.shared.R
import app.lensly.ui.theme.LenslyLogoBackdrop
import app.lensly.ui.theme.LenslyLogoDarkBackdrop

/**
 * How a logo's artwork sits on the pages of the themes. [DARK] artwork is too dark for a dark page and [PALE] artwork is too pale for a
 * light page, so each vanishes on the page it is named for. [NEUTRAL] artwork shows on both.
 */
enum class LogoTone { NEUTRAL, DARK, PALE }

/** The logo's width and height as a share of its backdrop disc, which leaves a margin of 15% of the disc per side. */
private const val BACKDROP_LOGO_SHARE = 0.7f

/** The tone of the logo [res]: [LogoTone.NEUTRAL] unless the logo is in one of the tone sets, or it is the unknown-token icon. */
fun logoTone(@DrawableRes res: Int): LogoTone = logoTones[res] ?: LogoTone.NEUTRAL

private val logoTones: Map<Int, LogoTone> = buildMap {
    chainIconsNeedingLightBackdrop.forEach { name -> chainIconDrawables[name]?.let { put(it, LogoTone.DARK) } }
    tokenIconsNeedingLightBackdrop.forEach { name -> tokenIconDrawables[name]?.let { put(it, LogoTone.DARK) } }
    chainIconsNeedingDarkBackdrop.forEach { name -> chainIconDrawables[name]?.let { put(it, LogoTone.PALE) } }
    tokenIconsNeedingDarkBackdrop.forEach { name -> tokenIconDrawables[name]?.let { put(it, LogoTone.PALE) } }
    // The unknown-token icon is white, and it stands in for every token without a logo.
    put(R.drawable.ic_unknown_token, LogoTone.PALE)
    // The Algorand mark is black, so it vanishes on a dark page. Solana is bright on both pages and needs no disc.
    put(R.drawable.ic_algorand, LogoTone.DARK)
}

/**
 * The disc behind a logo of [tone] on a page that is dark ([onDarkPage]) or light, or null when the logo shows on the page as it is. A dark
 * logo gets a light disc on a dark page, and a pale logo gets a dark disc on a light page. The other two pairs need no disc.
 */
fun backdropColor(tone: LogoTone, onDarkPage: Boolean): Color? = when {
    tone == LogoTone.DARK && onDarkPage -> LenslyLogoBackdrop
    tone == LogoTone.PALE && !onDarkPage -> LenslyLogoDarkBackdrop
    else -> null
}

/** Whether the page of the theme in composition is dark: its background is. The cover is always dark, and the hidden area follows the system. */
@Composable
fun onDarkPage(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * A logo at [size]. Where its [tone] would make it vanish on the page of the current theme (see [backdropColor]), the logo sits centred on a
 * disc of [size], so it stays visible. Elsewhere it fills [size] exactly as it did before backdrops existed.
 */
@Composable
fun BackdropLogo(painter: Painter, size: Dp, tone: LogoTone, modifier: Modifier = Modifier) {
    val disc = backdropColor(tone, onDarkPage = onDarkPage())
    if (disc != null) {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(disc))
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
