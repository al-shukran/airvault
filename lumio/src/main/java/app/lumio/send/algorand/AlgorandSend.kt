package app.lumio.send.algorand

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignRequestEncoder
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.Payment
import app.lensly.app.export.partsOf
import app.airvault.chains.algorand.AlgoAddress
import app.lensly.core.algorand.AlgoSignedTxn
import app.lensly.core.algorand.AlgoTxnEncoder
import app.lensly.core.ur.AlgoSignature
import app.lensly.core.ur.PathStep
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AlgorandParams
import app.lumio.data.provider.AlgorandPendingInfo
import app.lumio.data.provider.AlgorandPendingTx
import app.lumio.data.provider.AlgorandSuggestedParams
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.ProviderError
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import com.sparrowwallet.hummingbird.UR
import java.math.BigDecimal
import java.math.BigInteger

/**
 * What the network says before a send: the balance of the sending account, the parameters of the next transaction, and the assets the account has
 * opted in to. Each opted-in asset raises the minimum balance that a send must leave in the account.
 */
class AlgorandFunds(val balance: BigInteger, val params: AlgorandSuggestedParams, val holdings: List<AsaHolding> = emptyList()) {
    /** The minimum balance: 0.1 ALGO for the account, and 0.1 ALGO more for each asset it has opted in to. */
    val minimum: BigInteger get() = BigInteger.valueOf(AlgorandSend.MIN_BALANCE * (1 + holdings.size))
}

/**
 * What Lumio asks Lensly to sign on an Algorand account: an ALGO payment ([AlgorandPaymentPlan]), or an asset transfer or opt-in ([AsaSendPlan]). The
 * review, Lensly's answer and the broadcast take this one type. [txnBytes] are the canonical msgpack that Lensly signs as "TX" and these bytes.
 * [signer] is the account's 32-byte public key, [fingerprint] the wallet's master fingerprint, and [accountIndex] the N of m/44'/283'/N'/0/0.
 */
sealed interface AlgorandSendPlan {
    val network: NetworkId
    val signer: ByteArray
    val fromAddress: String
    val accountIndex: Int
    val fingerprint: Long
    val toAddress: String
    val amount: BigInteger
    val fee: BigInteger
    val txnBytes: ByteArray
}

/** A payment of ALGO from [signer] to [toAddress], whose transaction is [txn]. */
class AlgorandPaymentPlan(
    override val network: NetworkId,
    override val signer: ByteArray,
    override val fromAddress: String,
    override val accountIndex: Int,
    override val fingerprint: Long,
    override val toAddress: String,
    override val amount: BigInteger,
    override val fee: BigInteger,
    val txn: Payment,
    override val txnBytes: ByteArray,
) : AlgorandSendPlan

/**
 * An Algorand payment from one account: the amount rules, the fee, the plan that Lensly signs, the check of the signature that comes back, and the
 * broadcast. Lumio holds no key. It builds the transaction from public reads, and it sends only a signed transaction whose signature checks against
 * the account's public key over "TX" and the transaction bytes.
 */
object AlgorandSend {
    /** The ALGO an account keeps for itself: 0.1 ALGO, in microAlgos. Each opted-in asset keeps the same again (see [AlgorandFunds.minimum]). */
    const val MIN_BALANCE = 100_000L

    /** The signer's origin, which the sign request names: the app that asks. */
    const val ORIGIN = "Lumio"

    /** A transaction is valid for this many rounds from its first valid round, as the suggested parameters give it. */
    internal const val VALID_ROUNDS = 1_000L

    /** The fee is worked out again this many times at most, since the size of the signed transaction depends on the fee. */
    private const val FEE_ROUNDS = 3

    private const val DECIMALS = 6
    private val AMOUNT = Regex("[0-9]{1,12}(\\.[0-9]{1,6})?")

    private const val NOT_A_SIGNATURE = "That code is not a signature from Lensly. Scan the signature that Lensly shows."
    private const val WRONG_REQUEST = "This signature answers a different request. Scan the signature for this send."
    private const val WRONG_ACCOUNT = "This signature is from another account."
    private const val WRONG_SIGNATURE = "This signature does not match this transaction. Scan the signature again."
    private const val WRONG_TXID = "The network reported a different transaction id. Check the explorer before you send again."
    private const val NOT_ENOUGH = "The account cannot pay this amount, the fee and the minimum balance that must stay in it. Lower the amount."

