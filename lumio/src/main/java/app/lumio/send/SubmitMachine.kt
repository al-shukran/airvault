package app.lumio.send

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the submission of one signed transaction stands. Nothing is sent before the owner submits it. */
sealed interface SubmitState<out Id> {
    /** Signed and held in memory. Nothing has been sent. */
    data object Idle : SubmitState<Nothing>

    /** The network is asked to accept the signed transaction. */
    data object Submitting : SubmitState<Nothing>

    /** The network refused the transaction, or could not be reached. [retryable] is false when these same bytes can never be accepted. */
    class Failed(val message: String, val retryable: Boolean) : SubmitState<Nothing>

    /** The network accepted the transaction and reported its id, at [sentAtMs] (epoch milliseconds). */
    class Sent<Id>(val id: Id, val sentAtMs: Long) : SubmitState<Id>
}

/** How a failed submission reads to the owner: the words, and whether the same signed bytes may be sent again. */
class SubmitFailure(val message: String, val retryable: Boolean = true)

/**
 * One signed transaction, held until the owner submits it. [submit] is the only way to broadcast, and it sends [signed] as it is: a retry re-broadcasts
 * the same bytes and never signs again. [describe] turns a failure into the words the owner reads. The machine is made when the signature is checked, so
 * [signedAtMs] is the moment the transaction was signed; [clock] gives the epoch milliseconds of each moment.
 */
class SubmitMachine<S, Id>(
    val signed: S,
    private val broadcast: suspend (S) -> Id,
    private val describe: (Throwable) -> SubmitFailure,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val current = MutableStateFlow<SubmitState<Id>>(SubmitState.Idle)

    val signedAtMs: Long = clock()

    val state: StateFlow<SubmitState<Id>> = current.asStateFlow()

    /** Sends [signed] when the transaction is idle, or failed and retryable. A tap while it is submitting, or after it was sent, does nothing. */
    suspend fun submit() {
        val now = current.value
        val allowed = now is SubmitState.Idle || (now is SubmitState.Failed && now.retryable)
        if (!allowed) return
        current.value = SubmitState.Submitting
        current.value = try {
            SubmitState.Sent(broadcast(signed), clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = describe(e)
            SubmitState.Failed(failure.message, failure.retryable)
        }
    }
}
