package app.lensly.app

import app.lensly.app.export.SolanaPairingKeys
import app.lensly.core.solana.SolanaAccount
import app.lensly.core.solana.SolanaSigner
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    fun aNewWalletHasNoSolanaAccounts() {
        assertEquals(emptyList(), store.solanaKeys(newWallet()))
    }

    @Test
    fun aBulkRunStoresThePublicKeyOfEachAccountInOrder() {
        val id = newWallet()
        val seed = seed()

        val added = store.addSolanaAccounts(id, 3, cap)

        val expected = (0..2).map { SolanaAccount.publicKey(seed, it) }
        assertEquals(expected.map { it.toList() }, added?.map { it.toList() })
        assertEquals(expected.map { it.toList() }, store.solanaKeys(id).map { it.toList() })
    }

    @Test
    fun aLaterRunNumbersAfterTheAccountsTheWalletAlreadyHas() {
        val id = newWallet()
        val seed = seed()
        store.addSolanaAccounts(id, 2, cap)

        store.addSolanaAccounts(id, 2, cap)

        val keys = store.solanaKeys(id)
        assertEquals(4, keys.size)
        assertEquals(SolanaAccount.addressOf(SolanaAccount.publicKey(seed, 3)), SolanaAccount.addressOf(keys[3]))
    }

    @Test
    fun aRunOverTheBulkCapIsRefusedAndSavesNothing() {
        val id = newWallet()

        assertFailsWith<IllegalArgumentException> { store.addSolanaAccounts(id, 3, cap = 2) }
        assertEquals(emptyList(), store.solanaKeys(id))
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

        val app = SolanaPairingKeys.paired(WalletApp.SOLANA.routeId, store, id, seed)

        assertEquals("solana", app.app)
        assertEquals(listOf(listOf(hardened(44), hardened(501), hardened(0)), listOf(hardened(44), hardened(501), hardened(1))), app.keys.map { it.path })
        app.keys.forEachIndexed { index, key ->
            assertEquals(3, key.depth)
            assertEquals(0.toByte(), key.publicKey[0])
            assertContentEquals(SolanaAccount.publicKey(seed, index), key.publicKey.copyOfRange(1, key.publicKey.size))
            assertContentEquals(SolanaAccount.chainCode(seed, index), key.chainCode)
        }
        assertEquals(listOf(0, 1), app.accounts.map { it.key })
        assertEquals("Test 0", app.accounts.first().name)
    }

    @Test
    fun theSolanaAppIsListedAfterEthereum() {
        assertEquals(listOf(WalletApp.BITCOIN, WalletApp.ETHEREUM, WalletApp.SOLANA, WalletApp.ALGORAND), WalletApp.entries)
        assertEquals(WalletApp.SOLANA, WalletApp.fromRouteId("solana"))
    }

    /** The signature of account 1 verifies with the public key the wallet stores for account 1, and with no other. */
    @Test
    fun aSolanaSignatureVerifiesWithTheStoredKeyOfItsAccount() {
        val id = newWallet()
        store.addSolanaAccounts(id, 2, cap)
        val message = byteArrayOf(1, 2, 3, 4)

        val signature = store.solanaSign(id, 1, message)

        assertTrue(SolanaSigner.verify(store.solanaKeys(id)[1], message, signature))
        assertFalse(SolanaSigner.verify(store.solanaKeys(id)[0], message, signature))
    }

    @Test
    fun aSolanaSignatureForAnAccountTheWalletDoesNotHaveIsRefused() {
        val id = newWallet()
        store.addSolanaAccounts(id, 1, cap)
        assertFailsWith<IllegalArgumentException> { store.solanaSign(id, 1, byteArrayOf(1)) }
    }

    private fun hardened(index: Long): Long = 0x80000000L + index
}
