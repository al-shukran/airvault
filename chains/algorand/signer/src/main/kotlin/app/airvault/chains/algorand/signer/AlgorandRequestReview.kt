package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.AlgoAddress
import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.AlgoTxn
import app.airvault.chains.algorand.AlgorandNetwork
import app.airvault.chains.algorand.AssetTransfer
import app.airvault.chains.algorand.Payment
import app.lensly.core.ur.PathStep

/** A warning the review shows beside an Algorand request. The owner reads each one before the request can be signed. */
enum class AlgorandWarning {
    /** The transaction closes the account or the asset holding, and sends what is left to a close-to address. */
    CLOSE_TO,

    /**
     * The transfer is of an asset the catalog does not name. Lensly does not know its name or decimals, so it shows the amount in base
     * units.
     */
    UNKNOWN_ASSET,

    /** The transaction is part of a group. Lensly shows only this transaction, not the others in the group. */
    GROUP,

    /** The transaction carries a lease, which holds the sender's transaction with that lease until its last valid round. */
    LEASE,
}

/**
 * An Algorand request as Lensly reviews it before it signs. [accountIndex] is N of the account `m/44'/283'/N'/0/0` that signs, and
 * [signer] is its 32-byte public key, which is also the key of the sender. [txn] is the payment or asset transfer decoded from [signData],
 * the canonical msgpack the owner reviewed. The signature covers "TX" followed by [signData]. [requestId] is the request's 16-byte ID, or
 * null, to echo in the signature. [asset] is the asset an asset transfer moves, when the catalog names it on [network]. It is null for a
 * payment and for an asset the catalog does not name. [origin] is the origin the wallet names, which the review shows as the wallet's
 * claim, or null when the request names none.
 */
class AlgorandRequestReview internal constructor(
    val accountIndex: Int,
    val signer: ByteArray,
    val txn: AlgoTxn,
    val network: AlgorandNetwork,
    val signData: ByteArray,
    val requestId: ByteArray?,
    val asset: Asa? = null,
    val origin: String? = null,
) {
    /** The address of the signing account: the 58 base32 characters of [signer]. */
    val signerAddress: String get() = AlgoAddress.encode(signer)

    /** The close-to address, which takes what is left of the sender's balance or holding. Null when the transaction does not close it. */
    val closeTo: ByteArray?
        get() = when (val t = txn) {
            is Payment -> t.closeTo
            is AssetTransfer -> t.closeTo
        }

    /** Every warning this review shows, in a fixed order. */
    val warnings: List<AlgorandWarning>
        get() = buildList {
            if (closeTo != null) add(AlgorandWarning.CLOSE_TO)
            if (txn is AssetTransfer && asset == null) add(AlgorandWarning.UNKNOWN_ASSET)
            if (txn.group != null) add(AlgorandWarning.GROUP)
            if (txn.lease != null) add(AlgorandWarning.LEASE)
        }
}

/**
 * Checks an Algorand signing request against the Algorand accounts of its wallet, and builds the review the owner reads. Nothing here
 * signs, and nothing here reads a key. The caller passes the public keys of the wallet's Algorand accounts, in account order.
 */
object AlgorandRequestCheck {
    /** The outcome of [check]: a review to show, or the reason the request is refused. Nothing is signed for a refusal. */
    sealed interface Result {
        class Ok(val review: AlgorandRequestReview) : Result

        class Refused(val reason: String) : Result
    }

    /** The reason for a path that is not m/44'/283'/N'/0/0. */
    const val PATH_REFUSED = "The derivation path is not m/44'/283'/N'/0/0."

    /** The reason for an account N that the wallet does not have. */
    const val UNKNOWN_ACCOUNT = "This wallet has no such Algorand account."

    /** The reason for bytes that are not a transaction Lensly can read, when the decoder gives no reason of its own. */
    const val UNREADABLE_TRANSACTION = "The transaction could not be read."

    /** The reason for a sender that is not the account the request signs with. */
    const val NOT_THIS_SENDER = "The sender is not the account this request signs with."

    /** The reason for a transaction that rekeys the sender. */
    const val REKEY_REFUSED = "This transaction would give another address control of the account (rekey). Lensly refuses to sign it."

    /** The reason for an asset transfer that takes assets from another account. */
    const val CLAWBACK_REFUSED = "This asset transfer takes assets from another account (clawback). Lensly refuses to sign it."

