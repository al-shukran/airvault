package app.lensly.app

import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Algorand accounts of a wallet: the public key of each one is stored in the wallet's Algorand record, numbered after the ones the wallet
 * has. Account 0 has the address that the Ledger Algorand app shows for the same phrase. A Single Key wallet has no seed, so it refuses them.
 * The Algorand App is listed after Solana, and the pairing does not carry it yet.
 */
class WalletStoreAlgorandTest {
    private val keys = MemoryKeystore()

    @get:Rule
    val tmp = TemporaryFolder()

    /** The 24-word phrase of the Mnemonic A vectors in LedgerEd25519Test and AlgoAddressTest. */
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
    fun aNewWalletHasNoAlgorandAccounts() {
        assertEquals(emptyList(), store.algorandKeys(newWallet()))
    }

    @Test
    fun accountZeroHasTheAddressTheLedgerAlgorandAppShows() {
        val id = newWallet()

        store.addAlgorandAccounts(id, 1, cap)

        assertEquals(ACCOUNT_0_ADDRESS, AlgorandAccount.addressOf(store.algorandKeys(id).single()))
    }

    @Test
    fun aBulkRunStoresThePublicKeyOfEachAccountInOrder() {
        val id = newWallet()
        val seed = seed()

        val added = store.addAlgorandAccounts(id, 3, cap)

        val expected = (0..2).map { AlgorandAccount.publicKey(seed, it) }
        assertEquals(expected.map { it.toList() }, added?.map { it.toList() })
        assertEquals(expected.map { it.toList() }, store.algorandKeys(id).map { it.toList() })
    }

    @Test
    fun aLaterRunNumbersAfterTheAccountsTheWalletAlreadyHas() {
        val id = newWallet()
        val seed = seed()
        store.addAlgorandAccounts(id, 2, cap)

        store.addAlgorandAccounts(id, 2, cap)

        val keys = store.algorandKeys(id)
        assertEquals(4, keys.size)
        assertContentEquals(AlgorandAccount.publicKey(seed, 3), keys[3])
    }

    @Test
    fun aRunOverTheBulkCapIsRefusedAndSavesNothing() {
        val id = newWallet()

        assertFailsWith<IllegalArgumentException> { store.addAlgorandAccounts(id, 3, cap = 2) }
        assertEquals(emptyList(), store.algorandKeys(id))
    }

    @Test
    fun aSingleKeyWalletRefusesAlgorandAccounts() {
        val privateKey = ByteArray(32).also { it[31] = 1 }
        val id = store.createSingleKey("Single", privateKey, nowMs = 1L)

        assertFailsWith<IllegalArgumentException> { store.addAlgorandAccounts(id, 1, cap) }
    }

    @Test
    fun theAlgorandAppIsListedLastAndThePairingCarriesNoAlgorandApp() {
        val id = newWallet()
        val seed = seed()
        store.addAlgorandAccounts(id, 2, cap)

        assertEquals(WalletApp.ALGORAND, WalletApp.fromRouteId("algorand"))
        assertEquals(listOf(WalletApp.BITCOIN, WalletApp.ETHEREUM, WalletApp.SOLANA, WalletApp.ALGORAND), WalletApp.entries)
        assertNull(WalletApp.ALGORAND.pairedApp(store, id, seed))
        assertEquals(listOf("bitcoin", "ethereum", "solana"), WalletApp.entries.mapNotNull { it.pairedApp(store, id, seed)?.app })
    }

    private companion object {
        /** The address of Mnemonic A account 0, as LedgerEd25519Test and AlgoAddressTest assert it for the same public key. */
        const val ACCOUNT_0_ADDRESS = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"
    }
}
