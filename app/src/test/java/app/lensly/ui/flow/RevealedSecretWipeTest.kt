package app.lensly.ui.flow

import app.lensly.core.vault.FakeClock
import app.lensly.core.wallet.Mnemonic
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * A Mnemonic wallet's secret holds its words in a [Mnemonic]. Clearing the secret wipes that Mnemonic, whether the
 * countdown ends or the owner leaves, and a screen that still holds the Mnemonic reads it as empty.
 */
class RevealedSecretWipeTest {

    private val words = List(23) { "abandon" } + "art"

    /** The Mnemonic the secret holds, read by reflection so the test can look at it after the secret has cleared it. */
    private fun heldMnemonic(secret: RevealedSecret): Mnemonic =
        RevealedSecret::class.java.getDeclaredField("words").run {
            isAccessible = true
            get(secret) as Mnemonic
        }

    @Test
    fun wipingTheSecretZeroesTheMnemonicItHolds() {
        val secret = RevealedSecret.ofMnemonic(words, null)
        val held = heldMnemonic(secret)
        assertEquals(Mnemonic.WORD_COUNT, held.size)
        assertEquals("art", secret.page(RevealPart.WORDS, Mnemonic.WORD_COUNT - 1))

        secret.wipe()

        assertEquals(0, held.size)
        assertEquals(0, secret.pageCount(RevealPart.WORDS))
        assertFailsWith<IndexOutOfBoundsException> { secret.page(RevealPart.WORDS, 0) }
    }

    @Test
    fun theCountdownWipesTheMnemonicTheScreenShows() {
        val clock = FakeClock()
        val secret = RevealedSecret.ofMnemonic(words, null)
        val held = heldMnemonic(secret)
        val flow = RevealFlow(SecretPorts(secret)) { clock.elapsedMillis() }

        runBlocking {
            flow.submitPassword(CharArray(0))
            flow.show()
        }
        assertIs<RevealStep.Revealed>(flow.state.value)
        assertEquals(Mnemonic.WORD_COUNT, held.size)

        clock.advance(RevealFlow.TIMEOUT_MS)
        flow.tick()

        assertIs<RevealStep.Cleared>(flow.state.value)
        assertEquals(0, held.size)
    }

    @Test
    fun leavingTheScreenWipesTheMnemonicTheScreenShows() {
        val clock = FakeClock()
        val secret = RevealedSecret.ofMnemonic(words, null)
        val held = heldMnemonic(secret)
        val flow = RevealFlow(SecretPorts(secret)) { clock.elapsedMillis() }

        runBlocking {
            flow.submitPassword(CharArray(0))
            flow.show()
        }
        flow.leave(ClearReason.DONE)

        assertEquals(RevealStep.Cleared(ClearReason.DONE), flow.state.value)
        assertEquals(0, held.size)
    }
}

/** Ports for a flow whose password is right and whose secret is [secret]. */
private class SecretPorts(private val secret: RevealedSecret) : RevealPorts {
    override suspend fun checkPassword(password: CharArray): PasswordCheck = PasswordCheck.Accepted

    override suspend fun loadSecret(): SecretLoad = SecretLoad.Loaded(secret)
}
