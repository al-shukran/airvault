package app.airvault.chains.solana.signer

import app.airvault.core.model.isPrintableCodePoint
import app.lensly.core.registry.UnknownCallPolicy
import app.lensly.core.ur.PathStep
import fr.acinq.bitcoin.Base58
import java.math.BigInteger

/** A warning the review shows beside a Solana request. The owner reads each one before the request can be signed. */
enum class SolanaWarning {
    /** An instruction calls a program Lensly does not read. Lensly shows its raw bytes. */
    UNKNOWN_PROGRAM,

    /**
     * A durable nonce names an authority that is not the signing account. Only the authority can advance the nonce, so this account
     * cannot. The review shows this at error level.
     */
    NONCE_AUTHORITY_NOT_SIGNER,

    /**
     * A transaction advances a durable nonce. It has no expiry: it stays valid until the nonce advances, and advancing the nonce cancels it.
     */
    DURABLE_NONCE,

    /** An account comes from an address lookup table. The table is not in the request, so Lensly cannot check the address offline. */
    LOOKUP_TABLE,

    /** A token transfer or a token account creation names a mint the token list does not hold. Lensly shows the mint, and no name. */
    UNKNOWN_TOKEN,

    /** A plain SPL Transfer names no mint, so its decimals are not known. Lensly shows its amount in base units. */
    DECIMALS_UNKNOWN,

    /**
     * A token transfer runs under Token-2022. Its mint may carry extensions that Lensly cannot read offline: a transfer fee, which can make
     * the recipient receive less than the amount shown, a transfer hook, which runs another program, or a permanent delegate, which can move
     * the tokens later. The review shows this at CAUTION.
     */
    TOKEN_2022,
}

/**
 * A Solana request as Lensly reviews it before it signs. [accountIndex] is N of the account `m/44'/501'/N'` that signs, and [signer] is
 * its 32-byte public key. A transaction holds its parsed [message] and its [instructions], in order. A message to sign holds its parsed
 * [offchainMessage]. [lookups] lists each lookup-table account that the instructions use, in the order they first appear. [tokens] names
 * the mints the review shows. [cluster] is the cluster the request states, or null when it states none. [origin] is the origin the wallet
 * names: a claim the review shows as unverified. Every field holds public data only.
 */
