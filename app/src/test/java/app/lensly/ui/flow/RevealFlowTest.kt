package app.lensly.ui.flow

import app.lensly.core.vault.FailureDelay
import app.lensly.core.vault.FailureState
import app.lensly.core.vault.FakeClock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

private const val PASSWORD = "cobalt lantern marsh vivid tundra pepper"
private val WORDS = List(23) { "abandon" } + "art"

/** Fakes for the app's side of the flow. [check] answers each password check, and [load] answers each read. */
private class FakeRevealPorts(
    private val check: (CharArray) -> PasswordCheck = { PasswordCheck.Accepted },
    private val load: () -> SecretLoad = { SecretLoad.Loaded(RevealedSecret.ofMnemonic(WORDS, null)) },
) : RevealPorts {
    val checked = mutableListOf<CharArray>()
    var loads = 0
        private set

    override suspend fun checkPassword(password: CharArray): PasswordCheck {
        checked += password
        return check(password)
    }

    override suspend fun loadSecret(): SecretLoad {
        loads++
        return load()
    }
}

/** Models the vault's Failure Delay with the real policy: a wrong password adds a failure, and a check made during the delay is refused. */
private class DelayedVault(private val clock: FakeClock, private val password: String) {
    private val createdWall = clock.wallMillis()
    private var state = FailureState(
        seq = 1,
        failures = 0,
        lastFailureWallMs = clock.wallMillis(),
        lastFailureElapsedMs = clock.elapsedMillis(),
    )

    fun check(entered: CharArray): PasswordCheck {
        if (FailureDelay(createdWall).isBlocked(state, clock)) return PasswordCheck.Rejected
        if (String(entered) != password) {
            state = state.copy(
                seq = state.seq + 1,
                failures = state.failures + 1,
                lastFailureWallMs = clock.wallMillis(),
                lastFailureElapsedMs = clock.elapsedMillis(),
            )
            return PasswordCheck.Rejected
        }
        state = state.copy(seq = state.seq + 1, failures = 0)
        return PasswordCheck.Accepted
    }
}

/** A flow clock that the tests move by hand. */
private class Now(var ms: Long = 5_000L)

class RevealFlowTest {

    @Test
    fun startsOnThePasswordStepAndReadsNothing() {
        val ports = FakeRevealPorts()
        val flow = RevealFlow(ports) { 0L }
        assertEquals(RevealStep.Password(busy = false), flow.state.value)
        assertEquals(0, ports.loads)
    }

    @Test
    fun rightPasswordOpensTheWarningWithoutReadingTheSecret() {
        runBlocking {
            val ports = FakeRevealPorts()
            val flow = RevealFlow(ports) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            assertEquals(RevealStep.Warning, flow.state.value)
            assertEquals(0, ports.loads)
        }
    }

    @Test
    fun thePasswordIsZeroedOnceItHasBeenChecked() {
        runBlocking {
            val ports = FakeRevealPorts()
            val flow = RevealFlow(ports) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            assertTrue(ports.checked.single().all { it == '\u0000' })
        }
    }

    @Test
    fun wrongPasswordStaysOnThePasswordStepWithNothingShown() {
        runBlocking {
            val ports = FakeRevealPorts(check = { PasswordCheck.Rejected })
            val flow = RevealFlow(ports) { 0L }
            flow.submitPassword("wrong".toCharArray())
            assertEquals(RevealStep.Password(busy = false), flow.state.value)
            assertEquals(0, ports.loads)
        }
    }

    @Test
    fun anUnavailableVaultEndsWithoutRevealing() {
        runBlocking {
            val ports = FakeRevealPorts(check = { PasswordCheck.Unavailable })
            val flow = RevealFlow(ports) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            assertEquals(RevealStep.Cleared(ClearReason.UNAVAILABLE), flow.state.value)
            assertEquals(0, ports.loads)
        }
    }

    @Test
    fun showReadsTheWordsAndShowsTheFirstOne() {
        runBlocking {
            val now = Now()
            val ports = FakeRevealPorts()
            val flow = RevealFlow(ports) { now.ms }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()
            val shown = assertIs<RevealStep.Revealed>(flow.state.value)
            assertEquals(RevealPart.WORDS, shown.part)
            assertEquals(0, shown.index)
            assertEquals(60, shown.secondsLeft)
            assertEquals("abandon", shown.secret.page(RevealPart.WORDS, 0))
            assertEquals(1, ports.loads)
        }
    }

