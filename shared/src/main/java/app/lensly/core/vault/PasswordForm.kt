package app.lensly.core.vault

import java.nio.CharBuffer
import java.text.Normalizer

/**
 * The Unicode form of a password. A password is stored (derived) from its NFC form, so the same visible text derives one key whichever
 * form the keyboard emits. A vault made before this rule derived from the raw text, so an unlock also tries the typed text as it came.
 */
object PasswordForm {
    /** True when [password] is NFC already. Nothing is copied, so the common case, ASCII or NFC text, leaves no copy of the password. */
    fun isNfc(password: CharArray): Boolean = Normalizer.isNormalized(CharBuffer.wrap(password), Normalizer.Form.NFC)

    /** The NFC form of [password] as a new array, or null when [password] is NFC already. The caller zeroes the result. */
    fun nfcOrNull(password: CharArray): CharArray? {
        if (isNfc(password)) return null
        return Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC).toCharArray()
    }

    /** True when [first] and [second] are the same text up to Unicode form. */
    fun sameText(first: CharArray, second: CharArray): Boolean {
        val a = nfcOrNull(first)
        val b = nfcOrNull(second)
        try {
            return (a ?: first).contentEquals(b ?: second)
        } finally {
            a?.fill('\u0000')
            b?.fill('\u0000')
        }
    }
}
