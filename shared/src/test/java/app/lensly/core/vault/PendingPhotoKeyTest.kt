package app.lensly.core.vault

import app.lensly.ui.camera.PhotoStore
import app.lensly.ui.camera.holds
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Photos taken before the first setup. A shot makes the photo key pair, the gallery opens those photos with no vault, the vault takes the
 * pair over when it is created, and a wipe removes the pair and its photos.
 */
class PendingPhotoKeyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val keys = MemoryKeystore()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"), File(tmp.root, "photos"))
        vault = Vault(layout, keys, clock, KDF)
    }

    @Test
    fun aShotBeforeTheFirstSetupOpensInTheGalleryWithNoVault() {
        val photo = Random(1).nextBytes(300)

        shoot(photo)

        assertOnlyPhotoOpens(photo, pendingPrivateKey())
        assertFalse(vault.exists())
    }

    @Test
    fun thePendingPrivateKeyIsNeverPlainOnDisk() {
        shoot(Random(2).nextBytes(300))
        val privateKey = pendingPrivateKey()

        assertFalse(filesUnder(layout.photos).any { it.readBytes().holds(privateKey) })
    }

    @Test
    fun theVaultAdoptsThePairMadeBeforeTheFirstSetup() {
        val photo = Random(3).nextBytes(300)
        shoot(photo)
        val pending = pendingPrivateKey()

        createVault()

        val session = unlockedSession()
        try {
            val photoKey = session.getVaultRecord(VaultRecordNames.PHOTO_KEY)
            assertContentEquals(pending, photoKey)
            assertOnlyPhotoOpens(photo, photoKey)
        } finally {
            session.close()
        }
    }

    @Test
    fun aShotBeforeSetupIsSavedToBothVaultsBecauseTheDecoyHasAPlaceholderKey() {
        PendingPhotoKey.ensure(layout, keys)

        assertTrue(PhotoStore.forCover(layout).savePhoto(Random(6).nextBytes(300), nowMs = 1_000L))

        assertEquals(1, layout.photoDir(0).listFiles().orEmpty().size)
        assertEquals(1, layout.photoDir(1).listFiles().orEmpty().size)
    }

    @Test
    fun noPendingPrivateKeyIsLeftAfterTheVaultIsCreated() {
        shoot(Random(4).nextBytes(300))
        val pending = pendingPrivateKey()

        createVault()

        assertFalse(layout.pendingKeyFile.exists())
        assertNull(keys.pendingPhotoKey())
        assertFalse(filesUnder(layout.photos).any { it.readBytes().holds(pending) })
    }

    @Test
    fun aShotAfterTheVaultExistsMakesNoPendingPair() {
        createVault()

        PendingPhotoKey.ensure(layout, keys)

        assertFalse(layout.pendingKeyFile.exists())
        assertNull(keys.pendingPhotoKey())
    }

    @Test
    fun aWipeBeforeTheFirstSetupRemovesThePendingKeyAndThePhotos() {
        shoot(Random(5).nextBytes(300))

        vault.wipe()

        assertFalse(layout.photos.exists())
        assertFalse(layout.pendingKeyFile.exists())
        assertNull(keys.pendingPhotoKey())
        assertTrue(vault.isBeforeSetup())
    }

    @Test
    fun aWipeAfterTheVaultAdoptedThePairRemovesItsPhotos() {
        shoot(Random(6).nextBytes(300))
        createVault()

        vault.wipe()

        assertFalse(layout.photos.exists())
        assertFalse(vault.exists())
    }

    @Test
    fun aPendingPairThatCannotBeVerifiedIsErasedWithItsPhotos() {
        shoot(Random(7).nextBytes(300))
        layout.pendingKeyFile.writeBytes(ByteArray(80) { 7 })

        assertNull(PendingPhotoKey.read(layout, keys))

        assertTrue(PhotoStore.of(layout, side = 0).list().isEmpty())
        assertFalse(layout.photoKeyFile(0).exists())
    }

    @Test
    fun aPendingPairWithoutItsKeystoreKeyIsErasedWithItsPhotos() {
        shoot(Random(8).nextBytes(300))
        keys.deletePendingPhotoKey()

        assertNull(PendingPhotoKey.read(layout, keys))

        assertTrue(PhotoStore.of(layout, side = 0).list().isEmpty())
    }

    @Test
    fun aShotAfterAnErasedPairMakesANewPair() {
        shoot(Random(9).nextBytes(300))
        keys.deletePendingPhotoKey()
        val photo = Random(10).nextBytes(300)

        shoot(photo)

        assertOnlyPhotoOpens(photo, pendingPrivateKey())
    }

    /** What a shot does before the first setup: make the pair if there is none, then seal the photo to it. */
    private fun shoot(photo: ByteArray) {
        PendingPhotoKey.ensure(layout, keys)
        assertTrue(PhotoStore.forCover(layout).savePhoto(photo.copyOf(), nowMs = 1_000L))
    }

    /** Opens the photo folder with [privateKey] and checks that [photo] is the only photo in it. */
    private fun assertOnlyPhotoOpens(photo: ByteArray, privateKey: ByteArray?) {
        val store = PhotoStore.of(layout, side = 0, privateKey = privateKey)
        val opened = store.list().map { requireNotNull(store.photoBytes(it.file)) }
        assertEquals(1, opened.size)
        assertContentEquals(photo, opened.single())
    }

    private fun pendingPrivateKey(): ByteArray = requireNotNull(PendingPhotoKey.read(layout, keys)) { "No pending photo key." }

    private fun createVault() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    private fun unlockedSession(): VaultSession {
        val result = runBlocking { vault.unlock(APP.toCharArray()) }
        return (result as? UnlockResult.Unlocked)?.session ?: error("The vault did not unlock: $result")
    }

    private fun filesUnder(dir: File): List<File> = dir.walkTopDown().filter { it.isFile }.toList()

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
