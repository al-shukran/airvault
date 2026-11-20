package app.lensly.app

import app.airvault.core.model.Curve
import app.lensly.app.export.AlgorandPairingKeys
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.wallet.Ed25519Signer
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.WalletKeyAccess
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Algorand accounts of a wallet: the public key of each one is stored in the wallet's Algorand record, numbered after the ones the wallet
 * has. Account 0 has the address that the Ledger Algorand app shows for the same phrase. A Single Key wallet has no seed, so it refuses them.
 * The Algorand App is listed after Solana, and the pairing carries each account's key on its path m/44'/283'/N'/0/0.
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
    fun theAlgorandAppIsListedLastAndThePairingCarriesIt() {
        val id = newWallet()
        val seed = seed()
        store.addAlgorandAccounts(id, 2, cap)

        assertEquals(WalletApp.ALGORAND, WalletApp.fromRouteId("algorand"))
        assertEquals(listOf(WalletApp.BITCOIN, WalletApp.ETHEREUM, WalletApp.SOLANA, WalletApp.ALGORAND), WalletApp.entries)
        val fingerprint = HdWallet(seed).masterFingerprint
        assertEquals(listOf("algorand", "algorand"), WalletApp.ALGORAND.pairedAccounts(store, id, seed, fingerprint).map { it.export.chain.namespace })
        // The pairing lists the accounts of every App in the order of the Apps, so the two Algorand accounts come last.
        assertEquals(listOf("algorand", "algorand"), WalletApp.entries.flatMap { it.pairedAccounts(store, id, seed, fingerprint) }.takeLast(2).map { it.export.chain.namespace })
    }

    @Test
    fun thePairingCarriesEachKeyOnItsLedgerAlgorandPathWithItsChainCode() {
        val id = newWallet()
        val seed = seed()
        store.addAlgorandAccounts(id, 2, cap)

        val accounts = AlgorandPairingKeys.paired(store, id, seed, HdWallet(seed).masterFingerprint)

        assertEquals(
            listOf(
                listOf(hardened(44), hardened(283), hardened(0), 0L, 0L),
                listOf(hardened(44), hardened(283), hardened(1), 0L, 0L),
            ),
            accounts.map { account -> account.export.signer.path.map { step -> if (step.hardened) hardened(step.index.toLong()) else step.index.toLong() } },
        )
        accounts.forEachIndexed { index, account ->
            assertEquals(5, account.export.signer.path.size)
            assertContentEquals(AlgorandAccount.publicKey(seed, index), account.export.publicKey.bytes)
            assertEquals(null, account.export.chainCode)
        }
        assertEquals(listOf(0, 1), accounts.map { it.export.signer.path[2].index })
        assertEquals("Test 0", accounts.first().name)
    }

    @Test
    fun theKeyAccessOfAWalletSignsWithTheStoredKeyOfTheAccountItIsAskedFor() {
        val id = newWallet()
        store.addAlgorandAccounts(id, 2, cap)
        val message = "TX".toByteArray(Charsets.US_ASCII) + ByteArray(8) { it.toByte() }

        val signature = store.withKeys(id) { keys ->
            keys.signEd25519(Curve.ED25519_BIP32, WalletKeyAccess.algorandPath(1), message)
        }

        assertTrue(Ed25519Signer.verify(store.algorandKeys(id)[1], message, signature))
    }

    private fun hardened(index: Long): Long = 0x80000000L + index

    private companion object {
        /** The address of Mnemonic A account 0, as LedgerEd25519Test and AlgoAddressTest assert it for the same public key. */
        const val ACCOUNT_0_ADDRESS = "OSI2442FE4NVQHRW4RVLUWO5AF43TPIUM7DS4PDGB7EMFPJMMU4KNIN3GQ"
    }
}
