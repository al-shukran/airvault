package app.lensly.core.registry

import app.lensly.core.parse.ParserLimits

/**
 * One ABI type as a signature spells it. [canonical] is its text, and the registry accepts no other spelling: `uint256` and never
 * `uint`, `address[]`, `bytes32[2]`, `(address,uint24)` with no spaces. A signature hashes to its selector only in that spelling.
 *
 * An [AtomicType] is one 32-byte word. A [DynamicBytes] is `bytes` or `string`. An [ArrayType] is `T[]` or `T[k]`, and a [TupleType] is
 * `(T1,...,Tn)`. A [dynamic] type is encoded behind an offset, as the Solidity ABI spec lays it out; every other type sits in place.
 */
internal sealed interface AbiType {
    val canonical: String

    /** True when the value is encoded behind an offset: bytes, string, a dynamic array, or a tuple or array that holds one. */
    val dynamic: Boolean

    /** The bytes the type takes where it sits: one word for a dynamic type (its offset), its full size for a static one. */
    val headBytes: Long

    /** The nesting of arrays and tuples: 0 for a word or bytes, 1 for `uint256[]`, 2 for `(uint256[],bool)[]`. */
    val depth: Int
}

/** address, bool, uintN, intN or bytesN: one word. */
internal class AtomicType(override val canonical: String) : AbiType {
    override val dynamic: Boolean get() = false
    override val headBytes: Long get() = WORD
    override val depth: Int get() = 0
}

/** bytes or string: a length word, then the data padded to whole words. */
internal class DynamicBytes(override val canonical: String) : AbiType {
    override val dynamic: Boolean get() = true
    override val headBytes: Long get() = WORD
    override val depth: Int get() = 0
}

/** `T[k]` when [length] is set, and `T[]` when it is null. */
internal class ArrayType(val element: AbiType, val length: Int?) : AbiType {
    override val canonical: String = element.canonical + "[" + (length?.toString() ?: "") + "]"
    override val dynamic: Boolean = length == null || element.dynamic
    override val headBytes: Long = if (length == null || element.dynamic) WORD else saturate(length.toLong() * element.headBytes)
    override val depth: Int = element.depth + 1
}

/** `(T1,...,Tn)`, a struct of the fields in order. */
internal class TupleType(val components: List<AbiType>) : AbiType {
    override val canonical: String = components.joinToString(",", "(", ")") { it.canonical }
    override val dynamic: Boolean = components.any { it.dynamic }
    override val headBytes: Long =
        if (dynamic) WORD else saturate(components.fold(0L) { sum, component -> saturate(sum + component.headBytes) })
    override val depth: Int = 1 + (components.maxOfOrNull { it.depth } ?: 0)
}

private const val WORD = 32L

/** The largest size a static type reports. A larger type cannot fit in any calldata, so its exact size does not matter. */
private const val SIZE_CAP = 1L shl 40

private fun saturate(size: Long): Long = minOf(size, SIZE_CAP)

/**
 * Strict parsing of ABI types and signatures. Only the canonical spelling parses, a type nests at most [ParserLimits.MAX_ABI_DEPTH]
 * levels deep, and a fixed array holds at most [ParserLimits.MAX_ABI_ARRAY_LENGTH] items. Anything else is null.
 */
internal object AbiTypes {

    /** The type [text] spells in canonical form, or null when it is not one. */
    fun parse(text: String): AbiType? {
        val reader = Reader(text)
        val type = reader.type(depth = 0) ?: return null
        return type.takeIf { reader.atEnd() && it.canonical == text }
    }

    /**
     * The name and the parameter types of [signature], such as `swap(address,(uint256,bool)[])`, or null when it is not a canonical
     * signature. The name is an identifier, and every type is written as [parse] writes it.
     */
    fun signature(signature: String): Pair<String, List<AbiType>>? {
        val reader = Reader(signature)
        val name = reader.identifier() ?: return null
        if (!reader.consume('(')) return null
        val types = ArrayList<AbiType>()
        if (reader.peek() != ')') {
            do {
                types += reader.type(depth = 0) ?: return null
            } while (reader.consume(','))
        }
        if (!reader.consume(')') || !reader.atEnd()) return null
        val canonical = name + types.joinToString(",", "(", ")") { it.canonical }
        return if (canonical == signature) Pair(name, types) else null
    }

    /** A cursor over one spelling. Each method returns null, and never throws, when the text is not canonical. */
    private class Reader(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos == text.length

        fun peek(): Char? = text.getOrNull(pos)

        fun consume(c: Char): Boolean {
            if (peek() != c) return false
            pos++
            return true
        }

        fun identifier(): String? {
            val start = pos
            if (peek()?.let { isLetter(it) || it == '_' } != true) return null
            while (peek()?.let { isLetter(it) || isDigit(it) || it == '_' } == true) pos++
            return text.substring(start, pos)
        }

        /** One type at [depth], then its array suffixes. A tuple is `(`, its types separated by commas, and `)`. */
        fun type(depth: Int): AbiType? {
            if (depth > ParserLimits.MAX_ABI_DEPTH) return null
            var type = (if (peek() == '(') tuple(depth) else atomic()) ?: return null
            while (consume('[')) {
                val start = pos
                while (peek()?.let(::isDigit) == true) pos++
                val digits = text.substring(start, pos)
                val length = if (digits.isEmpty()) {
                    null
                } else {
                    canonicalNumber(digits, 1, ParserLimits.MAX_ABI_ARRAY_LENGTH) ?: return null
                }
                if (!consume(']')) return null
                type = ArrayType(type, length)
                if (type.depth > ParserLimits.MAX_ABI_DEPTH) return null
            }
            return type
        }

        private fun tuple(depth: Int): AbiType? {
            consume('(')
            val components = ArrayList<AbiType>()
            do {
                components += type(depth + 1) ?: return null
            } while (consume(','))
            if (!consume(')')) return null
            return TupleType(components)
        }

        /** A word or bytes type. A width is written in decimal with no leading zero, and the name alone is `bytes` or `string`. */
        private fun atomic(): AbiType? {
            val start = pos
            while (peek()?.let { isLetter(it) || isDigit(it) } == true) pos++
            val word = text.substring(start, pos)
            return when {
                word == "address" || word == "bool" -> AtomicType(word)
                word == "bytes" || word == "string" -> DynamicBytes(word)
                word.startsWith("uint") && width(word.removePrefix("uint")) -> AtomicType(word)
                word.startsWith("int") && width(word.removePrefix("int")) -> AtomicType(word)
                word.startsWith("bytes") && canonicalNumber(word.removePrefix("bytes"), 1, WORD_BYTES) != null -> AtomicType(word)
                else -> null
            }
        }

        /** An integer width: a multiple of 8 from 8 to 256. */
        private fun width(digits: String): Boolean = canonicalNumber(digits, 8, 256)?.let { it % 8 == 0 } == true

        private fun isLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

        private fun isDigit(c: Char): Boolean = c in '0'..'9'
    }

    /** [digits] as a number in [min]..[max], or null unless it is written in decimal with no leading zero. */
    private fun canonicalNumber(digits: String, min: Int, max: Int): Int? {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' } || (digits.length > 1 && digits[0] == '0')) return null
        return digits.toIntOrNull()?.takeIf { it in min..max }
    }

    private const val WORD_BYTES = 32
}
