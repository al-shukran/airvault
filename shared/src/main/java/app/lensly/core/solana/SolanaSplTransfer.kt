package app.lensly.core.solana

import fr.acinq.bitcoin.Base58
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The legacy message of one SPL token transfer from a nonce account, as Lumio builds it and Lensly signs it. It holds, in order: AdvanceNonceAccount
 * for the account's nonce account, which must come first; then, when the recipient has no token account for the mint, the Associated Token Account
 * program's CreateIdempotent for it, paid by the account; then Token TransferChecked from the account's token account to the recipient's. The
 * account is the only signer. It is the fee payer and the transfer's authority. The keys are in the order the Solana SDK compiles: the fee payer
 * first, then the writable keys and the read-only keys, each group in ascending byte order. Nothing here needs a key.
 */
object SolanaSplTransfer {
    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
    private const val RECENT_BLOCKHASHES = "SysvarRecentB1ockHashes11111111111111111111"
    private const val KEY_BYTES = 32
    private const val ADVANCE_NONCE_ACCOUNT = 4
    private const val CREATE_IDEMPOTENT = 1
    private const val TRANSFER_CHECKED = 12
    private const val MAX_DECIMALS = 18

    /** One account of the message: its key, and whether it signs or is written. */
    private class Entry(val key: ByteArray) {
        var signer = false
        var writable = false
    }

    /**
     * The serialized message that moves [amount] base units of [mint] from the token account of [account] to that of [recipient], where the mint
     * has [decimals] decimals. [createRecipientAccount] adds the CreateIdempotent that opens the recipient's account, and it is set when that
     * account does not exist yet. [nonceValue] is the nonce that the account's nonce account holds, and the message names it as its recent
     * blockhash. Throws IllegalArgumentException when a key is not 32 bytes, the recipient is the account, the amount is not positive or does not
     * fit in 64 bits, or the decimals are outside 0 to 18.
     */
    fun message(
        account: ByteArray,
        recipient: ByteArray,
        mint: ByteArray,
        amount: ULong,
        decimals: Int,
        nonceValue: ByteArray,
        createRecipientAccount: Boolean,
    ): ByteArray {
        require(account.size == KEY_BYTES && recipient.size == KEY_BYTES && mint.size == KEY_BYTES) { "A Solana address is 32 bytes." }
        require(!recipient.contentEquals(account)) { "The recipient is this account." }
        require(amount > 0UL) { "The amount is more than 0." }
        require(decimals in 0..MAX_DECIMALS) { "A token has 0 to $MAX_DECIMALS decimals." }

        val nonce = SolanaNonce.accountOf(account)
        val source = SolanaTokenAccounts.associatedAddress(account, mint)
        val destination = SolanaTokenAccounts.associatedAddress(recipient, mint)
        val system = Base58.decode(SYSTEM_PROGRAM)
        val sysvar = Base58.decode(RECENT_BLOCKHASHES)
        val token = Base58.decode(SolanaTokenAccounts.TOKEN_PROGRAM)
        val ataProgram = Base58.decode(SolanaTokenAccounts.ASSOCIATED_TOKEN_PROGRAM)

        val entries = LinkedHashMap<String, Entry>()
        fun use(key: ByteArray, signer: Boolean = false, writable: Boolean = false) {
            val entry = entries.getOrPut(Base58.encode(key)) { Entry(key) }
            entry.signer = entry.signer || signer
            entry.writable = entry.writable || writable
        }
        use(account, signer = true, writable = true)
        use(nonce, writable = true)
        use(source, writable = true)
        use(destination, writable = true)
        use(mint)
        use(system)
        use(sysvar)
        use(token)
        if (createRecipientAccount) {
            use(recipient)
            use(ataProgram)
        }

        val signers = entries.values.filter { it.signer }
        val writable = entries.values.filter { !it.signer && it.writable }.sortedWith(BYTE_ORDER)
        val readonly = entries.values.filter { !it.signer && !it.writable }.sortedWith(BYTE_ORDER)
        val ordered = signers + writable + readonly
        val index = ordered.withIndex().associate { Base58.encode(it.value.key) to it.index }
        fun at(key: ByteArray): Int = checkNotNull(index[Base58.encode(key)])

        val instructions = ArrayList<SolanaNonce.Instruction>()
        instructions += SolanaNonce.Instruction(
            program = at(system),
            accounts = listOf(at(nonce), at(sysvar), at(account)),
            data = littleEndian32(ADVANCE_NONCE_ACCOUNT),
        )
        if (createRecipientAccount) {
            instructions += SolanaNonce.Instruction(
                program = at(ataProgram),
                accounts = listOf(at(account), at(destination), at(recipient), at(mint), at(system), at(token)),
                data = byteArrayOf(CREATE_IDEMPOTENT.toByte()),
            )
        }
        instructions += SolanaNonce.Instruction(
            program = at(token),
            accounts = listOf(at(source), at(mint), at(destination), at(account)),
            data = byteArrayOf(TRANSFER_CHECKED.toByte()) + littleEndian64(amount) + byteArrayOf(decimals.toByte()),
        )
        return SolanaNonce.compile(ordered.map { it.key }, readonly.size, nonceValue, instructions)
    }

    /** Keys compare as unsigned 256-bit numbers, which is their byte order, since every key is 32 bytes. */
    private val BYTE_ORDER = Comparator<Entry> { a, b -> BigInteger(1, a.key).compareTo(BigInteger(1, b.key)) }

    private fun littleEndian32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun littleEndian64(value: ULong): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong()).array()
}
