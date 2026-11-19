package app.airvault.chains.solana

import app.airvault.core.model.AddressCodec
import app.airvault.core.model.AddressError
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.model.ChainId
import fr.acinq.bitcoin.Base58

private const val SOLANA_NAMESPACE = "solana"
private val SOLANA_ADDRESS = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")

/** A Solana address is the base58 of a 32-byte public key. */
private const val SOLANA_KEY_BYTES = 32

/** The Solana address rules (spec 3.1). A Solana address is the same on every Solana network. */
object SolanaAddressCodec : AddressCodec {
    override fun parse(chain: ChainId, text: String): AddressResult {
        if (chain.namespace != SOLANA_NAMESPACE) return AddressResult.Invalid(AddressError.WRONG_NETWORK)
        solanaAddressOf(text)?.let { return AddressResult.Valid(it, AddressKind.DEFAULT) }
        val reason = when {
            text.length !in SOLANA_ADDRESS_LENGTHS -> AddressError.LENGTH
            !SOLANA_ADDRESS.matches(text) -> AddressError.CHARSET
            else -> AddressError.LENGTH // base58 that decodes to other than a 32-byte key
        }
        return AddressResult.Invalid(reason)
    }

    override fun fromPublicKey(chain: ChainId, publicKey: ByteArray, kind: AddressKind): String {
        require(publicKey.size == SOLANA_KEY_BYTES) { "A Solana public key is $SOLANA_KEY_BYTES bytes" }
        return Base58.encode(publicKey)
    }
}

private val SOLANA_ADDRESS_LENGTHS = 32..44

/** The Solana address [text], or null when it is not one: base58 that decodes to a 32-byte public key. */
fun solanaAddressOf(text: String): String? {
    if (!SOLANA_ADDRESS.matches(text)) return null
    val key = runCatching { Base58.decode(text) }.getOrNull() ?: return null
    return if (key.size == SOLANA_KEY_BYTES) text else null
}
