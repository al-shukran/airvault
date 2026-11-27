package app.lensly.ui.camera

import app.lensly.core.vault.MediaEnvelope
import app.lensly.core.vault.VaultLayout
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The cover has no session, so what it shoots must show in the gallery of either password and tell the two vaults apart by nothing. */
class CoverPhotoBothVaultsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val realKey = MediaEnvelope.newPrivateKey()
    private val decoyKey = MediaEnvelope.newPrivateKey()
    private val photo = Random(5).nextBytes(400)

    private fun layout(withDecoy: Boolean): VaultLayout {
        val root = folder.newFolder()
        val layout = VaultLayout(root, File(root, "photos"))
        layout.makePhotoFolders()
        layout.photoKeyFile(0).writeBytes(MediaEnvelope.publicKeyOf(realKey))
        // Without a Duress Password the decoy's key file holds random bytes that no private key matches.
        layout.photoKeyFile(1).writeBytes(
            if (withDecoy) MediaEnvelope.publicKeyOf(decoyKey) else MediaEnvelope.publicKeyOf(MediaEnvelope.newPrivateKey()),
        )
        return layout
    }

    @Test
    fun aCoverPhotoShowsInTheGalleryOfBothPasswords() {
        val layout = layout(withDecoy = true)

        assertTrue(PhotoStore.forCover(layout).savePhoto(photo.copyOf(), nowMs = 1_000L))

        for ((side, key) in listOf(0 to realKey, 1 to decoyKey)) {
            val gallery = PhotoStore.of(layout, side, privateKey = key.copyOf())
            val saved = gallery.list().single()
            assertContentEquals(photo, gallery.photoBytes(saved.file))
        }
    }

    @Test
    fun theOtherVaultsKeyDoesNotOpenAFile() {
        val layout = layout(withDecoy = true)
        PhotoStore.forCover(layout).savePhoto(photo.copyOf(), nowMs = 1_000L)

        assertTrue(PhotoStore.of(layout, side = 1, privateKey = realKey.copyOf()).list().isEmpty())
        assertTrue(PhotoStore.of(layout, side = 0, privateKey = decoyKey.copyOf()).list().isEmpty())
    }

    @Test
    fun aVaultWithoutADuressPasswordWritesTheSameFilesAsOneWithIt() {
        val with = layout(withDecoy = true)
        val without = layout(withDecoy = false)

        PhotoStore.forCover(with).savePhoto(photo.copyOf(), nowMs = 1_000L)
        PhotoStore.forCover(without).savePhoto(photo.copyOf(), nowMs = 1_000L)

        assertEquals(sizes(with), sizes(without))
        assertEquals(1, sizes(without).second.size)
    }

    @Test
    fun theTwoCopiesOfACoverPhotoHaveTheSameName() {
        val layout = layout(withDecoy = true)

        PhotoStore.forCover(layout).savePhoto(photo.copyOf(), nowMs = 1_000L)

        assertEquals(names(layout, side = 0), names(layout, side = 1))
        assertEquals(1, names(layout, side = 0).size)
    }

    @Test
    fun deletingACoverPhotoInTheGalleryOfSide0AlsoShredsTheTwinCopy() {
        val layout = layout(withDecoy = true)
        PhotoStore.forCover(layout).savePhoto(photo.copyOf(), nowMs = 1_000L)
        val real = PhotoStore.of(layout, side = 0, privateKey = realKey.copyOf())

        assertTrue(real.delete(real.list().single().file))

        assertTrue(names(layout, side = 0).isEmpty())
        assertTrue(names(layout, side = 1).isEmpty())
    }

    @Test
    fun deletingACoverPhotoInTheGalleryOfSide1AlsoShredsTheTwinCopy() {
        val layout = layout(withDecoy = true)
        PhotoStore.forCover(layout).savePhoto(photo.copyOf(), nowMs = 1_000L)
        val decoy = PhotoStore.of(layout, side = 1, privateKey = decoyKey.copyOf())

        assertTrue(decoy.delete(decoy.list().single().file))

        assertTrue(names(layout, side = 0).isEmpty())
        assertTrue(names(layout, side = 1).isEmpty())
    }

    @Test
    fun aCoverPhotoWhoseDecoyCopyCannotBeMadeIsNotSavedAtAll() {
        val layout = layout(withDecoy = true)
        layout.photoKeyFile(1).delete()

        assertFalse(PhotoStore.forCover(layout).savePhoto(photo.copyOf(), nowMs = 1_000L))

        assertTrue(layout.photoDir(0).listFiles().orEmpty().isEmpty())
        assertTrue(layout.photoDir(1).listFiles().orEmpty().isEmpty())
    }

    private fun names(layout: VaultLayout, side: Int): List<String> =
        layout.photoDir(side).listFiles().orEmpty().map { it.name }.sorted()

    private fun sizes(layout: VaultLayout): Pair<List<Long>, List<Long>> =
        listOf(0, 1).map { side -> layout.photoDir(side).listFiles().orEmpty().map { it.length() }.sorted() }
            .let { it[0] to it[1] }
}
