package app.lensly.core.vault

import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What a vault made by an earlier build gets on its first unlock: the Review Record pool, and its free-slot list moved into the pad. */
class UpgradeMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"))
        vault = Vault(layout, keys, clock, KDF)
    }

    @Test
    fun anUpgradedVaultGetsItsReviewPoolOnTheFirstUnlock() {
        create()
        layout.reviewPool.deleteRecursively()

        unlock().close()

        assertTrue(layout.reviewPool.isDirectory, "The first unlock did not create the Review Record pool")
        assertEquals(ReviewPoolFormat.BATCH, layout.reviewPool.listFiles().orEmpty().count { it.isFile })
    }

    @Test
    fun anEarlierFreeSlotListMovesIntoThePadOnTheFirstUnlock() {
        create()
        val session = unlock()
        val folders = layout.wallets.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
        val realKey = session.copyOfVaultKey()
        session.close()
        val legacy = legacyPad(folders)
        File(layout.root, WALLET_PAD_FILE_NAME).writeBytes(legacy)

        unlock().close()

        assertNotEquals(legacy.size, File(layout.root, WALLET_PAD_FILE_NAME).length().toInt(), "The legacy pad was not rewritten")
        assertEquals(folders, WalletPad(layout, keys, realKey, REAL_SECTION).freeSlotsForTesting())
    }

    /** The file an earlier build wrote: a version byte, a count, the folder ids, sealed under the hardware key. */
    private fun legacyPad(ids: Set<String>): ByteArray {
        val sorted = ids.sorted()
        val plain = ByteArray(5 + sorted.size * 36)
        plain[0] = 1
        ByteBuffer.wrap(plain, 1, 4).putInt(sorted.size)
        sorted.forEachIndexed { index, id -> id.toByteArray(Charsets.US_ASCII).copyInto(plain, 5 + index * 36) }
        return Aead.sealWithKeystore(keys.hardwareKey(), "lensly.vault.wallet-pad".toByteArray(Charsets.US_ASCII), plain)
    }

    private fun create() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlock(): VaultSession {
        val result = runBlocking { vault.unlock(APP.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return result.session
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
