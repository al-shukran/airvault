package app.lensly.core.parse

import app.lensly.core.evm.AccessListEntry
import app.lensly.core.evm.EIP712_DOMAIN
import app.lensly.core.evm.TxType
import app.lensly.core.evm.TypedData
import app.lensly.core.evm.TypedDataParser
import app.lensly.core.evm.TypedField
import app.lensly.core.evm.TypedValue
import app.lensly.core.evm.UnsignedTx
import app.lensly.core.registry.ContractCall
import app.lensly.core.registry.DecodedParam
import app.lensly.core.registry.StaticDecode
import app.lensly.core.registry.WhitelistParam
import app.lensly.core.ur.DataType
import app.lensly.core.ur.EthSignRequest
import app.lensly.core.ur.PathStep
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

/** Raised when a payload breaks the wire format or one of its limits. The app refuses the reply that carried it. */
class WireException(message: String) : Exception(message)

/**
 * The wire format between the app and the Parser Sandbox. A payload is a version, a tag, then the fields of its value. Every
 * length is prefixed and bounded, and every decoded value is checked against its schema, so a malformed or oversized reply is
 * refused rather than trusted. The encoder applies the same bounds, so an oversized value is never sent.
 */
object ParserWire {
    /** The method of a call that parses scanned parts. */
    const val SCAN = 1

    /** The method of a call that decodes the calldata of a whitelisted call. */
    const val DECODE_STATIC = 2

    /** The largest reply the app accepts. It carries the data to sign, the calldata and an access list, each within its limit. */
    const val MAX_REPLY_BYTES = 256 * 1024

    private const val VERSION = 1

    /** The tag that both kinds of reply share for a refusal. It carries the reason. */
    private const val REFUSED = 0
    private const val NOT_PART = 1
    private const val INCOMPLETE = 2
    private const val COMPLETE = 3
    private const val STATIC_OK = 1

    private const val NO_TRANSACTION = 0
    private const val TRANSACTION_OK = 1
    private const val TRANSACTION_REJECTED = 2
    private const val NO_TYPED = 0
    private const val TYPED_OK = 1
    private const val TYPED_REJECTED = 2
    private const val MAX_TYPED_NAME_BYTES = 256

    private const val MAX_TEXT_BYTES = 4096
    private const val MAX_PARTS = 4096
    private const val MAX_PART_BYTES = 8192
    private const val MAX_PATH_STEPS = 16
    private const val MAX_CALL_PARAMS = 32
    private const val MAX_WORD_BYTES = 32
    private const val REQUEST_ID_BYTES = 16
    private const val ADDRESS_BYTES = 20
    private const val MAX_SIGN_DATA = ParserLimits.MAX_SIGN_DATA_BYTES
    private const val MAX_CALLDATA = ParserLimits.MAX_CALLDATA_BYTES
    private const val MAX_CALLDATA_HEX = 2 + 2 * MAX_CALLDATA

    /** How deep a reply may nest multicalls. A deeper reply is broken. The app refuses a batch deeper than its own limit, with its reason. */
    private const val MAX_CALL_LEVELS = 8
    private const val ADDRESS_TEXT_BYTES = 42
    private const val MAX_ACCESS_LIST_ENTRIES = 4096
    private const val MAX_STORAGE_KEYS = 4096
    private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
    private val HEX_DATA = Regex("0x(?:[0-9a-fA-F]{2})*")
    private val STORAGE_KEY = Regex("0x[0-9a-fA-F]{64}")

    /** The reply both calls share when the sandbox cannot answer: a refusal with its reason. */
    fun encodeRefusal(reason: String): ByteArray = Out().apply {
        int(VERSION)
        int(REFUSED)
        text(reason)
    }.bytes()

    fun encodeScanRequest(parts: List<String>): ByteArray = Out().apply {
        int(VERSION)
        int(SCAN)
        count(parts.size, MAX_PARTS)
        parts.forEach { text(it, MAX_PART_BYTES) }
    }.bytes()

    fun decodeScanRequest(payload: ByteArray): List<String> = In(payload).run {
        method(SCAN)
        List(count(MAX_PARTS)) { text(MAX_PART_BYTES) }.also { done() }
    }

    fun encodeScanReply(reply: ScanReply): ByteArray = Out().apply {
        int(VERSION)
        when (reply) {
            is ScanReply.Refused -> {
                int(REFUSED)
                text(reply.reason)
            }
            ScanReply.NotPart -> int(NOT_PART)
            is ScanReply.Incomplete -> {
                int(INCOMPLETE)
                double(reply.progress)
            }
            is ScanReply.Complete -> {
                int(COMPLETE)
                parsed(reply.parsed)
            }
        }
    }.bytes()

