package app.lumio.home

import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lumio.btc.BtcAccountRef
import app.lumio.btc.BtcRow
import app.lumio.btc.BtcSnapshot
import app.lumio.evm.EvmChain
import app.lumio.evm.Holding
import app.lumio.pair.PairFixtures
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
        assertTrue(assets[0].network.isEvm)
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

    private fun snapshot(key: String, confirmed: Long, pending: Long) = BtcSnapshot(
        key = key,
        identity = "",
        scannedAtMs = 1L,
        tipHeight = 100L,
        confirmedSats = confirmed,
        unconfirmedSats = pending,
        nextReceiveIndex = 0,
        utxos = emptyList(),
        transactions = emptyList(),
    )
}
