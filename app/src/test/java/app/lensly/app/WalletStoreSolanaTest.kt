package app.lensly.app

import app.lensly.app.export.SolanaPairingKeys
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.wallet.HdWallet
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Solana accounts of a wallet: the public key of each one is stored in the wallet's Solana record, numbered after the ones the wallet
 * has, and the pairing carries each key on its path m/44'/501'/N'. A Single Key wallet has no seed, so it refuses them.
 */
class WalletStoreSolanaTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private val cap = BulkCap.DEFAULT.accounts
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it + 11).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    private fun newWallet(): String = store.create(
        name = "Test",
        deviceGenerated = false,
        words = words,
        passphrase = null,
        nowMs = 1L,
    )

    private fun seed(): ByteArray = Mnemonic.of(words).use { it.deriveSeed("") }

    @Test
    fun aNewWalletHasOneSolanaAccountAtIndexZero() {
        val id = newWallet()

        assertContentEquals(SolanaAccount.publicKey(seed(), 0), store.solanaKeys(id).single())
    }

    @Test
    fun aBulkRunStoresThePublicKeyOfEachAccountInOrder() {
        val id = newWallet()
        val seed = seed()

        val added = store.addSolanaAccounts(id, 3, cap)

        val expected = (1..3).map { SolanaAccount.publicKey(seed, it) }
        assertEquals(expected.map { it.toList() }, added?.map { it.toList() })
        assertEquals((0..3).map { SolanaAccount.publicKey(seed, it).toList() }, store.solanaKeys(id).map { it.toList() })
    }

    @Test
    fun aLaterRunNumbersAfterTheAccountsTheWalletAlreadyHas() {
        val id = newWallet()
        val seed = seed()
        store.addSolanaAccounts(id, 2, cap)

        store.addSolanaAccounts(id, 2, cap)

        val keys = store.solanaKeys(id)
        assertEquals(5, keys.size)
        assertEquals(SolanaAccount.addressOf(SolanaAccount.publicKey(seed, 3)), SolanaAccount.addressOf(keys[3]))
    }

    @Test
    fun aRunOverTheBulkCapIsRefusedAndSavesNothing() {
        val id = newWallet()

        assertFailsWith<IllegalArgumentException> { store.addSolanaAccounts(id, 3, cap = 2) }
        assertEquals(listOf(SolanaAccount.publicKey(seed(), 0).toList()), store.solanaKeys(id).map { it.toList() })
    }

    @Test
    fun aSolanaWalletKeepsAtMostOneHundredAccounts() {
        // The wallet starts with one Solana account, so a run of 99 reaches the cap of 100.
        val id = newWallet()
        assertNotNull(store.addSolanaAccounts(id, 99, cap = BulkCap.HUNDRED.accounts))

        val refusal = assertFailsWith<SolanaCapReached> { store.addSolanaAccounts(id, 1, cap = BulkCap.HUNDRED.accounts) }
        assertEquals("A wallet keeps at most 100 Solana accounts.", refusal.message)
        assertEquals(100, store.solanaKeys(id).size)
    }

    @Test
    fun aSingleKeyWalletRefusesSolanaAccounts() {
        val privateKey = ByteArray(32).also { it[31] = 1 }
        val id = store.createSingleKey("Single", privateKey, nowMs = 1L)

        assertFailsWith<IllegalArgumentException> { store.addSolanaAccounts(id, 1, cap) }
    }

    @Test
    fun thePairingCarriesEachKeyOnItsLedgerLivePathWithItsChainCode() {
        val id = newWallet()
        val seed = seed()
        store.addSolanaAccounts(id, 2, cap)

        val accounts = SolanaPairingKeys.paired(store, id, seed, HdWallet(seed).masterFingerprint)

        assertEquals(
            (0..2).map { index -> listOf(hardened(44), hardened(501), hardened(index.toLong())) },
            accounts.map { account -> account.export.signer.path.map { step -> if (step.hardened) hardened(step.index.toLong()) else step.index.toLong() } },
        )
        accounts.forEachIndexed { index, account ->
            assertEquals(3, account.export.signer.path.size)
            assertContentEquals(SolanaAccount.publicKey(seed, index), account.export.publicKey.bytes)
            assertEquals(null, account.export.chainCode)
        }
        assertEquals("Test 0", accounts.first().name)
    }

    @Test
    fun theSolanaAppIsListedAfterEthereum() {
        assertEquals(listOf(WalletApp.BITCOIN, WalletApp.ETHEREUM, WalletApp.SOLANA, WalletApp.ALGORAND), WalletApp.entries)
        assertEquals(WalletApp.SOLANA, WalletApp.fromRouteId("solana"))
    }

    private fun hardened(index: Long): Long = 0x80000000L + index
}