    /** Decodes and checks a scan reply. Throws [WireException] for anything the schema or a limit refuses. */
    fun decodeScanReply(payload: ByteArray): ScanReply = In(payload).run {
        version()
        val reply = when (int()) {
            REFUSED -> ScanReply.Refused(text())
            NOT_PART -> ScanReply.NotPart
            INCOMPLETE -> ScanReply.Incomplete(double().also { if (!(it in 0.0..1.0)) throw WireException("progress is outside 0 to 1") })
            COMPLETE -> ScanReply.Complete(parsedOf())
            else -> throw WireException("unknown scan reply")
        }
        done()
        reply
    }

    fun encodeStaticRequest(calldata: ByteArray, params: List<WhitelistParam>): ByteArray = Out().apply {
        int(VERSION)
        int(DECODE_STATIC)
        blob(calldata, MAX_CALLDATA)
        count(params.size, MAX_CALL_PARAMS)
        params.forEach { param(it) }
    }.bytes()

    fun decodeStaticRequest(payload: ByteArray): Pair<ByteArray, List<WhitelistParam>> = In(payload).run {
        method(DECODE_STATIC)
        val calldata = blob(MAX_CALLDATA)
        val params = List(count(MAX_CALL_PARAMS)) { whitelistParam() }
        done()
        calldata to params
    }

    fun encodeStaticReply(decoded: StaticDecode): ByteArray = Out().apply {
        int(VERSION)
        when (decoded) {
            is StaticDecode.Refused -> {
                int(REFUSED)
                text(decoded.reason)
            }
            is StaticDecode.Ok -> {
                int(STATIC_OK)
                count(decoded.params.size, MAX_CALL_PARAMS)
                decoded.params.forEach {
                    param(it.param)
                    text(it.value)
                }
            }
        }
    }.bytes()

    /** Decodes and checks a static reply. Throws [WireException] for anything the schema or a limit refuses. */
    fun decodeStaticReply(payload: ByteArray): StaticDecode = In(payload).run {
        version()
        val reply = when (int()) {
            REFUSED -> StaticDecode.Refused(text())
            STATIC_OK -> StaticDecode.Ok(List(count(MAX_CALL_PARAMS)) { DecodedParam(whitelistParam(), text()) })
            else -> throw WireException("unknown static reply")
        }
        done()
        reply
    }

    private fun Out.request(request: EthSignRequest) {
        optional(request.requestId) { blob(it, REQUEST_ID_BYTES) }
        blob(request.signData, MAX_SIGN_DATA)
        int(request.dataType.wire)
        optional(request.chainId) { long(it) }
        count(request.derivationPath.size, MAX_PATH_STEPS)
        request.derivationPath.forEach {
            int(it.index)
            flag(it.hardened)
        }
        optional(request.sourceFingerprint) { int(it) }
        optional(request.address) { blob(it, ADDRESS_BYTES) }
        optional(request.origin) { text(it, EthSignRequest.MAX_PAYLOAD_BYTES) }
    }

    private fun In.requestOf(): EthSignRequest {
        val requestId = optional { exact(REQUEST_ID_BYTES) }
        val signData = blob(MAX_SIGN_DATA)
        if (signData.isEmpty()) throw WireException("signData is empty")
        val dataType = DataType.fromWire(int().toLong()) ?: throw WireException("unknown dataType")
        val chainId = optional { long().also { if (it < 0) throw WireException("negative chainId") } }
        val path = List(count(MAX_PATH_STEPS)) {
            val index = int()
            if (index < 0) throw WireException("negative path index")
            PathStep(index, flag())
        }
        val fingerprint = optional { int() }
        val address = optional { exact(ADDRESS_BYTES) }
        val origin = optional { text(EthSignRequest.MAX_PAYLOAD_BYTES) }
        return EthSignRequest(requestId, signData, dataType, chainId, path, fingerprint, address, origin)
    }

