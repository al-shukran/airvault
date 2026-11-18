package app.airvault.core.model

private val FIELD_KEY_FORMAT = Regex("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)*")

/** Key of a transaction extra. Keys are namespaced and stable, for example `algorand.note` (spec 6.1). */
@JvmInline
value class FieldKey(val value: String) {
    init {
        require(FIELD_KEY_FORMAT.matches(value)) { "field key is malformed: $value" }
    }

    override fun toString(): String = value
}

/**
 * A transaction extra the app declares (spec 6.1). [label] is the form label and uses CMOS title case. [danger] is the
 * minimum warning level when the field is set (spec 6.4).
 */
data class FieldSpec(
    val key: FieldKey,
    val kind: FieldKind,
    val label: String,
    val danger: Danger = Danger.INFO,
)

/** What a transaction extra holds. Kinds for other chains are added with those chains. */
sealed interface FieldKind {
    /**
     * Free bytes, such as a note. [maxBytes] null means no limit. [fixedLength] pins the size, as Algorand's
     * 32-byte lease does.
     */
    data class MemoBytes(val maxBytes: Int?, val fixedLength: Int? = null) : FieldKind

    /** An address the transaction gives authority or funds to, such as close-to or rekey-to. */
    data class AddressField(val role: AddressRole) : FieldKind
}

/** The part an address plays in a transaction. More roles arrive with the chains that need them. */
enum class AddressRole { CLOSE_TO, REKEY_TO }

/** Warning levels, lowest first (spec 3.2). The app picks the level; the core enforces what each level means. */
enum class Danger { INFO, CAUTION, DANGER, BLOCK }
