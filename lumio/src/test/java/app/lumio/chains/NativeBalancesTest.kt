package app.lumio.chains

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.vault.WalletRecordNames
import app.lumio.data.FakeProvider
import app.lumio.data.MonotonicClock
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Op
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.algorand.ALGORAND_MAINNET
import app.lumio.data.provider.algorand.ALGORAND_TESTNET
import app.lumio.data.provider.algorand.AlgorandAlgod
import app.lumio.data.provider.solana.SOLANA_DEVNET
import app.lumio.data.provider.solana.SOLANA_MAINNET
import app.lumio.data.provider.solana.SolanaRpc
import app.lumio.data.rpc.JsonRpc
import app.lumio.data.sched.Scheduler
import app.lumio.home.HomeFormat
import app.lumio.home.HomeLoader
import app.lumio.network.NetworkMode
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairRecords
import app.lumio.pair.PairedWallet
import app.lumio.pair.PairedWallets
import java.math.BigInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Algorand and Solana native balances: a refresh stores each coin's balance in the vault, Home lists it in the coin's own unit, and a read that fails keeps
 * the balance it had.
 */
class NativeBalancesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val session by lazy { VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 1).toByte() }) }
    private val algorand = FakeProvider(id = "algorand", networks = setOf(ALGORAND_TESTNET, ALGORAND_MAINNET), ops = setOf(Op.NATIVE_BALANCE))
    private val solana = FakeProvider(id = "solana", networks = setOf(SOLANA_DEVNET, SOLANA_MAINNET), ops = setOf(Op.NATIVE_BALANCE))
    private val scheduler = Scheduler(
        listOf(algorand, solana),
        { emptyMap() },
        JsonRpc.newHttp(),
        MonotonicClock,
        Random(7),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** Pairs one wallet, with an Ethereum account and the first Algorand and Solana accounts of the test seed, and returns the vault's wallets. */
    private fun pairedWallets(): List<PairedWallet> {
        val id = session.createWallet()
        val records = mapOf(
            WalletRecordNames.META to PairRecords.encodeMeta(
                PairRecords.Meta(fingerprint = 1L, createdAtMs = 0L, name = "Demo", accountKeys = listOf(PairFixtures.addressKey(PairFixtures.seed, 0))),
            ),
            WalletRecordNames.account(0) to PairRecords.encodeAccount(PairRecords.Account(name = null, label = null, group = null)),
            WalletRecordNames.ALGORAND to PairRecords.encodeAlgorand(listOf(AlgorandAccount.publicKey(PairFixtures.seed, 0))),
            WalletRecordNames.SOLANA to PairRecords.encodeSolana(listOf(SolanaAccount.publicKey(PairFixtures.seed, 0))),
        )
        session.putRecords(id, records, emptyList())
        return PairedWallets.list(session)
    }

    /** Refreshes the testnet coins of [wallets] at the time [at], and returns the failures. */
    private fun refresh(wallets: List<PairedWallet>, at: Long): List<NativeFailure> = runBlocking {
        NativeBalances.refresh(session, wallets, NativeBalances.coinsOf(NetworkMode.TESTNET), scheduler, nowMs = { at })
    }

    /** The lines Home shows for the testnet side: each amount in its coin's unit, with the symbol. */
    private fun homeLines(wallets: List<PairedWallet>): List<String> =
        HomeLoader.nativeCached(session, wallets, NetworkMode.TESTNET).map { HomeFormat.coins(it.balance) + " " + it.symbol }

    /** The lines Home shows for the mainnet side: each amount in its coin's unit, with the symbol. */
    private fun mainnetLines(wallets: List<PairedWallet>): List<String> =
        HomeLoader.nativeCached(session, wallets, NetworkMode.MAINNET).map { HomeFormat.coins(it.balance) + " " + it.symbol }

    @Test
    fun aRefreshStoresEachCoinAndHomeListsItInItsOwnUnit() {
        val wallets = pairedWallets()
        algorand.outcomes.add(BigInteger("1500000"))
        solana.outcomes.add(BigInteger("1500000000"))

        val failures = refresh(wallets, at = 1_000L)

        assertEquals(emptyList<NativeFailure>(), failures)
        assertEquals(listOf("1500000", "1500000000"), NativeBalanceCache.read(session).map { it.amount })
        assertEquals(listOf("ALGO-testnet", "SOL-devnet"), HomeLoader.nativeCached(session, wallets, NetworkMode.TESTNET).map { it.id })
        assertEquals(listOf("1.5 ALGO", "1.5 SOL"), homeLines(wallets))
    }

    @Test
    fun aFailedReadKeepsThePreviousBalanceAndReportsTheFailure() {
        val wallets = pairedWallets()
        algorand.outcomes.add(BigInteger("1500000"))
        solana.outcomes.add(BigInteger("1500000000"))
        refresh(wallets, at = 1_000L)

        algorand.outcomes.add(ProviderError.Fatal("The endpoint refused the read."))
        solana.outcomes.add(BigInteger("2000000000"))
        val failures = refresh(wallets, at = 2_000L)

        assertEquals(listOf(ALGORAND_TESTNET), failures.map { it.network })
        val cached = NativeBalanceCache.read(session).associateBy { it.network }
        assertEquals("1500000", cached.getValue(ALGORAND_TESTNET).amount)
        assertEquals(1_000L, cached.getValue(ALGORAND_TESTNET).atMs)
        assertEquals(listOf("1.5 ALGO", "2 SOL"), homeLines(wallets))
    }

    @Test
    fun eachSideReadsExactlyItsTwoCoinsAtTheirPublicEndpoints() {
        assertEquals(
            listOf(
                Triple(ALGORAND_MAINNET, "ALGO", "https://mainnet-api.algonode.cloud"),
                Triple(SOLANA_MAINNET, "SOL", "https://api.mainnet-beta.solana.com"),
            ),
            NativeBalances.coinsOf(NetworkMode.MAINNET).map { Triple(it.network, it.symbol, endpointOf(it.network)) },
        )
        assertEquals(
            listOf(
                Triple(ALGORAND_TESTNET, "ALGO", "https://testnet-api.algonode.cloud"),
                Triple(SOLANA_DEVNET, "SOL", "https://api.devnet.solana.com"),
            ),
            NativeBalances.coinsOf(NetworkMode.TESTNET).map { Triple(it.network, it.symbol, endpointOf(it.network)) },
        )
    }

    /** The public endpoint that serves [network], from the provider tables. */
    private fun endpointOf(network: NetworkId): String = when (network.family) {
        Family.ALGO -> AlgorandAlgod.ENDPOINTS.getValue(network.chain)
        Family.SOL -> SolanaRpc.ENDPOINTS.getValue(network.chain)
        else -> error("not a native family: $network")
    }

    @Test
    fun theMainnetSideReadsTheMainnetCoins() {
        val wallets = pairedWallets()
        algorand.outcomes.add(BigInteger("2500000"))
        solana.outcomes.add(BigInteger("500000000"))

        val failures = runBlocking {
            NativeBalances.refresh(session, wallets, NativeBalances.coinsOf(NetworkMode.MAINNET), scheduler, nowMs = { 1_000L })
        }

        assertTrue(failures.isEmpty())
        assertEquals(1, algorand.calls)
        assertEquals(1, solana.calls)
        assertEquals(listOf(ALGORAND_MAINNET, SOLANA_MAINNET), NativeBalanceCache.read(session).map { it.network })
        assertEquals(listOf("2.5 ALGO", "0.5 SOL"), mainnetLines(wallets))
        assertTrue(HomeLoader.nativeCached(session, wallets, NetworkMode.TESTNET).isEmpty())
    }
}
