package app.airvault.chains.evm.signer

import app.airvault.core.model.ChainId
import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.FieldPath
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.Warning
import app.airvault.core.model.isPrintableCodePoint
import app.lensly.core.registry.Amounts
import app.lensly.core.registry.Registry
import app.lensly.core.registry.TokenEntry
import app.lensly.core.wallet.toHex
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.web3j.crypto.Keys

/**
 * The rows and warnings of one typed message (EIP-712) as its review shows them: the domain, the summary of a Permit or Permit2 payload, and
 * every field of the message, nested. [decoded] holds every field the parser read. Each of them is a row of [domain], [summary] or [fields].
 */
internal class TypedPart(
    val domain: List<ReviewRow>,
    val summary: List<ReviewRow>,
    val fields: List<ReviewRow>,
    val warnings: List<Warning>,
    val decoded: Set<FieldPath>,
    val unknownToken: Boolean,
)

/** Reads [typed] for the review on [chain] (chain ID [chainId]), whose network is named [networkName]. */
internal fun typedPart(
    typed: TypedData,
    registry: Registry,
    chainId: Long,
    chain: ChainId,
    networkName: String,
    /** The name the domain's chain ID row gives it: the request's network, or the off-chain domain that names another chain. */
    domainNetworkName: String = networkName,
    /** The top-level `string` members of the message that hold an address. One that is a valid 0x address is shown as an address. */
    stringAddressMembers: Set<String> = emptySet(),
): TypedPart {
    val decoded = HashSet<FieldPath>()
    val domain = typed.types.getValue(EIP712_DOMAIN).map { member ->
        val field = FieldPath("eip712.domain.${member.name}")
        decoded += field
        val text = valueText(member.type, typed.domain.fields.getValue(member.name))
        when (member.name) {
            "name" -> ReviewRow("Name", DisplayValue.Text(text), field)
            "version" -> ReviewRow("Version", DisplayValue.Text(text), field)
            "chainId" -> ReviewRow("Chain ID", DisplayValue.Text("$text ($domainNetworkName)"), field)
            "verifyingContract" -> ReviewRow("Verifying Contract", DisplayValue.Address(chain, text), field)
            "salt" -> ReviewRow("Salt", DisplayValue.Text(text), field)
            else -> ReviewRow(member.name, DisplayValue.Text(text), field)
        }
    }
    val permit = permitSummary(typed, registry, chainId, chain)
    val fields = ArrayList<ReviewRow>()
    for (member in typed.types.getValue(typed.primaryType)) {
        val value = typed.message.fields.getValue(member.name)
        val address = (value as? TypedValue.TextValue)?.value
            ?.takeIf { member.type == "string" && member.name in stringAddressMembers && STRING_ADDRESS.matches(it) }
        if (address == null) {
            addRows(member.name, member.name, member.type, value, 0, typed.types, chain, fields, decoded)
        } else {
            // The signed value stays the string. Only its display changes: checksummed, grouped and matched like any other address.
            val field = FieldPath("eip712.${member.name}")
            decoded += field
            fields += ReviewRow("${member.name} (${member.type})", DisplayValue.Address(chain, Keys.toChecksumAddress(address)), field)
        }
    }
    return TypedPart(
        domain = domain,
        summary = permit?.rows.orEmpty(),
        fields = fields,
        warnings = permit?.warnings.orEmpty(),
        decoded = decoded,
        unknownToken = permit?.unknownToken == true,
    )
}

private val STRING_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

/** Adds the rows of one value: a header row for a struct or an array, then its members one level deeper, or one value row. */
private fun addRows(
    label: String,
    path: String,
    type: String,
    value: TypedValue,
    depth: Int,
    types: Map<String, List<TypedField>>,
    chain: ChainId,
    rows: MutableList<ReviewRow>,
    decoded: MutableSet<FieldPath>,
) {
    when {
        TypedData.isArray(type) -> {
            rows += ReviewRow("$label ($type)", null, null, depth)
            val element = TypedData.elementOf(type)
            (value as TypedValue.ArrayValue).items.forEachIndexed { index, item ->
                addRows("[$index]", "$path[$index]", element, item, depth + 1, types, chain, rows, decoded)
            }
        }
        type in types -> {
            rows += ReviewRow("$label ($type)", null, null, depth)
            val struct = value as TypedValue.StructValue
            for (member in types.getValue(type)) {
                addRows(
                    member.name,
                    "$path.${member.name}",
                    member.type,
                    struct.fields.getValue(member.name),
                    depth + 1,
                    types,
                    chain,
                    rows,
                    decoded,
                )
            }
        }
        else -> {
            val field = FieldPath("eip712.$path")
            decoded += field
            val shown = if (type == "address") DisplayValue.Address(chain, valueText(type, value)) else DisplayValue.Text(valueText(type, value))
            rows += ReviewRow("$label ($type)", shown, field, depth)
        }
    }
}

