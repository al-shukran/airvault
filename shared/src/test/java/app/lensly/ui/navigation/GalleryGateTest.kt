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
    fun theDuressPasswordOpensNoGalleryAndNeitherPromotesNorErasesTheRealVault() {
        val photo = saveRealPhoto()
        val keyFile = layout.vaultKey.readBytes()

        assertNull(galleryAfterDelay(DURESS))

        // The gallery only checks a password, so the Duress Password is a wrong one there: the real vault is as it was.
        assertContentEquals(keyFile, layout.vaultKey.readBytes())
        assertTrue(!layout.promotionMarker.exists())
        val gallery = assertNotNull(galleryAfterDelay(APP))
        assertEquals(listOf(photo), gallery.list().map { it.file })
        val session = (runBlocking { clock.advance(FailureDelay.LEARNING_CAP_MS); vault.unlock(APP.toCharArray()) } as UnlockResult.Unlocked).session
        assertEquals(listOf(realWallet), session.listWallets())
        session.close()
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

    private fun createVaultWithDuress() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        val session = (runBlocking { vault.unlock(APP.toCharArray()) } as UnlockResult.Unlocked).session
        realWallet = session.createWallet()
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { decoy -> decoy.createWallet() } })
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
