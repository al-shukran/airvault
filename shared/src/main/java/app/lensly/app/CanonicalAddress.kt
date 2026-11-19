package app.lensly.app

import app.airvault.chains.algorand.AlgorandAddressCodec
import app.airvault.chains.algorand.algorandMainnet
import app.airvault.chains.algorand.algorandManifest
import app.airvault.chains.bitcoin.BitcoinAddressCodec
import app.airvault.chains.bitcoin.bitcoinMainnet
import app.airvault.chains.bitcoin.bitcoinManifest
import app.airvault.chains.bitcoin.bitcoinTestnet4
import app.airvault.chains.evm.EvmAddressCodec
import app.airvault.chains.evm.ethereumMainnet
import app.airvault.chains.evm.evmManifest
import app.airvault.chains.solana.SolanaAddressCodec
import app.airvault.chains.solana.SOLANA_MAINNET
import app.airvault.chains.solana.solanaManifest
import app.airvault.core.model.AddressCodec
import app.airvault.core.model.AddressResult
import app.airvault.core.model.ChainApp
import app.airvault.core.model.ChainId
import app.airvault.core.model.ChainManifest
import app.airvault.core.model.ChainRegistry
import app.lensly.core.btc.BtcNetwork

/** The address rules of one chain, as the [ChainApp] that the registry indexes. */
private class AddressRules(override val manifest: ChainManifest, override val addresses: AddressCodec) : ChainApp

/**
 * The address rules of the four chains, in the order a lookup tries them: the order the enum this replaces listed them in, which decides
 * an address that more than one chain could read. The Address Book, the reviews and the pairing check take every address through these
 * rules. The phone apps' installed lists stay the lists of signing and wallet apps. This one holds the address rules alone.
 */
private val addressRules: ChainRegistry<ChainApp> = ChainRegistry(
    listOf(
        AddressRules(evmManifest, EvmAddressCodec),
        AddressRules(bitcoinManifest, BitcoinAddressCodec),
        AddressRules(solanaManifest, SolanaAddressCodec),
        AddressRules(algorandManifest, AlgorandAddressCodec),
    ),
)

/**
 * The chain a family's addresses are named on, for the places that mean the family and not one network. Lookups match on the family
 * (see [addressBookEntryFor]), so the reference of the id names the family's main network and nothing more.
 */
val EVM_FAMILY: ChainId = ethereumMainnet
val BITCOIN_FAMILY: ChainId = bitcoinMainnet
val SOLANA_FAMILY: ChainId = SOLANA_MAINNET
val ALGORAND_FAMILY: ChainId = algorandMainnet

/** The canonical form of [text] when the rules of [chain] take it as an address on [chain], or null. */
private fun normalisedOn(chain: ChainId, text: String): String? {
    val rules = addressRules.byNamespace(chain.namespace).firstOrNull() ?: return null
    return (rules.addresses.parse(chain, text) as? AddressResult.Valid)?.normalised
}

/**
 * The chain [text] is a valid address on, or null when no installed address rules take it. The networks are tried in the order
 * [addressRules] lists them. The four chains share no valid address: an EVM address starts with 0x, which none of the others can, and
 * the lengths and checksums of the others tell them apart.
 */
fun chainOfAddress(text: String): ChainId? = addressRules.networks().firstOrNull { normalisedOn(it.chainId, text) != null }?.chainId

/**
 * The canonical form of [text] when it is a valid address of the family of [chain], or null. This is the form the Address Book keeps.
 * Bitcoin's mainnet and testnet4 are one family, so a testnet4 address is valid on the Bitcoin family id.
 */
fun canonicalAddressOn(text: String, chain: ChainId): String? =
    chainOfAddress(text)?.takeIf { it.namespace == chain.namespace }?.let { normalisedOn(it, text) }

/**
 * The canonical form of [text], trimmed, or null when it is not a valid address of Ethereum, Bitcoin, Solana or Algorand. An EVM address
 * is in its checksum form, and a Bitcoin address as bitcoin-kmp writes it. Solana and Algorand addresses are kept exactly as written.
 */
fun canonicalAddressOf(text: String): String? = text.trim().let { trimmed ->
    chainOfAddress(trimmed)?.let { normalisedOn(it, trimmed) }
}

/** The Bitcoin network [text] is a valid address of, or null when it is not an address of Mainnet or Testnet4. */
fun btcNetworkOf(text: String): BtcNetwork? = when (chainOfAddress(text)) {
    bitcoinMainnet -> BtcNetwork.MAINNET
    bitcoinTestnet4 -> BtcNetwork.TESTNET4
    else -> null
}
