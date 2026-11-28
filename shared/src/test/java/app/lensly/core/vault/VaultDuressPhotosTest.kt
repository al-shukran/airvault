package app.lensly.core.vault

import app.lensly.ui.camera.PhotoStore
import java.io.File
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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
        awaitSealer()

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

    @Test
    fun anUnlockSweepsTempFilesFromBothPhotoFoldersSoTheirNamesStayEqual() {
        val photo = shoot()
        File(layout.photoDir(1), "${photo.name}.123.tmp").writeBytes(PHOTO)
        File(layout.photoDir(0), "other.456.tmp").writeBytes(PHOTO)

        unlocked(APP).close()
        awaitSealer()

        assertEquals(setOf(photo.name), layout.photoDir(0).list().orEmpty().toSet())
        assertEquals(setOf(photo.name), layout.photoDir(1).list().orEmpty().toSet())
    }

    @Test
    fun aPhotoLeftWithoutItsTwinByAKilledCoverSaveIsShreddedAtTheNextUnlock() {
        val photo = shoot()
        File(layout.photoDir(0), "half-saved").writeBytes(PHOTO)
        File(layout.photoDir(1), "orphan").writeBytes(PHOTO)

        unlocked(APP).close()
        awaitSealer()

        assertEquals(setOf(photo.name), layout.photoDir(0).list().orEmpty().toSet())
        assertEquals(setOf(photo.name), layout.photoDir(1).list().orEmpty().toSet())
    }

    @Test
    fun aDeleteKilledBetweenItsTwoShredsLeavesNoPhotoReadableAfterTheNextUnlock() {
        val photo = shoot()
        val gallery = PhotoStore.of(layout, side = 0)
        PhotoStore.betweenDeleteStepsForTesting = { throw IllegalStateException("killed") }
        try {
            runCatching { gallery.delete(photo) }
        } finally {
            PhotoStore.betweenDeleteStepsForTesting = null
        }
        assertTrue(photo.exists(), "the photo goes after its twin, so the gallery still lists it")
        assertTrue(!File(layout.photoDir(1), photo.name).exists(), "the twin goes first")

        unlocked(APP).close()
        awaitSealer()

        assertEquals(emptySet(), layout.photoDir(0).list().orEmpty().toSet())
        assertEquals(emptySet(), layout.photoDir(1).list().orEmpty().toSet())
    }

    @Test
    fun anUnlockDoesNotWaitForASaveThatIsStillRunning() {
        val photo = shoot()
        File(layout.photoDir(1), "${photo.name}.123.tmp").writeBytes(PHOTO)
        val release = CountDownLatch(1)
        PhotoStore.sealer.execute { release.await(30, TimeUnit.SECONDS) }
        try {
            val unlock = FutureTask { unlocked(APP).close() }
            Thread(unlock).start()
            unlock.get(10, TimeUnit.SECONDS)
            assertTrue(File(layout.photoDir(1), "${photo.name}.123.tmp").exists(), "the repair is queued behind the save")
        } finally {
            release.countDown()
        }
        awaitSealer()
        assertEquals(setOf(photo.name), layout.photoDir(1).list().orEmpty().toSet())
    }

    private fun awaitSealer() = PhotoStore.whileNoSaveRuns {}

    @Test
    fun setupReturnsBeforeTheCopiesAreMadeAndEveryPhotoOpensAfterThePromotion() {
        val session = unlocked(APP)
        session.createWallet()
        val photos = (1..3).map { shoot(nowMs = it * 1_000L) }
        val before = photos.associate { it.name to File(layout.photoDir(1), it.name).readBytes() }
        // The sealer is held at the first copy, so no copy can be made until the test lets it go.
        val atFirstCopy = CountDownLatch(1)
        val release = CountDownLatch(1)
        resealHookForTesting = { atFirstCopy.countDown(); release.await(30, TimeUnit.SECONDS) }
        try {
            val setup = FutureTask { runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } } }
            Thread(setup).start()
            assertEquals(DuressResult.Enabled, setup.get(30, TimeUnit.SECONDS), "setup returns while the copies are still queued")
            assertTrue(atFirstCopy.await(30, TimeUnit.SECONDS))
            assertEquals(before.keys, layout.photoDir(1).list().orEmpty().toSet(), "the twins are there, with their names")
            photos.forEach { assertContentEquals(before[it.name], File(layout.photoDir(1), it.name).readBytes(), "no copy is made yet") }
        } finally {
            release.countDown()
            resealHookForTesting = null
        }
        session.close()
        awaitSealer()

        val promoted = unlocked(DURESS)
        runBlocking { vault.awaitCleanup() }
        val gallery = PhotoStore.of(layout, side = promoted.side, privateKey = promoted.getVaultRecord(VaultRecordNames.PHOTO_KEY)!!)
        assertEquals(photos.map { it.name }.toSet(), gallery.list().map { it.file.name }.toSet())
        gallery.list().forEach { assertContentEquals(PHOTO, gallery.photoBytes(it.file)) }
        gallery.close()
        promoted.close()
    }

    @Test
    fun aPhotoDeletedBeforeItsCopyIsMadeLeavesNoCopyOnTheDecoySide() {
        assertDeletedDuringSetupLeavesNoTwin(twinFirst = false)
    }

    @Test
    fun aPhotoWithoutATwinThatIsDeletedBeforeItsRandomTwinIsMadeLeavesNoCopyOnTheDecoySide() {
        assertDeletedDuringSetupLeavesNoTwin(twinFirst = true)
    }

    private fun assertDeletedDuringSetupLeavesNoTwin(twinFirst: Boolean) {
        val session = unlocked(APP)
        session.createWallet()
        val photo = shoot()
        // A photo with no side-1 twin is given a random one; otherwise its twin is sealed again.
        if (twinFirst) assertTrue(File(layout.photoDir(1), photo.name).delete())
        // The gallery's delete runs on the sealer, between two tasks; here it is the files going, as it leaves them.
        resealHookForTesting = { source ->
            File(layout.photoDir(1), source.name).delete()
            assertTrue(source.delete())
        }
        try {
            assertEquals(DuressResult.Enabled, runBlocking { vault.setDuress(session, DURESS.toCharArray()) { it.createWallet() } })
            awaitSealer()
        } finally {
            resealHookForTesting = null
        }
        session.close()

        assertEquals(emptySet(), layout.photoDir(0).list().orEmpty().toSet())
        assertEquals(emptySet(), layout.photoDir(1).list().orEmpty().toSet())
    }

    private fun shoot(nowMs: Long = 1_000L): File {
        val known = layout.photoDir(0).list().orEmpty().toSet()
        assertTrue(PhotoStore.forCover(layout).savePhoto(PHOTO.copyOf(), nowMs = nowMs))
        return layout.photoDir(0).listFiles().orEmpty().single { it.name !in known }
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
