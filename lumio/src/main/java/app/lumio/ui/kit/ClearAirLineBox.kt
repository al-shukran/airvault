package app.lumio.ui.kit

import androidx.compose.ui.text.style.LineHeightStyle

/**
 * Sets a line in the whole of its line height, with the text a little above the middle of it, so a line sits where the mock's line box puts it. Compose
 * otherwise gives a single line only the height of its text, so a row made of one line comes out shorter than the mock's.
 */
val ClearAirLineBox = LineHeightStyle(LineHeightStyle.Alignment(0.3f), LineHeightStyle.Trim.None)
