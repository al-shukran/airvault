package app.lensly.ui.theme

import androidx.compose.ui.graphics.Color

// Lensly is dark-only. These tokens are the single source for every color in the app.
//
// Surfaces step up from the page: LenslyBlack is the page, then each level is a lift in grey.
// Material roles (surfaceContainer and so on) are mapped to these levels in Theme.kt.
//
// Text: LenslyOnSurface is primary text. LenslyMuted is secondary text, the onSurface muted token
// (Material onSurfaceVariant). It passes 4.5:1 on every surface up to LenslySurfaceContainer.
//
// Brand: LenslyAccent is the red for primary fills and the signature dot. It is about 4:1 on black, so use it
// for fills and large text, not for body text.
//
// Status: LenslyWarning, LenslySuccess and LenslyError carry banner and state text. Each passes 4.5:1 on every
// surface up to LenslySurfaceContainer, and LenslyError also passes on LenslySurfaceHigh.

val LenslyBlack = Color(0xFF000000)
val LenslySurfaceLow = Color(0xFF0F0F0F)
val LenslySurface = Color(0xFF141414)
val LenslySurfaceContainer = Color(0xFF1E1E1E)
val LenslySurfaceHigh = Color(0xFF2A2A2A)
val LenslySurfaceHighest = Color(0xFF363636)

/**
 * Decorative separators and frames: rules, dividers, and the outline on labelled pills. It is only 1.85:1 on
 * [LenslyBlack], so it must never be the only thing that marks a control.
 */
val LenslyOutline = Color(0xFF3A3A3A)

/**
 * The boundary of an interactive control: text field rules, field borders, and checkboxes. WCAG 1.4.11 wants 3:1 for
 * a component boundary, and this is the lightest step that reaches it: 3.88:1 on [LenslyBlack], 3.41:1 on
 * [LenslySurface], and 3.08:1 on [LenslySurfaceContainer], which is the fill the bordered fields sit on.
 */
val LenslyOutlineInteractive = Color(0xFF6A6A6A)
val LenslyOnSurface = Color(0xFFFFFFFF)
val LenslyMuted = Color(0xFF8E8E93)
val LenslyAccent = Color(0xFFD71921)

/** Amber for a warning that needs attention but does not stop the user. */
val LenslyWarning = Color(0xFFFFB020)

/** Green for a confirmed result. */
val LenslySuccess = Color(0xFF3DDC84)

/** Legible red for errors. The brand red [LenslyAccent] is too dark for small text on black. */
val LenslyError = Color(0xFFFF6B6B)

/** The light disc behind a dark logo that would vanish on black. Neutral grey, so it adds no hue to the brand colour. */
val LenslyLogoBackdrop = Color(0xFFE6E6E6)
