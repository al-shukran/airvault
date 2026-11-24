package app.lumio.ui.receive

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import app.lensly.ui.qr.QrMatrix
import java.io.File

/** The side of the shared code's image, in pixels: sharp enough to scan from a screen after a messenger has scaled it. */
private const val SHARE_PIXELS = 768

/** The white around the shared code, in modules: a code sent as an image needs more quiet zone than the card gives it on screen. */
private const val SHARE_MARGIN = 4

/** The folder of the app's cache that holds the shared code. [FileProvider] shares from it, and no other folder. */
private const val SHARE_DIR = "receive"

/** The file name of the shared code. It is written again on each share, so the cache keeps one code. */
private const val SHARE_FILE = "receive-qr.png"

private const val PNG_QUALITY = 100

/**
 * Writes the code of [address] as a PNG in the app's cache, and returns the content URI that a share grants to the receiving app. It reads and writes
 * a file, so the caller runs it off the main thread.
 */
internal fun writeReceiveCode(context: Context, address: String): Uri {
    val pixels = QrMatrix.pixelsForCentredLogo(address, margin = SHARE_MARGIN, targetPx = SHARE_PIXELS)
    val bitmap = Bitmap.createBitmap(pixels.argb, pixels.size, pixels.size, Bitmap.Config.ARGB_8888)
    val file = File(receiveShareDir(context).apply { mkdirs() }, SHARE_FILE)
    try {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    } finally {
        bitmap.recycle()
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.receive", file)
}

/** The folder of the shared code. It names an address of the owner's, so every erase of Lumio deletes it (see [app.lumio.eraseOutsideVault]). */
internal fun receiveShareDir(context: Context): File = File(context.cacheDir, SHARE_DIR)

/** Opens the system share sheet with the code at [uri] as an image and [text] as its words. */
internal fun shareReceive(context: Context, uri: Uri, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TEXT, text)
    send.clipData = ClipData.newUri(context.contentResolver, "QR code", uri)
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
