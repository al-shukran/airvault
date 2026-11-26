package app.lumio.data.price

import app.lumio.data.ETH
import app.lumio.data.asset.AssetId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.NativePrice
import app.lumio.data.provider.TokenPrice
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Test

/** Which price request and cache key each asset gets, and which assets have no price source at all. */
class AssetPriceTest {
    private val usdc = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
    private val base = NetworkId(Networks.evm(8453), testnet = false)

    @Test
    fun aNativeCoinKeepsItsCoinGeckoKeyAndItsNativePriceRequest() {
        assertEquals("native-price:ethereum", priceKeyOf(AssetId.Native(ETH)))
        assertIs<NativePrice>(priceRequestOf(AssetId.Native(ETH)))
    }

    @Test
    fun solanaAndAlgorandCoinsAreAskedForByCoinOnly() {
        val sol = AssetId.Native(NetworkId(Networks.SOL_MAINNET, testnet = false))
        val algo = AssetId.Native(NetworkId(Networks.ALGO_MAINNET, testnet = false))
        assertEquals("native-price:solana", priceKeyOf(sol))
        assertEquals("native-price:algorand", priceKeyOf(algo))
        assertIs<NativePrice>(priceRequestOf(sol))
        assertIs<NativePrice>(priceRequestOf(algo))
        assertNull(priceRequestOf(AssetId.Native(NetworkId(Networks.SOL_DEVNET, testnet = true))))
        assertNull(priceRequestOf(AssetId.Native(NetworkId(Networks.ALGO_TESTNET, testnet = true))))
    }

    @Test
    fun aTokenIsPricedByItsContractUnderItsChainPrefix() {
        val asset = AssetId.Erc20(ETH, usdc)

        assertEquals("token-price:ethereum:$usdc", priceKeyOf(asset))
        assertEquals(usdc, (priceRequestOf(asset) as TokenPrice).contract)
        assertEquals("token-price:base:$usdc", priceKeyOf(AssetId.Erc20(base, usdc)))
    }

    @Test
    fun theSameTokenOnTwoChainsIsTwoAssetsWithTwoKeys() {
        assertNotEquals(priceKeyOf(AssetId.Erc20(ETH, usdc)), priceKeyOf(AssetId.Erc20(base, usdc)))
    }

    @Test
    fun anAssetWithNoPriceSourceHasNoRequestAndNoKey() {
        val testnet = NetworkId(Networks.evm(1), testnet = true)
        val unpriced = NetworkId(Networks.evm(250), testnet = false)

        assertNull(priceRequestOf(AssetId.Native(testnet)))
        assertNull(priceKeyOf(AssetId.Native(testnet)))
        assertNull(priceRequestOf(AssetId.Erc20(unpriced, usdc)))
        assertNull(priceKeyOf(AssetId.Erc20(testnet, usdc)))
    }

    @Test
    fun aTokenAddressIsTrimmedAndLowercasedAndAnythingElseIsRefused() {
        assertEquals(AssetId.Erc20(ETH, usdc), AssetId.Erc20.of(ETH, "  0xA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48 "))
        assertFailsWith<IllegalArgumentException> { AssetId.Erc20(ETH, "0xA0B86991C6218B36C1D19D4A2E9EB0CE3606EB48") }
        assertFailsWith<IllegalArgumentException> { AssetId.Erc20.of(ETH, "USDC") }
    }
}
