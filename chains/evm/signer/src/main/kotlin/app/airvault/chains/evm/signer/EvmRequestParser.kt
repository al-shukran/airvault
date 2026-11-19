package app.airvault.chains.evm.signer

import app.airvault.chains.evm.evmManifest
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.ParseOutcome
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.RequestParser
import app.airvault.core.signer.SignerRef
import app.lensly.core.parse.ParserLimits
import app.lensly.core.parse.WireException
import org.web3j.utils.Numeric

/** The limits EVM asks for: the codec's own. The parser lowers the core's limits to these, so it reads no more than either allows. */
private val EVM_LIMITS = ParserLimits(
    maxSignDataBytes = EthSignRequest.MAX_SIGN_DATA_BYTES,
    maxPayloadBytes = EthSignRequest.MAX_PAYLOAD_BYTES,
)

/** The chain a request names none for: Ethereum mainnet. A personal message carries no chain id. */
internal const val DEFAULT_CHAIN_ID = 1L

private val DEFAULT_CHAIN = ChainId("eip155:$DEFAULT_CHAIN_ID")

/**
 * The parser of eth-sign-request (spec 3.2). It reads the request and parses its data: a transaction as RLP, typed data as EIP-712 and a
 * call or multicall inside the transaction. The result is kept as the body, which the app reads back with [EvmRequestBody.decode]. A request
 * that a parse refuses still carries the refusal in its body, so the review shows it.
 */
object EvmRequestParser : RequestParser {
    override fun parse(urType: String, cbor: ByteArray, limits: ParserLimits): ParseOutcome {
        if (urType != EthSignRequest.UR_TYPE) return ParseOutcome.Refused("UR type $urType is not ${EthSignRequest.UR_TYPE}")
        val effective = limits.lowerTo(EVM_LIMITS)
        if (cbor.size > effective.maxPayloadBytes) return ParseOutcome.Refused("payload exceeds ${effective.maxPayloadBytes} bytes")
        val request = when (val decoded = EthSignRequest.decode(cbor)) {
            is EthSignRequest.Result.Ok -> decoded.request
            is EthSignRequest.Result.Rejected -> return ParseOutcome.Refused(decoded.reason)
        }
        if (request.signData.size > effective.maxSignDataBytes) {
            return ParseOutcome.Refused("signData exceeds ${effective.maxSignDataBytes} bytes")
        }
        val chain = request.chainId?.let { id ->
            runCatching { ChainId("eip155:$id") }.getOrNull() ?: return ParseOutcome.Refused("chain id $id is not valid")
        } ?: DEFAULT_CHAIN
        val kind = when (request.dataType) {
            DataType.TRANSACTION, DataType.TYPED_TRANSACTION -> RequestKind.TRANSACTION
            DataType.TYPED_DATA -> RequestKind.TYPED_DATA
            DataType.PERSONAL_MESSAGE -> RequestKind.MESSAGE
        }
        val header = RequestHeader(
            app = evmManifest.id,
            chain = chain,
            kind = kind,
            signers = listOf(SignerRef(request.sourceFingerprint, request.derivationPath)),
            requestId = request.requestId,
        )
        val body = try {
            EvmRequestBody.encode(parsedOf(request))
        } catch (e: WireException) {
            return ParseOutcome.Refused(e.message ?: "The request is too large to read.")
        }
        return ParseOutcome.Parsed(AppRequest(header, body))
    }

    /**
     * The transaction [request]'s data decodes to, and the call its calldata is. Typed data is parsed as EIP-712. A personal
     * message has neither.
     */
    fun parsedOf(request: EthSignRequest): ParsedRequest {
        if (request.dataType == DataType.TYPED_DATA) {
            return ParsedRequest(request, tx = null, call = null, typed = TypedDataParser.parse(request.signData))
        }
        if (request.dataType != DataType.TRANSACTION && request.dataType != DataType.TYPED_TRANSACTION) {
            return ParsedRequest(request, tx = null, call = null)
        }
        val tx = UnsignedTx.decode(request.signData, request.dataType)
        val calldata = (tx as? UnsignedTx.Result.Ok)?.tx?.data?.let { Numeric.hexStringToByteArray(it) }
        val call = calldata?.takeIf { it.isNotEmpty() }?.let { ContractCall.decode(it) }
        val multicall = calldata?.let { multicallOf(it) }
        return ParsedRequest(request, tx, call, multicall)
    }
}
