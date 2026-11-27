package app.lensly.ui.camera

import app.lensly.core.vault.MediaEnvelope
import app.lensly.core.vault.VaultLayout
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
        layout.photoKeyFile(decoy = false).writeBytes(MediaEnvelope.publicKeyOf(realKey))
        // Without a Duress Password the decoy's key file holds random bytes that no private key matches.
        layout.photoKeyFile(decoy = true).writeBytes(
            if (withDecoy) MediaEnvelope.publicKeyOf(decoyKey) else MediaEnvelope.publicKeyOf(MediaEnvelope.newPrivateKey()),
        )
        return layout
    }

    @Test
    fun aCoverPhotoShowsInTheGalleryOfBothPasswords() {
        val layout = layout(withDecoy = true)

        assertTrue(PhotoStore.forCover(layout).savePhoto(photo.copyOf(), nowMs = 1_000L))

        for ((decoy, key) in listOf(false to realKey, true to decoyKey)) {
            val gallery = PhotoStore.of(layout, decoy, privateKey = key.copyOf())
            val saved = gallery.list().single()
            assertContentEquals(photo, gallery.photoBytes(saved.file))
        }
    }

    @Test
    fun theOtherVaultsKeyDoesNotOpenAFile() {
        val layout = layout(withDecoy = true)
        PhotoStore.forCover(layout).savePhoto(photo.copyOf(), nowMs = 1_000L)

        assertTrue(PhotoStore.of(layout, decoy = true, privateKey = realKey.copyOf()).list().isEmpty())
        assertTrue(PhotoStore.of(layout, decoy = false, privateKey = decoyKey.copyOf()).list().isEmpty())
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

    private fun sizes(layout: VaultLayout): Pair<List<Long>, List<Long>> =
        listOf(false, true).map { decoy -> layout.photoDir(decoy).listFiles().orEmpty().map { it.length() }.sorted() }
            .let { it[0] to it[1] }
}
