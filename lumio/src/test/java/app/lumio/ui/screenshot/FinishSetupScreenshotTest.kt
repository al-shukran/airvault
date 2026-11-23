package app.lumio.ui.screenshot

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.lumio.data.config.FinishItem
import app.lumio.data.config.FinishSetup
import app.lumio.ui.settings.FinishSetupCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The goldens of the Finish Setting Up card at the top of Settings: nothing ticked in the dark theme, and pairing ticked in the light one. They use the
 * same theme, size and recording as [LumioScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LUMIO_DARK_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinishSetupScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun finishSetupCard() = compose.lumioSnapshot("finish_setup_card") {
        FinishSetupCard(setup = FinishSetup(), onOpen = {})
    }

    @Test
    @Config(qualifiers = LUMIO_LIGHT_QUALIFIERS)
    fun finishSetupCardPairedLight() = compose.lumioSnapshot("finish_setup_card_paired_light") {
        FinishSetupCard(setup = FinishSetup(setOf(FinishItem.PAIR)), onOpen = {})
    }
}
