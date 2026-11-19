package app.lumio.tokens

import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.HdWallet
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.price.DefiLlamaChains
import app.lumio.evm.EvmChain
import app.lumio.evm.Portfolio
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import java.io.File
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The owner's tokens as a vault record: added once per chain and contract, removed by chain and contract, and a damaged record reads as none. */
class CustomTokenStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy {
        VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 7 + 3).toByte() })
    }

    private val usdc = Token(1L, USDC, "USDC", "USD Coin", 6)
    private val dai = Token(1L, DAI, "DAI", "Dai Stablecoin", 18)

    @Test
    fun tokensAreAddedAndKeptInTheVault() {
        CustomTokenStore.add(session, usdc)
        CustomTokenStore.add(session, dai)

        assertEquals(listOf(usdc, dai), CustomTokenStore.read(session))
    }

    @Test
    fun aTokenAlreadyOnItsChainIsRefusedWithTheOwnersWords() {
        CustomTokenStore.add(session, usdc)

        assertEquals("That token is already in your list.", assertFailsWith<IllegalArgumentException> { CustomTokenStore.add(session, usdc) }.message)
        CustomTokenStore.add(session, usdc.copy(chainId = 10L))
        assertEquals(2, CustomTokenStore.read(session).size)
    }

    @Test
    fun removeDropsOnlyTheTokenAtThatChainAndContract() {
        CustomTokenStore.add(session, usdc)
        CustomTokenStore.add(session, dai)
        CustomTokenStore.remove(session, chainId = 1L, contract = USDC)
        CustomTokenStore.remove(session, chainId = 10L, contract = DAI)

        assertEquals(listOf(dai), CustomTokenStore.read(session))
    }

    @Test
    fun aMissingOrDamagedRecordReadsAsNoTokens() {
        assertEquals(emptyList(), CustomTokenStore.read(session))
        session.putVaultRecord(CustomTokenStore.RECORD, "not json".encodeToByteArray())

        assertEquals(emptyList(), CustomTokenStore.read(session))
    }

    @Test
    fun holdsMatchesTheChainAndTheContractWithoutCase() {
        val mainnet = NetworkId(Networks.evm(1), testnet = false)
        val sepolia = NetworkId(Networks.evm(11155111), testnet = true)

        assertTrue(CustomTokenStore.holds(listOf(usdc), mainnet, USDC.uppercase()))
        assertTrue(!CustomTokenStore.holds(listOf(usdc), sepolia, USDC))
        assertTrue(!CustomTokenStore.holds(emptyList(), mainnet, USDC))
    }
}

/** The cache of token reads: a refresh replaces the reads it reads again, keeps the rest, and drops an address that is no longer shown. */
class TokenPortfolioMergeTest {
    private val wallet = "w1"
    private fun read(address: String, contract: String, chain: Long = 1L, wallet: String = this.wallet, amount: String = "1", at: Long = 1L) =
        CachedTokenBalance(wallet, chain, address, contract, amount, at)


    @Test
    fun aFreshReadReplacesTheSameReadAndKeepsTheOthers() {
        val a = "0x" + "01".repeat(20)
        val cache = listOf(read(a, USDC, amount = "5"), read(a, DAI, amount = "7"))
        val merged = TokenPortfolio.merged(cache, wallet, 1L, setOf(a), listOf(read(a, USDC, amount = "9", at = 2L)))

        assertEquals(listOf(read(a, DAI, amount = "7"), read(a, USDC, amount = "9", at = 2L)), merged)
    }

    @Test
    fun anAddressNoLongerShownAndOtherWalletsAndChainsKeepTheirOwnRules() {
        val shown = "0x" + "01".repeat(20)
        val hidden = "0x" + "02".repeat(20)
        val cache = listOf(
            read(hidden, USDC),
            read(shown, USDC, chain = 10L),
            read(shown, USDC, wallet = "other"),
            read(shown, USDC),
        )
        val merged = TokenPortfolio.merged(cache, wallet, 1L, setOf(shown), emptyList())

        assertEquals(listOf(read(shown, USDC, chain = 10L), read(shown, USDC, wallet = "other"), read(shown, USDC)), merged)
    }

    @Test
    fun holdingsAreTheCachedReadsOfTheListedTokensOnTheShownAddresses() {
        val paired = PairedWallet(id = wallet, name = "Demo", fingerprint = 1L, createdAtMs = 0L, evmAccounts = PairFixtures.evmAccounts(2), bitcoin = emptyList())
        val mainnet = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
        val shown = paired.evmAddresses.take(2)
        val cache = listOf(
            read(shown[0], USDC, amount = "250", at = 5L),
            read(shown[1], DAI, amount = "3", at = 6L),
            read(shown[1], "0x" + "99".repeat(20), amount = "1"),
        )
        val tokensOf = { chain: EvmChain -> if (chain.chainId == 1L) listOf(Token(1L, USDC, "USDC", "USD Coin", 6), Token(1L, DAI, "DAI", "Dai", 18)) else emptyList() }

        val holdings = TokenPortfolio.holdings(paired, 2, listOf(mainnet), tokensOf, cache)

        assertEquals(listOf("USDC", "DAI"), holdings.map { it.token.symbol })
        assertEquals(listOf(BigInteger.valueOf(250), BigInteger.valueOf(3)), holdings.map { it.amount })
        assertEquals(listOf(5L, 6L), holdings.map { it.atMs })
    }
}

/** The default list the app ships, read from its asset file: five major tokens on each mainnet chain, each priced by a DefiLlama prefix. */
class DefaultTokenListTest {
    private val file = File("src/main/assets/tokens/default-tokens.json")
    private val tokens by lazy { TokenCatalog.parse(file.readText()) }

    @Test
    fun theListHoldsTheFiveMajorTokensOfEachMainnetChain() {
        assertEquals(44, tokens.size)
        assertTrue(tokens.all { it.symbol in setOf("USDC", "USDT", "DAI", "WETH", "WBTC") })
        assertEquals(13, tokens.map { it.chainId }.toSet().size)
    }

    @Test
    fun everyContractIsLowercaseAndEveryChainHasAPriceSource() {
        assertTrue(tokens.all { Regex("0x[0-9a-f]{40}").matches(it.contract) })
        assertTrue(tokens.all { it.decimals in setOf(6, 8, 18) })
        val unpriced = tokens.map { it.chainId }.toSet().filter { DefiLlamaChains.prefixOf(NetworkId(Networks.evm(it), testnet = false)) == null }
        assertEquals(emptyList<Long>(), unpriced)
    }

    @Test
    fun theWrappedEtherOfBaseIsTheStandardPredeploy() {
        val weth = tokens.single { it.chainId == 8453L && it.symbol == "WETH" }
        assertEquals("0x4200000000000000000000000000000000000006", weth.contract)
        assertEquals(18, weth.decimals)
    }
}
