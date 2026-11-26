package app.lumio.watchlist

import app.lumio.data.asset.AssetId
import app.lumio.data.net.Networks
import app.lumio.data.net.NetworkId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistTest {
    private val bitcoin = WatchedAsset(NetworkId(Networks.BTC_MAINNET, testnet = false))
    private val ethereum = WatchedAsset(NetworkId(Networks.evm(1), testnet = false))
    private val solana = WatchedAsset(NetworkId(Networks.SOL_MAINNET, testnet = false))
    private val usdc = WatchedAsset(NetworkId(Networks.evm(1), testnet = false), contract = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")

    @Test
    fun marketCapKeepsTheOrderTheAssetsWereStarredIn() {
        val list = WalletWatchlist(assets = listOf(solana, bitcoin, ethereum), sort = WatchlistSort.MARKET_CAP)
        assertEquals(listOf(solana, bitcoin, ethereum), list.ordered { null })
    }

    @Test
    fun changeSortPutsTheBiggestGainFirstAndAnAssetWithNoChangeLast() {
        val changes = mapOf(bitcoin to 1.82, ethereum to -2.41, solana to null)
        val list = WalletWatchlist(assets = listOf(ethereum, solana, bitcoin), sort = WatchlistSort.CHANGE_24H)
        assertEquals(listOf(bitcoin, ethereum, solana), list.ordered { changes[it] })
    }

    @Test
    fun starringAnAssetAddsItAfterTheOthersAndStarringItAgainRemovesIt() {
        val list = WalletWatchlist(assets = listOf(bitcoin, solana), sort = WatchlistSort.CHANGE_24H)
        val starred = list.toggled(ethereum)
        assertEquals(listOf(bitcoin, solana, ethereum), starred.assets)
        assertEquals(WatchlistSort.CHANGE_24H, starred.sort)
        assertEquals(listOf(bitcoin, solana), starred.toggled(ethereum).assets)
    }

    @Test
    fun firstRunShowsTheMainnetCoinsWithoutContracts() {
        assertEquals(4, FIRST_RUN_ASSETS.size)
        assertTrue(FIRST_RUN_ASSETS.all { it.contract == null && !it.network.testnet })
    }

    @Test
    fun aWatchedAssetIsItsNativeCoinOrItsToken() {
        assertEquals(AssetId.Native(NetworkId(Networks.evm(1), testnet = false)), ethereum.assetId)
        assertTrue(usdc.assetId is AssetId.Erc20)
    }

    @Test
    fun aWatchedSolanaOrAlgorandTokenIsItsMintOrItsAssetIdAndTheMintKeepsItsCase() {
        val mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val spl = WatchedAsset(solana.network, contract = mint)
        val asa = WatchedAsset(NetworkId(Networks.ALGO_MAINNET, testnet = false), contract = "31566704")
        assertEquals(AssetId.Spl(solana.network, mint), spl.assetId)
        assertEquals(AssetId.Asa(asa.network, 31566704UL), asa.assetId)
        val record = Json.encodeToString(WalletWatchlist.serializer(), WalletWatchlist(assets = listOf(spl, asa)))
        assertEquals(listOf(spl, asa), Json.decodeFromString(WalletWatchlist.serializer(), record).assets)
    }
}
