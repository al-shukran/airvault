package app.airvault.core.signer

import app.airvault.core.model.AppId
import app.airvault.core.model.ChainId
import app.lensly.core.parse.ParserLimits
import app.lensly.core.ur.PathStep

/**
 * Parses the requests of one chain app (spec 3.2). It runs inside the Parser Sandbox with no key access, as a pure function of its
 * bytes. It must not throw: a request it cannot read is [ParseOutcome.Refused] with the reason.
 */
interface RequestParser {
    /**
     * Parses the CBOR payload [cbor] of a UR of type [urType]. [limits] are the core's limits. The parser may lower them, never raise
     * them (see [ParserLimits.lowerTo]).
     */
    fun parse(urType: String, cbor: ByteArray, limits: ParserLimits): ParseOutcome
}

/** What a [RequestParser] makes of one UR payload. */
sealed interface ParseOutcome {
    data class Parsed(val request: AppRequest) : ParseOutcome

    /** [reason] says why, in plain words. */
    data class Refused(val reason: String) : ParseOutcome
}

/** A parsed request: a [header] that the core reads, and a [body] that only the app reads. The app serialises its own body. */
data class AppRequest(val header: RequestHeader, val body: ByteArray)

/**
 * What the core reads from a parsed request (spec 3.2). [requestId] is echoed in the response (spec 7.3), and it is null when the
 * request has none.
 */
data class RequestHeader(
    val app: AppId,
    val chain: ChainId,
    val kind: RequestKind,
    val signers: List<SignerRef>,
    val requestId: ByteArray?,
)

/** What a request asks the key to sign. The wire numbers are the ones the Parser Sandbox sends. */
enum class RequestKind(val wire: Int) {
    TRANSACTION(1),
    MESSAGE(2),
    TYPED_DATA(3);

    companion object {
        fun fromWire(value: Int): RequestKind? = entries.firstOrNull { it.wire == value }
    }
}

/** A key the request asks for: the master fingerprint the wallet named, or null when it named none, and the derivation path. */
data class SignerRef(val fingerprint: Int?, val path: List<PathStep>)
