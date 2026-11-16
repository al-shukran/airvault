package app.lensly.ui.home

import app.lensly.ui.components.chainIconDrawables
import app.lensly.ui.components.tokenIconDrawables
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** The collage's logos: chains and tokens alternate, and the longer list keeps its tail. */
class HomeCollageTest {

    @Test
    fun chainsAndTokensAlternateUntilOneListRunsOut() {
        assertEquals(listOf(1, 10, 2, 20, 3), interleaveLogos(listOf(1, 2, 3), listOf(10, 20)))
    }

    @Test
    fun aLogoTooDarkForTheDarkTileSitsOnALightDisc() {
        assertTrue(logoOnLightDisc(chainIconDrawables.getValue("chain_base")))
        assertTrue(logoOnLightDisc(tokenIconDrawables.getValue("token_bal")))
    }

    @Test
    fun aBrightLogoSitsOnTheDarkTile() {
        assertFalse(logoOnLightDisc(chainIconDrawables.getValue("chain_arbitrum_one")))
        assertFalse(logoOnLightDisc(tokenIconDrawables.getValue("token_aero")))
    }
}
