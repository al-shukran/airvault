package app.lumio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lumio.R

// Manrope is a variable font, so each weight selects a point on the one file. Amounts use its tabular figures ("tnum"), so a changing
// number never moves the text around it.
@OptIn(ExperimentalTextApi::class)
val LumioManropeFamily = FontFamily(
    Font(R.font.manrope_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

// JetBrains Mono is for addresses, hashes and raw data only. It is a variable font, as Manrope is.
@OptIn(ExperimentalTextApi::class)
val LumioMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

/** The mono style of section 1.2: addresses in groups of four, every other group dimmed by the caller. */
val LumioMono = TextStyle(
    fontFamily = LumioMonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
)

private const val TABULAR = "tnum"

private fun lumioStyle(
    size: Float,
    line: Float,
    weight: FontWeight,
    tracking: Float,
    tabular: Boolean = false,
) = TextStyle(
    fontFamily = LumioManropeFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = if (tabular) TABULAR else null,
)

/**
 * The type scale of section 1.2. Sizes and line heights are in sp, and tracking is in sp as well. The roles the spec does not list take the
 * nearest listed style, so no text falls back to the system face. The amount roles use tabular figures.
 */
val LumioTypography = Typography(
    displayLarge = lumioStyle(44f, 48f, FontWeight.SemiBold, -1.0f, tabular = true),
    displayMedium = lumioStyle(32f, 38f, FontWeight.SemiBold, -0.6f, tabular = true),
    displaySmall = lumioStyle(32f, 38f, FontWeight.SemiBold, -0.6f, tabular = true),
    headlineLarge = lumioStyle(32f, 38f, FontWeight.SemiBold, -0.6f, tabular = true),
    headlineMedium = lumioStyle(22f, 28f, FontWeight.Bold, -0.3f),
    headlineSmall = lumioStyle(22f, 28f, FontWeight.Bold, -0.3f),
    titleLarge = lumioStyle(22f, 28f, FontWeight.Bold, -0.3f),
    titleMedium = lumioStyle(17f, 24f, FontWeight.SemiBold, 0f),
    titleSmall = lumioStyle(15f, 20f, FontWeight.SemiBold, 0f),
    bodyLarge = lumioStyle(16f, 24f, FontWeight.Normal, 0f),
    bodyMedium = lumioStyle(14f, 20f, FontWeight.Normal, 0f),
    bodySmall = lumioStyle(12f, 16f, FontWeight.Medium, 0f),
    labelLarge = lumioStyle(15f, 20f, FontWeight.Bold, 0f),
    labelMedium = lumioStyle(13f, 18f, FontWeight.SemiBold, 0.1f),
    labelSmall = lumioStyle(11f, 14f, FontWeight.SemiBold, 0.2f),
)
