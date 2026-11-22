package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.ui.send.SignedSendSteps
import kotlinx.coroutines.runBlocking

/** A submission that the network has accepted, so the screen shows the Sent state with the id [id]. */
internal fun sentSubmit(id: String): SubmitMachine<Unit, String> =
    SubmitMachine<Unit, String>(Unit, { id }, { SubmitFailure(it.message.orEmpty()) }).also { runBlocking { it.submit() } }

/** A submission that the network refused with [message], so the screen shows the Failed state. */
internal fun refusedSubmit(message: String): SubmitMachine<Unit, String> =
    SubmitMachine<Unit, String>(Unit, { error(message) }, { SubmitFailure(message) }).also { runBlocking { it.submit() } }

/**
 * The signed-transaction pages of a chain, with no-op actions, for the goldens. Use it for a submission that is already sent or refused: an idle one
 * would count down to its own send, which a golden must not wait for.
 */
@Composable
internal fun SubmitFixture(
    machine: SubmitMachine<*, String>,
    headline: String,
    amount: String,
    recipient: String,
    network: String,
    timing: String,
    explorerUrl: (String) -> String? = { null },
) {
    SignedSendSteps(
        machine = machine,
        headline = headline,
        amount = amount,
        recipient = recipient,
        network = network,
        timing = timing,
        fromName = "Account 1",
        explorerUrl = explorerUrl,
        onExplorer = {},
        onSent = {},
        onStop = {},
        onDone = {},
        onReview = {},
        backLabel = "Review",
        offline = null,
        onLock = {},
    )
}
