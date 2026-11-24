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
            enabled = true,
            onEnabled = {},
            tags = listOf("Works without an API Key", "Anonymous"),
            networks = emptyList(),
            keyed = true,
            savedTail = null,
            offline = null,
            onSaveKey = {},
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
            enabled = true,
            onEnabled = {},
            tags = emptyList(),
            networks = emptyList(),
            keyed = true,
            savedTail = null,
            offline = null,
            onSaveKey = {},
            onBack = {},
            onLock = {},
        )
    }
}
