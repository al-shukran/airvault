package app.lensly.core.solana

import fr.acinq.bitcoin.Base58

/**
 * A Solana message as Lensly reviews it before signing: the bytes the signer signs, split into their parts. Read-only. Nothing here
 * signs, sends or looks up an address. [decode] builds one from bytes, and it checks every length and index first.
 */
class SolanaMessage internal constructor(
    val version: Version,
    val header: Header,
    /** The static account keys, 32 bytes each. Key 0 is the fee payer. */
    val accountKeys: List<ByteArray>,
    val recentBlockhash: ByteArray,
    val instructions: List<CompiledInstruction>,
    /** Empty for a legacy message. */
    val addressTableLookups: List<AddressTableLookup>,
) {
    enum class Version { LEGACY, V0 }

    class Header(
        val numRequiredSignatures: Int,
        val numReadonlySignedAccounts: Int,
        val numReadonlyUnsignedAccounts: Int,
    )

    /** An instruction as the message stores it: a program and account indices, and the data. */
    class CompiledInstruction(
        val programIdIndex: Int,
        val accountIndices: List<Int>,
        val data: ByteArray,
    )

    /**
     * A lookup table this message loads accounts from. The indexes point into the table, and the table is not in the message, so
     * the loaded addresses cannot be read from the message alone.
     */
    class AddressTableLookup(
        val tableKey: ByteArray,
        val writableIndexes: List<Int>,
        val readonlyIndexes: List<Int>,
    )

    /** Outcome of [decode]. Decoding never throws. */
    sealed interface Result {
        class Ok(val message: SolanaMessage) : Result

        class Rejected(val reason: String) : Result
    }

    /** The fee payer's address, in base58. */
    val feePayer: String get() = Base58.encode(accountKeys[0])

    /**
     * How many accounts an instruction index can name: the static keys, then the loaded writable accounts, then the loaded readonly
     * accounts. Every index in the message is below this.
     */
    val accountCount: Int = accountKeys.size + addressTableLookups.sumOf { it.writableIndexes.size + it.readonlyIndexes.size }

    /** Whether the account at [index] signs: a static key among the first [Header.numRequiredSignatures]. A loaded account never signs. */
    fun isSigner(index: Int): Boolean = index < accountKeys.size && index < header.numRequiredSignatures

    /**
     * Whether the account at [index] is writable. A static key follows the header: signed keys are writable before the read-only signed
     * ones, and unsigned keys are writable before the read-only unsigned ones. A loaded account is writable when its table entry is.
     */
    fun isWritable(index: Int): Boolean = when {
        index >= accountKeys.size -> (account(index) as? SolanaMessageAccount.Lookup)?.writable == true
        isSigner(index) -> index < header.numRequiredSignatures - header.numReadonlySignedAccounts
        else -> index < accountKeys.size - header.numReadonlyUnsignedAccounts
    }

    /** The roles of the account at [index], as the review words them: "signer" when it signs, then "writable" or "read-only". */
    fun roles(index: Int): String =
        listOfNotNull(if (isSigner(index)) "signer" else null, if (isWritable(index)) "writable" else "read-only").joinToString(", ")

    /** The account an index names. [index] must be below [accountCount]. */
    fun account(index: Int): SolanaMessageAccount {
        require(index in 0 until accountCount) { "account index $index is out of range" }
        if (index < accountKeys.size) return SolanaMessageAccount.Static(Base58.encode(accountKeys[index]))
        var position = index - accountKeys.size
        for (lookup in addressTableLookups) {
            if (position < lookup.writableIndexes.size) {
                return SolanaMessageAccount.Lookup(Base58.encode(lookup.tableKey), lookup.writableIndexes[position], writable = true)
            }
            position -= lookup.writableIndexes.size
        }
        for (lookup in addressTableLookups) {
            if (position < lookup.readonlyIndexes.size) {
                return SolanaMessageAccount.Lookup(Base58.encode(lookup.tableKey), lookup.readonlyIndexes[position], writable = false)
            }
            position -= lookup.readonlyIndexes.size
        }
        error("unreachable: the index was checked against accountCount")
    }

    companion object {
        /** The largest message a transaction can carry, which is the Solana packet size. */
        const val MAX_BYTES = 1232

        /** Parses a serialized message. Any input that is not a well-formed message is [Result.Rejected]. */
        fun decode(bytes: ByteArray): Result = try {
            Result.Ok(parseSolanaMessage(bytes))
        } catch (e: MalformedSolanaMessageException) {
            Result.Rejected(e.message ?: "malformed message")
        } catch (e: RuntimeException) {
            // A last-resort guard, as in the EVM parser: no parser bug escapes a scan.
            Result.Rejected("malformed message")
        }
    }
}

/** An account a message refers to: a key the message lists, or a slot in a lookup table that is not in the message. */
sealed interface SolanaMessageAccount {
    /** A key the message lists, in base58. */
    data class Static(val address: String) : SolanaMessageAccount

    /**
     * An account loaded from the lookup table [tableAddress] at [tableIndex]. The address is not in the message, so it cannot be
     * shown from the message alone.
     */
    data class Lookup(val tableAddress: String, val tableIndex: Int, val writable: Boolean) : SolanaMessageAccount
}
