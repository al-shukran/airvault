package app.lensly.ui.navigation

import app.lensly.core.vault.CreateResult
import app.lensly.core.vault.DuressResult
import app.lensly.core.vault.FailureDelay
import app.lensly.core.vault.FakeClock
import app.lensly.core.vault.KdfParams
import app.lensly.core.vault.MemoryKeystore
import app.lensly.core.vault.UnlockResult
import app.lensly.core.vault.Vault
import app.lensly.core.vault.VaultLayout
import app.lensly.ui.camera.PhotoStore
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The gallery's password gate and the per-vault photo folders. The real vault and its Decoy Vault are built on the JVM with an
 * in-memory keystore, as in VaultDuressTest.
 */
class GalleryGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"), File(tmp.root, "photos"))
        vault = Vault(layout, MemoryKeystore(), clock, KDF)
        createVaultWithDuress()
    }

    @Test
    fun theAppPasswordOpensTheRealVaultsPhotos() {
        val photo = saveRealPhoto()

        val gallery = assertNotNull(galleryAfterDelay(APP))

        assertEquals(listOf(photo), gallery.list().map { it.file })
        assertContentEquals(PHOTO, gallery.photoBytes(photo))
    }

    @Test
    fun theDuressPasswordPromotesTheDecoyErasesTheRealVaultAndOpensThePromotedGallery() {
        val shot = saveCoverPhoto()
        val oldKeyFile = layout.vaultKey.readBytes()

        // Not the wrong-password answer: a gallery opens. It lists the photo the cover took after the Duress Password was set.
        val first = assertNotNull(galleryAfterDelay(DURESS))
        assertEquals(listOf(shot.name), first.list().map { it.file.name })
        assertContentEquals(PHOTO, first.photoBytes(first.list().single().file))
        assertEquals(0, vault.failureCountForTesting())
        // The real vault is gone: a new key file is in place, the cleanup finished before the gallery returned, and the App Password opens nothing.
        assertTrue(!oldKeyFile.contentEquals(layout.vaultKey.readBytes()))
        assertTrue(!layout.promotionMarker.exists())
        assertNull(galleryAfterDelay(APP))
        // The gallery is the promoted vault's: the Duress Password opens it, and the cover's next photo is listed there too.
        val next = saveCoverPhoto()
        val promoted = assertNotNull(galleryAfterDelay(DURESS))
        assertEquals(setOf(shot.name, next.name), promoted.list().map { it.file.name }.toSet())
        val session = (runBlocking { clock.advance(FailureDelay.LEARNING_CAP_MS); vault.unlock(DURESS.toCharArray()) } as UnlockResult.Unlocked).session
        assertEquals(listOf(decoyWallet), session.listWallets())
        session.close()
    }

    @Test
    fun theAppPasswordStillOpensACoverPhotoBeforeAnyPromotionAndTheDecoysKeyFileKeepsItsShape() {
        val keyFile = layout.photoKeyFile(1)
        val sizeAtSetup = keyFile.length()
        val shot = saveCoverPhoto()

        val gallery = assertNotNull(galleryAfterDelay(APP))

        assertEquals(listOf(shot.name), gallery.list().map { it.file.name })
        assertEquals(sizeAtSetup, layout.photoKeyFile(0).length())
        assertEquals(layout.photoKeyFile(0).length(), keyFile.length())
        assertEquals(layout.photoKeyFile(0).name.length, keyFile.name.length)
    }

    @Test
    fun aPasswordThatOpensNeitherVaultGivesNoGallery() {
        saveRealPhoto()

        assertNull(galleryAfterDelay(WRONG))
    }

    @Test
    fun aGalleryGuessCountsAsAFailedUnlock() {
        assertNull(gallery(WRONG))

        assertEquals(1, vault.failureCountForTesting())
    }

    private fun gallery(password: String): PhotoStore? =
        runBlocking { galleryStoreFor(vault, layout, password.toCharArray()) }

    /** Moves the clock past any failure delay, then asks the gate. */
    private fun galleryAfterDelay(password: String): PhotoStore? {
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        return gallery(password)
    }

    /** Saves a photo the way the cover does, with no password: the vault's public key alone seals it. */
    private fun saveRealPhoto(): File {
        assertTrue(PhotoStore.of(layout, side = 0).savePhoto(PHOTO.copyOf(), nowMs = 1_000L))
        return PhotoStore.of(layout, side = 0).directory.listFiles().orEmpty().single()
    }

    private lateinit var realWallet: String
    private lateinit var decoyWallet: String

    /** Saves a photo the way the cover does: one copy sealed to each vault's public key. Returns the side 0 copy. */
    private fun saveCoverPhoto(): File {
        val before = PhotoStore.of(layout, side = 0).directory.listFiles().orEmpty().toSet()
        assertTrue(PhotoStore.forCover(layout).savePhoto(PHOTO.copyOf(), nowMs = 1_000L))
        return PhotoStore.of(layout, side = 0).directory.listFiles().orEmpty().toSet().minus(before).single()
    }

    private fun createVaultWithDuress() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        val session = (runBlocking { vault.unlock(APP.toCharArray()) } as UnlockResult.Unlocked).session
        realWallet = session.createWallet()
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { decoy -> decoy.createWallet().also { decoyWallet = it } } })
        session.close()
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
        val PHOTO = ByteArray(300) { (it * 7).toByte() }
    }
}
