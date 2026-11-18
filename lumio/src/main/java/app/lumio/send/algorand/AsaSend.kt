package app.lumio.send.algorand

import app.lensly.app.export.partsOf
import app.lensly.core.algorand.AlgoAddress
import app.lensly.core.algorand.AlgoSignedTxn
import app.lensly.core.algorand.AlgoTxnEncoder
import app.lensly.core.algorand.AssetTransfer
import app.lensly.core.ur.AlgoSignRequest
import app.lensly.core.ur.AlgoSignRequestEncoder
import app.lensly.core.ur.AlgoSignType
import app.lensly.core.ur.AlgoSignature
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AlgorandSuggestedParams
import app.lumio.data.provider.Broadcast
import app.lumio.data.sched.Priority
import app.lumio.data.sched.Scheduler
import com.sparrowwallet.hummingbird.UR
import java.math.BigInteger

/** The ALGO that an account must keep for each asset it holds beyond the asset it opts in to: 0.1 ALGO, in microAlgos. */
const val ASSET_MIN_BALANCE = 100_000L

/**
 * An asset transfer, or an opt-in (zero units to the signer), that Lumio has built for Lensly to sign. [txnBytes] are the canonical bytes that Lensly
 * signs as "TX" followed by them. [signer] is the account's public key, [fingerprint] the wallet's master fingerprint, and [accountIndex] the N of
 * m/44'/283'/N'/0/0.
 */
class AsaSendPlan(
    val network: NetworkId,
    val signer: ByteArray,
    val fromAddress: String,
    val accountIndex: Int,
    val fingerprint: Long,
    val toAddress: String,
    val assetId: ULong,
    val amount: BigInteger,
    val fee: BigInteger,
    val txn: AssetTransfer,
    val txnBytes: ByteArray,
)

/**
 * An Algorand asset send and an asset opt-in. Lumio builds the `axfer` from public reads, and it holds no key. A transfer is planned only when the
 * recipient has opted in to the asset, since the network refuses it otherwise, and the sender holds the amount. Lensly's signature is checked against
 * the signer's key, as a payment's is.
 */
object AsaSend {
    /** The reason a transfer is refused when the recipient has not opted in to the asset. */
    const val NOT_OPTED_IN = "The recipient has not opted in to this asset, so they cannot receive it yet. Ask them to opt in first."

    /** The reason a transfer is refused when the account holds less of the asset than the amount. */
    const val NOT_HELD = "This account does not hold enough of this asset."

    /** The reason a send or an opt-in is refused when the ALGO cannot pay the fee and the minimum balance that must stay in the account. */
    const val NOT_ENOUGH_ALGO = "This account cannot pay the fee and the minimum balance. Add ALGO to it first."

    private const val NOT_A_SIGNATURE = "That code is not a signature from Lensly. Scan the signature that Lensly shows."
    private const val WRONG_REQUEST = "This signature answers a different request. Scan the signature for this send."
    private const val WRONG_ACCOUNT = "This signature is from another account."
    private const val WRONG_SIGNATURE = "This signature does not match this transaction. Scan the signature again."
    private const val WRONG_TXID = "The network reported a different transaction id. Check the explorer before you send again."

    /** The fee is worked out again this many times at most, since the size of the signed transaction depends on the fee. */
    private const val FEE_ROUNDS = 3

    /**
     * The transfer of [amount] units of [assetId] from [signer] to [to], planned at [funds], when the sender holds [held] units and the recipient
     * [recipientOptedIn]. Throws IllegalArgumentException with the owner's words for an amount that is not positive, a holding that is too small, a
     * recipient that has not opted in, or an ALGO balance that cannot pay the fee and keep the minimum balance.
     */
    fun plan(
        network: NetworkId,
        funds: AlgorandFunds,
        fingerprint: Long,
        accountIndex: Int,
        signer: ByteArray,
        to: ByteArray,
        assetId: ULong,
        amount: BigInteger,
        held: BigInteger,
        recipientOptedIn: Boolean,
    ): AsaSendPlan {
        require(amount.signum() > 0 && amount.bitLength() < Long.SIZE_BITS) { "Enter an amount above zero." }
        require(held >= amount) { NOT_HELD }
        require(recipientOptedIn) { NOT_OPTED_IN }
        return build(network, funds, fingerprint, accountIndex, signer, to, assetId, amount, keep = AlgorandSend.MIN_BALANCE)
    }

    /**
     * The opt-in of [signer] to [assetId]: zero units from the signer to the signer. Holding a new asset raises the minimum balance by
     * [ASSET_MIN_BALANCE], so the ALGO balance must cover that as well as the fee.
     */
    fun optIn(network: NetworkId, funds: AlgorandFunds, fingerprint: Long, accountIndex: Int, signer: ByteArray, assetId: ULong): AsaSendPlan =
        build(network, funds, fingerprint, accountIndex, signer, signer, assetId, BigInteger.ZERO, keep = AlgorandSend.MIN_BALANCE + ASSET_MIN_BALANCE)

