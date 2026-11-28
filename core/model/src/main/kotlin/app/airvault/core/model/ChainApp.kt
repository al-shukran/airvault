package app.airvault.core.model

/**
 * A chain app as the core sees it: its manifest and the address rules of its chain (spec 3.1). The signer and wallet
 * sides extend this interface.
 */
interface ChainApp {
    val manifest: ChainManifest
    val addresses: AddressCodec
}

/** Address rules of one chain app. Every address passes [parse] before it becomes part of an account id (spec 4). */
interface AddressCodec {
    /** Decodes [text], checks charset, length, checksum, case and network, and returns the normalised form. */
    fun parse(chain: ChainId, text: String): AddressResult

    /** The address for [publicKey] in the requested [kind]. */
    fun fromPublicKey(chain: ChainId, publicKey: ByteArray, kind: AddressKind = AddressKind.DEFAULT): String
}

sealed interface AddressResult {
    /** [embedded] holds data packed into the address, such as an XRPL tag, for the field it belongs to (spec 3.1). */
    data class Valid(
        val normalised: String,
        val kind: AddressKind,
        val embedded: Map<FieldKey, String> = emptyMap(),
    ) : AddressResult

    data class Invalid(val reason: AddressError) : AddressResult
}

enum class AddressError { CHARSET, LENGTH, CHECKSUM, MIXED_CASE, WRONG_NETWORK, WRONG_VERSION, NOT_ON_CURVE }

/** Address kinds. Bitcoin-family script types are added with that chain app (spec 3.3.1). */
enum class AddressKind { DEFAULT }