/** A value as the review reads it: digits, a checksummed address, hex bytes, true or false, or text. */
private fun valueText(type: String, value: TypedValue): String = when (value) {
    is TypedValue.IntValue -> value.value.toString()
    is TypedValue.BytesValue -> if (type == "address") checksumOf(value.bytes) else "0x" + value.bytes.toHex()
    is TypedValue.FlagValue -> value.value.toString()
    is TypedValue.TextValue -> if (value.value.isEmpty()) "(empty)" else printableOrHex(value.value)
    is TypedValue.StructValue, is TypedValue.ArrayValue -> error("the parser reads only atomic values here")
}

/**
 * [text] as a review shows it: the text itself when every character is printable, otherwise its UTF-8 bytes in hex. A backslash, a line
 * break, a tab and the Unicode line and paragraph separators are escaped, so the text stays on one line and two different strings never
 * show the same.
 */
internal fun printableOrHex(text: String): String =
    if (text.codePoints().allMatch(::isPrintableCodePoint)) {
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    } else {
        "0x" + text.toByteArray(StandardCharsets.UTF_8).toHex() + " (not printable)"
    }

private fun checksumOf(bytes: ByteArray): String = Keys.toChecksumAddress("0x" + bytes.toHex())

/** The rows a Permit or Permit2 payload shows, the warnings they earn, and whether the registry lacks one of its tokens. */
private class PermitRows(val rows: List<ReviewRow>, val warnings: List<Warning>, val unknownToken: Boolean)

/**
 * Collects the summary rows of a payload. Each row names the message field it shows, so the coverage check accounts for it. A token the
 * registry does not hold sets [unknownToken]. A null [chainId] looks up no token, so every token of the payload is unknown.
 */
private class PermitSummary(private val registry: Registry, private val chainId: Long?, private val chain: ChainId) {
    private val rows = ArrayList<ReviewRow>()
    private val warnings = ArrayList<Warning>()
    private var unknownToken = false

    fun address(label: String, address: ByteArray, field: FieldPath?) {
        rows += ReviewRow(label, DisplayValue.Address(chain, checksumOf(address)), field)
    }

    fun text(label: String, text: String, field: FieldPath?) {
        rows += ReviewRow(label, DisplayValue.Text(text), field)
    }

    /** A token row: its name and symbol when the registry holds it, otherwise Unknown token with its address. */
    fun token(label: String, contract: ByteArray, field: FieldPath?): TokenEntry? {
        val address = checksumOf(contract)
        val entry = chainId?.let { registry.token(it, address) }
        if (entry == null) unknownToken = true
        text(label, if (entry != null) "${entry.name} (${entry.symbol}) $address" else "$UNKNOWN_TOKEN_NAME $address", field)
        return entry
    }

    /**
     * An amount of [bits] bits. It is unlimited when [ContractCall.isUnlimited] reads it so, or when it is the largest value its type holds.
     * The decimals of a known token format it; an unknown token shows the raw integer.
     */
    fun amount(label: String, raw: BigInteger, bits: Int, token: TokenEntry?, field: FieldPath) {
        val unlimited = ContractCall.isUnlimited(raw) || raw == BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        val amount = if (token != null) "${Amounts.format(raw, token.decimals)} ${token.symbol}" else "$raw (decimals unknown)"
        text(label, if (unlimited) "Unlimited (raw $raw)" else amount, field)
        if (unlimited) warnings += Warning(Danger.DANGER, CODE_UNLIMITED, unlimitedApprovalWarning(token?.symbol), field, ACK_UNLIMITED)
    }

    fun date(label: String, seconds: BigInteger, field: FieldPath?) {
        text(label, dateText(seconds), field)
    }

    fun build() = PermitRows(rows, warnings, unknownToken)
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
    "PermitTransferFrom" to listOf(
        "permitted" to "TokenPermissions",
        "spender" to "address",
        "nonce" to "uint256",
        "deadline" to "uint256",
    ),
    "PermitBatchTransferFrom" to listOf(
        "permitted" to "TokenPermissions[]",
        "spender" to "address",
        "nonce" to "uint256",
        "deadline" to "uint256",
    ),
)

private val PERMIT_NESTED_LAYOUTS: Map<String, List<Pair<String, String>>> = mapOf(
    "PermitDetails" to listOf("token" to "address", "amount" to "uint160", "expiration" to "uint48", "nonce" to "uint48"),
    "TokenPermissions" to listOf("token" to "address", "amount" to "uint256"),
)

