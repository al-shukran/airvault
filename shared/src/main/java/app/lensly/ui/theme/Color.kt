package app.lensly.ui.theme

import androidx.compose.ui.graphics.Color

// Theme colours are not here: they come from the theme in composition (MaterialTheme.colorScheme and LocalLumenPalette). What remains
// is the fixed colour of the logo and of the account palette, which do not change with the theme.

/** The light disc behind a dark logo that would vanish on black. Neutral grey, so it adds no hue to the brand colour. */
val LenslyLogoBackdrop = Color(0xFFE6E6E6)

/** The dark disc behind a pale logo that would vanish on a light page. Neutral, like [LenslyLogoBackdrop]. */
val LenslyLogoDarkBackdrop = Color(0xFF1C1C1C)

// The palette of account and wallet colours. Each one is light enough that a black icon reads on it, and each one stands
// out on black. The names match ProfileColour in the app package.
val LenslyProfileRed = Color(0xFFFF5C5C)
val LenslyProfileOrange = Color(0xFFFF9F43)
val LenslyProfileYellow = Color(0xFFFFD166)
val LenslyProfileGreen = Color(0xFF4ADE80)
val LenslyProfileTeal = Color(0xFF2EC4B6)
val LenslyProfileBlue = Color(0xFF4DA3FF)
val LenslyProfileViolet = Color(0xFFA78BFA)
val LenslyProfilePink = Color(0xFFF472B6)
