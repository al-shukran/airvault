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

// Archivo is a variable font: each weight selects a point on the one file, and the width axis is set per family. Headings are set at width
// 114 and body text at width 92.
@OptIn(ExperimentalTextApi::class)
private fun archivoFamily(width: Float) = FontFamily(
    Font(R.font.archivo_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.width(width))),
    Font(R.font.archivo_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.width(width))),
    Font(R.font.archivo_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.width(width))),
    Font(R.font.archivo_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.width(width))),
)

private val LumioHeadingFamily = archivoFamily(width = 114f)
private val LumioBodyFamily = archivoFamily(width = 92f)

// JetBrains Mono is a variable font. Its figures are all one width, so an amount, balance or time never moves the text around it. Addresses
// and hashes use it too.
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

private fun lumioStyle(
    family: FontFamily,
    size: Float,
    line: Float,
    weight: FontWeight,
    tracking: Float,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/**
 * The type scale of section 1.2. Sizes and line heights are in sp, and tracking is in sp as well. The display, headline and title styles set
 * headings in Archivo at width 114, and the body and label styles set text in Archivo at width 92. The amount styles, which the spec marks as
 * tabular, are set in JetBrains Mono. The roles the spec does not list take the nearest listed style, so no text falls back to the system face.
 */
val LumioTypography = Typography(
    displayLarge = lumioStyle(LumioMonoFamily, 44f, 48f, FontWeight.SemiBold, -1.0f),
    displayMedium = lumioStyle(LumioMonoFamily, 32f, 38f, FontWeight.SemiBold, -0.6f),
    displaySmall = lumioStyle(LumioMonoFamily, 32f, 38f, FontWeight.SemiBold, -0.6f),
    headlineLarge = lumioStyle(LumioMonoFamily, 32f, 38f, FontWeight.SemiBold, -0.6f),
    headlineMedium = lumioStyle(LumioHeadingFamily, 22f, 28f, FontWeight.Bold, -0.3f),
    headlineSmall = lumioStyle(LumioHeadingFamily, 22f, 28f, FontWeight.Bold, -0.3f),
    titleLarge = lumioStyle(LumioHeadingFamily, 22f, 28f, FontWeight.Bold, -0.3f),
    titleMedium = lumioStyle(LumioHeadingFamily, 17f, 24f, FontWeight.SemiBold, 0f),
    titleSmall = lumioStyle(LumioHeadingFamily, 15f, 20f, FontWeight.SemiBold, 0f),
    bodyLarge = lumioStyle(LumioBodyFamily, 16f, 24f, FontWeight.Normal, 0f),
    bodyMedium = lumioStyle(LumioBodyFamily, 14f, 20f, FontWeight.Normal, 0f),
    bodySmall = lumioStyle(LumioBodyFamily, 12f, 16f, FontWeight.Medium, 0f),
    labelLarge = lumioStyle(LumioBodyFamily, 15f, 20f, FontWeight.Bold, 0f),
    labelMedium = lumioStyle(LumioBodyFamily, 13f, 18f, FontWeight.SemiBold, 0.1f),
    labelSmall = lumioStyle(LumioBodyFamily, 11f, 14f, FontWeight.SemiBold, 0.2f),
)
