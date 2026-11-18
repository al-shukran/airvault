package app.lumio.chains

import app.lensly.core.registry.Amounts
import app.lensly.core.solana.SolanaTokenList
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.AsaInfo
import app.lumio.data.provider.SplBalance
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.Test

/** The rows the account screen lists for a Solana or Algorand account, and how their amounts read in whole units. */
class ChainHoldingsTest {
    private val names = SolanaTokenList.parse("""{"tokens":[{"mint":"$USDC_MINT","symbol":"USDC","name":"USD Coin","decimals":6}]}""")

    @Test
    fun aMintTheListNamesTakesItsSymbolAndAnOtherIsShortened() {
        val rows = ChainHoldings.solanaRows(
            listOf(
                SplBalance(ACCOUNT, USDC_MINT, CLASSIC_PROGRAM, BigInteger("1500000"), 6),
                SplBalance(ACCOUNT, UNKNOWN_MINT, SPL_2022_PROGRAM, BigInteger("42"), 9),
            ),
            names,
            "Solana devnet",
        )

        assertEquals(
            listOf(
                ChainHolding("USDC", "Solana devnet", BigInteger("1500000"), 6, USDC_MINT),
                ChainHolding("5UHr...2XJ6", "Solana devnet", BigInteger("42"), 9, UNKNOWN_MINT),
            ),
            rows,
        )
    }

    @Test
    fun anSplAccountHoldingNothingIsNotListed() {
        val rows = ChainHoldings.solanaRows(listOf(SplBalance(ACCOUNT, USDC_MINT, CLASSIC_PROGRAM, BigInteger.ZERO, 6)), names, "Solana devnet")

        assertEquals(emptyList(), rows)
    }

    @Test
    fun aSplAmountReadsInWholeUnitsWithTheDecimalsTheAccountReports() {
        val row = ChainHoldings.solanaRows(listOf(SplBalance(ACCOUNT, USDC_MINT, CLASSIC_PROGRAM, BigInteger("1500000"), 6)), names, "Solana devnet").single()

        assertEquals("1.5", Amounts.format(row.amount, row.decimals))
    }

    @Test
    fun anAlgorandAssetTakesItsUnitNameAndItsDecimalsFromItsParams() {
        val rows = ChainHoldings.algorandRows(
            listOf(AsaHolding(USDC_ASSET, BigInteger("2500000"), frozen = false)),
            mapOf(USDC_ASSET to AsaInfo(decimals = 6, unitName = "USDC", name = "USDC test")),
            "Algorand testnet",
        )

        assertEquals(listOf(ChainHolding("USDC", "Algorand testnet", BigInteger("2500000"), 6, "$USDC_ASSET")), rows)
        assertEquals("2.5", Amounts.format(rows.single().amount, rows.single().decimals))
    }

    @Test
    fun anAssetWithNoUnitNameIsNamedByItsID() {
        val rows = ChainHoldings.algorandRows(
            listOf(AsaHolding(1234567UL, BigInteger.ONE, frozen = false)),
            mapOf(1234567UL to AsaInfo(decimals = 0, unitName = "", name = "")),
            "Algorand mainnet",
        )

        assertEquals("ASA 1234567", rows.single().symbol)
        assertEquals("1", Amounts.format(rows.single().amount, rows.single().decimals))
    }

    private companion object {
        const val ACCOUNT = "11111111111111111111111111111111"
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val UNKNOWN_MINT = "5UHrBSdVTrUZxAwCiVT876romMWawWhnDtGrz8jX2XJ6"
        const val CLASSIC_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val SPL_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        const val USDC_ASSET = 10458941UL
    }
}
