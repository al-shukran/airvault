package app.lumio.evm

import app.lumio.data.provider.ProviderError
import app.lumio.send.outcomeUnknown
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A broadcast over a pooled connection that the server drops after it read the request: the server holds one copy, and the outcome is unknown. */
class BroadcastSentOnceTest {
    private val server = MockWebServer()
    private val chain = EvmChain(1L, "Ethereum", "ETH", 18, testnet = false, explorerUrl = null)
    private val hash = "0x" + "ab".repeat(32)

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    private fun answer(result: String) = MockResponse.Builder().body("""{"jsonrpc":"2.0","id":1,"result":$result}""").build()

    @Test
    fun aBroadcastOnAPooledConnectionThatTheServerDropsIsSentOnceAndItsOutcomeIsUnknown() {
        val reads = testReads(EvmSettings(rpc = mapOf(1L to rpcInUse(server.url("/").toString()))))
        server.enqueue(answer("\"0x7\""))
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(answer("\"$hash\""))
        runBlocking { reads.nonce(chain, "0x" + "11".repeat(20), pending = false) }

        val failure = assertThrows(RpcFailure::class.java) { runBlocking { reads.broadcast(chain, byteArrayOf(0x02)) } }

        assertEquals(2, server.requestCount)
        assertFalse(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<ProviderError.Transient>().first().neverSent)
        assertTrue(outcomeUnknown(failure))
    }
}
