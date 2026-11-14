package app.lensly.core.evm

import app.lensly.core.parse.CANNOT_BE_READ
import app.lensly.core.parse.ParserLimits
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.web3j.crypto.Keys

/**
 * Reads an EIP-712 typed data document, the JSON of eth_signTypedData, strictly. It accepts the four members of the format and
 * nothing else, and it reads each value as the type its declaration names: an integer in range, an address with a valid checksum
 * when it is mixed case, bytes of the declared length. A document outside that is refused with the reason. A domain must name a
 * chain ID, so every accepted message names the chain it is for.
 *
 * The parser runs in the Parser Sandbox. It never throws: every failure comes back as [Result.Rejected].
 */
object TypedDataParser {
    /** The outcome of [parse]: the typed data, or the reason it is refused. */
    sealed interface Result {
        class Ok(val data: TypedData) : Result

        class Rejected(val reason: String) : Result
    }

    /** Reads [document] as UTF-8 JSON in the EIP-712 format. */
    fun parse(document: ByteArray): Result = try {
        Result.Ok(Document(decodeUtf8(document)).build())
    } catch (e: IllegalArgumentException) {
        Result.Rejected(e.message ?: CANNOT_BE_READ)
    } catch (e: RuntimeException) {
        Result.Rejected(CANNOT_BE_READ)
    }
}

private val ROOT_MEMBERS = setOf("types", "primaryType", "domain", "message")

/** The members an EIP-712 domain may name when the document does not declare its type, in the order the standard lists them. */
private val DOMAIN_MEMBERS = listOf(
    TypedField("name", "string"),
    TypedField("version", "string"),
    TypedField("chainId", "uint256"),
    TypedField("verifyingContract", "address"),
    TypedField("salt", "bytes32"),
)

private const val MAX_NAME_SHOWN = 32
private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
private val HEX_BYTES = Regex("0x(?:[0-9a-fA-F]{2})*")
private val WHOLE_NUMBER = Regex("-?(?:0|[1-9][0-9]*)")
private val HEX_INTEGER = Regex("0x[0-9a-fA-F]{1,64}")

private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

private fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: CharacterCodingException) {
    fail("the document is not valid UTF-8")
}

/** A JSON value as the strict reader reads it. A number keeps its text, so an integer is read exactly. */
private sealed interface Json {
    class Obj(val members: Map<String, Json>) : Json

    class Arr(val items: List<Json>) : Json

    class Str(val value: String) : Json

    class Num(val text: String) : Json

    class Bool(val value: Boolean) : Json

    data object Null : Json
}

/**
 * Reads one JSON document. It refuses what the JSON grammar refuses, and also a duplicate member name, a lone surrogate escape and
 * text after the document. It counts values and nesting, so a document cannot exceed [ParserLimits].
 */
private class JsonReader(private val text: String) {
    private var at = 0
    private var values = 0

    fun document(): Json {
        val root = value(level = 0)
        skipSpace()
        if (at != text.length) fail("the document has text after its end")
        return root
    }

    /** The value at the cursor. [level] is the number of objects and arrays that enclose it. */
    private fun value(level: Int): Json {
        if (++values > ParserLimits.MAX_TYPED_VALUES) fail("the document holds more than ${ParserLimits.MAX_TYPED_VALUES} values")
        skipSpace()
        return when (peek()) {
            '{' -> obj(level + 1)
            '[' -> arr(level + 1)
            '"' -> Json.Str(string())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> number()
        }
    }

    private fun obj(level: Int): Json.Obj {
        checkDepth(level)
        at++
        val members = LinkedHashMap<String, Json>()
        skipSpace()
        if (peek() == '}') {
            at++
            return Json.Obj(members)
        }
        while (true) {
            skipSpace()
            if (peek() != '"') fail("a member name is not a string")
            val name = string()
            skipSpace()
            expect(':')
            val member = value(level)
            if (members.put(name, member) != null) fail("the member ${name.take(MAX_NAME_SHOWN)} appears twice")
            skipSpace()
            when (next()) {
                ',' -> Unit
                '}' -> return Json.Obj(members)
                else -> fail("an object has no comma or closing brace")
            }
        }
    }

    private fun arr(level: Int): Json.Arr {
        checkDepth(level)
        at++
        val items = ArrayList<Json>()
        skipSpace()
        if (peek() == ']') {
            at++
            return Json.Arr(items)
        }
        while (true) {
            items.add(value(level))
            skipSpace()
            when (next()) {
                ',' -> Unit
                ']' -> return Json.Arr(items)
                else -> fail("an array has no comma or closing bracket")
            }
        }
    }

    private fun checkDepth(level: Int) {
        if (level > ParserLimits.MAX_TYPED_DEPTH) fail("the document nests deeper than ${ParserLimits.MAX_TYPED_DEPTH} levels")
    }

    private fun string(): String {
        at++ // the opening quote
        val out = StringBuilder()
        while (true) {
            val c = next()
            when {
                c == '"' -> return out.toString()
                c == '\\' -> escape(out)
                c < ' ' -> fail("a string holds a control character")
                else -> out.append(c)
            }
        }
    }

