package app.lensly.core.registry

/**
 * The owner's rule for a whitelisted target that is an upgradeable proxy. An offline phone cannot read a proxy's current
 * implementation, so the rule decides what happens to such a call. [code] is the value the settings store keeps.
 */
enum class ProxyPolicy(val code: Int) {
    /** A whitelisted proxy is refused, because its implementation cannot be confirmed offline. The default. */
    BIND_TO_IMPLEMENTATION(1),

    /** Every whitelisted proxy is refused. */
    REFUSE_ALL_PROXIES(2),

    /** A whitelisted proxy is reviewed under a red warning, and the owner confirms twice. */
    WARN_AND_CONFIRM(3),
    ;

    companion object {
        val DEFAULT: ProxyPolicy = BIND_TO_IMPLEMENTATION

        /** The policy stored as [code], or null when no policy has that code. */
        fun ofCode(code: Int): ProxyPolicy? = entries.firstOrNull { it.code == code }
    }
}
