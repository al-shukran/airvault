package app.lensly.ui.flow

import kotlinx.coroutines.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

/** The guard every chain's prepare runs its check through: a RuntimeException is a refusal, and a cancellation or a locked wallet is not. */
class GuardedCheckTest {
    private val refused: (String) -> String = { "refused: $it" }

    @Test
    fun aCheckThatReturnsPassesItsResultThrough() {
        assertEquals("reviewed", guardedCheck("could not read", refused) { "reviewed" })
    }

    @Test
    fun aRuntimeExceptionBecomesTheRefusalWithTheUnreadableReason() {
        assertEquals("refused: could not read", guardedCheck("could not read", refused) { throw IllegalStateException("boom") })
        assertEquals("refused: could not read", guardedCheck("could not read", refused) { throw IllegalArgumentException("boom") })
    }

    @Test
    fun aCancellationPropagatesAndIsNotARefusal() {
        assertFailsWith<CancellationException> {
            guardedCheck("could not read", refused) { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun aLockedWalletPropagatesForTheFlowToShow() {
        assertFailsWith<WalletUnavailable> {
            guardedCheck("could not read", refused) { throw WalletUnavailable() }
        }
    }
}
