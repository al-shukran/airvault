package app.lensly.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lensly.shared.R

// Archivo is the heading face. It ships as one variable font: the weight axis picks the weight, and the width axis is set at 114, as the
// Lumen and Clear Air headings are set.
@OptIn(ExperimentalTextApi::class)
val ArchivoFamily = FontFamily(
    Font(
        resId = R.font.archivo_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.width(114f)),
    ),
    Font(
        resId = R.font.archivo_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.width(114f)),
    ),
)

val SpaceMonoFamily = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

// Running text is a clean sans, the face the reference designs use for body copy. It is the system sans
// (Roboto on Android). Data that must be compared by eye, such as addresses, hashes and amounts, copies
// SpaceMonoFamily instead.
val LenslySansFamily: FontFamily = FontFamily.SansSerif

/**
 * The face of data that is compared by eye, such as addresses. The cover's is Space Mono; the Lumen theme provides JetBrains Mono.
 */
val LocalMonoFamily = staticCompositionLocalOf { SpaceMonoFamily }

private const val LABEL_TRACKING = 1.5

/**
 * The cover's type scale. Display and headline use Archivo, titles and labels use Space Mono, and body text uses the sans.
 */
val CoverTypography = Typography(
    // Display and headline styles use the heading face.
    displayLarge = TextStyle(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 96.sp,
        lineHeight = 96.sp,
        letterSpacing = 0.sp,
    ),
    // Archivo at width 114 is wider than the dot face was: at 64 sp "AVAILABLE" breaks inside the word, and 44 sp leaves room for it on a
    // 320 dp phone at the default font scale.
    displayMedium = TextStyle(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 44.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 48.sp,
        lineHeight = 48.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    // Titles, body and labels use the monospace face.
    titleLarge = TextStyle(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = LenslySansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = LenslySansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = LenslySansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = LABEL_TRACKING.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = LABEL_TRACKING.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SpaceMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = LABEL_TRACKING.sp,
    ),
)
