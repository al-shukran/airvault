package app.airvault.chains.bitcoin

import app.airvault.core.model.AddressCodec
import app.airvault.core.model.AddressError
import app.airvault.core.model.AddressKind
import app.airvault.core.model.AddressResult
import app.airvault.core.model.ChainId
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey

/**
 * The Bitcoin address rules (spec 3.1): bech32 and bech32m, and base58check, on mainnet and testnet4. A testnet4 address is not a
 * mainnet address, so [parse] checks the network the address is named on.
 */
object BitcoinAddressCodec : AddressCodec {
    override fun parse(chain: ChainId, text: String): AddressResult {
        val network = networkOf(chain) ?: return AddressResult.Invalid(AddressError.WRONG_NETWORK)
        btcAddressOn(text, network)?.let { return AddressResult.Valid(it, AddressKind.DEFAULT) }
        // The reason is the best one the decoder gives: an address of another network, or text that does not decode at all.
        val elsewhere = NETWORKS.any { (_, other) -> other != network && btcAddressOn(text, other) != null }
        return AddressResult.Invalid(if (elsewhere) AddressError.WRONG_NETWORK else AddressError.CHECKSUM)
    }

    /** The native segwit (P2WPKH) address of [publicKey], the 33-byte compressed key. Native segwit is the default kind. */
    override fun fromPublicKey(chain: ChainId, publicKey: ByteArray, kind: AddressKind): String {
        val network = requireNotNull(networkOf(chain)) { "$chain is not a Bitcoin network" }
        return Bitcoin.computeP2WpkhAddress(PublicKey(publicKey), network.chainHash)
    }
}

/** The networks the address rules know, in the order a lookup tries them, with the bitcoin-kmp chain each one is. */
private val NETWORKS: List<Pair<ChainId, Chain>> = listOf(bitcoinMainnet to Chain.Mainnet, bitcoinTestnet4 to Chain.Testnet4)

private fun networkOf(chain: ChainId): Chain? = NETWORKS.firstOrNull { it.first == chain }?.second

/**
 * The canonical form of [text] as an address of [network], or null when it is not one. A bech32 address is the same address in either case,
 * so an upper-case one, as a QR code gives it, is lowered first. The canonical form of a bech32 address is lower case.
 */
fun btcAddressOn(text: String, network: Chain): String? {
    if (text.isEmpty()) return null
    // No base58 address starts with BC1 or TB1, so only these prefixes are lowered.
    val input = if (text.startsWith("BC1") || text.startsWith("TB1")) text.lowercase() else text
    val script = Bitcoin.addressToPublicKeyScript(network.chainHash, input).right ?: return null
    return Bitcoin.addressFromPublicKeyScript(network.chainHash, script).right
}
