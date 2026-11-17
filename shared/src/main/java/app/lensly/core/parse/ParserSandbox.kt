package app.lensly.core.parse

import app.lensly.core.algorand.AlgoTxnDecoder
import app.lensly.core.algorand.AlgoTxnException
import app.lensly.core.btc.BtcPsbt
import app.lensly.core.evm.TypedDataParser
import app.lensly.core.evm.UnsignedTx
import app.lensly.core.registry.ContractCall
import app.lensly.core.registry.AbiDecoder
import app.lensly.core.registry.CallDecode
import app.lensly.core.registry.WhitelistParam
import app.lensly.core.solana.SolanaMessage
import app.lensly.core.solana.SolanaOffchainMessage
import app.lensly.core.ur.AlgoSignRequest
import app.lensly.core.ur.AlgoSignType
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.SolSignRequest
import app.lensly.core.ur.SolSignType
import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UR
import fr.acinq.bitcoin.psbt.Psbt
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.web3j.utils.Numeric

/** The refusal of a request that the Parser Sandbox could not parse in time, or that did not answer, or answered wrongly. */
const val CANNOT_BE_READ = "This code could not be read safely."

/** The refusal of a code that is a UR of a type other than eth-sign-request. */
const val UNSUPPORTED_CODE = "Unsupported code"

/** The refusal of a `ur:crypto-psbt` whose PSBT does not parse. */
const val NOT_A_PSBT = "The Bitcoin request is not a valid PSBT."

/** What the sandbox says about the scanned parts of a request. */
sealed interface ScanReply {
    /** The new text is not part of the request. The scan keeps what it had. */
    data object NotPart : ScanReply

    /** The new text belongs to the request, which is not complete yet. [progress] runs from 0 to 1. */
    data class Incomplete(val progress: Double) : ScanReply

    /** The new text completes the request. [parsed] holds the request and what its data decodes to. */
    data class Complete(val parsed: ParsedRequest) : ScanReply

    /**
     * The new text completes a `ur:crypto-psbt`. [psbt] is the PSBT in its serialized form, which the sandbox has parsed, so a
     * PSBT that does not parse never gets here.
     */
    data class Bitcoin(val psbt: ByteArray) : ScanReply

    /**
     * The new text completes a `ur:sol-sign-request`. [payload] is its CBOR. The sandbox has parsed it, and has read its message when it
     * is a transaction, so a request that does not parse never gets here.
     */
    data class Solana(val payload: ByteArray) : ScanReply

    /**
     * The new text completes a `ur:algo-sign-request`. [payload] is its CBOR. The sandbox has parsed it, and has read its transaction when
     * it is one, so a request that does not parse never gets here.
     */
    data class Algorand(val payload: ByteArray) : ScanReply

    /** The request cannot be read. [reason] says why, in plain words. */
    data class Refused(val reason: String) : ScanReply
}

/**
 * An eth-sign-request and what its data decodes to. A transaction dataType carries its decoded transaction, or the reason it
 * is refused. Typed data carries its parse result, the typed message or the reason it is refused. Other dataTypes carry neither.
 * [call] is set when a decoded transaction's calldata is a call [ContractCall] knows.
 * [multicall] is set when that calldata is a multicall whose calls the sandbox read, level by level.
 */
data class ParsedRequest(
    val request: EthSignRequest,
    val tx: UnsignedTx.Result?,
    val call: ContractCall?,
    val multicall: Multicall? = null,
    val typed: TypedDataParser.Result? = null,
)

/**
 * The parsers of untrusted request input. The app runs them in the Parser Sandbox ([SandboxedParser]), and it checks every
 * result again on the way back. Neither method throws: a failure comes back as a refusal.
 */
interface RequestParser {
    /** Parses the scanned [parts] of one request, in the order they were scanned. */
    fun scan(parts: List<String>): ScanReply

    /** Decodes the calldata of a whitelisted call with the static [params] its registry entry declares. */
    fun decodeCall(calldata: ByteArray, params: List<WhitelistParam>): CallDecode
}

/**
 * The parsers themselves, run in the calling process. The Parser Sandbox runs them in its own process, and unit tests run them
 * here. Release code reaches them only through the sandbox.
 */
object InProcessParser : RequestParser {
    override fun scan(parts: List<String>): ScanReply = ScanSession(id = 0).read(parts)

    override fun decodeCall(calldata: ByteArray, params: List<WhitelistParam>): CallDecode =
        AbiDecoder.decode(calldata, params)

    /** The reply for a complete [ur]: the request it carries, parsed, or the reason it is refused. */
    internal fun replyOf(ur: UR): ScanReply {
        if (ur.type == BtcPsbt.UR_TYPE) return bitcoinReply(ur)
        if (ur.type == SolSignRequest.UR_TYPE) return solanaReply(ur)
        if (ur.type == AlgoSignRequest.UR_TYPE) return algorandReply(ur)
        if (ur.type != EthSignRequest.UR_TYPE) return ScanReply.Refused(UNSUPPORTED_CODE)
        return when (val decoded = EthSignRequest.decode(ur.cborBytes)) {
            is EthSignRequest.Result.Ok -> ScanReply.Complete(parsedOf(decoded.request))
            is EthSignRequest.Result.Rejected -> ScanReply.Refused(decoded.reason)
        }
    }

