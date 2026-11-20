package app.lensly.core.parse

import app.airvault.abi.AbiValue
import app.airvault.abi.CallDecode
import app.airvault.abi.DecodedParam
import app.airvault.abi.WhitelistParam
import app.airvault.abi.hexBytesOrNull
import app.airvault.core.model.AppId
import app.airvault.core.model.ChainId
import app.airvault.core.signer.AppRequest
import app.airvault.core.signer.RequestHeader
import app.airvault.core.signer.RequestKind
import app.airvault.core.signer.SignerRef
import app.lensly.core.ur.PathStep
import app.lensly.core.wallet.toHex

/**
 * The wire format between the app and the Parser Sandbox. A payload is a version, a tag, then the fields of its value. Every
 * length is prefixed and bounded, and every decoded value is checked against its schema, so a malformed or oversized reply is
 * refused rather than trusted. The encoder applies the same bounds, so an oversized value is never sent.
 */
object ParserWire {
    /** The method of a call that parses scanned parts. */
    const val SCAN = 1

    /** The method of a call that decodes the calldata of a whitelisted call. */
    const val DECODE_CALL = 2

    /** The largest reply the app accepts. It carries the data to sign, the calldata and an access list, each within its limit. */
    const val MAX_REPLY_BYTES = 256 * 1024

    private const val VERSION = 1

    /** The tag that both kinds of reply share for a refusal. It carries the reason. */
    private const val REFUSED = 0
    private const val NOT_PART = 1
    private const val INCOMPLETE = 2
    private const val APP_REQUEST = 6
    private const val REFUSED_REQUEST = 7
    private const val CALL_OK = 1

    /** The tags of one decoded value on the wire: a static word, the bytes of a bytes or string, and the items of an array or tuple. */
    private const val VALUE_WORD = 0
    private const val VALUE_DYNAMIC = 1
    private const val VALUE_ITEMS = 2

    /** The most bytes of an app request's body, which the wire carries as a blob. */
    private const val MAX_APP_BODY_BYTES = ParserLimits.MAX_APP_BODY_BYTES

    /** The most keys one app request may ask for. */
    private const val MAX_SIGNERS = 16

    private const val MAX_CALLDATA = ParserLimits.MAX_CALLDATA_BYTES

    private const val MAX_TEXT_BYTES = 4096
    private const val MAX_PARTS = 4096
    private const val MAX_PART_BYTES = 8192
    private const val MAX_PATH_STEPS = 16
    private const val MAX_CALL_PARAMS = 32
    private const val MAX_WORD_BYTES = 32
    private const val REQUEST_ID_BYTES = 16

    /** The reply both calls share when the sandbox cannot answer: a refusal with its reason. */
    fun encodeRefusal(reason: String): ByteArray = WireWriter().apply {
        int(VERSION)
        int(REFUSED)
        text(reason)
    }.bytes()

    /**
     * One call of a scan: the scan's [session], how many of its parts the sandbox already holds ([base]), and the [parts] that follow
     * them. A call that starts a scan has a base of 0.
     */
    data class ScanRequest(val session: Long, val base: Int, val parts: List<String>)

    /** Encodes one call of scan [session] that adds [parts] after the [base] parts the sandbox holds. */
    fun encodeScanRequest(session: Long, base: Int, parts: List<String>): ByteArray = WireWriter().apply {
        int(VERSION)
        int(SCAN)
        long(session)
        count(base, MAX_PARTS)
        count(parts.size, MAX_PARTS)
        parts.forEach { text(it, MAX_PART_BYTES) }
    }.bytes()

    fun decodeScanRequest(payload: ByteArray): ScanRequest = WireReader(payload).run {
        method(SCAN)
        val session = long()
        val base = count(MAX_PARTS)
        val parts = List(count(MAX_PARTS)) { text(MAX_PART_BYTES) }
        done()
        ScanRequest(session, base, parts)
    }

