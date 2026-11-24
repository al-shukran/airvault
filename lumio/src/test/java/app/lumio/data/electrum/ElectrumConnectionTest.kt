package app.lumio.data.electrum

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ElectrumConnectionTest {
    private val identity = TestIdentities.server

    /** Answers each request with its first parameter. */
    private fun echo(method: String, params: JsonArray): Answer = Answer.Reply(params[0])

    private fun server(port: Int, maxBatch: Int = 100) = ElectrumServer("localhost", Trust.Pinned(identity.spkiSha256), maxBatch, port)

    private fun calls(values: List<String>) = values.map { value ->
        RpcCall("blockchain.scripthash.get_balance", buildJsonArray { add(JsonPrimitive(value)) })
    }

    @Test
    fun aBatchIsSplitAtTheServersLimitAndEachReplyMatchesItsCall(): Unit = runBlocking {
        FakeElectrum(identity, answer = ::echo).use { fake ->
            val connection = ElectrumConnection.open(server(fake.port, maxBatch = 20), 5_000, 5_000)
            val values = (0 until 45).map { "h$it" }
            val replies = connection.batch(calls(values))
            assertEquals(values, replies.map { it.getOrThrow().jsonPrimitive.content })
            // The handshake is a line of its own. The 45 calls go out as lines of 20, 20 and 5.
            assertEquals(listOf(1, 20, 20, 5), fake.lineSizes.toList())
            connection.close()
        }
    }

    @Test
    fun repliesThatArriveOutOfOrderAreMatchedByID(): Unit = runBlocking {
        FakeElectrum(identity, batchOrder = { it.reversed() }, answer = ::echo).use { fake ->
            val connection = ElectrumConnection.open(server(fake.port), 5_000, 5_000)
            val values = (0 until 10).map { "h$it" }
            assertEquals(values, connection.batch(calls(values)).map { it.getOrThrow().jsonPrimitive.content })
            connection.close()
        }
    }

    @Test
    fun aServerErrorFailsOnlyItsOwnCall(): Unit = runBlocking {
        val answer = { method: String, params: JsonArray ->
            if (params[0].jsonPrimitive.content == "bad") Answer.Fail("history too large") else echo(method, params)
        }
        FakeElectrum(identity, answer = answer).use { fake ->
            val connection = ElectrumConnection.open(server(fake.port), 5_000, 5_000)
            val replies = connection.batch(calls(listOf("a", "bad", "c")))
            assertEquals("a", replies[0].getOrThrow().jsonPrimitive.content)
            assertEquals("history too large", assertIs<ElectrumError.Rpc>(replies[1].exceptionOrNull()).message)
            assertEquals("c", replies[2].getOrThrow().jsonPrimitive.content)
            connection.close()
        }
    }

    @Test
    fun notificationsAreIgnored(): Unit = runBlocking {
        FakeElectrum(identity, notifications = true, answer = ::echo).use { fake ->
            val connection = ElectrumConnection.open(server(fake.port), 5_000, 5_000)
            assertEquals(listOf("x", "y"), connection.batch(calls(listOf("x", "y"))).map { it.getOrThrow().jsonPrimitive.content })
            connection.close()
        }
    }

    @Test
    fun aServerThatStopsAnsweringTimesOutAndTheConnectionCloses(): Unit = runBlocking {
        FakeElectrum(identity, answer = { _, _ -> Answer.Silent }).use { fake ->
            val connection = ElectrumConnection.open(server(fake.port), 5_000, 300)
            assertFailsWith<ElectrumError.Transport> { connection.batch(calls(listOf("x"))) }
            assertFalse(connection.isAlive)
        }
    }

    @Test
    fun aServerThatSpeaksAnOlderProtocolIsRefused(): Unit = runBlocking {
        FakeElectrum(identity, protocol = "1.3", answer = ::echo).use { fake ->
            assertFailsWith<ElectrumError.Transport> { ElectrumConnection.open(server(fake.port), 5_000, 5_000) }
        }
    }

    @Test
    fun aCertificateWithAnotherKeyIsRefusedBeforeAnyRequestIsSent(): Unit = runBlocking {
        FakeElectrum(identity, answer = ::echo).use { fake ->
            val pinnedToOther = ElectrumServer("localhost", Trust.Pinned(TestIdentities.other.spkiSha256), 100, fake.port)
            assertFailsWith<ElectrumError.Transport> { ElectrumConnection.open(pinnedToOther, 5_000, 5_000) }
            assertTrue(fake.lineSizes.isEmpty())
        }
    }

    @Test
    fun aSelfSignedCertificateIsRefusedWhereAnAuthorityIsExpected(): Unit = runBlocking {
        FakeElectrum(identity, answer = ::echo).use { fake ->
            val expectsAuthority = ElectrumServer("localhost", Trust.Authority, 100, fake.port)
            assertFailsWith<ElectrumError.Transport> { ElectrumConnection.open(expectsAuthority, 5_000, 5_000) }
            assertTrue(fake.lineSizes.isEmpty())
        }
    }
}
