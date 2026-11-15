package app.lumio.data.provider.explorer

import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.amount
import app.lumio.data.provider.answerObject
import app.lumio.data.provider.text
import java.math.BigInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The words that name a rate or usage cap in these APIs' answers. They are matched without regard to case. */
private val RATE_WORDS = listOf("rate limit", "limit reached", "too many")

/** The words that name a chain or a plan this call does not reach. Such an answer is not a failure of the provider. */
private val UNSERVED_WORDS = listOf("not supported")

/**
 * The balance in wei from an account answer of the Etherscan family, `{"status":"1","message":"OK","result":"<wei>"}`. Etherscan V2, Routescan and
 * Blockscout answer this way. A status of "0" is a failure, and its reason is in `result`, or in `message` when `result` is null. These APIs answer
 * a rate limit and a rejected key with HTTP 200, so the reason sets the error:
 * - a rate or usage cap is [ProviderError.RateLimited], with no fixed wait, so the scheduler backs off;
 * - a chain or plan that is not served is [ProviderError.Unsupported]: the provider is skipped for this call, and it is not a failure;
 * - any other refusal, such as a rejected key or a bad parameter, is [ProviderError.Fatal], which skips the provider for this call.
 * The reason never reaches a message, since it can echo a key.
 */
internal fun explorerBalance(text: String): BigInteger {
    val root = answerObject(text)
    return when (root["status"].text()) {
        "1" -> root["result"].amount()
        "0" -> throw failureOf(root)
        else -> throw ProviderError.Fatal("The provider's answer does not have the documented shape.")
    }
}

private fun failureOf(root: JsonObject): ProviderError {
    val reason = listOf(root["message"], root["result"])
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .joinToString(" ")
    return when {
        RATE_WORDS.any { reason.contains(it, ignoreCase = true) } -> ProviderError.RateLimited()
        UNSERVED_WORDS.any { reason.contains(it, ignoreCase = true) } -> ProviderError.Unsupported()
        else -> ProviderError.Fatal("The provider refused the request.")
    }
}
