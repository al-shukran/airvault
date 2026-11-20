package app.airvault.chains.evm.signer

import app.airvault.chains.evm.EVM_COIN_TYPE
import app.airvault.chains.evm.EvmAddressCodec
import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.evm.evmManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.AddressKind
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.Curve
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.Review
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.EcdsaSignature
import app.airvault.core.signer.KeyAccess
import app.airvault.core.signer.Path
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignResponse
import app.airvault.core.signer.SignerApp
import app.lensly.core.evm.MessageSigner
import app.lensly.core.evm.TxSigner
import app.lensly.core.evm.TypedDataSigner
import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.ur.PathStep
import org.web3j.crypto.Sign

/** The reason for a request that names no requestId, which the response must echo. */
private const val NO_REQUEST_ID = "The request has no requestId to echo."

/** The reason for an approved review that names no sender, which the signing key must match. */
private const val NO_SENDER = "The approved review names no sender."

/** The reason for a key that is not the sender the review shows, so nothing is signed for the request. */
private const val SENDER_MISMATCH = "The key at the request's path is not the sender the review shows."

/**
 * The Lensly (signer) side of the EVM chain app: its manifest, its request parser, its address codec, and the signing of an approved request.
 */
object EvmSigner : SignerApp {
    override val manifest: ChainManifest = evmManifest

    override val parser: RequestParser = EvmRequestParser

    override val addresses: AddressCodec = EvmAddressCodec

    /**
     * The path of the one account a Single Key wallet holds: m/44'/60'/0'/0/0, the address of account 0. A request that names no path is signed
     * with this key, since a Single Key request names none. The approved sender still has to be the address of this key.
     */
    val singleKeyPath: Path = Path(
        listOf(
            PathStep(44, hardened = true),
            PathStep(EVM_COIN_TYPE, hardened = true),
            PathStep(0, hardened = true),
            PathStep(0, hardened = false),
            PathStep(0, hardened = false),
        ),
    )

    /**
     * Signs the request of [request] that [approval] reviewed, with the key at the path its header names, and answers with the `eth-signature`.
     * The request is read again from its body. The key must be the sender the review shows, so a key of another account signs nothing. The
     * signature covers the body, which is the one the review was built from.
     */
    override fun sign(request: AppRequest, approval: Approval, keys: KeyAccess): SignResponse {
        val parsed = EvmRequestBody.decode(request.body)
        require(approval.review.chain == request.header.chain) { "The approval is for another chain." }
        val requestId = requireNotNull(parsed.request.requestId) { NO_REQUEST_ID }
        val path = Path(request.header.signers.single().path.ifEmpty { singleKeyPath.steps })
        val sender = addressOf(keys.publicKey(Curve.SECP256K1, path).bytes)
        require(sender.equals(senderOf(approval.review), ignoreCase = true)) { SENDER_MISMATCH }
        val signDigest: (ByteArray) -> EcdsaSignature = { digest -> keys.signEcdsaSecp256k1(path, digest) }
        val signData = parsed.request.signData
        val signature = when (parsed.request.dataType) {
            DataType.TRANSACTION, DataType.TYPED_TRANSACTION -> TxSigner.signatureOf(signData, txOf(parsed), signDigest)
            DataType.PERSONAL_MESSAGE -> MessageSigner.signatureOf(signData, signDigest)
            DataType.TYPED_DATA -> TypedDataSigner.signatureOf(typedOf(parsed), signDigest)
        }
        return SignResponse(EvmSignResponse.UR_TYPE, EvmSignResponse.cbor(requestId, signature))
    }
}

/** The EIP-55 address of the key whose 33-byte compressed public key is [compressed]. */
private fun addressOf(compressed: ByteArray): String {
    val uncompressed = Sign.CURVE_PARAMS.curve.decodePoint(compressed).getEncoded(false)
    return EvmAddressCodec.fromPublicKey(ethereumMainnet, uncompressed, AddressKind.DEFAULT)
}

/** The sender the review shows: the address of its From row, which is the account the request is signed for. */
private fun senderOf(review: Review): String {
    val row = review.sections.flatMap { it.rows }.firstOrNull { it.label == FROM_LABEL }
    return (row?.value as? DisplayValue.Address)?.address ?: throw IllegalArgumentException(NO_SENDER)
}

/** The transaction of [parsed], which the request must decode to. */
private fun txOf(parsed: ParsedRequest): UnsignedTx = when (val tx = parsed.tx) {
    is UnsignedTx.Result.Ok -> tx.tx
    is UnsignedTx.Result.Rejected -> throw IllegalArgumentException(tx.reason)
    null -> throw IllegalArgumentException(CANNOT_BE_READ)
}

/** The typed data of [parsed], which the request must parse to. */
private fun typedOf(parsed: ParsedRequest): TypedData = when (val typed = parsed.typed) {
    is TypedDataParser.Result.Ok -> typed.data
    is TypedDataParser.Result.Rejected -> throw IllegalArgumentException(typed.reason)
    null -> throw IllegalArgumentException(CANNOT_BE_READ)
}
