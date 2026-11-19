package app.lensly.app

import app.airvault.core.model.Curve
import app.lensly.app.export.AlgorandPairingKeys
import app.lensly.core.algorand.AlgorandAccount
import app.lensly.core.wallet.Ed25519Signer
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
        assertEquals("algorand", WalletApp.ALGORAND.pairedApp(store, id, seed)?.app)
        assertEquals(
            listOf("bitcoin", "ethereum", "solana", "algorand"),
            WalletApp.entries.mapNotNull { it.pairedApp(store, id, seed)?.app },
        )
    }

    @Test
    fun thePairingCarriesEachKeyOnItsLedgerAlgorandPathWithItsChainCode() {
        val id = newWallet()
        val seed = seed()
        store.addAlgorandAccounts(id, 2, cap)

        val app = AlgorandPairingKeys.paired(WalletApp.ALGORAND.routeId, store, id, seed)

        assertEquals("algorand", app.app)
        assertEquals(
            listOf(
                listOf(hardened(44), hardened(283), hardened(0), 0L, 0L),
                listOf(hardened(44), hardened(283), hardened(1), 0L, 0L),
            ),
            app.keys.map { it.path },
        )
        app.keys.forEachIndexed { index, key ->
            assertEquals(5, key.path.size)
            assertContentEquals(AlgorandAccount.publicKey(seed, index), key.material)
            assertEquals(null, key.chainCode)
        }
        assertEquals(listOf(0, 1), app.accounts.map { it.key })
        assertEquals("Test 0", app.accounts.first().name)
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

        /** The public key of account 0, which is the sender key of [PAY_BASIC]. */
        const val ACCOUNT_0_PUB = "7491ae7345271b581e36e46aba59dd0179b9bd1467c72e3c660fc8c2bd2c6538"

        /** The SDK payment `pay_basic` from account 0 to account 1, the transaction the review checks and the signature covers. */
        const val PAY_BASIC =
            "89a3616d74ce0016e360a3666565cd03e8a26676ce02faf080a367656eac746573746e65742d76312e30a26768c4204863b5" +
                "18a4b3c84ec810f22d4f1081cb0f71f059a7ac20dec62f7f70e5093a22a26c76ce02faf468a3726376c420c11dff7e914904" +
                "1e8555aa0fccb8226f48731d74ec4507c43213e1b705f5615da3736e64c4207491ae7345271b581e36e46aba59dd0179b9bd" +
                "1467c72e3c660fc8c2bd2c6538a474797065a3706179"
    }
}
