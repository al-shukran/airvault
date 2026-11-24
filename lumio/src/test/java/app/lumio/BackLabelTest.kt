package app.lumio

import kotlin.test.assertEquals
import org.junit.Test

/** A back arrow names the screen that Back returns to: a tab by its name, and the Data Providers and Networks lists by theirs. */
class BackLabelTest {

    @Test
    fun aTabNamesItself() {
        assertEquals("Explore", backLabelOf(LumioRoute.EXPLORE))
        assertEquals("Settings", backLabelOf(LumioRoute.SETTINGS))
    }

    @Test
    fun theDataProvidersAndTheNetworksListNameThemselves() {
        assertEquals("Data Providers", backLabelOf(LumioRoute.DATA_PROVIDERS))
        assertEquals("Networks", backLabelOf(LumioRoute.SETTINGS_NETWORKS))
    }
}
