package app.lumio.data.provider.solana

import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.RecentBlockhash
import app.lumio.data.provider.RentExemptMinimum
import app.lumio.data.provider.Request
import app.lumio.data.provider.SOLANA_SIGNATURE
import app.lumio.data.provider.SolanaAccountRead
import app.lumio.data.provider.SolanaAccountState
import app.lumio.data.provider.SolanaAddressHistory
import app.lumio.data.provider.SolanaBalances
import app.lumio.data.provider.SolanaBlockHeight
import app.lumio.data.provider.SolanaBlockhash
import app.lumio.data.provider.SolanaSignatureStatus
import app.lumio.data.provider.SolanaStatus
import app.lumio.data.provider.SolanaTx
import app.lumio.data.provider.SplBalance
import app.lumio.data.provider.SplTokenBalances
import app.lumio.data.provider.amount
import app.lumio.data.provider.asArray
import app.lumio.data.provider.asObject
import app.lumio.data.provider.text
import app.lumio.data.provider.whole
import app.lumio.data.rpc.JsonRpc
import java.math.BigInteger
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The most times a call is sent again after a 429. One call makes at most this many requests after its first. */
internal const val MAX_RETRIES = 3

/** The first back-off after a 429 that sent no Retry-After. It doubles with each retry. */
internal const val BASE_BACKOFF_MS = 500L

/** The longest wait a call takes itself. A longer Retry-After is left to the scheduler, which cools the provider for it instead. */
internal const val MAX_WAIT_MS = 4_000L

/** A public key, which is also the size of a blockhash: 32 bytes in base58, so 32 to 44 characters. */
private val PUBLIC_KEY = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")

/** The classic SPL token program. */
private const val SPL_TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"

/** The Token-2022 program. Its token accounts are read beside the classic ones. */
private const val SPL_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

/**
 * The commitment that blockhashes and block heights are read at. "confirmed" is the newest block that a cluster has voted on, and "finalized" lags
 * it, so a blockhash read at "confirmed" stays valid for the longest time that the read allows.
 */
private const val CONFIRMED = "confirmed"

private const val NOT_A_SOLANA_VALUE = "The endpoint sent a value that is not a Solana value."

/** The wait before retry [retry] (counted from 1) after a 429: the Retry-After when one was sent, else the back-off. Null when that wait is over [MAX_WAIT_MS]. */
internal fun waitFor(retryAfterMs: Long?, retry: Int): Long? =
    (retryAfterMs ?: (BASE_BACKOFF_MS shl (retry - 1))).takeIf { it <= MAX_WAIT_MS }

/**
 * The JSON-RPC calls behind each Solana request, named as Solana's JSON-RPC names them. A call holds public data only: an address, an amount, or a
 * signed transaction that anyone can broadcast. The first HTTP request of an execution is paid by the scheduler's admission. Each later one calls
 * [Http.charge] first, so it takes its token from the call's rate bucket. A 429 is sent again after [waitFor], up to [MAX_RETRIES] times. Then the
 * [ProviderError.RateLimited] goes to the scheduler. Blocking HTTP runs on the IO dispatcher.
 */
