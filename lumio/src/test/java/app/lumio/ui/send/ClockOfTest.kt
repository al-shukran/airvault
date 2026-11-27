package app.lumio.ui.send

import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import org.junit.Test

class ClockOfTest {
    private val at = Instant.parse("2026-11-22T14:02:00Z").toEpochMilli()

    @Test
    fun aStepTimeIsTwentyFourHoursAndMinutesInItsZone() {
        assertEquals("14:02", clockOf(at, ZoneId.of("UTC")))
        assertEquals("19:32", clockOf(at, ZoneId.of("Asia/Kolkata")))
    }

    /** The test JVM runs in UTC on every host (lumio/build.gradle.kts), so a screen renders 14:02 on a Mac in any zone as CI does. */
    @Test
    fun theTestJvmRunsInUtcSoARenderDoesNotDependOnTheHost() {
        assertEquals("14:02", clockOf(at))
    }
}
