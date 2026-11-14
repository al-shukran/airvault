package app.lensly.core.btc

import fr.acinq.bitcoin.Transaction
import kotlin.math.abs
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BtcFeeRateTest {
    private val seed = BitcoinVectors.seed()

    private fun account(type: ScriptType) = BtcAccount.fromSeed(seed, BtcNetwork.TESTNET4, type)

    @Test
    fun aNativeSegwitRequestOfOneInputAndTwoOutputsIsEstimatedAt141VBytes() {
        val account = account(ScriptType.NATIVE_SEGWIT)
        val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
        val tx = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 30_000L, 60_000L)).global.tx
        // 113 bytes without the witness, a 108-byte witness and the 2-unit segwit marker: 562 weight units, which is 141 vbytes.
        assertEquals(141L, BtcFeeRate.estimatedVsize(tx, ScriptType.NATIVE_SEGWIT))
    }

    @Test
    fun theEstimateIsWithinTwoVBytesOfTheSignedTransactionForEveryScriptType() {
        for (type in ScriptType.entries) {
            val account = account(type)
            val spend = BtcFixtures.Spend(BtcFixtures.funding(account, 0, 0, 100_000L), account, 0, 0)
            val psbt = BtcFixtures.psbt(listOf(spend), BtcFixtures.paying(account, 30_000L, 60_000L))
            val estimate = BtcFeeRate.estimatedVsize(psbt.global.tx, type)
            val tx = BtcFixtures.finalize(BtcSigner(account).sign(psbt).psbt, listOf(spend))
            val actual = (Transaction.weight(tx).toLong() + 3L) / 4L
            assertTrue(abs(estimate - actual) <= 2L, "${type.name}: estimated $estimate vbytes, signed $actual")
        }
    }
}
