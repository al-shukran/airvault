package app.lumio.ui.wallets

import app.lensly.core.btc.BtcNetwork
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.home.NativeAsset
import app.lumio.ui.home.AssetIconSpec
import app.lumio.wallets.AccountChain
import app.lumio.wallets.WalletAccount
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The wallet screens' state and text: the line under a wallet, the freshness of a total, the cents of a total, the stagger and the account rows. */
class WalletRowsTest {
    private val fingerprint = 0x1a2b3c4dL

    private fun account(chain: AccountChain, index: Int = 0, script: String? = null, balance: NativeAsset? = null) = WalletAccount(
        walletId = "wallet-demo",
        chain = chain,
        index = index,
        address = "0x" + "a1b2c3d4".repeat(5),
        networkName = "network",
        balance = balance,
        script = script,
    )

    @Test
    fun summaryCountsChainsAndAccounts() {
        assertEquals("No accounts", summaryLine(emptyList()))
        assertEquals("1 chain · 1 account", summaryLine(listOf(account(AccountChain.ETHEREUM))))
        assertEquals("1 chain · 2 accounts", summaryLine(listOf(account(AccountChain.ETHEREUM), account(AccountChain.ETHEREUM, index = 1))))
        val fourChains = listOf(
            account(AccountChain.BITCOIN),
            account(AccountChain.ETHEREUM),
            account(AccountChain.SOLANA),
            account(AccountChain.ALGORAND),
        )
        assertEquals("4 chains · 4 accounts", summaryLine(fourChains))
    }

    @Test
    fun updatedAgoReadsTheAgeInMinutesAndHours() {
        assertEquals("Updated just now", updatedAgo(0L))
        assertEquals("Updated just now", updatedAgo(59_999L))
        assertEquals("Updated just now", updatedAgo(-5_000L), "a clock that runs behind reads as just now")
        assertEquals("Updated 1 min ago", updatedAgo(60_000L))
        assertEquals("Updated 3 min ago", updatedAgo(3 * 60_000L + 12_000L))
        assertEquals("Updated 59 min ago", updatedAgo(59 * 60_000L))
        assertEquals("Updated 1 h ago", updatedAgo(60 * 60_000L))
        assertEquals("Updated 26 h ago", updatedAgo(26 * 60 * 60_000L))
    }

    @Test
    fun freshnessLineIsRefreshingThenTheAge() {
        assertEquals("Refreshing…", freshnessLine(refreshing = true, updatedAtMs = 0L, nowMs = 0L))
        assertEquals("Refreshing…", freshnessLine(refreshing = true, updatedAtMs = null, nowMs = 0L))
        assertEquals("Not updated yet", freshnessLine(refreshing = false, updatedAtMs = null, nowMs = 5_000_000L))
        assertEquals("Updated 3 min ago", freshnessLine(refreshing = false, updatedAtMs = 1_000_000L, nowMs = 1_000_000L + 3 * 60_000L))
    }

    @Test
    fun splitCentsKeepsTheDecimalWithTheCents() {
        assertEquals("\$12,480" to ".20", splitCents("\$12,480.20"))
        assertEquals("\$0" to ".05", splitCents("\$0.05"))
        assertEquals("\$1,234" to "", splitCents("\$1,234"))
    }

    @Test
    fun staggerDelayStepsThirtyMillisecondsAndStopsAtTheEighth() {
        assertEquals(0L, staggerDelayMs(0))
        assertEquals(30L, staggerDelayMs(1))
        assertEquals(210L, staggerDelayMs(7))
        assertEquals(210L, staggerDelayMs(8), "items from the eighth share the eighth's timing")
        assertEquals(210L, staggerDelayMs(40))
    }

    @Test
    fun accountsGroupByChainInChainOrderAndSkipEmptyChains() {
        val accounts = listOf(
            account(AccountChain.BITCOIN),
            account(AccountChain.ETHEREUM),
            account(AccountChain.ETHEREUM, index = 1),
            account(AccountChain.ALGORAND),
        )
        val groups = accountsByChain(accounts)
        assertEquals(listOf(AccountChain.BITCOIN, AccountChain.ETHEREUM, AccountChain.ALGORAND), groups.map { it.first })
        assertEquals(2, groups.single { it.first == AccountChain.ETHEREUM }.second.size)
    }

    @Test
    fun accountDescriptionShowsTheTokenCountOnlyWhenThereAreTokens() {
        val ethereum = account(AccountChain.ETHEREUM)
        assertEquals(shortAddress(ethereum.address), accountDescription(ethereum, tokens = 0))
        assertTrue(accountDescription(ethereum, tokens = 1).endsWith(" · +1 token"))
        assertTrue(accountDescription(ethereum, tokens = 2).endsWith(" · +2 tokens"))
        val bitcoin = account(AccountChain.BITCOIN, script = "native segwit")
        assertTrue(accountDescription(bitcoin, tokens = 0).endsWith(" · native segwit"))
    }

    @Test
    fun accountIconsFollowTheChain() {
        val monad = NetworkId(Family.EVM, "10143", testnet = true)
        assertEquals(AssetIconSpec.Bitcoin, accountIconSpec(account(AccountChain.BITCOIN), evmChainId = 10143L) { null })
        assertEquals(AssetIconSpec.Chain(null), accountIconSpec(account(AccountChain.ETHEREUM), evmChainId = 10143L) { null })
        val solanaBalance = NativeAsset("SOL-devnet", "SOL", "Solana devnet", monad, BigDecimal("0.4"))
        assertEquals(AssetIconSpec.Letter("S"), accountIconSpec(account(AccountChain.SOLANA, balance = solanaBalance), evmChainId = 10143L) { null })
        assertEquals(AssetIconSpec.Letter("A"), accountIconSpec(account(AccountChain.ALGORAND), evmChainId = 10143L) { null })
    }
}
