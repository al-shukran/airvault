package app.lumio.data.provider.explorer

import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.amount
import app.lumio.data.provider.answerObject
import app.lumio.data.provider.asArray
import app.lumio.data.provider.asObject
import app.lumio.data.provider.text
import app.lumio.data.provider.whole
import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/** The shape that an explorer answer must keep, in the words of the shared shape message. */
private const val SHAPE = "The provider's answer does not have the documented shape."

/** An address as the explorers write it: `0x` and 40 hex digits. */
private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** A transaction hash as the explorers write it: `0x` and 64 hex digits. The Esplora transaction ids of Bitcoin have no `0x`, so they are read elsewhere. */
private val TX_HASH = Regex("0x[0-9a-fA-F]{64}")

/** Input data as the explorers write it: `0x` and whole bytes of hex. */
private val HEX_BYTES = Regex("0x(?:[0-9a-fA-F]{2})*")

/** The most decimals a token may declare. A 256-bit amount has at most 78 digits, so 77 decimals is the widest that holds a whole token. */
private const val MAX_DECIMALS = 77L

/**
 * The rows of an account list, the `txlist` or `tokentx` action. A status of "1" holds the rows in `result`. A status of "0" with an empty array in
 * `result` is an account with no matches: an answer, not a failure. Any other status of "0" is a refusal, which [failureOf] reads as [explorerBalance]
 * reads it.
 */
private fun explorerRows(text: String): JsonArray {
    val root = answerObject(text)
    return when (root["status"].text()) {
        "1" -> root["result"].asArray()
        "0" -> (root["result"] as? JsonArray)?.takeIf { it.isEmpty() } ?: throw failureOf(root)
        else -> throw ProviderError.Fatal(SHAPE)
    }
}

/** The normal transactions of an account list, in the order the explorer gave them. */
internal fun explorerNormalTxs(text: String): List<ExplorerTx.Normal> = explorerRows(text).map { it.asObject().normalTx() }

/**
 * The ERC-20 transfers of an account list, in the order the explorer gave them. Identical transfers of one transaction take ordinals 0, 1, and so on,
 * in that order, since the explorers name no log index.
 */
internal fun explorerTokenTxs(text: String): List<ExplorerTx.Token> {
    val seen = HashMap<String, Int>()
    return explorerRows(text).map { row ->
        val token = row.asObject().tokenTx()
        val key = listOf(token.hash, token.contract, token.from, token.to.orEmpty(), token.amount.toString()).joinToString("|")
        val ordinal = seen.getOrDefault(key, 0)
        seen[key] = ordinal + 1
        token.copy(ordinal = ordinal)
    }
}

/**
 * Every row must name [address] as its sender or its recipient. A list with a row that does not is not that account's history, so the whole answer
 * is refused as a shape failure.
 */
internal fun List<ExplorerTx>.involving(address: String): List<ExplorerTx> {
    val me = address.lowercase()
    if (any { it.from != me && it.to != me }) throw ProviderError.Fatal(SHAPE)
    return this
}

private fun JsonObject.normalTx(): ExplorerTx.Normal = ExplorerTx.Normal(
    hash = this["hash"].txHash(),
    timestampSec = this["timeStamp"].whole(0L, Long.MAX_VALUE),
    from = this["from"].address(),
    to = this["to"].addressOrNull(),
    confirmations = this["confirmations"].whole(0L, Long.MAX_VALUE),
    blockNumber = this["blockNumber"].whole(0L, Long.MAX_VALUE),
    valueWei = this["value"].amount(),
    failed = this["isError"].flag(),
    gasUsed = this["gasUsed"].amount(),
    gasPrice = this["gasPrice"].amount(),
    input = this["input"].inputHex(),
    createdContract = this["contractAddress"].addressOrNull(),
)

private fun JsonObject.tokenTx(): ExplorerTx.Token = ExplorerTx.Token(
    hash = this["hash"].txHash(),
    timestampSec = this["timeStamp"].whole(0L, Long.MAX_VALUE),
    from = this["from"].address(),
    to = this["to"].addressOrNull(),
    confirmations = this["confirmations"].whole(0L, Long.MAX_VALUE),
    blockNumber = this["blockNumber"].whole(0L, Long.MAX_VALUE),
    contract = this["contractAddress"].address(),
    name = this["tokenName"].text(),
    symbol = this["tokenSymbol"].text(),
    decimals = this["tokenDecimal"].whole(0L, MAX_DECIMALS).toInt(),
    amount = this["value"].amount(),
)

/** `isError` of a normal transaction: "0" completed, "1" reverted. Any other text does not have the documented shape. */
private fun JsonElement?.flag(): Boolean = when (text()) {
    "0" -> false
    "1" -> true
    else -> throw ProviderError.Fatal(SHAPE)
}

/** A transaction hash as the EVM explorers write it: `0x` and 64 hex digits, read in lower case. */
private fun JsonElement?.txHash(): String {
    val raw = text()
    if (!TX_HASH.matches(raw)) throw ProviderError.Fatal(SHAPE)
    return raw.lowercase()
}

private fun JsonElement?.address(): String = addressOf(text())

/** An address that may be absent: an empty string or a JSON null is null, as the recipient of a contract creation is. */
private fun JsonElement?.addressOrNull(): String? {
    val raw = (this as? JsonPrimitive)?.contentOrNull
    return if (raw.isNullOrEmpty()) null else addressOf(raw)
}

private fun addressOf(raw: String): String {
    if (!ADDRESS.matches(raw)) throw ProviderError.Fatal(SHAPE)
    return raw.lowercase()
}

/** The input data as lower-case hex. An input that is not hex, such as a placeholder, is read as none, which is `0x`. */
private fun JsonElement?.inputHex(): String {
    val raw = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
    return if (HEX_BYTES.matches(raw)) raw.lowercase() else "0x"
}
