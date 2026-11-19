package app.lensly.core.parse

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

private const val MAX_TEXT_BYTES = 4096
private const val MAX_WORD_BYTES = 32

/** Raised when a payload breaks the wire format or one of its limits. The app refuses the reply that carried it. */
class WireException(message: String) : Exception(message)

/** Writes one payload. Every field is bounded before it is written. */
class WireWriter {
    private val bytes = ByteArrayOutputStream()
    private val data = DataOutputStream(bytes)

    fun int(value: Int) = data.writeInt(value)

    fun long(value: Long) = data.writeLong(value)

    fun double(value: Double) = data.writeDouble(value)

    fun flag(value: Boolean) = data.writeByte(if (value) 1 else 0)

    fun count(size: Int, max: Int) {
        if (size > max) throw WireException("$size exceeds $max")
        int(size)
    }

    fun blob(value: ByteArray, max: Int) {
        if (value.size > max) throw WireException("${value.size} bytes exceeds $max")
        data.writeInt(value.size)
        data.write(value)
    }

    /** A blob of exactly [size] bytes, which [WireReader.exact] reads back. */
    fun exact(value: ByteArray, size: Int) {
        if (value.size != size) throw WireException("expected $size bytes")
        blob(value, size)
    }

    fun text(value: String, max: Int = MAX_TEXT_BYTES) = blob(value.toByteArray(StandardCharsets.UTF_8), max)

    /** A non-negative integer as its big-endian magnitude, at most 32 bytes. */
    fun big(value: BigInteger) {
        if (value.signum() < 0) throw WireException("negative integer")
        val raw = value.toByteArray()
        blob(if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw, MAX_WORD_BYTES)
    }

    fun <T : Any> optional(value: T?, write: (T) -> Unit) {
        flag(value != null)
        if (value != null) write(value)
    }

    fun bytes(): ByteArray {
        data.flush()
        return bytes.toByteArray()
    }
}

/** Reads one payload. Every length is checked against the bytes that remain before anything is allocated. */
class WireReader(payload: ByteArray) {
    private val buffer = ByteBuffer.wrap(payload)

    private fun need(count: Int) {
        if (buffer.remaining() < count) throw WireException("payload is truncated")
    }

    fun int(): Int {
        need(Int.SIZE_BYTES)
        return buffer.getInt()
    }

    fun long(): Long {
        need(Long.SIZE_BYTES)
        return buffer.getLong()
    }

    fun double(): Double {
        need(Double.SIZE_BYTES)
        return buffer.getDouble()
    }

    fun flag(): Boolean {
        need(1)
        return when (buffer.get().toInt()) {
            0 -> false
            1 -> true
            else -> throw WireException("flag is not 0 or 1")
        }
    }

    fun <T : Any> optional(read: () -> T): T? = if (flag()) read() else null

    fun count(max: Int): Int = int().also { if (it < 0 || it > max) throw WireException("count $it exceeds $max") }

    fun blob(max: Int): ByteArray {
        val size = int()
        if (size < 0 || size > max) throw WireException("length $size exceeds $max")
        need(size)
        return ByteArray(size).also { buffer.get(it) }
    }

    fun exact(size: Int): ByteArray = blob(size).also { if (it.size != size) throw WireException("expected $size bytes") }

    fun text(max: Int = MAX_TEXT_BYTES): String = try {
        StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(blob(max))).toString()
    } catch (e: CharacterCodingException) {
        throw WireException("text is not UTF-8")
    }

    fun big(): BigInteger = BigInteger(1, blob(MAX_WORD_BYTES))

    fun done() {
        if (buffer.hasRemaining()) throw WireException("trailing bytes")
    }
}
