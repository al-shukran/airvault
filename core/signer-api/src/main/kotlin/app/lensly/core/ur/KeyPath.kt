package app.lensly.core.ur

/** The CBOR tag of a keypath, as the eth-sign, sol-sign and algo-sign requests carry it. */
const val KEYPATH_TAG = 304L

private const val MAX_PATH_STEPS = 16
private const val MAX_INDEX = Int.MAX_VALUE.toLong()
private const val MAX_FINGERPRINT = 0xFFFFFFFFL

/** One derivation step. For example, 44' is PathStep(index = 44, hardened = true). */
data class PathStep(val index: Int, val hardened: Boolean)

/**
 * Reads a tag 304 keypath and returns its steps with the source fingerprint, which is null when the wallet left it out.
 * The request decoders share it, and SolSignRequest adds its own hardened-only rule.
 */
fun keyPathOf(item: Cbor): Pair<List<PathStep>, Int?> {
    val tagged = item as? Cbor.Tagged
    if (tagged == null || tagged.tag != KEYPATH_TAG) malformed("derivationPath must be a tag 304 keypath")
    val fields = fieldsOf(tagged.value)

    val components = (fields[1L] as? Cbor.Arr ?: malformed("keypath components are missing")).items
    if (components.isEmpty() || components.size % 2 != 0 || components.size > 2 * MAX_PATH_STEPS) {
        malformed("keypath has an invalid component count")
    }
    // Wildcard, range and pair components are refused. Only plain index steps can be signed.
    val steps = components.chunked(2).map { (index, hardened) ->
        val number = uintOf(index, "path index")
        if (number > MAX_INDEX) malformed("path index out of range")
        val isHardened = (hardened as? Cbor.Bool ?: malformed("path step is missing its hardened flag")).value
        PathStep(number.toInt(), isHardened)
    }

    val sourceFingerprint = fields[2L]?.let {
        val number = uintOf(it, "source fingerprint")
        if (number > MAX_FINGERPRINT) malformed("source fingerprint exceeds 32 bits")
        number.toInt()
    }
    return steps to sourceFingerprint
}

private fun fieldsOf(root: Cbor): Map<Long, Cbor> {
    val dict = root as? Cbor.Dict ?: malformed("payload is not a CBOR map")
    val fields = HashMap<Long, Cbor>()
    for ((key, value) in dict.entries) {
        val number = uintOf(key, "map key")
        if (fields.put(number, value) != null) malformed("duplicate map key $number")
    }
    return fields
}

private fun uintOf(item: Cbor, name: String): Long =
    (item as? Cbor.Uint ?: malformed("$name must be an unsigned integer")).value
