package app.lensly.app.sign

import app.airvault.chains.evm.evmManifest
import app.airvault.chains.evm.signer.DataType
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmRequestBody
import app.airvault.chains.evm.signer.EvmRequestParser
import app.airvault.chains.evm.signer.EvmSigner
import app.airvault.chains.evm.signer.ParsedRequest
import app.airvault.core.model.Approval
import app.airvault.core.model.ChainId
import app.airvault.core.model.checkLabels
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.Path
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.app.export.partsOf
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.UNSUPPORTED_CODE
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.ur.DEFAULT_MAX_FRAGMENT
import app.lensly.core.ur.framesOf
import app.lensly.core.wallet.SingleKeyAccess
import com.sparrowwallet.hummingbird.UR

/** Large enough that a request of the largest size is one frame. */
private const val ONE_FRAME = 64 * 1024

/**
 * The frames the sign flow answers [ready] with: the review with every check ticked, approved, then signed by [EvmSigner] with [key] as the one
 * key the request names, at the path its header names. A Single Key request with no path is signed at the Single Key path.
 */
fun signFramesOf(ready: Prepared.Ready, key: ByteArray): List<String> {
    val request = appRequestOf(ready.request)
    val approval = requireNotNull(Approval.of(ready.review, ready.review.checkLabels().toSet()))
    val path = Path(request.header.signers.single().path.ifEmpty { EvmSigner.singleKeyPath.steps })
    val response = EvmSigner.sign(request, approval, SingleKeyAccess(key.copyOf(), path))
    return partsOf(framesOf(UR(response.urType, response.cbor), DEFAULT_MAX_FRAGMENT))
}

/** [request] as the EVM app sends it to the sign flow: its header names the signer and path, and its body is the parsed request. */
fun appRequestOf(request: EthSignRequest): AppRequest {
    val kind = when (request.dataType) {
        DataType.TRANSACTION, DataType.TYPED_TRANSACTION -> RequestKind.TRANSACTION
        DataType.TYPED_DATA -> RequestKind.TYPED_DATA
        DataType.PERSONAL_MESSAGE -> RequestKind.MESSAGE
    }
    val header = RequestHeader(
        app = evmManifest.id,
        chain = ChainId("eip155:${request.chainId ?: 1}"),
        kind = kind,
        signers = listOf(SignerRef(request.sourceFingerprint, request.derivationPath)),
        requestId = request.requestId,
    )
    return AppRequest(header, EvmRequestBody.encode(EvmRequestParser.parsedOf(request)))
}

/** Parses [urCbor] through the Parser Sandbox's wire format, as the app does, then prepares what it parsed. */
fun SignPipeline.prepare(urCbor: ByteArray, wallet: WalletView): Prepared =
    prepare(UR(EthSignRequest.UR_TYPE, urCbor), wallet)

/**
 * As [prepare] for a scanned UR. The UR is framed and read by the Parser Sandbox, as a scanned one is. A UR of any type
 * other than eth-sign-request is refused.
 */
fun SignPipeline.prepare(ur: UR, wallet: WalletView): Prepared =
    when (val reply = inProcessSandboxParser.scan(listOf(framesOf(ur, ONE_FRAME).first()))) {
        is ScanReply.Refused -> Prepared.Refused(reply.reason)
        is ScanReply.App -> evmParsedOf(reply)?.let { prepare(it, wallet) } ?: Prepared.Refused(UNSUPPORTED_CODE)
        ScanReply.NotPart, is ScanReply.Incomplete -> error("A single frame is a whole request or a refusal")
    }

/** As [prepare] for a request that is already decoded. Its transaction is decoded in this process. */
fun SignPipeline.prepare(request: EthSignRequest, wallet: WalletView): Prepared =
    prepare(EvmRequestParser.parsedOf(request), wallet)

/** The EVM request in an app reply, read back as the app reads it. Null for a reply that is not an EVM request. */
fun evmParsedOf(reply: ScanReply): ParsedRequest? =
    (reply as? ScanReply.App)?.takeIf { it.request.header.app == evmManifest.id }?.let { EvmRequestBody.decode(it.request.body) }

/** The app reply that carries [parsed] as the EVM app sends it: its header, and the body the EVM app writes for it. */
fun evmReplyOf(parsed: ParsedRequest): ScanReply.App {
    val header = RequestHeader(evmManifest.id, ChainId("eip155:1"), RequestKind.TRANSACTION, emptyList(), null)
    return ScanReply.App(AppRequest(header, EvmRequestBody.encode(parsed)))
}