    private fun Out.parsed(parsed: ParsedRequest) {
        request(parsed.request)
        when (val tx = parsed.tx) {
            null -> int(NO_TRANSACTION)
            is UnsignedTx.Result.Ok -> {
                int(TRANSACTION_OK)
                transaction(tx.tx)
            }
            is UnsignedTx.Result.Rejected -> {
                int(TRANSACTION_REJECTED)
                text(tx.reason)
            }
        }
        optional(parsed.call) { contractCall(it) }
        optional(parsed.multicall) { batch(it, level = 1) }
        when (val typed = parsed.typed) {
            null -> int(NO_TYPED)
            is TypedDataParser.Result.Ok -> {
                int(TYPED_OK)
                typedData(typed.data)
            }
            is TypedDataParser.Result.Rejected -> {
                int(TYPED_REJECTED)
                text(typed.reason)
            }
        }
    }

    /** The parsed request that follows the reply tag. Its transaction and call must agree with its data type. */
    private fun In.parsedOf(): ParsedRequest {
        val request = requestOf()
        val tx: UnsignedTx.Result? = when (int()) {
            NO_TRANSACTION -> null
            TRANSACTION_OK -> UnsignedTx.Result.Ok(transactionOf())
            TRANSACTION_REJECTED -> UnsignedTx.Result.Rejected(text())
            else -> throw WireException("unknown transaction result")
        }
        val call = optional { contractCallOf() }
        val multicall = optional { batchOf(level = 1) }
        val typed: TypedDataParser.Result? = when (int()) {
            NO_TYPED -> null
            TYPED_OK -> TypedDataParser.Result.Ok(typedDataOf())
            TYPED_REJECTED -> TypedDataParser.Result.Rejected(text())
            else -> throw WireException("unknown typed data result")
        }
        if ((request.dataType == DataType.TYPED_DATA) != (typed != null)) {
            throw WireException("typed data result does not match the data type")
        }
        val isTransaction = request.dataType == DataType.TRANSACTION || request.dataType == DataType.TYPED_TRANSACTION
        if (isTransaction != (tx != null)) throw WireException("transaction result does not match the data type")
        if (call != null && tx !is UnsignedTx.Result.Ok) throw WireException("a contract call needs a decoded transaction")
        if (multicall != null && tx !is UnsignedTx.Result.Ok) throw WireException("a multicall needs a decoded transaction")
        if (tx is UnsignedTx.Result.Ok) {
            val expected = if (request.dataType == DataType.TRANSACTION) {
                listOf(TxType.LEGACY_EIP155)
            } else {
                listOf(TxType.EIP2930, TxType.EIP1559)
            }
            if (tx.tx.type !in expected) throw WireException("transaction type does not match the data type")
        }
        return ParsedRequest(request, tx, call, multicall, typed)
    }

    /**
     * The typed message, as its types, its primary type, then its domain and its message. A value is written as its type says:
     * a struct by its members in declared order, an array by its count and items, and an atomic value by its bytes or number.
     */
    private fun Out.typedData(data: TypedData) {
        count(data.types.size, ParserLimits.MAX_TYPED_TYPES)
        for ((name, members) in data.types) {
            text(name, MAX_TYPED_NAME_BYTES)
            count(members.size, ParserLimits.MAX_TYPED_MEMBERS)
            for (member in members) {
                text(member.name, MAX_TYPED_NAME_BYTES)
                text(member.type, MAX_TYPED_NAME_BYTES)
            }
        }
        text(data.primaryType, MAX_TYPED_NAME_BYTES)
        typedValue(EIP712_DOMAIN, data.domain, data.types)
        typedValue(data.primaryType, data.message, data.types)
    }

    private fun Out.typedValue(type: String, value: TypedValue, types: Map<String, List<TypedField>>) {
        when {
            TypedData.isArray(type) -> {
                val items = (value as? TypedValue.ArrayValue ?: throw WireException("an array value is missing")).items
                count(items.size, ParserLimits.MAX_TYPED_VALUES)
                items.forEach { typedValue(TypedData.elementOf(type), it, types) }
            }
            type in types -> {
                val struct = value as? TypedValue.StructValue ?: throw WireException("a struct value is missing")
                for (member in types.getValue(type)) {
                    typedValue(member.type, struct.fields.getValue(member.name), types)
                }
            }
            else -> when (value) {
                is TypedValue.IntValue -> {
                    flag(value.value.signum() < 0)
                    big(value.value.abs())
                }
                is TypedValue.BytesValue -> blob(value.bytes, ParserLimits.MAX_SIGN_DATA_BYTES)
                is TypedValue.FlagValue -> flag(value.value)
                is TypedValue.TextValue -> text(value.value, ParserLimits.MAX_SIGN_DATA_BYTES)
                else -> throw WireException("a typed value does not match its type")
            }
        }
    }

