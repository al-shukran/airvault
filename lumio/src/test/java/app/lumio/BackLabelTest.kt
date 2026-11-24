package app.lumio

import kotlin.test.assertEquals
import org.junit.Test

/**
 * A back arrow names the screen that Back returns to: a tab by its name, and the Data Providers, Networks and Test Networks lists by theirs. The Add
 * Custom Network page is opened from both the Networks and the Test Networks lists, so its arrow reads the list beneath it.
 */
class BackLabelTest {

    @Test
    fun aTabNamesItself() {
        assertEquals("Explore", backLabelOf(LumioRoute.EXPLORE))
        assertEquals("Settings", backLabelOf(LumioRoute.SETTINGS))
    }

    @Test
    fun theListsNameThemselves() {
        assertEquals("Data Providers", backLabelOf(LumioRoute.DATA_PROVIDERS))
        assertEquals("Networks", backLabelOf(LumioRoute.SETTINGS_NETWORKS))
        assertEquals("Test Networks", backLabelOf(LumioRoute.TEST_NETWORKS))
    }
}
