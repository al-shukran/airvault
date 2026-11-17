package app.lensly.core.parse

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.TimeUnit

/**
 * The Parser Sandbox. The manifest declares it an isolated process, so the parsers that run here have no permissions, no
 * app files and no Keystore. It answers each call with [answer] and keeps nothing between calls.
 */
class ParserSandboxService : Service() {
    private val binder = object : IParserSandbox.Stub() {
        override fun call(method: Int, payload: ByteArray): ByteArray = answer(InProcessParser, method, payload)
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

/**
 * The binder to [ParserSandboxService]. It binds on the first call. The system restarts a sandbox that died, and a call made
 * meanwhile waits for it, for at most [connectNanos]. A [reset] ends that wait at once. Call it off the main thread, where the
 * connection arrives.
 */
class BinderTransport(
    context: Context,
    private val connectNanos: Long = CONNECT_NANOS,
) : SandboxTransport, ServiceConnection {
    private val context = context.applicationContext
    private val lock = Object()
    private var bound = false
    private var sandbox: IParserSandbox? = null

    override fun call(method: Int, payload: ByteArray): ByteArray {
        val target = synchronized(lock) {
            if (!bound) {
                bound = context.bindService(Intent(context, ParserSandboxService::class.java), this, Context.BIND_AUTO_CREATE)
                check(bound) { "The Parser Sandbox could not be bound." }
            }
            awaitSandbox()
        }
        return checkNotNull(target.call(method, payload))
    }

    /** Waits for the connection, for at most [connectNanos], and fails when the binding is dropped meanwhile. Called under [lock]. */
    private fun awaitSandbox(): IParserSandbox {
        val deadline = System.nanoTime() + connectNanos
        while (bound && sandbox == null) {
            val left = deadline - System.nanoTime()
            check(left > 0) { "The Parser Sandbox did not connect in time." }
            TimeUnit.NANOSECONDS.timedWait(lock, left)
        }
        return checkNotNull(sandbox) { "The Parser Sandbox was dropped." }
    }

    override fun reset() {
        synchronized(lock) {
            if (bound) context.unbindService(this)
            bound = false
            sandbox = null
            lock.notifyAll()
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        synchronized(lock) {
            if (bound) sandbox = IParserSandbox.Stub.asInterface(service)
            lock.notifyAll()
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        synchronized(lock) { sandbox = null }
    }

    override fun onBindingDied(name: ComponentName) = reset()

    private companion object {
        /**
         * How long a call waits for the sandbox to connect. It ends before the caller's own timeout, so a sandbox that never
         * connects is refused there.
         */
        const val CONNECT_NANOS = 4_000_000_000L
    }
}
