package app.airvault.core.model

private val APP_ID_FORMAT = Regex("[a-z0-9-]+")
private val CAIP2_FORMAT = Regex("[-a-z0-9]{3,8}:[-_a-zA-Z0-9]{1,32}")
private val CAIP19_FORMAT = Regex("[-a-z0-9]{3,8}:[-_a-zA-Z0-9]{1,32}/[-a-z0-9]{3,8}:[-.%a-zA-Z0-9]{1,128}")
private val CAIP10_FORMAT = Regex("[-a-z0-9]{3,8}:[-_a-zA-Z0-9]{1,32}:[-.%a-zA-Z0-9]{1,128}")

/** Stable id of a chain app: lowercase letters, digits and hyphens (spec 2.1). */
@JvmInline
value class AppId(val value: String) {
    init {
        require(APP_ID_FORMAT.matches(value)) { "app id must match [a-z0-9-]+: $value" }
    }

    override fun toString(): String = value
}

/** A CAIP-2 chain id, `namespace:reference`, for example `algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k` (spec 4). */
@JvmInline
value class ChainId(val caip2: String) {
    init {
        require(CAIP2_FORMAT.matches(caip2)) { "CAIP-2 id must be namespace:reference: $caip2" }
    }

    val namespace: String get() = caip2.substringBefore(':')

    override fun toString(): String = caip2
}

/**
 * A CAIP-10 account id, `chain_id:address`, for example `algorand:wGHE2Pwdvd7S12BL5FaOP20EGYesN73k:<address>` (spec 4). The address is the
 * normalised form that the app's address codec returns, so two ids for one account compare equal.
 */
@JvmInline
value class AccountId(val caip10: String) {
    init {
        require(CAIP10_FORMAT.matches(caip10)) { "CAIP-10 account id is malformed: $caip10" }
    }

    /** The chain the account is on: the part before the last colon. */
    val chainId: ChainId get() = ChainId(caip10.substringBeforeLast(':'))

    /** The address: the part after the last colon. Addresses never contain a colon. */
    val address: String get() = caip10.substringAfterLast(':')

    override fun toString(): String = caip10

    companion object {
        /** The id of [address] on [chainId]. */
        fun of(chainId: ChainId, address: String): AccountId = AccountId("${chainId.caip2}:$address")
    }
}

/**
 * A CAIP-19 asset id, `chain/namespace:reference` (spec 4). CAIP-19 is in Review status; this pins the syntax of
 * that draft. Native coins use the `slip44` namespace, for example `algorand:.../slip44:283`.
 */
@JvmInline
value class AssetId(val caip19: String) {
    init {
        require(CAIP19_FORMAT.matches(caip19)) { "CAIP-19 asset id is malformed: $caip19" }
    }

    override fun toString(): String = caip19
}
