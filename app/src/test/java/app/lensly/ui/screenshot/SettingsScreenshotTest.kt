package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.settings.CatalogCounts
import app.lensly.ui.settings.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings with its values read, and while the saved cap and the catalog counts are still being read. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsLoaded() = compose.snapshot("settings_loaded") {
        SettingsScreen(
            bulkCap = BulkCap.HUNDRED,
            onBulkCap = {},
            onChangePassword = {},
            catalog = CatalogCounts(networks = 12, tokens = 48),
            onUpdateCatalog = {},
            onWipe = {},
            versionName = "1.0",
            versionCode = 1,
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun settingsLoading() = compose.snapshot("settings_loading") {
        SettingsScreen(
            bulkCap = null,
            onBulkCap = {},
            onChangePassword = {},
            catalog = null,
            onUpdateCatalog = {},
            onWipe = {},
            versionName = "1.0",
            versionCode = 1,
            onBack = {},
            onLock = {},
        )
    }
}
