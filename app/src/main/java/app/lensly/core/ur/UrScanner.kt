package app.lensly.core.ur

import app.lensly.core.parse.ParserLimits
import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder

private val SEQUENCE_COMPONENT = Regex("""(\d+)-(\d+)""")

/**
 * The reason a scanned UR part is refused before the decoder sees it, or null when it is within the limits or is not a
 * UR part at all. The decoder builds one index for each part that a UR declares, so a part that declares ten million
 * would exhaust memory before any check fails. Text that is not a UR part is left to the decoder.
 */
internal fun urPartRefusal(part: String): String? {
    if (!part.startsWith("ur:", ignoreCase = true)) return null
    if (part.length > ParserLimits.MAX_UR_PART_CHARS) {
        return "A code is longer than ${ParserLimits.MAX_UR_PART_CHARS} characters."
    }
    // A multi-part frame reads "ur:<type>/<sequence>/<body>", and its sequence reads "<index>-<count>".
    val components = part.substring("ur:".length).split('/')
    if (components.size != 3) return null
    val declared = SEQUENCE_COMPONENT.matchEntire(components[1])?.groupValues?.get(2) ?: return null
    val count = declared.toIntOrNull()
    if (count == null || count > ParserLimits.MAX_UR_PARTS) {
        return "The request has more than ${ParserLimits.MAX_UR_PARTS} parts."
    }
    return null
}

/**
 * Collects scanned QR payloads, either one single-part UR or the frames of an animated one, until a UR is complete.
 * Input may be upper- or lowercase, since the decoder ignores case.
 */
class UrScanner {
    private val decoder = URDecoder()

    /**
     * Feeds one scanned payload. Returns false for anything that is not a valid frame of the current UR, and for any
     * frame that breaks the limits of [urPartRefusal].
     */
    fun receive(part: String): Boolean {
        if (urPartRefusal(part) != null) return false
        return decoder.receivePart(part)
    }

    /** Fraction of the UR received, from 0.0 to 1.0. */
    val progress: Double
        get() = if (result() != null) 1.0 else decoder.estimatedPercentComplete

    /** The complete UR, or null until every fragment has arrived or if decoding failed. */
    fun result(): UR? {
        val result = decoder.result ?: return null
        return if (result.type == ResultType.SUCCESS) result.ur else null
    }
}
