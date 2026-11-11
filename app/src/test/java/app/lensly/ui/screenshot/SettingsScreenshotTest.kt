package app.lensly.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.core.wallet.BulkCap
import app.lensly.ui.settings.Catalog
import app.lensly.ui.shippedRegistry
import app.lensly.ui.settings.FailureCapChoice
import app.lensly.ui.settings.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings with its values read, and while the saved lockout and the catalog are still being read. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsLoaded() = compose.snapshot("settings_loaded") {
        val registry = shippedRegistry()
        SettingsScreen(
            bulkCap = BulkCap.HUNDRED,
            onBulkCap = {},
            onChangePassword = {},
            failureDelayCap = FailureCapChoice.DEFAULT,
            onFailureDelayCap = {},
            catalog = Catalog(networks = registry.networks(), tokens = registry.tokens()),
            onNetworks = {},
            onTokens = {},
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
            failureDelayCap = null,
            onFailureDelayCap = {},
            catalog = null,
            onNetworks = {},
            onTokens = {},
            onUpdateCatalog = {},
            onWipe = {},
            versionName = "1.0",
            versionCode = 1,
            onBack = {},
            onLock = {},
        )
    }
}
