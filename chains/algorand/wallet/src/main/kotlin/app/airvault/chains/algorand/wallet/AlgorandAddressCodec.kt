package app.airvault.chains.algorand.wallet

import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.AddressError
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.model.ChainId
import org.bouncycastle.crypto.digests.SHA512tDigest

/**
 * Algorand addresses (spec 4): the 32-byte Ed25519 public key, then the last 4 bytes of its SHA-512/256 digest, written as 58
 * upper-case base32 characters (RFC 4648 alphabet, no padding). It is the format algosdk's `encodeAddress` writes, and the same
 * format as Lensly's `AlgoAddress`, so both apps show one address for one key.
 */
object AlgorandAddressCodec : AddressCodec {
    private const val PUBLIC_KEY_BYTES = 32
    private const val CHECKSUM_BYTES = 4
    private const val ADDRESS_LENGTH = 58
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    override fun parse(chain: ChainId, text: String): AddressResult {
        if (algorandManifest.networks.none { it.chainId == chain }) return AddressResult.Invalid(AddressError.WRONG_NETWORK)
        if (text.length != ADDRESS_LENGTH) return AddressResult.Invalid(AddressError.LENGTH)
        if (text.any { it !in ALPHABET }) return AddressResult.Invalid(AddressError.CHARSET)
        val raw = unbase32(text)
        val publicKey = raw.copyOfRange(0, PUBLIC_KEY_BYTES)
        // The text must be the canonical encoding of its bytes (its padding bits are zero), and its checksum must match the key.
        val canonical = base32(raw) == text
        val checksumMatches = raw.copyOfRange(PUBLIC_KEY_BYTES, raw.size).contentEquals(checksum(publicKey))
        return if (canonical && checksumMatches) {
            AddressResult.Valid(text, AddressKind.DEFAULT)
        } else {
            AddressResult.Invalid(AddressError.CHECKSUM)
        }
    }

    override fun fromPublicKey(chain: ChainId, publicKey: ByteArray, kind: AddressKind): String {
        require(algorandManifest.networks.any { it.chainId == chain }) { "$chain is not an Algorand network" }
        require(publicKey.size == PUBLIC_KEY_BYTES) { "an Algorand public key is $PUBLIC_KEY_BYTES bytes" }
        require(kind == AddressKind.DEFAULT) { "Algorand has one address kind" }
        return base32(publicKey + checksum(publicKey))
    }

    /** The last [CHECKSUM_BYTES] bytes of the SHA-512/256 digest of [publicKey]. */
    private fun checksum(publicKey: ByteArray): ByteArray {
        val digest = SHA512tDigest(256)
        digest.update(publicKey, 0, publicKey.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out.copyOfRange(out.size - CHECKSUM_BYTES, out.size)
    }

    /** RFC 4648 base32 of [bytes], without padding. */
    private fun base32(bytes: ByteArray): String {
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

    /** The bytes that [text] encodes. The caller has checked every character is in the alphabet. Trailing bits are dropped. */
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