    /** The reason for a genesis ID other than the testnet and mainnet ones. */
    const val UNKNOWN_NETWORK = "This transaction is for a network Lensly does not sign for. Lensly signs for Algorand testnet and mainnet."

    /** The reason for a genesis hash that is not the one its genesis ID names. Such a transaction is for another network. */
    const val GENESIS_HASH_MISMATCH = "The genesis hash does not match the network. Lensly refuses to sign it."

    /** The reason for a request to sign a message. Lensly signs only transactions. */
    const val NOT_A_TRANSACTION = "Lensly signs Algorand transactions only. This request is a message."

    /** The reason for an address the request names that is not the signing account's. */
    const val DIFFERENT_ADDRESS = "This request is for a different address."

    private const val PURPOSE = 44
    private const val ALGORAND_COIN_TYPE = 283

    /** The account number N of an Algorand derivation path, m/44'/283'/N'/0/0, or null for any other path. */
    fun accountIndexOf(path: List<PathStep>): Int? {
        if (path.size != 5) return null
        val (purpose, coin, account, change, address) = path
        if (purpose != PathStep(PURPOSE, true) || coin != PathStep(ALGORAND_COIN_TYPE, true)) return null
        if (!account.hardened || change != PathStep(0, false) || address != PathStep(0, false)) return null
        return account.index
    }

    /**
     * Checks a request to sign [signData] with the account at [derivationPath], for the request [requestId]. [signData] is the canonical
     * msgpack of the transaction without the "TX" tag: Lensly adds the tag when it signs, as the Ledger app does. A request is refused when
     * its path is not m/44'/283'/N', when the wallet has no account N, when [signData] is not a payment or an asset transfer that Lensly
     * can read, when it rekeys the sender, when the sender is not account N's key, when it is a clawback, when its genesis ID is not the
     * testnet or the mainnet one, or when its genesis hash is not that network's. [assets] only names the asset of an asset transfer for the
     * review: an asset it does not name is warned about, not refused. [origin] is the origin the request names, shown as a claim.
     */
    fun check(
        signData: ByteArray,
        derivationPath: List<PathStep>,
        requestId: ByteArray?,
        accountKeys: List<ByteArray>,
        assets: AsaCatalog = AsaCatalog.EMPTY,
        origin: String? = null,
    ): Result {
        val index = accountIndexOf(derivationPath) ?: return Result.Refused(PATH_REFUSED)
        val signer = accountKeys.getOrNull(index) ?: return Result.Refused(UNKNOWN_ACCOUNT)
        val txn = try {
            AlgoTxnDecoder.decode(signData)
        } catch (e: AlgoTxnException) {
            return Result.Refused(e.message ?: UNREADABLE_TRANSACTION)
        }
        if (txn.rekeyTo != null) return Result.Refused(REKEY_REFUSED)
        if (!txn.sender.contentEquals(signer)) return Result.Refused(NOT_THIS_SENDER)
        if (txn is AssetTransfer && txn.assetSender != null) return Result.Refused(CLAWBACK_REFUSED)
        val network = AlgorandNetwork.ofGenesisId(txn.genesisId) ?: return Result.Refused(UNKNOWN_NETWORK)
        if (!txn.genesisHash.contentEquals(network.genesisHash)) return Result.Refused(GENESIS_HASH_MISMATCH)
        val asset = (txn as? AssetTransfer)?.let { assets.lookup(network, it.assetId) }
        return Result.Ok(AlgorandRequestReview(index, signer, txn, network, signData, requestId, asset, origin))
    }

    /**
     * Checks a decoded [request] against [accountKeys], as [check] does for its signData, path and request ID. It also refuses a message,
     * and an address the request names that is not the signing account's. An empty address names none.
     */
    fun check(request: AlgoSignRequest, accountKeys: List<ByteArray>, assets: AsaCatalog = AsaCatalog.EMPTY): Result {
        if (request.signType != AlgoSignType.TRANSACTION) return Result.Refused(NOT_A_TRANSACTION)
        val checked = check(request.signData, request.derivationPath, request.requestId, accountKeys, assets, request.origin)
        if (checked is Result.Ok && request.address.isNotEmpty() && request.address != checked.review.signerAddress) {
            return Result.Refused(DIFFERENT_ADDRESS)
        }
        return checked
    }
}
