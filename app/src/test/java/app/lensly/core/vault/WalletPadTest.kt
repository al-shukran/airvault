package app.lensly.core.vault

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The wallet folders keep a fixed count. An unused slot holds files with the same names and sizes as a wallet's, filled
 * with random bytes, so the folder count never says how many wallets exist or which vault they belong to.
 */
class WalletPadTest {

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
    fun aFreshVaultHoldsSixteenWalletFolders() {
        create()

        assertEquals(WALLET_SLOT_STEP, folderCount())
    }

    @Test
    fun theFolderCountStaysUntilEverySlotIsUsedThenGrows() {
        create()
        val real = unlock()
        repeat(WALLET_SLOT_STEP - 1) { real.createWallet() }
        assertEquals(WALLET_SLOT_STEP, folderCount())

        real.createWallet()
        assertEquals(WALLET_SLOT_STEP, folderCount())

        real.createWallet()
        assertEquals(WALLET_SLOT_STEP * 2, folderCount())
        real.close()
    }

    @Test
    fun aDecoyWalletUsesTheSameSlotsAsARealOne() {
        create()
        val real = unlock()
        repeat(WALLET_SLOT_STEP / 2) { real.createWallet() }

        val result = runBlocking {
            vault.setDuress(real, DURESS.toCharArray()) { decoy ->
                repeat(WALLET_SLOT_STEP / 2) { decoy.createWallet() }
            }
        }
        assertEquals(DuressResult.Enabled, result)
        assertEquals(WALLET_SLOT_STEP, folderCount())

        real.createWallet()
        assertEquals(WALLET_SLOT_STEP * 2, folderCount())
        real.close()
    }

    @Test
    fun anUnusedSlotLooksLikeAWalletFolder() {
        create()
        val real = unlock()
        val wallet = real.createWallet()

        assertEquals(fileSet(layout.walletDir(wallet)), fileSet(anUnusedFolder(real)))
        real.close()
    }

    @Test
    fun anOlderStoreIsPaddedOnItsFirstUnlockAndItsWalletsStillOpen() {
        create()
        val real = unlock()
        val first = real.createWallet()
        val second = real.createWallet()
        real.putRecord(first, "secret", byteArrayOf(1, 2, 3))
        real.close()

        // An older store had one folder per wallet and no unused-slot list.
        val owned = setOf(first, second)
        layout.wallets.listFiles()?.filter { it.isDirectory && it.name !in owned }?.forEach { it.deleteRecursively() }
        File(layout.root, WALLET_PAD_FILE_NAME).delete()
        assertEquals(2, folderCount())

        val reopened = unlock()
        assertEquals(WALLET_SLOT_STEP, folderCount())
        assertEquals(owned, reopened.listWallets().toSet())
        assertContentEquals(byteArrayOf(1, 2, 3), reopened.getRecord(first, "secret"))
        reopened.close()
    }

    @Test
    fun deletingAWalletTurnsItsFolderIntoAnUnusedSlot() {
        create()
        val real = unlock()
        val wallet = real.createWallet()
        val shapeBefore = fileSet(layout.walletDir(wallet))

        real.deleteWallet(wallet)

        assertEquals(WALLET_SLOT_STEP, folderCount())
        assertFalse(real.listWallets().contains(wallet))
        // The slot keeps the wallet's file names and sizes, filled with random bytes.
        assertEquals(shapeBefore, fileSet(layout.walletDir(wallet)))
        real.close()
    }

    private fun create() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlock(password: String = APP): VaultSession {
        val result = runBlocking { vault.unlock(password.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return result.session
    }

    private fun folderCount(): Int = layout.wallets.listFiles()?.count { it.isDirectory } ?: 0

    private fun anUnusedFolder(session: VaultSession): File {
        val used = session.listWallets().toSet()
        val folders = layout.wallets.listFiles().orEmpty().filter { it.isDirectory }
        return folders.first { it.name !in used }
    }

    /** The file names and sizes of [dir], without reading the contents. */
    private fun fileSet(dir: File): Set<Pair<String, Long>> =
        dir.listFiles().orEmpty().filter { it.isFile }.map { it.name to it.length() }.toSet()

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
