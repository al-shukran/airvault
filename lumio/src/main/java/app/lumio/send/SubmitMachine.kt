package app.lumio.send

import app.lumio.data.provider.ProviderError
import app.lumio.evm.RpcFailure
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

    /**
     * The network refused the transaction, or could not be reached. [retryable] is false when these same bytes can never be accepted. [detail] is what
     * the failure was, for the page's Technical Details: no key and no address. [unknown] is true when the network may have taken the transaction
     * before the failure: a timeout, a dropped connection, or an answer that could not be read.
     */
    class Failed(val message: String, val retryable: Boolean, val detail: String, val unknown: Boolean = false) : SubmitState<Nothing>

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
            SubmitState.Failed(failure.message, failure.retryable, technicalDetail(e), outcomeUnknown(e))
        }
    }
}

/** True when [error], or a cause of it, is a failure after which the network may hold the transaction: a transient failure or an unreadable answer. */
internal fun outcomeUnknown(error: Throwable): Boolean =
    generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).any { it is ProviderError.Transient || it is ProviderError.Unreadable }

/**
 * What [error] was, as one line for Technical Details: the type of each error in its chain, and the message of the types whose words are Lumio's own
 * (a refusal, an endpoint's failure, an invalid input). The message of any other error stays out, since it may carry an address of the endpoint.
 */
internal fun technicalDetail(error: Throwable): String =
    generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).joinToString("\n") { cause ->
        val name = cause::class.simpleName ?: "Error"
        val words = cause.message?.takeIf { cause is RpcFailure || cause is ProviderError || cause is IllegalArgumentException }
        if (words == null) name else "$name: $words"
    }

private const val MAX_CAUSES = 4
