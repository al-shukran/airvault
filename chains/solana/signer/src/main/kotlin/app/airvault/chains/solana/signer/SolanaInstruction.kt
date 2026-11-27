package app.airvault.chains.solana.signer


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
     * The one-time setup of a durable nonce account: System CreateAccount, or CreateAccountWithSeed, for [account], paid for by [funder],
     * with [lamports] of rent, followed by System InitializeNonceAccount that names [authority]. The two instructions are one here. A
     * setup made with a [seed] creates [account] at the address that the seed and its base give.
     */
    data class CreateNonceAccount(
        override val programId: SolanaMessageAccount,
        val funder: SolanaMessageAccount,
        val account: SolanaMessageAccount,
        val lamports: ULong,
        val authority: SolanaMessageAccount,
        val seed: NonceSeed? = null,
    ) : SolanaInstruction

    /** The base account and the seed that a nonce account is derived from: the account is createWithSeed of [base] and [text]. */
    data class NonceSeed(val base: SolanaMessageAccount, val text: String)

    /**
     * SPL Token or Token-2022 Approve (tag 4) or ApproveChecked (tag 13): lets [delegate] spend up to [amount] from [source]. [mint] and
     * [decimals] are set only for the checked form.
     */
    data class TokenApprove(
        override val programId: SolanaMessageAccount,
        val source: SolanaMessageAccount,
        val mint: SolanaMessageAccount?,
        val delegate: SolanaMessageAccount,
        val owner: SolanaMessageAccount,
        val amount: ULong,
        val decimals: Int?,
    ) : SolanaInstruction

    /**
     * SPL Token or Token-2022 SetAuthority (tag 6): hands the [authorityType] authority of [account] from [currentAuthority] to
     * [newAuthority], or removes it when [newAuthority] is null.
     */
    data class TokenSetAuthority(
        override val programId: SolanaMessageAccount,
        val account: SolanaMessageAccount,
        val authorityType: Int,
        val newAuthority: SolanaMessageAccount?,
        val currentAuthority: SolanaMessageAccount,
    ) : SolanaInstruction

    /** SPL Token or Token-2022 CloseAccount (tag 9): closes [account], and its lamports go to [destination]. */
    data class TokenCloseAccount(
        override val programId: SolanaMessageAccount,
        val account: SolanaMessageAccount,
        val destination: SolanaMessageAccount,
        val owner: SolanaMessageAccount,
    ) : SolanaInstruction

    /** SPL Token or Token-2022 Burn (tag 8) or BurnChecked (tag 15): destroys [amount] of [mint] held in [account]. [decimals] is set only for the checked form. */
    data class TokenBurn(
        override val programId: SolanaMessageAccount,
        val account: SolanaMessageAccount,
        val mint: SolanaMessageAccount,
        val authority: SolanaMessageAccount,
        val amount: ULong,
        val decimals: Int?,
    ) : SolanaInstruction

    /** System Program CreateAccount (index 0) that is not a nonce setup: [funder] pays [lamports] into the new [account], which [owner] program owns. */
    data class SystemCreateAccount(
        override val programId: SolanaMessageAccount,
        val funder: SolanaMessageAccount,
        val account: SolanaMessageAccount,
        val lamports: ULong,
        val space: ULong,
        val owner: String,
    ) : SolanaInstruction

    /** System Program Assign (index 1): hands [account] to the program [owner]. */
    data class SystemAssign(
        override val programId: SolanaMessageAccount,
        val account: SolanaMessageAccount,
        val owner: String,
    ) : SolanaInstruction

    /** System Program WithdrawNonceAccount (index 5): [authority] moves [lamports] out of [nonceAccount] to [to]. */
    data class WithdrawNonce(
        override val programId: SolanaMessageAccount,
        val nonceAccount: SolanaMessageAccount,
        val to: SolanaMessageAccount,
        val authority: SolanaMessageAccount,
        val lamports: ULong,
    ) : SolanaInstruction

    /** System Program AuthorizeNonceAccount (index 7): hands the authority of [nonceAccount] from [currentAuthority] to [newAuthority]. */
    data class AuthorizeNonce(
        override val programId: SolanaMessageAccount,
        val nonceAccount: SolanaMessageAccount,
        val currentAuthority: SolanaMessageAccount,
        val newAuthority: SolanaMessageAccount,
    ) : SolanaInstruction

    /**
     * Any other program, or a known program with a shape this decoder does not read. [dataHex] is the instruction data, and
     * [accountIndices] are the instruction's indices into the message. [knownProgram] is set when the program is one this decoder reads
     * in some of its instructions, so the review can say the instruction is not read rather than that the program is unknown.
     */
    data class Raw(
        override val programId: SolanaMessageAccount,
        val accountIndices: List<Int>,
        val accounts: List<SolanaMessageAccount>,
        val dataHex: String,
        val knownProgram: Boolean,
    ) : SolanaInstruction
}
