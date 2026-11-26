package app.lumio.send

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `arrival notice carries no tag, so it cannot name the transaction`() {
        ArrivalNotifications(context).post(TX_ID)

        assertNull(manager.activeNotifications.single().tag)
    }

    @Test
    fun `different arrivals stack and a repeated arrival updates its notice`() {
        val notices = ArrivalNotifications(context)

        notices.post(TX_ID)
        notices.post(OTHER_TX_ID)
        assertEquals(2, manager.activeNotifications.size)

        notices.post(TX_ID)
        assertEquals(2, manager.activeNotifications.size)
    }

    @Test
    fun `an arrival after a restart stacks on the notices still shown instead of replacing one`() {
        ArrivalNotifications(context).apply {
            post(TX_ID)
            post(OTHER_TX_ID)
        }

        ArrivalNotifications(context).post(THIRD_TX_ID)

        assertEquals(3, manager.activeNotifications.size)
    }

    private fun assertNoForbiddenWords(visible: String) {
        assertFalse("'$visible' names a coin, amount, address or crypto action", FORBIDDEN.containsMatchIn(visible))
    }

    private companion object {
        const val TX_ID = "tx-4c9e1b7a"
        const val OTHER_TX_ID = "tx-81d03f5e"
        const val THIRD_TX_ID = "tx-2b7f90ac"
        val FORBIDDEN = Regex(
            "wallet|crypto|coin|token|receiv|arriv|transaction|\\btx\\b|\\bsend|\\bsent\\b|address|amount|bitcoin|\\bbtc\\b|ether|" +
                "\\beth\\b|solana|\\bsol\\b|algorand|\\balgo\\b|monad|\\bmon\\b|usdc|0x|bc1|\\d",
            RegexOption.IGNORE_CASE,
        )
    }
}
