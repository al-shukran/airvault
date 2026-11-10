package app.lensly.core.vault

import com.nulabinc.zxcvbn.Zxcvbn
import java.nio.CharBuffer
import java.util.Arrays
import kotlin.math.ln

/** Why [PasswordPolicy] refused a password. */
enum class PolicyReason { TOO_SHORT, WEAK_SCORE, LOW_ENTROPY }

/** Outcome of [PasswordPolicy.check]. */
sealed interface PolicyResult {
    data object Accepted : PolicyResult

    data class Refused(val reason: PolicyReason) : PolicyResult
}

/**
 * App Password policy: at least [MIN_CODE_POINTS] code points, a zxcvbn score of at least
 * [MIN_ZXCVBN_SCORE], and an estimated guess entropy of at least [MIN_ENTROPY_BITS] bits.
 * The checks run in that order and the first failure is reported.
 */
object PasswordPolicy {
    const val MIN_CODE_POINTS = 12
    const val MIN_ZXCVBN_SCORE = 3
    const val MIN_ENTROPY_BITS = 50.0

    private val LOG2_10 = ln(10.0) / ln(2.0)

    private val zxcvbn: Zxcvbn by lazy { Zxcvbn() }

    /** Evaluates [password] and leaves the array as it was. */
    fun check(password: CharArray): PolicyResult {
        val codePoints = Character.codePointCount(CharBuffer.wrap(password), 0, password.size)
        if (codePoints < MIN_CODE_POINTS) return PolicyResult.Refused(PolicyReason.TOO_SHORT)
        // zxcvbn wipes the sequence it is given, so it gets a copy and the caller's array is untouched.
        val copy = password.copyOf()
        try {
            val strength = zxcvbn.measure(CharBuffer.wrap(copy))
            try {
                return evaluate(codePoints, strength.score, strength.guessesLog10)
            } finally {
                strength.wipe()
            }
        } finally {
            Arrays.fill(copy, '\u0000')
        }
    }

    /** Pure threshold logic, separated from zxcvbn so that every branch can be tested directly. */
    internal fun evaluate(codePoints: Int, score: Int, guessesLog10: Double): PolicyResult = when {
        codePoints < MIN_CODE_POINTS -> PolicyResult.Refused(PolicyReason.TOO_SHORT)
        score < MIN_ZXCVBN_SCORE -> PolicyResult.Refused(PolicyReason.WEAK_SCORE)
        guessesLog10 * LOG2_10 < MIN_ENTROPY_BITS -> PolicyResult.Refused(PolicyReason.LOW_ENTROPY)
        else -> PolicyResult.Accepted
    }
}
