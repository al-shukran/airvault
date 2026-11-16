package app.lensly.core.solana

import fr.acinq.bitcoin.Base58
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The legacy message of one System Program transfer, as Lumio builds it and Lensly signs it. The sender is the only signer and the fee payer,
 * the recipient is writable, and the System Program is the one readonly account. It holds public data only. The bytes it returns are the bytes
 * the signer signs, unchanged, and [SolanaMessage.decode] reads them back as this transfer.
 */
object SolanaTransfer {
    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    private const val KEY_BYTES = 32
    private const val BLOCKHASH_BYTES = 32

    /** The index of the System Program in the message's static keys: sender 0, recipient 1, System Program 2. */
    private const val SYSTEM_PROGRAM_INDEX = 2

    /** System Program Transfer is instruction index 2, a u32 little-endian, then the amount as a u64 little-endian. */
    private const val TRANSFER_INSTRUCTION = 2
    private const val TRANSFER_DATA_BYTES = 12

    /**
     * The serialized message that moves [lamports] from [sender] to [recipient] and names [recentBlockhash]. Keys and blockhash are 32 bytes each.
     * A negative amount is refused. Any other amount is written as it is, and the caller checks the balance first.
     */
    fun message(sender: ByteArray, recipient: ByteArray, lamports: Long, recentBlockhash: ByteArray): ByteArray {
        require(sender.size == KEY_BYTES && recipient.size == KEY_BYTES) { "A Solana address is 32 bytes." }
        require(recentBlockhash.size == BLOCKHASH_BYTES) { "A blockhash is 32 bytes." }
        require(lamports >= 0) { "An amount is not negative." }
        val data = ByteBuffer.allocate(TRANSFER_DATA_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TRANSFER_INSTRUCTION)
            .putLong(lamports)
            .array()
        val out = ByteArrayOutputStream()
        // Header: one required signature, no readonly signed accounts, and one readonly unsigned account, the System Program.
        out.write(byteArrayOf(1, 0, 1))
        // Static keys: the sender (writable signer), the recipient (writable), then the System Program (readonly).
        out.write(compactU16(3))
        out.write(sender)
        out.write(recipient)
        out.write(Base58.decode(SYSTEM_PROGRAM))
        out.write(recentBlockhash)
        // One instruction: its program, its two accounts by index (sender 0, recipient 1), and its data.
        out.write(compactU16(1))
        out.write(byteArrayOf(SYSTEM_PROGRAM_INDEX.toByte()))
        out.write(compactU16(2))
        out.write(byteArrayOf(0, 1))
        out.write(compactU16(data.size))
        out.write(data)
        return out.toByteArray()
    }
}
