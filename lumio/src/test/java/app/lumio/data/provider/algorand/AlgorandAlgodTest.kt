package app.lumio.data.provider.algorand

import app.lensly.core.algorand.AlgorandAccount
import app.lumio.data.config.ProviderConfig
import app.lumio.data.net.NetworkId
import app.lumio.data.net.Networks
import app.lumio.data.provider.AsaAssetInfo
import app.lumio.data.provider.AsaHolding
import app.lumio.data.provider.AsaHoldings
import app.lumio.data.provider.AsaInfo
import app.lumio.data.provider.Http
import app.lumio.data.provider.MAX_ANSWER_BYTES
import app.lumio.data.provider.NativeBalance
import app.lumio.data.provider.OpKey
import app.lumio.data.provider.ProviderCatalog
import app.lumio.data.provider.ProviderError
import app.lumio.data.provider.Request
import app.lumio.data.provider.TipHeight
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Algorand provider against a local MockWebServer that answers as algod does. No test reaches a network: the endpoint of a test is the local
 * server, except where a test reads the public endpoints as constants.
 */
class AlgorandAlgodTest {
    private val server = MockWebServer()
    private val http = Http(OkHttpClient())
    private val cfg = ProviderConfig()
    private val address = AlgorandAccount.addressOf(ByteArray(32) { 7 })

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    /** The address of the local server, without a trailing slash, as an endpoint of the provider. */
    private fun localBase(): String = server.url("/").toString().trimEnd('/')

    /** The provider with the testnet endpoint set to the local server. */
    private fun provider() = AlgorandAlgod(mapOf("testnet" to localBase()))

    private fun <T> call(req: Request<T>): T = runBlocking { provider().execute(req, cfg, http) }

    /** An account answer whose `amount` is [amount], written as algod writes it: a JSON number. */
    private fun account(amount: String) = MockResponse().setBody(
        """{"address":"$address","amount":$amount,"amount-without-pending-rewards":$amount,"min-balance":100000,"round":1}""",
    )

    @Test
    fun theAssetHoldingsAreTheAssetsOfTheAccountWithTheirAmountsAndFrozenFlags() {
        server.enqueue(
            MockResponse().setBody(
                """{"address":"$address","amount":1500000,"assets":[""" +
                    """{"amount":1500000,"asset-id":10458941,"is-frozen":false},""" +
                    """{"amount":0,"asset-id":31566704,"is-frozen":true}]}""",
            ),
        )

        assertEquals(
            listOf(AsaHolding(10458941UL, BigInteger("1500000"), frozen = false), AsaHolding(31566704UL, BigInteger.ZERO, frozen = true)),
            call(AsaHoldings(ALGORAND_TESTNET, address)),
        )
        assertEquals("/v2/accounts/$address", server.takeRequest().path)
    }

    @Test
    fun anAccountWithNoAssetsListHoldsNoAssets() {
        server.enqueue(account("1500000"))

        assertEquals(emptyList(), call(AsaHoldings(ALGORAND_TESTNET, address)))
    }

    @Test
    fun anAssetEntryThatIsNotAnObjectIsAMalformedAnswer() {
        server.enqueue(MockResponse().setBody("""{"address":"$address","amount":1,"assets":[3]}"""))

        assertFailsWith<ProviderError.Fatal> { call(AsaHoldings(ALGORAND_TESTNET, address)) }
    }

    @Test
    fun anAssetsDecimalsAndUnitNameAreReadFromItsParams() {
        server.enqueue(
            MockResponse().setBody("""{"index":10458941,"params":{"creator":"$address","decimals":6,"name":"USDC","unit-name":"USDC","total":1}}"""),
        )

        assertEquals(AsaInfo(decimals = 6, unitName = "USDC", name = "USDC"), call(AsaAssetInfo(ALGORAND_TESTNET, 10458941UL)))
        assertEquals("/v2/assets/10458941", server.takeRequest().path)
    }

    @Test
    fun aMissingNameIsEmptyAndDecimalsOutsideZeroToNineteenAreMalformed() {
        server.enqueue(MockResponse().setBody("""{"index":7,"params":{"creator":"$address","decimals":0}}"""))
        assertEquals(AsaInfo(decimals = 0, unitName = "", name = ""), call(AsaAssetInfo(ALGORAND_TESTNET, 7UL)))
        server.enqueue(MockResponse().setBody("""{"index":8,"params":{"decimals":20}}"""))
        assertFailsWith<ProviderError.Fatal> { call(AsaAssetInfo(ALGORAND_TESTNET, 8UL)) }
    }

    @Test
    fun anAssetIdOfZeroIsRefusedBeforeItIsSent() {
        assertFailsWith<IllegalArgumentException> { AsaAssetInfo(ALGORAND_TESTNET, 0UL) }
    }

