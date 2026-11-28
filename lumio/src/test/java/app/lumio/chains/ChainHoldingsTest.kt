package app.lumio.chains

import app.lensly.core.registry.Amounts
import app.airvault.chains.solana.signer.SolanaTokenList
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.AsaInfo
import app.lumio.data.provider.SplBalance
import app.lumio.ui.kit.addressLineText
import java.math.BigInteger
import kotlin.test.assertEquals
import org.junit.Test

/** The rows the account screen lists for a Solana or Algorand account, and how their amounts read in whole units. */
class ChainHoldingsTest {
    private val names = SolanaTokenList.parse("""{"tokens":[{"mint":"$USDC_MINT","symbol":"USDC","name":"USD Coin","decimals":6}]}""")

    @Test
    fun aMintTheListNamesTakesItsSymbolAndAnOtherIsItsGroupedEnds() {
        val rows = ChainHoldings.solanaRows(
            listOf(
                SplBalance(ACCOUNT, USDC_MINT, CLASSIC_PROGRAM, BigInteger("1500000"), 6),
                SplBalance(ACCOUNT, UNKNOWN_MINT, SPL_2022_PROGRAM, BigInteger("42"), 9),
            ),
            names,
            SOL_DEVNET,
            "Solana Devnet",
        )

        assertEquals(
            listOf(
                ChainHolding("USDC", "Solana Devnet", BigInteger("1500000"), 6, AssetId.Spl(SOL_DEVNET, USDC_MINT)),
                ChainHolding(addressLineText(UNKNOWN_MINT), "Solana Devnet", BigInteger("42"), 9, AssetId.Spl(SOL_DEVNET, UNKNOWN_MINT)),
            ),
            rows,
        )
    }

    @Test
    fun anSplAccountHoldingNothingIsNotListed() {
        val rows = ChainHoldings.solanaRows(listOf(SplBalance(ACCOUNT, USDC_MINT, CLASSIC_PROGRAM, BigInteger.ZERO, 6)), names, SOL_DEVNET, "Solana Devnet")

        assertEquals(emptyList(), rows)
    }

    @Test
    fun aSplAmountReadsInWholeUnitsWithTheDecimalsTheAccountReports() {
        val row = ChainHoldings.solanaRows(listOf(SplBalance(ACCOUNT, USDC_MINT, CLASSIC_PROGRAM, BigInteger("1500000"), 6)), names, SOL_DEVNET, "Solana Devnet").single()

        assertEquals("1.5", Amounts.format(row.amount, row.decimals))
    }

    @Test
    fun anAlgorandAssetTakesItsUnitNameAndItsDecimalsFromItsParams() {
        val rows = ChainHoldings.algorandRows(
            listOf(AsaHolding(USDC_ASSET, BigInteger("2500000"), frozen = false)),
            mapOf(USDC_ASSET to AsaInfo(decimals = 6, unitName = "USDC", name = "USDC test")),
            ALGO_TESTNET,
            "Algorand Testnet",
        )

        assertEquals(listOf(ChainHolding("USDC", "Algorand Testnet", BigInteger("2500000"), 6, AssetId.Asa(ALGO_TESTNET, USDC_ASSET))), rows)
        assertEquals("$USDC_ASSET", rows.single().key)
        assertEquals("2.5", Amounts.format(rows.single().amount, rows.single().decimals))
    }

    @Test
    fun anAssetWithNoUnitNameIsNamedByItsID() {
        val rows = ChainHoldings.algorandRows(
            listOf(AsaHolding(1234567UL, BigInteger.ONE, frozen = false)),
            mapOf(1234567UL to AsaInfo(decimals = 0, unitName = "", name = "")),
            ALGO_TESTNET,
            "Algorand Mainnet",
        )

        assertEquals("ASA 1234567", rows.single().symbol)
        assertEquals("1", Amounts.format(rows.single().amount, rows.single().decimals))
    }

    private companion object {
        val SOL_DEVNET = NetworkId(Networks.SOL_DEVNET, testnet = true)
        val ALGO_TESTNET = NetworkId(Networks.ALGO_TESTNET, testnet = true)
        const val ACCOUNT = "11111111111111111111111111111111"
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val UNKNOWN_MINT = "5UHrBSdVTrUZxAwCiVT876romMWawWhnDtGrz8jX2XJ6"
        const val CLASSIC_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val SPL_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        const val USDC_ASSET = 10458941UL
    }
}
