package app.airvault.chains.evm.signer

import app.airvault.core.model.Danger
import app.airvault.core.model.DisplayValue
import app.airvault.core.model.ReviewRow
import app.airvault.core.model.ReviewSection
import app.airvault.core.model.Warning
import java.math.BigInteger

/*
 * The L1-signed messages of Lighter. lighter-go, types/txtypes/utils.go (the Template constants) and the GetL1SignatureBody of change_pub_key.go,
 * transfer.go and approve_integrator.go. Each is EIP-191 personal-message text. A number is "0x" and 16 lowercase hex digits
 * (getHex10FromUint64), a public key is 80 hex digits without a prefix, and a memo is 64 hex digits.
 */

/** A Lighter message: the section that labels its fields, and the warnings it earns. */
internal class LighterReview(val section: ReviewSection, val warnings: List<Warning>)

private const val FOOTER = "Only sign this message for a trusted client!"
private const val NUM = "(0x[0-9a-f]{16})"
private const val CODE_LIGHTER = "evm.personal-message.lighter"

private enum class LighterKind(val header: String, val action: String, val pattern: Regex, val fields: List<String>) {
    REGISTER(
        "Register Lighter Account", "Register an API key for a Lighter account",
        Regex("Register Lighter Account\n\npubkey: 0x([0-9a-f]{80})\nnonce: $NUM\naccount index: $NUM\napi key index: $NUM\n$FOOTER"),
        listOf("Public key", "Nonce", "Account index", "API key index"),
    ),
    TRANSFER(
        "Transfer", "Transfer funds between Lighter accounts",
        Regex(
            "Transfer\n\nnonce: $NUM\nfrom: $NUM \\(route $NUM\\)\napi key: $NUM\nto: $NUM \\(route $NUM\\)\nasset: $NUM\namount: $NUM\nfee: $NUM" +
                "\nchainId: $NUM\nmemo: ([0-9a-f]{64})\n$FOOTER",
        ),
        listOf(
            "Nonce", "From account index", "From route", "API key index", "To account index", "To route", "Asset index", "Amount", "Fee",
            "Chain ID", "Memo",
        ),
    ),
    SUB_ACCOUNT(
        "Create Lighter Sub Account", "Create a Lighter sub account",
        Regex("Create Lighter Sub Account\n\nmaster account index: $NUM\n$FOOTER"),
        listOf("Master account index"),
    ),
    AIRDROP(
        "Airdrop Allocation", "Allocate an airdrop",
        Regex("Airdrop Allocation\n\nallocations: ([^\n]+)\nchainId: $NUM\n$FOOTER"),
        listOf("Allocations", "Chain ID"),
    ),
    APPROVE_INTEGRATOR(
        "Approve Integrator", "Approve another Lighter account to charge fees",
        Regex(
            "Approve Integrator\n\nnonce: $NUM\naccount index: $NUM\napi key index: $NUM\nintegrator account index: $NUM\nmax perps taker fee: $NUM" +
                "\nmax perps maker fee: $NUM\nmax spot taker fee: $NUM\nmax spot maker fee: $NUM\napproval expiry: $NUM\nchainId: $NUM\n$FOOTER",
        ),
        listOf(
            "Nonce", "Account index", "API key index", "Integrator account index", "Max perps taker fee", "Max perps maker fee",
            "Max spot taker fee", "Max spot maker fee", "Approval expiry", "Chain ID",
        ),
    ),
}

/** The kind [text] opens as, by its header alone, or null. Close spellings count, so a message that nearly fits a template is not passed as plain. */
private fun headedAs(text: String): LighterKind? {
    val start = text.trimStart().lowercase()
    return LighterKind.entries.firstOrNull { kind ->
        val header = kind.header.lowercase()
        if (kind == LighterKind.TRANSFER) start.substringBefore('\n').trim() == header else start.startsWith(header)
    }
}

/**
 * The labelled review of a Lighter message, or null for a message that is not headed as one. A headed message that does not fit its template
 * exactly gets a CAUTION and no labelled fields. The message itself is always shown whole by the review.
 */
internal fun lighterReview(text: String): LighterReview? {
    val kind = headedAs(text) ?: return null
    val values = kind.pattern.matchEntire(text)?.groupValues?.drop(1)
    if (values == null) {
        val row = ReviewRow("Lighter Message", DisplayValue.Text(MALFORMED_NOTE), null)
        return LighterReview(
            ReviewSection("Lighter", listOf(row)),
            listOf(Warning(Danger.CAUTION, CODE_LIGHTER, MALFORMED_TEXT, acknowledge = ACK_MALFORMED)),
        )
    }
    val rows = mutableListOf(ReviewRow("Action", DisplayValue.Text(kind.action), null))
    kind.fields.zip(values).forEach { (label, value) -> rows += ReviewRow(label, DisplayValue.Text(shown(kind, label, value)), null) }
    return LighterReview(ReviewSection("Lighter", rows), warningsOf(kind, kind.fields.zip(values).toMap()))
}

/** A field as the review shows it: a number in decimal, and a key or memo as hex. */
private fun shown(kind: LighterKind, label: String, value: String): String = when {
    value.startsWith("0x") && value.length == 18 -> BigInteger(value.drop(2), 16).toString().let { decimal ->
        if (kind == LighterKind.TRANSFER && label == "Amount") "$decimal (smallest units of the asset)" else decimal
    }
    label == "Public key" -> "0x$value"
    label == "Memo" -> "0x$value"
    else -> value
}

private fun warningsOf(kind: LighterKind, fields: Map<String, String>): List<Warning> = when (kind) {
    LighterKind.REGISTER -> listOf(Warning(Danger.DANGER, CODE_LIGHTER, REGISTER_TEXT, acknowledge = ACK_REGISTER))
    LighterKind.APPROVE_INTEGRATOR -> listOf(Warning(Danger.DANGER, CODE_LIGHTER, INTEGRATOR_TEXT, acknowledge = ACK_INTEGRATOR))
    LighterKind.TRANSFER -> {
        val to = BigInteger(fields.getValue("To account index").drop(2), 16)
        listOf(Warning(Danger.CAUTION, CODE_LIGHTER, "This sends funds to Lighter account $to. Check the account and amount before you sign.", acknowledge = ACK_TRANSFER))
    }
    else -> emptyList()
}

private const val MALFORMED_NOTE = "This message starts like a Lighter message, but it does not match any format Lighter publishes."
private const val MALFORMED_TEXT = "This message starts like a Lighter message, but it does not match the format Lighter publishes, so Lensly " +
    "cannot explain it. Read all of it before you sign."
private const val ACK_MALFORMED = "I understand Lensly cannot tell what this Lighter message does."
private const val REGISTER_TEXT = "This registers an API key for your Lighter account. Whoever holds that key can act for your account. " +
    "Sign only if you made this key yourself."
private const val ACK_REGISTER = "I understand this lets another key act for my Lighter account."
private const val INTEGRATOR_TEXT = "This lets another Lighter account charge fees on your trades. Check the integrator account and the fee limits " +
    "before you sign."
private const val ACK_INTEGRATOR = "I understand this lets another Lighter account charge fees on my account."
private const val ACK_TRANSFER = "I understand this sends funds to another Lighter account."
