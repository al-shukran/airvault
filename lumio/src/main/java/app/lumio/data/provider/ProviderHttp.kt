package app.lumio.data.provider

import java.io.IOException
import java.math.BigInteger
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response

/** The longest answer a provider may send. A balance or a page of outputs is far below it. */
internal const val MAX_ANSWER_BYTES = 4L * 1024 * 1024

/**
 * The one cap on a Retry-After, for every provider: a longer wait is cut to it, so a huge header cannot push a cooldown out for days. The
 * scheduler then asks again sooner, and a provider that still refuses cools again.
 */
internal const val MAX_RETRY_AFTER_S = 3_600L

private const val SHAPE_MESSAGE = "The answer does not have the documented shape."
private val DIGITS = Regex("[0-9]{1,80}")
private val WHOLE = Regex("-?[0-9]{1,19}")
private val HEX64 = Regex("[0-9a-fA-F]{64}")

/**
 * The base URL a provider calls: [raw] when the owner set one, else [default]. A key must never travel in clear text to another host, so only
 * HTTPS is accepted, or plain HTTP to this machine. A URL with a user name, a query or a fragment is refused, and so is a malformed one.
 */
internal fun providerBase(raw: String?, default: String): HttpUrl? {
    val url = (raw?.trim()?.takeIf { it.isNotEmpty() } ?: default).toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedQuery != null || url.fragment != null) return null
    val local = url.host == "localhost" || url.host == "127.0.0.1"
    return url.takeIf { it.scheme == "https" || (it.scheme == "http" && local) }
}

/**
 * The client for one provider call. It is the app's client with redirects off, so a key that sits in a URL never follows a redirect to
 * another host.
 */
internal fun Http.noRedirects(): OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

/**
 * Sends [request] off the main thread and returns the answer text. A status that is not a success becomes the [ProviderError] that
 * [failureFor] names. An IOException is left for the scheduler, which reads it as transient. Cancelling the coroutine cancels the call, so a
 * request that the scheduler has given up on stops at once. [notFoundIsAnswer] is set for a lookup whose 404 means "no such thing".
 */
internal suspend fun fetchText(
    client: OkHttpClient,
    request: okhttp3.Request,
    banMs: Long?,
    notFoundIsAnswer: Boolean = false,
): String = suspendCancellableCoroutine { cont ->
    val call = client.newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(c: Call, e: IOException) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }

        override fun onResponse(c: Call, response: Response) {
            val result = runCatching {
                response.use { answer ->
                    if (!answer.isSuccessful) throw failureFor(answer.code, answer.header("Retry-After"), banMs, notFoundIsAnswer)
                    readAnswer(answer)
                }
            }
            if (cont.isActive) cont.resumeWith(result)
        }
    })
}

/**
 * The error for an HTTP status that is not a success. Every provider maps its statuses here, so each one reads them the same way.
 * - 402, 429, 430 and 435 are quota, rate or concurrency refusals: [ProviderError.RateLimited]. Its cooldown is the Retry-After, in seconds or as
 *   an HTTP date. A 429 with no Retry-After gets [banMs], when the provider documents a ban for it; otherwise the scheduler backs off on its own.
 * - 401 and 403 mean a rejected key, and 404 a path this provider does not serve: both are [ProviderError.Fatal], which skips the provider for
 *   the one request. A 404 that is an answer, such as an unknown block, is [ProviderError.NotFound] when [notFoundIsAnswer] is set.
 * - 408 and 5xx are [ProviderError.Transient].
 */
internal fun failureFor(code: Int, retryAfter: String?, banMs: Long?, notFoundIsAnswer: Boolean = false): ProviderError = when (code) {
    401, 403 -> ProviderError.Fatal("The provider rejected the request.")
    404 -> if (notFoundIsAnswer) ProviderError.NotFound() else ProviderError.Fatal("The provider does not serve this request.")
    402, 429, 430, 435 -> ProviderError.RateLimited(retryAfterMs(retryAfter) ?: banMs.takeIf { code == 429 })
    408, in 500..599 -> ProviderError.Transient()
    else -> ProviderError.Fatal("The provider answered HTTP $code.")
}

/**
 * The wait that a Retry-After header asks for, in milliseconds: a count of seconds, or an HTTP date read against [nowMs]. A wait longer than
 * [MAX_RETRY_AFTER_S] is cut to it. Null when the header is missing or cannot be read, so the scheduler uses its own backoff.
 */
internal fun retryAfterMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val text = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    text.toLongOrNull()?.let { seconds ->
        return if (seconds < 0) null else seconds.coerceAtMost(MAX_RETRY_AFTER_S) * 1_000L
    }
    return try {
        val at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (at - nowMs).coerceIn(0L, MAX_RETRY_AFTER_S * 1_000L)
    } catch (e: DateTimeParseException) {
        null
    }
}

/** Reads the answer, which must fit in [MAX_ANSWER_BYTES]. A longer answer is transient, so it is never cut short and used. */
private fun readAnswer(response: Response): String {
    val source = (response.body ?: throw ProviderError.Transient(IOException("The answer has no body."))).source()
    if (source.request(MAX_ANSWER_BYTES + 1)) throw ProviderError.Transient(IOException("The answer is larger than $MAX_ANSWER_BYTES bytes."))
    return source.readUtf8()
}

/**
 * The answer as a JSON object. Text that is not JSON, or JSON whose top level is not an object, is transient: a captive page, a CDN page or a
 * cut-off answer may pass. A JSON object with the wrong fields inside it is fatal for the request, and [JsonElement?.asObject] reports it.
 */
internal fun answerObject(text: String): JsonObject {
    val root = try {
        Json.Default.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw ProviderError.Transient(e)
    }
    return root as? JsonObject ?: throw ProviderError.Transient(IOException("The answer is not a JSON object."))
}

internal fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: throw ProviderError.Fatal(SHAPE_MESSAGE)

internal fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: throw ProviderError.Fatal(SHAPE_MESSAGE)

/** A finite decimal number, written as a JSON number. A string, a null, or a value that is not finite is refused. */
internal fun JsonElement?.decimal(): Double {
    val primitive = this as? JsonPrimitive ?: throw ProviderError.Fatal(SHAPE_MESSAGE)
    val value = if (primitive.isString) null else primitive.doubleOrNull
    if (value == null || !value.isFinite()) throw ProviderError.Fatal(SHAPE_MESSAGE)
    return value
}

/** The text of a JSON value, whether it was written as a string or a number. */
internal fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull ?: throw ProviderError.Fatal(SHAPE_MESSAGE)

/**
 * A non-negative amount in base units, read from its digits only. A decimal point, a sign or an exponent is refused, so no float can round a
 * balance and no unit is guessed.
 */
internal fun JsonElement?.amount(): BigInteger {
    val digits = text()
    if (!DIGITS.matches(digits)) throw ProviderError.Fatal(SHAPE_MESSAGE)
    return BigInteger(digits)
}

/** A transaction id: 64 hex digits, returned in lower case. */
internal fun JsonElement?.hexHash(): String {
    val hash = text()
    if (!HEX64.matches(hash)) throw ProviderError.Fatal(SHAPE_MESSAGE)
    return hash.lowercase()
}

/** A whole number from [min] to [max], written as digits with an optional leading minus. Anything else is refused. */
internal fun JsonElement?.whole(min: Long, max: Long): Long {
    val digits = text()
    val value = if (WHOLE.matches(digits)) digits.toLongOrNull() else null
    if (value == null || value !in min..max) throw ProviderError.Fatal(SHAPE_MESSAGE)
    return value
}