class SolanaRequestReview internal constructor(
    val signType: SolSignType,
    val accountIndex: Int,
    val signer: ByteArray,
    val message: SolanaMessage?,
    val instructions: List<SolanaInstruction>,
    val lookups: List<SolanaMessageAccount.Lookup>,
    val offchainMessage: SolanaOffchainMessage?,
    val tokens: SolanaTokenList,
    val cluster: SolCluster?,
    val origin: String?,
) {
    /** The address of the signing account, in base58. */
    val signerAddress: String get() = Base58.encode(signer)

    /** The fee payer of a transaction, in base58. Null for a message. */
    val feePayer: String? get() = message?.feePayer

    /** The token a mint names, when [tokens] holds it. A mint from a lookup table cannot be named, so it names no token. */
    fun tokenOf(mint: SolanaMessageAccount): SolanaToken? = (mint as? SolanaMessageAccount.Static)?.let { tokens.token(it.address) }

    /** The mints the instructions name, through a token transfer or a token account creation, in order and once each. */
    val mints: List<SolanaMessageAccount>
        get() = instructions.mapNotNull { instruction ->
            when (instruction) {
                is SolanaInstruction.TokenTransferChecked -> instruction.mint
                is SolanaInstruction.AssociatedTokenCreate -> instruction.mint
                else -> null
            }
        }.distinct()

    /** The compute limit the transaction sets, or null when it sets none. A transaction sets at most one, or it is refused. */
    val computeUnitLimit: UInt?
        get() = instructions.filterIsInstance<SolanaInstruction.SetComputeUnitLimit>().singleOrNull()?.units

    /**
     * The priority fee in lamports: the compute price times the units the transaction pays for. Those are the limit it sets. With no limit
     * they are the defaults of its instructions, see [defaultUnits]. Zero when the transaction sets no price.
     */
    val priorityFeeLamports: ULong
        get() {
            val price = instructions.filterIsInstance<SolanaInstruction.SetComputeUnitPrice>().singleOrNull() ?: return 0uL
            return priorityFee(computeUnitLimit ?: defaultUnits(), price.microLamports)
        }

    /** Whether [priorityFeeLamports] is an upper bound rather than the exact fee: the transaction sets a price and no limit. */
    val priorityFeeIsBound: Boolean
        get() = computeUnitLimit == null && instructions.any { it is SolanaInstruction.SetComputeUnitPrice }

    /** The base fee in lamports: [LAMPORTS_PER_SIGNATURE] for each signature the transaction requires. Zero for a message. */
    val baseFeeLamports: ULong
        get() = (message?.header?.numRequiredSignatures ?: 0).toULong() * LAMPORTS_PER_SIGNATURE

    /**
     * The most the transaction can cost in fees: the base fee and the priority fee, capped at the largest u64 when their sum would wrap. It is
     * exact unless [priorityFeeIsBound] is set.
     */
    val maxFeeLamports: ULong
        get() = (baseFeeLamports + priorityFeeLamports).let { if (it < baseFeeLamports) ULong.MAX_VALUE else it }

    /** The recent blockhash of a transaction, in base58. For a durable nonce it is the nonce's stored value. Null for a message. */
    val recentBlockhash: String?
        get() = message?.let { Base58.encode(it.recentBlockhash) }

    /** The required signers other than this account and the fee payer, in base58, in the order the message lists them. */
    val otherRequiredSigners: List<String>
        get() {
            val tx = message ?: return emptyList()
            return (0 until tx.header.numRequiredSignatures)
                .map { Base58.encode(tx.accountKeys[it]) }
                .filter { it != signerAddress && it != tx.feePayer }
        }

    /**
     * The units a transaction with no limit pays for, as the runtime allocates them: each instruction of the message is counted by its
     * program, capped at [MAX_UNITS]. A nonce setup is two instructions, so it counts twice.
     */
    private fun defaultUnits(): UInt {
        val tx = message ?: return 0u
        return minOf(tx.instructions.sumOf { defaultUnitsOf(tx.account(it.programIdIndex)) }, MAX_UNITS)
    }

    /** Every warning this review shows, in a fixed order. */
    val warnings: List<SolanaWarning>
        get() = buildList {
            if (instructions.any { it is SolanaInstruction.Raw }) add(SolanaWarning.UNKNOWN_PROGRAM)
            if (nonceAuthorityIsNotSigner) add(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER)
            if (instructions.any { it is SolanaInstruction.AdvanceNonce }) add(SolanaWarning.DURABLE_NONCE)
            if (instructions.any { it is SolanaInstruction.TokenTransfer }) add(SolanaWarning.DECIMALS_UNKNOWN)
            if (mints.any { tokenOf(it) == null }) add(SolanaWarning.UNKNOWN_TOKEN)
            if (lookups.isNotEmpty()) add(SolanaWarning.LOOKUP_TABLE)
            if (instructions.any { it.isToken2022Transfer }) add(SolanaWarning.TOKEN_2022)
        }

    /**
     * Whether a durable nonce, in its advance or its setup, names an authority other than the signing account. The check is by key. An
     * account from a lookup table never signs, so it is never the signer.
     */
    private val nonceAuthorityIsNotSigner: Boolean
        get() = instructions.any { instruction ->
            val authority = when (instruction) {
                is SolanaInstruction.AdvanceNonce -> instruction.authority
                is SolanaInstruction.CreateNonceAccount -> instruction.authority
                else -> return@any false
            }
            authority != SolanaMessageAccount.Static(signerAddress)
        }
}

/**
 * The priority fee of [units] compute units at [microLamportsPerUnit] micro-lamports each, in lamports. The fee is rounded up to a whole
 * lamport and capped at the largest u64, as `get_prioritization_fee` in Agave's compute-budget crate does.
 */
