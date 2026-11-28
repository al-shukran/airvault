package app.lensly.core.vault

import app.lensly.ui.camera.PhotoStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Setting a Duress Password, removing it and promoting its vault leave the files looking as if no Decoy Vault had ever been there. On the
 * JVM with an in-memory keystore.
 */
class VaultDuressSetupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = layoutAt("vault")
        vault = Vault(layout, keys, clock, KDF)
        assertEquals(CreateResult.Created, runBlocking { vault.create(P1.toCharArray()) })
    }

    @Test
    fun noSlotIsFreeInBothTheRealAndTheDecoyList() {
        val real = unlocked(P1)
        real.createWallet()
        val decoyKey = setDuress(real)

        val realFree = WalletPad(layout, keys, real.copyOfVaultKey(), real.side).freeSlots()
        val decoyFree = WalletPad(layout, keys, decoyKey, 1 - real.side).freeSlots()

        assertEquals(emptySet(), realFree intersect decoyFree)
        // The real vault keeps its spare slots, so its next wallet does not grow the store.
        assertTrue(realFree.isNotEmpty(), "the real vault keeps the slots the decoy did not use")
        real.createWallet()
        assertEquals(WALLET_SLOT_STEP, layout.wallets.listFiles().orEmpty().size)
        real.close()
    }

    @Test
    fun aRealWalletMadeAfterTheSetupIsRetiredByADuressUnlock() {
        val real = unlocked(P1)
        val before = real.createWallet()
        setDuress(real)
        val after = real.createWallet()
        val realKey = real.copyOfVaultKey()
        real.close()

        val promoted = unlocked(P2)
        awaitCleanup()
        promoted.close()

        for (id in listOf(before, after)) {
            assertNull(WalletFolder.walletKeyOf(keys, layout.walletDir(id), realKey, id), "the old Vault Key still opens $id")
            assertTrue(WalletFolder.isUnused(layout.walletDir(id)), "$id is an unused slot")
        }
        realKey.fill(0)
    }

    @Test
    fun releaseRetiresAWalletFolderThatIsAlreadyListedAsFree() {
        val real = unlocked(P1)
        val id = real.createWallet()
        val key = real.copyOfVaultKey()
        val pad = WalletPad(layout, keys, key, real.side)
        // The folder is a wallet's, and the list names it free all the same.
        pad.padTo(WALLET_SLOT_STEP)
        pad.write(pad.freeSlots() + id)
        assertNotNull(WalletFolder.walletKeyOf(keys, layout.walletDir(id), key, id))

        pad.release(listOf(id))

        assertNull(WalletFolder.walletKeyOf(keys, layout.walletDir(id), key, id))
        real.close()
        key.fill(0)
    }

    @Test
    fun aDecoyThatAKillLeftWithoutRecordFilesGetsThemInThePromotion() {
        val fresh = recordSizesOfAVaultWithoutDecoy()
        val real = unlocked(P1)
        val decoyKey = setDuress(real)
        real.close()
        // The state after a kill between the commit and the claim: no file is named by the decoy's key.
        VaultRecordNames.KNOWN.forEach { name ->
            val file = File(layout.vaultRecords, VaultRecordNames.fileName(decoyKey, name))
            assertTrue(file.renameTo(File(layout.vaultRecords, VaultRecordNames.randomFileName(java.security.SecureRandom()))))
        }

        val promoted = unlocked(P2)
        awaitCleanup()
        VaultRecordNames.KNOWN.forEach { promoted.putVaultRecord(it, byteArrayOf(1)) }
        promoted.close()

        assertEquals(fresh, sizes(layout.vaultRecords))
        decoyKey.fill(0)
    }

    @Test
    fun settingADuressPasswordKeepsTheTwoPhotoFoldersAlike() {
        val real = unlocked(P1)
        real.createWallet()
        savePhotos("a")
        val sizesBefore = photoSizes()

        setDuress(real)

        assertEquals(sizesBefore, photoSizes())
        assertFoldersAlike()
        savePhotos("b")
        assertFoldersAlike()
        real.close()
    }

    @Test
    fun removingTheDuressPasswordKeepsTheTwoPhotoFoldersAlike() {
        val real = unlocked(P1)
        real.createWallet()
        setDuress(real)
        savePhotos("a")
        val sizesBefore = photoSizes()

        runBlocking { vault.removeDuress(real) }
        PhotoStore.whileNoSaveRuns {}

        assertEquals(sizesBefore, photoSizes())
        assertFoldersAlike()
        real.close()
    }

    @Test
    fun aDecoySidePhotoWithNoTwinGoesAndARealPhotoWithNoTwinGetsOne() {
        val real = unlocked(P1)
        real.createWallet()
        File(layout.photoDir(0).apply { mkdirs() }, "real-only").apply { writeBytes(ByteArray(300) { 1 }) }.setLastModified(0L)
        File(layout.photoDir(1).apply { mkdirs() }, "decoy-only").apply { writeBytes(ByteArray(50) { 2 }) }.setLastModified(0L)

        setDuress(real)

        assertEquals(listOf("real-only"), names(0))
        assertFoldersAlike()
        assertEquals(300L, File(layout.photoDir(1), "real-only").length())
        assertFalse(File(layout.photoDir(1), "real-only").readBytes().all { it == 1.toByte() || it == 0.toByte() })
        real.close()
    }

    @Test
    fun aDecoyThatWasNotSetUpGivesItsSlotsBackWhetherItIsEmptyOrFails() {
        for (populate in listOf<(VaultSession) -> Unit>({}, { throw IllegalStateException("populate failed") })) {
            val own = layoutAt("try${tmp.root.list().orEmpty().size}")
            val ownVault = Vault(own, MemoryKeystore(), FakeClock(), KDF)
            runBlocking { ownVault.create(P1.toCharArray()) }
            val real = (runBlocking { ownVault.unlock(P1.toCharArray()) } as UnlockResult.Unlocked).session
            real.createWallet()

            val result = runBlocking { ownVault.setDuress(real, P2.toCharArray(), populate) }
            real.createWallet()

            assertTrue(result is DuressResult.DecoyEmpty || result is DuressResult.Failed, "got $result")
            assertEquals(WALLET_SLOT_STEP, own.wallets.listFiles().orEmpty().size, "the store grew after a setup that did not complete")
            real.close()
        }
    }

    private fun savePhotos(tag: String) {
        listOf("$tag-a" to 100, "$tag-b" to 200).forEach { (name, size) ->
            // The cover clears the modification time of every photo it saves.
            listOf(0, 1).forEach { side ->
                File(layout.photoDir(side).apply { mkdirs() }, name).apply { writeBytes(ByteArray(size)) }.setLastModified(0L)
            }
        }
    }

    /** Both folders list the same names, each with the same size and modification time, as the cover leaves them. */
    private fun assertFoldersAlike() {
        fun listing(side: Int) = layout.photoDir(side).listFiles().orEmpty().map { Triple(it.name, it.length(), it.lastModified()) }.sortedBy { it.first }
        assertEquals(listing(0), listing(1))
        assertTrue(listing(0).all { it.third == 0L }, "a photo file shows a modification time: ${listing(0)}")
    }

    private fun names(side: Int) = layout.photoDir(side).listFiles().orEmpty().map { it.name }.sorted()

    private fun photoSizes() = listOf(0, 1).map { side -> layout.photoDir(side).listFiles().orEmpty().map { it.length() }.sorted() }

    private fun sizes(dir: File) = dir.listFiles().orEmpty().filter { it.isFile }.map { it.length() }.sorted()

    private fun recordSizesOfAVaultWithoutDecoy(): List<Long> {
        val other = layoutAt("fresh")
        val otherVault = Vault(other, MemoryKeystore(), FakeClock(), KDF)
        runBlocking { otherVault.create(P1.toCharArray()) }
        return sizes(other.vaultRecords)
    }

    private fun unlocked(password: String): VaultSession {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        val result = runBlocking { vault.unlock(password.toCharArray()) }
        assertTrue(result is UnlockResult.Unlocked, "Expected Unlocked but was $result")
        return (result as UnlockResult.Unlocked).session
    }

    /** Sets [P2] with one decoy wallet and returns the decoy's Vault Key. The caller zeroes it. */
    private fun setDuress(session: VaultSession): ByteArray {
        var key = ByteArray(0)
        val result = runBlocking {
            vault.setDuress(session, P2.toCharArray()) { decoy ->
                decoy.createWallet()
                key = decoy.copyOfVaultKey()
            }
        }
        assertEquals(DuressResult.Enabled, result)
        // The photo copies are made after the setup returns.
        PhotoStore.whileNoSaveRuns {}
        return key
    }

    /** Waits for the promotion cleanup. It runs before the promoted session is closed, as a closed session skips it. */
    private fun awaitCleanup() = runBlocking { vault.awaitCleanup() }

    private fun layoutAt(name: String) = VaultLayout(File(tmp.root, name), File(tmp.root, "$name-photos"))

    private companion object {
        const val P1 = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val P2 = "Hj3\$kP9wQz!Lm6Tn2Ysd"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
