package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignResponse
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.AlgorandAddressCodec
import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.algorand.bytesToSign
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

/**
 * The Lensly side of the Algorand chain app: its manifest, its request parser, its address rules every Algorand side shares, and the signing of
 * an approved request.
 */
object AlgorandSigner : SignerApp {
    override val manifest: ChainManifest = algorandManifest

    override val parser: RequestParser = AlgorandRequestParser

    override val addresses: AddressCodec = AlgorandAddressCodec

    /**
     * Signs the approved transaction of [request] with the Ed25519 key of the path its header names, and answers with the `algo-signature`. The
     * request is read again from its body, and the transaction is checked under the same rules as its review: the key must be the transaction's
     * sender, and the transaction must not rekey, clawback or name another network. The signature covers "TX" followed by the signed bytes.
     */
    override fun sign(request: AppRequest, approval: Approval, keys: KeyAccess): SignResponse {
        val algo = when (val decoded = AlgoSignRequest.decode(request.body)) {
            is AlgoSignRequest.Result.Ok -> decoded.request
            is AlgoSignRequest.Result.Rejected -> throw IllegalArgumentException(decoded.reason)
        }
        require(algo.signType == AlgoSignType.TRANSACTION) { AlgorandRequestCheck.NOT_A_TRANSACTION }
        require(approval.review.chain == request.header.chain) { "The approval is for another chain." }
        val path = Path(request.header.signers.singleOrNull()?.path ?: throw IllegalArgumentException(AlgorandRequestCheck.PATH_REFUSED))
        require(algo.derivationPath == path.steps) { AlgorandRequestCheck.PATH_REFUSED }
        val index = AlgorandRequestCheck.accountIndexOf(path.steps) ?: throw IllegalArgumentException(AlgorandRequestCheck.PATH_REFUSED)
        val account = keys.publicKey(Curve.ED25519_BIP32, path).bytes
        val checked = AlgorandRequestCheck.checkSigner(algo.signData, index, account)
        if (checked is AlgorandRequestCheck.Result.Refused) throw IllegalArgumentException(checked.reason)
        val signature = keys.signEd25519(Curve.ED25519_BIP32, path, bytesToSign(algo.signData))
        return SignResponse(AlgoSignResponse.UR_TYPE, AlgoSignResponse.cbor(algo.requestId, signature, account))
    }
}