internal class SolanaCalls(
    private val http: Http,
    private val url: String,
    private val allowLocalHttp: Boolean,
    private val pause: suspend (Long) -> Unit,
) {
    private var sent = 0

    /** The answer to [req] from the endpoint at [url]. Throws [ProviderError] for a failure of the endpoint. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> execute(req: Request<T>): T {
        val result: Any? = when (req) {
            is NativeBalance -> lamports(address(req.address))
            is SolanaBalances -> balances(req.addresses)
            is RecentBlockhash -> blockhash()
            is SolanaBlockHeight -> blockHeight()
            is Broadcast -> broadcast(req.raw)
            is SolanaAddressHistory -> history(req)
            is SplTokenBalances -> tokenBalances(address(req.owner))
            is SolanaSignatureStatus -> status(req.signature)
            is SolanaAccountRead -> accountState(req.address)
            is RentExemptMinimum -> rentExemptMinimum(req.size)
            else -> throw ProviderError.Unsupported()
        }
        return result as T
    }

    /** `getBalance`: the lamports of [address], which the answer gives as a number in `value`. */
    private suspend fun lamports(address: String): BigInteger =
        call("getBalance", JsonArray(listOf(JsonPrimitive(address)))).asObject()["value"].amount()

    /**
     * `getMultipleAccounts` with no account data, so the answer is only the lamports of each account, in order. An account that does not exist is null
     * in the answer and holds 0. An answer with another number of accounts than was asked is a malformed answer.
     */
    private suspend fun balances(addresses: List<String>): List<BigInteger> {
        val keys = JsonArray(addresses.map { JsonPrimitive(address(it)) })
        val noData = options("dataSlice" to options("offset" to JsonPrimitive(0), "length" to JsonPrimitive(0)))
        val accounts = call("getMultipleAccounts", JsonArray(listOf(keys, noData))).asObject()["value"].asArray()
        if (accounts.size != addresses.size) throw ProviderError.Fatal(NOT_A_SOLANA_VALUE)
        return accounts.map { account -> if (account is JsonNull) BigInteger.ZERO else account.asObject()["lamports"].amount() }
    }

    /** `getLatestBlockhash` at [CONFIRMED]: the blockhash in `value`, and the height after which it is no longer valid. */
    private suspend fun blockhash(): SolanaBlockhash {
        val value = call("getLatestBlockhash", confirmedParams()).asObject()["value"].asObject()
        return SolanaBlockhash(
            blockhash = value["blockhash"].matching(PUBLIC_KEY),
            lastValidBlockHeight = value["lastValidBlockHeight"].whole(0, Long.MAX_VALUE),
        )
    }

    /** `getBlockHeight` at [CONFIRMED]: the height that a blockhash's last valid height is compared with. */
    private suspend fun blockHeight(): Long = call("getBlockHeight", confirmedParams()).whole(0, Long.MAX_VALUE)

    /** The one parameter that names the [CONFIRMED] commitment. */
    private fun confirmedParams(): JsonArray = JsonArray(listOf(options("commitment" to JsonPrimitive(CONFIRMED))))

    /**
     * `getAccountInfo` with the data in base64: the owner and the data of [address], or null when the account does not exist. Data that is not
     * base64 is a malformed answer.
     */
    private suspend fun accountState(address: String): SolanaAccountState? {
        val params = JsonArray(listOf(JsonPrimitive(address), options("encoding" to JsonPrimitive("base64"))))
        val value = call("getAccountInfo", params).asObject()["value"].present()?.asObject() ?: return null
        val encoded = value["data"].asArray().firstOrNull().text()
        val data = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw ProviderError.Fatal(NOT_A_SOLANA_VALUE)
        }
        return SolanaAccountState(owner = value["owner"].matching(PUBLIC_KEY), data = data)
    }

    /** `getMinimumBalanceForRentExemption` for an account of [size] bytes: the lamports that the answer gives it must hold. */
    private suspend fun rentExemptMinimum(size: Long): Long =
        call("getMinimumBalanceForRentExemption", JsonArray(listOf(JsonPrimitive(size)))).whole(0, Long.MAX_VALUE)

    /** `sendTransaction` with the signed bytes in base64. The answer is the signature of the transaction. */
    private suspend fun broadcast(raw: ByteArray): String {
        val params = JsonArray(listOf(JsonPrimitive(Base64.getEncoder().encodeToString(raw)), options("encoding" to JsonPrimitive("base64"))))
        return call("sendTransaction", params).matching(SOLANA_SIGNATURE)
    }

    /**
     * `getSignaturesForAddress`, then `getTransaction` for each signature it returns. The rows keep the order of the signatures, newest first.
     * A transaction's answer gives the address's balance change, and [balanceChange] reads it.
     */
    private suspend fun history(req: SolanaAddressHistory): List<SolanaTx> {
        val params = JsonArray(listOf(JsonPrimitive(req.address), options("limit" to JsonPrimitive(req.limit))))
        return call("getSignaturesForAddress", params).asArray().map { row ->
            val fields = row.asObject()
            val signature = fields["signature"].matching(SOLANA_SIGNATURE)
            val transactionParams = JsonArray(
                listOf(
                    JsonPrimitive(signature),
                    options("encoding" to JsonPrimitive("json"), "maxSupportedTransactionVersion" to JsonPrimitive(0)),
                ),
            )
            val transaction = call("getTransaction", transactionParams).asObject()
            SolanaTx(
                signature = signature,
                slot = fields["slot"].whole(0, Long.MAX_VALUE),
                blockTimeS = fields["blockTime"].present()?.whole(Long.MIN_VALUE, Long.MAX_VALUE),
                failed = fields["err"].present() != null,
                lamportDelta = balanceChange(req.address, transaction),
            )
        }
    }

    /**
     * The change in lamports of [address] in [tx], or null when the answer does not show its balance. Balances are listed in the order of the
     * transaction's account keys: the static keys, then the loaded writable addresses, then the loaded read-only ones.
     */
    private fun balanceChange(address: String, tx: JsonObject): Long? {
        val meta = tx["meta"].present() as? JsonObject ?: return null
        val before = meta["preBalances"] as? JsonArray ?: return null
        val after = meta["postBalances"] as? JsonArray ?: return null
        val loaded = meta["loadedAddresses"].present() as? JsonObject
        val message = tx["transaction"].asObject()["message"].asObject()
        val keys = (message["accountKeys"].asArray() + loaded.loadedList("writable") + loaded.loadedList("readonly")).map { it.text() }
        val index = keys.indexOf(address)
        if (index < 0 || index >= before.size || index >= after.size) return null
        return after[index].whole(0, Long.MAX_VALUE) - before[index].whole(0, Long.MAX_VALUE)
    }

    /** The token accounts of [owner] under the classic token program, then under Token-2022: one `getTokenAccountsByOwner` for each program. */
    private suspend fun tokenBalances(owner: String): List<SplBalance> =
        listOf(SPL_TOKEN_PROGRAM, SPL_2022_PROGRAM).flatMap { program -> tokenAccounts(owner, program) }

    /**
     * `getTokenAccountsByOwner` for one token [program], with jsonParsed accounts. Each account's own address is its pubkey, and its parsed info gives
     * its mint and amount.
     */
    private suspend fun tokenAccounts(owner: String, program: String): List<SplBalance> {
        val params = JsonArray(
            listOf(
                JsonPrimitive(owner),
                options("programId" to JsonPrimitive(program)),
                options("encoding" to JsonPrimitive("jsonParsed")),
            ),
        )
        val accounts = call("getTokenAccountsByOwner", params).asObject()["value"].asArray()
        return accounts.map { entry ->
            val account = entry.asObject()
            val info = account["account"].asObject()["data"].asObject()["parsed"].asObject()["info"].asObject()
            val tokenAmount = info["tokenAmount"].asObject()
            SplBalance(
                account = account["pubkey"].matching(PUBLIC_KEY),
                mint = info["mint"].matching(PUBLIC_KEY),
                program = program,
                amount = tokenAmount["amount"].amount(),
                decimals = tokenAmount["decimals"].whole(0, 255L).toInt(),
            )
        }
    }

    /**
     * `getSignatureStatuses` for [signature], with the history searched so an older signature is found. The answer is null while the network does not
     * know the signature. A status is confirmed at `confirmed` or `finalized`, and failed when its `err` is set.
     */
    private suspend fun status(signature: String): SolanaStatus? {
        val params = JsonArray(
            listOf(
                JsonArray(listOf(JsonPrimitive(signature))),
                options("searchTransactionHistory" to JsonPrimitive(true)),
            ),
        )
        val row = call("getSignatureStatuses", params).asObject()["value"].asArray().firstOrNull()
        if (row == null || row is JsonNull) return null
        val fields = row.asObject()
        val level = fields["confirmationStatus"].present()?.text()
        return SolanaStatus(confirmed = level == "confirmed" || level == "finalized", failed = fields["err"].present() != null)
    }

    /** One JSON-RPC call. A 429 is sent again after [waitFor] while [MAX_RETRIES] allows, and then the last [ProviderError.RateLimited] is thrown. */
    private suspend fun call(method: String, params: JsonArray): JsonElement {
        var retries = 0
        while (true) {
            if (sent++ > 0) http.charge()
            try {
                return withContext(Dispatchers.IO) { JsonRpc.call(http.client, url, method, params, allowLocalHttp) }
            } catch (e: ProviderError.RateLimited) {
                val wait = waitFor(e.retryAfterMs, retries + 1)
                if (retries == MAX_RETRIES || wait == null) throw e
                retries++
                pause(wait)
            }
        }
    }

    private fun address(text: String): String {
        require(PUBLIC_KEY.matches(text)) { "Not a Solana address." }
        return text
    }
}

/** The JSON value, or null when it is missing or JSON null. */
private fun JsonElement?.present(): JsonElement? = this?.takeUnless { it is JsonNull }

/** The text of this value, when it fits [pattern]. Any other value is a malformed answer. */
private fun JsonElement?.matching(pattern: Regex): String {
    val value = text()
    if (!pattern.matches(value)) throw ProviderError.Fatal(NOT_A_SOLANA_VALUE)
    return value
}

/** The array under [name] of this object, or an empty list when this object is null or has no such array. */
private fun JsonObject?.loadedList(name: String): List<JsonElement> = (this?.get(name).present() as? JsonArray).orEmpty()

private fun options(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(fields.toMap())
