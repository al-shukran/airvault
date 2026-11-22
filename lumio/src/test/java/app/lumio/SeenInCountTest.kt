package app.lumio

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.btc.BtcNetwork
import app.lensly.core.btc.ScriptType
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lumio.data.ETH
import app.lumio.data.db.LumioDatabase
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.tx.LumioTx
import app.lumio.data.tx.TxAsset
import app.lumio.data.tx.TxDirection
import app.lumio.data.tx.TxKeys
import app.lumio.data.tx.TxStatus
import app.lumio.data.tx.TxStore
import app.lumio.pair.PairFixtures
import app.lumio.pair.PairedWallet
import java.io.File
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The count that the address sheet shows, on Robolectric with a real store: one transaction on each chain, each held by the account that owns it, and
 * each with the counterparty that the sheet is opened on.
 */
@RunWith(RobolectricTestRunner::class)
class SeenInCountTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: LumioDatabase
    private lateinit var store: TxStore

    private val alpha = PairedWallet(
        id = "alpha",
        name = "Alpha",
        fingerprint = PairFixtures.fingerprint(PairFixtures.seed).toLong(),
        createdAtMs = 1L,
        bitcoin = listOf(PairFixtures.account(PairFixtures.seed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT)),
        evmAccounts = PairFixtures.evmAccounts(1),
        solana = listOf(SolanaAccount.publicKey(PairFixtures.seed, 0)),
    )
    private val beta = PairedWallet(
        id = "beta",
        name = "Beta",
        fingerprint = PairFixtures.fingerprint(PairFixtures.otherSeed).toLong(),
        createdAtMs = 2L,
        bitcoin = emptyList(),
        algorand = listOf(AlgorandAccount.publicKey(PairFixtures.otherSeed, 0)),
    )

    private val evmCounterparty = PairFixtures.address(PairFixtures.otherSeed, 0)
    private val btcCounterparty = PairFixtures.account(PairFixtures.otherSeed, BtcNetwork.MAINNET, ScriptType.NATIVE_SEGWIT).receiveAddress(0)
    private val solCounterparty = SolanaAccount.addressOf(SolanaAccount.publicKey(PairFixtures.otherSeed, 1))
    private val algoCounterparty = AlgorandAccount.addressOf(AlgorandAccount.publicKey(PairFixtures.otherSeed, 1))

    @Before
    fun open() {
        database = LumioDatabase.open(context, File(tmp.root, "tx.db"))
        store = TxStore(database.txs(), keys())
    }

    @After
    fun close() {
        database.close()
    }

    private fun keys(): TxKeys =
        TxKeys.from(VaultSession(VaultLayout(tmp.root), MemoryKeystore(), ByteArray(32) { (it * 5 + 1).toByte() }))

    private fun tx(n: Int, network: NetworkId, account: String, counterparty: String, asset: TxAsset): LumioTx = LumioTx(
        id = "$network:$n",
        network = network,
        account = account,
        hash = "0x" + n.toString(16).padStart(64, '0'),
        timestampMs = n * 1_000L,
        asset = asset,
        amount = BigInteger.ONE,
        direction = TxDirection.IN,
        fee = null,
        counterparty = counterparty,
        status = TxStatus.CONFIRMED,
        confirmations = 1_000L,
        inputData = null,
        memo = null,
        spam = false,
    )

    @Test
    fun eachChainCounterpartyIsCountedAcrossTheWalletsAccounts() = runBlocking {
        val btc = NetworkId(Networks.BTC_MAINNET, testnet = false)
        val sol = NetworkId(Networks.SOL_MAINNET, testnet = false)
        val algo = NetworkId(Networks.ALGO_MAINNET, testnet = false)
        store.put(
            listOf(
                tx(1, ETH, alpha.evmAddresses.single(), evmCounterparty, TxAsset(null, "ETH", "Ether", 18)),
                tx(2, btc, alpha.bitcoin.single().receiveAddress(0), btcCounterparty, TxAsset(null, "BTC", "Bitcoin", 8)),
                tx(3, sol, SolanaAccount.addressOf(SolanaAccount.publicKey(PairFixtures.seed, 0)), solCounterparty, TxAsset(null, "SOL", "Solana", 9)),
                tx(4, algo, AlgorandAccount.addressOf(AlgorandAccount.publicKey(PairFixtures.otherSeed, 0)), algoCounterparty, TxAsset(null, "ALGO", "Algorand", 6)),
            ),
        )
        assertEquals(1, seenInCount(store, alpha, evmCounterparty))
        assertEquals(1, seenInCount(store, alpha, btcCounterparty))
        assertEquals(1, seenInCount(store, alpha, solCounterparty))
        assertEquals(1, seenInCount(store, beta, algoCounterparty))
        assertEquals(0, seenInCount(store, beta, solCounterparty))
    }
}
