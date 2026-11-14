package app.lensly.app.sign

import app.lensly.core.parse.InProcessParser
import app.lensly.core.parse.ScanReply
import app.lensly.core.parse.inProcessSandboxParser
import app.lensly.core.ur.EthSignRequest
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
        is ScanReply.Complete -> prepare(reply.parsed, wallet)
        is ScanReply.Refused -> Prepared.Refused(reply.reason)
        ScanReply.NotPart, is ScanReply.Incomplete -> error("A single frame is a whole request or a refusal")
    }

/** As [prepare] for a request that is already decoded. Its transaction is decoded in this process. */
fun SignPipeline.prepare(request: EthSignRequest, wallet: WalletView): Prepared =
    prepare(InProcessParser.parsedOf(request), wallet)
