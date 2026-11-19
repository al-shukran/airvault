package app.lensly.app.sign

import app.airvault.chains.evm.evmManifest
import app.airvault.chains.evm.signer.EthSignRequest
import app.airvault.chains.evm.signer.EvmRequestBody
import app.airvault.chains.evm.signer.EvmRequestParser
import app.airvault.chains.evm.signer.ParsedRequest
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.UNSUPPORTED_CODE
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.ur.framesOf
import com.sparrowwallet.hummingbird.UR

/** Large enough that a request of the largest size is one frame. */
private const val ONE_FRAME = 64 * 1024

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
