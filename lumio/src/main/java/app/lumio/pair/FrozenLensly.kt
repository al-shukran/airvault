package app.lumio.pair

/**
 * What a frozen Lensly accepts, keyed on pairing format 7 (see "Frozen Lensly contract" in docs/creating-airvault-apps.md). A frozen Lensly is
 * never updated, so Lumio's requests must stay inside this table: a request type or a key outside it is refused by that Lensly.
 */
object FrozenLensly {
    /** The pairing format a frozen Lensly writes and reads. Lumio keeps reading it. */
    const val PAIRING_VERSION = 7L

    /**
     * The request UR types a frozen Lensly reads, with the map keys each one reads. Keys from `RESERVED_KEYS_FROM` are ignored, so they need no
     * entry. A PSBT is not a map, so its entries name no keys.
     */
    val REQUEST_KEYS: Map<String, Set<Long>> = mapOf(
        "eth-sign-request" to (1L..7L).toSet(),
        "sol-sign-request" to (1L..7L).toSet(),
        "algo-sign-request" to (1L..6L).toSet(),
        "crypto-psbt" to emptySet(),
        "psbt" to emptySet(),
    )
}
