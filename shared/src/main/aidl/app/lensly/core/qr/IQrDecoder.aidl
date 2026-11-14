package app.lensly.core.qr;

import android.os.SharedMemory;

/**
 * The one call the app makes to the QR Decode Sandbox. [frame] holds the luminance plane: height rows of [rowStride] bytes,
 * and the first width bytes of each row are pixels. The reply holds the texts decoded from the frame, at most
 * QrDecodeLimits.MAX_TEXTS, each within QrDecodeLimits.MAX_TEXT_CHARS. A frame outside QrDecodeLimits is refused with an
 * IllegalArgumentException.
 */
interface IQrDecoder {
    String[] decode(in SharedMemory frame, int width, int height, int rowStride);
}
