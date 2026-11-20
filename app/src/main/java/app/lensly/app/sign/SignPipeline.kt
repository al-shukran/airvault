package app.lensly.app.sign

import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmReviewBuilder
import app.airvault.chains.evm.signer.EvmReviewResult
import app.airvault.chains.evm.signer.ParsedRequest
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.SummaryPart
import app.airvault.core.model.isPrintableCodePoint
import app.lensly.app.AddressNotes
import app.lensly.app.SigningAccount
import app.lensly.app.autoAccountName
import app.lensly.core.parse.RequestParser
import app.lensly.core.registry.ProxyPolicy
import app.lensly.core.registry.Registry
import app.lensly.core.registry.UnknownCallPolicy
import app.lensly.core.wallet.EvmKey

/** The outcome of [SignPipeline.prepare]: a review for the user to confirm, or a refusal that says why. */
sealed interface Prepared {
    /**
     * The review the owner reads, built by [EvmReviewBuilder] from the request that gets signed. [signer] is the account that signs, and
     * [addressNotes] are the notes of the review's addresses, which the port reads. [key] is the key that signs, which [walletId] holds.
     * Nothing is signed here: the sign flow signs the approved request through the chain app.
     */
    data class Ready(
        val review: Review,
        val signer: SigningAccount,
        val request: EthSignRequest,
        val key: EvmKey,
        val walletId: String,
        val addressNotes: Map<String, AddressNotes> = emptyMap(),
    ) : Prepared {
        /** The account whose own key signs, or null for a BIP44 address key, which belongs to no account of the wallet. */
        val accountIndex: Int? get() = key.accountIndex
    }

    /** [rows] are set when the refusal is for a contract call Lensly cannot decode, so the refusal can show the whole call. */
    data class Refused(val reason: String, val rows: List<ReviewRow> = emptyList()) : Prepared
}

/**
 * Checks a scanned eth-sign-request, as the Parser Sandbox parsed it, against the wallet and the registry, and builds the review the owner
 * confirms. A failed check returns [Prepared.Refused] with a plain reason. Signing is the chain app's, in `EvmSigner`, not this class's.
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
     * Checks the request of [parsed] against [wallet]. A Mnemonic wallet's request names its master fingerprint and the path of its key, which
     * [EvmKey.of] reads: m/44'/60'/N'/0/0 for account N, or m/44'/60'/0'/0/N for a BIP44 address key below the account count. A Single Key
     * wallet has neither, so its request must name the wallet's address, and only that address signs. The derivation path is not read for a
     * Single Key.
     */
    fun prepare(parsed: ParsedRequest, wallet: WalletView): Prepared {
        val request = parsed.request
        // The size check comes before any decoding of the payload.
        if (request.signData.size > EthSignRequest.MAX_SIGN_DATA_BYTES) {
            return Prepared.Refused("The data to sign is larger than 64 KiB")
        }
        // The origin is the wallet's claim, and the review shows it as text. A claim with a character the owner cannot read is refused.
        val origin = request.origin
        if (origin != null && !origin.codePoints().allMatch(::isPrintableCodePoint)) return Prepared.Refused(UNREADABLE_ORIGIN)
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

        // A BIP44 address key is no account, so it takes no account's name. Its review names its own address and path instead.
        val label = when (val account = key.accountIndex) {
            null -> "Address ${key.index}"
            else -> wallet.accountNames[account] ?: autoAccountName(wallet.autoNameBase, account)
        }
        return when (val result = reviews.review(parsed, address, path)) {
            is EvmReviewResult.Ok -> Prepared.Ready(
                review = result.review,
                signer = SigningAccount(name = label, walletName = wallet.name, address = address, isAddressKey = key.accountIndex == null),
                request = request,
                key = key,
                walletId = wallet.id,
            )
            is EvmReviewResult.Refused -> Prepared.Refused(result.reason, result.rows)
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
