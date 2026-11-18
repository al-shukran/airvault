package app.lensly.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.components.ChoiceDialogRow
import app.lensly.ui.components.NumberFieldSetting
import app.lensly.ui.components.RadioRow
import app.lensly.ui.components.SliderSetting
import app.lensly.ui.theme.LenslySpacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared setting controls at rest: a radio list, a slider with its caption, a number field, and a choice row. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingControlsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun radioRows() = compose.snapshot("setting_controls_radio") {
        Column(modifier = Modifier.fillMaxWidth().padding(LenslySpacing.md)) {
            RadioRow(
                title = "Strict",
                description = "Each amount is verified from its input.",
                selected = true,
                onSelect = {},
            )
            RadioRow(
                title = "Segwit Unverified",
                description = "Segwit amounts: you confirm them.",
                selected = false,
                onSelect = {},
            )
        }
    }

    @Test
    fun slider() = compose.snapshot("setting_controls_slider") {
        Column(modifier = Modifier.fillMaxWidth().padding(LenslySpacing.md)) {
            SliderSetting(
                title = "Longest Wait",
                options = listOf("1 s", "5 s", "10 s", "30 s", "1 min", "5 min", "15 min", "1 h"),
                selected = "10 s",
                label = { it },
                onSelect = {},
                caption = { if (it == "10 s") "Default" else null },
            )
        }
    }

    @Test
    fun numberField() = compose.snapshot("setting_controls_number") {
        Column(modifier = Modifier.fillMaxWidth().padding(LenslySpacing.md)) {
            NumberFieldSetting(
                title = "Fee Rate Above",
                value = 200,
                range = 1..10_000,
                suffix = "sat/vB",
                onValid = {},
            )
        }
    }

    @Test
    fun choiceRow() = compose.snapshot("setting_controls_choice") {
        Column(modifier = Modifier.fillMaxWidth().padding(LenslySpacing.md)) {
            ChoiceDialogRow(
                label = "Bulk Cap",
                value = "100",
                options = listOf("20", "50", "100"),
                selected = "100",
                optionLabel = { it },
                onSelect = {},
                description = "The most accounts one bulk run can create.",
            )
        }
    }
}
