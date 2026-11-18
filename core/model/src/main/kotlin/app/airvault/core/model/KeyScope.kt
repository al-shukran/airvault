package app.airvault.core.model

/** The curves a key scope can name (spec 2.1). Each curve has its own derivation rules. */
enum class Curve { SECP256K1, ED25519_SLIP10, ED25519_BIP32 }

/**
 * The paths one app may derive on one [curve] (spec 2.1). [purpose] is shown during code review.
 *
 * The registry checks each prefix against spec 2.2. The core checks every signing call at run time.
 */
data class KeyScope(
    val curve: Curve,
    val pathPrefixes: List<PathTemplate>,
    val purpose: String,
)

/**
 * A derivation path prefix, for example `m/44'/283'`. A trailing `'` marks a hardened level. A level written `*` or
 * `*'` is a wildcard, hardened or not. The text must be well formed here. Which paths an app may use is checked by
 * [ChainRegistry].
 */
data class PathTemplate(val text: String) {
    val parts: List<PathPart> = parsePath(text)
}

/** One level of a [PathTemplate]: a fixed BIP-32 index, or a wildcard that matches any index with the same hardening. */
sealed interface PathPart {
    val hardened: Boolean

    data class Fixed(val index: Int, override val hardened: Boolean) : PathPart

    data class Wildcard(override val hardened: Boolean) : PathPart
}

private val PATH_INDEX_FORMAT = Regex("[0-9]{1,10}")

private fun parsePath(text: String): List<PathPart> {
    require(text == "m" || text.startsWith("m/")) { "a path template starts with m: $text" }
    if (text == "m") return emptyList()
    return text.removePrefix("m/").split('/').map { level ->
        val hardened = level.endsWith('\'')
        val digits = level.removeSuffix("'")
        if (digits == "*") {
            PathPart.Wildcard(hardened)
        } else {
            require(PATH_INDEX_FORMAT.matches(digits) && digits.toLong() <= Int.MAX_VALUE) {
                "path level is not a BIP-32 index: $level in $text"
            }
            PathPart.Fixed(digits.toInt(), hardened)
        }
    }
}