    /** The reply for a complete `ur:crypto-psbt`: its PSBT, parsed here in the sandbox, or a refusal when it does not parse. */
    private fun bitcoinReply(ur: UR): ScanReply = try {
        ScanReply.Bitcoin(Psbt.write(BtcPsbt.fromUr(ur)).toByteArray())
    } catch (e: IllegalArgumentException) {
        ScanReply.Refused(NOT_A_PSBT)
    }

    /**
     * The reply for a complete `ur:sol-sign-request`: its CBOR, when the request parses and its sign data parses too. A transaction must
     * parse as a transaction, and a message must parse as a Solana off-chain message. Otherwise the reason it is refused.
     */
    private fun solanaReply(ur: UR): ScanReply {
        val request = when (val decoded = SolSignRequest.decode(ur.cborBytes)) {
            is SolSignRequest.Result.Ok -> decoded.request
            is SolSignRequest.Result.Rejected -> return ScanReply.Refused(decoded.reason)
        }
        if (request.signType == SolSignType.TRANSACTION) {
            (SolanaMessage.decode(request.signData) as? SolanaMessage.Result.Rejected)?.let { return ScanReply.Refused(it.reason) }
        }
        if (request.signType == SolSignType.MESSAGE) {
            val parsed = SolanaOffchainMessage.parse(request.signData)
            if (parsed is SolanaOffchainMessage.Result.Rejected) return ScanReply.Refused(parsed.reason)
        }
        return ScanReply.Solana(ur.cborBytes)
    }

