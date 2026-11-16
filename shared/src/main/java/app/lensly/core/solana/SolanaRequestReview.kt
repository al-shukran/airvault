package app.lensly.core.solana

import app.lensly.core.ur.PathStep
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignType

/** A warning the review shows beside a Solana request. The owner reads each one before the request can be signed. */
enum class SolanaWarning {
    /** An instruction calls a program Lensly does not read. Lensly shows its raw bytes. */
    UNKNOWN_PROGRAM,

    /** An account comes from an address lookup table. The table is not in the request, so Lensly cannot check the address offline. */
    LOOKUP_TABLE,
}

/**
 * A Solana request as Lensly reviews it before it signs. [accountIndex] is N of the account `m/44'/501'/N'` that signs, and [signer] is
 * its 32-byte public key. A transaction holds its parsed [message] and its [instructions], in order. A message to sign holds its
 * [messageBytes]. [lookups] lists each lookup-table account that the instructions use, in the order they first appear. It holds public
 * data only.
 */
class SolanaRequestReview internal constructor(
    val signType: SolSignType,
    val accountIndex: Int,
    val signer: ByteArray,
    val message: SolanaMessage?,
    val instructions: List<SolanaInstruction>,
    val lookups: List<SolanaMessageAccount.Lookup>,
    val messageBytes: ByteArray?,
) {
    /** The address of the signing account, in base58. */
    val signerAddress: String get() = SolanaAccount.addressOf(signer)

    /** The fee payer of a transaction, in base58. Null for a message. */
    val feePayer: String? get() = message?.feePayer

    /** Every warning this review shows, in a fixed order. */
    val warnings: List<SolanaWarning>
        get() = buildList {
            if (instructions.any { it is SolanaInstruction.Raw }) add(SolanaWarning.UNKNOWN_PROGRAM)
            if (lookups.isNotEmpty()) add(SolanaWarning.LOOKUP_TABLE)
        }
}

/**
 * Checks a sol-sign-request against the Solana accounts of its wallet, and builds the review the owner reads. Nothing here signs, and
 * nothing here reads a key. The caller passes the public keys of the wallet's Solana accounts, in account order.
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
     * account N's key, or when a transaction cannot be read, lists an account twice, or does not take account N as a signer.
     */
    fun check(request: SolSignRequest, accountKeys: List<ByteArray>): Result {
        val index = accountIndexOf(request.derivationPath) ?: return Result.Refused(PATH_REFUSED)
        val signer = accountKeys.getOrNull(index) ?: return Result.Refused(UNKNOWN_ACCOUNT)
        val named = request.address
        if (named != null && !named.contentEquals(signer)) return Result.Refused(DIFFERENT_ADDRESS)
        return when (request.signType) {
            SolSignType.TRANSACTION -> transaction(request.signData, index, signer)
            SolSignType.MESSAGE -> Result.Ok(
                SolanaRequestReview(
                    signType = SolSignType.MESSAGE,
                    accountIndex = index,
                    signer = signer,
                    message = null,
                    instructions = emptyList(),
                    lookups = emptyList(),
                    messageBytes = request.signData,
                ),
            )
        }
    }

    private fun transaction(bytes: ByteArray, index: Int, signer: ByteArray): Result {
        val message = when (val decoded = SolanaMessage.decode(bytes)) {
            is SolanaMessage.Result.Ok -> decoded.message
            is SolanaMessage.Result.Rejected -> return Result.Refused(UNREADABLE_TRANSACTION)
        }
        val keys = message.accountKeys
        if (keys.map { it.toList() }.toSet().size != keys.size) return Result.Refused(DUPLICATE_ACCOUNT)
        if (keys.take(message.header.numRequiredSignatures).none { it.contentEquals(signer) }) return Result.Refused(NOT_A_SIGNER)
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
                instructions = message.decodeInstructions(),
                lookups = lookups,
                messageBytes = null,
            ),
        )
    }
}
