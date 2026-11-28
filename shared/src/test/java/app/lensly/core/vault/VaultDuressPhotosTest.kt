package app.lensly.core.vault

import app.lensly.ui.camera.PhotoStore
import java.io.File
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The photos the cover takes before and after a Duress Password is set stay readable by the decoy once it is promoted, and the decoy's
 * key file is shaped like the real side's at every step. On the JVM with an in-memory keystore.
 */
class VaultDuressPhotosTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var layout: VaultLayout
    private lateinit var vault: Vault

    @Before
    fun setUp() {
        layout = VaultLayout(File(tmp.root, "vault"), File(tmp.root, "photos"))
        vault = Vault(layout, MemoryKeystore(), clock, KDF)
        assertEquals(CreateResult.Created, runBlocking { vault.create(APP.toCharArray()) })
    }

    @Test
    fun aPlaceholderKeyIsAValidPublicKeyThatNoRandomByteStringIsAlwaysTakenFor() {
        val p = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
        repeat(500) {
            val key = placeholderPhotoKey()
            assertEquals(MediaEnvelope.KEY_BYTES, key.size)
            assertEquals(0, key[31].toInt() and 0x80, "the top bit of an X25519 public key is clear")
            assertTrue(BigInteger(1, key.reversedArray()) < p)
        }
    }

    @Test
    fun theDecoysKeyFileIsPublicKeyShapedAfterSetupRemovalAndSetupAgain() {
        val session = unlocked(APP)
        session.createWallet()
        assertPublicKeyShaped(layout.photoKeyFile(1))
        // A key file that is random bytes is caught with one in two, so the cycle is repeated.
        repeat(8) {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            assertPublicKeyShaped(layout.photoKeyFile(1))
            runBlocking { vault.removeDuress(session) }
            assertPublicKeyShaped(layout.photoKeyFile(1))
        }
        session.close()
    }

    @Test
    fun aPhotoTakenBeforeTheDuressPasswordIsSetOpensAfterThePromotionWithTheSameBytes() {
        val session = unlocked(APP)
        session.createWallet()
        val photo = shoot()
        val sealedTwin = File(layout.photoDir(1), photo.name)
        val sizeBefore = sealedTwin.length()

        assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
        session.close()

        assertEquals(sizeBefore, sealedTwin.length(), "the twin keeps its size")
        assertEquals(0L, sealedTwin.lastModified(), "the twin keeps modification time zero")
        assertEquals(setOf(photo.name), layout.photoDir(1).list().orEmpty().toSet(), "no temp file is left")
        val promoted = unlocked(DURESS)
        runBlocking { vault.awaitCleanup() }
        val key = promoted.getVaultRecord(VaultRecordNames.PHOTO_KEY)!!
        val gallery = PhotoStore.of(layout, side = promoted.side, privateKey = key)
        assertEquals(listOf(photo.name), gallery.list().map { it.file.name })
        assertContentEquals(PHOTO, gallery.photoBytes(gallery.list().single().file))
        gallery.close()
        promoted.close()
    }

    private fun shoot(): File {
        assertTrue(PhotoStore.forCover(layout).savePhoto(PHOTO.copyOf(), nowMs = 1_000L))
        return layout.photoDir(0).listFiles().orEmpty().single()
    }

    private fun unlocked(password: String): VaultSession {
        clock.advance(FailureDelay.MAX_CAP_MS + 1)
        return (runBlocking { vault.unlock(password.toCharArray()) } as UnlockResult.Unlocked).session
    }

    private fun assertPublicKeyShaped(file: File) {
        val bytes = file.readBytes()
        assertEquals(MediaEnvelope.KEY_BYTES, bytes.size)
        assertEquals(0, bytes[31].toInt() and 0x80, "${file.name} holds a byte string that no public key is")
    }

    private companion object {
        const val APP = "q7Vn!2Lx#Rb9Tm4Wc8Zp"
        const val DURESS = "Gh5%Rt8!Yu2#Pk6@Ws3Fn"
        val KDF = KdfParams(memoryKiB = 8 * 1024, iterations = 1)
        val PHOTO = ByteArray(300) { (it * 7).toByte() }
    }
}
