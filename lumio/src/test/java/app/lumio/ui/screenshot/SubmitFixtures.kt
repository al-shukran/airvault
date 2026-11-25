package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import app.lumio.data.config.TransactionSettings
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.ui.send.SignedSendSteps
import java.time.Instant
import kotlinx.coroutines.runBlocking

/** The clock of a golden: 14:02 UTC on 22 November 2026, so the timeline shows the same times on every run. */
internal val GOLDEN_CLOCK_MS = Instant.parse("2026-11-22T14:02:00Z").toEpochMilli()

/** A submission that the network has accepted, so the screen shows the Sent state with the id [id]. */
internal fun sentSubmit(id: String): SubmitMachine<Unit, String> = SubmitMachine<Unit, String>(
    Unit,
    { id },
    { SubmitFailure(it.message.orEmpty()) },
    clock = { GOLDEN_CLOCK_MS },
).also { runBlocking { it.submit() } }

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
        recipientAddress = null,
        network = network,
        timing = timing,
        fromName = "Account 1",
        arrival = null,
        explorerUrl = explorerUrl,
        onExplorer = {},
        onSent = {},
        onStop = {},
        onDone = {},
        onReview = {},
        backLabel = "Review",
        lastChanceSeconds = TransactionSettings.DEFAULT_LAST_CHANCE_SECONDS,
        offline = null,
        onLock = {},
    )
}
