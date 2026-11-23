package app.lumio.data.provider

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** [fetchBytes]: a body is capped while it streams, and a call is cancelled with its coroutine, as [fetchText] is. */
class ProviderHttpTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun aBodyAtTheLimitIsReadAndOneByteMoreIsFatal() {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(LIMIT))).build())
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(LIMIT + 1))).build())

        runBlocking {
            assertEquals(LIMIT, fetchBytes(client, request(server.url("/at").toString()), LIMIT.toLong()).size)
            assertFailsWith<ProviderError.Fatal> { fetchBytes(client, request(server.url("/over").toString()), LIMIT.toLong()) }
        }
    }

    @Test
    fun aBodyOverTheLimitIsCutOffWhileItStreams() {
        val served = AtomicLong()
        val streamed = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(CountingBody(size = 4L * LIMIT, served = served)).build()
        }.build()

        runBlocking {
            assertFailsWith<ProviderError.Fatal> { fetchBytes(streamed, request("https://img.example/logo.png"), LIMIT.toLong()) }
        }
        assertTrue(served.get() < 2L * LIMIT, "read ${served.get()} bytes of a body over the limit")
    }

    @Test
    fun cancellingTheCoroutineCancelsTheCall() {
        val waiting = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Boolean>()
        val blocking = OkHttpClient.Builder().addInterceptor { chain ->
            waiting.complete(Unit)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            cancelled.complete(chain.call().isCanceled())
            throw IOException("the call was cancelled or timed out")
        }.build()

        runBlocking {
            withTimeout(10_000) {
                val job = launch { fetchBytes(blocking, request("https://img.example/logo.png"), LIMIT.toLong()) }
                waiting.await()
                job.cancelAndJoin()
                assertTrue(cancelled.await(), "the coroutine's cancellation did not cancel the call")
            }
        }
    }

    private fun request(url: String): Request = Request.Builder().url(url).build()

    /** A body of [size] bytes that counts into [served] what is read from it. */
    private class CountingBody(private val size: Long, private val served: AtomicLong) : ResponseBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = size

        override fun source(): BufferedSource = object : Source {
            private var left = size

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (left == 0L) return -1L
                val n = minOf(byteCount, left)
                sink.write(ByteArray(n.toInt()))
                left -= n
                served.addAndGet(n)
                return n
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() {}
        }.buffer()
    }

    private companion object {
        const val LIMIT = 512 * 1024
    }
}
