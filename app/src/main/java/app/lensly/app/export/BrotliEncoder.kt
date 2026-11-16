package app.lensly.app.export

/**
 * Google's Brotli encoder: the C library built from brotli v1.2.1 in src/main/cpp, called through a JNI wrapper. Only Lensly links it, so
 * Lumio never carries the encoder. The settings of the pairing's Brotli stream are chosen by [PairingCodec].
 */
object BrotliEncoder {
    init {
        System.loadLibrary("brotlienc_jni")
    }

    /**
     * [input] as one Brotli stream, at [quality] (0 to 11) and window [lgwin] (10 to 24).
     * @throws IllegalStateException when the encoder refuses the settings or the input.
     */
    external fun encode(input: ByteArray, quality: Int, lgwin: Int): ByteArray
}
