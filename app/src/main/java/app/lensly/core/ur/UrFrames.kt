package app.lensly.core.ur

import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder

/** Largest CBOR fragment per animated QR frame, in bytes. */
internal const val DEFAULT_MAX_FRAGMENT = 200

private const val MIN_FRAGMENT = 10

/**
 * Uppercase UR frames for [ur]. A payload that fits in [maxFragment] bytes is one frame, repeated.
 * A larger payload is an endless fountain-coded stream, so any sufficiently long run of frames decodes.
 *
 * Each iteration starts a fresh encoder, so the sequence can be read more than once from the first frame.
 */
internal fun framesOf(ur: UR, maxFragment: Int): Sequence<String> {
    require(maxFragment >= MIN_FRAGMENT) { "maxFragment must be at least $MIN_FRAGMENT bytes" }
    return Sequence {
        // The first frame gets sequence number 1 because the encoder increments before emitting.
        val encoder = UREncoder(ur, maxFragment, MIN_FRAGMENT, 0L)
        generateSequence { encoder.nextPart().uppercase() }.iterator()
    }
}