private val MAX_INSTANT_SECONDS: BigInteger = BigInteger.valueOf(Instant.MAX.epochSecond)
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/**
 * The summary of a Permit (ERC-2612) or Permit2 payload: its token, spender, amount and deadline. Null for any other payload, and for a
 * payload that lacks a member of the layout of its primary type, so every field of it is still shown and nothing is guessed.
 */
private fun permitSummary(typed: TypedData, registry: Registry, chainId: Long, chain: ChainId): PermitRows? {
    if (!hasPermitLayout(typed)) return null
    // A domain that names no chain is valid on every chain, so its tokens are not looked up on the chain the request is read on.
    val summary = PermitSummary(registry, if (typed.domainChainId == null) null else chainId, chain)
    val message = typed.message.fields
    fun field(path: String) = FieldPath("eip712.$path")
    when (typed.primaryType) {
        "Permit" -> {
            val token = (typed.domain.fields["verifyingContract"] as? TypedValue.BytesValue)?.bytes
                ?.let { summary.token("Token", it, field("domain.verifyingContract")) }
            summary.address("Owner", bytesOf(message, "owner"), field("owner"))
            summary.address("Spender", bytesOf(message, "spender"), field("spender"))
            summary.amount("Amount", uintOf(message, "value"), bits = 256, token = token, field = field("value"))
            summary.date("Deadline", uintOf(message, "deadline"), field("deadline"))
        }
        "PermitSingle" -> {
            val details = fieldsOf(message, "details")
            summary.address("Spender", bytesOf(message, "spender"), field("spender"))
            val token = summary.token("Token", bytesOf(details, "token"), field("details.token"))
            summary.amount("Amount", uintOf(details, "amount"), bits = 160, token = token, field = field("details.amount"))
            summary.date("Expires", uintOf(details, "expiration"), field("details.expiration"))
            summary.date("Signature Deadline", uintOf(message, "sigDeadline"), field("sigDeadline"))
        }
        "PermitBatch" -> {
            summary.address("Spender", bytesOf(message, "spender"), field("spender"))
            val details = itemsOf(message, "details")
            details.forEachIndexed { index, item ->
                val fields = (item as TypedValue.StructValue).fields
                val suffix = numbered(index, details.size)
                val path = "details[$index]"
                val token = summary.token("Token$suffix", bytesOf(fields, "token"), field("$path.token"))
                summary.amount("Amount$suffix", uintOf(fields, "amount"), bits = 160, token = token, field = field("$path.amount"))
                summary.date("Expires$suffix", uintOf(fields, "expiration"), field("$path.expiration"))
            }
            summary.date("Signature Deadline", uintOf(message, "sigDeadline"), field("sigDeadline"))
        }
        "PermitTransferFrom" -> {
            summary.address("Spender", bytesOf(message, "spender"), field("spender"))
            val permitted = fieldsOf(message, "permitted")
            val token = summary.token("Token", bytesOf(permitted, "token"), field("permitted.token"))
            summary.amount("Amount", uintOf(permitted, "amount"), bits = 256, token = token, field = field("permitted.amount"))
            summary.date("Deadline", uintOf(message, "deadline"), field("deadline"))
        }
        "PermitBatchTransferFrom" -> {
            summary.address("Spender", bytesOf(message, "spender"), field("spender"))
            val permitted = itemsOf(message, "permitted")
            permitted.forEachIndexed { index, item ->
                val fields = (item as TypedValue.StructValue).fields
                val suffix = numbered(index, permitted.size)
                val path = "permitted[$index]"
                val token = summary.token("Token$suffix", bytesOf(fields, "token"), field("$path.token"))
                summary.amount("Amount$suffix", uintOf(fields, "amount"), bits = 256, token = token, field = field("$path.amount"))
            }
            summary.date("Deadline", uintOf(message, "deadline"), field("deadline"))
        }
    }
    return summary.build()
}

/** " 1", " 2" and so on for the items of a list of more than one, and nothing for a list of one. */
private fun numbered(index: Int, count: Int): String = if (count > 1) " ${index + 1}" else ""

/** True when the primary type is a Permit or Permit2 type, and its members and the structs it uses declare that type's layout. */
private fun hasPermitLayout(typed: TypedData): Boolean {
    val layout = PERMIT_LAYOUTS[typed.primaryType] ?: return false
    if (!declaresLayout(typed.types, typed.primaryType, layout)) return false
    return PERMIT_NESTED_LAYOUTS.all { (name, members) ->
        typed.types[name]?.let { declaresLayout(typed.types, name, members) } ?: true
    }
}

/** True when the struct [name] declares every member of [layout] with its type. A member beyond the layout does not change the summary. */
private fun declaresLayout(types: Map<String, List<TypedField>>, name: String, layout: List<Pair<String, String>>): Boolean {
    val declared = types[name]?.map { it.name to it.type } ?: return false
    return layout.all { it in declared }
}

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
