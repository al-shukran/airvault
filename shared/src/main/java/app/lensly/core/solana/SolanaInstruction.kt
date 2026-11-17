package app.lensly.core.solana

/**
 * An instruction of a [SolanaMessage], decoded for review. The decoder in [decodeInstructions] reads only the known shapes. Anything
 * else is [Raw]: the review shows it as bytes, with a warning. Amounts are in base units: lamports, or token units.
 */
sealed interface SolanaInstruction {
    /** The program the instruction calls. */
    val programId: SolanaMessageAccount

    /** System Program Transfer (instruction index 2). Moves [lamports] from [from] to [to]. */
    data class SystemTransfer(
        override val programId: SolanaMessageAccount,
        val from: SolanaMessageAccount,
        val to: SolanaMessageAccount,
        val lamports: ULong,
    ) : SolanaInstruction

    /** SPL Token or Token-2022 Transfer (instruction tag 3). It carries no mint and no decimals. */
    data class TokenTransfer(
        override val programId: SolanaMessageAccount,
        val source: SolanaMessageAccount,
        val destination: SolanaMessageAccount,
        val authority: SolanaMessageAccount,
        val amount: ULong,
    ) : SolanaInstruction

    /**
     * SPL Token or Token-2022 TransferChecked (instruction tag 12). The mint is listed so the review can show which token this is.
     * The parser does not check the mint or the decimals against the chain.
     */
    data class TokenTransferChecked(
        override val programId: SolanaMessageAccount,
        val source: SolanaMessageAccount,
        val mint: SolanaMessageAccount,
        val destination: SolanaMessageAccount,
        val authority: SolanaMessageAccount,
        val amount: ULong,
        val decimals: Int,
    ) : SolanaInstruction

    /**
     * Associated Token Account program: create the account for [owner] and [mint], paid by [payer]. When [idempotent] is set, the
     * program does nothing if the account already exists.
     */
    data class AssociatedTokenCreate(
        override val programId: SolanaMessageAccount,
        val payer: SolanaMessageAccount,
        val account: SolanaMessageAccount,
        val owner: SolanaMessageAccount,
        val mint: SolanaMessageAccount,
        val idempotent: Boolean,
    ) : SolanaInstruction

    /** ComputeBudget SetComputeUnitLimit (instruction tag 2). */
    data class SetComputeUnitLimit(
        override val programId: SolanaMessageAccount,
        val units: UInt,
    ) : SolanaInstruction

    /** ComputeBudget SetComputeUnitPrice (instruction tag 3), in micro-lamports per compute unit. */
    data class SetComputeUnitPrice(
        override val programId: SolanaMessageAccount,
        val microLamports: ULong,
    ) : SolanaInstruction

    /**
     * System Program AdvanceNonceAccount (instruction index 4). The network reads a durable nonce only from the first instruction, so this
     * is recognised only there. [nonceAccount] holds the nonce, and [authority] must sign to advance it.
     */
    data class AdvanceNonce(
        override val programId: SolanaMessageAccount,
        val nonceAccount: SolanaMessageAccount,
        val authority: SolanaMessageAccount,
    ) : SolanaInstruction

    /**
     * The one-time setup of a durable nonce account: System CreateAccount for [account], with [lamports] of rent, followed by System
     * InitializeNonceAccount that names [authority]. The two instructions are one here.
     */
    data class CreateNonceAccount(
        override val programId: SolanaMessageAccount,
        val account: SolanaMessageAccount,
        val lamports: ULong,
        val authority: SolanaMessageAccount,
    ) : SolanaInstruction

    /**
     * Any other program, or a known program with a shape this decoder does not read. [dataHex] is the instruction data, and
     * [accountIndices] are the instruction's indices into the message.
     */
    data class Raw(
        override val programId: SolanaMessageAccount,
        val accountIndices: List<Int>,
        val accounts: List<SolanaMessageAccount>,
        val dataHex: String,
    ) : SolanaInstruction
}
