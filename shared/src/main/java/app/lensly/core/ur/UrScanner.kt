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
    // Split as the decoder does, which drops trailing empty segments, so both read the same frame. A single part reads
    // "ur:<type>/<body>" and a multi-part frame reads "ur:<type>/<sequence>/<body>", with "<index>-<count>" as its sequence.
    // Any other shape is refused, so a trailing slash cannot hide a declared count from this check.
    val components = part.substring("ur:".length).split('/').dropLastWhile { it.isEmpty() }
    if (components.size == 2) return null
    if (components.size != 3) return "The code is not a valid UR part."
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

    /** How many distinct parts of the UR have been received so far. */
    val receivedParts: Int
        get() = decoder.receivedPartIndexes.size

    /** How many parts the UR declares, or 0 until a multi-part frame has been read. */
    val expectedParts: Int
        get() = decoder.expectedPartCount

    /** Fraction of the UR received, from 0.0 to 1.0. */
    val progress: Double
        get() = if (result() != null) 1.0 else decoder.estimatedPercentComplete

    /** The complete UR, or null until every fragment has arrived or if decoding failed. */
    fun result(): UR? {
        val result = decoder.result ?: return null
        return if (result.type == ResultType.SUCCESS) result.ur else null
    }
}
