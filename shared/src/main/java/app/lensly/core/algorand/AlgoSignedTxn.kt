package app.lensly.core.algorand

import app.airvault.chains.algorand.AlgoAddress
import app.airvault.chains.algorand.bytesToSign
import app.airvault.chains.algorand.signer.AlgoTxnDecoder
import org.bouncycastle.crypto.digests.SHA512tDigest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * What an Algorand account signs, and the signed transaction that algod takes.
 *
 * [verify] checks a signature over [bytesToSign] before anything is sent, so a signature for another transaction or another
 * account is refused. [assemble] writes the signed transaction: the msgpack map `{sig, txn}` with the bytes that were
 * signed, as go-algorand's `SignedTxn` encodes a single-signature transaction. [txid] is the transaction ID that the
 * network gives the transaction, which the send checks against the server's answer.
 */
object AlgoSignedTxn {
    /** An Ed25519 signature is 64 bytes. */
    const val SIGNATURE_BYTES = 64

    /**
     * True when [signature] is the Ed25519 signature of [publicKey] over [bytesToSign] of [txn]. A key or a signature of the
     * wrong length, and any other malformed input, is false.
     */
    fun verify(publicKey: ByteArray, txn: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != AlgoAddress.PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        val message = bytesToSign(txn)
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * The signed transaction for [txn] and [signature]: a fixmap of two entries, `sig` (the 64 bytes) and `txn` (the transaction
     * map as it was signed). Throws [AlgoTxnException] when [txn] is not a transaction the decoder accepts.
     */
    fun assemble(txn: ByteArray, signature: ByteArray): ByteArray {
        require(signature.size == SIGNATURE_BYTES) { "signature must be $SIGNATURE_BYTES bytes" }
        AlgoTxnDecoder.decode(txn)
        return byteArrayOf(0x82.toByte()) + msgpackStr("sig") + msgpackBin(signature) + msgpackStr("txn") + txn
    }

    /** The transaction ID of [txn]: the base32 of the SHA-512/256 hash of [bytesToSign], without padding (52 characters). */
    fun txid(txn: ByteArray): String {
        val message = bytesToSign(txn)
        val digest = SHA512tDigest(256)
        digest.update(message, 0, message.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return AlgoAddress.base32(out)
    }
}
