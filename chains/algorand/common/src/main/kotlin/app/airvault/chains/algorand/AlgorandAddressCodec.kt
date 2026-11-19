package app.airvault.chains.algorand

import app.airvault.core.model.AddressCodec
import app.airvault.core.model.AddressError
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.model.ChainId

private val ALGORAND_ADDRESS = Regex("[A-Z2-7]{${AlgoAddress.ADDRESS_LENGTH}}")

/**
 * The Algorand address rules (spec 3.1). An address is the same on every Algorand network, so [parse] checks only the namespace.
 * Letters must be upper case: a lower-case address is not an address.
 */
object AlgorandAddressCodec : AddressCodec {
    override fun parse(chain: ChainId, text: String): AddressResult {
        if (chain.namespace != "algorand") return AddressResult.Invalid(AddressError.WRONG_NETWORK)
        val reason = when {
            text.length != AlgoAddress.ADDRESS_LENGTH -> AddressError.LENGTH
            !ALGORAND_ADDRESS.matches(text) -> AddressError.CHARSET
            algorandAddressOf(text) == null -> AddressError.CHECKSUM
            else -> return AddressResult.Valid(text, AddressKind.DEFAULT)
        }
        return AddressResult.Invalid(reason)
    }

    /** The address of a 32-byte Ed25519 public key. */
    override fun fromPublicKey(chain: ChainId, publicKey: ByteArray, kind: AddressKind): String = AlgoAddress.encode(publicKey)
}

/** The Algorand address [text], or null when it is not one: 58 upper-case base32 characters with a matching checksum. */
fun algorandAddressOf(text: String): String? = text.takeIf { runCatching { AlgoAddress.decode(it) }.isSuccess }
