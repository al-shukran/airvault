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
import app.lensly.core.ur.PathStep

/** The outcome of [SignPipeline.prepare]: a review for the user to confirm, or a refusal that says why. */
sealed interface Prepared {
    /**
     * The review the owner reads, built by [EvmReviewBuilder] from the request that gets signed. [signer] is the account that signs, and
     * [addressNotes] are the notes of the review's addresses, which the port reads. [accountIndex] is N in m/44'/60'/N'/0/0, and [walletId] is
     * the wallet that holds that account. Nothing is signed until [SignPipeline.sign] runs.
     */
    data class Ready(
        val review: Review,
        val signer: SigningAccount,
        val request: EthSignRequest,
        val accountIndex: Int,
        val walletId: String,
        /** The transaction the Parser Sandbox decoded from the request's data. Null for a request that is not a transaction. */
        val tx: UnsignedTx? = null,
        /** The typed message the Parser Sandbox parsed, which is what gets signed. Null for a request that is not typed data. */
        val typed: TypedData? = null,
        val addressNotes: Map<String, AddressNotes> = emptyMap(),
    ) : Prepared

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
     * Checks the request of [parsed] against [wallet]. A Mnemonic wallet's request names its master fingerprint and an account path,
     * m/44'/60'/N'/0/0 for account N. A Single Key wallet has neither, so its request must name the wallet's address, and only that
     * address signs. The derivation path is not read for a Single Key.
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
        val index: Int
        val address: String
        if (masterFingerprint == null) {
            val requestedAddress = request.address
                ?: return Prepared.Refused("The request does not say which address to sign with")
            index = 0
            address = wallet.addressAt(index)
            if (!sameAddress(requestedAddress, address)) return Prepared.Refused("This request is for a different address")
        } else {
            val sourceFingerprint = request.sourceFingerprint
                ?: return Prepared.Refused("The request does not say which wallet it is for")
            if (sourceFingerprint != masterFingerprint) {
                return Prepared.Refused("This request is for a different wallet")
            }

            val accountIndex = accountIndexOf(request.derivationPath)
            if (accountIndex == null || accountIndex >= wallet.accountCount) return Prepared.Refused("Unknown account")
            index = accountIndex
            address = wallet.addressAt(index)
            val requestedAddress = request.address
            if (requestedAddress != null && !sameAddress(requestedAddress, address)) {
                return Prepared.Refused("This request is for a different address")
            }
        }

        val label = wallet.accountNames[index] ?: autoAccountName(wallet.autoNameBase, index)
        return when (val result = reviews.review(parsed, address)) {
            is EvmReviewResult.Ok -> Prepared.Ready(
                review = result.review,
                signer = SigningAccount(name = label, walletName = wallet.name, address = address),
                request = request,
                accountIndex = index,
                walletId = wallet.id,
                tx = (parsed.tx as? UnsignedTx.Result.Ok)?.tx,
                typed = (parsed.typed as? TypedDataParser.Result.Ok)?.data,
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
                    val chainId = requireNotNull(request.chainId) { "The request does not name a network" }
                    when (val result = TypedDataSigner.sign(request.dataType, typed, chainId, privateKey)) {
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
        const val ACCOUNT_PATH_LENGTH = 5

        /** The first two steps of every Lensly account path, m/44'/60'. The third step is the account, and [ACCOUNT_PATH_SUFFIX] ends it. */
        val ACCOUNT_PATH_PREFIX = listOf(PathStep(44, true), PathStep(60, true))

        /** The last two steps of every Lensly account path, 0/0: the address of the account. */
        val ACCOUNT_PATH_SUFFIX = listOf(PathStep(0, false), PathStep(0, false))

        /**
         * N when [path] is exactly m/44'/60'/N'/0/0, otherwise null. The old form, with the account number as the last step, is not an account path, so a request
         * for it is refused. For N = 0 the two forms are the same key.
         */
        fun accountIndexOf(path: List<PathStep>): Int? {
            if (path.size != ACCOUNT_PATH_LENGTH) return null
            if (path.subList(0, ACCOUNT_PATH_PREFIX.size) != ACCOUNT_PATH_PREFIX) return null
            val account = path[ACCOUNT_PATH_PREFIX.size]
            if (!account.hardened) return null
            return if (path.subList(ACCOUNT_PATH_PREFIX.size + 1, path.size) == ACCOUNT_PATH_SUFFIX) account.index else null
        }
    }
}

/** The addresses a review names, each once: the counterparties of its summary and the address of every row it shows. */
fun evmCounterparties(review: Review): List<String> {
    val rows = review.facts + review.sections.flatMap { it.rows }
    val named = rows.mapNotNull { (it.value as? DisplayValue.Address)?.address }
    val summary = review.summary.filterIsInstance<SummaryPart.Counterparty>().map { it.address }
    return (summary + named).distinct()
}
