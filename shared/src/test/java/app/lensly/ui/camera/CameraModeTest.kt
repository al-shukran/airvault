package app.lensly.ui.camera

import kotlin.test.assertEquals
import org.junit.Test

class CameraModeTest {

    @Test
    fun durationsReadAsMinutesAndPaddedSeconds() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:07", formatDuration(7_400L))
        assertEquals("12:05", formatDuration(725_999L))
    }
}
