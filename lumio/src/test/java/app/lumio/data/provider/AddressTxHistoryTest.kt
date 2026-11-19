package app.lumio.data.provider

import app.lumio.data.ETH
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

/** A history request names an EVM address and a page that holds 1 to [MAX_TX_PAGE] rows, counted from page 1. */
class AddressTxHistoryTest {
    private val account = "0x" + "ab".repeat(20)

    @Test
    fun anEvmAddressAndAFullPageAreAccepted() {
        val request = AddressTxHistory(ETH, account, page = 1, offset = MAX_TX_PAGE)

        assertEquals(Ops.EVM_TX_HISTORY, request.op)
    }

    @Test
    fun aBitcoinOrMalformedAddressIsRefused() {
        assertFailsWith<IllegalArgumentException> { AddressTxHistory(ETH, "bc1qexample", page = 1, offset = 20) }
        assertFailsWith<IllegalArgumentException> { AddressTxHistory(ETH, "0x" + "ab".repeat(19), page = 1, offset = 20) }
    }

    @Test
    fun aPageThatCountsFromZeroOrHoldsNoRowsOrTooManyIsRefused() {
        assertFailsWith<IllegalArgumentException> { AddressTxHistory(ETH, account, page = 0, offset = 20) }
        assertFailsWith<IllegalArgumentException> { AddressTxHistory(ETH, account, page = 1, offset = 0) }
        assertFailsWith<IllegalArgumentException> { AddressTxHistory(ETH, account, page = 1, offset = MAX_TX_PAGE + 1) }
    }
}
