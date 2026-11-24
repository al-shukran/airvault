package app.lumio.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Test

class GateCopyTest {
    @Test
    fun theCountdownReadsSecondsThenMinutesAndRoundsUp() {
        assertEquals("1 s", waitCountdown(1L))
        assertEquals("38 s", waitCountdown(38_000L))
        assertEquals("1 min", waitCountdown(60_000L))
        assertEquals("4 min 10 s", waitCountdown(250_000L))
    }

    @Test
    fun theEraseWarningNamesLumio() {
        assertEquals("1 try left before Lumio is erased", lumioEraseWarning(1))
        assertEquals("2 tries left before Lumio is erased", lumioEraseWarning(2))
    }
}
