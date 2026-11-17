package app.lensly.core.solana

import app.lensly.core.registry.UnknownCallPolicy
import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolCluster
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignType
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

    /** An account comes from an address lookup table. The table is not in the request, so Lensly cannot check the address offline. */
    LOOKUP_TABLE,

    /** A token transfer or a token account creation names a mint the token list does not hold. Lensly shows the mint, and no name. */
    UNKNOWN_TOKEN,

    /** A plain SPL Transfer names no mint, so its decimals are not known. Lensly shows its amount in base units. */
    DECIMALS_UNKNOWN,
}

/**
 * A Solana request as Lensly reviews it before it signs. [accountIndex] is N of the account `m/44'/501'/N'` that signs, and [signer] is
 * its 32-byte public key. A transaction holds its parsed [message] and its [instructions], in order. A message to sign holds its
 * [messageBytes]. [lookups] lists each lookup-table account that the instructions use, in the order they first appear. [tokens] names
 * the mints the review shows. [cluster] is the cluster the request states, or null when it states none. Every field holds public data only.
 */
class SolanaRequestReview internal constructor(
    val signType: SolSignType,
    val accountIndex: Int,
    val signer: ByteArray,
    val message: SolanaMessage?,
    val instructions: List<SolanaInstruction>,
    val lookups: List<SolanaMessageAccount.Lookup>,
    val messageBytes: ByteArray?,
    val tokens: SolanaTokenList,
    val cluster: SolCluster?,
) {
    /** The address of the signing account, in base58. */
    val signerAddress: String get() = SolanaAccount.addressOf(signer)

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

    /**
     * The priority fee in lamports, when the transaction sets exactly one compute limit and exactly one compute price. Null otherwise.
     */
    val priorityFeeLamports: ULong?
        get() {
            val limits = instructions.filterIsInstance<SolanaInstruction.SetComputeUnitLimit>()
            val prices = instructions.filterIsInstance<SolanaInstruction.SetComputeUnitPrice>()
            if (limits.size != 1 || prices.size != 1) return null
            return priorityFee(limits[0].units, prices[0].microLamports)
        }

    /** Every warning this review shows, in a fixed order. */
    val warnings: List<SolanaWarning>
        get() = buildList {
            if (instructions.any { it is SolanaInstruction.Raw }) add(SolanaWarning.UNKNOWN_PROGRAM)
            if (nonceAuthorityIsNotSigner) add(SolanaWarning.NONCE_AUTHORITY_NOT_SIGNER)
            if (instructions.any { it is SolanaInstruction.TokenTransfer }) add(SolanaWarning.DECIMALS_UNKNOWN)
            if (mints.any { tokenOf(it) == null }) add(SolanaWarning.UNKNOWN_TOKEN)
            if (lookups.isNotEmpty()) add(SolanaWarning.LOOKUP_TABLE)
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
internal fun priorityFee(units: UInt, microLamportsPerUnit: ULong): ULong {
    val micro = BigInteger(microLamportsPerUnit.toString()).multiply(BigInteger(units.toString()))
    val lamports = micro.add(MICRO_LAMPORTS_PER_LAMPORT.subtract(BigInteger.ONE)).divide(MICRO_LAMPORTS_PER_LAMPORT)
    return if (lamports.bitLength() > ULong.SIZE_BITS) ULong.MAX_VALUE else lamports.toLong().toULong()
}

private val MICRO_LAMPORTS_PER_LAMPORT: BigInteger = BigInteger.valueOf(1_000_000)

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

    /** The reason for a transaction that lists one account twice. */
    const val DUPLICATE_ACCOUNT = "The transaction lists an account twice."

    /** The reason for a transaction that the account does not sign. */
    const val NOT_A_SIGNER = "This account does not sign this transaction."

    /** The reason for a transaction that calls a program Lensly does not read, when the Unknown contract calls setting refuses it. */
    const val UNKNOWN_PROGRAM_REFUSAL =
        "This transaction calls a program that Lensly does not read, and your Unknown contract calls setting refuses it."

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
     * transaction that calls a program Lensly does not read is refused when [unknownCalls] is REFUSE, and reviewed under an error-level
     * warning when it is WARN, which is the default.
     */
    fun check(
        request: SolSignRequest,
        accountKeys: List<ByteArray>,
        tokens: SolanaTokenList = SolanaTokenList.EMPTY,
        unknownCalls: UnknownCallPolicy = UnknownCallPolicy.WARN,
    ): Result {
        val index = accountIndexOf(request.derivationPath) ?: return Result.Refused(PATH_REFUSED)
        val signer = accountKeys.getOrNull(index) ?: return Result.Refused(UNKNOWN_ACCOUNT)
        val named = request.address
        if (named != null && !named.contentEquals(signer)) return Result.Refused(DIFFERENT_ADDRESS)
        return when (request.signType) {
            SolSignType.TRANSACTION -> transaction(request.signData, index, signer, tokens, unknownCalls, request.cluster)
            SolSignType.MESSAGE -> Result.Ok(
                SolanaRequestReview(
                    signType = SolSignType.MESSAGE,
                    accountIndex = index,
                    signer = signer,
                    message = null,
                    instructions = emptyList(),
                    lookups = emptyList(),
                    messageBytes = request.signData,
                    tokens = tokens,
                    cluster = request.cluster,
                ),
            )
        }
    }

    private fun transaction(
        bytes: ByteArray,
        index: Int,
        signer: ByteArray,
        tokens: SolanaTokenList,
        unknownCalls: UnknownCallPolicy,
        cluster: SolCluster?,
    ): Result {
        val message = when (val decoded = SolanaMessage.decode(bytes)) {
            is SolanaMessage.Result.Ok -> decoded.message
            is SolanaMessage.Result.Rejected -> return Result.Refused(UNREADABLE_TRANSACTION)
        }
        val keys = message.accountKeys
        if (keys.map { it.toList() }.toSet().size != keys.size) return Result.Refused(DUPLICATE_ACCOUNT)
        if (keys.take(message.header.numRequiredSignatures).none { it.contentEquals(signer) }) return Result.Refused(NOT_A_SIGNER)
        val instructions = message.decodeInstructions()
        if (unknownCalls == UnknownCallPolicy.REFUSE && instructions.any { it is SolanaInstruction.Raw }) {
            return Result.Refused(UNKNOWN_PROGRAM_REFUSAL)
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
                messageBytes = null,
                tokens = tokens,
                cluster = cluster,
            ),
        )
    }
}
