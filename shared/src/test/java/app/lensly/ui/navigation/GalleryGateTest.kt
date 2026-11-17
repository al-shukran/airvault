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
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        vault = Vault(VaultLayout(File(tmp.root, "vault")), MemoryKeystore(), clock, KDF)
        createVaultWithDuress()
    }

    @Test
    fun aPhotoSavedForTheRealVaultIsNotListedInTheDecoy() {
        val photo = saveRealPhoto()

        assertEquals(listOf(photo), PhotoStore.of(tmp.root, decoy = false).list())
        assertTrue(PhotoStore.of(tmp.root, decoy = true).list().isEmpty())
    }

    @Test
    fun theAppPasswordOpensTheRealVaultsPhotos() {
        val photo = saveRealPhoto()

        assertEquals(listOf(photo), galleryAfterDelay(APP)?.list())
    }

    @Test
    fun theDuressPasswordOpensTheDecoysFolderWithoutARealPhoto() {
        saveRealPhoto()

        val decoy = assertNotNull(galleryAfterDelay(DURESS))

        assertEquals(PhotoStore.of(tmp.root, decoy = true).directory, decoy.directory)
        assertTrue(decoy.list().isEmpty())
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
        runBlocking { galleryStoreFor(vault, tmp.root, password.toCharArray()) }

    /** Moves the clock past any failure delay, then asks the gate. */
    private fun galleryAfterDelay(password: String): PhotoStore? {
        clock.advance(FailureDelay.LEARNING_CAP_MS)
        return gallery(password)
    }

    private fun saveRealPhoto(): File =
        PhotoStore.of(tmp.root, decoy = false).newFile(nowMs = 1_000L).apply { writeBytes(ByteArray(1)) }

    private fun createVaultWithDuress() {
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
        val session = (runBlocking { vault.unlock(APP.toCharArray()) } as UnlockResult.Unlocked).session
        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { decoy -> decoy.createWallet() } })
        session.close()
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val WRONG = "Zx8#Vb2!Nm5\$Qw7%Ld4"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
    }
}
