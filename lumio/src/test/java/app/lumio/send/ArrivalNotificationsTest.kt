package app.lumio.send

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Every notice Lumio posts is safe before unlock: its visible version is private, and the channel and that version name no coin, amount, address
 * or crypto action. The arrival itself is in the private version, which the lock screen does not show.
 */
@RunWith(RobolectricTestRunner::class)
class ArrivalNotificationsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `arrival notice is private and its cover names no coin, amount, address or crypto action`() {
        ArrivalNotifications(context).post("tx", "Your MON Has Arrived", "0.25 MON to 0x00000000000000000000000000000000000000ab")

        val notification = manager.activeNotifications.single().notification
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)

        val cover = requireNotNull(notification.publicVersion)
        assertNoForbiddenWords(cover.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNoForbiddenWords(cover.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNoForbiddenWords(manager.notificationChannels.single().name)
    }

    private fun assertNoForbiddenWords(visible: CharSequence?) {
        val text = requireNotNull(visible).toString()
        assertFalse("'$text' names a coin, amount, address or crypto action", FORBIDDEN.containsMatchIn(text))
    }

    private companion object {
        val FORBIDDEN = Regex(
            "wallet|crypto|coin|token|receiv|arriv|transaction|\\btx\\b|\\bsend|\\bsent\\b|address|amount|bitcoin|\\bbtc\\b|ether|" +
                "\\beth\\b|solana|\\bsol\\b|algorand|\\balgo\\b|monad|\\bmon\\b|usdc|0x|bc1|\\d",
            RegexOption.IGNORE_CASE,
        )
    }
}
