package app.lumio.data.provider

/**
 * Why a provider call failed. The scheduler reads the class to choose what happens next: cool the provider, try another, or give up.
 */
sealed class ProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The provider refused for rate reasons. [retryAfterMs] is its Retry-After, when it sent one. */
    class RateLimited(val retryAfterMs: Long? = null) : ProviderError("rate limited")

    /** A failure that may pass: a timeout, a server error, a dropped connection. */
    class Transient(cause: Throwable? = null) : ProviderError("transient failure", cause)

    /** The provider does not serve this request after all. It is skipped for this request, and this is not a failure. */
    class Unsupported : ProviderError("unsupported request")

    /** The provider has no answer for this request. That is an answer, so it is not retried. */
    class NotFound : ProviderError("not found")

    /** A failure another provider may not share, such as a rejected key. The provider is skipped for this request. */
    class Fatal(message: String) : ProviderError(message)
}
