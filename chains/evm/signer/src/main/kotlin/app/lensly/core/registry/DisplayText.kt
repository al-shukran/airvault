package app.lensly.core.registry

import app.airvault.core.model.isSingleLinePrintable

/** The longest label a registry shows: a wallet name, an ecosystem, a code format or a primary type. */
internal const val LABEL_MAX = 64

/** The longest note, reason or limitation a registry shows. */
internal const val NOTE_MAX = 240

/**
 * True for 1 to [max] characters that are not blank and are all printable on one line (see [isSingleLinePrintable]), so no spoofing mark can
 * hide in text the owner reads on a warning or a support note.
 */
fun isDisplayText(value: String, max: Int): Boolean =
    value.isNotBlank() && value.length <= max && isSingleLinePrintable(value)
