package app.lumio.ui.wallets

import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.home.NativeAsset
import app.lumio.wallets.WalletAccount
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/** The wallet screens' state and text: the line under a wallet, the freshness of a total, the monograms, and the fold of a family's accounts. */
class WalletRowsTest {

    private fun account(
        chain: ChainId,
        index: Int = 0,
        balance: NativeAsset? = null,
        name: String? = null,
        script: String? = null,
    ) = WalletAccount(
        walletId = "wallet-demo",
        id = AccountId.of(chain, "0x" + "%040x".format(index + 1)),
        index = index,
        networkName = "network",
        coin = null,
        balance = balance,
        script = script,
        name = name,
    )

    private fun coins(amount: String, network: NetworkId = NetworkId(Networks.evm(10143), testnet = true)) =
        NativeAsset("EVM-10143", "MON", "Monad testnet", network, BigDecimal(amount))

    @Test
    fun summaryCountsAccountsAndTheAppsThatHoldThem() {
        assertEquals("No accounts", summaryLine(emptyList()))
        assertEquals("1 account · 1 app", summaryLine(listOf(account(Networks.evm(1L)))))
        assertEquals("2 accounts · 1 app", summaryLine(listOf(account(Networks.evm(1L)), account(Networks.evm(1L), index = 1))))
        val fourApps = listOf(
            account(Networks.BTC_MAINNET),
            account(Networks.evm(1L)),
            account(Networks.SOL_MAINNET),
            account(Networks.ALGO_MAINNET),
        )
        assertEquals("4 accounts · 4 apps", summaryLine(fourApps))
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
    fun freshnessLineIsCheckingThenTheAge() {
        assertEquals("Checking…", freshnessLine(refreshing = true, updatedAtMs = 0L, nowMs = 0L))
        assertEquals("Checking…", freshnessLine(refreshing = true, updatedAtMs = null, nowMs = 0L))
        assertEquals("Not updated yet", freshnessLine(refreshing = false, updatedAtMs = null, nowMs = 5_000_000L))
        assertEquals("Updated 3 min ago", freshnessLine(refreshing = false, updatedAtMs = 1_000_000L, nowMs = 1_000_000L + 3 * 60_000L))
    }

    @Test
    fun accountsGroupByChainInChainOrderAndSkipEmptyChains() {
        val accounts = listOf(
            account(Networks.BTC_MAINNET),
            account(Networks.evm(1L)),
            account(Networks.evm(1L), index = 1),
            account(Networks.ALGO_MAINNET),
        )
        val groups = accountsByChain(accounts)
        assertEquals(listOf(Networks.BIP122, Networks.EIP155, Networks.ALGORAND), groups.map { it.first })
        assertEquals(2, groups.single { it.first == Networks.EIP155 }.second.size)
    }

    @Test
    fun accountTitleIsTheNameInLenslyOrItsPlaceInTheWallet() {
        assertEquals("PulsarNova", accountTitle(account(Networks.evm(10143L), name = "PulsarNova")))
        assertEquals("Account 2", accountTitle(account(Networks.evm(10143L), index = 1)))
    }

    @Test
    fun monogramTakesTheCapitalsOfTheNameOrItsFirstLetters() {
        assertEquals("PN", monogramOf(account(Networks.evm(10143L), name = "PulsarNova")))
        assertEquals("CS", monogramOf(account(Networks.evm(10143L), name = "Cold Storage")))
        assertEquals("TR", monogramOf(account(Networks.evm(10143L), name = "Trading")))
        assertEquals("E2", monogramOf(account(Networks.evm(10143L), index = 1)), "an unnamed account is its chain's letter and its place")
        assertEquals("B1", monogramOf(account(Networks.BTC_TESTNET4)), "a Bitcoin account with no name is B and its place")
    }

    @Test
    fun accountLineShowsTheAddressWithItsScriptOrItsAssetCount() {
        val held = account(Networks.evm(10143L), balance = coins("12.5"))
        assertEquals("0x0000 0000 … 0000 0001", accountLine(held, tokens = 0).substringBefore(" · "))
        assertEquals("1 asset", accountLine(held, tokens = 0).substringAfter(" · "))
        assertEquals("3 assets", accountLine(held, tokens = 2).substringAfter(" · "), "the coin and two tokens")
        val bitcoin = account(Networks.BTC_TESTNET4, script = "Native SegWit")
        assertEquals("Native SegWit", accountLine(bitcoin, tokens = 0).substringAfter(" · "))
    }

    @Test
    fun accountLineShowsOnlyTheAddressWhileNoReadHasLanded() {
        val unread = account(Networks.evm(10143L), balance = null)
        assertEquals("0x0000 0000 … 0000 0001", accountLine(unread, tokens = 0))
    }

    @Test
    fun foldKeepsAccountsWorthMoreThanTheThresholdAndAnyWithoutARead() {
        val big = account(Networks.evm(10143L), index = 0, balance = coins("10000"))
        val unread = account(Networks.evm(10143L), index = 1, balance = null)
        val priced = { _: WalletAccount -> 25_000.0 }
        val fold = foldAccounts(listOf(big, unread), priced)
        assertEquals(listOf(big, unread), fold.shown)
        assertEquals(emptyList(), fold.folded)
    }

    @Test
    fun foldTakesEmptyAccountsAndAccountsUnderTheThreshold() {
        val empty = account(Networks.evm(10143L), index = 0, balance = coins("0"))
        val small = account(Networks.evm(10143L), index = 1, balance = coins("4000"))
        val unpriced = account(Networks.evm(10143L), index = 2, balance = coins("3"))
        val big = account(Networks.evm(10143L), index = 3, balance = coins("9000"))
        val usdOf: (WalletAccount) -> Double? = { account -> if (account === big) 22_500.0 else if (account === small) 10_000.0 else null }
        val fold = foldAccounts(listOf(empty, small, unpriced, big), usdOf)
        assertEquals(listOf(unpriced, big), fold.shown, "shown accounts keep their order in the wallet")
        assertEquals(listOf(empty, small), fold.folded)
        assertEquals(1, fold.empty)
        assertEquals(1, fold.under)
        assertEquals("1 empty, 1 under \$13K", foldSummary(fold))
        assertEquals("2 More Accounts", moreAccountsTitle(2))
        assertEquals("1 More Account", moreAccountsTitle(1))
    }

    @Test
    fun foldSummaryNamesOnlyTheKindsThatAreThere() {
        val empty = account(Networks.evm(10143L), index = 0, balance = coins("0"))
        assertEquals("1 empty", foldSummary(foldAccounts(listOf(empty), usdOf = { null })))
        val small = account(Networks.evm(10143L), index = 1, balance = coins("1"))
        assertEquals("1 under \$13K", foldSummary(foldAccounts(listOf(small), usdOf = { 2.5 })))
    }

    @Test
    fun foldCountsTheTokensAnAccountHoldsWithItsCoin() {
        val empty = account(Networks.evm(10143L), index = 0, balance = coins("0"))
        val holdsTokens = account(Networks.evm(10143L), index = 1, balance = coins("0"))
        val tokenWorth = { account: WalletAccount -> if (account === holdsTokens) 20_000.0 else null }
        val fold = foldAccounts(listOf(empty, holdsTokens), usdOf = { null }, tokenUsdOf = tokenWorth)
        assertEquals(listOf(holdsTokens), fold.shown, "an empty coin with tokens worth the threshold stays in view")
        assertEquals(listOf(empty), fold.folded)
        val smallTokens = { _: WalletAccount -> 5_000.0 }
        assertEquals(emptyList(), foldAccounts(listOf(holdsTokens), usdOf = { null }, tokenUsdOf = smallTokens).shown)
    }

    @Test
    fun groupUsdIsNullWhenNoAccountHasAPrice() {
        val priced = account(Networks.evm(10143L), index = 0, balance = coins("1"))
        val unpriced = account(Networks.BTC_TESTNET4, index = 0, balance = null)
        val usdOf: (WalletAccount) -> Double? = { account -> if (account === priced) 25.0 else null }
        assertEquals(25.0, groupUsd(listOf(priced, unpriced), usdOf))
        assertNull(groupUsd(listOf(unpriced), usdOf))
    }
}
