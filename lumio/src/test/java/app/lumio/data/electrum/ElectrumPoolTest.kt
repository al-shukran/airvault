package app.lumio.data.electrum

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ElectrumPoolTest {
    private val identity = TestIdentities.server

    private fun server(port: Int) = ElectrumServer("localhost", Trust.Pinned(identity.spkiSha256), 100, port)

    /** A port that nothing listens on: the fake server that held it is closed, so a connection to it is refused at once. */
    private fun refusedPort(): Int = FakeElectrum(identity, answer = echoOnly).use { it.port }

    /** Sends [value] to the server as the echo method and returns what comes back. */
    private suspend fun ElectrumPool.echo(value: String): String = withServer { connection ->
        val call = RpcCall("echo", buildJsonArray { add(JsonPrimitive(value)) })
        connection.batch(listOf(call)).single().getOrThrow().jsonPrimitive.content
    }

    private val echoOnly: (String, JsonArray) -> Answer = { _, params -> Answer.Reply(params[0]) }

    @Test
    fun failsOverToTheNextServerAndPrefersTheOneThatAnswered(): Unit = runBlocking {
        FakeElectrum(identity, answer = echoOnly).use { fake ->
            val refused = refusedPort()
            val attempts = mutableListOf<Int>()
            val pool = ElectrumPool(
                servers = listOf(server(refused), server(fake.port)),
                clock = { 0L },
                open = { s ->
                    attempts += s.port
                    ElectrumConnection.open(s, 5_000, 5_000)
                },
            )
            assertEquals("a", pool.echo("a"))
            assertEquals("b", pool.echo("b"))
            // The first call tried the refused server, then the one that answered. The second went straight to the one that answered.
            assertEquals(listOf(refused, fake.port), attempts)
        }
    }

    @Test
    fun aServerThatFailedIsSkippedUntilItsCooldownEnds(): Unit = runBlocking {
        // The second server drops the connection on a request for "b". The first server is down from the start.
        val dropOnB: (String, JsonArray) -> Answer = { _, params ->
            if (params[0].jsonPrimitive.content == "b") Answer.Hangup else Answer.Reply(params[0])
        }
        FakeElectrum(identity, answer = echoOnly).use { first ->
            FakeElectrum(identity, answer = dropOnB).use { second ->
                val now = AtomicLong(0)
                val down = mutableSetOf(first.port)
                val attempts = mutableListOf<Int>()
                val pool = ElectrumPool(
                    servers = listOf(server(first.port), server(second.port)),
                    clock = { now.get() },
                    open = { s ->
                        attempts += s.port
                        if (s.port in down) throw ElectrumError.Transport("the server is down")
                        ElectrumConnection.open(s, 5_000, 5_000)
                    },
                )
                // The first server is down, so the pool moves on to the second, which answers.
                assertEquals("a", pool.echo("a"))
                assertEquals(listOf(first.port, second.port), attempts)

                // The second drops the connection, and the first is cooling, so no server is left to answer.
                val unavailable = assertFailsWith<ElectrumError.Unavailable> { pool.echo("b") }
                assertEquals(1, unavailable.failures.size)
                assertEquals(listOf(first.port, second.port), attempts)

                // Within the cooldown neither server is tried.
                now.set(ElectrumPool.COOLDOWN_MS - 1)
                assertFailsWith<ElectrumError.Unavailable> { pool.echo("c") }
                assertEquals(listOf(first.port, second.port), attempts)

                // After the cooldown the pool opens the second server again, and it answers.
                now.set(ElectrumPool.COOLDOWN_MS)
                down.clear()
                assertEquals("d", pool.echo("d"))
                assertEquals(listOf(first.port, second.port, second.port), attempts)
            }
        }
    }

    @Test
    fun aConnectionThatTheServerClosedIsReopenedWithoutCoolingTheServer(): Unit = runBlocking {
        val repeat = (System.getenv("PROOF_REPEAT") ?: "1").toInt()
        var failed = 0
        for (i in 1..repeat) {
            var phase = "start"
            try {
                FakeElectrum(identity, closeAfterLines = 2, answer = echoOnly).use { fake ->
                    val opened = mutableListOf<ElectrumConnection>()
                    val pool = ElectrumPool(
                        servers = listOf(server(fake.port)),
                        clock = { 0L },
                        open = { s -> ElectrumConnection.open(s, 5_000, 5_000).also { opened += it } },
                    )
                    phase = "echo-a"
                    assertEquals("a", pool.echo("a"))
                    phase = "await-closed"
                    withTimeout(5_000) { opened.single().awaitClosed() }
                    phase = "echo-b"
                    assertEquals("b", pool.echo("b"))
                    assertEquals(2, opened.size)
                }
            } catch (e: Throwable) {
                failed++
                println("PROOF-FAIL iteration=$i phase=$phase ${e}")
                if (e is ElectrumError.Unavailable) e.failures.forEach { it.printStackTrace(System.out) }
                e.printStackTrace(System.out)
            }
        }
        println("PROOF-RESULT repeat=$repeat failed=$failed")
        if (failed > 0) throw AssertionError("failed=$failed of $repeat")
    }
}