    fun encodeScanReply(reply: ScanReply): ByteArray = WireWriter().apply {
        int(VERSION)
        when (reply) {
            is ScanReply.Refused -> {
                val request = reply.request
                if (request == null) {
                    int(REFUSED)
                    text(reply.reason)
                } else {
                    int(REFUSED_REQUEST)
                    text(reply.reason)
                    appRequest(request)
                }
            }
            ScanReply.NotPart -> int(NOT_PART)
            is ScanReply.Incomplete -> {
                int(INCOMPLETE)
                double(reply.progress)
            }
            is ScanReply.App -> {
                int(APP_REQUEST)
                appRequest(reply.request)
            }
        }
    }.bytes()

    /** Decodes and checks a scan reply. Throws [WireException] for anything the schema or a limit refuses. */
    fun decodeScanReply(payload: ByteArray): ScanReply = WireReader(payload).run {
        version()
        val reply = when (int()) {
            REFUSED -> ScanReply.Refused(text())
            REFUSED_REQUEST -> ScanReply.Refused(text(), appRequestOf())
            NOT_PART -> ScanReply.NotPart
            INCOMPLETE -> ScanReply.Incomplete(double().also { if (!(it in 0.0..1.0)) throw WireException("progress is outside 0 to 1") })
            APP_REQUEST -> ScanReply.App(appRequestOf())
            else -> throw WireException("unknown scan reply")
        }
        done()
        reply
    }

    fun encodeCallRequest(calldata: ByteArray, params: List<WhitelistParam>): ByteArray = WireWriter().apply {
        int(VERSION)
        int(DECODE_CALL)
        blob(calldata, MAX_CALLDATA)
        count(params.size, MAX_CALL_PARAMS)
        params.forEach { param(it) }
    }.bytes()

    fun decodeCallRequest(payload: ByteArray): Pair<ByteArray, List<WhitelistParam>> = WireReader(payload).run {
        method(DECODE_CALL)
        val calldata = blob(MAX_CALLDATA)
        val params = List(count(MAX_CALL_PARAMS)) { whitelistParam() }
        done()
        calldata to params
    }

    fun encodeCallReply(decoded: CallDecode): ByteArray = WireWriter().apply {
        int(VERSION)
        when (decoded) {
            is CallDecode.Refused -> {
                int(REFUSED)
                text(decoded.reason)
            }
            is CallDecode.Ok -> {
                int(CALL_OK)
                count(decoded.params.size, MAX_CALL_PARAMS)
                decoded.params.forEach {
                    param(it.param)
                    value(it.value)
                }
            }
        }
    }.bytes()

    /** Decodes and checks a call reply. Throws [WireException] for anything the schema or a limit refuses. */
    fun decodeCallReply(payload: ByteArray): CallDecode = WireReader(payload).run {
        version()
        val budget = DecodeBudget("ABI", ParserLimits.MAX_ABI_VALUES)
        val reply = when (int()) {
            REFUSED -> CallDecode.Refused(text())
            CALL_OK -> CallDecode.Ok(List(count(MAX_CALL_PARAMS)) { DecodedParam(whitelistParam(), valueOf(budget, depth = 0)) })
            else -> throw WireException("unknown call reply")
        }
        done()
        reply
    }

    /** Writes an app request: its header, which the core reads, then its body, which only the app reads. */
    private fun WireWriter.appRequest(request: AppRequest) {
        val header = request.header
        text(header.app.value)
        text(header.chain.caip2)
        int(header.kind.wire)
        count(header.signers.size, MAX_SIGNERS)
        header.signers.forEach { signer ->
            optional(signer.fingerprint) { int(it) }
            count(signer.path.size, MAX_PATH_STEPS)
            signer.path.forEach {
                int(it.index)
                flag(it.hardened)
            }
        }
        optional(header.requestId) { exact(it, REQUEST_ID_BYTES) }
        blob(request.body, MAX_APP_BODY_BYTES)
    }

