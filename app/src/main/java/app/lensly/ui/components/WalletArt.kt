package app.lensly.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.lensly.app.ProfileColour
import app.lensly.ui.profile.swatch
import app.lensly.ui.theme.LenslyOnSurface
import app.lensly.ui.theme.LenslySurfaceLow

private const val RING_COUNT = 6

// The palette only ever grows, and the first eight colours are the ones the art was first drawn from. A seed picks its glow
// hues from those eight alone, so a wallet that existed before the palette grew keeps exactly the art it had; the newer
// colours are reached only by choosing them explicitly.
private const val SEED_PALETTE_SIZE = 8

// SplitMix64 constants, as signed Longs.
private const val GOLDEN_GAMMA = -0x61c8864680b583ebL
private const val MIX_ONE = -0x40a7b892e31b1a47L
private const val MIX_TWO = -0x6b2fb644ecceee15L

/**
 * The generated artwork behind a wallet card: a dark page with three soft glows and a set of concentric rings. [seed] is the
 * wallet's id, so a wallet always gets the same art. The first glow takes the wallet's profile [colour] when it has one, and the
 * seed picks the other hues from the palette. It is drawn with Canvas, so it needs no bitmap. Size it with [modifier].
 */
@Composable
fun WalletArt(seed: String, colour: ProfileColour?, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawWalletArt(seed, colour) }
}

private fun DrawScope.drawWalletArt(seed: String, colour: ProfileColour?) {
    val random = ArtRandom(seed)
    val (first, second, third) = seedHues(random, colour)
    val w = size.width
    val h = size.height
    val reach = maxOf(w, h)

    drawRect(LenslySurfaceLow)
    glow(first.swatch(), alpha = 0.55f, centre = Offset(w * random.next(0.15f, 0.85f), h * random.next(0.15f, 0.7f)), radius = reach * 0.75f)
    glow(second.swatch(), alpha = 0.4f, centre = Offset(w * random.next(0.1f, 0.9f), h * random.next(0.5f, 1f)), radius = reach * 0.6f)
    glow(third.swatch(), alpha = 0.3f, centre = Offset(w * random.next(0.5f, 1f), h * random.next(0f, 0.5f)), radius = reach * 0.45f)

    // Fine rings around a point near one corner, like the rings of a lens.
    val ringCentre = Offset(w * random.next(0.6f, 0.95f), h * random.next(0.05f, 0.45f))
    val ringStep = reach * 0.07f
    val stroke = Stroke(width = 1.dp.toPx())
    for (ring in 1..RING_COUNT) {
        drawCircle(color = LenslyOnSurface.copy(alpha = 0.12f), radius = ringStep * ring, center = ringCentre, style = stroke)
    }
}

/** The three glow hues: the profile [colour] when it has one, then two the seed picks from the seed palette. */
internal fun seedHues(random: ArtRandom, colour: ProfileColour?): List<ProfileColour> {
    val palette = ProfileColour.entries.take(SEED_PALETTE_SIZE)
    return listOf(
        colour ?: palette[random.pick(palette.size)],
        palette[random.pick(palette.size)],
        palette[random.pick(palette.size)],
    )
}

/** A soft round glow: the colour at [alpha] at its centre, fading to nothing at [radius]. */
private fun DrawScope.glow(colour: Color, alpha: Float, centre: Offset, radius: Float) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(colour.copy(alpha = alpha), colour.copy(alpha = 0f)),
            center = centre,
            radius = radius,
        ),
    )
}

/** A SplitMix64 stream seeded from a string, so the same seed always gives the same values in the same order. */
internal class ArtRandom(seed: String) {
    private var state = seed.hashCode().toLong()

    private fun nextBits(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_ONE
        z = (z xor (z ushr 27)) * MIX_TWO
        return z xor (z ushr 31)
    }

    /** A value from [low] up to, but not including, [high]. */
    fun next(low: Float, high: Float): Float = low + (high - low) * ((nextBits() ushr 40).toFloat() / (1 shl 24))

    /** An index from 0 up to, but not including, [count]. */
    fun pick(count: Int): Int = ((nextBits() ushr 33) % count).toInt()
}