    /** The reason [text] is not an Algorand address, or null when it is one. A valid address has 58 characters and a checksum that matches. */
    fun recipientError(text: String): String? = runCatching { recipientKey(text) }.exceptionOrNull()?.let { "Enter a valid Algorand address." }

    /** The 32-byte public key that the address [text] names. */
    fun recipientKey(text: String): ByteArray = AlgoAddress.decode(text.trim())

    /** The amount in microAlgos that [text] names in ALGO: at most six decimal places, above zero. */
    fun microAlgos(text: String): BigInteger {
        val trimmed = text.trim()
        require(AMOUNT.matches(trimmed)) { "Enter an amount in ALGO, with up to six decimal places." }
        val micro = BigDecimal(trimmed).movePointRight(DECIMALS).toBigIntegerExact()
        require(micro.signum() > 0) { "Enter an amount above zero." }
        return micro
    }

    /** The ALGO text of [micro] as the amount field takes it: the digits, with no trailing zeros. */
    fun algoText(micro: BigInteger): String = BigDecimal(micro).movePointLeft(DECIMALS).stripTrailingZeros().toPlainString()

    /** The most that can be sent from [funds] with [fee]: the balance less the fee and the minimum balance. Zero when nothing can. */
    fun maxMicro(funds: AlgorandFunds, fee: BigInteger): BigInteger =
        (funds.balance - funds.minimum - fee).max(BigInteger.ZERO)

    /** The derivation path of account [index]: m/44'/283'/N'/0/0, the Ledger Algorand path that Lensly signs with. */
    fun derivationPath(index: Int): List<PathStep> = listOf(
        PathStep(44, true),
        PathStep(283, true),
        PathStep(index, true),
        PathStep(0, false),
        PathStep(0, false),
    )

    /** The network's balance of [address], the parameters of the next transaction and the assets it has opted in to, read through [scheduler]. */
    suspend fun funds(scheduler: Scheduler, network: NetworkId, address: String): AlgorandFunds {
        val balance = scheduler.run(NativeBalance(network, address), Priority.FOREGROUND)
        val params = scheduler.run(AlgorandParams(network), Priority.FOREGROUND)
        return AlgorandFunds(balance, params, optedInAssets(scheduler, network, address))
    }

    /**
     * The fee of a payment of [amount] from [signer] to [to] at [funds]: the fee per byte times the size of the signed transaction, and never less
     * than the minimum fee. The size depends on the fee, so the fee is worked out again until it settles.
     */
    fun estimateFee(funds: AlgorandFunds, signer: ByteArray, to: ByteArray, amount: BigInteger): BigInteger =
        feeOf(template(funds.params, signer, to, amount, fee = 0UL), funds.params).toBigInteger()

    /**
     * The plan of a payment of [amount] from account [accountIndex] of the wallet with [fingerprint] to [to], on [network], at [funds]. Throws
     * IllegalArgumentException with the owner's words when the account cannot pay the amount, the fee and the minimum balance.
     */
    fun plan(
        network: NetworkId,
        funds: AlgorandFunds,
        fingerprint: Long,
        accountIndex: Int,
        signer: ByteArray,
        to: ByteArray,
        amount: BigInteger,
    ): AlgorandPaymentPlan {
        require(amount.signum() > 0 && amount.bitLength() < Long.SIZE_BITS) { "Enter an amount above zero." }
        val fee = feeOf(template(funds.params, signer, to, amount, fee = 0UL), funds.params)
        val txn = template(funds.params, signer, to, amount, fee = fee)
        val total = amount + fee.toBigInteger()
        require(total <= funds.balance - funds.minimum) { NOT_ENOUGH }
        return AlgorandPaymentPlan(
            network = network,
            signer = signer,
            fromAddress = AlgoAddress.encode(signer),
            accountIndex = accountIndex,
            fingerprint = fingerprint,
            toAddress = AlgoAddress.encode(to),
            amount = amount,
            fee = fee.toBigInteger(),
            txn = txn,
            txnBytes = AlgoTxnEncoder.encodePayment(txn),
        )
    }

    /** The animated frames of the sign request for [plan], under the 16-byte [requestId]: the transaction, for Lensly to review and sign. */
    fun requestParts(plan: AlgorandSendPlan, requestId: ByteArray): List<String> = partsOf(
        AlgoSignRequestEncoder.encode(
            AlgoSignRequest(
                requestId = requestId,
                signData = plan.txnBytes,
                derivationPath = derivationPath(plan.accountIndex),
                sourceFingerprint = plan.fingerprint.toInt(),
                address = plan.fromAddress,
                origin = ORIGIN,
                signType = AlgoSignType.TRANSACTION,
            ),
        ),
    )

