package app.lensly.ui.home

import kotlin.test.assertEquals
import org.junit.Test

/** The collage's logos: chains and tokens alternate, and the longer list keeps its tail. */
class HomeCollageTest {

    @Test
    fun chainsAndTokensAlternateUntilOneListRunsOut() {
        assertEquals(listOf(1, 10, 2, 20, 3), interleaveLogos(listOf(1, 2, 3), listOf(10, 20)))
    }
}
