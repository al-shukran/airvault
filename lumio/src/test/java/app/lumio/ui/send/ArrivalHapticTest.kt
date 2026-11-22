package app.lumio.ui.send

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import app.lumio.ui.kit.LumioHaptics
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The confirm haptic of an arrival: once when the send lands while its page is open, and never for a page opened after it had landed. */
@RunWith(RobolectricTestRunner::class)
class ArrivalHapticTest {
    @get:Rule
    val compose = createComposeRule()

    private val felt = ArrayList<Int>()

    /** A view that records the haptic feedback it is asked for, instead of vibrating. */
    private class FeltView(context: Context, private val felt: MutableList<Int>) : View(context) {
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            felt += feedbackConstant
            return true
        }
    }

    @Test
    fun theConfirmHapticPlaysOnceWhenTheSendLandsWhileThePageIsOpen() {
        val arrived = mutableStateOf(false)
        compose.setContent {
            val context = LocalContext.current
            val view = remember { FeltView(context, felt) }
            ArrivalHaptic(arrived = arrived.value, id = "tx", haptics = LumioHaptics(view))
        }

        compose.runOnIdle { arrived.value = true }
        compose.waitForIdle()

        assertEquals(listOf(HapticFeedbackConstants.CONFIRM), felt)
    }

    @Test
    fun aPageOpenedAfterTheSendLandedPlaysNoHaptic() {
        compose.setContent {
            val context = LocalContext.current
            val view = remember { FeltView(context, felt) }
            ArrivalHaptic(arrived = true, id = "tx", haptics = LumioHaptics(view))
        }

        compose.waitForIdle()

        assertEquals(emptyList<Int>(), felt)
    }
}
