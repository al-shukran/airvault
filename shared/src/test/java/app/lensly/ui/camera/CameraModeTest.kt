package app.lensly.ui.camera

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CameraModeTest {

    @Test
    fun theMicrophoneIsAskedOnTheFirstEntryToVideoWhenItIsNotAllowed() {
        assertTrue(shouldAskForMicrophone(granted = false, alreadyAsked = false))
    }

    @Test
    fun theMicrophoneIsNotAskedAgainOnLaterEntriesToVideo() {
        assertFalse(shouldAskForMicrophone(granted = false, alreadyAsked = true))
    }

    @Test
    fun theMicrophoneIsNotAskedWhenItIsAlreadyAllowed() {
        assertFalse(shouldAskForMicrophone(granted = true, alreadyAsked = false))
    }

    @Test
    fun durationsReadAsMinutesAndPaddedSeconds() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:07", formatDuration(7_400L))
        assertEquals("12:05", formatDuration(725_999L))
    }
}
