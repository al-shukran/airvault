package app.lensly.core.registry

/**
 * The owner's rule for a call Lensly cannot read: an Ethereum contract call that the signed whitelist does not approve, or a Solana
 * instruction that calls a program Lensly does not read. [code] is the value the settings store keeps.
 */
enum class UnknownCallPolicy(val code: Int, val label: String) {
    /** The call is reviewed under an error-level warning, with its raw data shown in full, and the owner may sign it. The default. */
    WARN(1, "Warn"),

    /** The call is refused, and nothing is signed. */
    REFUSE(2, "Refuse"),
    ;

    companion object {
        val DEFAULT: UnknownCallPolicy = WARN

        /** The policy stored as [code], or null when no policy has that code. */
        fun ofCode(code: Int): UnknownCallPolicy? = entries.firstOrNull { it.code == code }
    }
}
