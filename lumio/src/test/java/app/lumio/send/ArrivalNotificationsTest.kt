package app.lumio.send

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The arrival notice shows only the cover's words, so nothing about the arrival reaches the notification shade. The channel name is the cover too.
 */
@RunWith(RobolectricTestRunner::class)
class ArrivalNotificationsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `arrival notice shows only the cover and names no coin, amount, address or crypto action`() {
        ArrivalNotifications(context).post("tx")

        val notification = manager.activeNotifications.single().notification
        val title = requireNotNull(notification.extras.getCharSequence(Notification.EXTRA_TITLE)).toString()
        val text = requireNotNull(notification.extras.getCharSequence(Notification.EXTRA_TEXT)).toString()
        assertEquals("Camera", title)
        assertEquals("Tap to open", text)
        assertNoForbiddenWords(title)
        assertNoForbiddenWords(text)
        assertEquals("Camera", manager.notificationChannels.single().name)
    }

    private fun assertNoForbiddenWords(visible: String) {
        assertFalse("'$visible' names a coin, amount, address or crypto action", FORBIDDEN.containsMatchIn(visible))
    }

    private companion object {
        val FORBIDDEN = Regex(
            "wallet|crypto|coin|token|receiv|arriv|transaction|\\btx\\b|\\bsend|\\bsent\\b|address|amount|bitcoin|\\bbtc\\b|ether|" +
                "\\beth\\b|solana|\\bsol\\b|algorand|\\balgo\\b|monad|\\bmon\\b|usdc|0x|bc1|\\d",
            RegexOption.IGNORE_CASE,
        )
    }
}
