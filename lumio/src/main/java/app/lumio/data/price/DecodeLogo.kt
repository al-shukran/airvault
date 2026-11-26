package app.lumio.data.price

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** The largest side, in pixels, of a logo that is decoded. A logo is drawn at 48 dp, so a bigger image is not one. */
private const val MAX_LOGO_SIDE = 1024

/** The side a decoded logo is brought down to, in pixels, at most twice over. */
private const val TARGET_LOGO_SIDE = 256

private fun boundsOf(bytes: ByteArray): BitmapFactory.Options? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return bounds.takeIf { it.outWidth in 1..MAX_LOGO_SIDE && it.outHeight in 1..MAX_LOGO_SIDE }
}

/**
 * The logo in [bytes], down-sampled, or null when it is no image or declares more than [MAX_LOGO_SIDE] pixels a side (a small file can declare
 * hundreds of megabytes of pixels). The bounds are read first, so no oversized bitmap is ever allocated. Throws [OutOfMemoryError] when a good logo
 * does not fit in memory, which says nothing about the logo.
 */
internal fun decodeLogoOrThrow(bytes: ByteArray): Bitmap? {
    val bounds = boundsOf(bytes) ?: return null
    val options = BitmapFactory.Options().apply { inSampleSize = Integer.highestOneBit(maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / TARGET_LOGO_SIDE)) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

/** [decodeLogoOrThrow], or null when the logo does not fit in memory. */
internal fun decodeLogo(bytes: ByteArray): Bitmap? = try {
    decodeLogoOrThrow(bytes)
} catch (e: OutOfMemoryError) {
    null
}
