package app.lensly.core.vault

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Every wallet folder holds the same files with the same sizes, whether it holds a real wallet, a decoy wallet, or nothing. */
class WalletFolderShapeTest {

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
    fun aRealWalletADecoyWalletAndPaddingHaveTheSameFilesAndSizes() {
        create()
        val real = unlock()
        val wallet = real.createWallet()
        real.putRecord(wallet, WalletFolder.META, ByteArray(200) { 1 })
        real.putRecord(wallet, WalletFolder.SECRET, ByteArray(40) { 2 })
        real.putRecord(wallet, WalletFolder.BASE_NAME, byteArrayOf(3))
        real.putRecord(wallet, WalletFolder.PROFILE, byteArrayOf(4))
        real.putRecord(wallet, WalletFolder.accountName(3), byteArrayOf(5))
        val result = runBlocking {
            vault.setDuress(real, DURESS.toCharArray()) { decoy ->
                decoy.putRecord(decoy.createWallet(), WalletFolder.SECRET, ByteArray(1500) { 6 })
            }
        }
        assertEquals(DuressResult.Enabled, result)
        real.close()

        val shapes = shapes()
        assertEquals(WALLET_SLOT_STEP, shapes.size)
        assertEquals(1 + WalletFolder.NAMES.size, shapes.first().size)
        shapes.forEach { assertEquals(shapes.first(), it) }
    }

    @Test
    fun aVaultFromAnEarlierBuildIsPaddedOnItsFirstUnlock() {
        create()
        val real = unlock()
        val wallet = real.createWallet()
        val key = real.copyOfVaultKey()
        real.close()
        // An earlier build sealed each record at its own size, and its unused folders held only the key file.
        LegacyWalletFolder.writeRecord(layout, keys, key, wallet, WalletFolder.META, ByteArray(37) { 1 })
        LegacyWalletFolder.writeRecord(layout, keys, key, wallet, WalletFolder.SECRET, ByteArray(90) { 2 })
        key.fill(0)
        layout.wallets.listFiles().orEmpty().filter { it.isDirectory && it.name != wallet }.forEach { dir ->
            dir.listFiles().orEmpty().filter { it.name != WALLET_KEY_FILE_NAME }.forEach { it.delete() }
        }
        assertTrue(shapes().any { it.size == 1 }, "The legacy layout was not set up")

        val reopened = unlock()

        assertContentEquals(ByteArray(37) { 1 }, reopened.getRecord(wallet, WalletFolder.META))
        assertContentEquals(ByteArray(90) { 2 }, reopened.getRecord(wallet, WalletFolder.SECRET))
        reopened.close()
        val shapes = shapes()
        assertEquals(WALLET_SLOT_STEP, shapes.size)
        shapes.forEach { assertEquals(shapes.first(), it) }
    }

    private fun create() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlock(): VaultSession {
        val result = runBlocking { vault.unlock(APP.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return result.session
    }

    /** The sorted (file name, size) list of each wallet folder. */
    private fun shapes(): List<List<Pair<String, Long>>> =
        layout.wallets.listFiles().orEmpty().filter { it.isDirectory }.map { dir ->
            dir.listFiles().orEmpty().filter { it.isFile }.map { it.name to it.length() }.sortedBy { it.first }
        }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
