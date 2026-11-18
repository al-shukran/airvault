package app.lensly.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.components.PillButton
import app.lensly.ui.components.SecureKeyboard
import app.lensly.ui.theme.LenslySpacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accent fill in the dark Lumen theme: the gold of [PillButton] accent and the keypad's backspace key. Their labels are the dark
 * `onPrimary` ink. The other goldens render the light theme, so this one is recorded with the night qualifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w720dp-h1600dp-port-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccentNightScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun accentLabelsInTheNightTheme() = compose.snapshot("accent_labels_night") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(LenslySpacing.md),
            verticalArrangement = Arrangement.spacedBy(LenslySpacing.md),
        ) {
            PillButton(text = "Create", onClick = {}, accent = true, modifier = Modifier.fillMaxWidth())
            PillButton(text = "Create", onClick = {}, accent = false, modifier = Modifier.fillMaxWidth())
            SecureKeyboard(onKey = {}, onBackspace = {}, onDone = {})
        }
    }
}
