package app.lensly.core.ur

import com.sparrowwallet.hummingbird.ResultType
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder

/**
 * Collects scanned QR payloads, either one single-part UR or the frames of an animated one, until a UR is complete.
 * Input may be upper- or lowercase, since the decoder ignores case.
 */
class UrScanner {
    private val decoder = URDecoder()

    /** Feeds one scanned payload. Returns false for anything that is not a valid frame of the current UR. */
    fun receive(part: String): Boolean = decoder.receivePart(part)

    /** Fraction of the UR received, from 0.0 to 1.0. */
    val progress: Double
        get() = if (result() != null) 1.0 else decoder.estimatedPercentComplete

    /** The complete UR, or null until every fragment has arrived or if decoding failed. */
    fun result(): UR? {
        val result = decoder.result ?: return null
        return if (result.type == ResultType.SUCCESS) result.ur else null
    }
}
