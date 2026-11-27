package app.lensly.ui.camera

import android.media.MediaDataSource
import app.lensly.core.vault.MediaEnvelope
import app.lensly.core.vault.VaultLayout
import java.io.File

/**
 * The real vault's photo store under [folder], with [privateKey] held. The store seals with the public half, which is written to the
 * layout's key file, and opens with the private half, as the gallery does once its gate has read the key.
 */
internal fun keyedStore(folder: File, privateKey: ByteArray = MediaEnvelope.newPrivateKey()): PhotoStore {
    val layout = VaultLayout(folder, File(folder, "photos"))
    layout.photos.mkdirs()
    layout.photoKeyFile(0).writeBytes(MediaEnvelope.publicKeyOf(privateKey))
    return PhotoStore.of(layout, side = 0, privateKey = privateKey)
}

/** True when [part] occurs in this array. */
internal fun ByteArray.holds(part: ByteArray): Boolean =
    (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

/**
 * A media source with no bytes. Robolectric keys every MediaDataSource by one name, so any stub stands for a video's source in a test.
 */
internal fun stubMediaSource(): MediaDataSource = object : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = -1

    override fun getSize(): Long = 0L

    override fun close() = Unit
}
