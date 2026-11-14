package app.lensly.core.qr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SharedMemory
import android.system.OsConstants
import java.util.concurrent.TimeUnit

/**
 * The binder to [QrDecodeService]. It binds on the first frame, and again after [disconnect]. A frame goes in as a shared
 * memory region that the sandbox maps read-only. The app closes its copy when the call returns.
 */
class BinderQrDecodeTransport(context: Context) : QrDecodeTransport, ServiceConnection {
    private val context = context.applicationContext
    private val lock = Object()
    private var bound = false
    private var sandbox: IQrDecoder? = null

    override fun decode(luma: ByteArray, width: Int, height: Int): List<String> {
        val target = connect()
        val frame = SharedMemory.create("qr-frame", luma.size)
        try {
            val region = frame.mapReadWrite()
            region.put(luma)
            SharedMemory.unmap(region)
            frame.setProtect(OsConstants.PROT_READ)
            // The frame is compact, so its row stride is its width.
            return target.decode(frame, width, height, width).toList()
        } finally {
            frame.close()
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            if (bound) context.unbindService(this)
            bound = false
            sandbox = null
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        synchronized(lock) {
            if (bound) sandbox = IQrDecoder.Stub.asInterface(service)
            lock.notifyAll()
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        synchronized(lock) { sandbox = null }
    }

    override fun onBindingDied(name: ComponentName) = disconnect()

    /** Binds if needed, then waits for the connection. The caller's timeout covers the whole call. */
    private fun connect(): IQrDecoder {
        synchronized(lock) {
            if (!bound) {
                bound = context.bindService(Intent(context, QrDecodeService::class.java), this, Context.BIND_AUTO_CREATE)
                check(bound) { "The QR decode sandbox could not be bound." }
            }
            val deadline = System.nanoTime() + CONNECT_WAIT_NANOS
            while (sandbox == null) {
                val left = deadline - System.nanoTime()
                check(left > 0) { "The QR decode sandbox did not connect." }
                lock.wait(TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1L))
            }
            return checkNotNull(sandbox)
        }
    }

    private companion object {
        /** The longest a call waits for the sandbox to connect. */
        val CONNECT_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5)
    }
}
