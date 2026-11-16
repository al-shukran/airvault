package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.screenshot.SCREENSHOT_QUALIFIERS
import app.lensly.ui.screenshot.snapshot
import app.lumio.demo.DemoPortfolio
import app.lumio.ui.settings.DeveloperSettingsPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Developer page of the debug build, with its demo portfolio switch. Only a debug build has this page, so its golden is recorded from the debug tests. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeveloperScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsDeveloper() = compose.snapshot("settings_developer") {
        DeveloperSettingsPage(onBack = {}, section = checkNotNull(DemoPortfolio.developerSection {}))
    }
}
