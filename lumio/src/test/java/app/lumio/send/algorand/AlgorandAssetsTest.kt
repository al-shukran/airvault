package app.lumio.send.algorand

import app.lumio.data.provider.AlgorandSuggestedParams
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.AsaInfo
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

/** The coins an Algorand send offers, and the plan that each coin's send routes to, including the opt-in of the Add asset screen. */
class AlgorandAssetsTest {
    private val usdc = 10458941UL
    private val held = 1234UL
    private val infos = mapOf(
        usdc to AsaInfo(decimals = 6, unitName = "USDC", name = "USD Coin"),
        held to AsaInfo(decimals = 0, unitName = "", name = ""),
    )
    private val signer = ByteArray(32) { 9 }
    private val recipient = ByteArray(32) { 8 }
    private val funds = AlgorandFunds(
        balance = BigInteger.valueOf(10_000_000L),
        params = AlgorandSuggestedParams(
            genesisId = "testnet-v1.0",
            genesisHash = ByteArray(32) { 1 },
            lastRound = 50_000_000L,
            feePerByte = 0L,
            minFee = 1_000L,
        ),
    )

    @Test
    fun theChoicesAreAlgoThenTheAssetsTheAccountHolds() {
        val holdings = listOf(AsaHolding(usdc, BigInteger("2500000"), false), AsaHolding(held, BigInteger("7"), false))

        val choices = AlgorandAssets.choices(holdings, infos = infos)

        assertEquals(listOf(null, usdc, held), choices.map { it.assetId })
        assertEquals(BigInteger("2500000"), choices[1].held)
        assertEquals("ASA 1234", choices[2].symbol)
        assertEquals(listOf(usdc, held), AlgorandAssets.infoIds(holdings))
    }

    @Test
    fun aKnownAssetWithNoBalanceIsOptedInButNotOfferedInThePicker() {
        // The holdings keep zero balances, so an opted-in asset is known to be opted in, though the picker offers only what the account holds.
        val holdings = listOf(AsaHolding(usdc, BigInteger.ZERO, false))

        assertTrue(AlgorandAssets.optedIn(usdc, holdings))
        assertEquals(listOf(null), AlgorandAssets.choices(holdings, infos).map { it.assetId })
        assertEquals(emptyList(), AlgorandAssets.infoIds(holdings))
    }

    @Test
    fun anAssetTheAccountHasNotOptedInToIsNotOfferedInThePicker() {
        assertFalse(AlgorandAssets.optedIn(usdc, emptyList()))
        assertEquals(listOf(null), AlgorandAssets.choices(emptyList(), infos).map { it.assetId })
    }

    @Test
    fun anAlgoSendRoutesToAPaymentOfTheAmount() {
        val plan = AlgorandAssets.plan(AlgorandAssets.native, ALGORAND_TESTNET, funds, 1L, 0, signer, recipient, BigInteger("1500000"), recipientOptedIn = true)

        val payment = assertIs<AlgorandPaymentPlan>(plan)
        assertEquals(BigInteger("1500000"), payment.amount)
        assertContentEquals(recipient, payment.txn.receiver)
    }

    @Test
    fun aHeldAssetSendRoutesToATransferAndIsRefusedWhenTheRecipientHasNotOptedIn() {
        val heldUsdc = AlgorandAsset(usdc, "USDC", 6, BigInteger("3000000"))

        val transfer = assertIs<AsaSendPlan>(
            AlgorandAssets.plan(heldUsdc, ALGORAND_TESTNET, funds, 1L, 0, signer, recipient, BigInteger("2500000"), recipientOptedIn = true),
        )
        assertFalse(transfer.isOptIn)
        assertEquals(usdc, transfer.assetId)
        assertEquals(BigInteger("2500000"), transfer.amount)

        val refused = assertFailsWith<IllegalArgumentException> {
            AlgorandAssets.plan(heldUsdc, ALGORAND_TESTNET, funds, 1L, 0, signer, recipient, BigInteger("2500000"), recipientOptedIn = false)
        }
        assertEquals(AsaSend.NOT_OPTED_IN, refused.message)
    }

    @Test
    fun anAssetTheAccountHasNotOptedInToRoutesToAnOptInOfTheSigner() {
        val notHeld = AlgorandAsset(usdc, "USDC", 6, null)

        val optIn = assertIs<AsaSendPlan>(
            AlgorandAssets.plan(notHeld, ALGORAND_TESTNET, funds, 1L, 0, signer, recipient, BigInteger.ONE, recipientOptedIn = false),
        )
        assertTrue(optIn.isOptIn)
        assertEquals(usdc, optIn.assetId)
        assertContentEquals(signer, optIn.txn.receiver)
    }

    @Test
    fun anAmountIsReadInTheBaseUnitsOfTheCoinAndRefusedWhenItHasTooManyPlaces() {
        assertEquals(BigInteger("1500000"), AlgorandAssets.units("1.5", 6, "USDC"))
        assertEquals(BigInteger("2"), AlgorandAssets.units("2", 0, "TST"))
        assertFailsWith<IllegalArgumentException> { AlgorandAssets.units("1.0000001", 6, "USDC") }
        assertFailsWith<IllegalArgumentException> { AlgorandAssets.units("1.5", 0, "TST") }
        assertFailsWith<IllegalArgumentException> { AlgorandAssets.units("0", 6, "USDC") }
    }

    @Test
    fun anAmountIsShownWithoutTrailingZeros() {
        assertEquals("1.5", AlgorandAssets.text(BigInteger("1500000"), 6))
        assertEquals("2", AlgorandAssets.text(BigInteger("2000000"), 6))
    }
}
