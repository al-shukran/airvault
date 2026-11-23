package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.ui.settings.ProviderPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Provider page: a provider that works without a key and is anonymous, and a provider that is off for want of a key. Recorded with
 * `:lumio:recordRoborazziDebug`, like [LumioScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderPageScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun providerWorkingWithoutAKey() = compose.lumioSnapshot("provider_working") {
        ProviderPage(
            name = "Blockscout",
            status = "✓ Working",
            statusNote = null,
            tags = listOf("Works without an API Key", "Anonymous"),
            keyed = true,
            keySet = false,
            offline = null,
            onConfigureKey = {},
            onBack = {},
            onLock = {},
        )
    }

    @Test
    fun providerOffForWantOfAKey() = compose.lumioSnapshot("provider_needs_key") {
        ProviderPage(
            name = "Etherscan",
            status = "– Off",
            statusNote = "Needs API Key",
            tags = emptyList(),
            keyed = true,
            keySet = false,
            offline = null,
            onConfigureKey = {},
            onBack = {},
            onLock = {},
        )
    }
}
