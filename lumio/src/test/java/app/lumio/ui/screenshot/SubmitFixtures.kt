package app.lumio.ui.screenshot

import androidx.compose.runtime.Composable
import app.lumio.send.SubmitFailure
import app.lumio.send.SubmitMachine
import app.lumio.ui.send.SubmitScreen
import app.lumio.ui.send.SummaryRow
import kotlinx.coroutines.runBlocking

/** A submission that the network has accepted, so the screen shows the Sent state with the id [id]. */
internal fun sentSubmit(id: String): SubmitMachine<Unit, String> =
    SubmitMachine<Unit, String>(Unit, { id }, { SubmitFailure(it.message.orEmpty()) }).also { runBlocking { it.submit() } }

/** A submission that the network refused with [message], so the screen shows the Failed state. */
internal fun refusedSubmit(message: String): SubmitMachine<Unit, String> =
    SubmitMachine<Unit, String>(Unit, { error(message) }, { SubmitFailure(message) }).also { runBlocking { it.submit() } }

/** A signed transaction that nothing has been sent for, so the screen shows the Idle state with Submit. */
internal fun idleSubmit(): SubmitMachine<Unit, String> = SubmitMachine<Unit, String>(Unit, { error("not sent") }, { SubmitFailure("") })

/** The Submit screen with no-op actions, for the goldens. */
@Composable
internal fun SubmitFixture(
    machine: SubmitMachine<*, String>,
    rows: List<SummaryRow>,
    explorerUrl: (String) -> String? = { null },
    status: String? = null,
    notice: String? = null,
) {
    SubmitScreen(
        machine = machine,
        rows = rows,
        explorerUrl = explorerUrl,
        status = status,
        explorerNotice = notice,
        onSubmit = {},
        onOpenExplorer = {},
        onStartOver = {},
        onBack = {},
        onDone = {},
    )
}
