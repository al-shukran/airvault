package app.lensly.app.sign

import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmReviewBuilder
import app.airvault.chains.evm.signer.EvmReviewResult
import app.airvault.chains.evm.signer.ParsedRequest
import app.airvault.chains.evm.signer.TypedData
import app.airvault.chains.evm.signer.TypedDataParser
import app.airvault.chains.evm.signer.UndecodedCall
import app.airvault.chains.evm.signer.UnsignedTx
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.isPrintable
import app.lensly.app.AddressNotes
import app.lensly.app.SigningAccount
import app.lensly.app.autoAccountName
import app.lensly.app.export.partsOf
import app.lensly.core.evm.MessageSigner
import app.lensly.core.evm.TxSigner
import app.lensly.core.evm.TypedDataSigner
import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.parse.RequestParser
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.Registry
import app.lensly.core.registry.UnknownCallPolicy
import app.lensly.core.ur.EthSignatureEncoder

/** The outcome of [SignPipeline.prepare]: a review for the user to confirm, or a refusal that says why. */
sealed interface Prepared {
    /**
     * The review the owner reads, built by [EvmReviewBuilder] from the request that gets signed. [signer] is the account that signs, and
     * [addressNotes] are the notes of the review's addresses, which the port reads. [key] is the key that signs, and [walletId] is the
     * wallet that holds it. Nothing is signed until [SignPipeline.sign] runs.
     */
    data class Ready(
        val review: Review,
        val signer: SigningAccount,
        val request: EthSignRequest,
        val key: EvmKey,
        val walletId: String,
        /** The transaction the Parser Sandbox decoded from the request's data. Null for a request that is not a transaction. */
        val tx: UnsignedTx? = null,
        /** The typed message the Parser Sandbox parsed, which is what gets signed. Null for a request that is not typed data. */
        val typed: TypedData? = null,
        /** The domain separator of [typed] as the review showed it. Signing refuses a message whose domain is not this one. */
        val typedDomainSeparator: ByteArray? = null,
        val addressNotes: Map<String, AddressNotes> = emptyMap(),
    ) : Prepared {
        /** The account the review names, which is the account of [key]. */
        val accountIndex: Int get() = key.accountIndex
    }

    /** [call] is set when the refusal is for a contract call Lensly cannot decode, so the refusal can show it. */
    data class Refused(val reason: String, val call: UndecodedCall? = null) : Prepared
}

/**
 * Checks a scanned eth-sign-request, as the Parser Sandbox parsed it, against the wallet and the registry, and signs only after the owner
 * confirms the review. A failed check returns [Prepared.Refused] with a plain reason and nothing is signed.
 *
 * The review is built by [EvmReviewBuilder]. It refuses a BLOCK denylist match and a call it cannot decode, unless the signed whitelist
 * approves the contract and selector or [unknownCalls] is WARN. A chain outside the registry is not refused: its review shows it as unknown,
 * with a warning. [proxyPolicy] decides what a whitelisted proxy gets. [unknownCalls] is REFUSE unless the caller passes the owner's setting,
 * so a caller that does not read the setting keeps the refusal.
 *
 * The pipeline parses nothing itself. The request arrives parsed, and [parser] decodes the calldata of a whitelisted call.
 */
