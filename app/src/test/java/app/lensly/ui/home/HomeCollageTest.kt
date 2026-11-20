package app.lensly.ui.home

import app.lensly.ui.components.LogoTone
import app.lensly.ui.components.chainIconDrawables
import app.lensly.ui.components.logoTone
import app.lensly.ui.components.tokenIconDrawables
import kotlin.test.assertEquals
import org.junit.Test

/** The collage's logos: chains and tokens alternate, and the longer list keeps its tail. */
class HomeCollageTest {

    @Test
    fun chainsAndTokensAlternateUntilOneListRunsOut() {
        assertEquals(listOf(1, 10, 2, 20, 3), interleaveLogos(listOf(1, 2, 3), listOf(10, 20)))
    }

    @Test
    fun aLogoTooDarkForADarkTileIsToneDark() {
        assertEquals(LogoTone.DARK, logoTone(chainIconDrawables.getValue("chain_base")))
        assertEquals(LogoTone.DARK, logoTone(tokenIconDrawables.getValue("token_bal")))
    }

    @Test
    fun aBrightLogoIsNeutral() {
        assertEquals(LogoTone.NEUTRAL, logoTone(chainIconDrawables.getValue("chain_arbitrum_one")))
        assertEquals(LogoTone.NEUTRAL, logoTone(tokenIconDrawables.getValue("token_aero")))
    }

    @Test
    fun aLogoTooPaleForALightTileIsTonePale() {
        assertEquals(LogoTone.PALE, logoTone(tokenIconDrawables.getValue("token_wbtc")))
    }
}
