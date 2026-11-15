package app.lumio.data.rpc

import app.lumio.data.provider.Http
import app.lumio.data.provider.ProviderError
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The RPC addresses Lumio accepts. HTTPS only. Plain HTTP is accepted only for the emulator's host address and this machine, and only in a
 * debug build ([allowLocalHttp]). An address with a user name or password is refused, so a secret never sits in a URL that is stored.
 */
object RpcUrl {
    private const val MAX_LENGTH = 512
    private val LOCAL_HOSTS = setOf("localhost", "10.0.2.2")

    /** The normalized address for [raw]. Throws IllegalArgumentException, with the owner's message, when it is not accepted. */
    fun validate(raw: String, allowLocalHttp: Boolean): String {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Enter an RPC address." }
        require(text.length <= MAX_LENGTH) { "The RPC address is too long." }
        val url = text.toHttpUrlOrNull() ?: throw IllegalArgumentException("That is not a web address.")
        require(url.username.isEmpty() && url.password.isEmpty()) { "The RPC address must not hold a user name or password." }
        when (url.scheme) {
            "https" -> Unit
            "http" -> require(allowLocalHttp && url.host in LOCAL_HOSTS) { "Plain HTTP is refused. Use an https address." }
            else -> throw IllegalArgumentException("Use an https address.")
        }
        return url.toString()
    }
}

/**
 * The one JSON-RPC client. Every RPC provider and the settings check POST through [call], so the rules hold once: HTTPS addresses only, no
 * redirects followed, an answer capped at 64 KiB, and each call bounded by the client's timeouts. A request holds public data only. Each failure
 * is a [ProviderError] whose message is for the owner. It names no address and no key.
 */
object JsonRpc {
    /** Each call is bounded by this many milliseconds, for connecting, reading and the whole call. */
    const val DEFAULT_TIMEOUT_MS: Long = 10_000L

    private const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val RATE_LIMITED_CODE = -32005
    private val JSON_TYPE = "application/json".toMediaType()

    /** The HTTP stack for RPC calls: no redirects, and each call bounded by [timeoutMs]. */
    fun newHttp(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Http = Http(
        OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    )

    /**
     * The result member of the answer to [method] with [params] at [url]. Blocking, so run it off the main thread. A 429, or a JSON-RPC error
     * that names a rate limit, is [ProviderError.RateLimited]; a 5xx, a timeout or a dropped connection is [ProviderError.Transient]; anything
     * else that fails is [ProviderError.Fatal].
     */
    fun call(client: OkHttpClient, url: String, method: String, params: JsonArray, allowLocalHttp: Boolean): JsonElement {
        val target = try {
            RpcUrl.validate(url, allowLocalHttp)
        } catch (e: IllegalArgumentException) {
            throw ProviderError.Fatal(e.message ?: "The RPC address is not accepted.")
        }
        val body = JsonObject(
            mapOf(
                "jsonrpc" to JsonPrimitive("2.0"),
                "id" to JsonPrimitive(1),
                "method" to JsonPrimitive(method),
                "params" to params,
            ),
        ).toString().toRequestBody(JSON_TYPE)
        val request = Request.Builder().url(target).post(body).build()
        val text = try {
            client.newCall(request).execute().use { response ->
                throwForStatus(response)
                response.peekBody(MAX_RESPONSE_BYTES + 1L).string()
            }
        } catch (e: IOException) {
            throw ProviderError.Transient(e)
        }
        if (text.length > MAX_RESPONSE_BYTES) throw ProviderError.Fatal("The endpoint's answer is too large.")
        return resultOf(text)
    }

    private fun throwForStatus(response: Response) {
        when (val code = response.code) {
            in 200..299 -> Unit
            429 -> throw ProviderError.RateLimited(response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1_000L))
            408, in 500..599 -> throw ProviderError.Transient()
            in 300..399 -> throw ProviderError.Fatal("The endpoint redirected, and redirects are not followed.")
            else -> throw ProviderError.Fatal("The endpoint answered HTTP $code.")
        }
    }

    /** The result member of a JSON-RPC answer. An error object that names a rate limit is rate limited, any other error object, a missing result or a null result is a failure. */
    private fun resultOf(text: String): JsonElement {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ProviderError.Fatal("The endpoint sent something that is not JSON-RPC.")
        val error = root["error"]
        if (error != null && error !is JsonNull) {
            val fields = error as? JsonObject
            val code = (fields?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
            val message = (fields?.get("message") as? JsonPrimitive)?.content.orEmpty()
            if (code == RATE_LIMITED_CODE || message.contains("rate limit", ignoreCase = true)) throw ProviderError.RateLimited()
            throw ProviderError.Fatal("The endpoint returned a JSON-RPC error.")
        }
        val result = root["result"]
        if (result == null || result is JsonNull) throw ProviderError.Fatal("The endpoint sent no result.")
        return result
    }
}