fun priorityFee(units: UInt, microLamportsPerUnit: ULong): ULong {
    val micro = BigInteger(microLamportsPerUnit.toString()).multiply(BigInteger(units.toString()))
    val lamports = micro.add(MICRO_LAMPORTS_PER_LAMPORT.subtract(BigInteger.ONE)).divide(MICRO_LAMPORTS_PER_LAMPORT)
    return if (lamports.bitLength() > ULong.SIZE_BITS) ULong.MAX_VALUE else lamports.toLong().toULong()
}

private val MICRO_LAMPORTS_PER_LAMPORT: BigInteger = BigInteger.valueOf(1_000_000)

/** The base fee of one signature, in lamports. */
private val LAMPORTS_PER_SIGNATURE: ULong = 5_000uL

/** The compute units an instruction is given when its transaction sets no limit, for an instruction that is not a built-in. */
private val DEFAULT_UNITS_PER_INSTRUCTION: UInt = 200_000u

/**
 * The compute units an instruction of the System Program is given when its transaction sets no limit. The runtime's
 * MAX_BUILTIN_ALLOCATION_COMPUTE_UNIT_LIMIT, from SIMD-0170: a builtin uses far fewer units than the default for other programs.
 */
private val MAX_BUILTIN_UNITS: UInt = 3_000u

/** The largest compute limit a transaction can request. */
private val MAX_UNITS: UInt = 1_400_000u

/**
 * The default compute units of one instruction, by the program it calls. A System Program instruction is a builtin and gets
 * [MAX_BUILTIN_UNITS]. The compute budget program's instructions set the limit and price, so they pay for nothing by default. Any other
 * program, including one a lookup table names, gets [DEFAULT_UNITS_PER_INSTRUCTION].
 */
private fun defaultUnitsOf(program: SolanaMessageAccount): UInt =
    when ((program as? SolanaMessageAccount.Static)?.address) {
        SYSTEM_PROGRAM -> MAX_BUILTIN_UNITS
        COMPUTE_BUDGET_PROGRAM -> 0u
        else -> DEFAULT_UNITS_PER_INSTRUCTION
    }

/** Whether this instruction is a token transfer that the Token-2022 program runs. */
private val SolanaInstruction.isToken2022Transfer: Boolean
    get() = (this is SolanaInstruction.TokenTransfer || this is SolanaInstruction.TokenTransferChecked) &&
        (programId as? SolanaMessageAccount.Static)?.address == TOKEN_2022_PROGRAM

/**
 * Checks a sol-sign-request against the Solana accounts of its wallet, and builds the review the owner reads. Nothing here signs, and
 * nothing here reads a key. The caller passes the public keys of the wallet's Solana accounts, in account order, and the [tokens] the
 * review names. Without [tokens] every mint is unknown.
 */
object SolanaRequestCheck {
    /** The outcome of [check]: a review to show, or the reason the request is refused. Nothing is signed for a refusal. */
    sealed interface Result {
        class Ok(val review: SolanaRequestReview) : Result

        class Refused(val reason: String) : Result
    }

    /** The reason for a path that is not m/44'/501'/N'. */
    const val PATH_REFUSED = "The derivation path is not m/44'/501'/N'."

    /** The reason for an account N that the wallet does not have. */
    const val UNKNOWN_ACCOUNT = "This wallet has no such Solana account."

    /** The reason for a request whose signer address is not the account's key. */
    const val DIFFERENT_ADDRESS = "This request is for a different address."

    /** The reason for a transaction that cannot be read. */
    const val UNREADABLE_TRANSACTION = "The transaction could not be read."

    /** The reason for a transaction that sets its compute limit or its compute price more than once. */
    const val DUPLICATE_COMPUTE_BUDGET = "The transaction sets its compute limit or its compute price more than once."

    /** The reason for a transaction that sends to an account from an address lookup table, which Lensly cannot check offline. */
    const val LOOKUP_RECIPIENT_REFUSAL =
        "This transaction sends to an account from an address lookup table, which Lensly cannot check offline."

