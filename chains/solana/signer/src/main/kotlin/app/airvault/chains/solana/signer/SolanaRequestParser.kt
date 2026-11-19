package app.airvault.chains.solana.signer

import app.airvault.chains.solana.SOLANA_DEVNET
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.chains.solana.SOLANA_TESTNET
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerRef
import app.lensly.core.parse.ParserLimits

/** The limits Solana asks for: the codec's own. The parser lowers the core's limits to these, so it reads no more than either allows. */
private val SOLANA_LIMITS = ParserLimits(
    maxSignDataBytes = SolSignRequest.MAX_SIGN_DATA_BYTES,
    maxPayloadBytes = SolSignRequest.MAX_PAYLOAD_BYTES,
)

/**
 * The parser of sol-sign-request (spec 3.2). It reads the request, and then its sign data: a transaction must parse as a transaction,
 * and a message must parse as a Solana off-chain message. A request names its cluster, and one that names none is for mainnet-beta.
 * The sign data is kept as the body, which the Solana review reads again.
 */
object SolanaRequestParser : RequestParser {
    override fun parse(urType: String, cbor: ByteArray, limits: ParserLimits): ParseOutcome {
        if (urType != SolSignRequest.UR_TYPE) return ParseOutcome.Refused("UR type $urType is not ${SolSignRequest.UR_TYPE}")
        val effective = limits.lowerTo(SOLANA_LIMITS)
        if (cbor.size > effective.maxPayloadBytes) return ParseOutcome.Refused("payload exceeds ${effective.maxPayloadBytes} bytes")
        val request = when (val decoded = SolSignRequest.decode(cbor)) {
            is SolSignRequest.Result.Ok -> decoded.request
            is SolSignRequest.Result.Rejected -> return ParseOutcome.Refused(decoded.reason)
        }
        if (request.signData.size > effective.maxSignDataBytes) {
            return ParseOutcome.Refused("signData exceeds ${effective.maxSignDataBytes} bytes")
        }
        val kind = when (request.signType) {
            SolSignType.TRANSACTION -> {
                (SolanaMessage.decode(request.signData) as? SolanaMessage.Result.Rejected)?.let { return ParseOutcome.Refused(it.reason) }
                RequestKind.TRANSACTION
            }
            SolSignType.MESSAGE -> {
                val parsed = SolanaOffchainMessage.parse(request.signData)
                if (parsed is SolanaOffchainMessage.Result.Rejected) return ParseOutcome.Refused(parsed.reason)
                RequestKind.MESSAGE
            }
        }
        val header = RequestHeader(
            app = solanaManifest.id,
            chain = clusterChain(request.cluster),
            kind = kind,
            signers = listOf(SignerRef(request.sourceFingerprint, request.derivationPath)),
            requestId = request.requestId,
        )
        return ParseOutcome.Parsed(AppRequest(header, cbor))
    }

    /** The chain of [cluster]. A request that names none is for mainnet-beta. */
    private fun clusterChain(cluster: SolCluster?): ChainId = when (cluster) {
        null, SolCluster.MAINNET_BETA -> SOLANA_MAINNET
        SolCluster.DEVNET -> SOLANA_DEVNET
        SolCluster.TESTNET -> SOLANA_TESTNET
    }
}