    @Test
    fun theBalanceIsTheAmountOfTheAccountInMicroAlgos() {
        server.enqueue(account("1500000"))

        assertEquals(BigInteger("1500000"), call(NativeBalance(ALGORAND_TESTNET, address)))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v2/accounts/$address", request.path)
    }

    @Test
    fun theBalanceShowsAsAlgo() {
        server.enqueue(account("1500000"))

        assertEquals("1.5 ALGO", AlgorandFormat.amount(call(NativeBalance(ALGORAND_TESTNET, address))))
    }

    @Test
    fun anAccountWithNothingInItIsZero() {
        server.enqueue(account("0"))

        assertEquals("0 ALGO", AlgorandFormat.amount(call(NativeBalance(ALGORAND_TESTNET, address))))
    }

    @Test
    fun oneMicroAlgoIsSixDecimalsAndAThousandAlgoHasNoDecimals() {
        assertEquals("0.000001 ALGO", AlgorandFormat.amount(BigInteger.ONE))
        assertEquals("1000 ALGO", AlgorandFormat.amount(BigInteger("1000000000")))
    }

    @Test
    fun theChainNameChoosesTheEndpoint() {
        server.enqueue(account("2000000"))
        val mainnetOnly = AlgorandAlgod(mapOf("mainnet" to localBase(), "testnet" to "http://example.com"))

        val balance = runBlocking { mainnetOnly.execute(NativeBalance(ALGORAND_MAINNET, address), cfg, http) }

        assertEquals(BigInteger("2000000"), balance)
        assertEquals("/v2/accounts/$address", server.takeRequest().path)
    }

    @Test
    fun thePublicEndpointsAreHttpsAndServeBothNetworks() {
        assertEquals("https://testnet-api.algonode.cloud", AlgorandAlgod.ENDPOINTS["testnet"])
        assertEquals("https://mainnet-api.algonode.cloud", AlgorandAlgod.ENDPOINTS["mainnet"])
        val provider = AlgorandAlgod()
        assertTrue(provider.supports(ALGORAND_TESTNET, OpKey.on(ALGORAND_TESTNET, "nativeBalance"), cfg))
        assertTrue(provider.supports(ALGORAND_MAINNET, OpKey.on(ALGORAND_MAINNET, "nativeBalance"), cfg))
    }

    @Test
    fun aPlainHttpEndpointIsNotUsed() {
        val provider = AlgorandAlgod(mapOf("testnet" to "http://example.com"))

        assertFalse(provider.supports(ALGORAND_TESTNET, OpKey.on(ALGORAND_TESTNET, "nativeBalance"), cfg))
    }

    @Test
    fun otherNetworksAndOperationsAreUnsupported() {
        assertFailsWith<ProviderError.Unsupported> { call(NativeBalance(NetworkId(Networks.evm(1), testnet = false), address)) }
        assertFailsWith<ProviderError.Unsupported> { call(TipHeight(ALGORAND_TESTNET)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aMalformedAddressIsNotSent() {
        assertFailsWith<ProviderError.NotFound> { call(NativeBalance(ALGORAND_TESTNET, "../$address")) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRateLimitIsRateLimitedWithItsRetryAfter() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))

        val error = assertFailsWith<ProviderError.RateLimited> { call(NativeBalance(ALGORAND_TESTNET, address)) }

        assertEquals(7_000L, error.retryAfterMs)
    }

    @Test
    fun aServerErrorIsTransient() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ALGORAND_TESTNET, address)) }
    }

    @Test
    fun aRedirectIsNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()))
        server.enqueue(account("1"))

        assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ALGORAND_TESTNET, address)) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anAnswerThatIsNotJsonIsTransient() {
        server.enqueue(MockResponse().setBody("<html>busy</html>"))

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ALGORAND_TESTNET, address)) }
    }

    @Test
    fun anAmountThatIsNotAWholeNumberOfMicroAlgosIsFatal() {
        val malformed = listOf(
            """{"address":"$address"}""",
            """{"amount":null}""",
            """{"amount":1.5}""",
            """{"amount":-1}""",
            """{"amount":"abc"}""",
        )
        malformed.forEach { server.enqueue(MockResponse().setBody(it)) }

        repeat(malformed.size) { assertFailsWith<ProviderError.Fatal> { call(NativeBalance(ALGORAND_TESTNET, address)) } }
    }

    @Test
    fun anAnswerLargerThanTheCapIsTransient() {
        val padding = "x".repeat(MAX_ANSWER_BYTES.toInt())
        server.enqueue(MockResponse().setBody("""{"amount":1,"pad":"$padding"}"""))

        assertFailsWith<ProviderError.Transient> { call(NativeBalance(ALGORAND_TESTNET, address)) }
    }

    @Test
    fun theCatalogListsTheAlgorandProvider() {
        assertEquals(listOf("algorand"), ProviderCatalog.algorandProviders().map { it.id })
    }
}
