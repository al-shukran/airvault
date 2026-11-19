package app.airvault.chains.algorand

import org.bouncycastle.crypto.digests.SHA512tDigest

/**
 * Algorand account addresses. An address is the 32-byte Ed25519 public key followed by the last 4 bytes of the key's
 * SHA-512/256 digest, written as 58 upper-case base32 characters (RFC 4648 alphabet, no padding).
 *
 * This is the format algosdk's `encodeAddress` writes (algosdk 3.9.0, MIT). The Mnemonic A addresses in the tests
 * were produced by that function when the vectors were recorded.
 */
object AlgoAddress {
    /** An Algorand public key is 32 bytes. */
    const val PUBLIC_KEY_BYTES = 32

    /** An address is 58 characters. */
    const val ADDRESS_LENGTH = 58

    private const val CHECKSUM_BYTES = 4
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** The address of [publicKey], which must be [PUBLIC_KEY_BYTES] long. */
    fun encode(publicKey: ByteArray): String {
        require(publicKey.size == PUBLIC_KEY_BYTES) { "public key must be $PUBLIC_KEY_BYTES bytes" }
        return base32(publicKey + checksum(publicKey))
    }

    /**
     * The public key that [address] names. Throws [IllegalArgumentException] unless the text is exactly
     * [ADDRESS_LENGTH] upper-case base32 characters, its padding bits are zero, and its checksum matches the key.
     */
    fun decode(address: String): ByteArray {
        require(address.length == ADDRESS_LENGTH) { "address must be $ADDRESS_LENGTH characters" }
        require(address.all { it in ALPHABET }) { "address must be upper-case base32" }
        val raw = unbase32(address)
        require(base32(raw) == address) { "address has non-zero padding bits" }
        val publicKey = raw.copyOfRange(0, PUBLIC_KEY_BYTES)
        require(raw.copyOfRange(PUBLIC_KEY_BYTES, raw.size).contentEquals(checksum(publicKey))) {
            "address checksum does not match"
        }
        return publicKey
    }

    /** The last [CHECKSUM_BYTES] bytes of the SHA-512/256 digest of [publicKey]. */
    private fun checksum(publicKey: ByteArray): ByteArray {
        val digest = SHA512tDigest(256)
        digest.update(publicKey, 0, publicKey.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out.copyOfRange(out.size - CHECKSUM_BYTES, out.size)
    }

    /** RFC 4648 base32 of [bytes], without padding. The transaction IDs of the signed transactions use it too. */
    fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = ((buffer shl 8) or (b.toInt() and 0xFF)) and 0xFFFF
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    /** The bytes that [text] encodes. Trailing bits that do not fill a whole byte are dropped. */
    private fun unbase32(text: String): ByteArray {
        val out = ByteArray(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var at = 0
        for (c in text) {
            buffer = ((buffer shl 5) or ALPHABET.indexOf(c)) and 0xFFFF
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[at++] = (buffer shr bits).toByte()
            }
        }
        return out
    }
}
