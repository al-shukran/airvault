package app.lensly.app.export

/**
 * Google's Brotli encoder: the C library built from brotli v1.2.1 in src/main/cpp, called through a JNI wrapper. Only Lensly links it, so
 * Lumio never carries the encoder. It compresses the body of a version-2 pairing at quality 11 and window 22, the settings [PairingCodec]
 * documents.
 */
object BrotliEncoder : BrotliCompressor {
    private const val QUALITY = 11
    private const val LGWIN = 22

    init {
        System.loadLibrary("brotlienc_jni")
    }

    override fun compress(bytes: ByteArray): ByteArray = encode(bytes, QUALITY, LGWIN)

    /**
     * [input] as one Brotli stream, at [quality] (0 to 11) and window [lgwin] (10 to 24).
     * @throws IllegalStateException when the encoder refuses the settings or the input.
     */
    external fun encode(input: ByteArray, quality: Int, lgwin: Int): ByteArray
}
