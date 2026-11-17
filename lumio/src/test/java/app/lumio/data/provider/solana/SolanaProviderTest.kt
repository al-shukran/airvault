package app.lumio.data.provider.solana

import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.Family
import app.lumio.data.net.NetworkId
import app.lumio.data.provider.Broadcast
import app.lumio.data.provider.Http
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.MAX_SOLANA_HISTORY
import app.lumio.data.provider.Op
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.RecentBlockhash
import app.lumio.data.provider.RentExemptMinimum
import app.lumio.data.provider.Request
import app.lumio.data.provider.SolanaAccountRead
import app.lumio.data.provider.SolanaAddressHistory
import app.lumio.data.provider.SolanaSignatureStatus
import app.lumio.data.provider.SolanaStatus
import app.lumio.data.provider.SolanaTx
import app.lumio.data.provider.SplBalance
import app.lumio.data.provider.SplTokenBalances
import app.lumio.data.provider.Trait
import app.lumio.data.rpc.JsonRpc
import java.math.BigInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Solana provider against a local MockWebServer that answers in the shapes Solana's JSON-RPC gives. No test reaches a network. Waits are recorded
 * rather than slept, so the 429 back-off runs in no real time.
 */
class SolanaProviderTest {
    private val server = MockWebServer()
    private val waits = mutableListOf<Long>()
    private var charges = 0
    private val http = Http(JsonRpc.newHttp(timeoutMs = 2_000L).client) { charges++ }
    private val devnet = SOLANA_DEVNET
    private val address = "So11111111111111111111111111111111111111112"
    private val other = "11111111111111111111111111111111"
    private val mint = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val blockhash = "A".repeat(44)
    private val firstSignature = "B".repeat(88)
    private val secondSignature = "C".repeat(88)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    /** The owner's devnet RPC, set to the local server, with the waits recorded. */
    private fun provider() = CustomSolanaRpc("devnet", allowLocalHttp = true, pause = { waits += it })

    private fun cfg() = ProviderConfig(baseUrl = server.url("/").toString())

    private fun <T> call(req: Request<T>): T = runBlocking { provider().execute(req, cfg(), http) }

