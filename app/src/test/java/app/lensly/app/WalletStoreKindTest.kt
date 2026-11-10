package app.lensly.app

import app.lensly.core.vault.VaultLayout
import app.lensly.core.vault.VaultSession
import app.lensly.core.wallet.BulkCap
import app.lensly.core.wallet.HdWallet
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.web3j.utils.Numeric

/** Storage round trips for both wallet kinds, and the load of a wallet written before kinds existed. */
class WalletStoreKindTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val words = List(23) { "abandon" } + "art"
    private val cap = BulkCap.DEFAULT.accounts
    private lateinit var session: VaultSession
    private lateinit var store: WalletStore

    @Before
    fun openVault() {
        session = VaultSession(VaultLayout(tmp.root), ByteArray(32) { (it + 7).toByte() })
        store = WalletStore(session)
    }

    @After
    fun closeVault() {
        session.close()
    }

    /** The test key 0x4c08...2318, which has no funds and is used by no other fixture. */
    private val key: ByteArray = Numeric.hexStringToByteArray(KEY_HEX)

    private fun newSingleKey(): String = store.createSingleKey(name = "Imported", privateKey = key, nowMs = 5L)

    @Test
    fun aSingleKeyWalletHasOneAccountAndNoAccountKey() {
        val id = newSingleKey()

        val meta = assertNotNull(store.meta(id))
        assertEquals(WalletKind.SINGLE_KEY, meta.kind)
        assertEquals("Imported", meta.name)
        assertEquals(5L, meta.createdAtMs)
        assertEquals(false, meta.deviceGenerated)
        assertEquals(1, meta.accountCount)
        assertNull(meta.masterFingerprintHex)
        assertNull(meta.accountXpub)
        assertEquals(listOf(id), store.list().map { it.id })
    }

    @Test
    fun theStoredKeyComesBackUnchanged() {
        val id = newSingleKey()

        val secret = assertIs<WalletSecret.SingleKey>(store.secret(id))

        assertContentEquals(key, secret.privateKey)
        secret.privateKey.fill(0)
    }

    @Test
    fun itsOnlyAccountIsTheAddressOfItsKey() {
        val id = newSingleKey()

        assertEquals(listOf(KEY_ADDRESS), store.addresses(id, 1))
    }

    @Test
    fun aSingleKeyWalletRefusesEveryGenerationRequest() {
        val id = newSingleKey()

        assertFailsWith<IllegalArgumentException> { store.addAccount(id) }
        assertFailsWith<IllegalArgumentException> { store.addAccounts(id, count = 1, cap = cap) }
        assertFailsWith<IllegalArgumentException> { store.addresses(id, count = 2) }
        assertEquals(1, store.meta(id)?.accountCount)
    }

    @Test
    fun anInvalidKeyIsNeverSaved() {
        assertFailsWith<IllegalArgumentException> {
            store.createSingleKey(name = "Zero", privateKey = ByteArray(32), nowMs = 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            store.createSingleKey(name = "Short", privateKey = key.copyOf(31), nowMs = 1L)
        }
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun aMnemonicWalletWrittenBeforeKindsStillLoads() {
        val wallet = HdWallet(Mnemonic.toSeed(words))
        val id = writeLegacyMnemonicWallet(accountCount = 2)

        val meta = assertNotNull(store.meta(id))
        assertEquals(WalletKind.MNEMONIC, meta.kind)
        assertEquals("Old", meta.name)
        assertEquals(2, meta.accountCount)
        assertEquals(wallet.masterFingerprintHex, meta.masterFingerprintHex)
        assertContentEquals(wallet.accountXpub().publicKey, requireNotNull(meta.accountXpub).publicKey)
        assertEquals(listOf(id), store.list().map { it.id })
        assertIs<WalletSecret.Mnemonic>(store.secret(id))
        assertEquals(listOf(wallet.address(0), wallet.address(1)), store.addresses(id, 2))
    }

    @Test
    fun aMnemonicWalletWrittenBeforeKindsKeepsItsKindWhenItIsSaved() {
        val id = writeLegacyMnemonicWallet(accountCount = 1)

        val updated = store.addAccount(id)

        assertEquals(WalletKind.MNEMONIC, updated.kind)
        assertEquals(2, updated.accountCount)
        val reloaded = assertNotNull(store.meta(id))
        assertEquals(WalletKind.MNEMONIC, reloaded.kind)
        assertEquals(2, reloaded.accountCount)
        assertContentEquals(
            HdWallet(Mnemonic.toSeed(words)).accountXpub().publicKey,
            requireNotNull(reloaded.accountXpub).publicKey,
        )
    }

    /** Writes a wallet in the version 1 records, as a vault from before kinds holds it. */
    private fun writeLegacyMnemonicWallet(accountCount: Int): String {
        val wallet = HdWallet(Mnemonic.toSeed(words))
        val id = session.createWallet()
        session.putRecord(
            id,
            WalletRecords.META,
            LegacyWalletRecords.meta(
                name = "Old",
                createdAtMs = 1L,
                deviceGenerated = false,
                accountCount = accountCount,
                masterFingerprintHex = wallet.masterFingerprintHex,
                xpub = wallet.accountXpub(),
            ),
        )
        session.putRecord(id, WalletRecords.SECRET, LegacyWalletRecords.secret(words, passphrase = null))
        return id
    }

    private companion object {
        const val KEY_HEX = "4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318"
        const val KEY_ADDRESS = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23"
    }
}
