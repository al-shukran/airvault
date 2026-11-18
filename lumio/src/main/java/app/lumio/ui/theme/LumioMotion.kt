package app.lumio.ui.theme

import androidx.compose.animation.core.BoundsTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Rect

/**
 * Lumen's motion, section 3.1 of the Phase 2.8 design. Springs move things, easings fade and tint them, and the durations are in milliseconds.
 * For a `Dp`, `IntOffset` or `Rect` value, call `spring<T>` with the same damping ratio and stiffness. Read these values in a layout or
 * draw lambda, never in composition (section 3.7).
 */
object LumioMotion {
    /** Bounds, container transforms and slides. */
    val spatial = spring<Float>(dampingRatio = 0.86f, stiffness = 380f)

    /** Press scale, chips and odometer digits. */
    val spatialFast = spring<Float>(dampingRatio = 0.90f, stiffness = 700f)

    /** The success check, the ring closing and the orb's pop. */
    val bouncy = spring<Float>(dampingRatio = 0.62f, stiffness = 420f)

    /** Alpha and colour. The damping is 1, so they never overshoot. */
    val effects = spring<Float>(dampingRatio = 1.00f, stiffness = 1600f)

    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 120
    const val MEDIUM = 220
    const val LONG = 360
    const val COUNT_UP = 900
    const val STAGGER = 30
    const val STAGGER_MAX_ITEMS = 8

    /** The bounds of a shared element move with the spatial spring. */
    val sharedBoundsTransform = BoundsTransform { _, _ -> spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = Rect.VisibilityThreshold) }
}
