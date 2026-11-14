package app.lensly.core.parse

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

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
 * meanwhile waits for it, for as long as its caller does. Call it off the main thread, where the connection arrives.
 */
class BinderTransport(context: Context) : SandboxTransport, ServiceConnection {
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
            while (sandbox == null) lock.wait()
            checkNotNull(sandbox)
        }
        return checkNotNull(target.call(method, payload))
    }

    override fun reset() {
        synchronized(lock) {
            if (bound) context.unbindService(this)
            bound = false
            sandbox = null
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
}
