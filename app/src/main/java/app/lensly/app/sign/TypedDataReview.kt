package app.lensly.app.sign

import app.lensly.core.evm.EIP712_DOMAIN
import app.lensly.core.evm.TypedData
import app.lensly.core.evm.TypedField
import app.lensly.core.evm.TypedValue
import app.lensly.core.registry.Amounts
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.wallet.toHex
import app.lensly.ui.sign.TypedReview
import app.lensly.ui.sign.TypedRow
import app.lensly.ui.sign.UNKNOWN_TOKEN_NAME
import app.lensly.ui.sign.unlimitedApprovalWarning
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.web3j.crypto.Keys

/** The review of one typed message: what the screen shows, the parameter rows a record keeps, and what to warn about. */
internal class TypedDataReview(
    val review: TypedReview,
    /** One row per value, labelled with its path in the message, in the order the screen shows them. */
    val params: List<Pair<String, String>>,
    val warnings: List<String>,
    /** The first token a Permit or Permit2 payload moves that the registry holds, for its logo. Null when none is known. */
    val token: TokenEntry?,
)

/**
 * Reads [typed] for the review on chain [chainId], whose network is named [networkName]. A Permit or Permit2 payload gets a summary of
 * its token, spender, amount and deadline. Every field of the message is listed, nested, so none goes unread.
 */
internal fun typedDataReview(typed: TypedData, registry: Registry, chainId: Long, networkName: String): TypedDataReview {
    val domain = domainRows(typed, networkName)
    val summary = permitSummary(typed, registry, chainId)
    val params = ArrayList<Pair<String, String>>(domain)
    params += summary?.rows.orEmpty()
    val fields = ArrayList<TypedRow>()
    for (member in typed.types.getValue(typed.primaryType)) {
        addRows(
            label = member.name,
            path = member.name,
            type = member.type,
            value = typed.message.fields.getValue(member.name),
            depth = 0,
            types = typed.types,
            rows = fields,
            params = params,
        )
    }
    return TypedDataReview(
        review = TypedReview(
            domain = domain,
            summary = summary?.rows.orEmpty(),
            fields = fields,
            hasUnknownToken = summary?.unknownToken == true,
        ),
        params = params,
        warnings = summary?.warnings.orEmpty(),
        token = summary?.firstKnownToken,
    )
}

/** The domain's members, with the chain ID named after its network. */
private fun domainRows(typed: TypedData, networkName: String): List<Pair<String, String>> =
    typed.types.getValue(EIP712_DOMAIN).map { member ->
        val text = valueText(member.type, typed.domain.fields.getValue(member.name))
        when (member.name) {
            "name" -> "Name" to text
            "version" -> "Version" to text
            "chainId" -> "Chain ID" to "$text ($networkName)"
            "verifyingContract" -> "Verifying contract" to text
            "salt" -> "Salt" to text
            else -> member.name to text
        }
    }

/** Adds the lines of one value: a header line for a struct or an array, then its members one level deeper, or one value line. */
private fun addRows(
    label: String,
    path: String,
    type: String,
    value: TypedValue,
    depth: Int,
    types: Map<String, List<TypedField>>,
    rows: MutableList<TypedRow>,
    params: MutableList<Pair<String, String>>,
) {
    when {
        TypedData.isArray(type) -> {
            rows += TypedRow(depth, label, type, value = null)
            val element = TypedData.elementOf(type)
            (value as TypedValue.ArrayValue).items.forEachIndexed { index, item ->
                addRows("[$index]", "$path[$index]", element, item, depth + 1, types, rows, params)
            }
        }
        type in types -> {
            rows += TypedRow(depth, label, type, value = null)
            val struct = value as TypedValue.StructValue
            for (member in types.getValue(type)) {
                addRows(
                    label = member.name,
                    path = "$path.${member.name}",
                    type = member.type,
                    value = struct.fields.getValue(member.name),
                    depth = depth + 1,
                    types = types,
                    rows = rows,
                    params = params,
                )
            }
        }
        else -> {
            val text = valueText(type, value)
            rows += TypedRow(depth, label, type, text)
            params += path to text
        }
    }
}

