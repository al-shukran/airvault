package app.lumio.data.provider

import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Why a provider call failed. The scheduler reads the class to choose what happens next: cool the provider, try another, or give up.
 */
sealed class ProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The provider refused for rate reasons. [retryAfterMs] is its Retry-After, when it sent one. */
    class RateLimited(val retryAfterMs: Long? = null) : ProviderError("rate limited")

    /** A failure that may pass: a timeout, a server error, a dropped connection. */
    class Transient(cause: Throwable? = null) : ProviderError("transient failure", cause) {
        /**
         * True when a cause shows that the request never left: the host did not resolve, the connection was refused, or the TLS handshake failed. It
         * reads the chain, since a coroutine that recovers a stack trace wraps this error in a copy of itself. An error that carries a suppressed
         * failure of another kind, such as a dropped connection, says an earlier attempt may have sent it, so the answer is false.
         */
        val neverSent: Boolean
            get() {
                val chain = generateSequence(cause) { it.cause }.take(MAX_CAUSES).toList()
                return chain.any { it.isUnsent() } && chain.none { error -> error.suppressed.any { !it.isUnsent() } }
            }
    }

    /** The provider does not serve this request after all. It is skipped for this request, and this is not a failure. */
    class Unsupported : ProviderError("unsupported request")

    /** The provider has no answer for this request. That is an answer, so it is not retried. */
    class NotFound : ProviderError("not found")

    /** A failure another provider may not share, such as a rejected key. The provider is skipped for this request. */
    open class Fatal(message: String) : ProviderError(message)

    /**
     * A [Fatal] for an answer that was a success (2xx) but cannot be read. The provider may have accepted the request, so the scheduler does not send
     * a [Broadcast] again after it. Every other request treats it as any [Fatal].
     */
    class Unreadable(message: String) : Fatal(message)
}

private const val MAX_CAUSES = 4

private fun Throwable.isUnsent() = this is UnknownHostException || this is ConnectException || this is SSLHandshakeException