    @Test
    fun movingStepsOneWordAtATimeAndDoesNotWrap() {
        runBlocking {
            val flow = RevealFlow(FakeRevealPorts()) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()
            flow.move(1)
            assertEquals(1, assertIs<RevealStep.Revealed>(flow.state.value).index)
            flow.move(-1)
            flow.move(-1)
            assertEquals(0, assertIs<RevealStep.Revealed>(flow.state.value).index)
            repeat(30) { flow.move(1) }
            assertEquals(23, assertIs<RevealStep.Revealed>(flow.state.value).index)
        }
    }

    @Test
    fun theCountdownClearsTheSecretAfterSixtySeconds() {
        runBlocking {
            val now = Now()
            val flow = RevealFlow(FakeRevealPorts()) { now.ms }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()
            val secret = assertIs<RevealStep.Revealed>(flow.state.value).secret

            now.ms += 59_000L
            flow.tick()
            assertEquals(1, assertIs<RevealStep.Revealed>(flow.state.value).secondsLeft)

            now.ms += 1_000L
            flow.tick()
            assertEquals(RevealStep.Cleared(ClearReason.TIMEOUT), flow.state.value)
            assertTrue(secret.isWiped)
            assertEquals(0, secret.pageCount(RevealPart.WORDS))
        }
    }

    @Test
    fun aPageChangeRestartsTheCountdown() {
        runBlocking {
            val now = Now()
            val flow = RevealFlow(FakeRevealPorts()) { now.ms }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()

            now.ms += 50_000L
            flow.move(1)
            now.ms += 50_000L
            flow.tick()
            assertEquals(10, assertIs<RevealStep.Revealed>(flow.state.value).secondsLeft)

            now.ms += 10_000L
            flow.tick()
            assertEquals(RevealStep.Cleared(ClearReason.TIMEOUT), flow.state.value)
        }
    }

    @Test
    fun leavingClearsTheSecretAtOnceAndNothingRunsAfterwards() {
        runBlocking {
            val ports = FakeRevealPorts()
            val flow = RevealFlow(ports) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()
            val secret = assertIs<RevealStep.Revealed>(flow.state.value).secret

            flow.leave(ClearReason.DONE)
            assertEquals(RevealStep.Cleared(ClearReason.DONE), flow.state.value)
            assertTrue(secret.isWiped)

            flow.show()
            flow.move(1)
            flow.leave(ClearReason.LOCKED)
            assertEquals(RevealStep.Cleared(ClearReason.DONE), flow.state.value)
            assertEquals(1, ports.loads)
        }
    }

    @Test
    fun leavingOnTheWarningReadsNothing() {
        runBlocking {
            val ports = FakeRevealPorts()
            val flow = RevealFlow(ports) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.leave(ClearReason.DONE)
            assertEquals(RevealStep.Cleared(ClearReason.DONE), flow.state.value)
            assertEquals(0, ports.loads)
        }
    }

    @Test
    fun aVaultLockedWhileTheSecretIsReadEndsWithoutRevealing() {
        runBlocking {
            val flow = RevealFlow(FakeRevealPorts(load = { SecretLoad.Locked })) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()
            assertEquals(RevealStep.Cleared(ClearReason.VAULT_CLOSED), flow.state.value)
        }
    }

    @Test
    fun anUnreadableWalletEndsWithoutRevealing() {
        runBlocking {
            val flow = RevealFlow(FakeRevealPorts(load = { SecretLoad.Unavailable })) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()
            assertEquals(RevealStep.Cleared(ClearReason.UNAVAILABLE), flow.state.value)
        }
    }