/** A value as the review reads it: digits, a checksummed address, hex bytes, true or false, or text. */
private fun valueText(type: String, value: TypedValue): String = when (value) {
    is TypedValue.IntValue -> value.value.toString()
    is TypedValue.BytesValue -> if (type == "address") checksumOf(value.bytes) else "0x" + value.bytes.toHex()
    is TypedValue.FlagValue -> value.value.toString()
    is TypedValue.TextValue -> when {
        value.value.isEmpty() -> "(empty)"
        value.value.all(::isPrintable) -> value.value
        else -> "0x" + value.value.toByteArray(StandardCharsets.UTF_8).toHex() + " (not printable)"
    }
    is TypedValue.StructValue, is TypedValue.ArrayValue -> ""
}

private fun checksumOf(bytes: ByteArray): String = Keys.toChecksumAddress("0x" + bytes.toHex())

/** The lines a Permit or Permit2 payload shows. The rows, the warnings and the tokens the registry holds. */
private class Summary(
    val rows: List<Pair<String, String>>,
    val warnings: List<String>,
    val firstKnownToken: TokenEntry?,
    val unknownToken: Boolean,
)

/** Collects the summary rows of a payload. A token the registry does not hold sets [unknownToken]. */
private class PermitSummary(private val registry: Registry, private val chainId: Long) {
    private val rows = ArrayList<Pair<String, String>>()
    private val warnings = ArrayList<String>()
    private var firstKnownToken: TokenEntry? = null
    private var unknownToken = false

    fun row(label: String, value: String) {
        rows += label to value
    }

    /** A token row: its name and symbol when the registry holds it, otherwise Unknown token with its address. */
    fun token(label: String, contract: ByteArray): TokenEntry? {
        val address = checksumOf(contract)
        val entry = registry.token(chainId, address)
        if (entry == null) unknownToken = true
        if (entry != null && firstKnownToken == null) firstKnownToken = entry
        rows += label to (if (entry != null) "${entry.name} (${entry.symbol}) $address" else "$UNKNOWN_TOKEN_NAME $address")
        return entry
    }

    /**
     * An amount of [bits] bits. It is unlimited when it is at least 2^255 or the largest value its type holds. The decimals of a
     * known token format it; an unknown token shows the raw integer.
     */
    fun amount(label: String, raw: BigInteger, bits: Int, token: TokenEntry?) {
        val unlimited = raw >= TWO_255 || raw == BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        val amount = if (token != null) "${Amounts.format(raw, token.decimals)} ${token.symbol}" else "$raw (decimals unknown)"
        rows += label to (if (unlimited) "Unlimited (raw $raw)" else amount)
        if (unlimited) warnings += unlimitedApprovalWarning(token?.symbol)
    }

    fun date(label: String, seconds: BigInteger) {
        rows += label to dateText(seconds)
    }

    fun summary() = Summary(rows, warnings, firstKnownToken, unknownToken)
}

/** The Permit and Permit2 layouts a review reads: the members each primary type holds, and the members of the structs it uses. */
private val PERMIT_LAYOUTS: Map<String, List<Pair<String, String>>> = mapOf(
    "Permit" to listOf(
        "owner" to "address",
        "spender" to "address",
        "value" to "uint256",
        "nonce" to "uint256",
        "deadline" to "uint256",
    ),
    "PermitSingle" to listOf("details" to "PermitDetails", "spender" to "address", "sigDeadline" to "uint256"),
    "PermitBatch" to listOf("details" to "PermitDetails[]", "spender" to "address", "sigDeadline" to "uint256"),
    "PermitTransferFrom" to listOf("permitted" to "TokenPermissions", "nonce" to "uint256", "deadline" to "uint256"),
    "PermitBatchTransferFrom" to listOf("permitted" to "TokenPermissions[]", "nonce" to "uint256", "deadline" to "uint256"),
)

private val PERMIT_NESTED_LAYOUTS: Map<String, List<Pair<String, String>>> = mapOf(
    "PermitDetails" to listOf("token" to "address", "amount" to "uint160", "expiration" to "uint48", "nonce" to "uint48"),
    "TokenPermissions" to listOf("token" to "address", "amount" to "uint256"),
)

private const val ANY_SUBMITTER = "Anyone who submits this signature"
private val TWO_255: BigInteger = BigInteger.ONE.shiftLeft(255)
private val MAX_INSTANT_SECONDS: BigInteger = BigInteger.valueOf(Instant.MAX.epochSecond)
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/**
 * The summary of a Permit (ERC-2612) or Permit2 payload: its token, spender, amount and deadline. Null for any other payload, and for a
 * payload whose members do not have the layout of its primary type, so every field of it is still shown and nothing is guessed.
 */
