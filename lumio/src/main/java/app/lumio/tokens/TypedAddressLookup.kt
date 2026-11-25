package app.lumio.tokens

import app.lumio.evm.RpcFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lookup of a contract address the owner types or pastes on Add Token. It starts once the text is a valid address: after [PAUSE_MS] without a
 * change, or at once when the owner leaves the field ([commit]). A change of the text cancels a lookup that waits or runs. A lookup that does not
 * answer within [TIMEOUT_MS] fails as an unreachable endpoint, because the read queue has no timeout of its own. [onState] reports each step.
 */
internal class TypedAddressLookup(
    private val scope: CoroutineScope,
    private val read: suspend (contract: String) -> Token,
    private val onState: (State) -> Unit,
) {
    sealed interface State {
        /** No lookup waits or runs, and the text is not a valid address. */
        data object Idle : State

        /** The lookup runs now. */
        data object Checking : State

        /** The lookup ended with a token, or with the owner's reason it failed. */
        data class Done(val result: Result<Token>) : State
    }

    private var text = ""
    private var job: Job? = null

    /** The contract the lookup of the current text answered. An unreachable endpoint answers nothing, so leaving the field tries it again. */
    private var answered: String? = null

    /** The owner's text changed, by typing or by a paste. A lookup of the old text is cancelled. */
    fun changed(next: String) {
        if (next == text) return
        text = next
        job?.cancel()
        job = null
        answered = null
        onState(State.Idle)
        validContract(next)?.let { start(it, PAUSE_MS) }
    }

    /**
     * The owner left the field or pressed Done. A text that is not an address reports why, as the owner's words. A lookup waiting or running for
     * this text is left to finish, so no lookup repeats.
     */
    fun commit() {
        if (job?.isActive == true) return
        val contract = try {
            CustomTokenLookup.contractOf(text)
        } catch (e: IllegalArgumentException) {
            onState(State.Done(Result.failure(e)))
            return
        }
        if (answered == contract) return
        start(contract, 0L)
    }

    /** The owner dismissed the token the lookup found. The next [commit] looks the address up again. */
    fun forget() {
        answered = null
    }

    private fun start(contract: String, pauseMs: Long) {
        job = scope.launch {
            delay(pauseMs)
            onState(State.Checking)
            val result = attempt(contract)
            ensureActive()
            val unreachable = (result.exceptionOrNull() as? RpcFailure)?.reason == RpcFailure.Reason.UNREACHABLE
            answered = contract.takeUnless { unreachable }
            onState(State.Done(result))
        }
    }

    /** One read of [contract], given [TIMEOUT_MS] to answer. Cancellation passes through, so a cancelled lookup reports nothing. */
    private suspend fun attempt(contract: String): Result<Token> = try {
        Result.success(withTimeoutOrNull(TIMEOUT_MS) { read(contract) } ?: throw RpcFailure(RpcFailure.Reason.UNREACHABLE, "The endpoint did not answer."))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun validContract(address: String): String? = runCatching { CustomTokenLookup.contractOf(address) }.getOrNull()

    internal companion object {
        /** How long the text must stay unchanged before a valid address is looked up. */
        const val PAUSE_MS = 300L

        /** How long a lookup may take, its wait in the read queue included, before it fails as unreachable. */
        const val TIMEOUT_MS = 15_000L
    }
}
