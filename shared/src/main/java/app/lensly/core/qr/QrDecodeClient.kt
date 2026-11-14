package app.lensly.core.qr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What the app gets back for one frame. */
sealed interface QrDecodeOutcome {
    /** The sandbox answered. [texts] is empty when the frame holds no code. */
    data class Answered(val texts: List<String>) : QrDecodeOutcome

    /** The sandbox timed out, died, could not be reached or refused the frame. The code could not be read. */
    data object Unavailable : QrDecodeOutcome
}

/** The connection to the QR Decode Sandbox. */
interface QrDecodeTransport {
    /** Sends one frame and blocks until the reply. Throws when the sandbox cannot be reached or has died. */
    fun decode(luma: ByteArray, width: Int, height: Int): List<String>

    /** Drops the connection, so the next [decode] makes a new one. */
    fun disconnect()
}

/**
 * Sends frames to the QR Decode Sandbox, one call at a time. A call that takes longer than [timeoutMillis], or that fails,
 * is [QrDecodeOutcome.Unavailable] and drops the connection, so the next frame binds the sandbox again. A reply is checked
 * against [QrDecodeLimits] before it is returned.
 */
class QrDecodeClient(
    private val transport: QrDecodeTransport,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    /** Decodes one luminance frame of [width] x [height] bytes. Blocks for at most [timeoutMillis]. */
    fun decode(luma: ByteArray, width: Int, height: Int): QrDecodeOutcome {
        // A binder call cannot be interrupted, so each call runs on a thread that this method can stop waiting for.
        val call = FutureTask(Callable { transport.decode(luma, width, height) })
        calls.execute(call)
        return try {
            QrDecodeOutcome.Answered(QrDecodeLimits.acceptReply(call.get(timeoutMillis, TimeUnit.MILLISECONDS)))
        } catch (e: TimeoutException) {
            giveUp(call)
        } catch (e: ExecutionException) {
            giveUp(call)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            giveUp(call)
        }
    }

    /** Drops the connection. */
    fun close() = transport.disconnect()

    private fun giveUp(call: FutureTask<*>): QrDecodeOutcome {
        call.cancel(true)
        transport.disconnect()
        return QrDecodeOutcome.Unavailable
    }

    private companion object {
        /** The longest a frame may take. The first frame also binds the sandbox, and the decode follows. */
        const val TIMEOUT_MILLIS = 10_000L

        val calls = Executors.newCachedThreadPool { work -> Thread(work, "qr-decode-call").apply { isDaemon = true } }
    }
}