private fun permitSummary(typed: TypedData, registry: Registry, chainId: Long): Summary? {
    if (!hasPermitLayout(typed)) return null
    val summary = PermitSummary(registry, chainId)
    val message = typed.message.fields
    when (typed.primaryType) {
        "Permit" -> {
            val token = (typed.domain.fields["verifyingContract"] as? TypedValue.BytesValue)?.bytes
                ?.let { summary.token("Token", it) }
            summary.row("Owner", checksumOf(bytesOf(message, "owner")))
            summary.row("Spender", checksumOf(bytesOf(message, "spender")))
            summary.amount("Amount", uintOf(message, "value"), bits = 256, token = token)
            summary.date("Deadline", uintOf(message, "deadline"))
        }
        "PermitSingle" -> {
            val details = fieldsOf(message, "details")
            summary.row("Spender", checksumOf(bytesOf(message, "spender")))
            val token = summary.token("Token", bytesOf(details, "token"))
            summary.amount("Amount", uintOf(details, "amount"), bits = 160, token = token)
            summary.date("Expires", uintOf(details, "expiration"))
            summary.date("Signature deadline", uintOf(message, "sigDeadline"))
        }
        "PermitBatch" -> {
            summary.row("Spender", checksumOf(bytesOf(message, "spender")))
            val details = itemsOf(message, "details")
            details.forEachIndexed { index, item ->
                val fields = (item as TypedValue.StructValue).fields
                val suffix = numbered(index, details.size)
                val token = summary.token("Token$suffix", bytesOf(fields, "token"))
                summary.amount("Amount$suffix", uintOf(fields, "amount"), bits = 160, token = token)
                summary.date("Expires$suffix", uintOf(fields, "expiration"))
            }
            summary.date("Signature deadline", uintOf(message, "sigDeadline"))
        }
        "PermitTransferFrom" -> {
            summary.row("Spender", ANY_SUBMITTER)
            val permitted = fieldsOf(message, "permitted")
            val token = summary.token("Token", bytesOf(permitted, "token"))
            summary.amount("Amount", uintOf(permitted, "amount"), bits = 256, token = token)
            summary.date("Deadline", uintOf(message, "deadline"))
        }
        "PermitBatchTransferFrom" -> {
            summary.row("Spender", ANY_SUBMITTER)
            val permitted = itemsOf(message, "permitted")
            permitted.forEachIndexed { index, item ->
                val fields = (item as TypedValue.StructValue).fields
                val suffix = numbered(index, permitted.size)
                val token = summary.token("Token$suffix", bytesOf(fields, "token"))
                summary.amount("Amount$suffix", uintOf(fields, "amount"), bits = 256, token = token)
            }
            summary.date("Deadline", uintOf(message, "deadline"))
        }
    }
    return summary.summary()
}

/** " 1", " 2" and so on for the items of a list of more than one, and nothing for a list of one. */
private fun numbered(index: Int, count: Int): String = if (count > 1) " ${index + 1}" else ""

/** True when the primary type is a Permit or Permit2 type and its members, and the structs it uses, have that type's layout. */
private fun hasPermitLayout(typed: TypedData): Boolean {
    val layout = PERMIT_LAYOUTS[typed.primaryType] ?: return false
    if (layoutOf(typed.types, typed.primaryType) != layout) return false
    return PERMIT_NESTED_LAYOUTS.all { (name, members) ->
        typed.types[name]?.let { layoutOf(typed.types, name) == members } ?: true
    }
}

private fun layoutOf(types: Map<String, List<TypedField>>, name: String): List<Pair<String, String>>? =
    types[name]?.map { it.name to it.type }

private fun fieldsOf(fields: Map<String, TypedValue>, name: String): Map<String, TypedValue> =
    (fields.getValue(name) as TypedValue.StructValue).fields

private fun itemsOf(fields: Map<String, TypedValue>, name: String): List<TypedValue> =
    (fields.getValue(name) as TypedValue.ArrayValue).items

private fun bytesOf(fields: Map<String, TypedValue>, name: String): ByteArray =
    (fields.getValue(name) as TypedValue.BytesValue).bytes

private fun uintOf(fields: Map<String, TypedValue>, name: String): BigInteger =
    (fields.getValue(name) as TypedValue.IntValue).value

/** A deadline as a UTC date and time, or the raw number when it is outside the dates a clock holds. */
private fun dateText(seconds: BigInteger): String =
    if (seconds.signum() < 0 || seconds > MAX_INSTANT_SECONDS) "$seconds (not a date)"
    else DATE.format(Instant.ofEpochSecond(seconds.toLong()))