    @Test
    fun aWrongPasswordDuringAFailureDelayIsRefusedAndTheDelayHoldsUntilItEnds() {
        runBlocking {
            val clock = FakeClock()
            val vault = DelayedVault(clock, PASSWORD)
            val ports = FakeRevealPorts(check = { vault.check(it) })
            val flow = RevealFlow(ports) { clock.elapsedMillis() }

            flow.submitPassword("wrong".toCharArray())
            assertEquals(RevealStep.Password(busy = false), flow.state.value)

            // The right password is refused during the 10 s learning-period delay, and looks exactly like a wrong one.
            flow.submitPassword(PASSWORD.toCharArray())
            assertEquals(RevealStep.Password(busy = false), flow.state.value)

            clock.advance(FailureDelay.LEARNING_CAP_MS - 1)
            flow.submitPassword(PASSWORD.toCharArray())
            assertEquals(RevealStep.Password(busy = false), flow.state.value)
            assertEquals(0, ports.loads)

            clock.advance(1)
            flow.submitPassword(PASSWORD.toCharArray())
            assertEquals(RevealStep.Warning, flow.state.value)
        }
    }

    @Test
    fun aPassphraseIsOfferedOnlyWhenThereIsOneAndItsWordsKeepTheirCase() {
        runBlocking {
            val withPassphrase = RevealedSecret.ofMnemonic(WORDS, "Correct  Horse")
            assertTrue(withPassphrase.hasPassphrase)
            assertEquals(3, withPassphrase.pageCount(RevealPart.PASSPHRASE))
            assertEquals("Correct", withPassphrase.page(RevealPart.PASSPHRASE, 0))
            assertEquals("(empty)", withPassphrase.page(RevealPart.PASSPHRASE, 1))
            assertEquals("Horse", withPassphrase.page(RevealPart.PASSPHRASE, 2))

            val plain = RevealedSecret.ofMnemonic(WORDS, "")
            assertFalse(plain.hasPassphrase)

            val flow = RevealFlow(FakeRevealPorts(load = { SecretLoad.Loaded(plain) })) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()
            flow.select(RevealPart.PASSPHRASE)
            assertEquals(RevealPart.WORDS, assertIs<RevealStep.Revealed>(flow.state.value).part)
        }
    }

    @Test
    fun selectingThePassphraseStartsItsFirstWordAndCanGoBackToTheWords() {
        runBlocking {
            val secret = RevealedSecret.ofMnemonic(WORDS, "two words")
            val flow = RevealFlow(FakeRevealPorts(load = { SecretLoad.Loaded(secret) })) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()
            flow.move(1)
            flow.select(RevealPart.PASSPHRASE)
            val onPassphrase = assertIs<RevealStep.Revealed>(flow.state.value)
            assertEquals(RevealPart.PASSPHRASE, onPassphrase.part)
            assertEquals(0, onPassphrase.index)
            flow.move(1)
            assertEquals("words", assertIs<RevealStep.Revealed>(flow.state.value).secret.page(RevealPart.PASSPHRASE, 1))
            flow.select(RevealPart.WORDS)
            assertEquals(RevealPart.WORDS, assertIs<RevealStep.Revealed>(flow.state.value).part)
        }
    }

    @Test
    fun aSingleKeyIsOnePageAndItsBytesAreZeroed() {
        runBlocking {
            val key = ByteArray(32) { 0x11 }
            val secret = RevealedSecret.ofKey(key)
            assertTrue(key.all { it == 0.toByte() })
            assertEquals(1, secret.pageCount(RevealPart.KEY))
            assertEquals("11".repeat(32), secret.page(RevealPart.KEY, 0))

            val flow = RevealFlow(FakeRevealPorts(load = { SecretLoad.Loaded(secret) })) { 0L }
            flow.submitPassword(PASSWORD.toCharArray())
            flow.show()
            val shown = assertIs<RevealStep.Revealed>(flow.state.value)
            assertEquals(RevealPart.KEY, shown.part)
            flow.move(1)
            assertEquals(0, assertIs<RevealStep.Revealed>(flow.state.value).index)
            flow.select(RevealPart.WORDS)
            assertEquals(RevealPart.KEY, assertIs<RevealStep.Revealed>(flow.state.value).part)
        }
    }

    @Test
    fun theSecretNeverAppearsInItsText() {
        val secret = RevealedSecret.ofMnemonic(WORDS, "hunter2")
        assertEquals("RevealedSecret(redacted)", secret.toString())
        val revealed = RevealStep.Revealed(secret, RevealPart.WORDS, index = 0, secondsLeft = 60)
        assertFalse(revealed.toString().contains("abandon"))
        assertFalse(revealed.toString().contains("hunter2"))
    }
}
