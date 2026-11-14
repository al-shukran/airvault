package app.lensly.core.evm

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import org.web3j.crypto.Hash

/** The name of the domain struct that every typed data message carries. */
const val EIP712_DOMAIN = "EIP712Domain"

/** One member of a struct: its name, and its type such as "address", "uint256[]" or "Person". */
data class TypedField(val name: String, val type: String)

/** A value of a typed message, held in the form its type asks for. An address and every bytes type are [BytesValue]. */
sealed interface TypedValue {
    /** A uintN or intN. */
    class IntValue(val value: BigInteger) : TypedValue

    /** An address (20 bytes), a bytes value of any length, or a bytesN value. */
    class BytesValue(val bytes: ByteArray) : TypedValue

    /** A bool. */
    class FlagValue(val value: Boolean) : TypedValue

    /** A string of Unicode text. */
    class TextValue(val value: String) : TypedValue

    /** A struct: its members by name, in the order the struct declares them. */
    class StructValue(val fields: Map<String, TypedValue>) : TypedValue

    /** An array, fixed or dynamic. */
    class ArrayValue(val items: List<TypedValue>) : TypedValue
}

/**
 * An EIP-712 typed data message: its types, its primary type, its domain and its message. [types] holds every struct type the
 * message uses, EIP712Domain included, in declared order. The constructor checks the types and every value against them, so an
 * instance hashes and displays exactly what its types declare. It applies no policy about which domains are acceptable:
 * [TypedDataParser] decides that.
 */