    private fun answer(result: String) = MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":$result}""")

    private fun rateLimited(retryAfter: String? = null): MockResponse =
        MockResponse().setResponseCode(429).apply { retryAfter?.let { setHeader("Retry-After", it) } }

    private fun method(request: RecordedRequest): String = Regex("\"method\":\"([A-Za-z]+)\"").find(request.body.readUtf8())!!.groupValues[1]

    private fun jsonStrings(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    /** One getTransaction answer whose balances are listed in [keys] order, with [loaded] as its loadedAddresses (or null). */
    private fun transaction(keys: List<String>, before: List<Long>, after: List<Long>, loaded: String = "null"): String =
        """{"slot":10,"blockTime":1700000000,"meta":{"err":null,"fee":5000,"preBalances":$before,"postBalances":$after,""" +
            """"loadedAddresses":$loaded},"transaction":{"message":{"accountKeys":${jsonStrings(keys)}},"signatures":[]}}"""

    @Test
    fun theBalanceIsTheLamportsOfGetBalance() {
        server.enqueue(answer("""{"context":{"slot":1},"value":1500000000}"""))

        assertEquals(BigInteger("1500000000"), call(NativeBalance(devnet, address)))
        assertEquals("getBalance", method(server.takeRequest()))
    }

    @Test
    fun theLatestBlockhashIsTheBlockhashValue() {
        server.enqueue(answer("""{"context":{"slot":1},"value":{"blockhash":"$blockhash","lastValidBlockHeight":100}}"""))

        assertEquals(blockhash, call(RecentBlockhash(devnet)))
        assertEquals("getLatestBlockhash", method(server.takeRequest()))
    }

    /** `getAccountInfo` in base64: the owner is the program, and the data is the decoded bytes. A missing account is null. */
    @Test
    fun theAccountInfoIsTheOwnerAndTheDataInBytes() {
        server.enqueue(answer("""{"context":{"slot":1},"value":{"data":["AAEC","base64"],"executable":false,"lamports":1,"owner":"$other","rentEpoch":0,"space":3}}"""))

        val state = assertNotNull(call(SolanaAccountRead(devnet, address)))
        assertEquals(other, state.owner)
        assertContentEquals(byteArrayOf(0, 1, 2), state.data)
        assertEquals("getAccountInfo", method(server.takeRequest()))
    }

    @Test
    fun aMissingAccountIsNull() {
        server.enqueue(answer("""{"context":{"slot":1},"value":null}"""))

        assertNull(call(SolanaAccountRead(devnet, address)))
    }

    @Test
    fun theRentExemptMinimumIsTheLamportsTheNetworkAsks() {
        server.enqueue(answer("1447680"))

        assertEquals(1_447_680L, call(RentExemptMinimum(devnet, 80L)))
        assertEquals("getMinimumBalanceForRentExemption", method(server.takeRequest()))
    }

    @Test
    fun aSignedTransactionIsSentAsBase64AndItsSignatureIsReturned() {
        server.enqueue(answer("\"$firstSignature\""))

        assertEquals(firstSignature, call(Broadcast(devnet, byteArrayOf(1, 2, 3))))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"sendTransaction\""))
        assertTrue(body.contains("[\"AQID\",{\"encoding\":\"base64\"}]"))
    }

    @Test
    fun theHistoryReadsTheSignaturesThenEachTransactionAndChargesEachLaterRequest() {
        server.enqueue(
            answer(
                """[{"signature":"$firstSignature","slot":10,"err":null,"blockTime":1700000000},""" +
                    """{"signature":"$secondSignature","slot":9,"err":{"InstructionError":[0,{"Custom":1}]},"blockTime":null}]""",
            ),
        )
        server.enqueue(answer(transaction(listOf(other, address), listOf(1_000_000_000, 500_000_000), listOf(999_995_000, 1_500_000_000))))
        server.enqueue(answer(transaction(listOf(address, other), listOf(1_000_000_000, 1), listOf(999_995_000, 1))))

        assertEquals(
            listOf(
                SolanaTx(firstSignature, slot = 10, blockTimeS = 1_700_000_000, failed = false, lamportDelta = 1_000_000_000),
                SolanaTx(secondSignature, slot = 9, blockTimeS = null, failed = true, lamportDelta = -5_000),
            ),
            call(SolanaAddressHistory(devnet, address, limit = 2)),
        )
        assertEquals("getSignaturesForAddress", method(server.takeRequest()))
        assertEquals("getTransaction", method(server.takeRequest()))
        assertEquals("getTransaction", method(server.takeRequest()))
        assertEquals(2, charges)
    }

    @Test
    fun aLoadedAddressIsFoundAfterTheStaticKeys() {
        server.enqueue(answer("""[{"signature":"$firstSignature","slot":3,"err":null,"blockTime":null}]"""))
        val loaded = """{"writable":["$address"],"readonly":[]}"""
        server.enqueue(answer(transaction(listOf(other), listOf(900L, 2_000L), listOf(800L, 2_100L), loaded)))

        assertEquals(100L, call(SolanaAddressHistory(devnet, address, limit = 1)).single().lamportDelta)
    }

    @Test
    fun anEmptyHistoryIsAnEmptyListWithOneRequest() {
        server.enqueue(answer("[]"))

        assertEquals(emptyList(), call(SolanaAddressHistory(devnet, address, limit = 5)))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theSplBalancesAreReadWithJsonParsedFromTheClassicTokenProgram() {
        server.enqueue(
            answer(
                """{"context":{"slot":1},"value":[{"pubkey":"$other","account":{"data":{"parsed":{"info":{"mint":"$mint","owner":"$address",""" +
                    """"tokenAmount":{"amount":"1500000","decimals":6,"uiAmount":1.5,"uiAmountString":"1.5"}},"type":"account"},""" +
                    """"program":"spl-token","space":165},"executable":false,"lamports":2039280,""" +
                    """"owner":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","rentEpoch":0,"space":165}}]}""",
            ),
        )

        assertEquals(listOf(SplBalance(mint, BigInteger("1500000"), 6)), call(SplTokenBalances(devnet, address)))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"getTokenAccountsByOwner\""))
        assertTrue(body.contains("\"programId\":\"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA\""))
        assertTrue(body.contains("\"encoding\":\"jsonParsed\""))
    }

    @Test
    fun aTooManyRequestsAnswerIsSentAgainAfterTheBackOffAndThenServed() {
        server.enqueue(rateLimited())
        server.enqueue(answer("""{"context":{"slot":1},"value":42}"""))

        assertEquals(BigInteger.valueOf(42), call(NativeBalance(devnet, address)))
        assertEquals(2, server.requestCount)
        assertEquals(listOf(500L), waits)
        assertEquals(1, charges)
    }

    @Test
    fun aRetryAfterSetsTheWaitInPlaceOfTheBackOff() {
        server.enqueue(rateLimited("2"))
        server.enqueue(answer("""{"context":{"slot":1},"value":7}"""))

        call(NativeBalance(devnet, address))

        assertEquals(listOf(2_000L), waits)
    }

    @Test
    fun aRetryAfterLongerThanTheCallWaitsGoesToTheSchedulerAtOnce() {
        server.enqueue(rateLimited("60"))

        val failure = assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(devnet, address)) }

        assertEquals(60_000L, failure.retryAfterMs)
        assertEquals(1, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun repeatedTooManyRequestsStopAtTheRetryCapAndAreRateLimited() {
        repeat(MAX_RETRIES + 1) { server.enqueue(rateLimited()) }

        val failure = assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(devnet, address)) }

        assertNull(failure.retryAfterMs)
        assertEquals(MAX_RETRIES + 1, server.requestCount)
        assertEquals(listOf(500L, 1_000L, 2_000L), waits)
    }

    @Test
    fun aRateLimitInsideAJsonRpcErrorIsRetriedLikeAnHttp429() {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32005,"message":"Node is behind"}}"""))
        server.enqueue(answer("""{"context":{"slot":1},"value":3}"""))

        assertEquals(BigInteger.valueOf(3), call(NativeBalance(devnet, address)))
        assertEquals(listOf(500L), waits)
    }

    @Test
    fun aMalformedAnswerIsFatal() {
        server.enqueue(MockResponse().setBody("<html>portal</html>"))
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(devnet, address)) }

        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1}"""))
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(devnet, address)) }

        server.enqueue(answer("""{"context":{"slot":1},"value":1.5}"""))
        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(devnet, address)) }

        server.enqueue(answer("""{"context":{"slot":1},"value":{"blockhash":"not base58 0OIl","lastValidBlockHeight":1}}"""))
        assertFailsWith<ProviderError.Fatal> { call(RecentBlockhash(devnet)) }

        server.enqueue(answer("\"0xdeadbeef\""))
        assertFailsWith<ProviderError.Fatal> { call(Broadcast(devnet, byteArrayOf(1))) }
    }

    @Test
    fun aJsonRpcErrorObjectIsFatalAndAMissingMethodIsUnsupported() {
        server.enqueue(
            MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"Transaction simulation failed"}}"""),
        )
        assertFailsWith<ProviderError.Fatal> { call(Broadcast(devnet, byteArrayOf(1))) }

        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}"""))
        assertFailsWith<ProviderError.Unsupported> { call(RecentBlockhash(devnet)) }
    }

    @Test
    fun aValueThatIsNotASolanaAddressIsRefusedBeforeAnyCall() {
        assertFailsWith<IllegalArgumentException> { call(NativeBalance(devnet, "0x" + "ab".repeat(20))) }
        assertFailsWith<IllegalArgumentException> { SolanaAddressHistory(devnet, "a/b", limit = 1) }
        assertFailsWith<IllegalArgumentException> { SolanaAddressHistory(devnet, address, limit = MAX_SOLANA_HISTORY + 1) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun thePublicProviderServesTheDevnetFromItsEndpointAndNoEvmNetwork() {
        val publicRpc = SolanaRpc()

        assertEquals("https://api.devnet.solana.com", publicRpc.endpoint(devnet, ProviderConfig()))
        assertTrue(publicRpc.supports(devnet, Op.NATIVE_BALANCE, ProviderConfig()))
        assertTrue(publicRpc.supports(devnet, Op.BROADCAST, ProviderConfig()))
        assertFalse(publicRpc.supports(devnet, Op.NONCE, ProviderConfig()))
        assertFalse(publicRpc.supports(NetworkId(Family.EVM, "1", testnet = false), Op.NATIVE_BALANCE, ProviderConfig()))
        assertFalse(publicRpc.supports(NetworkId(Family.SOL, "testnet", testnet = true), Op.NATIVE_BALANCE, ProviderConfig()))
        assertEquals(setOf(Trait.KEYLESS), publicRpc.traits)
    }

    @Test
    fun thePublicProviderServesTheMainnetBetaFromItsEndpoint() {
        val publicRpc = SolanaRpc()

        assertEquals("https://api.mainnet-beta.solana.com", publicRpc.endpoint(SOLANA_MAINNET, ProviderConfig()))
        assertTrue(publicRpc.supports(SOLANA_MAINNET, Op.NATIVE_BALANCE, ProviderConfig()))
        assertEquals(SOLANA_MAINNET, NetworkId(Family.SOL, "mainnet-beta", testnet = false))
    }

    @Test
    fun theOwnerEndpointIsServedOnlyOverHttpsOutsideADebugBuildAndOnlyForItsChain() {
        val https = ProviderConfig(baseUrl = "https://solana.example.org/rpc")
        val mainnet = NetworkId(Family.SOL, "mainnet-beta", testnet = false)

        assertEquals("https://solana.example.org/rpc", CustomSolanaRpc("devnet", allowLocalHttp = false).endpoint(devnet, https))
        assertNull(CustomSolanaRpc("devnet", allowLocalHttp = false).endpoint(devnet, ProviderConfig(baseUrl = "http://localhost:8899")))
        assertEquals(
            "http://localhost:8899/",
            CustomSolanaRpc("devnet", allowLocalHttp = true).endpoint(devnet, ProviderConfig(baseUrl = "http://localhost:8899")),
        )
        assertNull(CustomSolanaRpc("devnet", allowLocalHttp = false).endpoint(devnet, ProviderConfig(baseUrl = "https://user:pass@solana.example.org")))
        assertNull(CustomSolanaRpc("devnet", allowLocalHttp = false).endpoint(devnet, ProviderConfig()))
        assertNull(CustomSolanaRpc("devnet", allowLocalHttp = false).endpoint(mainnet, https))
        assertEquals(setOf(Trait.OWN_SERVER), CustomSolanaRpc("devnet", allowLocalHttp = false).traits)
    }

    @Test
    fun theSignatureStatusIsNullUntilTheNetworkKnowsItAndReadsItsConfirmation() {
        server.enqueue(answer("""{"context":{"slot":1},"value":[null]}"""))
        assertNull(call(SolanaSignatureStatus(devnet, firstSignature)))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"method\":\"getSignatureStatuses\""))
        assertTrue(body.contains("[[\"$firstSignature\"],{\"searchTransactionHistory\":true}]"))

        server.enqueue(answer("""{"context":{"slot":2},"value":[{"slot":2,"confirmations":null,"err":null,"confirmationStatus":"processed"}]}"""))
        assertEquals(SolanaStatus(confirmed = false, failed = false), call(SolanaSignatureStatus(devnet, firstSignature)))

        server.enqueue(answer("""{"context":{"slot":3},"value":[{"slot":3,"confirmations":null,"err":null,"confirmationStatus":"confirmed"}]}"""))
        assertEquals(SolanaStatus(confirmed = true, failed = false), call(SolanaSignatureStatus(devnet, firstSignature)))

        server.enqueue(
            answer(
                """{"context":{"slot":4},"value":[{"slot":4,"confirmations":null,"err":{"InstructionError":[0,{"Custom":1}]},""" +
                    """"confirmationStatus":"finalized"}]}""",
            ),
        )
        assertEquals(SolanaStatus(confirmed = true, failed = true), call(SolanaSignatureStatus(devnet, firstSignature)))
    }

    @Test
    fun aTooManyRequestsStatusReadIsSentAgainAfterTheBackOff() {
        server.enqueue(rateLimited())
        server.enqueue(answer("""{"context":{"slot":1},"value":[null]}"""))

        assertNull(call(SolanaSignatureStatus(devnet, firstSignature)))
        assertEquals(listOf(500L), waits)
        assertEquals(1, charges)
    }

    @Test
    fun aBlockhashThatHasExpiredIsRefusedWithTheWordsForTheOwner() {
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"Transaction simulation failed: Blockhash not found","data":{}}}""",
            ),
        )

        val failure = assertFailsWith<ProviderError.Fatal> { call(Broadcast(devnet, byteArrayOf(1))) }

        assertEquals(JsonRpc.BLOCKHASH_REFUSED_MESSAGE, failure.message)
    }

    @Test
    fun aSignatureThatIsNotASolanaSignatureIsRefusedBeforeAnyCall() {
        assertFailsWith<IllegalArgumentException> { SolanaSignatureStatus(devnet, "abc") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theCatalogListsTheSolanaProvidersByTheirIdsAndTheDevnetIsTheSolanaFamily() {
        assertEquals(listOf("solana", "custom-sol-devnet"), ProviderCatalog.solanaProviders(allowLocalHttp = false).map { it.id })
        assertEquals(NetworkId(Family.SOL, "devnet", testnet = true), SOLANA_DEVNET)
    }
}
