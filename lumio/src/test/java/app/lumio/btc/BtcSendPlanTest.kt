package app.lumio.btc

import app.lensly.core.btc.BtcAccount
import app.lensly.core.btc.BtcCoin
import app.lensly.core.btc.BtcFixtures
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.pair.PairFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Coin selection, fees, change and the owner's inputs, with no network. The vsize stub is 100 vbytes per input and 30 more with change. */
class BtcSendPlanTest {
    private val testnet4 = BtcNetwork.TESTNET4
    private val account = BtcAccount.fromSeed(PairFixtures.seed, testnet4, ScriptType.NATIVE_SEGWIT)
    private val watch = account.watchOnly()
    private val other = BtcAccount.fromSeed(PairFixtures.seed, testnet4, ScriptType.NATIVE_SEGWIT, accountIndex = 1).receiveAddress(5)

    private fun coin(sats: Long, index: Int, salt: Int): BtcCoin =
        BtcCoin(BtcFixtures.funding(account, 0, index, sats, salt), 0, 0, index)

    private val stubVsize: (List<BtcCoin>, Boolean) -> Long = { coins, withChange -> 100L * coins.size + if (withChange) 30L else 0L }

    @Test
    fun largestCoinsAreSpentFirstAndTheChangeIsPaidOut() {
        val coins = listOf(coin(10_000, 0, 1), coin(50_000, 1, 2), coin(20_000, 2, 3))
        val pick = chooseCoins(coins, amountSats = 30_000, feeRateSatPerVb = 1.0, vsizeOf = stubVsize)
        assertEquals(listOf(50_000L), pick.coins.map { it.valueSats })
        assertEquals(130L, pick.feeSats)
        assertEquals(19_870L, pick.changeSats)
    }

    @Test
    fun moreCoinsAreAddedUntilTheAmountAndTheFeeAreCovered() {
        val pick = chooseCoins(listOf(coin(20_000, 0, 1), coin(15_000, 1, 2)), 30_000, 1.0, stubVsize)
        assertEquals(2, pick.coins.size)
        // Two inputs with change: 230 vbytes at 1 sat/vB.
        assertEquals(230L, pick.feeSats)
        assertEquals(4_770L, pick.changeSats)
    }

    @Test
    fun aRemainderBelowDustGoesToTheFeeAndMakesNoChange() {
        val pick = chooseCoins(listOf(coin(30_200, 0, 1)), 30_000, 1.0, stubVsize)
        // With change the fee is 130, which leaves 70 sat, below dust. Without change the 200 left over is the fee.
        assertEquals(0L, pick.changeSats)
        assertEquals(200L, pick.feeSats)
    }

    @Test
    fun aBalanceThatDoesNotCoverTheFeeIsRefusedAndNoCoinsIsRefused() {
        assertFailsWith<IllegalArgumentException> { chooseCoins(listOf(coin(30_050, 0, 1)), 30_000, 1.0, stubVsize) }
        assertFailsWith<IllegalArgumentException> { chooseCoins(listOf(coin(10_000, 0, 1)), 30_000, 1.0, stubVsize) }
        assertFailsWith<IllegalArgumentException> { chooseCoins(emptyList(), 30_000, 1.0, stubVsize) }
    }

    @Test
    fun unmarkedCoinsAreSpentBeforeAMarkedCoinWhenTheyCoverTheAmount() {
        val marked = BtcCoin(BtcFixtures.funding(account, 0, 0, 50_000L, salt = 4), 0, 0, 0, riskMarked = true)
        val pick = chooseCoins(listOf(marked, coin(20_000, 1, 2), coin(15_000, 2, 3)), 30_000, 1.0, stubVsize)
        assertEquals(listOf(20_000L, 15_000L), pick.coins.map { it.valueSats })
        assertFalse(pick.coins.any { it.riskMarked })
    }

    @Test
    fun aMarkedCoinIsSpentOnlyWhenTheUnmarkedCoinsDoNotCoverTheAmountAndTheFee() {
        val marked = BtcCoin(BtcFixtures.funding(account, 0, 0, 50_000L, salt = 4), 0, 0, 0, riskMarked = true)
        val pick = chooseCoins(listOf(marked, coin(10_000, 1, 2)), 30_000, 1.0, stubVsize)
        assertEquals(listOf(10_000L, 50_000L), pick.coins.map { it.valueSats })
        assertEquals(listOf(false, true), pick.coins.map { it.riskMarked })
    }

    @Test
    fun theFeeIsTheRateTimesTheVsizeRoundedUp() {
        assertEquals(212L, feeFor(141, 1.5))
        assertEquals(141L, feeFor(141, 1.0))
    }

    @Test
    fun aSendWithChangePaysTheFeeOfItsOwnVsizeAndPaysTheChangeToTheNextUnusedAddress() {
        val spendable = BtcSpendable(listOf(coin(100_000, 0, 1)), nextChangeIndex = 3)
        val plan = BtcSend.plan(watch, spendable, other, amountSats = 30_000, feeRateSatPerVb = 2.0)
        assertEquals(3, plan.changeIndex)
        assertEquals(feeFor(plan.vsize, 2.0), plan.feeSats)
        assertEquals(100_000L - 30_000L - plan.feeSats, plan.changeSats)
        assertEquals(2, plan.tx.txOut.size)
    }

