package app.lensly.core.parse

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Bounds a wait in a test, so a missing wake-up fails the test instead of hanging it. */
private const val WAIT_BOUND_SECONDS = 10L

@RunWith(RobolectricTestRunner::class)
class BinderTransportTest {

    /**
     * A context whose binding the test drives. [bindService] records the connection and reports success, and nothing connects until the
     * test says so.
     */
    private class BindingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val binds = AtomicInteger()

        @Volatile
        var connection: ServiceConnection? = null

        override fun getApplicationContext(): Context = this

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            binds.incrementAndGet()
            return true
        }

        override fun unbindService(conn: ServiceConnection) = Unit
    }

    @Test
    fun aCallThatNeverConnectsFailsAtItsWaitDeadline() {
        val transport = BinderTransport(BindingContext(), connectNanos = TimeUnit.MILLISECONDS.toNanos(50))

        val error = assertFailsWith<IllegalStateException> { transport.call(METHOD, ByteArray(0)) }
        assertEquals("The Parser Sandbox did not connect in time.", error.message)
    }

    @Test
    fun aResetEndsACallWaitingForTheSandboxAtOnce() {
        val context = BindingContext()
        val transport = BinderTransport(context)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val waiting = pool.submit<ByteArray> { transport.call(METHOD, ByteArray(0)) }
            awaitBinds(context)
            transport.reset()

            // The wait is the default one, four seconds. A reset that did not wake it would fail with the deadline's message.
            val error = assertFailsWith<ExecutionException> { waiting.get(WAIT_BOUND_SECONDS, TimeUnit.SECONDS) }
            assertEquals("The Parser Sandbox was dropped.", error.cause?.message)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aCallWaitingForTheSandboxGoesOnWhenItConnects() {
        val context = BindingContext()
        val transport = BinderTransport(context)
        val sandbox = object : IParserSandbox.Stub() {
            override fun call(method: Int, payload: ByteArray): ByteArray = byteArrayOf(method.toByte())
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val reply = pool.submit<ByteArray> { transport.call(METHOD, ByteArray(0)) }
            awaitBinds(context)
            context.connection?.onServiceConnected(ComponentName("app.lensly", "ParserSandboxService"), sandbox)

            assertContentEquals(byteArrayOf(METHOD.toByte()), reply.get(WAIT_BOUND_SECONDS, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    /** Waits until the transport has bound, so the call under test is waiting for the connection. */
    private fun awaitBinds(context: BindingContext) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_BOUND_SECONDS)
        while (context.binds.get() == 0 && System.nanoTime() < deadline) Thread.sleep(1)
        check(context.binds.get() > 0) { "The transport never bound." }
    }

    private companion object {
        /** The method of the calls in these tests. Any method works, as the stand-in answers every call. */
        val METHOD = ParserWire.SCAN
    }
}
