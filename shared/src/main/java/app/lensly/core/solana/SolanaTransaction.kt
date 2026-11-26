package app.lensly.core.solana

import app.lensly.core.wallet.Ed25519Signer
import java.io.ByteArrayOutputStream

/**
 * A signed Solana transaction as `sendTransaction` takes it: the compact-u16 count of signatures, then the signatures, then the message. Lumio
 * assembles it from a signature that Lensly made and checked, and it holds no key.
 */
object SolanaTransaction {
    /** The signed transaction bytes of [message] with [signatures], in the order of the message's signers. Each signature is 64 bytes. */
    fun signed(signatures: List<ByteArray>, message: ByteArray): ByteArray {
        require(signatures.isNotEmpty()) { "A transaction has at least one signature." }
        require(signatures.all { it.size == Ed25519Signer.SIGNATURE_BYTES }) { "A signature is 64 bytes." }
        val out = ByteArrayOutputStream()
        out.write(compactU16(signatures.size))
        signatures.forEach { out.write(it) }
        out.write(message)
        return out.toByteArray()
    }

    /** The first signature of [signed], a transaction that [signed] built, with fewer than 128 signatures: the one the network knows it by. */
    fun firstSignature(signed: ByteArray): ByteArray {
        require(signed.size > Ed25519Signer.SIGNATURE_BYTES && signed[0] in 1..0x7F) { "This is not a signed transaction." }
        return signed.copyOfRange(1, 1 + Ed25519Signer.SIGNATURE_BYTES)
    }
}

/**
 * The compact-u16 ("shortvec") encoding of [value]: 7 bits per byte, least significant group first, and the high bit set on every byte but the
 * last. This is the shortest form, the only one [SolanaMessageReader] accepts.
 */
internal fun compactU16(value: Int): ByteArray {
    require(value in 0..0xFFFF) { "A compact-u16 value is 0 to 65535." }
    val out = ByteArrayOutputStream()
    var rest = value
    while (true) {
        val group = rest and 0x7F
        rest = rest ushr 7
        if (rest == 0) {
            out.write(group)
            return out.toByteArray()
        }
        out.write(group or 0x80)
    }
}