    /** The typed message that [typedData] wrote. The schema is checked before any value is read, so a cycle cannot recurse. */
    private fun In.typedDataOf(): TypedData {
        val types = LinkedHashMap<String, List<TypedField>>()
        repeat(count(ParserLimits.MAX_TYPED_TYPES)) {
            val name = text(MAX_TYPED_NAME_BYTES)
            val members = List(count(ParserLimits.MAX_TYPED_MEMBERS)) {
                TypedField(text(MAX_TYPED_NAME_BYTES), text(MAX_TYPED_NAME_BYTES))
            }
            if (types.put(name, members) != null) throw WireException("a typed data type appears twice")
        }
        val primaryType = text(MAX_TYPED_NAME_BYTES)
        if (EIP712_DOMAIN !in types || primaryType !in types) throw WireException("the typed data names an unknown type")
        try {
            TypedData.checkSchema(types)
            val domain = typedValueOf(EIP712_DOMAIN, types) as TypedValue.StructValue
            val message = typedValueOf(primaryType, types) as TypedValue.StructValue
            return TypedData(types, primaryType, domain, message)
        } catch (e: IllegalArgumentException) {
            throw WireException(e.message ?: "the typed data is malformed")
        }
    }

    private fun In.typedValueOf(type: String, types: Map<String, List<TypedField>>): TypedValue = when {
        TypedData.isArray(type) -> {
            val element = TypedData.elementOf(type)
            TypedValue.ArrayValue(List(count(ParserLimits.MAX_TYPED_VALUES)) { typedValueOf(element, types) })
        }
        type in types -> {
            val fields = LinkedHashMap<String, TypedValue>()
            for (member in types.getValue(type)) fields[member.name] = typedValueOf(member.type, types)
            TypedValue.StructValue(fields)
        }
        type == "bool" -> TypedValue.FlagValue(flag())
        type == "string" -> TypedValue.TextValue(text(ParserLimits.MAX_SIGN_DATA_BYTES))
        type == "address" || type.startsWith("bytes") -> TypedValue.BytesValue(blob(ParserLimits.MAX_SIGN_DATA_BYTES))
        else -> {
            val negative = flag()
            val magnitude = big()
            TypedValue.IntValue(if (negative) magnitude.negate() else magnitude)
        }
    }

    private fun Out.transaction(tx: UnsignedTx) {
        int(tx.type.ordinal)
        big(tx.chainId)
        big(tx.nonce)
        optional(tx.to) { text(it) }
        big(tx.value)
        text(tx.data, MAX_CALLDATA_HEX)
        big(tx.gasLimit)
        optional(tx.gasPrice) { big(it) }
        optional(tx.maxPriorityFeePerGas) { big(it) }
        optional(tx.maxFeePerGas) { big(it) }
        count(tx.accessList.size, MAX_ACCESS_LIST_ENTRIES)
        tx.accessList.forEach { entry ->
            text(entry.address)
            count(entry.storageKeys.size, MAX_STORAGE_KEYS)
            entry.storageKeys.forEach { text(it) }
        }
    }

    private fun In.transactionOf(): UnsignedTx {
        val type = TxType.entries.getOrNull(int()) ?: throw WireException("unknown transaction type")
        val chainId = big().also { if (it.signum() <= 0) throw WireException("chainId must be positive") }
        val nonce = big()
        val to = optional { text() }?.also { if (!ADDRESS.matches(it)) throw WireException("to is not an address") }
        val value = big()
        val data = text(MAX_CALLDATA_HEX).also { if (!HEX_DATA.matches(it)) throw WireException("data is not hex") }
        val gasLimit = big()
        val gasPrice = optional { big() }
        val maxPriorityFee = optional { big() }
        val maxFee = optional { big() }
        val accessList = List(count(MAX_ACCESS_LIST_ENTRIES)) { accessListEntryOf() }
        val gasPriced = type != TxType.EIP1559
        val feesMatch = (gasPrice != null) == gasPriced && (maxPriorityFee != null) != gasPriced && (maxFee != null) != gasPriced
        if (!feesMatch) throw WireException("fee fields do not match the transaction type")
        if (type == TxType.LEGACY_EIP155 && accessList.isNotEmpty()) throw WireException("a legacy transaction has no access list")
        return UnsignedTx(type, chainId, nonce, to, value, data, gasLimit, gasPrice, maxPriorityFee, maxFee, accessList)
    }

    private fun In.accessListEntryOf(): AccessListEntry {
        val address = text().also { if (!ADDRESS.matches(it)) throw WireException("access list address is not an address") }
        val keys = List(count(MAX_STORAGE_KEYS)) {
            text().also { if (!STORAGE_KEY.matches(it)) throw WireException("storage key is not 32 bytes") }
        }
        return AccessListEntry(address, keys)
    }