    /**
     * The signed transaction for [plan], from the signature in [ur] that Lensly made for [requestId]. The signature is checked against the account's
     * public key over "TX" and the transaction bytes before anything is sent: a signature for another request, another account, or another
     * transaction is refused, with the reason.
     */
    fun signed(plan: AlgorandSendPlan, requestId: ByteArray, ur: UR): ByteArray {
        val answer: AlgoSignature = when (val result = AlgoSignature.decode(ur)) {
            is AlgoSignature.Result.Ok -> result.signature
            is AlgoSignature.Result.Rejected -> throw IllegalArgumentException(NOT_A_SIGNATURE)
        }
        require(answer.requestId.contentEquals(requestId)) { WRONG_REQUEST }
        answer.publicKey?.let { key -> require(key.contentEquals(plan.signer)) { WRONG_ACCOUNT } }
        require(AlgoSignedTxn.verify(plan.signer, plan.txnBytes, answer.signature)) { WRONG_SIGNATURE }
        return AlgoSignedTxn.assemble(plan.txnBytes, answer.signature)
    }

    /**
     * Sends [signed] on the plan's network and returns the transaction ID. The network must report the ID of the plan's transaction, so the owner is
     * never sent to a transaction that Lumio did not sign.
     */
    suspend fun broadcast(scheduler: Scheduler, plan: AlgorandSendPlan, signed: ByteArray): String {
        val expected = AlgoSignedTxn.txid(plan.txnBytes)
        val reported = scheduler.run(Broadcast(plan.network, signed), Priority.FOREGROUND)
        require(reported == expected) { WRONG_TXID }
        return reported
    }

    /** The pool's state of [txid] on [network], or null when the pool does not hold it: the transaction is confirmed, or it was dropped. */
    suspend fun pendingState(scheduler: Scheduler, network: NetworkId, txid: String): AlgorandPendingInfo? = try {
        scheduler.run(AlgorandPendingTx(network, txid), Priority.VISIBLE)
    } catch (e: ProviderError.NotFound) {
        null
    }

    /** The page on Lora, the explorer that Algorand testnet and mainnet both use, for the transaction [txid] on [network]. */
    fun explorerUrl(network: NetworkId, txid: String): String =
        "https://lora.algokit.io/${if (network.testnet) "testnet" else "mainnet"}/transaction/$txid"

    /** The words a failure shows: the owner's words for a refusal, else [fallback]. A failed network read has words of its own. */
    fun ownerWords(error: Throwable, fallback: String): String = when (error) {
        is IllegalArgumentException -> error.message ?: fallback
        is ProviderError.Fatal -> error.message ?: fallback
        is ProviderError -> NETWORK_FAILED
        else -> fallback
    }

    /** The network could not be read: the owner tries again. */
    private const val NETWORK_FAILED = "The Algorand network could not be read. Try again."

    private fun template(params: AlgorandSuggestedParams, signer: ByteArray, to: ByteArray, amount: BigInteger, fee: ULong): Payment {
        val first = params.lastRound.toULong()
        return Payment(
            sender = signer,
            fee = fee,
            firstValid = first,
            lastValid = first + VALID_ROUNDS.toULong(),
            genesisId = params.genesisId,
            genesisHash = params.genesisHash,
            note = ByteArray(0),
            lease = null,
            group = null,
            rekeyTo = null,
            receiver = to,
            amount = amount.toLong().toULong(),
            closeTo = null,
        )
    }

    private fun feeOf(txn: Payment, params: AlgorandSuggestedParams): ULong {
        val minimum = params.minFee.toULong()
        var fee = minimum
        repeat(FEE_ROUNDS) {
            val size = AlgoSignedTxn.assemble(AlgoTxnEncoder.encodePayment(txn.copy(fee = fee)), ByteArray(AlgoSignedTxn.SIGNATURE_BYTES)).size
            val next = maxOf(minimum, params.feePerByte.toULong() * size.toULong())
            if (next == fee) return fee
            fee = next
        }
        return fee
    }

    private fun ULong.toBigInteger(): BigInteger = BigInteger(toString())
}