    /** The reason for a transaction that lists one account twice. */
    const val DUPLICATE_ACCOUNT = "The transaction lists an account twice."

    /** The reason for a transaction that the account does not sign. */
    const val NOT_A_SIGNER = "This account does not sign this transaction."

    /** The reason for a transaction that calls a program Lensly does not read, when the Unknown contract calls setting refuses it. */
    const val UNKNOWN_PROGRAM_REFUSAL =
        "This transaction calls a program that Lensly does not read, and your Unknown Contract Calls setting refuses it."

    /** The reason for a message whose bytes decode as a transaction. Lensly does not sign a transaction as a message. */
    const val TRANSACTION_AS_MESSAGE = "These bytes are a Solana transaction. Lensly does not sign a transaction as a message."

    /** The reason for an off-chain message that does not name the requesting account as a signer. */
    const val SIGNER_NOT_NAMED = "This message does not name this account as a signer."

    /** The reason for an origin with a character Lensly cannot show. The review shows the origin as the wallet's claim, so it must read. */
    const val UNREADABLE_ORIGIN = "The request names an origin with a character Lensly cannot show."

    private const val PURPOSE = 44
    private const val SOLANA_COIN_TYPE = 501

    /** The account number N of a Solana derivation path, m/44'/501'/N', or null for any other path. */
    fun accountIndexOf(path: List<PathStep>): Int? {
        if (path.size != 3) return null
        val (purpose, coin, account) = path
        if (purpose != PathStep(PURPOSE, true) || coin != PathStep(SOLANA_COIN_TYPE, true) || !account.hardened) return null
        return account.index
    }

    /**
     * Checks [request] against [accountKeys], the public keys of the wallet's Solana accounts. Account N is the key at position N. A
     * request is refused when its path is not m/44'/501'/N', when the wallet has no account N, when it names a signer address other than
     * account N's key, or when a transaction cannot be read, lists an account twice, or does not take account N as a signer. A
     * transaction that sets its compute limit or price more than once is refused, and so is one that sends SOL or tokens to an account
     * from an address lookup table. A transaction that calls a program Lensly does not read is refused when [unknownCalls] is REFUSE, and
     * reviewed under an error-level warning when it is WARN, which is the default. A message is refused unless it is a Solana off-chain
     * message that names account N as a signer, and bytes that decode as a transaction are refused as a message whatever their format. A
     * request whose origin holds a character Lensly cannot show is refused first.
     */
    fun check(
        request: SolSignRequest,
        accountKeys: List<ByteArray>,
        tokens: SolanaTokenList = SolanaTokenList.EMPTY,
        unknownCalls: UnknownCallPolicy = UnknownCallPolicy.WARN,
    ): Result {
        if (request.origin?.codePoints()?.allMatch(::isPrintableCodePoint) == false) return Result.Refused(UNREADABLE_ORIGIN)
        val index = accountIndexOf(request.derivationPath) ?: return Result.Refused(PATH_REFUSED)
        val signer = accountKeys.getOrNull(index) ?: return Result.Refused(UNKNOWN_ACCOUNT)
        return checkSigner(request, index, signer, tokens, unknownCalls)
    }

    /**
     * Checks [request] against account [index], whose public key is [signer]. [check] calls it with the key of the wallet's stored account. The
     * signer also calls it at sign time with the key the key access derived, so a key that is not the account the request names signs nothing,
     * under the same rules that the review applied.
     */
    fun checkSigner(
        request: SolSignRequest,
        index: Int,
        signer: ByteArray,
        tokens: SolanaTokenList = SolanaTokenList.EMPTY,
        unknownCalls: UnknownCallPolicy = UnknownCallPolicy.WARN,
    ): Result {
        val named = request.address
        if (named != null && !named.contentEquals(signer)) return Result.Refused(DIFFERENT_ADDRESS)
        return when (request.signType) {
            SolSignType.TRANSACTION ->
                transaction(request.signData, index, signer, tokens, unknownCalls, request.cluster, request.origin)
            SolSignType.MESSAGE -> messageToSign(request.signData, index, signer, tokens, request.cluster, request.origin)
        }
    }

