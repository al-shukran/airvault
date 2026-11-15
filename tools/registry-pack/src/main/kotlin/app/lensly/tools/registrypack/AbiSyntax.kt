package app.lensly.tools.registrypack

/**
 * An ABI type as a whitelist entry spells it. Only the canonical spelling is accepted, and the application (`AbiTypes` in `:shared`)
 * accepts the same spellings: `uint256` and never `uint`, `address[]`, `bytes32[2]`, and `(address,uint24)` with no spaces. A
 * signature hashes to its selector only in that spelling, so a second spelling would name another function.
 */
internal sealed class AbiType(val canonical: String) {
    /** The nesting of arrays and tuples: 0 for a word or bytes, 1 for `uint256[]`, 2 for `(uint256[],bool)[]`. */
    abstract val depth: Int

    /** One 32-byte word: address, bool, uintN, intN or bytesN. */
    class Atomic(canonical: String) : AbiType(canonical) {
        override val depth = 0
    }

    /** `bytes` or `string`: a length word, then the data. */
    class DynamicBytes(canonical: String) : AbiType(canonical) {
        override val depth = 0
    }

    /** `T[k]`, or `T[]` when [length] is null. */
    class ArrayOf(val element: AbiType, val length: Int?) :
        AbiType(element.canonical + "[" + (length?.toString() ?: "") + "]") {
        override val depth = element.depth + 1
    }

    /** `(T1,...,Tn)`: a struct of its fields, in order. */
    class TupleOf(val components: List<AbiType>) :
        AbiType(components.joinToString(",", "(", ")") { it.canonical }) {
        override val depth = 1 + (components.maxOfOrNull { it.depth } ?: 0)
    }
}

/**
 * Strict parsing of canonical ABI types and signatures. The limits are the application's (`ParserLimits` in `:shared`): a type nests at
 * most [MAX_DEPTH] levels, and a fixed array holds at most [MAX_ARRAY_LENGTH] items. Anything else parses to null.
 */
internal object AbiSyntax {
    const val MAX_DEPTH = 8
    const val MAX_ARRAY_LENGTH = 512
    private const val WORD_BYTES = 32

    /** The type [text] spells in canonical form, or null when it is not one. */
    fun type(text: String): AbiType? {
        val reader = Reader(text)
        val type = reader.type(depth = 0) ?: return null
        return type.takeIf { reader.atEnd() && it.canonical == text }
    }

    /** The name and the types of a canonical signature, such as `swap(address,(uint256,bool)[])`, or null when it is not one. */
    fun signature(text: String): Pair<String, List<AbiType>>? {
        val reader = Reader(text)
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
        return if (canonical == text) Pair(name, types) else null
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
            if (depth > MAX_DEPTH) return null
            var type = (if (peek() == '(') tuple(depth) else atomic()) ?: return null
            while (consume('[')) {
                val start = pos
                while (peek()?.let { isDigit(it) } == true) pos++
                val digits = text.substring(start, pos)
                val length = if (digits.isEmpty()) null else canonicalNumber(digits, 1, MAX_ARRAY_LENGTH) ?: return null
                if (!consume(']')) return null
                type = AbiType.ArrayOf(type, length)
                if (type.depth > MAX_DEPTH) return null
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
            return AbiType.TupleOf(components)
        }

        /** A word or bytes type. A width is written in decimal with no leading zero, and the name alone is `bytes` or `string`. */
        private fun atomic(): AbiType? {
            val start = pos
            while (peek()?.let { isLetter(it) || isDigit(it) } == true) pos++
            val word = text.substring(start, pos)
            return when {
                word == "address" || word == "bool" -> AbiType.Atomic(word)
                word == "bytes" || word == "string" -> AbiType.DynamicBytes(word)
                word.startsWith("uint") && width(word.removePrefix("uint")) -> AbiType.Atomic(word)
                word.startsWith("int") && width(word.removePrefix("int")) -> AbiType.Atomic(word)
                word.startsWith("bytes") && canonicalNumber(word.removePrefix("bytes"), 1, WORD_BYTES) != null -> AbiType.Atomic(word)
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
}
