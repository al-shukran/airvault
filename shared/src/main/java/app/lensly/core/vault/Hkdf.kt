package app.lensly.core.vault

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The output size of HMAC-SHA256, which HKDF runs on. */
private const val HASH_BYTES = 32

/**
 * HKDF with HMAC-SHA256 (RFC 5869): extracts the key material under [salt], then expands it to [length] bytes under [info]. A null [salt]
 * is a string of zeros, as the RFC allows. The caller zeroes the result.
 */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
    require(length in 1..255 * HASH_BYTES) { "An HKDF output is 1 to ${255 * HASH_BYTES} bytes." }
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt ?: ByteArray(HASH_BYTES), "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    try {
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            val n = minOf(block.size, length - written)
            block.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        block.fill(0)
        return out
    } finally {
        prk.fill(0)
    }
}
