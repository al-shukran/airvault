package app.lensly.core.vault

import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The wallet list a session keeps between reads, and the write count that tells a caller when a read is out of date. */
class VaultSessionWalletListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keys = FlakyKeystore(MemoryKeystore())
    private lateinit var session: VaultSession

    @Before
    fun open() {
        session = VaultSession(VaultLayout(tmp.root), keys, ByteArray(32) { (it * 7 + 3).toByte() })
    }

    @After
    fun close() {
        session.close()
    }

    @Test
    fun aNewWalletIsListedAtOnceAndADeletedOneIsNot() {
        val first = session.createWallet()
        assertEquals(listOf(first), session.listWallets())

        val second = session.createWallet()
        assertEquals(listOf(first, second).sorted(), session.listWallets())

        session.deleteWallet(first)
        assertEquals(listOf(second), session.listWallets())
    }

    @Test
    fun aWriteMovesTheWriteCountAndAReadDoesNot() {
        val start = session.writeCount
        session.listWallets()
        assertEquals(start, session.writeCount, "Listing the wallets is a read.")

        val wallet = session.createWallet()
        val created = session.writeCount
        assertTrue(created > start, "Creating a wallet is a write.")

        session.putRecord(wallet, WalletFolder.META, byteArrayOf(1, 2, 3))
        val stored = session.writeCount
        assertTrue(stored > created, "Storing a record is a write.")

        session.getRecord(wallet, WalletFolder.META)
        assertEquals(stored, session.writeCount, "Reading a record is a read.")
    }

    @Test
    fun aScanThatCannotCheckAFolderIsNotKept() {
        val wallet = session.createWallet()

        keys.failing = true
        assertTrue(session.listWallets().isEmpty(), "A folder the Keystore cannot check is left out.")

        keys.failing = false
        assertEquals(listOf(wallet), session.listWallets(), "The next listing checks the folder again.")
    }
}

/** The in-memory Keystore, which can be made to fail every wallet folder key lookup. */
private class FlakyKeystore(private val inner: MemoryKeystore) : KeystoreBackend by inner {
    var failing = false

    override fun folderKey(keyId: String): SecretKey? {
        if (failing) throw VaultException("The Keystore failed to answer.")
        return inner.folderKey(keyId)
    }
}
