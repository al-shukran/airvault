package app.lumio.data.tx

import app.lumio.btc.EsploraInput
import app.lumio.btc.EsploraOutput
import app.lumio.btc.EsploraPrevout
import app.lumio.btc.EsploraTx
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The Esplora transactions of the account, mapped to [LumioTx]: direction, fee, counterparty, confirmations and the pending time. */
class BtcTxMappingTest {
    private val net = NetworkId(Networks.BTC_MAINNET, testnet = false)
    private val me = "bc1qmine"
    private val other = "bc1qother"
    private val tip = 800_000L
    private val observedAtMs = 1_700_000_000_000L
    private val txid = "ab".repeat(32)

    /** A transaction whose inputs and outputs are given as (address, sats) pairs. A null address is a script with no address. */
    private fun tx(
        inputs: List<Pair<String?, Long>>,
        outputs: List<Pair<String?, Long>>,
        height: Long? = 799_990L,
        time: Long? = 1_699_999_000L,
    ) = EsploraTx(
        txid = txid,
        inputs = inputs.map { (address, sats) -> EsploraInput(EsploraPrevout(address, sats)) },
        outputs = outputs.map { (address, sats) -> EsploraOutput(address, sats) },
        blockHeight = height,
        blockTimeSec = time,
    )

    private fun map(t: EsploraTx) = t.toLumioTx(me, net, tipHeight = tip, observedAtMs = observedAtMs)

    @Test
    fun aPaymentToTheAccountIsInWithTheSenderAsCounterparty() {
        val tx = map(tx(inputs = listOf(other to 50_000L), outputs = listOf(me to 40_000L, other to 9_000L)))

        assertEquals(TxDirection.IN, tx.direction)
        assertEquals(BigInteger.valueOf(40_000), tx.amount)
        assertEquals(other, tx.counterparty)
        assertNull(tx.fee)
        assertEquals(TxStatus.CONFIRMED, tx.status)
        assertEquals(tip - 799_990L + 1, tx.confirmations)
        assertEquals(1_699_999_000_000L, tx.timestampMs)
        assertEquals(BTC_ASSET, tx.asset)
    }

    @Test
    fun aPaymentFromTheAccountIsOutWithItsValueAndTheFee() {
        val tx = map(tx(inputs = listOf(me to 100_000L), outputs = listOf(other to 60_000L, me to 39_000L)))

        assertEquals(TxDirection.OUT, tx.direction)
        assertEquals(BigInteger.valueOf(60_000), tx.amount)
        assertEquals(other, tx.counterparty)
        assertEquals(BigInteger.valueOf(1_000), tx.fee)
    }

    @Test
    fun aTransactionThatOnlyMovesTheAccountsOwnCoinsIsSelf() {
        val tx = map(tx(inputs = listOf(me to 100_000L), outputs = listOf(me to 99_000L)))

        assertEquals(TxDirection.SELF, tx.direction)
        assertEquals(BigInteger.valueOf(99_000), tx.amount)
        assertEquals(BigInteger.valueOf(1_000), tx.fee)
        assertNull(tx.counterparty)
    }

    @Test
    fun aSpendThatSharesItsInputsWithAnotherPartyHasNoKnownFee() {
        val tx = map(tx(inputs = listOf(me to 30_000L, other to 70_000L), outputs = listOf(other to 90_000L, me to 5_000L)))

        assertEquals(TxDirection.OUT, tx.direction)
        assertEquals(BigInteger.valueOf(90_000), tx.amount)
        assertNull(tx.fee)
    }

    @Test
    fun aCoinbaseOutputIsInWithNoCounterparty() {
        val coinbase = EsploraTx(
            txid = txid,
            inputs = listOf(EsploraInput(prevout = null)),
            outputs = listOf(EsploraOutput(me, 625_000_000L)),
            blockHeight = 799_990L,
            blockTimeSec = 1_699_999_000L,
        )

        val tx = map(coinbase)

        assertEquals(TxDirection.IN, tx.direction)
        assertEquals(BigInteger.valueOf(625_000_000), tx.amount)
        assertNull(tx.counterparty)
    }

    @Test
    fun aTransactionNotYetInABlockIsPendingWithTheObservedTime() {
        val tx = map(tx(inputs = listOf(other to 50_000L), outputs = listOf(me to 40_000L), height = null, time = null))

        assertEquals(TxStatus.PENDING, tx.status)
        assertEquals(0L, tx.confirmations)
        assertEquals(observedAtMs, tx.timestampMs)
    }

    @Test
    fun anInconsistentFeeIsUnknownNotNegative() {
        val tx = map(tx(inputs = listOf(me to 1_000L), outputs = listOf(me to 2_000L)))

        assertEquals(TxDirection.SELF, tx.direction)
        assertNull(tx.fee)
    }

    @Test
    fun theIdNamesTheNetworkAndTheTransaction() {
        assertEquals("BTC:mainnet:$txid", map(tx(inputs = listOf(other to 1_000L), outputs = listOf(me to 900L))).id)
    }
}
