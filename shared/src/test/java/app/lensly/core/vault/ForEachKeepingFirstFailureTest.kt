package app.lensly.core.vault

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/** The erase loop of [VaultKeystore.deleteAll]: every alias is tried, and the first refusal is the one rethrown. */
class ForEachKeepingFirstFailureTest {
    @Test
    fun everyItemIsTriedWhenAnEarlierOneThrows() {
        val tried = mutableListOf<String>()
        val refusal = IllegalStateException("first")

        val thrown = assertFailsWith<IllegalStateException> {
            forEachKeepingFirstFailure(listOf("hw", "slot", "folder")) { alias ->
                tried += alias
                if (alias == "hw") throw refusal
            }
        }

        assertSame(refusal, thrown)
        assertEquals(listOf("hw", "slot", "folder"), tried)
    }

    @Test
    fun laterRefusalsAreKeptAsSuppressed() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")

        val thrown = assertFailsWith<IllegalStateException> {
            forEachKeepingFirstFailure(listOf("a", "b", "c")) { alias ->
                if (alias == "a") throw first
                if (alias == "c") throw second
            }
        }

        assertSame(first, thrown)
        assertTrue(thrown.suppressedExceptions.contains(second))
    }

    @Test
    fun nothingIsThrownWhenEveryItemSucceeds() {
        val tried = mutableListOf<String>()

        forEachKeepingFirstFailure(listOf("a", "b")) { tried += it }

        assertEquals(listOf("a", "b"), tried)
    }
}
