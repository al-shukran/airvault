package app.lumio.pair

import app.lensly.core.ur.UrScanner

/** What one scanned text did to a pairing scan. */
sealed interface ScanStep {
    /** A frame of an animated code was read. [fraction] is how much of the code is in, from 0 to 1. */
    data class Reading(val fraction: Float) : ScanStep

    /** The code is whole, and it is a pairing. */
    data class Complete(val pairing: Pairing) : ScanStep

    /** The code is whole, and Lumio refuses it. The owner can scan again with a new [PairScan]. */
    data class Refused(val reason: String) : ScanStep

    /** The text is not a frame of the code being read, so it changes nothing. */
    data object Ignored : ScanStep
}

/**
 * Reads one pairing code from the camera's text: a single QR code, or the frames of an animated one, in any order. The frames go through
 * the shared [UrScanner], which refuses a frame that breaks its limits. Once the code is whole, [onText] ignores what comes next.
 */
class PairScan {
    private val scanner = UrScanner()
    private var finished = false

    fun onText(text: String): ScanStep {
        if (finished || !scanner.receive(text)) return ScanStep.Ignored
        val ur = scanner.result() ?: return ScanStep.Reading(scanner.progress.toFloat())
        finished = true
        return try {
            ScanStep.Complete(PairImport.of(ur))
        } catch (e: IllegalArgumentException) {
            ScanStep.Refused(e.message ?: "This is not a Lensly pairing code.")
        }
    }
}
