package app.lensly.core.parse

import app.lensly.core.evm.UnsignedTx
import app.lensly.core.registry.ContractCall
import app.lensly.core.registry.StaticAbiDecoder
import app.lensly.core.registry.StaticDecode
import app.lensly.core.registry.WhitelistParam
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.UrScanner
import app.lensly.core.ur.urPartRefusal
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.web3j.utils.Numeric

/** The refusal of a request that the Parser Sandbox could not parse in time, or that did not answer, or answered wrongly. */
const val CANNOT_BE_READ = "This code could not be read safely."

/** The refusal of a code that is a UR of a type other than eth-sign-request. */
const val UNSUPPORTED_CODE = "Unsupported code"

/** What the sandbox says about the scanned parts of a request. */
sealed interface ScanReply {
    /** The new text is not part of the request. The scan keeps what it had. */
    data object NotPart : ScanReply

    /** The new text belongs to the request, which is not complete yet. [progress] runs from 0 to 1. */
    data class Incomplete(val progress: Double) : ScanReply

    /** The new text completes the request. [parsed] holds the request and what its data decodes to. */
    data class Complete(val parsed: ParsedRequest) : ScanReply

    /** The request cannot be read. [reason] says why, in plain words. */
    data class Refused(val reason: String) : ScanReply
}

/**
 * An eth-sign-request and what its data decodes to. A transaction dataType carries its decoded transaction, or the reason it
 * is refused. Other dataTypes carry neither. [call] is set when a decoded transaction's calldata is a call [ContractCall] knows.
 */
data class ParsedRequest(
    val request: EthSignRequest,
    val tx: UnsignedTx.Result?,
    val call: ContractCall?,
)

/**
 * The parsers of untrusted request input. The app runs them in the Parser Sandbox ([SandboxedParser]), and it checks every
 * result again on the way back. Neither method throws: a failure comes back as a refusal.
 */
interface RequestParser {
    /** Parses the scanned [parts] of one request, in the order they were scanned. */
    fun scan(parts: List<String>): ScanReply

    /** Decodes the calldata of a whitelisted call with the static [params] its registry entry declares. */
    fun decodeStatic(calldata: ByteArray, params: List<WhitelistParam>): StaticDecode
}

/**
 * The parsers themselves, run in the calling process. The Parser Sandbox runs them in its own process, and unit tests run them
 * here. Release code reaches them only through the sandbox.
 */
internal object InProcessParser : RequestParser {
    override fun scan(parts: List<String>): ScanReply {
        val scanner = UrScanner()
        var accepted = true
        for (part in parts) {
            urPartRefusal(part)?.let { return ScanReply.Refused(it) }
            accepted = scanner.receive(part)
        }
        if (!accepted) return ScanReply.NotPart
        val ur = scanner.result() ?: return ScanReply.Incomplete(scanner.progress)
        if (ur.type != EthSignRequest.UR_TYPE) return ScanReply.Refused(UNSUPPORTED_CODE)
        return when (val decoded = EthSignRequest.decode(ur.cborBytes)) {
            is EthSignRequest.Result.Ok -> ScanReply.Complete(parsedOf(decoded.request))
            is EthSignRequest.Result.Rejected -> ScanReply.Refused(decoded.reason)
        }
    }

    override fun decodeStatic(calldata: ByteArray, params: List<WhitelistParam>): StaticDecode =
        StaticAbiDecoder.decode(calldata, params)

    /** The transaction [request]'s data decodes to, and the call its calldata is. A data type that is not a transaction has neither. */
    fun parsedOf(request: EthSignRequest): ParsedRequest {
        if (request.dataType != DataType.TRANSACTION && request.dataType != DataType.TYPED_TRANSACTION) {
            return ParsedRequest(request, tx = null, call = null)
        }
        val tx = UnsignedTx.decode(request.signData, request.dataType)
        val calldata = (tx as? UnsignedTx.Result.Ok)?.tx?.data?.let { Numeric.hexStringToByteArray(it) }
        val call = calldata?.takeIf { it.isNotEmpty() }?.let { ContractCall.decode(it) }
        return ParsedRequest(request, tx, call)
    }
}

/**
 * Answers one call inside the Parser Sandbox with [parser]. It never throws: a payload it cannot read, a parser that fails
 * and a reply that breaks the wire limits all come back as a refusal.
 */
internal fun answer(parser: RequestParser, method: Int, payload: ByteArray): ByteArray = try {
    when (method) {
        ParserWire.SCAN -> ParserWire.encodeScanReply(parser.scan(ParserWire.decodeScanRequest(payload)))
        ParserWire.DECODE_STATIC -> {
            val (calldata, params) = ParserWire.decodeStaticRequest(payload)
            ParserWire.encodeStaticReply(parser.decodeStatic(calldata, params))
        }
        else -> ParserWire.encodeRefusal(CANNOT_BE_READ)
    }
} catch (e: Throwable) {
    // Fail closed. The process holds nothing but this call, so even an Error is answered with a refusal.
    ParserWire.encodeRefusal(CANNOT_BE_READ)
}

/** How the app reaches the Parser Sandbox. The app's transport is a binder to the isolated process. */
internal interface SandboxTransport {
    /** Sends one call and blocks until the reply. Throws when the sandbox cannot be reached or has died. */
    fun call(method: Int, payload: ByteArray): ByteArray

    /** Drops the connection, so the next call makes a new one. */
    fun reset()
}

/**
 * The app's side of the Parser Sandbox. It sends each call through [transport] and checks the reply before it is used: a
 * reply that is late, missing, larger than [ParserWire.MAX_REPLY_BYTES] or outside the wire format is a refusal. A call that
 * times out or fails resets the transport, so the next request gets a new connection.
 */
internal class SandboxedParser(
    private val transport: SandboxTransport,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) : RequestParser {
    // A binder call cannot be interrupted, so each call gets a thread that the caller can stop waiting for.
    private val calls = Executors.newCachedThreadPool { work -> Thread(work, "parser-sandbox").apply { isDaemon = true } }

    override fun scan(parts: List<String>): ScanReply =
        ask(ParserWire.SCAN, { ParserWire.encodeScanRequest(parts) }, ParserWire::decodeScanReply)
            ?: ScanReply.Refused(CANNOT_BE_READ)

    override fun decodeStatic(calldata: ByteArray, params: List<WhitelistParam>): StaticDecode =
        ask(ParserWire.DECODE_STATIC, { ParserWire.encodeStaticRequest(calldata, params) }, ParserWire::decodeStaticReply)
            ?: StaticDecode.Refused(CANNOT_BE_READ)

    /** The checked reply to one call, or null when there is none to trust. */
    private fun <T> ask(method: Int, encode: () -> ByteArray, decode: (ByteArray) -> T): T? {
        val payload = try {
            encode()
        } catch (e: WireException) {
            return null
        }
        val pending = calls.submit<ByteArray> { transport.call(method, payload) }
        val reply = try {
            pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // A timeout, a failed call or an interrupted wait: the caller gets no reply, and the connection is dropped.
            null
        }
        if (reply == null) {
            pending.cancel(true)
            transport.reset()
            return null
        }
        if (reply.size > ParserWire.MAX_REPLY_BYTES) return null
        return try {
            decode(reply)
        } catch (e: WireException) {
            null
        }
    }

    private companion object {
        /** The longest the app waits for one reply. It covers the start of the sandbox process and every decode budget. */
        const val TIMEOUT_MILLIS = 5_000L
    }
}