    /** Reads an app request. An id the types refuse is a wire error, so the app refuses the reply that carried it. */
    private fun WireReader.appRequestOf(): AppRequest {
        val app = try {
            AppId(text())
        } catch (e: IllegalArgumentException) {
            throw WireException("app id is malformed")
        }
        val chain = try {
            ChainId(text())
        } catch (e: IllegalArgumentException) {
            throw WireException("chain id is malformed")
        }
        val kind = RequestKind.fromWire(int()) ?: throw WireException("unknown request kind")
        val signers = List(count(MAX_SIGNERS)) {
            val fingerprint = optional { int() }
            val path = List(count(MAX_PATH_STEPS)) {
                val index = int()
                if (index < 0) throw WireException("negative path index")
                PathStep(index, flag())
            }
            SignerRef(fingerprint, path)
        }
        val requestId = optional { exact(REQUEST_ID_BYTES) }
        val body = blob(MAX_APP_BODY_BYTES)
        return AppRequest(RequestHeader(app, chain, kind, signers, requestId), body)
    }

    private fun WireWriter.param(param: WhitelistParam) {
        text(param.name)
        text(param.type)
        flag(param.tokenRef)
        optional(param.components) { fields ->
            count(fields.size, MAX_CALL_PARAMS)
            fields.forEach { param(it) }
        }
    }

    /** A whitelist parameter, built only when its name, type and field names pass the checks of [WhitelistParam]. */
    private fun WireReader.whitelistParam(depth: Int = 0): WhitelistParam {
        if (depth > ParserLimits.MAX_ABI_DEPTH) throw WireException("parameters nest deeper than ${ParserLimits.MAX_ABI_DEPTH} levels")
        val name = text()
        val type = text()
        val tokenRef = flag()
        val components = optional { List(count(MAX_CALL_PARAMS)) { whitelistParam(depth + 1) } }
        return try {
            WhitelistParam(name, type, tokenRef, components)
        } catch (e: IllegalArgumentException) {
            throw WireException("not a valid parameter")
        }
    }

    /** One decoded value: a tag, then a word, the bytes of a dynamic value, or the items of an array or tuple. */
    private fun WireWriter.value(value: AbiValue) {
        when (value) {
            is AbiValue.Word -> {
                int(VALUE_WORD)
                val word = hexBytesOrNull(value.hex)
                if (word == null || word.size != MAX_WORD_BYTES) throw WireException("a word is not 32 bytes of hex")
                blob(word, MAX_WORD_BYTES)
            }
            is AbiValue.Dynamic -> {
                int(VALUE_DYNAMIC)
                blob(hexBytesOrNull(value.hex) ?: throw WireException("bytes are not hex"), MAX_CALLDATA)
            }
            is AbiValue.Items -> {
                int(VALUE_ITEMS)
                count(value.items.size, ParserLimits.MAX_ABI_VALUES)
                value.items.forEach { value(it) }
            }
        }
    }

    /** The value that [WireWriter.value] wrote. [budget] counts every value of the reply, and [depth] is how deep this one sits. */
    private fun WireReader.valueOf(budget: DecodeBudget, depth: Int): AbiValue {
        budget.charge()?.let { throw WireException(it) }
        if (depth > ParserLimits.MAX_ABI_DEPTH) throw WireException("values nest deeper than ${ParserLimits.MAX_ABI_DEPTH} levels")
        return when (int()) {
            VALUE_WORD -> AbiValue.Word(exact(MAX_WORD_BYTES).toHex())
            VALUE_DYNAMIC -> AbiValue.Dynamic(blob(MAX_CALLDATA).toHex())
            VALUE_ITEMS -> AbiValue.Items(List(count(ParserLimits.MAX_ABI_VALUES)) { valueOf(budget, depth + 1) })
            else -> throw WireException("unknown value")
        }
    }

    private fun WireReader.version() {
        if (int() != VERSION) throw WireException("unsupported wire version")
    }

    private fun WireReader.method(expected: Int) {
        version()
        if (int() != expected) throw WireException("unexpected method")
    }
}
