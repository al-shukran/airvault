package app.lensly.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/**
 * Clear Air's motion, from the Visual System of the proposal. Motion shows where you went or what changed. Nothing loops except the spark while
 * Lensly holds a request. Every transition settles within 300 ms, and reduced motion cuts to the end state with a cross-fade.
 */
object ClearAirMotion {
    /** The press: the surface shrinks to this scale and springs back. */
    const val PRESS_SCALE = 0.98f

    /** The press release: spring ζ1.0, k1400, about 90 ms. */
    val press: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1400f)

    /** A sheet rising from the bottom: spring ζ0.9, k520, about 280 ms. Its scrim fades in over [SCRIM_MS]. */
    val sheet: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 520f)
    const val SCRIM_MS = 200

    /** A full-screen push, which is a new place: 240 ms on a decelerating curve, with a 1/12-width slide and a fade. */
    const val PUSH_MS = 240
    val pushEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** A digit of an amount rolling to its new value: spring ζ0.9, k700, about 180 ms. A value shown on open never animates. */
    val numbers: SpringSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 700f)

    /** The spark travelling along the line to the next step: 260 ms, on the emphasized curve. */
    const val STEP_MS = 260
    val emphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** One QR part being read: the segment fills in 120 ms, with a clock tick for each part. */
    const val QR_SEGMENT_MS = 120

    /** The success check's stroke takes 300 ms, and the rows of a timeline come in 40 ms apart. */
    const val SUCCESS_MS = 300
    const val STAGGER_MS = 40

    /** The spark's breath while Lensly holds the request: opacity 0.5 to 1 and back, over 2.4 s. It is the only loop in the app. */
    const val BREATH_MS = 2400
    val breathEasing: Easing = EaseInOut

    /** The length of the cross-fade that reduced motion uses in place of a move. */
    const val CROSS_FADE_MS = 100
}
