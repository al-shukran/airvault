package app.lumio.home

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcRow
import app.lumio.btc.BtcSnapshot
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

/** The native and Bitcoin balances that the wallet screens list, built from the vault's reads. A coin with no read is not an asset. */
class HomeBalancesTest {
    private val ethereum = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    private val sepolia = EvmChain(11155111L, "Sepolia", "ETH", 18, testnet = true, explorerUrl = null)
    private val base = EvmChain(8453L, "Base", "ETH", 18, testnet = false, explorerUrl = null)
    private val usdc = "0x" + "c3".repeat(20)

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
    fun aTokenIsOneAssetWithItsHoldingsAddedAndZeroHoldingsLeftOut() {
        val holdings = listOf(
            tokenHolding(usdc.uppercase().replace("0X", "0x"), "1500000"),
            tokenHolding(usdc, "0"),
            tokenHolding(usdc, "2000000"),
        )

        val assets = HomeBalances.tokens(holdings)

        assertEquals(listOf(TokenAsset.idOf(NetworkId(Family.EVM, "1", testnet = false), usdc)), assets.map { it.id })
        assertEquals(usdc, assets.single().contract)
        assertEquals(0, BigDecimal("3.5").compareTo(assets.single().balance))
    }

    @Test
    fun aTokenWithNoBalanceIsNoAsset() {
        assertTrue(HomeBalances.tokens(listOf(tokenHolding(usdc, "0"))).isEmpty())
    }

    private fun tokenHolding(contract: String, amount: String) = TokenHolding(
        chain = ethereum,
        token = Token(chainId = 1L, contract = contract, symbol = "USDC", name = "USD Coin", decimals = 6),
        amount = BigInteger(amount),
        atMs = 1L,
    )

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