    @Test
    fun aRemainderTooSmallForChangeGoesToTheFeeAndNoChangeOutputIsBuilt() {
        val spendable = BtcSpendable(listOf(coin(30_200, 0, 1)), nextChangeIndex = 0)
        val plan = BtcSend.plan(watch, spendable, other, amountSats = 30_000, feeRateSatPerVb = 1.0)
        assertNull(plan.changeIndex)
        assertEquals(0L, plan.changeSats)
        assertEquals(200L, plan.feeSats)
        assertEquals(1, plan.tx.txOut.size)
    }

    @Test
    fun aPlanRefusesARecipientOfAnotherNetworkAndAnAmountBelowDust() {
        val spendable = BtcSpendable(listOf(coin(100_000, 0, 1)), nextChangeIndex = 0)
        val mainnet = BtcAccount.fromSeed(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT).receiveAddress(0)
        assertFailsWith<IllegalArgumentException> { BtcSend.plan(watch, spendable, mainnet, 30_000, 2.0) }
        assertFailsWith<IllegalArgumentException> { BtcSend.plan(watch, spendable, other, 100, 2.0) }
    }

    @Test
    fun amountsAreBitcoinWithAtMostEightDecimalsAndAtLeastTheDustLimit() {
        assertEquals(100_000L, BtcSendInput.amountSats("0.001"))
        assertEquals(546L, BtcSendInput.amountSats(" 0.00000546 "))
        assertFailsWith<IllegalArgumentException> { BtcSendInput.amountSats("0.000000001") }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.amountSats("0.000005") }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.amountSats("-1") }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.amountSats("1e3") }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.amountSats("21000001") }
    }

    @Test
    fun feeRatesAreFromOneToAThousandSatPerVbyte() {
        assertEquals(2.0, BtcSendInput.feeRate("2"))
        assertEquals(1.5, BtcSendInput.feeRate(" 1.5 "))
        assertFailsWith<IllegalArgumentException> { BtcSendInput.feeRate("0.5") }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.feeRate("1001") }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.feeRate("") }
    }

    @Test
    fun aRecipientMustBeAnAddressOfTheNetworkTheAccountIsOn() {
        val mainnet = BtcAccount.fromSeed(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT).receiveAddress(0)
        assertEquals(other, BtcSendInput.recipient(" $other ", testnet4))
        assertFailsWith<IllegalArgumentException> { BtcSendInput.recipient(mainnet, testnet4) }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.recipient(other, BtcNetwork.MAINNET) }
        assertFailsWith<IllegalArgumentException> { BtcSendInput.recipient("not an address", testnet4) }
    }

    @Test
    fun theStartingFeeRateIsTheThreeBlockEstimateRoundedUp() {
        assertTrue(BtcSend.feeSuggestion(mapOf(1 to 87.5, 3 to 2.3, 6 to 1.0)).fromEstimate)
        assertEquals(3.0, BtcSend.feeSuggestion(mapOf(1 to 87.5, 3 to 2.3, 6 to 1.0)).satPerVb)
    }

    @Test
    fun withoutTheThreeBlockTargetTheNextLongerWithinAnHourIsUsed() {
        assertEquals(12.0, BtcSend.feeSuggestion(mapOf(1 to 87.5, 6 to 12.0)).satPerVb)
        assertEquals(5.0, BtcSend.feeSuggestion(mapOf(2 to 4.2)).satPerVb, "only a shorter target: its rate, rounded up")
        assertTrue(BtcSend.feeSuggestion(mapOf(2 to 4.2)).fromEstimate)
    }

    @Test
    fun aServerWithNoEstimateWithinAnHourGivesTheFallbackAndSaysSo() {
        // blockstream.info's testnet4 answers only with targets of 144 blocks and longer.
        val testnet4 = BtcSend.feeSuggestion(mapOf(144 to 0.495, 504 to 590770.2, 1008 to 0.495))
        assertEquals(FALLBACK_FEE_RATE_SAT_PER_VB, testnet4.satPerVb)
        assertFalse(testnet4.fromEstimate)
        assertEquals(FALLBACK_FEE_RATE_SAT_PER_VB, BtcSend.feeSuggestion(null).satPerVb)
        assertFalse(BtcSend.feeSuggestion(emptyMap()).fromEstimate)
    }

    @Test
    fun anEstimateBelowOneIsRoundedUpToTheMinimumRate() {
        assertEquals(1.0, BtcSend.feeSuggestion(mapOf(3 to 0.4)).satPerVb)
    }

    @Test
    fun anEstimateAboveTheMaximumStartsAtTheMaximumTheFieldAccepts() {
        val suggestion = BtcSend.feeSuggestion(mapOf(3 to 1500.2))

        assertEquals(1000.0, suggestion.satPerVb)
        assertTrue(suggestion.fromEstimate)
        assertEquals(suggestion.satPerVb, BtcSendInput.feeRate(BtcSend.formatFeeRate(suggestion.satPerVb)), "the fee field takes its own start")
    }

    @Test
    fun anOwnerRateIsNeverReplacedBySuggestion() {
        assertEquals("7", BtcSend.fieldAfterSuggestion(current = "7", edited = true, suggestion = 3.0))
        assertEquals("", BtcSend.fieldAfterSuggestion(current = "", edited = true, suggestion = 3.0))
        assertEquals("3", BtcSend.fieldAfterSuggestion(current = "", edited = false, suggestion = 3.0))
        assertEquals("2", BtcSend.fieldAfterSuggestion(current = "2", edited = false, suggestion = 2.0))
    }
}
