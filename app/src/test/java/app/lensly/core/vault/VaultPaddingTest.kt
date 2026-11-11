package app.lensly.core.vault

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A wallet's record files live in the record pool, not in its own folder, so a wallet folder and an unused slot hold the same
 * single key file. How many records a wallet has cannot be read from its folder's file count, names or sizes.
 */
class VaultPaddingTest {

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
    fun aWalletWithRecordsLooksLikeAnUnusedSlot() {
        create()
        val real = unlock()
        val wallet = real.createWallet()
        real.putRecord(wallet, "secret", ByteArray(200) { it.toByte() })
        real.putRecord(wallet, "name", ByteArray(40) { it.toByte() })
        real.putRecord(wallet, "meta", ByteArray(700) { it.toByte() })

        val walletShape = fileSet(layout.walletDir(wallet))
        val unusedShape = fileSet(anUnusedFolder(real))
        // The three records are in the pool, not in the wallet's folder.
        assertEquals(3, VaultRecordPool.files(layout, wallet).size)

        // Same file count, same names, same sizes.
        assertEquals(1, walletShape.size)
        assertEquals(setOf(WALLET_KEY_FILE_NAME to walletShape.single().second), walletShape)
        assertEquals(walletShape, unusedShape)
        real.close()
    }

    @Test
    fun deletingAWalletRemovesItsRecordFiles() {
        create()
        val real = unlock()
        val wallet = real.createWallet()
        real.putRecord(wallet, "secret", byteArrayOf(1, 2, 3))
        real.putRecord(wallet, "meta", byteArrayOf(4, 5))
        assertEquals(2, VaultRecordPool.files(layout, wallet).size)

        real.deleteWallet(wallet)

        assertTrue(VaultRecordPool.files(layout, wallet).isEmpty())
        assertNull(real.getRecord(wallet, "secret"))
        assertEquals(setOf(WALLET_KEY_FILE_NAME), names(layout.walletDir(wallet)))
        real.close()
    }

    @Test
    fun aWipeRemovesEveryRecordFile() {
        create()
        val real = unlock()
        val wallet = real.createWallet()
        real.putRecord(wallet, "secret", byteArrayOf(1, 2, 3))
        real.close()

        vault.wipe()

        assertFalse(layout.root.exists())
        assertFalse(VaultRecordPool.dir(layout).exists())
    }

    @Test
    fun anotherSessionCannotReadTheRealRecords() {
        create()
        val real = unlock()
        val wallet = real.createWallet()
        real.putRecord(wallet, "secret", byteArrayOf(1, 2, 3))
        val otherKey = ByteArray(32) { (it * 7 + 3).toByte() }
        val decoy = VaultSession(layout, otherKey.copyOf(), isDecoy = true)
        try {
            // The record file is found, and this session's wallet key does not open it.
            assertFailsWith<VaultException> { decoy.getRecord(wallet, "secret") }
            assertFailsWith<VaultException> { decoy.putRecord(wallet, "secret", byteArrayOf(0)) }

            decoy.deleteWallet(wallet)

            assertEquals(listOf(wallet), real.listWallets())
            assertContentEquals(byteArrayOf(1, 2, 3), real.getRecord(wallet, "secret"))
        } finally {
            decoy.close()
            otherKey.fill(0)
        }
        real.close()
    }

    @Test
    fun removingTheDuressPasswordRemovesTheDecoysRecordFiles() {
        create()
        val real = unlock()
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(real, DURESS.toCharArray()) { it.createWallet() } })
        val decoy = unlock(DURESS)
        val decoyWallet = decoy.listWallets().single()
        decoy.putRecord(decoyWallet, "secret", byteArrayOf(1))
        decoy.close()
        assertEquals(1, VaultRecordPool.files(layout, decoyWallet).size)

        runBlocking { vault.removeDuress(real) }

        assertTrue(VaultRecordPool.files(layout, decoyWallet).isEmpty())
        real.close()
    }

    @Test
    fun aRecordInAWalletFolderMovesToThePoolAndStillReads() {
        create()
        val real = unlock()
        val wallet = real.createWallet()
        real.putRecord(wallet, "settings", byteArrayOf(7, 8, 9))
        // The layout before the pool: the record file sat in the wallet's folder, named by the record.
        val poolFile = VaultRecordPool.files(layout, wallet).single()
        val inFolder = File(layout.walletDir(wallet), "settings.bin")
        Files.move(poolFile.toPath(), inFolder.toPath())
        real.close()

        val reopened = unlock()

        assertContentEquals(byteArrayOf(7, 8, 9), reopened.getRecord(wallet, "settings"))
        assertEquals(setOf(WALLET_KEY_FILE_NAME), names(layout.walletDir(wallet)))
        reopened.close()
    }

    private fun create() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlock(password: String = APP): VaultSession {
        val result = runBlocking { vault.unlock(password.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return result.session
    }

    private fun anUnusedFolder(session: VaultSession): File {
        val used = session.listWallets().toSet()
        val folders = layout.wallets.listFiles().orEmpty().filter { it.isDirectory }
        return folders.first { it.name !in used }
    }

    /** The file names and sizes of [dir], without reading the contents. */
    private fun fileSet(dir: File): Set<Pair<String, Long>> =
        dir.listFiles().orEmpty().filter { it.isFile }.map { it.name to it.length() }.toSet()

    private fun names(dir: File): Set<String> = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
