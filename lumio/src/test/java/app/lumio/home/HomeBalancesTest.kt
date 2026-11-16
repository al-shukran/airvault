package app.lumio.home

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcRow
import app.lumio.btc.BtcSnapshot
import app.lumio.data.asset.AssetId
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.evm.EvmChain
import app.lumio.evm.Holding
import app.lumio.pair.PairFixtures
import app.lumio.tokens.Token
import app.lumio.tokens.TokenHolding
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The native balances and the token balances Home lists, built from the vault's reads. A coin with no read is not an asset. */
class HomeBalancesTest {
    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, testnet = true, explorerUrl = null)
    private val base = EvmChain(8453L, "Base", "ETH", 18, testnet = false, explorerUrl = null)
    private val usdcMainnet = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val usdcBase = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"

    @Test
    fun anEthereumChainSumsEveryAddressAndSkipsAChainWithNoRead() {
        val holdings = listOf(
            Holding(ethereum, BigInteger("1500000000000000000"), atMs = 1L),
            Holding(ethereum, BigInteger("500000000000000000"), atMs = 2L),
            Holding(sepolia, BigInteger.ZERO, atMs = 3L),
        )

        val assets = HomeBalances.evm(listOf(ethereum, sepolia, base), holdings)

        assertEquals(listOf("EVM-1", "EVM-11155111"), assets.map { it.id })
        assertEquals(0, BigDecimal("2").compareTo(assets[0].balance))
        assertEquals("ETH", assets[0].symbol)
        assertEquals("Ethereum", assets[0].name)
        assertEquals(Family.EVM, assets[0].network.family)
    }

    @Test
    fun aTestnetChainIsMarkedAsATestnet() {
        val assets = HomeBalances.evm(listOf(sepolia), listOf(Holding(sepolia, BigInteger.ONE, atMs = 1L)))

        assertTrue(assets.single().testnet)
        assertTrue(assets.single().network.testnet)
    }

    @Test
    fun aBitcoinNetworkSumsTheConfirmedBalanceOfItsScannedAccounts() {
        val mainnetTaproot = PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.TAPROOT)
        val mainnetNative = PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)
        val testnet = PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)
        val taprootRef = BtcAccountRef("wallet-a", mainnetTaproot)
        val nativeRef = BtcAccountRef("wallet-a", mainnetNative)
        val rows = listOf(
            BtcRow(taprootRef, snapshot(taprootRef.key, confirmed = 150_000L, pending = 9_000L)),
            BtcRow(nativeRef, snapshot(nativeRef.key, confirmed = 50_000L, pending = -1_000L)),
            BtcRow(BtcAccountRef("wallet-a", testnet), null),
        )

        val assets = HomeBalances.bitcoin(rows)

        assertEquals(listOf("BTC-mainnet"), assets.map { it.id })
        // The confirmed balance is the figure: 150_000 + 50_000 satoshis, and the pending amounts stay on the Bitcoin page.
        assertEquals(0, BigDecimal("0.002").compareTo(assets.single().balance))
        assertEquals("Bitcoin", assets.single().name)
        assertFalse(assets.single().testnet)
    }

    @Test
    fun aBitcoinNetworkWithoutAScanIsNotAnAsset() {
        val testnet = PairFixtures.account(PairFixtures.seed, BtcNetwork.TESTNET4, ScriptType.NATIVE_SEGWIT)

        assertTrue(HomeBalances.bitcoin(listOf(BtcRow(BtcAccountRef("wallet-a", testnet), null))).isEmpty())
    }

    @Test
    fun aTokenSumsEveryAddressOfEveryWalletOnItsChainInItsDecimals() {
        val usdc = Token(1L, usdcMainnet, "USDC", "USD Coin", decimals = 6)
        val usdcOnBase = Token(8453L, usdcBase, "USDC", "USD Coin", decimals = 6)
        val holdings = listOf(
            TokenHolding(ethereum, usdc, BigInteger("1500000"), atMs = 1L),
            TokenHolding(ethereum, usdc, BigInteger("500000"), atMs = 2L),
            TokenHolding(base, usdcOnBase, BigInteger("250000000"), atMs = 3L),
        )

        val tokens = HomeBalances.tokens(holdings)

        assertEquals(listOf("EVM-1-$usdcMainnet", "EVM-8453-$usdcBase"), tokens.map { it.id })
        assertEquals(0, BigDecimal("2").compareTo(tokens[0].balance))
        assertEquals(0, BigDecimal("250").compareTo(tokens[1].balance))
        assertEquals("USDC", tokens[0].symbol)
        assertEquals("Ethereum", tokens[0].name)
        assertEquals(NetworkId(Family.EVM, "1", testnet = false), tokens[0].network)
        assertEquals(usdcMainnet, tokens[0].contract)
        assertEquals(AssetId.Erc20(NetworkId(Family.EVM, "1", testnet = false), usdcMainnet), tokens[0].assetId)
    }

    @Test
    fun aTokenWithAZeroBalanceIsNotAnAsset() {
        val usdc = Token(1L, usdcMainnet, "USDC", "USD Coin", decimals = 6)

        assertTrue(HomeBalances.tokens(listOf(TokenHolding(ethereum, usdc, BigInteger.ZERO, atMs = 1L))).isEmpty())
        assertTrue(HomeBalances.tokens(emptyList()).isEmpty())
    }

    @Test
    fun aTokenOnATestnetIsMarkedAsATestnet() {
        val tokenOnSepolia = Token(11155111L, usdcMainnet, "TUSDC", "Test USDC", decimals = 6)

        val asset = HomeBalances.tokens(listOf(TokenHolding(sepolia, tokenOnSepolia, BigInteger.ONE, atMs = 1L))).single()

        assertTrue(asset.testnet)
        assertEquals(0, BigDecimal("0.000001").compareTo(asset.balance))
    }

    private fun snapshot(key: String, confirmed: Long, pending: Long) = BtcSnapshot(
        key = key,
        identity = "",
        scannedAtMs = 1L,
        tipHeight = 100L,
        confirmedSats = confirmed,
        unconfirmedSats = pending,
        nextReceiveIndex = 0,
        nextReceiveAddress = "",
        utxos = emptyList(),
        transactions = emptyList(),
    )
}
