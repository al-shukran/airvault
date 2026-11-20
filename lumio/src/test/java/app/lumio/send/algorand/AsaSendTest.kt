package app.lumio.send.algorand

import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.signer.AlgoTxnDecoder
import app.lensly.core.algorand.AlgoTxnEncoder
import app.lumio.data.provider.AlgorandSuggestedParams
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/** The plan of an asset send and of an asset opt-in: the rules the network applies, and the bytes Lensly is asked to sign. */
class AsaSendTest {
    private val signer = ByteArray(32) { 9 }
    private val recipient = ByteArray(32) { 8 }
    private val usdc = 10458941UL
    private val params = AlgorandSuggestedParams(
        genesisId = "testnet-v1.0",
        genesisHash = ByteArray(32) { 1 },
        lastRound = 50_000_000L,
        feePerByte = 0L,
        minFee = 1_000L,
    )

    /** The ALGO that a transfer costs: the fee, which is the minimum fee here, and the minimum balance that must stay. */
    private val transferFunds = AlgorandFunds(BigInteger.valueOf(1_000L + AlgorandSend.MIN_BALANCE), params)
    private val optInFunds = AlgorandFunds(BigInteger.valueOf(1_000L + AlgorandSend.MIN_BALANCE + ASSET_MIN_BALANCE), params)

    private fun transfer(
        funds: AlgorandFunds = transferFunds,
        amount: BigInteger = BigInteger("2500000"),
        held: BigInteger = BigInteger("3000000"),
        optedIn: Boolean = true,
    ): AsaSendPlan = AsaSend.plan(
        network = ALGORAND_TESTNET,
        funds = funds,
        fingerprint = 1L,
        accountIndex = 0,
        signer = signer,
        to = recipient,
        assetId = usdc,
        amount = amount,
        held = held,
        recipientOptedIn = optedIn,
    )

    @Test
    fun aTransferToAnOptedInRecipientIsTheAssetTransferOfThePlanWithTheMinimumFee() {
        val plan = transfer()

        val expected = AssetTransfer(
            sender = signer,
            fee = 1_000UL,
            firstValid = 50_000_000UL,
            lastValid = 50_001_000UL,
            genesisId = "testnet-v1.0",
            genesisHash = ByteArray(32) { 1 },
            note = ByteArray(0),
            lease = null,
            group = null,
            rekeyTo = null,
            assetId = usdc,
            amount = 2_500_000UL,
            receiver = recipient,
            closeTo = null,
            assetSender = null,
        )
        assertContentEquals(AlgoTxnEncoder.encodeAssetTransfer(expected), plan.txnBytes)
        assertEquals(BigInteger.valueOf(1_000L), plan.fee)
        assertEquals(usdc, plan.assetId)
        assertIs<AssetTransfer>(AlgoTxnDecoder.decode(plan.txnBytes))
    }

    @Test
    fun aRecipientWhoHasNotOptedInIsRefusedWithTheOwnersWords() {
        val refused = assertFailsWith<IllegalArgumentException> { transfer(optedIn = false) }

        assertEquals(AsaSend.NOT_OPTED_IN, refused.message)
    }

    @Test
    fun anAmountThatIsNotPositiveOrMoreThanTheAccountHoldsIsRefused() {
        assertEquals(AsaSend.NOT_HELD, assertFailsWith<IllegalArgumentException> { transfer(held = BigInteger("2499999")) }.message)
        assertFailsWith<IllegalArgumentException> { transfer(amount = BigInteger.ZERO) }
    }

    @Test
    fun theTransferNeedsTheFeeAndTheMinimumBalanceAndNoMore() {
        val short = AlgorandFunds(BigInteger.valueOf(1_000L + AlgorandSend.MIN_BALANCE - 1), params)

        assertEquals(AsaSend.NOT_ENOUGH_ALGO, assertFailsWith<IllegalArgumentException> { transfer(funds = short) }.message)
        assertTrue(transfer(funds = transferFunds).txnBytes.isNotEmpty())
    }

    @Test
    fun anOptInIsAZeroTransferToTheSignerAndNeedsTheAssetsMinimumBalanceToo() {
        val plan = AsaSend.optIn(ALGORAND_TESTNET, optInFunds, fingerprint = 1L, accountIndex = 0, signer = signer, assetId = usdc)

        val optIn = assertIs<AssetTransfer>(AlgoTxnDecoder.decode(plan.txnBytes))
        assertTrue(optIn.isOptIn)
        assertContentEquals(signer, optIn.receiver)
        assertEquals(usdc, optIn.assetId)
        assertEquals(BigInteger.ZERO, plan.amount)
    }

    @Test
    fun anOptInWithoutTheAssetsExtraMinimumBalanceIsRefused() {
        val short = AlgorandFunds(BigInteger.valueOf(1_000L + AlgorandSend.MIN_BALANCE + ASSET_MIN_BALANCE - 1), params)

        assertEquals(AsaSend.NOT_ENOUGH_ALGO, assertFailsWith<IllegalArgumentException> {
            AsaSend.optIn(ALGORAND_TESTNET, short, fingerprint = 1L, accountIndex = 0, signer = signer, assetId = usdc)
        }.message)
    }

    @Test
    fun aTransferKeepsTheMinimumBalanceOfEveryAssetTheAccountHolds() {
        val held = listOf(AsaHolding(usdc, BigInteger("3000000"), frozen = false), AsaHolding(7UL, BigInteger.ONE, frozen = false))
        val short = AlgorandFunds(BigInteger.valueOf(1_000L + 3 * AlgorandSend.MIN_BALANCE - 1), params, held)
        val enough = AlgorandFunds(BigInteger.valueOf(1_000L + 3 * AlgorandSend.MIN_BALANCE), params, held)

        assertEquals(AsaSend.NOT_ENOUGH_ALGO, assertFailsWith<IllegalArgumentException> { transfer(funds = short) }.message)
        assertTrue(transfer(funds = enough).txnBytes.isNotEmpty())
    }

    @Test
    fun anOptInKeepsTheMinimumBalanceOfEveryAssetTheAccountHoldsAndTheNewOne() {
        val held = listOf(AsaHolding(usdc, BigInteger("3000000"), frozen = false), AsaHolding(7UL, BigInteger.ONE, frozen = false))
        val short = AlgorandFunds(BigInteger.valueOf(1_000L + 4 * AlgorandSend.MIN_BALANCE - 1), params, held)
        val enough = AlgorandFunds(BigInteger.valueOf(1_000L + 4 * AlgorandSend.MIN_BALANCE), params, held)

        assertEquals(AsaSend.NOT_ENOUGH_ALGO, assertFailsWith<IllegalArgumentException> {
            AsaSend.optIn(ALGORAND_TESTNET, short, fingerprint = 1L, accountIndex = 0, signer = signer, assetId = 9UL)
        }.message)
        assertTrue(AsaSend.optIn(ALGORAND_TESTNET, enough, fingerprint = 1L, accountIndex = 0, signer = signer, assetId = 9UL).isOptIn)
    }

    @Test
    fun aPlanGivesLenslyARequestToSign() {
        val plan = transfer()

        assertTrue(AlgorandSend.requestParts(plan, ByteArray(16) { 7 }).isNotEmpty())
    }
}
