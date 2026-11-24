package app.lumio.wallets

import app.airvault.core.model.AccountId
import app.airvault.core.model.ChainId
import app.lensly.core.btc.BtcNetwork
import app.lumio.btc.networkId
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.home.NativeAsset
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The state of the wallet screens' balances: what each row and each island shows for the reads it has, and the counters of the status line. */
class WalletProgressTest {

    private val bitcoin: ChainId = BtcNetwork.MAINNET.networkId().chain
    private val ethereum: ChainId = Networks.evm(1L)
    private val bitcoinCoin = AccountCoin("BTC", "Bitcoin", BtcNetwork.MAINNET.networkId())
    private val ethereumCoin = AccountCoin("ETH", "Ethereum", NetworkId(Networks.evm(1L), false))
    private val keptBitcoin = NativeAsset(id = "btc", symbol = "BTC", name = "Bitcoin", network = BtcNetwork.MAINNET.networkId(), balance = BigDecimal("0.5"))
    private val keptEthereum = NativeAsset(id = "eth", symbol = "ETH", name = "Ethereum", network = NetworkId(Networks.evm(1L), false), balance = BigDecimal("2"))

    private fun account(
        chain: ChainId,
        coin: AccountCoin?,
        balance: NativeAsset? = null,
        incomplete: Boolean = false,
        readAtMs: Long? = null,
    ) = WalletAccount(
        walletId = "wallet",
        id = AccountId.of(chain, "address-${chain.namespace}"),
        index = 0,
        networkName = "Test",
        coin = coin,
        balance = balance,
        incomplete = incomplete,
        readAtMs = readAtMs,
    )

    @Test
    fun anAccountWithNoBalanceIsCheckingUntilItsChainHasAnswered() {
        val unread = account(bitcoin, bitcoinCoin)

        assertEquals(BalanceSlot.Checking, balanceSlotOf(unread, WalletReads()))
        assertEquals(
            BalanceSlot.Checking,
            balanceSlotOf(unread, WalletReads(checking = setOf(Networks.BIP122), landed = setOf(Networks.BIP122))),
        )
        assertEquals(BalanceSlot.NotChecked, balanceSlotOf(unread, WalletReads(landed = setOf(Networks.BIP122))))
    }

    @Test
    fun aLastKnownBalanceShowsWhileItsChainIsReadAgain() {
        val kept = account(bitcoin, bitcoinCoin, balance = keptBitcoin, readAtMs = 1_000L)
        val reads = WalletReads(checking = setOf(Networks.BIP122), startedAtMs = mapOf(Networks.BIP122 to 5_000L))

        assertEquals(BalanceSlot.Value(incomplete = false, stale = true), balanceSlotOf(kept, reads))
        assertFalse(failureShown(listOf(kept), Networks.BIP122, reads))
    }

    @Test
    fun aPartialBitcoinScanShowsItsBalanceAsIncomplete() {
        val partial = account(bitcoin, bitcoinCoin, balance = keptBitcoin, incomplete = true, readAtMs = 6_000L)
        val reads = WalletReads(
            landed = setOf(Networks.BIP122),
            startedAtMs = mapOf(Networks.BIP122 to 5_000L),
            unreachable = setOf(bitcoin),
        )

        assertEquals(BalanceSlot.Value(incomplete = true, stale = false), balanceSlotOf(partial, reads))
        assertTrue(failureShown(listOf(partial), Networks.BIP122, reads))
    }

    @Test
    fun aFailedChainSaysSoOnlyWhileAValueIsMissingOldOrIncomplete() {
        val reads = WalletReads(
            landed = setOf(Networks.EIP155),
            startedAtMs = mapOf(Networks.EIP155 to 5_000L),
            unreachable = setOf(ethereum),
        )
        val current = account(ethereum, ethereumCoin, balance = keptEthereum, readAtMs = 6_000L)
        val old = account(ethereum, ethereumCoin, balance = keptEthereum, readAtMs = 1_000L)
        val missing = account(ethereum, ethereumCoin)

        assertFalse(failureShown(listOf(current), Networks.EIP155, reads))
        assertTrue(failureShown(listOf(current, old), Networks.EIP155, reads))
        assertTrue(failureShown(listOf(current, missing), Networks.EIP155, reads))
        assertFalse(failureShown(listOf(current, old), Networks.EIP155, reads.copy(unreachable = emptySet())))
    }

    @Test
    fun aChainThatIsBeingReadNamesNoFailureYet() {
        val missing = account(ethereum, ethereumCoin)
        val reads = WalletReads(checking = setOf(Networks.EIP155), unreachable = setOf(ethereum))

        assertFalse(failureShown(listOf(missing), Networks.EIP155, reads))
        assertEquals(BalanceSlot.Checking, balanceSlotOf(missing, reads))
    }

    @Test
    fun anAccountWithoutANativeCoinShowsNothing() {
        assertEquals(BalanceSlot.None, balanceSlotOf(account(ethereum, coin = null), WalletReads()))
    }

    @Test
    fun theStatusLineCountsTheBitcoinAccountsItHasChecked() {
        assertEquals(
            "Checking Bitcoin · 3 of 8 accounts",
            checkingLine(WalletReads(checking = setOf(Networks.BIP122), bitcoinDone = 3, bitcoinTotal = 8)),
        )
        assertEquals(
            "Checking Bitcoin · 0 of 1 account",
            checkingLine(WalletReads(checking = setOf(Networks.BIP122), bitcoinDone = 0, bitcoinTotal = 1)),
        )
        assertEquals("Checking Ethereum", checkingLine(WalletReads(checking = setOf(Networks.EIP155))))
        assertEquals(
            "Checking Bitcoin · 3 of 8 accounts",
            checkingLine(WalletReads(checking = setOf(Networks.SOLANA, Networks.BIP122), bitcoinDone = 3, bitcoinTotal = 8)),
        )
        assertNull(checkingLine(WalletReads()))
    }

    @Test
    fun theTotalSaysItIsCheckingThePriceUntilThePricesHaveBeenRead() {
        assertTrue(isCheckingPrice(WalletReads()))
        assertTrue(isCheckingPrice(WalletReads(priced = true, pricing = true)))
        assertFalse(isCheckingPrice(WalletReads(priced = true)))
    }

    @Test
    fun aLandedReadReplacesOnlyTheUnreachableChainsOfItsFamilies() {
        val unreachable = setOf(ethereum, bitcoin)

        assertEquals(setOf(bitcoin), landedUnreachable(unreachable, setOf(Networks.EIP155), emptySet()))
        assertEquals(setOf(ethereum), landedUnreachable(unreachable, setOf(Networks.BIP122), emptySet()))
        assertEquals(setOf(bitcoin, ethereum), landedUnreachable(unreachable, setOf(Networks.SOLANA), setOf(ethereum, bitcoin)))
    }

    @Test
    fun aRetryOfSolanaOrAlgorandReadsBothNativeFamilies() {
        assertEquals(setOf(Networks.SOLANA, Networks.ALGORAND), readFamilies(Networks.SOLANA))
        assertEquals(setOf(Networks.BIP122), readFamilies(Networks.BIP122))
    }

    @Test
    fun theFailureNoteNamesTheNetwork() {
        assertEquals("Couldn't reach Bitcoin. Showing the last known balance.", unreachableNote("Bitcoin", keptBalance = true))
    }

    @Test
    fun theFailureNoteDoesNotPromiseABalanceTheChainDoesNotKeep() {
        assertEquals("Couldn't reach Bitcoin.", unreachableNote("Bitcoin", keptBalance = false))
    }
}