    private fun transaction(
        bytes: ByteArray,
        index: Int,
        signer: ByteArray,
        tokens: SolanaTokenList,
        unknownCalls: UnknownCallPolicy,
        cluster: SolCluster?,
        origin: String?,
    ): Result {
        val message = when (val decoded = SolanaMessage.decode(bytes)) {
            is SolanaMessage.Result.Ok -> decoded.message
            is SolanaMessage.Result.Rejected -> return Result.Refused(UNREADABLE_TRANSACTION)
        }
        val keys = message.accountKeys
        if (keys.map { it.toList() }.toSet().size != keys.size) return Result.Refused(DUPLICATE_ACCOUNT)
        if (keys.take(message.header.numRequiredSignatures).none { it.contentEquals(signer) }) return Result.Refused(NOT_A_SIGNER)
        val instructions = message.decodeInstructions()
        if (instructions.count { it is SolanaInstruction.SetComputeUnitLimit } > 1 ||
            instructions.count { it is SolanaInstruction.SetComputeUnitPrice } > 1
        ) {
            return Result.Refused(DUPLICATE_COMPUTE_BUDGET)
        }
        if (unknownCalls == UnknownCallPolicy.REFUSE && instructions.any { it is SolanaInstruction.Raw }) {
            return Result.Refused(UNKNOWN_PROGRAM_REFUSAL)
        }
        if (instructions.any { instruction -> valueRecipients(instruction).any { it is SolanaMessageAccount.Lookup } }) {
            return Result.Refused(LOOKUP_RECIPIENT_REFUSAL)
        }
        val lookups = message.instructions
            .flatMap { listOf(it.programIdIndex) + it.accountIndices }
            .map { message.account(it) }
            .filterIsInstance<SolanaMessageAccount.Lookup>()
            .distinct()
        return Result.Ok(
            SolanaRequestReview(
                signType = SolSignType.TRANSACTION,
                accountIndex = index,
                signer = signer,
                message = message,
                instructions = instructions,
                lookups = lookups,
                offchainMessage = null,
                tokens = tokens,
                cluster = cluster,
                origin = origin,
            ),
        )
    }

    /** The accounts an instruction moves value to: the SOL recipient, and the token destination and authority. */
    private fun valueRecipients(instruction: SolanaInstruction): List<SolanaMessageAccount> = when (instruction) {
        is SolanaInstruction.SystemTransfer -> listOf(instruction.to)
        is SolanaInstruction.TokenTransfer -> listOf(instruction.destination, instruction.authority)
        is SolanaInstruction.TokenTransferChecked -> listOf(instruction.destination, instruction.authority)
        else -> emptyList()
    }

    /**
     * A message is signed only in the off-chain format, and only for an account it names as a signer. Bytes that decode as a transaction
     * are refused whatever their format, so a signature Lensly makes over a message is never a transaction signature.
     */
    private fun messageToSign(
        bytes: ByteArray,
        index: Int,
        signer: ByteArray,
        tokens: SolanaTokenList,
        cluster: SolCluster?,
        origin: String?,
    ): Result {
        if (SolanaMessage.decode(bytes) is SolanaMessage.Result.Ok) return Result.Refused(TRANSACTION_AS_MESSAGE)
        val offchain = when (val parsed = SolanaOffchainMessage.parse(bytes)) {
            is SolanaOffchainMessage.Result.Ok -> parsed.message
            is SolanaOffchainMessage.Result.Rejected -> return Result.Refused(parsed.reason)
        }
        if (offchain.signers.none { it.contentEquals(signer) }) return Result.Refused(SIGNER_NOT_NAMED)
        return Result.Ok(
            SolanaRequestReview(
                signType = SolSignType.MESSAGE,
                accountIndex = index,
                signer = signer,
                message = null,
                instructions = emptyList(),
                lookups = emptyList(),
                offchainMessage = offchain,
                tokens = tokens,
                cluster = cluster,
                origin = origin,
            ),
        )
    }
}
