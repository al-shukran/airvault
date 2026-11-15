package app.lumio.btc

import kotlin.test.assertEquals
import org.junit.Test

/** What a transaction does to an account, from the amounts that cross its addresses. */
class NetAmountTest {
    private val mine = "tb1qmine"
    private val myChange = "tb1qchange"
    private val owned = setOf(mine, myChange)

    @Test
    fun aSpendThatReturnsChangeIsNegativeByTheAmountSentAndTheFee() {
        // 1,000 in from mine; 300 out to a stranger, 600 back to my change. The fee of 100 leaves the account.
        val tx = tx(
            inputs = listOf(EsploraInput(EsploraPrevout(mine, 1_000)), EsploraInput(EsploraPrevout("tb1qstranger", 50_000))),
            outputs = listOf(EsploraOutput("tb1qstranger", 300), EsploraOutput(myChange, 600), EsploraOutput("tb1qother", 49_000)),
        )

        assertEquals(-400L, netSats(tx, owned))
    }

    @Test
    fun aPaymentReceivedIsPositiveByWhatTheAccountGets() {
        val tx = tx(
            inputs = listOf(EsploraInput(EsploraPrevout("tb1qstranger", 9_000))),
            outputs = listOf(EsploraOutput(mine, 5_000), EsploraOutput("tb1qstranger", 3_900)),
        )

        assertEquals(5_000L, netSats(tx, owned))
    }

    @Test
    fun aCoinbaseInputHasNoPrevoutAndCountsForNothing() {
        val tx = tx(inputs = listOf(EsploraInput(null)), outputs = listOf(EsploraOutput(mine, 625_000_000)))

        assertEquals(625_000_000L, netSats(tx, owned))
    }

    @Test
    fun aTransactionThatDoesNotTouchTheAccountIsZero() {
        val tx = tx(
            inputs = listOf(EsploraInput(EsploraPrevout("tb1qstranger", 2_000))),
            outputs = listOf(EsploraOutput("tb1qother", 1_900), EsploraOutput(null, 0)),
        )

        assertEquals(0L, netSats(tx, owned))
    }

    @Test
    fun aSelfTransferBetweenOwnAddressesNetsOnlyTheFee() {
        val tx = tx(
            inputs = listOf(EsploraInput(EsploraPrevout(mine, 1_000))),
            outputs = listOf(EsploraOutput(myChange, 900)),
        )

        assertEquals(-100L, netSats(tx, owned))
    }

    private fun tx(inputs: List<EsploraInput>, outputs: List<EsploraOutput>) = EsploraTx(
        txid = "tx",
        inputs = inputs,
        outputs = outputs,
        blockHeight = null,
        blockTimeSec = null,
    )
}