    private fun escape(out: StringBuilder) {
        when (val c = next()) {
            '"', '\\', '/' -> out.append(c)
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                val unit = hex4()
                when (unit) {
                    in 0xD800..0xDBFF -> {
                        if (next() != '\\' || next() != 'u') fail("a surrogate is not paired")
                        val low = hex4()
                        if (low !in 0xDC00..0xDFFF) fail("a surrogate is not paired")
                        out.append(unit.toChar()).append(low.toChar())
                    }
                    in 0xDC00..0xDFFF -> fail("a surrogate is not paired")
                    else -> out.append(unit.toChar())
                }
            }
            else -> fail("a string holds an unknown escape")
        }
    }

    private fun hex4(): Int {
        if (at + 4 > text.length) fail("a unicode escape is cut short")
        val digits = text.substring(at, at + 4)
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("a unicode escape is not hex")
        at += 4
        return digits.toInt(16)
    }

    private fun number(): Json.Num {
        val start = at
        if (peek() == '-') at++
        if (!isDigit(peek())) fail("a value is not JSON")
        if (peek() == '0') at++ else digits()
        if (peek() == '.') {
            at++
            if (!isDigit(peek())) fail("a number has no digits after its point")
            digits()
        }
        if (peek() == 'e' || peek() == 'E') {
            at++
            if (peek() == '+' || peek() == '-') at++
            if (!isDigit(peek())) fail("a number has no digits in its exponent")
            digits()
        }
        return Json.Num(text.substring(start, at))
    }

    private fun digits() {
        while (isDigit(peek())) at++
    }

    private fun literal(word: String, value: Json): Json {
        if (!text.startsWith(word, at)) fail("a value is not JSON")
        at += word.length
        return value
    }

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    private fun skipSpace() {
        while (at < text.length && text[at] in " \t\n\r") at++
    }

    private fun peek(): Char = if (at < text.length) text[at] else '\u0000'

    private fun next(): Char {
        if (at >= text.length) fail("the document ends too early")
        return text[at++]
    }

    private fun expect(c: Char) {
        if (next() != c) fail("the document has no '$c' where one belongs")
    }
}

/** Converts a parsed document into [TypedData]. Each step names the member it checks, so a refusal says where it failed. */
private class Document(text: String) {
    private val root: Json.Obj = JsonReader(text).document() as? Json.Obj ?: fail("the document is not a JSON object")

    fun build(): TypedData {
        onlyMembers(root.members, ROOT_MEMBERS, "the document")
        val declared = typesOf(required(root.members, "types", "the document"))
        val primaryType = textOf(required(root.members, "primaryType", "the document"), "primaryType")
        val domainJson = objectOf(required(root.members, "domain", "the document"), "domain")
        val types = withDomainType(declared, domainJson)
        // The schema is checked before any value is read, so a struct that contains itself cannot recurse.
        TypedData.checkSchema(types)
        require(primaryType in types) { "primaryType ${primaryType.take(MAX_NAME_SHOWN)} is not one of the types" }
        val domain = structOf(EIP712_DOMAIN, domainJson, types, "domain")
        checkDomainChainId(types, domain)
        val message = structOf(primaryType, objectOf(required(root.members, "message", "the document"), "message"), types, "message")
        return TypedData(types, primaryType, domain, message)
    }

    private fun typesOf(json: Json): Map<String, List<TypedField>> {
        val members = objectOf(json, "types").members
        require(members.size <= ParserLimits.MAX_TYPED_TYPES) {
            "the types declare more than ${ParserLimits.MAX_TYPED_TYPES} structs"
        }
        val types = LinkedHashMap<String, List<TypedField>>()
        for ((name, list) in members) {
            val path = "types.${name.take(MAX_NAME_SHOWN)}"
            val items = arrayItems(list, path)
            require(items.size <= ParserLimits.MAX_TYPED_MEMBERS) {
                "$path declares more than ${ParserLimits.MAX_TYPED_MEMBERS} members"
            }
            types[name] = items.mapIndexed { index, member ->
                val memberPath = "$path[$index]"
                val fields = objectOf(member, memberPath)
                onlyMembers(fields.members, setOf("name", "type"), memberPath)
                TypedField(
                    name = textOf(required(fields.members, "name", memberPath), "$memberPath.name"),
                    type = textOf(required(fields.members, "type", memberPath), "$memberPath.type"),
                )
            }
        }
        return types
    }

    /** The declared types, plus EIP712Domain built from the domain's members when the document does not declare it. */
    private fun withDomainType(declared: Map<String, List<TypedField>>, domain: Json.Obj): Map<String, List<TypedField>> {
        if (EIP712_DOMAIN in declared) return declared
        for (name in domain.members.keys) {
            require(DOMAIN_MEMBERS.any { it.name == name }) {
                "domain member ${name.take(MAX_NAME_SHOWN)} is not an EIP-712 domain member"
            }
        }
        return declared + (EIP712_DOMAIN to DOMAIN_MEMBERS.filter { it.name in domain.members })
    }

