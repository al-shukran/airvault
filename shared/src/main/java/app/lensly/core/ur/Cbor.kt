package app.lensly.core.ur

import app.lensly.core.parse.DecodeBudget
import app.lensly.core.parse.ParserLimits
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Raised for CBOR that is malformed or for a payload that breaks the schema of its UR type. */
internal class MalformedPayloadException(message: String) : Exception(message)

internal fun malformed(reason: String): Nothing = throw MalformedPayloadException(reason)

/**
 * The CBOR subset that Lensly's UR types use.
 *
 * Decoding is strict. Indefinite lengths, floats, simple values other than booleans, negative integers,
 * integers of 2^63 or more, and nesting deeper than [ParserLimits.MAX_CBOR_DEPTH] are rejected. A payload with more
 * than [ParserLimits.MAX_CBOR_ITEMS] items, or one that runs past its decode budget, is rejected too. Every declared
 * length is checked against the bytes that remain before anything is allocated.
 */
sealed class Cbor {
    class Uint(val value: Long) : Cbor() {
        init {
            require(value >= 0) { "CBOR unsigned integers must be non-negative" }
        }
    }

    class Bytes(val value: ByteArray) : Cbor()
    class Text(val value: String) : Cbor()
    class Bool(val value: Boolean) : Cbor()
    class Arr(val items: List<Cbor>) : Cbor()
    class Dict(val entries: List<Pair<Cbor, Cbor>>) : Cbor()
    class Tagged(val tag: Long, val value: Cbor) : Cbor()

    companion object {
        /** Decodes exactly one item that fills all of [bytes]. Throws [MalformedPayloadException] otherwise. */
        fun decode(
            bytes: ByteArray,
            budget: DecodeBudget = DecodeBudget("CBOR", ParserLimits.MAX_CBOR_ITEMS),
        ): Cbor {
            val reader = Reader(bytes, budget)
            val item = reader.read(0)
            if (reader.position != bytes.size) malformed("trailing bytes after CBOR item")
            return item
        }

        fun encode(item: Cbor): ByteArray {
            val out = ByteArrayOutputStream()
            write(out, item)
            return out.toByteArray()
        }

        private fun write(out: ByteArrayOutputStream, item: Cbor) {
            when (item) {
                is Uint -> head(out, MAJOR_UINT, item.value)
                is Bytes -> {
                    head(out, MAJOR_BYTES, item.value.size.toLong())
                    out.write(item.value)
                }
                is Text -> {
                    val utf8 = item.value.toByteArray(StandardCharsets.UTF_8)
                    head(out, MAJOR_TEXT, utf8.size.toLong())
                    out.write(utf8)
                }
                is Bool -> out.write(if (item.value) SIMPLE_TRUE else SIMPLE_FALSE)
                is Arr -> {
                    head(out, MAJOR_ARRAY, item.items.size.toLong())
                    item.items.forEach { write(out, it) }
                }
                is Dict -> {
                    head(out, MAJOR_MAP, item.entries.size.toLong())
                    item.entries.forEach {
                        write(out, it.first)
                        write(out, it.second)
                    }
                }
                is Tagged -> {
                    head(out, MAJOR_TAG, item.tag)
                    write(out, item.value)
                }
            }
        }

        /** Writes the initial byte and argument using the shortest form, as CBOR requires. */
        private fun head(out: ByteArrayOutputStream, major: Int, value: Long) {
            val type = major shl 5
            when {
                value < 24 -> out.write(type or value.toInt())
                value <= 0xFFL -> {
                    out.write(type or 24)
                    writeBigEndian(out, value, 1)
                }
                value <= 0xFFFFL -> {
                    out.write(type or 25)
                    writeBigEndian(out, value, 2)
                }
                value <= 0xFFFFFFFFL -> {
                    out.write(type or 26)
                    writeBigEndian(out, value, 4)
                }
                else -> {
                    out.write(type or 27)
                    writeBigEndian(out, value, 8)
                }
            }
        }

        private fun writeBigEndian(out: ByteArrayOutputStream, value: Long, length: Int) {
            for (shift in (length - 1) downTo 0) {
                out.write(((value ushr (shift * 8)) and 0xFF).toInt())
            }
        }

        private const val MAJOR_UINT = 0
        private const val MAJOR_BYTES = 2
        private const val MAJOR_TEXT = 3
        private const val MAJOR_ARRAY = 4
        private const val MAJOR_MAP = 5
        private const val MAJOR_TAG = 6
        private const val SIMPLE_FALSE = 0xF4
        private const val SIMPLE_TRUE = 0xF5
    }

    private class Reader(private val bytes: ByteArray, private val budget: DecodeBudget) {
        var position = 0
            private set

        private fun remaining(): Int = bytes.size - position

        private fun byte(): Int {
            if (remaining() < 1) malformed("truncated CBOR")
            return bytes[position++].toInt() and 0xFF
        }

        private fun unsigned(length: Int): Long {
            var value = 0L
            repeat(length) { value = (value shl 8) or byte().toLong() }
            return value
        }

        /** The argument that follows an initial byte. Indefinite lengths (31) and reserved values are rejected. */
        private fun argument(info: Int): Long {
            val value = when (info) {
                in 0..23 -> info.toLong()
                24 -> unsigned(1)
                25 -> unsigned(2)
                26 -> unsigned(4)
                27 -> unsigned(8)
                else -> malformed("unsupported CBOR length encoding $info")
            }
            if (value < 0) malformed("CBOR integer exceeds 2^63")
            return value
        }

        private fun take(length: Long): ByteArray {
            if (length < 0 || length > remaining()) malformed("truncated CBOR")
            val end = position + length.toInt()
            val slice = bytes.copyOfRange(position, end)
            position = end
            return slice
        }

        fun read(depth: Int): Cbor {
            budget.charge()?.let { malformed(it) }
            if (depth > ParserLimits.MAX_CBOR_DEPTH) malformed("CBOR nested deeper than ${ParserLimits.MAX_CBOR_DEPTH}")
            val initial = byte()
            val major = initial ushr 5
            val info = initial and 0x1F
            return when (major) {
                MAJOR_UINT -> Uint(argument(info))
                1 -> malformed("negative integers are not accepted")
                MAJOR_BYTES -> Bytes(take(argument(info)))
                MAJOR_TEXT -> Text(utf8(take(argument(info))))
                MAJOR_ARRAY -> {
                    val count = argument(info)
                    // Every item occupies at least one byte, so a larger count cannot fit.
                    if (count > remaining()) malformed("truncated CBOR")
                    Arr(List(count.toInt()) { read(depth + 1) })
                }
                MAJOR_MAP -> {
                    val count = argument(info)
                    // Every entry occupies at least two bytes.
                    if (count > remaining() / 2) malformed("truncated CBOR")
                    Dict(List(count.toInt()) { read(depth + 1) to read(depth + 1) })
                }
                MAJOR_TAG -> Tagged(argument(info), read(depth + 1))
                else -> when (info) {
                    20 -> Bool(false)
                    21 -> Bool(true)
                    else -> malformed("unsupported CBOR simple value or float")
                }
            }
        }

        private fun utf8(bytes: ByteArray): String = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            malformed("CBOR text is not valid UTF-8")
        }
    }
}
