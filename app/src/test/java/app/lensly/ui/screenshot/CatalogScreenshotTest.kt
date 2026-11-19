package app.lensly.ui.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lensly.ui.settings.NetworksScreen
import app.lensly.ui.settings.TokensScreen
import app.lensly.ui.shippedRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Networks and Tokens lists over the catalog that ships in the APK. The rows fade in, so each capture waits for them. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = SCREENSHOT_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val registry = shippedRegistry()

    @Test
    fun networks() = compose.snapshot("networks_all", settleMs = SETTLE_MS) { NetworksSnapshot() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun networksNight() = compose.snapshot("networks_all_night", settleMs = SETTLE_MS) { NetworksSnapshot() }

    @Test
    fun tokens() = compose.snapshot("tokens_grouped", settleMs = SETTLE_MS) { TokensSnapshot() }

    @Test
    @Config(qualifiers = SCREENSHOT_NIGHT_QUALIFIERS)
    fun tokensNight() = compose.snapshot("tokens_grouped_night", settleMs = SETTLE_MS) { TokensSnapshot() }

    @Composable
    private fun NetworksSnapshot() {
        NetworksScreen(networks = registry.networks(), tokens = registry.tokens(), onBack = {})
    }

    @Composable
    private fun TokensSnapshot() {
        TokensScreen(networks = registry.networks(), tokens = registry.tokens(), onBack = {})
    }

    private companion object {
        /** Longer than the last staggered row takes to fade in. */
        const val SETTLE_MS = 1_000L
    }
}