class SignPipeline(
    private val registry: Registry,
    private val parser: RequestParser,
    private val proxyPolicy: ProxyPolicy = ProxyPolicy.DEFAULT,
    private val unknownCalls: UnknownCallPolicy = UnknownCallPolicy.REFUSE,
) {
    private val reviews = EvmReviewBuilder(registry, parser::decodeCall, proxyPolicy, unknownCalls)

    /**
     * Checks the request of [parsed] against [wallet]. A Mnemonic wallet's request names its master fingerprint and the path of its key,
     * which [EvmKey.of] reads. A Single Key wallet has no path, so its request must name the wallet's address, and only that address
     * signs. The derivation path is not read for a Single Key.
     */
    fun prepare(parsed: ParsedRequest, wallet: WalletView): Prepared {
        val request = parsed.request
        // The size check comes before any decoding of the payload.
        if (request.signData.size > EthSignRequest.MAX_SIGN_DATA_BYTES) {
            return Prepared.Refused("The data to sign is larger than 64 KiB")
        }
        // The origin is the wallet's claim, and the review shows it as text. A claim with a character the owner cannot read is refused.
        val origin = request.origin
        if (origin != null && !origin.all(::isPrintable)) return Prepared.Refused(UNREADABLE_ORIGIN)
        val masterFingerprint = wallet.masterFingerprint
        val key: EvmKey
        val address: String
        val path: String?
        if (masterFingerprint == null) {
            val requestedAddress = request.address
                ?: return Prepared.Refused("The request does not say which address to sign with")
            key = EvmKey.SINGLE_KEY
            address = wallet.addressAt(key.account, key.index)
            if (!sameAddress(requestedAddress, address)) return Prepared.Refused("This request is for a different address")
            path = null
        } else {
            val sourceFingerprint = request.sourceFingerprint
                ?: return Prepared.Refused("The request does not say which wallet it is for")
            if (sourceFingerprint != masterFingerprint) {
                return Prepared.Refused("This request is for a different wallet")
            }

            key = EvmKey.of(request.derivationPath, wallet.accountCount) ?: return Prepared.Refused("Unknown account")
            address = wallet.addressAt(key.account, key.index)
            val requestedAddress = request.address
            if (requestedAddress != null && !sameAddress(requestedAddress, address)) {
                return Prepared.Refused("This request is for a different address")
            }
            path = key.path
        }

        val label = wallet.accountNames[key.accountIndex] ?: autoAccountName(wallet.autoNameBase, key.accountIndex)
        val typed = (parsed.typed as? TypedDataParser.Result.Ok)?.data
        return when (val result = reviews.review(parsed, address, path)) {
            is EvmReviewResult.Ok -> Prepared.Ready(
                review = result.review,
                signer = SigningAccount(name = label, walletName = wallet.name, address = address),
                request = request,
                key = key,
                walletId = wallet.id,
                tx = (parsed.tx as? UnsignedTx.Result.Ok)?.tx,
                typed = typed,
                typedDomainSeparator = typed?.domainSeparator(),
            )
            is EvmReviewResult.Refused -> Prepared.Refused(result.reason, result.call)
        }
    }

    /**
     * Signs the reviewed [ready] with [privateKey] and returns the ur:eth-signature parts that answer its request.
     * Takes ownership of [privateKey] and zeroes it before returning, on success and on refusal alike. Throws
     * [IllegalArgumentException] when the request has no requestId to echo or the signer refuses the data.
     */
    fun sign(ready: Prepared.Ready, privateKey: ByteArray): List<String> {
        try {
            val request = ready.request
            val requestId = requireNotNull(request.requestId) { "The request has no requestId to echo" }
            val signature = when (request.dataType) {
                DataType.TRANSACTION, DataType.TYPED_TRANSACTION ->
                    TxSigner.sign(request.signData, requireNotNull(ready.tx) { CANNOT_BE_READ }, privateKey)
                DataType.PERSONAL_MESSAGE ->
                    when (val result = MessageSigner.sign(request.dataType, request.signData, privateKey)) {
                        is MessageSigner.Result.Signed -> result.signature
                        is MessageSigner.Result.Refused -> throw IllegalArgumentException(result.reason)
                    }
                DataType.TYPED_DATA -> {
                    val typed = requireNotNull(ready.typed) { CANNOT_BE_READ }
                    val reviewed = requireNotNull(ready.typedDomainSeparator) { CANNOT_BE_READ }
                    when (val result = TypedDataSigner.sign(request.dataType, typed, reviewed, privateKey)) {
                        is TypedDataSigner.Result.Signed -> result.signature
                        is TypedDataSigner.Result.Refused -> throw IllegalArgumentException(result.reason)
                    }
                }
            }
            return partsOf(EthSignatureEncoder.encode(requestId, signature))
        } finally {
            privateKey.fill(0)
        }
    }

    private companion object {
        const val UNREADABLE_ORIGIN = "The request names an origin with a character Lensly cannot show."
    }
}

/** The addresses a review names, each once: the counterparties of its summary and the address of every row it shows. */
fun evmCounterparties(review: Review): List<String> {
    val rows = review.facts + review.sections.flatMap { it.rows }
    val named = rows.mapNotNull { (it.value as? DisplayValue.Address)?.address }
    val summary = review.summary.filterIsInstance<SummaryPart.Counterparty>().map { it.address }
    return (summary + named).distinct()
}
