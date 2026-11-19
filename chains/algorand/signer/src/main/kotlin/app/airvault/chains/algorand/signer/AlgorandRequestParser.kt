package app.airvault.chains.algorand.signer

import app.airvault.chains.algorand.AlgoSignRequest
import app.airvault.chains.algorand.AlgoSignType
import app.airvault.chains.algorand.algorandManifest
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerRef
import app.lensly.core.parse.ParserLimits

/** The limits Algorand asks for: the codec's own. The parser lowers the core's limits to these, so it reads no more than either allows. */
private val ALGORAND_LIMITS = ParserLimits(
    maxSignDataBytes = AlgoSignRequest.MAX_SIGN_DATA_BYTES,
    maxPayloadBytes = AlgoSignRequest.MAX_PAYLOAD_BYTES,
)

/** The same refusal as the review's unknown-network check, which names the networks Lensly signs for. */
private const val UNKNOWN_NETWORK =
    "This transaction is for a network Lensly does not sign for. Lensly signs for Algorand testnet and mainnet."

/**
 * The parser of algo-sign-request (spec 3.2). It reads the request, and for a transaction the transaction too. A transaction names its
 * chain by the genesis hash it carries. A message carries no genesis hash, and the request carries no network, so a message names the
 * Algorand mainnet.
 */
object AlgorandRequestParser : RequestParser {
    override fun parse(urType: String, cbor: ByteArray, limits: ParserLimits): ParseOutcome {
        if (urType != AlgoSignRequest.UR_TYPE) return ParseOutcome.Refused("UR type $urType is not ${AlgoSignRequest.UR_TYPE}")
        val effective = limits.lowerTo(ALGORAND_LIMITS)
        if (cbor.size > effective.maxPayloadBytes) return ParseOutcome.Refused("payload exceeds ${effective.maxPayloadBytes} bytes")
        val request = when (val decoded = AlgoSignRequest.decode(cbor)) {
            is AlgoSignRequest.Result.Ok -> decoded.request
            is AlgoSignRequest.Result.Rejected -> return ParseOutcome.Refused(decoded.reason)
        }
        if (request.signData.size > effective.maxSignDataBytes) {
            return ParseOutcome.Refused("signData exceeds ${effective.maxSignDataBytes} bytes")
        }
        val kind: RequestKind
        val chain: ChainId
        when (request.signType) {
            AlgoSignType.TRANSACTION -> {
                val txn = try {
                    AlgoTxnDecoder.decode(request.signData)
                } catch (e: AlgoTxnException) {
                    return ParseOutcome.Refused(e.message ?: "The transaction could not be read.")
                }
                kind = RequestKind.TRANSACTION
                chain = runCatching { algorandChainId(txn.genesisHash) }.getOrNull() ?: return ParseOutcome.Refused(UNKNOWN_NETWORK)
            }
            AlgoSignType.MESSAGE -> {
                kind = RequestKind.MESSAGE
                chain = algorandManifest.networks.first { !it.testnet }.chainId
            }
        }
        val header = RequestHeader(
            app = algorandManifest.id,
            chain = chain,
            kind = kind,
            signers = listOf(SignerRef(request.sourceFingerprint, request.derivationPath)),
            requestId = request.requestId,
        )
        return ParseOutcome.Parsed(AppRequest(header, cbor))
    }
}