    private fun build(
        network: NetworkId,
        funds: AlgorandFunds,
        fingerprint: Long,
        accountIndex: Int,
        signer: ByteArray,
        to: ByteArray,
        assetId: ULong,
        amount: BigInteger,
        keep: Long,
    ): AsaSendPlan {
        require(assetId > 0UL) { "An asset ID is more than 0." }
        val fee = feeOf(template(funds.params, signer, to, assetId, amount, fee = 0UL), funds.params)
        val txn = template(funds.params, signer, to, assetId, amount, fee)
        require(funds.balance >= fee.toBigInteger() + BigInteger.valueOf(keep)) { NOT_ENOUGH_ALGO }
        return AsaSendPlan(
            network = network,
            signer = signer,
            fromAddress = AlgoAddress.encode(signer),
            accountIndex = accountIndex,
            fingerprint = fingerprint,
            toAddress = AlgoAddress.encode(to),
            assetId = assetId,
            amount = amount,
            fee = fee.toBigInteger(),
            txn = txn,
            txnBytes = AlgoTxnEncoder.encodeAssetTransfer(txn),
        )
    }

    /** The sign request that asks Lensly to sign [plan], with [requestId] so that its answer can be matched to this request. */
    fun requestParts(plan: AsaSendPlan, requestId: ByteArray): List<String> = partsOf(
        AlgoSignRequestEncoder.encode(
            AlgoSignRequest(
                requestId = requestId,
                signData = plan.txnBytes,
                derivationPath = AlgorandSend.derivationPath(plan.accountIndex),
                sourceFingerprint = plan.fingerprint.toInt(),
                address = plan.fromAddress,
                origin = AlgorandSend.ORIGIN,
                signType = AlgoSignType.TRANSACTION,
            ),
        ),
    )

    /**
     * The signed transaction for [plan], once Lensly's [ur] answers [requestId] with a signature over "TX" and the plan's bytes that checks against
     * the signer's key. Throws IllegalArgumentException with the owner's words for any other answer.
     */
    fun signed(plan: AsaSendPlan, requestId: ByteArray, ur: UR): ByteArray {
        val answer = when (val result = AlgoSignature.decode(ur)) {
            is AlgoSignature.Result.Ok -> result.signature
            is AlgoSignature.Result.Rejected -> throw IllegalArgumentException(NOT_A_SIGNATURE)
        }
        require(answer.requestId.contentEquals(requestId)) { WRONG_REQUEST }
        answer.publicKey?.let { key -> require(key.contentEquals(plan.signer)) { WRONG_ACCOUNT } }
        require(AlgoSignedTxn.verify(plan.signer, plan.txnBytes, answer.signature)) { WRONG_SIGNATURE }
        return AlgoSignedTxn.assemble(plan.txnBytes, answer.signature)
    }

    /** Sends [signed] on the plan's network and returns the transaction ID, which must be the ID of the plan's transaction. */
    suspend fun broadcast(scheduler: Scheduler, plan: AsaSendPlan, signed: ByteArray): String {
        val expected = AlgoSignedTxn.txid(plan.txnBytes)
        val reported = scheduler.run(Broadcast(plan.network, signed), Priority.FOREGROUND)
        require(reported == expected) { WRONG_TXID }
        return reported
    }

    private fun template(
        params: AlgorandSuggestedParams,
        signer: ByteArray,
        to: ByteArray,
        assetId: ULong,
        amount: BigInteger,
        fee: ULong,
    ): AssetTransfer {
        val first = params.lastRound.toULong()
        return AssetTransfer(
            sender = signer,
            fee = fee,
            firstValid = first,
            lastValid = first + AlgorandSend.VALID_ROUNDS.toULong(),
            genesisId = params.genesisId,
            genesisHash = params.genesisHash,
            note = ByteArray(0),
            lease = null,
            group = null,
            rekeyTo = null,
            assetId = assetId,
            amount = amount.toLong().toULong(),
            receiver = to,
            closeTo = null,
            assetSender = null,
        )
    }

    /** The fee of [txn]: the fee per byte times the size of the signed transaction, never less than the minimum fee, worked out again as the size moves. */
    private fun feeOf(txn: AssetTransfer, params: AlgorandSuggestedParams): ULong {
        val minimum = params.minFee.toULong()
        var fee = minimum
        repeat(FEE_ROUNDS) {
            val size = AlgoSignedTxn.assemble(AlgoTxnEncoder.encodeAssetTransfer(txn.copy(fee = fee)), ByteArray(AlgoSignedTxn.SIGNATURE_BYTES)).size
            val next = maxOf(minimum, params.feePerByte.toULong() * size.toULong())
            if (next == fee) return fee
            fee = next
        }
        return fee
    }

    private fun ULong.toBigInteger(): BigInteger = BigInteger(toString())
}
