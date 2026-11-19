package app.lumio.data.price

import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class NativeCoinsTest {
    private fun evm(chain: String, testnet: Boolean = false) = NetworkId(Networks.evm(chain.toLong()), testnet)

    @Test
    fun everyEthereumLayerTwoPricesWithTheEthOfEthereum() {
        assertEquals("ethereum", NativeCoins.coinOf(evm("1")))
        assertEquals("ethereum", NativeCoins.coinOf(evm("8453")))
        assertEquals("ethereum", NativeCoins.coinOf(evm("42161")))
        assertEquals("ethereum", NativeCoins.coinOf(evm("59144")))
    }

    @Test
    fun theOtherNativeCoinsHaveTheirOwnPriceCoin() {
        assertEquals("binancecoin", NativeCoins.coinOf(evm("56")))
        assertEquals("polygon-ecosystem-token", NativeCoins.coinOf(evm("137")))
        assertEquals("avalanche-2", NativeCoins.coinOf(evm("43114")))
        assertEquals("monad", NativeCoins.coinOf(evm("143")))
    }

    @Test
    fun bitcoinMainnetIsPricedAsBitcoin() {
        assertEquals("bitcoin", NativeCoins.coinOf(NetworkId(Networks.BTC_MAINNET, testnet = false)))
    }

    @Test
    fun aTestnetHasNoPriceEvenUnderAMainnetChainId() {
        assertNull(NativeCoins.coinOf(evm("1", testnet = true)))
        assertNull(NativeCoins.coinOf(evm("143", testnet = true)))
        assertNull(NativeCoins.coinOf(evm("10143", testnet = true)))
        assertNull(NativeCoins.coinOf(evm("11155111", testnet = true)))
        assertNull(NativeCoins.coinOf(NetworkId(Networks.BTC_TESTNET4, testnet = true)))
        assertNull(NativeCoins.coinOf(NetworkId(Networks.BTC_MAINNET, testnet = true)))
    }

    @Test
    fun aChainWithNoEntryOrNoVerifiedPriceHasNoPrice() {
        assertNull(NativeCoins.coinOf(evm("999999")))
        assertNull(NativeCoins.coinOf(evm("250")))
    }
}