class TypedData(
    val types: Map<String, List<TypedField>>,
    val primaryType: String,
    val domain: TypedValue.StructValue,
    val message: TypedValue.StructValue,
) {
    init {
        checkSchema(types)
        require(primaryType in types) { "primaryType $primaryType is not a struct type" }
        require(EIP712_DOMAIN in types) { "the types declare no $EIP712_DOMAIN" }
        checkValue(EIP712_DOMAIN, domain, "domain", types)
        checkValue(primaryType, message, "message", types)
    }

    /** The domain's chain ID, or null when the domain holds none. */
    val domainChainId: BigInteger?
        get() = (domain.fields["chainId"] as? TypedValue.IntValue)?.value

    /** The digest that is signed: keccak256(0x1901 ‖ domainSeparator ‖ hashStruct(message)). */
    fun digest(): ByteArray = Hash.sha3(
        byteArrayOf(0x19, 0x01) + hashStruct(EIP712_DOMAIN, domain) + hashStruct(primaryType, message),
    )

    /** The EIP-712 encodeType of [type]: its own signature, then the struct types it uses, sorted by name. */
    internal fun encodeType(type: String): String {
        val referenced = sortedSetOf<String>()
        collectStructs(type, referenced)
        referenced.remove(type)
        return (listOf(type) + referenced).joinToString(separator = "") { name ->
            types.getValue(name).joinToString(separator = ",", prefix = "$name(", postfix = ")") { "${it.type} ${it.name}" }
        }
    }

    /** hashStruct of [value] as [type]: keccak256(typeHash ‖ encodeData). */
    internal fun hashStruct(type: String, value: TypedValue.StructValue): ByteArray {
        val typeHash = Hash.sha3(encodeType(type).toByteArray(StandardCharsets.UTF_8))
        return Hash.sha3(typeHash + encodeData(type, value))
    }

    private fun collectStructs(type: String, into: MutableSet<String>) {
        for (field in types.getValue(type)) {
            val base = baseTypeOf(field.type)
            if (base in types && into.add(base)) collectStructs(base, into)
        }
    }

    private fun encodeData(type: String, value: TypedValue.StructValue): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in types.getValue(type)) {
            out.write(encodeValue(field.type, value.fields.getValue(field.name)))
        }
        return out.toByteArray()
    }

    /** One 32-byte word of encodeData: the value of an atomic type, the hash of a dynamic or struct value. */
    private fun encodeValue(type: String, value: TypedValue): ByteArray = when {
        isArray(type) -> {
            val out = ByteArrayOutputStream()
            for (item in (value as TypedValue.ArrayValue).items) out.write(encodeValue(elementOf(type), item))
            Hash.sha3(out.toByteArray())
        }
        type in types -> hashStruct(type, value as TypedValue.StructValue)
        else -> encodeAtomic(type, value)
    }

    private fun encodeAtomic(type: String, value: TypedValue): ByteArray = when {
        type == "string" -> Hash.sha3((value as TypedValue.TextValue).value.toByteArray(StandardCharsets.UTF_8))
        type == "bytes" -> Hash.sha3((value as TypedValue.BytesValue).bytes)
        type == "bool" -> wordOf(if ((value as TypedValue.FlagValue).value) BigInteger.ONE else BigInteger.ZERO)
        type == "address" -> leftPad((value as TypedValue.BytesValue).bytes)
        type.startsWith("bytes") -> rightPad((value as TypedValue.BytesValue).bytes)
        else -> wordOf((value as TypedValue.IntValue).value)
    }

    internal companion object {
        /** The longest fixed array a type may name. */
        const val MAX_FIXED_ARRAY = 1024

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        private val ARRAY_SUFFIXES = Regex("(?:\\[\\]|\\[[1-9][0-9]{0,3}\\])*")
        private val ATOMIC_TYPES: Set<String> = setOf("address", "bool", "string", "bytes") +
            (1..32).map { "bytes$it" } + (8..256 step 8).flatMap { listOf("uint$it", "int$it") }
        private val TWO_256: BigInteger = BigInteger.ONE.shiftLeft(256)
        private val TWO_255: BigInteger = BigInteger.ONE.shiftLeft(255)

        /**
         * Refuses a type set that EIP-712 cannot hash: a name that is not an identifier, a repeated member, a member of a type that
         * is neither atomic nor declared, an array suffix that is malformed, or a struct that contains itself.
         */
        fun checkSchema(types: Map<String, List<TypedField>>) {
            require(types.isNotEmpty()) { "the types declare nothing" }
            for ((name, fields) in types) {
                require(IDENTIFIER.matches(name)) { "type name $name is not an identifier" }
                require(fields.map { it.name }.toSet().size == fields.size) { "type $name repeats a member" }
                for (field in fields) {
                    require(IDENTIFIER.matches(field.name)) { "member ${field.name} of $name is not an identifier" }
                    val base = baseTypeOf(field.type)
                    require(base in ATOMIC_TYPES || base in types) { "member ${field.name} of $name has unknown type ${field.type}" }
                    val suffixes = field.type.substring(base.length)
                    require(ARRAY_SUFFIXES.matches(suffixes)) { "member ${field.name} of $name has a malformed array type" }
                    fixedLengthOf(field.type)?.let { length ->
                        require(length <= MAX_FIXED_ARRAY) { "member ${field.name} of $name names an array longer than $MAX_FIXED_ARRAY" }
                    }
                }
            }
            val state = HashMap<String, Boolean>()
            fun visit(type: String) {
                when (state[type]) {
                    false -> throw IllegalArgumentException("type $type contains itself")
                    true -> return
                    null -> Unit
                }
                state[type] = false
                for (field in types.getValue(type)) {
                    val base = baseTypeOf(field.type)
                    if (base in types) visit(base)
                }
                state[type] = true
            }
            types.keys.forEach { visit(it) }
        }

        /** Checks [value] against [type], naming it [path] in a refusal. Arrays, structs and atomic values are all checked. */
        fun checkValue(type: String, value: TypedValue, path: String, types: Map<String, List<TypedField>>) {
            when {
                isArray(type) -> {
                    val array = value as? TypedValue.ArrayValue ?: fail("$path must be an array")
                    fixedLengthOf(type)?.let { length ->
                        require(array.items.size == length) { "$path must hold $length items" }
                    }
                    array.items.forEachIndexed { index, item -> checkValue(elementOf(type), item, "$path[$index]", types) }
                }
                type in types -> {
                    val struct = value as? TypedValue.StructValue ?: fail("$path must be an object")
                    val declared = types.getValue(type)
                    require(struct.fields.keys == declared.map { it.name }.toSet()) { "$path must hold exactly the members of $type" }
                    for (field in declared) {
                        checkValue(field.type, struct.fields.getValue(field.name), "$path.${field.name}", types)
                    }
                }
                else -> checkAtomic(type, value, path)
            }
        }

        private fun checkAtomic(type: String, value: TypedValue, path: String) {
            when {
                type == "bool" -> require(value is TypedValue.FlagValue) { "$path must be true or false" }
                type == "string" -> require(value is TypedValue.TextValue) { "$path must be text" }
                type == "address" -> require((value as? TypedValue.BytesValue)?.bytes?.size == 20) { "$path is not an address" }
                type == "bytes" -> require(value is TypedValue.BytesValue) { "$path must be bytes" }
                type.startsWith("bytes") -> {
                    val size = type.removePrefix("bytes").toInt()
                    require((value as? TypedValue.BytesValue)?.bytes?.size == size) { "$path must be $size bytes" }
                }
                else -> {
                    val number = (value as? TypedValue.IntValue)?.value ?: fail("$path must be a number")
                    val bits = type.filter(Char::isDigit).toInt()
                    if (type.startsWith("uint")) {
                        require(number.signum() >= 0 && number.bitLength() <= bits) { "$path is out of range for $type" }
                    } else {
                        require(number.bitLength() <= bits - 1) { "$path is out of range for $type" }
                    }
                }
            }
        }

        /** The 32-byte word of an integer in two's complement, as a uint or an int is hashed. */
        private fun wordOf(value: BigInteger): ByteArray {
            val raw = value.mod(TWO_256).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
            return ByteArray(32 - raw.size) + raw
        }

        private fun leftPad(bytes: ByteArray): ByteArray = ByteArray(32 - bytes.size) + bytes

        private fun rightPad(bytes: ByteArray): ByteArray = bytes + ByteArray(32 - bytes.size)

        /** The base type of [type] without its array suffixes: "Person" for "Person[][2]". */
        internal fun baseTypeOf(type: String): String = type.substringBefore('[')

        /** True when [type] ends in an array suffix. */
        internal fun isArray(type: String): Boolean = type.endsWith("]")

        /** The type of one item of the array [type]: "Person[]" for "Person[][2]". */
        internal fun elementOf(type: String): String = type.substring(0, type.lastIndexOf('['))

        /** The length of the outer array of [type], or null for a dynamic array or a type that is not an array. */
        internal fun fixedLengthOf(type: String): Int? {
            if (!isArray(type)) return null
            val inside = type.substring(type.lastIndexOf('[') + 1, type.length - 1)
            return inside.toIntOrNull()
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }
}
