package app.lumio.ui.home

import app.lensly.core.registry.NetworkEntry
import app.lensly.core.registry.Registry
import app.lensly.core.registry.RegistryLoader
import app.lensly.core.registry.TokenEntry
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.home.NativeAsset
import app.lumio.home.TokenAsset
import app.lumio.tokens.TokenCatalog
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The icon Home draws beside each asset. A token shows its own logo from the registry, a native coin its chain's logo, Bitcoin the Bitcoin logo, and
 * a token with no logo a letter. No token row shows a chain logo.
 */
class AssetIconSpecTest {
    private val registry: Registry = RegistryLoader.load { path ->
        listOf("../shared/src/main/assets", "../shared/src/debug/assets")
            .map { File(it, path) }
            .firstOrNull(File::isFile)
            ?.readText()
    }
    private val ethereum: NetworkEntry? = registry.network(1L)

    @Test
    fun usdcAndUsdtOnEthereumShowTheirOwnLogos() {
        for (symbol in listOf("USDC", "USDT")) {
            val token = registryToken(1L, symbol)
            val spec = assetIconSpec(tokenAsset(token.symbol, token.contract), ethereum, token)

            assertEquals(AssetIconSpec.Token(token, ethereum), spec)
            assertEquals("token_" + symbol.lowercase(), token.icon)
        }
    }

    @Test
    fun usdcOnBaseShowsItsOwnLogoNotTheBaseLogo() {
        val base = registry.network(8453L)
        val token = registryToken(8453L, "USDC")

        val spec = assetIconSpec(tokenAsset("USDC", token.contract, chain = "8453"), base, token)

        assertEquals(AssetIconSpec.Token(token, base), spec)
    }

    @Test
    fun aNativeCoinShowsItsChainLogoAndBitcoinShowsTheBitcoinLogo() {
        val base = registry.network(8453L)
        val nativeBase = NativeAsset("EVM-8453", "ETH", "Base", NetworkId(Networks.evm(8453), testnet = false), BigDecimal.ONE)
        val bitcoin = NativeAsset("BTC-mainnet", "BTC", "Bitcoin", NetworkId(Networks.BTC_MAINNET, testnet = false), BigDecimal.ONE)

        assertEquals(AssetIconSpec.Chain(base), assetIconSpec(nativeBase, base, null))
        assertEquals(AssetIconSpec.Bitcoin, assetIconSpec(bitcoin, null, null))
    }

    @Test
    fun anUnlistedTokenGetsALetterNeverAChainLogo() {
        val spec = assetIconSpec(tokenAsset("ZZZ", "0x" + "c3".repeat(20)), ethereum, null)

        assertEquals(AssetIconSpec.Letter("Z"), spec)
    }

    @Test
    fun aListedTokenWithNoLogoGetsALetter() {
        val noLogo = TokenEntry(1L, "0x" + "d4".repeat(20), "USDP", "Pax Dollar", 18, icon = null)
        val unknownLogo = TokenEntry(1L, "0x" + "e5".repeat(20), "FOO", "Foo", 18, icon = "token_not_shipped")

        assertEquals(AssetIconSpec.Letter("U"), assetIconSpec(tokenAsset("USDP", noLogo.contract), ethereum, noLogo))
        assertEquals(AssetIconSpec.Letter("F"), assetIconSpec(tokenAsset("FOO", unknownLogo.contract), ethereum, unknownLogo))
    }

    @Test
    fun everyDefaultTokenShowsItsOwnLogoExceptWrappedEther() {
        // WETH has no logo in the registry, and the bundled list has no logo for it. It shows a letter until a licensed logo is added.
        val defaults = TokenCatalog.parse(File("src/main/assets/tokens/default-tokens.json").readText())
        val lettered = mutableSetOf<String>()

        for (token in defaults) {
            val network = NetworkId(Networks.evm(token.chainId), testnet = false)
            val entry = registry.token(token.chainId, token.contract)
            val spec = assetIconSpec(tokenAsset(token.symbol, token.contract, chain = token.chainId.toString()), registry.network(token.chainId), entry)

            assertTrue("${token.symbol} on ${network.chain} must not show a chain logo", spec !is AssetIconSpec.Chain)
            if (spec is AssetIconSpec.Letter) lettered += token.symbol
        }

        assertEquals(setOf("WETH"), lettered)
    }

    @Test
    fun aLetterIsTheFirstLetterOrDigitOfTheSymbolInCapitals() {
        assertEquals("U", letterOf("usdc.e"))
        assertEquals("1", letterOf("1INCH"))
        assertEquals("?", letterOf("$"))
    }

    @Test
    fun lumioDrawsBitcoinBnbHypeAndUsdcAsRoundMarksAndKeepsTheOtherLogos() {
        assertEquals(app.lumio.R.drawable.lumio_mark_bitcoin, roundMarkOf(AssetIconSpec.Bitcoin))
        assertEquals(app.lumio.R.drawable.lumio_mark_bnb, roundMarkOf(AssetIconSpec.Chain(registry.network(56L))))
        assertEquals(app.lumio.R.drawable.lumio_mark_hyperevm, roundMarkOf(AssetIconSpec.Chain(registry.network(999L))))
        assertEquals(app.lumio.R.drawable.lumio_mark_usdc, roundMarkOf(AssetIconSpec.Token(registryToken(1L, "USDC"), ethereum)))
        assertEquals(null, roundMarkOf(AssetIconSpec.Chain(ethereum)))
        assertEquals(null, roundMarkOf(AssetIconSpec.Token(registryToken(1L, "USDT"), ethereum)))
        assertEquals(null, roundMarkOf(AssetIconSpec.Solana))
    }

    private fun registryToken(chainId: Long, symbol: String): TokenEntry =
        checkNotNull(registry.tokens().firstOrNull { it.chainId == chainId && it.symbol == symbol }) { "$symbol on $chainId is not in the registry" }

    private fun tokenAsset(symbol: String, contract: String, chain: String = "1"): TokenAsset {
        val network = NetworkId(Networks.evm(chain.toLong()), testnet = false)
        return TokenAsset(
            id = TokenAsset.idOf(network, contract),
            symbol = symbol,
            name = "Ethereum",
            network = network,
            contract = contract,
            balance = BigDecimal.ONE,
        )
    }
}
