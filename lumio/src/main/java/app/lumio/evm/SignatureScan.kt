package app.lumio.evm

import app.lensly.core.ur.UrScanner
import com.sparrowwallet.hummingbird.UR

/** What one scanned text did to a scan of Lensly's signature. */
sealed interface SignatureStep {
    /** A frame of an animated code was read. [fraction] is how much of the code is in, from 0 to 1. */
    data class Reading(val fraction: Float) : SignatureStep

    /** The code is whole. What it says is checked when the signature is applied to the send. */
    class Complete(val ur: UR) : SignatureStep

    /** The text is not a frame of a UR, so it changes nothing. */
    data object Ignored : SignatureStep
}

/**
 * Reads one signature code from the camera's text: a single QR code, or the frames of an animated one, in any order. The frames go through
 * the shared [UrScanner]. Once the code is whole, later text is ignored.
 */
class SignatureScan {
    private val scanner = UrScanner()
    private var finished = false

    fun onText(text: String): SignatureStep {
        if (finished || !scanner.receive(text)) return SignatureStep.Ignored
        val ur = scanner.result() ?: return SignatureStep.Reading(scanner.progress.toFloat())
        finished = true
        return SignatureStep.Complete(ur)
    }
}
