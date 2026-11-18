package app.lumio.ui.kit

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** One asset's slice of the allocation: its [assetId] (the id of its `HomeAsset`), its value in dollars, and the [color] it is drawn in. */
@Immutable
data class AllocationSlice(val assetId: String, val usd: Double, val color: Color)

/** One share of the allocation as the ring draws it: its [assetId], its [fraction] of the total (from 0 to 1), and its [color]. */
@Immutable
data class AllocationShare(val assetId: String, val fraction: Float, val color: Color)

/** The rules of the allocation (section 1.6): which assets it shows, and how big each share is. */
internal object AllocationMath {
    /** The id of the share that merges the small assets. No asset takes it. */
    const val OTHER_ID = "allocation:other"

    /** An asset with a smaller share of the total than this is merged into Other. */
    const val OTHER_BELOW = 0.02

    /** The total in dollars of [slices]. A value that is zero, negative or not a number counts for nothing. */
    fun total(slices: List<AllocationSlice>): Double = slices.sumOf { valueOf(it) }

    /**
     * The shares of [slices]: the assets largest first, ties by id, then Other, which holds every asset below [OTHER_BELOW] of the total. Slices with the
     * same id are added together, and a slice with no value takes no share. The shares sum to 1, and the list is empty when nothing has a value.
     * [otherColor] colours the Other share.
     */
    fun shares(slices: List<AllocationSlice>, otherColor: Color): List<AllocationShare> {
        val merged = slices.groupBy { it.assetId }
            .map { (id, same) -> AllocationSlice(id, same.sumOf { valueOf(it) }, same.first().color) }
            .filter { it.usd > 0.0 }
        val total = merged.sumOf { it.usd }
        if (total <= 0.0) return emptyList()
        val (small, big) = merged.partition { it.usd / total < OTHER_BELOW }
        val shares = big
            .sortedWith(compareByDescending<AllocationSlice> { it.usd }.thenBy { it.assetId })
            .map { AllocationShare(it.assetId, (it.usd / total).toFloat(), it.color) }
        val smallTotal = small.sumOf { it.usd }
        return if (smallTotal > 0.0) shares + AllocationShare(OTHER_ID, (smallTotal / total).toFloat(), otherColor) else shares
    }

    /** The index of the share under [angle] (in degrees, as the ring measures them), or null in a gap, in the seam, or beyond the arc. */
    fun shareAt(shares: List<AllocationShare>, angle: Float): Int? {
        val at = AllocationGeometry.normalise(angle)
        var hit: Int? = null
        AllocationGeometry.layout(shares.size, { shares[it].fraction }) { index, start, span ->
            if (hit == null && span > 0f && at >= start && at < start + span) hit = index
        }
        return hit
    }

    private fun valueOf(slice: AllocationSlice): Double = if (slice.usd.isFinite() && slice.usd > 0.0) slice.usd else 0.0
}

/**
 * The arcs of the ring, in degrees on the canvas (zero is three o'clock, and the angle grows clockwise). The shares run from [ARC_START] through a sweep of
 * [RING_SWEEP]; the seam, the gap at one o'clock that holds the dot, is what is left of the circle.
 */
internal object AllocationGeometry {
    /** The degrees between two neighbouring shares, once both are visible. */
    const val GAP = 4f

    /** A share at or above this fraction has its whole gap. A smaller share has its gap scaled in, so the gap grows in as the share does. */
    const val FULL_GAP_SHARE = 0.02f

    /** The part of a share's gap that it has, from 0 to 1. */
    fun visibility(share: Float): Float = (share.coerceIn(0f, 1f) / FULL_GAP_SHARE).coerceAtMost(1f)

    /** [angle] brought into the sweep: the same angle, from [ARC_START] to a full turn on. */
    fun normalise(angle: Float): Float {
        var turned = (angle - ARC_START) % 360f
        if (turned < 0f) turned += 360f
        return turned + ARC_START
    }

    /**
     * Lays out [count] arcs in order. [shareOf] gives arc i's share, and [arc] receives its index, its start and its span in degrees. The span of arc i is
     * its share of the usable sweep, which is the sweep less the gaps between neighbours, so shares that sum to 1 fill the sweep exactly. It is inline, so the
     * draw calls it every frame without allocating.
     */
    inline fun layout(count: Int, shareOf: (Int) -> Float, arc: (index: Int, start: Float, span: Float) -> Unit) {
        var gaps = 0f
        for (i in 0 until count - 1) gaps += GAP * minOf(visibility(shareOf(i)), visibility(shareOf(i + 1)))
        val usable = maxOf(RING_SWEEP - gaps, 0f)
        var cursor = ARC_START
        for (i in 0 until count) {
            val span = shareOf(i).coerceIn(0f, 1f) * usable
            arc(i, cursor, span)
            cursor += span
            if (i < count - 1) cursor += GAP * minOf(visibility(shareOf(i)), visibility(shareOf(i + 1)))
        }
    }
}

/**
 * The colours of the segments (section 1.1): one colour per asset, and one for Other. Each reads at 3:1 or better on its page, as WCAG asks of graphics,
 * so the palette is checked in both themes. A colour depends on the asset's id only, so a balance change never recolours a segment.
 */
internal object AllocationPalette {
    private val night = listOf(
        Color(0xFF7FB2FF),
        Color(0xFFE9C49A),
        Color(0xFFE58FB8),
        Color(0xFF9AB0C6),
        Color(0xFFD8D2C4),
        Color(0xFF7ED3C4),
    )
    private val day = listOf(
        Color(0xFF2F6FD0),
        Color(0xFFA8702F),
        Color(0xFFB23F78),
        Color(0xFF55697F),
        Color(0xFF6F7A2A),
        Color(0xFF1E8C7B),
    )
    private val nightOther = Color(0xFF6B7786)
    private val dayOther = Color(0xFF8A8478)

    /** Whether [background] is a dark page, which takes the night colours. The theme's background is the token it reads. */
    fun isDark(background: Color): Boolean = background.luminance() < 0.5f

    /** The colours of the segments on a page that is [dark] or light. */
    fun ramp(dark: Boolean): List<Color> = if (dark) night else day

    /** The colour of Other on a page that is [dark] or light. */
    fun other(dark: Boolean): Color = if (dark) nightOther else dayOther

    /** The colour of the asset [assetId] on the page [background]. */
    fun colorOf(assetId: String, background: Color): Color {
        val ramp = ramp(isDark(background))
        return ramp[Math.floorMod(assetId.hashCode(), ramp.size)]
    }

    /** The colour of Other on the page [background]. */
    fun otherOf(background: Color): Color = other(isDark(background))
}