    private fun checkDomainChainId(types: Map<String, List<TypedField>>, domain: TypedValue.StructValue) {
        val members = types.getValue(EIP712_DOMAIN)
        val chainId = members.firstOrNull { it.name == "chainId" } ?: fail("The typed data names no chain ID in its domain")
        require(chainId.type == "uint256") { "The domain chain ID must be a uint256" }
        val value = (domain.fields.getValue("chainId") as TypedValue.IntValue).value
        require(value.signum() > 0 && value.bitLength() < 63) { "The domain chain ID is outside the range Lensly reads" }
        members.firstOrNull { it.name == "verifyingContract" }?.let {
            require(it.type == "address") { "The domain verifying contract must be an address" }
        }
    }

    private fun structOf(
        type: String,
        json: Json.Obj,
        types: Map<String, List<TypedField>>,
        path: String,
    ): TypedValue.StructValue {
        val declared = types.getValue(type)
        onlyMembers(json.members, declared.map { it.name }.toSet(), path)
        val fields = LinkedHashMap<String, TypedValue>()
        for (field in declared) {
            val memberPath = "$path.${field.name}"
            fields[field.name] = valueOf(field.type, required(json.members, field.name, path), types, memberPath)
        }
        return TypedValue.StructValue(fields)
    }

    private fun valueOf(type: String, json: Json, types: Map<String, List<TypedField>>, path: String): TypedValue = when {
        TypedData.isArray(type) -> {
            val items = arrayItems(json, path)
            TypedData.fixedLengthOf(type)?.let { length ->
                require(items.size == length) { "$path must hold $length items" }
            }
            val element = TypedData.elementOf(type)
            TypedValue.ArrayValue(items.mapIndexed { index, item -> valueOf(element, item, types, "$path[$index]") })
        }
        type in types -> structOf(type, objectOf(json, path), types, path)
        else -> atomicOf(type, json, path)
    }

    private fun atomicOf(type: String, json: Json, path: String): TypedValue = when {
        type == "bool" -> TypedValue.FlagValue((json as? Json.Bool ?: fail("$path must be true or false")).value)
        type == "string" -> TypedValue.TextValue(textOf(json, path))
        type == "address" -> TypedValue.BytesValue(addressOf(textOf(json, path), path))
        type == "bytes" -> TypedValue.BytesValue(hexOf(textOf(json, path), path, length = null))
        type.startsWith("bytes") -> {
            val length = type.removePrefix("bytes").toInt()
            TypedValue.BytesValue(hexOf(textOf(json, path), path, length))
        }
        else -> TypedValue.IntValue(integerOf(json, path, signed = type.startsWith("int")))
    }

    private fun onlyMembers(members: Map<String, Json>, allowed: Set<String>, path: String) {
        for (name in members.keys) {
            require(name in allowed) { "$path has an unknown member ${name.take(MAX_NAME_SHOWN)}" }
        }
    }

    private fun required(members: Map<String, Json>, name: String, path: String): Json =
        members[name] ?: fail("$path is missing $name")

    private fun objectOf(json: Json, path: String): Json.Obj = json as? Json.Obj ?: fail("$path must be an object")

    private fun arrayItems(json: Json, path: String): List<Json> = (json as? Json.Arr ?: fail("$path must be an array")).items

    private fun textOf(json: Json, path: String): String = (json as? Json.Str ?: fail("$path must be text")).value
}

/** A checksummed or all-lower or all-upper address. A mixed-case address must carry the EIP-55 checksum. */
private fun addressOf(text: String, path: String): ByteArray {
    require(ADDRESS.matches(text)) { "$path is not an address" }
    val hex = text.substring(2)
    val mixed = hex != hex.lowercase() && hex != hex.uppercase()
    require(!mixed || Keys.toChecksumAddress(text) == text) { "$path has a wrong address checksum" }
    return hexBytes(hex)
}

/** The bytes of a 0x-prefixed hex text, with exactly [length] bytes when [length] is given. */
private fun hexOf(text: String, path: String, length: Int?): ByteArray {
    val valid = if (length == null) HEX_BYTES.matches(text) else Regex("0x[0-9a-fA-F]{${length * 2}}").matches(text)
    require(valid) { if (length == null) "$path is not hex bytes" else "$path must be $length bytes" }
    return hexBytes(text.substring(2))
}

private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** A uintN or intN, from a JSON whole number or a decimal or 0x-hex string. An unsigned type refuses a negative number. */
private fun integerOf(json: Json, path: String, signed: Boolean): BigInteger {
    val value: BigInteger = when (json) {
        is Json.Num -> {
            require(WHOLE_NUMBER.matches(json.text)) { "$path must be a whole number" }
            BigInteger(json.text)
        }
        is Json.Str -> when {
            WHOLE_NUMBER.matches(json.value) -> BigInteger(json.value)
            HEX_INTEGER.matches(json.value) -> BigInteger(json.value.substring(2), 16)
            else -> fail("$path is not a number")
        }
        else -> fail("$path is not a number")
    }
    require(signed || value.signum() >= 0) { "$path must not be negative" }
    return value
}
