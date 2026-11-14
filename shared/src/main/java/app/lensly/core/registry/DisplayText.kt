package app.lensly.core.registry

/** The longest label a registry shows: a wallet name, an ecosystem, a code format or a primary type. */
internal const val LABEL_MAX = 64

/** The longest note, reason or limitation a registry shows. */
internal const val NOTE_MAX = 240

/**
 * True for 1 to [max] characters that are not blank and hold no control or format character, so no spoofing mark can hide in text
 * the owner reads on a warning or a support note.
 */
internal fun isDisplayText(value: String, max: Int): Boolean =
    value.isNotBlank() &&
        value.length <= max &&
        value.none { it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT }
