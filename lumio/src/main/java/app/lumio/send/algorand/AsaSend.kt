package app.lumio.send.algorand

import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.AlgoAddress
import app.lensly.core.algorand.AlgoSignedTxn
import app.lensly.core.algorand.AlgoTxnEncoder
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.AlgorandSuggestedParams
import java.math.BigInteger

/** The ALGO that an account must keep for each asset it holds beyond the asset it opts in to: 0.1 ALGO, in microAlgos. */
const val ASSET_MIN_BALANCE = 100_000L

/**
 * An asset transfer, or an opt-in (zero units to the signer), that Lumio has built for Lensly to sign. [assetId] is the asset and [txn] its transaction.
 * [txnBytes] are the canonical bytes that Lensly signs as "TX" followed by them. [signer] is the account's public key, [fingerprint] the wallet's master
 * fingerprint, and [accountIndex] the N of m/44'/283'/N'/0/0.
 */
class AsaSendPlan(
    override val network: NetworkId,
    override val signer: ByteArray,
    override val fromAddress: String,
    override val accountIndex: Int,
    override val fingerprint: Long,
    override val toAddress: String,
    val assetId: ULong,
    override val amount: BigInteger,
    override val fee: BigInteger,
    val txn: AssetTransfer,
    override val txnBytes: ByteArray,
) : AlgorandSendPlan {
    /** An opt-in moves no units: it is a zero transfer to the signer, which holds the asset from then on. */
    val isOptIn: Boolean get() = amount.signum() == 0
}

/**
 * An Algorand asset send and an asset opt-in. Lumio builds the `axfer` from public reads, and it holds no key. A transfer is planned only when the
 * recipient has opted in to the asset, since the network refuses it otherwise, and the sender holds the amount. Lensly's signature is checked by
 * [AlgorandSend.signed], as a payment's is.
 */
object AsaSend {
    /** The reason a transfer is refused when the recipient has not opted in to the asset. */
    const val NOT_OPTED_IN = "The recipient has not opted in to this asset, so they cannot receive it yet. Ask them to opt in first."

    /** The reason a transfer is refused when the account holds less of the asset than the amount. */
    const val NOT_HELD = "This account does not hold enough of this asset."

    /** The reason a send or an opt-in is refused when the ALGO cannot pay the fee and the minimum balance that must stay in the account. */
    const val NOT_ENOUGH_ALGO = "This account cannot pay the fee and the minimum balance. Add ALGO to it first."

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
