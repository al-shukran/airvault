package app.lensly.core.btc

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Bitcoin message signing in the BIP137 format: a 65-byte compact signature, base64-encoded. Its header byte holds the
 * recovery id and the address type: 31 to 34 for P2PKH, 35 to 38 for P2SH-P2WPKH and 39 to 42 for P2WPKH. Every key here is
 * compressed. Taproot has no BIP137 header, so it is refused.
 */
object BtcMessage {
    /** The magic line that starts every signed message, as a length byte followed by "Bitcoin Signed Message:\n". */
    private val MAGIC = "\u0018Bitcoin Signed Message:\n".encodeToByteArray()

    /**
     * The signature of [message] by the key at m/.../[change]/[index] of [account], as base64. The owner reads the full text on
     * screen before this is called.
     *
     * @throws IllegalArgumentException for a taproot Account, which BIP137 does not cover
     */
    fun sign(account: BtcAccount, message: String, change: Boolean = false, index: Int = 0): String {
        val branch = if (change) BtcAccount.CHANGE else BtcAccount.RECEIVE
        return signWith(account.privateKey(branch, index), headerBase(account.scriptType), message)
    }

    private fun headerBase(scriptType: ScriptType): Int = when (scriptType) {
        ScriptType.LEGACY -> 31
        ScriptType.NESTED_SEGWIT -> 35
        ScriptType.NATIVE_SEGWIT -> 39
        ScriptType.TAPROOT -> throw IllegalArgumentException("BIP137 has no header for taproot messages")
    }

    /** Signs [message] with [key] and writes the header that is [headerBase] plus the recovery id. */
    internal fun signWith(key: PrivateKey, headerBase: Int, message: String): String {
        val hash = messageHash(message.encodeToByteArray())
        val signature = Crypto.sign(hash, key)
        val publicKey = key.publicKey()
        // The recovery id is the one that recovers this key from the signature. Exactly one of the four does.
        val recid = (0..3).first { candidate ->
            runCatching { Crypto.recoverPublicKey(signature, hash, candidate) }.getOrNull() == publicKey
        }
        val header = (headerBase + recid).toByte()
        return Base64.getEncoder().encodeToString(byteArrayOf(header) + signature.toByteArray())
    }

    /** The double SHA-256 of the magic line, the message length as a compact size, and the message bytes. */
    internal fun messageHash(message: ByteArray): ByteArray = Crypto.hash256(MAGIC + compactSize(message.size) + message)

    private fun compactSize(size: Int): ByteArray = when {
        size < 0xFD -> byteArrayOf(size.toByte())
        size <= 0xFFFF -> byteArrayOf(0xFD.toByte(), size.toByte(), (size shr 8).toByte())
        else -> byteArrayOf(0xFE.toByte()) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array()
    }
}
