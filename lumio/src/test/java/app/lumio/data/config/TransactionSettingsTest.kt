package app.lumio.data.config

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** What the transaction settings make of a read: whether Activity includes spam, which is the effect of Hide Spam. */
class TransactionSettingsTest {

    @Test
    fun hidingSpamLeavesItOutOfActivityAndShowingItPutsItBack() {
        assertFalse(TransactionSettings().activityIncludesSpam)
        assertTrue(TransactionSettings(hideSpam = false).activityIncludesSpam)
    }
}
