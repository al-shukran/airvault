package app.lumio.transactions

private const val HASH_HEAD = 6
private const val HASH_TAIL = 4

/**
 * A transaction hash cut to its first six and last four characters, such as "0xa1b2…c3d4". A hash is not an address, so it is not grouped, and this is the
 * one short form that does not go through AddressGroups (scripts/ci/check-address-short.sh exempts this file).
 */
fun shortHash(hash: String): String = if (hash.length <= HASH_HEAD + HASH_TAIL) hash else hash.take(HASH_HEAD) + "…" + hash.takeLast(HASH_TAIL)
