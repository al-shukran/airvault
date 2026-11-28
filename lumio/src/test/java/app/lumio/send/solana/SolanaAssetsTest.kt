package app.lumio.send.solana

import app.lensly.core.solana.SolanaTokenAccounts
import app.airvault.chains.solana.signer.SolanaTokenList
import app.lumio.data.provider.SplBalance
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.ui.kit.addressLineText
import fr.acinq.bitcoin.Base58
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

/** The coins a Solana send offers, the amounts in their base units, and the plan that each coin's send routes to. */
class SolanaAssetsTest {
    private val account = Base58.encode(ByteArray(32) { 5 })
    private val usdcMint = Base58.encode(ByteArray(32) { 3 })
    private val unnamedMint = Base58.encode(ByteArray(32) { 6 })
    private val token2022Mint = Base58.encode(ByteArray(32) { 7 })
    private val names = SolanaTokenList.parse("""{"tokens":[{"mint":"$usdcMint","symbol":"USDC","name":"USD Coin","decimals":6}]}""")
    private val usdc = SplBalance(account, usdcMint, SolanaTokenAccounts.TOKEN_PROGRAM, BigInteger("25000000"), 6)
    private val sender = SolanaSendAccount(index = 0, key = ByteArray(32) { 1 }, sourceFingerprint = 5)
    private val recipient = ByteArray(32) { 2 }
    private val nonce = ByteArray(32) { 4 }
    private val requestId = ByteArray(16) { 7 }
    private val sol = BigInteger.valueOf(2_000_000_000L)

    @Test
    fun theChoicesAreSolThenTheClassicTokensHeldAboveZeroNamedByTheList() {
        val unheld = SplBalance(account, unnamedMint, SolanaTokenAccounts.TOKEN_PROGRAM, BigInteger.ZERO, 9)
        val token2022 = SplBalance(account, token2022Mint, TOKEN_2022, BigInteger("42"), 9)

        val choices = SolanaAssets.choices(listOf(usdc, unheld, token2022), names)

        assertEquals(listOf("SOL", "USDC"), choices.map { it.symbol })
        assertEquals(6, choices[1].decimals)
        assertEquals(usdcMint, choices[1].key)
        assertEquals(SolanaAssets.SOL_KEY, choices[0].key)
    }

    @Test
    fun aMintTheListDoesNotNameIsWrittenAsItsGroupedEnds() {
        val unnamedHeld = SplBalance(account, unnamedMint, SolanaTokenAccounts.TOKEN_PROGRAM, BigInteger("5"), 2)

        val symbol = SolanaAssets.choices(listOf(unnamedHeld), names)[1].symbol

        assertEquals(addressLineText(unnamedMint), symbol)
        assertEquals("QWmr oo4Y … HGbU zwF", symbol.replace('\u00A0', ' '), "the grouped ends, joined by no-break spaces so a break comes only after the ellipsis")
    }

    @Test
    fun aSolSendRoutesToALamportTransferAndATokenSendToATokenTransfer() {
        val solPlan = SolanaAssets.plan(
            SOLANA_DEVNET,
            SolanaAssets.native,
            sender,
            recipient,
            BigInteger.valueOf(1_500_000_000L),
            sol,
            nonce,
            requestId,
            recipientAccountExists = true,
            rentLamports = 0L,
        )
        assertNull(solPlan.token)
        assertEquals(1_500_000_000L, solPlan.lamports)

        val tokenPlan = SolanaAssets.plan(
            SOLANA_DEVNET,
            SolanaAssets.choices(listOf(usdc), names)[1],
            sender,
            recipient,
            BigInteger("1500000"),
            sol,
            nonce,
            requestId,
            recipientAccountExists = true,
            rentLamports = 0L,
        )
        assertEquals(0L, tokenPlan.lamports)
        assertEquals(usdcMint, tokenPlan.token?.mint)
        assertEquals(1_500_000UL, tokenPlan.token?.amount)
    }

    @Test
    fun aTokenAmountIsReadInItsBaseUnitsAndRefusedWhenItHasTooManyPlaces() {
        assertEquals(BigInteger("1500000"), SolanaSendInput.tokenUnits("1.5", 6))
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.tokenUnits("1.0000001", 6) }
        assertFailsWith<IllegalArgumentException> { SolanaSendInput.tokenUnits("0", 6) }
        assertEquals("1.5", SolanaAssets.text(BigInteger("1500000"), 6))
    }

    @Test
    fun anAmountMoreThanTheAccountHoldsIsNamedInTheFieldForSolAndForAToken() {
        val held = BigInteger.valueOf(1_500_000_000L)
        assertEquals("More than your available 1.5 SOL", SolanaAssets.amountError("2", SolanaAssets.native, held))
        assertNull(SolanaAssets.amountError("1.5", SolanaAssets.native, held))
        assertNull(SolanaAssets.amountError("2", SolanaAssets.native, null))
        val token = SolanaSendAsset(usdc, "USDC", 6)
        assertEquals("More than your available 25 USDC", SolanaAssets.amountError("25.000001", token, usdc.amount))
        assertNull(SolanaAssets.amountError("25", token, usdc.amount))
    }

    private companion object {
        const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    }
}
