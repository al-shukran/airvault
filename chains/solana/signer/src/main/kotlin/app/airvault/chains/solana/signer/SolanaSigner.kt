package app.airvault.chains.solana.signer

import app.airvault.chains.solana.SolanaAddressCodec
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.Curve
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignResponse
import app.airvault.core.signer.SignerApp

/** The reason for a request that names no requestId, which the response must echo. */
private const val NO_REQUEST_ID = "The request has no requestId to echo."

/**
 * The Lensly side of the Solana chain app: its manifest, its request parser, its address codec, and the signing of an approved request.
 */
object SolanaSigner : SignerApp {
    override val manifest: ChainManifest = solanaManifest

    override val parser: RequestParser = SolanaRequestParser

    override val addresses: AddressCodec = SolanaAddressCodec

    /**
     * Signs the sign data of [request] with the Ed25519 key of the path its header names, and answers with the `sol-signature`. The request is
     * read again from its body, and the key must be the account the request names, checked under the same rules as the review. The signature
     * covers the sign data as it is, which is the transaction message or the off-chain message.
     */
    override fun sign(request: AppRequest, approval: Approval, keys: KeyAccess): SignResponse {
        val sol = when (val decoded = SolSignRequest.decode(request.body)) {
            is SolSignRequest.Result.Ok -> decoded.request
            is SolSignRequest.Result.Rejected -> throw IllegalArgumentException(decoded.reason)
        }
        require(approval.review.chain == request.header.chain) { "The approval is for another chain." }
        val requestId = requireNotNull(sol.requestId) { NO_REQUEST_ID }
        val path = Path(request.header.signers.singleOrNull()?.path ?: throw IllegalArgumentException(SolanaRequestCheck.PATH_REFUSED))
        require(sol.derivationPath == path.steps) { SolanaRequestCheck.PATH_REFUSED }
        val index = SolanaRequestCheck.accountIndexOf(path.steps) ?: throw IllegalArgumentException(SolanaRequestCheck.PATH_REFUSED)
        val key = keys.publicKey(Curve.ED25519_SLIP10, path).bytes
        val checked = SolanaRequestCheck.checkSigner(sol, index, key)
        if (checked is SolanaRequestCheck.Result.Refused) throw IllegalArgumentException(checked.reason)
        val signature = keys.signEd25519(Curve.ED25519_SLIP10, path, sol.signData)
        return SignResponse(SolSignResponse.UR_TYPE, SolSignResponse.cbor(requestId, signature))
    }
}