    /**
     * The reply for a complete `ur:algo-sign-request`: its CBOR, when the request parses and, for a transaction, the transaction decodes
     * too. Otherwise the reason it is refused. A message is answered without a transaction read, and the review refuses it.
     */
    private fun algorandReply(ur: UR): ScanReply {
        val request = when (val decoded = AlgoSignRequest.decode(ur.cborBytes)) {
            is AlgoSignRequest.Result.Ok -> decoded.request
            is AlgoSignRequest.Result.Rejected -> return ScanReply.Refused(decoded.reason)
        }
        if (request.signType == AlgoSignType.TRANSACTION) {
            try {
                AlgoTxnDecoder.decode(request.signData)
            } catch (e: AlgoTxnException) {
                return ScanReply.Refused(e.message ?: "The transaction could not be read.")
            }
        }
        return ScanReply.Algorand(ur.cborBytes)
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

/**
 * One scan of one request, as the Parser Sandbox holds it between calls. [id] names the scan to the app. [accepted] counts the
 * parts the scanner has taken, so a call can tell whether the app and the sandbox are in step.
 */
internal class ScanSession(val id: Long) {
    private val scanner = UrScanner()
    var accepted = 0
        private set

    /**
     * Reads [parts] in order and replies for the last one. A part that the scanner does not take is not part of the request: it is not
     * counted, it leaves the scanner as it was, and it is [ScanReply.NotPart] when it comes last.
     */
    fun read(parts: List<String>): ScanReply {
        var taken = true
        for (part in parts) {
            taken = scanner.receive(part)
            if (taken) accepted++
        }
        if (!taken) return ScanReply.NotPart
        val ur = scanner.result() ?: return ScanReply.Incomplete(scanner.progress)
        return InProcessParser.replyOf(ur)
    }
}

/** The reason the sandbox gives for a scan call that does not go on from the scan it holds. The app then sends the whole list again. */
internal const val SCAN_NOT_HELD = "The Parser Sandbox does not hold the scan this call goes on from."

/**
 * Answers the calls of one Parser Sandbox. [parser] decodes calldata. The scan the app is building is kept here between calls, so a scan
 * call reads only the parts it brings. It never throws: a payload it cannot read, a parser that fails and a reply that breaks the wire
 * limits all come back as a refusal.
 */
internal class SandboxAnswers(private val parser: RequestParser = InProcessParser) {
    private var scan: ScanSession? = null

    /** Answers one call. Calls are answered one at a time, as the scan they carry is kept between them. */
    @Synchronized
    fun answer(method: Int, payload: ByteArray): ByteArray = try {
        when (method) {
            ParserWire.SCAN -> scanAnswer(ParserWire.decodeScanRequest(payload))
            ParserWire.DECODE_CALL -> {
                val (calldata, params) = ParserWire.decodeCallRequest(payload)
                ParserWire.encodeCallReply(parser.decodeCall(calldata, params))
            }
            else -> ParserWire.encodeRefusal(CANNOT_BE_READ)
        }
    } catch (e: Throwable) {
        // Fail closed. A call that failed may have read part of its scan, so the scan is dropped and the app starts it again.
        scan = null
        ParserWire.encodeRefusal(CANNOT_BE_READ)
    }

    /** Reads the parts of a scan call when it goes on from the scan held. A call with no base starts a scan from no parts. */
    private fun scanAnswer(request: ParserWire.ScanRequest): ByteArray {
        if (request.base == 0) scan = ScanSession(request.session)
        val held = scan
        if (held == null || held.id != request.session || held.accepted != request.base) {
            return ParserWire.encodeRefusal(SCAN_NOT_HELD)
        }
        return ParserWire.encodeScanReply(held.read(request.parts))
    }
}

/** How the app reaches the Parser Sandbox. The app's transport is a binder to the isolated process. */
interface SandboxTransport {
    /** Sends one call and blocks until the reply. Throws when the sandbox cannot be reached or has died. */
    fun call(method: Int, payload: ByteArray): ByteArray

    /** Drops the connection, so the next call makes a new one. */
    fun reset()
}

/** The most calls to the Parser Sandbox that may be in flight at once. A call past that is refused without a call being made. */
internal const val MAX_CALLS_IN_FLIGHT = 2

/**
 * The app's side of the Parser Sandbox. It sends each call through [transport] and checks the reply before it is used: a
 * reply that is late, missing, larger than [ParserWire.MAX_REPLY_BYTES] or outside the wire format is a refusal. A call that
 * times out or fails resets the transport, so the next request gets a new connection.
 *
 * The sandbox keeps a scan between calls, so a scan whose list extends the one the sandbox holds sends only the new parts. A
 * sandbox that does not hold it, after a restart for example, is sent the whole list again as a new scan.
 */
class SandboxedParser(
    private val transport: SandboxTransport,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) : RequestParser {
    /** The scan the sandbox holds, or null when the app does not know what the sandbox holds. */
    private var held: HeldScan? = null

    // A binder call cannot be interrupted, so each call gets a thread that the caller can stop waiting for. A thread stays blocked until
    // its binder call returns, so the threads are bounded: a sandbox that never answers cannot grow them without limit.
    private val calls = ThreadPoolExecutor(0, MAX_CALLS_IN_FLIGHT, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, SynchronousQueue()) { work ->
        Thread(work, "parser-sandbox").apply { isDaemon = true }
    }

    @Synchronized
    override fun scan(parts: List<String>): ScanReply {
        val known = held
        if (known != null && parts.size >= known.parts.size && parts.subList(0, known.parts.size) == known.parts) {
            sendScan(known.session, known.parts.size, parts)?.let { return it }
        }
        return sendScan(Random.nextLong(), 0, parts) ?: ScanReply.Refused(CANNOT_BE_READ)
    }

    /**
     * Sends one call of scan [session], which adds the parts of [parts] after the first [base], and records what the sandbox holds after
     * it. Null when the sandbox does not hold the scan, so the caller starts it again.
     */
    private fun sendScan(session: Long, base: Int, parts: List<String>): ScanReply? {
        val reply = ask(
            ParserWire.SCAN,
            { ParserWire.encodeScanRequest(session, base, parts.subList(base, parts.size)) },
            ParserWire::decodeScanReply,
        )
        if (reply == ScanReply.Refused(SCAN_NOT_HELD)) {
            held = null
            return null
        }
        held = when (reply) {
            // The last part was not taken, so the sandbox holds only the parts before it.
            ScanReply.NotPart -> HeldScan(session, parts.dropLast(1))
            null, is ScanReply.Refused -> null
            else -> HeldScan(session, parts.toList())
        }
        return reply ?: ScanReply.Refused(CANNOT_BE_READ)
    }

    override fun decodeCall(calldata: ByteArray, params: List<WhitelistParam>): CallDecode =
        ask(ParserWire.DECODE_CALL, { ParserWire.encodeCallRequest(calldata, params) }, ParserWire::decodeCallReply)
            ?: CallDecode.Refused(CANNOT_BE_READ)

    /** The checked reply to one call, or null when there is none to trust. */
    private fun <T> ask(method: Int, encode: () -> ByteArray, decode: (ByteArray) -> T): T? {
        val payload = try {
            encode()
        } catch (e: WireException) {
            return null
        }
        val pending = try {
            calls.submit<ByteArray> { transport.call(method, payload) }
        } catch (e: RejectedExecutionException) {
            // Every thread is still blocked in a call the sandbox has not answered. The caller gets no reply, and the connection drops.
            transport.reset()
            return null
        }
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

    /** A scan the sandbox holds: its [session], and the [parts] it has taken, in order. */
    private class HeldScan(val session: Long, val parts: List<String>)

    private companion object {
        /** The longest the app waits for one reply. It covers the start of the sandbox process and every decode budget. */
        const val TIMEOUT_MILLIS = 5_000L

        /** How long an idle thread of [calls] waits for its next call before it ends. */
        const val KEEP_ALIVE_SECONDS = 30L
    }
}
