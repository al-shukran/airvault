package app.lensly.core.qr;

import android.os.SharedMemory;

/**
 * The QR decode service's one call. [frame] holds the luminance plane: height rows of rowStride bytes, the first width bytes of
 * each row are pixels. The reply holds the decoded texts, at most a few, each within the limits of QrDecodeLimits.
 * Work in progress: the service and the client are not written yet.
 */
interface IQrDecoder {
    String[] decode(in SharedMemory frame, int width, int height, int rowStride);
}
