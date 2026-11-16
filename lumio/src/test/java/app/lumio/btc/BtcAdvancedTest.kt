package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.pair.PairFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

/** The Bitcoin advanced sheet: the fee rate is the form's rule (1 to 1000 sat/vB), its warning, and a request rebuilt at the rate kept. */
class BtcAdvancedTest {
    private val testnet4 = BtcNetwork.TESTNET4
    private val account = BtcAccount.fromSeed(PairFixtures.seed, testnet4, ScriptType.NATIVE_SEGWIT)
    private val watch = account.watchOnly()
    private val other = BtcAccount.fromSeed(PairFixtures.seed, testnet4, ScriptType.NATIVE_SEGWIT, accountIndex = 1).receiveAddress(5)

    private fun coin(sats: Long, index: Int, salt: Int): BtcCoin =
        BtcCoin(BtcFixtures.funding(account, 0, index, sats, salt), 0, 0, index)

    @Test
    fun aFeeRateIsTheFormsRuleOfOneToAThousandSatPerVb() {
        assertEquals(1.0, BtcSendInput.feeRate("1"))
        assertEquals(2.5, BtcSendInput.feeRate(" 2.5 "))
        assertFailsWith<IllegalArgumentException> { BtcSendInput.feeRate("0") }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.feeRate("0.5") }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.feeRate("1001") }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.feeRate("") }
    }

    @Test
    fun aRateMoreThanThreeTimesTheSuggestedOneIsWarned() {
        assertEquals(listOf("The fee rate is more than 3 times the suggested rate."), BtcAdvanced.warnings(2.0, 7.0))
        assertTrue(BtcAdvanced.warnings(2.0, 6.0).isEmpty())
    }

    @Test
    fun theRequestIsRebuiltAtTheEditedRateWithTheSameCoinsAndTheFeeFollowsIt() {
        val spendable = BtcSpendable(listOf(coin(100_000, 0, 1)), nextChangeIndex = 3)
        val low = BtcSend.plan(watch, spendable, other, amountSats = 30_000, feeRateSatPerVb = 2.0)
        val edited = BtcSend.plan(watch, spendable, other, amountSats = 30_000, feeRateSatPerVb = BtcSendInput.feeRate("10"))

        assertEquals(low.coins, edited.coins)
        assertEquals(10.0, edited.feeRateSatPerVb)
        assertEquals(feeFor(edited.vsize, 10.0), edited.feeSats)
        assertTrue(edited.changeSats < low.changeSats)
        // The unsigned transaction the request carries pays the fee that the owner was shown: the inputs less the outputs.
        val paid = edited.tx.txOut.sumOf { it.amount.sat }
        assertEquals(100_000L, paid + edited.feeSats)
    }
}