    private fun Out.contractCall(call: ContractCall) {
        when (call) {
            is ContractCall.Transfer -> {
                int(0)
                text(call.to)
                big(call.value)
            }
            is ContractCall.Approve -> {
                int(1)
                text(call.spender)
                big(call.value)
            }
            is ContractCall.TransferFrom -> {
                int(2)
                text(call.from)
                text(call.to)
                big(call.value)
            }
            is ContractCall.IncreaseAllowance -> {
                int(3)
                text(call.spender)
                big(call.addedValue)
            }
            is ContractCall.DecreaseAllowance -> {
                int(4)
                text(call.spender)
                big(call.subtractedValue)
            }
            is ContractCall.SetApprovalForAll -> {
                int(5)
                text(call.operator)
                flag(call.approved)
            }
            is ContractCall.SafeTransferFrom -> {
                int(6)
                text(call.from)
                text(call.to)
                big(call.tokenId)
            }
        }
    }

    private fun In.contractCallOf(): ContractCall {
        fun address(): String = text().also { if (!ADDRESS.matches(it)) throw WireException("not an address") }
        return when (int()) {
            0 -> ContractCall.Transfer(address(), big())
            1 -> ContractCall.Approve(address(), big())
            2 -> ContractCall.TransferFrom(address(), address(), big())
            3 -> ContractCall.IncreaseAllowance(address(), big())
            4 -> ContractCall.DecreaseAllowance(address(), big())
            5 -> ContractCall.SetApprovalForAll(address(), flag())
            6 -> ContractCall.SafeTransferFrom(address(), address(), big())
            else -> throw WireException("unknown contract call")
        }
    }

    /** The calls of a multicall. [level] is the level its calls sit at: 1 for the calls of the transaction's own multicall. */
    private fun Out.batch(multicall: Multicall, level: Int) {
        if (level > MAX_CALL_LEVELS) throw WireException("calls nest deeper than $MAX_CALL_LEVELS levels")
        optional(multicall.deadline) { big(it) }
        count(multicall.calls.size, ParserLimits.MAX_NESTED_NODES)
        multicall.calls.forEach { inner(it, level) }
    }

    private fun Out.inner(call: InnerCall, level: Int) {
        optional(call.target) { text(it, ADDRESS_TEXT_BYTES) }
        optional(call.allowFailure) { flag(it) }
        optional(call.value) { big(it) }
        blob(call.calldata, MAX_CALLDATA)
        optional(call.call) { contractCall(it) }
        optional(call.nested) { batch(it, level + 1) }
    }

    private fun In.batchOf(level: Int): Multicall {
        if (level > MAX_CALL_LEVELS) throw WireException("calls nest deeper than $MAX_CALL_LEVELS levels")
        val deadline = optional { big() }
        return Multicall(deadline, List(count(ParserLimits.MAX_NESTED_NODES)) { innerOf(level) })
    }

    private fun In.innerOf(level: Int): InnerCall {
        val target = optional { text(ADDRESS_TEXT_BYTES) }
            ?.also { if (!ADDRESS.matches(it)) throw WireException("target is not an address") }
        val allowFailure = optional { flag() }
        val value = optional { big() }
        val calldata = blob(MAX_CALLDATA)
        val call = optional { contractCallOf() }
        val nested = optional { batchOf(level + 1) }
        if (call != null && nested != null) throw WireException("a call is decoded or nested, not both")
        return InnerCall(target, allowFailure, value, calldata, call, nested)
    }

    private fun Out.param(param: WhitelistParam) {
        text(param.name)
        text(param.type)
        flag(param.tokenRef)
    }

    /** A whitelist parameter, built only when its name and type pass the checks of [WhitelistParam]. */
    private fun In.whitelistParam(): WhitelistParam {
        val name = text()
        val type = text()
        val tokenRef = flag()
        return try {
            WhitelistParam(name, type, tokenRef)
        } catch (e: IllegalArgumentException) {
            throw WireException("not a static parameter")
        }
    }

    private fun In.version() {
        if (int() != VERSION) throw WireException("unsupported wire version")
    }

    private fun In.method(expected: Int) {
        version()
        if (int() != expected) throw WireException("unexpected method")
    }

    /** Writes one payload. Every field is bounded before it is written. */
    private class Out {
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
    private class In(payload: ByteArray) {
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
}
