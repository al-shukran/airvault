package app.lumio.btc

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** What a Bitcoin refresh says when some accounts could not be reached: the read ones show, and the missed ones are named. */
class RefreshNoticeTest {
    private val failure = "Could not reach the servers."

    @Test
    fun noNoticeWhenEveryAccountWasRead() {
        assertNull(BtcPortfolio.refreshNotice(read = 2, missed = 0, message = failure))
    }

    @Test
    fun theFailureAloneWhenNoAccountWasRead() {
        assertEquals(failure, BtcPortfolio.refreshNotice(read = 0, missed = 2, message = failure))
    }

    @Test
    fun aPartialRefreshSaysHowManyAccountsMissedAndThatTheRestShowTheirLastKnownBalance() {
        assertEquals(
            "1 of 3 accounts could not be reached. Their last known balances are shown.",
            BtcPortfolio.refreshNotice(read = 2, missed = 1, message = failure),
        )
    }
}
