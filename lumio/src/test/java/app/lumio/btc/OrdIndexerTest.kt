package app.lumio.btc

import app.lensly.core.btc.BtcNetwork
import app.lumio.data.provider.Http
import app.lumio.withOverrides
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The Ordinals indexer, with a local MockWebServer: an answer marks an output only when it names an inscription or a rune. */
class OrdIndexerTest {
    private val ord = MockWebServer()
    private val txid = "ab".repeat(32)

    @Before
    fun start() {
        ord.start()
    }

    @After
    fun stop() {
        ord.shutdown()
    }

    private fun indexer(budgetMs: Long = INDEXER_BUDGET_MS): OrdIndexer = OrdIndexer(Http(OkHttpClient()), ord.url("/ord"), budgetMs)

    private fun answer(body: String): MockResponse = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun anInscriptionOrARuneOnTheOutputMarksIt() = runBlocking<Unit> {
        ord.enqueue(answer("""{"inscriptions":["${txid}i0"],"runes":{}}"""))
        ord.enqueue(answer("""{"inscriptions":[],"runes":{"DOG":{"amount":"1"}}}"""))
        ord.enqueue(answer("""{"inscriptions":[],"runes":[{"name":"DOG"}]}"""))

        assertEquals(setOf(txid to 0), indexer().markedOutputs(listOf(txid to 0)))
        assertEquals(setOf(txid to 1), indexer().markedOutputs(listOf(txid to 1)))
        assertEquals(setOf(txid to 2), indexer().markedOutputs(listOf(txid to 2)))
    }

    @Test
    fun eachOutputIsAskedForAtItsOwnPathWithJsonAccepted() = runBlocking<Unit> {
        ord.enqueue(answer("""{"inscriptions":[],"runes":{}}"""))

        indexer().markedOutputs(listOf(txid to 3))

        val asked = ord.takeRequest()
        assertEquals("/ord/output/$txid:3", asked.path)
        assertEquals("application/json", asked.getHeader("Accept"))
    }

    @Test
    fun anOutputWithNoInscriptionAndNoRuneIsUnmarked() = runBlocking<Unit> {
        ord.enqueue(answer("""{"inscriptions":[],"runes":{}}"""))
        ord.enqueue(answer("""{"inscriptions":[],"runes":[]}"""))

        assertEquals(emptySet(), indexer().markedOutputs(listOf(txid to 0)))
        assertEquals(emptySet(), indexer().markedOutputs(listOf(txid to 1)))
    }

    @Test
    fun aLookupThatRunsPastTheBudgetLeavesItsOutputUnmarked() = runBlocking<Unit> {
        // The headers arrive at once and the body only after 2 seconds, so the lookup is still waiting when the 300 ms budget ends. The delay stays
        // short, so the mock server can shut down in its teardown.
        ord.enqueue(answer("""{"inscriptions":["${txid}i0"],"runes":{}}""").setBodyDelay(2, TimeUnit.SECONDS))
        val started = System.nanoTime()

        assertEquals(emptySet(), indexer(budgetMs = 300L).markedOutputs(listOf(txid to 0)))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000L)
    }

    @Test
    fun aMalformedOrUnexpectedAnswerLeavesItsOutputUnmarked() = runBlocking<Unit> {
        val answers = listOf(
            "not json",
            "",
            "[]",
            """{"inscriptions":"$txid","runes":{}}""",
            """{"inscriptions":[],"runes":"DOG"}""",
            """{"inscriptions":null,"runes":null}""",
        )
        for (body in answers) {
            ord.enqueue(answer(body))
            assertEquals(emptySet(), indexer().markedOutputs(listOf(txid to 0)), body)
        }
    }

    @Test
    fun aMalformedInscriptionsFieldDoesNotHideARune() = runBlocking<Unit> {
        ord.enqueue(answer("""{"inscriptions":null,"runes":{"DOG":{}}}"""))
        ord.enqueue(answer("""{"inscriptions":"$txid","runes":{"DOG":{}}}"""))

        assertEquals(setOf(txid to 0), indexer().markedOutputs(listOf(txid to 0)))
        assertEquals(setOf(txid to 1), indexer().markedOutputs(listOf(txid to 1)))
    }

    @Test
    fun aMalformedRunesFieldDoesNotHideAnInscription() = runBlocking<Unit> {
        ord.enqueue(answer("""{"inscriptions":["${txid}i0"],"runes":"DOG"}"""))
        ord.enqueue(answer("""{"inscriptions":["${txid}i0"],"runes":null}"""))

        assertEquals(setOf(txid to 0), indexer().markedOutputs(listOf(txid to 0)))
        assertEquals(setOf(txid to 1), indexer().markedOutputs(listOf(txid to 1)))
    }

    @Test
    fun aServerErrorLeavesItsOutputUnmarked() = runBlocking<Unit> {
        ord.enqueue(MockResponse().setResponseCode(500))
        ord.enqueue(MockResponse().setResponseCode(404))

        assertEquals(emptySet(), indexer().markedOutputs(listOf(txid to 0)))
        assertEquals(emptySet(), indexer().markedOutputs(listOf(txid to 1)))
    }

    @Test
    fun anOutputWhoseIdIsNotATransactionIdIsNeverAsked() = runBlocking<Unit> {
        assertEquals(emptySet(), indexer().markedOutputs(listOf("../admin" to 0)))
        assertEquals(0, ord.requestCount)
    }

    @Test
    fun theIndexerIsOffUnlessTheOwnerSetsAServer() {
        assertNull(indexerOf(emptyMap(), Http(OkHttpClient())))

        val set = withIndexerUrl(emptyMap(), "https://ord.example.org")
        assertEquals("https://ord.example.org", indexerUrlOf(set))
        assertTrue(indexerOf(set, Http(OkHttpClient())) != null)
        assertNull(indexerUrlOf(withIndexerUrl(set, null)))
    }

    @Test
    fun savingTheEsploraServersKeepsTheIndexer() {
        val set = withIndexerUrl(emptyMap(), "https://ord.example.org")

        val saved = withOverrides(set, mapOf(BtcNetwork.MAINNET to "https://node.example.org/api"))

        assertEquals("https://ord.example.org", indexerUrlOf(saved))
        assertEquals(2, saved.size)
    }
}
