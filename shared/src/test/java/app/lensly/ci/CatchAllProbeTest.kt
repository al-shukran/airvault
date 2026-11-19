package app.lensly.ci

import org.junit.Assert.assertTrue
import org.junit.Test

class CatchAllProbeTest {
    @Test
    fun runsWithoutAShardsLine() {
        assertTrue(true)
    }
}
