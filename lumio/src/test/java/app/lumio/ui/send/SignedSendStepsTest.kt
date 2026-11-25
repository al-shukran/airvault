package app.lumio.ui.send

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.lensly.ui.theme.ClearAirTheme
import app.lensly.ui.theme.LumenTheme
import app.lumio.data.provider.ProviderError
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Last Chance pause of a signed send, as the Transactions settings set it: the countdown runs for that many seconds, then Lumio broadcasts the
 * transaction once. Zero sends at once.
 */
@RunWith(RobolectricTestRunner::class)
class SignedSendStepsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun machine(broadcasts: AtomicInteger): SubmitMachine<Unit, String> = SubmitMachine(
        Unit,
        { broadcasts.incrementAndGet(); "id" },
        { SubmitFailure(it.message.orEmpty()) },
        clock = { 0L },
    )

    private fun show(machine: SubmitMachine<*, String>, seconds: Int) {
        compose.setContent {
            LumenTheme {
                ClearAirTheme {
                    SignedSendSteps(
                        machine = machine,
                        headline = "Your MON Is on Its Way",
                        amount = "0.25 MON",
                        recipient = "FelixStone",
                        network = "Monad Testnet",
                        timing = "About 1 second",
                        fromName = "Account 1",
                        arrival = null,
                        explorerUrl = { null },
                        onExplorer = {},
                        onSent = {},
                        onStop = {},
                        onDone = {},
                        onReview = {},
                        backLabel = "Review",
                        lastChanceSeconds = seconds,
                        offline = null,
                        onLock = {},
                    )
                }
            }
        }
    }

    @Test
    fun theSendWaitsForThePauseThenBroadcastsOnce() {
        compose.mainClock.autoAdvance = false
        val broadcasts = AtomicInteger()
        show(machine(broadcasts), seconds = 2)

        compose.waitForIdle()
        compose.onAllNodesWithText("Sending 0.25 MON to FelixStone in 2 seconds.").assertCountEquals(1)
        assertEquals(0, broadcasts.get())

        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
        assertEquals(1, broadcasts.get())
    }

    private fun failing(error: Exception): SubmitMachine<Unit, String> =
        SubmitMachine(Unit, { throw error }, { SubmitFailure("The endpoint did not answer.") }, clock = { 0L })

    @Test
    fun aBroadcastThatMayHaveLeftDoesNotSayNothingWasSent() {
        compose.mainClock.autoAdvance = false
        show(failing(ProviderError.Transient(SocketTimeoutException("timeout"))), seconds = 0)

        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500L)
        compose.onAllNodesWithText("We Couldn't Confirm It Was Sent", ignoreCase = true).assertCountEquals(1)
        compose.onAllNodesWithText("Nothing Left Your Account", ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Nothing was sent.", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun aRefusedBroadcastStillSaysNothingLeftTheAccount() {
        compose.mainClock.autoAdvance = false
        show(failing(ProviderError.Fatal("The endpoint returned a JSON-RPC error.")), seconds = 0)

        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500L)
        compose.onAllNodesWithText("Nothing Left Your Account", ignoreCase = true).assertCountEquals(1)
        compose.onAllNodesWithText("We Couldn't Confirm It Was Sent", ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun zeroSendsAtOnceWithoutAPause() {
        compose.mainClock.autoAdvance = false
        val broadcasts = AtomicInteger()
        show(machine(broadcasts), seconds = 0)

        compose.waitForIdle()
        assertEquals(1, broadcasts.get())
    }
}
